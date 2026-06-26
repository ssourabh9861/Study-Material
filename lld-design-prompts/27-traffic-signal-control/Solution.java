/* =============================================================================
 * Traffic Signal Control System  —  Single-file LLD review / revision artifact
 * =============================================================================
 *
 * THIS FILE IS FOR READING & REVISION. It is complete and correct-by-inspection;
 * you do NOT need to compile or run it. The main() only ILLUSTRATES usage.
 *
 * -----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -----------------------------------------------------------------------------
 * Functional:
 *   1. Single intersection or a synchronized network ("green wave")? (single first)
 *   2. Fixed 4-way (N/S/E/W) or arbitrary N approaches? Pedestrian / turn signals?
 *   3. Are opposing flows grouped into PHASES (N+S green, then E+W green), or
 *      independent per-direction greens?
 *   4. Color set: just Red/Yellow/Green, or also flashing-red, all-red clearance,
 *      pedestrian WALK / DONT-WALK, protected-left arrows?
 *   5. Transition trigger: time-based, sensor/actuated (extend green while cars
 *      detected), or time-of-day plans? Need all three, pluggable?
 *   6. Emergency-vehicle / transit preemption (force a corridor green safely)?
 *   7. Manual operator control: pause / single-step / force a phase?
 * Non-functional:
 *   8. Thread-safety: timer thread drives transitions while external threads issue
 *      commands? (assume YES)
 *   9. Persistence of timing config across restart? (out of scope this round)
 *  10. Who consumes change events — UI, logger, metrics? (drives Observer)
 *  11. Timing precision (whole seconds?) and real vs virtual clock for tests?
 *  12. Failure mode: fail to all-flashing-red? (safe default)
 * Scope-narrowing assumption stated to interviewer:
 *  13. "One intersection, configurable directions grouped into pre-validated
 *      conflict-free phases, pluggable timing strategy, emergency override,
 *      Observer event bus, injected Clock/Scheduler for determinism, and
 *      thread-safety. Network sync, pedestrian/turn signals, and persistence are
 *      designed-for but not fully implemented."
 *
 * -----------------------------------------------------------------------------
 * PATTERNS APPLIED
 *   - State    : SignalState {Red,Green,Yellow} — legal cycle G->Y->R->G only.
 *   - Strategy : TimingStrategy {Fixed, Actuated, TimeOfDay} — hot-swappable.
 *   - Observer : SignalObserver {ConsoleDashboard, AuditLog, Metrics}.
 *   - Singleton: factory-guarded, one TrafficController per intersection id.
 *   - Command  : preempt/clearPreemption/pause/resume as safe queued actions.
 * SAFETY INVARIANT (centralized in Intersection.applyPhase):
 *   never Green->Green directly; always Yellow -> all-red clearance -> Green.
 * CONCURRENCY: one ReentrantLock per controller; events fired OUTSIDE the lock;
 *   Clock + Scheduler injected so tests advance virtual time deterministically.
 * ============================================================================= */

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    /* =========================================================================
     * Value objects / enums
     * ========================================================================= */

    enum Color { RED, GREEN, YELLOW, FLASHING_RED }

    enum Direction { NORTH, SOUTH, EAST, WEST }

    enum EventType { SIGNAL_CHANGED, PHASE_CHANGED, PREEMPTED, PREEMPTION_CLEARED, FAULT, RECOVERED }

    enum ControllerMode { STOPPED, RUNNING, PAUSED, PREEMPTED, SAFE_MODE }

    /** Immutable description of something that changed; passed to observers. */
    static final class SignalEvent {
        final EventType type;
        final String signalId;     // null for non-signal events
        final Color from;          // null where N/A
        final Color to;            // null where N/A
        final String detail;
        final long timestampMillis;

        SignalEvent(EventType type, String signalId, Color from, Color to, String detail, long ts) {
            this.type = type;
            this.signalId = signalId;
            this.from = from;
            this.to = to;
            this.detail = detail;
            this.timestampMillis = ts;
        }

        static SignalEvent signalChanged(String id, Color from, Color to, long ts) {
            return new SignalEvent(EventType.SIGNAL_CHANGED, id, from, to, null, ts);
        }
        static SignalEvent info(EventType type, String detail, long ts) {
            return new SignalEvent(type, null, null, null, detail, ts);
        }

        @Override public String toString() {
            if (type == EventType.SIGNAL_CHANGED) {
                return "[" + timestampMillis + "ms] SIGNAL " + signalId + ": " + from + " -> " + to;
            }
            return "[" + timestampMillis + "ms] " + type + (detail == null ? "" : ": " + detail);
        }
    }

    /* =========================================================================
     * Clock + Scheduler abstractions (injected -> deterministic tests)
     * ========================================================================= */

    interface Clock { long nowMillis(); }

    /** Real wall-clock for production. */
    static final class SystemClock implements Clock {
        @Override public long nowMillis() { return System.currentTimeMillis(); }
    }

    /** Test clock: virtual time the test advances by hand. Thread-safe. */
    static final class ManualClock implements Clock {
        private final AtomicLong now = new AtomicLong(0);
        @Override public long nowMillis() { return now.get(); }
        void advanceSeconds(long s) { now.addAndGet(s * 1000L); }
    }

    /**
     * Scheduler abstraction. The controller schedules its next tick after the
     * current phase duration. In production this is backed by an executor; in
     * tests by a manual scheduler the test drives.
     */
    interface Scheduler {
        /** Run task once after delaySec. Returns a handle that can be cancelled. */
        Cancellable scheduleAfter(int delaySec, Runnable task);
        void shutdown();
    }

    interface Cancellable { void cancel(); }

    /** Production scheduler backed by a single-thread scheduled executor. */
    static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService exec =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "traffic-timer");
                    t.setDaemon(true);
                    return t;
                });
        @Override public Cancellable scheduleAfter(int delaySec, Runnable task) {
            ScheduledFuture<?> f = exec.schedule(task, delaySec, TimeUnit.SECONDS);
            return () -> f.cancel(false);
        }
        @Override public void shutdown() { exec.shutdownNow(); }
    }

    /**
     * Manual scheduler for deterministic demos/tests. It remembers only the most
     * recently scheduled task (the controller always has at most one pending
     * tick) and lets the test fire it explicitly via fireDue().
     */
    static final class ManualScheduler implements Scheduler {
        private Runnable pending;
        private int dueInSec;
        @Override public Cancellable scheduleAfter(int delaySec, Runnable task) {
            this.pending = task;
            this.dueInSec = delaySec;
            return () -> { if (this.pending == task) this.pending = null; };
        }
        /** Fire the pending tick (simulates the timer firing). Returns false if none. */
        boolean fireDue() {
            Runnable r = pending;
            pending = null;
            if (r != null) { r.run(); return true; }
            return false;
        }
        int nextDelaySec() { return dueInSec; }
        @Override public void shutdown() { pending = null; }
    }

    /* =========================================================================
     * STATE pattern — signal colors
     * ========================================================================= */

    interface SignalState {
        Color color();
        SignalState next();            // legal successor in the lifecycle
        int defaultDurationSec();      // nominal time this color is shown
        boolean isGo();                // true only for GREEN
    }

    static final class GreenState implements SignalState {
        static final int DEFAULT = 30;
        @Override public Color color() { return Color.GREEN; }
        @Override public SignalState next() { return new YellowState(); } // G -> Y
        @Override public int defaultDurationSec() { return DEFAULT; }
        @Override public boolean isGo() { return true; }
    }

    static final class YellowState implements SignalState {
        static final int DEFAULT = 4;
        @Override public Color color() { return Color.YELLOW; }
        @Override public SignalState next() { return new RedState(); }     // Y -> R
        @Override public int defaultDurationSec() { return DEFAULT; }
        @Override public boolean isGo() { return false; }
    }

    static final class RedState implements SignalState {
        static final int DEFAULT = 1; // all-red clearance gap when used as such
        @Override public Color color() { return Color.RED; }
        @Override public SignalState next() { return new GreenState(); }   // R -> G (only when phase selects it)
        @Override public int defaultDurationSec() { return DEFAULT; }
        @Override public boolean isGo() { return false; }
    }

    /** Fail-safe state (designed-for fault handling). Flashing red = all-way stop. */
    static final class FlashingRedState implements SignalState {
        @Override public Color color() { return Color.FLASHING_RED; }
        @Override public SignalState next() { return new RedState(); }     // recover to solid red
        @Override public int defaultDurationSec() { return Integer.MAX_VALUE; }
        @Override public boolean isGo() { return false; }
    }

    /* =========================================================================
     * Signal — a single signal head, driven by its SignalState
     * ========================================================================= */

    static final class Signal {
        private final String id;
        private final Direction direction;
        private SignalState state;

        Signal(String id, Direction direction) {
            this.id = id;
            this.direction = direction;
            this.state = new RedState(); // every signal boots RED (safe default)
        }

        String id() { return id; }
        Direction direction() { return direction; }
        Color currentColor() { return state.color(); }
        boolean isGo() { return state.isGo(); }

        /** Advance one step along the natural lifecycle (G->Y->R->G). */
        SignalEvent advance(long ts) {
            Color from = state.color();
            state = state.next();
            return SignalEvent.signalChanged(id, from, state.color(), ts);
        }

        /** Force a specific state (used by the intersection's safe sequence). */
        SignalEvent transitionTo(SignalState target, long ts) {
            Color from = state.color();
            this.state = target;
            return SignalEvent.signalChanged(id, from, target.color(), ts);
        }
    }

    /* =========================================================================
     * Phase — a named, conflict-free set of signals allowed GREEN together
     * ========================================================================= */

    static final class Phase {
        private final String id;
        private final Set<String> greenSignalIds; // signals that go GREEN in this phase
        private final int minGreenSec;
        private final int maxGreenSec;

        Phase(String id, Set<String> greenSignalIds, int minGreenSec, int maxGreenSec) {
            if (minGreenSec <= 0 || maxGreenSec < minGreenSec)
                throw new IllegalArgumentException("invalid green bounds for phase " + id);
            this.id = id;
            this.greenSignalIds = Collections.unmodifiableSet(new LinkedHashSet<>(greenSignalIds));
            this.minGreenSec = minGreenSec;
            this.maxGreenSec = maxGreenSec;
        }
        String id() { return id; }
        Set<String> greenSignalIds() { return greenSignalIds; }
        boolean servesSignal(String signalId) { return greenSignalIds.contains(signalId); }
        int minGreenSec() { return minGreenSec; }
        int maxGreenSec() { return maxGreenSec; }
        @Override public String toString() { return "Phase(" + id + " green=" + greenSignalIds + ")"; }
    }

    /* =========================================================================
     * Intersection — owns signals + phases; ENFORCES the safety invariant
     * ========================================================================= */

    static final class Intersection {
        private final String id;
        private final Map<String, Signal> signals = new LinkedHashMap<>();
        private final List<Phase> phases = new ArrayList<>();
        private Phase activePhase; // null until first phase applied

        // Timing knobs for the safe sequence; defaults are sane.
        private int yellowSec = YellowState.DEFAULT;
        private int allRedClearanceSec = 2;

        Intersection(String id) { this.id = id; }

        String id() { return id; }

        Intersection addSignal(Signal s) { signals.put(s.id(), s); return this; }
        Intersection addPhase(Phase p) { phases.add(p); return this; }
        Intersection setSafeSequenceTimings(int yellowSec, int allRedClearanceSec) {
            this.yellowSec = yellowSec; this.allRedClearanceSec = allRedClearanceSec; return this;
        }

        List<Phase> phases() { return Collections.unmodifiableList(phases); }
        java.util.Collection<Signal> signalsView() { return Collections.unmodifiableCollection(signals.values()); }
        Phase activePhase() { return activePhase; }
        int yellowSec() { return yellowSec; }
        int allRedClearanceSec() { return allRedClearanceSec; }

        Optional<Phase> phaseById(String pid) {
            return phases.stream().filter(p -> p.id().equals(pid)).findFirst();
        }

        /**
         * Fail-fast configuration check. Every green-signal id in every phase must
         * reference a real signal. (Phases are conflict-free by construction: each
         * phase lists only signals that may move together; the controller never
         * runs two phases at once, so cross-phase conflicts cannot occur.)
         */
        void validateConflictFree() {
            if (phases.isEmpty()) throw new IllegalStateException("no phases configured");
            for (Phase p : phases) {
                for (String sid : p.greenSignalIds()) {
                    if (!signals.containsKey(sid))
                        throw new IllegalStateException("phase " + p.id() + " references unknown signal " + sid);
                }
            }
        }

        /** Drive every signal to RED immediately (used for clearance + fault). */
        List<SignalEvent> allRed(long ts) {
            List<SignalEvent> out = new ArrayList<>();
            for (Signal s : signals.values()) {
                if (s.currentColor() != Color.RED)
                    out.add(s.transitionTo(new RedState(), ts));
            }
            return out;
        }

        /** Drive every signal to FLASHING RED (fail-safe mode). */
        List<SignalEvent> allFlashingRed(long ts) {
            List<SignalEvent> out = new ArrayList<>();
            for (Signal s : signals.values())
                out.add(s.transitionTo(new FlashingRedState(), ts));
            return out;
        }

        /**
         * THE SAFETY-CRITICAL METHOD. Transition from the current active phase to
         * target, NEVER going green->green directly:
         *   1) any currently-green signal not in target -> YELLOW (clearance)
         *   2) all signals -> RED (all-red clearance gap)
         *   3) target phase signals -> GREEN
         * Each sub-step advances the virtual clock by the relevant duration so the
         * emitted events carry meaningful timestamps. Returns events in order.
         *
         * (clockTick advances the injected clock; passed in so this object stays
         *  free of any direct time dependency.)
         */
        List<SignalEvent> applyPhase(Phase target, ClockTick clockTick) {
            List<SignalEvent> events = new ArrayList<>();

            // Step 1: yellow-out outgoing greens (those green now but not in target).
            boolean anyYellow = false;
            for (Signal s : signals.values()) {
                boolean isGreenNow = s.currentColor() == Color.GREEN;
                if (isGreenNow && !target.servesSignal(s.id())) {
                    events.add(s.transitionTo(new YellowState(), clockTick.now()));
                    anyYellow = true;
                }
            }
            if (anyYellow) clockTick.advance(yellowSec);

            // Step 2: all-red clearance gap.
            events.addAll(allRed(clockTick.now()));
            if (activePhase != null) clockTick.advance(allRedClearanceSec);

            // Step 3: raise target phase to green.
            for (String sid : target.greenSignalIds()) {
                Signal s = signals.get(sid);
                if (s.currentColor() != Color.GREEN)
                    events.add(s.transitionTo(new GreenState(), clockTick.now()));
            }
            this.activePhase = target;
            events.add(SignalEvent.info(EventType.PHASE_CHANGED, "active=" + target.id(), clockTick.now()));
            return events;
        }
    }

    /** Tiny mutable view over the injected clock used inside applyPhase. */
    interface ClockTick {
        long now();
        void advance(int sec);
    }

    /* =========================================================================
     * STRATEGY pattern — timing plans
     * ========================================================================= */

    /** Context the strategy may inspect to decide timing. */
    static final class TimingContext {
        final Intersection intersection;
        final Phase currentPhase;            // may be null at start
        final long nowMillis;
        final Map<String, Integer> demandByPhaseId; // sensor demand (cars waiting), optional

        TimingContext(Intersection intersection, Phase currentPhase, long nowMillis,
                      Map<String, Integer> demandByPhaseId) {
            this.intersection = intersection;
            this.currentPhase = currentPhase;
            this.nowMillis = nowMillis;
            this.demandByPhaseId = demandByPhaseId;
        }
    }

    interface TimingStrategy {
        /** Which phase should run next (round-robin / actuated / time-of-day). */
        Phase nextPhase(TimingContext ctx);
        /** How long the chosen phase's green should last (clamped to phase min/max). */
        int greenDurationSec(Phase phase, TimingContext ctx);
        String name();
    }

    /** Round-robin through the configured phases with fixed green per phase. */
    static final class FixedTimerStrategy implements TimingStrategy {
        private final int fixedGreenSec;
        FixedTimerStrategy(int fixedGreenSec) { this.fixedGreenSec = fixedGreenSec; }

        @Override public Phase nextPhase(TimingContext ctx) {
            List<Phase> phases = ctx.intersection.phases();
            if (ctx.currentPhase == null) return phases.get(0);
            int idx = indexOf(phases, ctx.currentPhase);
            return phases.get((idx + 1) % phases.size());
        }
        @Override public int greenDurationSec(Phase phase, TimingContext ctx) {
            return clamp(fixedGreenSec, phase.minGreenSec(), phase.maxGreenSec());
        }
        @Override public String name() { return "FixedTimer(" + fixedGreenSec + "s)"; }
    }

    /**
     * Sensor/actuated: round-robin order, but green length scales with detected
     * demand (cars waiting), clamped to [minGreen, maxGreen] so a busy direction
     * can't starve cross traffic. If no demand info, behaves like minGreen.
     */
    static final class ActuatedTimingStrategy implements TimingStrategy {
        private final int secondsPerCar;
        private final int baseGreenSec;
        ActuatedTimingStrategy(int baseGreenSec, int secondsPerCar) {
            this.baseGreenSec = baseGreenSec; this.secondsPerCar = secondsPerCar;
        }
        @Override public Phase nextPhase(TimingContext ctx) {
            List<Phase> phases = ctx.intersection.phases();
            if (ctx.currentPhase == null) return phases.get(0);
            int idx = indexOf(phases, ctx.currentPhase);
            return phases.get((idx + 1) % phases.size());
        }
        @Override public int greenDurationSec(Phase phase, TimingContext ctx) {
            int demand = ctx.demandByPhaseId == null ? 0
                    : ctx.demandByPhaseId.getOrDefault(phase.id(), 0);
            int want = baseGreenSec + demand * secondsPerCar;
            return clamp(want, phase.minGreenSec(), phase.maxGreenSec());
        }
        @Override public String name() { return "Actuated(base=" + baseGreenSec + "s,+/car=" + secondsPerCar + "s)"; }
    }

    /**
     * Time-of-day: delegates to a day strategy or a night strategy depending on
     * the hour derived from the clock. (Composition of strategies.)
     */
    static final class TimeOfDayStrategy implements TimingStrategy {
        private final TimingStrategy day;
        private final TimingStrategy night;
        private final int dayStartHour, nightStartHour;
        TimeOfDayStrategy(TimingStrategy day, TimingStrategy night, int dayStartHour, int nightStartHour) {
            this.day = day; this.night = night;
            this.dayStartHour = dayStartHour; this.nightStartHour = nightStartHour;
        }
        private TimingStrategy active(long nowMillis) {
            long hour = (nowMillis / 1000L / 3600L) % 24L;
            boolean isDay = hour >= dayStartHour && hour < nightStartHour;
            return isDay ? day : night;
        }
        @Override public Phase nextPhase(TimingContext ctx) { return active(ctx.nowMillis).nextPhase(ctx); }
        @Override public int greenDurationSec(Phase phase, TimingContext ctx) {
            return active(ctx.nowMillis).greenDurationSec(phase, ctx);
        }
        @Override public String name() { return "TimeOfDay(day=" + day.name() + ",night=" + night.name() + ")"; }
    }

    private static int indexOf(List<Phase> phases, Phase p) {
        for (int i = 0; i < phases.size(); i++) if (phases.get(i).id().equals(p.id())) return i;
        return -1;
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /* =========================================================================
     * OBSERVER pattern — change-event sinks
     * ========================================================================= */

    interface SignalObserver { void onEvent(SignalEvent e); }

    static final class ConsoleDashboardObserver implements SignalObserver {
        private final String name;
        ConsoleDashboardObserver(String name) { this.name = name; }
        @Override public void onEvent(SignalEvent e) {
            System.out.println("  [" + name + "] " + e);
        }
    }

    static final class AuditLogObserver implements SignalObserver {
        private final List<SignalEvent> log = new CopyOnWriteArrayList<>();
        @Override public void onEvent(SignalEvent e) { log.add(e); }
        int size() { return log.size(); }
        List<SignalEvent> entries() { return Collections.unmodifiableList(new ArrayList<>(log)); }
    }

    static final class MetricsObserver implements SignalObserver {
        private final Map<EventType, Integer> counts = new ConcurrentHashMap<>();
        @Override public void onEvent(SignalEvent e) {
            counts.merge(e.type, 1, Integer::sum);
        }
        Map<EventType, Integer> snapshot() { return new LinkedHashMap<>(counts); }
    }

    /* =========================================================================
     * TrafficController — orchestrator (factory-guarded singleton per intersection)
     * ========================================================================= */

    static final class TrafficController {
        private final Intersection intersection;
        private volatile TimingStrategy timingStrategy;
        private final Clock clock;
        private final Scheduler scheduler;
        private final List<SignalObserver> observers = new CopyOnWriteArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();

        private ControllerMode mode = ControllerMode.STOPPED;
        private Cancellable pendingTick;
        private Phase preemptedPhase; // remembered while preempted (for context)

        // Sensor demand snapshot (set by sensors; read by actuated strategy).
        private volatile Map<String, Integer> demand = Collections.emptyMap();

        TrafficController(Intersection intersection, TimingStrategy strategy,
                          Clock clock, Scheduler scheduler) {
            this.intersection = intersection;
            this.timingStrategy = strategy;
            this.clock = clock;
            this.scheduler = scheduler;
            this.intersection.validateConflictFree(); // fail fast at construction
        }

        /* ---- observers ---- */
        void addObserver(SignalObserver o) { observers.add(o); }
        void removeObserver(SignalObserver o) { observers.remove(o); }

        /* ---- sensors (feeds actuated strategy) ---- */
        void setDemand(Map<String, Integer> demandByPhaseId) {
            this.demand = new LinkedHashMap<>(demandByPhaseId);
        }

        /* ---- lifecycle ---- */
        void start() {
            List<SignalEvent> events;
            lock.lock();
            try {
                if (mode == ControllerMode.RUNNING) return;
                mode = ControllerMode.RUNNING;
                Phase first = timingStrategy.nextPhase(ctx(null));
                events = intersection.applyPhase(first, tick());
                scheduleNext(first);
            } finally { lock.unlock(); }
            notifyAll(events); // OUTSIDE the lock
        }

        void stop() {
            lock.lock();
            try {
                mode = ControllerMode.STOPPED;
                cancelPending();
            } finally { lock.unlock(); }
        }

        void pause() {
            lock.lock();
            try {
                if (mode == ControllerMode.RUNNING) { mode = ControllerMode.PAUSED; cancelPending(); }
            } finally { lock.unlock(); }
        }

        void resume() {
            List<SignalEvent> events = null;
            lock.lock();
            try {
                if (mode == ControllerMode.PAUSED) {
                    mode = ControllerMode.RUNNING;
                    Phase cur = intersection.activePhase();
                    scheduleNext(cur); // resume the cycle from the current phase
                }
            } finally { lock.unlock(); }
            if (events != null) notifyAll(events);
        }

        /** Hot-swap the timing strategy atomically; applies from next phase. */
        void setTimingStrategy(TimingStrategy newStrategy) {
            lock.lock();
            try { this.timingStrategy = newStrategy; }
            finally { lock.unlock(); }
        }

        /* ---- the timer-driven advance ---- */
        void tick() {
            List<SignalEvent> events;
            lock.lock();
            try {
                if (mode != ControllerMode.RUNNING) return; // ignore stray ticks
                Phase current = intersection.activePhase();
                Phase next = timingStrategy.nextPhase(ctx(current));
                events = intersection.applyPhase(next, tickHelper());
                scheduleNext(next);
            } finally { lock.unlock(); }
            notifyAll(events);
        }

        /* ---- COMMAND: emergency preemption ---- */
        void preempt(String phaseId) {
            List<SignalEvent> events = new ArrayList<>();
            lock.lock();
            try {
                Phase target = intersection.phaseById(phaseId)
                        .orElseThrow(() -> new IllegalArgumentException("unknown phase " + phaseId));
                cancelPending(); // stop auto-advance; we hold this green
                preemptedPhase = intersection.activePhase();
                mode = ControllerMode.PREEMPTED;
                events.add(SignalEvent.info(EventType.PREEMPTED, "force " + phaseId, clock.nowMillis()));
                events.addAll(intersection.applyPhase(target, tickHelper())); // SAME safe sequence
            } finally { lock.unlock(); }
            notifyAll(events);
        }

        void clearPreemption() {
            List<SignalEvent> events = new ArrayList<>();
            lock.lock();
            try {
                if (mode != ControllerMode.PREEMPTED) return; // no-op if not preempted
                mode = ControllerMode.RUNNING;
                events.add(SignalEvent.info(EventType.PREEMPTION_CLEARED, "resume plan", clock.nowMillis()));
                Phase cur = intersection.activePhase();
                Phase next = timingStrategy.nextPhase(ctx(cur)); // continue from where we are
                events.addAll(intersection.applyPhase(next, tickHelper()));
                scheduleNext(next);
                preemptedPhase = null;
            } finally { lock.unlock(); }
            notifyAll(events);
        }

        /* ---- fault / fail-safe (designed-for) ---- */
        void enterSafeMode(String reason) {
            List<SignalEvent> events = new ArrayList<>();
            lock.lock();
            try {
                mode = ControllerMode.SAFE_MODE;
                cancelPending();
                events.add(SignalEvent.info(EventType.FAULT, reason, clock.nowMillis()));
                events.addAll(intersection.allFlashingRed(clock.nowMillis()));
            } finally { lock.unlock(); }
            notifyAll(events);
        }

        /* ---- helpers ---- */
        private TimingContext ctx(Phase current) {
            return new TimingContext(intersection, current, clock.nowMillis(), demand);
        }

        private void scheduleNext(Phase phase) {
            int green = timingStrategy.greenDurationSec(phase, ctx(phase));
            cancelPending();
            pendingTick = scheduler.scheduleAfter(green, this::tick);
        }

        private void cancelPending() {
            if (pendingTick != null) { pendingTick.cancel(); pendingTick = null; }
        }

        /** ClockTick bound to the injected clock; used by applyPhase. */
        private ClockTick tick() { return tickHelper(); }
        private ClockTick tickHelper() {
            return new ClockTick() {
                @Override public long now() { return clock.nowMillis(); }
                @Override public void advance(int sec) {
                    if (clock instanceof ManualClock) ((ManualClock) clock).advanceSeconds(sec);
                    // production SystemClock advances on its own; nothing to do.
                }
            };
        }

        /** Notify observers OUTSIDE the lock; isolate observer failures. */
        private void notifyAll(List<SignalEvent> events) {
            if (events == null) return;
            for (SignalEvent e : events) {
                for (SignalObserver o : observers) {
                    try { o.onEvent(e); }
                    catch (RuntimeException ex) {
                        System.err.println("observer failed: " + ex.getMessage());
                    }
                }
            }
        }

        // exposed for the demo
        ControllerMode mode() { lock.lock(); try { return mode; } finally { lock.unlock(); } }
        Intersection intersection() { return intersection; }
    }

    /* =========================================================================
     * SINGLETON (factory-guarded) — one controller per intersection id
     * ========================================================================= */

    static final class TrafficControllerFactory {
        private static final Map<String, TrafficController> CONTROLLERS = new ConcurrentHashMap<>();

        private TrafficControllerFactory() {}

        /** Returns THE controller for this intersection, creating it once. */
        static TrafficController getController(String intersectionId, Intersection intersection,
                                               TimingStrategy strategy, Clock clock, Scheduler scheduler) {
            return CONTROLLERS.computeIfAbsent(intersectionId,
                    id -> new TrafficController(intersection, strategy, clock, scheduler));
        }
        static Optional<TrafficController> existing(String intersectionId) {
            return Optional.ofNullable(CONTROLLERS.get(intersectionId));
        }
        /** Test isolation: drop all controllers. */
        static void clear() { CONTROLLERS.clear(); }
    }

    /* =========================================================================
     * Builder helper for a standard 4-way intersection (N/S share, E/W share)
     * ========================================================================= */

    static Intersection buildFourWay(String id) {
        Signal north = new Signal("N", Direction.NORTH);
        Signal south = new Signal("S", Direction.SOUTH);
        Signal east  = new Signal("E", Direction.EAST);
        Signal west  = new Signal("W", Direction.WEST);

        // Two non-conflicting phases: N+S green, then E+W green.
        Phase nsPhase = new Phase("NS", new LinkedHashSet<>(List.of("N", "S")), 5, 60);
        Phase ewPhase = new Phase("EW", new LinkedHashSet<>(List.of("E", "W")), 5, 60);

        return new Intersection(id)
                .addSignal(north).addSignal(south).addSignal(east).addSignal(west)
                .addPhase(nsPhase).addPhase(ewPhase)
                .setSafeSequenceTimings(/*yellow*/4, /*allRed*/2);
    }

    private static void printState(TrafficController c, String label) {
        StringBuilder sb = new StringBuilder("STATE after " + label + " | mode=" + c.mode() + " | ");
        for (Signal s : c.intersection().signalsView())
            sb.append(s.id()).append("=").append(s.currentColor()).append(" ");
        System.out.println(sb.toString().trim());
    }

    /* =========================================================================
     * DEMO — illustrates the key scenarios (uses ManualClock + ManualScheduler
     * so it is deterministic; NOT meant to be compiled/run, only read).
     * ========================================================================= */

    public static void main(String[] args) {
        TrafficControllerFactory.clear();

        ManualClock clock = new ManualClock();
        ManualScheduler scheduler = new ManualScheduler();
        Intersection intersection = buildFourWay("MAIN_AND_1ST");

        TimingStrategy fixed = new FixedTimerStrategy(30);
        TrafficController controller = TrafficControllerFactory.getController(
                "MAIN_AND_1ST", intersection, fixed, clock, scheduler);

        // Observers: dashboard prints, audit log records, metrics counts.
        ConsoleDashboardObserver dash = new ConsoleDashboardObserver("DASH");
        AuditLogObserver audit = new AuditLogObserver();
        MetricsObserver metrics = new MetricsObserver();
        controller.addObserver(dash);
        controller.addObserver(audit);
        controller.addObserver(metrics);

        System.out.println("=== 1) START (NS goes green) ===");
        controller.start();
        printState(controller, "start");

        System.out.println("\n=== 2) Timer fires -> safe transition to EW (Y -> all-red -> green) ===");
        scheduler.fireDue();           // simulate green time elapsing
        printState(controller, "tick #1");

        System.out.println("\n=== 3) Hot-swap to ACTUATED strategy with sensor demand ===");
        controller.setTimingStrategy(new ActuatedTimingStrategy(/*base*/10, /*sec per car*/2));
        controller.setDemand(Map.of("NS", 15, "EW", 1)); // NS heavily loaded
        scheduler.fireDue();           // back to NS; green should extend with demand
        System.out.println("  next scheduled green = " + scheduler.nextDelaySec() + "s (clamped to phase max 60)");
        printState(controller, "tick #2 (actuated)");

        System.out.println("\n=== 4) EMERGENCY preemption -> force EW green safely, then hold ===");
        controller.preempt("EW");
        printState(controller, "preempt(EW)");
        System.out.println("  (no auto-advance while preempted; mode=" + controller.mode() + ")");

        System.out.println("\n=== 5) Clear preemption -> resume normal plan ===");
        controller.clearPreemption();
        printState(controller, "clearPreemption");

        System.out.println("\n=== 6) Pause / resume ===");
        controller.pause();
        printState(controller, "pause");
        controller.resume();
        printState(controller, "resume");

        System.out.println("\n=== 7) Fault -> fail-safe all flashing red ===");
        controller.enterSafeMode("loop-detector failure on N approach");
        printState(controller, "enterSafeMode");

        System.out.println("\n=== 8) Factory guarantees one controller per intersection ===");
        TrafficController same = TrafficControllerFactory.getController(
                "MAIN_AND_1ST", intersection, fixed, clock, scheduler);
        System.out.println("  same instance? " + (same == controller));

        System.out.println("\n=== Audit log size = " + audit.size() + " events ===");
        System.out.println("=== Metrics = " + metrics.snapshot() + " ===");

        controller.stop();
        scheduler.shutdown();
        System.out.println("\nDemo complete.");
    }
}

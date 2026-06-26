/* =====================================================================================
 * Meeting Scheduler / Calendar — single-file LLD review artifact
 * =====================================================================================
 *
 * REVIEW / REVISION ARTIFACT: read-and-revise. Complete and correct-by-inspection;
 * not intended to be compiled or run in CI. A main() illustrates usage.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Domain/booking engine only (in-memory) or full system with DB + API?
 *   2. Entities in scope: Meeting only, or also Room/Resource/Group?
 *   3. Find common free slots across attendees, or only conflict-check a chosen slot?
 *   4. Recurring meetings? Which types (daily/weekly/monthly/full RRULE)?
 *   5. Recurrence exceptions (cancel/modify one occurrence) and end condition (count/until)?
 *   6. RSVP (accept/decline/tentative)? Does organizer see responses?
 *   7. Reminders/notifications: just fire events, or implement delivery channels?
 *   8. Room booking a hard constraint? Does room capacity vs attendee count matter?
 *   9. Edit/reschedule/cancel supported? What happens to attendees/room on cancel?
 * Non-functional:
 *  10. Concurrency: multiple organizers at once? Single process or distributed?
 *  11. Scale: #users/#meetings/#rooms? Need sub-linear free-slot search?
 *  12. Time zones & DST: participants across zones? Store UTC + render local?
 *  13. Conflict policy: hard-block, or allow-with-warning (personal vs room)?
 *  14. Consistency: strict no-double-book invariant, or eventual?
 * Scope-narrowing (confirm OUT of scope):
 *  15. Auth, persistence details, networking, billing, full iCal, working-hours/holidays,
 *      priority preemption.
 *  16. Time granularity: arbitrary Instant or quantized 15/30-min slots?
 *
 * ASSUMPTIONS BAKED IN:
 *   - Intervals are half-open [start, end): back-to-back meetings do NOT conflict.
 *     Overlap iff (a.start < b.end && b.start < a.end).
 *   - All instants stored in UTC (Instant); recurrence expanded in organizer ZoneId
 *     so wall-clock time is preserved across DST; rendering uses ZonedDateTime.
 *   - Single-JVM, in-memory store; thread-safety via striped per-resource locks
 *     around the check-then-book critical section, locks taken in sorted key order.
 *
 * PATTERNS: Strategy (RecurrenceRule, ConflictStrategy, SlotSearchStrategy,
 *   NotificationChannel), Observer (Meeting -> MeetingObserver), Factory
 *   (MeetingFactory), Repository (MeetingRepository), Value Object (Interval).
 * ===================================================================================== */

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    /* ============================== Value object: Interval ============================== */

    /** Immutable half-open interval [start, end) over Instant (UTC). Thread-safe by design. */
    static final class Interval {
        final Instant start;
        final Instant end;

        Interval(Instant start, Instant end) {
            if (start == null || end == null) throw new IllegalArgumentException("null bound");
            if (!start.isBefore(end)) throw new IllegalArgumentException("start must be < end");
            this.start = start;
            this.end = end;
        }

        /** Overlap iff a.start < b.end && b.start < a.end (half-open => back-to-back is safe). */
        boolean overlaps(Interval o) {
            return this.start.isBefore(o.end) && o.start.isBefore(this.end);
        }

        boolean contains(Instant t) {
            return !t.isBefore(start) && t.isBefore(end); // [start, end)
        }

        Duration duration() {
            return Duration.between(start, end);
        }

        @Override public boolean equals(Object x) {
            if (this == x) return true;
            if (!(x instanceof Interval)) return false;
            Interval i = (Interval) x;
            return start.equals(i.start) && end.equals(i.end);
        }
        @Override public int hashCode() { return Objects.hash(start, end); }
        @Override public String toString() { return "[" + start + ", " + end + ")"; }
    }

    /* ============================== Enums ============================== */

    enum RSVPStatus { NEEDS_ACTION, ACCEPTED, DECLINED, TENTATIVE }

    /** Lifecycle events the Meeting (Subject) broadcasts to observers. */
    enum MeetingEventType { CREATED, RESCHEDULED, CANCELLED, RSVP_CHANGED, REMINDER_DUE }

    /* ============================== User / Resource / Room ============================== */

    static final class User {
        final String id;
        final String name;
        final String email;
        final ZoneId homeZone;
        final Calendar calendar; // composition: lifecycle-bound to the user

        User(String id, String name, String email, ZoneId homeZone) {
            this.id = id; this.name = name; this.email = email; this.homeZone = homeZone;
            this.calendar = new Calendar(this);
        }
        @Override public String toString() { return name; }
    }

    /** Anything bookable with a capacity (Room, projector, car...). LSP: Room substitutes here. */
    interface Resource {
        String id();
        String name();
        int capacity();
    }

    static final class Room implements Resource {
        final String id, name, location;
        final int capacity;
        Room(String id, String name, int capacity, String location) {
            this.id = id; this.name = name; this.capacity = capacity; this.location = location;
        }
        public String id() { return id; }
        public String name() { return name; }
        public int capacity() { return capacity; }
        @Override public String toString() { return name + "(" + location + ", cap=" + capacity + ")"; }
    }

    /* ============================== Attendee ============================== */

    static final class Attendee {
        final User user;
        volatile RSVPStatus status; // mutated on RSVP; volatile for visibility
        Attendee(User user, RSVPStatus status) { this.user = user; this.status = status; }
        boolean countsAsBusy() {
            // Declined attendees are free; everyone else is busy (configurable policy).
            return status != RSVPStatus.DECLINED;
        }
        @Override public String toString() { return user.name + ":" + status; }
    }

    /* ============================== Strategy: RecurrenceRule ============================== */

    /**
     * Strategy for expanding a series into occurrence start instants within a window.
     * Expansion happens in the organizer's zone so wall-clock time survives DST.
     */
    interface RecurrenceRule {
        /**
         * @param baseStart first occurrence start (UTC instant)
         * @param window    window of interest [from, to)
         * @param zone      organizer zone for wall-clock preservation
         * @return occurrence start instants within the window, ascending
         */
        List<Instant> occurrences(Instant baseStart, Interval window, ZoneId zone);
        String describe();
    }

    /** Single occurrence only. */
    static final class NoRecurrence implements RecurrenceRule {
        public List<Instant> occurrences(Instant baseStart, Interval window, ZoneId zone) {
            List<Instant> out = new ArrayList<>();
            if (!baseStart.isBefore(window.start) && baseStart.isBefore(window.end)) out.add(baseStart);
            return out;
        }
        public String describe() { return "none"; }
    }

    /** Abstract helper for periodic rules with an optional count / until-date end condition. */
    abstract static class PeriodicRecurrence implements RecurrenceRule {
        final Integer count;     // max occurrences, nullable
        final Instant until;     // exclusive end, nullable
        PeriodicRecurrence(Integer count, Instant until) { this.count = count; this.until = until; }

        /** Step the wall-clock time forward by one period, preserving local time (DST-safe). */
        abstract ZonedDateTime next(ZonedDateTime current);

        public List<Instant> occurrences(Instant baseStart, Interval window, ZoneId zone) {
            List<Instant> out = new ArrayList<>();
            ZonedDateTime cur = baseStart.atZone(zone);
            int emitted = 0;
            // Safety bound to avoid runaway loops on a "forever" rule with a wide window.
            for (int guard = 0; guard < 100_000; guard++) {
                Instant inst = cur.toInstant();
                if (count != null && emitted >= count) break;
                if (until != null && !inst.isBefore(until)) break;
                if (!inst.isBefore(window.end)) break; // past the window
                if (!inst.isBefore(window.start)) out.add(inst);
                emitted++; // count occurrences from baseStart regardless of window
                cur = next(cur);
            }
            return out;
        }
    }

    static final class DailyRecurrence extends PeriodicRecurrence {
        final int everyNDays;
        DailyRecurrence(int everyNDays, Integer count, Instant until) {
            super(count, until); this.everyNDays = Math.max(1, everyNDays);
        }
        ZonedDateTime next(ZonedDateTime c) { return c.plusDays(everyNDays); }
        public String describe() { return "daily/" + everyNDays; }
    }

    static final class WeeklyRecurrence extends PeriodicRecurrence {
        final int everyNWeeks;
        final Set<DayOfWeek> days; // e.g. MON, WED — empty means same weekday as baseStart
        WeeklyRecurrence(int everyNWeeks, Set<DayOfWeek> days, Integer count, Instant until) {
            super(count, until);
            this.everyNWeeks = Math.max(1, everyNWeeks);
            this.days = (days == null) ? Collections.emptySet() : new HashSet<>(days);
        }
        // Simple model: step by N weeks; for multi-day, filter is applied via occurrences override.
        ZonedDateTime next(ZonedDateTime c) { return c.plusWeeks(everyNWeeks); }

        @Override public List<Instant> occurrences(Instant baseStart, Interval window, ZoneId zone) {
            if (days.isEmpty()) return super.occurrences(baseStart, window, zone);
            // Multi-day-of-week expansion at the same local time-of-day.
            List<Instant> out = new ArrayList<>();
            ZonedDateTime base = baseStart.atZone(zone);
            LocalTime tod = base.toLocalTime();
            ZonedDateTime weekStart = base.with(LocalTime.MIN);
            int emitted = 0;
            for (int w = 0; w < 5_000; w++) {
                for (DayOfWeek d : DayOfWeek.values()) {
                    if (!days.contains(d)) continue;
                    ZonedDateTime occ = weekStart.with(d).with(tod);
                    Instant inst = occ.toInstant();
                    if (inst.isBefore(baseStart)) continue;
                    if (count != null && emitted >= count) return out;
                    if (until != null && !inst.isBefore(until)) return out;
                    if (!inst.isBefore(window.end)) return out;
                    if (!inst.isBefore(window.start)) out.add(inst);
                    emitted++;
                }
                weekStart = weekStart.plusWeeks(everyNWeeks);
            }
            return out;
        }
        public String describe() { return "weekly/" + everyNWeeks + days; }
    }

    static final class MonthlyRecurrence extends PeriodicRecurrence {
        final int everyNMonths;
        MonthlyRecurrence(int everyNMonths, Integer count, Instant until) {
            super(count, until); this.everyNMonths = Math.max(1, everyNMonths);
        }
        ZonedDateTime next(ZonedDateTime c) { return c.plusMonths(everyNMonths); }
        public String describe() { return "monthly/" + everyNMonths; }
    }

    /* ============================== Strategy: ConflictStrategy ============================== */

    enum ConflictKind { ALLOW, WARN, REJECT }

    static final class ConflictDecision {
        final ConflictKind kind;
        final Interval conflictingWith; // nullable
        ConflictDecision(ConflictKind kind, Interval conflictingWith) {
            this.kind = kind; this.conflictingWith = conflictingWith;
        }
        static ConflictDecision allow() { return new ConflictDecision(ConflictKind.ALLOW, null); }
    }

    interface ConflictStrategy {
        ConflictDecision decide(Interval proposed, List<Interval> existing);
        String name();
    }

    /** Hard-block: any overlap is rejected (used for rooms). */
    static final class RejectOnConflict implements ConflictStrategy {
        public ConflictDecision decide(Interval proposed, List<Interval> existing) {
            for (Interval e : existing) {
                if (proposed.overlaps(e)) return new ConflictDecision(ConflictKind.REJECT, e);
            }
            return ConflictDecision.allow();
        }
        public String name() { return "REJECT"; }
    }

    /** Permit overlap but surface a warning (sometimes acceptable for personal calendars). */
    static final class AllowWithWarning implements ConflictStrategy {
        public ConflictDecision decide(Interval proposed, List<Interval> existing) {
            for (Interval e : existing) {
                if (proposed.overlaps(e)) return new ConflictDecision(ConflictKind.WARN, e);
            }
            return ConflictDecision.allow();
        }
        public String name() { return "ALLOW_WITH_WARNING"; }
    }

    /* ============================== Strategy: SlotSearchStrategy ============================== */

    interface SlotSearchStrategy {
        /** Pick candidate start instants of length 'dur' from contiguous free intervals. */
        List<Instant> pick(List<Interval> free, Duration dur);
    }

    /** Earliest-fit: the first start in each free interval that can hold 'dur'. */
    static final class EarliestFitStrategy implements SlotSearchStrategy {
        public List<Instant> pick(List<Interval> free, Duration dur) {
            List<Instant> out = new ArrayList<>();
            for (Interval f : free) {
                if (f.duration().compareTo(dur) >= 0) out.add(f.start);
            }
            return out;
        }
    }

    /** All non-overlapping slots of length 'dur', stepping by 'dur' within each free interval. */
    static final class AllSlotsStrategy implements SlotSearchStrategy {
        public List<Instant> pick(List<Interval> free, Duration dur) {
            List<Instant> out = new ArrayList<>();
            for (Interval f : free) {
                Instant t = f.start;
                while (!t.plus(dur).isAfter(f.end)) {
                    out.add(t);
                    t = t.plus(dur);
                }
            }
            return out;
        }
    }

    /* ============================== Strategy: NotificationChannel ============================== */

    interface NotificationChannel {
        void send(User to, String message);
    }

    static final class ConsoleEmailChannel implements NotificationChannel {
        public void send(User to, String message) {
            System.out.println("  [EMAIL -> " + to.email + "] " + message);
        }
    }

    /* ============================== Observer: MeetingObserver ============================== */

    interface MeetingObserver {
        void onEvent(Meeting meeting, MeetingEventType type, String detail);
    }

    /** Sends invites/reminders through a pluggable channel (Strategy inside Observer). */
    static final class EmailReminderObserver implements MeetingObserver {
        private final NotificationChannel channel;
        EmailReminderObserver(NotificationChannel channel) { this.channel = channel; }
        public void onEvent(Meeting m, MeetingEventType type, String detail) {
            String subject;
            switch (type) {
                case CREATED:      subject = "Invitation: " + m.title; break;
                case RESCHEDULED:  subject = "Updated time: " + m.title; break;
                case CANCELLED:    subject = "Cancelled: " + m.title; break;
                case REMINDER_DUE: subject = "Reminder: " + m.title + " starts soon"; break;
                case RSVP_CHANGED: subject = "RSVP update: " + m.title; break;
                default:           subject = m.title;
            }
            String body = subject + (detail == null ? "" : " (" + detail + ")");
            channel.send(m.organizer, body);
            for (Attendee a : m.attendees) channel.send(a.user, body);
        }
    }

    /** Simple audit observer — demonstrates multiple decoupled reactions. */
    static final class AuditLogObserver implements MeetingObserver {
        public void onEvent(Meeting m, MeetingEventType type, String detail) {
            System.out.println("  [AUDIT] " + type + " meeting=" + m.id
                    + (detail == null ? "" : " detail=" + detail));
        }
    }

    /* ============================== Event / Meeting (Subject) ============================== */

    abstract static class Event {
        final String id;
        volatile String title;
        volatile Interval interval; // base interval (UTC)
        final User owner;
        Event(String id, String title, Interval interval, User owner) {
            this.id = id; this.title = title; this.interval = interval; this.owner = owner;
        }
    }

    static final class Meeting extends Event {
        final User organizer;
        final List<Attendee> attendees;
        volatile Room room; // nullable
        volatile RecurrenceRule recurrence;
        final ZoneId seriesZone; // organizer zone, used to expand recurrence (DST-safe)
        final Set<Instant> cancelledOccurrences = ConcurrentHashMap.newKeySet();
        volatile boolean cancelled = false;
        private final List<MeetingObserver> observers = new CopyOnWriteArrayList<>();

        Meeting(String id, String title, Interval interval, User organizer,
                List<Attendee> attendees, Room room, RecurrenceRule recurrence, ZoneId seriesZone) {
            super(id, title, interval, organizer);
            this.organizer = organizer;
            this.attendees = new CopyOnWriteArrayList<>(attendees);
            this.room = room;
            this.recurrence = recurrence;
            this.seriesZone = seriesZone;
        }

        void addObserver(MeetingObserver o) { observers.add(o); }
        void notifyObservers(MeetingEventType type, String detail) {
            for (MeetingObserver o : observers) o.onEvent(this, type, detail);
        }

        Duration occurrenceDuration() { return interval.duration(); }

        /** Expand the series into concrete intervals within the window, skipping exceptions. */
        List<Interval> expand(Interval window) {
            List<Interval> out = new ArrayList<>();
            if (cancelled) return out;
            Duration d = occurrenceDuration();
            for (Instant s : recurrence.occurrences(interval.start, window, seriesZone)) {
                if (cancelledOccurrences.contains(s)) continue;
                out.add(new Interval(s, s.plus(d)));
            }
            return out;
        }

        /** Cancel a single occurrence of a recurring series (iCal EXDATE-style exception). */
        void cancelOccurrence(Instant occurrenceStart) { cancelledOccurrences.add(occurrenceStart); }

        @Override public String toString() {
            return "Meeting{" + id + ", '" + title + "', " + interval
                    + ", room=" + room + ", rec=" + recurrence.describe()
                    + ", attendees=" + attendees + "}";
        }
    }

    /* ============================== Factory ============================== */

    /** Centralizes Meeting construction and default observer wiring. */
    static final class MeetingFactory {
        private final List<MeetingObserver> defaultObservers;
        MeetingFactory(List<MeetingObserver> defaultObservers) {
            this.defaultObservers = new ArrayList<>(defaultObservers);
        }

        Meeting createOneOff(String id, String title, Interval interval, User organizer,
                             List<Attendee> attendees, Room room) {
            return build(id, title, interval, organizer, attendees, room, new NoRecurrence());
        }

        Meeting createRecurring(String id, String title, Interval interval, User organizer,
                                List<Attendee> attendees, Room room, RecurrenceRule rule) {
            return build(id, title, interval, organizer, attendees, room, rule);
        }

        private Meeting build(String id, String title, Interval interval, User organizer,
                              List<Attendee> attendees, Room room, RecurrenceRule rule) {
            Meeting m = new Meeting(id, title, interval, organizer, attendees, room, rule,
                    organizer.homeZone);
            for (MeetingObserver o : defaultObservers) m.addObserver(o);
            return m;
        }
    }

    /* ============================== Calendar ============================== */

    /** A user's collection of events; answers free/busy for a window. */
    static final class Calendar {
        final User owner;
        // read-heavy busy queries; COW keeps reads lock-free and consistent
        private final List<Meeting> meetings = new CopyOnWriteArrayList<>();

        Calendar(User owner) { this.owner = owner; }

        void add(Meeting m) { meetings.add(m); }
        void remove(String meetingId) { meetings.removeIf(mm -> mm.id.equals(meetingId)); }

        /** Busy intervals (expanded across recurrence) for this user within the window. */
        List<Interval> busyIntervals(Interval window, String excludeMeetingId) {
            List<Interval> busy = new ArrayList<>();
            for (Meeting m : meetings) {
                if (excludeMeetingId != null && m.id.equals(excludeMeetingId)) continue;
                if (m.cancelled) continue;
                // count this user only if they haven't declined
                boolean userBusy = m.organizer.id.equals(owner.id);
                if (!userBusy) {
                    for (Attendee a : m.attendees) {
                        if (a.user.id.equals(owner.id) && a.countsAsBusy()) { userBusy = true; break; }
                    }
                }
                if (userBusy) busy.addAll(m.expand(window));
            }
            return busy;
        }
    }

    /* ============================== Repository ============================== */

    interface MeetingRepository {
        void save(Meeting m);
        Meeting findById(String id);
        List<Meeting> findByRoom(String roomId);
        List<Meeting> all();
    }

    static final class InMemoryMeetingRepository implements MeetingRepository {
        private final Map<String, Meeting> store = new ConcurrentHashMap<>();
        public void save(Meeting m) { store.put(m.id, m); }
        public Meeting findById(String id) { return store.get(id); }
        public List<Meeting> findByRoom(String roomId) {
            List<Meeting> out = new ArrayList<>();
            for (Meeting m : store.values()) {
                if (!m.cancelled && m.room != null && m.room.id().equals(roomId)) out.add(m);
            }
            return out;
        }
        public List<Meeting> all() { return new ArrayList<>(store.values()); }
    }

    /* ============================== Lock manager (striped) ============================== */

    /**
     * Hands out a stable lock per resource key. Fixed-size stripe array bounds memory
     * (trades a little false contention for no unbounded per-key map growth).
     */
    static final class BookingLockManager {
        private final Lock[] stripes;
        BookingLockManager(int stripeCount) {
            stripes = new ReentrantLock[Math.max(1, stripeCount)];
            for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock();
        }
        Lock lockFor(String key) {
            int idx = (key.hashCode() & 0x7fffffff) % stripes.length;
            return stripes[idx];
        }
    }

    /* ============================== FreeBusyService ============================== */

    static final class FreeBusyService {
        /** Sort + coalesce overlapping/adjacent intervals into a minimal busy set. */
        List<Interval> mergeBusy(List<Interval> intervals) {
            if (intervals.isEmpty()) return new ArrayList<>();
            List<Interval> sorted = new ArrayList<>(intervals);
            sorted.sort(Comparator.comparing(i -> i.start));
            List<Interval> merged = new ArrayList<>();
            Instant curStart = sorted.get(0).start;
            Instant curEnd = sorted.get(0).end;
            for (int i = 1; i < sorted.size(); i++) {
                Interval iv = sorted.get(i);
                if (!iv.start.isAfter(curEnd)) {            // overlap or touch -> coalesce
                    if (iv.end.isAfter(curEnd)) curEnd = iv.end;
                } else {
                    merged.add(new Interval(curStart, curEnd));
                    curStart = iv.start; curEnd = iv.end;
                }
            }
            merged.add(new Interval(curStart, curEnd));
            return merged;
        }

        /** Complement of merged busy within the window = free intervals. */
        List<Interval> freeWithin(Interval window, List<Interval> busy) {
            List<Interval> merged = mergeBusy(busy);
            List<Interval> free = new ArrayList<>();
            Instant cursor = window.start;
            for (Interval b : merged) {
                if (b.end.isBefore(window.start) || !b.start.isBefore(window.end)) continue; // outside
                Instant bStart = b.start.isBefore(window.start) ? window.start : b.start;
                Instant bEnd = b.end.isAfter(window.end) ? window.end : b.end;
                if (cursor.isBefore(bStart)) free.add(new Interval(cursor, bStart));
                if (bEnd.isAfter(cursor)) cursor = bEnd;
            }
            if (cursor.isBefore(window.end)) free.add(new Interval(cursor, window.end));
            return free;
        }

        /** Common free slots across users (+ optional room) of length 'dur' within window. */
        List<Instant> commonSlots(List<User> users, Room room, Interval window, Duration dur,
                                  MeetingRepository repo, SlotSearchStrategy strategy) {
            List<Interval> allBusy = new ArrayList<>();
            for (User u : users) allBusy.addAll(u.calendar.busyIntervals(window, null));
            if (room != null) {
                for (Meeting m : repo.findByRoom(room.id())) allBusy.addAll(m.expand(window));
            }
            List<Interval> free = freeWithin(window, allBusy);
            return strategy.pick(free, dur);
        }
    }

    /* ============================== Requests / exceptions ============================== */

    static final class MeetingRequest {
        final String id, title;
        final User organizer;
        final List<Attendee> attendees;
        final Interval interval;
        final Room room;            // nullable
        final RecurrenceRule recurrence; // nullable -> NoRecurrence
        MeetingRequest(String id, String title, User organizer, List<Attendee> attendees,
                       Interval interval, Room room, RecurrenceRule recurrence) {
            this.id = id; this.title = title; this.organizer = organizer;
            this.attendees = attendees; this.interval = interval; this.room = room;
            this.recurrence = recurrence;
        }
    }

    static final class ConflictException extends RuntimeException {
        final Interval conflictingWith;
        ConflictException(String msg, Interval c) { super(msg); this.conflictingWith = c; }
    }

    /* ============================== MeetingScheduler (orchestrator) ============================== */

    static final class MeetingScheduler {
        private final MeetingRepository repo;
        private final ConflictStrategy roomConflictStrategy;   // strict for rooms
        private final ConflictStrategy attendeeConflictStrategy; // configurable for people
        private final FreeBusyService freeBusy;
        private final BookingLockManager locks;
        private final MeetingFactory factory;

        MeetingScheduler(MeetingRepository repo, ConflictStrategy roomConflictStrategy,
                         ConflictStrategy attendeeConflictStrategy, FreeBusyService freeBusy,
                         BookingLockManager locks, MeetingFactory factory) {
            this.repo = repo;
            this.roomConflictStrategy = roomConflictStrategy;
            this.attendeeConflictStrategy = attendeeConflictStrategy;
            this.freeBusy = freeBusy;
            this.locks = locks;
            this.factory = factory;
        }

        Meeting schedule(MeetingRequest req) {
            validate(req);
            RecurrenceRule rule = (req.recurrence == null) ? new NoRecurrence() : req.recurrence;
            // Build (but don't persist) so we can expand occurrences for conflict checks.
            Meeting candidate = (rule instanceof NoRecurrence)
                    ? factory.createOneOff(req.id, req.title, req.interval, req.organizer,
                                           req.attendees, req.room)
                    : factory.createRecurring(req.id, req.title, req.interval, req.organizer,
                                              req.attendees, req.room, rule);

            // Lock all involved resources in a consistent (sorted) order -> no deadlock.
            List<Lock> ordered = acquire(lockKeys(req));
            try {
                Interval checkWindow = wideWindow(req.interval);
                List<Interval> occurrences = candidate.expand(checkWindow);

                // Room: strict, must not double-book.
                if (req.room != null) {
                    List<Interval> roomBusy = new ArrayList<>();
                    for (Meeting m : repo.findByRoom(req.room.id())) roomBusy.addAll(m.expand(checkWindow));
                    for (Interval occ : occurrences) {
                        ConflictDecision d = roomConflictStrategy.decide(occ, roomBusy);
                        if (d.kind == ConflictKind.REJECT) {
                            throw new ConflictException("Room " + req.room.name()
                                    + " double-booked at " + occ + " vs " + d.conflictingWith,
                                    d.conflictingWith);
                        }
                    }
                }

                // Attendees: configurable policy (reject or warn).
                for (Attendee a : req.attendees) {
                    List<Interval> userBusy = a.user.calendar.busyIntervals(checkWindow, req.id);
                    for (Interval occ : occurrences) {
                        ConflictDecision d = attendeeConflictStrategy.decide(occ, userBusy);
                        if (d.kind == ConflictKind.REJECT) {
                            throw new ConflictException("Attendee " + a.user.name
                                    + " busy at " + occ, d.conflictingWith);
                        } else if (d.kind == ConflictKind.WARN) {
                            System.out.println("  [WARN] " + a.user.name + " has overlap at " + occ);
                        }
                    }
                }

                // Commit: persist + index into calendars.
                repo.save(candidate);
                req.organizer.calendar.add(candidate);
                for (Attendee a : req.attendees) a.user.calendar.add(candidate);
            } finally {
                release(ordered);
            }

            candidate.notifyObservers(MeetingEventType.CREATED, "scheduled " + candidate.interval);
            return candidate;
        }

        void cancel(String meetingId) {
            Meeting m = repo.findById(meetingId);
            if (m == null || m.cancelled) return; // idempotent
            List<Lock> ordered = acquire(lockKeysFor(m));
            try {
                m.cancelled = true;
                m.organizer.calendar.remove(meetingId);
                for (Attendee a : m.attendees) a.user.calendar.remove(meetingId);
            } finally {
                release(ordered);
            }
            m.notifyObservers(MeetingEventType.CANCELLED, null);
        }

        void reschedule(String meetingId, Interval newInterval) {
            Meeting m = repo.findById(meetingId);
            if (m == null || m.cancelled) throw new IllegalStateException("no such active meeting");
            List<Lock> ordered = acquire(lockKeysFor(m));
            try {
                Interval checkWindow = wideWindow(newInterval);
                // Build proposed occurrences from the new base time.
                Duration d = newInterval.duration();
                List<Interval> proposed = new ArrayList<>();
                for (Instant s : m.recurrence.occurrences(newInterval.start, checkWindow, m.seriesZone)) {
                    if (!m.cancelledOccurrences.contains(s)) proposed.add(new Interval(s, s.plus(d)));
                }
                if (m.room != null) {
                    List<Interval> roomBusy = new ArrayList<>();
                    for (Meeting other : repo.findByRoom(m.room.id())) {
                        if (other.id.equals(m.id)) continue; // exclude self
                        roomBusy.addAll(other.expand(checkWindow));
                    }
                    for (Interval occ : proposed) {
                        ConflictDecision dec = roomConflictStrategy.decide(occ, roomBusy);
                        if (dec.kind == ConflictKind.REJECT) {
                            throw new ConflictException("Room busy at " + occ, dec.conflictingWith);
                        }
                    }
                }
                m.interval = newInterval;
                // Attendees may need to re-confirm.
                for (Attendee a : m.attendees) a.status = RSVPStatus.NEEDS_ACTION;
            } finally {
                release(ordered);
            }
            m.notifyObservers(MeetingEventType.RESCHEDULED, "now " + newInterval);
        }

        void respond(String meetingId, User user, RSVPStatus status) {
            Meeting m = repo.findById(meetingId);
            if (m == null) return;
            for (Attendee a : m.attendees) {
                if (a.user.id.equals(user.id)) { a.status = status; break; }
            }
            m.notifyObservers(MeetingEventType.RSVP_CHANGED, user.name + " -> " + status);
        }

        List<Instant> findFreeSlots(List<User> users, Interval window, Duration dur, Room room,
                                    SlotSearchStrategy strategy) {
            return freeBusy.commonSlots(users, room, window, dur, repo, strategy);
        }

        /* ---- helpers ---- */

        private void validate(MeetingRequest req) {
            if (req.interval == null) throw new IllegalArgumentException("interval required");
            if (!req.interval.start.isBefore(req.interval.end))
                throw new IllegalArgumentException("start must be before end");
            if (req.room != null && req.attendees.size() + 1 > req.room.capacity())
                throw new IllegalArgumentException("room capacity " + req.room.capacity()
                        + " < party size " + (req.attendees.size() + 1));
        }

        // Wide window so recurrence expansion has bounds for conflict checks (e.g. +1 year).
        private Interval wideWindow(Interval base) {
            return new Interval(base.start, base.start.plus(Duration.ofDays(365)));
        }

        private List<String> lockKeys(MeetingRequest req) {
            List<String> keys = new ArrayList<>();
            if (req.room != null) keys.add("room:" + req.room.id());
            keys.add("user:" + req.organizer.id);
            for (Attendee a : req.attendees) keys.add("user:" + a.user.id);
            return keys;
        }

        private List<String> lockKeysFor(Meeting m) {
            List<String> keys = new ArrayList<>();
            if (m.room != null) keys.add("room:" + m.room.id());
            keys.add("user:" + m.organizer.id);
            for (Attendee a : m.attendees) keys.add("user:" + a.user.id);
            return keys;
        }

        /** Acquire distinct locks in sorted key order to guarantee a global ordering. */
        private List<Lock> acquire(List<String> keys) {
            // de-dup + sort keys, then map to (possibly shared) stripe locks, de-dup locks.
            List<String> distinct = new ArrayList<>(new HashSet<>(keys));
            Collections.sort(distinct);
            List<Lock> acquired = new ArrayList<>();
            Set<Lock> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (String k : distinct) {
                Lock l = locks.lockFor(k);
                if (seen.add(l)) { l.lock(); acquired.add(l); }
            }
            return acquired;
        }

        private void release(List<Lock> acquired) {
            for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
        }
    }

    /* ============================== Demo ============================== */

    static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm z").withZone(ZoneId.of("UTC"));

    static Instant utc(int year, int month, int day, int hour, int min) {
        return ZonedDateTime.of(year, month, day, hour, min, 0, 0, ZoneOffset.UTC).toInstant();
    }

    public static void main(String[] args) {
        // --- Wiring (dependency injection of all strategies/services) ---
        NotificationChannel email = new ConsoleEmailChannel();
        List<MeetingObserver> observers = List.of(
                new EmailReminderObserver(email), new AuditLogObserver());
        MeetingFactory factory = new MeetingFactory(observers);
        MeetingRepository repo = new InMemoryMeetingRepository();
        FreeBusyService freeBusy = new FreeBusyService();
        BookingLockManager locks = new BookingLockManager(64);
        MeetingScheduler scheduler = new MeetingScheduler(
                repo, new RejectOnConflict(), new RejectOnConflict(), freeBusy, locks, factory);

        // --- Users (different time zones) ---
        User alice = new User("u1", "Alice", "alice@corp.com", ZoneId.of("America/New_York"));
        User bob   = new User("u2", "Bob",   "bob@corp.com",   ZoneId.of("Europe/London"));
        User carol = new User("u3", "Carol", "carol@corp.com", ZoneId.of("Asia/Kolkata"));

        Room boardroom = new Room("r1", "Boardroom", 10, "HQ-3");
        Room huddle    = new Room("r2", "Huddle",    3,  "HQ-2");

        System.out.println("=== 1) Schedule a one-off meeting with a room ===");
        Meeting m1 = scheduler.schedule(new MeetingRequest(
                "m1", "Design Review", alice,
                List.of(new Attendee(bob, RSVPStatus.NEEDS_ACTION),
                        new Attendee(carol, RSVPStatus.NEEDS_ACTION)),
                new Interval(utc(2026, 7, 1, 14, 0), utc(2026, 7, 1, 15, 0)),
                boardroom, null));
        System.out.println("  Scheduled: " + m1);

        System.out.println("\n=== 2) Conflict: same room, overlapping slot is rejected ===");
        try {
            scheduler.schedule(new MeetingRequest(
                    "m2", "Budget Sync", bob,
                    List.of(new Attendee(alice, RSVPStatus.NEEDS_ACTION)),
                    new Interval(utc(2026, 7, 1, 14, 30), utc(2026, 7, 1, 15, 30)),
                    boardroom, null));
        } catch (ConflictException e) {
            System.out.println("  Rejected as expected: " + e.getMessage());
        }

        System.out.println("\n=== 3) Back-to-back (10-11, 11-12) is NOT a conflict (half-open) ===");
        Meeting m3 = scheduler.schedule(new MeetingRequest(
                "m3", "Standup", alice, List.of(new Attendee(bob, RSVPStatus.ACCEPTED)),
                new Interval(utc(2026, 7, 1, 15, 0), utc(2026, 7, 1, 16, 0)), boardroom, null));
        System.out.println("  Scheduled back-to-back fine: " + m3.interval);

        System.out.println("\n=== 4) RSVP: Bob declines m1 -> frees his calendar ===");
        scheduler.respond("m1", bob, RSVPStatus.DECLINED);

        System.out.println("\n=== 5) Find common free slots for Alice & Carol on 2026-07-01 (UTC) ===");
        Interval workday = new Interval(utc(2026, 7, 1, 9, 0), utc(2026, 7, 1, 18, 0));
        List<Instant> slots = scheduler.findFreeSlots(
                List.of(alice, carol), workday, Duration.ofMinutes(30), null, new AllSlotsStrategy());
        System.out.println("  Free 30-min starts (first 6):");
        for (int i = 0; i < Math.min(6, slots.size()); i++) System.out.println("    " + FMT.format(slots.get(i)));

        System.out.println("\n=== 6) Recurring meeting: daily standup for 3 days (Alice's zone) ===");
        Meeting rec = scheduler.schedule(new MeetingRequest(
                "m4", "Daily Standup", alice, List.of(new Attendee(bob, RSVPStatus.ACCEPTED)),
                new Interval(utc(2026, 8, 3, 13, 0), utc(2026, 8, 3, 13, 15)),
                huddle, new DailyRecurrence(1, 3, null)));
        Interval recWindow = new Interval(utc(2026, 8, 1, 0, 0), utc(2026, 8, 10, 0, 0));
        System.out.println("  Occurrences:");
        for (Interval occ : rec.expand(recWindow)) System.out.println("    " + FMT.format(occ.start));

        System.out.println("\n=== 7) Cancel one occurrence of the recurring series ===");
        rec.cancelOccurrence(utc(2026, 8, 4, 13, 0));
        System.out.println("  Occurrences after cancelling 08-04:");
        for (Interval occ : rec.expand(recWindow)) System.out.println("    " + FMT.format(occ.start));

        System.out.println("\n=== 8) Reschedule m1 to a free slot ===");
        scheduler.reschedule("m1", new Interval(utc(2026, 7, 1, 16, 30), utc(2026, 7, 1, 17, 30)));
        System.out.println("  m1 now at " + repo.findById("m1").interval);

        System.out.println("\n=== 9) Cancel m1 (idempotent) ===");
        scheduler.cancel("m1");
        scheduler.cancel("m1"); // no-op second time
        System.out.println("  m1 cancelled = " + repo.findById("m1").cancelled);

        System.out.println("\n=== 10) Time-zone rendering of m3 for each user ===");
        Instant when = m3.interval.start;
        for (User u : List.of(alice, bob, carol)) {
            System.out.println("  " + u.name + " sees: "
                    + when.atZone(u.homeZone).format(
                        DateTimeFormatter.ofPattern("MM-dd HH:mm z")));
        }

        System.out.println("\nDone.");
    }
}

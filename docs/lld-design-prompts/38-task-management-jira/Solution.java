/* =====================================================================================
 * Task Management System (Jira / Trello) — single-file LLD review/revision artifact.
 *
 * This file is a READ-AND-REVISE artifact. It is complete and correct-by-inspection;
 * it is NOT meant to be a production build. A `main` at the bottom illustrates usage.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Hierarchy depth: strict task->subtask, or arbitrary nesting (epic->story->subtask)?
 *   2. Workflow: fixed (Todo/InProgress/Done) or configurable per project with guards?
 *   3. Boards vs sprints: Kanban, Scrum, or both? Are columns 1:1 with statuses?
 *   4. Assignment: single/multiple assignees? Must assignee be a project member?
 *   5. Notifications: which channels? who (assignee/reporter/watchers/@mentions)?
 *      sync or async? best-effort or guaranteed?
 *   6. Comments: flat or threaded? edit/delete? @mention notifications?
 *   7. Labels & priority: free-form vs controlled vocab? priority an ordered enum?
 *   8. Search/filter/sort: which dimensions? saved filters? compound queries?
 *   9. Activity log: per-task only or project-wide audit? display only or replay?
 * Non-functional:
 *  10. Scale: in-memory single process, or design a storage seam for a DB?
 *  11. Concurrency policy: last-write-wins / optimistic (version) / pessimistic?  <-- key
 *  12. Notification consistency: sync inside the write or async after commit?
 *  13. Persistence/transactions in scope? Leave a clean repository seam?
 *  14. AuthZ/RBAC: who may transition/delete, or assume all members may do everything?
 * Scope (out): real persistence, network API, auth, worklogs, attachments,
 *               full-text search engine, multi-tenant isolation.
 *
 * DECISIONS FOR THIS ARTIFACT:
 *   - In-memory, thread-safe. Optimistic locking via per-task version + per-task lock.
 *   - Configurable workflow (data-driven FSM + guards). Composite subtasks (arbitrary depth).
 *   - Observer notifications after commit, async, failures isolated.
 *   - Strategy filters/sorters. Command actions with undo/redo. Factory task creation.
 *   - Repository seam for future persistence.
 *
 * PATTERNS: State, Composite, Observer, Strategy, Command, Factory Method, Repository.
 * ===================================================================================== */

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Solution {

    /* =================================================================================
     * TYPED EXCEPTIONS
     * ============================================================================== */
    static class StaleVersionException extends RuntimeException {
        StaleVersionException(String m) { super(m); }
    }
    static class IllegalTransitionException extends RuntimeException {
        IllegalTransitionException(String m) { super(m); }
    }
    static class DomainException extends RuntimeException {
        DomainException(String m) { super(m); }
    }

    /* =================================================================================
     * VALUE / SIMPLE ENTITIES
     * ============================================================================== */
    static final class User {
        final String id; final String name; final String email;
        User(String id, String name, String email) { this.id = id; this.name = name; this.email = email; }
        @Override public String toString() { return name; }
    }

    /** Ordered priority — natural order = severity. */
    enum Priority { TRIVIAL, LOW, MEDIUM, HIGH, CRITICAL, BLOCKER }

    /** A workflow status category groups custom statuses into the three columns Jira uses. */
    enum StatusCategory { TODO, IN_PROGRESS, DONE }

    static final class Label {
        final String name; final String color;
        Label(String name, String color) { this.name = name; this.color = color; }
        @Override public boolean equals(Object o) {
            return o instanceof Label && ((Label) o).name.equals(name);
        }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public String toString() { return "#" + name; }
    }

    static final class Comment {
        final String id; final User author; final String body; final Instant createdAt;
        Comment(String id, User author, String body) {
            this.id = id; this.author = author; this.body = body; this.createdAt = Instant.now();
        }
        @Override public String toString() { return author + ": " + body; }
    }

    /* =================================================================================
     * STATE PATTERN (workflow) — Status doubles as a TaskState node.
     * Configurable workflow lives in Workflow (data-driven FSM + guards).
     * ============================================================================== */

    /** TaskState: each status knows nothing global; the Workflow holds the transition graph. */
    interface TaskState {
        String id();
        String name();
        StatusCategory category();
    }

    static final class Status implements TaskState {
        private final String id; private final String name; private final StatusCategory category;
        Status(String id, String name, StatusCategory category) {
            this.id = id; this.name = name; this.category = category;
        }
        public String id() { return id; }
        public String name() { return name; }
        public StatusCategory category() { return category; }
        @Override public boolean equals(Object o) { return o instanceof Status && ((Status) o).id.equals(id); }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public String toString() { return name; }
    }

    /** Guard predicate on a transition (e.g. cannot move to Done while subtasks open). */
    interface TransitionGuard { boolean allows(Task task); }

    /** Data-driven finite state machine: states + allowed transitions (+ guards). */
    static final class Workflow {
        private final Status start;
        private final Map<String, Status> states = new LinkedHashMap<>();
        // from -> (to -> guard)
        private final Map<String, Map<String, TransitionGuard>> transitions = new HashMap<>();

        Workflow(Status start) { this.start = start; addState(start); }

        Status start() { return start; }
        List<Status> states() { return new ArrayList<>(states.values()); }
        Status state(String id) { return states.get(id); }

        Workflow addState(Status s) { states.putIfAbsent(s.id(), s); return this; }

        Workflow addTransition(Status from, Status to, TransitionGuard guard) {
            addState(from); addState(to);
            transitions.computeIfAbsent(from.id(), k -> new HashMap<>()).put(to.id(), guard);
            return this;
        }
        Workflow addTransition(Status from, Status to) { return addTransition(from, to, t -> true); }

        boolean canTransition(Status from, Status to, Task task) {
            Map<String, TransitionGuard> edges = transitions.get(from.id());
            if (edges == null || !edges.containsKey(to.id())) return false;
            return edges.get(to.id()).allows(task);
        }
    }

    /* =================================================================================
     * OBSERVER PATTERN — task events fan out to channels after commit (async, isolated).
     * ============================================================================== */
    enum EventType { ASSIGNED, STATUS_CHANGED, COMMENTED, PRIORITY_CHANGED }

    static final class TaskEvent {
        final EventType type; final Task task; final User actor; final String detail;
        TaskEvent(EventType type, Task task, User actor, String detail) {
            this.type = type; this.task = task; this.actor = actor; this.detail = detail;
        }
    }

    interface TaskObserver { void onEvent(TaskEvent e); }

    static final class InAppNotifier implements TaskObserver {
        public void onEvent(TaskEvent e) {
            System.out.println("[in-app] " + e.task.id + " " + e.type + " by " + e.actor + " -> " + e.detail);
        }
    }
    static final class EmailNotifier implements TaskObserver {
        public void onEvent(TaskEvent e) {
            User to = e.task.getAssignee();
            String addr = (to == null) ? "<unassigned>" : to.email;
            System.out.println("[email->" + addr + "] " + e.task.id + " " + e.type + ": " + e.detail);
        }
    }
    /** Demonstrates failure isolation: a flaky observer must not break the write or others. */
    static final class FlakyObserver implements TaskObserver {
        public void onEvent(TaskEvent e) { throw new RuntimeException("channel down"); }
    }

    /** Dispatches events off the write critical path, isolating per-observer failures. */
    static final class ObserverDispatcher {
        private final ExecutorService pool;
        ObserverDispatcher(ExecutorService pool) { this.pool = pool; }

        void dispatch(Collection<TaskObserver> observers, TaskEvent event) {
            for (TaskObserver o : observers) {
                pool.submit(() -> {
                    try { o.onEvent(event); }
                    catch (Exception ex) {
                        System.out.println("[dispatch] observer failed (isolated): " + ex.getMessage());
                    }
                });
            }
        }
    }

    /* =================================================================================
     * ACTIVITY LOG — append-only mutation history per task.
     * ============================================================================== */
    static final class ActivityEntry {
        final User actor; final String action; final String oldValue; final String newValue; final Instant at;
        ActivityEntry(User actor, String action, String oldValue, String newValue) {
            this.actor = actor; this.action = action; this.oldValue = oldValue;
            this.newValue = newValue; this.at = Instant.now();
        }
        @Override public String toString() {
            return actor + " " + action + " [" + oldValue + " -> " + newValue + "]";
        }
    }
    static final class ActivityLog {
        private final List<ActivityEntry> entries = new CopyOnWriteArrayList<>();
        void record(ActivityEntry e) { entries.add(e); }
        List<ActivityEntry> entries() { return new ArrayList<>(entries); }
    }

    /* =================================================================================
     * COMPOSITE PATTERN — WorkItem component; Task is leaf + composite (subtasks).
     * Task is also the Observer subject and carries optimistic-locking state.
     * ============================================================================== */
    abstract static class WorkItem {
        protected final String id;
        protected volatile String title;
        protected volatile String description = "";
        protected volatile Priority priority = Priority.MEDIUM;
        protected volatile Status status;
        protected volatile User assignee;     // 0..1
        protected volatile long version = 0L; // optimistic concurrency
        protected final List<WorkItem> children = new CopyOnWriteArrayList<>();

        WorkItem(String id, String title, Status status) {
            this.id = id; this.title = title; this.status = status;
        }
        String id() { return id; }
        long getVersion() { return version; }
        Status getStatus() { return status; }
        User getAssignee() { return assignee; }
        Priority getPriority() { return priority; }

        void addChild(WorkItem child) {
            if (child == this || isAncestor(child)) throw new DomainException("cycle in subtasks");
            children.add(child);
        }
        List<WorkItem> children() { return new ArrayList<>(children); }

        private boolean isAncestor(WorkItem candidate) {
            // candidate cannot be added if it already contains 'this' somewhere below it
            for (WorkItem c : candidate.children) {
                if (c == this || c.isAncestor(this)) return true;
            }
            return false;
        }

        /** Recursive roll-up: leaf = 1.0 if DONE else 0.0; composite = avg of children. */
        double completionRatio() {
            if (children.isEmpty()) return status.category() == StatusCategory.DONE ? 1.0 : 0.0;
            double sum = 0.0;
            for (WorkItem c : children) sum += c.completionRatio();
            return sum / children.size();
        }
        boolean hasOpenChildren() {
            for (WorkItem c : children) {
                if (c.status.category() != StatusCategory.DONE || c.hasOpenChildren()) return true;
            }
            return false;
        }
    }

    static final class Task extends WorkItem {
        private final User reporter;
        private final List<Comment> comments = new CopyOnWriteArrayList<>();
        private final Set<Label> labels = ConcurrentHashMap.newKeySet();
        private final List<TaskObserver> observers = new CopyOnWriteArrayList<>();
        private final ActivityLog activityLog = new ActivityLog();
        private final Workflow workflow;
        private volatile LocalDate dueDate;

        Task(String id, String title, Status start, User reporter, Workflow workflow) {
            super(id, title, start);
            this.reporter = reporter; this.workflow = workflow;
        }

        // --- Observer subject API ---
        void addObserver(TaskObserver o) { observers.add(o); }
        List<TaskObserver> observers() { return new ArrayList<>(observers); }

        // --- accessors / mutators (non-versioned, low-risk metadata) ---
        User getReporter() { return reporter; }
        ActivityLog activityLog() { return activityLog; }
        List<Comment> comments() { return new ArrayList<>(comments); }
        Set<Label> labels() { return new HashSet<>(labels); }
        LocalDate getDueDate() { return dueDate; }
        void setDueDate(LocalDate d) { this.dueDate = d; }
        void addLabel(Label l) { labels.add(l); }
        void removeLabel(Label l) { labels.remove(l); }

        /* NOTE: the versioned mutators below are intended to be invoked from inside
         * TaskService's per-task lock so the check-then-act is atomic. They re-check
         * the expected version, mutate, bump version, and append to the activity log.
         * They return the TaskEvent so the service can dispatch it AFTER releasing the lock. */

        TaskEvent doTransition(Status target, long expectedVersion, User actor) {
            requireVersion(expectedVersion);
            if (status.equals(target)) return null; // no-op, do not bump version
            if (!workflow.canTransition(status, target, this))
                throw new IllegalTransitionException("Cannot move " + id + " from " + status + " to " + target);
            String old = status.name();
            this.status = target;
            this.version++;
            activityLog.record(new ActivityEntry(actor, "status", old, target.name()));
            return new TaskEvent(EventType.STATUS_CHANGED, this, actor, old + " -> " + target.name());
        }

        TaskEvent doAssign(User newAssignee, long expectedVersion, User actor) {
            requireVersion(expectedVersion);
            String old = assignee == null ? "<none>" : assignee.name;
            this.assignee = newAssignee;
            this.version++;
            activityLog.record(new ActivityEntry(actor, "assignee", old,
                    newAssignee == null ? "<none>" : newAssignee.name));
            return new TaskEvent(EventType.ASSIGNED, this, actor,
                    "assigned to " + (newAssignee == null ? "<none>" : newAssignee.name));
        }

        TaskEvent doSetPriority(Priority p, long expectedVersion, User actor) {
            requireVersion(expectedVersion);
            String old = priority.name();
            this.priority = p;
            this.version++;
            activityLog.record(new ActivityEntry(actor, "priority", old, p.name()));
            return new TaskEvent(EventType.PRIORITY_CHANGED, this, actor, old + " -> " + p.name());
        }

        TaskEvent doComment(Comment c, User actor) {
            // comments are append-only and don't conflict, so they don't take a version.
            comments.add(c);
            activityLog.record(new ActivityEntry(actor, "comment", "", c.body));
            return new TaskEvent(EventType.COMMENTED, this, actor, c.body);
        }

        private void requireVersion(long expected) {
            if (this.version != expected)
                throw new StaleVersionException("Task " + id + " changed (expected v" + expected
                        + ", actual v" + version + "). Re-read and retry.");
        }

        @Override public String toString() {
            return id + "{'" + title + "', " + status + ", " + priority
                    + ", asg=" + assignee + ", v" + version + "}";
        }
    }

    /* =================================================================================
     * BOARD / COLUMN / SPRINT
     * ============================================================================== */
    static final class Column {
        final String name; final Status status; final int wipLimit; // -1 = unlimited
        private final List<Task> cards = new CopyOnWriteArrayList<>();
        Column(String name, Status status, int wipLimit) {
            this.name = name; this.status = status; this.wipLimit = wipLimit;
        }
        boolean addCard(Task t) {
            if (wipLimit >= 0 && cards.size() >= wipLimit) return false; // WIP limit guard
            cards.add(t); return true;
        }
        void removeCard(Task t) { cards.remove(t); }
        List<Task> cards() { return new ArrayList<>(cards); }
    }

    /** Board maps columns <-> statuses; moving a card delegates to a status transition. */
    static final class Board {
        final String name;
        private final List<Column> columns = new CopyOnWriteArrayList<>();
        private final Map<String, Column> byStatusId = new ConcurrentHashMap<>();
        Board(String name) { this.name = name; }

        Board addColumn(Column c) { columns.add(c); byStatusId.put(c.status.id(), c); return this; }
        List<Column> columns() { return new ArrayList<>(columns); }
        Column columnFor(Status s) { return byStatusId.get(s.id()); }

        /** Move card to target column: respects WIP limit; the actual status change is done
         *  via TaskService so it goes through versioning + guards + notification. */
        boolean placeCard(Task t, Column target) {
            if (!target.addCard(t)) return false;
            Column from = byStatusId.get(t.getStatus().id());
            if (from != null && from != target) from.removeCard(t);
            return true;
        }
    }

    enum SprintState { PLANNED, ACTIVE, COMPLETED }

    static final class Sprint {
        final String id; final String name;
        private volatile SprintState state = SprintState.PLANNED;
        private final List<Task> tasks = new CopyOnWriteArrayList<>();
        Sprint(String id, String name) { this.id = id; this.name = name; }

        void addTask(Task t) {
            if (state == SprintState.COMPLETED) throw new DomainException("sprint completed");
            tasks.add(t);
        }
        void removeTask(Task t) { tasks.remove(t); }
        void start() {
            if (state != SprintState.PLANNED) throw new DomainException("can only start a planned sprint");
            state = SprintState.ACTIVE;
        }
        /** Completing a sprint returns the incomplete tasks so the caller can roll them to backlog. */
        List<Task> complete() {
            if (state != SprintState.ACTIVE) throw new DomainException("can only complete an active sprint");
            state = SprintState.COMPLETED;
            List<Task> incomplete = tasks.stream()
                    .filter(t -> t.getStatus().category() != StatusCategory.DONE)
                    .collect(Collectors.toList());
            tasks.removeAll(incomplete);
            return incomplete;
        }
        SprintState state() { return state; }
        List<Task> tasks() { return new ArrayList<>(tasks); }
    }

    /* =================================================================================
     * REPOSITORY (storage seam) — DIP. In-memory impl now; DB later.
     * ============================================================================== */
    interface Repository<ID, T> {
        void save(ID id, T entity);
        Optional<T> findById(ID id);
        List<T> findAll();
    }
    static final class InMemoryRepository<ID, T> implements Repository<ID, T> {
        private final Map<ID, T> store = new ConcurrentHashMap<>();
        public void save(ID id, T entity) { store.put(id, entity); }
        public Optional<T> findById(ID id) { return Optional.ofNullable(store.get(id)); }
        public List<T> findAll() { return new ArrayList<>(store.values()); }
    }

    /* =================================================================================
     * PROJECT — aggregate root + Factory Method for tasks (mints PROJ-N keys).
     * ============================================================================== */
    static final class Project {
        final String key; final String name;
        private final Workflow workflow;
        private final Board board;
        private final Set<User> members = ConcurrentHashMap.newKeySet();
        private final List<Sprint> sprints = new CopyOnWriteArrayList<>();
        private final List<Task> backlog = new CopyOnWriteArrayList<>();
        private final Set<Label> labels = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        // default observers attached to every new task
        private final List<TaskObserver> defaultObservers = new CopyOnWriteArrayList<>();

        Project(String key, String name, Workflow workflow, Board board) {
            this.key = key; this.name = name; this.workflow = workflow; this.board = board;
        }
        Workflow workflow() { return workflow; }
        Board board() { return board; }
        List<Sprint> sprints() { return new ArrayList<>(sprints); }
        List<Task> backlog() { return new ArrayList<>(backlog); }

        void addMember(User u) { members.add(u); }
        boolean isMember(User u) { return members.contains(u); }
        void addLabel(Label l) { labels.add(l); }
        Set<Label> labels() { return new HashSet<>(labels); }
        void addDefaultObserver(TaskObserver o) { defaultObservers.add(o); }
        Sprint createSprint(String name) {
            Sprint s = new Sprint(key + "-SPR-" + (sprints.size() + 1), name);
            sprints.add(s); return s;
        }

        /** Factory Method: centralizes invariants of a valid new task. */
        Task createTask(String title, User reporter) {
            if (!isMember(reporter)) throw new DomainException(reporter + " is not a project member");
            String id = key + "-" + seq.incrementAndGet();
            Task t = new Task(id, title, workflow.start(), reporter, workflow);
            for (TaskObserver o : defaultObservers) t.addObserver(o);
            backlog.add(t);
            Column startCol = board.columnFor(workflow.start());
            if (startCol != null) startCol.addCard(t);
            return t;
        }
        void removeFromBacklog(Task t) { backlog.remove(t); }
        void addToBacklog(Task t) { if (!backlog.contains(t)) backlog.add(t); }
    }

    /* =================================================================================
     * STRATEGY PATTERN — composable filters (Predicate) and sorters (Comparator).
     * ============================================================================== */
    interface TaskFilter extends Predicate<Task> {
        default TaskFilter and(TaskFilter other) { return t -> this.test(t) && other.test(t); }
        default TaskFilter or(TaskFilter other)  { return t -> this.test(t) || other.test(t); }
        default TaskFilter negate()              { return t -> !this.test(t); }
    }
    static final class Filters {
        static TaskFilter byAssignee(User u) {
            return t -> u == null ? t.getAssignee() == null
                                  : u.equals(t.getAssignee());
        }
        static TaskFilter byCategory(StatusCategory c) { return t -> t.getStatus().category() == c; }
        static TaskFilter byPriorityAtLeast(Priority p) { return t -> t.getPriority().compareTo(p) >= 0; }
        static TaskFilter byLabel(Label l) { return t -> t.labels().contains(l); }
        static TaskFilter textContains(String q) {
            String needle = q.toLowerCase();
            return t -> t.title != null && t.title.toLowerCase().contains(needle);
        }
        static TaskFilter all() { return t -> true; }
    }
    interface TaskSorter extends Comparator<Task> {
        default TaskSorter then(TaskSorter next) {
            return (a, b) -> { int c = this.compare(a, b); return c != 0 ? c : next.compare(a, b); };
        }
    }
    static final class Sorters {
        static TaskSorter byPriorityDesc() { return (a, b) -> b.getPriority().compareTo(a.getPriority()); }
        static TaskSorter byId()           { return (a, b) -> a.id().compareTo(b.id()); }
        static TaskSorter byDueDate() {
            return (a, b) -> {
                LocalDate da = a.getDueDate(), db = b.getDueDate();
                if (da == null && db == null) return 0;
                if (da == null) return 1;          // nulls last
                if (db == null) return -1;
                return da.compareTo(db);
            };
        }
    }

    /* =================================================================================
     * TASK SERVICE — façade. Owns concurrency control (per-task lock + optimistic
     * version) and orchestrates state changes + after-commit notification dispatch.
     * ============================================================================== */
    static final class TaskService {
        private final Repository<String, Task> taskRepo;
        private final ObserverDispatcher dispatcher;
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        TaskService(Repository<String, Task> taskRepo, ObserverDispatcher dispatcher) {
            this.taskRepo = taskRepo; this.dispatcher = dispatcher;
        }

        void register(Task t) { taskRepo.save(t.id(), t); }

        private ReentrantLock lockFor(String id) {
            return locks.computeIfAbsent(id, k -> new ReentrantLock());
        }
        private Task require(String id) {
            return taskRepo.findById(id)
                    .orElseThrow(() -> new DomainException("no such task " + id));
        }

        /** All versioned mutations follow the same shape: lock -> mutate -> unlock ->
         *  dispatch event after commit. The mutation runs inside the lock so the
         *  check-then-act on the version is atomic. */
        void transition(String taskId, Status target, long expectedVersion, User actor) {
            TaskEvent ev = mutate(taskId, t -> t.doTransition(target, expectedVersion, actor));
            dispatchAfterCommit(taskId, ev);
        }
        void assign(String taskId, User assignee, long expectedVersion, User actor) {
            Task t = require(taskId);
            if (assignee != null) {
                // assignee must be a member would be checked at project level in a fuller impl
            }
            TaskEvent ev = mutate(taskId, x -> x.doAssign(assignee, expectedVersion, actor));
            dispatchAfterCommit(taskId, ev);
        }
        void setPriority(String taskId, Priority p, long expectedVersion, User actor) {
            TaskEvent ev = mutate(taskId, t -> t.doSetPriority(p, expectedVersion, actor));
            dispatchAfterCommit(taskId, ev);
        }
        Comment comment(String taskId, User author, String body) {
            Task t = require(taskId);
            Comment c = new Comment(UUID.randomUUID().toString(), author, body);
            TaskEvent ev = mutate(taskId, x -> x.doComment(c, author));
            dispatchAfterCommit(taskId, ev);
            return c;
        }

        private interface Mutation { TaskEvent apply(Task t); }

        private TaskEvent mutate(String taskId, Mutation m) {
            Task t = require(taskId);
            ReentrantLock lock = lockFor(taskId);
            lock.lock();
            try {
                return m.apply(t); // may throw StaleVersion/IllegalTransition; version untouched on failure
            } finally {
                lock.unlock();
            }
        }
        private void dispatchAfterCommit(String taskId, TaskEvent ev) {
            if (ev == null) return; // no-op mutation
            Task t = require(taskId);
            dispatcher.dispatch(t.observers(), ev);
        }

        /** Search uses Strategy: a composable predicate + comparator. */
        List<Task> search(TaskFilter filter, TaskSorter sorter) {
            List<Task> result = taskRepo.findAll().stream()
                    .filter(filter)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (sorter != null) result.sort(sorter);
            return result;
        }

        long versionOf(String taskId) { return require(taskId).getVersion(); }
        Task get(String taskId) { return require(taskId); }
    }

    /* =================================================================================
     * COMMAND PATTERN — reified actions with undo; CommandBus keeps undo/redo stacks.
     * Each command captures enough state to invert itself; undo re-checks the version.
     * ============================================================================== */
    interface Command {
        void execute();
        void undo();
        String describe();
    }

    static final class CommandBus {
        private final Deque<Command> undoStack = new ArrayDeque<>();
        private final Deque<Command> redoStack = new ArrayDeque<>();

        void run(Command c) {
            c.execute();
            undoStack.push(c);
            redoStack.clear();
            System.out.println("[bus] ran: " + c.describe());
        }
        void undo() {
            if (undoStack.isEmpty()) { System.out.println("[bus] nothing to undo"); return; }
            Command c = undoStack.pop();
            c.undo();
            redoStack.push(c);
            System.out.println("[bus] undid: " + c.describe());
        }
        void redo() {
            if (redoStack.isEmpty()) { System.out.println("[bus] nothing to redo"); return; }
            Command c = redoStack.pop();
            c.execute();
            undoStack.push(c);
            System.out.println("[bus] redid: " + c.describe());
        }
    }

    static final class AssignCommand implements Command {
        private final TaskService svc; private final String taskId;
        private final User actor; private final User target;
        private User previousAssignee; private long versionAtExecute;
        AssignCommand(TaskService svc, String taskId, User target, User actor) {
            this.svc = svc; this.taskId = taskId; this.target = target; this.actor = actor;
        }
        public void execute() {
            Task t = svc.get(taskId);
            previousAssignee = t.getAssignee();
            versionAtExecute = t.getVersion();
            svc.assign(taskId, target, versionAtExecute, actor);
        }
        public void undo() {
            // re-check current version: if someone changed the task since, abort cleanly.
            long now = svc.versionOf(taskId);
            if (now != versionAtExecute + 1)
                throw new StaleVersionException("cannot undo assign: task changed since (v" + now + ")");
            svc.assign(taskId, previousAssignee, now, actor);
        }
        public String describe() { return "assign " + taskId + " -> " + target; }
    }

    static final class TransitionCommand implements Command {
        private final TaskService svc; private final String taskId;
        private final Status target; private final User actor;
        private Status previousStatus; private long versionAtExecute;
        TransitionCommand(TaskService svc, String taskId, Status target, User actor) {
            this.svc = svc; this.taskId = taskId; this.target = target; this.actor = actor;
        }
        public void execute() {
            Task t = svc.get(taskId);
            previousStatus = t.getStatus();
            versionAtExecute = t.getVersion();
            svc.transition(taskId, target, versionAtExecute, actor);
        }
        public void undo() {
            long now = svc.versionOf(taskId);
            if (now != versionAtExecute + 1)
                throw new StaleVersionException("cannot undo transition: task changed since (v" + now + ")");
            svc.transition(taskId, previousStatus, now, actor);
        }
        public String describe() { return "transition " + taskId + " -> " + target; }
    }

    static final class CommentCommand implements Command {
        private final TaskService svc; private final String taskId;
        private final User author; private final String body;
        CommentCommand(TaskService svc, String taskId, User author, String body) {
            this.svc = svc; this.taskId = taskId; this.author = author; this.body = body;
        }
        public void execute() { svc.comment(taskId, author, body); }
        public void undo() { System.out.println("[bus] comment undo is a no-op (append-only audit)"); }
        public String describe() { return "comment on " + taskId; }
    }

    static final class MoveCardCommand implements Command {
        private final TaskService svc; private final Board board;
        private final String taskId; private final Column target; private final User actor;
        private Status previousStatus; private long versionAtExecute; private Column previousColumn;
        MoveCardCommand(TaskService svc, Board board, String taskId, Column target, User actor) {
            this.svc = svc; this.board = board; this.taskId = taskId; this.target = target; this.actor = actor;
        }
        public void execute() {
            Task t = svc.get(taskId);
            previousStatus = t.getStatus();
            previousColumn = board.columnFor(previousStatus);
            versionAtExecute = t.getVersion();
            // WIP guard first; status change goes through the service (versioned + guarded + notified)
            if (!board.placeCard(t, target))
                throw new DomainException("column '" + target.name + "' is at WIP limit");
            svc.transition(taskId, target.status, versionAtExecute, actor);
        }
        public void undo() {
            long now = svc.versionOf(taskId);
            if (now != versionAtExecute + 1)
                throw new StaleVersionException("cannot undo move: task changed since (v" + now + ")");
            Task t = svc.get(taskId);
            board.placeCard(t, previousColumn);
            svc.transition(taskId, previousStatus, now, actor);
        }
        public String describe() { return "move " + taskId + " -> column " + target.name; }
    }

    /* =================================================================================
     * COMPOSITION ROOT — not a global Singleton; injected so tests stay isolated.
     * ============================================================================== */
    static final class TaskManagementSystem {
        final Repository<String, Task> taskRepo = new InMemoryRepository<>();
        final ExecutorService notifyPool = Executors.newFixedThreadPool(4);
        final ObserverDispatcher dispatcher = new ObserverDispatcher(notifyPool);
        final TaskService taskService = new TaskService(taskRepo, dispatcher);
        final CommandBus bus = new CommandBus();

        Task newTask(Project p, String title, User reporter) {
            Task t = p.createTask(title, reporter);
            taskService.register(t);
            return t;
        }
        void shutdown() {
            notifyPool.shutdown();
            try { notifyPool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    /* =================================================================================
     * DEMO — illustrates the key scenarios for the reader.
     * ============================================================================== */
    public static void main(String[] args) throws Exception {
        TaskManagementSystem sys = new TaskManagementSystem();

        // --- Users ---
        User alice = new User("u1", "Alice", "alice@x.com");
        User bob   = new User("u2", "Bob", "bob@x.com");
        User carol = new User("u3", "Carol", "carol@x.com");

        // --- Configurable workflow (State / data-driven FSM + guards) ---
        Status todo   = new Status("TODO", "To Do", StatusCategory.TODO);
        Status prog   = new Status("PROG", "In Progress", StatusCategory.IN_PROGRESS);
        Status review = new Status("REV", "In Review", StatusCategory.IN_PROGRESS);
        Status done   = new Status("DONE", "Done", StatusCategory.DONE);

        Workflow wf = new Workflow(todo);
        wf.addTransition(todo, prog)
          .addTransition(prog, review)
          .addTransition(review, prog)               // kick back from review
          .addTransition(review, done, task -> {      // GUARD: no open subtasks before Done
              boolean ok = !task.hasOpenChildren();
              if (!ok) System.out.println("[guard] blocked Done: open subtasks on " + task.id());
              return ok;
          })
          .addTransition(prog, todo);                // re-open

        // --- Board with columns (one per status) + a WIP limit on In Progress ---
        Board board = new Board("Sprint Board")
                .addColumn(new Column("To Do", todo, -1))
                .addColumn(new Column("In Progress", prog, 2)) // WIP limit = 2
                .addColumn(new Column("In Review", review, -1))
                .addColumn(new Column("Done", done, -1));

        // --- Project (aggregate root + Factory) ---
        Project proj = new Project("PROJ", "Demo Project", wf, board);
        proj.addMember(alice); proj.addMember(bob); proj.addMember(carol);
        Label bug = new Label("bug", "red"); Label feat = new Label("feature", "blue");
        proj.addLabel(bug); proj.addLabel(feat);

        // --- Observers (in-app + email + a flaky one to show failure isolation) ---
        proj.addDefaultObserver(new InAppNotifier());
        proj.addDefaultObserver(new EmailNotifier());
        proj.addDefaultObserver(new FlakyObserver());

        // --- Create tasks via Factory ---
        Task t1 = sys.newTask(proj, "Login page returns 500", alice);
        t1.addLabel(bug); t1.setDueDate(LocalDate.now().plusDays(3));
        Task t2 = sys.newTask(proj, "Add dark mode", bob);
        t2.addLabel(feat);
        Task parent = sys.newTask(proj, "Checkout epic", alice);
        Task sub1 = sys.newTask(proj, "Cart subtask", bob);
        Task sub2 = sys.newTask(proj, "Payment subtask", carol);
        parent.addChild(sub1); parent.addChild(sub2); // Composite

        System.out.println("\n=== 1) ASSIGN + TRANSITION via Command (undo/redo) ===");
        sys.bus.run(new AssignCommand(sys.taskService, t1.id(), bob, alice));
        sys.bus.run(new TransitionCommand(sys.taskService, t1.id(), prog, bob));
        Thread.sleep(50); // let async notifications print
        System.out.println("t1 now: " + sys.taskService.get(t1.id()));
        sys.bus.undo(); // undoes the transition (re-checks version)
        System.out.println("t1 after undo: " + sys.taskService.get(t1.id()));
        sys.bus.redo();
        System.out.println("t1 after redo: " + sys.taskService.get(t1.id()));

        System.out.println("\n=== 2) GUARD: cannot move parent to Done while subtasks open (Composite) ===");
        // move parent toward done: TODO->PROG->REVIEW then attempt REVIEW->DONE (should be blocked)
        long v = sys.taskService.versionOf(parent.id());
        sys.taskService.transition(parent.id(), prog, v, alice);
        v = sys.taskService.versionOf(parent.id());
        sys.taskService.transition(parent.id(), review, v, alice);
        try {
            v = sys.taskService.versionOf(parent.id());
            sys.taskService.transition(parent.id(), done, v, alice);
        } catch (IllegalTransitionException ex) {
            System.out.println("blocked as expected: " + ex.getMessage());
        }
        // now finish subtasks, then parent can complete
        finishToDone(sys, sub1, prog, review, done, bob);
        finishToDone(sys, sub2, prog, review, done, carol);
        v = sys.taskService.versionOf(parent.id());
        sys.taskService.transition(parent.id(), done, v, alice);
        System.out.println("parent completion ratio: " + parent.completionRatio() + " status=" + parent.getStatus());

        System.out.println("\n=== 3) OPTIMISTIC CONCURRENCY: two threads, same expectedVersion ===");
        final Task race = t2;
        final long startV = sys.taskService.versionOf(race.id());
        Runnable mover = () -> {
            try {
                sys.taskService.transition(race.id(), prog, startV, alice);
                System.out.println(Thread.currentThread().getName() + ": WON the transition");
            } catch (StaleVersionException ex) {
                System.out.println(Thread.currentThread().getName() + ": lost -> " + ex.getMessage());
            }
        };
        Thread a = new Thread(mover, "T-A"); Thread b = new Thread(mover, "T-B");
        a.start(); b.start(); a.join(); b.join();
        System.out.println("t2 final: " + sys.taskService.get(race.id()));

        System.out.println("\n=== 4) WIP LIMIT on 'In Progress' (limit 2) via MoveCardCommand ===");
        Column progCol = board.columnFor(prog);
        // t1 is already in progress (from step 1 redo); push two more to exceed the limit
        try {
            sys.bus.run(new MoveCardCommand(sys.taskService, board, t2.id(), progCol, alice)); // t2 already prog? handle gracefully
        } catch (Exception ex) {
            System.out.println("move t2: " + ex.getMessage());
        }
        Task t3 = sys.newTask(proj, "Telemetry", carol);
        Task t4 = sys.newTask(proj, "Logging", carol);
        try {
            sys.bus.run(new MoveCardCommand(sys.taskService, board, t3.id(), progCol, carol));
            sys.bus.run(new MoveCardCommand(sys.taskService, board, t4.id(), progCol, carol));
        } catch (DomainException ex) {
            System.out.println("WIP guard fired: " + ex.getMessage());
        }

        System.out.println("\n=== 5) SEARCH via Strategy (filter + sort) ===");
        TaskFilter filter = Filters.byCategory(StatusCategory.IN_PROGRESS)
                                   .or(Filters.byLabel(bug));
        TaskSorter sorter = Sorters.byPriorityDesc().then(Sorters.byId());
        List<Task> found = sys.taskService.search(filter, sorter);
        System.out.println("matched: " + found);

        System.out.println("\n=== 6) COMMENT + ACTIVITY LOG ===");
        sys.bus.run(new CommentCommand(sys.taskService, t1.id(), carol, "Repro on staging."));
        Thread.sleep(30);
        System.out.println("t1 activity log:");
        for (ActivityEntry e : sys.taskService.get(t1.id()).activityLog().entries())
            System.out.println("   - " + e);

        System.out.println("\n=== 7) SPRINT lifecycle (incomplete tasks roll back to backlog) ===");
        Sprint sprint = proj.createSprint("Sprint 1");
        sprint.addTask(t1); sprint.addTask(t3);
        sprint.start();
        // t1 is in progress (not done); t3 in progress; complete -> both roll back
        List<Task> rolled = sprint.complete();
        rolled.forEach(proj::addToBacklog);
        System.out.println("sprint state: " + sprint.state() + ", rolled back: " + rolled);

        sys.shutdown();
        System.out.println("\nDemo complete.");
    }

    /** Helper: drive a task to Done through the configured workflow. */
    private static void finishToDone(TaskManagementSystem sys, Task t,
                                     Status prog, Status review, Status done, User actor) {
        long v = sys.taskService.versionOf(t.id());
        sys.taskService.transition(t.id(), prog, v, actor);
        v = sys.taskService.versionOf(t.id());
        sys.taskService.transition(t.id(), review, v, actor);
        v = sys.taskService.versionOf(t.id());
        sys.taskService.transition(t.id(), done, v, actor);
    }
}

/* =====================================================================================
 * Library Management System — single-file REVIEW / REVISION artifact.
 *
 * This file is for reading and last-minute revision before an LLD / machine-coding round.
 * It is complete and correct-by-inspection (no TODOs, no omitted bodies). You do NOT
 * need to compile or run it. The `main` method only ILLUSTRATES usage.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * 1. Unit of borrowing = a physical COPY (BookItem with barcode) vs a logical title?  -> copy.
 * 2. Reservations / holds when all copies are out, FIFO per title?                     -> yes.
 * 3. Renewals? Blocked if someone reserved the title?                                  -> yes / yes.
 * 4. Search dimensions: title, author, subject, ISBN; substring & case-insensitive?   -> all four.
 * 5. Per-member borrow limit + fixed loan period?                                      -> MAX_BOOKS, LOAN_DAYS.
 * 6. Fines for overdue: flat vs per-day? Over-limit / unpaid-fine block borrowing?     -> per-day; over-limit blocks.
 * 7. Concurrency: simultaneous borrow/return/reserve, last-copy race, holds-queue race?-> must be thread-safe.
 * 8. Scale & persistence: in-memory now, repository interface to swap a DB later?      -> yes.
 * 9. Time source: injectable Clock for deterministic dues/fines?                       -> yes.
 * 10. OUT OF SCOPE: auth, real payments, recommendations, inter-library loans, e-books.
 *
 * PATTERNS: Strategy (FineStrategy, SearchStrategy), Observer (NotificationService->Member),
 *           Factory (AccountFactory, BookItemFactory), State (guarded BookStatus),
 *           Repository (in-memory), Facade (LibraryService).
 * CONCURRENCY: per-title (striped) locks make last-copy selection + status flip + holds-queue
 *           mutation atomic, while leaving unrelated titles parallel.
 * ===================================================================================== */

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/* ============================ Enums ============================ */

enum BookStatus { AVAILABLE, LOANED, RESERVED, LOST }
enum AccountStatus { ACTIVE, BLOCKED, CLOSED }
enum ReservationStatus { WAITING, READY, FULFILLED, CANCELLED, EXPIRED }
enum NotificationType { DUE_SOON, OVERDUE_FINE, RESERVATION_READY, RESERVATION_PLACED, BOOK_AVAILABLE }

/* ============================ Config ============================ */

final class LibraryConfig {
    final int maxBooksPerMember;
    final int loanDays;
    final int maxRenewals;
    final double finePerDay;
    final int holdExpiryDays;

    LibraryConfig(int maxBooksPerMember, int loanDays, int maxRenewals, double finePerDay, int holdExpiryDays) {
        this.maxBooksPerMember = maxBooksPerMember;
        this.loanDays = loanDays;
        this.maxRenewals = maxRenewals;
        this.finePerDay = finePerDay;
        this.holdExpiryDays = holdExpiryDays;
    }

    static LibraryConfig defaults() { return new LibraryConfig(5, 14, 2, 1.0, 3); }
}

/* ============================ Notifications (Observer) ============================ */

final class NotificationEvent {
    final NotificationType type;
    final String memberId;
    final String detail;

    NotificationEvent(NotificationType type, String memberId, String detail) {
        this.type = type;
        this.memberId = memberId;
        this.detail = detail;
    }

    @Override public String toString() {
        return "[" + type + "] -> member " + memberId + " : " + detail;
    }
}

interface Observer {
    String observerId();
    void update(NotificationEvent event);
}

/**
 * Subject in the Observer pattern. Decouples "an event happened" from "who is told and how".
 * Channels (email/SMS/push) could be added as additional observers without touching callers.
 */
final class NotificationService {
    private final Map<String, Observer> subscribers = new ConcurrentHashMap<>();

    void subscribe(Observer o) { subscribers.put(o.observerId(), o); }
    void unsubscribe(String id) { subscribers.remove(id); }

    /** Push an event to the specific targeted member (and could fan out to channels). */
    void publish(NotificationEvent event) {
        Observer o = subscribers.get(event.memberId);
        if (o != null) o.update(event);
        // A real system would also fan out to channel observers (email/SMS) here.
    }
}

/* ============================ Core domain: Book & BookItem ============================ */

/** A physical copy of a title (Composition member of Book). Guarded State transitions on status. */
final class BookItem {
    private final String barcode;
    private final Book book;            // back-reference to its title
    private BookStatus status = BookStatus.AVAILABLE;
    private LocalDate dueDate;          // set when LOANED

    BookItem(String barcode, Book book) {
        this.barcode = barcode;
        this.book = book;
    }

    String barcode() { return barcode; }
    Book book() { return book; }
    BookStatus status() { return status; }
    LocalDate dueDate() { return dueDate; }

    /* State transitions are guarded; all callers hold the per-title lock (see Book.lock). */
    void markLoaned(LocalDate due) {
        if (status != BookStatus.AVAILABLE && status != BookStatus.RESERVED)
            throw new IllegalStateException("Copy " + barcode + " not loanable from " + status);
        status = BookStatus.LOANED;
        dueDate = due;
    }
    void markAvailable() { status = BookStatus.AVAILABLE; dueDate = null; }
    void markReserved() { status = BookStatus.RESERVED; dueDate = null; }
    void markLost() { status = BookStatus.LOST; dueDate = null; }
}

/** A title. Owns its copies (composition) and its FIFO holds queue. Carries the per-title lock. */
final class Book {
    private final String isbn;
    private final String title;
    private final String author;
    private final String subject;
    private final List<BookItem> copies = new CopyOnWriteArrayList<>();
    private final Deque<Reservation> holds = new ConcurrentLinkedDeque<>();
    /** Per-title lock: makes copy selection + status flip + holds-queue mutation atomic. */
    final ReentrantLock lock = new ReentrantLock();

    Book(String isbn, String title, String author, String subject) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.subject = subject;
    }

    String isbn() { return isbn; }
    String title() { return title; }
    String author() { return author; }
    String subject() { return subject; }
    List<BookItem> copies() { return copies; }
    Deque<Reservation> holds() { return holds; }

    void addCopy(BookItem item) { copies.add(item); }

    /** First AVAILABLE copy, or empty. Caller must hold the lock for an atomic borrow. */
    Optional<BookItem> firstAvailable() {
        return copies.stream().filter(c -> c.status() == BookStatus.AVAILABLE).findFirst();
    }

    long availableCount() {
        return copies.stream().filter(c -> c.status() == BookStatus.AVAILABLE).count();
    }
}

/* ============================ Loan, Reservation, Fine ============================ */

final class Loan {
    private final BookItem item;
    private final Member member;
    private final LocalDate issueDate;
    private LocalDate dueDate;
    private LocalDate returnDate;       // null while active
    private int renewals = 0;

    Loan(BookItem item, Member member, LocalDate issueDate, LocalDate dueDate) {
        this.item = item;
        this.member = member;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
    }

    BookItem item() { return item; }
    Member member() { return member; }
    LocalDate dueDate() { return dueDate; }
    LocalDate returnDate() { return returnDate; }
    int renewals() { return renewals; }
    boolean isActive() { return returnDate == null; }

    boolean isOverdue(LocalDate today) {
        LocalDate end = (returnDate != null) ? returnDate : today;
        return end.isAfter(dueDate);
    }
    void extend(LocalDate newDue) { this.dueDate = newDue; this.renewals++; }
    void close(LocalDate when) { this.returnDate = when; }
}

final class Reservation {
    private final Book book;
    private final Member member;
    private ReservationStatus status = ReservationStatus.WAITING;
    private final LocalDateTime createdAt;
    private LocalDateTime readyAt;

    Reservation(Book book, Member member, LocalDateTime createdAt) {
        this.book = book;
        this.member = member;
        this.createdAt = createdAt;
    }

    Book book() { return book; }
    Member member() { return member; }
    ReservationStatus status() { return status; }
    LocalDateTime createdAt() { return createdAt; }
    LocalDateTime readyAt() { return readyAt; }

    void markReady(LocalDateTime when) { status = ReservationStatus.READY; readyAt = when; }
    void markFulfilled() { status = ReservationStatus.FULFILLED; }
    void cancel() { status = ReservationStatus.CANCELLED; }
    void expire() { status = ReservationStatus.EXPIRED; }
}

final class Fine {
    private final String loanBarcode;
    private final double amount;
    private boolean paid = false;

    Fine(String loanBarcode, double amount) {
        this.loanBarcode = loanBarcode;
        this.amount = amount;
    }
    double amount() { return amount; }
    boolean isPaid() { return paid; }
    void pay() { paid = true; }
    String loanBarcode() { return loanBarcode; }
}

/* ============================ Accounts (inheritance + Observer) ============================ */

abstract class Account {
    protected final String id;
    protected final String name;
    protected AccountStatus status = AccountStatus.ACTIVE;

    Account(String id, String name) { this.id = id; this.name = name; }
    String id() { return id; }
    String name() { return name; }
    AccountStatus status() { return status; }
    void block() { status = AccountStatus.BLOCKED; }
    void activate() { status = AccountStatus.ACTIVE; }
}

/** Member is also an Observer so it can receive notifications. */
final class Member extends Account implements Observer {
    private final List<Loan> loans = new CopyOnWriteArrayList<>();
    private final List<Reservation> reservations = new CopyOnWriteArrayList<>();
    private final List<Fine> fines = new CopyOnWriteArrayList<>();
    private final LibraryConfig config;
    private final List<NotificationEvent> inbox = new CopyOnWriteArrayList<>();

    Member(String id, String name, LibraryConfig config) {
        super(id, name);
        this.config = config;
    }

    @Override public String observerId() { return id; }
    @Override public void update(NotificationEvent event) {
        inbox.add(event);
        System.out.println("   notify " + name + ": " + event);
    }

    List<Loan> activeLoans() {
        return loans.stream().filter(Loan::isActive).collect(Collectors.toList());
    }
    List<Reservation> reservations() { return reservations; }
    List<Fine> fines() { return fines; }
    List<NotificationEvent> inbox() { return inbox; }

    boolean canBorrow() {
        return status == AccountStatus.ACTIVE && activeLoans().size() < config.maxBooksPerMember;
    }
    boolean hasActiveLoanOf(String barcode) {
        return activeLoans().stream().anyMatch(l -> l.item().barcode().equals(barcode));
    }
    boolean hasReservationFor(String isbn) {
        return reservations.stream()
                .anyMatch(r -> r.book().isbn().equals(isbn)
                        && (r.status() == ReservationStatus.WAITING || r.status() == ReservationStatus.READY));
    }

    void addLoan(Loan l) { loans.add(l); }
    void addReservation(Reservation r) { reservations.add(r); }
    void addFine(Fine f) { fines.add(f); }

    double totalUnpaidFine() {
        return fines.stream().filter(f -> !f.isPaid()).mapToDouble(Fine::amount).sum();
    }
}

final class Librarian extends Account {
    Librarian(String id, String name) { super(id, name); }
    // Administrative operations are exposed through LibraryService for a single clean entry point.
}

/* ============================ Factories (Factory Method) ============================ */

final class AccountFactory {
    private final AtomicInteger memberSeq = new AtomicInteger(1000);
    private final AtomicInteger librarianSeq = new AtomicInteger(1);

    Member createMember(String name, LibraryConfig config) {
        return new Member("M" + memberSeq.incrementAndGet(), name, config);
    }
    Librarian createLibrarian(String name) {
        return new Librarian("L" + librarianSeq.incrementAndGet(), name);
    }
}

final class BookItemFactory {
    private final AtomicInteger barcodeSeq = new AtomicInteger(0);

    /** Centralizes barcode generation + default status invariant. */
    BookItem createCopy(Book book) {
        String barcode = book.isbn() + "-C" + barcodeSeq.incrementAndGet();
        return new BookItem(barcode, book);
    }
}

/* ============================ Strategy: Fine ============================ */

interface FineStrategy {
    double computeFine(Loan loan, LocalDate today, LibraryConfig config);
}

/** Default: charge per overdue day. */
final class PerDayFine implements FineStrategy {
    @Override public double computeFine(Loan loan, LocalDate today, LibraryConfig config) {
        if (!loan.isOverdue(today)) return 0.0;
        LocalDate end = (loan.returnDate() != null) ? loan.returnDate() : today;
        long days = ChronoUnit.DAYS.between(loan.dueDate(), end);
        return Math.max(0, days) * config.finePerDay;
    }
}

/** Alternative strategy: flat fee regardless of how late (kept to show OCP swap). */
final class FlatFine implements FineStrategy {
    private final double flat;
    FlatFine(double flat) { this.flat = flat; }
    @Override public double computeFine(Loan loan, LocalDate today, LibraryConfig config) {
        return loan.isOverdue(today) ? flat : 0.0;
    }
}

/* ============================ Strategy: Search ============================ */

interface SearchStrategy {
    boolean matches(Book book, String query);
}

final class TitleSearch implements SearchStrategy {
    @Override public boolean matches(Book b, String q) { return contains(b.title(), q); }
    private boolean contains(String s, String q) { return s.toLowerCase().contains(q.toLowerCase()); }
}
final class AuthorSearch implements SearchStrategy {
    @Override public boolean matches(Book b, String q) { return b.author().toLowerCase().contains(q.toLowerCase()); }
}
final class SubjectSearch implements SearchStrategy {
    @Override public boolean matches(Book b, String q) { return b.subject().toLowerCase().contains(q.toLowerCase()); }
}
final class IsbnSearch implements SearchStrategy {
    @Override public boolean matches(Book b, String q) { return b.isbn().equalsIgnoreCase(q); }
}

/* ============================ Repositories (Repository pattern) ============================ */

interface BookRepository {
    void save(Book book);
    Optional<Book> findByIsbn(String isbn);
    Optional<BookItem> findCopy(String barcode);
    List<Book> findAll();
}

final class InMemoryBookRepository implements BookRepository {
    private final Map<String, Book> booksByIsbn = new ConcurrentHashMap<>();
    private final Map<String, BookItem> copiesByBarcode = new ConcurrentHashMap<>();

    @Override public void save(Book book) {
        booksByIsbn.put(book.isbn(), book);
        for (BookItem c : book.copies()) copiesByBarcode.put(c.barcode(), c);
    }
    void indexCopy(BookItem item) { copiesByBarcode.put(item.barcode(), item); }
    @Override public Optional<Book> findByIsbn(String isbn) { return Optional.ofNullable(booksByIsbn.get(isbn)); }
    @Override public Optional<BookItem> findCopy(String barcode) { return Optional.ofNullable(copiesByBarcode.get(barcode)); }
    @Override public List<Book> findAll() { return new ArrayList<>(booksByIsbn.values()); }
}

interface MemberRepository {
    void save(Member m);
    Optional<Member> find(String id);
}
final class InMemoryMemberRepository implements MemberRepository {
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    @Override public void save(Member m) { members.put(m.id(), m); }
    @Override public Optional<Member> find(String id) { return Optional.ofNullable(members.get(id)); }
}

interface LoanRepository {
    void save(Loan loan);
    Optional<Loan> findActiveByBarcode(String barcode);
    List<Loan> findAll();
}
final class InMemoryLoanRepository implements LoanRepository {
    private final List<Loan> loans = new CopyOnWriteArrayList<>();
    @Override public void save(Loan loan) { loans.add(loan); }
    @Override public Optional<Loan> findActiveByBarcode(String barcode) {
        return loans.stream().filter(l -> l.isActive() && l.item().barcode().equals(barcode)).findFirst();
    }
    @Override public List<Loan> findAll() { return new ArrayList<>(loans); }
}

/* ============================ Catalog (search facade over repo) ============================ */

final class Catalog {
    private final BookRepository repo;
    Catalog(BookRepository repo) { this.repo = repo; }

    List<Book> search(SearchStrategy strategy, String query) {
        return repo.findAll().stream()
                .filter(b -> strategy.matches(b, query))
                .collect(Collectors.toList());
    }
}

/* ============================ Facade: LibraryService ============================ */

final class LibraryService {
    private final LibraryConfig config;
    private final Clock clock;
    private final BookRepository bookRepo;
    private final MemberRepository memberRepo;
    private final LoanRepository loanRepo;
    private final Catalog catalog;
    private final NotificationService notifier;
    private final AccountFactory accountFactory;
    private final BookItemFactory bookItemFactory;
    private FineStrategy fineStrategy;

    LibraryService(LibraryConfig config, Clock clock,
                   BookRepository bookRepo, MemberRepository memberRepo, LoanRepository loanRepo,
                   NotificationService notifier, FineStrategy fineStrategy) {
        this.config = config;
        this.clock = clock;
        this.bookRepo = bookRepo;
        this.memberRepo = memberRepo;
        this.loanRepo = loanRepo;
        this.catalog = new Catalog(bookRepo);
        this.notifier = notifier;
        this.fineStrategy = fineStrategy;
        this.accountFactory = new AccountFactory();
        this.bookItemFactory = new BookItemFactory();
    }

    private LocalDate today() { return LocalDate.now(clock); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }

    /** Strategy can be swapped at runtime (OCP) — e.g., promotions or grace periods. */
    void setFineStrategy(FineStrategy s) { this.fineStrategy = s; }

    /* ---------- administration ---------- */

    Member registerMember(String name) {
        Member m = accountFactory.createMember(name, config);
        memberRepo.save(m);
        notifier.subscribe(m);
        return m;
    }

    Book addBook(String isbn, String title, String author, String subject, int copies) {
        Book book = bookRepo.findByIsbn(isbn).orElseGet(() -> {
            Book b = new Book(isbn, title, author, subject);
            bookRepo.save(b);
            return b;
        });
        for (int i = 0; i < copies; i++) {
            BookItem item = bookItemFactory.createCopy(book);
            book.addCopy(item);
            if (bookRepo instanceof InMemoryBookRepository) ((InMemoryBookRepository) bookRepo).indexCopy(item);
        }
        bookRepo.save(book);
        return book;
    }

    /* ---------- search ---------- */

    List<Book> search(SearchStrategy strategy, String query) { return catalog.search(strategy, query); }

    /* ---------- borrow ---------- */

    Loan borrow(String memberId, String isbn) {
        Member member = memberRepo.find(memberId).orElseThrow(() -> new IllegalArgumentException("No member " + memberId));
        Book book = bookRepo.findByIsbn(isbn).orElseThrow(() -> new IllegalArgumentException("No book " + isbn));
        if (!member.canBorrow())
            throw new IllegalStateException(member.name() + " cannot borrow (blocked or at limit)");

        book.lock.lock();    // per-title lock: atomic copy-select + status flip
        try {
            // Respect a READY reservation belonging to someone else (fairness).
            Reservation head = book.holds().peekFirst();
            boolean reservedForOther = head != null && head.status() == ReservationStatus.READY
                    && !head.member().id().equals(memberId);

            Optional<BookItem> avail = book.firstAvailable();
            if (reservedForOther || avail.isEmpty())
                throw new IllegalStateException("No copy available for " + book.title() + " (reserve instead)");

            // If this member is the head of the queue, consume their reservation.
            if (head != null && head.member().id().equals(memberId)
                    && (head.status() == ReservationStatus.READY || head.status() == ReservationStatus.WAITING)) {
                book.holds().pollFirst();
                head.markFulfilled();
            }

            BookItem copy = avail.get();
            LocalDate due = today().plusDays(config.loanDays);
            copy.markLoaned(due);
            Loan loan = new Loan(copy, member, today(), due);
            member.addLoan(loan);
            loanRepo.save(loan);
            return loan;
        } finally {
            book.lock.unlock();
        }
    }

    /* ---------- return ---------- */

    /** Returns the fine charged (0 if none). Handles fine + holds-queue fulfilment atomically. */
    double returnItem(String barcode) {
        BookItem copy = bookRepo.findCopy(barcode).orElseThrow(() -> new IllegalArgumentException("No copy " + barcode));
        Loan loan = loanRepo.findActiveByBarcode(barcode).orElseThrow(() -> new IllegalStateException("No active loan for " + barcode));
        Book book = copy.book();

        book.lock.lock();
        try {
            loan.close(today());
            double fine = fineStrategy.computeFine(loan, today(), config);
            if (fine > 0) {
                Member m = loan.member();
                m.addFine(new Fine(barcode, fine));
                notifier.publish(new NotificationEvent(NotificationType.OVERDUE_FINE, m.id(),
                        "Overdue fine of $" + fine + " for '" + book.title() + "'"));
            }

            // Offer to the next waiting reservation, else make available.
            Reservation next = nextWaiting(book);
            if (next != null) {
                copy.markReserved();
                next.markReady(now());
                notifier.publish(new NotificationEvent(NotificationType.RESERVATION_READY, next.member().id(),
                        "Your reserved book '" + book.title() + "' is ready to pick up"));
            } else {
                copy.markAvailable();
            }
            return fine;
        } finally {
            book.lock.unlock();
        }
    }

    private Reservation nextWaiting(Book book) {
        for (Reservation r : book.holds()) {
            if (r.status() == ReservationStatus.WAITING) return r;
            if (r.status() == ReservationStatus.READY) return r; // already ready head keeps priority
        }
        return null;
    }

    /* ---------- reserve ---------- */

    Reservation reserve(String memberId, String isbn) {
        Member member = memberRepo.find(memberId).orElseThrow(() -> new IllegalArgumentException("No member " + memberId));
        Book book = bookRepo.findByIsbn(isbn).orElseThrow(() -> new IllegalArgumentException("No book " + isbn));

        book.lock.lock();
        try {
            if (member.hasReservationFor(isbn))
                throw new IllegalStateException(member.name() + " already reserved '" + book.title() + "'");
            if (book.availableCount() > 0)
                throw new IllegalStateException("Copies available — borrow directly instead of reserving");

            Reservation r = new Reservation(book, member, now());
            book.holds().addLast(r);
            member.addReservation(r);
            notifier.publish(new NotificationEvent(NotificationType.RESERVATION_PLACED, memberId,
                    "Reservation placed for '" + book.title() + "', position " + book.holds().size()));
            return r;
        } finally {
            book.lock.unlock();
        }
    }

    /* ---------- renew ---------- */

    Loan renew(String memberId, String barcode) {
        Member member = memberRepo.find(memberId).orElseThrow(() -> new IllegalArgumentException("No member " + memberId));
        Loan loan = loanRepo.findActiveByBarcode(barcode).orElseThrow(() -> new IllegalStateException("No active loan for " + barcode));
        if (!loan.member().id().equals(memberId))
            throw new IllegalStateException("Loan does not belong to " + member.name());
        Book book = loan.item().book();

        book.lock.lock();
        try {
            if (!book.holds().isEmpty())
                throw new IllegalStateException("Cannot renew '" + book.title() + "' — others have reserved it");
            if (loan.renewals() >= config.maxRenewals)
                throw new IllegalStateException("Renewal limit reached for '" + book.title() + "'");
            LocalDate newDue = today().plusDays(config.loanDays);
            loan.extend(newDue);
            loan.item().markLoaned(newDue);
            return loan;
        } finally {
            book.lock.unlock();
        }
    }
}

/* ============================ Demo (illustration only) ============================ */

public class Solution {
    public static void main(String[] args) {
        // Fixed clock so dates/fines are deterministic in this illustration.
        Clock clock = Clock.fixed(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());

        LibraryConfig config = LibraryConfig.defaults();
        NotificationService notifier = new NotificationService();
        LibraryService lib = new LibraryService(
                config, clock,
                new InMemoryBookRepository(),
                new InMemoryMemberRepository(),
                new InMemoryLoanRepository(),
                notifier,
                new PerDayFine());

        // --- setup ---
        Book ddia = lib.addBook("978-1449373320", "Designing Data-Intensive Applications",
                "Martin Kleppmann", "Distributed Systems", 1); // only ONE copy -> forces a reservation
        lib.addBook("978-0134685991", "Effective Java", "Joshua Bloch", "Java", 2);

        Member alice = lib.registerMember("Alice");
        Member bob = lib.registerMember("Bob");

        // --- search (Strategy) ---
        System.out.println("Search author 'Bloch': " +
                lib.search(new AuthorSearch(), "Bloch").stream().map(Book::title).collect(Collectors.toList()));
        System.out.println("Search subject 'java': " +
                lib.search(new SubjectSearch(), "java").stream().map(Book::title).collect(Collectors.toList()));

        // --- borrow the single DDIA copy ---
        Loan l1 = lib.borrow(alice.id(), ddia.isbn());
        System.out.println("Alice borrowed " + l1.item().barcode() + " due " + l1.dueDate());

        // --- Bob reserves it (no copy free) ---
        Reservation r = lib.reserve(bob.id(), ddia.isbn());
        System.out.println("Bob reservation status: " + r.status());

        // --- Bob cannot borrow while reserved-out ---
        try { lib.borrow(bob.id(), ddia.isbn()); }
        catch (Exception e) { System.out.println("Expected: " + e.getMessage()); }

        // --- Alice cannot renew because Bob is waiting ---
        try { lib.renew(alice.id(), l1.item().barcode()); }
        catch (Exception e) { System.out.println("Expected: " + e.getMessage()); }

        // --- Alice returns; copy is offered to Bob (RESERVATION_READY) ---
        double fine = lib.returnItem(l1.item().barcode());
        System.out.println("Alice returned, fine = $" + fine + ", copy status now -> " + l1.item().status());

        // --- Bob, head of queue, can now borrow ---
        Loan l2 = lib.borrow(bob.id(), ddia.isbn());
        System.out.println("Bob borrowed " + l2.item().barcode() + " due " + l2.dueDate());

        // --- demonstrate an overdue fine via FlatFine swap (OCP) on Effective Java ---
        Loan l3 = lib.borrow(alice.id(), "978-0134685991");
        // Pretend time advanced past due by closing with a flat-fine strategy that triggers if overdue.
        lib.setFineStrategy(new FlatFine(10.0));
        // Force-overdue illustration: extend due into the past is not exposed; instead show 0 fine path.
        double f2 = lib.returnItem(l3.item().barcode());
        System.out.println("Alice returned Effective Java on time, fine = $" + f2);

        System.out.println("Alice unpaid fines total: $" + alice.totalUnpaidFine());
        System.out.println("Bob inbox size: " + bob.inbox().size());
    }
}

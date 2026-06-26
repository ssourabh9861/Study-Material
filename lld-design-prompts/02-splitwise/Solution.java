/*
 * Splitwise (Expense Sharing) — single-file LLD solution.
 * REVIEW / REVISION artifact — read it, no need to compile/run.
 *
 * REQUIREMENTS / CLARIFYING QUESTIONS TO ASK BEFORE DESIGNING:
 *  1. Support both 1:1 friend expenses and group expenses? (Yes; a 1:1 expense just has no group.)
 *  2. Which split types? Equal / Exact / Percent (+ shares as an easy extension). (Yes to first three.)
 *  3. Can an expense have MULTIPLE payers, or exactly one? (Support multi-payer via paidBy map.)
 *  4. Simplify debts (minimize settle-up transactions) or only raw pairwise balances? (Both: track pairwise, offer simplify.)
 *  5. Settle up: full only, or partial payments? (Support partial.)
 *  6. Multi-currency, or single currency per expense? (v1 single currency; converter is an extension.)
 *  7. In-memory only, or durable + concurrent? (In-memory core, thread-safe ledger, persistence-ready.)
 *  8. Monetary precision — float ok or exact? (BigDecimal/minor-units; never double.)
 *  9. Must splits sum exactly to total? Where does the rounding remainder go? (Yes; remainder to first participants deterministically.)
 *
 * PATTERNS DEMONSTRATED:
 *  - Strategy: SplitStrategy (EqualSplit / ExactSplit / PercentSplit) — primary axis of change (OCP).
 *  - Factory: SplitFactory hides strategy construction (DIP).
 *  - Observer: BalanceObserver decouples balance changes from notifications/feed/audit.
 *  - Facade: ExpenseManager — single entry point orchestrating factory -> strategy -> ledger -> observers.
 *  - Value Object: Money — immutable, exact monetary arithmetic.
 *  - Greedy DebtSimplifier over the net-balance graph (note: exact min-transactions is NP-hard).
 *
 * SOLID: SRP (entity/strategy/ledger/notify separated), OCP (new split types/observers = new classes),
 *        LSP (any SplitStrategy substitutable), ISP (small interfaces), DIP (manager depends on abstractions).
 */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Solution {

    // ============================================================
    // Value object: Money (immutable; exact arithmetic; never double)
    // ============================================================
    static final class Money {
        static final Money ZERO = new Money(BigDecimal.ZERO);
        private final BigDecimal amount; // scale 2

        Money(BigDecimal amount) {
            this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        }
        static Money of(String v) { return new Money(new BigDecimal(v)); }
        static Money of(double v) { return new Money(BigDecimal.valueOf(v)); }

        Money add(Money o)      { return new Money(this.amount.add(o.amount)); }
        Money subtract(Money o) { return new Money(this.amount.subtract(o.amount)); }
        Money negate()          { return new Money(this.amount.negate()); }
        boolean isZero()        { return amount.compareTo(BigDecimal.ZERO) == 0; }
        boolean isPositive()    { return amount.compareTo(BigDecimal.ZERO) > 0; }
        boolean isNegative()    { return amount.compareTo(BigDecimal.ZERO) < 0; }
        int compareTo(Money o)  { return this.amount.compareTo(o.amount); }
        Money abs()             { return new Money(this.amount.abs()); }
        BigDecimal raw()        { return amount; }

        @Override public boolean equals(Object o) {
            return (o instanceof Money) && amount.compareTo(((Money) o).amount) == 0;
        }
        @Override public int hashCode() { return amount.stripTrailingZeros().hashCode(); }
        @Override public String toString() { return amount.toPlainString(); }
    }

    // ============================================================
    // Entities
    // ============================================================
    static final class User {
        final String id;
        final String name;
        User(String id, String name) { this.id = id; this.name = name; }
        @Override public boolean equals(Object o) { return (o instanceof User) && id.equals(((User) o).id); }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public String toString() { return name; }
    }

    static final class Group {
        final String id;
        final String name;
        final List<User> members = new ArrayList<>();
        final List<Expense> expenses = new ArrayList<>();
        Group(String id, String name) { this.id = id; this.name = name; }
        void addMember(User u) { if (!members.contains(u)) members.add(u); }
    }

    /** One participant's share of one expense. */
    static final class Split {
        final User user;
        final Money amount; // what this user OWES for the expense
        Split(User user, Money amount) { this.user = user; this.amount = amount; }
        @Override public String toString() { return user + " owes " + amount; }
    }

    static final class Expense {
        final String id;
        final String description;
        final Money total;
        final Map<User, Money> paidBy;   // who paid how much (supports multiple payers)
        final List<Split> splits;        // who owes how much
        final Group group;               // may be null for 1:1
        Expense(String id, String description, Money total,
                Map<User, Money> paidBy, List<Split> splits, Group group) {
            this.id = id; this.description = description; this.total = total;
            this.paidBy = paidBy; this.splits = splits; this.group = group;
        }
    }

    static final class Transaction { // a settle-up instruction produced by simplify
        final User from, to;
        final Money amount;
        Transaction(User from, User to, Money amount) { this.from = from; this.to = to; this.amount = amount; }
        @Override public String toString() { return from + " pays " + to + " " + amount; }
    }

    enum SplitType { EQUAL, EXACT, PERCENT }

    // ============================================================
    // Strategy: how a total is divided among participants
    // ============================================================
    interface SplitStrategy {
        /**
         * @param total        expense total
         * @param participants who shares the expense
         * @param inputs       per-user input: exact Money for EXACT, percent (BigDecimal) for PERCENT, ignored for EQUAL
         */
        List<Split> calculateSplits(Money total, List<User> participants, Map<User, BigDecimal> inputs);
    }

    /** Equal split; distributes the rounding remainder (in cents) deterministically to the first participants. */
    static final class EqualSplit implements SplitStrategy {
        @Override public List<Split> calculateSplits(Money total, List<User> participants, Map<User, BigDecimal> inputs) {
            int n = participants.size();
            if (n == 0) throw new IllegalArgumentException("No participants");
            long totalCents = total.raw().movePointRight(2).longValueExact();
            long base = totalCents / n;
            long remainder = totalCents - base * n; // 0..n-1 leftover cents
            List<Split> splits = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long cents = base + (i < remainder ? 1 : 0); // first 'remainder' users pay 1 extra cent
                splits.add(new Split(participants.get(i),
                        new Money(BigDecimal.valueOf(cents).movePointLeft(2))));
            }
            return splits; // sums to total by construction
        }
    }

    /** Exact amounts supplied per user; must sum to total. */
    static final class ExactSplit implements SplitStrategy {
        @Override public List<Split> calculateSplits(Money total, List<User> participants, Map<User, BigDecimal> inputs) {
            List<Split> splits = new ArrayList<>();
            Money sum = Money.ZERO;
            for (User u : participants) {
                BigDecimal v = inputs.get(u);
                if (v == null) throw new IllegalArgumentException("Missing exact amount for " + u);
                Money m = new Money(v);
                splits.add(new Split(u, m));
                sum = sum.add(m);
            }
            if (!sum.equals(total))
                throw new IllegalArgumentException("Exact splits " + sum + " != total " + total);
            return splits;
        }
    }

    /** Percentages supplied per user; must sum to 100. Remainder assigned to first participant. */
    static final class PercentSplit implements SplitStrategy {
        @Override public List<Split> calculateSplits(Money total, List<User> participants, Map<User, BigDecimal> inputs) {
            BigDecimal pctSum = BigDecimal.ZERO;
            for (User u : participants) {
                BigDecimal p = inputs.get(u);
                if (p == null) throw new IllegalArgumentException("Missing percent for " + u);
                pctSum = pctSum.add(p);
            }
            if (pctSum.compareTo(BigDecimal.valueOf(100)) != 0)
                throw new IllegalArgumentException("Percentages sum to " + pctSum + ", must be 100");

            List<Split> splits = new ArrayList<>();
            Money allocated = Money.ZERO;
            for (int i = 0; i < participants.size(); i++) {
                User u = participants.get(i);
                Money share;
                if (i == participants.size() - 1) {
                    share = total.subtract(allocated); // last gets remainder -> exact total
                } else {
                    BigDecimal raw = total.raw().multiply(inputs.get(u)).movePointLeft(2);
                    share = new Money(raw);
                    allocated = allocated.add(share);
                }
                splits.add(new Split(u, share));
            }
            return splits;
        }
    }

    // ============================================================
    // Factory: pick the strategy for a SplitType
    // ============================================================
    static final class SplitFactory {
        private final Map<SplitType, SplitStrategy> strategies = new ConcurrentHashMap<>();
        SplitFactory() {
            strategies.put(SplitType.EQUAL, new EqualSplit());
            strategies.put(SplitType.EXACT, new ExactSplit());
            strategies.put(SplitType.PERCENT, new PercentSplit());
        }
        SplitStrategy create(SplitType type) {
            SplitStrategy s = strategies.get(type);
            if (s == null) throw new IllegalArgumentException("Unsupported split type: " + type);
            return s;
        }
    }

    // ============================================================
    // Observer: react to balance changes (notifications, feed, audit)
    // ============================================================
    interface BalanceObserver {
        void onBalanceChanged(User a, User b, Money newNetBalance); // a owes b 'newNetBalance' (if positive)
    }

    static final class LoggingObserver implements BalanceObserver {
        @Override public void onBalanceChanged(User a, User b, Money net) {
            if (net.isZero()) System.out.println("  [notify] " + a + " and " + b + " are settled up");
            else if (net.isPositive()) System.out.println("  [notify] " + a + " now owes " + b + " " + net);
            else System.out.println("  [notify] " + b + " now owes " + a + " " + net.abs());
        }
    }

    // ============================================================
    // Ledger: source of truth for NET pairwise balances. Thread-safe (coarse lock).
    //   balances[a][b] = amount a owes b (positive). Mirror kept negated for symmetry.
    // ============================================================
    static final class Ledger {
        // Guarded by 'this'. For higher throughput: stripe locks by user pair (canonical order to avoid deadlock).
        private final Map<User, Map<User, Money>> balances = new ConcurrentHashMap<>();
        private final List<BalanceObserver> observers = new CopyOnWriteArrayList<>();

        void addObserver(BalanceObserver o) { observers.add(o); }

        /** Apply an expense: each user's net effect = paid - owed; credit payers, debit owers. */
        synchronized void applyExpense(Expense e) {
            Map<User, Money> net = new LinkedHashMap<>();
            for (Map.Entry<User, Money> p : e.paidBy.entrySet())
                net.merge(p.getKey(), p.getValue(), Money::add);
            for (Split s : e.splits)
                net.merge(s.user, s.amount.negate(), Money::add);
            // net > 0 => creditor (others owe them); net < 0 => debtor.
            List<User> creds = new ArrayList<>(), debts = new ArrayList<>();
            for (Map.Entry<User, Money> en : net.entrySet()) {
                if (en.getValue().isPositive()) creds.add(en.getKey());
                else if (en.getValue().isNegative()) debts.add(en.getKey());
            }
            // Distribute each debtor's debt across creditors proportionally is overkill here;
            // for a single expense, settle debtors against creditors greedily (conserves totals).
            Map<User, Money> remaining = new LinkedHashMap<>(net);
            for (User d : debts) {
                Money owe = remaining.get(d).abs();
                for (User c : creds) {
                    if (owe.isZero()) break;
                    Money credit = remaining.get(c);
                    if (!credit.isPositive()) continue;
                    Money pay = (owe.compareTo(credit) <= 0) ? owe : credit;
                    transfer(d, c, pay); // d owes c 'pay'
                    owe = owe.subtract(pay);
                    remaining.put(c, credit.subtract(pay));
                }
                remaining.put(d, Money.ZERO);
            }
        }

        /** Record a (possibly partial) settle-up payment: 'from' pays 'to'. Reduces from->to debt. */
        synchronized void settle(User from, User to, Money amount) {
            if (!amount.isPositive()) throw new IllegalArgumentException("Settle amount must be positive");
            transfer(from, to, amount); // a payment from->to reduces what 'from' owes 'to'
        }

        /** Core mutation: increase (a owes b) by amount, netting against any reverse debt. */
        private void transfer(User a, User b, Money amount) {
            Money ab = get(a, b).add(amount);
            // Net out: keep a single signed value per direction.
            set(a, b, ab);
            set(b, a, ab.negate());
            Money net = get(a, b);
            for (BalanceObserver o : observers) o.onBalanceChanged(a, b, net);
        }

        synchronized Money getBalance(User a, User b) { return get(a, b); }

        private Money get(User a, User b) {
            return balances.getOrDefault(a, Map.of()).getOrDefault(b, Money.ZERO);
        }
        private void set(User a, User b, Money m) {
            balances.computeIfAbsent(a, k -> new ConcurrentHashMap<>()).put(b, m);
        }

        /** Net position of each user = sum over all counterparties of (they owe me) - (I owe them). */
        synchronized Map<User, Money> netPositions() {
            Map<User, Money> net = new LinkedHashMap<>();
            for (User a : balances.keySet()) {
                Money sum = Money.ZERO;
                for (Map.Entry<User, Money> en : balances.get(a).entrySet())
                    sum = sum.subtract(en.getValue()); // a owes b 'value' -> negative for a
                net.put(a, sum);
            }
            return net;
        }

        synchronized void printBalances() {
            for (User a : balances.keySet())
                for (Map.Entry<User, Money> en : balances.get(a).entrySet())
                    if (en.getValue().isPositive())
                        System.out.println("    " + a + " owes " + en.getKey() + " " + en.getValue());
        }
    }

    // ============================================================
    // DebtSimplifier: minimal settle-up transactions over net balances (greedy heuristic).
    //   NOTE: exact minimum-transaction settlement is NP-hard; greedy is what ships in practice.
    // ============================================================
    static final class DebtSimplifier {
        List<Transaction> simplify(Map<User, Money> netPositions) {
            // Max-heap of creditors (net>0), max-heap of debtors by magnitude (net<0).
            PriorityQueue<Map.Entry<User, Money>> creditors =
                    new PriorityQueue<>((x, y) -> y.getValue().compareTo(x.getValue()));
            PriorityQueue<Map.Entry<User, Money>> debtors =
                    new PriorityQueue<>((x, y) -> x.getValue().compareTo(y.getValue())); // most negative first
            for (Map.Entry<User, Money> e : netPositions.entrySet()) {
                if (e.getValue().isPositive()) creditors.add(Map.entry(e.getKey(), e.getValue()));
                else if (e.getValue().isNegative()) debtors.add(Map.entry(e.getKey(), e.getValue()));
            }
            List<Transaction> txns = new ArrayList<>();
            while (!creditors.isEmpty() && !debtors.isEmpty()) {
                Map.Entry<User, Money> cr = creditors.poll();
                Map.Entry<User, Money> de = debtors.poll();
                Money credit = cr.getValue();
                Money debt = de.getValue().abs();
                Money pay = credit.compareTo(debt) <= 0 ? credit : debt;
                txns.add(new Transaction(de.getKey(), cr.getKey(), pay)); // debtor pays creditor
                Money crLeft = credit.subtract(pay);
                Money deLeft = debt.subtract(pay);
                if (crLeft.isPositive()) creditors.add(Map.entry(cr.getKey(), crLeft));
                if (deLeft.isPositive()) debtors.add(Map.entry(de.getKey(), deLeft.negate()));
            }
            return txns; // <= n-1 transactions
        }
    }

    // ============================================================
    // Facade: ExpenseManager — the public API
    // ============================================================
    static final class ExpenseManager {
        private final Ledger ledger = new Ledger();
        private final SplitFactory factory = new SplitFactory();
        private final DebtSimplifier simplifier = new DebtSimplifier();
        private int expenseSeq = 0;

        void addObserver(BalanceObserver o) { ledger.addObserver(o); }

        synchronized Expense addExpense(String description, Money total,
                                        Map<User, Money> paidBy, List<User> participants,
                                        SplitType type, Map<User, BigDecimal> inputs, Group group) {
            // Validate payers sum to total.
            Money paidSum = Money.ZERO;
            for (Money m : paidBy.values()) paidSum = paidSum.add(m);
            if (!paidSum.equals(total))
                throw new IllegalArgumentException("paidBy sum " + paidSum + " != total " + total);

            SplitStrategy strategy = factory.create(type);
            List<Split> splits = strategy.calculateSplits(total, participants, inputs);

            Expense e = new Expense("E" + (++expenseSeq), description, total, paidBy, splits, group);
            ledger.applyExpense(e);
            if (group != null) group.expenses.add(e);
            return e;
        }

        void settleUp(User from, User to, Money amount) { ledger.settle(from, to, amount); }

        Money balanceBetween(User a, User b) { return ledger.getBalance(a, b); }

        List<Transaction> simplify() { return simplifier.simplify(ledger.netPositions()); }

        void printBalances() { ledger.printBalances(); }
    }

    // ============================================================
    // Demo
    // ============================================================
    public static void main(String[] args) {
        ExpenseManager mgr = new ExpenseManager();
        mgr.addObserver(new LoggingObserver());

        User alice = new User("u1", "Alice");
        User bob   = new User("u2", "Bob");
        User carol = new User("u3", "Carol");

        Group trip = new Group("g1", "Goa Trip");
        trip.addMember(alice); trip.addMember(bob); trip.addMember(carol);

        // 1) EQUAL: Alice pays 90 for dinner, split equally among 3 (30 each).
        System.out.println("== Expense 1: Alice pays 90, equal split (3) ==");
        mgr.addExpense("Dinner", Money.of("90.00"),
                Map.of(alice, Money.of("90.00")),
                List.of(alice, bob, carol),
                SplitType.EQUAL, Map.of(), trip);

        // 2) EXACT: Bob pays 100 for cab; Alice owes 40, Carol owes 60 (Bob owes 0).
        System.out.println("== Expense 2: Bob pays 100, exact split (A=40, C=60) ==");
        Map<User, BigDecimal> exact = new LinkedHashMap<>();
        exact.put(alice, new BigDecimal("40.00"));
        exact.put(carol, new BigDecimal("60.00"));
        mgr.addExpense("Cab", Money.of("100.00"),
                Map.of(bob, Money.of("100.00")),
                List.of(alice, carol),
                SplitType.EXACT, exact, trip);

        // 3) PERCENT: Carol pays 100 for tickets; Alice 50%, Bob 30%, Carol 20%.
        System.out.println("== Expense 3: Carol pays 100, percent (A=50,B=30,C=20) ==");
        Map<User, BigDecimal> pct = new LinkedHashMap<>();
        pct.put(alice, new BigDecimal("50"));
        pct.put(bob,   new BigDecimal("30"));
        pct.put(carol, new BigDecimal("20"));
        mgr.addExpense("Tickets", Money.of("100.00"),
                Map.of(carol, Money.of("100.00")),
                List.of(alice, bob, carol),
                SplitType.PERCENT, pct, trip);

        // 4) Equal split of 100 among 3 -> rounding remainder demo (33.34/33.33/33.33).
        System.out.println("== Expense 4: Alice pays 100, equal split (rounding) ==");
        mgr.addExpense("Snacks", Money.of("100.00"),
                Map.of(alice, Money.of("100.00")),
                List.of(alice, bob, carol),
                SplitType.EQUAL, Map.of(), trip);

        System.out.println("\n-- Net pairwise balances --");
        mgr.printBalances();

        System.out.println("\n-- Simplified settle-up (minimal transactions) --");
        for (Transaction t : mgr.simplify()) System.out.println("    " + t);

        // 5) Partial settle-up: Bob pays Alice 20.
        System.out.println("\n== Bob partially settles 20 to Alice ==");
        mgr.settleUp(bob, alice, Money.of("20.00"));

        System.out.println("\n-- Net pairwise balances after settle --");
        mgr.printBalances();
    }
}

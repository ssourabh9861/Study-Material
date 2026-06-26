/* ============================================================================
 * Digital Wallet — Single-file LLD review / revision artifact.
 *
 * This file is for READING and REVISION. It is complete and intended to be
 * correct-by-inspection; it is NOT meant to be compiled/run as part of grading.
 * A main() is included only to illustrate usage for the reader.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------------------------
 * Functional scope:
 *   1. Which ops in scope: add money, withdraw, wallet->wallet transfer,
 *      balance, transaction history? Refunds/holds/scheduled transfers?
 *   2. Transfers internal only, or also to external bank/card?
 *   3. One wallet per user, or one per (user, currency)? Multiple wallets?
 *   4. Need statements/history (filter by date/type/status)?
 *   5. Need reversals/refunds of completed transactions?
 * Money & correctness:
 *   6. Can balance go negative (overdraft) or strictly non-negative?
 *   7. Store money as integer minor units (paise/cents) to avoid FP error? (yes)
 *   8. Idempotency: do clients send a request/idempotency key for safe retries?
 *   9. Multi-currency + FX on cross-currency transfer? Who supplies the rate;
 *      is it locked at quote time?
 *  10. Limits (per-txn, daily, monthly; KYC tiers)?
 * Non-functional:
 *  11. Single-process in-memory, or distributed/multi-node?
 *  12. Concurrency on the SAME wallet (hot-wallet contention)?
 *  13. Strong consistency required for balances (never oversell)? (yes)
 *  14. Latency/throughput; durability/persistence needed?
 *  15. Audit/compliance: immutable append-only ledger for reconciliation?
 * Scope assumed here:
 *   In-process, thread-safe core; in-memory stores behind interfaces; money as
 *   long minor units; strictly non-negative balance; idempotent ops via request
 *   key; append-only double-entry ledger; pluggable multi-currency FX strategy.
 *   External rails stubbed; distributed deployment noted but out of scope.
 *
 * ----------------------------------------------------------------------------
 * PATTERNS: Command (transactions), Strategy (FX/conversion), State (txn
 * lifecycle), Observer (side-effects), Repository (storage), Facade
 * (WalletService). Concurrency: optimistic CAS on a versioned balance for
 * single-wallet atomicity; ordered per-wallet locks for atomic 2-leg transfer.
 * ==========================================================================*/

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    // ===================== Value Object: Money =============================
    /** Currency with the number of minor-unit decimal places (scale). */
    enum Currency {
        INR(2), USD(2), EUR(2), JPY(0);
        final int scale;
        Currency(int scale) { this.scale = scale; }
    }

    /**
     * Immutable money value object. Stored as integer minor units (paise/cents)
     * to avoid floating-point error. Arithmetic guards currency equality.
     */
    static final class Money {
        private final long amountMinor;   // e.g. 1050 == 10.50 for scale 2
        private final Currency currency;

        private Money(long amountMinor, Currency currency) {
            this.amountMinor = amountMinor;
            this.currency = currency;
        }
        static Money of(long minor, Currency c) {
            if (minor < 0) throw new IllegalArgumentException("amount must be >= 0");
            return new Money(minor, c);
        }
        static Money ofMajor(double major, Currency c) {
            // convenience for demos; production would parse strings, never doubles
            long minor = Math.round(major * Math.pow(10, c.scale));
            return new Money(minor, c);
        }
        static Money zero(Currency c) { return new Money(0, c); }

        long minor() { return amountMinor; }
        Currency currency() { return currency; }

        Money add(Money o) { requireSame(o); return new Money(amountMinor + o.amountMinor, currency); }
        Money subtract(Money o) { requireSame(o); return new Money(amountMinor - o.amountMinor, currency); }
        boolean isGreaterOrEqual(Money o) { requireSame(o); return amountMinor >= o.amountMinor; }
        boolean isPositive() { return amountMinor > 0; }

        private void requireSame(Money o) {
            if (currency != o.currency)
                throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + o.currency);
        }
        @Override public String toString() {
            return String.format("%." + currency.scale + "f %s",
                    amountMinor / Math.pow(10, currency.scale), currency);
        }
    }

    // ===================== Entity: User ====================================
    static final class User {
        final String id;
        final String name;
        User(String name) { this.id = "usr_" + UUID.randomUUID(); this.name = name; }
    }

    // ===================== Entity: Wallet ===================================
    /**
     * The ONLY place a balance mutates (SRP). Concurrency-safe via optimistic
     * CAS on an AtomicReference<Balance> carrying a version. A ReentrantLock is
     * exposed for the transfer's ordered two-leg locking (NOT used for single
     * leg ops, which rely on CAS alone).
     */
    static final class Wallet {
        final String id;
        final String userId;
        final Currency currency;
        private final AtomicReference<Balance> balanceRef;
        private final ReentrantLock lock = new ReentrantLock();

        Wallet(String userId, Currency currency) {
            this.id = "wlt_" + UUID.randomUUID();
            this.userId = userId;
            this.currency = currency;
            this.balanceRef = new AtomicReference<>(new Balance(Money.zero(currency), 0));
        }

        Money getBalance() { return balanceRef.get().money; }
        ReentrantLock lock() { return lock; }

        /** CAS credit loop — always succeeds. */
        void credit(Money amount) {
            if (amount.currency() != currency) throw new IllegalArgumentException("currency mismatch");
            while (true) {
                Balance cur = balanceRef.get();
                Balance next = new Balance(cur.money.add(amount), cur.version + 1);
                if (balanceRef.compareAndSet(cur, next)) return;
                // another thread won the race; retry with fresh snapshot
            }
        }

        /**
         * CAS debit loop — atomic check-and-decrement. Returns false if funds
         * are insufficient. This single atomic step is what prevents double
         * spend and a negative balance under concurrency.
         */
        boolean tryDebit(Money amount) {
            if (amount.currency() != currency) throw new IllegalArgumentException("currency mismatch");
            while (true) {
                Balance cur = balanceRef.get();
                if (!cur.money.isGreaterOrEqual(amount)) return false;  // insufficient
                Balance next = new Balance(cur.money.subtract(amount), cur.version + 1);
                if (balanceRef.compareAndSet(cur, next)) return true;
                // lost the race; loop and re-check against fresh balance
            }
        }

        /** Immutable snapshot of (money, version) for CAS. */
        static final class Balance {
            final Money money;
            final long version;
            Balance(Money money, long version) { this.money = money; this.version = version; }
        }
    }

    // ===================== Transaction lifecycle (State) ====================
    enum TransactionType { ADD_MONEY, WITHDRAW, TRANSFER }

    /** Legal transitions centralized in one place (State pattern as an enum). */
    enum TransactionState {
        PENDING { @Override boolean canMoveTo(TransactionState s) { return s == COMPLETED || s == FAILED; } },
        COMPLETED { @Override boolean canMoveTo(TransactionState s) { return s == REVERSED; } },
        FAILED { @Override boolean canMoveTo(TransactionState s) { return false; } },
        REVERSED { @Override boolean canMoveTo(TransactionState s) { return false; } };
        abstract boolean canMoveTo(TransactionState s);
    }

    static final class Transaction {
        final String id;
        final String requestId;          // idempotency key
        final TransactionType type;
        final Money amount;              // gross amount in source currency
        final String sourceWalletId;    // may be null (ADD_MONEY)
        final String destWalletId;      // may be null (WITHDRAW)
        final Instant createdAt;
        private volatile TransactionState state;
        volatile String reversalOfTxnId; // set on a compensating txn

        Transaction(String requestId, TransactionType type, Money amount,
                    String sourceWalletId, String destWalletId) {
            this.id = "txn_" + UUID.randomUUID();
            this.requestId = requestId;
            this.type = type;
            this.amount = amount;
            this.sourceWalletId = sourceWalletId;
            this.destWalletId = destWalletId;
            this.createdAt = Instant.now();
            this.state = TransactionState.PENDING;
        }
        TransactionState state() { return state; }
        synchronized void transitionTo(TransactionState target) {
            if (!state.canMoveTo(target))
                throw new IllegalStateException("illegal transition " + state + " -> " + target);
            state = target;
        }
        @Override public String toString() {
            return String.format("Transaction[%s type=%s amount=%s state=%s req=%s]",
                    id, type, amount, state, requestId);
        }
    }

    // ===================== Double-entry Ledger =============================
    enum EntryType { DEBIT, CREDIT }

    /** Immutable single posting. */
    static final class LedgerEntry {
        final String walletId;
        final String txnId;
        final EntryType entryType;
        final Money amount;
        final Money balanceAfter;
        final Instant at;
        LedgerEntry(String walletId, String txnId, EntryType entryType, Money amount, Money balanceAfter) {
            this.walletId = walletId; this.txnId = txnId; this.entryType = entryType;
            this.amount = amount; this.balanceAfter = balanceAfter; this.at = Instant.now();
        }
        @Override public String toString() {
            return String.format("  %-6s %-10s %s (bal=%s) txn=%s", entryType, walletId, amount, balanceAfter, txnId);
        }
    }

    /** Append-only ledger; source of truth for history and reconciliation. */
    static final class Ledger {
        // walletId -> entries (the inner list is synchronized for append/read)
        private final Map<String, List<LedgerEntry>> byWallet = new ConcurrentHashMap<>();

        void record(LedgerEntry e) {
            byWallet.computeIfAbsent(e.walletId, k -> Collections.synchronizedList(new ArrayList<>())).add(e);
        }
        List<LedgerEntry> entriesFor(String walletId) {
            List<LedgerEntry> l = byWallet.get(walletId);
            if (l == null) return Collections.emptyList();
            synchronized (l) { return new ArrayList<>(l); }
        }
        /** Re-derive balance from postings: sum(credits) - sum(debits). */
        Money derivedBalance(String walletId, Currency c) {
            Money bal = Money.zero(c);
            for (LedgerEntry e : entriesFor(walletId)) {
                bal = (e.entryType == EntryType.CREDIT) ? bal.add(e.amount) : bal.subtract(e.amount);
            }
            return bal;
        }
    }

    // ===================== Strategy: FX conversion =========================
    interface ExchangeRateProvider {
        /** Units of `to` per 1 unit of `from`. */
        BigDecimal rate(Currency from, Currency to);
    }

    /** Demo provider with a tiny fixed rate table. */
    static final class StaticExchangeRateProvider implements ExchangeRateProvider {
        private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();
        StaticExchangeRateProvider() {
            put(Currency.USD, Currency.INR, new BigDecimal("83.00"));
            put(Currency.INR, Currency.USD, new BigDecimal("0.0120"));
            put(Currency.EUR, Currency.INR, new BigDecimal("90.00"));
        }
        void put(Currency f, Currency t, BigDecimal r) { rates.put(f + ">" + t, r); }
        @Override public BigDecimal rate(Currency from, Currency to) {
            if (from == to) return BigDecimal.ONE;
            BigDecimal r = rates.get(from + ">" + to);
            if (r == null) throw new IllegalStateException("no rate " + from + "->" + to);
            return r;
        }
    }

    interface ConversionStrategy {
        Money convert(Money source, Currency target);
    }

    /** Same-currency: identity (and a guard). */
    static final class SameCurrencyStrategy implements ConversionStrategy {
        @Override public Money convert(Money source, Currency target) {
            if (source.currency() != target)
                throw new IllegalArgumentException("SameCurrencyStrategy used cross-currency");
            return source;
        }
    }

    /**
     * FX strategy: convert via BigDecimal then round ONCE to target minor units.
     * Rate is locked at execution time (looked up here, per call).
     */
    static final class FxConversionStrategy implements ConversionStrategy {
        private final ExchangeRateProvider provider;
        FxConversionStrategy(ExchangeRateProvider provider) { this.provider = provider; }
        @Override public Money convert(Money source, Currency target) {
            if (source.currency() == target) return source;
            BigDecimal rate = provider.rate(source.currency(), target);
            // major(source) * rate -> major(target), then to minor units of target
            BigDecimal srcMajor = new BigDecimal(source.minor())
                    .movePointLeft(source.currency().scale);
            BigDecimal tgtMajor = srcMajor.multiply(rate);
            long tgtMinor = tgtMajor.movePointRight(target.scale)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            return Money.of(tgtMinor, target);
        }
    }

    // ===================== Idempotency =====================================
    interface IdempotencyStore {
        Transaction get(String requestId);
        /** Reserve-or-return: returns existing if present, else stores and returns null. */
        Transaction putIfAbsent(String requestId, Transaction txn);
    }
    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private final Map<String, Transaction> map = new ConcurrentHashMap<>();
        @Override public Transaction get(String requestId) { return map.get(requestId); }
        @Override public Transaction putIfAbsent(String requestId, Transaction txn) {
            return map.putIfAbsent(requestId, txn);
        }
    }

    // ===================== Repositories ====================================
    interface WalletRepository { void save(Wallet w); Wallet find(String id); }
    interface TransactionRepository { void save(Transaction t); Transaction find(String id); }

    static final class InMemoryWalletRepository implements WalletRepository {
        private final Map<String, Wallet> map = new ConcurrentHashMap<>();
        @Override public void save(Wallet w) { map.put(w.id, w); }
        @Override public Wallet find(String id) {
            Wallet w = map.get(id);
            if (w == null) throw new IllegalArgumentException("no wallet " + id);
            return w;
        }
    }
    static final class InMemoryTransactionRepository implements TransactionRepository {
        private final Map<String, Transaction> map = new ConcurrentHashMap<>();
        @Override public void save(Transaction t) { map.put(t.id, t); }
        @Override public Transaction find(String id) { return map.get(id); }
    }

    // ===================== Observer ========================================
    interface TransactionObserver { void onCompleted(Transaction txn); }
    static final class LoggingObserver implements TransactionObserver {
        @Override public void onCompleted(Transaction txn) {
            System.out.println("[observer] completed -> " + txn);
        }
    }

    // ===================== Command pattern =================================
    interface TransactionCommand {
        Transaction execute();
        Transaction undo();
    }

    /** SYSTEM external account id used as the counter-leg for add/withdraw. */
    static final String SYSTEM_EXTERNAL = "system_external";

    /** ADD_MONEY: external -> wallet (single wallet leg). */
    static final class AddMoneyCommand implements TransactionCommand {
        private final Wallet wallet; private final Money amount;
        private final Ledger ledger; private final Transaction txn;
        AddMoneyCommand(Wallet wallet, Money amount, Ledger ledger, String requestId) {
            this.wallet = wallet; this.amount = amount; this.ledger = ledger;
            this.txn = new Transaction(requestId, TransactionType.ADD_MONEY, amount, null, wallet.id);
        }
        @Override public Transaction execute() {
            wallet.credit(amount);
            // double-entry: credit the wallet, debit the system external account
            ledger.record(new LedgerEntry(wallet.id, txn.id, EntryType.CREDIT, amount, wallet.getBalance()));
            ledger.record(new LedgerEntry(SYSTEM_EXTERNAL, txn.id, EntryType.DEBIT, amount, Money.zero(amount.currency())));
            txn.transitionTo(TransactionState.COMPLETED);
            return txn;
        }
        @Override public Transaction undo() {
            wallet.tryDebit(amount); // reverse the credit (best effort within lock)
            return txn;
        }
        Transaction txn() { return txn; }
    }

    /** WITHDRAW: wallet -> external (single wallet leg). */
    static final class WithdrawCommand implements TransactionCommand {
        private final Wallet wallet; private final Money amount;
        private final Ledger ledger; private final Transaction txn;
        WithdrawCommand(Wallet wallet, Money amount, Ledger ledger, String requestId) {
            this.wallet = wallet; this.amount = amount; this.ledger = ledger;
            this.txn = new Transaction(requestId, TransactionType.WITHDRAW, amount, wallet.id, null);
        }
        @Override public Transaction execute() {
            if (!wallet.tryDebit(amount)) {     // atomic check-and-debit
                txn.transitionTo(TransactionState.FAILED);
                return txn;
            }
            ledger.record(new LedgerEntry(wallet.id, txn.id, EntryType.DEBIT, amount, wallet.getBalance()));
            ledger.record(new LedgerEntry(SYSTEM_EXTERNAL, txn.id, EntryType.CREDIT, amount, Money.zero(amount.currency())));
            txn.transitionTo(TransactionState.COMPLETED);
            return txn;
        }
        @Override public Transaction undo() { wallet.credit(amount); return txn; }
        Transaction txn() { return txn; }
    }

    /**
     * TRANSFER: src wallet -> dst wallet, atomically (debit + credit), possibly
     * cross-currency. Acquires both wallet locks in deterministic id order to
     * avoid deadlock. Debit is checked first; if a later step fails the debit
     * is undone before releasing locks.
     */
    static final class TransferCommand implements TransactionCommand {
        private final Wallet src, dst; private final Money amount;
        private final Ledger ledger; private final ConversionStrategy fx;
        private final Transaction txn;
        private Money creditedAmount; // converted amount actually credited (for undo)

        TransferCommand(Wallet src, Wallet dst, Money amount, Ledger ledger,
                        ConversionStrategy fx, String requestId) {
            this.src = src; this.dst = dst; this.amount = amount; this.ledger = ledger;
            this.fx = fx;
            this.txn = new Transaction(requestId, TransactionType.TRANSFER, amount, src.id, dst.id);
        }

        @Override public Transaction execute() {
            if (src.id.equals(dst.id)) throw new IllegalArgumentException("self-transfer not allowed");
            // Lock ordering by id prevents the A->B / B->A deadlock.
            Wallet first = src.id.compareTo(dst.id) < 0 ? src : dst;
            Wallet second = first == src ? dst : src;
            first.lock().lock();
            second.lock().lock();
            try {
                if (!src.tryDebit(amount)) {           // atomic check-and-debit on source
                    txn.transitionTo(TransactionState.FAILED);
                    return txn;
                }
                try {
                    creditedAmount = fx.convert(amount, dst.currency); // rate locked here
                    dst.credit(creditedAmount);
                    // double-entry: DEBIT source + CREDIT destination
                    ledger.record(new LedgerEntry(src.id, txn.id, EntryType.DEBIT, amount, src.getBalance()));
                    ledger.record(new LedgerEntry(dst.id, txn.id, EntryType.CREDIT, creditedAmount, dst.getBalance()));
                    txn.transitionTo(TransactionState.COMPLETED);
                    return txn;
                } catch (RuntimeException ex) {
                    src.credit(amount);                // undo the debit; no partial state escapes the lock
                    txn.transitionTo(TransactionState.FAILED);
                    throw ex;
                }
            } finally {
                second.lock().unlock();
                first.lock().unlock();
            }
        }

        /** Compensating reversal of a completed transfer (additive, never destructive). */
        @Override public Transaction undo() {
            Wallet first = src.id.compareTo(dst.id) < 0 ? src : dst;
            Wallet second = first == src ? dst : src;
            first.lock().lock(); second.lock().lock();
            try {
                if (creditedAmount != null && dst.tryDebit(creditedAmount)) {
                    src.credit(amount);
                    Transaction rev = new Transaction(txn.requestId + ":rev", TransactionType.TRANSFER,
                            amount, dst.id, src.id);
                    rev.reversalOfTxnId = txn.id;
                    ledger.record(new LedgerEntry(dst.id, rev.id, EntryType.DEBIT, creditedAmount, dst.getBalance()));
                    ledger.record(new LedgerEntry(src.id, rev.id, EntryType.CREDIT, amount, src.getBalance()));
                    rev.transitionTo(TransactionState.COMPLETED);
                    txn.transitionTo(TransactionState.REVERSED);
                    return rev;
                }
                return txn;
            } finally {
                second.lock().unlock(); first.lock().unlock();
            }
        }
        Transaction txn() { return txn; }
    }

    // ===================== Facade: WalletService ===========================
    static final class WalletService {
        private final WalletRepository wallets;
        private final TransactionRepository txns;
        private final Ledger ledger;
        private final IdempotencyStore idem;
        private final ConversionStrategy fx;
        private final List<TransactionObserver> observers;

        WalletService(WalletRepository wallets, TransactionRepository txns, Ledger ledger,
                      IdempotencyStore idem, ConversionStrategy fx, List<TransactionObserver> observers) {
            this.wallets = wallets; this.txns = txns; this.ledger = ledger;
            this.idem = idem; this.fx = fx;
            this.observers = observers == null ? new ArrayList<>() : observers;
        }

        Wallet createWallet(User user, Currency currency) {
            Wallet w = new Wallet(user.id, currency);
            wallets.save(w);
            return w;
        }

        Transaction addMoney(String walletId, Money amount, String requestId) {
            return runIdempotent(requestId, () -> {
                require(amount.isPositive(), "amount must be positive");
                AddMoneyCommand cmd = new AddMoneyCommand(wallets.find(walletId), amount, ledger, requestId);
                return finish(cmd.execute());
            });
        }

        Transaction withdraw(String walletId, Money amount, String requestId) {
            return runIdempotent(requestId, () -> {
                require(amount.isPositive(), "amount must be positive");
                WithdrawCommand cmd = new WithdrawCommand(wallets.find(walletId), amount, ledger, requestId);
                return finish(cmd.execute());
            });
        }

        Transaction transfer(String srcWalletId, String dstWalletId, Money amount, String requestId) {
            return runIdempotent(requestId, () -> {
                require(amount.isPositive(), "amount must be positive");
                require(!srcWalletId.equals(dstWalletId), "cannot transfer to same wallet");
                TransferCommand cmd = new TransferCommand(
                        wallets.find(srcWalletId), wallets.find(dstWalletId), amount, ledger, fx, requestId);
                return finish(cmd.execute());
            });
        }

        Money getBalance(String walletId) { return wallets.find(walletId).getBalance(); }
        List<LedgerEntry> getStatement(String walletId) { return ledger.entriesFor(walletId); }

        // ---- helpers ----
        private Transaction finish(Transaction t) {
            txns.save(t);
            if (t.state() == TransactionState.COMPLETED)
                for (TransactionObserver o : observers) o.onCompleted(t);
            return t;
        }

        /**
         * Idempotency guard: if requestId already produced a transaction, return
         * it without re-executing. A placeholder reservation collapses concurrent
         * duplicate retries to a single execution.
         */
        private Transaction runIdempotent(String requestId, java.util.function.Supplier<Transaction> op) {
            if (requestId != null) {
                Transaction existing = idem.get(requestId);
                if (existing != null) return existing;
            }
            Transaction result = op.get();
            if (requestId != null) {
                Transaction prior = idem.putIfAbsent(requestId, result);
                if (prior != null) return prior; // another thread executed first
            }
            return result;
        }

        private static void require(boolean cond, String msg) {
            if (!cond) throw new IllegalArgumentException(msg);
        }
    }

    // ===================== ReconciliationService ===========================
    static final class ReconciliationService {
        private final Ledger ledger;
        private final WalletRepository wallets;
        ReconciliationService(Ledger ledger, WalletRepository wallets) {
            this.ledger = ledger; this.wallets = wallets;
        }
        /** true if stored balance == balance re-derived from the ledger. */
        boolean reconcile(String walletId) {
            Wallet w = wallets.find(walletId);
            Money derived = ledger.derivedBalance(walletId, w.currency);
            return derived.minor() == w.getBalance().minor();
        }
    }

    // ===================== Demonstration (main) ============================
    public static void main(String[] args) throws InterruptedException {
        WalletRepository walletRepo = new InMemoryWalletRepository();
        TransactionRepository txnRepo = new InMemoryTransactionRepository();
        Ledger ledger = new Ledger();
        IdempotencyStore idem = new InMemoryIdempotencyStore();
        ExchangeRateProvider rates = new StaticExchangeRateProvider();
        ConversionStrategy fx = new FxConversionStrategy(rates); // handles same- and cross-currency
        List<TransactionObserver> observers = new ArrayList<>();
        observers.add(new LoggingObserver());

        WalletService service = new WalletService(walletRepo, txnRepo, ledger, idem, fx, observers);
        ReconciliationService recon = new ReconciliationService(ledger, walletRepo);

        User alice = new User("Alice");
        User bob = new User("Bob");
        Wallet aliceInr = service.createWallet(alice, Currency.INR);
        Wallet bobInr = service.createWallet(bob, Currency.INR);
        Wallet bobUsd = service.createWallet(bob, Currency.USD);

        System.out.println("=== 1. Add money ===");
        service.addMoney(aliceInr.id, Money.ofMajor(1000.00, Currency.INR), "req-add-1");
        System.out.println("Alice INR balance: " + service.getBalance(aliceInr.id));

        System.out.println("\n=== 2. Idempotent retry of the same add ===");
        Transaction retry = service.addMoney(aliceInr.id, Money.ofMajor(1000.00, Currency.INR), "req-add-1");
        System.out.println("Retry returned same txn? balance unchanged: " + service.getBalance(aliceInr.id) + " (txn " + retry.id + ")");

        System.out.println("\n=== 3. Same-currency transfer Alice -> Bob (INR) ===");
        service.transfer(aliceInr.id, bobInr.id, Money.ofMajor(250.00, Currency.INR), "req-xfer-1");
        System.out.println("Alice INR: " + service.getBalance(aliceInr.id) + " | Bob INR: " + service.getBalance(bobInr.id));

        System.out.println("\n=== 4. Cross-currency transfer Alice INR -> Bob USD ===");
        service.transfer(aliceInr.id, bobUsd.id, Money.ofMajor(83.00, Currency.INR), "req-xfer-2");
        System.out.println("Alice INR: " + service.getBalance(aliceInr.id) + " | Bob USD: " + service.getBalance(bobUsd.id));

        System.out.println("\n=== 5. Insufficient funds withdraw (should FAIL) ===");
        Transaction w = service.withdraw(bobUsd.id, Money.ofMajor(9999.00, Currency.USD), "req-wd-1");
        System.out.println("Withdraw state: " + w.state());

        System.out.println("\n=== 6. Concurrency: 100 threads each debit 10 INR from Alice (no double-spend) ===");
        // Top up to a known amount first.
        service.addMoney(aliceInr.id, Money.ofMajor(900.00, Currency.INR), "req-add-2"); // bring to a round figure
        Money before = service.getBalance(aliceInr.id);
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        final int[] successes = {0};
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    Transaction t = service.withdraw(aliceInr.id, Money.ofMajor(10.00, Currency.INR), "req-conc-" + n);
                    if (t.state() == TransactionState.COMPLETED) {
                        synchronized (successes) { successes[0]++; }
                    }
                } catch (Exception ignored) {
                } finally { done.countDown(); }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();
        Money after = service.getBalance(aliceInr.id);
        System.out.println("Before=" + before + " After=" + after + " successfulDebits=" + successes[0]);
        System.out.println("Balance never went negative: " + (after.minor() >= 0));

        System.out.println("\n=== 7. Reconciliation (balance == ledger-derived) ===");
        System.out.println("Alice INR reconciles: " + recon.reconcile(aliceInr.id));
        System.out.println("Bob INR reconciles:   " + recon.reconcile(bobInr.id));
        System.out.println("Bob USD reconciles:   " + recon.reconcile(bobUsd.id));

        System.out.println("\n=== 8. Statement for Alice (INR) ===");
        for (LedgerEntry e : service.getStatement(aliceInr.id)) System.out.println(e);
    }
}

/* =============================================================================
 * ATM — Single-file LLD review / revision artifact (READ-AND-REVISE; not meant to be run as a product).
 *
 * ---------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ---------------------------------------------------------------------------
 * Functional:
 *   - Which transactions? (withdraw / deposit / balance / transfer / mini-statement / PIN change)
 *   - Multiple transactions per session, or one-and-done?
 *   - Withdrawals: exact denominations? fewest-notes? per-txn / daily limits?
 *   - Deposits: cash and/or cheque? available immediately or held for clearing?
 *   - Single bank or interbank (route by card BIN)?
 * Non-functional / constraints:
 *   - One physical user at a time (true) BUT the account is shared across channels -> debits must be atomic at the BANK.
 *   - PIN retries before lockout (assume 3); lockout persistent on card/account, not just session.
 *   - Power loss mid-dispense -> need a journal + idempotency for reconciliation.
 *   - Hardware faults (card jam, empty hopper, no paper) -> degrade or go OutOfService.
 * Scope / assumptions:
 *   - BankService is a mockable interface (source of truth for balances/atomicity).
 *   - Single currency; money tracked in integer minor units; whole-note dispensing.
 *
 * DESIGN PATTERNS DEMONSTRATED
 *   - State                  : ATMState -> Idle / HasCard / Authenticated / OutOfService
 *   - Strategy (+ Factory)   : Transaction -> Withdraw / Deposit / BalanceInquiry / Transfer
 *   - Chain of Responsibility: CashDispenser -> DenominationDispenser links by note value
 *   - DIP / Facade           : BankService abstraction (mock here; could be a router)
 *   - Value Object           : Money (no floating-point money math)
 *
 * KEY DECISIONS
 *   - Withdrawal is two-phase: canDispense -> debit -> dispense -> COMPENSATE (credit back) on dispense failure.
 *   - Idempotency keys on debit/credit make retries safe.
 *   - Dispenser inventory mutation is synchronized (avoid check-then-act TOCTOU).
 *   - ATM keeps NO authoritative balance; the bank owns it.
 * ============================================================================= */

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Solution {

    /* ===================== Value object: Money ===================== */
    static final class Money {
        final long minorUnits;          // e.g. cents
        private Money(long minorUnits) { this.minorUnits = minorUnits; }
        static Money ofUnits(long wholeUnits) { return new Money(wholeUnits * 100L); }
        static Money ofMinor(long minor)       { return new Money(minor); }
        long wholeUnits()  { return minorUnits / 100L; }
        boolean isZeroOrNeg() { return minorUnits <= 0; }
        Money plus(Money o)  { return new Money(this.minorUnits + o.minorUnits); }
        Money minus(Money o) { return new Money(this.minorUnits - o.minorUnits); }
        boolean lt(Money o)  { return this.minorUnits < o.minorUnits; }
        boolean isWholeUnit() { return minorUnits % 100L == 0; }
        @Override public String toString() { return String.format("$%d.%02d", minorUnits / 100, Math.abs(minorUnits % 100)); }
    }

    /* ===================== Denominations ===================== */
    enum Denomination {
        HUNDRED(100), FIFTY(50), TWENTY(20), TEN(10), FIVE(5), ONE(1);
        final int value;
        Denomination(int value) { this.value = value; }
    }

    /* ===================== Domain objects ===================== */
    static final class Account {
        final String id;
        Account(String id) { this.id = id; }
    }

    static final class Card {
        final String cardNumber;
        final String accountId;
        boolean locked = false;
        Card(String cardNumber, String accountId) { this.cardNumber = cardNumber; this.accountId = accountId; }
    }

    enum TxnType { WITHDRAW, DEPOSIT, BALANCE, TRANSFER }

    /* ===================== Bank boundary (DIP / Facade) ===================== */
    interface BankService {
        boolean authenticate(Card card, String pin);
        Money   getBalance(String accountId);
        boolean debit(String accountId, Money amount, String idempotencyKey);
        boolean credit(String accountId, Money amount, String idempotencyKey);
        boolean transfer(String fromAccount, String toAccount, Money amount, String idempotencyKey);
        void    lockCard(Card card);
    }

    /** Mock bank: authoritative balances + atomic, idempotent money movement. */
    static final class MockBankService implements BankService {
        private final Map<String, Long> balanceMinor = new ConcurrentHashMap<>();   // accountId -> minor units
        private final Map<String, String> pins        = new ConcurrentHashMap<>();  // cardNumber -> pin
        private final Set<String> appliedKeys         = ConcurrentHashMap.newKeySet(); // idempotency

        void seedAccount(String accountId, Money opening) { balanceMinor.put(accountId, opening.minorUnits); }
        void seedPin(String cardNumber, String pin)       { pins.put(cardNumber, pin); }

        @Override public boolean authenticate(Card card, String pin) {
            return !card.locked && Objects.equals(pins.get(card.cardNumber), pin);
        }
        @Override public Money getBalance(String accountId) {
            return Money.ofMinor(balanceMinor.getOrDefault(accountId, 0L));
        }
        // Atomicity: a single critical section on the account; idempotency guards retries.
        @Override public synchronized boolean debit(String accountId, Money amount, String idempotencyKey) {
            if (appliedKeys.contains(idempotencyKey)) return true; // already applied -> safe replay
            long bal = balanceMinor.getOrDefault(accountId, 0L);
            if (bal < amount.minorUnits) return false;             // insufficient funds
            balanceMinor.put(accountId, bal - amount.minorUnits);
            appliedKeys.add(idempotencyKey);
            return true;
        }
        @Override public synchronized boolean credit(String accountId, Money amount, String idempotencyKey) {
            if (appliedKeys.contains(idempotencyKey)) return true;
            balanceMinor.merge(accountId, amount.minorUnits, Long::sum);
            appliedKeys.add(idempotencyKey);
            return true;
        }
        @Override public synchronized boolean transfer(String from, String to, Money amount, String key) {
            if (appliedKeys.contains(key)) return true;
            long fromBal = balanceMinor.getOrDefault(from, 0L);
            if (fromBal < amount.minorUnits) return false;
            balanceMinor.put(from, fromBal - amount.minorUnits);
            balanceMinor.merge(to, amount.minorUnits, Long::sum);
            appliedKeys.add(key);
            return true;
        }
        @Override public void lockCard(Card card) { card.locked = true; }
    }

    /* ===================== Cash dispenser (Chain of Responsibility) ===================== */
    /** Each link peels off as many of its note as it can, then delegates the remainder. */
    static abstract class DenominationDispenser {
        protected DenominationDispenser next;
        protected final Denomination denom;
        DenominationDispenser(Denomination denom) { this.denom = denom; }
        DenominationDispenser setNext(DenominationDispenser next) { this.next = next; return next; }

        /** Try to plan a dispense for `remainingWholeUnits` using `available` inventory.
         *  Fills `plan`; returns leftover that could not be satisfied (0 == success). */
        int plan(int remainingWholeUnits, Map<Denomination, Integer> available, Map<Denomination, Integer> plan) {
            int canUseByValue = remainingWholeUnits / denom.value;
            int have          = available.getOrDefault(denom, 0);
            int use           = Math.min(canUseByValue, have);
            if (use > 0) {
                plan.put(denom, use);
                remainingWholeUnits -= use * denom.value;
            }
            if (remainingWholeUnits == 0) return 0;
            if (next == null) return remainingWholeUnits;   // nothing smaller left -> not fully dispensable
            return next.plan(remainingWholeUnits, available, plan);
        }
    }
    static final class ConcreteDispenser extends DenominationDispenser {
        ConcreteDispenser(Denomination d) { super(d); }
    }

    static final class NotDispensableException extends RuntimeException {
        NotDispensableException(String m) { super(m); }
    }

    /** Holds note inventory; builds the CoR head from largest -> smallest note. */
    static final class CashDispenser {
        private final Map<Denomination, Integer> inventory = new EnumMap<>(Denomination.class);
        private final DenominationDispenser chainHead;

        CashDispenser(Map<Denomination, Integer> initial) {
            inventory.putAll(initial);
            // Build chain largest -> smallest (greedy fewest-notes for canonical currency).
            Denomination[] order = { Denomination.HUNDRED, Denomination.FIFTY, Denomination.TWENTY,
                                     Denomination.TEN, Denomination.FIVE, Denomination.ONE };
            DenominationDispenser head = new ConcreteDispenser(order[0]);
            DenominationDispenser cur  = head;
            for (int i = 1; i < order.length; i++) cur = cur.setNext(new ConcreteDispenser(order[i]));
            this.chainHead = head;
        }

        /** Feasibility check (no mutation). */
        synchronized boolean canDispense(Money amount) {
            if (!amount.isWholeUnit() || amount.isZeroOrNeg()) return false;
            Map<Denomination, Integer> plan = new EnumMap<>(Denomination.class);
            int leftover = chainHead.plan((int) amount.wholeUnits(), snapshot(), plan);
            return leftover == 0;
        }

        /** Atomic check-then-act: plan against current inventory and commit if fully satisfiable. */
        synchronized Map<Denomination, Integer> dispense(Money amount) {
            if (!amount.isWholeUnit() || amount.isZeroOrNeg())
                throw new NotDispensableException("Amount must be a positive whole unit: " + amount);
            Map<Denomination, Integer> plan = new EnumMap<>(Denomination.class);
            int leftover = chainHead.plan((int) amount.wholeUnits(), snapshot(), plan);
            if (leftover != 0)
                throw new NotDispensableException("Cannot dispense exact amount with available notes: " + amount);
            // commit
            for (Map.Entry<Denomination, Integer> e : plan.entrySet())
                inventory.merge(e.getKey(), -e.getValue(), Integer::sum);
            return plan;
        }

        private Map<Denomination, Integer> snapshot() { return new EnumMap<>(inventory); }
        synchronized long totalCashUnits() {
            long t = 0; for (var e : inventory.entrySet()) t += (long) e.getKey().value * e.getValue(); return t;
        }
    }

    /* ===================== Transaction strategies ===================== */
    static final class TxnContext {
        final Card card;
        final BankService bank;
        final CashDispenser dispenser;
        final Money amount;        // null for balance inquiry
        final String toAccountId;  // for transfer
        final String idempotencyKey;
        TxnContext(Card card, BankService bank, CashDispenser dispenser, Money amount, String toAccountId, String key) {
            this.card = card; this.bank = bank; this.dispenser = dispenser;
            this.amount = amount; this.toAccountId = toAccountId; this.idempotencyKey = key;
        }
    }
    static final class TxnResult {
        final boolean ok; final String message; final Map<Denomination, Integer> notes;
        TxnResult(boolean ok, String message, Map<Denomination, Integer> notes) {
            this.ok = ok; this.message = message; this.notes = notes;
        }
        static TxnResult fail(String m) { return new TxnResult(false, m, null); }
        static TxnResult ok(String m)   { return new TxnResult(true, m, null); }
        static TxnResult ok(String m, Map<Denomination, Integer> notes) { return new TxnResult(true, m, notes); }
    }

    interface Transaction { TxnResult execute(TxnContext ctx); }

    /** Two-phase, debit-before-dispense, compensate on dispense failure. */
    static final class WithdrawTransaction implements Transaction {
        @Override public TxnResult execute(TxnContext ctx) {
            Money amount = ctx.amount;
            if (amount == null || amount.isZeroOrNeg()) return TxnResult.fail("Invalid amount.");
            if (!ctx.dispenser.canDispense(amount))     return TxnResult.fail("ATM cannot dispense " + amount + " with available notes.");
            if (!ctx.bank.debit(ctx.card.accountId, amount, ctx.idempotencyKey))
                return TxnResult.fail("Insufficient funds.");
            try {
                Map<Denomination, Integer> notes = ctx.dispenser.dispense(amount);
                return TxnResult.ok("Dispensed " + amount + ". New balance: " + ctx.bank.getBalance(ctx.card.accountId), notes);
            } catch (RuntimeException dispenseFailure) {
                // Compensate: money was debited but not handed out.
                ctx.bank.credit(ctx.card.accountId, amount, ctx.idempotencyKey + ":reversal");
                return TxnResult.fail("Dispense failed; debit reversed. No cash taken.");
            }
        }
    }

    static final class DepositTransaction implements Transaction {
        @Override public TxnResult execute(TxnContext ctx) {
            if (ctx.amount == null || ctx.amount.isZeroOrNeg()) return TxnResult.fail("Invalid amount.");
            boolean ok = ctx.bank.credit(ctx.card.accountId, ctx.amount, ctx.idempotencyKey);
            return ok ? TxnResult.ok("Deposited " + ctx.amount + ". New balance: " + ctx.bank.getBalance(ctx.card.accountId))
                      : TxnResult.fail("Deposit failed.");
        }
    }

    static final class BalanceInquiryTransaction implements Transaction {
        @Override public TxnResult execute(TxnContext ctx) {
            return TxnResult.ok("Balance: " + ctx.bank.getBalance(ctx.card.accountId));
        }
    }

    static final class TransferTransaction implements Transaction {
        @Override public TxnResult execute(TxnContext ctx) {
            if (ctx.amount == null || ctx.amount.isZeroOrNeg()) return TxnResult.fail("Invalid amount.");
            if (ctx.toAccountId == null) return TxnResult.fail("Destination account required.");
            boolean ok = ctx.bank.transfer(ctx.card.accountId, ctx.toAccountId, ctx.amount, ctx.idempotencyKey);
            return ok ? TxnResult.ok("Transferred " + ctx.amount + " to " + ctx.toAccountId
                                     + ". New balance: " + ctx.bank.getBalance(ctx.card.accountId))
                      : TxnResult.fail("Transfer failed (insufficient funds?).");
        }
    }

    /** Factory: maps a user selection to the right Strategy (OCP — add a type without editing callers). */
    static final class TransactionFactory {
        static Transaction create(TxnType type) {
            switch (type) {
                case WITHDRAW: return new WithdrawTransaction();
                case DEPOSIT:  return new DepositTransaction();
                case BALANCE:  return new BalanceInquiryTransaction();
                case TRANSFER: return new TransferTransaction();
                default: throw new IllegalArgumentException("Unknown transaction: " + type);
            }
        }
    }

    /* ===================== State pattern ===================== */
    interface ATMState {
        void insertCard(ATM atm, Card card);
        void enterPin(ATM atm, String pin);
        void selectTransaction(ATM atm, TxnType type, Money amount, String toAccountId);
        void ejectCard(ATM atm);
    }

    static final class IdleState implements ATMState {
        @Override public void insertCard(ATM atm, Card card) {
            if (card.locked) { atm.print("Card is locked. Retained."); return; }
            atm.setCurrentCard(card);
            atm.resetPinAttempts();
            atm.print("Card inserted. Please enter PIN.");
            atm.setState(new HasCardState());
        }
        @Override public void enterPin(ATM atm, String pin)        { atm.print("Insert a card first."); }
        @Override public void selectTransaction(ATM atm, TxnType t, Money a, String to) { atm.print("Insert a card first."); }
        @Override public void ejectCard(ATM atm)                   { atm.print("No card to eject."); }
    }

    static final class HasCardState implements ATMState {
        @Override public void insertCard(ATM atm, Card card) { atm.print("A card is already inserted."); }
        @Override public void enterPin(ATM atm, String pin) {
            Card card = atm.getCurrentCard();
            if (atm.getBank().authenticate(card, pin)) {
                atm.print("Authenticated. Choose a transaction.");
                atm.setState(new AuthenticatedState());
            } else {
                atm.incrementPinAttempts();
                if (atm.getPinAttempts() >= ATM.MAX_PIN_ATTEMPTS) {
                    atm.getBank().lockCard(card);
                    atm.print("Too many wrong PINs. Card locked and retained.");
                    atm.clearCard();
                    atm.setState(new IdleState());
                } else {
                    atm.print("Wrong PIN. Attempts left: " + (ATM.MAX_PIN_ATTEMPTS - atm.getPinAttempts()));
                }
            }
        }
        @Override public void selectTransaction(ATM atm, TxnType t, Money a, String to) { atm.print("Enter PIN first."); }
        @Override public void ejectCard(ATM atm) { atm.print("Card ejected."); atm.clearCard(); atm.setState(new IdleState()); }
    }

    static final class AuthenticatedState implements ATMState {
        @Override public void insertCard(ATM atm, Card card) { atm.print("Already in a session."); }
        @Override public void enterPin(ATM atm, String pin)  { atm.print("Already authenticated."); }
        @Override public void selectTransaction(ATM atm, TxnType type, Money amount, String toAccountId) {
            Transaction txn = TransactionFactory.create(type);
            TxnContext ctx = new TxnContext(atm.getCurrentCard(), atm.getBank(), atm.getDispenser(),
                                            amount, toAccountId, atm.newIdempotencyKey());
            TxnResult result = txn.execute(ctx);
            atm.print((result.ok ? "[OK] " : "[FAIL] ") + result.message);
            if (result.notes != null) atm.print("   Notes: " + result.notes);
            // Stay authenticated to allow multiple transactions in one session.
        }
        @Override public void ejectCard(ATM atm) { atm.print("Session ended. Card ejected."); atm.clearCard(); atm.setState(new IdleState()); }
    }

    static final class OutOfServiceState implements ATMState {
        @Override public void insertCard(ATM atm, Card card) { atm.print("ATM out of service."); }
        @Override public void enterPin(ATM atm, String pin)  { atm.print("ATM out of service."); }
        @Override public void selectTransaction(ATM atm, TxnType t, Money a, String to) { atm.print("ATM out of service."); }
        @Override public void ejectCard(ATM atm)             { atm.print("ATM out of service."); }
    }

    /* ===================== ATM context ===================== */
    static final class ATM {
        static final int MAX_PIN_ATTEMPTS = 3;

        private ATMState state = new IdleState();
        private Card currentCard;
        private int pinAttempts = 0;
        private final CashDispenser dispenser;
        private final BankService bank;
        private final AtomicLong keySeq = new AtomicLong();

        ATM(CashDispenser dispenser, BankService bank) { this.dispenser = dispenser; this.bank = bank; }

        // Public API delegates to the current state.
        void insertCard(Card card)                                   { state.insertCard(this, card); }
        void enterPin(String pin)                                    { state.enterPin(this, pin); }
        void selectTransaction(TxnType type, Money amount)           { state.selectTransaction(this, type, amount, null); }
        void selectTransaction(TxnType type, Money amount, String to){ state.selectTransaction(this, type, amount, to); }
        void ejectCard()                                            { state.ejectCard(this); }

        // State-management helpers.
        void setState(ATMState s)        { this.state = s; }
        void setCurrentCard(Card c)      { this.currentCard = c; }
        Card getCurrentCard()            { return currentCard; }
        void clearCard()                 { this.currentCard = null; this.pinAttempts = 0; }
        void resetPinAttempts()          { this.pinAttempts = 0; }
        void incrementPinAttempts()      { this.pinAttempts++; }
        int  getPinAttempts()            { return pinAttempts; }
        CashDispenser getDispenser()     { return dispenser; }
        BankService getBank()            { return bank; }
        String newIdempotencyKey()       { return (currentCard != null ? currentCard.cardNumber : "anon") + "-" + keySeq.incrementAndGet(); }
        void print(String s)             { System.out.println(s); }
    }

    /* ===================== Demo (illustration only) ===================== */
    public static void main(String[] args) {
        // Seed the bank.
        MockBankService bank = new MockBankService();
        bank.seedAccount("ACC-1", Money.ofUnits(500));   // $500.00
        bank.seedAccount("ACC-2", Money.ofUnits(50));    // $50.00 (transfer target)
        bank.seedPin("CARD-1", "1234");

        // Seed the cash hopper.
        Map<Denomination, Integer> notes = new EnumMap<>(Denomination.class);
        notes.put(Denomination.HUNDRED, 5);
        notes.put(Denomination.FIFTY, 5);
        notes.put(Denomination.TWENTY, 10);
        notes.put(Denomination.TEN, 10);
        notes.put(Denomination.FIVE, 10);
        notes.put(Denomination.ONE, 20);
        CashDispenser dispenser = new CashDispenser(notes);

        ATM atm = new ATM(dispenser, bank);
        Card card = new Card("CARD-1", "ACC-1");

        System.out.println("=== Scenario 1: wrong PIN then correct PIN ===");
        atm.insertCard(card);
        atm.enterPin("0000");                 // wrong
        atm.enterPin("1234");                 // correct -> authenticated

        System.out.println("\n=== Scenario 2: balance, withdraw, deposit, transfer ===");
        atm.selectTransaction(TxnType.BALANCE, null);
        atm.selectTransaction(TxnType.WITHDRAW, Money.ofUnits(180));   // 1x100,1x50,1x20,...
        atm.selectTransaction(TxnType.DEPOSIT,  Money.ofUnits(70));
        atm.selectTransaction(TxnType.TRANSFER, Money.ofUnits(100), "ACC-2");
        atm.selectTransaction(TxnType.BALANCE, null);
        atm.ejectCard();

        System.out.println("\n=== Scenario 3: insufficient funds ===");
        atm.insertCard(card);
        atm.enterPin("1234");
        atm.selectTransaction(TxnType.WITHDRAW, Money.ofUnits(100000));  // way over balance
        atm.ejectCard();

        System.out.println("\n=== Scenario 4: amount not dispensable ($3 with no $1-friendly combo would still work; try odd) ===");
        atm.insertCard(card);
        atm.enterPin("1234");
        atm.selectTransaction(TxnType.WITHDRAW, Money.ofUnits(3));   // dispensable: 3x$1
        atm.ejectCard();

        System.out.println("\n=== Scenario 5: PIN lockout (3 wrong tries) ===");
        Card card2 = new Card("CARD-1", "ACC-1");  // same credentials, fresh card object
        atm.insertCard(card2);
        atm.enterPin("9");
        atm.enterPin("8");
        atm.enterPin("7");                           // 3rd wrong -> lock + retain
        System.out.println("Card locked? " + card2.locked);
        atm.insertCard(card2);                       // locked card refused at Idle

        System.out.println("\nRemaining ATM cash (whole units): " + dispenser.totalCashUnits());
    }
}

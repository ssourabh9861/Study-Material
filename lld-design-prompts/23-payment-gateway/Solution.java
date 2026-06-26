/* =====================================================================================
 * PAYMENT GATEWAY / PAYMENT PROCESSING — single-file LLD review artifact
 * -------------------------------------------------------------------------------------
 * This is a READ-AND-REVISE artifact. It is meant to be complete and correct-by-inspection,
 * NOT compiled or run in production. A `main` illustrates the key scenarios.
 *
 * ------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Which payment methods at launch (card / UPI / wallet / netbanking / BNPL)? Pluggable?
 *   2. One PSP or many? If many, who decides routing — static, least-cost, or failover?
 *   3. Auth-then-capture (two-step), single-step SALE, or both?
 *   4. Refunds in scope? Full only, or partial / multiple partials per transaction?
 *   5. Recurring / subscription charges, or only one-off?
 *   6. Sync (PSP answers now) or async (webhook later) or both?
 * Non-functional / constraints:
 *   7. Expected throughput (TPS) and latency budget per charge?
 *   8. Idempotency guarantee — duplicate request must never double-charge? Key scheme / window?
 *   9. Ledger consistency — strict (never overdraw) or eventual + reconciliation?
 *  10. Single-process (machine-coding) or distributed (multi-node, shared DB)?
 *  11. Failover expectation when a PSP is down — fail fast, queue, or backup PSP?
 * Data / compliance / out-of-scope:
 *  12. Store raw PAN (PCI-DSS) or tokenize via PSP? (Assume tokenize.)
 *  13. Single currency or multi-currency + FX?
 *  14. Fraud = simple rule chain or external ML service?
 *  15. Out of scope: HTTP layer, real PSP SDKs, DB engine, settlement, onboarding, KYC.
 *
 * ASSUMED ANSWERS (so the code is concrete):
 *   - Pluggable methods (CARD/UPI/WALLET/NETBANKING) and pluggable PSPs via Adapter.
 *   - Both SALE and AUTH->CAPTURE; full + multiple partial refunds.
 *   - Idempotency mandatory (per-key lock + putIfAbsent + same key to PSP).
 *   - Async webhooks + reconciliation for lost callbacks; bounded retries on transient errors.
 *   - Fraud as a Chain of Responsibility. Ledger is double-entry, per-account serialized.
 *   - Single-process multi-threaded scope; notes inline on what changes when distributed.
 *
 * PATTERNS: Strategy (PaymentMethod, PspRouter), State (TransactionState),
 *           Adapter (PspAdapter), Chain of Responsibility (FraudCheck),
 *           Facade (PaymentService), Observer (EventPublisher), Factory (PaymentMethodFactory).
 * ===================================================================================== */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/* =====================================================================================
 * VALUE OBJECTS & ENUMS
 * ===================================================================================== */

/** Immutable money value object with safe arithmetic and an ISO currency code. */
final class Money {
    private final BigDecimal amount;
    private final String currency;

    Money(BigDecimal amount, String currency) {
        if (amount == null || currency == null) throw new IllegalArgumentException("null money");
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
    }

    static Money of(String amount, String currency) { return new Money(new BigDecimal(amount), currency); }

    BigDecimal amount() { return amount; }
    String currency() { return currency; }

    boolean isPositive() { return amount.signum() > 0; }
    boolean isZeroOrNegative() { return amount.signum() <= 0; }

    Money add(Money o) { assertSameCurrency(o); return new Money(amount.add(o.amount), currency); }
    Money subtract(Money o) { assertSameCurrency(o); return new Money(amount.subtract(o.amount), currency); }
    boolean greaterThan(Money o) { assertSameCurrency(o); return amount.compareTo(o.amount) > 0; }
    boolean lessThan(Money o) { assertSameCurrency(o); return amount.compareTo(o.amount) < 0; }
    boolean equalsAmount(Money o) { assertSameCurrency(o); return amount.compareTo(o.amount) == 0; }

    private void assertSameCurrency(Money o) {
        if (!currency.equals(o.currency)) throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + o.currency);
    }

    @Override public String toString() { return amount.toPlainString() + " " + currency; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money)) return false;
        Money m = (Money) o;
        return amount.compareTo(m.amount) == 0 && currency.equals(m.currency);
    }
    @Override public int hashCode() { return Objects.hash(amount.stripTrailingZeros(), currency); }
}

enum MethodType { CARD, UPI, WALLET, NETBANKING }

/** SALE = authorize + capture immediately. AUTH = authorize now, capture later. */
enum CaptureMode { SALE, AUTH }

enum Status { CREATED, AUTHORIZED, CAPTURED, FAILED, PARTIALLY_REFUNDED, REFUNDED, PENDING }

enum ResultCode { SUCCESS, PENDING, FAILED }

/* =====================================================================================
 * STRATEGY — PAYMENT METHODS
 * Each method encapsulates its own validation and exposes a MethodType used by routing.
 * Open/Closed: a new method is a new class; nothing else changes.
 * ===================================================================================== */

interface PaymentMethod {
    MethodType type();
    /** Throws ValidationException if the method-specific details are invalid. */
    void validate(PaymentRequest request);
}

/** Card details — note we tokenize: we never store a raw PAN, only a token + last4 + brand. */
final class CardMethod implements PaymentMethod {
    final String token; final String last4; final String brand; final int expMonth; final int expYear;
    CardMethod(String token, String last4, String brand, int expMonth, int expYear) {
        this.token = token; this.last4 = last4; this.brand = brand; this.expMonth = expMonth; this.expYear = expYear;
    }
    public MethodType type() { return MethodType.CARD; }
    public void validate(PaymentRequest r) {
        if (token == null || token.isEmpty()) throw new ValidationException("missing card token");
        if (expMonth < 1 || expMonth > 12) throw new ValidationException("bad expiry month");
        // In production: also check expiry not in the past, brand support, etc.
    }
}

final class UpiMethod implements PaymentMethod {
    final String vpa; // virtual payment address, e.g. user@bank
    UpiMethod(String vpa) { this.vpa = vpa; }
    public MethodType type() { return MethodType.UPI; }
    public void validate(PaymentRequest r) {
        if (vpa == null || !vpa.contains("@")) throw new ValidationException("invalid UPI VPA");
    }
}

final class WalletMethod implements PaymentMethod {
    final String walletId;
    WalletMethod(String walletId) { this.walletId = walletId; }
    public MethodType type() { return MethodType.WALLET; }
    public void validate(PaymentRequest r) {
        if (walletId == null || walletId.isEmpty()) throw new ValidationException("missing wallet id");
    }
}

final class NetBankingMethod implements PaymentMethod {
    final String bankCode;
    NetBankingMethod(String bankCode) { this.bankCode = bankCode; }
    public MethodType type() { return MethodType.NETBANKING; }
    public void validate(PaymentRequest r) {
        if (bankCode == null || bankCode.isEmpty()) throw new ValidationException("missing bank code");
    }
}

/** FACTORY: builds the concrete PaymentMethod from the raw details on the request. */
final class PaymentMethodFactory {
    static PaymentMethod from(PaymentRequest r) {
        Object d = r.methodDetails;
        switch (r.methodType) {
            case CARD: return (CardMethod) d;
            case UPI: return (UpiMethod) d;
            case WALLET: return (WalletMethod) d;
            case NETBANKING: return (NetBankingMethod) d;
            default: throw new ValidationException("unknown method type: " + r.methodType);
        }
    }
}

class ValidationException extends RuntimeException {
    ValidationException(String m) { super(m); }
}

/* =====================================================================================
 * REQUEST / RESULT VALUE OBJECTS
 * ===================================================================================== */

final class PaymentRequest {
    final String idempotencyKey;
    final Money amount;
    final MethodType methodType;
    final Object methodDetails;   // a CardMethod / UpiMethod / ...
    final String customerId;
    final String merchantId;
    final CaptureMode captureMode;

    PaymentRequest(String idempotencyKey, Money amount, MethodType methodType, Object methodDetails,
                   String customerId, String merchantId, CaptureMode captureMode) {
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.methodType = methodType;
        this.methodDetails = methodDetails;
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.captureMode = captureMode;
    }

    /** Fingerprint to detect idempotency-key reuse with a DIFFERENT payload (a client bug). */
    String fingerprint() {
        return amount + "|" + methodType + "|" + customerId + "|" + merchantId + "|" + captureMode;
    }
}

final class PaymentResult {
    final ResultCode code;
    final String transactionId;
    final Status status;
    final String message;
    final String requestFingerprint; // stored so duplicate-with-different-payload can be detected

    PaymentResult(ResultCode code, String transactionId, Status status, String message, String fp) {
        this.code = code; this.transactionId = transactionId; this.status = status; this.message = message;
        this.requestFingerprint = fp;
    }
    @Override public String toString() {
        return "PaymentResult{" + code + ", txn=" + transactionId + ", status=" + status + ", msg=" + message + "}";
    }
}

/* =====================================================================================
 * ADAPTER — PSP INTEGRATIONS
 * Canonical interface; each PSP translates its foreign API to/from these types.
 * DIP: PaymentService depends on this abstraction, never on a concrete vendor.
 * ===================================================================================== */

final class PspRequest {
    final String idempotencyKey; // forwarded to the PSP so even a retry is deduped there
    final Money amount;
    final MethodType methodType;
    final String customerId;
    PspRequest(String idempotencyKey, Money amount, MethodType methodType, String customerId) {
        this.idempotencyKey = idempotencyKey; this.amount = amount; this.methodType = methodType; this.customerId = customerId;
    }
}

enum PspOutcome { OK, DECLINED, TRANSIENT_ERROR, TIMEOUT }

final class PspResult {
    final PspOutcome outcome;
    final String pspRef;   // the PSP's own reference id for this charge
    final String message;
    PspResult(PspOutcome outcome, String pspRef, String message) {
        this.outcome = outcome; this.pspRef = pspRef; this.message = message;
    }
    boolean isOk() { return outcome == PspOutcome.OK; }
    boolean isTransient() { return outcome == PspOutcome.TRANSIENT_ERROR || outcome == PspOutcome.TIMEOUT; }
}

enum PspStatus { AUTHORIZED, CAPTURED, FAILED, UNKNOWN }

interface PspAdapter {
    String name();
    boolean supports(MethodType type);
    PspResult authorize(PspRequest request);
    PspResult capture(String pspRef, Money amount);
    PspResult refund(String pspRef, Money amount);
    PspStatus fetchStatus(String pspRef); // used by reconciliation
}

/**
 * A configurable mock PSP standing in for a real vendor SDK. In a real adapter the body
 * would translate to e.g. Stripe's PaymentIntents API and map its errors to PspOutcome.
 */
final class MockPspAdapter implements PspAdapter {
    private final String name;
    private final List<MethodType> supported;
    // Scripted behaviour so the demo can show OK / decline / transient / timeout deterministically.
    private final PspOutcome scriptedAuthOutcome;
    private final AtomicInteger transientThenOkCounter; // >0 means: fail transiently this many times, then OK
    private final Map<String, PspStatus> statusByRef = new ConcurrentHashMap<>();

    MockPspAdapter(String name, List<MethodType> supported, PspOutcome scriptedAuthOutcome, int transientFailsBeforeOk) {
        this.name = name; this.supported = supported; this.scriptedAuthOutcome = scriptedAuthOutcome;
        this.transientThenOkCounter = new AtomicInteger(transientFailsBeforeOk);
    }

    public String name() { return name; }
    public boolean supports(MethodType type) { return supported.contains(type); }

    public PspResult authorize(PspRequest request) {
        if (transientThenOkCounter.get() > 0) {
            transientThenOkCounter.decrementAndGet();
            return new PspResult(PspOutcome.TRANSIENT_ERROR, null, name + " transient error");
        }
        if (scriptedAuthOutcome == PspOutcome.DECLINED)
            return new PspResult(PspOutcome.DECLINED, null, name + " declined");
        if (scriptedAuthOutcome == PspOutcome.TIMEOUT)
            return new PspResult(PspOutcome.TIMEOUT, null, name + " timeout");
        String ref = name + "-" + UUID.randomUUID();
        statusByRef.put(ref, PspStatus.AUTHORIZED);
        return new PspResult(PspOutcome.OK, ref, "authorized");
    }

    public PspResult capture(String pspRef, Money amount) {
        statusByRef.put(pspRef, PspStatus.CAPTURED);
        return new PspResult(PspOutcome.OK, pspRef, "captured");
    }

    public PspResult refund(String pspRef, Money amount) {
        return new PspResult(PspOutcome.OK, pspRef, "refunded");
    }

    public PspStatus fetchStatus(String pspRef) {
        return statusByRef.getOrDefault(pspRef, PspStatus.UNKNOWN);
    }

    /** Test hook: simulate the PSP eventually capturing (used by reconciliation demo). */
    void simulatePspCapture(String pspRef) { statusByRef.put(pspRef, PspStatus.CAPTURED); }
}

/* =====================================================================================
 * STRATEGY — PSP ROUTING
 * Returns an ordered list of adapters to try (failover). Swap the implementation for
 * least-cost routing without touching PaymentService.
 * ===================================================================================== */

interface PspRouter {
    List<PspAdapter> route(PaymentRequest request);
}

final class FailoverRouter implements PspRouter {
    private final List<PspAdapter> adapters;
    FailoverRouter(List<PspAdapter> adapters) { this.adapters = adapters; }
    public List<PspAdapter> route(PaymentRequest request) {
        List<PspAdapter> out = new ArrayList<>();
        for (PspAdapter a : adapters) if (a.supports(request.methodType)) out.add(a);
        if (out.isEmpty()) throw new NoRouteException("no PSP supports " + request.methodType);
        return out; // tried in order; if first errors transiently we move to the next
    }
}

/** Alternative Strategy: sort by (lowest fee, then highest success rate). Sketch for §4. */
final class LeastCostRouter implements PspRouter {
    private final List<PspAdapter> adapters;
    private final Map<String, Integer> feeBps; // basis points per PSP name
    LeastCostRouter(List<PspAdapter> adapters, Map<String, Integer> feeBps) { this.adapters = adapters; this.feeBps = feeBps; }
    public List<PspAdapter> route(PaymentRequest request) {
        List<PspAdapter> out = new ArrayList<>();
        for (PspAdapter a : adapters) if (a.supports(request.methodType)) out.add(a);
        if (out.isEmpty()) throw new NoRouteException("no PSP supports " + request.methodType);
        out.sort((x, y) -> Integer.compare(feeBps.getOrDefault(x.name(), 9999), feeBps.getOrDefault(y.name(), 9999)));
        return out;
    }
}

class NoRouteException extends RuntimeException { NoRouteException(String m) { super(m); } }

/* =====================================================================================
 * CHAIN OF RESPONSIBILITY — FRAUD / VALIDATION
 * Ordered, independent checks; any link can reject and stop the chain.
 * ===================================================================================== */

final class FraudDecision {
    final boolean approved;
    final String reason;
    private FraudDecision(boolean approved, String reason) { this.approved = approved; this.reason = reason; }
    static FraudDecision approve() { return new FraudDecision(true, "approved"); }
    static FraudDecision reject(String reason) { return new FraudDecision(false, reason); }
}

abstract class FraudCheck {
    private FraudCheck next;
    FraudCheck setNext(FraudCheck n) { this.next = n; return n; } // returns next for fluent chaining

    final FraudDecision check(PaymentRequest r) {
        FraudDecision d = doCheck(r);
        if (!d.approved) return d;            // short-circuit on rejection
        return next == null ? FraudDecision.approve() : next.check(r);
    }
    protected abstract FraudDecision doCheck(PaymentRequest r);
}

final class AmountLimitCheck extends FraudCheck {
    private final Money max;
    AmountLimitCheck(Money max) { this.max = max; }
    protected FraudDecision doCheck(PaymentRequest r) {
        if (r.amount.isZeroOrNegative()) return FraudDecision.reject("non-positive amount");
        if (r.amount.greaterThan(max)) return FraudDecision.reject("amount exceeds limit " + max);
        return FraudDecision.approve();
    }
}

/** Velocity: too many attempts by the same customer in this window => reject. */
final class VelocityCheck extends FraudCheck {
    private final int maxPerCustomer;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    VelocityCheck(int maxPerCustomer) { this.maxPerCustomer = maxPerCustomer; }
    protected FraudDecision doCheck(PaymentRequest r) {
        int n = counts.computeIfAbsent(r.customerId, k -> new AtomicInteger()).incrementAndGet();
        if (n > maxPerCustomer) return FraudDecision.reject("velocity limit exceeded for " + r.customerId);
        return FraudDecision.approve();
    }
}

final class BlacklistCheck extends FraudCheck {
    private final java.util.Set<String> blacklisted;
    BlacklistCheck(java.util.Set<String> blacklisted) { this.blacklisted = blacklisted; }
    protected FraudDecision doCheck(PaymentRequest r) {
        if (blacklisted.contains(r.customerId)) return FraudDecision.reject("customer blacklisted");
        return FraudDecision.approve();
    }
}

/** Stand-in for an external ML risk score; reject above a threshold. */
final class RiskScoreCheck extends FraudCheck {
    private final int threshold;
    RiskScoreCheck(int threshold) { this.threshold = threshold; }
    protected FraudDecision doCheck(PaymentRequest r) {
        int score = Math.abs(r.fingerprint().hashCode()) % 100; // deterministic pseudo-score for the demo
        if (score >= threshold) return FraudDecision.reject("risk score " + score + " >= " + threshold);
        return FraudDecision.approve();
    }
}

/* =====================================================================================
 * STATE — TRANSACTION LIFECYCLE
 * Each state knows which transitions are legal. Illegal moves throw, by construction.
 * Transitions are idempotent where it matters (re-applying AUTHORIZED is a no-op),
 * which makes the webhook-vs-reconciliation race safe.
 * ===================================================================================== */

interface TransactionState {
    Status status();
    void authorize(Transaction txn, String pspRef);
    void capture(Transaction txn, Money amount);
    void fail(Transaction txn, String reason);
    void refund(Transaction txn, Money amount);
}

abstract class BaseState implements TransactionState {
    public void authorize(Transaction t, String pspRef) { illegal("authorize"); }
    public void capture(Transaction t, Money amount) { illegal("capture"); }
    public void fail(Transaction t, String reason) { t.applyFail(reason); } // failing is allowed from most states
    public void refund(Transaction t, Money amount) { illegal("refund"); }
    private void illegal(String op) { throw new IllegalStateTransitionException(op + " not allowed in state " + status()); }
}

final class CreatedState extends BaseState {
    public Status status() { return Status.CREATED; }
    public void authorize(Transaction t, String pspRef) { t.applyAuthorize(pspRef); }
}

final class AuthorizedState extends BaseState {
    public Status status() { return Status.AUTHORIZED; }
    // Idempotent re-authorize (e.g. a duplicate webhook) is a no-op.
    public void authorize(Transaction t, String pspRef) { /* no-op: already authorized */ }
    public void capture(Transaction t, Money amount) { t.applyCapture(amount); }
}

final class CapturedState extends BaseState {
    public Status status() { return Status.CAPTURED; }
    public void capture(Transaction t, Money amount) { /* no-op: already captured */ }
    public void refund(Transaction t, Money amount) { t.applyRefund(amount); }
    public void fail(Transaction t, String reason) { /* cannot fail a captured txn */ }
}

final class PartiallyRefundedState extends BaseState {
    public Status status() { return Status.PARTIALLY_REFUNDED; }
    public void refund(Transaction t, Money amount) { t.applyRefund(amount); }
    public void fail(Transaction t, String reason) { /* terminal-ish: ignore */ }
}

final class RefundedState extends BaseState {
    public Status status() { return Status.REFUNDED; }
    public void refund(Transaction t, Money amount) { throw new IllegalStateTransitionException("already fully refunded"); }
    public void fail(Transaction t, String reason) { /* terminal */ }
}

final class FailedState extends BaseState {
    public Status status() { return Status.FAILED; }
    public void fail(Transaction t, String reason) { /* already failed */ }
}

class IllegalStateTransitionException extends RuntimeException { IllegalStateTransitionException(String m) { super(m); } }

/* =====================================================================================
 * TRANSACTION AGGREGATE  (thread-safe: every mutation holds the per-txn lock)
 * ===================================================================================== */

final class Refund {
    final Money amount; final long at;
    Refund(Money amount) { this.amount = amount; this.at = System.nanoTime(); }
}

final class StateChange {
    final Status from; final Status to; final String note; final long at;
    StateChange(Status from, Status to, String note) { this.from = from; this.to = to; this.note = note; this.at = System.nanoTime(); }
    @Override public String toString() { return from + "->" + to + (note == null ? "" : " (" + note + ")"); }
}

final class Transaction {
    private final String id;
    private final Money amount;          // requested amount
    private Money capturedAmount;        // grows on capture
    private Money refundedAmount;        // grows on refund
    private final MethodType methodType;
    private final String customerId;
    private final String merchantId;
    private final String currency;

    private TransactionState state;
    private String pspRef;
    private final List<Refund> refunds = new ArrayList<>();
    private final List<StateChange> history = new ArrayList<>();
    private final Lock lock = new ReentrantLock(); // guards all mutations of this aggregate

    Transaction(String id, PaymentRequest r) {
        this.id = id;
        this.amount = r.amount;
        this.currency = r.amount.currency();
        this.capturedAmount = Money.of("0", currency);
        this.refundedAmount = Money.of("0", currency);
        this.methodType = r.methodType;
        this.customerId = r.customerId;
        this.merchantId = r.merchantId;
        this.state = new CreatedState();
    }

    // ---- public transition API delegates to the current state object (State pattern) ----
    void authorize(String ref) { lock.lock(); try { state.authorize(this, ref); } finally { lock.unlock(); } }
    void capture(Money amt)    { lock.lock(); try { state.capture(this, amt); } finally { lock.unlock(); } }
    void fail(String reason)   { lock.lock(); try { state.fail(this, reason); } finally { lock.unlock(); } }
    void refund(Money amt)     { lock.lock(); try { state.refund(this, amt); } finally { lock.unlock(); } }

    // ---- package-private "apply" hooks invoked by state objects (already under lock) ----
    void applyAuthorize(String ref) {
        this.pspRef = ref;
        transitionTo(new AuthorizedState(), "authorized ref=" + ref);
    }
    void applyCapture(Money amt) {
        if (amt.greaterThan(amount)) throw new IllegalStateTransitionException("capture exceeds authorized amount");
        this.capturedAmount = amt;
        transitionTo(new CapturedState(), "captured " + amt);
    }
    void applyRefund(Money amt) {
        if (amt.isZeroOrNegative()) throw new ValidationException("refund must be positive");
        Money newTotal = refundedAmount.add(amt);
        if (newTotal.greaterThan(capturedAmount))
            throw new IllegalStateTransitionException("refund " + newTotal + " exceeds captured " + capturedAmount);
        this.refundedAmount = newTotal;
        this.refunds.add(new Refund(amt));
        if (refundedAmount.equalsAmount(capturedAmount)) transitionTo(new RefundedState(), "fully refunded");
        else transitionTo(new PartiallyRefundedState(), "partial refund " + amt);
    }
    void applyFail(String reason) { transitionTo(new FailedState(), "failed: " + reason); }

    private void transitionTo(TransactionState next, String note) {
        history.add(new StateChange(state.status(), next.status(), note));
        this.state = next;
    }

    // ---- read accessors (snapshot under lock to be safe) ----
    String id() { return id; }
    String pspRef() { lock.lock(); try { return pspRef; } finally { lock.unlock(); } }
    Money amount() { return amount; }
    Money capturedAmount() { lock.lock(); try { return capturedAmount; } finally { lock.unlock(); } }
    Money refundedAmount() { lock.lock(); try { return refundedAmount; } finally { lock.unlock(); } }
    String customerId() { return customerId; }
    String merchantId() { return merchantId; }
    String currency() { return currency; }
    Status status() { lock.lock(); try { return state.status(); } finally { lock.unlock(); } }
    List<StateChange> history() { lock.lock(); try { return new ArrayList<>(history); } finally { lock.unlock(); } }
}

/* =====================================================================================
 * REPOSITORY (persistence abstraction; in-memory default)
 * ===================================================================================== */

interface TransactionRepository {
    void save(Transaction t);
    Transaction find(String id);
    List<Transaction> findByStatus(Status status);
}

final class InMemoryTransactionRepository implements TransactionRepository {
    private final Map<String, Transaction> store = new ConcurrentHashMap<>();
    public void save(Transaction t) { store.put(t.id(), t); }
    public Transaction find(String id) { return store.get(id); }
    public List<Transaction> findByStatus(Status status) {
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : store.values()) if (t.status() == status) out.add(t);
        return out;
    }
}

/* =====================================================================================
 * IDEMPOTENCY STORE
 * Atomic putIfAbsent of (key -> result) + a per-key lock so concurrent duplicates can't
 * both reach the PSP. DISTRIBUTED NOTE: replace with a DB unique constraint / Redis SET NX.
 * ===================================================================================== */

interface IdempotencyStore {
    PaymentResult get(String key);
    /** Returns the existing result if present, otherwise stores and returns the new one. */
    PaymentResult putIfAbsent(String key, PaymentResult result);
    Lock lockFor(String key);
}

final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<String, PaymentResult> results = new ConcurrentHashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    public PaymentResult get(String key) { return results.get(key); }
    public PaymentResult putIfAbsent(String key, PaymentResult result) {
        PaymentResult prev = results.putIfAbsent(key, result);
        return prev != null ? prev : result;
    }
    public Lock lockFor(String key) { return locks.computeIfAbsent(key, k -> new ReentrantLock()); }
}

/* =====================================================================================
 * LEDGER (double-entry, per-account serialized, non-negative for debit accounts)
 * ===================================================================================== */

final class LedgerEntry {
    final String id; final String accountId; final Money delta; final String memo; final long at;
    LedgerEntry(String accountId, Money delta, String memo) {
        this.id = UUID.randomUUID().toString(); this.accountId = accountId; this.delta = delta; this.memo = memo; this.at = System.nanoTime();
    }
}

final class Account {
    final String id;
    final boolean allowNegative; // merchant accounts may go up; a prepaid customer wallet may not
    private Money balance;
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Lock lock = new ReentrantLock();

    Account(String id, Money opening, boolean allowNegative) { this.id = id; this.balance = opening; this.allowNegative = allowNegative; }

    void apply(Money delta, String memo) {
        lock.lock();
        try {
            Money next = balance.add(delta);
            if (!allowNegative && next.amount().signum() < 0)
                throw new InsufficientFundsException("account " + id + " would go negative: " + next);
            balance = next;
            entries.add(new LedgerEntry(id, delta, memo));
        } finally { lock.unlock(); }
    }
    Money balance() { lock.lock(); try { return balance; } finally { lock.unlock(); } }
}

class InsufficientFundsException extends RuntimeException { InsufficientFundsException(String m) { super(m); } }

final class Ledger {
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    void register(Account a) { accounts.put(a.id, a); }
    Account account(String id) {
        Account a = accounts.get(id);
        if (a == null) throw new IllegalArgumentException("no account " + id);
        return a;
    }

    /**
     * Double-entry money movement. To avoid deadlock under concurrency we always lock the
     * two accounts in a deterministic order (by id) — classic ordered-locking technique.
     */
    void post(String fromId, String toId, Money amount, String memo) {
        Account from = account(fromId), to = account(toId);
        Account first = fromId.compareTo(toId) <= 0 ? from : to;
        Account second = first == from ? to : from;
        synchronized (first) {
            synchronized (second) {
                from.apply(new Money(amount.amount().negate(), amount.currency()), "debit:" + memo);
                to.apply(amount, "credit:" + memo);
            }
        }
    }
    Money balance(String accountId) { return account(accountId).balance(); }
}

/* =====================================================================================
 * OBSERVER — EVENTS (decouple side effects: audit, notifications, metrics)
 * ===================================================================================== */

final class PaymentEvent {
    final String txnId; final Status status; final String detail;
    PaymentEvent(String txnId, Status status, String detail) { this.txnId = txnId; this.status = status; this.detail = detail; }
}

interface PaymentEventListener { void onEvent(PaymentEvent e); }

final class EventPublisher {
    private final List<PaymentEventListener> listeners = new ArrayList<>();
    void subscribe(PaymentEventListener l) { listeners.add(l); }
    void publish(PaymentEvent e) {
        // Copy to avoid concurrent-modification if a listener subscribes during dispatch.
        for (PaymentEventListener l : new ArrayList<>(listeners)) {
            try { l.onEvent(e); } catch (RuntimeException ex) { /* a faulty listener must not break the flow */ }
        }
    }
}

final class AuditLogListener implements PaymentEventListener {
    public void onEvent(PaymentEvent e) { System.out.println("  [AUDIT] txn=" + e.txnId + " -> " + e.status + " : " + e.detail); }
}

/* =====================================================================================
 * RETRY POLICY (bounded; only retries TRANSIENT outcomes; reuses idempotency key)
 * ===================================================================================== */

final class RetryPolicy {
    final int maxAttempts;
    RetryPolicy(int maxAttempts) { this.maxAttempts = maxAttempts; }
}

/* =====================================================================================
 * WEBHOOK EVENT (async PSP confirmation)
 * ===================================================================================== */

final class WebhookEvent {
    final String eventId;   // used to dedupe replayed webhooks (idempotency again)
    final String pspRef;
    final PspStatus status;
    final String signature; // HMAC; verified before processing in production
    WebhookEvent(String eventId, String pspRef, PspStatus status, String signature) {
        this.eventId = eventId; this.pspRef = pspRef; this.status = status; this.signature = signature;
    }
}

/* =====================================================================================
 * FACADE — PAYMENT SERVICE (orchestrator)
 * Coordinates: idempotency -> fraud -> route -> authorize (+capture) -> ledger -> events.
 * Depends only on abstractions (DIP), all injected via the constructor.
 * ===================================================================================== */

final class PaymentService {
    private final IdempotencyStore idempotency;
    private final FraudCheck fraudChain;          // head of the Chain of Responsibility
    private final PspRouter router;
    private final TransactionRepository repo;
    private final Ledger ledger;
    private final EventPublisher events;
    private final RetryPolicy retry;

    // Map PSP ref -> txn id so webhooks/reconciliation can find the transaction.
    private final Map<String, String> pspRefToTxn = new ConcurrentHashMap<>();
    // Dedupe webhook event ids.
    private final java.util.Set<String> processedWebhooks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Remember which adapter handled each ref so capture/refund go to the same PSP.
    private final Map<String, PspAdapter> adapterByRef = new ConcurrentHashMap<>();

    PaymentService(IdempotencyStore idempotency, FraudCheck fraudChain, PspRouter router,
                   TransactionRepository repo, Ledger ledger, EventPublisher events, RetryPolicy retry) {
        this.idempotency = idempotency; this.fraudChain = fraudChain; this.router = router;
        this.repo = repo; this.ledger = ledger; this.events = events; this.retry = retry;
    }

    /** Initiate a payment. SALE = authorize+capture; AUTH = authorize only. */
    PaymentResult initiate(PaymentRequest req) {
        // 1) Idempotency: per-key lock so concurrent duplicates serialize; cached result wins.
        Lock keyLock = idempotency.lockFor(req.idempotencyKey);
        keyLock.lock();
        try {
            PaymentResult existing = idempotency.get(req.idempotencyKey);
            if (existing != null) {
                // Edge case: same key, different payload => client bug, reject.
                if (!Objects.equals(existing.requestFingerprint, req.fingerprint()))
                    return fail(null, "idempotency key reused with a different payload", req.fingerprint());
                return existing; // duplicate — return original, never re-charge
            }

            // 2) Method-specific validation (Strategy).
            PaymentMethod method = PaymentMethodFactory.from(req);
            method.validate(req);

            // 3) Fraud / validation chain (Chain of Responsibility).
            FraudDecision decision = fraudChain.check(req);
            if (!decision.approved) {
                PaymentResult r = fail(null, "fraud: " + decision.reason, req.fingerprint());
                return idempotency.putIfAbsent(req.idempotencyKey, r);
            }

            // 4) Create the transaction (CREATED state).
            Transaction txn = new Transaction("txn-" + UUID.randomUUID(), req);
            repo.save(txn);

            // 5) Route to PSPs (Strategy) and authorize with failover + bounded retry.
            List<PspAdapter> candidates = router.route(req);
            PspRequest pspReq = new PspRequest(req.idempotencyKey, req.amount, req.methodType, req.customerId);
            PspResult authRes = null; PspAdapter used = null;

            outer:
            for (PspAdapter adapter : candidates) {
                for (int attempt = 1; attempt <= retry.maxAttempts; attempt++) {
                    PspResult r = adapter.authorize(pspReq);
                    if (r.isOk()) { authRes = r; used = adapter; break outer; }
                    if (r.outcome == PspOutcome.TIMEOUT) {
                        // INDETERMINATE: do not assume failure, do not re-charge. Mark PENDING + reconcile later.
                        txn.fail("timeout-pending"); // simplified: in production a dedicated PENDING state + reconcile
                        PaymentResult pending = new PaymentResult(ResultCode.PENDING, txn.id(), Status.PENDING,
                                "PSP timeout — pending reconciliation", req.fingerprint());
                        publish(txn, "psp timeout, pending");
                        return idempotency.putIfAbsent(req.idempotencyKey, pending);
                    }
                    if (r.outcome == PspOutcome.DECLINED) break; // terminal for this PSP; try next PSP
                    // TRANSIENT_ERROR: retry on the same adapter (same idempotency key => safe).
                }
            }

            if (authRes == null) {
                txn.fail("all PSPs failed/declined");
                PaymentResult r = new PaymentResult(ResultCode.FAILED, txn.id(), Status.FAILED, "authorization failed", req.fingerprint());
                publish(txn, "authorization failed");
                return idempotency.putIfAbsent(req.idempotencyKey, r);
            }

            // 6) Apply AUTHORIZED via the state machine and remember the ref->txn/adapter mapping.
            txn.authorize(authRes.pspRef);
            pspRefToTxn.put(authRes.pspRef, txn.id());
            adapterByRef.put(authRes.pspRef, used);
            publish(txn, "authorized by " + used.name());

            // 7) If SALE, capture immediately and post to the ledger.
            if (req.captureMode == CaptureMode.SALE) {
                PspResult cap = used.capture(authRes.pspRef, req.amount);
                if (!cap.isOk()) {
                    txn.fail("capture failed");
                    PaymentResult r = new PaymentResult(ResultCode.FAILED, txn.id(), Status.FAILED, "capture failed", req.fingerprint());
                    publish(txn, "capture failed");
                    return idempotency.putIfAbsent(req.idempotencyKey, r);
                }
                txn.capture(req.amount);
                ledger.post(custAcc(req.customerId), merchAcc(req.merchantId), req.amount, "capture " + txn.id());
                publish(txn, "captured");
            }

            PaymentResult ok = new PaymentResult(ResultCode.SUCCESS, txn.id(), txn.status(), "ok", req.fingerprint());
            return idempotency.putIfAbsent(req.idempotencyKey, ok);
        } finally {
            keyLock.unlock();
        }
    }

    /** Capture an AUTHORIZED transaction (the two-step flow). */
    PaymentResult capture(String txnId, Money amount) {
        Transaction txn = mustFind(txnId);
        PspAdapter adapter = adapterByRef.get(txn.pspRef());
        PspResult r = adapter.capture(txn.pspRef(), amount);
        if (!r.isOk()) return fail(txnId, "psp capture failed", null);
        txn.capture(amount);
        ledger.post(custAcc(txn.customerId()), merchAcc(txn.merchantId()), amount, "capture " + txnId);
        publish(txn, "captured " + amount);
        return new PaymentResult(ResultCode.SUCCESS, txnId, txn.status(), "captured", null);
    }

    /** Full or partial refund. Guarded by sum(refunds) <= captured (enforced in Transaction). */
    PaymentResult refund(String txnId, Money amount) {
        Transaction txn = mustFind(txnId);
        // Guard up front to avoid hitting the PSP for an obviously invalid refund.
        if (txn.refundedAmount().add(amount).greaterThan(txn.capturedAmount()))
            return fail(txnId, "refund exceeds captured amount", null);
        PspAdapter adapter = adapterByRef.get(txn.pspRef());
        PspResult r = adapter.refund(txn.pspRef(), amount);
        if (!r.isOk()) return fail(txnId, "psp refund failed", null);
        txn.refund(amount);
        ledger.post(merchAcc(txn.merchantId()), custAcc(txn.customerId()), amount, "refund " + txnId);
        publish(txn, "refunded " + amount);
        return new PaymentResult(ResultCode.SUCCESS, txnId, txn.status(), "refunded", null);
    }

    /** Async PSP confirmation. Verifies signature, dedupes by event id, advances the state machine. */
    void handleWebhook(WebhookEvent e) {
        if (!verifySignature(e)) { System.out.println("  [WEBHOOK] rejected bad signature " + e.eventId); return; }
        if (!processedWebhooks.add(e.eventId)) return; // replay — already processed
        String txnId = pspRefToTxn.get(e.pspRef);
        if (txnId == null) { System.out.println("  [WEBHOOK] unknown ref " + e.pspRef); return; }
        Transaction txn = repo.find(txnId);
        applyPspStatus(txn, e.status, "webhook " + e.eventId);
    }

    /** Reconciliation: ask the PSP for the authoritative status and apply it to stuck txns. */
    void reconcile(String txnId) {
        Transaction txn = mustFind(txnId);
        PspAdapter adapter = adapterByRef.get(txn.pspRef());
        if (adapter == null) return;
        PspStatus authoritative = adapter.fetchStatus(txn.pspRef());
        applyPspStatus(txn, authoritative, "reconcile");
    }

    // ---- helpers --------------------------------------------------------------------

    private void applyPspStatus(Transaction txn, PspStatus status, String note) {
        switch (status) {
            case AUTHORIZED:
                txn.authorize(txn.pspRef()); // idempotent if already authorized
                publish(txn, note + ": authorized");
                break;
            case CAPTURED:
                txn.authorize(txn.pspRef());        // ensure authorized first (no-op if already)
                if (txn.status() != Status.CAPTURED) {
                    txn.capture(txn.amount());
                    ledger.post(custAcc(txn.customerId()), merchAcc(txn.merchantId()), txn.amount(), "reconcile-capture " + txn.id());
                }
                publish(txn, note + ": captured");
                break;
            case FAILED:
                txn.fail("psp reported failed");
                publish(txn, note + ": failed");
                break;
            case UNKNOWN:
            default:
                System.out.println("  [RECON] txn=" + txn.id() + " still UNKNOWN; will retry later");
        }
    }

    private boolean verifySignature(WebhookEvent e) {
        // Production: HMAC over the raw body with the PSP's shared secret. Demo: accept non-empty.
        return e.signature != null && !e.signature.isEmpty();
    }

    private Transaction mustFind(String id) {
        Transaction t = repo.find(id);
        if (t == null) throw new IllegalArgumentException("no transaction " + id);
        return t;
    }

    private PaymentResult fail(String txnId, String msg, String fp) {
        return new PaymentResult(ResultCode.FAILED, txnId, Status.FAILED, msg, fp);
    }

    private void publish(Transaction txn, String detail) {
        events.publish(new PaymentEvent(txn.id(), txn.status(), detail));
    }

    // Convention: customer and merchant ledger account ids.
    private String custAcc(String customerId) { return "cust:" + customerId; }
    private String merchAcc(String merchantId) { return "merch:" + merchantId; }
}

/* =====================================================================================
 * DEMO — illustrates the key scenarios (NOT a test suite; for reading)
 * ===================================================================================== */

public class Solution {
    public static void main(String[] args) {
        // ---- Wire up the system (constructor injection = DIP) ----
        Ledger ledger = new Ledger();
        // Customers prepaid; merchants accumulate. (allowNegative=false for customers in this demo.)
        ledger.register(new Account("cust:alice", Money.of("1000.00", "INR"), false));
        ledger.register(new Account("cust:bob", Money.of("1000.00", "INR"), false));
        ledger.register(new Account("merch:shop1", Money.of("0.00", "INR"), true));

        // PSPs (Adapter). primary supports CARD+UPI; backup supports CARD only.
        MockPspAdapter primary = new MockPspAdapter("PSP-Primary",
                Arrays.asList(MethodType.CARD, MethodType.UPI), PspOutcome.OK, /*transientFails=*/0);
        MockPspAdapter backup = new MockPspAdapter("PSP-Backup",
                Arrays.asList(MethodType.CARD), PspOutcome.OK, 0);
        PspRouter router = new FailoverRouter(Arrays.asList(primary, backup));

        // Fraud chain (Chain of Responsibility): amount limit -> velocity -> blacklist -> risk score.
        FraudCheck fraud = new AmountLimitCheck(Money.of("100000.00", "INR"));
        fraud.setNext(new VelocityCheck(10))
             .setNext(new BlacklistCheck(new java.util.HashSet<>(Arrays.asList("mallory"))))
             .setNext(new RiskScoreCheck(/*threshold=*/95)); // very permissive for the demo

        EventPublisher events = new EventPublisher();
        events.subscribe(new AuditLogListener());

        PaymentService service = new PaymentService(
                new InMemoryIdempotencyStore(), fraud, router,
                new InMemoryTransactionRepository(), ledger, events, new RetryPolicy(3));

        // ---- Scenario 1: successful SALE (auth + capture) ----
        System.out.println("== Scenario 1: SALE success ==");
        PaymentRequest r1 = new PaymentRequest("idem-1", Money.of("250.00", "INR"), MethodType.CARD,
                new CardMethod("tok_abc", "4242", "VISA", 12, 2030), "alice", "shop1", CaptureMode.SALE);
        PaymentResult res1 = service.initiate(r1);
        System.out.println("  result: " + res1);
        System.out.println("  alice balance: " + ledger.balance("cust:alice") + ", shop1 balance: " + ledger.balance("merch:shop1"));

        // ---- Scenario 2: idempotency — replaying the SAME request must NOT re-charge ----
        System.out.println("== Scenario 2: idempotent replay ==");
        PaymentResult res2 = service.initiate(r1); // same idem-1
        System.out.println("  replay result (same txn id, no re-charge): " + res2);
        System.out.println("  alice balance unchanged: " + ledger.balance("cust:alice"));

        // ---- Scenario 3: two-step AUTH then CAPTURE ----
        System.out.println("== Scenario 3: AUTH then CAPTURE ==");
        PaymentRequest r3 = new PaymentRequest("idem-3", Money.of("300.00", "INR"), MethodType.UPI,
                new UpiMethod("bob@bank"), "bob", "shop1", CaptureMode.AUTH);
        PaymentResult res3 = service.initiate(r3);
        System.out.println("  after auth: " + res3 + " (status should be AUTHORIZED)");
        PaymentResult cap3 = service.capture(res3.transactionId, Money.of("300.00", "INR"));
        System.out.println("  after capture: " + cap3);
        System.out.println("  bob balance: " + ledger.balance("cust:bob") + ", shop1 balance: " + ledger.balance("merch:shop1"));

        // ---- Scenario 4: partial refunds then full ----
        System.out.println("== Scenario 4: partial refunds ==");
        PaymentResult ref1 = service.refund(res3.transactionId, Money.of("100.00", "INR"));
        System.out.println("  refund 100: " + ref1 + " (expect PARTIALLY_REFUNDED)");
        PaymentResult ref2 = service.refund(res3.transactionId, Money.of("200.00", "INR"));
        System.out.println("  refund 200: " + ref2 + " (expect REFUNDED)");
        PaymentResult ref3 = service.refund(res3.transactionId, Money.of("1.00", "INR"));
        System.out.println("  over-refund attempt rejected: " + ref3);
        System.out.println("  bob balance restored: " + ledger.balance("cust:bob"));

        // ---- Scenario 5: fraud rejection (blacklisted customer) ----
        System.out.println("== Scenario 5: fraud reject ==");
        PaymentRequest r5 = new PaymentRequest("idem-5", Money.of("50.00", "INR"), MethodType.CARD,
                new CardMethod("tok_x", "1111", "VISA", 1, 2031), "mallory", "shop1", CaptureMode.SALE);
        System.out.println("  result: " + service.initiate(r5));

        // ---- Scenario 6: failover — primary declines, backup succeeds ----
        System.out.println("== Scenario 6: PSP failover ==");
        MockPspAdapter declining = new MockPspAdapter("PSP-Declining",
                Arrays.asList(MethodType.CARD), PspOutcome.DECLINED, 0);
        MockPspAdapter healthy = new MockPspAdapter("PSP-Healthy",
                Arrays.asList(MethodType.CARD), PspOutcome.OK, 0);
        PaymentService failoverSvc = new PaymentService(new InMemoryIdempotencyStore(), fraud,
                new FailoverRouter(Arrays.asList(declining, healthy)),
                new InMemoryTransactionRepository(), ledger, events, new RetryPolicy(2));
        PaymentRequest r6 = new PaymentRequest("idem-6", Money.of("75.00", "INR"), MethodType.CARD,
                new CardMethod("tok_y", "2222", "MC", 6, 2030), "alice", "shop1", CaptureMode.SALE);
        System.out.println("  result (should succeed via PSP-Healthy): " + failoverSvc.initiate(r6));

        // ---- Scenario 7: transient error then success (bounded retry) ----
        System.out.println("== Scenario 7: transient retry ==");
        MockPspAdapter flaky = new MockPspAdapter("PSP-Flaky",
                Arrays.asList(MethodType.CARD), PspOutcome.OK, /*transientFails=*/2);
        PaymentService retrySvc = new PaymentService(new InMemoryIdempotencyStore(), fraud,
                new FailoverRouter(Arrays.asList(flaky)),
                new InMemoryTransactionRepository(), ledger, events, new RetryPolicy(3));
        PaymentRequest r7 = new PaymentRequest("idem-7", Money.of("40.00", "INR"), MethodType.CARD,
                new CardMethod("tok_z", "3333", "VISA", 9, 2030), "alice", "shop1", CaptureMode.SALE);
        System.out.println("  result (after 2 transient fails, then OK): " + retrySvc.initiate(r7));

        // ---- Scenario 8: async AUTH then webhook capture + reconciliation ----
        System.out.println("== Scenario 8: webhook + reconciliation ==");
        InMemoryTransactionRepository asyncRepo = new InMemoryTransactionRepository();
        MockPspAdapter asyncPsp = new MockPspAdapter("PSP-Async",
                Arrays.asList(MethodType.CARD), PspOutcome.OK, 0);
        PaymentService asyncSvc = new PaymentService(new InMemoryIdempotencyStore(), fraud,
                new FailoverRouter(Arrays.asList(asyncPsp)),
                asyncRepo, ledger, events, new RetryPolicy(2));
        PaymentRequest r8 = new PaymentRequest("idem-8", Money.of("500.00", "INR"), MethodType.CARD,
                new CardMethod("tok_w", "4444", "VISA", 4, 2030), "bob", "shop1", CaptureMode.AUTH);
        PaymentResult res8 = asyncSvc.initiate(r8);
        Transaction t8 = asyncRepo.find(res8.transactionId);
        System.out.println("  authorized, ref=" + t8.pspRef());
        // PSP captures out-of-band, then sends a webhook:
        asyncPsp.simulatePspCapture(t8.pspRef());
        asyncSvc.handleWebhook(new WebhookEvent("evt-1", t8.pspRef(), PspStatus.CAPTURED, "sig-ok"));
        System.out.println("  after webhook status: " + t8.status());
        // Replayed webhook is ignored (idempotent):
        asyncSvc.handleWebhook(new WebhookEvent("evt-1", t8.pspRef(), PspStatus.CAPTURED, "sig-ok"));
        // Reconciliation is a no-op now (already captured), but demonstrates the recovery path:
        asyncSvc.reconcile(res8.transactionId);
        System.out.println("  history: " + t8.history());

        System.out.println("== Done ==");
    }
}

/*
 * =============================================================================
 *  VENDING MACHINE — single-file LLD review / revision artifact
 *  (Read-and-revise only. Not meant to be compiled or run in CI; a `main` is
 *   included purely to illustrate the key scenarios for the reader.)
 * =============================================================================
 *
 *  CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 *  -----------------------------------------------------------
 *  Functional:
 *    1. Payment types: coins/notes only, or also card / UPI / contactless?
 *    2. Must we compute & return change? From a FINITE cash float or infinite?
 *    3. One product per transaction, or a multi-item cart?
 *    4. Can the user CANCEL mid-transaction and get a full refund?
 *    5. Exact-change-unavailable: refuse + refund, or dispense and owe? (refuse)
 *    6. Operator ops in scope: restock, refill change, collect cash?
 *  Non-functional / constraints:
 *    7. Single physical machine (in-process singleton) or a fleet w/ server?
 *    8. Concurrency: one front panel (serialized) or multiple input threads?
 *    9. Persistence across power cycle, or in-memory OK for the round?
 *   10. Which denominations are valid (fixed set)?
 *  Out of scope (confirm): hardware/motor control, networking, UI rendering,
 *    operator auth, multi-currency, dynamic pricing.
 *
 *  ANSWERS ASSUMED HERE: single machine; coins+notes now with a card strategy
 *  designed-in; finite change float that must be computed; cancel/refund yes;
 *  refuse sale if exact change can't be made; operator restock/collect yes;
 *  single-user panel but inventory & register made thread-safe defensively;
 *  money kept in integer smallest units (cents).
 *
 *  PATTERNS: State (Idle/HasMoney/Dispense/SoldOut) is the centerpiece;
 *  Strategy (PaymentStrategy, ChangeCalculator); Singleton (machine wiring);
 *  Flyweight (shared stateless state objects). SOLID: SRP per class,
 *  OCP via Strategy seams, DIP on State/Strategy abstractions.
 * =============================================================================
 */

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/* ------------------------------------------------------------------ *
 *  Value objects & enums
 * ------------------------------------------------------------------ */

/** Valid coins/notes, each carrying its integer value in cents. */
enum Denomination {
    PENNY(1), NICKEL(5), DIME(10), QUARTER(25), ONE(100), FIVE(500);

    final int value;
    Denomination(int value) { this.value = value; }

    /** Denominations ordered high -> low (handy for greedy change). */
    static List<Denomination> descending() {
        List<Denomination> all = new ArrayList<>(Arrays.asList(values()));
        all.sort((a, b) -> Integer.compare(b.value, a.value));
        return all;
    }
}

/** Immutable product. Price is in integer cents to avoid float money bugs. */
final class Product {
    final String code;
    final String name;
    final int price;

    Product(String code, String name, int price) {
        this.code = code;
        this.name = name;
        this.price = price;
    }

    @Override public String toString() {
        return name + " (" + code + ", " + price + "c)";
    }
}

/** What the machine hands back on a successful dispense. */
final class DispenseResult {
    final Product product;
    final Map<Denomination, Integer> change;

    DispenseResult(Product product, Map<Denomination, Integer> change) {
        this.product = product;
        this.change = change;
    }

    @Override public String toString() {
        return "Dispensed " + product + " | change=" + change;
    }
}

/** Raised for illegal operations in a given state (kept simple for the artifact). */
class VendingException extends RuntimeException {
    VendingException(String msg) { super(msg); }
}

/* ------------------------------------------------------------------ *
 *  Inventory (thread-safe)
 * ------------------------------------------------------------------ */

/** A single slot: the product it holds and how many remain. */
final class ItemSlot {
    final Product product;
    int quantity;
    ItemSlot(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }
}

/**
 * Manages stock. Single responsibility: stock lookup / decrement / restock.
 * Thread-safe so an operator restock can't race a customer dispense.
 */
final class Inventory {
    private final Map<String, ItemSlot> slots = new ConcurrentHashMap<>();

    void restock(String code, Product product, int qty) {
        slots.compute(code, (k, slot) -> {
            if (slot == null) return new ItemSlot(product, qty);
            synchronized (slot) { slot.quantity += qty; }
            return slot;
        });
    }

    Product getProduct(String code) {
        ItemSlot slot = slots.get(code);
        if (slot == null) throw new VendingException("Unknown product code: " + code);
        return slot.product;
    }

    boolean isSoldOut(String code) {
        ItemSlot slot = slots.get(code);
        return slot == null || slot.quantity <= 0;
    }

    /** Atomic check-then-act: verify stock and decrement under the slot lock. */
    void deduct(String code) {
        ItemSlot slot = slots.get(code);
        if (slot == null) throw new VendingException("Unknown product code: " + code);
        synchronized (slot) {
            if (slot.quantity <= 0) throw new VendingException("Sold out: " + code);
            slot.quantity--;
        }
    }

    boolean isEmpty() {
        for (ItemSlot s : slots.values()) {
            synchronized (s) { if (s.quantity > 0) return false; }
        }
        return true;
    }

    Map<String, Integer> snapshot() {
        Map<String, Integer> out = new TreeMap<>();
        slots.forEach((code, slot) -> {
            synchronized (slot) { out.put(code + ":" + slot.product.name, slot.quantity); }
        });
        return out;
    }
}

/* ------------------------------------------------------------------ *
 *  Change calculation (Strategy)
 * ------------------------------------------------------------------ */

/** Strategy: compute a denomination breakdown for `amount`, or null if impossible. */
interface ChangeCalculator {
    /** @return map of denomination->count summing to amount, or null if not makeable. */
    Map<Denomination, Integer> calculate(int amount, Map<Denomination, Integer> available);
}

/**
 * Greedy change: optimal for canonical denomination sets (US-like). For arbitrary
 * sets greedy can fail (e.g., {1,3,4} for 6) — swap in a DP calculator behind this
 * same interface (OCP). Works against the FINITE float in `available`.
 */
final class GreedyChangeCalculator implements ChangeCalculator {
    @Override
    public Map<Denomination, Integer> calculate(int amount, Map<Denomination, Integer> available) {
        if (amount < 0) return null;
        Map<Denomination, Integer> result = new EnumMap<>(Denomination.class);
        int remaining = amount;
        for (Denomination d : Denomination.descending()) {
            int have = available.getOrDefault(d, 0);
            if (have == 0 || d.value > remaining) continue;
            int use = Math.min(remaining / d.value, have);
            if (use > 0) {
                result.put(d, use);
                remaining -= use * d.value;
            }
        }
        return remaining == 0 ? result : null; // null => cannot make exact change
    }
}

/* ------------------------------------------------------------------ *
 *  Cash register (thread-safe) — holds the finite float
 * ------------------------------------------------------------------ */

final class CashRegister {
    private final Map<Denomination, Integer> floatBox = new EnumMap<>(Denomination.class);
    private final ChangeCalculator calculator;
    private final ReentrantLock lock = new ReentrantLock();

    CashRegister(ChangeCalculator calculator) {
        this.calculator = calculator;
        for (Denomination d : Denomination.values()) floatBox.put(d, 0);
    }

    /** Customer inserted a coin/note: it becomes part of the float. */
    void accept(Denomination d) {
        lock.lock();
        try { floatBox.merge(d, 1, Integer::sum); }
        finally { lock.unlock(); }
    }

    void refill(Denomination d, int count) {
        lock.lock();
        try { floatBox.merge(d, count, Integer::sum); }
        finally { lock.unlock(); }
    }

    boolean canMakeChange(int amount) {
        lock.lock();
        try { return calculator.calculate(amount, floatBox) != null; }
        finally { lock.unlock(); }
    }

    /** Compute AND physically remove the change denominations atomically. */
    Map<Denomination, Integer> dispenseChange(int amount) {
        lock.lock();
        try {
            Map<Denomination, Integer> change = calculator.calculate(amount, floatBox);
            if (change == null) throw new VendingException("Cannot make change for " + amount);
            change.forEach((d, n) -> floatBox.merge(d, -n, Integer::sum));
            return change;
        } finally { lock.unlock(); }
    }

    /** Refund: try to return the same value from the float (same mechanism as change). */
    Map<Denomination, Integer> refund(int amount) {
        return amount == 0 ? Collections.emptyMap() : dispenseChange(amount);
    }

    Map<Denomination, Integer> collectAll() {
        lock.lock();
        try {
            Map<Denomination, Integer> collected = new EnumMap<>(floatBox);
            for (Denomination d : Denomination.values()) floatBox.put(d, 0);
            return collected;
        } finally { lock.unlock(); }
    }
}

/* ------------------------------------------------------------------ *
 *  Payment strategy
 * ------------------------------------------------------------------ */

/** Strategy: how balance is added. Cash now; CardPaymentStrategy is an additive extension. */
interface PaymentStrategy {
    /** @return the amount (cents) successfully collected and added to balance. */
    int collect(VendingMachine vm, Object input);
}

/** Cash payment: each call adds one inserted denomination to the register & balance. */
final class CashPaymentStrategy implements PaymentStrategy {
    @Override
    public int collect(VendingMachine vm, Object input) {
        Denomination d = (Denomination) input;
        vm.getCashRegister().accept(d);
        return d.value;
    }
}

/* -- Extension sketch (designed-in, not wired into main):
final class CardPaymentStrategy implements PaymentStrategy {
    public int collect(VendingMachine vm, Object input) {
        int amount = (Integer) input;          // authorize + capture via gateway...
        return amount;                          // ...then report as balance. States untouched.
    }
}
*/

/* ------------------------------------------------------------------ *
 *  State pattern — the centerpiece
 * ------------------------------------------------------------------ */

/** Each state defines how the machine reacts to the four customer events. */
interface State {
    void selectProduct(VendingMachine vm, String code);
    void insertMoney(VendingMachine vm, Denomination d);
    DispenseResult dispense(VendingMachine vm);
    void cancel(VendingMachine vm);
    String name();
}

/** Waiting for a product selection. Inserting money is rejected until a product is chosen. */
final class IdleState implements State {
    @Override public void selectProduct(VendingMachine vm, String code) {
        if (vm.getInventory().isSoldOut(code)) {
            System.out.println("[Idle] " + code + " is sold out.");
            vm.setState(vm.getSoldOutState());
            return;
        }
        Product p = vm.getInventory().getProduct(code);
        vm.setSelectedProduct(p);
        System.out.println("[Idle] Selected " + p + ". Please insert money.");
        vm.setState(vm.getHasMoneyState());
    }
    @Override public void insertMoney(VendingMachine vm, Denomination d) {
        throw new VendingException("Select a product before inserting money.");
    }
    @Override public DispenseResult dispense(VendingMachine vm) {
        throw new VendingException("Select a product first.");
    }
    @Override public void cancel(VendingMachine vm) {
        System.out.println("[Idle] Nothing to cancel.");
    }
    @Override public String name() { return "Idle"; }
}

/** Product selected; collecting money. Transitions to Dispense once balance >= price. */
final class HasMoneyState implements State {
    @Override public void selectProduct(VendingMachine vm, String code) {
        throw new VendingException("Product already selected; finish or cancel first.");
    }
    @Override public void insertMoney(VendingMachine vm, Denomination d) {
        int added = vm.getPaymentStrategy().collect(vm, d);
        vm.addBalance(added);
        int price = vm.getSelectedProduct().price;
        System.out.println("[HasMoney] Balance=" + vm.getBalance() + "c / price=" + price + "c");
        if (vm.getBalance() >= price) {
            System.out.println("[HasMoney] Sufficient funds. Press dispense.");
            vm.setState(vm.getDispenseState());
        }
    }
    @Override public DispenseResult dispense(VendingMachine vm) {
        throw new VendingException("Insufficient balance; insert more money.");
    }
    @Override public void cancel(VendingMachine vm) {
        refundAndReset(vm);
    }
    static void refundAndReset(VendingMachine vm) {
        int bal = vm.getBalance();
        Map<Denomination, Integer> refund = vm.getCashRegister().refund(bal);
        System.out.println("[Cancel] Refunding " + bal + "c -> " + refund);
        vm.reset();
    }
    @Override public String name() { return "HasMoney"; }
}

/** Enough money in. On dispense: verify change feasibility, then vend + return change. */
final class DispenseState implements State {
    @Override public void selectProduct(VendingMachine vm, String code) {
        throw new VendingException("Mid-transaction; press dispense or cancel.");
    }
    @Override public void insertMoney(VendingMachine vm, Denomination d) {
        // Allow extra money; it just increases change owed (still must be makeable).
        int added = vm.getPaymentStrategy().collect(vm, d);
        vm.addBalance(added);
        System.out.println("[Dispense] Extra inserted; balance=" + vm.getBalance() + "c");
    }
    @Override public DispenseResult dispense(VendingMachine vm) {
        Product p = vm.getSelectedProduct();
        int changeDue = vm.getBalance() - p.price;

        // Re-check stock (could have changed) and change feasibility BEFORE committing.
        if (vm.getInventory().isSoldOut(p.code)) {
            HasMoneyState.refundAndReset(vm);
            vm.setState(vm.getSoldOutState());
            throw new VendingException("Just sold out; money refunded.");
        }
        if (!vm.getCashRegister().canMakeChange(changeDue)) {
            System.out.println("[Dispense] Exact change unavailable — refunding.");
            HasMoneyState.refundAndReset(vm);
            throw new VendingException("Exact change unavailable; refunded.");
        }

        // Commit: deduct stock first (atomic), then release change.
        vm.getInventory().deduct(p.code);
        Map<Denomination, Integer> change = vm.getCashRegister().dispenseChange(changeDue);
        DispenseResult result = new DispenseResult(p, change);
        System.out.println("[Dispense] " + result);
        vm.reset();
        return result;
    }
    @Override public void cancel(VendingMachine vm) {
        // Cancel still allowed pre-commit (we haven't deducted/released yet).
        HasMoneyState.refundAndReset(vm);
    }
    @Override public String name() { return "Dispense"; }
}

/** Selected slot (or whole machine) empty. Allows cancel/refund and recovery to Idle. */
final class SoldOutState implements State {
    @Override public void selectProduct(VendingMachine vm, String code) {
        if (!vm.getInventory().isSoldOut(code)) { // a different, stocked product
            vm.setState(vm.getIdleState());
            vm.getCurrentState().selectProduct(vm, code);
            return;
        }
        System.out.println("[SoldOut] " + code + " unavailable.");
    }
    @Override public void insertMoney(VendingMachine vm, Denomination d) {
        throw new VendingException("Sold out; cannot accept money.");
    }
    @Override public DispenseResult dispense(VendingMachine vm) {
        throw new VendingException("Sold out; nothing to dispense.");
    }
    @Override public void cancel(VendingMachine vm) {
        HasMoneyState.refundAndReset(vm);
        vm.setState(vm.getIdleState());
    }
    @Override public String name() { return "SoldOut"; }
}

/* ------------------------------------------------------------------ *
 *  VendingMachine — the State context
 * ------------------------------------------------------------------ */

final class VendingMachine {
    // Flyweight: one shared instance of each state.
    private final State idleState     = new IdleState();
    private final State hasMoneyState = new HasMoneyState();
    private final State dispenseState = new DispenseState();
    private final State soldOutState  = new SoldOutState();

    private State currentState;
    private final Inventory inventory;
    private final CashRegister cashRegister;
    private PaymentStrategy paymentStrategy;

    private Product selectedProduct;
    private int balance;            // cents inserted in the current transaction

    VendingMachine(Inventory inventory, CashRegister cashRegister, PaymentStrategy payment) {
        this.inventory = inventory;
        this.cashRegister = cashRegister;
        this.paymentStrategy = payment;
        this.currentState = idleState;
    }

    /* ---- Public customer API: delegate to current state ---- */
    public void selectProduct(String code) { currentState.selectProduct(this, code); }
    public void insertMoney(Denomination d) { currentState.insertMoney(this, d); }
    public DispenseResult dispense() { return currentState.dispense(this); }
    public void cancel() { currentState.cancel(this); }

    /* ---- Operator API ---- */
    public void restock(String code, Product p, int qty) {
        inventory.restock(code, p, qty);
        if (currentState == soldOutState && !inventory.isEmpty()) setState(idleState);
    }
    public void refillChange(Denomination d, int count) { cashRegister.refill(d, count); }
    public Map<Denomination, Integer> collectCash() { return cashRegister.collectAll(); }

    /* ---- State-machine bookkeeping ---- */
    void reset() {
        this.balance = 0;
        this.selectedProduct = null;
        setState(idleState);
    }
    void addBalance(int amount) { this.balance += amount; }
    void setState(State s) {
        System.out.println("    (state: " + currentState.name() + " -> " + s.name() + ")");
        this.currentState = s;
    }
    void setSelectedProduct(Product p) { this.selectedProduct = p; }

    /* ---- Accessors used by states ---- */
    State getCurrentState()   { return currentState; }
    State getIdleState()      { return idleState; }
    State getHasMoneyState()  { return hasMoneyState; }
    State getDispenseState()  { return dispenseState; }
    State getSoldOutState()   { return soldOutState; }
    Inventory getInventory()  { return inventory; }
    CashRegister getCashRegister() { return cashRegister; }
    PaymentStrategy getPaymentStrategy() { return paymentStrategy; }
    void setPaymentStrategy(PaymentStrategy p) { this.paymentStrategy = p; }
    Product getSelectedProduct() { return selectedProduct; }
    int getBalance() { return balance; }
}

/* ------------------------------------------------------------------ *
 *  Demonstration — illustrates the key scenarios for the reader.
 * ------------------------------------------------------------------ */

public class Solution {
    public static void main(String[] args) {
        Inventory inventory = new Inventory();
        inventory.restock("A1", new Product("A1", "Cola", 150), 2);
        inventory.restock("B2", new Product("B2", "Water", 100), 1);
        inventory.restock("C3", new Product("C3", "Chips", 125), 1);

        CashRegister register = new CashRegister(new GreedyChangeCalculator());
        // Seed the float so change can be made.
        register.refill(Denomination.QUARTER, 10);
        register.refill(Denomination.DIME, 10);
        register.refill(Denomination.NICKEL, 10);
        register.refill(Denomination.ONE, 5);

        VendingMachine vm = new VendingMachine(inventory, register, new CashPaymentStrategy());

        line("SCENARIO 1: happy path — buy Cola (150c) with 2x ONE, expect 50c change");
        vm.selectProduct("A1");
        vm.insertMoney(Denomination.ONE);
        vm.insertMoney(Denomination.ONE);     // balance 200 >= 150 -> Dispense
        System.out.println("Result: " + vm.dispense());

        line("SCENARIO 2: cancel mid-transaction — refund");
        vm.selectProduct("C3");
        vm.insertMoney(Denomination.QUARTER); // 25c
        vm.insertMoney(Denomination.QUARTER); // 50c
        vm.cancel();                          // refunds 50c

        line("SCENARIO 3: insufficient funds then complete");
        vm.selectProduct("B2");               // Water 100c
        vm.insertMoney(Denomination.QUARTER); // 25
        try { vm.dispense(); } catch (VendingException e) { System.out.println("Rejected: " + e.getMessage()); }
        vm.insertMoney(Denomination.QUARTER); // 50
        vm.insertMoney(Denomination.QUARTER); // 75
        vm.insertMoney(Denomination.ONE);     // 175 -> Dispense, change 75
        System.out.println("Result: " + vm.dispense());

        line("SCENARIO 4: sold out — B2 now empty");
        try { vm.selectProduct("B2"); } catch (VendingException e) { System.out.println(e.getMessage()); }
        vm.cancel();                          // recover to Idle

        line("SCENARIO 5: insert money before selecting (illegal)");
        try { vm.insertMoney(Denomination.QUARTER); }
        catch (VendingException e) { System.out.println("Rejected: " + e.getMessage()); }

        line("SCENARIO 6: exact-change-unavailable — drain small denoms first");
        register.collectAll();                       // empty the float
        register.refill(Denomination.FIVE, 5);       // only 500c notes -> can't make small change
        vm.selectProduct("A1");                      // Cola 150c (1 left)
        vm.insertMoney(Denomination.FIVE);           // 500c, change due 350c -> not makeable
        try { vm.dispense(); }
        catch (VendingException e) { System.out.println("Rejected: " + e.getMessage()); }

        line("OPERATOR: collect cash & inventory snapshot");
        System.out.println("Collected: " + vm.collectCash());
        System.out.println("Inventory: " + inventory.snapshot());
    }

    private static void line(String title) {
        System.out.println("\n================= " + title + " =================");
    }
}

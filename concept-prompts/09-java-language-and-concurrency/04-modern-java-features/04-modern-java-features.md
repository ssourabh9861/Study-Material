# Modern Java Features

> A definitive engineering-handbook chapter on the language features that define idiomatic Java from JDK 8 through JDK 21 (and the road to 25): **records, sealed types, pattern matching, switch expressions, text blocks, `var`, and the module system (JPMS)** — plus the LTS-by-LTS API additions that quietly reshaped how senior engineers write Java.

---

## 1. Overview & where it fits

### 1.1 What "Modern Java" means here

"Modern Java" is not a product or a library — it is the accumulated set of **language and standard-library changes** that landed mostly between **Java 8 (2014)** and **Java 21 (2023)**, and which together changed what *idiomatic, well-written Java* looks like. If you learned Java in the Java 6/7 era, the code a senior engineer writes today is recognizably different: fewer getters/setters, fewer `if (x instanceof Foo) { Foo f = (Foo) x; ... }` casts, far fewer visitor-pattern hierarchies, fewer string-concatenation walls for multi-line literals, and far less ceremony in general.

This chapter covers the **core language features** that drive that shift:

- **Records** — concise, immutable data carriers (the "this class is just data" declaration).
- **Sealed classes/interfaces** — controlled, exhaustively-known type hierarchies.
- **Pattern matching** — for `instanceof`, for `switch`, and **record patterns** (destructuring).
- **Switch expressions** — `switch` that produces a value, with no fall-through traps.
- **Text blocks** — multi-line string literals.
- **`var`** — local-variable type inference.
- **The module system (JPMS)** — strong encapsulation and explicit dependencies at the JAR/package level.
- **Useful API additions** across the LTS line (8 → 11 → 17 → 21).

### 1.2 The problem each one solves (one-liners)

| Feature | Pain it removes |
|---|---|
| Records | Boilerplate: constructor, `equals`/`hashCode`/`toString`, accessors for plain data |
| Sealed types | Open-ended hierarchies you can't reason about exhaustively |
| Pattern matching | Cast-after-`instanceof` ceremony; the "type test then extract" dance |
| Switch expressions | Fall-through bugs, the `break` tax, and `switch` as a statement-only construct |
| Text blocks | `"line1\n" + "line2\n"` walls for SQL/JSON/HTML |
| `var` | Redundant type names on the left when the right side already states them |
| JPMS | "JAR hell," weak encapsulation (anything `public` is reachable), implicit classpath dependencies |

### 1.3 When you reach for each (quick mental model)

- You have a class that is **purely data** with no behavior beyond accessors → **record**.
- You have a fixed, known-at-compile-time **family of subtypes** (e.g., an AST, a result type, a protocol message kind) → **sealed interface + records**, then **pattern-match** over it.
- You're branching on an object's **runtime type and shape** → **pattern matching in `switch`**, with **record patterns** to destructure.
- You need a `switch` that **returns a value** → **switch expression**.
- You're embedding **SQL/JSON/HTML/multi-line text** → **text block**.
- The right-hand-side type is **obvious and verbose** (`new HashMap<String, List<Integer>>()`) → **`var`**.
- You're building a **library or platform** that must enforce API boundaries and reliable configuration → consider **JPMS**.

### 1.4 The one-paragraph mental model

Modern Java is a deliberate move toward **data-oriented programming** as a first-class style alongside OOP. The "killer combo" is **records + sealed interfaces + pattern-matching switch**: you model your domain as an *algebraic data type* (a closed set of shapes), then process values by *destructuring and branching* on those shapes — with the compiler guaranteeing you handled every case (exhaustiveness). Around that core, `var`, text blocks, and switch expressions cut ceremony, while JPMS and the LTS API additions modernize the platform underneath. The net effect: **less code, more compiler-checked correctness, and intent that reads off the page.**

> **Term — Algebraic Data Type (ADT):** a type built from two combinators. A *product type* bundles several values together ("A **and** B" — a record is a product). A *sum type* (a.k.a. *tagged union*) is a choice among alternatives ("A **or** B **or** C" — a sealed interface with record implementations is a sum). The phrase comes from functional languages (Haskell, ML, Scala, Rust enums). Java now expresses both cleanly.

> **Term — LTS (Long-Term Support):** a JDK release that vendors support with security/bugfix updates for years (typically 5–8+), versus the 6-month "feature" releases that are superseded quickly. The LTS line is **8, 11, 17, 21, 25** (25 ships Sept 2025). Most production shops standardize on an LTS.

> **Term — Preview feature:** a fully-specified, fully-implemented language feature that ships *disabled by default* so the community can road-test it before it's permanent. You opt in with `--enable-preview` (and `--release N` at compile time). The API/syntax may change in the next release. Many features below (records, sealed, patterns, switch expressions, text blocks) went through 1–3 preview rounds before becoming permanent.

---

## 2. Foundations from first principles

This section assumes you know Java the language (classes, generics, interfaces, the classpath) but are new to *these specific features*. We build each from zero.

### 2.1 The "data class" problem that records solve

Before records, a class holding two values looked like this:

```java
// Pre-records: a "value object" the hard way
public final class Point {
    private final int x;
    private final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() { return x; }
    public int y() { return y; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return x == p.x && y == p.y;
    }

    @Override
    public int hashCode() { return Objects.hash(x, y); }

    @Override
    public String toString() { return "Point[x=" + x + ", y=" + y + "]"; }
}
```

That is ~30 lines to say "a Point is an x and a y." Every field appears **four to six times** (declaration, constructor parameter, constructor assignment, accessor, `equals`, `hashCode`, `toString`). This is mechanical, error-prone (people forget to update `equals` when adding a field), and noise that hides the intent.

> **Term — value object / data carrier:** an object whose identity is its *contents*, not its memory address. Two value objects with equal fields should be `equals`. Money, coordinates, a DTO, a database row — all data carriers.

> **Term — immutability:** an object whose state cannot change after construction. Immutable objects are inherently thread-safe (no writes to coordinate), safe to cache and share, and safe as map keys. Records lean hard into immutability.

### 2.2 Records: the basics

A **record** declares the same `Point` in one line:

```java
public record Point(int x, int y) {}
```

The compiler generates, for `record Point(int x, int y)`:

- **A `private final` field** for each component (`x`, `y`).
- **A canonical constructor** taking all components in declaration order.
- **A public accessor** per component, named exactly after it: `x()`, `y()` (note: *not* `getX()`).
- **`equals(Object)`** comparing all components.
- **`hashCode()`** derived from all components.
- **`toString()`** of the form `Point[x=1, y=2]`.

> **Term — record component:** one of the named values in the record header (`x`, `y`). It is the single source of truth: the field, accessor, constructor parameter, and `equals`/`hashCode`/`toString` are all derived from it.

> **Term — canonical constructor:** the constructor whose parameters exactly match the record components, in order. It's the "real" constructor; every other constructor must ultimately delegate to it.

Records are implicitly `final` (you cannot extend a record) and implicitly extend `java.lang.Record` (you cannot make a record extend another class). They **can** implement interfaces.

### 2.3 The "open hierarchy" problem that sealed types solve

In classic OO, an `interface` or non-`final` class can be implemented/extended **by anyone, anywhere** — including code you've never seen. That's great for extensibility, but terrible when you want to *reason about all the cases*. Consider:

```java
interface Shape {}
final class Circle implements Shape { ... }
final class Square implements Shape { ... }
```

If you `switch` over a `Shape`, the compiler can't know `Circle` and `Square` are the *only* shapes — someone could add `Triangle` tomorrow in another module. So the compiler forces you to write a `default` branch even when you've covered every shape you know about, and it *can't warn you* when a new shape is added and you forget to handle it.

> **Term — exhaustiveness:** the compile-time guarantee that a `switch` (or other branching) covers every possible case. With an open hierarchy, exhaustiveness is impossible to verify. With a sealed (closed) hierarchy, the compiler can verify it — and *fail your build* if you miss a case.

### 2.4 Sealed types: the basics

A **sealed** type explicitly lists its permitted direct subtypes:

```java
public sealed interface Shape permits Circle, Square, Triangle {}

public record Circle(double radius) implements Shape {}
public record Square(double side) implements Shape {}
public record Triangle(double base, double height) implements Shape {}
```

Now the set of `Shape`s is **closed and known**. Every permitted subtype must itself declare its "openness" using one of three modifiers:

- `final` — cannot be extended further (records are implicitly `final`, so they satisfy this for free).
- `sealed` — continues the controlled hierarchy with its own `permits`.
- `non-sealed` — deliberately reopens this branch to arbitrary extension.

> **Term — `non-sealed`:** the only hyphenated keyword in Java. It marks a permitted subtype as an escape hatch: "this branch of the hierarchy is open again." Useful when you want a mostly-closed hierarchy with one extensible point (e.g., a framework's built-in node types plus a `non-sealed CustomNode`).

### 2.5 The "instanceof then cast" problem

Before pattern matching, type-narrowing was two steps:

```java
Object obj = ...;
if (obj instanceof String) {        // 1) test the type
    String s = (String) obj;        // 2) cast (redundant — we just proved it)
    System.out.println(s.length());
}
```

The cast is pure ceremony — you already proved the type. Worse, the variable name and type are repeated, and the cast can drift out of sync from the test (`instanceof String` but cast to `CharSequence`).

### 2.6 Pattern matching for `instanceof`: the basics

A **type pattern** binds a variable in the same breath as the test:

```java
Object obj = ...;
if (obj instanceof String s) {   // test + bind in one step
    System.out.println(s.length());  // 's' is in scope and already a String
}
```

> **Term — pattern:** a combination of a *type test* and a *binding* (and optionally a *deconstruction*). The simplest is a **type pattern** (`String s`). A **record pattern** (`Point(int x, int y)`) additionally destructures. A pattern *matches* a value if the value fits the shape, and on a match it binds the named variables.

> **Term — pattern variable / binding variable:** the variable introduced by a pattern (`s`, `x`, `y`). Its scope is *flow-sensitive*: it's in scope exactly where the compiler can prove the pattern matched (e.g., inside the `if` body, or after `if (!(obj instanceof String s)) return;` because the negation guarantees a match below).

### 2.7 The `switch` legacy and switch expressions

Classic `switch` is a **statement** with C-style semantics: it falls through cases unless you `break`, it can't produce a value directly, and forgetting `break` is a perennial bug.

```java
// Classic switch statement — fall-through, no value, break tax
int numLetters;
switch (day) {
    case MONDAY:
    case FRIDAY:
    case SUNDAY:
        numLetters = 6;
        break;            // forget this → fall-through bug
    case TUESDAY:
        numLetters = 7;
        break;
    default:
        throw new IllegalStateException();
}
```

A **switch expression** uses arrow syntax (`->`), produces a value, has **no fall-through**, and the compiler checks **exhaustiveness**:

```java
// Switch expression — value-producing, no fall-through, arms can group labels
int numLetters = switch (day) {
    case MONDAY, FRIDAY, SUNDAY -> 6;     // multiple labels, one arm
    case TUESDAY                -> 7;
    case THURSDAY, SATURDAY     -> 8;
    case WEDNESDAY              -> 9;
};                                         // note the semicolon: it's an expression
// No default needed if all enum constants are covered (exhaustive)
```

> **Term — arrow vs colon labels:** `case X ->` is the new arrow form (one arm, no fall-through, expression or block on the right). `case X:` is the classic colon form (fall-through, statements). You can use either, but **don't mix them in one `switch`** — the compiler forbids it.

> **Term — `yield`:** inside an arrow arm that needs a *block* of statements, `yield value;` produces the switch expression's value (the way `return` produces a method's value). It's also used in colon-form switch expressions.

### 2.8 Text blocks: the basics

A **text block** is a multi-line string literal delimited by `"""`:

```java
String json = """
    {
      "name": "Ada",
      "active": true
    }
    """;
```

No `\n`, no `+` concatenation, no escaping of the inner `"`. Indentation is handled by a documented algorithm (Section 4.5).

### 2.9 `var`: the basics

`var` lets the compiler **infer** a local variable's type from its initializer:

```java
var list = new ArrayList<String>();      // inferred: ArrayList<String>
var entry = Map.entry("k", 42);          // inferred: Map.Entry<String,Integer>
var path = Path.of("/etc/hosts");        // inferred: Path
```

`var` is **not** dynamic typing — the variable still has a single static type, fixed at compile time. It's purely **local type inference**: the type is computed from the right-hand side and the variable behaves identically to a fully-typed declaration.

> **Term — type inference:** the compiler deducing a type you didn't write. Java already had it for generics (`List<String> x = new ArrayList<>()` — the diamond `<>`) and lambdas. `var` extends it to the *whole* local variable type.

### 2.10 JPMS: the basics

Before Java 9, the unit of deployment was the **JAR on the classpath**, and the unit of encapsulation was the **`public` keyword** — anything `public` was reachable by anyone who had the JAR. There was no way to say "this package is internal; don't touch it." This produced two chronic problems:

> **Term — classpath:** the flat, ordered list of JARs/directories the JVM searches for classes. It has no notion of "this JAR needs that JAR" — dependencies are implicit and discovered only at runtime when a class is missing (`NoClassDefFoundError`) or duplicated.

> **Term — JAR hell / classpath hell:** the family of failures from the flat classpath: missing transitive dependencies, two versions of the same library both present (the first one on the path wins, silently), and split packages (the same package spread across multiple JARs).

The **Java Platform Module System (JPMS)**, introduced in Java 9 ("Project Jigsaw"), adds a layer above packages: the **module**. A module is a named, self-describing unit that declares (1) what packages it **exports** (its public API) and (2) what other modules it **requires** (its dependencies), via a `module-info.java` file:

```java
module com.acme.billing {
    requires com.acme.core;          // I depend on this module
    requires java.sql;               // and this platform module
    exports com.acme.billing.api;    // others may use this package
    // everything else (com.acme.billing.internal.*) is strongly encapsulated
}
```

> **Term — strong encapsulation:** even `public` classes in a *non-exported* package are inaccessible from other modules — at compile time *and* at runtime (reflection included, unless explicitly opened). This is the headline guarantee JPMS adds over the classpath.

> **Term — module path vs classpath:** modules live on the `--module-path` (`-p`); legacy JARs live on the `--class-path` (`-cp`). They coexist via the "unnamed module" and "automatic modules" (Section 3.6).

---

## 3. How it works internally

This is the heart of the chapter. We trace what the compiler and JVM actually do for each feature — bytecode, lifecycle, state, and corner semantics.

### 3.1 Records — what the compiler generates and the JVM stores

When you write `record Point(int x, int y) {}`, `javac` emits a class that:

1. **Extends `java.lang.Record`** (an abstract class added in Java 16). `java.lang.Record` itself declares `equals`, `hashCode`, and `toString` as abstract — but the record's own generated overrides satisfy them.
2. **Declares two `private final` fields**, `x` and `y`.
3. **Emits a canonical constructor** that assigns the fields.
4. **Emits accessor methods** `x()` and `y()`.
5. **Emits `equals`, `hashCode`, `toString`** — but here's the interesting part: it does **not** inline field-by-field logic into bytecode the old way. Instead it generates them as `invokedynamic` calls to a bootstrap method, `java.lang.runtime.ObjectMethods.bootstrap`.

> **Term — `invokedynamic` (indy):** a JVM bytecode instruction that defers *how a call is linked* to a one-time "bootstrap method" run on first execution. It's the mechanism behind lambdas, string concatenation (Java 9+), and record `equals`/`hashCode`/`toString`. The benefit: the actual implementation can change without changing the emitted bytecode, and the JVM can optimize it as a `MethodHandle` chain.

> **Term — `MethodHandle`:** a typed, directly-executable reference to a method/field/constructor (from `java.lang.invoke`). Think of it as a faster, JVM-native function pointer. Records' generated methods compose `MethodHandle`s that read each component.

6. **Emits a `Record` attribute** in the class file listing the components (name + type + generic signature). This is what reflection (`Class.getRecordComponents()`) reads.

**Key runtime properties:**

- The fields are `final`, so a record instance is **shallowly immutable** — the references can't be reassigned. (Mutable *contents*, e.g., a `List` component, can still be mutated unless you defensively copy — see 6.x.)
- Records are valid as **HashMap keys** and in **HashSet** immediately, because `equals`/`hashCode` are generated.
- Records serialize specially: see 3.1.4.

#### 3.1.1 Constructor flavors

```java
public record Range(int lo, int hi) {

    // (1) Compact canonical constructor: validate/normalize WITHOUT re-listing params
    public Range {                       // no parameter list, no body assignment
        if (lo > hi) throw new IllegalArgumentException("lo > hi");
        // 'lo' and 'hi' here are the constructor parameters;
        // the compiler assigns them to the fields AFTER this block runs.
    }

    // (2) Additional (non-canonical) constructor: MUST delegate to canonical
    public Range(int single) {
        this(single, single);            // delegate
    }
}
```

The **compact canonical constructor** is record-specific magic: you write only the body, the parameters are implicit (named after the components), and **field assignment is auto-appended by the compiler after your code**. So you can mutate the *parameters* (normalize them) before they're assigned, but you cannot write `this.lo = ...` inside a compact constructor.

You may also write a **full canonical constructor** (with an explicit parameter list and explicit assignments), but you can't have both forms.

#### 3.1.2 Overriding accessors and adding members

You can override an accessor or add static factories, static fields, and instance methods (but **no additional instance fields** — the state is exactly the components):

```java
public record Temperature(double celsius) {
    public double fahrenheit() { return celsius * 9 / 5 + 32; }  // derived, OK
    public static Temperature freezing() { return new Temperature(0); } // static factory
    // private int cache;   // COMPILE ERROR: instance fields not allowed
    public static int SCALE = 1; // static fields ARE allowed
}
```

#### 3.1.3 Local records

Since Java 16 you can declare a record **inside a method** — handy for ad-hoc tuples in a stream pipeline:

```java
List<String> top = scores.entrySet().stream()
    .map(e -> {
        record Scored(String name, int score) {}   // local record
        return new Scored(e.getKey(), e.getValue());
    })
    .sorted(Comparator.comparingInt(Scored::score).reversed())
    .map(Scored::name)
    .toList();
```

#### 3.1.4 Record serialization (subtle and important)

Records use a **dedicated, secure serialization path**. On deserialization, the JVM does **not** bypass the constructor (as it does for normal `Serializable` classes via `sun.misc.Unsafe`-style allocation). Instead it reads the component values from the stream and **invokes the canonical constructor**. Consequences:

- Your **invariants are enforced** on deserialization (validation in the compact constructor runs) — this closes a long-standing security hole where malicious streams could create objects in illegal states.
- `readObject`/`writeObject`/`readResolve` customization is *mostly* ignored for records (the model is fixed: serialize components, deserialize via canonical constructor). `readResolve`/`writeReplace` do still apply at the object-substitution level.

### 3.2 Sealed types — how the compiler enforces the contract

`sealed`/`permits` is enforced at **two times**:

1. **Compile time:** every class in `permits` must (a) exist, (b) be in the *same module* (or, for the unnamed module, the *same package*), and (c) directly extend/implement the sealed type. Conversely, a class extending a sealed type that is *not* in its `permits` list is a compile error.
2. **Run time:** the sealed class file carries a **`PermittedSubclasses` attribute** (added in Java 17). The JVM verifies at class-load that any class claiming to extend/implement a sealed type is in that list — so you can't defeat sealing by hand-crafting bytecode or loading a sneaky class.

> **Term — `PermittedSubclasses` attribute:** a class-file attribute listing the allowed direct subtypes of a sealed type. It's the runtime half of the sealing contract.

**Inference of `permits`:** if all permitted subtypes are declared in the *same source file*, you may omit `permits` entirely — the compiler infers it:

```java
// In one .java file: permits is inferred from the same-file subtypes
public sealed interface Expr {}
record Num(double value) implements Expr {}
record Add(Expr left, Expr right) implements Expr {}
record Mul(Expr left, Expr right) implements Expr {}
```

**Sealed + exhaustive switch:** because the compiler knows the full set of permitted subtypes, a `switch` covering all of them is **exhaustive without a `default`**. If you later add `Sub` to the sealed interface, *every* exhaustive switch that didn't handle `Sub` now **fails to compile** — a compiler-enforced "go fix all the call sites" feature. This is the single most valuable property of sealing.

### 3.3 Pattern matching — flow scoping and the matching algorithm

#### 3.3.1 `instanceof` patterns and flow scoping

The binding variable's scope is determined by **definite assignment / flow analysis**: it's in scope precisely where the compiler can prove the pattern matched.

```java
// Binding flows past a guard clause due to the early return
static double area(Object o) {
    if (!(o instanceof Shape s)) {      // if NOT a Shape, return
        return 0;
    }
    // here 's' IS in scope — control only reaches here when the match succeeded
    return s.area();
}

// Binding flows through && (proven on the right of &&)
if (o instanceof String str && str.length() > 3) { ... }  // OK
// but NOT through || (not proven on the right of ||)
// if (o instanceof String str || str.length() > 3) { ... }  // COMPILE ERROR
```

#### 3.3.2 Switch patterns and the dispatch mechanism

A `switch` with type/record patterns compiles to an **`invokedynamic`** call to `java.lang.runtime.SwitchBootstraps.typeSwitch`, which builds an efficient dispatcher (essentially a sequence of `instanceof`-style checks, but linked once and JIT-friendly). The switch evaluates labels **top to bottom**; the **first matching pattern wins**. This top-to-bottom semantics matters: a more general pattern must come *after* more specific ones, or it dominates them.

> **Term — dominance:** label B *dominates* label A if any value matching A would already match B. If a dominating label appears first, A is unreachable. The compiler **rejects** dominated labels as an error (e.g., `case Object o ->` before `case String s ->`).

#### 3.3.3 Record patterns (destructuring)

A **record pattern** matches a record *and* destructures its components into sub-patterns:

```java
sealed interface Shape permits Circle, Rectangle {}
record Point(double x, double y) {}
record Circle(Point center, double radius) implements Shape {}
record Rectangle(Point topLeft, Point bottomRight) implements Shape {}

static String describe(Shape s) {
    return switch (s) {
        // Nested record pattern: destructure Circle, then its Point
        case Circle(Point(var cx, var cy), var r) ->
            "Circle at (%f,%f) r=%f".formatted(cx, cy, r);
        case Rectangle(Point(var x1, var y1), Point(var x2, var y2)) ->
            "Rect %fx%f".formatted(x2 - x1, y2 - y1);
    };  // exhaustive: no default needed (sealed)
}
```

Record patterns **nest arbitrarily** and use **type inference** (`var`) per component. The match succeeds only if the value is the record type *and* each nested sub-pattern matches.

#### 3.3.4 Guards (`when`)

A **guarded pattern** adds a boolean condition with `when`:

```java
static String classify(Object o) {
    return switch (o) {
        case Integer i when i < 0  -> "negative int";
        case Integer i            -> "non-negative int";
        case String s when s.isBlank() -> "blank string";
        case String s             -> "string: " + s;
        case null                 -> "null!";        // explicit null label
        default                   -> "other";
    };
}
```

> **Term — guard / `when` clause:** an extra boolean test attached to a case label. A guarded label matches only if the pattern matches **and** the guard is true. Order matters: put the guarded, more-specific arm before the unguarded fallback.

#### 3.3.5 `null` handling in switch (a real behavior change)

Classic `switch` throws `NullPointerException` if the selector is `null`. A pattern switch can include an explicit `case null` (optionally `case null, default ->`). **If you don't write `case null`, a pattern switch still throws NPE on null** — the behavior is opt-in, preserving back-compat while letting you handle null in one place.

### 3.4 Switch expressions — internals and exhaustiveness

A switch *expression* must be **total**: every possible input is covered, either by enumerating all enum constants / all sealed subtypes, or by a `default`. The compiler inserts a synthetic `default` that throws `MatchException` (Java 21+) or `IncompatibleClassChangeError`-style behavior in older versions when, e.g., an enum gains a constant at runtime that wasn't known at compile time (a separately-compiled enum). The arrow form compiles to ordinary jump tables (`tableswitch`/`lookupswitch`) for `int`/`enum`/`String`, and to the `typeSwitch` indy bootstrap for pattern switches.

> **Term — `MatchException`:** thrown (Java 21+) when a switch expression that *should* be exhaustive encounters a value it can't match — e.g., a sealed hierarchy changed after separate compilation, or a record accessor throws during deconstruction. It signals a "this shouldn't happen" mismatch rather than a programming bug at the call site.

### 3.5 Text blocks — the indentation & escape algorithm

Text-block processing happens at **compile time** in three documented steps; the resulting `String` is an ordinary constant:

1. **Line-terminator normalization:** all line terminators (`\r\n`, `\r`) become `\n`.
2. **Incidental whitespace removal:** the compiler finds the **minimum indentation** across all non-blank lines *and* the closing `"""` delimiter line, then strips that amount from every line. This lets you indent the block to match surrounding code without that indentation leaking into the string.
3. **Escape processing:** standard escapes (`\n`, `\t`, `\"`) plus two text-block-only escapes:
   - `\` at end of line — **suppresses the newline** (line continuation).
   - `\s` — a **single space** that is *not* stripped as incidental whitespace (use it to preserve trailing spaces).

```java
String html = """
        <html>
            <body>
                <p>Hi</p>
            </body>
        </html>
        """;
// The 8 spaces before <html> (and the closing """) are incidental → stripped.
// The relative indentation of <body>/<p> is preserved.
```

The opening `"""` **must** be followed by a line terminator (you can't put content on the same line). The closing `"""` position controls trailing-whitespace stripping: put it at the left margin to strip all common indentation; put it indented to keep some.

### 3.6 JPMS — the resolution lifecycle and module graph

The module system runs a **resolution** algorithm at startup (or at compile/link time):

1. **Root modules** are identified (e.g., the main module, or those named via `--add-modules`).
2. **Transitive closure:** for each module, its `requires` are resolved against the module path and the JDK's own modules, recursively, producing the **module graph**.
3. **Readability:** module A *reads* module B if A `requires` B (directly or via `requires transitive`). A class in A can access an exported type of B only if A reads B.
4. **Accessibility check:** a `public` type in package P of module B is accessible to A only if (a) A reads B, and (b) B `exports P` (or `exports P to A` for a qualified export).
5. **Consistency checks:** no two modules may export the *same package* (no split packages), each module name is unique, and all `requires` must be satisfiable — otherwise startup fails with a clear error (vs. the classpath's lazy `NoClassDefFoundError`).

> **Term — readability vs accessibility:** *readability* is module-to-module ("A can see B at all"). *Accessibility* is type-level ("A can use this specific `public` type"). You need both: read the module **and** the package must be exported.

> **Term — `requires transitive`:** "implied readability." If A `requires transitive B`, then anyone who `requires A` automatically reads B too. Use it when A's public API *exposes* B's types (e.g., a method returns a `B.Thing`), so consumers don't have to redundantly require B.

> **Term — `opens` / `open module`:** `exports` grants compile-time + reflective access to a package's `public` API. `opens` grants **deep reflective** access (including private members) at *runtime only* — needed by frameworks like Spring, Hibernate, Jackson that reflect over your classes. `open module M {}` opens everything.

> **Term — `provides ... with` / `uses`:** JPMS's built-in service-loader wiring. A module declares `provides com.acme.Plugin with com.acme.impl.MyPlugin;` and consumers declare `uses com.acme.Plugin;`, then load via `ServiceLoader`. This is the module-aware replacement for `META-INF/services` files.

**Three kinds of modules at runtime:**

| Kind | What it is | Behavior |
|---|---|---|
| **Named (explicit) module** | A JAR with `module-info.class` on the module path | Strong encapsulation; explicit `requires`/`exports` |
| **Automatic module** | A *plain* JAR (no `module-info`) placed on the **module path** | Gets an auto-derived name (from `Automatic-Module-Name` manifest entry or the filename); **reads all other modules** and **exports all its packages** — a migration bridge |
| **Unnamed module** | Everything on the **classpath** (`-cp`) | Reads everything; can't be `requires`d by name; the legacy world |

> **Term — automatic module:** a non-modular JAR on the module path, treated as a module so modular code can `requires` it during migration. Its name comes from the `Automatic-Module-Name:` manifest header (stable, recommended) or, failing that, a name derived from the filename (fragile — avoid relying on it).

> **Term — `jlink`:** a JDK tool that assembles a **custom runtime image** containing *only* the modules your app needs (your modules + the JDK modules they transitively require). The result is a smaller, self-contained runtime — popular for containers. Requires fully-modularized dependencies (or automatic modules) to be useful.

> **Term — `jdeps`:** a static-analysis tool that reports a JAR/class's dependencies, flags uses of internal JDK APIs (`sun.*`, `jdk.internal.*`), and can **generate a `module-info.java` skeleton** to start modularizing.

---

## 4. The complete toolkit

### 4.1 Records — surface area

| Element | What it is | Notes / defaults |
|---|---|---|
| `record Name(T comp, ...) {}` | Declaration | Implicitly `final`; extends `java.lang.Record` |
| Component accessor `comp()` | Generated getter | Named after component (no `get` prefix); can be overridden |
| Canonical constructor | All-components ctor | Auto-generated if not written |
| Compact canonical ctor `Name { ... }` | Validation/normalization | No param list; fields auto-assigned after body |
| Additional ctor | Convenience ctor | Must `this(...)` to canonical |
| `Class.isRecord()` | Reflection | `true` for records |
| `Class.getRecordComponents()` | Reflection | Returns `RecordComponent[]` |
| `RecordComponent` | Reflective component | `.getName()`, `.getType()`, `.getAccessor()`, `.getGenericType()` |

Allowed inside a record: static fields, static methods, instance methods, static/instance initializers? — **static initializers yes, instance initializers no**, nested types, implementing interfaces. **Not** allowed: additional instance fields, `extends`, native methods.

### 4.2 Sealed types — surface area

| Element | What it is | Constraint |
|---|---|---|
| `sealed ... permits A, B, C` | Declares a sealed type | Permitted subtypes in same module (or same package if unnamed) |
| `permits` omitted | Inference | Allowed only if all subtypes in the same compilation unit (file) |
| `final` subtype | Closes the branch | Records are implicitly `final` |
| `sealed` subtype | Continues sealing | Needs its own `permits` |
| `non-sealed` subtype | Reopens the branch | Arbitrary further extension allowed |
| `PermittedSubclasses` attr | Class-file metadata | Runtime-enforced |
| `Class.isSealed()`, `Class.getPermittedSubclasses()` | Reflection | Introspect the hierarchy |

### 4.3 Pattern matching — surface area

| Pattern | Syntax | Where |
|---|---|---|
| Type pattern | `Type t` | `instanceof`, `switch` |
| Record pattern | `Rec(P1, P2, ...)` | `instanceof`, `switch`; nests; `var` per component |
| Guarded label | `case P when cond ->` | `switch` only |
| `null` label | `case null ->` / `case null, default ->` | `switch` |
| `default` | `default ->` | `switch` |
| Var in record pattern | `Point(var x, var y)` | inferred component types |

Notes: type patterns in `switch` arrived in Java 21 (after 17/20 previews). Record patterns: Java 21 (previewed 19/20). Generic record patterns support inference (`Box(var v)` infers the type argument). **Named type patterns** with `var` for the *whole* value (e.g. `case Point p`) bind the value; you can combine: `case Point p when p.x() > 0`.

### 4.4 Switch expressions — surface area

| Element | Meaning |
|---|---|
| `case A, B ->` | Multiple labels → one arm |
| `-> expr` | Arrow arm yields `expr` |
| `-> { ... yield v; }` | Block arm; `yield` produces the value |
| `yield v;` (colon form) | Produce value from a `case L:` arm in a switch expression |
| Exhaustiveness | Required for switch *expressions* and pattern switches |
| `MatchException` | Thrown on impossible-match (Java 21+) |

### 4.5 Text blocks — surface area

| Element | Meaning |
|---|---|
| `"""` ... `"""` | Delimiters; opening `"""` must be followed by newline |
| Incidental whitespace | Min indentation (incl. closing delimiter line) is stripped |
| `\` (line-end) | Suppress the newline (continuation) |
| `\s` | A space that survives incidental-whitespace stripping |
| `String::stripIndent` | Apply the same algorithm programmatically |
| `String::translateEscapes` | Apply escape processing programmatically |
| `String::formatted(args)` | `String.format` as an instance method — pairs nicely with text blocks |

### 4.6 `var` — rules

| Rule | Detail |
|---|---|
| Scope | **Local variables only** (incl. `for`, try-with-resources, enhanced-for, since 10/11) |
| Lambda params | `var` allowed in lambda parameters (Java 11) — useful to attach annotations |
| Requires initializer | `var x;` is illegal; the RHS must determine the type |
| No `null`/no poly | `var x = null;` illegal; can't infer from `{...}` array initializer or lambda/method-ref alone |
| Not for fields/params/returns | By design — keeps inference local and APIs explicit |
| Not a keyword | `var` is a *reserved type name*; you can still have a variable/method named `var` (but not a type) |

### 4.7 JPMS — directive & tooling reference

| Directive (in `module-info.java`) | Meaning |
|---|---|
| `requires M;` | Depend on module M |
| `requires transitive M;` | Depend on M and imply readability to my consumers |
| `requires static M;` | Compile-time-only dependency (optional at runtime) |
| `exports P;` | Export package P's public API |
| `exports P to M1, M2;` | Qualified export (only those modules) |
| `opens P;` | Open P for deep reflection at runtime |
| `opens P to M;` | Qualified open |
| `open module M {}` | Open all packages for reflection |
| `uses S;` | Consume service type S via `ServiceLoader` |
| `provides S with Impl;` | Provide an implementation of S |

| Command / flag | Purpose |
|---|---|
| `--module-path` / `-p` | The module path |
| `--module` / `-m M/MainClass` | Launch a module's main class |
| `--add-modules M` | Add root modules (e.g., to include otherwise-unused ones) |
| `--add-reads M=N` | Make M read N at runtime (escape hatch) |
| `--add-exports M/P=N` | Export P from M to N at runtime (escape hatch) |
| `--add-opens M/P=N` | Open P from M to N at runtime (the famous reflection fix) |
| `--describe-module` / `jar -d` | Show a module's descriptor |
| `jdeps` | Dependency analysis; `--generate-module-info` |
| `jlink` | Build a custom runtime image |
| `jmod` | Work with `.jmod` files (JDK modules) |

---

## 5. Code examples by use case

### 5.1 A typed result without exceptions (sealed + records + switch)

A clean alternative to throwing/checked exceptions for expected outcomes — model success and failure as data.

```java
// A small, closed Result ADT
public sealed interface Result<T> permits Result.Ok, Result.Err {
    record Ok<T>(T value) implements Result<T> {}
    record Err<T>(String message, Throwable cause) implements Result<T> {}

    static <T> Result<T> ok(T v) { return new Ok<>(v); }
    static <T> Result<T> err(String msg, Throwable c) { return new Err<>(msg, c); }
}

// Usage: exhaustive handling, no nulls, no try/catch sprawl
static int parsePort(String raw) {
    Result<Integer> r = tryParse(raw);
    return switch (r) {
        case Result.Ok<Integer>(Integer port) when port in_range(port) -> port; // see note
        case Result.Ok<Integer>(Integer port) -> throw new IllegalArgumentException("out of range: " + port);
        case Result.Err<Integer>(String msg, var cause) -> {
            log.warn("parse failed: {}", msg, cause);
            yield 8080; // default
        }
    };
}
```

> Note: Java has no `in_range`/`in` operator — use a `when` guard such as `when port >= 0 && port <= 65535`. The record pattern `Ok<Integer>(Integer port)` destructures the success value directly.

### 5.2 An interpreter / expression evaluator (the ADT sweet spot)

```java
sealed interface Expr permits Num, Add, Mul, Neg {}
record Num(double value) implements Expr {}
record Add(Expr left, Expr right) implements Expr {}
record Mul(Expr left, Expr right) implements Expr {}
record Neg(Expr operand) implements Expr {}

// No visitor pattern, no instanceof ladders — destructure and recurse.
static double eval(Expr e) {
    return switch (e) {
        case Num(double v)        -> v;
        case Add(var l, var r)    -> eval(l) + eval(r);
        case Mul(var l, var r)    -> eval(l) * eval(r);
        case Neg(var x)           -> -eval(x);
    };  // exhaustive: add a new Expr subtype and THIS won't compile until handled
}

// eval(new Add(new Num(2), new Mul(new Num(3), new Neg(new Num(4)))))  ==  2 + 3*(-4) == -10
```

This is the canonical demonstration of *data-oriented programming*: the shape of the data drives the code, and the compiler enforces completeness.

### 5.3 HTTP/JSON DTOs with validation (records + compact ctor)

```java
public record CreateUser(String email, String displayName, int age) {
    // Compact canonical constructor validates and normalizes
    public CreateUser {
        Objects.requireNonNull(email, "email");
        email = email.trim().toLowerCase();          // normalize the parameter
        if (!email.contains("@")) throw new IllegalArgumentException("bad email");
        if (age < 13) throw new IllegalArgumentException("min age 13");
    }
}
// Jackson (2.12+) deserializes records via the canonical constructor automatically.
// Bean Validation works on record components too (annotate components).
```

### 5.4 Defensive copying for a record with a mutable component

Records are only *shallowly* immutable. If a component is mutable, copy it in the compact constructor and on the way out.

```java
public record Order(String id, List<String> items) {
    public Order {
        items = List.copyOf(items);   // immutable defensive copy on the way in
    }
    // accessor already returns the immutable copy; no extra work needed here
}
```

> `List.copyOf` (Java 10) returns an unmodifiable copy; if the input is already an unmodifiable list of the same type it may return it as-is. Pair with `List.of`, `Set.of`, `Map.of` (Java 9) for immutable literals.

### 5.5 Text blocks for SQL, JSON, and formatted output

```java
// SQL with parameters
String sql = """
    SELECT id, email, created_at
    FROM users
    WHERE active = ?
      AND created_at >= ?
    ORDER BY created_at DESC
    LIMIT ?
    """;

// JSON template via formatted()
String body = """
    {
      "name": "%s",
      "age": %d
    }
    """.formatted(name, age);

// Preserve a trailing space with \s, and join a long line with \
String oneLine = """
    The quick brown fox \
    jumps over the lazy dog.\s
    """;   // -> "The quick brown fox jumps over the lazy dog. \n"
```

### 5.6 `var` where it helps (and a counter-example)

```java
// Helps: long generic types the RHS already states
var byUser = new ConcurrentHashMap<UserId, List<Session>>();
for (var entry : byUser.entrySet()) { ... }            // Map.Entry<UserId, List<Session>>
try (var in = Files.newInputStream(path)) { ... }       // InputStream

// Hurts readability: the RHS doesn't reveal the type
var result = service.process(input);   // what type? unclear → write the type instead
var x = 0;                             // int or long? trivial gain, write `int x = 0;`
```

### 5.7 Pattern matching to replace an `instanceof` ladder

```java
// Before
static String render(Object o) {
    if (o instanceof Integer) {
        return "int:" + (Integer) o;
    } else if (o instanceof Long) {
        return "long:" + (Long) o;
    } else if (o instanceof String) {
        String s = (String) o;
        return s.isEmpty() ? "empty" : "str:" + s;
    }
    return "unknown";
}

// After
static String render(Object o) {
    return switch (o) {
        case Integer i           -> "int:" + i;
        case Long l              -> "long:" + l;
        case String s when s.isEmpty() -> "empty";
        case String s            -> "str:" + s;
        case null                -> "null";
        default                  -> "unknown";
    };
}
```

### 5.8 A modular CLI (JPMS end to end)

```
src/
  com.acme.app/
    module-info.java
    com/acme/app/Main.java
  com.acme.core/
    module-info.java
    com/acme/core/Greeter.java
```

```java
// com.acme.core/module-info.java
module com.acme.core {
    exports com.acme.core;          // public API
}
// com.acme.app/module-info.java
module com.acme.app {
    requires com.acme.core;         // explicit dependency
}
```

```bash
# compile both modules
javac -d out --module-source-path src $(find src -name '*.java')
# run the app module's main class
java --module-path out --module com.acme.app/com.acme.app.Main
# build a minimal runtime image
jlink --module-path out:$JAVA_HOME/jmods \
      --add-modules com.acme.app \
      --output dist --strip-debug --no-header-files --no-man-pages
```

### 5.9 The reflection escape hatch (`--add-opens`)

When a framework needs deep reflective access into a module that didn't `opens` the package:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar app.jar
# "Open package java.lang of module java.base to all classpath (unnamed) code."
```

This is the single most common JPMS-era command senior engineers memorize, because libraries doing deep reflection on JDK internals break on Java 16+ (strong encapsulation became the default) and this is the surgical fix.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Records**: field access is identical to hand-written classes. `equals`/`hashCode`/`toString` go through `invokedynamic` to `ObjectMethods`; the first call links a `MethodHandle` chain (small one-time cost), and the JIT inlines it thereafter — steady-state performance matches hand-written code. Don't micro-optimize by hand-writing these unless profiling shows a hot path *and* a measurable win (rare).
- **Pattern switch dispatch**: the `typeSwitch` indy builds an efficient dispatcher; for large hierarchies it's competitive with hand-written `instanceof` ladders and the JIT handles it well. Ordering specific cases first can shorten the common path slightly.
- **Text blocks**: zero runtime cost — they're compile-time constants like any string literal, interned identically.
- **`var`**: zero runtime cost — pure compile-time inference; the bytecode is identical to writing the type.
- **JPMS / `jlink`**: smaller images → faster container startup and smaller attack surface. Module resolution adds a small one-time startup cost but catches missing dependencies *eagerly* (a reliability win).

### 6.2 Correctness & concurrency

- Records are **shallowly immutable**: thread-safe to *share* only if their components are themselves immutable or defensively copied (6.4 example). A `record Cache(Map<K,V> data)` with a mutable map is *not* thread-safe.
- Pattern switches **eliminate fall-through bugs** (arrow form) and **enforce exhaustiveness** — both are correctness wins. Treat a `default` in a sealed switch with suspicion: it *defeats* the "won't compile when you add a case" guarantee. Prefer no `default` for sealed selectors so new cases force compile errors.
- Guard ordering is significant — a misordered guard can make an arm unreachable (the compiler catches *dominance* but not all logic mistakes in guards).

### 6.3 Memory

- Records hold exactly their components — no hidden state — so footprint is predictable and minimal (object header + fields). They are excellent candidates for the future **value objects / Valhalla** flattening (a record may become a flattened "value class" with no header in later JDKs — design with that in mind by keeping records immutable).
- Defensive copies (`List.copyOf`) allocate; for hot paths consider passing already-immutable collections.

### 6.4 Security

- **Record deserialization is safer**: it goes through the canonical constructor, so invariants/validation run and malicious streams can't fabricate illegal-state objects (a classic deserialization attack vector closed).
- **Strong encapsulation (JPMS)** reduces attack surface: internal packages are unreachable even via reflection unless explicitly opened. `jlink` images ship only needed modules.
- Avoid `open module` and broad `--add-opens ALL-UNNAMED` in production unless required; scope opens to specific modules/packages.

### 6.5 Observability

- Record `toString()` is auto-generated and **includes all components** — convenient for logging, but a **PII/secret leak risk**. Override `toString()` on records carrying secrets/tokens/passwords:

```java
public record Credentials(String user, String secret) {
    @Override public String toString() { return "Credentials[user=" + user + ", secret=***]"; }
}
```

### 6.6 Testing

- Records' generated `equals` makes assertions trivial: `assertEquals(new Point(1,2), result)`.
- Local records make readable test fixtures/tuples.
- Exhaustive pattern switches are partly *tested by the compiler* — adding a sealed subtype breaks compilation of switches that don't handle it, surfacing gaps before tests run.

### 6.7 Production hardening checklist

- Pin to an **LTS** (17 or 21) for production; track preview-feature graduation if you used any.
- Run `jdeps` in CI to catch use of internal JDK APIs (`sun.*`) before an upgrade breaks you.
- For libraries, add `Automatic-Module-Name:` to the manifest **even if you don't fully modularize** — it gives consumers a stable module name.
- Override record `toString()` for any record with sensitive fields.
- Defensive-copy mutable record components.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Do instead |
|---|---|---|
| `default` in a sealed pattern switch | Defeats exhaustiveness checking | Omit `default`; let new cases break the build |
| Records with mutable, un-copied components | Breaks immutability/thread-safety | `List.copyOf` in compact ctor |
| `var` everywhere | Obscures types when RHS isn't self-documenting | Use `var` only when the type is obvious |
| Logging a record with secrets | PII/secret leak via auto `toString` | Override `toString` |
| Mixing `case X:` and `case X ->` | Compile error / confusion | Pick one form per switch |
| Reaching for JPMS in a small app | High ceremony, low payoff | Use the classpath; modularize platforms/libraries |
| Relying on filename-derived automatic module names | Fragile across rename/version | Set `Automatic-Module-Name` |
| Reopening hierarchies with `non-sealed` casually | Loses the closed-set guarantee | Keep sealed unless a real plugin point exists |

---

## 7. Advanced topics & deep internals

### 7.1 Generic records and inference in patterns

Record patterns infer type arguments. Given `record Box<T>(T value) {}`:

```java
Object o = new Box<>("hi");
if (o instanceof Box<String>(var s)) { ... }   // s inferred as String
if (o instanceof Box(var v)) { ... }           // v inferred as Object (raw-ish inference)
```

The compiler uses the static type information available; nested generic record patterns can infer through multiple levels.

### 7.2 Sealing across modules and packages

- In a **named module**, permitted subtypes may be in different packages of the *same module*.
- In the **unnamed module/classpath**, they must be in the *same package*.
- A sealed type and its subtypes cannot straddle modules. This co-location requirement is what makes the closed set verifiable.

### 7.3 Exhaustiveness with `null`, `sealed`, and separate compilation

- A pattern switch over a sealed type is exhaustive if it covers all permitted subtypes. `null` is **not** automatically covered — add `case null` if the selector can be null (otherwise NPE).
- **Separate compilation hazard:** if module B's enum/sealed type gains a constant/subtype *after* module A was compiled, A's "exhaustive" switch encounters an unknown value at runtime → `MatchException` (Java 21+). This is by design (binary compatibility), but means: recompile consumers when you extend a sealed/enum API, or keep an explicit `default`/`case null, default` for cross-module-evolving types.

### 7.4 Deconstruction can throw → `MatchException`

If a record's accessor is overridden to throw (or returns something that causes a nested pattern to mismatch in a surprising way), the switch may produce a `MatchException` wrapping the cause. Keep accessors total and side-effect-free.

### 7.5 Text-block edge cases

- A text block is *still* a `String` at runtime — `==` interning, `intern()`, and constant-folding all behave like normal literals.
- Trailing whitespace on each line is **stripped** as incidental unless protected with `\s`.
- The closing delimiter's indentation participates in the "minimum indentation" calculation — moving it changes how much is stripped.

### 7.6 `var` corner cases

- `var` captures the **most specific** inferred type, which can be a **non-denotable** type (e.g., an anonymous class type, or an intersection from a conditional). This is occasionally a feature (you keep capabilities you couldn't otherwise name) but can surprise: `var x = condition ? new A() : new B();` may infer a synthetic least-upper-bound type.
- `var` in a `for` loop: `for (var i = 0; i < n; i++)` infers `int`.
- Annotations on inferred types: `var` doesn't let you write type annotations on the inferred type, but `final var` is allowed.

### 7.7 JPMS deep internals

- **Layers (`ModuleLayer`)**: the module graph at runtime lives in a `ModuleLayer`. The boot layer is created at startup; frameworks (OSGi-like containers, plugin systems) can create **child layers** with their own module graph and classloaders — enabling dynamic, isolated module loading.
- **Cyclic dependencies** between modules are **forbidden** at the `requires` level (the graph must be a DAG), though cycles within a module are fine.
- **Split packages** are forbidden across modules; this is the most common migration blocker when two legacy JARs share a package.
- `requires static` (compile-time-only) supports optional dependencies (annotations like `@Nullable` that aren't needed at runtime).

### 7.8 Interaction with future Java (Valhalla, Amber, Loom)

- **Project Amber** is the umbrella for most features here (records, sealed, patterns, switch, text blocks). The trajectory continues: deconstruction patterns for arbitrary classes, primitive patterns in switch (e.g., `case 42`, `case int i`), `with` expressions for records (derived copies).
- **Project Valhalla**: records are the prototype for *value classes* — immutable, identity-free types the JVM can flatten (no header, stack-allocate, store inline in arrays). Writing immutable records today positions code to benefit.
- **Project Loom (virtual threads, Java 21)**: not a language feature per se, but part of "modern Java 21." Virtual threads make blocking code scale; combined with records/sealed for structured data, the idiom is "plain blocking code over rich immutable data."

---

## 8. Tradeoffs & decision frameworks

### 8.1 Record vs class vs Lombok `@Data`

| Aspect | `record` | Hand-written class | Lombok `@Data`/`@Value` |
|---|---|---|---|
| Boilerplate | None (built-in) | Maximal | None (annotation) |
| Mutability | Immutable (shallow) | Your choice | `@Data` mutable, `@Value` immutable |
| Standard / no deps | Yes (JDK) | Yes | No (build-time dependency, IDE plugin) |
| Inheritance | Can't extend classes | Full | Full |
| Pattern-matching destructuring | **Yes** (record patterns) | No | No |
| Future Valhalla flattening | Likely | No | No |
| When to use | Pure data carriers | Behavior-rich/mutable types | Legacy/mutable JavaBeans without records |

**Use records when:** the type is pure immutable data and you want destructuring/exhaustiveness. **Avoid when:** you need mutability, must extend a class, or need hidden derived state.

### 8.2 Sealed interface + records vs enum vs class hierarchy

| Need | Best fit |
|---|---|
| Fixed set of *singletons/constants* | `enum` |
| Fixed set of *shapes carrying different data* | `sealed interface` + records |
| Open, extensible, behavior-polymorphic family | ordinary interface (no sealing) |
| Mostly-closed with one plugin point | sealed + a `non-sealed` branch |

### 8.3 Switch expression vs if-else vs polymorphism

- **Switch expression / pattern switch:** branching on the *shape/type* of data (data-oriented). Best when adding *operations* over a fixed set of *types*.
- **Polymorphism (virtual methods):** best when adding *types* over a fixed set of *operations* (the classic OO axis). This is the "expression problem" tradeoff — sealed + switch optimizes the opposite axis from subtype polymorphism.
- **if-else:** fine for one-off, non-type-based conditions; replace ladders of `instanceof` with pattern switch.

### 8.4 `var` — use when / avoid when

- **Use when:** the RHS is a constructor/factory that names the type (`new ...`, `List.of(...)`), in `for`/try-with-resources, for verbose generic types.
- **Avoid when:** the RHS is a method whose return type isn't obvious, for `int`/`boolean` literals where the type is trivial anyway, or anywhere the explicit type aids the reader.

### 8.5 JPMS — adopt when / skip when

- **Adopt when:** you ship a **platform/SDK/library** that must enforce API boundaries; you want **`jlink`** custom runtimes; you need reliable configuration and strong encapsulation.
- **Skip when:** a typical application service where the classpath works fine, your dependencies aren't fully modularized (automatic-module friction), or the modularization cost outweighs the benefit. Many large, healthy Java apps **never** adopt JPMS for their own code and just live with `--add-opens` flags as needed.

---

## 9. Failure modes & debugging

### 9.1 `InaccessibleObjectException` / "module does not opens" (JPMS)

**Symptom (Java 16+):**
```
java.lang.reflect.InaccessibleObjectException: Unable to make field ... accessible:
module java.base does not "opens java.lang" to unnamed module @...
```
**Cause:** a library uses deep reflection on a package that isn't `opens`. **Fix:** add `--add-opens <module>/<package>=ALL-UNNAMED` (or `=<your.module>`), or update the library to a JPMS-aware version. **Diagnose:** the message names the exact module/package — copy it straight into the flag.

### 9.2 Missing/duplicate module errors at startup

**Symptom:** `Error occurred during initialization of boot layer ... module not found: X` or `module X reads package P from both A and B` (split package). **Diagnose:** `java --describe-module X`, `jar --describe-module --file lib.jar`, and `jdeps` to map the dependency graph. **Fix:** add the module (`--add-modules`), resolve split packages (merge/relocate), or move conflicting JARs off the module path.

### 9.3 Automatic-module name instability

**Symptom:** build breaks after a dependency renames its JAR; module name was derived from the filename. **Fix:** depend on a version that sets `Automatic-Module-Name`, or pin the filename. **Lesson:** never ship a library without an `Automatic-Module-Name` manifest header.

### 9.4 `MatchException` at runtime

**Symptom:** an "exhaustive" switch throws `MatchException`. **Cause:** sealed/enum type evolved after separate compilation, or a record accessor threw during deconstruction. **Diagnose:** check the cause; verify all consumers were recompiled against the new sealed/enum definition. **Fix:** recompile consumers; for cross-module-evolving APIs, add an explicit `default`.

### 9.5 Preview-feature mismatch

**Symptom:** `Preview features are not enabled` or `class file version ... preview` errors. **Cause:** code compiled with `--enable-preview --release N` only runs on the *same* JDK N with `--enable-preview`. **Fix:** match JDK versions; once a feature graduates (e.g., records in 16, sealed/switch in 17, patterns in 21), drop `--enable-preview`.

### 9.6 Records and reflection-based frameworks (older versions)

**Symptom:** an older serialization/ORM library can't instantiate a record (it expected a no-arg constructor + setters). **Cause:** records have no no-arg constructor and no setters. **Fix:** upgrade to record-aware versions (Jackson 2.12+, Hibernate for `@Embeddable`/immutable mappings, etc.), or use the canonical-constructor binding path.

### 9.7 Text-block indentation surprises

**Symptom:** unexpected leading spaces in the resulting string, or stripped trailing spaces. **Diagnose:** print with delimiters: `System.out.println("[" + block + "]")`. **Fix:** move the closing `"""` to control stripping; use `\s` to protect trailing spaces.

### 9.8 Real-world incident pattern: "works on 11, breaks on 17"

A widespread class of upgrade incidents: libraries doing deep reflection on JDK internals (`setAccessible(true)` on `java.*`) worked under Java 8/11 (which warned but allowed it) and **threw on Java 16+** when strong encapsulation became the default. The fix in countless production runbooks is a list of `--add-opens` flags. Senior takeaway: run `jdeps --jdk-internals` and integration tests on the target JDK *before* upgrading, and inventory required `--add-opens` flags as part of the upgrade plan.

---

## 10. Interview drill

**Q1. What does the compiler generate for a record, and what can't you add to one?**
*Model answer:* `private final` field + public accessor per component, a canonical constructor, and `equals`/`hashCode`/`toString` (via `invokedynamic` to `ObjectMethods`). It extends `java.lang.Record`, is implicitly `final`, and can implement interfaces. You **cannot** add extra instance fields, extend a class, or make it non-final.
- *Follow-up: how do `equals`/`hashCode` get implemented?* Via `invokedynamic` bootstrapped by `java.lang.runtime.ObjectMethods`, composing `MethodHandle`s over the components — linked once, then JIT-inlined.
- *Follow-up: compact vs canonical constructor?* Compact omits the parameter list and field assignments (auto-appended after your validation/normalization); you mutate parameters, not fields. The full canonical form lists params and assigns explicitly. You can't have both.
- *Follow-up: how do records deserialize?* Through the canonical constructor (not `Unsafe` allocation), so invariants are enforced — a security improvement.

**Q2. Why do sealed types matter for `switch`?**
*Model answer:* They close the type hierarchy at compile time (enforced via the `PermittedSubclasses` attribute at runtime), so a `switch` covering all permitted subtypes is **exhaustive without `default`**. Add a new subtype and every such switch fails to compile until updated — compiler-enforced completeness.
- *Follow-up: what are the three subtype modifiers?* `final`, `sealed` (continue), `non-sealed` (reopen).
- *Follow-up: why avoid a `default` over a sealed selector?* It defeats the "won't compile when you add a case" guarantee.

**Q3. Explain flow scoping for `instanceof` patterns.**
*Model answer:* The binding is in scope exactly where the compiler proves the match holds — inside the `if` body, after a negated early-return, on the right of `&&` — but not on the right of `||` or after a body that doesn't guarantee the match.
- *Follow-up: give a case where the binding is in scope after the `if`.* `if (!(o instanceof String s)) return;` — below, `s` is in scope because control only continues on a match.

**Q4. What is a record pattern and where can it be used?**
*Model answer:* A pattern that matches a record type and destructures its components into nested sub-patterns (`Circle(Point(var x, var y), var r)`), usable in `instanceof` and `switch`, nesting arbitrarily with per-component `var` inference. Java 21.
- *Follow-up: how does dispatch work in a pattern switch?* `invokedynamic` to `SwitchBootstraps.typeSwitch`; first matching label (top-to-bottom) wins; dominated labels are a compile error.

**Q5. Switch expression vs switch statement — what changed and why does it matter?**
*Model answer:* Arrow form produces a value, has no fall-through, groups labels (`case A, B ->`), and (for expressions/pattern switches) is checked for exhaustiveness. Eliminates `break` bugs and lets `switch` be used as an expression.
- *Follow-up: when is `yield` needed?* In a block arm of a switch expression (or colon-form switch expression) to produce the value.
- *Follow-up: what's `MatchException`?* Thrown when a should-be-exhaustive switch can't match — typically sealed/enum evolution after separate compilation, or a deconstruction failure.

**Q6. How do text blocks handle indentation?**
*Model answer:* Normalize line terminators → strip the **minimum common indentation** (including the closing delimiter line) → process escapes. `\` at line end suppresses the newline; `\s` preserves a space. They're compile-time constants.

**Q7. When should you NOT use `var`?**
*Model answer:* When the initializer doesn't make the type obvious (a method call returning a non-obvious type), for trivial literals where it adds nothing, or anywhere the explicit type improves readability. `var` is local-only, requires an initializer, and is purely compile-time inference (not dynamic typing).

**Q8 (senior signal). When would you adopt JPMS, and when would you deliberately not?**
*Model answer:* Adopt for platforms/SDKs/libraries needing enforced API boundaries, strong encapsulation, and `jlink` custom runtimes. Skip for ordinary application services where the classpath works, especially if dependencies aren't modularized (automatic-module friction) — the cost often outweighs the benefit. Many healthy large apps never modularize their own code and just manage `--add-opens` flags.
- *Follow-up: difference between named, automatic, and unnamed modules?* Named = has `module-info`, strong encapsulation; automatic = plain JAR on module path, reads all/exports all (migration bridge); unnamed = classpath world.
- *Follow-up: why is `Automatic-Module-Name` important even without full modularization?* It gives consumers a stable module name independent of the JAR filename.

**Q9 (senior signal). Model a domain with a fixed set of message types that need many operations. Records/sealed or polymorphism?**
*Model answer:* If types are fixed and operations grow, prefer **sealed interface + records + pattern switch** (data-oriented): adding an operation is one new method with an exhaustive switch, and the compiler enforces coverage. If *types* grow but operations are fixed, prefer subtype **polymorphism**. This is the expression problem — pick the axis that's stable.
- *Follow-up: cost of the data-oriented choice?* Adding a *type* touches every switch (the compiler points you to them). Polymorphism trades that for adding a *type* being cheap but adding an *operation* touching every class.

**Q10 (senior signal). A library that worked on Java 11 throws `InaccessibleObjectException` on 17. Walk me through diagnosis and fix without just disabling encapsulation globally.**
*Model answer:* The message names the exact `module/package`. First, prefer upgrading the library to a JPMS-aware version. If not possible, add a **scoped** `--add-opens <module>/<package>=<consuming-module-or-ALL-UNNAMED>` rather than blanket-opening everything; document each flag and why. Run `jdeps --jdk-internals` to inventory all such uses before the upgrade so the flag list is complete, and cover it with integration tests on the target JDK.
- *Follow-up: `exports` vs `opens`?* `exports` = compile-time + reflective access to the public API; `opens` = runtime deep reflection (private members) — what frameworks need.

**Q11. How does a record with a mutable component stay safe?**
*Model answer:* It doesn't, by default — records are *shallowly* immutable. Defensively copy mutable components (`List.copyOf`) in the compact constructor (and ensure accessors don't leak the mutable reference).

**Q12 (senior signal). You added a new subtype to a sealed interface. What happens across the codebase, and is that good?**
*Model answer:* Every exhaustive `switch` over that sealed type that lacks the new case **fails to compile**, pointing you to exactly the call sites to update. That's the intended, valuable behavior — completeness enforced by the compiler. The risk is cross-module separate compilation: consumers compiled before the change can hit `MatchException` at runtime, so recompile consumers (or keep a `default` for externally-evolving APIs).

---

## 11. Glossary

- **ADT (Algebraic Data Type):** a type built from products (records, "and") and sums (sealed hierarchies, "or").
- **Accessibility:** type-level visibility across modules: read the module **and** the package is exported.
- **Automatic module:** a non-modular JAR on the module path; reads all modules, exports all packages; name from `Automatic-Module-Name` or filename.
- **Binding / pattern variable:** the variable a pattern introduces on a match; flow-scoped.
- **Canonical constructor:** the constructor whose parameters match the record's components in order.
- **Classpath:** the flat, ordered search path for classes; no dependency metadata.
- **Compact canonical constructor:** record constructor with no parameter list; fields auto-assigned after the body.
- **Deconstruction / record pattern:** matching and destructuring a record's components.
- **Dominance:** a label that subsumes a later one, making it unreachable (compile error).
- **Exhaustiveness:** compile-time guarantee that a switch covers all cases.
- **Flow scoping:** scope of a pattern variable determined by where the compiler proves the match.
- **Guard / `when`:** an extra boolean condition on a case label.
- **Immutability:** state can't change after construction; inherently thread-safe.
- **`invokedynamic` (indy):** bytecode that defers call linking to a bootstrap method; powers lambdas, string concat, record methods, pattern switch.
- **JAR hell:** classpath failures from implicit/duplicate/split dependencies.
- **`jdeps`:** dependency-analysis tool; flags internal-API use; generates `module-info` skeletons.
- **`jlink`:** builds a custom minimal runtime image from required modules.
- **JPMS:** Java Platform Module System (Project Jigsaw, Java 9).
- **LTS:** long-term-support JDK release (8, 11, 17, 21, 25).
- **`MatchException`:** thrown when a should-be-exhaustive switch can't match (Java 21+).
- **`MethodHandle`:** a typed, directly-executable reference to a method/field/constructor.
- **Module:** a named, self-describing unit declaring `requires`/`exports`.
- **`ModuleLayer`:** the runtime module graph; supports child layers for dynamic loading.
- **`non-sealed`:** reopens a branch of a sealed hierarchy to arbitrary extension.
- **`opens`:** grants runtime deep-reflective access to a package.
- **`PermittedSubclasses` attribute:** class-file metadata listing a sealed type's allowed subtypes.
- **Preview feature:** a complete-but-provisional feature behind `--enable-preview`.
- **Product type:** a record — several values bundled together.
- **Readability:** module-to-module visibility (A `requires` B).
- **Record:** concise, immutable data carrier; components drive everything.
- **Record component:** a named value in the record header; single source of truth.
- **`requires transitive`:** implied readability passed to consumers.
- **`requires static`:** compile-time-only (optional at runtime) dependency.
- **Sealed type:** a type with an explicitly permitted, closed set of subtypes.
- **Strong encapsulation:** non-exported packages are inaccessible even via reflection.
- **Sum type:** a sealed interface with alternative implementations — a choice among shapes.
- **Switch expression:** value-producing `switch` with arrow arms, no fall-through, exhaustiveness.
- **Text block:** multi-line string literal delimited by `"""`.
- **Type inference:** the compiler deducing a type you didn't write (`var`, diamond, lambdas).
- **Type pattern:** the simplest pattern — a type test plus a binding.
- **Unnamed module:** everything on the classpath; reads everything; legacy world.
- **`var`:** local-variable type inference; compile-time only; not dynamic typing.
- **`yield`:** produces a value from a block arm of a switch expression.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Records**
- `record P(int x, int y) {}` → fields, accessors `x()`, ctor, `equals`/`hashCode`/`toString`. Final; no extends; no extra instance fields. Compact ctor validates (`public P { ... }`). Deserialize via canonical ctor (safe). Override `toString` for secrets. Defensive-copy mutable components.

**Sealed**
- `sealed interface S permits A, B {}`; subtypes are `final`/`sealed`/`non-sealed`. `permits` inferred if all subtypes in the same file. Enables exhaustive switch with **no `default`** → adding a case breaks the build (good).

**Pattern matching**
- `if (o instanceof String s)`; flow-scoped (works through `&&` and negated early-return, not `||`). Record patterns destructure & nest: `case Circle(Point(var x, var y), var r) ->`. Guards: `case Integer i when i < 0 ->`. Null: `case null ->` (else NPE). First match wins; no dominated labels.

**Switch expressions**
- `int n = switch(x){ case A,B -> 1; default -> 0; };` Arrow = no fall-through; `yield` in blocks; must be exhaustive. Don't mix `->` and `:`.

**Text blocks** — `"""` ... `"""`; strips min common indent (incl. closing delim); `\` = no newline, `\s` = kept space; compile-time constant.

**`var`** — locals only; needs initializer; obvious-type RHS only; compile-time, not dynamic.

**JPMS** — `module M { requires X; exports P; opens Q; }`. Named/automatic/unnamed modules. `--add-opens M/P=ALL-UNNAMED` fixes reflection breaks. `jlink` = custom runtime; `jdeps` = analysis. Set `Automatic-Module-Name` in libraries.

**Version map** — text blocks & records & instanceof patterns: previewed 14–15, final **16** (records, instanceof) / **15** (text blocks). Sealed & switch expressions final **17** (switch expr final 14). Pattern switch & record patterns final **21**. `var`: **10** (locals), **11** (lambda params). JPMS: **9**.

**LTS API highlights** — Java 9: `List/Set/Map.of`, `Stream.takeWhile/dropWhile`, JPMS, JShell, `var` (10/11). Java 11: `var` in lambdas, `String.isBlank/strip/lines/repeat`, `Files.readString/writeString`, the standard `HttpClient` (`java.net.http`), single-file source launch (`java File.java`). Java 17: sealed, pattern-matching `instanceof`, text blocks (all final), enhanced pseudo-random generators, `Stream.toList()` (16). Java 21: records patterns, pattern switch, **virtual threads**, sequenced collections (`SequencedCollection`/`getFirst`/`getLast`), `String.indent`, structured concurrency (preview).

### 12.2 Self-test (no answers)

1. Write a `sealed interface Json` with record implementations for `JsonNull`, `JsonBool`, `JsonNumber`, `JsonString`, `JsonArray(List<Json>)`, `JsonObject(Map<String,Json>)`, then a `pretty(Json)` method using a pattern switch with **no `default`**. What happens if you later add `JsonComment`?
2. A record `Money(BigDecimal amount, Currency currency)` is logged and shows up in production logs. Nothing leaks here — but make `Money(String iban, BigDecimal amount)` safe to log. Which method do you override and how?
3. Explain why `if (o instanceof String s || s.isEmpty())` does not compile, but `if (!(o instanceof String s)) return; use(s);` does.
4. You're upgrading a service from Java 11 to 21 and a JSON library throws `InaccessibleObjectException`. Give the exact diagnostic steps and the scoped flag you'd add — without globally disabling encapsulation.
5. Convert this to a switch expression and remove the fall-through bug: a classic `switch (status)` statement that maps HTTP codes to a category, currently using `break` and a shared `category` variable.
6. When does a "guaranteed exhaustive" switch throw `MatchException` at runtime, and how do you prevent it across module boundaries?
7. Give two cases where `var` *hurts* readability and rewrite them with explicit types; then give two where it clearly helps.
8. Your record `Team(String name, List<Player> roster)` is shared across threads and players keep "disappearing" from one thread's view. Diagnose and fix using only standard-library calls.

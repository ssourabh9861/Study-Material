# Java Type System & Generics — A Complete Reference

> Concept area: Java Language & Concurrency · Subtopic: Type System & Generics
> Audience: senior JVM backend developers who want to master this from first principles to deep internals.

---

## 1. Overview & where it fits

### What it is

Java's **type system** is the set of rules the language and runtime use to classify every value and expression into a **type**, and to decide which operations are legal on which values. A *type* is a label that says "values of this kind support these operations and occupy this shape in memory." `int`, `String`, `List<String>`, `int[]`, and `Runnable` are all types. The type system is the machinery that lets the compiler reject `"hello" - 3` before you ever run the program.

**Generics** are the part of Java's type system that lets you write code parameterized *by type*. Instead of writing a `List` that holds `Object` (and casting everything in and out), you write `List<String>` — a list whose element type is a parameter you fill in. The `<String>` is a **type argument**; the `E` in `interface List<E>` is a **type parameter**. Generics turn "a list of stuff" into "a list of *this specific kind* of stuff," checked at compile time.

### The problem it solves

Before generics (Java 1.4 and earlier), collections held `Object`:

```java
List names = new ArrayList();
names.add("Alice");
String first = (String) names.get(0);   // explicit, error-prone cast
names.add(42);                           // compiles fine — bug waiting to happen
String oops = (String) names.get(1);     // ClassCastException at RUNTIME
```

Two problems: (1) you cast on every read, which is verbose and unsafe; (2) nothing stops you putting an `Integer` into a "list of strings," so type errors surface at runtime as `ClassCastException` — far from where the bug was introduced. Generics move that error to **compile time**:

```java
List<String> names = new ArrayList<>();
names.add("Alice");
String first = names.get(0);   // no cast — compiler knows it's a String
names.add(42);                 // COMPILE ERROR — caught immediately
```

This is the core value proposition: **generics provide compile-time type safety and eliminate casts**, with (mostly) zero runtime cost.

### When you reach for it

- **Designing any container or collection** — anything that "holds" or "produces" or "consumes" values of a type the caller chooses.
- **Writing utility/algorithm code** that should work uniformly across many types (`Comparator<T>`, `Optional<T>`, `CompletableFuture<T>`, `Function<T,R>`).
- **Building APIs** where you want callers to get type-safe results without casts and without you knowing their concrete types.
- **Enforcing relationships between parameters and return types** (e.g., "the return type equals the element type of the list passed in").

### One-paragraph mental model

Think of generics as **a compile-time-only contract**. At compile time, the compiler tracks the precise type arguments (`List<String>`) and uses them to check every call and insert hidden casts. At runtime, almost all of that type information is **erased** — a `List<String>` and a `List<Integer>` are both just `List` to the JVM. This erasure is the single most important fact about Java generics: it makes generics backward-compatible with pre-generics bytecode and keeps them cheap, but it is also the root cause of nearly every confusing limitation (`new T[]` is illegal, you can't do `obj instanceof List<String>`, overloads can collide, etc.). Master erasure and you've mastered 70% of the surprises.

---

## 2. Foundations from first principles

### 2.1 Static vs dynamic typing

A type system is **static** if types are checked at *compile time* (before the program runs) and **dynamic** if checked at *run time*. Java is predominantly **statically typed**: the compiler `javac` proves a great deal about your program before producing bytecode. Python and JavaScript are dynamically typed — a name can hold an `int` now and a `string` later, and type mismatches blow up only when the offending line executes.

Java is also **strongly typed** (it does not silently coerce wildly incompatible types — there is no implicit "add a string to a number to get a number" the way JavaScript does) and **mostly nominal** (type compatibility is decided by *name/declaration*, not just by shape — implementing `Runnable` requires you to *declare* `implements Runnable`, not merely to have a `run()` method). The contrast is **structural typing** (TypeScript, Go interfaces) where having the right shape is enough.

> **Term — compile time vs runtime.** *Compile time* is when `javac` translates `.java` source into `.class` bytecode. *Runtime* is when the JVM (`java`) loads and executes that bytecode. Errors caught at compile time never reach production; errors that surface at runtime do.

### 2.2 The two type worlds: primitives and references

Java has exactly **two kinds of types**:

1. **Primitive types** — `boolean`, `byte`, `short`, `char`, `int`, `long`, `float`, `double`. These hold raw values directly (a `int` variable *is* the 32-bit integer). They are not objects, have no methods, and cannot be `null`.
2. **Reference types** — classes, interfaces, arrays, enums, records, and type variables. A reference-type variable holds a *reference* (a pointer) to an object on the heap, or `null`.

> **Why this matters for generics:** **type parameters can only be reference types.** You cannot write `List<int>`. You must use the **boxed** wrapper class `List<Integer>`. This is a direct consequence of erasure (see §3) and the reason **autoboxing** exists and the reason primitive-heavy generic code can be slow (boxing allocates objects).

> **Term — boxing / autoboxing.** *Boxing* wraps a primitive in its object wrapper (`int` → `Integer`). *Autoboxing* is the compiler doing it for you (`Integer i = 5;`). *Unboxing* is the reverse (`int x = i;`). Each box is a heap object; in tight loops or large collections this allocation and the cache-unfriendly pointer-chasing are real performance costs. (Project Valhalla aims to fix this — see §7.)

### 2.3 The reference-type hierarchy and subtyping

Every reference type except `Object` has supertypes. `Object` is the root. **Subtyping** is the "is-a" relation: `String` is a subtype of `Object`, so a `String` value is usable wherever an `Object` is expected. The formal rule is the **Liskov Substitution Principle (LSP)**: if `S` is a subtype of `T`, a value of type `S` can be substituted anywhere a `T` is required without breaking correctness.

> **Term — supertype / subtype.** If `B extends A` (or `B implements A`), then `B` is a *subtype* of `A` and `A` is a *supertype* of `B`. Subtyping is reflexive (`T` is a subtype of itself) and transitive.

Java's subtyping comes from: class inheritance (`extends`), interface implementation (`implements`), array covariance (§2.7), and — with generics — wildcard bounds (§2.6).

### 2.4 Generics 101 — type parameters and type arguments

A **generic type** declares one or more **type parameters** in angle brackets:

```java
public class Box<T> {            // T is a type parameter (a "type variable")
    private T value;
    public void set(T value) { this.value = value; }
    public T get() { return value; }
}
```

`T` is a placeholder for a real type supplied later. When you *use* `Box`, you supply a **type argument**:

```java
Box<String> b = new Box<>();   // String is the type argument
b.set("hi");
String s = b.get();            // returns String, no cast
```

Conventional single-letter names: `T` (type), `E` (element), `K`/`V` (key/value), `R` (result), `S`/`U`/`N` (additional). These are conventions, not rules — any identifier works, but follow convention for readability.

**Generic methods** declare their own type parameters, placed *before the return type*:

```java
public static <T> T firstOrNull(List<T> list) {     // <T> declares the method's type param
    return list.isEmpty() ? null : list.get(0);
}
String name = firstOrNull(List.of("a", "b"));        // T inferred as String
```

### 2.5 Bounded type parameters

A bound restricts what a type argument may be. **Upper bound** with `extends`:

```java
// T must be Number or a subtype — now we can call Number methods on T
public static <T extends Number> double sum(List<T> nums) {
    double total = 0;
    for (T n : nums) total += n.doubleValue();   // doubleValue() exists because T <: Number
    return total;
}
```

`<T extends Number>` means "T is some unknown type that is Number or below." Without the bound, `T` is treated as `Object` inside the method and you could only call `Object` methods.

**Multiple bounds** use `&`. At most one may be a class (and it must come first); the rest must be interfaces:

```java
public static <T extends Comparable<T> & Serializable> T maxSerializable(List<T> list) { ... }
```

> **Term — `extends` in bounds vs inheritance.** In a bound, `extends` means "is a subtype of," and it covers *both* classes and interfaces. `<T extends Runnable>` is legal even though `Runnable` is an interface — there's no separate `implements` keyword in generic bounds.

### 2.6 Wildcards: `?`, `? extends`, `? super`

A **wildcard** `?` is an *unknown type* — used when you don't care about, or can't name, the exact type argument.

- **Unbounded wildcard `List<?>`** — "a list of some unknown type." You can read elements as `Object` and check size, but you cannot `add` anything except `null` (the compiler doesn't know the actual element type, so it can't verify your argument is compatible).

- **Upper-bounded wildcard `List<? extends Number>`** — "a list of Number or some subtype (we don't know which)." You can *read* elements as `Number`. You **cannot add** (except `null`): the list might really be `List<Integer>`, so adding a `Double` would corrupt it. This is a **producer** — it produces `Number`s for you to consume.

- **Lower-bounded wildcard `List<? super Integer>`** — "a list of Integer or some supertype." You can *add* `Integer` (and subtypes of `Integer`) safely. Reading gives you only `Object` (the element type could be `Number` or `Object`). This is a **consumer** — it consumes `Integer`s.

#### PECS — Producer Extends, Consumer Super

The mnemonic from Joshua Bloch's *Effective Java*: **PECS — "Producer `extends`, Consumer `super`."**

- If a parameter *produces* `T` values for you (you read from it), use `? extends T`.
- If a parameter *consumes* `T` values from you (you write to it), use `? super T`.
- If it does both, use an exact type `T` (no wildcard).

Canonical example — `Collections.copy`:

```java
public static <T> void copy(List<? super T> dest, List<? extends T> src) {
    // src PRODUCES T-or-subtype elements (we read) -> extends
    // dest CONSUMES T-or-subtype elements (we write) -> super
    for (int i = 0; i < src.size(); i++) dest.set(i, src.get(i));
}
```

This lets you copy a `List<Integer>` (src) into a `List<Number>` or `List<Object>` (dest), which is exactly what you want and what would otherwise be rejected.

### 2.7 Variance: covariance, contravariance, invariance

**Variance** describes how subtyping of components relates to subtyping of containers.

- **Covariant**: if `S <: T`, then `Container<S> <: Container<T>`. Order preserved.
- **Contravariant**: if `S <: T`, then `Container<T> <: Container<S>`. Order reversed.
- **Invariant**: no subtype relationship is induced regardless of `S` vs `T`.

**Java generics are invariant.** `List<String>` is **not** a subtype of `List<Object>`, even though `String <: Object`. This surprises beginners but is *necessary for soundness*:

```java
List<String> strings = new ArrayList<>();
List<Object> objects = strings;   // ILLEGAL — and here's why it must be:
objects.add(42);                  // would put an Integer into a List<String>!
String s = strings.get(0);        // ...and explode here with ClassCastException
```

By making `List<String>` and `List<Object>` unrelated, the compiler prevents that hole.

**Wildcards give you opt-in variance**: `List<? extends Object>` *is* a supertype of `List<String>` (use-site covariance), and `List<? super String>` is a supertype of `List<Object>` (use-site contravariance). Java has **use-site variance** (you choose variance where you *use* the type, via wildcards), unlike Scala/Kotlin which also offer **declaration-site variance** (`out T`/`in T` declared on the type itself).

**Arrays are covariant** — and this is a famous design wart:

```java
Object[] arr = new String[3];   // legal: arrays ARE covariant
arr[0] = 42;                     // compiles, but throws ArrayStoreException at RUNTIME
```

Array covariance was a Java 1.0 compromise (it let pre-generics code write polymorphic array routines) but it trades compile-time safety for a runtime check (`ArrayStoreException`). Generics chose invariance precisely to avoid this.

### 2.8 Reifiable vs non-reifiable types

> **Term — reifiable.** A type is *reifiable* if its full type information is available at runtime — the JVM "knows" it. A type is *non-reifiable* if some of its type information is erased and unavailable at runtime.

**Reifiable types** include: primitives, non-generic classes/interfaces (`String`, `Number`), raw types (`List`), `List<?>` (unbounded wildcard), and arrays of reifiable types.

**Non-reifiable types** include: parameterized types with concrete arguments (`List<String>`, `Map<String,Integer>`) and bounded wildcards (`List<? extends Number>`). At runtime these are all just `List`/`Map` — the `<String>` is gone.

This distinction is *the* reason for these illegal operations:

```java
new List<String>[10];          // ILLEGAL: generic array creation
obj instanceof List<String>;   // ILLEGAL: only List<?> or raw List allowed
new T[size];                   // ILLEGAL: T is non-reifiable
catch (MyException<String> e)  // ILLEGAL: exceptions must be reifiable
```

### 2.9 Type erasure — the foundation

> **Term — type erasure.** The process by which `javac` removes generic type information during compilation, replacing type parameters with their **bound** (or `Object` if unbounded) and inserting casts where needed. After compilation, the bytecode contains *no* `<...>` type arguments (except in metadata/signatures kept for tooling).

Erasure is the mechanism that makes everything in §2.8 true. It deserves its own deep section — see §3.

### 2.10 `var` and local type inference

Since **Java 10**, `var` lets the compiler infer the type of a **local variable** from its initializer:

```java
var names = new ArrayList<String>();   // inferred ArrayList<String>
var count = 10;                        // inferred int
var entry = Map.entry("k", 1);         // inferred Map.Entry<String,Integer>
```

`var` is *not* dynamic typing and *not* a new type — the variable still has a single, static, compile-time type; you just didn't spell it out. It works only for locals (and `for`/try-with-resources headers), never for fields, method parameters, or return types. We cover its rules and traps in §4.6 and §6.8.

---

## 3. How it works internally — type erasure, end to end

This is the heart of the document. Generics are a **compile-time feature implemented by erasure**; understanding the exact transformation explains every limitation.

### 3.1 The big picture: what `javac` does

When you compile generic code, `javac` performs (conceptually) these passes:

1. **Parse & resolve types** — builds the AST and resolves type names.
2. **Type inference** — infers type arguments for generic method calls and diamond `<>` (see §3.6).
3. **Type checking** — verifies all generic constraints using the *full* parameterized types. *This is where type safety is enforced.* `names.add(42)` on a `List<String>` fails here.
4. **Erasure** — rewrites the program, removing type parameters:
   - Each type variable is replaced by its **leftmost bound**, or `Object` if unbounded.
   - Parameterized types lose their arguments: `List<String>` → `List`.
   - **Casts are inserted** at every point where erased code returns a value that the caller expects at a more specific type.
   - **Bridge methods** are synthesized where needed to preserve polymorphism (see §3.4).
5. **Bytecode generation** — emits `.class` files. Type-argument info survives only in the **`Signature` attribute** (metadata), not in the executable instructions.

### 3.2 Erasure rules, precisely

| Source type | Erased to |
|---|---|
| `T` (unbounded) | `Object` |
| `T extends Number` | `Number` |
| `T extends Comparable<T> & Serializable` | `Comparable` (leftmost bound) |
| `List<String>`, `List<? extends X>`, `List<?>` | `List` |
| `T[]` (T unbounded) | `Object[]` |
| `List<String>[]` (if it existed) | `List[]` |

Example transformation. Source:

```java
class Box<T extends Number> {
    private T value;
    T get() { return value; }
    void set(T value) { this.value = value; }
}
Box<Integer> b = new Box<>();
b.set(5);
int x = b.get();
```

Erased (conceptually):

```java
class Box {                      // type parameter gone
    private Number value;        // T erased to its bound Number
    Number get() { return value; }
    void set(Number value) { this.value = value; }
}
Box b = new Box();
b.set(Integer.valueOf(5));       // autoboxing
int x = ((Integer) b.get()).intValue();   // COMPILER-INSERTED cast (to Integer), then unbox
```

The cast `(Integer)` is the "hidden cast" — it's exactly the cast you used to write by hand pre-generics. The compiler proved it's safe (because of step-3 checking), so it can't fail unless you defeated the type system with raw types or unchecked operations.

### 3.3 What survives: the `Signature` attribute and reflection

Erasure removes type arguments from *bytecode instructions*, but `javac` records the original generic signatures in a class-file **`Signature` attribute** (JVMS §4.7.9). This is metadata, not executable. It lets:

- **Reflection** read declared generic types: `Method.getGenericReturnType()`, `Field.getGenericType()`, `Class.getTypeParameters()`, `ParameterizedType`.
- **IDEs and compilers** that consume your `.class` files still see `List<String>` in your public API.

Crucially, this metadata is on **declarations** (class/method/field signatures), not on **instances**. So:

```java
List<String> l = new ArrayList<>();
l.getClass();   // == ArrayList.class — NO type argument, instances don't carry it
```

You can recover the declared type of a *field* or *superclass* via reflection, but never the runtime type argument of an arbitrary *object*.

### 3.4 Bridge methods — preserving polymorphism under erasure

Consider:

```java
interface Comparable<T> { int compareTo(T o); }
class Money implements Comparable<Money> {
    public int compareTo(Money other) { ... }   // signature: compareTo(Money)
}
```

After erasure, `Comparable.compareTo` becomes `compareTo(Object)`. But `Money` declared `compareTo(Money)`. These are *different signatures* — so `Money` doesn't actually override the erased interface method, which would break polymorphism (`((Comparable) money).compareTo(x)` would dispatch to nothing).

The compiler fixes this by generating a **bridge method** in `Money`:

```java
// synthetic, compiler-generated
public int compareTo(Object o) {       // matches erased interface signature
    return compareTo((Money) o);       // delegates to the real method, with a cast
}
```

Now `Money` has *two* `compareTo` methods in bytecode: the real `compareTo(Money)` and the bridge `compareTo(Object)`. The bridge is marked `ACC_BRIDGE | ACC_SYNTHETIC`.

**Consequences you can observe:**
- `Money.class.getMethods()` shows both methods. Filter with `Method.isBridge()` if iterating.
- The cast inside the bridge can throw `ClassCastException` if heap pollution (§3.5) snuck a wrong type in.
- Stack traces sometimes show the synthetic bridge frame.

> **Term — bridge method.** A synthetic method the compiler inserts so that an overriding method with a more specific (generic-derived) signature still correctly overrides the erased supertype method. It bridges the erased signature to the real one with a cast.

### 3.5 Heap pollution

> **Term — heap pollution.** A situation where a variable of a parameterized type refers to an object that is *not* of that parameterized type — e.g., a `List<String>` reference actually pointing to a list that contains an `Integer`. Erasure makes this possible because the runtime can't enforce the type argument.

It typically arises from unchecked operations or raw types:

```java
List<String> strings = new ArrayList<>();
List raw = strings;            // raw type — unchecked
raw.add(42);                   // heap pollution: an Integer is now in a "List<String>"
String s = strings.get(0);     // ClassCastException at the hidden cast
```

The `ClassCastException` happens at the *read site* (where the inserted cast runs), not the *write site* — which is why these bugs are hard to trace. The compiler warns ("unchecked") at the write; heed those warnings.

### 3.6 Type inference internals

`javac` infers type arguments in several places:

1. **Generic method invocation:** `Collections.<String>emptyList()` can usually be written `Collections.emptyList()`; the compiler infers `String` from the target (the variable type, the method parameter, etc.).
2. **Diamond operator (`<>`, Java 7+):** `new ArrayList<>()` infers from the variable's declared type.
3. **Target typing (Java 8+):** inference uses the *expected* type at the use site, including lambda parameter types and chained method calls. This is why `List.of()` assigned to `List<String>` infers `String`.
4. **`var` (Java 10+):** infers the local's type from the initializer.

The algorithm (post-Java 8, JLS §18) collects **inference variables**, gathers **constraints** (from arguments, the target type, and bounds), and resolves them via a bound-set/reduction process, ultimately picking the most specific types that satisfy all constraints. Pre-Java 8 inference was weaker and often forced explicit witnesses (`Collections.<String>emptyList()`); Java 8's improved inference and "poly expressions" removed most of those.

A practical inference gotcha:

```java
var list = new ArrayList<>();   // infers ArrayList<Object> — the <> has nothing to anchor to!
```

With `var` the diamond has no target type, so it falls back to `Object`. Always specify: `new ArrayList<String>()`.

### 3.7 Lifecycle / state transitions summary

```
Source (.java, with <T>)
   │  javac: parse → infer → TYPE-CHECK (full generics, safety enforced here)
   ▼
Type-checked AST
   │  javac: ERASE (T → bound/Object), INSERT CASTS, GENERATE BRIDGES
   ▼
Bytecode (.class)
   ├─ executable instructions: NO type args, just casts/bridges
   └─ Signature attribute: original generics preserved (metadata only)
   │  JVM: load, verify, JIT
   ▼
Runtime
   ├─ instances: carry NO type argument (getClass() shows raw class)
   └─ reflection: can read DECLARED generic signatures from metadata
```

---

## 4. The complete toolkit

### 4.1 Language constructs

| Construct | Syntax | Purpose | Notes / defaults |
|---|---|---|---|
| Generic class/interface | `class Box<T> {}` | Parameterize a type by `T` | Any number of params: `Map<K,V>` |
| Generic method | `<T> T id(T x)` | Method has own type param | Declared before return type |
| Bounded param (upper) | `<T extends Number>` | Restrict to subtype of bound | Default bound is `Object` |
| Multiple bounds | `<T extends A & B & C>` | Intersection of bounds | ≤1 class, must be first; rest interfaces |
| Recursive bound | `<T extends Comparable<T>>` | "T comparable to itself" | Common for ordering / fluent builders |
| Unbounded wildcard | `List<?>` | Unknown element type | Read as `Object`; can't add (except `null`) |
| Upper-bounded wildcard | `List<? extends T>` | Producer of T | Read as T; can't add |
| Lower-bounded wildcard | `List<? super T>` | Consumer of T | Add T; read as Object |
| Diamond | `new ArrayList<>()` | Infer type args | Java 7+; needs target type |
| Raw type | `List` (no `<>`) | Pre-generics compatibility | Avoid; disables generic checks |
| `var` | `var x = expr;` | Infer local var type | Java 10+; locals only |
| Explicit type witness | `Collections.<String>emptyList()` | Force a method's type arg | Rarely needed post-Java 8 |
| Generic constructor | `<T> Foo(T arg)` | Constructor with own type param | Distinct from class type params |
| `@SafeVarargs` | annotation on varargs method | Suppress unchecked varargs warning | Only on `static`/`final`/`private` methods |

### 4.2 Reflection API for generics (`java.lang.reflect`)

| Type / method | Returns | Purpose |
|---|---|---|
| `Type` (interface) | — | Root of the reflective type model |
| `Class<?>` | implements `Type` | A reifiable raw type |
| `ParameterizedType` | `Type` | E.g. `List<String>`; `getRawType()`, `getActualTypeArguments()`, `getOwnerType()` |
| `TypeVariable<D>` | `Type` | A type parameter `T`; `getName()`, `getBounds()`, `getGenericDeclaration()` |
| `WildcardType` | `Type` | A `?`; `getUpperBounds()`, `getLowerBounds()` |
| `GenericArrayType` | `Type` | E.g. `T[]` or `List<String>[]`; `getGenericComponentType()` |
| `Class.getTypeParameters()` | `TypeVariable[]` | Declared type params of a class |
| `Class.getGenericSuperclass()` | `Type` | Superclass *with* type args (enables super-type-token trick) |
| `Class.getGenericInterfaces()` | `Type[]` | Implemented interfaces with type args |
| `Method.getGenericReturnType()` | `Type` | Declared return type incl. generics |
| `Method.getGenericParameterTypes()` | `Type[]` | Declared param types incl. generics |
| `Field.getGenericType()` | `Type` | Declared field type incl. generics |
| `Method.isBridge()` | `boolean` | Is this a synthetic bridge method? |
| `Method.isSynthetic()` | `boolean` | Compiler-generated? |

### 4.3 Library utilities that lean on generics

| API | Signature shape | What it shows |
|---|---|---|
| `Collections.emptyList()` | `static <T> List<T>` | Inference-friendly factory |
| `Collections.copy(dest, src)` | `<T> void copy(List<? super T>, List<? extends T>)` | Textbook PECS |
| `Collections.max(coll)` | `<T extends Object & Comparable<? super T>> T max(Collection<? extends T>)` | Recursive bound + PECS |
| `Comparator.comparing(fn)` | `<T,U extends Comparable<? super U>> Comparator<T>` | Higher-order generics |
| `Optional<T>`, `.map`, `.flatMap` | `<U> Optional<U> map(Function<? super T,? extends U>)` | PECS on functions |
| `Stream<T>`, `.map`, `.collect` | `<R> Stream<R> map(Function<? super T,? extends R>)` | Producer/consumer wildcards |
| `Class<T>.cast(obj)` | `T cast(Object)` | Type-token-based safe cast |
| `List.toArray(T[])` | `<T> T[] toArray(T[] a)` | Work around no-`new T[]` |
| `EnumSet<E extends Enum<E>>` | recursive bound | Self-referential generic |

### 4.4 Compiler flags & tools

| Flag / tool | Effect |
|---|---|
| `javac -Xlint:unchecked` | Warn on unchecked (unsafe generic) operations |
| `javac -Xlint:rawtypes` | Warn on use of raw types |
| `javac -Xlint:all` | All recommended lint warnings |
| `javac -Werror` | Treat warnings as errors (CI hardening) |
| `@SuppressWarnings("unchecked")` | Locally suppress an unchecked warning (justify it!) |
| `@SuppressWarnings("rawtypes")` | Locally suppress raw-type warning |
| `javap -v MyClass` | Disassemble; shows `Signature` attributes & bridge methods |
| `javap -s MyClass` | Show internal (erased) type signatures |

### 4.5 The "type token" pattern toolkit

Because `T.class` and `new T[]` don't work, idioms exist to pass type info at runtime:

| Idiom | Form | Use |
|---|---|---|
| Class token | `Class<T> type` parameter | `enumValues(Class<E>)`, `Class.cast` |
| Super type token | `abstract class TypeRef<T>` + `getGenericSuperclass()` | Capture *parameterized* types like `List<String>` (Guava `TypeToken`, Jackson `TypeReference`, Spring `ParameterizedTypeReference`) |
| `Array.newInstance` | `(T[]) Array.newInstance(componentClass, len)` | Build a generic array reflectively |

### 4.6 `var` rules (Java 10+)

| Allowed | Not allowed |
|---|---|
| Local variables with initializer | Fields |
| Indexed `for` / enhanced `for` variable | Method parameters |
| `try`-with-resources variable | Return types |
| Lambda *parameter* `var` (Java 11+, with annotations) | Variables with no initializer |
| | `var x = null;` (can't infer) |
| | `var arr = {1,2,3};` (array initializer needs explicit type) |
| | Catch clause variable |

---

## 5. Code examples by use case

### 5.1 A type-safe heterogeneous container (Typesafe Heterogeneous Container, THC)

Store values of *different* types in one map, keyed by their `Class`, with no casts at the call site. This is how `java.util.Collections.checkedMap` and many DI/config systems work.

```java
import java.util.*;

/** A container where each key carries its own value type via a Class token. */
public final class Favorites {
    private final Map<Class<?>, Object> map = new HashMap<>();

    public <T> void put(Class<T> type, T instance) {
        // Class.cast acts as a runtime guard against heap pollution
        map.put(Objects.requireNonNull(type), type.cast(instance));
    }

    public <T> T get(Class<T> type) {
        return type.cast(map.get(type));   // safe cast via the token; no unchecked warning
    }

    public static void main(String[] args) {
        Favorites f = new Favorites();
        f.put(String.class, "hello");
        f.put(Integer.class, 42);
        f.put(Class.class, Favorites.class);

        String s = f.get(String.class);    // no cast needed by caller
        int i = f.get(Integer.class);
        System.out.println(s + " " + i);   // hello 42
    }
}
```

Why it works: the `Class<T>` token *reifies* the type the caller wants. `type.cast(...)` performs a checked runtime cast, so even if someone defeats generics with a raw `Class`, you get an immediate, localized `ClassCastException` instead of latent heap pollution.

### 5.2 PECS in a real method — flexible `pushAll` / `popAll`

```java
import java.util.*;

public class Stack<E> {
    private final Deque<E> elements = new ArrayDeque<>();

    public void push(E e) { elements.push(e); }
    public E pop() { return elements.pop(); }
    public boolean isEmpty() { return elements.isEmpty(); }

    // We READ from src -> src is a PRODUCER of E -> ? extends E
    public void pushAll(Iterable<? extends E> src) {
        for (E e : src) push(e);
    }

    // We WRITE into dst -> dst is a CONSUMER of E -> ? super E
    public void popAll(Collection<? super E> dst) {
        while (!isEmpty()) dst.add(pop());
    }

    public static void main(String[] args) {
        Stack<Number> numbers = new Stack<>();
        List<Integer> ints = List.of(1, 2, 3);        // List<Integer> is a List<? extends Number>
        numbers.pushAll(ints);                         // OK thanks to ? extends

        Collection<Object> objs = new ArrayList<>();   // Collection<Object> is a Collection<? super Number>
        numbers.popAll(objs);                          // OK thanks to ? super
        System.out.println(objs);                      // [3, 2, 1]
    }
}
```

Without the wildcards, `pushAll(List<Integer>)` on a `Stack<Number>` would not compile (invariance), and `popAll(List<Object>)` would not either.

### 5.3 Generic method with a recursive bound — `max`

```java
import java.util.*;

public class Algorithms {
    // T must be comparable to itself (or a supertype) — the recursive bound
    public static <T extends Comparable<? super T>> T max(Collection<? extends T> coll) {
        Iterator<? extends T> it = coll.iterator();
        T best = it.next();
        while (it.hasNext()) {
            T next = it.next();
            if (next.compareTo(best) > 0) best = next;
        }
        return best;
    }

    public static void main(String[] args) {
        System.out.println(max(List.of(3, 1, 4, 1, 5, 9)));         // 9
        System.out.println(max(List.of("pear", "apple", "fig")));   // pear
    }
}
```

`? super T` in `Comparable<? super T>` is the subtle bit: it allows a subtype to be compared using an *ancestor's* `compareTo`. E.g., if `Manager extends Employee implements Comparable<Employee>`, you can still `max(List<Manager>)`.

### 5.4 Super type token — capturing `List<String>` at runtime

Class tokens can't represent `List<String>` (it erases to `List`). The super-type-token trick captures the full parameterized type via an anonymous subclass.

```java
import java.lang.reflect.*;

public abstract class TypeRef<T> {
    private final Type type;
    protected TypeRef() {
        // getGenericSuperclass() reads the Signature metadata of the anon subclass
        Type superClass = getClass().getGenericSuperclass();
        this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
    }
    public Type getType() { return type; }

    public static void main(String[] args) {
        TypeRef<java.util.List<String>> ref = new TypeRef<>() {};  // anonymous subclass!
        System.out.println(ref.getType());   // java.util.List<java.lang.String>
    }
}
```

This is exactly the mechanism behind Jackson's `new TypeReference<List<String>>(){}`, Guava's `TypeToken`, and Spring's `ParameterizedTypeReference`. The trailing `{}` is essential — it creates a *subclass*, whose `Signature` records the type argument.

### 5.5 Working around "no `new T[]`" — a generic array

```java
import java.lang.reflect.Array;
import java.util.*;

public class Stacks {
    // Caller passes the component Class so we can build a real T[] at runtime
    @SuppressWarnings("unchecked")
    public static <T> T[] toArray(List<T> list, Class<T> componentType) {
        T[] arr = (T[]) Array.newInstance(componentType, list.size());  // reified array
        return list.toArray(arr);
    }

    public static void main(String[] args) {
        String[] s = toArray(List.of("a", "b"), String.class);
        System.out.println(Arrays.toString(s));   // [a, b]
    }
}
```

The unchecked cast is genuinely safe here because `Array.newInstance(String.class, n)` returns an actual `String[]`, so `@SuppressWarnings("unchecked")` is justified — and you should write a comment saying so.

### 5.6 `@SafeVarargs` — generic varargs done right

Generic varargs create a non-reifiable array (`T...` → `T[]` → erased `Object[]`), risking heap pollution. If your method only *reads* the varargs and never exposes the array, annotate it `@SafeVarargs`.

```java
import java.util.*;

public class VarargsDemo {
    @SafeVarargs   // promise: we don't store or leak the varargs array unsafely
    public static <T> List<T> listOf(T... items) {
        List<T> result = new ArrayList<>();
        for (T item : items) result.add(item);   // read-only use — safe
        return result;
    }

    public static void main(String[] args) {
        List<String> xs = listOf("a", "b", "c");
        System.out.println(xs);
    }
}
```

Anti-example (do NOT do): returning the varargs array or storing it lets callers corrupt it — that's why `@SafeVarargs` is your promise, not a free pass.

### 5.7 Generic builder with self-type (CRTP / fluent inheritance)

To make a fluent builder return the *subclass* type from inherited methods, use a recursive self-type bound.

```java
public abstract class AbstractBuilder<T extends AbstractBuilder<T>> {
    private String name;

    @SuppressWarnings("unchecked")
    protected T self() { return (T) this; }   // safe by construction of subclasses

    public T name(String name) { this.name = name; return self(); }
    protected String name() { return name; }
}

class PizzaBuilder extends AbstractBuilder<PizzaBuilder> {
    private boolean cheese;
    public PizzaBuilder cheese(boolean c) { this.cheese = c; return self(); }
    public String build() { return name() + (cheese ? " +cheese" : ""); }

    public static void main(String[] args) {
        // .name() returns PizzaBuilder (not AbstractBuilder), so .cheese() chains:
        String p = new PizzaBuilder().name("Margherita").cheese(true).build();
        System.out.println(p);   // Margherita +cheese
    }
}
```

This **Curiously Recurring Template Pattern (CRTP)** is how many fluent APIs (e.g., parts of Spring Security's HTTP DSL) keep the concrete builder type through inherited calls.

### 5.8 Bounded wildcards in a functional/stream API

```java
import java.util.*;
import java.util.function.*;

public class Pipelines {
    // Function PRODUCES R (extends) and CONSUMES T (super) — textbook PECS on functions
    public static <T, R> List<R> mapAll(
            List<? extends T> input,
            Function<? super T, ? extends R> mapper) {
        List<R> out = new ArrayList<>(input.size());
        for (T t : input) out.add(mapper.apply(t));
        return out;
    }

    public static void main(String[] args) {
        List<Integer> nums = List.of(1, 2, 3);
        // mapper: Function<Number, String> accepted for input List<Integer>
        Function<Number, String> f = n -> "n=" + n;
        System.out.println(mapAll(nums, f));   // [n=1, n=2, n=3]
    }
}
```

This is exactly the signature shape of `Stream.map`, `Optional.map`, and `CompletableFuture.thenApply` — note how `? super T` on the input and `? extends R` on the output maximize the set of acceptable lambdas/method references.

### 5.9 Interaction with sealed types + records (Java 17+/21+)

```java
sealed interface Expr<T> permits Lit, Add {}
record Lit<T>(T value) implements Expr<T> {}
record Add(Expr<Integer> left, Expr<Integer> right) implements Expr<Integer> {}

public class Eval {
    static int eval(Expr<Integer> e) {
        // Exhaustive switch over a sealed hierarchy — no default needed (Java 21 patterns)
        return switch (e) {
            case Lit<Integer> l -> l.value();
            case Add a -> eval(a.left()) + eval(a.right());
        };
    }

    public static void main(String[] args) {
        Expr<Integer> e = new Add(new Lit<>(2), new Add(new Lit<>(3), new Lit<>(4)));
        System.out.println(eval(e));   // 9
    }
}
```

> **Term — sealed type.** A class/interface (`sealed`) that restricts which classes may extend/implement it via a `permits` clause. Because the set of subtypes is closed and known to the compiler, a `switch` over them can be checked for **exhaustiveness** — the compiler proves every case is covered, so no `default` is required. Combined with generics and records, this gives algebraic-data-type-style modeling in Java.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Generics themselves are zero-cost at runtime** — erasure means a `List<String>` runs identically to a raw `List` plus the inserted casts (which are extremely cheap and often elided by the JIT).
- **Boxing is the real cost.** `List<Integer>` boxes every `int`. In hot loops or large collections this allocates millions of `Integer` objects, increasing GC pressure and cache misses. Mitigations: use primitive specializations (`int[]`, `IntStream`, `OptionalInt`, Eclipse Collections' `IntList`, fastutil's `IntArrayList`) for primitive-heavy data.
- **Inserted casts** are usually free; the JIT proves them redundant after the first checks. Don't micro-optimize them away.
- **Bridge methods** add a tiny indirection on polymorphic calls through erased supertypes; negligible in practice.

### 6.2 Correctness & concurrency

- Generics give **compile-time** safety only. They do **not** make code thread-safe; `List<String>` is no more synchronized than `List`.
- **Avoid raw types** — they silently turn off generic checking and invite heap pollution.
- **Never ignore unchecked warnings.** Each one is a place the compiler couldn't prove safety. Either restructure to remove it, or suppress *narrowly* with a comment explaining why it's safe.
- **`Class.cast` / checked collections** (`Collections.checkedList`) can turn latent heap pollution into immediate, localized failures — invaluable when debugging.

### 6.3 Security

- **Deserialization + generics:** type tokens and reflective generic resolution are common in serialization frameworks (Jackson, Gson). Polymorphic deserialization driven by attacker-controlled type info is a classic RCE vector. Use allow-lists; never deserialize untrusted data into open type hierarchies.
- Erasure means you **cannot rely on generics for runtime security checks** — a `List<String>` can hold anything if generics were bypassed. Validate at trust boundaries with explicit `Class.cast` or `instanceof`.

### 6.4 Memory

- Box objects (`Integer`, `Long`, `Double`) each carry object-header overhead (typically 12–16 bytes) plus the payload, versus 4–8 bytes for the primitive. A `List<Long>` of 10M elements is dramatically larger than a `long[]`.
- The `Integer` cache (`-128..127` by default, tunable via `-XX:AutoBoxCacheMax`) interns small values; `==` on cached `Integer`s can deceptively work for small numbers and fail for large — always use `.equals()` or unbox.

### 6.5 Observability & debugging

- `javap -v` reveals `Signature` attributes and bridge/synthetic methods — use it to understand erased shapes and overload collisions.
- Stack traces may show synthetic bridge frames; `Method.isBridge()`/`isSynthetic()` help you filter when reflecting.
- `ClassCastException` messages name the erased classes involved; combine with the inserted-cast model (§3.2) to locate the true source (often a write site far away).

### 6.6 Testing

- Compile your test/CI with `-Xlint:all -Werror` so unchecked/raw-type usage fails the build.
- Test generic APIs with multiple type arguments to catch accidental over-narrowing (missing wildcards that reject valid callers).

### 6.7 Production hardening checklist

- No raw types in new code.
- Zero unchecked warnings (or each suppressed with justification).
- Public collection-returning APIs use PECS appropriately so callers aren't blocked by invariance.
- Primitive-heavy paths avoid boxing (use primitive arrays/streams).
- Type tokens (`Class<T>` / super type tokens) used at trust boundaries for runtime safety.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Raw types (`List l`) | Disables generic checks; heap pollution | Use `List<X>` or `List<?>` |
| Ignoring unchecked warnings | Hides real safety gaps | Restructure or suppress narrowly |
| `new T[n]` workarounds via `(T[]) new Object[n]` then returning it | `ClassCastException` when callers expect a real `T[]` | Use `Array.newInstance(Class<T>, n)` |
| Over-using wildcards in return types | Forces callers into wildcard hell | Wildcards belong in *parameters*, not return types |
| `instanceof List<String>` | Illegal / meaningless after erasure | Check `instanceof List<?>` then cast |
| `var x = new ArrayList<>()` | Infers `ArrayList<Object>` | Spell out: `new ArrayList<String>()` |
| `var` hurting readability | Reader can't tell the type | Use `var` only when RHS makes the type obvious |
| Bounded type param where wildcard suffices | Leaks type param into API needlessly | Prefer wildcard if `T` appears only once |
| Comparing boxed `==` | Works for cached small values, fails otherwise | Use `.equals()` / unbox |

---

## 7. Advanced topics & deep internals

### 7.1 Capture conversion

When the compiler encounters a wildcard, it sometimes performs **capture conversion**: it invents a fresh, anonymous type variable (often shown in errors as `CAP#1`) to stand for the unknown type. This lets some operations on wildcards type-check internally.

```java
public static void swap(List<?> list, int i, int j) {
    // Can't write list.set(i, list.get(j)) directly — element type is unknown.
    swapHelper(list, i, j);  // delegate to capture the wildcard
}
private static <E> void swapHelper(List<E> list, int i, int j) {
    E tmp = list.get(i);     // now E is the captured type — set/get are consistent
    list.set(i, list.get(j));
    list.set(j, tmp);
}
```

The infamous error `required: CAP#1, found: Object` means the compiler captured a wildcard into a fresh variable it can't reconcile. The fix is usually the private generic-helper pattern above.

### 7.2 Why exceptions can't be generic

You cannot write `class MyException<T> extends Exception`. Exceptions are matched at runtime via `catch` clauses, which compare *erased* types. `catch (MyException<String>)` and `catch (MyException<Integer>)` would both be `catch (MyException)` after erasure — indistinguishable — so the language forbids it. A type variable may, however, be *thrown* if its bound is throwable (`<X extends Throwable>` ... `throw x`), enabling the "sneaky throws" trick.

### 7.3 Sneaky throws

Erasure lets you throw checked exceptions without declaring them:

```java
@SuppressWarnings("unchecked")
static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t;   // cast erased away — at runtime just rethrows t
}
// caller: sneakyThrow(new java.io.IOException()) — no checked-exception declaration needed!
```

The cast `(T) t` is erased, so no real check happens; `T` is inferred to satisfy `throws`, defeating checked-exception enforcement. Used by Lombok's `@SneakyThrows`. Use sparingly — it subverts the compiler's exception checking.

### 7.4 Overload collisions from erasure

Two methods whose signatures *erase to the same thing* cannot coexist:

```java
void process(List<String> l) {}
void process(List<Integer> l) {}   // COMPILE ERROR: both erase to process(List)
```

Workarounds: rename methods (`processStrings`/`processInts`) or change parameter shape.

### 7.5 Inability to overload, instantiate, or static-reference type params

- `new T()` — illegal (no constructor known; T unknown at runtime). Pass a `Supplier<T>` or `Class<T>` + `getDeclaredConstructor().newInstance()`.
- `T.class` — illegal. Pass `Class<T>` as a parameter (type token).
- **Static members can't use the class's type parameter:** `class Box<T> { static T x; }` is illegal because static state is shared across all instantiations, but each instantiation could have a different `T`. Static *generic methods* with their *own* type parameter are fine.

### 7.6 Wildcard capture helpers and `Class<?>` vs `Class<T>`

`Class<?>` is "the Class object of some unknown type"; `Class<T>` ties the class token to a known type variable, enabling `T newInstance` and `T cast`. Prefer `Class<T>` in APIs where the caller knows the type.

### 7.7 Intersection types and `var`

A conditional expression or a lambda target can produce an **intersection type** (`A & B`). With `var`, the compiler can infer such non-denotable types:

```java
var x = condition ? (CharSequence & Comparable<String>) "a" : "b";  // intersection
```

These types can't be written explicitly, which is one of the few cases where `var` infers something you literally couldn't name.

### 7.8 Project Valhalla (forward-looking, not yet GA as of 2024)

> **Term — Project Valhalla.** A long-running OpenJDK effort to add **value classes / value objects** ("primitive classes") and eventually **generic specialization over primitives** (so `List<int>` could store unboxed ints). The intended payoff is "codes like a class, works like an int" — eliminating boxing overhead in generics. As of early-2024 mainline JDKs this is *not* shipped; treat it as future work and verify against the current JDK before relying on it.

### 7.9 Reification — what Java deliberately gave up

Languages like C# **reify** generics (the runtime knows `List<int>` is distinct from `List<string>`, supports `new T[]`, `typeof(T)`, etc.) at the cost of generating specialized code and breaking pre-generics binary compatibility. Java chose erasure for **migration compatibility** — existing `.class` files and the existing `Collection` types kept working when generics arrived in Java 5. Understanding this tradeoff (compatibility & simplicity vs runtime power) explains every Java generics limitation.

### 7.10 Bounded wildcards and the "get/put principle"

An equivalent restatement of PECS from Naftalin & Wadler's *Java Generics and Collections*: **"the Get and Put Principle: use `extends` when you only get values out, `super` when you only put values in, and neither when you both get and put."** Same rule, different phrasing — useful when teaching.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Erasure vs reification

| Dimension | Erasure (Java) | Reification (C#/.NET) |
|---|---|---|
| Runtime type info | Lost (mostly) | Preserved |
| `new T[]`, `T.class`, `typeof(T)` | Illegal | Legal |
| Backward compatibility | Excellent (pre-generics code works) | Required CLR changes |
| Code bloat | None (one erased class) | Possible specialization |
| Primitive specialization | No (boxing) | Yes (`List<int>` unboxed) |
| Bridge methods / heap pollution | Yes | No |

### 8.2 Wildcard vs explicit type parameter

| Use a **type parameter `<T>`** when… | Use a **wildcard `?`** when… |
|---|---|
| The type appears in multiple places and must be the *same* | The type appears once and you don't care about its name |
| The method returns the type | The parameter is only a producer/consumer |
| You need to relate parameter and return types | You want maximum caller flexibility on a parameter |

Rule of thumb: if a type parameter appears **only once** in the method signature, replace it with a wildcard.

### 8.3 `var` vs explicit type

| Use `var` when… | Avoid `var` when… |
|---|---|
| RHS makes the type obvious (`new ArrayList<String>()`) | RHS is a method call with an unclear return type |
| Long, noisy generic types (`Map.Entry<String, List<Integer>>`) | The inferred type would surprise the reader |
| Local scope, short lifetime | You want the declared type to differ from the runtime type (interface vs impl) |

### 8.4 Boxing collection vs primitive collection

| Need | Choose |
|---|---|
| General-purpose, modest size | `List<Integer>` (JDK) |
| Hot path / huge primitive data | `int[]`, `IntStream`, fastutil/Eclipse Collections primitive lists |
| Nullable single primitive | `Optional<Integer>` or `OptionalInt` (avoids boxing) |

### 8.5 Use-when / avoid-when summary

- **Use generics** for any reusable container, algorithm, or API parameterized by a caller-chosen type. Always — they're free safety.
- **Use bounded type params** when you need to call methods of the bound on the type variable.
- **Use wildcards** to make parameters flexible (PECS).
- **Avoid raw types** except when interoperating with legacy/reflective code, and isolate that.
- **Avoid pushing generics into static state** or relying on runtime type-argument info (it isn't there).

---

## 9. Failure modes & debugging

### 9.1 `ClassCastException` from a hidden cast

**Symptom:** `CCE` on a line that contains no visible cast (e.g., `String s = list.get(0);`).
**Cause:** heap pollution — a wrong-typed object got into a parameterized collection via raw types or unchecked ops; the compiler-inserted cast (§3.2) fails on read.
**Diagnose:** read the CCE's "cannot be cast" classes; search for raw types and unchecked warnings near the *write* sites; wrap the collection in `Collections.checkedList(list, String.class)` to fail fast at the offending `add`.

### 9.2 "incompatible types: List<String> cannot be converted to List<Object>"

**Cause:** assuming covariance. **Fix:** use `List<? extends Object>` for read-only access, or rethink the API.

### 9.3 "required: CAP#1, found: Object" (capture errors)

**Cause:** trying to do an unsafe operation on a wildcard type. **Fix:** the private generic-helper pattern (§7.1) to capture the wildcard.

### 9.4 "name clash: both methods have same erasure"

**Cause:** two overloads erase to the same signature (§7.4). **Fix:** rename or change parameters.

### 9.5 "generic array creation"

**Cause:** `new T[n]` or `new List<String>[n]`. **Fix:** `Array.newInstance(Class<T>, n)` with an unchecked cast, or use a `List<List<String>>`.

### 9.6 Unchecked-warning floods

**Cause:** raw types or unsafe casts in legacy code. **Diagnose:** `javac -Xlint:unchecked,rawtypes`. **Fix:** parameterize types; suppress only at the narrowest scope with justification.

### 9.7 Surprising `var` type

**Symptom:** `var list = new ArrayList<>()` won't accept `String`-specific code, or you assigned an interface intent but got the impl type. **Fix:** specify the diamond's argument or avoid `var` here.

### 9.8 Real-world incident pattern: boxing-induced GC pressure

A common production story: a hot analytics path used `List<Long>` for billions of IDs; each `Long` allocation drove the young-generation GC to thrash, tanking throughput. **Diagnosis:** allocation profiler / `-verbose:gc` showing massive `java.lang.Long` allocation. **Fix:** switch to `long[]`/`LongStream` or a primitive collection — order-of-magnitude memory reduction and GC relief.

### 9.9 Tools

- `javap -v` / `-s` — inspect erased signatures, bridges, `Signature` attributes.
- `-Xlint:unchecked,rawtypes` — surface unsafe generics.
- `Collections.checkedXxx` — fail-fast heap-pollution detection.
- Allocation/GC profilers (async-profiler, JFR) — find boxing hotspots.

---

## 10. Interview drill

**Q1. What is type erasure and why does Java use it?**
*Model answer:* Erasure is the compiler removing generic type information at compile time — type variables become their leftmost bound (or `Object`), parameterized types lose their arguments, and casts are inserted where needed. Java chose it for **migration compatibility**: existing pre-generics bytecode and the existing collection classes had to keep working when generics arrived in Java 5, and erasure means a generic class compiles to a single class with no runtime overhead.
*Follow-ups:* (a) *What survives erasure?* The `Signature` attribute (metadata) preserves declared generic types for reflection/tooling; instructions don't. (b) *What's the cost of this choice?* No `new T[]`, no `T.class`, no `instanceof List<String>`, overload collisions, heap pollution, and boxing for primitives. (c) *How does C# differ?* It reifies generics — runtime knows `List<int>` vs `List<string>` and supports `new T[]`, at the cost of CLR support and specialization.

**Q2. Explain PECS.**
*Model answer:* "Producer Extends, Consumer Super." If a parameter produces `T`s you read, use `? extends T`; if it consumes `T`s you write, use `? super T`; if both, use exact `T`. `Collections.copy(List<? super T> dest, List<? extends T> src)` is the canonical example.
*Follow-ups:* (a) *Why can't you add to a `List<? extends Number>`?* The actual element type is unknown (could be `List<Integer>`), so adding a `Double` would be unsafe; the compiler forbids all adds except `null`. (b) *What can you read from `List<? super Integer>`?* Only `Object` — the element type could be any supertype. (c) *Where do wildcards belong — params or returns?* Params; wildcards in return types force callers into wildcard handling.

**Q3. Why are Java generics invariant when arrays are covariant?**
*Model answer:* Invariance is required for compile-time soundness — if `List<String>` were a `List<Object>`, you could add an `Integer` and later get a `ClassCastException` on read. Arrays were made covariant in Java 1.0 for polymorphic array routines before generics existed, trading compile-time safety for a runtime `ArrayStoreException`. Generics learned from that mistake.
*Follow-ups:* (a) *How do you opt into covariance with generics?* Use-site wildcards (`List<? extends T>`). (b) *Demonstrate the array hole.* `Object[] a = new String[1]; a[0] = 42;` → `ArrayStoreException`. (c) *Does Java have declaration-site variance?* No — only use-site (wildcards); Kotlin/Scala have both.

**Q4. What are bridge methods?**
*Model answer:* Synthetic methods the compiler generates so an override with a generic-derived signature still overrides the erased supertype method. E.g., `Money implements Comparable<Money>` gets a synthetic `compareTo(Object)` that casts and delegates to `compareTo(Money)`. They're marked `ACC_BRIDGE | ACC_SYNTHETIC`.
*Follow-ups:* (a) *How do you detect one reflectively?* `Method.isBridge()`. (b) *Can a bridge throw?* Yes — its internal cast throws `ClassCastException` under heap pollution. (c) *Why are they needed?* Without them, the erased interface method `compareTo(Object)` wouldn't be overridden and polymorphic dispatch would break.

**Q5. What's heap pollution and how does it arise?**
*Model answer:* A parameterized-type reference pointing to an object inconsistent with that type (e.g., a `List<String>` containing an `Integer`). It arises from raw types, unchecked casts, or unsafe generic varargs, because erasure prevents the runtime from enforcing type arguments. The resulting `ClassCastException` typically fires at a *read* site (the inserted cast), far from the write.
*Follow-ups:* (a) *How to detect early?* `Collections.checkedList` fails fast at the bad `add`. (b) *Role of `@SafeVarargs`?* It's your assertion that a generic-varargs method doesn't pollute (doesn't store/leak the array). (c) *Compiler help?* Unchecked warnings flag exactly these spots.

**Q6. Why can't you write `new T[]` or `T.class`?**
*Model answer:* `T` is non-reifiable — erased to `Object` (or its bound) at runtime — so there's no concrete component type to allocate an array of, and no `Class` object representing `T`. Workarounds: `Array.newInstance(Class<T>, n)` with an unchecked cast, and passing a `Class<T>` type token.
*Follow-ups:* (a) *Why is `new T()` illegal too?* No constructor is known at runtime. (b) *How does Guava's `TypeToken` capture `List<String>`?* Super type token: anonymous subclass + `getGenericSuperclass()`. (c) *Why is `instanceof List<String>` illegal?* The type argument doesn't exist at runtime; only `List<?>`/raw `List` checks are allowed.

**Q7. When should you use a type parameter vs a wildcard? (senior-signal)**
*Model answer:* Use a named type parameter `<T>` when the type appears in multiple positions that must agree, or when you return it, or when you relate inputs to outputs. Use a wildcard when the type appears once and you only produce or consume. The heuristic: a type parameter used exactly once should be a wildcard. This keeps APIs flexible (PECS) without leaking unnecessary type parameters that complicate callers.
*Follow-ups:* (a) *Tradeoff of over-wildcarding returns?* Forces callers into capture/wildcard handling — avoid. (b) *Example of a relating signature?* `<T> void copy(List<? super T> d, List<? extends T> s)` ties src and dst through `T`. (c) *When does a wildcard beat `Object`?* When you still want to preserve some relationship (`? extends Number` lets you read `Number`, `Object` doesn't).

**Q8. How do you decide between `List<Integer>` and `int[]`/`IntStream`? (senior-signal)**
*Model answer:* It's a memory/throughput vs ergonomics tradeoff. `List<Integer>` is general and composable but boxes every element (object header + pointer-chasing + GC pressure). For hot paths or large primitive datasets, `int[]`/`IntStream`/primitive collections avoid boxing and are far more memory- and cache-efficient. Profile allocation/GC; if boxing dominates, switch.
*Follow-ups:* (a) *Why does erasure force boxing?* Type parameters must be reference types; there's no `List<int>`. (b) *What might change this?* Project Valhalla's value types / generic specialization (not GA yet). (c) *Subtle `==` bug with boxing?* The `Integer` cache (`-128..127`) makes `==` accidentally work for small values and fail for large — always `.equals()`/unbox.

**Q9. Why can't exceptions be generic, and what's "sneaky throws"?**
*Model answer:* `catch` clauses match on erased types, so `catch (MyEx<String>)` and `catch (MyEx<Integer>)` would be indistinguishable; thus generic exception *types* are forbidden. However, a method can throw a type variable bounded by `Throwable`, and because the cast is erased, you can rethrow a checked exception without declaring it — "sneaky throws" (Lombok's `@SneakyThrows`).
*Follow-ups:* (a) *Is sneaky throws safe?* It subverts checked-exception checking — use sparingly. (b) *Why does the cast not fail?* `(T) t` erases away; at runtime it just rethrows the original. (c) *Legit use of throwing a type var?* Generic frameworks that re-propagate caller exceptions transparently.

**Q10. When would you choose erasure-style generics over reified generics if you were designing a language? (senior-signal)**
*Model answer:* Erasure if backward/binary compatibility and implementation simplicity dominate (Java's situation in 2004 — millions of lines of pre-generics code and existing collections). Reification if runtime type power (specialization, `new T[]`, reflection over arguments) and primitive performance matter more and you can afford runtime/compiler complexity (C#/.NET, which controlled its whole runtime). The deciding factors are ecosystem migration cost vs runtime capability requirements.
*Follow-ups:* (a) *Can you get both?* Partially — Valhalla aims to add specialization atop erasure. (b) *What runtime features does reification unlock?* `typeof(T)`, `new T[]`, distinct `List<int>` vs `List<string>`. (c) *Cost of reification?* Code bloat from specialization and a runtime that must understand generics.

**Q11. Walk through what happens to `List<String> l = new ArrayList<>(); l.add("x"); String s = l.get(0);` through compilation.**
*Model answer:* At type-check, the compiler verifies `add(String)` and that `get` returns `String`. After erasure, `ArrayList<String>` → `ArrayList`, `add(String)` → `add(Object)`, `get()` returns `Object`. The compiler inserts `(String)` on the `get` result: `String s = (String) l.get(0);`. Bytecode has no `<String>` in instructions, only the `Signature` attribute records it.
*Follow-ups:* (a) *Where could a CCE occur?* At that inserted cast, if heap pollution put a non-String in. (b) *Does the diamond cost anything?* No — pure compile-time inference. (c) *Does `l.getClass()` reveal `String`?* No — it's `ArrayList.class`.

**Q12. Explain capture conversion with the `swap(List<?>)` example.**
*Model answer:* You can't `list.set(i, list.get(j))` on a `List<?>` because the element type is unknown and the compiler can't prove the `set` argument matches. Delegating to a private `<E> void swapHelper(List<E>, ...)` makes the compiler *capture* the wildcard into a fresh type variable `E`, so `get` and `set` share a consistent type and type-check.
*Follow-ups:* (a) *What is `CAP#1`?* The compiler's name for a captured wildcard in error messages. (b) *Why is the helper needed?* It binds the unknown type to a single named variable for the body. (c) *Could you avoid it?* Sometimes via `Collections.swap`, which already encapsulates this.

---

## 11. Glossary

- **Autoboxing / boxing / unboxing** — automatic conversion between primitives and their wrapper objects (`int` ↔ `Integer`). Each box is a heap allocation.
- **Bound (type bound)** — a constraint on a type parameter: upper (`extends`) or, for wildcards, lower (`super`).
- **Bridge method** — synthetic compiler-generated method that makes a generic override correctly override the erased supertype method.
- **Capture conversion** — the compiler replacing a wildcard with a fresh anonymous type variable so operations can type-check.
- **Class token** — a `Class<T>` argument passed at runtime to recover type info lost to erasure.
- **Compile time / runtime** — when `javac` runs vs when the JVM runs the bytecode.
- **Contravariance** — subtyping reversed in a type constructor; via `? super T`.
- **Covariance** — subtyping preserved; arrays (always) and `? extends T` wildcards.
- **CRTP (Curiously Recurring Template Pattern)** — `class X extends Base<X>`, used for self-typed fluent builders.
- **Declaration-site variance** — variance declared on the type itself (`out`/`in` in Kotlin/Scala); Java lacks it.
- **Diamond operator (`<>`)** — Java 7+ syntax letting the compiler infer constructor type arguments.
- **Erasure** — removal of generic type info at compile time, replacing type variables with bounds and inserting casts.
- **Generic class/interface/method** — one declaring type parameters.
- **Heap pollution** — a parameterized reference pointing to an object inconsistent with that parameter type.
- **Intersection type** — `A & B`; a type that is simultaneously several types.
- **Invariance** — no induced subtyping; Java generics are invariant.
- **Liskov Substitution Principle (LSP)** — subtypes must be usable wherever supertypes are expected.
- **Nominal typing** — compatibility by declared name, not shape (Java's default).
- **PECS** — Producer Extends, Consumer Super (wildcard usage mnemonic).
- **Parameterized type** — a generic type with concrete arguments, e.g. `List<String>`.
- **Primitive type** — `int`, `boolean`, etc.; not objects, can't be type arguments.
- **Project Valhalla** — OpenJDK effort to add value types & generic specialization (not GA as of 2024).
- **Raw type** — a generic type used without type arguments (`List`); disables generic checks.
- **Recursive (self-referential) bound** — `T extends Comparable<T>`.
- **Reference type** — classes, interfaces, arrays, enums, records, type variables; can be `null`.
- **Reifiable / non-reifiable** — whether full type info exists at runtime.
- **Reification** — preserving generic type info at runtime (C#); Java does not.
- **Sealed type** — a type with a closed `permits` set of subtypes, enabling exhaustive switches.
- **Signature attribute** — class-file metadata preserving original generic signatures for reflection/tooling.
- **Static / dynamic typing** — type checking at compile time vs runtime.
- **Structural typing** — compatibility by shape (TypeScript/Go).
- **Subtype / supertype** — the "is-a" relationship between types.
- **Super type token** — anonymous-subclass trick (`new TypeRef<List<String>>(){}`) to capture a parameterized type at runtime.
- **Target typing** — inference that uses the expected type at the use site.
- **Type argument** — the concrete type supplied for a type parameter (`String` in `List<String>`).
- **Type erasure** — see *Erasure*.
- **Type inference** — the compiler deducing type arguments / `var` types.
- **Type parameter / type variable** — the placeholder `T` in a generic declaration.
- **Type system** — the rules classifying values into types and governing legal operations.
- **Use-site variance** — variance chosen where a type is used, via wildcards (Java's model).
- **`var`** — Java 10+ local-variable type inference keyword.
- **`@SafeVarargs`** — annotation asserting a generic-varargs method doesn't cause heap pollution.
- **Variance** — how subtyping of components relates to subtyping of containers.
- **Wildcard (`?`)** — an unknown type argument; bounded by `extends`/`super` or unbounded.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Java is statically, strongly, mostly nominally typed.** Two type worlds: **primitives** (can't be type args) and **references**.
- **Generics = compile-time-only contracts.** Checked at compile time; **erased** at runtime (T → bound/Object, casts inserted).
- **PECS:** Producer `extends`, Consumer `super`. Wildcard if a type param appears once.
- **Variance:** generics **invariant**; arrays **covariant** (→ `ArrayStoreException`). Opt into variance with wildcards (use-site only in Java).
- **Reifiable** (runtime-known): primitives, raw types, `List<?>`, non-generic classes. **Non-reifiable**: `List<String>`, bounded wildcards.
- **Erasure forbids:** `new T[]`, `T.class`, `new T()`, `instanceof List<String>`, generic exceptions, overloads with same erasure, static use of class type param.
- **Workarounds:** `Class<T>` token + `cast`; super type token for parameterized types; `Array.newInstance` for generic arrays.
- **Bridge methods** preserve polymorphism; **heap pollution** + inserted casts cause far-away `ClassCastException`s.
- **Boxing** is the real perf cost — prefer `int[]`/`IntStream`/primitive collections on hot paths. `Integer` cache `-128..127` (tunable `-XX:AutoBoxCacheMax`) breaks `==`.
- **`var`** (Java 10+): locals only; `new ArrayList<>()` with `var` infers `Object` — specify the argument.
- **Hardening:** no raw types; zero unchecked warnings; `-Xlint:all -Werror`; PECS in params, not returns; `Collections.checkedXxx` to fail fast.
- **Sealed + generics + records** (17/21) enable ADT-style exhaustive switches.
- **Erasure rationale:** migration/binary compatibility (vs C# reification, which gives runtime power at runtime/complexity cost).

### Self-test (no answers — recall actively)

1. Trace `List<String> l = new ArrayList<>(); String s = l.get(0);` from source through erasure to bytecode. Where exactly is the inserted cast, and under what condition could it throw?
2. Write the signature of a method that copies from one list to another using PECS, and explain why each wildcard is `extends` vs `super`.
3. Why is `List<String>` not a subtype of `List<Object>`, but `String[]` is usable as `Object[]`? What runtime exception does the array case risk, and why did generics choose differently?
4. Implement a generic factory that creates a `T[]` of a given size, and explain why your unchecked cast is actually safe.
5. Explain capture conversion using `swap(List<?> list, int i, int j)` and the private-helper pattern. What does `CAP#1` mean in an error?
6. Why can't `class MyException<T> extends Exception` exist, yet you *can* throw a type variable? Sketch "sneaky throws."
7. Give two distinct reasons a `List<Integer>` can be slower and more memory-hungry than an `int[]`, and name the JDK feature that may eventually close the gap.

# Class Loading & ClassLoaders

> JVM Internals & Garbage Collection — a definitive engineering-handbook chapter.

---

## 1. Overview & where it fits

### What it is

**Class loading** is the JVM mechanism that takes a class's binary representation — the bytes of a `.class` file (the JVM's compiled, platform-independent representation of a Java type, produced by `javac` or any other compiler that targets the JVM) — and turns it into a live, usable `java.lang.Class` object inside a running process. Until a type has been loaded, linked, and initialized, the JVM cannot create instances of it, call its static methods, or read its static fields.

A **ClassLoader** is the Java object responsible for *finding* and *defining* classes. It is the bridge between "a name like `com.acme.Order`" and "an in-memory `Class<Order>` object." Every `Class` object in the JVM remembers which ClassLoader defined it; that fact is more important than it first appears (see §3.6 — *class identity = (name, defining loader)*).

### The problem it solves

C and C++ resolve symbols at link time into a single static or dynamically-loaded binary. The JVM instead defers as much as possible to **runtime**: classes are located, verified, and wired together *lazily*, *on demand*, and *by name*. This buys the platform several capabilities that are otherwise hard:

- **Dynamic extensibility** — load code that did not exist when the program started (plugins, JDBC drivers, app-server-deployed web apps, OSGi bundles, scripting).
- **Isolation** — run two versions of the same library in one process without them colliding, because each lives under a different loader.
- **Safety** — verify untrusted bytecode before it ever runs (the bytecode verifier), and historically sandbox it via the (now-removed) SecurityManager.
- **Lazy startup** — don't pay to load and initialize a class until something actually touches it.

### When you reach for it

You consciously interact with class loading when you:
- Build a **plugin system** or **modular app** where modules ship as separate jars and may be added/removed at runtime.
- Build or operate an **application server / servlet container** (Tomcat, Jetty, JBoss/WildFly) — each deployed app gets its own loader.
- Implement **hot reload** / live class redefinition (dev tooling, JRebel, DCEVM, Spring Devtools).
- Debug **`ClassNotFoundException`, `NoClassDefFoundError`, `LinkageError`, `ClassCastException` "between identical classes,"** or **"jar hell."**
- Tune or diagnose **Metaspace** (the native-memory region that holds class metadata) and **class unloading**.
- Do **bytecode generation** at runtime (Hibernate/ByteBuddy/CGLIB proxies, mocking frameworks, Spring AOP).

### The one-paragraph mental model

> A ClassLoader is a *namespace* and a *byte source*. When some code references a type by name, the JVM asks the loader that owns the requesting class to produce a `Class` object for that name. By default, every loader first asks its **parent** (parent-delegation) and only loads the class itself if no ancestor could. The resulting `Class` is uniquely keyed by the pair **(fully-qualified name, the loader that *defined* it)** — so the "same" class loaded by two different loaders yields two distinct, incompatible runtime types. The class then goes through a strict lifecycle — **load → verify → prepare → resolve → initialize** — most of it lazy, with initialization deferred until first active use. Class metadata lives in **Metaspace** (native memory, not the Java heap), and a class can be **unloaded** only when its entire defining loader becomes unreachable.

---

## 2. Foundations from first principles

### 2.1 What is a class, really, at runtime?

When you write:

```java
package com.acme;
public class Order {
    public static final int MAX_ITEMS = 100;
    private long id;
    public long getId() { return id; }
}
```

`javac` compiles this to `com/acme/Order.class` — a binary file in the **class file format** (a strictly specified layout: a magic number `0xCAFEBABE`, version, the **constant pool**, access flags, fields, methods as bytecode, and attributes). The **constant pool** is a per-class table of symbolic references: strings, class names, field/method names+types, etc., referenced by index from the bytecode. Crucially, references in the constant pool are **symbolic** — `Order` doesn't contain a pointer to `String`; it contains the *name* `java/lang/String`, to be resolved later.

At runtime the JVM turns that file into:
- A block of metadata in **Metaspace** (the `InstanceKlass` in HotSpot's C++ internals — see §7).
- A mirror object on the Java heap: the `java.lang.Class` instance you get from `Order.class` or `obj.getClass()`.

### 2.2 What is a ClassLoader (the Java abstraction)?

`java.lang.ClassLoader` is an abstract class. Its core responsibilities:
1. **Find** the bytes for a class name (`findClass`, typically by reading a file, jar entry, or network resource).
2. **Define** the class — call the protected, `final`, native-backed `defineClass(name, bytes, off, len, protectionDomain)`, which hands the bytes to the JVM to parse, verify, and create the `Class`. *Only `defineClass` makes a loader the **defining loader** of a class.*
3. **Delegate** to a parent loader before defining anything itself (the default policy in `loadClass`).

Key terms you must internalize early:

- **Defining loader:** the loader whose `defineClass` actually created the `Class`. This is what `Class.getClassLoader()` returns and what participates in class identity.
- **Initiating loader:** any loader whose `loadClass` was *called* for that name — even if it delegated the real work elsewhere. The same class can have many initiating loaders but exactly one defining loader.

### 2.3 The constant-pool / symbolic-reference idea (why loading is lazy)

Because references are symbolic strings, the JVM can load `Order` without yet loading `String`, `List`, or any type `Order` mentions. Those get resolved only when a bytecode instruction that uses them actually executes (or earlier, if the JVM chooses eager resolution). This is the foundation of **lazy loading**: you only pay for what you touch.

### 2.4 Names, packages, and binary names

A **binary name** is the JVM-internal fully-qualified name, e.g. `com.acme.Order`, `com.acme.Order$Inner` (nested), `[Lcom.acme.Order;` (array of Order). The loader is asked for binary names. Arrays are special: array classes are *created by the JVM itself*, not by reading bytes — but they are associated with the loader of their element type.

### 2.5 Why "by name + by loader" instead of just "by name"?

If identity were just the name, you could never load two versions of `commons-lang` in one JVM, and an app server could never isolate web apps. By making the **defining loader part of the identity**, the JVM gives you namespaces: `AppA`'s `org.foo.Widget` and `AppB`'s `org.foo.Widget` are genuinely different types that cannot be assigned to each other. This is the single most important and most surprising idea in the whole topic.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle, then the loader hierarchy and delegation, then identity.

### 3.1 The lifecycle: load → link (verify → prepare → resolve) → initialize

The JVM specification (JVMS) defines the lifecycle in three top-level phases, with **linking** subdivided into three:

```
              ┌──────────────── Linking ────────────────┐
  Loading  →  Verify   →   Prepare   →   Resolve   →   Initialization   →  (Unloading)
```

Each is described below in execution order.

#### Phase 1 — Loading

**Goal:** obtain the binary bytes for a binary name and create a tentative `Class`.

Steps under the hood:
1. Some trigger requests class `C` (e.g. a `new`, a static access, reflection, or another class being resolved).
2. The JVM asks the appropriate **initiating loader** for `C` by invoking its `loadClass(name)`.
3. `loadClass` (default impl) **first checks** whether `C` is already loaded *by this loader* (`findLoadedClass` — native cache lookup). If so, return it.
4. If not, it **delegates to the parent** loader's `loadClass`. The parent recurses the same way up to the bootstrap loader.
5. If no ancestor found it, *this* loader calls its own `findClass`, which fetches bytes and calls `defineClass`.
6. `defineClass` invokes the native JVM to parse the class file: check magic number, version compatibility, well-formedness, build the constant pool, and create the `InstanceKlass`/`Class`. **Superclasses and superinterfaces are loaded here** (recursively) because the class file names them and the JVM needs the hierarchy.

Loading constraints enforced: a class's defining loader must be consistent for supertypes (loader constraints, see §3.6.1). If two classes claim a name that maps to different actual classes across loaders in incompatible ways, you get a `LinkageError`.

#### Phase 2a — Verification

**Goal:** prove the bytecode is well-formed and type-safe *before* execution, so the interpreter/JIT can trust it.

The **bytecode verifier** checks, among many things:
- The constant pool is internally consistent.
- Every instruction operates on operand types it expects (no treating an `int` as a reference).
- The operand stack never overflows/underflows and has consistent types at every merge point (this is the expensive part — historically iterative data-flow analysis; since class file version 50.0 / Java 6, classes carry **`StackMapTable`** attributes so verification is a faster single pass — *type checking* vs. the old *type inference*).
- `final` classes aren't subclassed; `final` methods aren't overridden; access rules hold.
- Control flow stays within the method; no jumps into the middle of instructions.

If verification fails: **`VerifyError`** (a subclass of `LinkageError`). Verification can be partially deferred and is per-method lazy in practice, but conceptually it's a link-time guarantee. You can (dangerously) weaken it with `-Xverify:none` / `-noverify` (deprecated and being removed; do not use in production).

#### Phase 2b — Preparation

**Goal:** allocate storage for **static fields** and set them to **default values** (not the programmer's initializers).

- `static int count;` → set to `0`.
- `static boolean ready;` → `false`.
- `static Object ref;` → `null`.
- `static final int MAX = 100;` with a **compile-time constant** → may be assigned its constant value here, and is often inlined into callers at compile time (constant folding), which has surprising consequences (see §9 "stale constants").

No Java code runs in preparation. No user-written static initializer executes yet.

#### Phase 2c — Resolution

**Goal:** turn symbolic references in the constant pool into direct references (actual pointers/offsets).

- A symbolic ref like "method `getId` of class `com/acme/Order` with descriptor `()J`" is resolved to a concrete method handle/vtable index.
- Resolution may trigger **loading** of referenced classes (a referenced type that isn't loaded yet gets loaded now).
- Resolution is **lazy by default** in HotSpot: a reference is resolved the first time the corresponding bytecode (`invokevirtual`, `getstatic`, `new`, etc.) executes. The JVMS permits eager or lazy resolution; HotSpot chooses lazy.
- Failures here surface as `NoSuchMethodError`, `NoSuchFieldError`, `IllegalAccessError`, `AbstractMethodError`, or `NoClassDefFoundError` — all `LinkageError`/`IncompatibleClassChangeError` family — typically when a class was compiled against one version of a dependency and run against another.

#### Phase 3 — Initialization

**Goal:** run the class's **static initializer** `<clinit>` — i.e., static field initializers and `static { ... }` blocks — exactly once, in textual order.

This is the phase with the most precisely specified semantics, because it must be **thread-safe and exactly-once**:

The JVM holds a per-class **initialization lock**. The state machine for a class is roughly: *uninitialized → being-initialized (by thread T) → initialized | erroneous*.

Initialization triggers — the JVMS calls these the events that cause **"active use"**:
1. `new` (instance creation), or `newarray`/`anewarray`? — note: creating an array of a type does **not** initialize the element type.
2. Invoking a **static method** of the class.
3. Accessing or assigning a **static field** that is *not* a compile-time constant (constants are inlined and don't trigger).
4. Reflection that initializes (`Class.forName(name)` with default `initialize=true`).
5. Initializing a **subclass** triggers initialization of its **superclass** first (but not superinterfaces, unless the interface has default methods — Java 8+ nuance).
6. The class designated as the program's entry point (the one with `main`).
7. (Java 7+) certain `invokedynamic`/`MethodHandle` resolutions.

Steps inside initialization (simplified JVMS §5.5):
1. Acquire the init lock for `C`.
2. If another thread is initializing `C`, **block** until done. If the *current* thread is already initializing `C` (recursive init), proceed without blocking (this is how cyclic static init can observe partially-initialized classes — a real footgun).
3. If `C` is already initialized, release and return.
4. If `C` is in the *erroneous* state, throw **`NoClassDefFoundError`**.
5. Mark `C` "being initialized by this thread," release lock.
6. If `C` is a class (not interface) and its **superclass** isn't initialized, recursively initialize it (and superinterfaces with default methods).
7. Execute `<clinit>` (static initializers).
8. On success: lock, mark **initialized**, notify waiters, release.
9. On exception in `<clinit>`: mark **erroneous**; wrap the original throwable in **`ExceptionInInitializerError`** and throw it (errors propagate as-is). Future touches throw `NoClassDefFoundError` — *this is why a class can "mysteriously" throw `NoClassDefFoundError` later even though its bytes exist: it failed init once.*

### 3.2 Triggering vs. non-triggering — the subtle table

| Action | Loads class? | Initializes class? |
|---|---|---|
| `Order.class` literal | yes (if not loaded) | **no** |
| `Class.forName("com.acme.Order")` | yes | **yes** (default) |
| `Class.forName("com.acme.Order", false, cl)` | yes | **no** |
| `new Order()` | yes | yes |
| `Order.staticMethod()` | yes | yes |
| read `Order.NON_CONSTANT_STATIC` | yes | yes |
| read `Order.COMPILE_TIME_CONSTANT` | **no** (inlined) | **no** |
| declaring a field/var of type `Order` | no | no |
| `Order[] a = new Order[5];` | yes (element type loaded) | **no** |
| accessing a static field via a subclass that *declares* it in the parent | initializes the **declaring** (parent) class only |

### 3.3 The ClassLoader hierarchy

Modern JVMs (Java 9+ with the module system) have this default chain:

```
                ┌─────────────────────────────────────────┐
                │  Bootstrap ClassLoader  (null in Java)   │  ← java.base etc. (core JDK)
                └─────────────────────────────────────────┘
                                   ▲ parent
                ┌─────────────────────────────────────────┐
                │  Platform ClassLoader                    │  ← rest of JDK modules
                │  (PlatformClassLoader; was "ext" pre-9)  │
                └─────────────────────────────────────────┘
                                   ▲ parent
                ┌─────────────────────────────────────────┐
                │  Application/System ClassLoader          │  ← your app's classpath/modulepath
                │  (AppClassLoader)                        │
                └─────────────────────────────────────────┘
                                   ▲ parent
                ┌─────────────────────────────────────────┐
                │  (your custom loaders, plugin loaders…)  │
                └─────────────────────────────────────────┘
```

- **Bootstrap ClassLoader:** written in C++ inside the JVM, has **no Java object** — `String.class.getClassLoader()` returns **`null`**. Loads the core platform classes. Pre-Java-9 it loaded `rt.jar`; since Java 9 it loads a subset of JDK modules (`java.base`, etc.) from the runtime image.
- **Platform ClassLoader (Java 9+):** replaced the old **Extension ClassLoader** (which loaded `jre/lib/ext`, removed in Java 9). Loads the remaining platform/JDK modules. Accessible via `ClassLoader.getPlatformClassLoader()`.
- **Application (System) ClassLoader:** loads classes from `-classpath`/`CLASSPATH`/`--module-path` — your application code and its dependency jars. Accessible via `ClassLoader.getSystemClassLoader()`. It is an instance of an internal class (e.g. `jdk.internal.loader.ClassLoaders$AppClassLoader` in modern JDKs; pre-9 it was `sun.misc.Launcher$AppClassLoader`).

Pre-Java-9 chain (still worth knowing for legacy systems): **Bootstrap → Extension (`sun.misc.Launcher$ExtClassLoader`) → Application (`AppClassLoader`)**.

### 3.4 The parent-delegation model

Default algorithm of `ClassLoader.loadClass(name, resolve)`:

```java
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {     // per-name lock (Java 7+ parallel-capable)
        Class<?> c = findLoadedClass(name);          // 1. already loaded by me?
        if (c == null) {
            try {
                if (parent != null) c = parent.loadClass(name, false);  // 2. ask parent
                else c = findBootstrapClassOrNull(name);                // bootstrap
            } catch (ClassNotFoundException ignored) { }
            if (c == null) c = findClass(name);       // 3. only now load it myself
        }
        if (resolve) resolveClass(c);
        return c;
    }
}
```

**Why delegate to parent first?**
1. **Safety:** a user can't override `java.lang.Object` or `java.lang.String` with a malicious version — the bootstrap loader always wins for core classes.
2. **Uniqueness/consistency:** core types are loaded once by the bootstrap loader, so everyone shares the *same* `String` class. Without delegation you'd get multiple incompatible `String` types.

**When delegation is intentionally broken or inverted:**
- **Web/servlet containers** invert delegation for *web-app* classes: a web app's loader checks **itself first** (so the app's bundled libs win over the container's), but still delegates JDK/Java EE API classes to the parent. This is the **"parent-last" / child-first"** policy and is explicitly endorsed by the Servlet spec for WEB-INF/classes and WEB-INF/lib.
- **OSGi** replaces the tree entirely with a *graph* of bundle loaders wired by package imports/exports.
- **Thread Context ClassLoader (TCCL)** is the escape hatch for the "parent can't see child" problem (see §3.5 / §7.4).

### 3.5 The SPI / "parent can't see child" problem and the Thread Context ClassLoader

A classic tension: **JNDI, JDBC, JAXP and other SPIs** ship their *API/factory* classes in the JDK (loaded by bootstrap/platform), but the *implementation* (e.g. a specific JDBC driver) lives on the application classpath (loaded by AppClassLoader, a **child**). Under strict delegation, the bootstrap-loaded factory cannot see the child-loaded implementation — parents can't reach down.

The workaround is the **Thread Context ClassLoader (TCCL):** a per-thread reference (`Thread.currentThread().getContextClassLoader()`) that platform code uses to load SPI implementations, deliberately bypassing the normal parent chain. By default a thread inherits its parent thread's TCCL, and the main thread's is the AppClassLoader. Frameworks set/reset it around calls. (Java's `ServiceLoader` uses the TCCL by default.)

### 3.6 Class identity = (binary name, defining loader)

Two `Class` objects are the **same runtime type** iff they have the **same binary name** *and* the **same defining loader**. Consequences:

- The "same" jar loaded by two sibling loaders yields two incompatible `Foo` types. Casting/assigning between them throws **`ClassCastException`** with the infamous message *"`com.acme.Foo` cannot be cast to `com.acme.Foo`"* — same name, different loaders.
- `instanceof`, `==` on `Class` objects, and assignment compatibility all respect the loader.
- A type's *runtime package* is **(package name, defining loader)** — so package-private access also respects loader identity; two same-named classes in different loaders are in *different runtime packages* and cannot access each other's package-private members.

#### 3.6.1 Loader constraints

To keep the type system sound across loaders, the JVM enforces **loader constraints**: if loader L1 (initiating) and loader L2 see a type name `T` used as, say, a method parameter across an override, the JVM requires that `T` resolves to the *same* `Class` for both. If not, you get a `LinkageError: loader constraint violation`. This prevents subtle type confusion when classes from different loaders interact through shared interfaces.

### 3.7 Loading sequence example trace (concrete)

Suppose `App.main` does `new com.acme.Order()` and `Order extends Base implements Auditable`, all on the application classpath:

1. JVM starts, bootstrap loads `java.base`. AppClassLoader loads `App` (initiated by AppClassLoader; it delegated to platform→bootstrap, both failed, App defined it).
2. `main` runs; `new Order` triggers load of `Order`. AppClassLoader.loadClass("com.acme.Order") → delegate up (fail) → findClass → read bytes → defineClass.
3. During defineClass, the JVM sees `Order`'s superclass `Base` and interface `Auditable`; it loads each (same delegation) before finishing `Order`.
4. `Order` is verified, prepared (statics → defaults), then **initialized** because `new` is active use: first `Base.<clinit>` runs (superclass initialized first), `Auditable.<clinit>` runs only if it has default methods + non-constant statics, then `Order.<clinit>`.
5. The constructor runs; symbolic refs inside it (e.g. to `java.util.ArrayList`) are resolved lazily as those instructions execute, loading `ArrayList` if needed.

---

## 4. The complete toolkit

### 4.1 `java.lang.ClassLoader` — key methods

| Method | Purpose | Key params / notes | Default behavior |
|---|---|---|---|
| `loadClass(String)` / `loadClass(String,boolean)` | Entry point to load a class (the delegation algorithm). | `resolve` forces linking. | Parent-first delegation. Override to change policy. |
| `findClass(String)` | Locate+define a class *this* loader is responsible for. | You override this in custom loaders. | Throws `ClassNotFoundException`. |
| `defineClass(String,byte[],int,int)` / `(…,ProtectionDomain)` | Turn bytes into a `Class`; makes this the **defining loader**. | `final`; cannot be overridden. Validates the bytes. | Native; triggers parse+verify setup. |
| `findLoadedClass(String)` | Return a class already defined by this loader, or null. | — | Cache lookup. |
| `resolveClass(Class)` | Link (resolve) a class. | Rarely called directly. | — |
| `getParent()` | The delegation parent. | `null` ⇒ bootstrap. | — |
| `getResource` / `getResourceAsStream` / `getResources` | Load non-class resources via same delegation. | Returns `URL`/`InputStream`/`Enumeration<URL>`. | Parent-first. |
| `getSystemClassLoader()` (static) | The application loader. | — | AppClassLoader. |
| `getPlatformClassLoader()` (static, Java 9+) | The platform loader. | — | — |
| `registerAsParallelCapable()` (static, protected) | Opt into per-name locks for concurrency. | Call in subclass static init. | Off ⇒ coarse per-loader lock. |
| `getDefinedPackage` / `definePackage` | Package metadata. | — | — |
| `setDefaultAssertionStatus`, etc. | Assertion control. | — | — |

### 4.2 Built-in / utility loaders & APIs

| Class / API | Purpose | Notes |
|---|---|---|
| `java.net.URLClassLoader` | Load classes/resources from a list of `URL`s (jars, dirs, http). | The workhorse for custom/plugin loaders pre-modules. `close()` (Java 7+) releases jar file handles — essential for hot redeploy on Windows. |
| `Class.forName(String)` | Load **and initialize** by name using the caller's loader. | `forName(name,false,loader)` to avoid init / pick loader. |
| `ClassLoader.loadClass` vs `Class.forName` | The former does **not** initialize; the latter does (by default). | Classic interview distinction. |
| `java.util.ServiceLoader` | SPI discovery via `META-INF/services` (or `module-info` `provides`). | Uses TCCL by default; lazy. |
| `MethodHandles.Lookup.defineHiddenClass` (Java 15+) | Define a **hidden class** not discoverable by name, GC-able independently, ideal for frameworks/lambdas. | Replaces `Unsafe.defineAnonymousClass`. |
| `java.lang.instrument.Instrumentation` + Java agents | `redefineClasses`, `retransformClasses`, add to bootstrap/system classpath. | Backbone of profilers, APM, hot reload. |
| `java.lang.invoke` / `LambdaMetafactory` | Lambdas are realized as runtime-spun (often hidden) classes. | Explains "where lambda classes come from." |
| JPMS `ModuleLayer` + `Configuration` (Java 9+) | Load whole module graphs into a new layer with their own loaders. | Modern alternative to ad-hoc `URLClassLoader` plugin trees. |

### 4.3 CLI flags & options relevant to class loading / Metaspace

| Flag | Purpose | Default / notes |
|---|---|---|
| `-classpath` / `-cp` / `CLASSPATH` | Where AppClassLoader looks. | `.` if unset. |
| `--module-path` / `-p`, `--add-modules`, `--add-opens`, `--add-reads` | Module resolution / reflective access. | Java 9+. |
| `-verbose:class` (or `-Xlog:class+load=info` Java 9+) | Log each class load and its source. | Invaluable for "where did this class come from?" |
| `-Xlog:class+unload`, `-Xlog:class+loader+constraints` | Trace unloading / loader constraints. | Unified logging, Java 9+. |
| `-XX:+TraceClassLoading` / `-XX:+TraceClassUnloading` | Legacy equivalents. | Deprecated in favor of `-Xlog`. |
| `-XX:MetaspaceSize` | Initial high-water mark that triggers the first Metaspace GC. | Platform-dependent (~20–21 MB typical), **not** a floor on usage. |
| `-XX:MaxMetaspaceSize` | Hard cap on Metaspace. | **Unlimited by default** (bounded only by native memory) — a notorious source of native OOM. |
| `-XX:CompressedClassSpaceSize` | Size of the compressed class pointers region (part of Metaspace when compressed class pointers on). | Default 1 GB reserved. |
| `-XX:+UseCompressedClassPointers` / `-XX:+UseCompressedOops` | 32-bit class pointers to save metadata memory. | On by default for heaps < 32 GB. |
| `-Xverify:none` / `-noverify` | Disable verification. | **Deprecated/removed**; do not use. |
| `-Djava.system.class.loader=MyLoader` | Replace the system class loader. | Must have a `(ClassLoader parent)` constructor. |
| `-XX:+ClassUnloading`, `-XX:+ClassUnloadingWithConcurrentMark` | Enable class unloading in the GC. | On by default for modern collectors. |
| `-XX:+HeapDumpOnOutOfMemoryError` | Capture a heap dump (also helps diagnose loader leaks). | Off by default. |
| `-Xshare:on/off/dump`, `-XX:+UseAppCDS`, `-XX:SharedArchiveFile` | Class Data Sharing / Application CDS — memory-map pre-parsed class metadata across JVMs and speed startup. | See §7.6. |

---

## 5. Code examples by use case

The examples below are deliberately *different scenarios*, not variations of one. Each is complete enough to adapt; non-obvious lines are commented.

### 5.1 Observing the loader hierarchy and identity

```java
public class WhoLoadedMe {
    public static void main(String[] args) {
        // Bootstrap-loaded core class → loader is null (no Java object exists for bootstrap).
        System.out.println("String loader: " + String.class.getClassLoader());           // null

        // Your own class is loaded by the application/system loader.
        ClassLoader app = WhoLoadedMe.class.getClassLoader();
        System.out.println("App class loader: " + app);                                    // AppClassLoader

        // Walk the delegation chain upward until we hit bootstrap (null).
        for (ClassLoader cl = app; cl != null; cl = cl.getParent()) {
            System.out.println("  loader: " + cl + "  parent: " + cl.getParent());
        }

        // The platform and system loaders are reachable statically.
        System.out.println("platform: " + ClassLoader.getPlatformClassLoader());
        System.out.println("system  : " + ClassLoader.getSystemClassLoader());

        // Context loader of the current thread (defaults to the app loader on the main thread).
        System.out.println("TCCL    : " + Thread.currentThread().getContextClassLoader());
    }
}
```

What to notice: `String`'s loader is `null` (bootstrap), and the chain ends at `null`. This single program clears up most confusion about "the four loaders."

### 5.2 A minimal in-memory ClassLoader (define a class from a byte[])

Useful when you generate or fetch bytecode at runtime.

```java
import java.util.Map;

/** Defines classes from an in-memory name→bytecode map. Parent-first by default. */
public class InMemoryClassLoader extends ClassLoader {
    private final Map<String, byte[]> classBytes;

    public InMemoryClassLoader(Map<String, byte[]> classBytes, ClassLoader parent) {
        super(parent);                       // keep normal delegation to 'parent'
        this.classBytes = classBytes;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = classBytes.get(name);     // only handle classes we actually own
        if (b == null) throw new ClassNotFoundException(name);
        // defineClass makes THIS loader the *defining* loader → part of class identity.
        return defineClass(name, b, 0, b.length);
    }
}
```

Usage (paired with an in-memory compile via `javax.tools.JavaCompiler`, or pre-generated bytes from ASM/ByteBuddy). Because we extend `ClassLoader` and only override `findClass`, we inherit correct parent-delegation: JDK classes still come from the parent.

### 5.3 A plugin loader with `URLClassLoader` and `ServiceLoader`

Load plugins from a directory of jars, each exposing an SPI, isolating each plugin's third-party deps.

```java
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ServiceLoader;

public interface Plugin {            // the SPI lives in the host app
    String name();
    void run();
}

class PluginHost {
    static void loadAndRun(File pluginsDir) throws Exception {
        File[] jars = pluginsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) {
            URL[] urls = { jar.toURI().toURL() };
            // child-of-app loader; closing it later releases the jar handle (Win) and enables unload.
            try (URLClassLoader loader = new URLClassLoader(urls, PluginHost.class.getClassLoader())) {
                // ServiceLoader reads META-INF/services/Plugin from the jar via THIS loader.
                ServiceLoader<Plugin> services = ServiceLoader.load(Plugin.class, loader);
                for (Plugin p : services) {
                    System.out.println("Running plugin: " + p.name());
                    p.run();
                }
            } // try-with-resources closes the loader; plugin classes become unloadable when unreferenced
        }
    }
}
```

Key points: each plugin gets its **own loader** → isolation (two plugins can bundle different versions of the same library). The host interface `Plugin` is loaded by the *parent* (the app loader), so plugin implementations and the host share the *same* `Plugin` type — required for the cast/iteration to work (this is exactly the identity rule from §3.6).

### 5.4 Demonstrating `ClassCastException` across loaders ("Foo cannot be cast to Foo")

```java
import java.net.URL;
import java.net.URLClassLoader;

public class TwoLoaders {
    public static void main(String[] args) throws Exception {
        URL[] cp = { new java.io.File("plugin.jar").toURI().toURL() };

        // Two SIBLING loaders, both with the same parent, both able to define com.acme.Foo.
        URLClassLoader l1 = new URLClassLoader(cp, ClassLoader.getSystemClassLoader().getParent());
        URLClassLoader l2 = new URLClassLoader(cp, ClassLoader.getSystemClassLoader().getParent());

        Class<?> foo1 = Class.forName("com.acme.Foo", true, l1);
        Class<?> foo2 = Class.forName("com.acme.Foo", true, l2);

        System.out.println(foo1 == foo2);                 // false — distinct Class objects
        System.out.println(foo1.getName().equals(foo2.getName())); // true — same name

        Object o1 = foo1.getDeclaredConstructor().newInstance();
        // The next line throws ClassCastException: com.acme.Foo cannot be cast to com.acme.Foo
        com.acme.Foo bad = (com.acme.Foo) o1;   // only if com.acme.Foo is also visible to THIS code
    }
}
```

We give them the platform loader as parent (not the app loader) so neither delegates the `Foo` lookup to a common defining loader — each *defines* its own `Foo`. This is the canonical reproduction of the "jar hell" cast error.

### 5.5 Child-first (parent-last) loader, as app servers do

```java
import java.net.URL;
import java.net.URLClassLoader;

/** Loads from local URLs FIRST, falling back to the parent only if not found locally.
 *  Mirrors a servlet container's WEB-INF policy. JDK classes are still delegated. */
public class ChildFirstClassLoader extends URLClassLoader {
    public ChildFirstClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);            // 1. already loaded?
            if (c == null) {
                // 2. ALWAYS delegate core java.*/javax.* to parent (safety; can't redefine JDK).
                if (name.startsWith("java.") || name.startsWith("javax.")
                        || name.startsWith("jdk.") || name.startsWith("sun.")) {
                    c = super.loadClass(name, false);       // normal parent-first for platform
                } else {
                    try {
                        c = findClass(name);                // 3. try locally FIRST (child-first)
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);   // 4. fall back to parent
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
```

The guard for `java.*`/`jdk.*` is mandatory: defining a core JDK class in a child loader throws `SecurityException`/`LinkageError`, and you must never shadow the platform. Everything else prefers the local copy — that's how a web app's bundled `slf4j` wins over the container's.

### 5.6 Hot reload by discarding and recreating a loader

The only reliable way to "reload" a class is to throw away its loader and make a new one (you cannot redefine a class's shape in-place without an agent).

```java
import java.net.URL;
import java.net.URLClassLoader;

public class HotReloader {
    private URLClassLoader current;
    private final URL[] urls;
    private final ClassLoader parent;

    public HotReloader(URL[] urls, ClassLoader parent) { this.urls = urls; this.parent = parent; }

    /** Reload: close old loader (frees handles + lets old classes unload), create a fresh one. */
    public synchronized Class<?> reload(String className) throws Exception {
        if (current != null) current.close();             // release file handles; old classes now GC-able
        current = new URLClassLoader(urls, parent);        // fresh namespace → fresh class identity
        return current.loadClass(className);               // returns the NEW version
    }
}
```

Caveats: any live instance of the *old* class, or a static reference to the old loader (e.g. a thread-local, a cache, a JDBC driver registered in `DriverManager`, a shutdown hook), pins the old loader and *prevents unloading* — the classic "PermGen/Metaspace leak on redeploy" (see §9.4).

### 5.7 Hidden classes (Java 15+) for framework-generated code

```java
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;

public class HiddenClassDemo {
    static Class<?> spin(byte[] bytecode) throws IllegalAccessException {
        Lookup lookup = MethodHandles.lookup();
        // NESTMATE: shares access with this class; not findable by name; GC'd independently.
        Lookup defined = lookup.defineHiddenClass(bytecode, true,
                MethodHandles.Lookup.ClassOption.NESTMATE);
        return defined.lookupClass();
    }
}
```

Hidden classes can't be referenced by name from other classes, aren't returned by `Class.forName`, and can be unloaded independently of their defining loader. This is what modern lambda/proxy infrastructure uses to avoid Metaspace bloat.

### 5.8 Lazy init demonstration (`Class.forName` vs `.class`, and constant inlining)

```java
class Heavy {
    static { System.out.println("Heavy <clinit> ran"); }   // prints only on initialization
    static final String NAME = "heavy";                      // compile-time constant → inlined
    static int counter = compute();                          // non-constant static
    static int compute() { System.out.println("compute()"); return 42; }
}

public class InitTrigger {
    public static void main(String[] args) throws Exception {
        Class<?> c = Heavy.class;                 // loads, but does NOT initialize → no print
        System.out.println("got class literal");

        System.out.println(Heavy.NAME);           // inlined constant → STILL no <clinit>

        System.out.println(Heavy.counter);        // non-constant static read → triggers <clinit> NOW
        // Now you see: "Heavy <clinit> ran" then "compute()".

        Class.forName("Heavy");                   // would also have initialized (no-op now)
    }
}
```

This makes concrete the trigger table in §3.2 — the difference between *loading* and *initialization*, and why compile-time constants are dangerous to change without recompiling consumers (§9.5).

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Startup cost** is dominated by loading + verifying classes (thousands for a Spring Boot app). Mitigations: **CDS/AppCDS** (§7.6) to memory-map pre-parsed metadata; **`-Xshare:on`**; AOT-ish techniques (GraalVM native image eliminates runtime class loading entirely); reduce classpath scanning (Spring's component scan, classpath indexing).
- **Verification** is one-time per class but adds latency; `StackMapTable` (Java 6+) made it cheaper. Never disable it in prod for "speed."
- **Lazy resolution** keeps steady-state fast; the first call through a call site pays resolution + possibly loading.
- **Lock granularity:** call `registerAsParallelCapable()` in custom loaders to get **per-class-name locks** instead of one coarse lock per loader; without it, concurrent loads through one loader serialize and can even deadlock with cyclic dependencies across two non-parallel loaders.

### 6.2 Correctness & concurrency

- **Exactly-once `<clinit>`** is guaranteed by the JVM — this is the basis of the **initialization-on-demand holder idiom** for lazy singletons (no explicit locks needed):
  ```java
  class Singleton {
      private Singleton() {}
      private static class Holder { static final Singleton INSTANCE = new Singleton(); }
      static Singleton get() { return Holder.INSTANCE; } // Holder init is lazy + thread-safe
  }
  ```
- **Avoid static-init cycles:** if `A.<clinit>` reads `B` and `B.<clinit>` reads `A`, one thread sees a partially-initialized class (default-valued statics). Symptoms: mysterious `null`/`0` constants at startup.
- **Class-loading deadlock:** two loaders, each loading a class that triggers the other, with non-parallel-capable loaders, can deadlock. Make loaders parallel-capable and avoid cross-loader cyclic loads.

### 6.3 Memory

- Class metadata lives in **Metaspace** (native memory), *not* the heap; only the `Class` mirror and interned strings are on-heap. **Set `-XX:MaxMetaspaceSize`** in production so a metadata leak fails fast with a clear `OutOfMemoryError: Metaspace` instead of consuming all native memory and getting OOM-killed by the OS.
- Each loader that defines many classes (or many short-lived loaders that never unload) bloats Metaspace. Prefer hidden classes / shared loaders for dynamically generated code.

### 6.4 Security

- Parent-delegation is itself a security mechanism (can't shadow `java.lang.*`).
- The **`defineClass` validation** rejects classes claiming `java.*` package names from non-bootstrap loaders.
- The **bytecode verifier** is the primary defense against malformed/hostile bytecode — never disable it.
- The old **SecurityManager** and `ProtectionDomain`/`CodeSource` machinery (per-class permissions) is **deprecated for removal** (JEP 411, Java 17+); don't build new designs on it. `defineClass` still associates a `ProtectionDomain`.
- Loading code from the network or untrusted sources requires signature verification at your layer; the JVM won't vet provenance for you.

### 6.5 Observability

- `-verbose:class` / `-Xlog:class+load=info:file=load.log` tells you **which loader loaded which class from where** — the first tool to reach for in "wrong version loaded" incidents.
- `-Xlog:class+unload` confirms unloading (or its absence → leak).
- JFR (Java Flight Recorder) has class-loading and Metaspace events.
- `jcmd <pid> VM.classloader_stats` and `GC.class_stats` (and `jmap -clstats`) dump per-loader class counts and Metaspace usage — gold for hunting loader leaks.

### 6.6 Testability

- Tests that rely on static state are order-dependent because `<clinit>` runs once per loader per JVM. Frameworks (JUnit + custom loaders, TestNG) sometimes use fresh loaders to reset static state.
- Mocking frameworks (Mockito, PowerMock) and bytecode tools define classes at runtime — be aware they add to Metaspace and create hidden/extra loaders.

### 6.7 Production hardening checklist

- Always set `-XX:MaxMetaspaceSize` and `-XX:+HeapDumpOnOutOfMemoryError`.
- Make custom loaders `Closeable`/parallel-capable; `close()` them on undeploy.
- On undeploy, proactively: deregister JDBC drivers, cancel timers/threads, clear thread-locals, remove shutdown hooks, flush caches keyed by app classes (defeats loader leaks).
- Pin dependency versions; avoid duplicate jars on the classpath; use a build tool's dependency convergence checks.

### 6.8 Anti-patterns

- Disabling verification (`-noverify`).
- Storing app-class instances in JDK/container-level static caches (e.g. `ThreadLocal`, `java.beans.Introspector` cache) → loader leak.
- Relying on classpath ordering for "which duplicate class wins" (non-deterministic across environments).
- Catching `ClassNotFoundException` and silently continuing.
- Using `Class.forName` when you only need the `Class` object (it needlessly initializes).
- Custom loaders that don't delegate `java.*` to the parent.

---

## 7. Advanced topics & deep internals

### 7.1 HotSpot data structures

Inside HotSpot (C++):
- **`Klass` / `InstanceKlass`** — the runtime metadata for a class (vtable, itable, field layout, constant pool cache, methods). Lives in Metaspace.
- **`java.lang.Class` mirror** — the heap object that Java code sees; it references the `Klass`.
- **`ConstantPool` + `ConstantPoolCache`** — the cache holds *resolved* entries so repeated `invoke`/`getstatic` are fast after first resolution.
- **`ClassLoaderData` (CLD)** — one per loader; owns a Metaspace chunk arena and the list of classes that loader defined. **This is the unit of class unloading.**

### 7.2 Metaspace internals

- Introduced in **Java 8**, replacing **PermGen** (permanent generation, a fixed heap region that caused infamous `OutOfMemoryError: PermGen space`). Metaspace lives in **native memory**, grows dynamically, and is freed per-`ClassLoaderData` when a loader is unloaded.
- Two parts when compressed class pointers are on: **Klass metaspace** (the compressed class space, default 1 GB reserved via `CompressedClassSpaceSize`) holding `Klass` structures, and **non-class metaspace** for everything else (methods, constant pools).
- `MetaspaceSize` is the **threshold that triggers a metadata GC**, after which the threshold may grow/shrink; it is *not* an initial allocation or a floor. `MaxMetaspaceSize` is the hard ceiling (default: unlimited).
- Allocation is chunk-based per CLD; freeing a loader returns its chunks. Fragmentation can keep RSS high even after frees (JDK has improved this with the Java 16 "Elastic Metaspace" / new allocator that returns memory to the OS more aggressively).

### 7.3 Class unloading — exact conditions

A class `C` can be unloaded only when its **defining `ClassLoaderData` is unreachable**, which requires that **all** of these are unreachable: the loader object, **every** `Class` it defined, and **every instance** of those classes. In practice unloading happens for:
- The bootstrap/platform/app loaders: **never** (they live forever).
- Custom loaders: only when nothing pins them.

Unloading is performed by the GC during a collection that scans Metaspace roots:
- **G1, ZGC, Shenandoah, Parallel** all support class unloading; it happens during certain marking cycles. G1 does it during the remark/concurrent cycle (with `ClassUnloadingWithConcurrentMark`).
- Hidden/anonymous classes and classes defined by short-lived loaders are the typical unloadables.

Common **pins** that prevent unloading (memorize these for incident debugging): static references from a longer-lived loader, live threads whose `contextClassLoader` is the doomed loader, `ThreadLocal`s holding app objects, JDBC `DriverManager` registrations, JMX MBeans, shutdown hooks, finalizers, `java.beans.Introspector`/logging/`SecurityProviders` caches, and running timers.

### 7.4 Thread Context ClassLoader, deeply

The TCCL exists purely to let *parent-loaded* framework code find *child-loaded* implementations. Mechanics:
- `Thread.setContextClassLoader(cl)` / `getContextClassLoader()`.
- New threads inherit the creating thread's TCCL.
- Containers set the TCCL to the web-app loader before invoking app code, then restore it (try/finally) — failing to restore is a classic loader-leak / wrong-class bug.
- `ServiceLoader.load(Service.class)` (no loader arg) uses the TCCL.

### 7.5 Modules (JPMS) and loaders (Java 9+)

- The module system layers a **readability/visibility** graph on top of loaders. The three built-in loaders now map to module sets (bootstrap=`java.base` et al.; platform=other JDK modules; app=your modules + classpath).
- **Unnamed module:** code on the classpath (not in a named module) lives in its loader's *unnamed module* and can read everything — preserving classpath behavior.
- **`ModuleLayer`**: you can spin up a new layer with its own `ClassLoader`(s) for a resolved set of modules — the modern, clean way to do plugin isolation. `Configuration.resolveAndBind` + `ModuleLayer.defineModulesWith…` returns a controller with the new loaders.
- Strong encapsulation: `--add-opens`/`--add-exports` are needed for deep reflection across modules; `InaccessibleObjectException`/`IllegalAccessException` are the new "reflection can't reach it" errors.

### 7.6 Class Data Sharing (CDS) and AppCDS

- **CDS** memory-maps a pre-parsed archive of core class metadata into the JVM at startup, shared read-only across processes → faster startup, lower per-JVM footprint. On by default for the JDK classes since Java 12 (default CDS archive).
- **AppCDS** (Java 10+) extends this to *application* classes: produce a class list, dump an archive (`-XX:ArchiveClassesAtExit` in Java 13+, or the older `-Xshare:dump` flow), then run with `-XX:SharedArchiveFile`. Big wins for microservices/serverless cold start.
- **Dynamic CDS** (Java 13+, JEP 350): auto-archive at exit, no separate training run for the class list.

### 7.7 Hidden classes vs. anonymous classes vs. lambdas

- Pre-15, frameworks used `sun.misc.Unsafe.defineAnonymousClass` (non-standard). Java 15 (JEP 371) introduced **hidden classes** as the supported replacement; `Unsafe.defineAnonymousClass` is being removed.
- **Lambdas** are not anonymous inner classes; `javac` emits an `invokedynamic` whose bootstrap (`LambdaMetafactory`) spins a class at first execution (often a hidden class). This keeps Metaspace lean and avoids one `.class` file per lambda.

### 7.8 `defineClass` and the constant-pool-cache resolution path (deep trace of one `invokevirtual`)

1. Interpreter hits `invokevirtual #idx`.
2. Looks up `ConstantPoolCache` entry for `#idx`. If unresolved, performs **resolution**: resolve the `MethodRef` symbolic ref → load declaring class if needed → check access/loader constraints → compute vtable index.
3. Caches the resolved entry; subsequent calls skip resolution.
4. Dispatch via the receiver's vtable. A `NoSuchMethodError`/`AbstractMethodError` here means the runtime class shape differs from compile-time assumptions (binary incompatibility).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Loader policy comparison

| Policy | Lookup order | Use when | Avoid when | Risk |
|---|---|---|---|---|
| **Parent-first (default)** | parent → self | normal apps; you want shared, consistent types | you must override a parent-provided lib | child can't override parent libs |
| **Child-first (parent-last)** | self → parent (except `java.*`) | app servers, plugins needing their own dep versions | you need to share types defined by parent | duplicate types, `ClassCastException`, must guard `java.*` |
| **Flat (single loader)** | one loader | small apps, CLIs | need isolation/hot reload | jar hell, no unloading |
| **Graph (OSGi/JPMS layers)** | per package import/export | large modular systems, true isolation | simple apps (overhead/complexity) | wiring complexity, split-package issues |

### 8.2 `Class.forName` vs `ClassLoader.loadClass` vs `.class`

| Mechanism | Loads? | Initializes? | Picks loader? | Use when |
|---|---|---|---|---|
| `Foo.class` | yes | **no** | caller's | you just need the `Class` object |
| `obj.getClass()` | already loaded | n/a | n/a | runtime type of an instance |
| `ClassLoader.loadClass(n)` | yes | **no** | the given loader | you control the loader, want lazy init |
| `Class.forName(n)` | yes | **yes** | caller's | you need the class fully initialized (e.g. JDBC drivers, legacy) |
| `Class.forName(n,false,cl)` | yes | no | chosen loader | most flexible; framework code |

### 8.3 Dynamic code generation: choices

| Approach | Loader/Metaspace impact | Use when |
|---|---|---|
| Hidden classes (JDK) | minimal, GC-able independently | lambdas, proxies, framework internals |
| Custom `ClassLoader` + ASM/ByteBuddy | each loader pins its classes | plugins, full classes you may unload as a group |
| JDK dynamic proxies (`Proxy`) | defined in a loader you pass | interface-based proxies |
| CGLIB (legacy) | subclass-based, more Metaspace | legacy Spring proxying |

### 8.4 Plugin isolation: `URLClassLoader` tree vs `ModuleLayer`

- Use **`URLClassLoader` trees** for classpath-era simplicity and broad version support; you manage delegation manually (child-first).
- Use **`ModuleLayer`** (Java 9+) when you want declared dependencies, strong encapsulation, and clean teardown — at the cost of requiring modularized plugins.

---

## 9. Failure modes & debugging

### 9.1 `ClassNotFoundException` vs `NoClassDefFoundError` — the #1 confusion

| | `ClassNotFoundException` | `NoClassDefFoundError` |
|---|---|---|
| Type | checked `Exception` | `Error` (subclass of `LinkageError`) |
| When | **explicit** load by name failed: `Class.forName`, `loadClass`, `ClassLoader` couldn't find bytes | the class **was present at compile time** but is missing/unloadable/failed-init **at runtime** when *implicitly* referenced |
| Typical cause | reflection with a wrong/absent name; missing jar for a reflective lookup | jar present at compile, absent at runtime; OR the class's `<clinit>` previously threw (now permanently erroneous) |
| Fix | check the name; ensure the jar is on the classpath/visible to the right loader | fix the runtime classpath; **find the original `ExceptionInInitializerError`** that poisoned the class |

> Critical insight: a `NoClassDefFoundError` on a class whose `.class` clearly exists almost always means its **static initializer threw earlier** (look up the log for the *first* `ExceptionInInitializerError` / `Caused by`). The class is now in the *erroneous* state and every subsequent use throws `NoClassDefFoundError`.

### 9.2 The `LinkageError` family

- **`VerifyError`** — bytecode failed verification (corrupt class, incompatible compiler output, instrumentation bug).
- **`NoSuchMethodError` / `NoSuchFieldError`** — compiled against one version, ran against another (member removed/changed signature). Classic with mismatched library versions.
- **`AbstractMethodError`** — a concrete class is missing an implementation the interface now requires (interface evolved, impl not recompiled).
- **`IncompatibleClassChangeError`** — broad category: a class changed in a binary-incompatible way (e.g. a class became an interface).
- **`UnsupportedClassVersionError`** — class compiled with a newer JDK than the running JVM (e.g. "class file version 61.0" = Java 17 on a Java 11 JVM).
- **loader constraint violation** `LinkageError` — same type name resolves to different classes across loaders in an interaction (§3.6.1).

### 9.3 `ClassCastException`: "X cannot be cast to X"

Same binary name, two defining loaders (§3.6, §5.4). Diagnose by printing `obj.getClass().getClassLoader()` and the target type's loader; they'll differ. Fix by ensuring the *shared* type is loaded by a **common ancestor** loader (move the SPI/interface up to the parent), or by not duplicating the jar across sibling loaders.

### 9.4 Metaspace / loader leaks (redeploy leak)

Symptoms: after N redeploys, `OutOfMemoryError: Metaspace`; class/loader count climbs and never drops in `jcmd VM.classloader_stats`.

Diagnose:
1. `jcmd <pid> GC.class_stats` / `VM.classloader_stats` — count loaders/classes over time.
2. `jmap -histo:live` then a heap dump (`jcmd GC.heap_dump` or `-XX:+HeapDumpOnOutOfMemoryError`).
3. In Eclipse MAT / VisualVM: find the dead web-app `ClassLoader` instances, run **"path to GC roots, excluding weak/soft"** to see what pins them. Usual culprits: a `ThreadLocal`, a registered JDBC driver, a running thread with the old TCCL, a JMX MBean, a leaked `static` cache, a shutdown hook.

Fix: break the pin on undeploy (deregister, clear thread-locals, stop threads, remove hooks). Tomcat has a `JreMemoryLeakPreventionListener` and logs *"The web application appears to have started a thread … but has failed to stop it"* exactly for this.

### 9.5 Stale compile-time constants

`static final int X = 100;` is **inlined** into every consumer at compile time. Change it to `200` and recompile only the producer → consumers still use `100` until *they* are recompiled. No error, just wrong values. Fix: recompile all consumers; or avoid `static final` constants for values that may change (use a method or non-final field).

### 9.6 Real-world incident patterns

- **Tomcat redeploy Metaspace creep** (above) — extremely common; root cause is almost always a thread-local or driver pin.
- **Wrong SLF4J/Logback binding** — two logging jars on the classpath; classpath order decides which `StaticLoggerBinder` loads → "multiple bindings" warning and unexpected logging behavior. Fix duplicate jars.
- **`UnsupportedClassVersionError` in CI/prod** — a library or your own jar compiled for a newer JDK than the runtime. Read the version number, bump the runtime or recompile with `--release`.
- **JDBC driver not found despite jar present** — pre-Java-6 needed `Class.forName("com.mysql.jdbc.Driver")` to register; with `ServiceLoader` auto-registration it "just works" *unless* the driver jar is in a child loader the `DriverManager` (parent) can't see — a TCCL/visibility issue.
- **Split package across module + classpath** — JPMS rejects the same package coming from two modules/loaders.

### 9.7 The debugging toolbox (commands)

```bash
# What loaded a class and from where:
java -verbose:class -cp app.jar com.acme.Main            # legacy
java -Xlog:class+load=info -cp app.jar com.acme.Main      # Java 9+ unified logging

# Confirm/trace unloading:
java -Xlog:class+unload=info ...

# Per-loader class & Metaspace stats on a live JVM:
jcmd <pid> VM.classloader_stats
jcmd <pid> GC.class_stats          # (may need -XX:+UnlockDiagnosticVMOptions)
jmap -clstats <pid>

# Heap dump to hunt loader leaks:
jcmd <pid> GC.heap_dump /tmp/dump.hprof
# then analyze in Eclipse MAT: "Duplicate Classes" + "Path to GC Roots"

# Find which jar provides a class:
jar tf somelib.jar | grep Foo.class
# or scan a directory of jars:
for j in *.jar; do unzip -l "$j" | grep -q 'com/acme/Foo.class' && echo "$j"; done

# Check a class's bytecode version (e.g. 61 = Java 17):
javap -v Foo.class | grep -i 'major version'
```

---

## 10. Interview drill

**Q1. Walk me through the class lifecycle from `.class` file to usable type.**
Model answer: Loading (find bytes, `defineClass`, recursively load supertypes) → Linking = Verify (bytecode safety, `StackMapTable`) + Prepare (allocate statics, set defaults) + Resolve (symbolic→direct refs, lazy in HotSpot) → Initialization (run `<clinit>` once, thread-safe, on first active use). Unloading later if the defining loader becomes unreachable.
- Probe: *When exactly does initialization run?* On active use: `new`, static method call, non-constant static access, `Class.forName`, subclass init (triggers superclass), entry-point class.
- Probe: *Does reading `static final int X = 100` trigger init?* No — compile-time constants are inlined; no class touch.
- Probe: *Default values in prepare vs init?* Prepare sets `0/false/null`; init runs the programmer's initializers.

**Q2. `ClassNotFoundException` vs `NoClassDefFoundError`?**
Model answer: CNFE is a checked exception thrown when an *explicit* lookup (`forName`/`loadClass`) can't find bytes. NCDFE is an `Error` thrown when an *implicitly* referenced class that existed at compile time is missing at runtime — or, importantly, when the class's `<clinit>` threw earlier (erroneous state).
- Probe: *NCDFE on a class that's clearly on the classpath — why?* Its static initializer threw an `ExceptionInInitializerError` on first use; find that root cause.
- Probe: *Which is checked?* CNFE (Exception); NCDFE is an Error.

**Q3. Explain class identity and the "Foo cannot be cast to Foo" error.**
Model answer: Identity = (binary name, defining loader). The same jar loaded by two loaders yields two distinct, incompatible types; casting throws CCE with identical names. Fix by loading the shared type from a common ancestor.
- Probe: *How does this enable app-server isolation?* Each web app gets its own loader → its classes are a separate namespace.
- Probe: *What's a loader constraint violation?* The JVM rejects interactions where a shared type name resolves to different classes across loaders.

**Q4. Describe parent-delegation and why it exists.**
Model answer: `loadClass` checks already-loaded, then asks the parent, then loads itself. Provides safety (can't shadow `java.lang.*`) and consistency (one `String` type).
- Probe: *When do you break it?* Web containers do child-first for app classes (but still delegate `java.*`).
- Probe: *Risks of child-first?* Duplicate types, CCE, must guard core packages.

**Q5. What is the Thread Context ClassLoader and what problem does it solve? (senior-signal)**
Model answer: Parent-loaded SPI/factory code (JDBC, JAXP) can't see child-loaded implementations because parents can't reach down. TCCL is a per-thread loader that framework code uses to load implementations, bypassing strict delegation. `ServiceLoader` uses it by default.
- Probe: *How is it set/inherited?* `Thread.setContextClassLoader`; inherited from the creating thread; containers set+restore it around app calls.
- Probe: *How can it cause leaks?* A pooled thread retains an old web-app TCCL after undeploy, pinning that loader.

**Q6. PermGen vs Metaspace.**
Model answer: PermGen (≤Java 7) was a fixed heap region for class metadata → `OOM: PermGen`. Metaspace (Java 8+) is native memory, grows dynamically, freed per-loader on unload; bounded by `MaxMetaspaceSize` (default unlimited).
- Probe: *Is `MetaspaceSize` a floor?* No — it's the first GC threshold, not an allocation/floor.
- Probe: *Why set `MaxMetaspaceSize`?* So leaks fail fast with a clear OOM instead of native exhaustion.

**Q7. When and how does a class get unloaded? (senior-signal)**
Model answer: Only when its defining `ClassLoaderData` is unreachable — loader, all its `Class` objects, and all instances must be unreachable. Built-in loaders never unload. The GC reclaims Metaspace per CLD.
- Probe: *Name three things that pin a loader.* Thread-local holding an app object; a registered JDBC driver; a live thread with that TCCL (also static caches, MBeans, shutdown hooks).
- Probe: *How do you prove a leak?* `jcmd VM.classloader_stats` over time; heap dump + path-to-GC-roots in MAT.

**Q8. Implement a thread-safe lazy singleton without locks. Why does it work?**
Model answer: Initialization-on-demand holder idiom (§6.2). Works because the JVM guarantees `<clinit>` runs exactly once, lazily, and is itself synchronized by the JVM.
- Probe: *Why not double-checked locking with a plain field?* Needs `volatile`; holder idiom is simpler and faster.
- Probe: *When does `Holder` initialize?* On first call to `get()`, i.e., first access to `Holder.INSTANCE`.

**Q9. How would you design a hot-reloadable plugin system? (senior-signal)**
Model answer: One `ClassLoader` (or `ModuleLayer`) per plugin, child-first for plugin deps but delegating the host SPI interface to the parent so types are shared. To reload, close the old loader and create a new one; ensure no pins (threads, thread-locals, caches, drivers) survive so the old classes unload. Set `MaxMetaspaceSize` and monitor loader counts.
- Probe: *Why must the SPI interface come from the parent?* So host and plugin share the same type — otherwise CCE.
- Probe: *How do you guarantee old classes unload?* Drop all references to the old loader and its instances; verify with `-Xlog:class+unload`.

**Q10. What does the bytecode verifier check, and should you ever disable it?**
Model answer: Type safety of every instruction, operand-stack consistency (via `StackMapTable`), control-flow integrity, access/`final` rules. Never disable in prod — it's the JVM's integrity guarantee; `-noverify` is deprecated/removed.
- Probe: *What changed in Java 6?* `StackMapTable` enabled faster single-pass type-checking verification.
- Probe: *What error if it fails?* `VerifyError` (a `LinkageError`).

**Q11. `Class.forName` vs `ClassLoader.loadClass`?**
Model answer: `forName` loads **and initializes** (by default) using the caller's loader; `loadClass` loads but does **not** initialize. Use `forName(name,false,cl)` for control.
- Probe: *Why did old JDBC code call `Class.forName`?* To force driver `<clinit>` to register with `DriverManager` (pre-ServiceLoader).
- Probe: *Which to prefer to avoid side effects?* `loadClass` / `forName(...,false,...)`.

**Q12. You see `UnsupportedClassVersionError: ... class file version 61.0`. Diagnose. (senior-signal)**
Model answer: A class was compiled for Java 17 (61.0) but runs on an older JVM. Either upgrade the runtime or recompile with `--release <older>`. Identify the offending jar with `javap -v | grep 'major version'`. Discuss compile-target hygiene (`--release` over `-source/-target`), CI matrix, and multi-release jars.
- Probe: *Map a few versions.* 52=Java 8, 55=Java 11, 61=Java 17, 65=Java 21.
- Probe: *Why `--release` over `-target`?* It also constrains the API surface to that version, preventing accidental newer-API usage.

---

## 11. Glossary

- **`.class` file / class file format:** the JVM's binary representation of a type; starts with magic `0xCAFEBABE`.
- **Active use:** the events that trigger class initialization (`new`, static method/field access, `Class.forName`, subclass init, entry point).
- **AppCDS / CDS:** Class Data Sharing — memory-mapped, pre-parsed class metadata shared across JVMs to speed startup.
- **Bootstrap ClassLoader:** the C++-implemented top loader; `getClassLoader()` returns `null`; loads core JDK (`java.base`).
- **Bytecode verifier:** validates bytecode type-safety and structure before execution.
- **`ClassLoaderData` (CLD):** HotSpot per-loader structure owning a Metaspace arena; the unit of unloading.
- **`<clinit>`:** the synthetic class (static) initializer method; runs once on initialization.
- **Compile-time constant:** a `static final` primitive/String initialized with a constant expression; inlined into consumers.
- **Constant pool:** per-class table of symbolic references (names, descriptors, literals).
- **Defining loader:** the loader whose `defineClass` created the class; part of class identity.
- **Delegation (parent-first):** a loader asks its parent before loading itself.
- **`ExceptionInInitializerError`:** wraps an exception thrown by `<clinit>`; leaves the class *erroneous*.
- **Hidden class (Java 15+):** a class not findable by name, independently unloadable; used for framework-generated code.
- **Initiating loader:** any loader whose `loadClass` was invoked for a name (may delegate).
- **`InstanceKlass` / `Klass`:** HotSpot C++ metadata for a class, stored in Metaspace.
- **JPMS / module / `ModuleLayer`:** Java Platform Module System (Java 9+); strong encapsulation and a readability graph; layers can carry their own loaders.
- **`LinkageError`:** family of errors from linking problems (`VerifyError`, `NoSuchMethodError`, loader constraints, etc.).
- **Loader constraint:** JVM rule ensuring a shared type name resolves to the same class across interacting loaders.
- **Metaspace:** native-memory region (Java 8+) holding class metadata; replaced PermGen.
- **`NoClassDefFoundError`:** error when an implicitly referenced class is missing/unloadable at runtime or previously failed init.
- **OSGi:** a module/bundle framework using a *graph* of cooperating loaders wired by package import/export.
- **Parallel-capable loader:** a loader registered for per-class-name locks (`registerAsParallelCapable`).
- **PermGen:** pre-Java-8 fixed heap region for class metadata; source of `OOM: PermGen space`.
- **Platform ClassLoader (Java 9+):** loads non-base JDK modules; successor to the Extension loader.
- **Preparation:** lifecycle phase allocating statics and setting default values.
- **`ProtectionDomain` / `CodeSource`:** security metadata associated at `defineClass`; tied to the deprecated SecurityManager.
- **Resolution:** turning symbolic constant-pool refs into direct refs; lazy in HotSpot.
- **`ServiceLoader`:** JDK SPI discovery via `META-INF/services` / module `provides`; uses TCCL by default.
- **`StackMapTable`:** class-file attribute (Java 6+) enabling fast single-pass verification.
- **System/Application ClassLoader:** loads classpath/module-path application code.
- **Thread Context ClassLoader (TCCL):** per-thread loader used by frameworks/SPIs to find child-loaded implementations.
- **`UnsupportedClassVersionError`:** running a class compiled for a newer JVM than present.
- **`URLClassLoader`:** loads classes/resources from a list of URLs; `close()`able.
- **Verification:** the linking sub-phase that runs the bytecode verifier.

---

## 12. Cheat-sheet & self-test

### Dense recap (one screen)

- **Lifecycle:** Load → **Verify → Prepare → Resolve** (=Link) → Initialize → (Unload). Prepare = defaults; Initialize = `<clinit>` once, on active use.
- **Loaders:** Bootstrap (`null`, `java.base`) → Platform (JDK modules) → Application (classpath). Parent-first delegation; child-first in web containers (guard `java.*`).
- **Identity = (binary name, defining loader).** Same name + different loader ⇒ incompatible types ⇒ "Foo cannot be cast to Foo."
- **Defining loader** = the one that called `defineClass`. **Initiating loader** = any whose `loadClass` was called.
- **Triggers init:** `new`, static method, non-constant static field, `Class.forName` (default), subclass init, entry point. **Does NOT:** `.class` literal, `forName(...,false,...)`, `loadClass`, reading a compile-time constant, declaring a field, `new T[]`.
- **`Class.forName` initializes; `ClassLoader.loadClass` does not.**
- **Errors:** CNFE (checked, explicit lookup) vs NCDFE (Error, implicit ref OR failed `<clinit>`). `VerifyError`/`NoSuchMethodError`/`UnsupportedClassVersionError`/loader-constraint = `LinkageError` family. CCE-across-loaders = identity issue.
- **Metaspace** (native, Java 8+, replaced PermGen). `MetaspaceSize` = first-GC threshold (~20 MB), **not** a floor. `MaxMetaspaceSize` = **unlimited by default** → set it. Compressed class space default 1 GB reserved.
- **Unloading:** only when the whole `ClassLoaderData` is unreachable (loader + all its Classes + all instances). Built-in loaders never unload. Pins: thread-locals, JDBC drivers, live threads' TCCL, static caches, MBeans, shutdown hooks.
- **Class versions:** 52=Java 8, 55=11, 61=17, 65=21.
- **Tools:** `-Xlog:class+load/unload`, `-verbose:class`, `jcmd VM.classloader_stats`, `jmap -clstats`, MAT path-to-GC-roots, `javap -v` for version.

### Self-test (no answers — recall actively)

1. A class is on the classpath yet you get `NoClassDefFoundError` on first use. List the two distinct root causes and how you'd confirm each from logs.
2. Write the exact `loadClass` delegation algorithm from memory, including where the per-name lock and `findLoadedClass` fit, and explain why a child-first loader must special-case `java.*`.
3. You have one jar containing `com.acme.Codec` loaded by two sibling `URLClassLoader`s whose parent is the platform loader. Predict the result of `==` on the two `Class` objects, of an `instanceof` check, and of a cross-cast — and justify each using the identity rule.
4. After 12 redeploys a Tomcat node throws `OutOfMemoryError: Metaspace`. Give the full diagnosis workflow (commands + analysis steps) and the three most likely pins, with the fix for each.
5. Explain precisely why the initialization-on-demand holder idiom is thread-safe and lazy without any `synchronized` or `volatile`, citing the JVM guarantee that makes it work.
6. Distinguish `MetaspaceSize` from `MaxMetaspaceSize`, give the default behavior of each, and explain why leaving `MaxMetaspaceSize` unset is dangerous in production.
7. Design a hot-reloadable plugin system and explain, type-by-type, which classes must be loaded by the parent vs. the plugin loader, and how you guarantee the old version's classes actually unload.


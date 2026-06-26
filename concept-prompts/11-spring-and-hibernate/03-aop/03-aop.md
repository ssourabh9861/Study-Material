# Aspect-Oriented Programming (AOP) — Spring & Hibernate

> An exhaustive engineering reference for senior JVM backend developers. Covers AOP from first principles through Spring's proxy-based internals, AspectJ weaving, the implementation of `@Transactional`/`@Async`/`@Cacheable`, pointcut grammar, production concerns, and interview-grade depth.

---

## 1. Overview & where it fits

**Aspect-Oriented Programming (AOP)** is a programming paradigm that lets you *modularize cross-cutting concerns* — behavior that is needed in many places across an application but does not belong to the *core business logic* of any one of them. Logging, security checks, transaction management, caching, metrics/timing, retry, and auditing are the canonical examples. Without AOP, that code gets copy-pasted into every method that needs it (scattering) and gets tangled together with business logic (tangling). AOP pulls that behavior into one place — an **aspect** — and *weaves* it back into the target code at well-defined points, without the target code being aware.

**The problem it solves.** Consider a service with 40 methods. Suppose every one of them must: open a database transaction, log entry/exit, record a timer metric, and verify the caller has a role. With plain object-oriented code you write those four lines (or four wrapper blocks) 40 times. Change the logging format and you edit 40 methods. AOP collapses all four concerns into four small aspects; the 40 methods stay focused purely on business rules. The concern is defined *once* and *applied declaratively* (often by an annotation or a pattern match) to all 40.

**When you reach for it.**
- The behavior is **orthogonal** to business logic (it would be the same regardless of *what* the method does).
- The behavior is **repeated** across many types/methods.
- You want the behavior **applied declaratively** (annotation, naming convention, package) rather than hand-wired.
- Classic uses: transactions (`@Transactional`), security (`@PreAuthorize`), caching (`@Cacheable`), async dispatch (`@Async`), retry, rate limiting, method-level metrics/tracing, audit logging.

**When you do *not* reach for it.** When the behavior is intrinsic to one method, when control flow must be obvious to a reader (AOP is "spooky action at a distance"), or when you need behavior to apply to *every* call including internal calls within the same object — Spring's proxy-based AOP famously *cannot* do that last one (see §3 and §9).

**One-paragraph mental model.** Think of AOP as *programmable interception*. You declare two things: **where** to intercept (a **pointcut** — a predicate over points in program execution) and **what to do** there (an **advice** — code that runs before/after/around the intercepted call). A tool called a **weaver** then arranges for your advice to run at the matched points. In Spring, the weaver works at runtime by wrapping your bean in a **proxy** object: callers actually hold the proxy, the proxy runs the advice, then delegates to your real object. In AspectJ, the weaver instead rewrites the bytecode of your classes (at compile or load time) so the advice is baked directly in. Everything else in this document is detail on those two ideas: *what counts as a point*, and *how the weaving is implemented*.

---

## 2. Foundations from first principles

### 2.1 Cross-cutting concerns, scattering, tangling

A **concern** is any distinct requirement or responsibility of a program (e.g., "persist orders", "log every request", "enforce permissions"). A **core concern** is the primary business purpose of a module. A **cross-cutting concern** is one that *cuts across* many modules instead of living in one — security, logging, transactions, etc.

Two pathologies arise when you implement cross-cutting concerns with ordinary OOP:

- **Scattering** — the same concern's code appears in many places (the logging snippet copied into 40 methods). One logical decision is physically duplicated.
- **Tangling** — within a single method, multiple concerns are interleaved (business logic + transaction handling + logging + auth all in one method body). The method does five things at once.

AOP exists to fix both: scattering by defining the concern once, tangling by physically separating it from business code.

### 2.2 The vocabulary (define each term precisely)

These are the canonical AOP terms (standardized largely by AspectJ; Spring reuses them). Memorize them — every framework, doc, and interview uses exactly these words.

- **Aspect** — a module that encapsulates a cross-cutting concern. In Spring it is typically a class annotated `@Aspect` containing pointcuts and advice. (Analogy: a class is to a normal concern what an aspect is to a cross-cutting concern.)

- **Join point** — a *point during the execution of a program* where an aspect *could* be plugged in. In full AspectJ, join points include method execution, method call, constructor execution, field read/write, exception handler execution, static initializer, etc. **In Spring AOP, the only kind of join point is *method execution*** (specifically, execution of a Spring-managed bean's method). This is a critical limitation: Spring cannot advise field access, constructors, or `static` methods.

- **Pointcut** — a *predicate* that selects join points. It is the "where". A pointcut is written in a small expression language (the **AspectJ pointcut expression language**, a subset of which Spring supports) — e.g., `execution(* com.acme.service.*.*(..))` means "the execution of any method in any class in package `com.acme.service`". Advice is associated with a pointcut.

- **Advice** — the action taken by an aspect at a matched join point. It is the "what". Advice has a **type** that says *when* it runs relative to the join point:
  - **`@Before`** — runs before the join point. Cannot prevent the join point from running (unless it throws).
  - **`@AfterReturning`** — runs after the join point completes *normally*; can read the returned value.
  - **`@AfterThrowing`** — runs after the join point exits by *throwing* an exception; can read the exception.
  - **`@After`** (a.k.a. "after finally") — runs after the join point regardless of outcome (normal or exception), like a `finally` block.
  - **`@Around`** — the most powerful: wraps the join point. It receives a `ProceedingJoinPoint`; it decides *whether*, *when*, and *with what arguments* to call `proceed()` (which actually invokes the join point), and it can transform the return value or swallow/replace exceptions. Around advice can short-circuit the target entirely.

- **Target object** — the object whose method is being advised (your real bean). In proxy-based AOP, the target is wrapped by a proxy.

- **Proxy** — an object created by the AOP framework that stands in for the target. Callers interact with the proxy; the proxy applies advice and then (usually) delegates to the target. (See §3 for JDK vs CGLIB proxies.)

- **Weaving** — the process of linking aspects with target objects to create the advised objects. *When* weaving happens defines three strategies:
  - **Compile-time weaving (CTW)** — at compilation, a special compiler (the AspectJ compiler `ajc`) injects advice into the `.class` files.
  - **Load-time weaving (LTW)** — at class-load time, a Java agent (the AspectJ weaving agent) intercepts class loading and rewrites bytecode in memory.
  - **Runtime weaving** — no bytecode change to your class; instead a proxy object is generated at runtime to intercept calls. **This is what Spring AOP uses.**

- **Introduction** (a.k.a. **inter-type declaration**, ITD) — declaring additional methods or fields, or making a target implement a new interface, via an aspect. Spring supports a limited form (`@DeclareParents`); AspectJ supports full ITDs.

- **AOP proxy** — in Spring specifically, the proxy that implements the AOP behavior, created by either a JDK dynamic proxy or a CGLIB subclass.

- **Advisor** — a Spring-specific term (from Spring's lower-level API) meaning *a pointcut + a single advice bundled together*. The `@Aspect`/`@Before` model is sugar on top of advisors.

### 2.3 A first mental picture

Imagine `OrderService.placeOrder()`. You want every call logged. With AOP:

1. You write an aspect `LoggingAspect` with a pointcut "any method on `OrderService`" and `@Around` advice that logs entry, calls `proceed()`, logs exit.
2. Spring, at startup, sees `OrderService` is targeted by an aspect and creates a **proxy** of `OrderService`.
3. Anything that `@Autowired`-injects `OrderService` actually receives the proxy.
4. A caller calls `proxy.placeOrder()`. The proxy runs the logging advice, calls the real `placeOrder()`, runs the exit logging, returns.

The business code never mentions logging. That is AOP.

### 2.4 How AOP relates to other techniques

- **Decorator / Proxy design patterns** — AOP is essentially the proxy/decorator pattern *automated and generalized*. Spring AOP literally builds proxies; AOP saves you from hand-writing one decorator per concern per class.
- **Interceptors / Filters** — Servlet filters and Spring `HandlerInterceptor`s are coarse-grained interception at the web layer. AOP is finer (any bean method) and not tied to HTTP.
- **Annotations + reflection** — you *could* write a framework that scans annotations and dispatches behavior; AOP is the principled, reusable form of that idea.
- **Dependency Injection (DI)** — AOP and DI are complementary pillars of Spring. DI assembles your objects; AOP decorates them. Spring's AOP is bootstrapped *by* the DI container: it post-processes beans and swaps them for proxies (see §3).

> **Beginner aside — what is a "bean"?** In Spring, a *bean* is simply an object that the Spring container (the **ApplicationContext**) creates, configures, and manages. You declare beans via `@Component`/`@Service`/`@Bean` etc., and Spring instantiates and wires them. AOP in Spring only applies to *beans* — objects you create yourself with `new` are invisible to it.

> **Beginner aside — what is a "proxy"?** A proxy is a stand-in object that exposes the same interface as another object (the target) and forwards calls to it, optionally doing extra work before/after. To the caller it looks identical to the target. This is the mechanism Spring uses to inject advice without touching your source.

---

## 3. How it works internally (the heart of the doc)

This section traces, step by step, exactly how Spring turns a plain bean into an advised one and how a call flows through. Then it contrasts with AspectJ weaving.

### 3.1 The two implementation families

| Strategy | When weaving happens | What gets changed | Used by |
|---|---|---|---|
| **Proxy-based (runtime)** | Application startup | A new proxy object wraps the target; target class untouched | **Spring AOP** |
| **Compile-time weaving (CTW)** | At `ajc` compile | Your `.class` bytecode is rewritten | AspectJ |
| **Post-compile / binary weaving** | After compile, on existing jars | Bytecode in jars rewritten | AspectJ |
| **Load-time weaving (LTW)** | At class load (JVM agent) | Bytecode rewritten in memory | AspectJ (+ Spring can drive it) |

Spring AOP = runtime proxies. AspectJ = the other three. Spring can *also* drive AspectJ LTW if you want full AspectJ power inside Spring, but the default "Spring AOP" everyone uses is proxies.

### 3.2 Spring's startup: how a bean becomes a proxy

Spring AOP is implemented through a **`BeanPostProcessor`**. A `BeanPostProcessor` is a container extension point: a hook Spring calls for *every* bean after it is instantiated and dependency-injected, giving the hook a chance to wrap or replace the bean before it is handed out.

The specific post-processor is **`AnnotationAwareAspectJAutoProxyCreator`** (an `AbstractAutoProxyCreator`). It is registered automatically when you use `@EnableAspectJAutoProxy` or when Spring Boot's `AopAutoConfiguration` runs.

Step by step at startup:

1. **Aspect discovery.** The auto-proxy creator scans the context for beans annotated `@Aspect`. For each, it parses the pointcuts and advice into Spring **Advisor** objects (pointcut + advice pairs). Built-in advisors (e.g., for `@Transactional`) are added too.

2. **Per-bean candidacy check.** As each ordinary bean finishes initialization, the post-processor's `postProcessAfterInitialization` runs. It asks: *does any advisor's pointcut match any method of this bean's class?* This match is computed by evaluating pointcut expressions against the class's methods.

3. **If no advisor matches** — the original bean is returned unchanged. (No proxy = no overhead. This is important: only advised beans pay any cost.)

4. **If at least one advisor matches** — Spring builds a proxy:
   - It collects all matching advisors, **orders** them (by `@Order`/`Ordered`, and `@Aspect` precedence), and builds a chain.
   - It chooses a **proxy mechanism** (JDK dynamic proxy or CGLIB — see §3.4).
   - It instantiates the proxy, whose internal handler holds: the target object, the ordered advisor chain, and config.

5. **The proxy replaces the bean** in the container. Every injection point that depends on this bean now receives the *proxy*, not the raw target.

> **Beginner aside — `@EnableAspectJAutoProxy`.** Despite the name, this enables Spring's *proxy-based* AOP using *AspectJ-style annotations* (`@Aspect`, `@Before`...). It does **not** turn on real AspectJ weaving. Spring Boot enables it by default when `spring-boot-starter-aop` is on the classpath, so you rarely write it explicitly.

### 3.3 Runtime: how a call flows through the proxy

When a caller invokes a method on the proxy:

1. The proxy intercepts the call (via the JDK `InvocationHandler` or the CGLIB `MethodInterceptor`).
2. Spring builds a **`ReflectiveMethodInvocation`** representing this call, holding the target, method, args, and the ordered list of **interceptors** (each advisor becomes a `MethodInterceptor`).
3. The invocation is a **chain** (the *interceptor chain*), executed like nested function calls / an onion:
   - The first interceptor runs its "before" portion, then calls `invocation.proceed()`.
   - `proceed()` advances to the next interceptor, which does the same.
   - When the chain is exhausted, `proceed()` finally invokes the **real target method** via reflection.
   - As the stack unwinds, each interceptor's "after" portion runs in reverse order.
4. `@Around` advice maps directly onto this: its `ProceedingJoinPoint.proceed()` *is* the call to `invocation.proceed()`. Before/AfterReturning/AfterThrowing/After advices are adapted into interceptors that run their logic in the right slot.

Concretely, for advice ordered A (priority 1) then B (priority 2) wrapping target `m()`:

```
A.before
  B.before
    m()        <-- real target
  B.after
A.after
```

So **lower `@Order` value = outermost = runs first on the way in, last on the way out.**

### 3.4 JDK dynamic proxies vs CGLIB

Spring has two ways to build the proxy object:

**JDK dynamic proxy** (`java.lang.reflect.Proxy`):
- Built into the JDK. Creates a proxy class *implementing one or more interfaces* at runtime.
- Requires the target to **implement at least one interface**. The proxy is a sibling of the target (both implement the interface); the proxy is **not** a subclass of the target.
- Calls dispatch through an `InvocationHandler.invoke(proxy, method, args)`.
- **Consequence:** you must inject the bean *by its interface type*. Injecting by the concrete class fails (`ClassCastException` / no matching bean) because the JDK proxy is not an instance of the concrete class.

**CGLIB proxy** (Spring bundles CGLIB, repackaged under `org.springframework.cglib`):
- Generates a **subclass** of the target class at runtime and overrides its methods to insert interception (using a `MethodInterceptor` / `Enhancer`).
- Works even when the target implements **no interface**.
- **Cannot proxy `final` classes or `final`/`private`/`static` methods** — a subclass cannot override them, so they are silently *not advised*.
- Historically required a no-arg constructor / used Objenesis to bypass it; modern Spring uses Objenesis so a default constructor is not required.
- On the JVM, generating subclasses interacts with module/illegal-access rules; on Java 16+ deep reflection can require `--add-opens` in edge cases.

**Which does Spring pick?**

| Condition | Proxy used |
|---|---|
| Target implements ≥1 interface AND `proxyTargetClass=false` (default historically) | **JDK dynamic proxy** |
| Target implements no interface | **CGLIB** (only option) |
| `proxyTargetClass=true` (or `@EnableAspectJAutoProxy(proxyTargetClass=true)`) | **CGLIB** always |
| **Spring Boot default** | **CGLIB** — Boot sets `proxyTargetClass=true` by default since Boot 2.x for both `@EnableAspectJAutoProxy` and `@EnableTransactionManagement` |

> **Version flag.** Plain Spring Framework defaults to JDK proxies when an interface exists; **Spring Boot flips the default to CGLIB** (`spring.aop.proxy-target-class=true`). This is a frequent source of confusion. To force JDK proxies in Boot, set `spring.aop.proxy-target-class=false` (and ensure interfaces exist).

### 3.5 The two great limitations of proxy-based AOP

Because Spring AOP advises only via an *external proxy*, two rules follow that trip up nearly everyone:

**(1) Self-invocation bypasses the proxy.** If a method of the target calls *another advised method on the same object* using `this.otherMethod()`, the call goes directly through `this` (the raw target), **not** through the proxy — so the advice on `otherMethod()` does **not** run. The proxy only intercepts calls that arrive *from outside* the object.

```java
@Service
public class InvoiceService {

    @Transactional            // advice present
    public void outer() {
        inner();              // self-call: bypasses proxy -> @Transactional on inner() IGNORED
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { ... }  // expected a NEW transaction; gets none here
}
```
Here `inner()`'s `@Transactional` is silently ineffective when reached via `outer()`. The same applies to `@Async`, `@Cacheable`, `@Retryable`, etc. (See §9 for fixes.)

**(2) Only externally-visible, overridable, public-ish methods get advised.**
- Spring AOP advises **`public` methods** by default. `protected`/package-private/`private` methods are generally **not** advised (with JDK proxies only interface methods, which are public, are even visible; with CGLIB, `private`/`final`/`static` cannot be overridden).
- `static` methods are never advised (no instance to proxy).
- Methods on objects you created with `new` (non-beans) are never advised.

These are not bugs; they are direct consequences of "advice lives in an outer proxy". AspectJ (which rewrites the class itself) has none of these limits.

### 3.6 AspectJ weaving internals (for contrast)

AspectJ does not use proxies. Instead, its weaver edits bytecode:

- **CTW with `ajc`:** the AspectJ compiler compiles your `.java` and `.aj` (or annotation-style aspects) together, emitting `.class` files in which advice bytecode is inlined at the matched join points. Method bodies literally contain the advice calls.
- **Binary weaving:** `ajc` (or the weaver) processes already-compiled jars, rewriting their bytecode.
- **LTW:** a JVM agent (`-javaagent:aspectjweaver.jar`) hooks the class loader. As each class loads, the weaver consults `META-INF/aop.xml`, matches join points, and rewrites the bytecode in memory before the class is defined.

Because AspectJ touches the class itself, it can advise: constructors, field get/set, `static` methods, `final` methods (it edits, not overrides), `private` methods, and — crucially — **self-invocations** (the advice is *in* the method, so it runs no matter who called it). It also has zero proxy/dispatch overhead at runtime (advice is inlined). The cost is build/deploy complexity and a learning curve.

### 3.7 How `@Transactional` is implemented (a full trace)

`@Transactional` is the most important AOP-powered feature in Spring; understanding it cements the whole model.

1. `@EnableTransactionManagement` (auto-enabled by Spring Boot) registers a **`BeanFactoryTransactionAttributeSourceAdvisor`** — an advisor whose pointcut matches any method/class annotated `@Transactional`, and whose advice is the **`TransactionInterceptor`**.
2. At startup, the auto-proxy creator sees this advisor matches your `@Transactional` bean and proxies it (CGLIB in Boot).
3. At call time, the interceptor chain reaches `TransactionInterceptor.invoke()`. It:
   - Reads the **transaction attributes** (propagation, isolation, timeout, readOnly, rollbackFor) from the annotation via a `TransactionAttributeSource`.
   - Asks the configured **`PlatformTransactionManager`** (e.g., `DataSourceTransactionManager`, `JpaTransactionManager`) to *get a transaction* per the propagation rule (e.g., `REQUIRED` joins or creates).
   - Calls `proceed()` to run your real method.
   - On normal return → `commit()`. On a `RuntimeException`/`Error` (by default) → `rollback()`. On checked exceptions → commit by default unless `rollbackFor` says otherwise.
4. Because this is proxy-based, **all the proxy limitations apply**: self-invocation, non-public methods (`@Transactional` on `private` methods is ignored), and `final` methods (with CGLIB they can't be advised) silently lose transactionality. This is the #1 production AOP gotcha (see §9).

> **Beginner aside — propagation.** *Propagation* controls how an existing transaction interacts with a new `@Transactional` call. `REQUIRED` (default) joins an existing tx or starts one. `REQUIRES_NEW` suspends any current tx and starts a brand-new independent one. `NESTED` uses a savepoint. These behaviors are enforced by the `TransactionInterceptor` — and only work when the call actually goes through the proxy.

### 3.8 How `@Async` and `@Cacheable` are implemented

- **`@Async`** — `@EnableAsync` registers an `AsyncAnnotationBeanPostProcessor` that proxies beans with `@Async` methods. The advice (`AsyncExecutionInterceptor`) submits the method invocation to a `TaskExecutor` (thread pool) instead of running it inline, returning a `Future`/`CompletableFuture`/`void`. Same proxy limits: self-invocation of an `@Async` method runs synchronously; `@Async` on `private` methods is ignored; the return type must be `void` or a future.

- **`@Cacheable` / `@CacheEvict` / `@CachePut`** — `@EnableCaching` registers a `CacheInterceptor` (via `BeanFactoryCacheOperationSourceAdvisor`). On call, the interceptor computes a cache **key** (default `SimpleKeyGenerator` over the args, or a SpEL `key` expression), checks the `CacheManager`'s cache: on hit it returns the cached value *and skips the target method entirely* (an `@Around`-style short-circuit); on miss it proceeds, stores the result, and returns it. Again: self-invocation and non-public methods bypass it.

All three are the same machine — an advisor (pointcut over an annotation) + an interceptor — proving the value of understanding the core model once.

---

## 4. The complete toolkit

### 4.1 Annotations (Spring AOP / AspectJ annotation style)

| Annotation | Purpose | Key attributes | Notes |
|---|---|---|---|
| `@Aspect` | Marks a class as an aspect | — | The class must also be a Spring bean (`@Component`) to be picked up by Spring AOP. |
| `@Pointcut` | Names a reusable pointcut expression | the expression string; method name = pointcut id | Method body is empty; signature defines bindable params. |
| `@Before` | Advice before join point | pointcut expr or named pointcut | Can't access return value. |
| `@AfterReturning` | After normal return | `pointcut`, `returning` (binds return value) | |
| `@AfterThrowing` | After exception | `pointcut`, `throwing` (binds exception) | Re-throws unless you handle. |
| `@After` | After (finally) | pointcut | Runs on success or failure. |
| `@Around` | Wraps join point | pointcut; method takes `ProceedingJoinPoint` | Must call `proceed()` (or deliberately not). Most powerful. |
| `@Order` / `Ordered` | Aspect precedence | `value` (int) | Lower = higher precedence = outermost. |
| `@EnableAspectJAutoProxy` | Turn on proxy AOP | `proxyTargetClass`, `exposeProxy` | Boot enables automatically. |
| `@DeclareParents` | Introduction (add interface/mixin) | `value`, `defaultImpl` | Spring's limited ITD support. |

### 4.2 Advice method API objects

| Type | Provides | Use in |
|---|---|---|
| `JoinPoint` | `getArgs()`, `getSignature()`, `getTarget()`, `getThis()`, `getKind()` | Before/After/AfterReturning/AfterThrowing |
| `ProceedingJoinPoint` (extends `JoinPoint`) | adds `proceed()` and `proceed(Object[] args)` | `@Around` only |
| `Signature` / `MethodSignature` | method name, declaring type, return type, parameter types/names | introspection |

- `getTarget()` returns the **raw target** object; `getThis()` returns the **proxy**. Useful to detect/work around self-invocation.

### 4.3 Pointcut designators (PCDs) supported by Spring AOP

Spring supports a *subset* of AspectJ PCDs. Key ones:

| Designator | Matches | Example |
|---|---|---|
| `execution(...)` | **Method execution** (the workhorse) | `execution(* com.acme..*Service.*(..))` |
| `within(...)` | Any join point within given types | `within(com.acme.service..*)` |
| `this(Type)` | When the **proxy** is instanceof Type | `this(com.acme.Marker)` |
| `target(Type)` | When the **target** is instanceof Type | `target(com.acme.Repo)` |
| `args(...)` | Argument types/values at runtime | `args(Long, ..)` |
| `@target(Ann)` | Target class annotated with Ann | `@target(org.springframework.stereotype.Service)` |
| `@within(Ann)` | Within types annotated with Ann | `@within(com.acme.Audited)` |
| `@annotation(Ann)` | Method annotated with Ann | `@annotation(com.acme.Timed)` |
| `@args(Ann)` | Runtime arg's type annotated with Ann | `@args(com.acme.Validated)` |
| `bean(idPattern)` | Spring bean name match (Spring-only PCD) | `bean(*Service)` |

**Not supported in Spring AOP** (AspectJ-only because there's no method-execution join point for them): `call`, `get`, `set`, `cflow`, `cflowbelow`, `initialization`, `preinitialization`, `staticinitialization`, `handler`. Using them throws an `IllegalArgumentException` at startup.

### 4.4 The `execution()` pattern grammar

```
execution(modifiers-pattern?  ret-type-pattern  declaring-type-pattern? name-pattern(param-pattern)  throws-pattern?)
```
- `*` = any single token (one return type, one name segment, etc.).
- `..` = "any number of" — in package position (`com.acme..`) means "this package and all sub-packages"; in param position (`(..)`) means "any number of args of any type".
- `+` after a type = "that type and all subtypes": `target(com.acme.Repo+)`.

Examples:
- `execution(public * *(..))` — every public method.
- `execution(* set*(..))` — every method named `set...`.
- `execution(* com.acme.service.*.*(..))` — all methods of classes directly in `com.acme.service`.
- `execution(* com.acme.service..*.*(..))` — same but including sub-packages.
- `execution(String com.acme.UserService.find*(Long, ..))` — methods returning `String`, named `find*`, first arg `Long`.

Combine with boolean operators: `&&`, `||`, `!`.

```java
@Pointcut("execution(* com.acme.service..*(..))")
void inService() {}

@Pointcut("@annotation(com.acme.Timed)")
void timed() {}

@Around("inService() && timed()")
public Object x(ProceedingJoinPoint pjp) throws Throwable { ... }
```

### 4.5 Configuration flags

| Flag / property | Effect | Default |
|---|---|---|
| `spring.aop.auto` (Boot) | Enable Spring AOP auto-config | `true` |
| `spring.aop.proxy-target-class` (Boot) | Force CGLIB | `true` in Boot |
| `proxyTargetClass` on `@EnableAspectJAutoProxy` | Force CGLIB | `false` (plain Spring) |
| `exposeProxy` on `@EnableAspectJAutoProxy` | Bind current proxy to `AopContext` | `false` |
| `@EnableTransactionManagement(proxyTargetClass=, mode=)` | Tx proxy mode; `mode=ASPECTJ` switches to AspectJ weaving | `PROXY` |
| `-javaagent:aspectjweaver.jar` | Enable AspectJ LTW | off |

`exposeProxy=true` plus `((MyType) AopContext.currentProxy()).inner()` is the official workaround for self-invocation (see §9).

### 4.6 Lower-level Spring AOP API (rarely written by hand)

`Advisor`, `Pointcut`, `MethodMatcher`, `ClassFilter`, `MethodInterceptor` (AOP Alliance), `ProxyFactory`, `AspectJExpressionPointcut`, `NameMatchMethodPointcut`, `DefaultPointcutAdvisor`. You program these when building proxies manually (e.g., in library code) instead of using `@Aspect`.

---

## 5. Code examples by use case

### 5.1 Method-timing / metrics aspect (`@Around` + custom annotation)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {
    String value() default "";   // optional metric name
}

@Aspect
@Component
public class TimingAspect {

    private static final Logger log = LoggerFactory.getLogger(TimingAspect.class);
    private final MeterRegistry meters;   // Micrometer

    public TimingAspect(MeterRegistry meters) { this.meters = meters; }

    // Match any method annotated @Timed
    @Around("@annotation(timed)")
    public Object time(ProceedingJoinPoint pjp, Timed timed) throws Throwable {
        String name = timed.value().isEmpty()
                ? pjp.getSignature().toShortString()
                : timed.value();
        long start = System.nanoTime();
        boolean ok = true;
        try {
            return pjp.proceed();            // run the real method
        } catch (Throwable t) {
            ok = false;
            throw t;                          // never swallow silently
        } finally {
            long ns = System.nanoTime() - start;
            meters.timer("method.timer", "name", name, "ok", String.valueOf(ok))
                  .record(ns, TimeUnit.NANOSECONDS);
            log.debug("{} took {} ms (ok={})", name, ns / 1_000_000.0, ok);
        }
    }
}
```
Note `@annotation(timed)` binds the annotation instance into the parameter named `timed`, letting you read its attributes.

### 5.2 Audit logging (`@AfterReturning` + `@AfterThrowing`)

```java
@Aspect
@Component
@Order(20)   // run inside transaction advice if Tx is @Order lower
public class AuditAspect {

    private final AuditTrail trail;
    public AuditAspect(AuditTrail trail) { this.trail = trail; }

    @Pointcut("within(@org.springframework.stereotype.Service *)")
    void anyService() {}

    @AfterReturning(pointcut = "anyService()", returning = "result")
    public void onSuccess(JoinPoint jp, Object result) {
        trail.record(user(), jp.getSignature().toShortString(), "OK");
    }

    @AfterThrowing(pointcut = "anyService()", throwing = "ex")
    public void onFailure(JoinPoint jp, Throwable ex) {
        trail.record(user(), jp.getSignature().toShortString(), "ERR:" + ex.getClass().getSimpleName());
    }

    private String user() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
```

### 5.3 Declarative retry with backoff (`@Around`)

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface Retry {
    int maxAttempts() default 3;
    long backoffMs() default 200;
    Class<? extends Throwable>[] on() default { Exception.class };
}

@Aspect @Component
public class RetryAspect {
    @Around("@annotation(retry)")
    public Object around(ProceedingJoinPoint pjp, Retry retry) throws Throwable {
        int attempt = 0;
        while (true) {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                attempt++;
                boolean retryable = Arrays.stream(retry.on()).anyMatch(c -> c.isInstance(t));
                if (!retryable || attempt >= retry.maxAttempts()) throw t;
                Thread.sleep(retry.backoffMs() * attempt);   // linear backoff
            }
        }
    }
}
```
(Production tip: prefer Spring Retry / Resilience4j over hand-rolled retry; this illustrates the mechanism.)

### 5.4 Permission check (`@Before` short-circuiting via exception)

```java
@Aspect @Component
public class SecurityAspect {

    @Before("@annotation(req)")
    public void check(RequiresRole req) {     // binds annotation by type
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean has = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + req.value()));
        if (!has) throw new AccessDeniedException("Need role " + req.value());
        // throwing here prevents the join point from running at all
    }
}
```

### 5.5 Argument validation / sanitation (`@Around` with arg binding)

```java
@Aspect @Component
public class TrimAspect {
    @Around("execution(* com.acme..*(String, ..)) && args(first, ..)")
    public Object trim(ProceedingJoinPoint pjp, String first) throws Throwable {
        Object[] args = pjp.getArgs();
        args[0] = first == null ? null : first.strip();   // mutate first arg
        return pjp.proceed(args);                          // proceed with new args
    }
}
```
`proceed(args)` is how you alter the arguments passed to the target — only `@Around` can do this.

### 5.6 Manual proxy (no `@Aspect`, low-level API)

```java
public class ManualProxyDemo {
    public static void main(String[] args) {
        OrderService target = new OrderServiceImpl();

        ProxyFactory pf = new ProxyFactory(target);
        pf.addAdvice((MethodInterceptor) inv -> {
            System.out.println("before " + inv.getMethod().getName());
            Object r = inv.proceed();
            System.out.println("after");
            return r;
        });
        // pf.setProxyTargetClass(true);  // force CGLIB
        OrderService proxy = (OrderService) pf.getProxy();
        proxy.place();   // advice runs
    }
}
```

### 5.7 Introduction / mixin via `@DeclareParents`

```java
public interface Auditable { Instant lastModified(); }
public class AuditableImpl implements Auditable {
    public Instant lastModified() { return Instant.now(); }
}

@Aspect @Component
public class IntroductionAspect {
    @DeclareParents(value = "com.acme.service.*+",
                    defaultImpl = AuditableImpl.class)
    public static Auditable mixin;   // all matching beans now implement Auditable
}
```
Now any service bean can be cast to `Auditable`. This is Spring's limited inter-type declaration.

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Proxy dispatch cost** is small but nonzero: an extra virtual call + interceptor-chain iteration + (for JDK proxies) reflective method invoke. For hot, fine-grained methods called millions of times, this matters; for service-layer methods it's negligible.
- **Only advised beans pay anything** — unmatched beans are returned un-proxied.
- **`execution()` is cheaper to match** than runtime PCDs like `args()`/`@args()`/`this()`/`target()` that require runtime type checks on each call. Prefer compile-time-decidable pointcuts (`execution`, `within`, `@annotation`) and keep dynamic matchers narrow.
- **AspectJ CTW/LTW** inline advice → near-zero per-call overhead; choose it for very hot paths if AOP is required there.
- **Avoid overly broad pointcuts** (`execution(* *(..))`) — they force matching work against every method of every bean and proxy half your container.

### 6.2 Correctness & concurrency
- Aspects are **singletons by default** and run on the **caller's thread** (except `@Async`, which moves work to a pool). Keep aspect state stateless or thread-safe; do not store per-request data in instance fields.
- `@Around` advice **must** either call `proceed()` or intentionally short-circuit, and must **propagate exceptions** unless deliberately handling them — swallowing a `Throwable` here silently breaks callers and transactions.
- Ordering matters: if your custom transactional-ish aspect must run inside the DB transaction, set its `@Order` *higher* (inner) than the tx advisor; to run outside (e.g., metrics around the whole tx), set it *lower* (outer). The tx advisor's default order is `Ordered.LOWEST_PRECEDENCE` (very outer) unless configured.

### 6.3 Security
- Don't log sensitive args/return values in logging aspects — redact.
- Security aspects (authz) are valid but Spring Security's own method security (`@PreAuthorize`) is itself AOP and battle-tested; prefer it over hand-rolled.
- Beware that AOP-based authz on a method bypassed by self-invocation provides **no protection** — a serious vulnerability if you rely on it.

### 6.4 Observability & testability
- Aspects are hard to see in stack traces — add log markers; the proxy frame (`...$$EnhancerBySpringCGLIB$$...` or `$Proxy123`) appears in stacks, a tell-tale that a bean is proxied.
- Test aspects through the *container* (Spring test context) so the proxy is actually applied; unit-testing the aspect class in isolation with `new` won't exercise weaving.
- Use `AopUtils.isAopProxy(bean)`, `AopUtils.isCglibProxy(bean)`, `AopUtils.isJdkDynamicProxy(bean)`, and `AopProxyUtils.ultimateTargetClass(bean)` to assert/inspect proxying in tests.

### 6.5 Production hardening / anti-patterns
- **Anti-pattern: relying on self-invocation working.** It doesn't (proxy). Restructure (see §9) or use AspectJ.
- **Anti-pattern: `@Transactional`/`@Async`/`@Cacheable` on `private`/`final`/`static` methods** — silently ineffective.
- **Anti-pattern: business logic inside aspects.** Aspects are for cross-cutting concerns; putting domain rules there hides behavior.
- **Anti-pattern: aspects with hidden mutation** (changing args/results unexpectedly) — surprising and hard to debug.
- **Anti-pattern: catch-and-swallow in `@AfterThrowing`/`@Around`** masking failures.
- **Anti-pattern: ordering left to chance** — declare `@Order` explicitly when multiple aspects target the same join points.
- **Hardening:** keep aspects fast (they run on every matched call), idempotent where retried, and free of blocking I/O on hot paths.

---

## 7. Advanced topics & deep internals

### 7.1 Advice ordering precisely
- Across **different aspects**, precedence is by `@Order`/`Ordered` (lower value = higher precedence = outermost). Without explicit order, ordering is **undefined**.
- Within a **single aspect**, when multiple advices match the same join point, the order is by advice type for the "in" path: `@Around` (entering) → `@Before` → join point → `@After`/`@AfterReturning`/`@AfterThrowing` → `@Around` (exiting). Behavior across same-type advices in one aspect historically followed declaration order but was version-dependent; Spring 5.2.7+ made same-aspect precedence deterministic (`@Around`/`@Before` by declaration order, after-advices in reverse). Don't rely on subtle same-aspect ordering — split into separate ordered aspects if it matters.

### 7.2 `exposeProxy` and `AopContext.currentProxy()`
Setting `exposeProxy=true` makes Spring stash the current proxy in a thread-local. Inside the target you can then do:
```java
((InvoiceService) AopContext.currentProxy()).inner();
```
This routes the self-call back through the proxy so `inner()`'s advice runs. It works but couples your code to AOP — use sparingly.

### 7.3 Proxying `final` and `private`
- CGLIB cannot override `final` methods → they are **not advised** and no error is thrown (silent). `final` *classes* cannot be CGLIB-proxied at all (startup error if a proxy is required).
- `private` methods are invisible to both proxy types.
- Kotlin classes/methods are `final` by default → a frequent surprise; use the `kotlin-spring` (all-open) plugin to open `@Component`/`@Transactional` classes.

### 7.4 Spring AOP vs AspectJ — capabilities

| Capability | Spring AOP (proxy) | AspectJ (weaving) |
|---|---|---|
| Join point types | method execution only | method/ctor exec & call, field get/set, handler, static init, etc. |
| Advise self-invocation | No | Yes |
| Advise `private`/`final`/`static` | No | Yes |
| Advise non-Spring objects | No | Yes |
| Constructors / field access | No | Yes |
| Runtime overhead | proxy dispatch | ~none (inlined) |
| Setup complexity | trivial (just Spring) | weaver/agent/compiler |
| `call()` pointcut | No | Yes |
| Where it runs | only Spring beans | any class |

Use Spring AOP for the 95% case (service-method cross-cutting concerns). Reach for AspectJ when you need to advise things proxies can't: domain objects, constructors, field access, self-invocation, or hot inner methods.

### 7.5 Driving AspectJ inside Spring
- **LTW:** add `aspectjweaver` as a `-javaagent`, a `META-INF/aop.xml`, and `@EnableLoadTimeWeaving` (or `<context:load-time-weaver/>`). Spring can also use an instrumentable classloader in some containers.
- **`@EnableTransactionManagement(mode = AdviceMode.ASPECTJ)`** makes even `@Transactional` use AspectJ weaving, which then *does* honor self-invocation and non-public methods (requires the `spring-aspects` module + weaving). Same option exists for `@EnableAsync` / `@EnableCaching`.

### 7.6 Multiple matching annotations / composite pointcuts
You can bind several annotations and combine PCDs. Beware: `@Around` advice that both binds an annotation and uses runtime PCDs is evaluated per call; keep the bound parameters minimal.

### 7.7 Interaction with proxying and `@Autowired` self-reference
A documented self-invocation workaround is to inject the bean **into itself** (`@Autowired private MyService self;` or via `ObjectProvider` to avoid early-cycle issues) and call `self.inner()`. Spring injects the proxy, so advice runs. Cleaner than `AopContext`.

### 7.8 Caching, generics, and bridge methods
Generic methods compile to bridge methods; pointcuts and annotation detection generally work, but edge cases with type erasure can cause `@annotation` matching to behave unexpectedly on synthetic/bridge methods. Spring usually resolves to the most specific method, but verify when advising generic interfaces.

---

## 8. Tradeoffs & decision frameworks

### 8.1 AOP vs explicit code

| | AOP | Explicit (hand-written) |
|---|---|---|
| Duplication | eliminated | high |
| Readability of control flow | lower (hidden) | higher (visible) |
| Coupling | concern centralized | concern scattered |
| Debuggability | harder (proxy frames) | straightforward |
| Best for | true cross-cutting, repeated, orthogonal concerns | one-off, locality-critical logic |

**Use AOP when…** the concern is orthogonal, repeated across many methods, and benefits from declarative application (tx, security, caching, metrics, audit, retry).

**Avoid AOP when…** the behavior is local to one method, the hidden control flow would confuse readers, or you need it to apply to self-calls/non-beans/constructors (then use AspectJ or just write the code).

### 8.2 Proxy mechanism decision

| Situation | Choose |
|---|---|
| You inject by interface, want lightest proxy | JDK dynamic proxy (`proxyTargetClass=false`, interface present) |
| No interface, or you inject by concrete class | CGLIB (`proxyTargetClass=true`) |
| Class or methods are `final` and must be advised | Neither proxy works → AspectJ |
| Spring Boot default | CGLIB (already set) |

### 8.3 Spring AOP vs AspectJ decision
- Default to **Spring AOP**.
- Switch to **AspectJ** only for: self-invocation requirements, advising fields/constructors/static/private/final, advising non-Spring objects, or extreme hot-path performance.

---

## 9. Failure modes & debugging

### 9.1 The classic: advice "doesn't run"
**Symptoms:** `@Transactional` not rolling back, `@Cacheable` not caching, `@Async` running synchronously, custom advice skipped.

**Most common causes (in order):**
1. **Self-invocation.** The method was reached via `this.method()` from within the same bean → proxy bypassed.
2. **Non-public method.** `@Transactional`/advice on `private`/protected/package-private method → ignored.
3. **`final` method/class with CGLIB** → cannot override → silently unadvised.
4. **Bean created with `new`** (not a Spring bean) → no proxy at all.
5. **Aspect not a bean** (missing `@Component`) or `@EnableAspectJAutoProxy` absent (rare in Boot).
6. **Wrong proxy type:** code injects the concrete class but a JDK proxy was created → `NoSuchBeanDefinitionException`/`ClassCastException` at startup, or unexpected un-proxied path.
7. **Pointcut typo** — expression matches nothing (no error; just silently inert).

**Diagnose with:**
```java
System.out.println(AopUtils.isAopProxy(bean));      // is it proxied at all?
System.out.println(AopUtils.isCglibProxy(bean));    // CGLIB?
System.out.println(AopUtils.isJdkDynamicProxy(bean));
System.out.println(AopProxyUtils.ultimateTargetClass(bean));
```
- Inspect the bean's `getClass().getName()` — a `$$EnhancerBySpringCGLIB$$` or `$ProxyN` suffix confirms proxying.
- Turn on `logging.level.org.springframework.aop=DEBUG` and `org.springframework.transaction=TRACE` (for tx) to see advisor matching and transaction begin/commit/rollback.
- For pointcut matching, temporarily broaden the pointcut and log in the advice to confirm it fires, then narrow.

**Fixes for self-invocation:**
- Move `inner()` to a **separate bean** and inject it (cleanest).
- Inject the bean into itself and call `self.inner()`.
- `exposeProxy=true` + `((Type) AopContext.currentProxy()).inner()`.
- Switch that concern to **AspectJ weaving** (mode=ASPECTJ or LTW).

### 9.2 Startup failures
- **"Cannot subclass final class"** — CGLIB asked to proxy a `final` class. Make it non-final, add an interface + use JDK proxy, or remove the advised behavior.
- **`BeanNotOfRequiredTypeException` / proxy `ClassCastException`** — JDK proxy created but you injected the concrete type. Inject the interface, or set `proxyTargetClass=true`.
- **`IllegalArgumentException` on pointcut** — used an AspectJ-only PCD (`call`, `get`, `set`, `cflow`) unsupported by Spring AOP.
- **Ordering/circular-proxy issues** — aspect depends on a bean it also advises; break the cycle with `@Lazy`/`ObjectProvider`.

### 9.3 Performance pathologies
- Broad pointcut (`execution(* *(..))`) proxies huge numbers of beans and adds dispatch everywhere → CPU/GC pressure; observe via profiler showing proxy/interceptor frames. Narrow the pointcut.
- Heavy work in an advice that runs on a hot method → latency spikes; move work async or off the hot path.

### 9.4 Real-world incident patterns
- A team added `@Transactional` to a `private` helper called only via self-invocation; data wrote without a transaction, so a mid-method failure left **partial, uncommitted-but-flushed** state and silent inconsistency — discovered only under load. Root cause: proxy self-invocation + non-public method (two of the limits at once).
- `@Async` on a method called internally ran on the request thread, so a "fire-and-forget" email send blocked the HTTP response under load.
- A logging aspect with a broad pointcut and synchronous file I/O became the bottleneck, adding tens of ms per call across the whole service.

### 9.5 Debugging toolkit summary

| Tool / setting | Use |
|---|---|
| `AopUtils.isAopProxy/isCglibProxy/isJdkDynamicProxy` | confirm proxying |
| `AopProxyUtils.ultimateTargetClass` | find real class behind proxy |
| `logging.level.org.springframework.aop=DEBUG` | advisor matching |
| `logging.level.org.springframework.transaction=TRACE` | tx begin/commit/rollback |
| `getClass().getName()` (look for `$$` / `$Proxy`) | quick proxy check |
| Profiler (async-profiler, JFR) | see interceptor-chain overhead |
| Breakpoint in `ReflectiveMethodInvocation.proceed` | watch the chain execute |

---

## 10. Interview drill

**Q1. What problem does AOP solve, in one sentence?**
*Model answer:* It modularizes cross-cutting concerns (logging, transactions, security, caching, metrics) so the same orthogonal behavior is defined once and applied declaratively across many methods, eliminating scattering and tangling.
- *Follow-up: name three cross-cutting concerns and why they cross-cut.* Transactions, security, logging — each is needed across most service methods regardless of business purpose.
- *Follow-up: difference between scattering and tangling?* Scattering = one concern duplicated across many places; tangling = many concerns interleaved within one method.

**Q2. Define aspect, join point, pointcut, advice, weaving.**
*Model answer:* Aspect = the module for a concern; join point = a point in execution where advice can apply; pointcut = a predicate selecting join points; advice = the code run at matched join points (before/after/around); weaving = linking aspects to targets to produce advised objects (compile-, load-, or runtime).
- *Follow-up: what join points does Spring AOP support?* Only **method execution** of Spring beans.
- *Follow-up: list the advice types and which can change the return value.* Before, AfterReturning, AfterThrowing, After, Around. Only **Around** can change the return value or args and short-circuit the target.

**Q3. How is Spring AOP implemented at runtime?**
*Model answer:* Via runtime **proxies**. A `BeanPostProcessor` (`AnnotationAwareAspectJAutoProxyCreator`) checks each bean against advisor pointcuts; matching beans are wrapped in a JDK dynamic proxy or CGLIB subclass. Calls flow through an ordered interceptor chain (`ReflectiveMethodInvocation.proceed()`), running advice around a reflective call to the target.
- *Follow-up: JDK proxy vs CGLIB — when each?* JDK when an interface exists and `proxyTargetClass=false`; CGLIB when no interface or `proxyTargetClass=true` (Boot default). JDK proxies are interface siblings; CGLIB subclasses the target.
- *Follow-up: why can't CGLIB advise `final` methods?* It works by overriding methods in a generated subclass; `final` can't be overridden, so it's silently skipped.

**Q4. (Senior signal) Why does self-invocation bypass Spring AOP, and how do you fix it without changing frameworks?**
*Model answer:* The advice lives in the proxy, not in the target. An internal `this.x()` call is dispatched directly on the raw target, so it never passes through the proxy and no advice runs. Fixes: extract `x()` into a separate bean; inject the bean into itself and call via the injected (proxied) reference; use `exposeProxy=true` + `AopContext.currentProxy()`; or switch that concern to AspectJ weaving.
- *Follow-up: which is cleanest and why?* Extracting into a separate collaborator — it removes the dependency on AOP mechanics and is explicit.
- *Follow-up: what would AspectJ do differently?* It rewrites the method's bytecode, so the advice is inside the method and runs regardless of caller, including self-calls.

**Q5. Trace exactly how `@Transactional` works.**
*Model answer:* `@EnableTransactionManagement` registers an advisor (`BeanFactoryTransactionAttributeSourceAdvisor`) whose pointcut matches `@Transactional` and whose advice is `TransactionInterceptor`. The bean is proxied. On call, the interceptor reads tx attributes, asks the `PlatformTransactionManager` to begin/join per propagation, calls `proceed()`, then commits on normal return or rolls back on `RuntimeException`/`Error` (checked exceptions commit by default unless `rollbackFor`).
- *Follow-up: default rollback rule?* Roll back on unchecked exceptions and `Error`; commit on checked exceptions unless configured.
- *Follow-up: `REQUIRED` vs `REQUIRES_NEW`?* REQUIRED joins/creates one tx; REQUIRES_NEW suspends the current and starts an independent one — but only when the call goes through the proxy.

**Q6. (Senior signal) Spring AOP vs AspectJ — how do you choose?**
*Model answer:* Default to Spring AOP for service-method cross-cutting concerns: zero setup, integrates with the container. Choose AspectJ when you must advise things proxies can't — self-invocation, `private`/`final`/`static`, constructors, field access, non-Spring objects — or for hot paths where inlined weaving beats proxy dispatch. Cost of AspectJ is the weaver/agent/compiler and build complexity.
- *Follow-up: what join points does AspectJ add?* Constructor exec/call, method *call* (not just execution), field get/set, exception handler, static init.
- *Follow-up: how to get AspectJ semantics for `@Transactional`?* `@EnableTransactionManagement(mode = AdviceMode.ASPECTJ)` with `spring-aspects` and weaving enabled.

**Q7. How do you control advice ordering?**
*Model answer:* Across aspects, use `@Order`/`Ordered`; lower value = higher precedence = outermost (runs first entering, last exiting). Without it, ordering is undefined. Within one aspect, the type order (Around→Before→target→after-advices) applies; same-type ordering became deterministic in Spring 5.2.7+, but it's safest to split into separately-ordered aspects.
- *Follow-up: how to ensure your aspect runs inside the transaction?* Give it higher order value (inner) than the tx advisor (which is very outer by default).

**Q8. What are the limitations of Spring AOP and why do they exist?**
*Model answer:* Only public-ish method execution on Spring beans is advised; self-invocation bypasses the proxy; `final`/`private`/`static` methods and `final`/non-bean classes aren't advised; no field/constructor join points. All stem from "advice lives in an external proxy that only intercepts external calls and can only override overridable methods."
- *Follow-up: how would you detect at runtime whether a bean is proxied?* `AopUtils.isAopProxy(bean)` and friends; or check the class name for `$$EnhancerBySpringCGLIB$$`/`$Proxy`.

**Q9. How is `@Cacheable` implemented and what's a subtle bug?**
*Model answer:* `@EnableCaching` registers a `CacheInterceptor`; on call it computes a key (SpEL or default key generator), checks the `CacheManager`; hit → return cached value, skipping the method; miss → proceed, store, return. Subtle bug: calling a `@Cacheable` method via self-invocation never caches; also a `null`/exception result handling and key-collision via a poor key generator.
- *Follow-up: how is the cache key derived by default?* `SimpleKeyGenerator` over the method arguments, unless a `key` SpEL expression is given.

**Q10. (Senior signal) You added `@Async` to a method and nothing runs asynchronously. Walk through diagnosis.**
*Model answer:* Check (1) is the method called via self-invocation? (2) is it `public`? (3) is `@EnableAsync` present? (4) does the return type support async (`void`/`Future`/`CompletableFuture`)? (5) is a `TaskExecutor` configured (else a default simple executor)? Confirm proxying via `AopUtils.isAopProxy`. Fix self-invocation by extracting to another bean.
- *Follow-up: what executor runs `@Async` by default?* If none defined, Spring uses a `SimpleAsyncTaskExecutor`-like default (creates a thread per task — not pooled), which is dangerous in production; define a bounded `ThreadPoolTaskExecutor`.

**Q11. What is an Advisor in Spring's lower-level API?**
*Model answer:* A pointcut + a single advice bundled together; the building block the `@Aspect` model compiles down to. Built-in advisors back `@Transactional`, `@Cacheable`, etc.

**Q12. Explain `proceed()` and the interceptor chain.**
*Model answer:* Each advisor becomes a `MethodInterceptor`; Spring builds a `ReflectiveMethodInvocation` holding the chain. Each interceptor does its "before" work then calls `proceed()`, recursing to the next; the final `proceed()` reflectively invokes the target. The stack unwinds running "after" logic in reverse. `@Around`'s `ProceedingJoinPoint.proceed()` is exactly this call.

---

## 11. Glossary

- **Advice** — code executed at a matched join point (before/after/around).
- **Advisor** — Spring's pointcut+advice pair; low-level AOP unit.
- **Aspect** — module encapsulating a cross-cutting concern (`@Aspect`).
- **AnnotationAwareAspectJAutoProxyCreator** — the `BeanPostProcessor` that creates Spring AOP proxies.
- **AspectJ** — full AOP language/weaver that edits bytecode (compile-/load-time) rather than using proxies.
- **`AopContext.currentProxy()`** — thread-local accessor for the current proxy (needs `exposeProxy=true`).
- **`BeanPostProcessor`** — Spring hook called for each bean post-construction; used to swap beans for proxies.
- **CGLIB** — library that generates a subclass at runtime to proxy classes without interfaces.
- **Cross-cutting concern** — behavior spanning many modules (logging, tx, security).
- **CTW / LTW** — compile-time / load-time weaving (AspectJ).
- **Inter-type declaration (introduction)** — adding methods/interfaces to a type via an aspect (`@DeclareParents`).
- **JDK dynamic proxy** — JDK-built proxy implementing the target's interface(s).
- **Join point** — a point in program execution where advice can apply; in Spring, only method execution.
- **`MethodInterceptor`** — AOP Alliance interface each advice maps to; runs in the chain.
- **Pointcut** — predicate selecting join points (AspectJ expression language).
- **PCD (pointcut designator)** — a keyword like `execution`, `within`, `@annotation` used to build pointcuts.
- **PlatformTransactionManager** — abstraction (`DataSourceTransactionManager`, `JpaTransactionManager`) that begins/commits/rolls back transactions for `@Transactional`.
- **ProceedingJoinPoint** — `@Around` parameter; `proceed()` invokes the join point.
- **Propagation** — `@Transactional` rule for interacting with existing transactions (`REQUIRED`, `REQUIRES_NEW`, `NESTED`...).
- **Proxy** — stand-in object exposing the target's interface and adding behavior.
- **`ReflectiveMethodInvocation`** — Spring object that drives the interceptor chain via `proceed()`.
- **Scattering** — one concern duplicated across many places.
- **Self-invocation** — a bean calling its own method via `this`, bypassing the proxy.
- **SpEL** — Spring Expression Language, used for cache keys / conditions.
- **Tangling** — multiple concerns interleaved in one method.
- **Target object** — the real bean being advised.
- **TaskExecutor** — thread-pool abstraction used by `@Async`.
- **TransactionInterceptor** — the advice implementing `@Transactional`.
- **Weaving** — linking aspects with targets to produce advised objects.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

- **AOP = programmable interception.** Pointcut (where) + Advice (what) + Weaving (how/when applied).
- **Advice types:** `@Before`, `@AfterReturning`, `@AfterThrowing`, `@After`, `@Around` (only Around can change return/args & short-circuit; receives `ProceedingJoinPoint`, must `proceed()`).
- **Spring AOP = runtime proxies.** Only **method execution** join points, only **Spring beans**, only **public-ish** methods.
- **Proxy choice:** interface + `proxyTargetClass=false` → **JDK proxy**; no interface or `proxyTargetClass=true` → **CGLIB**. **Spring Boot default = CGLIB.**
- **Two killer limits:** self-invocation bypasses proxy; `final`/`private`/`static` not advised.
- **`@Transactional`/`@Async`/`@Cacheable`** are all advisor+interceptor AOP → inherit all proxy limits.
- **Ordering:** lower `@Order` = outermost; runs first in, last out. Undefined without `@Order`.
- **Key PCDs:** `execution`, `within`, `@annotation`, `@target/@within`, `args`, `this/target`, `bean` (Spring-only). **Unsupported:** `call`, `get`, `set`, `cflow`.
- **`execution(mod? ret type? name(params) throws?)`**; `*`=one token, `..`=any (pkg subtree / any args), `+`=subtypes.
- **Self-invocation fixes:** separate bean • self-inject • `AopContext.currentProxy()` (`exposeProxy=true`) • AspectJ.
- **AspectJ** (CTW/LTW): edits bytecode → advises self-calls, `private`/`final`/`static`, constructors, fields; ~zero overhead; needs weaver.
- **Debug:** `AopUtils.isAopProxy/isCglibProxy/isJdkDynamicProxy`, `AopProxyUtils.ultimateTargetClass`, `logging.level.org.springframework.aop=DEBUG`, look for `$$EnhancerBySpringCGLIB$$`/`$Proxy` in class name.
- **`getTarget()`** = raw bean; **`getThis()`** = proxy.

### Self-test (no answers)

1. You add `@Cacheable` to a method and it never caches even on identical args, but a colleague's identical-looking method caches fine. List every reason this can happen and how you'd confirm each.
2. Explain why injecting a Spring AOP-proxied bean *by its concrete class* can fail at startup, and under what proxy setting it would succeed.
3. Write a pointcut that matches every public method returning `void` in any class under `com.acme.web` (including sub-packages) that is annotated `@Audited`, then explain the per-call matching cost of each component.
4. Two aspects (logging and a custom transactional aspect) target the same service method, and you need logging *outside* the transaction. Specify the `@Order` values relative to the tx advisor and justify the in/out execution order.
5. Contrast precisely what AspectJ can advise that Spring AOP cannot, and for each item explain the proxy limitation that causes the gap.
6. A `@Async` method blocks the request thread in production. Walk through the full diagnostic checklist and give two distinct fixes.
7. Implement (in pseudocode or Java) an `@Around` rate-limiting aspect keyed by a method argument, and identify which proxy limitations could make it silently inert.

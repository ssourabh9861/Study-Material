# IoC & Dependency Injection

> An exhaustive engineering-handbook chapter on Inversion of Control and Dependency Injection, centered on the Spring Framework but grounded in first principles that apply to any container or language.

---

## 1. Overview & where it fits

### 1.1 What it is

**Inversion of Control (IoC)** is a *design principle*: instead of your code controlling its own dependencies, control of those dependencies is *inverted* and handed to an external party (a framework, a container, a runtime). Your code no longer says "go get me the thing I need"; instead, "the thing I need" is given to it.

**Dependency Injection (DI)** is the most common *implementation technique* for IoC as it relates to object dependencies. With DI, an object does not construct or look up the collaborators it needs; those collaborators are *injected* (supplied from the outside) by an assembler — in Spring, that assembler is the **IoC container**.

A precise relationship to memorize:

- IoC is the **principle** (control over flow/wiring is inverted to the framework).
- DI is **one pattern** that achieves IoC for object wiring.
- The **Spring container** is a **concrete IoC container** that performs DI.

> **Adjacent term — "container":** In this context a *container* is a runtime object that creates, configures, assembles, and manages the lifecycle of your application objects. It is not the same as an OS container (Docker) or a Java EE web container (Tomcat). When Spring people say "the container," they almost always mean the `ApplicationContext`.

> **Adjacent term — "bean":** In Spring, a *bean* is simply an object that is instantiated, assembled, and managed by the Spring IoC container. It is not a special class; any plain Java object becomes a "bean" the moment the container owns its lifecycle. The name is historical (from JavaBeans).

### 1.2 The problem it solves

Without DI, objects wire themselves:

```java
public class OrderService {
    // OrderService decides EXACTLY which implementation it gets.
    private final PaymentGateway gateway = new StripePaymentGateway();
    private final InventoryClient inventory = new HttpInventoryClient("http://inv:8080");
}
```

This couples `OrderService` to:

- A **concrete class** (`StripePaymentGateway`) — you cannot swap to PayPal without editing `OrderService`.
- **Construction details** (the URL, timeouts, credentials) — configuration leaks into business logic.
- **Lifetime decisions** — is the gateway a singleton? thread-safe? pooled? `OrderService` has no idea and no control.

The pain manifests as:

1. **Rigidity** — changing one collaborator forces edits to its dependents.
2. **Untestability** — you cannot substitute a mock/stub; you are stuck with the real Stripe call in a unit test.
3. **Hidden wiring** — the dependency graph is scattered across constructors and `new` statements, impossible to see at a glance.
4. **Duplicated construction logic** — every place that needs a gateway re-creates it, re-reads config, re-establishes connections.

DI flips this. `OrderService` *declares* what it needs and receives it:

```java
public class OrderService {
    private final PaymentGateway gateway;
    private final InventoryClient inventory;

    // OrderService no longer chooses; it only declares its needs.
    public OrderService(PaymentGateway gateway, InventoryClient inventory) {
        this.gateway = gateway;
        this.inventory = inventory;
    }
}
```

Now `OrderService` depends on the **abstraction** (`PaymentGateway`), not a concrete class. Who builds the concrete `StripePaymentGateway`, with what URL and timeouts, is an *external* concern — the job of the container or the test harness.

### 1.3 When you reach for it

- Any non-trivial application with a graph of collaborating objects (services, repositories, clients, config).
- When you want to **swap implementations** by configuration or profile (real vs. mock, Stripe vs. PayPal, in-memory vs. JDBC).
- When you want **single-point lifecycle management** (one place that knows how many connection pools exist, when they open/close).
- When **testability** matters — DI is the single biggest enabler of fast, isolated unit tests on the JVM.

You generally do **not** need a full container for: tiny scripts, leaf utility classes with no dependencies, or value objects. DI the *principle* still helps (pass collaborators in); the *container* is overkill there.

### 1.4 One-paragraph mental model

> Think of the Spring container as a **factory + registry + lifecycle manager** for your objects. You hand it a set of *recipes* (bean definitions: "to make an `OrderService`, you need a `PaymentGateway` and an `InventoryClient`"). At startup it reads all recipes, computes the dependency graph, instantiates everything in the right order, injects each object's collaborators, runs initialization callbacks, and keeps singletons in a map keyed by name. Your code never calls `new` for managed objects; it declares needs and the container satisfies them. Shutdown reverses the process, running destruction callbacks.

---

## 2. Foundations from first principles

### 2.1 Coupling and cohesion (the why beneath the why)

**Coupling** is the degree to which one module depends on the internals of another. **Cohesion** is how focused a module is on a single responsibility. Good design seeks **low coupling, high cohesion**. The `new StripePaymentGateway()` example is *high coupling*: `OrderService` is welded to a specific class, its constructor signature, and its configuration.

DI reduces coupling by inserting an **abstraction seam**. The dependent talks to an interface; the implementation is chosen elsewhere. This is a direct application of two SOLID principles:

> **SOLID** is a mnemonic for five object-oriented design principles. The two most relevant here:
> - **D — Dependency Inversion Principle (DIP):** High-level modules should not depend on low-level modules; both should depend on abstractions. (Note: DIP and "DI" are related but distinct — DIP is the *goal*, DI is one *means*.)
> - **O — Open/Closed Principle:** Open for extension, closed for modification. With DI you extend behavior by supplying a new implementation, not by editing the consumer.

### 2.2 The three ways an object can get a dependency

1. **Construct it itself** — `new Foo()`. Maximum coupling. No seam.
2. **Look it up** (Service Locator) — ask a global registry: `registry.get(Foo.class)`. A seam exists, but the dependency is *hidden* (you must read the body to find it) and the object still controls the timing.
3. **Have it injected** — receive it as a constructor parameter, a setter argument, or a reflectively-set field. The dependency is *explicit* and external control is complete. This is DI.

> **Adjacent pattern — Service Locator:** A registry object you query for dependencies (`ServiceLocator.get(PaymentGateway.class)`). It achieves IoC but is widely considered inferior to DI because dependencies become invisible in the API (you can't tell what a class needs from its constructor), it couples every class to the locator itself, and it makes testing harder (you must populate a global). Spring supports a locator style (`applicationContext.getBean(...)`) but idiomatic Spring uses injection.

### 2.3 Hollywood Principle

IoC is colloquially summarized by the **Hollywood Principle**: *"Don't call us, we'll call you."* Your code doesn't drive the framework; the framework drives your code, calling into your beans and supplying what they need. This is the same inversion you see in event loops, GUI callbacks, and servlet containers.

### 2.4 What "inverted" actually refers to

The word *control* in IoC has been used for several things historically. Martin Fowler clarified that for *dependency wiring* specifically we should say **Dependency Injection**, reserving "IoC" for the broader idea. The control that is inverted is the **control over the acquisition of dependencies** (and, more broadly, over the program's flow — e.g., a template method framework calls your overrides).

### 2.5 A minimal hand-rolled container (to demystify)

Before Spring, build the idea yourself. A container is conceptually a `Map<Class<?>, Object>` plus wiring logic:

```java
// A toy IoC container — illustrative, NOT production code.
public class TinyContainer {
    private final Map<Class<?>, Object> singletons = new HashMap<>();

    // Register a ready-made instance.
    public <T> void register(Class<T> type, T instance) {
        singletons.put(type, instance);
    }

    // Resolve a type: if we have it, return it; otherwise build via constructor injection.
    @SuppressWarnings("unchecked")
    public <T> T resolve(Class<T> type) {
        if (singletons.containsKey(type)) return (T) singletons.get(type);
        try {
            // Pick the first constructor and recursively resolve each parameter.
            Constructor<?> ctor = type.getConstructors()[0];
            Object[] args = Arrays.stream(ctor.getParameterTypes())
                                  .map(this::resolve)   // recursion = building the graph
                                  .toArray();
            Object instance = ctor.newInstance(args);
            singletons.put(type, instance);             // cache as singleton
            return (T) instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot resolve " + type, e);
        }
    }
}
```

This 20-line toy already demonstrates the four jobs of a real container: **registration**, **resolution**, **recursive dependency graph construction**, and **singleton caching**. Spring is this idea, hardened with lifecycle callbacks, scopes, proxies, configuration sources, ordering, lazy init, circular-dependency handling, and thousands of edge cases.

> **Adjacent term — "reflection":** Java reflection is the API (`java.lang.reflect`) that lets code inspect and invoke classes, constructors, methods, and fields at runtime by name rather than at compile time. Spring uses reflection heavily to instantiate beans and set fields/call setters it discovered by scanning. Reflection is powerful but slower than direct calls and can bypass access control (e.g., set a `private` field).

---

## 3. How it works internally

This is the heart of the chapter. We trace the Spring container from "JVM starts" to "container shuts down."

### 3.1 The two container interfaces

> **`BeanFactory`** is the most basic Spring container interface. It provides the fundamental capability: configure and retrieve beans (`getBean(...)`), manage their basic lifecycle, and perform DI. It is *lazy* by default — beans are created on first request.

> **`ApplicationContext`** is a superset (it *extends* `BeanFactory`) and is the interface you almost always use. On top of bean management it adds: **eager singleton instantiation at startup**, **event publishing**, **internationalization (i18n) message resolution**, **resource loading** (classpath/file/URL abstraction), **automatic `BeanPostProcessor` and `BeanFactoryPostProcessor` registration**, and integration with environment/profiles.

Comparison:

| Capability | `BeanFactory` | `ApplicationContext` |
|---|---|---|
| Bean instantiation & DI | Yes | Yes |
| Default instantiation timing | Lazy (on first `getBean`) | Eager for singletons (at refresh) |
| Auto-detect `BeanPostProcessor`s | No (manual) | Yes |
| Auto-detect `BeanFactoryPostProcessor`s | No (manual) | Yes |
| Event publishing (`ApplicationEvent`) | No | Yes |
| `MessageSource` (i18n) | No | Yes |
| `ResourceLoader` / resource patterns | No | Yes |
| Environment & `@Profile` support | Limited | Yes |
| Typical use | Memory-constrained / embedded niche | Everything else |

In modern Spring (and Spring Boot) you essentially always use an `ApplicationContext`. Common concrete implementations:

| Implementation | Configuration source | Typical use |
|---|---|---|
| `AnnotationConfigApplicationContext` | `@Configuration` classes / scanned components | Standalone / non-web, modern Java config |
| `AnnotationConfigServletWebServerApplicationContext` | Java config + embedded servlet container | Spring Boot servlet web apps |
| `AnnotationConfigReactiveWebServerApplicationContext` | Java config + reactive server | Spring Boot WebFlux apps |
| `ClassPathXmlApplicationContext` | XML on the classpath | Legacy XML config |
| `FileSystemXmlApplicationContext` | XML on the filesystem | Legacy XML config |
| `GenericApplicationContext` | Programmatic registration | Tests, frameworks, manual wiring |

### 3.2 Bean definitions: the recipes

A **`BeanDefinition`** is the container's internal metadata object describing how to create one bean. It is *not* the bean itself — it is the recipe. Key fields:

- **Bean class** (or factory method) — what to instantiate.
- **Scope** — `singleton`, `prototype`, `request`, `session`, etc.
- **Constructor arguments / property values** — how to wire it.
- **Autowire mode**, **lazy-init flag**, **primary flag**, **depends-on**, **init/destroy method names**, **qualifiers**.

Every configuration mechanism (annotations, Java config, XML) is just a different *front end* that ultimately produces `BeanDefinition` objects registered in a **`BeanDefinitionRegistry`**. This is the unifying insight: XML, `@Component`, and `@Bean` all converge on `BeanDefinition`.

### 3.3 The `refresh()` lifecycle — the canonical sequence

When you create an `ApplicationContext`, the core method `AbstractApplicationContext.refresh()` runs. This is *the* most important internal flow in Spring. Its steps, in order (method names are real Spring internals):

1. **`prepareRefresh()`** — record start time, set active flag, initialize property sources, validate required properties.
2. **`obtainFreshBeanFactory()`** — create the internal `DefaultListableBeanFactory` and **load bean definitions** (parse XML, or process the registered `@Configuration` classes / scanned components). After this step, all recipes exist but **no beans are instantiated yet**.
3. **`prepareBeanFactory(beanFactory)`** — configure the factory: set the classloader, register the default `BeanExpressionResolver` (SpEL), register built-in beans like `environment`, `systemProperties`, and standard `BeanPostProcessor`s such as `ApplicationContextAwareProcessor`.
4. **`postProcessBeanFactory(beanFactory)`** — subclass hook (web contexts register web-specific scopes/beans here).
5. **`invokeBeanFactoryPostProcessors(beanFactory)`** — run all **`BeanFactoryPostProcessor`s** (BFPP). These can *modify bean definitions* before any bean is instantiated. The most important one is `ConfigurationClassPostProcessor`, which processes `@Configuration`, `@Bean`, `@ComponentScan`, `@Import`, etc., and registers the resulting definitions. `PropertySourcesPlaceholderConfigurer` (resolving `${...}`) also runs here. **A `BeanDefinitionRegistryPostProcessor` is a sub-interface** that can additionally *add new* bean definitions.
6. **`registerBeanPostProcessors(beanFactory)`** — instantiate and register all **`BeanPostProcessor`s** (BPP), which will intercept *every* bean's initialization later. Examples: `AutowiredAnnotationBeanPostProcessor` (handles `@Autowired`/`@Value`), `CommonAnnotationBeanPostProcessor` (`@Resource`, `@PostConstruct`, `@PreDestroy`), `AnnotationAwareAspectJAutoProxyCreator` (creates AOP proxies).
7. **`initMessageSource()`** — set up i18n message resolution.
8. **`initApplicationEventMulticaster()`** — set up the event publisher.
9. **`onRefresh()`** — subclass hook; in web contexts this **starts the embedded server** (Tomcat/Jetty/Netty) in Boot.
10. **`registerListeners()`** — register `ApplicationListener` beans with the multicaster; deliver early events.
11. **`finishBeanFactoryInitialization(beanFactory)`** — **instantiate all remaining non-lazy singleton beans.** This is where the bulk of your beans get created and wired (see §3.4).
12. **`finishRefresh()`** — call `LifecycleProcessor.onRefresh()` (start `SmartLifecycle` beans), publish `ContextRefreshedEvent`, register with JMX if applicable.

If anything throws during refresh, Spring **destroys already-created singletons** and propagates the exception — the context does not come up half-built.

### 3.4 Single bean creation: `getBean` → `createBean` → `doCreateBean`

For each singleton (and on demand for prototypes/lazy beans), the container runs this micro-lifecycle. Understanding it is what separates senior from junior Spring knowledge.

1. **`getBean(name)`** — entry point. Checks the **singleton cache** (`singletonObjects`). If present, returns it (this is why singletons are shared).
2. **Resolve dependencies first** — if the bean `depends-on` others, or its constructor needs collaborators, those are resolved (recursively `getBean`) before this bean is built.
3. **`createBean` → `doCreateBean`:**
   a. **Instantiation** — pick the constructor (via `SmartInstantiationAwareBeanPostProcessor` / `determineConstructorsFromBeanPostProcessors`), resolve constructor args, and `newInstance`. For field/setter injection, the no-arg or chosen constructor runs now.
   b. **Early singleton exposure** — a reference (or a factory for it) is added to `singletonFactories` to support circular references (see §7.3).
   c. **Populate properties (`populateBean`)** — perform **field and setter injection**. `AutowiredAnnotationBeanPostProcessor.postProcessProperties` injects `@Autowired`/`@Value`; `@Resource` is handled by `CommonAnnotationBeanPostProcessor`.
   d. **`initializeBean`:**
      - **Aware callbacks** — if the bean implements `BeanNameAware`, `BeanFactoryAware`, `ApplicationContextAware`, etc., the corresponding setters are called so the bean learns about its environment.
      - **`BeanPostProcessor.postProcessBeforeInitialization`** — for every BPP. This is where `@PostConstruct` is invoked (by `CommonAnnotationBeanPostProcessor`) and `@ConfigurationProperties` binding can occur.
      - **Init callbacks** — `InitializingBean.afterPropertiesSet()`, then the custom `init-method`.
      - **`BeanPostProcessor.postProcessAfterInitialization`** — for every BPP. **This is where AOP proxies are created** (the returned object may be a proxy wrapping your bean — crucial for `@Transactional`, `@Async`, `@Cacheable`).
   e. **Register destruction callbacks** — if the bean has `@PreDestroy`, implements `DisposableBean`, or declares a `destroy-method`, it is registered so shutdown can clean it up.
4. **Store in singleton cache** and return.

> **Adjacent term — "proxy" / AOP:** *AOP* (Aspect-Oriented Programming) lets you add cross-cutting behavior (transactions, logging, security, caching) without editing the target code. Spring implements it by wrapping your bean in a **proxy** — a stand-in object with the same interface (JDK dynamic proxy) or a generated subclass (CGLIB). Calls go to the proxy, which runs the aspect logic, then delegates to your real object. The practical consequence: the object injected into others may *not* be your raw bean but a proxy, which is why `this.someAnnotatedMethod()` self-invocations bypass the proxy and the aspect doesn't fire.

### 3.5 Bean scopes (state lifecycle)

> **Adjacent term — "scope":** A bean's *scope* defines how many instances exist and how long each lives.

| Scope | Instances | Lifetime | Notes |
|---|---|---|---|
| `singleton` (default) | One per container | Whole container lifetime | Eagerly created (unless lazy); **must be stateless or thread-safe**. |
| `prototype` | New instance per request | Caller-managed | Container does **not** call destroy callbacks for prototypes. |
| `request` | One per HTTP request | Request duration | Web only. |
| `session` | One per HTTP session | Session duration | Web only. |
| `application` | One per `ServletContext` | App duration | Web only. |
| `websocket` | One per WebSocket session | Session | Web only. |
| Custom | Defined by you | Defined by you | Implement `org.springframework.beans.factory.config.Scope`. |

The default singleton scope means a single shared instance — so the *single most common Spring bug* is putting mutable per-request state in a singleton field.

### 3.6 Where injection happens, precisely

- **Constructor injection** — happens in step 3.a (during instantiation). The object is *fully initialized* the moment it exists. Enables `final` fields.
- **Setter & field injection** — happen in step 3.c (`populateBean`), *after* the object already exists via its constructor. Fields cannot be `final`. The object briefly exists in a partially-wired state.

This timing difference is the technical root of why constructor injection is preferred (see §3.7 and §7).

### 3.7 Autowiring resolution algorithm (how Spring picks a bean)

When Spring must inject a dependency of type `T`, it:

1. Finds all bean definitions assignable to `T`.
2. If **exactly one** → inject it.
3. If **none** and the dependency is required → throw `NoSuchBeanDefinitionException`.
4. If **more than one**, disambiguate in this order:
   a. A candidate marked **`@Primary`** wins.
   b. Otherwise, a **`@Qualifier("name")`** on the injection point narrows to a matching candidate.
   c. Otherwise, **the field/parameter name** is matched against bean names (fallback by name).
   d. Otherwise → throw `NoUniqueBeanDefinitionException`.
5. For collections (`List<T>`, `Map<String, T>`), Spring injects **all** matching beans (ordered by `@Order`/`Ordered` for lists; keyed by bean name for maps).

---

## 4. The complete toolkit

### 4.1 Stereotype & configuration annotations

| Annotation | Package | Purpose | Notes / key behavior |
|---|---|---|---|
| `@Component` | `org.springframework.stereotype` | Generic managed bean marker | Base for the others; picked up by component scanning. |
| `@Service` | same | Semantic marker for service layer | Functionally identical to `@Component`; documents intent. |
| `@Repository` | same | Persistence layer marker | Adds **exception translation** (`PersistenceExceptionTranslationPostProcessor` converts vendor exceptions to Spring's `DataAccessException`). |
| `@Controller` | same | Spring MVC web controller | Enables handler-method mapping. |
| `@RestController` | `org.springframework.web.bind.annotation` | `@Controller` + `@ResponseBody` | REST endpoints. |
| `@Configuration` | `org.springframework.context.annotation` | Class declaring `@Bean` methods | **CGLIB-enhanced by default** so inter-`@Bean` calls return the singleton (`proxyBeanMethods=true`). |
| `@Bean` | same | Factory method producing a bean | Used inside `@Configuration` (or `@Component`). Method name = bean name by default. |
| `@ComponentScan` | same | Define packages to scan for components | `basePackages`, `basePackageClasses`, include/exclude filters. |
| `@Import` | same | Import other config classes / `ImportSelector` / registrar | Composition of configuration. |
| `@ImportResource` | same | Import legacy XML into Java config | Bridge old + new. |
| `@Lazy` | same | Defer instantiation until first use | On a bean, or on an injection point (lazy proxy). |
| `@Primary` | same | Prefer this candidate when ambiguous | Disambiguation. |
| `@Profile` | same | Activate bean(s) only for given profiles | Environment-driven wiring. |
| `@Scope` | same | Set bean scope | `value` + `proxyMode` for scoped proxies. |
| `@DependsOn` | same | Force initialization order | Names of beans to create first. |

### 4.2 Injection & value annotations

| Annotation | Source | Applies to | Behavior |
|---|---|---|---|
| `@Autowired` | Spring | constructor, setter, field, method | By-type injection; `required=false` for optional. |
| `@Qualifier` | Spring | injection point / bean | Narrow ambiguous candidates by name/qualifier. |
| `@Value` | Spring | field, param | Inject literals, `${properties}`, `#{SpEL}`. |
| `@Resource` | Jakarta (`jakarta.annotation`) | field, setter | By-**name** first (then type); JSR-250 standard. |
| `@Inject` | JSR-330 (`jakarta.inject`) | ctor, setter, field | Standard equivalent of `@Autowired` (by type). |
| `@Named` | JSR-330 | injection point / bean | Standard equivalent of `@Qualifier`/`@Component(name)`. |
| `@Lookup` | Spring | method | Method injection — return a fresh prototype on each call. |

> **Note on `@Autowired` and constructors:** Since Spring 4.3, if a class has **exactly one constructor**, `@Autowired` on it is **optional** — Spring uses it automatically. This is why modern Spring constructor injection often has no annotation at all.

> **Adjacent term — JSR-330 / JSR-250:** *JSRs* (Java Specification Requests) are standards. JSR-330 (`jakarta.inject`: `@Inject`, `@Named`, `@Provider`, `@Qualifier`, `@Singleton`) is the vendor-neutral DI standard Spring supports so your code isn't Spring-locked. JSR-250 (`jakarta.annotation`: `@Resource`, `@PostConstruct`, `@PreDestroy`) covers common lifecycle/resource annotations. (In Java EE → Jakarta EE the package prefix moved from `javax.` to `jakarta.`; Spring 6 / Boot 3 use `jakarta.*`.)

### 4.3 Lifecycle callbacks & aware interfaces

| Mechanism | Phase | Notes |
|---|---|---|
| `@PostConstruct` (JSR-250) | After DI, before bean in service | Preferred for init logic; no Spring coupling. |
| `InitializingBean.afterPropertiesSet()` | After DI | Couples to Spring; prefer `@PostConstruct`. |
| `@Bean(initMethod="...")` / XML `init-method` | After DI | Config-driven init. |
| `@PreDestroy` (JSR-250) | On singleton shutdown | Preferred for cleanup. |
| `DisposableBean.destroy()` | On shutdown | Couples to Spring. |
| `@Bean(destroyMethod="...")` | On shutdown | Default auto-detects `close`/`shutdown`. |
| `*Aware` interfaces | During init | `BeanNameAware`, `BeanFactoryAware`, `ApplicationContextAware`, `EnvironmentAware`, etc. Inject framework objects. |
| `SmartLifecycle` | Context start/stop | Ordered start/stop, for things like message listeners/servers. |

**Init order (when multiple are present):** `@PostConstruct` → `InitializingBean.afterPropertiesSet()` → custom `init-method`.
**Destroy order:** `@PreDestroy` → `DisposableBean.destroy()` → custom `destroy-method`.

### 4.4 Extension points (the container's plugin API)

| Type | When it runs | What it can do |
|---|---|---|
| `BeanFactoryPostProcessor` (BFPP) | After definitions loaded, before instantiation | Modify **bean definitions** (e.g., resolve `${}` placeholders). |
| `BeanDefinitionRegistryPostProcessor` | Before BFPPs | **Add/remove** bean definitions (e.g., `ConfigurationClassPostProcessor`). |
| `BeanPostProcessor` (BPP) | Around each bean's init | Wrap/modify bean **instances** (e.g., create AOP proxies). |
| `SmartInstantiationAwareBeanPostProcessor` | Around instantiation | Influence constructor selection, early references. |
| `ApplicationListener` / `@EventListener` | On events | React to context/business events. |
| `FactoryBean<T>` | On `getBean` | A bean that itself produces another bean (`getObject()`). |

> **Adjacent term — `FactoryBean`:** A `FactoryBean<T>` is a bean whose job is to *manufacture* another object. When you `getBean("foo")` and `foo` is a `FactoryBean`, you receive the *product* (`getObject()`), not the factory. To get the factory itself, prefix with `&` (`getBean("&foo")`). Used heavily by Spring for complex objects (e.g., creating `SqlSessionFactory`, proxies).

### 4.5 Programmatic API (the `ApplicationContext` itself)

| Method | Purpose |
|---|---|
| `getBean(Class<T>)` / `getBean(String)` / `getBean(String, Class<T>)` | Retrieve a bean. |
| `getBeansOfType(Class<T>)` | Map of all beans of a type. |
| `getBeanNamesForType(...)` | Names without instantiating (where possible). |
| `containsBean(name)` | Existence check. |
| `isSingleton(name)` / `isPrototype(name)` | Scope check. |
| `getEnvironment()` | Access profiles & property sources. |
| `publishEvent(Object)` | Fire an application event. |
| `getBeanProvider(Class<T>)` | Lazy, optional, streaming access (`ObjectProvider`). |
| `close()` / `registerShutdownHook()` | Trigger orderly shutdown. |

---

## 5. Code examples by use case

### 5.1 Constructor injection (the idiomatic default)

```java
// PaymentGateway is the abstraction; OrderService depends on it, not on Stripe.
public interface PaymentGateway {
    PaymentResult charge(String customerId, Money amount);
}

@Service
public class OrderService {
    private final PaymentGateway gateway;
    private final InventoryClient inventory;

    // Single constructor → @Autowired is optional since Spring 4.3.
    // final fields = immutable, guaranteed-set dependencies.
    public OrderService(PaymentGateway gateway, InventoryClient inventory) {
        this.gateway = gateway;
        this.inventory = inventory;
    }

    public OrderResult placeOrder(Order order) {
        inventory.reserve(order.items());
        PaymentResult pr = gateway.charge(order.customerId(), order.total());
        return new OrderResult(order.id(), pr.status());
    }
}
```

Why this is the gold standard: dependencies are explicit in the signature, fields are `final` (immutable, thread-safe publication), the object is impossible to construct in an invalid state, and unit testing needs no Spring at all:

```java
@Test
void chargesOnPlaceOrder() {
    PaymentGateway gw = mock(PaymentGateway.class);
    InventoryClient inv = mock(InventoryClient.class);
    when(gw.charge(any(), any())).thenReturn(PaymentResult.ok());

    OrderService svc = new OrderService(gw, inv);   // plain Java, no container
    OrderResult r = svc.placeOrder(sampleOrder());

    verify(inv).reserve(any());
    assertThat(r.status()).isEqualTo(Status.PAID);
}
```

### 5.2 Java configuration with `@Configuration` / `@Bean`

Use when you wire third-party classes you can't annotate, or need explicit construction logic.

```java
@Configuration
public class PaymentConfig {

    // A bean whose construction needs config — can't @Component a Stripe SDK class.
    @Bean
    public PaymentGateway paymentGateway(
            @Value("${stripe.api-key}") String apiKey,
            @Value("${stripe.timeout-ms:2000}") int timeoutMs) {   // 2000 = default if unset
        return new StripePaymentGateway(apiKey, Duration.ofMillis(timeoutMs));
    }

    // Inter-bean reference: calling paymentGateway() returns the SAME singleton,
    // because @Configuration is CGLIB-proxied (proxyBeanMethods=true by default).
    @Bean
    public AuditedPaymentGateway auditedGateway(MeterRegistry metrics) {
        return new AuditedPaymentGateway(paymentGateway(/* args injected by container */ null, 0), metrics);
    }
}
```

> **Pitfall flagged:** the snippet above shows the *concept* of inter-bean calls, but passing `null` is wrong — in real code prefer **method parameters** so Spring injects properly:
```java
@Bean
public AuditedPaymentGateway auditedGateway(PaymentGateway delegate, MeterRegistry metrics) {
    return new AuditedPaymentGateway(delegate, metrics);   // delegate injected by type
}
```

### 5.3 Disambiguating multiple implementations (`@Primary` + `@Qualifier`)

```java
public interface NotificationSender { void send(String to, String msg); }

@Service
@Primary                              // chosen by default when type is ambiguous
class EmailNotificationSender implements NotificationSender { /* ... */ }

@Service
@Qualifier("sms")                     // selectable explicitly
class SmsNotificationSender implements NotificationSender { /* ... */ }

@Service
class AlertService {
    private final NotificationSender defaultSender;   // gets Email (because @Primary)
    private final NotificationSender urgentSender;

    AlertService(NotificationSender defaultSender,
                 @Qualifier("sms") NotificationSender urgentSender) {  // explicit pick
        this.defaultSender = defaultSender;
        this.urgentSender = urgentSender;
    }
}
```

Injecting **all** implementations:

```java
@Service
class BroadcastService {
    private final List<NotificationSender> all;        // every NotificationSender bean
    private final Map<String, NotificationSender> byName; // keyed by bean name

    BroadcastService(List<NotificationSender> all, Map<String, NotificationSender> byName) {
        this.all = all;
        this.byName = byName;
    }
    void broadcast(String msg) { all.forEach(s -> s.send("everyone", msg)); }
}
```

### 5.4 Profiles & environment-driven wiring

```java
@Configuration
public class GatewayConfig {

    @Bean
    @Profile("prod")                         // only when 'prod' profile active
    PaymentGateway realGateway(@Value("${stripe.api-key}") String key) {
        return new StripePaymentGateway(key, Duration.ofSeconds(2));
    }

    @Bean
    @Profile({"dev", "test"})                // dev/test get a fake
    PaymentGateway fakeGateway() {
        return new InMemoryPaymentGateway();
    }
}
```

Activate via `spring.profiles.active=prod` (property/env var) or `SPRING_PROFILES_ACTIVE=prod`.

### 5.5 Lifecycle callbacks (managing a resource)

```java
@Component
public class ConnectionPool {

    private HikariDataSource ds;

    @PostConstruct                 // runs after DI, before the bean serves traffic
    void open() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getProperty("db.url"));
        this.ds = new HikariDataSource(cfg);
    }

    @PreDestroy                    // runs on orderly container shutdown
    void close() {
        if (ds != null) ds.close(); // release sockets/threads — avoid resource leak
    }
}
```

### 5.6 Prototype-in-singleton with `@Lookup` (method injection)

A common trap: a singleton needs a *fresh* prototype each call. Plain field injection gives you the *same* prototype forever (it's injected once). Solution — method injection:

```java
@Component
@Scope("prototype")
class ReportJob { /* stateful, per-invocation */ }

@Service
abstract class ReportService {

    public void run() {
        ReportJob job = newJob();   // a NEW ReportJob every call
        job.execute();
    }

    @Lookup                         // Spring overrides this method to return a fresh prototype
    protected abstract ReportJob newJob();
}
```

Alternative without abstract methods — inject an `ObjectProvider<ReportJob>` and call `.getObject()` each time.

### 5.7 Bootstrapping a standalone context programmatically

```java
public class App {
    public static void main(String[] args) {
        // AnnotationConfigApplicationContext: modern, annotation/Java-config based.
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {
            OrderService svc = ctx.getBean(OrderService.class);
            svc.placeOrder(/* ... */);
        } // try-with-resources → ctx.close() → @PreDestroy callbacks fire
    }
}

@Configuration
@ComponentScan("com.example.app")   // scan this package tree for @Component/@Service/etc.
class AppConfig {}
```

### 5.8 Optional & deferred dependencies (`ObjectProvider`)

```java
@Service
class FeatureService {
    private final ObjectProvider<ExperimentalEngine> engineProvider;

    FeatureService(ObjectProvider<ExperimentalEngine> engineProvider) {
        this.engineProvider = engineProvider;   // lazy: not resolved at construction
    }

    void maybeRun() {
        // getIfAvailable: returns null (or runs the supplier) if no such bean exists
        ExperimentalEngine engine = engineProvider.getIfAvailable();
        if (engine != null) engine.run();
    }
}
```

`ObjectProvider` cleanly handles **zero, one, or many** candidates and **lazy** resolution without `@Autowired(required=false)` null fields.

---

## 6. Implementation concerns & best practices

### 6.1 Correctness & concurrency

- **Singletons must be stateless or thread-safe.** The default scope shares one instance across all threads. Per-request mutable fields are a race-condition factory. Keep request state in method parameters/locals, or use `request` scope.
- **Don't store the `ApplicationContext` and call `getBean` at runtime** (Service Locator anti-pattern) — it hides dependencies and defeats testability. Inject what you need.
- **Beware proxy self-invocation.** `@Transactional`/`@Async`/`@Cacheable` work via proxies. Calling `this.annotatedMethod()` from within the same bean bypasses the proxy, so the aspect silently does nothing. Refactor to call through another bean or use self-injection.

### 6.2 Performance

- **Startup cost** scales with bean count and eager singleton init. Large apps see this in cold-start (notably serverless). Mitigations: `@Lazy` for rarely-used heavy beans, Spring Boot's lazy-init flag (`spring.main.lazy-initialization=true`), and **AOT/GraalVM native images** (Spring 6) which precompute much of the wiring.
- **Component scanning** is reflective and classpath-wide; narrow `basePackages` to reduce scan time. In native/AOT builds, scanning is replaced by generated code.
- Per-bean DI happens **once** for singletons — runtime method calls are normal Java speed (minus any proxy hop). Proxies add a small per-call indirection.

### 6.3 Memory

- Singletons live for the whole context lifetime → don't accidentally retain large per-request objects in singleton fields (a slow memory leak). Prototypes are **not** tracked for destruction, so resources they hold must be closed by the caller.

### 6.4 Security

- `@Value`/property placeholders can pull secrets from the environment — keep secrets out of source and logs; integrate with a secrets manager via a custom `PropertySource`.
- SpEL in `@Value`/config is powerful and can execute code — never evaluate SpEL from untrusted input.

### 6.5 Observability

- Enable bean-definition and condition reporting (Boot: `--debug` prints the auto-configuration report; the Actuator `beans` endpoint lists all beans, scopes, and dependencies).
- Log the active profiles and resolved properties at startup for incident forensics.

### 6.6 Testability

- Prefer constructor injection so tests construct beans with plain `new` + mocks — **no Spring context required** for unit tests (fast).
- Use `@SpringBootTest` / `@ContextConfiguration` only for integration tests; use slice tests (`@WebMvcTest`, `@DataJpaTest`) to load minimal contexts.
- `@MockBean`/`@MockitoBean` replaces a bean in the context for integration tests.

### 6.7 Production hardening

- Always allow **orderly shutdown** (`registerShutdownHook()` is automatic in Boot) so `@PreDestroy` runs (close pools, flush buffers).
- Validate required config at startup (`@Validated @ConfigurationProperties`) so misconfiguration fails fast, not at first request.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| **Field injection** (`@Autowired` on a field) | Hides dependencies, can't be `final`, requires reflection to test, enables god classes | Constructor injection |
| Circular dependencies | Fragile, indicates design smell; field-injection circularity works only by accident | Refactor; extract a third collaborator |
| `getBean` calls everywhere | Service Locator; invisible coupling | Inject dependencies |
| Mutable singleton state | Race conditions | Stateless beans / request scope |
| Overusing `@Lazy` to fix startup errors | Hides ordering/circular bugs | Fix the root cause |
| Business logic in `@Configuration` | Hard to test, blurs layers | Keep config thin; logic in `@Service` |
| Self-invocation of `@Transactional` | Aspect doesn't fire | Call through another bean / self-inject |

---

## 7. Advanced topics & deep internals

### 7.1 `@Configuration` `proxyBeanMethods` — full vs. lite mode

By default `@Configuration(proxyBeanMethods=true)`: Spring CGLIB-subclasses the config class so that calls between `@Bean` methods return the shared singleton. Set `proxyBeanMethods=false` ("lite" mode) when `@Bean` methods don't call each other — it skips the CGLIB enhancement, speeding startup and aiding native images. A `@Bean` method on a `@Component` (not `@Configuration`) is *always* in lite mode (no inter-bean proxying).

### 7.2 Constructor selection nuances

- One constructor → used automatically.
- Multiple constructors → you must annotate the intended one with `@Autowired`, or Spring tries to satisfy the "greediest" satisfiable one in some cases; ambiguity throws.
- `@Autowired(required=false)` on a constructor lets Spring fall back to a default constructor if dependencies are missing.

### 7.3 Circular dependencies — how Spring sometimes resolves them

> **The mechanism (three-level cache):** Spring's `DefaultSingletonBeanFactory` keeps three maps:
> 1. `singletonObjects` — fully initialized singletons.
> 2. `earlySingletonObjects` — raw, not-yet-fully-initialized instances exposed early.
> 3. `singletonFactories` — `ObjectFactory`s that can produce an early reference (possibly a proxy).
>
> When bean A (being created) needs B, and B needs A, Spring exposes A's *early reference* from `singletonFactories` so B can wire it before A finishes. This works **only for setter/field injection of singletons**, because the object must already exist (constructed) before its reference can be exposed early.

**Constructor-injected circular dependencies cannot be resolved this way** — neither object can be constructed without the other — and Spring throws `BeanCurrentlyInCreationException`. Since Spring Boot 2.6, circular references are **disabled by default**; you must set `spring.main.allow-circular-references=true` to even attempt them. **The correct response is to redesign**, not to enable the flag. (Constructor injection thus *surfaces* circular-dependency design smells at startup — another point in its favor.)

### 7.4 Lazy injection to break cycles (last resort)

`@Lazy` on one side of a cycle injects a **proxy** that resolves the real bean on first use, deferring the cycle past construction. It works but masks the design problem; prefer extracting a shared third component.

### 7.5 Scoped proxies (injecting short-lived beans into long-lived ones)

To inject a `request`/`session`-scoped bean into a singleton, you need a **scoped proxy** (`@Scope(value="request", proxyMode=ScopedProxyMode.TARGET_CLASS)`). The singleton holds a proxy; each method call is routed to the correct per-request instance via thread-bound context.

### 7.6 Bean ordering

`@Order(n)` / `Ordered` interface controls the order of beans in injected `List<T>`, of `BeanPostProcessor`s, of filters, and of `ApplicationListener`s. `@DependsOn` forces *instantiation* order (not injection order) — useful when an implicit dependency isn't expressed through injection (e.g., a bean that must initialize a static registry first).

### 7.7 Conditional beans

Spring Boot's `@Conditional*` family (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`, etc.) registers beans only when conditions hold — the backbone of **auto-configuration**. The generic `@Conditional(MyCondition.class)` lets you write arbitrary `Condition` logic evaluated during definition processing.

### 7.8 `@Configuration` vs `@Component` `@Bean` semantics — the subtle bug

Putting `@Bean` methods on a `@Component` and calling them between each other does **not** return singletons (lite mode), so you can accidentally create duplicate instances. Use `@Configuration` when inter-bean references matter.

### 7.9 Generics-aware injection

Spring resolves generic types: injecting `Converter<String, Integer>` finds the matching parameterized bean even if multiple `Converter` beans exist. This relies on `ResolvableType` introspection.

### 7.10 AOT & native images (Spring 6 / Boot 3)

Ahead-of-Time processing generates bean-registration code at build time, eliminating most reflection and runtime scanning. This enables GraalVM native images with millisecond startup but imposes constraints (closed-world: dynamic bean registration and heavy reflection need hints). Flag this as version-specific to Spring 6+.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Injection types compared

| Aspect | Constructor | Setter | Field |
|---|---|---|---|
| Immutability (`final`) | Yes | No | No |
| Mandatory deps enforced | Yes (can't construct without) | No | No |
| Optional/reconfigurable deps | Awkward | Good | N/A |
| Visible in public API | Yes | Yes | No (hidden) |
| Testable without container | Yes (plain `new`) | Yes | Requires reflection/`@InjectMocks` |
| Circular deps | Fails fast (good) | Can resolve (risky) | Can resolve (risky) |
| Verbosity | Higher (boilerplate; mitigate with Lombok `@RequiredArgsConstructor`) | Medium | Lowest |
| **Recommendation** | **Default — use this** | Optional/mutable deps only | Avoid (except quick tests) |

**Rule:** Use **constructor injection** for mandatory dependencies. Use **setter injection** only for genuinely optional or reconfigurable ones. Avoid **field injection** in production code.

### 8.2 Configuration style compared

| Style | Pros | Cons | Use when |
|---|---|---|---|
| Annotations (`@Component` + scan) | Concise, colocated, refactor-friendly | Magic; can't annotate third-party classes | Your own classes |
| Java config (`@Configuration`/`@Bean`) | Type-safe, explicit, full IDE support, can wire third-party | More verbose | Third-party beans, conditional/complex wiring |
| XML | Externalized, no recompile to rewire | Verbose, no type safety, falling out of use | Legacy maintenance only |

Modern default: **annotations for your code + Java config for everything else.** XML is legacy.

### 8.3 `BeanFactory` vs `ApplicationContext`

Use `ApplicationContext` essentially always. Reach for raw `BeanFactory` only in extremely memory-constrained/embedded scenarios where eager init and the extra features are unacceptable — rare in practice.

### 8.4 DI vs Service Locator vs manual wiring

| Approach | Coupling | Testability | Visibility | Use when |
|---|---|---|---|---|
| DI (container) | Low | High | High | Most apps |
| Service Locator | Medium | Lower | Low | Legacy / plugin systems |
| Manual `new` wiring | High to one place, low elsewhere | High | High | Tiny apps, libraries avoiding a container |

### 8.5 Spring DI vs alternatives (Guice, Dagger, CDI)

| Framework | Style | Wiring time | Notes |
|---|---|---|---|
| Spring | Reflection + scanning (+ AOT) | Runtime (or build w/ AOT) | Richest ecosystem, AOP, Boot. |
| Google Guice | Code (modules) | Runtime, reflection | Lightweight, explicit modules. |
| Dagger 2 | Annotation processing | **Compile time** | Zero reflection, fast startup (Android). |
| CDI (Jakarta) | Standard annotations | Runtime | Java/Jakarta EE standard. |

---

## 9. Failure modes & debugging

### 9.1 `NoSuchBeanDefinitionException`
**Cause:** no bean of the requested type/name exists. **Diagnose:** check component scanning covers the package; check the bean isn't gated behind an inactive `@Profile` or failing `@Conditional`; check the import. **Tool:** Actuator `/actuator/beans`, Boot `--debug` condition report.

### 9.2 `NoUniqueBeanDefinitionException`
**Cause:** multiple candidates, no `@Primary`/`@Qualifier`. **Fix:** mark one `@Primary` or qualify the injection point. The exception message lists all candidate bean names — read it.

### 9.3 `BeanCurrentlyInCreationException` / circular reference error
**Cause:** constructor-injection cycle (or cycle with circular refs disabled). **Diagnose:** Spring prints the cycle chain (`A → B → A`). **Fix:** redesign — extract a third bean, or move one dependency to setter/`@Lazy` as a stopgap.

### 9.4 `BeanCreationException` wrapping a root cause
**Cause:** something failed in `@PostConstruct`/constructor/`afterPropertiesSet`. **Diagnose:** read the `Caused by:` chain; the real error (e.g., bad DB URL) is nested. The bean name and injection point are in the message.

### 9.5 `@Transactional`/`@Async` silently not working
**Cause:** self-invocation (proxy bypass), method not `public`, or proxying disabled. **Diagnose:** check whether the call comes from outside the bean; check method visibility; verify `@EnableTransactionManagement`/`@EnableAsync` present. **Fix:** call through another bean, make method public, or use AspectJ load-time weaving.

### 9.6 Wrong implementation injected
**Cause:** unexpected `@Primary`, name-based fallback matching, or profile differences between environments. **Diagnose:** log the injected bean's class at startup; inspect `/actuator/beans`.

### 9.7 Slow / hanging startup
**Cause:** eager init of heavy beans (DB pools, remote clients), large classpath scan, deadlocked `@PostConstruct`. **Diagnose:** thread dump during startup; Boot startup tracing (`ApplicationStartup` / `BufferingApplicationStartup` + Actuator `/actuator/startup`). **Fix:** lazy init heavy beans, narrow scan packages.

### 9.8 Prototype not "fresh"
**Cause:** prototype injected once into a singleton field. **Fix:** `@Lookup`, `ObjectProvider`, or scoped proxy.

### 9.9 Real-world incident pattern
A classic production incident: a `@Service` singleton kept a mutable `SimpleDateFormat`/per-request `StringBuilder` in a field; under load, concurrent requests corrupted each other's data (intermittent garbled output, `ArrayIndexOutOfBoundsException` deep in `SimpleDateFormat`). Root cause: stateful singleton. Fix: make the formatter a local variable (or use thread-safe `DateTimeFormatter`). The lesson: **default scope is singleton; treat singletons as stateless.**

**Debugging toolkit summary:** Actuator endpoints `/actuator/beans`, `/actuator/conditions`, `/actuator/env`, `/actuator/startup`; Boot `--debug` auto-config report; enable `org.springframework` debug logging; thread dumps (`jstack`) for startup hangs; read the full `Caused by:` chain.

---

## 10. Interview drill

**Q1. Explain IoC vs DI.**
*Model answer:* IoC is the principle of inverting control over flow/dependency acquisition to a framework. DI is a specific pattern implementing IoC for object wiring: collaborators are supplied from outside rather than created/looked-up internally. Spring's container is a concrete IoC container performing DI.
- *Probe — Is Service Locator IoC?* Yes, it inverts who provides the dependency, but it's inferior to DI: dependencies are hidden and testing is harder.
- *Probe — What control is "inverted"?* Control over dependency acquisition and program flow (Hollywood Principle).
- *Probe — Does DI require a framework?* No — you can hand-wire with `new`. The framework just automates it at scale.

**Q2. Why is constructor injection preferred over field injection?**
*Model answer:* It enables `final` (immutable, thread-safe) fields, guarantees the object is never in a half-wired state, makes dependencies explicit in the public API, allows unit testing with plain `new` (no container/reflection), and causes circular dependencies to fail fast at startup rather than hide.
- *Probe — Downside of constructor injection?* Many constructor params signals too many dependencies (SRP violation) — a useful design smell. Verbosity, mitigated by Lombok.
- *Probe — When is setter injection legitimate?* Genuinely optional or reconfigurable dependencies.
- *Probe — How does field injection hurt tests?* You must use reflection or `@InjectMocks` instead of a plain constructor call.

**Q3. `BeanFactory` vs `ApplicationContext`?**
*Model answer:* `ApplicationContext` extends `BeanFactory`, adding eager singleton init, events, i18n, resource loading, automatic post-processor registration, and profiles. Use `ApplicationContext` always except rare memory-constrained cases.
- *Probe — Default instantiation timing?* `BeanFactory` lazy; `ApplicationContext` eager for singletons.
- *Probe — Who registers `BeanPostProcessor`s automatically?* `ApplicationContext`.

**Q4. Walk through a bean's lifecycle.**
*Model answer:* Definition loaded → instantiate (constructor injection) → populate (field/setter injection) → aware callbacks → `BeanPostProcessor.before` (`@PostConstruct`) → `InitializingBean`/`init-method` → `BeanPostProcessor.after` (AOP proxy created) → in service → on shutdown `@PreDestroy`/`DisposableBean`/`destroy-method`.
- *Probe — Where are proxies created?* In `postProcessAfterInitialization`.
- *Probe — Order of init callbacks?* `@PostConstruct` → `afterPropertiesSet` → custom init.

**Q5. How does Spring pick among multiple candidate beans?**
*Model answer:* Single match wins; else `@Primary`; else `@Qualifier`; else match by field/param name; else `NoUniqueBeanDefinitionException`. Collections inject all matches.
- *Probe — `@Primary` vs `@Qualifier`?* `@Primary` sets a default at the bean; `@Qualifier` picks explicitly at the injection point and overrides.
- *Probe — Inject all implementations?* `List<T>` or `Map<String,T>`.

**Q6. How does Spring handle circular dependencies?**
*Model answer:* Via a three-level singleton cache exposing early references — works for setter/field-injected singletons. Constructor-injected cycles can't be resolved and throw `BeanCurrentlyInCreationException`. Disabled by default since Boot 2.6; the real fix is redesign.
- *Probe — Why can't constructor cycles resolve?* Neither object can be constructed without the other.
- *Probe — Quick stopgap?* `@Lazy` on one injection point (injects a proxy).

**Q7. (Senior signal) When would you NOT use a DI container?**
*Model answer:* Tiny scripts/libraries where the container's startup/learning cost outweighs benefits; ultra-low-startup contexts (use Dagger's compile-time DI or hand-wiring); libraries that shouldn't impose Spring on consumers. DI the principle still applies (pass collaborators), just without a runtime container.

**Q8. (Senior signal) Justify annotations vs Java config vs XML for a new service.**
*Model answer:* Annotations for first-party classes (concise, refactor-safe); Java config for third-party/conditional/complex wiring (type-safe, explicit); XML only for legacy. Trade conciseness vs explicitness vs externalization. I'd default to annotations + Java config and reserve XML for maintaining old systems.

**Q9. (Senior signal) A `@Transactional` method isn't committing. Diagnose.**
*Model answer:* Most likely self-invocation bypassing the proxy, a non-public method, missing `@EnableTransactionManagement`, the wrong `PlatformTransactionManager`, or a swallowed exception preventing rollback semantics. I'd verify the call path, method visibility, and config, and consider AspectJ weaving if self-invocation is unavoidable.
- *Probe — Why does self-invocation break it?* The aspect lives on the proxy; `this.method()` calls the raw target, skipping the proxy.
- *Probe — Fix without restructuring?* Self-inject the bean or use `AopContext.currentProxy()`.

**Q10. What does `@Configuration` do that a plain `@Component` with `@Bean` methods doesn't?**
*Model answer:* `@Configuration` is CGLIB-enhanced (`proxyBeanMethods=true`) so inter-`@Bean` method calls return the shared singleton; on a `@Component` (lite mode) such calls create new instances. Use `@Configuration` when `@Bean` methods reference each other.
- *Probe — When to set `proxyBeanMethods=false`?* When `@Bean` methods don't call each other — faster startup, native-image friendly.

**Q11. (Senior signal) You see slow startup in a large app. How do you investigate and fix?**
*Model answer:* Capture startup timing via `BufferingApplicationStartup` + `/actuator/startup`, take thread dumps during startup, identify heavy eager beans (pools, remote clients). Fixes: lazy-init selected beans, narrow component-scan packages, consider AOT/native for cold-start-sensitive deployments, parallelize independent eager init where safe.

**Q12. What's the danger of mutable state in a default-scoped bean?**
*Model answer:* Singletons are shared across all threads; mutable fields cause race conditions and data corruption under concurrency. Keep beans stateless, put per-request state in locals/params, or use `request` scope.

---

## 11. Glossary

- **AOP (Aspect-Oriented Programming):** Adding cross-cutting behavior (transactions, logging) without modifying target code, via proxies/weaving.
- **ApplicationContext:** The standard Spring IoC container; a `BeanFactory` plus events, i18n, resources, profiles, eager init.
- **Autowiring:** Spring automatically supplying a dependency by resolving a matching bean.
- **Bean:** Any object managed by the Spring container.
- **BeanDefinition:** Internal metadata describing how to create a bean (the recipe).
- **BeanFactory:** The most basic Spring container interface; lazy bean management.
- **BeanPostProcessor (BPP):** Extension point that intercepts each bean's initialization (e.g., creates proxies).
- **BeanFactoryPostProcessor (BFPP):** Extension point that modifies bean definitions before instantiation.
- **CGLIB:** A bytecode library Spring uses to generate proxy subclasses (for classes without interfaces, and to enhance `@Configuration`).
- **Component scanning:** Automatically discovering `@Component`-annotated classes on the classpath.
- **Constructor injection:** Supplying dependencies through the constructor; the preferred form.
- **Coupling:** Degree of dependence between modules; DI reduces it.
- **DI (Dependency Injection):** Supplying an object's collaborators from outside.
- **DIP (Dependency Inversion Principle):** Depend on abstractions, not concretions.
- **Dependency:** A collaborator object that another object needs to do its job.
- **FactoryBean:** A bean that manufactures another bean via `getObject()`.
- **Field injection:** Setting dependencies directly into fields via reflection; discouraged.
- **Hollywood Principle:** "Don't call us, we'll call you" — the framework invokes your code.
- **IoC (Inversion of Control):** The principle of handing control of flow/wiring to a framework.
- **JSR-250:** Standard lifecycle/resource annotations (`@Resource`, `@PostConstruct`, `@PreDestroy`).
- **JSR-330:** Standard DI annotations (`@Inject`, `@Named`, `@Singleton`, `@Provider`).
- **Lazy initialization:** Creating a bean only when first needed.
- **Lifecycle callbacks:** Hooks run at init (`@PostConstruct`) and destroy (`@PreDestroy`).
- **ObjectProvider:** A lazy, optional, multi-candidate access wrapper for a dependency.
- **`@Primary`:** Marks the default bean among multiple candidates.
- **Profile:** A named set of beans activated for an environment (`dev`, `prod`).
- **Prototype scope:** A new bean instance per request; not destroy-managed.
- **Proxy:** A stand-in object wrapping a bean to add cross-cutting behavior.
- **`@Qualifier`:** Narrows ambiguous autowiring to a named candidate.
- **Reflection:** Runtime inspection/invocation of classes; how Spring instantiates/wires beans.
- **`refresh()`:** The core `ApplicationContext` startup method that builds the container.
- **Scope:** How many instances of a bean exist and how long they live.
- **Service Locator:** A registry queried for dependencies; an IoC style inferior to DI.
- **Setter injection:** Supplying dependencies via setter methods; for optional deps.
- **Singleton scope:** One shared bean instance per container (Spring default).
- **SmartLifecycle:** Interface for ordered start/stop of beans with the context.
- **SOLID:** Five OO design principles; DIP and OCP underpin DI.
- **SpEL (Spring Expression Language):** Expression language usable in `@Value`/config.
- **Stereotype annotation:** `@Component` and its specializations marking bean roles.
- **Three-level cache:** Spring's mechanism for resolving setter/field circular singleton dependencies.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **IoC** = principle (control inverted to framework); **DI** = pattern (collaborators injected); **Spring container** = concrete IoC container = `ApplicationContext`.
- **`ApplicationContext` extends `BeanFactory`**: adds eager singletons, events, i18n, resources, profiles, auto post-processors. Use it always.
- **Injection: constructor > setter > field.** Constructor → `final`, immutable, fails fast on cycles, testable with plain `new`. Field injection = anti-pattern.
- **Stereotypes:** `@Component` (generic), `@Service` (intent), `@Repository` (+ exception translation), `@Controller`/`@RestController`. `@Configuration` + `@Bean` for explicit/third-party wiring (CGLIB-proxied so inter-bean calls share singletons).
- **Disambiguation order:** single match → `@Primary` → `@Qualifier` → by-name → error.
- **Default scope = singleton → keep beans stateless.** Other scopes: prototype, request, session, application, websocket.
- **Lifecycle:** instantiate → populate → aware → `@PostConstruct`/`afterPropertiesSet`/init → (proxy created in `postProcessAfterInitialization`) → in service → `@PreDestroy`/destroy.
- **Circular deps:** resolvable only for setter/field singletons (3-level cache); constructor cycles throw `BeanCurrentlyInCreationException`; disabled by default since Boot 2.6 — **redesign instead**.
- **`@Autowired` optional on a single constructor** (since Spring 4.3).
- **Proxy gotcha:** self-invocation bypasses `@Transactional`/`@Async`/`@Cacheable`.
- **Debugging:** `/actuator/beans`, `/actuator/conditions`, `/actuator/startup`, `--debug` report, read the full `Caused by:` chain.
- **Key exceptions:** `NoSuchBeanDefinitionException`, `NoUniqueBeanDefinitionException`, `BeanCurrentlyInCreationException`, `BeanCreationException`.

### 12.2 Self-test (no answers — active recall)

1. Trace the `refresh()` sequence and name the exact step where (a) bean definitions are loaded, (b) `BeanFactoryPostProcessor`s run, (c) eager singletons are created, and (d) AOP proxies are created. Why is each ordered where it is?
2. Explain *mechanically* (with the three-level cache) why a setter-injected singleton circular dependency can be resolved but a constructor-injected one cannot.
3. You have two `DataSource` beans and an autowiring failure. List every disambiguation lever Spring offers and the order it applies them.
4. A `@Cacheable` method never caches when called from within the same class. Explain the root cause in terms of proxies, and give two fixes.
5. Compare constructor, setter, and field injection across immutability, testability, circular-dependency behavior, and API visibility — then state your default and your exceptions.
6. When would you choose Java `@Configuration` over component-scanning annotations, and what does `proxyBeanMethods=true` change at the bytecode level?
7. A singleton service intermittently corrupts output under load. Give the most likely root cause and the fix, and explain why the default bean scope makes this a common bug.

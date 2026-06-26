# Bean Lifecycle & Scopes

> An exhaustive engineering-handbook chapter on the Spring Framework bean lifecycle and bean scopes — from first principles to deep container internals, with idiomatic Java, production guidance, and interview drills.

---

## 1. Overview & where it fits

### What it is

In the Spring Framework, a **bean** is simply an object whose creation, configuration, assembly (dependency injection), and destruction are managed by the **Spring IoC container** rather than by your own `new` calls. "IoC" stands for **Inversion of Control**: instead of *your* code controlling when and how collaborators are built and wired, you hand that responsibility to a framework that calls *you* back at the right times. **Dependency Injection (DI)** is the most common concrete form of IoC — the container injects an object's dependencies into it instead of the object fetching them.

The **bean lifecycle** is the precise, ordered sequence of steps the container performs between "I have a recipe (a bean definition) for this object" and "this object is fully wired, ready to serve traffic," and later between "the application is shutting down" and "this object's resources have been released." The **bean scope** governs *how many instances* of a bean the container creates and *how long each lives* — one shared instance for the whole container (singleton), a fresh instance per lookup (prototype), one per HTTP request, one per HTTP session, and so on.

### The problem it solves

Without a container you would write code like:

```java
// Manual wiring: brittle, repetitive, hard to test.
DataSource ds = new HikariDataSource(buildConfig());
OrderRepository repo = new JdbcOrderRepository(ds);
PricingService pricing = new PricingService(taxRules());
OrderService service = new OrderService(repo, pricing);
```

This couples every class to the *construction* of its collaborators, scatters configuration, makes lifecycle (open/close, start/stop) your manual burden, and makes substitution for tests painful. The IoC container centralizes all of that: you declare *what* you need and *how* objects relate, and the container figures out *the order to build them in*, *injects* the right collaborators, *runs initialization hooks* (e.g., open a connection pool), and *runs destruction hooks* (e.g., close the pool) on shutdown.

### When you reach for it

You are already using it the moment you use Spring (or Spring Boot). The lifecycle and scopes matter concretely when you need to:

- Run setup/teardown logic at exactly the right moment (open caches, register listeners, prewarm pools, flush buffers on shutdown).
- Control instance cardinality and thread-safety (a stateless service should be a singleton; a stateful per-request object should be request-scoped).
- Modify or wrap beans globally (AOP proxies, metrics, tracing, security) via post-processors.
- Resolve tricky construction problems: circular dependencies, lazy beans, injecting a short-lived bean into a long-lived one.

### One-paragraph mental model

> Think of the container as a **factory with an assembly line and a registry**. You give it **recipes** (bean definitions). At startup it reads and possibly *rewrites the recipes* (BeanFactoryPostProcessors), then for each bean it *builds the raw object*, *bolts on its parts* (dependency injection), *lets factory robots inspect and wrap each object as it comes off the line* (BeanPostProcessors — this is where AOP proxies and `@PostConstruct` live), and finally *stamps it "ready"* and files it in the registry. **Scope** is the policy that decides whether the registry hands back the same stamped object every time (singleton) or runs the line again for a fresh one (prototype/request/session). On shutdown the container walks the registry in reverse and runs *teardown* on each managed object.

---

## 2. Foundations from first principles

We build the vocabulary first, because every later section assumes it.

### 2.1 Container, context, factory

- **`BeanFactory`** — the root interface of the Spring IoC container. It is the minimal contract: "give me a bean by name/type, tell me its scope, is it a singleton." It supports *lazy* instantiation (beans created only when first requested). You rarely use it directly.
- **`ApplicationContext`** — a superset of `BeanFactory` that adds enterprise features: event publishing, internationalization (i18n), resource loading, automatic detection and invocation of post-processors, and **eager** instantiation of singletons at startup. This is what you actually use. Common implementations:
  - `AnnotationConfigApplicationContext` — Java-config (`@Configuration`/`@Bean`) and component scanning.
  - `ClassPathXmlApplicationContext` — legacy XML config.
  - `GenericWebApplicationContext` / `AnnotationConfigServletWebServerApplicationContext` — web variants used by Spring Boot.
- **IoC container** — umbrella term for whichever of the above is running your beans.

### 2.2 Bean definition

A **`BeanDefinition`** is the container's *recipe* (metadata) for a bean — not the bean itself. It records: the bean class, scope, whether it is lazy, constructor arguments, property values, init-method and destroy-method names, dependency hints, autowire mode, etc. Bean definitions come from `@Component`/`@Bean` scanning, XML, or programmatic registration. Crucially, **definitions are processed before any bean object is instantiated**, which is what makes `BeanFactoryPostProcessor` (section 3) able to edit them.

### 2.3 Dependency Injection styles

- **Constructor injection** — dependencies are parameters of the constructor. *Preferred*: makes dependencies explicit, enables `final` fields, guarantees a fully-initialized object, and surfaces circular dependencies as errors instead of hiding them.
- **Setter injection** — dependencies set via setters after construction. Useful for optional dependencies and (historically) for breaking some circular references.
- **Field injection** — `@Autowired` directly on a field. Convenient but discouraged: hides dependencies, can't be `final`, hard to unit-test without reflection.

### 2.4 Stereotypes and component scanning

- **`@Component`** — marks a class as a container-managed bean; **`@Service`**, **`@Repository`**, **`@Controller`**, **`@RestController`** are specializations carrying extra semantics (e.g., `@Repository` translates persistence exceptions; `@Controller` is a web MVC handler).
- **Component scanning** — `@ComponentScan` (implied by `@SpringBootApplication`) walks packages, finds stereotype-annotated classes, and turns each into a bean definition.
- **`@Configuration` + `@Bean`** — a class whose `@Bean`-annotated methods each *produce* a bean. `@Configuration` classes are themselves CGLIB-enhanced (subclassed at runtime) so that inter-`@Bean` method calls return the shared singleton rather than a new object.

### 2.5 Lifecycle, scope, and proxy — the three pillars

- **Lifecycle** — the ordered callbacks each bean instance passes through (section 3).
- **Scope** — instance cardinality and lifespan policy (section 3.6, expanded in 4 & 7).
- **Proxy** — a stand-in object the container may substitute for the real bean to add behavior transparently. Two proxy technologies:
  - **JDK dynamic proxies** — proxy an *interface*; the proxy implements the same interface(s) as the target. Built into the JDK (`java.lang.reflect.Proxy`).
  - **CGLIB proxies** — generate a *subclass* of the target class at runtime by bytecode generation; used when there is no interface (or when forced). CGLIB ("Code Generation Library") is bundled inside Spring. It cannot proxy `final` classes/methods.

### 2.6 AOP in two sentences

**Aspect-Oriented Programming (AOP)** lets you apply cross-cutting behavior (transactions, security, caching, logging, metrics) declaratively, separately from business code. Spring implements AOP at runtime by *wrapping beans in proxies*; the wrapping happens during the bean lifecycle via a `BeanPostProcessor` (section 3.3) — this is why understanding the lifecycle is essential to understanding why `@Transactional` "stops working" when you call a method from within the same class.

---

## 3. How it works internally — the heart of the doc

This section traces, step by step, what the container does from boot to shutdown. We will first describe **container bootstrap**, then the **per-bean creation lifecycle**, then the **post-processor extension points**, then **circular-dependency resolution**, then **scopes**, then **destruction**.

### 3.0 Container bootstrap (`refresh()`)

When you start an `ApplicationContext`, the central method `AbstractApplicationContext.refresh()` runs a fixed sequence (this is one of the most important methods in all of Spring). Simplified order:

1. **`prepareRefresh()`** — set start time, init property sources, validate required properties.
2. **`obtainFreshBeanFactory()`** — create the `DefaultListableBeanFactory` and **load all bean definitions** (parse `@Configuration`, scan components, read XML). After this step the container *knows about every recipe* but has built *no beans* yet.
3. **`prepareBeanFactory()`** — register framework-internal beans/processors (e.g., `ApplicationContextAwareProcessor`).
4. **`postProcessBeanFactory()`** — subclass hook (web contexts register scopes like `request`/`session` here).
5. **`invokeBeanFactoryPostProcessors()`** — run all `BeanFactoryPostProcessor`s and `BeanDefinitionRegistryPostProcessor`s. **This is where bean definitions can be added, removed, or mutated** (e.g., `ConfigurationClassPostProcessor` parses `@Configuration` and registers `@Bean` definitions; `PropertySourcesPlaceholderConfigurer` resolves `${...}` placeholders).
6. **`registerBeanPostProcessors()`** — instantiate and register all `BeanPostProcessor`s so they are ready before regular beans are created.
7. **`initMessageSource()` / `initApplicationEventMulticaster()`** — i18n and event infrastructure.
8. **`onRefresh()`** — subclass hook (e.g., Spring Boot starts the embedded web server here, conceptually).
9. **`registerListeners()`** — register `ApplicationListener`s.
10. **`finishBeanFactoryInitialization()`** — **instantiate all remaining non-lazy singletons** (`preInstantiateSingletons()`). This is where the per-bean lifecycle below runs for each eager singleton.
11. **`finishRefresh()`** — start `Lifecycle`/`SmartLifecycle` beans, publish `ContextRefreshedEvent`.

Key takeaway: **`BeanFactoryPostProcessor`s act on *definitions* in step 5; `BeanPostProcessor`s act on *instances* during step 10.** Mixing these up is a classic source of confusion.

### 3.1 The per-bean creation lifecycle (the canonical sequence)

For each bean, `AbstractAutowireCapableBeanFactory.doCreateBean()` orchestrates the following. Memorize this order — it is the single most-asked interview topic in this area.

| # | Phase | What happens | Hook you can plug into |
|---|-------|--------------|------------------------|
| 1 | **Instantiation** | Container picks a constructor and calls it (or a factory method / `@Bean` method). Raw object now exists, dependencies *not yet* set. | `InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation` (can short-circuit and return a proxy) |
| 2 | **Populate properties (DI)** | Field/setter dependencies and `@Value`s are injected. | `InstantiationAwareBeanPostProcessor.postProcessProperties` / `postProcessAfterInstantiation` |
| 3 | **Aware callbacks** | If the bean implements `*Aware` interfaces, the container injects framework objects: `BeanNameAware.setBeanName`, `BeanClassLoaderAware.setBeanClassLoader`, `BeanFactoryAware.setBeanFactory`. (`ApplicationContextAware`, `EnvironmentAware`, etc. are handled slightly later by a dedicated post-processor.) | Implement the `*Aware` interface |
| 4 | **`postProcessBeforeInitialization`** | Every `BeanPostProcessor` gets the bean *before* init callbacks. `ApplicationContextAwareProcessor` runs the remaining `*Aware` callbacks here; `CommonAnnotationBeanPostProcessor` invokes **`@PostConstruct`** here. | `BeanPostProcessor.postProcessBeforeInitialization` |
| 5 | **Initialization callbacks** | `InitializingBean.afterPropertiesSet()` runs, then the custom **init-method** (`@Bean(initMethod=...)` or XML `init-method`). | `InitializingBean`, custom init method |
| 6 | **`postProcessAfterInitialization`** | Every `BeanPostProcessor` gets the bean *after* init. **AOP proxies are created here** (the bean reference handed back to the container may now be a proxy wrapping the original). | `BeanPostProcessor.postProcessAfterInitialization` |
| 7 | **Bean is ready** | The (possibly proxied) bean is placed in the singleton cache and available for injection/lookup. | — |
| 8 | **Destruction** (on context close, singletons only) | `DisposableBean.destroy()`, then custom **destroy-method**, then **`@PreDestroy`** ordering — actually `@PreDestroy` runs *first* (see 3.5). | `@PreDestroy`, `DisposableBean`, destroy-method |

#### Precise ordering of the init-side hooks (step 4–6 expanded)

For a single bean, the exact order is:

1. Constructor (instantiation).
2. Property population (setters / field injection / `@Autowired`).
3. `BeanNameAware`, `BeanClassLoaderAware`, `BeanFactoryAware`.
4. `BeanPostProcessor.postProcessBeforeInitialization` for **all** registered BPPs, in order. Inside this:
   - `ApplicationContextAwareProcessor` fires `EnvironmentAware`, `EmbeddedValueResolverAware`, `ResourceLoaderAware`, `ApplicationEventPublisherAware`, `MessageSourceAware`, `ApplicationContextAware` (in that internal order).
   - `CommonAnnotationBeanPostProcessor` (or `InitDestroyAnnotationBeanPostProcessor`) invokes `@PostConstruct`.
5. `InitializingBean.afterPropertiesSet()`.
6. Custom init-method.
7. `BeanPostProcessor.postProcessAfterInitialization` for all BPPs, in order (AOP/transaction/async proxies created here).

So the **practical mnemonic** is:

> **Construct → Inject → Aware → BPP-before (@PostConstruct here) → afterPropertiesSet → init-method → BPP-after (proxy here) → Ready.**

#### Why `@PostConstruct` runs before `afterPropertiesSet`

`@PostConstruct` is processed by a `BeanPostProcessor` (`CommonAnnotationBeanPostProcessor`) running in the **before-initialization** phase, whereas `afterPropertiesSet` is the *initialization* phase proper. Hence: `@PostConstruct` → `afterPropertiesSet()` → init-method. All three are valid places for init logic; prefer `@PostConstruct` for portability (it is a standard JSR-250 / Jakarta annotation, not a Spring interface).

### 3.2 `BeanFactoryPostProcessor` (BFPP) — editing the recipes

A **`BeanFactoryPostProcessor`** runs once, *after* all bean definitions are loaded but *before* any bean is instantiated (refresh step 5). It receives the `ConfigurableListableBeanFactory` and can read or **mutate `BeanDefinition`s**: change scope, add property values, register new definitions, resolve placeholders.

Built-in examples:

- **`PropertySourcesPlaceholderConfigurer`** — resolves `${property}` placeholders in definitions from the `Environment`.
- **`ConfigurationClassPostProcessor`** — a `BeanDefinitionRegistryPostProcessor` (a sub-interface that can also *register* new definitions) that parses `@Configuration`, `@Bean`, `@Import`, `@ComponentScan`, conditions, etc. This is effectively how Spring Boot auto-configuration injects hundreds of bean definitions.

Contract:

```java
public interface BeanFactoryPostProcessor {
    void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException;
}
```

Rule of thumb: **BFPP touches metadata; it must NOT instantiate beans.** Forcing bean instantiation from a BFPP (e.g., calling `getBean`) causes premature creation and bypasses other BFPPs — a real production footgun (your beans skip placeholder resolution, AOP, etc.).

### 3.3 `BeanPostProcessor` (BPP) — wrapping the instances

A **`BeanPostProcessor`** runs *per bean instance* during creation (steps 4 and 6 above):

```java
public interface BeanPostProcessor {
    default Object postProcessBeforeInitialization(Object bean, String beanName) { return bean; }
    default Object postProcessAfterInitialization(Object bean, String beanName)  { return bean; }
}
```

It can **return a different object** — including a proxy — which is exactly how Spring layers behavior:

- **`AnnotationAwareAspectJAutoProxyCreator`** (an `AbstractAutoProxyCreator`, itself a `SmartInstantiationAwareBeanPostProcessor`) wraps beans matched by AOP pointcuts and `@Transactional`/`@Async`/`@Cacheable` in proxies — done in `postProcessAfterInitialization`.
- **`AutowiredAnnotationBeanPostProcessor`** processes `@Autowired`/`@Value`/`@Inject` injection points (in the populate-properties phase via `InstantiationAwareBeanPostProcessor`).
- **`CommonAnnotationBeanPostProcessor`** processes `@PostConstruct`, `@PreDestroy`, `@Resource`.
- **`ConfigurationPropertiesBindingPostProcessor`** (Boot) binds `@ConfigurationProperties` beans.

Sub-interfaces give finer control:

- **`InstantiationAwareBeanPostProcessor`** — hooks *around instantiation* (`postProcessBeforeInstantiation` can return an object to skip default construction; `postProcessProperties` mutates injected values).
- **`SmartInstantiationAwareBeanPostProcessor`** — adds early-reference exposure (`getEarlyBeanReference`) used in circular-dependency resolution (section 3.4) and predicted bean types.
- **`MergedBeanDefinitionPostProcessor`** — lets a BPP cache metadata about a merged bean definition (used to discover injection points).
- **`DestructionAwareBeanPostProcessor`** — `postProcessBeforeDestruction` (this is how `@PreDestroy` is invoked).

Ordering: BPPs implementing `PriorityOrdered` run before those implementing `Ordered`, which run before unordered ones. AOP's auto-proxy creator is carefully ordered to run last so it wraps a fully-initialized bean.

### 3.4 Circular dependency resolution (the three-level cache)

A **circular dependency** is `A → B → A`: A needs B and B needs A. Spring can resolve *singleton, setter/field-injected* circular dependencies using a **three-level cache** of singletons. (It **cannot** resolve constructor-injection cycles — they throw `BeanCurrentlyInCreationException` — because you cannot construct A without B and B without A.)

The three caches in `DefaultSingletonBeanRegistry`:

| Cache | Field | Holds | Purpose |
|-------|-------|-------|---------|
| Level 1 | `singletonObjects` | fully initialized singletons | the final registry |
| Level 2 | `earlySingletonObjects` | raw/early bean references | beans exposed before init completes |
| Level 3 | `singletonFactories` | `ObjectFactory` lambdas | produce the *early reference* (possibly an AOP proxy) on demand |

Step-by-step trace for `A ⇄ B` (both singletons, field injection):

1. Container starts creating **A**: instantiates raw A (constructor done).
2. Before populating A, container **adds an `ObjectFactory` for A to level-3 cache** (`addSingletonFactory`). This factory, when called, runs `getEarlyBeanReference` across `SmartInstantiationAwareBeanPostProcessor`s — so if A needs an AOP proxy, the *proxy* is what gets exposed early.
3. Container populates A → discovers it needs **B** → starts creating B.
4. Instantiates raw B; adds B's `ObjectFactory` to level-3.
5. Populates B → discovers it needs **A** → asks the singleton registry for A.
6. Registry checks level-1 (miss), level-2 (miss), level-3 (**hit**): calls A's `ObjectFactory`, which yields A's early reference (raw or proxy), promotes it to level-2, removes the level-3 factory. B receives this early A reference.
7. B finishes population, runs init callbacks, becomes a complete singleton in level-1.
8. Control returns to A's population: A receives the now-complete B, A finishes init, A is promoted to level-1.
9. Spring verifies that the early reference exposed for A equals the final A (if AOP changed identity unexpectedly, it raises an error).

Why **three** levels and not two: level-3 stores a *factory* rather than the object so that the early reference can be an AOP proxy created *lazily and consistently* — the proxy must be created at most once and the same proxy must be used everywhere. Caching the factory (not the object) defers proxy creation until something actually needs the early reference, while caching the *result* in level-2 ensures a single proxy identity.

Important version note: As of **Spring Boot 2.6 / Spring Framework default**, circular references are **disabled by default** (`spring.main.allow-circular-references=false`); you must opt in or, better, refactor. Treat circular dependencies as a design smell.

### 3.5 Destruction lifecycle

When you `close()` the context (or it shuts down via the registered JVM shutdown hook), singleton destruction runs in **reverse dependency order** (dependents destroyed before their dependencies). For each disposable singleton:

1. `DestructionAwareBeanPostProcessor.postProcessBeforeDestruction` runs — `CommonAnnotationBeanPostProcessor` invokes **`@PreDestroy`** here.
2. `DisposableBean.destroy()` runs.
3. The custom **destroy-method** runs (`@Bean(destroyMethod=...)` / XML `destroy-method`).

So destruction order is: `@PreDestroy` → `destroy()` → destroy-method. Note Spring Boot/Spring infers a `close`/`shutdown` method as the destroy-method automatically unless you set `@Bean(destroyMethod = "")`.

**Prototype beans are NOT destroyed by the container.** The container instantiates, wires, and runs init callbacks on a prototype, then hands it off and *forgets it* — no destruction callback fires (this is a frequent leak source; section 9).

### 3.6 Scopes — instance cardinality and lifespan

A **scope** decides, for each `getBean` request, whether to return an existing instance or create a new one, and when to destroy it.

| Scope | Bean name | Instances | Lifespan | Destruction callbacks? | Registered by |
|-------|-----------|-----------|----------|------------------------|---------------|
| **singleton** (default) | `singleton` | exactly one per container | container lifetime | yes | core |
| **prototype** | `prototype` | new on every injection/lookup | uncontrolled (GC) | **no** | core |
| **request** | `request` | one per HTTP request | the request | yes (request end) | web |
| **session** | `session` | one per HTTP session | the session | yes (session end) | web |
| **application** | `application` | one per `ServletContext` | servlet context | yes | web |
| **websocket** | `websocket` | one per WebSocket session | the WS session | yes | websocket module |

The web scopes require a web-aware context and the request to be bound to the thread (Spring Boot's `RequestContextListener`/`DispatcherServlet` does this). Because a singleton outlives a request/session-scoped bean, injecting the latter into the former requires a **scoped proxy** (section 3.7).

Custom scopes are possible by implementing `org.springframework.beans.factory.config.Scope` and registering it with `ConfigurableBeanFactory.registerScope("name", scope)` (e.g., a thread scope or a tenant scope).

### 3.7 The singleton-injecting-prototype (and request/session) problem

If a **singleton** A injects a **prototype** B by plain field injection, B is resolved and set **once**, at A's creation. A then keeps that single B forever — you do *not* get a new B per use, defeating the prototype scope. Four solutions:

1. **`ObjectProvider<B>` / `Provider<B>` / `ObjectFactory<B>`** (preferred): inject a provider and call `.getObject()` / `.getIfAvailable()` each time you need a fresh B. Lazy, explicit, no proxy magic.
2. **Scoped proxy** (`@Scope(value="prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)`): inject a CGLIB proxy; every method call resolves a fresh target from the scope. Transparent but heavier.
3. **`@Lookup` method injection**: declare an abstract/overridable method annotated `@Lookup`; the container overrides it (via CGLIB) to return a fresh bean each call.
4. **`ApplicationContextAware` + `getBean`** (service locator): explicit but couples to the container — least preferred.

For **request/session** scoped beans injected into singletons you *must* use a scoped proxy (`proxyMode = TARGET_CLASS` or `INTERFACES`) or `ObjectProvider`, because at singleton-creation time there may be no active request to bind to.

---

## 4. The complete toolkit

### 4.1 Lifecycle callback mechanisms

| Mechanism | Type | Phase | Pros | Cons / Notes |
|-----------|------|-------|------|--------------|
| `@PostConstruct` | JSR-250 annotation (Jakarta) | before-init (via BPP) | standard, portable, simple | no args; runs before `afterPropertiesSet` |
| `@PreDestroy` | JSR-250 annotation | destruction (first) | standard, portable | singletons only; not for prototypes |
| `InitializingBean.afterPropertiesSet()` | Spring interface | init | guaranteed by container | couples to Spring |
| `DisposableBean.destroy()` | Spring interface | destruction | guaranteed | couples to Spring; singletons only |
| `@Bean(initMethod=, destroyMethod=)` | Java-config attrs | init / destroy | no Spring coupling on the bean class | name-based |
| XML `init-method` / `destroy-method` | XML attrs | init / destroy | legacy | rarely used now |
| `default-init-method` / `default-destroy-method` | `<beans>` attrs | init / destroy | apply to all | XML only |

### 4.2 `*Aware` interfaces (framework hooks injected into the bean)

| Interface | Injects | Typical use |
|-----------|---------|-------------|
| `BeanNameAware` | the bean's id/name | logging, self-identification |
| `BeanFactoryAware` | the owning `BeanFactory` | programmatic lookup |
| `BeanClassLoaderAware` | the class loader | class loading |
| `ApplicationContextAware` | the `ApplicationContext` | publish events, lookup, resources |
| `EnvironmentAware` | the `Environment` | read properties/profiles |
| `ResourceLoaderAware` | a `ResourceLoader` | load classpath/file resources |
| `ApplicationEventPublisherAware` | event publisher | publish app events |
| `MessageSourceAware` | i18n message source | localization |
| `EmbeddedValueResolverAware` | `${...}` resolver | resolve placeholders manually |

Prefer constructor injection of these where possible (e.g., inject `Environment` directly) instead of `*Aware`, which couples to Spring.

### 4.3 Post-processor extension points

| Interface | Granularity | When | Purpose |
|-----------|-------------|------|---------|
| `BeanDefinitionRegistryPostProcessor` | registry | refresh step 5 (earliest) | add/remove bean definitions |
| `BeanFactoryPostProcessor` | factory | refresh step 5 | mutate existing definitions |
| `BeanPostProcessor` | per instance | steps 4 & 6 | wrap/modify instances |
| `InstantiationAwareBeanPostProcessor` | per instance | around instantiation & populate | short-circuit creation, edit props |
| `SmartInstantiationAwareBeanPostProcessor` | per instance | + early reference | circular deps, type prediction |
| `MergedBeanDefinitionPostProcessor` | per definition | at merge | cache injection metadata |
| `DestructionAwareBeanPostProcessor` | per instance | destruction | `@PreDestroy`, cleanup |

### 4.4 Scope-related annotations and APIs

| API | Purpose | Key params / defaults |
|-----|---------|------------------------|
| `@Scope("singleton")` | declare scope | default scope = singleton |
| `@Scope(value, proxyMode)` | scope + proxy | `proxyMode`: `NO` (default), `INTERFACES`, `TARGET_CLASS`, `DEFAULT` |
| `@RequestScope` | meta-annotation for request scope | adds `proxyMode = TARGET_CLASS` |
| `@SessionScope` | session scope w/ proxy | `proxyMode = TARGET_CLASS` |
| `@ApplicationScope` | application scope w/ proxy | `proxyMode = TARGET_CLASS` |
| `ConfigurableBeanFactory.registerScope` | register custom scope | name + `Scope` impl |
| `ObjectProvider<T>` | lazy/optional/multiple lookup | `getObject`, `getIfAvailable`, `getIfUnique`, `stream`, `orderedStream` |
| `@Lookup` | method injection | overridden by container |

### 4.5 Lazy / eager and ordering

| API | Purpose | Default |
|-----|---------|---------|
| `@Lazy` (on bean) | defer instantiation until first use | non-lazy (eager singletons) |
| `@Lazy` (on injection point) | inject a lazy proxy | — |
| `spring.main.lazy-initialization` (Boot) | make *all* beans lazy | `false` |
| `@DependsOn` | force init order | — |
| `@Order` / `Ordered` / `@Priority` | ordering of collections & BPPs | unordered = last |
| `@Primary` / `@Qualifier` | disambiguate candidates | — |

### 4.6 `SmartLifecycle` (container start/stop, not bean create/destroy)

`Lifecycle` (`start()`/`stop()`/`isRunning()`) and `SmartLifecycle` (adds `getPhase()`, `isAutoStartup()`, async `stop(Runnable)`) control *running state* of components (servers, message listeners) and are driven at `finishRefresh()`/context close, ordered by `getPhase()` (lower phase starts first, stops last). This is distinct from the create/destroy lifecycle — use it for "start consuming"/"stop consuming" semantics.

---

## 5. Code examples by use case

### 5.1 Full lifecycle observation (all hooks at once)

```java
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.*;

@Configuration
@ComponentScan
class AppConfig {
    @Bean(initMethod = "customInit", destroyMethod = "customDestroy")
    LifecycleBean lifecycleBean() { return new LifecycleBean(); }
}

class LifecycleBean implements BeanNameAware, BeanFactoryAware,
        ApplicationContextAware, InitializingBean, DisposableBean {

    LifecycleBean() { System.out.println("1. Constructor"); }

    @Override public void setBeanName(String name) { System.out.println("2. BeanNameAware: " + name); }
    @Override public void setBeanFactory(BeanFactory bf) { System.out.println("3. BeanFactoryAware"); }
    @Override public void setApplicationContext(ApplicationContext ctx) { System.out.println("4. ApplicationContextAware"); }

    @PostConstruct void post() { System.out.println("5. @PostConstruct"); }
    @Override public void afterPropertiesSet() { System.out.println("6. afterPropertiesSet"); }
    public void customInit() { System.out.println("7. custom init-method"); }

    @PreDestroy void pre() { System.out.println("8. @PreDestroy"); }
    @Override public void destroy() { System.out.println("9. destroy()"); }
    public void customDestroy() { System.out.println("10. custom destroy-method"); }
}

public class Demo {
    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {
            // beans already constructed & initialized here (eager singletons)
        } // close() triggers destruction
    }
}
// Prints 1..7 at startup, then 8,9,10 at shutdown.
```

### 5.2 A custom `BeanPostProcessor` that times bean init

```java
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component // auto-detected because it's a stereotype; registered before regular beans
class TimingPostProcessor implements BeanPostProcessor {
    private final ConcurrentHashMap<String, Long> startTimes = new ConcurrentHashMap<>();

    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        startTimes.put(name, System.nanoTime()); // mark start of init phase
        return bean; // must return the bean (or a replacement)
    }

    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        Long start = startTimes.remove(name);
        if (start != null) {
            long micros = (System.nanoTime() - start) / 1_000;
            if (micros > 500) System.out.printf("Bean %s init took %d us%n", name, micros);
        }
        return bean;
    }
}
```

### 5.3 A `BeanFactoryPostProcessor` that forces a property at the definition level

```java
import org.springframework.beans.factory.config.*;
import org.springframework.stereotype.Component;

@Component
class EnforceConnectionTimeoutBFPP implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        // Operate on DEFINITIONS only — never call bf.getBean(...) here.
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition def = bf.getBeanDefinition(name);
            if ("com.example.HttpClientFactory".equals(def.getBeanClassName())) {
                // override / inject a property value before the bean is built
                def.getPropertyValues().add("connectTimeoutMs", 2000);
            }
        }
    }
}
```

### 5.4 Singleton injecting prototype — four idiomatic fixes

```java
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Lookup;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

@Component @Scope("prototype")
class Task { /* stateful, must be fresh each time */ }

// (A) ObjectProvider — preferred
@Component
class WorkerA {
    private final ObjectProvider<Task> taskProvider;
    WorkerA(ObjectProvider<Task> taskProvider) { this.taskProvider = taskProvider; }
    void run() { Task t = taskProvider.getObject(); /* fresh prototype each call */ }
}

// (B) Scoped proxy on the prototype bean definition
@Component @Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class ProxiedTask { }

@Component
class WorkerB {
    private final ProxiedTask task; // a CGLIB proxy; each method call hits a fresh target
    WorkerB(ProxiedTask task) { this.task = task; }
}

// (C) @Lookup method injection
@Component
abstract class WorkerC {
    void run() { Task t = createTask(); }
    @Lookup protected abstract Task createTask(); // container overrides to return fresh prototype
}
```

### 5.5 Request-scoped bean injected into a singleton controller

```java
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@Component @RequestScope // == @Scope(value="request", proxyMode=TARGET_CLASS)
class RequestContext {
    private String traceId;
    public void setTraceId(String id) { this.traceId = id; }
    public String getTraceId() { return traceId; }
}

@RestController
class OrderController {
    private final RequestContext requestContext; // injected as a CGLIB proxy
    OrderController(RequestContext requestContext) { this.requestContext = requestContext; }

    @GetMapping("/orders/{id}")
    String get(@PathVariable String id) {
        requestContext.setTraceId("t-" + id); // proxy resolves the bean for THIS request
        return "trace=" + requestContext.getTraceId();
    }
}
```

### 5.6 Lazy initialization to break a startup hotspot

```java
import org.springframework.context.annotation.*;

@Configuration
class HeavyConfig {
    // Only built when first injected/used — useful for rarely-used, expensive beans.
    @Bean @Lazy
    ExpensiveReportEngine reportEngine() { return new ExpensiveReportEngine(/* warms 200MB cache */); }
}

@org.springframework.stereotype.Service
class ReportService {
    private final ExpensiveReportEngine engine;
    // @Lazy on the injection point injects a proxy; the real bean is created on first method call
    ReportService(@Lazy ExpensiveReportEngine engine) { this.engine = engine; }
}
```

### 5.7 Custom scope: a simple thread scope

```java
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;
import java.util.*;

class ThreadScope implements Scope {
    private final ThreadLocal<Map<String, Object>> tl = ThreadLocal.withInitial(HashMap::new);

    @Override public Object get(String name, ObjectFactory<?> factory) {
        return tl.get().computeIfAbsent(name, n -> factory.getObject());
    }
    @Override public Object remove(String name) { return tl.get().remove(name); }
    @Override public void registerDestructionCallback(String name, Runnable cb) { /* store & run on thread end */ }
    @Override public Object resolveContextualObject(String key) { return null; }
    @Override public String getConversationId() { return "thread-" + Thread.currentThread().threadId(); }
}

@org.springframework.context.annotation.Configuration
class ScopeConfig {
    @org.springframework.context.annotation.Bean
    static org.springframework.beans.factory.config.CustomScopeConfigurer scopeConfigurer() {
        var cfg = new org.springframework.beans.factory.config.CustomScopeConfigurer();
        cfg.addScope("thread", new ThreadScope());
        return cfg; // registers the scope name "thread" via a BeanFactoryPostProcessor
    }
}
// Use with @Scope("thread") on a bean. Note: `static` @Bean avoids premature instantiation issues.
```

### 5.8 Why `@Transactional` self-invocation fails (lifecycle/proxy interaction)

```java
@org.springframework.stereotype.Service
class BillingService {
    @org.springframework.transaction.annotation.Transactional
    public void chargeAll(List<Long> ids) {
        for (Long id : ids) charge(id); // self-call — bypasses the proxy!
    }
    @org.springframework.transaction.annotation.Transactional(propagation = REQUIRES_NEW)
    public void charge(Long id) { /* will NOT start a new tx when called from chargeAll */ }
}
```

Because the AOP proxy was added in `postProcessAfterInitialization`, the proxy intercepts only **external** calls. `this.charge(...)` calls the *raw* method directly, so `REQUIRES_NEW` is ignored. Fix: inject a self-reference (`@Autowired BillingService self;`), split into two beans, or use `AopContext.currentProxy()`.

---

## 6. Implementation concerns & best practices

### Performance
- **Eager singletons cost startup time and memory.** Use `@Lazy` selectively for heavy, rarely-used beans; consider `spring.main.lazy-initialization=true` for fast dev startup (but it hides config errors until first use — avoid in prod unless measured).
- **Prototype/scoped beans cost a creation per lookup.** A prototype with heavy init in a tight loop is a hotspot — prefer object pools or refactor to stateless singletons + value objects.
- **Scoped proxies add a method-dispatch indirection** (CGLIB) and a per-call scope resolution. Negligible for I/O-bound work; measurable in hot CPU loops.
- **CGLIB enhancement of `@Configuration` classes** has a one-time class-generation cost at startup.

### Correctness & concurrency
- **Singletons must be thread-safe** because one instance serves all threads concurrently. Keep them stateless or use thread-safe state. The single most common Spring bug is mutable instance state in a singleton service.
- **Request/session-scoped beans are per-request/per-session**, so they may hold request state safely — but they are bound to the request thread; passing them to async threads loses the binding (use `RequestContextHolder.setRequestAttributes` or copy state).
- **Prototype beans get NO destruction callback** — manage their resources manually or use a `try`/`finally`.
- **Avoid constructor circular dependencies**; refactor to break cycles (extract a third collaborator, use events, or `ObjectProvider`).

### Memory
- Long-lived caches in singletons live for the whole JVM — bound them.
- Session-scoped beans accumulate with active sessions; large session beans + many users = heap pressure and (if clustered) serialization cost.

### Security
- Beans built from external config: validate inputs in `@PostConstruct`/`afterPropertiesSet` and fail fast.
- Be cautious exposing `ApplicationContextAware`/`getBean` (service-locator) — it widens the attack/coupling surface and bypasses DI's testability.

### Observability
- Use a `BeanPostProcessor` or `SmartLifecycle` to emit metrics/tracing at start/stop.
- Enable Boot debug to log auto-configuration and bean creation order. Actuator's `/beans` endpoint lists all beans, their scopes, and dependencies.

### Testing
- Prefer constructor injection so beans are trivially unit-testable with plain `new` and mocks — no container needed.
- For lifecycle/scope behavior, use `@SpringBootTest` or a sliced context; `@DirtiesContext` to reset; mock web scopes with `MockHttpServletRequest` + `RequestContextHolder`.

### Production hardening
- Always allow the context to close cleanly (`registerShutdownHook` is on by default in Boot) so `@PreDestroy`/destroy-methods run (flush, close pools).
- Set `@Bean(destroyMethod = "")` to suppress unwanted auto-inferred `close()`/`shutdown()` calls (e.g., a shared, externally-owned client).
- Keep `spring.main.allow-circular-references=false` (default) to surface design issues at startup.

### Anti-patterns
- Mutable shared state in singletons.
- `getBean()` in business logic (service locator).
- Field injection everywhere (untestable, hidden deps).
- Heavy work in constructors (runs before DI completes; breaks circular-dep resolution and proxying).
- Relying on `@PreDestroy` to clean up prototypes (never fires).
- Self-invocation of `@Transactional`/`@Async`/`@Cacheable` methods.
- Calling `getBean` from a `BeanFactoryPostProcessor`.

---

## 7. Advanced topics & deep internals

### 7.1 Proxy mechanics and identity
- Spring chooses **JDK dynamic proxy** if the bean implements at least one interface and `proxyTargetClass=false` (default for some, but Spring Boot defaults `spring.aop.proxy-target-class=true`, forcing **CGLIB**). CGLIB subclasses the target, so `final` classes/methods can't be proxied and a no-arg/accessible constructor is needed (Spring uses Objenesis to instantiate without calling the constructor when necessary).
- A proxied bean's `getClass()` is the proxy class, not the original — be careful with reflection, `instanceof` on concrete types, and `equals`.

### 7.2 `@Configuration` proxying (`full` vs `lite`)
- A `@Configuration(proxyBeanMethods=true)` class is CGLIB-enhanced so that calling one `@Bean` method from another returns the *cached singleton* (bean semantics preserved). Setting `proxyBeanMethods=false` (lite mode) skips the proxy for faster startup but then inter-method calls produce *new* instances — only safe if `@Bean` methods don't call each other.

### 7.3 Early bean references and `getEarlyBeanReference`
In circular-dependency resolution, `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference` is what lets AOP expose a *proxy* as the early reference. If your custom BPP creates proxies, implement this method too, or the early-injected reference will be the raw bean while the final one is a proxy — Spring detects the mismatch and throws `BeanCurrentlyInCreationException`.

### 7.4 `@Lazy` injection internals
`@Lazy` on an injection point injects a **lazy-resolution proxy**: a CGLIB/JDK proxy whose target is resolved on first method call. This also breaks some circular dependencies (the cycle isn't realized until first use). Cost: one indirection per call.

### 7.5 `FactoryBean` vs factory method
A **`FactoryBean<T>`** is a bean that *produces* another bean; `getBean("name")` returns the produced `T`, while `getBean("&name")` returns the `FactoryBean` itself. Its `getObject()` is invoked during creation; widely used internally (e.g., `SqlSessionFactoryBean`, transaction proxies). Don't confuse with a `@Bean` factory method.

### 7.6 Ordering nuances
- BPPs: `PriorityOrdered` → `Ordered` → unordered; within each, by `getOrder()` (lower first).
- Collection injection (`List<Foo>`): ordered by `@Order`/`Ordered`/`@Priority`.
- `@DependsOn` forces creation order but not injection.

### 7.7 `SmartLifecycle` phases
`getPhase()` controls start/stop order: lowest phase starts first and stops **last** (`Integer.MIN_VALUE` first start). The web server typically starts at a high phase. Use for graceful start/stop of long-running components (Kafka listeners, schedulers). `stop(Runnable)` enables async shutdown with a callback.

### 7.8 Scope binding & async
Request/session scopes use `RequestContextHolder` (a `ThreadLocal`). When work moves to another thread (async, `@Async`, reactive), the binding is lost. Mitigations: `RequestContextHolder.setRequestAttributes(attrs, true)` for inheritable thread locals, or `DelegatingSecurityContextExecutor`-style wrappers, or pass state explicitly.

### 7.9 Bean definition merging and parent contexts
Child bean definitions can inherit from parents (`parent` attribute / abstract definitions); the *merged* definition is what `MergedBeanDefinitionPostProcessor` sees. Spring also supports **parent/child ApplicationContexts** (e.g., root vs. web context in classic MVC) — a child sees the parent's beans but not vice versa.

### 7.10 Conditional and ordering of auto-configuration
Boot auto-config uses `@Conditional*` (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`) evaluated during `ConfigurationClassPostProcessor` (a BFPP), and `@AutoConfigureBefore/After/Order` to sequence configurations — all *definition-time*, before any bean is built.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing a scope

| Need | Use | Avoid |
|------|-----|-------|
| Stateless shared service/repo | **singleton** | per-request creation |
| Fresh, stateful object per use | **prototype** + `ObjectProvider`/`@Lookup` | plain field injection into a singleton |
| Per-HTTP-request state (trace, user) | **request** (proxy) | session/singleton |
| Per-user state across requests | **session** (proxy), bounded size | large session beans in clusters |
| One per servlet context | **application** | singleton if not web-specific |
| Per WebSocket session | **websocket** | request/session |

### 8.2 Init/destroy mechanism choice

| Want | Use |
|------|-----|
| Portable, no Spring coupling | `@PostConstruct` / `@PreDestroy` |
| Keep bean class framework-free | `@Bean(initMethod/destroyMethod)` |
| Need container guarantees / older code | `InitializingBean` / `DisposableBean` |
| Start/stop running components | `SmartLifecycle` |

### 8.3 Singleton→prototype injection

| Solution | Transparency | Coupling | Overhead | Pick when |
|----------|--------------|----------|----------|-----------|
| `ObjectProvider` | explicit call | low | minimal | default choice |
| Scoped proxy | transparent | none on call site | per-call proxy | call sites shouldn't know |
| `@Lookup` | semi-explicit | abstract method | CGLIB | simple "give me one" factory |
| `getBean` | explicit | high (container) | minimal | last resort |

### 8.4 JDK vs CGLIB proxy

| | JDK dynamic proxy | CGLIB |
|--|------------------|-------|
| Requires interface | yes | no |
| Proxies `final` | n/a | no |
| Constructor call | n/a | bypassed (Objenesis) |
| `getClass()` | interface proxy | subclass |
| Default in Boot | overridden — CGLIB by default | yes |

### 8.5 Lazy vs eager

Eager (default): fail-fast at startup, predictable warm-up, higher startup cost. Lazy: faster startup, deferred failures (risky), good for rarely-used heavy beans. **Rule:** eager by default in prod; lazy only for measured heavy/optional beans.

---

## 9. Failure modes & debugging

### 9.1 `BeanCurrentlyInCreationException` / circular dependency
**Symptom:** startup fails with "Requested bean is currently in creation: Is there an unresolvable circular reference?" **Cause:** constructor cycle, or `allow-circular-references=false` with a setter cycle. **Diagnose:** read the cycle chain in the stack trace. **Fix:** refactor (extract collaborator, use events), switch one side to setter/`@Lazy`/`ObjectProvider`, or (last resort) enable `spring.main.allow-circular-references=true`.

### 9.2 `NoSuchBeanDefinitionException` / `NoUniqueBeanDefinitionException`
Missing bean (not scanned, condition excluded it, wrong package) or multiple candidates. Fix scanning, add `@Primary`/`@Qualifier`, or check `@ConditionalOn*`. Use Actuator `/beans` and the auto-configuration report (`--debug`) to see what was created and why something was skipped.

### 9.3 `@Transactional`/`@Async`/`@Cacheable` "doesn't work"
Almost always **self-invocation** (proxy bypassed) or method **not public** / class is `final`. Diagnose: log `service.getClass()` — if it isn't a `$$SpringCGLIB$$`/`$Proxy` class, no proxy was created. Fix per 5.8.

### 9.4 Request/session scope: `No thread-bound request found`
**Cause:** accessing a request-scoped bean outside a web request (background thread, `@PostConstruct` of a singleton, scheduled task) without a scoped proxy. **Fix:** add `proxyMode = TARGET_CLASS` (so the proxy is injected and resolution is deferred) and only call it within a request, or bind a request context manually in tests/async.

### 9.5 Prototype/request beans leaking resources
**Cause:** no destruction callback for prototypes; request-scoped destruction not firing because the request listener isn't registered. **Fix:** clean up prototypes manually; ensure `RequestContextListener`/`DispatcherServlet` is active; for request scope, register a `DestructionAwareBeanPostProcessor`-driven cleanup.

### 9.6 Init logic runs but sees null dependencies
**Cause:** doing work in the constructor or in a `*Aware` callback before population/init completes; or relying on field injection that isn't set until after construction. **Fix:** move logic to `@PostConstruct`/`afterPropertiesSet`, or use constructor injection so deps are present in the constructor.

### 9.7 `@PreDestroy` not called
**Cause:** context not closed (JVM killed, `close()` not invoked), bean is a prototype, or destroy auto-inference disabled. **Fix:** ensure graceful shutdown (`registerShutdownHook`, Boot does this), don't expect it for prototypes.

### 9.8 BFPP instantiated beans too early
**Symptom:** beans created before `BeanPostProcessor`s/AOP/placeholder resolution are ready (no proxies, unresolved `${}`). **Cause:** calling `getBean` in a BFPP, or non-`static` `@Bean` methods returning BFPPs/BPPs causing premature `@Configuration` instantiation. **Fix:** never instantiate in a BFPP; declare BFPP/BPP `@Bean` methods `static`.

### 9.9 Real-world incident sketch
A team made a `RestTemplate`-backed `HttpClient` bean a singleton but stored a per-request auth token in a mutable field. Under load, requests interleaved and used each other's tokens — a cross-tenant data leak. Root cause: mutable state in a singleton. Fix: move token to a request-scoped bean (proxy-injected) or pass it as a method argument; the singleton client became stateless.

---

## 10. Interview drill

**Q1. Walk me through the full bean lifecycle.**
*Answer:* Instantiate (constructor) → populate properties (DI) → `*Aware` callbacks (`BeanNameAware`, `BeanFactoryAware`, then `ApplicationContextAware` via a BPP) → `BeanPostProcessor.postProcessBeforeInitialization` (which runs `@PostConstruct`) → `InitializingBean.afterPropertiesSet()` → custom init-method → `BeanPostProcessor.postProcessAfterInitialization` (AOP proxy created here) → ready. Destruction: `@PreDestroy` → `DisposableBean.destroy()` → custom destroy-method.
- *Follow-up: Where is the AOP proxy created and why there?* In `postProcessAfterInitialization`, so the proxy wraps a fully-initialized bean and is the reference handed to the container.
- *Follow-up: Why does `@PostConstruct` run before `afterPropertiesSet`?* Because it's invoked by `CommonAnnotationBeanPostProcessor` in the *before-initialization* phase, which precedes the initialization phase.
- *Follow-up: Are prototypes destroyed?* No — the container forgets them after init; no destruction callback fires.

**Q2. Difference between `BeanFactoryPostProcessor` and `BeanPostProcessor`?**
*Answer:* BFPP operates on **bean definitions** once, before any bean is instantiated (refresh step 5); BPP operates on **bean instances**, per bean, during creation. BFPP edits metadata; BPP wraps/modifies objects.
- *Follow-up: Name a built-in of each.* BFPP: `ConfigurationClassPostProcessor`, `PropertySourcesPlaceholderConfigurer`. BPP: `AutowiredAnnotationBeanPostProcessor`, the AOP auto-proxy creator.
- *Follow-up: What breaks if a BFPP calls `getBean`?* Beans get created before BPPs/AOP/placeholder resolution are ready — they miss proxying and may have unresolved `${}`.

**Q3. How does Spring resolve circular dependencies?** *(senior-signal)*
*Answer:* Via the three-level singleton cache: level-3 stores an `ObjectFactory` producing an early reference (possibly an AOP proxy via `getEarlyBeanReference`), level-2 holds the early object, level-1 the finished singleton. When B (mid-creation) asks for A (also mid-creation), the registry serves A's early reference from level-3/2. Works only for setter/field cycles of singletons — not constructor cycles.
- *Follow-up: Why a factory in level-3 rather than caching the object?* To create the AOP proxy lazily and exactly once, with consistent identity.
- *Follow-up: Why can't constructor cycles be resolved?* You can't construct either object without the other; there's no point to expose an early reference.
- *Follow-up: Default in recent Spring Boot?* Disabled (`allow-circular-references=false`); treat cycles as a design smell.

**Q4. A singleton needs a fresh prototype each call. How?** *(senior-signal)*
*Answer:* Plain injection sets the prototype once. Use `ObjectProvider`/`Provider` and call `getObject()` per use (preferred), or a scoped proxy (`proxyMode=TARGET_CLASS`), or `@Lookup` method injection.
- *Follow-up: Tradeoffs of scoped proxy vs ObjectProvider?* Proxy is transparent but adds per-call indirection and CGLIB constraints; `ObjectProvider` is explicit, lighter, testable.
- *Follow-up: Same for request scope into a singleton?* Yes — must use a scoped proxy or provider, since no request may be bound at injection time.

**Q5. Why does `@Transactional` self-invocation fail?** *(senior-signal)*
*Answer:* Transactions are applied by an AOP proxy created in `postProcessAfterInitialization`; the proxy intercepts only external calls. `this.method()` calls the raw method, bypassing the proxy. Fix: self-inject, split beans, or `AopContext.currentProxy()`.
- *Follow-up: How to confirm a proxy exists?* `bean.getClass()` shows `$$SpringCGLIB$$`/`$Proxy`.
- *Follow-up: Why does it also fail for `private`/`final` methods?* CGLIB can't override them; the advice can't be applied.

**Q6. Enumerate the scopes and their lifespans.**
*Answer:* singleton (one per container, container lifetime), prototype (new per lookup, no destruction), request, session, application, websocket (web/WS bound). See the scope table in section 3.6.
- *Follow-up: Which require a proxy when injected into a singleton?* request/session/websocket and prototype-when-you-want-fresh.
- *Follow-up: How do you add a custom scope?* Implement `Scope`, register via `CustomScopeConfigurer`/`registerScope`.

**Q7. `@PostConstruct` vs `InitializingBean` vs `@Bean(initMethod)`?**
*Answer:* All init hooks; order is `@PostConstruct` → `afterPropertiesSet` → init-method. Prefer `@PostConstruct` (portable) or `@Bean(initMethod)` (keeps bean class framework-free); `InitializingBean` couples to Spring.

**Q8. What is a `BeanDefinition` and when can you change it?**
*Answer:* The metadata recipe (class, scope, props, init/destroy, lazy). Mutable in a `BeanFactoryPostProcessor` before instantiation. Changing it after a bean is built has no effect.

**Q9. Lazy vs eager initialization — when each?** *(senior-signal-ish)*
*Answer:* Eager (default) fails fast and warms up at startup; use in prod. Lazy defers creation/failure; use for heavy, optional, rarely-used beans, or to break cycles. Global lazy speeds dev startup but hides errors.

**Q10. How are `*Aware` callbacks delivered and should you use them?**
*Answer:* Some (`BeanName/Factory/ClassLoader`) directly during creation; the rest (`ApplicationContext`, `Environment`, etc.) via `ApplicationContextAwareProcessor` in before-init. Prefer constructor-injecting `Environment`/`ApplicationEventPublisher` over `*Aware` to reduce coupling.

**Q11. Walk the container bootstrap (`refresh()`).**
*Answer:* prepare → load definitions → prepare factory → invoke BFPPs → register BPPs → init message source/event multicaster → onRefresh → register listeners → instantiate non-lazy singletons → finishRefresh (start lifecycle, publish event). See section 3.0.

**Q12. JDK vs CGLIB proxies — when and constraints?**
*Answer:* JDK proxies interfaces; CGLIB subclasses the class (needed without interfaces, can't proxy `final`, bypasses constructor via Objenesis). Boot defaults to CGLIB (`proxy-target-class=true`).

---

## 11. Glossary

- **AOP (Aspect-Oriented Programming):** applying cross-cutting behavior (tx, security, caching) declaratively, in Spring via runtime proxies.
- **ApplicationContext:** the full-featured Spring IoC container (events, i18n, eager singletons) extending `BeanFactory`.
- **Aware interface:** marker interface (`*Aware`) that signals the container to inject a framework object into the bean.
- **Bean:** an object whose lifecycle is managed by the Spring container.
- **BeanDefinition:** metadata/recipe describing how to create and configure a bean.
- **BeanFactory:** the minimal IoC container interface.
- **BeanFactoryPostProcessor (BFPP):** extension point that mutates bean definitions before instantiation.
- **BeanPostProcessor (BPP):** extension point that modifies/wraps bean instances during creation.
- **CGLIB:** bytecode library used by Spring to create subclass proxies.
- **Circular dependency:** beans that depend on each other (A→B→A).
- **Component scanning:** discovering `@Component`-annotated classes to register as beans.
- **Constructor/setter/field injection:** the three DI styles.
- **DisposableBean / `@PreDestroy` / destroy-method:** destruction callbacks for singletons.
- **DI (Dependency Injection):** the container supplies a bean's dependencies.
- **Eager vs lazy:** instantiate at startup vs on first use.
- **FactoryBean:** a bean that produces another bean; `getBean("&x")` returns the factory itself.
- **IoC (Inversion of Control):** the framework controls object construction/wiring/lifecycle.
- **InitializingBean / `@PostConstruct` / init-method:** initialization callbacks.
- **JDK dynamic proxy:** interface-based proxy from `java.lang.reflect.Proxy`.
- **JSR-250 / Jakarta annotations:** standard annotations including `@PostConstruct`, `@PreDestroy`, `@Resource`.
- **Lifecycle / SmartLifecycle:** start/stop control for running components (distinct from create/destroy).
- **Objenesis:** library to instantiate objects without invoking a constructor (used by CGLIB).
- **ObjectProvider / Provider / ObjectFactory:** lazy lookup wrappers for fresh/optional dependencies.
- **Prototype:** scope producing a new instance per lookup, with no destruction callback.
- **Proxy:** stand-in object adding behavior transparently around a target.
- **refresh():** the container bootstrap method orchestrating startup.
- **RequestContextHolder:** thread-local holding the current web request attributes (backs request/session scope).
- **Scope:** instance cardinality + lifespan policy (singleton, prototype, request, session, application, websocket, custom).
- **Scoped proxy:** a proxy injected for a shorter-lived bean so a longer-lived bean can hold it safely.
- **Singleton:** default scope; one shared instance per container.
- **Stereotype:** `@Component` and specializations (`@Service`, `@Repository`, `@Controller`).
- **Three-level cache:** the singletonObjects/earlySingletonObjects/singletonFactories caches enabling circular-dep resolution.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Lifecycle order (init):** Construct → Inject → Aware → BPP-before (**@PostConstruct**) → afterPropertiesSet → init-method → BPP-after (**AOP proxy**) → Ready.
**Lifecycle order (destroy, singletons only):** **@PreDestroy** → destroy() → destroy-method.
**BFPP vs BPP:** BFPP = definitions, once, pre-instantiation. BPP = instances, per bean, during creation.
**Scopes:** singleton (default, 1/container), prototype (new/lookup, no destroy), request, session, application, websocket. Web/WS scopes need a bound request and a scoped proxy when injected into a singleton.
**Singleton→prototype fixes:** `ObjectProvider` (best) | scoped proxy `TARGET_CLASS` | `@Lookup` | `getBean` (last resort).
**Circular deps:** 3-level cache; works for setter/field singletons, not constructors; **disabled by default** (`spring.main.allow-circular-references=false`).
**Proxies:** JDK (interface) vs CGLIB (subclass, no `final`, Objenesis); Boot default CGLIB. AOP/@Transactional self-invocation bypasses proxy.
**Lazy:** `@Lazy` per bean/injection point; global `spring.main.lazy-initialization`. Eager default = fail-fast.
**Init hook preference:** `@PostConstruct` (portable) > `@Bean(initMethod)` (decoupled) > `InitializingBean` (coupled).
**Gotchas:** mutable singleton state (thread-unsafe); no `@PreDestroy` for prototypes; `getBean` in BFPP; non-`static` `@Bean` for BPP/BFPP.

### Self-test (no answers)

1. In what exact order do `@PostConstruct`, `afterPropertiesSet()`, and a custom init-method run, and *why* is that the order?
2. You inject a `@Scope("prototype")` bean by plain field injection into a singleton and observe the same instance every time. Explain why, and give two distinct fixes with their tradeoffs.
3. Describe the three-level singleton cache and trace how `A ⇄ B` (field injection) is resolved, including where an AOP proxy enters the picture.
4. Why does calling a `@Transactional` method from another method in the *same* bean often skip the transaction semantics? How would you verify and fix it?
5. Where in `refresh()` are `BeanFactoryPostProcessor`s vs `BeanPostProcessor`s invoked, and what is the consequence of calling `getBean()` inside a `BeanFactoryPostProcessor`?
6. Which scopes require an active web request to function, what backs that binding, and what happens when work moves to an async thread?
7. Give a concrete production scenario where making a stateful bean a singleton causes a correctness/security bug, and the corrected design.

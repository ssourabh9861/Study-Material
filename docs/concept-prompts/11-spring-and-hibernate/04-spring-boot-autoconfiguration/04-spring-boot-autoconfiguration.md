# Spring Boot & Auto-Configuration

> An exhaustive engineering-handbook chapter for senior JVM backend developers. From first principles to deep internals: what Spring Boot adds, how auto-configuration actually works, the conditional machinery, starters, externalized configuration, the embedded server, Actuator, debugging/overriding, and building your own starter.

---

## 1. Overview & where it fits

### What it is

**Spring Boot** is an opinionated convention-over-configuration layer that sits *on top of* the core Spring Framework. It does not replace Spring; it removes the ceremony of bootstrapping a Spring application. Its three signature contributions are:

1. **Auto-configuration** — the framework inspects what is on your classpath, what beans you have already defined, and what properties are set, and then *automatically* contributes sensible default beans (a `DataSource`, a `DispatcherServlet`, a `JdbcTemplate`, a Jackson `ObjectMapper`, an embedded Tomcat, and so on) so you don't have to wire them by hand.
2. **Starters** — curated, transitive dependency bundles (e.g. `spring-boot-starter-web`) that pull in a coherent, version-aligned set of libraries so you stop hand-picking compatible versions.
3. **Standalone, production-ready runtime** — an embedded server (Tomcat/Jetty/Undertow/Netty) so the app is a self-contained executable JAR (`java -jar app.jar`), plus **Actuator** (health checks, metrics, info endpoints) for operating it.

Before defining anything further, two adjacent terms a newcomer needs:

- **Spring Framework**: the underlying Inversion-of-Control (IoC) container plus a huge ecosystem (Spring MVC for web, Spring Data for persistence, Spring Security, etc.). **Inversion of Control** means *the framework creates and wires your objects for you* rather than you calling `new` everywhere; the objects it manages are called **beans**, and the registry that holds them is the **ApplicationContext**. **Dependency Injection (DI)** is the specific IoC technique of handing a bean its collaborators (via constructor, setter, or field) instead of the bean fetching them itself.
- **Bean definition**: metadata describing how to create a bean — its class, scope (singleton/prototype), constructor args, init/destroy methods. The container reads bean definitions, then instantiates beans from them.

### The problem it solves

Classic Spring (circa 2004–2013) was powerful but verbose. To stand up a web app you wrote XML (or `@Configuration` classes) declaring a `DispatcherServlet`, a `ViewResolver`, a `DataSource`, a `TransactionManager`, a `SessionFactory`, message converters, and so on — hundreds of lines of boilerplate that were nearly identical across every project. You also had to manually choose mutually compatible versions of Spring MVC, Jackson, Hibernate, the JDBC driver, the validation provider, etc., and a single mismatch (a `NoSuchMethodError` at runtime) could cost an afternoon.

Spring Boot's thesis: **most of that wiring is the same everywhere, so make it the default, and let the developer override only the deltas.** You add `spring-boot-starter-web`, write a `@RestController`, run `main()`, and you have a working HTTP server on port 8080 — with zero XML and zero explicit bean wiring.

### When you reach for it

- Any new Spring-based service, especially microservices and REST/gRPC backends.
- Batch jobs, messaging consumers (Kafka/RabbitMQ), scheduled workers.
- Anything you want to package as a single deployable artifact and operate with standard health/metrics endpoints.

You might *not* reach for it (or use it minimally) when: you have an existing classic Spring app with bespoke wiring you don't want disturbed; you need an extremely small footprint where even Spring's reflection cost matters (consider Spring Native/GraalVM or Quarkus/Micronaut); or you're in a non-Spring shop.

### The one-paragraph mental model

> Spring Boot is a *rules engine for bean wiring*. At startup it loads a list of candidate configuration classes (the auto-configurations), and each one carries **conditions** — "only apply me if class X is on the classpath," "only if no bean of type Y already exists," "only if property `z` is true." Conditions are evaluated in a defined order; the surviving configurations register their default beans. **Your own beans always win**, because most auto-config conditions back off the moment they see a user-defined bean of the same type. So Boot gives you a fully wired application out of the box, and you reconfigure it by (a) setting properties, (b) defining your own beans to override defaults, or (c) explicitly excluding auto-configurations.

---

## 2. Foundations from first principles

### 2.1 The Spring container, in 90 seconds

Spring's heart is the **ApplicationContext** — an object that holds a map of beans and knows how to construct them in dependency order. You tell it where to find bean definitions:

- **Component scanning**: annotate classes with `@Component` (or stereotypes `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Configuration`) and the container discovers them by scanning packages.
- **`@Bean` methods**: inside a `@Configuration` class, a method annotated `@Bean` returns an object that becomes a bean; the method name is the bean id.
- **`@Configuration`**: a class that *is* a source of bean definitions. Boot's auto-configurations are just `@Configuration` classes with conditions attached.

A few core terms defined as promised:

- **Stereotype annotations**: specialized `@Component` markers that also carry semantic meaning (e.g. `@Repository` adds persistence-exception translation; `@Controller` marks a web controller). Functionally they all make the class a scanned bean.
- **Bean scope**: lifetime/sharing of a bean. `singleton` (default) = one instance per container; `prototype` = new instance per injection; web scopes `request`/`session` exist too.
- **Bean lifecycle**: instantiate → populate dependencies → run `BeanPostProcessor`s → call init callbacks (`@PostConstruct`, `InitializingBean.afterPropertiesSet`, `@Bean(initMethod=...)`) → bean is ready → on shutdown call destroy callbacks (`@PreDestroy`, `DisposableBean.destroy`, `destroyMethod`).
- **`BeanPostProcessor` (BPP)**: a hook the container invokes around every bean's initialization; this is how proxying (AOP, transactions, `@Async`) and property binding are implemented. **AOP (Aspect-Oriented Programming)** = wrapping a bean in a proxy so cross-cutting concerns (logging, transactions, security) run before/after method calls without polluting the method body.
- **`BeanFactoryPostProcessor` (BFPP)**: a hook that runs *earlier*, on the bean *definitions* themselves, before any bean is instantiated. `ConfigurationClassPostProcessor` (which processes `@Configuration`, `@Bean`, `@Import`, conditions) and `PropertySourcesPlaceholderConfigurer` (which resolves `${...}` placeholders) are BFPPs. This earlier/later distinction matters for auto-config ordering.

### 2.2 What Spring Boot literally adds

Concretely, on top of the framework, Boot ships:

| Module | What it provides |
|---|---|
| `spring-boot` | `SpringApplication` bootstrap, embedded server abstraction, `Environment` setup, banner, `ApplicationRunner`/`CommandLineRunner`. |
| `spring-boot-autoconfigure` | ~150+ auto-configuration classes for Spring, data, web, messaging, etc. |
| `spring-boot-starters` | The starter POMs (dependency bundles). |
| `spring-boot-actuator` | Production endpoints (health, metrics, info, env, …). |
| `spring-boot-loader` | The custom classloader that lets a single fat JAR contain nested JARs. |
| `spring-boot-devtools` | Dev-time conveniences (automatic restart, live reload). |
| Build plugins | `spring-boot-maven-plugin` / `spring-boot-gradle-plugin` to build the executable JAR and manage versions. |

### 2.3 The `@SpringBootApplication` triumvirate

The annotation you put on `main` is a meta-annotation combining three things:

```java
@SpringBootApplication                 // = the three below, combined
// @SpringBootConfiguration             //  -> a specialized @Configuration
// @EnableAutoConfiguration             //  -> turn on the auto-config engine
// @ComponentScan                       //  -> scan THIS package and below
public class StoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
```

- **`@SpringBootConfiguration`**: marks the class as the primary `@Configuration` for the app (and helps tests locate it). Functionally a `@Configuration`.
- **`@ComponentScan`**: scans the package of this class and all sub-packages for `@Component`/stereotype beans. *Lesson:* put your main class in a **root package** above all your code, or scanning misses things.
- **`@EnableAutoConfiguration`**: the trigger for everything in this chapter — it imports the auto-configuration selector that loads the candidate configurations.

### 2.4 The mental shift: convention, then override

The defining behavior to internalize: **auto-config beans "back off" when you define your own.** This is implemented via `@ConditionalOnMissingBean`. So you rarely *disable* Boot; you *override by presence*. Define a `DataSource` bean and Boot's default `DataSource` auto-config silently steps aside. This is what makes Boot feel non-magical once you understand it: there is a deterministic rulebook, and you are a higher-priority input than the defaults.

---

## 3. How it works internally — the heart of the chapter

This section traces, in order, what happens from `SpringApplication.run(...)` to a fully wired, conditionally-configured context.

### 3.1 The `SpringApplication.run` lifecycle (control flow)

`SpringApplication.run` does roughly the following, in order:

1. **Create `SpringApplication`** and **deduce the application type**. Boot inspects the classpath: if `DispatcherHandler` (WebFlux) is present without `DispatcherServlet`, it's `REACTIVE`; if `DispatcherServlet`/`Servlet` is present, it's `SERVLET`; otherwise `NONE` (a plain app, no embedded server). This deduction picks which `ApplicationContext` implementation to create later.
   - **WebFlux** = Spring's reactive, non-blocking web stack (built on Project Reactor, runs on Netty by default). **Spring MVC** = the traditional servlet-based, blocking-friendly stack (runs on Tomcat by default). **Servlet** = the Java EE/Jakarta API for HTTP handling that Tomcat/Jetty implement.
2. **Load `ApplicationContextInitializer`s and `ApplicationListener`s** from `META-INF/spring.factories` (the legacy SPI file — see §3.6).
3. **Locate the main class** (by scanning the stack for the `main` method) for logging/banner purposes.
4. **Get `SpringApplicationRunListeners`** (notably `EventPublishingRunListener`) and fire **`ApplicationStartingEvent`**.
5. **Prepare the `Environment`**: create a `ConfigurableEnvironment`, attach property sources (system properties, env vars, command-line args), then run **config-data processing** to load `application.properties`/`application.yml` and activate profiles. Fire **`ApplicationEnvironmentPreparedEvent`**.
6. **Print the banner.**
7. **Create the `ApplicationContext`** (e.g. `AnnotationConfigServletWebServerApplicationContext` for a servlet web app).
8. **Prepare the context**: apply initializers, fire **`ApplicationContextInitializedEvent`**, register the primary source (your `@SpringBootApplication` class) as a bean definition, fire **`ApplicationPreparedEvent`**.
9. **Refresh the context** — `AbstractApplicationContext.refresh()`. *This is where auto-configuration actually runs* (see §3.2). At the end of refresh the embedded web server is created and started.
10. **Call runners**: invoke all `ApplicationRunner` and `CommandLineRunner` beans (your post-startup hooks).
11. Fire **`ApplicationStartedEvent`**, then **`AvailabilityChangeEvent` (LivenessState=CORRECT)**, then **`ApplicationReadyEvent`** (after which the app is serving traffic; readiness probe flips to `ACCEPTING_TRAFFIC`).
12. On any exception, fire **`ApplicationFailedEvent`** and report via `FailureAnalyzer`s (the friendly "Description / Action" error blocks).

> **Adjacent term — SPI (Service Provider Interface):** a pluggability mechanism where the framework defines an interface and discovers implementations by reading a well-known file on the classpath. Boot uses two such files: `META-INF/spring.factories` (legacy, key=interface, value=comma-separated impls) and `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (new, one auto-config FQN per line).

### 3.2 Where auto-configuration runs: inside `refresh()`

`@EnableAutoConfiguration` is meta-annotated with `@Import(AutoConfigurationImportSelector.class)`. During context refresh, the `ConfigurationClassPostProcessor` (a BFPP) parses all `@Configuration` classes, and when it hits an `@Import` of a **`DeferredImportSelector`**, it defers it until *after* all user `@Configuration` classes are processed.

- **`@Import`**: pulls another `@Configuration` class (or a selector/registrar) into the current configuration. **`ImportSelector`**: returns an array of class names to import, computed at runtime. **`DeferredImportSelector`**: an `ImportSelector` whose imports are processed *last*, so user config (and thus user beans) is known first — critical so that `@ConditionalOnMissingBean` can see your beans.

`AutoConfigurationImportSelector` is a `DeferredImportSelector`. Its `selectImports` does:

1. **Load candidate auto-configurations** — read every `AutoConfiguration.imports` file (and legacy `spring.factories` `EnableAutoConfiguration` key) on the classpath into one big list of FQ class names. In a typical web app this is ~150 candidates.
2. **Remove duplicates.**
3. **Apply explicit exclusions** — anything in `@SpringBootApplication(exclude=...)`, `@EnableAutoConfiguration(excludeName=...)`, or property `spring.autoconfigure.exclude`.
4. **Filter with `AutoConfigurationImportFilter`s** — fast, *coarse-grained* condition pre-checks (e.g. `OnClassCondition`, `OnBeanCondition`, `OnWebApplicationCondition` run here as filters) to throw out candidates whose required classes aren't even present, *before* the expensive per-class metadata parsing. This is a performance optimization; see §7.
5. **Fire `AutoConfigurationImportEvent`** (used to build the conditions report).
6. **Sort** the survivors using ordering metadata (`@AutoConfigureOrder`, `@AutoConfigureBefore`, `@AutoConfigureAfter`, and the order in `AutoConfiguration.imports`).
7. **Return** the sorted list of class names to import as configuration classes.

Each imported class is then processed like any `@Configuration`: its class-level and `@Bean`-method-level **`@Conditional`** annotations are evaluated, and surviving `@Bean` methods register beans.

### 3.3 The `@Conditional` machinery (data flow of a decision)

`@Conditional(SomeCondition.class)` attaches a `Condition` to a configuration class or `@Bean` method. The container calls `condition.matches(context, metadata)`; if it returns `false`, that class/method is skipped (its beans are never registered). Boot provides a rich library of conditions, all built on the base `SpringBootCondition`:

| Annotation | True when… | Notes |
|---|---|---|
| `@ConditionalOnClass` | the named class **is present** on the classpath | Value referenced by name so a missing class doesn't NoClassDefFoundError. |
| `@ConditionalOnMissingClass` | the named class is **absent** | |
| `@ConditionalOnBean` | a matching bean **already exists** in the context | Order-sensitive; relies on deferred import. |
| `@ConditionalOnMissingBean` | **no** matching bean exists | The "back off" workhorse. |
| `@ConditionalOnProperty` | a property has a given value (or just exists) | `havingValue`, `matchIfMissing` params. |
| `@ConditionalOnResource` | a classpath resource exists | e.g. a config file. |
| `@ConditionalOnWebApplication` | running as a web app (SERVLET/REACTIVE/ANY) | |
| `@ConditionalOnNotWebApplication` | not a web app | |
| `@ConditionalOnExpression` | a SpEL expression evaluates true | **SpEL** = Spring Expression Language, a runtime expression syntax like `${...}`/`#{...}`. |
| `@ConditionalOnSingleCandidate` | exactly one candidate bean of a type exists (or one is primary) | Used when autowiring a single dependency. |
| `@ConditionalOnJava` | JVM version in range | |
| `@ConditionalOnJndi` | a JNDI name is available | **JNDI** = Java Naming and Directory Interface, used in app servers to look up resources like DataSources. |
| `@ConditionalOnCloudPlatform` | running on a named platform (e.g. Kubernetes) | |
| `@ConditionalOnWarDeployment` | deployed as a traditional WAR | |
| `@ConditionalOnThreading` (newer) | platform vs virtual threads | Java 21 virtual threads support. |

**`@ConditionalOnMissingBean` deep mechanics** — the single most important condition:

- It is evaluated against the beans *known so far*. Because auto-config is a deferred import processed after user config, your beans are usually registered first → the auto-config backs off. ✔
- But ordering *within* auto-config matters: if auto-config A defines a bean and auto-config B has `@ConditionalOnMissingBean` for that type, B must be ordered *after* A (`@AutoConfigureAfter`) or it may run first and incorrectly define its own bean. This is why ordering annotations exist.
- It matches by **type** (and optionally `name`, `value`, `annotation`, `ignored` parameters). On a `@Bean` *method*, the "missing bean" check defaults to the method's **return type** — so always declare the broadest sensible return type.

> **Why "referenced by name" matters:** `@ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")` (or `value = HikariDataSource.class`) is read from the *annotation metadata* via ASM bytecode parsing, not by loading the class. **ASM** is a bytecode-manipulation library Spring uses to read annotation values without triggering class loading — so referencing an absent class in `@ConditionalOnClass` is safe and doesn't blow up.

### 3.4 A concrete trace: how a `DataSource` gets configured

Walk through `DataSourceAutoConfiguration` (the canonical example):

1. **Candidate loaded** from `AutoConfiguration.imports`.
2. **Class-level conditions**: `@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })` — only proceed if JDBC types are present (they are, via `spring-boot-starter-jdbc`/`-data-jpa`). `@ConditionalOnMissingBean(type="io.r2dbc.spi.ConnectionFactory")` — back off if this is a reactive R2DBC app. **R2DBC** = Reactive Relational Database Connectivity, the non-blocking equivalent of JDBC.
3. **`@EnableConfigurationProperties(DataSourceProperties.class)`** binds `spring.datasource.*` properties to a `DataSourceProperties` bean.
4. **Inner configurations** decide the implementation:
   - `EmbeddedDatabaseConfiguration` — `@ConditionalOnMissingBean(DataSource.class)` + no explicit URL + an embedded DB driver (H2/HSQLDB/Derby) on classpath → creates an in-memory `DataSource`. **H2** is a tiny in-memory Java SQL database commonly used for tests.
   - `PooledDataSourceConfiguration` — otherwise, pick a connection pool. **Connection pool** = a cache of reusable open DB connections so you don't pay the TCP+auth handshake per query. Boot's preference order: **HikariCP** (default, fastest) → **Tomcat JDBC pool** → **Apache Commons DBCP2** → `spring.datasource.type` override. Selection uses `@ConditionalOnClass` + `@ConditionalOnMissingBean(DataSource.class)` per candidate.
5. **Result**: a `HikariDataSource` bean bound from `spring.datasource.url/username/password/...`. If *you* declared a `@Bean DataSource`, every branch above sees it via `@ConditionalOnMissingBean` and backs off — your bean wins.

Downstream, `JdbcTemplateAutoConfiguration` (`@ConditionalOnSingleCandidate(DataSource.class)`) gives you a `JdbcTemplate`; `HibernateJpaAutoConfiguration` gives you an `EntityManagerFactory` and `JpaTransactionManager`; `DataSourceTransactionManagerAutoConfiguration` provides a `PlatformTransactionManager`. Each backs off if you define your own.

### 3.5 Property binding & `@ConfigurationProperties` (data flow)

When a config class is `@EnableConfigurationProperties(Foo.class)` (or `Foo` is `@ConfigurationProperties`-annotated and picked up), Boot's **`Binder`** walks the `Environment`'s ordered property sources and binds matching keys onto the object's fields. Binding is **relaxed**: `spring.datasource.maxPoolSize`, `spring.datasource.max-pool-size`, `SPRING_DATASOURCE_MAX_POOL_SIZE` all bind to the same field. Type conversion (String→int/Duration/DataSize/enum/List/Map) is handled by a `ConversionService`. Validation runs if the class is `@Validated` (JSR-380 bean validation). See §4.4 for the full property-source order.

### 3.6 `spring.factories` vs `AutoConfiguration.imports` (version note)

- **Before Spring Boot 2.7**: auto-configurations were listed under the `org.springframework.boot.autoconfigure.EnableAutoConfiguration` key in `META-INF/spring.factories`.
- **Spring Boot 2.7+ and required in 3.x**: each auto-config FQN goes one-per-line in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, and the class is annotated `@AutoConfiguration` (a specialized `@Configuration(proxyBeanMethods=false)` that also carries `before`/`after` ordering). `spring.factories` still works for *other* SPI keys (initializers, listeners, failure analyzers) but the `EnableAutoConfiguration` key is **removed in Boot 3**.

> **Spring Boot 3.x major facts** (flag as version-specific): requires **Java 17+**, migrates `javax.*` → **`jakarta.*`** (Jakarta EE namespace change), and integrates GraalVM native-image AOT. **AOT (Ahead-Of-Time) processing** = generating bean-wiring code and reflection hints at build time so the app can start faster and run as a native binary.

---

## 4. The complete toolkit

### 4.1 Bootstrap & lifecycle API

| API | Purpose | Key options / defaults |
|---|---|---|
| `SpringApplication.run(Class, args)` | One-line bootstrap. | Returns the `ApplicationContext`. |
| `new SpringApplicationBuilder(App.class)` | Fluent builder; supports parent/child contexts, banners, profiles. | `.web(WebApplicationType.NONE)`, `.profiles("prod")`, `.bannerMode(OFF)`. |
| `SpringApplication.setWebApplicationType(...)` | Force SERVLET/REACTIVE/NONE. | Auto-deduced by default. |
| `SpringApplication.setBannerMode(...)` | `CONSOLE` (default), `LOG`, `OFF`. | |
| `SpringApplication.setLazyInitialization(true)` | Create beans on first use, not at startup. | Default `false`; also `spring.main.lazy-initialization=true`. |
| `SpringApplication.setDefaultProperties(...)` | Lowest-priority fallback props. | |
| `addListeners(...)`, `addInitializers(...)` | Register lifecycle hooks programmatically. | |
| `CommandLineRunner` / `ApplicationRunner` | Run code after startup; `ApplicationRunner` gets parsed `ApplicationArguments`. | Ordered via `@Order`. |
| `@SpringBootApplication(exclude=, scanBasePackages=, proxyBeanMethods=)` | Configure the app annotation. | `proxyBeanMethods` default `true`. |

### 4.2 Auto-config authoring annotations

| Annotation | Purpose |
|---|---|
| `@AutoConfiguration(before=, after=)` | Declare a class as an auto-configuration with ordering. (Boot 2.7+) |
| `@AutoConfigureBefore` / `@AutoConfigureAfter` / `@AutoConfigureOrder` | Fine-grained ordering relative to other auto-configs. |
| `@EnableConfigurationProperties(X.class)` | Activate binding of an `@ConfigurationProperties` class as a bean. |
| `@ConfigurationPropertiesScan` | Scan a package for `@ConfigurationProperties` classes (alternative to listing each). |
| `@Conditional*` (full table in §3.3) | The condition library. |
| `@ConditionalOnAvailableEndpoint` | (Actuator) only if an endpoint is enabled/exposed. |
| `@Import` / `ImportSelector` / `ImportBeanDefinitionRegistrar` | Programmatic bean/config contribution. |

### 4.3 Common starters

| Starter | Pulls in |
|---|---|
| `spring-boot-starter` | Core: Boot, autoconfigure, logging (Logback), YAML. |
| `spring-boot-starter-web` | Spring MVC + embedded **Tomcat** + Jackson (JSON). |
| `spring-boot-starter-webflux` | Reactive WebFlux + **Netty**. |
| `spring-boot-starter-data-jpa` | Spring Data JPA + **Hibernate** + JDBC + a connection pool (Hikari). |
| `spring-boot-starter-jdbc` | `JdbcTemplate` + HikariCP. |
| `spring-boot-starter-data-redis` | Spring Data Redis + Lettuce client. |
| `spring-boot-starter-security` | Spring Security. |
| `spring-boot-starter-validation` | Hibernate Validator (JSR-380). |
| `spring-boot-starter-actuator` | Production endpoints + Micrometer metrics. |
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ, Spring Test, JSONassert. |
| `spring-boot-starter-aop` | AspectJ-style AOP. |
| `spring-boot-starter-jetty` / `-undertow` | Swap the servlet container (exclude `-tomcat` first). |
| `spring-boot-starter-parent` | Maven parent POM: dependency BOM + plugin config + Java version. |

> **BOM (Bill of Materials):** a special POM listing curated, mutually-compatible versions in `<dependencyManagement>`. Inheriting `spring-boot-starter-parent` (or importing `spring-boot-dependencies` BOM) means you declare dependencies *without versions* and Boot picks compatible ones. This is "dependency management."

### 4.4 Externalized configuration — sources & **precedence**

Boot merges many property sources into one `Environment`. **Higher in this list overrides lower** (this is the canonical Boot order; flag as version-stable but worth verifying per release):

1. Devtools global settings (`~/.config/spring-boot`) — dev only.
2. `@TestPropertySource` annotations (tests).
3. `properties` attribute on `@SpringBootTest`.
4. Command-line arguments (`--server.port=9000`).
5. `SPRING_APPLICATION_JSON` (inline JSON in an env var/system prop).
6. `ServletConfig` / `ServletContext` init params.
7. JNDI attributes (`java:comp/env`).
8. Java **System properties** (`-Dserver.port=9000`).
9. OS **environment variables** (`SERVER_PORT=9000`).
10. Profile-specific application properties *outside* the jar (`application-{profile}.properties`).
11. Profile-specific application properties *inside* the jar.
12. Application properties *outside* the jar (`application.properties`/`.yml` next to the jar or in `./config/`).
13. Application properties *inside* the jar.
14. `@PropertySource` on `@Configuration` classes.
15. Default properties (`SpringApplication.setDefaultProperties`).

Key file/format facts:

| Concept | Detail |
|---|---|
| File names | `application.properties` or `application.yml` (YAML). |
| Search locations | classpath root, classpath `/config`, current dir, `./config/`, and `./config/*/` subdirs. Override with `spring.config.location` / `spring.config.additional-location`. |
| Profiles | `application-{profile}.yml`; activate via `spring.profiles.active=prod` (and `spring.profiles.group` to compose). |
| `@ConfigurationProperties(prefix="app")` | Type-safe binding of `app.*` to a POJO; preferred over scattered `@Value`. |
| `@Value("${key:default}")` | Single-property injection with a default. |
| Relaxed binding | kebab-case is canonical; camelCase, snake_case, UPPER env-var forms all bind. |
| Encrypted/secret config | Not built-in; use Spring Cloud Config, Vault, or Kubernetes secrets mounted as env/files. |

### 4.5 Embedded server & web config

| Property / API | Purpose | Default |
|---|---|---|
| `server.port` | HTTP port; `0` = random. | `8080` |
| `server.servlet.context-path` | Base path. | `/` |
| `server.tomcat.threads.max` | Tomcat worker threads. | `200` |
| `server.tomcat.accept-count` | Backlog queue when all threads busy. | `100` |
| `server.compression.enabled` | gzip responses. | `false` |
| `server.ssl.*` | TLS keystore config. | off |
| `server.shutdown=graceful` | Drain in-flight requests on shutdown. | `immediate` |
| `WebServerFactoryCustomizer<...>` | Programmatic server tuning bean. | |
| `spring.threads.virtual.enabled=true` | Use Java 21 virtual threads for request handling. | `false` (Boot 3.2+) |

Swap containers by excluding Tomcat and adding another starter:
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-undertow</artifactId>
</dependency>
```

### 4.6 Actuator endpoints

| Endpoint | Shows | Exposed by default over HTTP? |
|---|---|---|
| `/actuator/health` | Liveness/readiness + component health (db, disk, …). | **Yes** |
| `/actuator/info` | Build/app info. | Yes (path), but empty unless info contributors configured. |
| `/actuator/metrics` | Micrometer metrics. | No (must expose) |
| `/actuator/prometheus` | Prometheus scrape format. | No |
| `/actuator/env` | Resolved `Environment` (sanitized). | No |
| `/actuator/configprops` | `@ConfigurationProperties` beans (sanitized). | No |
| `/actuator/beans` | All beans. | No |
| `/actuator/conditions` | **The conditions report** — what auto-config matched and why. | No |
| `/actuator/mappings` | All request mappings. | No |
| `/actuator/loggers` | View/change log levels at runtime. | No |
| `/actuator/threaddump`, `/heapdump` | Diagnostics. | No |
| `/actuator/shutdown` | Graceful shutdown (POST). | No, and disabled by default. |

Key Actuator config: `management.endpoints.web.exposure.include=health,info,metrics,prometheus`; `management.endpoint.health.show-details=when-authorized` (default; `always`/`never` options); `management.server.port` to move Actuator to a separate port (good for security — keep ops endpoints off the public port). **Micrometer** is the metrics facade Boot uses (like SLF4J but for metrics) that exports to Prometheus, Datadog, CloudWatch, etc.

---

## 5. Code examples by use case

### 5.1 Minimal REST service (zero config)

```java
@SpringBootApplication
public class StoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}

@RestController
@RequestMapping("/products")
class ProductController {
    @GetMapping("/{id}")
    public Product byId(@PathVariable long id) {
        return new Product(id, "Widget");
    }
}

record Product(long id, String name) {}
```
Add `spring-boot-starter-web`, run `main`. Auto-config gives you Tomcat on 8080, Jackson JSON serialization, content negotiation, error handling (`/error`), and the `DispatcherServlet`. You wrote no configuration.

### 5.2 Overriding an auto-configured bean

```java
@Configuration
class JsonConfig {
    // Boot's JacksonAutoConfiguration backs off because we define our own ObjectMapper.
    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
            .findAndAddModules()                         // pick up JavaTime, etc.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // ISO-8601 dates
            .serializationInclusion(JsonInclude.Include.NON_NULL)    // omit nulls
            .build();
    }
}
```
This is the idiomatic override: define your bean of the same type and the matching `@ConditionalOnMissingBean` in Boot's auto-config steps aside. (For Jackson specifically, prefer customizing via `Jackson2ObjectMapperBuilderCustomizer` so you keep Boot's other defaults.)

### 5.3 Type-safe configuration with `@ConfigurationProperties`

```java
@ConfigurationProperties(prefix = "billing")
@Validated
public record BillingProperties(
    @NotBlank String currency,            // billing.currency
    @Positive int retries,                // billing.retries
    Duration timeout,                     // billing.timeout=5s -> Duration
    Map<String, String> gateways          // billing.gateways.stripe=...
) {}

@Configuration
@EnableConfigurationProperties(BillingProperties.class)
class BillingConfig {
    @Bean
    BillingClient billingClient(BillingProperties props) {
        return new BillingClient(props.currency(), props.timeout(), props.retries());
    }
}
```
```yaml
# application.yml
billing:
  currency: USD
  retries: 3
  timeout: 5s            # relaxed binding to java.time.Duration
  gateways:
    stripe: https://api.stripe.com
    adyen: https://api.adyen.com
```
Why this over `@Value`: grouped, validated, IDE-autocompleted (with the config-processor — see §5.7), and easy to test.

### 5.4 Profiles for environment-specific config

```yaml
# application.yml  (common)
spring:
  application.name: store
  profiles.active: ${APP_ENV:dev}      # default dev, overridable by env var
---
spring:
  config.activate.on-profile: dev
  datasource.url: jdbc:h2:mem:store
---
spring:
  config.activate.on-profile: prod
  datasource:
    url: jdbc:postgresql://db:5432/store
    hikari.maximum-pool-size: 20
```
```java
@Service
@Profile("prod")            // bean only exists in prod
class RealPaymentGateway implements PaymentGateway { /* ... */ }

@Service
@Profile("!prod")           // dev/test stub
class FakePaymentGateway implements PaymentGateway { /* ... */ }
```

### 5.5 Customizing the embedded server programmatically

```java
@Component
class ServerTuning implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("relaxedQueryChars", "[]{}"); // accept brackets in query
            connector.setMaxPostSize(2 * 1024 * 1024);          // 2 MB
        });
    }
}
```

### 5.6 Excluding and conditionally controlling auto-config

```java
// Hard-exclude DataSource auto-config (e.g. this service has no DB):
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class NoDbApplication { /* ... */ }
```
```properties
# Equivalent via property (works even for third-party auto-configs):
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```
```java
// Toggle a feature bean with a property:
@Configuration
class FeatureConfig {
    @Bean
    @ConditionalOnProperty(prefix = "features", name = "audit", havingValue = "true",
                           matchIfMissing = false)
    AuditListener auditListener() { return new AuditListener(); }
}
```

### 5.7 A custom Actuator health indicator

```java
@Component
class PaymentGatewayHealthIndicator implements HealthIndicator {
    private final PaymentGateway gateway;
    PaymentGatewayHealthIndicator(PaymentGateway gateway) { this.gateway = gateway; }

    @Override
    public Health health() {
        try {
            var latency = gateway.ping();              // returns Duration
            return Health.up().withDetail("latencyMs", latency.toMillis()).build();
        } catch (Exception e) {
            return Health.down(e).build();             // flips /actuator/health to DOWN
        }
    }
}
```
The bean's name (`paymentGateway`) becomes the component key under `/actuator/health` details, and an `DOWN` here propagates to the overall health status (and Kubernetes readiness if wired to a group).

### 5.8 Enabling IDE metadata for your properties

Add the annotation processor so your `@ConfigurationProperties` get autocomplete/Javadoc in `application.yml`:
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-configuration-processor</artifactId>
  <optional>true</optional>
</dependency>
```
It generates `META-INF/spring-configuration-metadata.json` at compile time.

---

## 6. Implementation concerns & best practices

### 6.1 Performance (startup & runtime)
- **Startup cost** comes mostly from classpath scanning, condition evaluation, and bean instantiation. The `AutoConfigurationImportFilter` pre-filtering (§3.2) keeps the ~150 candidates from each doing full metadata parsing — this is why Boot startup is tolerable. Don't defeat it by sprinkling unconditional heavy beans.
- **Lazy init** (`spring.main.lazy-initialization=true`) cuts startup but moves cost to first request and hides startup errors — use selectively (`@Lazy` per bean) rather than globally in prod.
- **Connection pool sizing**: HikariCP default `maximum-pool-size=10`. The right size is usually small; the classic formula is `connections ≈ ((core_count * 2) + effective_spindle_count)`. Oversized pools cause DB-side contention.
- **GraalVM native image / AOT** (Boot 3): startup in tens of milliseconds and lower memory, at the cost of build complexity and reflection-hint maintenance.

### 6.2 Correctness / concurrency
- Beans are **singletons by default** and shared across request threads — **keep them stateless** or use thread-safe state. Mutable instance fields on a `@Service`/`@Controller` are a classic bug.
- `@Transactional` works via a proxy: **self-invocation** (calling another `@Transactional` method on `this`) bypasses the proxy and the transaction. Inject the bean into itself or split the class.
- Ordering pitfalls in custom auto-config: forgetting `@AutoConfigureAfter` makes `@ConditionalOnBean`/`@ConditionalOnMissingBean` nondeterministic.

### 6.3 Memory
- Each embedded Tomcat thread reserves stack (default ~512KB–1MB) → 200 threads is ~100–200MB of potential stack. Tune `server.tomcat.threads.max` for memory-constrained pods.
- Fat-JAR + nested-JAR classloading has a small overhead; for k8s prefer **layered JARs** (`spring-boot-maven-plugin` layers) so Docker caches dependency layers separately from your code layer.

### 6.4 Security
- **Never expose all Actuator endpoints publicly.** `management.endpoints.web.exposure.include=*` is a frequent leak (env, beans, heapdump). Expose the minimum, put Actuator on a separate `management.server.port`, and protect it with Spring Security.
- `/actuator/env` and `/configprops` **sanitize** keys matching `password`, `secret`, `key`, `token`, `credentials` by default — but custom secret keys may not be sanitized; configure `management.endpoint.env.keys-to-sanitize`.
- Don't log resolved properties at startup; secrets leak into logs.

### 6.5 Observability
- Expose `health`, `info`, `prometheus`. Wire **liveness** vs **readiness** probes: `management.endpoint.health.probes.enabled=true` gives `/actuator/health/liveness` and `/readiness`.
- Use Micrometer + `@Timed`/`Observation` API; Boot 3 ships **Micrometer Tracing** (successor to Spring Cloud Sleuth) for distributed traces. **Distributed tracing** = correlating a request as it hops across services via a propagated trace id.

### 6.6 Testing
- `@SpringBootTest` boots the full (or a sliced) context. Prefer **test slices** to keep tests fast: `@WebMvcTest` (just MVC layer + mocked services), `@DataJpaTest` (JPA + in-memory DB + rollback), `@JsonTest`, `@RestClientTest`. Each slice activates only the relevant auto-configurations.
- `@MockBean`/`@MockitoBean` replaces a bean with a mock in the test context.
- `@TestConfiguration` adds test-only beans without affecting component scan.
- **Testcontainers** + `@ServiceConnection` (Boot 3.1+) spins up a real Postgres/Kafka in Docker and auto-wires its connection details — far more faithful than H2.

### 6.7 Production hardening checklist
- Enable `server.shutdown=graceful` + a `spring.lifecycle.timeout-per-shutdown-phase`.
- Pin versions via the BOM; run `mvn dependency:tree` to catch drift.
- Set explicit `spring.profiles.active` per environment; never ship secrets in the jar.
- Externalize logging (`logging.level.*`, JSON logs for log aggregation).
- Set JVM container flags (`-XX:MaxRAMPercentage`) and a sane pool size.

### 6.8 Anti-patterns
- **Field injection everywhere** (`@Autowired` on fields) — prefer constructor injection (immutable, testable, fails fast).
- **`@ComponentScan(basePackages="com.acme")` set too broad/narrow** — keep the main class at the root package.
- **Re-declaring beans Boot already provides** just to tweak one thing — use the customizer beans instead.
- **`exposure.include=*`** in prod.
- **Disabling all auto-config** then re-adding configs by hand — defeats the purpose; override surgically.

---

## 7. Advanced topics & deep internals

### 7.1 The two-phase condition evaluation (performance internal)
Boot evaluates `OnClassCondition`, `OnBeanCondition`, `OnWebApplicationCondition` **twice**: first as fast `AutoConfigurationImportFilter`s during import selection (coarse, batch, multi-threaded for `OnClassCondition`), then again precisely as `@Conditional`s during config-class parsing. The filter phase uses a precomputed metadata file, `META-INF/spring-autoconfigure-metadata.properties` (generated by `spring-boot-autoconfigure-processor`), which records each auto-config's `ConditionalOnClass`/`Bean`/`WebApplication` requirements so Boot can reject candidates *without* parsing each class. This file is the secret to acceptable startup time.

### 7.2 `proxyBeanMethods=false`
`@AutoConfiguration` and many modern `@Configuration` classes set `proxyBeanMethods=false` ("lite mode"). Normally `@Configuration` classes are **CGLIB-proxied** so that calling one `@Bean` method from another returns the *same* singleton. With `proxyBeanMethods=false`, no proxy is created (faster, less memory) but inter-`@Bean` method calls create *new* instances — so you must inject dependencies as method parameters instead of calling sibling `@Bean` methods. **CGLIB** is a bytecode library used to subclass classes at runtime for proxying.

### 7.3 Ordering algorithm details
Final auto-config order is computed by `AutoConfigurationSorter`: start from the order in the imports file, then apply `@AutoConfigureOrder` (lower runs first; default `0`), then enforce `@AutoConfigureBefore`/`After` as a topological constraint. A cycle throws at startup. Ordering only affects auto-configs relative to each other — user `@Configuration` is always processed before any of them.

### 7.4 `FailureAnalyzer` and friendly errors
When startup throws, Boot runs registered `FailureAnalyzer`s (from `spring.factories`) to turn a stack trace into a "**Description / Action**" block. Example: a port-in-use error becomes "Web server failed to start. Port 8080 was already in use. Action: identify and stop the process…". You can write your own for your starter.

### 7.5 Config Data API (Boot 2.4+) — replaced the old loader
The legacy `application.properties` loader was replaced by the **Config Data API** (`ConfigDataLocationResolver`/`Loader`), enabling `spring.config.import` (e.g. `spring.config.import=configtree:/etc/secrets/`, or `optional:vault://`, or `optional:configserver:`). This also changed multi-document YAML profile semantics (use `spring.config.activate.on-profile`, not the old `spring.profiles`).

### 7.6 Lesser-known knobs
- `spring.factories` `EnvironmentPostProcessor` lets you mutate the `Environment` very early (before context refresh) — used to inject computed defaults.
- `@ImportAutoConfiguration` (used in test slices) imports a *specific* set of auto-configs deterministically, ignoring the global imports list.
- `spring.main.allow-bean-definition-overriding=true` re-enables overriding a bean by name (disabled by default since Boot 2.1 to surface accidental duplicates).
- `spring.main.banner-mode`, `spring.output.ansi.enabled` for cosmetics.
- `spring.main.cloud-platform=NONE` to disable cloud-platform detection.

### 7.7 Virtual threads (Boot 3.2+, Java 21)
`spring.threads.virtual.enabled=true` routes Tomcat/Jetty request handling and `@Async`/scheduled execution onto **virtual threads** (lightweight JVM-managed threads), improving throughput for blocking I/O workloads without growing the OS-thread pool. Watch for `synchronized` pinning and thread-local heavy code.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Boot vs classic Spring vs alternatives

| Dimension | Classic Spring | Spring Boot | Quarkus / Micronaut |
|---|---|---|---|
| Wiring | Manual (XML/Java config) | Auto-configured | Compile-time DI |
| Startup time | Moderate | Moderate (faster with AOT) | Very fast |
| Native image | Hard | Supported (Boot 3 AOT) | First-class |
| Ecosystem breadth | Huge | Huge (same) | Growing |
| Learning curve | Steeper wiring | Gentle start, "magic" later | New idioms |
| Best for | Legacy/bespoke | Mainstream services | Serverless/edge, fast cold start |

### 8.2 Override strategy decision

| Goal | Mechanism |
|---|---|
| Tweak a value | Set a property. |
| Replace a default component | Define a `@Bean` of that type (relies on `@ConditionalOnMissingBean`). |
| Add to a default (e.g. extra Jackson module) | Use the provided `*Customizer` bean. |
| Remove a feature entirely | `exclude` the auto-config (annotation or `spring.autoconfigure.exclude`). |
| Conditionally enable your own feature | `@ConditionalOnProperty`. |

### 8.3 Embedded server choice

| Server | Use when |
|---|---|
| **Tomcat** (default) | General servlet apps; mature, well-understood. |
| **Undertow** | Lower memory, high throughput, lightweight. |
| **Jetty** | Long-standing alternative, good for embedded/async. |
| **Netty** (WebFlux) | Reactive, non-blocking, high-concurrency I/O. |

**Use auto-config when** you want defaults and you'll override deltas (almost always). **Avoid/limit it when** you have a tightly bespoke context where Boot's defaults fight you — then exclude specific auto-configs rather than abandoning Boot.

---

## 9. Failure modes & debugging

### 9.1 The conditions report — your #1 tool
Run with `--debug` (or `debug=true`) and Boot prints the **Auto-Configuration Report**: `Positive matches` (auto-configs that applied and why), `Negative matches` (what *didn't* apply and the failing condition), `Exclusions`, and `Unconditional classes`. The same data is at `/actuator/conditions`. When "my bean isn't being created" or "Boot configured something I didn't expect," this report tells you exactly which condition decided.

### 9.2 Common production failures

| Symptom | Likely cause | Diagnose / fix |
|---|---|---|
| `Port 8080 was already in use` | Another process/instance bound the port. | `lsof -i:8080`; set `server.port`. FailureAnalyzer already names it. |
| `Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured` | JPA/JDBC on classpath but no DB URL and no embedded driver. | Set `spring.datasource.url`, add H2 for tests, or exclude `DataSourceAutoConfiguration`. |
| Two beans of same type → `NoUniqueBeanDefinitionException` | You defined a bean Boot also defined, or two of yours. | Mark one `@Primary`, use `@Qualifier`, or check conditions report for overlap. |
| `BeanCurrentlyInCreationException` | Circular dependency (constructor cycle). | Break the cycle; use setter/`@Lazy`; redesign. |
| Property not taking effect | Wrong precedence (something higher overrides it) or wrong key/relaxed-binding mismatch. | `/actuator/env`, `/actuator/configprops`; print effective value. |
| Auto-config "missing" | A `@ConditionalOnClass` failed (missing dependency) or it was excluded. | Conditions report negative matches; check `mvn dependency:tree`. |
| `NoClassDefFoundError`/`NoSuchMethodError` at runtime | Dependency version drift (you overrode a BOM version). | `mvn dependency:tree`; let the BOM manage versions. |
| Actuator exposing secrets | `exposure.include=*`. | Restrict include list; separate management port; secure with Security. |
| Slow startup | Too many beans eagerly created / heavy `@PostConstruct`. | `ApplicationStartup` (BufferingApplicationStartup) + `/actuator/startup` for a timed bean-creation trace. |

### 9.3 Tooling for diagnosis
- `--debug` flag → conditions report.
- `/actuator/conditions`, `/actuator/beans`, `/actuator/mappings`, `/actuator/env`, `/actuator/startup`, `/actuator/threaddump`, `/actuator/heapdump`.
- `mvn dependency:tree -Dincludes=...` / `gradle dependencies` for version conflicts.
- `BufferingApplicationStartup` (set via `application.setApplicationStartup(...)`) for startup-step timings.

### 9.4 A real-world style incident
A team added `spring-boot-starter-data-redis` for a cache, deployed to prod, and pods began failing readiness because the Redis health indicator was now contributing to `/actuator/health` and the readiness probe — and the prod Redis security group blocked the pods. The auto-configured `RedisHealthIndicator` (added by the starter) turned overall health `DOWN`. Fix: open the network *and* understand that **adding a starter silently adds health contributors and auto-config beans**. Lesson: every starter you add changes the conditions report — review it.

---

## 10. Interview drill

**Q1. What does Spring Boot add over the Spring Framework?**
*Model answer:* Auto-configuration (rule-based default bean wiring from classpath/properties/existing beans), starters (curated transitive dependency bundles with managed versions), an embedded server for standalone executable JARs, and Actuator for production observability — all convention-over-configuration, with your beans overriding defaults.
- *Probe: Does Boot replace Spring?* No — it's a layer on top; `@SpringBootApplication` is still a `@Configuration` + `@ComponentScan` plus `@EnableAutoConfiguration`.
- *Probe: Where do starter versions come from?* The `spring-boot-dependencies` BOM (inherited via `spring-boot-starter-parent` or imported), so you declare dependencies without versions.
- *Probe: Is auto-config magic?* No — it's deterministic `@Conditional` evaluation you can inspect via the conditions report.

**Q2. Walk me through the auto-configuration mechanism step by step.**
*Model answer:* `@EnableAutoConfiguration` imports `AutoConfigurationImportSelector` (a `DeferredImportSelector`, so it runs after user config). It loads candidates from `AutoConfiguration.imports` (2.7+) / `spring.factories` (`EnableAutoConfiguration` key, pre-2.7), de-dupes, removes exclusions, pre-filters with `AutoConfigurationImportFilter`s using `spring-autoconfigure-metadata.properties`, sorts by order annotations, and imports the survivors as `@Configuration`. Each then has its `@Conditional`s evaluated; surviving `@Bean` methods register beans.
- *Probe: Why deferred?* So user beans are registered first and `@ConditionalOnMissingBean` correctly backs off.
- *Probe: Why two condition phases?* Fast coarse filtering (no class parsing) for startup speed, then precise evaluation.
- *Probe: How is referencing a missing class in `@ConditionalOnClass` safe?* ASM reads annotation metadata without loading the class.

**Q3. How does `@ConditionalOnMissingBean` enable overriding?**
*Model answer:* Auto-config `@Bean` methods are guarded by `@ConditionalOnMissingBean`; if a bean of that type already exists (yours, registered earlier due to deferred import), the method is skipped — your bean wins.
- *Probe: What can break it?* Auto-config ordering — if a `@ConditionalOnMissingBean` config runs *before* the config that would have defined the bean, ordering must be fixed with `@AutoConfigureAfter`.
- *Probe: Match by what?* Type by default (the `@Bean` method's return type), optionally name/annotation — declare the broadest return type.

**Q4. Explain externalized configuration precedence.**
*Model answer:* Boot merges many property sources; command-line args > `SPRING_APPLICATION_JSON` > system properties > env vars > profile-specific files (outside then inside jar) > `application.properties` (outside then inside) > `@PropertySource` > default properties. Higher overrides lower.
- *Probe: Relaxed binding?* `max-pool-size`, `maxPoolSize`, `MAX_POOL_SIZE` all bind to the same field; kebab-case is canonical.
- *Probe: `@Value` vs `@ConfigurationProperties`?* Prefer the latter: grouped, validated, type-converted, IDE-discoverable.

**Q5. How do you override or disable an auto-configuration?**
*Model answer:* Override by defining your own bean of the same type; disable via `@SpringBootApplication(exclude=...)`, `@EnableAutoConfiguration(excludeName=...)`, or `spring.autoconfigure.exclude=...`; toggle features with `@ConditionalOnProperty`.
- *Probe: Property value? Customizer bean?* Use a property for values, a `*Customizer` bean to augment a default without replacing it.
- *Probe: How verify which path applied?* The conditions report (`--debug` or `/actuator/conditions`).

**Q6. How does the embedded server work, and how do you change/tune it?**
*Model answer:* The web-app-type deduction picks a `ServletWebServerFactory` (Tomcat by default), the context starts the server during `refresh()`. Tune via `server.*` properties or a `WebServerFactoryCustomizer`. Swap by excluding `spring-boot-starter-tomcat` and adding Jetty/Undertow.
- *Probe: Random port?* `server.port=0`; read actual port from `ServletWebServerApplicationContext`/`@LocalServerPort` in tests.
- *Probe: Graceful shutdown?* `server.shutdown=graceful` drains in-flight requests.

**Q7. What is Actuator and how do you operate a Boot app with it?**
*Model answer:* A set of production endpoints (health/metrics/info/env/conditions/loggers/…). Expose the minimum (`management.endpoints.web.exposure.include`), wire health to k8s liveness/readiness probes, export metrics via Micrometer/Prometheus, and secure or isolate it on a separate management port.
- *Probe: Custom health?* Implement `HealthIndicator`.
- *Probe: Security risk?* Exposing `*` leaks env/heapdump/beans — restrict and protect.

**Q8 (senior-signal). When would you avoid auto-configuration, and how would you do it surgically?**
*Model answer:* When defaults conflict with a bespoke context or you need a minimal footprint. Don't blanket-disable; **exclude specific auto-configs** and provide explicit beans, keeping the rest. Or move to AOT/native if startup/memory dominate. The decision is about *delta cost*: overriding three beans is cheaper and safer than hand-wiring 150.

**Q9 (senior-signal). You added a starter and prod readiness started failing. Diagnose your way through it.**
*Model answer:* Starters add auto-config beans *and* health contributors. Pull the conditions report and `/actuator/health` details to see the new contributor (e.g. a Redis/DB indicator) turning health `DOWN`, then check network/credentials. Root insight: dependency changes have config side-effects; treat the conditions report as part of code review.

**Q10 (senior-signal). Justify `@ConfigurationProperties` over scattered `@Value`, and where it can bite you.**
*Model answer:* It centralizes and validates config, enables relaxed binding and type conversion, and produces IDE metadata. Risks: binding silently ignores unknown/misspelled keys unless `spring.config.import`/`ignoreUnknownFields=false`/`@ConstructorBinding` discipline is used; immutable record binding needs the right Boot version; and over-broad prefixes can collide. Mitigate with `@Validated`, `ignoreInvalidFields=false`, and tests asserting the bound object.

**Q11. What changed in Spring Boot 3?**
*Model answer:* Java 17 baseline, `javax.*`→`jakarta.*`, GraalVM/AOT integration, Micrometer Tracing replacing Sleuth, and `spring.factories` `EnableAutoConfiguration` key removed in favor of `AutoConfiguration.imports`.
- *Probe: Migration risk?* Any code or dependency using `javax.servlet`/`javax.persistence` must move to `jakarta.*`.

**Q12. How would you build a custom starter? (see §12.1 / below)**
*Model answer:* Two-module pattern: an `*-autoconfigure` module with `@AutoConfiguration` classes, `@ConfigurationProperties`, conditions, and an `AutoConfiguration.imports` file; and a thin `*-starter` module that just depends on it and the required libs. Use `@ConditionalOnMissingBean` so consumers can override, and ship configuration metadata.
- *Probe: Naming?* Third-party starters: `acme-spring-boot-starter` (don't prefix with `spring-boot-`).
- *Probe: How do consumers disable it?* `exclude`/`spring.autoconfigure.exclude` — so make the class public and listed.

---

## 11. Glossary

- **AOP (Aspect-Oriented Programming):** wrapping beans in proxies to run cross-cutting concerns (transactions, logging) around method calls.
- **AOT (Ahead-Of-Time) processing:** build-time generation of wiring/reflection hints for faster start and native images.
- **ApplicationContext:** Spring's bean container/registry.
- **ASM:** bytecode library Spring uses to read annotations without loading classes.
- **Auto-configuration:** rule-based registration of default beans based on classpath/properties/existing beans.
- **Bean:** an object managed by the Spring container.
- **BeanFactoryPostProcessor (BFPP):** hook operating on bean *definitions* before instantiation.
- **BeanPostProcessor (BPP):** hook operating around each bean's initialization.
- **BOM (Bill of Materials):** POM listing curated compatible dependency versions.
- **CGLIB:** runtime subclassing library used for `@Configuration`/AOP proxies.
- **Component scanning:** discovering `@Component`-annotated classes by package.
- **Conditions report:** Boot's positive/negative match listing of auto-configs (`--debug`, `/actuator/conditions`).
- **Config Data API:** Boot 2.4+ property-loading subsystem enabling `spring.config.import`.
- **Connection pool:** cache of reusable DB connections (e.g. HikariCP).
- **DeferredImportSelector:** import selector processed after user config — basis of auto-config.
- **Dependency Injection (DI):** supplying a bean's collaborators rather than the bean fetching them.
- **DispatcherServlet:** Spring MVC's front controller routing HTTP requests to handlers.
- **Embedded server:** an HTTP server bundled inside the app JAR (Tomcat/Jetty/Undertow/Netty).
- **Environment:** Spring's abstraction over property sources and profiles.
- **Fat/uber JAR:** a single JAR containing the app and all dependencies (nested JARs).
- **FailureAnalyzer:** turns startup exceptions into Description/Action messages.
- **H2:** lightweight in-memory Java SQL database, common in tests.
- **HikariCP:** Boot's default high-performance JDBC connection pool.
- **Inversion of Control (IoC):** framework creates/wires objects instead of your code.
- **Jakarta EE:** successor namespace to Java EE; `javax.*`→`jakarta.*` (Boot 3).
- **JDBC / R2DBC:** blocking / reactive relational DB connectivity APIs.
- **JNDI:** Java Naming and Directory Interface for looking up server resources.
- **Layered JAR:** fat JAR split into Docker-cache-friendly layers.
- **Micrometer:** metrics facade Boot uses; exports to Prometheus/Datadog/etc.
- **Profile:** a named set of beans/config active in a given environment (`dev`/`prod`).
- **`proxyBeanMethods`:** whether a `@Configuration` is CGLIB-proxied; `false` = lite mode.
- **Relaxed binding:** matching properties across kebab/camel/snake/UPPER forms.
- **Spring MVC / WebFlux:** servlet-based / reactive web stacks.
- **SpEL:** Spring Expression Language.
- **SPI (Service Provider Interface):** classpath-file-driven pluggability (`spring.factories`).
- **Starter:** a curated transitive dependency bundle.
- **Stereotype annotations:** semantic `@Component` variants (`@Service`, `@Repository`, `@Controller`).
- **Test slice:** focused test context loading only relevant auto-configs (`@WebMvcTest`, etc.).
- **Testcontainers:** library running real dependencies in Docker for tests.
- **Virtual threads:** lightweight JVM threads (Java 21) for high-concurrency blocking I/O.

---

## 12. Cheat-sheet & self-test

### 12.1 Building a custom starter (recap recipe)
```
acme-feature-spring-boot-autoconfigure/   # auto-config module
  src/main/java/com/acme/AcmeAutoConfiguration.java
  src/main/resources/META-INF/spring/
      org.springframework.boot.autoconfigure.AutoConfiguration.imports   # one FQN per line
acme-feature-spring-boot-starter/          # thin starter module
  pom.xml  -> depends on the autoconfigure module + required libs
```
```java
@AutoConfiguration
@ConditionalOnClass(AcmeClient.class)
@EnableConfigurationProperties(AcmeProperties.class)
public class AcmeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean              // let consumers override
    @ConditionalOnProperty(prefix = "acme", name = "enabled", matchIfMissing = true)
    AcmeClient acmeClient(AcmeProperties props) {
        return new AcmeClient(props.endpoint(), props.timeout());
    }
}
```

### 12.2 Dense recap
- `@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`. Put it at the **root package**.
- Auto-config = `DeferredImportSelector` → load `AutoConfiguration.imports` (2.7+) → exclude → **filter** (fast) → sort (`@AutoConfigureBefore/After/Order`) → evaluate `@Conditional` → register beans. **Your beans win** via `@ConditionalOnMissingBean`.
- Override: **property** (value) / **own bean** (replace) / **customizer** (augment) / **exclude** (remove) / **`@ConditionalOnProperty`** (toggle).
- Property precedence (high→low): CLI args > `SPRING_APPLICATION_JSON` > sys props > env vars > profile files (out→in jar) > app files (out→in jar) > `@PropertySource` > defaults. Relaxed binding everywhere.
- Defaults to memorize: `server.port=8080`, Tomcat `threads.max=200`/`accept-count=100`, Hikari `maximum-pool-size=10`, banner `CONSOLE`, lazy-init `false`, only `health` exposed over HTTP by default.
- Debug: `--debug` / `/actuator/conditions`, `/actuator/env`, `/actuator/beans`, `/actuator/startup`, `mvn dependency:tree`.
- Boot 3: Java 17+, `jakarta.*`, AOT/native, `AutoConfiguration.imports` mandatory, Micrometer Tracing.
- Security: never `exposure.include=*` in prod; isolate `management.server.port`; secrets out of the jar.
- Tests: prefer slices (`@WebMvcTest`/`@DataJpaTest`/`@JsonTest`); `@MockBean`; Testcontainers + `@ServiceConnection`.

### 12.3 Self-test (no answers)
1. Trace exactly what `SpringApplication.run` does between creating the `Environment` and firing `ApplicationReadyEvent`, and name where auto-configuration actually executes.
2. Two auto-configs each want to define a `Foo` bean and one uses `@ConditionalOnMissingBean`. What annotation guarantees the right one wins, and why is import deferral alone insufficient?
3. You set `server.port` in `application.yml` but the app starts on a different port in prod. List every property source that could be overriding it, in precedence order.
4. Design a custom starter that ships a configurable HTTP client, is overridable by consumers, can be disabled by a property, and provides IDE metadata. Sketch the modules, files, and key annotations.
5. A pod passes liveness but fails readiness after you added `spring-boot-starter-data-redis`. Explain the mechanism and the exact endpoints/reports you'd inspect.
6. Explain `proxyBeanMethods=false`: what changes, what you must do differently when writing such a config class, and why Boot uses it for `@AutoConfiguration`.
7. Why is `@ConditionalOnClass(SomeMissingClass.class)` safe even when the class is absent? Name the mechanism.
8. When would you choose Undertow or Netty over Tomcat, and what app-type deduction drives the default?
```

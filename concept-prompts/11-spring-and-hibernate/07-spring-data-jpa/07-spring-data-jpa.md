# Spring Data JPA — The Definitive Engineering Handbook Chapter

> A complete reference-and-learning document for senior JVM backend developers. Built from first principles up to deep internals, production operation, and interview mastery.

---

## 1. Overview & where it fits

**Spring Data JPA** is a Spring project that eliminates the boilerplate of writing a *Data Access Object* (DAO) / *repository* layer on top of JPA. Instead of hand-writing classes that open an `EntityManager`, build queries, execute them, map results, and handle transactions, you declare an **interface**, and Spring generates a working implementation at runtime.

The single sentence to anchor everything: **Spring Data JPA turns a Java interface declaration into a fully functional, transaction-aware data-access component by generating a proxy that delegates to JPA (usually Hibernate).**

### The problem it solves

Before Spring Data JPA, a typical repository looked like this (raw JPA):

```java
@Repository
public class UserDaoImpl implements UserDao {

    @PersistenceContext            // injects a container-managed EntityManager
    private EntityManager em;

    @Override
    @Transactional
    public User findById(Long id) {
        return em.find(User.class, id);
    }

    @Override
    @Transactional
    public List<User> findByLastName(String lastName) {
        // JPQL — a query language over entities, not tables
        return em.createQuery(
                   "select u from User u where u.lastName = :ln", User.class)
                 .setParameter("ln", lastName)
                 .getResultList();
    }

    @Override
    @Transactional
    public User save(User u) {
        if (u.getId() == null) { em.persist(u); return u; }   // INSERT
        else                   { return em.merge(u); }         // UPDATE
    }
    // ... 15 more nearly identical methods, repeated for every entity ...
}
```

Every entity (`User`, `Order`, `Product`, …) needs its own near-identical DAO. The code is 90% mechanical: open EM, build query, set parameters, run, map, manage the transaction. This is pure ceremony — error-prone, tedious, and untestable in isolation.

Spring Data JPA reduces all of the above to:

```java
public interface UserRepository extends JpaRepository<User, Long> {
    List<User> findByLastName(String lastName);   // implementation generated for you
}
```

You write **no implementation**. `findById`, `save`, `delete`, `findAll`, paging, sorting — all already exist. `findByLastName` is parsed from its *name* into a JPQL query automatically.

### When you reach for it

- You are building a Spring (or Spring Boot) application with a relational database and want a clean, declarative persistence layer.
- You want CRUD, paging, sorting, and simple finder queries with zero implementation code.
- You want a single, consistent programming model across many entities.
- You want to mix declarative queries with hand-written JPQL/native SQL and even type-safe query DSLs (Querydsl, JPA Criteria via Specifications) without leaving the repository abstraction.

### When you do NOT reach for it

- You need fine-grained control over every SQL statement, complex reporting joins, bulk ETL, or you are latency-critical at the microsecond level — consider **jOOQ**, **MyBatis**, or **Spring JdbcTemplate / JdbcClient** instead (more on this in §8).
- Your store is not relational (use Spring Data MongoDB, Spring Data Redis, etc. — same programming model, different module).
- You are writing a tiny app where one `JdbcTemplate` call would do.

### The mental model (one paragraph)

Picture four layers stacked vertically. At the bottom is **JDBC**, the raw Java API for talking to a database with SQL strings and `ResultSet`s. Above it sits **Hibernate**, an *Object-Relational Mapping* (ORM) engine that maps Java objects to rows and generates SQL for you. Above Hibernate is **JPA**, a standard *specification* (a set of interfaces and annotations) that Hibernate implements — JPA is the contract, Hibernate is one provider of that contract. At the top is **Spring Data JPA**, which does not touch SQL or rows at all; it operates purely on the JPA layer, generating the repository *plumbing* (the boilerplate around `EntityManager`) so you never write a DAO again. When you call a repository method, the call travels down: Spring Data → JPA `EntityManager` → Hibernate session → JDBC → database, and the result travels back up, mapped from `ResultSet` to entity object along the way.

---

## 2. Foundations from first principles

This section defines every core term from zero. If you already know JPA cold, skim — but the precise definitions here are load-bearing for the rest of the document.

### 2.1 What is JDBC?

**JDBC** (*Java Database Connectivity*) is the lowest-level standard Java API for relational databases. You acquire a `Connection`, create a `Statement` or `PreparedStatement`, send a SQL string, get back a `ResultSet`, and manually pull each column out by index or name. JDBC knows nothing about your domain objects — it deals in SQL text and tabular results. Everything above it ultimately produces JDBC calls.

```java
try (Connection c = dataSource.getConnection();
     PreparedStatement ps = c.prepareStatement(
         "select id, last_name from users where last_name = ?")) {
    ps.setString(1, "Smith");
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            long id = rs.getLong("id");
            String ln = rs.getString("last_name");
            // ... you build the User object by hand ...
        }
    }
}
```

### 2.2 What is ORM?

**ORM** (*Object-Relational Mapping*) is the technique of mapping rows in relational tables to objects in an object-oriented language, and vice versa. The "impedance mismatch" it bridges: tables have rows, columns, foreign keys, and no inheritance; objects have fields, references, collections, and inheritance hierarchies. An ORM engine translates between the two — turning `em.find(User.class, 7L)` into `SELECT ... FROM users WHERE id = 7` and the resulting row into a `User` instance with its fields populated.

### 2.3 What is Hibernate?

**Hibernate** is the most widely used ORM engine on the JVM. It generates SQL from your object operations, manages a cache of loaded objects (the *persistence context*, see §2.7), tracks changes to those objects (*dirty checking*), and flushes the minimal set of `INSERT`/`UPDATE`/`DELETE` statements at the right time. Hibernate predates JPA; when JPA was standardized, Hibernate became its reference-quality implementation.

### 2.4 What is JPA?

**JPA** (*Jakarta Persistence API*, formerly *Java Persistence API*) is a **specification** — a Java standard (originally `javax.persistence`, now `jakarta.persistence` since Jakarta EE 9) defining a vendor-neutral API and annotations for ORM. JPA itself ships no engine; it is a set of interfaces (`EntityManager`, `EntityManagerFactory`, `Query`, `TypedQuery`), annotations (`@Entity`, `@Id`, `@OneToMany`, …), and a query language (JPQL). A **JPA provider** (also called *persistence provider*) implements the spec: Hibernate, EclipseLink, and Apache OpenJPA are the three main ones. Because you code against JPA interfaces, you can (in theory) swap providers. In practice almost everyone uses Hibernate, and Spring Boot ships it by default.

> **Naming note (version-specific):** With Jakarta EE 9+ (2020), all JPA packages moved from `javax.persistence.*` to `jakarta.persistence.*`. Spring Boot 3.x (Spring Framework 6, late 2022) and Hibernate 6.x require `jakarta.*`. Spring Boot 2.x uses `javax.*`. This is the single most common cause of "wrong import" build errors when upgrading.

### 2.5 What is an Entity?

An **entity** is a plain Java class annotated with `@Entity` that JPA maps to a database table. Each instance corresponds to a row.

```java
import jakarta.persistence.*;

@Entity
@Table(name = "users")                 // optional; defaults to class name
public class User {

    @Id                                // marks the primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY) // DB auto-increment
    private Long id;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    private String email;              // column "email" inferred

    @Version                           // optimistic-locking version column
    private long version;

    // JPA requires a no-arg constructor (can be protected)
    protected User() {}

    public User(String lastName, String email) {
        this.lastName = lastName; this.email = email;
    }
    // getters/setters omitted
}
```

Key entity annotations introduced here:
- `@Id` — the primary key field.
- `@GeneratedValue` — how the PK is produced (`IDENTITY` = DB auto-increment, `SEQUENCE` = DB sequence, `TABLE` = a generator table, `AUTO` = provider chooses). Strategy choice has real performance implications, covered in §7.
- `@Column` — column-level mapping (name, nullability, length, precision).
- `@Version` — enables *optimistic locking* (§2.10).

### 2.6 What is the EntityManager?

The **`EntityManager`** is the central JPA interface for interacting with the persistence context. Its core methods:
- `persist(entity)` — register a new entity for insertion.
- `merge(entity)` — copy the state of a *detached* entity into a managed one (for updates).
- `find(Class, id)` — load by primary key (uses cache first).
- `getReference(Class, id)` — return a lazy proxy without hitting the DB until accessed.
- `remove(entity)` — schedule deletion.
- `flush()` — force pending changes to be written to the DB now.
- `createQuery(...)` / `createNativeQuery(...)` — build JPQL or SQL queries.

An `EntityManager` is created by an **`EntityManagerFactory`** (one per persistence unit, thread-safe, heavyweight) and is itself **not thread-safe** and short-lived (typically one per transaction/request).

### 2.7 What is the Persistence Context?

The **persistence context** (PC) is the in-memory set of *managed* entity instances that an `EntityManager` tracks within a unit of work. It is two things at once:

1. A **first-level cache (L1 cache)**: within one PC, `find(User.class, 7L)` returns the *same object instance* every time — repeated lookups don't re-hit the DB. This guarantees object identity: `a == b` for the same row in one PC.
2. A **change tracker (dirty checking)**: the PC remembers the loaded state of each entity. At flush time, it compares current field values against the snapshot and emits `UPDATE` statements only for what changed.

Entities have four **lifecycle states**:
- **Transient / new**: a freshly `new`-ed object, not associated with any PC, no DB row.
- **Managed / persistent**: associated with a PC; changes are tracked and auto-flushed.
- **Detached**: was managed, but the PC closed (or the entity was evicted); changes are no longer tracked.
- **Removed**: scheduled for deletion at flush.

Transitions: `persist()` moves transient → managed; closing the EM (or `detach()`) moves managed → detached; `merge()` brings a detached entity's state back into a managed instance; `remove()` moves managed → removed.

### 2.8 What is JPQL?

**JPQL** (*Jakarta/Java Persistence Query Language*) is an object-oriented query language that looks like SQL but operates on **entities and their fields**, not tables and columns. `select u from User u where u.lastName = :ln` queries the `User` *entity* by its `lastName` *field*; the provider translates this to SQL using the entity's mapping. JPQL supports joins across entity relationships, aggregates, subqueries, and constructor expressions. It is database-portable because the provider generates the dialect-specific SQL.

### 2.9 What is a Transaction?

A **transaction** is a unit of work that is *atomic* (all-or-nothing), *consistent*, *isolated* from concurrent work, and *durable* once committed (the **ACID** properties). In JPA, changes you make to managed entities are flushed and committed within a transaction. In Spring, transactions are usually declarative via `@Transactional` — Spring opens a transaction before the method runs and commits (or rolls back on exception) after. Spring Data JPA repository methods are **transactional by default** (see §3.6).

### 2.10 Optimistic vs. pessimistic locking

- **Optimistic locking**: assume conflicts are rare. Add a `@Version` column; on update, the provider issues `UPDATE ... SET version = 3 WHERE id = ? AND version = 2`. If another transaction already bumped the version, zero rows match and an `OptimisticLockException` is thrown. No DB locks are held between read and write.
- **Pessimistic locking**: assume conflicts are likely. Take an actual DB lock at read time (`SELECT ... FOR UPDATE`) so others block until you commit. Stronger guarantee, lower concurrency.

### 2.11 Lazy vs. eager loading

A relationship (e.g., `User` → `List<Order> orders`) can be fetched:
- **Eagerly** (`FetchType.EAGER`): loaded immediately with the parent, via join or extra query.
- **Lazily** (`FetchType.LAZY`): loaded only when the collection/reference is first accessed, using a proxy. Defaults: `@OneToMany` and `@ManyToMany` are LAZY; `@ManyToOne` and `@OneToOne` are EAGER. Lazy loading is the source of both great performance wins and the infamous N+1 and `LazyInitializationException` problems (§9).

### 2.12 Where Spring Data JPA sits in all of this

Spring Data JPA writes **none** of the SQL and does **none** of the ORM. It is a productivity layer that, at application startup, scans for repository interfaces and creates proxy beans that:
- delegate CRUD to a shared base implementation (`SimpleJpaRepository`) which calls the `EntityManager`,
- parse method names into JPQL,
- bind `@Query`-annotated JPQL/SQL,
- weave in `@Transactional` semantics,
- and add cross-cutting features (auditing, paging, projections).

Everything below the JPA line is Hibernate's job. Keep this separation crisp — most "Spring Data JPA" performance bugs are actually Hibernate/JPA behavior the developer didn't understand.

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, what happens from "I declared an interface" to "a SQL statement hits the database."

### 3.1 The abstraction stack, end to end

```
Your code:        userRepository.findByLastName("Smith")
                        │
[Spring Data JPA]  JDK dynamic proxy  ──► QueryExecutorMethodInterceptor
                        │  (decides: derived query? @Query? base CRUD?)
                        ▼
[Spring Data JPA]  PartTreeJpaQuery / SimpleJpaQuery / SimpleJpaRepository
                        │  builds a javax/jakarta TypedQuery
                        ▼
[JPA]              EntityManager.createQuery(...).getResultList()
                        ▼
[Hibernate]        Session → HQL/JPQL parse → SQL AST → dialect SQL
                        │  persistence-context check, flush if needed
                        ▼
[JDBC]             PreparedStatement → ResultSet
                        ▼
[Database]         SELECT ... FROM users WHERE last_name = ?
```

### 3.2 Startup: how repository beans are created

When the application context starts (triggered by `@EnableJpaRepositories`, which Spring Boot auto-configures):

1. **Scanning.** Spring scans the configured base packages for interfaces that extend a Spring Data *Repository* marker interface (`Repository`, `CrudRepository`, `JpaRepository`, etc.).
2. **`RepositoryFactoryBean` registration.** For each repository interface, Spring registers a `JpaRepositoryFactoryBean`. This is a `FactoryBean` — a special bean whose job is to *produce another bean* (the actual repository proxy).
3. **Metadata analysis.** The factory inspects the interface: the entity type and ID type (from the generic parameters, e.g. `JpaRepository<User, Long>`), and every declared method. Each method is classified: is it a base CRUD method, a *derived* query (parsed from the name), or annotated with `@Query`?
4. **Query pre-parsing & validation.** Depending on the bootstrap mode, query methods are parsed and (optionally) validated against the metamodel at startup. Derived methods are turned into a `PartTree` (a parsed representation of the method name). `@Query` JPQL is checked for syntax. This is why a typo in a derived method name often fails *at startup*, not at first call — a valuable fail-fast property.
5. **Proxy creation.** The factory creates a **JDK dynamic proxy** implementing the repository interface. The proxy is backed by a chain of `MethodInterceptor`s.
6. **Bean registration.** The proxy is registered as a singleton bean, injectable by interface type wherever you `@Autowired UserRepository`.

> **Bootstrap modes (Spring Boot, version-specific):** `spring.data.jpa.repositories.bootstrap-mode` can be `default`, `deferred`, or `lazy`. `lazy` defers repository initialization until first use, speeding startup but moving query-parse failures to runtime. Useful for large apps with hundreds of repositories.

### 3.3 The proxy and the interceptor chain

The proxy is a `java.lang.reflect.Proxy` (JDK dynamic proxy — a runtime-generated class implementing your interface; every method call routes to a single `invoke(proxy, method, args)` handler). Spring's handler is built from a `RepositoryComposition` plus advices. On each method call, the chain decides where to route:

1. **Is it `Object` method** (`toString`, `equals`, `hashCode`)? Handle locally.
2. **Is it a custom-implementation method** (you provided a fragment, see §7.4)? Route to your impl.
3. **Is it a base method** (`save`, `findById`, `findAll`, `delete`, paging/sorting)? Route to `SimpleJpaRepository` (the default base class).
4. **Otherwise it's a query method.** Route to the pre-built `RepositoryQuery` for that method:
   - `PartTreeJpaQuery` for derived queries,
   - `SimpleJpaQuery` for `@Query` (JPQL),
   - `NativeJpaQuery` for `@Query(nativeQuery = true)`,
   - `StoredProcedureJpaQuery` for `@Procedure`,
   - named queries (`@NamedQuery`) resolved by convention.

Around all of this, Spring wraps:
- **Transaction advice** — applies `@Transactional` semantics (the repository base class is annotated `@Transactional(readOnly = true)` at class level with write methods overriding to read-write).
- **Exception translation** — `PersistenceExceptionTranslationPostProcessor` converts provider-specific exceptions (Hibernate's `ConstraintViolationException`, etc.) into Spring's `DataAccessException` hierarchy (e.g. `DataIntegrityViolationException`), so your code never depends on Hibernate's exception types.

### 3.4 `SimpleJpaRepository` — the base CRUD implementation

Every `JpaRepository` is ultimately backed by **`SimpleJpaRepository<T, ID>`**, the single shared implementation of all base methods. It holds an `EntityManager`, the `JpaEntityInformation` (metadata), and implements, roughly:

```java
// Conceptual, abridged from Spring Data JPA source.
@Transactional
public <S extends T> S save(S entity) {
    if (entityInformation.isNew(entity)) {   // PK null (or @Version null)?
        em.persist(entity);                   // INSERT path
        return entity;
    } else {
        return em.merge(entity);              // UPDATE path
    }
}

@Transactional(readOnly = true)
public Optional<T> findById(ID id) {
    return Optional.ofNullable(em.find(domainType, id));
}

@Transactional(readOnly = true)
public List<T> findAll() {
    return em.createQuery("select e from " + entityName + " e", domainType)
             .getResultList();
}

@Transactional
public void deleteById(ID id) {
    findById(id).ifPresent(this::delete);     // load then remove (fires callbacks)
}
```

Key consequences of this implementation that surprise people:
- **`save()` on a new entity calls `persist`; on an existing one calls `merge`.** `merge` may issue a `SELECT` first if the entity isn't in the PC, then an `UPDATE`. `merge` also returns a *new managed instance* — the argument you passed in remains detached. Always use the return value: `user = repo.save(user);`
- **"Is new" detection** relies on `entityInformation.isNew()`. By default, an entity is "new" if its `@Id` is `null` (for object IDs) or if it implements `Persistable<ID>` and you override `isNew()`. With assigned IDs (you set the PK yourself, e.g. a UUID or natural key), the default check says "not new" → `merge` → an unnecessary `SELECT`. Fix: implement `Persistable` and track newness with a transient flag, or use `@Version` (a null version means new). This is a classic subtle pitfall.
- **`deleteById` loads then deletes** so JPA lifecycle callbacks and cascades fire. If you don't need that, a `@Modifying @Query("delete ...")` is faster.

### 3.5 Derived query methods — name → query, step by step

When you declare `List<User> findByLastNameAndEmailIgnoreCaseOrderByLastNameDesc(String ln, String email)`, here is exactly what happens:

1. **Strip the prefix.** Recognized prefixes: `find…By`, `read…By`, `get…By`, `query…By`, `search…By`, `stream…By`, `count…By`, `exists…By`, `delete…By` / `remove…By`. Everything between the verb and `By` is ignored "decoration" (`findTop10DistinctPeopleBy…` — `Top10` and `Distinct` are honored, `People` is ignored).
2. **Detect limiting/distinct.** `First`/`Top` (optionally with a number) → `LIMIT`; `Distinct` → `SELECT DISTINCT`.
3. **Parse the subject + predicate into a `PartTree`.** The part after `By` is split on `And`/`Or`. Each *part* maps a property path to an operator keyword.
4. **Resolve property paths.** `LastName` resolves against the entity's properties (camel-case → property `lastName`). Nested paths traverse relationships: `findByAddressZipCode` → `user.address.zipCode` (creates a join). To disambiguate, use an underscore: `findByAddress_ZipCode`.
5. **Map operator keywords.** Keywords become JPQL operators: `Is`/`Equals` (`=`), `Between`, `LessThan`, `GreaterThanEqual`, `Like`, `StartingWith`, `EndingWith`, `Containing`, `In`, `NotIn`, `IsNull`, `IsNotNull`, `True`, `False`, `IgnoreCase`, `Before`, `After`, etc.
6. **Apply ordering.** `OrderBy<Prop>Asc/Desc` becomes the `ORDER BY` clause.
7. **Bind parameters positionally.** Method arguments fill the predicate slots left to right (unless `@Param` named binding is used with `@Query`).
8. **Build and cache a `TypedQuery`.** The resulting JPQL (e.g. `select u from User u where upper(u.lastName) = upper(?1) and u.email = ?2 order by u.lastName desc`) is created once and reused.

If the method name can't be parsed (a property doesn't exist), startup fails with a clear error — fail-fast.

### 3.6 Transaction & flush mechanics inside a repository call

1. A repository method is invoked. Spring's transaction advice checks for an existing transaction.
2. If none and the method needs one, Spring starts a transaction (binding an `EntityManager` to the thread via `EntityManagerHolder`).
3. The actual query/CRUD runs against that EM's persistence context.
4. **Auto-flush:** before executing a query, Hibernate (with default `FlushMode.AUTO`) flushes pending changes if they could affect the query's results — so your reads see your own uncommitted writes. At transaction commit, a final flush runs and the DB transaction commits.
5. `@Transactional(readOnly = true)` (the default for read methods) sets the flush mode to `MANUAL`/`NEVER` for that scope and hints the JDBC driver/connection that it's read-only — skipping dirty-check flushes is a real performance win for read-heavy paths.
6. On a `RuntimeException` (or `Error`), Spring rolls back by default; on checked exceptions it commits unless `rollbackFor` is set.

> **Critical operational fact:** A single repository call that has *no* surrounding `@Transactional` creates and commits its own transaction (and the PC closes right after). That's why accessing a lazy collection *outside* a service-level `@Transactional` throws `LazyInitializationException` — the PC that could load it is already gone (§9.1).

### 3.7 Paging & sorting internals

When you call `findAll(Pageable)` returning a `Page<T>`:
1. Spring builds the main query and appends `ORDER BY` from the `Sort`, plus `LIMIT`/`OFFSET` (dialect-specific) from the `Pageable`.
2. To populate `Page.getTotalElements()`, Spring issues a **second `count(*)` query**. (This is why a `Page` does two round trips; a `Slice` does only one — it just asks for one extra row to know if there's a next page.)
3. Results and total are wrapped in a `PageImpl`.

### 3.8 The full request lifecycle (typical web app)

```
HTTP request → Controller (@Transactional?) → Service @Transactional → Repository proxy
   → SimpleJpaRepository / RepositoryQuery → EntityManager (PC bound to thread/tx)
   → Hibernate Session → SQL via JDBC → DB
   ← entities mapped → returned up → @Transactional commit (final flush)
   → PC closes → entities now DETACHED → DTO mapping / JSON serialization
```

The boundary where the PC closes is the single most important line to reason about. Anything touching a lazy field must happen *before* that boundary, i.e. inside the transaction.

---

## 4. The complete toolkit

### 4.1 Repository interface hierarchy

| Interface | Extends | What you get | Typical use |
|---|---|---|---|
| `Repository<T,ID>` | — | Empty marker; pick only methods you want | Strict, expose-nothing repos |
| `CrudRepository<T,ID>` | `Repository` | `save`, `saveAll`, `findById`, `existsById`, `findAll`, `findAllById`, `count`, `deleteById`, `delete`, `deleteAll` | Basic CRUD, store-agnostic |
| `ListCrudRepository<T,ID>` | `CrudRepository` | Same but returns `List` instead of `Iterable` (since Spring Data 3.0) | Cleaner return types |
| `PagingAndSortingRepository<T,ID>` | `Repository` | `findAll(Sort)`, `findAll(Pageable)` | Paging/sorting without full CRUD |
| `ListPagingAndSortingRepository<T,ID>` | above | `List`-returning variants | 3.0+ |
| `JpaRepository<T,ID>` | `ListCrudRepository` + `ListPagingAndSorting` + `QueryByExampleExecutor` | All of the above **plus** `flush`, `saveAndFlush`, `saveAllAndFlush`, `deleteAllInBatch`, `deleteAllByIdInBatch`, `getReferenceById`, JPA-specific batch ops | The default choice in JPA apps |
| `JpaSpecificationExecutor<T>` | — (add-on) | `findAll(Specification)`, `findOne`, `count`, paged variants | Dynamic Criteria queries (§5.4) |
| `QuerydslPredicateExecutor<T>` | — (add-on) | `findAll(Predicate)`, etc. | Type-safe Querydsl queries (§5.5) |
| `QueryByExampleExecutor<T>` | — (add-on) | `findAll(Example)`, etc. | Probe-object matching |

You usually extend `JpaRepository` and *optionally* add `JpaSpecificationExecutor` and/or `QuerydslPredicateExecutor`.

### 4.2 Core `JpaRepository` methods (selected) and defaults

| Method | Returns | Notes / gotchas |
|---|---|---|
| `save(S)` | `S` | persist if new, else merge. **Use the return value.** Not a batch op. |
| `saveAll(Iterable)` | `List` | Loops `save` per element; not true JDBC batch unless batching configured (§6.1). |
| `findById(ID)` | `Optional<T>` | L1-cache aware; `em.find`. |
| `getReferenceById(ID)` | `T` (proxy) | Lazy proxy; no DB hit until accessed (was `getOne`, deprecated). Throws on access if missing. |
| `existsById(ID)` | `boolean` | `SELECT 1` style; cheaper than `findById`. |
| `findAll()` | `List<T>` | Loads entire table — dangerous on big tables. |
| `findAll(Sort)` | `List<T>` | Adds `ORDER BY`. |
| `findAll(Pageable)` | `Page<T>` | Two queries (data + count). |
| `count()` | `long` | `SELECT count(*)`. |
| `deleteById(ID)` | `void` | Loads then removes (callbacks/cascades fire). No error if absent (3.0+ semantics; older threw). |
| `delete(T)` | `void` | Removes managed entity. |
| `deleteAllInBatch()` | `void` | Single `DELETE FROM t` — **bypasses** cascades/callbacks/L1. Fast and dangerous. |
| `deleteAllByIdInBatch(Iterable)` | `void` | `DELETE ... WHERE id IN (...)`; bypasses lifecycle. |
| `flush()` | `void` | Forces flush of PC to DB. |
| `saveAndFlush(S)` | `S` | save then flush (useful to surface constraint errors immediately / get generated IDs). |

### 4.3 Derived-query keywords (the most useful subset)

| Keyword(s) | JPQL fragment | Sample method |
|---|---|---|
| `Is`, `Equals`, (implicit) | `= ?` | `findByEmail` |
| `Between` | `between ? and ?` | `findByAgeBetween` |
| `LessThan`/`GreaterThan`(`Equal`) | `< ? / > ?` | `findByAgeGreaterThanEqual` |
| `IsNull` / `IsNotNull` | `is null` | `findByEmailIsNull` |
| `Like` / `NotLike` | `like ?` | `findByNameLike` |
| `StartingWith`/`EndingWith`/`Containing` | `like %?%` | `findByNameContaining` |
| `In` / `NotIn` | `in (?)` | `findByIdIn(Collection)` |
| `True` / `False` | `= true` | `findByActiveTrue` |
| `IgnoreCase` | `upper(x)=upper(?)` | `findByEmailIgnoreCase` |
| `Before` / `After` | `< ? / > ?` (dates) | `findByCreatedAfter` |
| `OrderBy…Asc/Desc` | `order by` | `findByActiveTrueOrderByNameAsc` |
| `Top`/`First`(`N`) | `limit N` | `findTop5ByOrderByScoreDesc` |
| `Distinct` | `select distinct` | `findDistinctByLastName` |

Return types allowed: `T`, `Optional<T>`, `List<T>`, `Stream<T>` (must be used in try-with-resources inside a tx), `Page<T>`, `Slice<T>`, `Window<T>` (keyset, 3.1+), `Future`/`CompletableFuture` (with `@Async`), reactive types (in R2DBC, not JPA).

### 4.4 `@Query` and friends

| Annotation | Purpose | Key params |
|---|---|---|
| `@Query("jpql")` | Bind JPQL directly | `value`, `countQuery` (for paging), `nativeQuery` |
| `@Query(value="sql", nativeQuery=true)` | Raw SQL | `value`, `countQuery`, plus result mapping concerns |
| `@Param("name")` | Named parameter binding | matches `:name` in query |
| `@Modifying` | Marks UPDATE/DELETE/INSERT query | `flushAutomatically`, `clearAutomatically` |
| `@QueryHints` | Pass JPA/Hibernate query hints | array of `@QueryHint` |
| `@EntityGraph` | Override fetch plan (avoid N+1) | `attributePaths`, `type` (FETCH/LOAD) |
| `@Lock` | Lock mode for the query | `LockModeType` (e.g. `PESSIMISTIC_WRITE`) |
| `@Procedure` | Call a stored procedure | `procedureName` / `name` |

### 4.5 Configuration flags (Spring Boot, `application.properties`)

| Property | Default | Effect |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `none` (or `create-drop` with embedded DB in tests) | Schema management: `none`/`validate`/`update`/`create`/`create-drop`. **Use `validate` or `none` in prod; never `update`/`create`.** |
| `spring.jpa.show-sql` | `false` | Logs SQL to stdout (unformatted). Prefer logger config below. |
| `spring.jpa.properties.hibernate.format_sql` | `false` | Pretty-print logged SQL. |
| `spring.jpa.open-in-view` | `true` | **OSIV** — keeps PC open through view rendering. Source of hidden N+1; consider `false` (§6, §9). |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | unset (no batching) | Enable JDBC batch inserts/updates (e.g. `50`). |
| `spring.jpa.properties.hibernate.order_inserts` / `order_updates` | `false` | Group same-type statements so batching works. |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | unset | Batch-fetch lazy associations (mitigates N+1). |
| `spring.jpa.properties.hibernate.generate_statistics` | `false` | Emit Hibernate stats (query counts, cache hits). |
| `spring.data.jpa.repositories.bootstrap-mode` | `default` | `default`/`deferred`/`lazy` repo init. |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | unset | Normalize timestamp time zone (set to `UTC`). |
| `logging.level.org.hibernate.SQL` | `INFO` | Set to `DEBUG` to log SQL. |
| `logging.level.org.hibernate.orm.jdbc.bind` (Hibernate 6) / `org.hibernate.type.descriptor.sql` (Hibernate 5) | — | Set to `TRACE` to log bound parameter values. |

### 4.6 Auditing annotations

| Annotation | On field of type | Populated with |
|---|---|---|
| `@CreatedDate` | `Instant`/`LocalDateTime`/`Date`/`Long` | timestamp at insert |
| `@LastModifiedDate` | same | timestamp at each update |
| `@CreatedBy` | your user type | principal at insert (via `AuditorAware`) |
| `@LastModifiedBy` | your user type | principal at each update |
| `@EnableJpaAuditing` (config) | — | turns auditing on |
| `@EntityListeners(AuditingEntityListener.class)` | on entity | hooks the listener |

---

## 5. Code examples by use case

These are distinct scenarios, not variations of one. Each is idiomatic and adaptable.

### 5.1 Use case: a clean CRUD repository with derived finders

```java
@Entity
@Table(name = "customers")
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private boolean active;
    private Instant createdAt;
    // constructors, getters, setters omitted
}

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // SELECT ... WHERE email = ?
    Optional<Customer> findByEmail(String email);

    // SELECT ... WHERE last_name = ? AND active = true ORDER BY first_name
    List<Customer> findByLastNameAndActiveTrueOrderByFirstNameAsc(String lastName);

    // SELECT ... WHERE upper(last_name) LIKE upper(?)  (Containing => %..%)
    List<Customer> findByLastNameContainingIgnoreCase(String fragment);

    // SELECT count(*) ... WHERE active = true
    long countByActiveTrue();

    // exists check, cheap
    boolean existsByEmail(String email);

    // delete by predicate (returns count of deleted rows since 3.x)
    long deleteByActiveFalse();
}
```

Usage in a service:

```java
@Service
public class CustomerService {
    private final CustomerRepository repo;
    CustomerService(CustomerRepository repo) { this.repo = repo; }

    @Transactional                       // write transaction
    public Customer register(String first, String last, String email) {
        if (repo.existsByEmail(email))
            throw new DuplicateEmailException(email);
        Customer c = new Customer(first, last, email, true, Instant.now());
        return repo.save(c);             // INSERT; returns entity with generated id
    }

    @Transactional(readOnly = true)      // read-only: no dirty-check flush
    public List<Customer> search(String fragment) {
        return repo.findByLastNameContainingIgnoreCase(fragment);
    }
}
```

### 5.2 Use case: custom JPQL and native queries with `@Query`

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    // JPQL — note we query the entity 'Order' and its fields.
    @Query("select o from Order o where o.total > :min and o.status = :status")
    List<Order> findExpensiveOrders(@Param("min") BigDecimal min,
                                    @Param("status") OrderStatus status);

    // JPQL join + aggregation, returning a DTO via constructor expression.
    @Query("""
           select new com.acme.dto.CustomerSpend(c.id, c.email, sum(o.total))
           from Order o join o.customer c
           group by c.id, c.email
           having sum(o.total) > :threshold
           """)
    List<CustomerSpend> topSpenders(@Param("threshold") BigDecimal threshold);

    // Native SQL — when you need a DB-specific feature (here, a window function).
    @Query(value = """
           select * from orders o
           where o.created_at >= now() - interval '30 days'
           order by o.total desc
           limit :n
           """, nativeQuery = true)
    List<Order> recentTopOrders(@Param("n") int n);

    // Paged native query MUST supply a countQuery.
    @Query(value = "select * from orders where status = :s",
           countQuery = "select count(*) from orders where status = :s",
           nativeQuery = true)
    Page<Order> findByStatusNative(@Param("s") String status, Pageable pageable);
}
```

The DTO used in the constructor expression:

```java
public record CustomerSpend(Long customerId, String email, BigDecimal total) {}
```

### 5.3 Use case: pagination, sorting, slices, and keyset

```java
public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByCategory(String category, Pageable pageable);
    Slice<Product> findByActiveTrue(Pageable pageable);   // no count query

    // Keyset (seek) pagination — scalable for deep pages (3.1+).
    Window<Product> findByCategoryOrderById(
        String category, ScrollPosition position, Limit limit);
}
```

```java
// Offset paging (page 2, 20 per page, sorted by price desc then name asc)
Pageable pageable = PageRequest.of(2, 20,
        Sort.by(Sort.Order.desc("price"), Sort.Order.asc("name")));
Page<Product> page = productRepo.findByCategory("books", pageable);
long total = page.getTotalElements();   // triggers the count query
List<Product> content = page.getContent();
boolean hasNext = page.hasNext();

// Keyset paging — first chunk, then continue from where we stopped.
Window<Product> first = productRepo.findByCategoryOrderById(
        "books", ScrollPosition.keyset(), Limit.of(20));
if (!first.isEmpty() && first.hasNext()) {
    ScrollPosition next = first.positionAt(first.size() - 1);
    Window<Product> second = productRepo.findByCategoryOrderById(
        "books", next, Limit.of(20));
}
```

> **Why keyset matters:** `OFFSET 100000 LIMIT 20` forces the DB to scan and discard 100k rows. Keyset (`WHERE id > :lastSeenId ORDER BY id LIMIT 20`) uses the index and stays O(page size) regardless of depth. Use it for infinite scroll and deep pagination.

### 5.4 Use case: dynamic queries with Specifications (JPA Criteria)

A **Specification** wraps a JPA Criteria `Predicate`, letting you compose filters at runtime (e.g. for a search form where any subset of fields may be present).

```java
public interface CustomerRepository
        extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {}

public final class CustomerSpecs {
    public static Specification<Customer> hasLastName(String ln) {
        return (root, query, cb) ->
            ln == null ? null : cb.equal(root.get("lastName"), ln);
    }
    public static Specification<Customer> isActive(Boolean active) {
        return (root, query, cb) ->
            active == null ? null : cb.equal(root.get("active"), active);
    }
    public static Specification<Customer> emailContains(String frag) {
        return (root, query, cb) ->
            frag == null ? null
                : cb.like(cb.lower(root.get("email")), "%" + frag.toLowerCase() + "%");
    }
}
```

```java
// Compose only the filters that are present; null specs are skipped.
Specification<Customer> spec = Specification
        .where(CustomerSpecs.hasLastName(form.lastName()))
        .and(CustomerSpecs.isActive(form.active()))
        .and(CustomerSpecs.emailContains(form.emailFragment()));

Page<Customer> result = customerRepo.findAll(spec, PageRequest.of(0, 25));
```

This generates a single SQL `WHERE` with exactly the applied conditions — no string concatenation, no SQL injection risk, fully type-checkable against the entity model.

### 5.5 Use case: type-safe queries with Querydsl

**Querydsl** generates a metamodel (`QCustomer`) from your entities at build time, giving fully type-safe, fluent predicates (a missing field is a compile error, not a runtime one).

```java
public interface CustomerRepository
        extends JpaRepository<Customer, Long>, QuerydslPredicateExecutor<Customer> {}
```

```java
QCustomer c = QCustomer.customer;            // generated metamodel
BooleanBuilder where = new BooleanBuilder();
if (form.lastName() != null) where.and(c.lastName.eq(form.lastName()));
if (form.active()  != null)  where.and(c.active.eq(form.active()));
if (form.emailFragment() != null)
    where.and(c.email.lower().contains(form.emailFragment().toLowerCase()));

Page<Customer> page = customerRepo.findAll(where, PageRequest.of(0, 25));
```

### 5.6 Use case: `@Modifying` bulk update/delete

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // Bulk UPDATE — single SQL statement, does NOT load entities.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.active = false where u.lastLogin < :cutoff")
    int deactivateStaleUsers(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from User u where u.active = false")
    int purgeInactive();
}
```

```java
@Transactional   // @Modifying queries REQUIRE an active transaction
public int deactivate(Instant cutoff) {
    return userRepo.deactivateStaleUsers(cutoff);  // returns affected row count
}
```

> **Why `clearAutomatically = true`:** a bulk `UPDATE`/`DELETE` runs as raw SQL and **bypasses the persistence context** — any already-loaded entities in the PC become stale (they still hold the old values). Clearing the PC after the bulk op prevents you from reading stale data later in the same transaction. `flushAutomatically = true` flushes pending changes *before* the bulk op so they aren't lost.

### 5.7 Use case: projections (interface and DTO/class)

Projections fetch only the columns you need, avoiding loading whole entities.

```java
// Closed interface projection — Spring generates a proxy; selects only these columns.
public interface CustomerSummary {
    Long getId();
    String getEmail();
    String getLastName();
}

// Open projection with SpEL — fetches full entity, computes in memory.
public interface CustomerName {
    @Value("#{target.firstName + ' ' + target.lastName}")
    String getFullName();
}

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<CustomerSummary> findByActiveTrue();          // closed projection

    <T> List<T> findByLastName(String lastName, Class<T> type);  // dynamic projection
}
```

```java
List<CustomerSummary> summaries = repo.findByActiveTrue();  // SELECT id,email,last_name
List<CustomerSummary> s2 = repo.findByLastName("Smith", CustomerSummary.class);
List<Customer> full     = repo.findByLastName("Smith", Customer.class); // same method!
```

> **Closed vs open:** a *closed* projection (only getters matching properties) lets Spring/Hibernate select just those columns. An *open* projection (`@Value` SpEL) must load the whole entity, so it's no faster than loading the entity. Prefer closed projections (or DTO `@Query` constructor expressions) for performance.

### 5.8 Use case: solving N+1 with `@EntityGraph` and fetch joins

```java
@Entity
public class Author {
    @Id @GeneratedValue private Long id;
    private String name;
    @OneToMany(mappedBy = "author")          // LAZY by default
    private List<Book> books = new ArrayList<>();
}

public interface AuthorRepository extends JpaRepository<Author, Long> {

    // Without this, iterating authors then their books = 1 + N queries.
    @EntityGraph(attributePaths = "books")   // single query, LEFT JOIN FETCH books
    List<Author> findAll();

    // Equivalent via explicit fetch join in JPQL.
    @Query("select distinct a from Author a left join fetch a.books")
    List<Author> findAllWithBooks();
}
```

### 5.9 Use case: auditing (created/modified timestamps and user)

```java
@Configuration
@EnableJpaAuditing
class AuditConfig {
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext())
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName);
    }
}

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {
    @CreatedDate     private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;
    @CreatedBy       private String createdBy;
    @LastModifiedBy  private String updatedBy;
    // getters
}

@Entity
public class Invoice extends Auditable {
    @Id @GeneratedValue private Long id;
    private BigDecimal amount;
}
```

Now every `Invoice` insert/update auto-populates the four audit fields — no manual code.

### 5.10 Use case: custom repository fragment (escape hatch)

When you need imperative logic the abstraction can't express:

```java
// 1. Fragment interface
public interface CustomerRepositoryCustom {
    List<Customer> complexSearch(SearchCriteria criteria);
}

// 2. Implementation — naming convention: <Fragment>Impl
public class CustomerRepositoryCustomImpl implements CustomerRepositoryCustom {
    @PersistenceContext private EntityManager em;

    @Override
    public List<Customer> complexSearch(SearchCriteria c) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Customer> q = cb.createQuery(Customer.class);
        Root<Customer> root = q.from(Customer.class);
        // ... build predicates imperatively ...
        return em.createQuery(q).getResultList();
    }
}

// 3. Compose into the main repository
public interface CustomerRepository
        extends JpaRepository<Customer, Long>, CustomerRepositoryCustom {}
```

Spring stitches the fragment into the proxy; `complexSearch` routes to your impl while everything else stays generated.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Batch writes.** Set `hibernate.jdbc.batch_size=50` (start there; tune 20–100), plus `order_inserts=true` and `order_updates=true`. Without batching, `saveAll(1000)` is 1000 round trips. Caveat: `GenerationType.IDENTITY` **disables insert batching** in Hibernate (it must read each generated key immediately) — use `SEQUENCE` with a pooled optimizer for batchable inserts.
- **Read-only transactions.** Mark read paths `@Transactional(readOnly = true)` to skip dirty-check flushes and let the driver optimize.
- **Projections / DTO queries** beat loading entities when you only need a few fields — less data over the wire, no PC bloat, no lazy traps.
- **`default_batch_fetch_size`** (e.g. 100) makes Hibernate load lazy associations for many parents in one `IN (...)` query instead of N — a low-effort N+1 mitigation.
- **Avoid `findAll()` on large tables.** Always page or stream.
- **Count-query cost.** Each `Page` does an extra `count(*)`. For huge tables, prefer `Slice` or keyset pagination; or supply an optimized `countQuery`.
- **Cache the `EntityManagerFactory` / connection pool tuning** lives in HikariCP (`maximum-pool-size`, default 10). Right-size to DB capacity.

### 6.2 Correctness & concurrency

- **Optimistic locking:** add `@Version` to mutable entities accessed concurrently; handle `OptimisticLockingFailureException` (Spring's wrapper) with a retry or user-facing conflict.
- **Pessimistic locking** via `@Lock(LockModeType.PESSIMISTIC_WRITE)` for "read-modify-write" on hot rows where conflicts are frequent.
- **Always use `save()`'s return value** (merge returns a new managed instance).
- **`@Modifying` + clear** to avoid stale PC after bulk ops.
- **Transaction boundaries belong in the service layer**, not the controller or repository. One business operation = one transaction.

### 6.3 Security

- **SQL injection:** derived queries, `@Query` with bound params, Specifications, and Querydsl are all parameterized — safe. The danger is **native `@Query` with string concatenation** or `Sort`/dynamic-column names interpolated into SQL. Never concatenate user input into a native query. Validate sort properties against an allowlist (Spring's `Sort` references entity properties, which is safer, but native paging by arbitrary column names is risky).
- **Mass-assignment:** don't bind HTTP request bodies directly onto entities; map through DTOs so clients can't set fields like `role` or `id`.
- **Information leakage:** projections / DTOs prevent over-fetching sensitive columns into responses.

### 6.4 Observability

- Log SQL with `logging.level.org.hibernate.SQL=DEBUG` and bound params with the bind logger (§4.5). **Never leave bind-parameter logging on in production** (PII + volume).
- Enable `hibernate.generate_statistics=true` temporarily to count queries per request — the fastest way to catch N+1.
- Use **datasource-proxy** or **p6spy** to log real SQL with inlined params and timings; **FlexyPool** for connection-pool metrics.
- Expose Hikari and Hibernate metrics via Micrometer/Actuator (`hikaricp.connections.active`, query timers).
- A great CI guard: assert query counts in integration tests (libraries like `db-util`'s `@AssertSelectCount` or Hibernate statistics) so N+1 regressions fail the build.

### 6.5 Cost

- Fewer round trips = lower latency and lower cloud DB cost (read units, connection minutes). Batching and projections directly reduce spend.
- The `count(*)` per page can be expensive on large tables; cache or approximate counts where exactness isn't required.
- OSIV (§9.2) keeps connections checked out longer, reducing effective pool capacity and raising the connection count you must provision.

### 6.6 Testing

- **`@DataJpaTest`** boots only the JPA slice (repositories + an embedded/Testcontainers DB), rolls back each test by default, and configures an in-memory DB unless told otherwise.
- Prefer **Testcontainers** with the *real* database engine over H2 — H2 differs from Postgres/MySQL in SQL dialect, types, and constraint behavior, so H2-green tests can hide prod bugs.
- Use `TestEntityManager` to set up state and `flush()`/`clear()` to force real DB reads (defeating the L1 cache) so your assertions test SQL, not the cache.
- Assert query counts to catch N+1 (§6.4).

### 6.7 Production hardening

- `spring.jpa.hibernate.ddl-auto=validate` (or `none`) in prod; manage schema with **Flyway** or **Liquibase**.
- Set `hibernate.jdbc.time_zone=UTC` to avoid timestamp drift.
- Consider `spring.jpa.open-in-view=false` (it logs a warning when left at the default `true`) and fetch everything you need inside the transaction.
- Configure statement timeouts (`jakarta.persistence.query.timeout` hint or DB-level) so a runaway query doesn't pin a connection forever.
- Right-size Hikari pool; set `connection-timeout`, `max-lifetime` below the DB's idle timeout.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it hurts | Fix |
|---|---|---|
| Relying on OSIV for lazy loading in views | Hidden N+1, long-held connections | OSIV off + fetch joins / `@EntityGraph` / DTOs |
| `findAll()` then filter in Java | Loads whole table | Derived/`@Query` filter in SQL |
| Ignoring `save()` return value | Operate on detached instance | `x = repo.save(x)` |
| `IDENTITY` ID + expecting batch inserts | Batching silently disabled | `SEQUENCE` + pooled optimizer |
| Bulk `@Modifying` without `clearAutomatically` | Stale PC reads | clear after |
| Derived-method explosion (`findByAAndBAndC…`) | Unreadable, brittle | Specifications / Querydsl / `@Query` |
| `EAGER` everywhere | Loads the world per query | Default LAZY + explicit fetch |
| Entities as API DTOs | Lazy serialization errors, over-fetch, coupling | Map to DTOs |
| `ddl-auto=update` in prod | Silent, unreviewed schema changes | Flyway/Liquibase + `validate` |

---

## 7. Advanced topics & deep internals

### 7.1 `isNew` detection and assigned identifiers

`SimpleJpaRepository.save` branches on `entityInformation.isNew(entity)`. The default `JpaMetamodelEntityInformation.isNew` returns true when the `@Id` is null (for object types) or zero (for primitive numeric IDs — a subtle trap with `long id`). With **assigned IDs** (UUIDs, natural keys), the ID is never null, so `save` always does `merge`, which issues a `SELECT` before the `INSERT`. Remedies:
- Implement `Persistable<ID>` and provide `isNew()` backed by a `@Transient` boolean set in a `@PrePersist`/`@PostLoad` listener.
- Or rely on `@Version`: a null version is treated as new (`isNew` checks the version field if present).
- Or use `persist` directly via a custom fragment when you know the entity is new.

### 7.2 Flush modes and write ordering

Hibernate's `FlushMode`: `AUTO` (default — flush before queries that might be affected, and at commit), `COMMIT` (flush only at commit), `MANUAL`/`NEVER` (you flush explicitly; set by `readOnly=true`). Within a flush, Hibernate orders operations: inserts (in dependency order), updates, collection element removals, collection inserts, then deletes — to satisfy FK constraints. Understanding this explains why your `INSERT` and `DELETE` may appear in a different order than your code.

### 7.3 Second-level cache (L2) and query cache

The PC (L1) lives for one transaction. The **second-level cache (L2)** is shared across transactions/sessions, configured per entity with `@Cache` and a provider (Ehcache, Infinispan, Hazelcast, Caffeine via JCache). It caches entity state by ID. The **query cache** caches query result *IDs* for a given query+params (must be enabled separately and almost always needs L2 too). Caveats: L2 can serve stale data if other apps write to the DB; invalidation is by entity; query cache is easy to misuse. Use L2 for read-mostly reference data, not hot mutable tables.

### 7.4 Custom base repository (changing behavior for all repos)

You can replace `SimpleJpaRepository` globally:

```java
public class BaseRepositoryImpl<T, ID> extends SimpleJpaRepository<T, ID> {
    private final EntityManager em;
    public BaseRepositoryImpl(JpaEntityInformation<T, ?> info, EntityManager em) {
        super(info, em); this.em = em;
    }
    // override e.g. save() to add tenant stamping, soft-delete, etc.
}

@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
@Configuration
class JpaConfig {}
```

Common uses: soft delete (override `delete` to set a flag), multi-tenancy stamping, default `@EntityGraph`.

### 7.5 Streaming, `Window`, and large result sets

`Stream<T>` query methods (with `@QueryHints` `HINT_FETCH_SIZE` and a forward-only cursor) let you process millions of rows without loading all into memory — but the stream must be closed and used inside the transaction, and you should `em.clear()` periodically to avoid PC growth. `Window<T>` + `ScrollPosition` (3.1+) provide cursor/keyset scrolling as a first-class API.

### 7.6 Multiple datasources & transaction managers

With more than one datasource you define separate `EntityManagerFactory`, `DataSource`, and `JpaTransactionManager` beans, scope repositories to packages via `@EnableJpaRepositories(basePackages=..., entityManagerFactoryRef=..., transactionManagerRef=...)`, and name the transaction manager in `@Transactional("txManagerB")`. Cross-datasource atomicity needs JTA/XA (rarely worth it; prefer the outbox pattern).

### 7.7 Named queries, stored procedures, and `@Query` precedence

Resolution order for a query method: explicit `@Query` > named query (`@NamedQuery`/orm.xml named `Entity.method`) > derived from method name. `@Procedure` maps to a DB stored procedure; in/out params bind by position or `@Param`.

### 7.8 Projections internals

For a **closed interface projection**, Spring builds a JPQL constructor-style selection of only the needed properties and returns a `Map`-backed proxy (or a real DTO via Spring's `SpelAwareProxyProjectionFactory`). For **DTO class projections** (a target class with a matching constructor, since Spring Data 1.10), Spring also limits the select list. **Dynamic projections** (`<T> List<T> find...(Class<T>)`) let the caller choose the shape per call.

### 7.9 `@DynamicUpdate` / `@DynamicInsert`

By default Hibernate generates *static* `UPDATE` SQL listing all columns (cached, prepared once). `@DynamicUpdate` regenerates SQL each time listing only dirty columns — useful for very wide tables or to avoid clobbering columns, at the cost of statement-cache reuse. `@DynamicInsert` omits null columns from `INSERT`.

### 7.10 Lesser-known behaviors

- **`getReferenceById`** returns a proxy; calling a getter triggers a `SELECT` (or `EntityNotFoundException` if the row is gone). Great for setting an FK without loading the parent.
- **`deleteAllInBatch`** ignores `@SQLDelete`/soft-delete and cascades — it's a raw `DELETE`.
- **Derived `delete…By`** loads matching entities and removes them one by one (callbacks fire) unless you use `@Modifying @Query`.
- **`countQuery` inference** for paged `@Query` JPQL is automatic but can be wrong for complex queries; supply it explicitly.
- **`@Query` with `SpEL`** (`?#{...}`, `:#{#entityName}`) enables generic base queries and SpEL-driven parameters.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Query mechanism: which to use

| Mechanism | Type-safe | Dynamic | Best for | Avoid when |
|---|---|---|---|---|
| Derived methods | No (string name) | No | Simple, fixed finders | >2-3 predicates; combinatorial filters |
| `@Query` JPQL | Partly (validated at start) | No | Complex but fixed queries, joins, DTOs | Filters vary per request |
| `@Query` native | No | No | DB-specific SQL, perf-critical | Portability matters |
| Specifications (Criteria) | Yes | Yes | Dynamic filters, composable | Very complex joins (verbose) |
| Querydsl | Yes (generated Q-types) | Yes | Type-safe dynamic + complex | You can't add a build step |
| Query by Example | Yes | Limited | Simple probe matching | Ranges/OR/complex predicates |

### 8.2 Spring Data JPA vs alternatives

| Tool | Abstraction | Strengths | Weaknesses | Use when |
|---|---|---|---|---|
| **Spring Data JPA / Hibernate** | High (ORM) | Productivity, dirty checking, caching, portability | Hidden SQL, N+1 traps, learning curve | CRUD-heavy domain apps |
| **Spring `JdbcClient`/`JdbcTemplate`** | Low | Full SQL control, predictable, light | Manual mapping, no dirty checking | Simple/perf-critical queries |
| **jOOQ** | Mid (typed SQL DSL) | Type-safe SQL, exact control, great for reporting | Commercial for some DBs, build step | SQL-centric, complex queries |
| **MyBatis** | Mid (SQL in XML/annotations) | SQL visible & tunable, mapping flexible | More boilerplate than JPA | Teams that want SQL-first |
| **Spring Data R2DBC** | Mid (reactive) | Non-blocking, backpressure | No JPA features (no dirty checking, no lazy) | Reactive stacks |

Pragmatic pattern: **use Spring Data JPA for writes and the domain model, drop to jOOQ/JdbcClient for heavy read/reporting queries** in the same app.

### 8.3 Pagination strategy

| Strategy | Cost | Stable under writes | Deep pages | Use when |
|---|---|---|---|---|
| Offset (`Page`) | data + count query, O(offset) scan | No (rows shift) | Slow | Small datasets, need total count |
| `Slice` | data only | No | Slow | "Load more" without total |
| Keyset/`Window` | data only, O(page) | Yes | Fast | Infinite scroll, deep/large data |

### 8.4 ID generation strategy

| Strategy | Batchable inserts | Round trips | Notes |
|---|---|---|---|
| `IDENTITY` | **No** | 1 (key returned with insert) | Simple; kills batching |
| `SEQUENCE` (+ pooled optimizer) | Yes | pre-fetch block of IDs | Best for high-volume inserts (Postgres/Oracle) |
| `TABLE` | Yes-ish | extra row locking | Portable but contended; avoid |
| `UUID`/assigned | Yes | 0 | Fix `isNew` (see §7.1) |

### 8.5 OSIV: on or off

- **On (default):** lazy loading "just works" in controllers/views; risk of hidden N+1 and long-held connections.
- **Off:** forces you to fetch what you need inside the service transaction (fetch joins, `@EntityGraph`, DTOs); cleaner, more predictable, better connection utilization. **Recommended for new services**, with the discipline to assemble DTOs in the transactional layer.

---

## 9. Failure modes & debugging

### 9.1 `LazyInitializationException`

**Symptom:** `failed to lazily initialize a collection ... no session` when serializing/accessing a lazy field outside a transaction. **Cause:** the PC closed (transaction ended) before the lazy association was touched. **Diagnose:** find where the access happens relative to the `@Transactional` boundary; check if OSIV is masking it in tests but failing in async/scheduled code. **Fix:** fetch eagerly for that use case via `@EntityGraph`/fetch join, map to a DTO inside the transaction, or (last resort) keep the access inside the transactional service method.

### 9.2 N+1 selects

**Symptom:** one query to load N parents, then N more to load each parent's lazy association — query count scales with data. Often invisible until production load. **Diagnose:** enable `hibernate.generate_statistics=true` or `org.hibernate.SQL=DEBUG`; count queries per request; assert counts in tests. **Fix:** `@EntityGraph(attributePaths=...)`, JPQL `join fetch`, `default_batch_fetch_size`, or projections/DTOs that select exactly what's needed. With OSIV on, N+1 frequently hides in the view/serialization layer.

### 9.3 `MultipleBagFetchException` / cartesian products

**Symptom:** `cannot simultaneously fetch multiple bags` when fetch-joining two `List` (bag) collections. **Cause:** Hibernate can't dedupe two unordered collections in one join. **Fix:** use `Set` instead of `List`, or fetch one collection per query (and let `default_batch_fetch_size` handle the rest), or use `@EntityGraph` with multiple separate queries.

### 9.4 `OptimisticLockingFailureException`

**Symptom:** concurrent update fails because the `@Version` no longer matches. **Diagnose:** expected under contention; check for long-lived detached entities being merged stale. **Fix:** retry the read-modify-write, or surface a conflict to the user; consider pessimistic locking for hot rows.

### 9.5 Stale reads after bulk `@Modifying`

**Symptom:** after a bulk update, entities already in the PC still show old values. **Cause:** bulk SQL bypasses the PC. **Fix:** `@Modifying(clearAutomatically=true)` or `em.clear()`.

### 9.6 Unexpected `SELECT` before `INSERT`

**Symptom:** `save()` on a new entity with an assigned ID issues a `SELECT`. **Cause:** `isNew` returns false → `merge`. **Fix:** §7.1 (Persistable / version / explicit persist).

### 9.7 Paging returns wrong totals or duplicates with fetch joins

**Symptom:** `Page` with a `join fetch` collection returns wrong counts or duplicate parents; Hibernate may even log "firstResult/maxResults specified with collection fetch; applying in memory" — meaning it paginated **in memory** after loading everything (a severe memory/perf risk). **Fix:** don't fetch collections in a paged query; page on the root, then fetch associations with a second query or `@EntityGraph` on IDs.

### 9.8 Connection-pool exhaustion

**Symptom:** `Connection is not available, request timed out after ...` (Hikari). **Causes:** OSIV holding connections through long view rendering; long transactions; leaks. **Diagnose:** Hikari metrics (`active`/`pending`), enable `leakDetectionThreshold`. **Fix:** OSIV off, shorter transactions, right-size pool, statement timeouts.

### 9.9 Real-world incident sketches

- *The OSIV outage:* a JSON endpoint serialized an entity graph; with OSIV on, each list element triggered a lazy query; under traffic, the pool drained and the service timed out. Fix: DTO projection + OSIV off; query count dropped from ~500 to 2.
- *The batch that wasn't:* a nightly job saving 200k rows took hours because `GenerationType.IDENTITY` disabled batching. Switching to `SEQUENCE` with a pooled optimizer and `batch_size=50` cut it to minutes.
- *The H2 lie:* tests passed on H2 but prod (Postgres) rejected an `INSERT` due to a stricter constraint and a `timestamptz` mismatch. Fix: Testcontainers with real Postgres in CI.

### 9.10 Tooling for diagnosis

| Tool | What it shows |
|---|---|
| `org.hibernate.SQL=DEBUG` + bind logger | Executed SQL + params |
| `hibernate.generate_statistics=true` | Query/cache counts per session |
| p6spy / datasource-proxy | Real SQL with inlined params + timing |
| Hikari metrics (Actuator/Micrometer) | Pool active/idle/pending, leaks |
| DB `EXPLAIN`/`EXPLAIN ANALYZE` | Query plan, index usage |
| `db-util` `@AssertSelectCount` | Fails tests on N+1 |

---

## 10. Interview drill

**Q1. Explain the abstraction stack: Spring Data JPA → JPA → Hibernate → JDBC.**
*Model answer:* JDBC is the raw SQL/`ResultSet` API. Hibernate is an ORM engine that generates SQL and maps rows to objects. JPA is the *specification* (interfaces/annotations) Hibernate implements. Spring Data JPA sits on top of JPA, generating repository proxies so you don't hand-write DAOs; it produces no SQL itself.
- *Probe: Who actually generates the SQL?* Hibernate, from JPQL/Criteria/entity operations.
- *Probe: Can you swap Hibernate for EclipseLink?* Yes in principle (both are JPA providers), but provider-specific config and behavior differ; rarely done.
- *Probe: Where does dirty checking live?* In Hibernate's persistence context, not Spring Data.

**Q2. How does a derived query method like `findByLastNameAndAgeGreaterThan` become SQL?**
*Model answer:* At startup the method name is parsed into a `PartTree` — prefix stripped, the part after `By` split on `And`/`Or`, property paths resolved against the entity, keywords mapped to operators — producing JPQL that Hibernate compiles to dialect SQL. Parsing happens once and is cached.
- *Probe: When do parse errors surface?* At startup (fail-fast), unless `bootstrap-mode=lazy`.
- *Probe: How to traverse a nested property?* `findByAddress_ZipCode` (underscore to disambiguate).

**Q3. What is the persistence context and why does it matter?**
*Model answer:* It's the per-transaction set of managed entities — an L1 cache guaranteeing object identity, and a change tracker enabling dirty checking. It defines when SQL flushes and when lazy loading can occur.
- *Probe: L1 vs L2?* L1 is per-PC/transaction; L2 is shared across sessions, opt-in per entity.
- *Probe: Entity lifecycle states?* transient, managed, detached, removed.

**Q4. What is the N+1 problem and how do you fix it in Spring Data JPA?**
*Model answer:* Loading N parents then one query per parent's lazy association = 1+N queries. Fixes: `@EntityGraph`, JPQL `join fetch`, `default_batch_fetch_size`, or projecting to DTOs.
- *Probe: How does OSIV worsen it?* Lazy loads fire during view/serialization, hidden from the service layer.
- *Probe: Why not fetch-join a collection in a paged query?* Hibernate paginates in memory → memory blow-up and wrong counts.

**Q5. `save()` — persist or merge, and why does it matter?**
*Model answer:* `SimpleJpaRepository.save` calls `persist` if the entity is "new" (`isNew`), else `merge`. `merge` may `SELECT` then `UPDATE` and returns a *new managed instance*, so you must use the return value.
- *Probe: Assigned IDs break this how?* `isNew` sees a non-null ID → always merge → extra SELECT; fix via `Persistable`/`@Version`.
- *Probe: Does `save` batch?* No; `saveAll` loops `save`; batching needs Hibernate config and a non-IDENTITY ID strategy.

**Q6. How do `@Modifying` queries behave?**
*Model answer:* They run a single UPDATE/DELETE/INSERT as raw SQL, require an active transaction, return affected-row count, and **bypass the PC** — so use `clearAutomatically`/`flushAutomatically` to avoid stale or lost state.
- *Probe: Why clear?* Loaded entities won't reflect the bulk change.
- *Probe: Difference from `deleteById`?* `deleteById` loads then removes (callbacks/cascades fire); bulk delete doesn't.

**Q7. Specifications vs Querydsl vs derived methods — when each?** *(senior-signal)*
*Model answer:* Derived for a few fixed predicates; `@Query` for fixed complex queries; Specifications for dynamic, composable filters with no extra build step (verbose for big joins); Querydsl for type-safe dynamic queries when you can add code generation. Pick by dynamism, type-safety needs, and complexity.
- *Probe: Injection risk?* All parameterized except native string-concatenation.
- *Probe: How does Specification avoid SQL strings?* It builds JPA Criteria `Predicate`s typed against the entity model.

**Q8. Should OSIV be on or off in production? Justify.** *(senior-signal)*
*Model answer:* Off, generally. On is convenient (lazy loading in views) but causes hidden N+1 and holds DB connections through view rendering, hurting pool capacity and tail latency. Off forces disciplined fetching (fetch joins/`@EntityGraph`/DTOs in the transactional layer), giving predictable query counts and connection use.
- *Probe: What breaks when you turn it off?* Lazy access in controllers/serialization → fix by fetching in the service.
- *Probe: How to detect OSIV-driven N+1?* Query-count assertions + Hibernate statistics.

**Q9. How would you make bulk inserts of 1M rows fast?** *(senior-signal)*
*Model answer:* Use `SEQUENCE` ID generation with a pooled optimizer (IDENTITY disables batching), set `hibernate.jdbc.batch_size`, `order_inserts/updates=true`, flush+clear the PC every batch_size rows to bound memory, run in a single transaction (or chunked transactions), and consider dropping to `JdbcTemplate`/`COPY`/native bulk load for extreme volumes.
- *Probe: Why flush+clear periodically?* To stop the PC growing unbounded (memory) and slowing dirty checks.
- *Probe: When abandon JPA entirely here?* For pure ETL, native bulk-load (`COPY`/`LOAD DATA`) beats ORM by orders of magnitude.

**Q10. How are repository implementations created and wired?**
*Model answer:* At startup Spring scans repository interfaces, registers a `JpaRepositoryFactoryBean` per interface, analyzes metadata and pre-parses queries, then creates a JDK dynamic proxy backed by an interceptor chain that routes calls to `SimpleJpaRepository` (CRUD), a `RepositoryQuery` (derived/`@Query`), or custom fragments — wrapped with transaction and exception-translation advice.
- *Probe: What's `SimpleJpaRepository`?* The shared base implementation of all CRUD methods over the `EntityManager`.
- *Probe: How to customize all repos?* `@EnableJpaRepositories(repositoryBaseClass=...)`.

**Q11. How does pagination work and what does it cost?**
*Model answer:* `findAll(Pageable)` adds `ORDER BY`+`LIMIT/OFFSET` and issues a second `count(*)` for the total, returning a `Page`. `Slice` skips the count (fetches one extra row to know `hasNext`). Keyset/`Window` uses `WHERE key > last` for O(page) deep pagination.
- *Probe: Why is offset slow at depth?* The DB scans and discards `offset` rows.
- *Probe: When is `count` wrong?* Paged `@Query` with complex SQL — supply `countQuery`.

**Q12. What's the difference between `@Transactional(readOnly=true)` and default?** *(senior-signal-ish)*
*Model answer:* `readOnly=true` sets flush mode to manual/never (skipping dirty-check flushes) and hints the connection/driver it's read-only, improving read performance and preventing accidental writes. Default is read-write with `AUTO` flush.
- *Probe: Does readOnly prevent writes?* It doesn't hard-block them at the JPA level in all setups, but skips flush, so changes typically aren't persisted; some drivers/DBs enforce it.
- *Probe: Where should transactions be declared?* Service layer, one per business operation.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; the guarantees of a transaction.
- **AuditorAware** — Spring interface returning the "current user" for `@CreatedBy`/`@LastModifiedBy`.
- **Bag** — Hibernate term for an unordered `List` collection (no index column); two can't be fetch-joined together.
- **Bootstrap mode** — When repositories initialize: `default`, `deferred`, `lazy`.
- **Criteria API** — JPA's programmatic, type-checked query-building API (used by Specifications).
- **CrudRepository** — Base Spring Data interface providing CRUD methods.
- **Detached** — Entity state: was managed, PC closed/evicted; changes no longer tracked.
- **Derived query** — A query generated by parsing a repository method's name.
- **Dirty checking** — Hibernate comparing managed entities to their loaded snapshot to emit minimal UPDATEs.
- **DTO** — Data Transfer Object; a plain class for moving/shaping data, decoupled from entities.
- **EntityGraph** — A declarative fetch plan overriding lazy/eager for a query (avoids N+1).
- **EntityManager** — Core JPA interface for persistence operations; manages the PC.
- **EntityManagerFactory** — Thread-safe factory producing `EntityManager`s (one per persistence unit).
- **Entity** — `@Entity`-annotated class mapped to a table; instance = row.
- **Eager/Lazy loading** — Whether an association loads immediately or on first access.
- **First-level cache (L1)** — The persistence context's per-transaction object cache.
- **Flush** — Synchronizing the PC's pending changes to the DB (emitting SQL).
- **FlushMode** — `AUTO`/`COMMIT`/`MANUAL` policy for when flushing happens.
- **Hibernate** — The dominant JPA-implementing ORM engine on the JVM.
- **HikariCP** — Default Spring Boot JDBC connection pool.
- **Impedance mismatch** — The structural gap between object and relational models that ORM bridges.
- **JDBC** — Java's low-level SQL/`ResultSet` database API.
- **JDK dynamic proxy** — A runtime-generated class implementing an interface, routing calls to one handler.
- **JPA** — Jakarta/Java Persistence API; the ORM *specification*.
- **JPQL** — JPA's object query language (queries entities/fields, not tables/columns).
- **JpaRepository** — Spring Data's JPA-specific repository with batch/flush extras.
- **Keyset (seek) pagination** — Paging via `WHERE key > lastSeen` instead of OFFSET; O(page) at any depth.
- **L2 cache** — Shared second-level cache across sessions; opt-in per entity.
- **LazyInitializationException** — Thrown when a lazy association is touched after the PC closed.
- **Managed/persistent** — Entity state: tracked by an open PC.
- **Merge** — Copy a detached entity's state into a managed instance (returns the managed one).
- **N+1** — One query for parents plus one per parent's association; scales with data.
- **ORM** — Object-Relational Mapping.
- **OSIV (Open Session In View)** — Keeps the PC open through web view rendering; default on in Spring Boot.
- **Optimistic locking** — Conflict detection via a `@Version` column at write time.
- **PagingAndSortingRepository** — Spring Data interface adding paged/sorted finders.
- **Persistable** — Interface letting an entity declare its own `isNew()`.
- **Persistence context (PC)** — The managed-entity set/L1 cache for a unit of work.
- **Persistence provider** — A JPA implementation (Hibernate, EclipseLink, OpenJPA).
- **Pessimistic locking** — DB-level locks (`FOR UPDATE`) taken at read time.
- **PartTree** — Spring Data's parsed representation of a derived method name.
- **Projection** — Returning a subset/shape of data (interface or DTO) instead of the full entity.
- **Proxy (repository)** — The generated object implementing your repository interface.
- **Querydsl** — A library generating type-safe query metamodels (`QEntity`).
- **Query by Example** — Querying by a populated "probe" entity instance.
- **ResultSet** — JDBC's tabular query result.
- **Slice** — A page without a total count (one query + a peek for `hasNext`).
- **SimpleJpaRepository** — Spring Data's default base CRUD implementation.
- **Specification** — A composable wrapper over a Criteria `Predicate` for dynamic queries.
- **Transient (JPA)** — Entity state: new object not yet associated with a PC. (Distinct from the `transient` Java keyword.)
- **Transaction** — An ACID unit of work.
- **TypedQuery** — A JPA query with a known result type.
- **Window** — Spring Data's keyset-scrolling result type (3.1+).
- **`@Version`** — Field enabling optimistic locking.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Stack:** Spring Data JPA (proxies, no SQL) → JPA (spec/`EntityManager`) → Hibernate (ORM/SQL gen, PC, dirty checking) → JDBC → DB.
**Repos:** extend `JpaRepository<T,ID>`; add `JpaSpecificationExecutor`/`QuerydslPredicateExecutor` for dynamic queries.
**Queries:** derived (name → JPQL, parsed once at startup) | `@Query` JPQL | `@Query(nativeQuery=true)` | Specifications (Criteria) | Querydsl.
**`save`:** persist if `isNew` else merge — **use the return value**; assigned IDs trigger extra SELECT (fix via `Persistable`/`@Version`).
**Paging:** `Page` = data + `count(*)`; `Slice` = data only; keyset/`Window` = O(page), use for deep/infinite scroll.
**`@Modifying`:** bulk SQL, needs tx, bypasses PC → `clearAutomatically`/`flushAutomatically`.
**Defaults to remember:** `@ManyToOne/@OneToOne` EAGER, `@OneToMany/@ManyToMany` LAZY; OSIV **on** (`spring.jpa.open-in-view=true`); `ddl-auto=none` (prod: `validate`); Hikari pool size **10**; batching **off** until `hibernate.jdbc.batch_size` set; `IDENTITY` **disables** insert batching.
**N+1 fixes:** `@EntityGraph` | `join fetch` | `default_batch_fetch_size` | DTO projections; never fetch a collection in a paged query.
**Locking:** `@Version` (optimistic) | `@Lock(PESSIMISTIC_WRITE)`.
**Read paths:** `@Transactional(readOnly=true)` to skip flushes.
**Observability:** `org.hibernate.SQL=DEBUG`, `generate_statistics=true`, p6spy, assert query counts in tests.
**Anti-patterns:** OSIV-driven lazy in views, `findAll()` on big tables, entities as DTOs, ignoring `save` return, `ddl-auto=update` in prod.
**Decision rules:** fixed simple → derived; fixed complex → `@Query`; dynamic → Specifications/Querydsl; perf-critical/reporting → drop to jOOQ/JdbcClient; deep pages → keyset.

### Self-test (no answers — recall practice)

1. Trace, layer by layer, what happens when you call `userRepository.findByEmail("a@b.com")` from a non-transactional controller method — where exactly does the persistence context open and close, and what could throw if the result has a lazy collection you serialize?
2. You add an assigned UUID primary key and notice every `save()` of a brand-new entity emits a `SELECT` before the `INSERT`. Explain why, and give two distinct fixes.
3. A paged endpoint with `@EntityGraph(attributePaths="lineItems")` returns the wrong `totalElements` and logs an in-memory pagination warning. Explain the cause and redesign the queries to fix both correctness and memory risk.
4. Compare Specifications and Querydsl for a 6-field optional search form: type-safety, build setup, readability, and SQL-injection exposure.
5. Your nightly job inserting 500k entities is slow. List every configuration and design change you'd make, in priority order, and name the one default that silently prevents JDBC batching.
6. When would you turn OSIV off, what breaks when you do, and how do you restructure code to compensate? How would you prove the change reduced query counts?
7. Explain the difference in behavior, SQL, and PC effects between `deleteById(id)`, `delete(entity)`, `deleteAllInBatch()`, and a `@Modifying @Query("delete ...")`.

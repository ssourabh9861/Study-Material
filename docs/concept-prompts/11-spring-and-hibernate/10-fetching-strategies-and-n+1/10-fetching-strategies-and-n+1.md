# Fetching Strategies & the N+1 Problem

> An exhaustive engineering-handbook chapter for senior JVM backend developers. Covers lazy vs eager fetching, proxies, `LazyInitializationException`, the N+1 select problem, and every practical fix (`JOIN FETCH`, `@EntityGraph`, batch fetching, subselect, DTO projections), plus pagination pitfalls, open-session-in-view, detection tooling, internals, and interview prep.

---

## 1. Overview & where it fits

### What it is

A **fetching strategy** is the policy by which an Object-Relational Mapping (ORM) framework — here **Hibernate**, the de-facto JPA provider used by Spring Data JPA — decides *when* and *how* to load the data that sits behind an entity's associations (the `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany` relationships) and, more broadly, the columns of an entity.

> **ORM (Object-Relational Mapping):** a layer that maps rows in relational tables to objects in your program and back, so you write `order.getCustomer().getName()` instead of hand-writing SQL joins and copying `ResultSet` columns into fields. Hibernate is the most popular Java ORM.
>
> **JPA (Jakarta Persistence API, formerly Java Persistence API):** the *standard* (a specification — a set of interfaces and annotations like `@Entity`, `EntityManager`) that ORMs implement. Hibernate is one *implementation* of JPA. "JPA" = the contract; "Hibernate" = a concrete engine behind it.
>
> **Spring Data JPA:** a Spring layer on top of JPA/Hibernate that generates repository implementations for you (e.g. you declare `interface OrderRepository extends JpaRepository<Order, Long>` and get `findAll`, `save`, derived queries like `findByCustomerId`, etc., for free).

The **N+1 select problem** is the single most common and most damaging performance bug that fetching strategies produce. It is the situation where loading a list of *N* parent rows triggers *1* query for the parents plus *N* additional queries (one per parent) to load each parent's children — `N + 1` round trips to the database where 1 or 2 would have sufficed.

### The problem it solves / creates

ORMs hide SQL. That is the value proposition and the trap. Because association traversal (`a.getB().getC()`) looks like ordinary in-memory object navigation, it is **invisible** that each `.get*()` might be firing a SQL statement against the database over the network. Fetching strategy is the set of knobs that control whether that traversal is:

- **cheap** (data already in memory, or fetched in one efficient join), or
- **catastrophic** (a query per element of a collection, repeated across a request, multiplied by concurrency).

### When you reach for this knowledge

- You see a request that should be one query taking hundreds of milliseconds and the DB shows hundreds of nearly identical `SELECT ... WHERE id = ?` statements.
- You hit `org.hibernate.LazyInitializationException: could not initialize proxy - no Session`.
- You need to design an API/repository layer that reads predictably under load.
- Pagination returns the wrong number of rows or logs `HHH000104: firstResult/maxResults specified with collection fetch; applying in memory`.
- Any JPA/Hibernate interview — this is the highest-probability performance topic asked.

### One-paragraph mental model

Every entity association has a **fetch type** — `LAZY` (load it later, on first access, via a proxy/placeholder) or `EAGER` (load it now, alongside the parent). Hibernate defaults `@ManyToOne` and `@OneToOne` to **EAGER**, and `@OneToMany`/`@ManyToMany` to **LAZY**. Lazy associations are backed by **proxies** — stand-in objects that look like the real thing but trigger a database load when first touched, *provided a persistence Session is still open*. The N+1 problem arises when you iterate a collection of parents and touch a lazy (or per-row eager) association inside the loop, producing one query per parent. You fix it by telling Hibernate to fetch the association set *together* with the parents — via `JOIN FETCH`, `@EntityGraph`, batch fetching, subselect fetching, or by bypassing entities entirely with **DTO projections**. The art is choosing the right tool per query, keeping associations `LAZY` by default, and never relying on the **open-session-in-view** crutch that papers over the design by keeping the Session open through view rendering.

---

## 2. Foundations from first principles

We build the vocabulary from zero. If you already know JPA mechanics, skim — but the later sections assume these terms.

### 2.1 Entities, the persistence context, and the Session

> **Entity:** a Java class annotated `@Entity` whose instances correspond to rows in a table. It has an `@Id` (primary key) and fields mapped to columns.

> **`EntityManager` (JPA) / `Session` (Hibernate):** the primary API for persistence operations. `EntityManager` is the JPA standard interface; `Session` is Hibernate's richer native interface (you can unwrap one from the other: `entityManager.unwrap(Session.class)`). Both represent a *unit of work* with the database. Throughout this doc, "Session" and "persistence context" are used near-synonymously; precisely, the Session *owns* a persistence context.

> **Persistence context (a.k.a. the first-level cache / L1 cache):** an in-memory map, scoped to a single Session, of *managed* entities keyed by `(entity type, primary key)`. While an entity is managed, Hibernate tracks changes to it (**dirty checking**) and guarantees **identity** — within one persistence context, two lookups of the same row return the *same* Java object (`==`). When the Session closes, all its entities become **detached**.

> **Entity lifecycle states:**
> - **Transient (new):** a freshly `new`-ed object, not associated with any Session, no DB row.
> - **Managed (persistent):** attached to an open persistence context; changes are auto-flushed to the DB.
> - **Detached:** was managed, but the Session closed (or the entity was evicted). It still holds data but is no longer tracked; lazy associations may now be unloadable.
> - **Removed:** marked for deletion; the row will be deleted on flush.

> **Flush:** the act of synchronizing in-memory changes (managed-entity mutations, new inserts) to the database by issuing SQL. Happens automatically before query execution and at transaction commit (`FlushMode.AUTO`, the default).

> **Transaction:** a unit of DB work that is atomic (all-or-nothing). In Spring you usually demarcate it with `@Transactional`. The Session is typically bound to the transaction's lifetime.

### 2.2 Associations and their multiplicity

> **Association:** a mapped relationship between two entities, reflecting a foreign-key relationship in the schema.
> - `@ManyToOne`: many child rows point to one parent (e.g. many `Order`s → one `Customer`). The FK lives on the child's table. This is the "owning" side typically.
> - `@OneToMany`: the inverse — one parent has a collection of children (`Customer.orders`). Usually mapped with `mappedBy` to indicate the child owns the FK.
> - `@OneToOne`: one-to-one (e.g. `User` ↔ `UserProfile`).
> - `@ManyToMany`: requires a join table (e.g. `Student` ↔ `Course` via `student_course`).

### 2.3 Lazy vs eager — the core dichotomy

> **Eager fetching (`FetchType.EAGER`):** when the parent entity is loaded, the associated data is loaded *immediately* in the same operation (ideally via a SQL join, sometimes via a secondary select).

> **Lazy fetching (`FetchType.LAZY`):** the associated data is *not* loaded with the parent. Instead, Hibernate places a **proxy** (for a single-valued association like `@ManyToOne`) or a **lazy collection wrapper** (for `@OneToMany`/`@ManyToMany`) in the field. The real data loads only when you first *access* it — and only if a Session is still open.

> **Proxy:** a subclass (or interface implementation) of your entity, generated at runtime (historically via **CGLIB**/Javassist; in modern Hibernate via **ByteBuddy**), that holds just the primary key and a reference back to the Session. Every method call (except, by config, `getId()`) on the proxy triggers **initialization**: a `SELECT` to load the real row, after which the proxy delegates to the loaded data.
>
> *Beginner note:* "bytecode generation" means Hibernate writes a new `.class` in memory at startup/first-use whose `getName()` etc. are overridden to first run the load logic. ByteBuddy is the library that does this; CGLIB was the old one.

> **`PersistentCollection`:** Hibernate's lazy collection types (`PersistentBag`, `PersistentSet`, `PersistentList`, `PersistentMap`) that replace your plain `List`/`Set`. They start uninitialized and load their contents on first access (`.size()`, `.iterator()`, `.get()`, etc.).

### 2.4 The defaults you must memorize

| Association | JPA-spec default `FetchType` | Why |
|---|---|---|
| `@ManyToOne` | **EAGER** | Single row, assumed cheap to load |
| `@OneToOne` | **EAGER** | Single row |
| `@OneToMany` | **LAZY** | Collection, potentially large/unbounded |
| `@ManyToMany` | **LAZY** | Collection via join table |
| Basic `@Basic` field (column) | EAGER | Plain column; lazy basics need bytecode enhancement |
| `@ElementCollection` | LAZY | Collection of embeddables/basics |

> **Memory hook:** *"to-One is eager, to-Many is lazy."* This is the JPA standard. It is also frequently *the wrong default* for `@ManyToOne` in real apps — see §6.

These defaults are defined by the JPA specification (Jakarta Persistence). Hibernate honors them. They are not version-specific across modern Hibernate (5.x/6.x).

---

## 3. How it works internally

This is the heart of the chapter. We trace exactly what Hibernate does, in order.

### 3.1 Lifecycle of a lazy single-valued association (`@ManyToOne` set to LAZY)

Consider:

```java
@Entity
public class Order {
    @Id Long id;

    @ManyToOne(fetch = FetchType.LAZY) // overrides the EAGER default
    @JoinColumn(name = "customer_id")
    private Customer customer;
}
```

Step-by-step when you do `Order o = em.find(Order.class, 1L); String name = o.getCustomer().getName();`:

1. **`em.find`** issues `SELECT * FROM orders WHERE id = 1`. The `customer_id` FK value (say 42) is read, but the `customer` field is **not** populated with a real `Customer`.
2. Hibernate creates a **proxy** for `Customer` with id 42 and assigns it to `o.customer`. The proxy is an instance of a ByteBuddy-generated subclass of `Customer`. It carries a `LazyInitializer` (`HibernateProxy`) that holds the id and a back-reference to the Session.
3. `o.getCustomer()` returns the **proxy** (no DB hit yet).
4. `.getName()` is the first *real* method call on the proxy. The `LazyInitializer` sees it is uninitialized and:
   a. Checks the persistence context's L1 cache for `Customer#42` — if present, uses it.
   b. Otherwise checks the second-level cache (if enabled).
   c. Otherwise issues `SELECT * FROM customers WHERE id = 42`.
5. The loaded `Customer` becomes the proxy's **target**; subsequent calls delegate to it.
6. If at step 4 **no Session is open** (e.g. the transaction has committed and the entity is detached), Hibernate throws **`LazyInitializationException: could not initialize proxy [Customer#42] - no Session`**.

> **`LazyInitializer` / `HibernateProxy`:** the internal interface (`org.hibernate.proxy.HibernateProxy`) every proxy implements. `Hibernate.initialize(proxy)` forces it to load; `Hibernate.isInitialized(proxy)` tests it.

### 3.2 Lifecycle of a lazy collection (`@OneToMany` LAZY)

```java
@Entity
public class Customer {
    @Id Long id;
    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();
}
```

1. `em.find(Customer.class, 42L)` runs `SELECT * FROM customers WHERE id = 42`. The `orders` field is set to an **uninitialized `PersistentBag`** (a `PersistentCollection`). No orders are loaded.
2. First access — `customer.getOrders().size()` or iteration — triggers initialization.
3. Hibernate issues `SELECT * FROM orders WHERE customer_id = 42` and fills the collection.
4. Same `LazyInitializationException` risk if the Session is closed at first access.

### 3.3 Where N+1 is born — the control flow

Now the canonical disaster. Suppose `Order.customer` is LAZY (or, more insidiously, left at its EAGER default), and you do:

```java
List<Order> orders = orderRepository.findAll(); // query #1
for (Order o : orders) {
    System.out.println(o.getCustomer().getName()); // one query EACH
}
```

Internal sequence:

1. `findAll()` → `SELECT * FROM orders` returns N rows → N `Order` objects, each with a `customer` **proxy**.
2. Loop iteration 1: `getCustomer().getName()` initializes proxy → `SELECT * FROM customers WHERE id = ?` (query #2).
3. Iteration 2: a *different* customer proxy → query #3. ... and so on.
4. Total = **1 (parents) + N (one per distinct uninitialized proxy)**.

> **Crucial nuance:** if many orders share the same customer, the L1 cache *deduplicates* — once `Customer#42` is loaded, other proxies for #42 resolve from L1 with no new query. So the worst case is `1 + (number of distinct parents accessed)`, not always `1 + N`. But in the common case of distinct parents, it is genuinely `1 + N`.

The same happens with collections:

```java
List<Customer> customers = customerRepository.findAll(); // query #1
for (Customer c : customers)
    total += c.getOrders().size(); // query per customer → N+1
```

### 3.4 Why EAGER does *not* save you (the EAGER N+1 trap)

A widespread misconception: "make it EAGER and the N+1 goes away." Often **false**. When you load a list of parents *and* an association is EAGER, Hibernate **cannot always merge** it into the root query — especially when the root query is a JPQL query or when there are multiple eager collections. Hibernate then issues a **secondary select per parent** to satisfy eagerness → the *same* N+1 pattern, now triggered automatically and unavoidably, even when you never touch the association.

> **JPQL (Jakarta/Java Persistence Query Language):** an object-oriented query language over *entities* (not tables): `SELECT o FROM Order o WHERE o.total > 100`. Hibernate translates it to SQL. HQL (Hibernate Query Language) is Hibernate's superset of JPQL.

Concretely: `em.createQuery("SELECT o FROM Order o", Order.class)` with `Order.customer` EAGER will run the order query, then issue N customer selects (or batch them, depending on config). `em.find(Order.class, id)` *can* use a join for a single eager `@ManyToOne`, but a JPQL list query generally will not auto-join eager associations. **This is the single biggest reason to keep everything LAZY and fetch explicitly per query.**

### 3.5 The SQL trace (what you'd actually see in logs)

With `org.hibernate.SQL` at DEBUG:

```
-- findAll customers:
select c.id, c.name from customers c
-- then, inside the loop, one per customer:
select o.id, o.customer_id, o.total from orders o where o.customer_id=?
select o.id, o.customer_id, o.total from orders o where o.customer_id=?
select o.id, o.customer_id, o.total from orders o where o.customer_id=?
... (N times)
```

That staircase of identical `WHERE ... = ?` selects, differing only in the bound parameter, is the **fingerprint of N+1**.

### 3.6 The fix mechanics, internally

- **`JOIN FETCH` / fetch join:** Hibernate rewrites the SQL to a single `LEFT JOIN` (or inner join with `JOIN FETCH`) that loads parents and children in **one round trip**. The `ResultSet` is then de-duplicated in memory into the object graph.
- **`@EntityGraph`:** a JPA standard way to declare, at query time, which associations to fetch eagerly *for this query only*. Hibernate translates the graph into joins (FETCH graph) or treats listed attrs as eager and the rest by default (LOAD graph).
- **Batch fetching (`@BatchSize` / `hibernate.default_batch_fetch_size`):** instead of one select per proxy, Hibernate collects up to `batch_size` uninitialized proxies/collections of the *same type* and loads them with one `WHERE id IN (?, ?, ..., ?)`. Turns `1 + N` into `1 + ceil(N / batch_size)`.
- **Subselect fetching (`@Fetch(FetchMode.SUBSELECT)`):** loads *all* collections of all parents from the original query with a single `WHERE parent_id IN (SELECT id FROM ... original query)`. Turns `1 + N` into `1 + 1`.
- **DTO projection:** skips entities entirely; a JPQL/SQL `SELECT new com.x.Dto(...)` or a Spring projection runs exactly the join you want and returns flat data — no proxies, no lazy loading, no L1 overhead.

---

## 4. The complete toolkit

### 4.1 Annotations & fetch controls

| Tool | Where | Purpose | Key params / values | Default |
|---|---|---|---|---|
| `fetch = FetchType.LAZY/EAGER` | On `@ManyToOne/@OneToOne/@OneToMany/@ManyToMany` | Sets default fetch policy for the association | `LAZY`, `EAGER` | per §2.4 |
| `@Fetch(FetchMode.XXX)` (Hibernate) | On association | Hibernate-specific fetch mode | `SELECT` (default, secondary select), `JOIN` (join in same query for find/get), `SUBSELECT` (one subselect for all collections) | `SELECT` |
| `@BatchSize(size = n)` | On entity class or collection | Enables batch loading for that type/collection | `size` (max ids per IN-batch) | none (off) |
| `@EntityGraph` (JPA) | On repository method / `@NamedEntityGraph` on entity | Declarative per-query fetch plan | `attributePaths`, `type = FETCH | LOAD` | — |
| `@NamedEntityGraph` / `@NamedAttributeNode` / `@NamedSubgraph` | On entity | Reusable named graphs | named nodes & subgraphs | — |
| `JOIN FETCH` / `LEFT JOIN FETCH` | In JPQL/HQL | Fetch association in the query | `FETCH` keyword | — |
| `@Basic(fetch = LAZY)` + bytecode enhancement | On column field | Lazy column loading (e.g. big LOBs) | requires `hibernate-enhance` plugin | EAGER |
| `@LazyToOne` (deprecated in newer Hibernate) | On `@OneToOne` | Proxy vs no-proxy for to-one | `PROXY`, `NO_PROXY` | `PROXY` |

> **`FetchMode.JOIN` caveat:** it only affects `Session.get`/`find` (and entity-load-by-id), **not** JPQL/criteria list queries. For list queries you must use `JOIN FETCH` or an entity graph. This trips people up constantly.

### 4.2 Configuration properties (Hibernate / Spring)

| Property | Purpose | Typical value | Default |
|---|---|---|---|
| `hibernate.default_batch_fetch_size` | Global batch size for proxies & collections | `16`–`100` (often `16`, `25`, or `100`) | unset (off / 1) |
| `hibernate.jdbc.batch_size` | JDBC *write* batching (inserts/updates), unrelated to read N+1 but commonly confused | `20`–`50` | unset |
| `spring.jpa.open-in-view` | Open-Session-In-View filter | **set to `false`** in services | `true` (Spring Boot!) |
| `hibernate.max_fetch_depth` | Max join depth for nested eager outer joins | `0`–`3` | provider-specific (often 2–3) |
| `spring.jpa.properties.hibernate.batch_fetch_style` | Algorithm for batch IN-lists | `LEGACY`, `PADDED`, `DYNAMIC` | `LEGACY` (5.x) |
| `hibernate.show_sql` / `spring.jpa.show-sql` | Print SQL to stdout (no params) | `true` in dev only | `false` |
| `hibernate.format_sql` | Pretty-print SQL | `true` in dev | `false` |
| `logging.level.org.hibernate.SQL=DEBUG` | Log SQL via logger | dev/staging | — |
| `logging.level.org.hibernate.orm.jdbc.bind=TRACE` (H6) / `org.hibernate.type.descriptor.sql.BasicBinder=TRACE` (H5) | Log bound parameters | dev | — |
| `hibernate.generate_statistics=true` | Enable `Statistics` (query counts, etc.) | staging/perf tests | `false` |

> **`batch_fetch_style` values:** `LEGACY` pre-creates a fixed set of batch sizes (1,2,3,...,10,12,16,...) and pads to the nearest; `PADDED` pads the IN-list to a power-of-two-ish size so the SQL string (and thus the prepared-statement plan cache) is reused; `DYNAMIC` builds an exact-length IN-list every time (more plans, fewer rows). In Hibernate 6 the in-clause padding is governed by `hibernate.query.in_clause_parameter_padding`.

### 4.3 Programmatic / API tools

| API | Purpose |
|---|---|
| `Hibernate.initialize(obj)` | Force-load a proxy/collection while Session open |
| `Hibernate.isInitialized(obj)` | Check if a proxy/collection is loaded (no side effect) |
| `Hibernate.unproxy(obj)` | Get the real entity behind a proxy |
| `em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(obj, "attr")` | JPA-standard load check |
| `EntityGraph<T> em.createEntityGraph(Class)` / `em.getEntityGraph(name)` | Build/fetch graphs programmatically; pass as hint `jakarta.persistence.fetchgraph` / `loadgraph` |
| `SessionFactory.getStatistics()` → `getQueryExecutionCount()`, `getEntityFetchCount()`, etc. | Count queries to detect N+1 in tests |
| `@QueryHints({@QueryHint(name=..., value=...)})` | Attach hints (e.g. fetch size, read-only) to repository methods |

### 4.4 Spring Data JPA constructs

| Construct | Purpose |
|---|---|
| `@EntityGraph(attributePaths = {...})` on repo method | Per-method fetch plan (most common modern fix) |
| `@Query("... JOIN FETCH ...")` on repo method | Explicit fetch join |
| Interface/Class **projections** (`interface OrderView { String getId(); CustomerView getCustomer(); }`) | DTO projection without writing JPQL |
| `Tuple` / `Object[]` results | Ad-hoc flat projections |
| `JpaRepository.findAll(EntityGraph)` (via `EntityGraphJpaSpecificationExecutor` / fluent) | Apply graph to derived queries |

### 4.5 Detection tooling

| Tool | What it does |
|---|---|
| `org.hibernate.SQL` DEBUG logging | Shows the staircase of selects |
| **Hibernate `Statistics`** (`generate_statistics=true`) | Counts queries per session; assert in tests |
| **datasource-proxy** | Wraps the `DataSource`; logs/counts queries, can detect duplicate/slow queries, integrates with tests |
| **Hypersistence Utils** (formerly hibernate-types) — `SQLStatementCountValidator` | `SQLStatementCountValidator.assertSelectCount(1)` in unit tests fails the build on N+1 |
| **p6spy** | JDBC proxy that logs real SQL with bound params |
| **FlexyPool / Glowroot / APM (New Relic, Datadog, Dynatrace)** | Production query-count and slow-query visibility |
| **`spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS`** | Logs slow queries |

---

## 5. Code examples by use case

### 5.1 Reproducing N+1 (the bug)

```java
@Entity
public class Customer {
    @Id @GeneratedValue Long id;
    private String name;
    @OneToMany(mappedBy = "customer") // LAZY by default
    private List<Order> orders = new ArrayList<>();
    // getters...
}

@Entity
public class Order {
    @Id @GeneratedValue Long id;
    private BigDecimal total;
    @ManyToOne // EAGER by default — note!
    @JoinColumn(name = "customer_id")
    private Customer customer;
}

// Service — classic N+1
@Transactional(readOnly = true)
public long sumOrderCountsBad() {
    long total = 0;
    for (Customer c : customerRepo.findAll()) {  // 1 query
        total += c.getOrders().size();           // +N queries (lazy collection init)
    }
    return total;
}
```

SQL: 1 + N. The fix candidates follow.

### 5.2 Fix A — `JOIN FETCH` (best for a single collection, no pagination)

```java
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Query("SELECT DISTINCT c FROM Customer c LEFT JOIN FETCH c.orders")
    List<Customer> findAllWithOrders();
}
```

- One SQL statement with a join.
- `DISTINCT` removes the duplicate parent rows the join produces (a customer with 3 orders appears 3 times in the `ResultSet`). 
- **Pitfall:** SQL-level `DISTINCT` is also emitted, which can be wasteful. In Hibernate 5.2.2+ add the hint `hibernate.query.passDistinctThrough=false` (or in HQL 6 it's handled automatically for entity queries) so JPQL `DISTINCT` deduplicates in memory only, *not* in SQL. Example:

```java
@Query("SELECT DISTINCT c FROM Customer c LEFT JOIN FETCH c.orders")
@QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
List<Customer> findAllWithOrders();
```

> Note: in Hibernate 6, `passDistinctThrough` is deprecated/no-op because the optimization is the default for entity queries; flag this as version-specific.

### 5.3 Fix B — `@EntityGraph` (declarative, composable, the modern default)

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"customer"})
    List<Order> findByTotalGreaterThan(BigDecimal min); // derived query + graph
}
```

This makes `customer` fetched via join *for this query only*, so the `getCustomer()` calls in §3.3 cost zero extra queries — while leaving `customer` LAZY everywhere else.

Reusable named graph on the entity:

```java
@Entity
@NamedEntityGraph(
    name = "Order.withCustomerAndItems",
    attributeNodes = {
        @NamedAttributeNode("customer"),
        @NamedAttributeNode(value = "items", subgraph = "items-sub")
    },
    subgraphs = @NamedSubgraph(name = "items-sub",
        attributeNodes = @NamedAttributeNode("product"))
)
public class Order { /* ... items, customer ... */ }

public interface OrderRepository extends JpaRepository<Order, Long> {
    @EntityGraph(value = "Order.withCustomerAndItems")
    Optional<Order> findById(Long id);
}
```

> `@NamedSubgraph` lets you go *deep*: fetch `order.items.product` in one plan.

### 5.4 Fix C — Batch fetching (best when you genuinely need many collections, or graph joins explode)

```java
// Global, in application.properties
spring.jpa.properties.hibernate.default_batch_fetch_size=25
```

Or per association/entity:

```java
@Entity
@BatchSize(size = 25)               // batch-load Customer proxies
public class Customer {
    @OneToMany(mappedBy = "customer")
    @BatchSize(size = 25)           // batch-load each customer's orders collection
    private List<Order> orders = new ArrayList<>();
}
```

Now §5.1's loop produces `SELECT ... FROM orders WHERE customer_id IN (?, ?, ... up to 25)`, turning 1+N into `1 + ceil(N/25)`. Batch fetching is the **safe companion to pagination** (see §5.6) because it does not join, so it never breaks `LIMIT`.

### 5.5 Fix D — Subselect fetching (one extra query regardless of N)

```java
@Entity
public class Customer {
    @OneToMany(mappedBy = "customer")
    @Fetch(FetchMode.SUBSELECT)
    private List<Order> orders = new ArrayList<>();
}
```

When the original query loads customers, first collection access fires *one* query:

```sql
SELECT * FROM orders WHERE customer_id IN (
  SELECT id FROM customers /* the original query, re-run as subselect */
)
```

`1 + N` → `1 + 1`. **Caveat:** like a join, it ignores `LIMIT` semantics — it re-runs the original query (including its WHERE) as a subselect, which is fine for non-paged queries but interacts oddly with pagination and dynamic queries.

### 5.6 Fix E — Pagination done right (DON'T join-fetch a collection while paging)

The trap:

```java
@Query("SELECT DISTINCT c FROM Customer c LEFT JOIN FETCH c.orders")
Page<Customer> findAllWithOrders(Pageable pageable); // DANGER
```

Because the join multiplies rows, applying `LIMIT 10` would cut a customer's orders in half — so **Hibernate cannot push the limit to SQL**. It logs:

```
HHH000104: firstResult/maxResults specified with collection fetch; applying in memory!
```

…then **fetches the ENTIRE table into memory** and paginates in the JVM. On a large table this OOMs or stalls. **Two correct patterns:**

**Pattern 1 — paginate IDs, then fetch (the "two-query" / window pattern):**

```java
// Query 1: page the parent IDs only (LIMIT works — no join)
@Query("SELECT c.id FROM Customer c ORDER BY c.id")
Page<Long> findCustomerIds(Pageable pageable);

// Query 2: fetch full graph for just those IDs (no LIMIT needed)
@Query("SELECT DISTINCT c FROM Customer c LEFT JOIN FETCH c.orders WHERE c.id IN :ids")
List<Customer> findWithOrdersByIds(@Param("ids") List<Long> ids);
```

```java
@Transactional(readOnly = true)
public Page<Customer> pageCustomers(Pageable pageable) {
    Page<Long> idPage = repo.findCustomerIds(pageable);
    List<Customer> content = repo.findWithOrdersByIds(idPage.getContent());
    return new PageImpl<>(content, pageable, idPage.getTotalElements());
}
```

**Pattern 2 — page parents normally, batch-fetch collections:** set `default_batch_fetch_size` (or `@BatchSize`) and **do not** join-fetch the collection. Pagination works at SQL level; collections load in batched IN-queries. Simpler, slightly more queries.

> **Rule of thumb:** *You may safely `JOIN FETCH` at most one collection, and never while paginating that collection.* Joining a single-valued `@ManyToOne` while paging is fine (it doesn't multiply rows). Joining **two** collections produces a Cartesian product — Hibernate 6 throws `MultipleBagFetchException` for two `List` (bag) collections.

### 5.7 Fix F — DTO projection (the highest-performance read path)

When you only need to *read* data (not mutate entities), skip entities entirely.

**Constructor expression (JPQL):**

```java
public record OrderSummary(Long orderId, BigDecimal total, String customerName) {}

@Query("""
   SELECT new com.acme.OrderSummary(o.id, o.total, c.name)
   FROM Order o JOIN o.customer c
   WHERE o.total > :min
""")
List<OrderSummary> summaries(@Param("min") BigDecimal min);
```

One query, exactly the columns you need, no proxies, no L1 bloat, no lazy exceptions.

**Spring interface projection:**

```java
interface OrderSummaryView {
    Long getId();
    BigDecimal getTotal();
    String getCustomerName(); // SpEL or matching alias
}
List<OrderSummaryView> findByTotalGreaterThan(BigDecimal total);
```

**Native query + projection (full control over SQL):**

```java
@Query(value = """
   SELECT o.id AS id, o.total AS total, c.name AS customerName
   FROM orders o JOIN customers c ON c.id = o.customer_id
   WHERE o.total > :min
""", nativeQuery = true)
List<OrderSummaryView> nativeSummaries(@Param("min") BigDecimal min);
```

> **When DTO projections win:** read-only list/report endpoints, GraphQL/REST responses, aggregations. They sidestep the entire fetching-strategy minefield. The cost: you can't lazily navigate further or dirty-check; that's the point.

### 5.8 Avoiding `LazyInitializationException` correctly

```java
// WRONG: lazy access after the transaction/session closed
public CustomerDto getCustomerDto(Long id) {
    Customer c = repo.findById(id).orElseThrow(); // session closed after this returns
    return new CustomerDto(c.getName(), c.getOrders().size()); // LIE: no session
}

// RIGHT: do association access inside the transaction, return a detached DTO
@Transactional(readOnly = true)
public CustomerDto getCustomerDto(Long id) {
    Customer c = repo.findWithOrders(id); // fetch graph inside tx
    return new CustomerDto(c.getName(), c.getOrders().size()); // safe
}
```

> **Never** "fix" LIE by enabling open-session-in-view or by slapping EAGER on everything. Fix it by fetching what you need within the transactional boundary, then mapping to a DTO. (See §6.4.)

### 5.9 Detecting N+1 in a unit test (CI guardrail)

```java
// Using Hypersistence Utils
@Test
@Transactional
void noNPlusOne() {
    SQLStatementCountValidator.reset();
    service.loadDashboard();          // exercise the path
    SQLStatementCountValidator.assertSelectCount(2); // fail build if it grows
}
```

```java
// Using raw Hibernate Statistics
Statistics stats = sessionFactory.getStatistics();
stats.clear();
service.loadDashboard();
assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Default everything to LAZY.** Override `@ManyToOne`/`@OneToOne` to `LAZY` explicitly (`fetch = FetchType.LAZY`). EAGER on a to-one means *every* load of the entity (including in unrelated queries) pulls the association, and list queries trigger the EAGER N+1 (§3.4). With LAZY, you fetch on demand per query.
- **Fetch per query, not per mapping.** The mapping declares *capability*; the query declares *intent*. Use `@EntityGraph`/`JOIN FETCH`/projections at the query to fetch exactly what that use case needs.
- **One collection join max; never join two bags.** Two `List` collection joins → `MultipleBagFetchException` (Hibernate). Use `Set`, or batch-fetch the second, or split queries.
- **Prefer DTO projections for read-only paths.** They eliminate entity overhead (proxy creation, dirty-check snapshot, L1 storage) and are typically 2–10× lighter for large lists.
- **Tune `default_batch_fetch_size`** (16–100). Larger reduces round trips but produces longer IN-lists (watch DB plan-cache churn and Oracle's 1000-element IN limit; Hibernate splits batches accordingly).

### 6.2 Correctness & concurrency

- **`@OneToOne` lazy is special:** an *optional* (nullable) child `@OneToOne` on the parent side cannot be proxied by id alone (Hibernate must check existence), so it often stays effectively eager unless you use `@MapsId`, bytecode enhancement (`@LazyToOne(NO_PROXY)` historically), or make the FK on the parent side. Flag: this is a known sharp edge.
- **`Set` vs `List`/bag and duplicates:** a `JOIN FETCH` into a `List` (bag) yields duplicate parents → use `DISTINCT`. `Set` collections dedupe naturally but require correct `equals`/`hashCode`.
- **`equals`/`hashCode` on entities:** never use the DB-generated `@Id` in `hashCode` for entities placed in `HashSet` before persistence (id is null pre-insert). Use a business key or `@NaturalId`. Bad equality breaks `Set`-based associations and dedup.
- **Concurrency:** the persistence context/Session is **not thread-safe**. Never share an entity (with its proxies) across threads. Lazy init on a detached/foreign-thread entity throws.

### 6.3 Memory

- **In-memory pagination (HHH000104)** is the classic OOM. Avoid collection fetch joins with `Pageable`.
- **Subselect/large fetch joins** materialize big Cartesian products in the `ResultSet`; even after dedup, the driver buffered them. For wide graphs, prefer batched or split queries.
- **L1 cache growth:** loading 100k entities in one transaction keeps 100k objects + dirty-check snapshots in memory. For bulk reads use projections, `Stream`, or `StatelessSession` and periodic `em.clear()`.

### 6.4 The open-session-in-view (OSIV) anti-pattern

> **Open-Session-In-View (OSIV):** a pattern where a servlet filter/interceptor keeps the Hibernate Session open for the *entire HTTP request* — through controller, view rendering, and serialization — so lazy associations can be initialized late (e.g. during JSON serialization). In **Spring Boot it is ON by default** (`spring.jpa.open-in-view=true`), and Spring even logs a warning about it at startup.

Why it is an anti-pattern:

- **Hidden N+1 at serialization time:** Jackson walks the object graph and touches every lazy proxy, firing queries *outside* any service method, invisible to your transactional design.
- **Database connection held for the whole request,** including slow view rendering and network write to the client → connection-pool exhaustion under load.
- **Mixes layers:** the web tier silently performs DB I/O; you lose the clean transactional boundary; errors surface during response writing (hard to handle).
- **Masks LIE** instead of fixing the fetch design.

**Best practice:** set `spring.jpa.open-in-view=false`, fetch exactly what you need inside `@Transactional` service methods, and return **DTOs** (never lazy entities) to the controller. If you must return entities, fetch the needed graph first.

### 6.5 Security

- **Native projection SQL injection:** never string-concatenate user input into `@Query(nativeQuery=true)`. Use bound parameters.
- **Over-fetching = data exposure:** EAGER + serializing entities can leak fields/associations (passwords, internal refs) into API responses. DTOs give you an explicit allowlist.

### 6.6 Observability

- Enable `org.hibernate.SQL=DEBUG` + parameter binding in dev/staging.
- `generate_statistics=true` and surface `QueryExecutionCount`/`EntityFetchCount` to metrics (Micrometer) — alert on per-request query counts.
- Use datasource-proxy or p6spy to log *real* SQL with params and timings, and to detect duplicate identical queries (a N+1 tell).

### 6.7 Testing & production hardening

- Add `SQLStatementCountValidator` (Hypersistence) assertions to the hottest read paths so N+1 regressions fail CI.
- Load-test with realistic row counts; N+1 is invisible with 3 rows of seed data and lethal with 10k.
- Pin `default_batch_fetch_size` and `open-in-view=false` in config.
- Code review checklist: any new repo method returning entities with associations touched downstream must declare a fetch plan or return a DTO.

### 6.8 Anti-patterns to avoid (quick list)

1. `FetchType.EAGER` on associations "to be safe."
2. Relying on OSIV.
3. `JOIN FETCH` a collection with `Pageable`.
4. Two collection (`List`/bag) `JOIN FETCH`es in one query.
5. Returning managed entities from controllers / serializing lazy proxies.
6. Fixing LIE with `Hibernate.initialize` sprinkled everywhere instead of a fetch plan.
7. Catch-all EAGER plus second-level cache hiding the real query cost in dev.

---

## 7. Advanced topics & deep internals

### 7.1 Proxy internals (ByteBuddy era)

Modern Hibernate (5.3+) uses **ByteBuddy** (replacing CGLIB) to generate proxy classes. A proxy is a subclass of your entity implementing `HibernateProxy`, carrying a `LazyInitializer`. Implications:

- The proxy is **not** your entity's exact class — `proxy.getClass() != Customer.class` (it's `Customer$HibernateProxy$xyz`). `instanceof` works (it's a subclass) but `getClass()` comparisons and some equals implementations break. Use `Hibernate.unproxy()` or `Hibernate.getClass(obj)`.
- Calling `getId()` typically does **not** initialize the proxy (the id is known) — *if* you annotate the id getter or rely on field access with the id available. This lets you set FKs (`order.setCustomer(em.getReference(Customer.class, 42L))`) without a SELECT.

> **`em.getReference(Class, id)`:** returns a proxy without hitting the DB; ideal for setting associations by id (insert/update) without loading the target. Throws `EntityNotFoundException` lazily on first real access if the row doesn't exist.

### 7.2 Bytecode enhancement (lazy basics & lazy to-one without proxies)

> **Bytecode enhancement:** a build-time step (Maven/Gradle Hibernate enhance plugin) that rewrites your entity `.class` files to intercept *field* access directly, rather than relying on proxies. Enables:
> - **Lazy basic columns** (`@Basic(fetch=LAZY)`) — e.g. a huge `@Lob` text/blob not loaded until accessed.
> - **`@LazyGroup("name")`** — group lazy fields so accessing one triggers loading the whole group in one select.
> - **No-proxy lazy to-one** — the parent's own loaded state holds an enhanced field, avoiding the proxy-class identity problems.
> - **Dirty tracking** without full snapshot comparison (self-dirty-tracking), reducing flush cost.

Enable via plugin config: `enableLazyInitialization`, `enableDirtyTracking`, `enableAssociationManagement`.

### 7.3 `batch_fetch_style` and IN-clause padding (plan-cache friendliness)

- **`LEGACY`:** Hibernate precomputes a set of batch sizes and rounds the actual batch up to the nearest, padding the IN-list with a repeated id. Fewer distinct SQL strings → better prepared-statement cache reuse.
- **`PADDED`:** pads the IN-list length up to reduce SQL-string variety.
- **`DYNAMIC`:** exact-size IN-list each time → many SQL variants → plan-cache churn on databases like Oracle/SQL Server (mitigate with `hibernate.query.in_clause_parameter_padding=true`, default `true` in Hibernate 6).

> **Why padding matters:** databases cache execution plans keyed by SQL text. `IN (?)`, `IN (?,?)`, `IN (?,?,?)` are *different* strings → different cached plans → cache thrash and CPU on hard parses. Padding to powers of two keeps the number of distinct strings small.

### 7.4 `MultipleBagFetchException` and the two-collection problem

Fetching two `List` (bag — unordered, duplicates allowed) collections in one query produces a Cartesian product Hibernate refuses to materialize: `cannot simultaneously fetch multiple bags`. Remedies:

1. Change one/both to `Set` (Hibernate allows multiple `Set` fetches — but beware the Cartesian product still happens in SQL and bloats the result; usually only two small collections).
2. Keep them `List` but fetch one with `JOIN FETCH` and the other with `@BatchSize`/subselect.
3. Split into separate queries; Hibernate stitches via L1 cache (`select c with items`, then `select c with tags` — the second resolves into the same managed `Customer`).

### 7.5 Second-level cache interaction

> **Second-level cache (L2):** an optional, SessionFactory-wide cache (Ehcache, Infinispan, Caffeine) shared across sessions. Entities and collections can be cached so a lazy init/proxy resolve hits the cache instead of the DB.

If L2 caches the association/collection, "N+1" queries become N cache hits — fast, but still N lookups and still hidden cost; and stale-cache correctness issues appear. L2 is a complement, not a substitute, for a correct fetch plan.

### 7.6 `StatelessSession` for bulk reads

> **`StatelessSession`:** a Hibernate session with **no** persistence context, no dirty checking, no L1 cache, no lazy loading. You fetch rows as detached objects with explicit `get`s. Ideal for ETL/bulk processing of millions of rows without the memory/GC cost of managed entities. Lazy associations don't work (no session to init them) — you must join-fetch or project.

### 7.7 Streaming and `setFetchSize`

For huge result sets, use `Stream<T>` repository methods (or `ScrollableResults`) plus JDBC `fetchSize` (`@QueryHint(name="org.hibernate.fetchSize", value="...")`) and periodic `em.detach`/`em.clear()` to keep memory flat. Combine with projections to avoid lazy traps.

### 7.8 Hibernate 6 specifics

- JPQL `DISTINCT` no longer passes through to SQL for entity queries by default (the old `passDistinctThrough` need is gone).
- New SQM (Semantic Query Model) query engine; fetch-join handling and `@EntityGraph` translation improved.
- `@OneToOne` lazy and batch handling refined. Always verify behavior on your exact Hibernate version — flag version-specifics in design docs.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Fix comparison

| Technique | Queries | Pagination-safe? | Multiple collections? | Mutability | Best for |
|---|---|---|---|---|---|
| `JOIN FETCH` | 1 | No (for collections) | No (bags) | Managed entities | One association, no paging |
| `@EntityGraph` | 1 | No (for collections) | Limited | Managed entities | Declarative per-query fetch; composable |
| `@BatchSize` / `default_batch_fetch_size` | 1 + ⌈N/size⌉ | **Yes** | Yes | Managed entities | Paginated parents + many collections |
| `@Fetch(SUBSELECT)` | 1 + 1 | No (re-runs query) | Yes | Managed entities | Non-paged, all-collections load |
| DTO projection | 1 | **Yes** | Yes (flat join) | Read-only | Read/report endpoints |
| EAGER mapping | varies / N+1 | No | No | Managed | (avoid as a global default) |

### 8.2 Lazy vs Eager decision rules

- **Use LAZY (and override the to-one default to LAZY) when:** the association isn't needed by *every* code path; the collection can be large; you want per-query control. **This is the default recommendation for nearly everything.**
- **Use EAGER only when:** the association is *always* needed whenever the entity is loaded, is a single small to-one, and the entity is loaded mostly by id (`find`), not in lists. Even then, prefer LAZY + explicit fetch.

### 8.3 Which fix to pick (flowchart in prose)

1. **Read-only data for a response/report?** → **DTO projection.** Done.
2. **Need managed entities, single association, no pagination?** → **`JOIN FETCH` / `@EntityGraph`.**
3. **Need pagination of parents?** → **page IDs then fetch**, *or* batch-fetch collections (`default_batch_fetch_size`). Never join-fetch the paged collection.
4. **Many associations / multiple collections?** → **batch fetching** (and/or split queries), or DTO.
5. **Bulk processing millions of rows?** → **`StatelessSession` + projections + streaming.**

### 8.4 Alternatives outside the JPA toolkit

- **jOOQ / Spring `JdbcTemplate` / MyBatis:** explicit SQL, no lazy loading, no N+1 surprises — at the cost of ORM convenience. Common choice for read-heavy/reporting services alongside JPA for writes (CQRS-ish).
- **GraphQL data loaders:** batch and cache per-request to defeat N+1 at the API layer (`DataLoader` pattern), independent of JPA.

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → diagnosis table

| Symptom | Likely cause | Diagnosis |
|---|---|---|
| Endpoint slow, DB shows hundreds of `SELECT ... WHERE x=?` | N+1 (lazy in loop, or EAGER list) | `org.hibernate.SQL=DEBUG`; count via Statistics |
| `LazyInitializationException: no Session` | Lazy access after tx/session closed (DTO mapping outside tx, OSIV off + lazy in view) | Stacktrace points to the `.get*()`; fetch within tx |
| `HHH000104: applying in memory` + high heap/OOM | Collection `JOIN FETCH` + `Pageable` | Log line itself; switch to ID-paging or batch |
| `MultipleBagFetchException: cannot simultaneously fetch multiple bags` | Two `List` collections fetch-joined | Exception message; use Set/batch/split |
| Connection pool exhausted under load | OSIV holding connections through view render | HikariCP metrics; disable OSIV |
| Duplicate identical queries within a request | OSIV-driven serialization N+1, or repeated `findById` | datasource-proxy duplicate detection |
| `proxy.getClass()` mismatch / ClassCast | Proxy class vs entity class | `Hibernate.unproxy`/`getClass` |

### 9.2 Concrete debugging session

1. **Reproduce with logging on:**
   ```
   logging.level.org.hibernate.SQL=DEBUG
   logging.level.org.hibernate.orm.jdbc.bind=TRACE   # Hibernate 6 param binding
   spring.jpa.properties.hibernate.format_sql=true
   ```
2. **Count statements** for the request: enable `generate_statistics=true`, or wrap in `SQLStatementCountValidator`/datasource-proxy. If selects ≫ 2 and scale with row count → N+1.
3. **Identify the offending association** from the repeated `WHERE fk = ?`.
4. **Apply the right fix** per §8.3.
5. **Lock it in** with a `assertSelectCount(...)` test.

### 9.3 Real-world incident patterns

- **The "fine in dev, dead in prod" launch:** seed data had ~5 rows per parent; production had thousands. An EAGER `@ManyToOne` on a hot list endpoint caused 1+N where N≈50k, exhausting the DB connection pool and cascading 500s. Fix: LAZY + `@EntityGraph` on that endpoint; added Statistics-based CI guard.
- **The OSIV serialization storm:** a controller returned entities; Jackson serialized lazy collections during JSON write, firing thousands of queries *after* the service returned. Latency p99 spiked, connections held through response streaming. Fix: `open-in-view=false`, return DTOs.
- **The pagination OOM:** `Page<Customer> findAllWithOrders(Pageable)` with a fetch join silently pulled the whole table to memory (HHH000104) and OOM'd on a 2M-row table. Fix: ID-paging pattern (§5.6).

---

## 10. Interview drill

**Q1. What are the default fetch types for each JPA association, and why?**
*Model:* `@ManyToOne` and `@OneToOne` default to **EAGER**; `@OneToMany` and `@ManyToMany` default to **LAZY** ("to-one eager, to-many lazy"). Rationale: a to-one loads at most one extra row (assumed cheap); a collection is potentially large/unbounded so deferring is safer.
- *Probe: Is the EAGER default a good idea?* No — for list queries EAGER to-one triggers automatic N+1 (secondary select per row) and over-fetches in unrelated paths. Best practice is to override to LAZY and fetch per query.
- *Probe: Can you make `@OneToOne` truly lazy?* Hard for optional/nullable parent-side to-ones because Hibernate needs to know if the row exists to decide null vs proxy; requires `@MapsId`, FK on the right side, or bytecode enhancement.

**Q2. Explain the N+1 problem with a SQL trace.**
*Model:* Loading N parents (1 query) and then touching a lazy/per-row-eager association inside a loop fires one `SELECT ... WHERE fk=?` per parent → N+1 total. Trace shows one parent select then a staircase of identical parameterized selects. L1 cache can reduce it to 1 + distinct-parents.
- *Probe: Does EAGER fix it?* Usually not for list queries — it just makes the N selects automatic and unavoidable.
- *Probe: How does L1 cache affect the count?* Repeated access to the same id resolves from L1, so worst case is 1 + distinct parents, not always 1 + N.

**Q3. What is `LazyInitializationException` and how do you fix it correctly?**
*Model:* Thrown when you access an uninitialized lazy proxy/collection after the Session closed (entity detached). Correct fix: initialize what you need *inside* the transaction (fetch join / entity graph) and return a DTO; not by enabling OSIV or making everything EAGER.
- *Probe: Why is OSIV a bad fix?* It holds the DB connection through view rendering, hides N+1 at serialization, and breaks layering.
- *Probe: What does `getReference` give you?* A proxy by id without a SELECT — useful to set associations without loading the target.

**Q4. Walk through the tools to fix N+1 and when you'd use each.**
*Model:* `JOIN FETCH`/`@EntityGraph` (1 query, single association, no paging); batch fetching (`@BatchSize`/`default_batch_fetch_size`, 1+⌈N/size⌉, pagination-safe); subselect (1+1, no paging); DTO projection (1 query, read-only, best for responses). Pick per query intent. (See §8.)
- *Probe: Which is pagination-safe?* Batch fetching and DTO projections; collection join fetch is not.

**Q5 (senior-signal). You must paginate parents and also return their child collections. Design it.**
*Model:* Don't join-fetch a collection with `Pageable` (causes in-memory paging / OOM, HHH000104). Either (a) two-query window: page parent IDs with a no-join query, then fetch the full graph `WHERE id IN :ids`; or (b) page parents normally and let `default_batch_fetch_size`/`@BatchSize` batch-load the collections. Choose (a) for tight query counts, (b) for simplicity. Justify by the join-row-multiplication problem and the SQL `LIMIT` semantics.
- *Probe: Why can't Hibernate push LIMIT into a collection-join query?* Because the join multiplies parent rows, so `LIMIT n` would truncate a parent's children — it must paginate parents in memory instead.

**Q6 (senior-signal). When would you abandon entities for DTO projections, and what do you give up?**
*Model:* For read-only/reporting/list endpoints where you only need specific columns and never mutate. You gain: minimal queries, no proxy/L1 overhead, no LIE, explicit field allowlist (security). You give up: dirty checking, lazy navigation, automatic cascade/identity — all irrelevant for read paths. It's effectively CQRS read-side.
- *Probe: Interface vs constructor (record) projection tradeoffs?* Interface projections are terse and Spring-managed (and support nested projections / native queries); constructor expressions are explicit JPQL, type-safe, and decouple from entity field names.

**Q7 (senior-signal). Your service is fast in tests but melts in production. How do you reason about and prevent fetching regressions?**
*Model:* Tests with tiny seed data hide N+1 (cost scales with row count). Reason in terms of *queries per request* independent of row count. Prevent via: query-count assertions (`SQLStatementCountValidator`/Statistics) on hot paths in CI, realistic load tests, `open-in-view=false`, LAZY-by-default with explicit per-query fetch, and APM query-count alerts in prod.
- *Probe: Where does OSIV hurt under load specifically?* It holds a pooled DB connection for the whole request including slow view rendering → pool exhaustion and latency amplification.

**Q8. What is `MultipleBagFetchException` and how do you resolve it?**
*Model:* Fetching two `List` (bag) collections in one query creates a Cartesian product Hibernate won't materialize. Resolve by: making collections `Set`, fetching one via join and the other via batch/subselect, or splitting into separate queries stitched by L1 cache.

**Q9. How does batch fetching change the query shape, and how do you tune it?**
*Model:* Instead of one select per proxy/collection, Hibernate emits `WHERE id IN (?,...)` for up to `batch_size` pending of the same type → 1+⌈N/size⌉. Tune `hibernate.default_batch_fetch_size` (16–100) and `batch_fetch_style`/IN-padding for prepared-statement-cache friendliness; mind Oracle's 1000-IN limit.
- *Probe: Why pad the IN-list?* To limit distinct SQL strings so the DB reuses cached execution plans (avoid hard-parse churn).

**Q10. Difference between `@Fetch(FetchMode.JOIN)` and `JOIN FETCH` in JPQL?**
*Model:* `FetchMode.JOIN` only affects entity loads by id (`find`/`get`); it is ignored for JPQL/criteria list queries. To fetch in a list query you must use JPQL `JOIN FETCH` or an entity graph. Confusing them is a classic bug.

**Q11. Explain how a Hibernate proxy works and its gotchas.**
*Model:* A ByteBuddy-generated subclass holding a `LazyInitializer` (id + session). First real method call initializes via L1/L2/SELECT. Gotchas: `getClass()` returns the proxy class (use `Hibernate.unproxy`/`getClass`); broken `equals`/`hashCode`; LIE when session closed; `getId()` typically doesn't initialize.

**Q12. What's the difference between JDBC batch_size and default_batch_fetch_size?**
*Model:* `hibernate.jdbc.batch_size` batches *writes* (insert/update statements) for throughput; `hibernate.default_batch_fetch_size` batches *reads* of lazy proxies/collections to fight N+1. Different problems; commonly confused.

---

## 11. Glossary

- **Association:** mapped relationship between entities reflecting a FK.
- **Batch fetching:** loading multiple pending proxies/collections of the same type via one `IN (...)` query.
- **Bag:** Hibernate's `List`-backed, unordered, duplicate-allowing collection (`PersistentBag`).
- **ByteBuddy:** runtime bytecode library Hibernate uses to generate proxy subclasses (replaced CGLIB).
- **Bytecode enhancement:** build-time rewriting of entity classes to enable lazy basics, lazy groups, no-proxy lazy to-one, and self-dirty-tracking.
- **CGLIB:** older bytecode-generation library formerly used for proxies.
- **Detached:** entity state after its Session closed; no longer tracked; lazy nav unsafe.
- **Dirty checking:** Hibernate detecting modified managed entities to auto-generate UPDATEs at flush.
- **DTO (Data Transfer Object):** a flat object carrying exactly the data a use case needs; here, a projection target.
- **Eager fetching:** loading an association immediately with its parent.
- **`EntityGraph`:** JPA per-query declaration of which associations to fetch.
- **`EntityManager`:** JPA's persistence API; Hibernate's `Session` is the native equivalent.
- **First-level cache (L1):** the per-Session persistence context cache guaranteeing entity identity.
- **Flush:** synchronizing in-memory changes to the DB via SQL.
- **Fetch join:** `JOIN FETCH` — fetching an association in the same query.
- **`FetchType`:** `LAZY` or `EAGER`, the association's default load policy.
- **Hibernate:** the leading Java ORM and a JPA implementation.
- **HHH000104:** Hibernate warning for in-memory pagination of a collection fetch.
- **HQL/JPQL:** entity-oriented query languages translated to SQL.
- **`LazyInitializationException` (LIE):** thrown on lazy access after the Session closed.
- **`LazyInitializer`/`HibernateProxy`:** internal proxy machinery.
- **Lazy fetching:** deferring association load until first access via proxy/collection wrapper.
- **`MultipleBagFetchException`:** error from fetch-joining two bag collections at once.
- **N+1 problem:** 1 parent query + N child queries from per-row association loads.
- **ORM:** Object-Relational Mapping; maps rows ↔ objects.
- **OSIV (Open-Session-In-View):** keeping the Session open for the whole HTTP request; an anti-pattern (Spring Boot default `true`).
- **Persistence context:** the L1 cache / unit-of-work owned by a Session.
- **`PersistentCollection`:** Hibernate's lazy collection types (`PersistentBag`, `PersistentSet`, etc.).
- **Projection:** querying a subset of columns / a DTO instead of full entities.
- **Proxy:** runtime-generated stand-in for a lazy entity that loads on first real access.
- **Second-level cache (L2):** SessionFactory-wide cache shared across sessions.
- **`Session`:** Hibernate's native persistence API (richer than `EntityManager`).
- **`StatelessSession`:** session without persistence context/lazy loading; for bulk ops.
- **Subselect fetching:** loading all parents' collections via one subselect of the original query.
- **Transaction:** atomic unit of DB work; usually bounds the Session in Spring.
- **`@BatchSize`:** annotation enabling batch fetching for a type/collection.
- **`getReference`:** returns a proxy by id without a SELECT.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

- **Defaults:** `@ManyToOne`/`@OneToOne` = EAGER; `@OneToMany`/`@ManyToMany` = LAZY. → **Override to-one to LAZY.**
- **N+1 fingerprint:** 1 parent select + a staircase of `SELECT ... WHERE fk=?`. Cost scales with row count.
- **EAGER does NOT fix N+1** for list queries — it automates it.
- **Fix matrix:** `JOIN FETCH`/`@EntityGraph` (1q, no paging) · batch fetch `default_batch_fetch_size=16..100` (1+⌈N/size⌉, **paging-safe**) · subselect (1+1) · DTO projection (1q, read-only, best for APIs).
- **Pagination + collection fetch join = HHH000104 in-memory OOM.** Use ID-paging or batch fetching.
- **Two `List` fetch joins = `MultipleBagFetchException`.** Use Set / batch / split.
- **Disable OSIV:** `spring.jpa.open-in-view=false`. Return DTOs, not lazy entities.
- **Fix LIE** inside the transaction (fetch what you need), not with EAGER/OSIV.
- **Detect:** `org.hibernate.SQL=DEBUG`, `generate_statistics=true`, datasource-proxy/Hypersistence `SQLStatementCountValidator.assertSelectCount(n)`.
- **`FetchMode.JOIN`** affects only `find`/`get`, not JPQL list queries.
- **`getReference(id)`** = proxy, no SELECT; great for setting FKs.
- **Bulk reads:** `StatelessSession` + projections + JDBC fetch size + `em.clear()`.

### Self-test (no answers)

1. You load 1,000 invoices and print `invoice.getCustomer().getName()` in a loop; the DB shows 1,001 queries. Explain why, identify the most likely mapping cause, and give two distinct fixes — one safe with pagination and one not.
2. A repository method `Page<Author> findAllWithBooks(Pageable)` uses `LEFT JOIN FETCH a.books`. What warning/behavior do you expect at runtime, why, and how do you redesign it?
3. Your endpoint throws `LazyInitializationException` only in production with `open-in-view=false`. Walk through the root cause and the correct fix without re-enabling OSIV.
4. Compare `@BatchSize`/`default_batch_fetch_size` vs `@Fetch(SUBSELECT)` vs `JOIN FETCH` in terms of query count, pagination safety, and number-of-collections supported. When do you pick each?
5. Why does setting `@ManyToOne(fetch = EAGER)` frequently make list-query performance *worse*, and what is the relationship between this and the N+1 problem?
6. Describe how you would add a CI guard that fails the build if a hot read path regresses into N+1, naming the specific tools/APIs.
7. Explain `MultipleBagFetchException`, why two `Set` fetches behave differently, and the Cartesian-product cost you still pay.

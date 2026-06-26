# Hibernate ORM Internals

> A definitive engineering-handbook chapter for senior JVM backend developers who want to master Hibernate from first principles down to its action-queue and dirty-checking internals — well enough to design with it, operate it in production, debug it under load, and answer any interview question on it.

---

## 1. Overview & where it fits

### What it is

**Hibernate ORM** is an **object-relational mapping** framework for the JVM. "ORM" means it maps your **Java objects** (instances of classes) onto **relational database rows** (tuples in tables), and back again, so you can program against objects and let the framework generate and run the SQL. Hibernate is the reference implementation behind much of **JPA** (the **Jakarta Persistence API**, formerly **Java Persistence API**) — a standardized specification (a set of interfaces and rules) for ORM on the JVM. JPA is the *interface*; Hibernate is the most widely used *implementation* of that interface, plus a large set of native extensions beyond the spec.

Two terms you will see constantly:

- **JPA** — a *specification*. It defines interfaces like `EntityManager`, `EntityManagerFactory`, annotations like `@Entity`, `@Id`, `@OneToMany`, and a query language called **JPQL** (Jakarta Persistence Query Language). A specification ships no working code by itself; it is a contract.
- **Hibernate** — a *provider* (implementation) of JPA, plus its own richer **native API** (`Session`, `SessionFactory`, `Criteria`, HQL). When you use Spring Data JPA, you are almost always using Hibernate underneath.

### The problem it solves

Without an ORM you write JDBC by hand. **JDBC** (Java Database Connectivity) is the low-level JVM API for talking to relational databases: you open a `Connection`, create a `PreparedStatement`, set parameters by index, execute, iterate a `ResultSet` row by row, and manually copy each column into object fields. This is correct but tedious and repetitive, and it spreads SQL strings and column-index bookkeeping throughout your code. The repetitive copying of result columns into objects (and object fields into statement parameters) is the bulk of data-access code, and it is exactly what an ORM automates.

But Hibernate solves much more than boilerplate. Its real value is managing a **graph of objects** with **identity**, **change tracking**, **lazy loading**, **caching**, **transactional write batching**, and **relationship navigation** — features that are painful to build by hand and easy to get subtly wrong.

### The object-relational impedance mismatch

This is the foundational reason ORM is hard, and the reason Hibernate's internals look the way they do. **Impedance mismatch** is a borrowed electrical-engineering metaphor: two systems that don't naturally "fit" lose energy at the boundary. The object world and the relational world disagree on several axes:

| Axis | Object model (Java) | Relational model (SQL) | The friction |
|---|---|---|---|
| **Granularity** | Many fine-grained classes (e.g. `Address`, `Money`) | Tables are coarser; you don't make a table per value type | Need *embeddable* / value-type mapping |
| **Identity** | Two notions: `==` (reference identity) and `.equals()` (value equality) | One notion: primary key | Need to reconcile DB identity with object identity |
| **Associations** | Object references, directional, can be bidirectional, navigable both ways | Foreign keys, inherently unidirectional, no "navigation" | Need to map references to FKs and manage both sides |
| **Inheritance** | First-class (`extends`, polymorphism) | No native inheritance | Need inheritance strategies (single-table, joined, table-per-class) |
| **Data navigation** | Walk references: `order.getCustomer().getAddress()` | Set-based joins; walking row-by-row is the N+1 anti-pattern | Need lazy loading + fetch strategies |
| **Subtyping/polymorphic queries** | `instanceof`, virtual dispatch | A query returns rows of one shape | Need polymorphic query support |

Hibernate exists to bridge that boundary while hiding most of the friction. Understanding its internals largely means understanding *how* it bridges each axis — especially identity (the persistence context), associations (proxies and lazy loading), and change tracking (dirty checking and the flush).

### When you reach for it

- You have a rich domain model with relationships, and you want to navigate objects rather than assemble result sets.
- You want transactional consistency, change tracking, and write batching without hand-coding it.
- You want database portability (dialects) and to express most queries in JPQL/HQL rather than vendor SQL.
- You are using Spring Boot / Spring Data JPA, where Hibernate is the default and idiomatic choice.

When you should *not*: heavy bulk/ETL workloads (use JDBC, `jOOQ`, or native bulk SQL), reporting queries returning millions of rows, or latency-critical hot paths where you need full control of the exact SQL. We treat this properly in Section 8.

### The one-paragraph mental model

> Think of a Hibernate `Session` (JPA `EntityManager`) as a **transactional, in-memory write cache and identity map** sitting in front of your database for the duration of a unit of work. When you load or save an entity, Hibernate puts a *managed* copy of it into this cache — the **persistence context**. While the context is open, Hibernate **watches** every managed entity for changes (dirty checking). It does **not** hit the database on every setter; instead it **queues** the implied SQL and **flushes** it — usually at transaction commit, or before a query that might be affected — translating your in-memory object changes into a correctly ordered, batched stream of `INSERT`/`UPDATE`/`DELETE` statements. The persistence context also guarantees that within one session, the same database row is represented by exactly one object instance (the identity-map guarantee). Everything else — proxies, lazy loading, the first-level cache, `merge`, flush modes — is a consequence of these two ideas: *managed copies* and *deferred, ordered, batched writes*.

---

## 2. Foundations from first principles

We build the vocabulary now. Every term introduced here is used heavily later.

### 2.1 Entity, table, mapping

An **entity** is a Java class whose instances correspond to rows in a database table. You declare it with `@Entity`. The class must have an identifier field annotated `@Id` (the mapping of the primary key). Hibernate reads these annotations (or XML mappings) to build a **metamodel** — an internal description of how each class maps to tables and columns, used at runtime to generate SQL.

```java
import jakarta.persistence.*;

@Entity
@Table(name = "customers")          // optional; defaults to the entity name
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // PK generation strategy (Section 3.7)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 120)
    private String name;

    @Column(name = "email", unique = true)
    private String email;

    @Version                          // optimistic-locking version column (Section 7)
    private long version;

    // JPA requires a no-arg constructor (can be protected) so Hibernate can instantiate via reflection
    protected Customer() {}

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }
    // getters/setters omitted for brevity
}
```

Key terms introduced:
- **`@GeneratedValue`** — tells Hibernate the database (or Hibernate) assigns the primary key value, and how (Section 3.7).
- **`@Version`** — a column Hibernate uses for **optimistic locking**: it increments on each update and detects concurrent modification. Explained fully in Section 7.
- **No-arg constructor** — Hibernate creates entity instances by reflection and then populates fields, so it needs a constructor it can call with no arguments.

### 2.2 SessionFactory / EntityManagerFactory

- **`SessionFactory`** (Hibernate-native) and **`EntityManagerFactory`** (JPA) are **heavyweight, thread-safe, application-scoped** objects. You build **one per database** for the whole application's lifetime. They hold the parsed metamodel, the connection pool reference, the dialect, the second-level cache, and compiled query plans. Building one is expensive (it scans entities, builds SQL templates); never build them per request.
- A **`Dialect`** is Hibernate's abstraction of database-specific SQL: how to write `LIMIT`, which sequence syntax to use, what column types map to what Java types, whether it supports `INSERT ... RETURNING`, and so on. Hibernate picks a dialect (e.g. `PostgreSQLDialect`, `MySQLDialect`, `OracleDialect`) so the same mapping produces correct SQL across vendors.

### 2.3 Session / EntityManager

- A **`Session`** (Hibernate) / **`EntityManager`** (JPA) is a **lightweight, NOT thread-safe, short-lived** object representing a single **unit of work** — typically one transaction or one request. You open one, do work, commit/flush, close it. It is the gateway to all persistence operations (`persist`, `find`, `merge`, `remove`, queries) and it *owns the persistence context*.
- `EntityManager` is the JPA interface; in Hibernate it is implemented by the same object that implements `Session`. You can unwrap one to the other: `entityManager.unwrap(Session.class)`.

### 2.4 The persistence context (a.k.a. first-level cache, L1 cache)

This is the single most important internal concept. The **persistence context** is an **in-memory map of managed entities for the duration of a session**, keyed by **entity type + primary key**. It does three jobs:

1. **Identity map / identity guarantee.** Within one persistence context, a given database row maps to **exactly one** object instance. If you `find` the same `Customer#42` twice in one session, you get the *same Java object* (`==` true), not two copies. This avoids inconsistencies where two in-memory copies of the same row could diverge.
2. **First-level cache.** A repeated `find` by the same id within the session returns the cached instance **without** hitting the database. This is *always on* and cannot be disabled (you can only evict from it).
3. **Dirty-tracking scope.** Every entity in the persistence context is *watched*. When you change a field, Hibernate will eventually detect it and schedule an `UPDATE`. The persistence context holds a **snapshot** of each entity's loaded state for this comparison.

> "First-level" vs "second-level": the **L1 cache** is the persistence context — scoped to **one session** and never shared. The **L2 cache** (Section 7) is optional, shared across sessions, and lives in the `SessionFactory`.

### 2.5 Transactions and units of work

A **transaction** is a database concept: a group of statements that either all commit or all roll back (atomicity), with isolation from other concurrent transactions. In Hibernate the persistence context lifecycle is normally tied to a transaction (or, in Spring, to a transactional method). At **commit**, Hibernate **flushes** (writes queued SQL) and the database transaction commits. The phrase **unit of work** describes this scope: open context → make changes → flush → commit → close.

### 2.6 Proxy and lazy loading (preview)

When Hibernate loads an entity that has an association (say `Order.customer`), it often does **not** load the associated `Customer` immediately. Instead it stores a **proxy** — a runtime-generated subclass of `Customer` that looks like a `Customer` but holds only the id, and triggers a database load the first time you call a real getter. This is **lazy loading**. The proxy mechanism (and the dreaded `LazyInitializationException` when the session is already closed) is covered in Sections 3 and 9.

### 2.7 Flush (preview)

A **flush** is the act of synchronizing the in-memory persistence context with the database by executing the queued `INSERT`/`UPDATE`/`DELETE` statements. Crucially, **flush is not commit**: a flush sends SQL to the DB within the transaction; commit makes it permanent. Hibernate flushes automatically at well-defined points (before commit; before some queries) and you can flush manually. This deferred, batched write behavior is the source of most "surprising" Hibernate behavior, and we devote much of Section 3 to it.

---

## 3. How it works internally

This is the heart of the document. We trace the actual control and data flow inside Hibernate for each fundamental operation, name the internal collaborators, and define the **entity lifecycle state machine**.

### 3.1 The internal collaborators (who does what)

When you call `entityManager.persist(x)`, several internal objects cooperate. Knowing their names makes stack traces and the source readable:

| Internal component | Role |
|---|---|
| **`SessionImpl`** | The concrete `Session`/`EntityManager`. Entry point; delegates to the others. |
| **`PersistenceContext`** (`StatefulPersistenceContext`) | Holds the identity map of managed entities, their **entity snapshots** (loaded state), proxies, and collection state. |
| **`ActionQueue`** | The ordered list of pending DB operations (insertions, updates, deletions, collection actions). The "write-behind" buffer. |
| **`EntityPersister`** (e.g. `SingleTableEntityPersister`) | Per-entity-type object that knows the table/column mapping and generates/executes the actual SQL for that entity. |
| **`EventListenerGroup`s** (e.g. `PersistEventListener`, `FlushEventListener`, `DirtyCheckEventListener`) | Hibernate is **event-driven** internally. Every API call fires an event handled by a chain of listeners. This is the real engine. |
| **`Dialect`** | Produces DB-specific SQL fragments. |
| **`IdentifierGenerator`** | Produces primary-key values (identity, sequence, table, UUID). |
| **`EntityEntry`** | A per-managed-entity record inside the persistence context: its id, current status (MANAGED/DELETED/etc.), the loaded snapshot, the persister, the lock mode. |

The mental model: **the public API is a thin facade over an event/listener pipeline that mutates the persistence context and the action queue, and the action queue is later drained into SQL via persisters and the dialect.**

### 3.2 The entity lifecycle state machine

Every entity instance, from Hibernate's point of view, is in exactly one of four states. **The state lives in the persistence context, not in the object** — the same Java object can be managed in one session and detached in another.

```
                         persist() / save()
        (new object) ───────────────────────────► MANAGED ◄──────────────┐
          TRANSIENT                                  │  ▲                  │
             ▲  │                                     │  │ find()/load()/   │
             │  │ garbage collected                   │  │ query result     │
             │  │                                     │  │                  │
             │  └─────────────────────────────────────┘  │                  │
             │                                            │                  │
   (after remove + flush:                    remove()    │       merge()    │
    row deleted, object                       ┌──────────▼──────────┐       │
    becomes transient again)                  │      REMOVED        │       │
                                              └──────────┬──────────┘       │
                                                         │ flush → DELETE   │
                          detach()/clear()/              │                  │
                          close()/evict()                ▼                  │
        DETACHED ◄────────────────────────────── (session ends)            │
            │                                                                │
            └───────────────────── merge() ─────────────────────────────────┘
                          (returns a managed copy; arg stays detached)
```

The four states, defined precisely:

1. **TRANSIENT (new).** A plain Java object you just `new`-ed. It has **no** database identity (no row), and Hibernate is **not** tracking it. It is not in any persistence context. If you drop the reference it is simply garbage-collected, with no DB effect.

2. **MANAGED / PERSISTENT.** The object is **in the persistence context** and associated with a database row (or a pending insert). Hibernate **tracks** it: any field change will be detected at flush and turned into an `UPDATE`. You get a managed instance from `find`, `getReference`, a query, or by calling `persist`/`merge`. **This is the only state in which automatic dirty checking works.**

3. **DETACHED.** The object *was* managed but its persistence context ended (the session closed) or it was explicitly detached/evicted/cleared. It still has a database identity (it knows its id), but Hibernate is **no longer tracking it**. Changing its fields does **nothing** to the database. Detached objects are what you typically send across a network boundary (e.g. as a DTO source) and bring back later via `merge`.

4. **REMOVED.** The object is still in the persistence context and still managed, but it is **scheduled for deletion**. The corresponding `DELETE` is queued and will run at flush. After the flush+commit, the row is gone; the object becomes effectively transient.

The transition operations, in JPA terms (Hibernate-native synonyms in parentheses):

| Transition | JPA call | Hibernate-native | Effect |
|---|---|---|---|
| transient → managed | `persist(e)` | `save(e)` / `persist(e)` | Schedules INSERT; entity now tracked |
| (any with id) → managed copy | `merge(e)` | `merge(e)` / `saveOrUpdate` (loose) | Copies state into a managed instance |
| managed → removed | `remove(e)` | `delete(e)` | Schedules DELETE |
| managed → detached | `detach(e)` | `evict(e)` | Stops tracking this one entity |
| all managed → detached | `clear()` | `clear()` | Empties persistence context |
| managed → detached | `close()` | `close()` | Session ends; all become detached |
| reload from DB into managed | `refresh(e)` | `refresh(e)` | Overwrites in-memory state with DB state |

### 3.3 Trace: `find()` / `get()` — loading an entity

Step by step, when you call `customer = em.find(Customer.class, 42L)`:

1. **L1 cache lookup.** `SessionImpl` asks the `PersistenceContext` whether an entity with key `(Customer, 42)` is already present. If yes, it returns that exact instance immediately — **no SQL**. This is the identity-map guarantee in action.
2. **L2 cache lookup (if enabled).** If not in L1 and the entity is L2-cacheable, Hibernate checks the second-level cache. A hit constructs an entity from cached state without SQL.
3. **SQL generation.** On a miss, the `EntityPersister` for `Customer` produces the `SELECT ... FROM customers WHERE id = ?` (the dialect handles syntax) and binds `42`.
4. **JDBC execution.** The statement runs on the transaction's `Connection`; a `ResultSet` returns.
5. **Hydration.** Hibernate reads each column and builds the entity's state — first as a flat array of values (the **hydrated state**), then sets them into a new `Customer` instance via the persister.
6. **Snapshot + registration.** The persister also stores a **loaded-state snapshot** (a copy of the field values as loaded) inside the new `EntityEntry`, and registers the instance in the persistence context as MANAGED. This snapshot is what dirty checking later compares against.
7. **Associations.** For each association, depending on fetch type: `LAZY` associations get **proxies** or uninitialized **collection wrappers**; `EAGER` ones are fetched now (possibly via a join or a follow-up select).

`find` vs `getReference` (`get` vs `load` in native API):
- **`find` / `get`**: hits the DB (or cache) **now**; returns `null` if the row doesn't exist.
- **`getReference` / `load`**: returns a **proxy immediately without hitting the DB**; the SELECT is deferred until you touch a non-id property. If the row doesn't exist, you get an `EntityNotFoundException` *later*, when the proxy initializes. Use `getReference` when you only need the entity to set a foreign key (e.g. `order.setCustomer(em.getReference(Customer.class, 42L))`) and don't need its data.

### 3.4 Trace: `persist()` — making a transient entity managed

When you call `em.persist(newCustomer)`:

1. A **`PersistEvent`** is fired and handled by the `DefaultPersistEventListener` chain.
2. Hibernate checks the entity's state. If it is transient (no id, not in context), it proceeds; if it is already managed it is a no-op; if detached it throws.
3. **Identifier assignment depends on the generation strategy** (this is subtle and important):
   - **IDENTITY**: the database assigns the id via an auto-increment column. Hibernate **cannot know the id without executing the INSERT**, so it must run the `INSERT` **immediately** during `persist` (not deferred to flush) to obtain the generated key. This disables JDBC batch inserts for that entity (Section 3.7).
   - **SEQUENCE / TABLE**: Hibernate can fetch the next id from the sequence **without** inserting the row, so it gets the id now (possibly from a pre-allocated pool) and **defers** the `INSERT` to flush — enabling batching.
   - **UUID / assigned**: the id is known immediately; insert deferred.
4. The entity is placed into the persistence context as MANAGED with an `EntityEntry`, and (for non-IDENTITY) an **`EntityInsertAction`** is appended to the `ActionQueue`.
5. **Cascade**: if the entity has associations annotated with `cascade = PERSIST` (or `ALL`), the persist propagates to those associated objects (transitive persistence).

> Key takeaway: with `IDENTITY` generation, `persist` triggers SQL right away; with `SEQUENCE`, it does not. This single fact explains a lot of "why did the INSERT happen here?" confusion.

### 3.5 Trace: automatic dirty checking

This is the magic that lets you change an object and have the `UPDATE` "just happen." There is **no** interception of your setters in the common case (Hibernate doesn't proxy your setters by default). Instead, at flush time:

1. For each MANAGED entity in the persistence context, Hibernate retrieves its **loaded-state snapshot** (captured at load/persist time) from the `EntityEntry`.
2. It computes the entity's **current state** by reading its fields now.
3. It compares current vs snapshot **property by property**. If any persistent property differs, the entity is **dirty**.
4. For each dirty entity, an **`EntityUpdateAction`** is queued. By default the generated `UPDATE` includes **all** columns (`dynamicUpdate=false`); with `@DynamicUpdate` it includes only the changed columns (Section 7).
5. Collections (one-to-many, many-to-many) have their own dirty detection via wrapper classes (`PersistentBag`, `PersistentSet`, etc.) that track adds/removes.

This is called **state-based / snapshot dirty checking**. Its cost is proportional to (number of managed entities × number of properties) per flush — which is why holding thousands of entities in one context and flushing repeatedly is expensive (Section 6).

> **Bytecode enhancement** (optional, Section 7) changes this: with enhancement, Hibernate instruments your entity classes at build time to track dirtiness *as you mutate fields*, avoiding the full snapshot comparison. This is opt-in.

### 3.6 Trace: the flush — turning the action queue into SQL

A **flush** synchronizes the persistence context with the DB. Here is the internal sequence (`DefaultFlushEventListener` → `AbstractFlushingEventListener`):

1. **flushEverythingToExecutions** — Hibernate runs **dirty checking** over all managed entities and collections (Section 3.5), populating the `ActionQueue` with insert/update/delete/collection actions. It also **cascades** flush operations to associated entities.
2. **performExecutions** — the `ActionQueue` is drained in a **fixed, deterministic order** (this ordering is critical for referential integrity):
   1. `OrphanRemovalAction` (deletes for orphan-removed children)
   2. `EntityInsertAction` (and `EntityIdentityInsertAction`)
   3. `EntityUpdateAction`
   4. `CollectionRemoveAction`
   5. `CollectionUpdateAction`
   6. `CollectionRecreateAction`
   7. `EntityDeleteAction`
   So: **inserts before updates before deletes**, with collection actions interleaved appropriately. Within each action type, Hibernate can **batch** statements via JDBC batching if enabled.
3. **postFlush** — the persistence context is reconciled: snapshots are updated to the new state (so the next dirty check has a fresh baseline), and collections are marked clean.

Crucially, **flush ≠ commit**. After a flush, the SQL is in the database's transaction buffer but **not committed**; a rollback would still undo it. Commit triggers a final flush (if needed) and then the JDBC commit.

> **Why the fixed order matters.** Suppose you insert a parent and a child that references it, and delete an old child. If Hibernate deleted before inserting, or updated a FK before the referenced row existed, you'd violate foreign-key constraints. The deterministic order (inserts → updates → deletes) is Hibernate's strategy to satisfy most FK constraints automatically. It is not foolproof for every cyclic graph — see Section 9 for constraint-violation debugging and `@org.hibernate.annotations` ordering hints.

### 3.7 Identifier generation strategies and their batching implications

The `@GeneratedValue(strategy = ...)` choice has **deep** performance consequences because of *when* Hibernate can learn the id.

| Strategy | How the id is obtained | When the INSERT runs | JDBC batch inserts? | Typical DB support |
|---|---|---|---|---|
| **IDENTITY** | DB auto-increment column; id known only **after** INSERT | **Immediately on `persist`** (must execute to read generated key) | **No** (Hibernate disables insert batching for IDENTITY entities) | MySQL `AUTO_INCREMENT`, PostgreSQL `SERIAL`/`IDENTITY`, SQL Server `IDENTITY` |
| **SEQUENCE** | Reads from a DB sequence (`SELECT nextval(...)`) **before** insert | **Deferred to flush** | **Yes** (the killer advantage) | PostgreSQL, Oracle, DB2; MySQL 8+ has no real sequences |
| **TABLE** | A separate table acts as a sequence (row holds the next value) | Deferred to flush | Yes | Any DB (portable but slow; extra locking/round-trips) |
| **AUTO** | Provider chooses; Hibernate 6 default leans to SEQUENCE-style (`hibernate_sequence` / per-entity sequences) where supported | Depends on resolved strategy | Depends | Any |
| **UUID** (`@GeneratedValue` + `@UuidGenerator`, or assigned) | Generated in JVM (e.g. random/v7 UUID) | Deferred to flush | Yes | Any (column is UUID/`CHAR(36)`/`BINARY(16)`) |

Why this matters in production: if you bulk-insert 10,000 rows with `IDENTITY`, you get **10,000 individual round-trips** — batching is impossible because each insert must return its generated key before the next can proceed in a batchable way. Switch to `SEQUENCE` with a pooled optimizer and Hibernate can fetch ids in blocks and batch the inserts, turning 10,000 round-trips into a few dozen. This is one of the most common, highest-impact tuning changes.

**Sequence optimizers** (the batching of *id allocation* itself):
- A naive sequence call costs one DB round-trip per id. To avoid that, Hibernate supports **allocation/pooling optimizers** that reserve a block of ids per sequence call.
- `allocationSize` (on `@SequenceGenerator`) defaults to **50** in Hibernate. With `pooled`/`pooled-lo` optimizers, one `nextval` reserves 50 ids, so 50 inserts cost 1 id round-trip instead of 50.
- **Caveat:** the DB sequence's own `INCREMENT BY` must agree with Hibernate's `allocationSize`, or you get gaps/collisions. With `pooled-lo`, Hibernate treats each `nextval` as the *low* end of a block of size `allocationSize`. Misconfiguration here (DB increments by 1 but Hibernate allocates 50) is a classic duplicate-key bug.

```java
@Entity
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "invoice_seq")
    @SequenceGenerator(
        name = "invoice_seq",
        sequenceName = "invoice_sequence",  // must exist in DB
        allocationSize = 50)                // 1 nextval reserves 50 ids; default 50
    private Long id;
    // ...
}
```

### 3.8 save vs persist vs merge vs saveOrUpdate vs update

These are constantly confused. Precise semantics:

| Method | API | Returns | Semantics | Side effects / gotchas |
|---|---|---|---|---|
| `persist(e)` | JPA (and Hibernate) | `void` | Make a transient entity managed; schedule INSERT. | JPA-compliant; if `e` is detached, throws. Does not guarantee the id is set immediately (only for non-IDENTITY it may be deferred). Cascades on `PERSIST`. |
| `save(e)` | Hibernate-native | `Serializable` (the id) | Like persist but **returns the generated id** and **guarantees an id is assigned** before returning. | Non-standard; tied to Hibernate. With IDENTITY it forces the INSERT now. |
| `merge(e)` | JPA (and Hibernate) | the **managed copy** | Copies the state of `e` (transient OR detached) into a **managed instance** and returns it. | **The argument is NOT managed afterwards** — only the returned object is. Forgetting `e = em.merge(e)` is a top bug. Issues a SELECT to load the current row before copying (unless state is known), then schedules UPDATE/INSERT. |
| `update(e)` | Hibernate-native | `void` | Reattach a **detached** entity, marking it dirty/managed. | Throws if a *different* instance with same id is already in the context (`NonUniqueObjectException`). Less safe than merge. |
| `saveOrUpdate(e)` | Hibernate-native | `void` | If transient → save; if detached → update. | Convenience; same NonUniqueObject risk as update. |
| `refresh(e)` | JPA | `void` | Reload `e`'s state from the DB, discarding in-memory changes. | Overwrites your unflushed changes. |
| `remove(e)` / `delete(e)` | JPA / native | `void` | Schedule DELETE. | `e` must be managed (JPA `remove`); Hibernate `delete` can take detached and will reattach. Cascades on `REMOVE`. |

**persist vs merge decision rule:**
- Use **`persist`** for brand-new entities within the same session/transaction where you created them.
- Use **`merge`** when you have a **detached** entity (e.g. an object reconstructed from a web request or returned from a previous transaction) and want to apply its state to the database. Always reassign: `entity = em.merge(entity);`.

**Why `merge` does a SELECT:** to merge, Hibernate must load the current managed state of the row so it can copy your detached changes onto it and dirty-check correctly. If the row isn't already in L1, that's an extra SELECT. For high-volume save paths, prefer `persist` on managed graphs over `merge` of detached graphs.

### 3.9 How a flush translates to SQL — a concrete walkthrough

Consider:

```java
@Transactional
public void demo() {
    Customer c = em.find(Customer.class, 42L);   // (A) SELECT customers ... id=42
    c.setName("New Name");                         // (B) in-memory only; NO SQL yet
    Order o = new Order(c, 19999);                 // transient
    em.persist(o);                                 // (C) with SEQUENCE: id fetched, INSERT queued
    List<Order> recent =
        em.createQuery("select o from Order o where o.customer = :c", Order.class)
          .setParameter("c", c).getResultList();   // (D) triggers an auto-flush first!
}
```

What actually runs, in order:
- **(A)** `SELECT` for customer 42. Snapshot stored.
- **(B)** No SQL. `c` is dirty in memory.
- **(C)** With `SEQUENCE`: `SELECT nextval` (or from pool), then `INSERT` is **queued**, not executed. With `IDENTITY`: the `INSERT` runs **now**.
- **(D)** Before running the JPQL query, Hibernate sees the query touches `Order` (and `Customer`), and the persistence context has **pending changes that could affect the query result** (a new `Order`, a changed `Customer`). With the default flush mode **AUTO**, Hibernate **auto-flushes first**: it runs the queued `INSERT` for `o` and the `UPDATE` for `c`'s name, *then* runs the SELECT. This is "surprising flush behavior" #1: a *read* query caused your *writes* to hit the DB earlier than commit.

If you had set `FlushModeType.COMMIT`, the auto-flush before the query would **not** happen, and the query might return stale results (it wouldn't see the unflushed `Order`). This is the classic flush-mode tradeoff.

### 3.10 Flush modes — full semantics

A **flush mode** controls *when* Hibernate auto-flushes.

| Mode | JPA enum | Behavior | Use when |
|---|---|---|---|
| **AUTO** (default) | `FlushModeType.AUTO` | Flush before transaction commit **and** before executing a query whose results could be affected by pending changes. | Default; correctness-first. |
| **COMMIT** | `FlushModeType.COMMIT` | Flush only at commit; **do not** auto-flush before queries. | Read-heavy code where you accept possibly-stale query results to avoid premature writes. |
| **ALWAYS** (Hibernate) | `FlushMode.ALWAYS` | Flush before **every** query, even if pending changes can't affect it. | Rare; defensive. |
| **MANUAL** (Hibernate) | `FlushMode.MANUAL` | Never auto-flush; you must call `flush()` explicitly. | Long conversations / multi-request units of work where you want full control. |

Hibernate's AUTO is slightly smarter than the JPA minimum: it checks the **query spaces** (the tables a query reads) against the tables with pending changes; it only auto-flushes if they intersect. This is why a query on an unrelated table may *not* trigger a flush.

### 3.11 The write-behind / action queue summarized

The pattern Hibernate implements is **write-behind caching** for the database. "Write-behind" means: accept the write into a fast in-memory buffer now, and apply it to the slow backing store (the DB) later, in a batch. The buffer is the `ActionQueue`. Benefits: (1) **batching** — multiple inserts/updates go in one round-trip; (2) **ordering** — Hibernate can reorder to satisfy FK constraints; (3) **deduplication/coalescing** — multiple changes to the same entity collapse into one `UPDATE`. The cost: your code's apparent control flow and the actual SQL timing diverge, which is exactly the source of debugging confusion (Sections 9 and 3.9).

---

## 4. The complete toolkit

### 4.1 Core JPA / Hibernate APIs

| Method | Where | Purpose | Key params | Notes / defaults |
|---|---|---|---|---|
| `find(Class, id)` | `EntityManager` | Load by PK, return null if absent | type, id, optional `LockModeType`, properties (hints) | Hits L1 then L2 then DB |
| `getReference(Class, id)` | `EntityManager` | Lazy proxy by PK | type, id | No immediate SQL; `EntityNotFoundException` on init if missing |
| `persist(e)` | `EntityManager` | Transient → managed (INSERT) | entity | Cascades on PERSIST |
| `merge(e)` | `EntityManager` | Detached/transient → managed copy | entity | **Returns** managed copy; reassign! |
| `remove(e)` | `EntityManager` | Managed → removed (DELETE) | entity | Cascades on REMOVE; arg must be managed |
| `refresh(e)` | `EntityManager` | Reload from DB | entity, optional lock | Discards in-memory changes |
| `detach(e)` | `EntityManager` | Evict one entity | entity | Stops tracking |
| `clear()` | `EntityManager` | Evict all | — | Empties context |
| `flush()` | `EntityManager` | Force write-behind to DB now | — | Not commit |
| `setFlushMode(m)` | `EntityManager` | Set auto-flush policy | mode | Default AUTO |
| `lock(e, mode)` | `EntityManager` | Acquire DB lock | entity, `LockModeType` | Pessimistic/optimistic |
| `contains(e)` | `EntityManager` | Is entity managed here? | entity | Boolean |
| `getReference` vs `find` | — | proxy vs eager | — | see Section 3.3 |
| `createQuery(jpql, T)` | `EntityManager` | JPQL query | string, result type | Auto-flush per mode |
| `createNativeQuery(sql)` | `EntityManager` | Raw SQL | string, mapping | Bypasses JPQL translation |
| `createNamedQuery(name)` | `EntityManager` | Pre-defined query | name | Validated at startup |

Hibernate-native extras on `Session`:

| Method | Purpose | Notes |
|---|---|---|
| `save(e)` | persist + return id | Forces id assignment |
| `update(e)` | reattach detached | NonUniqueObject risk |
| `saveOrUpdate(e)` | save or update by state | Convenience |
| `get(Class, id)` | like `find` | returns null if missing |
| `load(Class, id)` | like `getReference` | returns proxy |
| `evict(e)` | like `detach` | |
| `setHibernateFlushMode(m)` | richer flush modes | MANUAL/ALWAYS |
| `byId(Class).load(id)` | fluent get | |
| `byMultipleIds(Class).multiLoad(ids)` | batch load by PKs | One SELECT ... IN (...) |
| `setReadOnly(e, true)` | skip dirty checking for this entity | Perf: no snapshot compare |
| `setDefaultReadOnly(true)` | whole session read-only | Big perf win for read paths |
| `doWork(Work)` | run raw JDBC on the session's connection | escape hatch |

### 4.2 Key annotations

| Annotation | Purpose | Key attributes / defaults |
|---|---|---|
| `@Entity` | Mark a class as an entity | `name` (defaults to simple class name) |
| `@Table` | Table mapping | `name`, `schema`, `uniqueConstraints`, `indexes` |
| `@Id` | Primary key field | — |
| `@GeneratedValue` | PK generation | `strategy` (AUTO/IDENTITY/SEQUENCE/TABLE), `generator` |
| `@SequenceGenerator` | Configure a sequence | `sequenceName`, `allocationSize` (default 50), `initialValue` |
| `@Column` | Column mapping | `name`, `nullable` (default true), `unique`, `length` (default 255), `insertable`, `updatable` |
| `@Version` | Optimistic-lock version | int/long/short/timestamp |
| `@Basic(fetch=LAZY)` | Lazy scalar | needs bytecode enhancement to truly be lazy |
| `@OneToMany` | Collection association | `mappedBy`, `cascade`, `fetch` (default **LAZY**), `orphanRemoval` |
| `@ManyToOne` | FK association | `fetch` (default **EAGER** — a common perf trap), `optional` |
| `@OneToOne` | 1:1 | `fetch` (default EAGER), `mappedBy` |
| `@ManyToMany` | join-table assoc | `fetch` (default LAZY), `joinTable` |
| `@JoinColumn` | FK column | `name`, `referencedColumnName`, `nullable` |
| `@MapsId` | Share PK with FK | for 1:1 / derived ids |
| `@Embeddable` / `@Embedded` | Value type inline in table | granularity bridge |
| `@DynamicUpdate` | UPDATE only changed columns | off by default |
| `@DynamicInsert` | INSERT only non-null columns | off by default |
| `@BatchSize(size=n)` | Batch lazy loads | mitigates N+1 |
| `@Fetch(FetchMode.SUBSELECT/JOIN/SELECT)` | Hibernate fetch strategy | tune collection loading |
| `@NaturalId` | Business key lookup | enables `bySimpleNaturalId` + cache |
| `@Immutable` | Read-only entity | skips dirty checking |
| `@Cache` | L2 cache region/strategy | `usage` (READ_ONLY, READ_WRITE, NONSTRICT_READ_WRITE, TRANSACTIONAL) |

### 4.3 Configuration flags that affect internals

| Property | Effect | Typical value |
|---|---|---|
| `hibernate.jdbc.batch_size` | Enable JDBC batching; max statements per batch | `20`–`50` (default: **no batching** / 0) |
| `hibernate.order_inserts` | Reorder inserts by entity type to enable batching | `true` (for batch) |
| `hibernate.order_updates` | Reorder updates similarly | `true` (for batch) |
| `hibernate.jdbc.batch_versioned_data` | Allow batching of versioned (optimistic-lock) updates | `true` (most modern DBs report update counts correctly) |
| `hibernate.default_batch_fetch_size` | Global `@BatchSize` for lazy loads | `16`–`50` |
| `hibernate.show_sql` | Log SQL to stdout | `false` (use a SQL logger instead) |
| `hibernate.format_sql` | Pretty-print logged SQL | `false` |
| `hibernate.generate_statistics` | Expose `Statistics` (query counts, cache hits) | `false`; turn on in perf tests |
| `hibernate.hbm2ddl.auto` | Schema management | `none` in prod; `validate`/`update`/`create-drop` otherwise |
| `hibernate.cache.use_second_level_cache` | Enable L2 | `false` |
| `hibernate.cache.use_query_cache` | Enable query cache | `false` |
| `hibernate.connection.provider_disables_autocommit` | Tell Hibernate the pool disables autocommit (lets it delay connection acquisition) | `true` with HikariCP `autoCommit=false` |
| `hibernate.enable_lazy_load_no_trans` | (Anti-pattern) allow lazy init outside a transaction | keep `false` |
| `hibernate.query.in_clause_parameter_padding` | Pad `IN (...)` lists to power-of-two sizes to reuse query plans | `true` helps plan-cache hit rate |

---

## 5. Code examples by use case

### 5.1 Bulk insert done right (SEQUENCE + batching + periodic clear)

The single most important production pattern: insert many rows efficiently without OOM and without N round-trips.

```java
@PersistenceContext
private EntityManager em;

@Transactional
public void bulkInsert(List<InvoiceData> rows) {
    int batchSize = 50;              // must match hibernate.jdbc.batch_size
    for (int i = 0; i < rows.size(); i++) {
        Invoice inv = new Invoice(rows.get(i));  // entity uses GenerationType.SEQUENCE
        em.persist(inv);                          // INSERT queued, not executed
        if (i > 0 && i % batchSize == 0) {
            em.flush();   // send the batched INSERTs to the DB
            em.clear();   // detach everything → free the snapshots → bound memory & dirty-check cost
        }
    }
    // remaining entities flush at commit
}
```

Required config (e.g. `application.properties`):
```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
```
Why each line: without `batch_size`, Hibernate sends one INSERT per round-trip even if queued. `order_inserts/updates` groups same-type statements so the JDBC driver can actually batch them. The periodic `flush()/clear()` keeps the persistence context small — otherwise dirty checking grows O(n²)-ish over the loop and you risk `OutOfMemoryError`. **This pattern fails silently if the entity uses `IDENTITY`** — batching won't happen; switch to `SEQUENCE`.

### 5.2 Fixing the N+1 select problem

**N+1** means: 1 query to fetch a list of parents, then N extra queries (one per parent) to fetch each parent's lazy association. A 100-row page becomes 101 queries.

```java
// PROBLEM: this triggers N+1 if Order.customer is lazy and you touch it in a loop
List<Order> orders = em.createQuery("select o from Order o", Order.class).getResultList();
for (Order o : orders) {
    System.out.println(o.getCustomer().getName()); // each call → a SELECT for that customer
}

// FIX 1: JOIN FETCH — one query, eagerly joins the association
List<Order> orders = em.createQuery(
    "select o from Order o join fetch o.customer", Order.class).getResultList();

// FIX 2: Entity graph — declarative fetch plan, reusable
EntityGraph<Order> g = em.createEntityGraph(Order.class);
g.addAttributeNodes("customer");
List<Order> orders = em.createQuery("select o from Order o", Order.class)
    .setHint("jakarta.persistence.fetchgraph", g)
    .getResultList();

// FIX 3: @BatchSize on the association → loads lazies in chunks of N (e.g. 1 query per 16 parents)
@ManyToOne(fetch = FetchType.LAZY) @BatchSize(size = 16) private Customer customer;
```

Choosing: `join fetch` for a known query; entity graphs for reusable fetch plans; `@BatchSize`/`@Fetch(SUBSELECT)` when you cannot change the query but want to bound the number of follow-up selects.

### 5.3 Optimistic locking with `@Version` (lost-update prevention)

```java
@Entity
public class Account {
    @Id private Long id;
    private long balance;
    @Version private long version;   // Hibernate adds it to the WHERE of every UPDATE
}

@Transactional
public void withdraw(Long id, long amount) {
    Account a = em.find(Account.class, id);   // loads balance + version (say version=7)
    a.setBalance(a.getBalance() - amount);    // dirty
    // at flush: UPDATE account SET balance=?, version=8 WHERE id=? AND version=7
    // If another tx already bumped it to 8, this UPDATE affects 0 rows →
    //   Hibernate throws OptimisticLockException → caller retries.
}
```

The mechanism: every `UPDATE` carries `AND version = <loaded version>` and sets `version = <loaded+1>`. If a concurrent transaction committed first, the row's version no longer matches, **0 rows update**, and Hibernate detects the lost update. This is **optimistic** concurrency: no locks held during the think-time, conflicts detected at write.

### 5.4 Pessimistic locking for a hot row

When contention is high and retries are wasteful, lock the row in the DB:

```java
@Transactional
public void reserveSeat(Long seatId) {
    // SELECT ... FOR UPDATE — other txns block until this commits
    Seat seat = em.find(Seat.class, seatId, LockModeType.PESSIMISTIC_WRITE);
    if (seat.isAvailable()) seat.reserve();
}
```

`PESSIMISTIC_WRITE` issues `SELECT ... FOR UPDATE` (DB-specific via dialect), serializing access. Use sparingly: it holds DB locks for the transaction's duration and can cause lock waits/deadlocks under load.

### 5.5 Detached-entity update flow (web request → service)

```java
// Controller receives a detached object reconstructed from JSON (id present, fields set)
@PutMapping("/customers/{id}")
public CustomerDto update(@PathVariable Long id, @RequestBody CustomerDto dto) {
    Customer detached = dto.toEntity(id);   // NOT managed
    Customer managed = service.update(detached);
    return CustomerDto.from(managed);
}

@Transactional
public Customer update(Customer detached) {
    // merge: SELECT current row, copy detached state onto managed copy, schedule UPDATE
    return em.merge(detached);   // MUST use the return value
}
```

A safer alternative that avoids the blind overwrite of `merge` (which can clobber fields the client didn't intend to change) is **load-then-mutate**:

```java
@Transactional
public Customer update(Long id, CustomerPatch patch) {
    Customer c = em.find(Customer.class, id);   // managed
    patch.applyTo(c);                            // mutate only intended fields
    return c;                                    // dirty checking writes only those
}
```

This is generally preferred for partial updates because it avoids overwriting unmentioned columns and avoids the optimistic-lock pitfalls of stale detached versions.

### 5.6 Read-only query path (skip dirty checking)

```java
@Transactional(readOnly = true)
public List<Report> reportData() {
    Session s = em.unwrap(Session.class);
    s.setDefaultReadOnly(true);   // no snapshots taken → no dirty checking → less memory & CPU
    return em.createQuery("select r from Report r", Report.class)
             .setHint(org.hibernate.jpa.QueryHints.HINT_READONLY, true)
             .getResultList();
}
```

Marking the session/query read-only tells Hibernate not to take entity snapshots, eliminating dirty-check cost and roughly halving entity memory footprint — a substantial win for large read queries. Spring's `@Transactional(readOnly=true)` propagates this hint to Hibernate.

### 5.7 Stateless session for ETL

```java
StatelessSession ss = sessionFactory.openStatelessSession();
Transaction tx = ss.beginTransaction();
ScrollableResults<LegacyRow> rows = ss.createQuery("from LegacyRow", LegacyRow.class)
    .scroll(ScrollMode.FORWARD_ONLY);
while (rows.next()) {
    LegacyRow r = rows.get();
    ss.insert(transform(r));   // direct INSERT, no persistence context, no dirty check, no cache
}
tx.commit();
ss.close();
```

A **`StatelessSession`** has **no persistence context**: no L1 cache, no dirty checking, no cascade, no lazy loading. Operations (`insert`, `update`, `delete`) map almost directly to SQL. It is the right tool for high-volume batch jobs where the ORM's bookkeeping is pure overhead. The tradeoff: you lose identity map, cascades, and automatic change tracking.

### 5.8 Bulk update/delete via JPQL (DML)

```java
@Transactional
public int markStale(Instant cutoff) {
    // Single UPDATE statement; does NOT load entities, does NOT run dirty checking
    return em.createQuery("update Order o set o.status = 'STALE' where o.createdAt < :cut")
             .setParameter("cut", cutoff)
             .executeUpdate();   // returns affected row count
}
```

Bulk DML bypasses the persistence context entirely — it does **not** update the version column unless you say so, does **not** cascade, and can leave **stale managed entities** in the L1 cache (call `em.clear()` after if you'll re-read). Fast, but you trade away ORM safety nets.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **N+1 selects** — the #1 ORM performance bug. Diagnose with SQL logging / statistics; fix with `join fetch`, entity graphs, or `@BatchSize` (Section 5.2).
- **Default `@ManyToOne` is EAGER.** This silently fetches associations you may not need, often via extra selects. Make `@ManyToOne`/`@OneToOne` **LAZY** by default and fetch explicitly per query.
- **Enable JDBC batching** (`hibernate.jdbc.batch_size`, `order_inserts`, `order_updates`) — off by default. Combine with `SEQUENCE` ids.
- **Bound persistence-context size** in loops with periodic `flush()/clear()`. Dirty checking is O(entities × properties) per flush.
- **Use read-only sessions/queries** for non-mutating paths to skip snapshots.
- **Avoid loading large collections** into memory; paginate, or use `ScrollableResults`/`Stream` with care (and a server-side cursor where supported).
- **Watch `findAll()`** in Spring Data on big tables — it loads everything. Prefer pageable queries.

### 6.2 Correctness & concurrency

- **`equals`/`hashCode` on entities.** A notorious trap. Do **not** base them on the auto-generated `@Id`, because the id is `null` before persist and changes after — this breaks `HashSet` membership across the lifecycle. Prefer a **business/natural key** or a UUID assigned at construction. If you must use the surrogate id, follow Vlad Mihalcea's pattern: constant `hashCode`, `equals` comparing id only when both non-null.
- **Lost updates** — prevent with `@Version` optimistic locking (Section 5.3) or pessimistic locks for hot rows.
- **The persistence context is NOT thread-safe.** Never share an `EntityManager`/`Session` across threads. Spring gives each thread its own via a transaction-scoped proxy.
- **`LazyInitializationException`** — touching a lazy association after the session closed. Fix by fetching what you need inside the transaction (don't extend the session into the view layer; the **Open-Session-In-View** anti-pattern, on by default in Spring Boot, masks this and harms performance — disable it: `spring.jpa.open-in-view=false`).

### 6.3 Memory

- Each managed entity holds itself **plus a snapshot** (≈2× footprint). Read-only halves this.
- Large `IN (...)` lists and unbounded result lists are common OOM sources.
- L2 cache stores entities in a dehydrated form; size it and monitor evictions.

### 6.4 Security

- **SQL injection**: JPQL/Criteria with bound parameters is safe. Never concatenate user input into HQL/native SQL; always `setParameter`.
- **Native queries** bypass JPQL translation — validate and parameterize them.
- **Mass assignment**: `merge` of a client-supplied entity can overwrite fields the user shouldn't control. Prefer explicit field copies (Section 5.5) or DTO → load-then-mutate.

### 6.5 Observability

- Turn on `hibernate.generate_statistics` in load tests and read `SessionFactory.getStatistics()` (query count, cache hit ratio, flush count, slowest queries).
- Use a JDBC-level proxy like **datasource-proxy** or **p6spy** to log actual SQL with bound params and timings — far better than `show_sql`.
- **Hibernate query plan cache** metrics, connection-pool (HikariCP) metrics, and `flushCount`/`entityLoadCount` are the key signals.
- Per-request "SQL statement count" assertions in tests catch N+1 regressions early.

### 6.6 Testing

- Assert SQL counts in integration tests (e.g. with **datasource-proxy** `assertSelectCount`) to lock in fetch behavior.
- Test against the **real DB** (Testcontainers) — H2 hides dialect-specific behavior (sequences, locking, constraint timing).
- Test flush-mode-dependent code explicitly; flush boundaries are where bugs hide.

### 6.7 Production hardening

- `hibernate.hbm2ddl.auto=none` (or `validate`) in production; manage schema with Flyway/Liquibase.
- `spring.jpa.open-in-view=false`.
- Default associations to `LAZY`; fetch deliberately.
- Set connection-pool timeouts shorter than transaction timeouts; set statement timeouts.
- Enable batching and `SEQUENCE` ids on write-heavy entities.

### 6.8 Anti-patterns to avoid

- Open-Session-In-View left on (default true) → lazy loads in the view, long-held connections.
- Using `IDENTITY` ids on bulk-insert tables (kills batching).
- Putting business logic inside `equals`/`hashCode` based on mutable/auto id.
- `merge` everywhere instead of load-then-mutate.
- Catching and ignoring `OptimisticLockException` (silently losing updates).
- One giant transaction for a million-row import (no flush/clear; OOM).
- `@OneToMany` with `EAGER` fetch on large collections (cartesian explosions on joins).

---

## 7. Advanced topics & deep internals

### 7.1 Dynamic update/insert and bytecode enhancement

- **`@DynamicUpdate`**: by default Hibernate's `UPDATE` lists *all* columns (because it precomputes one static SQL string per entity at startup, which is faster to prepare and reuses the statement cache). With `@DynamicUpdate`, Hibernate builds the `UPDATE` at flush listing only the changed columns. Useful for wide tables with rarely-changing columns or with DB triggers on specific columns, but it sacrifices statement-cache reuse and adds per-flush SQL generation.
- **`@DynamicInsert`**: similarly, only non-null columns are inserted (lets DB defaults apply).
- **Bytecode enhancement** (`hibernate-enhance-maven-plugin` / Gradle plugin) instruments entity classes to: (1) **track dirtiness inline** (no snapshot comparison), (2) support **lazy scalar attributes** and **lazy `@OneToOne`** properly, and (3) enable **dirty-tracking-based flush** which is cheaper for large contexts. It changes the dirty-check internals from "snapshot diff" to "self-reported dirty set."

### 7.2 The loaded-state snapshot and `@org.hibernate.annotations.OptimisticLocking`

The snapshot stored in each `EntityEntry` is the basis for both dirty checking and, with `OptimisticLockType.DIRTY`/`ALL`, version-less optimistic locking (the `WHERE` clause includes old column values instead of a version). `OptimisticLockType.ALL` puts every column in the `WHERE`; `DIRTY` puts only the changed ones. These trade a version column for wider WHERE clauses.

### 7.3 Second-level cache (L2) internals

- **L2** is a **shared, cross-session** cache living in the `SessionFactory`, backed by a provider (Ehcache, Caffeine, Infinispan, Redis via a region factory). It stores **dehydrated** entity state (a map of column values), **not** live objects — so each session rehydrates its own instance, preserving the per-session identity guarantee.
- **Concurrency strategies** (`@Cache(usage=...)`):
  - `READ_ONLY` — for immutable data; fastest, no invalidation needed.
  - `NONSTRICT_READ_WRITE` — allows brief staleness; evicts on update (no locking); cheap.
  - `READ_WRITE` — uses soft locks to maintain consistency across concurrent writes; more overhead.
  - `TRANSACTIONAL` — full JTA-backed; rare.
- **Query cache** (separate, `use_query_cache`) caches *query result id lists*, keyed by query + params; it relies on L2 for the actual entities and on an `UpdateTimestampsCache` to invalidate when underlying tables change. Easy to misuse; only enable for stable, frequently-repeated queries.

### 7.4 Cascade types and orphan removal

- **Cascade** propagates an operation across an association: `PERSIST`, `MERGE`, `REMOVE`, `REFRESH`, `DETACH`, `ALL`. E.g. `cascade=PERSIST` on `Order.items` means persisting the order persists its items.
- **`orphanRemoval=true`** deletes a child when it is removed from the parent's collection (not just when the parent is deleted). It is **not** the same as `cascade=REMOVE`: orphan removal fires on *dereferencing*, cascade-remove fires on *parent deletion*.

### 7.5 Flush ordering edge cases & insert ordering

Hibernate's deterministic flush order (Section 3.6) plus `hibernate.order_inserts=true` groups inserts **by entity type** to maximize batch size. But cyclic foreign keys (A references B, B references A) can still violate constraints at flush; remedies include deferrable constraints (`DEFERRABLE INITIALLY DEFERRED` in PostgreSQL/Oracle), nullable FKs with a second update, or restructuring the graph.

### 7.6 `getReference` and proxy initialization details

A proxy is a generated subclass holding a `LazyInitializer` and the id. Touching any non-id getter calls `LazyInitializer.initialize()`, which runs the SELECT *if the session is still open*. After the session closes, that call throws `LazyInitializationException`. Note: `instanceof` and `getClass()` on a proxy behave subtly differently from the real class — use `Hibernate.unproxy(x)` when you need the concrete instance, and avoid `getClass()` equality checks on entities.

### 7.7 `@NaturalId` and business-key lookups

`@NaturalId` marks an immutable business key (e.g. ISBN). Hibernate provides `session.bySimpleNaturalId(Book.class).load(isbn)` and a dedicated natural-id L2 cache region, letting you look up by business key with caching while keeping a surrogate PK.

### 7.8 Hibernate 5 → 6 changes worth flagging (version-specific)

- **Jakarta namespace**: Hibernate 6 / Spring Boot 3 use `jakarta.persistence.*` (not `javax.persistence.*`). This is a hard, breaking rename.
- **Hibernate 6 query engine** was rewritten (Semantic Query Model, SQM); HQL/Criteria handling and type system changed. Some custom dialect/type code from 5.x needs updating.
- **`save`/`update`/`saveOrUpdate` deprecated** in favor of JPA `persist`/`merge` in newer Hibernate. Prefer the JPA methods.
- Default `allocationSize` 50 and sequence-per-entity defaults differ subtly between major versions — verify against your version. **If unsure of an exact default for your version, check the docs rather than assume.**

---

## 8. Tradeoffs & decision frameworks

### 8.1 Hibernate vs alternatives

| Tool | Model | Strengths | Weaknesses | Use when |
|---|---|---|---|---|
| **Hibernate/JPA** | Full ORM, persistence context | Object graph, change tracking, caching, portability | Hidden SQL, N+1 traps, learning curve | Rich domain model, transactional CRUD, Spring apps |
| **jOOQ** | Typed SQL DSL | Full SQL control, type-safe, no magic | No identity map/dirty tracking; you write SQL | Complex queries, reporting, SQL-first teams |
| **Spring JDBC / `JdbcTemplate`** | Thin JDBC | Simple, predictable, fast | Manual mapping, no associations | Simple DAOs, bulk, hot paths |
| **MyBatis** | SQL mapper | SQL in XML/annotations, control | Manual mapping, no dirty tracking | SQL-centric with mapping convenience |
| **R2DBC + Spring Data R2DBC** | Reactive, no full ORM | Non-blocking | No persistence context, immature ORM features | Reactive stacks |

### 8.2 Identifier strategy decision

- **Use `SEQUENCE`** when the DB supports sequences (PostgreSQL, Oracle) and you write in bulk — enables batching.
- **Use `IDENTITY`** only when forced (MySQL without sequences, or legacy auto-increment) and you accept no insert batching.
- **Use UUID (v7/sortable)** for distributed id generation without DB round-trips, or to assign ids before insert; accept larger keys/index size.
- **Avoid `TABLE`** unless you need maximum portability and can tolerate its locking/round-trip cost.

### 8.3 persist vs merge vs load-then-mutate

- **persist**: new entities, same transaction. *Use when* the object was created here.
- **load-then-mutate**: partial updates of existing rows. *Use when* you have an id and a patch; avoids overwriting unmentioned columns. **Preferred default for updates.**
- **merge**: full-state apply of a detached graph. *Use when* you genuinely have a fully-populated detached object and want to upsert it. *Avoid when* the input is partial (risk of nulling columns).

### 8.4 Fetch strategy decision

| Need | Strategy |
|---|---|
| One association, known query | `join fetch` |
| Reusable fetch plan across queries | Entity graph |
| Many lazy parents, can't change query | `@BatchSize` / `@Fetch(SUBSELECT)` |
| Almost never need association | Keep LAZY, fetch on demand |
| Always need it, small/1:1 | Consider EAGER (carefully) |

### 8.5 Flush mode decision

- **AUTO** (default): correctness-first; keep it unless profiling says otherwise.
- **COMMIT**: read-heavy services that tolerate query staleness within a transaction.
- **MANUAL**: long conversational units of work spanning multiple requests; you control flush explicitly.

---

## 9. Failure modes & debugging

### 9.1 `LazyInitializationException`
- **Symptom:** `failed to lazily initialize a role collection: ..., could not initialize proxy - no Session`.
- **Cause:** touching a lazy association after the session/transaction closed (e.g. in the JSON serializer or view).
- **Diagnose:** find where the access happens vs where the transaction ends; check if `open-in-view` is masking it elsewhere.
- **Fix:** fetch what the caller needs inside the transaction (`join fetch`/entity graph), map to a DTO inside the transaction, or restructure boundaries. Do **not** set `enable_lazy_load_no_trans=true` (silent extra queries, connection leaks).

### 9.2 N+1 selects
- **Symptom:** one list query followed by hundreds of identical single-row selects in the SQL log.
- **Diagnose:** SQL logging (datasource-proxy/p6spy) or `Statistics.getQueryExecutionCount()`; SQL-count assertions in tests.
- **Fix:** Section 5.2.

### 9.3 `OptimisticLockException` / `StaleObjectStateException`
- **Symptom:** update fails because the version changed under you.
- **Cause:** concurrent modification (working as designed) — or a stale detached entity merged with an old version.
- **Fix:** retry the transaction (re-read, re-apply); for detached flows prefer load-then-mutate so the version is fresh.

### 9.4 `NonUniqueObjectException`
- **Symptom:** "a different object with the same identifier value was already associated with the session."
- **Cause:** `update`/`saveOrUpdate` (or merging then also having the original) with two instances of the same id in one context.
- **Fix:** use `merge` and use its return value; don't keep both instances managed.

### 9.5 Foreign-key constraint violations at flush
- **Symptom:** a `ConstraintViolationException` during commit even though your object graph "looks right."
- **Cause:** flush ordering vs cyclic FKs, or a child inserted before its parent due to graph shape.
- **Diagnose:** enable formatted SQL logging and read the exact statement order at flush.
- **Fix:** `order_inserts=true`, correct cascade setup, deferrable constraints, or split into ordered flushes.

### 9.6 "Why did this SQL run *here*?" / unexpected auto-flush
- **Symptom:** writes hit the DB earlier than expected (before a query) — see Section 3.9.
- **Cause:** AUTO flush mode flushing before an affected query.
- **Fix:** understand it's expected; use COMMIT/MANUAL deliberately if you need to defer; or reorder code.

### 9.7 `OutOfMemoryError` during a large operation
- **Cause:** unbounded persistence context (no flush/clear) or loading a huge result list.
- **Fix:** periodic flush/clear (Section 5.1), `StatelessSession`, scrolling/streaming with pagination.

### 9.8 Connection pool exhaustion / long-held connections
- **Cause:** Open-Session-In-View holding a connection through view rendering; long transactions.
- **Diagnose:** HikariCP metrics (`active`, `pending`, `connectionTimeout` errors).
- **Fix:** `open-in-view=false`, short transactions, `provider_disables_autocommit=true` so Hibernate acquires connections lazily.

### 9.9 Real-world incident patterns
- A page that worked at 10 rows in dev fell over at 10k in prod: a lazy `@ManyToOne` accessed in a loop → N+1 → thousands of queries → DB CPU saturation. Fixed with `join fetch`.
- A nightly import OOM'd after a code change introduced `IDENTITY` ids on the imported entity, disabling batching and ballooning the context. Reverted to `SEQUENCE` + flush/clear.
- Intermittent "lost balance updates" traced to swallowed `OptimisticLockException` in a `try/catch` that logged and continued. Fixed by retrying the unit of work.

### 9.10 Debugging toolkit recap
- **datasource-proxy / p6spy** — real SQL + bound params + timings.
- **`hibernate.generate_statistics` + `Statistics`** — counts, cache ratios, slow queries.
- **`hibernate.format_sql` / `show_sql`** — quick SQL peek (dev only).
- **Testcontainers** — test on the real database.
- **`Session.getStatistics()` / flush counts** — verify flush behavior.

---

## 10. Interview drill

**Q1. What is the persistence context and why is it called the first-level cache?**
*Model answer:* It's an in-memory, session-scoped map of managed entities keyed by type+PK. It guarantees the identity map (one object per row per session), serves repeated `find` calls without SQL (hence "first-level cache"), and is the scope within which dirty checking operates by comparing each entity to its loaded snapshot.
- *Probe: Can you disable it?* No — it's always on; you can only `evict`/`clear`.
- *Probe: How does it differ from L2?* L1 is per-session, never shared, stores live objects; L2 is in the `SessionFactory`, shared across sessions, stores dehydrated state.
- *Probe: What memory cost does it add?* Roughly 2× per entity due to the snapshot; read-only mode skips the snapshot.

**Q2. Walk me through the four entity states and the transitions.**
*Model answer:* Transient (new, untracked, no row), Managed/Persistent (in context, tracked, dirty-checked), Detached (was managed, context ended, untracked but has id), Removed (managed but scheduled for delete). `persist` transient→managed, `remove` managed→removed, `detach/clear/close` managed→detached, `merge` detached→managed copy. Only managed entities are dirty-checked.
- *Probe: What happens if you mutate a detached entity?* Nothing to the DB until you `merge` it.
- *Probe: After `remove` + commit, what state?* Effectively transient; the row is gone.

**Q3. Explain dirty checking. Does Hibernate intercept setters?**
*Model answer:* By default no — at flush it compares each managed entity's current field values to the loaded snapshot stored in its `EntityEntry`; differing properties make it dirty and queue an `UPDATE`. Cost is O(entities × properties) per flush. Bytecode enhancement changes this to inline self-reported dirty tracking.
- *Probe: How to reduce the cost?* Read-only sessions, bounded context size, bytecode enhancement.
- *Probe: How are collection changes detected?* Via persistent collection wrappers (`PersistentBag`/`Set`) that track adds/removes.

**Q4. What's the difference between flush and commit?**
*Model answer:* Flush sends the queued SQL to the DB within the current transaction (action queue drained in insert→update→delete order); commit makes it durable. A rollback after a flush still undoes it. AUTO flush mode also flushes before queries whose results pending changes could affect.
- *Probe: When does auto-flush happen?* Before commit and before affected queries (AUTO).
- *Probe: What order are statements executed?* Orphan removals, inserts, updates, collection ops, deletes — fixed for FK safety.

**Q5. IDENTITY vs SEQUENCE — what's the performance difference and why?** *(senior-signal)*
*Model answer:* With IDENTITY the id is only known after the INSERT, so Hibernate must execute the INSERT immediately on `persist`, which prevents JDBC batch inserts. With SEQUENCE Hibernate fetches ids ahead (optionally pooled, `allocationSize`=50), so inserts can be deferred to flush and batched. For bulk writes this is the difference between thousands of round-trips and a handful.
- *Probe: How do sequence optimizers work?* `pooled`/`pooled-lo` reserve a block per `nextval`; the DB `INCREMENT BY` must match `allocationSize` or you get collisions/gaps.
- *Probe: MySQL?* No real sequences pre-8 and even 8 lacks JPA sequences; you're often stuck with IDENTITY or use a TABLE/UUID strategy.

**Q6. persist vs merge — when and why?** *(senior-signal)*
*Model answer:* `persist` makes a transient entity managed (INSERT); use for newly created objects in the same transaction. `merge` copies state from a transient/detached object into a managed copy and **returns** that copy — the argument stays detached — and typically issues a SELECT first. Use merge for detached graphs; but for partial updates prefer load-then-mutate to avoid clobbering unmentioned columns.
- *Probe: Common merge bug?* Not using the return value.
- *Probe: Why does merge SELECT?* To load current state to copy onto and dirty-check.

**Q7. What causes `LazyInitializationException` and how do you fix it properly?**
*Model answer:* Accessing a lazy association/proxy after the session closed. Fix by fetching needed data inside the transaction (join fetch / entity graph) or mapping to DTOs inside the boundary; disable Open-Session-In-View; never use `enable_lazy_load_no_trans`.
- *Probe: Why is OSIV bad?* Holds DB connections through view rendering and hides N+1.
- *Probe: What is a proxy exactly?* A generated subclass with a LazyInitializer that loads on first non-id getter.

**Q8. Optimistic vs pessimistic locking — when do you choose which?** *(senior-signal)*
*Model answer:* Optimistic (`@Version`) assumes conflicts are rare: no locks during think-time; detect at write by version mismatch (0 rows updated → exception → retry). Pessimistic (`SELECT ... FOR UPDATE`) holds a DB lock for the transaction; use under high contention or when retries are expensive/unsafe, accepting reduced concurrency and deadlock risk.
- *Probe: How does `@Version` work in SQL?* Adds `AND version=?` to the WHERE and sets `version+1`.
- *Probe: Risk with detached merge + version?* Stale version → spurious optimistic failures.

**Q9. Explain the N+1 problem and three different fixes.**
*Model answer:* 1 parent query + N child queries from lazy navigation. Fixes: `join fetch` (eager join in the query), entity graphs (reusable fetch plan), `@BatchSize`/`@Fetch(SUBSELECT)` (bounded follow-up selects).
- *Probe: Why not just make everything EAGER?* Cartesian products, over-fetching, and you lose per-query control.
- *Probe: How detect in CI?* SQL-count assertions / statistics.

**Q10. What does a flush actually do internally, step by step?**
*Model answer:* `flushEverythingToExecutions` runs dirty checking and cascades, populating the `ActionQueue`; `performExecutions` drains it in fixed order (inserts→updates→deletes with collection ops), batching where enabled; `postFlush` refreshes snapshots so the next dirty check has a clean baseline. Flush is within the transaction, not commit.
- *Probe: Why the fixed order?* To satisfy FK constraints automatically.
- *Probe: What enables batching here?* `jdbc.batch_size` + `order_inserts/updates` + non-IDENTITY ids.

**Q11. Why is Open-Session-In-View on by default in Spring Boot, and would you keep it?** *(senior-signal)*
*Model answer:* It's on to make lazy loading "just work" in the view, easing development. I'd disable it in production because it holds the persistence context (and a DB connection) open across view rendering, hides N+1, and couples the web layer to lazy loading. I'd fetch deliberately and map to DTOs inside the service boundary.

**Q12. How would you efficiently insert one million rows with Hibernate?**
*Model answer:* Use `SEQUENCE` ids with a pooled optimizer; enable `jdbc.batch_size` + `order_inserts`; loop with periodic `flush()/clear()` to bound the context; or use a `StatelessSession`/JDBC batch for max throughput. Avoid IDENTITY (no batching) and never hold all entities in one context.

---

## 11. Glossary

- **Action queue** — Hibernate's internal ordered buffer of pending insert/update/delete/collection actions (the write-behind list).
- **Allocation size** — number of ids reserved per sequence call when using pooled optimizers (default 50).
- **Bytecode enhancement** — build-time instrumentation of entities for inline dirty tracking and true lazy scalars.
- **Cascade** — propagation of an operation (persist/merge/remove/etc.) across an association.
- **Detached** — an entity that was managed but whose persistence context has ended; untracked but has an id.
- **Dialect** — Hibernate's abstraction of database-specific SQL syntax and capabilities.
- **Dirty checking** — detecting changed entities by comparing current state to the loaded snapshot at flush.
- **DTO (Data Transfer Object)** — a plain object used to carry data across boundaries, decoupled from entities.
- **Eager / Lazy fetching** — load an association immediately (eager) vs on first access (lazy).
- **EntityEntry** — per-managed-entity record (status, snapshot, persister) in the persistence context.
- **EntityManager / Session** — short-lived, non-thread-safe unit-of-work gateway owning the persistence context.
- **EntityManagerFactory / SessionFactory** — heavyweight, thread-safe, app-scoped factory holding the metamodel and caches.
- **EntityPersister** — per-entity-type object that generates and executes that entity's SQL.
- **Entity graph** — a reusable, declarative fetch plan.
- **First-level cache (L1)** — the persistence context as a per-session entity cache.
- **Flush** — synchronizing the persistence context to the DB by executing queued SQL (not commit).
- **Flush mode** — policy for when auto-flush happens (AUTO/COMMIT/ALWAYS/MANUAL).
- **Hydration** — building entity state from a JDBC result set.
- **Identity map** — the guarantee that one DB row maps to one object instance per session.
- **Impedance mismatch** — the structural disagreements between object and relational models.
- **IDENTITY / SEQUENCE / TABLE / UUID** — primary-key generation strategies.
- **JDBC** — the JVM's low-level relational database API.
- **JPA (Jakarta Persistence API)** — the ORM specification Hibernate implements.
- **JPQL / HQL** — object-oriented query languages over entities (JPA's JPQL; Hibernate's HQL superset).
- **LazyInitializationException** — thrown when a lazy association is accessed after the session closed.
- **Managed / Persistent** — an entity in the persistence context that Hibernate tracks.
- **Merge** — copy a transient/detached entity's state into a managed copy (returned).
- **N+1 problem** — 1 query plus N follow-up queries from lazy navigation in a loop.
- **Optimistic locking** — concurrency control via a `@Version` column detecting conflicts at write.
- **Open-Session-In-View (OSIV)** — keeping the persistence context open through view rendering (default-on Spring anti-pattern).
- **Orphan removal** — deleting a child entity when removed from its parent collection.
- **Pessimistic locking** — holding a DB lock (`SELECT ... FOR UPDATE`) for the transaction.
- **Persist** — make a transient entity managed (schedule INSERT).
- **Persistence context** — the session-scoped managed-entity map; the L1 cache.
- **Proxy** — a generated subclass standing in for a lazily-loaded entity until first access.
- **Removed** — a managed entity scheduled for deletion at flush.
- **Second-level cache (L2)** — optional shared, cross-session cache in the SessionFactory storing dehydrated state.
- **Snapshot (loaded state)** — copy of an entity's field values at load time, used for dirty checking.
- **StatelessSession** — a session with no persistence context (no cache/dirty-check/cascade) for batch work.
- **Transaction** — an all-or-nothing, isolated group of DB statements.
- **Transient** — a new, untracked object with no DB identity.
- **Unit of work** — the scope of one persistence context: open → change → flush → commit → close.
- **Write-behind** — buffering writes in memory and applying them to the DB later in batches.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one-screen recap)

**States:** Transient (new) → `persist` → Managed → `remove` → Removed; Managed → `detach/clear/close` → Detached → `merge` → Managed copy. Only **Managed** is dirty-checked.

**Two big ideas:** (1) persistence context = session-scoped identity map + L1 cache + dirty-check scope; (2) write-behind action queue flushed in **insert → update → delete** order.

**Flush ≠ commit.** AUTO flushes before commit *and* before affected queries. Modes: AUTO (default), COMMIT, ALWAYS, MANUAL.

**Ids:** IDENTITY → INSERT runs on `persist`, **no batching**. SEQUENCE → id pre-fetched, INSERT deferred, **batches** (allocationSize default 50). Prefer SEQUENCE for bulk.

**persist vs merge:** persist = new in this tx; merge = detached graph, **use the return value**; partial update → **load-then-mutate**.

**Batching config:** `hibernate.jdbc.batch_size` (e.g. 50, default off) + `order_inserts=true` + `order_updates=true`; combine with SEQUENCE + periodic `flush()/clear()`.

**Defaults to remember:** `@ManyToOne`/`@OneToOne` fetch = **EAGER** (make LAZY); `@OneToMany`/`@ManyToMany` = **LAZY**; `@Column` length = 255; OSIV = **on** in Spring Boot (turn **off**); hbm2ddl = use `none/validate` in prod.

**Perf wins:** kill N+1 (`join fetch`/entity graph/`@BatchSize`); read-only sessions skip snapshots; bound context size; SEQUENCE + batching; disable OSIV.

**Concurrency:** `@Version` (optimistic, retry on conflict) vs `PESSIMISTIC_WRITE` (`FOR UPDATE`, hold lock). Never base entity `equals/hashCode` on the auto id.

**Debug:** datasource-proxy/p6spy for real SQL; `generate_statistics` for counts/cache ratios; Testcontainers for real-DB behavior.

### Self-test (no answers)

1. You call `em.persist(x)` on an entity whose id is `GenerationType.IDENTITY`. Exactly when does the INSERT execute, and why does this preclude batch inserts?
2. In one transaction you change a managed entity, then run a JPQL query against the same table. With default flush mode, what SQL runs and in what order — and what changes if the flush mode is COMMIT?
3. Explain why `merge` typically issues a SELECT, and describe a case where using `merge` instead of load-then-mutate causes data loss.
4. Your nightly import OOMs after someone "optimized" the id strategy. Walk through the two independent root causes and the fixes.
5. A REST endpoint throws `LazyInitializationException` only in production. List the likely cause involving a Spring default, and three correct fixes (and one fix you'd reject).
6. Describe the exact internal order in which Hibernate drains the action queue during a flush, and explain how that order prevents a foreign-key violation when inserting a parent and child together.
7. Two threads withdraw from the same account concurrently with `@Version`. Trace the SQL each runs and explain precisely how the second one's flush fails.

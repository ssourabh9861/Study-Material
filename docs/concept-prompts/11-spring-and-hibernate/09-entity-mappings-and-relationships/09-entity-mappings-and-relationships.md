# Entity Mappings & Relationships (JPA / Hibernate)

> An exhaustive engineering-handbook chapter for senior JVM backend developers. It builds from first principles to deep internals, covers the full annotation toolkit, gives many worked examples, and ends with an interview drill, glossary, and self-test.

---

## 1. Overview & where it fits

**What it is.** *Entity mapping* is the discipline of describing — usually with annotations — how Java objects (your *domain model*) correspond to relational database rows, columns, and foreign keys. *Relationship mapping* is the subset that describes how associations between objects (`Order` has many `LineItem`s, `Employee` has one `Manager`) map onto the join columns and join tables that encode those associations in SQL.

This is the core of **Object-Relational Mapping (ORM)** — the technique of bridging the *object model* (graphs of objects connected by references, with inheritance and polymorphism) and the *relational model* (flat tables connected by foreign keys, with no inheritance). The mismatch between these two worlds is famously called the **object-relational impedance mismatch**: objects have identity by reference and navigate via pointers, while rows have identity by primary key and "navigate" via joins. ORM exists to paper over that mismatch.

**The problem it solves.** Without ORM you write JDBC by hand: open connections, build SQL strings, set parameters, iterate `ResultSet`s, and manually stitch rows back into object graphs. That is verbose, error-prone, and couples your code tightly to SQL. ORM lets you say "save this `Order` and its line items" and have the framework figure out the `INSERT` statements, foreign keys, and ordering. **JPA (Jakarta Persistence API, formerly Java Persistence API)** is the *specification* — a set of standard annotations and interfaces. **Hibernate** is the most widely used *implementation* of that spec (others include EclipseLink and OpenJPA). Spring Data JPA sits one layer above, generating repository implementations, but the mapping rules below are pure JPA/Hibernate.

> **JDBC** = Java Database Connectivity, the low-level standard API for talking to SQL databases. **JPA** is a higher-level abstraction built on top of JDBC. **Spring Data JPA** generates repositories on top of JPA. Hibernate is the engine doing the actual SQL generation underneath JPA.

**When you reach for it.** You use entity mappings whenever you persist a rich domain model to a relational store and want navigability ("give me this order's customer's address") plus automatic dirty-tracking and change flushing. You reach *past* it (toward JDBC, jOOQ, or MyBatis) when you need hand-tuned SQL, bulk operations, reporting queries, or you simply have a thin data layer where the object graph adds no value.

**One-paragraph mental model.** Think of Hibernate as a *write-behind cache and graph synchronizer* sitting between your objects and the database. Within a unit of work (a **persistence context**, usually one transaction), you load entities into a managed first-level cache; Hibernate tracks every change you make; and at *flush* time it computes the minimal set of `INSERT`/`UPDATE`/`DELETE` statements — in a dependency-correct order — to make the database match the in-memory object graph. Relationships are just metadata telling Hibernate which foreign key or join table encodes each association, which side "owns" (controls) that foreign key, and what to do (cascade, orphan-remove) when the graph changes.

---

## 2. Foundations from first principles

### 2.1 The four building blocks

1. **Entity** — a Java class mapped to a table; each instance corresponds (potentially) to one row. Marked `@Entity`.
2. **Identifier (primary key)** — every entity needs an `@Id`. This is the entity's database identity.
3. **Attribute/column mapping** — fields map to columns (`@Column`, or by convention).
4. **Association** — a reference from one entity to another, backed by a foreign key (FK) or join table.

### 2.2 The persistence lifecycle: entity states

A JPA entity instance is always in exactly one of four states. Understanding these is non-negotiable — most "why didn't my change save?" bugs come from a misunderstanding here.

| State | Meaning | Tracked by persistence context? | In DB? |
|---|---|---|---|
| **Transient (new)** | Freshly `new`'d object, never persisted | No | No |
| **Managed (persistent)** | Associated with a persistence context; changes are auto-tracked | Yes | Yes (or will be on flush) |
| **Detached** | Was managed, but the persistence context closed (or `detach()` called) | No | Yes |
| **Removed** | Marked for deletion (`remove()`); `DELETE` issued on flush | Yes | Until flush/commit |

> **Persistence context** = the in-memory set of managed entities for a unit of work, plus Hibernate's first-level cache and dirty-checking machinery. In Spring, it is normally bound to the current `@Transactional` method via the `EntityManager`. It guarantees that within one context, the same database row maps to exactly **one** Java object — this is the **identity guarantee** and it's why `entityA == entityB` works for two loads of the same row inside one transaction.

State transitions:

```
            persist()                       commit/flush
 TRANSIENT ───────────▶ MANAGED ──────────────────────────▶ (row in DB)
     ▲                   │   ▲                                   
     │                   │   │ merge()                           
     │            remove()   │                                   
     │                   ▼   │                                   
     │                REMOVED │                                  
     │                       │                                   
     └──────── (GC) ◀── DETACHED ◀── close()/clear()/detach()    
```

- `persist(e)` — transient → managed; schedules an `INSERT`.
- `remove(e)` — managed → removed; schedules a `DELETE`.
- `merge(e)` — copies state from a detached instance onto a managed one and returns the *managed* copy (the argument stays detached — a classic trap).
- `detach(e)` / `clear()` / closing the `EntityManager` — managed → detached.
- `find()` / `getReference()` — loads a managed instance.

### 2.3 Minimal entity

```java
import jakarta.persistence.*;   // Jakarta namespace (Spring Boot 3+, Hibernate 6+)

@Entity                         // marks this class as a JPA entity
@Table(name = "app_user")       // explicit table name; otherwise defaults to "User" -> "user" or "User"
public class User {

    @Id                                                  // primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // DB auto-increment column
    private Long id;

    @Column(nullable = false, length = 100)              // NOT NULL VARCHAR(100)
    private String email;

    private String displayName;   // no @Column -> defaults: column "display_name", nullable

    // JPA requires a no-arg constructor (can be protected) so it can instantiate via reflection
    protected User() {}

    public User(String email) { this.email = email; }

    // getters/setters omitted for brevity
}
```

> **`javax.persistence` vs `jakarta.persistence`:** Up to Java EE 8 / Spring Boot 2.x the package was `javax.persistence`. With the move to Jakarta EE 9+, it became `jakarta.persistence`. Spring Boot 3 and Hibernate 6 use `jakarta.*`. This is a hard, breaking rename — code does not compile against the wrong one. Everything in this doc uses `jakarta.*`; for Boot 2.x mentally substitute `javax.*`.

### 2.4 Naming conventions (where do column names come from?)

If you don't specify `@Column(name=...)`, Hibernate derives the column name from the field name via a **physical naming strategy**. Hibernate's default (`CamelCaseToUnderscoresNamingStrategy` in Spring Boot) turns `displayName` into `display_name`. Plain Hibernate (no Spring) historically left it as `displayName`. **This is a frequent source of "table or column not found" errors when moving between plain Hibernate and Spring Boot.** Always be explicit if your schema is fixed.

---

## 3. How it works internally

This section is the heart of the document. We trace what Hibernate actually does, in order.

### 3.1 Bootstrap: building the metamodel

1. **Configuration scan.** On startup, Hibernate (via JPA's `Persistence.createEntityManagerFactory` or Spring's `LocalContainerEntityManagerFactoryBean`) scans for `@Entity` classes.
2. **Metadata build.** For each entity it constructs a `PersistentClass` / `EntityPersister` — an object describing the table, columns, identifier, associations, fetch strategies, and a generated SQL template for CRUD.
3. **Type resolution.** Each attribute is bound to a Hibernate `Type` (a `BasicType` like `StringType`, or an association type like `OneToOneType`, `ManyToOneType`, `CollectionType`).
4. **SessionFactory creation.** The immutable, thread-safe `SessionFactory` (JPA: `EntityManagerFactory`) is produced. It is expensive to build and should be a singleton for the app's lifetime.

> **`SessionFactory` vs `Session`:** The `SessionFactory` (`EntityManagerFactory`) is a heavyweight, thread-safe, application-scoped object — create one. A `Session` (`EntityManager`) is a lightweight, **not** thread-safe, short-lived object representing one persistence context / unit of work — create one per request/transaction. In Spring you almost never create either by hand; you inject an `EntityManager` and Spring binds the right one to the transaction.

### 3.2 Loading an entity (read path)

When you call `em.find(Order.class, 42L)`:

1. **First-level cache check.** Hibernate checks the persistence context. If the entity is already managed there, it returns the *same* instance — no SQL. This is the identity guarantee.
2. **Second-level cache check (if enabled).** If a shared L2 cache is configured for the entity, Hibernate checks it.
3. **SQL `SELECT`.** Otherwise Hibernate issues a `SELECT` using the persister's template, binds the id, executes via JDBC.
4. **Hydration.** The `ResultSet` row is read into a flat array of column values called the **hydrated state** (a.k.a. the entity's *loaded state* / snapshot).
5. **Instantiation & population.** Hibernate instantiates the entity (via the no-arg constructor + reflection or bytecode), populates fields, and stores a **snapshot** of the loaded state in the persistence context for later dirty-checking.
6. **Association handling.** For `@ManyToOne`/`@OneToOne` set to `EAGER`, the associated row is fetched (often via a join). For `LAZY` associations and lazy collections, Hibernate inserts a **proxy** (for single associations) or a **persistent collection wrapper** (for collections) — see §3.5.

### 3.3 Dirty checking (the magic of "I just set a field and it saved")

Because Hibernate stored a *snapshot* of every loaded entity, at flush time it compares each managed entity's current field values against its snapshot, attribute by attribute. Any attribute that differs makes the entity *dirty*. Hibernate then generates an `UPDATE` for exactly the changed columns (or, by default, all columns — see `@DynamicUpdate` in §7). You never call `update()` on a managed entity; mutation alone is enough.

```java
@Transactional
public void renameUser(Long id) {
    User u = em.find(User.class, id);  // managed; snapshot taken
    u.setDisplayName("New Name");      // just a setter — no save() call
}                                      // on transaction commit, Hibernate flushes -> UPDATE app_user SET display_name=? WHERE id=?
```

> **Flush** = the act of synchronizing the in-memory persistence context with the database by issuing the pending SQL. It does **not** commit the transaction. The default `FlushModeType.AUTO` triggers a flush before query execution (to make queries see your pending changes) and at commit.

### 3.4 The flush algorithm and write ordering

At flush time Hibernate executes actions in a fixed, dependency-aware order (regardless of the order you called `persist`/`remove`):

1. `OrphanRemovalAction`s
2. all entity `INSERT`s (in insertion order; with `IDENTITY` ids they happen immediately on `persist`)
3. all entity `UPDATE`s
4. collection removals
5. collection updates/recreations
6. collection element inserts
7. all entity `DELETE`s

This ordering exists so foreign-key constraints aren't violated (parents inserted before children, children deleted before parents). **A famous consequence:** if you delete a parent and insert a different child referencing the same unique value, the ordering can still produce a constraint violation — sometimes you must `flush()` manually in between.

> **Constraint violation** here means the database rejects a statement because it breaks a rule like a foreign key or unique index. Hibernate's ordering minimizes these but cannot eliminate every case.

### 3.5 Proxies and lazy loading internals

For a `LAZY` `@ManyToOne` association, Hibernate does **not** load the target. Instead it puts a **proxy** in the field: a runtime-generated subclass (historically via CGLIB, now ByteBuddy) that holds only the id. The first time you call any non-id method on the proxy, it triggers a `SELECT` to *initialize* it. If the persistence context is already closed at that point, you get the infamous `LazyInitializationException`.

For lazy collections (`@OneToMany`, `@ManyToMany`), the field holds a Hibernate **persistent collection** (`PersistentBag`, `PersistentSet`, `PersistentList`) — a wrapper implementing `List`/`Set` that initializes (runs the `SELECT`) on first access (`.size()`, iteration, etc.).

> **Proxy** = a stand-in object that looks like the real entity but defers loading. **ByteBuddy/CGLIB** = bytecode libraries Hibernate uses to generate proxy subclasses at runtime. **`LazyInitializationException`** = thrown when you touch an uninitialized proxy/collection after its persistence context (Session) is gone — e.g., in the web layer after the transaction ended.

### 3.6 How a relationship maps to SQL — the central concept of *owning side*

A relationship in the object model is bidirectional (both objects reference each other) or unidirectional (only one does). But in SQL, an association is encoded by **one** mechanism: a foreign key column (or a join table). Somebody has to "own" that mechanism — i.e., control what gets written to the FK. That somebody is the **owning side**.

- The **owning side** is the side *without* `mappedBy`. It holds the `@JoinColumn`/`@JoinTable`. Hibernate reads the owning side's state to decide what FK value to write.
- The **inverse (non-owning) side** is the side *with* `mappedBy = "fieldOnOwningSide"`. Hibernate **ignores** changes made only to the inverse side when computing SQL.

**Rule of thumb for who owns what:**
- `@ManyToOne` is *always* the owning side (it sits on the table with the FK column — the "many" table).
- In a bidirectional `@OneToMany`/`@ManyToOne`, the `@ManyToOne` side owns; the `@OneToMany` side must use `mappedBy`.
- In `@OneToOne`, the side with the `@JoinColumn` owns.
- In `@ManyToMany`, you pick an owner with `@JoinTable`; the other uses `mappedBy`.

This is the single most common relationship bug: you set only the inverse side and nothing persists, because the owning side's FK was never updated. See §3.7 and §5.

### 3.7 Why bidirectional consistency helpers exist

Because Hibernate only consults the owning side, but your *in-memory graph* should be internally consistent (both directions agree), you write helper methods that update both sides at once:

```java
@Entity
public class Order {
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LineItem> items = new ArrayList<>();

    // consistency helper: keeps BOTH sides in sync
    public void addItem(LineItem item) {
        items.add(item);          // inverse side (collection) - for in-memory navigation
        item.setOrder(this);      // OWNING side (FK) - this is what Hibernate actually persists
    }
    public void removeItem(LineItem item) {
        items.remove(item);
        item.setOrder(null);      // breaks the FK; with orphanRemoval=true this triggers DELETE
    }
}
```

If you skipped `item.setOrder(this)`, the `order_id` FK on `line_item` would be left null and the insert might fail (or the row would dangle). Always set the owning side.

---

## 4. The complete toolkit

### 4.1 Class- and table-level annotations

| Annotation | Purpose | Key parameters | Notes / defaults |
|---|---|---|---|
| `@Entity` | Marks a persistent class | `name` (JPQL entity name) | Requires `@Id` and no-arg constructor |
| `@Table` | Maps to a table | `name`, `schema`, `catalog`, `uniqueConstraints`, `indexes` | Defaults to entity name |
| `@Column` | Maps a field to a column | `name`, `nullable`, `length` (def 255), `precision`, `scale`, `unique`, `insertable`, `updatable`, `columnDefinition` | |
| `@Id` | Marks the primary key | — | Mandatory |
| `@GeneratedValue` | PK generation | `strategy` (AUTO/IDENTITY/SEQUENCE/TABLE/UUID), `generator` | See §4.4 |
| `@Basic` | Marks a basic attribute | `fetch` (EAGER def), `optional` | Rarely needed explicitly |
| `@Transient` | Field NOT persisted | — | |
| `@Enumerated` | Maps an enum | `EnumType.STRING` or `ORDINAL` | **Always use STRING** (ORDINAL breaks on reorder) |
| `@Temporal` | Legacy `java.util.Date`/`Calendar` precision | `DATE`/`TIME`/`TIMESTAMP` | Not needed for `java.time.*` (use those!) |
| `@Lob` | Large object (CLOB/BLOB) | — | |
| `@Version` | Optimistic-lock version column | — | int/long/short/Timestamp |
| `@Access` | Field vs property access | `AccessType.FIELD`/`PROPERTY` | Inferred from `@Id` placement |

### 4.2 Relationship annotations

| Annotation | Cardinality | Owning side? | Companion annotations |
|---|---|---|---|
| `@OneToOne` | 1 ↔ 1 | side with `@JoinColumn` (or `@MapsId`) | `@JoinColumn`, `@PrimaryKeyJoinColumn`, `@MapsId`, `mappedBy` on inverse |
| `@ManyToOne` | many → 1 | always owning | `@JoinColumn` |
| `@OneToMany` | 1 → many | inverse (use `mappedBy`); unidirectional variant uses `@JoinColumn` | `@JoinColumn`, `@OrderBy`, `@OrderColumn`, `@MapKey` |
| `@ManyToMany` | many ↔ many | side with `@JoinTable` | `@JoinTable`, `mappedBy` on inverse |
| `@ElementCollection` | entity → collection of embeddables/basics | n/a (always owned) | `@CollectionTable`, `@Column` |

### 4.3 Relationship attribute reference

| Attribute | Applies to | Meaning | Default |
|---|---|---|---|
| `mappedBy` | `@OneToMany`, `@OneToOne`, `@ManyToMany` (inverse) | Names the owning field; marks this side inverse | none (= owning) |
| `cascade` | all | Which operations propagate to the target | none (empty) |
| `fetch` | all | `EAGER` or `LAZY` | `@ToOne`: EAGER; `@ToMany`: LAZY |
| `orphanRemoval` | `@OneToOne`, `@OneToMany` | Delete child when removed from collection / dereferenced | `false` |
| `optional` | `@ToOne` | Whether FK can be null (affects join type) | `true` |
| `targetEntity` | all | Explicit target class (for raw generics) | inferred |

### 4.4 ID generation strategies

| Strategy | SQL mechanism | Batch-insert friendly? | Notes |
|---|---|---|---|
| `IDENTITY` | DB auto-increment column | **No** — disables JDBC batching for inserts (Hibernate must fetch generated key per row) | MySQL default; simplest |
| `SEQUENCE` | DB sequence object | **Yes** | Best for PostgreSQL/Oracle; use `@SequenceGenerator` + `allocationSize` (default 50) for pre-allocation |
| `TABLE` | A separate "hi/lo" table | Yes | Portable but slow/contention-prone; avoid |
| `AUTO` | Provider picks | varies | Hibernate 6 picks SEQUENCE where supported |
| `UUID` (JPA 3.1) | App-generated UUID | Yes | `@GeneratedValue(strategy = UUID)`; random UUIDv4 hurts index locality |

> **JDBC batching** = sending many `INSERT`/`UPDATE` statements to the DB in one round-trip. `IDENTITY` defeats it because Hibernate needs each row's generated id back immediately, so it can't batch. `SEQUENCE` with `allocationSize` lets Hibernate grab a block of ids up front and batch the inserts.

### 4.5 Embeddables, keys, inheritance, helpers

| Annotation | Purpose |
|---|---|
| `@Embeddable` | Marks a value type embedded into the owner's table |
| `@Embedded` | Field holding an `@Embeddable` |
| `@AttributeOverride(s)` | Rename embedded columns when reused |
| `@EmbeddedId` | Composite key as an `@Embeddable` |
| `@IdClass` | Composite key via a separate id class (fields duplicated on entity) |
| `@MapsId` | Share the parent's PK as this entity's PK (great for `@OneToOne`) |
| `@Inheritance` | Inheritance strategy (`SINGLE_TABLE`/`JOINED`/`TABLE_PER_CLASS`) |
| `@DiscriminatorColumn` / `@DiscriminatorValue` | Type discriminator for SINGLE_TABLE/JOINED |
| `@MappedSuperclass` | Shared mapped fields without being an entity itself |
| `@SecondaryTable` | Split one entity across two tables |
| `@JoinColumn` / `@JoinColumns` | FK column(s) for an association |
| `@JoinTable` | Join table for `@ManyToMany`/unidirectional `@OneToMany` |
| `@OrderBy` | Sort a collection at load time via SQL `ORDER BY` |
| `@OrderColumn` | Persist list order in a dedicated index column |
| `@NaturalId` | Marks a business key (Hibernate-specific) |

### 4.6 Cascade types

| `CascadeType` | Propagates which `EntityManager` op |
|---|---|
| `PERSIST` | `persist()` |
| `MERGE` | `merge()` |
| `REMOVE` | `remove()` |
| `REFRESH` | `refresh()` |
| `DETACH` | `detach()` |
| `ALL` | all of the above |

> **Cascade** = "when I do X to the parent, also do X to the associated children." `orphanRemoval` is *different*: it deletes a child when it is *disassociated* from the parent (removed from a collection or its reference nulled), independent of any cascade. `CascadeType.REMOVE` only deletes children when the *parent* is deleted.

---

## 5. Code examples by use case

### 5.1 `@ManyToOne` + bidirectional `@OneToMany` (the bread-and-butter)

Scenario: an `Order` has many `LineItem`s. The FK `order_id` lives on `line_item`.

```sql
-- Schema
CREATE TABLE orders (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  placed_at TIMESTAMP NOT NULL
);
CREATE TABLE line_item (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id  BIGINT NOT NULL,
  sku       VARCHAR(64) NOT NULL,
  qty       INT NOT NULL,
  CONSTRAINT fk_li_order FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant placedAt = Instant.now();

    // INVERSE side: mappedBy points to the 'order' field on LineItem (the owner)
    @OneToMany(mappedBy = "order",
               cascade = CascadeType.ALL,   // persist/remove children with the order
               orphanRemoval = true)        // removing from list => DELETE the line item
    private List<LineItem> items = new ArrayList<>();

    public void addItem(LineItem li) { items.add(li); li.setOrder(this); }   // sync both sides
    public void removeItem(LineItem li) { items.remove(li); li.setOrder(null); }
    // getters/setters...
}

@Entity
@Table(name = "line_item")
public class LineItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // OWNING side: this @ManyToOne controls the order_id FK
    @ManyToOne(fetch = FetchType.LAZY, optional = false)  // LAZY: don't load order unless needed
    @JoinColumn(name = "order_id", nullable = false)      // the FK column
    private Order order;

    private String sku;
    private int qty;
    // getters/setters...
}
```

```java
@Transactional
public Long placeOrder() {
    Order o = new Order();
    LineItem li = new LineItem();
    li.setSku("ABC-123"); li.setQty(2);
    o.addItem(li);          // sets li.order = o  (owning side) AND adds to list
    em.persist(o);          // cascade PERSIST also inserts li
    return o.getId();
}
```

**Why `fetch = LAZY` on `@ManyToOne`?** The JPA default for `@ToOne` is `EAGER`, which silently fires extra `SELECT`s every time you load a `LineItem`. On a hot path that's a hidden N+1. Best practice: make *all* associations `LAZY` and fetch explicitly when needed (join fetch / entity graph).

### 5.2 `@OneToOne` sharing a primary key with `@MapsId`

Scenario: every `User` has exactly one `UserProfile`. Instead of a separate FK column, the profile *is* the user — they share the PK. This is the cleanest 1:1 mapping.

```java
@Entity
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true,
              fetch = FetchType.LAZY)
    private UserProfile profile;   // inverse side
}

@Entity
public class UserProfile {
    @Id
    private Long id;               // SAME value as user.id (no @GeneratedValue)

    @MapsId                        // tells JPA: use the associated user's PK as my PK
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id")       // FK == PK
    private User user;             // owning side

    private String bio;
}
```

```sql
CREATE TABLE user_profile (
  id  BIGINT PRIMARY KEY,                              -- both PK and FK
  bio VARCHAR(1000),
  CONSTRAINT fk_profile_user FOREIGN KEY (id) REFERENCES app_user(id)
);
```

> **Why `@MapsId` over a plain `@JoinColumn` FK?** A naive `@OneToOne` with its own FK column lets the inverse side become "lazy but always-fetched" because Hibernate can't tell if the optional side is null without querying — so it eagerly loads it even when marked LAZY (a well-known Hibernate limitation). Sharing the PK with `@MapsId` removes that problem and saves a column.

### 5.3 `@ManyToMany` with a join table

Scenario: `Student` ↔ `Course`. Many students take many courses.

```java
@Entity
public class Student {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})  // NOT REMOVE - see note
    @JoinTable(name = "student_course",
        joinColumns        = @JoinColumn(name = "student_id"),       // this side's FK
        inverseJoinColumns = @JoinColumn(name = "course_id"))        // other side's FK
    private Set<Course> courses = new HashSet<>();                   // Set, not List! (see §7.6)

    public void enroll(Course c) { courses.add(c); c.getStudents().add(this); }
    public void drop(Course c)   { courses.remove(c); c.getStudents().remove(this); }
}

@Entity
public class Course {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToMany(mappedBy = "courses")   // inverse side
    private Set<Student> students = new HashSet<>();
}
```

> **Never put `CascadeType.REMOVE` on `@ManyToMany`.** Deleting a student should not delete the courses (other students take them). Cascade-remove on a many-to-many is almost always a data-loss bug.

**When the join table needs its own columns** (e.g., `enrolled_at`, `grade`), `@ManyToMany` is no longer enough — promote it to a first-class entity with two `@ManyToOne`s:

```java
@Entity
public class Enrollment {
    @EmbeddedId
    private EnrollmentId id;                       // composite PK (student_id, course_id)

    @ManyToOne @MapsId("studentId") @JoinColumn(name="student_id")
    private Student student;
    @ManyToOne @MapsId("courseId")  @JoinColumn(name="course_id")
    private Course course;

    private Instant enrolledAt;
    private String grade;
}

@Embeddable
public class EnrollmentId implements Serializable {  // composite key MUST be Serializable + equals/hashCode
    private Long studentId;
    private Long courseId;
    @Override public boolean equals(Object o){ /* by both fields */ return /*...*/ false; }
    @Override public int hashCode(){ return Objects.hash(studentId, courseId); }
}
```

This "association entity" pattern is the standard way to attach attributes to a many-to-many.

### 5.4 Embeddable value type

Scenario: an `Address` is not an entity (no identity of its own) but a *value* embedded into `Customer`'s table.

```java
@Embeddable
public class Address {
    @Column(name = "street")
    private String street;
    @Column(name = "city")
    private String city;
    @Column(name = "zip", length = 10)
    private String zip;
}

@Entity
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded                                  // address columns live in customer table
    private Address shippingAddress;

    @Embedded
    @AttributeOverrides({                       // reuse Address, but rename columns to avoid clash
        @AttributeOverride(name="street", column=@Column(name="bill_street")),
        @AttributeOverride(name="city",   column=@Column(name="bill_city")),
        @AttributeOverride(name="zip",    column=@Column(name="bill_zip"))
    })
    private Address billingAddress;
}
```

Both addresses are stored as columns in the single `customer` table — no joins, no extra identity. Embeddables are great for cohesive value objects (money, address, geo-point).

### 5.5 Composite primary key with `@EmbeddedId`

Scenario: a legacy table keyed by `(country_code, postal_code)`.

```java
@Embeddable
public class RegionId implements Serializable {
    private String countryCode;
    private String postalCode;
    protected RegionId() {}
    public RegionId(String c, String p){ countryCode=c; postalCode=p; }
    @Override public boolean equals(Object o){ /* compare both */ return /*...*/ false; }
    @Override public int hashCode(){ return Objects.hash(countryCode, postalCode); }
}

@Entity
public class Region {
    @EmbeddedId
    private RegionId id;
    private String name;
}
```

`@EmbeddedId` is generally preferred over `@IdClass` because the key is a single cohesive object you can pass to `em.find(Region.class, new RegionId("IN","560001"))`.

### 5.6 Inheritance: `SINGLE_TABLE`

Scenario: `Payment` hierarchy — `CardPayment`, `BankTransfer`. All in one table with a discriminator.

```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "payment_type", discriminatorType = DiscriminatorType.STRING)
public abstract class Payment {
    @Id @GeneratedValue private Long id;
    private BigDecimal amount;
}

@Entity @DiscriminatorValue("CARD")
public class CardPayment extends Payment {
    private String last4;        // becomes a NULLABLE column (only set for card rows)
}

@Entity @DiscriminatorValue("BANK")
public class BankTransfer extends Payment {
    private String iban;         // also nullable
}
```

One `payment` table holds all subtypes; the `payment_type` column says which. Fast (no joins) but subclass-specific columns must be nullable — you cannot enforce `NOT NULL` on `last4`.

### 5.7 Inheritance: `JOINED`

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class Payment { @Id @GeneratedValue Long id; BigDecimal amount; }

@Entity
@PrimaryKeyJoinColumn(name = "id")
public class CardPayment extends Payment { String last4; }  // its own table, PK = FK to payment
```

Each class gets its own table; loading a `CardPayment` joins `payment` + `card_payment`. Normalized and `NOT NULL`-capable, but every read/write touches multiple tables.

### 5.8 Unidirectional `@OneToMany` with `@JoinColumn` (and why to avoid the join-table default)

```java
@Entity
public class Department {
    @Id @GeneratedValue Long id;

    @OneToMany
    @JoinColumn(name = "department_id")   // forces FK on the child table instead of a join table
    private List<Employee> employees = new ArrayList<>();
}
```

Without `@JoinColumn`, a *unidirectional* `@OneToMany` historically creates a **join table** (`department_employees`) — surprising and inefficient. Adding `@JoinColumn` puts the FK on `employee` directly. Even so, a *bidirectional* mapping (employee has `@ManyToOne Department`) is usually better because the owning `@ManyToOne` issues cleaner SQL.

---

## 6. Implementation concerns & best practices

### 6.1 Performance: the N+1 select problem

The defining ORM performance trap. You load 100 orders, then loop and touch each order's `customer` (a lazy `@ManyToOne`) — Hibernate fires 1 query for orders + 100 for customers = N+1 queries.

Fixes:
- **`JOIN FETCH`** in JPQL: `select o from Order o join fetch o.customer`.
- **Entity graphs** (`@NamedEntityGraph` or `EntityGraph` hint) to declare what to fetch eagerly per query.
- **`@BatchSize(size = N)`** (Hibernate) — initializes lazy associations in batches of N (turns N+1 into N/batch + 1).
- **`hibernate.default_batch_fetch_size`** global setting.
- **DTO projections** — for read-only views, select straight into a DTO and skip entities entirely.

> **N+1** is named for the query count: 1 to get the parents, N to get each parent's association. The cure is always "fetch the association in bulk."

### 6.2 Never use `EAGER`; fetch is a per-query decision

`fetch = EAGER` is a *global* declaration that bites you on every query, including ones that don't need the association, and it can produce huge cartesian-product joins when multiple eager collections are present (the `MultipleBagFetchException` / "duplicate rows" problem). Make everything `LAZY` and use join fetch / entity graphs where needed.

### 6.3 `equals()` / `hashCode()` — the detached-entity trap

This deserves its own treatment because it is subtle and frequently wrong.

**The problem.** You put entities in a `HashSet` (e.g., a `@OneToMany Set`). For the set to behave, `equals`/`hashCode` must be stable across the entity's whole lifecycle — including before and after it gets an id.

- **Don't use the generated `id` in `hashCode`.** A transient entity has `id == null`; you add it to a set; you persist it; Hibernate assigns an id; now its `hashCode` changed, and it's *lost* in the set (the bucket no longer matches).
- **Don't use all fields** (Lombok `@Data` / `@EqualsAndHashCode` with no exclusions) — that pulls in lazy associations (triggering loads / `LazyInitializationException`) and makes equality change as the entity mutates.
- **Don't rely on default identity (`Object.equals`)** if entities cross persistence-context boundaries — two loads of the same row in *different* sessions are different instances.

**The recommended patterns:**

1. **Best: a business/natural key.** If the entity has an immutable, unique business key (e.g., an order UUID assigned at creation, an ISBN), use that for `equals`/`hashCode`. Stable forever.

```java
@Entity
public class Order {
    @Id @GeneratedValue Long id;

    @NaturalId @Column(updatable = false, unique = true, nullable = false)
    private UUID businessKey = UUID.randomUUID();  // assigned at construction, never changes

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return businessKey != null && businessKey.equals(other.businessKey);
    }
    @Override public int hashCode() { return Objects.hash(businessKey); }
}
```

2. **If no natural key: use the id but with a *constant* hashCode.** equals checks the id (with a null guard); hashCode returns a class-stable constant. This keeps the set correct (just less efficient — all entries land in one bucket, which is fine for the small collections entities usually live in).

```java
@Override public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false; // proxy-safe
    Order other = (Order) o;
    return id != null && id.equals(other.id);    // null id => only equal to itself (this==o above)
}
@Override public int hashCode() {
    return getClass().hashCode();                 // or a constant; stable across id assignment
}
```

> **`Hibernate.getClass(this)`** unwraps proxies: a lazy proxy's runtime class is a generated subclass (`Order$HibernateProxy$xyz`), so `this.getClass() == other.getClass()` fails when comparing a proxy to a real instance. `Hibernate.getClass()` returns the true entity class on both, making proxy-vs-real comparisons work.

### 6.4 Bidirectional consistency (recap as a best practice)

Always provide `addX`/`removeX` helpers that set both sides, and always mutate the **owning** side. Treat raw collection access as a smell.

### 6.5 Cascade & orphanRemoval discipline

- `CascadeType.ALL + orphanRemoval = true` is correct for **true parent-child composition** (an order *owns* its line items). Use it there.
- It is **wrong** for **shared references** (a post's `Author` — deleting a post must not delete the author). Use no cascade or only `PERSIST`/`MERGE`.
- `orphanRemoval` implies the child cannot live without the parent; only use on composition.

### 6.6 Security: mass assignment & projection

Never bind HTTP request bodies directly onto entities (`@RequestBody Order`). An attacker can set fields you didn't intend (mass-assignment / over-posting). Use a request DTO and map deliberately. Likewise, don't serialize entities straight to JSON — lazy proxies blow up Jackson, and you leak internal fields. Use response DTOs.

### 6.7 Observability

- Turn on SQL logging in dev: `spring.jpa.show-sql` (crude) or, better, the logger `org.hibernate.SQL=DEBUG` + `org.hibernate.orm.jdbc.bind=TRACE` (Hibernate 6) for bound parameters.
- Use **Hibernate statistics** (`hibernate.generate_statistics=true`) to count queries, cache hits, and detect N+1.
- Tools: **datasource-proxy** or **p6spy** to log + assert query counts in tests.

### 6.8 Testing

- Assert query counts to catch N+1 regressions (datasource-proxy + a count assertion).
- Test with a real database (Testcontainers spinning up Postgres/MySQL) rather than H2 — dialect differences (sequence behavior, default columns, casing) bite in prod.
- Test detached-entity flows (serialize/deserialize across the session boundary) to surface `equals`/`hashCode` and lazy bugs.

### 6.9 Production hardening

- Set **batch insert/update** sizes (`hibernate.jdbc.batch_size=30..50`, `order_inserts=true`, `order_updates=true`) and use `SEQUENCE` ids so batching actually works.
- Always set a **statement timeout** at the JDBC/DB level so a runaway query can't hold a connection forever.
- Use **`@Version`** for optimistic locking on contended aggregates.

### 6.10 Anti-patterns checklist

- EAGER everywhere → N+1 and cartesian explosions.
- `@Data`/all-field `equals` on entities → lazy-load storms.
- id-based `hashCode` → set corruption after persist.
- Bidirectional set with no consistency helper → FK nulls, dangling rows.
- `@ManyToMany` with extra columns → silently lost; should be an association entity.
- `@ManyToMany` with `CascadeType.REMOVE` → data loss.
- `List` for `@ManyToMany` → delete-all-and-reinsert on every change (use `Set`).
- Entities as request/response payloads → mass assignment + serialization blowups.

---

## 7. Advanced topics & deep internals

### 7.1 `@DynamicUpdate` / `@DynamicInsert`

By default Hibernate uses a *static*, precompiled SQL `UPDATE` that sets **all** columns, even unchanged ones. `@DynamicUpdate` makes Hibernate generate SQL at runtime including only dirty columns. Useful for wide tables, tables with triggers/audit columns, or when you have partial unique indexes. Cost: SQL is generated per flush (slightly more CPU, no statement caching benefit). `@DynamicInsert` similarly omits null columns from `INSERT`.

### 7.2 Bytecode enhancement & lazy basic properties

Without bytecode enhancement, `@Basic(fetch = LAZY)` on a scalar column (e.g., a big `@Lob`) is *ignored* — Hibernate can't intercept field access to lazy-load a single column. With the **Hibernate bytecode enhancer** (a Gradle/Maven plugin that instruments your entity classes at build time), Hibernate can lazy-load individual attributes and do **dirty-tracking via instrumentation** instead of snapshot comparison (faster, less memory). Enhancement also enables `@LazyToOne` without proxies.

> **Bytecode enhancement** = modifying compiled `.class` files to insert interception hooks (e.g., field-access tracking). Hibernate offers a build-time plugin; the alternative is runtime proxy subclassing.

### 7.3 The `LazyInitializationException` and "Open Session in View"

When a controller renders an entity after the transaction closed, touching a lazy field throws `LazyInitializationException`. Spring Boot's default **Open-Session-In-View (OSIV)** keeps the `EntityManager` open for the whole request to hide this — but it is widely considered an anti-pattern: it holds a DB connection across the view-render phase and masks N+1. **Disable it** (`spring.jpa.open-in-view=false`) and fetch what you need inside the service-layer transaction (join fetch / entity graphs / DTOs).

### 7.4 Second-level cache (L2)

The first-level cache is the persistence context (per-session). The **second-level cache** is shared across sessions/transactions (e.g., backed by Ehcache, Caffeine, Infinispan, Hazelcast). Enabled per-entity with `@Cache` and a `CacheConcurrencyStrategy` (`READ_ONLY`, `NONSTRICT_READ_WRITE`, `READ_WRITE`, `TRANSACTIONAL`). Great for reference data; dangerous for write-heavy or rapidly-changing entities (staleness, invalidation cost).

### 7.5 `@OrderColumn` vs `@OrderBy`

- `@OrderBy("name ASC")` sorts the collection *at load time* using SQL `ORDER BY`; order is not persisted.
- `@OrderColumn(name="position")` *persists* the list index in a dedicated column so insertion order survives. Beware: reordering can trigger many `UPDATE`s, and gaps/shifts are handled by full rewrites.

### 7.6 Why `Set` beats `List` for `@ManyToMany`

A `@ManyToMany` mapped as a `List` (a `PersistentBag`) has no stable identity per row, so when you add/remove one element, Hibernate often **deletes all join-table rows and re-inserts** the survivors — O(n) churn per change. A `Set` (`PersistentSet`) issues a single targeted `INSERT`/`DELETE`. Same applies to unidirectional `@OneToMany` with a join table.

### 7.7 `@MappedSuperclass` vs `@Entity` inheritance

`@MappedSuperclass` shares *mappings* (e.g., a base class with `id`, `createdAt`, `updatedAt`) but is **not** itself queryable and produces no table or polymorphic queries. Use it for "DRY columns" (auditing base class). Use `@Inheritance` only when you need polymorphic queries (`select p from Payment p`).

### 7.8 `@Formula`, `@Generated`, derived/computed columns

Hibernate's `@Formula("subselect or expression")` maps a read-only computed value via SQL. `@Generated(GenerationTime.INSERT/ALWAYS)` re-reads DB-computed columns (defaults, triggers) after write. Both are Hibernate-specific.

### 7.9 `getReference()` vs `find()`

`em.getReference(Order.class, id)` returns a **proxy** without hitting the DB — useful to set a `@ManyToOne` FK without loading the parent (`li.setOrder(em.getReference(Order.class, orderId))`). `find()` loads now (or returns null if absent); `getReference()` defers and throws `EntityNotFoundException` on first access if the row doesn't exist.

### 7.10 Cascade ordering & the orphan-removal flush gotcha

`orphanRemoval` deletes happen at flush. If you remove a child from a collection and add a new child with the same unique key in the same flush, the insert may run before the delete (per §3.4 ordering) and violate the unique constraint. Workaround: explicit `flush()` between, or design keys to avoid collision.

### 7.11 Filtering & soft deletes

Hibernate `@SQLDelete` + `@Where`/`@SQLRestriction` implement soft delete (mark a `deleted` flag instead of physical delete and filter it out). Hibernate 6.4 added a first-class `@SoftDelete`. `@Filter` provides parameterized, session-activated filtering (e.g., multi-tenant row filtering).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Inheritance strategy comparison

| Aspect | SINGLE_TABLE | JOINED | TABLE_PER_CLASS |
|---|---|---|---|
| Tables | 1 | 1 per class (root + each subclass) | 1 per concrete class (no shared root table) |
| Read of one subtype | Fastest (no join) | Join root+subclass | Single table, but… |
| Polymorphic query (`select p from Payment p`) | 1 query, fast | Joins/unions | **UNION ALL** across all subclass tables (slow) |
| Subclass `NOT NULL` columns | **Impossible** (must be nullable) | Yes | Yes |
| Normalization | Poor (sparse table) | Best | Duplicated columns per table |
| FK from outside to base type | Easy | Easy | **Hard** (no single base table to reference) |
| Recommended? | **Default choice** for small hierarchies | When you need NOT NULL / normalization | **Avoid** unless special reason |

> **`UNION ALL`** = a SQL operator that stacks the rows of multiple `SELECT`s. `TABLE_PER_CLASS` polymorphic queries must union every concrete table, which scales poorly and can't share an id sequence cleanly.

**Rule:** Default to `SINGLE_TABLE`. Switch to `JOINED` when subclasses have many distinct, NOT-NULL columns or you need referential integrity. Avoid `TABLE_PER_CLASS`.

### 8.2 Composite key: `@EmbeddedId` vs `@IdClass`

| | `@EmbeddedId` | `@IdClass` |
|---|---|---|
| Key as an object | Yes (cohesive) | No (fields duplicated on entity) |
| `find()` ergonomics | `find(E.class, new KeyObj(...))` | `find(E.class, new IdClass(...))` |
| JPQL referencing | `e.id.countryCode` | `e.countryCode` |
| Recommendation | **Preferred** | Legacy/when you want flat field access |

### 8.3 Entity vs embeddable vs `@ElementCollection`

| Use… | When |
|---|---|
| `@Entity` | The thing has its own identity & lifecycle, is referenced by others, or queried independently |
| `@Embeddable` | A cohesive value with no identity, owned wholly by one entity (Money, Address) |
| `@ElementCollection` | A collection of basics/embeddables owned by one entity (tags, phone numbers) with no need for sharing or independent identity |

### 8.4 ORM vs alternatives

| Need | Reach for |
|---|---|
| Rich domain graph, navigation, dirty tracking | JPA/Hibernate |
| Hand-tuned SQL, type-safe queries, no entity overhead | jOOQ |
| Explicit SQL in XML/annotations, fine control | MyBatis |
| Simple row mapping, bulk, reporting | Spring `JdbcTemplate` / `JdbcClient` |
| Bulk `UPDATE`/`DELETE` over many rows | JPQL bulk ops or plain SQL (ORM row-by-row is too slow) |

---

## 9. Failure modes & debugging

### 9.1 `LazyInitializationException`
**Cause:** touched a lazy proxy/collection after the Session closed.
**Diagnose:** stack trace points to the field access in the view/serialization layer.
**Fix:** fetch inside the transaction (`JOIN FETCH`, entity graph), or return a DTO. Do **not** "fix" by enabling OSIV or making it EAGER.

### 9.2 N+1 selects
**Symptom:** one logical operation fires hundreds of `SELECT`s; latency spikes under load.
**Diagnose:** enable `org.hibernate.SQL=DEBUG` and `hibernate.generate_statistics=true`; count queries. Tools: p6spy, datasource-proxy, Hypersistence/JPA query-count assertions in tests.
**Fix:** join fetch / `@BatchSize` / `default_batch_fetch_size` / DTO projections.

### 9.3 `MultipleBagFetchException`
**Cause:** join-fetching two `List`-typed (bag) collections at once produces a cartesian product Hibernate refuses.
**Fix:** use `Set`s, or fetch one collection per query (Hibernate 6 can fetch multiple bags but with cartesian-product warnings — still fetch in separate queries or use `@BatchSize`).

### 9.4 Detached-entity set corruption
**Symptom:** `set.contains(entity)` returns false for an entity you just added/persisted; duplicates appear.
**Cause:** id-based `hashCode` changed after id assignment, or proxy-vs-instance class mismatch in `equals`.
**Fix:** business-key equals/hashCode or constant hashCode + null-guarded id equals + `Hibernate.getClass()` (§6.3).

### 9.5 "Detached entity passed to persist" / duplicate inserts
**Cause:** calling `persist()` on a detached entity (one that already has an id from a previous session), or re-persisting after `merge`.
**Fix:** use `merge()` for detached instances; remember `merge` returns the managed copy — keep using that, not the argument.

### 9.6 `ObjectOptimisticLockingFailureException`
**Cause:** two transactions updated the same `@Version`-stamped row; the second's version check failed.
**Fix:** expected with optimistic locking — retry the unit of work or surface a conflict to the user.

### 9.7 FK constraint violations on delete
**Cause:** deleting a parent that still has children, or flush-ordering (§3.4 / §7.10).
**Diagnose:** read the DB error (which constraint). Check cascade/orphanRemoval config.
**Fix:** add the right cascade, or `flush()` between conflicting operations, or `ON DELETE CASCADE` at the DB level (with care).

### 9.8 Wrong column/table name at startup
**Cause:** naming-strategy mismatch (camelCase vs snake_case) between plain Hibernate and Spring Boot.
**Fix:** be explicit with `@Table`/`@Column`, or pin the naming strategy.

### 9.9 Real-world incident pattern
A common production story: a dashboard endpoint that "was fast in dev" melts in prod. Root cause: a lazy `@ManyToOne` on a list of 5,000 rows produced 5,001 queries (N+1), each cheap in dev's local DB but, at 1ms network RTT to a remote prod DB, 5 seconds of serial round-trips that exhausted the connection pool. Fix was a single `JOIN FETCH`. The lesson: **always assert query counts in tests** and **never trust dev-local timings for query-fan-out bugs.**

---

## 10. Interview drill

**Q1. What is the owning side of a relationship and why does it matter?**
*Model answer:* The owning side is the one whose state Hibernate reads to decide what foreign key/join-table rows to write; it holds the `@JoinColumn`/`@JoinTable` and is the side *without* `mappedBy`. It matters because Hibernate ignores changes made only to the inverse side — set only the inverse and nothing persists.
- *Follow-up: Which side owns in a bidirectional one-to-many?* The `@ManyToOne` side (it has the FK column); the `@OneToMany` uses `mappedBy`.
- *Follow-up: How do you keep both sides consistent?* `addX/removeX` helper methods that mutate both, always setting the owning side.
- *Follow-up: Can the inverse side ever affect SQL?* Only via cascade/orphanRemoval triggered through it; the actual FK value still comes from the owning side.

**Q2. Explain the N+1 problem and three ways to fix it.**
*Model answer:* Loading N parents then lazily touching an association fires 1 + N queries. Fixes: `JOIN FETCH`, entity graphs, `@BatchSize`/`default_batch_fetch_size`, or DTO projections.
- *Follow-up: Why not just make it EAGER?* EAGER applies to *every* query, causes cartesian products with multiple collections, and you lose per-query control.
- *Follow-up: How do you detect it in CI?* Assert query counts with datasource-proxy/p6spy.

**Q3. Why is id-based `hashCode()` dangerous for entities?**
*Model answer:* A transient entity has a null id; once persisted, the generated id changes its hashCode, so it's lost in any `HashSet` it was added to before persisting. Use a business key, or a constant hashCode with null-guarded id equals.
- *Follow-up: Why null-guard the id in equals?* So a transient entity is only equal to itself (`this == o`), avoiding two distinct unsaved entities testing equal.
- *Follow-up: Why `Hibernate.getClass()`?* To compare proxies and real instances by their true entity class.

**Q4. Compare SINGLE_TABLE, JOINED, TABLE_PER_CLASS. (senior-signal)**
*Model answer:* See §8.1 table. Default SINGLE_TABLE (fast, but nullable subclass columns); JOINED for normalization/NOT NULL at the cost of joins; avoid TABLE_PER_CLASS (UNION polymorphic queries, FK problems).
- *Follow-up: When would you actually pick JOINED?* Many distinct NOT-NULL subclass columns, or external FKs need referential integrity and you can't tolerate a sparse table.
- *Follow-up: What's the discriminator column for and which strategies need it?* It records the row's concrete type; required for SINGLE_TABLE, optional/derivable for JOINED, n/a for TABLE_PER_CLASS.

**Q5. What's the difference between `cascade = REMOVE` and `orphanRemoval = true`? (senior-signal)**
*Model answer:* `REMOVE` deletes children when the **parent** is deleted; `orphanRemoval` deletes a child the moment it is **disassociated** from the parent (removed from the collection or its reference nulled), even if the parent lives on. Use both only for true composition.
- *Follow-up: Give a case where you want orphanRemoval but not cascade-remove of shared refs.* Order/LineItem (composition) yes; Post/Author (shared) no.
- *Follow-up: Why is cascade-remove on `@ManyToMany` a bug?* It would delete shared targets that other owners still reference.

**Q6. What is the persistence context and what guarantees does it give?**
*Model answer:* The per-unit-of-work set of managed entities + first-level cache + dirty tracking. Guarantees identity (one row → one object instance) and write-behind dirty flushing.
- *Follow-up: When does it flush?* Before queries (AUTO) and at commit; or on explicit `flush()`.
- *Follow-up: How does Spring bind it?* To the `@Transactional` method via a thread-bound `EntityManager`.

**Q7. Why is `fetch = LAZY` the recommended default and what are its hazards?**
*Model answer:* Lazy avoids fetching data you don't need and prevents accidental N+1/cartesian explosions. Hazard: `LazyInitializationException` if accessed outside the session; mitigate by fetching in the transaction or returning DTOs.
- *Follow-up: Is `@ManyToOne` lazy by default?* No — `@ToOne` defaults to EAGER; you must set LAZY.
- *Follow-up: Can a lazy `@OneToOne` really be lazy?* On the non-owning/optional side Hibernate often can't, because it must query to know if it's null — `@MapsId` avoids that.

**Q8. How do you model a many-to-many that needs extra attributes? (senior-signal)**
*Model answer:* Promote the join table to an association entity with a composite key (`@EmbeddedId` + two `@MapsId @ManyToOne`s) and put the extra columns there. `@ManyToMany` can't carry attributes.
- *Follow-up: Why composite key with `@MapsId`?* The pair (a,b) is naturally unique and shares the parents' PKs without an extra surrogate.
- *Follow-up: Why `Set` not `List` for `@ManyToMany`?* List/bag triggers delete-all-reinsert on changes; Set issues targeted DML.

**Q9. What does `merge()` do and what's the common trap?**
*Model answer:* `merge` copies a detached entity's state onto a managed instance and returns that managed instance. Trap: the argument stays detached — you must use the returned object.
- *Follow-up: Difference from `persist`?* `persist` is for transient entities and makes the *argument* managed; `merge` is for detached and returns a different managed copy.
- *Follow-up: When does merge cascade?* Per `CascadeType.MERGE` on associations.

**Q10. What is Open-Session-In-View and why is it controversial?**
*Model answer:* OSIV keeps the EntityManager open for the whole HTTP request so lazy access in the view doesn't throw. Controversial because it holds a DB connection through view rendering and hides N+1; best disabled with explicit fetching.
- *Follow-up: Default in Spring Boot?* On (with a warning logged).
- *Follow-up: Alternative?* Fetch needed data in the service transaction; return DTOs.

**Q11. How does Hibernate decide which columns to UPDATE? (senior-signal)**
*Model answer:* By default it compares each managed entity to its loaded snapshot (dirty checking) but issues a *static* UPDATE of all columns. `@DynamicUpdate` makes it emit only changed columns at the cost of per-flush SQL generation.
- *Follow-up: How does snapshot dirty-checking affect memory?* It doubles per-entity state; bytecode enhancement replaces it with cheaper instrumentation tracking.
- *Follow-up: When is `@DynamicUpdate` worth it?* Wide tables, audit triggers, partial indexes, or to reduce contention on hot columns.

**Q12. How do composite keys interact with equals/hashCode?**
*Model answer:* The `@EmbeddedId`/`@IdClass` class must be `Serializable` and implement `equals`/`hashCode` over all key parts; Hibernate uses them for identity and map lookups.
- *Follow-up: What breaks if you omit them?* `find()`/cache lookups and collection behavior break unpredictably.
- *Follow-up: Mutable composite key fields?* Avoid — keys should be immutable.

---

## 11. Glossary

- **ORM (Object-Relational Mapping):** Technique/framework mapping object graphs to relational tables.
- **JPA (Jakarta Persistence API):** The standard specification for ORM in Java.
- **Hibernate:** The leading JPA implementation; also has a native (non-JPA) API.
- **JDBC:** Low-level Java SQL API; ORM is built on it.
- **Entity:** A persistent class mapped to a table; instances map to rows.
- **Embeddable / value type:** A class embedded into an owner's table; has no identity of its own.
- **Persistence context:** Per-unit-of-work managed-entity set + first-level cache + dirty tracker.
- **`EntityManager` / `Session`:** The handle to one persistence context; not thread-safe.
- **`EntityManagerFactory` / `SessionFactory`:** Heavyweight, thread-safe factory; one per app.
- **First-level cache (L1):** The persistence context's per-session identity cache.
- **Second-level cache (L2):** Shared cross-session cache (Ehcache/Infinispan/etc.).
- **Managed / transient / detached / removed:** The four entity lifecycle states (§2.2).
- **Flush:** Synchronizing pending changes to the DB without committing.
- **Dirty checking:** Detecting changed entities by comparing to a loaded snapshot.
- **Owning side:** The relationship side that controls the FK/join table (no `mappedBy`).
- **Inverse side:** The mirror side; declares `mappedBy`; ignored when computing FK SQL.
- **`mappedBy`:** Marks the inverse side and names the owning field.
- **Cascade:** Propagation of an EntityManager op from parent to associated entities.
- **`orphanRemoval`:** Delete a child when disassociated from its parent.
- **Proxy:** A deferred-loading stand-in for an entity (generated by ByteBuddy/CGLIB).
- **`LazyInitializationException`:** Thrown when touching an uninitialized proxy/collection after the session closed.
- **N+1 problem:** 1 parent query + N child queries; an ORM performance trap.
- **`JOIN FETCH` / entity graph:** Mechanisms to eagerly fetch associations per query.
- **`@BatchSize`:** Hibernate hint to initialize lazy associations in batches.
- **Discriminator column:** Stores a row's concrete subtype in SINGLE_TABLE/JOINED inheritance.
- **`@MappedSuperclass`:** Shares mappings without being a queryable entity.
- **`@MapsId`:** Shares the parent's PK as the child's PK (great for 1:1).
- **Composite key:** A primary key made of multiple columns (`@EmbeddedId`/`@IdClass`).
- **Natural / business key:** An immutable domain-unique identifier; ideal for equals/hashCode.
- **`@Version` / optimistic locking:** Version column used to detect concurrent updates.
- **JDBC batching:** Sending many statements in one round-trip; needs SEQUENCE ids.
- **Identity / sequence / table generators:** PK generation strategies (§4.4).
- **OSIV (Open-Session-In-View):** Keeps the session open across the whole request.
- **`@DynamicUpdate`/`@DynamicInsert`:** Generate SQL with only changed/non-null columns.
- **Bytecode enhancement:** Build-time instrumentation enabling lazy attributes & cheaper dirty tracking.
- **`PersistentBag`/`PersistentSet`/`PersistentList`:** Hibernate's lazy collection wrappers.
- **`UNION ALL`:** SQL operator stacking rows; used by TABLE_PER_CLASS polymorphic queries.
- **Impedance mismatch:** The structural gap between object and relational models.
- **DTO (Data Transfer Object):** A flat object used to ferry data across boundaries; preferred over exposing entities.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **States:** transient → (persist) → managed → (commit) DB; managed → (detach/close) detached → (merge) managed; managed → (remove) removed.
- **Defaults:** `@ToOne` = EAGER (override to LAZY!); `@ToMany` = LAZY; `@Column.length` = 255; `allocationSize` for SEQUENCE = 50; cascade = none; orphanRemoval = false.
- **Owning side:** the one **without** `mappedBy`; holds `@JoinColumn`/`@JoinTable`. `@ManyToOne` always owns. Hibernate persists from the owning side only → always update it.
- **Consistency helpers:** `addX/removeX` set BOTH sides.
- **Cascade vs orphanRemoval:** REMOVE = when parent deleted; orphanRemoval = when child disassociated. Use both only for composition.
- **equals/hashCode:** business key, or null-guarded id equals + constant hashCode + `Hibernate.getClass()`. Never id-based hashCode; never all-field.
- **Many-to-many:** use `Set`; no `CascadeType.REMOVE`; extra columns ⇒ association entity with `@EmbeddedId` + `@MapsId`.
- **Inheritance:** default SINGLE_TABLE; JOINED for NOT-NULL/normalization; avoid TABLE_PER_CLASS.
- **Perf:** all LAZY + `JOIN FETCH`/entity graph/`@BatchSize`/DTOs; SEQUENCE ids + `batch_size` for batching; disable OSIV.
- **Composite key:** `@EmbeddedId` preferred; must be Serializable + equals/hashCode.
- **1:1:** prefer `@MapsId` (shared PK) over a separate FK column.
- **Debug:** `org.hibernate.SQL=DEBUG`, `generate_statistics=true`, p6spy/datasource-proxy, Testcontainers.

### 12.2 Self-test (no answers — recall under load)

1. You set only the `@OneToMany` (inverse) side and call `persist` on the parent. The child's FK is null afterward. Explain precisely why, and the fix.
2. Why does an id-based `hashCode()` corrupt a `HashSet` of entities, and what two alternative strategies preserve correctness across the lifecycle?
3. Give the exact circumstances under which a lazy `@OneToOne` is still eagerly loaded by Hibernate, and how `@MapsId` avoids it.
4. Compare SINGLE_TABLE vs JOINED on: query speed, NOT-NULL columns, normalization, and external FK referencing. When do you pick JOINED?
5. You must add `enrolled_at` and `grade` to a student↔course relationship. Sketch the full mapping and explain why `@ManyToMany` is insufficient.
6. Why does `IDENTITY` id generation defeat JDBC batch inserts, and what id strategy + settings restore batching?
7. Explain the flush write-ordering and construct a scenario where it still causes a unique-constraint violation, plus the workaround.
8. What does `merge()` return, what state is its argument left in afterward, and what's the resulting bug if you ignore the return value?

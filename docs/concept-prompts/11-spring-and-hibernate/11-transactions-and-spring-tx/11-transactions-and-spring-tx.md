# Transactions & Spring `@Transactional`

> An exhaustive engineering-handbook chapter on database transactions in the Java/JVM backend ecosystem, with a deep focus on Spring's declarative transaction management (`@Transactional`), the AOP proxy mechanism behind it, propagation and isolation, rollback rules, and the way it all connects to JPA/Hibernate's flush-and-commit lifecycle.

---

## 1. Overview & where it fits

### 1.1 What a transaction is

A **transaction** is a unit of work that the database treats as a single, indivisible logical operation. Either *all* of its constituent statements take effect, or *none* of them do. The canonical example: transferring money from account A to account B requires two `UPDATE`s (debit A, credit B). If the system crashes after the debit but before the credit, you must not lose money. A transaction guarantees that both happen or neither does.

The contract a transaction offers is summarized by the acronym **ACID**:

- **Atomicity** — all-or-nothing. Partial work is never visible after the fact; on failure the database **rolls back** (undoes) every change made inside the transaction.
- **Consistency** — the transaction moves the database from one valid state to another, respecting all declared constraints (foreign keys, `NOT NULL`, `CHECK`, unique indexes). This property is partly the application's responsibility and partly the DB's.
- **Isolation** — concurrent transactions don't step on each other; the degree of insulation is controlled by the **isolation level** (covered in depth in §1.5 and §4).
- **Durability** — once the database confirms a **commit** (the act of making a transaction's changes permanent), those changes survive crashes, power loss, etc. — typically via a write-ahead log flushed to durable storage.

> **Beginner aside — commit vs. rollback.** *Commit* is the explicit "make it permanent" step. *Rollback* is the "undo everything since the transaction began" step. Until you commit, your changes are private to your transaction (under most isolation levels). A connection is usually in **autocommit** mode by default, meaning each individual SQL statement is its own tiny transaction that commits immediately. Frameworks like Spring turn autocommit *off* for the duration of a managed transaction so several statements can be grouped.

### 1.2 The problem `@Transactional` solves

Managing transactions *by hand* in Java is verbose, error-prone, and intertwines business logic with plumbing. The manual (JDBC) version looks like this:

```java
Connection conn = dataSource.getConnection();
try {
    conn.setAutoCommit(false);          // begin: stop committing each statement
    // ... do work with the connection ...
    conn.commit();                      // make it permanent
} catch (SQLException e) {
    conn.rollback();                    // undo on failure
    throw e;
} finally {
    conn.setAutoCommit(true);           // restore default
    conn.close();                       // return to pool
}
```

Repeated in every method, this is noise. Worse, subtle bugs hide here: forgetting to roll back, leaking connections, committing on the wrong exception, nesting transactions incorrectly. **Spring's transaction management** abstracts all of this away. With **declarative transactions** you annotate a method `@Transactional` and Spring wraps it in exactly the boilerplate above — but correctly, consistently, and pluggably across JDBC, JPA/Hibernate, JTA, R2DBC, and more.

### 1.3 When you reach for it

- Any time a single business operation spans **two or more writes** that must succeed or fail together.
- Any time you need **consistent reads** across multiple queries within one logical operation.
- When you want a **single connection / persistence context** shared across a stack of service-layer calls.
- When integrating Hibernate/JPA, where the **persistence context** (the first-level cache / unit of work) is naturally scoped to a transaction.

You generally do **not** reach for a transaction around pure reads of a single statement (autocommit handles that fine), though a `readOnly` transaction can still be useful for the Hibernate optimizations described in §4.4 and §7.

### 1.4 One-paragraph mental model

> `@Transactional` is a **declarative instruction** that says: "Run this method inside a database transaction." Spring implements it with an **AOP proxy** — a stand-in object that wraps your bean. When a caller invokes a transactional method *through that proxy*, the proxy asks a **`PlatformTransactionManager`** to start (or join) a transaction, binds the resulting connection/`EntityManager` to the current thread, runs your method, and then either **commits** (normal return) or **rolls back** (a matching exception) before unbinding. Everything interesting — propagation, isolation, rollback rules, the self-invocation pitfall, read-only optimization — falls out of *how* that proxy and that transaction manager behave.

### 1.5 Isolation in one breath (full treatment in §4)

Concurrent transactions can interfere. The SQL standard names the classic **read phenomena** and defines four **isolation levels** that progressively forbid them:

- **Dirty read** — reading another transaction's *uncommitted* changes.
- **Non-repeatable read** — re-reading the same row and getting a different value because another transaction committed an `UPDATE`/`DELETE` in between.
- **Phantom read** — re-running the same range query and getting *new rows* because another transaction committed an `INSERT` matching the predicate.

| Isolation level    | Dirty read | Non-repeatable read | Phantom read |
|--------------------|:----------:|:-------------------:|:------------:|
| READ UNCOMMITTED   | possible   | possible            | possible     |
| READ COMMITTED     | prevented  | possible            | possible     |
| REPEATABLE READ    | prevented  | prevented           | possible*    |
| SERIALIZABLE       | prevented  | prevented           | prevented    |

\* The standard permits phantoms at REPEATABLE READ; some engines (notably InnoDB via next-key locking, and snapshot-based engines) prevent them in practice. Vendor-specific — see §4.2.

---

## 2. Foundations from first principles

### 2.1 The layers involved

Before Spring enters the picture, understand the stack a transaction passes through:

1. **The database engine** (PostgreSQL, MySQL/InnoDB, Oracle, SQL Server, H2…). This is where real ACID semantics live. It implements MVCC or locking, the write-ahead log, isolation levels, and the actual `BEGIN`/`COMMIT`/`ROLLBACK`.

   > **Beginner aside — MVCC.** *Multi-Version Concurrency Control* is a technique where the engine keeps multiple **versions** of each row. Readers see a consistent **snapshot** as of the time their statement (or transaction) started, while writers create new versions. This lets reads not block writes and vice-versa. PostgreSQL and InnoDB are MVCC engines. The alternative is pessimistic **locking**, where a transaction acquires locks that block others.

   > **Beginner aside — write-ahead log (WAL).** Before changing the actual data pages on disk, the engine appends a record of the change to a sequential log and flushes *that* to durable storage. On crash recovery it replays the log. This is what makes **durability** efficient — sequential log writes are far cheaper than random page writes, and a `COMMIT` only needs the log record durable.

2. **The JDBC driver and `java.sql.Connection`.** JDBC (Java Database Connectivity) is the low-level Java API for talking to a database. A `Connection` is a single session to the DB. Transactions in JDBC are controlled via `connection.setAutoCommit(false)`, `connection.commit()`, `connection.rollback()`, `connection.setTransactionIsolation(...)`, and savepoints (`connection.setSavepoint()` / `rollback(savepoint)`).

3. **The connection pool** (HikariCP by default in Spring Boot). Opening a physical DB connection is expensive (TCP handshake, auth, session setup — often 50–250 ms). A **pool** keeps a set of physical connections open and hands them out, so a "get connection" is a near-instant borrow. Spring borrows a connection at transaction start and returns it at the end.

   > **Beginner aside — HikariCP.** The default, very fast JDBC connection pool used by Spring Boot. Key knobs: `maximumPoolSize` (default 10), `minimumIdle`, `connectionTimeout` (default 30 s), `idleTimeout`, `maxLifetime` (default 30 min). A transaction holds a pooled connection for its *entire* duration, so long transactions starve the pool — a recurring production problem (§9).

4. **The persistence layer** — JPA (the Jakarta Persistence API specification) implemented by **Hibernate** (the default provider in Spring Boot). JPA introduces the `EntityManager` and the **persistence context** (see §2.4). Hibernate ultimately uses a JDBC `Connection` under the hood.

5. **Spring's transaction abstraction** — `PlatformTransactionManager` and friends, plus the `@Transactional` annotation and the AOP machinery that enforces it. This is the focus of the chapter.

### 2.2 Programmatic vs. declarative transactions

Spring supports two styles:

- **Programmatic** — you explicitly start and end transactions in code, via `TransactionTemplate` or by calling the `PlatformTransactionManager` directly. Verbose but gives precise control; useful inside framework code or for fine-grained partial commits.
- **Declarative** — you annotate methods/classes with `@Transactional` (or configure pointcuts in XML). Spring weaves the transaction logic in via AOP. This is the dominant style and the bulk of this document.

### 2.3 What "AOP proxy" means (the engine behind `@Transactional`)

> **Beginner aside — AOP.** *Aspect-Oriented Programming* lets you factor out **cross-cutting concerns** — logic that would otherwise be duplicated across many methods (logging, security checks, transactions) — into reusable "aspects" that are applied declaratively. Spring implements AOP largely with **proxies**.

> **Beginner aside — proxy.** A *proxy* is an object that implements the same interface (or subclasses) as the real object and stands in front of it. Callers think they're talking to the real bean, but they're actually talking to the proxy, which can run code *before* and *after* delegating to the real method. Spring AOP creates these at runtime.

When Spring finds a bean with `@Transactional`, it does **not** give you the raw bean. It gives you a **proxy**:

- If your bean implements an interface, Spring (by default) uses a **JDK dynamic proxy** — a runtime-generated class implementing that interface.
- If it has no interface (or you set `proxyTargetClass = true`), Spring uses a **CGLIB proxy** — a runtime-generated *subclass* of your bean. Spring Boot defaults to `proxyTargetClass = true` since Boot 2.0, so CGLIB is the common case.

  > **Beginner aside — CGLIB.** A bytecode-generation library that creates subclasses at runtime by overriding methods. Spring uses it to proxy classes that don't implement interfaces. Limitation: it can't proxy `final` classes or override `final`/`private`/`static` methods — which is exactly why `@Transactional` on a `final` method or class silently does nothing.

The proxy's logic, conceptually, is the `TransactionInterceptor` running the JDBC boilerplate from §1.2 around your method. Two consequences fall directly out of this design and cause the two most famous bugs:

1. **Self-invocation bypasses the proxy** (§4.6, §7.1). If method `a()` inside your bean calls `this.b()` where `b()` is `@Transactional`, the call goes straight to the real object — *not* through the proxy — so no transaction is started for `b()`.
2. **Only `public` methods are advised** by default (Spring AOP), because the proxy can only intercept externally visible calls.

### 2.4 The persistence context (Hibernate's unit of work)

> **Beginner aside — persistence context / first-level cache.** In JPA, the `EntityManager` maintains a **persistence context**: an in-memory map of all **managed** entities you've loaded or persisted within the current unit of work, keyed by their identity. It is also called the **first-level cache (L1)**. Two key behaviors: (a) **identity guarantee** — within one context, loading the same row twice returns the *same* Java object; (b) **automatic dirty checking** — Hibernate snapshots loaded entities and, at flush time, generates `UPDATE`s for any fields that changed, with no explicit `save()` call.

> **Beginner aside — flush.** *Flushing* is the act of synchronizing the in-memory persistence context to the database by emitting the pending `INSERT`/`UPDATE`/`DELETE` SQL. Flush is **not** commit. After a flush the SQL has been sent (and the DB sees it within the transaction), but the transaction is not yet committed and can still roll back. Hibernate flushes automatically before queries (to keep query results consistent with pending changes) and **always flushes before commit**.

The persistence context is normally **bound to the transaction**: it is created when the transaction begins and closed when it ends. This is why `@Transactional` and Hibernate are so tightly coupled — the transaction boundary *is* the persistence-context boundary in the standard "open-session-in-transaction" model. (Web apps sometimes extend it with the "Open Session in View" pattern; see §7.6.)

### 2.5 The core Spring abstractions

| Abstraction | Role |
|---|---|
| `PlatformTransactionManager` | The strategy interface Spring calls to `getTransaction`, `commit`, `rollback`. Implementations: `DataSourceTransactionManager` (plain JDBC), `JpaTransactionManager` (JPA/Hibernate), `JtaTransactionManager` (distributed/XA), `R2dbcTransactionManager` (reactive), `HibernateTransactionManager` (native Hibernate). |
| `TransactionDefinition` | Describes the *desired* transaction: propagation, isolation, timeout, read-only, rollback rules. `@Transactional`'s attributes map onto this. |
| `TransactionStatus` | Represents a *running* transaction: whether it's new, a savepoint holder, rollback-only flag, completion state. |
| `TransactionSynchronizationManager` | A holder of thread-bound resources (the current connection / `EntityManager`) and registered **synchronizations** (callbacks fired around commit/rollback). The glue that makes "the current transaction" a thread-local concept. |
| `TransactionInterceptor` / `TransactionAspectSupport` | The AOP advice that reads the metadata and drives the manager around your method. |

> **Beginner aside — thread-bound / thread-local.** Spring stores "the current transaction's resources" in `ThreadLocal` variables keyed by the `DataSource`/`EntityManagerFactory`. A `ThreadLocal` gives each thread its own copy of a value. This is why, in classic Spring MVC, a transaction is **per-thread**: all DAO calls on the same thread within the transaction transparently reuse the same connection. It's also why naive use across threads (e.g. handing work to an `ExecutorService`) breaks transaction propagation — the new thread has no bound resource (§9).

---

## 3. How it works internally — the heart of the document

This section traces, step by step, what happens from the moment a caller invokes a `@Transactional` method to the moment the transaction commits or rolls back.

### 3.1 Bootstrap: how proxies get created

1. `@EnableTransactionManagement` (auto-applied by Spring Boot via `TransactionAutoConfiguration`) registers an **infrastructure bean post-processor**: `InfrastructureAdvisorAutoProxyCreator`.
2. It also registers a `BeanFactoryTransactionAttributeSourceAdvisor` containing:
   - a **pointcut** (`TransactionAttributeSourcePointcut`) that matches any class/method carrying `@Transactional`, and
   - the **advice** (`TransactionInterceptor`).
3. During bean creation, the auto-proxy creator inspects each bean. If the pointcut matches, it wraps the bean in a proxy (JDK or CGLIB per §2.3) whose method calls pass through the `TransactionInterceptor`.

The metadata is read by `AnnotationTransactionAttributeSource`, which parses `@Transactional` attributes into a `TransactionAttribute` (a `TransactionDefinition` plus rollback rules) and **caches** it per method.

> **Beginner aside — pointcut/advice/advisor.** In Spring AOP, an *advice* is the code to run (the transaction logic). A *pointcut* is the predicate selecting which methods get advised (those with `@Transactional`). An *advisor* bundles a pointcut with an advice.

### 3.2 The interception flow (single transaction, happy path)

When a method is invoked through the proxy, `TransactionInterceptor.invoke()` → `TransactionAspectSupport.invokeWithinTransaction()` runs roughly this sequence:

1. **Resolve metadata.** Look up the `TransactionAttribute` for the target method (propagation, isolation, timeout, read-only, rollback rules, transaction-manager qualifier). If none and the call isn't transactional, just proceed to the target.
2. **Resolve the transaction manager.** Pick the `PlatformTransactionManager` bean (by qualifier if specified, else the primary/only one).
3. **`createTransactionIfNecessary`** → calls `txManager.getTransaction(definition)`. This is where propagation logic runs (§3.3). The manager:
   - Checks `TransactionSynchronizationManager` for an existing bound resource (an active transaction on this thread).
   - Decides, based on **propagation**, whether to **join** the existing one, **suspend** it and start a new one, run **non-transactionally**, or **throw**.
   - For a new physical transaction: borrows a `Connection` from the pool (or creates an `EntityManager`), sets `autoCommit=false`, applies isolation/timeout/read-only, and **binds** the resource to the thread.
   - Returns a `TransactionStatus` (with `isNewTransaction()` true if it created a physical tx).
4. **Invoke the target method** (your business code). Your DAO/repository calls now find the bound connection/`EntityManager` transparently.
5. **On normal return:** `commitTransactionAfterReturning` → if `status` is rollback-only (someone set it), roll back instead; otherwise:
   - Fire `beforeCommit` synchronizations → Hibernate **flushes** the persistence context here (emitting pending SQL).
   - `txManager.commit(status)` → if this is the **outermost** (new) transaction, issue the real `COMMIT`; if it's a participating inner one, do nothing (commit deferred to outer).
   - Fire `afterCommit` then `afterCompletion(COMMITTED)` synchronizations.
   - Unbind and clean up resources; restore autocommit; return connection to pool.
6. **On exception:** `completeTransactionAfterThrowing` → consult **rollback rules** (§3.5). If the exception *should* roll back: `txManager.rollback(status)` (real `ROLLBACK` if outermost; mark rollback-only if inner). Otherwise commit normally. Then clean up. Re-throw the original exception.

### 3.3 Propagation: the decision tree at `getTransaction`

Propagation governs the relationship between a transactional method and any transaction *already running on the thread*. The seven modes and their behavior:

| Propagation | If a tx exists | If none exists |
|---|---|---|
| **REQUIRED** (default) | Join it (participate) | Start a new physical tx |
| **REQUIRES_NEW** | **Suspend** the current, start a new independent physical tx, resume the old on completion | Start a new physical tx |
| **NESTED** | Create a **savepoint** within the current tx; rollback only undoes to the savepoint | Start a new physical tx |
| **SUPPORTS** | Join it | Run non-transactionally |
| **NOT_SUPPORTED** | **Suspend** the current; run non-transactionally | Run non-transactionally |
| **MANDATORY** | Join it | **Throw** `IllegalTransactionStateException` |
| **NEVER** | **Throw** `IllegalTransactionStateException` | Run non-transactionally |

Key internal mechanics:

- **Suspend/resume** (`REQUIRES_NEW`, `NOT_SUPPORTED`): Spring snapshots the currently bound resources and synchronizations into a `SuspendedResourcesHolder`, unbinds them, runs the inner work (with its own brand-new connection for `REQUIRES_NEW`), then restores the snapshot afterward. Critically, the suspended transaction **still holds its connection** — so `REQUIRES_NEW` uses **two pooled connections simultaneously** for the duration. Over-using it can exhaust the pool and even **deadlock** (the outer holds a connection while the inner waits for one).
- **Savepoint** (`NESTED`): A **savepoint** is a named marker *inside* a transaction you can roll back to without aborting the whole transaction. `NESTED` issues `Connection.setSavepoint()` and, on inner failure, `rollback(savepoint)` — the outer transaction survives and can still commit the work before the savepoint. Requires a `PlatformTransactionManager` and driver that support savepoints (JDBC `DataSourceTransactionManager` does; **`JpaTransactionManager` supports it only on capable dialects**, and JTA generally does **not**). This is the crucial difference from `REQUIRES_NEW`: `NESTED` shares one physical connection/transaction; `REQUIRES_NEW` uses a separate independent one that commits/rolls back on its own.

> **Beginner aside — physical vs. logical transaction.** A *physical* transaction is a real DB `BEGIN…COMMIT`. A *logical* transaction is a Spring-level participation scope. With `REQUIRED`, an inner method that "joins" creates a new *logical* scope but shares the one *physical* transaction; only the outermost scope actually commits. This is why an inner `REQUIRED` method that throws can poison the whole thing via the rollback-only flag (§3.6).

### 3.4 Worked propagation scenarios

- **Outer REQUIRED → inner REQUIRED, inner throws (caught by outer):** One physical transaction. The inner failure marks the shared transaction **rollback-only**. Even though the outer catches the exception and returns normally, at commit time Spring sees the rollback-only flag and throws `UnexpectedRollbackException` while rolling everything back. Classic surprise (§9.4).
- **Outer REQUIRED → inner REQUIRES_NEW, inner commits, then outer throws:** Two physical transactions. The inner already committed independently; the outer rolls back. The inner work **persists** — useful for audit/log rows you want to keep even when the main operation fails.
- **Outer REQUIRED → inner NESTED, inner throws (caught by outer):** One physical transaction with a savepoint. Inner rolls back to the savepoint; outer continues and commits the pre-savepoint work. Lets you do best-effort sub-operations.

### 3.5 Rollback rules (the classic checked-exception bug)

By default Spring rolls back on **`RuntimeException` (unchecked)** and **`Error`**, and **commits on checked exceptions** (anything extending `Exception` but not `RuntimeException`). This trips up everyone at least once:

```java
@Transactional
public void placeOrder() throws IOException {   // checked exception
    repo.save(order);
    if (somethingWrong) throw new IOException("oops");
    // Spring COMMITS the saved order, because IOException is checked!
}
```

To change this, declare it explicitly:

```java
@Transactional(rollbackFor = IOException.class)            // roll back on this checked one too
@Transactional(noRollbackFor = IllegalStateException.class) // do NOT roll back on this unchecked one
@Transactional(rollbackFor = Exception.class)               // roll back on everything (common, safe default)
```

Internally, `RuleBasedTransactionAttribute` holds an ordered list of `RollbackRuleAttribute` / `NoRollbackRuleAttribute`. On exception, Spring finds the rule whose exception type is the **closest superclass** of the thrown exception in the hierarchy (deepest/most-specific match wins) and uses it. If no rule matches, the default unchecked-rollback policy applies.

> **Why this default?** It mirrors EJB semantics: checked exceptions are considered "business" outcomes the caller is expected to handle (and may want the work committed), while unchecked exceptions signal programming/system failures that should abort. Most teams disagree with this for service code and standardize on `rollbackFor = Exception.class`.

### 3.6 The rollback-only flag and `UnexpectedRollbackException`

When an inner participating (`REQUIRED`) transaction decides to roll back, it cannot physically roll back (it doesn't own the physical transaction), so it sets `setRollbackOnly()` on the shared `TransactionStatus`. When control returns to the outermost transaction and it tries to commit, Spring detects the flag and instead rolls back the physical transaction, then throws `UnexpectedRollbackException` to signal "you thought you committed, but we rolled back." Catching the inner exception does **not** clear the flag.

### 3.7 The full lifecycle / state machine

```
        getTransaction(def)
   ┌──────────────────────────────┐
   │  evaluate PROPAGATION         │
   │   - join existing             │
   │   - suspend + new             │
   │   - savepoint (NESTED)        │
   │   - non-tx / throw            │
   └──────────────┬───────────────┘
                  │ (new physical tx)
        borrow Connection / open EntityManager
        setAutoCommit(false); set isolation/timeout/readOnly
        bind resource to thread (TransactionSynchronizationManager)
                  │
            run target method  ───────────► exception?
                  │ normal return                  │
        beforeCommit synch  (Hibernate FLUSH)      │ rollback rule says rollback?
                  │                          ┌─────┴─────┐
            commit physical tx?              yes         no
                  │                           │           │
        afterCommit / afterCompletion    rollback     commit (as above)
                  │                       physical
        unbind resource; restore autocommit; return to pool
                  │
              re-throw original exception (if any)
```

### 3.8 Synchronizations (callbacks around commit)

`TransactionSynchronization` is a callback you register (via `TransactionSynchronizationManager.registerSynchronization(...)`) that fires at lifecycle points: `beforeCommit`, `beforeCompletion`, `afterCommit`, `afterCompletion(status)`. This is the mechanism behind Spring's `@TransactionalEventListener` (publish an event that only fires *after* the transaction commits — see §5.6), and behind Hibernate registering its own synchronization to flush before commit and close the session after completion. **`afterCommit` is the correct place** to trigger side effects that must only happen if the data really persisted (send email, enqueue message, call external API).

---

## 4. The complete toolkit

### 4.1 `@Transactional` attributes

| Attribute | Type | Default | Meaning |
|---|---|---|---|
| `value` / `transactionManager` | String | "" (the primary) | Qualifier naming which `PlatformTransactionManager` to use (multi-datasource apps). |
| `propagation` | `Propagation` | `REQUIRED` | Relationship to an existing transaction (§3.3). |
| `isolation` | `Isolation` | `DEFAULT` (use the DB/driver default) | Read-phenomena protection level (§4.2). |
| `timeout` | int (seconds) | `-1` (use default/none) | Max duration; on expiry the transaction is marked rollback-only and a `TransactionTimedOutException` is thrown at the next resource access. |
| `timeoutString` | String | "" | SpEL/placeholder-resolvable form of `timeout`. |
| `readOnly` | boolean | `false` | Hint enabling read optimizations (§4.4). |
| `rollbackFor` | Class[] | {} | Extra exception types that trigger rollback. |
| `rollbackForClassName` | String[] | {} | Same, by name (avoids importing the class). |
| `noRollbackFor` | Class[] | {} | Exception types that must **not** roll back. |
| `noRollbackForClassName` | String[] | {} | Same, by name. |
| `label` | String[] | {} | Arbitrary labels for custom transaction managers/observability. |

> **Note:** Both Spring's own `org.springframework.transaction.annotation.@Transactional` and Jakarta's `jakarta.transaction.@Transactional` are recognized. Prefer Spring's — it exposes the full attribute set (`isolation`, `timeout`, `readOnly`, fine-grained rollback rules); the Jakarta one is more limited.

### 4.2 Isolation levels (`Isolation` enum → JDBC constant → DB behavior)

| `Isolation` | JDBC constant | Prevents |
|---|---|---|
| `DEFAULT` | (driver default) | Whatever the DB defaults to |
| `READ_UNCOMMITTED` | `TRANSACTION_READ_UNCOMMITTED` | nothing (allows dirty reads) |
| `READ_COMMITTED` | `TRANSACTION_READ_COMMITTED` | dirty reads |
| `REPEATABLE_READ` | `TRANSACTION_REPEATABLE_READ` | dirty + non-repeatable reads |
| `SERIALIZABLE` | `TRANSACTION_SERIALIZABLE` | dirty + non-repeatable + phantom reads |

**Vendor-specific defaults & quirks (flag these — they bite):**

- **PostgreSQL** default: **READ COMMITTED**. Its `REPEATABLE READ` is true snapshot isolation and *does* prevent phantoms. `SERIALIZABLE` uses **SSI** (Serializable Snapshot Isolation) and can throw `40001` serialization-failure errors that you must **retry**.
- **MySQL/InnoDB** default: **REPEATABLE READ**, and its next-key locking largely prevents phantoms even at RR.
- **Oracle** supports only **READ COMMITTED** (default) and **SERIALIZABLE** — *not* READ UNCOMMITTED or REPEATABLE READ. Requesting an unsupported level errors.
- **SQL Server** default: READ COMMITTED (locking-based unless `READ_COMMITTED_SNAPSHOT` is on).

> **Caveat:** With `JpaTransactionManager`, setting a custom isolation level historically required `setValidateExistingTransaction` care and, on some setups, a `JpaDialect` that supports per-transaction isolation. Modern Spring + Hibernate honors it, but always verify with SQL logging.

### 4.3 `Propagation` enum — see §3.3 for full semantics.

`REQUIRED`, `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `NOT_SUPPORTED`, `MANDATORY`, `NEVER`.

### 4.4 `readOnly = true` — what it actually does

It is a **hint** with several concrete effects depending on the manager:

- **Hibernate/JPA:** sets the Session **`FlushMode` to `MANUAL`/`NEVER`**, so Hibernate **skips dirty checking and automatic flushes**, saving CPU and memory (no entity snapshots compared). It also lets Hibernate avoid taking write locks where applicable.
- **JDBC:** calls `Connection.setReadOnly(true)`, a hint the driver/DB *may* use (e.g., to route to a read replica or optimize). Many drivers ignore it.
- **Routing:** combined with a routing `DataSource`, `readOnly` is the common signal to send the connection to a **read replica**.

It does **not** make writes impossible at the DB level by itself (unless the DB enforces it). Treat it as an optimization + intent declaration, not a hard guard.

### 4.5 `PlatformTransactionManager` implementations

| Manager | Use with | Notes |
|---|---|---|
| `DataSourceTransactionManager` | Plain JDBC / MyBatis / JdbcTemplate | Manages a single `DataSource`; supports savepoints (`NESTED`). |
| `JpaTransactionManager` | JPA/Hibernate via `EntityManagerFactory` | The Spring Boot default when JPA is on the classpath; binds `EntityManager` to the thread; `NESTED` only on capable dialects. |
| `HibernateTransactionManager` | Native Hibernate `SessionFactory` | Pre-JPA style; rarely used now. |
| `JtaTransactionManager` | Distributed/XA across multiple resources | Delegates to a JTA provider (Atomikos, Narayana, app-server TM); enables 2-phase commit; `NESTED`/savepoints generally unsupported. |
| `R2dbcTransactionManager` | Reactive (R2DBC) | For `Mono`/`Flux` pipelines; transaction context flows via the Reactor `Context`, not `ThreadLocal`. |
| `ChainedTransactionManager` (deprecated) | Best-effort across managers | Commits in sequence; **not** atomic — avoid; prefer outbox/JTA. |

> **Beginner aside — JTA / XA / two-phase commit.** *JTA* (Jakarta Transaction API) coordinates a transaction spanning **multiple** resources (two databases, a DB + a JMS queue). *XA* is the protocol; **two-phase commit (2PC)** is the algorithm: phase 1 asks every resource "can you commit?" (prepare); phase 2 tells them all to commit if everyone said yes. It's powerful but slow and operationally heavy; modern systems usually prefer the **transactional outbox** pattern over XA (§7.7).

### 4.6 Programmatic API

```java
// 4.6.1 TransactionTemplate (recommended programmatic style)
@Bean TransactionTemplate txTemplate(PlatformTransactionManager tm) {
    TransactionTemplate t = new TransactionTemplate(tm);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    t.setTimeout(5);
    return t;
}

String result = txTemplate.execute(status -> {     // returns a value
    repo.save(x);
    if (bad) status.setRollbackOnly();             // request rollback without throwing
    return "done";
});

txTemplate.executeWithoutResult(status -> repo.save(y)); // void variant (Spring 5.2+)
```

```java
// 4.6.2 Raw PlatformTransactionManager
TransactionStatus status = tm.getTransaction(new DefaultTransactionDefinition());
try {
    // ... work ...
    tm.commit(status);
} catch (RuntimeException e) {
    tm.rollback(status);
    throw e;
}
```

```java
// 4.6.3 Spring 6 / Boot 3: TransactionalOperator for reactive or functional style
TransactionalOperator operator = TransactionalOperator.create(reactiveTxManager);
Mono<Void> flow = repo.save(entity).then().as(operator::transactional);
```

### 4.7 Useful static helpers

| Call | Purpose |
|---|---|
| `TransactionSynchronizationManager.isActualTransactionActive()` | Is a real transaction running on this thread? Great for assertions/tests. |
| `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` | Read-only state. |
| `TransactionSynchronizationManager.getCurrentTransactionName()` | Usually `ClassName.methodName` — visible in logs/observability. |
| `TransactionSynchronizationManager.registerSynchronization(sync)` | Add lifecycle callbacks (afterCommit, etc.). |
| `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` | Mark rollback from inside a declarative method without throwing. |

---

## 5. Code examples by use case

### 5.1 Money transfer (atomic multi-write, the canonical case)

```java
@Service
public class TransferService {
    private final AccountRepository accounts;
    TransferService(AccountRepository accounts) { this.accounts = accounts; }

    @Transactional(rollbackFor = Exception.class)   // roll back on ALL exceptions, not just unchecked
    public void transfer(long fromId, long toId, BigDecimal amount) {
        Account from = accounts.findById(fromId).orElseThrow();
        Account to   = accounts.findById(toId).orElseThrow();
        if (from.getBalance().compareTo(amount) < 0)
            throw new InsufficientFundsException();   // unchecked → rollback (but we made it explicit anyway)
        from.debit(amount);                            // dirty-checking will UPDATE at flush — no save() needed
        to.credit(amount);
        // commit happens automatically when the method returns normally
    }
}
```

Why it's correct: both updates share one transaction and one connection; either both commit or both roll back. Dirty checking means we don't call `save()`; Hibernate flushes the `UPDATE`s at commit.

### 5.2 Keep an audit row even when the business operation fails (`REQUIRES_NEW`)

```java
@Service
public class OrderService {
    @Autowired private OrderService self;   // self-injected proxy to defeat self-invocation (§7.1)
    private final AuditService audit;
    private final OrderRepository orders;

    @Transactional
    public void place(Order order) {
        self.audit("attempt place " + order.getId());  // committed independently via proxy
        orders.save(order);
        riskyExternalCharge(order);                     // if this throws, the order rolls back...
    }                                                   // ...but the audit row stays.
}

@Service
class AuditService {
    private final AuditRepository repo;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(String msg) { repo.save(new AuditEntry(msg, Instant.now())); }
}
```

Note: `REQUIRES_NEW` uses a **second** connection while the outer one is suspended — watch pool sizing (§9.2).

### 5.3 Best-effort sub-step with `NESTED` (savepoint)

```java
@Transactional
public void importBatch(List<Row> rows) {
    for (Row r : rows) {
        try {
            self.importOne(r);          // NESTED: rolls back to a savepoint on failure
        } catch (ImportException e) {
            log.warn("skipping bad row {}", r.id());   // outer tx survives; we continue
        }
    }
}

@Transactional(propagation = Propagation.NESTED)
public void importOne(Row r) { repo.save(toEntity(r)); }   // requires savepoint-capable manager
```

### 5.4 Read-only query method (optimization + replica routing)

```java
@Transactional(readOnly = true, timeout = 10)
public List<OrderView> recentOrders(long customerId) {
    return orders.findRecentByCustomer(customerId);   // Hibernate sets FlushMode.MANUAL: no dirty checks
}
```

### 5.5 Explicit isolation for an inventory check-and-decrement

```java
@Transactional(isolation = Isolation.REPEATABLE_READ)   // protect the read-then-write against concurrent buyers
public void reserveStock(long sku, int qty) {
    Stock s = stock.findBySku(sku);                     // read
    if (s.getAvailable() < qty) throw new OutOfStockException();
    s.setAvailable(s.getAvailable() - qty);             // write based on the read
}
// Alternative: pessimistic lock with SELECT ... FOR UPDATE (§7.4) — often preferable to relying on isolation alone.
```

### 5.6 Fire a side effect only after commit (`@TransactionalEventListener`)

```java
@Transactional
public void register(User u) {
    users.save(u);
    eventPublisher.publishEvent(new UserRegistered(u.getId()));  // queued, not sent yet
}

@Component
class EmailOnRegister {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)   // fires ONLY if the tx commits
    public void onCommit(UserRegistered e) { emailService.sendWelcome(e.userId()); }
}
```

This prevents the classic "we emailed the customer but the transaction rolled back" bug.

### 5.7 Programmatic partial commits in a long job

```java
public void processFile(Path file) {
    try (var lines = Files.lines(file)) {
        var batch = new ArrayList<Record>(1000);
        lines.forEach(line -> {
            batch.add(parse(line));
            if (batch.size() == 1000) { flushBatch(batch); batch.clear(); }
        });
    }
}

private void flushBatch(List<Record> batch) {
    txTemplate.executeWithoutResult(status -> repo.saveAll(batch)); // each batch is its own short tx
}
```

Short transactions avoid holding a connection and locks for the entire file — a key scalability pattern.

### 5.8 Retry on serialization failure (PostgreSQL SERIALIZABLE)

```java
@Retryable(retryFor = CannotSerializeTransactionException.class, maxAttempts = 4,
           backoff = @Backoff(delay = 50, multiplier = 2))
@Transactional(isolation = Isolation.SERIALIZABLE)
public void transferStrict(long from, long to, BigDecimal amt) { /* ... */ }
```

Spring translates the DB's `40001` into `CannotSerializeTransactionException`; `@Retryable` (Spring Retry) re-runs the whole transaction. Note the ordering caveat in §7.2: the retry advice must sit **outside** the transaction advice so each attempt is a fresh transaction.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Keep transactions short.** A transaction holds a pooled connection and DB locks for its full duration. Long transactions throttle throughput, bloat MVCC version chains (PostgreSQL "bloat"), and starve the pool. Never do slow I/O (HTTP calls, file uploads, `Thread.sleep`) inside a transaction.
- **Don't make the transaction wider than needed.** Annotate at the **service** method that defines the business unit, not at the controller or repository. Repository methods auto-get a transaction from `@Transactional` already; double-wrapping is wasteful.
- **Use `readOnly = true`** for query-only methods — skips Hibernate dirty checking/flushing and may route to replicas.
- **Batch writes** with `hibernate.jdbc.batch_size` (e.g. 50) and ordered inserts/updates; without it Hibernate sends one round-trip per row.
- **Avoid `REQUIRES_NEW` in loops** — each iteration suspends/resumes and grabs a second connection.

### 6.2 Correctness & concurrency

- **Lost updates**: read-modify-write without protection loses concurrent updates. Use **optimistic locking** (`@Version` — Hibernate adds `WHERE version = ?` and throws `OptimisticLockException` on mismatch) or **pessimistic locking** (`SELECT … FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`).
- **Choose isolation deliberately.** Higher isolation = more correctness, more contention/aborts. SERIALIZABLE on PostgreSQL needs retry logic.
- **Set rollback rules explicitly.** Standardize on `rollbackFor = Exception.class` in service code unless you have a deliberate reason to commit-on-checked.

### 6.3 Security

- `readOnly` is not a security control. Enforce write restrictions at the DB role/grant level for true read-only paths.
- Beware **transaction-scoped privilege**: a `REQUIRES_NEW` audit write can persist even when the main op is rejected — make sure that's intended.

### 6.4 Observability

- Log SQL during development: `spring.jpa.show-sql=true` plus a SQL logger; in production use `datasource-proxy` or `p6spy` to log statements + bind params + timings.
- Watch pool metrics (HikariCP exposes `hikaricp.connections.active`, `.pending`, `.usage` via Micrometer). Rising `pending` and `active == max` means transactions are holding connections too long.
- Spring 6 / Boot 3 integrate **Micrometer Observation** for transactions; you can name transactions and trace boundaries.
- DB-side: `pg_stat_activity` (PostgreSQL) shows `idle in transaction` sessions — the smoking gun for a leaked/long transaction.

### 6.5 Testing

- `@DataJpaTest` and `@Transactional` test methods **roll back by default** after each test, keeping the DB clean. Use `@Commit` or `@Rollback(false)` to override.
- Assert transactional behavior with `TransactionSynchronizationManager.isActualTransactionActive()`.
- Test rollback paths explicitly (throw and verify nothing persisted).
- Beware: a `@Transactional` *test* method wraps everything in one transaction, which can **hide** flush-timing and lazy-loading bugs that appear in production. Integration-test critical flows without the test-level transaction.

### 6.6 Production hardening

- Set a global statement/transaction **timeout** (per-method `timeout`, and DB-side `statement_timeout` / `idle_in_transaction_session_timeout` on PostgreSQL) so runaway transactions can't pin connections forever.
- Size the pool: `connections = ((core_count * 2) + effective_spindle_count)` is the classic HikariCP rule of thumb; account for `REQUIRES_NEW` doubling.
- Add `idle_in_transaction_session_timeout` to kill abandoned transactions.

### 6.7 Anti-patterns to avoid

| Anti-pattern | Why it's bad |
|---|---|
| `@Transactional` on controllers | Transaction spans HTTP serialization/view rendering; locks held too long. |
| Self-invocation of `@Transactional` methods | Bypasses the proxy → no transaction (§7.1). |
| `@Transactional` on `private`/`final`/`static` methods | Proxy can't advise them; silently no-op. |
| Catching the exception inside an inner `REQUIRED` method and swallowing it | Doesn't clear rollback-only → `UnexpectedRollbackException` at outer commit. |
| Slow I/O inside a transaction | Pool starvation, lock contention. |
| Relying on default rollback for checked exceptions | Data committed on failure (§3.5). |
| Open Session in View on by default | Lazy loads in the view, N+1 queries, longer-held connections (§7.6). |
| Spawning threads inside a transaction expecting propagation | New thread has no bound resource (§9.5). |

---

## 7. Advanced topics & deep internals

### 7.1 The self-invocation pitfall (in depth)

Because `@Transactional` is enforced by a *proxy* wrapping the bean, only calls that go **through the proxy** are intercepted. A call like `this.method()` (or an unqualified `method()`) inside the same class targets the raw object and skips the interceptor. Therefore:

```java
@Service
class ReportService {
    public void run() {
        generate();       // ⚠ NO transaction — internal call bypasses proxy
    }
    @Transactional
    public void generate() { repo.save(...); }
}
```

**Fixes:**
1. **Move the transactional method to another bean** and inject it (cleanest).
2. **Self-injection**: inject the bean into itself (`@Autowired ReportService self;`) and call `self.generate()` — goes through the proxy.
3. **`AopContext.currentProxy()`** with `@EnableAspectJAutoProxy(exposeProxy = true)`: `((ReportService) AopContext.currentProxy()).generate();` (ties code to AOP infra — least clean).
4. **AspectJ load-time/compile-time weaving** (`mode = AspectJ`): weaves the advice into the bytecode directly, so even self-calls are advised. Heavier setup; needed for non-public/self-invocation transactional methods.

### 7.2 Aspect ordering (`@Order`) and combining with `@Async`, `@Cacheable`, `@Retryable`

Multiple proxies stack. Order matters:

- **`@Retryable` must wrap `@Transactional`** (retry outside transaction) so each retry is a *new* transaction. If `@Transactional` is outside, a failed-then-retried call reuses a rollback-marked transaction → `UnexpectedRollbackException`.
- **`@Async` + `@Transactional`**: `@Async` runs on a different thread → a *new* transaction context; it does **not** inherit the caller's transaction. Combining them on one method is fragile; usually separate them.
- **`@Cacheable` + `@Transactional`**: cache put happens around the method; ensure the transaction commits before cached results are trusted by others. Use `@CachePut`/eviction carefully on write methods.
- Control order with `@EnableTransactionManagement(order = …)` and `@Order` on aspects. Transaction advice default order is `Ordered.LOWEST_PRECEDENCE`.

### 7.3 Hibernate flush modes & flush timing

`FlushMode` values: `AUTO` (default — flush before queries and at commit), `COMMIT` (flush only at commit, not before queries — risks stale query results), `ALWAYS`, `MANUAL` (only explicit `flush()` — what `readOnly=true` triggers). Understanding flush timing explains otherwise-mysterious "why did my UPDATE happen *here*?" SQL ordering. Hibernate also reorders DML at flush (inserts, then updates, then deletes) which can surprise you with constraint timing.

### 7.4 Locking strategies layered on transactions

- **Optimistic** (`@Version`): no DB lock; detect conflict at write time via version column; throw and retry. Best for low-contention.
- **Pessimistic** (`@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`): acquire a row lock at read; others block. Best for high-contention hot rows; risks deadlocks and lock-wait timeouts.
- **Lock timeout hints**: `jakarta.persistence.lock.timeout` controls how long to wait for a pessimistic lock.

### 7.5 Second-level cache & query cache interaction

> **Beginner aside — second-level cache (L2).** Unlike the L1 persistence context (per-transaction), the **L2 cache** is shared across sessions/transactions at the `SessionFactory` level (e.g. via Ehcache/Infinispan/Hazelcast). The **query cache** caches the *identifiers* returned by a query (and relies on L2 for the entities).

Interaction with transactions:
- L2 updates are **transaction-aware**: Hibernate invalidates/updates L2 entries in coordination with the transaction (using a cache concurrency strategy: `READ_ONLY`, `NONSTRICT_READ_WRITE`, `READ_WRITE`, `TRANSACTIONAL`).
- `READ_WRITE` uses **soft locks** in L2 during a transaction and updates on commit — giving READ COMMITTED-like consistency to cached data.
- `TRANSACTIONAL` strategy requires a JTA transaction manager and a transactional cache provider; rare.
- The **query cache** is invalidated by writes to any table the query touches (via an "update timestamps" region). A misbehaving query cache can serve stale data if timestamps aren't maintained — a subtle correctness trap.
- On rollback, L2 changes staged during the transaction are discarded; on commit they're applied. This is why a `REQUIRES_NEW` write becomes visible to L2 independently of the outer transaction.

### 7.6 Open Session in View (OSIV)

> **Beginner aside — OSIV.** A pattern (and Spring Boot **default**, `spring.jpa.open-in-view=true`) where the Hibernate `Session`/persistence context stays open for the **whole web request**, including view/JSON serialization, so lazy associations can be loaded during rendering. Convenient but it (a) holds the persistence context — and sometimes the connection — far longer, (b) hides N+1 problems, (c) defers/obscures exceptions to serialization time. Many teams set `spring.jpa.open-in-view=false` and load needed data explicitly (fetch joins, DTO projections) inside the service transaction. Spring Boot logs a warning that OSIV is enabled by default.

### 7.7 Distributed transactions, sagas, and the outbox

- **XA/2PC** (via `JtaTransactionManager`) gives true atomicity across resources but is slow and brittle; avoid in microservices.
- **Saga**: a sequence of local transactions with compensating actions for rollback — eventual consistency, not ACID.
- **Transactional outbox**: write the business change **and** an "event to publish" row in the *same* local transaction; a separate relay reads the outbox and publishes to the broker (at-least-once). This is the standard way to get reliable "DB change + message" without XA. `@TransactionalEventListener(AFTER_COMMIT)` is a lightweight in-process cousin (but loses the event if the process dies before publishing — the outbox survives that).

### 7.8 Reactive transactions

In WebFlux/R2DBC there is **no thread-bound transaction** — the work hops threads. The transaction context flows through the **Reactor `Context`** instead. Use `ReactiveTransactionManager` + `TransactionalOperator`, or `@Transactional` on methods returning `Mono`/`Flux` (the publisher must be subscribed within the operator's scope). Blocking JDBC transactions and reactive ones don't mix.

### 7.9 Timeout mechanics

`timeout` is **not** a hard interrupt. Spring records a deadline in `TransactionSynchronizationManager`; on each subsequent resource access (e.g., a JDBC statement via Spring's `DataSourceUtils`), it checks the deadline and throws `TransactionTimedOutException` if exceeded, and applies the remaining time as the JDBC statement query timeout where supported. A statement already running won't be interrupted mid-flight unless the driver/DB enforces a statement timeout. Pair with DB-side `statement_timeout` for real enforcement.

### 7.10 `@Transactional` on the class vs. method; meta-annotations

- On a **class**, it applies to all public methods; method-level annotations override.
- You can build **composed annotations**: e.g. `@Transactional(readOnly = true, propagation = SUPPORTS)` wrapped as a custom `@ReadOnlyTransactional` meta-annotation for consistency across a codebase.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Propagation decision rules

| Want | Use |
|---|---|
| Default: one transaction for the whole business operation | `REQUIRED` |
| Sub-operation must commit even if outer fails (audit, logging) | `REQUIRES_NEW` |
| Best-effort sub-step; on failure undo just that step but keep the rest | `NESTED` |
| Method works inside a tx if one exists, fine without | `SUPPORTS` |
| Method must be called within a tx (enforce) | `MANDATORY` |
| Method must run with NO tx (e.g., a long non-tx operation) | `NOT_SUPPORTED` |
| Method must never be called within a tx (catch misuse) | `NEVER` |

### 8.2 `REQUIRES_NEW` vs `NESTED`

| | `REQUIRES_NEW` | `NESTED` |
|---|---|---|
| Physical transactions | Two (independent) | One (with savepoint) |
| Connections used | Two simultaneously | One |
| Inner commit visible if outer rolls back? | Yes (already committed) | No (savepoint is within outer) |
| Outer rollback undoes inner? | No | Yes |
| Manager support | All | DataSourceTxManager; JPA only on capable dialects; not JTA |
| Pool risk | High (double usage) | Low |

### 8.3 Optimistic vs pessimistic locking

| | Optimistic (`@Version`) | Pessimistic (`FOR UPDATE`) |
|---|---|---|
| When | Low contention | High contention on hot rows |
| Cost | Cheap reads; retry on conflict | Blocks other writers; deadlock risk |
| Failure mode | `OptimisticLockException` → retry | Lock-wait timeout / deadlock |
| Scales | Reads scale well | Serializes writers |

### 8.4 Declarative vs programmatic

Use **declarative** (`@Transactional`) for 95% of service methods — clean, consistent. Use **programmatic** (`TransactionTemplate`) when you need fine-grained, dynamic boundaries: partial commits in a loop, conditional transaction scope, or inside framework/library code where annotations can't reach.

### 8.5 Local tx vs XA vs outbox/saga

Use when…
- **Local transaction**: all writes hit one database. (Default — by far the most common.)
- **XA/JTA**: genuinely need atomic commit across two resources, low volume, can tolerate latency, operate the TM. Rare/avoid in microservices.
- **Outbox + relay**: need "DB write + reliable message" without XA. The modern default for event-driven systems.
- **Saga**: long-running business process across services; eventual consistency acceptable.

---

## 9. Failure modes & debugging

### 9.1 "My `@Transactional` does nothing"

Symptoms: data persists despite an exception, or rollback never happens.
Causes & checks:
- **Self-invocation** — internal `this.method()` call (§7.1). Fix: separate bean / self-inject / AspectJ.
- **Non-public method** — Spring AOP only advises public methods. Make it public or use AspectJ.
- **`final` method/class** — CGLIB can't override; no proxy. Remove `final`.
- **Bean not Spring-managed** — `new`ed manually, so no proxy at all.
- **Wrong `@Transactional`** — using `jakarta`/`javax` one where you expected Spring semantics, or two annotations on the classpath.
Diagnose: log `TransactionSynchronizationManager.isActualTransactionActive()` at the start of the method; enable SQL logging and look for `BEGIN`/`COMMIT`.

### 9.2 Connection pool exhaustion / `Connection is not available, request timed out`

Cause: transactions held open too long (slow I/O inside, `REQUIRES_NEW` doubling, leaked transactions), exceeding `maximumPoolSize`.
Diagnose: HikariCP logs "Timeout failure stats", Micrometer `hikaricp.connections.pending` rising, PostgreSQL `pg_stat_activity` rows in `idle in transaction`. Fix: shorten transactions, move I/O out, raise pool size cautiously, set `idle_in_transaction_session_timeout`, reduce `REQUIRES_NEW` usage.

### 9.3 `LazyInitializationException`

> **Beginner aside.** Thrown when you access a lazily-loaded association *after* the persistence context/session has closed (i.e., outside the transaction). The proxy has no session to fetch from.
Cause: returning entities from a service and lazy-loading in the controller/view with OSIV off. Fix: fetch what you need inside the transaction (fetch joins, `@EntityGraph`, DTO projections), or (not recommended) enable OSIV.

### 9.4 `UnexpectedRollbackException: Transaction rolled back because it has been marked as rollback-only`

Cause: an inner `REQUIRED` method threw/marked rollback-only; the outer caught the exception and tried to commit (§3.6). Fix: don't swallow exceptions from participating transactions; use `REQUIRES_NEW`/`NESTED` if the sub-op should be independent; or let the exception propagate.

### 9.5 Transaction "lost" across threads

Cause: submitting work to an `ExecutorService`/`@Async` inside a transaction; the new thread has no bound resource (§2.5). The child runs without (or in its own) transaction. Fix: keep the transaction on one thread; or pass data, not transactions; in reactive use the Reactor context.

### 9.6 Stale reads / serialization failures

PostgreSQL `SERIALIZABLE` throwing `40001` (`could not serialize access`) → translated to `CannotSerializeTransactionException`. This is **expected**; implement retry (§5.8). Deadlocks (`40P01`) similarly need retry or lock-ordering discipline.

### 9.7 Isolation level silently not applied

Some manager/dialect combos ignore a requested isolation level. Diagnose by checking the actual level via SQL (`SHOW transaction_isolation;` in PostgreSQL) inside the transaction, or by enabling Spring's `prepareConnection`/SQL logs. Flag as vendor-specific.

### 9.8 Real-world incident patterns

- **The audit that committed the bug**: a `REQUIRES_NEW` "log" wrote a partial record while the main op rolled back, leaving orphaned references. Lesson: be deliberate about what survives independently.
- **The Friday-night pool freeze**: a report endpoint did a 40-second HTTP call inside `@Transactional`; under load all 10 connections sat `idle in transaction` and the whole app stalled. Lesson: no I/O inside transactions; set timeouts.
- **The silent commit**: a service threw a checked `BusinessException` expecting rollback; default rules committed the half-finished order. Lesson: `rollbackFor = Exception.class`.

---

## 10. Interview drill

**Q1. How does `@Transactional` actually work under the hood?**
*Model answer:* Spring creates an AOP proxy (JDK dynamic or CGLIB) around the bean. Calls through the proxy hit a `TransactionInterceptor` that asks a `PlatformTransactionManager` to start or join a transaction (per propagation), binds the connection/`EntityManager` to the thread via `TransactionSynchronizationManager`, runs the method, then commits on normal return or rolls back per the rollback rules.
- *Probe: Why must the call go through the proxy?* Because the interceptor only runs on proxied invocations; `this.x()` bypasses it.
- *Probe: JDK vs CGLIB proxy?* JDK proxies interface methods; CGLIB subclasses the class (default in Boot, can't proxy final).
- *Probe: Where is "the current transaction" stored?* In `ThreadLocal`s in `TransactionSynchronizationManager`.

**Q2. What's the default rollback behavior and why is it a common bug?**
*Model answer:* Rolls back on unchecked (`RuntimeException`) and `Error`, commits on checked exceptions. Teams that throw checked business exceptions expecting rollback get a surprise commit; fix with `rollbackFor = Exception.class`.
- *Probe: How does Spring pick a rule?* Most-specific matching `RollbackRuleAttribute` by exception hierarchy distance.
- *Probe: Why this default historically?* EJB heritage — checked = recoverable business outcome.

**Q3. Explain the propagation levels with a scenario for `REQUIRES_NEW` vs `NESTED`.**
*Model answer:* (Give §8.2 table essence.) `REQUIRES_NEW` suspends the outer and runs an independent transaction on a second connection that commits on its own; `NESTED` uses a savepoint within the same transaction. Audit logging → `REQUIRES_NEW`; best-effort sub-step → `NESTED`.
- *Probe: Pool implications of `REQUIRES_NEW`?* Two connections held at once → exhaustion risk.
- *Probe: Does `NESTED` work with JPA/JTA?* Savepoints need driver/manager support; JTA generally doesn't; JPA only on capable dialects.

**Q4. What is the self-invocation problem and how do you fix it?**
*Model answer:* Calling a `@Transactional` method from within the same class bypasses the proxy, so no transaction. Fixes: move to another bean, self-inject the proxy, `AopContext.currentProxy()`, or AspectJ weaving.
- *Probe: Why doesn't AspectJ have the problem?* It weaves advice into bytecode, not via a proxy.
- *Probe: Do private methods get advised?* No (Spring AOP advises public only).

**Q5. What does `readOnly = true` do?**
*Model answer:* Sets Hibernate flush mode to manual (skips dirty checking/flushes), calls `Connection.setReadOnly(true)` (a driver hint), and is commonly used to route to read replicas. It's an optimization/intent hint, not a hard write guard.
- *Probe: Does it prevent writes?* Not by itself at the DB level.
- *Probe: Performance benefit?* No entity snapshots / dirty-check passes → less CPU and memory.

**Q6. How do transactions relate to the Hibernate persistence context and flush/commit?**
*Model answer:* The persistence context (L1 cache, unit of work) is bound to the transaction. Hibernate flushes (emits SQL) before queries and always before commit; commit makes it durable. Dirty checking issues UPDATEs without explicit saves.
- *Probe: Difference between flush and commit?* Flush = send SQL within the tx (still rollbackable); commit = make permanent.
- *Probe: What's `LazyInitializationException`?* Accessing a lazy association after the context closed.

**Q7. (Senior signal) When would you choose XA vs an outbox vs a saga?**
*Model answer:* XA for genuine atomic multi-resource commit, low volume, operationally tolerable — rare. Outbox for reliable "DB change + message" in one local transaction with a relay — the modern default. Saga for long-running cross-service processes accepting eventual consistency with compensations.
- *Probe: Why avoid XA in microservices?* 2PC latency, blocking, coordinator failure modes, ops burden.
- *Probe: Failure mode of `@TransactionalEventListener(AFTER_COMMIT)` vs outbox?* Event lost if process dies post-commit pre-publish; outbox survives.

**Q8. (Senior signal) You see `idle in transaction` connections piling up and pool timeouts. Walk me through diagnosis and fixes.**
*Model answer:* Identify long-held transactions (likely slow I/O inside `@Transactional`, OSIV, or `REQUIRES_NEW` doubling). Confirm via HikariCP pending metric and `pg_stat_activity`. Fixes: move I/O out of transactions, shorten scope, disable OSIV, add `idle_in_transaction_session_timeout` and per-method timeouts, size pool with the core-count formula.
- *Probe: Why does a long tx hurt MVCC engines specifically?* It pins old row versions → bloat and vacuum can't reclaim.
- *Probe: How does OSIV worsen this?* Holds the persistence context (and sometimes connection) through view rendering.

**Q9. (Senior signal) How do you pick an isolation level, and what's the cost of SERIALIZABLE?**
*Model answer:* Start at the DB default (usually READ COMMITTED); raise only for specific read-then-write invariants. SERIALIZABLE (esp. PostgreSQL SSI) gives full correctness but introduces serialization failures that require retry and reduces concurrency. Often a targeted pessimistic lock or `@Version` is cheaper than blanket SERIALIZABLE.
- *Probe: PostgreSQL vs MySQL defaults?* READ COMMITTED vs REPEATABLE READ.
- *Probe: How handle `40001`?* Retry the whole transaction with backoff.

**Q10. How do you combine `@Transactional` with `@Retryable` and `@Async`?**
*Model answer:* `@Retryable` must be the outer aspect so each retry is a fresh transaction; otherwise you retry inside a rollback-only transaction → `UnexpectedRollbackException`. `@Async` runs on another thread with its own transaction context — it doesn't inherit the caller's transaction; design accordingly.
- *Probe: How control aspect order?* `@Order` / `@EnableTransactionManagement(order=…)`.
- *Probe: Why doesn't async inherit the transaction?* Transactions are thread-bound; the async thread has no bound resource.

**Q11. What happens, step by step, when a participating (`REQUIRED`) inner method throws and the outer catches it?**
*Model answer:* The inner marks the shared transaction rollback-only. The outer's catch doesn't clear that flag. At outer commit, Spring sees rollback-only, rolls back the physical transaction, and throws `UnexpectedRollbackException`.
- *Probe: How to avoid?* Use `REQUIRES_NEW`/`NESTED` for the sub-op, or don't swallow.
- *Probe: Can the outer clear rollback-only?* No — once marked, the physical tx is doomed.

**Q12. Where should post-commit side effects (emails, messages) go?**
*Model answer:* In an `afterCommit` synchronization, typically via `@TransactionalEventListener(AFTER_COMMIT)`, so they only fire if the data actually committed — or in an outbox for durability across crashes.
- *Probe: Risk of doing it inside the transaction?* Side effect happens even if the tx later rolls back.
- *Probe: Outbox vs after-commit listener?* Outbox survives process crash; listener doesn't.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability: the transaction guarantees.
- **Advice / Pointcut / Advisor** — AOP terms: the code to run, the predicate selecting where, and the bundle of both.
- **AOP** — Aspect-Oriented Programming; factoring cross-cutting concerns into aspects.
- **Autocommit** — JDBC mode where each statement commits immediately; turned off for managed transactions.
- **CGLIB** — bytecode library Spring uses to create subclass proxies for classes without interfaces.
- **Commit** — making a transaction's changes permanent and durable.
- **Connection pool (HikariCP)** — a cache of open DB connections borrowed per transaction.
- **Dirty checking** — Hibernate detecting changed entity fields and emitting UPDATEs automatically.
- **Dirty read** — reading another transaction's uncommitted data.
- **`EntityManager`** — JPA's interface to the persistence context.
- **First-level cache (L1)** — the persistence context; per-transaction entity map.
- **Flush** — sending pending SQL to the DB (not commit).
- **Isolation level** — degree of insulation between concurrent transactions.
- **JDBC** — Java's low-level database API; `Connection`, `Statement`, etc.
- **JPA / Hibernate** — the persistence specification and its dominant implementation.
- **JTA / XA / 2PC** — distributed transaction API, protocol, and the two-phase commit algorithm.
- **`LazyInitializationException`** — accessing a lazy association after the session closed.
- **MVCC** — Multi-Version Concurrency Control; readers see snapshots, writers create versions.
- **Non-repeatable read** — re-reading a row yields a changed value due to a committed concurrent update.
- **Open Session in View (OSIV)** — keeping the persistence context open for the whole web request.
- **Optimistic locking (`@Version`)** — conflict detection via a version column at write time.
- **Persistence context** — Hibernate's unit of work / L1 cache, bound to the transaction.
- **Pessimistic locking (`FOR UPDATE`)** — acquiring DB row locks to block concurrent writers.
- **Phantom read** — a range query returns new rows on re-execution due to a committed insert.
- **`PlatformTransactionManager`** — Spring's transaction strategy interface.
- **Propagation** — how a transactional method relates to an existing transaction.
- **Proxy** — stand-in object intercepting calls to add behavior (here, transactions).
- **Rollback** — undoing all changes since the transaction began.
- **Rollback-only flag** — marker on a participating transaction forcing the outer to roll back.
- **Savepoint** — an intra-transaction marker you can roll back to without aborting the whole transaction.
- **Second-level cache (L2)** — shared, cross-session cache at the `SessionFactory` level.
- **Serialization failure (`40001`)** — SERIALIZABLE conflict requiring a retry.
- **Synchronization** — lifecycle callback fired around commit/rollback.
- **`TransactionDefinition` / `TransactionStatus`** — desired vs running transaction descriptors.
- **`TransactionSynchronizationManager`** — thread-bound resource & synchronization registry.
- **`TransactionTemplate`** — programmatic transaction helper.
- **`UnexpectedRollbackException`** — thrown when commit is attempted on a rollback-only transaction.
- **WAL (write-ahead log)** — sequential durability log replayed on crash recovery.
- **Transactional outbox** — pattern: write business data + event row in one tx; relay publishes later.
- **Saga** — sequence of local transactions with compensations for cross-service consistency.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Mechanism:** `@Transactional` → AOP proxy (CGLIB default in Boot) → `TransactionInterceptor` → `PlatformTransactionManager`. Resources are thread-bound via `TransactionSynchronizationManager`.
- **Default rollback:** unchecked + `Error` roll back; **checked exceptions COMMIT**. Use `rollbackFor = Exception.class`.
- **Propagation (default REQUIRED):** REQUIRED(join/new) · REQUIRES_NEW(suspend+independent, 2 connections) · NESTED(savepoint, 1 connection) · SUPPORTS · NOT_SUPPORTED(suspend, non-tx) · MANDATORY(throw if none) · NEVER(throw if exists).
- **Isolation defaults:** PostgreSQL READ COMMITTED; MySQL/InnoDB REPEATABLE READ; Oracle only RC/SERIALIZABLE.
- **Read phenomena ladder:** dirty < non-repeatable < phantom; prevented progressively RU → RC → RR → SERIALIZABLE.
- **`readOnly=true`:** Hibernate flush mode MANUAL (no dirty checks), `Connection.setReadOnly`, replica routing.
- **Flush ≠ commit.** Flush sends SQL (rollbackable); commit makes durable. Hibernate flushes before queries and at commit.
- **Two famous bugs:** self-invocation bypasses proxy; checked exceptions don't roll back by default.
- **`UnexpectedRollbackException`:** inner REQUIRED marked rollback-only, outer tried to commit.
- **Pool rule of thumb:** `(cores*2)+spindles`; default Hikari `maximumPoolSize=10`, `connectionTimeout=30s`.
- **Hardening:** short transactions, no I/O inside, set `timeout` + DB `statement_timeout`/`idle_in_transaction_session_timeout`, disable OSIV (`spring.jpa.open-in-view=false`).
- **Post-commit side effects:** `@TransactionalEventListener(AFTER_COMMIT)` or outbox.
- **Retry pattern:** `@Retryable` OUTSIDE `@Transactional`; handle `40001` with backoff.

### 12.2 Self-test (no answers — active recall)

1. Trace exactly what `TransactionAspectSupport.invokeWithinTransaction` does on a normal return, naming where Hibernate flushes and where the physical commit happens.
2. You have `outer()` `@Transactional(REQUIRED)` calling `inner()` `@Transactional(REQUIRES_NEW)`; `inner()` commits, then `outer()` throws. What persists, and why? How does the answer change if `inner()` were `NESTED`?
3. A teammate annotates a `private` helper with `@Transactional` and reports it "isn't working." Give three independent reasons it might silently no-op and how to confirm each.
4. Explain why a 30-second outbound HTTP call inside a `@Transactional` method can take down an entire service, including the specific metrics and DB views you'd inspect.
5. Your service throws a checked `PaymentDeclinedException` expecting a rollback, but the order row persists. Diagnose and give two ways to fix it.
6. On PostgreSQL you switch a hot path to `Isolation.SERIALIZABLE` and start seeing intermittent failures in production. What error class appears, why, and what is the correct handling (including aspect ordering with `@Retryable`)?
7. Describe how the second-level cache stays consistent with a `READ_WRITE` concurrency strategy across commit and rollback, and what a stale query cache looks like.

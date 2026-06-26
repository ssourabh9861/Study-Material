# CQRS & Event Sourcing

> An engineering handbook chapter. Reader profile: a senior Java/JVM backend developer who wants to fully master CQRS and Event Sourcing — from first principles to deep internals — well enough to design with them, operate and debug them in production, teach them, and answer any interview question on them.

---

## 1. Overview & where it fits

**CQRS (Command Query Responsibility Segregation)** and **Event Sourcing (ES)** are two *distinct* patterns that are frequently used together but are independent. Confusing them is the single most common mistake practitioners make, so we pin the definitions down first:

- **CQRS** splits the model you use to **change** state (the *write side*, driven by **commands**) from the model(s) you use to **read** state (the *read side*, driven by **queries**). Instead of one object/table/service serving both reads and writes, you have at least two — possibly two databases, two schemas, even two services. That is the *entire* idea. CQRS says nothing about *how* you store data.
- **Event Sourcing** changes *how you persist state*. Instead of storing the **current state** of an entity (a row you overwrite via `UPDATE`), you store the **full, ordered, immutable sequence of events** that happened to that entity. The current state is *derived* by replaying those events. The event log — not a snapshot table — is the **source of truth**.

You can do CQRS with ordinary CRUD tables on both sides. You can do Event Sourcing without CQRS (a single model that rebuilds from events for both reads and writes). But they combine *naturally*: ES gives you a stream of events; CQRS uses those events to build optimized read models. That synergy is why the two are taught together.

**The problem they solve.** A traditional system uses a single normalized model (often one ORM-mapped domain model over a relational schema) for everything. This works beautifully until:

1. **Reads and writes have opposite requirements.** Writes want strong consistency, validation, and normalization; reads want denormalized, pre-joined, fast-to-serve shapes. One model forced to satisfy both becomes a compromise that satisfies neither. Read traffic (often 10×–1000× write traffic) can't be scaled independently.
2. **You need history, not just "now."** Auditors, regulators, debugging, analytics, and "what did this account look like on March 3rd?" questions require the *path*, not just the destination. A row that was overwritten has thrown its history away.
3. **The domain is genuinely behavior-rich.** Some domains (trading, banking, logistics, insurance, healthcare) are *defined* by their sequences of facts. Modeling them as state mutations loses the domain's natural language.

**When you reach for them.** Reach for **CQRS** when read and write workloads diverge enough that one model hurts (asymmetric load, asymmetric shape, asymmetric consistency needs), or when a complex domain benefits from a task-based write model. Reach for **Event Sourcing** when history/audit is a hard requirement, when temporal queries matter, when you need to replay to rebuild new views, or when the domain is event-centric. Reach for **both together** when you want event-driven read models that scale independently and a perfect audit trail.

**When you should NOT.** For simple CRUD apps, internal tools, or domains where "current state" is all anyone ever needs, both patterns add cost (complexity, eventual consistency, operational burden, event versioning) far exceeding their benefit. The honest default for most software is *not* to use either. We dedicate a full section (§8) to this.

**One-paragraph mental model.** Picture an accountant's ledger. You never erase an entry; you *append* a new one (a debit, a credit, a correction). The ledger is the immutable truth (Event Sourcing). To answer "what's the balance?" you don't re-read every entry each time — you maintain a running tally on a separate sheet (a read model / projection). Different questions get different sheets: a balance sheet, a tax report, a monthly statement, all derived from the same ledger (CQRS read models). Commands ("post a payment") go through the accountant who validates them and writes a new ledger entry; queries ("show me the statement") read the pre-computed sheets. The ledger is the write side; the sheets are the read side; the accountant enforces the rules.

---

## 2. Foundations from first principles

We build up the vocabulary deliberately, because the rest of the document leans on it.

### 2.1 The starting point: CRUD and the single model

**CRUD** stands for **Create, Read, Update, Delete** — the four basic operations on persistent records. A classic layered app maps an HTTP request to a service method to a repository to a SQL table. The *same* `Account` object is loaded, mutated, and saved; the *same* object is serialized to JSON for reads. This is **state-oriented persistence**: the database holds the *current* state and overwrites it on each change. It is simple, well-understood, and correct for the majority of systems.

The limitations only bite at the margins:

- **Lost intent.** An `UPDATE accounts SET balance = 90 WHERE id = 1` records *what* the balance became but not *why* — was it a withdrawal? a fee? a correction? The reason is gone.
- **Lost history.** The previous balance is overwritten. To recover it you need a separate audit table that you must remember to maintain and keep consistent.
- **Read/write coupling.** The schema that's good for transactional writes (normalized, foreign keys) is rarely the schema that's fast for complex reads (which want denormalized, pre-aggregated data).

### 2.2 Commands vs. Queries (the CQS principle)

Before CQRS there was **CQS (Command Query Separation)**, a principle articulated by Bertrand Meyer (creator of the Eiffel language) in the late 1980s. **CQS at the method level says: every method is either a Command (it changes state and returns nothing) or a Query (it returns data and changes nothing) — never both.** `account.deposit(100)` (command, mutates, returns void) vs. `account.getBalance()` (query, pure, returns a number). The benefit: queries are side-effect-free and therefore safe to call repeatedly, cache, and reason about.

**CQRS scales CQS up from methods to whole models/architectures.** The term was coined by **Greg Young** (~2010), building on Meyer's CQS. Where CQS separates *methods*, CQRS separates the *objects/models* that handle the command path from those that handle the query path. The leap is recognizing that once the two paths are separate models, they don't even need the same data store, the same schema, or the same consistency guarantees.

- **Command** — an *imperative request to change state*, expressed in the domain's language and named in the **imperative mood**: `TransferFunds`, `CancelOrder`, `RegisterUser`. A command can be *rejected* (validation fails, business rule violated). A command is a request — it may not succeed.
- **Query** — a request to *read* state without changing it: `GetAccountBalance`, `ListOpenOrders`. Queries hit read models, never the write model.

> **Beginner aside — "imperative mood":** Commands are named as instructions you give ("Transfer funds!"), not as facts ("funds were transferred"). The second phrasing — past tense — is reserved for *events* (next).

### 2.3 Events and the event log

An **event** is an *immutable record of something that already happened* in the domain, named in the **past tense**: `FundsTransferred`, `OrderCancelled`, `UserRegistered`. Key properties:

- **Immutable.** Once it happened, it happened. You never edit or delete an event. (To "undo" a mistake you append a *compensating* event — e.g. `PaymentRefunded` — rather than deleting `PaymentTaken`.)
- **Past tense / factual.** An event is not a request; it's a recorded fact. It cannot be "rejected" — it already occurred.
- **Carries intent + data.** `FundsWithdrawn{accountId, amount: 100, reason: "ATM", at: ...}` records both what changed and why.

The **event log** (a.k.a. **event store**, **event stream**) is the **ordered, append-only sequence of all events**. In Event Sourcing this log *is the database*. The crucial inversion versus CRUD:

| | CRUD (state-oriented) | Event Sourcing (event-oriented) |
|---|---|---|
| Source of truth | Current-state row | The event log |
| Write operation | `UPDATE`/`INSERT`/`DELETE` | `APPEND` event(s) |
| Current state | Stored directly | **Derived** by replaying events |
| History | Lost (or in a side table) | Inherent — the log *is* the history |
| Mutability | Mutable rows | Immutable, append-only |

> **Beginner aside — "append-only":** You only ever add to the end; you never modify or remove what's already written. This is the same model as a write-ahead log, a Kafka topic partition, or a Git commit history. Append-only storage is simpler to make durable, easy to replicate, and trivially auditable because nothing is ever rewritten.

### 2.4 Aggregates, streams, and rebuilding state

Borrowing from **Domain-Driven Design (DDD)**:

> **Beginner aside — DDD:** Domain-Driven Design (Eric Evans, 2003) is an approach to modeling software around the business domain's concepts and language. Two relevant building blocks: an **Entity** has a unique identity that persists over time; an **Aggregate** is a cluster of entities/values treated as a single consistency boundary, with one **Aggregate Root** as the entry point. All changes to an aggregate go through its root, which enforces the aggregate's invariants (rules that must always hold).

In Event Sourcing, **each aggregate instance has its own event stream**, usually keyed by the aggregate ID. For an account with ID `acct-42`, the stream `acct-42` holds, in order: `AccountOpened`, `FundsDeposited(100)`, `FundsWithdrawn(30)`, `FundsDeposited(50)`. 

**Rebuilding state ("rehydration"/"folding"):** to get the current state of `acct-42`, you start from an empty/initial aggregate and **apply** each event in order. This is a **left fold** (reduce) over the event list:

```
state = events.reduce(initialState, (s, e) -> apply(s, e))
```

After applying the four events above, balance = 0 + 100 − 30 + 50 = 120. The aggregate's in-memory state is *purely a function of its events*. That property is the foundation of everything: it means state is reproducible, debuggable (replay to any point), and disposable (you can throw the in-memory state away and rebuild it anytime).

### 2.5 Snapshots

Replaying *all* events on every load is fine for short streams but expensive for long-lived aggregates with thousands or millions of events. A **snapshot** is a periodically-saved serialized copy of an aggregate's state at a known event version (e.g. "state as of version 10,000"). To rehydrate, you load the latest snapshot and replay only the events *after* it. Snapshots are a pure **optimization/cache** — they are *derived* data and can always be discarded and regenerated from the event log, which remains the sole source of truth.

### 2.6 Projections and read models

A **read model** (a.k.a. **materialized view**, **query model**) is a data structure shaped for a *specific query*, kept in whatever store best serves that query (relational table, document store, search index, cache, columnar warehouse). A **projection** is the *process/handler* that consumes events and updates a read model. "Project the events into a read model" = "feed each event to a handler that maintains a query-optimized view."

The same event stream can feed *many* projections simultaneously: one builds a SQL table for the account dashboard, another updates an Elasticsearch index for search, another increments counters in Redis for real-time metrics, another writes denormalized documents for a mobile API. This many-views-from-one-log capability is the chief reason CQRS+ES scales reads.

### 2.7 Eventual consistency

When the write side (event log) and the read side (projections) are separate, there's a **propagation delay**: a command appends an event, and *some time later* the projection processes it and updates the read model. Between those moments, a query may return **stale** data. This is **eventual consistency** — the system *will* converge to a consistent state, but not instantaneously.

> **Beginner aside — strong vs. eventual consistency:** *Strong consistency* means every read reflects all prior writes immediately (a single relational transaction gives you this). *Eventual consistency* means reads may lag, converging "eventually" (milliseconds to seconds typically). CQRS read models are almost always eventually consistent relative to the write side. This is the single biggest behavioral change for developers and users to absorb, and §6 covers how to cope with it.

> **Beginner aside — CAP theorem (since it always comes up):** CAP (Eric Brewer) says a distributed data store, when a **network partition (P)** occurs, must choose between **Consistency (C)** (every read sees the latest write) and **Availability (A)** (every request gets a non-error response). You cannot have all three during a partition. CQRS read models lean toward AP: they stay available and serve possibly-stale data, reconciling later. The write side typically stays CP within a single aggregate.

With these terms in hand, we can go deep on mechanics.

---

## 3. How it works internally

This is the heart of the document. We trace the full lifecycle of a write and a read, the rehydration algorithm, the projection pipeline, concurrency control, snapshotting, and replay/rebuild — step by step.

### 3.1 The end-to-end command lifecycle (write side)

Consider a `WithdrawFunds` command in an event-sourced, CQRS system. Step by step:

1. **Command arrives.** An HTTP/RPC/messaging endpoint receives a request and constructs a strongly-typed command object: `WithdrawFunds{accountId: "acct-42", amount: 30, requestId: "uuid-...", expectedVersion: 3}`.
2. **Command routing.** A **command bus**/dispatcher routes the command to the registered **command handler** for its type. (A command bus is just an in-memory dispatcher mapping command type → handler; it's not necessarily a network hop.)
3. **Load / rehydrate the aggregate.** The handler asks the repository for `acct-42`. The repository:
   - a. Loads the latest **snapshot** for `acct-42` if one exists (say, state at version 0 if none).
   - b. Reads all events from the event store for stream `acct-42` *after* the snapshot's version, in order.
   - c. **Folds** them: starts with the snapshot state (or empty), calls `apply(event)` for each, producing the current in-memory aggregate (balance = 120, version = 3).
4. **Optimistic concurrency check (optional but standard).** The aggregate's loaded version (3) is compared with the command's `expectedVersion` (3). If a concurrent writer had advanced the stream to version 4, the check fails fast — we'll see why this matters in §3.5.
5. **Invoke domain behavior.** The handler calls `account.withdraw(30)`. **This method does NOT mutate state directly.** It:
   - a. **Validates invariants** against current in-memory state (e.g. balance ≥ amount; account not frozen). If a rule fails, it throws/returns a rejection — *no event is produced*, nothing is persisted.
   - b. If valid, it **creates a new event** `FundsWithdrawn{amount: 30, ...}` and **records it as a pending/uncommitted event** on the aggregate.
   - c. It then **applies** that event to itself via the same `apply()` used during rehydration (so in-memory state moves to balance = 90, version = 4). Using the *same* apply path for new and replayed events guarantees behavior is identical whether an event is fresh or historical.
6. **Persist (append) events atomically.** The repository appends the uncommitted event(s) to the event store stream `acct-42`, asserting an **expected version** (append only if the stream is still at version 3). This append is the *single point of strong consistency* in the whole system. If it succeeds, the event is now an immutable fact. If the expected-version assertion fails (someone else wrote concurrently), the store rejects the append with a concurrency error.
7. **Publish events.** After a durable append, the new events are **published** to interested consumers: projection handlers (to update read models), process managers/sagas (to coordinate multi-step workflows), and integration consumers (to notify other services). Publication can be via an in-process event bus, an outbox + relay, or a poll of the event store. (How publication achieves at-least-once delivery is a critical reliability detail covered in §3.4 and §6.)
8. **Acknowledge the command.** The endpoint returns. Note: it typically returns *only* the fact that the command was accepted/applied and the new aggregate version — **not** the updated read model (which may not have caught up yet). This is the eventual-consistency contract surfacing in the API.

### 3.2 The rehydration (fold) algorithm in detail

Pseudocode for loading an aggregate:

```
load(aggregateId):
    snapshot = snapshotStore.getLatest(aggregateId)         # may be null
    state    = snapshot ? snapshot.state : emptyState()
    version  = snapshot ? snapshot.version : 0
    events   = eventStore.readStream(aggregateId, fromVersion = version + 1)
    for e in events (in ascending version order):
        state = apply(state, e)     # pure transition, no side effects
        version = e.version
    return Aggregate(state, version)
```

Properties that matter:

- **Determinism.** `apply` must be a **pure function** of (state, event) — no clocks, no random, no I/O, no calls to external services. If `apply` depended on `now()` or a remote lookup, replaying the same events later would produce *different* state, breaking the core guarantee. Any nondeterministic input (timestamps, generated IDs, results of external calls) must be **captured into the event at the time it's created** and read back from the event during replay.
- **Order matters.** Events within a stream are strictly ordered by version. Cross-stream global order is a separate, harder problem (§7).
- **Cost.** Without snapshots, load cost grows linearly with stream length. With snapshots every N events, load reads at most N events plus one snapshot.

### 3.3 The projection pipeline (read side)

A projection turns the event stream into a read model. Step by step for a relational read model maintained by a worker:

1. **Subscribe / poll.** The projection maintains a **checkpoint** — the position (global sequence number or per-stream version) up to which it has processed. It reads events *after* the checkpoint from the event store (push subscription or periodic poll/catch-up).
2. **Dispatch by event type.** For each event, it routes to a handler: `on(FundsDeposited)`, `on(FundsWithdrawn)`, etc.
3. **Apply to the read model.** The handler issues writes to the read store: e.g. `UPDATE account_view SET balance = balance + ? WHERE id = ?`. Read-model writes are often **idempotent** or **upsert**-shaped to tolerate reprocessing.
4. **Advance the checkpoint.** After successfully applying an event (or a batch), it persists the new checkpoint. The ordering of "apply read-model write" vs. "save checkpoint" determines the delivery semantics:
   - If you **apply then checkpoint**, a crash between them causes the event to be **reprocessed** on restart → **at-least-once** → handlers must be **idempotent**.
   - If you **checkpoint then apply**, a crash loses the apply → **at-most-once** → data loss. (Almost nobody chooses this.)
   - **Exactly-once** to the read model is achievable only if the read-model write and the checkpoint write are in **one transaction** (same DB), or via idempotency keys.

> **Beginner aside — idempotent:** An operation is idempotent if doing it twice has the same effect as doing it once. `SET balance = 120` is idempotent; `balance = balance + 30` is not (running it twice adds 60). To make event processing safe under at-least-once delivery, you either use absolute/upsert writes, or record processed event IDs and skip duplicates.

5. **Catch-up vs. live.** A new projection starts at checkpoint 0 and reads the *entire* history (catch-up phase) to build its view from scratch, then switches to processing live events as they arrive. The ability to **start a brand-new projection and rebuild it from the full log** is the superpower of ES: you can add a new read model (or fix a buggy one) at any time without touching the write side.

### 3.4 How events get from the store to the projections reliably

Naively publishing to a message broker *after* committing to the DB risks a crash between the two, losing the publish. The standard solutions:

- **Built-in event store subscriptions.** Stores like EventStoreDB, Axon Server, or a Kafka log expose ordered subscriptions with server-tracked or client-tracked checkpoints; consumers poll/stream and resume from their checkpoint. The store *is* the broker.
- **Transactional Outbox.** When using a relational DB as the event store, the append and an "outbox" row are written in the **same DB transaction**. A separate **relay/poller** (or **Change Data Capture** off the DB's transaction log) reads the outbox and publishes to the broker, marking rows sent. This guarantees the publish happens iff the commit happened → **at-least-once**.

> **Beginner aside — CDC (Change Data Capture):** CDC tools (e.g. Debezium) tail a database's write-ahead/transaction log and emit a stream of every committed row change. Used with the outbox pattern, CDC turns committed DB writes into a reliable event stream without dual-write race conditions.

- **Polling the event store directly.** Projections simply poll `read events after checkpoint X` on an interval. Simple, robust, slightly higher latency.

The recurring theme: **never dual-write** to two systems without a single committing transaction or a log you replay; always derive the second system from the first.

### 3.5 Concurrency control on the write side

Two commands targeting the same aggregate concurrently could both load version 3, both decide to withdraw, and both try to append — potentially overspending the balance. The standard guard is **optimistic concurrency control (OCC)** at the event store:

> **Beginner aside — optimistic vs. pessimistic locking:** *Pessimistic* locking takes a lock before reading (others wait). *Optimistic* assumes conflicts are rare: it reads without locking, then at write time checks "did anything change since I read?" — if so, it fails and the caller retries. ES stores implement OCC via **expected version**: the append says "append these events *only if* the stream is currently at version N." The store performs this check-and-append atomically. The first writer wins; the second gets a concurrency exception and retries by reloading the now-newer aggregate and re-evaluating its command.

This makes the **aggregate the consistency boundary**: invariants are enforced strongly *within* one aggregate (one stream, serialized via expected-version appends), while *across* aggregates consistency is eventual (coordinated by sagas/process managers, §7). Designing aggregate boundaries correctly — small enough to avoid contention, large enough to enclose true invariants — is the central modeling skill.

### 3.6 Snapshotting workflow

1. **Trigger.** A policy decides when to snapshot: every N events (common: 50–500), every T time, or on load if (currentVersion − snapshotVersion) exceeds a threshold.
2. **Capture.** Serialize the current aggregate state plus its version. Store it keyed by aggregate ID, often keeping the **latest** (or last K) snapshots.
3. **Use on load.** Repository loads latest snapshot, replays only events after it.
4. **Invalidation on schema change.** If the aggregate's state shape (snapshot format) changes, *old snapshots must be discarded* and regenerated, because they encode the old shape. The event log is unaffected (events have their own versioning, §7). **Never** treat snapshots as authoritative — always be able to delete them all and rebuild purely from events.

### 3.7 The full event lifecycle / state machine

A single event's journey:

```
[created in aggregate, uncommitted]
        │  append (with expected version)
        ▼
[durably stored, immutable, assigned stream version + global position]   ← source of truth, permanent
        │  published (at-least-once)
        ├──► [consumed by projection P1] → read model R1 updated → checkpoint advanced
        ├──► [consumed by projection P2] → read model R2 updated → checkpoint advanced
        ├──► [consumed by saga/process manager] → may emit new commands
        └──► [consumed by integration relay] → published to other bounded contexts
```

The event is born once, persisted once, and **fanned out** to N independent consumers, each tracking its own checkpoint and each free to fail and retry without affecting the others. Adding a new consumer later simply means starting a new checkpoint at 0 and catching up through history.

---

## 4. The complete toolkit

Because CQRS/ES is a *pattern* family rather than a single library, the "toolkit" spans (a) conceptual building blocks, (b) frameworks/products in the JVM ecosystem, (c) event-store products, and (d) the operations each component exposes. Tables follow.

### 4.1 Core building blocks (vocabulary → responsibility)

| Building block | Responsibility | Key parameters / shape | Typical default |
|---|---|---|---|
| Command | Request to change state | Target aggregate ID, payload, optional `expectedVersion`, `requestId` (idempotency) | n/a |
| Command Handler | Loads aggregate, invokes behavior, persists events | Maps 1 command type → 1 handler | n/a |
| Command Bus / Gateway | Routes commands to handlers | Sync vs async; interceptors | Usually synchronous, in-process |
| Aggregate (root) | Enforces invariants; emits events; consistency boundary | `apply(event)` (pure), `handle(command)` | n/a |
| Event | Immutable past-tense fact | Type, payload, version, timestamp, metadata | n/a |
| Event Store | Append-only persistence of streams | Stream ID, expected version, global position | n/a |
| Snapshot Store | Cached aggregate state at a version | Snapshot interval N | N = 50–500 (tunable) |
| Event Bus / Publisher | Delivers committed events to consumers | Delivery semantics, ordering | At-least-once |
| Projection / Projector | Builds & maintains a read model from events | Checkpoint, event→handler map, batch size | n/a |
| Read Model / Materialized view | Query-optimized data store | Backing store (SQL/NoSQL/search/cache) | n/a |
| Checkpoint / Token | Position a consumer has processed up to | Global seq or per-stream version | Starts at 0 / beginning |
| Process Manager / Saga | Coordinates multi-aggregate workflows via events→commands | State, timeout handlers, compensations | n/a |

### 4.2 JVM / Java frameworks & libraries

| Tool | What it is | Notes / version flags |
|---|---|---|
| **Axon Framework** | The dominant Java CQRS/ES framework: command bus, aggregates (`@Aggregate`, `@CommandHandler`, `@EventSourcingHandler`), event store, query bus, sagas, projections. | Open source (Java). Pairs with **Axon Server** (its purpose-built event store) or JPA/JDBC stores. Annotation-driven. |
| **Axon Server** | Dedicated event store + message router for Axon. | Free SE edition; commercial EE adds clustering. Version-specific features. |
| **EventStoreDB (KurrentDB) Java client** | Client for EventStoreDB, a purpose-built event-sourcing database (streams, catch-up subscriptions, projections engine). | Cross-language; gRPC client. |
| **Eventuate (Tram / Local)** | Chris Richardson's libraries for sagas and event sourcing on the JVM, often with Kafka + RDBMS + CDC. | Focus on microservice sagas + transactional messaging. |
| **Spring + Spring Data + Spring Modulith** | Not a CQRS framework per se, but commonly assembled into one: Spring Data for read models, `ApplicationEventPublisher`/Modulith for in-process events, an outbox table for reliability. | Roll-your-own flavor. |
| **Kafka + Kafka Streams** | Log as event backbone; Kafka Streams builds materialized views (state stores) — effectively projections; changelog topics persist them. | Kafka is *not* a general event store for per-aggregate OCC out of the box (see §4.4 & §7.5). |
| **Debezium** | CDC connector; tails DB logs to produce event/outbox streams into Kafka. | Used for the outbox pattern. |
| **Akka Persistence (Pekko)** | Actor-based event sourcing on the JVM; each actor persists events and recovers by replay; supports snapshots and projections. | Akka now BSL-licensed; **Apache Pekko** is the open-source fork. Version/license sensitive. |
| **Marten** (.NET) / **EventStoreDB** / **Axon** | (Marten is .NET-only; listed for awareness in polyglot shops.) | Not JVM. |

### 4.3 Axon's primary annotations/APIs (concrete, Java)

| Annotation / API | Purpose | Notes |
|---|---|---|
| `@Aggregate` | Marks an aggregate root managed by Axon. | Class-level. |
| `@AggregateIdentifier` | Marks the field that is the aggregate's ID / stream key. | Required. |
| `@CommandHandler` | Method (or constructor) that handles a command; validates and applies events. | On constructor → creates aggregate. |
| `apply(Object event)` | Static `AggregateLifecycle.apply(...)`: records and applies a new event. | Routes to `@EventSourcingHandler`. |
| `@EventSourcingHandler` | Pure state-transition method; called both during replay and for new events. | Must be side-effect-free. |
| `@QueryHandler` | Handles a query against a read model. | On the query side. |
| `@EventHandler` | Projection handler updating a read model. | Has a processor + token store. |
| `@Saga` / `@SagaEventHandler` / `@StartSaga` / `@EndSaga` | Process-manager lifecycle. | Coordinates aggregates. |
| `TokenStore` | Persists projection checkpoints (tracking tokens). | Enables resume/replay. |
| `@CreationPolicy` | Lets a command handler create-or-load. | Newer Axon versions. |

### 4.4 Event-store products and their relevant operations

| Product | Append API / semantics | Subscriptions | Concurrency control | Built-in projections |
|---|---|---|---|---|
| **EventStoreDB / KurrentDB** | `appendToStream(stream, expectedRevision, events)` | Catch-up + persistent subscriptions | `expectedRevision` (OCC per stream) | Yes (JS projections engine) |
| **Axon Server** | Append events tied to aggregate + sequence | Event processors with tracking tokens | Sequence number per aggregate | Via Axon processors |
| **Relational DB (custom)** | `INSERT` into `events(stream_id, version, ...)` with `UNIQUE(stream_id, version)` | Poll by global `position`; or CDC | Unique constraint enforces OCC | Build your own |
| **Kafka (as log)** | `produce(topic, key=aggregateId, event)` | Consumer groups, offsets | **No native per-key expected-version**; needs compaction/transactions/external dedup | Kafka Streams materialized views |
| **DynamoDB (custom)** | Conditional `PutItem` on `(streamId, version)` | DynamoDB Streams + Lambda | Conditional write = OCC | Build your own |

> **Important version/vendor flag:** **EventStoreDB** was renamed/rebranded toward **Kurrent/KurrentDB** in 2024–2025; APIs and product names depend on version. **Akka** moved to the BSL license (2022); the open-source continuation is **Apache Pekko**. Always check the exact version's docs for current method names and licensing.

### 4.5 Read-model store choices (for projections)

| Store | Good when the query needs… | Example |
|---|---|---|
| Relational (Postgres/MySQL) | Joins, ad-hoc filters, transactions with checkpoint | Account statement table |
| Document (Mongo) | Denormalized per-screen documents | Mobile API view |
| Search (Elasticsearch/OpenSearch) | Full-text, faceted search, ranking | Product search |
| Cache (Redis) | Counters, leaderboards, fast key lookups | Real-time metrics |
| Columnar/warehouse (BigQuery, Redshift, ClickHouse) | Analytics, aggregations over huge data | Reporting |
| Graph (Neo4j) | Relationship traversal | Recommendations |

---

## 5. Code examples by use case

These are idiomatic Java examples spanning *different* scenarios. The first builds the pattern by hand (so you see the mechanics with zero magic); later ones use Axon and Kafka Streams. Non-obvious lines are commented.

### 5.1 Hand-rolled event-sourced aggregate (no framework) — Banking account

This shows the pure mechanics: command → validate → emit event → apply → append → rehydrate.

```java
// ---------- Events (immutable past-tense facts) ----------
sealed interface AccountEvent permits AccountOpened, FundsDeposited, FundsWithdrawn {}
record AccountOpened(String accountId, String owner) implements AccountEvent {}
record FundsDeposited(String accountId, long amountCents, String reason) implements AccountEvent {}
record FundsWithdrawn(String accountId, long amountCents, String reason) implements AccountEvent {}

// ---------- Commands (imperative requests) ----------
sealed interface AccountCommand {}
record OpenAccount(String accountId, String owner) implements AccountCommand {}
record Deposit(String accountId, long amountCents, String reason) implements AccountCommand {}
record Withdraw(String accountId, long amountCents, String reason) implements AccountCommand {}

// ---------- The aggregate ----------
final class Account {
    private String id;
    private long balanceCents;          // money in integer cents — never use double for money
    private boolean opened;
    private int version = 0;            // number of applied events; used for optimistic concurrency
    private final List<AccountEvent> uncommitted = new ArrayList<>();

    // Rehydrate from history: pure fold over events.
    static Account rehydrate(List<AccountEvent> history) {
        Account a = new Account();
        for (AccountEvent e : history) a.apply(e);   // same apply() used for replay AND new events
        a.uncommitted.clear();                       // replayed events are already committed
        return a;
    }

    // ----- Command handling: validate invariants, then emit (do NOT mutate directly) -----
    void handle(OpenAccount c) {
        if (opened) throw new IllegalStateException("Account already opened");
        raise(new AccountOpened(c.accountId(), c.owner()));
    }
    void handle(Deposit c) {
        requireOpen();
        if (c.amountCents() <= 0) throw new IllegalArgumentException("Deposit must be positive");
        raise(new FundsDeposited(id, c.amountCents(), c.reason()));
    }
    void handle(Withdraw c) {
        requireOpen();
        if (c.amountCents() <= 0) throw new IllegalArgumentException("Withdraw must be positive");
        if (c.amountCents() > balanceCents)        // <-- the invariant: no overdraft
            throw new IllegalStateException("Insufficient funds");
        raise(new FundsWithdrawn(id, c.amountCents(), c.reason()));
    }

    private void raise(AccountEvent e) {
        apply(e);                 // move in-memory state forward
        uncommitted.add(e);       // queue for persistence
    }

    // ----- State transition: PURE function of (state, event). No I/O, no clock, no random. -----
    private void apply(AccountEvent e) {
        switch (e) {
            case AccountOpened ev   -> { this.id = ev.accountId(); this.opened = true; }
            case FundsDeposited ev  -> this.balanceCents += ev.amountCents();
            case FundsWithdrawn ev  -> this.balanceCents -= ev.amountCents();
        }
        this.version++;           // each applied event advances version
    }

    private void requireOpen() { if (!opened) throw new IllegalStateException("Account not opened"); }
    List<AccountEvent> uncommittedEvents() { return List.copyOf(uncommitted); }
    void markCommitted() { uncommitted.clear(); }
    int version() { return version; }
    long balanceCents() { return balanceCents; }
}
```

```java
// ---------- A minimal event store with optimistic concurrency ----------
interface EventStore {
    /** Append events, but only if the stream is at expectedVersion. Otherwise throw. */
    void append(String streamId, int expectedVersion, List<AccountEvent> events);
    List<AccountEvent> readStream(String streamId);
}

// In-memory reference implementation (production: Postgres/EventStoreDB/etc.)
final class InMemoryEventStore implements EventStore {
    private final Map<String, List<AccountEvent>> streams = new ConcurrentHashMap<>();

    @Override
    public synchronized void append(String streamId, int expectedVersion, List<AccountEvent> events) {
        List<AccountEvent> stream = streams.computeIfAbsent(streamId, k -> new ArrayList<>());
        if (stream.size() != expectedVersion)        // OCC check: did someone else write meanwhile?
            throw new ConcurrentModificationException(
                "Expected version " + expectedVersion + " but stream is at " + stream.size());
        stream.addAll(events);                        // atomic under the lock = the one strong-consistency point
    }
    @Override
    public synchronized List<AccountEvent> readStream(String streamId) {
        return List.copyOf(streams.getOrDefault(streamId, List.of()));
    }
}
```

```java
// ---------- Repository ties it together; retries on concurrency conflict ----------
final class AccountRepository {
    private final EventStore store;
    AccountRepository(EventStore store) { this.store = store; }

    Account load(String id) {
        return Account.rehydrate(store.readStream(id));   // (no snapshots in this minimal demo)
    }

    /** Execute a command with optimistic-retry. */
    void execute(String id, java.util.function.Consumer<Account> command) {
        for (int attempt = 0; attempt < 5; attempt++) {   // bounded retry on conflict
            Account agg = load(id);
            int expected = agg.version();                 // version we read at
            command.accept(agg);                          // may raise events or throw a domain error
            try {
                store.append(id, expected, agg.uncommittedEvents());
                agg.markCommitted();
                return;
            } catch (ConcurrentModificationException retry) {
                // someone wrote concurrently; loop reloads and re-evaluates the command
            }
        }
        throw new IllegalStateException("Too many concurrent conflicts on " + id);
    }
}

// Usage:
// repo.execute("acct-42", a -> a.handle(new Withdraw("acct-42", 3000, "ATM")));
```

The key teaching points: state changes *only* through `apply`, which is pure; commands validate then emit; the store enforces order and concurrency; rehydration is a fold; conflicts retry. This ~120 lines *is* event sourcing.

### 5.2 A projection building a read model (the CQRS read side)

```java
// Read model: a denormalized table optimized for "show me the account dashboard".
// Schema: account_view(id PK, owner, balance_cents, last_event_version)
final class AccountDashboardProjection {
    private final DataSource ds;
    AccountDashboardProjection(DataSource ds) { this.ds = ds; }

    /** Idempotent: we store last_event_version and skip already-applied events. */
    void on(AccountEvent e, int eventVersion) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);                     // read-model write + checkpoint in ONE tx
            switch (e) {
                case AccountOpened ev -> upsertOpen(c, ev);
                case FundsDeposited ev -> applyDelta(c, ev.accountId(), +ev.amountCents(), eventVersion);
                case FundsWithdrawn ev -> applyDelta(c, ev.accountId(), -ev.amountCents(), eventVersion);
            }
            c.commit();
        }
    }

    private void applyDelta(Connection c, String id, long delta, int ver) throws SQLException {
        // The WHERE last_event_version < ? makes reprocessing a no-op (at-least-once safety).
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE account_view SET balance_cents = balance_cents + ?, last_event_version = ? " +
                "WHERE id = ? AND last_event_version < ?")) {
            ps.setLong(1, delta); ps.setInt(2, ver); ps.setString(3, id); ps.setInt(4, ver);
            ps.executeUpdate();
        }
    }
    private void upsertOpen(Connection c, AccountOpened ev) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account_view(id, owner, balance_cents, last_event_version) " +
                "VALUES (?,?,0,1) ON CONFLICT (id) DO NOTHING")) {  // Postgres upsert; idempotent
            ps.setString(1, ev.accountId()); ps.setString(2, ev.owner());
            ps.executeUpdate();
        }
    }
}
```

Different query needs → different projections from the *same* events: a `MonthlyStatementProjection` writing to a reporting table, a `FraudSignalsProjection` pushing to Redis counters, a `SearchProjection` indexing into Elasticsearch — none of which the write side knows about.

### 5.3 Snapshotting a long-lived aggregate

```java
record Snapshot(String aggregateId, int version, long balanceCents, boolean opened, String id, String owner) {}

interface SnapshotStore {
    Optional<Snapshot> latest(String aggregateId);
    void save(Snapshot s);
}

final class SnapshottingRepository {
    private final EventStore events; private final SnapshotStore snapshots;
    private static final int SNAPSHOT_EVERY = 100;     // tunable; trade load latency vs storage/IO

    Account load(String id) {
        Optional<Snapshot> snap = snapshots.latest(id);
        // Start from snapshot state if present, else empty:
        Account a = snap.map(Account::fromSnapshot).orElseGet(Account::new);
        int from = snap.map(Snapshot::version).orElse(0);
        List<AccountEvent> tail = events.readStreamFrom(id, from);  // only events AFTER snapshot
        for (AccountEvent e : tail) a.replay(e);                    // fold only the tail
        return a;
    }

    void afterAppend(Account a) {
        if (a.version() % SNAPSHOT_EVERY == 0)         // policy: snapshot every N events
            snapshots.save(a.toSnapshot());            // snapshot is derived cache; safe to delete anytime
    }
}
```

### 5.4 Axon Framework: the same account, annotation-driven

```java
@Aggregate
public class AccountAggregate {
    @AggregateIdentifier private String id;
    private long balanceCents;

    public AccountAggregate() {}   // required no-arg for Axon

    @CommandHandler                // constructor command handler => creates the aggregate
    public AccountAggregate(OpenAccount cmd) {
        AggregateLifecycle.apply(new AccountOpened(cmd.accountId(), cmd.owner()));
    }

    @CommandHandler
    public void handle(Withdraw cmd) {
        if (cmd.amountCents() > balanceCents)
            throw new IllegalStateException("Insufficient funds");   // rejects command, no event
        AggregateLifecycle.apply(new FundsWithdrawn(id, cmd.amountCents(), cmd.reason()));
    }

    @EventSourcingHandler          // pure state transition; runs on replay and on new events alike
    public void on(AccountOpened e) { this.id = e.accountId(); this.balanceCents = 0; }
    @EventSourcingHandler
    public void on(FundsWithdrawn e) { this.balanceCents -= e.amountCents(); }
    @EventSourcingHandler
    public void on(FundsDeposited e) { this.balanceCents += e.amountCents(); }
}

// Read side: a tracking event processor maintains a JPA read model and a query handler serves it.
@Component
class AccountProjection {
    private final AccountViewRepository repo;   // Spring Data JPA repo over account_view
    AccountProjection(AccountViewRepository repo) { this.repo = repo; }

    @EventHandler                  // Axon assigns a TrackingToken (checkpoint) automatically
    void on(FundsWithdrawn e) {
        repo.findById(e.accountId()).ifPresent(v -> { v.subtract(e.amountCents()); repo.save(v); });
    }

    @QueryHandler                  // queries hit the read model, never the aggregate
    AccountView handle(GetAccount q) { return repo.findById(q.accountId()).orElseThrow(); }
}
```

Axon hides the event store, command/query buses, tracking tokens (checkpoints), and replay machinery. To **rebuild** a projection you reset its tracking token to the beginning and let it reprocess the full log.

### 5.5 Kafka Streams as the read side (log → materialized view)

When the event log is a **Kafka topic** keyed by aggregate ID, Kafka Streams can build the read model as a **state store**, persisted by a **changelog topic**.

```java
StreamsBuilder builder = new StreamsBuilder();

// Treat the per-account event topic as a stream of events keyed by accountId.
KStream<String, AccountEvent> events =
        builder.stream("account-events", Consumed.with(Serdes.String(), accountEventSerde));

// Aggregate (fold) events into a running balance per key => a KTable (materialized view).
KTable<String, Long> balances = events
    .groupByKey()
    .aggregate(
        () -> 0L,                                  // initializer
        (key, event, acc) -> switch (event) {       // the same fold as rehydration, but stream-wide
            case FundsDeposited d -> acc + d.amountCents();
            case FundsWithdrawn w -> acc - w.amountCents();
            default -> acc;
        },
        Materialized.<String, Long>as("balance-store")    // local state store
            .withKeySerde(Serdes.String())
            .withValueSerde(Serdes.Long())
            // ^ backed by an internal CHANGELOG topic so the view is rebuildable after a crash/rebalance
    );

balances.toStream().to("account-balances", Produced.with(Serdes.String(), Serdes.Long()));
```

> **Beginner aside — KTable / changelog topic / state store:** A *KTable* is a stream interpreted as an evolving table (latest value per key). Kafka Streams keeps the table in a local **state store** (RocksDB on disk) for fast reads, and mirrors every update to a compacted **changelog topic** in Kafka so the store can be **rebuilt** after failure or when the task moves to another instance. This is projection + checkpoint + rebuild, implemented by the framework.

### 5.6 Temporal query — "what was the balance on a past date?"

Event sourcing makes time travel a fold up to a point:

```java
long balanceAsOf(String id, Instant asOf, EventStore store) {
    long balance = 0;
    for (StoredEvent se : store.readStreamWithMeta(id)) {  // each event carries its commit timestamp
        if (se.timestamp().isAfter(asOf)) break;           // stop at the cutoff
        balance += switch (se.event()) {
            case FundsDeposited d -> d.amountCents();
            case FundsWithdrawn w -> -w.amountCents();
            default -> 0L;
        };
    }
    return balance;
}
```

In CRUD this query is impossible without a bespoke history table; in ES it falls out of the model for free.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Write path.** Appends are sequential and fast (no read-modify-write of state). The dominant cost is **rehydration** of long streams — mitigate with **snapshots** (tune interval: too small = snapshot churn; too large = long replays). Keep aggregates *small* so streams stay short and contention low.
- **Read path.** Scales independently: add projection instances, choose the perfect store per query, and pre-compute everything. Read latency is decoupled from write complexity.
- **Projection throughput.** Process events in **batches** within one transaction; use bulk upserts; partition projections by key for parallelism. Watch **projection lag** (the gap between newest committed event and the checkpoint) — it's your eventual-consistency window.
- **Serialization** of events is on the hot path: prefer compact, schema-evolvable formats (Avro/Protobuf with a **schema registry**, or carefully-versioned JSON). Avoid Java native serialization (brittle, insecure, slow).

### 6.2 Correctness & concurrency

- **`apply` must be pure and deterministic.** No clocks, randomness, UUID generation, or external calls inside event-application. Capture all nondeterminism *into the event* at creation time.
- **Aggregate = consistency boundary.** Enforce true invariants inside one aggregate via OCC (expected version). Don't make aggregates huge just to enclose weakly-related data — you'll create write contention and large streams.
- **Idempotent projections.** Assume at-least-once delivery; make handlers safe to reprocess (upserts, processed-ID tables, or `WHERE version < ?` guards).
- **Idempotent commands.** Carry a client `requestId`; dedup on the write side so retried HTTP requests don't double-withdraw.

### 6.3 Event versioning (a defining hard problem)

Events live *forever*, so their schema must evolve without breaking the ability to replay old events. Strategies:

| Strategy | What it does | When |
|---|---|---|
| **Tolerant reader / weak schema** | Consumers ignore unknown fields, default missing ones. | Additive changes (new optional field). |
| **Upcasting** | A pipeline transforms old event versions into the current shape *at read time* (e.g. `AccountOpenedV1 → V2`). Axon has explicit "upcasters." | Renames, splits, type changes. |
| **Multiple versions side by side** | Keep `v1` and `v2` event types; handle both. | Gradual migration. |
| **Copy-and-replace / stream migration** | Read old stream, write a new transformed stream (rare; loses pure-append purity). | Major restructuring. |

Rules of thumb: **never change the meaning of an existing field; only add**, and prefer upcasting. Never delete events. Plan versioning from day one — retrofitting it is painful.

### 6.4 Security & privacy (GDPR vs. immutability)

The "right to be forgotten" collides head-on with an immutable, append-only log. Approaches:

- **Crypto-shredding:** encrypt personal data in events with a per-subject key; to "forget" someone, destroy their key, rendering those event payloads unrecoverable while the log structure stays intact.
- **Separate PII stores** referenced by ID from events (keep PII out of the immutable log).
- Access controls and encryption-at-rest on the event store; audit who reads streams.

### 6.5 Observability

Instrument and alert on:

- **Projection lag** per projection (events behind head). The most important CQRS metric — it *is* your staleness.
- **Command rejection rate** and **concurrency-conflict/retry rate** (high conflicts → aggregate boundaries too coarse).
- **Append latency** and **event store throughput**.
- **Dead-letter** / poison-event counts (events a projection repeatedly fails on).
- **Rebuild duration** for catch-up (capacity planning).

Because every change is an event, the log itself is a *rich* observability source: you can replay production events into a staging projection to reproduce bugs exactly.

### 6.6 Cost

Storage grows unboundedly (you keep all events forever) — budget for it; compaction is not generally available (you can't delete events). Operational cost is real: more moving parts (stores, buses, projection workers, schema registry), more on-call surface. Factor team learning cost too.

### 6.7 Testing

- **Given-When-Then on events.** *Given* a list of past events, *When* a command, *Then* expect specific new events (or a rejection). This tests the aggregate purely and deterministically — no DB needed. (Axon ships an `AggregateTestFixture` for exactly this.)
- **Projection tests:** feed a known event sequence, assert the read model.
- **Replay tests:** rebuild a projection from a captured production log and assert correctness.

### 6.8 Production hardening

- **Outbox or CDC** for reliable publication (never dual-write).
- **Bounded retries with backoff** and **dead-letter queues** for poison events.
- **Snapshot regeneration jobs** and the ability to **delete all snapshots and rebuild**.
- **Backpressure** on projections; **partitioned** processing for scale.
- **Schema registry** + compatibility checks in CI to catch breaking event changes.
- **Replay runbooks** (how to rebuild a projection safely, blue/green the read model, swap, then delete the old one).

### 6.9 Anti-patterns to avoid

- **Event sourcing the whole system "because it's cool."** Apply per-aggregate/bounded-context, only where it pays.
- **CRUD events:** `AccountUpdated{...whole object...}` — captures state, not intent; loses the entire benefit. Prefer specific, intention-revealing events.
- **Giant aggregates** → contention, long streams, slow loads.
- **Putting business logic in projections.** Decisions belong on the write side; projections only *shape* data.
- **Querying the write side / event store for reads** (replaying to answer queries online).
- **Leaking eventual consistency to users without UX handling** (see §9.1).
- **Treating Kafka as a turnkey event store** without solving per-aggregate OCC, retention, and replay (see §7.5).
- **No event versioning plan.**

---

## 7. Advanced topics & deep internals

### 7.1 Ordering: per-stream vs. global

Within one aggregate stream, order is strict and easy (monotonic version). A **global total order** across all streams is expensive and often unnecessary. EventStoreDB exposes a global `$all` position; Kafka gives ordering only *within a partition*. If a projection spans multiple aggregates and *requires* cross-aggregate ordering, you must either route those aggregates to one partition/stream (limiting parallelism) or design the projection to be order-insensitive (commutative updates). Most well-designed read models tolerate per-stream ordering only.

### 7.2 Sagas / Process Managers (cross-aggregate consistency)

Since each aggregate is its own consistency boundary, multi-aggregate workflows (order → payment → shipping) use a **saga**: a long-running process that *listens* to events and *issues* commands, maintaining its own state and **compensating** actions on failure.

> **Beginner aside — saga & compensation:** A saga replaces a single distributed ACID transaction with a sequence of local transactions plus undo steps. If step 3 fails, the saga runs *compensating* commands to undo steps 1–2 (e.g. `RefundPayment` to undo `TakePayment`). It trades atomicity for availability and is the standard way to coordinate microservices without 2-phase commit.

Two saga styles: **orchestration** (a central coordinator) vs. **choreography** (services react to each other's events with no central brain). Sagas need timeouts, idempotency, and careful compensation design.

### 7.3 Snapshot strategy internals

- **Interval tuning:** measure average replay cost; set N so worst-case replay stays under your latency budget. Hot aggregates merit smaller N.
- **Async snapshotting:** generate snapshots off the write path to avoid latency spikes.
- **Versioned snapshots:** tag snapshots with a state-schema version; on schema change, ignore/discard old ones and replay from events.

### 7.4 Bi-temporal modeling

Events can carry **two times**: *valid time* (when the fact is true in the real world) and *transaction time* (when it was recorded). Bi-temporal modeling answers "what did we *believe* on date X about what was true on date Y?" — essential in finance/insurance. Encode both in event metadata.

### 7.5 Kafka as an event-sourcing backbone — nuances

Kafka is excellent as an **event backbone and read-model engine** but has gaps as a *per-aggregate event store*:

- **No native expected-version OCC** per key. You typically enforce single-writer-per-key (e.g. one consumer owns a partition), use **transactions/exactly-once semantics (EOS)**, or maintain an external version check.
- **Retention/compaction:** default topics expire data; pure ES needs **infinite retention** (or you accept that the log isn't permanent). **Log compaction** keeps only the latest value per key — great for *snapshots/state topics*, wrong for an *event log* (it deletes history).
- **Loading one aggregate** means scanning a partition — Kafka isn't built for "read just stream acct-42 quickly." Hence the common hybrid: **EventStoreDB/RDBMS as the authoritative per-aggregate store, Kafka as the integration/fan-out and read-model pipeline**, often fed via the outbox/CDC.

> **Beginner aside — log compaction:** Kafka can compact a topic so only the *most recent* message per key survives, turning the topic into a snapshot of latest state (used to back KTables). This is the opposite of event sourcing's "keep everything," so never compact your authoritative event log.

### 7.6 Exactly-once to read models

True exactly-once requires the read-model write and the checkpoint to commit atomically. Patterns: (a) same-DB transaction (read model + token in one RDBMS); (b) Kafka EOS (transactional producer + offset commit in one transaction); (c) idempotent writes + dedup (the pragmatic default). Without one of these, you get at-least-once and must be idempotent.

### 7.7 Event design depth

- **Fat vs. thin events:** thin events carry IDs only (consumers look up details → more coupling, smaller log); fat events carry needed data (looser coupling, larger log, but data is frozen-in-time — sometimes exactly what you want for audit). Prefer events that carry the data a consumer needs *as it was at that moment*.
- **Event granularity:** events should map to *business intentions*, not technical CRUD.
- **Idempotent event identity:** give every event a unique ID + the source command's `requestId` for dedup.

### 7.8 Read-your-own-writes and consistency mitigations

Strategies to hide eventual consistency from users:
- **Version/token handoff:** command returns the new aggregate version; the client polls or the read API waits until the projection's checkpoint ≥ that version before responding ("consistent read" / read-your-writes).
- **Optimistic UI:** the client renders the expected outcome immediately and reconciles when the read model catches up.
- **Synchronous in-process projection** for the single most consistency-sensitive view (couples it to the write, but bounds staleness to zero for that view).

### 7.9 Aggregate-less / functional event sourcing

You can model decisions as pure functions without OO aggregates: `decide(state, command) -> events` and `evolve(state, event) -> state`. This "Decider" pattern is increasingly popular (and maps cleanly to the fold-based mechanics shown in §5.1).

---

## 8. Tradeoffs & decision frameworks

### 8.1 CQRS (with or without ES): use / avoid

| Use CQRS when… | Avoid CQRS when… |
|---|---|
| Read and write loads differ greatly (asymmetric scaling) | Simple CRUD; reads and writes are symmetric |
| Reads need many different shapes/denormalizations | One model serves all queries fine |
| Complex, task-based write domain | Thin domain, mostly forms over data |
| You can tolerate eventual consistency on reads | You require strict read-after-write everywhere with no UX mitigation |
| Team is comfortable with the operational overhead | Small team, tight timeline, low complexity |

### 8.2 Event Sourcing: use / avoid

| Use ES when… | Avoid ES when… |
|---|---|
| Audit/history is a hard requirement (finance, healthcare, legal) | History is irrelevant; only "now" matters |
| Temporal queries / replay / rebuild views are valuable | No need to time-travel or rebuild |
| Domain is naturally event-driven | Domain is naturally state-oriented CRUD |
| You need to derive new read models later from past data | Read shapes are fixed and simple |
| Team accepts versioning + storage growth + complexity | You can't afford event-versioning discipline or unbounded storage |

### 8.3 Pattern comparison

| Approach | Source of truth | History | Read scaling | Complexity | Consistency |
|---|---|---|---|---|---|
| **CRUD (single model)** | Current state | Lost (unless audit table) | Coupled to writes | Low | Strong |
| **CQRS over CRUD** | Current state (write DB) | Lost | Independent read DB | Medium | Eventual on reads |
| **Event Sourcing only** | Event log | Inherent | Reads replay/snapshot | Medium-High | Strong per aggregate |
| **CQRS + Event Sourcing** | Event log | Inherent | Many independent projections | High | Eventual on reads, strong per-aggregate writes |

### 8.4 Decision heuristics

- **Default to CRUD.** Adopt CQRS only where load/shape asymmetry justifies it; adopt ES only where history/replay/temporal needs justify it. Apply both at the *bounded-context/aggregate* level, not system-wide.
- If you need **only** denormalized fast reads but no history → CQRS over CRUD (perhaps with simple materialized views) may suffice without ES.
- If you need **only** audit/history but reads are simple → ES without elaborate CQRS may suffice.
- The combined pattern is justified in a *minority* of subsystems — typically the core complex domain — within an otherwise mixed architecture.

---

## 9. Failure modes & debugging

### 9.1 Stale reads / eventual-consistency surprises

**Symptom:** user submits a command, immediately queries, sees old data ("I just transferred money, the balance didn't change"). **Cause:** projection lag. **Diagnose:** check projection-lag metric and the projection's checkpoint vs. event head. **Fix:** read-your-writes via version handoff, optimistic UI, or a synchronous critical-path projection (§7.8). This is a *design* issue surfaced as a bug — handle it explicitly.

### 9.2 Projection stuck on a poison event

**Symptom:** a projection's checkpoint stops advancing; lag grows unbounded. **Cause:** a handler throws on a specific event (bad data, bug, unhandled new event type) and keeps retrying forever. **Diagnose:** logs at the stuck position; identify the offending event by its global position/ID. **Fix:** fix the handler and reprocess; or route the event to a **dead-letter** store and skip; or upcast malformed events. Always have a poison-event policy.

### 9.3 Concurrency conflict storms

**Symptom:** high rate of `ConcurrentModification`/version-mismatch and command retries; throughput collapses on a hot aggregate. **Cause:** too many concurrent commands on one aggregate (boundary too coarse, or a real hotspot). **Diagnose:** conflict-rate metric per aggregate type/ID. **Fix:** split the aggregate, redesign the model to reduce contention, or serialize commands per aggregate (single-writer) with a queue.

### 9.4 Replay/rebuild takes forever

**Symptom:** rebuilding a projection from scratch takes hours, blocking a deploy. **Cause:** huge log + slow per-event handler + no parallelism. **Diagnose:** measure events/sec during catch-up. **Fix:** batch writes, parallelize by key/partition, build the new read model **offline/blue-green** then swap; add snapshots for aggregate loads (separate from projection rebuild).

### 9.5 Broken event versioning

**Symptom:** after a deploy, replay/loading fails to deserialize old events, or aggregates rebuild to *wrong* state. **Cause:** a breaking schema change (renamed/removed field, changed meaning) without an upcaster. **Diagnose:** deserialization errors; state assertions against known history. **Fix:** add upcasters mapping old → new; never deploy a breaking event change without one. Prevent via schema-registry compatibility checks in CI.

### 9.6 Lost events / dual-write race

**Symptom:** an event is committed but never reaches projections (or vice versa). **Cause:** publishing to a broker *after* the DB commit without an outbox — crash in between drops the publish. **Fix:** transactional outbox or CDC; the broker stream is *derived* from the committed log, never dual-written.

### 9.7 Real-world flavor (illustrative)

- **Finance/ledgers:** event sourcing is native (the ledger *is* an event log); the classic failure is mismanaging eventual consistency on balance displays — solved by read-your-writes on the critical view.
- **Retail order systems:** the classic pain is over-large `Order` aggregates causing concurrency conflicts during flash sales — solved by splitting aggregates and serializing per-order commands.
- **The widely-cited cautionary tale** is teams adopting full ES system-wide for a simple CRUD app, then drowning in event versioning, eventual-consistency bugs, and operational overhead — the canonical "don't use it everywhere" lesson.

**Debugging toolkit:** projection-lag dashboards; the event log itself (read a stream to see exactly what happened, in order, with timestamps — the best audit/debug tool there is); replay into a staging projection to reproduce bugs deterministically; given-when-then aggregate tests to bisect logic; conflict/retry and dead-letter metrics.

---

## 10. Interview drill

**Q1. What's the difference between CQRS and Event Sourcing? Can you have one without the other?**
*Model answer:* CQRS separates the write model (commands) from the read model(s) (queries) — possibly different stores/schemas. Event Sourcing changes persistence: store the immutable ordered event log as the source of truth and derive state by replaying it. They're orthogonal: CQRS can sit over plain CRUD; ES can serve reads by replaying without separate read models. They combine well because ES naturally feeds CQRS projections.
- *Follow-up: Why are they so often paired?* Because ES produces an event stream that's perfect for building multiple independent read models, and CQRS gives those projections a home; together you get audit + independent read scaling.
- *Follow-up: When would you use CQRS without ES?* When you need independent read scaling/denormalized views but don't need history — e.g. CRUD write DB replicated into a denormalized read DB.
- *Follow-up: ES without CQRS?* A single model that rebuilds from events for both reads and writes, where read shapes are simple enough not to need separate projections.

**Q2. How do you rebuild current state in event sourcing, and how do snapshots fit?**
*Model answer:* Fold (reduce) the ordered events through a pure `apply(state, event)` starting from an initial state. Snapshots cache state at a version so you replay only the tail after the latest snapshot; they're derived and discardable.
- *Follow-up: Why must `apply` be pure?* So replay is deterministic — same events always yield the same state. Nondeterminism must be captured into the event at creation.
- *Follow-up: How do you choose snapshot interval?* Balance replay latency vs. snapshot IO/storage; size N so worst-case tail replay meets your latency budget; smaller N for hot aggregates.

**Q3. Explain eventual consistency in CQRS and how you handle "read your own writes."**
*Model answer:* Read models lag the write side by the projection's processing delay, so a just-written change may not appear immediately. Mitigate with version handoff (command returns the new version; read waits until projection checkpoint ≥ that version), optimistic UI, or a synchronous critical-path projection.
- *Follow-up: How do you measure the staleness window?* Projection lag = newest committed event position − projection checkpoint.
- *Follow-up: What's the downside of a synchronous projection?* It couples the read model to the write path, reducing the independence/availability benefits and adding write latency.

**Q4. How does the write side stay consistent under concurrent commands?**
*Model answer:* Optimistic concurrency at the event store via expected-version appends — append only if the stream is still at the version you read; otherwise reject, and the handler reloads and retries. The aggregate is the consistency boundary; invariants are enforced within it.
- *Follow-up: Pessimistic vs optimistic here?* Optimistic suits low-contention aggregates (the design goal); pessimistic/serialized writes for genuine hotspots.
- *Follow-up: What if conflicts storm?* The aggregate is too coarse or a hotspot — split it or serialize commands per aggregate.

**Q5. How do you reliably get events from the store to projections/other services?**
*Model answer:* Never dual-write. Use the event store's own subscriptions (store as broker), or a transactional outbox / CDC (Debezium) so the broker stream is derived from the committed log. Delivery is at-least-once, so consumers must be idempotent and track checkpoints.
- *Follow-up: How do you get exactly-once to a read model?* Commit the read-model write and the checkpoint in one transaction (same DB), or use Kafka EOS, or idempotent writes + dedup.
- *Follow-up: At-least-once vs at-most-once trade in checkpointing?* Apply-then-checkpoint gives at-least-once (reprocess on crash → need idempotency); checkpoint-then-apply risks data loss.

**Q6. How do you handle event versioning over years of an immutable log?**
*Model answer:* Events are forever, so evolve compatibly: only add fields (tolerant reader), and use upcasters to transform old event versions into the current shape at read time; keep multiple versions during migration; never change a field's meaning or delete events.
- *Follow-up: How do you prevent breaking changes?* Schema registry with compatibility checks in CI; given-when-then replay tests.
- *Follow-up: What about snapshots when state schema changes?* Discard/version snapshots and rebuild from events; the log is unaffected.

**Q7 (senior signal). You're asked to "event-source the whole system." How do you respond?**
*Model answer:* Push back. ES/CQRS pay off in *specific* bounded contexts: history/audit-critical, temporal-query, or behavior-rich domains; and where read/write asymmetry justifies CQRS. Most subsystems are better as CRUD. I'd identify the core complex domain that benefits, apply the patterns there, and keep the rest simple — accepting a mixed architecture. Adopting it everywhere imports event-versioning burden, eventual-consistency bugs, storage growth, and operational complexity with little return for CRUD-shaped problems.
- *Follow-up: What concrete signals justify ES for a context?* Regulatory audit, "as-of" temporal queries, need to derive new views from past data, naturally event-centric domain.
- *Follow-up: What's the cost you'd quote to stakeholders?* Higher initial complexity, eventual consistency UX work, unbounded storage, event-versioning discipline, more operational surface, and team ramp-up.

**Q8 (senior signal). Where do you put the GDPR "right to be forgotten" against an immutable log?**
*Model answer:* The log is append-only, so you can't delete in place. Use crypto-shredding (encrypt PII per subject; destroy the key to "forget"), or keep PII out of the log in a separate erasable store referenced by ID. Choose based on how deeply PII is woven into events and audit needs.
- *Follow-up: Trade-offs of crypto-shredding?* Key management complexity; once shredded, those events become unreadable, which can affect replay/projections that depended on that data.
- *Follow-up: Does this break audit?* The fact and structure remain; only the personal payload becomes unrecoverable — usually acceptable.

**Q9 (senior signal). Kafka as your event store — defend or reject.**
*Model answer:* Kafka is excellent as the integration backbone and read-model engine (Kafka Streams materialized views, changelog topics), but it lacks native per-aggregate expected-version OCC, and default retention/compaction conflict with "keep all history" and "read one aggregate fast." I'd typically use a purpose-built per-aggregate store (EventStoreDB/RDBMS) as authoritative and Kafka for fan-out/projections via outbox+CDC — or, if Kafka-only, enforce single-writer-per-partition, infinite retention, and external version checks.
- *Follow-up: Why not log-compact the authoritative log?* Compaction keeps only the latest per key, destroying history — fine for snapshot/state topics, fatal for the event log.
- *Follow-up: How would you load one aggregate in Kafka?* You'd scan a partition or keep a per-aggregate index/state store — awkward, which is why a dedicated store is preferred.

**Q10. How do you test event-sourced aggregates and projections?**
*Model answer:* Aggregates with given-when-then: given past events, when a command, then assert emitted events or rejection — pure, fast, no DB (Axon's `AggregateTestFixture`). Projections by feeding known event sequences and asserting the read model. Plus replay tests against captured production logs.
- *Follow-up: Why is this style possible?* Because aggregates are pure functions of their events with no I/O in `apply`.
- *Follow-up: How do you reproduce a prod bug?* Replay the actual production event stream into a staging projection/aggregate for deterministic reproduction.

**Q11. Walk through what happens, step by step, when a `Withdraw` command is processed.**
*Model answer:* Route to handler → load/rehydrate aggregate (snapshot + tail replay) → OCC version check → invoke domain method (validate invariant, emit `FundsWithdrawn`, apply to self) → append events with expected version (atomic, the consistency point) → publish at-least-once → projections update read models and advance checkpoints → acknowledge command with new version (not necessarily updated read model). (See §3.1.)
- *Follow-up: Where's the single strong-consistency point?* The expected-version append to the event store.
- *Follow-up: What does the API return?* Typically acceptance + new aggregate version, not the projected read model (which may lag).

**Q12. What's an aggregate, and how do you size it?**
*Model answer:* A consistency boundary clustering entities under a root that enforces invariants; all changes go through the root and are serialized via OCC. Size it to be *exactly large enough to enclose true invariants* and no larger — small aggregates keep streams short, reduce contention, and parallelize well.
- *Follow-up: Symptom of too-large aggregates?* Concurrency-conflict storms, long replays, slow loads.
- *Follow-up: Too-small?* You can't enforce an invariant that spans the data → you push it to a saga with eventual consistency and compensation.

---

## 11. Glossary

- **Aggregate / Aggregate Root** — A DDD consistency boundary; a cluster of objects changed only through its root, which enforces invariants. In ES, each aggregate instance has its own event stream.
- **Append-only** — Storage you only add to; never modify or delete existing entries.
- **At-least-once / at-most-once / exactly-once** — Delivery guarantees: possibly duplicate (need idempotency) / possibly lost / never lost nor duplicated (hard; needs transactions or dedup).
- **Bi-temporal** — Modeling both *valid time* (when true in reality) and *transaction time* (when recorded).
- **Bounded context** — A DDD boundary within which a model and its terms are consistent.
- **CAP theorem** — Under a network partition, choose Consistency or Availability, not both.
- **CDC (Change Data Capture)** — Tailing a DB's transaction log to emit row-change events (e.g. Debezium).
- **Changelog topic** — A Kafka topic that backs a Streams state store so it can be rebuilt.
- **Checkpoint / Tracking token / Offset** — The position up to which a consumer has processed events.
- **Command** — An imperative request to change state; may be rejected; named in imperative mood.
- **Command bus / handler** — Dispatcher routing commands to the handler that loads the aggregate and applies behavior.
- **Compaction (log)** — Kafka feature keeping only the latest value per key; good for state topics, fatal for event logs.
- **Compensating action** — An event/command that undoes a prior one (refund undoes payment).
- **CQRS** — Command Query Responsibility Segregation: separate write and read models.
- **CQS** — Command Query Separation: each *method* either changes state or returns data, not both (Meyer).
- **CRUD** — Create, Read, Update, Delete; state-oriented persistence.
- **DDD (Domain-Driven Design)** — Modeling software around the business domain (Evans).
- **Dead-letter** — A holding area for messages/events that repeatedly fail processing.
- **Decider pattern** — Functional ES: `decide(state, command) -> events` and `evolve(state, event) -> state`.
- **Determinism / pure function** — Output depends only on inputs; no side effects, clocks, or randomness. Required for `apply`.
- **Eventual consistency** — Reads may lag writes but converge over time.
- **Event** — Immutable past-tense fact of something that happened.
- **Event store / log / stream** — Ordered append-only persistence of events; the source of truth in ES.
- **Event sourcing** — Persisting state as the event log and deriving current state by replay.
- **Fold / reduce / rehydration** — Replaying events through `apply` to reconstruct state.
- **Idempotent** — Repeating an operation has the same effect as doing it once.
- **Invariant** — A rule that must always hold for an aggregate (e.g. no overdraft).
- **KTable / state store** — Kafka Streams abstractions for an evolving table / its local store.
- **Materialized view / read model / projection (noun)** — A query-optimized data structure derived from events.
- **MVCC** — Multi-Version Concurrency Control: DBs keep multiple row versions so readers don't block writers; relevant background for OCC-style thinking.
- **Optimistic / pessimistic concurrency** — Detect conflicts at write time and retry / lock before reading.
- **Outbox (transactional)** — Writing an event and an outbox row in one DB transaction; a relay/CDC publishes it reliably.
- **Process manager / Saga** — A long-running coordinator reacting to events and issuing commands with compensations.
- **Projection (process)** — The handler that consumes events and maintains a read model.
- **Projection lag** — Gap between newest committed event and a projection's checkpoint; the staleness window.
- **Query** — A read that returns data without changing state.
- **Raft / Paxos** — Consensus algorithms ensuring replicas agree on an ordered log; underpin replicated event stores. (Mentioned for awareness; consensus is how distributed logs stay ordered and durable across nodes.)
- **Read-your-writes** — Guarantee that a client sees its own just-made change, often via version handoff.
- **Schema registry** — A service storing event/message schemas and enforcing compatibility.
- **Snapshot** — Cached aggregate state at a version to shorten replay; derived/discardable.
- **Strong consistency** — Every read reflects all prior writes immediately.
- **Upcaster / upcasting** — Transforms old event versions into the current shape at read time.
- **ZooKeeper** — A distributed coordination service (config, leader election, locks) historically used by Kafka and others. (Mentioned for awareness; it's a building block some event-infra relies on for coordination.)

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **CQRS** = split write model (commands, mutate) from read model(s) (queries, pure). Orthogonal to ES.
- **Event Sourcing** = store immutable, ordered, append-only **event log** as source of truth; **rebuild state by folding events** through a **pure** `apply`.
- **Snapshot** = cached state at a version → replay only the tail. Derived, discardable. Interval typically tens–hundreds of events.
- **Write path:** route command → rehydrate aggregate → OCC expected-version check → validate invariant → emit event → **append (the one strong-consistency point)** → publish at-least-once.
- **Read path (projection):** consume events → update read model → advance checkpoint. At-least-once ⇒ make handlers **idempotent**. Many projections from one log.
- **Consistency:** write side strong *per aggregate* (OCC); read side **eventually consistent**. Mitigate with read-your-writes via version handoff, optimistic UI, or a sync critical projection.
- **Aggregate = consistency boundary.** Keep small. Conflict storms ⇒ too coarse/hotspot.
- **Cross-aggregate workflows:** **sagas** with **compensation** (not distributed ACID).
- **Reliability:** never dual-write — use store subscriptions or **outbox/CDC**.
- **Event versioning:** events live forever; only add fields; use **upcasters**; never delete/change meaning; enforce with schema registry.
- **Kafka:** great backbone + Kafka Streams views/changelog; weak as authoritative per-aggregate store (no native expected-version OCC; retention/compaction conflict with "keep all").
- **Key metric:** **projection lag**. Also: conflict/retry rate, append latency, poison/dead-letter counts, rebuild duration.
- **GDPR vs. immutability:** crypto-shredding or PII-out-of-log.
- **Default:** plain CRUD. Adopt CQRS for read/write asymmetry; adopt ES for audit/temporal/replay needs; combine only in the core complex contexts.
- **Top anti-patterns:** "CRUD events" (state, not intent), giant aggregates, logic in projections, dual-writes, treating Kafka as turnkey ES, no versioning plan, leaking eventual consistency to users.
- **JVM toolkit:** **Axon** (`@Aggregate`, `@CommandHandler`, `@EventSourcingHandler`, `@EventHandler`, `@QueryHandler`, tracking tokens), **EventStoreDB/KurrentDB**, **Kafka + Kafka Streams + Debezium**, **Akka/Pekko Persistence**, **Eventuate**.

### 12.2 Self-test (no answers — recall actively)

1. A teammate says "Event Sourcing is just CQRS." Correct them precisely, and give one concrete example of using each pattern *without* the other.
2. Trace, step by step, what the write path does when a `CancelOrder` command arrives, naming the single point of strong consistency and explaining why the API response might not reflect the change yet.
3. You observe a projection whose checkpoint stopped advancing two hours ago while events keep arriving. List the likely cause, the metric you'd check, and three possible fixes.
4. Your event store has been live three years. Product wants to rename a field in `OrderPlaced` and change another field's units. Describe exactly how you evolve the schema without breaking replay, and what you'd add to CI to prevent regressions.
5. Justify, to a skeptical staff engineer, *not* adopting CQRS+ES for a new internal CRUD admin tool — and then describe the one subsystem in a trading platform where you *would* adopt it and why.
6. Explain how you'd give a user "read your own writes" on a balance screen in a CQRS+ES system, with at least two distinct techniques and their trade-offs.
7. Defend or reject "use Kafka as our authoritative event store," naming at least two specific Kafka characteristics that inform your answer.

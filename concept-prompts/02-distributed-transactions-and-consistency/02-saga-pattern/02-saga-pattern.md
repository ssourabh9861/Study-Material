# The Saga Pattern

> **Concept area:** Distributed Transactions & Consistency Patterns
> **Subtopic:** The Saga Pattern
> **Reader profile:** Senior backend engineer (Java/JVM) who wants to master sagas end to end — design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

### 1.1 The one-paragraph mental model

A **saga** is a way to get a *single business outcome* (place an order, book a trip, onboard a customer) done across *multiple services or databases* **without holding a single distributed lock or a global transaction**. Instead of "do everything atomically or nothing," a saga says: "do the work as a **sequence of small local transactions**, each in its own service/database, each committing immediately. If a later step fails, run **compensating transactions** that semantically undo the work the earlier steps already committed." You trade the strict, automatic **all-or-nothing atomicity** of a classic ACID transaction for **eventual, application-managed consistency** that is achievable at scale. The saga is the dominant pattern for keeping data consistent across microservices precisely because it never asks two independent services to commit together.

### 1.2 The problem it solves

In a monolith with one relational database, a business operation that touches five tables is trivially consistent: you `BEGIN`, do five `UPDATE`s, `COMMIT`. The database guarantees **ACID**:

- **A**tomicity — all five updates happen or none do.
- **C**onsistency — the database moves from one valid state to another (constraints hold).
- **I**solation — concurrent transactions don't see each other's half-done work.
- **D**urability — once committed, it survives a crash.

> **ACID** is the classic set of guarantees a single relational database transaction gives you. Each letter is defined above. The key one for this discussion is **A** (atomicity): the database can roll back automatically because all the data lives in one place under one lock manager.

Now split that monolith into microservices: `Order Service` (its own DB), `Payment Service` (its own DB), `Inventory Service` (its own DB), `Shipping Service` (its own DB). The business operation "place an order" must now write to **four databases owned by four independent processes**. There is no single `COMMIT` that spans them. If payment succeeds but inventory reservation fails, who rolls back the payment? The classic answer was a **distributed transaction** using **two-phase commit (2PC)**.

> **Two-Phase Commit (2PC)** is a protocol where a *coordinator* asks every participant "can you commit?" (the **prepare/vote** phase), and if all say yes, tells them all "commit" (the **commit** phase). It gives you atomicity across nodes — but at a steep cost (covered in §8). The crucial weakness: between prepare and commit, every participant must **hold its locks**, and if the coordinator crashes, participants are **blocked** indefinitely.

2PC does not fit modern microservice reality for three reasons:

1. **Coupling & blocking.** Holding locks across a network call to multiple services kills throughput and creates head-of-line blocking. A slow payment gateway stalls inventory.
2. **Availability.** 2PC is a **CP** choice (see CAP below): a coordinator or participant failure blocks the transaction. Microservices want high availability.
3. **Heterogeneity.** Your services may use Postgres, MySQL, DynamoDB, Kafka, and a third-party HTTP API. You cannot enlist a Stripe API call into an XA transaction.

> **CAP theorem** says that when a network **P**artition happens (messages between nodes are lost/delayed), a distributed system must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets a non-error response). 2PC leans toward C and sacrifices A during failures. Sagas lean toward A and accept *eventual* (not immediate) consistency.

> **XA** is a standard (from The Open Group) for coordinating 2PC across multiple "resource managers" (databases, message queues). Java exposes it via JTA. It only works for resources that implement the XA interface — a REST call to Stripe does not.

The saga is the answer: keep each step a **local** ACID transaction (so each service still uses its database's atomicity for *its own* data), and stitch the steps together with **application-level coordination + compensation** instead of a global lock.

### 1.3 When you reach for it

Reach for a saga when **all** of these are true:

- A business operation spans **2+ services or 2+ independently-committed datastores**.
- You **cannot or will not** use 2PC/XA (heterogeneous stores, third-party APIs, availability requirements, throughput requirements).
- The operation can tolerate **eventual consistency** for a short window (milliseconds to seconds, occasionally longer) and you can define **semantic compensations** ("refund the charge," "release the reservation") for each step.

Avoid it (or prefer something simpler) when:

- The whole operation fits in **one database** → just use a local transaction. Don't invent a saga.
- You genuinely need **strong, immediate isolation** and the operation is rare/low-throughput → 2PC may be acceptable.
- A step is **truly irreversible** with no semantic compensation (e.g., "launch the missile," "send the physical email") → you must reorder so irreversible steps are last (pivot transaction, §3), or rethink the design.

### 1.4 Where sagas sit among the alternatives (preview)

| Approach | Atomicity | Isolation | Availability under partition | Cross-tech | Typical use |
|---|---|---|---|---|---|
| Local ACID transaction | Strong (auto) | Strong (auto) | N/A (single DB) | No | One service/DB |
| 2PC / XA | Strong (auto) | Strong (locks held) | Low (blocking) | Only XA resources | Rare, two RDBMS |
| **Saga** | **App-managed (compensation)** | **Weak (must add countermeasures)** | **High** | **Yes** | **Microservices** |
| Outbox + idempotent consumers | App-managed | Weak | High | Yes | Building block *inside* sagas |
| TCC (Try-Confirm/Cancel) | App-managed (reserve+confirm) | Medium (reservation = soft lock) | High | Yes | When you can "reserve" resources |

We'll define every term above and revisit this table with full nuance in §8.

---

## 2. Foundations from first principles

Let's build the saga from zero, defining every term as it appears.

### 2.1 Transaction, local transaction, and "the unit of atomicity"

A **transaction** is a unit of work that either fully happens or fully doesn't. In a single database, the database engine enforces this with a **write-ahead log (WAL)** and a **lock manager**.

> **Write-ahead log (WAL)** — before changing the actual data pages, the database first appends the change to a sequential log on durable storage. On crash, it replays/undoes the log to reach a consistent state. This is how durability and atomicity are mechanically achieved inside one DB.

A **local transaction** in saga terminology means: a transaction confined to **one service and its own database**. The saga is built entirely out of local transactions — that's the whole trick. Each step does its work and **commits locally and immediately**. There is no global lock spanning steps.

### 2.2 What a saga *is*, precisely

A saga is an ordered sequence:

```
T1 → T2 → T3 → ... → Tn
```

where each `Ti` is a local transaction. Associated with (most of) these is a **compensating transaction** `Ci`:

```
C1, C2, ..., C(n-1)
```

`Ci` semantically undoes the effect of `Ti`. The two valid terminal outcomes of a saga are:

1. **Forward completion:** all of `T1..Tn` commit. The business operation succeeded.
2. **Backward recovery (rollback):** some `Tj` fails after `T1..T(j-1)` committed, so the saga runs `C(j-1), C(j-2), ..., C1` in **reverse order** to semantically undo everything done so far. The business operation is "cancelled," and the system is left in a state semantically equivalent to "nothing happened."

This is the original definition from the foundational 1987 paper by **Hector Garcia-Molina and Kenneth Salem, "Sagas" (ACM SIGMOD)**. The motivating example then was **long-lived transactions (LLTs)** — transactions that run for minutes or hours (batch jobs, travel bookings) and whose locks, if held the whole time, would destroy concurrency. The saga said: break the LLT into a chain of short transactions, each committing immediately, with compensations to undo if the chain breaks. The microservices community later adopted it for *spatially* distributed work (across services) rather than *temporally* long work — but the math is the same.

### 2.3 Compensating transaction (the core concept)

A **compensating transaction** `Ci` is a *new forward transaction* that produces the **semantic inverse** of `Ti`. Critically, it is **not** a database `ROLLBACK` — `Ti` already committed; you cannot roll it back. Instead you write **new data** that cancels out the meaning.

Examples:

| Forward `Ti` | Compensation `Ci` (semantic inverse) |
|---|---|
| Charge customer $100 | Refund $100 (a *new* credit transaction, often with its own audit record) |
| Reserve 1 unit of stock | Release the reservation (increment available stock by 1) |
| Create order in `PENDING` | Mark order `CANCELLED` (you do **not** delete the row) |
| Send "order confirmed" email | Send "order cancelled" email (you can't unsend the first) |
| Allocate a seat | Free the seat |

Three properties define a *good* compensation:

1. **Semantic, not physical.** It restores the *business meaning*, not the exact bytes. A refund leaves an audit trail of a charge + a refund — that's correct and often *required* by accounting/compliance. Trying to "delete the charge as if it never happened" is wrong.
2. **Idempotent.** Running `Ci` twice must equal running it once. Because retries and at-least-once delivery are everywhere in distributed systems, every compensation (and every forward step) must tolerate being invoked more than once. (Defined fully in §2.7.)
3. **Commutative-where-possible / retriable.** Compensations should be designed so they (almost) **always succeed** eventually — they are "must-complete" actions. If a compensation can fail, you need retries and, ultimately, a human escalation path (§9).

### 2.4 Pivot, compensatable, and retriable transactions

A precise vocabulary (popularized by Chris Richardson's *Microservices Patterns*) classifies each step:

- **Compensatable transaction** — a step that *can* be semantically undone (it has a `Ci`). Comes *before* the pivot.
- **Pivot transaction** — the **go/no-go point**. Once it commits, the saga is committed to going forward; before it, the saga can still roll back. The pivot is either the *last compensatable* step or the *first non-compensatable* step.
- **Retriable transaction** — a step *after* the pivot that has **no compensation** and is designed to **always eventually succeed** via retries (e.g., "send confirmation email," "decrement remaining-balance"). These come *after* the pivot.

This ordering is a design rule: **place irreversible work after the pivot, and reversible work before it.** Example for an order flow:

```
[Compensatable]  Create order (PENDING)        ← can cancel
[Compensatable]  Reserve inventory             ← can release
[Pivot]          Authorize+capture payment     ← go/no-go
[Retriable]      Approve order (PENDING→APPROVED)
[Retriable]      Send confirmation email
```

If payment fails, you roll back the two compensatable steps. Once payment succeeds (pivot), you only go forward, retrying the retriable steps until they succeed.

### 2.5 Eventual consistency, and why it's the price

> **Eventual consistency** means that after a write, different parts of the system may *temporarily disagree*, but if no new writes occur, they will *eventually converge* to the same value. A saga is eventually consistent: between `T2` committing and `T3` committing, the system is in an **intermediate state** that is *not* a valid "final" business state — the order exists but isn't paid yet. That window is the saga's "inconsistency window."

Contrast with **strong consistency** (every read sees the latest committed write, as in a single ACID transaction). Sagas give up strong consistency across services to gain availability and decoupling.

### 2.6 ACD, not ACID — the missing "I"

A subtle but critical truth: sagas provide **A**tomicity (via compensation), **C**onsistency (eventually), and **D**urability (each local step is durable), **but NOT Isolation**. There is no concept (out of the box) of "other transactions can't see my half-done saga." This is *the* hard part of sagas, and §3.6 and §7 are devoted to it. People summarize this as: **sagas are ACD, you must engineer the I yourself.**

### 2.7 Idempotency, dedup, and exactly-once-ish

> **Idempotent** — an operation that produces the same result whether applied once or many times. `SET balance = 100` is idempotent; `balance = balance - 10` is not. In distributed systems you almost never get **exactly-once** message delivery; you get **at-least-once** (messages may be redelivered) or **at-most-once** (messages may be lost). At-least-once + idempotent handlers ≈ "effectively exactly-once."

Every saga step handler must be **idempotent**, because:
- The orchestrator/broker may **redeliver** a command after a timeout even though the first attempt actually succeeded (the ack was lost).
- Retries on transient errors will re-invoke the same step.

The standard mechanism: a **dedup/idempotency key**. The handler records "I already processed request `<sagaId, stepId>`" in its own database (often in the *same local transaction* as the business write), and short-circuits duplicates.

### 2.8 The two coordination styles (preview)

A saga needs *something* to decide "T2 is done, now do T3" and "T3 failed, now compensate." There are two ways:

- **Orchestration** — a central coordinator (the *saga orchestrator*) explicitly tells each service what to do and tracks state. Think conductor.
- **Choreography** — no central coordinator; each service reacts to **events** emitted by the previous service and emits its own. Think dancers reacting to music.

Both are first-class and both are correct; the tradeoffs are deep (§3.2–3.3, §8).

---

## 3. How it works internally

This is the heart of the document. We'll trace the lifecycle, the control/data flow for both styles, the state machine, and the isolation mechanics.

### 3.1 The saga lifecycle (state machine, generic)

A saga instance progresses through states. A minimal but production-realistic state machine for an orchestrated saga:

```
                 ┌─────────────┐
                 │   STARTED   │
                 └──────┬──────┘
                        │ execute T1
                        ▼
        ┌────────────────────────────────┐
        │      RUNNING_FORWARD            │◄────────┐
        │ (executing Ti, i = 1..n)        │  Ti ok  │
        └───────┬───────────────┬─────────┘─────────┘
       Ti fails │               │ Tn ok
   (before pivot)               ▼
                │        ┌──────────────┐
                ▼        │   COMPLETED  │ (success, terminal)
       ┌──────────────┐ └──────────────┘
       │ COMPENSATING │◄──────┐
       │ (run Ci..C1) │  Ci ok│
       └──────┬───────┴───────┘
        all Ci ok │        │ Ci fails (retriable)
                  ▼        ▼ (after max retries)
        ┌──────────────┐  ┌────────────────────────┐
        │ COMPENSATED  │  │ COMPENSATION_FAILED /   │
        │ (terminal)   │  │ FAILED (needs human)    │
        └──────────────┘  └────────────────────────┘
```

State transitions explained step by step:

1. **STARTED** — a saga instance is created with a unique `sagaId` and its input payload persisted durably (so a crash can resume it). This persistence is non-negotiable; the orchestrator must be able to recover after a process restart.
2. **RUNNING_FORWARD** — the orchestrator executes `T1`. On success, it **durably records** "T1 done" (advances the saga's persisted state/position) *before* moving to `T2`. This "log progress then act" discipline is what makes the saga **crash-recoverable**: after a restart, the orchestrator reads the log, sees the last completed step, and resumes.
3. **On a step success** before `Tn`: loop back to RUNNING_FORWARD for the next step.
4. **On `Tn` success:** transition to **COMPLETED** (terminal, success).
5. **On a step failure before the pivot:** transition to **COMPENSATING**. The orchestrator looks at its log of completed steps and runs their compensations **in reverse order** (`C(j-1) ... C1`), recording each compensation's completion durably.
6. **On all compensations done:** **COMPENSATED** (terminal, "business operation cancelled cleanly").
7. **On a compensation failing:** retry with backoff. If it keeps failing past a limit, transition to **COMPENSATION_FAILED** and raise an alert — a human or automated remediation must intervene. **A compensation must never be silently dropped**; that would leave money/inventory orphaned.
8. **After the pivot:** failures of *retriable* steps do **not** trigger compensation. They are retried forward until they succeed (possibly forever, with backoff and alerting). The saga stays in RUNNING_FORWARD until they complete, then COMPLETED.

> **Durably record / write-ahead the intent** — the orchestrator persists "about to do step i" and/or "step i done" to a database (the **saga log**) before/after each external call. The saga log is itself stored in a local ACID transaction. This is the single most important implementation detail for correctness across crashes.

### 3.2 Orchestration: control & data flow, step by step

In **orchestration**, a dedicated component (the **Saga Execution Coordinator**, SEC, or simply *orchestrator*) owns the workflow. It is itself usually a stateful service backed by a database (the saga log).

Control flow for a 4-step order saga (happy path):

```
Client ──"place order"──▶ Orchestrator
  Orchestrator: persist saga(STARTED, sagaId=42)
  1. Orchestrator ──ReserveInventory(cmd, sagaId=42)──▶ Inventory Svc
                  ◀──InventoryReserved(reply)──────────
     persist: step1 done
  2. Orchestrator ──AuthorizePayment(cmd)──────────────▶ Payment Svc
                  ◀──PaymentAuthorized(reply)──────────
     persist: step2 done   (this was the PIVOT)
  3. Orchestrator ──ApproveOrder(cmd)──────────────────▶ Order Svc
                  ◀──OrderApproved──────────────────────
     persist: step3 done
  4. Orchestrator ──SendConfirmation(cmd)──────────────▶ Notification Svc
                  ◀──Sent──────────────────────────────
     persist: COMPLETED
  Orchestrator ──"order placed"──▶ Client (or async callback)
```

Failure path (payment declines at step 2):

```
  2. Orchestrator ──AuthorizePayment──▶ Payment Svc
                  ◀──PaymentDeclined──
     persist: COMPENSATING
  C1. Orchestrator ──ReleaseInventory(cmd)──▶ Inventory Svc
                   ◀──Released──
     persist: COMPENSATED  (and mark order CANCELLED via Order Svc)
  Orchestrator ──"order rejected: payment declined"──▶ Client
```

**Communication mechanics.** The orchestrator talks to participants in one of two ways:

- **Synchronous request/reply (HTTP/gRPC).** The orchestrator calls the service and waits. Simpler to reason about; but the orchestrator must handle timeouts (did the call succeed and the response was lost?), so participants must be idempotent and queryable.
- **Asynchronous command/reply over a message broker (Kafka, RabbitMQ, SQS).** The orchestrator publishes a `Command` message to the participant's command channel and listens on a reply channel. This is the classic Richardson design. It decouples availability (the participant can be down momentarily; the broker buffers) but adds messaging plumbing.

> **Message broker** — middleware (Kafka, RabbitMQ, ActiveMQ, AWS SQS/SNS) that stores and forwards messages between services so producers and consumers don't have to be online simultaneously. **Kafka** is a distributed, partitioned, replicated *log* — messages are appended and retained, consumers track their own offset. **RabbitMQ/SQS** are more queue-oriented (messages consumed and removed). Brokers give you durability and buffering, which sagas exploit for retries.

**Data flow.** The orchestrator typically threads a **saga context / payload** through the steps: the output of `T1` (e.g., `reservationId`) becomes part of the input to later steps and is needed by compensations (`C1` needs the `reservationId` to release it). The orchestrator **persists these intermediate results in the saga log**, because a compensation might run minutes later after a crash and restart and must know exactly what to undo.

### 3.3 Choreography: control & data flow, step by step

In **choreography**, there is **no orchestrator**. Each service:
1. Subscribes to the event(s) that should trigger its work.
2. Does its local transaction.
3. Publishes an event describing what it did (or failed to do).
4. The next service is subscribed to *that* event, and so on.

Happy path for the same order flow, event-driven:

```
Order Svc:      create order(PENDING) ──emits──▶ OrderCreated
Inventory Svc:  on OrderCreated → reserve ──emits──▶ InventoryReserved
Payment Svc:    on InventoryReserved → charge ──emits──▶ PaymentCompleted
Order Svc:      on PaymentCompleted → order=APPROVED ──emits──▶ OrderApproved
Notification:   on OrderApproved → send email
```

Failure path (payment fails) — compensation is also event-driven:

```
Payment Svc:    on InventoryReserved → charge FAILS ──emits──▶ PaymentFailed
Inventory Svc:  on PaymentFailed → release reservation ──emits──▶ InventoryReleased
Order Svc:      on InventoryReleased (or PaymentFailed) → order=CANCELLED
```

Notice there's no central place that "knows" the saga's overall state. The state is *implicit* in the chain of events and the data each service holds. This is simultaneously choreography's strength (no central bottleneck, services stay decoupled) and its weakness (no single place to see "where is saga 42?", risk of cyclic/spaghetti event dependencies).

**Critical building block in both styles: the Transactional Outbox.** A choreographed service must do two things atomically: (a) commit its local DB write **and** (b) publish its event. If it commits the DB then crashes before publishing, the saga stalls; if it publishes then fails to commit, it lies. You cannot atomically "write to DB and write to Kafka" without 2PC — which we're avoiding. The solution:

> **Transactional Outbox pattern** — in the *same local DB transaction* as the business write, also insert a row into an `outbox` table describing the event to publish. A separate process (a **relay** / **message relay** / **CDC connector**) reads new outbox rows and publishes them to the broker, marking them sent. Because the business write and the outbox insert are one local transaction, they're atomic. The relay achieves at-least-once publication; consumers dedup by event id.

> **CDC (Change Data Capture)** — reading a database's transaction log (WAL/binlog) to stream row changes as events. **Debezium** is the popular open-source CDC tool; it tails Postgres WAL / MySQL binlog and publishes changes to Kafka. CDC is a common way to implement the outbox relay without polling.

So a robust choreographed step is really: `BEGIN; business write; INSERT INTO outbox(...); COMMIT;` then relay → broker → next service. The same outbox pattern is also used by **orchestration** when the orchestrator-to-participant channel is a broker.

### 3.4 The orchestrator's internal engine (how a workflow engine actually runs a saga)

When you use a real engine (Temporal, Camunda, AWS Step Functions, or a hand-rolled one), the orchestrator is a **durable state machine** with these internals:

1. **Event sourcing / command log.** The engine persists every decision and every step result as an **append-only history**. The current state is derived by replaying that history. This is **event sourcing**.

   > **Event sourcing** — instead of storing only the current state, you store the full ordered list of *events/commands* that produced it; current state = fold over the events. Lets you recompute state, audit, and (crucially for workflow engines) **replay** to recover after a crash.

2. **Deterministic replay (Temporal's model).** In **Temporal/Cadence**, your workflow code is written as ordinary (Java) code, but the engine runs it as a *replayable, deterministic function*. When the worker crashes and a new worker picks up the workflow, the engine **replays the event history** through your workflow code to reconstruct local variables and program counter, then continues. This is why Temporal workflow code must be **deterministic** (no `new Random()`, `System.currentTimeMillis()`, direct I/O, or unguarded `Thread.sleep` — you must use the SDK's deterministic equivalents). The *activities* (the actual side-effecting calls) run outside this determinism constraint and their results are recorded in history.

   > **Determinism (Temporal context)** — given the same event history, the workflow code must take the same path every time it's replayed. Non-deterministic constructs would diverge from history and corrupt recovery. The SDK provides `Workflow.currentTimeMillis()`, `Workflow.newRandom()`, `Workflow.sleep()` etc. as deterministic substitutes.

3. **Timers & timeouts as first-class state.** The engine persists timers (e.g., "wait up to 30 min for payment confirmation"). When a timer fires, it's just another event appended to history. This lets sagas wait for human approval or external callbacks for days without holding any thread.

4. **Task queues & workers.** The engine doesn't run your code; it hands **tasks** to **workers** (your processes) via task queues, gets results, appends them to history, and decides the next task. This decoupling means the engine survives worker crashes and scales horizontally.

5. **At-least-once activity execution + idempotency.** The engine guarantees an activity is attempted at least once and retried per a **retry policy**; your activity must be idempotent because it may run more than once (worker crashed after doing the work but before reporting).

For **Camunda / Zeebe / Flowable** (BPMN engines), the internals differ but the spirit is the same:

> **BPMN (Business Process Model and Notation)** — a graphical standard for modeling business processes as flowcharts (tasks, gateways, events). Camunda/Flowable execute BPMN diagrams as state machines. A saga is modeled as a BPMN process where each service-task is a step and **compensation boundary events / compensation handlers** define the `Ci`. The engine persists process state to a relational DB and drives the tokens through the diagram, invoking compensation handlers automatically when a `bpmn:compensateEventDefinition` is thrown.

### 3.5 Forward vs backward recovery, precisely

There are two recovery directions:

- **Backward recovery (compensation):** undo committed steps in reverse. Used for failures *before* the pivot. Requires every prior step to be compensatable.
- **Forward recovery (roll-forward):** keep retrying the *current* step until it succeeds. Used for *retriable* steps after the pivot, and for **compensations themselves** (a compensation must roll forward — it's not allowed to give up easily). Forward recovery requires the step to be idempotent and (eventually) reliably succeed.

Some engines also support **save points** — restart from the last completed step rather than the beginning — which is exactly what the durable saga log enables.

### 3.6 Isolation anomalies *inside* the engine — what actually goes wrong

Because sagas lack the **I** in ACID, concurrent activity can observe and act on the saga's **intermediate state**. The classic anomalies (from the *Microservices Patterns* treatment, themselves echoing the ANSI SQL isolation phenomena):

> **ANSI SQL isolation phenomena** — *dirty read* (read uncommitted data), *non-repeatable read* (the same row changes between two reads in a transaction), and *phantom read* (new rows appear matching a query). Isolation levels (Read Uncommitted, Read Committed, Repeatable Read, Serializable) are how a single DB controls these. Sagas have no such levels across services, so the analogous problems reappear at the saga level.

1. **Lost updates.** Saga A reads order total = $100, plans to apply a discount. Concurrently, another saga/transaction updates the order. When A writes back, it overwrites the other's change. Lost-update happens because A's read and write straddle other committed steps.

2. **Dirty reads.** Saga A reserves inventory (a committed local transaction). A *reporting query* or another saga B reads "inventory available = 4" — but A's saga later fails and compensates, releasing the reservation back to 5. B made a decision on data that got rolled back. B did a **dirty read** of A's not-yet-final saga state.

3. **Fuzzy / non-repeatable reads.** Within saga A, step T1 reads the order amount; later step T3 reads it again and it changed because saga B committed a step in between. A's two reads disagree.

These are *real* production hazards (double-charging, overselling, negative balances). The countermeasures are in §7; the mechanics of why they happen is precisely "each step commits immediately and is therefore visible, but the *saga* isn't done."

### 3.7 Putting the flow together: an end-to-end internal trace (orchestrated, crash in the middle)

Let's trace a crash to show recovery:

```
t0  Orchestrator: INSERT saga(id=42, state=STARTED, payload=...)  -- committed
t1  Orchestrator: INSERT step_log(saga=42, step=1, status=STARTED) -- committed
t2  Orchestrator calls Inventory.Reserve(...)  → returns reservationId=R9
t3  Orchestrator: UPDATE step_log(saga=42, step=1, status=DONE, result={resv:R9}) -- committed
t4  Orchestrator: INSERT step_log(saga=42, step=2, status=STARTED) -- committed
t5  Orchestrator calls Payment.Authorize(...)
    *** ORCHESTRATOR PROCESS CRASHES (or the node dies) ***
--- restart ---
t6  New orchestrator instance scans for in-flight sagas: finds saga 42 in RUNNING_FORWARD
t7  Reads step_log: step1 DONE (resv R9), step2 STARTED (unknown outcome!)
t8  AMBIGUITY: did Payment.Authorize succeed before the crash?
t9  Orchestrator QUERIES Payment service idempotently:
      "what is the status of payment for saga 42 / idempotency key X?"
    - if Payment says "authorized" → record step2 DONE, continue forward
    - if Payment says "no record"  → safe to retry Authorize (idempotent)
    - if Payment says "declined"   → begin COMPENSATING: call Inventory.Release(R9)
```

Key lessons embedded here:

- **The `STARTED` then `DONE` two-phase logging** is what lets the orchestrator detect "I was mid-step when I crashed."
- **Idempotency keys + queryable participants** resolve the in-doubt step. A non-idempotent, non-queryable participant makes recovery impossible to do correctly — this is why those properties are *requirements*, not nice-to-haves.
- The compensation `Inventory.Release(R9)` needs `R9`, which is why intermediate results are persisted (t3).

---

## 4. The complete toolkit

### 4.1 Conceptual API surface every saga framework exposes

| Concept / method | Purpose | Key parameters | Typical default |
|---|---|---|---|
| `startSaga(input)` / `WorkflowClient.start` | Begin a new saga instance | input payload, `sagaId`/workflowId (for dedup) | engine-generated id |
| Step / Activity definition | One forward local transaction | timeout, retry policy, idempotency key | per-engine |
| Compensation handler | The `Ci` for a step | reference to forward step, undo logic | none (you must write it) |
| Saga log / history store | Durable record of progress | storage backend, retention | required |
| Retry policy | How transient failures are retried | max attempts, initial interval, backoff coefficient, max interval, non-retryable error list | varies (see below) |
| Timeout | Bound a step's runtime | schedule-to-start, start-to-close, schedule-to-close, heartbeat | per-engine |
| Signal / external event | Inject an event into a running saga (approval, callback) | signal name, payload | n/a |
| Query | Read a running saga's state without side effects | query name | n/a |
| Compensation trigger | What initiates backward recovery | failure type, predicate | step failure |

### 4.2 Temporal (Java SDK) — the concrete toolkit

Temporal is the most common code-first orchestrator in the JVM world today. Key primitives:

| Primitive | What it is | Notes / defaults |
|---|---|---|
| `@WorkflowInterface` / `@WorkflowMethod` | Your saga's orchestration logic (deterministic) | Runs via replay; must be deterministic |
| `@ActivityInterface` / `@ActivityMethod` | A step (side-effecting call to a service) | Idempotent; retried automatically |
| `io.temporal.workflow.Saga` | Built-in helper that records compensations and runs them in reverse on failure | `Saga.Options` has `parallelCompensation` (default `false`), `continueWithError` |
| `Saga.addCompensation(fn, args...)` | Register a compensation to run if the saga aborts | LIFO order by default |
| `Saga.compensate()` | Execute all registered compensations | Called in `catch` block |
| `ActivityOptions` | Per-activity config | `setStartToCloseTimeout` (required), `setRetryOptions` |
| `RetryOptions` | Retry policy | `initialInterval` default **1s**, `backoffCoefficient` default **2.0**, `maximumInterval` default 100× initial, `maximumAttempts` default **0 = unlimited**, `doNotRetry` error types |
| `Workflow.sleep`, `Workflow.newTimer` | Deterministic timers | Use these, never `Thread.sleep` |
| `Workflow.getSignalMethod` / `@SignalMethod` | Receive external events (e.g., "payment-confirmed") | |
| `@QueryMethod` | Inspect running workflow state | Side-effect free |
| `WorkflowClient` / `WorkflowStub` | Start/signal/query workflows from clients | `WorkflowOptions.workflowId` enables dedup |
| `WorkflowIdReusePolicy` | Dedup behavior for same workflowId | controls reject/allow-duplicate |

> Note: exact defaults are version-specific. As of recent Temporal Java SDK versions, `backoffCoefficient` defaults to 2.0 and `maximumAttempts` of 0 means unlimited. Always confirm against the version you run.

### 4.3 Camunda 7 / Camunda 8 (Zeebe) — BPMN toolkit

| Primitive | What it is |
|---|---|
| `bpmn:serviceTask` | A step; bound to a Java delegate (C7) or a job worker (C8/Zeebe) |
| Compensation boundary event (`bpmn:boundaryEvent` + `compensateEventDefinition`) | Attaches a compensation handler to a task |
| `bpmn:compensateEventDefinition` (throw) | Triggers compensation of all completed activities in scope |
| `JavaDelegate` (C7) | Java code implementing a service task |
| Job worker (C8) | External worker polling Zeebe for jobs |
| `RuntimeService` (C7) | Start/manage process instances |
| Async continuations / `asyncBefore`, `asyncAfter` | Save points / transaction boundaries inside the process |
| Incident | Camunda's term for a stuck job needing intervention (its "compensation failed" surface) |
| Retry config (`R3/PT5M` etc.) | Job retries with backoff |

> **Camunda 7** is the embeddable, relational-DB-backed engine (runs in your JVM). **Camunda 8 / Zeebe** is the cloud-native, horizontally scalable engine where workers poll for jobs over gRPC; it stores state in its own event log, not your RDBMS. Choose C8 for very high scale, C7 for embedded simplicity.

### 4.4 AWS Step Functions — managed orchestrator toolkit

| Primitive | What it is |
|---|---|
| State machine (Amazon States Language, JSON) | The saga definition |
| `Task` state | A step (invokes Lambda, SDK call, ECS, etc.) |
| `Catch` / `Retry` fields | Per-state error handling and retry (backoff via `IntervalSeconds`, `BackoffRate`, `MaxAttempts`) |
| `Parallel` / `Map` states | Fan-out steps |
| Compensation | Implemented manually: a `Catch` routes to a "compensation branch" that calls undo tasks |
| Standard vs Express workflows | Standard: durable, up to 1 year, exactly-once; Express: high-volume, at-least-once, ≤5 min |

> Step Functions has **no built-in compensation construct** — you model the saga's rollback as explicit error-handling branches. This is more verbose but fully managed.

### 4.5 Eclipse MicroProfile LRA / Eventuate / Axon — JVM-specific options

| Tool | What it is |
|---|---|
| **MicroProfile LRA** (`@LRA`, `@Compensate`, `@Complete`) | A spec for **Long Running Actions**: annotate JAX-RS endpoints with compensation/completion handlers; a coordinator drives them. Backed by Narayana LRA. |
| **Eventuate Tram Sagas** | Chris Richardson's framework; explicit `SagaDefinition` DSL with `step().invokeParticipant(...).onReply(...).withCompensation(...)`, plus a transactional outbox (Eventuate Tram messaging) |
| **Axon Framework** | CQRS/event-sourcing framework; `@Saga` classes with `@SagaEventHandler`, `@StartSaga`, `@EndSaga`, association properties, and deadlines |
| **Narayana** (JBoss) | Provides both classic JTA/XA *and* the LRA implementation |

> **LRA (Long Running Action)** — the MicroProfile answer to sagas: a coordinator tracks participants who register `@Compensate` and `@Complete` callbacks; if the LRA is cancelled, it calls everyone's `@Compensate`. **CQRS** — Command Query Responsibility Segregation: separate the write model (commands) from the read model (queries); often paired with event sourcing and sagas in Axon.

### 4.6 Cross-cutting building blocks (you'll need these regardless of engine)

| Building block | Purpose | Tool examples |
|---|---|---|
| Transactional outbox | Atomic "DB write + publish event" | Debezium, Eventuate Tram, custom relay |
| CDC connector | Tail DB log to emit events | Debezium (Postgres WAL, MySQL binlog) |
| Idempotency store | Dedup retried commands | A table keyed by `(sagaId, stepId)` or message id |
| Message broker | Buffer/route commands & events | Kafka, RabbitMQ, AWS SQS/SNS, Google Pub/Sub |
| Dead-letter queue (DLQ) | Park messages that repeatedly fail | broker-native DLQ |
| Distributed tracing | Follow a saga across services | OpenTelemetry, Jaeger, Zipkin |
| Saga state store | Durable saga log/history | RDBMS, the engine's store |

---

## 5. Code examples by use case

All examples are Java. They are written to be **adaptable**; comments mark the load-bearing lines.

### 5.1 Use case: Order/Payment saga with Temporal (orchestration, with compensation)

This is the canonical worked example. Five steps with the pivot at payment.

**Activity interface (the steps + their compensations as activities):**

```java
@ActivityInterface
public interface OrderSagaActivities {

    // Forward steps. Each must be IDEMPOTENT (keyed by orderId).
    String reserveInventory(String orderId, String sku, int qty);   // returns reservationId
    String authorizePayment(String orderId, String customerId, Money amount); // returns paymentId
    void   approveOrder(String orderId);
    void   sendConfirmation(String orderId, String customerId);

    // Compensations. Each must be IDEMPOTENT and (eventually) reliably succeed.
    void   releaseInventory(String reservationId);
    void   refundPayment(String paymentId);
    void   cancelOrder(String orderId);
}
```

**Workflow (the saga orchestration logic — deterministic, no I/O here):**

```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult placeOrder(OrderRequest req);
}

public class OrderWorkflowImpl implements OrderWorkflow {

    // ActivityOptions: bound each step and define its retry policy.
    private final ActivityOptions options = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))   // hard cap per attempt
        .setRetryOptions(RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)                    // exponential backoff
            .setMaximumInterval(Duration.ofSeconds(30))
            .setMaximumAttempts(5)
            // Business failures must NOT be retried — they should abort the saga:
            .setDoNotRetry(PaymentDeclinedException.class.getName())
            .build())
        .build();

    private final OrderSagaActivities act =
        Workflow.newActivityStub(OrderSagaActivities.class, options);

    @Override
    public OrderResult placeOrder(OrderRequest req) {
        // Temporal's built-in Saga helper records compensations and runs them LIFO on abort.
        Saga.Options sagaOpts = new Saga.Options.Builder()
            .setParallelCompensation(false)   // compensate in reverse (LIFO) order
            .build();
        Saga saga = new Saga(sagaOpts);

        try {
            // --- T1: reserve inventory (compensatable) ---
            String reservationId = act.reserveInventory(req.orderId(), req.sku(), req.qty());
            saga.addCompensation(act::releaseInventory, reservationId);

            // --- T2 / PIVOT: authorize payment (compensatable up to here) ---
            String paymentId = act.authorizePayment(req.orderId(), req.customerId(), req.amount());
            saga.addCompensation(act::refundPayment, paymentId);

            // Order itself: mark APPROVED (retriable, post-pivot in spirit)
            saga.addCompensation(act::cancelOrder, req.orderId());
            act.approveOrder(req.orderId());

            // --- Retriable step: confirmation email. No compensation; retried forward. ---
            act.sendConfirmation(req.orderId(), req.customerId());

            return OrderResult.placed(paymentId);

        } catch (ActivityFailure e) {
            // Any non-retryable / exhausted failure lands here → run compensations in reverse.
            saga.compensate();   // releaseInventory, refundPayment, cancelOrder as registered
            throw e;             // surface failure to the caller / mark workflow failed
        }
    }
}
```

**An idempotent activity implementation (the part that actually touches the DB/gateway):**

```java
public class OrderSagaActivitiesImpl implements OrderSagaActivities {

    private final PaymentGateway gateway;
    private final IdempotencyStore idem;   // a table of processed keys

    @Override
    public String authorizePayment(String orderId, String customerId, Money amount) {
        // Idempotency key derived from the saga's business id → safe re-invocation.
        String key = "pay:" + orderId;

        // If we already charged for this order, return the prior result (no double charge).
        Optional<String> prior = idem.lookup(key);
        if (prior.isPresent()) return prior.get();

        // The gateway ALSO receives the idempotency key — defense in depth, since the
        // network call itself could be retried after a lost response.
        ChargeResult r = gateway.charge(customerId, amount, key);
        if (r.declined()) {
            // Business failure: throw a NON-retryable exception so the saga compensates.
            throw new PaymentDeclinedException(r.reason());
        }
        idem.store(key, r.paymentId());   // record so retries short-circuit
        return r.paymentId();
    }
    // ... reserveInventory, refundPayment (idempotent: "refund if not already refunded"), etc.
}
```

Why this is correct: forward steps register compensations *immediately after* they succeed; the `catch` runs them in reverse; business failures (`PaymentDeclinedException`) are non-retryable so they immediately trigger compensation, while transient failures retry with backoff. Crash recovery is handled by Temporal's history replay.

### 5.2 Use case: Choreography with Kafka + transactional outbox (no orchestrator)

Here each service reacts to events. We show the **Inventory Service** participant and the **outbox** mechanism.

**Outbox-backed local transaction (Spring + JPA):**

```java
@Service
public class InventoryEventHandler {

    private final ReservationRepository reservations;
    private final OutboxRepository outbox;       // outbox table in the SAME DB
    private final ObjectMapper json;

    @KafkaListener(topics = "order.events", groupId = "inventory")
    @Transactional                               // ONE local DB transaction
    public void on(OrderEvent event) {
        // Idempotency: skip if we've already processed this event id.
        if (reservations.processedEvent(event.id())) return;

        if (event.type() == OrderEventType.ORDER_CREATED) {
            boolean ok = tryReserve(event.sku(), event.qty(), event.orderId());

            // Emit the next event by writing to the OUTBOX, not Kafka directly.
            // Because this insert is in the same tx as tryReserve, they are atomic.
            OutboxEvent out = ok
                ? OutboxEvent.of("inventory.events", event.orderId(),
                                 InventoryReserved.of(event.orderId()))
                : OutboxEvent.of("inventory.events", event.orderId(),
                                 InventoryRejected.of(event.orderId(), "out_of_stock"));
            outbox.save(out);

        } else if (event.type() == OrderEventType.PAYMENT_FAILED) {
            // Compensation path: release the reservation, emit InventoryReleased.
            releaseReservation(event.orderId());
            outbox.save(OutboxEvent.of("inventory.events", event.orderId(),
                                       InventoryReleased.of(event.orderId())));
        }
        reservations.markProcessed(event.id());  // dedup record, same tx
    }
}
```

**The relay (or use Debezium instead of this loop):**

```java
@Component
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, byte[]> kafka;

    // Polls unsent outbox rows and publishes them at-least-once.
    @Scheduled(fixedDelay = 200)
    @Transactional
    public void publishBatch() {
        for (OutboxEvent e : outbox.findUnsent(100)) {
            kafka.send(e.topic(), e.aggregateId(), e.payload());  // key by aggregateId for ordering
            outbox.markSent(e.id());     // if publish succeeds but this fails → re-publish (dedup'd downstream)
        }
    }
}
```

Why this is correct: the business write and the "intent to publish" commit together; the relay guarantees the event eventually reaches Kafka; consumers dedup by event id; the compensation path (`PAYMENT_FAILED → release + InventoryReleased`) is just another event reaction.

### 5.3 Use case: Hand-rolled orchestrator with a saga log (Spring Boot, synchronous)

When you can't adopt a workflow engine, a disciplined hand-rolled orchestrator is viable. The essence is the **durable step log** and **idempotent participants**.

```java
@Service
public class OrderSagaOrchestrator {

    private final SagaLogRepository log;          // durable saga + step log
    private final InventoryClient inventory;
    private final PaymentClient payment;
    private final OrderClient orders;
    private final TransactionTemplate tx;         // for local saga-log transactions

    public OrderResult run(OrderRequest req) {
        SagaInstance saga = tx.execute(s ->
            log.create(req.orderId(), SagaState.RUNNING_FORWARD, req));   // durable STARTED

        Deque<Runnable> compensations = new ArrayDeque<>();  // LIFO stack of undo actions
        try {
            // T1 reserve
            String resv = step(saga, 1, () -> inventory.reserve(req.orderId(), req.sku(), req.qty()));
            compensations.push(() -> inventory.release(resv));

            // T2 pivot: authorize payment
            String pay = step(saga, 2, () -> payment.authorize(req.orderId(), req.customerId(), req.amount()));
            compensations.push(() -> payment.refund(pay));

            // T3 approve order (retriable in spirit)
            step(saga, 3, () -> { orders.approve(req.orderId()); return "ok"; });

            tx.executeWithoutResult(s -> log.complete(saga.id()));        // durable COMPLETED
            return OrderResult.placed(pay);

        } catch (BusinessFailure | TransientExhausted f) {
            tx.executeWithoutResult(s -> log.compensating(saga.id()));    // durable COMPENSATING
            compensateWithRetries(compensations);                        // run undo, LIFO
            tx.executeWithoutResult(s -> log.compensated(saga.id()));     // durable COMPENSATED
            throw f;
        }
    }

    // Runs a step with: durable STARTED marker, idempotent call, durable DONE marker.
    private <T> T step(SagaInstance saga, int n, Supplier<T> call) {
        tx.executeWithoutResult(s -> log.stepStarted(saga.id(), n));     // crash here ⇒ recovery queries participant
        T result = call.get();                                           // participant is idempotent
        tx.executeWithoutResult(s -> log.stepDone(saga.id(), n, result));// persist result for later compensation
        return result;
    }

    // Compensations MUST roll forward — retry hard, then escalate.
    private void compensateWithRetries(Deque<Runnable> comps) {
        while (!comps.isEmpty()) {
            Runnable c = comps.pop();
            retryWithBackoff(c, /*maxAttempts*/ 10);   // if it still fails → alert + leave saga COMPENSATION_FAILED
        }
    }
}
```

> The `step()` helper's "STARTED then DONE" pattern is exactly the crash-recovery mechanism from §3.7. A recovery job scans for sagas stuck in `RUNNING_FORWARD`/`COMPENSATING` and resumes them, querying participants for any step left in `STARTED`.

### 5.4 Use case: Semantic lock to prevent dirty reads (countermeasure in code)

To stop other actors from acting on a saga's intermediate state, mark records as **`*_PENDING`** (a **semantic lock** — see §7.1). Other transactions check the lock and either wait, fail, or treat the value cautiously.

```java
// When the order saga touches a customer's loyalty points, set a pending flag.
@Transactional
public void debitPointsPending(String customerId, int pts, String sagaId) {
    LoyaltyAccount a = accounts.lockForUpdate(customerId);   // SELECT ... FOR UPDATE (local DB lock)
    if (a.isLockedBySaga() && !a.lockOwner().equals(sagaId)) {
        throw new ResourceLockedException(customerId);       // another saga holds the semantic lock
    }
    a.setPendingDebit(pts);
    a.setLockOwner(sagaId);                                  // semantic lock = "a saga is mid-flight here"
    accounts.save(a);
}

// On saga commit: convert pending → final; on compensate: clear pending.
@Transactional
public void confirmPoints(String customerId, String sagaId) {
    LoyaltyAccount a = accounts.lockForUpdate(customerId);
    a.applyPendingDebit();   // points actually deducted
    a.clearLock();
}
@Transactional
public void releasePoints(String customerId, String sagaId) {  // compensation
    LoyaltyAccount a = accounts.lockForUpdate(customerId);
    a.discardPendingDebit();
    a.clearLock();
}
```

Readers that must avoid dirty reads consult `isLockedBySaga()` and either skip, retry, or read the **committed** (non-pending) value. This is the countermeasure for dirty reads in §7.

### 5.5 Use case: Commutative compensation to avoid lost updates

If a step is an **increment/decrement** (commutative) rather than an absolute set, compensation is trivial and lost-update-safe.

```java
// Forward: decrement available stock (commutative).
@Transactional
public void decrementStock(String sku, int qty) {
    // Atomic relative update — concurrent decrements don't lose each other.
    int updated = jdbc.update(
        "UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?",
        qty, sku, qty);
    if (updated == 0) throw new InsufficientStockException(sku);
}

// Compensation: increment back (commutative, idempotent if guarded by a reservation id).
@Transactional
public void incrementStock(String sku, int qty, String reservationId) {
    if (reservationLog.alreadyReleased(reservationId)) return;   // idempotent guard
    jdbc.update("UPDATE stock SET available = available + ? WHERE sku = ?", qty, sku);
    reservationLog.markReleased(reservationId);
}
```

> **Commutative** operations can be applied in any order with the same result (a+b = b+a). Using relative updates (`x = x - n`) instead of absolute writes (`x = newValue`) is the single most effective trick to avoid **lost updates** in sagas, because two concurrent commutative updates compose correctly.

### 5.6 Use case: Step Functions saga via Catch branches (managed, no engine to host)

Amazon States Language sketch (JSON), showing compensation modeled as explicit error routing:

```json
{
  "StartAt": "ReserveInventory",
  "States": {
    "ReserveInventory": {
      "Type": "Task", "Resource": "arn:...:ReserveInventory",
      "Retry": [{ "ErrorEquals": ["States.TaskFailed"], "MaxAttempts": 3, "BackoffRate": 2.0 }],
      "Catch": [{ "ErrorEquals": ["States.ALL"], "Next": "Fail" }],
      "Next": "AuthorizePayment"
    },
    "AuthorizePayment": {
      "Type": "Task", "Resource": "arn:...:AuthorizePayment",
      "Catch": [{ "ErrorEquals": ["States.ALL"], "Next": "ReleaseInventory" }],
      "Next": "ApproveOrder"
    },
    "ReleaseInventory": {                      
      "Type": "Task", "Resource": "arn:...:ReleaseInventory",  
      "Next": "Fail"                            
    },
    "ApproveOrder": { "Type": "Task", "Resource": "arn:...:ApproveOrder", "End": true },
    "Fail": { "Type": "Fail", "Error": "OrderFailed" }
  }
}
```

> Note how the **compensation is a normal state** (`ReleaseInventory`) reached only via a `Catch`. There is no built-in "undo"; you wire it by hand. For a longer chain you'd nest the catches so each step's failure routes through all prior compensations.

---

## 6. Implementation concerns & best practices

### 6.1 Correctness & concurrency

- **Make every step and every compensation idempotent.** This is the #1 rule. Use a `(sagaId, stepId)` or message-id dedup table, ideally written in the same local transaction as the business effect. Pass idempotency keys to third-party APIs too (Stripe, PayPal support them).
- **Persist progress before acting and after acting** (`STARTED`/`DONE`), so crash recovery can detect in-doubt steps and query participants.
- **Define the pivot explicitly** and order steps so reversible work precedes it and irreversible work follows it.
- **Compensations must roll forward (never give up easily).** Retry aggressively with backoff; on exhaustion, escalate to a human/DLQ — do not drop them.
- **Avoid absolute writes; prefer commutative relative updates** to dodge lost updates.
- **Don't compensate after the pivot** — use forward retry for post-pivot steps.

### 6.2 Isolation (the hard part — see §7 for full menu)

- Choose countermeasures per field: **semantic lock** (pending flags), **commutative updates**, **pessimistic view ordering** (read the final value, not intermediate), **reread / optimistic offline lock** (re-read and check version before write), and **by-value** (decide isolation strategy based on how risky the data is — e.g., apply strict locks for money, lax for analytics).
- **Version your aggregates** (optimistic locking with a `version` column) so concurrent saga steps detect conflicting writes instead of silently overwriting.

> **Optimistic locking** — instead of locking a row, you read its `version`, and on write do `UPDATE ... WHERE id=? AND version=?`; if 0 rows update, someone else changed it — you retry. **Pessimistic locking** — `SELECT ... FOR UPDATE` holds a DB lock for the duration of the local transaction (fine *within* one step, never across steps).

### 6.3 Performance

- Sagas add **latency** (sequential network round-trips) and **overhead** (persisting saga state, publishing/consuming events). Parallelize independent steps where the engine allows (Temporal `Async`, Step Functions `Parallel`, BPMN parallel gateway).
- **Outbox polling interval** trades latency vs DB load; CDC (Debezium) avoids polling and gives lower latency.
- **Broker partitioning**: key messages by the saga/aggregate id to preserve per-saga ordering (Kafka guarantees order only within a partition).
- Keep the **saga log writes** cheap and indexed; they're on the hot path.

### 6.4 Observability

- **Correlation/trace id = sagaId** propagated through every command/event header; integrate **OpenTelemetry** so a single trace spans all services.

  > **OpenTelemetry (OTel)** — a vendor-neutral standard + SDKs for emitting traces, metrics, and logs; a *trace* stitches together *spans* across services via a propagated trace id. Essential for following a saga that hops four services.
- **Metrics to emit:** saga start/complete/compensate counts, per-step latency, retry counts, time-in-flight, count of sagas stuck in `COMPENSATING`/`COMPENSATION_FAILED`, DLQ depth.
- **A queryable saga store / dashboard**: orchestration gives this for free (the orchestrator knows state); choreography needs a separate "saga state aggregator" or a tracing-based view because state is implicit.
- **Alerts** on: compensations failing, sagas exceeding a timeout, growing in-flight count, DLQ growth.

### 6.5 Security

- **Don't leak intermediate state** to external clients (a customer shouldn't see "charged" before the order is confirmed). Expose only terminal/explicitly-pending states.
- **Authorize compensations** as carefully as forward operations (a refund is money movement).
- **Protect the saga log & event stream** (PII in payloads — encrypt at rest, minimize what you store; consider storing references not full PII).
- **Idempotency keys** must be unguessable if they gate money movement.

### 6.6 Cost

- Managed engines (Step Functions Standard) charge per state transition — a chatty saga can be surprisingly expensive; Express workflows are cheaper but at-least-once and time-limited.
- Self-hosted engines (Temporal, Camunda) cost ops effort + storage for history (set retention).
- Broker storage and CDC infrastructure are real costs.

### 6.7 Testing

- **Unit-test compensations as first-class citizens** — most bugs hide in the undo path because it's rarely exercised in happy-path testing.
- **Inject failures at every step** (chaos/fault injection) to verify compensation order and idempotency. Temporal has a test framework with time-skipping; Camunda has process-test support.
- **Test redelivery/duplicate handling**: send each command twice and assert no double effect.
- **Test crash-and-resume**: kill the orchestrator mid-saga and verify it recovers (replay/log).
- **Property-based tests** for "compensate(do(x)) ≈ no-op" semantics.

### 6.8 Production hardening checklist

- Durable, replicated saga log/history.
- Bounded retries + backoff + jitter; non-retryable error classification.
- DLQ + human-intervention runbook for `COMPENSATION_FAILED`.
- Timeouts on every step (and on the saga as a whole) so it can't hang forever.
- Idempotency everywhere; dedup store with TTL aligned to max saga duration + retries.
- Poison-message handling (a malformed command shouldn't wedge the queue).
- Versioning strategy for evolving workflows (see §7.4).

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Treating compensation as DB `ROLLBACK` | The step already committed; you can't roll back | Write a semantic inverse transaction |
| Non-idempotent steps/compensations | Retries/redelivery double-effect (double charge) | Dedup keys, idempotency keys |
| No durable saga log | Crash loses where you were | Persist progress before/after each step |
| Irreversible step before the pivot | Can't compensate it | Reorder; put irreversible work last |
| "Distributed monolith" choreography | Hidden cyclic event dependencies, no visibility | Orchestrate, or aggregate saga state |
| Compensation allowed to fail silently | Orphaned money/inventory | Roll forward + DLQ + alert |
| Holding a lock across steps | Reinvents 2PC's blocking | Use semantic locks/commutativity |
| Exposing intermediate state to users | Confusing/incorrect UX | Show only terminal/pending-by-design states |
| Synchronous orchestrator blocking on every call | Throughput collapse, cascading timeouts | Async commands over broker, timeouts, bulkheads |

---

## 7. Advanced topics & deep internals

### 7.1 The full isolation countermeasure menu (Richardson's taxonomy)

Sagas lack isolation; you reintroduce it surgically per data item:

1. **Semantic lock.** Set a flag (`order.state = PENDING`, `account.lockedBy = sagaId`) on records the saga is mutating. Other transactions detect the lock and: fail fast, block/retry, or read the committed value. The compensation/completion clears the lock. (Implemented in §5.4.) Risk: **deadlocks** between sagas locking the same records in different orders — mitigate with lock ordering and timeouts.

2. **Commutative updates.** Use relative operations so order doesn't matter and lost updates can't happen (§5.5). Best for counters, balances, stock.

3. **Pessimistic view.** Reorder the saga so the *step that reads risky data runs last*, after the data has settled, reducing the window where a dirty read matters. Or have the consumer read the most pessimistic interpretation (assume the saga will commit).

4. **Reread value (optimistic offline lock).** Before updating, re-read the record and verify it hasn't changed since you read it (version check). If it changed, abort/retry. Prevents lost updates.

5. **By-value (countermeasure selection).** Pick the isolation strategy based on the *value/risk* of the data: use strict semantic locks for money, accept eventual consistency for analytics. A dynamic policy: low-risk requests use sagas with weak isolation; high-risk requests escalate to a stricter path.

### 7.2 Orchestration vs choreography — the deep tradeoff (and hybrids)

| Dimension | Orchestration | Choreography |
|---|---|---|
| Coordination | Central orchestrator issues commands | Services react to events |
| Coupling | Orchestrator knows all participants (logic centralized) | Services only know events (logic distributed) |
| Visibility / debuggability | High — orchestrator holds saga state | Low — state is implicit in event chain |
| Single point of failure | Orchestrator (mitigate with HA + durable log) | None central (but broker is critical) |
| Risk | Orchestrator becomes a "god service" / smart pipe | "Distributed monolith": cyclic event spaghetti |
| Adding a step | Edit orchestrator | Edit producer + consumer wiring (more places) |
| Cross-service coupling | Lower between participants; higher to orchestrator | Higher between participants (event contracts) |
| Best for | Complex, many-step, conditional flows | Simple, linear, few-participant flows |
| Testing | Easier (one place to drive) | Harder (must simulate event chains) |

**Hybrid:** large systems often choreograph *within* a bounded context and orchestrate *across* contexts, or use an orchestrator that communicates via events (commands as events). Temporal is orchestration; CDC/Kafka choreography is event-driven; you can mix.

### 7.3 Saga vs TCC vs 2PC vs event-driven — when each wins

> **TCC (Try-Confirm-Cancel)** — a saga variant where each step first **Try**s to *reserve* the resource (a soft lock that the resource itself enforces), then a global **Confirm** commits all reservations, or a global **Cancel** releases them. Because the resource holds a *reservation* (e.g., "funds earmarked"), TCC has *better isolation* than a plain saga (others see the reservation), at the cost of every service implementing Try/Confirm/Cancel. Used heavily in fintech (e.g., Seata's TCC mode).

| Property | Saga | TCC | 2PC/XA | Local txn |
|---|---|---|---|---|
| Isolation | Weak (DIY) | Medium (reservations) | Strong | Strong |
| Blocking/locks across services | No | Soft (reservation) | Yes (hard) | N/A |
| Availability under partition | High | High | Low | N/A |
| Implementation effort | Medium (compensations) | High (Try/Confirm/Cancel each) | Low if XA-supported | Trivial |
| Works with non-XA / HTTP APIs | Yes | Yes | No | N/A |
| Latency | Higher (multi-step) | Higher | Medium (2 phases) | Lowest |

### 7.4 Versioning & long-running workflows

- **Workflow versioning** is a real operational hazard: if a saga can run for days and you deploy new orchestration logic, in-flight sagas may be incompatible. Temporal provides `Workflow.getVersion()` / patching APIs to branch logic by version; Camunda has process definition versioning + migration. Plan for it.
- **Determinism constraints** (Temporal) ripple into everyday coding: no direct clocks/random/IO in workflow code; only in activities. Violations corrupt replay.
- **History size limits**: Temporal caps event history (e.g., a soft limit around tens of thousands of events / size limits); for very long loops use `continueAsNew` to reset history.

### 7.5 Exactly-once illusions and the dual-write problem

- The **dual-write problem** — atomically writing to your DB *and* a broker — is unsolved without 2PC; the **outbox/CDC** pattern (§3.3) is the canonical workaround and is *the* backbone of reliable choreography.
- "Exactly-once processing" in Kafka (with transactions + idempotent producer) is exactly-once *within Kafka*, not across your DB and Kafka; you still need outbox + idempotent consumers for end-to-end correctness.

### 7.6 Deadlocks and ordering between concurrent sagas

Two sagas that semantically lock resources A then B vs B then A can deadlock. Countermeasures: global lock ordering, lock timeouts with retry, or commutative designs that need no locks. Detection: monitor `ResourceLockedException` rates and lock-wait timeouts.

### 7.7 Compensation that itself has side effects you can't undo

Sometimes a compensation triggers external effects (refund posts to a statement; a cancellation email is sent). These are *expected and correct* — the business accepts the audit trail. The design principle: **compensations restore business meaning, not history.** Where a compensation truly cannot succeed (e.g., refund to a closed card), you escalate to a manual/alternative remediation (issue store credit), tracked as a distinct task.

### 7.8 Engine internals worth knowing

- **Temporal** persists history in its own datastore (Cassandra/MySQL/Postgres) via the Temporal server; workers are stateless and replay history. Sticky caching keeps recent workflow state in worker memory to avoid full replay each time.
- **Camunda 7** uses a relational DB with optimistic locking on `ACT_RU_*` tables; job executor threads acquire and execute jobs with `R<n>/<duration>` retry config; **incidents** capture exhausted jobs.
- **Zeebe (Camunda 8)** is built on a replicated, partitioned **event log** (Raft-replicated) for horizontal scale.

  > **Raft** is a consensus algorithm that keeps a replicated log consistent across a cluster by electing a leader that orders all writes; used by Zeebe, etcd, and many systems to achieve fault-tolerant agreement.

- **Eventuate Tram** stores saga state in your service DB and uses a transactional outbox + CDC for messaging.

---

## 8. Tradeoffs & decision frameworks

### 8.1 "Use a saga when… / avoid when…"

**Use a saga when:**
- The operation spans 2+ independently-committed datastores/services.
- You need high availability and can't tolerate 2PC blocking.
- Steps have meaningful semantic compensations.
- Eventual consistency (short window) is acceptable to the business.

**Avoid (or prefer alternatives) when:**
- Everything fits in one DB → local transaction.
- You need immediate cross-service isolation and operation is rare/low-volume → consider 2PC.
- Steps are irreversible and can't be reordered after a pivot → redesign.
- The team can't invest in idempotency/observability discipline → a saga will bite you; reduce scope first.

### 8.2 Orchestration vs choreography decision rule

- **≤ 3 participants, linear flow, low change rate → choreography** (less infrastructure, services stay decoupled). Watch for it growing into a distributed monolith.
- **Complex/conditional/many-step flows, need visibility & central control → orchestration** (Temporal/Camunda/Step Functions).
- **Need audit, human-in-the-loop, long waits, timers → orchestration** with a durable engine.

### 8.3 Engine selection

| Need | Pick |
|---|---|
| Code-first, JVM, long-running, strong recovery | **Temporal** (or Cadence) |
| BPMN modeling, business-analyst-readable, embeddable | **Camunda 7 / Flowable** |
| BPMN at massive scale, cloud-native | **Camunda 8 / Zeebe** |
| Fully managed, AWS-native, low ops | **AWS Step Functions** |
| Spec-based JAX-RS sagas | **MicroProfile LRA / Narayana** |
| Outbox + Tram + explicit DSL | **Eventuate Tram Sagas** |
| CQRS/event-sourced domain | **Axon Framework** |
| TCC-style reservations / Spring Cloud Alibaba | **Seata** (AT/TCC/Saga modes) |

### 8.4 Saga vs alternatives quick chooser

- One DB → local transaction.
- Two RDBMS, rare, need strong atomicity → 2PC/XA.
- Many heterogeneous services, need availability → **saga**.
- Need better isolation than saga, can implement reservations → **TCC**.
- Read-heavy fan-out consistency → CQRS + event-driven projections (often alongside a saga).

---

## 9. Failure modes & debugging

### 9.1 The catalogue of what breaks

| Failure mode | Symptom | Root cause | Diagnosis | Fix |
|---|---|---|---|---|
| Double charge | Customer charged twice | Non-idempotent payment step + retry/redelivery | Search payment ledger by `orderId`; two charges same key | Idempotency key in step + at gateway |
| Stuck saga (in-flight forever) | Order stays `PENDING` | Lost reply / crashed orchestrator / step hung | Query saga store for `RUNNING_FORWARD` older than SLA | Step timeouts + recovery job + query participant |
| Orphaned reservation | Stock "reserved" but no order | Compensation never ran | Reservations with no matching order; `COMPENSATION_FAILED` | DLQ + retry compensation; reconciliation job |
| Compensation failed | Money not refunded | Refund endpoint down / business rule blocks it | `COMPENSATION_FAILED` count > 0; alert | Roll-forward retries, manual remediation runbook |
| Dirty read downstream | Report shows stock that got released | Consumer acted on intermediate state | Trace shows read between reserve and compensate | Semantic lock / read committed value |
| Lost update | Balance wrong after concurrent sagas | Absolute writes racing | Version mismatch / audit deltas don't sum | Commutative updates / optimistic locking |
| Event storm / cycle | Runaway events, CPU spike | Choreography cyclic dependency | Trace shows event loop A→B→A | Break cycle; add orchestration |
| Poison message | Queue/consumer wedged | Malformed command repeatedly fails | DLQ depth grows; same message retried | DLQ + schema validation |
| Deadlock between sagas | Sagas time out waiting | Two sagas lock resources in opposite order | Lock-wait timeouts in two sagas | Global lock ordering / commutativity |
| Replay corruption (Temporal) | "Nondeterministic" workflow error | Clock/random/IO in workflow code | Temporal nondeterminism exception in logs | Move to activity / use SDK deterministic APIs |

### 9.2 The tools & commands you actually use

- **Temporal:** `tctl`/`temporal` CLI — `temporal workflow list`, `temporal workflow show -w <id>` (full event history), `temporal workflow describe`, `temporal workflow query`, `temporal workflow signal`, `temporal workflow reset` (re-run from a point), `temporal workflow terminate`. The **Web UI** shows the event history timeline — the single best debugging artifact for a stuck saga.
- **Camunda:** **Cockpit** (web) to inspect process instances, **incidents**, variables; `RuntimeService`/`HistoryService` queries; Operate (C8) for instance/incident inspection; `zbctl` for Zeebe.
- **Step Functions:** AWS console **execution graph** (visual per-execution state coloring), `aws stepfunctions get-execution-history`, CloudWatch logs/metrics.
- **Kafka choreography:** `kafka-consumer-groups.sh --describe` (consumer lag = stalled saga step), inspect DLQ topics, `kafka-console-consumer` to read events, check outbox table for unsent rows.
- **Tracing:** Jaeger/Zipkin/Tempo UI filtered by `sagaId` to see the full cross-service span tree and where it stops.
- **DB forensics:** query the saga/step log and idempotency tables; for outbox issues, `SELECT * FROM outbox WHERE sent=false` (stuck relay).

### 9.3 Real-world style incidents (illustrative)

- **Lost-response double charge.** Payment succeeded; the ack to the orchestrator timed out; orchestrator retried; gateway lacked an idempotency key → two charges. Fix: idempotency key on every payment call; reconcile and refund duplicates. (Generic but extremely common across e-commerce.)
- **The "distributed monolith" event cycle.** A choreographed saga where Service A's event triggered B, B's triggered C, and C's triggered A again under a condition, creating an infinite event loop that saturated the broker. Fix: introduce an orchestrator and break the cycle; add loop detection.
- **Stuck compensation orphaning inventory.** Inventory release endpoint was down during a Black-Friday spike; compensations silently exhausted retries and were dropped (no DLQ). Thousands of phantom reservations made items appear out of stock. Fix: DLQ + alerting + nightly reconciliation comparing reservations to orders. (Pattern reported across multiple retail postmortems.)
- **Temporal nondeterminism after a deploy.** Someone called `new Random()` in workflow code; a worker restart replayed history and diverged → workflow task failures. Fix: use `Workflow.newRandom()`, version with `Workflow.getVersion`.

### 9.4 Recovery & reconciliation as a permanent safety net

Even with perfect engine semantics, run a **reconciliation job**: periodically compare authoritative records across services (orders vs payments vs reservations) and flag/fix divergences. Sagas give eventual consistency; reconciliation gives *detectable* consistency and a backstop for the rare dropped compensation. This is standard practice in payments and inventory systems.

---

## 10. Interview drill

**Q1. What is a saga and what guarantee does it give up compared to an ACID transaction?**
*Model answer:* A saga is a business operation implemented as a sequence of local transactions across services, each committing immediately, with compensating transactions to semantically undo earlier steps if a later one fails. It provides Atomicity (via compensation), Consistency (eventually), and Durability — but **not Isolation**. So it's "ACD," and you must engineer isolation yourself.
- *Probe:* Why can't you just `ROLLBACK`? → Because each step already committed in its own DB; you can't roll back a committed transaction in another service. Compensation is a *new* forward transaction producing the semantic inverse.
- *Probe:* What's eventual consistency here? → Between steps the system is in an intermediate state that isn't a valid final state; it converges once the saga completes or fully compensates.
- *Probe:* When would you still use 2PC? → Few participants, all XA-capable, rare/low-throughput, and you need strong isolation and can tolerate blocking.

**Q2. Orchestration vs choreography — explain and when you'd choose each.**
*Model answer:* Orchestration uses a central coordinator that issues commands and tracks saga state; choreography has services react to and emit events with no central coordinator. Orchestration gives visibility, central control, easier complex/conditional flows, and is easier to debug, at the cost of a coordinator to run and a potential "god service." Choreography keeps services decoupled and removes a central component but risks becoming a distributed monolith with poor visibility. Use choreography for simple, linear, few-participant flows; orchestration for complex, many-step, conditional, or long-running flows needing audit/visibility.
- *Probe:* How do you debug a stuck choreographed saga? → Distributed tracing keyed by sagaId, inspect consumer lag and DLQs, often build a saga-state aggregator because state is implicit.
- *Probe:* How do services emit events atomically with their DB write? → Transactional outbox + relay/CDC; never dual-write directly to DB and broker.

**Q3. (Senior signal) Sagas lack isolation. Walk me through the anomalies and your countermeasures.**
*Model answer:* The anomalies are lost updates, dirty reads, and non-repeatable/fuzzy reads, because each step's commit is immediately visible while the overall saga isn't done. Countermeasures: **semantic locks** (pending flags other actors respect), **commutative updates** (relative increments to avoid lost updates), **pessimistic view** (reorder so risky reads run last), **reread/optimistic offline lock** (version check before write), and **by-value** (choose strictness based on data risk — strict for money, lax for analytics). I'd version aggregates for optimistic locking and pick countermeasures per field.
- *Probe:* How do semantic locks cause deadlocks, and how do you prevent them? → Two sagas locking the same resources in different orders deadlock; prevent with global lock ordering, timeouts, or commutative designs.
- *Probe:* Why are commutative updates so effective against lost updates? → `x = x - n` composes correctly under concurrency (order-independent), whereas absolute writes overwrite each other.

**Q4. How does a saga survive an orchestrator crash mid-execution?**
*Model answer:* The orchestrator persists progress durably — typically writing "step N started" before the call and "step N done (+result)" after — in a saga log within local transactions. On restart it scans in-flight sagas, finds steps left in `STARTED`, and resolves the ambiguity by querying idempotent, queryable participants ("did payment for saga 42 happen?"), then continues forward or compensates. Engines like Temporal do this via deterministic replay of an event-sourced history.
- *Probe:* Why must participants be idempotent and queryable? → Because after a lost response the orchestrator can't tell if the step happened; idempotency makes retry safe, queryability lets it discover the truth.
- *Probe:* What's deterministic replay? → Temporal reconstructs workflow state by re-executing your workflow code against the recorded event history, so workflow code must be deterministic.

**Q5. (Senior signal) Design the order/payment saga. Where's the pivot and why?**
*Model answer:* Steps: create order (PENDING, compensatable), reserve inventory (compensatable), **authorize+capture payment (pivot)**, approve order (retriable), send confirmation (retriable). The pivot is payment because it's the go/no-go: before it, everything is reversible (cancel order, release inventory); once payment captures, we commit forward and only run retriable steps. I'd order all reversible/risky-to-reverse work before the pivot and irreversible "must-happen" work (emails, approvals) after, using forward retry there instead of compensation.
- *Probe:* What if the confirmation email fails? → It's post-pivot/retriable: retry forward with backoff; never compensate the payment for an email failure.
- *Probe:* How do you avoid double charging on retry? → Idempotency key per order at both the step and the gateway.

**Q6. What is the transactional outbox and what problem does it solve?**
*Model answer:* It solves the dual-write problem: you can't atomically write to your DB and publish to a broker without 2PC. So in the same local transaction as the business write, insert an `outbox` row describing the event; a separate relay (polling or CDC like Debezium) publishes outbox rows to the broker at-least-once and marks them sent. Consumers dedup by event id. This guarantees the event is published iff the business change committed.
- *Probe:* Polling vs CDC? → Polling is simple but adds latency/DB load; CDC (Debezium tailing WAL/binlog) is lower-latency and avoids polling but adds infra.
- *Probe:* Does Kafka exactly-once solve this? → No — Kafka EOS is within Kafka; you still need outbox + idempotent consumers for DB↔broker correctness.

**Q7. How do you handle a compensation that itself fails?**
*Model answer:* Compensations are "must-complete" — you roll *forward*: retry with exponential backoff and jitter, classify non-retryable vs retryable errors, and on exhaustion send to a DLQ and raise an alert with a runbook for manual or alternative remediation (e.g., store credit if a card is closed). You never silently drop a compensation. A reconciliation job is the backstop.
- *Probe:* Why roll forward and not compensate the compensation? → Compensations restore business meaning; undoing them would leave inconsistency. They're designed to eventually succeed.
- *Probe:* What metric would alert you? → Count of sagas in `COMPENSATION_FAILED` and DLQ depth.

**Q8. (Senior signal) When would you NOT use a saga?**
*Model answer:* When the operation fits in a single database (use a local transaction — don't over-engineer); when you need immediate strong isolation across services for a rare, low-volume operation and can tolerate blocking (2PC may be simpler); when steps are irreversible and can't be reordered after a pivot (redesign); or when the team can't sustain the idempotency/observability discipline a saga demands. Sagas trade implementation and isolation complexity for availability and decoupling — only pay that when you need it.
- *Probe:* What's the cost you're accepting? → Eventual consistency window, compensation code, idempotency everywhere, weaker isolation, more moving parts and observability needs.
- *Probe:* Alternative if you need better isolation than a saga? → TCC, where resources hold reservations (soft locks) so others see in-flight commitments.

**Q9. Explain idempotency in sagas and how you implement it.**
*Model answer:* Idempotency means re-invoking a step/compensation yields the same result as one invocation — essential because delivery is at-least-once and retries are pervasive. Implement with a dedup table keyed by `(sagaId, stepId)` or message id, written in the same local transaction as the business effect; on duplicate, short-circuit and return the prior result. Pass idempotency keys to external APIs too.
- *Probe:* TTL on the dedup store? → Aligned to max saga duration + retry window so you don't dedup forever or too soon.

**Q10. Compare saga, TCC, and 2PC on isolation and availability.**
*Model answer:* 2PC: strong isolation, low availability (blocking), needs XA. Saga: weak isolation (DIY), high availability, works with anything. TCC: medium isolation via reservations (soft locks visible to others), high availability, but every service must implement Try/Confirm/Cancel. Choose by isolation need vs implementation cost and tech constraints.
- *Probe:* Where does TCC's better isolation come from? → The Try phase reserves the resource, so concurrent actors see funds/stock earmarked, unlike a plain saga's intermediate state.

**Q11. (Senior signal) Your choreographed saga has become hard to reason about and occasionally loops. What's happening and what do you do?**
*Model answer:* It's drifting into a distributed monolith — event dependencies have grown cyclic/implicit, with no central view of state. Symptoms: event storms, hard debugging, occasional loops. I'd add distributed tracing to map the actual event graph, identify and break the cycle, and migrate the cross-cutting coordination to an orchestrator (or at least introduce a saga-state aggregator) so the flow is explicit, observable, and conditionally controllable.

**Q12. What latency and failure properties do sagas add, and how do you bound them?**
*Model answer:* Sagas add latency (sequential round-trips, persistence, messaging) and new failure modes (stuck sagas, failed compensations, dirty reads). Bound them with per-step and whole-saga timeouts, bounded retries with backoff+jitter, parallelizing independent steps, DLQs for poison/failed compensations, and observability (per-step latency, in-flight count, compensation failures) with alerts.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability: the guarantees of a single-DB transaction.
- **ACD (saga)** — sagas give Atomicity (via compensation), Consistency (eventual), Durability, but not Isolation.
- **At-least-once / at-most-once / exactly-once** — message delivery guarantees; sagas assume at-least-once and add idempotency to approximate exactly-once.
- **Backward recovery** — undoing committed steps via compensations in reverse order.
- **BPMN** — Business Process Model and Notation; a flowchart standard executed by engines like Camunda.
- **CAP theorem** — under a network partition, choose Consistency or Availability.
- **CDC (Change Data Capture)** — streaming DB row changes by tailing its log (e.g., Debezium).
- **Choreography** — saga coordination via events, no central coordinator.
- **Commutative update** — relative operation (x = x - n) whose result is order-independent; avoids lost updates.
- **Compensating transaction (Ci)** — a new forward transaction that semantically undoes a prior step.
- **Compensatable transaction** — a step that has a compensation; precedes the pivot.
- **CQRS** — Command Query Responsibility Segregation; separate write and read models.
- **Dead-letter queue (DLQ)** — where repeatedly-failing messages are parked for inspection.
- **Determinism (Temporal)** — workflow code must take the same path on replay of the same history.
- **Dirty read** — reading another transaction's/saga's uncommitted (or not-yet-final) data.
- **Distributed monolith** — services so entangled (often via choreography cycles) that they must change together.
- **Dual-write problem** — the inability to atomically write to a DB and a broker without 2PC.
- **Durably record / saga log** — persisting saga progress so a crash can recover.
- **Eventual consistency** — replicas/services converge to the same value after writes stop.
- **Event sourcing** — store the sequence of events; current state = fold over events.
- **Forward recovery** — retrying the current step until it succeeds (used post-pivot and for compensations).
- **Idempotent** — applying an operation many times equals applying it once.
- **Isolation phenomena** — dirty read, non-repeatable read, phantom read (ANSI SQL).
- **JTA / XA** — Java/standard APIs for coordinating 2PC across XA-capable resources.
- **Lost update** — a write overwrites a concurrent committed change because read+write straddled it.
- **LRA (Long Running Action)** — MicroProfile's saga spec (`@LRA`, `@Compensate`, `@Complete`).
- **Message broker** — middleware (Kafka, RabbitMQ, SQS) that stores and forwards messages.
- **Optimistic / pessimistic locking** — version-check-on-write vs hold-a-DB-lock concurrency control.
- **Orchestration** — saga coordination via a central orchestrator issuing commands.
- **Outbox (transactional)** — write business change + event-intent in one local transaction; relay publishes it.
- **Pivot transaction** — the go/no-go point; before it the saga can roll back, after it only rolls forward.
- **Raft** — leader-based consensus algorithm for a replicated log (used by Zeebe, etcd).
- **Retriable transaction** — a post-pivot step with no compensation, designed to always eventually succeed.
- **Saga** — a sequence of local transactions with compensations to manage cross-service consistency.
- **Saga Execution Coordinator (SEC)** — the orchestrator that drives an orchestrated saga.
- **Semantic lock** — a pending flag on a record that other actors respect to avoid dirty reads.
- **TCC (Try-Confirm-Cancel)** — saga variant using resource reservations for better isolation.
- **Two-phase commit (2PC)** — prepare/vote then commit protocol giving cross-node atomicity with blocking.
- **WAL (write-ahead log)** — the DB's durable change log enabling atomicity/durability.
- **XA** — standard for 2PC across resource managers (DBs, queues).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Saga = sequence of local transactions + compensations.** ACD, **not** I — engineer isolation yourself.
- **Compensation = semantic inverse forward transaction**, never a DB rollback. Must be **idempotent** and **roll forward** (never silently drop).
- **Pivot** = go/no-go. Reversible/risky work **before** pivot; irreversible/retriable work **after** (forward retry, no compensation).
- **Two styles:** Orchestration (central coordinator, visible, debuggable, complex flows) vs Choreography (events, decoupled, simple flows, risk of distributed monolith).
- **Crash recovery:** persist `step STARTED` then `step DONE` in a durable saga log; on restart, query **idempotent + queryable** participants to resolve in-doubt steps.
- **Dual-write → transactional outbox** (+ relay/CDC like Debezium). Key messages by aggregate/saga id for ordering. Consumers dedup by event id.
- **Isolation countermeasures:** semantic lock, commutative updates, pessimistic view, reread/optimistic offline lock, by-value.
- **Saga vs TCC vs 2PC:** isolation weak/medium/strong; availability high/high/low; effort medium/high/low-if-XA.
- **Temporal RetryOptions defaults:** initialInterval ~1s, backoffCoefficient ~2.0, maximumAttempts 0 = unlimited (verify per version). Make activities idempotent; workflow code must be **deterministic**.
- **Observability:** trace id = sagaId; alert on `COMPENSATION_FAILED`, in-flight age, DLQ depth, consumer lag.
- **Backstop:** reconciliation job comparing authoritative records across services.
- **Anti-patterns:** non-idempotent steps, treating compensation as rollback, no durable log, irreversible step before pivot, holding locks across steps, dropping failed compensations, exposing intermediate state.

### 12.2 Self-test (no answers — recall practice)

1. A payment step succeeds but the orchestrator crashes before recording it. Describe exactly how a correct orchestrator avoids double-charging on restart, naming the two participant properties it depends on.
2. Your choreographed saga occasionally loops forever, saturating Kafka. Diagnose the likely cause and give two concrete fixes.
3. Two concurrent sagas each adjust a customer's wallet balance with absolute writes (`balance = X`) and the balance ends up wrong. Name the anomaly and rewrite the operations to be safe.
4. Place these in correct saga order and label each (compensatable / pivot / retriable): send confirmation email, reserve inventory, capture payment, create order, approve order. Justify the pivot.
5. Explain why a transactional outbox is required for reliable choreography, and contrast polling-based vs CDC-based relays.
6. A refund (compensation) keeps failing because the customer's card is closed. What should the saga do, step by step, and what metric/alert would surface this?
7. Give the saga-vs-TCC-vs-2PC tradeoff on isolation and availability, and state one scenario where you'd pick each.

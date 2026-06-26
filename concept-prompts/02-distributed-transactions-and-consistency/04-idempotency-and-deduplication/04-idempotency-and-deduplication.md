# Idempotency & Deduplication

*An exhaustive engineering handbook chapter — Distributed Transactions & Consistency Patterns*

---

## 1. Overview & where it fits

### 1.1 What it is

**Idempotency** is the property of an operation such that performing it *once* and performing it *many times* produce the *same observable effect on the system state*. The word comes from mathematics: a function `f` is idempotent if `f(f(x)) = f(x)`. In distributed systems we widen the definition slightly — we care not about repeated *function composition* but about repeated *invocation*: calling `charge(card, $10, requestId=abc)` five times must result in exactly one $10 charge, not five.

**Deduplication** ("dedup") is the *mechanism* by which you achieve idempotency in practice: you detect that an incoming request, message, or event is a repeat of one you have already processed, and you suppress the duplicate work (or replay the previously computed result). Idempotency is the *goal* (the guarantee a client can rely on); deduplication is one common *implementation strategy* for reaching that goal.

> **Mental model (one paragraph):** In any distributed system, messages get retried. The network drops packets, clients time out and resend, load balancers fail over, message brokers redeliver, and humans double-click. You cannot prevent duplicates — you can only make them *harmless*. Idempotency is the discipline of designing every state-changing operation so that a duplicate is a no-op (or returns the original result). The standard tool is an **idempotency key**: a unique token attached to a logical operation, stored durably the first time you see it, and checked on every subsequent arrival so you can short-circuit the repeat.

### 1.2 The problem it solves

Distributed systems are built on **unreliable networks**. The foundational difficulty is the **two generals problem** / the impossibility of perfectly reliable communication over a lossy channel: when you send a request and get no response, you *cannot know* whether (a) the request never arrived, (b) it arrived and was processed but the response was lost, or (c) it's still in flight. The only safe action a client can take is to **retry**. But retrying a *non-idempotent* operation (like "transfer $100") risks doing it twice.

This produces a fundamental tension:

- **To tolerate failures, you must retry.**
- **To retry safely, the operation must be idempotent.**

Therefore idempotency is not a nice-to-have; it is the *enabling precondition* for fault tolerance. Almost every reliability pattern — retries with backoff, at-least-once message delivery, circuit breakers that re-issue, dead-letter reprocessing, saga compensation — silently assumes the operations underneath are idempotent. When they aren't, you get the classic production incidents: double charges, duplicate emails, double-shipped orders, doubled inventory decrements, replayed financial postings.

### 1.3 When you reach for it

You need idempotency / deduplication whenever **any** of these is true:

| Trigger | Why duplicates happen |
|---|---|
| A client calls a remote API that can mutate state | Client timeouts → retries; lost responses |
| You consume from a message broker (Kafka, SQS, RabbitMQ, Pub/Sub) | These deliver **at-least-once** by default → redelivery |
| You expose a public HTTP `POST`/`PATCH` endpoint | Browsers, mobile apps, proxies, users double-submit |
| You run a saga / workflow with retried steps | Orchestrator re-issues steps after a crash |
| You implement webhooks (you *send* them) | Receivers ack slowly → you resend |
| You do ETL / batch jobs that can be re-run | Job re-runs after partial failure |
| You replicate data or replay an event log | Replay re-applies events |

You do **not** strictly need it for **pure reads** (a `GET` with no side effects is already idempotent and safe) or for operations whose duplicates are genuinely harmless and the cost of a dedup store outweighs the risk (rare — usually you still want it).

### 1.4 Where it fits in the consistency landscape

Idempotency sits at the intersection of **delivery semantics** and **state correctness**:

- It is the practical foundation of **"exactly-once effects"** (sometimes called *effectively-once*), which is achievable, as opposed to **"exactly-once delivery"**, which is generally *not* achievable over a network (more in §1.5 and §7).
- It pairs with **outbox patterns** and **transactional messaging** to make event publishing reliable.
- It is the per-step safety net inside **sagas** (long-running distributed transactions) and **two-phase-commit (2PC)** alternatives.
- It interacts with **isolation/concurrency control** (you often need a unique constraint, a compare-and-set, or a lock to make dedup itself race-free).

### 1.5 HTTP method idempotency (the standard reference table)

The HTTP spec (RFC 9110, which superseded RFC 7231) classifies methods by two orthogonal properties:

- **Safe** — does not modify state (read-only).
- **Idempotent** — repeating the request has the same effect as making it once.

| Method | Safe | Idempotent (per spec) | Notes |
|---|---|---|---|
| `GET` | Yes | Yes | Read-only |
| `HEAD` | Yes | Yes | Like GET, no body |
| `OPTIONS` | Yes | Yes | Metadata |
| `PUT` | No | **Yes** | Full replacement: PUT x twice = x |
| `DELETE` | No | **Yes** | Deleting twice still ends "deleted" |
| `POST` | No | **No** | Creates a new resource each time |
| `PATCH` | No | **No** (not guaranteed) | Depends on the patch semantics |

**Crucial caveat:** "idempotent per spec" is about the *intended semantics*, not a guarantee your implementation honors. A buggy `PUT` that appends instead of replaces is not idempotent. And `POST`, which the spec calls non-idempotent, is exactly where **idempotency keys** earn their keep — they let you make a `POST` *behave* idempotently. So the spec's classification tells you which methods need extra machinery (`POST`, often `PATCH`) and which you should *design* to be naturally idempotent (`PUT`, `DELETE`).

---

## 2. Foundations from first principles

We build the concept from zero. Each term is defined the moment it appears.

### 2.1 The retry problem, concretely

Consider a client and a server communicating over TCP. **TCP** (Transmission Control Protocol) gives you *reliable, ordered byte streams within a single connection* — but a connection can drop, a server can crash mid-request, and a timeout can fire while the response is in flight. So at the *application* level, delivery is still unreliable.

Sequence of a lost-response failure:

```
Client                         Server
  |  POST /charge ($10) ------->  |
  |                               |  charges $10  (state changed!)
  |                               |  returns 200
  |  <----X (response lost)       |
  |  (timeout)                    |
  |  POST /charge ($10) ------->  |   <-- retry
  |                               |  charges $10 AGAIN  ($20 total — BUG)
```

The server *did the work*, but the client never learned that. From the client's vantage point, the request "failed," so it retries. Without idempotency, the second charge succeeds and the customer is double-billed.

### 2.2 Defining idempotency precisely

> An operation `O` with input `I` is **idempotent** if, for any number `n ≥ 1` of executions of `O(I)`, the resulting *system state* and the *response returned to the caller* are equivalent to executing `O(I)` exactly once.

Two subtleties:

1. **Equivalent state** — the database, balances, inventory, etc., end up identical regardless of repeat count.
2. **Equivalent response** — ideally each retry also *returns the same answer* (e.g., the same `chargeId` and `200 OK`), so the client can finish cleanly. This is stronger than just "state is safe"; it's what lets a retrying client converge.

Note idempotency is **per logical operation**, not per byte-identical request. "Charge $10 for order #555" is one logical operation; ten HTTP requests carrying that intent must collapse to one charge. The mechanism that ties those ten requests to one logical operation is the **idempotency key**.

### 2.3 Natural vs synthetic idempotency

- **Natural (intrinsic) idempotency** — the operation is idempotent by its very semantics, no extra bookkeeping needed.
  - `SET balance = 100` (absolute assignment) — running it twice leaves balance at 100.
  - `DELETE FROM users WHERE id = 7` — second delete affects 0 rows, state identical.
  - `PUT /users/7 {…full object…}` — full replacement.
  - Setting a flag: `status = SHIPPED`.
  - Inserting with a deterministic primary key that the DB rejects on duplicate.

- **Synthetic (engineered) idempotency** — the operation is *not* naturally idempotent (e.g., "add $10," "send an email," "create a new order"), so you **bolt on** a deduplication layer: an idempotency key + a dedup store that records "I have seen and processed key K." The classic example is making `POST /charges` safe.

The senior instinct is: **prefer natural idempotency where you can redesign the operation** (e.g., model "set absolute quantity" instead of "increment"), and fall back to synthetic idempotency where you cannot (e.g., genuinely creating new entities, or talking to a downstream that only offers increments).

### 2.4 Idempotency key (the central primitive)

An **idempotency key** (also called a *request ID*, *client token*, *dedup ID*, or *idempotency token*) is a unique value that identifies a single *logical* operation across all its retries.

Properties a good key must have:

| Property | Why |
|---|---|
| **Unique per logical operation** | So two *different* operations never collide and get deduped wrongly |
| **Stable across retries** | The client must send the *same* key on every retry of the *same* operation |
| **Hard to guess / collide** (often) | Avoid accidental or malicious collisions |
| **Generated by the right party** | Usually the *client* (it owns "what is one logical op") |

**Who generates it?** Almost always the **client/initiator**, because only the initiator knows that "these three attempts are the same intent." If the server generated it, every attempt would look new. (Stripe, for instance, has the client send an `Idempotency-Key` HTTP header.)

**How to generate it:**
- A **UUID v4** (random 122-bit identifier) generated once per logical operation and reused across retries — most common.
- A **deterministic hash** of the meaningful request content (e.g., `sha256(orderId + ":" + userId + ":charge")`) — good when you want the *same business intent* to dedup even if the client forgets to persist a key. But be careful: hashing the *whole* payload means a benign field change creates a "new" key. Hash only the *identity-defining* fields.
- A **business/natural key** already present in the domain (e.g., `orderId`) — often the cleanest.

### 2.5 Delivery semantics — at-most-once, at-least-once, exactly-once

These three terms describe *messaging/processing guarantees* and are the backdrop for everything here.

- **At-most-once:** each message is delivered/processed **0 or 1** times. Achieved by *not retrying* (fire-and-forget). No duplicates, but you can **lose** messages. Used when loss is acceptable (e.g., metrics samples).
- **At-least-once:** each message is delivered/processed **1 or more** times. Achieved by *retrying until acknowledged*. You never lose a message but you **will see duplicates**. This is the default for Kafka, SQS standard queues, RabbitMQ, most webhooks. **This is the world idempotency was built for.**
- **Exactly-once:** each message takes effect **precisely once** — no loss, no duplicates.

The single most important fact in this chapter:

> **Exactly-once *delivery* over an unreliable network is impossible in general.** What you *can* build is **exactly-once *effects*** (a.k.a. *effectively-once* processing): the message may be *delivered* multiple times, but its *effect on your state* happens once — because your consumer is **idempotent**. So: **at-least-once delivery + idempotent processing = exactly-once effects.** That equation is the whole game.

(We expand on why exactly-once delivery is impossible, and how Kafka's "exactly-once semantics" feature actually works, in §7.)

### 2.6 Dedup store (the memory of what you've seen)

To deduplicate, you must **remember** which keys you've already processed. That memory lives in a **dedup store**. Candidate stores and their tradeoffs (full table in §4):

- A relational DB row with a **unique constraint** on the idempotency key (strongest, transactional).
- **Redis** with `SET key value NX EX <ttl>` (fast, but needs care around persistence and atomicity).
- A dedicated table recording `(key, status, response, created_at)`.
- The broker's own dedup (e.g., SQS FIFO 5-minute dedup window, Kafka idempotent producer).

Two design axes for a dedup store:

1. **Atomicity of "check-and-claim."** You must atomically (a) check if the key exists and (b) claim it if not — otherwise two concurrent duplicates both pass the check. This needs a unique constraint, a `SET NX`, a compare-and-set (CAS), or a lock.
2. **Whether you store the *result*.** Storing just "seen" lets you *suppress* a duplicate. Storing the *previous response* lets you *replay* it, so the retrying client gets the same `200 + chargeId` it would have gotten originally. Replaying is the gold standard.

### 2.7 Compare-and-set (CAS)

**Compare-and-set** (a.k.a. compare-and-swap) is an atomic primitive: "update X to *new* **only if** X currently equals *expected*." Hardware exposes it as a CPU instruction (`CMPXCHG`); databases expose it via conditional updates (`UPDATE … WHERE version = ?`); Redis offers it via `WATCH`/`MULTI`/`EXEC` or Lua scripts; DynamoDB offers conditional writes. CAS is how you make the "claim this key" step race-free without a heavyweight lock.

### 2.8 TTL (time-to-live)

A **TTL** is an expiration window after which a stored key is forgotten. Dedup stores usually can't keep keys forever (unbounded growth), so each key gets a TTL. The TTL must be **longer than the maximum possible duplicate window** — i.e., longer than the longest your client/broker could conceivably retry. If TTL is too short, a late duplicate arrives *after* the key expired and gets processed again (a real source of double-charges). Picking TTL is a §6 / §7 topic; defaults range from minutes (SQS FIFO = 5 min) to 24 hours (Stripe) to indefinitely (a permanent DB unique constraint on a business key).

### 2.9 Scope of a key

An idempotency key is only meaningful within a **scope**. The same string `"abc"` might be a valid key for *user A's* payment and a *different* operation for *user B*. Scope dimensions:

- **Tenant / user** — usually keys are scoped per account.
- **Endpoint / operation type** — `abc` on `/charges` ≠ `abc` on `/refunds`.
- **Resource** — sometimes scoped to a specific resource ID.

Best practice: store and check the key together with its scope (e.g., compound unique key `(tenant_id, endpoint, idempotency_key)`), and often **bind the key to the request fingerprint** so that reusing a key with a *different payload* is rejected (see §6 anti-patterns and §7 conflict detection).

### 2.10 The minimal correct algorithm

Putting the primitives together, the canonical idempotent-write algorithm is:

```
on request(key, payload):
  1. fingerprint = hash(payload)                  # bind key to content
  2. atomically INSERT (key, fingerprint, status=IN_PROGRESS)   # claim
       if insert fails because key already exists:
          existing = load(key)
          if existing.fingerprint != fingerprint:
             return 422 "key reused with different payload"
          if existing.status == COMPLETED:
             return existing.saved_response          # REPLAY
          else: # IN_PROGRESS
             return 409 "request in progress, retry later"
  3. do the real work (charge the card, create the order, …)
  4. persist result + mark status=COMPLETED (ideally in same txn as the work)
  5. return result
```

Every production idempotency layer is an elaboration of these five steps. The rest of this chapter is about doing each step *correctly under concurrency, failure, and scale*.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle, the state machine, the control/data flow, and the concurrency hazards.

### 3.1 The idempotency-record state machine

A dedup record (one per key) moves through these states:

```
            (first request claims key)
   [absent] ───────────────────────────▶ [IN_PROGRESS]
                                              │
                  work succeeds, response     │  work fails
                  persisted                   │  (and not retriable in-band)
                                              ▼
                                         [COMPLETED] ── (TTL expires) ──▶ [absent]
                                              ▲
       (duplicate arrives) ──── replay saved response
```

- **absent** — never seen this key. A request transitions it to IN_PROGRESS by atomically claiming.
- **IN_PROGRESS** — claimed; the original request is doing the work. A duplicate arriving now is a *concurrent* retry. Two valid policies:
  - **Reject with 409 Conflict** ("a request with this key is already in progress; try again") — simplest, safe.
  - **Block/wait** for the original to finish, then replay its result — better UX, more complex (needs a wait + notify or polling).
- **COMPLETED** — work done, response persisted. Duplicates **replay** the saved response.
- **FAILED** (optional explicit state) — work failed deterministically. Policy question: do you let the client retry (transition back to absent / allow re-claim) or return the stored failure? Most systems treat *transient* failures as "delete the record so a retry can re-run," and *permanent* failures as "store and replay the error."

### 3.2 Control flow, step by step (server-side idempotent write)

Let's trace a `POST /charges` with header `Idempotency-Key: K`.

**Step 0 — Extract and validate the key.**
Read `K` from the header. Reject (`400`) if missing on an endpoint that requires it. Optionally validate format/length (e.g., ≤ 255 chars) to prevent abuse.

**Step 1 — Compute the request fingerprint.**
`fp = sha256(canonicalize(relevant request fields))`. Canonicalization means: sort JSON keys, normalize numbers, drop volatile fields (timestamps, trace IDs). This binds `K` to *what* it claimed to do, so a later reuse of `K` with a different body can be detected.

**Step 2 — Atomically claim the key (the critical section).**
Attempt an atomic insert of `(scope, K, fp, status=IN_PROGRESS, created_at=now)`.

- **Success** → you are the *first*; proceed to Step 4.
- **Unique-constraint violation** → a record already exists; go to Step 3.

This atomic claim is what prevents two concurrent duplicates from both doing the work. The atomicity comes from the **unique index** (DB) or `SET NX` (Redis) — the storage layer arbitrates the race.

**Step 3 — Handle an existing record.**
Load the existing row.
- If `existing.fp != fp` → key reused for a *different* request → return `422`/`409` "idempotency key reused."
- Else if `existing.status == COMPLETED` → **replay** `existing.response` with the original status code. Done.
- Else (`IN_PROGRESS`) → original still running. Return `409` (or wait-and-poll). Done.

**Step 4 — Do the work, transactionally tied to the record.**
Perform the side effect. The hard part: **the side effect and the "mark COMPLETED" must be atomic together**, or you create a window where the work happened but the record didn't update — a crash there leaves an IN_PROGRESS record forever and an unreplayable result. Strategies (detailed in §3.4):
- If the side effect is a row in *your own* DB → do it in the *same transaction* as updating the idempotency record. Clean and atomic.
- If the side effect is an *external* call (charge a card via a payment gateway) → you can't put it in your DB transaction. You then rely on the *downstream's own idempotency key* and a careful ordering (record IN_PROGRESS, call downstream with its own key, then mark COMPLETED). A crash mid-flight is recovered by re-running and relying on the downstream dedup.

**Step 5 — Persist response + mark COMPLETED.**
Store the serialized response body and status code, set `status = COMPLETED`, set/refresh TTL. Return the response.

### 3.3 Data flow

```
        ┌────────────┐      Idempotency-Key: K       ┌───────────────┐
        │   Client    │ ───────────────────────────▶ │  API server   │
        │ (owns K,    │ ◀──────────────────────────  │               │
        │  retries)   │      200 + {chargeId}         └──────┬────────┘
        └────────────┘                                       │
                                                              │ claim / check / store
                                                              ▼
                                              ┌────────────────────────────┐
                                              │   Dedup store               │
                                              │  (key, fp, status, response,│
                                              │   created_at, ttl)          │
                                              │  UNIQUE(scope, key)         │
                                              └──────────────┬─────────────┘
                                                              │ (only on first time)
                                                              ▼
                                              ┌────────────────────────────┐
                                              │   Side-effecting work       │
                                              │  (DB write / payment gateway│
                                              │   / publish event)          │
                                              └────────────────────────────┘
```

### 3.4 The atomicity problem: tying the side effect to the record

This is the subtlest internal concern. There are three canonical cases.

**Case A — Side effect is in your own database (best case).**
Put the business write *and* the idempotency-record update in one ACID transaction.

```
BEGIN;
  INSERT INTO idempotency(key, fp, status) VALUES (K, fp, 'COMPLETED');  -- or update
  INSERT INTO orders(...) VALUES (...);                                   -- the real work
COMMIT;
```

If `COMMIT` succeeds, both happened; if it fails/crashes, neither did. A retry re-runs cleanly. This is the **gold standard** and is why local idempotency is far easier than cross-service idempotency.

**Case B — Side effect is an external service that itself supports an idempotency key (good case).**
You can't share a transaction with Stripe. So you *chain* idempotency:

```
1. INSERT idempotency(K, IN_PROGRESS)            -- in your DB
2. call gateway.charge(amount, idempotencyKey = K')   -- K' derived from K
       (the gateway dedups on K'; retrying the gateway call is safe)
3. UPDATE idempotency SET status=COMPLETED, response=...  -- in your DB
```

If you crash between 2 and 3, on retry you re-call the gateway with the *same* `K'`; the gateway returns the *original* charge (not a new one), and you then complete step 3. Correctness rests on the downstream being idempotent. This is **idempotency composition** — your idempotency layer delegates the actual exactly-once-effect to the downstream's.

**Case C — Side effect is external and NOT idempotent (worst case).**
E.g., an SMS gateway that has no dedup. You cannot guarantee exactly-once. You can only *minimize* duplicates: record intent, attempt the side effect, record completion, and accept a small double-send probability on crash-in-the-window. Mitigations: make the window tiny, use the **transactional outbox** (§7) so the "send" is itself a durable, dedupable event, or push the dedup responsibility as close to the side effect as possible.

### 3.5 The transactional outbox (how reliable idempotent *publishing* works)

A recurring internal pattern: you update your DB *and* need to publish an event, atomically. You can't 2-phase-commit your DB and Kafka cheaply/reliably. The **outbox pattern**:

1. In the *same DB transaction* as the business write, insert a row into an `outbox` table: `(event_id, payload, published=false)`. `event_id` is the idempotency key for the event.
2. A separate **relay** (a poller, or a **change-data-capture** tool like Debezium reading the DB write-ahead log) reads unpublished outbox rows and publishes them to the broker, then marks them published.
3. The relay may publish a message *more than once* (it crashes after publishing but before marking published) — **so it's at-least-once**, and *consumers must dedup on `event_id`*. The outbox guarantees *at-least-once with a stable dedup key*, which combined with idempotent consumers gives exactly-once effects.

**Change-data-capture (CDC):** a technique where a tool tails the database's transaction log (e.g., Postgres WAL, MySQL binlog) and emits a stream of row changes. **Debezium** is the popular open-source CDC tool feeding Kafka. CDC makes the outbox relay reliable without polling.

### 3.6 Idempotent consumer internals (under at-least-once delivery)

Now the message-consumer side. A broker (Kafka/SQS/RabbitMQ) hands you a message; you must process it exactly once *in effect*.

**Step-by-step:**

1. **Receive** message `M` with a stable dedup id (Kafka: often a business key or `topic-partition-offset`; SQS: `MessageId`; or an application-level `eventId` in the payload — prefer the latter, see below).
2. **Begin** a transaction in your local store.
3. **Check-and-claim**: try to insert `eventId` into a `processed_messages` table (unique constraint). If it already exists → it's a duplicate → **skip** (ack and return). If inserted → proceed.
4. **Apply** the business effect *in the same transaction* (Case A above).
5. **Commit.** Now the "I processed M" record and the effect are atomic.
6. **Acknowledge** the message to the broker.

The ordering of *commit* then *ack* matters: if you ack before committing and then crash, the broker thinks it's done but your effect is lost (you violated at-least-once and *lost* the message). Always **commit the effect first, ack second.** If you crash after commit but before ack, the broker redelivers, your dedup check sees the `eventId` already processed, you skip and re-ack — perfectly safe. That asymmetry (redeliver-and-dedup is safe; lose-then-ack is not) is *why* we choose at-least-once + idempotency.

**Why an application-level `eventId` beats `topic-partition-offset`:** offsets are unstable across topic compaction, repartitioning, mirroring, and reprocessing from a different topic. A producer-assigned `eventId` (UUID minted when the event is *created*, carried in the payload/header) survives all of that and is the true logical identity of the event.

### 3.7 Concurrency hazards (the races you must defeat)

| Hazard | What happens | Fix |
|---|---|---|
| **Lost-update race on claim** | Two duplicates both read "key absent," both insert, both do the work | Use a **unique constraint** / `SET NX` / CAS so the storage layer rejects the second insert atomically. Never "SELECT then INSERT" in two steps without a constraint. |
| **Check-then-act gap** | Check key exists (no), then later insert — another thread sneaks in between | Collapse check+claim into one atomic op (insert-and-catch-violation). |
| **Work done, record not updated (crash window)** | Effect happened; IN_PROGRESS stuck forever; replay impossible | Tie effect + record in one txn (Case A), or delegate to downstream idempotency (Case B). |
| **TTL too short** | Late duplicate after expiry → reprocessed | TTL > max retry/redelivery window. |
| **Replay returns stale/empty response** | You stored "seen" but not the response → duplicate gets a `409`/empty instead of the original `200` | Store the full response for replay. |
| **Concurrent in-progress duplicate** | Two near-simultaneous tries both find IN_PROGRESS | Return `409` or wait-and-replay; never both proceed. |

### 3.8 Putting it together — full annotated lifecycle (pseudocode)

```text
PROCESS(request):
  K  = request.idempotencyKey                 # supplied by client
  fp = fingerprint(request.body)              # bind key to payload

  result = TRY_CLAIM(scope, K, fp)            # atomic INSERT … ON CONFLICT
  if result == CLAIMED:                       # we are first
      try:
          BEGIN TXN
            effect = DO_WORK(request)          # the real side effect (Case A)
            STORE_RESPONSE(K, effect)          # status -> COMPLETED, save body
          COMMIT TXN
          return effect
      catch transient error:
          DELETE_RECORD(K)                     # allow a later retry to re-run
          rethrow
  else:                                        # key already existed
      existing = LOAD(K)
      if existing.fp != fp: return 422
      if existing.status == COMPLETED: return existing.response   # REPLAY
      else: return 409                          # IN_PROGRESS
```

---

## 4. The complete toolkit

This section enumerates the concrete APIs, primitives, configs, and tools, with parameters and defaults. Where a number is version/vendor-specific, it's flagged.

### 4.1 Storage primitives for "atomic claim"

| Store | Primitive | Semantics | Notes / defaults |
|---|---|---|---|
| **PostgreSQL / MySQL** | `UNIQUE` index + `INSERT … ON CONFLICT DO NOTHING` (PG) / `INSERT IGNORE` or `INSERT … ON DUPLICATE KEY UPDATE` (MySQL) | Atomic claim; second insert is a no-op or detectable | Strongest; transactional with business write |
| **PostgreSQL** | `INSERT … ON CONFLICT (key) DO UPDATE … RETURNING` | Upsert + read existing in one round trip | Great for replay |
| **Redis** | `SET key val NX EX <ttl>` | Set only if Not eXists, with TTL | `NX` makes it atomic; returns nil if existed |
| **Redis** | `SET key val NX PX <ms>` | Same, millisecond TTL | |
| **Redis** | Lua script (`EVAL`) | Multi-step atomic (check+store response) | For replay-capable dedup |
| **Redis** | `WATCH`/`MULTI`/`EXEC` | Optimistic CAS transaction | Heavier than `SET NX` |
| **DynamoDB** | `PutItem` with `ConditionExpression: attribute_not_exists(pk)` | Conditional write = atomic claim | Throws `ConditionalCheckFailedException` on dup |
| **DynamoDB** | `UpdateItem` with condition + TTL attribute | CAS + auto-expiry | TTL attribute, sweeper deletes within ~48h (not exact) |
| **SQS FIFO** | `MessageDeduplicationId` | Broker-side dedup | **5-minute** window, fixed (AWS) |
| **Kafka** | Idempotent producer (`enable.idempotence=true`) | Dedup of producer retries per partition | Default `true` since Kafka 3.0 |

### 4.2 The idempotency record schema (canonical)

```sql
CREATE TABLE idempotency_keys (
    scope            VARCHAR(128)  NOT NULL,   -- tenant/account
    endpoint         VARCHAR(128)  NOT NULL,   -- operation type
    idem_key         VARCHAR(255)  NOT NULL,   -- client-supplied key
    request_fp       CHAR(64)      NOT NULL,   -- sha256 of canonical payload
    status           VARCHAR(16)   NOT NULL,   -- IN_PROGRESS | COMPLETED | FAILED
    response_code    INT,                      -- saved for replay
    response_body    TEXT,                     -- saved for replay
    locked_at        TIMESTAMP,                -- for IN_PROGRESS lease/stale detection
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    expires_at       TIMESTAMP     NOT NULL,   -- TTL
    PRIMARY KEY (scope, endpoint, idem_key)    -- enforces atomic claim
);
CREATE INDEX idx_idem_expiry ON idempotency_keys (expires_at);  -- for the sweeper
```

### 4.3 HTTP-level conventions

| Element | Convention | Notes |
|---|---|---|
| `Idempotency-Key` header | Client-supplied UUID/token | Stripe, and the IETF draft `draft-ietf-httpapi-idempotency-key-header`. Not yet a finalized RFC — flag as evolving. |
| `Idempotency-Replayed` (response) | Some APIs return a header indicating a replay | Helpful for clients/debugging |
| `409 Conflict` | In-progress duplicate | |
| `422 Unprocessable Entity` | Key reused with different body | Stripe returns an error in this case |
| Idempotency window | API-documented TTL | **Stripe: keys retained for 24 hours** (vendor-specific, can change) |

### 4.4 Java/JVM building blocks

| Tool | Purpose | Key APIs / config |
|---|---|---|
| **Spring `@Transactional`** | Tie effect + idempotency record in one txn | `propagation`, `isolation` (default `READ_COMMITTED` on most DBs) |
| **Spring Data JPA** | Persist idempotency rows | `save`, `findById`; rely on DB unique constraint |
| **`DataIntegrityViolationException`** | Catch the unique-violation = "duplicate" | Maps DB constraint error |
| **Spring Kafka `@KafkaListener`** | Consume; manual ack mode for commit-then-ack | `AckMode.MANUAL` / `MANUAL_IMMEDIATE` |
| **`enable.auto.commit=false`** | Control offset commit precisely | Default `true` (dangerous for exactly-once effects) |
| **Spring Kafka error handlers** | `DefaultErrorHandler`, `SeekToCurrentErrorHandler` (legacy) | Redelivery/backoff |
| **Lettuce / Jedis (Redis)** | `SET NX EX`, Lua `EVAL` | `RedisTemplate.opsForValue().setIfAbsent(k, v, Duration)` |
| **Resilience4j / Spring Retry** | Client-side retries with a *fixed* idempotency key | `@Retryable`, `RetryConfig.maxAttempts` |
| **Kafka Streams** | `processing.guarantee=exactly_once_v2` | Transactional read-process-write within Kafka only |
| **Spring `@KafkaListener` + outbox** | Reliable publishing | Combine with Debezium CDC |

### 4.5 Broker/infra dedup features (vendor-specific — flagged)

| System | Feature | Window / default | Caveats |
|---|---|---|---|
| **AWS SQS FIFO** | `MessageDeduplicationId` or content-based dedup | **5 min** dedup interval (fixed) | Only FIFO queues; standard queues have **no** dedup |
| **Kafka idempotent producer** | `enable.idempotence=true` | Per producer-session, per partition | Dedups *producer* retries, **not** consumer-side app duplicates |
| **Kafka transactions / EOS** | `transactional.id`, `processing.guarantee=exactly_once_v2` | Within Kafka read-process-write | Doesn't cover external side effects |
| **Google Pub/Sub** | Exactly-once delivery (regional) + `ordering_key` | Per subscription | Still recommend app-level dedup |
| **RabbitMQ** | No native dedup | — | Plugins exist; usually app-level |
| **Redis** | `SET NX`, RedisBloom (probabilistic) | TTL-based | Bloom filter trades memory for tiny false-positive rate |

### 4.6 The dedup-store TTL/cleanup toolkit

| Mechanism | How cleanup happens | Notes |
|---|---|---|
| Redis `EX/PX` | Lazy + active expiration by Redis | Set TTL at write time; nothing to sweep |
| DynamoDB TTL attribute | Background sweeper | Deletes within ~48h of expiry (not exact) — don't rely on precise timing |
| SQL `expires_at` + sweeper job | Cron/`DELETE WHERE expires_at < now()` | Run frequently; index `expires_at` |
| Partitioned tables by day | Drop old partitions | Cheapest cleanup at high volume |

---

## 5. Code examples by use case

All examples are Java unless noted. They are written to be adapted, with comments on the load-bearing lines.

### 5.1 Use case A — Idempotent payment endpoint (DB-backed, replay-capable)

A `POST /charges` that is safe to retry. Side effect = external gateway (Case B), so we chain idempotency. We persist the response for replay.

```java
// === Domain ===
record ChargeRequest(String orderId, long amountCents, String currency) {}
record ChargeResponse(String chargeId, String status) {}

// === Idempotency record ===
@Entity
@Table(name = "idempotency_keys",
       uniqueConstraints = @UniqueConstraint(columnNames = {"scope","endpoint","idem_key"}))
class IdempotencyRecord {
    @Id @GeneratedValue Long id;
    String scope;            // tenant/account
    String endpoint;         // "POST /charges"
    String idemKey;          // client-supplied Idempotency-Key
    String requestFp;        // sha256 of canonical body
    String status;           // IN_PROGRESS | COMPLETED
    Integer responseCode;
    @Lob String responseBody;
    Instant expiresAt;
    // getters/setters/constructors omitted
}

interface IdempotencyRepo extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByScopeAndEndpointAndIdemKey(String s, String e, String k);
}
```

```java
@RestController
class ChargeController {

    private final IdempotencyRepo repo;
    private final PaymentGateway gateway;       // external, supports its own idem key
    private final ObjectMapper json;
    private final PlatformTransactionManager txm;

    // ... constructor ...

    @PostMapping("/charges")
    public ResponseEntity<?> charge(
            @RequestHeader("Idempotency-Key") String idemKey,   // REQUIRED
            @RequestAttribute("tenantId") String tenant,
            @RequestBody ChargeRequest body) throws Exception {

        final String endpoint = "POST /charges";
        final String fp = sha256(canonicalize(body));           // bind key to payload

        // --- Step 1: atomic claim ---
        IdempotencyRecord rec = new IdempotencyRecord();
        rec.scope = tenant; rec.endpoint = endpoint; rec.idemKey = idemKey;
        rec.requestFp = fp; rec.status = "IN_PROGRESS";
        rec.expiresAt = Instant.now().plus(Duration.ofHours(24));   // TTL > max retry window
        try {
            repo.saveAndFlush(rec);     // flush forces the INSERT now -> unique violation surfaces here
        } catch (DataIntegrityViolationException dup) {
            // --- Step 2: a record already exists: replay / conflict ---
            IdempotencyRecord existing = repo
                .findByScopeAndEndpointAndIdemKey(tenant, endpoint, idemKey)
                .orElseThrow();
            if (!existing.requestFp.equals(fp)) {
                return ResponseEntity.status(422)
                    .body("Idempotency-Key reused with a different payload");
            }
            if ("COMPLETED".equals(existing.status)) {
                return ResponseEntity.status(existing.responseCode)   // REPLAY original
                    .body(json.readValue(existing.responseBody, ChargeResponse.class));
            }
            return ResponseEntity.status(409).body("Request already in progress");
        }

        // --- Step 3: we are the first. Do the real (external) work. ---
        // Derive a *stable* downstream key from our key so the gateway dedups too (Case B).
        String gatewayKey = "chg_" + idemKey;
        ChargeResponse resp = gateway.charge(body.amountCents(), body.currency(),
                                             body.orderId(), gatewayKey);

        // --- Step 4: persist response + mark COMPLETED ---
        rec.status = "COMPLETED";
        rec.responseCode = 200;
        rec.responseBody = json.writeValueAsString(resp);
        repo.save(rec);

        return ResponseEntity.ok(resp);
    }

    static String canonicalize(ChargeRequest r) {  // stable, field-ordered representation
        return r.orderId() + "|" + r.amountCents() + "|" + r.currency();
    }
    static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

**Why this is correct:**
- The `saveAndFlush` + unique constraint is the *atomic claim* — two concurrent retries: one inserts, the other gets `DataIntegrityViolationException` and replays/409s.
- The downstream `gatewayKey` makes the *external* charge idempotent (Case B); a crash after charging but before marking COMPLETED is recovered because the next retry re-calls the gateway with the same key and gets the same charge back.
- Reusing the key with a different body is rejected (422), preventing a dangerous "I thought K meant charge $10 but now it means charge $1000" confusion.

**Known residual risk:** if you crash *between* the gateway charge and the `repo.save(COMPLETED)`, the record stays IN_PROGRESS; a retry will re-call the gateway (safe, deduped) and then complete. But an IN_PROGRESS record could be *abandoned* if the client never retries — a sweeper should expire stale IN_PROGRESS rows (and you'd re-charge-safe on the next genuine retry).

### 5.2 Use case B — Idempotent local write in one transaction (Case A, gold standard)

Creating an order in your own DB. Effect + idempotency record in one transaction — no external dependency.

```java
@Service
class OrderService {
    private final OrderRepo orders;
    private final IdempotencyRepo idem;

    @Transactional   // <-- effect + dedup record commit atomically
    public Order createOrder(String tenant, String idemKey, CreateOrderCmd cmd) {
        String fp = sha256(canonicalize(cmd));
        try {
            IdempotencyRecord r = new IdempotencyRecord();
            r.scope = tenant; r.endpoint = "createOrder"; r.idemKey = idemKey;
            r.requestFp = fp; r.status = "COMPLETED";    // we'll fill response after work
            idem.saveAndFlush(r);                        // claim within the txn

            Order order = orders.save(new Order(cmd));   // the real work, SAME txn
            r.responseBody = String.valueOf(order.getId());
            r.responseCode = 201;
            return order;                                // commit happens at method end
        } catch (DataIntegrityViolationException dup) {
            // Duplicate: load and return the original order (replay)
            IdempotencyRecord existing = idem
               .findByScopeAndEndpointAndIdemKey(tenant, "createOrder", idemKey)
               .orElseThrow();
            return orders.findById(Long.valueOf(existing.responseBody)).orElseThrow();
        }
    }
}
```

Here, because everything is in one DB and one `@Transactional` method, the order row and the idempotency row commit together or not at all — the strongest possible guarantee. Note we must handle the unique-violation, which in some JPA setups marks the transaction rollback-only; in production you'd structure this as: try a separate read-first fast path, then insert-and-catch in a nested/new transaction. (A `REQUIRES_NEW` propagation or a native `INSERT ... ON CONFLICT ... RETURNING` avoids the rollback-marking pitfall — see §6.)

A cleaner, race-free Postgres-native variant avoids the rollback issue entirely:

```java
// Single round-trip atomic upsert; no exception-driven control flow.
@Query(value = """
    INSERT INTO idempotency_keys (scope, endpoint, idem_key, request_fp, status, expires_at)
    VALUES (:scope, :endpoint, :key, :fp, 'IN_PROGRESS', now() + interval '24 hours')
    ON CONFLICT (scope, endpoint, idem_key) DO NOTHING
    RETURNING id
    """, nativeQuery = true)
Optional<Long> tryClaim(String scope, String endpoint, String key, String fp);
// Returns present(id) if we claimed; empty() if it already existed (-> go replay).
```

### 5.3 Use case C — Idempotent Kafka consumer (at-least-once → exactly-once effects)

Spring Kafka, manual ack, commit-then-ack ordering, app-level `eventId` dedup.

```java
@Configuration
class KafkaCfg {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory(
            ConsumerFactory<String, OrderEvent> cf) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, OrderEvent>();
        f.setConsumerFactory(cf);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);  // we ack manually
        return f;
    }
    // consumer props: enable.auto.commit=false (so offsets commit only when WE say)
}
```

```java
@Component
class OrderEventConsumer {
    private final ProcessedRepo processed;     // unique constraint on event_id
    private final InventoryService inventory;

    @KafkaListener(topics = "orders", containerFactory = "factory")
    public void onMessage(OrderEvent ev, Acknowledgment ack) {
        // eventId is minted by the PRODUCER when the event is created -> stable across replays
        String eventId = ev.eventId();
        try {
            applyOnce(eventId, ev);   // transactional dedup + effect
        } catch (DuplicateEventException dup) {
            // already processed -> just ack and move on
        }
        ack.acknowledge();            // ACK ONLY AFTER the effect is committed
    }

    @Transactional
    void applyOnce(String eventId, OrderEvent ev) {
        try {
            processed.saveAndFlush(new ProcessedMessage(eventId));   // claim (unique)
        } catch (DataIntegrityViolationException dup) {
            throw new DuplicateEventException(eventId);              // seen before -> skip
        }
        inventory.decrement(ev.sku(), ev.qty());   // the real effect, SAME txn as the claim
    }
}
```

**Why correct / the ordering rule:** `applyOnce` commits the dedup record *and* the inventory change atomically. We `ack.acknowledge()` only *after* that returns. Crash sequence analysis:
- Crash *before* commit → broker redelivers → reprocessed cleanly (no record yet). Safe.
- Crash *after* commit, *before* ack → broker redelivers → `processed` already has `eventId` → skip → ack. Safe (effect not repeated).
- We *never* ack before committing, so we never lose a message.

### 5.4 Use case D — Redis-backed dedup with `SET NX` (fast path, no replay)

When you only need *suppression* (not replay) and want very low latency — e.g., dedup of webhook deliveries you *receive*.

```java
@Component
class WebhookDedup {
    private final StringRedisTemplate redis;

    /** @return true if this is the FIRST time we've seen this delivery id. */
    public boolean claim(String deliveryId) {
        Boolean first = redis.opsForValue()
            .setIfAbsent("wh:" + deliveryId, "1", Duration.ofHours(48));  // SET NX EX
        return Boolean.TRUE.equals(first);
    }
}

@PostMapping("/webhooks/stripe")
public ResponseEntity<Void> receive(@RequestHeader("Stripe-Signature") String sig,
                                    @RequestBody String raw) {
    verifySignature(sig, raw);                    // always verify webhooks
    String deliveryId = extractEventId(raw);      // Stripe event id "evt_..."
    if (!dedup.claim(deliveryId)) {
        return ResponseEntity.ok().build();        // duplicate delivery -> no-op, 200
    }
    process(raw);                                  // do the work once
    return ResponseEntity.ok().build();
}
```

**Caveats (flagged):** `SET NX EX` is atomic *for the claim*, but there's still the Case-C window between `claim()` and `process()`. If `process()` crashes after claiming, the key is set so a retry will *skip* and you'll have *lost* the work — the opposite failure of double-processing. For at-least-once correctness with Redis you generally want a two-phase approach (claim as IN_PROGRESS, then a second op to COMPLETED, with a short lease so abandoned IN_PROGRESS keys can be reclaimed) — i.e., move toward the DB-backed state machine. Use the simple `SET NX` only when *missing* one event is acceptable, or when `process()` is itself retried by an outer at-least-once loop that re-derives the claim.

A safer Redis pattern uses a Lua script to atomically read-or-write the status and stored response, mirroring the SQL state machine:

```lua
-- KEYS[1]=idemKey  ARGV[1]=fp  ARGV[2]=ttlSeconds
local v = redis.call('HGETALL', KEYS[1])
if next(v) == nil then
  redis.call('HSET', KEYS[1], 'fp', ARGV[1], 'status', 'IN_PROGRESS')
  redis.call('EXPIRE', KEYS[1], ARGV[2])
  return 'CLAIMED'
end
-- existing: return its status so caller can replay or 409
return redis.call('HGET', KEYS[1], 'status')
```

### 5.5 Use case E — Making a non-idempotent "increment" idempotent (synthetic)

Downstream offers only `addBalance(delta)` (not idempotent). We wrap it with a dedup ledger.

```java
@Service
class BalanceService {
    private final LedgerRepo ledger;     // unique(idem_key)
    private final BalanceRepo balances;

    @Transactional
    public long applyDelta(String account, String idemKey, long delta) {
        try {
            ledger.saveAndFlush(new LedgerEntry(idemKey, account, delta));  // claim + record the intent
        } catch (DataIntegrityViolationException dup) {
            // already applied this delta -> return current balance, do NOT add again
            return balances.findByAccount(account).getAmount();
        }
        Balance b = balances.findByAccount(account);
        b.setAmount(b.getAmount() + delta);   // the increment, SAME txn as the ledger claim
        return b.getAmount();
    }
}
```

The **ledger** is the dedup store; the unique `idemKey` makes the otherwise-unsafe increment exactly-once. A bonus: the ledger is now an audit log of every applied delta — a common real-world design ("event-sourced balance").

### 5.6 Use case F — Idempotent saga step with compensation

In a saga (a sequence of local transactions with compensating actions on failure), each step and each compensation must be idempotent because the orchestrator retries.

```java
// Step: reserve inventory. Idempotent via reservationId (the saga's id).
@Transactional
public void reserveInventory(String sagaId, String sku, int qty) {
    try {
        reservations.saveAndFlush(new Reservation(sagaId, sku, qty)); // claim by sagaId
    } catch (DataIntegrityViolationException dup) {
        return;   // already reserved for this saga -> no-op (idempotent)
    }
    inventory.decrement(sku, qty);
}

// Compensation: release inventory. Also idempotent.
@Transactional
public void releaseInventory(String sagaId) {
    Reservation r = reservations.findBySagaId(sagaId).orElse(null);
    if (r == null || r.isReleased()) return;   // already released -> no-op
    inventory.increment(r.getSku(), r.getQty());
    r.setReleased(true);
}
```

A **saga** is a way to do a "distributed transaction" without locking many services: you break it into local transactions, and if a later step fails you run *compensating* transactions to undo the earlier ones. Because the orchestrator may retry both forward steps and compensations after crashes, **every step must be idempotent** — exactly the property we keyed on `sagaId` above.

### 5.7 Use case G — Client-side retry with a fixed idempotency key

The flip side: the *client* must reuse the same key across retries, or all of the above is pointless.

```java
String idemKey = UUID.randomUUID().toString();   // mint ONCE per logical operation
RetryConfig cfg = RetryConfig.custom()
    .maxAttempts(4)
    .intervalFunction(IntervalFunction.ofExponentialBackoff(200, 2.0)) // 200,400,800ms
    .retryExceptions(IOException.class, TimeoutException.class)
    .build();
Retry retry = Retry.of("charge", cfg);

Supplier<ChargeResponse> call = Retry.decorateSupplier(retry, () ->
    httpClient.post("/charges")
              .header("Idempotency-Key", idemKey)   // SAME key on every attempt
              .body(body)
              .execute());

ChargeResponse resp = call.get();   // safe to retry: server dedups on idemKey
```

**The cardinal client rule:** generate the key *outside* the retry loop and reuse it. A common bug is generating a fresh UUID *inside* the retry, which defeats idempotency entirely.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Extra round trip / write per request.** Every idempotent write adds at least one dedup-store operation. Mitigations: keep the dedup store fast (Redis for hot path, DB for durable), use a single atomic upsert (`INSERT … ON CONFLICT … RETURNING`) to combine check+claim into one round trip, and batch where possible.
- **Hot keys / contention.** If many requests share a scope or a sweep query locks rows, you get contention. Index `expires_at`; consider partitioning by day so cleanup is a partition drop, not a big `DELETE`.
- **Replay payload size.** Storing the full response body for replay costs storage and write bandwidth. Cap stored body size; for large responses, store a pointer (e.g., the created resource id) and reconstruct.
- **Fingerprint cost.** SHA-256 of a big payload is cheap relative to a DB write, but canonicalization (JSON re-serialization) can be the hidden cost — canonicalize only the identity-defining fields.

### 6.2 Correctness & concurrency

- **Always make check+claim atomic** (unique constraint / `SET NX` / conditional write). Never `SELECT` then `INSERT` as two statements without a constraint backing it.
- **Tie the effect to the record** in one transaction when possible (Case A). When the effect is external, delegate idempotency downstream (Case B).
- **Bind the key to the payload fingerprint** so a reused key with a different body is rejected, not silently treated as the same op.
- **Choose the IN_PROGRESS policy** deliberately: 409 (simple) vs wait-and-replay (better UX). Document it.
- **JPA pitfall:** catching `DataIntegrityViolationException` *inside* a `@Transactional` method can mark the transaction rollback-only, so subsequent reads/writes in the same transaction fail. Fixes: use a *read-first fast path* before the insert; use `INSERT … ON CONFLICT DO NOTHING RETURNING` (no exception); or do the claim in a separate `REQUIRES_NEW` transaction.
- **Isolation levels.** Under `READ COMMITTED` (Postgres/MySQL default), two concurrent inserts of the same key still race correctly *because the unique index serializes them* — one commits, the other errors. You do *not* need `SERIALIZABLE` for the claim; you need the unique constraint. For the *replay-read* of an IN_PROGRESS row written by a not-yet-committed transaction, remember you won't see uncommitted rows under READ COMMITTED — hence the 409 fallback.

### 6.3 Security

- **Key scoping prevents cross-tenant poisoning.** Always scope keys per tenant/account; otherwise tenant A could pre-claim a key that tenant B later uses (a denial-of-service or response-leak vector). Make the stored key compound: `(tenantId, endpoint, key)`.
- **Validate key format/length** to prevent storage-exhaustion attacks (e.g., reject keys > 255 chars).
- **Don't leak another principal's stored response.** Replay must verify the *same caller* (scope) before returning a stored body.
- **Webhooks you receive:** dedup the *signed* event id, and verify the signature *before* trusting the id, so an attacker can't poison your dedup store with forged ids.
- **Rate-limit key creation** so a flood of unique keys can't bloat the store.

### 6.4 Observability

Instrument these signals:

| Metric | Why |
|---|---|
| Idempotency hits (replays) per endpoint | High → clients retrying a lot; possible upstream timeout issue |
| `422` reuse-with-different-body count | Client bug (regenerating keys or mutating payload) |
| `409` in-progress count | Concurrency / slow processing |
| Dedup-store latency / error rate | The dedup store is now on your critical path |
| Stale `IN_PROGRESS` count | Crashes leaving abandoned records |
| TTL-expiry sweep volume | Capacity planning |

Log the idempotency key (or its hash) in request logs and propagate it in trace context so you can correlate all retries of one logical operation. Add an `Idempotency-Replayed: true` response header to make replays visible in client logs.

### 6.5 Cost

- Dedup storage grows with traffic × TTL. At high volume, a 24h TTL on millions of requests/day is a real bill — use partition drops and tight TTLs.
- External-idempotency calls (e.g., Stripe) cost the same whether first or replayed, but replay typically returns from their cache — confirm the vendor doesn't bill replays as new operations.

### 6.6 Testing

- **Duplicate-injection tests:** send the same request twice (sequentially and *concurrently*) and assert one effect + identical responses.
- **Concurrency test:** fire N parallel requests with the same key; assert exactly one passes the claim, others 409/replay, and the side effect counter == 1.
- **Crash-in-window tests:** kill the process between effect and record-update (use a test hook/fault injection); restart; replay; assert no double effect.
- **TTL-boundary test:** advance a clock past TTL; assert a late duplicate behaves per policy.
- **Key-reuse-different-body test:** assert 422.
- **Consumer redelivery test:** make the broker redeliver (e.g., throw after commit-before-ack) and assert exactly-once effect.

### 6.7 Production hardening checklist

- Require `Idempotency-Key` on all mutating public endpoints; reject missing keys.
- Compound, scoped, fingerprinted keys.
- Single atomic claim; effect tied to record (Case A) or delegated downstream (Case B).
- Store response for replay; return original status code.
- TTL > max retry/redelivery window (account for client backoff schedules, broker redelivery, manual replays).
- Sweeper for expired and stale-IN_PROGRESS records, with a lease/timeout to reclaim abandoned ones.
- Metrics + tracing keyed on the idempotency key.
- Document the contract (key required? scope? TTL? 409 vs wait?) in your API docs.

### 6.8 Anti-patterns (avoid these)

| Anti-pattern | Why it's wrong | Do instead |
|---|---|---|
| Generating the key *server-side* | Every retry looks new → no dedup | Client generates and reuses the key |
| Regenerating the key inside the retry loop (client) | Defeats the whole mechanism | Mint once per logical op, reuse |
| `SELECT` then `INSERT` without a unique constraint | Race: two duplicates both insert | Atomic claim via constraint/`SET NX`/CAS |
| Storing only "seen," not the response | Duplicate gets empty/409 instead of original 200 | Store and replay the response |
| TTL shorter than the retry window | Late duplicate reprocessed | TTL > max possible duplicate delay |
| Hashing the *whole* payload for the key | Benign field change → "new" op | Hash only identity-defining fields |
| Ack-then-commit in a consumer | Crash loses the message | Commit effect, *then* ack |
| Assuming Kafka EOS covers external side effects | EOS is Kafka-internal only | App-level idempotent consumer for external effects |
| Believing "exactly-once delivery" exists | It doesn't over a network | Design for exactly-once *effects* |
| No scoping → cross-tenant collisions | Security/correctness bug | Scope keys per tenant/endpoint |
| Treating non-idempotent + non-deduped external calls as safe to retry | Double SMS/email/charge | Outbox + downstream idempotency, or accept/limit duplicates |

---

## 7. Advanced topics & deep internals

### 7.1 Why exactly-once *delivery* is impossible (and what's actually possible)

The impossibility is a consequence of the **two generals problem**: two parties communicating over a lossy channel can never become *common-knowledge* certain that a message was received, because the acknowledgment can itself be lost, and the ack-of-ack can be lost, ad infinitum. Applied to messaging: the sender can never *know* the receiver got-and-processed the message exactly once without an ack, and the ack can be lost, forcing a resend — hence at-least-once. The only escape is to make duplicates harmless (idempotency) — yielding **exactly-once effects**. So when a vendor advertises "exactly-once," read it as "exactly-once *effects within our boundary*," not a violation of this theorem.

### 7.2 What Kafka "exactly-once semantics" (EOS) actually does

Kafka's EOS (`processing.guarantee=exactly_once_v2`) combines two things:

1. **Idempotent producer** (`enable.idempotence=true`, default true in modern Kafka): each producer gets a **PID** (producer id) and assigns a monotonically increasing **sequence number** per partition. The broker remembers the last sequence per (PID, partition) and *rejects duplicates* caused by producer retries. This dedups *producer-side retries only*.
2. **Transactions** (`transactional.id`): a consumer-process-producer loop can read offsets, process, produce output, and commit *consumer offsets and produced messages together* atomically via a transaction coordinator and a two-phase-commit-like protocol. Downstream consumers reading with `isolation.level=read_committed` only see committed messages.

**The crucial limitation:** EOS only covers data *within Kafka* (read from Kafka, write to Kafka, commit offsets). The moment your processing has an **external side effect** (charge a card, write to a non-Kafka DB, send an email), EOS does *not* make that side effect exactly-once. You still need an application-level idempotent consumer. EOS is a powerful tool for Kafka-to-Kafka pipelines (Kafka Streams), not a substitute for idempotency at the edges.

### 7.3 Idempotent producer internals (PID + sequence numbers)

When `enable.idempotence=true`:
- On first connect, the producer requests a **PID** from the broker.
- Each record sent to a partition carries `(PID, epoch, sequence)`.
- The broker tracks the highest contiguous sequence per `(PID, partition)`. A retry with an already-seen sequence is acknowledged but **not re-appended** — dedup. A gap (out-of-order) raises `OutOfOrderSequenceException`.
- The **epoch** fences zombie producers: when a new producer instance takes over a `transactional.id`, it bumps the epoch, and the broker rejects writes from the old epoch. This prevents a "split-brain" double-write.

Limits: this guarantee holds *within a producer session* (across retries) and per partition; it does not dedup logically-duplicate messages your *application* produces from different sessions — that's still your job.

### 7.4 SQS FIFO dedup internals (and the 5-minute trap)

SQS FIFO queues dedup on `MessageDeduplicationId` (or a content hash if content-based dedup is on) within a **fixed 5-minute** window. Two sends with the same dedup id inside 5 minutes → the second is accepted-but-not-delivered (deduped). The trap: if your retry happens *after* 5 minutes (e.g., a delayed redrive, a manual replay, a long outage), the dedup id has aged out and you get a *duplicate delivery*. So SQS FIFO dedup is a convenience layer, not a substitute for app-level idempotency on the consumer. (Standard SQS queues have *no* dedup at all and are explicitly at-least-once.)

### 7.5 Exactly-once with Flink/Kafka Streams (checkpoint + 2PC sinks)

Stream processors achieve exactly-once *effects* via **checkpointing** + **two-phase-commit sinks**. **Flink** periodically snapshots operator state (a *checkpoint*) and aligns it with source offsets; on recovery it restores state and rewinds the source. For external sinks, it uses a **TwoPhaseCommitSinkFunction**: writes are *pre-committed* (e.g., to a transaction) at checkpoint, and *committed* only after the checkpoint is confirmed durable — so on failure the pre-committed-but-uncommitted writes are aborted. This is exactly-once *effects* via transactional sinks, and it still requires the sink to support transactions or idempotent writes.

### 7.6 Idempotency conflict detection & the fingerprint

The fingerprint (`request_fp`) does more than reject reuse: it enables **safe key reuse detection** across retries that *legitimately* differ in non-semantic fields. Canonicalization should strip: timestamps, request ids, trace headers, and any server-assigned defaults. Hash only the **business-identity** fields. A subtle bug class: including a *default-filled* field (e.g., `currency` defaulting to "USD" on the server but absent on the client's first call vs present on retry) changes the fingerprint and produces spurious 422s. Canonicalize *after* defaulting, or only over client-provided identity fields.

### 7.7 Tuning the TTL precisely

TTL must exceed the **maximum duplicate window**, which is the sum of every layer that can resend:
```
TTL > max(client retry horizon) 
    + max(broker redelivery / visibility-timeout cycles)
    + max(manual replay / DLQ reprocessing window)
    + clock-skew margin
```
Examples: if clients retry for up to 1 hour and you sometimes manually replay a dead-letter queue up to 24h later, TTL should be ≥ ~25h. Stripe uses 24h (vendor default, flagged). For business keys (e.g., `orderId`) you may keep the dedup record *forever* (permanent unique constraint) — no TTL needed, at the cost of unbounded growth (mitigate with partitioning/archival).

### 7.8 Probabilistic dedup (Bloom filters) — when memory matters

For ultra-high-volume dedup where a *tiny* false-positive rate is acceptable, a **Bloom filter** (a space-efficient probabilistic set that can say "definitely not seen" or "probably seen") cuts memory dramatically. **RedisBloom** offers `BF.ADD`/`BF.EXISTS`. Tradeoff: a false positive means you *wrongly* treat a new item as a duplicate and *skip* it — i.e., you can *lose* work. Use Bloom filters only as a *pre-filter* in front of an exact store, or where dropping a 1-in-a-million item is acceptable (e.g., dedup of analytics events), never for money.

### 7.9 Idempotency vs commutativity vs CRDTs

Idempotency (`f(f(x)) = f(x)`) is related to but distinct from **commutativity** (order doesn't matter: `f∘g = g∘f`) and **associativity**. **CRDTs** (Conflict-free Replicated Data Types) are data structures whose merge operation is idempotent, commutative, and associative, so replicas converge regardless of duplication or reordering. A *grow-only set* or a *G-Counter* naturally absorbs duplicate updates. When you can model state as a CRDT, you get idempotency *for free* and don't need a dedup store — a powerful advanced design for eventually-consistent systems.

### 7.10 The "at-least-once + idempotent" vs "transactional" spectrum

You can place reliability designs on a spectrum:

- **Best-effort / at-most-once** — fastest, can lose data.
- **At-least-once + idempotent consumer** — the pragmatic default; exactly-once *effects*; simple, robust, language-agnostic.
- **Transactional / EOS (Kafka, Flink)** — exactly-once effects *within a transactional boundary*; lower app burden inside that boundary but doesn't cover external side effects.
- **Distributed transactions / 2PC** — strong, but blocking, slow, and fragile under coordinator failure; rarely used across services today.

The senior judgment: reach for **at-least-once + idempotency** as the default; use EOS for Kafka-internal pipelines; avoid 2PC across microservices.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Dedup store choice

| Store | Atomic claim | Durability | Latency | Replay support | Best for | Avoid when |
|---|---|---|---|---|---|---|
| **SQL unique constraint** | Strong (index) | Strong (ACID, same txn as effect) | ms | Easy (store response col) | Money, local effects (Case A) | Extreme throughput needing sub-ms |
| **Redis `SET NX`** | Strong (single key) | Weaker (needs AOF/replication; can lose on failover) | sub-ms | Possible (Lua/HSET) | Hot-path suppression, webhooks | Strong durability for money |
| **DynamoDB conditional write** | Strong | Strong | low single-digit ms | Yes (store item attrs) | Serverless, high scale | Need transactional tie to a non-Dynamo effect |
| **Broker dedup (SQS FIFO / Kafka idem producer)** | Built-in | Broker-managed | n/a | No | Reducing obvious duplicates | As the *only* line of defense |
| **Bloom filter (RedisBloom)** | Approximate | Weak | sub-ms | No | Analytics, very high volume | Money / any case where losing items is bad |

### 8.2 Natural vs synthetic idempotency — which to use

| Situation | Prefer |
|---|---|
| You control the operation's semantics | **Natural**: redesign to absolute SET, upsert, PUT, or CRDT |
| Operation genuinely creates a new entity | **Synthetic**: idempotency key + dedup store |
| Downstream only offers increment/append | **Synthetic** wrapper (ledger + unique key) |
| Eventually-consistent replicated state | **CRDT** (idempotent by construction) |

### 8.3 IN_PROGRESS policy

| Policy | Pros | Cons | Use when |
|---|---|---|---|
| Return `409` | Simple, no blocking | Client must retry to get result | Most APIs |
| Wait-and-replay | Best client UX (one call returns result) | Holds a connection; needs wait/notify | Low-latency UX-critical endpoints |

### 8.4 Delivery semantics decision

| Need | Choose |
|---|---|
| Loss acceptable, speed paramount | At-most-once (fire-and-forget) |
| No loss, duplicates handled by you | **At-least-once + idempotent consumer** (default) |
| Kafka-to-Kafka pipeline, no external effects | Kafka EOS / Kafka Streams |
| Strong cross-service atomicity, low volume, can tolerate blocking | (Reluctantly) 2PC — usually avoid; prefer saga + idempotency |

### 8.5 "Use when / avoid when" rules

**Use idempotency keys when:** the endpoint mutates state and can be retried; you call external non-idempotent services; you consume from at-least-once brokers; you run sagas/workflows.

**Avoid (or simplify) when:** the operation is a pure read (`GET`); the operation is *naturally* idempotent and a dedup store adds no safety (e.g., absolute `SET`); duplicates are provably harmless and rare and the dedup store's cost/latency isn't justified (rare — usually still worth it for money or user-visible effects).

---

## 9. Failure modes & debugging

### 9.1 Common production failures and how they manifest

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| **Double charge / duplicate order** | No idempotency key, or client regenerates it per retry, or TTL expired before retry | Correlate two effects to the same business intent; check whether both carried the same key; check key age vs TTL | Require & reuse key; lengthen TTL |
| **Duplicate emails/SMS** | Non-idempotent external call (Case C) without outbox | Trace the send; check for crash between effect and record | Outbox + downstream idem, or accept/limit |
| **Lost messages** | Ack-before-commit ordering in consumer | Inspect consumer offsets vs DB state; find acked-but-not-applied | Commit effect, then ack |
| **Spurious 422 "key reused"** | Fingerprint over volatile/defaulted fields | Diff the two payloads' canonical forms | Canonicalize only identity fields |
| **Stuck `IN_PROGRESS` records** | Crash between effect and mark-COMPLETED, no sweeper | Query records in IN_PROGRESS older than X | Add lease + sweeper; ensure Case A/B recovery |
| **Replay returns empty/409 instead of original** | Stored "seen" only, not response | Inspect record's response columns | Store and replay response |
| **Dedup store down → endpoint 500s** | Dedup store now on critical path | Check store latency/availability metrics | Add fallback/timeout policy; HA the store |
| **Cross-tenant collision** | Unscoped keys | Find a key shared by two tenants | Scope keys per tenant/endpoint |
| **Late duplicate after TTL** | TTL < max duplicate window | Compare duplicate arrival delta to TTL | Increase TTL beyond replay/DLQ horizon |

### 9.2 Debugging toolkit (actual commands)

- **Find duplicate effects (SQL):**
  ```sql
  SELECT order_ref, count(*) FROM charges
  GROUP BY order_ref HAVING count(*) > 1;     -- did the same intent charge twice?
  ```
- **Inspect idempotency records:**
  ```sql
  SELECT idem_key, status, created_at, expires_at
  FROM idempotency_keys WHERE status='IN_PROGRESS' AND created_at < now() - interval '10 minutes';
  ```
- **Redis: see if a dedup key exists and its TTL:**
  ```
  EXISTS wh:evt_123
  TTL    wh:evt_123          # seconds remaining; -1 = no expiry, -2 = absent
  ```
- **Kafka: check consumer lag / offsets:**
  ```
  kafka-consumer-groups.sh --bootstrap-server b:9092 --group g --describe
  ```
- **Kafka: confirm idempotent producer is on:** check broker/producer config `enable.idempotence`, `acks=all`.
- **Trace correlation:** search logs/traces by the idempotency key to see *all* attempts of one logical operation lined up with their results.

### 9.3 Real-world incident patterns (representative)

- **The "retry storm double-charge":** a payments API didn't require an idempotency key; during a downstream slowdown, clients' 30s timeouts fired and they retried; the slow-but-successful original plus the retry both charged. Fix: mandatory client-generated `Idempotency-Key` + server replay, plus client timeouts tuned above realistic latency. (This class of bug is exactly why Stripe's `Idempotency-Key` exists.)
- **The "DLQ replay after the window":** an outage filled a dead-letter queue; ops replayed it 6 hours later; the dedup TTL was 1 hour, so every replayed message reprocessed and double-applied. Fix: TTL ≥ DLQ replay horizon; or permanent business-key dedup.
- **The "ack before commit" message loss:** a consumer acked Kafka offsets via auto-commit on a fixed timer, then the DB write failed; offsets advanced, messages were never applied, and the data was silently lost. Fix: `enable.auto.commit=false`, manual ack *after* commit.
- **The "double-click order":** a web form without client-side dedup or server idempotency created two orders on a double-click. Fix: client mints a key on form load, sends it with the submit; server dedups.

---

## 10. Interview drill

Each question has a model answer and deep-probe follow-ups (with answers). Senior-signal questions are marked.

**Q1. What does it mean for an operation to be idempotent, and why does it matter in distributed systems?**
*Model:* An operation is idempotent if executing it once and executing it N times produce the same system state (and ideally the same response). It matters because networks are unreliable: clients must retry on timeout/loss, and retries are only safe if the operation is idempotent. Idempotency is the precondition for fault tolerance.
- *Probe: Is `GET` idempotent? Is it safe?* Both — it's read-only (safe) and repeatable (idempotent).
- *Probe: Is `POST` idempotent?* Not by default; you make it idempotent with an idempotency key + dedup store.
- *Probe: Difference between idempotent and safe?* Safe = no state change; idempotent = repeatable with same effect. `PUT`/`DELETE` are idempotent but not safe.

**Q2. Walk me through the server-side algorithm for an idempotent `POST`.**
*Model:* Read the client's `Idempotency-Key`; compute a fingerprint of the payload; atomically claim the key (INSERT with unique constraint / `SET NX`). If claimed, do the work, store the response, mark COMPLETED, return it. If the key already exists: if the fingerprint differs, return 422; if COMPLETED, replay the stored response; if IN_PROGRESS, return 409 (or wait).
- *Probe: Why fingerprint?* To detect a key reused with a different payload.
- *Probe: Why atomic claim?* To prevent two concurrent duplicates from both doing the work.
- *Probe: Where do you store the response and why?* In the record, so duplicates replay the original result (same status + body) and the client converges.

**Q3. Explain at-least-once vs exactly-once. Can you have exactly-once delivery?**
*Model:* At-least-once = retried until acked, so duplicates but no loss. Exactly-once *delivery* over a network is impossible (two generals / lost-ack regress). What's achievable is exactly-once *effects*: at-least-once delivery + idempotent processing.
- *Probe: Then what is Kafka EOS?* Exactly-once within Kafka's boundary (idempotent producer + transactions for read-process-write); it does not cover external side effects.
- *Probe: So how do you get exactly-once effects with external side effects?* Idempotent consumer with a dedup store, committing effect and dedup record atomically, then acking.
- *Probe: What's the safe commit/ack ordering?* Commit the effect first, ack second; redeliver-and-dedup is safe, ack-then-lose is not.

**Q4. (Senior-signal) You need to call a third-party payment API that may or may not be idempotent. Design the reliability.**
*Model:* Prefer to use the gateway's own idempotency key derived stably from our key (Case B) so retries dedup downstream. Record IN_PROGRESS locally, call the gateway with the derived key, then mark COMPLETED. If the gateway is *not* idempotent (Case C), I can't guarantee exactly-once; I'd minimize the crash window, push the call through a transactional outbox so the "charge intent" is durable and dedupable, and accept/limit residual duplicates with reconciliation. Justify by the impossibility result: without downstream idempotency, exactly-once external effects aren't attainable, only approachable.
- *Probe: Why not 2PC with the gateway?* Gateways don't offer XA; 2PC is blocking and fragile; idempotency composition is the standard.
- *Probe: How do you recover from a crash after charging but before marking COMPLETED?* Retry re-calls the gateway with the same key, gets the original charge back, then completes the record.

**Q5. How do you make an idempotent Kafka consumer? Walk the failure cases.**
*Model:* Use a stable producer-assigned `eventId`; in one DB transaction, insert `eventId` into a `processed` table (unique) and apply the effect; commit; then manually ack. Crash before commit → redeliver, reprocess cleanly. Crash after commit before ack → redeliver, dedup skips, re-ack. Never ack before commit (would lose the message).
- *Probe: Why an application `eventId` over topic-partition-offset?* Offsets are unstable across compaction/repartition/replay/mirroring; the producer-minted id is the true logical identity.
- *Probe: What container settings enable this in Spring Kafka?* `enable.auto.commit=false`, `AckMode.MANUAL`, ack after commit.

**Q6. How do you choose a TTL for the dedup store?**
*Model:* TTL must exceed the maximum duplicate window = client retry horizon + broker redelivery cycles + manual/DLQ replay window + clock-skew margin. Too short → late duplicates reprocess. For business keys you may keep records permanently (unique constraint) and skip TTL, trading growth for safety.
- *Probe: What real incident comes from too-short TTL?* DLQ replayed hours later after a 1h TTL → mass double-apply.
- *Probe: How clean up at scale?* Partition by day and drop partitions; index `expires_at` for sweepers.

**Q7. (Senior-signal) Natural vs synthetic idempotency — when do you redesign vs bolt on a dedup store?**
*Model:* Prefer natural idempotency when you control semantics — model absolute SET/upsert/PUT or a CRDT so duplicates are intrinsically harmless, no dedup store needed. Bolt on synthetic idempotency (key + dedup store) when the op genuinely creates new entities or the downstream only offers increments. Tradeoff: natural is cheaper and simpler but not always expressible; synthetic adds a store on the critical path but is universally applicable.
- *Probe: Give a natural-idempotency redesign.* Replace "add 5 to quantity" with "set quantity to 25" (server computes absolute), or use an upsert keyed on a business id.
- *Probe: When is a CRDT the answer?* Eventually-consistent replicated state where merges must absorb duplicates/reorders (e.g., counters, sets) — idempotent by construction.

**Q8. What concurrency hazards exist in a dedup implementation and how do you defeat them?**
*Model:* The lost-update race (two duplicates both pass a non-atomic check then both insert) — defeat with an atomic claim (unique constraint / `SET NX` / conditional write). The crash window between effect and record update — defeat by tying both in one transaction (Case A) or delegating to downstream idempotency (Case B). The check-then-act gap — collapse into one atomic op.
- *Probe: Do you need SERIALIZABLE isolation?* No — the unique index serializes concurrent claims even under READ COMMITTED.
- *Probe: JPA pitfall when catching the unique violation?* It can mark the transaction rollback-only; use read-first fast path, `ON CONFLICT DO NOTHING RETURNING`, or `REQUIRES_NEW`.

**Q9. (Senior-signal) When would you NOT add idempotency, and what are the costs of adding it?**
*Model:* Skip it for pure reads, for naturally idempotent ops where a dedup store adds no safety, and for genuinely harmless rare duplicates where the store's latency/cost isn't justified. Costs: an extra write/round trip per request, storage growth (× TTL), a new critical-path dependency (if the dedup store is down, do you fail open or closed?), and operational complexity (sweepers, leases). For anything touching money or user-visible effects, the cost is almost always justified.
- *Probe: Fail-open vs fail-closed if the dedup store is unavailable?* For money, fail closed (reject) to avoid double effects; for low-stakes, fail open with reconciliation.
- *Probe: How do you keep it off the hot path?* Redis for fast suppression, DB for durable money paths; single atomic upsert to avoid extra round trips.

**Q10. Explain the transactional outbox and why it produces a dedupable stream.**
*Model:* In the same DB transaction as the business write, insert an event row (with an `event_id`) into an outbox table. A relay (poller or CDC like Debezium reading the WAL) publishes outbox rows to the broker and marks them published; it may publish more than once (crash before marking) → at-least-once with a stable `event_id`. Consumers dedup on `event_id` → exactly-once effects. It solves the dual-write problem (DB + broker) without 2PC.
- *Probe: What's CDC/Debezium?* Tools that tail the DB transaction log and emit row changes as a stream, making the relay reliable without polling.
- *Probe: Why is the event_id critical?* It's the dedup key that lets at-least-once publishing become exactly-once effects.

**Q11. How does Kafka's idempotent producer actually dedup?**
*Model:* Each producer gets a PID and assigns a per-partition monotonic sequence number; the broker tracks the last sequence per (PID, partition) and rejects re-sends with an already-seen sequence (producer-retry dedup). An epoch fences zombie producers so an old instance can't double-write. It dedups producer retries only — not application-level logical duplicates.
- *Probe: Does it survive a producer restart?* The guarantee is per producer session; logical dedup across sessions is the app's job (and transactions/`transactional.id` + epoch handle fencing).
- *Probe: Default value of `enable.idempotence`?* `true` in modern Kafka (3.0+); pairs with `acks=all`.

**Q12. (Senior-signal) A teammate says "we use SQS FIFO, so we don't need idempotency." Respond.**
*Model:* SQS FIFO dedups only within a fixed 5-minute window on `MessageDeduplicationId`; any duplicate arriving after that (delayed redrive, manual replay, long outage) gets delivered again. Broker dedup is a convenience, not a correctness guarantee. We still need an idempotent consumer keyed on a stable application id for exactly-once effects. (And standard SQS has no dedup at all.)
- *Probe: What window does SQS FIFO use?* 5 minutes, fixed.
- *Probe: What's the safe design regardless of broker?* App-level idempotent consumer + commit-then-ack; treat broker dedup as best-effort defense-in-depth.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; the guarantees a transactional database provides.
- **Acknowledgment (ack)** — a signal to a broker that a message was successfully processed, so it won't be redelivered.
- **At-least-once** — delivery/processing semantics where each message is handled 1+ times; no loss, but duplicates occur.
- **At-most-once** — semantics where each message is handled 0 or 1 times; no duplicates, but loss is possible.
- **Bloom filter** — a space-efficient probabilistic set; answers "definitely not present" or "probably present" (no false negatives, possible false positives).
- **CAP** — a theorem stating that under a network Partition, a distributed system must choose between Consistency and Availability.
- **CAS (compare-and-set / compare-and-swap)** — an atomic "update only if current value equals expected" primitive.
- **CDC (change data capture)** — tailing a database's transaction log to emit a stream of row changes (e.g., Debezium).
- **Canonicalization** — normalizing a payload (sort keys, drop volatile fields) into a stable form for hashing/fingerprinting.
- **CRDT (Conflict-free Replicated Data Type)** — a data type whose merge is idempotent, commutative, and associative, so replicas converge despite duplication/reordering.
- **Dead-letter queue (DLQ)** — a queue holding messages that repeatedly failed processing, for later inspection/replay.
- **Debezium** — an open-source CDC platform that streams DB changes (Postgres WAL, MySQL binlog) to Kafka.
- **Dedup store** — durable memory of processed idempotency keys/event ids, used to detect duplicates.
- **DynamoDB conditional write** — a write that succeeds only if a condition holds (e.g., item doesn't exist), giving an atomic claim.
- **Epoch (Kafka)** — a counter bumped when a new producer takes over a `transactional.id`, used to fence stale ("zombie") producers.
- **Exactly-once delivery** — every message delivered precisely once; impossible in general over an unreliable network.
- **Exactly-once effects (effectively-once)** — the effect on state happens once, achieved via at-least-once delivery + idempotent processing.
- **Fencing** — preventing an outdated/duplicate actor (e.g., a zombie producer or stale lock holder) from acting.
- **Fingerprint** — a hash of the identity-defining request fields, used to bind an idempotency key to its payload.
- **Idempotency** — property where repeating an operation yields the same state/response as performing it once.
- **Idempotency key** — a unique token identifying one logical operation across all its retries.
- **Idempotent producer (Kafka)** — producer mode (`enable.idempotence=true`) that dedups producer retries via PID + per-partition sequence numbers.
- **IN_PROGRESS / COMPLETED / FAILED** — states of an idempotency record in the dedup state machine.
- **Isolation level** — the degree to which concurrent transactions are insulated from each other (e.g., READ COMMITTED, SERIALIZABLE).
- **Lease** — a time-bounded claim; used to reclaim abandoned IN_PROGRESS records after a timeout.
- **MVCC (Multi-Version Concurrency Control)** — a DB technique giving each transaction a consistent snapshot via multiple row versions.
- **Natural idempotency** — operations idempotent by their semantics (absolute SET, DELETE, PUT, upsert).
- **Outbox pattern** — writing events to a DB table in the same transaction as the business write, then relaying them to a broker.
- **PID (producer id, Kafka)** — broker-assigned id used with sequence numbers for producer-retry dedup.
- **Raft** — a consensus algorithm for replicating a log across nodes with a single elected leader (background for many brokers/coordinators).
- **Read-committed / read-uncommitted** — isolation levels controlling visibility of other transactions' (un)committed data.
- **Replay (response replay)** — returning the stored original response to a duplicate request.
- **Saga** — a long-running distributed transaction modeled as local transactions with compensating actions on failure.
- **SET NX EX (Redis)** — set a key only if absent, with an expiry; an atomic claim primitive.
- **SQS FIFO** — AWS first-in-first-out queue with ordering and a fixed 5-minute dedup window on `MessageDeduplicationId`.
- **Synthetic idempotency** — engineered idempotency via an idempotency key + dedup store on a non-idempotent op.
- **TCP** — a transport protocol giving reliable, ordered byte streams within a connection (but connections can still fail).
- **TTL (time-to-live)** — expiry after which a stored dedup key is forgotten.
- **Transactional id (Kafka)** — identifier enabling producer transactions and epoch-based fencing for exactly-once-within-Kafka.
- **Two generals problem** — the impossibility of guaranteed agreement over a lossy channel; the basis for why exactly-once delivery can't exist.
- **Two-phase commit (2PC)** — a blocking atomic-commit protocol across participants with a coordinator; strong but fragile/slow.
- **Unique constraint** — a DB index enforcing uniqueness; the workhorse for atomic idempotency claims.
- **WAL (write-ahead log)** — the DB's durable log of changes; the source CDC tools tail.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
GOAL: repeating an op = doing it once (idempotency). MEANS: detect & suppress/replay duplicates (dedup).

THE EQUATION:   at-least-once delivery  +  idempotent processing  =  exactly-once EFFECTS
                (exactly-once DELIVERY is impossible over a network — two generals)

IDEMPOTENCY KEY: client-generated, stable across retries, scoped (tenant+endpoint+key), fingerprinted to payload.

5-STEP SERVER ALGO:
  1 read key   2 fingerprint payload   3 ATOMIC CLAIM (unique / SET NX / conditional write)
  4 do work tied to record   5 store response + COMPLETED   (duplicate -> REPLAY; in-progress -> 409; reuse w/ diff body -> 422)

ATOMICITY CASES:
  A effect in your DB         -> same transaction (GOLD)
  B external + idempotent     -> delegate via downstream key, then mark COMPLETED
  C external + NOT idempotent -> can't guarantee; outbox + minimize window + reconcile

CONSUMER RULE:  commit effect, THEN ack.  (ack-then-crash = LOST message)
  dedup on producer-minted eventId, not topic-partition-offset.

TTL > client-retry horizon + broker redelivery + DLQ/manual replay window + skew.
  Stripe keys: 24h (vendor).  SQS FIFO dedup: 5 min (fixed).  Kafka enable.idempotence: true (3.0+).

HTTP: GET/HEAD safe+idempotent; PUT/DELETE idempotent not safe; POST/PATCH not idempotent (use a key).

NATURAL (prefer): absolute SET, upsert, PUT, DELETE, CRDT.   SYNTHETIC: key + dedup store.

TOP ANTI-PATTERNS: server-generated key; key regenerated per retry; SELECT-then-INSERT without constraint;
  store "seen" not response; TTL too short; ack before commit; trust broker dedup as sole defense.

STORES: SQL unique (money/local, ACID) | Redis SET NX (hot path) | Dynamo conditional (serverless) | Bloom (analytics only).
```

### 12.2 Self-test (no answers — active recall)

1. Sketch the five-step server-side algorithm for an idempotent `POST`, and explain at which exact step a unique constraint prevents a concurrent double-charge.
2. Your consumer crashes after committing the effect but before acking the broker. Trace what happens on redelivery and prove no double effect occurs.
3. A dead-letter queue is replayed 8 hours after the outage; your dedup TTL is 2 hours. Predict the bug and give two fixes.
4. You must integrate a payment gateway. Describe how you'd compose idempotency for Case B, and what residual risk remains if it's actually Case C.
5. Explain why "exactly-once delivery" is impossible but "exactly-once effects" is achievable, and where Kafka EOS does and does not help.
6. Give one example each of redesigning a non-idempotent operation into a *naturally* idempotent one and into a *synthetically* idempotent one.
7. Your service starts throwing spurious `422 "key reused"` errors on legitimate retries. List the most likely root cause and how you'd confirm it.
```
```

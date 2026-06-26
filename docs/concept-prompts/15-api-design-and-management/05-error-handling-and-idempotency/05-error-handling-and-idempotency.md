# Error Handling & Idempotency

> An exhaustive engineering-handbook chapter on designing error models and idempotent write APIs, for senior JVM backend developers. From first principles to deep internals.

---

## 1. Overview & where it fits

### What this chapter is about

Two problems sit at the center of every networked API, and they are deeply intertwined:

1. **Error handling** — when something goes wrong (and over a network, something *always* eventually goes wrong), how does the server tell the client *what* went wrong, *who* is at fault, *whether it is worth retrying*, and *how to act on it programmatically*? A good error model is a contract, not an afterthought.
2. **Idempotency** — when a client retries a request because it never saw a response (timeout, dropped connection, 502 from a proxy), how does the server guarantee the operation happens *at most once* even though the wire saw the request *more than once*? Without this, retries duplicate charges, double-create orders, and send duplicate emails.

These are joined at the hip because **errors cause retries, and retries cause duplicates.** You cannot design a safe retry policy without a clear error model (which errors are retryable?), and a retry policy is unsafe on write endpoints without idempotency. This chapter treats them as one subject.

### The problem each one solves

**Networks are unreliable in a specific, asymmetric way.** When a client sends a request and gets no response, there are *two* indistinguishable possibilities:

- The request never reached the server (safe to retry — nothing happened).
- The request reached the server, the server processed it and mutated state, but the *response* was lost on the way back (NOT safe to blindly retry — it already happened).

The client cannot tell these apart from its side. This is **the fundamental ambiguity of distributed systems**, sometimes called the **Two Generals Problem** (two armies on hilltops trying to coordinate an attack by sending messengers through a valley where messengers can be captured — they can never be *certain* the other received the last message). Idempotency is the practical engineering answer: make it safe for the client to retry by guaranteeing the server deduplicates.

**Error handling solves a different problem:** the gap between *machine* and *human* needs. A program calling your API needs a stable, parseable signal it can branch on (`insufficient_funds` vs `card_expired`). A developer reading logs at 3 a.m. needs a human-readable explanation and a correlation ID to trace. A naive API returns `500 Internal Server Error` with an HTML stack trace and satisfies neither.

### When you reach for these

| Situation | What you need |
|---|---|
| Any public or partner-facing HTTP/gRPC API | A consistent, documented error model |
| Any endpoint that mutates state and may be retried (payments, orders, transfers, provisioning) | Idempotency keys |
| Microservices calling each other with retries | Both: retryable-error classification AND idempotency on the callee |
| Event-driven / message-queue consumers (at-least-once delivery) | Idempotency (dedup), even though there's no "HTTP retry" |
| Batch/bulk endpoints | A partial-failure error model |

### One-paragraph mental model

> Think of your API as a **vending machine with a receipt printer**. The error model is the *receipt*: every interaction — success or failure — produces a structured, scannable receipt that says exactly what happened, in both a barcode (machine code) and printed words (human message), with a transaction number (correlation ID) you can quote to support. Idempotency is the *coin-return memory*: if you press the button twice because the machine seemed stuck, it remembers your first press by the **token you handed it** (the idempotency key) and gives you the same single soda plus the same single receipt — never two sodas, never two charges. The two features together let a nervous customer keep pressing the button safely and always understand the outcome.

---

## 2. Foundations from first principles

We build up the vocabulary from zero. If you already know HTTP status codes cold, skim to §2.6.

### 2.1 What "idempotent" actually means

**Idempotence** is a mathematical property: an operation `f` is idempotent if applying it multiple times produces the same result as applying it once. Formally, `f(f(x)) = f(x)`.

- Pressing a crosswalk button: idempotent. Pressing it 10 times = pressing it once.
- `SET x = 5`: idempotent. Running it again leaves `x = 5`.
- `x = x + 1`: **not** idempotent. Running it twice adds 2.
- Deleting a file: idempotent *in effect* (the end state — file gone — is the same), though the second call may report "not found".

In API terms, an operation is idempotent if **making the same call N times has the same observable effect on server state as making it once.** Crucially, idempotency is about *effect on state*, not about the response being byte-identical (though good idempotency replays the original response too — more on that later).

> ⚠️ **Common confusion:** idempotent ≠ "returns the same response." `GET /random` returns different bodies each call but is idempotent because it doesn't change state. Conversely, an idempotent *write* should ideally also replay the same *response* so the client experience is identical, but the defining property is the state effect.

### 2.2 Safe methods vs idempotent methods

HTTP defines two related-but-distinct properties for methods (from **RFC 9110**, the HTTP Semantics spec — the modern consolidation of the older RFC 7231):

- **Safe**: the method is *read-only* — it shouldn't change server state at all. `GET`, `HEAD`, `OPTIONS`, `TRACE`.
- **Idempotent**: repeating it has the same effect as doing it once. All safe methods are idempotent (doing nothing twice = doing nothing once), **plus** `PUT` and `DELETE`.

| Method | Safe? | Idempotent? | Typical semantics |
|---|---|---|---|
| `GET` | Yes | Yes | Read a resource |
| `HEAD` | Yes | Yes | Read headers only |
| `OPTIONS` | Yes | Yes | Describe communication options |
| `PUT` | No | **Yes** | Replace resource at a known URI |
| `DELETE` | No | **Yes** | Remove resource |
| `POST` | No | **No** | Create / process / "do something" |
| `PATCH` | No | **No** (not guaranteed) | Partial update |

The interesting cell is **`POST` is not idempotent.** `POST /orders` twice creates two orders. This is *the* reason idempotency keys exist: they retrofit idempotency onto `POST` (and onto `PATCH`).

**Why is `PUT` idempotent but `POST` isn't?** `PUT /users/42` says "make the resource at this exact URI equal to this body." Repeating it just re-sets the same URI to the same body — same end state. `POST /users` says "create a *new* user here" — the server picks the URI, so each call yields a new resource.

> **Important caveat:** HTTP method idempotency is a *semantic promise the spec asks you to keep*, not something HTTP enforces. You can write a buggy `PUT` handler that appends instead of replaces. The spec tells clients "you may retry `PUT`/`GET`/`DELETE` automatically"; it's your job to honor that.

### 2.3 At-most-once, at-least-once, exactly-once

These three **delivery/processing semantics** are foundational:

- **At-most-once**: the operation runs zero or one times. Never duplicated, but may be lost. (Fire-and-forget, no retries.)
- **At-least-once**: the operation runs one or more times. Never lost, but may be duplicated. (Retry until acknowledged.)
- **Exactly-once**: the operation runs precisely once. The holy grail.

The hard truth: **true exactly-once *delivery* is impossible** over an unreliable network (you can always lose the final ack). What you *can* achieve is **exactly-once *processing***, which is the combination:

> **at-least-once delivery + idempotent processing = effectively-once.**

The sender keeps retrying (at-least-once) so nothing is lost; the receiver deduplicates (idempotency) so nothing is duplicated. Idempotency keys are precisely the dedup mechanism that turns at-least-once into effectively-once. Kafka's "exactly-once semantics" works the same way under the hood (producer idempotence + transactional offsets).

### 2.4 What an HTTP status code is

For newcomers: every HTTP response starts with a 3-digit **status code** grouped by first digit:

- **1xx** — Informational (rarely used directly; e.g. `100 Continue`).
- **2xx** — Success. `200 OK`, `201 Created`, `202 Accepted` (request accepted, processing async), `204 No Content`.
- **3xx** — Redirection. `301 Moved Permanently`, `304 Not Modified`.
- **4xx** — **Client error**: the *caller* did something wrong; retrying the *identical* request won't help. `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict`, `422 Unprocessable Entity`, `429 Too Many Requests`.
- **5xx** — **Server error**: the *server* failed; the request may succeed on retry. `500 Internal Server Error`, `502 Bad Gateway`, `503 Service Unavailable`, `504 Gateway Timeout`.

This 4xx/5xx split is the *first cut* of retryability, but it's not the whole story (see §2.6).

### 2.5 Correlation IDs, trace IDs, request IDs

- A **request ID** uniquely identifies one HTTP request (often generated at the edge/gateway). Returned in a header like `X-Request-Id`. Lets you find *this one call* in logs.
- A **correlation ID** (a.k.a. **trace ID**) ties together *all* the work triggered by one logical operation as it fans out across services. If a single `POST /checkout` calls payments, inventory, and shipping, they all log the same correlation ID so you can reconstruct the whole story. This is the basis of **distributed tracing** (e.g. **OpenTelemetry**, an open standard for emitting traces/metrics/logs; **W3C Trace Context** standardizes the `traceparent` header that carries the trace ID across hops).

Putting a correlation ID in *every* error response is the single highest-leverage thing you can do for debuggability.

### 2.6 Retryable vs non-retryable — the real distinction

Naively: "retry 5xx, don't retry 4xx." But the precise rule is about **whether retrying *could possibly* change the outcome and whether it's *safe*:**

- **Retryable** = transient + the prior attempt either definitely didn't take effect or is protected by idempotency. Examples: `503`, `429`, `502`, `504`, connection reset, DNS timeout, `500` from a stateless read.
- **Non-retryable** = deterministic given the same input. Retrying the identical request gives the identical failure. Examples: `400` (malformed), `401` (bad creds), `403` (not allowed), `404` (gone), `422` (validation), `409` (logical conflict — usually).

Edge cases that trip people up:

| Status | Default class | Nuance |
|---|---|---|
| `408 Request Timeout` | Retryable | Client took too long; retry with the request again. |
| `409 Conflict` | Usually non-retryable | But may be retryable after refetching state (optimistic locking). |
| `425 Too Early` | Retryable | Replay risk; wait and retry. |
| `429 Too Many Requests` | Retryable **with backoff** | Respect `Retry-After`. |
| `500` | Depends | Retryable for idempotent ops; risky otherwise. |
| `501 Not Implemented` | Non-retryable | Won't ever work. |

The takeaway: **the server should *tell* the client whether to retry**, rather than forcing the client to guess from the status code. We'll formalize this with a `retryable` flag and `Retry-After` later.

### 2.7 Exponential backoff and jitter (the client side of safe retries)

If a server is overloaded and 1,000 clients all retry after exactly 1 second, they create a synchronized **thundering herd** that re-overloads it — a self-perpetuating outage (a "retry storm"). The fix:

- **Exponential backoff**: wait `base * 2^attempt` between retries (e.g. 100ms, 200ms, 400ms, 800ms…), capped at some max.
- **Jitter**: add randomness so clients de-synchronize. AWS's well-known recommendation is **full jitter**: `sleep = random(0, base * 2^attempt)`.
- **Retry budget / circuit breaker**: cap the *total* fraction of traffic that is retries (e.g. 10%), and stop calling a failing dependency entirely for a cooldown period (circuit "opens"). A **circuit breaker** is a state machine (Closed → Open → Half-Open) that prevents hammering a known-down dependency.

### 2.8 RFC 7807 / RFC 9457 — Problem Details

**RFC 7807** ("Problem Details for HTTP APIs", 2016) defined a standard JSON (and XML) format for machine-readable error responses, so that every API doesn't invent its own error shape. **RFC 9457** (2023) **obsoletes and replaces** RFC 7807 — it is the current standard, with minor clarifications (notably around multiple errors and the `type`/`instance` URIs). They are wire-compatible; if you implemented 7807 you are 9457-compliant in practice.

The media type is `application/problem+json`. The standard members:

| Member | Type | Meaning |
|---|---|---|
| `type` | URI (string) | A URI identifying the problem *category*. Defaults to `"about:blank"`. Dereferenceable docs encouraged but not required. |
| `title` | string | Short, human-readable summary of the problem type. Should be the same for the same `type`. |
| `status` | number | The HTTP status code, duplicated in the body (handy when the body is logged without headers). |
| `detail` | string | Human-readable explanation *specific to this occurrence*. |
| `instance` | URI (string) | URI identifying this specific occurrence (e.g. `/errors/abc-123`). |

Plus **extension members** — any additional fields you want (`code`, `errors`, `traceId`, `balance`, `retryable`…). This is where your machine-readable error code and field-level validation details live.

We'll use Problem Details as the backbone of the error model in §5.

---

## 3. How it works internally

This section traces the *actual runtime mechanics* — first of an idempotent write, then of a uniform error handler — step by step, including the concurrency edge cases that separate a toy implementation from a production one.

### 3.1 The lifecycle of an idempotent write (Stripe-style)

The canonical design: the client generates a unique **idempotency key** (a UUID or random 32+ char string) and sends it in a header, conventionally `Idempotency-Key`. The server stores the key → result mapping and replays on repeat.

There is an emerging IETF draft, **`draft-ietf-httpapi-idempotency-key-header`**, standardizing the `Idempotency-Key` request header; it's not yet an RFC as of this writing, so treat the header name as a de-facto convention popularized by Stripe, PayPal, Adyen, etc. (Flag: vendor convention, not yet a ratified standard.)

#### Control flow, step by step

```
Client                          Server (idempotency layer)            Store (DB)
  |                                   |                                   |
  | 1. POST /charges                  |                                   |
  |    Idempotency-Key: K, body B --> |                                   |
  |                                   | 2. fingerprint = hash(B)          |
  |                                   | 3. INSERT (K, fingerprint,        |
  |                                   |    status=IN_PROGRESS) ---------> | 
  |                                   |    ... ON CONFLICT DO NOTHING     |
  |                                   | <-------------- inserted? --------|
  |                                   |                                   |
  |        (CASE A: new key)          | 4a. row inserted -> execute op    |
  |                                   |     ... do the charge ... ------> |
  |                                   | 5a. UPDATE row SET status=DONE,   |
  |                                   |     response=R, http_status=200 ->|
  | <----- 200, body R ---------------| 6a. return R, store R             |
  |                                   |                                   |
  |        (CASE B: replay)           | 4b. row existed, status=DONE      |
  |                                   |     fingerprint matches?          |
  | <----- 200, body R (replayed) ----| 5b. return stored R               |
  |                                   |                                   |
  |        (CASE C: in-flight)        | 4c. row existed, status=IN_PROGRESS
  | <----- 409 Conflict --------------| 5c. a concurrent request is       |
  |          (or 425 / retry later)   |     still processing this key     |
  |                                   |                                   |
  |        (CASE D: key reuse)        | 4d. row existed, fingerprint      |
  | <----- 422 Unprocessable ---------|     MISMATCH -> reject            |
```

Let's expand each case.

#### The four cases in detail

**CASE A — first time we see key K.** The atomic `INSERT ... ON CONFLICT DO NOTHING` (Postgres) / `INSERT IGNORE` (MySQL) / conditional put (DynamoDB) succeeds. This insert is the **race-free claim** on the key. We mark it `IN_PROGRESS`, run the business logic exactly once, then persist the response and flip to `DONE`. The atomic insert is essential: a plain "SELECT then INSERT" has a window where two concurrent requests both see "no row" and both proceed — a classic **time-of-check-to-time-of-use (TOCTOU)** race producing a double charge.

**CASE B — replay of a completed key.** The row exists with `status=DONE`. We **replay the stored response** verbatim (same status code, same body, ideally same headers) without re-running the business logic. This is what makes the client's retry feel like a no-op success.

**CASE C — request still in flight.** Two requests with key K arrive nearly simultaneously; the first claimed the key and is mid-flight (`IN_PROGRESS`). The second must NOT proceed. Options:
- Return `409 Conflict` with a Problem Details telling the client to retry shortly. Stripe historically returned a specific error here.
- Or block briefly and poll for completion, then replay (more complex; risks holding connections).
The safe default is to reject with 409/425 and let the client back off.

**CASE D — same key, different body.** The client reused an idempotency key for a genuinely different request (a client bug). The stored `fingerprint` (hash of the request body, and ideally method + path + relevant headers) doesn't match. We **reject with `422 Unprocessable Entity`** (Stripe returns an error explaining the key was already used with different parameters). This catches client bugs early instead of silently returning the wrong cached result.

#### Data flow / what gets stored

The idempotency record typically holds:

| Column | Purpose |
|---|---|
| `idempotency_key` (PK or unique) | The client-supplied key. Often scoped — see below. |
| `request_fingerprint` | Hash of method + path + body (and maybe user) to detect key reuse. |
| `status` | `IN_PROGRESS` / `COMPLETED` (and maybe `FAILED`). |
| `response_status_code` | Stored HTTP status to replay. |
| `response_body` | Stored serialized response. |
| `response_headers` | (Optional) headers to replay. |
| `resource_id` | The created resource's ID (so even a partial record can link to it). |
| `created_at` | For TTL expiry. |
| `locked_at` / `expires_at` | For lease/lock semantics. |

#### Key scope

An idempotency key must be **scoped** so two different users' identical keys don't collide. Typical scope = `(api_key / account_id / user_id, idempotency_key)`. Stripe scopes keys per account. Never make the key globally unique across all tenants — a UUID collision is astronomically unlikely, but malicious or buggy clients could probe each other's results. Scope by authenticated principal.

#### TTL (time-to-live)

Idempotency records are kept for a window, then purged. **Stripe keeps idempotency keys for 24 hours.** After expiry, the same key is treated as new. The TTL must be **longer than your maximum realistic client retry window** (including manual retries) but short enough to bound storage. 24h is the common choice. (Flag: 24h is Stripe-specific; pick yours deliberately.)

> **Subtle but critical:** the TTL also means a key reused after expiry won't be deduplicated. Clients must generate a *fresh* key per logical operation and not "recycle" keys across days.

### 3.2 The state machine of an idempotency record

```
                 INSERT (claim)
   (no record) ───────────────► IN_PROGRESS
        ▲                          │   │
        │ TTL expiry / purge       │   │ business logic threw
        │                          │   │ a *retryable* failure
        │              success     │   ▼
        │           ┌──────────────┘  FAILED ──┐
        │           ▼                 (release)  │ allow retry with same key
   COMPLETED ◄──────┘                 └──────────┘
   (replay forever until TTL)
```

A key design decision: **what happens when the business logic fails?**
- If it fails in a way that *might* succeed on retry (transient, e.g. downstream `503`), you generally want to **release the key** (delete the IN_PROGRESS row, or mark FAILED) so the *same* idempotency key can be retried and actually do the work. Otherwise a transient blip permanently "burns" the key and the client can never complete the operation.
- If it fails *deterministically* (e.g. validation `422`), you can **store the failure response** and replay it — retrying won't help anyway, and replaying the same 422 is consistent.

Stripe's documented behavior: **results are only saved if the request *completes*** (i.e. the server actually produced a response). If the server never responds (it crashes mid-request), the key is effectively un-recorded or stuck IN_PROGRESS; a sweeper or a short IN_PROGRESS lease handles cleanup. This is why a **lease/lock with expiry** on IN_PROGRESS matters: a crashed handler shouldn't lock a key forever.

### 3.3 Concurrency: the races you must defeat

1. **Double-execute race (TOCTOU).** Defeated by an *atomic claim* (unique constraint + insert, or `INSERT ... ON CONFLICT`, or a DynamoDB conditional write with `attribute_not_exists`). Never check-then-act in two statements.
2. **Lost update on the response.** Two near-simultaneous requests: one executes, one must wait/replay. The IN_PROGRESS state + 409 handles this.
3. **Crash between executing and storing response.** Mitigations: (a) do the business write and the idempotency-record update in the **same database transaction** when they share a DB — then either both commit or neither does; (b) if they're in different systems, use the resource creation itself as the dedup record (store the idempotency key *on the created resource* with a unique constraint), so the existence of the resource *is* the proof of completion.
4. **Replay returns stale data.** If you store the response body at completion time, a later replay returns the *original* response even if the resource has since changed. This is usually *correct* (the client is retrying the *create*, not asking for current state) — but document it.

### 3.4 The lifecycle of a uniform error handler (Spring example)

For a typical Spring Boot service, an unhandled exception bubbling out of a controller flows like this:

```
Controller throws Exception
        │
        ▼
DispatcherServlet catches it
        │
        ▼
Searches registered HandlerExceptionResolvers in order:
   1. ExceptionHandlerExceptionResolver  ── matches @ExceptionHandler / @RestControllerAdvice
   2. ResponseStatusExceptionResolver    ── matches @ResponseStatus / ResponseStatusException
   3. DefaultHandlerExceptionResolver    ── maps Spring MVC's own exceptions to status codes
        │
        ▼
A matching @ExceptionHandler runs → builds ProblemDetail → serialized as
   application/problem+json with the chosen status
        │
        ▼
If nothing matches → falls through to /error (BasicErrorController) → default
   error JSON/HTML (the thing you want to override)
```

The goal of a uniform error handler is to ensure **every** exit path — validation failures, business exceptions, auth failures, and unexpected `RuntimeException`s — converges on **one consistent Problem Details shape** with a correlation ID, an error `code`, and a `retryable` hint. We implement this in §5 with `@RestControllerAdvice`.

Internally, the order matters: more specific `@ExceptionHandler(MyException.class)` wins over a broader `@ExceptionHandler(Exception.class)`. Spring picks the **most specific** matching handler by exception type hierarchy. Since Spring 6 / Boot 3, `ProblemDetail` and `ErrorResponse` are first-class — Spring MVC will itself emit RFC 9457 bodies for its built-in exceptions if you enable it (`spring.mvc.problemdetails.enabled=true`).

---

## 4. The complete toolkit

### 4.1 HTTP status codes you'll actually map to (curated)

| Code | Name | Use it for | Retryable? |
|---|---|---|---|
| `200` | OK | Successful read/update returning a body | n/a |
| `201` | Created | Resource created (set `Location` header) | n/a |
| `202` | Accepted | Async accepted, not yet done | n/a |
| `204` | No Content | Success, no body (e.g. DELETE) | n/a |
| `400` | Bad Request | Malformed syntax, unparseable body | No |
| `401` | Unauthorized | Missing/invalid authentication | No |
| `403` | Forbidden | Authenticated but not allowed | No |
| `404` | Not Found | Resource doesn't exist | No |
| `405` | Method Not Allowed | Wrong HTTP method | No |
| `409` | Conflict | State conflict / idempotency in-flight / version mismatch | Sometimes |
| `412` | Precondition Failed | `If-Match` ETag mismatch (optimistic concurrency) | After refetch |
| `415` | Unsupported Media Type | Wrong `Content-Type` | No |
| `422` | Unprocessable Entity | Syntactically valid but semantically invalid (validation, idempotency key reuse with different body) | No |
| `425` | Too Early | Replay risk; retry later | Yes |
| `428` | Precondition Required | Server requires `If-Match`/idempotency key | No (fix request) |
| `429` | Too Many Requests | Rate limited | Yes, with `Retry-After` |
| `500` | Internal Server Error | Unhandled server fault | If idempotent |
| `502` | Bad Gateway | Upstream returned garbage | Yes |
| `503` | Service Unavailable | Overloaded/maintenance | Yes, with `Retry-After` |
| `504` | Gateway Timeout | Upstream timed out | Yes (idempotent) |

> **Note on 400 vs 422:** Both are widely used for validation. The pragmatic convention: `400` = the request didn't even parse / structurally wrong; `422` = it parsed fine but failed business/semantic validation. Many teams use `400` for everything and add a machine code; that's also acceptable if consistent. RFC 9110 doesn't mandate the split.

### 4.2 RFC 9457 Problem Details members (recap as a toolkit table)

| Member | Required? | Default | Notes |
|---|---|---|---|
| `type` | No | `"about:blank"` | URI; stable per error category |
| `title` | No | derived from status if `type` is blank | Human, type-level |
| `status` | No | — | Mirror the HTTP status |
| `detail` | No | — | Human, instance-level |
| `instance` | No | — | URI for this occurrence |
| *extensions* | No | — | `code`, `errors[]`, `traceId`, `retryable`, `retryAfter`, domain fields |

### 4.3 Idempotency-related headers & conventions

| Header | Direction | Purpose |
|---|---|---|
| `Idempotency-Key` | Request | Client-supplied dedup key (UUID/random). De-facto standard. |
| `Idempotency-Replayed` / `Idempotent-Replayed` | Response | Some APIs set a flag to indicate the response was replayed from cache. Vendor-specific. |
| `Retry-After` | Response | Seconds (or HTTP-date) the client should wait before retrying. Used with `429`, `503`. Standard (RFC 9110). |
| `Location` | Response | URI of created resource on `201`. |
| `ETag` / `If-Match` / `If-None-Match` | Both | Optimistic concurrency control; conditional requests. |
| `X-Request-Id` / `X-Correlation-Id` | Both | Request/correlation tracing. Conventions, not standardized. |
| `traceparent` | Both | W3C Trace Context standard for distributed tracing. |

### 4.4 Spring (Java) error-handling toolkit

| Construct | Since | Purpose |
|---|---|---|
| `ProblemDetail` | Spring 6 / Boot 3 | First-class RFC 9457 body. `forStatus`, `setType`, `setDetail`, `setProperty(...)`. |
| `ErrorResponse` / `ErrorResponseException` | Spring 6 | Interface/exception carrying a `ProblemDetail`. |
| `@RestControllerAdvice` | Spring 4.3 | Global exception handling across all controllers; combines `@ControllerAdvice` + `@ResponseBody`. |
| `@ExceptionHandler` | Spring 3 | Method handling a specific exception type. |
| `ResponseEntityExceptionHandler` | — | Base class with handlers for Spring MVC's built-in exceptions; override `handleExceptionInternal` to inject your shape. |
| `@ResponseStatus` | Spring 3 | Annotate an exception class with its HTTP status. |
| `ResponseStatusException` | Spring 5 | Throw with a status + reason without a custom exception class. |
| `spring.mvc.problemdetails.enabled` | Boot 3 | Makes built-in MVC exceptions emit `application/problem+json`. |
| `MethodArgumentNotValidException` | — | Thrown when `@Valid` bean validation fails (`@NotNull`, `@Size`, etc.). |
| `HandlerExceptionResolver` | — | The SPI behind it all (rarely implemented directly). |

### 4.5 Storage backends for idempotency keys

| Backend | Atomic claim primitive | TTL support | Notes |
|---|---|---|---|
| PostgreSQL | `INSERT ... ON CONFLICT DO NOTHING` + unique index | Manual (cron/`pg_cron`) or `created_at` filter | Strong consistency; can share a transaction with the business write. |
| MySQL | `INSERT IGNORE` / `INSERT ... ON DUPLICATE KEY` + unique index | Manual / event scheduler | Same idea. |
| Redis | `SET key val NX EX <ttl>` (atomic set-if-not-exists with expiry) | Native (`EX`/`PX`) | Fast; but it's a cache — guard against eviction/loss; not for the source of truth on money unless persisted. |
| DynamoDB | `PutItem` with `ConditionExpression: attribute_not_exists(pk)` | Native (TTL attribute) | Serverless; conditional writes are atomic. |
| Same-DB resource table | Unique constraint on `(scope, idempotency_key)` *on the business table* | Inherits business retention | Strongest: existence of the row *is* completion proof. |

---

## 5. Code examples by use case

All Java examples target **Java 17+ / Spring Boot 3.x** unless noted. They're written to be adapted, with the non-obvious lines commented.

### 5.1 A uniform error handler with RFC 9457 Problem Details

First, a small domain error vocabulary and a base exception that carries a machine code and a retryable hint.

```java
// ErrorCode.java — the machine-readable code vocabulary. Stable strings clients branch on.
public enum ErrorCode {
    VALIDATION_FAILED,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    INSUFFICIENT_FUNDS,
    IDEMPOTENCY_KEY_REUSED,
    IDEMPOTENCY_IN_PROGRESS,
    RATE_LIMITED,
    UPSTREAM_UNAVAILABLE,
    INTERNAL_ERROR
}
```

```java
// ApiException.java — base class for all expected business errors.
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;       // which HTTP status to emit
    private final ErrorCode code;          // machine code for clients
    private final boolean retryable;       // explicit retry hint
    private final transient Object[] details; // optional structured extras

    public ApiException(HttpStatus status, ErrorCode code, boolean retryable,
                        String message, Object... details) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details;
    }
    public HttpStatus status()   { return status; }
    public ErrorCode code()      { return code; }
    public boolean retryable()   { return retryable; }
    public Object[] details()    { return details; }
}
```

A couple of concrete subclasses for ergonomics:

```java
public class NotFoundException extends ApiException {
    public NotFoundException(String what) {
        // 404 is never retryable with the same input
        super(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, false, what + " not found");
    }
}

public class InsufficientFundsException extends ApiException {
    private final long balanceMinor;   // domain-specific extension field
    public InsufficientFundsException(long balanceMinor) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.INSUFFICIENT_FUNDS, false,
              "Insufficient funds for this charge");
        this.balanceMinor = balanceMinor;
    }
    public long balanceMinor() { return balanceMinor; }
}
```

Now the global handler. **This is the heart of a consistent error model.**

```java
// GlobalExceptionHandler.java
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String CODE = "code";
    private static final String RETRYABLE = "retryable";
    private static final String TRACE_ID = "traceId";
    private static final String TIMESTAMP = "timestamp";

    // ---- 1. Our own business exceptions ----
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest req) {
        ProblemDetail pd = base(ex.status(), ex.getMessage(), ex.code(), ex.retryable(), req);
        // attach domain-specific extension fields
        if (ex instanceof InsufficientFundsException ife) {
            pd.setProperty("balance", ife.balanceMinor());
        }
        return pd;
    }

    // ---- 2. Bean-validation failures (@Valid on a @RequestBody) ----
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        // Turn each field error into a structured entry: clients can map field -> message
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(this::toFieldError)
            .toList();

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setDetail("One or more fields are invalid.");
        pd.setProperty(CODE, ErrorCode.VALIDATION_FAILED.name());
        pd.setProperty(RETRYABLE, false);
        pd.setProperty("errors", fieldErrors);          // RFC 9457 extension member
        decorate(pd, request);
        return ResponseEntity.badRequest().body(pd);
    }

    private Map<String, String> toFieldError(FieldError fe) {
        return Map.of("field", fe.getField(),
                      "message", Objects.requireNonNullElse(fe.getDefaultMessage(), "invalid"));
    }

    // ---- 3. The catch-all: never leak a stack trace to the client ----
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        // Log the FULL exception server-side with the correlation id; return a sanitized body.
        String traceId = currentTraceId();
        log.error("Unhandled exception traceId={}", traceId, ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal server error");
        pd.setDetail("An unexpected error occurred. Quote the traceId to support.");
        pd.setType(URI.create("https://errors.example.com/internal"));
        pd.setProperty(CODE, ErrorCode.INTERNAL_ERROR.name());
        pd.setProperty(RETRYABLE, true);   // a 500 on an idempotent op is retryable
        pd.setProperty(TRACE_ID, traceId);
        pd.setProperty(TIMESTAMP, Instant.now().toString());
        return pd;
    }

    // ---- helpers ----
    private ProblemDetail base(HttpStatus status, String detail, ErrorCode code,
                               boolean retryable, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(status.getReasonPhrase());
        pd.setDetail(detail);
        // Stable, dereferenceable type URI per code — links to your error catalog docs.
        pd.setType(URI.create("https://errors.example.com/" + code.name().toLowerCase()));
        pd.setProperty(CODE, code.name());
        pd.setProperty(RETRYABLE, retryable);
        pd.setProperty(TRACE_ID, currentTraceId());
        pd.setProperty(TIMESTAMP, Instant.now().toString());
        pd.setInstance(URI.create(req.getRequestURI()));
        return pd;
    }

    private void decorate(ProblemDetail pd, WebRequest request) {
        pd.setProperty(TRACE_ID, currentTraceId());
        pd.setProperty(TIMESTAMP, Instant.now().toString());
    }

    private String currentTraceId() {
        // With Micrometer Tracing / OpenTelemetry, pull the active trace id.
        // Fallback: read from MDC where a servlet filter put X-Request-Id.
        String t = org.slf4j.MDC.get("traceId");
        return t != null ? t : UUID.randomUUID().toString();
    }

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
}
```

A sample response body produced by case 2:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/v1/charges",
  "code": "VALIDATION_FAILED",
  "retryable": false,
  "traceId": "4f1c2a9b6d7e",
  "timestamp": "2026-06-25T10:15:30Z",
  "errors": [
    { "field": "amount", "message": "must be greater than 0" },
    { "field": "currency", "message": "must not be blank" }
  ]
}
```

Notice the design choices: **machine `code` + human `detail`**, a **`retryable` flag** so clients don't guess, a **`traceId`** for support, structured **`errors[]`** for field-level validation, and a **`type` URI** linking to documentation. The catch-all *never* leaks a stack trace.

### 5.2 An idempotent `POST /charges` endpoint (Postgres-backed)

The schema:

```sql
CREATE TABLE idempotency_keys (
    scope             TEXT        NOT NULL,         -- e.g. account id
    idempotency_key   TEXT        NOT NULL,
    request_hash      TEXT        NOT NULL,         -- fingerprint of method+path+body
    status            TEXT        NOT NULL,         -- 'IN_PROGRESS' | 'COMPLETED'
    response_status   INT,
    response_body     JSONB,
    resource_id       TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until      TIMESTAMPTZ,                  -- lease for crash recovery
    PRIMARY KEY (scope, idempotency_key)            -- the atomic-claim guarantee
);
-- TTL purge runs separately, e.g. nightly:
-- DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours';
```

A reusable idempotency service:

```java
// IdempotencyService.java
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final Duration LEASE = Duration.ofSeconds(60); // max time we allow IN_PROGRESS

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    /** Result wrapper distinguishing a fresh execution from a replay. */
    public record Outcome(int httpStatus, String body, boolean replayed) {}

    public Outcome execute(String scope, String key, String requestHash,
                           Supplier<ChargeResult> businessLogic) {
        // 1. Atomically try to CLAIM the key.
        int inserted = jdbc.update("""
            INSERT INTO idempotency_keys
                (scope, idempotency_key, request_hash, status, locked_until)
            VALUES (?, ?, ?, 'IN_PROGRESS', now() + (? || ' seconds')::interval)
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """, scope, key, requestHash, LEASE.toSeconds());

        if (inserted == 1) {
            // 1a. We won the claim — execute the business logic exactly once.
            return runAndStore(scope, key, businessLogic);
        }

        // 2. Key already existed — read its current state.
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT request_hash, status, response_status, response_body, locked_until
            FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?
            """, scope, key);

        // 3. Detect key reuse with a DIFFERENT body (client bug) -> 422.
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCode.IDEMPOTENCY_KEY_REUSED, false,
                "Idempotency-Key was already used with different request parameters");
        }

        String status = (String) row.get("status");
        if ("COMPLETED".equals(status)) {
            // 4. Replay the stored response verbatim.
            return new Outcome((Integer) row.get("response_status"),
                               row.get("response_body").toString(), true);
        }

        // 5. status == IN_PROGRESS. Check whether the lease expired (previous handler crashed).
        java.sql.Timestamp lockedUntil = (java.sql.Timestamp) row.get("locked_until");
        if (lockedUntil != null && lockedUntil.toInstant().isBefore(java.time.Instant.now())) {
            // Lease expired -> reclaim and re-run. (Safe because the prior attempt never completed.)
            jdbc.update("""
                UPDATE idempotency_keys SET locked_until = now() + (? || ' seconds')::interval
                WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
                """, LEASE.toSeconds(), scope, key);
            return runAndStore(scope, key, businessLogic);
        }

        // 6. A concurrent request is genuinely still processing -> tell client to retry.
        throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
            ErrorCode.IDEMPOTENCY_IN_PROGRESS, true,
            "A request with this Idempotency-Key is already being processed");
    }

    @Transactional // business write + idempotency record commit atomically (same DB)
    protected Outcome runAndStore(String scope, String key, Supplier<ChargeResult> logic) {
        try {
            ChargeResult result = logic.get();          // do the charge ONCE
            String json = mapper.writeValueAsString(result);
            jdbc.update("""
                UPDATE idempotency_keys
                   SET status='COMPLETED', response_status=?, response_body=?::jsonb,
                       resource_id=?, locked_until=NULL
                 WHERE scope=? AND idempotency_key=?
                """, 201, json, result.id(), scope, key);
            return new Outcome(201, json, false);
        } catch (ApiException deterministic) {
            // Deterministic failure (e.g. insufficient funds): store it so retries replay it.
            persistFailure(scope, key, deterministic);
            throw deterministic;
        } catch (Exception transientErr) {
            // Transient failure: RELEASE the key so the same key can be retried successfully.
            jdbc.update("DELETE FROM idempotency_keys WHERE scope=? AND idempotency_key=?",
                        scope, key);
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.UPSTREAM_UNAVAILABLE, true, "Temporarily unavailable, please retry");
        }
    }

    private void persistFailure(String scope, String key, ApiException ex) {
        try {
            String json = mapper.writeValueAsString(Map.of("code", ex.code().name(),
                                                           "message", ex.getMessage()));
            jdbc.update("""
                UPDATE idempotency_keys SET status='COMPLETED', response_status=?,
                       response_body=?::jsonb, locked_until=NULL
                 WHERE scope=? AND idempotency_key=?
                """, ex.status().value(), json, scope, key);
        } catch (Exception ignore) { /* best-effort */ }
    }

    public static String fingerprint(String method, String path, String body) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((method + " " + path + " " + body).getBytes());
            return HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

record ChargeResult(String id, long amount, String currency, String status) {}
```

The controller wiring:

```java
// ChargesController.java
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/v1/charges")
public class ChargesController {

    private final IdempotencyService idempotency;
    private final PaymentService payments;

    public ChargesController(IdempotencyService i, PaymentService p) {
        this.idempotency = i; this.payments = p;
    }

    public record ChargeRequest(
        @Positive long amount,                 // bean validation -> 400 if violated
        @NotBlank String currency,
        @NotBlank String source) {}

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AccountPrincipal account,
            @Valid @RequestBody ChargeRequest req,
            @org.springframework.web.bind.annotation.RequestBody(required=false) String rawBody) {

        // 1. For money-moving endpoints, REQUIRE the idempotency key (428 if missing).
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.valueOf(428),
                ErrorCode.VALIDATION_FAILED, false,
                "Idempotency-Key header is required for this endpoint");
        }

        String hash = IdempotencyService.fingerprint("POST", "/v1/charges", rawBody);

        IdempotencyService.Outcome out = idempotency.execute(
            account.id(), idempotencyKey, hash,
            () -> payments.charge(req.amount(), req.currency(), req.source()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (out.replayed()) headers.add("Idempotent-Replayed", "true"); // optional hint
        return ResponseEntity.status(out.httpStatus()).headers(headers).body(out.body());
    }
}
```

This single endpoint demonstrates: **required idempotency key (428)**, **request fingerprinting (422 on reuse)**, **atomic claim**, **exactly-once execution**, **response replay**, **lease-based crash recovery**, **deterministic-vs-transient failure handling**, and **bean validation feeding the uniform error handler**.

### 5.3 A client with safe retries: exponential backoff + jitter + idempotency key

```java
// SafeRetryingClient.java — how a *caller* should retry.
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class SafeRetryingClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_MILLIS = 100;
    private static final long CAP_MILLIS = 5_000;

    public HttpResponse<String> charge(String body) throws InterruptedException {
        // CRITICAL: generate ONE idempotency key for the whole logical operation,
        // and reuse it across ALL retries so the server dedups them.
        String idempotencyKey = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.example.com/v1/charges"))
                .header("Idempotency-Key", idempotencyKey)        // same key every attempt
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int s = resp.statusCode();
                if (s < 500 && s != 429 && s != 408) {
                    return resp;   // 2xx or a non-retryable 4xx — done either way
                }
                // Retryable status: honor Retry-After if present, else backoff.
                sleepBeforeRetry(attempt, resp.headers().firstValue("Retry-After").orElse(null));
            } catch (java.io.IOException networkError) {
                // Timeout / connection reset: AMBIGUOUS — safe to retry ONLY because we have
                // an idempotency key protecting against duplicate execution.
                if (attempt == MAX_ATTEMPTS - 1) throw new RuntimeException(networkError);
                sleepBeforeRetry(attempt, null);
            }
        }
        throw new RuntimeException("Exhausted retries");
    }

    private void sleepBeforeRetry(int attempt, String retryAfter) throws InterruptedException {
        long delay;
        if (retryAfter != null) {
            delay = Long.parseLong(retryAfter.trim()) * 1000L;     // server-dictated wait
        } else {
            long exp = Math.min(CAP_MILLIS, BASE_MILLIS * (1L << attempt)); // exponential
            delay = ThreadLocalRandom.current().nextLong(0, exp + 1);       // FULL JITTER
        }
        Thread.sleep(delay);
    }
}
```

The comment on the `IOException` branch is the whole point: **retrying after a timeout is only safe because of the idempotency key.** Without it, that retry could double-charge.

### 5.4 Partial failure: a bulk endpoint with per-item results

When a request operates on many items, an all-or-nothing status code can't express "7 succeeded, 3 failed." Use **`207 Multi-Status`** (originally from WebDAV but reused) or a `200` with a per-item result array.

```java
// BulkController.java
@RestController
@RequestMapping("/v1/notifications")
public class BulkController {

    public record SendItem(@NotBlank String to, @NotBlank String template) {}
    public record BulkRequest(@Size(min = 1, max = 1000) List<@Valid SendItem> items) {}

    public record ItemResult(int index, String status, String id,
                             String errorCode, String errorMessage) {}
    public record BulkResponse(int total, int succeeded, int failed, List<ItemResult> results) {}

    private final NotificationService svc;
    public BulkController(NotificationService svc) { this.svc = svc; }

    @PostMapping("/bulk")
    public ResponseEntity<BulkResponse> sendBulk(@Valid @RequestBody BulkRequest req) {
        List<ItemResult> results = new ArrayList<>();
        int ok = 0;
        for (int i = 0; i < req.items().size(); i++) {
            SendItem item = req.items().get(i);
            try {
                String id = svc.send(item.to(), item.template());   // may fail independently
                results.add(new ItemResult(i, "succeeded", id, null, null));
                ok++;
            } catch (ApiException e) {
                // Capture the per-item failure WITHOUT aborting the whole batch.
                results.add(new ItemResult(i, "failed", null, e.code().name(), e.getMessage()));
            }
        }
        int failed = req.items().size() - ok;
        BulkResponse body = new BulkResponse(req.items().size(), ok, failed, results);
        // 207 communicates "mixed outcomes; inspect each result".
        HttpStatus status = (failed == 0) ? HttpStatus.OK
                          : (ok == 0)     ? HttpStatus.UNPROCESSABLE_ENTITY
                                          : HttpStatus.MULTI_STATUS; // 207
        return ResponseEntity.status(status).body(body);
    }
}
```

Design rule for partial failure: **the top-level status reflects the *batch* outcome; each item carries its own result.** Clients must always inspect the body, not just the status code. Document precisely whether the batch is transactional (all-or-nothing) or best-effort (independent items) — this example is best-effort.

### 5.5 Optimistic concurrency with ETags (a different "conflict" tool)

Idempotency keys protect *creates*; for *updates* you often want **optimistic concurrency control** so two clients don't clobber each other. The tool is the `ETag`/`If-Match` pair.

```java
@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private final DocumentService docs;
    public DocumentController(DocumentService docs) { this.docs = docs; }

    @GetMapping("/{id}")
    public ResponseEntity<Document> get(@PathVariable String id) {
        Document d = docs.find(id).orElseThrow(() -> new NotFoundException("Document"));
        // The ETag encodes the version; clients echo it back on update.
        return ResponseEntity.ok().eTag("\"" + d.version() + "\"").body(d);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Document> update(
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody DocumentUpdate update) {

        if (ifMatch == null) {
            // 428 Precondition Required: force clients to send a version, preventing blind overwrites.
            throw new ApiException(HttpStatus.valueOf(428), ErrorCode.VALIDATION_FAILED, false,
                "If-Match header required to update");
        }
        long expectedVersion = Long.parseLong(ifMatch.replace("\"", ""));
        try {
            Document updated = docs.update(id, update, expectedVersion); // CAS on version
            return ResponseEntity.ok().eTag("\"" + updated.version() + "\"").body(updated);
        } catch (OptimisticLockException e) {
            // 412 Precondition Failed: someone else updated it; client must refetch and retry.
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, ErrorCode.CONFLICT, false,
                "Document was modified by another request; refetch and retry");
        }
    }
}
```

This is a worked example of distinguishing **idempotency (dedup of the *same* operation)** from **concurrency control (rejection of *conflicting* operations)** — both surface as "conflict-ish" but solve different problems.

### 5.6 Idempotency for a Kafka/message consumer (no HTTP)

Idempotency is not just HTTP. **At-least-once message delivery** means consumers see duplicates after a rebalance or redelivery. Dedup by a business key.

```java
@KafkaListener(topics = "payments.requested", groupId = "settlement")
public void onPaymentRequested(PaymentEvent event) {
    // The producer stamps a stable event id; we use it as the idempotency key.
    String dedupKey = event.eventId();
    try {
        // Atomic claim: unique constraint on processed_events(event_id).
        boolean firstTime = processedEvents.tryInsert(dedupKey);
        if (!firstTime) {
            log.info("Duplicate event {} ignored", dedupKey);
            return; // already processed — ack and move on
        }
        settlementService.settle(event); // exactly-once effect
    } catch (TransientException e) {
        processedEvents.remove(dedupKey); // release so redelivery can retry
        throw e;                          // nack -> Kafka redelivers
    }
}
```

Same pattern as the HTTP case: **at-least-once delivery + idempotent processing = effectively-once.**

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **The atomic claim adds one write per request.** On a hot path this can matter. Mitigations: index the `(scope, key)` PK tightly; use a fast store (Redis `SET NX EX` is ~sub-millisecond) as a *front* dedup with a durable store as backstop for money; batch TTL purges off the request path.
- **Replaying stored responses** avoids re-running expensive business logic — a *performance win* for retries, not just a correctness feature.
- **Don't store huge response bodies** verbatim if they're large; store a reference (resource id) and re-serialize, or cap stored body size.
- **Connection holding under IN_PROGRESS**: if you choose to *block-and-wait* on concurrent duplicates instead of returning 409, bound the wait or you'll exhaust the thread pool under a retry storm. Prefer fast 409 + client backoff.

### 6.2 Correctness & concurrency (the part that bites)

- **Never check-then-insert.** Always use a single atomic operation (unique constraint, `ON CONFLICT`, conditional put). This is the #1 source of double-charge bugs.
- **Co-locate the dedup record and the business write in one transaction** when they share a DB. If they can't share a transaction (different services/DBs), make the *business resource itself* carry the idempotency key under a unique constraint, so its existence is the completion proof. Avoid the "two-phase with no atomicity" trap where the charge commits but the idempotency record doesn't.
- **Handle the crash-mid-flight case** with a bounded IN_PROGRESS lease, not an indefinite lock.
- **Fingerprint the request** to catch key reuse with different params (return 422). Decide deliberately what's in the fingerprint (body always; method+path yes; volatile headers no).
- **Decide failure semantics explicitly**: transient failures release the key; deterministic failures store-and-replay. Document it.

### 6.3 Security

- **Never leak internal details in error bodies.** No stack traces, SQL, internal hostnames, or library versions to external clients. Log them server-side keyed by `traceId`; return a sanitized message. Stack traces in 500s are an information-disclosure vulnerability.
- **Scope idempotency keys per authenticated principal** so one tenant can't read another's cached responses by guessing/colliding keys.
- **Treat the idempotency key as untrusted input**: bound its length (e.g. ≤ 255 chars), validate charset, and don't use it unsanitized in logs (log injection) or as a filesystem path.
- **Error messages shouldn't enable enumeration**: returning `404` for "user not found" but `403` for "user exists but forbidden" can leak existence. For sensitive resources, return a uniform `404`.
- **Rate-limit error-producing endpoints** (esp. auth) to prevent brute force, and return `429` with `Retry-After`.

### 6.4 Observability

- **Always include a correlation/trace ID** in every error response and propagate it via `traceparent` (W3C Trace Context). Put it in MDC so every log line carries it.
- **Emit metrics by error `code` and status class**: `http_responses_total{status="4xx"|"5xx", code="..."}`. Track idempotency hit rate (`replayed` ratio), in-progress conflicts, and key-reuse rejections — spikes indicate client retry storms or bugs.
- **Distinguish your-fault from their-fault** in dashboards: 5xx rate is your SLO; 4xx rate is client behavior (a sudden 422 spike may be a client deploy bug).
- **Log at the right level**: expected business errors (404, 422) at `INFO`/`DEBUG`; unexpected 500s at `ERROR` with the full exception.

### 6.5 Cost

- Idempotency storage costs money (rows × TTL × write IOPS). 24h TTL on a high-RPS payment API is non-trivial; size it.
- Retries multiply load: a poorly-tuned client retry policy can 3–5× your traffic during a partial outage. Server-side **retry budgets** and **load shedding** (return 429/503 early when overloaded) protect you and are cheaper than scaling to absorb a retry storm.

### 6.6 Testing

- **Test the replay path**: call the endpoint twice with the same key; assert one side effect, identical responses.
- **Test the concurrent path**: fire two requests with the same key simultaneously (e.g. two threads / a `CountDownLatch`); assert exactly one executes, the other gets 409 or the replay.
- **Test key reuse with different body** → 422.
- **Test crash recovery**: simulate an IN_PROGRESS row past its lease; assert it's reclaimed.
- **Contract-test the error shape**: snapshot the Problem Details JSON so you don't accidentally change the wire contract clients depend on.
- **Fault injection**: use tools like Toxiproxy to drop the response after the server commits, and assert the client's retry-with-key is deduplicated.

### 6.7 Production hardening checklist

- [ ] Every endpoint returns RFC 9457 bodies, including unhandled 500s.
- [ ] Every response carries a correlation ID.
- [ ] Money/critical-write endpoints **require** an idempotency key (428 if absent).
- [ ] Atomic claim, not check-then-act.
- [ ] IN_PROGRESS lease + sweeper for crashed handlers.
- [ ] Request fingerprinting + 422 on reuse.
- [ ] Documented retryable classification + `Retry-After` on 429/503.
- [ ] No stack traces / internal details leaked externally.
- [ ] Idempotency keys scoped per principal, length-bounded.
- [ ] Metrics by code; alert on 5xx and replay/conflict spikes.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Do instead |
|---|---|---|
| `200 OK` with `{"error": ...}` in body | Breaks every HTTP-aware tool, proxy, cache, monitor | Use real status codes |
| Returning `500` for validation errors | Tells clients to retry a request that will always fail | Use 4xx for client faults |
| Stack trace in the response | Information disclosure | Log server-side, return traceId |
| Check-then-insert idempotency | TOCTOU double-charge | Atomic claim |
| Recycling idempotency keys across operations | Wrong replay / 422 storms | One fresh key per logical op |
| Blind client retries on POST without a key | Duplicate writes | Idempotency key + backoff |
| Inconsistent error shapes per endpoint | Clients can't write generic handling | One uniform handler |
| Permanent IN_PROGRESS lock | A crash bricks the key forever | Lease with expiry |
| No `Retry-After` on 429/503 | Thundering herd | Always hint backoff |
| Burning the key on transient failure | Client can never complete | Release on transient errors |

---

## 7. Advanced topics & deep internals

### 7.1 Where to put the dedup boundary

You can deduplicate at several layers; the choice has correctness implications:

- **API gateway / edge** (e.g. an Envoy/Kong plugin keyed on `Idempotency-Key`): simple, language-agnostic, but the gateway usually doesn't share a transaction with your DB — so it protects against duplicate *delivery* but cannot make the business write + dedup atomic. Good for read-through caching of responses; risky as the sole guarantee for money.
- **Application layer** (our §5.2 example): can share a transaction with the business write — strongest correctness.
- **Database via the business resource's own unique constraint**: e.g. `orders` has `UNIQUE(account_id, idempotency_key)`. The insert of the order *is* the claim; a duplicate fails the constraint and you fetch+return the existing order. This collapses dedup and business write into one atomic statement — the gold standard, no separate table needed.

### 7.2 Storing the response vs reconstructing it

Two schools:
- **Store-the-response**: persist the exact body+status at completion; replay verbatim. Pros: identical bytes, no recompute. Cons: storage cost; stale if you'd want fresh data (usually you don't for a create).
- **Store-the-resource-id-only**: on replay, re-fetch the resource and re-serialize. Pros: smaller storage, always current. Cons: the replay can differ from the original (resource mutated), surprising clients; extra DB read.

Stripe stores the response. For a *create*, store-the-response is the safest mental model: a retry of "create X" should yield the original "X created" answer, not the current state of X.

### 7.3 Idempotency vs at-least-once vs deduplication windows

Idempotency keys give you a **finite dedup window** (the TTL). After it, duplicates are no longer caught. This is fine because real retry windows are minutes-to-hours. But understand the bound: if a client retries *after* TTL with the same key, you'll double-execute. Mitigation for paranoid systems: make the *business* invariant itself idempotent (e.g. a `UNIQUE(order_external_ref)` that lives as long as the order), so even an expired key can't create a true duplicate.

### 7.4 Distributed transactions & the Saga pattern

When a write spans multiple services, you can't wrap them in one ACID transaction. The **Saga pattern** breaks the operation into a sequence of local transactions, each with a **compensating action** to undo it if a later step fails (e.g. "charge → reserve inventory → create shipment"; if shipment fails, run "refund charge" + "release inventory"). Sagas make **partial failure first-class**: the error model must express which steps succeeded and which compensations ran. Each step should be idempotent (steps get retried) and each compensation idempotent (compensations get retried). This is where idempotency and the error model meet at architectural scale.

> **MVCC / optimistic locking term:** Many DBs (Postgres) use **MVCC (Multi-Version Concurrency Control)** — readers see a snapshot, writers create new versions, avoiding read locks. Your version-based optimistic locking (§5.5) rides on this: a `WHERE version = ?` update either matches the current version (succeeds) or matches zero rows (conflict).

### 7.5 gRPC error model (when you're not on HTTP/JSON)

gRPC has its own status codes and a richer error model:
- **Status codes** are an enum (`OK`, `INVALID_ARGUMENT`, `NOT_FOUND`, `ALREADY_EXISTS`, `FAILED_PRECONDITION`, `ABORTED`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, …). `UNAVAILABLE` and `DEADLINE_EXCEEDED` are the canonical retryable ones; `ABORTED` and `RESOURCE_EXHAUSTED` are retryable with backoff.
- **Rich errors** via `google.rpc.Status` with `details` carrying typed messages (`ErrorInfo`, `RetryInfo` — which literally carries a retry delay, `QuotaFailure`, `BadRequest` with field violations). This is gRPC's analog to Problem Details.
- gRPC has **built-in retry config** (service config `retryPolicy` with `maxAttempts`, `initialBackoff`, `retryableStatusCodes`) — the framework does exponential backoff for you, which is why classifying errors as the right status code is so important there.

The same idempotency-key pattern applies: pass the key in gRPC metadata.

### 7.6 Lesser-known behaviors & edge cases

- **PATCH idempotency**: a JSON Merge Patch (`{"status": "active"}`) is idempotent; a JSON Patch with `add` to an array (`[{"op":"add","path":"/tags/-","value":"x"}]`) is **not** (appends each time). So PATCH is "depends on the patch."
- **DELETE returning 404 on the second call**: the *effect* is idempotent (resource is gone), but the *response* differs (204 then 404). Some APIs return 204 both times for a smoother idempotent feel. Document your choice.
- **`Retry-After` formats**: it can be either a number of seconds *or* an HTTP-date. Clients must parse both.
- **Caching of error responses**: by default many 4xx/5xx are not cacheable, but `404`, `405`, `410`, `414`, `501` are *heuristically cacheable* per HTTP. Set `Cache-Control: no-store` on error bodies you don't want cached by intermediaries.
- **Idempotency under content negotiation**: if the *same* logical request can be returned as JSON or XML, your fingerprint/replay must account for `Accept`, or store the response in a canonical form and re-negotiate.
- **Clock skew & TTL**: TTL purge based on `created_at` is fine, but lease-based crash recovery using wall-clock `locked_until` is vulnerable to clock skew across app servers. Prefer DB-server `now()` (single clock) over app-server time.

### 7.7 Tuning knobs

| Knob | Typical value | Effect of increasing |
|---|---|---|
| Idempotency TTL | 24h (Stripe) | More dedup coverage, more storage |
| IN_PROGRESS lease | 30–120s | Slower crash recovery, fewer false reclaims |
| Client max attempts | 3–5 | More resilience, more load amplification |
| Backoff base | 50–200ms | Slower recovery, gentler on server |
| Backoff cap | 5–30s | Bounds worst-case latency |
| Retry budget | 10–20% of traffic | More retries allowed, more amplification risk |
| Circuit breaker error threshold | ~50% over window | Opens sooner = protects dependency faster |

---

## 8. Tradeoffs & decision frameworks

### 8.1 Where to enforce idempotency

| Option | Atomic with business write? | Language-agnostic? | Complexity | Use when |
|---|---|---|---|---|
| API gateway plugin | No | Yes | Low | Read-heavy, non-critical writes; quick win |
| App-layer service + table | Yes (same DB) | No | Medium | Critical writes; default choice |
| Business resource unique constraint | Yes (single statement) | No | Low–Med | When the resource naturally carries a client ref; strongest |
| Redis `SET NX EX` | No (cache) | Yes | Low | High-RPS, can tolerate rare loss; or as a fast front |

**Rule:** for money and irreversible actions, dedup must be **atomic with the write** → app-layer table or resource unique constraint. Gateways/Redis are fine for non-critical dedup.

### 8.2 Error body format

| Format | Pros | Cons | Use when |
|---|---|---|---|
| RFC 9457 Problem Details | Standard, tool support, extensible | Slightly verbose | Default for new APIs |
| Custom JSON envelope | Full control | Reinvents the wheel; clients learn yours | Legacy consistency |
| gRPC `google.rpc.Status` | Typed, retry-aware | gRPC only | gRPC services |
| JSON:API errors | Standard within JSON:API ecosystem | Niche | If you already use JSON:API |

### 8.3 400 vs 422 for validation

- **Use 400** if you want simplicity and don't care about the parse-vs-semantics distinction.
- **Use 422** for semantic validation when the body parsed fine, reserving 400 for malformed/unparseable input.
- **Either is acceptable**; consistency across your API matters more than the choice. Always add a machine `code`.

### 8.4 Idempotency keys vs ETags vs natural keys

| Tool | Protects | Mechanism | Use when |
|---|---|---|---|
| Idempotency key | Duplicate **creates** from retries | Client token + dedup store | POST that mutates / charges |
| ETag + If-Match | Conflicting **updates** (lost-update) | Version compare-and-set | PUT/PATCH on shared resources |
| Natural/business unique key | Logical duplicates regardless of retry | DB unique constraint | When a real-world key exists (order ref, invoice no.) |

These compose: a `POST /orders` can use an idempotency key for retry-dedup *and* a business unique constraint on `order_ref` for logical-dedup.

### 8.5 Decision rules ("use when / avoid when")

**Idempotency keys — use when:** the endpoint mutates state, the client may retry (always, over a network), and duplicates are harmful (charges, orders, emails, provisioning). **Avoid when:** the method is already idempotent (`PUT`/`DELETE` to a known URI) — though a key still helps for response replay; or the operation is naturally deduped by a business unique key that's cheaper to rely on.

**Required (428) vs optional idempotency key — require when:** the action is irreversible/financial. **Make optional when:** duplicates are merely annoying, to ease client onboarding.

**Retry on the client — do when:** error is classified retryable AND (method is idempotent OR you sent an idempotency key). **Avoid when:** error is 4xx non-retryable, or it's a non-idempotent write without a key.

---

## 9. Failure modes & debugging

### 9.1 The classic: the double charge

**Symptom:** customer charged twice; support ticket; one `POST /charges` but two ledger entries.
**Root causes (in order of likelihood):**
1. No idempotency key at all → client retried after timeout → two charges.
2. Key present but **check-then-insert** race → two concurrent claims both proceeded.
3. Key generated *per attempt* instead of *per operation* on the client → each retry had a different key → no dedup.
4. Dedup record and charge in **separate transactions** → charge committed, dedup record rollback'd → retry re-charged.

**Diagnose:**
- Pull both ledger entries' `traceId`s and `Idempotency-Key`s from logs. Same key? → server bug. Different keys? → client bug (#3).
- Check the idempotency table for the key: one row or two? Two rows with same key → constraint missing or wrong scope.
- `EXPLAIN` the claim query; confirm it's an atomic `ON CONFLICT`, not two statements.
- Inspect transaction boundaries: is `runAndStore` actually `@Transactional` and is the charge inside it?

### 9.2 Retry storm / thundering herd

**Symptom:** a dependency hiccups; your service's outbound traffic to it triples; the dependency stays down because it can't drain the retry backlog; cascading failure.
**Diagnose:** request rate to the dependency spikes far above baseline; ratio of retries to first-attempts (track a `is_retry` tag) jumps; latency climbs while throughput stays flat.
**Fix:** exponential backoff **with jitter** (not fixed delay), a **retry budget** capping retries to ~10% of traffic, **circuit breaker** to stop calling a dead dependency, and server-side **load shedding** (return 503/429 early). Honor `Retry-After`.

### 9.3 Key stuck IN_PROGRESS

**Symptom:** a client keeps getting `409 IDEMPOTENCY_IN_PROGRESS` and can never complete.
**Cause:** the original handler crashed after claiming the key but before completing, and there's no lease/sweeper, so the row is `IN_PROGRESS` forever.
**Diagnose:** query `SELECT * FROM idempotency_keys WHERE status='IN_PROGRESS' AND locked_until < now()`. If rows exist, recovery isn't running.
**Fix:** implement the lease + reclaim (§5.2 step 5) or a background sweeper that resets/deletes expired IN_PROGRESS rows.

### 9.4 Clients ignoring the body and branching only on status

**Symptom:** clients treat all 200s as full success and miss the per-item failures in a `207`/`200` bulk response; or treat all 4xx the same and don't read the `code`.
**Diagnose:** support cases where "the API said success but nothing happened" for the failed items.
**Fix:** documentation + a top-level status that *forces* inspection (use `207`, not `200`, on mixed outcomes), and a clear `failed > 0` field. Provide client SDKs that surface per-item results.

### 9.5 Information disclosure via errors

**Symptom:** a security scan flags stack traces / SQL / framework versions in 500 responses; or an attacker enumerates valid usernames via differing 403/404.
**Diagnose:** trigger a 500 (e.g. malformed input that hits an unhandled path) and inspect the body; check whether the catch-all handler is actually registered (a misconfigured `@RestControllerAdvice` may not be component-scanned).
**Fix:** ensure the `@ExceptionHandler(Exception.class)` catch-all is in scope and returns a sanitized Problem Details with only a `traceId`; uniform 404 for sensitive existence checks; set `server.error.include-stacktrace=never` and `include-message=never` in Spring Boot.

### 9.6 The tools you'll actually use to debug

- **Logs**: grep by `traceId`/`Idempotency-Key`; structured JSON logs make this queryable (e.g. in Elastic/Loki/Datadog).
- **Distributed tracing** (Jaeger/Tempo/Datadog APM via OpenTelemetry): see the full fan-out of one request and where the error originated.
- **Metrics** (Prometheus/Grafana): `rate(http_responses_total{status=~"5.."}[5m])`, replay ratio, retry ratio.
- **DB**: `SELECT` the idempotency table; `EXPLAIN ANALYZE` the claim; check for missing unique indexes.
- **`curl -i`**: inspect status + headers (`Retry-After`, `Location`, `Idempotent-Replayed`) directly.
- **Fault injection** (Toxiproxy, chaos tooling): drop the response after commit to verify dedup-on-retry.

### 9.7 A real-world flavor

A frequently-cited class of incident: a payments integration where the client library generated a **new idempotency key on each retry attempt** (it created the key inside the retry loop instead of outside it). Under a brief upstream timeout, every retry looked like a brand-new charge to the provider, producing a burst of duplicate charges before anyone noticed. The fix is exactly §5.3's structure: **generate the key once, outside the loop.** The broader lesson: idempotency is a *contract*, and the client side (key generation discipline) is half of it.

---

## 10. Interview drill

**Q1. What does it mean for an HTTP method to be idempotent, and which methods are?**
*Model answer:* Idempotent means repeating the request has the same effect on server state as making it once. Safe methods (`GET`, `HEAD`, `OPTIONS`) are idempotent, plus `PUT` and `DELETE`. `POST` and `PATCH` are not guaranteed idempotent. It's a semantic promise the spec asks you to keep, not something HTTP enforces.
- *Probe: Is idempotent the same as safe?* No — safe means read-only (no state change). All safe methods are idempotent, but `PUT`/`DELETE` change state yet are still idempotent.
- *Probe: Why isn't POST idempotent?* `POST` means "create/process here"; the server assigns the URI, so each call yields a new resource. `PUT` targets a known URI and replaces it, so repeats are no-ops on state.
- *Probe: Is DELETE truly idempotent if the second call returns 404?* The *effect* is idempotent (resource gone both times); only the *response* differs. Some APIs return 204 both times for smoothness.

**Q2. Walk me through implementing an idempotency key for a POST that charges a card.**
*Model answer:* Client generates a unique key per logical operation, sends it in `Idempotency-Key`. Server atomically claims the key (unique constraint + `INSERT ON CONFLICT`), marks IN_PROGRESS, runs the charge once in the same transaction as the dedup-record update, stores the response, marks COMPLETED. Repeats with the same key replay the stored response; same key + different body → 422; concurrent in-flight → 409; crashed handler recovered via an IN_PROGRESS lease. TTL ~24h.
- *Probe: Why atomic claim and not SELECT-then-INSERT?* TOCTOU race: two concurrent requests both see "no row" and both charge. The atomic insert serializes the claim.
- *Probe: What if the business write and dedup record are in different databases?* You lose atomicity; put the idempotency key on the business resource under a unique constraint so the resource's existence is the completion proof.
- *Probe: What happens on a transient failure mid-charge?* Release the key so the same key can be retried and complete; don't permanently burn it. Deterministic failures you store and replay.

**Q3. How does a client decide whether to retry, and how does it retry safely?**
*Model answer:* Retry on retryable errors (5xx, 429, 408, network timeouts) but not on deterministic 4xx (400/401/403/404/422). Retry safely only if the method is idempotent or an idempotency key was sent. Use exponential backoff with full jitter, honor `Retry-After`, cap attempts, and use a retry budget/circuit breaker to avoid storms.
- *Probe: Why jitter?* Without it, all clients retry in sync and re-overload the server (thundering herd). Jitter de-synchronizes them.
- *Probe: A request times out with no response — retry?* Ambiguous (request or response lost). Only retry if you have an idempotency key protecting against duplicate execution.

**Q4. Design a consistent error model for a public API.**
*Model answer:* Use RFC 9457 Problem Details (`application/problem+json`) with `type`, `title`, `status`, `detail`, `instance`, plus extensions: a stable machine `code`, a `retryable` flag, a `traceId`, and structured `errors[]` for field validation. One global handler ensures every path — including unhandled 500s — emits this shape, never leaking stack traces. Document a code catalog with stable strings clients branch on.
- *Probe: Machine vs human messages — why both?* Machines branch on `code` (stable, never localized); humans read `detail`/`title` (can change/localize). Coupling them (parsing human text) is fragile.
- *Probe: Where does the correlation ID come from?* Generated at the edge or via OpenTelemetry/W3C Trace Context (`traceparent`), put in MDC, echoed in every response and log line.

**Q5 (senior signal). When would you NOT use idempotency keys, and what would you use instead?**
*Model answer:* For already-idempotent operations (`PUT`/`DELETE` to a known URI) keys are optional (still useful for response replay). When a natural business unique key exists (order reference, invoice number), a DB unique constraint dedups logically *and* survives the idempotency TTL, so I'd rely on it — possibly *in addition* to a key for response replay. For pure reads, never. The decision hinges on whether duplicates are harmful and whether a cheaper natural dedup already exists.
- *Probe: Tradeoff of relying on the business key vs the idempotency table?* Business key dedups forever and needs no extra table, but can't replay the *original response* and may surface as a constraint-violation you must translate into a friendly "already exists, here's the resource". The table gives clean response replay but has a finite TTL.

**Q6 (senior signal). You're seeing duplicate charges in production. How do you diagnose and what's the most likely root cause?**
*Model answer:* Pull both charges' `Idempotency-Key` and `traceId`. Same key → server-side dedup bug (check for check-then-insert race, missing unique constraint, wrong key scope, or non-atomic transaction boundaries). Different keys → client bug, most commonly generating the key *inside* the retry loop so each retry looks new. I'd check the idempotency table for one-vs-two rows, verify the claim is atomic, and confirm the charge and dedup record commit in one transaction. Most likely root cause for "different keys": per-attempt key generation on the client.
- *Probe: How do you prevent the per-attempt-key bug systematically?* Provide an SDK that owns key generation outside the retry loop; document the contract; add a server metric for "same body, many distinct keys, same principal, short window" as a detector.

**Q7 (senior signal). Where in the stack should idempotency live — gateway, app, or DB — and why?**
*Model answer:* It depends on criticality. For money/irreversible writes it must be **atomic with the business write**, which only the app layer (sharing a DB transaction) or the DB itself (business-resource unique constraint) can guarantee. A gateway plugin is language-agnostic and easy but can't share a transaction, so it protects against duplicate delivery, not against the charge-committed-but-record-lost case. Redis `SET NX EX` is fast but is a cache and can lose data, so it's a front-line optimization, not the source of truth for money. I default to the app/DB layer for critical writes and reserve gateway/Redis for non-critical dedup.
- *Probe: Give the single strongest pattern.* Put the idempotency key on the business resource under a unique constraint — one atomic insert is both the claim and the write; existence is completion proof; no separate table or transaction coordination.

**Q8. Explain "at-least-once + idempotent = exactly-once." Is true exactly-once delivery possible?**
*Model answer:* True exactly-once *delivery* is impossible over an unreliable network — you can always lose the final acknowledgment (Two Generals). What's achievable is exactly-once *processing*: the sender retries until acknowledged (at-least-once, nothing lost) and the receiver deduplicates (idempotency, nothing duplicated), yielding effectively-once. Kafka's EOS, idempotent consumers, and idempotency keys all implement this.
- *Probe: How does a Kafka consumer dedup?* By a stable event id used as an idempotency/dedup key with an atomic claim, releasing it on transient failure so redelivery can retry.

**Q9. How do you communicate partial failure in a bulk endpoint?**
*Model answer:* Don't collapse it into one code. Return `207 Multi-Status` (or `200` with a results array) where the top-level status reflects the batch and each item carries its own status/code/message. Document whether the batch is transactional (all-or-nothing) or best-effort. Force clients to inspect the body, not just the status.
- *Probe: When all items fail?* Return a 4xx (e.g. 422) so it doesn't look like success; when mixed, 207; when all succeed, 200.

**Q10. What status codes are retryable and how should the server help the client decide?**
*Model answer:* Retryable: 408, 425, 429, 500 (for idempotent ops), 502, 503, 504, and network errors. Non-retryable: 400, 401, 403, 404, 422, 501. But rather than make clients guess, the server should send an explicit `retryable` flag in the Problem Details and `Retry-After` on 429/503. 409 is "it depends" — retryable after refetching state.
- *Probe: Why surface `retryable` explicitly when status codes already imply it?* Because the same code (e.g. 500 or 409) can be retryable or not depending on context; an explicit flag removes ambiguity and lets the server change behavior without clients re-learning heuristics.

**Q11. What is optimistic concurrency control and how is it different from idempotency?**
*Model answer:* Optimistic concurrency (via ETag/`If-Match` and a version compare-and-set) rejects *conflicting concurrent updates* to the same resource — it prevents lost updates by returning 412 when the client's version is stale. Idempotency dedups *the same operation* retried. One stops different writers from clobbering each other; the other stops the same write from happening twice. They compose: a create can use an idempotency key, an update can use ETags.
- *Probe: Which status for a stale ETag?* `412 Precondition Failed`; `428 Precondition Required` if you mandate `If-Match` and the client omitted it.

**Q12. How do you keep error responses from becoming a security liability?**
*Model answer:* Never leak stack traces, SQL, internal hostnames, or versions externally — log them server-side keyed by `traceId`, return a sanitized message. Avoid enumeration (uniform 404 for sensitive existence checks). Bound and sanitize the idempotency key (untrusted input; log-injection risk). Rate-limit error-heavy endpoints (auth) and return 429. In Spring Boot, set `server.error.include-stacktrace=never`.
- *Probe: How can a 403-vs-404 difference leak data?* It reveals whether a resource exists; an attacker can enumerate valid IDs/usernames. Return a uniform 404 for sensitive resources.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; the guarantees of a classic database transaction. "Atomic" = all-or-nothing.
- **At-least-once / at-most-once / exactly-once** — delivery/processing semantics describing whether an operation can be lost, duplicated, or neither.
- **Backoff (exponential)** — increasing the wait between retries geometrically (`base·2^n`) to relieve a struggling server.
- **Circuit breaker** — a state machine (Closed/Open/Half-Open) that stops calling a failing dependency for a cooldown to prevent hammering it.
- **Compensating action** — an operation that undoes a previously committed step in a saga (e.g. a refund undoes a charge).
- **Correlation ID / trace ID** — an identifier tying together all work caused by one logical operation across services.
- **CAS (compare-and-set)** — update only if the current value matches an expected one; the basis of optimistic locking.
- **ETag** — an opaque token identifying a specific version of a resource, used with `If-Match`/`If-None-Match` for conditional requests.
- **Effectively-once** — the practical result of at-least-once delivery plus idempotent processing.
- **Fingerprint (request)** — a hash of the request (method+path+body) used to detect idempotency-key reuse with different parameters.
- **Idempotent** — repeating an operation has the same effect as doing it once.
- **Idempotency key** — a client-supplied unique token enabling the server to deduplicate retried write requests.
- **Jitter** — randomness added to retry delays so clients de-synchronize and avoid a thundering herd.
- **Lease (lock with expiry)** — a time-bounded claim on a resource so a crashed holder doesn't lock it forever.
- **Load shedding** — deliberately rejecting some requests (429/503) when overloaded to protect the system.
- **MDC (Mapped Diagnostic Context)** — a per-thread key/value map in logging frameworks (SLF4J/Logback) used to attach context like `traceId` to every log line.
- **MVCC (Multi-Version Concurrency Control)** — a DB technique where readers see snapshots and writers create new versions, avoiding read locks.
- **OpenTelemetry** — an open standard and toolkit for emitting traces, metrics, and logs for observability.
- **Optimistic concurrency control** — allowing concurrent reads, detecting conflicts at write time via version/ETag instead of locking.
- **Partial failure** — when some parts of a multi-item or multi-step operation succeed and others fail.
- **Problem Details (RFC 7807 / 9457)** — the standard `application/problem+json` format for machine-readable HTTP error bodies.
- **Retry budget** — a cap on the fraction of traffic allowed to be retries, limiting amplification.
- **`Retry-After`** — an HTTP response header telling the client how long to wait before retrying (seconds or HTTP-date).
- **RFC 9110** — the current HTTP Semantics specification (defines methods, status codes, safe/idempotent properties).
- **Saga** — a pattern for long-running, multi-service transactions as a sequence of local transactions with compensations.
- **Safe (HTTP method)** — read-only; does not change server state.
- **Status code** — the 3-digit HTTP result code; 2xx success, 3xx redirect, 4xx client error, 5xx server error.
- **Thundering herd / retry storm** — many clients retrying simultaneously and overwhelming a recovering server.
- **TOCTOU (Time-Of-Check-To-Time-Of-Use)** — a race between checking a condition and acting on it; the classic double-execute bug.
- **TTL (Time-To-Live)** — how long a record (e.g. an idempotency key) is retained before expiry.
- **Two Generals Problem** — the impossibility of guaranteed agreement over an unreliable channel; the theoretical root of why exactly-once delivery can't exist.
- **W3C Trace Context** — the standard (`traceparent` header) for propagating trace IDs across service hops.

---

## 12. Cheat-sheet & self-test

### Dense recap (one screen)

**Method properties:** Safe = `GET/HEAD/OPTIONS`. Idempotent = safe + `PUT/DELETE`. NOT idempotent = `POST`, `PATCH` (depends).

**Retryable:** 408, 425, 429, 500(idempotent), 502, 503, 504, network timeouts. **Non-retryable:** 400, 401, 403, 404, 422, 501. **409 = depends.** Server should send explicit `retryable` + `Retry-After`.

**Error body = RFC 9457** `application/problem+json`: `type, title, status, detail, instance` + extensions `code` (machine, stable), `retryable`, `traceId`, `errors[]`. Never leak stack traces.

**Idempotency recipe:** client sends `Idempotency-Key` (one per *logical op*, reused across retries). Server: (1) **atomic claim** (unique constraint / `ON CONFLICT` / conditional put) — never check-then-insert; (2) IN_PROGRESS + **lease** for crash recovery; (3) run once, ideally in the **same transaction** as the dedup record; (4) **replay** stored response on repeat; (5) **422** on same-key/different-body; (6) **409** on concurrent in-flight; (7) release key on **transient** failure, store+replay on **deterministic**. TTL ~24h (Stripe). Scope per principal.

**Numbers:** Stripe TTL = 24h. Full jitter: `sleep = rand(0, base·2^n)`. Retry budget ~10%. Lease ~30–120s. 207 for partial failure.

**Equation:** at-least-once delivery + idempotent processing = **effectively-once**. True exactly-once *delivery* is impossible (Two Generals).

**Decision quick-rules:** Money/irreversible write → require idempotency key (428 if absent) + atomic-with-write dedup. Concurrent updates → ETag/`If-Match` → 412/428. Natural business key exists → DB unique constraint (survives TTL). Client retry → only if retryable AND (idempotent OR key sent), with backoff+jitter.

### Self-test (no answers — active recall)

1. A client `POST`s an order, the network drops the response, and it retries with a *new* idempotency key each time. What happens, why, and exactly where is the bug?
2. Design the database schema and the single SQL statement that makes the idempotency claim atomic. Why does `SELECT` then `INSERT` fail here?
3. Your handler crashes after claiming an idempotency key but before completing the charge. Trace what every subsequent retry sees, and describe the mechanism that lets the operation eventually complete.
4. Construct a full RFC 9457 Problem Details body for a validation failure on two fields, including the extension members you'd add and why each exists.
5. A dependency briefly returns 503 and your service's traffic to it triples and stays elevated. Name the phenomenon, the three client-side mechanisms and one server-side mechanism that prevent it, and how you'd confirm the diagnosis from metrics.
6. Compare enforcing idempotency at the API gateway vs the application layer vs a business-resource unique constraint, specifically regarding atomicity with the business write. Which would you pick for a payment and why?
7. Explain why true exactly-once delivery is impossible but exactly-once processing is achievable, and map the parts of an idempotent Kafka consumer onto that equation.
8. When do you return 400 vs 422 vs 409 vs 412 vs 428? Give a concrete scenario for each.

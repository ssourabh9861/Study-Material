# Webhooks

> A definitive engineering-handbook chapter on **Webhooks** — server→client callbacks, delivery guarantees, idempotency, signing/verification, failure handling, payload design, subscription management, testing, and building both producer and consumer sides. Java/JVM-flavored, but the patterns are language-agnostic.

---

## 1. Overview & where it fits

### What a webhook is

A **webhook** is an HTTP callback that a server (the *producer* or *provider*) sends to a URL you control (the *consumer* or *receiver*) when something interesting happens. Instead of you repeatedly asking "did anything change yet?", the producer *tells you* the moment it does. The name is a portmanteau of *web* + *hook*: you register a hook (a URL) into someone else's system, and they "call back" into it.

Concretely: you give Stripe (a payment processor) a URL like `https://api.acme.com/webhooks/stripe`. When a customer's payment succeeds, Stripe makes an HTTP `POST` to that URL with a JSON body describing the event (`payment_intent.succeeded`). Your server receives it, verifies it's genuinely from Stripe, and reacts (mark the order paid, send a receipt).

### The problem it solves: polling is wasteful

The alternative to webhooks is **polling** — your client repeatedly hits the producer's API ("any new payments? any new payments? any new payments?"). Polling has three structural problems:

1. **Latency vs. cost tradeoff.** Poll every 10 minutes and you learn about events up to 10 minutes late. Poll every second and you make 86,400 requests/day/resource, almost all returning "nothing new."
2. **Wasted compute and bandwidth** on both sides — the vast majority of polls are empty.
3. **Scaling pain.** With N consumers each polling, the producer absorbs N × frequency requests regardless of whether anything happened.

Webhooks invert this: **the work happens only when there's actually something to report**, and the consumer learns about it within (typically) seconds. This is why webhooks are often called **"reverse APIs"** — in a normal API the client calls the server; in a webhook the server calls the client. The roles of caller and callee are flipped.

> **Adjacent term — API (Application Programming Interface):** a contract that lets one program call another over a defined interface. A *REST API* over HTTP is the most common kind here: the client sends an HTTP request (GET/POST/...) to a URL and gets a response. Keep this picture in mind — a webhook is *the same HTTP machinery* but with the direction of the initial call reversed.

### Where webhooks sit among "push" technologies

Webhooks are one point in a family of **push** mechanisms (server tells client, rather than client asking). Quick mental map:

| Mechanism | Transport | Who hosts the endpoint | Typical use | Realtime? |
|---|---|---|---|---|
| **Polling** | HTTP (client→server) | Producer | Anything, simplest | No (interval-bound) |
| **Long polling** | HTTP held open | Producer | Pre-WebSocket realtime | Near |
| **Webhooks** | HTTP (server→client) | **Consumer** | Server-to-server events between *organizations* | Near (seconds) |
| **WebSockets** | TCP, bidirectional | Producer | Browser/app live updates | Yes |
| **Server-Sent Events (SSE)** | HTTP stream | Producer | Browser one-way streams | Yes |
| **Message queue / pub-sub** | AMQP/Kafka/SQS | Broker | *Internal* event-driven systems | Yes |

The defining trait of webhooks: **the consumer runs a publicly reachable HTTP endpoint, and the producer is an HTTP client to it.** This makes webhooks the standard way *different companies* integrate event-driven across the public internet (Stripe→you, GitHub→your CI, Slack→your bot, Twilio→your app). Inside a single system you'd usually reach for Kafka or SQS instead (see §8).

> **Adjacent term — pub/sub (publish/subscribe):** a messaging pattern where publishers emit events to a topic and subscribers receive them, decoupled by a broker. Webhooks are a pub/sub-like pattern where "delivery" is an HTTP POST and there is no shared broker — the producer is the broker.

### When you reach for webhooks

- You're a **SaaS provider** and customers need to react to events in your system (payments, deploys, document signed, message received).
- You're **integrating two backends across the internet** and want low-latency, event-driven flow without the consumer polling.
- The events are **relatively low-to-moderate volume** per consumer and **occasional bursts** are acceptable (webhooks degrade poorly under sustained millions/sec to a single endpoint).

### When you do NOT

- Browser/mobile clients (they can't host a public endpoint) → use WebSockets/SSE/push notifications.
- High-throughput internal event streaming → use Kafka/Pulsar/SQS/SNS.
- You need strict global ordering and exactly-once → webhooks give you neither for free (see §3, §6).

### One-paragraph mental model

> A webhook is an **at-least-once, best-effort, HTTP-POST notification** from a producer to a consumer-hosted URL, triggered by an event. The producer **retries on failure with backoff**, so the consumer must be **idempotent** (handle duplicates safely) and must **verify authenticity** (usually an HMAC signature over the raw body plus a timestamp to stop replays). Ordering is **not guaranteed**; payloads are either **thin** (just an ID — go fetch the rest) or **fat** (full state inline). Everything hard about webhooks reduces to those four words: *duplicates, ordering, authenticity, failure*.

---

## 2. Foundations from first principles

We'll build the whole picture from zero. If you already know HTTP cold, skim — but the precise definitions matter later.

### 2.1 The HTTP request the producer sends

A webhook delivery is *just an HTTP request*. A representative one from a provider looks like:

```http
POST /webhooks/stripe HTTP/1.1
Host: api.acme.com
Content-Type: application/json
Content-Length: 412
User-Agent: Stripe/1.0 (+https://stripe.com/docs/webhooks)
Stripe-Signature: t=1718900000,v1=5257a869e7ec...c4d2,v0=...
Idempotency-Key: evt_1P9aXyZ...

{
  "id": "evt_1P9aXyZabc",
  "type": "payment_intent.succeeded",
  "created": 1718900000,
  "data": { "object": { "id": "pi_3P...", "amount": 4200, "currency": "usd" } }
}
```

Anatomy:

- **Method** is almost always `POST` (you're creating/notifying, not reading). A few providers use `PUT`. Never rely on `GET` for webhooks — GETs shouldn't carry bodies and should be safe/idempotent by HTTP semantics.
- **URL/path** is *your* endpoint, which you registered with the producer.
- **Headers** carry metadata: content type, a **signature header** for verification, sometimes an **event ID** / **idempotency key**, sometimes the **event type** and a **delivery attempt counter**.
- **Body** is the event payload — typically JSON, sometimes form-encoded (older systems, e.g. some Twilio/GitHub form payloads).

> **Adjacent term — HTTP method (verb):** the action word of a request. `GET` reads (should be safe + idempotent), `POST` submits/creates (not idempotent by default), `PUT` replaces (idempotent), `DELETE` removes (idempotent). "Safe" = no side effects; "idempotent" = doing it twice equals doing it once.

> **Adjacent term — HTTP status code:** the 3-digit result the consumer returns. `2xx` = success, `3xx` = redirect, `4xx` = client error (the request is bad — *the producer usually should NOT retry these*), `5xx` = server error (transient — *retry*). This 4xx/5xx distinction is load-bearing for webhook retries (see §3).

### 2.2 Synchronous vs. asynchronous, and why webhooks force async thinking

A normal API call is **synchronous**: you call, you wait, you get the answer in the same exchange. A webhook is the producer being **asynchronous** about *its* work — it does the work (charge the card), then *later and separately* notifies you. From the consumer's side, the POST is synchronous (the producer waits for your 2xx), but you should treat the *processing* as asynchronous: **acknowledge fast, process later** (see §6).

> **Adjacent term — synchronous vs asynchronous:** synchronous = caller blocks until the work is done and gets the result inline. Asynchronous = the work is decoupled in time; you get an acknowledgement now and the result/effect later. Webhooks are the *asynchronous notification* leg of an otherwise synchronous API.

### 2.3 Delivery semantics: at-least-once, at-most-once, exactly-once

This is the single most important foundation. "Delivery guarantee" describes how many times a message can arrive:

- **At-most-once:** each event is delivered **0 or 1** times. No retries. Simple, but you can silently lose events. (Fire-and-forget.)
- **At-least-once:** each event is delivered **1 or more** times. The producer **retries** until it sees success, so on a flaky network it may deliver the *same* event multiple times. **This is what essentially every real webhook system gives you.**
- **Exactly-once:** each event has *effect* exactly once. True exactly-once *delivery* over an unreliable network is impossible (the classic Two Generals result); what people mean is **exactly-once processing** = at-least-once delivery **+ idempotent consumer**. You build the "exactly-once" feeling yourself, on the consumer side.

> **Why exactly-once delivery is impossible (intuition):** the producer sends, the consumer processes, the consumer's "I got it" ACK is lost on the way back. The producer can't tell "consumer never got it" from "consumer got it but ACK was lost." It must choose: resend (→ possible duplicate, at-least-once) or not (→ possible loss, at-most-once). There is no third option over an unreliable channel. So everyone picks at-least-once + idempotency.

> **Adjacent term — ACK (acknowledgement):** the consumer's signal "I have safely received/recorded this." For webhooks the ACK *is* the HTTP `2xx` response. If the producer doesn't get a 2xx (timeout, 5xx, connection reset), it assumes failure and retries.

### 2.4 Idempotency (the consumer's load-bearing property)

**Idempotent** = applying an operation multiple times has the same effect as applying it once. Because webhooks are at-least-once, **your handler will receive duplicates** — from retries, from network hiccups, occasionally from producer bugs. If "payment succeeded" runs twice and you ship two orders or send two emails, that's a real-money bug.

The fix: every event has a stable **event ID** (e.g. `evt_1P9aXyZabc`). Before processing, record/check that ID; if you've seen it, no-op. We make this concrete in §3.4 and §5.

> **Adjacent term — deduplication (dedup):** detecting and dropping repeated copies of the same logical message, usually by a unique key. Idempotent webhook handling is dedup by event ID plus making the side effect itself safe to repeat.

### 2.5 Ordering

**Ordering** = do events arrive in the same sequence they happened? With webhooks: **generally no.** Reasons: retries reorder things (event 5 fails and is retried after event 6 succeeds), the producer may use a pool of senders in parallel, and the network reorders. So design for **commutativity** (order doesn't matter) or carry enough info to reorder/reject stale events yourself (versioning/timestamps; see §3.5, §7).

### 2.6 Authenticity, integrity, and replay

Your webhook endpoint is a **public URL**. Anyone on the internet can POST to it. So you must answer three security questions on every request:

- **Authenticity** — is this really from the producer (not an attacker)? → **signatures**.
- **Integrity** — was the body tampered with in transit? → the signature also covers the body.
- **Freshness / anti-replay** — is this a *new* event, or an attacker replaying an old captured-but-valid request? → **timestamps** + a tolerance window (+ optionally dedup by event ID).

The dominant solution is an **HMAC signature** plus a **timestamp** — the "Stripe model" — covered in depth in §3.3 and §5.

> **Adjacent term — HMAC (Hash-based Message Authentication Code):** a way to prove a message came from someone who knows a shared secret, and wasn't altered. You compute `HMAC(secret, message)` → a fixed-length code (e.g. with SHA-256). The producer and consumer share the secret; the producer sends the code in a header; the consumer recomputes it over the body and checks it matches. An attacker without the secret can't forge a valid code. It's **symmetric** (same secret both sides) and fast.

> **Adjacent term — TLS / HTTPS:** Transport Layer Security encrypts the connection so eavesdroppers can't read or modify traffic, and authenticates the *server's* identity via certificates. Webhooks should always be HTTPS. **But TLS alone is not enough** — it secures the pipe, it doesn't prove *who* sent the POST at the application layer. That's why you still need HMAC signatures: TLS stops the man-in-the-middle on the wire; HMAC stops the forger who simply POSTs to your public URL directly.

### 2.7 The producer/consumer split

Every webhook system has two halves, and you may build either or both:

- **Producer (provider) side:** detect events, enqueue deliveries, sign them, POST to subscriber URLs, retry on failure, dead-letter, expose subscription management + replay tools.
- **Consumer (receiver) side:** host an endpoint, verify signature + timestamp, dedup by event ID, ACK fast, process async, handle/retry your own downstream failures.

We treat both throughout, with full worked examples in §5.

---

## 3. How it works internally

This is the heart of the chapter. We trace the **full lifecycle** of an event from "something happened" to "consumer durably processed it," then drill into each hard part.

### 3.1 End-to-end lifecycle (the happy path, then the real path)

```
[Producer domain event] 
      │  (1) something happens: payment captured
      ▼
[Event recorded in producer DB]  ── (2) write event row + outbox row (same txn)
      │
      ▼
[Delivery scheduler / dispatcher] ── (3) pick subscriptions matching event type
      │
      ▼
[Build + sign HTTP request] ── (4) serialize payload, add timestamp, compute HMAC
      │
      ▼
[POST to subscriber URL] ─────────── (5) send over HTTPS, with timeout
      │
      ├─ 2xx within timeout ──────► (6a) mark delivered, done
      │
      ├─ 4xx (except 408/429) ────► (6b) mark permanently failed (don't retry), alert
      │
      └─ 5xx / timeout / 429 ─────► (6c) schedule retry with backoff
                                          │
                                          ├─ retries exhausted ─► dead-letter + disable/alert
                                          └─ later attempt 2xx  ─► mark delivered
```

On the **consumer**:

```
[POST arrives]
   │ (A) read RAW body bytes (do NOT parse yet)
   ▼
[Verify timestamp freshness]  ── reject if |now - t| > tolerance (e.g. 5 min)
   │
   ▼
[Verify HMAC over (timestamp + "." + rawBody)] ── constant-time compare
   │  fail → 400, stop
   ▼
[Dedup by event ID] ── seen before? → return 200 immediately (idempotent no-op)
   │
   ▼
[Persist event durably (e.g. insert into inbox)] ── this IS the ACK-worthy step
   │
   ▼
[Return 200 FAST]  ◄── producer's retry loop now stops
   │
   ▼
[Process asynchronously] ── worker reads inbox, does business logic idempotently
```

The crucial insight: **the consumer's 200 means "I have durably accepted responsibility for this event," not "I finished all the business logic."** If you do heavy work *before* returning 200, you risk the producer's timeout (often 5–30s) firing, you returning 200 late or 5xx, and a needless retry — now you're processing the same expensive work twice.

### 3.2 The producer's transactional outbox (how producers avoid losing or double-emitting events)

The hardest producer-side problem: **you changed your database AND you need to emit an event — atomically.** If you commit the DB change then crash before enqueuing the webhook, the event is lost. If you enqueue first then the DB txn rolls back, you've emitted a webhook for something that didn't happen ("ghost event").

The standard fix is the **Transactional Outbox pattern**:

1. In the **same database transaction** as your business write, insert a row into an `outbox` table describing the event.
2. A separate **relay/dispatcher** process reads unsent outbox rows and performs the webhook delivery, marking them sent.

Because step 1 is one atomic transaction, you never have "business changed but event missing" or vice versa. The relay gives **at-least-once** emission (it may re-read a row if it crashes after delivery but before marking sent — hence consumers must dedup).

> **Adjacent term — database transaction (ACID):** a unit of work that is Atomic (all-or-nothing), Consistent, Isolated, and Durable. The outbox pattern leans on **atomicity**: the business change and the event record commit together or not at all.

> **Adjacent term — relay / dispatcher:** a background process (often a polling loop or a CDC consumer) that moves outbox rows into actual deliveries. **CDC (Change Data Capture)** = streaming a DB's row-level changes (via the transaction log/WAL) into another system, e.g. with Debezium reading PostgreSQL's WAL or MySQL's binlog. Using CDC instead of polling the outbox is the high-scale variant.

Minimal outbox schema:

```sql
CREATE TABLE outbox (
  id           BIGSERIAL PRIMARY KEY,
  event_id     UUID NOT NULL UNIQUE,         -- the stable id consumers dedup on
  event_type   TEXT NOT NULL,                -- e.g. 'payment.succeeded'
  aggregate_id TEXT NOT NULL,                -- e.g. the order id (for per-key ordering)
  payload      JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at      TIMESTAMPTZ,                  -- null = not yet delivered
  attempts     INT NOT NULL DEFAULT 0
);
CREATE INDEX outbox_unsent_idx ON outbox (created_at) WHERE sent_at IS NULL;
```

### 3.3 Signing: the Stripe model in detail

The de-facto standard, popularized by Stripe and widely copied (Slack, Shopify, GitHub variants), works like this on the **producer**:

1. Take the **timestamp** `t` (Unix seconds) of when you're sending.
2. Take the **raw request body** bytes (the exact JSON you'll send — no re-serialization later).
3. Form a **signed payload string**: `signed_payload = t + "." + rawBody`.
4. Compute `signature = HMAC_SHA256(secret, signed_payload)`, hex-encoded.
5. Send header: `Stripe-Signature: t=<t>,v1=<signature>` (the scheme version is `v1`; `v0` slots exist for rotation/older schemes).

On the **consumer**:

1. Read the **raw body** (critical: the *exact bytes received*, before any parsing/whitespace normalization — re-serializing JSON changes bytes and breaks HMAC).
2. Parse `t` and `v1` from the header.
3. Reject if `|now - t| > tolerance` (Stripe's default tolerance is **300 seconds / 5 minutes**) — this is **replay protection**.
4. Recompute `HMAC_SHA256(secret, t + "." + rawBody)`.
5. **Constant-time compare** against `v1`. If equal → authentic; else → 400.

Why each piece:

- **HMAC over the body** → authenticity + integrity (tamper-evident; forger needs the secret).
- **Timestamp in the signed payload** → an attacker can't replay an old captured request with a fresh timestamp, because changing `t` invalidates the signature; and they can't reuse the old `t` because it's outside the tolerance window.
- **Tolerance window** → bounds how long a captured-but-valid request is replayable (smaller = safer, but too small breaks on clock skew / slow retries).
- **Constant-time compare** → defends against **timing attacks**, where an attacker measures how long comparison takes to guess the signature byte by byte. (See §6 security.)

> **Adjacent term — timing attack:** an attack that infers secret data from how long an operation takes. A naive string comparison returns early on the first mismatching byte, leaking *where* the mismatch is; doing this repeatedly can reconstruct a secret/signature. **Constant-time comparison** always takes the same time regardless of where bytes differ (e.g. Java's `MessageDigest.isEqual`, which is constant-time on modern JDKs).

> **Adjacent term — secret rotation:** periodically changing the shared HMAC secret. Good systems support **two valid secrets at once** during a rollover window, so a delivery signed with the old secret still verifies while you swap in the new one. This is why signature schemes carry a version (`v1`) and providers let you have multiple signing secrets per endpoint.

Some providers (e.g. GitHub `X-Hub-Signature-256`, Shopify `X-Shopify-Hmac-Sha256` base64) use slightly different headers/encodings but the same HMAC-over-raw-body idea. A few high-security providers use **asymmetric signatures** (the producer signs with a private key; you verify with their public key — no shared secret to leak). Stripe also offers asymmetric signatures for some uses. Asymmetric is strictly better for *not having to store a shared secret*, at the cost of slower verification.

> **Adjacent term — symmetric vs asymmetric crypto:** symmetric (HMAC) uses one shared secret for both signing and verifying — fast, but both sides hold the secret. Asymmetric (e.g. Ed25519/RSA signatures) uses a private key to sign and a public key to verify — the consumer only needs the *public* key, so a leak there can't forge events.

### 3.4 Idempotency & dedup: the consumer's state machine

The consumer maintains a record of processed event IDs. The classic implementation is an **inbox table** with a unique constraint on `event_id`:

```sql
CREATE TABLE webhook_inbox (
  event_id    TEXT PRIMARY KEY,         -- producer's stable event id
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status      TEXT NOT NULL DEFAULT 'received',  -- received | processed | failed
  payload     JSONB NOT NULL
);
```

State transitions per event:

```
        (POST arrives, verified)
              │
   INSERT event_id ──┬── success (new)  → status=received → enqueue processing
                     └── UNIQUE violation (duplicate) → return 200, do nothing
              │
        worker picks it up
              │
   business logic (itself idempotent) → status=processed
              │ on error
   leave status=received/failed → retried by your own worker
```

The `UNIQUE` constraint is the deduplication primitive: the **database** atomically decides "first time" vs "duplicate," so two concurrent deliveries of the same event can't both proceed. This is far more robust than "check then insert" in application code, which has a race window.

There are two layers of idempotency and you usually want **both**:

1. **Dedup by event ID** (cheap, catches retries/duplicates) — the inbox.
2. **Idempotent side effects** (defense in depth) — e.g. `INSERT ... ON CONFLICT DO NOTHING` when creating the order, or "only transition order to PAID if currently PENDING." Even if dedup is bypassed, the business operation is safe to repeat.

### 3.5 Ordering: what producers can and can't promise

Most producers explicitly document **no ordering guarantee**. Some offer weaker promises:

- **No ordering** (default): events may arrive out of order. Design consumers to tolerate it (use `created` timestamps / version numbers on the *resource* to ignore stale updates).
- **Per-key / per-aggregate ordering**: deliveries for the *same* entity (same `aggregate_id`) are serialized; different entities may interleave. Implemented producer-side by partitioning the dispatcher by key and not sending event N+1 for a key until event N is acknowledged.
- **Strict global ordering**: rare; effectively requires a single-threaded dispatcher and blocks the whole stream on one slow/failing consumer — usually a bad trade.

Consumer-side defenses regardless of producer promise:

- Carry a **monotonic version** or `updated_at` in the payload (event-carried state) and **last-writer-wins by version**: ignore an event whose version ≤ the version you already applied.
- Or use **thin events** (just an ID) and **re-fetch current state** from the producer's API — the fetch returns *latest* truth, so stale ordering matters less (but introduces a read and a race window). See §3.7.

### 3.6 Failure handling: retries, backoff, dead-letter, replay

When a delivery fails (timeout, connection refused, 5xx, 429), the producer schedules a retry. Key design parameters:

- **What counts as failure** → no 2xx within timeout, or a retryable status. Most producers retry on `5xx`, `429 Too Many Requests`, `408 Request Timeout`, and network errors. They do **not** retry on other `4xx` (e.g. `400`, `401`, `403`, `404`, `422`) because those mean *your endpoint rejected it on purpose* — retrying won't help.
- **Retry schedule** → **exponential backoff with jitter**. Backoff = wait longer each attempt (e.g. 1m, 5m, 30m, 2h, 5h...) so you don't hammer a struggling consumer. **Jitter** = randomize the delay so many failed deliveries don't all retry at the same instant (the "thundering herd").
- **Max attempts / max age** → give up after N attempts or after the event is older than some window (e.g. Stripe retries for up to ~3 days with exponential backoff).
- **Dead-letter** → after exhausting retries, move the delivery to a **dead-letter queue (DLQ)** / failed-deliveries store, alert, and typically **auto-disable** a chronically failing endpoint to stop wasting resources.
- **Manual replay** → operators (or the consumer via API/dashboard) can **re-send** failed or even successful deliveries — essential for "we had a bug, please resend the last 6 hours."

> **Adjacent term — exponential backoff with jitter:** retry delays grow multiplicatively (`base * 2^attempt`) up to a cap, then a random factor is applied. Canonical "full jitter": `sleep = random(0, min(cap, base * 2^attempt))`. This spreads retries in time, preventing synchronized retry storms.

> **Adjacent term — dead-letter queue (DLQ):** a holding area for messages that couldn't be delivered/processed after all retries. It prevents poison messages from blocking the pipeline forever and gives operators a place to inspect, fix, and replay.

> **Adjacent term — thundering herd:** when many clients react to the same trigger simultaneously (e.g. all retry at t+60s), spiking load and often re-causing the failure. Jitter and backoff are the standard mitigations.

A representative retry schedule (illustrative — always check the specific provider):

| Attempt | Delay after previous | Cumulative time |
|---|---|---|
| 1 | immediate | 0 |
| 2 | ~1 min | ~1 min |
| 3 | ~5 min | ~6 min |
| 4 | ~30 min | ~36 min |
| 5 | ~2 hr | ~2.6 hr |
| 6 | ~5 hr | ~7.6 hr |
| 7–N | ~hourly, decaying | up to ~72 hr |

> Stripe documents retrying "for up to three days with an exponential backoff"; the exact per-attempt delays are not contractually fixed and have changed over time. Treat the table above as a *shape*, not gospel.

### 3.7 Thin vs. fat events (data flow design)

- **Thin event (notification / "event as signal"):** the payload is minimal — event type + the **ID** of the changed resource (`{"type":"order.updated","data":{"id":"ord_123"}}`). The consumer then **calls back** to the producer's API to fetch the full current state.
  - Pros: small payloads; consumer always reads *latest* truth (sidesteps stale-ordering and stale-data); no sensitive data sitting in delivery logs/DLQs.
  - Cons: extra API round-trip per event (latency + load on producer API); a read-after-event race (state may have changed again); useless if producer's API is down.
- **Fat event (event-carried state transfer):** the payload includes the **full state** of the resource (or the full change). The consumer can act without calling back.
  - Pros: no callback round-trip; works even if producer's read API is down; great for analytics/replay.
  - Cons: large payloads; may carry sensitive data into your logs; **stale data risk** (the embedded state may be older than current, especially with out-of-order delivery) → you need versioning.

> **Adjacent term — event-carried state transfer:** an event-driven pattern where events carry the data needed to act, so consumers don't have to query back. The opposite end is "event notification" (thin), which only says *that* something changed.

Many mature providers send **fat-ish** events (enough to act) **plus** the IDs to re-fetch if you want the absolute latest — best of both, and Stripe's model (the event embeds the object, and you can also retrieve it fresh).

---

## 4. The complete toolkit

### 4.1 HTTP status codes the consumer should return

| Code | Meaning to producer | Producer behavior |
|---|---|---|
| `200`, `201`, `202`, `204` | Accepted | Done, no retry |
| `2xx` returned slowly (>timeout) | Treated as failure | **Retry** (you may double-process) |
| `400` | Bad/invalid (e.g. bad signature) | No retry (permanent) |
| `401` / `403` | Auth rejected | No retry; often disables endpoint after repeats |
| `404` / `410` | Endpoint gone | No retry; may disable |
| `408` | Request timeout | Usually retry |
| `409` | Conflict | Provider-specific; often no retry |
| `422` | Unprocessable | No retry (you rejected the content) |
| `429` | Rate limited | Retry, ideally honoring `Retry-After` |
| `5xx` | Transient server error | **Retry with backoff** |

Rule of thumb you, as a consumer, should follow: **return 2xx the instant you've durably stored the event; return 5xx only for genuinely transient internal failures you want retried; return 400 for signature/format failures you never want retried.** Do NOT return 200 for a bad signature (you'd be telling the producer "delivered" while silently dropping it).

### 4.2 Common producer headers (varies by vendor — flagged)

| Header | Purpose | Example provider |
|---|---|---|
| `Stripe-Signature: t=...,v1=...` | timestamp + HMAC-SHA256 hex | Stripe |
| `X-Hub-Signature-256: sha256=...` | HMAC-SHA256 hex of body | GitHub |
| `X-Shopify-Hmac-Sha256: <base64>` | HMAC-SHA256 base64 of body | Shopify |
| `Svix-Id`, `Svix-Timestamp`, `Svix-Signature` | id + ts + HMAC (multi-version) | Svix-based providers |
| `<X>-Event-Type` / `X-GitHub-Event` | event type, lets you route without parsing | many |
| `<X>-Delivery` / `X-GitHub-Delivery` | unique delivery/event id (dedup key) | GitHub |
| `Webhook-Id`, `Webhook-Timestamp`, `Webhook-Signature` | the **Standard Webhooks** spec headers | standardized |
| `User-Agent` | identify the producer | all |

> **Adjacent term — Standard Webhooks (standardwebhooks.com):** an open spec (backed by Svix and others) to standardize webhook signatures/headers across providers, so consumers can use one verification library. Worth knowing it exists; adoption is growing but not universal.

### 4.3 Consumer-side verification toolkit (Java)

| Tool / API | Purpose | Notes/defaults |
|---|---|---|
| `javax.crypto.Mac` (`"HmacSHA256"`) | compute HMAC | JCA standard, in the JDK |
| `javax.crypto.spec.SecretKeySpec` | wrap the secret bytes as a key | algorithm string must match |
| `java.security.MessageDigest.isEqual(byte[],byte[])` | **constant-time** compare | use this, NOT `Arrays.equals`/`String.equals` |
| `HexFormat` (JDK 17+) | hex encode/decode | replaces manual `String.format("%02x")` |
| `java.util.Base64` | base64 (for vendors that base64 the HMAC) | |
| `HttpServletRequest.getInputStream()` / Spring `@RequestBody byte[]` | read **raw** body bytes | never `@RequestBody Map`/`String` if it re-encodes |
| Stripe SDK `Webhook.constructEvent(payload, sigHeader, secret)` | verify + parse in one call | enforces 5-min tolerance |
| Provider SDKs (GitHub, Shopify, Svix) | verify per their scheme | prefer over hand-rolling |

### 4.4 Producer-side delivery toolkit (Java)

| Tool | Purpose | Notes |
|---|---|---|
| `java.net.http.HttpClient` (JDK 11+) | send POSTs | set `connectTimeout`; per-request `timeout(...)` |
| `HttpRequest.newBuilder().timeout(Duration)` | per-delivery timeout | **set this** (e.g. 5–10s) — unbounded waits stall the dispatcher |
| Apache HttpClient / OkHttp | alternative HTTP clients | connection pooling, retries |
| Resilience4j `Retry`, `CircuitBreaker`, `RateLimiter` | backoff, circuit breaking per endpoint | library; configurable |
| `ScheduledExecutorService` / Quartz / a job queue (Sidekiq-style) | schedule retries | persistent queue preferred over in-memory |
| Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` | emit only after the txn commits | avoids ghost events without a full outbox |
| Kafka / SQS / RabbitMQ | durable delivery queue behind the dispatcher | gives at-least-once + DLQ for free |
| Debezium (CDC) | drive the outbox relay from the DB log | high-scale outbox relay |

### 4.5 Tunnels & test tooling

| Tool | Purpose |
|---|---|
| `stripe listen --forward-to localhost:4242/webhook` | Stripe CLI: receive real test events locally, prints the signing secret |
| `stripe trigger payment_intent.succeeded` | fire a synthetic event |
| ngrok / Cloudflare Tunnel / localtunnel | expose `localhost` to a public URL for dev |
| webhook.site / RequestBin / Beeceptor | inspect raw incoming webhook requests |
| Svix Play / provider "send test event" buttons | replay/test deliveries |
| `curl` with a hand-computed signature | unit-test your verifier |

### 4.6 Subscription-management API surface (what a producer exposes)

| Endpoint (conventional) | Purpose |
|---|---|
| `POST /webhook_endpoints` | register a URL + the event types you want |
| `GET /webhook_endpoints` | list your endpoints |
| `PATCH /webhook_endpoints/{id}` | enable/disable, change events, rotate secret |
| `DELETE /webhook_endpoints/{id}` | remove |
| `GET /webhook_endpoints/{id}/secret` | retrieve/roll the signing secret |
| `GET /events/{id}` | fetch a past event (for thin-event re-fetch / audit) |
| `POST /events/{id}/resend` or dashboard "Resend" | manual replay |
| `GET /webhook_deliveries?status=failed` | inspect failed deliveries (DLQ view) |

---

## 5. Code examples by use case

All examples are Java (JDK 17+, Spring Boot flavored where a framework helps). They're written to be adapted, with the non-obvious lines commented.

### 5.1 Consumer: verify a Stripe-style signature by hand (no SDK)

This shows the verification *mechanics* — you'd normally use the SDK, but you must understand this.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public final class StripeStyleVerifier {

    private static final long TOLERANCE_SECONDS = 300; // 5 min replay window

    /**
     * @param rawBody   the EXACT bytes received (do not re-serialize JSON!)
     * @param sigHeader value of "Stripe-Signature": "t=...,v1=...,v1=..."
     * @param secret    the endpoint signing secret (whsec_...)
     */
    public static boolean verify(byte[] rawBody, String sigHeader, String secret) {
        long t = -1;
        var expectedSigs = new java.util.ArrayList<String>();
        for (String part : sigHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].trim()) {
                case "t"  -> t = Long.parseLong(kv[1].trim());
                case "v1" -> expectedSigs.add(kv[1].trim()); // may be multiple during rotation
            }
        }
        if (t < 0 || expectedSigs.isEmpty()) return false;

        // 1) Replay protection: reject if timestamp is outside the tolerance window.
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - t) > TOLERANCE_SECONDS) return false;

        // 2) Recompute HMAC over "t.rawBody" — note: prefix is the ASCII timestamp + "."
        byte[] signedPayload = concat(
                (t + ".").getBytes(StandardCharsets.UTF_8), rawBody);
        byte[] computed = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), signedPayload);
        String computedHex = HexFormat.of().formatHex(computed);

        // 3) Constant-time compare against each provided signature.
        for (String expected : expectedSigs) {
            byte[] a = computedHex.getBytes(StandardCharsets.UTF_8);
            byte[] b = expected.getBytes(StandardCharsets.UTF_8);
            // MessageDigest.isEqual is constant-time on modern JDKs -> resists timing attacks
            if (MessageDigest.isEqual(a, b)) return true;
        }
        return false;
    }

    private static byte[] hmacSha256(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
```

Key gotchas embedded above: read the **raw bytes**, include the **`t.` prefix**, support **multiple `v1`** during rotation, **constant-time compare**, and **enforce the tolerance**.

### 5.2 Consumer: Spring Boot endpoint — verify, dedup, ACK fast, process async

```java
@RestController
@RequestMapping("/webhooks/stripe")
public class StripeWebhookController {

    private final String signingSecret;          // from config/secret manager
    private final WebhookInboxRepo inbox;         // persistence
    private final ApplicationEventPublisher bus;  // hand off to async worker

    public StripeWebhookController(@Value("${stripe.webhook.secret}") String secret,
                                   WebhookInboxRepo inbox,
                                   ApplicationEventPublisher bus) {
        this.signingSecret = secret;
        this.inbox = inbox;
        this.bus = bus;
    }

    // IMPORTANT: take the body as raw bytes so the HMAC matches exactly.
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader("Stripe-Signature") String sig) {

        // 1) Authenticity + integrity + replay protection.
        if (!StripeStyleVerifier.verify(rawBody, sig, signingSecret)) {
            // 400 (NOT 200): we never want this delivery to count as accepted,
            // but we also don't want the producer to retry a forged/garbage request.
            return ResponseEntity.badRequest().build();
        }

        // 2) Parse just enough to get the event id + type.
        JsonNode evt = Json.read(rawBody);
        String eventId = evt.get("id").asText();
        String type    = evt.get("type").asText();

        // 3) Dedup: rely on the DB unique constraint, not a check-then-insert.
        boolean firstTime = inbox.tryInsert(eventId, type, rawBody);
        if (!firstTime) {
            // Duplicate (a retry). We already accepted it; ACK so producer stops retrying.
            return ResponseEntity.ok().build();
        }

        // 4) Hand off to async processing AFTER the row is committed.
        //    Returning 200 now == "durably accepted", per our contract.
        bus.publishEvent(new WebhookReceived(eventId));
        return ResponseEntity.ok().build();
    }
}

@Repository
class WebhookInboxRepo {
    private final JdbcTemplate jdbc;
    WebhookInboxRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** @return true if newly inserted, false if it already existed (duplicate). */
    boolean tryInsert(String eventId, String type, byte[] payload) {
        int rows = jdbc.update(
            // ON CONFLICT DO NOTHING -> atomic dedup at the DB layer (Postgres).
            "INSERT INTO webhook_inbox(event_id, status, payload) " +
            "VALUES (?, 'received', ?::jsonb) ON CONFLICT (event_id) DO NOTHING",
            eventId, new String(payload, java.nio.charset.StandardCharsets.UTF_8));
        return rows == 1;
    }
}
```

```java
@Component
class WebhookProcessor {
    private final OrderService orders;
    WebhookProcessor(OrderService orders) { this.orders = orders; }

    // Runs AFTER the inbox insert is committed, on a separate thread pool.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onReceived(WebhookReceived e) {
        // Business logic must ALSO be idempotent (defense in depth):
        // e.g. only move PENDING -> PAID; a second run on a PAID order is a no-op.
        orders.markPaidIfPending(e.eventId());
    }
}
```

Why this shape: signature first (cheapest rejection of garbage), DB-unique dedup (race-free), fast 200, async business logic that is itself idempotent.

### 5.3 Consumer: GitHub webhook (different scheme — `X-Hub-Signature-256`)

```java
// GitHub signs: HMAC-SHA256(secret, rawBody), sent as "sha256=<hex>".
// No timestamp in the signature -> you get no built-in replay protection,
// so dedup by the X-GitHub-Delivery id is your main defense.
@PostMapping("/webhooks/github")
public ResponseEntity<Void> github(@RequestBody byte[] body,
                                   @RequestHeader("X-Hub-Signature-256") String sig,
                                   @RequestHeader("X-GitHub-Event") String event,
                                   @RequestHeader("X-GitHub-Delivery") String deliveryId) {
    byte[] mac = hmacSha256(secret.getBytes(UTF_8), body);
    String expected = "sha256=" + HexFormat.of().formatHex(mac);
    if (!MessageDigest.isEqual(expected.getBytes(UTF_8), sig.getBytes(UTF_8)))
        return ResponseEntity.status(401).build();

    if (!inbox.tryInsert(deliveryId, event, body))   // dedup by delivery id
        return ResponseEntity.ok().build();
    bus.publishEvent(new WebhookReceived(deliveryId));
    return ResponseEntity.ok().build();
}
```

Lesson: schemes differ (header name, encoding, presence of timestamp). Always read the provider's exact spec; don't assume Stripe's format.

### 5.4 Producer: emit events safely with the transactional outbox

```java
@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    public PaymentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional  // business write + outbox insert commit together (or not at all)
    public void capturePayment(String orderId, long amount) {
        jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", orderId);

        String eventId = UUID.randomUUID().toString();
        String payload = """
            {"id":"%s","type":"payment.succeeded",
             "data":{"order_id":"%s","amount":%d},"created":%d}
            """.formatted(eventId, orderId, amount, Instant.now().getEpochSecond());

        // Outbox row in the SAME transaction => no ghost events, no lost events.
        jdbc.update("INSERT INTO outbox(event_id, event_type, aggregate_id, payload) " +
                    "VALUES (?,?,?,?::jsonb)",
                    eventId, "payment.succeeded", orderId, payload);
    }
}
```

```java
@Component
class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final WebhookSender sender;
    OutboxRelay(JdbcTemplate jdbc, WebhookSender sender) { this.jdbc = jdbc; this.sender = sender; }

    // Poll unsent rows. (At scale, replace with Debezium CDC reading the WAL.)
    @Scheduled(fixedDelay = 1000)
    void pump() {
        // SKIP LOCKED lets multiple relay instances run without grabbing the same rows.
        List<OutboxRow> batch = jdbc.query(
            "SELECT id,event_id,event_type,aggregate_id,payload FROM outbox " +
            "WHERE sent_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED",
            OutboxRelay::map);

        for (OutboxRow r : batch) {
            boolean ok = sender.deliver(r);    // signs + POSTs to all matching subscriptions
            if (ok) jdbc.update("UPDATE outbox SET sent_at=now() WHERE id=?", r.id());
            else    jdbc.update("UPDATE outbox SET attempts=attempts+1 WHERE id=?", r.id());
        }
    }
    // ... row mapper omitted ...
}
```

> **Adjacent term — `FOR UPDATE SKIP LOCKED`:** a SQL feature (Postgres/MySQL) where a `SELECT ... FOR UPDATE` locks the chosen rows and `SKIP LOCKED` makes other transactions skip already-locked rows instead of blocking. It turns a plain table into a safe concurrent work queue for multiple relay workers.

### 5.5 Producer: sign + POST with timeout and backoff

```java
@Component
public class WebhookSender {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))   // bound connect time
            .build();
    private final SubscriptionRepo subs;
    WebhookSender(SubscriptionRepo subs) { this.subs = subs; }

    boolean deliver(OutboxRow r) {
        boolean allOk = true;
        for (Subscription s : subs.forEventType(r.eventType())) {   // fan-out to subscribers
            long t = Instant.now().getEpochSecond();
            String signed = t + "." + r.payload();
            String sig = "t=" + t + ",v1=" + hmacHex(s.signingSecret(), signed);

            HttpRequest req = HttpRequest.newBuilder(URI.create(s.url()))
                    .timeout(Duration.ofSeconds(10))                 // per-delivery timeout
                    .header("Content-Type", "application/json")
                    .header("Webhook-Signature", sig)
                    .header("Webhook-Id", r.eventId())               // consumer dedups on this
                    .header("User-Agent", "AcmeHooks/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(r.payload()))
                    .build();
            try {
                int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (code / 100 != 2) { allOk = false; scheduleRetryIfRetryable(s, r, code); }
            } catch (Exception io) {
                allOk = false;
                scheduleRetryIfRetryable(s, r, -1);                  // network error -> retry
            }
        }
        return allOk;
    }

    private void scheduleRetryIfRetryable(Subscription s, OutboxRow r, int code) {
        // Retry only on transient conditions; 4xx (except 408/429) is permanent.
        boolean retryable = code == -1 || code >= 500 || code == 429 || code == 408;
        if (!retryable) { deadLetter(s, r, code); return; }
        int attempt = r.attempts() + 1;
        if (attempt > 12) { deadLetter(s, r, code); disableEndpointIfFlapping(s); return; }

        // Exponential backoff with FULL JITTER.
        long capMs  = Duration.ofHours(5).toMillis();
        long expMs  = Math.min(capMs, (long) (1000L * Math.pow(2, attempt)));
        long delay  = ThreadLocalRandom.current().nextLong(0, expMs + 1);
        retryQueue.schedule(() -> deliverOne(s, r), delay, TimeUnit.MILLISECONDS);
    }
    // ... hmacHex, deadLetter, disableEndpointIfFlapping, retryQueue omitted ...
}
```

### 5.6 Consumer with downstream slowness: ACK first, queue, then process

When your business logic is slow or your DB is intermittently down, don't make the producer wait:

```java
@PostMapping("/webhooks/stripe")
public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                     @RequestHeader("Stripe-Signature") String sig) {
    if (!verify(body, sig, secret)) return ResponseEntity.badRequest().build();
    String eventId = Json.read(body).get("id").asText();

    // Durable, fast write: just land it on a queue (SQS/Kafka) or inbox table.
    if (queue.enqueueIfNew(eventId, body)) {   // dedup at enqueue
        // 200 means "safely queued", processing happens out-of-band.
        return ResponseEntity.ok().build();
    }
    return ResponseEntity.ok().build();        // duplicate -> still 200
}
```

If the queue write itself fails, return **5xx** — *now* you want the producer to retry, because you did NOT durably accept the event.

### 5.7 Testing: unit-test the verifier with a known vector + replay window

```java
@Test
void rejectsTamperedBody() {
    long t = Instant.now().getEpochSecond();
    byte[] body = "{\"id\":\"evt_1\"}".getBytes(UTF_8);
    String good = "t=" + t + ",v1=" + hmacHex(SECRET, t + "." + new String(body, UTF_8));
    assertTrue(StripeStyleVerifier.verify(body, good, SECRET));

    byte[] tampered = "{\"id\":\"evt_2\"}".getBytes(UTF_8); // attacker edits body
    assertFalse(StripeStyleVerifier.verify(tampered, good, SECRET)); // sig no longer matches
}

@Test
void rejectsReplayOutsideWindow() {
    long old = Instant.now().getEpochSecond() - 3600; // 1h old > 5min tolerance
    byte[] body = "{\"id\":\"evt_1\"}".getBytes(UTF_8);
    String sig  = "t=" + old + ",v1=" + hmacHex(SECRET, old + "." + new String(body, UTF_8));
    assertFalse(StripeStyleVerifier.verify(body, sig, SECRET)); // expired
}

@Test
void duplicateEventProcessedOnce() {
    process(EVENT);        // first time
    process(EVENT);        // retry / duplicate
    verify(orders, times(1)).markPaid(any()); // side effect happened exactly once
}
```

### 5.8 Local end-to-end test with the Stripe CLI

```bash
# 1) Forward real Stripe test events to your local server; it prints a signing secret.
stripe listen --forward-to localhost:8080/webhooks/stripe
#   -> Ready! Your webhook signing secret is whsec_abc123...   (use this in config)

# 2) In another terminal, fire a synthetic event.
stripe trigger payment_intent.succeeded

# Alternatively, expose localhost publicly and register the URL in the dashboard:
ngrok http 8080
#   -> https://random.ngrok.io  (register https://random.ngrok.io/webhooks/stripe)
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **ACK fast.** Target a P99 endpoint latency well under the producer's timeout (commonly 5–30s; **Stripe's is ~30s but you should aim for <1–2s**). Do only verify + dedup + durable-store synchronously; defer everything else.
- **Bound everything on the producer side:** connect timeout, read timeout, total per-delivery timeout. An endpoint that holds the connection open for 60s must not stall your dispatcher — use timeouts + a bounded thread pool / async client.
- **Fan-out cost:** one domain event × N subscribers = N HTTP calls. Parallelize across subscribers, but **isolate** them (one slow subscriber must not delay others — separate queues/circuit breakers per endpoint).
- **Connection reuse:** keep-alive / HTTP/2 to subscribers reduces TLS handshake cost on bursty traffic.

### 6.2 Correctness & concurrency

- **Idempotency is non-negotiable** (at-least-once delivery guarantees duplicates). Use DB unique constraints, not check-then-act.
- **Concurrent duplicate deliveries** (same event POSTed twice in parallel during a retry race) are handled by the unique-constraint approach; a `synchronized` block in app code does NOT help across multiple instances.
- **Ordering:** never assume it. Version your resources and apply last-writer-wins, or re-fetch.
- **Don't trust payload contents for authorization** — verify the signature, then still validate that the referenced resource belongs to the right account/tenant (a valid signature proves *who sent it*, not *what you're allowed to do with it*).

### 6.3 Security (the part most teams get wrong)

- **Always verify signatures.** An unverified public POST endpoint is an open door (anyone can fake "payment succeeded").
- **Verify over the RAW body**, before parsing. Re-serializing JSON to verify is the #1 cause of "valid signatures that don't validate."
- **Constant-time compare** the signature (`MessageDigest.isEqual`), never `==`/`equals`/`Arrays.equals`.
- **Enforce a timestamp tolerance** (replay protection). 5 minutes is the common default; tighten if your clocks are well-synced.
- **HTTPS only**, and ideally validate the producer's TLS cert. TLS protects the wire; the HMAC protects identity — you need both.
- **Store secrets in a secret manager** (Vault, AWS Secrets Manager, KMS-encrypted), not in code/config files. Support **rotation** with two active secrets.
- **SSRF guard on the producer side:** customers register arbitrary URLs. Block deliveries to internal/metadata IPs (`169.254.169.254`, `127.0.0.1`, RFC1918 ranges) or an attacker uses *your* server to probe your internal network. Validate/resolve the URL and reject private targets.

> **Adjacent term — SSRF (Server-Side Request Forgery):** an attack where the attacker makes *your* server issue requests to targets of their choosing. For webhooks, a malicious subscriber URL like `http://169.254.169.254/latest/meta-data/` could trick your dispatcher into fetching cloud credentials. Always validate destination IPs before delivering.

- **Don't leak in logs:** fat events may carry PII/secrets. Redact before logging; control access to DLQs (they hold full payloads).
- **Rate-limit / size-limit** incoming webhooks: reject oversized bodies (e.g. >256 KB–1 MB) to avoid memory abuse.

### 6.4 Observability

Instrument both sides:

- **Producer metrics:** delivery success rate per endpoint, latency histograms, retry counts, DLQ depth, endpoints auto-disabled, age of oldest undelivered event.
- **Consumer metrics:** received count by type, verification-failure count (spikes = secret mismatch or attack), dedup-hit rate (high = lots of retries = something slow), processing latency, processing-failure count.
- **Tracing:** propagate a trace/correlation id (often the event id) end-to-end so you can follow one event from emission to processing.
- **Audit log:** keep the raw event + headers for a retention window — invaluable for "did we receive event X?" and for replay.

> **Adjacent term — DLQ depth / oldest-event age:** queue-health metrics. A growing DLQ or an old "oldest undelivered" means deliveries are systematically failing — page on these.

### 6.5 Cost

- Each delivery is an HTTP request + TLS + retries; chronic failures multiply cost (every event retried 12×). Auto-disable flapping endpoints.
- Storing all raw events/deliveries for replay grows fast — set retention (e.g. 30–90 days) and archive cold data.
- Fat events cost more bandwidth/storage than thin; choose deliberately.

### 6.6 Testing

- **Unit-test the verifier** with known-good and tampered/expired vectors (§5.7).
- **Idempotency test:** deliver the same event twice; assert the side effect happens once.
- **Contract tests:** record real provider payloads (from the CLI/sandbox) as fixtures; assert your parser/handler against them so a provider schema change is caught.
- **Chaos:** simulate retries, out-of-order delivery, and slow/failing downstreams.
- **Local loop:** Stripe CLI / ngrok / webhook.site for manual exploration.

### 6.7 Production hardening checklist

- [ ] Signature verification on raw body, constant-time, timestamp tolerance.
- [ ] Dedup by event id with a unique constraint.
- [ ] ACK fast (<2s), process async on a durable queue/inbox.
- [ ] Idempotent business logic (defense in depth).
- [ ] Bounded timeouts on the producer; backoff + jitter; DLQ; auto-disable.
- [ ] SSRF guard on subscriber URLs; HTTPS enforced.
- [ ] Secrets in a manager; rotation with dual secrets.
- [ ] Metrics + alerts on failure rate, DLQ depth, verify-failure spikes.
- [ ] Manual replay tooling for both sides.
- [ ] Payload versioning / schema evolution plan.

### 6.8 Anti-patterns

- Doing heavy work *before* returning 200 → timeouts → duplicate processing.
- Returning **200 on bad signature** → silently dropping events the producer thinks were delivered.
- Verifying over the parsed/re-serialized body → flaky verification.
- `Arrays.equals` for signatures → timing attack surface.
- Assuming ordering / exactly-once → data corruption under retries.
- In-memory-only retry queue on the producer → lost deliveries on restart.
- No dedup → double charges/emails.
- Trusting TLS alone, no HMAC → forgeable endpoint.
- Synchronous fan-out where one slow subscriber blocks all others.

---

## 7. Advanced topics & deep internals

### 7.1 Ordering done properly: per-aggregate serialization

To offer per-key ordering, the producer keys deliveries by `aggregate_id` and ensures event N+1 for a key isn't dispatched until event N is acknowledged (or dead-lettered). Implementation: a partitioned queue (Kafka topic partitioned by key, or a per-key in-flight lock). The cost: **head-of-line blocking** — one failing event for a key stalls that key's stream. Bound it with a max-stall timeout that moves the stuck event to DLQ and lets the stream proceed (giving up strict ordering for that key, but staying live).

> **Adjacent term — head-of-line blocking:** when the item at the front of a queue can't proceed and blocks everything behind it, even if those could proceed. Strict ordering inherently risks this.

### 7.2 Consumer-side reordering with version fences

For fat events, embed a monotonic `version` (or `updated_at`) on the resource. The consumer keeps the last-applied version per resource and **drops** any event with `version ≤ applied`. This makes processing order-insensitive without producer-side ordering:

```java
// Last-writer-wins by version; out-of-order/duplicate stale events are ignored.
int updated = jdbc.update(
    "UPDATE orders SET status=?, version=? WHERE id=? AND version < ?",
    newStatus, evtVersion, orderId, evtVersion);
// updated == 0 means this event is stale (we already have newer state) -> no-op
```

### 7.3 Exactly-once *effect* across non-idempotent downstreams

If a side effect is *inherently* non-idempotent (e.g. "send an SMS"), wrap it with an **idempotency table**: before sending, `INSERT` a row keyed by event id + action; if the insert succeeds, perform the side effect and record the external id; if it conflicts, skip. Make the insert and the "intent to send" part of one transaction; treat a crash-after-insert-before-send by reconciling against the provider's API (did the SMS go out?).

### 7.4 Verification edge cases

- **Body mutation by proxies/gateways:** an API gateway that re-encodes, gzips, or pretty-prints JSON will break HMAC. Verify *before* any such layer, or configure the gateway to pass bytes through untouched.
- **Charset/encoding:** the HMAC is over bytes; ensure your framework hands you the *bytes*, not a `String` decoded with a possibly-different charset.
- **Multiple signatures:** during secret rotation a provider may send several `v1=` values (or `v1` for new + something for old). Accept if *any* matches a *currently valid* secret.
- **Clock skew:** if your server clock drifts, timestamp tolerance can falsely reject. Run NTP; consider a slightly looser tolerance if you can't guarantee tight sync.

> **Adjacent term — NTP (Network Time Protocol):** keeps a machine's clock synchronized to within milliseconds of a reference. Webhook timestamp checks assume reasonably synced clocks; skew causes spurious replay-window rejections.

### 7.5 Backpressure & poison events

- **Backpressure:** if the consumer is overwhelmed it can return `429` with `Retry-After`; well-behaved producers honor it and back off. On the producer, a per-endpoint **circuit breaker** stops hammering a down consumer and fails fast.
- **Poison event:** an event that always fails processing (malformed, references deleted data). Cap processing retries on the *consumer* side too and route to a consumer-side DLQ for manual handling, so one poison event doesn't wedge the worker forever.

> **Adjacent term — circuit breaker:** a resilience pattern (Resilience4j, Hystrix-style) that, after a threshold of failures, "opens" and short-circuits further calls for a cooldown, then "half-opens" to test recovery. Prevents wasting resources on a known-down dependency and gives it room to recover.

### 7.6 Schema/version evolution

Webhooks are a public contract. Strategies:

- **Additive only:** add fields, never remove/rename — consumers ignore unknown fields.
- **Versioned payloads / endpoints:** include `"api_version"` (Stripe pins a webhook to the account's API version) or version the type (`order.updated.v2`).
- **Per-endpoint version pinning:** let a subscriber choose which schema version they receive, so you can ship a new version without breaking existing consumers.

### 7.7 Webhooks vs. an internal event bus, bridged

A common architecture: internally you use Kafka/SNS (durable, ordered-per-partition, high throughput); a **webhook gateway** subscribes to those internal topics and translates them into signed outbound HTTP deliveries with retries/DLQ. Products like **Svix**, **Hookdeck**, **Convoy**, and AWS **EventBridge API Destinations** are managed versions of this gateway. This cleanly separates *internal* event semantics from *external* delivery concerns.

> **Adjacent term — EventBridge API Destinations / Svix / Hookdeck / Convoy:** managed "webhooks-as-a-service" gateways. They take your events and handle signing, retries, backoff, DLQ, subscription management, and observability so you don't rebuild that machinery. Useful when webhooks aren't your core product.

### 7.8 Receiving at scale: idempotency-store choices

The dedup store must be fast and durable. Options and their tradeoffs:

| Store | Pros | Cons |
|---|---|---|
| Relational unique constraint | strong consistency, transactional with business write | write throughput limited by DB |
| Redis `SET key NX EX ttl` | very fast | not durable by default; TTL means very-late duplicates can slip through |
| DynamoDB conditional put | scalable, managed | eventual-consistency care, cost |

If you use a TTL'd store (Redis), set the TTL **longer than the producer's max retry window** (e.g. >3 days for Stripe), or a very late retry could be processed as new.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Webhooks vs. alternatives

| Approach | Latency | Consumer hosts endpoint? | Ordering | Throughput ceiling | Best for |
|---|---|---|---|---|---|
| **Polling** | interval-bound | no | n/a (you read latest) | high (you control) | simple, no infra on consumer, low freshness need |
| **Webhooks** | seconds | **yes (public URL)** | usually none | moderate per endpoint | cross-org server-to-server events |
| **WebSockets/SSE** | ms | no (persistent conn) | per-connection | high | browser/app realtime |
| **Kafka/SQS/SNS** | ms | no (consumer pulls/sub) | per-partition / none | very high | internal event-driven systems |
| **gRPC streaming** | ms | no | per-stream | high | internal RPC streaming |

### 8.2 Thin vs. fat events

| Dimension | Thin (notification) | Fat (state-carried) |
|---|---|---|
| Payload size | tiny | large |
| Extra API call to act | yes (re-fetch) | no |
| Always latest truth | yes (fetch is fresh) | no (may be stale) |
| Works if producer read API down | no | yes |
| Sensitive data in logs/DLQ | minimal | risk |
| Stale-ordering risk | low | needs versioning |

**Use thin when:** payloads would be large/sensitive, you need absolute-latest state, producer API is reliable. **Use fat when:** you want zero callback latency, replayable analytics, resilience to the read API being down — and you add versioning to handle staleness.

### 8.3 Push (webhooks) vs. pull (polling) decision rules

- **Use webhooks when:** events are sparse, freshness matters (seconds), consumers can host a public HTTPS endpoint, and you're integrating across orgs.
- **Avoid webhooks when:** the consumer can't host an endpoint (browsers/mobile), you need very high sustained throughput to one consumer, or you need strict global ordering / exactly-once for free.
- **Hybrid:** webhooks for freshness + a periodic **reconciliation poll** ("list everything changed since T") to catch any dropped/missed deliveries. Belt and suspenders — this is what mature integrations do.

### 8.4 Build vs. buy (delivery infra)

- **Build** if webhooks are core to your product, you need deep control, or you have unusual requirements.
- **Buy** (Svix/Hookdeck/EventBridge/Convoy) if webhooks are a feature, not the product — they give signing, retries, DLQ, dashboards, and subscription management out of the box, saving months.

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → diagnosis table

| Symptom | Likely cause | How to diagnose / fix |
|---|---|---|
| Consumer never receives events | wrong URL, endpoint disabled, firewall, producer can't resolve DNS | check producer's delivery dashboard/logs; test reachability with the provider's "send test event"; check your access logs |
| Signature verification fails for all events | wrong secret, verifying re-serialized body, charset issue, gateway mutating body | log a hash of the raw body at the edge vs. at the verifier; confirm secret; verify on raw bytes before any proxy |
| Verification fails intermittently | secret rotation in progress; multiple `v1` not handled | accept any valid signature among current secrets |
| Replay-window rejections | clock skew, slow retries arriving past tolerance | check NTP; widen tolerance slightly; ensure timestamp from producer not consumer |
| Duplicate side effects (double charge/email) | no dedup or non-idempotent logic | add unique-constraint dedup + idempotent operations |
| Events processed out of order | assumed ordering | add version fence / last-writer-wins |
| Producer retries forever / DLQ growing | consumer returns non-2xx or times out | check consumer P99 latency and status codes; ACK fast + async |
| Missing events (gaps) | producer dropped after retries, or ghost/lost emission | reconciliation poll; add transactional outbox on producer |
| Memory spike on consumer | huge payloads, unbounded body read | enforce body size limit |
| Producer fetching internal IPs | SSRF via malicious subscriber URL | block private/metadata IP ranges |

### 9.2 Actual tools/commands to diagnose

- **Reproduce signature locally:** compute the HMAC with `openssl`:
  ```bash
  # Stripe-style: HMAC-SHA256 over "t.body"
  printf '%s.%s' "$T" "$(cat body.json)" \
    | openssl dgst -sha256 -hmac "$WHSEC" -hex
  ```
- **Inspect exactly what arrived:** point the producer at `https://webhook.site/...` (or RequestBin) to see raw headers/body, then diff against what your server logs.
- **Stripe CLI:** `stripe events list`, `stripe events resend evt_...`, `stripe listen` (local), `stripe trigger <event>`.
- **Provider dashboards** (Stripe, GitHub, Shopify) show per-delivery status, response code, response body, and a **Resend** button — your first stop.
- **Curl a forged-but-correct request** to your endpoint to confirm verification logic in isolation.
- **Capture the raw body hash at the load balancer** and compare with the hash your app verifies — instantly reveals body-mutation bugs.

### 9.3 Real-world incident patterns

- **The re-serialization bug:** a team parsed JSON into a map, re-serialized for verification, and signatures failed for any payload with non-canonical key order/whitespace. Fix: verify on raw bytes. (Extremely common.)
- **The proxy-gzip bug:** a CDN/WAF gzipped or pretty-printed bodies; HMAC over the mutated body failed. Fix: verify before the proxy or pass-through.
- **The 200-on-error bug:** an exception handler returned 200 for everything (to "stop retries"); events were silently lost and never reprocessed, discovered weeks later via reconciliation. Fix: return 5xx on transient failure, 400 only on truly bad input, and ACK only after durable store.
- **The duplicate-charge incident:** no dedup + a retry storm during a consumer outage caused customers to be charged twice. Fix: event-id dedup + idempotent payment ops.
- **The thundering-herd self-DDoS:** many failed deliveries retried at the exact same backoff instant, re-knocking over the recovering consumer. Fix: full jitter.
- **The SSRF disclosure:** a subscriber registered `http://169.254.169.254/...`; the dispatcher fetched cloud metadata creds. Fix: IP allow/deny on delivery targets.

---

## 10. Interview drill

**Q1. What is a webhook and how does it differ from a normal API call?**
Model answer: A webhook is a server→client HTTP callback (a "reverse API"): the producer POSTs to a consumer-hosted URL when an event occurs, instead of the consumer polling. The HTTP machinery is the same; the *direction of the initiating call* is reversed, and the consumer must host a public endpoint.
- Probe: *Why not just poll?* Polling wastes requests and trades latency for cost; webhooks push only when something happens, with seconds-level latency.
- Probe: *Why are webhooks usually server-to-server across orgs, not browser-facing?* Browsers can't host a public endpoint; for browsers you use WebSockets/SSE/push.
- Probe: *What transport guarantees does a webhook give?* At-least-once, best-effort, no ordering by default.

**Q2. What delivery guarantee do webhooks provide, and what does that force on the consumer?**
Model answer: At-least-once. The producer retries on failure, so duplicates happen; the consumer must be idempotent (dedup by event id + idempotent side effects).
- Probe: *Why not exactly-once delivery?* Impossible over an unreliable network (lost ACK ambiguity); you get exactly-once *effect* via at-least-once + idempotency.
- Probe: *Where do duplicates actually come from?* Retries after timeouts/5xx, lost ACKs, producer bugs, redelivery from outbox after a relay crash.

**Q3. Walk me through verifying a webhook signature (the Stripe model).**
Model answer: Read the raw body bytes; parse timestamp `t` and the `v1` HMAC from the signature header; reject if `|now−t|` exceeds the tolerance (e.g. 5 min) for replay protection; recompute `HMAC_SHA256(secret, t + "." + rawBody)`; constant-time compare. Verify before parsing/proxies; support multiple secrets during rotation.
- Probe: *Why HMAC over just TLS?* TLS secures the wire and authenticates the server cert, but your endpoint is publicly POST-able; HMAC proves the sender knows the shared secret.
- Probe: *Why the timestamp inside the signed payload?* Anti-replay: changing `t` breaks the signature, and the tolerance window bounds replayability.
- Probe: *Why constant-time compare?* To avoid timing attacks that reconstruct the signature byte by byte.

**Q4. How do you make a webhook consumer idempotent?**
Model answer: Dedup by the stable event id using a DB unique constraint (`INSERT ... ON CONFLICT DO NOTHING`) so the database, not racy app code, decides first-vs-duplicate; and make the business operation itself idempotent (conditional state transitions / upserts). Both layers, defense in depth.
- Probe: *Why DB constraint over a `synchronized` block?* The block doesn't work across multiple instances; the DB is the single point of truth.
- Probe: *If you use Redis with TTL for dedup, what's the trap?* A very late retry after TTL expiry is treated as new — set TTL longer than the producer's max retry window.

**Q5. Describe a sensible producer retry policy.**
Model answer: Retry on transient failures (5xx, 429, 408, network errors), not on other 4xx (the consumer rejected it on purpose). Use exponential backoff with full jitter up to a cap, bounded by max attempts / max age (e.g. ~3 days). On exhaustion, dead-letter, alert, and auto-disable chronically failing endpoints; provide manual replay.
- Probe: *Why jitter?* To avoid thundering-herd synchronized retries that re-DDoS a recovering consumer.
- Probe: *Why not retry a 400/422?* It's a permanent client-side rejection; retrying wastes resources and never succeeds.

**Q6. Thin vs. fat events — which and when? (senior-signal)**
Model answer: Thin (id only, re-fetch) gives smallest payloads, always-latest truth, and keeps sensitive data out of logs/DLQ, at the cost of a callback round-trip and dependence on the producer's read API. Fat (full state) avoids the callback and survives the read API being down, but risks stale data (needs versioning) and leaks PII into logs. Choose thin for large/sensitive data and strict-freshness needs; fat for zero-latency action and replayability — and version fat payloads.
- Probe: *How do you handle staleness with fat events?* Monotonic version / `updated_at`; last-writer-wins (`WHERE version < :v`).
- Probe: *What's the hybrid?* Send enough to act inline plus the ids to re-fetch if you need absolute latest.

**Q7. How does a producer avoid losing or double-emitting events when it writes to its DB and must notify subscribers? (senior-signal)**
Model answer: Transactional outbox: insert the event into an `outbox` table in the *same* transaction as the business write (atomicity → no ghost/lost events), then a relay (polling with `FOR UPDATE SKIP LOCKED`, or CDC via Debezium) delivers and marks rows sent. Relay crashes give at-least-once emission, so consumers dedup.
- Probe: *Why not just POST after commit in app code?* A crash between commit and POST loses the event; the outbox makes emission durable.
- Probe: *What does CDC buy you?* Drives the relay from the DB log instead of polling, scaling better and lowering latency.

**Q8. Your consumer is slow/flaky. How do you keep the producer happy and not lose events?**
Model answer: ACK fast — verify + dedup + durably store (queue/inbox) then return 200 within ~1–2s; process asynchronously. If the durable store write fails, return 5xx so the producer retries. This decouples slow business logic from the delivery contract.
- Probe: *What if you return 200 then crash before processing?* The event is in your durable inbox/queue; your own worker reprocesses it idempotently — you owned it the moment you stored it.
- Probe: *What if processing keeps failing (poison event)?* Cap consumer-side retries and route to a consumer DLQ for manual handling.

**Q9. What ordering guarantees do webhooks give, and how do you design around the lack of them?**
Model answer: Generally none. Design commutatively, or carry a monotonic version/timestamp and apply last-writer-wins (drop events ≤ applied version), or use thin events + re-fetch latest. Producer-side per-aggregate ordering is possible but risks head-of-line blocking.
- Probe: *Cost of strict ordering?* A single-threaded/per-key serialized dispatcher; one stuck event blocks the stream.
- Probe: *How do you bound head-of-line blocking?* Max-stall timeout that DLQs the stuck event and lets the stream proceed.

**Q10. Webhooks vs. an internal message queue (Kafka/SQS) — when each? (senior-signal)**
Model answer: Webhooks are for cross-organization, internet-facing event delivery where the *consumer hosts an HTTP endpoint*; they're moderate-throughput, retry/backoff/DLQ over HTTP, no shared broker. Kafka/SQS are for *internal* high-throughput event streaming with a broker, partition ordering, and consumer-pull semantics. A common bridge: internal Kafka → a webhook gateway (Svix/EventBridge) → signed outbound HTTP. Pick the queue internally for throughput/ordering; pick webhooks at the org boundary.
- Probe: *Why not expose Kafka to customers directly?* Operational/security coupling, no public HTTP endpoint per consumer, harder auth; webhooks are the standard external contract.
- Probe: *What does a webhook gateway add?* Signing, retries, backoff, DLQ, subscription management, observability — the webhook plumbing, productized.

**Q11. How do you secure the webhook endpoint beyond signature verification?**
Model answer: HTTPS only; verify HMAC over raw body with timestamp tolerance and constant-time compare; store/rotate secrets in a secret manager; size-limit and rate-limit incoming requests; redact sensitive payloads in logs and lock down DLQ access; on the producer, SSRF-guard delivery targets (block private/metadata IPs); and authorize the *referenced resource* against the tenant, not just trust a valid signature.
- Probe: *A valid signature on an event for another tenant's resource — safe?* No; verify identity (signature) *and* authorize the action against your data.

**Q12. How do you test webhooks end-to-end?**
Model answer: Unit-test the verifier with known-good/tampered/expired vectors; idempotency test (same event twice → one effect); contract tests against recorded real payloads; local loop with Stripe CLI / ngrok / webhook.site; chaos tests for retries, reordering, and downstream failure; and a reconciliation job to catch dropped deliveries.
- Probe: *How do you test against schema drift?* Pin recorded fixtures from the provider sandbox and assert your parser; add new versions additively.

---

## 11. Glossary

- **ACK (acknowledgement):** the consumer's signal it received/stored the event; for webhooks, the HTTP 2xx response.
- **API:** a contract letting programs call each other; here, REST over HTTP.
- **Asymmetric crypto:** private key signs, public key verifies; consumer needs only the public key.
- **At-least-once / at-most-once / exactly-once:** delivery counts of 1+, 0–1, and (effectively) 1; webhooks are at-least-once.
- **Backoff (exponential):** retry delays grow multiplicatively up to a cap.
- **Backpressure:** signaling "slow down" (e.g. 429 + Retry-After) when overwhelmed.
- **CDC (Change Data Capture):** streaming a DB's row changes from its transaction log (e.g. Debezium).
- **Circuit breaker:** stops calling a failing dependency after a failure threshold, then probes recovery.
- **Constant-time comparison:** equality check whose duration doesn't depend on where bytes differ; resists timing attacks.
- **DLQ (dead-letter queue):** holding area for undeliverable/unprocessable messages after retries.
- **Dedup (deduplication):** dropping repeated copies of the same logical message by a unique key.
- **EventBridge API Destinations / Svix / Hookdeck / Convoy:** managed webhook-gateway services.
- **Event-carried state transfer:** events carry the data to act on (fat events).
- **Fat event:** payload includes full resource state.
- **Ghost event:** an emitted event for a change that didn't actually commit.
- **Head-of-line blocking:** a stuck front item blocks everything behind it.
- **HMAC:** keyed hash proving authenticity + integrity with a shared secret.
- **HTTP method/status code:** the verb (GET/POST/...) and 3-digit result; 2xx success, 4xx client error (usually no retry), 5xx server error (retry).
- **Idempotent:** repeating the operation yields the same effect as doing it once.
- **Inbox/outbox tables:** consumer dedup store / producer transactional event store.
- **Jitter:** randomized delay added to backoff to avoid synchronized retries.
- **NTP:** protocol that keeps machine clocks synchronized.
- **Polling:** the consumer repeatedly asks the producer for changes.
- **Pub/sub:** publishers emit to topics, subscribers receive, decoupled by a broker.
- **Relay/dispatcher:** background process that turns outbox rows into deliveries.
- **Replay protection:** timestamp + tolerance window (+ dedup) to reject re-sent old requests.
- **Secret rotation:** changing the signing secret, ideally with two active secrets during rollover.
- **`SKIP LOCKED`:** SQL clause making a table a safe concurrent work queue.
- **SSE (Server-Sent Events):** one-way HTTP stream from server to browser.
- **SSRF:** tricking a server into making requests to attacker-chosen targets.
- **Standard Webhooks:** an open spec standardizing webhook signatures/headers.
- **Symmetric crypto:** one shared secret for both signing and verifying (HMAC).
- **Synchronous/asynchronous:** caller blocks for the result / result is decoupled in time.
- **Thin event:** payload is just type + resource id; consumer re-fetches state.
- **Thundering herd:** many clients reacting simultaneously, spiking load.
- **TLS/HTTPS:** encrypts and authenticates the connection at the transport layer.
- **Timing attack:** inferring secrets from operation duration.
- **Transactional outbox:** writing the event in the same txn as the business change for atomic emission.
- **WebSocket:** persistent bidirectional TCP connection, often for browsers.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

```
WEBHOOK = server→client HTTP POST callback ("reverse API"). At-least-once, best-effort, no ordering.

THE FOUR HARD THINGS:  duplicates · ordering · authenticity · failure

CONSUMER MUST:
  1. Read RAW body bytes (verify BEFORE parsing / before any proxy).
  2. Verify HMAC_SHA256(secret, t + "." + rawBody); constant-time compare (MessageDigest.isEqual).
  3. Reject if |now - t| > tolerance (~300s) -> replay protection.
  4. Dedup by event id (DB UNIQUE / ON CONFLICT DO NOTHING).
  5. ACK fast (<~2s, 200) AFTER durable store; process async.
  6. Idempotent business logic (defense in depth). Version-fence for ordering.
  RETURN CODES: 2xx=accepted(no retry) · 400=bad sig(no retry) · 5xx=transient(retry).
  NEVER return 200 on bad signature.

PRODUCER MUST:
  - Transactional OUTBOX (event row in same txn as business write) -> no lost/ghost events.
  - Relay delivers (poll + FOR UPDATE SKIP LOCKED, or CDC/Debezium). Sign with timestamp.
  - Retry on 5xx/429/408/network ONLY; exponential backoff + FULL JITTER; cap (~3 days).
  - DLQ on exhaustion; auto-disable flapping endpoints; manual replay.
  - SSRF-guard target URLs (block 127.0.0.1 / 169.254.169.254 / RFC1918). HTTPS only.
  - Subscription mgmt API; secret rotation with dual secrets; payload versioning (additive).

PAYLOAD:  thin (id, re-fetch latest, small, safe) vs fat (full state, no callback, needs versioning).

DEFAULTS/NUMBERS (verify per vendor):
  Stripe sig tolerance 300s · Stripe retries ~3 days exp backoff · consumer ACK target <1–2s.
  Redis dedup TTL MUST exceed producer max retry window.

WHEN TO USE: cross-org server→server events, seconds latency, consumer hosts public HTTPS endpoint.
WHEN NOT:    browsers/mobile (WebSocket/SSE), internal high-throughput (Kafka/SQS), need strict order/exactly-once free.
HYBRID:      webhooks + periodic reconciliation poll to catch dropped deliveries.

TOOLS: Java Mac(HmacSHA256), SecretKeySpec, MessageDigest.isEqual, HexFormat; HttpClient(timeout!);
       Resilience4j; Stripe CLI (listen/trigger/resend); ngrok; webhook.site; Debezium; Svix/Hookdeck/EventBridge.
```

### Self-test (no answers — recall practice)

1. Explain why exactly-once *delivery* is impossible but exactly-once *effect* is achievable, and exactly what you implement to get the latter.
2. A teammate verifies signatures by deserializing the JSON to a `Map` and re-serializing it before computing the HMAC. Verification fails ~30% of the time. Diagnose and fix.
3. Design the producer side so that a crash *between* committing the business change and notifying subscribers never loses or fabricates an event. Name the pattern and the relay options.
4. Your consumer occasionally double-ships orders. Walk through the two independent layers you'd add to guarantee one shipment per event, and why an in-process lock is insufficient.
5. Give the full retry policy you'd implement on a producer: which status codes are retryable, the backoff/jitter formula, the stop conditions, and what happens on exhaustion.
6. When would you choose fat events over thin, and what additional field must fat events carry to survive out-of-order delivery? Show the SQL that enforces it.
7. List four things TLS does NOT protect against for a public webhook endpoint, and the application-layer control that addresses each.
8. A customer registers `http://169.254.169.254/latest/meta-data/` as their webhook URL. What attack is this, what could leak, and what guard stops it?

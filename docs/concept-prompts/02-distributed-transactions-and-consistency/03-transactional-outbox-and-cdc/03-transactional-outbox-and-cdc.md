# Transactional Outbox & Change Data Capture (CDC)

> An exhaustive engineering-handbook chapter on reliably propagating state changes from a database to the rest of a distributed system — covering the dual-write problem, the transactional outbox pattern, Change Data Capture, Debezium + Kafka Connect, the inbox pattern, idempotency, ordering, and exactly-once concerns.

---

## 1. Overview & where it fits

### 1.1 What it is

The **transactional outbox** and **Change Data Capture (CDC)** are two complementary techniques that solve the same fundamental problem: **how do you reliably tell other systems about a change you just made to your database, without losing messages and without leaking inconsistent state?**

- A **transactional outbox** is a pattern in which, instead of publishing an event directly to a message broker (Kafka, RabbitMQ, SNS/SQS, etc.) as part of your business logic, you write the event as a row into a special **outbox table** *inside the same local database transaction* that performs your business write. A separate process — the **message relay** (also called the *publisher* or *forwarder*) — later reads rows from that outbox table and publishes them to the broker.

- **Change Data Capture (CDC)** is a broader technique for observing and streaming the *changes* made to a database — inserts, updates, deletes — typically by reading the database's internal **transaction log** (the write-ahead log / replication log). CDC turns a database into a *source of an event stream*: every committed change becomes an event.

The two intersect: the most robust, modern way to implement the relay in the outbox pattern is to use **log-based CDC** (e.g., **Debezium**) to tail the outbox table's changes and publish them, rather than polling the table with SQL.

### 1.2 The problem it solves

In a microservices or event-driven architecture, a single user action frequently needs to do two things atomically *from the user's perspective*:

1. **Change local state** — e.g., insert an `Order` row into the `orders` table.
2. **Notify the outside world** — e.g., publish an `OrderCreated` event to Kafka so the shipping service, billing service, and analytics pipeline can react.

The naive implementation does both directly:

```java
// ANTI-PATTERN: the dual write
orderRepository.save(order);          // 1. write to DB
kafkaTemplate.send("orders", event);  // 2. write to broker
```

This is the **dual-write problem**: you are writing to two *independent* systems (the database and the broker) with two *independent* commit points, and there is no single transaction spanning both. Any crash, network blip, or timeout *between* the two writes — or a partial failure of either — leaves the system **inconsistent**: the order exists but no one was told, or the event was published but the order rolled back. (Section 2 dissects exactly why every variation of this is broken.)

The outbox pattern fixes this by collapsing the two writes into **one local ACID transaction** (the DB write and the outbox-row write commit together or not at all), then doing the broker publish **asynchronously and idempotently** afterward.

> **ACID** = Atomicity, Consistency, Isolation, Durability — the guarantees a traditional relational database transaction provides. *Atomicity* means a transaction either fully commits or fully rolls back; there is no halfway state. This is the property the outbox pattern exploits.

### 1.3 When you reach for it

Reach for the outbox + CDC pattern when **all** of the following hold:

- You have a service that owns a database **and** needs to emit events/messages to other services or systems.
- You need **at-least-once delivery** of those events with **no silent message loss**, even across crashes.
- You cannot (or do not want to) rely on a distributed transaction (2PC/XA) spanning the DB and the broker — which is the usual case, because most modern brokers (Kafka) don't support XA well, and 2PC is operationally painful (Section 8).
- You can tolerate **eventual consistency**: downstream systems learn about the change a short time (milliseconds to seconds) after the local commit, not synchronously.

You do **not** need it when: the two systems you're updating are actually the *same* database (just use one transaction); or when losing the occasional notification is genuinely acceptable (rare in serious systems); or when you have a true single-system transaction available.

### 1.4 The one-paragraph mental model

> **Treat "I need to tell the world" as just another row I write to my own database, inside the same transaction as the business change. Because it's in the same transaction, it's atomic — it commits if and only if the business change commits. Then, separately and asynchronously, a relay drains those rows and publishes them to the broker, retrying until it succeeds, marking each as done. The database's own durable, ordered transaction log becomes the single source of truth for "what happened, in what order," and CDC is the mechanism for reading that log. The cost is that delivery is at-least-once (duplicates happen) and eventually consistent (a small delay), so consumers must be idempotent.**

---

## 2. Foundations from first principles

This section builds the problem up from zero, defining every term as it appears.

### 2.1 The setting: services, databases, and brokers

- A **service** is a deployable unit of business logic (e.g., the Order Service). In a microservices architecture each service typically owns its **own private database** — no other service reads or writes it directly. This is the *database-per-service* pattern, and it's what forces services to communicate via APIs or events rather than shared tables.

- A **message broker** is middleware that accepts messages from *producers* and delivers them to *consumers*, decoupling the two in time and space. Examples: **Apache Kafka** (a distributed, durable, append-only log), **RabbitMQ** (a traditional AMQP message queue/router), **AWS SNS/SQS** (cloud pub/sub and queues), **Google Pub/Sub**, **NATS**. A broker lets the Order Service emit an `OrderCreated` event without knowing or caring who consumes it.

  > **Kafka, briefly:** Kafka stores messages in **topics**, each split into **partitions**. A partition is an ordered, append-only sequence of records; each record has an **offset** (its position). Ordering is guaranteed *only within a partition*. Records are durable (written to disk and replicated) and retained for a configurable time, so consumers can re-read. This append-only-log design is itself very similar to a database transaction log, which is why Kafka and CDC fit together so naturally.

- An **event** (or *domain event*) is an immutable fact stating that something happened: `OrderCreated`, `PaymentCaptured`, `InventoryReserved`. Events are usually published to a broker so other services can react.

### 2.2 The dual-write problem in detail

Consider the Order Service handling "place an order." It must:

1. Persist the order to its database (so the order truly exists and survives a restart).
2. Publish `OrderCreated` to Kafka (so shipping/billing react).

Naively:

```java
@Transactional
public void placeOrder(Order order) {
    orderRepository.save(order);                 // DB write
    kafkaTemplate.send("orders", toEvent(order));// broker write
}
```

A common but **false** belief is that `@Transactional` saves you. It does **not** make the Kafka send transactional with the DB — `@Transactional` only governs the database transaction. The Kafka send is a network call to a completely separate system. Let's enumerate the failure interleavings.

**Failure case A — crash after DB commit, before broker send.**
The order is saved and committed. The process crashes (or the broker is down) before/while sending. Result: **the order exists, but no event was ever published.** Shipping never ships. This is a **lost update / lost event** — silent and corrosive.

**Failure case B — broker send succeeds, DB transaction rolls back.**
Suppose you send to Kafka first, then the DB commit fails (constraint violation, deadlock, disk full). Result: **the event was published for an order that does not exist.** Downstream services act on a phantom order. This is a **phantom/ghost event**.

**Failure case C — broker send appears to fail but actually succeeded.**
You send to Kafka; the ack times out due to a network partition; your code treats it as failed and retries or aborts. But the broker *did* persist the message. Result: **duplicate events**, or inconsistent reasoning about whether the event exists. The fundamental issue is the **two generals / unreliable acknowledgment** problem: you can never be 100% certain a remote write happened if the acknowledgment can be lost.

  > **Two Generals Problem:** a classic impossibility result — two parties communicating over an unreliable channel can never reach *guaranteed* common knowledge of an agreement, because any acknowledgment can itself be lost, requiring an acknowledgment of the acknowledgment, ad infinitum. This is why "did my message get through?" is fundamentally unanswerable with certainty over a network, and why at-least-once + idempotency is the pragmatic answer.

**Why ordering the writes doesn't fix it.** Whichever you do first, the *other* can fail independently. There is no ordering of two independent commits that yields atomicity. The only ways to get atomicity across two systems are:

- A **distributed transaction** spanning both (2PC/XA) — see below, usually rejected.
- Make one of the two writes *derive from* the other so there's really only one commit point — which is exactly what the outbox does.

### 2.3 Why not just use a distributed transaction (2PC/XA)?

**Two-Phase Commit (2PC)** is a protocol to commit a transaction atomically across multiple **resource managers** (e.g., a DB and a broker). A **transaction coordinator** runs two phases:

1. **Prepare:** ask every participant "can you commit?" Each does the work, persists it tentatively, locks resources, and votes *yes* or *no*.
2. **Commit:** if all voted yes, tell everyone to commit; otherwise tell everyone to abort.

  > **XA** is the standard (X/Open) API/interface that lets a transaction manager coordinate 2PC across heterogeneous resources (databases, JMS brokers). In Java, JTA (Java Transaction API) exposes this.

2PC gives true atomicity but is widely avoided here because:

- **Blocking & availability:** if the coordinator crashes after prepare but before commit, participants hold locks indefinitely (the *in-doubt* state), reducing availability. 2PC is not partition-tolerant.
- **Latency & throughput:** two network round-trips plus disk forces per participant; locks held longer; lower throughput.
- **Broker support:** **Kafka does not support XA/2PC** as a participant. Kafka's own transactions are *intra-Kafka* (atomic across Kafka topics + offsets), not across Kafka and your DB. So 2PC simply isn't on the table for Kafka. Some JMS brokers (ActiveMQ) do support XA, but you inherit all the operational pain.
- **Operational complexity:** recovery logs, heuristic outcomes, coordinator HA — a lot of machinery.

The outbox pattern is, in effect, a way to get *practical* reliability (at-least-once + idempotency) without paying the 2PC tax, accepting eventual consistency in return.

### 2.4 The transactional outbox: the core idea

Instead of two independent commits, make the *intent to publish* part of the **same** local transaction as the business change:

```
BEGIN TRANSACTION
  INSERT/UPDATE business tables        -- the actual state change
  INSERT INTO outbox (...) VALUES (...) -- a row describing the event to publish
COMMIT
```

Because both writes are in one transaction against **one** database, atomicity is guaranteed by the DB: either both the order *and* the outbox row exist, or neither does. **The lost-event and phantom-event cases are now impossible.**

Then, **asynchronously**, a **message relay** reads new outbox rows and publishes them to the broker:

```
LOOP:
  read unpublished outbox rows (in order)
  for each: publish to broker
  on success: mark row as published (or delete it)
  on failure: leave it; retry next iteration
```

Key properties:

- **No message loss:** the row is durably committed before the relay even looks at it. If the relay or process crashes, the row is still there to be picked up later.
- **At-least-once delivery:** because publish-then-mark is itself two steps that can fail between (crash after publish, before mark), the relay may republish a row it already published. Hence consumers must be **idempotent** (Section 2.7).
- **Eventual consistency:** there's a delay between the local commit and the broker publish. Usually small (ms to low seconds), but nonzero.

### 2.5 Two ways to implement the relay

There are two dominant relay strategies. Section 6 has full code for both; here's the conceptual split.

**(a) Polling publisher.** The relay periodically runs `SELECT ... FROM outbox WHERE published = false ORDER BY id` and publishes each row, then marks/deletes it. Simple, works with any database, no special privileges. Downsides: polling latency and load, lock contention if multiple relays poll, and you must manage ordering and cleanup yourself.

**(b) Log-tailing (CDC).** Instead of polling the table with SQL, the relay reads the database's **transaction log** (WAL/binlog) directly. Every committed `INSERT` into the outbox table appears in the log as a change event, which the relay (e.g., Debezium) converts into a broker message. No polling load on the table, very low latency, exact commit order. This is the modern default for high-scale systems. (CDC is explained from scratch in Section 2.6.)

### 2.6 Change Data Capture (CDC) from scratch

#### 2.6.1 The write-ahead log (WAL)

Relational databases don't write your changes straight to the data files on every commit — that would be slow and unsafe. Instead they use **write-ahead logging**:

> **Write-Ahead Log (WAL):** before any change is applied to the actual data pages, a record describing the change is first appended to a sequential, append-only log on durable storage and flushed (fsync'd) to disk. Only after the log record is durable is the commit acknowledged. The data pages themselves can be updated lazily later. This is the *WAL rule*: "log first, then data." It is the foundation of crash recovery — on restart, the DB replays the log to bring data files up to date (redo) and undoes uncommitted work (undo).

Different databases name this differently:

- **PostgreSQL:** the **WAL** (write-ahead log), stored as segment files. PostgreSQL exposes it for **logical replication** via *replication slots* and *logical decoding plugins* (e.g., `pgoutput`).
- **MySQL / MariaDB:** the **binary log (binlog)** — a record of all data-modifying statements/row changes, originally for replication and point-in-time recovery. In **row-based** mode, it logs the actual before/after row images.
- **SQL Server:** the **transaction log**, with built-in CDC and Change Tracking features.
- **Oracle:** **redo logs**, read via LogMiner or Oracle GoldenGate / XStream.
- **MongoDB:** the **oplog** (operations log), a capped collection used for replication; *change streams* expose it as an API.

#### 2.6.2 What CDC does

**Change Data Capture** taps this log to produce a stream of change events. There are two broad families:

- **Query-based / poll-based CDC:** periodically query tables for rows changed since last poll, typically using a monotonically increasing column (an `updated_at` timestamp or a version/sequence column), or trigger-populated audit tables. Simple, DB-agnostic, but: misses deletes (the row is gone), misses intermediate states between polls, adds query load, and timestamp-based detection is racy. (Polling the *outbox* table, Section 2.5a, is a special case of this where you've engineered an explicit "changes" table.)

- **Log-based CDC:** read the WAL/binlog directly and decode it into change events. This captures **every** committed change exactly once in commit order, including deletes and all intermediate updates, with near-zero impact on the source tables (it reads the log, not the tables). This is what Debezium does. Downsides: requires DB configuration and privileges (enabling logical replication / row-based binlog), is more vendor-specific, and log retention must be managed.

#### 2.6.3 The anatomy of a CDC change event

A log-based CDC event for a row change typically contains:

- **Operation type:** create (`c`/insert), update (`u`), delete (`d`), or read/snapshot (`r`).
- **`before` image:** the row's column values *before* the change (for updates/deletes; requires the DB to log full row images — e.g., MySQL `binlog_row_image=FULL`, PostgreSQL `REPLICA IDENTITY FULL`).
- **`after` image:** the row's values *after* the change (for inserts/updates).
- **Source metadata:** the table/schema name, transaction id, log position (LSN/GTID/offset), timestamp, server id.
- **Position/offset:** so the consumer can record how far it has read and resume after a restart (the CDC connector's *offset*).

  > **LSN (Log Sequence Number):** PostgreSQL's monotonically increasing position within the WAL — effectively "byte offset in the log." Used to track replication progress.
  > **GTID (Global Transaction Identifier):** MySQL's globally unique id for each transaction, enabling consistent failover and resumption.

### 2.7 Idempotency, the inbox pattern, and exactly-once

Because outbox + CDC gives **at-least-once** delivery, a consumer can see the same event more than once. Correctness therefore requires **idempotency**.

> **Idempotency:** an operation is *idempotent* if performing it multiple times has the same effect as performing it once. `SET balance = 100` is idempotent; `balance = balance + 100` is not. Designing consumers so that reprocessing a duplicate event is harmless is the cornerstone of reliable event-driven systems.

Ways to achieve idempotent consumption:

1. **Naturally idempotent operations.** Upserts keyed by the event's business id; "set to value X" rather than "add X." Reprocessing just re-sets the same value.

2. **The inbox pattern (dedup table).** The mirror image of the outbox. Each event carries a unique **event id** (a UUID or `(aggregate, sequence)`). The consumer, in the **same transaction** as its business write, inserts the event id into an **inbox** (or *processed_messages*) table with a unique constraint. If the insert fails because the id already exists, the event is a duplicate and is skipped. This makes processing **effectively-once**:

   ```
   BEGIN
     INSERT INTO inbox(event_id) VALUES (?)  -- unique constraint; fails on dup
     ... apply business change ...
   COMMIT
   ```
   If the row already exists, the whole transaction is abandoned (the event already took effect).

3. **Idempotency keys at the API/store layer.** Many sinks (payment gateways, etc.) accept an *idempotency key* to dedupe on their side.

**"Exactly-once" — what it really means.** True exactly-once *delivery* over an unreliable network is impossible (the Two Generals problem). What systems actually provide is **exactly-once *processing* / effectively-once semantics**: at-least-once delivery + idempotent/deduplicating processing = each event's *effect* is applied exactly once. Kafka's "exactly-once semantics (EOS)" is a special case that holds *within Kafka* (consume-transform-produce within Kafka topics using transactions and offset commits), not across Kafka and your external database — so you still need idempotency at the boundary to your DB.

### 2.8 Ordering

Many consumers care about order: `OrderCreated` must precede `OrderShipped`. Where does ordering come from and where can it break?

- The **DB transaction log is totally ordered** by commit. So CDC reading the outbox produces events in commit order. Good.
- **Kafka preserves order only within a partition.** If you publish related events to different partitions, ordering is lost across them. The fix: **partition by a key** (e.g., `orderId` or `aggregateId`) so all events for one aggregate land in the same partition and stay ordered relative to each other. You usually *don't* need global ordering — only per-aggregate ordering.
- A **polling relay with multiple workers** can reorder events if two workers grab rows concurrently. Single-threaded relay, ordered fetch, or per-aggregate locking is needed to preserve order.
- **Producer retries can reorder** on Kafka unless you enable idempotent producers / cap in-flight requests (Section 6/7).

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle, control flow, data flow, and state transitions for both the polling relay and the CDC/Debezium relay.

### 3.1 The write path (common to both relay types)

Step by step, what happens when the application handles a command that produces an event:

1. **Begin local transaction** on the service's database (e.g., via Spring `@Transactional`, the JDBC `Connection.setAutoCommit(false)`, or a programmatic transaction).
2. **Apply the business change** — insert/update the domain tables (e.g., `INSERT INTO orders ...`).
3. **Build the event payload** in memory — serialize the domain event to JSON/Avro/Protobuf, assign it a unique **event id** (UUID), capture the **aggregate id** (for partitioning/ordering), event **type**, **timestamp**, and a **schema version**.
4. **Insert the outbox row** — `INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at) VALUES (...)`. Same connection, same transaction.
5. **Commit.** The DB writes both the business change and the outbox row to the WAL, fsyncs, and acknowledges. *Atomicity is now guaranteed:* both rows are durable, or neither is.
6. **Return success** to the caller. At this point the event is *guaranteed* to eventually be published, but has not yet been.

State of the outbox row after step 6: **PENDING / unpublished.**

### 3.2 The read/publish path — polling relay

The polling relay is a loop, usually one of several competing instances (with safeguards). Control + data flow:

1. **Poll:** every *N* milliseconds, run a query for unpublished rows in order, with a batch limit:
   ```sql
   SELECT * FROM outbox
   WHERE published = false
   ORDER BY id ASC
   LIMIT 100
   FOR UPDATE SKIP LOCKED;   -- claim rows, skip rows other workers locked
   ```
   > **`FOR UPDATE SKIP LOCKED`:** a row-locking clause (PostgreSQL, MySQL 8+) that locks the selected rows for this transaction and *skips* any rows already locked by another transaction instead of blocking. This lets multiple relay workers safely pull *different* batches concurrently without stepping on each other — the standard trick for a competing-consumers polling relay.
2. **Publish each row** to the broker, keyed by aggregate id for partition ordering. Wait for the broker ack (e.g., Kafka `acks=all`).
3. **Mark/delete** the row: `UPDATE outbox SET published = true, published_at = now() WHERE id = ?` or `DELETE FROM outbox WHERE id = ?`. Commit this transaction.
4. **Repeat.**

**Failure handling & the at-least-once window.** The dangerous gap is between step 2 (publish succeeded at the broker) and step 3 (mark done). If the relay crashes there, on restart the row is still `published=false`, so it gets republished → **duplicate**. This is unavoidable without 2PC, and is precisely why consumers must be idempotent. (You can shrink the window but never eliminate it.)

**State machine of an outbox row (polling):**

```
PENDING --(claimed by worker, FOR UPDATE SKIP LOCKED)--> IN-FLIGHT
IN-FLIGHT --(publish ok + mark)--> PUBLISHED (or DELETED)
IN-FLIGHT --(publish fails / crash)--> PENDING (lock released on rollback)
```

### 3.3 The read/publish path — log-based CDC relay (Debezium)

Here the "relay" is a CDC connector tailing the DB log. Let's first define the players.

> **Kafka Connect:** a framework and runtime (part of Apache Kafka) for streaming data between Kafka and external systems via reusable **connectors**. **Source connectors** pull data *into* Kafka; **sink connectors** push data *out*. Connect runs as a cluster of **workers**; each connector is split into **tasks** for parallelism. Connect manages offset storage, config, restarts, and rebalancing.

> **Debezium:** an open-source CDC platform implemented primarily as a set of Kafka Connect **source connectors** (for PostgreSQL, MySQL, MongoDB, SQL Server, Oracle, Db2, Cassandra, Vitess, Spanner, etc.). A Debezium connector reads the source DB's log, converts each change into a structured event, and writes it to a Kafka topic. Debezium can also run embedded (the *Debezium Engine* library) without Kafka Connect.

End-to-end flow for the outbox-via-Debezium approach:

1. **App commits** the business row + outbox row in one transaction (Section 3.1). The change lands in the WAL/binlog.
2. **Debezium connector** (running in a Kafka Connect worker) is connected to the DB via a **logical replication slot** (Postgres) or as a **replica/binlog reader** (MySQL). It continuously reads new log entries.
3. For each committed change to the **outbox table**, Debezium decodes a change event with `before`/`after`/`op`/`source` (Section 2.6.3).
4. Debezium applies **transforms (SMTs)** — for the outbox use case, the **Outbox Event Router** SMT (`io.debezium.transforms.outbox.EventRouter`) reshapes the raw change event: it pulls the `payload` column out as the message value, uses the `aggregate_id` column as the Kafka message **key** (preserving per-aggregate order), routes to a topic derived from `aggregate_type`, and copies metadata into headers.
   > **SMT (Single Message Transform):** a lightweight, stateless function Kafka Connect applies to each record as it flows through, e.g., renaming fields, routing topics, masking data. SMTs are chained in the connector config.
5. Debezium **produces** the resulting message to Kafka (via Connect's producer, typically `acks=all`, idempotent).
6. Debezium **commits its offset** — i.e., records the log position (LSN/GTID/binlog offset) it has processed, in Connect's offset store (a Kafka topic). On restart it resumes from there.
7. **Postgres advances the replication slot** based on Debezium's confirmed flush position, allowing the DB to recycle old WAL segments. (Critical: if Debezium stops, the slot pins WAL and disk fills — Section 9.)

**Snapshotting.** When a Debezium connector starts for the first time (or is told to), it can take a **snapshot**: a consistent read of the existing table contents (emitted as `op=r` "read" events) so consumers get the current state before the live stream of changes begins. For an outbox table you usually configure snapshot mode to *not* re-emit historical outbox rows (or you keep the outbox small by deleting/retaining), since old outbox rows have typically already been published.

**At-least-once in CDC.** Debezium guarantees at-least-once: after a crash it resumes from the last committed offset and may re-emit a few events processed but not yet offset-committed. Same idempotency requirement as the polling relay.

**State machine (CDC outbox row):**

```
COMMITTED (in WAL) --> DECODED by Debezium --> ROUTED by SMT --> PRODUCED to Kafka --> OFFSET COMMITTED
(crash between PRODUCED and OFFSET COMMITTED => re-PRODUCED on restart => duplicate)
```

### 3.4 Data flow diagram (textual)

```
        ┌─────────────────────────────────────────────────────────┐
        │                   Order Service                          │
        │  @Transactional {                                        │
        │     INSERT orders ...        ─┐                          │
        │     INSERT outbox ...         │ one local DB transaction │
        │  } COMMIT                    ─┘                          │
        └───────────────┬─────────────────────────────────────────┘
                        │ writes to WAL/binlog (atomic)
                        ▼
                ┌───────────────┐
                │   Database    │  orders | outbox tables; WAL/binlog
                └───────┬───────┘
       polling SELECT   │   log-based (replication slot / binlog)
        ┌───────────────┴───────────────┐
        ▼                                ▼
 ┌─────────────┐                 ┌──────────────────────┐
 │ Polling     │                 │ Debezium (Kafka      │
 │ Relay loop  │                 │ Connect source conn.) │
 └──────┬──────┘                 └──────────┬───────────┘
        │ publish (key=aggregateId)         │ EventRouter SMT + produce
        ▼                                   ▼
              ┌──────────────────────────────────┐
              │            Apache Kafka            │
              │  topic "orders" (partitioned)      │
              └──────────────┬────────────────────┘
                             ▼ at-least-once
        ┌──────────────────────────────────────────────┐
        │  Consumer (Shipping Service)                   │
        │  @Transactional {                              │
        │     INSERT inbox(event_id)  -- dedup           │
        │     ... apply business change ...              │
        │  } COMMIT                                       │
        └──────────────────────────────────────────────┘
```

### 3.5 Lifecycle summary

1. **Write:** business change + outbox row, atomically committed.
2. **Capture:** relay (poll or CDC) observes the new outbox row.
3. **Publish:** event sent to broker, keyed for ordering, with retries.
4. **Acknowledge/advance:** row marked published / offset committed / slot advanced.
5. **Consume:** consumer dedupes via inbox/idempotency and applies the effect.
6. **Cleanup:** old outbox rows deleted/retained; old log segments recycled.

---

## 4. The complete toolkit

### 4.1 Outbox table schema — recommended columns

| Column | Type (Postgres) | Purpose | Notes / defaults |
|---|---|---|---|
| `id` | `uuid` or `bigserial` | Primary key & unique event id | UUID is convenient as the broker message id for dedup. `bigserial` gives natural ordering. |
| `aggregate_type` | `varchar` | The kind of aggregate (e.g., `Order`) | Used to derive topic name in EventRouter. |
| `aggregate_id` | `varchar` | The aggregate's id (e.g., order id) | Used as the **Kafka message key** → per-aggregate ordering. |
| `event_type` | `varchar` | Event name (`OrderCreated`) | Often placed in a header. |
| `payload` | `jsonb` / `text` / `bytea` | Serialized event body | `jsonb` for JSON; `bytea` for Avro/Protobuf. |
| `headers` | `jsonb` | Optional metadata (trace id, schema version) | Propagate tracing/context. |
| `created_at` | `timestamptz` | Creation time | `default now()`. |
| `published` | `boolean` | (Polling only) sent flag | `default false`. Not needed for CDC. |
| `published_at` | `timestamptz` | (Polling only) when sent | Nullable. |

**Indexes:** for polling, an index on `(published, id)` (partial: `WHERE published = false`) speeds the claim query. For CDC you generally need *no* index for reading (Debezium reads the log), but keep a PK.

Debezium's Outbox Event Router expects (by default) columns named `id`, `aggregatetype`, `aggregateid`, `type`, `payload` (all configurable via `table.field.event.*` options).

### 4.2 Debezium connector — key configuration (PostgreSQL example)

| Property | Purpose | Typical / default value |
|---|---|---|
| `connector.class` | Which connector | `io.debezium.connector.postgresql.PostgresConnector` |
| `database.hostname/port/user/password/dbname` | DB connection | — |
| `plugin.name` | Logical decoding plugin | `pgoutput` (built into PG 10+; no extension needed) |
| `slot.name` | Replication slot name | e.g., `debezium`; must be unique per connector |
| `publication.name` | PG publication used | `dbz_publication` (auto-created if perms allow) |
| `table.include.list` | Which tables to capture | `public.outbox` for the outbox use case |
| `topic.prefix` | Logical server/topic namespace | required (e.g., `inventory`) |
| `snapshot.mode` | Initial snapshot behavior | `initial` (default); `never`/`no_data` often used for outbox |
| `heartbeat.interval.ms` | Emit heartbeats to advance slot on idle tables | `0` (off) by default — **set it** for low-traffic outbox to avoid WAL growth |
| `decimal.handling.mode`, `time.precision.mode` | Type mapping | defaults vary |
| `max.batch.size` / `max.queue.size` | Internal batching | `2048` / `8192` defaults |
| `tombstones.on.delete` | Emit null tombstone on delete | `true` default |
| `transforms` | SMT chain | `outbox` |
| `transforms.outbox.type` | The outbox SMT | `io.debezium.transforms.outbox.EventRouter` |
| `transforms.outbox.route.by.field` | Field deriving the topic | `aggregatetype` (default) |
| `transforms.outbox.table.field.event.key` | Field used as Kafka key | `aggregateid` |
| `transforms.outbox.table.field.event.payload` | Field used as value | `payload` |
| `transforms.outbox.route.topic.replacement` | Topic name template | e.g., `outbox.event.${routedByValue}` |

MySQL connector differs: `connector.class=io.debezium.connector.mysql.MySqlConnector`; requires `binlog_format=ROW`, `binlog_row_image=FULL`, a `server-id`, and the user needs `REPLICATION SLAVE`, `REPLICATION CLIENT`, `SELECT` privileges. It also uses a Kafka **schema-history** topic to track DDL.

### 4.3 Kafka producer settings relevant to the relay

| Setting | Purpose | Recommended for outbox |
|---|---|---|
| `acks` | Durability of produce | `all` (wait for in-sync replicas) |
| `enable.idempotence` | Producer dedup + ordering | `true` (default in modern Kafka) |
| `max.in.flight.requests.per.connection` | Concurrency vs ordering | `≤5` with idempotence (keeps order); `1` if no idempotence |
| `retries` | Auto-retry transient failures | high / `Integer.MAX_VALUE` with idempotence |
| `compression.type` | Throughput | `lz4`/`zstd` for large payloads |
| `linger.ms` / `batch.size` | Batching | tune for throughput |

### 4.4 PostgreSQL CDC plumbing — commands & flags

| Item | What it does |
|---|---|
| `wal_level = logical` (postgresql.conf) | Enables logical decoding (CDC). Requires restart. Default is `replica`. |
| `max_replication_slots`, `max_wal_senders` | Must be ≥ number of CDC connectors. |
| `CREATE PUBLICATION dbz_publication FOR TABLE outbox;` | Defines which tables are published for logical replication. |
| `SELECT * FROM pg_replication_slots;` | Inspect slots, their `active` state, and `confirmed_flush_lsn`. |
| `SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) FROM pg_replication_slots;` | **Retained WAL bytes per slot — your #1 health metric.** |
| `ALTER TABLE outbox REPLICA IDENTITY FULL;` | Log full old-row image (needed if you rely on `before` for updates/deletes). |
| `SELECT pg_drop_replication_slot('name');` | Drop a stale slot that's pinning WAL (last resort). |

### 4.5 Library / framework toolkit (JVM)

| Tool | Role |
|---|---|
| **Spring `@Transactional`** | Wraps business + outbox write in one local tx. |
| **Spring Data JPA / JDBC** | Persist domain + outbox entities. |
| **Debezium Outbox Quarkus extension** (`quarkus-debezium-outbox`) | Auto-creates outbox table & writes outbox rows on `@Observes ExportedEvent`. |
| **Debezium Embedded Engine** (`io.debezium.embedded`) | Run CDC in-process without Kafka Connect; you supply a handler. |
| **Eventuate Tram** | A library implementing transactional messaging (outbox + CDC) for Java/Spring. |
| **Spring Modulith `@ApplicationModuleListener` + event publication registry** | Spring's built-in outbox-like event publication store. |
| **Axon Framework** | Event-sourcing/CQRS framework with its own reliable event store/publishing. |
| **kafka-clients producer** | Used by the polling relay to publish. |

---

## 5. Code examples by use case

### 5.1 Example 1 — Spring Boot order service writing the outbox atomically

```java
// Outbox entity (JPA). One row per event to publish.
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    private UUID id;                 // also used as the broker message id for dedup
    private String aggregateType;    // e.g. "Order" -> derives the topic
    private String aggregateId;      // e.g. order id -> Kafka message key (ordering)
    private String eventType;        // e.g. "OrderCreated"
    @Column(columnDefinition = "jsonb")
    private String payload;          // serialized event JSON
    private Instant createdAt;
    // getters/setters/constructors omitted
}
```

```java
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OrderService(OrderRepository orders, OutboxRepository outbox, ObjectMapper mapper) {
        this.orders = orders; this.outbox = outbox; this.mapper = mapper;
    }

    // CRITICAL: both writes share ONE local transaction. Atomic by construction.
    @Transactional
    public Order placeOrder(PlaceOrderCommand cmd) {
        Order order = new Order(cmd.customerId(), cmd.lines(), OrderStatus.PLACED);
        orders.save(order);                                  // business write

        OrderCreated event = OrderCreated.from(order);       // build domain event
        OutboxEvent row = new OutboxEvent(
                UUID.randomUUID(),                           // unique event id
                "Order",                                     // aggregateType
                order.getId().toString(),                    // aggregateId (ordering key)
                "OrderCreated",
                toJson(event),
                Instant.now());
        outbox.save(row);                                    // outbox write — SAME tx
        return order;
        // On COMMIT both rows are durable; on any failure both roll back.
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
```

The application code never touches Kafka. There is no dual write. Publishing is entirely the relay's job.

### 5.2 Example 2 — A robust polling-publisher relay (Java + JDBC + Kafka)

```java
public class PollingRelay {

    private final DataSource ds;
    private final KafkaProducer<String, byte[]> producer;
    private static final int BATCH = 200;

    public PollingRelay(DataSource ds, KafkaProducer<String, byte[]> producer) {
        this.ds = ds; this.producer = producer;
    }

    /** Runs every poll interval (e.g., scheduled at 200ms). */
    public void drainOnce() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);

            // Claim a batch of unpublished rows in order. SKIP LOCKED lets multiple
            // relay instances run safely without blocking each other.
            String claim = """
                SELECT id, aggregate_type, aggregate_id, event_type, payload
                FROM outbox
                WHERE published = false
                ORDER BY id ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;

            List<Row> batch = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(claim)) {
                ps.setInt(1, BATCH);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        batch.add(new Row(
                            (UUID) rs.getObject("id"),
                            rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("event_type"),
                            rs.getBytes("payload")));
                    }
                }
            }
            if (batch.isEmpty()) { c.commit(); return; }

            // Publish each row. Key = aggregateId => per-aggregate ordering on Kafka.
            // We send synchronously here for simplicity; batch+future for throughput.
            List<Future<RecordMetadata>> acks = new ArrayList<>();
            for (Row r : batch) {
                ProducerRecord<String, byte[]> rec = new ProducerRecord<>(
                        topicFor(r.aggregateType()), r.aggregateId(), r.payload());
                rec.headers().add("eventId", r.id().toString().getBytes(UTF_8));
                rec.headers().add("eventType", r.eventType().getBytes(UTF_8));
                acks.add(producer.send(rec));
            }
            producer.flush();
            for (Future<RecordMetadata> f : acks) f.get(); // throws if any send failed

            // Only after ALL sends are acked do we mark them published, then commit.
            // The lock from FOR UPDATE is still held, so no other worker touched them.
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE outbox SET published = true, published_at = now() WHERE id = ?")) {
                for (Row r : batch) { upd.setObject(1, r.id()); upd.addBatch(); }
                upd.executeBatch();
            }
            c.commit();
            // DANGER WINDOW: if we crash AFTER producer ack but BEFORE this commit,
            // the rows stay published=false and get re-sent -> duplicates.
            // => consumers MUST be idempotent.
        }
    }

    private String topicFor(String aggregateType) {
        return "outbox.event." + aggregateType;
    }

    record Row(UUID id, String aggregateType, String aggregateId,
               String eventType, byte[] payload) {}
}
```

Notes worth absorbing: ordering is preserved by `ORDER BY id` + keying by `aggregateId`; concurrency is safe via `FOR UPDATE SKIP LOCKED`; the unavoidable at-least-once window is between the producer ack and the DB commit.

### 5.3 Example 3 — Idempotent consumer using the inbox pattern (Spring Kafka)

```java
@Component
public class ShippingEventConsumer {

    private final InboxRepository inbox;
    private final ShipmentService shipments;

    public ShippingEventConsumer(InboxRepository inbox, ShipmentService shipments) {
        this.inbox = inbox; this.shipments = shipments;
    }

    @KafkaListener(topics = "outbox.event.Order", groupId = "shipping")
    @Transactional   // local DB tx wrapping dedup + business write
    public void onMessage(ConsumerRecord<String, byte[]> rec) {
        String eventId = header(rec, "eventId");

        // Dedup: unique constraint on inbox.event_id. If it already exists,
        // this insert throws -> we treat the event as already processed and skip.
        try {
            inbox.insert(eventId, Instant.now());
        } catch (DuplicateKeyException dup) {
            return; // duplicate delivery; effect already applied. Safe to ack & move on.
        }

        OrderCreated event = parse(rec.value());
        shipments.createShipmentFor(event.orderId(), event.lines()); // business effect
        // COMMIT: inbox row + shipment commit together. Effectively-once processing.
    }
}
```

Offset commit happens after the listener returns successfully (Spring Kafka default). If the consumer crashes before committing the Kafka offset, the event is redelivered — but the inbox row makes reprocessing a no-op.

### 5.4 Example 4 — Debezium PostgreSQL outbox connector (Kafka Connect REST config)

```json
{
  "name": "order-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "orders-db",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "${file:/secrets:dbpass}",
    "database.dbname": "orders",
    "topic.prefix": "ordersrv",
    "plugin.name": "pgoutput",
    "slot.name": "order_outbox_slot",
    "publication.autocreate.mode": "filtered",
    "table.include.list": "public.outbox",
    "snapshot.mode": "no_data",
    "heartbeat.interval.ms": "10000",
    "heartbeat.action.query":
      "INSERT INTO dbz_heartbeat(ts) VALUES (now()) ON CONFLICT (id) DO UPDATE SET ts = now()",

    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.topic.replacement": "outbox.event.${routedByValue}",
    "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType",

    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",

    "producer.override.acks": "all",
    "producer.override.enable.idempotence": "true"
  }
}
```

Key effects: only `public.outbox` is captured; the `EventRouter` SMT turns each inserted outbox row into a Kafka message whose **key** is `aggregate_id` (preserving per-order ordering), whose **value** is the raw `payload`, and whose **topic** is `outbox.event.<aggregate_type>`. `heartbeat` keeps the replication slot advancing even when the outbox is idle (prevents WAL bloat). `snapshot.mode=no_data` avoids re-emitting historical outbox rows.

### 5.5 Example 5 — Embedded Debezium (no Kafka Connect) writing to any sink

```java
// Run CDC in-process; useful when you want to publish to SNS/SQS/HTTP, not Kafka,
// or want a single deployable. Requires the debezium-embedded + connector jars.
public class EmbeddedOutboxRelay {

    public void start() {
        Properties props = new Properties();
        props.setProperty("name", "embedded-outbox");
        props.setProperty("connector.class",
                "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage",
                "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", "/data/offsets.dat");
        props.setProperty("offset.flush.interval.ms", "1000");
        props.setProperty("database.hostname", "orders-db");
        props.setProperty("database.port", "5432");
        props.setProperty("database.user", "debezium");
        props.setProperty("database.password", "secret");
        props.setProperty("database.dbname", "orders");
        props.setProperty("topic.prefix", "ordersrv");
        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("slot.name", "embedded_slot");
        props.setProperty("table.include.list", "public.outbox");
        props.setProperty("snapshot.mode", "no_data");

        DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(Json.class)
            .using(props)
            .notifying(record -> {
                // record.value() is the change event JSON; extract payload & publish.
                // The engine records offsets after this callback returns successfully,
                // giving at-least-once semantics.
                publishToBroker(record.key(), record.value());
            })
            .build();

        Executors.newSingleThreadExecutor().execute(engine);
    }

    private void publishToBroker(String key, String changeEventJson) {
        // parse, route, send to SNS/SQS/HTTP/etc. — idempotency still required downstream
    }
}
```

### 5.6 Example 6 — Outbox cleanup job

```sql
-- Periodically purge published rows to keep the outbox small and CDC snapshots cheap.
-- Keep a short retention window for forensics/replay.
DELETE FROM outbox
WHERE published = true
  AND published_at < now() - interval '3 days';
```

For the CDC variant (no `published` column), you can delete rows by age after confirming the slot's `confirmed_flush_lsn` has advanced past them, or simply rely on the fact that Debezium has already produced them:

```sql
DELETE FROM outbox WHERE created_at < now() - interval '1 hour';
```

(Deletes themselves appear in the WAL; with `snapshot.mode=no_data` and EventRouter configured to only route inserts, deletes are ignored as routing targets.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Write amplification:** each business transaction now writes an extra outbox row, slightly increasing WAL volume and commit cost. Usually negligible relative to the business write; measure under load.
- **Polling cost:** a polling relay issues a query every interval. Use a **partial index** (`WHERE published = false`), tune batch size and interval to balance latency vs DB load, and prefer `SKIP LOCKED` over a global lock. CDC eliminates this query load entirely.
- **Latency:** polling latency ≈ poll interval/2 + publish time. CDC latency ≈ log-decode + produce time (typically single-digit to low tens of ms). Choose CDC when you need low, consistent end-to-end latency.
- **Throughput / batching:** batch produces (Kafka `linger.ms`, `batch.size`), batch the `UPDATE ... published` with a JDBC batch, and use compression for large payloads.
- **Outbox table bloat:** delete or partition published rows. In PostgreSQL, frequent deletes create dead tuples — ensure autovacuum keeps up, or use table partitioning by time and `DROP` old partitions.

### 6.2 Correctness & concurrency

- **One transaction, always.** The business write and outbox insert must share the *same* connection/transaction. Watch out for Spring proxy pitfalls: calling a `@Transactional` method from within the same class bypasses the proxy and the transaction may not apply.
- **Ordering:** key by `aggregate_id`; keep `max.in.flight.requests.per.connection ≤ 5` with idempotent producer; use a single-threaded relay per partition or rely on CDC's commit order.
- **At-least-once is intrinsic:** never assume exactly-once delivery. Make every consumer idempotent (inbox table or naturally idempotent operations).
- **Schema evolution:** version the payload (`schema_version` field / header). Use a schema registry (Avro/Protobuf) for strong contracts.

### 6.3 Security

- **CDC privileges are powerful.** A logical replication user can read all changes to captured tables. Grant least privilege; restrict `table.include.list` to the outbox only (so the connector never sees PII in business tables). Store DB credentials in a secrets manager; use Kafka Connect's `config.providers` (e.g., `${file:...}` / Vault) instead of plaintext.
- **PII in payloads:** the outbox payload may contain sensitive data flowing to Kafka. Encrypt at rest/in transit, mask via SMT if needed, and govern topic access.
- **Replication slot as a backdoor:** anyone who can create a slot can siphon your WAL. Audit `pg_replication_slots`.

### 6.4 Observability

- **Outbox lag / backlog:** count of unpublished rows (`SELECT count(*) FROM outbox WHERE published=false`) or oldest unpublished `created_at`. Alert if it grows — the relay is stuck.
- **CDC lag:** for Postgres, **retained WAL bytes per slot** (`pg_wal_lsn_diff`) and slot `active` status; for MySQL, seconds-behind / binlog position lag. Debezium exposes JMX metrics: `MilliSecondsBehindSource`, `NumberOfEventsFiltered`, `QueueRemainingCapacity`, snapshot progress.
- **Kafka Connect health:** connector/task state via REST (`/connectors/<n>/status`), `connect-offsets`/`connect-status` topics, dead-letter queue size.
- **End-to-end tracing:** propagate trace ids through outbox headers → Kafka headers → consumer, so a request can be followed across the async boundary.
- **Duplicate rate & inbox hits:** count inbox dedup rejections to understand real duplicate volume.

### 6.5 Cost

- CDC requires running Kafka Connect workers (CPU/memory) and managing connectors — operational cost. The Embedded engine avoids a Connect cluster but couples CDC to your service's lifecycle.
- Polling adds DB query load (cheap per query, can add up at scale).
- WAL retention and outbox bloat consume storage; manage retention.

### 6.6 Testing

- **Unit:** verify that `placeOrder` writes both rows in one transaction and that a forced failure rolls both back (no orphan outbox row, no orphan order).
- **Integration with Testcontainers:** spin up Postgres + Kafka + Debezium (or the embedded engine) and assert that committing an outbox row results in a Kafka message with the right key/topic/payload.
- **Idempotency tests:** deliver the same event twice; assert the business effect happens once and the second delivery is a no-op (inbox rejects it).
- **Chaos tests:** kill the relay between produce and mark; assert duplicates are produced and consumers tolerate them. Kill Debezium; assert resume-from-offset, no loss.
- **Ordering tests:** produce many events for the same aggregate; assert consumer sees them in order.

### 6.7 Production hardening checklist

- Set `heartbeat.interval.ms` (Postgres) so idle outbox tables don't pin WAL.
- Monitor and alert on replication-slot WAL retention and outbox backlog.
- Configure a **dead-letter queue (DLQ)** for poison messages in sink connectors/consumers.
- Make the relay/connector **HA**: Kafka Connect distributed mode with multiple workers; for the polling relay, multiple instances with `SKIP LOCKED` or leader election.
- Define **outbox retention/cleanup** and ensure autovacuum/partition pruning keeps the table small.
- Plan **snapshot strategy** so connector restarts don't replay millions of rows.
- Use **idempotent Kafka producer** and `acks=all`.
- Capacity-plan WAL/binlog **retention window** to exceed your worst-case relay downtime, so a stopped connector can still resume without data loss.

### 6.8 Anti-patterns to avoid

- **The dual write** (publish to broker inside business logic). The whole point is to *not* do this.
- **Relying on `@Transactional` to make Kafka sends atomic** — it doesn't.
- **Publishing from `afterCommit` callbacks** (Spring `TransactionSynchronization.afterCommit`) and calling it reliable — it's still a dual write; a crash after commit but before the callback loses the event. (It reduces but does not eliminate the window, and many treat it as "good enough" — be explicit that it can lose events.)
- **No idempotency on consumers** — duplicates are guaranteed, not hypothetical.
- **Publishing to different partitions for related events** — breaks ordering.
- **Forgetting heartbeats / slot monitoring** — silent WAL bloat that fills the disk and takes the DB down.
- **Capturing whole business tables instead of an explicit outbox** — leaks internal schema and PII as your public event contract; couples consumers to your DB layout. (The outbox table is your deliberate, stable public contract.)
- **Letting the outbox grow unbounded** — slow queries, expensive snapshots.

---

## 7. Advanced topics & deep internals

### 7.1 "Listen-to-yourself" / log-as-source vs outbox-table

There are two CDC philosophies:

- **Outbox table (recommended):** you write a *purpose-built* event row. The captured stream is a clean, intentional **public event contract** decoupled from your internal schema.
- **Direct table capture ("listen to yourself"):** you CDC your *business* tables directly and derive events from raw row changes. Less code, but it exposes internal schema, makes events brittle to refactors, struggles to express domain events that aren't 1:1 with rows, and may leak fields you didn't intend. Use only for replication/ETL, not for inter-service domain events.

### 7.2 Transaction boundaries & multi-row events in CDC

Log-based CDC emits row-change events, not "logical transactions," by default. If a single business transaction wrote several outbox rows (or several tables), consumers see several independent events. Debezium offers **transaction metadata** topics (`transaction.metadata`) and `provide.transaction.metadata=true`, which emit `BEGIN`/`END` markers with a transaction id and event counts, letting consumers reconstruct transaction boundaries if needed. For the simple outbox (one event row per event), this is usually unnecessary.

### 7.3 PostgreSQL logical decoding internals

- **Logical decoding** reconstructs row changes from the physical WAL using an **output plugin**. `pgoutput` is built in (used by native logical replication); historically `wal2json`/`decoderbufs` were external plugins.
- A **replication slot** records the **`confirmed_flush_lsn`** — the point up to which the consumer has confirmed receipt. Postgres will **not recycle WAL beyond the oldest slot's `restart_lsn`**, which is why an abandoned slot pins WAL forever and fills disk.
- Logical replication does not stream changes for a transaction until it **commits** (in older versions) — so a giant long-running transaction buffers and then bursts. Postgres 14+ supports **streaming of in-progress transactions** (`logical_decoding_work_mem`, protocol streaming) to bound memory. Very large transactions can still stress the decoder.
- **TOAST** (oversized column storage): unchanged TOASTed values may be omitted from the change event unless `REPLICA IDENTITY FULL` is set — a classic gotcha where a column appears "missing" in updates.

### 7.4 MySQL binlog internals

- Requires `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL` for complete before/after images. `MINIMAL` omits unchanged columns.
- Debezium reads the binlog as if it were a replica, tracking position via filename+offset or **GTID**. GTID enables clean failover to a new primary.
- DDL changes are tracked in Debezium's **schema history topic**; corrupting/losing it can wedge the connector.

### 7.5 Snapshots, incremental snapshots, and signaling

- **Initial snapshot** can block streaming and lock briefly; Debezium minimizes locking but large tables are slow.
- **Incremental snapshots** (Debezium's *signal-based* watermarking, based on the DBLog algorithm) let you snapshot large tables *while* streaming live changes, in chunks, without long locks — triggered by writing to a **signal table** or via Kafka signals. Useful for re-bootstrapping a consumer without stopping the world.

### 7.6 Exactly-once with Kafka transactions (and why it doesn't cross your DB)

Kafka's **transactional producer** + **read-process-write** lets you atomically (a) consume from a topic, (b) produce to other topics, and (c) commit consumer offsets — all within Kafka. This yields exactly-once *within Kafka pipelines*. But your DB write is **outside** this transaction, so a consumer that writes to a database still needs idempotency. Debezium produce + offset is at-least-once, not Kafka-EOS, by default.

### 7.7 Ordering across rebalances and partitions

- Kafka **consumer group rebalances** (when instances join/leave) can cause brief duplicate delivery as partitions move; idempotency covers this.
- A single partition is processed by exactly one consumer in a group at a time — so per-partition (per-aggregate, given keying) order is preserved across the group.
- If you need strict global ordering (rare), you need a single partition (throughput bottleneck) or a downstream re-sequencer using a monotonic sequence carried in the event.

### 7.8 Polling relay scaling patterns

- **Competing consumers** via `FOR UPDATE SKIP LOCKED` (Postgres/MySQL 8) — multiple workers, no double-send, ordering preserved per aggregate only if you also lock per aggregate or partition the work by `aggregate_id` hash.
- **Sharded outbox** by hash to parallelize while preserving per-aggregate order within a shard.
- **Leader-elected single relay** (e.g., via ZooKeeper/Kubernetes lease) for strict global order at lower throughput.
  > **ZooKeeper:** a distributed coordination service providing consensus primitives (locks, leader election, config). Often used to pick a single active instance among many.

### 7.9 Lesser-known behaviors

- Debezium emits a **tombstone** (null-valued record) after a delete when `tombstones.on.delete=true`, used for Kafka log compaction. EventRouter handles this for outbox deletes.
- Heartbeat events advance the slot but are *not* business events — filter them out.
- `snapshot.mode=initial` will re-snapshot only if no offset exists; a deleted slot/offset can trigger an unexpected full snapshot on restart.
- Multiple connectors on one Postgres each need their **own slot**; sharing causes corruption.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Outbox relay strategies compared

| Dimension | Polling publisher | Log-based CDC (Debezium) | `afterCommit` hook (NOT reliable) |
|---|---|---|---|
| Message loss on crash | None (row durable) | None (resume from offset) | **Possible** (crash after commit, before send) |
| Latency | Poll interval bound (~10s–100s ms) | Low (single-digit ms typical) | Lowest (synchronous) but unsafe |
| DB load | Repeated SELECTs | Reads log, negligible table load | None |
| Setup complexity | Low (any DB) | Higher (logical replication, Connect) | Trivial |
| Ordering | Needs care (SKIP LOCKED, keying) | Commit order preserved | App order, but unreliable |
| Scaling | Competing consumers / sharding | Connect tasks/partitions | N/A |
| Vendor coupling | None | DB- and connector-specific | None |
| Best for | Simpler systems, moderate scale | High scale, low latency, many consumers | Never for guaranteed delivery |

### 8.2 Outbox+CDC vs alternatives for cross-system atomicity

| Approach | Atomicity | Loss-free | Latency | Operational cost | When to use |
|---|---|---|---|---|---|
| **Transactional outbox + relay/CDC** | Local tx atomic; eventual to broker | Yes (at-least-once) | Low–moderate | Moderate | Default for DB→broker eventing |
| **2PC / XA** | True distributed atomic | Yes | Higher; blocking | High; Kafka unsupported | Legacy XA brokers, strict sync atomicity |
| **Event sourcing** | Store is the event log | Yes | Low | High paradigm shift | When events *are* the source of truth |
| **Listen-to-yourself (capture business tables)** | Local tx atomic | Yes | Low | Moderate | ETL/replication, not domain events |
| **Best-effort publish (dual write)** | None | **No** | Lowest | Lowest | Only when loss is truly acceptable |
| **Saga (orchestration/choreography)** | Per-step compensations | n/a (uses messaging beneath) | — | Moderate | Multi-service business transactions *built on top of* reliable messaging |

> **Saga:** a pattern for multi-service business transactions: a sequence of local transactions, each emitting an event that triggers the next, with **compensating actions** to undo prior steps on failure (instead of a global rollback). Sagas *rely on* reliable messaging — which is exactly what outbox+CDC provides under the hood.

> **Event sourcing:** instead of storing current state, you store the full ordered sequence of state-changing events; current state is derived by replaying them. The event store naturally doubles as the outbox.

### 8.3 Decision rules

- **Use outbox + CDC when:** you own a DB, must emit events reliably, can't/won't use 2PC, and tolerate eventual consistency. Choose **CDC/Debezium** at high scale or low-latency needs; choose **polling** for simplicity/low ops.
- **Avoid / reconsider when:** you need synchronous cross-system atomicity (consider 2PC or redesign), the "other system" is the same DB (just use one transaction), or message loss is acceptable (skip the machinery). Avoid capturing business tables directly for *domain events*.

---

## 9. Failure modes & debugging

### 9.1 Outbox backlog grows (relay stuck)

- **Symptom:** `SELECT count(*) FROM outbox WHERE published=false` climbs; consumers stop receiving events.
- **Causes:** relay crashed/not deployed; broker unreachable; producer config wrong (`acks` can't be satisfied, no ISR); poison row failing repeatedly.
- **Diagnose:** check relay/connector logs and liveness; Kafka Connect `/connectors/<n>/status`; broker connectivity; look for a single id repeatedly failing (poison).
- **Fix:** restart/redeploy relay; restore broker; route poison messages to a **DLQ**; backfill drains automatically once unblocked.

### 9.2 PostgreSQL disk fills due to pinned WAL

- **Symptom:** disk usage climbs steadily; eventually Postgres refuses writes / crashes. The single most common Debezium-in-prod incident.
- **Cause:** a replication slot whose consumer (Debezium) is down or lagging — WAL cannot be recycled past `restart_lsn`. Idle outbox table + no heartbeats also pins WAL because the slot never advances.
- **Diagnose:**
  ```sql
  SELECT slot_name, active,
         pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained
  FROM pg_replication_slots;
  ```
- **Fix:** restart Debezium so the slot advances; enable `heartbeat.interval.ms` (+ a heartbeat write) for low-traffic tables; as a last resort, `pg_drop_replication_slot('name')` (this **loses** the consumer's position — the connector will re-snapshot/resume from current). Capacity-plan `max_slot_wal_keep_size` (PG 13+) to cap retained WAL and protect the DB at the cost of breaking lagging slots.

### 9.3 Duplicate events overwhelming consumers

- **Symptom:** consumers process the same event multiple times; double side-effects if not idempotent.
- **Cause:** intrinsic at-least-once (crash between produce and offset/mark); rebalances; relay republishing.
- **Diagnose:** inbox dedup-rejection metrics; compare event ids.
- **Fix:** ensure idempotency (inbox table/idempotency keys); make side-effects naturally idempotent. Duplicates are expected — design for them, don't try to eliminate them.

### 9.4 Out-of-order events

- **Symptom:** `OrderShipped` processed before `OrderCreated`.
- **Cause:** events for one aggregate split across partitions; producer reordering on retry; multi-worker polling without per-aggregate ordering.
- **Fix:** key by `aggregate_id`; enable idempotent producer; single relay per partition / CDC commit order; consumers tolerant of reordering or using sequence numbers.

### 9.5 Connector stuck / repeated snapshot

- **Symptom:** Debezium re-snapshots on every restart, or never starts streaming.
- **Cause:** offsets lost (offset topic misconfigured), slot dropped, schema-history topic (MySQL) lost/corrupt, wrong `snapshot.mode`.
- **Diagnose:** inspect `connect-offsets`/`connect-status` topics, connector logs, slot existence.
- **Fix:** ensure durable offset storage; don't delete slots casually; for MySQL preserve schema-history; set appropriate `snapshot.mode` (`never`/`no_data` for outbox).

### 9.6 Phantom / lost events when someone "optimized" to a dual write

- **Symptom:** orders exist with no downstream effect, or events for nonexistent orders.
- **Cause:** a regression where someone replaced the outbox write with a direct broker send.
- **Fix:** revert to outbox; add tests asserting no direct broker calls in business code; static analysis/architecture tests (e.g., ArchUnit) forbidding broker clients in the domain layer.

### 9.7 Real-world incident pattern

A frequently reported production story: a low-traffic service's Debezium slot stopped advancing over a weekend (no heartbeat configured, no writes to the captured table). WAL accumulated, the primary's disk filled Monday morning, and the database went read-only, cascading outages across dependent services. The fix was permanent heartbeats, `max_slot_wal_keep_size`, and alerting on per-slot retained WAL — now standard hardening for any Debezium deployment.

---

## 10. Interview drill

**Q1. What is the dual-write problem and why can't `@Transactional` fix it?**
*Model answer:* The dual-write problem arises when one operation must update two independent systems (e.g., a DB and Kafka) with no transaction spanning both. Any crash or failure between the two writes leaves them inconsistent — a saved order with no event, or an event for an order that rolled back. `@Transactional` governs only the database transaction; the Kafka send is a separate network call outside that transaction, so it isn't rolled back with the DB and can succeed or fail independently.
- *Follow-up: Does ordering the writes help?* No — whichever is first, the other can fail independently; no ordering of two independent commits yields atomicity.
- *Follow-up: What about sending in an `afterCommit` callback?* It shrinks but doesn't close the window — a crash after commit but before the callback loses the event. It's a dual write in disguise and can lose messages.
- *Follow-up: When is dual write acceptable?* Only when occasional message loss is genuinely tolerable, which is rare in serious systems.

**Q2. Explain the transactional outbox pattern end to end.**
*Model answer:* In the same local transaction as the business write, insert a row into an outbox table describing the event. Because it's one DB transaction, it's atomic — both or neither. A separate relay then reads outbox rows and publishes them to the broker asynchronously, retrying until success and marking each as published. This gives no message loss and at-least-once delivery; consumers must be idempotent.
- *Follow-up: Where's the at-least-once window?* Between the successful broker publish and marking the row published/committing the offset — a crash there causes a republish.
- *Follow-up: How do you preserve ordering?* Key broker messages by aggregate id so per-aggregate events stay in one partition, and use ordered fetch / idempotent producer / CDC commit order.

**Q3. What is Change Data Capture and how does log-based CDC work?**
*Model answer:* CDC streams a database's changes as events. Log-based CDC reads the DB's write-ahead log (Postgres WAL via logical decoding / replication slot, MySQL binlog), decoding each committed change into a structured event with operation type, before/after images, and a log position. It captures every committed change exactly once in commit order, including deletes, with negligible load on the source tables.
- *Follow-up: Log-based vs query-based CDC?* Query-based polls for changed rows by timestamp/version — simple and DB-agnostic but misses deletes and intermediate states and adds query load. Log-based is complete and low-impact but needs DB config/privileges.
- *Follow-up: What's a replication slot and why is it dangerous?* It tracks the consumer's WAL position so the DB knows what it can recycle; an abandoned/lagging slot pins WAL and can fill the disk.

**Q4. How do Debezium and Kafka Connect implement the outbox pattern?**
*Model answer:* Debezium runs as a Kafka Connect source connector tailing the DB log. It captures inserts into the outbox table, and the Outbox Event Router SMT reshapes each into a Kafka message — payload column as value, aggregate_id as key (ordering), topic derived from aggregate_type. Connect produces to Kafka with idempotent producer and acks=all, committing offsets (log position) so it resumes after restart.
- *Follow-up: How is order preserved?* Aggregate_id is the message key, so all events for one aggregate go to one partition in commit order.
- *Follow-up: What guarantees does it provide?* At-least-once; resume from last committed offset, possibly re-emitting a few events.

**Q5. What is the inbox pattern and how does it give effectively-once processing?**
*Model answer:* The consumer, in the same transaction as its business write, inserts the event's unique id into an inbox table with a unique constraint. If the insert fails (id seen before), it's a duplicate and is skipped. At-least-once delivery plus this dedup means each event's effect is applied exactly once — effectively-once processing.
- *Follow-up: Why not rely on Kafka exactly-once?* Kafka EOS is exactly-once within Kafka (consume-process-produce + offsets); your DB write is outside that transaction, so you still need idempotency at the DB boundary.
- *Follow-up: Alternatives to an inbox table?* Naturally idempotent operations (upserts, set-to-value), or idempotency keys at the sink.

**Q6. Why avoid 2PC/XA here?** *(senior-signal)*
*Model answer:* 2PC gives true cross-system atomicity but is blocking (coordinator failure leaves participants holding locks in-doubt), reduces availability, adds latency, and — decisively — Kafka doesn't support XA. The outbox trades synchronous atomicity for eventual consistency, getting practical reliability (at-least-once + idempotency) without the operational and availability cost of 2PC.
- *Follow-up: When would you still pick 2PC?* When you must have synchronous cross-system atomicity and all resources are XA-capable (e.g., DB + legacy JMS), and you accept the availability cost.
- *Follow-up: What CAP tradeoff does the outbox embody?* It favors availability/partition tolerance with eventual consistency over strong synchronous consistency.

**Q7. Polling publisher vs log-based CDC — when do you choose each?** *(senior-signal)*
*Model answer:* Polling is simple, DB-agnostic, no special privileges, good for moderate scale, but adds query load and has poll-interval latency. CDC has low/consistent latency, negligible table load, and exact commit order, but needs logical replication/binlog config, privileges, and a Connect cluster (operational cost) and is vendor-specific. Choose CDC at high scale / low-latency / many consumers; polling for simpler systems or when you can't enable CDC.
- *Follow-up: How do you scale a polling relay safely?* `FOR UPDATE SKIP LOCKED` for competing consumers, sharding by aggregate hash, or leader election for strict global order.
- *Follow-up: How do you keep CDC from breaking the DB?* Monitor per-slot WAL retention, enable heartbeats, cap retained WAL, ensure connector HA.

**Q8. Should you capture business tables directly or use a dedicated outbox table for inter-service events?** *(senior-signal)*
*Model answer:* Use a dedicated outbox table. It's a deliberate, stable public event contract decoupled from internal schema; you control exactly what's emitted and can express domain events not 1:1 with rows. Capturing business tables ("listen to yourself") leaks internal schema and PII, makes events brittle to refactors, and is suited to ETL/replication, not domain events.
- *Follow-up: Any downside to the outbox table?* Extra write per transaction and table maintenance (cleanup/partitioning); negligible vs the contract/decoupling benefits.
- *Follow-up: How do you evolve the event schema?* Version payloads, use a schema registry (Avro/Protobuf), and keep backward/forward compatibility.

**Q9. How do you guarantee ordering of related events?**
*Model answer:* Order originates from the totally-ordered DB commit log. Preserve it by keying broker messages on aggregate id (one partition per aggregate), using an idempotent producer with bounded in-flight requests, and either single-threaded/per-partition relay or CDC commit order. Global ordering is rarely needed — per-aggregate suffices.
- *Follow-up: What breaks ordering?* Different partitions for related events, producer retry reordering, multi-worker polling without per-aggregate locking, rebalances (duplicates not reorders).

**Q10. How do you make the whole pipeline observable and what do you alert on?**
*Model answer:* Track outbox backlog (unpublished count / oldest age), CDC slot lag (retained WAL bytes, MilliSecondsBehindSource), connector/task status, DLQ size, duplicate/inbox-reject rates, and end-to-end trace propagation. Alert primarily on growing slot WAL retention (disk-fill risk) and growing outbox backlog (relay stuck).
- *Follow-up: First thing you check when consumers stop getting events?* Relay/connector status and broker connectivity; then outbox backlog and slot lag.

**Q11. Walk through exactly what happens on a crash at each pipeline stage.**
*Model answer:* Crash before commit → both writes roll back, no event (correct). Crash after commit, before publish → row remains, relay publishes later (no loss). Crash after publish, before mark/offset → republish on restart (duplicate; idempotency handles it). Crash in consumer before offset commit → redelivery; inbox dedup makes it a no-op. No interleaving causes loss; some cause duplicates.

**Q12. What are the most common Debezium production incidents and how do you prevent them?**
*Model answer:* (1) WAL disk-fill from a stuck/idle slot — prevent with heartbeats, slot lag alerts, `max_slot_wal_keep_size`, connector HA. (2) Unexpected full re-snapshot from lost offsets/slot — durable offset storage, careful `snapshot.mode`. (3) Schema-history loss (MySQL) wedging the connector — protect that topic. (4) Duplicates — idempotent consumers.

---

## 11. Glossary

- **ACID:** Atomicity, Consistency, Isolation, Durability — guarantees of a DB transaction; atomicity (all-or-nothing) is what the outbox exploits.
- **Aggregate / aggregate id:** a domain object (e.g., an Order) and its identifier; used as the message key for per-aggregate ordering.
- **At-least-once delivery:** every message is delivered one or more times; duplicates possible, loss not.
- **Binlog:** MySQL's binary log of data changes, used for replication and CDC.
- **CAP theorem:** in a network partition, a distributed system must choose between consistency and availability.
- **CDC (Change Data Capture):** capturing and streaming a database's changes as events.
- **Compensating action:** an operation that semantically undoes a previous step (used in sagas).
- **Connector (Kafka Connect):** a pluggable component moving data between Kafka and external systems; source (in) or sink (out).
- **Debezium:** open-source log-based CDC platform, mostly as Kafka Connect source connectors.
- **DLQ (Dead-Letter Queue):** a destination for messages that repeatedly fail processing.
- **Dual write:** writing to two independent systems without a spanning transaction — the bug the outbox fixes.
- **Effectively-once / exactly-once processing:** at-least-once delivery + idempotent/deduped processing so each effect applies once.
- **Event sourcing:** persisting state as an ordered log of events; current state is a replay.
- **EventRouter:** Debezium's SMT that turns outbox-table changes into routed, keyed Kafka messages.
- **fsync:** a syscall forcing buffered file data to durable storage; the WAL is fsync'd before commit acknowledgment.
- **GTID:** MySQL Global Transaction Identifier; uniquely names a transaction for failover/resumption.
- **Idempotency:** repeating an operation has the same effect as doing it once.
- **Inbox pattern:** consumer-side dedup table (unique event id) for idempotent processing.
- **JTA / XA:** Java Transaction API / the X/Open standard for distributed (2PC) transactions.
- **Kafka:** distributed append-only log broker with topics, partitions, offsets; order per partition.
- **Kafka Connect:** framework/runtime for streaming data in/out of Kafka via connectors.
- **Logical decoding:** Postgres feature reconstructing row changes from the WAL via an output plugin (e.g., `pgoutput`).
- **LSN (Log Sequence Number):** Postgres WAL position.
- **Offset (Connect):** the source log position a connector has processed; stored for resume.
- **Offset (Kafka):** a record's position within a partition.
- **Outbox table:** a table holding events to publish, written in the same transaction as the business change.
- **Partition (Kafka):** an ordered shard of a topic; ordering guaranteed only within it.
- **Polling publisher:** a relay that finds and publishes outbox rows via periodic SQL queries.
- **Relay / message relay / publisher:** the process that reads the outbox and publishes to the broker.
- **Replication slot:** Postgres object tracking a logical-replication consumer's WAL position; pins WAL until advanced.
- **REPLICA IDENTITY:** Postgres setting controlling how much old-row data is logged for updates/deletes.
- **Saga:** multi-service transaction as a chain of local transactions with compensations.
- **Schema registry:** a service storing/validating message schemas (Avro/Protobuf) for compatibility.
- **SKIP LOCKED:** SQL clause to skip rows locked by other transactions, enabling competing-consumer polling.
- **SMT (Single Message Transform):** a per-record transform applied in Kafka Connect.
- **Snapshot (CDC):** a consistent initial read of existing data emitted before the live change stream.
- **Tombstone:** a null-valued Kafka record marking a key's deletion (for log compaction).
- **TOAST:** Postgres mechanism for storing oversized column values; can cause omitted values in CDC events.
- **Two-Phase Commit (2PC):** prepare/commit protocol for cross-system atomic transactions.
- **Two Generals Problem:** impossibility of guaranteed agreement over an unreliable channel.
- **WAL (Write-Ahead Log):** durable, ordered log written before data pages; basis of recovery and log-based CDC.
- **ZooKeeper:** distributed coordination service (locks, leader election, config).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Problem:** dual write (DB + broker, two commits) → lost or phantom events. `@Transactional` does NOT make the broker send atomic.
- **Fix:** write the event as a row into an **outbox table in the same DB transaction** → atomic. A **relay** publishes it asynchronously.
- **Relay options:** **polling** (`SELECT ... WHERE published=false ... FOR UPDATE SKIP LOCKED`, then publish, then mark) or **log-based CDC** (Debezium tails WAL/binlog).
- **CDC = read the log:** Postgres WAL via logical decoding + replication slot (`pgoutput`); MySQL binlog (`binlog_format=ROW`, `binlog_row_image=FULL`). Captures every committed change in commit order.
- **Debezium + Connect:** source connector + **Outbox EventRouter SMT** → key=`aggregate_id`, value=`payload`, topic from `aggregate_type`. At-least-once; resumes from committed offset.
- **Guarantees:** **at-least-once**, **eventual consistency**. Never exactly-once across DB+broker.
- **Consumers must be idempotent:** **inbox table** (unique event id, same tx as business write) or naturally idempotent ops.
- **Ordering:** key by aggregate id (one partition per aggregate); idempotent producer, `max.in.flight ≤ 5`, `acks=all`.
- **Top prod risk:** Postgres **WAL disk-fill** from a stuck/idle replication slot → set `heartbeat.interval.ms`, alert on `pg_wal_lsn_diff(... , confirmed_flush_lsn/restart_lsn)`, consider `max_slot_wal_keep_size`.
- **Don't:** dual-write; rely on `afterCommit`; skip idempotency; split related events across partitions; capture business tables for domain events; let the outbox/WAL grow unbounded.
- **Use CDC when:** high scale / low latency / many consumers. **Use polling when:** simple, DB-agnostic, no CDC privileges.

### 12.2 Self-test (no answers)

1. Draw the four crash interleavings of a dual write and state which cause loss vs duplicates. Then show how the outbox changes each outcome.
2. You see Postgres disk usage climbing on a service with a Debezium connector. List the exact queries you'd run and the three most likely root causes, in order of likelihood.
3. Write the outbox INSERT and the consumer's inbox INSERT, and explain precisely why each must be inside the same transaction as its respective business write.
4. A teammate proposes capturing the `orders` business table directly instead of an outbox table. Give three concrete reasons to push back and one situation where their approach is actually fine.
5. Explain why "exactly-once delivery" is impossible but "effectively-once processing" is achievable, and describe the mechanism that bridges them in this pattern.
6. Your consumers occasionally see `OrderShipped` before `OrderCreated`. Enumerate every place ordering could have been lost and the fix for each.
7. Compare polling vs CDC relays across latency, DB load, ordering, operational cost, and vendor coupling — then state your default choice for a brand-new high-throughput service and justify it.

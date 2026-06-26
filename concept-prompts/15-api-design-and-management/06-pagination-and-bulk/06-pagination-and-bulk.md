# Pagination & Bulk APIs

> An exhaustive engineering-handbook chapter for senior JVM/backend developers. Covers offset/limit pagination and why it breaks at scale, cursor/keyset pagination, page-token design, total counts, bulk/batch endpoints, streaming large result sets, and long-running operations. Worked examples in Java, SQL, and HTTP.

---

## 1. Overview & where it fits

### What it is

**Pagination** is the practice of returning a large collection of resources to a client in bounded *pages* (chunks) rather than all at once. **Bulk APIs** (also called *batch APIs*) are the inverse-flavored problem: letting a client *send or mutate* many items in a single request rather than one HTTP round-trip per item. Together they are the standard answer to one fundamental question in API design:

> *"What do I do when the data — in either direction — is too big to handle in a single, simple request/response?"*

These two problems sit on opposite ends of the same axis:

| Direction | Problem | Mechanism |
|---|---|---|
| Server → Client (reads) | A query matches more rows than you can or should return at once | **Pagination** (offset, cursor/keyset), **streaming** |
| Client → Server (writes/reads) | A client needs to create/update/delete/fetch many entities efficiently | **Bulk / batch endpoints** |
| Either, but slow | The operation itself takes too long for one synchronous request | **Long-running operations** (async job + polling/callbacks) |

### The problem it solves

Naively, an endpoint like `GET /orders` could `SELECT * FROM orders` and serialize every row. This fails in several concrete ways as data grows:

- **Memory blowup**: materializing 10 million rows into a `List<Order>` and then into a JSON array can OOM (Out Of Memory — when the JVM exhausts its heap and throws `OutOfMemoryError`) the server, the serializer, *and* the client.
- **Latency**: the client waits for the full result before seeing anything; tail latencies (p99 — the 99th-percentile latency, i.e. the value below which 99% of requests complete) explode.
- **Timeouts**: load balancers, reverse proxies, and clients have request timeouts (often 30–60s); a giant query blows past them.
- **Wasted work**: the client usually only needs the first 20 rows (a UI page) but you computed and shipped millions.
- **Cost**: bandwidth, CPU for serialization, and database I/O all scale with rows returned.

Bulk APIs solve the symmetric inefficiency: creating 10,000 records via 10,000 separate `POST` requests means 10,000 TCP/TLS handshakes (or HTTP/2 stream setups), 10,000 auth checks, 10,000 framework dispatch cycles, and 10,000 transaction boundaries. A single bulk request amortizes all of that.

### When you reach for it

- **Pagination**: any list/collection endpoint that *can* grow unbounded. Default to pagination from day one — retrofitting it later is a breaking change.
- **Bulk**: any time a client legitimately needs to operate on N>1 items and the per-item overhead dominates, or atomic/partial-success semantics matter.
- **Streaming**: exports, reports, data pipelines — where the *entire* dataset must move but need not be buffered in memory.
- **Long-running operations (LRO)**: when the work (a bulk import, a report generation, a re-index) takes longer than a sane synchronous timeout.

### One-paragraph mental model

Think of a large result set as a *cursor over an ordered stream*, not a *materialized array*. Pagination is the contract for handing the client a small window of that stream plus a bookmark (an offset number, or — far better — an opaque **page token** encoding "where I left off") so they can ask for the next window. The two implementation families are **offset-based** ("skip N, take M" — simple, but O(N) deep and unstable under concurrent writes) and **keyset/cursor-based** ("give me rows after this last-seen key" — O(log N) via an index, stable, but no random page jumps). Bulk APIs flip the direction: one request carrying many items, where the hard design decisions are *partial success* (what happens when item 7 of 1000 fails?), *limits* (max batch size), and *idempotency* (safe retries). When even one request can't finish in time, you escalate to a **long-running operation**: the API returns a job handle immediately, the work runs asynchronously, and the client polls a status endpoint or receives a callback.

---

## 2. Foundations from first principles

Let's build the vocabulary from zero. A senior dev will know much of this, but precise definitions prevent the subtle bugs.

### 2.1 What is a "collection endpoint"?

A REST (Representational State Transfer — an architectural style for HTTP APIs organized around *resources* identified by URLs) **collection endpoint** returns multiple resources, e.g. `GET /v1/orders`. It is distinct from an **item endpoint** like `GET /v1/orders/{id}` which returns one. Pagination is exclusively a property of collection endpoints.

### 2.2 Ordering is the bedrock

Pagination is meaningless without a **total order** over the collection. If you "skip 20, take 20" but the rows have no defined order, the database is free to return them in *any* order — and may return different orders on each call (especially after a sort-spill, a parallel scan, or a plan change). This produces the most common pagination bug in the wild: **duplicated and skipped rows across pages**.

> **Rule:** Every paginated query MUST have a deterministic `ORDER BY` that ends in a **unique** column (typically the primary key) as a tiebreaker. Ordering by `created_at` alone is not enough if two rows can share a timestamp — append `, id`.

- **Total order**: a relation where every pair of elements is comparable and ties are broken deterministically. `ORDER BY created_at DESC, id DESC` is a total order; `ORDER BY created_at DESC` alone is only a *partial* order if timestamps collide.
- **Stable sort key**: a column (or tuple of columns) whose value, for a given row, does not change between requests during a pagination session — and which is unique so it can serve as a cursor boundary.

### 2.3 Offset/limit pagination

The simplest model. Two parameters:

- **`offset`** (or `skip`): how many rows to discard from the start of the ordered result.
- **`limit`** (or `page_size`, `count`, `per_page`): how many rows to return.

Often expressed as **page-number** style instead: `page=3&page_size=20` ⟺ `offset = (page - 1) * page_size = 40`, `limit = 20`.

SQL:

```sql
SELECT id, customer_id, total_cents, created_at
FROM orders
ORDER BY created_at DESC, id DESC   -- total order, with PK tiebreaker
LIMIT 20 OFFSET 40;                 -- page 3 of size 20
```

**Why it's seductive**: trivial to implement, supports *random access* (jump straight to page 47), and gives you a notion of "total pages" if you also compute a count.

**Why it breaks** (covered in depth in §3 and §7):

1. **Slow deep offsets**: `OFFSET 1000000` forces the database to *generate and discard* the first million rows before returning your 20. This is O(offset), not O(limit). Deep pages get linearly slower.
2. **Inconsistency under writes**: if a new row is inserted at the top while a user pages through, every subsequent page *shifts*. The user sees a row twice (if rows were inserted ahead of them) or skips a row (if rows were deleted). The "page" is defined by *position*, and positions move.

### 2.4 Cursor / keyset pagination

Instead of "skip N", you say "give me the rows that come *after* this specific row in the sort order." The "cursor" is the sort-key value(s) of the last row you saw.

- **Keyset pagination** (a.k.a. **seek method**): the technical SQL technique of filtering by `WHERE (sort_key) > (last_seen_value)` and using an index to *seek* directly to that position instead of scanning-and-discarding.
- **Cursor pagination**: the API-level term — the client passes back an opaque **cursor**/page token rather than raw key values.

SQL (single sort column + PK tiebreaker, descending):

```sql
SELECT id, customer_id, total_cents, created_at
FROM orders
WHERE (created_at, id) < (:last_created_at, :last_id)  -- row-value comparison
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

The `(created_at, id) < (:a, :b)` is a **row-value (tuple) comparison**, which most databases (PostgreSQL, MySQL 8+, SQLite) evaluate lexicographically and — critically — can satisfy with a single composite index range scan. This is O(log N) to *locate* the start plus O(limit) to read, regardless of how deep you are. (Details and per-DB caveats in §3.4 and §7.)

- **Opaque cursor / page token**: an encoded string (often Base64URL of a small JSON or a signed blob) that the client treats as a black box. The server encodes "where I left off" inside it. Opacity lets you change the encoding later without breaking clients, and lets you sign/encrypt it to prevent tampering.

### 2.5 Total counts

Clients (especially UIs) love showing "1,234 results" or "Page 3 of 62". Computing that total means a `SELECT COUNT(*) ... WHERE <filters>`, which on large tables is often as expensive as the data query itself (it must touch every matching row/index entry). At scale this is frequently the slowest part of a paginated endpoint — hence the common guidance to **avoid exact totals** and offer approximations or "has more" booleans instead (§7).

### 2.6 Bulk / batch endpoints

A single request carrying an array of items to create/update/delete (or a set of IDs to fetch). Key concepts:

- **Partial success**: some items succeed, some fail. The response must report per-item status. This conflicts with HTTP's single status code — you typically return `200`/`207` with a per-item results array.
- **207 Multi-Status**: an HTTP status code (from WebDAV, RFC 4918) meaning "the response body contains multiple independent status codes, one per sub-operation." Commonly reused for batch APIs.
- **Idempotency**: the property that performing an operation multiple times has the same effect as performing it once. Critical for safe retries of bulk writes (an **idempotency key** lets the server dedupe).
- **Batch size limit**: a server-enforced maximum number of items (and/or total payload bytes) per request.

### 2.7 Streaming

Instead of buffering the whole result, the server writes rows to the response body as it reads them from the database, and the client consumes incrementally. Mechanisms: **NDJSON** (newline-delimited JSON — one JSON object per line), **chunked transfer encoding** (HTTP/1.1 mechanism to send a body of unknown length in chunks), **JSON streaming arrays**, **Server-Sent Events (SSE)**, or **gRPC server-streaming**. Memory stays O(1) on the server (one row at a time), latency-to-first-byte is low, but you lose easy retry/resume unless you layer a cursor on top.

### 2.8 Long-running operations (LRO)

When the work can't finish within a request timeout, you decouple submission from completion:

1. Client `POST`s the work; server validates, enqueues, and returns `202 Accepted` with a job/operation resource URL.
2. Server processes asynchronously (worker pool, queue).
3. Client **polls** `GET /operations/{id}` for status, or registers a **webhook/callback** (a URL the server calls when done).

- **202 Accepted**: HTTP status meaning "I've accepted the request for processing but haven't completed it."
- **Webhook**: an HTTP callback — the server makes an outbound POST to a client-supplied URL on an event (here, job completion), inverting the usual client→server direction.
- **Idempotent submission**: re-submitting the same job (same idempotency key) returns the existing operation rather than starting a duplicate.

---

## 3. How it works internally

This is the heart of the chapter. We trace the actual control and data flow for each mechanism, the lifecycle/state machine, and what the database does under the hood.

### 3.1 What `LIMIT/OFFSET` actually does in the database

Consider PostgreSQL executing:

```sql
SELECT * FROM orders ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 1000000;
```

Step by step:

1. **Plan selection**: the query planner picks an access path. With an index on `(created_at DESC, id DESC)`, it can do an **index scan** that returns rows already in order (no separate sort). Without one, it does a **sequential scan + sort** (a full table read, then an in-memory or on-disk sort — a "sort spill" to disk if the result exceeds `work_mem`).
2. **Row production**: the executor begins producing rows in sorted order.
3. **OFFSET discard**: the executor *still produces* the first 1,000,000 rows but **throws each away** — it must, because it has to know which rows precede the offset. This is the killer: the cost is proportional to `OFFSET + LIMIT`, not `LIMIT`.
4. **LIMIT cutoff**: after discarding 1,000,000, it emits the next 20 and stops (the planner can use this to avoid producing the *rest* of the table — a "top-N" optimization — but it cannot avoid producing everything *up to and including* the offset window).

So **deep offset latency grows linearly with the page number.** Page 1 is fast; page 50,000 reads a million index entries first. This is true across PostgreSQL, MySQL/InnoDB, SQL Server, Oracle — it's inherent to the operator's semantics, not an implementation quirk.

> **Internal note (MySQL/InnoDB):** when the index is *secondary* (non-clustered) and the query selects columns not in the index, InnoDB must do a **bookmark lookup** (a.k.a. row lookup) back into the clustered primary-key index for *each* of the offset+limit rows it produces — even the discarded ones — unless the secondary index is *covering* (contains all selected columns). Deep offsets thus do `OFFSET + LIMIT` random clustered-index lookups. This is why a covering index dramatically speeds even offset pagination.

### 3.2 The consistency problem under concurrent writes (offset)

Picture a feed ordered `created_at DESC`, page size 3.

```
Time T0, the table (newest first):
  [E][D][C][B][A]

User fetches page 1 (offset 0, limit 3): [E][D][C]

Before page 2, a new row F is inserted (newest):
  [F][E][D][C][B][A]

User fetches page 2 (offset 3, limit 3): [C][B][A]
                                          ^ C is shown AGAIN (duplicate)
                                          and the *true* "row 4" (D) was pushed to row 5,
                                          but the user already saw D, so no skip here —
                                          duplicates happen on inserts ahead of the cursor.
```

Symmetrically, a **deletion** ahead of the user's position shifts everything *up*, so a row that should have appeared on page 2 gets skipped. This is fundamental: offset defines a page by *absolute position*, and positions are not stable under mutation. There is no fix within offset pagination other than snapshotting (see §7).

### 3.3 Keyset (seek) execution — the win

```sql
SELECT * FROM orders
WHERE (created_at, id) < (:last_created_at, :last_id)
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

Step by step (PostgreSQL with index on `(created_at DESC, id DESC)`):

1. **Index seek**: the planner uses the index to jump *directly* to the first entry satisfying `(created_at, id) < (:a, :b)`. This is a B-tree descent — O(log N) — landing precisely at the boundary. No rows before the boundary are touched.
2. **Index range scan**: it walks the index forward (in sort order) reading exactly `LIMIT` entries.
3. **Heap fetch**: for each, fetch the row (or skip if covering index / index-only scan).
4. **Stop** after 20.

Cost: O(log N + limit), *independent of depth*. Page 50,000 is as fast as page 1.

**Why the row-value comparison matters.** A naive translation of the tuple comparison is *wrong*:

```sql
-- WRONG: this is NOT equivalent to (created_at, id) < (:a, :b)
WHERE created_at < :a AND id < :b
```

That filters out valid rows (e.g. a row with `created_at < :a` but `id > :b` is incorrectly excluded). The correct *expanded* form (for databases without tuple comparison, like older MySQL) is:

```sql
WHERE created_at < :a
   OR (created_at = :a AND id < :b)
```

The tuple form `(created_at, id) < (:a, :b)` is exactly this, and PostgreSQL/MySQL 8 can drive a single index range scan from it. The expanded `OR` form *may* not use the composite index optimally on all engines — test the plan. (Tuning in §7.)

**Consistency under writes (keyset):** because the page boundary is a *value* (`created_at < X AND id < Y`), inserting a row with a *newer* timestamp doesn't shift anything you've already paged past — that new row simply appears on a *future* fetch only if it falls after your cursor in sort order (it won't, if it's newer than rows you've already seen in a DESC scan — it would've appeared on page 1, which you already passed). Net effect: **no duplicates from inserts, no skips from the shifting-position problem.** You can still *miss* a brand-new row that sorts before your current position if you started paging before it existed — but you will never see a row twice or skip an existing row due to position drift. This "stable relative to the keys you've seen" property is the core correctness advantage.

### 3.4 Multi-column sort keys and direction

Real APIs sort by user-chosen fields (price, name, rating) that aren't unique. The recipe generalizes: **append a unique tiebreaker (PK) and build the cursor from the full tuple.**

- Sort `ORDER BY price ASC, id ASC` → cursor `(price, id)`; next page filter `(price, id) > (:last_price, :last_id)`.
- Sort `ORDER BY rating DESC, created_at DESC, id DESC` → cursor is the 3-tuple; filter `(rating, created_at, id) < (...)`.

**Mixed directions** (`ORDER BY price ASC, rating DESC`) cannot be expressed as a single tuple comparison because `<`/`>` apply uniformly. You must expand manually:

```sql
-- ORDER BY price ASC, rating DESC, id ASC
WHERE price > :p
   OR (price = :p AND rating < :r)
   OR (price = :p AND rating = :r AND id > :i)
ORDER BY price ASC, rating DESC, id ASC
LIMIT :n;
```

And you need an index matching the exact sort directions — e.g. `(price ASC, rating DESC, id ASC)` — or the database may have to sort (PostgreSQL supports per-column `ASC/DESC` and even `NULLS FIRST/LAST` in index definitions; MySQL 8+ supports descending indexes; MySQL 5.7 does not, which can defeat the seek).

**NULLs**: `NULL` comparisons are tricky. `WHERE col > :x` excludes rows where `col IS NULL`. If your sort column is nullable and you use `NULLS LAST`, the keyset predicate must explicitly handle the NULL boundary. Simplest fix: only allow keyset on `NOT NULL` columns, or use `COALESCE`/sentinel values, or store a computed non-null sort key. (This is a frequent source of "the last few rows never appear" bugs.)

### 3.5 Page-token encoding lifecycle

The opaque token hides the cursor mechanics from the client. Lifecycle:

1. **First request**: client sends no token (or `page_size` only). Server runs the query with no `WHERE` boundary, fetches `limit + 1` rows (the "+1" trick to detect `has_next` without a count — if you get `limit+1` rows, there's another page; return only `limit` and set `next_token`).
2. **Encode**: server takes the last returned row's sort-key tuple, builds a small structure, e.g. `{"v":1,"k":[1719300000000,98231],"d":"orders_by_created_desc","ps":20}`, and Base64URL-encodes it (optionally signs/encrypts — see below). That's `next_page_token`.
3. **Subsequent request**: client echoes `page_token=<opaque>`. Server decodes, validates the version and the embedded sort spec (to reject tokens from a *different* query/sort), and reconstructs the `WHERE` boundary.
4. **Termination**: when fewer than `limit` rows come back (or the `+1` row is absent), `next_page_token` is omitted/null → client stops.

**What to embed in a token (and why):**

| Field | Why |
|---|---|
| Schema version (`v`) | Lets you evolve the encoding without breaking old tokens; reject unknown versions cleanly. |
| Sort key value(s) | The actual keyset boundary. |
| Sort spec / query hash | Detect when a client reuses a token with *different* filters or sort — reject to avoid silent corruption. |
| Page size | So the token is self-describing (or validate it matches the request). |
| Direction | Forward/backward (for bidirectional cursors). |
| (Optional) issue timestamp / TTL | Expire stale cursors (e.g. for snapshot-based reads). |
| (Optional) signature/MAC | Tamper-resistance if cursors must not be guessable/forgeable. |

**Security on tokens:** if a raw cursor leaks an internal ID or could be modified to access other tenants' data, you must **sign** (HMAC) or **encrypt** the token. Never trust a decoded cursor as an authorization boundary — always re-apply tenant/permission filters server-side regardless of token contents. (More in §6.)

### 3.6 Bulk endpoint internal flow & partial-success state

A `POST /v1/orders:batchCreate` with 500 items. Internal flow:

1. **Ingress validation**: check batch size ≤ max (reject `413 Payload Too Large` or `400` with a clear message if over). Authenticate/authorize once.
2. **Idempotency check**: if an `Idempotency-Key` header is present, look it up in an idempotency store (e.g. Redis/DB). If a completed response is cached, return it verbatim (the retry-safe path). If in-flight, return `409`/wait.
3. **Per-item processing strategy** — choose one:
   - **All-or-nothing (transactional)**: wrap all 500 in one DB transaction; any failure → rollback all, return `4xx`/`422` with the offending item. Atomic but one bad item dooms the batch.
   - **Best-effort / partial success**: process each item independently; collect per-item `{index, status, id|error}`. Return `200`/`207` with a results array. Item 7 failing doesn't block item 8.
   - **Hybrid**: validate all first (fail fast on structural errors), then apply, then report.
4. **Response assembly**: build the per-item results in the *same order* as the request (or echo each item's client-supplied correlation id) so the client can map results back.
5. **Idempotency persistence**: store the response keyed by the idempotency key with a TTL.

The **partial-success state** per item is a small state machine: `pending → (validated | invalid) → (applied | failed)`. The response surfaces the terminal state per item.

### 3.7 Streaming internal flow

`GET /v1/orders:export` streaming NDJSON:

1. Server opens a DB cursor / `Statement.setFetchSize(n)` (JDBC hint to fetch rows in batches from the DB rather than all at once) and begins iterating.
2. For each row, serialize to one JSON line and write to the response `OutputStream`, then **flush** periodically.
3. HTTP uses **chunked transfer encoding** (no `Content-Length` known up front).
4. Server memory stays bounded (one row + buffers). Client reads line-by-line.
5. On error mid-stream, you can't change the already-sent `200` status — you emit a trailer/sentinel record or close the connection; clients must detect truncation (e.g. a final `{"_eof":true}` marker or a record count).

Critical JDBC detail: by default many drivers **buffer the entire result set in memory** before you read row 1. To truly stream you must configure the driver (PostgreSQL: autocommit off + `setFetchSize`; MySQL: `setFetchSize(Integer.MIN_VALUE)` for the "streaming" mode). (See §6/§9.)

### 3.8 Long-running operation lifecycle / state machine

```
            submit (POST)                  worker picks up
   client ───────────────► [QUEUED] ──────────────────► [RUNNING]
                              │                              │
                              │ dup idempotency key          │ progress updates
                              ▼                              ▼
                        return existing                 [RUNNING] (percent, counts)
                          operation                          │
                                              ┌──────────────┼───────────────┐
                                              ▼              ▼               ▼
                                         [SUCCEEDED]    [FAILED]        [CANCELLED]
                                              │              │               │
                                              └──── client polls GET /operations/{id} ────┘
                                                     or receives webhook callback
```

Flow:

1. **Submit**: `POST /v1/imports` → validate synchronously (cheap checks), persist an `Operation` row with state `QUEUED`, enqueue a task, return `202` + `Location: /v1/operations/{id}` and a body with the operation resource.
2. **Process**: a worker dequeues, sets `RUNNING`, does the work, updating progress (e.g. `processed`/`total`).
3. **Complete**: set terminal state (`SUCCEEDED` with a result/result-URL, or `FAILED` with an error). Optionally fire a webhook.
4. **Observe**: client polls `GET /v1/operations/{id}` (with backoff) until terminal, or relies on the callback. Provide `Retry-After` to suggest poll interval.
5. **Result retrieval**: result may be inline (small) or a link (`result_url`) to download (e.g. for a generated export).

---

## 4. The complete toolkit

### 4.1 HTTP-level building blocks

| Element | Purpose | Notes / defaults |
|---|---|---|
| Query params `page`, `page_size` | Offset/page-number pagination | No HTTP default; you choose. Common `page_size` default 20–50, max 100–1000. |
| Query params `offset`, `limit` | Offset pagination | Same; cap `limit`. |
| Query param `page_token`/`cursor` | Cursor pagination | Opaque string; pair with `page_size`. |
| `Link` header (RFC 8288) | Convey `next`/`prev`/`first`/`last` URLs | GitHub-style: `Link: <...&page=2>; rel="next"`. Keeps URLs out of body. |
| `Range`/`Content-Range` headers (RFC 7233) | Byte/row ranges; some APIs use for pagination | E.g. `Range: items=0-19`, `Content-Range: items 0-19/200`. Less common for JSON. |
| `202 Accepted` | LRO submission accepted | Pair with `Location` header → operation URL. |
| `207 Multi-Status` | Per-item batch results | From WebDAV; reused for bulk. Some prefer `200` with results array. |
| `Retry-After` header | Tell client when to poll/retry | Seconds or HTTP-date. Used with `202`, `429`, `503`. |
| `Idempotency-Key` header | Dedupe retried writes/bulk | De-facto standard (Stripe). Server stores keyed response. |
| `Prefer: respond-async` | Client asks for async processing | RFC 7240; server may answer `202`. |
| Chunked transfer encoding | Stream bodies of unknown length | HTTP/1.1; automatic when no `Content-Length`. |
| `413 Payload Too Large` | Reject oversized bulk request | Set when batch exceeds size/byte cap. |
| `429 Too Many Requests` | Rate limiting (esp. deep paging / bulk) | Include `Retry-After`. |

### 4.2 Java / JVM: Spring Data pagination toolkit

| Type / method | Purpose | Key params / defaults |
|---|---|---|
| `Pageable` | Abstraction of page request (offset+sort) | Created via `PageRequest.of(page, size, Sort)`. |
| `PageRequest.of(int page, int size)` | Build a page request (0-indexed page) | `page` 0-based; `size` your choice. |
| `Sort` / `Sort.by(...)` | Sort spec | `Sort.by(Order.desc("createdAt"), Order.asc("id"))`. |
| `Page<T>` | Result page **with total count** | `getContent()`, `getTotalElements()` (runs a `COUNT`!), `getTotalPages()`, `hasNext()`. |
| `Slice<T>` | Page **without** total count | `hasNext()` via fetching `size+1`; **no COUNT query** — cheaper. |
| `Window<T>` (Spring Data 3.1+) | Keyset/offset scrolling | `repository.findBy(..., q -> q.limit(n).sortBy(sort)).scroll(ScrollPosition)`. |
| `ScrollPosition.keyset()` / `.offset()` | Choose keyset vs offset scrolling | `Window.positionAt(last)` gives next `ScrollPosition`. |
| `@Query` + `:#{#pageable}` | Custom JPQL/native with paging | Spring appends `LIMIT/OFFSET` for offset; you write keyset `WHERE` manually. |
| `Limit.of(n)` (Spring Data 3.2+) | Simple top-N without full Pageable | `repository.findByX(..., Limit.of(20))`. |

> **Default page size:** Spring Boot's `spring.data.web.pageable.default-page-size` defaults to **20**, and `max-page-size` defaults to **2000**. `one-indexed-parameters` defaults to **false** (pages are 0-indexed). Override in `application.properties`.

### 4.3 JDBC streaming knobs

| Setting | Purpose | Notes |
|---|---|---|
| `Statement.setFetchSize(n)` | Rows fetched per DB round trip | PostgreSQL: requires autocommit off to stream; otherwise whole RS buffered. |
| `connection.setAutoCommit(false)` | Enable server-side cursor (PG) | Needed for true streaming on PostgreSQL JDBC. |
| MySQL `setFetchSize(Integer.MIN_VALUE)` + `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` | MySQL streaming mode | Row-by-row; connection unusable for other queries until RS closed. |
| `ResultSet.TYPE_FORWARD_ONLY` | Forward-only cursor | Lowest overhead; required for streaming. |
| Spring `JdbcTemplate.query(sql, RowCallbackHandler)` | Process rows one at a time | Combine with fetch size for streaming. |
| Hibernate `Query.setFetchSize` + `ScrollableResults` | Stream JPA results | `scroll(ScrollMode.FORWARD_ONLY)`. |
| `Stream<T>` repository methods | `@QueryHints` + `Stream` return | Must run in a transaction and be closed (try-with-resources). |

### 4.4 Cursor encoding tools (Java)

| Tool | Purpose |
|---|---|
| `java.util.Base64.getUrlEncoder().withoutPadding()` | URL-safe Base64 for tokens. |
| Jackson `ObjectMapper` | Serialize cursor struct to JSON before encoding. |
| `javax.crypto.Mac` (`HmacSHA256`) | Sign tokens to prevent tampering. |
| `Cipher` (AES-GCM) | Encrypt tokens if contents are sensitive. |

### 4.5 Vendor / framework pagination conventions

| System | Style | Token/param | Total count? |
|---|---|---|---|
| Stripe API | Cursor | `starting_after`, `ending_before` (object IDs), `limit` (default 10, max 100) | `has_more` boolean; no total. |
| GitHub REST | Page-number | `page`, `per_page` (max 100); `Link` header | Sometimes via `Link rel="last"`. |
| Google Cloud / AIP-158 | Cursor | `page_size`, `page_token`, response `next_page_token` | Optional `total_size` (best-effort). |
| Elasticsearch | `search_after` (keyset), `scroll` (snapshot), PIT | `search_after` array + sort; `scroll_id` | `hits.total` (can be approximate; `track_total_hits`). |
| MongoDB | `skip`/`limit` (offset) or range query on `_id` | `_id` keyset recommended | `countDocuments()`. |
| GraphQL (Relay) | Cursor "connections" | `first/after`, `last/before`, `edges{cursor,node}`, `pageInfo{hasNextPage,endCursor}` | Optional `totalCount`. |
| AWS SDKs | Cursor | `NextToken` / `Marker`, `MaxResults` | Usually none. |

---

## 5. Code examples by use case

Six distinct scenarios. Java where language-relevant; SQL and HTTP where appropriate.

### 5.1 Offset pagination with Spring Data — and its `Slice` (no-count) variant

```java
// Repository: Slice avoids the expensive COUNT(*) that Page would trigger.
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Page<T> -> runs an extra SELECT COUNT(*) to populate getTotalElements().
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    // Slice<T> -> fetches size+1 rows to know hasNext(); NO count query.
    Slice<Order> findByStatus(OrderStatus status, Pageable pageable);
}
```

```java
@GetMapping("/v1/orders")
public OrdersResponse list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam Long customerId) {

    // Clamp page size to protect the backend from abusive ?size=1000000.
    int clamped = Math.min(size, 100);
    // Total order: createdAt then id (unique tiebreaker) — otherwise pages drift.
    Pageable pageable = PageRequest.of(page, clamped,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));

    Slice<Order> slice = orderRepository.findByStatus(OrderStatus.OPEN, pageable);

    return new OrdersResponse(
            slice.getContent(),
            slice.hasNext() ? page + 1 : null  // next page number, or null at end
    );
}
```

Use this only where deep pages are rare (small collections, admin tools) — it is the *anti-pattern* for large, deep, or write-heavy datasets (§3.1–3.2).

### 5.2 Keyset/cursor pagination end-to-end (opaque, signed token) in Java

```java
// --- Cursor value object: the keyset boundary we encode into the token ---
record Cursor(long createdAtEpochMs, long id, int pageSize, String sortId) {}

@Component
class CursorCodec {
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] hmacKey; // injected secret, e.g. 32 random bytes

    CursorCodec(@Value("${cursor.hmac-secret}") String secret) {
        this.hmacKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    String encode(Cursor c) {
        try {
            byte[] payload = mapper.writeValueAsBytes(c);
            byte[] sig = hmac(payload);                 // sign to prevent tampering
            byte[] framed = ByteBuffer.allocate(4 + payload.length + sig.length)
                    .putInt(payload.length).put(payload).put(sig).array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(framed);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    Cursor decode(String token, String expectedSortId) {
        try {
            byte[] framed = Base64.getUrlDecoder().decode(token);
            ByteBuffer buf = ByteBuffer.wrap(framed);
            int len = buf.getInt();
            byte[] payload = new byte[len];        buf.get(payload);
            byte[] sig     = new byte[buf.remaining()]; buf.get(sig);
            if (!MessageDigest.isEqual(sig, hmac(payload)))   // constant-time compare
                throw new BadCursorException("tampered or invalid token");
            Cursor c = mapper.readValue(payload, Cursor.class);
            if (!c.sortId().equals(expectedSortId))           // reject cross-sort reuse
                throw new BadCursorException("cursor sort mismatch");
            return c;
        } catch (BadCursorException e) { throw e;
        } catch (Exception e) { throw new BadCursorException("malformed token"); }
    }

    private byte[] hmac(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
```

```java
@GetMapping("/v1/orders")
public CursorPage<OrderDto> list(
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String pageToken) {

    int limit = Math.min(pageSize, 100);
    final String SORT_ID = "orders_created_desc_v1";

    List<Order> rows;
    if (pageToken == null) {
        rows = orderDao.firstPage(limit + 1);            // +1 to detect has_next
    } else {
        Cursor c = cursorCodec.decode(pageToken, SORT_ID);
        rows = orderDao.afterCursor(c.createdAtEpochMs(), c.id(), limit + 1);
    }

    boolean hasNext = rows.size() > limit;
    if (hasNext) rows = rows.subList(0, limit);          // trim the probe row

    String next = null;
    if (hasNext && !rows.isEmpty()) {
        Order last = rows.get(rows.size() - 1);
        next = cursorCodec.encode(new Cursor(
                last.getCreatedAt().toEpochMilli(), last.getId(), limit, SORT_ID));
    }
    return new CursorPage<>(rows.stream().map(OrderDto::from).toList(), next);
}
```

The DAO using the correct **row-value comparison**:

```java
// DESC ordering => seek to rows that come AFTER the cursor in DESC order = "less than"
List<Order> afterCursor(long createdAtMs, long id, int limit) {
    return jdbc.query("""
        SELECT id, customer_id, total_cents, created_at
        FROM orders
        WHERE (created_at, id) < (?, ?)        -- tuple comparison: index range seek
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """,
        ORDER_MAPPER,
        Timestamp.from(Instant.ofEpochMilli(createdAtMs)), id, limit);
}
```

Required index:

```sql
CREATE INDEX idx_orders_created_id ON orders (created_at DESC, id DESC);
```

### 5.3 Bulk create with partial success (HTTP contract + handler)

**Request:**

```http
POST /v1/orders:batchCreate
Idempotency-Key: 4d8f...e21
Content-Type: application/json

{
  "items": [
    { "clientRef": "a1", "customerId": 100, "totalCents": 4999 },
    { "clientRef": "a2", "customerId": 100, "totalCents": -5 },
    { "clientRef": "a3", "customerId": 101, "totalCents": 12000 }
  ]
}
```

**Response (`207 Multi-Status`):**

```json
{
  "results": [
    { "clientRef": "a1", "status": "CREATED", "id": 90011 },
    { "clientRef": "a2", "status": "FAILED",
      "error": { "code": "INVALID_AMOUNT", "message": "totalCents must be >= 0" } },
    { "clientRef": "a3", "status": "CREATED", "id": 90012 }
  ],
  "summary": { "requested": 3, "succeeded": 2, "failed": 1 }
}
```

**Handler (best-effort, per-item isolation):**

```java
private static final int MAX_BATCH = 500;

@PostMapping("/v1/orders:batchCreate")
public ResponseEntity<BatchResponse> batchCreate(
        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
        @RequestBody BatchCreateRequest req) {

    if (req.items().size() > MAX_BATCH)
        return ResponseEntity.status(413).build();      // reject oversized batch

    if (idemKey != null) {
        var cached = idempotencyStore.get(idemKey);     // safe retry: replay result
        if (cached != null) return ResponseEntity.status(207).body(cached);
    }

    List<ItemResult> results = new ArrayList<>(req.items().size());
    for (CreateItem item : req.items()) {
        try {
            // Each item in its OWN transaction so one failure can't poison the rest.
            Order saved = orderService.createInNewTransaction(item);
            results.add(ItemResult.created(item.clientRef(), saved.getId()));
        } catch (ValidationException e) {
            results.add(ItemResult.failed(item.clientRef(), "INVALID_AMOUNT", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            results.add(ItemResult.failed(item.clientRef(), "CONFLICT", "duplicate"));
        }
    }
    BatchResponse resp = BatchResponse.of(results);
    if (idemKey != null) idempotencyStore.put(idemKey, resp, Duration.ofHours(24));
    return ResponseEntity.status(207).body(resp);
}
```

```java
@Service
class OrderService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)  // isolate each item
    Order createInNewTransaction(CreateItem item) {
        if (item.totalCents() < 0) throw new ValidationException("totalCents must be >= 0");
        return repo.save(Order.from(item));
    }
}
```

Switch to all-or-nothing by wrapping the loop in a single `@Transactional` method and rethrowing on first failure (return `422` with the failing index).

### 5.4 Streaming a large export as NDJSON (bounded memory)

```java
@GetMapping(value = "/v1/orders:export", produces = "application/x-ndjson")
public void export(HttpServletResponse response) throws IOException {
    response.setStatus(200);
    response.setHeader("Content-Type", "application/x-ndjson");
    // No Content-Length -> chunked transfer encoding kicks in automatically.

    OutputStream out = response.getOutputStream();
    JsonFactory jf = new JsonFactory();

    // RowCallbackHandler processes one row at a time; combine with fetch size.
    jdbcTemplate.setFetchSize(1000);
    long[] count = {0};
    jdbcTemplate.query(
        "SELECT id, customer_id, total_cents, created_at FROM orders ORDER BY id",
        (RowCallbackHandler) rs -> {
            try (JsonGenerator gen = jf.createGenerator(out)) {
                gen.writeStartObject();
                gen.writeNumberField("id", rs.getLong("id"));
                gen.writeNumberField("customerId", rs.getLong("customer_id"));
                gen.writeNumberField("totalCents", rs.getLong("total_cents"));
                gen.writeStringField("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                gen.writeEndObject();
                gen.flush();
            }
            out.write('\n');
            if (++count[0] % 1000 == 0) out.flush();   // periodic flush, bounded buffering
        });

    // EOF sentinel so the client can detect truncation vs clean end.
    out.write(("{\"_eof\":true,\"count\":" + count[0] + "}\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
}
```

For PostgreSQL true server-side streaming, ensure the underlying connection has autocommit off and a positive fetch size (Spring's `JdbcTemplate.setFetchSize` plus a transaction). Otherwise the PG driver buffers the whole result set first.

### 5.5 Long-running operation: async bulk import with status polling

**Submit:**

```http
POST /v1/imports
Idempotency-Key: import-2026-06-25-A

{ "sourceUrl": "s3://bucket/orders-2026-06-25.csv" }
```

**Response:**

```http
HTTP/1.1 202 Accepted
Location: /v1/operations/op_7Yc91
Retry-After: 5

{ "operationId": "op_7Yc91", "state": "QUEUED", "createdAt": "2026-06-25T10:00:00Z" }
```

```java
@PostMapping("/v1/imports")
public ResponseEntity<OperationDto> startImport(
        @RequestHeader("Idempotency-Key") String idemKey,
        @RequestBody ImportRequest req) {

    // Idempotent submission: same key -> same operation, no duplicate job.
    Operation op = operationService.findByIdempotencyKey(idemKey)
            .orElseGet(() -> {
                Operation created = operationService.create(OperationType.IMPORT, idemKey, req);
                importQueue.enqueue(created.getId());   // hand off to worker pool
                return created;
            });

    return ResponseEntity.status(202)
            .header("Location", "/v1/operations/" + op.getId())
            .header("Retry-After", "5")
            .body(OperationDto.from(op));
}

@GetMapping("/v1/operations/{id}")
public ResponseEntity<OperationDto> get(@PathVariable String id) {
    Operation op = operationService.get(id);
    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
    if (op.getState() == State.QUEUED || op.getState() == State.RUNNING)
        b.header("Retry-After", "5");                  // suggest poll interval
    return b.body(OperationDto.from(op));
}
```

```java
// Worker
@Component
class ImportWorker {
    @JmsListener(destination = "imports")              // or @KafkaListener / @SqsListener
    void handle(String operationId) {
        Operation op = operationService.markRunning(operationId);
        try {
            int total = csvReader.count(op.getSourceUrl());
            int processed = 0;
            for (var batch : csvReader.batches(op.getSourceUrl(), 1000)) {
                orderService.importBatch(batch);
                processed += batch.size();
                operationService.updateProgress(operationId, processed, total);  // observable progress
            }
            operationService.markSucceeded(operationId,
                    Map.of("imported", processed, "resultUrl", "/v1/imports/" + operationId + "/report"));
            webhookSender.fireIfRegistered(op);        // optional callback
        } catch (Exception e) {
            operationService.markFailed(operationId, e.getMessage());
            webhookSender.fireIfRegistered(op);
        }
    }
}
```

Client polling with backoff:

```java
String opId = submit();
Duration delay = Duration.ofSeconds(2);
while (true) {
    OperationDto op = client.getOperation(opId);
    if (op.isTerminal()) { handle(op); break; }
    Thread.sleep(delay.toMillis());
    delay = min(delay.multipliedBy(2), Duration.ofSeconds(30)); // exponential backoff, capped
}
```

### 5.6 Bidirectional (prev/next) keyset and the "jump to page" reality

GraphQL-Relay style needs both directions. Forward: `(k) < cursor ORDER BY k DESC`. Backward: `(k) > cursor ORDER BY k ASC` then **reverse the result list** before returning so the client still sees descending order.

```sql
-- "previous page" relative to a cursor (DESC display order):
SELECT * FROM (
  SELECT id, created_at FROM orders
  WHERE (created_at, id) > (:c_created, :c_id)   -- rows newer than the cursor
  ORDER BY created_at ASC, id ASC                -- ascending to grab the nearest ones
  LIMIT :limit
) sub
ORDER BY created_at DESC, id DESC;               -- re-sort to display order
```

To approximate "jump to page 47" with keyset (which has no native random access), the pragmatic options are: (a) keep offset for shallow jumps but cap depth; (b) precompute "anchor" cursors at known boundaries; or (c) for monotonic numeric keys, estimate the boundary value. Honest answer: arbitrary deep page jumps are the one thing keyset gives up — usually a worthwhile trade.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Always index the sort key.** Keyset pagination is only fast with an index whose column order *and direction* match the `ORDER BY`. Verify with `EXPLAIN (ANALYZE, BUFFERS)` (PG) / `EXPLAIN FORMAT=JSON` (MySQL); look for `Index Scan` / `Index Cond`, not `Seq Scan` + `Sort`.
- **Avoid `COUNT(*)` on hot paths.** Prefer `Slice`/`has_more` over `Page`/`total`. If you must show a total, use an estimate (PG: `reltuples` from `pg_class`, or `EXPLAIN` row estimate) or cache it.
- **Use the `limit + 1` trick** for `has_next` instead of a count.
- **Covering indexes** turn even offset pagination from `OFFSET+LIMIT` heap fetches into pure index reads; include the selected columns (`INCLUDE` in PG, or put them in the index in MySQL).
- **Cap `page_size`** (e.g. max 100–1000). Unbounded page sizes are a denial-of-service vector and a memory risk.
- **Cap offset depth** if you keep offset at all (reject `OFFSET > N` with a `400` steering clients to cursors), or switch the deep path to keyset internally.
- **Stream exports**; never `findAll()` into a `List` for big tables. Configure JDBC fetch size to avoid the driver buffering everything.

### 6.2 Correctness & concurrency

- **Total order with a unique tiebreaker** is non-negotiable (else duplicates/skips).
- **Keyset is consistent under writes; offset is not.** Prefer keyset for feeds, infinite scroll, and any actively-mutated dataset.
- **Snapshot reads** for "consistent export": Elasticsearch `scroll`/PIT (point-in-time), PostgreSQL repeatable-read transaction snapshot, or a logical snapshot (e.g. read as-of a watermark timestamp). Required when the client must see a frozen view across pages.
- **Bulk atomicity**: decide all-or-nothing vs partial success *deliberately* and document it. Partial success needs a per-item result contract.
- **Idempotency** for all bulk/LRO writes: accept an `Idempotency-Key`, store the response, and replay on retry. Without it, network retries duplicate data.

### 6.3 Security

- **Sign or encrypt opaque tokens** if they could be tampered with to access other rows/tenants, or if the raw key is sensitive (e.g. sequential internal IDs revealing volume). Use HMAC (integrity) and AES-GCM (confidentiality) as needed.
- **Never use the cursor as the authorization boundary.** Always re-apply tenant/row-level filters server-side; a forged cursor must not bypass access control.
- **Limit bulk size and total bytes** to prevent OOM/DoS (`413`).
- **Rate-limit** deep pagination and bulk endpoints separately (they're expensive). Return `429` + `Retry-After`.
- **Validate `page_size`/`offset` bounds** to reject negative or absurd values (`400`).
- **Webhook callbacks**: sign the callback payload (HMAC header) so the receiver can verify authenticity; require HTTPS; guard against SSRF when accepting client-supplied callback URLs.

### 6.4 Observability

- Emit metrics: page-size distribution, offset depth distribution (to spot deep-paging clients), pagination query latency, count-query latency separately, bulk batch sizes, partial-failure rates, LRO queue depth and processing time, webhook delivery success.
- Log the **sort spec and decoded cursor** (not the raw token if signed) on errors to debug "missing rows" reports.
- For LROs, expose progress (`processed/total`) and store terminal errors for postmortem.

### 6.5 Cost

- Bandwidth and serialization CPU scale with rows shipped — pagination directly controls cloud egress and CPU cost.
- `COUNT(*)` on large tables is a recurring hidden cost; eliminate or cache.
- Streaming reduces peak memory (fewer/smaller instances) but holds a DB connection longer — watch connection-pool exhaustion.

### 6.6 Testing

- Test **page boundaries**: exact multiples of page size, last partial page, empty result, single item.
- Test **consistency under concurrent inserts/deletes** (insert between page fetches; assert no dup/skip for keyset).
- Test **token tampering** (flipped bits → rejected) and **cross-sort reuse** (token from sort A used on sort B → rejected).
- Test **bulk partial success** ordering and the summary counts; test **idempotent replay** (same key twice → identical response, no double-write).
- Test **deep offset rejection** and **page-size clamping**.
- Property test: paging through the whole dataset with keyset visits every row exactly once.

### 6.7 Production hardening checklist

- Default and max page sizes set and enforced.
- Keyset on all large/mutating collections; offset only where justified and depth-capped.
- All bulk/LRO writes idempotent.
- Streaming endpoints have read timeouts and connection-pool guards.
- Cursors signed if exposed/sensitive; never an authz boundary.
- Totals approximated or omitted on hot endpoints.
- Metrics + alerts on deep-paging, slow counts, LRO backlog, webhook failures.

### 6.8 Anti-patterns

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| `ORDER BY` without unique tiebreaker | Dup/skip across pages | Append PK. |
| Offset for deep/large/mutating data | O(offset) latency, inconsistency | Keyset/cursor. |
| `COUNT(*)` on every list call | Often the slowest query | `Slice`/`has_more`, estimate, or cache. |
| `findAll()` then paginate in memory | Loads whole table; OOM | Push `LIMIT` to DB. |
| Unbounded `page_size` / batch size | DoS, OOM | Clamp + `413`. |
| Naive `col < a AND id < b` keyset | Drops valid rows | Tuple `(col,id) < (a,b)` or proper OR expansion. |
| Transparent cursor exposing raw IDs | Tampering, enumeration | Opaque + signed token; server-side authz. |
| Synchronous huge bulk/import | Timeouts, retries duplicate | LRO with idempotency. |
| Same idempotency key reused for different bodies | Wrong cached response | Bind key to a request hash; reject mismatch. |

---

## 7. Advanced topics & deep internals

### 7.1 Why deep offset is *fundamentally* O(offset)

No engine can answer "the rows after position N in sort order" without identifying the first N rows, because position is defined by what precedes it. Even with a perfect index, the engine must traverse N index entries to know it has skipped exactly N. The only escape is to redefine the page boundary by *value* (keyset) rather than *position* — which is precisely what keyset does. This is information-theoretic, not a missing optimization.

### 7.2 Index-only scans & covering indexes

PostgreSQL **index-only scan**: if the index contains every column the query needs (via `INCLUDE` for non-key payload columns), PG never touches the heap — but only if the page's **visibility map** marks it all-visible (vacuum-dependent). MySQL/InnoDB analog: a **covering index** lets InnoDB satisfy the query from the secondary index without clustered-index lookups. For pagination, make the keyset index cover the selected columns and you eliminate per-row heap fetches.

### 7.3 Estimated totals

Exact `COUNT(*)` is O(matching rows). Alternatives:

- **PostgreSQL**: `SELECT reltuples::bigint FROM pg_class WHERE relname='orders';` (live-tuple estimate from the last `ANALYZE`/`VACUUM`), or parse `EXPLAIN`'s estimated row count. Fast but approximate and ignores `WHERE` unless you `EXPLAIN` the filtered query.
- **HyperLogLog / probabilistic counters** for distinct-ish counts.
- **Cached/materialized counts** updated by triggers or periodic jobs.
- **`track_total_hits` in Elasticsearch**: by default ES stops counting at 10,000 and reports `"gte": 10000` — a deliberate "don't compute exact deep totals" design.

### 7.4 Elasticsearch deep pagination specifics

- **`from`/`size`** (offset) is capped by `index.max_result_window` (default **10,000**) — going deeper errors, by design, because each shard must build a priority queue of `from+size` and the coordinating node merges them: memory grows with depth.
- **`search_after`**: keyset pagination using the last hit's sort values — the recommended deep-paging method, stateless, no window cap.
- **`scroll`**: takes a consistent snapshot and pages through it with a `scroll_id`; great for exports, but holds search context (memory) and is *not* for real-time user paging. Largely superseded by **PIT (point-in-time) + `search_after`**, which gives snapshot consistency without scroll's downsides.

### 7.5 MySQL vs PostgreSQL keyset quirks

- **MySQL 5.7**: no descending indexes — `INDEX(created_at, id)` is ascending; a `ORDER BY created_at DESC, id DESC` may still use it via backward scan, but mixed directions defeat it. **MySQL 8.0+** adds true descending indexes.
- **Row-value comparison**: PostgreSQL and MySQL 8 optimize `(a,b) < (?,?)` into an index range scan; older MySQL may not, so write the expanded `OR` form and confirm the plan.
- **`LIMIT` with `OFFSET` deferred join (MySQL trick)**: for unavoidable offset, fetch only PKs via a covering index, then join back: `SELECT * FROM orders JOIN (SELECT id FROM orders ORDER BY ... LIMIT 20 OFFSET 100000) k USING(id)`. The inner query is index-only so the expensive offset traversal avoids heap lookups; only 20 full rows are fetched.

### 7.6 Cursor stability vs sort-key mutability

If you paginate by a column that can *change* (e.g. `updated_at`, or `price` that's edited), a row can move relative to your cursor and be seen twice or skipped — keyset's consistency guarantee assumes the sort key is **immutable for the duration of paging**. Safe sort keys: `id` (immutable), `created_at` (immutable). Mutable sort keys need snapshot reads or acceptance of the anomaly.

### 7.7 Bidirectional cursors & `pageInfo`

Relay's `PageInfo { hasNextPage, hasPreviousPage, startCursor, endCursor }` requires computing both directions. `hasPreviousPage` for a forward query is often just "did the client supply an `after` cursor?" (spec allows shortcuts). True bidirectional needs the reverse query (§5.6) and careful handling of which direction's `limit+1` probe you used.

### 7.8 Bulk transactional limits & lock contention

A single all-or-nothing transaction over 10,000 rows holds locks for the whole duration, bloats the transaction log/WAL, and risks lock timeouts/deadlocks. Best practice: **chunk** large bulk operations into sub-batches (e.g. 500–1000) inside the worker, each its own transaction, with idempotent progress tracking — converting a giant transaction into a resumable, partial-success-friendly job (which is why big bulk belongs in an LRO).

### 7.9 Token expiry & snapshot lifecycle

Snapshot-based cursors (scroll, PIT, repeatable-read) consume server resources and must be **TTL'd**. Embed an expiry in the token and return a clear `410 Gone`/`400` when a client presents an expired cursor, instructing them to restart pagination.

### 7.10 Hybrid: keyset + filtered facets

When list endpoints support arbitrary filters + sorts, the cursor must encode the *exact* sort/filter context (or a hash of it) so reusing a token against changed parameters is rejected — otherwise you silently mix result sets. AIP-158 explicitly says servers should reject a `page_token` used with different request parameters.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Offset vs Keyset vs Snapshot/scroll

| Dimension | Offset/limit | Keyset/cursor | Snapshot (scroll/PIT) |
|---|---|---|---|
| Deep-page latency | O(offset) — degrades | O(log N + limit) — flat | Flat within snapshot |
| Consistency under writes | Poor (dup/skip) | Good (stable vs seen keys) | Strong (frozen view) |
| Random page jump | Yes (native) | No (sequential only) | No |
| Total count available | Easy (extra COUNT) | Hard/avoided | Sometimes |
| Implementation complexity | Trivial | Moderate (tokens, tuple SQL) | Higher (resource mgmt, TTL) |
| Server memory/state | None | None (stateless token) | Holds snapshot/context |
| Best for | Small/admin lists, need page jumps | Feeds, infinite scroll, large mutating data | Consistent exports, ML/data dumps |

### 8.2 When to use what

- **Use offset/limit when**: dataset is small (≤ a few thousand), users need to jump to arbitrary pages (e.g. classic search results UI), and writes are infrequent. Cap depth and page size.
- **Avoid offset when**: data is large, deep paging happens, or rows mutate during paging (feeds, logs, events).
- **Use keyset/cursor when**: large or growing collections, infinite scroll / "load more", consistency under concurrent writes matters, you don't need arbitrary page jumps. **Default for most public list APIs.**
- **Avoid keyset when**: you genuinely need random page access and can't approximate it, or your sort key is mutable and you can't snapshot.
- **Use snapshot/scroll/PIT when**: a single logical export must be internally consistent across all pages (reporting, re-indexing, ML training dumps). Not for interactive user paging.
- **Use streaming when**: the whole dataset moves to a machine consumer and buffering it is wasteful; combine with a resumable cursor for restartability.
- **Use bulk/batch when**: per-item overhead dominates and clients legitimately operate on many items; pick partial-success vs atomic deliberately.
- **Use LRO when**: the operation exceeds a safe synchronous timeout (large imports, report generation, re-indexing). Always idempotent submission.

### 8.3 Bulk: atomic vs partial-success

| | All-or-nothing (atomic) | Partial success |
|---|---|---|
| Semantics | Whole batch commits or none | Each item independent |
| Status | `4xx`/`422` on any failure | `200`/`207` + per-item results |
| Use when | Items are interdependent / must be consistent | Items independent; want max throughput |
| Retry behavior | Retry whole batch | Retry only failed items |
| Lock/txn cost | High (one big txn) | Lower (per-item or chunked) |

### 8.4 Polling vs callbacks for LRO

| | Polling | Webhook/callback |
|---|---|---|
| Client complexity | Simple loop + backoff | Must run an HTTPS endpoint |
| Latency to learn result | Up to poll interval | Near-immediate |
| Server load | Repeated GETs | One outbound POST + retries |
| Firewall/NAT friendliness | Works anywhere | Needs reachable callback URL |
| Reliability | Client-driven, robust | Needs retries, signing, dedupe |
| Best | Public APIs, untrusted networks | Server-to-server, low latency |

Often offered **together**: webhook for speed, polling as a fallback.

---

## 9. Failure modes & debugging

### 9.1 Duplicate / missing rows across pages

- **Symptom**: users report seeing the same item twice, or items vanishing while scrolling.
- **Cause**: non-unique `ORDER BY` (no tiebreaker), or offset pagination over mutating data.
- **Diagnose**: check the query's `ORDER BY` for a unique terminal column; reproduce by paging while inserting rows. Log decoded cursors and sort spec.
- **Fix**: add PK tiebreaker; switch to keyset for mutating data.

### 9.2 Slow deep pages

- **Symptom**: page 1 is 5 ms, page 5000 is 4 s; p99 latency tracks page depth.
- **Diagnose**: `EXPLAIN (ANALYZE, BUFFERS)` on a deep-offset query — you'll see huge `Rows Removed by ...`/`actual rows` far exceeding `LIMIT`, or a big sort. Metrics: correlate latency with `offset` param.
- **Fix**: keyset pagination; or MySQL deferred-join trick; cap offset depth.

### 9.3 `COUNT(*)` dominating latency

- **Symptom**: list endpoint slow even on page 1; trace shows two queries, the count being slower than the data fetch.
- **Diagnose**: APM span breakdown; PG `pg_stat_statements` showing the count query high in total time.
- **Fix**: drop exact total (`Slice`/`has_more`), use estimate, or cache.

### 9.4 OOM on list/export

- **Symptom**: `OutOfMemoryError`, or heap spikes correlated with large queries.
- **Cause**: `findAll()`/unbounded query materialized in memory; JDBC driver buffering whole result set (PG without autocommit-off, MySQL without streaming mode).
- **Diagnose**: heap dump shows a giant `ArrayList`/result-set buffer; thread stack in JSON serialization.
- **Fix**: enforce `LIMIT`; for exports, stream with proper fetch-size config; clamp page/batch size.

### 9.5 Connection-pool exhaustion from streaming

- **Symptom**: under load, requests block waiting for DB connections; pool `getConnection` timeouts.
- **Cause**: streaming endpoints hold a connection for the entire (slow) client read; many concurrent slow clients exhaust the pool.
- **Diagnose**: pool metrics (active vs idle), long-held connections.
- **Fix**: separate pool/limit for streaming; read timeouts; backpressure; consider exporting to object storage via an LRO instead of live-streaming to clients.

### 9.6 Keyset "last rows never appear"

- **Symptom**: pagination ends a few rows early; certain rows never returned.
- **Cause**: nullable sort column + `NULLS LAST` not handled in the keyset predicate; or wrong `OR`-expansion dropping valid rows.
- **Diagnose**: count rows visited via keyset vs `SELECT COUNT(*)`; they differ.
- **Fix**: handle NULL boundary explicitly, use a `NOT NULL` sort key, or correct the tuple/OR predicate.

### 9.7 Cursor reused across changed filters

- **Symptom**: client changes a filter mid-scroll and gets nonsensical/mixed results.
- **Cause**: token doesn't bind to filter/sort context; server applies new filter with old boundary.
- **Fix**: embed sort+filter hash in the token; reject mismatched reuse (`400`).

### 9.8 Bulk double-write on retry

- **Symptom**: duplicates created when a client retries a bulk POST after a timeout.
- **Cause**: no idempotency key; the original request actually succeeded but the response was lost.
- **Diagnose**: duplicate rows with near-identical timestamps; client logs show a retry.
- **Fix**: require/honor `Idempotency-Key`; store and replay responses.

### 9.9 LRO stuck / orphaned

- **Symptom**: operation stays `RUNNING` forever; client polls indefinitely.
- **Cause**: worker crashed mid-job without updating state; no lease/heartbeat.
- **Diagnose**: queue depth, worker logs, operation `updatedAt` stale.
- **Fix**: heartbeats/leases with timeout → mark `FAILED` and allow retry; idempotent re-processing so a re-run is safe.

### 9.10 Real-world patterns/incidents (illustrative)

- **The "infinite scroll dupes" class of bugs** is endemic to offset-paginated feeds; the standard postmortem fix is migrating to cursor pagination (the approach Twitter/X, Stripe, Slack, and most large feed APIs adopt — Stripe's API is cursor-only by design).
- **Elasticsearch `max_result_window` errors** at `from+size > 10000` are a deliberate guardrail; teams hitting it are steered to `search_after`/PIT.
- **MySQL deep-offset slowdowns** are a classic that the deferred-join/late-row-lookup trick addresses; widely documented in MySQL performance literature.

(Where I attribute a behavior to a specific vendor I've stated the version/default; treat company-specific anecdotes as illustrative patterns rather than cited incidents.)

---

## 10. Interview drill

**Q1. Why does offset pagination get slow on deep pages, and how does keyset fix it?**
Model answer: `LIMIT m OFFSET n` must produce and discard the first `n` rows in sort order, so cost is O(n+m) — page depth drives latency. Keyset replaces "skip n" with "give me rows after this last-seen key" via `WHERE (sortcols) < lastvalues`, which an index can *seek* to directly: O(log N + m), flat regardless of depth.
- *Probe: why can't the DB optimize the offset away?* Position is defined by what precedes it; to skip exactly n rows it must identify n rows. It's information-theoretic.
- *Probe: what index do you need for keyset?* A composite index whose columns and directions match the `ORDER BY` (including the unique tiebreaker), so the seek + range scan stays index-resident.
- *Probe: what does keyset give up?* Random page jumps — it's sequential only.

**Q2. Walk me through designing an opaque page token.**
Model answer: Encode the keyset boundary (sort-key tuple) plus metadata — schema version, sort/filter id, page size, optional direction and TTL — as JSON, Base64URL it, and sign (HMAC) it. On the next request decode, verify the signature, check the version and that the sort/filter context matches the request, reconstruct the `WHERE` boundary, and re-apply authorization filters server-side.
- *Probe: why opaque?* So you can change the encoding/strategy without breaking clients, and prevent them from relying on internals.
- *Probe: why sign?* To prevent tampering/enumeration; but the cursor is never the authz boundary — you always re-filter by tenant/permissions.
- *Probe: what if a client reuses a token with a different sort?* Reject (`400`) via the embedded sort id, else you silently corrupt the result set.

**Q3. Why avoid returning exact total counts, and what are the alternatives?**
Model answer: `COUNT(*)` with filters touches every matching row/index entry — often the slowest part of a list call and a scaling cliff. Alternatives: omit totals (return `has_more`/`next_token`), use estimates (PG `reltuples`, `EXPLAIN` row estimate, ES `track_total_hits` cap), or cache/materialize counts.
- *Probe: when is an exact count justified?* Small datasets, or when product genuinely requires it and you can cache it.
- *Probe: how get a fast estimate in PG?* `pg_class.reltuples` (whole table) or `EXPLAIN` estimate for filtered queries — approximate, depends on `ANALYZE` freshness.

**Q4. Design a bulk-create endpoint. Atomic or partial success?**
Model answer: Depends on item independence. Independent items → partial success: process each (ideally each in its own transaction), return `207`/`200` with a per-item results array and a summary; clients retry only failures. Interdependent items → all-or-nothing in one transaction, fail the batch with the offending index. Always cap batch size (`413` over the limit) and honor an `Idempotency-Key` for safe retries.
- *Probe: how do clients map results to inputs?* Echo a client-supplied `clientRef`/correlation id per item, or preserve order.
- *Probe: why per-item transactions for partial success?* So one failure's rollback doesn't undo successful items; isolates failures.
- *Probe: very large bulk?* Move to an LRO; chunk internally; track idempotent progress.

**Q5 (senior signal). You're designing a public list API used by feeds, dashboards, and exports. Argue for a pagination strategy and justify the tradeoffs.**
Model answer: Default to **cursor/keyset** for interactive list endpoints — feeds and dashboards need consistency under heavy writes and flat latency, which offset can't give; the cost is losing random page jumps, acceptable for infinite-scroll UIs. Keep **offset only** where product truly needs page jumps over small, stable datasets, with capped depth/size. For **exports** that must be internally consistent, use a **snapshot mechanism** (PIT/scroll or repeatable-read) behind an **LRO** writing to object storage, not a live stream, to avoid holding DB connections. Counts are `has_more` by default, estimated on demand. This isolates the three workloads' very different consistency/latency/cost profiles instead of forcing one mechanism to serve all.
- *Probe: how handle a UI that shows "page 12 of 60"?* Either accept offset with caps for that surface, precompute anchor cursors, or change the UX to infinite scroll — call out the product tradeoff.
- *Probe: how protect against abusive clients?* Clamp page size, cap offset depth, rate-limit deep paging/bulk separately, sign cursors.

**Q6 (senior signal). A client reports duplicate items while scrolling a feed. Diagnose and propose a fix, weighing options.**
Model answer: Most likely either (a) `ORDER BY` lacks a unique tiebreaker so rows with equal sort values reorder between fetches, or (b) offset pagination over a feed receiving inserts, shifting positions. Diagnose by inspecting the query's `ORDER BY` and reproducing with concurrent inserts. Fix (a) by appending the PK; fix (b) by migrating to keyset, which defines pages by value not position. If random page jumps are required by some surface, scope offset to that surface only with caps, accepting its weaker consistency. The keyset migration is a token-format change — version the tokens and support both during rollout.
- *Probe: can keyset still miss rows?* It won't dup or skip *existing* rows due to position drift, but a row inserted before your current position after you passed it won't appear until a fresh scroll — acceptable for feeds.
- *Probe: mutable sort key?* Then even keyset can dup/skip; use an immutable key (id/created_at) or snapshot.

**Q7 (senior signal). When would you choose webhooks over polling for long-running operations, and what are the failure modes you must handle?**
Model answer: Choose webhooks for server-to-server integrations where low latency-to-completion matters and the client can host a reachable HTTPS endpoint; choose polling for public APIs, untrusted networks, or clients behind NAT/firewalls. Often offer both (webhook + poll fallback). Failure modes to handle: callback delivery failures (retries with backoff + dedupe via event ids), receiver downtime (durable retry queue, eventual give-up), security (sign payloads with HMAC, require HTTPS, prevent SSRF on client-supplied URLs), and ordering/duplication (idempotent receivers). Always make the operation pollable too so a lost webhook never strands the client.
- *Probe: how prevent the client from missing a result?* Idempotent, queryable operation resource + `Retry-After`; webhook is an optimization, not the source of truth.
- *Probe: SSRF risk?* Validate/allowlist callback URLs, block internal IP ranges, no redirects to internal hosts.

**Q8. How do you stream a million-row export without OOM in Java?**
Model answer: Don't materialize — use a forward-only DB cursor with a sane fetch size and write rows to the response as you read them (NDJSON over chunked transfer). On PostgreSQL JDBC, set autocommit off and a positive `fetchSize` (else the driver buffers the whole result); on MySQL use the streaming mode (`fetchSize=Integer.MIN_VALUE`, forward-only). Flush periodically, emit an EOF sentinel so the client can detect truncation, and bound concurrency to protect the connection pool.
- *Probe: how does the client know the stream completed vs got cut off?* EOF sentinel record / final count, or a trailer; otherwise truncation is silent.
- *Probe: connection pool risk?* Slow clients hold connections; use a separate pool/limit, timeouts, or export-to-storage via LRO.

**Q9. What's the `limit + 1` trick and why use it?**
Model answer: Fetch `page_size + 1` rows; if you get the extra one, there's a next page — set `has_next`/`next_token` and return only `page_size` rows. It gives `has_more` without a `COUNT(*)`.
- *Probe: edge case at the exact boundary?* If total is an exact multiple of page size, the `+1` correctly signals one more (empty-ish) page only when a real extra row exists; you trim the probe row so clients never see it.

**Q10. How do mixed-direction sorts (price ASC, rating DESC) complicate keyset?**
Model answer: A single tuple comparison `(a,b) < (?,?)` assumes uniform direction, so mixed directions must be expanded into explicit `OR` clauses per prefix, and you need an index matching the exact directions (PG: per-column ASC/DESC and NULLS ordering in the index; MySQL 8 descending indexes). Otherwise the DB sorts and you lose the seek.
- *Probe: NULLs?* `>`/`<` exclude NULLs; with `NULLS LAST` you must add explicit NULL-boundary predicates or use a non-null sort key.

**Q11. Why is idempotency central to bulk and LRO APIs?**
Model answer: These operations are expensive and often retried after timeouts where the client can't tell if the original succeeded. An idempotency key lets the server dedupe — replay the stored response (bulk) or return the existing operation (LRO) — so retries never duplicate work or data.
- *Probe: key scope/TTL?* Bind the key to a request hash (reject reuse with a different body), store with a TTL (e.g. 24h), and key per endpoint/account.

**Q12. Elasticsearch: `from/size`, `search_after`, `scroll`, PIT — when each?**
Model answer: `from/size` for shallow paging (capped at `max_result_window`=10k by default). `search_after` (keyset on sort values) for deep, stateless, real-time paging — the recommended deep method. `scroll` for consistent snapshots when exporting, but it holds context and isn't for live UI. **PIT + `search_after`** supersedes scroll: snapshot consistency without scroll's resource downsides.
- *Probe: why the 10k cap?* Each shard builds a `from+size` priority queue and the coordinator merges — memory grows with depth; the cap is a guardrail.

---

## 11. Glossary

- **202 Accepted**: HTTP status: request accepted for async processing, not yet complete; pair with `Location` to the operation resource.
- **207 Multi-Status**: HTTP status (WebDAV/RFC 4918) whose body carries multiple per-sub-operation statuses; reused for bulk APIs.
- **Backpressure**: a flow-control mechanism where a slow consumer signals a producer to slow down, preventing buffer overflow.
- **Base64URL**: URL-safe Base64 encoding (`-`/`_` instead of `+`/`/`, no padding) used for tokens in URLs.
- **Batch / bulk API**: an endpoint accepting many items in one request.
- **B-tree**: the balanced tree index structure databases use; supports O(log N) seeks and ordered range scans — the backbone of keyset pagination.
- **Bookmark / row lookup**: in InnoDB, fetching the full row from the clustered index after finding it via a secondary index.
- **Chunked transfer encoding**: HTTP/1.1 mechanism to send a body in chunks when its total length isn't known up front (used for streaming).
- **Clustered index**: an index whose leaf nodes *are* the table rows, ordered by the key (InnoDB primary key). Lookups via other indexes must hop back to it.
- **Covering index / index-only scan**: an index that contains all columns a query needs, so the heap/clustered index isn't touched.
- **Cursor (API)**: an opaque token marking a position in a result set for pagination.
- **Cursor (DB)**: a server-side pointer into a result set, enabling row-by-row/streamed reads.
- **DoS (Denial of Service)**: overwhelming a system so legitimate users can't use it; unbounded page/batch sizes are a vector.
- **Idempotency**: performing an operation N times has the same effect as once.
- **Idempotency key**: a client-supplied unique value letting the server dedupe retried requests.
- **Keyset / seek pagination**: paginating via `WHERE sortkey > lastvalue` + index seek, instead of offset.
- **Link header (RFC 8288)**: HTTP header conveying related URLs (`next`, `prev`, `first`, `last`) for pagination.
- **Long-running operation (LRO)**: work too slow for a synchronous request; submitted async, observed via polling/callbacks.
- **MVCC (Multi-Version Concurrency Control)**: a database technique keeping multiple row versions so readers don't block writers; underlies snapshot/repeatable-read consistency used for consistent exports.
- **NDJSON**: newline-delimited JSON — one JSON object per line; ideal for streaming.
- **Offset/limit pagination**: skip N rows, return M; simple but O(offset) and unstable under writes.
- **OOM (OutOfMemoryError)**: JVM error when the heap is exhausted.
- **Opaque token**: a value the client treats as a black box; the server owns its meaning.
- **p99 latency**: the 99th-percentile latency — the value under which 99% of requests complete.
- **Page token**: the opaque cursor passed between requests for cursor pagination.
- **Partial success**: a batch where some items succeed and others fail, reported per item.
- **PIT (Point-In-Time)**: Elasticsearch snapshot context combined with `search_after` for consistent deep paging.
- **Polling**: client repeatedly querying an operation's status until it's terminal.
- **REST**: an HTTP API style organized around resources and standard verbs.
- **Retry-After**: HTTP header suggesting when a client should retry/poll.
- **Row-value (tuple) comparison**: `(a,b) < (x,y)` evaluated lexicographically; enables single-index keyset seeks.
- **Scroll (Elasticsearch)**: snapshot-based deep paging via a `scroll_id`; for exports, not live UI.
- **search_after (Elasticsearch)**: keyset pagination using the last hit's sort values.
- **Seek method**: synonym for keyset pagination.
- **Server-Sent Events (SSE)**: a one-way server→client streaming protocol over HTTP.
- **Slice (Spring Data)**: a page without a total count (uses `size+1` to know `hasNext`).
- **Snapshot read**: reading a consistent frozen view of data across multiple queries/pages.
- **SSRF (Server-Side Request Forgery)**: tricking a server into making requests to unintended (often internal) destinations; a risk when accepting client-supplied callback URLs.
- **Stable sort key**: a unique, immutable-for-the-session column used as a cursor boundary.
- **Streaming**: writing/reading results incrementally instead of buffering the whole set.
- **Tiebreaker**: a unique column appended to `ORDER BY` to guarantee a total order.
- **Total order**: an ordering where every pair is comparable with deterministic tie resolution.
- **track_total_hits (Elasticsearch)**: setting controlling exact vs capped total-hit counting (default cap 10,000).
- **WAL (Write-Ahead Log)**: the durability log databases write before applying changes; big transactions bloat it.
- **Webhook**: an outbound HTTP callback the server makes to a client URL on an event.
- **work_mem (PostgreSQL)**: per-operation memory budget for sorts/hashes; exceeding it spills to disk.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Key numbers / defaults**
- Spring Boot default page size **20**, max page size **2000**, pages **0-indexed**.
- Stripe `limit` default **10**, max **100**; cursor-only (`starting_after`/`ending_before`).
- GitHub `per_page` max **100**.
- Elasticsearch `max_result_window` **10,000** (offset cap); `track_total_hits` cap **10,000**.
- Common API caps: page_size max ~100–1000; bulk batch max ~100–1000 items.

**Decision rules**
- Large/mutating/feeds → **keyset/cursor** (default). Small + need page jumps → **offset** (cap depth & size). Consistent export → **snapshot (PIT/scroll) behind LRO**. Machine consumer, whole dataset → **stream (NDJSON)**. Slow op → **LRO + idempotent submit + poll/webhook**.
- Always: unique **tiebreaker** in `ORDER BY`; index matching sort cols+directions; clamp page size; avoid `COUNT(*)` (use `has_more`/`limit+1`); idempotency keys on bulk/LRO; sign opaque cursors, never use them as authz.

**Keyset SQL pattern (DESC)**
```sql
WHERE (created_at, id) < (:c, :i)
ORDER BY created_at DESC, id DESC LIMIT :n;   -- index: (created_at DESC, id DESC)
```

**`has_next` without COUNT**: fetch `limit+1`, trim, set token if extra row present.

**Bulk contract**: `207` + `results[]` (per-item status/id/error) + `summary{requested,succeeded,failed}`; `Idempotency-Key`; cap size → `413`.

**LRO contract**: `POST` → `202` + `Location` + `Retry-After`; `GET /operations/{id}` → state machine `QUEUED→RUNNING→{SUCCEEDED,FAILED,CANCELLED}`; idempotent submit; optional webhook.

**Top anti-patterns**: no tiebreaker; deep offset; `COUNT(*)` on hot path; `findAll()` into memory; unbounded page/batch; naive `col<a AND id<b` keyset; transparent cursor; synchronous huge bulk; reused idempotency key with different body.

### Self-test (no answers — active recall)

1. Explain precisely why `LIMIT 20 OFFSET 1000000` cannot be made fast even with a perfect index, and write the keyset SQL (with the correct tuple comparison and required index) that replaces it for a `created_at DESC` feed.
2. Design the full contents of an opaque page token for a list endpoint that supports user-chosen sort and filters, and describe every validation you perform when it comes back — including the security checks.
3. You must build a bulk-update endpoint for 1,000 items where some may fail. Specify the HTTP status, request/response shapes, transaction strategy, size limits, and idempotency behavior — and say when you'd instead make it a long-running operation.
4. Walk through streaming a 5-million-row export in Java end-to-end: the JDBC/driver settings for PostgreSQL *and* MySQL, the wire format, how the client detects truncation, and how you protect the connection pool.
5. A product team needs "page 37 of 412" in the UI but the table has 50M rows and constant inserts. Lay out the options, the tradeoffs of each, and what you'd actually ship and why.
6. Describe the long-running-operation state machine and the failure modes (worker crash, lost webhook, duplicate submission) with the mechanism that mitigates each.
7. Give three distinct causes of "duplicate or missing rows across pages" and the fix for each.

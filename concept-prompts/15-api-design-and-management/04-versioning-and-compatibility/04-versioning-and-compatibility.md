# API Versioning & Compatibility

> A definitive engineering-handbook chapter for senior JVM/backend developers. From first principles through deep internals, with many runnable Java examples, schema-evolution rules, contract testing, decision frameworks, and an interview drill.

---

## 1. Overview & where it fits

### 1.1 What this is

**API versioning and compatibility** is the discipline of *changing an API over time without breaking the programs that depend on it.* An API (Application Programming Interface) is a contract: a set of promises a provider (server, library, schema) makes to its consumers (clients, callers, deserializers) about what inputs are accepted and what outputs are returned. The moment that contract has more than one consumer you no longer control, you can no longer change it freely. Versioning and compatibility are the tools that let you keep evolving anyway.

Two related-but-distinct sub-problems live here:

- **Compatibility** — the *property* that a change does not break existing consumers (or that an old provider can still serve new consumers). This is the goal.
- **Versioning** — the *mechanism* of exposing more than one contract simultaneously (or labeling contracts) so that when you *cannot* preserve compatibility you can still ship the change. This is one of the tools, and a costly one.

The mature stance, repeated throughout this chapter: **prefer compatibility; version only when compatibility is impossible.** A new explicit version (v2) is an admission that you broke the contract. Every version you publish is a maintenance liability that lives as long as your slowest consumer.

### 1.2 The problem it solves

Consider a payments service `POST /charges`. On day one, one team calls it. A year later: 14 internal services, 3 mobile app versions still in users' pockets (you cannot force-upgrade them), 2 partner integrations bound by contract, a batch job, and a Kafka consumer reading the events you emit. You need to add a field, rename a confusing one, tighten a validation rule, and remove a deprecated parameter.

Without a compatibility discipline, any of those changes can:
- Throw a `500` or `400` for a client that sent the "old" shape.
- Cause a deserializer to throw `UnrecognizedPropertyException` on a new field.
- Silently drop data because a consumer never learned about a new enum value.
- Break a mobile app that cannot be updated for weeks because it is pinned in an app store.

The discipline answers: *which changes are safe to ship in place, which require a new version, how do I signal deprecation, and how do I prove I did not break anyone before deploying?*

### 1.3 When you reach for it

- **Always**, the moment an API has a second consumer you do not deploy in lockstep with the provider.
- **Acutely**, when you have **independently deployable services** (microservices) or **independently upgradable clients** (mobile apps, partner integrations, public APIs, SDKs).
- **Especially** for **persisted contracts** — message schemas (Kafka, Protobuf, Avro), event payloads, and database-adjacent serialized blobs — where "old" and "new" data coexist *forever* in topics, logs, and storage.

If your client and server are always built, tested, and deployed together as one unit (a classic monolith with no external API), you have far less to worry about — but the moment a mobile app, a partner, a queue, or a separate deploy pipeline enters the picture, you are in this chapter.

### 1.4 The one-paragraph mental model

**Treat your API as a published contract that must be evolved like a database schema under zero downtime: only make changes that old readers and old writers can tolerate (additive, optional, widening), broadcast intent to remove things long before you remove them (deprecate, then sunset), prove safety automatically (schema-compatibility checks and consumer-driven contract tests) before each deploy, and reserve a brand-new version number for the rare break you cannot avoid — knowing that every version you publish is a tax you pay until the last old consumer is gone.**

---

## 2. Foundations from first principles

We build the vocabulary from zero. Every term a newcomer might not know is defined inline the first time it appears.

### 2.1 Provider, consumer, contract

- **Provider** (a.k.a. *producer*, *server*): the side that *defines and serves* the API. For REST it's the HTTP service; for messaging it's the code that *writes* messages; for a schema it's whoever owns the `.proto`/`.avsc`.
- **Consumer** (a.k.a. *client*, *caller*): the side that *uses* the API. For REST it's the calling code; for messaging it's the code that *reads* messages.
- **Contract**: the agreed shape of the interaction — endpoints/operations, request shape, response shape, status codes, field types, nullability, enum values, ordering guarantees, error formats, and semantics (what a field *means*).

A subtlety that trips people up: in **request/response** APIs the *consumer writes the request* and the *provider writes the response*. So for the request payload, the **client is the producer** of bytes and the **server is the consumer** of bytes; for the response it's reversed. Compatibility reasoning must be done *per message direction*, not per role. We will be precise about this in §2.3.

### 2.2 Wire format vs schema vs semantics

Three layers, often conflated:

- **Wire format**: the actual bytes on the network — JSON text, Protobuf binary, Avro binary, XML, MessagePack. Defines *how* values are encoded.
- **Schema**: the declared structure — fields, types, required/optional, defaults, enum members. May be explicit (`.proto`, `.avsc`, OpenAPI, JSON Schema) or implicit (a POJO + Jackson rules).
- **Semantics**: what the data *means*. `amount: 100` — cents or dollars? `status: "OK"` — does it now also imply "settled"? Semantic changes can break consumers even when the schema is byte-for-byte identical. *Compatibility tooling checks structure; it cannot check meaning.* This is the most dangerous blind spot in the whole topic.

### 2.3 Backward vs forward compatibility (precise definitions)

These two terms are constantly mixed up. Pin them down with the *who-reads-whose-data* test.

- **Backward compatibility**: **new code can read data written by old code.** A *new reader* understands *old data*. In API terms: a **new server accepts requests built by old clients**, and **a new consumer can read old messages**.
  - Mnemonic: "I can read into the past (backward)."

- **Forward compatibility**: **old code can read data written by new code.** An *old reader* tolerates *new data*. In API terms: an **old client can handle responses from a new server**, and an **old consumer can read messages written by a new producer.**
  - Mnemonic: "Old code survives into the future (forward)."

- **Full compatibility**: both backward and forward hold.

Why both matter, made concrete with deploy ordering:

- During a **rolling deploy** of a service, old and new instances run **at the same time** behind the same load balancer. A client (or another service) may hit a new instance, then an old one, in successive calls. The *response* shape must be readable by the client whether old or new served it. The *request* shape must be acceptable whether old or new received it. You need **full compatibility** during the rollout window.
- For **messaging**, producers and consumers are deployed independently and on their own schedules. If producers upgrade first, consumers must read new messages → you need **forward compatibility on the consumer / backward on the read path**. If consumers upgrade first, they must still read old messages → **backward compatibility**. Because you rarely control ordering, **full compatibility** is the safe default for shared topics.

Concrete examples:

| Change | Backward-compatible? (new reads old) | Forward-compatible? (old reads new) |
|---|---|---|
| Add a new **optional** field to a response | Yes (new reader ignores its absence) | Yes *if* old readers ignore unknown fields; **No** if they fail on unknowns |
| Remove a field from a response | Old data had it, new reader doesn't need it → Yes | Old reader expected it, now it's gone → **No (breaks)** |
| Add a required request field | New server demands it, old client never sends it → **No (breaks)** | n/a (server is reader) |
| Rename a field | **No** in both directions (it's remove + add) | **No** |
| Widen a type (int32 → int64) | Often No on read of out-of-range; see §6 | Depends on wire format |

### 2.4 The "tolerant reader" and Postel's Law

- **Tolerant reader**: a consumer written to *ignore what it doesn't recognize* and *not assume more than it needs*. It reads the three fields it cares about and ignores the other forty. This single design choice is what makes **additive changes forward-compatible**.
- **Postel's Law** (the *robustness principle*, from Jon Postel's TCP spec): *"Be conservative in what you send, be liberal in what you accept."* Provider should emit a tight, well-defined shape; consumer should tolerate extra/unknown content. This is the philosophical backbone of evolvable APIs — though see §7.6 for its well-known critique (over-liberal acceptance hides bugs and entrenches sloppy producers).

### 2.5 Why "just version it" is the wrong default

Newcomers reach for "I'll make a v2." But a new version means:
- You now **run and maintain two code paths** (or a translation layer) until every consumer migrates.
- Public/partner consumers may **never** migrate; "temporary" v1 lives for years (Stripe's earliest versions are still served).
- You **multiply** test surface, docs, and on-call surface.

So the default is: **evolve in place via additive, non-breaking changes** (§2.6). Version only when you must break the contract.

### 2.6 Non-breaking vs breaking — the core dichotomy

- **Non-breaking (compatible) change**: existing consumers continue to work with no code change. Generally **additive and optional**.
- **Breaking change**: at least one existing consumer must change to keep working. Generally **removal, rename, narrowing, or semantic change.**

The full catalog is in §6.4. The one-line rule of thumb: **adding optional things is usually safe; removing, renaming, requiring, or narrowing is usually breaking; changing meaning is always dangerous.**

### 2.7 SemVer as the labeling vocabulary

**Semantic Versioning (SemVer)** is the `MAJOR.MINOR.PATCH` convention:
- **MAJOR**: incompatible (breaking) change.
- **MINOR**: backward-compatible feature addition.
- **PATCH**: backward-compatible bug fix.

SemVer is the lingua franca for **libraries/SDKs/artifacts** (a Maven/Gradle dependency). For *network* APIs, only the MAJOR number is usually surfaced (`/v1`, `/v2`); minor/patch evolution happens silently and additively. Keep the two mental models distinct: SemVer for *artifacts you publish*, single integer (or date) for *network endpoints*. (Date-based versioning — e.g., Stripe's `2024-06-20` — is a variant covered in §3.6.)

---

## 3. API versioning strategies (REST/HTTP focus)

When you genuinely must break, you expose multiple versions. There are four mainstream mechanisms plus a few notable variants. We define each, then compare.

### 3.1 URI path versioning

Put the version in the path: `GET /v1/charges`, `GET /v2/charges`.

```
GET https://api.example.com/v2/charges/ch_123
```

- **How it works**: the version is part of the resource locator. Routing is trivial — your gateway/router maps `/v1/**` and `/v2/**` to different controllers or services.
- **Pros**: Maximally visible and explicit; trivially cacheable (the URL is the cache key); easy to browse, log, curl, and document; works through every proxy and CDN with zero config.
- **Cons**: Violates a strict REST/URI purist view that "a URI identifies a resource, not a representation version" (the *same* charge `ch_123` now has two URLs). Version leaks into every client URL, every hyperlink, every bookmark. Hypermedia links must be version-aware.
- **Reality**: by far the **most common in practice** (GitHub historically, Twitter, most internal microservices). Pragmatism wins.

### 3.2 Custom header versioning

Send the version in a request header:

```
GET /charges/ch_123 HTTP/1.1
X-API-Version: 2
```

- **How it works**: routing/dispatch reads the header and selects the handler. The URL stays clean and stable across versions.
- **Pros**: URL identifies the resource (REST-purist friendly); easy to default (missing header → latest or pinned default).
- **Cons**: Invisible in the URL — harder to debug, log, and curl ("why did this break? oh, the header"). **Caching is harder**: shared caches/CDNs key on URL by default, so you must add `Vary: X-API-Version` or you risk serving a v1 body to a v2 request. Custom (`X-`) headers are non-standard (the `X-` prefix was *deprecated* by RFC 6648 — though still widely used).
- **Reality**: common for internal APIs and some platforms; the caching footgun bites teams repeatedly.

### 3.3 Media-type / content-negotiation versioning ("Accept header" versioning)

Encode the version in the `Accept` header via a **vendor media type**:

```
GET /charges/ch_123 HTTP/1.1
Accept: application/vnd.example.charge.v2+json
```

The structure `application/vnd.<vendor>.<type>.<version>+<format>` uses the **`vnd.` (vendor) tree** of MIME types — a registered convention for application-specific media types — and the **`+json` structured-syntax suffix** indicating the underlying syntax is JSON.

- **How it works**: this is **content negotiation** (the HTTP mechanism where the client states preferences via `Accept`, `Accept-Language`, etc., and the server picks a *representation*). Versioning a *representation* is arguably the most RESTful interpretation: the resource is fixed, the *representation* varies.
- **Pros**: Theoretically the most "correct" REST approach; URL identifies the resource; integrates with HTTP caching's `Vary: Accept`.
- **Cons**: Highest friction for humans — verbose, easy to typo, hard to set in a browser; tooling/SDK support uneven; still needs `Vary: Accept` for correct caching. Steeper learning curve for partners.
- **Reality**: GitHub used `application/vnd.github.v3+json` for years; admired in theory, less common in practice because of ergonomics.

### 3.4 Query-parameter versioning

Put the version in the query string: `GET /charges/ch_123?version=2` or `?api-version=2024-06-20`.

- **How it works**: routing reads a query param. Easy to set, easy to default.
- **Pros**: Visible, easy to test in a browser, easy to default; no header plumbing.
- **Cons**: Caching nuance — query string is *part of* the cache key in most CDNs, but some configurations strip unknown query params; mixes API control metadata with resource selectors; can collide with real filtering params. Aesthetic objections (control data in the query string).
- **Reality**: Used by some large platforms — **Azure's REST APIs** famously use `?api-version=2023-...` (date-based). Perfectly workable.

### 3.5 No explicit version ("evolve without versioning")

The preferred default: **don't version; just keep the contract compatible** (§4). The "version" is implicitly "latest, always compatible." This is what you should aim for between major breaks.

### 3.6 Notable variant: date-based / rolling versions

Instead of integers, use **dates**: `Stripe-Version: 2024-06-20`, `api-version=2024-06-20`. Each account is **pinned** to the version active when it integrated; new accounts default to the newest. Stripe internally keeps a chain of **request/response transformers** that upgrade an old request to current and downgrade a current response to the requested old version, so the core only ever speaks "latest." This gives consumers *stability* while the provider ships continuously. It is operationally sophisticated (you maintain a transformer per breaking change forever) but extremely consumer-friendly.

### 3.7 Comparison table

| Strategy | Where version lives | Visibility/debuggability | Caching | REST purity | Defaulting | Typical use |
|---|---|---|---|---|---|---|
| URI path (`/v2/...`) | URL path | Highest | Trivial (URL is key) | Low (resource has N URLs) | URL must include it | Most internal + many public APIs |
| Custom header (`X-API-Version`) | Request header | Low (hidden) | Needs `Vary` | High | Easy (missing → default) | Internal platforms |
| Media type (`Accept: ...v2+json`) | `Accept` header | Lowest (verbose) | Needs `Vary: Accept` | Highest | Easy | GitHub-style, theory-driven |
| Query param (`?version=2`) | Query string | High | CDN-config dependent | Medium | Easy | Azure (date-based) |
| No version (compatible evolution) | n/a | n/a | Trivial | Highest | Always latest | The goal between breaks |
| Date-based (`2024-06-20`) | Header or query | Medium | Needs `Vary` if header | Medium | Pin per consumer | Stripe, Azure |

**Recommendation matrix:**
- **Internal microservices**: URI path `/v1` for simplicity and observability; aim to *never need* `/v2` by evolving compatibly. Pin the major in the service mesh routing.
- **Public REST APIs**: URI path for ergonomics, or date-based if you ship continuously and value consumer stability.
- **Avoid** mixing strategies across one organization — pick one and standardize; inconsistency is a tax on every integrator.

---

## 4. Evolving without versioning (the additive discipline)

This is the most important *practical* section. The goal is to make changes that are **simultaneously backward- and forward-compatible**, so no version bump is needed and no consumer must change.

### 4.1 The golden rules

1. **Only add; never remove or rename in place.** New optional fields, new endpoints, new enum-handling, new optional params.
2. **New fields are optional and have sensible defaults / are nullable.** A consumer that ignores them is unaffected; a consumer that doesn't send them gets default behavior.
3. **Never change the meaning of an existing field.** If meaning must change, *add a new field* and deprecate the old.
4. **Never tighten validation on existing inputs.** Loosening is safe; tightening can reject previously-valid requests (a break).
5. **Make readers tolerant.** Ignore unknown fields; handle unknown enum values gracefully (map to an `UNKNOWN` bucket); don't assume field ordering.
6. **Treat enums as open.** Producers may introduce new members; consumers must not crash on unrecognized members.
7. **Don't repurpose identifiers.** Never reuse a removed field name, a Protobuf field number, or an enum ordinal for a different meaning. Reserve them (§5).

### 4.2 Making JSON consumers tolerant (Jackson)

The single most common production break in JVM REST stacks: a new field appears in a response and an old client's Jackson `ObjectMapper` throws `UnrecognizedPropertyException`. Fix it at the source — **configure all deserializers to ignore unknowns**:

```java
// Global, application-wide: the single most important compatibility setting
// for any JVM service that consumes JSON it does not fully control.
ObjectMapper mapper = JsonMapper.builder()
    // Do NOT fail when the JSON has fields the POJO doesn't declare.
    // This makes the reader FORWARD-compatible: tolerates new producer fields.
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    // Treat a missing primitive as its default rather than throwing.
    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) // careful: see note
    // Don't emit nulls, keeping payloads tight (Postel: conservative send).
    .serializationInclusion(JsonInclude.Include.NON_NULL)
    .build();
```

Or per-class (when you cannot touch the global mapper):

```java
@JsonIgnoreProperties(ignoreUnknown = true) // forward-compatible reader
public record ChargeResponse(
    String id,
    long amountMinorUnits,        // amount in cents; semantics fixed forever
    String currency,
    @JsonInclude(JsonInclude.Include.NON_NULL) String description // optional add
) {}
```

In Spring Boot, the autoconfigured `ObjectMapper` can be tuned via properties so every controller/`RestTemplate`/`WebClient` shares the setting:

```properties
# application.properties — make the whole app a tolerant reader
spring.jackson.deserialization.fail-on-unknown-properties=false
spring.jackson.default-property-inclusion=non_null
```

> **Beginner note — Jackson / ObjectMapper:** Jackson is the de-facto JSON library on the JVM; `ObjectMapper` is its central engine that converts between JSON text and Java objects (POJOs/records). `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` defaults to **`true`**, which means out-of-the-box Jackson *throws* on unknown fields. Flipping it to `false` is what makes your service tolerate additive changes from upstream.

### 4.3 Handling unknown enum values

An open-enum reader must not explode when a producer adds a new member. Jackson supports this directly:

```java
public enum ChargeStatus {
    PENDING, SUCCEEDED, FAILED,
    @JsonEnumDefaultValue UNKNOWN; // unrecognized strings map here
}

// Per-field or global: route unknown enum text to the default member.
mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
// Alternatively map unknowns to null instead of failing:
// mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
```

Without this, the default behavior throws `InvalidFormatException` on an unknown enum string — a classic forward-compat break the day the producer adds `DISPUTED`.

### 4.4 Additive change examples (safe)

```java
// v: response gains an OPTIONAL field. Old clients ignore it (if tolerant);
// new clients use it. Backward + forward compatible.
public record OrderV1(String id, long totalMinorUnits, String currency) {}

public record OrderV1Plus(
    String id,
    long totalMinorUnits,
    String currency,
    String trackingUrl // NEW, optional, nullable, additive — no version bump
) {}
```

```java
// Request gains an OPTIONAL parameter with a default. Old clients omit it
// and get prior behavior; new clients opt into the new behavior.
@GetMapping("/orders")
public Page<Order> list(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size,
    // NEW optional filter; absent => no filtering => old behavior preserved
    @RequestParam(required = false) String status) { ... }
```

### 4.5 The "expand–contract" (a.k.a. parallel-change) pattern

To make a *seemingly breaking* change safely, split it into compatible steps over multiple deploys. This is the workhorse for renames, type changes, and removals.

**Goal: rename `name` → `fullName` in a response** (a pure rename is breaking).

1. **Expand**: server emits **both** `name` and `fullName` (same value). Backward+forward compatible.
2. **Migrate consumers**: each consumer switches to reading `fullName`, at its own pace. Track via metrics on `name` reads if possible.
3. **Deprecate**: announce `name` deprecated, set a sunset date (§4.7).
4. **Contract**: after the sunset date *and* after telemetry confirms zero `name` consumers, remove `name`.

The same three-phase shape (expand → migrate → contract) applies to: changing a field's type (add a new field of the new type, migrate, drop the old), removing an endpoint (deprecate, drain traffic, remove), and splitting a field.

### 4.6 Deprecation policy

A deprecation is a *promise to remove later* plus the runway to do it. A credible policy specifies:
- **What** is deprecated (endpoint, field, param, version).
- **Why** and **what to use instead** (the migration path).
- **When** it will be removed (the **sunset date**) — long enough for your slowest consumer; public APIs commonly use **6–12 months**, internal often **30–90 days**.
- **How** consumers are notified — changelog, email, and **machine-readable HTTP signals** (§4.7).
- **Enforcement**: telemetry to confirm zero usage before removal, plus optional "brownouts" (briefly returning errors before the deadline to flush out stragglers — used by some platforms to force action).

### 4.7 Deprecation & sunset HTTP signaling

Two standardized response headers let a server tell clients programmatically that an endpoint is going away:

- **`Deprecation`** (RFC 9745 / earlier IETF draft): signals the resource is deprecated. Modern form is a boolean-style structured field `Deprecation: true`, or an `@`-prefixed timestamp in the draft (`Deprecation: @1735689600` = the Unix epoch second at which deprecation took/takes effect). *Be explicit about which form your stack emits; tooling support varies.*
- **`Sunset`** (RFC 8594): an **HTTP-date** marking when the resource will stop working: `Sunset: Sat, 31 Jan 2026 23:59:59 GMT`.
- **`Link`** with `rel="sunset"` or `rel="deprecation"`: points to docs/migration guide: `Link: <https://api.example.com/deprecations/charges-v1>; rel="sunset"`.

```java
// Spring controller advice that brands a deprecated endpoint's responses.
@RestControllerAdvice
class DeprecationHeaders {
    void mark(HttpServletResponse resp) {
        resp.setHeader("Deprecation", "true"); // RFC 9745 structured boolean
        resp.setHeader("Sunset", "Sat, 31 Jan 2026 23:59:59 GMT"); // RFC 8594
        resp.addHeader("Link",
            "<https://api.example.com/deprecations/v1>; rel=\"sunset\"");
    }
}
```

> **Beginner note — RFC / IETF draft:** An **RFC** (Request for Comments) is a numbered, published Internet standards document from the IETF (Internet Engineering Task Force). RFC 8594 standardized `Sunset`; the `Deprecation` header was a long-running *draft* later published as **RFC 9745 (2025)**. "Draft" means not-yet-final, so different libraries implement slightly different syntaxes — hence the warning to verify what your stack emits.

---

## 5. Schema evolution for binary formats: Protobuf & Avro

Binary serialization formats are where compatibility rules become *precise and enforceable*, because the schema is explicit and the encoding is rule-governed. These dominate event streaming (Kafka) and high-performance RPC (gRPC). Master the rules — they are the literal source of most messaging incidents.

### 5.1 Why binary formats are special

- **Persisted, long-lived data**: messages sit in Kafka topics, logs, and stores for days/months. Old-schema and new-schema records coexist indefinitely; a consumer *will* read both. Full compatibility is the default requirement.
- **Independent deploy**: producers and consumers ship separately. You cannot assume ordering.
- **Schema registries** (e.g., Confluent Schema Registry) can **enforce** compatibility at publish time, *refusing* an incompatible schema — turning a runtime outage into a CI failure. This is the single biggest operational win in the topic.

> **Beginner note — Protobuf / Avro / gRPC / Kafka:** **Protobuf** (Protocol Buffers) is Google's compact binary serialization format defined by `.proto` files. **Avro** is Apache's binary format defined by JSON `.avsc` schemas, popular in the Hadoop/Kafka world. **gRPC** is Google's RPC framework that uses Protobuf over HTTP/2. **Kafka** is a distributed append-only log / message broker where producers write records that consumers read later, often days later — which is exactly why schema evolution matters there.

### 5.2 Protobuf evolution rules

Protobuf encodes each field as a **field number + wire-type tag**, *not the field name*. The number is the contract; names are for code generation only.

```proto
syntax = "proto3";
message Charge {
  string id = 1;
  int64  amount_minor_units = 2; // cents
  string currency = 3;
  // reserved tags/names protect against accidental reuse (see below)
  reserved 4, 7 to 9;
  reserved "old_description";
}
```

**Safe (compatible) changes:**
- **Add a new field with a *new, unused* number.** Old readers ignore unknown fields (proto stores them as "unknown fields" and even *re-serializes* them, preserving data on round-trips). New readers see the new field; if a producer didn't set it, proto3 gives the **type default** (0, empty string, false, empty list). Backward + forward compatible.
- **Remove a field** — *but you must `reserved` its number and name* so they're never reused. Readers treat the now-absent field as default.
- **Rename a field** — names don't go on the wire, so renaming is wire-compatible. (But it breaks *source code* of consumers regenerating from the `.proto`; coordinate.)
- **Convert between compatible scalar types that share a wire type**, e.g., `int32`/`int64`/`uint32`/`uint64`/`bool` are all varint-encoded and interconvert *with caveats* (truncation/sign issues if values exceed the narrower range). `sint32`/`sint64` use zig-zag and are **not** compatible with the plain int types. `string` and `bytes` are compatible if the bytes are valid UTF-8.
- **`optional` ↔ field presence**: in proto3, scalar fields default to "no presence" (you can't tell unset from default-zero). Adding the `optional` keyword (proto3 field presence) is generally compatible on the wire.

**Breaking changes (never do silently):**
- **Reusing a field number** for a different field/type — catastrophic: old bytes get reinterpreted as garbage. This is why `reserved` exists.
- **Changing a field's type across wire-type boundaries** (e.g., `int32` → `string`, or `int32` → `sint32`) — the decoder misreads the bytes.
- **Changing field numbers** of existing fields.
- **`required` (proto2 only)**: proto3 removed `required` precisely because it's an evolution trap — a reader requiring a field can't read data that omits it, and you can never safely remove a `required` field.
- **Moving a field into/out of a `oneof`** is breaking.

**Field-number ranges to know:** valid field numbers are **1 to 536,870,911 (2^29−1)**; numbers **19000–19999** are *reserved by Protobuf itself* and unusable. Numbers **1–15** use a **single byte** for the tag, so assign them to your most frequent fields for size/speed.

### 5.3 Avro evolution rules

Avro is **schema-on-read with a twist**: the **writer's schema** (used to encode) and the **reader's schema** (the consumer's expectation) are *both* present at decode time, and Avro performs **schema resolution** to reconcile them. This is what makes Avro's evolution model so powerful.

> **Beginner note — schema resolution / reader's vs writer's schema:** When an Avro consumer reads a record, it knows two schemas: the one the data was *written* with and the one it *wants* to read with. Avro's resolution algorithm matches fields **by name**, applies **defaults** for fields the reader expects but the writer didn't have, and **skips** fields the writer wrote but the reader doesn't want. This name-based matching with defaults is the engine behind Avro's compatibility.

**Safe changes (with the right defaults):**
- **Add a field *with a default*.** Backward-compatible: a new reader reading old data uses the default. Without a default, a new reader **cannot** read old data → break. Defaults are mandatory for clean evolution.
- **Remove a field that had a default.** Forward-compatible: an old reader reading new data (which lacks the field) falls back to its default; a new reader simply skips it.
- **Rename via `aliases`.** Avro matches by name, so a raw rename breaks resolution; add the old name as an **`alias`** so the reader still matches.
- **Widen via union or promotion.** Avro permits certain numeric **promotions** during resolution: `int → long → float → double`, and `string ↔ bytes`. Reader can be the wider type and still read narrower-written data.
- **Make a field nullable** by changing its type to a **union** `["null", "string"]` *with default `null`* — this is the canonical "optional field" in Avro.

**Breaking changes:**
- Adding a field **without a default** (new readers can't read old data).
- Removing a field **without a default** (old readers can't read new data).
- **Changing a field's type** outside the allowed promotions.
- **Renaming without an alias.**
- Changing a field from non-null to null without handling defaults.

**Avro union/default gotcha:** for a union type, the **default value must match the *first* type listed in the union**. So `["null","string"]` with default `null` is valid; `["string","null"]` with default `null` is **invalid**. This trips up nearly everyone once.

### 5.4 Confluent Schema Registry compatibility modes

The registry stores schemas per **subject** (usually `<topic>-value` and `<topic>-key`) and *checks* a new schema against prior ones before allowing registration. The modes:

| Mode | Checks new schema against | Allowed changes | Guarantees | Upgrade first |
|---|---|---|---|---|
| `BACKWARD` (default) | Latest registered | Delete fields; add **optional** fields | New consumer reads data from last producer | **Consumers** |
| `BACKWARD_TRANSITIVE` | **All** previous | same | reads **all** historical data | Consumers |
| `FORWARD` | Latest | Add fields; delete **optional** fields | Old consumer reads data from new producer | **Producers** |
| `FORWARD_TRANSITIVE` | All previous | same | old consumers read all future data | Producers |
| `FULL` | Latest | add/delete **optional** fields only | both directions vs latest | Either |
| `FULL_TRANSITIVE` | All previous | add/delete optional only | both directions, all versions | Either |
| `NONE` | nothing | anything | none — you're on your own | — |

The **default is `BACKWARD`**. "Transitive" means the check runs against *every* historical version, not just the latest — essential when very old data still lives in the topic. Choose `FULL_TRANSITIVE` for shared, long-lived event topics where you can't control deploy order and old data persists; it's the strictest and safest.

> **Beginner note — "subject":** A *subject* is the registry's name for a schema's evolution history (a versioned list of schemas). By the default `TopicNameStrategy`, the subject is `<topic>-value`, so all records on a topic share one evolving schema lineage.

### 5.5 Worked Avro evolution example

```json
// v1 writer schema (avsc)
{ "type": "record", "name": "Charge", "namespace": "com.example",
  "fields": [
    { "name": "id", "type": "string" },
    { "name": "amountMinorUnits", "type": "long" }
  ]
}
```

```json
// v2 — ADD an optional field WITH a default (BACKWARD-compatible).
// New reader reading v1 data uses default "USD".
{ "type": "record", "name": "Charge", "namespace": "com.example",
  "fields": [
    { "name": "id", "type": "string" },
    { "name": "amountMinorUnits", "type": "long" },
    { "name": "currency", "type": "string", "default": "USD" },
    { "name": "description",
      "type": ["null", "string"], "default": null }   // nullable optional
  ]
}
```

The registry, in `BACKWARD`/`FULL` mode, will **accept** v2 because both new fields have defaults. If you had added `"currency": "string"` **without** a default, the registry would **reject** the registration — catching the break in CI rather than in production.

---

## 6. Code examples by use case (Java-first)

Several *different* scenarios, not variations of one.

### 6.1 Spring Boot: URI-path versioning with shared logic

```java
// Two controllers, two URL prefixes. v2 differs only in the response shape.
@RestController
@RequestMapping("/v1/charges")
class ChargeV1Controller {
    private final ChargeService svc;
    ChargeV1Controller(ChargeService svc) { this.svc = svc; }

    @GetMapping("/{id}")
    ChargeV1 get(@PathVariable String id) {
        Charge c = svc.find(id);
        // v1 exposed a single "amount" in dollars (legacy semantic).
        return new ChargeV1(c.id(), c.amountMinorUnits() / 100.0, c.currency());
    }
}

@RestController
@RequestMapping("/v2/charges")
class ChargeV2Controller {
    private final ChargeService svc;
    ChargeV2Controller(ChargeService svc) { this.svc = svc; }

    @GetMapping("/{id}")
    ChargeV2 get(@PathVariable String id) {
        Charge c = svc.find(id);
        // v2 fixes the dangerous float-dollars to explicit minor units + status.
        return new ChargeV2(c.id(), c.amountMinorUnits(), c.currency(), c.status());
    }
}
// Key idea: ONE domain service, thin per-version mapping layers. Never fork
// business logic per version; only fork the representation.
record ChargeV1(String id, double amount, String currency) {}
record ChargeV2(String id, long amountMinorUnits, String currency, String status) {}
```

### 6.2 Spring Boot: header-based version dispatch (single endpoint)

```java
@RestController
@RequestMapping("/charges")
class ChargeController {
    private final ChargeService svc;
    ChargeController(ChargeService svc) { this.svc = svc; }

    // Route by header value WITHOUT exploding into many methods:
    // Spring matches on the 'headers' condition; missing header => default v1.
    @GetMapping(value = "/{id}", headers = "X-API-Version=2")
    ResponseEntity<ChargeV2> getV2(@PathVariable String id) {
        Charge c = svc.find(id);
        return ResponseEntity.ok()
            .header("Vary", "X-API-Version") // REQUIRED for cache correctness
            .body(new ChargeV2(c.id(), c.amountMinorUnits(), c.currency(), c.status()));
    }

    @GetMapping("/{id}") // no header / X-API-Version=1 => v1 default
    ResponseEntity<ChargeV1> getV1(@PathVariable String id) {
        Charge c = svc.find(id);
        return ResponseEntity.ok()
            .header("Vary", "X-API-Version")
            .body(new ChargeV1(c.id(), c.amountMinorUnits() / 100.0, c.currency()));
    }
}
```

### 6.3 Media-type (content negotiation) versioning

```java
@RestController
@RequestMapping("/charges")
class ChargeMediaTypeController {
    // produces= drives content negotiation on the Accept header.
    @GetMapping(value = "/{id}",
        produces = "application/vnd.example.charge.v2+json")
    ChargeV2 getV2(@PathVariable String id, ChargeService svc) {
        Charge c = svc.find(id);
        return new ChargeV2(c.id(), c.amountMinorUnits(), c.currency(), c.status());
    }

    @GetMapping(value = "/{id}",
        produces = "application/vnd.example.charge.v1+json")
    ChargeV1 getV1(@PathVariable String id, ChargeService svc) {
        Charge c = svc.find(id);
        return new ChargeV1(c.id(), c.amountMinorUnits() / 100.0, c.currency());
    }
}
// Remember to set Vary: Accept on responses (Spring often handles this).
```

### 6.4 Breaking vs non-breaking change catalog (reference)

| Change | REST/JSON | Protobuf | Avro | Verdict |
|---|---|---|---|---|
| Add optional response field | Safe (tolerant reader) | Safe (new number) | Safe (with default) | **Non-breaking** |
| Add **required** request field | Breaks old clients | n/a | n/a | **Breaking** |
| Remove a response field | Breaks readers needing it | Safe + `reserved` | Safe if had default | **Breaking** (REST) / conditional |
| Rename a field | Breaking | Wire-safe, code-breaking | Safe with `alias` | **Breaking** (REST) |
| Change field type (narrow) | Breaking | Breaking across wire types | Breaking outside promotions | **Breaking** |
| Widen type (int→long) | Usually safe; JS number range caveat | Safe (same varint) | Safe (promotion) | Mostly non-breaking |
| Add enum value | Breaks non-tolerant readers | Safe (unknown→default) | Safe if reader tolerant | **Breaking unless reader tolerant** |
| Remove enum value | Breaking if value still emitted historically | risky | risky | **Breaking** |
| Tighten validation (e.g., maxLength) | Rejects previously-valid input | n/a | n/a | **Breaking** |
| Loosen validation | Safe | n/a | n/a | **Non-breaking** |
| Make optional field required | Breaking | Breaking | Breaking | **Breaking** |
| Make required field optional | Safe for server; clients already send it | Safe | Safe | **Non-breaking** |
| Change error code / status semantics | Breaking (clients branch on codes) | n/a | n/a | **Breaking** |
| Change default value of a field | **Semantic breaking** | semantic | semantic | **Breaking** (silent!) |
| Change field meaning (units, etc.) | Silent breaking | silent | silent | **Worst kind** |
| Reorder array / change pagination | Often breaking | n/a | n/a | **Breaking** |

### 6.5 Consumer-driven contract testing with Pact (JUnit 5)

**Consumer-driven contract testing (CDC)** flips integration testing: the **consumer** declares exactly what it needs from the provider ("when I GET /charges/ch_123 I expect a 200 with these fields"), producing a **pact** (a JSON contract). The **provider** then runs that pact against itself in *its own* test suite and fails if it would break the consumer. This catches breaking changes **at the provider's build time**, before deploy, without spinning up the real consumer.

> **Beginner note — Pact / broker:** **Pact** is the most popular CDC framework (polyglot). A **Pact Broker** is a server that stores pacts and verification results and supports `can-i-deploy` — a gate that answers "is it safe to deploy provider version X given all the consumers depending on it?" It encodes compatibility into the deploy pipeline.

**Consumer side** — defines the expectation and generates the pact:

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "charges-provider")
class ChargesConsumerPactTest {

    @Pact(consumer = "billing-consumer")
    V4Pact getCharge(PactDslWithProvider builder) {
        return builder
            .given("charge ch_123 exists")           // provider state
            .uponReceiving("a request for charge ch_123")
                .path("/v2/charges/ch_123").method("GET")
            .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                // Only assert what THIS consumer actually consumes — being
                // strict about the whole body would make additive changes
                // (new fields) falsely "break" the contract.
                .body(newJsonBody(o -> {
                    o.stringType("id", "ch_123");
                    o.numberType("amountMinorUnits", 1999);
                    o.stringType("currency", "USD");
                }).build())
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "getCharge")
    void verifyConsumerCanReadCharge(MockServer mock) {
        var client = new ChargesClient(mock.getUrl()); // points at Pact mock
        Charge c = client.getCharge("ch_123");
        assertEquals(1999, c.amountMinorUnits());      // consumer logic works
    }
}
```

**Provider side** — verifies the real provider satisfies the pact:

```java
@Provider("charges-provider")
@PactBroker(url = "https://pacts.example.com") // or @PactFolder for local
class ChargesProviderPactVerificationTest {

    @BeforeEach
    void setTarget(PactVerificationContext ctx) {
        ctx.setTarget(new HttpTestTarget("localhost", 8080)); // running provider
    }

    @State("charge ch_123 exists") // sets up the data the pact assumed
    void chargeExists() { testData.insertCharge("ch_123", 1999, "USD"); }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext ctx) {
        ctx.verifyInteraction(); // replays the consumer's expectations
    }
}
```

In CI, the provider pipeline runs the verification and then calls `pact-broker can-i-deploy --pacticipant charges-provider --version $GIT_SHA --to-environment production`; if any consumer's pact would break, the deploy is blocked. This is the *enforcement* counterpart to a schema registry, but for REST/HTTP.

### 6.6 Tolerant gRPC/Protobuf consumer with default-safe enums

```proto
syntax = "proto3";
message ChargeEvent {
  string id = 1;
  // ALWAYS reserve 0 as the UNSPECIFIED member so unknown/new values
  // (and unset) map to a safe default rather than a meaningful one.
  enum Status {
    STATUS_UNSPECIFIED = 0;
    PENDING = 1;
    SUCCEEDED = 2;
    FAILED = 3;
    // DISPUTED = 4; // added later: old readers see UNSPECIFIED-equivalent
  }
  Status status = 2;
}
```

```java
// Old generated code reading a message whose status=4 (DISPUTED) it doesn't
// know: proto returns the enum's UNRECOGNIZED sentinel; handle it explicitly.
switch (event.getStatus()) {
    case PENDING, SUCCEEDED, FAILED -> process(event);
    case STATUS_UNSPECIFIED, UNRECOGNIZED -> {
        // Forward-compatible: don't crash on a value coined after this build.
        metrics.increment("charge.status.unknown");
        deadLetterOrDefer(event);
    }
    default -> throw new IllegalStateException("unreachable");
}
```

### 6.7 OpenAPI-diff breaking-change gate in CI

```bash
# Fail the build if the new spec breaks the old one. openapi-diff returns a
# non-zero exit code on incompatible changes; wire it as a required check.
docker run --rm -v "$PWD":/specs openapitools/openapi-diff:latest \
  --fail-on-incompatible \
  /specs/openapi-main.yaml \
  /specs/openapi-pr.yaml
```

This is the cheapest, highest-leverage guard for REST APIs described by OpenAPI: a machine, not a reviewer's memory, decides whether the PR breaks the contract.

---

## 7. Implementation concerns & best practices

### 7.1 Performance & cost

- **Caching correctness vs strategy**: header/media-type versioning *requires* `Vary` headers or shared caches/CDNs will cross-serve representations (a v1 body to a v2 request) — a correctness bug that also wrecks cache hit ratio (a separate `Vary` value per version fragments the cache). URI-path versioning sidesteps both: each version is a distinct URL.
- **Translation-layer cost**: date-based versioning's request/response transformers (§3.6) add CPU and a maintenance burden that grows with every breaking change *forever*. Budget for it.
- **Field bloat**: "additive forever" payloads grow. Periodically run the expand–contract cycle to actually *remove* dead fields after telemetry confirms zero readers; otherwise responses bloat and Protobuf field numbers leak meaning.
- **Protobuf tag sizing**: keep the hottest fields in numbers 1–15 (single-byte tags).

### 7.2 Correctness & concurrency

- **Rolling-deploy window**: during a rollout, full compatibility is mandatory because both versions serve concurrently behind the LB. Test the *mixed* state, not just before/after.
- **Idempotency & versioning interplay**: a change to whether an operation is idempotent, or to the idempotency-key semantics, is a breaking change even if the schema is unchanged.
- **Don't reuse identifiers** (field numbers, names, enum ordinals) — `reserved` them. Reuse causes silent data corruption, the hardest class of bug to detect.

### 7.3 Security

- **New optional fields can leak data**: an additive field that exposes PII (Personally Identifiable Information) to consumers that shouldn't see it is "compatible" but a security regression. Run authz/data-classification review on additive changes too.
- **Tolerant readers and mass assignment**: `FAIL_ON_UNKNOWN_PROPERTIES=false` on a *request* deserializer means clients can send fields you silently ignore — fine for reads, but ensure you never bind unexpected fields into privileged objects (the *mass-assignment* vulnerability). Bind requests to explicit DTOs, not domain entities.
- **Deprecated endpoints linger as attack surface**: old versions often lack newer security fixes. Sunset and *remove* them, don't just deprecate.

### 7.4 Observability

- **Per-version metrics**: tag requests/responses with the resolved version; you cannot retire v1 until you can *prove* zero traffic. Emit `api_requests_total{version="v1"}` and per-field read telemetry where feasible.
- **Deprecation hit counters**: count calls to deprecated endpoints/fields, broken down by consumer (API key / service identity) so you know exactly who to chase.
- **Schema registry / Pact broker dashboards**: the registry's compatibility-rejection events and the broker's `can-i-deploy` failures are leading indicators of attempted breaks.

### 7.5 Testing & production hardening

- **Three complementary gates**: (1) schema-registry compatibility checks for messaging; (2) Pact/CDC for REST; (3) OpenAPI-diff for spec-described REST. Use all that apply.
- **Test old clients against new servers and vice versa** explicitly (golden-payload tests: keep recorded old payloads and assert the new code still deserializes them).
- **Canary + per-version error rates**: deploy the new version to a slice, watch deserialization errors and 4xx/5xx by version.
- **Brownouts**: for internal APIs, deliberately fail deprecated endpoints for short windows before the deadline to surface forgotten consumers while you can still roll back.

### 7.6 Anti-patterns

- **Versioning everything by default** ("we ship v2 every sprint"): explodes maintenance; the right default is compatible evolution.
- **Renaming fields in place** to "clean up": pure break. Use expand–contract.
- **Strict readers in CDC tests**: asserting the *entire* response body in a Pact makes harmless additive changes fail. Assert only what you consume.
- **Reusing Protobuf field numbers / Avro names** after removal: silent corruption. Always `reserved`/`alias`.
- **`required` in proto2 / no defaults in Avro**: evolution traps.
- **No sunset date**: "deprecated" with no deadline is just a label nobody acts on.
- **Changing semantics under a stable schema**: the worst, because no structural tool catches it. Add a *new* field instead.
- **Over-applying Postel's Law**: being *too* liberal in what you accept hides producer bugs and lets malformed data accumulate. Be tolerant of *unknown* additions, strict about *known* fields' validity. (This is the standard modern critique of the robustness principle.)

---

## 8. Advanced topics & deep internals

### 8.1 Protobuf wire format internals (why the rules are what they are)

Protobuf encodes each field as a **tag** = `(field_number << 3) | wire_type`, varint-encoded, followed by the value.

- **Wire types**: `0` = varint (int32/64, uint, bool, enum), `1` = 64-bit fixed, `2` = length-delimited (string, bytes, embedded messages, packed repeated), `5` = 32-bit fixed. (Types 3/4 were groups, deprecated.)
- **Why int32↔int64 is safe**: both are wire type 0 (varint) — the same bytes decode either way (subject to range).
- **Why int32↔sint32 is unsafe**: `sint*` uses **zig-zag encoding** (maps signed to unsigned so small-magnitude negatives stay short) — same wire type 0, but the *bit pattern means something different*, so the value comes out wrong.
- **Why field-number reuse is catastrophic**: the decoder dispatches purely on field number + wire type; reusing 2 for a different type makes it try to decode old bytes as the new type → garbage or exception.
- **Unknown-field preservation**: a decoder that meets an unknown field number stores the raw bytes in an "unknown fields" set and **re-emits them on serialization**, so a proxy that round-trips a message it only partially understands won't drop the new fields. This is a deep forward-compat superpower.
- **proto3 presence**: scalars default to "no presence" — you can't distinguish unset from zero/empty, which is why "did the producer set amount=0 or just not send it?" is ambiguous unless you use `optional` (which adds a presence bit) or wrapper types (`google.protobuf.Int64Value`).

### 8.2 Avro schema resolution internals

Avro decoding requires **both** schemas. Resolution rules:
- Match record fields **by name** (aliases extend this).
- Reader field absent in writer → use reader's **default** (or error if none).
- Writer field absent in reader → **skip** its bytes.
- Numeric **promotion** ladder applied on mismatch: `int→long→float→double`, `string↔bytes`.
- Enum symbol unknown to reader → error unless reader's enum specifies a **default** (Avro 1.9+ enum defaults).
- Union resolution picks the matching branch by writer's index/type.

Because the writer schema must be available, Kafka+Avro embeds a **schema ID** (not the whole schema) in each message via the Confluent **wire format**: a 1-byte magic `0x00`, a 4-byte big-endian schema ID, then the Avro body. The consumer fetches the writer schema from the registry by ID and resolves against its own reader schema. This is why the registry is on the hot path conceptually (with caching) and why a registry outage can stall consumption.

### 8.3 JSON's quiet compatibility traps

- **Number precision**: JSON has one number type; **JavaScript** parses numbers as IEEE-754 doubles, so int64 values above `2^53` lose precision. Widening a field to int64 is "compatible" on the JVM but silently corrupts JS clients. Mitigation: serialize large IDs/amounts as **strings**.
- **Absent vs null vs default**: three distinct states that consumers conflate. `Optional<>` fields, `@JsonInclude(NON_NULL)`, and explicit tri-state DTOs disambiguate.
- **Field ordering**: JSON object key order is not significant, but some naive parsers and signature/canonicalization schemes assume it — don't.

### 8.4 GraphQL & gRPC versioning philosophies (contrast)

- **GraphQL** discourages versioning entirely: clients ask for exactly the fields they want, so adding fields/types is inherently additive, and you **deprecate fields** with `@deprecated(reason: "...")` rather than minting versions. The compatibility burden shifts to "never change/remove a field a client still selects," enforced by **schema-diff** tools (e.g., GraphQL Inspector) and field-usage analytics. Breaking changes: removing a field/type, changing a type, making a nullable field non-nullable on input or vice versa on output.
- **gRPC**: relies on Protobuf evolution (§5.2, §8.1). You almost never "version" a gRPC service; you evolve the `.proto` compatibly. When you must break, you add a new *method* or a new *service* (e.g., `ChargeServiceV2`) rather than a path version.

### 8.5 Date-based version-transformer chains (deep dive)

Stripe-style design: the core domain always speaks "today." Each historical breaking change is captured as a small, ordered, reversible **transformer** (e.g., "in version ≥ 2024-06-20, `amount` is minor units; before, it was a float dollars"). On request, transformers from the caller's pinned version up to current are applied in order; on response, the inverse chain runs top-down to the caller's version. Properties: the core stays clean; transformers are independently testable; the chain grows monotonically (you never delete a transformer because some account is still pinned). The cost is a permanent, ever-growing transformer library and the discipline to write a transformer pair for every break.

### 8.6 Lesser-known behaviors

- **HTTP `Vary` and CDNs**: many CDNs *ignore* or limit `Vary` on certain headers; verify your CDN honors `Vary: Accept` / `Vary: X-API-Version` before relying on header versioning behind a cache.
- **Protobuf JSON mapping**: proto3 has a canonical JSON mapping; unknown JSON fields are *ignored by default* in many runtimes but can be configured to error — check your library's `ignoringUnknownFields()` setting (gRPC-gateway, `JsonFormat`).
- **Avro default for union must match first type** (§5.3) — a parse-time error, not a runtime one, so it fails loudly in CI (good).
- **Schema registry `NONE` mode** disables all checks — sometimes set "temporarily" and forgotten, removing your safety net. Audit registry config as code.

---

## 9. Tradeoffs & decision frameworks

### 9.1 Choosing a versioning strategy

| If you… | Prefer | Because |
|---|---|---|
| Run internal microservices | URI path `/v1`, evolve compatibly | Observability, trivial routing/caching; aim never to need v2 |
| Run a public REST API for many integrators | URI path or date-based | Ergonomics; date-based gives consumer stability |
| Ship continuously and value consumer stability | Date-based + transformers | Consumers pin; you keep moving |
| Want strict REST semantics | Media-type negotiation | Resource URL stable, representation varies |
| Use gRPC | No versioning; evolve `.proto`; new method/service for breaks | Protobuf evolution rules suffice |
| Use GraphQL | No versioning; deprecate fields | Selection sets make adds non-breaking |
| Stream events on Kafka | Schema (Avro/Protobuf) + registry `FULL_TRANSITIVE` | Old + new data coexist forever |

### 9.2 Compatibility-mode selection (messaging)

- **`BACKWARD`/`BACKWARD_TRANSITIVE`** — when consumers upgrade first (common for command topics where you control consumers). Use *transitive* if old data persists.
- **`FORWARD`/`FORWARD_TRANSITIVE`** — when producers upgrade first and consumers lag (common for events with many independent consumers you don't control).
- **`FULL`/`FULL_TRANSITIVE`** — when you cannot guarantee order (shared public-ish topics). Strictest, safest, but only allows add/remove of *optional* fields.
- **`NONE`** — never on a shared topic; only for a topic with a single tightly-coupled producer+consumer pair, and even then, reconsider.

### 9.3 Version vs evolve — the decision rule

```
Is the change additive + optional (and meaning unchanged)?
  └─ Yes → evolve in place. No version. Ship it.
  └─ No  → Can you express it via expand–contract over several deploys?
            └─ Yes → do that; still no new version.
            └─ No  → It's a genuine break. Mint a new MAJOR version,
                     deprecate the old with a sunset date, run dual
                     stack, migrate consumers, then remove.
```

### 9.4 Alternatives to versioning

- **Feature flags / capability negotiation**: client announces capabilities; server adapts. Avoids version proliferation for *behavioral* (not structural) variation.
- **Translation/anti-corruption layer**: a gateway upgrades old requests / downgrades new responses (the date-based transformer pattern generalized).
- **Field deprecation in-schema** (GraphQL `@deprecated`, Protobuf `deprecated = true` option): soft signal without versioning.

---

## 10. Failure modes & debugging

### 10.1 `UnrecognizedPropertyException` on a new field (REST/JVM)

- **Symptom**: consumer throws `com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException` after the provider added a field; or a 500/parse error in a downstream service.
- **Root cause**: a non-tolerant reader (`FAIL_ON_UNKNOWN_PROPERTIES=true`, Jackson's default).
- **Diagnose**: grep logs for `UnrecognizedPropertyException`; reproduce by deserializing the new payload with the consumer's `ObjectMapper` config. Check `spring.jackson.deserialization.fail-on-unknown-properties`.
- **Fix**: make readers tolerant (§4.2); long-term, enforce CDC/OpenAPI-diff so the provider learns before shipping.

### 10.2 Unknown enum value crashes consumer

- **Symptom**: `InvalidFormatException`/`UNRECOGNIZED` after a new enum member is emitted.
- **Fix**: open-enum handling (§4.3, §6.6) — `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` / a `0`/`UNSPECIFIED` member; route unknowns to a dead-letter or default branch.

### 10.3 Schema registry rejects a producer

- **Symptom**: producer fails on send with `Schema being registered is incompatible with an earlier schema` (HTTP 409 from the registry).
- **Diagnose**: `curl <registry>/subjects/<topic>-value/versions` to list history; `curl <registry>/config/<subject>` for the mode; diff the new `.avsc`/`.proto` against the latest. This is the registry *working as intended* — it blocked a break.
- **Fix**: add a default / make the field optional / `reserved` removed numbers; or, if the break is intentional and coordinated, evolve consumers first and adjust the compatibility mode deliberately (not casually to `NONE`).

### 10.4 Cache cross-serving wrong version

- **Symptom**: a v2 client intermittently receives a v1 body (or vice versa) when behind a CDN/shared cache, with header/media-type versioning.
- **Root cause**: missing `Vary: X-API-Version` / `Vary: Accept`, so the cache keys only on URL.
- **Diagnose**: inspect response headers (`curl -i`), check CDN cache key config, reproduce with two `Accept`/header values against the same URL.
- **Fix**: emit the correct `Vary`; confirm the CDN honors it; or switch to URI-path versioning to make the URL the cache key.

### 10.5 Silent semantic break

- **Symptom**: no errors, but downstream numbers are wrong (e.g., amounts off by 100×) after a deploy that changed `amount` from dollars to cents under the same schema.
- **Root cause**: meaning change with unchanged structure — invisible to schema/contract tools.
- **Diagnose**: business-metric anomaly detection, reconciliation reports, golden-payload tests that pin *expected values*, not just shapes.
- **Fix**: never change meaning in place — add `amountMinorUnits` and deprecate `amount`. Add semantic assertions to contract tests.

### 10.6 int64 precision loss in JS clients

- **Symptom**: large IDs/amounts round to nearby values in browser/Node consumers after widening to int64.
- **Root cause**: JS IEEE-754 doubles can't represent integers above 2^53 exactly (§8.3).
- **Fix**: serialize big integers as strings; document it; add a JS-consumer contract test.

### 10.7 Real-world incident patterns

- **Twitter's elevated-traffic versioning friction** and many public APIs' multi-year v1 tails illustrate that "temporary" versions become permanent — the cost of *not* preferring compatible evolution.
- **Kafka pipelines** routinely suffer org-wide consumer crashes when a producer ships an Avro field without a default and the registry was set to `NONE` or `BACKWARD` (not transitive) while old data persisted — the canonical argument for `FULL_TRANSITIVE` on shared topics.
- **gRPC field-number reuse** after a hasty removal has caused silent data corruption in production at multiple orgs — the canonical argument for `reserved`.

---

## 11. Interview drill

**Q1. Define backward vs forward compatibility precisely, and give an HTTP example of each.** *(recall + precision)*
- **Model answer**: Backward = *new code reads old data* (new server accepts old client's request; new consumer reads old messages). Forward = *old code reads new data* (old client tolerates new server's response; old consumer reads new producer's messages). HTTP example: adding an optional response field is forward-compatible only if old clients ignore unknown fields; removing a request field the server still requires is backward-incompatible.
- **Follow-ups:** (a) *Why do you need both during a rolling deploy?* Old and new instances serve concurrently, so requests/responses must be valid in either direction during the window. (b) *Which one do messaging consumers need if producers deploy first?* Forward (old consumers must read new data) — practically, full. (c) *Give a change that's backward but not forward compatible.* Removing a response field: a new reader doesn't need it (backward ok), but an old reader that expected it breaks (forward no).

**Q2. Walk through the four REST versioning strategies and their tradeoffs.** *(recall)*
- **Model answer**: URI path (visible, cache-trivial, REST-impure), custom header (clean URL, needs `Vary`, hidden), media-type/content-negotiation (most RESTful, verbose, needs `Vary: Accept`), query param (visible, CDN-config-dependent). See §3.7.
- **Follow-ups:** (a) *Why does header versioning complicate caching?* Caches key on URL; without `Vary` they cross-serve. (b) *Which is most "RESTful" and why?* Media-type — the resource URL is stable; only the representation varies. (c) *What do you actually recommend internally?* URI path, but aim to evolve compatibly so v2 is never needed.

**Q3. How do you add a field to a JSON API without breaking anyone?** *(applied)*
- **Model answer**: Make it optional/nullable with a default; ensure consumers ignore unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES=false`); don't change existing semantics. No version bump.
- **Follow-ups:** (a) *What's Jackson's default and why is it dangerous?* Default `true` → throws on unknowns → forward-compat break. (b) *Where do you set it app-wide in Spring?* `spring.jackson.deserialization.fail-on-unknown-properties=false`. (c) *What about a new enum value?* Open-enum handling / `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`.

**Q4. Explain the expand–contract (parallel-change) pattern for renaming a field.** *(applied)*
- **Model answer**: Emit both old+new (expand); migrate consumers to new; deprecate old with sunset; remove old after telemetry shows zero readers (contract). Each step is individually compatible.
- **Follow-ups:** (a) *How do you know it's safe to remove?* Per-field/endpoint usage telemetry by consumer. (b) *Apply it to a type change.* Add a new field of the new type, migrate, drop old. (c) *What if a consumer never migrates?* Brownouts / hard sunset / direct outreach; for public APIs, keep both longer.

**Q5. Protobuf: which changes are safe and which break the wire? Why?** *(internals)*
- **Model answer**: Safe: add field with new number, remove+`reserved`, rename (names aren't on the wire), int32↔int64 (same varint wire type). Breaking: reuse a field number, change across wire types (int32↔string, int32↔sint32 zig-zag), change existing numbers, proto2 `required`.
- **Follow-ups:** (a) *Why is int32↔sint32 unsafe though both are varint?* sint uses zig-zag — same wire type, different bit meaning. (b) *What does `reserved` protect against?* Accidental reuse of a removed number/name → silent corruption. (c) *Why did proto3 drop `required`?* It's an evolution trap — readers can't tolerate its absence and you can never remove it.

**Q6. Avro: how does schema resolution enable evolution, and what's the role of defaults?** *(internals)*
- **Model answer**: Decode uses both writer and reader schemas; fields match by name (+aliases), missing reader fields get defaults, extra writer fields are skipped, numeric promotions apply. Defaults are what let a new reader read old data (added field) and an old reader read new data (removed field).
- **Follow-ups:** (a) *Add a field without a default — what happens?* New reader can't read old data → registry rejects under BACKWARD/FULL. (b) *Union default gotcha?* Default must match the union's *first* type → `["null","string"]` default null. (c) *How is the writer schema available at read time in Kafka?* Confluent wire format embeds a 4-byte schema ID; consumer fetches it from the registry.

**Q7. What are Schema Registry compatibility modes, what's the default, and when do you use TRANSITIVE/FULL?** *(applied)*
- **Model answer**: BACKWARD (default), FORWARD, FULL, their TRANSITIVE variants, and NONE. BACKWARD = consumers upgrade first; FORWARD = producers first; FULL = either; TRANSITIVE checks all historical versions, needed when old data persists; FULL_TRANSITIVE for shared, long-lived event topics.
- **Follow-ups:** (a) *Default and what it allows?* BACKWARD; delete fields + add optional fields. (b) *Why TRANSITIVE matters?* Old data still in the topic must remain readable. (c) *When is NONE acceptable?* Essentially only a single tightly-coupled producer/consumer; risky.

**Q8. Explain consumer-driven contract testing and how it differs from end-to-end tests.** *(applied)*
- **Model answer**: Consumers declare exactly what they need → a pact; the provider verifies the pact in its own build, failing if it would break a consumer. Unlike E2E, no live systems are wired together; it's fast, runs in CI, and pinpoints the broken contract. A broker + `can-i-deploy` gates deploys.
- **Follow-ups:** (a) *Why assert only the fields you consume?* So additive provider changes don't falsely fail. (b) *How does it catch a break before deploy?* Provider build verifies all consumer pacts. (c) *Registry vs Pact — when each?* Registry for messaging schemas; Pact for REST/HTTP interactions.

**Q9 (senior-signal). When would you deliberately ship a breaking change and mint v2, versus contorting to stay compatible?** *(tradeoff/justification)*
- **Model answer**: Mint v2 only when the change cannot be expressed additively or via expand–contract *and* the cost of carrying a confusing/compromised contract (developer confusion, perf, security, semantic correctness) exceeds the dual-stack maintenance cost. Even then, plan deprecation+sunset+migration up front, and prefer date-based pinning or a translation layer over a hard fork if many consumers can't migrate. The key judgment: every version is a permanent tax; compatible contortions are usually cheaper, but not always — a deeply wrong semantic (amount in dollars-as-float) may justify a clean break.
- **Follow-ups:** (a) *How do you bound the cost of v1's tail?* Sunset date + telemetry + brownouts + per-consumer outreach. (b) *Public vs internal difference?* Public tails are far longer (6–12+ months); internal can be 30–90 days. (c) *Alternative to v2?* Translation/transformer layer (Stripe model), feature flags, GraphQL field deprecation.

**Q10 (senior-signal). Design a versioning + compatibility strategy for a company with mobile apps, partner REST APIs, and internal Kafka events. Justify each choice.** *(architecture/justification)*
- **Model answer**: *Internal services*: URI-path `/v1`, evolve compatibly, OpenAPI-diff gate, CDC (Pact) with `can-i-deploy`. *Mobile/partner REST*: URI-path or date-based with explicit deprecation/sunset headers and 6–12-month runways (can't force upgrades); tolerant readers mandated in the SDK. *Kafka events*: Avro/Protobuf + Schema Registry in `FULL_TRANSITIVE`, `0`/`UNSPECIFIED` enum defaults, `reserved` discipline. Cross-cutting: per-version telemetry to prove zero usage before removal; expand–contract for all renames/type changes; never change semantics in place.
- **Follow-ups:** (a) *Why FULL_TRANSITIVE for events but maybe BACKWARD elsewhere?* Old event data persists and consumers are uncontrolled. (b) *How do you retire mobile v1 safely?* Telemetry by app version + forced-upgrade prompts + long sunset + brownouts. (c) *What single guard gives the most leverage?* Automated compatibility gates in CI (registry/Pact/openapi-diff) — they convert prod outages into build failures.

**Q11 (senior-signal). Critique Postel's Law in the context of evolvable APIs.** *(tradeoff/justification)*
- **Model answer**: Tolerant reading (ignore unknowns) is what makes additive evolution work, so the "liberal in what you accept" half is essential for *unknown additions*. But being liberal about *malformed known data* hides producer bugs, lets bad data accumulate, and entrenches sloppy producers — the well-documented critique. The nuanced stance: tolerate unknown *additions*; be strict about the validity of *known* fields. Postel for evolution, strictness for correctness.
- **Follow-ups:** (a) *Concrete failure from over-liberality?* Accepting two date formats forever because someone sent the wrong one once. (b) *How does this map to Jackson settings?* `FAIL_ON_UNKNOWN_PROPERTIES=false` (tolerate additions) but keep field-level validation strict. (c) *Where does fuzzing fit?* Validate the strict half — malformed known fields should be rejected, and fuzzing finds where you accidentally accept them.

---

## 12. Glossary

- **Additive change**: adding new optional fields/endpoints/params without removing or altering existing ones; generally non-breaking.
- **Anti-corruption layer**: a translation boundary that maps between an external/old contract and your internal/new model, isolating change.
- **Avro**: Apache binary serialization format with JSON-defined schemas (`.avsc`); uses writer+reader schema resolution.
- **Backward compatibility**: new code can read data written by old code (new reader, old data).
- **Breaking change**: a change requiring at least one existing consumer to change to keep working.
- **Brownout**: deliberately failing a deprecated endpoint for short windows before its sunset to flush out remaining consumers.
- **`can-i-deploy`**: Pact Broker command that gates a deploy by checking all relevant consumer/provider contracts.
- **CDC (Consumer-Driven Contract testing)**: testing where consumers specify their expectations and providers verify them.
- **CDN (Content Delivery Network)**: distributed caching layer near users; keys on URL by default, hence `Vary` matters.
- **Compatibility mode**: a Schema Registry setting (BACKWARD/FORWARD/FULL/TRANSITIVE/NONE) governing allowed schema changes.
- **Content negotiation**: HTTP mechanism where the client states preferences (`Accept`) and the server selects a representation.
- **Contract**: the agreed structure + semantics of an API interaction.
- **Date-based versioning**: labeling versions by date (`2024-06-20`) and pinning consumers; provider upgrades requests/downgrades responses via transformers.
- **Deprecation**: a published intent to remove a feature later, with a migration path and (ideally) a sunset date.
- **`Deprecation` header**: HTTP response header (RFC 9745) signaling a resource is deprecated.
- **Expand–contract (parallel change)**: a multi-deploy pattern to make breaking changes safely (add new alongside old, migrate, then remove old).
- **Field number (Protobuf)**: the integer tag identifying a field on the wire; the real contract (names aren't serialized).
- **Forward compatibility**: old code can read data written by new code (old reader, new data).
- **Full compatibility**: both backward and forward.
- **gRPC**: RPC framework using Protobuf over HTTP/2.
- **GraphQL**: query language where clients select exact fields; evolves via field deprecation rather than versioning.
- **IEEE-754 double**: the 64-bit floating-point format JS uses for all numbers; exact integers only up to 2^53.
- **Idempotency**: property that repeating an operation has the same effect as doing it once.
- **Jackson**: the standard JVM JSON library; `ObjectMapper` is its core engine.
- **Kafka**: distributed append-only log/message broker; producers write, consumers read later.
- **Mass assignment**: a vulnerability where a request binds unexpected fields into privileged objects.
- **MIME / media type**: a content-type label like `application/json`; the `vnd.` tree is for vendor-specific types.
- **MVCC** (mentioned in prompt as an example term): Multi-Version Concurrency Control — a database technique keeping multiple data versions so readers don't block writers. *(Adjacent; not central here.)*
- **Non-breaking change**: existing consumers keep working with no change.
- **OpenAPI**: a specification format for describing REST APIs; `openapi-diff` detects breaking changes between specs.
- **Pact**: the dominant CDC framework; a *pact* is the consumer-generated contract file.
- **Pact Broker**: server storing pacts and verification results; powers `can-i-deploy`.
- **PII (Personally Identifiable Information)**: data identifying a person; relevant to additive-field security review.
- **Postel's Law / robustness principle**: "be conservative in what you send, liberal in what you accept."
- **Promotion (Avro)**: allowed numeric type widening during resolution (`int→long→float→double`, `string↔bytes`).
- **Protobuf (Protocol Buffers)**: Google's compact binary serialization format defined by `.proto` files.
- **Provider / Consumer**: the side defining/serving an API vs the side using it.
- **Raft** (prompt example term): a consensus algorithm for replicated state machines. *(Adjacent; not central here.)*
- **`required` (proto2)**: a field that must be present; removed in proto3 because it's an evolution trap.
- **`reserved` (Protobuf)**: declaration preventing reuse of removed field numbers/names.
- **REST**: architectural style for HTTP APIs centered on resources and representations.
- **RFC / IETF / draft**: numbered Internet standards documents / their standards body / not-yet-final versions.
- **Rolling deploy**: replacing instances gradually so old and new run concurrently.
- **Schema**: declared structure of data (fields, types, optionality, defaults).
- **Schema registry**: a service storing and compatibility-checking schemas (e.g., Confluent).
- **Schema resolution (Avro)**: reconciling writer and reader schemas at decode time.
- **SemVer (Semantic Versioning)**: `MAJOR.MINOR.PATCH` convention for artifacts.
- **Subject (registry)**: the named, versioned schema history (default `<topic>-value`).
- **Sunset / `Sunset` header**: the date a resource stops working; RFC 8594 response header.
- **Tolerant reader**: a consumer that ignores unknown fields and assumes minimally.
- **Transformer chain (date-based)**: ordered, reversible converters upgrading requests / downgrading responses across versions.
- **Transitive (compatibility)**: checking a new schema against *all* historical versions, not just the latest.
- **`Vary` header**: tells caches which request headers affect the response, so they don't cross-serve.
- **Varint / wire type / zig-zag (Protobuf)**: variable-length integer encoding / the 3-bit field encoding category / signed-int encoding keeping small negatives short.
- **Wire format**: the actual encoded bytes on the network.
- **ZooKeeper** (prompt example term): a coordination service for distributed systems (config, leader election); older Kafka used it for metadata. *(Adjacent; not central here.)*

---

## 13. Cheat-sheet & self-test

### 13.1 One-screen recap

**Definitions**
- Backward = new reads old. Forward = old reads new. Full = both. Need *full* during rolling deploys and on shared Kafka topics.

**Default discipline**
- Prefer compatible evolution over versioning. Add optional, never rename/remove/require/narrow in place, never change meaning. Make readers tolerant. Use expand–contract for "breaking" changes.

**REST versioning**
- URI path (visible, cache-trivial) / header (`Vary`) / media-type (`Vary: Accept`, most RESTful) / query param (CDN-dependent) / date-based (pin + transformers). Internal: URI path, aim never to need v2.

**Jackson must-knows**
- `FAIL_ON_UNKNOWN_PROPERTIES` default **true** → flip to **false** for forward-compat. `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` + `@JsonEnumDefaultValue` for open enums. Spring: `spring.jackson.deserialization.fail-on-unknown-properties=false`.

**Protobuf**
- Field number is the contract; names aren't on the wire. Add new number = safe. Remove → `reserved`. Never reuse a number. int32↔int64 safe (varint); int32↔sint32/string unsafe. Numbers 1–15 = 1-byte tag; 19000–19999 forbidden; max 2^29−1. proto3 has no `required`. Reserve `0`/`UNSPECIFIED` enum member.

**Avro**
- Resolution uses writer+reader schemas; match by name (+`aliases`). Add/remove fields **with defaults**. Union default must match the **first** type. Promotions: int→long→float→double, string↔bytes. Kafka embeds a 4-byte schema ID.

**Schema Registry modes**
- Default **BACKWARD** (consumers upgrade first). FORWARD (producers first). FULL (either). *TRANSITIVE* = check all history. Use **FULL_TRANSITIVE** for shared long-lived event topics. Never **NONE** on shared topics.

**Deprecation signaling**
- `Deprecation` (RFC 9745), `Sunset: <HTTP-date>` (RFC 8594), `Link; rel="sunset"`. Public sunset 6–12 mo; internal 30–90 days. Track per-consumer usage before removal.

**Enforcement gates (turn outages into build failures)**
- Messaging → schema registry. REST → Pact CDC + `can-i-deploy`. Spec'd REST → `openapi-diff --fail-on-incompatible`.

**Worst trap**
- Semantic change under a stable schema (e.g., dollars→cents) — no structural tool catches it. Add a new field instead.

### 13.2 Self-test (no answers)

1. A producer adds a new Avro field with no default while the registry is in `BACKWARD` mode and a year of old data sits in the topic. What happens at registration time, and what would happen at consume time if it *had* been allowed through?
2. You must rename a JSON response field that 30 internal services and 2 mobile app versions consume. Lay out every step from now to safe removal, including what telemetry you need and where you'd put deprecation signals.
3. Why is `int32 → sint32` unsafe in Protobuf even though both are varint wire type 0, and how would you migrate a field's encoding from one to the other without corruption?
4. Design the caching strategy for a header-versioned (`X-API-Version`) public API behind a CDN. What header(s) must you set, what's the hit-ratio consequence, and when would you switch to URI-path versioning instead?
5. Explain how Stripe-style date-based versioning keeps the core code "speaking only the latest version," what artifact you must write for each breaking change, and the permanent cost this imposes.
6. Give one change that is backward-compatible but not forward-compatible, one that is forward but not backward, and one that is neither — for a REST/JSON response.
7. Your downstream service reports correct-looking but financially wrong totals after a provider deploy that changed no field names or types. Name the failure class, why schema tools missed it, and three controls that would have caught it.

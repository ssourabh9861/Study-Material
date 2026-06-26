# GraphQL — A Definitive Engineering Handbook Chapter

> **Reader profile:** A senior backend developer in the Java/JVM ecosystem who wants to *fully master* GraphQL — design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What GraphQL is

**GraphQL** is a *query language for APIs* and a *server-side runtime* for executing those queries against your data. It was created at Facebook in 2012, used internally to power their mobile apps, and open-sourced in 2015. It is now governed by the **GraphQL Foundation** (under the Linux Foundation). The authoritative reference is the **GraphQL Specification** (the current stable version is the "October 2021" spec edition; the spec is versioned by date, not by semantic version).

Two things to internalize up front:

1. **GraphQL is a specification, not a product.** There is no "the GraphQL server." There are many implementations: `graphql-java` (the canonical JVM library), Netflix DGS, Spring for GraphQL, Apollo Server (Node), `gqlgen` (Go), Hot Chocolate (.NET), Juniper (Rust), Strawberry/Graphene (Python), and many more. They all implement the same query language and execution semantics.

2. **GraphQL is transport-agnostic, but in practice runs over HTTP.** The spec does not mandate HTTP. Almost every deployment uses HTTP `POST` to a single endpoint (conventionally `/graphql`), with the query in a JSON body. This single-endpoint model is one of GraphQL's defining operational characteristics and the source of many of its tradeoffs (see caching, §1.4 and §6).

> **Adjacent term — "query language":** A query language is a formal syntax for *describing what data you want* (and sometimes how to change it), as opposed to writing imperative code to fetch it. SQL is a query language for relational databases. GraphQL is a query language for APIs — but unlike SQL it does not directly touch a database; it describes data shaped by a *schema* that you, the API author, define.

### 1.2 The problem GraphQL solves

GraphQL was born from the pain of building data-fetching for mobile clients against REST APIs. The two headline problems:

**Over-fetching.** With REST, an endpoint returns a fixed shape. `GET /users/42` might return 30 fields when the mobile screen needs only `name` and `avatarUrl`. The client downloads (and the server serializes) bytes nobody uses. On a slow mobile network this is real latency and battery cost.

**Under-fetching and the N+1 round-trip problem.** A screen often needs *related* data. To render a user's profile with their last 3 posts and each post's comment count, a REST client might call:

- `GET /users/42` → then
- `GET /users/42/posts?limit=3` → then, for each post,
- `GET /posts/{id}/comments/count` (×3)

That is 5 sequential HTTP round-trips, each with its own latency. Mobile latency dominates; each round-trip might cost 100–300 ms. GraphQL lets the client express the *entire* graph of needed data in **one request**:

```graphql
query ProfileScreen {
  user(id: "42") {
    name
    avatarUrl
    posts(last: 3) {
      title
      commentCount
    }
  }
}
```

One request, one response, shaped exactly to the screen. The client gets exactly what it asked for — no more, no less. This is the core value proposition: **client-driven, declarative data fetching**.

> **Adjacent term — "round-trip":** A round-trip is one full request-and-response cycle over the network. Each round-trip pays the network round-trip time (RTT) at minimum. Reducing the number of round-trips is one of the biggest levers for perceived performance on high-latency links.

### 1.3 When you reach for GraphQL

Use GraphQL when **diverse clients with diverging data needs** consume the same backend: a web app, an iOS app, an Android app, and partner integrations, each wanting slightly different fields and shapes. GraphQL lets you ship one schema and let each client self-serve its exact projection without you minting a new REST endpoint per screen (the "endpoint sprawl" / "backend-for-frontend explosion" problem).

It also shines when your domain is genuinely a **graph** — entities richly connected by relationships (users → posts → comments → authors → followers) — and clients traverse those relationships in unpredictable ways.

Avoid GraphQL (or use it sparingly) for: simple CRUD with one client, file upload/download, true public caching at the CDN edge, high-throughput machine-to-machine RPC where the schema never varies, and anything where the operational complexity isn't paid back. (Full decision framework in §8.)

### 1.4 The one-paragraph mental model

> GraphQL exposes a single typed **schema** that defines a graph of object types and their fields. A client sends a **query** (or **mutation**, or **subscription**) — a tree-shaped selection of fields it wants. The server **executes** that query by walking the selection tree and, for each field, invoking a **resolver** function that knows how to fetch that field's value from whatever backend (a database, another service, a cache). The execution engine assembles the resolved values into a response whose shape mirrors the query exactly. The schema is the contract; resolvers are the implementation; the query is the request; the response shape equals the request shape. The hard parts in production are not the query language — they are *efficient fetching* (the N+1 problem, solved by batching/`DataLoader`), *caching* (you lose REST's free HTTP caching), and *protecting* the single endpoint from expensive/abusive queries (depth and complexity limiting).

---

## 2. Foundations from first principles

This section builds GraphQL from zero. Every core term is defined as it appears.

### 2.1 The schema and the type system

The **schema** is the heart of a GraphQL API: a typed description of every piece of data a client can ask for and every operation it can perform. It is written in **SDL** (Schema Definition Language), GraphQL's human-readable type syntax.

> **Adjacent term — "SDL (Schema Definition Language)":** SDL is the language for *describing* a GraphQL schema — types, fields, and their relationships. It is distinct from the *query language* used by clients. SDL is what you, the API author, write; the query is what the client writes.

A minimal schema:

```graphql
# A scalar leaf type and an object type
type User {
  id: ID!            # ID is a built-in scalar; the ! means "non-null"
  name: String!
  email: String      # nullable: may be absent
  posts: [Post!]!    # a list of Posts; the list is non-null and each element is non-null
}

type Post {
  id: ID!
  title: String!
  body: String!
  author: User!      # relationship back to User — this is the "graph" in GraphQL
}

# Every schema has a root Query type — the entry points for reads
type Query {
  user(id: ID!): User       # a field that takes an argument
  posts(first: Int): [Post!]!
}
```

#### 2.1.1 Scalars — the leaf values

**Scalar types** are the leaves of every query: they hold concrete primitive values and cannot be selected into further. The five built-in scalars:

| Scalar | Meaning | Notes |
|---|---|---|
| `Int` | 32-bit signed integer | Spec mandates 32-bit. For 64-bit IDs/counts use `String` or a custom `Long`/`BigInteger` scalar. |
| `Float` | Double-precision (IEEE 754) | |
| `String` | UTF-8 text | |
| `Boolean` | `true` / `false` | |
| `ID` | An opaque unique identifier | Serialized as a string; semantically "do not do math on this." |

> **Critical gotcha:** `Int` is **32-bit** per the spec. A database `BIGINT` (e.g. a Snowflake ID or epoch-millis timestamp) **overflows** `Int`. Use a **custom scalar** (e.g. `Long`, `BigDecimal`, `DateTime`, `JSON`) — these are first-class and common. `graphql-java` ships `ExtendedScalars` (e.g. `GraphQLLong`, `DateTime`, `Json`, `BigDecimal`) in the `graphql-java-extended-scalars` library.

#### 2.1.2 Object types and fields

An **object type** (`type User { ... }`) is a named collection of **fields**. Each field has a name and a type, and may take **arguments**. Object types are the non-leaf nodes of the graph; a client selecting an object field must in turn select sub-fields of it (you can't select `posts` without selecting fields inside `Post`).

#### 2.1.3 The non-null and list modifiers

Two type *modifiers* wrap any type:

- `!` — **Non-Null.** `String!` means the field will never be null. If a resolver returns null for a non-null field, GraphQL raises an execution error and **propagates the null upward** to the nearest nullable parent (a subtle and important behavior — see §7.4).
- `[ ]` — **List.** `[Post]` is a nullable list of nullable `Post`s. `[Post!]!` is a non-null list of non-null `Post`s. The brackets and bangs compose: `[[Int!]]!` is a non-null list of nullable lists of non-null ints.

Reading nullability correctly is a core skill. `[Post!]!`:
- outer `!` → the list itself is never null (could be empty `[]`)
- inner `!` → no element is ever null

#### 2.1.4 The three root operation types

A schema has up to three special **root types** that are the entry points for the three **operation types**:

| Operation | Root type | Purpose | Semantics |
|---|---|---|---|
| **Query** | `type Query` | Read data | Fields execute **in parallel** (no ordering guaranteed). Should be side-effect-free. |
| **Mutation** | `type Mutation` | Write data | Top-level fields execute **serially, in listed order** (so a sequence of writes is deterministic). |
| **Subscription** | `type Subscription` | Long-lived stream of events | Each subscription is a long-lived event source pushing data to the client over time (typically WebSocket). |

> **Why serial mutations matter:** If a client sends a mutation with `{ deleteAll, insert }`, GraphQL guarantees `deleteAll` completes before `insert` begins. Query fields have no such guarantee and may run concurrently — this is why queries must be free of observable side effects.

#### 2.1.5 The other type kinds

GraphQL's type system has six kinds total. Beyond **Scalar** and **Object**:

- **Enum** — a fixed set of named values: `enum Role { ADMIN EDITOR VIEWER }`. Serialized as strings on the wire but validated against the allowed set.
- **Interface** — an abstract type listing fields that implementing object types must provide. `interface Node { id: ID! }`. Used for polymorphism: a field can return `Node` and the actual object is one of several implementers.
- **Union** — `union SearchResult = User | Post | Comment`. A value is *one of* the member types, but unlike an interface, members need share no fields. Clients use **inline fragments** (`... on User { name }`) to select per-type.
- **Input Object** — a special object type used **only as an argument** to fields/mutations: `input CreateUserInput { name: String! email: String }`. Input types cannot have resolvers or circular non-nullable references and may not include interface/union/output-object fields.

> **Adjacent term — "polymorphism" (here):** Polymorphism means a single field can return values of different concrete types at runtime. Interfaces and unions are GraphQL's two polymorphism mechanisms. The execution engine needs a **type resolver** to decide, for each returned object, which concrete type it is.

### 2.2 The query language (operations)

A client sends a **document** containing one or more **operations**. Anatomy of a query:

```graphql
query GetUser($id: ID!) {        # operation type, name, variable declaration
  user(id: $id) {                 # root field with an argument bound to a variable
    id
    name
    posts(first: 2) {             # nested field with its own argument
      title
      author {                    # traversing the graph
        name
      }
    }
  }
}
```

Key concepts:

- **Operation name** (`GetUser`): optional but strongly recommended in production — it shows up in logs, traces, and metrics, and is required for some persisted-query schemes.
- **Variables** (`$id: ID!`): typed parameters supplied separately from the query text (in the request's `variables` JSON object). Variables let you reuse and safely parameterize a query without string concatenation — the GraphQL analog of SQL prepared-statement parameters, and a key defense against injection-style problems.
- **Arguments**: any field may declare arguments (`user(id:)`, `posts(first:)`). Arguments are typed by the schema.
- **Aliases**: rename a field in the response, or request the same field twice with different args:
  ```graphql
  { firstPost: post(id:"1"){title}  secondPost: post(id:"2"){title} }
  ```
- **Fragments**: reusable selection sets. `fragment UserFields on User { id name }` then `...UserFields`. **Inline fragments** (`... on Post { title }`) select fields conditional on the runtime type (used with interfaces/unions).
- **Directives**: annotations that alter execution. Built-in: `@skip(if: $bool)` and `@include(if: $bool)` to conditionally include fields; `@deprecated(reason: "...")` on schema fields. Custom directives are supported (e.g. `@auth`, `@cacheControl`).

### 2.3 Resolvers — the implementation

A **resolver** is a function the engine calls to produce the value of a single field. Conceptually:

```
resolver(source, args, context, info) -> value (or a CompletableFuture/Publisher of value)
```

- **source** (a.k.a. *parent* / *root*): the value returned by the *parent* field's resolver. For `user.posts`, the source is the `User` object that `user` resolved to.
- **args**: the field's arguments (e.g. `{first: 2}`).
- **context**: a per-request object you populate (auth principal, request-scoped caches, `DataLoader` registry, tracing span). Shared across all resolvers in a request.
- **info**: execution metadata — the field name, the parent type, the *selection set* of sub-fields requested (useful for query optimization / projection), the path, and variable values.

Two crucial facts:

1. **Every field has a resolver**, even if you don't write one. Engines provide a **default resolver** (often called a "property data fetcher" in `graphql-java`) that, for a field `name` on a source object, returns `source.getName()` (a getter) or `source.name` (a map key / public field). You only write explicit resolvers for fields that need custom fetching.

2. **Resolution is recursive and depth-first per branch but breadth-parallel.** The engine resolves the root field, then for each sub-field invokes its resolver with the parent's result as source, and so on down the tree. Sibling fields can be resolved in parallel.

> **Adjacent term — "DataFetcher":** In `graphql-java` (the JVM standard), a resolver is called a **`DataFetcher`** — a functional interface `Object get(DataFetchingEnvironment env)`. The `DataFetchingEnvironment` carries source, args, context, and the selection info. Everywhere this doc says "resolver," in `graphql-java` read "`DataFetcher`."

### 2.4 The request lifecycle (10,000-ft view, detailed in §3)

1. Client `POST`s `{ query, variables, operationName }` to `/graphql`.
2. Server **parses** the query string into an AST.
3. Server **validates** the AST against the schema (types, fields, arg types, fragment usage).
4. Server **executes** the operation by walking the AST and invoking resolvers.
5. Server **assembles** `{ data, errors, extensions }` and returns it (HTTP 200 even for partial errors — see §3.6).

---

## 3. How it works internally

This is the heart of the document. We trace the full lifecycle in detail, using `graphql-java` semantics (the JVM reference) but the phases are universal to the spec.

### 3.1 The five-phase execution pipeline

```
   HTTP layer (out of GraphQL scope)
        │  raw JSON body: { query, variables, operationName }
        ▼
 ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
 │ 1. PARSE     │──▶│ 2. VALIDATE  │──▶│ 3. (optional)│
 │ string → AST │   │ AST × schema │   │ instrument-  │
 └──────────────┘   └──────────────┘   │    ation     │
                                        └──────┬───────┘
                                               ▼
                              ┌──────────────────────────────┐
                              │ 4. EXECUTE                    │
                              │  - select operation           │
                              │  - coerce variables           │
                              │  - resolve root fields        │
                              │  - recursively resolve subtree│
                              │  - run DataLoader dispatch     │
                              └──────────────┬─────────────────┘
                                             ▼
                              ┌──────────────────────────────┐
                              │ 5. COMPLETE / SERIALIZE        │
                              │  - coerce scalar/enum values   │
                              │  - apply non-null propagation  │
                              │  - collect errors              │
                              │  - build { data, errors, ... } │
                              └────────────────────────────────┘
```

### 3.2 Phase 1 — Parse

The query string is lexed and parsed into an **AST (Abstract Syntax Tree)** — a tree of nodes (`OperationDefinition`, `Field`, `Argument`, `FragmentDefinition`, etc.).

> **Adjacent term — "AST (Abstract Syntax Tree)":** An AST is an in-memory tree representation of source code (here, a GraphQL query). Each node is a syntactic construct. Parsing converts flat text into this tree so the engine can analyze and walk it. If the text is malformed, parsing fails with a syntax error before any validation or execution.

In `graphql-java` the parser is built on an ANTLR grammar (ANTLR is a parser-generator). Parse failures return an error and no `data`. **Parsing can itself be a DoS vector** — a deeply nested or enormous query string can blow the stack or memory. Production servers cap query size, parse depth, and token count (see §6 security).

### 3.3 Phase 2 — Validate

The AST is checked against the schema using the spec's validation rules. Among the dozens of rules:

- Every selected field exists on its parent type.
- Argument names and types are valid; required (non-null) args are present.
- Variables are declared, used, and type-compatible with where they're used.
- Fragments reference real types and don't form cycles; all defined fragments are used; no fragment spreads on incompatible types.
- Selection sets on object types are non-empty; scalar fields have no sub-selections.
- A subscription operation has exactly one root field.

Validation is **static** (no resolvers run). A failed validation returns `errors` and no `data`. Because validation walks the whole query, **validation results are cacheable per query document** — many servers cache "this exact query string is valid" to skip re-validation (see persisted queries, §6.2).

### 3.4 Phase 3 — Instrumentation hooks (implementation-specific)

`graphql-java` exposes **`Instrumentation`** — callbacks around each phase (begin/end parse, validate, execute, and *each field fetch*). This is how tracing (OpenTelemetry), metrics (Micrometer), query-complexity analysis, and field-level timing (Apollo Tracing format) are implemented. Conceptually equivalent to "plugins" in Apollo Server or "interceptors" elsewhere.

### 3.5 Phase 4 — Execute (the core)

#### 3.5.1 Operation selection and variable coercion

If the document has multiple operations, `operationName` selects which one. The engine then **coerces** the incoming JSON `variables` against the operation's declared variable types — converting/validating each (e.g. ensuring `$id` is a valid `ID`, applying input-object defaults, rejecting wrong types).

#### 3.5.2 Field collection

For the selected operation's root selection set, the engine performs **field collection**: it flattens fragments (`...UserFields`, inline fragments) and applies `@skip`/`@include` directives to produce the concrete set of fields to resolve, grouped by response key (alias or name). Duplicate selections of the same field are merged.

#### 3.5.3 The recursive `ExecuteSelectionSet` algorithm

For each field in the collected set, the engine runs **`ExecuteField`**:

1. Determine the field's resolver (`DataFetcher`) from the schema's runtime wiring.
2. Build the `DataFetchingEnvironment` (source = parent value, coerced args, shared context, info).
3. **Invoke the resolver.** It may return a plain value, a `CompletableFuture<T>` (async), or — for lists — a collection.
4. **Complete the value** (`CompleteValue`):
   - If the field type is a **scalar/enum**, run the type's **output coercion** (serialize the Java value to the wire form — e.g. a `java.time.Instant` → ISO-8601 string for a `DateTime` scalar).
   - If it's an **object type**, recursively `ExecuteSelectionSet` on the sub-fields with this value as the new source.
   - If it's a **list**, iterate and complete each element (in parallel for async resolvers).
   - If it's an **interface/union**, run the **type resolver** to find the concrete object type, then proceed as object.
5. Apply **non-null propagation** if a non-null field resolves null (see §3.7).

Sibling fields are resolved concurrently when resolvers are async; the engine awaits all the `CompletableFuture`s before completing the parent.

#### 3.5.4 Query vs Mutation execution order

- **Query** root fields: resolved with no ordering guarantee (engine may parallelize).
- **Mutation** root fields: resolved **strictly serially in document order**, each fully completing (including its sub-tree) before the next begins. This is the spec's only sequencing guarantee and the reason mutations are the safe place for writes.

### 3.6 The N+1 problem and DataLoader (the single most important internal mechanism)

#### 3.6.1 The problem, concretely

Consider:

```graphql
{ posts(first: 10) { title author { name } } }
```

Naively:
- `posts` resolver → 1 DB query returning 10 posts. (the "1")
- For each of the 10 posts, the `author` resolver runs independently → 10 separate `SELECT * FROM users WHERE id = ?` queries. (the "N")

Total: **N+1 = 11 queries**. With nested lists (each post's comments, each comment's author) this explodes multiplicatively. This is the **N+1 problem**, GraphQL's defining performance pitfall. It arises because each field resolves in isolation, blind to its siblings.

> **Adjacent term — "N+1 query problem":** A pattern where fetching a list of N parent rows triggers one additional query per row to fetch a related child — N+1 queries instead of 2. Common in ORMs (lazy loading) and especially in GraphQL because resolvers run per-field-per-object.

#### 3.6.2 The solution: batching + per-request caching (DataLoader)

**`DataLoader`** is a utility (originally from Facebook's `dataloader` JS lib; ported to the JVM as `java-dataloader`, bundled with `graphql-java`) that solves N+1 with two mechanisms:

1. **Batching.** Instead of each `author` resolver fetching one user, it calls `userLoader.load(authorId)`, which returns a `CompletableFuture` and *queues* the key. The engine resolves the whole tier of `author` fields, accumulating keys `[id1, id2, … id10]`. Then, at a **dispatch** point, the loader invokes a single **batch loader function** `loadUsers(List<Long> ids) -> List<User>` — **one** query (`SELECT * FROM users WHERE id IN (?, …)`). The 10 queued futures are completed from that one result. N+1 → 2.

2. **Per-request caching (memoization).** Within a single request, `load(id)` for the same `id` returns the same cached `CompletableFuture` — fetched once even if 50 fields ask for the same user.

> **Why "per-request" matters:** The `DataLoader` cache is **request-scoped**, created fresh per request and discarded after. It is *not* a shared application cache — using one would leak data across users and serve stale data. Cross-request caching is a separate concern (§6.4).

#### 3.6.3 How dispatch is triggered (the subtle internal part)

The magic is **when** the batch fires. The keys must be collected from *all* sibling `author` resolvers before the batch query runs. `graphql-java` integrates `DataLoader` with its execution engine via the **`DataLoaderDispatcherInstrumentation`** (in modern versions, automatically active when a `DataLoaderRegistry` is in the context). The engine tracks the "level" of fields it's executing; when it has dispatched all resolvers at a level and they're all parked on `load()` calls, it triggers `dataLoader.dispatch()`, which runs the batch functions. This level-by-level dispatch is what makes batching work without you manually flushing.

```
Level 1: posts resolver        → returns 10 posts (1 query)
Level 2: 10 author resolvers   → each calls userLoader.load(authorId)
                                   (queues keys, returns futures, does NOT block)
   ──── engine detects level complete, dispatches userLoader ────
                                 → loadUsers([1,2,...]) runs ONCE (1 query)
                                 → 10 futures complete
Level 3+: continue
```

#### 3.6.4 Minimal `java-dataloader` example

```java
// The batch loader: given many keys, return values in the SAME ORDER as the keys.
BatchLoader<Long, User> userBatchLoader = userIds ->
    CompletableFuture.supplyAsync(() -> {
        Map<Long, User> byId = userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, u -> u));
        // MUST return a list aligned 1:1 with userIds (null-fill misses).
        return userIds.stream().map(byId::get).collect(Collectors.toList());
    });

// Per request: build a fresh registry and put it in the GraphQL context.
DataLoader<Long, User> userLoader = DataLoaderFactory.newDataLoader(userBatchLoader);
DataLoaderRegistry registry = new DataLoaderRegistry();
registry.register("users", userLoader);

// In the author resolver:
DataFetcher<CompletableFuture<User>> authorFetcher = env -> {
    Post post = env.getSource();
    DataLoader<Long, User> loader = env.getDataLoader("users"); // from registry in context
    return loader.load(post.getAuthorId());                     // queues + returns future
};
```

**Contract you must honor:** the batch function must return results **in the same order and cardinality** as the input keys (use null/`Optional.empty()` for missing keys). Getting this wrong silently maps the wrong user to the wrong post — a nasty data-integrity bug.

### 3.7 Error handling and null propagation (subtle but spec-defined)

The response envelope is `{ data, errors, extensions }`. Key rules:

- A request that *parses and validates* and at least partially executes returns **HTTP 200** with `data` and possibly `errors`. (Whether to use other status codes is a long-running debate; the new "GraphQL over HTTP" spec adds nuance, and `application/graphql-response+json` content type may use 4xx/5xx — see §7.6.)
- A resolver that throws or returns null for a non-null field generates an entry in `errors` (with `message`, `locations`, `path`, and optional `extensions`).
- **Null bubbling / propagation:** If a field declared `String!` (non-null) resolves to null, the engine cannot return null there, so it **nullifies the nearest nullable ancestor**, propagating upward until it finds a field that *can* be null (or reaches `data`, which becomes null entirely). This means one deep non-null failure can blank out a large subtree. Design nullability deliberately: over-using `!` makes responses brittle; under-using it weakens the contract.

### 3.8 Subscriptions internals

A **subscription** sets up a long-lived stream. Internally:

1. Client opens a persistent connection (almost always **WebSocket**, using a subprotocol — historically `subscriptions-transport-ws`, now the maintained `graphql-ws` protocol; an HTTP-based alternative is SSE via the `graphql-sse` protocol).
2. Client sends a subscription operation. The server resolves the subscription's single root field to an **event stream** — in `graphql-java`, a Reactive Streams **`Publisher`** (often a Project Reactor `Flux`).
3. For each event the source emits, the server runs the *rest* of the selection set (the sub-fields) against that event payload — like a mini-query per event — and pushes the result down the socket.
4. The stream stays open until the client unsubscribes or the connection drops.

> **Adjacent term — "Reactive Streams / Publisher":** A standard JVM API (`org.reactivestreams.Publisher`) for asynchronous streams of data with backpressure (the consumer can signal how much it can handle). Project Reactor's `Flux` and `Mono` are the common implementations used in Spring/`graphql-java` subscription code.

### 3.9 Introspection (the schema is queryable)

GraphQL servers expose a **meta-schema**: you can query `__schema`, `__type`, and field `__typename` to discover the full schema at runtime. This powers tooling (GraphiQL, Apollo Studio, code generators, client IDE autocomplete). It is also a security consideration — leaving introspection on in production hands attackers a complete map of your API (§6.3).

> **Adjacent term — "introspection":** The built-in ability to query a GraphQL server *about its own schema* — its types, fields, arguments, descriptions, and deprecations — using special `__`-prefixed meta-fields defined by the spec.

---

## 4. The complete toolkit

This section enumerates the practical surface area on the JVM: libraries, classes, methods, and config. Where something is version- or vendor-specific, it's flagged.

### 4.1 JVM library landscape

| Library | Role | When to use |
|---|---|---|
| **`graphql-java`** | The core engine: parse, validate, execute. Everything else builds on it. | Always present (transitively). Direct use for low-level control. |
| **`graphql-java-extended-scalars`** | Custom scalars: `DateTime`, `Long`, `BigDecimal`, `Json`, `PositiveInt`, etc. | Whenever you need non-default scalars (almost always). |
| **`graphql-java-extended-validation`** | Bean-Validation-style directives (`@Size`, `@Range`, `@NotBlank`) on schema. | Declarative input validation. |
| **`java-dataloader`** | Batching/caching to kill N+1. Bundled with `graphql-java`. | Always, for any non-trivial graph. |
| **Spring for GraphQL** (`spring-boot-starter-graphql`) | Spring's official integration: annotated controllers, transport (HTTP/WS/RSocket/SSE), testing. Built on `graphql-java`. | Default for Spring Boot apps. |
| **Netflix DGS** (`graphql-dgs`) | Netflix's annotation-driven framework on `graphql-java`. Now aligned/co-developed with Spring for GraphQL. | Large Netflix-style estates; rich tooling/codegen. |
| **`graphql-java-tools` / SPQR** | Schema-first (tools) or code-first (SPQR generates schema from Java types). | Alternative wiring styles. |
| **Apollo Federation JVM** (`federation-graphql-java-support`) | Adds federation directives/`_entities` resolution to `graphql-java`. | Building a federated subgraph on the JVM. |

> **Version note:** Spring for GraphQL and Netflix DGS have converged — DGS 8+ runs on Spring for GraphQL's engine. New Spring projects typically start with Spring for GraphQL directly.

### 4.2 Schema-first vs code-first

| Approach | What you author | Pros | Cons |
|---|---|---|---|
| **Schema-first** | `.graphqls` SDL files + wire resolvers to fields | Schema is the source of truth; readable; design-by-contract; easy for frontend collaboration | Risk of drift between SDL and Java types; manual wiring |
| **Code-first** | Java types/annotations; SDL generated | Single source of truth (Java); refactoring-safe; no drift | SDL is a build artifact; less obvious to non-Java consumers |

Spring for GraphQL and DGS support both; schema-first is the more common default in the SDL-centric GraphQL community.

### 4.3 Core `graphql-java` classes and methods

| Class / Interface | Purpose | Key methods / notes |
|---|---|---|
| `GraphQL` | The executable engine instance. | `GraphQL.newGraphQL(schema).build()`; `graphQL.executeAsync(executionInput)` → `CompletableFuture<ExecutionResult>` |
| `GraphQLSchema` | The compiled schema (types + wiring). | Built from `TypeDefinitionRegistry` + `RuntimeWiring`. |
| `SchemaParser` | Parses SDL text → `TypeDefinitionRegistry`. | `new SchemaParser().parse(sdlReader)` |
| `RuntimeWiring` | Binds types/fields to `DataFetcher`s, scalars, type resolvers. | `.type("Query", b -> b.dataFetcher("user", userFetcher))` |
| `DataFetcher<T>` | A resolver: `T get(DataFetchingEnvironment env)`. | Functional interface. May return `T` or `CompletableFuture<T>`. |
| `DataFetchingEnvironment` | Resolver inputs. | `getSource()`, `getArgument(name)`, `getGraphQlContext()`, `getDataLoader(key)`, `getSelectionSet()`, `getExecutionStepInfo()` |
| `ExecutionInput` | One request. | `.query(...)`, `.variables(...)`, `.operationName(...)`, `.graphQLContext(...)`, `.dataLoaderRegistry(...)` |
| `ExecutionResult` | One response. | `.getData()`, `.getErrors()`, `.getExtensions()` |
| `Instrumentation` | Lifecycle hooks for tracing/metrics/limits. | `beginExecution`, `beginFieldFetch`, etc. |
| `DataLoaderRegistry` / `DataLoader` | Batching. | `register(name, loader)`; `loader.load(key)`; `dispatch()` |
| `GraphqlErrorBuilder` | Build spec-compliant errors. | `.message(...).path(...).extensions(...).build()` |
| `TypeResolver` | For interfaces/unions: pick concrete type. | `getType(TypeResolutionEnvironment)` |

### 4.4 Built-in directives and common custom ones

| Directive | Built-in? | Purpose |
|---|---|---|
| `@skip(if: Boolean!)` | Yes (query) | Omit a field/fragment when true. |
| `@include(if: Boolean!)` | Yes (query) | Include only when true. |
| `@deprecated(reason: String)` | Yes (schema) | Mark a field/enum value deprecated; shows in introspection. |
| `@specifiedBy(url: String!)` | Yes (schema) | Point a custom scalar to its spec. |
| `@oneOf` | Yes (recent spec) | Input object where exactly one field must be set (tagged-union input). |
| `@cacheControl(maxAge, scope)` | No (Apollo) | Per-field cache hints (Apollo Server / response cache). |
| `@key`, `@external`, `@requires`, `@provides`, `@shareable` | No (Federation) | Federation entity directives (§7.1). |

### 4.5 Pagination toolkit — Relay Cursor Connections

The de-facto standard for paginating GraphQL lists is the **Relay Cursor Connections Specification**. It defines a uniform shape:

```graphql
type PostConnection {
  edges: [PostEdge!]!
  pageInfo: PageInfo!
  totalCount: Int          # optional, often omitted for perf
}
type PostEdge {
  node: Post!
  cursor: String!          # opaque cursor for THIS edge
}
type PageInfo {
  hasNextPage: Boolean!
  hasPreviousPage: Boolean!
  startCursor: String
  endCursor: String
}
# Field uses standard args:
posts(first: Int, after: String, last: Int, before: String): PostConnection!
```

- **`first`/`after`** = forward pagination; **`last`/`before`** = backward.
- A **cursor** is an **opaque** string (usually base64) encoding the position — typically the sort key value(s) of that row, *not* an offset. Clients must treat it as opaque.
- **Cursor (keyset) pagination beats offset pagination** at scale: `WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?` is O(log n) via an index, whereas `OFFSET 100000` makes the DB scan and discard 100k rows. (See §7.3.)

> **Adjacent term — "Relay":** Relay is Facebook's opinionated GraphQL client for React. Its conventions — the `Connection`/`Edge`/`PageInfo` pagination shape, the global `Node` interface with a globally-unique `id`, and the input-object mutation convention — became community standards even outside Relay itself.

### 4.6 HTTP request/response shape

A standard GraphQL-over-HTTP request:

```http
POST /graphql HTTP/1.1
Content-Type: application/json

{
  "query": "query GetUser($id: ID!){ user(id:$id){ name } }",
  "variables": { "id": "42" },
  "operationName": "GetUser"
}
```

Response:

```json
{ "data": { "user": { "name": "Ada" } }, "errors": null }
```

The **GraphQL-over-HTTP spec** (a separate, in-progress spec) standardizes: `GET` for queries (params `query`, `variables`, `operationName`), `POST` for everything, the `application/json` and newer `application/graphql-response+json` media types, and status-code semantics.

---

## 5. Code examples by use case

Idiomatic, copy-adaptable examples spanning different real scenarios. Java + Spring for GraphQL unless noted.

### 5.1 Use case A — Schema-first read API with Spring for GraphQL

**`src/main/resources/graphql/schema.graphqls`:**

```graphql
scalar DateTime

type Query {
  book(id: ID!): Book
  books(first: Int = 20, after: String): BookConnection!
}

type Book {
  id: ID!
  title: String!
  publishedAt: DateTime
  author: Author!          # resolved via DataLoader to avoid N+1
}

type Author {
  id: ID!
  name: String!
}

type BookConnection {
  edges: [BookEdge!]!
  pageInfo: PageInfo!
}
type BookEdge { node: Book!  cursor: String! }
type PageInfo { hasNextPage: Boolean!  endCursor: String }
```

**Controller (annotation-driven resolvers):**

```java
@Controller
public class BookController {

  private final BookService books;
  private final AuthorService authors;

  public BookController(BookService books, AuthorService authors) {
    this.books = books; this.authors = authors;
  }

  // Maps to Query.book
  @QueryMapping
  public Book book(@Argument String id) {
    return books.findById(Long.valueOf(id));
  }

  // Maps to Query.books — Relay-style forward pagination
  @QueryMapping
  public BookConnection books(@Argument int first, @Argument String after) {
    return books.page(first, after); // service builds edges + pageInfo with keyset cursor
  }

  // SCHEMA-MAPPED FIELD RESOLVER for Book.author.
  // @BatchMapping makes Spring batch all authors for the page in ONE call → no N+1.
  @BatchMapping(typeName = "Book", field = "author")
  public Map<Book, Author> author(List<Book> booksOnThisPage) {
    Set<Long> authorIds = booksOnThisPage.stream()
        .map(Book::getAuthorId).collect(Collectors.toSet());
    Map<Long, Author> byId = authors.findAllById(authorIds).stream()
        .collect(Collectors.toMap(Author::getId, a -> a));
    // Return a map from each source Book → its Author (Spring distributes results).
    return booksOnThisPage.stream()
        .collect(Collectors.toMap(b -> b, b -> byId.get(b.getAuthorId())));
  }
}
```

`@BatchMapping` is Spring for GraphQL's ergonomic wrapper over `DataLoader`: you receive *all* parent `Book`s being resolved at this tier and return a `Map<Book, Author>`. Spring handles registry, dispatch, and result distribution. This is the idiomatic N+1 fix in Spring.

**Custom scalar registration:**

```java
@Configuration
public class GraphQlConfig {
  @Bean
  public RuntimeWiringConfigurer scalars() {
    return wiring -> wiring.scalar(ExtendedScalars.DateTime); // ISO-8601 <-> OffsetDateTime
  }
}
```

### 5.2 Use case B — Mutations with input objects and a result-union for errors

```graphql
input CreateBookInput {
  title: String!
  authorId: ID!
  publishedAt: DateTime
}

type CreateBookSuccess { book: Book! }
type ValidationError    { field: String!  message: String! }

# Return a union so the client handles success vs. domain error explicitly.
union CreateBookResult = CreateBookSuccess | ValidationError

type Mutation {
  createBook(input: CreateBookInput!): CreateBookResult!
}
```

```java
@Controller
public class BookMutation {

  @MutationMapping
  public Object createBook(@Argument CreateBookInput input) { // returns the union value
    if (input.title().isBlank()) {
      return new ValidationError("title", "must not be blank");
    }
    Book saved = bookService.create(input); // @Transactional inside service
    return new CreateBookSuccess(saved);
  }

  // Union/interface type resolution: tell GraphQL which concrete type a value is.
  // Spring infers by class name matching the GraphQL type name; otherwise register a TypeResolver.
}
```

Returning a **result union** instead of throwing for *expected domain failures* is a strong pattern: validation/business errors become part of the typed schema (clients must handle them), while the top-level `errors` array is reserved for *unexpected* failures (bugs, infra). This separation makes clients more robust.

### 5.3 Use case C — Low-level `graphql-java` (no framework) with explicit DataLoader

```java
public GraphQL buildEngine() throws IOException {
  // 1. Parse SDL
  TypeDefinitionRegistry typeRegistry = new SchemaParser()
      .parse(new InputStreamReader(getClass().getResourceAsStream("/schema.graphqls")));

  // 2. Wire resolvers
  RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
      .scalar(ExtendedScalars.DateTime)
      .type("Query", b -> b
          .dataFetcher("book", env -> bookRepo.findById(Long.valueOf(env.getArgument("id")))))
      .type("Book", b -> b
          .dataFetcher("author", env -> {
              Book src = env.getSource();
              return env.<Long, Author>getDataLoader("authorLoader")
                        .load(src.getAuthorId());          // batched
          }))
      .build();

  GraphQLSchema schema = new SchemaGenerator()
      .makeExecutableSchema(typeRegistry, wiring);
  return GraphQL.newGraphQL(schema).build();
}

// Per-request execution with a FRESH DataLoaderRegistry:
public CompletableFuture<ExecutionResult> execute(String query, Map<String,Object> vars) {
  DataLoader<Long, Author> authorLoader = DataLoaderFactory.newDataLoader(
      (List<Long> ids) -> CompletableFuture.supplyAsync(() -> {
          Map<Long, Author> m = authorRepo.findAllById(ids).stream()
              .collect(Collectors.toMap(Author::getId, a -> a));
          return ids.stream().map(m::get).toList(); // order-aligned!
      }));
  DataLoaderRegistry registry = new DataLoaderRegistry();
  registry.register("authorLoader", authorLoader);

  ExecutionInput input = ExecutionInput.newExecutionInput()
      .query(query).variables(vars)
      .dataLoaderRegistry(registry)            // wires batching for THIS request
      .build();
  return graphQL.executeAsync(input);
}
```

### 5.4 Use case D — Subscription (real-time) with Spring + Reactor

```graphql
type Subscription {
  bookAdded(authorId: ID): Book!
}
```

```java
@Controller
public class BookSubscription {
  private final Sinks.Many<Book> sink = Sinks.many().multicast().onBackpressureBuffer();

  // Call this from your write path whenever a book is created:
  public void publish(Book b) { sink.tryEmitNext(b); }

  @SubscriptionMapping
  public Flux<Book> bookAdded(@Argument String authorId) {
    Flux<Book> stream = sink.asFlux();
    return (authorId == null) ? stream
        : stream.filter(b -> b.getAuthorId().equals(Long.valueOf(authorId)));
  }
}
```

The transport is WebSocket via the `graphql-ws` protocol; Spring auto-configures it when `spring.graphql.websocket.path` is set. Each emitted `Book` runs through the subscription's selection set and is pushed to subscribed clients.

### 5.5 Use case E — Depth + complexity limiting via Instrumentation (production hardening)

```java
@Bean
public Instrumentation queryGuards() {
  return new ChainedInstrumentation(List.of(
      // Reject queries nested deeper than 10 levels (cheap structural guard).
      new MaxQueryDepthInstrumentation(10),
      // Reject queries whose estimated complexity exceeds a budget.
      new MaxQueryComplexityInstrumentation(1000)
  ));
}
```

`MaxQueryDepthInstrumentation` and `MaxQueryComplexityInstrumentation` ship with `graphql-java`. Depth limiting blocks pathological nesting (`a{b{a{b…}}}`); complexity limiting assigns a cost per field (with multipliers for list `first` args) and rejects queries over budget *before* execution. Both are essential for a public endpoint (§6.3).

### 5.6 Use case F — Persisted query / APQ allow-listing (gateway-level)

```text
# Apollo Automatic Persisted Queries (APQ) handshake:
# 1. Client sends only a SHA-256 hash of the query (tiny request).
POST /graphql {"extensions":{"persistedQuery":{"version":1,"sha256Hash":"<hash>"}}}
# 2. Server miss → responds PERSISTED_QUERY_NOT_FOUND.
# 3. Client retries WITH the full query + hash; server stores it keyed by hash.
# 4. All future requests send only the hash → smaller payloads + cacheable by URL/CDN.
```

For security you can run **persisted queries in "safelist-only" mode**: only pre-registered (hash, query) pairs are accepted at runtime, eliminating arbitrary ad-hoc queries entirely (§6.3). On the JVM this is typically done at an edge/gateway (Apollo Router, or a custom filter that resolves hashes to queries before handing off to `graphql-java`).

### 5.7 Use case G — Querying from a Java client

```java
// Spring's HttpGraphQlClient (reactive) — typed, fluent client.
HttpGraphQlClient client = HttpGraphQlClient.builder(
        WebClient.create("https://api.example.com/graphql")).build();

Mono<Book> book = client.documentName("getBook")   // loads a .graphql file by name
    .variable("id", "42")
    .retrieve("book")                                // JSON path into data
    .toEntity(Book.class);
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Always batch related fetches** with `DataLoader`/`@BatchMapping`. The #1 GraphQL performance bug is an un-batched relationship resolver. Verify with query-count assertions in tests (§6.6).
- **Project only requested columns.** Use `DataFetchingEnvironment.getSelectionSet()` to inspect which sub-fields the client asked for and `SELECT` only those (avoids over-fetching at the DB layer — GraphQL solves over-fetching for the *client* but you must propagate it to the *database*).
- **Avoid deep, expensive default field resolvers.** A property fetcher that lazily hits the DB in a getter reintroduces N+1.
- **Cache parsed+validated documents.** Reuse the AST for repeated query strings (`graphql-java`'s `PreparsedDocumentProvider`). Persisted queries make this trivial.
- **Bound concurrency.** A single query can fan out into thousands of resolver tasks; cap the thread pool and DB connection pool, and load-shed.
- **Watch serialization cost.** Large responses are expensive to serialize as JSON; field-level limiting and pagination matter.

### 6.2 Correctness & concurrency

- **Query fields run in parallel** — never mutate shared state in a query resolver. Keep them pure reads.
- **Mutations run serially** at the top level — but their *sub-resolvers* still parallelize. Wrap the actual write in a transaction (`@Transactional` at the service layer, not the resolver).
- **DataLoader order contract:** batch results must align 1:1 with keys. Use a map-then-reorder pattern (§3.6.4).
- **Non-null propagation:** model nullability so that a single deep failure doesn't nuke an unrelated subtree. Prefer nullable fields for data that can legitimately fail to load; reserve `!` for true invariants.

### 6.3 Security (GraphQL has a distinct, larger attack surface than REST)

| Threat | Mitigation |
|---|---|
| **Malicious deep/complex queries (DoS)** — a single tiny query can request millions of nodes (e.g. cyclic `friends{friends{friends…}}`). | Depth limiting, complexity/cost limiting, query timeouts, pagination caps (`first` max), node/breadth limits. |
| **Introspection leakage** in production | Disable introspection in prod (or restrict to authenticated internal users). It hands attackers your full schema. *Note:* disabling alone isn't real security (field guessing still works) — pair with safelisting. |
| **Batching abuse** — sending hundreds of operations/aliases in one request to amplify cost. | Cap operation count, alias count, and duplicate-field count; rate-limit by cost, not request count. |
| **Field-level authorization** — REST's URL-based authz doesn't apply; any field is reachable. | Authorize *in resolvers* (or via `@auth` directive / instrumentation) on a per-field/per-object basis. Never rely solely on the gateway. |
| **Injection** — resolver code building SQL from args. | Use parameterized queries; treat all args as untrusted, exactly as in REST. Variables are typed but values are still attacker-controlled. |
| **Information leakage via errors** | Strip stack traces; map internal exceptions to safe messages; put codes (not internals) in `errors.extensions.code`. |
| **Persisted-query / safelist bypass** | In high-security contexts, accept **only** pre-registered queries (APQ in safelist mode). Rejects all ad-hoc queries. |

> **Real failure pattern:** A public GraphQL endpoint with introspection on, no depth limit, and a cyclic relationship (`user.friends.user.friends…`) is a textbook DoS. One crafted query can exhaust DB connections and CPU. This is *the* canonical GraphQL production incident.

### 6.4 Caching (GraphQL's biggest operational tradeoff vs REST)

REST gets **free HTTP caching**: `GET /users/42` is a cacheable URL — CDNs, browsers, and reverse proxies cache it with `Cache-Control`/`ETag`. GraphQL **loses this** because:
- Everything is `POST` to one URL (`/graphql`) → not cacheable by URL.
- The query body varies infinitely → no stable cache key.

Mitigations, from edge to data:

1. **Persisted queries + `GET`.** Turn the query into a stable hash, send as `GET /graphql?extensions=...`. Now the URL is stable and CDN-cacheable (Apollo's APQ + `GET` is built for this).
2. **Response cache with `@cacheControl`.** Apollo Server / Router can cache full responses keyed by query+variables+session scope, honoring per-field `maxAge` hints (the response's `maxAge` = the minimum across all selected fields).
3. **Normalized client caching.** Apollo Client / Relay cache by **normalized entity id** (`__typename` + `id`) on the client, deduping across queries. This is where GraphQL caching mostly lives in practice.
4. **Server-side data caching.** `DataLoader` (per-request) plus an application cache (Redis/Caffeine) behind the batch loader for cross-request hits — *carefully scoped* to avoid leaking per-user data.

> **Adjacent term — "ETag / Cache-Control":** HTTP caching headers. `Cache-Control: max-age=60` tells caches a `GET` response is fresh for 60 s; `ETag` is a version fingerprint enabling conditional `GET` (304 Not Modified). These attach to URLs — which is exactly why single-endpoint POST-based GraphQL can't use them without persisted-query tricks.

### 6.5 Observability

- **Per-field timing** via `Instrumentation` → emit spans (OpenTelemetry) and metrics (Micrometer). The Apollo Tracing format reports per-field start/duration.
- **Always send `operationName`** so logs/metrics group by operation, not by an opaque query blob.
- **Track resolver error rates, complexity scores, and DataLoader batch sizes** as first-class metrics. A drop in batch size (toward 1) signals a regressed N+1.
- **Correlate the single endpoint:** since everything hits `/graphql`, request-level metrics are useless without operation/field dimensions. Build dashboards by operation name and field path.

### 6.6 Testing

- **Schema tests:** snapshot the SDL; fail CI on unintended schema changes; run schema-diff to catch breaking changes (removing a field, narrowing a type, making an arg required).
- **Resolver/integration tests:** Spring's `GraphQlTester` / `HttpGraphQlTester` lets you fire documents and assert on response paths:
  ```java
  graphQlTester.documentName("getBook").variable("id","42")
      .execute().path("book.title").entity(String.class).isEqualTo("Dune");
  ```
- **N+1 regression tests:** count DB queries during a list query (e.g. with a query-counting datasource proxy) and assert it stays constant as the list grows.
- **Complexity/depth guard tests:** assert that an over-budget query is rejected with the expected error.

### 6.7 Production hardening checklist

- Introspection disabled (or auth-gated) in prod.
- Depth limit + complexity/cost limit + execution timeout configured.
- Max query size (bytes) and parse limits at the HTTP layer.
- `first`/pagination caps enforced server-side.
- Persisted queries / safelist for public/critical endpoints.
- Field-level authorization wired and tested.
- Errors sanitized; no stack traces leaked.
- Per-operation metrics + tracing.
- Bounded thread/connection pools + load shedding.

### 6.8 Anti-patterns

- **No batching** (the N+1 trap).
- **One giant `Query` god-resolver** doing everything imperatively instead of composing field resolvers.
- **Exposing the database schema 1:1** as the GraphQL schema (couples API to storage; loses the graph design opportunity).
- **Throwing for expected domain errors** instead of modeling them as result types/unions.
- **Over-using non-null `!`** → brittle null propagation.
- **Mutations that return only `Boolean`** → client must refetch; instead return the affected entity so the client cache updates.
- **Unbounded list fields with no pagination.**
- **Putting business logic in resolvers** instead of a service layer (resolvers should be thin adapters).

---

## 7. Advanced topics & deep internals

### 7.1 Federation (GraphQL for microservices)

A monolithic GraphQL schema doesn't fit a microservices org where different teams own different domains. **Apollo Federation** (the dominant standard; v1 and the current **v2**) composes multiple **subgraph** services into one **supergraph** behind a **gateway/router** that presents a single unified schema to clients.

Mechanics:

- Each subgraph owns some types and marks entities with **`@key(fields: "id")`** — declaring the field(s) that uniquely identify an entity so other subgraphs can reference it.
- A type can be **extended across subgraphs**: the `users` subgraph owns `User { id name }`; the `reviews` subgraph adds `User { reviews: [Review!]! }` by *referencing* `User` via its key.
- The router builds a **query plan**: it splits an incoming query into sub-queries per subgraph, calls each, and uses the special **`_entities`** root field + `@key` to fetch and **stitch** entity fields back together.
- Federation v2 directives: `@key`, `@shareable` (a field resolvable by multiple subgraphs), `@external`, `@requires`, `@provides`, `@override`, `@inaccessible`, `@tag`.

> **Adjacent term — "query plan":** The router's compiled execution strategy for a federated query — which subgraph to call for which fields, in what order, and how to join the results via entity keys. It's analogous to a database query planner but across HTTP services.

> **Adjacent term — "schema stitching":** An older, pre-federation technique for combining multiple GraphQL schemas at a gateway by manually defining how types link. Federation supersedes it with a declarative, decentralized model (ownership lives in each subgraph).

**JVM note:** Build a federated subgraph on the JVM with `federation-graphql-java-support` (adds `@key`, `_entities`, `_service`); the router itself is typically Apollo Router (Rust) or Apollo Gateway (Node), not JVM. Netflix DGS has first-class federation support.

**Tradeoff:** Federation buys team autonomy and a unified client API but adds a router hop, cross-service N+1 risk (entity fetches), distributed tracing complexity, and composition-validation tooling needs. Use it when multiple teams must independently own and ship parts of one graph.

### 7.2 Defer / Stream (incremental delivery)

The spec has an in-progress **`@defer`** and **`@stream`** feature: the server sends an initial response immediately and *streams* slow/large parts later as incremental payloads (via multipart HTTP or SSE). `@defer` on a fragment delays that fragment; `@stream` on a list streams elements as they resolve. This improves perceived latency for screens where some data is slow. Support is implementation- and version-specific (Apollo Router/Client and `graphql-java` have varying levels); flag as not-yet-universal.

### 7.3 Pagination deep dive: cursor vs offset, and the connection cost

- **Offset pagination** (`limit/offset`): simple but O(n) deep-page cost (DB scans+discards) and unstable under concurrent inserts (rows shift, causing skips/duplicates).
- **Keyset/cursor pagination**: cursor encodes the last seen sort-key tuple; `WHERE (sort_key, id) < (?, ?) ORDER BY … LIMIT n` is index-friendly and stable. The Relay `cursor` should encode exactly these keys (base64 the tuple).
- **`totalCount` is often a trap**: computing it may require a full `COUNT(*)` over a filtered set — expensive. Many APIs omit it or make it explicitly opt-in and approximate.
- **`hasNextPage`**: implement by fetching `n+1` rows and checking if the extra exists, rather than a separate count.

### 7.4 Null propagation edge cases

If `Query.user: User!` resolves null, propagation bubbles to `data: null` (since the parent is the root, which is nullable, but the field is non-null all the way up). If `User.name: String!` is null but `Query.user: User` is nullable, only `user` becomes null. Understanding *where* the null stops is essential for designing graceful partial responses. A common real bug: a non-null list element resolving null nullifies the *entire list* (then bubbles), surprising clients who expected the other elements.

### 7.5 Custom scalars and coercion internals

A scalar needs a **`Coercing<I, O>`** with three methods (in `graphql-java`):
- `serialize(Object)` — internal Java value → output (response) value.
- `parseValue(Object)` — incoming **variable** value → internal.
- `parseLiteral(Object)` — incoming **inline literal** in the query AST → internal.

Get these consistent (especially `parseValue` vs `parseLiteral`) or variables and inline literals behave differently. Use `ExtendedScalars` rather than rolling your own where possible.

### 7.6 HTTP status code semantics (the long debate)

Classic GraphQL returns **200** even with errors (the transport succeeded; the GraphQL operation may have partially failed — errors live in the body). The **GraphQL-over-HTTP spec** refines this: with the new media type `application/graphql-response+json`, a *request* that fails to parse/validate may return **400**, and auth failures **401/403**, while a successfully-executed-but-partially-errored operation still returns **200** with `errors`. This is **version/implementation-specific** — verify your server's mode before building client retry logic on status codes.

### 7.7 `@oneOf` input objects

A recent spec addition: `input ... @oneOf` declares a tagged-union *input* where exactly one field must be provided (e.g. `input By @oneOf { id: ID  email: String }`). Solves the long-standing awkwardness of "either-or" arguments. Support is version-gated (recent `graphql-java`).

### 7.8 Tuning knobs (`graphql-java`-specific)

| Knob | Effect | Default |
|---|---|---|
| `MaxQueryDepthInstrumentation(n)` | Reject queries deeper than `n`. | none (unlimited) until you add it |
| `MaxQueryComplexityInstrumentation(n)` | Reject over-budget queries. | none until added |
| `PreparsedDocumentProvider` | Cache parsed+validated docs. | none (parses every request) |
| `ExecutionInput.dataLoaderRegistry` | Enables batching. | empty registry (no batching) |
| `DataLoaderOptions` (caching, batch size, scheduling) | Tune batch behavior. | caching on, no batch-size cap |
| `subscriptionExecutionStrategy` / `ExecutionStrategy` | Override resolution strategy (async vs blocking). | `AsyncExecutionStrategy` |

> **Important default to flag:** `graphql-java` ships with **no** depth/complexity limits by default. An out-of-the-box server is DoS-vulnerable until you add the instrumentations yourself. This is the single most overlooked production gap.

---

## 8. Tradeoffs & decision frameworks

### 8.1 GraphQL vs REST vs gRPC

| Dimension | GraphQL | REST | gRPC |
|---|---|---|---|
| Data shaping | Client-driven, exact projection | Fixed per endpoint | Fixed per method |
| Over/under-fetching | Eliminated | Common | Per-method (proto) |
| Round-trips | One request, many resources | Many (or BFF endpoints) | One per call |
| Caching | Hard (POST/one URL); needs persisted queries/CDN tricks | Free, mature HTTP caching | None standard (binary RPC) |
| Schema/contract | Strong, introspectable, typed | OpenAPI (optional) | Strong (protobuf), required |
| Transport | HTTP (usually POST); WS for subs | HTTP, verb+URL semantics | HTTP/2, binary, streaming |
| Tooling/discoverability | Excellent (introspection, GraphiQL) | Good (OpenAPI/Swagger) | Good (proto/codegen) |
| Performance (wire) | JSON; flexible | JSON; simple | Binary, compact, fast |
| Server complexity | Higher (resolvers, N+1, limits) | Lower | Medium |
| Best fit | Many diverse clients, graph-shaped data | Public web APIs, caching, simple CRUD | Internal service-to-service RPC |

### 8.2 Use GraphQL when…

- Multiple heterogeneous clients (web, iOS, Android, partners) need divergent data shapes.
- The domain is genuinely a graph and clients traverse it unpredictably.
- You want to stop minting one endpoint per screen (kill BFF sprawl).
- Strong typed contract + great client tooling matter.
- You can invest in operational maturity (limits, observability, batching).

### 8.3 Avoid / reconsider GraphQL when…

- One client, simple CRUD → REST is less work.
- Public API where **CDN/HTTP caching** is the main performance lever → REST's URL caching is hard to beat.
- File upload/download or binary streaming → use REST/multipart (GraphQL multipart upload is a non-standard extension).
- High-throughput internal RPC with a stable contract → gRPC is faster and simpler.
- Team can't fund the security/observability work → an unhardened public GraphQL endpoint is a liability.

### 8.4 Schema-first vs code-first

Choose **schema-first** when the schema is a cross-team contract and frontend collaboration on SDL matters. Choose **code-first** when the backend is the source of truth and refactoring safety / no-drift outweigh SDL readability.

### 8.5 Monolithic schema vs federation

| | Monolithic graph | Federated supergraph |
|---|---|---|
| Team ownership | One team / coordinated | Independent teams per subgraph |
| Deployment | Single service | Independent subgraph deploys |
| Operational complexity | Lower | Higher (router, composition, query plans) |
| Cross-domain joins | In-process (fast) | Cross-service entity fetches (network) |
| Use when | Small org / single domain | Large org, multiple domains/teams |

---

## 9. Failure modes & debugging

### 9.1 N+1 explosion

**Symptom:** A list query is slow; DB shows hundreds of identical single-row `SELECT`s.
**Diagnose:** Enable SQL logging; count queries per request; inspect `DataLoader` batch-size metric (if it's ~1, batching isn't engaging). Check that the registry is per-request and registered, and that resolvers call `load()` (not a direct fetch).
**Fix:** Introduce `DataLoader`/`@BatchMapping`. Add an N+1 regression test.

### 9.2 DataLoader not dispatching (futures never complete / deadlock)

**Symptom:** Request hangs; `CompletableFuture`s from `load()` never resolve.
**Cause:** Mixing blocking calls inside resolvers that block the thread the dispatcher needs; or a `DataLoaderRegistry` not wired into the `ExecutionInput`/context; or calling `.get()`/`.join()` on a load future *inside* the same resolver (forcing a dispatch that can't happen yet).
**Fix:** Return the `CompletableFuture` from the resolver — never block on it. Ensure the registry is in the execution input. Don't share blocking thread pools that starve.

### 9.3 Query-of-death (DoS)

**Symptom:** CPU/connection-pool saturation from a single request; one giant nested/aliased query.
**Diagnose:** Log query complexity scores; capture the offending operation; check depth.
**Fix:** Add depth + complexity limits + timeout; cap operation/alias counts; move to persisted-query safelist for the endpoint.

### 9.4 Null-propagation surprise

**Symptom:** A whole subtree (or `data`) comes back null when one deep field failed.
**Diagnose:** Read the `errors[].path` — it points to the originating non-null violation.
**Fix:** Reconsider nullability; make legitimately-fallible fields nullable so failures stay localized.

### 9.5 Cross-request data leak via shared cache

**Symptom:** User A sees User B's data intermittently.
**Cause:** A `DataLoader` or cache scoped at application level (singleton) instead of per-request, leaking auth-scoped data.
**Fix:** Per-request registries; scope any shared cache by tenant/principal; never cache authz-sensitive data without the principal in the key.

### 9.6 Federation entity-resolution N+1

**Symptom:** Router fans out one `_entities` call per item across a subgraph boundary.
**Diagnose:** Inspect the query plan and subgraph access logs; look for repeated single-entity fetches.
**Fix:** Batch `_entities` resolution (the `representations` list is already batched per type — ensure your subgraph entity resolver handles the *list*, not one at a time).

### 9.7 Schema breaking change shipped

**Symptom:** Old clients break after a field is removed/renamed or an arg made required.
**Diagnose:** Schema diff against the previous version.
**Fix:** Use `@deprecated` and additive evolution (GraphQL favors *no versioning* — you add fields and deprecate old ones rather than `/v2`). Run schema-change checks in CI; track field usage to know when a deprecated field is safe to remove.

> **Real-world pattern:** GraphQL APIs typically **don't version** (no `/v2`). You evolve additively: add new fields, mark old ones `@deprecated(reason:)`, monitor usage, then remove. Breaking changes are caught by composition/diff tooling in CI. Skipping that tooling is a classic production incident.

---

## 10. Interview drill

**Q1. What problem does GraphQL solve that REST struggles with?**
*Model answer:* Over-fetching (REST returns fixed shapes with unused fields) and under-fetching/round-trips (REST needs multiple calls to assemble related data). GraphQL lets the client request exactly the fields it needs across the graph in one request; the response shape mirrors the query.
- *Follow-up: Doesn't a well-designed REST API with sparse fieldsets and compound endpoints solve this?* Partly — `?fields=` and BFF endpoints help, but they require server changes per client need and don't compose across relationships; GraphQL pushes that flexibility to the client without endpoint sprawl.
- *Follow-up: What does GraphQL give up to get this?* Free HTTP caching, simple URL-based observability, and added server complexity (resolvers, N+1, limits).

**Q2. Explain the N+1 problem in GraphQL and how DataLoader fixes it.**
*Model answer:* Each field resolves in isolation, so resolving `author` for N posts fires N separate user queries (plus the 1 for posts). `DataLoader` queues keys from all sibling resolvers, then at a dispatch point runs one batched query (`WHERE id IN (...)`) and per-request memoizes duplicates — turning N+1 into 2.
- *Follow-up: When does the batch actually fire?* The engine dispatches the loader once all resolvers at the current execution level have parked on `load()` calls — level-by-level dispatch via `DataLoaderDispatcherInstrumentation`.
- *Follow-up: What contract must the batch function honor?* Return results in the same order and cardinality as input keys, null-filling misses; otherwise you map values to the wrong parents.
- *Follow-up: Why must the DataLoader cache be per-request?* A shared cache would leak auth-scoped data across users and serve stale values.

**Q3. How do queries, mutations, and subscriptions differ in execution semantics?**
*Model answer:* Query root fields may run in parallel (must be side-effect-free); mutation root fields run strictly serially in document order (safe place for writes); subscriptions establish a long-lived event stream (usually WebSocket via `graphql-ws`), running the selection set per emitted event.
- *Follow-up: Do mutation sub-resolvers also run serially?* No — only the top-level mutation fields are serialized; their sub-trees parallelize.

**Q4. Why is caching harder in GraphQL, and how do you address it?**
*Model answer:* Everything is POST to one URL with a variable body, so URL-based HTTP/CDN caching doesn't apply. Mitigations: persisted queries + GET (stable cacheable URL), response caching with `@cacheControl`, normalized client caches (by `__typename`+id), and server-side data caches behind DataLoader.
- *Follow-up: How does a normalized client cache dedupe?* It stores entities by global id and merges fields from different queries into one cached object, so a later query reuses already-cached fields.

**Q5 (senior signal). When is GraphQL the wrong choice? Justify.**
*Model answer:* Single simple client (REST is less work), public APIs whose main perf lever is CDN caching, binary/file streaming, and high-throughput internal RPC (gRPC). Also wrong when the team can't fund the operational hardening (limits, authz-per-field, observability) — an unhardened public endpoint is a DoS and data-exposure liability.
- *Follow-up: A team wants GraphQL "because it's modern" for one mobile app over simple CRUD. What do you advise?* Push back: the cost (resolvers, batching, limits, caching loss) likely outweighs benefit; consider REST with sparse fieldsets, or a thin BFF, unless data needs are genuinely divergent/graph-shaped.

**Q6 (senior signal). Walk me through securing a public GraphQL endpoint.**
*Model answer:* Depth limit + complexity/cost budget + execution timeout; cap query size/alias/operation counts; pagination caps; field-level authorization in resolvers (URL authz doesn't apply); disable or auth-gate introspection; persisted-query safelist for high-security; sanitize errors; rate-limit by cost. Note `graphql-java` ships with *no* limits by default.
- *Follow-up: Is disabling introspection sufficient security?* No — field names are guessable; it's defense-in-depth, real protection comes from limits + safelisting + authz.
- *Follow-up: How do you rate-limit fairly?* By estimated query cost/complexity, not raw request count, since one request can be arbitrarily expensive.

**Q7. Explain non-null propagation and a bug it causes.**
*Model answer:* If a non-null (`!`) field resolves null, the engine can't emit null there, so it nullifies the nearest nullable ancestor, bubbling upward. Bug: a single null element in a `[T!]!` list nullifies the whole list (and possibly the parent), wiping unrelated data.
- *Follow-up: How do you design to avoid this?* Reserve `!` for true invariants; make fallible fields nullable so failures stay localized; model expected errors as result unions.

**Q8 (senior signal). Compare federation vs a monolithic schema for a 30-team org.**
*Model answer:* Federation lets each team own and deploy a subgraph independently while clients see one supergraph; the router builds a query plan and stitches entities via `@key`. Costs: a router hop, cross-service entity-fetch N+1 risk, composition validation, distributed tracing. For 30 teams the autonomy usually justifies it; for a single team it's overhead — prefer a monolithic graph.
- *Follow-up: How does the router fetch a field owned by another subgraph?* Via the `_entities` root field: it sends a list of entity `representations` (the `@key` fields) to the owning subgraph, which resolves the requested fields.
- *Follow-up: How do you avoid cross-subgraph N+1?* Ensure subgraph entity resolvers batch the `representations` list rather than resolving one entity per call.

**Q9. How do you paginate in GraphQL, and why cursors over offsets?**
*Model answer:* Relay Cursor Connections: `edges{node,cursor}`, `pageInfo{hasNextPage,endCursor}`, args `first/after`/`last/before`. Cursors (keyset) over offsets because offset pagination scans+discards rows (O(n) deep pages) and is unstable under concurrent writes; keyset uses an index on the sort tuple and is stable.
- *Follow-up: What's in a cursor?* An opaque (base64) encoding of the row's sort-key tuple — never an offset; clients must treat it as opaque.

**Q10. What's the difference between schema-first and code-first, and how do you prevent SDL/code drift?**
*Model answer:* Schema-first authors SDL and wires resolvers (contract-first, drift risk); code-first derives SDL from typed code (no drift, SDL is generated). Prevent drift in schema-first with CI checks that the wiring covers every field and schema-diff/snapshot tests.

**Q11. Why do most GraphQL APIs not version, and how do they evolve?**
*Model answer:* GraphQL favors additive evolution: clients only get fields they request, so adding fields never breaks anyone. You add new fields, `@deprecated` old ones with a reason, monitor field usage, then remove once unused. Breaking changes (removing/renaming, requiring an arg) are caught by schema-diff tooling in CI.

**Q12. Trace what happens server-side from receiving a query to returning the response.**
*Model answer:* Parse string→AST; validate AST against schema; (instrumentation hooks); select operation + coerce variables; collect fields (flatten fragments, apply `@skip`/`@include`); recursively execute fields invoking resolvers (queries parallel, mutations serial), batching via DataLoader at each level; complete values (scalar coercion, recurse objects, type-resolve unions, apply non-null propagation); assemble `{data, errors, extensions}`; return (HTTP 200 even on partial errors, classically).

---

## 11. Glossary

- **Alias** — A client-chosen response key for a field, allowing the same field twice or a renamed result.
- **APQ (Automatic Persisted Queries)** — Apollo scheme where clients send a query hash; the server stores/serves by hash, shrinking payloads and enabling GET/CDN caching.
- **Argument** — A typed parameter on a field (`user(id: ID!)`).
- **AST (Abstract Syntax Tree)** — In-memory tree representation of a parsed query.
- **BFF (Backend-for-Frontend)** — A per-client backend that tailors APIs to one frontend; GraphQL often replaces a proliferation of these.
- **Batch loader** — A function `(List<K>) -> List<V>` that fetches many keys in one call (the core of DataLoader).
- **Coercion** — Converting between wire values and internal types for scalars/variables (input coercion) and outputs (output coercion).
- **Code-first** — Defining the schema via typed code; SDL is generated.
- **Complexity/cost limiting** — Rejecting queries whose computed cost exceeds a budget, to prevent DoS.
- **Connection / Edge / PageInfo** — Relay's standard pagination types.
- **Context** — Per-request object shared across resolvers (auth, DataLoader registry, tracing).
- **Cursor** — Opaque pointer to a position in a paginated list (keyset value, not an offset).
- **DataFetcher** — `graphql-java`'s term for a resolver.
- **DataLoader** — Utility for batching and per-request caching of fetches; fixes N+1.
- **Depth limiting** — Rejecting queries nested beyond a configured depth.
- **Directive** — `@`-prefixed annotation altering execution or schema (`@skip`, `@deprecated`, `@key`).
- **DoS (Denial of Service)** — Overloading a service to make it unavailable; GraphQL's flexible queries are a DoS vector without limits.
- **ETag / Cache-Control** — HTTP caching headers attached to URLs.
- **Federation** — Composing multiple subgraph services into one supergraph behind a router.
- **Field** — A unit of data on a type; resolved by a resolver.
- **Field collection** — Execution step flattening fragments and applying `@skip`/`@include`.
- **Fragment** — Reusable named selection set; inline fragments select per runtime type.
- **GraphiQL** — In-browser IDE for exploring/running GraphQL queries (powered by introspection).
- **gRPC** — Binary HTTP/2 RPC framework; an alternative for internal service-to-service calls.
- **Input Object** — A type used only as an argument (`input CreateUserInput`).
- **Instrumentation** — `graphql-java` lifecycle hooks for tracing, metrics, and limits.
- **Interface** — Abstract type listing fields implementers must provide; enables polymorphism.
- **Introspection** — Querying the schema about itself via `__schema`/`__type`/`__typename`.
- **Keyset (cursor) pagination** — Paginating by a sort-key tuple; index-friendly and stable.
- **Mutation** — Operation that writes data; root fields run serially.
- **N+1 problem** — One query per related row instead of a single batched query.
- **Node** — Relay's global interface (`Node { id: ID! }`) for globally-identifiable objects.
- **Non-null (`!`)** — Type modifier asserting a value is never null; triggers propagation if violated.
- **Null propagation (bubbling)** — Nullifying the nearest nullable ancestor when a non-null field is null.
- **Operation** — A query, mutation, or subscription.
- **Persisted query** — A pre-registered query referenced by hash; enables caching and safelisting.
- **Polymorphism** — A field returning one of several concrete types (via interface/union).
- **Publisher / Reactive Streams** — JVM async stream API with backpressure; backs subscriptions.
- **Query** — Read operation; root fields may run in parallel.
- **Query plan** — Router's compiled strategy for executing a federated query across subgraphs.
- **Relay** — Facebook's opinionated GraphQL client; source of connection/Node/mutation conventions.
- **Resolver** — Function producing a field's value (`source, args, context, info`).
- **REST** — Resource-oriented HTTP API style with fixed endpoints and free URL caching.
- **Root types** — `Query`, `Mutation`, `Subscription` — operation entry points.
- **Round-trip** — One request/response network cycle.
- **Scalar** — Leaf value type (`Int`, `Float`, `String`, `Boolean`, `ID`, or custom).
- **Schema** — The typed contract describing all available data and operations.
- **Schema-first** — Authoring SDL as the source of truth and wiring resolvers to it.
- **Schema stitching** — Older gateway technique for combining schemas; superseded by federation.
- **SDL (Schema Definition Language)** — Syntax for describing a GraphQL schema.
- **Selection set** — The set of fields requested under a parent field.
- **Subgraph** — One service contributing part of a federated supergraph.
- **Subscription** — Long-lived operation streaming events to the client.
- **Supergraph** — The composed, unified schema exposed by a federation router.
- **Type resolver** — Resolves the concrete type of an interface/union value at runtime.
- **Union** — A type that is one of several member object types sharing no fields.
- **Variable** — A typed, externally-supplied parameter to an operation.
- **WebSocket** — Persistent bidirectional connection; the usual subscription transport (`graphql-ws`).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The model:** Schema (contract) → Query (request tree) → Resolvers (per-field fetch) → Response (shape = request). Single endpoint, usually `POST /graphql`.

**Type system:** 6 kinds — Scalar (`Int`=32-bit!, `Float`, `String`, `Boolean`, `ID` + custom), Object, Interface, Union, Enum, Input. Modifiers: `!` non-null, `[]` list.

**Operations:** Query (parallel, read), Mutation (serial top-level, write), Subscription (stream, usually WebSocket/`graphql-ws`).

**Lifecycle:** Parse → Validate → (Instrument) → Execute (coerce vars → collect fields → recurse resolvers → DataLoader dispatch per level) → Complete (coerce scalars, non-null propagation, collect errors) → `{data, errors, extensions}` (HTTP 200 even on partial errors, classically).

**N+1 fix:** DataLoader — batch (`load()` queues keys → one `WHERE IN` per level) + per-request cache. Batch must return key-aligned, same-cardinality results.

**Pagination:** Relay connections — `edges{node,cursor}`, `pageInfo{hasNextPage,endCursor}`, args `first/after`, `last/before`. Cursors = opaque keyset, not offsets.

**Caching loss:** No URL caching (POST/one URL). Use persisted queries + GET, `@cacheControl` response cache, normalized client cache (`__typename`+id), server data cache.

**Security must-haves:** depth limit, complexity/cost limit, timeout, alias/operation caps, pagination caps, field-level authz, introspection off in prod, error sanitizing, persisted-query safelist. **`graphql-java` has NO limits by default.**

**Federation:** subgraphs + `@key` → router builds query plan → `_entities` stitches. Use for multi-team orgs.

**Wrong choice when:** single simple client, CDN-caching-critical public API, file/binary streaming, high-throughput internal RPC (use gRPC), or no budget for hardening.

**Versioning:** Usually none — evolve additively, `@deprecated`, monitor usage, remove. Use schema-diff in CI.

**JVM stack:** `graphql-java` (core) + `java-dataloader` + extended-scalars; Spring for GraphQL (`@QueryMapping`/`@MutationMapping`/`@SubscriptionMapping`/`@BatchMapping`, `GraphQlTester`) or Netflix DGS; federation via `federation-graphql-java-support`.

### 12.2 Self-test (no answers — for active recall)

1. A list query returns 10 items and your DB logs show 11 queries. Name the problem, explain *why* it happens at the field-resolution level, and write the resolver change that fixes it — including the exact contract the batch function must satisfy.
2. A non-null list field `comments: [Comment!]!` returns one null element. Describe precisely what the client receives and why, then state the schema change that would localize the failure.
3. You must make a public GraphQL endpoint safe against a single hand-crafted DoS query. List every protective mechanism you'd enable and which ones `graphql-java` gives you out of the box by default.
4. Explain why GraphQL loses REST's free HTTP caching and describe two distinct techniques to recover edge/CDN caching, including the request-shape changes each requires.
5. Your org has many teams that must independently own parts of one client-facing graph. Describe the architecture you'd choose, how a field owned by team B is resolved when team A's subgraph is the entry point, and the main new failure mode it introduces.
6. Contrast offset and cursor pagination at the database level for deep pages, explain what a Relay `cursor` should encode, and why clients must treat it as opaque.
7. Walk through the full server-side request lifecycle from raw HTTP body to JSON response, naming each phase and what can fail in each.

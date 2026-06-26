# MCP — The Resources Primitive

> An engineering-handbook chapter on **Resources** in the **Model Context Protocol (MCP)**. Written for a senior JVM/Java backend developer who wants to master resources from first principles through deep internals: design, operation, debugging, teaching, and interviews.

---

## 0. Orientation: what you are about to read, and the vocabulary you need

Before the formal sections, a short orientation so every later term lands.

**MCP (Model Context Protocol)** is an open protocol, originally published by Anthropic in November 2024, that standardizes how an AI application connects to external context and capabilities. Think of it as "USB-C for LLM applications": instead of every app inventing its own bespoke way to feed files, database rows, and tools to a model, MCP defines one wire protocol so any compliant **client** can talk to any compliant **server**.

A few words you will see constantly — defined here once, expanded later:

- **LLM (Large Language Model):** the underlying AI model (e.g., Claude, GPT). It consumes text/tokens as input ("context") and produces text as output. It cannot natively open files or call your database — something must put that data into its context window.
- **Context window:** the bounded amount of text (measured in *tokens*, sub-word units) the model can "see" at once. Everything the model knows about your request must fit here. Resources are one disciplined way to fill it.
- **Host / Client / Server (the MCP trio):**
  - **Host** = the user-facing AI application (Claude Desktop, an IDE plugin, a chat app, Cowork). It embeds one or more **clients**.
  - **Client** = the protocol-speaking component *inside* the host; it maintains a 1:1 connection to a single server.
  - **Server** = a separate process or service that *exposes* capabilities (resources, tools, prompts) over MCP. You, the backend engineer, most often write servers.
- **JSON-RPC 2.0:** the message format MCP uses on the wire. It is a tiny, language-agnostic remote-procedure-call standard where every message is a JSON object with `method`, `params`, and (for requests) an `id`. We'll dissect it in §3.
- **Primitive:** in MCP, a "primitive" is one of the core capability *types* a server can offer. The three server-side primitives are **Tools**, **Resources**, and **Prompts**. (Clients also offer primitives — Sampling, Roots, Elicitation — but this chapter is about the server-side **Resources** primitive.)

This chapter is exclusively about **Resources**: application-controlled, read-only context data exposed by **URI** (Uniform Resource Identifier — a string that names a thing, like `file:///etc/hosts` or `db://users/42`).

---

## 1. Overview & where it fits

### 1.1 What a Resource is, in one sentence

A **Resource** is a named, addressable, read-only piece of context data — a file, a database row, an API response, a log snippet, a screenshot — that an MCP server exposes to a client by a **URI**, so the host application (or the user) can decide to load it into the model's context.

### 1.2 The problem it solves

LLMs are blind to your systems. To make a model useful on *your* data, you must inject relevant context into its prompt. Before MCP, every application solved this ad hoc: custom file readers, bespoke RAG pipelines, hand-rolled "paste the doc into the prompt" glue. Each integration was one-off and non-portable.

MCP's Resources primitive gives a **uniform, discoverable, addressable** way to publish read-only data:

- **Discoverable:** the client can ask "what resources do you have?" (`resources/list`) without knowing them in advance.
- **Addressable:** every resource has a stable **URI**; you can ask for exactly one by name (`resources/read`).
- **Read-only by contract:** resources *return data*; they never *perform actions* or cause side effects. (Contrast with Tools, which *do* things.)
- **Application-controlled:** the host/user decides which resources enter the context — not the model. This is the single most important design property and the cleanest way to distinguish resources from tools (covered in depth in §1.5 and §8).

### 1.3 When you reach for Resources

Reach for a resource when the answer to all of these is "yes":

1. The thing is **data to be read**, not an action to be performed.
2. It has (or can be given) a **stable identity** you can express as a URI.
3. It is reasonable for the **application or user** to decide whether/when to include it (rather than the model deciding autonomously).
4. Including it is **idempotent and side-effect-free** — reading it twice changes nothing.

Canonical examples: project files in an IDE, a row from a CRM database, a Confluence page, the latest log file, a rendered chart image, the contents of a Git blob.

### 1.4 The one-paragraph mental model

A resource is a **GET endpoint with a URI namespace, surfaced into an AI app's context-selection UI**. The server publishes a catalog (`resources/list`), optionally parameterized by **templates** (URI patterns with `{placeholders}`), and serves bytes/text on demand (`resources/read`). The client may **subscribe** to a resource to be told when it changes. Crucially, *the model does not pull resources autonomously*; the host (often via the user, e.g., an "@-mention file" picker) selects which resources to attach. Resources are the "nouns" of MCP; tools are the "verbs."

### 1.5 Where it sits relative to Tools and Prompts

| Primitive | What it is | Control | Side effects | Typical use |
|---|---|---|---|---|
| **Resources** | Read-only context **data** by URI | **Application/user-controlled** | None (idempotent reads) | Files, DB rows, API payloads, logs, images |
| **Tools** | Callable **functions/actions** | **Model-controlled** (model decides to call) | Yes (can mutate, send, charge) | Send email, run query, create ticket |
| **Prompts** | Reusable **prompt templates / workflows** | **User-controlled** (user invokes) | None directly | Slash commands, guided flows |

The decision rule, stated crisply: **data the app curates → resource; action the model invokes → tool; workflow the user triggers → prompt.** §8 gives the full decision framework.

---

## 2. Foundations from first principles

This section builds the concept from zero. If you already know JSON-RPC and URIs, skim — but the resource-specific semantics start at §2.4.

### 2.1 The transport layer: how bytes move

MCP messages travel over a **transport**. A transport is just "the pipe that carries JSON-RPC messages." MCP defines two standard ones:

- **stdio (standard input/output):** the client launches the server as a **subprocess** and exchanges newline-delimited JSON-RPC messages over the subprocess's stdin/stdout. ("Subprocess" = a child OS process started by the parent.) This is the default for local servers (e.g., a server that reads files on your laptop). Logging must go to **stderr**, never stdout, because stdout is the protocol channel — a stray `System.out.println` corrupts the stream.
- **Streamable HTTP:** the server is an HTTP service. The client POSTs JSON-RPC requests; the server may respond with a single JSON body **or** open a **Server-Sent Events (SSE)** stream for incremental/async messages. ("SSE" = a one-way HTTP streaming format where the server pushes `data:` lines to the client over a long-lived connection.) This is the transport for remote/hosted servers. *Version note:* the older spec (2024-11-05) used a separate "HTTP+SSE" transport with two endpoints; the **Streamable HTTP** transport (introduced 2025-03-26) consolidated this and is current. Flag this when targeting older clients.

Resources work identically over either transport — the primitive is transport-agnostic. Transports only affect *how* the JSON-RPC messages are framed and delivered.

### 2.2 JSON-RPC 2.0 in 90 seconds

Every MCP message is a JSON-RPC 2.0 object. There are three shapes:

- **Request** (expects a response): `{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{...}}`. The `id` correlates the response.
- **Response:** `{"jsonrpc":"2.0","id":1,"result":{...}}` on success, or `{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"..."}}` on failure.
- **Notification** (fire-and-forget, **no `id`**, no response expected): `{"jsonrpc":"2.0","method":"notifications/resources/updated","params":{...}}`.

Standard error codes you will meet: `-32700` parse error, `-32600` invalid request, `-32601` method not found, `-32602` invalid params, `-32603` internal error. MCP layers additional semantics on top (e.g., a resource-not-found is conventionally `-32002`, "Resource not found", per the spec's example, though servers vary — verify against your SDK).

### 2.3 The connection lifecycle (initialize → operate → shutdown)

Before any `resources/*` call, the client and server **handshake**:

1. **`initialize` request** (client → server): client sends its supported `protocolVersion`, its `capabilities`, and `clientInfo` (name/version).
2. **`initialize` result** (server → client): server replies with its chosen `protocolVersion`, its `capabilities` (this is where it advertises whether it supports resources, and which sub-features), and `serverInfo`.
3. **`notifications/initialized`** (client → server): client confirms it's ready. *Now* operation begins.

The server's `capabilities.resources` object is the key for this chapter. It looks like:

```json
{
  "capabilities": {
    "resources": {
      "subscribe": true,        // server supports per-resource update subscriptions
      "listChanged": true       // server will emit notifications/resources/list_changed
    }
  }
}
```

If a server omits `resources` from capabilities, it has **no resources** and the client must not call `resources/*`. If it includes `resources` but sets `subscribe:false` (or omits it), the client must not call `resources/subscribe`. These flags are the contract — honor them.

### 2.4 What makes a resource a *resource* (the semantic core)

Four invariants define the primitive:

1. **Identity = URI.** Every resource is named by a URI. The scheme (`file:`, `https:`, `db:`, `git:`, custom like `myapp:`) is yours to design; MCP doesn't restrict schemes. The URI must be stable enough that "read this URI" means the same thing across calls (modulo content changes).
2. **Read semantics.** A resource read is conceptually a `GET`: idempotent, side-effect-free, cacheable. Reading must not mutate state or trigger business actions.
3. **Application-controlled inclusion.** The host decides what to surface and the user/app decides what to attach. Servers must not assume the model autonomously fetched a resource. (Some clients *do* let models read resources, but the *design intent* and default UX is app/user curation.)
4. **Typed content.** Every read returns content annotated with a **MIME type** (e.g., `text/plain`, `application/json`, `image/png`). MIME ("Multipurpose Internet Mail Extensions") types tell the consumer how to interpret the bytes. Text content is returned as a `text` field; binary as a base64 `blob` field.

### 2.5 The four resource operations (the surface area)

| Method | Direction | Kind | Purpose |
|---|---|---|---|
| `resources/list` | client → server | request | Enumerate available concrete resources (paginated) |
| `resources/templates/list` | client → server | request | Enumerate **URI templates** (parameterized resources) |
| `resources/read` | client → server | request | Fetch the content of one or more resources by URI |
| `resources/subscribe` / `resources/unsubscribe` | client → server | request | Start/stop receiving update notifications for a URI |
| `notifications/resources/updated` | server → client | notification | "This subscribed resource changed" |
| `notifications/resources/list_changed` | server → client | notification | "The set of available resources changed" |

We dissect each in §3 (internals) and §4 (toolkit/parameters).

---

## 3. How it works internally — the heart of the doc

This section traces every workflow step by step: discovery, reading, templates, subscriptions, list-change, and pagination. I describe both the **control flow** (who sends what, when) and the **data flow** (the exact payload shapes).

### 3.1 Discovery: `resources/list`

**Goal:** the client learns which concrete resources exist.

**Control flow:**
1. After the lifecycle handshake (§2.3) and confirming `capabilities.resources` exists, the client sends `resources/list`.
2. The server returns an array of **Resource descriptors** plus an optional `nextCursor` for pagination.
3. If `nextCursor` is present, the client may call `resources/list` again with `params.cursor` set, repeating until `nextCursor` is absent.

**Request:**
```json
{ "jsonrpc":"2.0", "id":1, "method":"resources/list", "params": { "cursor": null } }
```

**Response (a Resource descriptor):**
```json
{
  "jsonrpc":"2.0","id":1,
  "result":{
    "resources":[
      {
        "uri":"file:///project/src/Main.java",
        "name":"Main.java",
        "title":"Application Entry Point",     // optional, human display name (spec 2025-06-18+)
        "description":"Main class with the bootstrap method",
        "mimeType":"text/x-java-source",
        "size": 2048,                          // optional, bytes — lets clients estimate token cost
        "annotations": {                       // optional hints
          "audience": ["user","assistant"],
          "priority": 0.8,
          "lastModified": "2026-06-20T10:00:00Z"
        }
      }
    ],
    "nextCursor":"opaque-cursor-token"
  }
}
```

**Field meanings (these are the descriptor schema):**
- `uri` (**required**): the canonical identifier.
- `name` (**required**): a programmatic/short name.
- `title` (optional): human-friendly display label; falls back to `name`.
- `description` (optional): free text; crucial because clients/models use it to decide relevance.
- `mimeType` (optional but recommended): the content type the read *will* return.
- `size` (optional): byte size, for token-budget estimation.
- `annotations` (optional): `audience` (who it's for: `user`, `assistant`), `priority` (0–1, relative importance), `lastModified` (ISO-8601 timestamp). Annotations are *hints*, not guarantees.

**Data-flow note:** the descriptor is metadata only — it does **not** contain content. Content comes from `resources/read`. This separation lets a client show a catalog cheaply (you can list 10,000 files without reading any).

### 3.2 Reading: `resources/read`

**Goal:** fetch actual bytes/text for a URI.

**Control flow:**
1. Client sends `resources/read` with a `uri`.
2. Server resolves the URI to content, reads it, and returns one or more **content items** in a `contents` array. (The array exists because a single URI *may* expand to multiple pieces — e.g., a directory URI returning several files, though most reads return exactly one item.)

**Request:**
```json
{ "jsonrpc":"2.0","id":2,"method":"resources/read","params":{ "uri":"file:///project/src/Main.java" } }
```

**Response — text content:**
```json
{
  "jsonrpc":"2.0","id":2,
  "result":{
    "contents":[
      {
        "uri":"file:///project/src/Main.java",
        "name":"Main.java",
        "title":"Application Entry Point",
        "mimeType":"text/x-java-source",
        "text":"public class Main { public static void main(String[] a){} }"
      }
    ]
  }
}
```

**Response — binary content (base64):**
```json
{
  "result":{
    "contents":[
      {
        "uri":"file:///assets/diagram.png",
        "mimeType":"image/png",
        "blob":"iVBORw0KGgoAAAANSUhEUgAA..."   // base64-encoded bytes
      }
    ]
  }
}
```

**The text/blob rule:** each content item carries **either** `text` (UTF-8 string) **or** `blob` (base64). Never both. The `mimeType` tells the consumer how to interpret it. If you omit `mimeType`, consumers must guess — avoid this.

**Error path:** if the URI doesn't exist or can't be read, return a JSON-RPC error (commonly `-32002` "Resource not found", with `data` carrying the URI). Do **not** return an empty `contents` array to signal not-found — that's ambiguous.

### 3.3 Resource templates: `resources/templates/list` and parameterized URIs

**The problem templates solve:** you cannot pre-list an infinite or huge space of resources. You can't enumerate every possible `db://users/{id}`. Instead you publish a **template**: a URI pattern with placeholders, following **RFC 6570 URI Template** syntax. ("RFC 6570" is the IETF standard defining `{var}` expansion in URIs.)

**Control flow:**
1. Client sends `resources/templates/list`.
2. Server returns template descriptors, each with a `uriTemplate` string.
3. The client (or user) fills in the variables to form a concrete URI, then calls `resources/read` with that URI.

**Response:**
```json
{
  "result":{
    "resourceTemplates":[
      {
        "uriTemplate":"db://users/{userId}",
        "name":"user-record",
        "title":"User Record",
        "description":"A single user row by numeric id",
        "mimeType":"application/json"
      },
      {
        "uriTemplate":"logs://{service}/{date}",
        "name":"service-log",
        "description":"Daily log for a service. date is YYYY-MM-DD",
        "mimeType":"text/plain"
      }
    ]
  }
}
```

**Important distinction:** `resources/list` returns *concrete, listable* resources; `resources/templates/list` returns *patterns* the client expands. A server may offer both, either, or neither. Templates do **not** appear in `resources/list` — they're a separate catalog because they're not directly readable until parameterized.

**Completion support (optional):** some servers implement the **`completion/complete`** method so clients can autocomplete template variables (e.g., suggest valid `userId`s). This is part of MCP's completion capability, not the resources capability per se, but it pairs naturally with templates.

### 3.4 Subscriptions: live updates

**The problem:** a resource's content can change (a file is edited, a row is updated). A client that attached the old content wants to know it's stale.

**Preconditions:** the server must advertise `capabilities.resources.subscribe = true`.

**Control flow (happy path):**
1. Client → server: `resources/subscribe` with `{ "uri": "..." }`. Server returns an empty success result and begins tracking interest in that URI.
2. When the underlying data changes, server → client: `notifications/resources/updated` with `{ "uri": "...", "title": "..." }`. *Critically, the notification does NOT include the new content* — it's a "ping," not a "push of data."
3. Client decides whether to re-read: it sends `resources/read` to fetch the fresh content. (This pull-after-notify design keeps notifications cheap and avoids pushing large payloads the client may not want.)
4. Client → server: `resources/unsubscribe` when no longer interested.

**Subscribe request:**
```json
{ "jsonrpc":"2.0","id":3,"method":"resources/subscribe","params":{ "uri":"file:///project/config.yaml" } }
```

**Update notification (server → client):**
```json
{ "jsonrpc":"2.0","method":"notifications/resources/updated","params":{ "uri":"file:///project/config.yaml" } }
```

**State the server must maintain:** a mapping from `(client connection) → set of subscribed URIs`, and a mechanism to detect changes (filesystem watcher, DB trigger/CDC, polling, webhook). When a change fires, look up subscribers and emit notifications only to them. On disconnect, drop the subscription set.

**Coalescing:** if a resource changes 100 times in a second, a well-behaved server should **debounce/coalesce** into one (or a few) notifications. The protocol doesn't mandate frequency, so this is your responsibility (see §6 performance).

### 3.5 List-changed: the catalog itself changed

Distinct from per-resource updates: sometimes the **set** of resources changes (a file is created/deleted, a new table appears). If the server advertises `capabilities.resources.listChanged = true`, it emits:

```json
{ "jsonrpc":"2.0","method":"notifications/resources/list_changed" }
```

On receipt, the client should re-run `resources/list` to refresh its catalog. Note: there is no payload — it's a "your list is stale, re-fetch" signal. This is independent of subscriptions; you don't subscribe to list changes, you just opt in via the capability flag.

### 3.6 Pagination internals

`resources/list` and `resources/templates/list` are **cursor-paginated**. The server returns an opaque `nextCursor` token; the client passes it back as `params.cursor`. The cursor is *opaque* — clients must not parse or construct it; only echo it. Absence of `nextCursor` means the last page. There is no client-controlled page size in the base spec; the server chooses page sizes. Implication: a server with millions of resources must implement stable, efficient cursoring (e.g., keyset pagination over a sorted key), not offset pagination that breaks under concurrent mutation.

### 3.7 End-to-end trace (concrete walk-through)

Scenario: an IDE host wants to attach a Java file to the model's context, then keep it fresh.

1. **Handshake.** Client sends `initialize`; server replies advertising `resources:{subscribe:true,listChanged:true}`; client sends `notifications/initialized`.
2. **Catalog.** Client sends `resources/list`. Server returns 200 file descriptors + `nextCursor`. Client sends `resources/list` with the cursor; server returns the last 50 + no cursor. Client now has the full catalog.
3. **User selects.** The user @-mentions `Main.java` in the IDE chat. The host maps that to `uri = file:///project/src/Main.java`.
4. **Read.** Client sends `resources/read` for that URI; server returns the source text with `mimeType:"text/x-java-source"`. The host injects the text into the model's context, typically tagged with the URI so the model knows the source.
5. **Subscribe.** Because the user pinned the file, the client sends `resources/subscribe` for that URI.
6. **Change.** The user edits and saves `Main.java`. The server's filesystem watcher fires; the server emits `notifications/resources/updated` for that URI.
7. **Refresh.** The client re-reads the resource and updates the attached context (or marks it stale and asks the user). The conversation continues with current content.
8. **Cleanup.** When the user closes the file or session, the client sends `resources/unsubscribe`; on disconnect the server drops all subscriptions.

This loop — *list → read → (optionally) subscribe → re-read on notify* — is the canonical resource lifecycle.

---

## 4. The complete toolkit

This section enumerates the protocol methods, the descriptor/content schemas, capability flags, and the **Java SDK** surface (the official `io.modelcontextprotocol.sdk` libraries), plus relevant CLI tools.

### 4.1 Protocol methods (wire-level)

| Method / Notification | Params (key fields) | Result (key fields) | Capability gate | Notes |
|---|---|---|---|---|
| `resources/list` | `cursor?` | `resources[]`, `nextCursor?` | `resources` present | Paginated; descriptors only (no content) |
| `resources/templates/list` | `cursor?` | `resourceTemplates[]`, `nextCursor?` | `resources` present | RFC 6570 templates |
| `resources/read` | `uri` (required) | `contents[]` (each: `uri`,`mimeType`,`text`\|`blob`) | `resources` present | Either `text` or `blob` per item |
| `resources/subscribe` | `uri` (required) | `{}` | `resources.subscribe = true` | Begins tracking |
| `resources/unsubscribe` | `uri` (required) | `{}` | `resources.subscribe = true` | Stops tracking |
| `notifications/resources/updated` | `uri`, `title?` | — (notification) | `resources.subscribe = true` | No content payload |
| `notifications/resources/list_changed` | — | — (notification) | `resources.listChanged = true` | Re-list after this |
| `completion/complete` | `ref` (resource/template), `argument` | `completion.values[]` | `completions` capability | Optional, autocompletes template vars |

### 4.2 The Resource descriptor schema (from `resources/list`)

| Field | Type | Required | Purpose / Default |
|---|---|---|---|
| `uri` | string | yes | Canonical id |
| `name` | string | yes | Short programmatic name |
| `title` | string | no | Display label; defaults to `name` |
| `description` | string | no | Relevance text for clients/models |
| `mimeType` | string | no | Expected content type; strongly recommended |
| `size` | integer | no | Byte size, for token budgeting |
| `annotations.audience` | string[] | no | `["user","assistant"]` |
| `annotations.priority` | number | no | 0–1 importance hint |
| `annotations.lastModified` | string | no | ISO-8601 timestamp |

### 4.3 The content item schema (from `resources/read`)

| Field | Type | When | Purpose |
|---|---|---|---|
| `uri` | string | always | Which resource this content belongs to |
| `name` / `title` | string | optional | Echoed descriptors |
| `mimeType` | string | recommended | How to interpret bytes |
| `text` | string | text resources | UTF-8 content |
| `blob` | string (base64) | binary resources | Encoded bytes |
| `annotations` | object | optional | Same shape as descriptor annotations |

### 4.4 Capability flags (server `initialize` result)

| Flag | Type | Default if omitted | Meaning |
|---|---|---|---|
| `resources` (object present) | object | not present → no resources | Server offers resources |
| `resources.subscribe` | boolean | `false` | Server honors `resources/subscribe` |
| `resources.listChanged` | boolean | `false` | Server emits `list_changed` notifications |

### 4.5 Java SDK toolkit (official MCP Java SDK)

The official Java SDK (`io.modelcontextprotocol.sdk:mcp`) provides both sync and async server builders. There is also first-class **Spring AI MCP** integration (`spring-ai-mcp-server` / Spring Boot starters) that auto-wires servers. Key types and methods:

| Type / Method | Purpose |
|---|---|
| `McpServer.sync(transportProvider)` / `McpServer.async(...)` | Entry-point builder for a server |
| `McpServerFeatures.SyncResourceSpecification` | Bundles a `Resource` descriptor + a read handler |
| `McpSchema.Resource(uri, name, title, description, mimeType, annotations)` | The descriptor record |
| `McpSchema.ResourceTemplate(uriTemplate, name, title, description, mimeType)` | Template descriptor |
| `McpSchema.ReadResourceResult(List<ResourceContents>)` | Read result wrapper |
| `McpSchema.TextResourceContents(uri, mimeType, text)` | Text content item |
| `McpSchema.BlobResourceContents(uri, mimeType, base64Blob)` | Binary content item |
| `.resources(SyncResourceSpecification...)` (on builder) | Register static resources |
| `.resourceTemplates(...)` | Register templates |
| `.capabilities(ServerCapabilities.builder().resources(listChanged, subscribe).build())` | Declare capability flags |
| `server.notifyResourcesUpdated(uri)` / `notifyResourcesListChanged()` | Emit notifications (names vary by SDK version — verify) |
| Transport: `StdioServerTransportProvider`, `HttpServletStreamableServerTransportProvider` / WebFlux/WebMvc providers | Wire transports |

> Version caveat: the Java SDK API has evolved quickly. Method names like `notifyResourcesUpdated` and the exact builder DSL differ across `0.x` releases and the Spring AI line. Always check the version on your classpath. The conceptual shapes above are stable even where names drift.

### 4.6 CLI / developer tooling

| Tool | Purpose |
|---|---|
| **MCP Inspector** (`npx @modelcontextprotocol/inspector`) | Interactive GUI/CLI to connect to a server, list/read resources, test subscriptions, inspect raw JSON-RPC. The primary debugging tool. |
| `npx @modelcontextprotocol/inspector --cli <cmd>` | Scriptable CLI mode for CI checks |
| Claude Desktop config (`claude_desktop_config.json`) | Registers stdio servers for manual testing in a real host |
| `mcp` Python CLI / SDK dev tools | Alternative ecosystem tooling; useful for cross-checking interop |

---

## 5. Code examples by use case

All examples are Java unless noted, using the official Java SDK's conceptual API. They span **five distinct scenarios**: (5.1) static file resource, (5.2) templated DB-row resource, (5.3) binary image resource, (5.4) subscriptions with a file watcher, (5.5) list-changed on dynamic catalog. A Spring AI variant and a raw JSON-RPC variant follow.

### 5.1 Static text resource — expose a single config file

```java
// Build a sync MCP server over stdio that exposes one read-only config file.
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.nio.file.*;
import java.util.List;

public class ConfigResourceServer {
  public static void main(String[] args) {
    var transport = new StdioServerTransportProvider(); // stdio: client launches us as a subprocess

    // 1) The descriptor advertised by resources/list
    var descriptor = new Resource(
        "file:///etc/myapp/config.yaml",   // uri (stable identity)
        "config.yaml",                      // name
        "App Configuration",                // title (display)
        "Runtime config for MyApp",         // description (helps relevance)
        "application/yaml",                 // mimeType
        null);                              // annotations

    // 2) The read handler: invoked on resources/read for this uri
    var spec = new McpServerFeatures.SyncResourceSpecification(
        descriptor,
        (exchange, request) -> {            // request.uri() == the requested URI
          try {
            String body = Files.readString(Path.of("/etc/myapp/config.yaml"));
            // Return ONE text content item; mimeType tells the client how to render
            return new ReadResourceResult(List.of(
                new TextResourceContents(request.uri(), "application/yaml", body)));
          } catch (Exception e) {
            // Surface as a protocol error rather than empty content
            throw new RuntimeException("Cannot read config: " + e.getMessage(), e);
          }
        });

    McpServer.sync(transport)
        .serverInfo("config-server", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .resources(false, false)        // listChanged=false, subscribe=false
            .build())
        .resources(spec)
        .build();                           // starts serving on stdio
  }
}
```

Key points: the descriptor is metadata; the handler does the actual I/O lazily on read; we throw on failure so the SDK emits a JSON-RPC error.

### 5.2 Templated resource — a database row by id

```java
// Expose db://users/{userId} as a template; read returns JSON for one row.
import io.modelcontextprotocol.spec.McpSchema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.sql.DataSource;

public class UserRecordResources {
  private final DataSource ds;
  private final ObjectMapper json = new ObjectMapper();

  UserRecordResources(DataSource ds) { this.ds = ds; }

  // Template descriptor advertised by resources/templates/list
  ResourceTemplate template() {
    return new ResourceTemplate(
        "db://users/{userId}",       // RFC 6570 template
        "user-record",
        "User Record",
        "One user row, keyed by numeric id",
        "application/json");
  }

  // Read handler: parse {userId} out of the concrete URI, then query.
  ReadResourceResult read(McpSyncServerExchange exchange, ReadResourceRequest req) {
    String uri = req.uri();                          // e.g. "db://users/42"
    String userId = uri.substring(uri.lastIndexOf('/') + 1);
    if (!userId.matches("\\d+")) {                   // validate BEFORE touching the DB
      throw new IllegalArgumentException("userId must be numeric: " + userId);
    }
    try (var c = ds.getConnection();
         var ps = c.prepareStatement(                // parameterized: prevents SQL injection
             "SELECT id, name, email FROM users WHERE id = ?")) {
      ps.setLong(1, Long.parseLong(userId));
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new NoSuchElementException("No user " + userId); // -> not-found error
        }
        var row = Map.of("id", rs.getLong("id"),
                         "name", rs.getString("name"),
                         "email", rs.getString("email"));
        String body = json.writeValueAsString(row);
        return new ReadResourceResult(List.of(
            new TextResourceContents(uri, "application/json", body)));
      }
    } catch (Exception e) {
      throw new RuntimeException("read failed for " + uri, e);
    }
  }
}
```

Note the **security discipline**: validate the extracted variable, use a **prepared statement** (parameterized SQL that separates code from data, blocking injection), and never string-concatenate user input into SQL.

### 5.3 Binary resource — return a PNG as base64

```java
// Expose a generated chart image as a binary resource.
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.*;

ReadResourceResult readChart(ReadResourceRequest req) {
  byte[] png = ChartRenderer.renderToPng(req.uri()); // your rendering logic
  String b64 = Base64.getEncoder().encodeToString(png);
  // BlobResourceContents carries base64 in the `blob` field; mimeType is mandatory in practice
  return new ReadResourceResult(List.of(
      new BlobResourceContents(req.uri(), "image/png", b64)));
}
```

Binary rule restated: use `blob` (base64), never `text`. Beware size — base64 inflates payload ~33%, and large blobs bloat the JSON-RPC message and the model's context (if the model is multimodal).

### 5.4 Subscriptions — file watcher pushing update notifications

```java
// Watch a file and notify subscribers when it changes.
import java.nio.file.*;
import java.util.concurrent.*;

public class WatchedFileResource {
  private final McpSyncServer server;            // gives us notify* methods
  private final String uri = "file:///project/config.yaml";
  private final Path path = Path.of("/project/config.yaml");
  private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
  private volatile long lastModified = 0;

  WatchedFileResource(McpSyncServer server) {
    this.server = server;
    // Poll-based change detection (a real impl could use WatchService for inotify/FSEvents)
    exec.scheduleAtFixedRate(this::checkForChange, 1, 1, TimeUnit.SECONDS);
  }

  private void checkForChange() {
    try {
      long mtime = Files.getLastModifiedTime(path).toMillis();
      if (mtime != lastModified) {
        lastModified = mtime;
        // Coalesced "ping": we send the URI only, NOT the new content.
        server.notifyResourcesUpdated(new ResourcesUpdatedNotification(uri));
      }
    } catch (Exception ignore) { /* file may be temporarily absent during atomic rename */ }
  }
}
```

The server must declare `.resources(false /*listChanged*/, true /*subscribe*/)` in capabilities for clients to be allowed to subscribe. The notification is a ping; the client pulls fresh content via `resources/read`. (Use `WatchService` for production-grade, OS-native change events instead of polling — covered in §6.)

### 5.5 Dynamic catalog — emit list_changed when files appear/disappear

```java
// When the set of available resources changes, tell clients to re-list.
void onNewFileCreated(Path newFile) {
  catalog.add(toDescriptor(newFile));            // update in-memory catalog
  server.notifyResourcesListChanged();           // payload-less signal -> client re-runs resources/list
}
```

### 5.6 Spring AI variant — declarative resource registration

```java
// With Spring AI MCP server starter, you can register resources as beans.
import org.springframework.context.annotation.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.List;

@Configuration
class ResourceConfig {

  @Bean
  SyncResourceSpecification readmeResource() {
    var desc = new Resource("file:///README.md", "README.md",
        "Project Readme", "Top-level docs", "text/markdown", null);
    return new SyncResourceSpecification(desc, (exchange, req) ->
        new ReadResourceResult(List.of(
            new TextResourceContents(req.uri(), "text/markdown",
                java.nio.file.Files.readString(java.nio.file.Path.of("README.md"))))));
  }
}
```

Spring Boot auto-configuration (`spring-ai-starter-mcp-server-webmvc` or `-webflux` for HTTP, or the stdio starter) discovers these beans and wires capabilities automatically. *Version note:* Spring AI MCP artifact names and the exact bean types have changed across milestones — verify against your Spring AI version.

### 5.7 Raw JSON-RPC — what a non-SDK client/server exchanges

If you implement the protocol by hand (e.g., in a language without an SDK), here is a full `resources/read` exchange over stdio (newline-delimited):

```text
// client -> server (stdin)
{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"file:///notes.txt"}}

// server -> client (stdout)
{"jsonrpc":"2.0","id":7,"result":{"contents":[{"uri":"file:///notes.txt","mimeType":"text/plain","text":"hello"}]}}
```

Remember: on stdio, **all** logging goes to stderr; stdout carries only protocol frames.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **List is metadata-only.** Never read file contents during `resources/list`. Return descriptors fast; defer I/O to `resources/read`. Listing 100k files should be a directory scan, not 100k file reads.
- **Pagination.** Use **keyset (seek) pagination** for large catalogs — sort by a stable key and let the cursor encode "last key seen." Offset pagination (`LIMIT/OFFSET`) degrades and double-counts under concurrent inserts.
- **Lazy + cache reads.** Reads are idempotent → cacheable. Cache by `(uri, version/etag/mtime)`. Invalidate on the same signal you use to fire update notifications.
- **Size hints.** Populate `size` so clients can budget tokens before reading; this avoids a read that blows the context window.
- **Coalesce notifications.** Debounce file/DB change bursts (e.g., 250–500 ms window) so a save that triggers 5 fs events yields 1 notification. Unbounded notifications can melt a client.
- **Stream big content carefully.** MCP `resources/read` returns the whole content in one result; there is no chunked-resource read in the base spec. For large data, either (a) expose a *template* that returns a slice/page (`logs://svc/2026-06-25?lines=100`), or (b) model it differently. Don't return a 500 MB blob.
- **Base64 overhead.** Binary content inflates ~33% over the wire and consumes more JSON parse time and memory. Prefer references/links for very large binaries when the client supports it.

### 6.2 Correctness & concurrency

- **Idempotency.** A read must not mutate. If "reading" a queue/topic would consume a message, that's a **tool**, not a resource — model it as such.
- **Snapshot consistency.** A read should return a coherent snapshot. For a file, read atomically; for a DB row, a single `SELECT` is fine; for a multi-row "resource," consider a transaction with a read snapshot (e.g., `REPEATABLE READ`).
- **Subscription state per connection.** Track subscriptions keyed by connection. On disconnect, purge. Don't leak subscriptions across reconnects.
- **TOCTOU on file resources.** Time-of-check-to-time-of-use: resolve and canonicalize the path *once*, validate it's inside the allowed root, then open via the canonical path — don't re-resolve symlinks between check and open.

### 6.3 Security (the highest-risk area for resource servers)

- **Path traversal.** For `file:` resources, canonicalize (`toRealPath()`), then verify the result is under an allow-listed **root**. Reject `..`, symlinks escaping the root, absolute paths outside the root, and URL-encoded traversal (`%2e%2e`).
- **Injection.** For `db:`/API templates, validate every extracted variable and use parameterized queries. Treat all template variables as hostile input — they may be model- or user-supplied.
- **Authorization.** Resources can leak data. Enforce authZ in the read handler based on the connection's identity. For HTTP transport, MCP's authorization spec leans on **OAuth 2.1**; bind reads to the authenticated principal. Never assume "the client already checked."
- **SSRF (Server-Side Request Forgery).** If a resource fetches a URL (e.g., `https://...`), an attacker-controlled URI could make your server hit internal endpoints (`http://169.254.169.254/` cloud metadata!). Allow-list schemes/hosts; block link-local and private ranges unless explicitly intended.
- **Secrets exposure.** Don't expose `.env`, private keys, or credential files as resources. Maintain a deny-list of sensitive patterns even within an allowed root.
- **Confused-deputy / over-broad roots.** Expose the **narrowest** root necessary. A server rooted at `/` is a data-exfiltration primitive.
- **Resource size / DoS.** Cap maximum read size; reject or paginate huge files to prevent memory exhaustion.
- **MIME spoofing.** Set `mimeType` from server knowledge, not from untrusted file extensions alone, when it matters for downstream rendering.

### 6.4 Observability

- **Log to stderr (stdio) / structured logs (HTTP).** Log every `resources/read` with URI (redacted if sensitive), latency, bytes, and outcome.
- **Metrics.** Counters for list/read/subscribe per URI scheme; latency histograms; cache hit ratio; active subscription count; notification emit rate (watch for storms).
- **Tracing.** Propagate a correlation id from the request; tie reads to the originating conversation/turn where the host supports it.
- **MCP logging capability.** The protocol has a `logging` capability (`notifications/message`) so servers can stream structured log records to clients/hosts — use it for operator-visible diagnostics.

### 6.5 Cost

- **Token cost is the hidden cost.** Each read injected into context costs tokens (input tokens are billed). A 50 KB file ≈ ~12–16k tokens (rough: ~3.5–4 chars/token for English; code can be denser). The `size` hint + client budgeting prevents surprise bills and context overflow.
- **Cache to cut compute.** Repeated reads of unchanged resources should hit cache, not re-render/re-query.

### 6.6 Testing

- **Use MCP Inspector** for manual/interactive verification of list/read/templates/subscribe.
- **Unit-test handlers** directly: feed a `ReadResourceRequest`, assert the `ReadResourceResult` shape, mimeType, and error paths (not-found, unauthorized, traversal attempt).
- **Contract tests** against the JSON schema: assert each content item has exactly one of `text`/`blob`, a `uri`, and a `mimeType`.
- **Security tests:** traversal payloads, oversized files, injection strings in template variables.
- **Notification tests:** mutate underlying data, assert a single coalesced `notifications/resources/updated` fires.

### 6.7 Production hardening checklist

- Capabilities accurately reflect implemented features (don't advertise `subscribe` you don't honor).
- Root allow-list + canonicalization for `file:` schemes.
- Read size cap + timeout per read.
- Notification debouncing + subscriber cleanup on disconnect.
- AuthZ enforced in every read handler.
- Structured logs to stderr; no `System.out` on stdio.
- Graceful errors as JSON-RPC errors (never crash the process on a bad URI).
- Backpressure: bound concurrent reads (thread pool / semaphore).

### 6.8 Anti-patterns

- **Modeling actions as resources.** If "reading" sends an email or pops a queue, it's a tool. Reads must be safe to repeat.
- **Returning content in `resources/list`.** Bloats listing and defeats lazy loading.
- **Pushing content in update notifications.** Notifications are pings; let the client pull.
- **Unbounded catalogs without pagination.** Forces clients to choke on giant responses.
- **Trusting the URI.** Every URI from a client is untrusted input.
- **Stringly-typed everything with no mimeType.** Consumers can't render correctly.
- **Over-broad roots / exposing secrets.**

---

## 7. Advanced topics & deep internals

### 7.1 URI scheme design

You own the scheme. Patterns that work well:
- **`file:`** for local files — canonical, widely understood, but security-sensitive.
- **`https:`/`http:`** when the resource *is* a web document — but beware SSRF.
- **Custom schemes** (`db:`, `git:`, `confluence:`, `myapp:`) to model your domain. Custom schemes signal "this is server-specific" and avoid collisions with filesystem semantics. Keep them stable; clients may persist URIs.
- **Opaque vs. hierarchical URIs.** Hierarchical (`db://users/42/orders/9`) is browsable and template-friendly; opaque (`myapp:resource/AbC123`) hides structure and is good for capability-style references. Choose based on whether clients should construct/guess URIs.

### 7.2 Templates and completion deep dive

- Templates follow **RFC 6570**. Most servers use the **simple expansion** form `{var}`; advanced operators (`{+var}` reserved expansion that doesn't percent-encode, `{?q,page}` query expansion, `{/path*}` path-segment expansion with explode) are valid but **client support varies** — test before relying on `{?...}`/`{/...}`.
- Pair templates with **`completion/complete`** so users get autocomplete on variables (e.g., enumerate valid `service` names). Completion returns up to a bounded set of candidate values plus a `hasMore`/`total` indicator.
- Templates are *not* enumerable resources; a client can't list every `userId`. If you also want the top-N concrete instances browsable, additionally publish a small set of concrete descriptors in `resources/list`.

### 7.3 The list/read separation and token economics

The two-phase design (cheap metadata list, on-demand read) exists precisely because **content is expensive in tokens**. This lets the host show a large catalog and let the *application/user* curate which few resources actually enter context. This is the architectural reason resources are "application-controlled" — the protocol is shaped to give the human/app the selection point.

### 7.4 Annotations and audience targeting

`annotations.audience` (`user` / `assistant`) lets a server hint whether a resource is meant for human display, model consumption, or both. `priority` (0–1) hints relative importance for ranking. Hosts may use these to auto-suggest or auto-attach high-priority resources, or to hide assistant-only payloads from the human UI. They are **hints**; never rely on a client honoring them.

### 7.5 Subscriptions: delivery semantics and ordering

- The spec does **not** guarantee exactly-once or strict ordering of `notifications/resources/updated`. Treat them as **at-least-once hints** that something changed; the *re-read* is the source of truth. This is why notifications carry no content — content delivery is reconciled by the pull.
- A change that happens between subscribe-ack and your watcher arming could be missed; mitigate by reading once right after subscribing (read-then-trust-notifications).
- On reconnect, a client should re-subscribe and re-read; servers should not assume subscription persistence across connections.

### 7.6 Resources vs. RAG vs. tool-fetched context

- **Resources** = explicit, app/user-curated context attached by URI.
- **RAG (Retrieval-Augmented Generation)** = the system embeds/searches a corpus and *automatically* injects top-k chunks. RAG is great when the *system* should choose context from a large corpus; resources are great when a *human/app* should choose specific items. They compose: a RAG search could be a **tool** that returns URIs the host then reads as **resources**.
- **Tool-fetched context** = a model calls a tool to fetch data autonomously. Use when the *model* must decide what to pull mid-reasoning; use resources when inclusion should be decided up front by the app/user.

### 7.7 Embedded resources in tool/prompt results

A subtlety: tools and prompts can **embed resource content** in their results (an `EmbeddedResource` content block carrying a `resource` with its `uri`+`text`/`blob`). This lets a tool return data *tagged with a resource URI* so the host can treat it as referenceable context. It blurs the line usefully: the action (tool) produces data labeled as a resource. This is distinct from the resources primitive's own `read`, but shares the content schema.

### 7.8 Roots (client primitive) and how they bound resources

Clients can expose **Roots** — filesystem (or URI) boundaries the client suggests the server operate within (e.g., "the open project folder"). A well-behaved resource server reads the client's roots (`roots/list`) and confines its `file:` catalog to those roots. This is the protocol-level mechanism behind "don't expose `/`." Roots are *advisory*; the server still must enforce its own allow-list.

### 7.9 Version-specific behavior to flag

- **2024-11-05:** original spec; HTTP+SSE transport; resources core present.
- **2025-03-26:** Streamable HTTP transport replaces HTTP+SSE; auth framework formalized.
- **2025-06-18:** added `title` field (distinct from `name`), refined annotations, clarified content/embedded-resource shapes, strengthened authorization (OAuth Resource Server, Resource Indicators).
Always negotiate `protocolVersion` and code defensively against optional fields.

### 7.10 Memory and large-result internals

Because `resources/read` returns the whole content in one JSON-RPC result, the server materializes the full payload (plus base64 inflation for blobs) in memory and the client parses it whole. For multi-MB resources this is a real heap concern on both sides. Mitigations: enforce a max-read-size, expose slice templates, and prefer references for huge binaries. There is no native streaming-chunk read of a single resource in the base spec.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Resource vs. Tool — the master decision table

| Question | Lean **Resource** | Lean **Tool** |
|---|---|---|
| Is it data to read or an action to perform? | Read | Perform |
| Side effects on read/call? | None | Yes (mutate/send/charge) |
| Who should decide to include it? | App / user | Model |
| Idempotent / safe to repeat? | Yes | Often not |
| Has a stable addressable identity (URI)? | Yes | N/A (named by function) |
| Needs parameters chosen by the model at reasoning time? | No (or app fills them) | Yes |
| Example | "Read user 42's profile" | "Update user 42's email" |

Rule of thumb: **if you'd implement it as a `GET`, it's a resource; if as a `POST/PUT/DELETE` or an RPC with effects, it's a tool.**

### 8.2 Static resources vs. templates

| Aspect | Static (`resources/list`) | Template (`resources/templates/list`) |
|---|---|---|
| Cardinality | Small, enumerable | Large/infinite parameter space |
| Discoverability | Fully listed | Pattern only; client fills vars |
| Best for | Fixed docs, known files | DB rows by id, logs by date, search by query |
| Completion | N/A | Pairs with `completion/complete` |

### 8.3 Subscriptions: when to support

| Use when | Avoid when |
|---|---|
| Data changes during a session and freshness matters (configs, live docs, dashboards) | Data is immutable or change-detection is expensive/unreliable |
| You have a clean change signal (fs watcher, CDC, webhook) | You'd have to poll heavily with no good debounce |
| Clients will act on updates (re-read) | No client cares; it's just overhead |

### 8.4 Transport choice for a resource server

| Choose | When |
|---|---|
| **stdio** | Local-only, single-user, accesses local files/processes; simplest; default for desktop hosts |
| **Streamable HTTP** | Remote/hosted, multi-user, needs auth, runs as a service; supports SSE for async notifications |

### 8.5 Resources vs. RAG vs. tool-fetch (selection framework)

| Mechanism | Who selects context | Best when |
|---|---|---|
| **Resources** | App / user | Specific, known items; auditable, curated context |
| **RAG** | System (similarity search) | Large corpus, system picks relevant chunks |
| **Tool-fetch** | Model | Model must decide mid-reasoning what to pull |

These are not mutually exclusive — combine as in §7.6.

---

## 9. Failure modes & debugging

### 9.1 Common failure modes and fixes

| Symptom | Likely cause | Diagnosis | Fix |
|---|---|---|---|
| Client never sees resources | Capability not advertised, or omitted `resources` object | Inspect `initialize` result in MCP Inspector | Declare `resources` capability correctly |
| `subscribe` rejected/ignored | `resources.subscribe` not advertised, or not implemented | Check capabilities; check server logs | Set `subscribe:true` AND implement handler |
| Corrupted/garbled protocol on stdio | `System.out` / println used for logging | Run server manually; look for non-JSON on stdout | Route all logs to stderr |
| Read returns empty / silent failure | Returning empty `contents` on not-found | Inspect raw read response | Throw a JSON-RPC error (`-32002`) instead |
| Update notifications never arrive | No change detection wired, or coalescing swallowed all | Mutate data; watch notification stream in Inspector | Wire watcher; verify debounce window |
| Notification storm freezes client | No coalescing on bursty changes | Metric: notifications/sec spikes | Debounce 250–500 ms; cap rate |
| Huge read OOMs server or client | Returning multi-MB blob whole | Heap dump / OOM logs; payload size metric | Cap read size; paginate via template |
| Wrong rendering of content | Missing/incorrect `mimeType` | Inspect content item | Always set accurate `mimeType` |
| Data leak / unauthorized read | No authZ in read handler; over-broad root | Audit; pen-test traversal | Enforce authZ; allow-list root; canonicalize paths |
| Path traversal works | No canonicalization/root check | Try `file:///../../etc/passwd` | `toRealPath()` + root containment check |
| Stale content after edit | No subscription / client didn't re-read | Compare attached vs. on-disk | Subscribe + re-read on notify |
| Pagination loops or misses items | Offset pagination under concurrent writes | Add items mid-list; observe dupes/gaps | Use stable keyset cursor |

### 9.2 The debugging toolkit in practice

1. **MCP Inspector first.** Connect (`npx @modelcontextprotocol/inspector node server.js` or your launch command). Use the Resources tab to: confirm capabilities, run `resources/list`, page through, `resources/read` specific URIs, list templates, and test subscribe → mutate → observe `updated`. The raw JSON-RPC pane shows exact frames.
2. **Run the server standalone.** Pipe a handcrafted `initialize` + `resources/read` into stdin; inspect stdout. Any non-JSON on stdout is a bug.
3. **Turn on protocol logging.** Use the `logging` capability or stderr structured logs; correlate by request id.
4. **Reproduce with curl (HTTP transport).** POST a JSON-RPC `resources/read`; inspect the body or SSE stream.
5. **Network/heap profiling** for size issues: measure payload bytes and base64 inflation; profile heap on large reads.

### 9.3 Real-world-style incident sketches

- **"The println that broke MCP."** A team added a debug `System.out.println` in a read handler on a stdio server. Every read injected stray text into stdout, corrupting the JSON-RPC stream; the host showed "invalid message." Fix: route all logging to stderr; add a CI test that asserts stdout contains only valid JSON frames. (This is the single most common stdio bug.)
- **"The metadata read."** A file server with 80k files implemented `resources/list` by reading each file to compute `size` and a preview. Listing took 40+ seconds and timed out clients. Fix: derive `size` from `Files.size()` (stat, not read); drop previews; defer all content to `resources/read`.
- **"The subscription storm."** A config server emitted one `updated` per inotify event; an editor's atomic-save produced 6 events per save, and a formatter-on-save loop produced dozens per second, freezing the client. Fix: 300 ms debounce + dedupe by URI.
- **"The traversal."** A `file:` server rooted at the project dir didn't canonicalize; a crafted `file:///project/../../../../etc/shadow` URI read a sensitive file. Fix: `toRealPath()` then assert `startsWith(root)`; reject otherwise.
- **"The SSRF resource."** A server exposed `fetch://{url}` resources; a request for `fetch://http://169.254.169.254/latest/meta-data/iam/security-credentials/` exfiltrated cloud IAM creds. Fix: allow-list schemes/hosts; block link-local/metadata IPs.

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. The last three are "senior-signal" (judgment/tradeoff).

**Q1. What is a Resource in MCP, and how does it differ from a Tool?**
*Model answer:* A resource is read-only, application/user-controlled context data identified by a URI; reads are idempotent and side-effect-free. A tool is a model-controlled callable that performs actions and may have side effects. Resources are nouns (data to read), tools are verbs (actions to perform); the model decides to call tools, the app/user decides to include resources.
- *Probe: Who controls inclusion of each, and why does it matter?* Tools: model-controlled (it autonomously calls them). Resources: app/user-controlled. It matters for safety/auditability and token economics — humans curate what enters context.
- *Probe: Where's the gray area?* RAG-as-a-tool returning URIs the host reads as resources; embedded resources in tool results.
- *Probe: HTTP analogy?* Resource ≈ GET; tool ≈ POST/PUT/DELETE.

**Q2. Walk through the methods of the resources primitive.**
*Model answer:* `resources/list` (paginated descriptors), `resources/templates/list` (RFC 6570 templates), `resources/read` (content by URI, returns `contents[]` with `text`/`blob`+`mimeType`), `resources/subscribe`/`unsubscribe`, plus notifications `notifications/resources/updated` and `.../list_changed`.
- *Probe: Why is list separate from read?* Token economics — cheap metadata browsing, expensive content fetched on demand by the curator.
- *Probe: What's in an update notification?* Just the URI (a ping); content is pulled via re-read.
- *Probe: How does the client know which are supported?* The `capabilities.resources` object and its `subscribe`/`listChanged` flags.

**Q3. Explain resource templates.**
*Model answer:* Parameterized URI patterns (RFC 6570, e.g., `db://users/{userId}`) for spaces too large to enumerate. The client/user fills variables to form a concrete URI, then reads it. Listed via `resources/templates/list`, distinct from concrete `resources/list`.
- *Probe: How do users discover valid values?* Optional `completion/complete`.
- *Probe: Are templates readable directly?* No — only after expansion to a concrete URI.

**Q4. How do subscriptions and notifications work, including delivery guarantees?**
*Model answer:* Requires `subscribe` capability. Client `resources/subscribe` by URI; on change the server sends `notifications/resources/updated` (URI only); client re-reads. No content is pushed. Delivery is best-effort at-least-once with no strict ordering — the re-read is the source of truth.
- *Probe: How avoid missed changes around subscribe time?* Read once right after subscribing.
- *Probe: How handle bursty changes?* Debounce/coalesce.
- *Probe: list_changed vs updated?* list_changed = the catalog changed (re-list); updated = a subscribed resource's content changed (re-read).

**Q5. How do you represent binary vs text content?**
*Model answer:* Each content item carries exactly one of `text` (UTF-8) or `blob` (base64), always with `mimeType`. Binary uses `blob`; base64 inflates ~33%.
- *Probe: Risk of large blobs?* Memory/token blowup; cap size or use slice templates.
- *Probe: Multimodal implications?* An `image/png` blob can feed a multimodal model's context.

**Q6. What's the connection lifecycle before any resources call?**
*Model answer:* `initialize` (client caps/version) → server `initialize` result (server caps incl. `resources`) → client `notifications/initialized`. Only then call `resources/*`, gated by advertised capabilities.

**Q7. Name the top security risks for a resource server and mitigations.**
*Model answer:* Path traversal (canonicalize + root allow-list), injection (parameterized queries, validate template vars), SSRF for URL-fetching resources (scheme/host allow-list, block metadata IPs), authorization (enforce per principal in read handler), secret exposure (deny-list), DoS via huge reads (size caps).
- *Probe: Where do roots fit?* Clients advertise roots to bound the server's `file:` space; server still enforces its own allow-list.
- *Probe: TOCTOU?* Canonicalize once and open via the canonical path.

**Q8 (senior-signal). When would you model something as a resource vs a tool vs RAG, and why?**
*Model answer:* Resource when it's specific, addressable, read-only data the app/user should curate (auditable, deterministic context). Tool when the model must decide to act or fetch mid-reasoning, or there are side effects. RAG when the *system* should select from a large corpus by similarity. Justify by control point (who decides), side effects, and cardinality. They compose (RAG tool → resource reads).
- *Probe: A "search the docs" feature — which?* Often a tool returning URIs the host reads as resources, combining model-driven selection with curated, addressable context.
- *Probe: Why not make everything a tool?* You'd cede curation to the model, lose auditability, and burn tokens on model-driven fetches; resources keep humans in the selection loop.

**Q9 (senior-signal). Your resource server must serve a 10M-row table and a 5 GB log directory. Design the resource model.**
*Model answer:* Don't enumerate rows — expose a template `db://rows/{id}` plus maybe a small set of "pinned" concrete resources; add `completion/complete` for id discovery; consider a search **tool** that returns matching URIs. For logs, expose slice templates `logs://{service}/{date}?lines=N&offset=M` to bound payloads; cap read size; never return whole files. Add `size` hints, keyset pagination on any list, and caching keyed by mtime/etag.
- *Probe: How keep context small?* Slice templates, size hints, client token budgeting, summaries via prompts.
- *Probe: Freshness?* Subscribe to specific pinned resources with debounced updates; logs likely append-only so subscribe to "latest" with coalescing.

**Q10 (senior-signal). Subscriptions add complexity; when is the juice worth the squeeze, and what are the failure modes you'd guard against?**
*Model answer:* Worth it when intra-session freshness materially changes correctness (live configs, evolving docs/dashboards) and you have a reliable, debounced change signal and clients that act on updates. Guard against: notification storms (debounce/cap), missed changes around subscribe (read-then-trust), subscription leaks (purge on disconnect), and assuming ordering/exactly-once (reconcile via re-read). If change detection is flaky or no client reacts, skip subscriptions and let clients re-read on demand.
- *Probe: Cost model?* Each notify → potential re-read → tokens; storms multiply cost. Coalescing protects both latency and bill.
- *Probe: Stateless HTTP servers?* Subscription state must survive across SSE streams / be re-established on reconnect; design for re-subscribe.

**Q11. How do you debug "the client sees no resources"?**
*Model answer:* Check the `initialize` result for the `resources` capability in MCP Inspector; if missing, fix capability declaration. If present, run `resources/list`; check pagination/cursor handling and server logs (stderr). Ensure stdout isn't polluted by logging on stdio.

**Q12. What are the token/cost implications of resources, and how do you control them?**
*Model answer:* Injected content costs input tokens (roughly char-count/3.5–4). Control with `size` hints + client budgeting, slice templates, caching, and curating few high-priority resources rather than dumping everything. The list/read split exists to make curation cheap.

---

## 11. Glossary

- **Annotations:** optional hints on resources (`audience`, `priority`, `lastModified`); advisory, not guaranteed.
- **Application-controlled:** inclusion decided by the host app/user, not the model. Defining property of resources.
- **at-least-once:** a delivery guarantee where a message may arrive one or more times; consumers must be idempotent. MCP update notifications are best treated this way.
- **Base64:** text encoding of binary data; inflates size ~33%. Used in the `blob` content field.
- **CDC (Change Data Capture):** technique to detect and stream DB changes (e.g., via the write-ahead log); a clean change signal for subscriptions.
- **Capability:** a declared feature in the `initialize` handshake; `resources`, `resources.subscribe`, `resources.listChanged` gate this primitive.
- **Client:** the protocol component inside a host that maintains a 1:1 connection to a server.
- **Content item:** one element of a `resources/read` result; carries `uri`, `mimeType`, and exactly one of `text`/`blob`.
- **Context window:** the bounded token span the model can see at once.
- **Cursor (pagination):** opaque token from the server to fetch the next page; clients echo, never parse it.
- **Debounce / coalesce:** collapse a burst of change events into one notification within a time window.
- **Descriptor:** the metadata object for a resource returned by `resources/list` (no content).
- **Embedded resource:** resource content embedded inside a tool/prompt result, tagged with a URI.
- **Host:** the user-facing AI application embedding one or more clients.
- **Idempotent:** repeating the operation yields the same result with no extra effect; resource reads must be.
- **inotify / FSEvents / WatchService:** OS / Java file-change notification mechanisms (Linux / macOS / Java NIO).
- **JSON-RPC 2.0:** the message format MCP uses; requests have `id`+`method`+`params`, notifications omit `id`.
- **Keyset (seek) pagination:** paginating by "last key seen" rather than offset; stable under concurrent writes.
- **LLM:** large language model; consumes tokens, produces tokens.
- **listChanged:** capability/notification indicating the *set* of resources changed (re-list).
- **MCP (Model Context Protocol):** open protocol standardizing AI-app ↔ context/capability connections.
- **MIME type:** string like `text/plain`, `application/json`, `image/png` describing content format.
- **Notification:** JSON-RPC message with no `id` and no response expected.
- **OAuth 2.1:** the authorization framework MCP's HTTP transport leans on for auth.
- **Prepared statement:** parameterized SQL separating code from data, preventing injection.
- **Primitive:** a core MCP capability type (Tools, Resources, Prompts server-side; Sampling, Roots, Elicitation client-side).
- **Prompt (primitive):** reusable, user-invoked prompt template/workflow.
- **RAG (Retrieval-Augmented Generation):** automatically retrieving and injecting corpus chunks via similarity search.
- **RFC 6570 (URI Template):** IETF standard defining `{var}` expansion in URIs; used by resource templates.
- **Roots:** client-advertised boundaries (often folders) suggesting where a server should operate.
- **Server:** a process/service exposing MCP primitives (what backend engineers usually write).
- **SSE (Server-Sent Events):** one-way HTTP streaming of `data:` events; used by Streamable HTTP transport.
- **SSRF (Server-Side Request Forgery):** tricking a server into making requests to unintended (often internal) endpoints.
- **stdio transport:** client launches server as a subprocess; JSON-RPC over stdin/stdout; logs to stderr.
- **Streamable HTTP transport:** current HTTP-based MCP transport (2025-03-26+) consolidating the old HTTP+SSE.
- **Subprocess:** a child OS process started by a parent.
- **Subscription:** client interest in update notifications for a specific resource URI.
- **Template (resource):** parameterized URI pattern enumerated via `resources/templates/list`.
- **Token:** sub-word unit of model input/output; the unit context/cost is measured in.
- **TOCTOU:** time-of-check-to-time-of-use race, relevant to file path validation.
- **Tool (primitive):** model-controlled callable action, possibly with side effects.
- **URI / URI scheme:** string identifying a resource; scheme is the prefix (`file:`, `db:`, `https:`).
- **updated (notification):** indicates a *subscribed resource's content* changed (re-read).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Identity:** every resource = a **URI**. **Semantics:** read-only, idempotent, **GET-like**, **app/user-controlled** (not model). **Typed:** every content item has a `mimeType` + exactly one of `text`/`blob` (base64).

**Methods:** `resources/list` (paginated descriptors, no content) · `resources/templates/list` (RFC 6570 patterns) · `resources/read` (returns `contents[]`) · `resources/subscribe`/`unsubscribe` · notifications `resources/updated` (URI-only ping → re-read) and `resources/list_changed` (re-list).

**Capabilities:** advertise `resources`; `resources.subscribe` (default false) gates subscribe; `resources.listChanged` (default false) gates list-changed.

**Resource vs Tool:** data to read = resource; action to perform = tool. GET → resource; POST/PUT/DELETE → tool. App curates resources; model calls tools.

**Templates vs static:** infinite/large parameter space → template (+ optional `completion/complete`); small fixed set → static list.

**Notifications:** carry no content; at-least-once, no strict ordering; truth = re-read. **Debounce** bursts (250–500 ms). **Purge** subscriptions on disconnect.

**Top risks:** path traversal (canonicalize + root allow-list), injection (parameterized queries), SSRF (host allow-list, block metadata IPs), authZ (per principal in read handler), DoS (cap read size), stdout pollution on stdio (log to **stderr** only).

**Cost:** content costs input tokens (~chars/3.5–4); use `size` hints, slice templates, caching, curate few items. List is metadata-only — never read content during list.

**Transports:** stdio (local subprocess, stderr logging) · Streamable HTTP (remote, SSE, OAuth 2.1). Resources are transport-agnostic.

**Version flags:** `title` field & strengthened auth in 2025-06-18; Streamable HTTP since 2025-03-26 (replaced HTTP+SSE from 2024-11-05).

### 12.2 Self-test (no answers — active recall)

1. A teammate wants "reading a resource" to also mark a message as consumed from a queue. Explain in protocol terms why this is wrong and what primitive they should use instead.
2. Design the resource model for a service exposing 50M database rows plus a daily-rotating log directory, keeping context-token cost bounded — specify which methods, templates, and capabilities you'd use and why.
3. Your stdio server "works" in unit tests but the host reports "invalid message." List the three most likely causes and how you'd confirm each with concrete tools/commands.
4. Walk through the exact JSON-RPC sequence (methods + who sends what) from connection establishment to a client receiving a content update for a file it has attached, including capability gates.
5. Give three concrete attacks against a `file:`-scheme resource server and the precise mitigation for each.
6. Explain why `notifications/resources/updated` carries no content, and what failure this design choice prevents versus what new responsibility it places on the client.
7. State the rule for choosing between `resources/list` and `resources/templates/list`, then give two real examples on each side of the line.

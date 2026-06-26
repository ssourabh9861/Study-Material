# MCP — The Prompts Primitive

> An exhaustive engineering-handbook chapter on **prompts** in the Model Context Protocol (MCP): what they are, how the wire protocol exposes them, how clients and servers negotiate and exchange them, the full method/type toolkit, idiomatic code across multiple use cases, production concerns, deep internals, tradeoffs, debugging, and an interview drill.

---

## 0. Reading orientation (start here)

Before we dive in, a one-paragraph map so you always know where you are.

MCP (**Model Context Protocol**) is an open protocol, originally published by Anthropic in **November 2024**, that standardizes how applications feed context to large language models (LLMs). Think of it as "USB-C for LLM context": instead of every app inventing a bespoke way to wire data sources and capabilities into a model, MCP defines one JSON-RPC-based contract. MCP defines a small set of **primitives** — the nouns the protocol traffics in. On the **server** side the three core primitives are **Tools**, **Resources**, and **Prompts**. This chapter is exclusively about **Prompts**.

A *Prompt* in MCP is **a reusable, parameterized template, defined by a server, that a server can render into a sequence of chat messages on request.** The defining cultural fact about prompts — the thing you must internalize first — is that they are **user-controlled**: they are meant to be surfaced in the UI and *invoked deliberately by a human* (the classic surface is a slash-command menu), not silently called by the model. That single design decision (the "control axis") explains almost every other property of the primitive.

If you remember nothing else: **Tools are model-controlled, Resources are application-controlled, Prompts are user-controlled.** Prompts exist so a server author can ship a curated, named, argument-driven interaction that a user can pick from a menu and run.

---

## 1. Overview & where it fits

### 1.1 What a Prompt is

An MCP **prompt** is a named, discoverable template that a server publishes. Each prompt has:

- A **name** (a stable identifier, e.g. `summarize_pr`, `code_review`, `explain_error`).
- Optional human-facing metadata: a `title` (display name) and a `description`.
- An optional list of **arguments** — named parameters the prompt accepts (e.g. `repo`, `pr_number`, `tone`).
- A server-side **rendering behavior**: when the client calls `prompts/get` with concrete argument values, the server returns a fully-formed **sequence of messages** (roles + content) ready to be inserted into a model conversation.

The crucial conceptual move: a prompt is **not** just a string. It is a *factory for a conversation slice*. The output of "getting" a prompt is an array of messages — each with a `role` (`user` or `assistant`) and `content` (text, image, audio, or an embedded resource). The host application takes those messages and seeds the LLM conversation with them.

### 1.2 The problem it solves

Without MCP prompts, high-quality, reusable LLM interactions live in three bad places:

1. **Hard-coded in the host app.** The app developer bakes in "summarize this," "review this code," etc. Every server/integration that wants a tailored workflow has to convince the app team to add UI. This does not scale.
2. **Copy-pasted by users.** Users keep personal `.txt` files of "good prompts" and paste them in. Brittle, unshareable, un-versioned, no parameterization.
3. **Buried inside tools.** Some teams smuggle "guided workflows" into tool descriptions, which conflates *what the model can do autonomously* with *what the user wants to deliberately trigger*.

MCP prompts solve this by letting **the server that owns a domain also own the canonical, parameterized interactions for that domain**, and by giving the host a standard way to **discover** (`prompts/list`) and **render** (`prompts/get`) them. A Git server can ship a `summarize_pr` prompt; a database server can ship an `explain_query_plan` prompt; an incident-tooling server can ship a `postmortem_skeleton` prompt. The user sees them all as first-class, menu-selectable commands.

> **Term: JSON-RPC.** "JSON-RPC 2.0" is a lightweight remote-procedure-call protocol where every message is a small JSON object. A **request** has `jsonrpc:"2.0"`, a `method` name, a `params` object, and an `id`. A **response** echoes that `id` and carries either a `result` or an `error`. A **notification** is a request with *no* `id` (fire-and-forget; no response expected). MCP rides entirely on JSON-RPC 2.0. Every method we discuss — `prompts/list`, `prompts/get` — is a JSON-RPC method name.

### 1.3 When you reach for it

Reach for a prompt when **a human should deliberately trigger a curated, repeatable interaction** that your server knows how to compose. Signals:

- The interaction is a *workflow* ("summarize, then propose fixes, then draft a message"), not a single data fetch or a single side-effecting action.
- You want the user *in the loop* choosing it — slash-command UX, a button, a command palette entry.
- You want to standardize phrasing/quality (the server author is the expert on "the right way to ask").
- It benefits from **arguments** (parameterized over a PR number, a file path, a tone, a target language).
- It may need to **inject live context** (embed a resource — a file, a log, a record) into the messages.

### 1.4 The one-paragraph mental model

> A prompt is a **server-published, named, argument-taking function whose return value is a chat-message array**. The user picks it from a menu; the host calls `prompts/get(name, args)`; the server renders and returns `messages[]`; the host injects those messages into the LLM conversation. It is user-initiated by design, which is what distinguishes it from tools (model-initiated) and resources (app-initiated).

### 1.5 Where it sits in the MCP architecture

```
            ┌────────────────────────────────────────────┐
            │                 HOST                         │
            │   (e.g. Claude Desktop, IDE, Cowork)         │
            │   ┌──────────────────────────────────────┐  │
            │   │  one CLIENT per connected server      │  │
            │   └──────────────┬───────────────────────┘  │
            └──────────────────┼──────────────────────────┘
                               │  JSON-RPC 2.0 over a transport
                               │  (stdio  |  Streamable HTTP)
            ┌──────────────────▼──────────────────────────┐
            │                 SERVER                        │
            │   exposes:  Tools | Resources | PROMPTS       │
            │                              └── this chapter │
            └──────────────────────────────────────────────┘
```

- **Host:** the user-facing LLM application. It owns the model, the conversation, and the UI. *The host is what renders the slash-command menu of prompts.*
- **Client:** the protocol-speaking connector embedded in the host. **One client per server connection** (1:1). The client is what actually sends `prompts/list` and `prompts/get`.
- **Server:** the program exposing capabilities. Prompts are a *server* primitive.

> **Term: stdio vs Streamable HTTP transport.** A *transport* is how JSON-RPC bytes move between client and server. **stdio**: the host launches the server as a child process and they talk over standard input/standard output — ideal for local servers. **Streamable HTTP** (the current remote transport, which superseded the older "HTTP+SSE" transport in protocol revision 2025-03-26): the server is an HTTP endpoint; requests are HTTP POSTs and the server may stream responses via Server-Sent Events (SSE). Prompts work identically over either transport — the primitive is transport-agnostic.

---

## 2. Foundations from first principles

We build the concept from zero. If you already know JSON-RPC and MCP's handshake, skim to §2.5.

### 2.1 Messages, roles, and content (the LLM substrate)

Modern chat LLMs consume a **conversation**: an ordered list of messages, each tagged with a **role**. The standard roles are `system` (instructions/persona), `user` (the human's turns), and `assistant` (the model's turns). MCP prompts deal in **`user`** and **`assistant`** roles only. (There is deliberately *no* `system` role in a prompt message — more on why in §7.4.)

Each message carries **content**. In MCP, content is a typed object, not a bare string. The content types relevant to prompts are:

- **Text content** — `{ "type": "text", "text": "..." }`.
- **Image content** — `{ "type": "image", "data": "<base64>", "mimeType": "image/png" }`.
- **Audio content** — `{ "type": "audio", "data": "<base64>", "mimeType": "audio/wav" }` (added in protocol revision 2025-03-26).
- **Embedded resource** — `{ "type": "resource", "resource": { ... } }`, which inlines a Resource (text or blob) directly into the prompt. This is how prompts pull live context in (see §2.6 and §3.6).

So the *output* of rendering a prompt is structurally: an array of `{ role, content }` where `content` is one of the typed objects above.

### 2.2 What "primitive" means here

A **primitive** is one of MCP's first-class capability categories. The full set:

| Side | Primitive | Controlled by | One-liner |
|---|---|---|---|
| Server | **Tools** | the **model** | Functions the model can call to *do* things (side effects, fetches). |
| Server | **Resources** | the **application** | Read-only data the app can attach as context (files, records). |
| Server | **Prompts** | the **user** | Templated, parameterized interactions a user invokes from a menu. |
| Client | **Sampling** | the **server** (asks), user approves | Server requests the host's LLM to generate a completion. |
| Client | **Roots** | the **user/app** | Filesystem/URI boundaries the client exposes to the server. |
| Client | **Elicitation** | the **server** (asks user) | Server requests structured input from the user mid-flow (added 2025-06-18). |

The **control axis** (who initiates) is the load-bearing idea. Memorize the server triad:

- **Tools → model-controlled.** The model decides, autonomously and in-loop, to invoke a tool.
- **Resources → application-controlled.** The host app decides what data to pull in and attach (it may show the user a picker, but the *mechanism* is app-driven).
- **Prompts → user-controlled.** A human deliberately selects the prompt. The model never "calls a prompt." The app surfaces them but does not auto-run them.

### 2.3 Capability negotiation: how prompts get "switched on"

MCP connections begin with an **initialize** handshake in which each side declares **capabilities**. A server that offers prompts advertises a `prompts` capability:

```json
{
  "capabilities": {
    "prompts": {
      "listChanged": true
    }
  }
}
```

- The mere presence of the `prompts` key tells the client: *this server has prompts; you may call `prompts/list` and `prompts/get`.*
- The `listChanged: true` sub-capability tells the client: *I can emit a `notifications/prompts/list_changed` notification when my set of prompts changes; you should re-list when you receive it.*

If a server does **not** declare `prompts`, a well-behaved client must not call the prompt methods. This is **negotiated capability discovery** — neither side assumes; both declare.

> **Term: capability negotiation.** A handshake step where peers announce which optional features they support so the other side only uses what's actually available. It keeps the protocol extensible and backward-compatible: old clients simply ignore capabilities they don't understand.

### 2.4 The two methods (the entire prompt API surface, from the client's view)

There are exactly **two** request methods the client uses for prompts, plus **one** notification the server may send:

1. **`prompts/list`** — "What prompts do you have?" Returns metadata (names, titles, descriptions, argument specs). Supports pagination.
2. **`prompts/get`** — "Render prompt X with these arguments." Returns the message array.
3. **`notifications/prompts/list_changed`** (server→client notification) — "My prompt list changed; re-list."

That's it. The minimalism is intentional: list to discover, get to render. Everything else (arguments, embedded resources, multi-message outputs) is expressed in the *shape* of the data those two methods exchange.

### 2.5 The lifecycle in five beats

1. **Initialize** — client and server handshake; server declares `prompts` capability.
2. **Discover** — client calls `prompts/list`; caches the result; renders a menu.
3. **(Optional) Autocomplete** — as the user types argument values, the client may call `completion/complete` to get suggestions for an argument.
4. **Invoke** — user selects a prompt and supplies arguments; client calls `prompts/get`.
5. **Inject** — client takes the returned `messages[]` and seeds the LLM conversation; the model proceeds.
6. **(If list_changed)** — server later notifies; client re-discovers.

### 2.6 Embedding resources — the "live context" superpower

A prompt's messages can contain **embedded resources**. Instead of the server pasting a stale snapshot of a file into a text message, it can return a `resource` content block whose `resource` is the actual current data (with a `uri`, `mimeType`, and `text` or `blob`). This means a single `summarize_file` prompt can, at render time, fetch the *current* contents of the referenced file and embed them — so the rendered prompt is always fresh. We dissect the wire shape in §3.6.

---

## 3. How it works internally

This is the heart of the chapter. We trace control flow, data flow, lifecycle, and state, then look at each method's internal handling and the embedded-resource path.

### 3.1 End-to-end control flow (the happy path)

```
USER                HOST/CLIENT                         SERVER
 │                      │                                 │
 │ (open app)           │ initialize ───────────────────▶│
 │                      │◀────────── result(capabilities) │   server: {prompts:{listChanged:true}}
 │                      │ notifications/initialized ─────▶│
 │                      │                                 │
 │                      │ prompts/list ─────────────────▶│
 │                      │◀──── result {prompts:[...]}     │   metadata only
 │                      │ (build slash-command menu)      │
 │                      │                                 │
 │ type "/summa…"       │ completion/complete ──────────▶│   (optional, per arg)
 │                      │◀──── {completion:{values:[…]}}  │
 │                      │                                 │
 │ pick "summarize_pr"  │                                 │
 │ enter args           │                                 │
 │                      │ prompts/get(name,args) ───────▶│
 │                      │                                 │   server: validate args,
 │                      │                                 │           fetch context,
 │                      │                                 │           render messages,
 │                      │                                 │           maybe embed resource
 │                      │◀──── result {messages:[...]}    │
 │                      │ inject messages into convo      │
 │                      │ send to LLM ──▶ model           │
 │◀── model response    │                                 │
```

Two facts to anchor on:
- **`prompts/list` returns metadata, never rendered content.** Rendering happens only in `prompts/get`. This separation keeps listing cheap and lets rendering be expensive (it may hit a DB, read files, call other services).
- **The model is never in the loop for *selecting* a prompt.** Selection is a human/UI act. The model only sees the *result* of injection.

### 3.2 Data flow & the exact wire shapes

#### `prompts/list` request

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "prompts/list",
  "params": { "cursor": "optional-opaque-pagination-cursor" }
}
```

#### `prompts/list` response

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "prompts": [
      {
        "name": "summarize_pr",
        "title": "Summarize Pull Request",
        "description": "Produce a reviewer-friendly summary of a PR.",
        "arguments": [
          { "name": "repo",      "description": "owner/name",         "required": true  },
          { "name": "pr_number", "description": "PR number",          "required": true  },
          { "name": "tone",      "description": "concise | detailed", "required": false }
        ]
      }
    ],
    "nextCursor": "optional-cursor-if-more-pages"
  }
}
```

Key points:
- `arguments` is a list of **argument definitions**, each with `name`, optional `description`, and a `required` boolean. **Arguments are always strings on the wire** (more in §3.5).
- `title` is the human display name; `name` is the programmatic id. Per the 2025-06-18 spec, objects can also carry a `_meta` field and some carry `title` as the preferred display field, falling back to `name`.

#### `prompts/get` request

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "prompts/get",
  "params": {
    "name": "summarize_pr",
    "arguments": { "repo": "acme/widgets", "pr_number": "1421", "tone": "concise" }
  }
}
```

#### `prompts/get` response

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "description": "Summary prompt for acme/widgets#1421",
    "messages": [
      {
        "role": "user",
        "content": {
          "type": "text",
          "text": "You are reviewing PR #1421 in acme/widgets. Summarize it concisely."
        }
      },
      {
        "role": "user",
        "content": {
          "type": "resource",
          "resource": {
            "uri": "git://acme/widgets/pull/1421.diff",
            "mimeType": "text/x-diff",
            "text": "diff --git a/... (the live diff text) ..."
          }
        }
      }
    ]
  }
}
```

The response `result` has an optional top-level `description` and the required `messages[]`. Each message is `{ role, content }`. Note the **second message embeds a resource** — the live diff — rather than the server pasting it into a text blob.

### 3.3 `prompts/list` internals (server side, step by step)

1. **Receive** the JSON-RPC request; check the `prompts` capability was negotiated (defensive).
2. **Authorize/scope** (if applicable): some servers vary the visible prompt set by user identity, project root, or feature flags.
3. **Enumerate** the registered prompts. In an SDK, this is usually an in-memory registry populated at startup by decorators/registrations.
4. **Paginate**: if a `cursor` was provided, resume from it; otherwise start at the first page. Slice the page; compute `nextCursor` if more remain.
5. **Project to metadata**: emit only `name`, `title`, `description`, and `arguments[]` definitions — **never** rendered messages.
6. **Respond** with `{ prompts, nextCursor? }`.

> **Term: pagination cursor.** An opaque token the server returns (`nextCursor`) that the client passes back (`cursor`) to fetch the next page. "Opaque" means the client must treat it as a meaningless string — only the server understands it (it might encode an offset, a key, or a timestamp). It lets a server with thousands of prompts avoid sending them all at once.

### 3.4 `prompts/get` internals (server side, step by step)

1. **Receive** request; extract `name` and `arguments`.
2. **Resolve** the prompt by `name`. If unknown → return a JSON-RPC error (typically `-32602 Invalid params` with a message like "Unknown prompt").
3. **Validate arguments**: ensure every `required` argument is present; reject unexpected types. Missing required args → `-32602`.
4. **Coerce/parse**: arguments arrive as strings; the handler parses them (`pr_number` → int, `tone` → enum) and applies defaults for optional args.
5. **Gather context** (the expensive part): the handler may read files, query a DB, call an API, or read another MCP resource to build the body. *This is where embedded resources are fetched.*
6. **Render messages**: build the `messages[]` array — interleave text, embed resources, set roles. The server can produce **multiple messages** and **multiple roles** (e.g. a few-shot pattern: `user` example, `assistant` example, `user` real query).
7. **Respond** with `{ description?, messages }`.

State note: `prompts/get` is conceptually **stateless and idempotent at the protocol layer** — calling it twice with the same args yields the same shape. But because step 5 can read mutable external state (a file that changed), the *content* may differ run to run. That's a feature (freshness), not a violation.

### 3.5 Arguments: typing, validation, and autocompletion

- **All argument values are transmitted as strings.** The argument *definitions* in `prompts/list` do not carry rich JSON-schema types (unlike Tool input schemas). If you need an integer or enum, you validate/parse it server-side.
- **`required`** defaults to `false`. Omitting an optional argument is legal; the server applies its own default.
- **Autocompletion** is offered via a *separate* capability and method: if the server declares `completions`, the client can call **`completion/complete`** with a reference to the prompt and the argument being typed, and the server returns up to **100** suggested values (the response also carries `total` and `hasMore`). This is how a slash-command UI offers "tab-complete" for, say, a `language` argument.

`completion/complete` request shape (abridged):

```json
{
  "jsonrpc": "2.0", "id": 9, "method": "completion/complete",
  "params": {
    "ref": { "type": "ref/prompt", "name": "translate" },
    "argument": { "name": "target_lang", "value": "ja" }
  }
}
```

Response:

```json
{
  "jsonrpc": "2.0", "id": 9,
  "result": { "completion": { "values": ["japanese"], "total": 1, "hasMore": false } }
}
```

> **Term: `ref/prompt` vs `ref/resource`.** The `completion/complete` method serves both prompts and resource templates. The `ref` object's `type` discriminates: `ref/prompt` (with a `name`) targets a prompt argument; `ref/resource` (with a `uri`) targets a resource-template variable. We only care about `ref/prompt` here.

### 3.6 Embedded resources: the wire path in detail

When a prompt embeds a resource, the content block is:

```json
{
  "type": "resource",
  "resource": {
    "uri": "file:///srv/logs/app-2026-06-25.log",
    "mimeType": "text/plain",
    "text": "...the actual current log text..."
  }
}
```

For binary data the resource carries `blob` (base64) instead of `text`:

```json
{
  "type": "resource",
  "resource": {
    "uri": "screenshot://session/42",
    "mimeType": "image/png",
    "blob": "iVBORw0KGgoAAAANSUh..."
  }
}
```

Internally, at render time, the server **reads the resource by its own resource layer** (or any data source) and inlines it. Two design consequences:

1. **Freshness:** the embedded content is captured at `prompts/get` time, so it's as current as the read. A `summarize_log` prompt always embeds *today's* log.
2. **Provenance:** because the embed carries a `uri` and `mimeType`, the host can render it as an attributed, typed attachment (e.g. show a file chip), and downstream tooling knows where it came from.

### 3.7 The `listChanged` notification flow

When the server's set of prompts changes at runtime (e.g. a plugin loads, a feature flag flips, a new project is opened), and the server declared `prompts.listChanged: true`, it sends:

```json
{ "jsonrpc": "2.0", "method": "notifications/prompts/list_changed" }
```

(No `id` — it's a notification.) The client's contract: on receipt, **invalidate its cached prompt list and re-issue `prompts/list`**. This keeps the slash-command menu live without polling.

### 3.8 State machine (prompt subsystem)

```
        ┌──────────────┐  initialize (server lacks `prompts` cap)
        │  NO PROMPTS  │◀──────────────────────────────────────────┐
        └──────────────┘                                           │
                                                                   │
  initialize (prompts cap present)                                 │
        │                                                          │
        ▼                                                          │
   ┌──────────┐ prompts/list ┌────────────┐ prompts/get ┌──────────────┐
   │ DISCOVERY│─────────────▶│  LISTED    │────────────▶│   RENDERED    │
   │ (no list)│◀─────────────│ (menu live)│             │ (messages in) │
   └──────────┘  re-list on  └────────────┘             └──────────────┘
        ▲        list_changed       ▲                          │
        │                           └──────────────────────────┘
        │                              user invokes another / again
        └─ connection closed / server gone (NO PROMPTS)
```

---

## 4. The complete toolkit

This section enumerates the wire methods, the data types, the SDK surfaces (Python and TypeScript, the two reference SDKs; Java SDK noted where it differs), and the relevant config/flags.

### 4.1 Wire methods & notifications

| Method / Notification | Direction | Purpose | Key params | Returns |
|---|---|---|---|---|
| `prompts/list` | client→server (request) | Discover available prompts (metadata) | `cursor?` | `{ prompts: Prompt[], nextCursor? }` |
| `prompts/get` | client→server (request) | Render a prompt to messages | `name` (req), `arguments?` (string map) | `{ description?, messages: PromptMessage[] }` |
| `completion/complete` | client→server (request) | Autocomplete an argument value | `ref:{type:"ref/prompt",name}`, `argument:{name,value}` | `{ completion:{ values[], total?, hasMore? } }` |
| `notifications/prompts/list_changed` | server→client (notification) | Signal the prompt set changed | — | (no response) |

### 4.2 Capability declarations

| Capability key | Declared by | Meaning |
|---|---|---|
| `prompts` | server | Server offers prompts; client may call `prompts/list` / `prompts/get`. |
| `prompts.listChanged` | server | Server will emit `notifications/prompts/list_changed`. |
| `completions` | server | Server supports `completion/complete` (enables argument autocomplete). |

### 4.3 Core data types

#### `Prompt` (a listing entry)

| Field | Type | Req | Notes |
|---|---|---|---|
| `name` | string | yes | Stable programmatic identifier (slash-command id). |
| `title` | string | no | Human display name (preferred over `name` for UI). |
| `description` | string | no | What the prompt does; shown in menus. |
| `arguments` | `PromptArgument[]` | no | Argument definitions. |
| `_meta` | object | no | Implementation-defined metadata (spec 2025-06-18). |

#### `PromptArgument`

| Field | Type | Req | Notes |
|---|---|---|---|
| `name` | string | yes | Argument key. |
| `description` | string | no | Help text; also drives autocomplete UX. |
| `required` | boolean | no | Defaults to `false`. |
| `title` | string | no | Display label (later spec revisions). |

#### `PromptMessage`

| Field | Type | Req | Notes |
|---|---|---|---|
| `role` | `"user"` \| `"assistant"` | yes | No `system` role. |
| `content` | `TextContent` \| `ImageContent` \| `AudioContent` \| `EmbeddedResource` | yes | One typed content block per message. |

#### Content blocks

| Type | Discriminator | Payload fields |
|---|---|---|
| Text | `"text"` | `text` |
| Image | `"image"` | `data` (base64), `mimeType` |
| Audio | `"audio"` | `data` (base64), `mimeType` |
| Embedded resource | `"resource"` | `resource: { uri, mimeType?, text? | blob? }` |

### 4.4 Result envelopes

| Result | Fields |
|---|---|
| `ListPromptsResult` | `prompts: Prompt[]`, `nextCursor?: string` |
| `GetPromptResult` | `description?: string`, `messages: PromptMessage[]` |
| `CompleteResult` | `completion: { values: string[], total?: number, hasMore?: boolean }` |

### 4.5 SDK surfaces (high-level)

| Task | Python SDK (`mcp`, FastMCP) | TypeScript SDK | Java SDK (spec-aligned) |
|---|---|---|---|
| Define a prompt | `@mcp.prompt()` decorator on a function | `server.registerPrompt(name, config, handler)` | `McpServerFeatures.SyncPromptSpecification` / async variant |
| Declare arguments | function parameters (typed) | `argsSchema` (zod) in config | `Prompt` + `PromptArgument` list |
| Return messages | return `str`, `list[Message]`, or `PromptMessage` objects | return `{ messages: [...] }` | return `GetPromptResult` |
| Embed a resource | return content with an embedded resource type | content block `{type:"resource", resource}` | `EmbeddedResource` content |
| Notify list changed | server framework emits when registry mutates | `server.sendPromptListChanged()` | server notification API |
| Autocomplete | `@mcp.completion()` / completion handler | `completable()` field wrapper | completion handler |

> **Term: FastMCP.** The ergonomic, decorator-driven layer in the official Python MCP SDK (and a related standalone project). It lets you declare tools/resources/prompts with Python decorators instead of hand-writing JSON-RPC handlers. When you see `@mcp.prompt()`, that's FastMCP.

### 4.6 Tooling for development & inspection

| Tool | What it does for prompts |
|---|---|
| **MCP Inspector** (`npx @modelcontextprotocol/inspector`) | Web UI to connect to a server, browse the prompt list, fill arguments, and view the rendered `messages[]`. The fastest way to verify a prompt manually. |
| Host apps (Claude Desktop, IDEs, Cowork) | Surface prompts as slash-commands / command-palette entries. |
| SDK test harnesses | In-process client to call `list_prompts`/`get_prompt` in unit tests. |
| Transport debuggers | For stdio: inspect the JSON lines; for HTTP: a proxy to inspect POST bodies and SSE streams. |

---

## 5. Code examples by use case

All examples are complete enough to adapt. We default to the **Python SDK (FastMCP)** for server brevity since it's the most concise reference implementation, and provide **TypeScript** and **Java/JVM** where the reader profile calls for it. Every example covers a *different* scenario.

### 5.1 Use case A — A simple parameterized prompt (code review)

The "hello world": a `code_review` prompt that takes code and a language and returns a single user message.

**Python (FastMCP):**

```python
# server.py
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.prompts import base  # message helpers

mcp = FastMCP("dev-helper")

@mcp.prompt(title="Code Review")
def code_review(code: str, language: str = "python") -> list[base.Message]:
    """Ask the model to review a snippet and suggest improvements."""
    # The function parameters BECOME the prompt's arguments automatically.
    # `code` is required (no default); `language` is optional (has a default).
    instruction = (
        f"You are a senior {language} reviewer. Review the code below. "
        "Call out bugs, security issues, and idiomatic improvements. "
        "Be specific and cite line numbers.\n\n"
        f"```{language}\n{code}\n```"
    )
    # Returning a single user message. FastMCP wraps the string for us.
    return [base.UserMessage(instruction)]

if __name__ == "__main__":
    mcp.run()  # defaults to stdio transport
```

What to notice:
- The **function signature defines the arguments.** `code` → required; `language` → optional with default. FastMCP introspects this to build the `arguments[]` you saw in `prompts/list`.
- Returning a list of messages with explicit roles. `base.UserMessage(...)` produces `{role:"user", content:{type:"text", text:...}}`.

### 5.2 Use case B — Multi-message / few-shot prompt (SQL generation)

Prompts can return **several messages with alternating roles** to seed few-shot context. Here a `nl_to_sql` prompt embeds an example exchange before the real request.

```python
@mcp.prompt(title="Natural Language → SQL")
def nl_to_sql(question: str, dialect: str = "postgresql") -> list[base.Message]:
    """Few-shot prompt: teach the model the house style, then ask the real question."""
    return [
        base.UserMessage(
            f"Translate English to a single {dialect} query. "
            "Return ONLY SQL, no prose."
        ),
        # A worked example as a user/assistant pair (few-shot):
        base.UserMessage("How many users signed up last week?"),
        base.AssistantMessage(
            "SELECT count(*) FROM users "
            "WHERE created_at >= now() - interval '7 days';"
        ),
        # The real question:
        base.UserMessage(question),
    ]
```

This demonstrates the **assistant role inside a prompt** — legal and useful for few-shot/style priming. The host injects all four messages, so the model "sees" the example before answering.

> **Term: few-shot prompting.** Including a handful of input→output examples in the prompt so the model imitates the demonstrated pattern (as opposed to "zero-shot," where you give only the instruction). Prompts are an ideal place to standardize few-shot examples server-side.

### 5.3 Use case C — Embedding a live resource (log triage)

A `triage_log` prompt that embeds the *current* contents of a log file as an embedded resource, so the model always reasons over fresh data.

```python
from pathlib import Path
from mcp.server.fastmcp.prompts import base
from mcp.types import EmbeddedResource, TextResourceContents, TextContent
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("ops-helper")

@mcp.prompt(title="Triage Log")
def triage_log(service: str, lines: str = "200") -> list[base.Message]:
    """Embed the tail of a service log and ask the model to triage."""
    n = int(lines)                              # args arrive as strings; parse here
    log_path = Path(f"/var/log/{service}.log")
    text = "\n".join(log_path.read_text().splitlines()[-n:])  # live tail at render time

    # Build the embedded-resource content block by hand for full control.
    embedded = EmbeddedResource(
        type="resource",
        resource=TextResourceContents(
            uri=f"file://{log_path}",
            mimeType="text/plain",
            text=text,
        ),
    )
    return [
        base.Message(role="user", content=TextContent(
            type="text",
            text=f"Triage the last {n} lines of {service}. "
                 "Identify the root cause and the first remediation step.")),
        base.Message(role="user", content=embedded),  # the live log, embedded
    ]
```

Why this matters: the file is read **at `prompts/get` time**, so each invocation embeds the newest log lines. The host can render the embed as an attributed file chip because it carries `uri` and `mimeType`.

### 5.4 Use case D — TypeScript server with argument autocomplete

The TS SDK, with a `translate` prompt whose `target_lang` argument autocompletes.

```ts
// server.ts
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { completable } from "@modelcontextprotocol/sdk/server/completable.js";
import { z } from "zod";

const server = new McpServer({ name: "i18n", version: "1.0.0" });

const LANGS = ["japanese", "german", "spanish", "french", "hindi"];

server.registerPrompt(
  "translate",
  {
    title: "Translate Text",
    description: "Translate text into a target language.",
    argsSchema: {
      text: z.string(),
      // `completable` attaches an autocomplete callback to this argument.
      target_lang: completable(z.string(), (value) =>
        LANGS.filter((l) => l.startsWith(value.toLowerCase()))
      ),
    },
  },
  async ({ text, target_lang }) => ({
    messages: [
      {
        role: "user",
        content: {
          type: "text",
          text: `Translate the following into ${target_lang}. Preserve tone.\n\n${text}`,
        },
      },
    ],
  })
);

const transport = new StdioServerTransport();
await server.connect(transport);
```

Notes:
- `argsSchema` uses **zod** to declare and validate argument shapes; `completable(...)` wires the autocomplete callback that backs `completion/complete`.
- The handler returns `{ messages: [...] }` directly — the TS SDK does not wrap strings for you.

> **Term: zod.** A TypeScript-first schema declaration and validation library. The TS MCP SDK uses it so your handler receives parsed, validated arguments and the SDK can generate the wire-level argument metadata.

### 5.5 Use case E — A Java/JVM server defining a prompt

The reader is a JVM engineer, so here's the official **MCP Java SDK** style (sync server). The Java SDK mirrors the spec types closely (`Prompt`, `PromptArgument`, `GetPromptResult`, `PromptMessage`).

```java
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.List;
import java.util.Map;

public class PromptServer {

  public static void main(String[] args) {
    // 1) Describe the prompt's metadata (this is what prompts/list returns).
    Prompt summarize = new Prompt(
        "summarize_ticket",                       // name
        "Summarize Ticket",                       // title
        "Summarize a Jira ticket for standup.",   // description
        List.of(
            new PromptArgument("ticket_id", "e.g. PROJ-123", true),  // required
            new PromptArgument("audience",  "eng | exec",   false)   // optional
        ));

    // 2) The handler renders messages when prompts/get is called.
    SyncPromptSpecification spec = new SyncPromptSpecification(
        summarize,
        (exchange, request) -> {
          Map<String, Object> a = request.arguments();
          String ticketId = (String) a.get("ticket_id");           // args are strings
          String audience = (String) a.getOrDefault("audience", "eng");

          String body = fetchTicketSummaryContext(ticketId);       // your data layer

          PromptMessage msg = new PromptMessage(
              Role.USER,
              new TextContent(
                  "Summarize ticket " + ticketId + " for a " + audience
                      + " audience in 3 bullets.\n\n" + body));

          return new GetPromptResult(
              "Summary for " + ticketId,            // optional description
              List.of(msg));                        // messages[]
        });

    // 3) Build and start the server with the prompts capability enabled.
    McpServer.sync(new StdioServerTransportProvider())
        .serverInfo("jira-helper", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .prompts(true)          // declare the `prompts` capability (+listChanged)
            .build())
        .prompts(spec)              // register our prompt
        .build();
  }

  static String fetchTicketSummaryContext(String id) {
    // Pretend we hit Jira's REST API here and return relevant fields.
    return "Title: ...\nStatus: In Progress\nRecent comments: ...";
  }
}
```

JVM-specific notes:
- The Java SDK separates the **descriptor** (`Prompt`) from the **handler** (`SyncPromptSpecification`), matching the wire split between `prompts/list` (metadata) and `prompts/get` (render).
- `request.arguments()` is a `Map<String,Object>`; values arrive as strings. Parse/validate yourself.
- `.capabilities(... .prompts(true) ...)` is what flips the `prompts` capability on during the handshake. There is also an **async** variant (`McpServer.async(...)` with `AsyncPromptSpecification` returning `Mono<GetPromptResult>`) for reactive stacks (Project Reactor). Spring AI provides an `spring-ai-mcp` integration layer on top of this SDK if you're in a Spring Boot app.

> **Term: Project Reactor / `Mono`.** Reactor is a reactive-streams library for the JVM; a `Mono<T>` represents an async computation yielding 0 or 1 value. The MCP Java *async* server returns `Mono<GetPromptResult>` so handlers can do non-blocking I/O (DB, HTTP) while rendering a prompt.

### 5.6 Use case F — Client side: discovering and getting prompts (Python)

How a *host/client* uses the two methods. This is what your slash-command UI calls underneath.

```python
import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def main():
    params = StdioServerParameters(command="python", args=["server.py"])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()                  # handshake + capability exchange

            # 1) Discover prompts (build the menu).
            listing = await session.list_prompts()
            for p in listing.prompts:
                print(p.name, "-", p.description, "args:", [a.name for a in (p.arguments or [])])

            # 2) Render a chosen prompt with arguments.
            result = await session.get_prompt(
                "code_review",
                arguments={"code": "def f(x): return x/0", "language": "python"},
            )
            for m in result.messages:                   # inject these into the LLM convo
                print(m.role, "->", m.content)

asyncio.run(main())
```

This closes the loop: the server defines (5.1–5.5), the client lists and gets (5.6), and a real host would take `result.messages` and start an LLM turn with them.

### 5.7 Use case G — `list_changed`: a server that adds prompts at runtime

```python
# Pseudocode-ish: when a plugin loads, register a new prompt and notify clients.
def on_plugin_loaded(plugin):
    mcp.add_prompt(plugin.build_prompt())        # mutate the registry
    # FastMCP emits notifications/prompts/list_changed automatically when the
    # registry changes (server must have declared prompts.listChanged=true).
```

The client, having registered a handler, re-calls `list_prompts()` on receipt and refreshes the menu — no polling.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Keep `prompts/list` cheap and pure.** It is called on connect and on every `list_changed`. Do not do I/O per prompt at list time; precompute metadata. If you have many prompts, **paginate** with `nextCursor`.
- **Render lazily in `prompts/get`.** All expensive work (DB reads, file reads, API calls, embedding resources) belongs in `get`, not `list`. Even so, watch latency — a slow render delays the user's command.
- **Bound embedded-resource size.** Embedding a 50 MB log into a prompt will blow the model's context window *and* bloat the JSON-RPC payload. Tail/sample/summarize before embedding (see §6.7 anti-patterns).
- **Cache where safe.** If rendering hits a slow upstream and the data changes infrequently, cache the rendered body with a short TTL — but never cache *across users* if the content is user-scoped.

### 6.2 Correctness & concurrency

- **Validate every argument server-side.** Required-arg presence, type coercion (string→int/enum), and bounds. Return `-32602 Invalid params` on violation, with a helpful message.
- **Treat `prompts/get` as effectively read-only.** Prompts should not cause side effects — that's a Tool's job. If selecting a prompt mutates state, you've conflated primitives and you'll surprise users (who think they're "just composing a message").
- **Concurrency:** multiple `prompts/get` calls may be in flight. Keep handlers reentrant and thread-safe; don't share mutable rendering buffers. On the JVM async path, avoid blocking calls inside reactive handlers (offload to a bounded scheduler).
- **Determinism vs freshness:** decide deliberately. A "few-shot" prompt should be deterministic; a "triage today's log" prompt is intentionally non-deterministic across time. Document which.

### 6.3 Security

- **Prompt injection via embedded content.** When you embed a resource (a file, a record, a web page) you are inserting *untrusted text* into the model's instructions. That text may contain adversarial "ignore previous instructions" payloads. Mitigations: clearly delimit embedded content (fenced/tagged), keep authoritative instructions in the *server-authored* text messages, and never grant the model authority to act on embedded text without user confirmation (tools should require approval).
- **Argument injection.** Arguments are user/host-supplied strings; treat them as untrusted. If an argument becomes a file path or a DB identifier, **validate/whitelist** it — a `service` argument of `../../etc/passwd` must be rejected (path traversal). The §5.3 example would need such guarding in production.
- **Scope/authorization.** If prompts expose different data per tenant, enforce authorization at render time, not just at list time. Don't rely on "we hid it from the menu" — a client can call `prompts/get` with any name.
- **Don't leak secrets in rendered messages.** Embedding a config file that contains credentials puts them in the model context (and possibly in logs/telemetry).
- **Transport auth for remote servers.** Over Streamable HTTP, protect the endpoint (the spec's authorization framework uses OAuth 2.1-style bearer tokens in recent revisions). Prompts inherit whatever auth the transport enforces.

### 6.4 Observability

- **Log both methods with the prompt `name` and (sanitized) argument keys** — but be careful logging argument *values* and rendered bodies (PII, secrets, prompt-injection payloads). Prefer logging shapes/sizes.
- **Emit metrics:** `prompts/get` latency, error rate by prompt name, embedded-resource bytes, render cache hit ratio.
- **Trace the render path** if it fans out to DBs/APIs; a slow `summarize_pr` is usually a slow upstream, and you want the span.

### 6.5 Cost

- Embedded resources directly drive **token cost** at inference time (the host pays to send those tokens to the model). A bloated prompt is a recurring bill, not a one-time one — every invocation re-sends it.
- Autocomplete (`completion/complete`) can be chatty (one call per keystroke if naively wired); debounce on the client and cap results (the spec caps at 100 values per response anyway).

### 6.6 Testing

- **Unit-test renders:** call `get_prompt(name, args)` in-process and assert on the `messages[]` (roles, content types, that required text appears, that the embedded resource has the right `uri`/`mimeType`).
- **Argument matrix:** test required-missing → error, optional-defaulting, and bad coercions.
- **Golden snapshots** for stable prompts (few-shot) so phrasing changes are reviewed.
- **Inspector smoke test** in CI is great for manual verification but automate the SDK-level test for regressions.

### 6.7 Anti-patterns

| Anti-pattern | Why it's bad | Do instead |
|---|---|---|
| Using a **prompt** to perform an action (write a file, send mail) | Conflates control axes; users expect prompts to be inert message templates | Use a **Tool** for side effects |
| Doing heavy I/O in **`prompts/list`** | Slows connect and every re-list | Move work to `prompts/get` |
| Embedding **huge/unbounded** resources | Context-window overflow, cost, latency | Tail/sample/summarize; bound bytes |
| Putting authoritative instructions **only** inside embedded (untrusted) content | Prompt-injection surface | Keep instructions in server-authored text; delimit embeds |
| Rich-typing arguments and assuming the wire enforces it | Args are **strings**; no JSON-schema validation like Tools | Validate/parse server-side |
| Caching user-scoped renders **globally** | Cross-tenant data leak | Key cache by identity; or don't cache |
| Treating `name` as a display string | `name` is an id; UIs should prefer `title` | Set `title`; keep `name` stable |

---

## 7. Advanced topics & deep internals

### 7.1 Why no `system` role in prompt messages

Prompt messages are only `user` or `assistant`. The rationale: a prompt is a *contribution to a conversation the host owns*. The host controls the system prompt / persona; an MCP server should not be able to silently overwrite the host's system instructions (that would be a trust and safety problem and a prompt-injection vector). So servers express intent through `user`/`assistant` turns; the host decides how to integrate them with its own system message.

### 7.2 Prompts vs Tools vs Resources at the wire level (the control axis, made concrete)

The control distinction is not just philosophy — it shows up mechanically:

- A **Tool** is exposed for the **model** to call; tool-calls appear in the model's output and the host executes `tools/call`. The model *chooses*.
- A **Resource** is exposed for the **app** to attach; `resources/read` is invoked by the host's logic (or a user picker), and the data is added as context. The app *chooses*.
- A **Prompt** is exposed for the **user** to invoke; `prompts/get` is triggered by an explicit human selection, and the result *seeds* the conversation. The user *chooses*.

A subtle corollary: because prompts are user-triggered, **they are a natural place to require nothing autonomous** — there's a human gate by construction, which is why prompts are considered lower-risk than tools.

### 7.3 The interplay: prompts that reference tools/resources

A sophisticated prompt can *set up* a tool-using workflow. Example: a `refactor_module` prompt returns messages that (a) embed the current file (resource) and (b) instruct the model to *use available tools* to run tests after editing. The prompt doesn't call tools — it composes a conversation in which the model will likely choose to call tools, under the host's approval policy. This is the intended composition: **prompts orchestrate; tools execute; resources supply.**

### 7.4 Multi-modal prompts

Because content blocks include `image` and `audio` (audio since 2025-03-26), a prompt can render a `user` message containing an image (e.g. a screenshot embedded as base64) and ask the model to describe a UI bug. The same size/cost caveats apply, amplified — images are token-expensive.

### 7.5 Completion semantics & limits

- `completion/complete` returns at most **100** values; `total` may indicate the full count and `hasMore` whether the list is truncated.
- The server sees the partial `value` the user has typed plus the argument `name`, so completions are context-sensitive. (Some servers also receive a `context` of already-resolved arguments in later spec revisions, enabling dependent autocomplete: choose `repo` first, then complete `pr_number` against that repo.)
- Completion is a *separate capability* (`completions`); a server can offer prompts without offering completion.

### 7.6 Pagination subtleties

- The cursor is **opaque**; clients must not parse or fabricate it. A server may invalidate cursors on list changes — clients should be ready to restart from the first page if a cursor errors.
- There is no standardized page size; servers choose. For UX, list pages should be large enough that menus aren't fragmented.

### 7.7 Versioning & spec drift (flag the version-specific bits)

MCP spec revisions are date-stamped. Prompt-relevant changes:

- **2024-11-05** (initial): prompts, `prompts/list`, `prompts/get`, arguments, embedded resources, `list_changed`.
- **2025-03-26**: added **audio content**; replaced HTTP+SSE with **Streamable HTTP** (transport-level, but affects how prompt calls are carried remotely); authorization framework formalized.
- **2025-06-18**: tightened metadata fields (`title`, `_meta`), structured tool output (not prompts), **elicitation** added (a separate primitive), and clarifications. Always check `protocolVersion` from the handshake; behavior of edge fields can differ by revision.

> **Term: protocol revision / `protocolVersion`.** In the `initialize` handshake both sides send a `protocolVersion` (a date string). They agree on a common version; features unavailable in that version must not be used. This is how MCP evolves without breaking older peers.

### 7.8 Lesser-known behaviors

- **`prompts/get` may return a different `messages[]` for the same args over time** by design (freshness). Don't assume idempotent content.
- **An empty `arguments` map is valid** for argument-less prompts; some clients omit the key entirely.
- **`description` appears in two places** — per-prompt in the listing (what it does) and optionally in the `GetPromptResult` (a rendered, possibly arg-specific description). They serve different UX moments.
- **Title vs name fallback:** UIs should display `title` if present, else `name`. Servers that forget `title` get an uglier menu.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which primitive should this be?

| You want… | Use | Because |
|---|---|---|
| The user to deliberately trigger a curated, parameterized interaction | **Prompt** | User-controlled; renders to messages; no side effects |
| The model to autonomously perform an action / fetch | **Tool** | Model-controlled; executes; can have side effects |
| To attach read-only data as context | **Resource** | App-controlled; pure data, addressable by URI |
| The server to ask the host's LLM to generate text | **Sampling** | Client-side capability the server requests |
| The server to ask the user for structured input mid-flow | **Elicitation** | Client-side; structured Q&A (2025-06-18) |

### 8.2 "Use a prompt when… / avoid when…"

**Use a prompt when:**
- The interaction is repeatable and worth naming.
- A human should choose to run it (menu/slash-command UX).
- It benefits from arguments and/or live embedded context.
- You (the server author) are the authority on "the right way to ask."

**Avoid a prompt when:**
- It must perform side effects → that's a Tool.
- The model should decide autonomously whether/when to do it → Tool.
- It's just static data to attach → Resource.
- It needs to overwrite the host's system persona → not allowed; rethink.

### 8.3 Prompt vs hard-coded host prompt vs user copy-paste

| Approach | Discoverable | Parameterized | Server-owned/versioned | Live context | Shareable |
|---|---|---|---|---|---|
| MCP **Prompt** | Yes (`prompts/list`) | Yes (arguments) | Yes | Yes (embed) | Yes (ships with server) |
| Hard-coded in host | Only what host adds | App-dependent | No (host-owned) | App-dependent | No |
| User copy-paste | No | No | No | No | Manually |

### 8.4 stdio vs Streamable HTTP for prompt-bearing servers

| Factor | stdio | Streamable HTTP |
|---|---|---|
| Locality | Local child process | Local or remote |
| Auth | Inherited from process | Needs transport auth (OAuth 2.1-style) |
| Multi-client | One per process | Many clients, one endpoint |
| Best for | Personal/local servers | Shared/hosted servers |
| Prompt behavior | Identical | Identical (just carried over HTTP/SSE) |

### 8.5 Embed-resource vs reference-by-URI vs paste-as-text

| Strategy | Freshness | Token cost | Provenance | When |
|---|---|---|---|---|
| **Embed resource** (text/blob inline) | Fresh at render | High (full content sent) | Strong (uri+mime) | Need exact current content in-context |
| **Reference by URI only** (text message naming the resource) | Model must fetch (via a tool) | Low up front | Strong | Large data; let model pull selectively |
| **Paste as plain text** | Whatever you pasted | High | Weak (no uri/mime) | Quick/dirty; avoid in production |

---

## 9. Failure modes & debugging

### 9.1 Common failures and their signatures

| Symptom | Likely cause | Fix / diagnosis |
|---|---|---|
| Prompts don't appear in the host menu | Server didn't declare `prompts` capability, or client cached an empty list | Check `initialize` result for `capabilities.prompts`; in Inspector, confirm `prompts/list` returns entries |
| `prompts/list` empty but you registered prompts | Registration ran after server build; or wrong server instance | Ensure prompts are registered before/within server build; log registry size |
| `prompts/get` → `-32602 Invalid params` | Missing required arg, wrong name, or unparseable arg | Echo `name`+`arguments` in logs; verify required args; check arg coercion |
| Menu stale after server adds prompts | `listChanged` not declared, or client ignores notification | Declare `prompts.listChanged:true`; ensure client re-lists on `notifications/prompts/list_changed` |
| Rendered prompt huge / model truncates | Unbounded embedded resource | Cap/tail embed size; switch to URI-reference strategy |
| Autocomplete doesn't work | `completions` capability missing, or no completion handler | Declare `completions`; wire `completable`/completion handler |
| Model "obeys" malicious text in embed | Prompt injection via embedded content | Delimit embeds; keep instructions in authored text; gate tool actions on approval |
| Remote server: prompts 401/403 | Transport auth missing/expired | Check bearer token / OAuth flow on the HTTP transport |
| stdio server: nothing happens / hangs | Server wrote logs to **stdout** (corrupting JSON-RPC) | On stdio, **log to stderr only**; stdout is the protocol channel |

> **The stdout footgun (deep):** with the stdio transport, **stdout is the JSON-RPC byte stream**. Any stray `print`/`System.out.println` in a server corrupts framing and breaks every method including `prompts/list`. Always route logs to **stderr**. This is the single most common "my MCP server mysteriously doesn't work" cause.

### 9.2 Debugging toolkit & exact moves

1. **MCP Inspector** (`npx @modelcontextprotocol/inspector -- <your server cmd>`): open the Prompts tab, confirm the list, fill arguments, click to render, and *read the raw `messages[]`*. This isolates "is it the server or the host?" instantly.
2. **Raw wire trace:** for stdio, run the server manually and feed it a `prompts/list` JSON line on stdin; inspect the JSON response. For HTTP, use a proxy/curl to POST the JSON-RPC body and read the SSE/JSON response.
3. **SDK in-process test:** call `list_prompts()` / `get_prompt(...)` from a unit test to bypass the host entirely.
4. **Capability check:** print the `initialize` result; verify `capabilities.prompts` (and `.listChanged`, `completions`) are present.
5. **Argument echo:** temporarily log received `arguments` (sanitized) inside `prompts/get` to catch type/coercion mismatches.

### 9.3 Realistic incident narratives

- **"The slash-command does nothing after deploy."** A server upgraded to a new logging library that printed a banner to **stdout** on startup. Over stdio, that banner corrupted the first JSON-RPC frame; `initialize` failed, so no capabilities were exchanged and **no prompts ever listed**. Fix: redirect all logs to stderr. Lesson: on stdio, stdout is sacred.
- **"Code-review prompt returns a 30k-line message and the model ignores half of it."** A `review_module` prompt embedded an entire module *plus* its transitive imports as one resource, blowing the context window; the model silently truncated. Fix: embed only the target file, switch dependencies to URI references the model can pull on demand via a `read_file` tool. Lesson: bound embeds; prefer reference for large context.
- **"A teammate's prompt leaked another tenant's data."** A render cache keyed only by `(name, args)` served a tenant-specific summary to a different tenant who happened to pass the same args. Fix: include identity in the cache key (or disable caching for user-scoped renders). Lesson: never cache user-scoped renders globally.
- **"Prompt 'summarize report' sometimes empty."** The handler read a file that a nightly job rotated; on rotation it briefly didn't exist, and the handler embedded an empty string instead of erroring. Fix: validate the read; return a clear `-32602`/`-32603` error or a graceful message. Lesson: handle missing-context explicitly; freshness cuts both ways.

> **Term: JSON-RPC error codes.** Standard codes you'll see: `-32700` parse error, `-32600` invalid request, `-32601` method not found, `-32602` invalid params (the one you'll hit most for bad arguments), `-32603` internal error. Servers may use application-defined codes outside the reserved range too.

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. "Senior-signal" items (tradeoff/justification) are marked **★**.

**Q1. What is an MCP prompt, in one sentence?**
A server-published, named, parameterized template that, when fetched with arguments via `prompts/get`, renders into a sequence of chat messages the host injects into the LLM conversation.
- *Follow-up: Is a prompt a string?* No — its rendered form is an array of `{role, content}` messages; content can be text, image, audio, or an embedded resource.
- *Follow-up: Who triggers it?* The user, deliberately (e.g. a slash-command). Not the model.

**Q2. Name the three server primitives and who controls each.**
Tools (model-controlled), Resources (application-controlled), Prompts (user-controlled).
- *Follow-up: Why does the control axis matter?* It dictates trust and UX: model-controlled things can act autonomously (higher risk, need approval); user-controlled prompts have a human gate by construction.
- *Follow-up: Where do Sampling/Elicitation/Roots fit?* Those are client-side capabilities, not server primitives.

**Q3. What are the exact methods for prompts, and what does each return?**
`prompts/list` → `{prompts[], nextCursor?}` (metadata only); `prompts/get` → `{description?, messages[]}` (rendered). Plus the `notifications/prompts/list_changed` notification.
- *Follow-up: Why split list from get?* List stays cheap/pure for menus; get does the expensive rendering (I/O, embeds) only when invoked.
- *Follow-up: Is get idempotent?* Idempotent in shape, not necessarily in content — it can read mutable state, so output may change over time (freshness).

**Q4. How are arguments typed and validated?**
Argument definitions carry `name`, `description?`, `required?`. **Values are strings on the wire**; there's no JSON-schema enforcement like Tools, so the server must parse/validate.
- *Follow-up: How does autocomplete work?* Via the separate `completions` capability and `completion/complete`, referencing the prompt (`ref/prompt`) and the argument being typed; returns ≤100 suggestions.
- *Follow-up: How do you make an argument an enum?* Validate server-side; optionally back it with completion suggestions for UX.

**Q5. How do prompts pull in live data?**
By returning an **embedded resource** content block (`type:"resource"`) carrying the current `text`/`blob` plus `uri`/`mimeType`, read at `prompts/get` time so it's fresh.
- *Follow-up: Embed vs reference-by-URI tradeoff?* Embed = fresh, strong provenance, high token cost; reference = cheap upfront, lets the model pull selectively via tools.
- *Follow-up: Security risk of embedding?* Prompt injection — embedded untrusted text can carry adversarial instructions; delimit it and keep authoritative instructions in authored text.

**Q6. ★ A teammate wants to ship "deploy to prod" as an MCP prompt. Good idea?**
No. Deployment is a side-effecting action → it belongs in a **Tool** (model- or user-invoked with approval), not a prompt. Prompts should be inert message templates; users reasonably assume selecting one only composes a message. Shipping side effects as a prompt violates the control axis and surprises users.
- *Follow-up: What if they want a human to trigger it from a menu?* Many hosts let users invoke tools too, with explicit confirmation; or use a prompt that *guides* the model to call a deploy tool under approval — prompts orchestrate, tools execute.
- *Follow-up: What's the risk if you don't?* Accidental side effects, no approval gate, and conflated mental models that erode user trust.

**Q7. ★ When would you choose a prompt over hard-coding the interaction in the host app?**
When the domain server is the authority on the interaction and you want it discoverable, parameterized, versioned with the server, and able to embed live context — without changing the host. Hard-coding in the host doesn't scale across many servers and isn't shareable; prompts ship the curated workflow alongside the data source.
- *Follow-up: Downside of prompts vs host-coded?* You depend on host support for surfacing prompts; UX consistency varies by host.
- *Follow-up: Versioning?* Prompts version with the server; bump and emit `list_changed` so clients refresh.

**Q8. Why is there no `system` role in prompt messages?**
To prevent a server from silently overriding the host's system persona — a trust/safety and injection concern. Servers contribute `user`/`assistant` turns; the host owns the system message and decides integration.
- *Follow-up: How do you steer behavior then?* Put steering in a `user` message (or few-shot `assistant` examples) within the prompt.
- *Follow-up: Can a prompt return multiple roles?* Yes — that's exactly how few-shot priming is expressed.

**Q9. Your prompts stopped appearing after a deploy. Diagnose.**
Check the handshake: is `capabilities.prompts` present? Then check stdio stdout pollution (a stray print corrupting JSON-RPC), registration ordering, and whether the client cached an empty list and ignored `list_changed`. Use the Inspector to isolate server vs host.
- *Follow-up: The #1 stdio cause?* Logging to stdout instead of stderr.
- *Follow-up: How to confirm fast?* Inspector Prompts tab, or feed a `prompts/list` JSON line to the server manually.

**Q10. How does the client stay in sync when the server's prompt set changes?**
The server declares `prompts.listChanged:true` and emits `notifications/prompts/list_changed`; the client invalidates its cache and re-calls `prompts/list`. No polling.
- *Follow-up: If listChanged isn't declared?* The client may only refresh on reconnect; menus can go stale.
- *Follow-up: Is the notification a request?* No — it's a JSON-RPC notification (no `id`, no response).

**Q11. ★ You must embed a 40 MB log into a triage prompt. What do you do?**
Don't embed it whole — it overflows context, costs tokens every invocation, and inflates payloads. Tail/sample/summarize to a bounded slice, or expose the log as a resource/tool and embed only a pointer/excerpt, letting the model pull more on demand. Make the bound explicit and document it.
- *Follow-up: How to choose the bound?* Based on the model's context window minus headroom for instructions and the answer; measure tokens, not bytes.
- *Follow-up: Cost angle?* Embeds are re-sent every call — a recurring inference bill, not one-time.

**Q12. How would you unit-test a prompt?**
Call `get_prompt(name, args)` in-process and assert on `messages[]`: roles, content types, required text present, embedded resource `uri`/`mimeType`. Cover the argument matrix (missing-required → error, optional-default, bad coercion) and golden-snapshot stable phrasing.
- *Follow-up: Why not just use the Inspector?* Inspector is for manual verification; automate SDK-level tests for regression coverage in CI.
- *Follow-up: How to test freshness?* Mock the data source and assert the embed reflects the mocked current state.

---

## 11. Glossary

- **Argument (prompt argument):** A named parameter a prompt accepts; defined with `name`, optional `description`, and `required`. Values travel as strings.
- **Audio content:** A content block carrying base64 audio plus `mimeType` (added 2025-03-26).
- **Base64:** A text encoding of binary data using 64 ASCII characters; used to carry images/audio/blobs inside JSON.
- **Blob:** Binary resource data (carried base64) in an embedded resource, as opposed to `text`.
- **Capability negotiation:** Handshake step where peers declare supported optional features so each only uses what's available.
- **Client:** The protocol connector embedded in the host; one per server connection; issues `prompts/list`/`prompts/get`.
- **`completion/complete`:** Method that returns autocomplete suggestions for a prompt argument (or resource-template variable); capped at 100 values.
- **Content block:** A typed payload inside a message: text, image, audio, or embedded resource.
- **Control axis (who-initiates):** The principle that Tools are model-controlled, Resources app-controlled, Prompts user-controlled.
- **Cursor (pagination):** Opaque token returned as `nextCursor` and replayed as `cursor` to fetch the next page.
- **Elicitation:** A client-side primitive (2025-06-18) letting a server request structured input from the user mid-flow.
- **Embedded resource:** A content block (`type:"resource"`) inlining a resource's current `text`/`blob` plus `uri`/`mimeType` into a prompt message.
- **FastMCP:** The decorator-based ergonomic layer of the Python MCP SDK (`@mcp.prompt()`, etc.).
- **Few-shot prompting:** Including example input→output pairs in the prompt so the model imitates the pattern.
- **`GetPromptResult`:** The `prompts/get` result: `{description?, messages[]}`.
- **Host:** The user-facing LLM application that owns the model, conversation, and UI; renders the prompt menu.
- **Image content:** A content block carrying base64 image data plus `mimeType`.
- **`initialize`:** The first JSON-RPC exchange; negotiates `protocolVersion` and capabilities.
- **JSON-RPC 2.0:** The lightweight RPC protocol MCP rides on; requests have `method`/`params`/`id`, notifications omit `id`.
- **`listChanged`:** A sub-capability indicating the server will notify when its prompt list changes.
- **MCP (Model Context Protocol):** Open protocol standardizing how apps supply context/capabilities to LLMs.
- **MCP Inspector:** Official debugging UI to browse/list/get prompts (and other primitives) against a server.
- **`Mono`:** A Reactor type representing an async 0-or-1-value computation; returned by the Java async prompt handler.
- **Notification:** A JSON-RPC message with no `id`, expecting no response (e.g. `notifications/prompts/list_changed`).
- **Pagination:** Returning results in pages via cursors to bound payload size.
- **Primitive:** One of MCP's first-class capability categories (Tools, Resources, Prompts, etc.).
- **Project Reactor:** A JVM reactive-streams library used by the MCP Java async server.
- **Prompt:** A server-published, named, parameterized message template, user-invoked, rendered via `prompts/get`.
- **`PromptMessage`:** A `{role, content}` element of a rendered prompt; role is `user` or `assistant`.
- **`prompts/get`:** Method to render a named prompt with arguments into `messages[]`.
- **`prompts/list`:** Method to discover available prompts (metadata only), paginated.
- **`protocolVersion`:** Date-stamped version agreed during `initialize`; gates feature availability.
- **`ref/prompt`:** The completion reference type that targets a prompt argument.
- **Resource:** App-controlled, read-only data exposed by a server, addressable by URI; can be embedded in prompts.
- **Role:** A message's speaker tag (`user`/`assistant` in prompts; `system` is excluded).
- **Sampling:** A client-side capability the server can request: ask the host's LLM to generate a completion.
- **SSE (Server-Sent Events):** A one-way HTTP streaming mechanism used by the HTTP transport to stream responses.
- **stdio transport:** Client launches the server as a child process; they talk over stdin/stdout (stdout is the protocol channel — log to stderr).
- **Streamable HTTP:** The current remote transport (since 2025-03-26), using HTTP POST + optional SSE streaming.
- **System prompt/role:** Host-owned instruction/persona message; deliberately not expressible by MCP prompts.
- **Text content:** A content block carrying a plain string (`type:"text"`).
- **Title:** Human display name of a prompt/argument; UIs prefer it over `name`.
- **Tool:** Model-controlled function a server exposes; can have side effects; invoked via `tools/call`.
- **zod:** A TypeScript schema/validation library the TS SDK uses for argument schemas and completion.
- **`-32602`:** JSON-RPC "Invalid params" error code — the typical response to a bad/missing prompt argument.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
WHAT      Prompt = server-published, named, parameterized template
          → renders to messages[] via prompts/get.
CONTROL   Tools=model · Resources=app · PROMPTS=user (deliberate, slash-command).
METHODS   prompts/list  → {prompts[], nextCursor?}   (METADATA only, cheap, paginated)
          prompts/get   → {description?, messages[]} (RENDER here; do I/O & embeds here)
          notifications/prompts/list_changed         (re-list on receipt)
          completion/complete (ref/prompt)           (≤100 suggestions; needs `completions` cap)
CAPS      server declares  prompts{listChanged?}  and optionally  completions
ARGS      defs: {name, description?, required?(false)}; VALUES ARE STRINGS → validate server-side
MESSAGE   {role: user|assistant, content}; NO system role
CONTENT   text | image | audio | resource(embedded: uri+mimeType+ text|blob)
EMBED     resource read at get-time → FRESH; high token cost; provenance via uri/mimeType
ROLE OF $ Prompts ORCHESTRATE · Tools EXECUTE · Resources SUPPLY
NO-NOs    side effects in a prompt (→Tool) · I/O in list · unbounded embeds ·
          instructions only in untrusted embed · global cache of user-scoped renders ·
          (stdio) logging to STDOUT  ← #1 footgun
ERRORS    -32602 invalid params (bad/missing args) · -32601 method not found · -32603 internal
VERSIONS  2024-11-05 base · 2025-03-26 audio+StreamableHTTP · 2025-06-18 title/_meta/elicitation
DECIDE    user-triggered curated workflow? → Prompt. autonomous/side-effect? → Tool.
          read-only data? → Resource.
```

### 12.2 Decision rules (memorize)

1. Side effects? → **Tool**, never a Prompt.
2. Model should decide autonomously? → **Tool**.
3. Just data to attach? → **Resource**.
4. Human picks a curated, parameterized interaction? → **Prompt**.
5. Big context? → **reference by URI**, not a giant embed.
6. Args need types? → **validate server-side** (the wire gives you strings).
7. stdio? → **log to stderr**.

### 12.3 Self-test (no answers — recall actively)

1. Explain, without notes, the control axis and how it changes the UX and risk profile of each server primitive.
2. Trace the full happy-path control/data flow from app open to model response for a parameterized prompt that embeds a live file — name every method and notification involved.
3. Why are prompt argument values strings on the wire, and what are the concrete server-side responsibilities that creates? Give two failure modes if you skip them.
4. Design an embed strategy for a prompt that must reason over a 100 MB dataset within a model context window of ~200k tokens. Justify embed-vs-reference and state your bound.
5. Walk through diagnosing "my prompts vanished after deploy," listing at least four distinct causes and the exact tool/command you'd use for each.
6. Why is there no `system` role in prompt messages, and how do you achieve persona/steering instead?
7. Describe how `completion/complete` integrates with prompts, including the capability it requires, the `ref` type, and the result limits.
8. Give a worked argument-validation example (required + optional + enum coercion) and the exact JSON-RPC error you'd return on violation.
```

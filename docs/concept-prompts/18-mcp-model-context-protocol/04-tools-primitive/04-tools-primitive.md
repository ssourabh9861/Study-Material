# MCP Tools Primitive — A Definitive Engineering Reference

> **Concept area:** MCP — Model Context Protocol
> **Subtopic:** The **Tools** primitive
> **Reader:** A senior Java/JVM backend engineer who wants to master Tools end-to-end — design, internals, production operation, and interviews.

---

## 1. Overview & where it fits

### 1.1 What MCP is (one paragraph, for the newcomer)

The **Model Context Protocol (MCP)** is an open, JSON-RPC-based protocol that standardizes how an **LLM application** (called the **host**, e.g. Claude Desktop, an IDE, or your own agent) connects to external **capabilities** (data, actions, prompts) exposed by **servers**. Before MCP, every integration between an AI app and an external system (a database, a ticketing API, a filesystem) was bespoke: a custom function-calling shim per app per integration — the classic **M×N integration problem** (M apps × N tools = M×N glue layers). MCP collapses this to **M+N**: each app speaks MCP once, each tool provider implements an MCP server once, and they interoperate. MCP was introduced by **Anthropic in November 2024** and is maintained as an open specification with versioned releases (the spec is dated by revision, e.g. `2024-11-05`, `2025-03-26`, `2025-06-18`; always pin to a specific revision in production).

> **JSON-RPC (explained):** JSON-RPC 2.0 is a tiny remote-procedure-call protocol where each message is a JSON object with `jsonrpc: "2.0"`, a `method` name, optional `params`, and (for requests expecting a reply) an `id`. A response carries the same `id` plus either `result` or `error`. Messages without an `id` are **notifications** (fire-and-forget). MCP is built entirely on JSON-RPC 2.0. You will see this structure repeatedly below.

### 1.2 The three core primitives, and where Tools sit

MCP servers expose three **primitives** to the model/host:

| Primitive | Controlled by | Analogy | Side effects? | This doc |
|---|---|---|---|---|
| **Resources** | *Application*-controlled | GET endpoints / files / read-only data the host attaches as context | No (read-only by design) | — |
| **Prompts** | *User*-controlled | Slash commands / templated workflows the user explicitly invokes | No | — |
| **Tools** | **Model**-controlled | POST endpoints / function calls the **model decides** to invoke | **Yes, typically** | ← **this is our subject** |

The defining property of the **Tools primitive** is the **control axis**: **tools are model-controlled actions**. The host advertises the available tools to the LLM, and *the model itself decides* — during generation — when to call a tool and with what arguments. The host then executes the call (usually after a human-in-the-loop gate), feeds the result back, and the model continues. This is the mechanism by which an LLM stops being a text generator and becomes an **agent** that can *do* things: query a database, send an email, run a build, file a Jira ticket, place an order.

> **"Model-controlled" vs "application-controlled" vs "user-controlled":** This is the single most important distinction in MCP. A **resource** is chosen by the *application's* logic (e.g., "attach the current file"). A **prompt** is chosen by the *user* (e.g., the user clicks "/summarize"). A **tool** is chosen by the *model* mid-reasoning. Tools are the only primitive where the AI is the one pulling the trigger — which is exactly why tools carry the heaviest **security and approval** burden.

### 1.3 The problem Tools solve

LLMs are frozen, stateless text predictors. They cannot:
- Read **live** data (your current order status, today's stock price, the row in *your* Postgres).
- Perform **deterministic** computation reliably (exact arithmetic, regex, hashing).
- Cause **effects** in the world (write a file, charge a card, deploy).

Tools give the model a **typed, discoverable, permissioned** way to delegate these to real systems. The model emits *structured intent* ("call `create_ticket` with `{title, severity}`"), and a real program executes it. Tools are MCP's answer to the broader industry concept of **function calling / tool use**, but standardized so any MCP host can use any MCP server's tools.

### 1.4 When you reach for the Tools primitive

Reach for a tool when **the model needs to act or fetch live/computed data, and you want the model — not a fixed application flow — to decide whether and how**. If the data is static context the app should always inject, that is a **resource**. If it's a fixed workflow the *user* triggers, that's a **prompt**. If it's an action the *AI* should choose to take, it's a **tool**.

### 1.5 One-paragraph mental model

> **A tool is a typed, self-describing function published over JSON-RPC.** The server publishes a catalog (`tools/list`) where each entry has a `name`, a natural-language `description` (read by the *model* to decide when to use it), and a JSON Schema `inputSchema` (which constrains and documents the arguments). The model, seeing this catalog, emits a structured call; the host gates it (often via human approval), invokes `tools/call`, and the server returns `content` (text/image/structured data) plus an `isError` flag. Descriptions sell the tool to the model; schemas keep the model's arguments valid; annotations and approval keep effects safe.

---

## 2. Foundations from first principles

We build the concept from zero. Define each term as it appears.

### 2.1 Participants and roles

- **Host:** The LLM application the user interacts with (Claude Desktop, Cursor, your custom agent runtime). The host embeds an **MCP client** for each server it connects to.
- **Client:** The protocol-level connector inside the host. **One client ↔ one server**, a 1:1 stateful session. The client speaks JSON-RPC to the server.
- **Server:** The process that exposes tools/resources/prompts. It can be local (a subprocess) or remote (an HTTP service). A server is *not* the LLM; it's a plain program (often a thin wrapper over an existing API or DB).
- **The model (LLM):** Lives in the host. It is the *consumer* of tool descriptions and the *decider* of tool calls. The server never talks to the model directly; everything routes through host → client → server.

```
┌──────────────────────── HOST (LLM app) ───────────────────────┐
│                                                               │
│   LLM  ──decides to call──▶  Host orchestration               │
│    ▲                              │                            │
│    │ tool result                  │ (human approval gate)      │
│    │                              ▼                            │
│                          ┌──── MCP CLIENT ────┐                │
└──────────────────────────│   (1 per server)   │───────────────┘
                           └────────┬───────────┘
                          JSON-RPC over a transport
                           (stdio | Streamable HTTP)
                           ┌────────▼───────────┐
                           │     MCP SERVER     │  ← exposes tools
                           └────────┬───────────┘
                                    │
                          real systems: DB, API, FS, shell…
```

### 2.2 Transports (how bytes move)

MCP defines two standard **transports** (the channel over which JSON-RPC messages flow):

1. **stdio:** The host launches the server as a **subprocess** and exchanges newline-delimited JSON-RPC over the process's **stdin/stdout**. `stderr` is for logs. This is the dominant local pattern (zero network setup, OS-level process isolation). 
   > *Newline-delimited:* each JSON message is one line terminated by `\n`; the server must not emit unframed text to stdout or it corrupts the stream — log to stderr instead.
2. **Streamable HTTP** (current standard; superseded the older **HTTP+SSE** transport from the `2024-11-05` spec): the client POSTs JSON-RPC to a single HTTP endpoint; the server may reply with a single JSON response **or** upgrade to a **Server-Sent Events (SSE)** stream for multiple messages (e.g., progress + result). 
   > *SSE (Server-Sent Events):* a one-way HTTP streaming format where the server keeps the connection open and pushes `data:` lines. MCP uses it so a server can stream progress notifications and the final result over one request.

The Tools primitive is **transport-agnostic** — `tools/list` and `tools/call` look identical regardless of transport. Transport only affects deployment, auth, and scaling (covered in §6 and §7).

### 2.3 The session lifecycle (where tools become usable)

Tools cannot be called until the **session is initialized** and **capabilities are negotiated**.

1. **`initialize` (request, client→server):** client sends its `protocolVersion`, its `capabilities`, and `clientInfo`.
2. **`initialize` result (server→client):** server replies with the agreed `protocolVersion`, its `capabilities` (e.g. `tools`, and whether it supports `listChanged`), and `serverInfo`.
3. **`notifications/initialized` (client→server):** handshake complete; normal operation begins.

> **Capability negotiation (explained):** Before either side uses a feature, both declare support. A server that exposes tools advertises `"capabilities": { "tools": { "listChanged": true } }`. `listChanged` means "I may later notify you that my tool list changed." If a server doesn't advertise `tools`, the client must not call `tools/list` or `tools/call`. This prevents calling features the peer doesn't implement.

### 2.4 Anatomy of a tool definition

Every tool published by `tools/list` is a JSON object. The fields (per spec):

- **`name`** *(string, required)* — a **stable, unique** programmatic identifier (e.g. `get_weather`, `create_jira_ticket`). The model references this in calls. Treat it like a function name: snake_case or camelCase, no spaces, stable across versions.
- **`title`** *(string, optional; added in later specs)* — a **human-readable** display name for UIs (e.g. "Create Jira Ticket"). Distinct from `name` so you can rename the UI label without breaking calls.
- **`description`** *(string, required in practice)* — **natural-language prose read by the model** to decide *when* and *why* to use the tool, and what each part does. This is arguably the most important field for correctness (see §2.6).
- **`inputSchema`** *(JSON Schema object, required)* — a **JSON Schema** (draft 2020-12 in current specs) describing the arguments object. Used by the host to render forms, by some runtimes to **constrain** the model's output (so it can only emit valid args), and by the model as documentation.
- **`outputSchema`** *(JSON Schema object, optional; added 2025-06-18)* — schema for **structured** results, so callers can validate/parse the tool's output deterministically.
- **`annotations`** *(object, optional)* — non-binding **hints** about behavior: `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` (see §4.4). These help the host's UI and approval logic; they are **untrusted hints**, not guarantees.

> **JSON Schema (explained):** JSON Schema is a vocabulary for describing the shape of JSON data — `type`, `properties`, `required`, `enum`, `minimum`/`maximum`, `pattern`, `format`, etc. MCP uses it for `inputSchema` so the model knows the exact argument contract. Example: `{ "type": "object", "properties": { "city": { "type": "string" } }, "required": ["city"] }` says "an object with a required string `city`." **Draft 2020-12** is the version the current MCP spec targets; older tooling may only fully support draft-07, so test your schema validator's support.

### 2.5 The two methods that define the primitive

| Method | Direction | Purpose |
|---|---|---|
| **`tools/list`** | client → server | Discover available tools (returns the catalog; supports pagination via `cursor`). |
| **`tools/call`** | client → server | Execute a named tool with `arguments`; returns `content` + `isError`. |
| **`notifications/tools/list_changed`** | server → client | Server tells client the tool catalog changed; client should re-`tools/list`. |

That's the entire surface area of the primitive: **discover, invoke, and (optionally) get told it changed.** Everything else — descriptions, schemas, annotations, approval — is *policy and metadata layered on these two calls*.

### 2.6 Why descriptions and schemas "drive correct use"

The model never sees your server code. It sees only the `name`, `description`, `inputSchema`, and `annotations` — that text **is** the API contract from the model's perspective. Consequences:

- A vague description ("does stuff with users") → the model misuses or ignores the tool.
- A precise description ("Look up a customer by email. Returns the customer's plan tier and account status. Use this before answering billing questions.") → the model calls it correctly, at the right time.
- The schema **bounds** arguments: `enum` prevents invalid values; `required` prevents missing fields; `description` on each property tells the model what to put there. Some inference runtimes use the schema for **constrained decoding / structured outputs**, forcing the model's token stream to conform to the schema — turning "probably valid JSON" into "guaranteed valid JSON."

The mental shorthand: **the description is prompt engineering aimed at the model; the schema is type-checking aimed at the arguments.** You must invest in both.

---

## 3. How it works internally — the heart of the doc

We trace the full lifecycle, then the per-call control/data flow, then the state machine, then constrained decoding.

### 3.1 End-to-end lifecycle (server boot → tool result → next turn)

**Phase A — Connection & negotiation**

1. Host spawns/connects the server; transport established (stdio subprocess or HTTP session).
2. Client sends `initialize` with `protocolVersion` + `capabilities`.
3. Server responds with agreed version + its `capabilities` (must include `tools` to offer tools, and `tools.listChanged` if it will emit change notifications).
4. Client sends `notifications/initialized`.

**Phase B — Discovery**

5. Client sends `tools/list` (optionally with a `cursor` for pagination).
6. Server returns `{ tools: [ {name, title?, description, inputSchema, outputSchema?, annotations?}, … ], nextCursor? }`.
7. Host **transforms** each tool into the model's native tool-use format (e.g., Anthropic's `tools` array, OpenAI's `functions`/`tools`) and includes them in the LLM request. (This translation is host-specific but mechanical.)

**Phase C — Model decides (the model-controlled step)**

8. The user asks something; the host sends the prompt **plus the tool catalog** to the LLM.
9. The LLM, during generation, emits a **tool-use intent**: a structured block naming a tool and giving JSON `arguments` (validated against `inputSchema` by the runtime if constrained decoding is on).
10. The host **pauses** generation. The LLM cannot run code; it only *requested* a call.

**Phase D — Human-in-the-loop gate (recommended/typical)**

11. The host presents the proposed call to the **user for approval** (tool name, arguments, and any `destructiveHint`/`readOnlyHint` cues). The user approves, edits, or denies. (This step is policy, not protocol; the spec *strongly recommends* it for tools.)

**Phase E — Invocation**

12. On approval, the client sends `tools/call` with `{ name, arguments }`.
13. The server **validates** `arguments` against `inputSchema` (defense in depth — never trust the caller), performs **authorization** checks, then executes the underlying action (DB query, API call, etc.). It may emit **progress notifications** for long work.
14. The server returns a `CallToolResult`: `content` (array of typed blocks), optional `structuredContent` (matching `outputSchema`), and `isError`.

**Phase F — Feedback to the model**

15. The host injects the tool result back into the conversation as a **tool-result message** keyed to the original tool-use id.
16. The LLM resumes, reads the result, and either calls another tool (loop to Phase C) or produces the final answer.

**Phase G — Dynamic changes (optional)**

17. If the server's tools change (e.g., the user authenticated and new tools unlocked), the server sends `notifications/tools/list_changed`; the client re-runs `tools/list` and refreshes the catalog.

### 3.2 Per-call control flow (detailed sequence)

```
User: "Open a P1 incident for the checkout outage."

LLM (host)         Host orchestrator        MCP client        MCP server        Backend
   │                      │                      │                  │               │
   │ tool_use:           │                      │                  │               │
   │  create_incident ──▶│                      │                  │               │
   │  {sev:"P1",title…}  │                      │                  │               │
   │                     │  show approval UI    │                  │               │
   │                     │◀── user approves     │                  │               │
   │                     │ tools/call ─────────▶│  JSON-RPC req ──▶│               │
   │                     │                      │                  │ validate args │
   │                     │                      │                  │ authz check   │
   │                     │                      │                  │  POST /incid ─▶│
   │                     │                      │                  │◀── 201 {id}    │
   │                     │                      │◀── result ───────│               │
   │                     │◀── content+isError   │                  │               │
   │ tool_result ◀───────│                      │                  │               │
   │ "Created INC-4521"  │                      │                  │               │
   │ final answer ──────▶│ → user                                  │               │
```

### 3.3 The `tools/call` result structure (data flow out)

A `CallToolResult` has:

- **`content`**: an ordered array of **content blocks**. Each block has a `type`:
  - `text` → `{ "type":"text", "text":"…" }`
  - `image` → `{ "type":"image", "data":"<base64>", "mimeType":"image/png" }`
  - `audio` → `{ "type":"audio", "data":"<base64>", "mimeType":"audio/wav" }` (added 2025-03-26)
  - `resource_link` → reference to a resource the model can then read
  - `resource` (embedded) → `{ "type":"resource", "resource": { "uri":…, "text"|"blob":… } }`
- **`structuredContent`** *(optional)*: a JSON object conforming to the tool's `outputSchema`, for deterministic machine parsing. When present, servers SHOULD also mirror it as `text` content (e.g., the JSON serialized) for backward compatibility with clients that ignore structured output.
- **`isError`** *(boolean, default false)*: **the critical field.** `false` = success. `true` = the **tool itself** failed *but in a way the model should see and reason about* (e.g., "city not found"). See §3.5 for the two-layer error model.

### 3.4 State machine of a single tool invocation

```
            ┌─────────────┐
            │  ADVERTISED │  (in tools/list catalog)
            └──────┬──────┘
                   │ model emits tool_use
                   ▼
            ┌─────────────┐
            │  PROPOSED   │  (awaiting host/user approval)
            └──┬──────┬───┘
       denied  │      │ approved
               ▼      ▼
        ┌─────────┐  ┌──────────────┐
        │ REJECTED│  │  DISPATCHED  │ (tools/call sent)
        └────┬────┘  └──────┬───────┘
             │              │ server executing
             │              ▼
             │      ┌────────────────┐    progress notifications
             │      │   EXECUTING    │◀──(optional, repeated)
             │      └───┬────────┬───┘
             │  success │        │ tool/protocol failure
             │          ▼        ▼
             │   ┌──────────┐  ┌────────────────────┐
             │   │ SUCCESS  │  │ ERROR (isError=true │
             │   │isError=  │  │  OR JSON-RPC error) │
             │   │  false   │  └─────────┬──────────┘
             │   └────┬─────┘            │
             └────────┴──────────────────┘
                          │ result injected back to model
                          ▼
                    ┌──────────┐
                    │ OBSERVED │ (model reads result, continues)
                    └──────────┘
```

### 3.5 The two-layer error model (a subtle, exam-worthy point)

MCP distinguishes **protocol errors** from **tool execution errors** — this is one of the most misunderstood parts of the primitive.

| Layer | How it's signaled | Who should see it | Examples |
|---|---|---|---|
| **Protocol error** | JSON-RPC `error` object (the call fails at the RPC level; no `result`) | The **client/host** (programmatic) | Unknown tool name, malformed request, server crash, `-32602 Invalid params`, `-32601 Method not found` |
| **Tool execution error** | A **successful** JSON-RPC `result` with `isError: true` and error text in `content` | The **model** (so it can recover) | "City not found", "API rate limited", "Insufficient permissions for this record" |

**Why two layers?** If a tool says "I couldn't find that city," the *model* needs to know so it can apologize or ask for clarification — that's a normal business outcome, returned as `result` with `isError:true`. But if the *client* sent garbage (unknown tool, bad JSON), that's a protocol failure the model can't fix; it's a JSON-RPC `error`. **Rule of thumb:** errors the model should reason about → `isError:true` in a result; errors the model can't act on → JSON-RPC error. Putting a domain failure into a JSON-RPC error hides it from the model and breaks recovery.

> **JSON-RPC error codes (explained):** Standard codes include `-32700` (parse error), `-32600` (invalid request), `-32601` (method not found), `-32602` (invalid params), `-32603` (internal error). Codes `-32000` to `-32099` are reserved for server-defined errors. These appear in the `error.code` field of a JSON-RPC error response.

### 3.6 Constrained decoding / structured outputs (why valid args are "guaranteed")

When the host forwards tools to the LLM, advanced runtimes can use the `inputSchema` to **constrain token generation**: at each step, only tokens that keep the partial output valid against the schema are allowed. This is implemented via **grammar-constrained decoding** (compiling the JSON Schema into a finite-state grammar / pushdown automaton and masking the logits).

> **Logit masking (explained):** A model outputs a probability (logit) for every possible next token. Constrained decoding sets the logits of *schema-violating* tokens to `-∞` so they can't be sampled. The model is thus *forced* to emit only structurally valid JSON matching your schema. Result: the model can't produce `{"city": 42}` if `city` must be a string, nor omit a `required` field.

Caveats: constrained decoding guarantees *structural* validity, not *semantic* correctness — the model can still pass `{"city":"Atlantis"}`. Always validate **semantics** server-side. Also, not all hosts/runtimes enable it; never assume the server receives schema-valid input — **re-validate on the server**.

### 3.7 Pagination of `tools/list` (large catalogs)

`tools/list` supports **opaque cursor pagination**. The server returns `nextCursor` if more tools exist; the client re-requests with `params.cursor = nextCursor` until `nextCursor` is absent. Cursors are **opaque** — the client must not parse or construct them; only echo them back. This matters when a server exposes hundreds of tools (e.g., an API gateway). But note: exposing hundreds of tools to the model is itself an anti-pattern (see §6.8 "tool overload").

---

## 4. The complete toolkit

This section enumerates the protocol surface, the official SDKs, and the key APIs/config. We default to **Java** (the official **MCP Java SDK**, also embedded in **Spring AI**), and also give the protocol-level JSON.

### 4.1 Protocol methods (wire-level)

| Method | Params (key fields) | Result (key fields) | Notes |
|---|---|---|---|
| `initialize` | `protocolVersion`, `capabilities`, `clientInfo` | `protocolVersion`, `capabilities`, `serverInfo` | Must precede any tool method. |
| `tools/list` | `cursor?` | `tools[]`, `nextCursor?` | Paginated; cursors opaque. |
| `tools/call` | `name`, `arguments?` | `content[]`, `structuredContent?`, `isError` | `arguments` validated vs `inputSchema`. |
| `notifications/tools/list_changed` | *(none — notification)* | *(none)* | Server→client; requires `tools.listChanged` capability. |
| `notifications/progress` | `progressToken`, `progress`, `total?`, `message?` | — | For long-running tools; client opts in via `_meta.progressToken`. |
| `notifications/cancelled` | `requestId`, `reason?` | — | Either side may cancel an in-flight request. |

### 4.2 The tool definition fields (recap as reference table)

| Field | Type | Required | Default | Purpose |
|---|---|---|---|---|
| `name` | string | yes | — | Stable programmatic id (model calls this). |
| `title` | string | no | — | Human display name for UIs. |
| `description` | string | yes (practically) | — | Model-facing prose: when/why/how to use. |
| `inputSchema` | JSON Schema object | yes | — | Argument contract; constrains & documents args. |
| `outputSchema` | JSON Schema object | no | — | Structured result contract (spec ≥ 2025-06-18). |
| `annotations` | object | no | `{}` | Behavioral hints (see §4.4). |

### 4.3 `CallToolResult` fields

| Field | Type | Required | Default | Purpose |
|---|---|---|---|---|
| `content` | array of content blocks | yes | — | Human/model-readable output (text/image/audio/resource). |
| `structuredContent` | object | no | — | Machine-parseable result matching `outputSchema`. |
| `isError` | boolean | no | `false` | `true` = tool-level failure the model should see. |

### 4.4 Tool **annotations** (behavioral hints)

> **These are untrusted hints**, meant to help host UIs and approval policy. A server can lie; a client must not make security decisions based solely on annotations from an untrusted server.

| Annotation | Type | Default | Meaning |
|---|---|---|---|
| `title` | string | — | Human-readable title (UI). |
| `readOnlyHint` | boolean | `false` | Tool does **not** modify state (safe to auto-run / batch). |
| `destructiveHint` | boolean | `true` | Tool may perform **destructive/irreversible** updates (e.g., delete). Only meaningful when not read-only. |
| `idempotentHint` | boolean | `false` | Repeated calls with same args have **no additional effect** (safe to retry). |
| `openWorldHint` | boolean | `true` | Tool interacts with an **external/open** system (e.g., the web) whose state isn't fully known/controlled. |

Use these to drive UX: auto-approve `readOnlyHint:true`, require explicit confirmation for `destructiveHint:true`, enable safe retries for `idempotentHint:true`.

### 4.5 Official SDKs (where the Tools API lives)

| Language | SDK | Tool-registration entry points |
|---|---|---|
| Java | **MCP Java SDK** (`io.modelcontextprotocol.sdk`); also wrapped by **Spring AI** | `McpServer.sync(...)`/`async(...)`, `SyncToolSpecification`, `Tool`, `CallToolResult` |
| Python | **`mcp`** (FastMCP) | `@mcp.tool()` decorator |
| TypeScript | **`@modelcontextprotocol/sdk`** | `server.registerTool(...)` / `server.tool(...)` |
| C#, Kotlin, Go, Rust, etc. | community/official ports | varies |

### 4.6 Java SDK: the key types (the toolkit you'll actually call)

| Type | Role |
|---|---|
| `McpServer` (factory) | Build a sync or async server: `McpServer.sync(transportProvider)`. |
| `McpServerTransportProvider` (e.g. `StdioServerTransportProvider`, `HttpServletSseServerTransportProvider`/Streamable HTTP) | Wires the transport. |
| `McpSchema.Tool` | The tool definition (`name`, `description`, `inputSchema`). |
| `McpServerFeatures.SyncToolSpecification` | Binds a `Tool` to its handler `(exchange, arguments) -> CallToolResult`. |
| `McpSchema.CallToolResult` | The result builder (`addTextContent`, `isError`). |
| `McpSyncServerExchange` | Per-call context (logging, progress, sampling, server identity). |
| `.capabilities(ServerCapabilities.builder().tools(true).build())` | Declares the `tools` capability (and `listChanged`). |
| `server.notifyToolsListChanged()` | Emit `notifications/tools/list_changed`. |

### 4.7 Spring AI: the annotation-driven path

Spring AI lets you expose Spring beans' methods as MCP tools with `@Tool` and `@ToolParam`, then register them via `MethodToolCallbackProvider`. This is the most ergonomic Java route for many teams. (Worked example in §5.4.)

### 4.8 CLI / dev tooling

| Tool | Purpose |
|---|---|
| **MCP Inspector** (`npx @modelcontextprotocol/inspector`) | Interactive UI to connect to a server, browse `tools/list`, and invoke `tools/call` by hand. Essential for dev/debug. |
| Host config (e.g., `claude_desktop_config.json`) | Registers a server with a host (command, args, env). |
| `mcp` Python CLI / FastMCP dev server | Local run + Inspector launch. |

---

## 5. Code examples by use case

Five distinct scenarios, mostly Java, plus the raw JSON-RPC wire view.

### 5.1 The wire protocol (language-agnostic) — `tools/list` then `tools/call`

```jsonc
// --- Client → Server: discover tools ---
{ "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {} }

// --- Server → Client: the catalog ---
{
  "jsonrpc": "2.0", "id": 1,
  "result": {
    "tools": [{
      "name": "get_weather",
      "title": "Get Weather",
      "description": "Get the current weather for a city. Use when the user asks about current conditions or temperature. Returns temperature in Celsius and a short condition string.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "city": { "type": "string", "description": "City name, e.g. 'Bengaluru'." },
          "units": { "type": "string", "enum": ["c", "f"], "default": "c",
                     "description": "Temperature units." }
        },
        "required": ["city"]
      },
      "annotations": { "readOnlyHint": true, "openWorldHint": true }
    }]
  }
}

// --- Client → Server: invoke ---
{
  "jsonrpc": "2.0", "id": 2, "method": "tools/call",
  "params": { "name": "get_weather", "arguments": { "city": "Bengaluru", "units": "c" } }
}

// --- Server → Client: success result ---
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "content": [{ "type": "text", "text": "Bengaluru: 27°C, partly cloudy." }],
    "isError": false
  }
}

// --- Tool-level error (note: result, NOT a JSON-RPC error) ---
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "content": [{ "type": "text", "text": "Error: city 'Atlantis' not found." }],
    "isError": true
  }
}

// --- Protocol-level error (unknown tool) ---
{
  "jsonrpc": "2.0", "id": 2,
  "error": { "code": -32602, "message": "Unknown tool: get_wether" }
}
```

### 5.2 Java SDK — a **read-only** tool over stdio (weather lookup)

```java
// build.gradle: implementation 'io.modelcontextprotocol.sdk:mcp:<version>'
// (pin the exact version; the API surface evolves with the spec)

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WeatherServer {
  public static void main(String[] args) {
    var mapper = new ObjectMapper();

    // 1) JSON Schema for the input. This is the model-facing contract.
    String inputSchema = """
      {
        "type": "object",
        "properties": {
          "city":  { "type": "string", "description": "City name, e.g. 'Bengaluru'." },
          "units": { "type": "string", "enum": ["c","f"], "default": "c" }
        },
        "required": ["city"]
      }
      """;

    // 2) The tool definition: name + (model-facing) description + schema.
    var tool = new Tool(
        "get_weather",
        "Get current weather for a city. Use for questions about current "
      + "temperature or conditions. Returns temperature and a short summary.",
        inputSchema);

    // 3) Bind the tool to a handler. The handler is plain Java; it returns a result.
    var spec = new SyncToolSpecification(tool, (exchange, arguments) -> {
      // 3a) DEFENSE IN DEPTH: re-validate even though the host *may* have constrained args.
      Object cityObj = arguments.get("city");
      if (!(cityObj instanceof String city) || city.isBlank()) {
        // Bad/missing arg the model should fix → tool-level error (isError=true).
        return new CallToolResult(
            java.util.List.of(new TextContent("Error: 'city' is required.")),
            /* isError */ true);
      }
      String units = (String) arguments.getOrDefault("units", "c");

      try {
        // 3b) Do the read-only work (call a real API in production).
        var report = WeatherApi.current(city, units); // throws on not-found / network
        return new CallToolResult(
            java.util.List.of(new TextContent(report.summary())),
            false); // success
      } catch (CityNotFoundException e) {
        // Domain failure the MODEL should see and recover from → isError=true result.
        return new CallToolResult(
            java.util.List.of(new TextContent("Error: city '" + city + "' not found.")),
            true);
      } catch (Exception e) {
        // Unexpected: still surface to the model (don't leak stack traces/secrets).
        return new CallToolResult(
            java.util.List.of(new TextContent("Error: weather service unavailable.")),
            true);
      }
    });

    // 4) Build the server: declare the tools capability, register the tool, run over stdio.
    McpServer.sync(new StdioServerTransportProvider(mapper))
        .serverInfo("weather-server", "1.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).build())
        .tools(spec)
        .build();
    // Server now blocks reading stdin, answering tools/list and tools/call.
  }
}
```

Key points: the description tells the model *when* to use it; the schema bounds the args; the handler **re-validates** and uses `isError:true` for recoverable, model-visible failures.

### 5.3 Java SDK — a **side-effecting, idempotent** tool with annotations (create-or-get incident)

```java
// Idempotency: an "idempotency key" makes retries safe. We advertise idempotentHint.

String createIncidentSchema = """
  {
    "type": "object",
    "properties": {
      "title":          { "type": "string", "minLength": 5, "maxLength": 120 },
      "severity":       { "type": "string", "enum": ["P1","P2","P3","P4"] },
      "idempotencyKey": { "type": "string",
        "description": "Stable client-generated key; retries with the same key return the same incident." }
    },
    "required": ["title","severity","idempotencyKey"]
  }
  """;

var tool = new Tool("create_incident",
    "Open an incident ticket. Use when the user reports an outage or wants to "
  + "escalate. Repeating with the same idempotencyKey is safe and returns the "
  + "existing incident instead of creating a duplicate.",
    createIncidentSchema);

// Annotations express behavior to the host's approval/UX layer.
var annotated = tool.toBuilder()  // (illustrative; use the SDK's annotation API)
    .annotations(new ToolAnnotations(
        /* title */ "Create Incident",
        /* readOnlyHint */ false,
        /* destructiveHint */ false,   // creating isn't destructive
        /* idempotentHint */ true,     // safe to retry with same key
        /* openWorldHint */ true))
    .build();

var spec = new SyncToolSpecification(annotated, (exchange, args) -> {
  String title = (String) args.get("title");
  String sev   = (String) args.get("severity");
  String key   = (String) args.get("idempotencyKey");

  // AUTHZ: check the caller is allowed to create incidents (see §6 security).
  if (!authz.canCreateIncident(exchange)) {
    return new CallToolResult(
        java.util.List.of(new TextContent("Error: not authorized to create incidents.")),
        true);
  }

  // IDEMPOTENT WRITE: dedupe on the key.
  Incident inc = incidentStore.createOrGet(key, title, Severity.valueOf(sev));

  // Return BOTH human text and structured content (for clients that parse it).
  var structured = java.util.Map.of("id", inc.id(), "status", inc.status());
  return CallToolResult.builder()
      .addTextContent("Created/returned incident " + inc.id() + " (" + sev + ").")
      .structuredContent(structured)   // requires outputSchema on the tool (spec >= 2025-06-18)
      .isError(false)
      .build();
});
```

### 5.4 Spring AI — annotation-driven tool exposure

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;

@org.springframework.stereotype.Service
class OrderTools {

  @Tool(description = "Look up an order by its ID. Returns status, total, and ship date. "
      + "Use for any question about a specific order's status.")
  public OrderView getOrder(
      @ToolParam(description = "The order ID, format ORD-#####") String orderId) {
    return orderService.find(orderId);   // POJO return is auto-serialized to content
  }

  @Tool(description = "Cancel an order. Only allowed if not yet shipped. "
      + "This is irreversible — confirm with the user first.")
  public CancelResult cancelOrder(
      @ToolParam(description = "Order ID to cancel") String orderId) {
    return orderService.cancel(orderId); // destructive: host should require approval
  }
}

@org.springframework.context.annotation.Configuration
class McpToolsConfig {
  @Bean
  MethodToolCallbackProvider orderToolCallbacks(OrderTools tools) {
    // Spring AI reflects over @Tool methods, derives JSON Schema from parameters,
    // and registers them as MCP tools on the auto-configured server.
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }
}
```

Spring AI derives the `inputSchema` from the method signature + `@ToolParam` descriptions, and serializes the return value into `content`. This trades some control (you don't hand-write the schema) for big ergonomic wins.

### 5.5 Python (FastMCP) — for contrast and quick prototyping

```python
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("calc-server")

@mcp.tool()
def compound_interest(principal: float, annual_rate: float, years: int) -> str:
    """Compute compound interest (annual compounding).

    Use for finance questions about growth of a principal over time.
    principal: starting amount; annual_rate: e.g. 0.07 for 7%; years: whole years.
    """
    if principal < 0 or years < 0:
        # Model-visible recoverable error: raise → SDK returns isError=true result.
        raise ValueError("principal and years must be non-negative")
    amount = principal * (1 + annual_rate) ** years
    return f"Future value: {amount:,.2f}"

if __name__ == "__main__":
    mcp.run()  # stdio by default
```

FastMCP infers the JSON Schema from type hints and the docstring becomes the description — the fastest way to a correct tool, useful even for Java shops as a reference implementation.

### 5.6 Client side (Java) — calling a tool programmatically (for tests/agents)

```java
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema.*;

try (var client = McpClient.sync(new StdioClientTransport(serverParams)).build()) {
  client.initialize();                         // handshake
  ListToolsResult tools = client.listTools();  // tools/list
  // (a real agent would feed tools.tools() to the LLM here)

  CallToolResult r = client.callTool(new CallToolRequest(
      "get_weather", java.util.Map.of("city", "Bengaluru", "units", "c")));

  if (Boolean.TRUE.equals(r.isError())) {
    // handle tool-level failure
  } else {
    r.content().forEach(c -> System.out.println(c));
  }
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Tool count vs. context cost:** Every tool's `name`+`description`+`inputSchema` is serialized into the model's context **on every turn**. 50 verbose tools can consume thousands of tokens, slow inference, increase cost, and *degrade selection accuracy* ("tool overload" — the model picks the wrong one). Keep the active set small (rule of thumb: **single-digit to low-double-digit tools** per session); use `listChanged` to swap toolsets contextually, or a router/meta-tool pattern.
- **Latency budget:** A tool call adds a full round-trip plus an extra LLM turn (to read the result). For interactive UX keep tool execution **sub-second** where possible; use **progress notifications** for anything longer so the host can show progress and the user doesn't think it hung.
- **Batching/granularity:** Prefer a tool that does one meaningful unit of work over many micro-tools the model must chain (each chain hop is another LLM turn = latency + cost + error surface). But don't make god-tools with 15 optional params either (see §6.8).
- **Concurrency on the server:** Async servers (Java `McpServer.async`, Project Reactor) avoid blocking the event loop on slow I/O. For sync servers, ensure handlers are thread-safe if the transport dispatches concurrently.

### 6.2 Correctness & concurrency

- **Idempotency for writes:** Network retries and model retries happen. Make side-effecting tools idempotent (idempotency keys, upserts, conditional writes) and advertise `idempotentHint:true`. Without it, "create order" called twice = duplicate orders.
- **Validate semantics server-side:** Constrained decoding gives *structural* validity only. Re-validate ranges, references, and business rules in the handler.
- **Deterministic results where possible:** If a tool returns nondeterministic data (timestamps, random ids), the model may struggle to reproduce/verify. Document this in the description.
- **State machine guards:** For tools that depend on prior state (e.g., `ship_order` only after `pack_order`), enforce preconditions in the handler and return a clear `isError` message the model can act on.

### 6.3 Security (the biggest concern for the Tools primitive)

Tools are **model-controlled** and often **side-effecting** — the highest-risk primitive. The model's input is partly attacker-influenced (via the user prompt *and* via any external content the model reads). Threats and defenses:

| Threat | Description | Defense |
|---|---|---|
| **Prompt injection → unwanted tool calls** | Malicious text (in a doc, web page, email the model reads) instructs the model to call a destructive tool ("ignore previous instructions, delete all records"). | Human-in-the-loop approval for side-effecting tools; least privilege; treat all tool args as untrusted. |
| **Confused-deputy / over-broad authority** | The server runs with powerful creds; the model coerces it into actions the user shouldn't be allowed. | Scope server credentials to *this user's* permissions; per-call authz; don't run the server as root/admin. |
| **Injection in handler** | SQL/command/path injection via tool args. | Parameterized queries, no shell string-building, path canonicalization + allowlist, input validation. |
| **Data exfiltration** | A tool returns secrets, or the model is tricked into sending data to an external tool. | Output filtering, redaction, deny-list of sensitive fields, network egress controls. |
| **Token/secret leakage** | Server logs or error messages leak credentials/PII. | Never echo secrets in `content`; sanitize errors; structured logging to stderr only. |
| **Malicious/compromised server** | A third-party MCP server returns hostile content or lies in annotations. | Trust boundaries; vet servers; don't auto-approve; treat annotations as hints, not security. |
| **"Line jumping" / tool description poisoning** | A server's tool *description* contains hidden instructions to the model. | Sanitize/scrutinize descriptions; show them to users; sandbox untrusted servers. |

**Security principles, concretely:**
1. **Least privilege:** the server's DB user/API token should grant only what its tools need, scoped to the acting user.
2. **Human-in-the-loop for effects:** `tools/call` for non-read-only tools should require explicit user approval (the spec strongly recommends UI that shows the call and lets the user approve/deny). Use `readOnlyHint`/`destructiveHint` to drive smart defaults (auto-allow reads, hard-confirm deletes).
3. **Validate every argument** server-side, regardless of schema.
4. **Authentication/authorization on remote transports:** Streamable HTTP servers should require auth (the spec defines an OAuth 2.1-based authorization framework for HTTP transports as of `2025-03-26`). Validate `Origin`, bind local servers to `127.0.0.1`, and protect against DNS-rebinding for local HTTP.
5. **Sandboxing:** run untrusted/community servers in containers with restricted FS/network.

> **Confused deputy (explained):** A "confused deputy" is a program with more privileges than its caller that gets tricked into misusing those privileges on the caller's behalf. An MCP server holding admin DB creds is a deputy; if it executes whatever the model asks without per-user authz, an attacker (via prompt injection) makes it the *confused* deputy. Fix: the deputy must check the *caller's* authority, not just its own.

### 6.4 Observability

- **Structured logging:** Log every `tools/call` with tool name, (redacted) args, latency, outcome, and `isError`. On stdio, **log to stderr only** — stdout is the protocol channel.
- **Metrics:** call count, error rate (split protocol vs tool-level), p50/p95/p99 latency per tool, approval/denial rates.
- **Tracing:** propagate a trace/correlation id through the handler into backend calls; MCP's `_meta` and progress tokens help correlate.
- **Audit trail:** because tools cause effects, keep an immutable audit log of who/what/when for every side-effecting call (compliance + incident forensics).

### 6.5 Cost

- Tokens for tool definitions are paid **every turn**; trim descriptions to be precise but not bloated.
- Each tool call adds at least one extra LLM turn (read the result). Long tool chains multiply cost.
- Image/audio results are token-expensive; return `resource_link`s the model can fetch on demand instead of inlining megabytes of base64.

### 6.6 Testing

- **Contract tests** with the MCP Inspector or a programmatic client: assert `tools/list` shape, schema validity, and `tools/call` behavior for happy path + each error path.
- **Schema tests:** validate that example arguments pass the schema and that bad ones fail (catch draft-version incompatibilities early).
- **Golden eval set:** given user prompts, does the model select the right tool with right args? (Description quality directly affects this — treat descriptions as testable artifacts.)
- **Idempotency tests:** call twice, assert single effect.
- **Security tests:** injection payloads in args; ensure authz denies cross-tenant access.

### 6.7 Production hardening checklist

- Pin the **spec/protocol version** and SDK version.
- Enforce **timeouts** on handler work; support **cancellation** (`notifications/cancelled`).
- **Rate-limit** per user/tool to contain runaway agent loops.
- **Circuit-break** backend calls; return clean `isError` on backend outage.
- **Graceful degradation:** if a backend is down, the tool returns a model-readable error, not a crash.
- Bound result sizes (truncate huge outputs; paginate).
- Scrub PII/secrets from `content` and logs.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| **Vague descriptions** | Model misuses or skips the tool. | Write when/why/what, with examples. |
| **Tool overload (dozens of tools)** | Token cost + wrong-tool selection. | Curate; swap with `listChanged`; router tool. |
| **God-tool with a `mode` enum + 15 optional params** | Model confused; schema unhelpful. | Split into focused tools. |
| **Domain errors as JSON-RPC errors** | Model can't see/recover. | Use `isError:true` results. |
| **No idempotency on writes** | Duplicate side effects on retry. | Idempotency keys/upserts. |
| **Trusting model args (no server validation)** | Injection, bad data. | Validate + parameterize. |
| **Auto-approving destructive tools** | Prompt injection → data loss. | Human approval; annotations. |
| **Logging to stdout on stdio** | Corrupts JSON-RPC stream. | Log to stderr. |
| **Leaking stack traces/secrets in `content`** | Info disclosure to the model/logs. | Sanitize errors. |
| **Returning megabytes of base64 inline** | Token blowout. | Use `resource_link`. |

---

## 7. Advanced topics & deep internals

### 7.1 Structured output (`outputSchema` + `structuredContent`) — added 2025-06-18

Before this, every result was unstructured `content` (mostly text), forcing callers/models to *parse prose*. With `outputSchema`, a tool declares the JSON shape of its result, returns it in `structuredContent`, and clients can validate and consume it deterministically. **Backward-compat rule:** also serialize the structured data into a `text` content block so older clients still get something. This is the bridge from "LLM reads English" to "program reads typed JSON" within the same call.

### 7.2 `listChanged` and dynamic tool catalogs

`tools.listChanged: true` lets a server mutate its toolset at runtime and push `notifications/tools/list_changed`. Use cases:
- **Auth-gated tools:** unauthenticated session shows `login`; after login, the catalog expands.
- **Context-scoped toolsets:** different tools when the user is in "deploy mode" vs "read-only mode" — a key mitigation for tool overload.
The client must re-`tools/list` on the notification and refresh the LLM's tool array on the next turn. Watch for races: a model may emit a call to a tool that was just removed — handle with a graceful protocol error.

### 7.3 Progress, cancellation, and long-running tools

- **Progress:** the client sets a `progressToken` in the request's `_meta`; the server sends `notifications/progress` with `progress`/`total`/`message`. The host can render a progress bar. Without an opt-in token, servers shouldn't spam progress.
- **Cancellation:** either side can send `notifications/cancelled` for an in-flight `requestId`. The server should stop work and free resources. Implement cooperative cancellation in handlers (check a flag, abort backend calls). This matters because agents and users abandon long calls frequently.
- **Timeouts:** clients enforce their own; design handlers to be interruptible.

### 7.4 Annotations are hints, not contracts (trust model)

`readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint` come *from the server* and may be wrong or malicious. A robust host uses them to **improve UX defaults** (auto-run reads) but never to **waive security** for an untrusted server. For first-party servers you control, treat them as accurate; for third-party, verify behavior independently.

### 7.5 Schema dialect pitfalls (draft 2020-12 vs draft-07)

The spec targets **JSON Schema draft 2020-12**, but:
- Some host/model runtimes historically supported only a *subset* (e.g., parts of draft-07-style schemas), and some ignore advanced keywords (`$ref`, `allOf`, `oneOf`, `format`).
- Avoid exotic constructs in `inputSchema` if you want broad host compatibility; keep schemas flat and simple (`type`, `properties`, `required`, `enum`, `description`, basic numeric/string constraints).
- Test against the *actual* host(s) you deploy to. This is **version- and host-specific** — flag it in your design docs.

### 7.6 The model's tool-selection mechanics (why description engineering works)

Hosts translate MCP tools into the model's native tool-use format; the model is *fine-tuned* to read tool descriptions and emit tool-use blocks. Selection quality depends on:
- **Disambiguation:** descriptions must make tools mutually distinguishable ("get_order" vs "get_order_history" vs "search_orders").
- **Trigger phrasing:** include the situations that should trigger the tool ("Use when the user asks about…").
- **Negative guidance:** sometimes say what *not* to use it for.
- **Param descriptions:** each property's `description` reduces malformed args.
Treat descriptions as **prompts** subject to iteration and eval — small wording changes measurably shift selection accuracy.

### 7.7 Sampling and "agentic" tools (nested LLM calls)

MCP defines a **sampling** capability where a *server* can ask the *client/host* to run an LLM completion on its behalf (the host stays in control of model choice, cost, and approval). This enables tools that internally need model reasoning (e.g., a "summarize_codebase" tool that asks the host's model to summarize) without the server holding its own API key. Relevant to tools because it lets you build higher-order tools while keeping the human/host in the loop.

> **Sampling (explained):** In MCP, "sampling" = the server requesting that the host generate text from an LLM, with the host able to review/modify/deny the request. It inverts the usual flow (normally the host calls the server) and is gated to prevent servers from racking up uncontrolled model costs or doing hidden reasoning.

### 7.8 Multi-server composition & name collisions

A host connected to several servers may see two tools both named `search`. Hosts typically **namespace** tool names (e.g., `serverAlias.search`) when presenting to the model, but the *protocol* `name` per server is just `search`. Design names assuming they may be prefixed; keep them descriptive enough to survive namespacing.

### 7.9 Stateless vs stateful Streamable HTTP

Streamable HTTP can run **stateful** (a session id ties requests together; supports server-initiated messages, `listChanged`, progress over a held stream) or **stateless** (each request independent; simpler to scale horizontally behind a load balancer, but loses some server-push features). Choose based on whether your tools need progress/listChanged and your scaling model. **Vendor/deploy-specific.**

### 7.10 ETag/caching of `tools/list`

For large, slowly-changing catalogs, clients may cache `tools/list` and rely on `listChanged` for invalidation. The protocol doesn't mandate HTTP caching, but on Streamable HTTP you can layer standard caching. Don't over-cache if tools are auth-scoped per session.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which primitive? (Tools vs Resources vs Prompts)

| You want… | Control by | Use |
|---|---|---|
| The **model** to decide to *act* or fetch live/computed data | model | **Tool** |
| The **app** to always inject some read-only context | app | Resource |
| The **user** to trigger a templated workflow | user | Prompt |

**Use a Tool when:** the action has side effects, needs live data, requires deterministic computation, or the *AI* should choose if/when to do it.
**Avoid a Tool when:** the data is static context (→ resource) or the flow is a fixed user-invoked macro (→ prompt). Don't model "read a file the app always attaches" as a tool.

### 8.2 MCP Tools vs raw provider function-calling

| Dimension | MCP Tools | Raw function-calling (per-vendor) |
|---|---|---|
| Portability | One server works across all MCP hosts | Re-implement per host/provider |
| Discovery | Dynamic `tools/list`, `listChanged` | Usually static at request time |
| Transport/process model | stdio/HTTP, separate process, isolation | In-process callbacks |
| Approval/annotations | Standardized hints + HITL norms | App-defined |
| Overhead | Extra process + protocol | Minimal |
| **Use MCP when** | reusable integrations, multiple hosts, isolation, ecosystem | — |
| **Use raw when** | tightly coupled, single app, lowest latency, no reuse | ✓ |

### 8.3 Tool granularity decision rules

- **Split** when: distinct intents, distinct permissions, distinct error semantics, or a `mode` enum is creeping in.
- **Merge** when: the model must chain N micro-calls to do one obvious thing (latency/cost/error multiplier).
- **Target:** each tool = one clear, nameable verb + noun ("create_incident", "get_order").

### 8.4 stdio vs Streamable HTTP transport

| | stdio | Streamable HTTP |
|---|---|---|
| Setup | trivial (subprocess) | needs server, auth, networking |
| Isolation | OS process | network + auth boundary |
| Multi-user/remote | no (local only) | yes |
| Auth | inherited from host/user | OAuth 2.1 (spec ≥ 2025-03-26) |
| Scaling | per-host | horizontal (esp. stateless) |
| **Use when** | local tools, desktop hosts, dev | shared/remote services, SaaS |

### 8.5 Sync vs async server (Java)

| Sync | Async (Reactor) |
|---|---|
| Simple mental model | Non-blocking, better under high concurrency/slow I/O |
| Fine for fast, low-concurrency tools | Use for many concurrent calls or long I/O |
| Easier to test | More moving parts |

---

## 9. Failure modes & debugging

### 9.1 Common production failures and diagnosis

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Server "connects" but no tools appear | `tools` capability not declared; or `tools/list` errors | MCP Inspector → check capabilities + list call; server stderr logs | Declare `.capabilities(...tools(true))`; fix list handler |
| Garbled/closed stdio connection | Server wrote non-JSON to **stdout** (e.g., `System.out.println` debug) | Inspector connection errors; raw stdout dump | Move all logging to stderr |
| Model never calls a perfectly good tool | Weak/ambiguous description; tool overload | Eval prompts; compare descriptions | Rewrite description; cut tool count |
| Model calls tool with bad args | Loose schema; missing `enum`/`required`/`description` | Inspect rejected calls; schema review | Tighten schema; add param descriptions |
| `-32602 Invalid params` | Args don't match schema; dialect mismatch (draft 2020-12 vs host) | JSON-RPC error logs; validate schema locally | Simplify schema; match host's supported dialect |
| Duplicate side effects | Non-idempotent write + retry/double-call | Audit log of calls | Add idempotency key; advertise `idempotentHint` |
| Tool "hangs" the UI | Long handler, no progress, no timeout | Latency metrics; check for progress notifications | Add progress + cancellation + timeout |
| Domain failure invisible to model | Returned as JSON-RPC error instead of `isError` result | Compare error layer vs result layer | Return `result` with `isError:true` |
| Cross-tenant data leak | Missing per-user authz in handler | Security test with two tenants | Enforce caller-scoped authz |
| Secrets in model context/logs | Error text/echo leaks creds/PII | Inspect `content` + logs | Sanitize errors; redact logs |
| Remote server: 401/403 storms | Missing/expired OAuth token | HTTP logs; auth flow trace | Implement/refresh OAuth 2.1 flow |
| Tool vanished mid-session | `listChanged` removed it; race with model call | Notification logs | Handle gracefully; re-list; return clean error |

### 9.2 The debugging toolkit

- **MCP Inspector:** the first thing to reach for — connect, list tools, hand-invoke, see raw JSON. Reproduces issues without the LLM in the loop.
- **stderr logs (stdio):** your primary server log channel.
- **Programmatic client** (e.g., the Java client in §5.6) for automated contract/regression tests.
- **JSON Schema validator** (local) to confirm your `inputSchema` is valid in the target draft and that example args pass/fail correctly.
- **Host logs** (e.g., Claude Desktop's MCP logs) for the host↔client side.
- **Network capture** (for Streamable HTTP) to inspect SSE framing and auth headers.

### 9.3 Real-world incident patterns

- **The runaway agent loop:** a non-idempotent "send_email" tool with no rate limit + an agent retry storm → hundreds of duplicate emails. *Lesson:* idempotency + rate limits + HITL for effects.
- **The stdout pollution outage:** a debug `print`/`System.out.println` left in a handler corrupted the JSON-RPC stream intermittently; the server appeared to "randomly disconnect." *Lesson:* never write to stdout on stdio; CI lint for it.
- **Prompt-injection-driven deletion:** a tool the model could auto-call read a user-supplied document containing "delete all records," and a `delete_records` tool was auto-approved. *Lesson:* never auto-approve destructive tools; least privilege; treat all read content as untrusted.
- **Schema dialect mismatch:** a server used `oneOf`/`$ref`; a host that didn't support them produced `-32602` on every call. *Lesson:* keep schemas simple; test against the real host.

---

## 10. Interview drill

**Q1. What is the Tools primitive in MCP, and how does it differ from Resources and Prompts?**
*Model answer:* Tools are **model-controlled actions** exposed by an MCP server: the model decides when to call them and with what args, the host (usually after human approval) invokes `tools/call`, and the server returns typed content. Resources are **application-controlled** read-only context; Prompts are **user-controlled** templated workflows. The defining axis is *who decides to invoke*: the model for tools, the app for resources, the user for prompts. Tools are also the primary primitive with side effects, hence the security/approval focus.
- *Follow-up: Why does the control axis matter for security?* Because the model's decisions are influenced by untrusted input (user prompt + any content it reads), prompt injection can coerce tool calls; that's why tools need HITL approval and least privilege, unlike read-only resources.
- *Follow-up: Give an example of mis-modeling a tool as a resource.* "Always attach the current file" should be a resource, not a `read_current_file` tool — making it a tool wastes the model's decision budget and tokens.

**Q2. Walk me through the lifecycle of a single tool call, from model decision to result.**
*Model answer:* (1) host sends prompt + tool catalog to the LLM; (2) LLM emits a tool-use intent (name + JSON args); (3) host pauses, shows the call for **approval**; (4) on approval, client sends `tools/call`; (5) server validates args, checks authz, executes (maybe emitting progress); (6) server returns `content` + `isError`; (7) host injects the result back; (8) model reads it and continues or calls another tool.
- *Follow-up: Where can it fail and how is each failure signaled?* Unknown tool/bad request → JSON-RPC `error`; domain failure (city not found) → `result` with `isError:true`.
- *Follow-up: How do long-running calls behave?* Client opts into a `progressToken`; server sends `notifications/progress`; either side can `notifications/cancelled`.

**Q3. Explain the two-layer error model. When do you use `isError:true` vs a JSON-RPC error?**
*Model answer:* Protocol errors (malformed request, unknown method/tool) are JSON-RPC `error` objects — the call failed at the RPC layer, for the *client* to handle. Tool execution errors that the *model* should see and recover from (validation failure, "not found", rate-limited) are returned as a **successful** `result` with `isError:true` and an explanatory `content`. Putting domain failures in JSON-RPC errors hides them from the model and breaks its ability to recover.
- *Follow-up: What goes in the error content?* A clear, model-actionable message — no secrets/stack traces.
- *Follow-up: Why not just throw?* SDKs typically convert thrown exceptions into `isError:true` results so the model still sees them, but you should shape the message deliberately.

**Q4. Why do descriptions and JSON Schemas "drive correct use"? How do you write good ones?**
*Model answer:* The model only ever sees the tool's `name`, `description`, `inputSchema`, `annotations` — that text *is* the contract. The description (model-facing prose) drives **selection**: it should state when/why/what, disambiguate from sibling tools, and sometimes say what not to use it for. The schema drives **argument validity**: `required`, `enum`, ranges, and per-property `description` constrain and document args, and can power constrained decoding. Both are testable artifacts you iterate with evals.
- *Follow-up: What's constrained decoding?* Compiling the schema into a grammar and masking schema-violating tokens so the model can't emit invalid JSON — guarantees structure, not semantics.
- *Follow-up: Schema dialect gotcha?* Spec targets draft 2020-12, but some hosts support only subsets; keep schemas simple and test on the real host.

**Q5. How do annotations like `readOnlyHint`/`destructiveHint` affect behavior, and can you trust them?**
*Model answer:* They're **untrusted hints** that help host UX/approval: auto-run `readOnlyHint:true`, hard-confirm `destructiveHint:true`, allow safe retries for `idempotentHint:true`, flag external state with `openWorldHint`. A malicious server can lie, so they must never *waive* security for untrusted servers — only improve defaults for trusted ones.
- *Follow-up: Default of `destructiveHint`?* `true` (and only meaningful when not read-only).
- *Follow-up: How would HITL policy use them?* Auto-approve reads, require explicit confirmation for destructive/non-idempotent writes.

**Q6. How do you make a side-effecting tool safe to retry?**
*Model answer:* Make it **idempotent** — accept a client-generated idempotency key and upsert/create-or-get on it, or use conditional writes; advertise `idempotentHint:true`. Combine with server-side rate limits and HITL to contain runaway agent loops. Without this, retries/double-calls duplicate effects (duplicate orders/emails).
- *Follow-up: Where does the key come from?* The caller (host/agent) generates a stable key per logical intent.
- *Follow-up: What if the backend isn't idempotent?* Add a dedup layer in the server keyed on the idempotency key.

**Q7. (Senior signal) When would you NOT use MCP Tools, and use raw provider function-calling instead?**
*Model answer:* If the integration is tightly coupled to a single app, never reused across hosts, latency-critical, and you don't want a separate process/protocol, raw in-process function-calling is simpler and faster. MCP earns its overhead when you need **portability across hosts**, **process isolation**, **dynamic discovery/`listChanged`**, standardized approval/annotations, and ecosystem reuse. It's an architecture decision about reuse and isolation, not a feature checklist.
- *Follow-up: Cost of MCP overhead?* Extra process + protocol round-trips; tool defs cost tokens every turn.
- *Follow-up: Migration path?* Start with raw function-calling; extract to an MCP server when a second host/consumer appears.

**Q8. (Senior signal) Your agent is calling the wrong tool / too many tools. Diagnose and fix.**
*Model answer:* Likely **tool overload** (too many tools dilute selection and bloat context) and/or **ambiguous descriptions**. Diagnose with an eval set of prompts measuring selection accuracy. Fix by curating the active toolset (single-digit to low-double-digit), using `listChanged` to swap context-specific toolsets, rewriting descriptions for disambiguation and trigger phrasing, and possibly introducing a router/meta-tool. Verify with the same eval set.
- *Follow-up: How does tool count hurt beyond accuracy?* Tokens/cost per turn and latency.
- *Follow-up: How does `listChanged` help?* Show only relevant tools per mode (e.g., reveal deploy tools only in deploy mode).

**Q9. (Senior signal) Design the security model for a remote MCP server exposing write tools to many users.**
*Model answer:* Use Streamable HTTP with **OAuth 2.1** auth (spec ≥ 2025-03-26). Scope each session's backend credentials to the *acting user* (avoid a god-token → confused-deputy). Enforce per-call authorization in handlers. Require **HITL approval** for side-effecting tools, driven by annotations. Validate and parameterize all args (no SQL/command/path injection). Rate-limit per user/tool; circuit-break backends; audit every effecting call. Sanitize errors and logs (no secret/PII leakage). Treat tool descriptions/results from any untrusted upstream as potential injection vectors.
- *Follow-up: What's a confused deputy here?* The server holding broad creds tricked (via injection) into acting beyond the user's rights; fix with caller-scoped authz.
- *Follow-up: Stateless vs stateful HTTP for this?* Stateless scales horizontally but loses progress/`listChanged` push; choose per feature needs.

**Q10. What are `tools/list` pagination and `notifications/tools/list_changed` for?**
*Model answer:* `tools/list` returns the catalog with an opaque `nextCursor` for pagination of large catalogs; clients echo the cursor, never parse it. `notifications/tools/list_changed` (requires `tools.listChanged` capability) tells the client the catalog changed (e.g., after login or mode switch) so it re-lists. Together they support large/dynamic toolsets and a key overload mitigation: showing only contextually relevant tools.
- *Follow-up: Race risk with `listChanged`?* Model may call a just-removed tool; handle with a clean protocol error.
- *Follow-up: Is huge catalog + pagination a good design?* Usually not for the model — prefer curated/dynamic toolsets.

**Q11. How do structured outputs (`outputSchema`/`structuredContent`) change tool results?**
*Model answer:* Pre-2025-06-18, results were unstructured `content` (mostly text) that programs had to parse from prose. `outputSchema` declares a typed result; the server returns it in `structuredContent` for deterministic consumption, while also mirroring it as `text` for backward compatibility. This bridges "LLM reads English" and "program reads typed JSON" in one call.
- *Follow-up: Why also return text?* Older clients ignore `structuredContent`.
- *Follow-up: Version note?* `outputSchema`/`structuredContent` landed in the 2025-06-18 spec revision.

**Q12. On stdio, why must you never write to stdout, and what's the right channel for logs?**
*Model answer:* stdio uses stdout as the JSON-RPC channel; any stray non-JSON on stdout corrupts the message stream and causes intermittent disconnects/parse errors. Logs go to **stderr**. CI should lint for stray `System.out`/`print` in handlers.
- *Follow-up: How would you catch this in prod?* Inspector connection errors + raw stdout capture.
- *Follow-up: Does this apply to HTTP transport?* No — HTTP framing differs; but disciplined logging still matters.

---

## 11. Glossary

- **Agent (LLM agent):** An LLM that can take actions (via tools) in a loop, not just produce text.
- **Annotation (tool):** Optional, untrusted behavioral *hints* on a tool (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, `title`).
- **Capability negotiation:** The `initialize` handshake where client and server declare which features they support before using them.
- **CallToolResult:** The result of `tools/call` — `content[]`, optional `structuredContent`, and `isError`.
- **Client (MCP):** The protocol connector inside the host; one per server; speaks JSON-RPC.
- **Confused deputy:** A privileged program tricked into misusing its authority on a caller's behalf.
- **Constrained decoding / grammar-constrained generation:** Forcing the model's output to conform to a schema/grammar by masking invalid tokens.
- **Content block:** A typed unit of tool output: `text`, `image`, `audio`, `resource`, `resource_link`.
- **Destructive hint:** Annotation indicating a tool may make irreversible changes.
- **Function calling / tool use:** The general LLM capability to emit structured calls to external functions; MCP standardizes this across hosts.
- **Host:** The LLM application the user uses; embeds clients and the model.
- **HTTP+SSE transport:** The older (2024-11-05) HTTP transport, superseded by Streamable HTTP.
- **Human-in-the-loop (HITL):** Requiring user approval before executing (esp. side-effecting) tool calls.
- **Idempotency / idempotency key:** Property/mechanism ensuring repeated calls with the same key cause no additional effect.
- **Idempotent hint:** Annotation indicating repeated calls are safe.
- **`initialize`:** First request of a session; negotiates protocol version + capabilities.
- **`inputSchema`:** JSON Schema describing a tool's arguments (required field).
- **`isError`:** Boolean on a tool result; `true` = tool-level failure the model should see.
- **JSON-RPC 2.0:** The lightweight RPC protocol (method/params/id/result/error) MCP is built on.
- **JSON Schema (draft 2020-12):** Vocabulary describing JSON shapes; used for `inputSchema`/`outputSchema`.
- **Least privilege:** Granting only the minimum permissions needed.
- **`listChanged`:** Capability/notification indicating the tool catalog changed.
- **Logit masking:** Setting invalid tokens' probabilities to `-∞` during constrained decoding.
- **MCP (Model Context Protocol):** Open standard (Anthropic, Nov 2024) for connecting LLM apps to external capabilities.
- **MCP Inspector:** Official dev/debug UI to browse and invoke a server's primitives.
- **Notification (JSON-RPC):** A message with no `id`, expecting no reply.
- **Open-world hint:** Annotation indicating the tool touches an external/uncontrolled system.
- **`outputSchema` / `structuredContent`:** Schema + payload for typed, machine-parseable tool results (spec ≥ 2025-06-18).
- **Pagination / cursor:** Mechanism to fetch large `tools/list` results in pages via opaque cursors.
- **Primitive (MCP):** One of Tools, Resources, Prompts.
- **Progress notification:** Server→client update on long-running work (opt-in via `progressToken`).
- **Prompt (MCP primitive):** User-controlled templated workflow.
- **Prompt injection:** Malicious instructions embedded in input/content to subvert the model's behavior.
- **Protocol error:** A JSON-RPC `error` (RPC-level failure), distinct from a tool-level `isError` result.
- **Read-only hint:** Annotation indicating no state mutation.
- **Resource (MCP primitive):** Application-controlled read-only context.
- **Resource link:** A content block referencing a resource the model can fetch later.
- **Sampling:** MCP capability letting a server request an LLM completion from the host (host-gated).
- **Server (MCP):** Process exposing tools/resources/prompts; a wrapper over real systems.
- **SSE (Server-Sent Events):** One-way HTTP streaming format used by HTTP-based MCP transports.
- **stdio transport:** Transport over a subprocess's stdin/stdout (logs on stderr).
- **Streamable HTTP transport:** Current HTTP transport; single endpoint, optional SSE upgrade.
- **`tools/call`:** Method to execute a named tool.
- **`tools/list`:** Method to discover available tools.
- **Tool overload:** Degraded selection/cost from exposing too many tools at once.
- **Tool poisoning / line jumping:** Hidden malicious instructions embedded in tool descriptions/results.
- **Transport:** The channel carrying JSON-RPC messages (stdio or Streamable HTTP).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Tools = model-controlled, usually side-effecting actions.** (Resources = app-controlled read-only; Prompts = user-controlled.)
- **Two methods:** `tools/list` (discover, paginated, opaque cursor) and `tools/call` (execute). Plus `notifications/tools/list_changed`.
- **Tool def fields:** `name` (stable id), `title` (UI), `description` (model-facing prose — drives selection), `inputSchema` (JSON Schema, draft 2020-12 — drives arg validity), `outputSchema?` (≥2025-06-18), `annotations?`.
- **Annotations (untrusted hints):** `readOnlyHint` (def **false**), `destructiveHint` (def **true**), `idempotentHint` (def **false**), `openWorldHint` (def **true**).
- **Result:** `content[]` (text/image/audio/resource), `structuredContent?`, `isError` (def **false**).
- **Two-layer errors:** model-visible recoverable → `result` with `isError:true`; RPC-level → JSON-RPC `error` (`-32602` invalid params, `-32601` method not found, `-32603` internal).
- **Lifecycle:** `initialize` → `notifications/initialized` → `tools/list` → model emits call → **HITL approval** → `tools/call` → result back → loop.
- **Transports:** stdio (local subprocess; **log to stderr**, never stdout) | Streamable HTTP (remote; OAuth 2.1 auth ≥2025-03-26; stateful/stateless).
- **Security must-dos:** HITL for effects, least privilege / caller-scoped authz, validate+parameterize all args, rate-limit, idempotency, sanitize errors/logs, treat annotations as hints not guarantees, beware prompt injection + confused deputy.
- **Performance:** few tools (single-digit to low-double-digit); tool defs cost tokens every turn; progress + cancellation + timeouts for long calls.
- **Design rules:** one verb+noun per tool; idempotent writes; clear `isError` messages; precise disambiguating descriptions; simple schemas (test on target host).
- **Debug:** MCP Inspector first; stderr logs; local schema validator; programmatic client for contract tests.
- **Spec revisions to know:** `2024-11-05` (initial; HTTP+SSE), `2025-03-26` (Streamable HTTP, OAuth, audio), `2025-06-18` (structured output). **Pin your version.**

### 12.2 Self-test (no answers — active recall)

1. Why is "model-controlled" the single most important property of the Tools primitive, and what two consequences (one UX, one security) follow directly from it?
2. A tool returns `{"jsonrpc":"2.0","id":7,"error":{"code":-32602,...}}` for a "customer not found" case. Explain what's wrong, what the correct response is, and the concrete downstream effect on the model's behavior.
3. You expose 60 tools and the model frequently picks the wrong one. List three independent mechanisms (protocol- and design-level) to fix this, and how you'd measure improvement.
4. Design a `transfer_funds` tool: give its `name`, a strong `description`, an `inputSchema` (with the right constraints), the correct annotation values, and explain how you make it safe to retry and how the host should gate it.
5. Trace exactly what bytes/messages flow (in order, with method names) from session start to the model reading a tool result on the stdio transport, and name the one thing you must never write to stdout and why.
6. Contrast `inputSchema` constrained decoding guarantees vs. server-side validation: what does each catch that the other cannot, and why do you need both?
7. When would you choose raw provider function-calling over an MCP server, and what specifically do you give up by doing so?

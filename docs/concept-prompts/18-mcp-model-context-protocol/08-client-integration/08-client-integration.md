# MCP — Model Context Protocol: Client Integration

> An engineering-handbook chapter for senior backend developers (Java/JVM-first) who want to fully master MCP **client** integration — from first principles to deep internals, production hardening, and interview-readiness.

---

## 1. Overview & where it fits

### 1.1 What "MCP client integration" means

The **Model Context Protocol (MCP)** is an open protocol that standardizes how an application that talks to a Large Language Model (LLM) connects to external **tools**, **data sources**, and **reusable prompts**. It was introduced by Anthropic in **November 2024** and has since been adopted by multiple vendors and open-source projects. The protocol specification is versioned by date (e.g. `2024-11-05`, `2025-03-26`, `2025-06-18`); the protocol version string is literally a date, which matters in the handshake (see §3).

There are three roles in MCP:

- **Host** — the user-facing application that contains and orchestrates everything (e.g. Claude Desktop, an IDE like Cursor/VS Code, or your own custom chat app / backend service). The host decides *which* servers to connect to, owns the LLM API key, manages user consent, and renders results.
- **Client** — a connector object that lives **inside** the host. There is exactly **one client per server connection** (a 1:1 relationship). The client speaks the MCP wire protocol, maintains the session, and exposes a typed API to the host.
- **Server** — a separate process (or remote endpoint) that exposes capabilities: tools (functions the model can call), resources (readable data, file-like or URI-addressed), and prompts (parameterized templates).

> **First-principles note — "LLM":** A Large Language Model is a neural network that takes a sequence of text (and sometimes images) and predicts the next tokens. Modern chat LLMs support **tool calling** (a.k.a. function calling): you describe functions as JSON schemas, the model emits a structured request to call one, your code executes it, and you feed the result back. MCP is, in large part, a standardized plumbing layer that feeds those tool definitions to the model and routes the calls out to servers.

**"Client integration"** is the engineering task of building (or embedding) the client side: opening transports, performing the handshake, discovering capabilities, wiring MCP tools into the model's tool-calling loop, managing many connections, handling notifications and subscriptions, and surfacing timeouts/errors/consent to the user safely.

### 1.2 The problem it solves

Before MCP, every integration between an AI app and an external system was bespoke. If you had **M** AI applications and **N** integrations (Slack, GitHub, Postgres, a filesystem, an internal service), you faced an **M×N** combinatorial explosion of custom glue code. Each app re-implemented auth, schema description, error handling, and streaming for each tool.

MCP turns this into **M+N**: each host implements **one** MCP client; each integration ships **one** MCP server; any host can talk to any server. This is the same architectural win that the **Language Server Protocol (LSP)** brought to editors and language tooling.

> **First-principles note — "LSP":** The Language Server Protocol lets any editor talk to any language's tooling (autocomplete, go-to-definition) through one JSON-RPC protocol, instead of every editor re-implementing support for every language. MCP is explicitly modeled on LSP — same JSON-RPC base, same "client in the host, server as a separate process" shape.

### 1.3 When you reach for it

- You are building a **host application** (chat UI, IDE plugin, agent runtime, internal automation service) and want it to use third-party or internal capabilities **without** hardcoding each one.
- You want a **uniform** way to let the model call tools, read data, and reuse prompts across many backends.
- You want capabilities to be **swappable and discoverable at runtime** (a server can be added by config, not by recompiling the host).
- You need **separation of concerns**: the LLM key, consent, and UI stay in the host; the integration logic lives in independently deployed servers.

You generally **don't** reach for MCP when there is a single, stable, in-process integration you fully control and never intend to expose elsewhere — a plain function call is simpler. MCP earns its keep when you have **multiplicity** (many tools, many servers, many hosts) or want an **ecosystem** boundary.

### 1.4 One-paragraph mental model

> Think of the MCP client as a **typed, session-managed RPC stub** that lives inside your host. On startup it opens a transport to a server, performs an `initialize` handshake to agree on protocol version and **capabilities** (who supports tools, resources, prompts, subscriptions, sampling, etc.), then lets you `list` and `call` things. Your host's job is to translate the server's tool list into the **JSON schema** your LLM API expects, run the model's tool-calling loop, route each tool call to the right client, gate it behind **user consent**, and feed results back to the model — all while handling timeouts, cancellations, notifications, and reconnects.

---

## 2. Foundations from first principles

### 2.1 JSON-RPC 2.0 — the wire format

MCP messages are **JSON-RPC 2.0**. You must understand this format because every MCP method, response, error, and notification is a JSON-RPC message.

> **First-principles note — "RPC":** Remote Procedure Call is the idea of invoking a function that runs in another process/machine as if it were local. You serialize the call (name + arguments), send it, the other side executes it and returns a serialized result.

JSON-RPC 2.0 defines exactly three message shapes:

**1. Request** (expects a response). Has an `id` (number or string, must be unique within the session and **must not be null**):
```json
{ "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {} }
```

**2. Response** (correlates to a request by `id`). Exactly one of `result` or `error`:
```json
{ "jsonrpc": "2.0", "id": 1, "result": { "tools": [ /* ... */ ] } }
```
```json
{ "jsonrpc": "2.0", "id": 1, "error": { "code": -32601, "message": "Method not found" } }
```

**3. Notification** (fire-and-forget, **no `id`**, no response permitted):
```json
{ "jsonrpc": "2.0", "method": "notifications/tools/list_changed" }
```

Standard JSON-RPC error codes you will see:

| Code | Meaning | Typical cause in MCP |
|------|---------|----------------------|
| -32700 | Parse error | Malformed JSON on the wire |
| -32600 | Invalid request | Not a valid JSON-RPC object |
| -32601 | Method not found | Calling a method the peer doesn't implement |
| -32602 | Invalid params | Wrong/missing arguments, schema mismatch |
| -32603 | Internal error | Unhandled server exception |
| -32000 to -32099 | Server-defined | App-specific; MCP defines some (e.g. resource not found may use -32002) |

> **Important MCP distinction — protocol errors vs. tool errors.** A JSON-RPC `error` means *the protocol call itself failed* (bad method, bad params, server crashed). But when a **tool runs and fails** (e.g. an API it calls returns 500), MCP returns a **successful** JSON-RPC `result` whose body has `isError: true` and the error text inside `content`. This is deliberate: the model is supposed to *see* tool failures and react to them. Confusing these two is the single most common client-integration bug (see §9).

### 2.2 The three primitives the server exposes

| Primitive | Who controls invocation | Mental model | Analogy |
|-----------|------------------------|--------------|---------|
| **Tools** | **Model-controlled** | Functions the LLM may decide to call; can have side effects | POST endpoints / function calls |
| **Resources** | **Application-controlled** | Readable, addressable context (files, rows, docs); ideally read-only | GET endpoints / file reads |
| **Prompts** | **User-controlled** | Parameterized message templates the user explicitly selects | Slash commands / saved snippets |

These categories matter for **UX and security**: tools are the dangerous, model-driven ones (need consent); resources are data you attach; prompts are user-triggered. The client surfaces all three differently.

### 2.3 The three primitives the *client/host* can expose back (reverse direction)

MCP is **bidirectional**. The server can also ask the client for things, if the client advertised support during the handshake:

| Capability | Direction | What it does |
|-----------|-----------|--------------|
| **Sampling** | Server → Client → LLM | Server asks the host to run an LLM completion on its behalf (host keeps key + control). Method: `sampling/createMessage`. |
| **Roots** | Client → Server | Client tells the server which filesystem/URI roots it's allowed to operate within. Method: `roots/list` + `notifications/roots/list_changed`. |
| **Elicitation** | Server → Client → User | (Added `2025-06-18`) Server requests structured input from the user mid-operation. Method: `elicitation/create`. |

Understanding these is part of "client integration" because **your client must implement handlers** for any reverse capability you advertise. If you advertise `sampling` but don't handle `sampling/createMessage`, servers that rely on it will hang or error.

### 2.4 Transports — how bytes move

MCP separates the **protocol** (JSON-RPC + the method set) from the **transport** (how messages physically travel). There are two standard transports; you must know both because client setup differs.

**1. stdio (standard input/output).** The host **launches the server as a child process** and communicates over its stdin/stdout. Messages are newline-delimited JSON. `stderr` is for logging (the client may capture it). This is the default for **local** servers and is the simplest, lowest-latency option.

> **First-principles note — "stdio":** Every process has three default streams: stdin (input), stdout (output), stderr (errors/logs). Piping JSON over stdin/stdout is a classic, dependency-free IPC mechanism. The client writes a request to the child's stdin and reads the response from its stdout.

**2. Streamable HTTP** (current, since `2025-03-26`). The server is a **remote HTTP endpoint** (a single URL, often `/mcp`). The client POSTs JSON-RPC messages; the server may reply with a single JSON response **or** open a **Server-Sent Events (SSE)** stream for multiple messages (needed for server→client requests, progress, and notifications). A session id is carried in the `Mcp-Session-Id` header.

> **First-principles note — "SSE":** Server-Sent Events is a one-way HTTP streaming standard: the client opens a long-lived `GET`/response with `Content-Type: text/event-stream`, and the server pushes `data:` lines over time. Unlike WebSockets it is unidirectional (server→client) and rides on plain HTTP, so it traverses proxies easily.

**Legacy "HTTP+SSE" transport** (the original `2024-11-05` design) used **two** endpoints (a POST endpoint and a separate long-lived SSE endpoint). It is **deprecated** in favor of Streamable HTTP but you will still encounter it; a robust client may support both for backward compatibility. *Flagged as version-specific.*

There is **no official WebSocket transport** in the spec as of mid-2025 (some community implementations exist). Don't assume WebSocket support.

### 2.5 Capability negotiation — the contract

Neither side assumes the other supports anything. During `initialize`, each side sends a `capabilities` object. A client must **gate its behavior** on what the server advertised. For example, only call `resources/subscribe` if the server's capabilities include `resources: { subscribe: true }`. Building this gating correctly is core to client integration.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle: connect → initialize → discover → operate → notify → shut down. Each step shows the actual JSON-RPC traffic and what the client must do.

### 3.1 Lifecycle state machine

```
        ┌─────────────┐
        │ DISCONNECTED│
        └──────┬──────┘
               │ open transport (spawn process / open HTTP)
               ▼
        ┌─────────────┐
        │  CONNECTING │
        └──────┬──────┘
               │ send initialize request
               ▼
        ┌─────────────┐   initialize result received
        │ INITIALIZING│──────────────┐
        └──────┬──────┘              │
               │ send                ▼
               │ notifications/  ┌─────────────┐
               │ initialized     │   READY     │  ← normal operation:
               └────────────────►│ (OPERATION) │     list/call/read/subscribe
                                 └──────┬──────┘
                                        │ close() / transport dies
                                        ▼
                                 ┌─────────────┐
                                 │   CLOSED    │
                                 └─────────────┘
```

**Key rule:** Between sending `initialize` and receiving its result, the client **must not** send any other requests (one exception: it may send pings). After the result, the client **must** send the `notifications/initialized` notification before issuing operational requests. Servers should reject operational calls received before `initialized`.

### 3.2 Step 0 — Open the transport

**stdio:** spawn the server process with the configured command + args + environment.
```
exec: npx -y @modelcontextprotocol/server-filesystem /home/me/project
```
The client now owns the child's lifecycle: it must read stdout (responses), optionally drain stderr (logs), and kill the process on shutdown.

**Streamable HTTP:** establish the HTTP client against the base URL; no session id yet (it arrives in the initialize response headers).

### 3.3 Step 1 — `initialize` request (client → server)

The client sends its supported protocol version, its capabilities, and its identity:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "roots": { "listChanged": true },
      "sampling": {},
      "elicitation": {}
    },
    "clientInfo": { "name": "AcmeHost", "title": "Acme AI Console", "version": "1.4.0" }
  }
}
```

- `protocolVersion`: the **latest** version the client supports. If the server supports it, it echoes it back. If not, the server replies with a version *it* supports; the client then decides whether it can speak that version, and **disconnects if not**.
- `capabilities`: presence of a key = "I support this." `roots.listChanged: true` means "I will send `notifications/roots/list_changed` when my roots change." Empty object (`sampling: {}`) means "supported, no sub-flags."
- `clientInfo`: identity strings, used for logging/telemetry and shown to users.

### 3.4 Step 2 — `initialize` result (server → client)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "tools": { "listChanged": true },
      "resources": { "subscribe": true, "listChanged": true },
      "prompts": { "listChanged": true },
      "logging": {},
      "completions": {}
    },
    "serverInfo": { "name": "filesystem", "title": "Filesystem MCP", "version": "0.7.0" },
    "instructions": "Use list_directory before read_file. Paths are sandboxed to the configured roots."
  }
}
```

The client must now **store** this:
- The negotiated `protocolVersion` (use it for all subsequent behavior and, for HTTP, send it as the `MCP-Protocol-Version` header on every request from `2025-06-18` onward).
- The server capabilities (gate all later calls on these).
- `serverInfo` for display.
- `instructions` — free-text guidance the host **should** inject into the model's system prompt or otherwise make available to the model. This is how a server tells the model *how* to use it.
- For HTTP: capture the `Mcp-Session-Id` response header and send it on **every** subsequent request.

### 3.5 Step 3 — `notifications/initialized` (client → server)

```json
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```
No response. Now the session is **READY**.

### 3.6 Step 4 — Discovery

The client lists each primitive the server advertised. **List endpoints support cursor-based pagination.**

**Tools:**
```json
// → request
{ "jsonrpc":"2.0","id":2,"method":"tools/list","params":{ "cursor": null } }
// ← result
{ "jsonrpc":"2.0","id":2,"result":{
  "tools":[
    {
      "name":"read_file",
      "title":"Read File",
      "description":"Read the contents of a file within the sandbox.",
      "inputSchema":{
        "type":"object",
        "properties":{ "path":{"type":"string","description":"Absolute path"} },
        "required":["path"]
      },
      "outputSchema":{ "type":"object","properties":{ "content":{"type":"string"} } },
      "annotations":{ "readOnlyHint":true, "destructiveHint":false, "idempotentHint":true, "openWorldHint":false }
    }
  ],
  "nextCursor":"eyJwYWdlIjoyfQ=="
}}
```

> **First-principles note — "cursor pagination":** Instead of page numbers, the server returns an opaque `nextCursor` token; you pass it back to get the next page. The client must loop until `nextCursor` is absent/null to collect the full list. Cursors are opaque — never parse them.

Key per-tool fields the client cares about:
- `name`: unique id used when calling. (For your LLM you may need to **namespace** it across servers — see §3.9.)
- `inputSchema`: **JSON Schema** of arguments — this maps almost 1:1 to your LLM API's tool parameter schema.
- `outputSchema` (optional, newer): schema of `structuredContent` results, so you can validate.
- `annotations` (hints, **not security guarantees**): `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`. The client uses these for UX (e.g. auto-allow read-only tools, force confirmation on destructive ones). **Never trust them for security** — a malicious server can lie.

**Resources:**
```json
{ "jsonrpc":"2.0","id":3,"method":"resources/list","params":{} }
// result → resources:[ { "uri":"file:///project/README.md","name":"README","mimeType":"text/markdown" }, ... ]
```
Also `resources/templates/list` returns **URI templates** (RFC 6570) like `file:///logs/{date}.log` so the client/model can construct resource URIs dynamically.

**Prompts:**
```json
{ "jsonrpc":"2.0","id":4,"method":"prompts/list","params":{} }
// result → prompts:[ { "name":"code_review","description":"Review a diff","arguments":[ {"name":"diff","required":true} ] } ]
```

### 3.7 Step 5 — Invocation

**Call a tool** (`tools/call`):
```json
// → request
{ "jsonrpc":"2.0","id":5,"method":"tools/call",
  "params":{ "name":"read_file","arguments":{ "path":"/project/README.md" } } }
// ← success result
{ "jsonrpc":"2.0","id":5,"result":{
  "content":[ { "type":"text","text":"# My Project\n..." } ],
  "structuredContent":{ "content":"# My Project\n..." },
  "isError": false
}}
// ← tool-level failure (STILL a JSON-RPC result, not an error!)
{ "jsonrpc":"2.0","id":6,"result":{
  "content":[ { "type":"text","text":"Error: file not found: /nope" } ],
  "isError": true
}}
```

`content` is an array of typed parts. Content block types:

| `type` | Fields | Meaning |
|--------|--------|---------|
| `text` | `text` | Plain/markdown text |
| `image` | `data` (base64), `mimeType` | Inline image |
| `audio` | `data` (base64), `mimeType` | Inline audio (newer) |
| `resource` (embedded) | `resource: { uri, mimeType, text/blob }` | Inline copy of a resource |
| `resource_link` | `uri`, `name`, `mimeType` | A pointer to a resource the client can fetch |

**Read a resource** (`resources/read`):
```json
{ "jsonrpc":"2.0","id":7,"method":"resources/read","params":{ "uri":"file:///project/README.md" } }
// result → contents:[ { "uri":"...","mimeType":"text/markdown","text":"..." } ]   // or "blob": base64 for binary
```

**Get a prompt** (`prompts/get`) — returns a ready-to-use message list:
```json
{ "jsonrpc":"2.0","id":8,"method":"prompts/get",
  "params":{ "name":"code_review","arguments":{ "diff":"@@ -1 +1 @@..." } } }
// result → messages:[ { "role":"user","content":{ "type":"text","text":"Please review:\n..." } } ]
```

### 3.8 Step 6 — Notifications & subscriptions (server → client, async)

Notifications arrive **out of band** at any time after READY. The client needs a dispatcher that routes by `method`:

| Notification | Meaning | Client reaction |
|--------------|---------|-----------------|
| `notifications/tools/list_changed` | Tool set changed | Re-run `tools/list`; refresh model's tool schema |
| `notifications/resources/list_changed` | Resource set changed | Re-run `resources/list` |
| `notifications/prompts/list_changed` | Prompt set changed | Re-run `prompts/list` |
| `notifications/resources/updated` | A **subscribed** resource changed | Re-`resources/read` that URI; update context |
| `notifications/progress` | Progress on a long op | Update progress UI for that `progressToken` |
| `notifications/message` | Server log message (level, data) | Route to client logs / UI |
| `notifications/cancelled` | A request was cancelled | Stop work for that request id |

**Subscriptions** (only if server advertised `resources.subscribe`):
```json
{ "jsonrpc":"2.0","id":9,"method":"resources/subscribe","params":{ "uri":"file:///project/config.yaml" } }
// later, unsolicited:
{ "jsonrpc":"2.0","method":"notifications/resources/updated","params":{ "uri":"file:///project/config.yaml" } }
// to stop:
{ "jsonrpc":"2.0","id":10,"method":"resources/unsubscribe","params":{ "uri":"file:///project/config.yaml" } }
```

**Progress** requires the client to opt in by attaching a `progressToken` in the request's `_meta`:
```json
{ "jsonrpc":"2.0","id":11,"method":"tools/call",
  "params":{ "name":"reindex","arguments":{}, "_meta":{ "progressToken":"idx-1" } } }
// server pushes:
{ "jsonrpc":"2.0","method":"notifications/progress",
  "params":{ "progressToken":"idx-1","progress":42,"total":100,"message":"indexing files" } }
```

### 3.9 Step 7 — The tool-calling loop (the glue between MCP and the LLM API)

This is the integration that everyone actually wants. The flow:

```
1. Host collects tools from ALL connected clients.
2. Host TRANSLATES each MCP tool → the LLM API's tool/function schema.
   - name  ← (namespaced) MCP tool name, e.g. "filesystem__read_file"
   - description ← MCP description
   - parameters/input_schema ← MCP inputSchema (JSON Schema; usually drop-in)
3. Host sends the user message + tool list to the LLM.
4. LLM responds. Two cases:
   a. Plain text → show to user. DONE for this turn.
   b. One or more tool_use blocks → for EACH:
        i.   parse the (namespaced) name → find the owning client.
        ii.  CHECK USER CONSENT (esp. if not read-only). Maybe prompt.
        iii. client.callTool(name, args) over MCP.
        iv.  collect the result content (text/image/structured).
5. Host feeds tool results back to the LLM as tool_result messages,
   keyed by the model's tool_use id.
6. GOTO 3 (loop) until the model returns plain text (no more tool calls)
   or a max-iteration / budget limit is hit.
```

**Name namespacing** is essential: two servers may both expose a tool called `search`. The LLM only sees a flat list, so the host must rewrite names (e.g. `serverId__toolName`) on the way out and reverse the mapping on the way back. Watch length/charset limits of your LLM API's tool-name field (Anthropic allows `^[a-zA-Z0-9_-]{1,128}$`; OpenAI similar). Avoid characters MCP allows but the LLM API forbids (e.g. dots), hence `__` separators.

**Schema mapping nuances:**
- Anthropic Messages API: `tools: [{ name, description, input_schema }]`; results go back as a `user` message containing `tool_result` blocks (`{type:"tool_result", tool_use_id, content}`).
- OpenAI Chat Completions: `tools:[{type:"function", function:{name, description, parameters}}]`; results go back as `role:"tool"` messages with `tool_call_id`.
- Both want **JSON Schema** for parameters, which is exactly what `inputSchema` is — usually a direct copy, but strip MCP-specific keys the API rejects and ensure `type:"object"` at the root.

**Mapping content back to the model:** MCP `content` blocks → the LLM's tool-result format. Text maps directly. Images map to the model's image block (if it's multimodal). If the model isn't multimodal, you may have to drop or describe images. Always propagate `isError:true` by marking the tool result as an error so the model can recover.

### 3.10 Step 8 — Cancellation, ping, timeouts

- **Ping:** either side may send `{"method":"ping"}`; the peer must reply promptly. Clients use periodic pings as a liveness/keepalive check (important for HTTP/SSE behind idle-timeout proxies).
- **Cancellation:** to abort an in-flight request, send `notifications/cancelled` with the target `requestId` and an optional `reason`. The receiver should stop and not send a (now-unwanted) response — but a race is possible and must be tolerated.
- **Timeouts:** the client SHOULD enforce a per-request timeout. Receiving a `progress` notification MAY reset the timer (so genuinely long jobs aren't killed), but a hard **maximum** timeout should still apply to bound runaway operations.

### 3.11 Step 9 — Shutdown

There is **no** dedicated shutdown JSON-RPC method. Shutdown is transport-level:
- **stdio:** close the child's stdin; if it doesn't exit, send `SIGTERM`, then `SIGKILL`. Drain remaining stdout/stderr.
- **HTTP:** issue an HTTP `DELETE` to the endpoint with the `Mcp-Session-Id` to terminate the session; close the SSE stream(s).

---

## 4. The complete toolkit

### 4.1 The protocol method surface (what a client sends/receives)

| Method | Direction | Type | Purpose | Key params |
|--------|-----------|------|---------|-----------|
| `initialize` | C→S | request | Handshake | `protocolVersion`, `capabilities`, `clientInfo` |
| `notifications/initialized` | C→S | notification | Finish handshake | — |
| `ping` | both | request | Liveness/keepalive | — |
| `tools/list` | C→S | request | Discover tools | `cursor?` |
| `tools/call` | C→S | request | Invoke a tool | `name`, `arguments`, `_meta.progressToken?` |
| `resources/list` | C→S | request | Discover resources | `cursor?` |
| `resources/templates/list` | C→S | request | Discover URI templates | `cursor?` |
| `resources/read` | C→S | request | Read a resource | `uri` |
| `resources/subscribe` | C→S | request | Watch a resource | `uri` |
| `resources/unsubscribe` | C→S | request | Stop watching | `uri` |
| `prompts/list` | C→S | request | Discover prompts | `cursor?` |
| `prompts/get` | C→S | request | Materialize a prompt | `name`, `arguments` |
| `completion/complete` | C→S | request | Argument autocompletion | `ref`, `argument` |
| `logging/setLevel` | C→S | request | Set server log verbosity | `level` |
| `sampling/createMessage` | S→C | request | Server asks host to run LLM | `messages`, `modelPreferences`, `maxTokens`, ... |
| `roots/list` | S→C | request | Server asks client for roots | — |
| `elicitation/create` | S→C | request | Server asks user for input | `message`, `requestedSchema` |
| `notifications/cancelled` | both | notification | Cancel a request | `requestId`, `reason?` |
| `notifications/progress` | both | notification | Progress update | `progressToken`, `progress`, `total?`, `message?` |
| `notifications/message` | S→C | notification | Server log line | `level`, `logger?`, `data` |
| `notifications/tools/list_changed` | S→C | notification | Tools changed | — |
| `notifications/resources/list_changed` | S→C | notification | Resources changed | — |
| `notifications/resources/updated` | S→C | notification | Subscribed resource changed | `uri` |
| `notifications/prompts/list_changed` | S→C | notification | Prompts changed | — |
| `notifications/roots/list_changed` | C→S | notification | Client roots changed | — |

### 4.2 Official SDKs (the client classes you'll actually use)

| Language | Package | Client entry point | Notes |
|----------|---------|--------------------|-------|
| TypeScript/JS | `@modelcontextprotocol/sdk` | `Client` + a transport (`StdioClientTransport`, `StreamableHTTPClientTransport`) | Reference SDK; most complete |
| Python | `mcp` (a.k.a. `modelcontextprotocol`) | `ClientSession` + `stdio_client` / `streamablehttp_client` | Async (`anyio`); FastMCP for servers |
| Java | `io.modelcontextprotocol.sdk:mcp` | `McpClient.sync(...)` / `McpClient.async(...)` → `McpSyncClient` / `McpAsyncClient` | Maintained with **Spring AI**; Reactor-based under the hood |
| Kotlin | `io.modelcontextprotocol:kotlin-sdk` | `Client` | Coroutines |
| C# | `ModelContextProtocol` (NuGet) | `McpClientFactory` | Microsoft-backed |
| Go, Rust, Ruby, Swift, etc. | community/official variants | varies | Maturity varies — verify version support |

> *Version-specific flag:* SDK APIs are still evolving. The Java SDK's package coordinates and class names have changed across early releases; pin a version and check its README. As of writing, the Java SDK lives under `io.modelcontextprotocol.sdk` and integrates with Spring AI's `spring-ai-mcp` modules.

### 4.3 Java SDK — the client toolkit in detail

The Java SDK exposes both a **sync** (blocking) and **async** (Project Reactor `Mono`/`Flux`) client.

| Type / method | Purpose |
|---------------|---------|
| `McpClient.sync(transport)` | Builder for a blocking client |
| `McpClient.async(transport)` | Builder for a reactive client |
| `.requestTimeout(Duration)` | Per-request timeout |
| `.initializationTimeout(Duration)` | Handshake timeout |
| `.capabilities(ClientCapabilities)` | Advertise sampling/roots/elicitation |
| `.clientInfo(Implementation)` | name/version |
| `.toolsChangeConsumer(...)` / `.resourcesChangeConsumer(...)` / `.promptsChangeConsumer(...)` | Handlers for `*_changed` notifications |
| `.loggingConsumer(...)` | Handler for `notifications/message` |
| `.sampling(handler)` | Handler invoked when a server requests `sampling/createMessage` |
| `client.initialize()` | Run the handshake; returns `InitializeResult` |
| `client.listTools()` / `client.listTools(cursor)` | Discover tools (handle pagination) |
| `client.callTool(CallToolRequest)` | Invoke a tool; returns `CallToolResult` (has `isError()`, `content()`) |
| `client.listResources()` / `client.readResource(...)` | Resources |
| `client.subscribeResource(...)` / `client.unsubscribeResource(...)` | Subscriptions |
| `client.listPrompts()` / `client.getPrompt(...)` | Prompts |
| `client.ping()` | Liveness |
| `client.closeGracefully()` / `client.close()` | Shutdown |

Transports (Java):
- `StdioClientServerTransport` / `ServerParameters` — spawn a local process (`command`, `args`, `env`).
- `HttpClientStreamableHttpTransport` / `WebClientStreamableHttpTransport` — remote Streamable HTTP (JDK HttpClient or Spring WebClient flavors).
- Legacy SSE transport classes exist for the deprecated transport.

### 4.4 CLI & developer tooling

| Tool | What it does |
|------|--------------|
| `npx @modelcontextprotocol/inspector` | **MCP Inspector** — a GUI/CLI to connect to a server, list/call tools, inspect traffic. The single most useful debugging tool for client integration. |
| `npx -y @modelcontextprotocol/server-filesystem <dir>` | Reference filesystem server (great for testing your client) |
| `claude mcp add ...` | Claude Code CLI to register servers (host-specific) |
| Host config files | e.g. Claude Desktop's `claude_desktop_config.json` defines `mcpServers` (command/args/env). Hosts read these to know what to spawn. |

### 4.5 Host server-config schema (the de-facto standard)

Most hosts converged on a JSON shape like:
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/me/project"],
      "env": { "LOG_LEVEL": "info" }
    },
    "github": {
      "type": "http",
      "url": "https://mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${GITHUB_MCP_TOKEN}" }
    }
  }
}
```
Your client integration usually starts by parsing exactly this.

---

## 5. Code examples by use case

These default to **Java** (per reader profile), with one TypeScript and one Python example where they're the lingua franca, plus the all-important LLM-loop glue.

### 5.1 Use case A — Minimal stdio client (Java, sync)

```java
// Goal: spawn a local filesystem server, handshake, list & call a tool.
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema.*;

import java.time.Duration;
import java.util.Map;

public class MinimalStdioClient {
  public static void main(String[] args) {
    // 1) Describe how to launch the server process (stdio transport).
    ServerParameters params = ServerParameters.builder("npx")
        .args("-y", "@modelcontextprotocol/server-filesystem", "/tmp/project")
        .build();

    // 2) Build a sync client with sane timeouts.
    McpSyncClient client = McpClient.sync(new StdioClientTransport(params))
        .requestTimeout(Duration.ofSeconds(20))       // hard cap per request
        .initializationTimeout(Duration.ofSeconds(10))// handshake must finish fast
        .clientInfo(new Implementation("MinimalHost", "1.0.0"))
        .build();

    try {
      // 3) Handshake: sends initialize, waits for result, sends initialized.
      InitializeResult init = client.initialize();
      System.out.println("Connected to " + init.serverInfo().name()
          + " (proto " + init.protocolVersion() + ")");

      // 4) GATE on capabilities before using a feature.
      if (init.capabilities().tools() == null) {
        System.out.println("Server has no tools; nothing to do.");
        return;
      }

      // 5) Discover tools (handle pagination in real code; see 5.2).
      ListToolsResult tools = client.listTools();
      tools.tools().forEach(t -> System.out.println("tool: " + t.name()));

      // 6) Invoke a tool. Note: arguments are a plain Map matching inputSchema.
      CallToolResult res = client.callTool(new CallToolRequest(
          "read_file", Map.of("path", "/tmp/project/README.md")));

      // 7) CRITICAL: distinguish tool-level error from success.
      if (Boolean.TRUE.equals(res.isError())) {
        System.err.println("Tool failed: " + textOf(res));
      } else {
        System.out.println("Got:\n" + textOf(res));
      }
    } finally {
      client.closeGracefully(); // closes stdin, then SIGTERM/SIGKILL if needed
    }
  }

  // Helper: flatten text content blocks. Real code also handles image/resource.
  static String textOf(CallToolResult res) {
    StringBuilder sb = new StringBuilder();
    for (Content c : res.content()) {
      if (c instanceof TextContent tc) sb.append(tc.text());
    }
    return sb.toString();
  }
}
```

### 5.2 Use case B — Paginated discovery + capability gating helper (Java)

```java
// Goal: robustly collect ALL tools across pages, and only subscribe if allowed.
import java.util.*;

class Discovery {
  static List<Tool> listAllTools(McpSyncClient client) {
    List<Tool> all = new ArrayList<>();
    String cursor = null;
    do {
      ListToolsResult page = (cursor == null)
          ? client.listTools()
          : client.listTools(cursor);   // pass opaque cursor back
      all.addAll(page.tools());
      cursor = page.nextCursor();        // null/empty => done
    } while (cursor != null && !cursor.isEmpty());
    return all;
  }

  static void watchConfig(McpSyncClient client, InitializeResult init, String uri) {
    var resCaps = init.capabilities().resources();
    // Only subscribe if the server advertised resources.subscribe == true.
    if (resCaps != null && Boolean.TRUE.equals(resCaps.subscribe())) {
      client.subscribeResource(new SubscribeRequest(uri));
    } else {
      System.out.println("Subscribe unsupported; will poll instead.");
    }
  }
}
```

### 5.3 Use case C — Async client with notification handlers (Java, Reactor)

```java
// Goal: react to tools/list_changed and resources/updated without blocking.
import io.modelcontextprotocol.client.McpAsyncClient;
import reactor.core.publisher.Mono;

McpAsyncClient client = McpClient.async(transport)
    .requestTimeout(Duration.ofSeconds(30))
    // Fired when the server sends notifications/tools/list_changed:
    .toolsChangeConsumer(updatedTools -> Mono.fromRunnable(() -> {
        // Re-translate tools into the LLM schema and hot-swap them.
        toolRegistry.replace(serverId, updatedTools);
    }))
    // Fired on notifications/resources/updated (subscribed URIs):
    .resourcesChangeConsumer(changed -> Mono.fromRunnable(() ->
        changed.forEach(r -> contextCache.invalidate(r.uri()))))
    // Server log lines (notifications/message):
    .loggingConsumer(log -> Mono.fromRunnable(() ->
        logger.info("[{}] {}", log.level(), log.data())))
    .build();

client.initialize()
    .then(client.listTools())
    .doOnNext(t -> toolRegistry.put(serverId, t.tools()))
    .subscribe();
```

### 5.4 Use case D — Implementing the reverse `sampling` handler (Java)

```java
// Goal: let a server delegate an LLM call back to us (host keeps the key & control).
McpSyncClient client = McpClient.sync(transport)
    .capabilities(ClientCapabilities.builder().sampling().build()) // advertise it!
    .sampling(req -> {
        // SECURITY: never auto-approve blindly. Apply policy / user consent.
        if (!consent.allowSampling(serverId, req)) {
            return new CreateMessageResult(/*role*/"assistant",
                new TextContent("Sampling denied by user."), /*model*/"none",
                /*stopReason*/"refusal");
        }
        // Translate MCP sampling request -> your LLM API, call it, translate back.
        String answer = llm.complete(req.messages(), req.maxTokens());
        return CreateMessageResult.builder()
            .role("assistant")
            .content(new TextContent(answer))
            .model(llm.modelId())
            .stopReason("endTurn")
            .build();
    })
    .build();
```

### 5.5 Use case E — The full LLM tool-calling loop (Java + Anthropic-style API, pseudocode-faithful)

```java
// Goal: wire MULTIPLE MCP servers into one model tool-calling loop with consent.
class McpAgent {
  private final Map<String, McpSyncClient> clients;  // serverId -> client
  private final LlmApi llm;                           // your Anthropic/OpenAI wrapper
  private final ConsentService consent;

  // Build the model's tool list from all servers, namespaced to avoid clashes.
  List<LlmTool> buildToolList() {
    List<LlmTool> out = new ArrayList<>();
    clients.forEach((sid, c) -> {
      for (Tool t : Discovery.listAllTools(c)) {
        String mapped = sid + "__" + t.name();          // namespacing
        out.add(new LlmTool(mapped, t.description(), t.inputSchema())); // schema is reused
      }
    });
    return out;
  }

  String run(String userMessage) {
    List<Message> convo = new ArrayList<>();
    convo.add(Message.user(userMessage));
    List<LlmTool> tools = buildToolList();

    for (int turn = 0; turn < 12; turn++) {            // bound the loop!
      LlmResponse resp = llm.createMessage(convo, tools);
      convo.add(resp.asAssistantMessage());

      if (resp.toolUses().isEmpty()) {
        return resp.text();                            // model is done
      }

      List<ToolResult> results = new ArrayList<>();
      for (ToolUse use : resp.toolUses()) {
        // 1) Reverse the namespace -> (serverId, toolName)
        int sep = use.name().indexOf("__");
        String sid = use.name().substring(0, sep);
        String tool = use.name().substring(sep + 2);
        McpSyncClient client = clients.get(sid);

        // 2) CONSENT GATE (per call). Auto-allow only read-only/whitelisted.
        if (!consent.allow(sid, tool, use.input())) {
          results.add(ToolResult.error(use.id(),
              "User declined to run " + tool));
          continue;
        }

        // 3) Execute over MCP, with error surfacing.
        try {
          CallToolResult r = client.callTool(
              new CallToolRequest(tool, use.input()));
          if (Boolean.TRUE.equals(r.isError())) {
            // Tool-level error: still feed it back so the model can recover.
            results.add(ToolResult.error(use.id(), textOf(r)));
          } else {
            results.add(ToolResult.ok(use.id(), toLlmContent(r.content())));
          }
        } catch (McpTimeoutException e) {
          results.add(ToolResult.error(use.id(), "Tool timed out"));
        } catch (Exception e) {
          // Protocol/transport error: degrade gracefully, don't kill the turn.
          results.add(ToolResult.error(use.id(), "Tool unavailable: " + e.getMessage()));
        }
      }
      // 4) Feed all tool results back as one user turn; loop continues.
      convo.add(Message.toolResults(results));
    }
    return "Stopped: exceeded max tool-calling iterations.";
  }
}
```

The four boxed concerns — **namespacing**, **consent**, **error surfacing**, **loop bounding** — are exactly what separate a toy from a production client.

### 5.6 Use case F — TypeScript stdio client (reference SDK)

```typescript
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const transport = new StdioClientTransport({
  command: "npx",
  args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp/project"],
});

const client = new Client(
  { name: "ts-host", version: "1.0.0" },
  { capabilities: {} }                      // advertise reverse caps here
);

await client.connect(transport);            // opens transport + does initialize

const { tools } = await client.listTools(); // discovery
const result = await client.callTool({
  name: "read_file",
  arguments: { path: "/tmp/project/README.md" },
});

if (result.isError) console.error("tool error:", result.content);
else console.log(result.content);

await client.close();
```

### 5.7 Use case G — Remote Streamable HTTP client with auth (Python)

```python
import asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

async def main():
    headers = {"Authorization": "Bearer " + TOKEN}  # OAuth bearer for remote MCP
    # streamablehttp_client manages POST + SSE + Mcp-Session-Id for you.
    async with streamablehttp_client("https://mcp.example.com/mcp",
                                     headers=headers) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()                    # handshake
            tools = await session.list_tools()
            res = await session.call_tool("create_issue",
                                          {"title": "Bug", "body": "..."})
            print(res.isError, res.content)

asyncio.run(main())
```

### 5.8 Use case H — Spring AI auto-wired MCP client (Java, Spring Boot)

```yaml
# application.yml — Spring AI discovers servers from config and auto-creates clients.
spring:
  ai:
    mcp:
      client:
        enabled: true
        request-timeout: 20s
        stdio:
          connections:
            filesystem:
              command: npx
              args: [-y, "@modelcontextprotocol/server-filesystem", "/tmp/project"]
        streamable-http:
          connections:
            github:
              url: https://mcp.example.com/mcp
```
```java
// Spring AI exposes MCP tools as ToolCallbacks you hand directly to ChatClient.
@RestController
class AgentController {
  private final ChatClient chat;
  AgentController(ChatClient.Builder builder, ToolCallbackProvider mcpTools) {
    this.chat = builder.defaultTools(mcpTools).build(); // MCP tools auto-injected
  }
  @PostMapping("/ask")
  String ask(@RequestBody String q) {
    return chat.prompt(q).call().content();             // loop handled by Spring AI
  }
}
```
This is the lowest-effort path for JVM teams: Spring AI does discovery, schema mapping, and the tool-calling loop for you — at the cost of less control over consent/observability hooks, which you then add via interceptors.

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Connection reuse:** keep clients/sessions long-lived. Re-spawning a stdio server or re-handshaking HTTP per request is wasteful (process spawn ~tens of ms; handshake = round trips).
- **Parallel discovery:** on startup, initialize and list across all servers concurrently (Reactor `Flux.merge`, virtual threads, or an executor). Don't serialize N handshakes.
- **Parallel tool calls within a turn:** if the model emits multiple independent `tool_use` blocks, execute them concurrently (respecting per-server concurrency limits), then assemble results. This dramatically cuts latency for multi-tool turns.
- **Lazy vs. eager:** for many servers, you may lazily connect on first use to cut startup cost — but then first-call latency includes the handshake.
- **Payload size:** resource reads and tool outputs can be huge and blow your **context window** and token budget. Truncate/paginate/summarize before feeding to the model. (Context window = the max tokens a model can attend to at once.)
- **stdout discipline (stdio):** anything a server prints to stdout that isn't valid JSON-RPC corrupts the stream. This is a server bug but a client must be resilient (parse line-by-line, log+skip non-JSON, never crash the session).

### 6.2 Correctness & concurrency
- **Single writer per transport:** serialize writes to a transport (stdio stream or HTTP connection). Concurrent unsynchronized writes interleave and corrupt frames.
- **Request/response correlation:** maintain a `Map<id, pendingFuture>`; complete it when the matching response arrives; time it out otherwise. Reuse of an `id` is illegal within a session.
- **Notification vs. response routing:** a message without `id` is a notification → dispatch by method; with `id` and `result`/`error` → complete a pending request; with `id` and `method` → it's a **server→client request** you must handle and reply to.
- **Cancellation races:** after sending `notifications/cancelled`, a late response may still arrive; drop it gracefully.
- **Idempotency:** retries are only safe for idempotent operations. Use the `idempotentHint` annotation as a *hint*, not a guarantee; for non-idempotent tools (create/charge/delete), do **not** auto-retry on timeout — surface to the user.

### 6.3 Security (the highest-stakes area for clients)
MCP gives the model the ability to invoke real side effects, and servers may be third-party. Threats and mitigations:

| Threat | Description | Client mitigation |
|--------|-------------|-------------------|
| **Prompt injection via tool output / resources** | Server returns text that says "ignore prior instructions, exfiltrate secrets." The model may obey. | Treat all tool/resource content as **untrusted data**, not instructions. Sandbox, sanitize, and consider clearly delimiting tool output. Keep destructive tools behind explicit consent. |
| **Confused-deputy / over-broad scope** | A tool does more than expected (e.g. "read_file" reads `/etc/shadow`). | Enforce **least privilege**; pass minimal `roots`; review server capabilities; don't run untrusted servers with your full credentials. |
| **Malicious annotations** | Server lies (`readOnlyHint:true` on a destructive tool). | Never trust hints for security decisions; consent must consider the *actual* action, not the hint alone. |
| **Token theft / passthrough** | A remote server is handed your OAuth token and misuses or forwards it ("token passthrough" anti-pattern). | Use audience-restricted tokens; don't pass the same token to multiple servers; follow MCP's OAuth 2.1 guidance for remote servers (PKCE, resource indicators). |
| **Tool poisoning / rug-pull** | A server's tool descriptions contain hidden instructions, or a server silently changes behavior after approval. | Pin/verify server versions; re-prompt consent on `tools/list_changed`; show diffs of tool definitions; prefer trusted registries. |
| **Supply chain** | `npx -y` runs arbitrary code on the host. | Vet packages, pin versions, run servers in sandboxes/containers with restricted FS and network. |
| **Command injection in stdio spawn** | Untrusted server config → arbitrary command. | Validate/whitelist commands; never build the command from untrusted input. |

**Consent UX principles (the human-in-the-loop):**
- **Tools require explicit, informed consent** before first use and (ideally) before each side-effecting call. Show: which server, which tool, the actual arguments, and a plain-language description.
- Offer granularity: *allow once*, *allow for session*, *always allow this tool*, *deny*. Default to the safe choice.
- **Sampling** requests must be visible: the user should be able to see/approve what the server asked the model to do and review the result.
- **Elicitation** must be rendered as a trustworthy form; never let a server's prompt impersonate the host.
- **Resources/roots:** be explicit about what data is exposed; let the user scope it.

> **First-principles note — "OAuth 2.1 / PKCE":** OAuth is a delegated-authorization standard (log in once, grant an app limited access). PKCE ("pixie", Proof Key for Code Exchange) is an extension that prevents an intercepted authorization code from being exchanged by an attacker, by binding it to a secret only the legitimate client holds. MCP's remote-server auth guidance is built on OAuth 2.1 + PKCE + resource indicators (which audience the token is valid for).

### 6.4 Memory
- Bound in-flight request maps and queues; a server that never responds shouldn't cause unbounded growth (timeouts must clean up).
- Stream large resource/tool payloads where possible; avoid buffering multi-MB base64 blobs fully if you can hand them off.
- Drain server `stderr`; an unread pipe can fill its OS buffer and **block the server**.

### 6.5 Cost
- Every tool definition you expose consumes input tokens **on every model turn**. Exposing 200 tools is expensive and degrades tool-selection accuracy. **Curate**: only attach relevant servers/tools, or implement dynamic tool filtering per task.
- Sampling delegates LLM calls (and cost) to your key — meter and cap it.

### 6.6 Observability
- Assign a **trace/correlation id** per logical operation and tag every JSON-RPC message with it (via `_meta`) where possible.
- Log: handshake result (server name/version/proto), each `tools/call` (server, tool, latency, isError), notifications received, reconnects, consent decisions.
- Use the **MCP Inspector** during development to see raw traffic.
- Capture metrics: per-server call rate, error rate, p50/p95 latency, timeout rate, reconnect count.
- Use `logging/setLevel` to dial server verbosity and route `notifications/message` into your logging system with the right level mapping.

### 6.7 Testability
- **Unit:** mock the transport; feed canned JSON-RPC frames; assert your client's handshake, dispatch, correlation, and error mapping.
- **Contract:** run against the reference servers (filesystem, etc.) and the Inspector to validate real behavior.
- **Fault injection:** test slow responses (timeout path), non-JSON stdout, server crash mid-call, duplicate ids, late responses after cancellation, version mismatch on initialize.
- **Loop tests:** assert the agent loop bounds iterations, surfaces tool errors to the model, and namespacing round-trips correctly.

### 6.8 Production hardening checklist
- [ ] Per-request timeout **and** a hard max even with progress.
- [ ] Reconnect with exponential backoff + jitter; cap attempts; surface persistent failure.
- [ ] Health checks via `ping`; detect dead stdio child / closed SSE.
- [ ] Graceful shutdown (close stdin → SIGTERM → SIGKILL; HTTP DELETE session).
- [ ] Capability gating on every feature use.
- [ ] Consent enforced for side-effecting tools; safe defaults.
- [ ] Tool-name namespacing + length/charset validation for the LLM API.
- [ ] Sandboxed server execution (container, restricted FS/network).
- [ ] Bounded resource/tool output sizes before feeding the model.
- [ ] Structured logging + metrics + tracing.
- [ ] Re-consent / re-validate on `*_changed` notifications.

### 6.9 Anti-patterns to avoid
- Treating tool-level `isError:true` as a fatal protocol error (or vice-versa).
- Sending operational requests before `notifications/initialized`.
- Trusting `annotations` for security decisions.
- Auto-approving all tools "to reduce friction."
- One giant unbounded tool-calling loop with no iteration/budget cap.
- Re-handshaking per request.
- Ignoring `nextCursor` (silently using only the first page of tools).
- Blocking the read loop while handling a notification (deadlock the session).
- Passing the same broad OAuth token to every remote server.
- Not draining `stderr`/SSE → process stalls.

---

## 7. Advanced topics & deep internals

### 7.1 Version negotiation edge cases
- If the server responds to `initialize` with a `protocolVersion` the client doesn't support, the client **must disconnect**. Don't try to limp along.
- For Streamable HTTP from `2025-06-18`, the client must send the negotiated version as the `MCP-Protocol-Version` HTTP header on **every** subsequent request; servers may reject requests without it (often `400`). Older servers infer the version. *Version-specific.*
- Capability flags are additive across versions; absence means "unknown/unsupported," so default to the conservative behavior.

### 7.2 Streamable HTTP internals
- A single endpoint handles `POST` (client→server messages) and optionally `GET` (to open a standalone SSE stream for server→client messages without a triggering request).
- For a request, the server **chooses**: respond with `Content-Type: application/json` (single response) or `text/event-stream` (a stream that may carry progress + the final response + interleaved server→client requests). Your client must handle both content types on the same call.
- **Resumability:** SSE supports a `Last-Event-ID`; a robust client stores the last event id and, on reconnect, sends it so the server can replay missed messages. This is how long operations survive a dropped connection.
- **Session id:** `Mcp-Session-Id` from the initialize response must be echoed on all later requests; losing it means losing the session.
- **Stateless mode:** some servers run statelessly (no session id); the client must tolerate its absence.

### 7.3 Sampling internals (`modelPreferences`)
The server can express preferences without naming a model: `costPriority`, `speedPriority`, `intelligencePriority` (0..1) plus optional `hints` (model-name substrings). The host maps these to *its* available models — the host always has final say. The server may also request `includeContext: "thisServer" | "allServers" | "none"`, asking the host to include conversation/context; the host decides what (if anything) to include, for privacy.

### 7.4 Elicitation internals
Added in `2025-06-18`. `elicitation/create` sends a `message` and a `requestedSchema` (a restricted JSON Schema — typically flat objects of primitives). The client renders a form, validates input against the schema, and returns one of: `accept` (with `content`), `decline`, or `cancel`. The client should **never** auto-fill sensitive fields and must clearly attribute the request to the server. This enables interactive flows (e.g. "which of these 3 records did you mean?") without baking everything into tool args.

### 7.5 Completions (`completion/complete`)
For argument autocompletion in prompts and resource templates: the client sends a `ref` (to a prompt or resource template) and the partial `argument`; the server returns up to ~100 candidate values. This powers IDE-like dropdowns in the host UI.

### 7.6 `_meta` and progress tokens
`_meta` is an open extension bag on requests/results. The standardized use is `progressToken` to opt into progress notifications. Vendors also use `_meta` for trace ids and experimental features. Pass unknown `_meta` through; don't choke on it.

### 7.7 Multiple connections & lifecycle orchestration
- Keep a registry: `serverId → { client, capabilities, tools, status }`.
- Treat each connection's failure as **isolated** — one dead server must not break others or the whole agent turn.
- Hot-reload: when host config changes, diff and add/remove clients without restarting.
- Backpressure: cap concurrent in-flight calls per server; queue or reject beyond that.
- Ordering: notifications and responses can interleave; never assume a notification arrives before/after a related response.

### 7.8 Reconnection & idempotency at scale
- stdio child died → respawn, re-initialize, re-list, **re-subscribe** to all previously subscribed resources (subscriptions don't survive a new session).
- HTTP session expired (server returns `404`/`400` for unknown session) → re-initialize to get a fresh `Mcp-Session-Id`.
- After reconnect, in-flight non-idempotent calls are in an **unknown** state — surface to the user rather than blindly retrying.

### 7.9 Lesser-known behaviors
- A server may send `instructions` that you should inject into the system prompt — many clients forget this, losing important guidance.
- `resource_link` content lets a tool return *pointers* instead of inlining huge blobs; a smart client fetches lazily/on demand.
- `outputSchema` + `structuredContent` let you validate and parse tool output deterministically rather than re-parsing text — prefer it when present.
- `roots` are advisory to the server (a filesystem server *should* respect them, but enforcement is the server's job — don't rely on roots alone for sandboxing).
- Pagination cursors are opaque and may expire; restart the listing from scratch if a cursor is rejected.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Transport choice

| | stdio | Streamable HTTP | Legacy HTTP+SSE |
|---|-------|-----------------|-----------------|
| Server location | Local child process | Remote/local URL | Remote |
| Setup | Spawn binary | HTTP client + URL | Two endpoints |
| Latency | Lowest | Network-bound | Network-bound |
| Multi-client | One process per client | One server, many clients | Many clients |
| Auth | Inherits host (env) | OAuth/headers | OAuth/headers |
| Resumability | N/A (restart) | Yes (Last-Event-ID) | Limited |
| Status | Current | **Current/preferred** | **Deprecated** |
| Use when | Local tools, dev, max control | SaaS/remote, multi-tenant | Only for back-compat |

### 8.2 Sync vs. async client (Java)

| | Sync (`McpSyncClient`) | Async (`McpAsyncClient`) |
|---|---|---|
| Mental model | Blocking calls | Reactor `Mono`/`Flux` |
| Best for | Simple scripts, sequential flows, virtual-threads code | High-concurrency hosts, many servers, streaming |
| Notifications | Consumer callbacks | Reactive consumers, composable |
| Risk | Thread-per-call cost without virtual threads | Reactive complexity |

### 8.3 Build-your-own client vs. SDK vs. framework

| Approach | Pros | Cons | Use when |
|----------|------|------|----------|
| Raw JSON-RPC by hand | Full control, no deps | You reimplement handshake, correlation, transports, edge cases | Almost never; only for exotic embedded constraints |
| Official SDK (Java/TS/Python) | Correct, maintained, covers edge cases | Some churn; opinionated | Default for custom hosts |
| Framework (Spring AI, LangChain, etc.) | Discovery + schema mapping + loop done for you | Less control over consent/observability; abstraction leak | You want fast time-to-value on JVM and standard behavior is fine |

### 8.4 MCP vs. alternatives for tool integration

| Approach | What it is | Use when | Avoid when |
|----------|-----------|----------|------------|
| **MCP** | Standard protocol, swappable servers, ecosystem | Many tools/servers/hosts; want reuse and a consent boundary | Single in-process integration you fully own |
| Native function calling (hand-written tools) | You define tools directly to the LLM API | Few stable tools, tight control, lowest overhead | You need reuse/swap/3rd-party ecosystem |
| Plugin/OpenAPI-to-tool bridges | Auto-generate tools from an OpenAPI spec | You already have REST APIs and want quick exposure | You need stateful sessions, subscriptions, prompts, sampling |
| Agent frameworks' bespoke tool registries | Framework-specific tool abstractions | You're all-in on one framework | You want cross-host portability |

**Rule of thumb:** *Use MCP when you have an ecosystem/multiplicity problem or want a clean security boundary; use plain function calling when you have a handful of stable, owned tools.*

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → fix table

| Symptom | Likely cause | Diagnosis | Fix |
|---------|--------------|-----------|-----|
| Handshake hangs | Server slow/never sends initialize result; wrong command | Run server manually; check Inspector | `initializationTimeout`; verify command/args/env; check stderr |
| "Method not found" (-32601) | Calling a capability the server didn't advertise | Inspect `initialize` result capabilities | Gate calls on capabilities |
| Tool "works" but model ignores failures | Treating `isError:true` as success or hiding it from the model | Log the raw `result` | Feed tool errors back to the model as tool_result errors |
| Session works once then breaks (HTTP) | Lost/expired `Mcp-Session-Id` | Inspect request headers | Persist & resend session id; re-init on 404 |
| 400 on every HTTP request | Missing `MCP-Protocol-Version` header (2025-06-18+) | Check request headers | Send negotiated version header |
| stdout "garbage"/parse errors | Server printed non-JSON to stdout | Tail stdout | Treat as server bug; client should log+skip; fix server logging to stderr |
| Server hangs midway | Unread stderr/SSE buffer filled | Check pipe draining | Always drain stderr / consume SSE |
| Tools duplicated / wrong tool called | No namespacing across servers | Inspect tool list | Namespace `serverId__tool` |
| Subscriptions silently stop | Reconnect created a new session; subs not restored | Check reconnect logic | Re-subscribe after reconnect |
| Runaway loop / token burn | Unbounded tool-calling loop | Watch turn count | Cap iterations + token budget |
| Late response after cancel | Cancellation race | Logs show response after cancelled | Drop unmatched/late responses gracefully |
| Version mismatch disconnect | Server only supports older proto | initialize result protocolVersion | Support multiple versions or fail clearly |
| Sampling/elicitation requests hang | Advertised capability but no handler | Server logs "no response" | Implement the reverse handler or stop advertising |

### 9.2 Diagnostic tools & commands
- **MCP Inspector:** `npx @modelcontextprotocol/inspector` (optionally with a server command) to manually handshake, list, and call — and *see the raw JSON-RPC*. First thing to reach for.
- **Run the server by hand** (stdio): execute the exact command from your config in a terminal and paste a hand-written `initialize` JSON to its stdin; confirm a valid result.
- **HTTP:** `curl -i -X POST $URL -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d '{...initialize...}'` and inspect the `Mcp-Session-Id` header and body.
- **Enable verbose logging:** `logging/setLevel` to `debug`; route `notifications/message`.
- **JVM:** wireshark/tcpdump for HTTP; for stdio, log every frame at TRACE.

### 9.3 Real-world incident patterns
- **"The agent deleted files it shouldn't have."** Cause: a destructive tool auto-approved (annotation trusted, no consent). Fix: consent gate on side-effecting tools; default-deny destructive ops.
- **"Costs spiked 20x overnight."** Cause: a server's `tools/list_changed` added dozens of tools; the host re-attached all of them to every model turn, ballooning input tokens, and a retry loop on a flaky tool spun the agent loop. Fix: curate tools, bound the loop, and don't blindly re-expose on changes.
- **"Prompt injection exfiltration."** Cause: a web-scraping tool returned attacker-controlled text instructing the model to call a `send_email` tool with secrets. Fix: treat tool output as untrusted, isolate credentials, require consent for outbound/destructive tools, and consider content provenance.
- **"Token leaked to a third-party server."** Cause: token passthrough — the host handed the same broad OAuth token to a remote MCP server that logged/forwarded it. Fix: audience-restricted tokens per server, OAuth 2.1 resource indicators.
- **"Server froze under load."** Cause: client never drained `stderr`; the OS pipe buffer filled and blocked the server's writes. Fix: always consume stderr.

---

## 10. Interview drill

**Q1. What are the roles in MCP and how do client, host, and server relate?**
*Model answer:* The host is the user-facing app that owns the LLM key, consent, and UI. Inside it, one client object exists per server connection (1:1). The server is a separate process/endpoint exposing tools, resources, and prompts. The client speaks JSON-RPC to the server; the host orchestrates many clients and the model loop.
- *Follow-up: Why 1:1 client↔server?* Isolation: each connection has independent capabilities, lifecycle, and failure domain; simplifies correlation and reconnection.
- *Follow-up: Where does the LLM live?* In/behind the host. MCP doesn't define the model; it standardizes tool/data access around it.

**Q2. Walk me through the initialize handshake.**
*Model answer:* Client opens transport → sends `initialize` (its latest `protocolVersion`, `capabilities`, `clientInfo`) → server replies with negotiated version, its capabilities, `serverInfo`, optional `instructions` (+ session id for HTTP) → client sends `notifications/initialized` → session is READY. Before the result, the client sends nothing but pings; after it, it gates all calls on advertised capabilities.
- *Follow-up: What if versions don't match?* Server returns a version it supports; client disconnects if it can't speak it.
- *Follow-up: What must happen before operational calls?* `notifications/initialized` must be sent; servers should reject earlier operational requests.

**Q3. How do MCP tools get into a model's tool-calling loop?**
*Model answer:* Collect tools from all clients, translate each to the LLM API's tool schema (name → namespaced name, description, `inputSchema` → parameters), send with the user message; on `tool_use`, reverse-map to the owning client, consent-check, `callTool`, map `content` back to a tool_result, feed to the model, loop until plain text or a bounded limit.
- *Follow-up: Why namespace names?* Two servers can share a tool name; the model sees a flat list; also must satisfy the API's name charset/length.
- *Follow-up: How map tool output back?* text→text, image→image block (if multimodal), preserve `isError` so the model can recover.

**Q4. Protocol error vs. tool error — what's the difference and why does it matter?**
*Model answer:* A JSON-RPC `error` means the call itself failed (bad method/params/crash). A tool that runs but fails returns a successful `result` with `isError:true` and the error in `content`, so the model can see and react. Confusing them breaks recovery (you'd hide tool failures from the model or treat recoverable failures as fatal).
- *Follow-up: Should you retry on each?* Protocol/transport errors may be retryable if idempotent; tool errors are usually for the model to handle, not the client to silently retry.

**Q5. (Senior signal) When would you NOT use MCP?**
*Model answer:* When you have a small, stable set of in-process tools you fully own and never intend to share — plain function calling is simpler and cheaper (no transport, no handshake, no extra tokens). MCP earns its cost when you have multiplicity (many tools/servers/hosts), need runtime swappability, third-party ecosystem, or a clean consent/security boundary.
- *Follow-up: What's the hidden cost of MCP at scale?* Tokens: every exposed tool definition is paid on every model turn, and too many tools degrade selection accuracy; plus operational complexity (lifecycles, reconnects, consent).

**Q6. (Senior signal) Design the security/consent model for a host integrating untrusted servers.**
*Model answer:* Treat servers and their outputs as untrusted. Gate side-effecting tools behind explicit, informed consent (server + tool + actual args + plain description), with granularity (once/session/always/deny) and safe defaults. Never trust annotations for security. Sandbox server execution (container, restricted FS/network), enforce least privilege via narrow roots and scoped tokens, avoid token passthrough (audience-restricted per server), treat all tool/resource content as data not instructions (prompt-injection defense), and re-consent on `tools/list_changed`.
- *Follow-up: How defend against prompt injection from tool output?* Isolate credentials, require consent for outbound/destructive actions, delimit untrusted content, don't let tool output silently trigger high-risk tools.
- *Follow-up: What's token passthrough and why is it bad?* Forwarding the same broad token to downstream servers; it enables a confused-deputy and credential leakage. Use per-resource audience-restricted tokens.

**Q7. How do notifications and subscriptions work on the client side?**
*Model answer:* Notifications are id-less JSON-RPC messages arriving anytime after READY; the client dispatches by method. `*_changed` → re-list and refresh. Subscriptions (`resources/subscribe`, only if `resources.subscribe` advertised) cause `notifications/resources/updated` for a URI; the client re-reads and updates context; `unsubscribe` stops it. Subscriptions don't survive reconnect, so re-subscribe.
- *Follow-up: How handle progress?* Opt in with a `progressToken` in `_meta`; receive `notifications/progress`; may reset timeout but keep a hard cap.

**Q8. (Senior signal) How do you manage many server connections robustly in production?**
*Model answer:* Registry keyed by serverId with client, capabilities, tool cache, and status; isolate failures so one dead server doesn't break a turn; concurrent startup; per-server concurrency caps/backpressure; ping-based health; reconnect with backoff+jitter and re-subscribe; hot-reload on config change; bound and curate exposed tools; structured logging/metrics/tracing per call.
- *Follow-up: What about non-idempotent calls during a reconnect?* Their state is unknown; surface to the user instead of blindly retrying.
- *Follow-up: How keep token cost sane?* Curate/filter tools per task; don't auto-expose everything on `list_changed`.

**Q9. stdio vs. Streamable HTTP — when each?**
*Model answer:* stdio for local servers: host spawns a child, communicates over stdin/stdout, lowest latency, inherits host env for auth, but one process per client and no resumability. Streamable HTTP for remote/multi-tenant: a URL endpoint, OAuth/headers, SSE streaming with `Last-Event-ID` resumability and `Mcp-Session-Id` sessions; preferred over the deprecated two-endpoint HTTP+SSE.
- *Follow-up: What header is mandatory on HTTP from 2025-06-18?* `MCP-Protocol-Version` on every request after negotiation.

**Q10. What can a server ask the client to do (reverse direction) and what must the client implement?**
*Model answer:* Sampling (`sampling/createMessage`) — server delegates an LLM call to the host; Roots (`roots/list`) — client tells server its allowed roots; Elicitation (`elicitation/create`) — server asks the user for structured input. The client must advertise these in capabilities AND implement handlers; otherwise dependent servers hang. All must respect consent and host control (host always decides on sampling; user fills elicitation).
- *Follow-up: Why route sampling through the host?* The host keeps the API key, applies policy/consent, chooses the model from `modelPreferences`, and controls what context is shared.

**Q11. How do you debug a server that connects but no tools appear?**
*Model answer:* Check the initialize result's capabilities (no `tools` key = none), then `tools/list` and follow `nextCursor` pagination (a single page bug hides tools), verify namespacing didn't drop them, and use the MCP Inspector to see raw traffic. Confirm you sent `notifications/initialized` (some servers gate listing on it).
- *Follow-up: What if stdout has parse errors?* The server printed non-JSON to stdout (must use stderr for logs); client should log+skip and not crash.

**Q12. (Senior signal) The agent burned huge cost and looped. Diagnose and fix.**
*Model answer:* Likely an unbounded tool-calling loop plus too many exposed tools (token cost per turn) and retries on a flaky tool. Fix: cap iterations and a token budget, curate/dynamically filter tools, don't blindly re-attach on `list_changed`, make tool errors visible to the model (so it stops instead of retrying), and add per-server rate limits.
- *Follow-up: How prevent silent re-expansion of tools?* On `tools/list_changed`, diff and possibly re-consent; cap total tools attached to the model.

---

## 11. Glossary

- **Annotations (tool):** Optional behavior hints (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) for UX; not security guarantees.
- **Capability negotiation:** The handshake step where each side declares what it supports; clients gate behavior on it.
- **Client:** The in-host connector object; one per server connection; speaks MCP.
- **Consent:** Explicit user approval before (especially) side-effecting tool calls.
- **Context window:** Max tokens a model can process at once; tool/resource output competes for it.
- **Cursor (pagination):** Opaque token to fetch the next page of a list; loop until absent.
- **Elicitation:** Server→client→user request for structured input mid-operation (`elicitation/create`, since 2025-06-18).
- **Function/tool calling:** LLM feature to emit structured calls to described functions; MCP feeds and routes these.
- **Host:** The user-facing app orchestrating clients, the model, consent, and UI; owns the LLM key.
- **`initialize` / `notifications/initialized`:** The handshake request and its completing notification.
- **`instructions`:** Server-provided guidance the host should inject for the model.
- **`isError`:** Field on a tool result marking a tool-level (not protocol-level) failure.
- **JSON-RPC 2.0:** The request/response/notification wire format MCP uses.
- **JSON Schema:** Schema language describing tool input/output; maps to LLM tool params.
- **LSP (Language Server Protocol):** Editor↔tooling protocol that inspired MCP.
- **`Mcp-Session-Id`:** HTTP header carrying the session id for Streamable HTTP.
- **`MCP-Protocol-Version`:** HTTP header carrying the negotiated version (required 2025-06-18+).
- **`_meta`:** Open extension bag on messages; standard use is `progressToken`.
- **Namespacing:** Rewriting tool names per server (`serverId__tool`) to avoid clashes and satisfy API name rules.
- **Notification:** Id-less JSON-RPC message with no response.
- **OAuth 2.1 / PKCE:** Delegated-auth standard / code-interception defense used for remote MCP auth.
- **Ping:** Liveness/keepalive request either side can send.
- **Progress token:** Client-supplied token to opt into `notifications/progress`.
- **Prompt (MCP):** User-selected parameterized message template (`prompts/list`, `prompts/get`).
- **Resource:** Application-controlled readable, URI-addressed context (`resources/list`, `resources/read`).
- **Resource template:** RFC 6570 URI template to construct resource URIs dynamically.
- **Roots:** Client-declared filesystem/URI boundaries advisory to the server.
- **RPC:** Calling a function in another process as if local.
- **Sampling:** Server→host request to run an LLM completion (`sampling/createMessage`).
- **Server:** Separate process/endpoint exposing tools/resources/prompts.
- **SSE (Server-Sent Events):** One-way HTTP streaming (server→client) used by HTTP transports.
- **stdio:** Transport using a child process's stdin/stdout (stderr for logs).
- **Streamable HTTP:** Current HTTP transport: single endpoint, POST + optional SSE, sessions, resumability.
- **Subscription:** Watching a resource for changes (`resources/subscribe` → `notifications/resources/updated`).
- **Token passthrough (anti-pattern):** Forwarding the same broad token to downstream servers.
- **Tool (MCP):** Model-controlled function with side effects (`tools/list`, `tools/call`).
- **Tool poisoning / rug-pull:** Malicious or silently-changed tool definitions/behavior.
- **Transport:** How bytes move (stdio or Streamable HTTP); separate from the protocol.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Roles:** Host (owns key, consent, UI) ▷ Client (1 per server) ▷ Server (tools/resources/prompts).
**Wire:** JSON-RPC 2.0 — request (has id) / response (id + result|error) / notification (no id).
**Two error layers:** JSON-RPC `error` = protocol failed; `result.isError:true` = tool ran & failed (model sees it).
**Lifecycle:** open transport → `initialize` → server result (version/caps/`instructions`/sessionId) → `notifications/initialized` → READY → operate → shutdown (close stdin/SIGTERM, or HTTP DELETE).
**Discovery:** `tools/list`, `resources/list` (+ `templates/list`), `prompts/list` — **follow `nextCursor`**.
**Invoke:** `tools/call`, `resources/read`, `prompts/get`.
**Primitives:** Tools = model-controlled (consent!); Resources = app-controlled (read); Prompts = user-controlled.
**Reverse:** Sampling (server→host LLM), Roots (client→server), Elicitation (server→user) — advertise + implement.
**Notifications:** `*_changed` → re-list; `resources/updated` → re-read (needs subscribe); `progress` (opt-in token); `message` (logs).
**Transports:** stdio (local child, fastest) | Streamable HTTP (remote, SSE, `Mcp-Session-Id`, `MCP-Protocol-Version` header 2025-06-18+). Legacy HTTP+SSE = deprecated.
**LLM loop:** collect→translate (namespace + `inputSchema`)→model→on tool_use: reverse-map, consent, callTool, map result back (preserve isError)→loop, **bounded** (max iters + token budget).
**Security:** consent on side-effects; annotations ≠ security; untrusted output ≠ instructions (prompt injection); sandbox servers; least-privilege roots; audience-restricted tokens (no passthrough); re-consent on changes.
**Top anti-patterns:** confusing error layers; calls before `initialized`; ignoring `nextCursor`; unbounded loop; trusting hints; re-handshake per request; not draining stderr/SSE.
**Default Java path:** `McpClient.sync/async(transport)` + `requestTimeout`/`initializationTimeout` + change/sampling consumers; or **Spring AI** for auto-discovery + auto loop.
**Debug first move:** `npx @modelcontextprotocol/inspector`.

### 12.2 Self-test (no answers — recall practice)
1. Trace every JSON-RPC message exchanged from process spawn to the first successful `tools/call`, including which side sends each and what must *not* be sent before `notifications/initialized`.
2. A remote server intermittently returns HTTP 404 on requests that worked five minutes ago. List the likely causes and the exact client recovery steps, including what state you must rebuild.
3. Design the consent and namespacing layer for a host connecting to three servers, two of which expose a tool named `search`, one of which has a destructive `delete_record`. Specify defaults and the per-call decision flow.
4. Explain precisely how you map an MCP `tools/call` result that contains `[text, image, resource_link]` back into (a) an Anthropic tool_result and (b) an OpenAI tool message, and what you do if the model isn't multimodal.
5. Your agent's token cost tripled and latency doubled after a server sent `notifications/tools/list_changed`. Walk through diagnosis and the concrete fixes, naming the specific limits/caps you'd add.
6. Enumerate every reverse-direction request a server can make, what your client must advertise and implement for each, and the consent/host-control rule that applies.
7. Give three distinct prompt-injection attack paths through MCP tool/resource output and the client-side mitigation for each.

---

*End of chapter. This document targets the `2025-06-18` MCP spec where versioned; behaviors marked "version-specific" should be re-verified against the spec version you negotiate at runtime.*

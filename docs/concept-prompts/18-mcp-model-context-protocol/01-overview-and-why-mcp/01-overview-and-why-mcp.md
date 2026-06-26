# MCP — Model Context Protocol: Overview & Why MCP Exists

> A definitive engineering-handbook chapter for senior backend developers (Java/JVM-leaning) who want to fully master *why* MCP exists, what it standardizes, and where it fits — from first principles to deep internals.

---

## 1. Overview & where it fits

### 1.1 What MCP is in one sentence

**MCP (Model Context Protocol) is an open, JSON-RPC 2.0–based protocol — introduced by Anthropic in November 2024 — that standardizes how AI applications (the "hosts") connect language models to external tools, data sources, and prompts through a uniform client/server interface.** Instead of writing a one-off integration every time a new model or a new tool appears, you write the integration *once* against the protocol, and any MCP-speaking model can use any MCP-speaking tool server.

> **Adjacent term — JSON-RPC 2.0.** JSON-RPC is a lightweight *remote procedure call* (RPC) convention encoded in JSON. A client sends a JSON object naming a `method` (e.g. `"tools/call"`) plus `params`, and the server replies with a JSON object containing either a `result` or an `error`, correlated by an `id` field. "2.0" is the specific version of that spec (from 2010). It is transport-agnostic — it says nothing about *how* the bytes travel (stdin/stdout pipes, HTTP, WebSockets); it only fixes the *shape* of the request and response envelopes. MCP picks JSON-RPC 2.0 as its message format and then layers its own transports and method names on top.

### 1.2 The problem it solves — the M×N integration explosion

Imagine you have:

- **M** AI applications / models / agent frameworks — Claude Desktop, your internal chatbot, a coding assistant in your IDE, a customer-support agent, a data-analysis copilot, etc.
- **N** tools and data sources you want those AIs to reach — your Postgres database, GitHub, Jira, Google Drive, an internal pricing service, a Kubernetes cluster, a vector store, Slack, and so on.

If every application integrates with every tool **directly and bespokely**, you need up to **M × N** integrations. With 6 applications and 10 tools, that is up to **60** separate connectors, each with its own auth handling, error handling, schema definitions, and maintenance burden. Worse, the work is duplicated: the GitHub integration in your coding assistant and the GitHub integration in your support agent are *different code* doing the *same thing*, because each app speaks GitHub in its own idiosyncratic way.

MCP collapses this to **M + N**:

- Each **tool/data source** is wrapped *once* in an **MCP server** (N servers).
- Each **application** implements an **MCP client** *once* (M clients).
- Any client can now talk to any server because they share one protocol.

```
   BESPOKE (M × N)                         MCP (M + N)

  App1 ─┬─ GitHub                 App1 ─┐                 ┌─ GitHub-server
  App1 ─┼─ Postgres               App2 ─┤   one shared    ├─ Postgres-server
  App1 ─┴─ Jira                   App3 ─┼── MCP protocol ─┼─ Jira-server
  App2 ─┬─ GitHub                       │                 ├─ Drive-server
  App2 ─┼─ Postgres               (M clients)             └─ ... (N servers)
  App2 ─┴─ Jira
  ... (M × N edges)              edges = M + N, not M × N
```

This is the **central economic argument** for MCP: it converts an O(M×N) integration cost into O(M+N) by inserting a standard interface in the middle. Every adjacent benefit — reuse, a shared ecosystem, faster onboarding of new tools — flows from this one structural change.

### 1.3 The "USB-C / LSP for AI" analogy

Two analogies are repeated everywhere in MCP discussion; both are accurate and worth internalizing.

- **"USB-C for AI applications."** Before USB-C, every device had its own connector — a drawer full of chargers and cables. USB-C standardized the *physical and electrical interface* so any compliant device works with any compliant cable/port. MCP is the USB-C *port* for AI: a standard "socket" through which models plug into tools and data, regardless of who built either side. (This is Anthropic's own framing.)

- **"LSP for AI tools."** This analogy resonates most with engineers.

  > **Adjacent term — LSP (Language Server Protocol).** LSP, introduced by Microsoft in 2016 for VS Code, solved an identical M×N problem in the *developer-tooling* world. Before LSP, every editor (VS Code, IntelliJ, Vim, Emacs — the "M") needed a custom plugin for every language's smarts: autocomplete, go-to-definition, find-references, diagnostics (Python, Rust, Go, Java — the "N"). That is M×N plugins. LSP defined a JSON-RPC protocol so each language ships *one* "language server," and each editor implements *one* LSP client; any editor then gets rich support for any language. MCP is consciously modeled on LSP — same JSON-RPC foundation, same M+N collapse, same client/server split — but for connecting *AI models* to *capabilities* instead of *editors* to *language intelligence*. If you understand LSP, you already understand MCP's shape.

### 1.4 When you reach for MCP

You reach for MCP when **more than one AI application needs to use the same tool**, or when **you want a tool to be usable by AI applications you don't control** (e.g., you ship a product and want Claude Desktop, Cursor, and others to integrate it). You also reach for it when you want to **decouple** the lifecycle of your tool integrations from the lifecycle of your agent code — the MCP server can be updated, redeployed, and versioned independently.

You do *not* necessarily reach for it for a single app calling a single API once — there, a direct function call is simpler (covered in depth in §8).

### 1.5 One-paragraph mental model

> MCP is a thin, JSON-RPC 2.0 contract between a **host application** (which embeds one or more **clients**) and **servers** (which expose **tools**, **resources**, and **prompts**). The host orchestrates an LLM; when the model wants to do something in the world, the host's client sends a JSON-RPC request to the relevant server, the server executes it against the real system, and returns structured results that the host feeds back to the model. The protocol fixes *the handshake, the message shapes, the capability negotiation, and the three primitive types* — and deliberately leaves *what the tools actually do* entirely up to server authors. Think "LSP, but the client is an AI agent and the servers expose business capabilities instead of code intelligence."

---

## 2. Foundations from first principles

This section builds the concept up from zero. We define every core term as it appears.

### 2.1 What an LLM can and cannot do on its own

A large language model (LLM) is, at its core, a function that maps an input sequence of tokens to a probability distribution over the next token. It is *stateless* and *sandboxed*: it has no inherent ability to read a file, query a database, call an API, or remember anything beyond the text in its current context window.

> **Adjacent term — context window.** The context window is the maximum amount of text (measured in *tokens* — sub-word chunks; roughly 0.75 words each in English) the model can attend to at once. Everything the model "knows" in a turn must fit here: the system prompt, the conversation, any retrieved documents, and any tool definitions. As of 2024–2025, windows range from a few thousand tokens to ~200K (Claude) and 1M+ (some Gemini models). The window is finite and every token costs latency and money, which is *why* you don't just dump every tool and database into the prompt.

To be useful in real systems, an LLM must be *connected* to the outside world: it needs to fetch live data and take actions. That connection is the entire subject of MCP.

### 2.2 Function calling / tool use — the precursor

The first standard way to connect an LLM to the world is **function calling**, also called **tool use**.

**How tool use works (vendor-native, no MCP yet):**

1. You, the application developer, describe one or more *tools* to the model in a structured format — typically each tool has a `name`, a natural-language `description`, and a JSON Schema describing its `parameters`.

   > **Adjacent term — JSON Schema.** JSON Schema is a vocabulary for describing the structure of JSON data: types (`string`, `integer`, `object`), required fields, enums, ranges, nested shapes. The model uses it to know *what arguments a tool expects*, and the runtime uses it to validate the model's output before executing.

2. The model, given a user request, may respond not with prose but with a structured **tool call**: "call `get_weather` with `{"city": "Bengaluru"}`."
3. The model **cannot execute anything itself.** Your application code receives that tool call, runs the actual function (hits a weather API), and gets a result.
4. Your app sends the result back into the conversation as a **tool result** message.
5. The model reads the result and produces its final answer (or another tool call). This loop — *model → tool call → execute → result → model* — is the **agentic loop**.

This is powerful, but the *tool definitions and execution code live inside each application*. If three apps want weather, three apps re-implement the weather tool. That is the M×N problem again, now at the tool level.

### 2.3 The leap to MCP — packaging tools behind a reusable server

MCP's core idea: **take the tool definitions and their execution logic and move them out of the application into a standalone, reusable "server."** The application keeps only a generic **client** that knows how to *discover* and *invoke* whatever tools a server offers — it does not hard-code any specific tool.

So the relationship is:

> **Function/tool calling is the lower-level mechanism (model emits a structured call, app executes it). MCP is a higher-level standardization that packages tools behind a reusable, discoverable, network-accessible server and defines the protocol by which any client discovers and calls them.** MCP does not replace tool calling — under the hood, the host still presents the server's tools to the model as tool definitions and still runs the agentic loop. MCP standardizes *where the tools come from* and *how they're invoked*, not the fundamental model-calls-tool idea.

### 2.4 The three actors: Host, Client, Server

MCP defines a precise three-role architecture. Getting these right is foundational.

| Role | What it is | Examples | Cardinality |
|---|---|---|---|
| **Host** | The user-facing AI application that orchestrates the LLM and manages user interaction, security, and consent. Owns the LLM connection. | Claude Desktop, Cursor, an internal agent app you build | 1 host process |
| **Client** | A connector *inside* the host. Each client maintains a **1:1 stateful session with exactly one server.** The host spins up one client per server it connects to. | An SDK-created client object | N clients per host (one per server) |
| **Server** | A standalone program exposing capabilities (tools/resources/prompts) over MCP. Wraps a real system. | A GitHub MCP server, a Postgres MCP server | M servers total |

The crucial, often-missed point: **a client talks to exactly one server, and the host runs many clients.** This 1:1 client-server pairing keeps each session isolated and stateful (important for security and for capability negotiation).

> **Adjacent term — stateful session.** A "stateful" connection means both sides remember context across messages within the connection: they've completed a handshake, negotiated capabilities, and agreed on a protocol version. Contrast with stateless HTTP where each request stands alone. MCP sessions are stateful, which is why there is an explicit `initialize` handshake (see §3).

### 2.5 The three server primitives

An MCP server exposes capabilities as up to three **primitives**. This is the vocabulary you must know cold.

1. **Tools** — *model-controlled actions.* Functions the LLM can decide to call to do something or fetch something dynamically (e.g., `create_issue`, `run_query`, `send_email`). These are the workhorses and the closest analog to classic function calling. The model chooses when to invoke them (with host/user consent).

2. **Resources** — *application-controlled data.* Read-only (or read-mostly) data the server can expose for context: a file's contents, a database row, a document, a log. Resources are identified by URIs and are meant to be selected/attached by the *application or user*, not autonomously called by the model. Think "files you can drag into context."

3. **Prompts** — *user-controlled templates.* Reusable, parameterized prompt templates / workflows the server offers (e.g., a "summarize this PR" prompt). Typically surfaced to the user as slash-commands or menu items they explicitly choose.

| Primitive | Controlled by | Analogy | Typical trigger |
|---|---|---|---|
| **Tool** | Model (with consent) | A POST/RPC action | Model decides mid-loop |
| **Resource** | Application/User | A GET / a file | User attaches or app loads |
| **Prompt** | User | A macro / slash command | User selects it |

There are also **client-offered primitives** the *server* can request *back* from the host — most importantly **sampling** (the server asks the host to run an LLM completion on its behalf) and **roots** and **elicitation** — but those are advanced and covered in §7. For an overview, internalize the three server primitives first.

### 2.6 The protocol stack in layers

```
┌─────────────────────────────────────────────┐
│ Host application (orchestrates LLM, consent)  │  ← your agent / Claude Desktop
├─────────────────────────────────────────────┤
│ MCP Client(s) (one per server, stateful)      │  ← from MCP SDK
├─────────────────────────────────────────────┤
│ Protocol layer: capability negotiation,       │
│ primitives (tools/resources/prompts),         │  ← MCP spec
│ lifecycle, JSON-RPC 2.0 message shapes        │
├─────────────────────────────────────────────┤
│ Transport layer: how bytes move               │  ← stdio | Streamable HTTP
│ (stdio pipes  OR  Streamable HTTP / SSE)      │
└─────────────────────────────────────────────┘
```

The **protocol layer** is stable conceptually; the **transport layer** is where MCP has evolved fastest (the original HTTP+SSE transport was superseded by "Streamable HTTP" in the 2025 spec — see §7.5). Both transports carry the same JSON-RPC messages.

---

## 3. How it works internally

This is the heart of the document. We trace the full lifecycle, the message shapes, the control flow, and the data flow, step by step.

### 3.1 The connection lifecycle (state machine)

An MCP session moves through these phases:

```
          ┌────────────┐  initialize (request) ──►  ┌────────────┐
 host ───►│ CONNECTING │                              │  SERVER    │
          └────────────┘  ◄── initialize (result) ── └────────────┘
                 │
                 ▼  (client sends "initialized" notification)
          ┌────────────┐
          │ INITIALIZED │  ◄── normal operation: tools/list, tools/call,
          │  (OPERATING)│      resources/read, prompts/get, notifications…
          └────────────┘
                 │  shutdown / transport close
                 ▼
          ┌────────────┐
          │   CLOSED    │
          └────────────┘
```

**Phase 1 — Initialization (the handshake).** Mandatory and always first.

1. The **client sends an `initialize` request** containing:
   - `protocolVersion` — the MCP spec version the client speaks (e.g. `"2025-06-18"`, a date-based version string).
   - `capabilities` — what the *client* supports (e.g., whether it can do `sampling`, `roots`, `elicitation`).
   - `clientInfo` — name/version of the host application.

2. The **server responds** with its own `protocolVersion`, its `capabilities` (which of tools/resources/prompts it offers, and whether each supports `listChanged` notifications), and `serverInfo`.

   > **Adjacent term — capability negotiation.** Rather than assuming both sides support every feature, MCP has each side *declare* what it can do during the handshake. Features not declared must not be used. This lets old clients talk to new servers and vice versa — forward/backward compatibility without breaking. It is exactly how TLS negotiates cipher suites or HTTP negotiates content encoding.

3. The client sends an **`initialized` notification** (a JSON-RPC message with no `id`, expecting no reply) to confirm it's ready. Only now may normal requests flow.

If `protocolVersion` mismatches and can't be reconciled, the connection fails fast here.

**Phase 2 — Operation.** The bulk of the session. The client discovers and invokes primitives:

- `tools/list` → server returns tool definitions (name, description, `inputSchema`).
- `tools/call` → client invokes a tool by name with arguments; server executes and returns content.
- `resources/list`, `resources/read`, `resources/subscribe` → discover and fetch data.
- `prompts/list`, `prompts/get` → discover and instantiate prompt templates.
- **Notifications** flow either way without responses — e.g. `notifications/tools/list_changed` tells the client the tool set changed and it should re-list.

**Phase 3 — Shutdown.** Either side closes the transport (e.g., host kills the subprocess, or the HTTP connection ends). There is no heavyweight goodbye for stdio; closing the streams is the signal.

### 3.2 The JSON-RPC message envelope

Every MCP message is one of three JSON-RPC 2.0 shapes.

**Request** (expects a response; has an `id`):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_weather",
    "arguments": { "city": "Bengaluru" }
  }
}
```

**Response — success** (`result`) or **error** (`error`), correlated by `id`:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{ "type": "text", "text": "32°C, partly cloudy" }],
    "isError": false
  }
}
```
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": { "code": -32602, "message": "Invalid params: 'city' is required" }
}
```

> **Adjacent term — JSON-RPC error codes.** JSON-RPC reserves a set of negative integer codes: `-32700` parse error, `-32600` invalid request, `-32601` method not found, `-32602` invalid params, `-32603` internal error, and the `-32000..-32099` range for application-defined server errors. Knowing these helps you read MCP failures directly off the wire.

**Notification** (no `id`, no response expected):
```json
{
  "jsonrpc": "2.0",
  "method": "notifications/tools/list_changed"
}
```

Note a subtlety MCP inherits from JSON-RPC: **tool-execution failures vs protocol failures are different.** A tool that runs but returns a business error (e.g. "record not found") typically returns a *successful* JSON-RPC response with `isError: true` in the result content, so the model can see and reason about it. A malformed request or an unknown method returns a JSON-RPC `error`. This separation is deliberate: the LLM should learn from tool errors, but should never see protocol-plumbing errors.

### 3.3 End-to-end control flow of one agent turn

Trace what happens when a user asks an MCP-enabled host "What's the weather in Bengaluru, and file a ticket if it's over 30°C?"

1. **Host startup / connect.** Host launches/loads each configured server, creates one client each, performs the `initialize` handshake, and calls `tools/list` on each server. It now has a merged catalog of tools (e.g. `get_weather` from a weather server, `create_issue` from a Jira server).
2. **Tool exposure to the model.** The host translates MCP tool definitions into the LLM's native tool-use format and includes them in the model request along with the user's message.
3. **Model decides.** The model emits a tool call: `get_weather({"city":"Bengaluru"})`.
4. **Consent & routing.** The host checks policy/consent (maybe auto-allows reads), identifies which client owns `get_weather`, and that client sends a `tools/call` JSON-RPC request to the weather server.
5. **Server executes.** The weather server calls the real weather API, formats the result, returns `content`.
6. **Result to model.** The host injects the tool result back into the conversation; the model now sees "32°C".
7. **Second tool call.** Because 32 > 30, the model emits `create_issue({...})`. This is a *write*, so the host may prompt the user to approve before the Jira client sends `tools/call`.
8. **Execute & finalize.** Jira server creates the ticket, returns the issue key; host feeds it back; model produces the final natural-language answer citing the new ticket.

The model never speaks MCP and never touches the network. The **host** is the bridge between the model's tool-use protocol and MCP. This separation is exactly why the model can be swapped (Claude, GPT-class, local Llama) without changing servers — the *host* adapts.

### 3.4 Data flow and trust boundaries

```
 User ⇄ Host ⇄ LLM            (untrusted text in/out of model)
        │
        ├─ Client A ⇄ stdio/HTTP ⇄ Server A ⇄ real system A
        ├─ Client B ⇄ stdio/HTTP ⇄ Server B ⇄ real system B
        └─ ...
```

Two trust boundaries matter:
- **Model ↔ Host:** the model's output (tool calls) is *untrusted*; the host must validate and gate it (consent, allow-lists).
- **Host ↔ Server:** the server runs real actions; if the server is third-party, *it* is a trust boundary too (a malicious server could exfiltrate data via tool descriptions — see §6/§9 "tool poisoning").

### 3.5 Discovery is dynamic

Unlike hard-coded function calling, MCP tool discovery happens at runtime via `tools/list`, and the set can change mid-session (the server emits `notifications/tools/list_changed`). This enables servers that adapt their toolset by context — but it also means the model's available capabilities are not fixed at design time, which has security and testing implications (§6).

---

## 4. The complete toolkit

### 4.1 The protocol methods (the "API")

These are the JSON-RPC methods defined by MCP. (Method names are stable across recent spec versions; advanced/optional ones flagged.)

| Method | Direction | Purpose | Key params | Returns |
|---|---|---|---|---|
| `initialize` | client→server | Handshake; negotiate version & capabilities | `protocolVersion`, `capabilities`, `clientInfo` | server capabilities + `serverInfo` |
| `notifications/initialized` | client→server | Signal ready (notification) | — | — |
| `ping` | either | Liveness check | — | empty result |
| `tools/list` | client→server | Discover tools | `cursor` (pagination) | array of tool defs |
| `tools/call` | client→server | Execute a tool | `name`, `arguments` | `content[]`, `isError` |
| `notifications/tools/list_changed` | server→client | Tool set changed | — | — |
| `resources/list` | client→server | Discover resources | `cursor` | array of resource descriptors |
| `resources/read` | client→server | Read a resource | `uri` | resource `contents[]` |
| `resources/templates/list` | client→server | Discover URI templates | — | template list |
| `resources/subscribe` / `unsubscribe` | client→server | Watch a resource for changes | `uri` | — |
| `notifications/resources/updated` | server→client | A subscribed resource changed | `uri` | — |
| `notifications/resources/list_changed` | server→client | Resource set changed | — | — |
| `prompts/list` | client→server | Discover prompt templates | `cursor` | array of prompt defs |
| `prompts/get` | client→server | Instantiate a prompt | `name`, `arguments` | messages |
| `notifications/prompts/list_changed` | server→client | Prompt set changed | — | — |
| `sampling/createMessage` | server→client | Server asks host to run an LLM completion | messages, model prefs | model completion |
| `roots/list` | server→client | Server asks client for filesystem "roots" it may access | — | array of roots |
| `notifications/roots/list_changed` | client→server | Roots changed | — | — |
| `elicitation/create` | server→client | Server asks the user (via host) for structured input | schema, message | user-provided values |
| `logging/setLevel` | client→server | Configure server log verbosity | `level` | — |
| `notifications/message` | server→client | Server log/diagnostic message | `level`, `data` | — |
| `completion/complete` | client→server | Argument autocompletion for prompts/resources | ref, argument | suggestions |
| `notifications/progress` | either | Progress updates for long operations | `progressToken`, `progress` | — |
| `notifications/cancelled` | either | Cancel an in-flight request | `requestId` | — |

> **Adjacent term — pagination cursor.** When a list could be large, the server returns a `nextCursor` token; the client passes it back to fetch the next page. This is opaque-cursor pagination — the client treats the cursor as a black box, exactly like AWS/GitHub API pagination.

### 4.2 The transports

| Transport | When to use | How it works | Notes |
|---|---|---|---|
| **stdio** | Local servers as subprocesses (default for desktop tools) | Host spawns the server process; JSON-RPC messages flow over the process's **stdin/stdout**, newline-delimited; `stderr` is for logs | Zero network setup; inherits no auth; one server per process; lowest latency |
| **Streamable HTTP** | Remote/hosted servers, multi-client | Single HTTP endpoint; client POSTs JSON-RPC; server may stream responses via Server-Sent Events when needed; supports session resumption | Introduced in the **2025-03-26** spec, replacing the older "HTTP+SSE" two-endpoint transport. Supports auth (OAuth 2.1) |
| **HTTP+SSE (legacy)** | Older remote servers | Separate SSE endpoint for server→client, POST for client→server | **Deprecated** in favor of Streamable HTTP; you'll still see it in 2024-era servers |

> **Adjacent term — SSE (Server-Sent Events).** SSE is a simple one-way streaming protocol over plain HTTP: the server keeps the response open and pushes `data:` lines as they become available. It's lighter than WebSockets and works through most proxies. MCP uses it so a server can stream partial results / progress without the client polling.

> **Adjacent term — stdin/stdout/stderr.** Every process has three standard streams: standard input (where it reads), standard output (where it writes results), and standard error (where it writes diagnostics). stdio transport cleverly reuses stdout for protocol traffic — which is *why MCP servers must never `print()` debug text to stdout*; that would corrupt the JSON-RPC stream. Logs go to stderr.

### 4.3 Official SDKs and tools

| SDK / Tool | Language / purpose | Notes |
|---|---|---|
| **TypeScript SDK** | First-class; reference implementation | `@modelcontextprotocol/sdk` |
| **Python SDK** | First-class; very common for servers; includes **FastMCP** high-level API | Decorator-based server authoring |
| **Java SDK** | Official, maintained in collaboration with the **Spring AI** team | The JVM-native path; integrates with Spring AI / Spring Boot starters |
| **Kotlin SDK** | Official | JVM/Android |
| **C#/.NET, Go, Rust, Swift, Ruby, PHP** | Official or community | Ecosystem expanding fast |
| **MCP Inspector** | Official debugging UI | Connect to a server, list/call tools interactively — the single most useful dev tool (see §9) |
| **Claude Desktop config** | `claude_desktop_config.json` | Where you register stdio servers for Claude Desktop |
| **mcp.json / project configs** | Per-host server registries | Varies by host (Cursor, VS Code, etc.) |

> **Adjacent terms — Spring AI / Spring Boot starters.** **Spring AI** is the Spring ecosystem's framework for building AI/LLM apps on the JVM (abstractions for chat models, embeddings, tool calling, and MCP). A **Spring Boot starter** is a curated dependency bundle (`spring-boot-starter-*`) that auto-configures a feature with sensible defaults so you add one dependency and it "just works." Spring AI ships MCP client/server starters so a Spring Boot app can expose or consume MCP with minimal boilerplate.

### 4.4 Key configuration knobs (representative)

| Knob | Where | Default / typical | Effect |
|---|---|---|---|
| `command` + `args` | host server config (stdio) | none | how to launch the server subprocess |
| `env` | host server config | inherits/none | environment vars (often secrets/tokens) passed to server |
| `protocolVersion` | `initialize` | latest mutually supported | which spec semantics apply |
| capability flags (`tools`, `resources`, `prompts`, `sampling`, `roots`, `elicitation`) | handshake | only what's declared | gate which features may be used |
| `listChanged` per capability | handshake | often `true` | whether dynamic list-change notifications are sent |
| OAuth settings (issuer, scopes) | Streamable HTTP servers | none | remote auth (2025 spec) |
| `progressToken` | per-request meta | none | enables progress notifications for that call |

(Exact field names/defaults are version- and SDK-specific — confirm against the spec version your stack targets.)

---

## 5. Code examples by use case

These span different real scenarios. Java is shown first where relevant (the reader's ecosystem), then Python/TS for breadth and because much of the live ecosystem is there. All are adaptable, not toys.

### 5.1 (Java) A minimal MCP server exposing a tool — Spring AI

A Spring Boot service exposing a `get_weather` tool over MCP. With the MCP server starter, you annotate a method and Spring AI registers it as an MCP tool.

```java
// build.gradle (excerpt)
// implementation 'org.springframework.ai:spring-ai-starter-mcp-server'
// (artifact names track Spring AI's GA; confirm the version you target)

import org.springframework.ai.tool.annotation.Tool;       // marks a method as an LLM-callable tool
import org.springframework.ai.tool.annotation.ToolParam;  // documents a parameter for the schema
import org.springframework.stereotype.Service;

@Service
public class WeatherTools {

    private final WeatherApiClient api; // your real downstream client

    public WeatherTools(WeatherApiClient api) {
        this.api = api;
    }

    // The @Tool description IS what the model reads to decide when to call this.
    // Treat it as part of your prompt engineering — be precise and unambiguous.
    @Tool(description = "Get the current weather for a city. Returns temperature in Celsius and conditions.")
    public WeatherResult getWeather(
            @ToolParam(description = "City name, e.g. 'Bengaluru'") String city) {
        // Validate input defensively — the model can pass anything.
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city must be provided");
        }
        var w = api.fetch(city); // real downstream call
        return new WeatherResult(w.tempCelsius(), w.conditions());
    }

    // Returned POJO becomes structured JSON content for the model.
    public record WeatherResult(double tempCelsius, String conditions) {}
}
```

```java
// Registering the tool with the MCP server (Spring AI auto-config picks up @Tool beans,
// but you can also register explicitly):
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class McpConfig {
    @Bean
    MethodToolCallbackProvider weatherToolProvider(WeatherTools weatherTools) {
        // Exposes all @Tool-annotated methods on the bean as MCP tools.
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherTools)
                .build();
    }
}
```

What matters: the `description` strings *are* the model-facing contract; input validation is mandatory because tool arguments are model-generated and therefore untrusted.

### 5.2 (Java) An MCP client consuming a server — Spring AI

```java
// implementation 'org.springframework.ai:spring-ai-starter-mcp-client'

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;

@Service
public class AssistantService {

    private final ChatClient chatClient;

    // Spring AI auto-configures MCP clients from application.yml server entries
    // and exposes their tools as a ToolCallbackProvider you hand to the ChatClient.
    public AssistantService(ChatClient.Builder builder,
                            ToolCallbackProvider mcpTools) {
        this.chatClient = builder
                .defaultToolCallbacks(mcpTools) // model can now call MCP server tools
                .build();
    }

    public String ask(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content(); // runs the agentic loop, invoking MCP tools as the model requests
    }
}
```

```yaml
# application.yml — register a local stdio MCP server
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            weather:
              command: "python"
              args: ["-m", "weather_server"]   # or a packaged binary
```

The host code knows *nothing* about weather. It just gives the model "the tools from whatever servers are configured." Swap servers in YAML, no code change — that is the M+N payoff in practice.

### 5.3 (Python, FastMCP) A server exposing a tool, a resource, and a prompt

```python
# pip install "mcp[cli]"
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("docs-server")

# TOOL — model-controlled action
@mcp.tool()
def search_docs(query: str, limit: int = 5) -> list[str]:
    """Search internal documentation. Returns matching snippets."""
    return _vector_search(query, limit)   # your retrieval logic

# RESOURCE — application/user-controlled data, addressed by URI
@mcp.resource("docs://{doc_id}")
def get_doc(doc_id: str) -> str:
    """Return the full text of a documentation page by id."""
    return _load_doc(doc_id)

# PROMPT — user-controlled reusable template (surfaces as a slash command)
@mcp.prompt()
def summarize_doc(doc_id: str) -> str:
    return f"Summarize the document at docs://{doc_id} in 5 bullet points."

if __name__ == "__main__":
    mcp.run()  # defaults to stdio transport
```

This one file demonstrates all three primitives and the discipline that each maps to a different *control owner* (model / app / user).

### 5.4 (Python) A raw client call — what the host does under the hood

```python
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def run():
    params = StdioServerParameters(command="python", args=["-m", "docs_server"])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()                  # the handshake (§3.1)
            tools = await session.list_tools()          # tools/list
            print([t.name for t in tools.tools])
            result = await session.call_tool(            # tools/call
                "search_docs", {"query": "rate limiting", "limit": 3})
            print(result.content)
```

This is the explicit version of what Spring AI / Claude Desktop do for you: initialize, list, call.

### 5.5 (TypeScript) A server with input validation and a structured error

```ts
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod"; // schema validation library

const server = new McpServer({ name: "billing", version: "1.0.0" });

server.tool(
  "refund_order",
  { orderId: z.string().regex(/^ord_[a-z0-9]+$/), amountCents: z.number().int().positive() },
  async ({ orderId, amountCents }) => {
    const order = await db.findOrder(orderId);
    if (!order) {
      // Business error: return isError so the MODEL can see & reason about it.
      return { content: [{ type: "text", text: `No order ${orderId}` }], isError: true };
    }
    await payments.refund(order, amountCents);
    return { content: [{ type: "text", text: `Refunded ${amountCents}¢ on ${orderId}` }] };
  }
);

await server.connect(new StdioServerTransport());
```

Note the deliberate split (§3.2): a missing order is `isError: true` (model-visible), while a malformed `orderId` is rejected by the `zod` schema before your code runs (a protocol-level invalid-params error).

### 5.6 (Config) Registering a server in Claude Desktop

```json
// claude_desktop_config.json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxx" }
    },
    "postgres": {
      "command": "uvx",
      "args": ["mcp-server-postgres", "postgresql://localhost/mydb"]
    }
  }
}
```

This is the most common *first* contact engineers have with MCP: dropping a server into a host's config and instantly giving Claude new powers — no code at all. It viscerally demonstrates the "plug-in" thesis.

### 5.7 (Java) A read-only Resource server pattern

```java
@Service
public class LogResources {
    // Resources are read-mostly context, addressed by URI, chosen by app/user.
    @Tool(description = "List available service log files as resources.") // (some stacks model resources via dedicated APIs)
    public List<String> listLogUris() {
        return logStore.list().stream().map(id -> "logs://" + id).toList();
    }
}
```

(Exact resource APIs differ by SDK/version; the principle — URI-addressed, app-selected, read-mostly — is what to remember.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Tool count bloats the context window.** Every tool definition (name, description, schema) is injected into the model's prompt *every turn*. 80 tools across 6 servers can consume thousands of tokens, raising latency and cost and *degrading* tool-selection accuracy (the model gets confused among similar tools). **Mitigation:** expose only the tools a given host actually needs; consider per-task tool filtering; keep descriptions tight.
- **stdio is lowest-latency** (in-process pipes, no network); **Streamable HTTP** adds network + TLS + possibly auth round-trips. Pick stdio for local, HTTP for remote/shared.
- **Batch where possible.** A tool that returns 50 rows in one call beats 50 single-row calls — each round trip is a model turn (expensive).
- **Streaming/progress** (`notifications/progress`) keeps long tools (a big query, a build) responsive instead of a silent multi-second hang.

### 6.2 Correctness & concurrency

- **The model output is non-deterministic.** Validate every argument server-side; never trust the model to honor your schema even if you declared it.
- **Idempotency.** The agentic loop may retry; design write tools to be idempotent (accept an idempotency key) so a retried `create_issue` doesn't create duplicates.
- **Concurrency model.** A stdio server is typically one process handling one client; an HTTP server may handle many concurrent sessions — make server state thread-safe and per-session where needed.

### 6.3 Security — the most important section

MCP expands the attack surface because the model can now *act*, and servers can be third-party. Key threats and defenses:

- **Prompt injection → tool abuse.** Untrusted content (a web page, an email the model reads) can contain instructions that trick the model into calling a destructive tool. **Defense:** human-in-the-loop consent for write/dangerous tools; least-privilege tokens; clear separation of trusted vs untrusted content.
- **Tool poisoning / rug pulls.** A malicious or compromised server can embed hidden instructions in *tool descriptions* (which go straight into the prompt) or silently change a tool's behavior after you've approved it. **Defense:** pin/verify server versions; review tool descriptions; prefer first-party/audited servers; isolate untrusted servers.
- **Confused-deputy / token scope.** The server often holds powerful credentials (a GitHub PAT, a DB connection). If over-scoped, a single tricked tool call can do far more than intended. **Defense:** least-privilege credentials per server; scoped OAuth (2025 spec); never share one omnipotent token.
- **Secrets in config.** Tokens in `claude_desktop_config.json` / `env` are plaintext on disk. **Defense:** OS secret stores, short-lived tokens, env injection at runtime.
- **stdout corruption = covert channel + breakage.** Servers writing to stdout break the protocol; ensure logs go to stderr.
- **Supply chain.** `npx -y some-mcp-server` runs arbitrary code from the internet. **Defense:** vet, pin versions, run in sandboxes/containers.

> **Adjacent term — confused deputy.** A classic security flaw where a privileged component (the "deputy" — here, the MCP server with its credentials) is tricked by a less-privileged party (the model, possibly steered by an attacker) into misusing its authority. The fix is always least privilege + explicit authorization checks.

> **Adjacent term — human-in-the-loop (HITL).** A control pattern where a person must approve consequential actions before they execute. In MCP hosts this typically means a confirmation dialog before write/destructive tool calls.

### 6.4 Observability

- **Structured logging on the server (to stderr)** with request ids; the MCP `logging` capability can forward server logs to the host.
- **Capture every tool call + arguments + result + latency** at the host — this is your audit trail and your debugging gold.
- **Trace IDs** propagated into downstream systems so a model-initiated DB query is attributable.
- **MCP Inspector** for interactive inspection during dev (§9).

### 6.5 Cost

- Tokens for tool definitions (recurring, every turn) + tokens for tool results (can be huge if a tool dumps a 10K-row table). **Cap and paginate tool outputs**; summarize server-side; return only what the model needs.

### 6.6 Testability

- **Unit-test the server's tool functions directly** (they're just functions).
- **Contract-test against the protocol** using MCP Inspector or a scripted client (§5.4) — assert `tools/list` shape and `tools/call` results.
- **Mock the host's LLM** so agentic-loop tests are deterministic.

### 6.7 Production hardening checklist

- Least-privilege credentials per server; rotate; short-lived where possible.
- HITL consent on writes; allow-list of permitted tools per host/role.
- Resource/output size caps; pagination; timeouts on every tool.
- Pin server versions; vet third-party servers; sandbox untrusted ones.
- Logs to stderr; structured; with request ids and trace propagation.
- Idempotent writes; retries with backoff at the host.
- Version-pin the `protocolVersion` your stack supports and test handshake compatibility.

### 6.8 Anti-patterns

- **One mega-tool** that takes a free-form "action" string — defeats schemas and the model's ability to select correctly. Prefer many narrow, well-described tools.
- **Dumping every internal API as a tool** — overwhelms the context window and the model. Curate.
- **Vague tool descriptions** — the description *is* the interface; "does stuff with data" guarantees misuse.
- **Trusting model arguments** — always validate.
- **Logging to stdout** in a stdio server — corrupts the stream.
- **One omnipotent credential** shared by all servers — confused-deputy waiting to happen.

---

## 7. Advanced topics & deep internals

### 7.1 Sampling — servers borrowing the host's brain

`sampling/createMessage` lets a **server ask the host to run an LLM completion on its behalf.** Example: a server processing a document needs to summarize a chunk; instead of holding its own API key/model, it asks the host's model. This inverts the usual direction (server→client request) and is gated by client capability + user consent. It enables "agentic servers" without each server shipping its own model credentials, and keeps model choice/cost with the host. Many hosts don't yet implement sampling — check capability negotiation.

### 7.2 Roots — scoping filesystem/URI access

`roots/list` lets the **server ask the client which directories/URIs it's allowed to operate within** (e.g., "you may only touch `/home/user/project`"). It's a least-privilege boundary for filesystem-style servers, declared by the client and refreshable via `notifications/roots/list_changed`.

### 7.3 Elicitation — servers asking the user a question mid-task

`elicitation/create` (added in the 2025-06-18 spec) lets a **server request structured input from the user** through the host mid-execution (e.g., "which environment: staging or prod?"). The server supplies a JSON schema; the host renders a form; the user's answer flows back. This turns one-shot tools into interactive workflows.

### 7.4 Dynamic capabilities & `listChanged`

Servers can change their tool/resource/prompt sets at runtime and emit `notifications/*/list_changed`. Hosts should re-list on receipt. This supports context-dependent toolsets (e.g., a "deploy" tool only appears after you "connect to cluster") but complicates static analysis, caching, and security review — the capability surface is not fixed.

### 7.5 Transport evolution (version-specific — flag this)

- **2024-11-05 (initial):** stdio + **HTTP+SSE** (two endpoints: SSE for server→client, POST for client→server). Stateful but awkward for serverless/load-balanced deployments and for resuming dropped connections.
- **2025-03-26:** introduced **Streamable HTTP** (single endpoint, optional SSE streaming, session ids, resumability) and an **OAuth 2.1**-based authorization framework for remote servers. HTTP+SSE deprecated.
- **2025-06-18:** refinements including **elicitation**, structured tool output, and security/authorization clarifications (e.g., resource indicators to prevent token misuse).

> **Adjacent term — OAuth 2.1.** OAuth 2.1 is a consolidation of OAuth 2.0 best practices (PKCE mandatory, implicit flow removed) for delegated authorization — letting an app act on a user's behalf with scoped, revocable tokens instead of sharing passwords. MCP's remote-auth story standardizes on it so hosted servers can authenticate users/agents properly.

> **Adjacent term — PKCE.** "Proof Key for Code Exchange" — an OAuth extension that protects the authorization-code flow against interception by requiring the client to prove it started the flow (via a hashed code verifier). Mandatory in OAuth 2.1.

**Because MCP is young and evolving fast, always pin and verify the spec version your host and servers negotiate; features and even transports differ across these dates.**

### 7.6 Structured output & content types

Tool results are a `content[]` array of typed items: `text`, `image`, `audio`, and **embedded/resource** references. The 2025 spec added **structured tool output** (machine-readable JSON alongside human-readable text) so downstream code can parse results reliably rather than scraping prose.

### 7.7 Composition & nesting

A host can connect to many servers simultaneously; a single agent turn can chain tools across servers. There are also patterns where a server is itself an MCP *client* of other servers (a gateway/aggregator), composing capabilities — powerful but it deepens the trust chain.

### 7.8 Pagination, cancellation, progress, ping

Production-grade details: opaque `cursor` pagination on all `*/list` methods; `notifications/cancelled` to abort a long call (with the corresponding `requestId`); `notifications/progress` keyed by a `progressToken`; `ping` for liveness. These are the "boring" plumbing that separates a demo server from a robust one.

---

## 8. Tradeoffs & decision frameworks

### 8.1 MCP vs bespoke direct integration vs vendor-native tool calling

| Dimension | Vendor-native tool calling (no MCP) | Bespoke direct integration | MCP |
|---|---|---|---|
| Integration cost | Per app, per tool (M×N) | Per app, per tool (M×N) | Per tool once + per app once (M+N) |
| Reuse across apps | None | None | High (one server, many hosts) |
| Reuse across models | Low (rewrite per model API) | Low | High (host adapts; servers neutral) |
| Setup overhead | Minimal | Minimal | Protocol + server process |
| Discovery | Static (hard-coded) | Static | Dynamic (`tools/list`) |
| Best for | One app, few tools | One-off, perf-critical, tight coupling | Many apps/tools, shared ecosystem, third-party distribution |
| Operational complexity | Low | Low–medium | Medium (extra process/transport, versioning) |
| Security surface | Smaller | Smaller | Larger (third-party servers, dynamic tools) |

### 8.2 stdio vs Streamable HTTP

| | stdio | Streamable HTTP |
|---|---|---|
| Locality | Local (subprocess) | Local or remote |
| Latency | Lowest | Network-bound |
| Auth | Inherited/none | OAuth 2.1 supported |
| Multi-client | One client/process | Many concurrent sessions |
| Deployment | Bundled with host | Hosted service |
| Use when | Desktop tools, dev, single user | Shared/hosted servers, teams, SaaS |

### 8.3 Use-when / avoid-when rules

**Use MCP when:**
- Multiple hosts/agents must share the same tools.
- You want third parties (Claude Desktop, Cursor, others) to integrate your tool without custom work.
- You want tool integrations versioned/deployed independently of agent code.
- You're standardizing tool access across an org to stop reinventing connectors.

**Avoid / defer MCP when:**
- A single app calls a single API a couple of times — a direct function call is simpler and faster to ship.
- Ultra-low-latency, tightly-coupled paths where an extra process/transport hop isn't justified.
- You can't yet meet the security bar (consent, least-privilege, server vetting) for letting a model act through servers.
- The spec churn is a problem for your stability requirements — though pinning versions mitigates this.

### 8.4 Where MCP sits relative to adjacent ideas

- **vs plain RAG (retrieval-augmented generation):** RAG injects retrieved *text* into context; MCP can do that (resources) *and* let the model take *actions* (tools). MCP is broader.
  > **Adjacent term — RAG.** Retrieval-Augmented Generation: fetch relevant documents (often via vector search) and prepend them to the prompt so the model answers from up-to-date, specific data rather than only its training memory.
- **vs OpenAPI/plugins:** OpenAPI describes REST APIs; some ecosystems generate tools from OpenAPI. MCP is purpose-built for the *model-tool* interaction (consent, sampling, prompts, resources, dynamic discovery) and is model/host-agnostic.
- **vs agent frameworks (LangChain, etc.):** Those orchestrate the *agentic loop* in your app; MCP standardizes the *tool interface* the loop calls. They're complementary — frameworks increasingly consume MCP servers.

### 8.5 Adoption & ecosystem state (flag: fast-moving)

- **Introduced by Anthropic** in **November 2024** as an open standard with SDKs and reference servers.
- **Broad adoption through 2025:** major AI labs and tool vendors announced MCP support; IDEs and agent platforms added MCP client support; thousands of community servers appeared (GitHub, Slack, Postgres, filesystem, Puppeteer, and countless internal ones).
- **Governance:** open spec with public SDKs; evolving via dated spec releases (2024-11-05 → 2025-03-26 → 2025-06-18 → …).
- **Caveat:** the ecosystem and spec are **evolving fast**; transports, auth, and optional primitives have changed within months. Treat specific features as version-gated and verify against current docs for your target spec version. (Where exact, current adoption figures matter, confirm against primary sources rather than relying on a snapshot here.)

---

## 9. Failure modes & debugging

### 9.1 Common failure modes and how they look

| Symptom | Likely cause | Diagnosis | Fix |
|---|---|---|---|
| Server "connects" then nothing; client hangs | Server wrote logs to **stdout**, corrupting JSON-RPC | Inspect raw stdio; you'll see non-JSON lines | Route all logs to **stderr** |
| `initialize` fails / version error | Protocol-version mismatch | Check negotiated `protocolVersion` in logs | Align client/server spec versions |
| Tools not appearing in host | `tools/list` empty, capability not declared, or server crashed at startup | Run **MCP Inspector** against the server | Fix registration / capability flags |
| Model never calls the right tool | Vague/overlapping tool descriptions; too many tools | Read the descriptions; count tools in context | Tighten descriptions; reduce tool count |
| `tools/call` returns invalid-params (`-32602`) | Model produced args violating schema | Inspect the offending arguments | Loosen/clarify schema; validate & return a model-readable `isError` |
| Duplicate side effects (two tickets) | Non-idempotent write + agentic retry | Correlate request ids/timestamps | Idempotency keys |
| Remote server 401/403 | OAuth/token scope/expiry | Check auth flow + token scopes | Re-auth; correct scopes |
| Context overflow / high latency/cost | Too many tools or huge tool outputs | Measure token usage per turn | Filter tools; cap/paginate outputs |
| Stale tools after server change | Missing/ignored `list_changed` notification | Check notification handling | Re-list on `notifications/tools/list_changed` |
| Data exfiltration / unexpected actions | Prompt injection / tool poisoning | Audit tool descriptions + call logs | HITL, least privilege, vet servers |

### 9.2 The debugging toolkit

- **MCP Inspector** — connect to any server, list and call tools/resources/prompts interactively, see raw JSON-RPC. First thing to reach for.
- **Raw transport capture** — for stdio, run the server manually and pipe a hand-written `initialize` + `tools/list` to see exactly what it emits. For HTTP, capture with `curl`/proxies.
- **Host-side call logs** — every tool call, args, result, latency, and the negotiated capabilities/version.
- **stderr logs** from the server, structured, with request ids.
- **Replay** — capture a problematic session and replay tool calls against the server in isolation.

### 9.3 Representative real-world incident patterns

- **The stdout-logging bricked server.** A team added `print("starting…")` to a Python stdio server; the host silently failed because the first stdout bytes weren't valid JSON-RPC. Resolution: move to `logging` on stderr. (This is the single most common beginner failure.)
- **The over-scoped token.** An MCP DB server was configured with a superuser connection string "to make it work." A prompt-injection-steered tool call ran a destructive statement. Resolution: read-only role, separate write tools behind HITL.
- **Tool sprawl degraded accuracy.** An internal host wired 12 servers (~90 tools); the model started picking wrong/overlapping tools and latency ballooned. Resolution: curated, role-scoped tool sets per use case.
- **Transport-version drift.** A server built against the legacy HTTP+SSE transport stopped working with a host upgraded to Streamable HTTP. Resolution: upgrade the server SDK / pin compatible versions. (Version-specific — illustrates §7.5.)

---

## 10. Interview drill

### Q1. What problem does MCP solve, in structural terms?
**Model answer:** It collapses the **M×N** integration explosion — every AI app × every tool/data source — into **M+N** by inserting a standard JSON-RPC protocol between hosts (which embed clients) and servers (which wrap tools). Each tool is wrapped once as a server; each app implements the client once; any client can talk to any server.
- *Follow-up: Why is M+N strictly better only past a threshold?* Because MCP adds fixed overhead (a protocol, a server process, versioning). For tiny M and N (one app, one tool) the bespoke cost is lower; the payoff appears as either M or N grows or when reuse/third-party distribution matters.
- *Follow-up: What other system solved the same shape?* LSP (editors × languages → one language server + one editor client) and, by analogy, USB-C (devices × cables).
- *Follow-up: Does MCP eliminate per-tool work?* No — someone still writes each server once (the "+N"), and each host writes a client once (the "+M"); it eliminates the *multiplicative* duplication.

### Q2. How does MCP relate to ordinary function/tool calling?
**Model answer:** Tool calling is the lower-level mechanism: the model emits a structured call and the *app* executes it. MCP packages those tools behind a reusable, discoverable server and standardizes discovery (`tools/list`) and invocation (`tools/call`). Under the hood the host still presents server tools to the model as native tool definitions and runs the same agentic loop — MCP standardizes *where tools come from* and *how they're invoked*, not the model-calls-tool idea itself.
- *Follow-up: So who actually runs the agentic loop?* The host. The model never speaks MCP or touches the network.
- *Follow-up: Can you use MCP with non-Anthropic models?* Yes — servers are model-agnostic; the host adapts MCP tool defs to whatever model's tool-use format.

### Q3. Walk through the connection lifecycle.
**Model answer:** `initialize` request (client → server) with `protocolVersion` + `capabilities` + `clientInfo`; server responds with its version/capabilities/serverInfo (capability negotiation); client sends `initialized` notification; then operation (`tools/list`, `tools/call`, resources, prompts, notifications); finally shutdown by closing the transport.
- *Follow-up: Why an explicit handshake?* Sessions are stateful; both sides must agree on version and features for forward/backward compatibility.
- *Follow-up: What's the difference between a notification and a request?* A notification has no `id` and expects no response (e.g., `initialized`, `list_changed`); a request has an `id` and is correlated to a response.

### Q4. Name the three server primitives and who controls each.
**Model answer:** **Tools** (model-controlled actions), **Resources** (application/user-controlled read-mostly data, URI-addressed), **Prompts** (user-controlled templates/slash-commands). The control-owner distinction is the point.
- *Follow-up: Give a wrong-primitive example.* Exposing a destructive "delete" as a *resource* — resources should be read-mostly and app/user-selected, not model-invoked actions.
- *Follow-up: What client-offered primitives exist?* Sampling (server borrows the host's LLM), roots (allowed paths), elicitation (ask the user mid-task).

### Q5. Why must a stdio MCP server never write to stdout?
**Model answer:** stdio transport carries the JSON-RPC protocol over stdout; any non-protocol bytes corrupt the stream and silently break the session. Logs go to stderr.
- *Follow-up: How would you detect this in the field?* Run the server manually and watch raw stdout, or use MCP Inspector — you'll see non-JSON lines.

### Q6. (Senior signal) When would you NOT use MCP?
**Model answer:** When the integration is a single app calling a single API a couple of times (bespoke function call is simpler/faster), in ultra-low-latency tightly-coupled paths where an extra process/transport hop isn't justified, when you can't meet the security bar (consent, least privilege, server vetting), or when spec churn conflicts with stability needs (mitigable by version pinning). MCP earns its overhead through reuse, third-party distribution, and decoupled lifecycles.
- *Follow-up: What's the hidden cost people underestimate?* Token cost and accuracy degradation from tool sprawl — every tool def is in-context every turn.
- *Follow-up: How do you decide the threshold?* Roughly: more than one host or a desire for external integration → MCP; otherwise bespoke.

### Q7. (Senior signal) What new security risks does MCP introduce and how do you mitigate them?
**Model answer:** The model can now *act*, and servers may be third-party. Risks: prompt-injection-driven tool abuse, tool poisoning / rug pulls (malicious tool descriptions or silently changed behavior — descriptions go straight into the prompt), confused-deputy via over-scoped server credentials, plaintext secrets in config, and supply-chain risk from `npx`-fetched servers. Mitigations: human-in-the-loop on writes, least-privilege scoped credentials per server, version pinning + server vetting + sandboxing, OS secret stores, and strict trusted/untrusted content separation.
- *Follow-up: Explain confused deputy here.* The server (privileged deputy) is tricked by a less-privileged party (the model, possibly steered) into misusing its credentials; fix with least privilege + explicit authorization.
- *Follow-up: How does the 2025 spec help?* OAuth 2.1 + PKCE for remote auth, scoped tokens, and resource-indicator guidance to prevent token misuse.

### Q8. (Senior signal) MCP vs OpenAPI-generated tools vs an agent framework — how do they relate?
**Model answer:** OpenAPI describes REST APIs and can *generate* tools, but isn't model-aware (no consent, sampling, prompts, resources, dynamic discovery). Agent frameworks orchestrate the agentic loop in *your* app. MCP standardizes the *model↔tool interface* in a model/host-agnostic way and is complementary: frameworks increasingly consume MCP servers, and you can back an MCP server with an OpenAPI service.
- *Follow-up: Is MCP a replacement for RAG?* No — MCP can deliver retrieval (resources) and also enable actions (tools); RAG is one use case it can serve.

### Q9. What are the transports, and why did they change?
**Model answer:** **stdio** (local subprocess, stdin/stdout, lowest latency) and **Streamable HTTP** (remote, single endpoint, optional SSE streaming, sessions, OAuth 2.1), which replaced the legacy two-endpoint **HTTP+SSE** because that was awkward for serverless/load-balanced deployments and connection resumption.
- *Follow-up: Why version-pin?* Because transports and features changed across dated specs (2024-11-05 → 2025-03-26 → 2025-06-18); a server on the old transport can break a host on the new one.

### Q10. How does MCP handle large lists and long-running tools?
**Model answer:** Opaque `cursor` pagination on `*/list` methods; `notifications/progress` (keyed by a `progressToken`) for long operations; `notifications/cancelled` to abort by `requestId`; `ping` for liveness. Plus output caps to avoid blowing the context window.
- *Follow-up: Why cap tool output?* Tool results are injected into the model's context — a 10K-row dump explodes tokens, latency, and cost.

### Q11. Distinguish a tool business-error from a protocol error on the wire.
**Model answer:** A business error returns a *successful* JSON-RPC response with `isError: true` in the content so the model can see and reason about it; a protocol error (unknown method, invalid params) returns a JSON-RPC `error` object (e.g. `-32601`, `-32602`) that the model shouldn't see.
- *Follow-up: Why the split?* The model should learn from tool failures but never from plumbing errors; mixing them confuses the model and leaks internals.

### Q12. (Senior signal) You're standardizing tool access across an org's many agents. Pitch MCP and its risks.
**Model answer:** MCP gives M+N economics: write each connector once as a server, every agent reuses it; tools version/deploy independently; new agents onboard instantly; you can expose tools to external hosts too. Risks to manage: a larger security surface (consent, least-privilege creds, server vetting), tool-sprawl token/accuracy costs (curate per role), and spec churn (pin versions). Net: worth it once you have multiple agents or external integration needs.
- *Follow-up: First three controls you'd mandate?* HITL on writes, least-privilege scoped credentials per server, and a server allow-list with version pinning.

---

## 11. Glossary

- **Agentic loop** — the cycle model → tool call → execute → result → model, repeated until the model produces a final answer.
- **Capability negotiation** — the handshake step where client and server declare which features they support so only mutually-supported features are used.
- **Client** — an in-host connector maintaining a 1:1 stateful session with exactly one server.
- **Confused deputy** — a security flaw where a privileged component is tricked into misusing its authority on behalf of a less-privileged caller.
- **Context window** — the maximum tokens a model can attend to at once; all prompt/tools/results must fit.
- **Elicitation** — server-initiated request (via host) for structured user input mid-task (`elicitation/create`).
- **Function calling / tool use** — the base mechanism where a model emits a structured call and the app executes it.
- **Host** — the user-facing AI application orchestrating the LLM, embedding clients, and enforcing consent/security.
- **Human-in-the-loop (HITL)** — requiring human approval before consequential actions.
- **Idempotency** — property where repeating an operation has the same effect as doing it once (key for safe retries).
- **JSON-RPC 2.0** — the JSON request/response RPC convention MCP uses for message envelopes.
- **JSON Schema** — vocabulary for describing JSON structure; used for tool input schemas.
- **LSP (Language Server Protocol)** — Microsoft's JSON-RPC protocol decoupling editors from language intelligence; MCP's direct inspiration.
- **M×N / M+N** — the integration-count explosion (apps × tools) collapsed by a standard protocol to apps + tools.
- **MCP Inspector** — official interactive debugging tool for MCP servers.
- **Notification** — a JSON-RPC message with no `id`, expecting no response (e.g., `list_changed`).
- **OAuth 2.1** — consolidated OAuth for delegated, scoped, revocable authorization (PKCE mandatory); MCP's remote-auth basis.
- **PKCE** — Proof Key for Code Exchange; OAuth extension protecting the auth-code flow from interception.
- **Primitive** — an MCP capability type: tool, resource, or prompt (server-side); sampling/roots/elicitation (client-side).
- **Prompt (primitive)** — user-controlled reusable prompt template/workflow exposed by a server.
- **Progress token** — identifier enabling `notifications/progress` for a long-running call.
- **RAG (Retrieval-Augmented Generation)** — prepending retrieved documents to a prompt for grounded answers.
- **Resource (primitive)** — application/user-controlled, URI-addressed, read-mostly data the server exposes.
- **Roots** — client-declared directories/URIs a server is permitted to operate within.
- **Sampling** — server-initiated request for the host to run an LLM completion on the server's behalf (`sampling/createMessage`).
- **Server** — standalone program exposing tools/resources/prompts over MCP, wrapping a real system.
- **SSE (Server-Sent Events)** — one-way HTTP streaming used by MCP HTTP transports.
- **Spring AI** — Spring's framework for LLM apps on the JVM, with official MCP client/server support.
- **Spring Boot starter** — a dependency bundle that auto-configures a feature.
- **Stateful session** — a connection where both sides retain negotiated context across messages.
- **stdio transport** — JSON-RPC over a subprocess's stdin/stdout; lowest-latency, local.
- **Streamable HTTP** — the 2025 single-endpoint HTTP transport (optional SSE, sessions, OAuth) replacing legacy HTTP+SSE.
- **Tool (primitive)** — model-controlled action the LLM can invoke (with consent).
- **Tool poisoning** — embedding malicious instructions in tool descriptions or silently changing tool behavior.
- **Token** — sub-word unit of text the model processes; the unit of context size and billing.
- **Transport** — how MCP bytes move (stdio or Streamable HTTP); independent of the JSON-RPC message layer.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **What:** open JSON-RPC 2.0 protocol (Anthropic, Nov 2024) connecting AI hosts to tools/data via clients↔servers.
- **Why:** turn **M×N** integrations into **M+N**. Analogies: **USB-C for AI**, **LSP for AI tools**.
- **Roles:** **Host** (1, owns LLM + consent) → **Clients** (N, one per server, stateful) → **Servers** (M, wrap real systems).
- **Server primitives:** **Tools** (model-controlled actions), **Resources** (app/user-controlled, URI, read-mostly), **Prompts** (user-controlled templates). Client-side: **sampling, roots, elicitation**.
- **Lifecycle:** `initialize` → capabilities negotiated → `initialized` notification → operation (`tools/list`, `tools/call`, …) → close.
- **Errors:** business error = success response + `isError:true` (model-visible); protocol error = JSON-RPC `error` (`-32601` method-not-found, `-32602` invalid-params, …).
- **Transports:** **stdio** (local, stdin/stdout, lowest latency — never log to stdout) and **Streamable HTTP** (remote, OAuth 2.1; replaced legacy HTTP+SSE in 2025-03-26).
- **vs tool calling:** MCP = reusable, discoverable packaging of tools; host still runs the agentic loop and presents tools to the model.
- **Use when:** many hosts/tools, third-party distribution, decoupled lifecycles. **Avoid when:** single app+single API, ultra-low-latency, unmet security bar.
- **Top risks:** prompt injection, tool poisoning, confused-deputy via over-scoped creds, plaintext secrets, supply chain, tool sprawl (token/accuracy). **Top controls:** HITL on writes, least-privilege per-server creds, version pin + vet + sandbox, logs to stderr, idempotent writes, cap/paginate output.
- **Spec is version-dated and fast-moving** (2024-11-05 → 2025-03-26 → 2025-06-18 → …): pin and verify.
- **JVM path:** official **Java/Kotlin SDKs** + **Spring AI** MCP client/server starters.
- **Dev tool:** **MCP Inspector**.

### 12.2 Self-test (no answers — active recall)

1. Explain, with a worked count, how MCP converts an M×N integration problem into M+N, and identify exactly where the "+M" and "+N" costs live.
2. Trace a single agent turn end-to-end for "summarize the latest GitHub issue and email me," naming each MCP method, who initiates it, and where consent applies.
3. A teammate's stdio Python server "connects but exposes no tools." List your diagnostic steps in order and the three most likely root causes.
4. Distinguish tools, resources, and prompts by control-owner, and give one realistic mis-assignment for each that would be a design smell.
5. Justify choosing bespoke direct integration over MCP for a specific scenario, then describe the one change that would flip your decision to MCP.
6. Describe a tool-poisoning attack via tool descriptions and the layered defenses you'd deploy across host and server.
7. Why did the transport evolve from HTTP+SSE to Streamable HTTP, and what concrete deployment problems did the change address?

---

*End of chapter. This document targets the MCP spec lineage 2024-11-05 → 2025-06-18; because MCP is evolving rapidly, verify version-specific transports, auth, and optional primitives against the spec version your host and servers negotiate.*

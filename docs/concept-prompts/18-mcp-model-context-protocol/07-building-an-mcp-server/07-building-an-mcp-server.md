# Building an MCP Server

> A definitive, exhaustive engineering-handbook chapter for the senior backend developer who wants to fully master building MCP servers — from first principles to deep internals, production hardening, and interview-grade fluency.

---

## 1. Overview & where it fits

### 1.1 What an MCP server is, in one paragraph

The **Model Context Protocol (MCP)** is an open, JSON-RPC-based protocol that standardizes how applications expose **context** and **capabilities** to large language models (LLMs). An **MCP server** is a process you write that publishes three kinds of building blocks — **tools** (functions the model can call to *do* things), **resources** (read-only data the model or app can *read*), and **prompts** (reusable, parameterized message templates) — over a well-defined wire protocol. A separate **MCP host** (e.g., Claude Desktop, an IDE, or your own agent) launches or connects to your server, discovers what it offers, and brokers calls between the model and your server. The mental model: *MCP is to LLM tool/context integration what the Language Server Protocol (LSP) is to editor/language integration* — write the integration once against a standard protocol, and any compliant host can use it.

> **LLM (large language model):** a neural network trained on large text corpora that predicts the next token. On its own it cannot read your files, hit your database, or call your API — it only generates text. MCP is the plumbing that lets a host safely connect those external capabilities to the model.

> **JSON-RPC:** a lightweight remote-procedure-call protocol where requests and responses are JSON objects with fields like `jsonrpc`, `method`, `params`, `id`, and `result`/`error`. MCP uses **JSON-RPC 2.0** as its message format. We define it fully in §2.4.

### 1.2 The problem it solves

Before MCP, every "give the model access to X" integration was bespoke. If you wanted Claude to read your Postgres database, search your wiki, and file Jira tickets, you wrote three custom integrations, each tied to one specific app's plugin format. Swap the app (Claude → an IDE → your own agent) and you rewrote everything. This is the **N×M integration problem**: N hosts × M tools = N×M integrations.

> **N×M problem:** when N consumers each need to integrate with M providers and there is no shared standard, you end up writing N×M point-to-point integrations. A shared protocol collapses this to N+M: each host implements the protocol once, each provider implements it once.

MCP collapses N×M to **N+M**. You build one MCP server for "my company's wiki." Claude Desktop, Cursor, your internal agent, and any future MCP host can all use it without changes.

### 1.3 When you reach for it

Build an MCP server when:

- You want to expose **proprietary or internal capabilities** (databases, APIs, file systems, SaaS tools) to one or more LLM hosts.
- You want a **reusable, host-agnostic** integration rather than a one-off plugin.
- You need the model to **take actions** (write a file, run a query, call an API) in a way the host can mediate, log, and gate behind human approval.
- You want to ship **context providers** (documentation, schemas, logs) that the model can pull on demand instead of stuffing everything into a giant prompt.

Do **not** build an MCP server when a plain function call inside your own single application would do — if there is exactly one consumer and it is your own code, the protocol overhead buys you nothing. MCP earns its keep when the capability is shared, reused, or crosses a process/trust boundary.

### 1.4 The actors

| Actor | Role | Examples |
|---|---|---|
| **Host** | The LLM application the user interacts with; owns the model and the UI; manages one or more clients. | Claude Desktop, Claude Code, Cursor, VS Code, a custom agent |
| **Client** | A connector *inside* the host; maintains a 1:1 stateful session with one server. | The MCP client library embedded in the host |
| **Server** | The process *you* write; exposes tools/resources/prompts. | Your filesystem server, DB server, GitHub server |

A host typically runs **many** clients, each paired with exactly **one** server. The client↔server relationship is 1:1 and stateful for the life of the session.

> **Why a "client" separate from the "host"?** The host is the whole application (model, UI, orchestration). For each server it wants to talk to, it spins up a dedicated client object that owns the connection, capability negotiation, and message correlation for *that one* server. This isolation means one misbehaving server cannot corrupt another's session.

---

## 2. Foundations from first principles

### 2.1 The three primitives (and who controls them)

MCP defines a small, deliberate set of primitives. The key design axis is **who is in control** of invoking each one.

| Primitive | Direction | Controlled by | Mental model | Analogy |
|---|---|---|---|---|
| **Tool** | Server → exposed to model | **Model-controlled** (model decides to call it) | A function with a typed input schema the model can invoke | POST endpoint / RPC |
| **Resource** | Server → exposed to app | **Application-controlled** (host decides what to load) | Read-only addressable data identified by a URI | GET endpoint / file |
| **Prompt** | Server → exposed to user | **User-controlled** (user picks it, e.g., a slash command) | A reusable parameterized message template | Saved query / snippet |

This control-axis matters for design: **tools** are where you put *actions the model should autonomously choose*; **resources** are where you put *data the app or user attaches as context*; **prompts** are where you put *workflows a human deliberately triggers*.

There are also three **client-side primitives** the *server* can call back into (covered in §7): **sampling** (server asks the host to run an LLM completion), **roots** (host tells server which filesystem/URI boundaries it may operate in), and **elicitation** (server asks the host to collect input from the user mid-operation).

### 2.2 What a tool actually is

A tool has:

- A **name** (unique within the server, e.g., `get_weather`).
- A **title** / **description** (human- and model-readable; the model reads this to decide when to call it).
- An **input schema**: a **JSON Schema** object describing the parameters.
- Optionally an **output schema**: a JSON Schema for **structured content** returned.
- **Annotations / hints**: metadata such as `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` that help the host decide how much to gate the call.

> **JSON Schema:** a vocabulary (an IETF spec) for describing the shape of JSON data — types, required fields, enums, ranges, nested objects. MCP uses it so hosts and models know exactly what arguments a tool expects and can validate them. Example: `{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}`.

When the model calls a tool, the result is a list of **content blocks** (text, image, audio, embedded resource) plus an optional `structuredContent` object and an `isError` flag.

### 2.3 What a resource actually is

A resource is identified by a **URI** and returns content. Resources come in two flavors:

- **Direct (static) resources**: fixed URIs like `file:///logs/app.log` or `config://settings`.
- **Resource templates**: URI templates with variables (RFC 6570), e.g., `file:///{path}` or `db://users/{userId}`, which the host can fill in.

> **URI (Uniform Resource Identifier):** a string that identifies a resource, e.g., `file:///etc/hosts` or `https://api.example.com/v1`. MCP resources use URIs as their addressing scheme so the host can request a specific piece of data unambiguously.

> **RFC 6570 URI Template:** a standard for URIs containing variables in braces, like `https://example.com/users/{id}`. The `{id}` is a placeholder the host fills in. MCP resource templates use this so one declaration can serve a whole family of addresses.

Resources can be **read** (`resources/read`), **listed** (`resources/list`), **subscribed to** for change notifications (`resources/subscribe`), and can emit `notifications/resources/updated`.

### 2.4 The wire protocol: JSON-RPC 2.0

Every MCP message is a JSON-RPC 2.0 object. There are three message shapes:

1. **Request** — expects a response; has an `id`.
   ```json
   {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_weather","arguments":{"city":"London"}}}
   ```
2. **Response** — correlates to a request by `id`; carries `result` or `error`.
   ```json
   {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"15°C, rainy"}]}}
   ```
3. **Notification** — fire-and-forget; **no `id`**, no response expected.
   ```json
   {"jsonrpc":"2.0","method":"notifications/initialized"}
   ```

> **Why `id` matters:** the transport can interleave many in-flight requests. The `id` lets the client match each response to the request that produced it (request/response correlation). Notifications have no `id` precisely because nobody is waiting for a reply.

Error responses use a structured `error` object with a numeric `code`, a `message`, and optional `data`. Standard JSON-RPC codes:

| Code | Meaning |
|---|---|
| `-32700` | Parse error (invalid JSON) |
| `-32600` | Invalid request |
| `-32601` | Method not found |
| `-32602` | Invalid params |
| `-32603` | Internal error |
| `-32000` to `-32099` | Server-defined (implementation) errors |

### 2.5 Transports: how bytes move

MCP separates the **protocol** (what messages mean) from the **transport** (how bytes travel). There are two standard transports:

| Transport | Where it runs | How it works | When to use |
|---|---|---|---|
| **stdio** | Local; host spawns server as a subprocess | Messages are newline-delimited JSON over the server's **stdin/stdout**; logs go to **stderr** | Local tools, desktop integrations, dev. **Start here.** |
| **Streamable HTTP** | Remote or local over network | Single HTTP endpoint; POST for client→server, optional **Server-Sent Events (SSE)** stream for server→client | Remote servers, multi-client, web deployments |

> **stdio (standard input/output):** every process has three default streams — stdin (input), stdout (output), stderr (error output). For stdio transport, the host writes JSON-RPC to the server's stdin and reads responses from its stdout. **Critical rule: never write logs or anything non-protocol to stdout** — it corrupts the message stream. Logs go to stderr.

> **SSE (Server-Sent Events):** a one-way HTTP streaming mechanism where the server keeps an HTTP response open and pushes `text/event-stream` data to the client over time. MCP's Streamable HTTP transport uses SSE for server-initiated messages (notifications, sampling requests) while normal client requests use POST.

> **Historical note (version-specific):** earlier MCP revisions defined a separate **HTTP+SSE** transport (two endpoints: one for POST, one long-lived SSE endpoint). The **2025-03-26** revision replaced it with the unified **Streamable HTTP** transport (one endpoint). Many older servers and tutorials still reference the old "SSE transport"; treat that as deprecated.

### 2.6 The lifecycle: initialize → operate → shutdown

Every MCP session follows a strict three-phase lifecycle. This is non-negotiable and you will debug it often.

**Phase 1 — Initialization (handshake):**
1. Client sends `initialize` with its supported **protocol version**, its **capabilities**, and **clientInfo** (name/version).
2. Server responds with the protocol version *it* will use, *its* **capabilities**, **serverInfo**, and optional **instructions**.
3. Client sends the `notifications/initialized` notification to confirm it is ready.

Until step 3 completes, only `initialize`/`ping` traffic is allowed. This is **capability negotiation**: each side advertises what it supports (does the server have tools? resources? prompts? does it support resource subscriptions? does the client support sampling?) so neither side calls something the other cannot handle.

> **Capability negotiation:** at connection time both parties declare a feature map (e.g., server says `{"tools":{"listChanged":true},"resources":{"subscribe":true}}`). The other side must not use a feature unless it was advertised. This is how MCP stays forward/backward compatible — new features are opt-in via capabilities.

> **Protocol version:** a date-stamped string like `2025-06-18`. Client and server each propose a version; if the server doesn't support the client's, it responds with one it does, and the client decides whether to proceed. Mismatches are a common source of "it connects but nothing works" bugs.

**Phase 2 — Operation:** normal request/response and notification traffic — `tools/list`, `tools/call`, `resources/read`, `prompts/get`, etc.

**Phase 3 — Shutdown:** no special message; the transport simply closes. For stdio, the host closes the server's stdin, waits, then sends SIGTERM/SIGKILL if needed. For HTTP, the connection is closed.

### 2.7 Why Python/TypeScript first (even for a Java shop)

This handbook's reader is a Java/JVM engineer, and that matters for how we frame this chapter. As of this writing the **most mature, best-documented official SDKs are TypeScript and Python**. There is an official **Java SDK** (maintained in collaboration with the Spring team, and the basis of **Spring AI's MCP** support), plus **Kotlin**, **C#**, **Go**, **Rust**, and others. For a *first* build, TypeScript or Python gets you to a working server fastest with the least ceremony. **This chapter uses Python (with the `mcp` SDK / FastMCP) as the primary worked language**, then shows the **TypeScript** equivalent for the core server, and finishes with a **Java/Spring AI** section (§7.7) so you can map everything back to the JVM. Pick Python or TS to learn; ship in Java if that's your stack.

> **SDK (software development kit):** a library that implements the protocol's wire format, lifecycle, and transport for you, so you write business logic (your tools/resources) instead of JSON-RPC framing. The official MCP SDKs handle initialization, message correlation, schema validation, and transport.

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, exactly what happens from the moment a host launches your stdio server to the moment a tool result comes back.

### 3.1 Process startup (stdio)

1. The host reads its config (e.g., Claude Desktop's `claude_desktop_config.json`) and finds an entry like `{"command":"python","args":["server.py"]}`.
2. The host **spawns** that command as a child process, wiring up three pipes: the child's stdin, stdout, and stderr.
3. Your server process starts. The SDK installs a **read loop** on stdin and a **write path** on stdout. Your code registers handlers (tools, resources, prompts) *before* the loop starts serving.
4. The server blocks, waiting for the first line on stdin.

> **Subprocess / child process:** a program started by another program. The parent (host) controls the child's (server's) input and output streams. When the parent dies or closes the pipe, the child sees end-of-file on stdin and should shut down cleanly.

### 3.2 The handshake, traced

```
HOST/CLIENT                                   SERVER
   |  initialize (version, capabilities) ----->  |
   |                                             |  validate version, build capability map
   |  <----- initialize result (version, caps)   |
   |  notifications/initialized -------------->   |
   |                                             |  now "operational"
```

Concretely, on stdin the server receives:

```json
{"jsonrpc":"2.0","id":0,"method":"initialize","params":{
  "protocolVersion":"2025-06-18",
  "capabilities":{"sampling":{},"roots":{"listChanged":true}},
  "clientInfo":{"name":"claude-desktop","version":"0.x"}}}
```

The SDK:
1. Parses the JSON line.
2. Checks the requested `protocolVersion` against what it supports; picks a compatible one.
3. Records the **client's** capabilities (so the server knows it can later request `sampling`, etc.).
4. Builds the **server's** capability map by inspecting what you registered (any tools → advertise `tools`; any resources → `resources`; subscriptions supported → `resources.subscribe`).
5. Writes the `initialize` result on stdout.
6. Waits for `notifications/initialized`, then flips an internal flag to "operational."

### 3.3 Discovery: list before call

A well-behaved host calls the `*/list` methods to learn what's available, often caching the result.

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

The server returns the tool catalog, including each tool's `name`, `description`, `inputSchema`, and any annotations:

```json
{"jsonrpc":"2.0","id":1,"result":{"tools":[
  {"name":"get_weather",
   "description":"Get current weather for a city",
   "inputSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}
]}}
```

If your server's catalog changes at runtime (tools added/removed), and you advertised `listChanged:true`, you emit `notifications/tools/list_changed`, and the host re-lists.

### 3.4 A tool call, traced end to end

This is the critical path. Walk it carefully.

```
USER: "What's the weather in London?"
  |
HOST: sends conversation + tool catalog to the MODEL
  |
MODEL: decides to call get_weather, emits a tool_use with {"city":"London"}
  |
HOST: (optionally) asks USER to approve the call  [human-in-the-loop gate]
  |
CLIENT --> SERVER:  tools/call {name:"get_weather", arguments:{city:"London"}}
  |
SERVER: 1. validate arguments against inputSchema
        2. run your handler (call weather API, etc.)
        3. format result as content blocks (+ optional structuredContent)
  |
CLIENT <-- SERVER:  result {content:[{type:"text",text:"London: 15°C, rain"}]}
  |
HOST: feeds tool result back to the MODEL as a tool_result
  |
MODEL: produces final natural-language answer
  |
USER: sees "It's 15°C and raining in London."
```

Key internal facts:

- **The model never talks to your server directly.** The model emits *intent* (a tool_use). The host's client executes the actual `tools/call`. This indirection is what lets the host gate, log, and sandbox.
- **Validation happens twice in spirit:** the host *may* validate arguments against the schema before sending; your SDK validates again on receipt. Defense in depth.
- **The result loops back through the model.** A tool result is not shown to the user raw; the model reads it and decides what to say (and whether to call more tools).

### 3.5 Data flow vs. control flow

- **Control flow** (who decides what runs): User → Model (chooses tool) → Host (gates) → Server (executes) → Model (interprets) → User. The *decision* to call a tool is the model's; the *authority* to execute is the host's.
- **Data flow** (what bytes move): arguments flow client→server; content blocks flow server→client; both are JSON-RPC over the chosen transport.

### 3.6 State machine of a session

```
        initialize req
DISCONNECTED ----------------> INITIALIZING
                                   |
                  initialized note |
                                   v
                              OPERATIONAL <----+
                                   |           | (requests/notifications)
                          transport close      |
                                   |-----------+
                                   v
                              SHUTDOWN
```

Within OPERATIONAL, many requests can be **in flight concurrently**; the SDK tracks them by `id`. A `$/cancelRequest`-style cancellation (`notifications/cancelled` with the request `id`) can abort an in-flight request; the server should stop work and not send a result for a cancelled `id`.

> **In-flight request:** a request that has been sent but not yet answered. Because MCP is asynchronous over a single pipe, multiple requests can be outstanding at once; correlation by `id` keeps them straight.

### 3.7 Streaming, progress, and logging mid-call

During a long tool call, the server can send:

- **Progress notifications** (`notifications/progress`) tied to a `progressToken` the client supplied, to update a progress bar.
- **Log messages** (`notifications/message`) if the client advertised the `logging` capability — structured logs at levels `debug`/`info`/`warning`/`error` etc.

> **Progress token:** an opaque value the client attaches to a request's `_meta.progressToken`; the server echoes it in progress notifications so the client knows which operation the progress belongs to.

### 3.8 What "structured content" buys you

A tool result can include both human-readable `content` (text/images) **and** machine-readable `structuredContent` (a JSON object validated against the tool's `outputSchema`). This lets the host parse results programmatically (e.g., chart the numbers) while the model still gets readable text. If you declare an `outputSchema`, the SDK validates your `structuredContent` against it before sending.

---

## 4. The complete toolkit

This section enumerates the protocol methods, the Python SDK (FastMCP) surface, the TypeScript SDK surface, CLI/tooling, and configuration. Defaults are flagged; where a default is uncertain it is marked **(verify for your SDK version)**.

### 4.1 Protocol methods (the wire vocabulary)

| Method | Type | Direction | Purpose |
|---|---|---|---|
| `initialize` | request | client→server | Start handshake, negotiate version & capabilities |
| `notifications/initialized` | notification | client→server | Confirm client is ready |
| `ping` | request | either | Liveness check |
| `tools/list` | request | client→server | Enumerate tools |
| `tools/call` | request | client→server | Invoke a tool |
| `notifications/tools/list_changed` | notification | server→client | Tool catalog changed |
| `resources/list` | request | client→server | Enumerate static resources |
| `resources/templates/list` | request | client→server | Enumerate resource templates |
| `resources/read` | request | client→server | Read a resource by URI |
| `resources/subscribe` | request | client→server | Subscribe to a resource's changes |
| `resources/unsubscribe` | request | client→server | Stop watching |
| `notifications/resources/updated` | notification | server→client | A subscribed resource changed |
| `notifications/resources/list_changed` | notification | server→client | Resource list changed |
| `prompts/list` | request | client→server | Enumerate prompts |
| `prompts/get` | request | client→server | Render a prompt with arguments |
| `notifications/prompts/list_changed` | notification | server→client | Prompt list changed |
| `completion/complete` | request | client→server | Argument autocompletion for prompts/resources |
| `logging/setLevel` | request | client→server | Set server log verbosity |
| `notifications/message` | notification | server→client | Structured log message |
| `notifications/progress` | notification | either | Progress update for a long op |
| `notifications/cancelled` | notification | either | Cancel an in-flight request |
| `sampling/createMessage` | request | server→client | Ask host to run an LLM completion |
| `roots/list` | request | server→client | Ask host for filesystem/URI roots |
| `notifications/roots/list_changed` | notification | client→server | Roots changed |
| `elicitation/create` | request | server→client | Ask host to collect user input |

### 4.2 Python SDK — FastMCP surface (the high-level API)

The official Python SDK ships a high-level decorator-based API often called **FastMCP**. Core surface:

| Construct | What it does | Key params / notes |
|---|---|---|
| `FastMCP(name, instructions=...)` | Create a server | `name` is the server identity; `instructions` is guidance shown to the host/model |
| `@mcp.tool()` | Register a function as a tool | Name defaults to the function name; description from docstring; input schema inferred from type hints |
| `@mcp.resource("uri://...")` | Register a resource (static or template) | If the URI has `{vars}` matching function params, it's a template |
| `@mcp.prompt()` | Register a prompt | Returns a string or list of messages |
| `Context` (injected param) | Per-request handle | Gives `ctx.info()/debug()/warning()/error()` logging, `ctx.report_progress()`, `ctx.read_resource()`, `ctx.session`, `ctx.request_id` |
| `mcp.run(transport="stdio")` | Start the server | `transport` ∈ `stdio` (default), `streamable-http`, `sse` (deprecated) |
| `mcp.run(transport="streamable-http")` | Run over HTTP | Host/port set via constructor or env in some versions |

Type-hint → JSON Schema mapping (Python): `str`→string, `int`→integer, `float`→number, `bool`→boolean, `list[T]`→array, `dict`→object, `Optional[T]`/`T | None`→nullable, Pydantic `BaseModel`→object with nested schema, `Literal["a","b"]`→enum. Use `pydantic.Field(description=...)` to document individual parameters.

> **Pydantic:** a popular Python library for data validation using type annotations. FastMCP uses it to turn your function's type hints into JSON Schema and to validate incoming arguments. A `Field` lets you attach descriptions, defaults, and constraints (min/max, regex) to each parameter.

### 4.3 TypeScript SDK surface

| Construct | What it does | Notes |
|---|---|---|
| `new McpServer({name, version})` | Create a server | Identity for the handshake |
| `server.registerTool(name, {description, inputSchema, outputSchema, annotations}, handler)` | Register a tool | `inputSchema` defined with **Zod** shapes |
| `server.registerResource(name, uriOrTemplate, {description}, handler)` | Register a resource | Use `ResourceTemplate` for `{vars}` |
| `server.registerPrompt(name, {description, argsSchema}, handler)` | Register a prompt | Returns `{messages:[...]}` |
| `new StdioServerTransport()` | stdio transport | Pass to `server.connect()` |
| `new StreamableHTTPServerTransport({...})` | HTTP transport | For remote serving |
| `await server.connect(transport)` | Start serving | Begins the read loop |

> **Zod:** a TypeScript-first schema declaration and validation library. The TS SDK uses Zod schemas to both define a tool's input shape and validate arguments at runtime, then converts them to JSON Schema for the wire.

### 4.4 Tooling & CLI

| Tool | Command / usage | Purpose |
|---|---|---|
| **MCP Inspector** | `npx @modelcontextprotocol/inspector <cmd> <args>` | Interactive UI to launch, inspect, and call your server's tools/resources/prompts. The primary dev/test tool. |
| **MCP Inspector (CLI mode)** | `npx @modelcontextprotocol/inspector --cli <cmd> ... --method tools/list` | Scriptable/CI-friendly invocation without the web UI |
| **`uv` / `uvx`** | `uv run server.py`, `uvx mcp-server-foo` | Fast Python package runner; common in MCP configs to avoid global installs |
| **`mcp` CLI (Python SDK)** | `mcp dev server.py`, `mcp install server.py` | Dev runner + install into a host (varies by SDK version) |
| **`npx`** | `npx -y @scope/server` | Run a published Node MCP server without installing |
| **Claude Desktop config** | `claude_desktop_config.json` | Where you register stdio servers for Claude Desktop |

### 4.5 Tool annotations (hints)

| Annotation | Type | Default | Meaning |
|---|---|---|---|
| `title` | string | — | Human-friendly display name |
| `readOnlyHint` | bool | `false` | Tool does not modify state |
| `destructiveHint` | bool | `true` | Tool may perform destructive updates (only meaningful if not read-only) |
| `idempotentHint` | bool | `false` | Repeated identical calls have no additional effect |
| `openWorldHint` | bool | `true` | Tool interacts with external entities (the open world / internet) |

These are **hints**, not guarantees — the host uses them to decide how aggressively to gate (e.g., auto-approve read-only, always confirm destructive). Never rely on them for security; enforce authorization server-side.

### 4.6 Capabilities map (what each side advertises)

| Capability | Declared by | Sub-flags | Meaning |
|---|---|---|---|
| `tools` | server | `listChanged` | Server exposes tools; can notify on catalog change |
| `resources` | server | `subscribe`, `listChanged` | Server exposes resources; supports subscriptions / change notices |
| `prompts` | server | `listChanged` | Server exposes prompts |
| `logging` | server | — | Server can emit structured logs |
| `completions` | server | — | Server supports argument autocompletion |
| `sampling` | client | — | Client can fulfill server-initiated LLM completions |
| `roots` | client | `listChanged` | Client provides filesystem/URI roots |
| `elicitation` | client | — | Client can collect user input on the server's behalf |

---

## 5. Code examples by use case

We build several **distinct** servers, not variations of one toy. Primary language: **Python (FastMCP)**. Each is complete enough to run or adapt.

### 5.1 Example A — Minimal "hello tools": a weather + math server (stdio)

The canonical first server: a couple of tools, a resource, and a prompt, on stdio.

```python
# server.py
# Run: python server.py   (host launches this via stdio)
from mcp.server.fastmcp import FastMCP

# 'name' identifies the server in the handshake and in host UIs.
mcp = FastMCP("demo-tools")

@mcp.tool()
def add(a: int, b: int) -> int:
    """Add two integers and return the sum."""  # docstring becomes the tool description
    return a + b  # return value is auto-wrapped as a text content block

@mcp.tool()
def get_weather(city: str) -> str:
    """Get the current weather for a city (stubbed)."""
    # In real life: call an API. Keep it deterministic for the example.
    return f"{city}: 15°C, light rain"

@mcp.resource("config://app")
def app_config() -> str:
    """Static configuration exposed as a readable resource."""
    return "theme=dark\nlocale=en-US"

@mcp.prompt()
def summarize(text: str) -> str:
    """A reusable prompt template the user can invoke."""
    return f"Summarize the following in 3 bullet points:\n\n{text}"

if __name__ == "__main__":
    # stdio is the default; explicit here for clarity.
    mcp.run(transport="stdio")
```

What to notice:
- Type hints (`a: int`) become the input schema; the SDK validates `tools/call` arguments for you.
- The docstring is the model-facing description — **write it for the model**: state *when* to use the tool and *what it returns*.
- `mcp.run()` installs the stdin read loop. Do not print to stdout anywhere else.

### 5.2 Example B — A read-only database query server with structured output

Exposes a SQLite database safely: a templated resource for table schemas and a read-only query tool returning **structured content**.

```python
# db_server.py
import sqlite3
from typing import Any
from pydantic import BaseModel, Field
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("sqlite-explorer")
DB = "app.db"

def _conn() -> sqlite3.Connection:
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row  # rows behave like dicts
    return c

class QueryResult(BaseModel):
    columns: list[str]
    rows: list[dict[str, Any]]
    row_count: int = Field(description="Number of rows returned")

@mcp.resource("schema://tables")
def list_tables() -> str:
    """List all table names in the database."""
    with _conn() as c:
        names = [r["name"] for r in c.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")]
    return "\n".join(names)

@mcp.resource("schema://table/{table}")
def table_schema(table: str) -> str:
    """Return the CREATE statement (schema) for a given table."""
    with _conn() as c:
        row = c.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            (table,)).fetchone()
    return row["sql"] if row else f"-- no such table: {table}"

@mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": False})
def run_query(sql: str) -> QueryResult:
    """Run a READ-ONLY SQL query (SELECT only) and return rows.
    Rejects anything that is not a single SELECT statement."""
    normalized = sql.strip().rstrip(";")
    # Defense in depth: reject obvious mutations. This is NOT a substitute
    # for running with a read-only DB connection in production.
    lowered = normalized.lower()
    if not lowered.startswith("select"):
        raise ValueError("Only SELECT statements are allowed.")
    if ";" in normalized:  # block stacked queries
        raise ValueError("Multiple statements are not allowed.")
    with _conn() as c:
        cur = c.execute(normalized)
        cols = [d[0] for d in cur.description]
        rows = [dict(r) for r in cur.fetchall()]
    # Returning a Pydantic model populates structuredContent AND the
    # SDK derives an outputSchema from the model.
    return QueryResult(columns=cols, rows=rows, row_count=len(rows))

if __name__ == "__main__":
    mcp.run(transport="stdio")
```

What to notice:
- Returning a Pydantic model gives the host **structured content** plus an auto-derived `outputSchema`.
- `readOnlyHint=True` tells the host this is safe to auto-approve.
- The SQL guardrails are **defense in depth**, not real security. The real fix in production: open the SQLite connection in read-only mode (`file:app.db?mode=ro` with `uri=True`) and run the process as a low-privilege user. Never trust string parsing to make a mutating engine safe.

### 5.3 Example C — A long-running task with progress + logging + cancellation

Demonstrates the `Context` object: structured logging, progress reporting, and cooperative cancellation.

```python
# batch_server.py
import asyncio
from mcp.server.fastmcp import FastMCP, Context

mcp = FastMCP("batch-processor")

@mcp.tool()
async def process_files(count: int, ctx: Context) -> str:
    """Process N files, reporting progress and logging as it goes."""
    await ctx.info(f"Starting batch of {count} files")  # -> notifications/message
    for i in range(count):
        # report_progress drives the host's progress bar.
        await ctx.report_progress(progress=i, total=count)
        await ctx.debug(f"processing file {i}")
        try:
            await asyncio.sleep(0.2)  # simulate work; await yields for cancellation
        except asyncio.CancelledError:
            # Client sent notifications/cancelled; stop cleanly.
            await ctx.warning("batch cancelled by client")
            raise
    await ctx.report_progress(progress=count, total=count)
    await ctx.info("batch complete")
    return f"Processed {count} files."

if __name__ == "__main__":
    mcp.run(transport="stdio")
```

What to notice:
- `Context` is **injected** simply by adding a `ctx: Context` parameter — it does not appear in the tool's input schema.
- `await` points are where cancellation can take effect — long CPU-bound loops with no `await` cannot be cancelled cooperatively; offload those to a thread/process pool.
- Logging via `ctx` goes through `notifications/message` (protocol-safe). `print()` to stdout would corrupt the stream.

### 5.4 Example D — A remote server over Streamable HTTP with auth

Production servers are often remote. Here is the HTTP transport with a bearer-token gate (conceptual; auth specifics are version/host-dependent).

```python
# http_server.py
# Run: python http_server.py  (serves Streamable HTTP)
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("remote-tools")  # host/port configured via SDK/env per version

@mcp.tool()
def echo(text: str) -> str:
    """Echo input back. Trivial tool for connectivity tests."""
    return text

if __name__ == "__main__":
    # Serves a single MCP endpoint over HTTP with optional SSE for
    # server->client messages. Put a real reverse proxy + TLS in front.
    mcp.run(transport="streamable-http")
```

Auth note (**version/host-specific**): the **2025-03-26**+ spec defines an **OAuth 2.1**-based authorization framework for HTTP transports. For internal deployments, a simpler pattern is to terminate auth at a reverse proxy (validate a bearer token / mTLS) before traffic reaches the server. Always serve remote MCP over **TLS** and validate the `Origin` header to prevent DNS-rebinding attacks (see §6.4).

> **OAuth 2.1:** a consolidation of OAuth 2.0 best practices into a single, stricter spec (mandatory PKCE, no implicit flow). MCP's HTTP authorization profile builds on it so remote servers can require scoped, expiring access tokens.

> **mTLS (mutual TLS):** TLS where *both* client and server present certificates, so each authenticates the other. Useful for service-to-service MCP where you control both ends.

### 5.5 Example E — Resource templates + autocompletion

A documentation server: templated resources plus argument completion so the host can suggest valid values.

```python
# docs_server.py
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("docs")
PAGES = {"intro": "Welcome", "api": "API Reference", "faq": "Common Questions"}

@mcp.resource("docs://{page}")
def get_page(page: str) -> str:
    """Return the documentation page for the given slug."""
    return PAGES.get(page, f"# Not found: {page}")

# Completion handlers vary by SDK version; conceptually you register a
# completer keyed to a resource/prompt argument so completion/complete
# returns candidate values (here: the valid page slugs).
# (Consult your SDK version's completion API for the exact registration.)

if __name__ == "__main__":
    mcp.run(transport="stdio")
```

What to notice:
- One `@mcp.resource("docs://{page}")` declaration serves an entire family of URIs.
- Completion (`completion/complete`) lets the host offer the user/model valid argument values (the page slugs) instead of guessing.

### 5.6 Example F — TypeScript equivalent of the minimal server

For teams on Node, the same minimal server in TypeScript:

```typescript
// server.ts
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({ name: "demo-tools", version: "1.0.0" });

server.registerTool(
  "add",
  {
    description: "Add two integers and return the sum.",
    inputSchema: { a: z.number().int(), b: z.number().int() }, // Zod -> JSON Schema
  },
  async ({ a, b }) => ({
    content: [{ type: "text", text: String(a + b) }],
  })
);

server.registerResource(
  "app-config",
  "config://app",
  { description: "Static app configuration" },
  async (uri) => ({
    contents: [{ uri: uri.href, text: "theme=dark\nlocale=en-US" }],
  })
);

const transport = new StdioServerTransport();
await server.connect(transport); // begins the read loop on stdin
// IMPORTANT: never console.log() — it writes to stdout and corrupts the
// protocol stream. Use console.error() (stderr) for logging.
```

### 5.7 Connecting to a host (Claude Desktop)

Register the stdio server in `claude_desktop_config.json`:

- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "demo-tools": {
      "command": "python",
      "args": ["/absolute/path/to/server.py"],
      "env": { "API_KEY": "..." }
    }
  }
}
```

Rules that bite people:
- Use an **absolute path** (and an absolute interpreter path if `python` isn't on the host's PATH). Hosts often launch with a minimal PATH.
- Prefer `uv run` / `uvx` to pin the environment: `{"command":"uv","args":["run","--directory","/path","server.py"]}`.
- **Restart the host fully** after editing the config — most hosts read it only at startup.
- Logs for stdio servers are typically captured by the host; on Claude Desktop check the app's MCP log files (e.g., `~/Library/Logs/Claude/mcp*.log` on macOS) when a server "won't connect."

### 5.8 Testing with MCP Inspector

Before wiring into a host, drive the server with **MCP Inspector**:

```bash
# Launch the inspector against your stdio server:
npx @modelcontextprotocol/inspector python server.py

# CLI mode (scriptable, good for CI):
npx @modelcontextprotocol/inspector --cli python server.py --method tools/list
npx @modelcontextprotocol/inspector --cli python server.py \
  --method tools/call --tool-name add --tool-arg a=2 --tool-arg b=3
```

Inspector lets you: complete the handshake, list/call tools, read resources, render prompts, watch notifications/logs, and see raw JSON-RPC. **Workflow: get it green in Inspector first, then connect to a host.** Debugging in Inspector is far faster than debugging through a chat UI.

---

## 6. Implementation concerns & best practices

### 6.1 The stdout rule (the #1 mistake)

For stdio transport, **stdout is sacred** — it carries the JSON-RPC stream. Any stray write (a `print`, a library banner, a progress bar, a debug log) corrupts the stream and the host disconnects with cryptic parse errors. Rules:
- Route **all** logging to **stderr** (Python `logging` defaults to stderr; verify no handler writes to stdout).
- Silence noisy libraries before serving.
- For diagnostics during a call, use `ctx.info/debug` (protocol `notifications/message`), not `print`.

### 6.2 Performance

- **Cold start matters.** Hosts spawn stdio servers on demand; heavy imports (ML libs) add latency. Lazy-import expensive deps inside handlers, not at module top.
- **Don't block the event loop.** In async servers, never run blocking I/O or CPU-bound work directly in an `async def` handler — it stalls *all* concurrent requests. Offload with `asyncio.to_thread`, a thread pool, or a process pool.
- **Cache `*/list` results' inputs.** Building large catalogs on every `tools/list` is wasteful; precompute.
- **Paginate** large `resources/list`/`tools/list` via the `cursor` mechanism the protocol provides, rather than returning thousands of entries at once.
- **Bound result sizes.** Tool results flow back into the model's context window; returning a 5 MB blob blows the budget and costs tokens. Truncate, summarize, or return a resource reference instead.

> **Context window:** the maximum number of tokens a model can consider at once. Everything a tool returns is fed back to the model and counts against this budget — so concise, structured results are not just polite, they are economically necessary.

### 6.3 Correctness & concurrency

- The SDK correlates requests by `id`; your handlers may run concurrently. **Don't share mutable global state without synchronization.**
- Make tools **idempotent** where possible and advertise `idempotentHint`. Retries (by host or network) are a fact of life on HTTP transports.
- Validate beyond the schema: schema checks types, but business rules (range, existence, permissions) are on you. Raise clear errors.

### 6.4 Security (treat the model's input as hostile)

- **The model is an untrusted caller.** Tool arguments are effectively attacker-controlled (prompt injection can make the model call your tool with malicious args). Validate, sanitize, and authorize **server-side**, every time.
- **Authorization belongs on the server, never in annotations.** `readOnlyHint` is a UX hint; enforce real permissions with actual checks.
- **Path traversal:** a filesystem tool must canonicalize and confine paths to an allowed root (use **roots** the host provides). Reject `../` escapes.
- **Command/SQL injection:** never interpolate model-supplied strings into shells or SQL. Use parameterized queries and arg arrays.
- **For remote (HTTP) servers:** require TLS; **validate the `Origin` header** and bind to `localhost` for local HTTP servers to prevent **DNS-rebinding** attacks; use OAuth 2.1 / scoped tokens; rate-limit.
- **Secrets:** pass via `env` in the host config or a secrets manager — never hardcode, never log them, never return them in tool results.
- **Confused-deputy risk:** your server acts with *its* credentials on behalf of a model whose instructions may be poisoned. Scope the server's own permissions to the minimum (least privilege).

> **Prompt injection:** an attack where malicious text (in a document, web page, or prior message) manipulates the model into taking unintended actions — including calling your tools with harmful arguments. This is why server-side validation/authorization is mandatory: you cannot trust that the model's tool call reflects the user's intent.

> **DNS rebinding:** an attack where a malicious web page tricks a victim's browser into making requests to a local service (your MCP HTTP server) by rebinding a domain to `127.0.0.1`. Validating `Origin` and binding to loopback mitigates it.

### 6.5 Observability

- Log to **stderr** with structured, leveled logs; include the `request_id` (from `ctx`) to correlate a tool call across log lines.
- Emit protocol-level logs via `notifications/message` so the *host* can surface them; respect `logging/setLevel`.
- Track per-tool metrics: call count, latency, error rate, result size. These are your production health signals.
- On HTTP transport, propagate a trace/correlation header into your downstream calls for distributed tracing.

### 6.6 Testing

- **Unit-test handlers as plain functions.** Your tool functions are ordinary Python/TS functions — test them directly, no protocol needed.
- **Integration-test via Inspector CLI** in CI: assert `tools/list` shape and a few representative `tools/call` outputs.
- **Schema tests:** assert each tool's generated `inputSchema`/`outputSchema` matches expectations so a refactor doesn't silently break the contract the model relies on.
- **Failure tests:** assert that bad arguments produce a clean tool error (`isError`), not an unhandled exception that kills the process.

### 6.7 Error handling: two distinct error channels

MCP has **two** error mechanisms and conflating them is a classic bug:

1. **Protocol errors** — JSON-RPC `error` responses (e.g., unknown method, malformed request). These signal *the protocol* failed.
2. **Tool execution errors** — a *successful* response with `isError: true` and an explanatory text content block. The tool ran but the *operation* failed (API down, no such record). The model **sees** this and can react (retry, apologize, try another tool).

Rule: **business/operation failures should be returned as tool results with `isError:true`, not raised as protocol errors.** In FastMCP, raising a normal exception from a tool typically becomes an error result the model can read; raising in a way that breaks the protocol does not. Return actionable error *text* — the model is your audience.

### 6.8 Production hardening checklist

- Absolute paths and pinned interpreter in host config.
- All logging on stderr; nothing else on stdout.
- Lazy imports; bounded result sizes; pagination.
- Server-side validation + authorization on every tool.
- Least-privilege credentials; secrets via env/secret manager.
- TLS + Origin validation + auth for HTTP transport.
- Graceful shutdown on stdin EOF / SIGTERM.
- Health/ping handling; timeouts on every downstream call.
- Versioned server; clear `serverInfo` name/version for debuggability.

### 6.9 Anti-patterns

- **Vague tool descriptions.** The model chooses tools from descriptions; "does stuff" guarantees misuse. Describe *when*, *inputs*, *outputs*.
- **Tool sprawl.** 80 fine-grained tools overload the model's selection; prefer fewer, well-scoped tools. (Some hosts cap how many tools they'll expose.)
- **Returning huge blobs.** Burns context and money; return references/resources.
- **Trusting hints for security.** Already covered — worth repeating.
- **Hidden side effects in "read-only" tools.** If you label it `readOnlyHint`, it must truly not mutate.
- **Logging to stdout.** The eternal stdio footgun.
- **Synchronous blocking in async handlers.** Stalls everything.

---

## 7. Advanced topics & deep internals

### 7.1 Sampling — the server asks the host for an LLM call

**Sampling** lets the *server* request a completion from the *host's* model (`sampling/createMessage`). This inverts the usual flow: your server can use the model as a sub-routine (e.g., to summarize fetched data) **without holding its own API key**. The host mediates — it can show the request to the user, pick the model, and apply its own limits.

Why it's powerful: agentic servers can compose. A "research" tool can fetch pages, then *sample* the host's model to summarize each, then aggregate — all without bundling an LLM dependency. The client must advertise the `sampling` capability for this to be available. The request includes `messages`, `modelPreferences` (hints like cost/speed/intelligence priorities), `systemPrompt`, and `maxTokens`; the host returns a model message. **Human-in-the-loop is expected**: hosts may require approval before fulfilling sampling requests.

### 7.2 Roots — the host tells the server its boundaries

**Roots** are URIs (often filesystem directories) the host grants the server as its operating scope. A filesystem server should call `roots/list`, confine all path operations to those roots, and re-list when it receives `notifications/roots/list_changed`. This is the principled way to do path sandboxing — the *host* (which knows the user's intent and trust boundaries) supplies the allowed scope rather than the server guessing.

### 7.3 Elicitation — the server asks the user a question mid-call

**Elicitation** (`elicitation/create`, added in the **2025-06-18** revision) lets a server pause and request structured input from the user during an operation — e.g., "Which of these 3 matching records did you mean?" or "Confirm: delete 42 files?". The server sends a JSON Schema for the expected response; the host renders a form and returns the user's answer (or a decline/cancel). This enables interactive, multi-step tools without baking every choice into the initial arguments.

### 7.4 Completion — argument autocompletion

`completion/complete` lets a server suggest values for prompt arguments and resource-template variables as the user types (like IDE autocomplete). The server returns up to a capped list of candidates plus a "has more" flag. Improves UX for prompts/resources with constrained value sets.

### 7.5 Pagination internals

List operations (`tools/list`, `resources/list`, `prompts/list`, etc.) support **cursor-based pagination**: the response includes an opaque `nextCursor`; the client passes it back to fetch the next page. Cursors are opaque tokens — clients must not parse them. Use this for large catalogs to avoid one giant payload and to keep per-request latency bounded.

### 7.6 Notifications & change propagation

If your catalog or resource data changes at runtime:
- Advertise the relevant `listChanged` / `subscribe` capability.
- Emit `notifications/tools/list_changed` (or resources/prompts) when the catalog changes; hosts re-list.
- For `subscribe`d resources, emit `notifications/resources/updated` with the URI; the host re-reads.

A subtle correctness point: there is an inherent race between "resource changed" and "client re-reads" — design reads to be consistent (return the current value, version it if needers care about staleness).

### 7.7 Mapping it all to Java / Spring AI (for the JVM reader)

The official **Java SDK** and **Spring AI** provide first-class MCP server support. The conceptual mapping:

- A FastMCP `@mcp.tool()` ≈ a Spring AI tool registered via `@Tool`-annotated methods exposed through an MCP server bean, or tools registered on an `McpSyncServer` / `McpAsyncServer`.
- Capabilities, lifecycle, and JSON-RPC framing are handled by the SDK exactly as in Python/TS.
- Transports: the Java SDK supports **stdio** and HTTP (servlet/WebFlux-based) transports; Spring Boot starters wire these up.
- Spring AI exposes a `ToolCallback`/function abstraction; the MCP server starter publishes those as MCP tools, so a JVM service can become an MCP server with mostly configuration.

Practical guidance for the JVM shop: prototype in Python/TS to learn the protocol fast (this chapter's examples), then implement the production server in Java/Spring AI to fit your existing build, observability, and deployment story. Watch version pinning carefully — the Java SDK and Spring AI track the spec revisions, and capability/transport names must match what your target host expects.

> **Spring AI:** an application framework (from the Spring ecosystem) for building AI-powered apps on the JVM, with abstractions for models, prompts, tools/function-calling, RAG, and — relevant here — MCP client and server support via Spring Boot starters.

### 7.8 Lesser-known behaviors & edge cases

- **Protocol version negotiation can downgrade silently.** If a host requests a newer version than your SDK supports, you respond with your version; if the host then can't proceed, the session may just close. Log the negotiated version.
- **`_meta` fields** carry out-of-band data (like `progressToken`) and are easy to overlook; the SDK usually handles them, but custom transports must preserve them.
- **Cancellation is best-effort.** A cancelled request whose result is already on the wire may still arrive; clients must tolerate a late result for a cancelled `id`.
- **stdio servers and buffering.** Ensure stdout is line-buffered/flushed per message; a buffered runtime can make responses appear to "hang." (SDKs handle this, but custom framing must flush.)
- **Multiple instances.** A host may launch several instances of the same stdio server (e.g., per workspace). Don't assume a singleton; avoid global file locks that collide.
- **Tool name collisions across servers.** Hosts often namespace tools by server; still, keep names descriptive and stable — renaming a tool breaks any model behavior/prompts that referenced it.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Tool vs. Resource vs. Prompt — which primitive?

| You want… | Use | Why |
|---|---|---|
| The **model to autonomously perform an action** | **Tool** | Model-controlled; has typed args; can mutate |
| To **attach data as context** the app/user selects | **Resource** | App-controlled; read-only; URI-addressable |
| A **user-triggered reusable workflow** (slash command) | **Prompt** | User-controlled; parameterized template |
| The model to *read* data on its own initiative | **Tool** (a "fetch"/"search" tool) | Some hosts don't auto-surface resources to the model; a tool is reliably model-invokable |

A practical nuance: not all hosts expose resources/prompts to the *model* (some only surface them to the *user/app*). If you need the model to reliably pull data, also expose a **tool** for it.

### 8.2 stdio vs. Streamable HTTP

| Dimension | stdio | Streamable HTTP |
|---|---|---|
| Deployment | Local subprocess | Remote/networked |
| Multi-client | One per process | Many clients to one server |
| Auth | Process-level / env | OAuth 2.1 / TLS / proxy |
| Latency | Lowest (no network) | Network-bound |
| Complexity | Lowest | Higher (web stack, auth, scaling) |
| Best for | Desktop tools, dev, local data | SaaS, shared internal services |
| Start here? | **Yes** | After stdio works |

### 8.3 Build an MCP server vs. alternatives

| Option | Use when | Avoid when |
|---|---|---|
| **MCP server** | Capability is reused across hosts/teams; crosses a trust/process boundary; you want host-mediated gating | Single in-process consumer that's your own code |
| **Native function calling (no MCP)** | One app, one model, tightly coupled tools | You need cross-host reuse or out-of-process isolation |
| **Plain REST/gRPC API** | Non-LLM consumers; deterministic integration | You specifically need model-discoverable, model-invokable capabilities with schemas + host gating |
| **Embedding everything in the prompt** | Tiny, static context | Data is large, dynamic, or actionable |

### 8.4 SDK / language choice

| SDK | Maturity | Reach for it when |
|---|---|---|
| **Python (FastMCP)** | High | Fastest to a working server; data/ML adjacency |
| **TypeScript** | High | Node ecosystem; web/remote servers; same lang as many hosts |
| **Java / Spring AI** | Growing, official | JVM production shops; reuse Spring infra |
| **C#, Kotlin, Go, Rust** | Varies | Match existing service stack |

Rule of thumb: **learn in Python/TS, ship in your stack.**

---

## 9. Failure modes & debugging

### 9.1 "Server won't connect / disappears in the host"

Symptoms: the server doesn't appear, or shows an error, in the host.
Likely causes & fixes:
- **Wrong/relative path** in config → use absolute paths for both command and script.
- **Interpreter not on host PATH** → use absolute interpreter path or `uv`/`uvx`.
- **Wrote to stdout** → move all logging to stderr; this is the most common cause.
- **Crashed on startup** → check the host's MCP logs (e.g., `~/Library/Logs/Claude/mcp*.log` on macOS Claude Desktop) for the stderr capture and exception.
- **Didn't restart the host** after editing config.
Diagnose first in **Inspector**: `npx @modelcontextprotocol/inspector python server.py`. If it works there but not in the host, the problem is config/PATH/restart, not your code.

### 9.2 "Tools don't show up"

- Server connected but `tools/list` empty → handlers not registered before `run()`, or registration threw.
- Catalog changed at runtime but host shows stale list → you didn't advertise `listChanged` or didn't emit `notifications/tools/list_changed`.
- Model never calls the tool → description is vague or the tool overlaps others; tighten the description and reduce overlap.

### 9.3 "Parse error / unexpected token" on the wire

Almost always **stdout pollution**: a library banner, `print`, warning, or progress bar wrote to stdout. Audit every dependency's output; redirect or silence. Confirm your logger targets stderr.

### 9.4 "Tool call hangs"

- Blocking I/O in an async handler stalls the event loop → offload to a thread/process.
- Downstream call without a timeout → add timeouts everywhere.
- Unflushed stdout buffering (custom transport) → flush per message.

### 9.5 "Arguments rejected / validation errors"

- Type hints don't match what the model sends → broaden/clarify schema; add `Field` descriptions so the model formats args correctly.
- Required field missing → check `required` in the generated schema; mark optional params with defaults.

### 9.6 "Works in Inspector, fails in production host"

- Protocol version mismatch → log the negotiated version on both sides.
- Capability the host doesn't support → check you're not assuming `sampling`/`elicitation` the client never advertised.
- Auth/TLS on HTTP transport → check Origin validation, token scopes, proxy config.

### 9.7 Real-world incident patterns

- **The silent-stdout outage:** a server adds a new dependency that prints a deprecation warning to stdout on import; every session breaks with parse errors. Fix: capture/redirect third-party stdout; add a CI test that asserts a clean handshake.
- **The token-blowup cost spike:** a "list_records" tool returns full rows for 50k records; each call dumps megabytes into context, exploding token cost and latency. Fix: paginate, summarize, or return a resource reference; cap result size.
- **The confused-deputy leak:** an over-privileged DB server (admin creds) is driven by a prompt-injected model into reading another tenant's data. Fix: least-privilege creds, server-side authorization keyed to the actual user, read-only connections.
- **The PATH ghost:** server works from a terminal but not when the host launches it, because the host's PATH lacks the Python venv. Fix: absolute interpreter path or `uv run --directory`.

### 9.8 The debugging toolkit

| Symptom | Tool / command |
|---|---|
| General triage | MCP Inspector (`npx @modelcontextprotocol/inspector ...`) |
| Scripted checks / CI | Inspector CLI (`--cli --method tools/list`) |
| Host-side errors | Host MCP logs (e.g., Claude Desktop `mcp*.log`) |
| Wire inspection (HTTP) | Proxy/`curl` against the HTTP endpoint; check SSE stream |
| Server-side tracing | stderr structured logs with `request_id` correlation |
| Schema verification | Assert generated `inputSchema`/`outputSchema` in tests |

---

## 10. Interview drill

**Q1. What is an MCP server and what problem does it solve?**
A process exposing tools/resources/prompts to LLM hosts over JSON-RPC, standardizing LLM↔external-system integration. It solves the N×M integration problem: instead of writing a bespoke integration per (host, capability) pair, you write one server that any compliant host can use, collapsing N×M to N+M.
- *Follow-up: Why not just call functions directly in your app?* Because MCP earns value when the capability is reused across hosts, crosses a process/trust boundary, or needs host-mediated gating/logging. For a single in-process consumer that's your own code, MCP is overhead.
- *Follow-up: What's the LSP analogy?* Like the Language Server Protocol decoupled editors from language tooling (write one server, every editor benefits), MCP decouples LLM hosts from capability providers.
- *Follow-up: Who are the three actors?* Host (the app + model), client (one connector per server inside the host), server (your capability provider). Client↔server is 1:1 and stateful.

**Q2. Walk me through the lifecycle of an MCP session.**
Three phases: initialization (client sends `initialize` with version+capabilities; server responds with its version+capabilities; client sends `notifications/initialized`), operation (request/response + notifications), shutdown (transport closes). Until `initialized`, only `initialize`/`ping` are allowed.
- *Follow-up: What's capability negotiation and why?* Each side advertises supported features; neither uses a feature the other didn't advertise — this is how MCP stays forward/backward compatible and avoids unsupported calls.
- *Follow-up: What happens on version mismatch?* Server replies with a version it supports; client decides whether to proceed; otherwise the session ends. Always log the negotiated version.

**Q3. Trace exactly what happens when the model decides to call a tool.**
User message → host sends conversation + tool catalog to model → model emits a tool_use (intent) → host optionally gates with user approval → client sends `tools/call` → server validates args, runs handler, returns content blocks (+ optional structuredContent) → host feeds the tool_result back to the model → model produces the final answer. The model never touches the server directly; the host's client mediates.
- *Follow-up: Why the indirection?* So the host can gate, log, sandbox, and apply auth — security and control live in the host, not the model.
- *Follow-up: Where does validation happen?* Host may validate against the schema; the SDK validates again on receipt; you add business-rule validation. Defense in depth.

**Q4. What are the differences between tools, resources, and prompts?**
Tools are model-controlled actions with typed input schemas (can mutate). Resources are app-controlled, read-only, URI-addressable data. Prompts are user-controlled reusable templates (slash commands). The axis is *who controls invocation*.
- *Follow-up: If you need the model to read data reliably, which do you use?* A tool — some hosts don't surface resources to the model, only to the user/app, so a "fetch/search" tool is the reliable path.
- *Follow-up: What's a resource template?* An RFC 6570 URI template like `docs://{page}` that serves a whole family of addresses from one declaration.

**Q5. What are the transports and when do you use each?**
stdio (host spawns a subprocess; JSON-RPC over stdin/stdout, logs on stderr) for local/dev/desktop — start here. Streamable HTTP (single endpoint, POST + optional SSE) for remote/multi-client. The old HTTP+SSE two-endpoint transport is deprecated in favor of Streamable HTTP (2025-03-26).
- *Follow-up: The #1 stdio footgun?* Writing anything to stdout — it corrupts the JSON-RPC stream. All logging goes to stderr.
- *Follow-up: How does the server send messages to the client over HTTP?* Via an SSE stream on the same endpoint.

**Q6. How do you test and debug an MCP server?**
Unit-test handlers as plain functions; integration-test with **MCP Inspector** (interactive UI or `--cli` for CI); verify the handshake, list, and call paths. For host issues, read the host's MCP logs. Golden rule: get it green in Inspector before connecting to a host.
- *Follow-up: "Works in Inspector, not in host" — first checks?* Absolute paths, interpreter on PATH (or use `uv`), restart the host, stdout pollution, protocol version.
- *Follow-up: "Parse error" on the wire?* Stdout pollution from a dependency or `print`; redirect logging to stderr.

**Q7. How does error handling work, and what's the common mistake?**
Two channels: protocol errors (JSON-RPC `error`, for protocol-level failures like unknown method) and tool execution errors (a *successful* response with `isError:true` + explanatory text, for operation failures the model should see and react to). The common mistake is conflating them — return business failures as tool results with actionable text, not protocol errors.
- *Follow-up: Who's the audience for an error message?* The model — write errors it can act on (retry, choose another tool, apologize).
- *Follow-up: What happens if a tool throws?* In FastMCP, a normal exception typically becomes an error result the model can read; you should still catch and shape messages.

**Q8 (senior signal). You're exposing a production database to an MCP server. Walk me through the security design.**
The model is an untrusted caller (prompt injection makes its tool calls attacker-controlled). So: least-privilege, read-only DB credentials; parameterized queries (no string interpolation); server-side authorization keyed to the *actual* user, not the model; result size caps + pagination to bound token cost; never log/return secrets; for remote, TLS + Origin validation + OAuth scopes. Annotations like `readOnlyHint` are UX hints, never security. Beware the confused-deputy problem — your server acts with its own creds on behalf of a possibly-poisoned model, so minimize its blast radius.
- *Follow-up: Why isn't `readOnlyHint` enough?* It's advisory metadata for host UX; an attacker-influenced model can call any exposed tool. Enforce real permissions in code.
- *Follow-up: How do you bound cost?* Cap and paginate results; return references/resources for large data; summarize. Every byte returned re-enters the model's context and costs tokens.

**Q9 (senior signal). Tool vs. native function-calling vs. plain REST — how do you decide?**
MCP when the capability is reused across hosts/teams, crosses a trust/process boundary, or needs host-mediated gating. Native function-calling when it's one app + one model, tightly coupled. Plain REST/gRPC when consumers aren't LLMs or you want deterministic integration. The deciding questions: how many consumers, do you need model-discoverability + schemas + host gating, and is there a trust boundary?
- *Follow-up: Cost of choosing MCP prematurely?* Protocol/transport overhead, an extra process to operate, capability/version management — unjustified for a single in-process consumer.
- *Follow-up: Migration path?* Start with native function-calling; extract to an MCP server once a second host/consumer appears.

**Q10 (senior signal). Design an agentic "research" MCP server. What primitives and protocol features do you use, and what are the failure modes?**
Tools for `search` and `fetch_url`; use **sampling** so the server asks the host's model to summarize fetched pages (no embedded LLM key); **progress** notifications for long crawls; **elicitation** to ask the user to disambiguate; **resources** to expose retrieved documents; **roots** if it also reads local files. Failure modes: token blowup from returning full pages (summarize/paginate), runaway crawls (bound depth/time, add cancellation), prompt injection from fetched content (treat fetched text as untrusted; don't let it drive privileged tool calls), and event-loop stalls (offload blocking fetches).
- *Follow-up: What does sampling buy you?* Composition without bundling an LLM dependency or key; the host mediates model choice, limits, and approval.
- *Follow-up: How do you keep fetched web content from hijacking the agent?* Treat it as untrusted data, not instructions; isolate it from the tool-selection prompt; require human approval for destructive tools; least-privilege everything.

**Q11. How do `listChanged`, `subscribe`, and notifications work for dynamic data?**
Advertise `listChanged` (tools/resources/prompts) to emit `notifications/.../list_changed` when the catalog changes, prompting the host to re-list. Advertise `resources.subscribe` to let clients `resources/subscribe` and receive `notifications/resources/updated` per URI, prompting a re-read. There's an inherent change↔re-read race, so reads should return the current value.
- *Follow-up: When would you skip subscriptions?* Static catalogs/data — the extra machinery isn't worth it.

**Q12. What is pagination in MCP and why does it matter?**
List methods return an opaque `nextCursor`; the client passes it back for the next page. Cursors are opaque (don't parse). It bounds payload size and per-request latency for large catalogs and avoids dumping thousands of entries into one response.
- *Follow-up: Why opaque cursors?* So the server can change its internal paging strategy without breaking clients.

---

## 11. Glossary

- **Annotation (tool hint):** advisory metadata on a tool (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) guiding host gating; never a security control.
- **Capability negotiation:** the handshake step where client and server advertise supported features; neither uses an unadvertised feature.
- **Client:** the per-server connector inside a host; maintains a 1:1 stateful session with one server.
- **Content block:** a unit of tool/result output (text, image, audio, or embedded resource).
- **Context window:** the max tokens a model can consider at once; tool results consume it.
- **Cursor:** opaque pagination token returned as `nextCursor` and passed back for the next page.
- **DNS rebinding:** attack tricking a browser into hitting a local service; mitigated by Origin validation and loopback binding.
- **Elicitation:** server-initiated request for structured user input mid-operation (`elicitation/create`, 2025-06-18).
- **FastMCP:** the high-level, decorator-based Python SDK API for building MCP servers.
- **Host:** the LLM application (model + UI + orchestration) that runs clients and connects to servers.
- **Idempotent:** an operation that, repeated with the same input, produces no additional effect.
- **In-flight request:** a sent-but-unanswered request; correlated by `id`.
- **Initialize:** the first request that begins the handshake and negotiates version/capabilities.
- **JSON-RPC 2.0:** the JSON message format MCP uses (request/response/notification with `jsonrpc`, `method`, `params`, `id`, `result`/`error`).
- **JSON Schema:** vocabulary for describing JSON shapes; used for tool input/output schemas.
- **LLM:** large language model; generates text, can't natively access external systems.
- **LSP (Language Server Protocol):** the editor↔language-tooling standard MCP is analogized to.
- **mTLS:** mutual TLS; both client and server authenticate via certificates.
- **N×M problem:** N consumers × M providers needing bespoke integrations; a standard collapses it to N+M.
- **Notification:** a JSON-RPC message with no `id`, expecting no reply (fire-and-forget).
- **OAuth 2.1:** consolidated, stricter OAuth used by MCP's HTTP authorization profile.
- **Prompt:** a user-controlled, parameterized reusable message template (`prompts/get`).
- **Prompt injection:** malicious text manipulating the model into unintended actions, including malicious tool calls.
- **Progress token:** opaque value correlating progress notifications to a request.
- **Protocol version:** date-stamped string (e.g., `2025-06-18`) negotiated at init.
- **Pydantic:** Python validation library used by FastMCP to derive/validate schemas.
- **Resource:** app-controlled, read-only, URI-addressed data (`resources/read`).
- **Resource template:** RFC 6570 URI template (e.g., `docs://{page}`) serving a family of URIs.
- **Roots:** host-supplied URI boundaries scoping the server's operations (`roots/list`).
- **Sampling:** server-initiated request for an LLM completion from the host (`sampling/createMessage`).
- **SDK:** library implementing the protocol so you write business logic, not wire framing.
- **Server:** the process you write exposing tools/resources/prompts.
- **Spring AI:** JVM framework with MCP client/server support via Spring Boot starters.
- **SSE (Server-Sent Events):** one-way HTTP streaming used for server→client messages in Streamable HTTP.
- **stdio:** transport where the host spawns the server and exchanges JSON-RPC over stdin/stdout (logs on stderr).
- **Streamable HTTP:** the current HTTP transport (single endpoint, POST + optional SSE); replaced the old HTTP+SSE transport.
- **Structured content:** machine-readable JSON in a tool result, validated against an `outputSchema`.
- **Tool:** model-controlled action with a typed input schema (`tools/call`).
- **Transport:** how bytes move (stdio or Streamable HTTP), separate from the protocol's meaning.
- **URI:** string identifying a resource (e.g., `file:///etc/hosts`).
- **Zod:** TypeScript schema/validation library used by the TS SDK for tool input schemas.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **What:** MCP server = JSON-RPC process exposing **tools** (model-controlled actions), **resources** (app-controlled read-only URIs), **prompts** (user-controlled templates). Solves N×M → N+M. LSP analogy.
- **Actors:** Host (app+model) → Client (1 per server) → Server (you). Model never touches server directly.
- **Lifecycle:** `initialize` (negotiate version+capabilities) → `notifications/initialized` → operate → close. Only init/ping before initialized.
- **Tool call path:** user → model picks tool → host gates → `tools/call` → validate+run → content blocks (+structuredContent) → back to model → answer.
- **Transports:** **stdio** (subprocess; JSON-RPC on stdin/stdout; **logs on stderr only**) — start here. **Streamable HTTP** (one endpoint, POST+SSE; TLS+OAuth+Origin) for remote.
- **#1 footgun:** anything on **stdout** corrupts the stream. Log to stderr / `ctx.info`.
- **Two error channels:** protocol `error` (protocol failed) vs result `isError:true` (operation failed; model reads it). Return business failures as `isError`.
- **Security:** model is untrusted (prompt injection); validate + authorize **server-side**; least-privilege creds; parameterized queries; hints ≠ security; cap result size (token cost).
- **Advanced:** sampling (server asks host's model), roots (host-granted scope), elicitation (ask user mid-call), completion (autocomplete), pagination (opaque `nextCursor`), `listChanged`/`subscribe` for dynamic data.
- **Tooling:** **MCP Inspector** (`npx @modelcontextprotocol/inspector ...`, `--cli` for CI). Host config = `claude_desktop_config.json` with **absolute paths**; restart host after edits.
- **Default transport:** stdio. **JSON-RPC:** 2.0. **Spec revisions noted:** 2025-03-26 (Streamable HTTP, OAuth), 2025-06-18 (elicitation).
- **JVM:** learn in Python/TS, ship in Java/Spring AI.

### 12.2 Self-test (no answers — active recall)

1. A host launches your stdio server but it never appears in the UI and the host logs show "Unexpected token in JSON." List the three most likely root causes in order of probability, and the exact fix for each.
2. You need the model to *autonomously search* a knowledge base and the *user* to attach specific documents as context. Which primitive(s) do you use for each, and why might exposing only a resource fail to make the model search?
3. Write the JSON-RPC message sequence (method names only) for: connect → discover tools → call a tool → receive a long-running progress update → cancel it.
4. Your "list_orders" tool returns full order objects and your token bill just tripled. Name three independent mitigations and which protocol feature each one uses.
5. Explain why `readOnlyHint:true` does not make a tool safe to expose to an untrusted model, and describe the server-side controls that actually do.
6. A teammate proposes building an MCP server for a capability that only your one application will ever call, in-process. Make the case for *not* using MCP, then state the single condition that would change your recommendation.
7. Describe how **sampling** lets an agentic server summarize fetched web pages without holding its own model API key, and name two safeguards the host applies to such requests.

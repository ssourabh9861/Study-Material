# MCP Transports — A Definitive Engineering Reference

> Concept area: **MCP — Model Context Protocol**
> Subtopic: **Transports**
> Reader: a senior JVM/back-end developer who wants to master MCP transports from first principles through deep internals — enough to design, operate, debug, teach, and pass any interview on the subject.

---

## Table of contents

1. [Overview & where it fits](#1-overview--where-it-fits)
2. [Foundations from first principles](#2-foundations-from-first-principles)
3. [How it works internally](#3-how-it-works-internally)
4. [The complete toolkit](#4-the-complete-toolkit)
5. [Code examples by use case](#5-code-examples-by-use-case)
6. [Implementation concerns & best practices](#6-implementation-concerns--best-practices)
7. [Advanced topics & deep internals](#7-advanced-topics--deep-internals)
8. [Tradeoffs & decision frameworks](#8-tradeoffs--decision-frameworks)
9. [Failure modes & debugging](#9-failure-modes--debugging)
10. [Interview drill](#10-interview-drill)
11. [Glossary](#11-glossary)
12. [Cheat-sheet & self-test](#12-cheat-sheet--self-test)

---

## 1. Overview & where it fits

### 1.1 What MCP is, in one paragraph

The **Model Context Protocol (MCP)** is an open protocol — originally published by Anthropic in November 2024 and now maintained as an open standard — that defines a uniform way for an **LLM application** (the *host*, e.g. Claude Desktop, an IDE, an agent framework) to connect to external **context and capability providers** (the *servers*, e.g. a filesystem, a database, a GitHub API wrapper, a search tool). MCP is deliberately analogous to the **Language Server Protocol (LSP)**: where LSP standardized how editors talk to language-analysis backends so any editor can use any language server, MCP standardizes how AI hosts talk to capability backends so any host can use any server. MCP servers expose three primary primitive types — **tools** (functions the model can call), **resources** (readable data, addressed by URI), and **prompts** (reusable prompt templates) — plus a few client-side primitives (**sampling**, **roots**, **elicitation**). All of this rides on top of **JSON-RPC 2.0** messages.

### 1.2 Where *transports* fit

MCP is layered. Understanding the layering is the single most important framing for this entire document:

```
┌─────────────────────────────────────────────────────────┐
│  Application / capability layer                          │
│  tools, resources, prompts, sampling, roots, elicitation│
├─────────────────────────────────────────────────────────┤
│  Protocol / session layer                               │
│  JSON-RPC 2.0 messages: requests, responses,            │
│  notifications; initialize handshake; capability        │
│  negotiation; lifecycle                                  │
├─────────────────────────────────────────────────────────┤
│  TRANSPORT LAYER   ←──────────  THIS DOCUMENT            │
│  how JSON-RPC bytes actually move between host & server: │
│  • stdio (local subprocess)                             │
│  • Streamable HTTP (remote/networked)                   │
│  • (legacy) HTTP+SSE                                     │
│  • custom transports                                     │
└─────────────────────────────────────────────────────────┘
```

The **transport layer** is the bottom rung: it is responsible only for moving **JSON-RPC messages** (opaque byte-framed JSON) from one peer to the other and back, reliably and in order, plus the lifecycle of the underlying connection (open, stream, reconnect, close). It knows *nothing* about tools or resources. The session/protocol layer sits on top and treats the transport as a pair of message pipes: "send this JSON-RPC message" and "here is a JSON-RPC message that arrived." This clean separation is what lets the *same* server logic run either as a local subprocess (stdio) or as a remote web service (Streamable HTTP) by swapping only the transport.

> **JSON-RPC 2.0** (explained because a newcomer may not know it): a lightweight, transport-agnostic remote-procedure-call convention where every message is a JSON object. A **request** has `{"jsonrpc":"2.0","id":<n>,"method":"...","params":{...}}`; a **response** echoes the same `id` and carries either `result` or `error`; a **notification** is a request with **no `id`**, meaning "fire and forget, do not reply." Because JSON-RPC says nothing about *how* the JSON travels, MCP must define transports to fill that gap.

### 1.3 The problem transports solve

An MCP message is just JSON. But JSON by itself cannot cross a process or network boundary — you need a concrete medium and a **framing** convention (how to tell where one message ends and the next begins). The transport layer answers concrete questions:

- **Where does the server run?** Same machine as the host (local) or across a network (remote)?
- **How are bytes carried?** Through OS pipes (stdin/stdout) or over HTTP?
- **How are messages framed?** Newline-delimited JSON? HTTP request/response bodies? Server-Sent Events?
- **How does the server push data to the host** (e.g. progress, log notifications, server-initiated requests) when many media are request/response only?
- **How are sessions, reconnection, and resumability handled** so a dropped network connection doesn't lose an in-flight tool call?
- **What is the trust and auth model** — implicit local trust vs. authenticated, authorized remote access?

### 1.4 The mental model in one paragraph

Think of MCP transport as **"a bidirectional JSON-RPC pipe with a lifecycle."** Locally, that pipe is literally a child process's stdin/stdout (cheap, zero-config, fully trusted). Remotely, that pipe is an HTTP endpoint that accepts POSTed JSON-RPC and can *optionally upgrade a response into a stream* (using Server-Sent Events) so the server can push messages back as they happen; sessions, resumability, and OAuth-based authorization layer on top. The protocol/session machinery above doesn't care which pipe it is — it just calls `send(message)` and reacts to `onMessage(message)`.

### 1.5 When you reach for transports (decision preview)

- **Local tools, dev workflows, private data on the user's machine, CLI-launched servers →** stdio.
- **Shared/multi-user services, SaaS connectors, anything behind a network or an auth boundary, web-hosted servers →** Streamable HTTP.
- **Integrating with an existing client/server that only speaks the old protocol →** HTTP+SSE (legacy; support for backward compatibility only).
- **Embedding inside a single process / writing tests / unusual media (WebSocket, in-memory, message queue) →** custom/in-memory transport.

Full decision tables are in [§8](#8-tradeoffs--decision-frameworks).

---

## 2. Foundations from first principles

This section builds the vocabulary and the substrate. If you already know JSON-RPC, pipes, HTTP, and SSE cold, skim — but the MCP-specific framing rules at the end matter.

### 2.1 Hosts, clients, and servers

MCP defines three roles. Getting them straight removes most confusion later:

- **Host** — the user-facing LLM application (Claude Desktop, an IDE plugin, an agent runtime). The host owns the LLM, decides which servers to connect to, and aggregates their capabilities. A host may run *many* clients.
- **Client** — a connector object *inside* the host. **One client per server connection.** The client speaks MCP to exactly one server and manages that server's transport. This 1:1 client↔server pairing is a deliberate isolation boundary: one server cannot see another server's traffic.
- **Server** — the program exposing tools/resources/prompts. It may be a tiny local script or a large remote service.

```
        Host process
   ┌────────────────────────┐
   │  LLM                    │
   │  ┌──────┐  ┌──────┐     │      stdio        ┌──────────────┐
   │  │client│──┼──────┼─────┼──────────────────▶│ local server │ (subprocess)
   │  └──────┘  │      │     │                    └──────────────┘
   │  ┌──────┐  │      │     │   Streamable HTTP   ┌──────────────┐
   │  │client│──┼──────┼─────┼────────────────────▶ remote server │ (web service)
   │  └──────┘  └──────┘     │                     └──────────────┘
   └────────────────────────┘
```

Because each client manages its own transport, **transport choice is per-connection**, not global. A single host can hold a stdio connection to a local filesystem server *and* a Streamable HTTP connection to a remote GitHub server at the same time.

### 2.2 The JSON-RPC 2.0 substrate (in depth)

Every byte a transport carries is a JSON-RPC 2.0 message. There are exactly three shapes:

**Request** (expects a reply):
```json
{ "jsonrpc": "2.0", "id": 7, "method": "tools/call",
  "params": { "name": "search", "arguments": { "q": "mcp transports" } } }
```

**Response** (success or error, matched by `id`):
```json
{ "jsonrpc": "2.0", "id": 7, "result": { "content": [ { "type": "text", "text": "..." } ] } }
{ "jsonrpc": "2.0", "id": 7, "error": { "code": -32601, "message": "Method not found" } }
```

**Notification** (no `id`, no reply ever):
```json
{ "jsonrpc": "2.0", "method": "notifications/progress",
  "params": { "progressToken": "abc", "progress": 0.5 } }
```

Key facts that drive transport design:

- **`id` matters.** The transport need not preserve ordering of *responses*; the session layer correlates a response to its request by `id`. This is why HTTP — where requests can complete out of order — works fine.
- **Bidirectionality.** MCP is *not* a simple client-asks/server-answers protocol. The **server** can send requests *to the client* (e.g. `sampling/createMessage` to ask the host's LLM to generate text, or `roots/list` to ask which directories it may access). Therefore the transport must be **full-duplex**: both ends must be able to *initiate*. This is trivial over stdio (two independent pipes) but is the central design challenge for HTTP (which is fundamentally client-initiated request/response), and it is the reason SSE and Streamable HTTP exist.
- **Batching.** JSON-RPC permits sending an *array* of messages as one batch. MCP historically allowed batches; the **2025-06-18 revision removed support for JSON-RPC batching** to simplify implementations. Flag this as version-specific (see §7.7).

> **Full-duplex / bidirectional** (explained): a channel where both endpoints can send at any time, independently, without waiting to be asked — like a phone call, not a walkie-talkie. **Half-duplex** is one-direction-at-a-time. Plain HTTP request/response is effectively half-duplex per exchange (client speaks, server answers), which is why server-initiated messages need special handling.

### 2.3 The protocol revision string (you must know this)

MCP versions are dated strings, e.g. `2024-11-05`, `2025-03-26`, `2025-06-18`. The version is negotiated during `initialize`. **Transport behavior is version-gated**, so whenever a transport detail is given below, the revision matters:

| Revision | Transport-relevant change |
|---|---|
| `2024-11-05` | Original spec. Transports: **stdio** and **HTTP + SSE** (two-endpoint design). |
| `2025-03-26` | Introduced **Streamable HTTP** (single endpoint, optional SSE upgrade) replacing the old HTTP+SSE design. Added resumability via event IDs and `Last-Event-ID`. |
| `2025-06-18` | Removed JSON-RPC **batching**; clarified/strengthened the **authorization** spec (OAuth 2.1, Resource Server classification, `WWW-Authenticate`, protected-resource metadata RFC 9728); added `MCP-Protocol-Version` request header requirement for HTTP. |

> **Always check which revision your host and server speak.** Mismatches around SSE-vs-Streamable-HTTP and batching are the most common interop bugs.

### 2.4 Pipes, file descriptors, and stdio (for the transport newcomer)

The local transport rides on the oldest IPC mechanism in Unix: **standard streams**.

> **File descriptor (fd)**: a small integer the OS hands a process to refer to an open I/O channel. By convention **fd 0 = stdin** (input), **fd 1 = stdout** (output), **fd 2 = stderr** (error/diagnostics). When a parent process spawns a child, it can **redirect** these fds to **pipes** it holds the other end of.
>
> **Pipe**: a unidirectional, in-kernel byte buffer with a write end and a read end. `parent_write → kernel_buffer → child_read`. Two pipes give you full-duplex: one for parent→child, one for child→parent.
>
> **Subprocess / child process**: a program launched by another program. The launcher (the MCP host) controls the child's lifecycle (start, signal, kill) and owns the child's stdin/stdout pipes.

So a **stdio MCP transport** is: the host launches the server as a child process, **writes** JSON-RPC requests to the child's **stdin**, and **reads** JSON-RPC messages from the child's **stdout**. The child writes its replies and server-initiated messages to **stdout**, and is free to write human-readable logs to **stderr** (which the host may capture but must not parse as protocol). That last rule is critical and a frequent bug source — see §2.6.

### 2.5 HTTP, SSE, and chunked transfer (for the transport newcomer)

> **HTTP** (HyperText Transfer Protocol): a request/response protocol. The client opens a connection, sends a request (method + path + headers + optional body), the server returns a response (status + headers + body). Classic HTTP is **client-initiated**: the server cannot speak unless asked. This is the core mismatch with MCP's bidirectionality.
>
> **Server-Sent Events (SSE)**: a standardized way for a server to *push* a stream of events to a client over a single long-lived HTTP response. The client makes one GET (or, in MCP's case, sometimes receives an SSE-typed response to a POST) with `Accept: text/event-stream`; the server replies with `Content-Type: text/event-stream` and then keeps the response **open**, writing events as text frames:
> ```
> id: 42
> event: message
> data: {"jsonrpc":"2.0", ... }
>
> ```
> Each event is `field: value` lines terminated by a blank line. The `id:` field lets the client resume after a drop via the `Last-Event-ID` request header. SSE is **one-directional** (server→client only) and rides over ordinary HTTP, so it traverses proxies and firewalls that block WebSocket.
>
> **Chunked transfer encoding**: an HTTP mechanism to send a body of unknown length as a sequence of chunks. SSE relies on the response being streamable (not buffered whole). Intermediaries that buffer responses break SSE — a real operational hazard (see §9).
>
> **WebSocket** (mentioned for contrast): a protocol that upgrades an HTTP connection into a full-duplex byte channel. MCP's standard transports do **not** mandate WebSocket; SSE was chosen because it is simpler, rides plain HTTP, and is friendlier to existing HTTP infrastructure and serverless platforms.

### 2.6 MCP's transport framing rules (the concrete contract)

Whatever the medium, MCP fixes how messages are delimited:

**stdio framing.** Messages are **newline-delimited JSON** (a.k.a. NDJSON / JSON Lines): each JSON-RPC message is serialized to a single line of UTF-8 with **no embedded newlines**, terminated by `\n`. Hard rules:
- The message JSON **must not contain literal newlines** (so a reader can split on `\n`).
- Messages flow on **stdout** (server→host) and **stdin** (host→server).
- The server **must not** write anything to stdout that is not a valid MCP message. **Non-protocol logging goes to stderr.** A stray `println` to stdout corrupts the stream and is the #1 stdio bug.

**HTTP framing (Streamable HTTP).** The server exposes a single **MCP endpoint** path (e.g. `/mcp`) that supports both `POST` and `GET`:
- The client **POSTs** a JSON-RPC message (or, pre-2025-06-18, a batch). The client must send `Accept: application/json, text/event-stream`.
- The server responds either with a single `Content-Type: application/json` body (one response), **or** with `Content-Type: text/event-stream` (an SSE stream) when it needs to send back zero-or-more server messages plus the eventual response(s).
- The client may also issue a `GET` to the same endpoint (with `Accept: text/event-stream`) to open a standing SSE stream for **server-initiated** messages that aren't tied to a specific POST.
- Sessions are tracked with an **`Mcp-Session-Id`** response/request header.

These framing rules are the real "interface" of an MCP transport. Everything else (subprocess management, OAuth, reconnection) is plumbing around them.

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, the lifecycle and the data/control flow for each transport, then the cross-cutting session machinery.

### 3.1 The connection lifecycle (transport-agnostic)

Regardless of transport, an MCP connection goes through the same phases. The transport implements the *open/close/stream* parts; the session layer drives the *handshake*.

```
  [ open transport ]
        │
        ▼
  initialize  (client → server request)        ── capability negotiation begins
        │
        ▼
  initialize result (server → client)          ── server advertises capabilities + protocolVersion
        │
        ▼
  notifications/initialized (client → server)  ── handshake complete; normal ops may begin
        │
        ▼
  ── normal operation ──
     tools/list, tools/call, resources/read, prompts/get,
     server→client sampling/createMessage, roots/list,
     notifications (progress, log, list-changed), pings ...
        │
        ▼
  [ shutdown ]  — transport-specific (close pipes / close HTTP session)
```

> **Capability negotiation** (explained): during `initialize`, each side declares what it supports (e.g. the client says "I can do sampling and elicitation," the server says "I have tools and resources, and I emit list-changed notifications"). Both sides must respect the negotiated set. The transport doesn't participate in negotiation content, but it must be *open* before `initialize` can be sent and must stay open for the negotiated features (e.g. a server can only push `list-changed` notifications if the transport supports server→client messages).

The **`initialize` request must be the very first message** and (in current revisions) must not be batched with anything else.

### 3.2 stdio transport — internal workflow, step by step

**Setup / launch.** The host reads a server config that specifies a `command`, `args`, optional `env`, and `cwd`. The host then:

1. **Spawns the child process** using the OS process API (`ProcessBuilder` on the JVM; `child_process.spawn` in Node; `subprocess.Popen` in Python), with stdin and stdout connected to host-owned pipes and stderr either inherited or captured.
2. **Wires the streams**: host's writer → child stdin; child stdout → host's reader. A line-oriented reader is attached to stdout.
3. **Sends `initialize`** as the first newline-terminated JSON line on the child's stdin.
4. Reads the child's `initialize` result line from stdout, then sends `notifications/initialized`.

**Steady-state data flow.**

- **Host → server**: serialize JSON-RPC to one line, append `\n`, write to child stdin, flush. *Flushing matters* — buffered writers can deadlock (see §9.2).
- **Server → host**: server writes one line per message to stdout; the host's reader splits on `\n`, parses each line, and dispatches by shape (response→correlate by `id`; request→handle server-initiated; notification→fan out).
- **Concurrency**: because requests carry `id`s, the host may have many in-flight at once; replies may come back in any order and are matched by `id`. The reader loop typically runs on a dedicated thread (JVM) or the event loop (Node/Python async).
- **Logging**: server diagnostics go to **stderr**, which the host captures separately (often surfacing it in a log panel). stderr never carries protocol.

**Control flow / state machine of a stdio connection:**

```
  NEW ──spawn ok──▶ STARTING ──initialize result──▶ READY
   │                   │                              │
   │ spawn fail        │ init timeout/err            │ child exits / EOF on stdout
   ▼                   ▼                              ▼
  FAILED            FAILED                          CLOSED
```

**Shutdown (ordered, important to get right).** The spec prescribes a graceful sequence initiated by the *client/host*:
1. **Close the child's stdin** (sends EOF). A well-behaved server treats stdin EOF as "begin shutdown" and exits.
2. If the server does not exit within a grace period, **send `SIGTERM`** (a polite "please terminate" signal).
3. If still alive after another grace period, **send `SIGKILL`** (unconditional kill; cannot be caught).
4. The server may also initiate shutdown by closing its stdout and exiting.

> **SIGTERM / SIGKILL** (explained): Unix signals. `SIGTERM` (15) asks a process to terminate and *can* be trapped to do cleanup. `SIGKILL` (9) forcibly kills it and cannot be caught or ignored. The escalation EOF→SIGTERM→SIGKILL gives the server a chance to flush and clean up before being forced.

**Why stdio is the way it is.** Two private pipes give you free, in-order, full-duplex, framed-by-newline byte transport with OS-managed lifecycle and zero network surface. The cost: the server lives and dies with the host, runs on the same machine, and is implicitly trusted (it inherits the user's privileges).

### 3.3 Streamable HTTP — internal workflow, step by step

Streamable HTTP (introduced `2025-03-26`) is the current networked transport. The defining idea: **one endpoint, and any response may *optionally* be upgraded to an SSE stream.** This collapses the old two-endpoint HTTP+SSE design (§3.4) into something far simpler to deploy and to scale.

**The single MCP endpoint** (call it `/mcp`) supports three HTTP interactions:

**(A) Client POSTs a JSON-RPC message → server chooses the response style.**

1. Client sends:
   ```
   POST /mcp HTTP/1.1
   Content-Type: application/json
   Accept: application/json, text/event-stream
   Mcp-Session-Id: <id>            (after the session is established)
   MCP-Protocol-Version: 2025-06-18 (required on non-initialize requests in current rev)
   <JSON-RPC message in body>
   ```
2. The server inspects the message:
   - If it can answer with **one** response and needs to push nothing else, it returns `200 OK` with `Content-Type: application/json` and the single JSON-RPC response in the body. The request completes; the connection can be reused/closed.
   - If it wants to send **interim** messages (progress notifications, log messages, *server→client requests* like `sampling/createMessage` that the call depends on, or multiple responses), it returns `200 OK` with `Content-Type: text/event-stream` and **keeps the response open**, emitting SSE events. The **final** SSE event(s) carry the JSON-RPC response(s); after delivering the response to every request in that POST, the server **closes the stream**.
   - If the POSTed body contained **only notifications/responses** (no requests needing answers), the server returns **`202 Accepted`** with an empty body (nothing to answer).

**(B) Client GETs the endpoint to receive unsolicited server messages.**

```
GET /mcp HTTP/1.1
Accept: text/event-stream
Mcp-Session-Id: <id>
Last-Event-ID: <id>            (optional, for resuming)
```
The server returns an SSE stream it can use to push messages *not* tied to any particular client POST — e.g. `notifications/tools/list_changed`, or a server-initiated request. The server may also legitimately return `405 Method Not Allowed` if it doesn't offer a standing server→client stream.

**(C) Client DELETEs the endpoint to end the session.**

```
DELETE /mcp HTTP/1.1
Mcp-Session-Id: <id>
```
Tells the server to tear down session state. (Servers may also expire sessions on their own.)

**Session establishment.** On the response to the **`initialize`** POST, the server *may* assign a session by returning an **`Mcp-Session-Id`** header (an opaque, sufficiently random, ASCII string). The client must echo that header on **every** subsequent request. If a server that requires a session receives a request without a valid `Mcp-Session-Id`, it returns **`400 Bad Request`**. If a client receives **`404 Not Found`** for a session id, the session has expired and the client must re-`initialize`.

**State machine (Streamable HTTP session):**

```
  (no session) ──POST initialize──▶ INITIALIZING
        │                                │ 200 + Mcp-Session-Id
        │                                ▼
        │                            ACTIVE ──DELETE / expiry / 404──▶ TERMINATED
        │                                │
        │                                │ connection drop mid-stream
        │                                ▼
        │                           (reconnect: GET with Last-Event-ID) ──▶ ACTIVE
```

**Resumability and redelivery (the clever part).** When a server emits SSE events on a stream, it may attach an `id:` to each event. If the network connection drops, the client reconnects (a fresh `GET`, or re-POST depending on context) and sends `Last-Event-ID: <last id it saw>`. The server then **replays only the messages that came after that id on that stream** — not the whole session. This makes long-running tool calls survive transient disconnects without re-executing them. Crucially, event ids must be **per-stream unique** and the server must remember enough history to replay; servers commonly cap the replay buffer.

**Why Streamable HTTP works for serverless and load balancers.** Because most interactions are ordinary POST→JSON responses (stateless-friendly), and the SSE upgrade is *optional and per-request*, a server can be horizontally scaled. The `Mcp-Session-Id` lets a load balancer pin a session to a backend (sticky sessions) when the server keeps in-memory session state, or the server can externalize session state (Redis, etc.) to stay stateless. Contrast with the old HTTP+SSE design, where the *standing* SSE connection had to stay pinned to one process for the whole session — far harder to scale and fragile across deploys.

### 3.4 The legacy HTTP+SSE transport — and why it was replaced

The original `2024-11-05` networked transport used **two endpoints**:

1. A long-lived **SSE endpoint** (`GET /sse`): the client opens this first; the server immediately sends an `endpoint` event telling the client the URL of the second endpoint, then keeps the SSE stream open for *all* server→client traffic.
2. A **message endpoint** (`POST /messages?...`): the client POSTs every client→server JSON-RPC message here; the server's *reply* arrives back over the **SSE** stream, not in the POST response.

**Internal flow:**
```
client GET /sse ───────────────▶ server: 200 text/event-stream
                                  event: endpoint
                                  data: /messages?sessionId=XYZ
client POST /messages?sessionId=XYZ {json-rpc request}
        ◀───────────────────────  (HTTP 202; actual response comes via SSE)
        ◀── SSE: data:{json-rpc response}
```

**Why it was problematic (the motivation for Streamable HTTP):**

- **Mandatory long-lived connection.** The SSE GET had to stay open for the *entire* session. That is hostile to serverless/function platforms (which kill long requests) and to load balancers (the session is pinned to one process; a deploy or scale-in drops it).
- **No resumability.** A dropped SSE connection meant a lost session — no `Last-Event-ID` replay; the client had to start over.
- **Two endpoints, more state.** Coordinating the `endpoint` handshake, session id in a query string, and reply-over-SSE was fiddly and easy to get wrong.
- **Awkward for simple request/response.** Even a trivial `tools/list` required the whole SSE machinery to be live.

**Streamable HTTP fixes all four**: single endpoint, SSE only when needed, optional resumability with event ids, and plain JSON responses for the common case. The spec explicitly frames Streamable HTTP as the **replacement**; HTTP+SSE remains documented only for **backwards compatibility**. Backward-compat strategies:
- A **client** wanting to support old servers can POST an `initialize`; if it gets the new behavior, use Streamable HTTP; if it gets a `4xx`, fall back to opening a legacy `GET /sse`.
- A **server** can host both endpoints during a migration window.

### 3.5 Custom / in-memory transports

The transport interface is small enough that anything carrying ordered, framed, bidirectional JSON-RPC works. Common custom transports:

- **In-memory / in-process**: client and server in the same process connected by two queues. Indispensable for unit/integration tests (no subprocess, no sockets). All SDKs ship one.
- **WebSocket**: a community option where bidirectionality is native; not part of the standard transport set but easy to implement against the transport interface.
- **Message-queue / broker**: bridging MCP over Kafka/NATS/AMQP for fan-out or durability; you implement framing as one message = one JSON-RPC message.

The session layer only needs `start()`, `send(message)`, `onMessage(cb)`, `onClose(cb)`, `onError(cb)`, and `close()` — implement those over any medium and you have a transport.

### 3.6 Cross-cutting: streaming partial results, progress, and cancellation

These are session-layer features that *depend on* what the transport can carry:

- **Progress notifications** (`notifications/progress`): a long tool call attaches a `progressToken` (passed in the request's `_meta`); the server emits progress notifications referencing that token. On stdio, these flow on stdout; on Streamable HTTP, they flow on the SSE upgrade of the POST that started the call. **No SSE upgrade ⇒ no interim progress for that call.**
- **Partial / streamed results**: MCP does not (as of these revisions) define token-level streaming of a *single* tool result the way an LLM streams tokens; "streaming" in MCP means the server can send *multiple messages* (notifications, then the final response) over the open channel. True incremental result payloads are typically modeled as repeated notifications plus a final result.
- **Cancellation** (`notifications/cancelled`): the client can notify the server to abort an in-flight request by `id`. Requires server→client... no — it's client→server, but the server must be watching its input concurrently with doing work, which on stdio means reading stdin while computing, and on HTTP means the cancel is a separate POST referencing the request id.
- **Ping** (`ping` request, either direction): a liveness check. Useful on HTTP to keep intermediaries from idling out an SSE stream and to detect dead peers.

### 3.7 End-to-end trace: a `tools/call` over Streamable HTTP with progress

To make the data/control flow concrete, here is a full trace of one tool call that reports progress:

```
1. Client → POST /mcp
   Headers: Accept: application/json, text/event-stream
            Mcp-Session-Id: S1; MCP-Protocol-Version: 2025-06-18
   Body: {"jsonrpc":"2.0","id":42,"method":"tools/call",
          "params":{"name":"reindex","arguments":{"path":"/data"},
                    "_meta":{"progressToken":"p1"}}}

2. Server decides it will stream → responds 200, Content-Type: text/event-stream.

3. Server → SSE event (id:1):
   data: {"jsonrpc":"2.0","method":"notifications/progress",
          "params":{"progressToken":"p1","progress":0.25,"total":1.0}}

4. (connection drops here)

5. Client reconnects → GET /mcp, Mcp-Session-Id: S1, Last-Event-ID: 1
   Server replays events after id 1 on that stream.

6. Server → SSE event (id:2): progress 0.75
7. Server → SSE event (id:3):
   data: {"jsonrpc":"2.0","id":42,"result":{"content":[{"type":"text","text":"done"}]}}

8. Server closes the stream (all requests in the POST answered).
9. Client correlates id:42 → resolves the pending tools/call promise.
```

Notice: the *session* (S1) survived the drop; only the *stream* reconnected; the tool did **not** re-run; the response matched by `id` 42. That is the whole value proposition of Streamable HTTP resumability.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, classes, headers, status codes, config keys, and CLI tools you actually touch. Where a default is genuinely undefined by the spec or SDK-specific, that is stated.

### 4.1 The transport interface (conceptual, all SDKs)

Every SDK models a transport with roughly this shape. Names differ; semantics don't.

| Operation | Purpose | Notes |
|---|---|---|
| `start()` / `connect()` | Open the medium (spawn process / open HTTP session). | Must complete before `initialize` is sent. |
| `send(message)` | Serialize and write one JSON-RPC message. | Must frame correctly (newline for stdio; HTTP body for HTTP). |
| `onMessage(cb)` / message callback | Deliver a parsed inbound JSON-RPC message. | Session layer dispatches by `id`/shape. |
| `onClose(cb)` | Signal the medium closed (EOF, HTTP session end). | Triggers cleanup/reconnect. |
| `onError(cb)` | Surface transport errors (parse error, IO error). | Distinct from JSON-RPC `error` responses. |
| `close()` | Graceful shutdown of the medium. | stdio: close stdin → SIGTERM → SIGKILL; HTTP: DELETE session. |
| `sessionId` (HTTP) | Track `Mcp-Session-Id`. | Set from the `initialize` response. |
| `setProtocolVersion(v)` (HTTP) | Record negotiated version to emit `MCP-Protocol-Version`. | Required header in current rev. |

### 4.2 stdio transport — classes by SDK

| SDK | Server-side type | Client-side type | Notes |
|---|---|---|---|
| **Java** (`io.modelcontextprotocol.sdk`) | `StdioServerTransportProvider` | `StdioClientTransport` (with `ServerParameters`) | Java SDK is the reference JVM impl; integrates with Spring AI. |
| **Python** (`mcp`) | `mcp.server.stdio.stdio_server()` (context manager) | `mcp.client.stdio.stdio_client(StdioServerParameters(...))` | `StdioServerParameters(command, args, env, cwd)`. |
| **TypeScript** (`@modelcontextprotocol/sdk`) | `StdioServerTransport` | `StdioClientTransport({command, args, env, cwd})` | |

**stdio launch parameters (config keys):**

| Key | Meaning | Default |
|---|---|---|
| `command` | Executable to run (e.g. `node`, `python`, `java`, `npx`, `uvx`). | required |
| `args` | Argument array (script path, flags). | `[]` |
| `env` | Environment variables for the child. | host may pass a minimal env or inherit; **be explicit** |
| `cwd` | Working directory of the child. | host default (often the host's cwd) |
| stderr handling | Where child stderr goes. | host-defined (inherit/capture) |

### 4.3 Streamable HTTP transport — classes by SDK

| SDK | Server-side | Client-side |
|---|---|---|
| **Java** | `HttpServletSseServerTransportProvider` / WebFlux/WebMVC Streamable HTTP providers (Spring AI MCP) | `HttpClientStreamableHttpTransport` / WebFlux SSE client transport |
| **Python** | `streamable_http_app()` / mount via Starlette/FastAPI; `FastMCP(... ).streamable_http_app()` | `streamablehttp_client(url, headers=...)` |
| **TypeScript** | `StreamableHTTPServerTransport({sessionIdGenerator, ...})` | `StreamableHTTPClientTransport(url, {requestInit, authProvider})` |

> The Java SDK historically shipped SSE providers first; current Spring AI / reference SDK releases include Streamable HTTP. **Pin versions** and check the changelog — this area moves fast (version-specific).

### 4.4 HTTP headers used by the transport

| Header | Direction | Purpose |
|---|---|---|
| `Accept: application/json, text/event-stream` | client→server | Client signals it accepts either a JSON response or an SSE upgrade. |
| `Content-Type: application/json` | both | A single JSON-RPC message/response body. |
| `Content-Type: text/event-stream` | server→client | The response is an SSE stream. |
| `Mcp-Session-Id` | server→client (init), then client→server | Opaque session identifier; echoed on all later requests. |
| `MCP-Protocol-Version` | client→server | Negotiated revision; required on non-initialize requests (current rev). |
| `Last-Event-ID` | client→server | Resume an SSE stream after the given event id. |
| `id:` (SSE field) | server→client | Per-stream event id enabling resumption. |
| `Authorization: Bearer <token>` | client→server | OAuth access token for remote auth. |
| `WWW-Authenticate` | server→client (401) | Points the client at auth metadata (RFC 9728 protected-resource metadata). |
| `Origin` | client→server | Server **must validate** to prevent DNS-rebinding (see §6.4). |

### 4.5 HTTP status codes you must handle

| Status | Meaning in MCP transport |
|---|---|
| `200 OK` | Response present (JSON or SSE stream). |
| `202 Accepted` | POST contained only notifications/responses; nothing to answer. |
| `400 Bad Request` | Malformed request or missing required `Mcp-Session-Id`. |
| `401 Unauthorized` | Auth required/failed; look at `WWW-Authenticate`. |
| `403 Forbidden` | Authenticated but not allowed. |
| `404 Not Found` | Unknown endpoint **or** expired/invalid session → client must re-`initialize`. |
| `405 Method Not Allowed` | Server doesn't support that HTTP verb (e.g. no standing `GET` SSE). |
| `406 Not Acceptable` | Client's `Accept` header didn't include the needed media types. |
| `5xx` | Server error; client may retry with backoff. |

### 4.6 JSON-RPC error codes relevant at the transport/session boundary

| Code | Name | Typical cause |
|---|---|---|
| `-32700` | Parse error | Invalid JSON arrived (framing/encoding bug). |
| `-32600` | Invalid Request | Not a valid JSON-RPC object. |
| `-32601` | Method not found | Unknown `method`. |
| `-32602` | Invalid params | Bad arguments. |
| `-32603` | Internal error | Server-side failure. |
| `-32002` (SDK-common) | Resource not found / request cancelled (SDK-specific) | Varies; check SDK. |

> Distinguish **transport errors** (connection dropped, parse error) from **JSON-RPC error responses** (a valid response whose `error` field is set). They are handled in different layers.

### 4.7 CLI / tooling

| Tool | What it does |
|---|---|
| **MCP Inspector** (`npx @modelcontextprotocol/inspector`) | Interactive GUI/CLI to launch and probe a server over stdio or HTTP: list tools, call them, watch raw JSON-RPC. The single most useful debugging tool. |
| **`npx`** | Node package runner; commonly used as the `command` to run a published Node MCP server without a global install. |
| **`uvx` / `uv`** | Python (Astral `uv`) runner; common `command` for Python MCP servers (`uvx some-mcp-server`). |
| **`mcp dev` / `mcp run`** (Python SDK CLI) | Run a FastMCP server locally for development. |
| **`curl`** | Manually POST JSON-RPC to a Streamable HTTP endpoint to inspect behavior (great for reproducing header/status bugs). |
| **`claude mcp add` / config file** | Host-specific registration of servers (Claude Desktop `claude_desktop_config.json`, Claude Code `.mcp.json`). |

---

## 5. Code examples by use case

All examples are complete enough to adapt. JVM-first where the topic is language-relevant; config and other languages where they illustrate transport mechanics best. Comments flag the non-obvious lines.

### 5.1 Use case: a minimal stdio MCP server (Java, reference SDK)

```java
// build.gradle (excerpt)
// implementation("io.modelcontextprotocol.sdk:mcp:<pin-a-version>")

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

public class StdioEchoServer {
  public static void main(String[] args) {
    // CRITICAL on stdio: never write protocol-unrelated text to STDOUT.
    // Route all logging to STDERR (or a file). A single stray System.out.println
    // corrupts the newline-delimited JSON stream and breaks the client.
    System.setErr(System.err); // (illustrative) keep stderr for logs

    var mapper = new ObjectMapper();

    // The stdio transport provider wires this server to stdin/stdout.
    var transport = new StdioServerTransportProvider(mapper);

    // Declare one tool. Its JSON schema tells the client how to call it.
    var inputSchema = """
      { "type":"object",
        "properties": { "text": {"type":"string"} },
        "required": ["text"] }
      """;
    var echoTool = new Tool("echo", "Echo back the provided text", inputSchema);

    McpServer.sync(transport)
        .serverInfo("stdio-echo", "1.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).build())
        .tools(new McpServerFeatures.SyncToolSpecification(
            echoTool,
            (exchange, callArgs) -> {
              // callArgs is the parsed "arguments" object from tools/call.
              Object text = callArgs.get("text");
              return new CallToolResult(
                  List.of(new TextContent("echo: " + text)),
                  false /* isError */);
            }))
        .build();

    // The provider runs a read loop on stdin and a writer on stdout until EOF.
    // When the host closes our stdin, we receive EOF and should exit.
  }
}
```

Key points: the *only* thing on stdout is MCP JSON; the tool handler is synchronous here; capabilities advertise `tools` so the client knows to call `tools/list`.

### 5.2 Use case: launching that stdio server from a host (worked config)

**Claude Desktop** (`claude_desktop_config.json`) launching a Java stdio server packaged as a fat JAR:

```json
{
  "mcpServers": {
    "stdio-echo": {
      "command": "java",
      "args": ["-jar", "/opt/mcp/stdio-echo-all.jar"],
      "env": {
        "JAVA_TOOL_OPTIONS": "-Xms64m -Xmx256m",
        "LOG_LEVEL": "info"
      },
      "cwd": "/opt/mcp"
    }
  }
}
```

**Claude Code / generic `.mcp.json`** launching a published Node server via `npx` and a Python server via `uvx`:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/projects"]
    },
    "sqlite": {
      "command": "uvx",
      "args": ["mcp-server-sqlite", "--db-path", "/data/app.db"],
      "env": { "READONLY": "1" }
    }
  }
}
```

Notes: `-y` makes `npx` non-interactive (no install prompt that would hang the launch); pass the *allowed directory* as an arg to the filesystem server (least privilege); set `env` explicitly — do not rely on the child inheriting secrets you didn't intend to share.

### 5.3 Use case: connecting to a stdio server as a client (Python)

```python
import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def main():
    params = StdioServerParameters(
        command="java",
        args=["-jar", "/opt/mcp/stdio-echo-all.jar"],
        env={"LOG_LEVEL": "info"},
        cwd="/opt/mcp",
    )
    # stdio_client spawns the subprocess and returns (read, write) streams.
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()          # sends initialize + initialized
            tools = await session.list_tools()  # tools/list
            print("tools:", [t.name for t in tools.tools])
            result = await session.call_tool("echo", {"text": "hi"})  # tools/call
            print("result:", result.content)
    # Leaving the context managers closes stdin (EOF) → server shuts down.

asyncio.run(main())
```

The context-manager exit performs the graceful shutdown (close stdin → child exits). If the child ignores EOF, the SDK escalates to signals.

### 5.4 Use case: a Streamable HTTP server (Python / FastMCP) behind a real web stack

```python
# pip install "mcp[cli]" uvicorn
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("remote-tools")  # stateful by default; set stateless for serverless

@mcp.tool()
def add(a: int, b: int) -> int:
    """Add two integers."""
    return a + b

# Expose the Streamable HTTP ASGI app at /mcp.
app = mcp.streamable_http_app()

# Run with: uvicorn server:app --host 0.0.0.0 --port 8000
# Endpoint: POST/GET http://host:8000/mcp
```

For **serverless / horizontally scaled** deployments, run the server **stateless** (no per-process session memory; either no session id, or externalize session state). In FastMCP this is a constructor/config option (e.g. `stateless_http=True` in current versions) — verify the exact flag against your installed version (version-specific).

### 5.5 Use case: connecting to a Streamable HTTP server (TypeScript client) with auth

```ts
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const transport = new StreamableHTTPClientTransport(
  new URL("https://api.example.com/mcp"),
  {
    requestInit: {
      headers: { Authorization: `Bearer ${process.env.MCP_TOKEN}` }, // OAuth access token
    },
    // Optionally pass an authProvider to handle the full OAuth 2.1 flow,
    // including refresh and the 401 → WWW-Authenticate → metadata dance.
  }
);

const client = new Client({ name: "my-host", version: "1.0.0" }, { capabilities: {} });
await client.connect(transport);          // opens session, runs initialize
const tools = await client.listTools();   // tools/list
const out = await client.callTool({ name: "add", arguments: { a: 2, b: 3 } });
console.log(out);
await client.close();                     // sends DELETE to end the session
```

The transport handles `Mcp-Session-Id` capture/echo and SSE upgrades automatically; you only inject the auth header (or an `authProvider`).

### 5.6 Use case: manual probing of a Streamable HTTP endpoint with curl

```bash
# 1) initialize (note Accept includes both media types)
curl -i -X POST https://api.example.com/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer '"$MCP_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},
                 "clientInfo":{"name":"curl","version":"0"}}}'
# → read the Mcp-Session-Id header from the response.

# 2) list tools, echoing the session id + protocol version headers
curl -i -X POST https://api.example.com/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <paste-id>' \
  -H 'MCP-Protocol-Version: 2025-06-18' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3) open a server→client SSE stream
curl -N -X GET https://api.example.com/mcp \
  -H 'Accept: text/event-stream' \
  -H 'Mcp-Session-Id: <paste-id>'        # -N disables curl buffering so you see events live
```

This is the fastest way to reproduce header/status/SSE-buffering bugs without an SDK in the way.

### 5.7 Use case: in-memory transport for tests (TypeScript)

```ts
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

// Two linked endpoints share queues — no subprocess, no socket.
const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();

// ... configure `server` with tools ...
await server.connect(serverTransport);
await client.connect(clientTransport);

const tools = await client.listTools();   // exercises the full session layer
// Fast, deterministic, hermetic — ideal for CI.
```

Use this to test tool logic and the session layer without flaky process/network setup.

### 5.8 Use case: a stdio server that reports progress during a long call (pseudocode/Java)

```java
// Inside a tool handler that does a long job, emit progress notifications
// referencing the progressToken supplied by the client in params._meta.
(exchange, callArgs) -> {
    String token = currentProgressToken(exchange); // SDK-specific accessor
    for (int i = 1; i <= 4; i++) {
        doChunkOfWork(i);
        // Progress notifications travel server→host on stdout (still newline-framed).
        exchange.notifyProgress(token, i / 4.0, 1.0);
    }
    return new CallToolResult(List.of(new TextContent("done")), false);
};
```

On stdio these notifications interleave with other stdout traffic as separate JSON lines; the host correlates them to the call via `progressToken`. (Exact API names vary by SDK version — adapt.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **stdio is the fastest transport** for local use: no TCP, no TLS, no HTTP parsing — just kernel pipe copies. Latency is microseconds; throughput is bounded by JSON (de)serialization and pipe buffer size. Prefer it whenever the server can be local.
- **HTTP overhead** comes from connection setup (TLS handshake), header parsing, and (for SSE) keeping a connection open. **Reuse connections** (HTTP keep-alive) and prefer plain JSON responses when no streaming is needed (the server controls this).
- **JSON serialization dominates** at scale. Use a fast, reused `ObjectMapper`/serializer; avoid per-message allocation churn. For large tool outputs, consider returning a **resource URI** the client can fetch lazily rather than inlining megabytes in a single response.
- **Newline framing on stdio** requires no embedded newlines; compact JSON (no pretty-print) is mandatory and also faster.
- **Backpressure**: a slow reader on either end fills the pipe/socket buffer and blocks the writer. Always drain inbound messages promptly on a dedicated reader; never do heavy work on the read thread.

### 6.2 Correctness & concurrency

- **Match responses by `id`, never by order.** Out-of-order completion is normal, especially over HTTP.
- **Flush after every write on stdio.** Buffered writers that don't flush cause classic deadlocks (both sides waiting). The reference SDKs flush; if you roll your own, flush explicitly.
- **One writer, serialized.** Concurrent threads writing to the same stdout/socket must be serialized to avoid interleaved bytes corrupting a message. Use a single writer goroutine/thread/queue.
- **Handle EOF and half-close.** stdin EOF means "client is done"; stdout EOF means "server is gone." Distinguish clean close from crash.
- **Idempotency for resumable HTTP.** With `Last-Event-ID` replay, a client might see a message twice across a reconnect; design notification handling to tolerate duplicates, and ensure the *tool itself* isn't re-executed (the spec's replay is of *messages*, not re-running the call — but your own retry logic could re-POST; guard against double execution server-side).

### 6.3 Memory

- **SSE replay buffers** cost memory: the server must retain emitted events to satisfy `Last-Event-ID`. Cap the buffer (e.g. last N events or a time window) and document the limit; beyond it, force a re-initialize.
- **Session state** held in-memory pins memory per active session and complicates scaling. Externalize it (Redis) or run stateless when you need horizontal scale.
- **Large payloads**: streaming many notifications is cheaper on memory than building one giant result. Prefer chunked notifications + final small result, or a resource the client pulls.

### 6.4 Security (the most transport-divergent concern)

**Local (stdio):**
- The server runs with **the user's full privileges** and can read anything the user can. Trust is implicit. Treat installing a stdio server like installing any local software — it is arbitrary code execution.
- **Validate inputs** anyway; a compromised host or malicious tool arguments shouldn't let the server escape its intended scope (e.g. a filesystem server must confine to its allowed roots).
- **Never log secrets to stdout** (corrupts protocol) — and be careful what you log to stderr (it may be surfaced/persisted).

**Remote (Streamable HTTP):**
- **Authentication & authorization**: current MCP defines an **OAuth 2.1**-based scheme. The server is an **OAuth Resource Server**; on missing/invalid credentials it returns **`401`** with `WWW-Authenticate` pointing at **protected-resource metadata (RFC 9728)**, from which the client discovers the **authorization server**, then runs OAuth (with **PKCE**) to get an access token presented as `Authorization: Bearer ...`.
  > **OAuth 2.1 / PKCE** (explained): OAuth 2.1 is a consolidation of OAuth 2.0 best practices (mandatory PKCE, no implicit flow). **PKCE** (Proof Key for Code Exchange) protects the authorization-code flow from interception by binding the code to a one-time secret the client generates. **Resource Server** = the API that holds protected data and validates tokens; **Authorization Server** = the service that issues tokens.
- **TLS everywhere.** Remote MCP must run over HTTPS; tokens and data in transit demand it.
- **Validate `Origin`** on every request to defend against **DNS rebinding** attacks (a malicious web page tricking a browser into POSTing to a *localhost* MCP server). The spec explicitly calls this out.
  > **DNS rebinding** (explained): an attack where a hostname first resolves to the attacker's server (to serve malicious JS) then re-resolves to `127.0.0.1`, letting that JS reach services bound to localhost. Origin validation and binding only to `127.0.0.1` (not `0.0.0.0`) for local HTTP servers mitigate it.
- **Bind local HTTP servers to `127.0.0.1`**, not `0.0.0.0`, so they aren't reachable from the network.
- **Token audience binding**: ensure tokens are scoped to *this* resource server; reject tokens minted for another audience (prevents "confused deputy" token reuse).
- **Rate limiting / DoS**: remote endpoints face the open internet; rate-limit, cap concurrent SSE streams per client, and time out idle streams.

### 6.5 Observability

- **Correlate by `id` and session.** Log `Mcp-Session-Id`, JSON-RPC `id`, `method`, and durations. This lets you reconstruct a call across an SSE reconnect.
- **stdio servers: log to stderr** in structured form (JSON lines on stderr is fine; just keep stdout pure). The host typically captures stderr.
- **Metrics to emit**: requests by method, error rate by JSON-RPC code, SSE stream count/duration, reconnect/replay counts, session create/expire, tool latency.
- **Trace propagation**: pass a trace id through `_meta` if your stack supports distributed tracing, so tool calls join your wider traces.
- **MCP Inspector** for interactive observation in dev; raw `curl -N` for SSE in prod-like probing.

### 6.6 Cost

- **stdio: near zero infra cost** — it's a child process on the user's machine. Cost is the user's CPU/RAM.
- **Remote: standard web costs** — compute, egress, TLS termination, and the *hidden* cost of long-lived SSE connections (they occupy a connection slot / a serverless invocation the whole time). Streamable HTTP's "SSE only when needed" reduces this materially vs. legacy HTTP+SSE's always-open stream.

### 6.7 Testing

- **Unit/integration**: use the **in-memory transport** (§5.7) — hermetic, fast, no flakiness.
- **stdio E2E**: spawn the real server and assert on tool calls; assert that **stdout contains only JSON** (a guard test that catches stray prints).
- **HTTP E2E**: spin the server on a random port; test session establishment, `404`→re-init, SSE upgrade, `Last-Event-ID` replay, and `401`/auth flows.
- **Contract tests**: validate request/response against the JSON schemas; the MCP Inspector can drive these.
- **Chaos**: drop the SSE connection mid-call and assert resumption replays correctly and the tool does not re-execute.

### 6.8 Production hardening checklist

- stdio: pure stdout; explicit `env`/`cwd`; least-privilege args; handle stdin EOF for graceful exit; bound resource usage (heap flags for JVM).
- HTTP: HTTPS only; OAuth with PKCE; validate `Origin`; bind local to `127.0.0.1`; require `Mcp-Session-Id`; enforce `MCP-Protocol-Version`; cap SSE replay buffer; idle/keepalive timeouts; rate limits; graceful session expiry returning `404` so clients re-init cleanly.
- Both: version-pin SDKs; negotiate protocol version explicitly; tolerate duplicate notifications; structured logging with session+id correlation.

### 6.9 Common anti-patterns

- **Writing logs/banners to stdout on a stdio server.** Breaks framing. (#1 bug.)
- **Embedding newlines in stdio messages.** Splits one message into two.
- **Assuming response order.** Breaks under HTTP concurrency.
- **Keeping a long-lived SSE per session on a serverless platform** (legacy pattern) — gets killed; use Streamable HTTP and short-lived streams.
- **Binding local HTTP to `0.0.0.0`** — exposes it to the LAN.
- **Not validating `Origin`** — DNS rebinding.
- **Inlining huge payloads** in one response — memory blowups; use resources/chunking.
- **No flush** on the stdio writer — deadlocks.
- **Putting secrets in `args`** (visible in process listings) instead of `env`.
- **Re-executing a tool on reconnect** — confuse message replay (safe) with re-POSTing the call (unsafe).

---

## 7. Advanced topics & deep internals

### 7.1 Why SSE and not WebSocket?

The standard transports deliberately avoid mandating WebSocket. SSE rides ordinary HTTP responses, so it works through existing proxies, CDNs, corporate firewalls, and serverless gateways that often block or complicate WebSocket upgrades. SSE is also simpler: text frames, built-in reconnection semantics (`Last-Event-ID`), and no separate framing layer. The tradeoff is that SSE is unidirectional (server→client); MCP composes client→server POSTs with server→client SSE to recover full duplex, rather than using a single bidirectional socket.

### 7.2 The optional-streaming insight

The cleverest aspect of Streamable HTTP is that **streaming is a per-request, server-chosen upgrade**, not a connection mode. A server can answer `tools/list` with a plain JSON body (stateless, cacheable-ish, load-balancer-friendly) and answer a long `tools/call` by upgrading that same POST to SSE. This means the *common* case stays cheap and stateless, and only calls that need it pay for streaming. It also means a deployment can be *mostly* stateless and still support push when required.

### 7.3 Resumability internals and event-id design

For replay to work, the server must (a) assign **monotonic, per-stream-unique** event ids, and (b) retain a **replay buffer** of recent events keyed by stream. On `GET` (or reconnect) with `Last-Event-ID: N`, the server resends events with id > N **on that stream only**. Design considerations:
- **Per-stream vs. global ids**: ids must be unique within the stream the client is resuming; using globally unique ids (e.g. ULIDs) is simplest and avoids collisions across streams.
- **Buffer bound**: keep last N or last T seconds; if the client's `Last-Event-ID` is older than the buffer, the server cannot safely replay — return an error/force re-initialize.
- **Exactly-once vs at-least-once**: replay gives *at-least-once* delivery (client may re-see the boundary event). Make notification handlers idempotent.
- **Final response replay**: if the connection drops *after* the server produced the result but *before* the client read it, replay must include that final response event — otherwise the call appears to hang. This is why the response is itself an SSE event with an id.

### 7.4 Session lifecycle edge cases

- **Server doesn't issue a session id**: legal. The server is then stateless per request; the client simply doesn't send `Mcp-Session-Id`.
- **Session expiry mid-stream**: server returns `404`; the client must abandon the session and re-`initialize`. In-flight calls are lost (unless the client retries them under a new session — its choice).
- **Multiple concurrent streams per session**: a session may have one standing `GET` SSE plus several POST-upgraded SSE streams simultaneously. Each has its own event-id space.
- **DELETE vs idle expiry**: clean shutdown sends `DELETE`; servers should also expire idle sessions to reclaim memory.

### 7.5 stdio deep internals: buffering, encoding, signals

- **Encoding**: messages are UTF-8. A common bug is platform-default encoding on the JVM (`Charset.defaultCharset()`); force UTF-8 on the reader/writer.
- **Line splitting**: readers must split on `\n` and tolerate `\r\n` if the OS injected it; servers must not emit `\r` inside a message.
- **Pipe buffer size**: OS pipes have a finite buffer (e.g. 64 KiB on many Linux kernels). If neither side reads while both write, the buffers fill and both block — deadlock. Always read concurrently with writing.
- **Signal handling**: a stdio server should handle stdin EOF as the primary shutdown trigger and also trap `SIGTERM` for cleanup; `SIGKILL` cannot be trapped.
- **Zombie/orphan processes**: if the host dies without reaping the child, you get an orphan. Hosts should kill children on exit; servers can detect parent death (e.g. read EOF on stdin) and self-terminate.

### 7.6 Backward compatibility bridging in detail

A robust client that must talk to both old (HTTP+SSE) and new (Streamable HTTP) servers typically:
1. POSTs `initialize` to the single endpoint with the new `Accept` header.
2. If it gets a valid Streamable HTTP response (JSON or SSE with the new behavior), it proceeds in Streamable HTTP mode.
3. If it gets a `4xx` suggesting the endpoint doesn't accept POST (old design), it falls back: open `GET /sse`, read the `endpoint` event, and POST to the advertised message endpoint, expecting replies over SSE.

A server migrating can host both endpoints during a window, then deprecate `/sse`.

### 7.7 Batching removal (version-specific)

JSON-RPC batches (sending an array of messages) were permitted in earlier MCP and **removed in `2025-06-18`**. If you target the latest spec, do not send arrays. If you must interoperate with `2025-03-26` peers that send batches, handle arrays defensively but don't originate them. This is a frequent silent incompatibility.

### 7.8 The `MCP-Protocol-Version` header requirement

Current revisions require the client to send `MCP-Protocol-Version` on HTTP requests *after* initialization, set to the negotiated version. Servers use it to apply version-specific behavior and to reject mismatches. Omitting it against a strict server yields a `400`. (Version-specific — older servers ignore it.)

### 7.9 Custom transport design notes

If you implement a WebSocket or queue transport, you only need to honor: ordered, full-duplex, framed JSON-RPC; signal open/close/error; and (if you want resumability) provide your own redelivery. You do **not** need SSE or HTTP specifics — those are concrete transports, not the contract. Keep the message boundary = one JSON-RPC message, and serialize writes.

### 7.10 Interaction with sampling, roots, elicitation (server→client requests)

Server-initiated requests (`sampling/createMessage`, `roots/list`, `elicitation/create`) require the transport to carry **server→client requests** and their **client→server responses**. On stdio this is free. On Streamable HTTP, the server sends the request over an SSE stream (often the POST-upgraded stream of the triggering call, or the standing GET stream), and the client returns the response by **POSTing** it back to `/mcp` (correlated by `id`). Understanding that the client must POST its responses to server-initiated requests is a subtle but essential detail of the HTTP transport.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Transport comparison table

| Dimension | **stdio** | **Streamable HTTP** | **Legacy HTTP+SSE** | **Custom (e.g. in-memory / WS)** |
|---|---|---|---|---|
| Locality | Local subprocess only | Local or remote | Local or remote | Anywhere |
| Medium | OS pipes (stdin/stdout) | HTTP POST + optional SSE | 2 endpoints: SSE + POST | Your choice |
| Framing | Newline-delimited JSON | HTTP body / SSE events | SSE events / POST | Your choice |
| Bidirectional | Yes (two pipes) | Yes (POST + SSE) | Yes (POST + SSE) | Depends |
| Sessions | Implicit (the process) | `Mcp-Session-Id` (optional) | Session in query string | Your choice |
| Resumability | N/A (process-bound) | Yes (`Last-Event-ID`) | No | Your choice |
| Auth | Implicit local trust | OAuth 2.1 / Bearer / TLS | OAuth (less specified) | Your choice |
| Scaling | One process per host | Horizontal (stateless or sticky) | Hard (pinned SSE) | Depends |
| Serverless-friendly | N/A | Yes | No | Depends |
| Setup cost | Lowest | Medium | Medium-high | Varies |
| Latency | Lowest | Network-bound | Network-bound | Varies |
| Status | Current | Current (preferred remote) | Deprecated/compat-only | Non-standard |

### 8.2 Use-when / avoid-when

**stdio — use when:**
- The server is local to the user, operating on local resources (files, local DB, local CLI).
- You want zero network surface and the simplest possible setup.
- The server is per-user and trusted (installed by the user).

**stdio — avoid when:**
- The server must be shared across users/machines.
- The server can't run on the user's machine (heavy deps, secrets that must stay server-side).
- You need centralized auth, audit, or scaling.

**Streamable HTTP — use when:**
- The server is remote, multi-user, or a hosted SaaS connector.
- You need auth/authorization, audit, rate limiting, central control.
- You need horizontal scaling and/or serverless deployment.
- You need resumable long-running calls across flaky networks.

**Streamable HTTP — avoid when:**
- The server is trivially local and adding HTTP/TLS/auth is pure overhead (use stdio).

**Legacy HTTP+SSE — use when:**
- You must interoperate with an existing peer that only speaks the old protocol. Otherwise avoid.

**Custom — use when:**
- Tests (in-memory), embedding in one process, or an environment that mandates a specific medium (existing WebSocket gateway, message bus). Accept that it's non-standard.

### 8.3 Decision flow

```
Is the server on the user's machine and per-user/trusted?
   ├─ yes → stdio
   └─ no  → Does it need network/auth/scale/multi-user?
              ├─ yes → Streamable HTTP
              └─ "I'm only writing a test or embedding in-process" → in-memory custom
Need to talk to an old-protocol peer? → HTTP+SSE (compat bridge), else don't.
```

---

## 9. Failure modes & debugging

### 9.1 stdio: garbage on stdout / "failed to parse message"

**Symptom:** client logs JSON parse errors; server seems to "do nothing"; handshake never completes.
**Cause:** the server wrote non-protocol text to **stdout** (a banner, a `print`, a dependency's logging defaulting to stdout, or a debugger).
**Diagnose:** run the server standalone and pipe stdout to a file; inspect for any non-JSON line. Or use **MCP Inspector**, which shows raw frames. Search the codebase and dependencies for stdout writes.
**Fix:** route all logging to **stderr** or a file; configure logging frameworks (e.g. Logback/SLF4J) to use stderr; never `System.out.println`.

### 9.2 stdio: hang / deadlock

**Symptom:** both sides appear stuck; no messages flow.
**Cause:** unflushed writer (message sits in a buffer) or both sides writing without reading (pipe buffer full).
**Diagnose:** thread dump (`jstack`) the JVM server — a thread blocked in a write to stdout while the reader thread isn't draining points to backpressure; check for missing flush.
**Fix:** flush after each write; run the reader concurrently on its own thread; never block the read loop with heavy work.

### 9.3 stdio: server doesn't exit after host closes

**Symptom:** orphaned server processes accumulate.
**Cause:** server ignores stdin EOF; host doesn't escalate to signals.
**Diagnose:** `ps`/`pgrep` for orphans; check whether the server reads stdin to EOF.
**Fix:** server should treat stdin EOF as shutdown; host should escalate EOF→SIGTERM→SIGKILL.

### 9.4 HTTP: SSE never streams (events arrive all at once or not at all)

**Symptom:** progress notifications don't appear live; the whole stream materializes at the end, or times out.
**Cause:** an intermediary (reverse proxy, CDN, load balancer) **buffers** the response, defeating SSE; or response buffering on the server framework.
**Diagnose:** `curl -N` (disables curl buffering) directly against the server vs. through the proxy — if direct works and proxied doesn't, the proxy buffers.
**Fix:** disable proxy response buffering for the MCP path (e.g. nginx `proxy_buffering off;`, `X-Accel-Buffering: no` header), ensure chunked transfer, and disable compression buffering on the stream.

### 9.5 HTTP: `400 Bad Request — missing Mcp-Session-Id`

**Symptom:** every call after initialize fails with 400.
**Cause:** client didn't capture the `Mcp-Session-Id` from the `initialize` response or didn't echo it.
**Diagnose:** inspect response headers of `initialize`; verify subsequent requests carry the header.
**Fix:** capture and echo the session id; this is automatic in SDK transports — if you hand-roll, add it.

### 9.6 HTTP: `404` mid-session

**Symptom:** calls suddenly fail with 404 after working.
**Cause:** session expired (idle timeout) or server restarted/scaled and lost in-memory session state.
**Diagnose:** check server session TTL and deployment events; confirm whether sessions are in-memory and not pinned/externalized.
**Fix:** client should re-`initialize` on 404; server should externalize session state (Redis) or use sticky load balancing; tune TTL.

### 9.7 HTTP: `401`/auth loops

**Symptom:** repeated 401s; never authenticates.
**Cause:** missing/expired token, wrong audience, missing PKCE, or client not following `WWW-Authenticate`/protected-resource-metadata discovery.
**Diagnose:** inspect the `401`'s `WWW-Authenticate` header and the protected-resource metadata; verify token audience/scopes; check clock skew.
**Fix:** implement the OAuth 2.1 + PKCE flow via the SDK's `authProvider`; ensure tokens are audience-bound to this resource server.

### 9.8 Interop: SSE-vs-Streamable mismatch / batching

**Symptom:** client and server can't complete the handshake or reject each other's framing.
**Cause:** one peer expects legacy two-endpoint HTTP+SSE while the other speaks Streamable HTTP; or one sends a JSON-RPC batch a `2025-06-18` peer rejects.
**Diagnose:** check both peers' protocol revisions; capture the `initialize` exchange; look for an `endpoint` event (legacy) vs single-endpoint behavior.
**Fix:** align revisions; implement the backward-compat bridge (§7.6); stop originating batches.

### 9.9 Encoding/newline corruption

**Symptom:** non-ASCII text mangled, or messages split unexpectedly.
**Cause:** non-UTF-8 default encoding on the JVM; `\r\n` or embedded newline injected into a message.
**Diagnose:** hexdump the stream; check the JVM's `file.encoding`/default charset.
**Fix:** force UTF-8 on stdio reader/writer; ensure compact, newline-free serialization.

### 9.10 Real-world failure-story patterns

- **"It works in MCP Inspector but not in the host":** usually the host launches with a different `cwd`/`env` (e.g. `npx` not on PATH, missing secret env). Reproduce with the host's exact `command`/`args`/`env`/`cwd`.
- **"Long tool calls die behind our gateway":** SSE buffering or gateway idle timeout shorter than the call. Disable buffering; add `ping`s; tune idle timeouts.
- **"Sessions drop on every deploy":** in-memory sessions pinned to a process that rolls during deploy. Externalize session state or accept re-init on deploy (client handles 404).

---

## 10. Interview drill

Each question has a crisp model answer plus deep-probe follow-ups. "Senior-signal" questions (S) require justification/tradeoffs, not recall.

**Q1. What is a transport in MCP, and what is its responsibility?**
*Model answer:* The transport is the bottom layer that moves JSON-RPC 2.0 messages between host and server and manages the connection lifecycle. It handles framing (newline-delimited JSON for stdio; HTTP bodies/SSE events for HTTP), open/close/stream, and reconnection — but knows nothing about tools/resources/prompts, which live in the session layer above.
- *Probe: Why is the layering valuable?* Same server logic runs over different media by swapping transport; clean separation aids testing (in-memory transport) and portability.
- *Probe: What does the session layer rely on the transport to provide?* Ordered, full-duplex, framed message delivery, plus open/close/error signals.

**Q2. Name the standard MCP transports and when to use each.**
*Model answer:* **stdio** for local, per-user, trusted subprocess servers; **Streamable HTTP** for remote/multi-user/scaled servers needing auth and resumability; **HTTP+SSE** is legacy, kept for backward compatibility only.
- *Probe: Why did Streamable HTTP replace HTTP+SSE?* The old design's mandatory long-lived SSE connection was serverless- and load-balancer-hostile, had no resumability, and used two endpoints. Streamable HTTP uses one endpoint, optional per-request SSE, and `Last-Event-ID` resumability.
- *Probe: Can one host use multiple transports at once?* Yes — transport is per client/connection; a host can hold stdio and HTTP connections simultaneously.

**Q3. Walk through the stdio lifecycle from launch to shutdown.**
*Model answer:* Host spawns the server as a child with stdin/stdout wired to pipes; sends `initialize` as the first newline-terminated JSON line; receives the result; sends `notifications/initialized`. Normal ops are newline-framed JSON in both directions (logs to stderr). Shutdown: close child stdin (EOF) → SIGTERM → SIGKILL escalation.
- *Probe: Why must stdout carry only protocol?* The host splits stdout on `\n` and parses each line as JSON; any stray text corrupts framing.
- *Probe: How does the server know to shut down?* stdin EOF; it should also trap SIGTERM for cleanup.

**Q4. Explain the Streamable HTTP request flow, including when the server uses SSE.**
*Model answer:* The client POSTs JSON-RPC to a single `/mcp` endpoint with `Accept: application/json, text/event-stream`. The server replies with a plain JSON response if it has one answer and nothing to push, or upgrades to an SSE stream if it needs to send interim messages (progress, server-initiated requests, multiple responses), closing the stream after delivering the final response(s). A `GET` opens a standing server→client SSE stream; a `DELETE` ends the session. Sessions use the `Mcp-Session-Id` header.
- *Probe: What status for a POST of only notifications?* `202 Accepted` with empty body.
- *Probe: How does the client respond to a server-initiated request over HTTP?* By POSTing the response back to `/mcp`, correlated by `id`.

**Q5. How does resumability work over Streamable HTTP?**
*Model answer:* The server tags SSE events with `id:`. On disconnect, the client reconnects and sends `Last-Event-ID`; the server replays only events after that id on that stream. The session survives; the tool isn't re-executed; the final response, being an SSE event with an id, is replayed if it was missed.
- *Probe: What delivery guarantee does this give?* At-least-once for messages around the boundary; handlers must be idempotent.
- *Probe: What if `Last-Event-ID` predates the server's buffer?* The server can't replay safely; force re-initialize.

**Q6. (S) When would you choose stdio over Streamable HTTP for a new server, and what do you give up?**
*Model answer:* Choose stdio when the server is local, per-user, and operates on local resources — you get the lowest latency, zero network surface, trivial setup, and no auth burden. You give up multi-user sharing, central auth/audit, horizontal scaling, and remote accessibility; the server runs with the user's full privileges (implicit trust). If any of those remote needs exist, prefer Streamable HTTP despite its added complexity (TLS, OAuth, sessions).
- *Probe: Could you start stdio and migrate later?* Yes — since transport is swappable, keep server logic transport-agnostic and add an HTTP transport when remote needs arise.
- *Probe: What security review does each demand?* stdio = treat as local code execution with user privileges (vet the binary, least-privilege args/env). HTTP = OAuth 2.1/PKCE, TLS, Origin validation, token audience binding, rate limiting.

**Q7. (S) Your remote MCP server's long tool calls die behind the company gateway. Diagnose and design a fix.**
*Model answer:* Likely the gateway **buffers** responses (defeating SSE) or has an idle timeout shorter than the call. Diagnose by hitting the server directly with `curl -N` vs through the gateway. Fix by disabling response buffering on the MCP path (`proxy_buffering off`, `X-Accel-Buffering: no`), ensuring chunked transfer and no stream compression, sending periodic `ping`s to defeat idle timeouts, and raising the gateway idle timeout for that route. As defense in depth, make calls resumable so a drop replays rather than re-runs.
- *Probe: Why not just use WebSocket?* Often blocked by the same gateways; SSE over plain HTTP traverses infra more reliably, which is exactly why MCP chose it.
- *Probe: How keep it scalable?* Externalize session state or sticky-route by `Mcp-Session-Id`; keep non-streaming calls as plain JSON so they stay stateless.

**Q8. (S) Justify MCP's choice of SSE-with-optional-upgrade over a single persistent bidirectional socket.**
*Model answer:* SSE rides ordinary HTTP, so it works through proxies/CDNs/serverless gateways that block or complicate WebSocket; it has built-in reconnection (`Last-Event-ID`); and making the SSE upgrade *per-request and optional* keeps the common request/response case stateless and load-balancer-friendly, paying for streaming only when needed. A single persistent socket would force every interaction through a long-lived connection (the legacy HTTP+SSE problem) — bad for serverless and scaling. The cost is recombining two halves (client POST + server SSE) to get full duplex, which is an acceptable trade for infrastructure compatibility.
- *Probe: What's the downside vs WebSocket?* Slightly more moving parts (POST responses to server-initiated requests) and head-of-line per stream; but far better infra traversal.

**Q9. How are server→client messages carried on each transport?**
*Model answer:* On stdio, freely — it's a separate pipe (server writes to stdout). On Streamable HTTP, over an SSE stream: either the SSE upgrade of the triggering POST or a standing `GET` SSE stream; the client returns responses by POSTing them back.
- *Probe: Give an example of a server→client request.* `sampling/createMessage` (ask the host's LLM to generate), `roots/list`, `elicitation/create`.
- *Probe: What if the server has no GET SSE stream?* It returns `405` for GET; it can still push via POST-upgraded streams during calls.

**Q10. What changed across protocol revisions that affects transport?**
*Model answer:* `2024-11-05`: stdio + HTTP+SSE. `2025-03-26`: introduced Streamable HTTP with single endpoint and resumability. `2025-06-18`: removed JSON-RPC batching, strengthened OAuth 2.1 authorization (RFC 9728 protected-resource metadata, `WWW-Authenticate`), and required the `MCP-Protocol-Version` header on HTTP requests.
- *Probe: How do you stay compatible across revisions?* Negotiate version at `initialize`; don't originate batches; implement the SSE↔Streamable bridge for old peers; send `MCP-Protocol-Version`.

**Q11. How do you secure a local stdio server and a remote HTTP server differently?**
*Model answer:* stdio relies on implicit local trust — the server runs with the user's privileges, so vet it like installed software, use least-privilege args/env, confine its scope, and keep stdout pure (and don't log secrets to stderr). HTTP requires explicit security: TLS, OAuth 2.1 + PKCE, `Origin` validation (DNS-rebinding defense), binding local HTTP to `127.0.0.1`, token audience binding, and rate limiting.
- *Probe: What is DNS rebinding and how do you stop it?* A hostname re-resolving to localhost so a malicious page reaches local services; stop it with Origin validation and binding to `127.0.0.1`.

**Q12. What are the top stdio framing pitfalls?**
*Model answer:* Writing non-protocol text to stdout; embedding newlines inside a message; non-UTF-8 encoding; and not flushing the writer (deadlock). Logs must go to stderr; messages must be compact, newline-free UTF-8; flush after each write; read concurrently with writing.
- *Probe: How would you guard against stdout pollution in CI?* An E2E test asserting every stdout line parses as JSON.

---

## 11. Glossary

- **MCP (Model Context Protocol):** open standard for connecting LLM hosts to context/capability servers, over JSON-RPC.
- **Host:** the LLM application that owns the model and connects to servers.
- **Client:** the in-host connector managing exactly one server connection (and its transport).
- **Server:** the program exposing tools/resources/prompts.
- **Transport:** the layer that moves JSON-RPC messages and manages the connection (stdio, Streamable HTTP, HTTP+SSE, custom).
- **JSON-RPC 2.0:** transport-agnostic RPC convention using JSON request/response/notification objects.
- **Request / Response / Notification:** a message expecting a reply (`id`) / the reply (matched by `id`) / a fire-and-forget message (no `id`).
- **Full-duplex / half-duplex:** both ends can send anytime / one direction at a time.
- **Batching:** JSON-RPC sending an array of messages; removed in MCP `2025-06-18`.
- **stdio:** transport using a child process's standard input/output pipes; newline-delimited JSON.
- **File descriptor (fd):** OS integer handle for an I/O channel (0=stdin, 1=stdout, 2=stderr).
- **Pipe:** in-kernel unidirectional byte buffer connecting two processes.
- **Subprocess / child process:** a program launched and controlled by another.
- **SIGTERM / SIGKILL:** Unix signals to ask a process to terminate (catchable) / force-kill it (uncatchable).
- **EOF:** end-of-file; on stdin signals "no more input," a shutdown trigger.
- **NDJSON / JSON Lines:** one JSON value per line, newline-delimited.
- **HTTP:** client-initiated request/response protocol; the base for the networked transports.
- **SSE (Server-Sent Events):** server→client event stream over a long-lived HTTP response (`text/event-stream`), with `id:` for resumption.
- **Chunked transfer encoding:** HTTP body sent as length-prefixed chunks; enables streaming.
- **WebSocket:** full-duplex socket over an upgraded HTTP connection; not a standard MCP transport.
- **Streamable HTTP:** current MCP networked transport — single endpoint, POST + optional per-request SSE upgrade, sessions, resumability.
- **HTTP+SSE (legacy):** original two-endpoint networked transport (`GET /sse` + `POST /messages`); deprecated, compat-only.
- **Mcp-Session-Id:** HTTP header identifying a session; issued at `initialize`, echoed on later requests.
- **Last-Event-ID:** HTTP header to resume an SSE stream after a given event id.
- **MCP-Protocol-Version:** HTTP header carrying the negotiated protocol revision (required in current rev).
- **Resumability:** replaying SSE events after `Last-Event-ID` so a dropped connection doesn't lose progress or re-run a call.
- **Capability negotiation:** the `initialize` exchange where each side declares supported features.
- **initialize / notifications/initialized:** the first request/result and the follow-up notification completing the handshake.
- **Progress notification (`notifications/progress`):** server-emitted progress keyed by a `progressToken`.
- **Cancellation (`notifications/cancelled`):** client signal to abort an in-flight request by id.
- **Ping:** liveness-check request usable in either direction.
- **Sampling (`sampling/createMessage`):** server→client request asking the host's LLM to generate text.
- **Roots (`roots/list`):** server→client request for the directories/URIs it may access.
- **Elicitation (`elicitation/create`):** server→client request to ask the user for input.
- **Tools / Resources / Prompts:** server primitives — callable functions / readable URI-addressed data / reusable prompt templates.
- **OAuth 2.1:** modern OAuth profile (mandatory PKCE, no implicit flow) used for MCP remote auth.
- **PKCE:** Proof Key for Code Exchange; secures the OAuth code flow against interception.
- **Resource Server / Authorization Server:** the API holding protected data and validating tokens / the service issuing tokens.
- **RFC 9728 (protected-resource metadata):** standard the `401`/`WWW-Authenticate` points at to discover the auth server.
- **DNS rebinding:** attack where a hostname re-resolves to localhost to reach local services; mitigated by Origin validation and `127.0.0.1` binding.
- **Backpressure:** when a slow reader causes buffers to fill and the writer to block.
- **Sticky session:** load-balancer routing that pins a session to one backend.
- **Stateless server:** one that holds no per-session state in-process (or externalizes it), enabling easy horizontal scaling.
- **MCP Inspector:** official tool to launch and probe servers and view raw JSON-RPC frames.
- **npx / uvx:** Node / Python (uv) runners commonly used as the `command` to launch published servers.
- **ProcessBuilder:** JVM API to spawn and wire child processes (used by stdio transports).
- **LSP (Language Server Protocol):** the editor↔language-backend standard MCP is modeled after.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Layering:** App (tools/resources/prompts) → Session (JSON-RPC, initialize, capabilities) → **Transport** (moves framed JSON-RPC, owns connection lifecycle).

**Transports:**
- **stdio** — local subprocess; **newline-delimited JSON** on stdin/stdout; logs to **stderr**; full-duplex via two pipes; implicit trust; shutdown = close stdin (EOF) → SIGTERM → SIGKILL. Fastest, simplest, local-only.
- **Streamable HTTP** — single `/mcp` endpoint; **POST** JSON-RPC (`Accept: application/json, text/event-stream`); server returns plain JSON **or upgrades to SSE**; **GET** = standing server→client SSE; **DELETE** = end session; `202` for notification-only POST; sessions via `Mcp-Session-Id`; resumability via `Last-Event-ID`. Current remote transport.
- **HTTP+SSE (legacy)** — two endpoints (`GET /sse` + `POST /messages`), replies over SSE; no resumability; pins a long-lived connection; **compat-only**.

**Key headers:** `Accept`, `Content-Type`, `Mcp-Session-Id`, `MCP-Protocol-Version`, `Last-Event-ID`, `Authorization: Bearer`, `WWW-Authenticate`, `Origin`.
**Key statuses:** 200 (resp/SSE), 202 (notif-only), 400 (bad/missing session), 401 (auth), 404 (expired session → re-init), 405 (no GET SSE), 406 (bad Accept).
**Revisions:** 2024-11-05 (stdio + HTTP+SSE) → 2025-03-26 (Streamable HTTP + resumability) → 2025-06-18 (no batching, OAuth 2.1/RFC 9728, MCP-Protocol-Version header).

**Security:** stdio = local trust, user privileges, keep stdout pure, least-privilege args/env. HTTP = TLS + OAuth 2.1/PKCE + Origin validation + `127.0.0.1` binding + token audience + rate limits.

**Top bugs:** stdout pollution (stdio); no flush → deadlock; SSE buffered by proxy (`proxy_buffering off`, `-N` in curl); missing `Mcp-Session-Id` → 400; expired session → 404 → re-init; SSE↔Streamable mismatch; UTF-8/newline corruption.

**Decision:** local & per-user → stdio; remote/multi-user/scale/auth → Streamable HTTP; old peer → HTTP+SSE bridge; tests/embedding → in-memory.

### 12.2 Self-test (no answers — recall practice)

1. Trace, header by header and status by status, a full Streamable HTTP `tools/call` that streams two progress notifications, survives a mid-stream disconnect, and returns a result. Where do event ids and `Last-Event-ID` come in, and why does the tool not re-execute?
2. Explain exactly why a single stray `System.out.println` breaks a stdio server, and describe two tests that would catch it before production.
3. Why was the legacy HTTP+SSE transport hostile to serverless and load balancers, and which specific properties of Streamable HTTP fix each problem?
4. You must support both an old (2024-11-05) and a new (2025-06-18) server from one client. Describe the bridging logic, including what you must stop doing (batching) and which header you must now send.
5. Compare the security obligations of a stdio server vs. a remote Streamable HTTP server. Define DNS rebinding and give two concrete mitigations.
6. Design an event-id and replay-buffer strategy for a resumable server: how do you bound memory, guarantee correct replay (including the final response), and what do you do when `Last-Event-ID` is older than your buffer?
7. Given a remote server whose long calls die behind a corporate gateway, list the exact diagnostic steps (commands) and the server/proxy settings you'd change.

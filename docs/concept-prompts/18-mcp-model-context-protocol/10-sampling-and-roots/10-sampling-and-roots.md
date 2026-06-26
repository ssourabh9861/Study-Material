# MCP — Sampling & Roots (and Elicitation)

> A definitive engineering-handbook chapter on the three "advanced," client-side-empowered capabilities of the Model Context Protocol: **Sampling**, **Roots**, and **Elicitation**. Written for a senior JVM/backend engineer who wants to master these from first principles to deep internals.

---

## 0. Reader orientation & a note on the spec's evolution

The Model Context Protocol (MCP) is an open protocol, originally published by Anthropic in **November 2024**, that standardizes how applications provide *context* to large language models (LLMs). Think of it as "USB-C for LLM context": a single wire format so that any **host** application (Claude Desktop, an IDE plugin, a custom agent) can talk to any **server** that exposes tools, data, and prompts.

This chapter focuses on three capabilities that invert or constrain the usual flow of control:

- **Sampling** — a *server* asks the *client* to run an LLM completion on its behalf.
- **Roots** — a *client* tells a *server* which filesystem/URI boundaries it is allowed to operate within.
- **Elicitation** — a *server* asks the *client* (and through it, the human) for additional structured input mid-operation.

A critical framing before we start: **Sampling, Roots, and Elicitation are among the most actively evolving parts of the MCP specification.** The spec is versioned by date (e.g. `2024-11-05`, `2025-03-26`, `2025-06-18`). Sampling and Roots appeared early; **Elicitation was introduced in the `2025-06-18` revision** and is still maturing. Where a detail is version-specific I will flag it explicitly. When you implement against MCP, **pin to a dated spec revision** and read that revision's schema (`schema.ts` / JSON Schema) as the source of truth, because field names and semantics shift between revisions. Treat everything in this chapter as "true as of the 2025-06-18 revision unless noted," and verify against the live spec for production work.

> **What's an LLM / a "completion"?** A large language model is a neural network that, given a sequence of text (the *prompt*), predicts a continuation. A *completion* (or *sampling*, in the model-theory sense) is the act of drawing that continuation. "Sampling" in MCP borrows the statistical term — drawing tokens from the model's probability distribution — and reuses it to name the capability where a server requests such a draw from the client's model.

---

## 1. Overview & where it fits

### 1.1 The MCP cast of characters

MCP has a precise vocabulary. Define each term once, clearly:

- **Host** — the user-facing application that *contains* the LLM and orchestrates everything (e.g., Claude Desktop, an AI IDE, a chat app). The host owns the relationship with the user and with the model.
- **Client** — a connector object *inside the host*. There is **one client per server connection**. The client speaks MCP on the host's behalf. (Host : client is one-to-many; client : server is one-to-one.)
- **Server** — an external program that exposes capabilities (tools, resources, prompts) to the client. Servers are typically small, focused, and untrusted-ish: a "GitHub server," a "Postgres server," a "filesystem server."
- **Transport** — the byte pipe between client and server. The two standard transports are **stdio** (the server is a subprocess; messages flow over stdin/stdout) and **Streamable HTTP** (HTTP POST + Server-Sent Events; this superseded the older "HTTP+SSE" transport in the `2025-03-26` revision).
- **JSON-RPC 2.0** — the message format. Every MCP message is a JSON-RPC *request* (has an `id`, expects a response), *response* (carries `result` or `error`), or *notification* (no `id`, fire-and-forget). MCP is "JSON-RPC over a transport."

> **What is JSON-RPC 2.0?** A lightweight remote-procedure-call convention encoded as JSON. A request looks like `{"jsonrpc":"2.0","id":1,"method":"foo","params":{...}}`; a success response `{"jsonrpc":"2.0","id":1,"result":{...}}`; an error response replaces `result` with `error:{code,message,data}`. Notifications omit `id`. MCP layers semantics (capabilities, lifecycle) on top of this.

### 1.2 The normal direction of control — and the inversion

In ordinary MCP usage the control flow is **client → server**: the host's model decides it needs to call a tool, so the client sends `tools/call` to the server, the server runs and returns a result. The server is the *callee*.

The three capabilities in this chapter rearrange that:

| Capability | Initiator | Target | One-line purpose |
|---|---|---|---|
| **Sampling** | Server | Client (→ host's LLM) | "Please run an LLM completion for me." |
| **Elicitation** | Server | Client (→ human) | "Please ask the user for this specific structured input." |
| **Roots** | Client | Server | "Here are the directories/URIs you're allowed to touch." |

Sampling and Elicitation are **server-initiated requests** — the inversion of control. Roots is **client-initiated context** plus a notification when it changes. All three exist to make servers more capable *without* making them more dangerous, by keeping the host (and human) in the loop.

### 1.3 The problems each solves

**Sampling** solves: *a server needs LLM intelligence but should not embed its own model or API key.* Example: a "code-review" server wants to summarize a diff. Without sampling it would need its own Anthropic/OpenAI credentials, its own billing, its own model choice — duplicating what the host already has. With sampling, the server says "complete this prompt" and the host's model (and the host's billing, key, rate limits, and safety controls) does the work. This enables **recursive / agentic behavior**: a server can be agentic on its own without bringing an LLM.

**Roots** solves: *a server with filesystem or URI access needs to know the legitimate scope of work.* Without roots, a filesystem server either guesses (dangerous) or must be configured out-of-band. Roots let the client *declaratively scope* the server: "you operate within `/Users/me/project-a` and `/Users/me/project-b`, nothing else." It's a **boundary advertisement**, not a hard sandbox (the security nuance matters — see §6).

**Elicitation** solves: *a server discovers mid-operation that it needs more information from the human* — a missing parameter, a confirmation, a choice among options — and wants a *structured*, schema-validated answer rather than free text buried in chat. Example: a "book-a-flight" server that needs the user to pick a seat class. Before elicitation, servers had to either fail, guess, or stuff the question into a tool result and hope the model relayed it. Elicitation makes the ask first-class and schema-typed.

### 1.4 One-paragraph mental model

> MCP normally flows **host → server**. Sampling, Elicitation, and Roots are the **reverse and the guardrails**: Sampling lets a server *borrow the host's brain* (the LLM); Elicitation lets a server *borrow the host's mouth and ears* (the user, via a typed form); Roots let the client *fence the yard* the server may dig in. All three are **capability-negotiated** (only available if both sides advertised support during the handshake) and **human-in-the-loop by design** — the spec repeatedly says the host SHOULD let a human approve sampling and elicitation requests, and MUST keep control of model selection and what data leaves the machine.

---

## 2. Foundations from first principles

### 2.1 The lifecycle: how a connection is born

Everything depends on the **initialization handshake**, because that's where capabilities are negotiated. The lifecycle has three phases:

1. **Initialization** — the client sends `initialize`; the server responds; the client sends an `initialized` notification.
2. **Operation** — normal request/response/notification traffic (tools, resources, prompts, sampling, roots, elicitation).
3. **Shutdown** — the transport is closed (for stdio, the client closes the subprocess's stdin and waits, then SIGTERM/SIGKILL; for HTTP, the connection is dropped).

The `initialize` request and response each carry a **`capabilities`** object. **A capability not declared cannot be used.** This is the gate for our three features.

#### 2.1.1 Capability declarations

- The **client** declares, in its `initialize` request, what *it* can do for servers:
  - `sampling: {}` — "I can fulfill `sampling/createMessage` requests."
  - `roots: { listChanged: true }` — "I expose roots; and I will send `notifications/roots/list_changed` when they change."
  - `elicitation: {}` — "I can fulfill `elicitation/create` requests." (2025-06-18+)
- The **server** declares, in its `initialize` response, what *it* offers: `tools`, `resources`, `prompts`, `logging`, `completions`, etc. Notably, **the server does NOT declare `sampling` or `elicitation` as a capability** — those are *client* capabilities the server consumes. A well-behaved server checks the client's declared capabilities before attempting `sampling/createMessage` or `elicitation/create`.

> **Why declare at all? (capability negotiation)** Because MCP connects heterogeneous clients and servers of different ages and feature sets. Negotiation lets each side discover what the other supports *before* using it, so a server doesn't send a sampling request to a client (say, a headless CI bot) that has no model and would just error. It's the same idea as TLS cipher negotiation or HTTP `Accept` headers: agree on the common subset up front.

Here is a representative `initialize` exchange (abbreviated):

```jsonc
// client → server
{
  "jsonrpc": "2.0",
  "id": 0,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "sampling": {},                  // I can run completions for you
      "elicitation": {},               // I can ask the user for structured input
      "roots": { "listChanged": true } // I expose roots and notify on change
    },
    "clientInfo": { "name": "MyHost", "version": "1.4.0" }
  }
}
// server → client
{
  "jsonrpc": "2.0",
  "id": 0,
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "tools": { "listChanged": true },
      "resources": { "subscribe": true, "listChanged": true }
    },
    "serverInfo": { "name": "demo-server", "version": "0.2.0" }
  }
}
// client → server (notification, no id)
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```

> **What is `protocolVersion`?** A date string identifying which spec revision both sides agree to speak. The client proposes one; if the server can't speak it, the server responds with a version it *can* speak, and the client decides whether to proceed or disconnect. Pin and check this — it governs which fields exist.

### 2.2 Sampling from zero

**Definition.** Sampling is the flow where the **server sends a `sampling/createMessage` request to the client**, and the client (the host) runs an LLM completion and returns the generated message.

The cleanest way to internalize it: a server can "call the model" the same way your application code calls an LLM API — except the call goes *through the host*, so the host controls the key, the model, the cost, and the safety review.

**Why the inversion is powerful:**
- Servers gain LLM intelligence with **zero credentials and zero model-vendor lock-in**.
- It enables **nested agents**: a server can plan, reflect, summarize, classify — all by sampling — without being an "AI app" itself.
- The host can **batch, cache, route, and meter** all model usage in one place.

**Why it's sensitive:**
- The server is, in effect, **injecting prompts into your model**. A malicious or compromised server could try to exfiltrate data (by asking the model to reveal context) or run expensive completions on your dime.
- The completion may contain the server's content *and* whatever context the host adds — so the host must be careful about what it includes.
- Therefore the spec **strongly recommends human-in-the-loop**: the host SHOULD show the proposed prompt, let the user edit/approve it, and let the user review the completion before it goes back to the server.

> **What is "human-in-the-loop" (HITL)?** A design where an automated step pauses for a human to inspect/approve/modify before proceeding. In MCP sampling, the canonical HITL is a UI dialog showing "Server X wants to run this prompt; the model will reply with this; approve / edit / deny?". The spec uses RFC-2119 **SHOULD** language — it's a strong recommendation, not a wire-level enforcement.

### 2.3 Roots from zero

**Definition.** A **root** is a URI (commonly a `file://` URI, but the spec allows other schemes) that the client advertises to the server as an operational boundary. The set of roots answers the server's question: *"Which directories/resources am I supposed to work within?"*

Mechanics:
- If the client declares the `roots` capability, the server can call **`roots/list`** to fetch the current roots.
- Each root is `{ "uri": "...", "name": "optional human label" }`.
- If the client declared `roots: { listChanged: true }`, it will send **`notifications/roots/list_changed`** whenever the set changes (user opens a new folder, closes one, etc.); the server is expected to re-call `roots/list`.

**Key conceptual point — roots are guidance, not a sandbox.** The protocol defines roots as *informative boundaries*. A server *should* respect them; the host *should* enforce them (e.g., by only granting the server filesystem access within those paths). But the wire protocol itself does not prevent a misbehaving server from accessing paths outside the roots if the OS/process permissions allow it. **Enforcement is the host's job** (process sandboxing, chroot, containers, OS permissions). We'll return to this in §6.

> **What is a URI? A `file://` URI?** A Uniform Resource Identifier is a string that names a resource. `file:///Users/me/project` is a URI with the `file` scheme pointing at a local path (note the three slashes: `file://` + the absolute path `/Users/...`). Roots are usually `file://` but the spec is scheme-agnostic, so a server could be scoped to, say, an `https://api.internal/` prefix.

### 2.4 Elicitation from zero

**Definition (2025-06-18+).** Elicitation is the flow where the **server sends an `elicitation/create` request to the client**, asking the host to collect **structured input from the user** according to a **JSON Schema**. The client renders a form (or otherwise prompts the user), validates the answer against the schema, and returns one of three outcomes: **accept** (with the data), **decline** (user said no), or **cancel** (user dismissed).

Why it exists: tools often discover at runtime that they're missing a parameter or need a decision. Before elicitation, the only channels were (a) fail and hope the model retries, or (b) embed the question in a tool result as text. Elicitation gives a typed, validated, user-facing request that doesn't pollute the model's context with form-filling.

**Important constraint in the spec:** elicitation schemas are restricted to **flat objects of primitive properties** (string, number, integer, boolean, and enums) — *no nested objects, no arrays of objects*. This keeps client rendering simple and predictable (a flat form). Servers MUST NOT use elicitation to request sensitive secrets like passwords or full credit-card numbers; the spec calls this out explicitly as a security guideline.

> **What is JSON Schema?** A vocabulary for describing the shape of JSON data — types, required fields, ranges, enums, formats (email, date, uri). Elicitation uses a **restricted subset** so clients can auto-generate a usable form. Example: `{"type":"object","properties":{"seatClass":{"type":"string","enum":["economy","business"]}},"required":["seatClass"]}`.

---

## 3. How it works internally — the heart of the chapter

We'll trace each capability end-to-end: control flow, data flow, state, and the exact messages.

### 3.1 Sampling: full internal workflow

#### 3.1.1 The state machine (per sampling request)

```
[Server decides it needs a completion]
        │  builds CreateMessageRequest
        ▼
(SENT) server → client: sampling/createMessage  ──────────┐
        │                                                  │ (transport in flight)
        ▼                                                  │
[Client receives request]                                  │
        │  validate capability was negotiated              │
        │  apply host policy (limits, model mapping)        │
        ▼                                                  │
[HITL gate]  ── user denies ──► error response (e.g. -1 user rejected) ─┐
        │  user approves/edits                                          │
        ▼                                                               │
[Host runs LLM completion]                                             │
        │  may stream tokens internally                                │
        ▼                                                               │
[HITL gate #2: review completion (optional)] ── edits/denies ──────────┤
        │  approve                                                      │
        ▼                                                               │
(RESULT) client → server: result { role, content, model, stopReason } ─┘
        │
        ▼
[Server consumes the completion, continues its logic]
```

Two HITL gates are *recommended*: one before sending the prompt to the model, one before returning the completion to the server. Real hosts vary — some collapse these, some allow an "always allow for this server" setting.

#### 3.1.2 The request shape: `sampling/createMessage`

```jsonc
// server → client
{
  "jsonrpc": "2.0",
  "id": 42,
  "method": "sampling/createMessage",
  "params": {
    "messages": [
      {
        "role": "user",
        "content": { "type": "text", "text": "Summarize this diff in 2 sentences:\n<diff...>" }
      }
    ],
    "modelPreferences": {
      "hints": [ { "name": "claude-3-5-sonnet" }, { "name": "claude" } ],
      "costPriority": 0.3,
      "speedPriority": 0.2,
      "intelligencePriority": 0.9
    },
    "systemPrompt": "You are a concise senior code reviewer.",
    "includeContext": "thisServer",      // none | thisServer | allServers
    "temperature": 0.2,
    "maxTokens": 300,
    "stopSequences": ["\n\n"],
    "metadata": {}
  }
}
```

Field-by-field, with the subtle parts explained:

- **`messages`** (required): an array of `{ role, content }`. `role` is `"user"` or `"assistant"`. `content` is a typed object: `{type:"text", text}` or `{type:"image", data(base64), mimeType}` or `{type:"audio", data, mimeType}` (audio added later; check your revision). This is the conversation the server wants completed.
- **`modelPreferences`** (optional): the server's *non-binding* wishes. It does **not** name a concrete model directly except via **`hints`** (an ordered list of partial name substrings the host *may* match against its available models). Plus three priority scalars in `[0,1]`:
  - **`costPriority`** — how much the server values cheapness.
  - **`speedPriority`** — how much it values low latency.
  - **`intelligencePriority`** — how much it values capability.
  The host maps these to its own model catalog. **The host has final say on which model runs.** Hints are advisory; a host without that model substitutes its own.
- **`systemPrompt`** (optional): a suggested system prompt. The host MAY honor, modify, or ignore it.
- **`includeContext`**: governs whether the host injects MCP context into the prompt. `"none"` = just the server's messages. `"thisServer"` = include context from the requesting server. `"allServers"` = include context from all connected servers. **This is a security-relevant knob** (see §6): the host decides what "context" means and SHOULD let the user control it; a server requesting `allServers` is asking to see more.
- **`temperature`** (optional): sampling randomness (0 = deterministic-ish, higher = more varied). Same meaning as in any LLM API.
- **`maxTokens`** (required by most clients): cap on generated tokens.
- **`stopSequences`** (optional): strings that, if generated, halt the completion.
- **`metadata`** (optional): opaque provider-specific passthrough.

> **What is `temperature`? `maxTokens`? `stopReason`?** Temperature scales the model's output distribution — low values make it pick the most likely tokens (focused, repeatable), high values flatten the distribution (creative, risky). `maxTokens` bounds output length (and cost). `stopReason` (in the response) explains why generation ended: `"endTurn"` (model finished), `"maxTokens"` (hit the cap), `"stopSequence"` (hit a stop string).

#### 3.1.3 The response shape

```jsonc
// client → server
{
  "jsonrpc": "2.0",
  "id": 42,
  "result": {
    "role": "assistant",
    "content": { "type": "text", "text": "The diff adds null-checking to the parser and..." },
    "model": "claude-3-5-sonnet-20241022",   // the model the HOST actually used
    "stopReason": "endTurn"
  }
}
```

Note `model` reports what *actually* ran, which may differ from the server's hints. The server should not assume it got the model it asked for.

#### 3.1.4 Errors & the "user denied" case

If the user denies the sampling request, or the host policy forbids it, the client returns a JSON-RPC **error**, not a result. There is no single mandated error code for "user rejected" across all implementations; many use a generic code (and the spec defines standard JSON-RPC codes like `-32601 method not found`, `-32602 invalid params`, plus an application range). **Check your SDK** for the exact constant. The server must handle the error path gracefully — sampling can always be refused.

#### 3.1.5 Recursion / agentic depth

Because a sampling completion can itself drive the server to call more tools or sample again, you can build **nested loops**: server samples → gets a plan → calls its own tools → samples again to reflect. Hosts SHOULD bound this (max sampling calls per server per turn, total token budget) to prevent runaway cost/recursion. There is no protocol-level recursion limit; it's a host policy.

### 3.2 Roots: full internal workflow

#### 3.2.1 Sequence

```
[init] client declares capabilities.roots = { listChanged: true }
        │
        ▼
[server, when it needs scope] server → client: roots/list
        │
        ▼
[client] client → server: result { roots: [ {uri,name}, ... ] }
        │
        ▼
[server] caches roots, restricts its operations to within them
        │
   ... later, user opens a new folder ...
        │
[client] client → server (notification): notifications/roots/list_changed
        │
        ▼
[server] server → client: roots/list  (re-fetch)
```

#### 3.2.2 Messages

```jsonc
// server → client
{ "jsonrpc": "2.0", "id": 7, "method": "roots/list" }

// client → server
{
  "jsonrpc": "2.0",
  "id": 7,
  "result": {
    "roots": [
      { "uri": "file:///Users/me/project-a", "name": "Project A" },
      { "uri": "file:///Users/me/project-b", "name": "Project B" }
    ]
  }
}

// client → server (notification, when set changes)
{ "jsonrpc": "2.0", "method": "notifications/roots/list_changed" }
```

#### 3.2.3 Semantics & validation

- Roots SHOULD be **absolute** URIs. For `file://`, that means absolute paths.
- The server SHOULD treat them as the *outer* boundary and reject (or refuse to act on) any request that resolves outside all roots. **Path-traversal defense is essential**: a request for `file:///Users/me/project-a/../../../etc/passwd` normalizes outside the root and MUST be rejected. (This is a classic vuln; see §6.)
- An empty roots list (or `roots` capability absent) means the client gives no scope; the server should fall back to its own safe default (often: do nothing filesystem-y, or use a configured directory).
- Roots are **client-pushed context the server pulls on demand** plus a change notification — there is no server "subscribe" RPC for roots specifically; it relies on `list_changed`.

### 3.3 Elicitation: full internal workflow (2025-06-18+)

#### 3.3.1 State machine

```
[Server, mid-tool, needs user input]
        │ builds elicitation/create with message + requestedSchema
        ▼
server → client: elicitation/create
        │
        ▼
[Client] validate capability negotiated; render form from schema
        │
        ▼
[User interacts] ─► ACCEPT (fills form)  ─► validate against schema ─► result { action:"accept", content:{...} }
                ─► DECLINE (explicit no)  ──────────────────────────► result { action:"decline" }
                ─► CANCEL (dismiss/close) ──────────────────────────► result { action:"cancel" }
        │
        ▼
[Server] branches on action; on accept, consumes validated content
```

The **three-action model** is a deliberate UX/security design: the spec distinguishes a *deliberate refusal* (`decline`) from *walking away* (`cancel`). Servers should treat both as "no data," but may message differently.

#### 3.3.2 Messages

```jsonc
// server → client
{
  "jsonrpc": "2.0",
  "id": 99,
  "method": "elicitation/create",
  "params": {
    "message": "To book your flight, choose a cabin and confirm a checked bag.",
    "requestedSchema": {
      "type": "object",
      "properties": {
        "cabin": { "type": "string", "enum": ["economy", "premium", "business"],
                   "description": "Cabin class" },
        "checkedBag": { "type": "boolean", "default": false,
                        "description": "Add a checked bag?" }
      },
      "required": ["cabin"]
    }
  }
}

// client → server — ACCEPT
{
  "jsonrpc": "2.0",
  "id": 99,
  "result": {
    "action": "accept",
    "content": { "cabin": "business", "checkedBag": true }
  }
}

// client → server — DECLINE
{ "jsonrpc": "2.0", "id": 99, "result": { "action": "decline" } }

// client → server — CANCEL
{ "jsonrpc": "2.0", "id": 99, "result": { "action": "cancel" } }
```

#### 3.3.3 Schema restrictions (enforced expectations)

- **Top level must be `type: "object"`.**
- **Properties must be primitives:** `string`, `number`, `integer`, `boolean`, plus `enum`. String formats like `email`, `uri`, `date`, `date-time` are allowed as hints to the client renderer.
- **No nested objects, no arrays of objects.** (Some clients tolerate arrays of primitives, but the spec's intent is flat forms; don't rely on it.)
- The client SHOULD validate `content` against `requestedSchema` before returning `accept`. The server SHOULD *also* validate (defense in depth) — never trust the client to enforce your schema.
- **Never request secrets** via elicitation (passwords, API keys, full card numbers). Use a proper credential flow instead.

### 3.4 How all three are negotiated together (the unifying view)

The negotiation is purely at `initialize`. After that, no further negotiation occurs; each side simply uses what was advertised. Concretely:

| Feature | Advertised by | Field at `initialize` | Pull RPC | Push notification |
|---|---|---|---|---|
| Sampling | Client | `capabilities.sampling: {}` | `sampling/createMessage` (server→client) | — |
| Roots | Client | `capabilities.roots: { listChanged?: bool }` | `roots/list` (server→client) | `notifications/roots/list_changed` (client→server) |
| Elicitation | Client | `capabilities.elicitation: {}` | `elicitation/create` (server→client) | — |

A defensive server's pseudo-logic at startup:

```text
onInitialized(clientCapabilities):
    canSample   = clientCapabilities.has("sampling")
    canElicit   = clientCapabilities.has("elicitation")
    hasRoots    = clientCapabilities.has("roots")
    rootsLive   = hasRoots && clientCapabilities.roots.listChanged == true
    if hasRoots: roots = call("roots/list")
    // gate every later feature use on these booleans
```

---

## 4. The complete toolkit

Below, the wire-level API, then the two reference SDKs most relevant to a JVM engineer: the **official MCP Java SDK** (maintained in collaboration with the Spring team; also surfaced via **Spring AI**), and the **TypeScript SDK** (the reference implementation — worth knowing because the spec examples are TS-first).

### 4.1 Wire-level method reference

| Method | Direction | Type | Purpose | Key params | Result |
|---|---|---|---|---|---|
| `initialize` | client→server | request | Handshake, negotiate version & capabilities | `protocolVersion`, `capabilities`, `clientInfo` | `protocolVersion`, `capabilities`, `serverInfo` |
| `notifications/initialized` | client→server | notification | Signal client is ready | — | — |
| `sampling/createMessage` | server→client | request | Request an LLM completion | `messages`, `modelPreferences`, `systemPrompt`, `includeContext`, `temperature`, `maxTokens`, `stopSequences`, `metadata` | `role`, `content`, `model`, `stopReason` |
| `roots/list` | server→client | request | Fetch current roots | — | `roots[]` of `{uri,name?}` |
| `notifications/roots/list_changed` | client→server | notification | Roots set changed | — | — |
| `elicitation/create` | server→client | request | Ask user for structured input | `message`, `requestedSchema` | `action` + (on accept) `content` |
| `ping` | either | request | Liveness check | — | `{}` |
| `$/cancelNotification` style cancellation | either | notification | Cancel an in-flight request | `requestId`, `reason` | — |

> **What is `ping` / cancellation?** `ping` is a no-op request to verify the peer is alive (like a TCP keepalive at the app layer). MCP also supports **request cancellation** via a `notifications/cancelled` message referencing the original request `id`, so a host can abort a long sampling call the user no longer wants. Exact method name is `notifications/cancelled` in current revisions.

### 4.2 `modelPreferences` sub-fields

| Field | Type | Range/Values | Default | Meaning |
|---|---|---|---|---|
| `hints` | array of `{name}` | substrings | none | Ordered, advisory model-name hints; host matches against its catalog |
| `costPriority` | number | 0.0–1.0 | unset | Weight on cheapness |
| `speedPriority` | number | 0.0–1.0 | unset | Weight on latency |
| `intelligencePriority` | number | 0.0–1.0 | unset | Weight on capability |

All are **advisory**. The host's model-selection policy is authoritative. There is no protocol default if you omit them — the host picks however it likes.

### 4.3 `includeContext` enum

| Value | Effect | Risk |
|---|---|---|
| `none` | Only the server's `messages` are sent to the model | Lowest data exposure |
| `thisServer` | Host may add context originating from the requesting server | Medium |
| `allServers` | Host may add context from all connected servers | Highest — cross-server data could leak; host SHOULD gate/deny |

### 4.4 Java SDK toolkit (official MCP Java SDK / Spring AI MCP)

The official Java SDK is published under `io.modelcontextprotocol.sdk` (artifacts like `mcp` and Spring-integration modules). Spring AI wraps it for Spring Boot autoconfiguration. **Version note:** the Java SDK evolved rapidly through 2025; class names and async/sync split below reflect the general shape — verify against the version you pin (check `mcp` and `mcp-spring-*` artifact versions in Maven Central).

Core building blocks (server side, the side that *uses* sampling/elicitation/roots):

| Type (representative) | Role |
|---|---|
| `McpServer` (factory) | Builds a sync or async server: `McpServer.sync(transport)` / `McpServer.async(transport)` |
| `McpSyncServer` / `McpAsyncServer` | The server instance; exposes the connected client's exchange |
| `McpSyncServerExchange` / `McpAsyncServerExchange` | Per-session handle to call *back* to the client — this is where `createMessage`, `createElicitation`, and `listRoots` live |
| `McpSchema.CreateMessageRequest` / `...Result` | Sampling request/response POJOs |
| `McpSchema.ElicitRequest` / `ElicitResult` | Elicitation request/response POJOs |
| `McpSchema.ListRootsResult` / `Root` | Roots result POJOs |
| `McpSchema.ModelPreferences`, `ModelHint`, `SamplingMessage`, `Role`, `TextContent` | Sampling DTOs |

Client side (the side that *fulfills* them): the client builder takes **handlers/callbacks** you register:

| Builder hook (representative) | Fulfills |
|---|---|
| `.sampling(handler)` / `samplingHandler` | Implements `sampling/createMessage` — you call your LLM here |
| `.elicitation(handler)` | Implements `elicitation/create` — you render the form/prompt the user |
| `.roots(...)` / `.rootsChangeHandler(...)` and `addRoot/removeRoot` | Declares roots and triggers `list_changed` |
| `.capabilities(...)` | Declares which of the above are enabled |

> **Sync vs async in the Java SDK.** The SDK offers both a blocking (`McpSyncServer`) and a reactive (`McpAsyncServer`, built on Project Reactor `Mono`/`Flux`) flavor. **What is Project Reactor?** A reactive-streams library for the JVM where `Mono<T>` is a 0-or-1 async value and `Flux<T>` is an async stream; it lets the SDK do non-blocking I/O without thread-per-request. Choose async for high-concurrency HTTP servers; sync is simpler for stdio tools.

### 4.5 TypeScript SDK toolkit (reference)

| API | Role |
|---|---|
| `server.createMessage(params)` | Server-side: issue a sampling request to the client |
| `server.elicitInput({ message, requestedSchema })` | Server-side: issue an elicitation |
| `server.listRoots()` | Server-side: fetch roots |
| `client.setRequestHandler(CreateMessageRequestSchema, handler)` | Client-side: fulfill sampling |
| `client.setRequestHandler(ElicitRequestSchema, handler)` | Client-side: fulfill elicitation |
| `Client` constructor `capabilities` | Declare `sampling`, `elicitation`, `roots` |

### 4.6 Configuration & operational flags (host-level, not wire-level)

These live in the *host* (e.g., Claude Desktop's `claude_desktop_config.json`, or your custom host's config), because hosts own policy:

| Concern | Typical knob | Default behavior |
|---|---|---|
| Allow sampling per server | per-server toggle / approval prompt | Prompt the user (HITL) |
| Model routing for sampling | map `modelPreferences` → catalog | Host-defined |
| Token/cost budget per server | max tokens, max calls/turn | Host-defined; often unbounded by default — **set it** |
| `includeContext` ceiling | cap at `none`/`thisServer` | Host-defined; cautious hosts cap below `allServers` |
| Roots source | which folders the user has "opened" | Empty until user opens a folder |
| Elicitation rendering | form vs chat prompt | Client-defined |

---

## 5. Code examples by use case

These span **different real scenarios**, not variations of one. JVM-first where the topic is language-relevant; TS shown where the reference is clearer.

### 5.1 Use case A — A code-review server that *samples* the host's model (Java, server side)

Scenario: a server exposes a `review_diff` tool. Internally it asks the host's LLM to summarize risk, via sampling. Note how it gates on the client's declared capability and never embeds an API key.

```java
// Server-side tool handler (sync flavor). Pseudocode-faithful to the Java SDK shape;
// verify exact method names against your pinned io.modelcontextprotocol.sdk version.
import io.modelcontextprotocol.spec.McpSchema.*;
import io.modelcontextprotocol.server.McpSyncServerExchange;

CallToolResult reviewDiff(McpSyncServerExchange exchange, Map<String,Object> args) {
    String diff = (String) args.get("diff");

    // 1) Defensive capability check: did the client advertise sampling?
    if (!exchange.getClientCapabilities().sampling().isPresent()) {
        return CallToolResult.builder()
            .isError(true)
            .addTextContent("This host does not support sampling; cannot auto-review.")
            .build();
    }

    // 2) Build the sampling request. We express PREFERENCES, not a hard model choice.
    CreateMessageRequest req = CreateMessageRequest.builder()
        .messages(List.of(new SamplingMessage(Role.USER,
            new TextContent("Review this diff. List up to 3 risks, terse:\n" + diff))))
        .systemPrompt("You are a precise senior code reviewer. No praise, only risks.")
        .modelPreferences(ModelPreferences.builder()
            .addHint(ModelHint.of("claude-3-5-sonnet")) // advisory only
            .intelligencePriority(0.9)                  // we value capability
            .speedPriority(0.2)
            .costPriority(0.3)
            .build())
        .includeContext(CreateMessageRequest.ContextInclusionStrategy.NONE) // minimize exposure
        .temperature(0.2)   // low randomness for review consistency
        .maxTokens(300)     // bound cost
        .build();

    // 3) Call BACK to the client. The host runs HITL + the actual completion.
    CreateMessageResult res;
    try {
        res = exchange.createMessage(req);  // blocks until host returns or user denies
    } catch (Exception userDeniedOrPolicy) {
        return CallToolResult.builder().isError(true)
            .addTextContent("Sampling was refused: " + userDeniedOrPolicy.getMessage())
            .build();
    }

    // 4) Consume the completion. res.model() tells us what ACTUALLY ran.
    String summary = ((TextContent) res.content()).text();
    return CallToolResult.builder()
        .addTextContent("Automated review (via " + res.model() + "):\n" + summary)
        .build();
}
```

Why the parts matter: the `includeContext = NONE` line is a security default — the server doesn't need cross-server context to review a diff it already holds. The `try/catch` is mandatory because **sampling can always be denied**.

### 5.2 Use case B — A client that *fulfills* sampling by routing to a real model (TypeScript, client side)

Scenario: you're building a host/client. A server asks for a completion; you apply HITL and call Anthropic's API. This shows the *other* side of 5.1.

```ts
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { CreateMessageRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import Anthropic from "@anthropic-ai/sdk";

const client = new Client(
  { name: "MyHost", version: "1.0.0" },
  { capabilities: { sampling: {}, elicitation: {}, roots: { listChanged: true } } }
);

const anthropic = new Anthropic(); // key lives HERE, in the host — never in the server

client.setRequestHandler(CreateMessageRequestSchema, async (request) => {
  const p = request.params;

  // 1) HUMAN-IN-THE-LOOP: show the prompt, get approval. (UI omitted.)
  const approved = await askUserToApproveSampling(p);
  if (!approved) {
    // Refuse by throwing -> becomes a JSON-RPC error to the server.
    throw new Error("User denied sampling request");
  }

  // 2) Map the server's advisory preferences to OUR model catalog.
  const model = pickModel(p.modelPreferences); // e.g. honor "intelligencePriority"

  // 3) Run the actual completion with OUR credentials/limits.
  const completion = await anthropic.messages.create({
    model,
    max_tokens: p.maxTokens ?? 512,
    temperature: p.temperature ?? 0.7,
    system: p.systemPrompt,
    messages: p.messages.map(m => ({
      role: m.role,
      content: m.content.type === "text" ? m.content.text : "[non-text omitted]",
    })),
  });

  const text = completion.content.map(c => (c.type === "text" ? c.text : "")).join("");

  // 4) (Optional) HITL gate #2: let the user review before returning to the server.
  return {
    role: "assistant",
    content: { type: "text", text },
    model,                       // report what actually ran
    stopReason: completion.stop_reason ?? "endTurn",
  };
});
```

Key insight: the **API key never leaves the host.** The server got intelligence; it got no secret. That's the entire value proposition of sampling.

### 5.3 Use case C — A filesystem server respecting *roots* with path-traversal defense (Java)

Scenario: a server reads files but must stay within the client's roots. Shows fetching roots, reacting to changes, and the canonical security check.

```java
import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.nio.file.*;
import java.util.*;

class RootScopedFiles {
    // Cache of normalized, absolute root paths.
    private volatile List<Path> roots = List.of();

    // Call this on startup AND on every roots/list_changed notification.
    void refreshRoots(McpSyncServerExchange exchange) {
        var result = exchange.listRoots(); // server → client: roots/list
        this.roots = result.roots().stream()
            .map(r -> URI.create(r.uri()))
            .filter(u -> "file".equals(u.getScheme()))
            .map(u -> Paths.get(u).toAbsolutePath().normalize()) // normalize == resolve ../
            .toList();
    }

    String readWithinRoots(String requestedPath) throws Exception {
        // 1) Normalize the request to defeat ../ traversal.
        Path target = Paths.get(requestedPath).toAbsolutePath().normalize();

        // 2) Verify the normalized target is UNDER at least one root.
        boolean allowed = roots.stream().anyMatch(target::startsWith);
        if (!allowed) {
            // Refuse. Roots are guidance, but a good server ENFORCES them itself.
            throw new SecurityException("Path " + target + " is outside declared roots");
        }

        // 3) Optional extra hardening: reject symlinks that escape the root.
        Path real = target.toRealPath(); // resolves symlinks
        if (roots.stream().noneMatch(real::startsWith)) {
            throw new SecurityException("Symlink escapes roots: " + real);
        }

        return Files.readString(real);
    }
}
```

The `normalize()` + `startsWith` + `toRealPath()` trio is the standard defense: normalize collapses `..`, `startsWith` checks containment, and `toRealPath` foils symlink escapes. **Never** do a naive string `contains`/`startsWith` on the *raw* requested path.

### 5.4 Use case D — A booking server that *elicits* a structured choice (TypeScript, server side)

Scenario: mid-tool, the server needs the user to choose a cabin and confirm a bag. Shows the three-action handling.

```ts
// Inside a tool handler on the SERVER:
const result = await server.elicitInput({
  message: "Finalize your flight: choose a cabin and confirm a checked bag.",
  requestedSchema: {
    type: "object",
    properties: {
      cabin: { type: "string", enum: ["economy", "premium", "business"],
               description: "Cabin class" },
      checkedBag: { type: "boolean", default: false, description: "Add a checked bag?" },
    },
    required: ["cabin"],
  },
});

switch (result.action) {
  case "accept": {
    // Validate AGAIN server-side; never trust the client's validation alone.
    const { cabin, checkedBag } = result.content as { cabin: string; checkedBag?: boolean };
    if (!["economy", "premium", "business"].includes(cabin)) {
      throw new Error("Invalid cabin from client"); // defense in depth
    }
    return finalizeBooking(cabin, checkedBag ?? false);
  }
  case "decline":
    return { content: [{ type: "text", text: "Booking cancelled (you declined)." }] };
  case "cancel":
    return { content: [{ type: "text", text: "No changes made (dismissed)." }] };
}
```

Note: `cabin` and `checkedBag` are *primitives* — schema-legal for elicitation. Asking for a nested `passenger:{name,dob}` object would violate the flat-schema rule; split it into `passengerName`, `passengerDob` (string, format `date`) instead, or use a tool call.

### 5.5 Use case E — A client declaring & updating roots when the user opens a folder (TypeScript)

```ts
const client = new Client(
  { name: "MyIDE", version: "2.0.0" },
  { capabilities: { roots: { listChanged: true } } }
);

let roots = [{ uri: "file:///Users/me/project-a", name: "Project A" }];

import { ListRootsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
client.setRequestHandler(ListRootsRequestSchema, async () => ({ roots }));

// When the user opens another folder in the IDE:
function onUserOpenedFolder(absPath: string) {
  roots = [...roots, { uri: `file://${absPath}`, name: pathBasename(absPath) }];
  // Tell every connected server the set changed; they will re-call roots/list.
  client.notification({ method: "notifications/roots/list_changed" });
}
```

### 5.6 Use case F — A nested-agent server that samples in a loop (Java, sketch)

Scenario: a "research" server plans, acts, reflects — all by sampling — with a host-respecting budget.

```java
String runMiniAgent(McpSyncServerExchange ex, String goal) {
    int maxSteps = 4;                 // bound recursion: protect the user's bill
    StringBuilder scratch = new StringBuilder("Goal: " + goal + "\n");
    for (int step = 0; step < maxSteps; step++) {
        CreateMessageResult plan = ex.createMessage(CreateMessageRequest.builder()
            .messages(List.of(new SamplingMessage(Role.USER,
                new TextContent(scratch + "\nDecide the next single action or say DONE."))))
            .maxTokens(200).temperature(0.3)
            .includeContext(CreateMessageRequest.ContextInclusionStrategy.NONE)
            .build());
        String action = ((TextContent) plan.content()).text();
        if (action.contains("DONE")) break;
        String observation = executeLocalTool(action);   // server's own tools
        scratch.append("\nStep ").append(step).append(": ").append(action)
               .append("\nObs: ").append(observation);
    }
    return scratch.toString();
}
```

The `maxSteps` cap is the application-level recursion guard the protocol doesn't provide.

---

## 6. Implementation concerns & best practices

### 6.1 Security (the dominant concern)

Sampling, elicitation, and roots are the parts of MCP most directly tied to *trust and data exfiltration*, because they let an external server touch your model, your user, and your filesystem.

**Sampling threats & mitigations:**

- **Prompt injection / data exfiltration.** A malicious server crafts a sampling prompt designed to make the model reveal context it shouldn't, then reads the completion. *Mitigations:* HITL approval showing the actual prompt; default `includeContext` to `none`/`thisServer`; let the user veto `allServers`; strip/redact host secrets before any context inclusion; show the user the completion before returning it.
- **Cost / denial-of-wallet.** A server samples in tight loops on the host's billed model. *Mitigations:* per-server token and call-count budgets; rate limits; user-visible running cost; default budgets should NOT be unlimited.
- **Model misdirection.** A server's `systemPrompt` tries to jailbreak the host's model. *Mitigations:* treat `systemPrompt` as untrusted; the host MAY prepend its own immutable system framing; the host MAY ignore/sanitize.

**Roots threats & mitigations:**

- **Roots are NOT a sandbox.** The wire protocol does not stop a server from accessing paths outside roots; it only *informs*. *Mitigations:* the **host** must enforce — run servers in containers/jails, use OS file permissions, mount only allowed directories, or use seccomp/AppArmor/SELinux. The **server** should *also* self-enforce (path normalization, `startsWith`, symlink resolution; see 5.3).
- **Path traversal & symlink escape.** Classic `../` and symlink attacks. *Mitigations:* normalize then containment-check then `toRealPath` (5.3).

> **What are containers / chroot / seccomp / AppArmor / SELinux?** *Containers* (e.g., Docker) isolate a process's filesystem/network/PID namespaces. *chroot* changes a process's apparent root directory. *seccomp* filters which syscalls a process may make. *AppArmor* and *SELinux* are Linux mandatory-access-control systems that confine a process to a policy regardless of file permissions. These are how a host actually *enforces* what roots only *advertise*.

**Elicitation threats & mitigations:**

- **Phishing for secrets.** A server elicits "enter your password to continue." *Mitigations:* the spec forbids requesting secrets; clients SHOULD clearly attribute the request to the originating server ("Server X is asking…") so users can judge; never auto-fill from a credential store.
- **Confused-deputy / spoofed prompts.** A server tries to look like the host's own UI. *Mitigations:* client must visually distinguish server-originated elicitation from host UI and show provenance.

**General:** treat all server input (sampling results echoed back, elicited content) as **untrusted**; validate server-side; log provenance; require explicit user consent for these capabilities and make consent revocable.

### 6.2 Correctness & concurrency

- **Capability gating.** Always check the negotiated capability before calling `createMessage`/`elicitInput`/`listRoots`. Calling an unsupported method yields an error and a poor UX.
- **Request IDs are per-session and must be unique while in flight.** The SDK manages this; if you hand-roll JSON-RPC, never reuse an `id` that's still pending.
- **Concurrent server-initiated requests.** A server may have multiple sampling/elicitation requests outstanding; the client must correlate responses by `id`. Async SDKs (Reactor) handle this naturally; sync code must not serialize unnecessarily.
- **Roots cache coherence.** Cache roots but invalidate on `list_changed`. A stale cache can let a server operate against the wrong scope after the user closes a folder.
- **Cancellation.** Honor `notifications/cancelled` — abort the underlying LLM call to actually save cost, not just drop the response.

### 6.3 Performance & cost

- Sampling is the expensive operation (real model tokens). **Set `maxTokens`** on every request; choose low `temperature` for deterministic tasks (also enables host-side caching).
- Prefer `includeContext: none` unless you genuinely need context — it's cheaper (fewer tokens) *and* safer.
- Batch where possible; avoid sampling per-item in a loop when one call over a list suffices.
- Elicitation and roots are cheap (no model tokens); the cost is *latency* (a human round-trip for elicitation). Don't elicit for things you can default or infer.

### 6.4 Observability

- Log every sampling request with: requesting server, model requested vs used, token counts, cost, approval decision, latency. This is your audit trail for "why did the bill spike / what did server X ask the model."
- Log elicitation with: server, schema, action (accept/decline/cancel) — but **redact `content`** if it could contain anything sensitive.
- Log roots changes with timestamps so you can reconstruct "what scope was server X operating under at time T."
- Surface these as metrics: `sampling_requests_total{server,decision}`, `sampling_tokens{server}`, `elicitation_total{server,action}`.

### 6.5 Testing

- **Unit-test the server's gating logic** with a fake client capability set: client-with-sampling, client-without, client-with-roots-but-no-listChanged.
- **Test the path-traversal defense** with adversarial inputs: `../`, absolute paths outside roots, symlinks, Windows `..\`, URL-encoded `%2e%2e`.
- **Test elicitation actions**: accept/decline/cancel branches; schema-invalid content (client returns bad data — server must reject).
- **Mock the LLM** in sampling tests; assert your server handles denial errors and unexpected `model` values.
- Use the official **MCP Inspector** (a debugging tool) to exercise a server interactively and watch the JSON-RPC traffic.

### 6.6 Production hardening checklist

- [ ] Sampling requires explicit, per-server user consent; default deny for untrusted servers.
- [ ] Per-server token and call budgets enforced; alarms on overage.
- [ ] `includeContext` capped (often at `thisServer`) unless user opts up.
- [ ] Servers run sandboxed; roots backed by real OS-level confinement, not just advertised.
- [ ] Server self-validates paths *and* elicited content.
- [ ] Elicitation never requests secrets; UI shows server provenance.
- [ ] Full audit logging of all three flows.
- [ ] Cancellation actually cancels the upstream LLM call.
- [ ] Spec revision pinned; integration tests run against that revision's schema.

### 6.7 Anti-patterns

- **Putting an LLM API key in the server.** Defeats the purpose of sampling and spreads secrets.
- **Treating roots as a security boundary.** They're advisory; enforce in the host.
- **Naive path containment** (string `contains`) — vulnerable to traversal/symlinks.
- **Eliciting secrets or using nested elicitation schemas.** Violates the spec and breaks clients.
- **Unbounded sampling loops.** Denial-of-wallet.
- **Skipping capability checks** and then crashing when the client doesn't support the feature.
- **Trusting the client to validate elicited content** without re-validating server-side.

---

## 7. Advanced topics & deep internals

### 7.1 Model selection: how hosts interpret `modelPreferences`

The three priority scalars are intentionally abstract because **the host knows its own catalog and the server doesn't.** A robust host computes a score per available model, e.g.:

```
score(model) = wIntelligence * capability(model)
             + wSpeed        * speed(model)
             - wCost         * pricePerToken(model)
             + hintBonus(model, hints)   // boost if a hint substring matches the model name
```

`hintBonus` lets the server nudge ("prefer Claude") without hard-coding a model that may not exist on this host. **Edge case:** a server sends a hint for a model the host doesn't have — the host ignores the hint and falls back to priorities. Servers must therefore *never* assume `result.model` equals their hint.

### 7.2 The `includeContext` mechanics (and why it's underspecified by design)

The spec deliberately leaves "what is context" to the host. In practice `thisServer` might mean "the resources/prompts the requesting server has exposed plus the recent conversation turns involving it." `allServers` widens to every connected server. Because semantics vary by host, **a server cannot rely on receiving any particular context** — it should send everything it strictly needs inside `messages` and treat `includeContext` as best-effort enrichment. Security-wise, `allServers` is the cross-server-leak vector and cautious hosts disable it.

### 7.3 Multimodal content in sampling

`content` can be `image` or `audio` (audio added in later revisions) with base64 `data` + `mimeType`, not just text. This lets a server ask the host's *multimodal* model to, e.g., describe a screenshot. Two gotchas: (1) the host's selected model must support that modality — if it doesn't, you get an error or degraded result; (2) base64 inflates payloads ~33%, which matters on stdio (large messages can stall a line-delimited transport).

### 7.4 Streaming and sampling

The `sampling/createMessage` *response* is a single result, not a stream, at the protocol boundary — even if the host streams tokens internally from its model. So a server gets the completion all at once. If you need progressive output to the user, that's a host UI concern, not something the server observes. (Progress notifications via `notifications/progress` exist for long operations but are about progress signaling, not token streaming.)

### 7.5 Elicitation vs tool parameters vs prompts — a subtle taxonomy

- **Tool parameters** are decided by the *model* up front when it calls the tool.
- **Elicitation** is the *server* asking the *user* directly, mid-execution, bypassing the model for that specific input. This is key: elicited input does **not** pass through the model's reasoning (good for privacy/precision; the model never sees a password-adjacent field if you keep it out — though you must not elicit actual secrets).
- **Prompts** (server-exposed prompt templates) are user-invoked, pre-filled message scaffolds — a different mechanism entirely.

Edge case: what if the user provides elicited data that the model later contradicts? The server holds the authoritative elicited value; resolve conflicts in the server's logic.

### 7.6 Roots beyond `file://`

The spec permits non-file URI schemes. A server scoped to `https://api.acme.internal/v2/` means "only call endpoints under this prefix." This generalizes roots from "filesystem fence" to "URI-space fence," useful for HTTP-calling servers. Tooling support varies; most current servers use `file://`.

### 7.7 Interaction with the broader spec evolution

- **Structured tool output** and **resource links** (newer revisions) interplay with these features: a tool can return structured content that a subsequent sampling call references.
- **OAuth-based authorization** for HTTP transport (formalized in 2025 revisions) governs *whether a server may connect at all*; it's orthogonal to but compounding with sampling/elicitation trust — an authorized server is still untrusted for prompt-injection purposes.
- Because Elicitation is the newest of the three, expect schema-restriction details and the action enum to be the most likely to shift. **Re-read the changelog between revisions.**

### 7.8 Lesser-known behaviors

- A server MAY call `roots/list` repeatedly even without a `list_changed` notification; nothing forbids polling, though it's wasteful — prefer the notification.
- `modelPreferences` priorities are independent, not normalized — you can set all three to `0.9`; the host weighs them however it likes.
- A client that declares `sampling` but, at request time, has no model available (e.g., offline) should return an error, not hang.
- Declining elicitation (`decline`) vs cancelling (`cancel`) is a real semantic distinction servers can use for UX, but both mean "no validated data."

---

## 8. Tradeoffs & decision frameworks

### 8.1 When to use each capability

| Need | Use | Don't use |
|---|---|---|
| Server needs LLM intelligence | **Sampling** | Embedding your own model/key |
| Server needs a typed answer from the human | **Elicitation** | Stuffing the question into a tool result |
| Server needs to know its filesystem/URI scope | **Roots** | Hard-coding paths / out-of-band config |
| Server needs a value the model can infer | Tool parameters | Elicitation (don't bug the user) |
| Server needs to call an external API with its own model | Its own logic (no MCP capability) | Sampling for non-host models |

### 8.2 Sampling vs server-owned LLM call

| Dimension | Sampling (via host) | Server calls its own LLM |
|---|---|---|
| Credentials | None in server | Server holds keys (risk) |
| Model choice | Host decides (advisory hints) | Server decides |
| Cost/billing | Host's account, host-metered | Server's account |
| Safety review (HITL) | Built-in (host) | Server must build it |
| Vendor lock-in | None for server | Server bound to a vendor |
| Latency | Extra hop through host | Direct |
| Use when | You want host control & no secrets | You need a specific model/feature the host lacks |

### 8.3 Elicitation vs alternatives

| Approach | Structured? | Validated? | Model sees it? | Good for |
|---|---|---|---|---|
| **Elicitation** | Yes (flat schema) | Yes | No (direct to user) | Mid-flow choices, confirmations |
| Tool parameters | Yes (full schema) | Yes | Yes (model fills) | Inputs the model can decide up front |
| Free-text in tool result | No | No | Yes | Last resort / legacy clients |
| Server prompt template | Templated | n/a | Yes | User-initiated scaffolds |

### 8.4 Roots vs OS sandboxing

| Mechanism | Enforces? | Layer | Role |
|---|---|---|---|
| **Roots** | No (advisory) | Protocol | Tells the server its intended scope |
| OS permissions | Yes | Kernel | Hard limit on file access |
| Containers/chroot | Yes | OS namespaces | Confine the server process |
| seccomp/AppArmor/SELinux | Yes | Kernel MAC | Restrict syscalls/paths |

**Rule:** roots *communicate intent*; the other rows *enforce* it. Use both — roots for the server's own correctness, OS/container for actual security.

### 8.5 `includeContext` decision rule

- Use `none` by default.
- Step up to `thisServer` only if the task genuinely needs the requesting server's context.
- Avoid `allServers` unless the user explicitly authorizes cross-server context; cautious hosts disable it entirely.

---

## 9. Failure modes & debugging

### 9.1 Common failures and diagnosis

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Server's sampling call errors immediately | Client never declared `sampling` | Inspect `initialize` exchange; check `clientCapabilities` | Gate on capability; degrade gracefully |
| Sampling "hangs" | Waiting on HITL approval the user never sees / client has no model | MCP Inspector; client logs | Add timeout; ensure HITL UI fires; handle no-model |
| Bill spikes | Unbounded sampling loop / large `maxTokens` / `allServers` context | Audit logs of tokens per server | Budgets, cap `maxTokens`, restrict context |
| Server reads files outside intended dir | Roots not enforced / traversal bug | Reproduce with `../` and symlink inputs | Normalize + `startsWith` + `toRealPath`; sandbox |
| Server uses stale scope | Missed `list_changed` | Check notification handling | Refresh roots on every `list_changed` |
| Elicitation returns garbage / crashes client | Nested/array schema or non-primitive props | Validate schema against the restricted subset | Flatten schema to primitives |
| Elicited data invalid but accepted | Server trusted client validation | Unit test with bad content | Re-validate server-side |
| `result.model` unexpected | Host substituted a different model | Log requested vs actual | Don't assume hint honored |
| Version mismatch errors | `protocolVersion` not agreed | Inspect `initialize` result | Pin & support the negotiated version |

### 9.2 Tooling

- **MCP Inspector** — the official interactive debugger; connects to a server, lists capabilities, and lets you fire requests while showing raw JSON-RPC. First stop for "what is actually on the wire."
- **Transport-level capture** — for stdio, log stdin/stdout (line-delimited JSON); for HTTP, capture POST bodies and SSE events (mitmproxy, `tcpdump`, browser devtools).
- **SDK debug logging** — both Java and TS SDKs can log message traffic; enable it in dev.
- **`ping`** — verify liveness when a session seems stuck.

> **What is mitmproxy / tcpdump / SSE?** *mitmproxy* is an interactive HTTPS proxy for inspecting/modifying traffic. *tcpdump* captures raw network packets. *SSE* (Server-Sent Events) is a one-way HTTP streaming format (`text/event-stream`) the Streamable HTTP transport uses to push server→client messages over a long-lived response.

### 9.3 Realistic incident narratives

- **Denial-of-wallet via nested agent.** A research server sampled in a loop with no `maxSteps` and high `maxTokens`; an ambiguous goal kept it from emitting `DONE`. Result: a large, surprising model bill. *Lesson:* always bound recursion and tokens; alarm on per-server token rate.
- **Traversal past roots.** A filesystem server used `requestedPath.startsWith(rootString)` on raw input; an attacker passed `/Users/me/project-a/../../../etc/passwd`, which *did* start with the root string but resolved elsewhere. *Lesson:* normalize before containment-check, and resolve symlinks.
- **Elicitation rendering failure.** A server requested a nested `address:{street,city}` object; a client that only supported flat forms either errored or silently dropped fields. *Lesson:* keep schemas flat and primitive.
- **Context over-share.** A server requested `includeContext: allServers` to "be helpful"; the host, lacking a cap, included another server's data in the prompt, and the completion echoed sensitive content back. *Lesson:* default `none`, cap `allServers`, show prompts in HITL.

---

## 10. Interview drill

**Q1. What is MCP sampling and why is the inversion of control significant?**
*Model answer:* Sampling is a server→client request (`sampling/createMessage`) asking the host to run an LLM completion. The inversion — server calling the host's model — matters because the server gains intelligence with no API key, no model lock-in, and host-controlled cost/safety/HITL. It enables nested agentic behavior in servers that aren't themselves AI apps.
- *Probe: Who picks the model?* The host. `modelPreferences.hints` and the three priority scalars are advisory; `result.model` reports what actually ran and may differ.
- *Probe: Why sensitive?* It injects prompts into your model and can exfiltrate context or run up cost — hence HITL and `includeContext` controls.
- *Probe: How bound cost?* Per-server token/call budgets, low `maxTokens`, `includeContext:none`, recursion caps.

**Q2. Walk through the sampling message flow including the human-in-the-loop gates.**
*Model answer:* Server builds `CreateMessageRequest` → sends `sampling/createMessage` → client checks the capability was negotiated and applies policy → HITL gate #1 (approve/edit prompt) → host runs the completion → optional HITL gate #2 (review result) → client returns `{role,content,model,stopReason}` or a JSON-RPC error if denied → server consumes it.
- *Probe: What if the user denies?* Client returns an error; the server must handle that path.
- *Probe: Is the response streamed?* No — single result at the protocol boundary, even if the host streams internally.

**Q3. What are roots, and is a root a security boundary?**
*Model answer:* Roots are client-advertised URIs (usually `file://`) telling a server its operational scope, fetched via `roots/list` and refreshed on `notifications/roots/list_changed`. They are **advisory**, not a sandbox — the protocol doesn't enforce them. Real enforcement is the host's job via OS permissions, containers, chroot, seccomp/AppArmor/SELinux.
- *Probe: How does a server defend its own use of roots?* Normalize the path (collapse `..`), check `startsWith` a root, then `toRealPath` to defeat symlink escape.
- *Probe: When do roots change and what must the server do?* When the user opens/closes folders; the server re-calls `roots/list` on the change notification.

**Q4. What is elicitation and how does it differ from tool parameters?**
*Model answer:* Elicitation (2025-06-18+) is a server→client request (`elicitation/create`) asking the host to collect structured, schema-validated input from the *user*, returning `accept`/`decline`/`cancel`. Unlike tool parameters (which the *model* fills up front), elicited input comes directly from the user mid-flow and bypasses the model's reasoning.
- *Probe: Schema restrictions?* Flat object of primitives (string/number/integer/boolean/enum); no nested objects or arrays of objects; never request secrets.
- *Probe: Why three actions?* To distinguish a deliberate `decline` from a `cancel` (dismissal); both mean no validated data.

**Q5. How are these capabilities negotiated?**
*Model answer:* Entirely at `initialize`. The *client* declares `sampling`, `elicitation`, and/or `roots:{listChanged}` in its `capabilities`. A server checks `clientCapabilities` before using any of them. No further negotiation after the handshake.
- *Probe: Does the server declare sampling?* No — sampling/elicitation are client capabilities the server consumes; roots too is client-side.
- *Probe: What governs field availability?* The negotiated `protocolVersion` (a dated revision).

**Q6. (Senior signal) When would you NOT use sampling, and use a server-owned LLM call instead?**
*Model answer:* When the server needs a specific model/feature the host can't provide, or needs direct low-latency calls, or operates headless where no host model exists. The tradeoff: you reintroduce credentials, billing, and the need to build your own safety/HITL — and you lose host cost control. Default to sampling for host-embedded scenarios; go server-owned only when a concrete capability gap forces it.
- *Probe: Hybrid?* A server can use sampling when available and fall back to its own call when the client lacks the capability — but then it must guard secrets carefully.

**Q7. (Senior signal) You see a 10x model-cost spike attributed to one MCP server. Diagnose and prevent.**
*Model answer:* Pull per-server sampling audit logs (token counts, call counts, `maxTokens`, `includeContext`). Likely causes: unbounded nested-agent loop, oversized `maxTokens`, or `allServers` context inflating prompts. Immediate: revoke the server's sampling consent or cap it. Structural: enforce per-server token/call budgets with alarms, default `includeContext:none`, cap `maxTokens`, bound recursion, and surface running cost in HITL.
- *Probe: How to prevent recursion runaway?* Application-level step caps (the protocol has none) plus total-token budgets.

**Q8. (Senior signal) Critique "roots make MCP servers safe to give filesystem access."**
*Model answer:* False/dangerous. Roots are advisory metadata, not enforcement; a buggy or malicious server can ignore them if OS permissions allow. Safety requires real confinement — sandbox the server process (container/chroot/seccomp/MAC), grant only the needed mounts, and have the server self-validate paths (normalize + containment + symlink resolution). Roots complement, not replace, enforcement.
- *Probe: Defense-in-depth layers?* Host OS confinement + server-side path validation + roots as intent + audit logging.

**Q9. How do you defend the path-traversal attack against a roots-respecting server?**
*Model answer:* Normalize the requested path to absolute form (collapsing `..`), verify it `startsWith` at least one normalized root, then resolve symlinks via `toRealPath` and re-check containment. Reject otherwise. Never string-match raw input.
- *Probe: Windows / encoding pitfalls?* Handle `..\`, mixed separators, and URL-encoded `%2e%2e`; normalize first.

**Q10. What does `includeContext` do and why is `allServers` risky?**
*Model answer:* It tells the host whether/what MCP context to add to a sampling prompt: `none`, `thisServer`, or `allServers`. `allServers` lets the requesting server's prompt be enriched with *other* servers' data — a cross-server data-leak vector — so cautious hosts cap below it or require explicit user consent.
- *Probe: Default you'd ship?* `none`, stepping up only when needed, never `allServers` without user authorization.

**Q11. How does a server detect and react to roots changing?**
*Model answer:* If the client declared `roots:{listChanged:true}`, it sends `notifications/roots/list_changed`; the server re-calls `roots/list` and updates its cached, normalized root set. Without `listChanged`, the set is assumed static (or the server may poll, wastefully).

**Q12. What content types can sampling carry, and what breaks with multimodal?**
*Model answer:* `text`, `image`, and (later revisions) `audio` via base64 `data` + `mimeType`. Breakage: the host's selected model must support the modality, and base64 inflates payloads ~33%, which can stress stdio's line-delimited transport on large blobs.

---

## 11. Glossary

- **AppArmor / SELinux** — Linux mandatory-access-control systems confining a process to a policy regardless of file permissions.
- **Base64** — text encoding of binary data; inflates size ~33%.
- **Capability negotiation** — agreeing at `initialize` on which features each side supports.
- **chroot** — changes a process's apparent root directory; a crude filesystem jail.
- **Client** — the per-server connector inside the host; speaks MCP to one server.
- **Completion / sampling (model sense)** — the text a model generates from a prompt.
- **Container** — OS-level isolation of filesystem/network/PID namespaces (e.g., Docker).
- **Elicitation** — server→client request (`elicitation/create`) for structured user input via JSON Schema; returns accept/decline/cancel. (2025-06-18+)
- **HITL (human-in-the-loop)** — pausing for human approval/edit before proceeding.
- **Host** — the user-facing app containing the LLM and orchestrating clients.
- **`includeContext`** — sampling field controlling host-injected context: `none`/`thisServer`/`allServers`.
- **JSON-RPC 2.0** — the JSON request/response/notification format MCP uses.
- **JSON Schema** — vocabulary describing JSON data shape; elicitation uses a restricted flat subset.
- **`maxTokens`** — cap on generated tokens (bounds length and cost).
- **MCP Inspector** — official interactive debugger for MCP servers.
- **mitmproxy** — interactive HTTPS proxy for inspecting/modifying traffic.
- **`modelPreferences`** — advisory sampling hints (`hints`, cost/speed/intelligence priorities).
- **Notification** — a JSON-RPC message with no `id`, no response expected.
- **Prompt injection** — crafting input to subvert a model's behavior or exfiltrate context.
- **`protocolVersion`** — dated string identifying the agreed spec revision.
- **Project Reactor** — JVM reactive library (`Mono`/`Flux`) the Java SDK's async flavor uses.
- **Roots** — client-advertised URIs scoping a server's operations; advisory, fetched via `roots/list`, refreshed via `list_changed`.
- **Sampling (MCP sense)** — server→client request (`sampling/createMessage`) to run an LLM completion.
- **seccomp** — Linux facility filtering which syscalls a process may make.
- **Server** — external program exposing tools/resources/prompts; untrusted by default.
- **SSE (Server-Sent Events)** — one-way HTTP streaming (`text/event-stream`) used by Streamable HTTP transport.
- **stdio transport** — server runs as a subprocess; messages over stdin/stdout.
- **`stopReason`** — why a completion ended: `endTurn`/`maxTokens`/`stopSequence`.
- **Streamable HTTP transport** — HTTP POST + SSE transport (replaced HTTP+SSE in 2025-03-26).
- **Symlink** — a filesystem pointer to another path; a traversal-escape vector.
- **`temperature`** — sampling randomness; low = focused/repeatable, high = varied.
- **tcpdump** — raw network packet capture tool.
- **Transport** — the byte pipe between client and server (stdio or Streamable HTTP).
- **URI** — Uniform Resource Identifier; e.g., `file:///path`.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
DIRECTION OF CONTROL
  Normal:        client → server  (tools/resources/prompts)
  Sampling:      server → client  (sampling/createMessage)   = borrow host's BRAIN
  Elicitation:   server → client  (elicitation/create)        = borrow host's MOUTH/EARS (user)
  Roots:         client → server  (roots/list + list_changed) = fence the YARD

NEGOTIATION (all at initialize; CLIENT declares all three)
  sampling: {}                         roots: { listChanged: bool }
  elicitation: {}   (2025-06-18+)

SAMPLING PARAMS         RESULT
  messages*             role, content, model(actual), stopReason
  modelPreferences      stopReason ∈ {endTurn, maxTokens, stopSequence}
    hints[] (advisory), costPriority/speedPriority/intelligencePriority 0..1
  systemPrompt, temperature, maxTokens*, stopSequences, metadata
  includeContext ∈ {none(default safe), thisServer, allServers(risky)}

ROOTS                   ELICITATION
  {uri (file://), name} action ∈ {accept(+content), decline, cancel}
  refresh on            schema: FLAT object of PRIMITIVES only
   list_changed          (string/number/integer/boolean/enum)
  NOT a sandbox →        NEVER request secrets
   enforce in host       client validates + server RE-validates

SECURITY RULES OF THUMB
  - HITL on sampling & elicitation; default includeContext = none
  - Per-server token/call BUDGETS; bound recursion (no protocol limit)
  - Roots advisory → enforce via container/chroot/seccomp/MAC + OS perms
  - Path defense: normalize → startsWith(root) → toRealPath (symlinks)
  - Never put an LLM API key in a server; sampling is why it's unnecessary
  - Pin protocolVersion; Elicitation is newest → most likely to change
```

### 12.2 Self-test (no answers — recall actively)

1. Trace the full `sampling/createMessage` flow including both HITL gates and the denial path. Who decides the model, and what field reports the model that actually ran?
2. Your filesystem server gets the path `/proj/a/../../etc/shadow` and `/proj/a` is a root. Write the exact three-step check that must reject it, and explain why a naive `startsWith` on the raw string fails.
3. Why is "roots make a server safe to give filesystem access" wrong? Name three OS-level mechanisms that actually enforce scope.
4. A server's `elicitation/create` schema requests `{ passenger: { name, dob }, bags: [...] }`. Identify every spec violation and rewrite it legally.
5. Distinguish elicitation `decline` from `cancel`, and explain why the model never sees elicited input — what's the privacy/correctness consequence?
6. You're handed a 10x model-cost spike on one server. List the audit fields you'd pull, the three most likely root causes, and the structural fixes.
7. Explain `includeContext` values and construct a concrete scenario where `allServers` causes a data leak that HITL would have caught.
8. The client declared `sampling` but no `elicitation`. A server calls `elicitation/create`. What happens, and how should a well-written server have avoided it?
```

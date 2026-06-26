# gRPC — A Definitive Engineering Handbook Chapter

> **Reader profile:** A senior software developer in the Java/JVM backend ecosystem who wants to fully master gRPC — from first principles to deep internals — well enough to design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### What gRPC is

**gRPC** is a high-performance, open-source **Remote Procedure Call (RPC)** framework originally built at Google and now governed by the **CNCF** (Cloud Native Computing Foundation — the open-source foundation that also hosts Kubernetes, Prometheus, and Envoy). The "g" officially stands for nothing fixed; each release picks a backronym ("gRPC Remote Procedure Calls", "good", "green", etc.). What matters is the shape of the thing.

An **RPC** is the idea that calling a function on a remote machine should look, to the programmer, almost exactly like calling a local function: you invoke `getUser(id)`, and behind the scenes the arguments are serialized, shipped over the network, executed on a server, and the return value is shipped back and deserialized. The framework hides the networking. RPC is an old idea (Sun RPC in the 1980s, CORBA in the 1990s, Java RMI, Thrift, etc.); gRPC is its modern, HTTP/2-based incarnation.

gRPC stands on two pillars:

1. **Protocol Buffers (Protobuf)** — Google's **Interface Definition Language (IDL)** and binary serialization format. You describe your service's methods and message types in a `.proto` file; a code generator emits strongly-typed client and server code in your language.
2. **HTTP/2** — the transport. gRPC maps each RPC onto an HTTP/2 **stream**, exploiting multiplexing, binary framing, header compression, and flow control.

> **IDL (Interface Definition Language):** a language-neutral way to declare the shape of an API — its methods, their inputs, and their outputs — independent of any one programming language. From a single IDL file, tooling generates matching code in Java, Go, Python, C++, etc., guaranteeing both sides agree on the contract.

### The problem it solves

In a microservice architecture, dozens or hundreds of services call each other constantly, often in the **hot path** of a user request (the latency-critical chain of internal calls that must all complete before the user gets a response). Doing this over **REST/JSON** (textual HTTP APIs, see §8) has real costs:

- **Serialization is expensive and verbose.** JSON is text: field names are repeated on the wire, numbers are stringified, parsing is CPU-heavy.
- **The contract is implicit.** A REST endpoint's shape lives in documentation, OpenAPI specs, or tribal knowledge — easy to drift, hard to enforce at compile time.
- **No native streaming.** Classic REST is request/response; long-lived streams require ad-hoc machinery (SSE, WebSockets, long-polling).
- **Connection overhead.** HTTP/1.1 opens many TCP connections or serializes requests on one (head-of-line blocking).

gRPC attacks all four: a compact binary format, a compiler-enforced contract, first-class streaming, and HTTP/2 multiplexing over a single connection.

### When you reach for it

- **Internal east-west traffic** between microservices, especially latency- and throughput-sensitive paths.
- **Polyglot environments** where services in Java, Go, Python, C++, Rust must interoperate with a shared, enforced contract.
- **Streaming workloads**: telemetry feeds, log shipping, chat, live dashboards, bidirectional control channels.
- **Low-latency, high-QPS** RPC where JSON's CPU cost and verbosity hurt (think mobile-backend fan-out, ad serving, ML feature serving).

### When you do *not* reach for it

- **Public browser-facing APIs** where you want plain HTTP/JSON consumable from `fetch()` and cURL with zero tooling (browsers cannot speak raw gRPC — see gRPC-Web, §7).
- **Simple CRUD with broad third-party consumption** where REST's ubiquity and human-readability win.
- **Where human-debuggable wire traffic** matters more than performance (binary Protobuf is opaque without tooling).

### One-paragraph mental model

> Write your API as a set of typed method signatures in a `.proto` file. A compiler turns that into a **client stub** (looks like a local object whose methods make network calls) and a **server skeleton** (an abstract base class you implement). At runtime, each method call becomes one HTTP/2 stream: the request message is Protobuf-encoded, length-prefixed, framed, multiplexed onto a shared TCP connection, executed on the server, and the response (or a stream of responses) flows back the same way. Deadlines, cancellation, metadata (headers), and a rich status-code error model are built in. Because everything is HTTP/2 streams over one connection, you get cheap multiplexing and true bidirectional streaming for free.

---

## 2. Foundations from first principles

### 2.1 What "RPC" really means, mechanically

When you call `stub.getUser(request)`:

1. **Marshalling (serialization):** the `request` object is converted to a byte sequence per the Protobuf wire format.
2. **Transport:** those bytes are wrapped in a gRPC message frame, placed in HTTP/2 DATA frames, and sent over a TCP (usually TLS) connection.
3. **Dispatch:** the server reads the bytes, identifies the target method from the HTTP/2 `:path` header (e.g. `/myapp.UserService/GetUser`), and **unmarshals** them back into a `GetUserRequest` object.
4. **Execution:** your server method runs and returns a `GetUserResponse`.
5. **Return path:** that response is marshalled, framed, and sent back; the client unmarshals it into a return value.

> **Marshalling / serialization:** turning an in-memory object (with pointers, fields, nesting) into a flat byte stream suitable for storage or transmission. **Unmarshalling / deserialization** is the reverse. The two sides must agree on the encoding — that agreement is the wire format.

The key promise of RPC is *location transparency*: the call looks local. The key danger is that this transparency is a **leaky abstraction** — networks fail, add latency, and partition in ways local calls never do. Good gRPC code never forgets that every stub call can be slow, fail, or be cancelled.

### 2.2 Protocol Buffers — the IDL

A `.proto` file declares messages and services:

```proto
syntax = "proto3";                       // proto3 is the modern dialect

package myapp.user.v1;                   // logical namespace; version it!

option java_package = "com.myapp.user.v1";   // Java package for generated code
option java_multiple_files = true;            // one .java file per type (vs one big outer class)

// A message is a typed record. Each field has a name, a type, and a TAG NUMBER.
message User {
  string id = 1;          // field number 1 — NEVER reuse or change a tag once shipped
  string email = 2;
  int32 age = 3;
  repeated string roles = 4;   // 'repeated' = a list/array
  Address address = 5;         // nested message
}

message Address {
  string street = 1;
  string city = 2;
  string country_code = 3;
}

// A service is a set of RPC methods.
service UserService {
  rpc GetUser(GetUserRequest) returns (User);
}

message GetUserRequest {
  string id = 1;
}
```

The **field tag numbers** (the `= 1`, `= 2`) are the heart of Protobuf's efficiency and compatibility story. On the wire, fields are identified by these small integers, **not** by their names. This is why Protobuf is compact (no field names transmitted) and why renaming a field is safe but **renumbering or reusing a tag is catastrophic** — an old client and new server will silently misinterpret each other's bytes.

#### proto2 vs proto3

| Aspect | proto2 | proto3 |
|---|---|---|
| Field labels | `required`, `optional`, `repeated` | `repeated` and (since 3.15) explicit `optional`; no `required` |
| Default presence | Fields could be required | All scalar fields have implicit defaults; no "required" |
| Field presence tracking | Always available | Lost for scalars by default; re-added via `optional` keyword |
| Recommendation | Legacy; avoid for new work | Default for new services |

> **`required` is gone in proto3 on purpose.** Experience at Google showed `required` is a permanent, unremovable contract: you can never make a required field optional later without breaking every old client. Removing it from proto3 was a deliberate evolvability decision.

#### Scalar types and their wire representation

| Proto type | Java type | Wire type | Notes |
|---|---|---|---|
| `int32`, `int64` | `int`, `long` | Varint | Inefficient for negative numbers (10 bytes for -1) |
| `sint32`, `sint64` | `int`, `long` | Varint (ZigZag) | Use these for values that are often negative |
| `uint32`, `uint64` | `int`, `long` | Varint | Unsigned |
| `fixed32`, `fixed64` | `int`, `long` | 32/64-bit | Faster for large values; always 4/8 bytes |
| `sfixed32/64` | `int`, `long` | 32/64-bit | Signed fixed |
| `float`, `double` | `float`, `double` | 32/64-bit | |
| `bool` | `boolean` | Varint | 1 byte |
| `string` | `String` | Length-delimited | Must be valid UTF-8 |
| `bytes` | `ByteString` | Length-delimited | Arbitrary bytes |
| `message` | generated class | Length-delimited | Nested |
| `enum` | generated enum | Varint | First value must be `= 0` (the default) |

> **Varint (variable-length integer):** an encoding where small numbers use few bytes. Each byte uses 7 bits for the value and the top bit as a "more bytes follow" flag. So `1` is one byte, `300` is two bytes, etc. This is why Protobuf shrinks typical small integers dramatically versus a fixed 4- or 8-byte field.

> **ZigZag encoding:** maps signed integers to unsigned so that small-magnitude negatives stay small: 0→0, -1→1, 1→2, -2→3, … This is what `sint32`/`sint64` use, so `-1` costs one byte instead of ten.

#### Advanced proto constructs

- **`oneof`** — a union: at most one of a set of fields is set, and they share storage. Setting one clears the others.
  ```proto
  message Event {
    oneof payload {
      LoginEvent login = 1;
      LogoutEvent logout = 2;
      PurchaseEvent purchase = 3;
    }
  }
  ```
- **`map<K,V>`** — an associative array (`map<string, int32> counts = 1;`). On the wire it is sugar for a `repeated` message of key/value pairs.
- **`enum`** — named constants; the zero value is the default and should be a sentinel like `STATUS_UNSPECIFIED = 0`.
- **Well-known types** — `google.protobuf.Timestamp`, `Duration`, `Empty`, `Any`, `Struct`, `FieldMask`, and wrappers like `StringValue` (which give scalars explicit presence/nullability).
- **`reserved`** — block off retired tag numbers and names so nobody reuses them: `reserved 4, 7 to 9; reserved "old_field";`

### 2.3 The Protobuf wire format (why it's compact)

Each field is encoded as a **key** followed by a **value**. The key is a varint: `(field_number << 3) | wire_type`. The low 3 bits are the wire type:

| Wire type | Value | Used by |
|---|---|---|
| 0 | Varint | int32/64, uint, bool, enum, sint (ZigZag) |
| 1 | 64-bit | fixed64, sfixed64, double |
| 2 | Length-delimited | string, bytes, embedded messages, packed repeated |
| 5 | 32-bit | fixed32, sfixed32, float |

(Wire types 3 and 4 were "start/end group" — deprecated.)

So a field is: `[tag+wiretype varint][value]`. A `string name = 2 ("Bob")` encodes as: tag byte `0x12` (field 2, wire type 2), length byte `0x03`, then `B o b`. Five bytes total — no field name on the wire. **Unknown fields** (tags the parser doesn't recognize) are preserved and re-emitted in proto3, which underpins forward compatibility: a new field a parser doesn't understand is carried through rather than dropped.

This format is **not self-describing** — you cannot parse it without the schema (the `.proto`). That is the tradeoff for compactness: great for performance, opaque without tooling.

### 2.4 HTTP/2 — the transport

> **HTTP/2:** a major revision of HTTP (standardized 2015, RFC 7540) that keeps HTTP's semantics (methods, headers, status codes) but replaces the text wire format with a **binary framing layer**. Multiple concurrent requests share one TCP connection via independent **streams**, eliminating HTTP/1.1's connection-per-request or head-of-line blocking at the HTTP layer.

Concepts gRPC exploits:

- **Stream:** a bidirectional, independent sequence of frames within one connection, identified by a stream ID. **Every gRPC call is exactly one stream.**
- **Frame:** the smallest unit — HEADERS, DATA, RST_STREAM, SETTINGS, WINDOW_UPDATE, etc. A gRPC request is a HEADERS frame (the metadata/headers), then DATA frames (the message bytes), then optionally trailing HEADERS (the trailers carrying status).
- **Multiplexing:** many streams interleave their frames on one connection concurrently. One slow call no longer blocks others.
- **HPACK:** header compression. Repeated headers (like `:authority`, `content-type: application/grpc`) are sent once and referenced by index thereafter.
- **Flow control:** per-stream and per-connection credit-based backpressure. A receiver advertises a window; senders must not exceed it, preventing a fast producer from overwhelming a slow consumer. (Critical for streaming RPCs.)
- **Trailers:** HTTP/2 allows headers *after* the body. gRPC uses **trailers** to send the final `grpc-status` and `grpc-message` — this is why gRPC needs HTTP/2 (HTTP/1.1 has no reliable trailers) and why browsers struggle with it (the Fetch API can't read trailers — see gRPC-Web).

A gRPC message on the wire inside DATA frames is **length-prefixed framed**: a 1-byte **compressed flag** (0 or 1) followed by a 4-byte big-endian **length**, then that many bytes of (possibly compressed) Protobuf. This 5-byte prefix is how the receiver knows where each message in a stream begins and ends.

---

## 3. How it works internally

This is the heart of the document. We trace a call end-to-end, then cover the lifecycle, the streaming variants, and the state machine.

### 3.1 Channel, Stub, and the connection lifecycle

On the **client** side, the central object is the **Channel** (`ManagedChannel` in Java). A channel is a **virtual connection to a logical endpoint** — not necessarily one TCP socket. It owns:

- **Name resolution:** turning a target string (`dns:///user-service:50051`, `static:///...`, `xds:///...`) into a set of backend addresses. The default scheme is DNS.
- **A connection pool / subchannels:** one **subchannel** per backend address; each subchannel manages an actual HTTP/2 connection (with its own connectivity state).
- **A load-balancing policy:** picks which subchannel handles each RPC (`pick_first` default, or `round_robin`, etc.).
- **Connectivity state machine:** `IDLE → CONNECTING → READY → TRANSIENT_FAILURE → … → SHUTDOWN`.

> **Subchannel:** gRPC's internal abstraction for a connection to a single backend address. The channel may hold many subchannels (one per resolved address); the load balancer chooses among them per RPC.

A **stub** is the generated client object that holds a reference to a channel and exposes the typed methods. There are three stub flavors in Java:

| Stub type | Style | Use for |
|---|---|---|
| `BlockingStub` | Synchronous; returns the response (or an `Iterator` for server streaming) | Simple unary calls, scripts |
| `Stub` (async) | Callback-based via `StreamObserver` | All streaming types; non-blocking unary |
| `FutureStub` | Returns `ListenableFuture<Resp>` | Unary calls in a futures-composition style |

Channels are **expensive and meant to be long-lived and shared** — create one per backend per process and reuse it across threads (channels are thread-safe). Stubs are **cheap, immutable, and reusable**; you derive new stubs to attach per-call config (deadlines, metadata) via `withDeadline`, `withInterceptors`, etc.

### 3.2 End-to-end trace of a unary call (control + data flow)

Step by step, what happens when client code calls `userStub.getUser(req)`:

**Client side:**

1. **Stub invocation.** The generated stub calls `ClientCalls.blockingUnaryCall(channel, METHOD, callOptions, req)`. `METHOD` is a `MethodDescriptor` carrying the full method name (`/myapp.user.v1.UserService/GetUser`), the call type (UNARY), and the request/response **marshallers** (Protobuf codecs).
2. **Interceptor chain.** The call passes through any **client interceptors** (cross-cutting hooks — auth, logging, metrics). Each can wrap the `ClientCall`, mutate metadata, or short-circuit.
3. **Pick a transport.** The channel's load-balancing policy selects a READY subchannel (its HTTP/2 connection). If none is ready, the RPC may queue (wait-for-ready) or fail fast.
4. **Open a stream.** A new HTTP/2 stream is allocated. The client sends a **HEADERS frame** containing pseudo-headers and gRPC headers:
   - `:method: POST`, `:scheme: https`, `:path: /myapp.user.v1.UserService/GetUser`, `:authority: user-service:50051`
   - `content-type: application/grpc` (or `application/grpc+proto`)
   - `te: trailers` (signals the client understands trailers)
   - `grpc-timeout: 100m` (the deadline, if set — here 100 milliseconds)
   - any custom **metadata** (e.g. `authorization: Bearer …`)
5. **Send the message.** The request is Protobuf-encoded, prefixed with the 5-byte length-prefix frame, and sent as one or more **DATA frames**, respecting flow-control windows.
6. **Half-close.** For unary, the client sends `END_STREAM` to indicate it is done sending.

**Server side:**

7. **Accept stream & route.** The server's HTTP/2 layer (Netty in `grpc-java`) receives the HEADERS, reads `:path`, and looks up the registered **service definition** and method handler in its registry.
8. **Server interceptors.** The inbound call passes through **server interceptors** (auth validation, request logging, context propagation).
9. **Decode.** DATA frames are reassembled; the 5-byte prefix tells the framer the message boundary; the bytes are unmarshalled into a `GetUserRequest`.
10. **Dispatch to your method.** The framework invokes your `getUser(GetUserRequest req, StreamObserver<User> responseObserver)` implementation, typically on a server **executor** thread pool (not the I/O event-loop thread).
11. **Your logic runs**, producing a `User`.
12. **Respond.** You call `responseObserver.onNext(user)` then `responseObserver.onCompleted()`. The framework sends HEADERS (response headers, `content-type`), DATA (the encoded `User`), then **trailing HEADERS** carrying `grpc-status: 0` (OK) and optionally `grpc-message`.

**Client side again:**

13. **Receive & decode.** The client reads response headers, DATA (decodes into `User`), and trailers (reads `grpc-status`). If status is OK, the blocking stub returns the `User`. If non-zero, it throws a `StatusRuntimeException`.
14. **Stream closes.** The HTTP/2 stream is released; the underlying connection stays open for reuse.

The crucial insight: **status lives in trailers, not in the HTTP status code.** The HTTP response status is almost always `200 OK` even for a failed RPC; the *gRPC* status is in `grpc-status`. (Some transport-level failures map to HTTP statuses, which gRPC then translates.)

### 3.3 The four call types

gRPC supports four call shapes, all mapping onto HTTP/2 streams; the difference is how many messages flow in each direction.

```proto
service Demo {
  rpc Unary(Req) returns (Resp);                       // 1 -> 1
  rpc ServerStream(Req) returns (stream Resp);         // 1 -> N
  rpc ClientStream(stream Req) returns (Resp);         // N -> 1
  rpc BidiStream(stream Req) returns (stream Resp);     // N <-> M
}
```

| Type | Client sends | Server sends | Java client returns | Real use cases |
|---|---|---|---|---|
| **Unary** | 1 message | 1 message | the response (blocking) / `Future` | Classic request/response: `GetUser`, `CreateOrder` |
| **Server streaming** | 1 message | N messages, then status | `Iterator<Resp>` (blocking) or `StreamObserver` callbacks | Large result sets, live feeds, "subscribe to updates", paginated downloads, LLM token streaming |
| **Client streaming** | N messages, then half-close | 1 message | `StreamObserver<Req>` you push into | Bulk upload, metrics/log ingestion, aggregating many records into one summary |
| **Bidirectional** | N messages | M messages, fully interleaved | `StreamObserver<Req>` + `StreamObserver<Resp>` callback | Chat, collaborative editing, telemetry with backpressure, long-lived control channels |

**Bidirectional streaming** is the most powerful: both sides send independent streams of messages over the same HTTP/2 stream, in any interleaving, until either half-closes. It is not request-then-response; it is two pipes. Application-level framing/protocol is up to you.

### 3.4 Deadlines and cancellation

> **Deadline vs timeout:** a **timeout** is "fail if this one call takes longer than X." A **deadline** is an absolute point in time ("fail at 12:00:01.250") that **propagates across calls**. gRPC uses deadlines so that if service A gives a call 200ms, and A calls B, B sees a deadline reflecting the *remaining* budget, not a fresh 200ms.

Mechanics:

- The client sets a deadline via `stub.withDeadlineAfter(200, MILLISECONDS)`. This becomes the `grpc-timeout` header.
- The server learns the deadline from `Context.current().getDeadline()`. A well-behaved server checks `Context.current().isCancelled()` and propagates the deadline to downstream calls automatically (gRPC's `Context` is propagated).
- When the deadline passes, the call is terminated with status **`DEADLINE_EXCEEDED`** on both sides, and the HTTP/2 stream is RST.
- **Cancellation:** a client can cancel an in-flight call (e.g., user navigated away). This sends `RST_STREAM`; the server's `Context` becomes cancelled, letting handlers stop work and release resources. Cancellation also propagates: if A→B→C and A cancels, B's context cancels, and B should cancel C.

> **`Context` (gRPC):** a thread-local-like object carrying request-scoped values — the deadline, cancellation signal, and custom values — that propagates with the call and across thread-pool boundaries (if you use gRPC's `Context.wrap`/executors). It is *not* the same as HTTP headers; metadata is the wire format, `Context` is the in-process representation.

**Without deadlines you risk resource exhaustion cascades:** a stuck downstream holds a thread/connection on every upstream caller, which holds threads on *their* callers, until pools drain and the whole mesh stalls. **Always set deadlines.**

### 3.5 Metadata

**Metadata** is gRPC's name for key/value pairs sent as HTTP/2 headers (leading metadata, before the body) or trailers (trailing metadata, after). It is the channel for cross-cutting data: auth tokens, request IDs, tracing context, tenant IDs.

- Keys ending in `-bin` carry **binary** values (base64-encoded on the wire); others are ASCII strings.
- **Reserved keys** (`grpc-*`, `:`-prefixed pseudo-headers, `content-type`) are managed by the framework — don't set them yourself.
- In Java: `Metadata` object, `Metadata.Key.of("x-request-id", ASCII_STRING_MARSHALLER)`. Attach on the client via a `MetadataUtils` interceptor or a custom `ClientInterceptor`; read on the server via a `ServerInterceptor` that stashes values into `Context`.

### 3.6 Interceptors

**Interceptors** are middleware: they wrap calls to implement cross-cutting concerns once instead of per-method.

- **Client interceptors** (`ClientInterceptor`): wrap each outbound `ClientCall`. Use for: injecting auth headers, propagating trace context, client-side metrics, retries.
- **Server interceptors** (`ServerInterceptor`): wrap each inbound `ServerCall`. Use for: authentication/authorization, request logging, rate limiting, populating `Context`, exception translation.

They compose in a chain; ordering matters (auth before business logic; metrics outermost to time everything). Internally an interceptor receives a `Listener` for inbound events (`onMessage`, `onHalfClose`, `onCancel`, `onComplete`) and a `Call` object to send outbound events — letting it observe and mutate the full lifecycle, including streaming events.

### 3.7 The error model (status codes)

gRPC does **not** use HTTP status codes for application errors. It defines a fixed set of **status codes** (an enum, 0–16), sent as the `grpc-status` trailer:

| Code | Name | Meaning | Retriable? |
|---|---|---|---|
| 0 | `OK` | Success | — |
| 1 | `CANCELLED` | Call cancelled (often by client) | No |
| 2 | `UNKNOWN` | Unknown error (uncaught exception, bad mapping) | Maybe |
| 3 | `INVALID_ARGUMENT` | Bad client input (independent of system state) | No |
| 4 | `DEADLINE_EXCEEDED` | Deadline elapsed before completion | Sometimes (idempotent) |
| 5 | `NOT_FOUND` | Entity not found | No |
| 6 | `ALREADY_EXISTS` | Entity already exists | No |
| 7 | `PERMISSION_DENIED` | Caller lacks permission (authenticated but unauthorized) | No |
| 8 | `RESOURCE_EXHAUSTED` | Quota/rate limit/out of space | Yes (backoff) |
| 9 | `FAILED_PRECONDITION` | System state wrong for op; don't retry as-is | No |
| 10 | `ABORTED` | Concurrency conflict (e.g., txn abort) | Yes (at higher level) |
| 11 | `OUT_OF_RANGE` | Past valid range | No |
| 12 | `UNIMPLEMENTED` | Method not implemented/supported | No |
| 13 | `INTERNAL` | Internal invariant broken | Maybe |
| 14 | `UNAVAILABLE` | Transient; service down/unreachable | Yes (backoff) |
| 15 | `DATA_LOSS` | Unrecoverable data loss/corruption | No |
| 16 | `UNAUTHENTICATED` | No/invalid credentials | No |

> **`FAILED_PRECONDITION` vs `ABORTED` vs `UNAVAILABLE`:** the canonical distinction — `FAILED_PRECONDITION` means "don't retry until you fix the system state"; `ABORTED` means "retry at a higher level (e.g., re-read then re-write)"; `UNAVAILABLE` means "retry this exact call with backoff." Getting these right drives correct client retry behavior.

**Rich errors:** the `google.rpc.Status` message and the `grpc-status-details-bin` trailer carry structured error details (e.g. `BadRequest`, `QuotaFailure`, `RetryInfo`) so clients get machine-readable specifics beyond the code + message string.

### 3.8 State machine of an RPC (server side)

```
                onHeaders            onMessage(s)         onHalfClose
  [stream open] ──────────▶ [reading] ──────────▶ [reading] ──────────▶ [client done sending]
                                                                              │
                                                          your handler runs   │
                                                                              ▼
                                                       sendHeaders → sendMessage(s) → close(status)
                                                                              │
                                                                              ▼
                                                                         [closed: trailers sent]

  At ANY point: onCancel (RST_STREAM / deadline) → [cancelled] → resources released.
```

For streaming, `onMessage` fires repeatedly; `onHalfClose` marks the client finished sending (still able to receive). For bidi, the server may send messages before, during, or after receiving client messages.

---

## 4. The complete toolkit

### 4.1 `protoc` and codegen

| Tool | Purpose | Key flags |
|---|---|---|
| `protoc` | The Protobuf compiler | `--java_out=`, `--proto_path=`/`-I`, `--descriptor_set_out=` |
| `protoc-gen-grpc-java` | gRPC service-stub plugin for Java | invoked via `--grpc-java_out=` |
| `protobuf-maven-plugin` / `protobuf-gradle-plugin` | Run codegen in the build, download protoc | bind to `generate-sources` |
| `buf` | Modern Protobuf toolchain: lint, breaking-change detection, BSR registry, codegen | `buf lint`, `buf breaking`, `buf generate` |
| `grpcurl` | cURL for gRPC; invoke methods from CLI | `-d '{json}'`, `-plaintext`, `list`, `describe` |
| `grpc_health_probe` | CLI for the standard health-checking service (Kubernetes probes) | `-addr`, `-service` |
| `ghz` | gRPC load-testing / benchmarking | `-c` concurrency, `-n` requests, `--rps` |

> **`buf`:** a widely adopted third-party toolchain that fixes Protobuf's historically painful build/UX. Its killer features are **lint** (style rules) and **breaking-change detection** (`buf breaking` compares your `.proto` against a baseline and fails CI if you, say, change a field type or reuse a tag — catching wire-incompatibility before it ships).

### 4.2 grpc-java server API (selected)

| Class / method | Purpose | Notable params / defaults |
|---|---|---|
| `ServerBuilder.forPort(int)` / `NettyServerBuilder` | Create a server | binds a port |
| `.addService(BindableService)` | Register a service impl | one per service |
| `.intercept(ServerInterceptor)` | Add server interceptor | order matters |
| `.executor(Executor)` | Thread pool for handlers | default: a cached thread pool |
| `.maxInboundMessageSize(int)` | Reject oversized messages | **default 4 MiB** |
| `.maxInboundMetadataSize(int)` | Limit header size | default 8 KiB |
| `.keepAliveTime/Timeout(...)` | HTTP/2 keepalive pings | see §7.3 |
| `.permitKeepAliveTime/WithoutCalls(...)` | Anti-abuse limits on client pings | server rejects too-frequent pings |
| `Server.start()` / `.awaitTermination()` / `.shutdown()` / `.shutdownNow()` | Lifecycle | graceful vs forced |

### 4.3 grpc-java client API (selected)

| Class / method | Purpose | Notable params / defaults |
|---|---|---|
| `ManagedChannelBuilder.forAddress(host,port)` / `forTarget("dns:///…")` | Build a channel | |
| `.usePlaintext()` | Disable TLS (dev only) | **insecure** |
| `.useTransportSecurity()` / `TlsChannelCredentials` | TLS | recommended |
| `.defaultLoadBalancingPolicy("round_robin")` | LB policy | default `pick_first` |
| `.keepAliveTime/Timeout(...)` | Connection keepalive | off by default |
| `.maxInboundMessageSize(int)` | Response size cap | default 4 MiB |
| `.enableRetry()` / `.defaultServiceConfig(map)` | Built-in retries/hedging | off by default |
| `.intercept(ClientInterceptor)` | Client middleware | |
| Stub `.withDeadlineAfter(n, unit)` | Per-call deadline | none by default — set it! |
| Stub `.withWaitForReady()` | Queue instead of fail-fast while connecting | default fail-fast |
| Stub `.withCompression("gzip")` | Per-call compression | off by default |
| `channel.shutdown().awaitTermination(...)` | Clean shutdown | drains in-flight |

### 4.4 Channel service config (JSON, runtime-tunable)

gRPC reads a **service config** (a JSON blob, often delivered via DNS TXT or set programmatically) controlling per-method behavior without recompiling:

```json
{
  "methodConfig": [{
    "name": [{"service": "myapp.user.v1.UserService", "method": "GetUser"}],
    "timeout": "1s",
    "retryPolicy": {
      "maxAttempts": 4,
      "initialBackoff": "0.1s",
      "maxBackoff": "1s",
      "backoffMultiplier": 2,
      "retryableStatusCodes": ["UNAVAILABLE"]
    }
  }],
  "loadBalancingConfig": [{"round_robin": {}}]
}
```

> **Hedging** (alternative to retry): send the same request to multiple backends after small delays and take the first response. Set via `hedgingPolicy`. Use only for **idempotent** methods — duplicate side effects otherwise.

---

## 5. Code examples by use case

All examples assume `grpc-java` with the `protobuf-gradle-plugin`, Java 17.

### 5.1 The `.proto` (shared contract)

```proto
syntax = "proto3";
package myapp.user.v1;
option java_package = "com.myapp.user.v1";
option java_multiple_files = true;

import "google/protobuf/timestamp.proto";

service UserService {
  rpc GetUser(GetUserRequest) returns (User);                       // unary
  rpc ListUsers(ListUsersRequest) returns (stream User);            // server streaming
  rpc BulkCreate(stream User) returns (BulkCreateSummary);          // client streaming
  rpc Chat(stream ChatMessage) returns (stream ChatMessage);        // bidi
}

message User {
  string id = 1;
  string email = 2;
  int32 age = 3;
  google.protobuf.Timestamp created_at = 4;
}
message GetUserRequest { string id = 1; }
message ListUsersRequest { int32 page_size = 1; string filter = 2; }
message BulkCreateSummary { int32 created = 1; int32 failed = 2; }
message ChatMessage { string from = 1; string text = 2; }
```

### 5.2 Use case A — Unary service + blocking client

```java
// SERVER: implement the generated UserServiceImplBase
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {
  private final UserRepository repo;
  public UserServiceImpl(UserRepository repo) { this.repo = repo; }

  @Override
  public void getUser(GetUserRequest req, StreamObserver<User> responseObserver) {
    // Respect cancellation/deadline early to avoid wasted work.
    if (Context.current().isCancelled()) {
      responseObserver.onError(Status.CANCELLED.withDescription("client gone").asRuntimeException());
      return;
    }
    var maybe = repo.findById(req.getId());
    if (maybe.isEmpty()) {
      // Map domain errors to gRPC status codes explicitly.
      responseObserver.onError(Status.NOT_FOUND
          .withDescription("user " + req.getId() + " not found")
          .asRuntimeException());
      return;
    }
    User u = maybe.get();
    responseObserver.onNext(u);          // single response
    responseObserver.onCompleted();      // sends OK trailer
  }
}

// Wire up and start the server
Server server = ServerBuilder.forPort(50051)
    .addService(new UserServiceImpl(repo))
    .maxInboundMessageSize(8 * 1024 * 1024)   // raise from default 4 MiB
    .build()
    .start();
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
  server.shutdown();                          // stop accepting, drain in-flight
  try { server.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
}));
server.awaitTermination();
```

```java
// CLIENT: one channel for the process, reused; per-call deadline.
ManagedChannel channel = ManagedChannelBuilder
    .forTarget("dns:///user-service:50051")
    .useTransportSecurity()                 // TLS in prod
    .build();
UserServiceGrpc.UserServiceBlockingStub stub = UserServiceGrpc.newBlockingStub(channel);

try {
  User u = stub.withDeadlineAfter(200, TimeUnit.MILLISECONDS)  // ALWAYS set a deadline
               .getUser(GetUserRequest.newBuilder().setId("42").build());
  System.out.println(u.getEmail());
} catch (StatusRuntimeException e) {
  Status.Code code = e.getStatus().getCode();
  if (code == Status.Code.NOT_FOUND) { /* handle 404-equivalent */ }
  else if (code == Status.Code.DEADLINE_EXCEEDED) { /* slow downstream */ }
  else { /* log & surface */ }
} finally {
  channel.shutdownNow();   // app exit only; do NOT churn channels per request
}
```

### 5.3 Use case B — Server streaming (live feed / large result set)

```java
// SERVER: emit many messages, then complete.
@Override
public void listUsers(ListUsersRequest req, StreamObserver<User> obs) {
  // ServerCallStreamObserver lets us honor backpressure & cancellation.
  var sco = (ServerCallStreamObserver<User>) obs;
  Iterator<User> it = repo.stream(req.getFilter());
  while (it.hasNext()) {
    if (sco.isCancelled()) return;        // client went away — stop producing
    if (!sco.isReady()) {                 // flow control: don't outrun the consumer
      // In a real impl, register sco.setOnReadyHandler and resume when ready,
      // rather than busy-waiting. Shown inline for brevity.
      while (!sco.isReady() && !sco.isCancelled()) Thread.onSpinWait();
    }
    obs.onNext(it.next());
  }
  obs.onCompleted();
}
```

```java
// CLIENT (blocking): a server-streaming call returns an Iterator.
Iterator<User> users = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
    .listUsers(ListUsersRequest.newBuilder().setFilter("active").build());
while (users.hasNext()) {
  User u = users.next();        // blocks until next message or end
  process(u);
}
```

### 5.4 Use case C — Client streaming (bulk ingestion)

```java
// SERVER: aggregate N messages into 1 summary.
@Override
public StreamObserver<User> bulkCreate(StreamObserver<BulkCreateSummary> resp) {
  return new StreamObserver<>() {
    int created = 0, failed = 0;
    @Override public void onNext(User u) {
      try { repo.insert(u); created++; } catch (Exception e) { failed++; }
    }
    @Override public void onError(Throwable t) {
      // client stream failed/cancelled; clean up. No response is expected.
    }
    @Override public void onCompleted() {                    // client half-closed
      resp.onNext(BulkCreateSummary.newBuilder()
          .setCreated(created).setFailed(failed).build());
      resp.onCompleted();
    }
  };
}
```

```java
// CLIENT (async): push messages, then half-close, await summary via latch.
CountDownLatch done = new CountDownLatch(1);
UserServiceGrpc.UserServiceStub async = UserServiceGrpc.newStub(channel);
StreamObserver<User> sink = async.bulkCreate(new StreamObserver<>() {
  @Override public void onNext(BulkCreateSummary s) {
    System.out.println("created=" + s.getCreated() + " failed=" + s.getFailed());
  }
  @Override public void onError(Throwable t) { done.countDown(); }
  @Override public void onCompleted() { done.countDown(); }
});
for (User u : usersToUpload) sink.onNext(u);
sink.onCompleted();                              // signal "done sending"
done.await(30, TimeUnit.SECONDS);
```

### 5.5 Use case D — Bidirectional streaming (chat)

```java
// SERVER: echo/broadcast; respond as messages arrive.
@Override
public StreamObserver<ChatMessage> chat(StreamObserver<ChatMessage> outbound) {
  return new StreamObserver<>() {
    @Override public void onNext(ChatMessage msg) {
      // Process and respond at any time — fully duplex.
      outbound.onNext(ChatMessage.newBuilder()
          .setFrom("server").setText("ack: " + msg.getText()).build());
    }
    @Override public void onError(Throwable t) { /* peer error */ }
    @Override public void onCompleted() { outbound.onCompleted(); }  // mirror close
  };
}
```

```java
// CLIENT: both directions live simultaneously.
StreamObserver<ChatMessage> tx = async.chat(new StreamObserver<>() {
  @Override public void onNext(ChatMessage m) { System.out.println(m.getFrom()+": "+m.getText()); }
  @Override public void onError(Throwable t) { /* ... */ }
  @Override public void onCompleted() { /* server closed */ }
});
tx.onNext(ChatMessage.newBuilder().setFrom("alice").setText("hello").build());
tx.onNext(ChatMessage.newBuilder().setFrom("alice").setText("bye").build());
tx.onCompleted();
```

### 5.6 Use case E — Auth via interceptors (metadata)

```java
// CLIENT interceptor: inject a bearer token on every outbound call.
static final Metadata.Key<String> AUTH =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

ClientInterceptor authInterceptor = new ClientInterceptor() {
  @Override public <Q, S> ClientCall<Q, S> interceptCall(
      MethodDescriptor<Q, S> m, CallOptions opts, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(m, opts)) {
      @Override public void start(Listener<S> l, Metadata headers) {
        headers.put(AUTH, "Bearer " + TokenProvider.current());
        super.start(l, headers);
      }
    };
  }
};
Channel secured = ClientInterceptors.intercept(channel, authInterceptor);
```

```java
// SERVER interceptor: validate token, stash principal into Context.
static final Context.Key<String> PRINCIPAL = Context.key("principal");
ServerInterceptor auth = new ServerInterceptor() {
  @Override public <Q, S> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
    String token = headers.get(AUTH);
    String principal = TokenVerifier.verify(token);   // throws on invalid
    if (principal == null) {
      call.close(Status.UNAUTHENTICATED.withDescription("bad token"), new Metadata());
      return new ServerCall.Listener<>() {};           // no-op listener
    }
    Context ctx = Context.current().withValue(PRINCIPAL, principal);
    return Contexts.interceptCall(ctx, call, headers, next);   // propagate via Context
  }
};
```

### 5.7 Use case F — Health checks + reflection (production essentials)

```java
// Standard gRPC health service (used by k8s grpc probes & LB)
HealthStatusManager health = new HealthStatusManager();
Server server = ServerBuilder.forPort(50051)
    .addService(new UserServiceImpl(repo))
    .addService(health.getHealthService())                 // grpc.health.v1.Health
    .addService(ProtoReflectionService.newInstance())      // lets grpcurl introspect
    .build().start();
health.setStatus("myapp.user.v1.UserService", ServingStatus.SERVING);
```

```bash
# Introspect & call with grpcurl (no .proto needed thanks to reflection)
grpcurl -plaintext localhost:50051 list
grpcurl -plaintext -d '{"id":"42"}' localhost:50051 myapp.user.v1.UserService/GetUser
grpc_health_probe -addr=localhost:50051 -service=myapp.user.v1.UserService
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Reuse channels.** A `ManagedChannel` is expensive (DNS, connections, LB state). Create one per backend per process; never per request. Stubs are cheap — derive freely.
- **Default max message size is 4 MiB.** Large payloads either fail (`RESOURCE_EXHAUSTED`) or should be **streamed** in chunks rather than sent as one message. Raise the limit deliberately; don't ship 100 MiB unary messages.
- **Compression** (`gzip`) trades CPU for bandwidth. Worth it for large, compressible payloads over slow links; harmful for tiny messages on fast LANs. Off by default.
- **Connection concurrency limit.** HTTP/2 caps concurrent streams per connection (`SETTINGS_MAX_CONCURRENT_STREAMS`, often ~100 in `grpc-java`). A single channel to one backend can bottleneck at high QPS — use `round_robin` over multiple subchannels, or multiple connections, for very high throughput.
- **Avoid blocking the I/O event loop.** Run handler logic on the `executor` thread pool, not Netty's event-loop threads; blocking there stalls all streams on that loop.
- **Protobuf is fast but not free.** Reuse builders, avoid needless copies; `ByteString` is immutable — beware large allocations.

### 6.2 Correctness & concurrency

- **`StreamObserver` is not thread-safe.** Calls to `onNext`/`onCompleted`/`onError` on a single observer must be serialized. For concurrent producers, synchronize or funnel through one thread.
- **Honor backpressure** in streaming: use `ServerCallStreamObserver`/`ClientCallStreamObserver` `isReady()` + `setOnReadyHandler` rather than blasting `onNext` and overflowing buffers (which causes unbounded memory growth).
- **Idempotency & retries.** gRPC built-in retries (`enableRetry` + service config) only re-send when no response bytes have arrived. Still, only mark non-idempotent methods retriable with care — design create operations to accept an idempotency key.
- **Set deadlines everywhere; propagate them.** Use gRPC `Context` so downstream calls inherit the shrinking budget.

### 6.3 Security

- **TLS by default.** `usePlaintext()` is dev-only. Use `TlsChannelCredentials`/`TlsServerCredentials`. For service-to-service, **mTLS** (mutual TLS — both client and server present certs) is standard, often handled by a service mesh (Istio/Linkerd) sidecar.
- **AuthN/AuthZ via interceptors + per-RPC credentials** (`CallCredentials`) for tokens. Don't hand-roll auth per method.
- **Limit inbound sizes** (`maxInboundMessageSize`, `maxInboundMetadataSize`) to resist memory-exhaustion DoS.
- **Validate inputs**; map bad input to `INVALID_ARGUMENT`, not `INTERNAL`.

### 6.4 Observability

- **Metrics:** use OpenTelemetry/Micrometer gRPC instrumentation: per-method QPS, latency histograms, status-code distribution, in-flight streams.
- **Tracing:** propagate W3C `traceparent` via metadata; OpenTelemetry interceptors do this automatically.
- **Logging:** a server interceptor logging method, status, duration, peer. Enable channelz (`grpc.channelz`) and `GRPC_TRACE`/`GRPC_VERBOSITY` env vars for deep debugging.
- **Health:** expose `grpc.health.v1.Health` for orchestrators and L7 LBs.

### 6.5 Cost, testing, hardening

- **Testing:** `grpc-testing` provides `GrpcCleanupRule` and **in-process transport** (`InProcessServerBuilder`/`InProcessChannelBuilder`) — wire a real server+client with no sockets for fast, deterministic tests. Generate mock stubs for client-side unit tests.
- **Production hardening:** graceful shutdown (`shutdown()` then `awaitTermination`), keepalive tuned, connection-age limits (`maxConnectionAge`) so connections rebalance across new backends, health-based load shedding.
- **Schema governance:** `buf breaking` in CI; version packages (`myapp.user.v1`); never reuse tags; `reserved` retired ones.

### 6.6 Anti-patterns

| Anti-pattern | Why it hurts | Fix |
|---|---|---|
| Channel per request | Connection storm, latency spikes | One shared, long-lived channel |
| No deadlines | Resource-exhaustion cascades | `withDeadlineAfter` + propagation |
| L4 load balancing of gRPC | All RPCs pin to one backend (see §8) | L7 LB / client-side `round_robin` |
| Huge unary messages | Hits 4 MiB cap; GC pressure | Stream in chunks |
| Reusing/renumbering proto tags | Silent wire corruption | `reserved`; `buf breaking` |
| Returning `INTERNAL`/`UNKNOWN` for client errors | Clients retry pointlessly | Map to `INVALID_ARGUMENT`/`NOT_FOUND` etc. |
| Blocking on the event loop | Stalls all streams | Use the handler executor |
| Ignoring `isReady()` in streaming | Unbounded buffering, OOM | Flow-control-aware producers |

---

## 7. Advanced topics & deep internals

### 7.1 gRPC load balancing — and why L4 fails

This is one of the most important production topics, and a favorite interview question.

> **L4 (transport layer) load balancing:** distributes **TCP connections** across backends. The LB sees only sockets, not the application protocol. **L7 (application layer):** understands HTTP/2 and can distribute individual **requests/streams**.

**The problem:** gRPC multiplexes **many** RPCs over **one long-lived** HTTP/2 connection. An L4 LB load-balances at *connection* establishment. Once a client opens one connection to backend B, all its RPCs ride that connection — so an L4 LB pins all of a client's traffic to a single backend, defeating the purpose. Add long-lived connections (clients rarely reconnect) and you get severe imbalance: some backends idle, others saturated. **L4 LB + gRPC = broken balancing.**

**Solutions:**

1. **Client-side load balancing (the gRPC-native answer).** The client resolves *all* backend addresses (via DNS or a service discovery resolver), opens a subchannel to each, and the `round_robin` (or `weighted_round_robin`, `pick_first`) policy distributes RPCs across them. No proxy needed; balancing happens per-RPC at the source.
   ```java
   ManagedChannelBuilder.forTarget("dns:///user-service:50051")
       .defaultLoadBalancingPolicy("round_robin")
       .build();   // resolves all A records, balances RPCs across them
   ```
2. **L7 proxy (Envoy, gRPC-aware ingress, Linkerd).** A proxy that speaks HTTP/2 terminates streams and re-balances individual requests across backends. This is what service meshes do.
3. **Look-aside / xDS load balancing.** A control plane (the **xDS** protocol used by Envoy/Istio, delivered over gRPC itself) pushes endpoint and policy data to clients, enabling sophisticated balancing, locality awareness, and outlier detection. `xds:///` target scheme in grpc-java.

> **xDS:** the family of discovery APIs (LDS/RDS/CDS/EDS) Envoy popularized for dynamically configuring listeners, routes, clusters, and endpoints. gRPC clients can act as xDS clients, getting endpoints and LB policy from a control plane like Istio — "proxyless service mesh."

### 7.2 Name resolution & service config delivery

The channel resolves the target via a `NameResolver` keyed by scheme (`dns:`, `xds:`, custom). DNS resolution returns addresses *and* can return a **service config** via a TXT record (`_grpc_config.<host>`), letting ops change timeouts/retries/LB policy centrally without redeploying clients. Re-resolution happens on connection failure and periodically.

### 7.3 Keepalive, idle, and connection management

- **HTTP/2 keepalive PINGs** detect dead connections (e.g., silent NAT timeouts). `keepAliveTime`/`keepAliveTimeout` on both sides. Servers enforce `permitKeepAliveTime`/`permitKeepAliveWithoutCalls` to stop abusive clients pinging too often (which itself causes `ENHANCE_YOUR_CALM`/GOAWAY).
- **`maxConnectionAge`/`maxConnectionAgeGrace`** (server) periodically recycle connections via GOAWAY so clients re-resolve and rebalance onto newly added backends — crucial in autoscaling environments.
- **`GOAWAY` frame:** the graceful "stop using this connection" signal; in-flight streams below a threshold finish, new ones go to a fresh connection.
- **Idle timeout:** channels transition to IDLE after inactivity, dropping connections to save resources; the next RPC re-CONNECTs.

### 7.4 gRPC-Web and gateways

**Browsers cannot speak native gRPC.** The browser Fetch/XHR APIs cannot control HTTP/2 framing or read **trailers** — but gRPC puts its status in trailers. Hence:

> **gRPC-Web:** a protocol variant where trailers are encoded *into the response body* (a special trailer frame) so a JavaScript client can parse status without HTTP/2 trailers. It also works over HTTP/1.1. A **proxy** (Envoy's `grpc_web` filter, or the standalone `grpcwebproxy`) translates between browser-facing gRPC-Web and backend native gRPC.

```
Browser ──(gRPC-Web over HTTP/1.1 or 2)──▶ Envoy (grpc_web filter) ──(native gRPC/HTTP-2)──▶ Backend
```

- **gRPC-Web limitations:** **no client streaming and no bidirectional streaming** (server streaming is supported in newer implementations). Unary and server-streaming only.
- **`grpc-gateway`** (Go ecosystem): a reverse-proxy generator that exposes a **RESTful JSON/HTTP-1.1 API** in front of a gRPC service, driven by `google.api.http` annotations in the `.proto`. This lets you serve both gRPC (internal) and REST/JSON (external, browser/3rd-party) from one contract. Java equivalents exist (e.g., transcoding in Envoy via `grpc_json_transcoder`).

> **Transcoding:** automatically converting JSON/HTTP requests into gRPC calls and back, based on `.proto` HTTP annotations — so one service definition serves both REST clients and gRPC clients.

### 7.5 Compression, codecs, and message framing details

The 5-byte message prefix's first byte is the **compressed-message flag**. Compression is negotiated per-message; `grpc-encoding`/`grpc-accept-encoding` headers advertise codecs (`identity`, `gzip`). A **decompression bomb** guard exists (`maxInboundMessageSize` applies to the *decompressed* size).

### 7.6 Lesser-known behaviors

- **Wait-for-ready** changes failure semantics: instead of failing fast when no connection is READY, the RPC queues until the channel connects or the deadline fires. Use for resilience to transient unavailability; combine with deadlines so it can't hang forever.
- **Unknown-field preservation** (proto3) means a proxy or relay can forward messages with fields it doesn't know about — enabling rolling upgrades.
- **`Any` type** lets you embed arbitrary messages with a type URL; powerful but it defeats static typing — use sparingly (e.g., generic event envelopes).
- **`grpc-status` can arrive in HEADERS, not trailers** ("Trailers-Only" response) when the server fails before sending any message — clients must handle both.

---

## 8. Tradeoffs & decision frameworks

### 8.1 gRPC vs REST vs GraphQL

| Dimension | gRPC | REST (JSON/HTTP) | GraphQL |
|---|---|---|---|
| Transport | HTTP/2 (HTTP/3 emerging) | HTTP/1.1 or 2 | Usually HTTP/1.1 over POST |
| Payload | Protobuf (binary, compact) | JSON (text, verbose) | JSON |
| Contract | `.proto`, compiler-enforced | OpenAPI (optional), often informal | SDL schema, enforced |
| Codegen | First-class, multi-language | Optional (OpenAPI generators) | Strong (typed clients) |
| Streaming | Native (4 types incl. bidi) | SSE/WebSocket bolt-ons | Subscriptions (over WS) |
| Browser support | Needs gRPC-Web + proxy | Native | Native |
| Human-debuggable | No (binary) | Yes (cURL/JSON) | Mostly |
| Over/under-fetching | Fixed per method | Common problem | Solved (client picks fields) |
| Best for | Internal microservices, low-latency, polyglot, streaming | Public APIs, CRUD, broad reach | Aggregating many sources for varied clients (mobile/web) |
| Caching | Hard (POST, binary) | Easy (HTTP caching, GET) | Hard |
| Latency/CPU | Low | Higher (JSON parse, headers) | Variable |

**Decision rules:**

- **Use gRPC when:** internal service-to-service, polyglot, latency/throughput-critical, you need streaming, you want a compiler-enforced contract.
- **Use REST when:** public/partner APIs, browser/cURL consumers, CRUD where ubiquity and HTTP caching matter, simple integrations.
- **Use GraphQL when:** clients (esp. mobile/web BFFs) need to shape responses across many backends, over/under-fetching is a real pain, and you control the client.
- **Combine:** gRPC internally + REST/GraphQL at the edge (via transcoding or a BFF) is a very common, healthy architecture.

### 8.2 gRPC vs other RPC/messaging

| Option | When to prefer over gRPC |
|---|---|
| **Apache Thrift** | Legacy ecosystems already on Thrift; broad transport choices |
| **REST/JSON** | Public reach, human-debuggability |
| **Message queue (Kafka/RabbitMQ)** | Async, decoupled, durable, fan-out, event-driven — not request/response |
| **WebSocket** | Browser-native full-duplex without a proxy |
| **GraphQL** | Client-driven field selection across aggregated sources |

gRPC is **synchronous request/response (with streaming)**, point-to-point. For **asynchronous, durable, decoupled** messaging, reach for a broker instead.

### 8.3 Streaming type selection

| Need | Type |
|---|---|
| One in, one out | Unary |
| One request, many results (feed, download, LLM tokens) | Server streaming |
| Many inputs, one summary (upload, ingest) | Client streaming |
| Continuous duplex (chat, control plane, telemetry+backpressure) | Bidirectional |

---

## 9. Failure modes & debugging

### 9.1 Common production failures and diagnosis

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| `UNAVAILABLE` storms | Backend down, connection refused, DNS stale, GOAWAY churn | channelz, `GRPC_TRACE=connectivity_state`, server logs | Retries w/ backoff, health checks, re-resolution |
| `DEADLINE_EXCEEDED` | Slow downstream, deadline too tight, no propagation | distributed traces, per-method latency histograms | Tune deadlines, fix slow dependency, propagate budget |
| Load imbalance (one backend hot) | L4 LB + long-lived connections | backend QPS metrics per pod | `round_robin` client LB / L7 proxy / `maxConnectionAge` |
| `RESOURCE_EXHAUSTED` | Message > 4 MiB, or rate limit/quota | server logs, message size metrics | Stream/chunk; raise limit; backoff |
| `UNIMPLEMENTED` | Method/service not registered, version mismatch, wrong path | `grpcurl list`/`describe`, reflection | Deploy correct version; check proto package/version |
| OOM under streaming load | Ignoring flow control (`isReady`), buffering | heap dump, GC logs | Flow-control-aware producers, bounded queues |
| `UNAUTHENTICATED`/`PERMISSION_DENIED` | Token missing/expired/wrong audience; mTLS cert issue | interceptor logs, cert inspection | Fix creds/cert rotation |
| Hangs / no timeout | No deadline + wait-for-ready | thread dumps (threads stuck in `awaitTermination`) | Always set deadlines |
| `ENHANCE_YOUR_CALM` / GOAWAY on keepalive | Client pings too aggressively | server logs | Tune `keepAliveTime` ≥ server `permitKeepAliveTime` |
| Intermittent corruption / wrong fields | Reused/renumbered proto tag | `buf breaking`, schema diff | Restore tags; `reserved` |

### 9.2 Tools & commands

```bash
# Verbose gRPC tracing (grpc-java reads java logging; C-core uses these env vars)
export GRPC_VERBOSITY=DEBUG
export GRPC_TRACE=all                 # or: connectivity_state,http,subchannel

# Inspect a live service
grpcurl -plaintext host:50051 list
grpcurl -plaintext host:50051 describe myapp.user.v1.UserService
grpcurl -plaintext -d '{"id":"42"}' host:50051 myapp.user.v1.UserService/GetUser

# Load test
ghz --insecure --proto user.proto --call myapp.user.v1.UserService.GetUser \
    -d '{"id":"42"}' -c 50 -n 100000 host:50051

# Health probe (k8s)
grpc_health_probe -addr=host:50051

# Wire capture: Wireshark has native gRPC/Protobuf dissectors (load the .proto)
```

- **channelz** (`grpc.channelz.v1.Channelz`): a built-in service exposing live channel/subchannel/socket/server stats — invaluable for "why is balancing wrong" or "is this connection READY."
- **In-process transport** for reproducing logic bugs deterministically in tests.

### 9.3 Real-world incident patterns

- **The "all traffic to one pod" outage:** team puts a gRPC service behind a TCP (L4) cloud load balancer. Under autoscaling, existing clients keep their old connections; new pods receive almost no traffic; old pods overload. *Lesson:* gRPC needs L7/client-side LB, plus `maxConnectionAge` so connections recycle and rebalance.
- **The deadline-less cascade:** one slow database call holds a server thread; with no deadlines, upstream callers' threads pile up waiting, pools exhaust across the mesh, and a single slow dependency takes down the whole request path. *Lesson:* deadlines everywhere, propagated via `Context`.
- **The 4 MiB surprise:** a feature ships sending an image as a unary `bytes` field; works in dev with small images, fails in prod with `RESOURCE_EXHAUSTED` on a 6 MiB upload. *Lesson:* know the default limit; stream large payloads.
- **The tag-reuse data bug:** a dev deletes field `3` and adds a new field reusing tag `3` with a different type; old clients send the old field, new server reads it as the new type — silent garbage. *Lesson:* `reserved` retired tags; CI `buf breaking`.

---

## 10. Interview drill

**Q1. What is gRPC and what are its two foundational technologies?**
*Model answer:* gRPC is a high-performance RPC framework. Its two pillars are **Protocol Buffers** (a binary IDL + serialization format that generates typed client/server code from a `.proto` contract) and **HTTP/2** (the transport, providing multiplexed streams, binary framing, header compression, and flow control). Each RPC maps to one HTTP/2 stream.
- *Probe: Why HTTP/2 specifically?* → Multiplexing many RPCs over one connection (no head-of-line blocking at the HTTP layer), and **trailers** to carry the final status after the body — HTTP/1.1 lacks reliable trailers.
- *Probe: Why is Protobuf compact?* → Fields are tagged with small integers, not names; varint encoding shrinks small integers; the format isn't self-describing, so no schema metadata travels on the wire.
- *Probe: Downside of binary?* → Not human-readable; you need tooling (`grpcurl`, Wireshark dissectors) to debug.

**Q2. Explain the four call types and give a real use case for each.**
*Model answer:* Unary (1→1, e.g., `GetUser`); server streaming (1→N, e.g., live feed / LLM token streaming / large downloads); client streaming (N→1, e.g., bulk upload / metrics ingestion aggregated to a summary); bidirectional (N↔M, e.g., chat / control channel). All ride one HTTP/2 stream; they differ only in message cardinality per direction.
- *Probe: Does bidi mean request-then-response?* → No; both directions are independent and fully interleaved until either half-closes.
- *Probe: Which can gRPC-Web do?* → Unary and (newer) server streaming only; no client or bidi streaming in browsers.

**Q3. Walk through what happens on the wire for a unary call.**
*Model answer:* Client opens an HTTP/2 stream, sends HEADERS (`:path` = `/pkg.Service/Method`, `content-type: application/grpc`, `te: trailers`, `grpc-timeout`, custom metadata), then DATA frames with a 5-byte-length-prefixed Protobuf message, then END_STREAM. Server routes by `:path`, decodes, runs the handler, sends response HEADERS + DATA + **trailing HEADERS** with `grpc-status`. HTTP status is 200 even on app errors; the gRPC status lives in the trailer.

**Q4. How does gRPC's error model work and why not just use HTTP status codes?**
*Model answer:* gRPC defines 17 status codes (0=OK … 16=UNAUTHENTICATED) sent in the `grpc-status` trailer, plus an optional structured `google.rpc.Status` in `grpc-status-details-bin`. HTTP codes are too coarse and the transport-level success (200) is independent of the application result; gRPC's codes carry retriability and precise semantics (`FAILED_PRECONDITION` vs `ABORTED` vs `UNAVAILABLE`).
- *Probe: Which codes are retriable?* → `UNAVAILABLE`, `RESOURCE_EXHAUSTED` (with backoff), `ABORTED` (at a higher level), `DEADLINE_EXCEEDED` only if idempotent. Never blindly retry `INVALID_ARGUMENT`/`NOT_FOUND`/`FAILED_PRECONDITION`.

**Q5 (senior signal). Why does L4 load balancing break gRPC, and what do you do instead?**
*Model answer:* gRPC multiplexes many RPCs over a single long-lived HTTP/2 connection. An L4 LB balances at connection setup, so once a client picks backend B, all its RPCs pin to B — leading to severe imbalance, especially with autoscaling (old clients keep old connections; new pods get nothing). Fixes: **client-side LB** (`round_robin` over all resolved addresses), an **L7 proxy** (Envoy) that re-balances per request, or **xDS** for control-plane-driven balancing. Also set `maxConnectionAge` so connections recycle and rebalance onto new backends.
- *Probe: What does `round_robin` need from the resolver?* → All backend addresses (e.g., DNS returning all A records, or a headless k8s Service), so the channel opens a subchannel per backend.

**Q6 (senior signal). How do deadlines differ from timeouts, and why do they matter at scale?**
*Model answer:* A timeout is local ("fail this call after X"); a **deadline** is an absolute time that **propagates** through the call chain so downstream services see the *remaining* budget. Without deadlines, a slow dependency holds threads on every upstream caller, exhausting pools and cascading into a mesh-wide outage. With propagated deadlines, work stops everywhere when the budget expires.
- *Probe: How does propagation work in grpc-java?* → Via the gRPC `Context`, which carries the deadline and cancellation; downstream stubs derive their `grpc-timeout` from it.

**Q7 (senior signal). gRPC vs REST vs GraphQL — when do you choose each?**
*Model answer:* gRPC for internal, latency-sensitive, polyglot, streaming, contract-enforced service-to-service traffic. REST for public/partner APIs, browser/cURL consumers, and HTTP-cacheable CRUD. GraphQL for client-driven field selection across aggregated backends (mobile/web BFF). A mature architecture often uses gRPC internally with REST/GraphQL or transcoding at the edge.

**Q8. What is the default max message size, and how do you handle large payloads?**
*Model answer:* 4 MiB inbound by default in grpc-java. Either raise `maxInboundMessageSize` deliberately or, better, **stream** the payload in chunks (server/client streaming) rather than sending one giant unary message; the size limit applies to the decompressed message.

**Q9. What are interceptors and metadata used for?**
*Model answer:* **Metadata** is key/value pairs sent as HTTP/2 headers/trailers (auth tokens, request IDs, tracing context; `-bin` suffix for binary). **Interceptors** are middleware wrapping each call — client-side for injecting auth/trace headers and metrics; server-side for authn/authz, logging, rate limiting, populating `Context`. They centralize cross-cutting concerns instead of repeating them per method.

**Q10. Why can't browsers speak native gRPC, and what's the workaround?**
*Model answer:* Browser HTTP APIs can't control HTTP/2 framing or read trailers, where gRPC puts its status. **gRPC-Web** encodes trailers into the response body and a **proxy** (Envoy `grpc_web` filter / `grpcwebproxy`) translates to native gRPC backends. gRPC-Web supports unary and server streaming, not client/bidi.

**Q11. How would you make a gRPC service production-ready?**
*Model answer:* TLS/mTLS; deadlines everywhere with propagation; client-side or L7 LB + `maxConnectionAge`; health service for orchestrator probes; OpenTelemetry metrics/tracing; graceful shutdown; bounded message/metadata sizes; flow-control-aware streaming; service-config-driven retries (idempotent only); schema governance with `buf breaking` in CI; channelz for live introspection.

**Q12 (senior signal). What goes wrong if you create a channel per request?**
*Model answer:* Channels are expensive — DNS resolution, TCP+TLS handshakes, LB state, keepalive. Per-request channels cause connection storms, latency spikes, FD exhaustion, and break load-balancing/connection-reuse benefits of HTTP/2 multiplexing. Create one long-lived channel per backend per process and share it (thread-safe); derive cheap per-call stubs.

---

## 11. Glossary

- **ABORTED:** Status 10; concurrency conflict (e.g., transaction abort); retry at a higher level.
- **Backpressure:** mechanism by which a slow consumer signals a fast producer to slow down; gRPC uses HTTP/2 flow control plus `isReady()`.
- **Bidirectional streaming:** RPC where both client and server send independent message streams over one stream, interleaved freely.
- **`buf`:** modern Protobuf toolchain (lint, breaking-change detection, codegen, schema registry).
- **`ByteString`:** Protobuf's immutable byte-array type in Java.
- **CallCredentials:** per-RPC credentials (e.g., a bearer token) attached to a call.
- **Channel (`ManagedChannel`):** client-side virtual connection to a logical endpoint; owns resolution, subchannels, LB, connectivity state.
- **channelz:** built-in introspection service exposing live channel/socket/server stats.
- **CNCF:** Cloud Native Computing Foundation; open-source home of gRPC, Kubernetes, Envoy.
- **Context (gRPC):** request-scoped carrier of deadline, cancellation, and values that propagates across thread boundaries.
- **Deadline:** absolute time by which an RPC must complete; propagates across calls.
- **DEADLINE_EXCEEDED:** Status 4; the deadline elapsed.
- **Flow control:** HTTP/2 credit-based backpressure (per-stream and per-connection).
- **Frame:** smallest HTTP/2 unit (HEADERS, DATA, RST_STREAM, SETTINGS, WINDOW_UPDATE, GOAWAY, PING).
- **GOAWAY:** HTTP/2 frame telling a peer to stop opening new streams on a connection (graceful drain).
- **gRPC-Web:** protocol variant + proxy enabling browsers to call gRPC (trailers in body; unary + server streaming).
- **grpc-gateway / transcoding:** reverse proxy exposing a REST/JSON API in front of gRPC via proto HTTP annotations.
- **grpcurl:** CLI to introspect and invoke gRPC methods.
- **HPACK:** HTTP/2 header compression.
- **HTTP/2:** binary, multiplexed revision of HTTP; gRPC's transport.
- **Hedging:** sending duplicate requests to multiple backends and taking the first response (idempotent only).
- **IDL:** Interface Definition Language; here, Protobuf.
- **Interceptor:** middleware wrapping client or server calls for cross-cutting concerns.
- **L4 / L7 load balancing:** connection-level (transport) vs request-level (application) balancing.
- **Marshalling / serialization:** converting objects to bytes (and back: unmarshalling).
- **Metadata:** gRPC key/value pairs sent as HTTP/2 headers/trailers.
- **MethodDescriptor:** generated object carrying a method's full name, type, and marshallers.
- **mTLS:** mutual TLS; both client and server authenticate with certificates.
- **`oneof`:** Protobuf union — at most one field set at a time.
- **pick_first / round_robin:** LB policies; defaults and the common multi-backend choice.
- **Protocol Buffers (Protobuf):** Google's binary IDL + serialization format.
- **RESOURCE_EXHAUSTED:** Status 8; quota/rate/size limit hit; retry with backoff.
- **RPC:** Remote Procedure Call; invoking a remote function as if local.
- **Reserved (proto):** marking retired field tags/names so they can't be reused.
- **RST_STREAM:** HTTP/2 frame abruptly terminating a stream (used for cancellation).
- **Service config:** runtime JSON controlling per-method timeouts/retries/LB, deliverable via DNS.
- **Status code:** gRPC's 0–16 error enum sent in the `grpc-status` trailer.
- **Stream (HTTP/2):** independent bidirectional frame sequence; one per RPC.
- **StreamObserver:** Java callback interface for sending/receiving streamed messages (not thread-safe).
- **Stub:** generated client object exposing typed methods (blocking, async, future flavors).
- **Subchannel:** internal connection to a single backend address.
- **Trailers:** HTTP/2 headers sent after the body; carry `grpc-status`.
- **UNAVAILABLE:** Status 14; transient; retry with backoff.
- **UNIMPLEMENTED:** Status 12; method/service not implemented.
- **Varint:** variable-length integer encoding; small numbers use fewer bytes.
- **wait-for-ready:** option to queue an RPC until the channel connects rather than failing fast.
- **xDS:** dynamic config/discovery API family (LDS/RDS/CDS/EDS) for endpoints and LB policy.
- **ZigZag:** signed-integer encoding (`sint*`) keeping small negatives small.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **gRPC = Protobuf (binary IDL + codegen) over HTTP/2 (multiplexed streams, trailers).** One RPC = one HTTP/2 stream.
- **Four call types:** unary (1→1), server streaming (1→N), client streaming (N→1), bidi (N↔M).
- **Message framing:** 1-byte compressed flag + 4-byte length + Protobuf bytes. Status lives in **trailers** (`grpc-status`), HTTP status is usually 200.
- **Status codes:** 0 OK, 3 INVALID_ARGUMENT, 4 DEADLINE_EXCEEDED, 5 NOT_FOUND, 7 PERMISSION_DENIED, 8 RESOURCE_EXHAUSTED, 9 FAILED_PRECONDITION, 10 ABORTED, 14 UNAVAILABLE, 16 UNAUTHENTICATED. Retriable: UNAVAILABLE, RESOURCE_EXHAUSTED, ABORTED(higher level), DEADLINE_EXCEEDED(idempotent).
- **Key defaults:** max inbound message **4 MiB**; default LB **pick_first**; deadlines **none** (set them!); compression **off**; retries **off** (`enableRetry`); plaintext insecure.
- **Channels long-lived & shared; stubs cheap & derived.** Never per-request channels.
- **Deadlines always, propagated via `Context`.** Prevents cascading exhaustion.
- **L4 LB breaks gRPC** (pins to one backend) → use `round_robin` client LB, L7 proxy/Envoy, or xDS; recycle with `maxConnectionAge`.
- **Browsers need gRPC-Web + proxy** (unary + server streaming only). REST edge via grpc-gateway/transcoding.
- **Tooling:** `protoc`/`buf` (lint + breaking), `grpcurl`, `ghz`, `grpc_health_probe`, channelz, OpenTelemetry interceptors, in-process transport for tests.
- **Proto rules:** never reuse/renumber tags; `reserved` retired ones; proto3 has no `required`; zero value is enum default.
- **gRPC vs REST vs GraphQL:** internal/low-latency/polyglot/streaming → gRPC; public/cacheable/browser → REST; client-driven field selection → GraphQL.

### Self-test (no answers — active recall)

1. Trace, frame by frame, what crosses the wire for a server-streaming RPC from the client opening the stream to the server sending its final status. Where exactly does `grpc-status` appear, and how does that differ if the server errors before sending any message?
2. You deploy a gRPC service behind a cloud TCP load balancer and notice one pod at 90% CPU while three sit idle. Explain the mechanism causing this and give three distinct fixes with their tradeoffs.
3. A teammate proposes marking your `CreateOrder` RPC retriable on `UNAVAILABLE` in the service config. What must be true for this to be safe, and how would you design the method to make it so?
4. Distinguish `FAILED_PRECONDITION`, `ABORTED`, and `UNAVAILABLE` with a concrete scenario for each and the correct client behavior.
5. Your streaming ingestion service OOMs under load. Walk through how HTTP/2 flow control and `StreamObserver.isReady()` should have prevented it, and what your producer code did wrong.
6. Why can't a browser call a native gRPC service directly, and what precisely does a gRPC-Web proxy change about the response so the browser can read the status?
7. Explain why renumbering a Protobuf field tag is more dangerous than renaming the field, in terms of the wire format.

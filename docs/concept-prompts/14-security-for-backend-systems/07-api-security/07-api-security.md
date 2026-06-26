# API Security

> A definitive engineering-handbook chapter for senior Java/JVM backend developers. From first principles to deep internals, with idiomatic code, production hardening, and an interview drill.

---

## 1. Overview & where it fits

### What it is

**API security** is the discipline of protecting *programmatic* interfaces — the HTTP(S)/JSON, gRPC, GraphQL, or webhook endpoints your backend exposes to clients (browsers, mobile apps, partner servers, internal microservices, automated bots) — from misuse, abuse, and attack. It spans **who can call you** (authentication), **what they are allowed to do** (authorization), **what data they may send and receive** (validation, encoding, exposure control), **how fast and how much** (rate limiting, quotas), and **the perimeter controls** that sit in front of your code (API gateway, WAF, bot mitigation).

It is distinct from — but overlaps with — classic web app security. A traditional web app renders HTML to a human in a browser with cookies and a server-rendered session. An API is consumed by *machines and code*: tokens instead of cookies, JSON instead of HTML, fine-grained object-level access instead of page-level access, and far higher request volumes. Many of the worst breaches of the last decade were **API breaches**, and the failure pattern is remarkably consistent: the *transport* was encrypted, the *authentication* worked, and the application *still* handed an attacker someone else's data because it never checked **"is this caller allowed to touch *this specific object*?"**

> **Adjacent term — API (Application Programming Interface).** A contract that lets one piece of software call another. In backend security we almost always mean a *network-exposed* API: a set of endpoints (URLs + methods) that accept structured requests and return structured responses, usually over HTTP.

### The problem it solves

Without API security, any of the following becomes trivial for an attacker:

- **Impersonation** — calling your API as if they were a legitimate, authenticated user (broken authentication).
- **Lateral data theft** — authenticating as user A and reading/modifying user B's records by changing an ID in the request (BOLA/IDOR — the single most common and most damaging API flaw).
- **Privilege escalation** — a normal user invoking admin-only functions (broken function-level authorization).
- **Injection** — smuggling SQL, OS commands, or markup through unvalidated input.
- **Mass assignment** — flipping a field like `"isAdmin": true` or `"balance": 1000000` that the API blindly binds to your domain object.
- **Excessive data exposure** — the API returns the full object (including `passwordHash`, `ssn`, internal flags) and trusts the client to hide it.
- **Resource exhaustion / abuse** — scraping, credential stuffing, denial-of-wallet (running up your cloud bill), and brute force, all enabled by missing rate limits and quotas.

### When you reach for it

Always. Concretely you make explicit API-security decisions whenever you:

- Expose a new endpoint (public, partner, or internal).
- Add a field to a request/response DTO.
- Introduce a resource that has an owner (an account, an order, a document, a tenant).
- Onboard a new client type (mobile, SPA, server-to-server, third-party).
- Put a service on the network at all — "internal-only" is not a security boundary by itself.

### The one-paragraph mental model

> Treat **every request as hostile and every client as an attacker who has read your code.** Authenticate the caller (prove identity), authorize *both* the *function* they're calling *and* the *specific object* they're touching (BOLA is where APIs die), validate every byte of input against a strict allow-list, return only the fields that caller is entitled to, encode output for its sink, and meter the whole thing with rate limits and quotas. Push coarse controls (TLS, IP reputation, WAF, global rate limits, schema checks) to the **edge** (gateway/WAF) for defense-in-depth, but enforce the *fine-grained, business-aware* checks (object ownership, field-level entitlement) **inside the application**, because only the application knows what "owns" means.

### The OWASP API Security Top 10 (your map of the territory)

The Open Worldwide Application Security Project (OWASP) publishes a ranked list of the most critical API risks. The current edition is **OWASP API Security Top 10 — 2023**. Memorize it; interviewers and incident reviews both use it as a checklist.

| Rank | ID | Name | One-line essence |
|------|-----|------|------------------|
| 1 | API1 | Broken Object Level Authorization (BOLA/IDOR) | Caller reaches an object they don't own by guessing/changing its ID. |
| 2 | API2 | Broken Authentication | Weak/missing token validation, no rate limit on login, JWT mistakes. |
| 3 | API3 | Broken Object **Property** Level Authorization | Mass assignment (write) + excessive data exposure (read) merged into one. |
| 4 | API4 | Unrestricted Resource Consumption | No rate limit/quota → DoS, denial-of-wallet, scraping. |
| 5 | API5 | Broken Function Level Authorization | Normal user calls admin-only function. |
| 6 | API6 | Unrestricted Access to Sensitive Business Flows | Bots abuse a legit flow (buy all tickets, scrape inventory). |
| 7 | API7 | Server Side Request Forgery (SSRF) | API fetches an attacker-supplied URL → hits internal services/metadata. |
| 8 | API8 | Security Misconfiguration | Missing headers, verbose errors, open CORS, default creds. |
| 9 | API9 | Improper Inventory Management | Forgotten/shadow/zombie endpoints, undocumented versions. |
| 10 | API10 | Unsafe Consumption of APIs | Trusting third-party API responses blindly. |

> **Adjacent term — OWASP.** A non-profit that produces free security guidance. Two famous lists: the original **OWASP Top 10** (web apps) and the **OWASP API Security Top 10** (APIs). They are *different* lists; for backend APIs, use the API one.

---

## 2. Foundations from first principles

We build the vocabulary bottom-up. If you already know a term, skim; the inline definitions exist so a sharp newcomer never gets lost.

### 2.1 Identity, authentication, authorization, auditing (AAA)

- **Identity** — *who someone claims to be* (a user ID, a service account, a client ID). An identity by itself is just an assertion.
- **Authentication (authN)** — *proving* that claimed identity. "You say you're Alice; show me your credential." Credentials: a password, a private key, a signed token, a client certificate.
- **Authorization (authZ)** — given a proven identity, *deciding what they may do*. "Alice is authenticated; may she DELETE order 42?"
- **Auditing/Accounting** — recording *what they did*, for forensics and compliance.

> The single most important sentence in this chapter: **AuthN is not authZ.** Knowing *who* the caller is tells you *nothing* about whether they may touch a given object. BOLA happens precisely when engineers conflate the two — "the request has a valid token, so it's fine."

### 2.2 The two big authorization granularities

1. **Function-level authorization** — "may this caller invoke this *operation/endpoint* at all?" (e.g., only admins may `POST /admin/users`). Coarse, role-based, easy. OWASP **API5**.
2. **Object-level authorization (BOLA)** — "may this caller act on this *specific instance*?" (e.g., Alice may `GET /orders/{id}` only for orders Alice owns). Fine, data-dependent, easy to forget. OWASP **API1**.

A third, finer level:

3. **Property/field-level authorization** — "may this caller *read* or *write* this *field* of the object?" (e.g., a user may read their `email` but not their `creditScore`; may write their `displayName` but not their `role`). OWASP **API3**.

### 2.3 BOLA / IDOR — the #1 API vulnerability

**IDOR (Insecure Direct Object Reference)** is the classic name; **BOLA (Broken Object Level Authorization)** is OWASP's API-era name for the same bug. The pattern:

```
GET /api/v1/invoices/1001   →  Authorization: Bearer <Alice's valid token>
```

The server validates the token (authN ✔), looks up invoice `1001`, and returns it — **without checking that invoice 1001 belongs to Alice.** Alice changes `1001` to `1002` and reads Bob's invoice. The IDs are *direct object references*: they map straight to a database row, and the only thing standing between Alice and every invoice in the system is *that she didn't try other numbers.*

Why it's so common:
- Authentication "feels" like security, so engineers stop there.
- The ownership check is *business logic* the framework can't auto-generate; it must be written per resource.
- Sequential/guessable IDs make exploitation point-and-click. (Using UUIDs **reduces discoverability but does not fix the bug** — a leaked, shared, or logged UUID is still exploitable. Obscurity is not authorization.)

The fix, stated once and forever: **on every object access, verify that the authenticated principal is authorized for that specific object, using a check that the application enforces server-side and that cannot be influenced by the client.**

### 2.4 Credentials and tokens (the building blocks of authN)

- **API key** — a long random string issued to a client; presented on each call (header `X-API-Key` or `Authorization: ApiKey ...`). Identifies the *application/account*, not usually a human. Simple; weak on rotation, scoping, and non-repudiation.
- **Bearer token** — any token where "whoever holds it can use it" (Latin *bearer* = holder). OAuth2 access tokens are bearer tokens. Implication: a stolen bearer token = full impersonation until expiry. Protect like a password; keep TTLs short.
- **JWT (JSON Web Token)** — a compact, *self-contained*, digitally signed token. Three Base64URL parts separated by dots: `header.payload.signature`. The payload holds **claims** (e.g., `sub` = subject/user, `exp` = expiry, `aud` = audience, `iss` = issuer, `scope`). Anyone can *read* it (it's not encrypted by default); only the holder of the signing key can *forge* it. Verifying the signature proves the claims weren't tampered with.

  > **Adjacent term — claim.** A statement inside a token, e.g., `"sub":"alice"`, `"role":"admin"`. The token is only as trustworthy as your signature verification.

- **mTLS (mutual TLS)** — both sides of a TLS connection present X.509 certificates. The *server* proves identity to the client (normal HTTPS) **and** the *client* proves identity to the server with its own cert. Strong, phishing-resistant, ideal for service-to-service. Heavier to operate (cert issuance, rotation, revocation).

  > **Adjacent term — TLS (Transport Layer Security).** The protocol behind HTTPS. It encrypts traffic and authenticates the server via a certificate signed by a Certificate Authority (CA). **mTLS** adds client-side certificate authentication.
  > **Adjacent term — X.509 certificate.** A standardized digital identity document binding a public key to a subject (e.g., a hostname or service name), signed by a CA. **CA (Certificate Authority):** a trusted issuer of certificates.

### 2.5 OAuth2 and OIDC (the standard for delegated access)

**OAuth2** is an *authorization-delegation* framework: it lets a user grant a client *limited* access to resources without sharing their password. The core actors:

- **Resource Owner** — the user who owns the data.
- **Client** — the app requesting access (your SPA, mobile app, or partner server).
- **Authorization Server (AS)** — issues tokens after authenticating the user/client (e.g., Keycloak, Okta, Auth0, AWS Cognito, Azure AD/Entra ID).
- **Resource Server (RS)** — your API, which accepts and validates access tokens.

The client obtains an **access token** (short-lived bearer token used to call the RS) and often a **refresh token** (longer-lived, used to get new access tokens without re-login). Tokens carry **scopes** — coarse permission labels like `orders:read`, `orders:write`.

> **OAuth2 is authorization, not authentication.** It tells your API *what the client may do*, not reliably *who the user is*. For *identity* you layer **OIDC (OpenID Connect)** on top: OIDC adds an **ID token** (a JWT describing the authenticated user) and a standard `/userinfo` endpoint. Rule of thumb: **OAuth2 = access; OIDC = login.**

**OAuth2 grant types (flows)** — how a client gets a token:

| Grant | Use it for | Notes |
|-------|-----------|-------|
| **Authorization Code + PKCE** | Web apps, SPAs, mobile (any user-facing client) | The modern default. PKCE protects against code interception. |
| **Client Credentials** | Service-to-service (no user) | Client authenticates with its own ID/secret (or cert/JWT). |
| **Refresh Token** | Renewing access without re-login | Rotate refresh tokens; detect reuse. |
| **Device Authorization** | Input-constrained devices (TVs, CLIs) | User authorizes on a second device. |
| **Resource Owner Password Credentials (ROPC)** | (Legacy) client collects user's password | **Deprecated — avoid.** Defeats the point of delegation. |
| **Implicit** | (Legacy SPA flow) | **Deprecated — avoid.** Use Auth Code + PKCE. |

> **Adjacent term — PKCE (Proof Key for Code Exchange, "pixie").** An OAuth2 extension that stops an attacker who intercepts the authorization code from exchanging it for a token. The client creates a random secret (`code_verifier`), sends its hash (`code_challenge`) up front, and must present the original verifier to redeem the code. Mandatory for public clients (SPAs, mobile).
> **Adjacent term — scope.** A space-delimited list of permission labels in a token (`scope: "orders:read profile"`). Coarse-grained; your API still must do object-level checks.

### 2.6 Sessions vs tokens; stateful vs stateless

- **Stateful (server-side session):** the server stores session state and gives the client an opaque session ID (often a cookie). To revoke, delete the server record. Easy revocation; needs shared session storage at scale.
- **Stateless (self-contained token, e.g., JWT):** the server stores nothing; all needed data is *in* the token and trusted via signature. Scales horizontally with no shared store; **but revocation is hard** — a valid signed token works until `exp` unless you add a denylist or keep TTLs short.

This stateful/stateless tension drives many real design decisions (see §7).

### 2.7 Input validation, output encoding, injection

- **Input validation** — checking that incoming data matches an expected shape, type, length, range, and format *before* you use it. Prefer **allow-listing** (define what's permitted) over **deny-listing** (try to block known-bad). Allow-lists fail closed; deny-lists always miss a variant.
- **Injection** — when untrusted input is interpreted as *code/commands* by a downstream interpreter (SQL engine, OS shell, LDAP, XPath, template engine). Fix: keep data and code separate (parameterized queries, safe APIs), never string-concatenate untrusted input into a command.
- **Output encoding** — transforming data so it is treated as *inert text* by the *sink* that receives it (HTML, JSON, a shell, a log). The right encoding depends on the destination. For pure JSON APIs the canonical encoding is "emit well-formed JSON via a real serializer," but the moment your data lands in HTML, a CSV opened by Excel, or a log, sink-specific encoding matters.

  > **Adjacent term — XSS (Cross-Site Scripting).** Injecting script into a web page so it runs in another user's browser. JSON APIs aren't immune: if a browser renders your JSON as HTML (wrong `Content-Type`, or the data is later injected into a page), unencoded `<script>` can execute. Set `Content-Type: application/json` and `X-Content-Type-Options: nosniff`.
  > **Adjacent term — CSV/formula injection.** A field starting with `=`, `+`, `-`, or `@` becomes a live formula when the CSV is opened in a spreadsheet. Prefix such fields with `'` on export.

### 2.8 Rate limiting, throttling, quotas

- **Rate limiting** — capping requests per unit time (e.g., 100 req/min per API key). Protects against bursts, brute force, scraping, and accidental client loops.
- **Throttling** — slowing (queuing/delaying) rather than rejecting, to smooth load.
- **Quota** — a longer-horizon cap (e.g., 1,000,000 calls/month per plan). Business/cost control as much as security.

> **Adjacent term — denial-of-wallet.** A DoS variant against serverless/usage-billed systems: the attacker doesn't take you down, they make your cloud bill explode. Quotas and per-tenant limits defend against it.

### 2.9 CORS (Cross-Origin Resource Sharing)

Browsers enforce the **Same-Origin Policy (SOP):** by default, JavaScript on `https://a.com` cannot read responses from `https://b.com`. **CORS** is the controlled relaxation: your API uses response headers (`Access-Control-Allow-Origin`, etc.) to tell the browser *which* other origins may read its responses.

> **Critical, constantly-misunderstood point:** CORS is a **browser-enforced** mechanism that protects *the user's browser data*, not your server. It is **not** an authentication or authorization control. A `curl`, a mobile app, or a malicious server ignores CORS entirely. Mis-set `Access-Control-Allow-Origin: *` together with credentials is a real vulnerability, but a strict CORS policy is *not* a substitute for authZ.
> **Adjacent term — origin.** The triple (scheme, host, port), e.g., `https://app.example.com:443`. Two URLs share an origin only if all three match.
> **Adjacent term — preflight.** For "non-simple" requests (custom headers, `PUT`/`DELETE`, JSON content-type with credentials), the browser first sends an `OPTIONS` request asking permission; your server answers with the allowed methods/headers.

### 2.10 The perimeter: gateway, WAF, bot mitigation

- **API gateway** — a reverse proxy purpose-built for APIs that sits in front of your services and centralizes cross-cutting concerns: TLS termination, authN, coarse authZ, rate limiting, request routing, schema validation, logging. Examples: Kong, Apigee, AWS API Gateway, Spring Cloud Gateway, Envoy/Istio gateway, NGINX.
- **WAF (Web Application Firewall)** — a filter that inspects HTTP traffic and blocks known attack patterns (SQLi, XSS, path traversal) using rule sets (e.g., the **OWASP Core Rule Set / CRS** for ModSecurity). Signature/heuristic-based; bypassable; **defense-in-depth, not the primary control.**
- **Bot mitigation** — distinguishing legitimate clients from automated abuse (credential stuffing, scraping, scalping) via fingerprinting, behavioral analysis, CAPTCHAs, and challenges.

> **Adjacent term — reverse proxy.** A server that receives client requests and forwards them to backend servers, returning their responses. The client thinks it's talking to one host. Gateways and WAFs are specialized reverse proxies.
> **Adjacent term — defense-in-depth.** Layering multiple independent controls so that if one fails, others still protect you. A WAF *and* parameterized queries *and* least-privilege DB creds.

### 2.11 Security headers (quick map)

HTTP response headers that instruct the browser to behave more safely. For APIs the relevant ones:

| Header | Purpose |
|--------|---------|
| `Strict-Transport-Security` (HSTS) | Force HTTPS for future visits. |
| `X-Content-Type-Options: nosniff` | Stop the browser from guessing content types. |
| `Content-Security-Policy` | Restrict what resources a page may load (mostly for HTML; a tight `default-src 'none'` is good for API error pages). |
| `Cache-Control: no-store` | Prevent caching of sensitive responses. |
| `X-Frame-Options: DENY` | Block clickjacking via framing (HTML responses). |

### 2.12 Threat-modeling vocabulary

- **Threat** — something bad that *could* happen (data theft).
- **Vulnerability** — a weakness that enables it (missing object check).
- **Attack/exploit** — actually doing it.
- **Attack surface** — the sum of all points an attacker can poke (every endpoint, field, parameter).
- **STRIDE** — a threat taxonomy: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege. A handy checklist when designing an endpoint.

---

## 3. How it works internally

This section is the heart of the chapter: the *step-by-step* lifecycle of a secured API request, and the internal mechanics of each control.

### 3.1 The end-to-end lifecycle of a secured request

Consider `GET /api/v1/orders/42` from a browser SPA. Here is what happens, in order, as the request crosses each control layer:

```
[Client] ── HTTPS ──> [WAF / CDN] ── [API Gateway] ── [Service mesh] ── [Application]
```

1. **DNS + TLS handshake.** Client resolves the host, opens a TLS connection. The server presents its certificate; the client validates the chain. (If mTLS: client also presents its cert; server validates it.) Result: an encrypted, authenticated channel. *Failure here* = no connection.

2. **Edge / CDN / WAF inspection.** The request hits the edge first. The WAF inspects method, path, headers, and body against its rule set (e.g., CRS): blocks obvious SQLi/XSS/path-traversal, enforces max body size, drops malformed requests, applies IP reputation and geo rules, and does coarse global rate limiting (e.g., per-IP). *Failure here* = `403`/`406`/`429` before your code runs.

3. **API gateway processing.** The gateway:
   a. **Terminates or passes through TLS.**
   b. **Authenticates** the request: extracts the `Authorization: Bearer <JWT>` header, validates the signature against the AS's public keys (fetched from the **JWKS** endpoint), and checks `exp`, `iss`, `aud`, `nbf`. If the token is opaque, it **introspects** it (calls the AS's `/introspect`). *Failure* = `401`.
   c. **Coarse authZ:** checks the token has a required scope for the route (e.g., route `/orders/**` needs `orders:read`). *Failure* = `403`.
   d. **Rate limiting & quota** per API key/client/user.
   e. **Schema validation** (optional): validates the request against an OpenAPI schema.
   f. **Routes** the request to the upstream service, often adding trusted headers (`X-User-Id`, `X-Scopes`) and stripping client-spoofed ones.

   > **Adjacent term — JWKS (JSON Web Key Set).** A JSON document, published by the AS at a well-known URL (`/.well-known/jwks.json`), listing the public keys used to verify JWT signatures, each tagged with a key ID (`kid`). Verifiers fetch and cache it, matching the token's `kid` to the right key. This enables **key rotation** without redeploying verifiers.
   > **Adjacent term — token introspection.** RFC 7662: the RS calls the AS's `/introspect` endpoint to ask "is this opaque token still valid, and what are its scopes?" Used for non-JWT (reference) tokens and to honor revocation.

4. **Service mesh / mTLS (internal).** Inside the cluster, a service mesh (e.g., Istio with Envoy sidecars) may enforce **mTLS between services** and propagate identity. So even past the gateway, service-to-service calls are mutually authenticated. *Failure* = connection refused at the sidecar.

5. **Application: authentication context.** The framework (e.g., Spring Security) parses the validated token into a **principal** (a `Authentication`/`Principal` object) holding the user ID, scopes, roles, tenant. This is now the *trusted* identity for in-app decisions. The app must **not** trust client-supplied identity fields in the body or query.

6. **Application: input validation.** The request DTO is deserialized and validated (Bean Validation / Jakarta Validation annotations, or manual checks): types, lengths, ranges, formats, allowed enum values, and — crucially — **only the fields the client is allowed to send are bound** (mass-assignment defense).

7. **Application: function-level authZ.** Method security checks the principal has the role/scope for *this operation* (`@PreAuthorize("hasAuthority('SCOPE_orders:read')")`).

8. **Application: object-level authZ (the BOLA check).** The service loads order 42 and verifies `order.ownerId == principal.userId` (or runs an ABAC/ReBAC policy). **If not authorized → return `404` or `403`** (prefer `404` to avoid confirming the object exists). This is the step most often missing.

9. **Business logic + data access.** Parameterized queries (no string concatenation), least-privilege DB credentials, row-level scoping (e.g., the query itself filters `WHERE owner_id = ?`).

10. **Output filtering / serialization.** The service maps the domain object to a **response DTO that contains only the fields this caller may see** (property-level read authZ + anti-excessive-exposure). Serialize via Jackson with `Content-Type: application/json`.

11. **Security headers + response.** The gateway/app adds security headers (`nosniff`, `no-store`, HSTS), strips internal headers, and returns the response. Errors are returned in a **generic, non-leaky** form.

12. **Audit log.** The event (who, what, when, object, decision) is logged for forensics, *without* logging secrets/tokens/PII in cleartext.

### 3.2 Internal mechanics of JWT validation (step by step)

When the gateway/app receives `Authorization: Bearer eyJ...`:

1. **Split** the token into `header.payload.signature` (three Base64URL segments).
2. **Decode the header**; read `alg` (algorithm, e.g., `RS256`) and `kid` (key ID).
3. **Reject `alg: none`** and reject algorithms you didn't expect (the classic *algorithm-confusion* attack — see §7.3). Pin the expected algorithm.
4. **Resolve the key:** match `kid` against the cached JWKS; if missing, refetch JWKS (rate-limited) from the AS.
5. **Verify the signature** over `header.payload` using the public key (for RS256) or shared secret (for HS256).
6. **Validate claims:** `exp` (not expired), `nbf`/`iat` (not used too early), `iss` (expected issuer), `aud` (this API is the intended audience). Allow small clock skew (e.g., 30–60s).
7. **(Optional) check revocation** against a denylist or via introspection.
8. **Build the principal** from `sub`, `scope`, custom claims.

A single mistake here — skipping `aud`, accepting `none`, trusting `kid` to fetch an attacker URL — turns "secure" into "forgeable."

### 3.3 Internal mechanics of OAuth2 Authorization Code + PKCE (state machine)

```
1. Client generates: code_verifier (random), code_challenge = BASE64URL(SHA256(code_verifier)), state (CSRF nonce)
2. Client → AS /authorize?response_type=code&client_id=...&redirect_uri=...&scope=...&state=...&code_challenge=...&code_challenge_method=S256
3. AS authenticates the user (login + consent)
4. AS → redirect back to redirect_uri?code=AUTH_CODE&state=...
5. Client verifies returned state == sent state (CSRF defense)
6. Client → AS /token  (grant_type=authorization_code, code=AUTH_CODE, code_verifier=..., client_id=..., redirect_uri=...)
7. AS verifies SHA256(code_verifier) == stored code_challenge  → issues access_token (+ refresh_token, + id_token if OIDC)
8. Client → Resource Server with Authorization: Bearer access_token
9. RS validates token (per §3.2) and serves the request
```

The **state machine** at the AS for an auth code: `ISSUED → REDEEMED` (single use). A code may be redeemed exactly once; replays are rejected. PKCE binds the code to the client instance that started the flow.

### 3.4 Internal mechanics of rate limiting (algorithms)

Rate limiters are small state machines per key (IP, API key, user, route). The four canonical algorithms:

1. **Fixed window.** Count requests in each clock-aligned window (e.g., per minute); reset at the boundary. *Flaw:* burst at the window edge allows 2× the limit across the boundary.
2. **Sliding window log.** Store a timestamp per request; count those within the trailing window. Exact but memory-heavy.
3. **Sliding window counter.** Approximate the trailing window by weighting the previous and current fixed windows. Cheap and smooth — a common production choice.
4. **Token bucket.** A bucket holds up to `B` tokens, refilled at rate `r` tokens/sec. Each request consumes one token; empty bucket → reject (or queue). Allows controlled **bursts** up to `B`, then sustains `r`. The most popular general-purpose algorithm.
5. **Leaky bucket.** Requests enter a fixed-size queue drained at a constant rate; overflow is dropped. Produces a perfectly smooth output rate (good for protecting a fragile backend).

**Distributed enforcement:** with many gateway instances, counters must be *shared* or *coordinated*, usually in Redis (atomic `INCR`/Lua scripts) or via a centralized limiter. The classic tradeoff: a *global* exact limit needs a round-trip to shared state (latency); *per-node* limits avoid the round-trip but the effective global limit is `nodes × per_node`.

### 3.5 Internal mechanics of CORS (the browser dance)

For a credentialed cross-origin `GET`/non-simple request:

1. Browser sends a **preflight** `OPTIONS` with `Origin`, `Access-Control-Request-Method`, `Access-Control-Request-Headers`.
2. Server responds with `Access-Control-Allow-Origin: <echoed specific origin>`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`, `Access-Control-Allow-Credentials: true`, and `Access-Control-Max-Age` (how long to cache the preflight).
3. If the actual request matches, the browser sends it; the server again includes `Access-Control-Allow-Origin`.
4. If the response's `Access-Control-Allow-Origin` doesn't match the page's origin, **the browser blocks the JS from reading the response** (the request may still have hit the server!).

> Key internal nuance: **the request reaches your server regardless.** CORS only stops *the browser* from *exposing the response to JS*. Therefore (a) state-changing endpoints must also have CSRF protection / require non-cookie auth, and (b) you cannot rely on CORS to stop a non-browser attacker.

### 3.6 Internal mechanics of mTLS (handshake)

1. Client opens TLS; server sends its certificate + a `CertificateRequest`.
2. Client sends **its** certificate and a `CertificateVerify` (a signature proving it holds the private key).
3. Server validates the client cert: chain to a trusted CA, not expired, not revoked (CRL/OCSP), and the subject/SAN matches an allowed identity.
4. Both derive session keys; the channel is now mutually authenticated.

> **Adjacent terms — CRL / OCSP.** Revocation mechanisms. A **CRL (Certificate Revocation List)** is a published list of revoked certs. **OCSP (Online Certificate Status Protocol)** lets a verifier ask a responder, in real time, "is this cert still valid?" Revocation is the operationally hard part of mTLS.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, classes, annotations, headers, config flags, and tools, with purpose, key parameters, and defaults. Java/Spring-centric where language-relevant, plus standard HTTP and gateway tooling. **Flag version/vendor specifics where they matter.**

### 4.1 Spring Security (the JVM workhorse)

> Version note: examples target **Spring Boot 3.x / Spring Security 6.x** (Jakarta namespace `jakarta.*`, lambda DSL). Spring Security 5.x uses `javax.*` and an older DSL.

| API / class / annotation | Purpose | Key params / defaults |
|---|---|---|
| `SecurityFilterChain` (`@Bean`) | Defines the filter chain (authN/authZ rules). | Built via `HttpSecurity` DSL. Multiple chains matched by request matcher. |
| `HttpSecurity` | Fluent config: `authorizeHttpRequests`, `oauth2ResourceServer`, `csrf`, `cors`, `sessionManagement`, `headers`. | — |
| `authorizeHttpRequests(...)` | Path-based access rules. | `.requestMatchers("/admin/**").hasRole("ADMIN")`, `.anyRequest().authenticated()`. |
| `oauth2ResourceServer().jwt()` | Validate incoming JWT access tokens. | `jwk-set-uri` or `issuer-uri`; auto-fetches JWKS. |
| `oauth2ResourceServer().opaqueToken()` | Validate opaque tokens via introspection. | `introspection-uri`, client id/secret. |
| `@EnableMethodSecurity` | Turns on method-level annotations. | `prePostEnabled=true` (default in SS6). |
| `@PreAuthorize` / `@PostAuthorize` | SpEL-based authZ before/after a method. | `@PreAuthorize("hasAuthority('SCOPE_orders:read')")`; `@PostAuthorize("returnObject.ownerId == authentication.name")`. |
| `@PostFilter` / `@PreFilter` | Filter collection elements by authZ. | `@PostFilter("filterObject.ownerId == authentication.name")`. |
| `AuthorizationManager` | Programmatic authZ (replaces `AccessDecisionManager`). | Custom object-level checks. |
| `SecurityContextHolder` | Access current `Authentication`. | `SecurityContextHolder.getContext().getAuthentication()`. |
| `CorsConfigurationSource` | CORS policy. | `allowedOrigins` (no `*` with credentials), `allowedMethods`, `allowCredentials`. |
| `.headers(...)` | Security headers. | HSTS, `nosniff`, frame options on by default for HTML. |
| `SessionCreationPolicy.STATELESS` | No server session (token APIs). | Set for pure token APIs. |
| `BCryptPasswordEncoder` / `Argon2`/`SCrypt`/`Pbkdf2` | Password hashing. | BCrypt default strength 10; prefer Argon2id for new systems. |

> Application properties (Boot 3, resource server):
> ```properties
> spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.example.com/realms/app
> # or
> spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example.com/.well-known/jwks.json
> ```

### 4.2 Bean Validation (Jakarta Validation) — input validation

| Annotation | Purpose | Notes |
|---|---|---|
| `@NotNull` / `@NotBlank` / `@NotEmpty` | Presence checks. | `@NotBlank` = non-null + trimmed length > 0. |
| `@Size(min, max)` | Length/size bounds. | Strings, collections, maps. |
| `@Min` / `@Max` / `@Positive` / `@Negative` | Numeric range. | — |
| `@Pattern(regexp=...)` | Format via regex. | **Beware ReDoS** with catastrophic regexes. |
| `@Email` | Email format. | Loose by spec; combine with domain rules. |
| `@Valid` | Cascade validation into nested objects / trigger on a controller param. | Put on `@RequestBody` arg. |
| `@Validated` (Spring) | Enables method-level and group validation. | Class-level; supports validation groups. |
| Custom `ConstraintValidator` | Domain rules (e.g., allowed currency). | Implement `ConstraintValidator<A, T>`. |

> **Defaults:** validation only runs where you trigger it (`@Valid`/`@Validated`). Missing the annotation = no validation = silent hole.

### 4.3 Jackson (serialization — exposure & mass-assignment control)

| Feature | Purpose | Notes |
|---|---|---|
| `@JsonIgnore` | Never serialize/deserialize a field. | Use on secrets (`passwordHash`). |
| `@JsonProperty(access = READ_ONLY)` | Serialize but never deserialize. | Blocks that field from inbound binding. |
| `@JsonProperty(access = WRITE_ONLY)` | Deserialize but never serialize. | E.g., inbound `password`. |
| `@JsonIgnoreProperties(ignoreUnknown = false)` | Reject unknown inbound fields. | Set `false` to **fail on extra fields** (anti mass-assignment). |
| `FAIL_ON_UNKNOWN_PROPERTIES` | Global equivalent. | Default `true` in Jackson; Spring Boot **disables it** by default — re-enable for strictness. |
| `@JsonView` | Field subsets per view (e.g., public vs admin). | Tie to authZ for property-level read control. |
| **Dedicated DTOs** | Separate request/response DTOs from entities. | The strongest control — see §6. |

### 4.4 Standard HTTP status codes for security

| Code | Meaning in security context |
|---|---|
| `400 Bad Request` | Malformed/invalid input (validation failure). |
| `401 Unauthorized` | Authentication missing/failed. (Misnamed — it's about authN.) |
| `403 Forbidden` | Authenticated but not authorized. |
| `404 Not Found` | Use *instead of* `403` to hide existence of objects the caller can't access (anti-enumeration). |
| `405 Method Not Allowed` | Method not permitted on resource. |
| `406 / 415` | Unacceptable / unsupported media type. |
| `422 Unprocessable Entity` | Semantically invalid (some teams use for validation). |
| `429 Too Many Requests` | Rate limit/quota exceeded. Include `Retry-After`. |

### 4.5 Security & rate-limit response headers

| Header | Purpose / typical value |
|---|---|
| `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload` | Force HTTPS. |
| `X-Content-Type-Options: nosniff` | No MIME sniffing. |
| `Content-Security-Policy: default-src 'none'` | Lock down (good for API error pages). |
| `Cache-Control: no-store` | Don't cache sensitive responses. |
| `X-Frame-Options: DENY` | Anti-clickjacking. |
| `Referrer-Policy: no-referrer` | Don't leak URLs. |
| `Retry-After: 30` | With `429`/`503`. |
| `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` | IETF draft rate-limit hints to clients. |
| `Access-Control-Allow-Origin: https://app.example.com` | CORS — **specific origin, never `*` with credentials**. |

### 4.6 API gateways (comparison)

| Gateway | Type | AuthN/AuthZ | Rate limit | Notes |
|---|---|---|---|---|
| **Kong** | OSS/Enterprise, Lua/plugins | JWT, OAuth2, key-auth, mTLS plugins | Yes (Redis-backed) | Plugin ecosystem; runs on NGINX. |
| **AWS API Gateway** | Managed | Cognito, Lambda authorizers, IAM, API keys | Usage plans + throttling | Tight AWS integration; per-call pricing. |
| **Apigee** (Google) | Managed/Enterprise | OAuth2, API keys, SAML | Quotas/spike arrest | Strong analytics, monetization. |
| **Spring Cloud Gateway** | Library (JVM) | Integrates Spring Security | `RequestRateLimiter` (Redis) | Code-first, fits Spring stacks. |
| **Envoy / Istio** | Proxy / mesh | mTLS, ext_authz, JWT filter | Yes (rate-limit service) | Cloud-native, service mesh. |
| **NGINX / NGINX Plus** | Proxy | `auth_request`, JWT (Plus) | `limit_req`/`limit_conn` | Ubiquitous, lightweight. |

### 4.7 WAF / CRS

| Tool | Notes |
|---|---|
| **ModSecurity + OWASP CRS** | Open-source WAF engine + the OWASP **Core Rule Set** (generic attack signatures). Tune **paranoia levels** (PL1–PL4) to trade coverage vs false positives. |
| **AWS WAF** | Managed rules + custom rules; rate-based rules; Bot Control add-on. |
| **Cloudflare / Akamai / Imperva / F5** | Managed WAF + CDN + bot management + DDoS. |

### 4.8 Token & secret tooling

| Tool | Purpose |
|---|---|
| **Keycloak / Okta / Auth0 / Cognito / Entra ID** | Authorization servers / IdPs (issue OAuth2/OIDC tokens). |
| **HashiCorp Vault** | Secrets management, dynamic DB creds, PKI (cert issuance for mTLS). |
| **cert-manager (K8s)** | Automated TLS cert issuance/renewal (e.g., with Let's Encrypt / internal CA). |
| **SPIFFE/SPIRE** | Standard for workload identity (issues short-lived SVID certs for mTLS in meshes). |

> **Adjacent term — IdP (Identity Provider).** A service that authenticates users and asserts identity to other systems (often via OIDC/SAML).

### 4.9 Testing & scanning tools

| Tool | Purpose |
|---|---|
| **OWASP ZAP**, **Burp Suite** | Intercepting proxies / DAST for manual & automated API testing (great for BOLA hunting by tampering IDs). |
| **Schemathesis**, **Dredd** | Property-based testing from OpenAPI specs. |
| **42Crunch**, **APIsec** | API-specific security testing. |
| **Semgrep / SonarQube / Snyk / OWASP Dependency-Check** | SAST + dependency/SCA scanning (vulnerable libraries). |
| **Trivy / Grype** | Container image scanning. |

> **Adjacent terms — SAST / DAST / SCA.** **SAST** (Static Application Security Testing): scans source code. **DAST** (Dynamic): tests the running app. **SCA** (Software Composition Analysis): finds known-vulnerable dependencies.

---

## 5. Code examples by use case

All Java examples target **Spring Boot 3.x / Spring Security 6.x** unless noted. Comments explain the non-obvious lines.

### 5.1 Resource server validating JWT access tokens

```java
// build.gradle: implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'

// application.yml
// spring:
//   security:
//     oauth2:
//       resourceserver:
//         jwt:
//           issuer-uri: https://auth.example.com/realms/app   # discovers JWKS + validates iss

@Configuration
@EnableWebSecurity
@EnableMethodSecurity            // turns on @PreAuthorize / @PostAuthorize
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    http
      .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // no cookies/session
      .csrf(csrf -> csrf.disable())                 // safe ONLY because we use bearer tokens, not cookies
      .authorizeHttpRequests(auth -> auth
          .requestMatchers("/actuator/health").permitAll()
          .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
          .requestMatchers("/api/v1/admin/**").hasAuthority("SCOPE_admin")
          .anyRequest().authenticated())            // everything else needs a valid token
      .oauth2ResourceServer(oauth -> oauth
          .jwt(jwt -> jwt.jwtAuthenticationConverter(scopeConverter()))) // map "scope" claim -> authorities
      .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'")));
    return http.build();
  }

  // Convert space-delimited "scope" claim into SCOPE_* authorities used by hasAuthority(...)
  private JwtAuthenticationConverter scopeConverter() {
    var scopes = new JwtGrantedAuthoritiesConverter();
    scopes.setAuthorityPrefix("SCOPE_");
    scopes.setAuthoritiesClaimName("scope");
    var conv = new JwtAuthenticationConverter();
    conv.setJwtGrantedAuthoritiesConverter(scopes);
    return conv;
  }
}
```

What matters: `issuer-uri` makes Spring fetch JWKS and validate `iss` automatically; you should *also* validate `aud` (add a custom `OAuth2TokenValidator` if your AS doesn't enforce it). `STATELESS` + bearer tokens makes CSRF a non-issue (CSRF needs ambient credentials like cookies).

### 5.2 The BOLA fix — object-level authorization three ways

**(a) Query-scoped (best — the DB can't return what you don't ask for):**

```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  private final OrderRepository orders;

  @GetMapping("/{id}")
  public OrderResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt principal) {
    String userId = principal.getSubject();                 // trusted identity from the token, NOT the request body
    // The ownership predicate is IN THE QUERY: a non-owner gets an empty result, never another user's row.
    Order order = orders.findByIdAndOwnerId(id, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // 404 hides existence
    return OrderResponse.from(order);                       // map to a response DTO (see 5.4)
  }
}
```

**(b) Explicit check after load:**

```java
Order order = orders.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
if (!order.getOwnerId().equals(userId)) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND);  // do NOT leak 403 here unless you want to confirm existence
}
```

**(c) Declarative with `@PostAuthorize` (use sparingly — object is already loaded into memory):**

```java
@PostAuthorize("returnObject.ownerId == authentication.name")
public Order load(long id) { return orders.findById(id).orElseThrow(); }
```

> Prefer (a). It is the only one where a logic slip can't leak data, it pushes the filter to the DB, and it works naturally for list endpoints (`findAllByOwnerId`). Add a centralized `AuthorizationManager` if ownership logic is complex (delegation, teams, sharing).

### 5.3 Multi-tenant row scoping (avoid cross-tenant BOLA at scale)

```java
// Bind tenant from the validated token, store in a request-scoped holder.
@Component
class TenantContext {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  static void set(String t) { CURRENT.set(t); }
  static String get() { return CURRENT.get(); }
  static void clear() { CURRENT.remove(); }
}

// A filter sets it from the JWT "tenant" claim (after authentication).
@Component
class TenantFilter extends OncePerRequestFilter {
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwt) {
      TenantContext.set(jwt.getToken().getClaimAsString("tenant"));
    }
    try { chain.doFilter(req, res); } finally { TenantContext.clear(); } // ALWAYS clear (thread reuse!)
  }
}

// Enforce in data access (Hibernate filter / specification). Every query is tenant-scoped by construction.
@Repository
class OrderRepoImpl {
  @PersistenceContext EntityManager em;
  List<Order> findForCurrentTenant() {
    return em.createQuery("select o from Order o where o.tenantId = :t", Order.class)
             .setParameter("t", TenantContext.get())   // tenant comes from the token, never from the client
             .getResultList();
  }
}
```

The `finally { clear(); }` is load-bearing: servlet threads are pooled and reused; a leaked `ThreadLocal` would assign the previous request's tenant to the next user — a catastrophic cross-tenant leak.

### 5.4 Preventing mass assignment and excessive data exposure with DTOs

```java
// ENTITY: never bind directly to requests, never serialize directly to responses.
@Entity
class User {
  @Id Long id;
  String email;
  String displayName;
  String passwordHash;     // secret
  String role;             // privileged: "USER" / "ADMIN"
  Instant createdAt;
}

// REQUEST DTO: contains ONLY client-writable fields. There is literally no "role" field to set.
record UpdateProfileRequest(
    @NotBlank @Size(max = 80) String displayName,   // validated
    @Email @Size(max = 254) String email) {}

// RESPONSE DTO: contains ONLY caller-visible fields. No passwordHash, no internal flags.
record UserResponse(Long id, String email, String displayName, Instant createdAt) {
  static UserResponse from(User u) {
    return new UserResponse(u.id, u.email, u.displayName, u.createdAt);
  }
}

@PutMapping("/api/v1/me")
public UserResponse update(@Valid @RequestBody UpdateProfileRequest req,   // @Valid runs Bean Validation
                           @AuthenticationPrincipal Jwt principal) {
  User u = users.findById(Long.valueOf(principal.getSubject())).orElseThrow();
  u.displayName = req.displayName();   // we copy field-by-field; "role" can NEVER be set from input
  u.email = req.email();
  users.save(u);
  return UserResponse.from(u);
}
```

Why DTOs beat annotations: an attacker sending `{"displayName":"x","role":"ADMIN","balance":999}` cannot escalate, because the request type *has no such fields* — Jackson ignores or (better) rejects them. Conversely, the response *cannot* leak `passwordHash` because it isn't in `UserResponse`. Add `spring.jackson.deserialization.fail-on-unknown-properties=true` to **reject** unexpected fields loudly instead of silently dropping them.

### 5.5 Input validation including a custom rule and ReDoS-safe pattern

```java
record CreatePaymentRequest(
    @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,  // bounded regex, no catastrophic backtracking
    @NotBlank @Size(max = 140) String memo,
    @ValidCurrency String currencyCheck) {}                     // custom domain rule

// Custom constraint: allow-list of supported currencies (deny-list would miss new junk values).
@Documented @Constraint(validatedBy = CurrencyValidator.class)
@Target(ElementType.FIELD) @Retention(RetentionPolicy.RUNTIME)
@interface ValidCurrency { String message() default "unsupported currency";
    Class<?>[] groups() default {}; Class<? extends Payload>[] payload() default {}; }

class CurrencyValidator implements ConstraintValidator<ValidCurrency, String> {
  private static final Set<String> ALLOWED = Set.of("USD", "EUR", "INR", "GBP"); // allow-list
  public boolean isValid(String value, ConstraintValidatorContext ctx) {
    return value != null && ALLOWED.contains(value);
  }
}
```

### 5.6 SQL injection prevention (parameterized queries)

```java
// VULNERABLE — never do this:
String sql = "SELECT * FROM users WHERE email = '" + email + "'"; // attacker: ' OR '1'='1
// SAFE — JdbcTemplate with bind parameters; the driver sends data separately from the query plan:
jdbc.query("SELECT id, email FROM users WHERE email = ?", new Object[]{email}, rowMapper);
// SAFE — JPQL/named parameters:
em.createQuery("select u from User u where u.email = :e", User.class).setParameter("e", email).getResultList();
```

Parameterization keeps the SQL *structure* fixed; the input can never change the parsed query. The same principle applies to OS commands (use `ProcessBuilder` with an argument array, never a shell string), LDAP, and XPath.

### 5.7 Strict CORS configuration (Spring)

```java
@Bean
CorsConfigurationSource corsConfig() {
  var cfg = new CorsConfiguration();
  cfg.setAllowedOrigins(List.of("https://app.example.com"));      // EXACT origin(s), never "*"
  cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
  cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
  cfg.setAllowCredentials(true);                                  // legal ONLY with specific origins
  cfg.setMaxAge(Duration.ofMinutes(30));                          // cache preflight to cut OPTIONS traffic
  var src = new UrlBasedCorsConfigurationSource();
  src.registerCorsConfiguration("/api/**", cfg);
  return src;
}
// then in the filter chain: .cors(c -> c.configurationSource(corsConfig()))
```

The spec forbids `Access-Control-Allow-Origin: *` together with `Allow-Credentials: true`; libraries that echo the request `Origin` back unconditionally effectively recreate the wildcard-with-credentials hole — don't.

### 5.8 Rate limiting with Bucket4j (token bucket, per API key)

```java
// implementation 'com.bucket4j:bucket4j-core:8.x'  (in-memory; pair with bucket4j-redis for distributed)
@Component
class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private Bucket newBucket() {
    // 100 tokens, refilled 100/min: sustained 100 req/min with bursts up to 100.
    Bandwidth limit = Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)));
    return Bucket.builder().addLimit(limit).build();
  }

  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String key = apiKeyOf(req);                                  // per-key limiting (fall back to IP)
    Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      res.setHeader("RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
      chain.doFilter(req, res);
    } else {
      long waitSec = probe.getNanosToWaitForRefill() / 1_000_000_000;
      res.setStatus(429);
      res.setHeader("Retry-After", Long.toString(waitSec));      // tell well-behaved clients when to retry
      res.getWriter().write("{\"error\":\"rate_limited\"}");
    }
  }
}
```

For multiple instances, back this with Redis (`bucket4j-redis`) so the limit is global; otherwise each node enforces its own 100/min (effective limit = nodes × 100).

### 5.9 Security headers via a filter (defense-in-depth in-app)

```java
@Component
class SecurityHeadersFilter extends OncePerRequestFilter {
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    res.setHeader("Referrer-Policy", "no-referrer");
    chain.doFilter(req, res);
  }
}
```

### 5.10 SSRF-safe outbound fetch (API7)

```java
// When your API fetches a client-supplied URL (webhooks, image import), validate the TARGET.
URI uri = URI.create(userSuppliedUrl);
if (!"https".equals(uri.getScheme())) throw new BadRequest("https only");
InetAddress addr = InetAddress.getByName(uri.getHost());
if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
    || addr.isAnyLocalAddress() || isCloudMetadata(addr)) {        // block 169.254.169.254, 10/8, 192.168/16, ::1, etc.
  throw new ForbiddenException("blocked target");
}
// Re-resolve at connect time or pin the resolved IP to defeat DNS-rebinding (resolve once, connect to that IP).
```

SSRF lets an attacker make *your server* request internal addresses (cloud metadata at `169.254.169.254`, internal admin panels). Block private/loopback/link-local ranges and the metadata IP; beware **DNS rebinding** (a hostname that resolves public on validation, private on connect).

### 5.11 Opaque-token introspection (when you can't validate JWTs locally)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        opaquetoken:
          introspection-uri: https://auth.example.com/oauth2/introspect
          client-id: my-resource-server
          client-secret: ${INTROSPECTION_SECRET}
```

Each request triggers a server-side call to the AS to validate the token and read its scopes — slower than JWT (network round-trip, so cache short-lived results) but supports instant revocation.

### 5.12 mTLS for service-to-service (Spring Boot server side)

```yaml
server:
  ssl:
    enabled: true
    client-auth: need          # require a client certificate (mTLS)
    trust-store: classpath:truststore.p12   # CAs we trust to sign client certs
    trust-store-password: ${TRUSTSTORE_PW}
    key-store: classpath:server.p12         # our own server cert
    key-store-password: ${KEYSTORE_PW}
```

```java
// Authorize based on the client cert's subject (the authenticated peer identity).
@PreAuthorize("authentication.name == 'CN=billing-service,O=Example'")
@PostMapping("/internal/charge")
public ChargeResult charge(@RequestBody ChargeRequest r) { ... }
```

In a service mesh, you'd typically delegate mTLS to the sidecar (Istio `PeerAuthentication: STRICT`) and authorize with an `AuthorizationPolicy`, keeping certs out of app code.

---

## 6. Implementation concerns & best practices

### 6.1 Authentication & token handling

- **Validate every claim:** signature, `exp`, `nbf`, `iss`, `aud`, expected `alg`. Reject `alg: none`. Pin algorithms (don't let the token pick).
- **Short access-token TTLs** (minutes, e.g., 5–15) + refresh tokens for renewal. Short TTL is your cheap revocation.
- **Rotate refresh tokens** and **detect reuse** (a replayed old refresh token = stolen → revoke the family).
- **Never put secrets/PII in JWT payloads** (they're readable). Keep tokens small.
- **Store secrets in a vault**, not in code/config files/images. Rotate API keys and signing keys; support overlapping keys (publish two in JWKS during rotation).
- **Hash passwords with Argon2id / bcrypt / scrypt**, never plain SHA. Salt is built in.
- **Rate-limit auth endpoints aggressively** (login, token, password reset) to stop credential stuffing and brute force; add lockout/backoff and CAPTCHAs on anomalies.

### 6.2 Authorization (the part that fails)

- **Object-level checks on EVERY object access.** Default to **deny**. Prefer **query-scoped** ownership (`findByIdAndOwnerId`) so a missing check can't leak.
- **Centralize authZ** in a policy layer (an `AuthorizationManager`, OPA/Rego, or a ReBAC system like Google Zanzibar/OpenFGA) when ownership is complex (sharing, teams, hierarchies). Don't scatter `if (owner == user)` across controllers.
- **Function-level checks** for every privileged operation; verify both `GET /admin/...` *and* the underlying service method (attackers call methods, not just routes).
- **Property-level checks**: distinct write-allowed and read-allowed field sets per role (DTOs + `@JsonView`).
- **Return `404` not `403`** for objects the caller can't access, to prevent existence enumeration (where confidentiality of existence matters).

> **Adjacent terms — RBAC / ABAC / ReBAC.** **RBAC** (Role-Based): permissions via roles (admin, user). **ABAC** (Attribute-Based): decisions from attributes (department, time, resource owner). **ReBAC** (Relationship-Based): decisions from relationships in a graph ("Alice is editor of Doc 7"), as in Google Zanzibar / OpenFGA — ideal for sharing/collaboration models.
> **Adjacent term — OPA (Open Policy Agent).** A general-purpose policy engine; you write rules in **Rego** and query it for allow/deny decisions, decoupling policy from code.

### 6.3 Input validation & injection

- **Allow-list everywhere**: types, ranges, lengths, enums, formats. Reject, don't sanitize-and-hope.
- **Validate at the edge AND in the app** (gateway schema check is a fast filter; app validation is authoritative).
- **Parameterize all queries/commands.** Use ORMs/`JdbcTemplate`/`ProcessBuilder` arg arrays.
- **Cap sizes:** max body size, max array length, max string length, max nesting depth (JSON bombs). Set a request timeout.
- **Beware ReDoS:** avoid nested quantifiers in regex; bound input length first; consider RE2/`java.util.regex` with input limits.
- **Canonicalize before validating** (decode once, normalize Unicode/paths) to avoid double-encoding bypasses; reject path traversal (`..`).

### 6.4 Output & data exposure

- **Response DTOs only**; never serialize entities. The response type defines the contract.
- **Mask/redact sensitive fields** in responses *and* logs (PII, tokens, card numbers).
- **Set `Content-Type: application/json` + `nosniff`.** Encode for any non-JSON sink (HTML, CSV).
- **Generic error responses**: no stack traces, SQL text, internal hostnames, or class names to clients. Log detail server-side with a correlation ID returned to the client.

### 6.5 Abuse controls

- **Layered rate limits:** global (per-IP, edge), per-API-key/tenant, per-user, per-endpoint (tighter on expensive/auth endpoints).
- **Quotas** per plan for cost control / denial-of-wallet.
- **Protect sensitive business flows** (API6): require step-up auth, device checks, or CAPTCHAs on high-value flows (checkout, transfers, signups).

### 6.6 Observability & audit

- **Structured security logs:** who (principal), what (action+object), when, source IP, decision (allow/deny), correlation ID. **Never log secrets/tokens/passwords.**
- **Metrics & alerts:** `401`/`403`/`429` rates, spikes in `404` (enumeration), latency, error rates, per-tenant anomalies.
- **Distributed tracing** (correlation/trace IDs propagated through gateway → services) for incident reconstruction.
- **Tamper-resistant audit trail** for sensitive actions (append-only, retained per compliance).

### 6.7 Cost

- Token introspection per request = AS load + latency → cache. JWT validation is local and cheap (but watch JWKS refresh storms — cache JWKS, refresh on `kid` miss with backoff).
- WAF/CDN/bot-management are usually per-request priced; weigh against the risk they reduce.
- Rate limits and quotas are themselves a cost control (denial-of-wallet).

### 6.8 Testing & hardening

- **DAST**: OWASP ZAP/Burp; explicitly test BOLA by replaying requests with another user's IDs.
- **SAST/SCA**: Semgrep/Snyk/Dependency-Check in CI; fail builds on high-severity vulns.
- **Contract/property testing** from OpenAPI (Schemathesis) to catch unexpected fields and status codes.
- **Negative tests** as first-class citizens: unauthorized access returns 401/403/404; oversized/invalid input returns 400; rate limit returns 429.
- **API inventory** (API9): auto-discover endpoints, kill shadow/zombie/deprecated versions, require auth on *all* (including health/debug — restrict those).

### 6.9 Anti-patterns (do not do these)

| Anti-pattern | Why it's wrong | Fix |
|---|---|---|
| AuthN without object-level authZ | BOLA — the #1 breach. | Per-object ownership check (query-scoped). |
| UUIDs "for security" | Obscurity, not authorization. | Still check ownership. |
| Binding entities to request bodies | Mass assignment. | Request DTOs with only writable fields. |
| Serializing entities to responses | Excessive data exposure. | Response DTOs. |
| `Access-Control-Allow-Origin: *` w/ credentials | Cross-origin data theft. | Specific origins; never `*` with credentials. |
| CORS as authZ | It's browser-only; non-browsers ignore it. | Real authN/authZ. |
| Trusting client-supplied identity (`userId` in body) | Trivial impersonation. | Use the token principal. |
| `alg: none` / accepting any alg | JWT forgery. | Pin algorithm; reject `none`. |
| Long-lived bearer tokens, no revocation | Stolen token = long-term access. | Short TTL + refresh + denylist. |
| Verbose errors / stack traces to clients | Info disclosure. | Generic errors + server logs. |
| No rate limit on login | Credential stuffing/brute force. | Tight per-IP/per-account limits + lockout. |
| Secrets in code/config/images | Leak via repo/registry. | Vault/secret manager; rotate. |
| Deny-list input filtering | Always bypassable. | Allow-list. |

---

## 7. Advanced topics & deep internals

### 7.1 JWT vs opaque tokens, and the revocation problem

JWTs are self-contained and validated locally (fast, scalable, no AS round-trip) but **cannot be revoked before `exp`** without extra machinery:
- **Short TTL** (the simplest mitigation).
- **Denylist** of revoked `jti` (token IDs) in Redis until they expire.
- **Token versioning**: store a `tokenVersion` per user; bump on logout/compromise; reject tokens with stale versions (requires a per-request lookup — partially defeats statelessness).
- **Hybrid**: short-lived JWT access token + revocable refresh token; revoke at refresh.

Opaque (reference) tokens are validated via introspection (revocation is immediate) at the cost of an AS round-trip per call (cache briefly).

### 7.2 Token binding: sender-constrained tokens (DPoP, mTLS-bound)

Plain bearer tokens are stealable. **Sender-constrained tokens** bind a token to the client's key so a thief can't use it:
- **mTLS-bound tokens (RFC 8705):** the access token is bound to the client's TLS certificate; the RS checks the presented cert matches.
- **DPoP (Demonstrating Proof of Possession, RFC 9449):** the client signs each request with a private key; the token carries the public key's thumbprint. A stolen token without the private key is useless.

### 7.3 JWT attack catalog (know these cold)

- **`alg: none`**: token claims no signature; naive verifiers accept it. *Fix:* reject `none`, pin alg.
- **Algorithm confusion (RS256 → HS256):** the verifier is tricked into using the *public* RSA key as an HMAC *secret*; since the public key is, well, public, the attacker forges a valid HS256 signature. *Fix:* never let the token's `alg` choose the verification algorithm; configure exactly one expected alg.
- **`kid` injection / JWKS spoofing:** if the verifier blindly fetches keys from a `kid`/`jku` URL in the token, the attacker points it at their own key. *Fix:* pin the JWKS URL; ignore `jku`/`x5u` in untrusted tokens.
- **Missing `aud`/`iss` validation:** a token minted for service A is replayed at service B. *Fix:* validate `aud` and `iss`.
- **Weak HMAC secret:** brute-forceable HS256 key. *Fix:* long random keys, or use RS256/ES256.
- **JWT cracking / `none` downgrade** tools: jwt_tool, hashcat — used by attackers and pentesters.

### 7.4 Rate-limiting deep internals

- **Distributed exactness vs latency:** a global token bucket in Redis (Lua for atomicity) gives an exact global limit but adds a round-trip; per-node limits avoid it but are approximate. Many gateways use **sliding-window-counter** in Redis as the balance.
- **Sync intervals:** some limiters allow nodes to keep local counters and sync periodically — bursty but fast.
- **Header standards:** the IETF `RateLimit-*` headers (draft) tell clients their budget; older systems used `X-RateLimit-*`.
- **Cost-based limiting:** weight expensive endpoints more (a GraphQL query may cost N "points"). GraphQL especially needs **query depth/complexity limits** to prevent a single deep query from exhausting the server.
- **Concurrency limits** (in-flight requests) complement rate limits for slow/expensive operations.

### 7.5 Anti-enumeration & timing

- Return identical responses (status + timing) for "wrong password" vs "user doesn't exist" to prevent **account enumeration**.
- Use **constant-time comparison** for secrets/tokens/HMACs (`MessageDigest.isEqual`) to defeat **timing attacks** that leak how many bytes matched.

### 7.6 GraphQL & gRPC specifics

- **GraphQL**: a single endpoint with arbitrary client-shaped queries → authZ must be enforced **per field/resolver**, not per route; disable introspection in prod (or restrict); enforce query depth/complexity/cost limits; beware **batching attacks** (one request triggering thousands of resolvers — alias-based amplification) and use persisted queries.
- **gRPC**: uses HTTP/2 + protobuf; authZ via interceptors; mTLS is natural; rate-limit and validate at the interceptor layer.

### 7.7 API gateway as a security control point (and its limits)

The gateway is the ideal place for *coarse, business-agnostic* controls: TLS, JWT validation, global/per-key rate limits, schema validation, header injection/stripping, IP rules, request logging. **But it cannot do business-aware object-level authZ** (it doesn't know "Alice owns order 42"). So:
- Enforce **coarse** at the gateway, **fine** in the app (defense-in-depth, not duplication of intent).
- The gateway should **strip client-supplied trust headers** (`X-User-Id`) and inject *validated* ones, so the app can trust them — but the app should still treat them carefully (zero-trust: prefer re-validating the token at the service in high-assurance setups).
- Externalized authZ (`ext_authz` in Envoy → OPA) can move *policy* to the edge while keeping it centralized.

### 7.8 WAF internals & tuning

- **Negative security model** (CRS): block known-bad signatures. Fast to deploy, bypassable, false positives.
- **Positive security model**: allow only what matches a schema (OpenAPI-aware WAFs). Stronger but needs an accurate spec.
- **Paranoia levels** (CRS PL1–PL4): higher = more rules = more catches *and* more false positives. Tune per app; run in **detection/log-only** mode first, then enforce.
- **Anomaly scoring**: CRS sums rule hits; block when score crosses a threshold (reduces single-rule false positives).
- **Bypass realities**: encoding tricks, request smuggling, HTTP/2 downgrade — never rely on WAF as the only control.

### 7.9 Bot & abuse mitigation internals

- **Fingerprinting**: TLS/JA3 fingerprints, HTTP header order, browser/device fingerprints to spot non-browser or spoofed clients.
- **Behavioral analysis**: request velocity, navigation patterns, mouse/timing entropy.
- **Challenges**: CAPTCHA, proof-of-work, invisible challenges (managed bot products), step-up auth.
- **Credential-stuffing defense**: breached-password checks (k-anonymity range query against Have I Been Pwned), device binding, risk-based auth.
- **Trade-off**: aggressive bot blocking harms legitimate automation/partners and accessibility; use allow-lists for known good bots and partners.

### 7.10 Secrets, key rotation, and zero trust

- **Overlapping keys**: publish old+new in JWKS during rotation so in-flight tokens validate; remove old after max TTL.
- **Dynamic secrets** (Vault): short-lived DB credentials minted per workload, auto-revoked.
- **Workload identity** (SPIFFE/SPIRE): cryptographic identity per service for mTLS, short-lived SVIDs, no long-lived secrets.
- **Zero-trust**: never trust the network; authenticate and authorize *every* hop (mTLS + token), even internal.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing an authentication mechanism

| Mechanism | Best for | Pros | Cons | Avoid when |
|---|---|---|---|---|
| **API key** | Simple server-to-server, partner identification, low-sensitivity | Trivial to use; easy to issue | No user identity; weak rotation/scoping; leaks easily | Anything needing per-user authZ or strong assurance |
| **OAuth2 + OIDC (JWT)** | User-facing apps, SPAs, mobile, federated login | Standard, scalable, delegated, stateless | Complex; revocation hard; JWT footguns | Tiny internal tools where it's overkill |
| **OAuth2 Client Credentials** | Service-to-service with central AS | Centralized, scoped, rotatable | Needs an AS; token plumbing | When mTLS identity suffices and no AS exists |
| **mTLS** | Internal service mesh, high-assurance partners | Strong, phishing-resistant, no bearer theft | Cert lifecycle (issue/rotate/revoke) is heavy | Public consumer clients (cert distribution impractical) |

**Rule of thumb:** consumer/user APIs → **OAuth2 Authorization Code + PKCE + OIDC**; server-to-server → **mTLS** (in a mesh) or **Client Credentials**; quick partner identification → **API key** (plus rate limits), never as the sole control for sensitive data.

### 8.2 JWT vs opaque token

| | JWT (self-contained) | Opaque (reference) |
|---|---|---|
| Validation | Local (signature) | Introspection (network) |
| Latency | Low | Higher (cache) |
| Scale | Excellent (stateless) | AS becomes a dependency |
| Revocation | Hard (TTL/denylist) | Immediate |
| Payload privacy | Readable | Opaque |
| **Use when** | High scale, can tolerate TTL-based revocation | Need instant revocation, smaller scale |

### 8.3 Where to enforce each control

| Control | Edge/WAF | Gateway | Application | Data layer |
|---|---|---|---|---|
| TLS termination | ✔ | ✔ | | |
| IP reputation / DDoS | ✔ | | | |
| Generic attack signatures | ✔ (WAF) | | | |
| AuthN (token validation) | | ✔ | ✔ (re-validate, high assurance) | |
| Coarse authZ (scope/route) | | ✔ | ✔ | |
| **Object-level authZ (BOLA)** | | | ✔ (only place it can be) | ✔ (row scoping) |
| Property-level authZ | | | ✔ | |
| Rate limit (global/per-IP) | ✔ | ✔ | | |
| Rate limit (per-user/tenant) | | ✔ | ✔ | |
| Input schema validation | | ✔ (coarse) | ✔ (authoritative) | |
| Parameterized queries | | | ✔ | ✔ |
| Output DTO filtering | | | ✔ | |

### 8.4 RBAC vs ABAC vs ReBAC

| Model | Decision basis | Best for | Cost |
|---|---|---|---|
| **RBAC** | Roles | Simple, stable permission sets | Low; coarse |
| **ABAC** | Attributes (user, resource, env) | Context-dependent rules (time, location, ownership) | Medium; policy mgmt |
| **ReBAC** | Relationships in a graph | Sharing/collaboration (docs, teams, folders) | Higher; needs a system (OpenFGA/Zanzibar) |

Most real systems are **RBAC for function-level + ABAC/ReBAC for object-level** (ownership/sharing).

### 8.5 Build vs buy the perimeter

- **Build (in-app filters, Bucket4j, manual headers):** full control, no vendor cost, fine for simple/internal APIs.
- **Buy (managed gateway + WAF + bot management):** offloads DDoS, signatures, bot detection, and global rate limiting; per-request cost; less customization. Use when you face real internet-scale abuse.

---

## 9. Failure modes & debugging

### 9.1 BOLA leak in production

- **Symptom:** users report seeing others' data; spike in `GET /resource/{id}` across many IDs from one principal; many sequential `200`s on unowned objects.
- **Diagnose:** audit logs correlating `principal` vs `object.ownerId`; reproduce with two test accounts in Burp/ZAP by swapping IDs; check for missing ownership predicates in queries.
- **Fix:** add query-scoped ownership (`findByIdAndOwnerId`); add a centralized authZ check; add a regression test that fails if user B can read user A's object.
- **Real-world shape:** the canonical pattern behind many mobile/fintech breaches — a valid token plus an incrementable account/record ID.

### 9.2 JWT validation gaps

- **Symptom:** forged or expired tokens accepted; tokens from one service accepted by another.
- **Diagnose:** craft tokens with jwt_tool (`alg:none`, swapped `aud`, expired `exp`); confirm rejection. Check verifier config for pinned alg, `aud`, `iss`, `exp` leeway.
- **Fix:** enforce all claim checks; pin algorithm; validate `aud`.

### 9.3 CORS misconfiguration

- **Symptom (dev):** browser console "blocked by CORS policy"; **(security):** any origin can read responses.
- **Diagnose:** inspect `Access-Control-Allow-Origin` / `Allow-Credentials` in responses; test with a malicious origin. If the server *echoes* arbitrary origins with credentials → vulnerable.
- **Fix:** explicit origin allow-list; never `*` with credentials.

### 9.4 Rate limiter not working at scale

- **Symptom:** limits enforced per node, not globally; abuse continues because each of N nodes allows the full quota.
- **Diagnose:** observe that effective limit ≈ N × configured; check whether counters are in-memory vs shared (Redis).
- **Fix:** move counters to shared Redis (atomic Lua); or enforce at a single gateway tier.

### 9.5 Mass assignment / privilege escalation

- **Symptom:** users gaining roles/balances they shouldn't; audit shows privileged fields changed via normal update endpoints.
- **Diagnose:** test by adding extra fields (`"role":"ADMIN"`) to update payloads; check whether they take effect.
- **Fix:** request DTOs; `fail-on-unknown-properties=true`; never bind entities.

### 9.6 Excessive data exposure

- **Symptom:** sensitive fields visible in API responses or in client memory even if UI hides them.
- **Diagnose:** inspect raw responses (not the rendered UI) in Burp; grep for `passwordHash`, `ssn`, internal flags.
- **Fix:** response DTOs; redact in serialization and logs.

### 9.7 Token theft / replay

- **Symptom:** a leaked bearer token used from a new IP/device; refresh-token reuse.
- **Diagnose:** anomaly detection on token usage (geo/IP/device); refresh-token reuse detection.
- **Fix:** short TTLs; refresh rotation + reuse detection (revoke family); sender-constrained tokens (DPoP/mTLS).

### 9.8 SSRF via webhook/import features

- **Symptom:** outbound requests from your servers to `169.254.169.254` or internal IPs.
- **Diagnose:** egress logs; reproduce with a payload pointing at metadata/internal hosts.
- **Fix:** validate/allow-list target URLs; block private/loopback/link-local ranges and metadata IP; pin resolved IP (DNS-rebinding).

### 9.9 Debugging toolbox

| Need | Tool/command |
|---|---|
| Inspect a JWT | jwt.io, `jwt_tool`, `cat token | cut -d. -f2 | base64 -d` |
| Replay/tamper requests | Burp Suite, OWASP ZAP, `curl` |
| Check headers | `curl -i https://api/...`, browser devtools |
| Verify TLS/mTLS | `openssl s_client -connect host:443 -cert client.pem -key client.key` |
| Test CORS | `curl -H "Origin: https://evil.com" -i ...` (look at ACAO) |
| Hit rate limit | `for i in {1..200}; do curl -s -o /dev/null -w "%{http_code}\n" url; done` |
| Find vuln deps | `dependency-check`, `snyk test`, `trivy fs .` |
| Scan running API | `zap-api-scan.py -t openapi.json` |

---

## 10. Interview drill

**Q1. What is BOLA/IDOR and why is it the #1 API vulnerability? How do you prevent it?**
Model answer: BOLA (Broken Object Level Authorization) is when an authenticated caller accesses an object they don't own by manipulating its identifier — the server checks authN but not per-object authZ. It's #1 because authN feels like security so the object check gets skipped, the check is per-resource business logic frameworks can't auto-generate, and sequential IDs make it point-and-click. Prevent with server-side object-level checks on every access, ideally query-scoped (`findByIdAndOwnerId`) so a slip can't leak, return `404` for unauthorized objects, and centralize complex ownership logic.
- *Probe: Do UUIDs fix it?* No — obscurity isn't authorization; a leaked/logged/shared UUID is still exploitable. Still check ownership.
- *Probe: 403 or 404?* Prefer `404` to avoid confirming the object exists (anti-enumeration), where existence is sensitive.
- *Probe: How for list endpoints?* Scope the query (`findAllByOwnerId`) and `@PostFilter` only as a backstop.

**Q2. API keys vs OAuth2 vs mTLS — when do you use each?**
Model answer: API keys identify an application/account; simple but weak on rotation/scoping/user identity — fine for partner identification with rate limits, not for sensitive per-user authZ. OAuth2 (+OIDC) is the standard for user-facing/federated access: delegated, scoped, scalable; use Authorization Code + PKCE for user clients, Client Credentials for service-to-service. mTLS authenticates both peers cryptographically — best for internal mesh/high-assurance partners, but cert lifecycle is heavy.
- *Probe: OAuth2 vs OIDC?* OAuth2 = authorization (access); OIDC layers identity (ID token, login) on top.
- *Probe: Why is mTLS impractical for consumer apps?* Cert distribution/rotation to millions of untrusted devices is infeasible.

**Q3. Walk me through validating a JWT. What attacks must you defend against?**
Model answer: Split header.payload.signature; read `alg`/`kid`; pin the expected algorithm and reject `none`; resolve the key from cached JWKS by `kid`; verify the signature; validate `exp`/`nbf`/`iss`/`aud` with small clock skew; optionally check revocation. Attacks: `alg:none`, RS256→HS256 algorithm confusion (public key used as HMAC secret), `jku`/`kid` injection, missing `aud`/`iss` checks, weak HMAC secrets.
- *Probe: How do you revoke a JWT?* Short TTL, denylist of `jti`, token versioning, or hybrid with revocable refresh tokens.
- *Probe: Why not pick alg from the token?* That enables algorithm confusion; configure exactly one expected alg.

**Q4. Is CORS a security control? Explain precisely.**
Model answer: CORS is a *browser-enforced relaxation of the Same-Origin Policy* that governs whether JavaScript on one origin may *read* responses from another. It protects the *user's browser-side data*, not your server. Non-browser clients ignore it. So CORS is not authN/authZ; misconfiguring it (`*` with credentials, echoing arbitrary origins) is a vuln, but a strict policy never replaces real authorization. The request still reaches your server regardless of CORS.
- *Probe: Why can't `*` be used with credentials?* The spec forbids it; it would let any site read authenticated responses.
- *Probe: What's a preflight?* An `OPTIONS` request the browser sends for non-simple requests to ask permission first.

**Q5. Differentiate mass assignment and excessive data exposure, and how DTOs fix both.**
Model answer: Mass assignment (write side) is binding client input to fields it shouldn't set (e.g., `role`, `balance`). Excessive data exposure (read side) is returning fields the client shouldn't see (e.g., `passwordHash`). DTOs fix both: a request DTO has *only* writable fields (the dangerous ones don't exist to bind), and a response DTO has *only* visible fields (secrets aren't there to leak). Add `fail-on-unknown-properties=true` to reject unexpected inbound fields.
- *Probe: Why not annotate the entity?* Possible (`@JsonProperty(access=...)`), but error-prone; DTOs make the contract explicit and fail-safe.

**Q6. Compare rate-limiting algorithms and the distributed-enforcement tradeoff.**
Model answer: Fixed window (simple, edge-burst flaw), sliding-window log (exact, memory-heavy), sliding-window counter (cheap approximation, common), token bucket (bursts up to B then sustains r — popular), leaky bucket (perfectly smooth output). Distributed: a global exact limit needs shared state (Redis, atomic Lua) adding latency; per-node limits avoid the round-trip but the effective limit is nodes×per-node. Many gateways use Redis sliding-window-counter as the balance.
- *Probe: How do you protect a GraphQL endpoint?* Cost/complexity/depth limits, not just request counts.
- *Probe: What's denial-of-wallet?* Abuse that inflates usage-billed cost rather than causing downtime; quotas defend it.

**Q7 (senior-signal). Where do you enforce authorization — gateway or application — and why?**
Model answer: Coarse, business-agnostic controls (TLS, token validation, scope/route checks, global rate limits, schema validation) belong at the gateway for defense-in-depth and consistency. But **object-level authorization must live in the application** because only it knows business ownership ("Alice owns order 42"); the gateway can't. So: coarse at edge/gateway, fine in app, with row-level scoping at the data layer as a backstop. Treat injected trust headers carefully; in high-assurance setups re-validate the token at the service (zero trust).
- *Probe: Risk of trusting gateway-injected `X-User-Id`?* If an attacker can reach the service directly or spoof the header, they impersonate; strip client headers, prefer re-validation.
- *Probe: How do you centralize policy without scattering checks?* Externalized authZ (OPA/Rego via `ext_authz`) or a ReBAC service (OpenFGA).

**Q8 (senior-signal). Stateless JWTs scale beautifully but can't be revoked. How do you design around that?**
Model answer: Accept that statelessness trades away instant revocation. Mitigate with short access-token TTLs (minutes) plus revocable refresh tokens; add a `jti` denylist in Redis for emergency revocation until expiry; consider token versioning for "log out everywhere." If immediate revocation is a hard requirement, use opaque tokens with introspection (cache briefly) — accepting AS coupling and latency. Choose per the revocation SLA vs scale needs.
- *Probe: Cost of the denylist?* A per-request Redis lookup — partially erodes statelessness; keep it small (only revoked, short-TTL entries).
- *Probe: Refresh-token theft?* Rotate on use and detect reuse to revoke the whole family.

**Q9 (senior-signal). A partner integration needs strong, non-repudiable service-to-service auth across orgs. API key, OAuth2 client credentials, or mTLS? Justify.**
Model answer: For *strong* and *non-repudiable* cross-org auth, mTLS is the strongest — cryptographic peer identity, no stealable bearer token, phishing-resistant — if both orgs can manage cert issuance/rotation/revocation (Vault/PKI, OCSP). If a shared AS exists, OAuth2 client credentials gives centralized scoping/rotation with less PKI burden but uses bearer tokens (consider mTLS-bound or DPoP to constrain them). API keys are insufficient for non-repudiation (shared secret, weak rotation). I'd choose mTLS, or OAuth2 client credentials with sender-constrained tokens if PKI ops are a blocker, plus per-partner rate limits and audit logging either way.
- *Probe: How handle revocation in mTLS?* Short-lived certs (SPIFFE/SPIRE) or OCSP/CRL; prefer short TTLs to avoid revocation-checking pain.
- *Probe: Why constrain bearer tokens?* A plain bearer token is replayable if stolen; mTLS-binding/DPoP ties it to a key.

**Q10. How do you defend authentication endpoints specifically?**
Model answer: Rate-limit and add backoff/lockout on login/token/reset; CAPTCHAs or step-up on anomalies; check breached passwords (k-anonymity HIBP); constant-time credential comparison and identical responses for "bad password" vs "no such user" (anti-enumeration); strong password hashing (Argon2id/bcrypt); MFA; refresh-token rotation with reuse detection; alert on credential-stuffing patterns.
- *Probe: Why constant-time compare?* To prevent timing attacks that leak how many bytes matched.

**Q11. What is SSRF and how do you prevent it in an API that fetches user URLs?**
Model answer: SSRF tricks *your server* into requesting attacker-chosen URLs, often hitting internal services or cloud metadata (`169.254.169.254`). Prevent by allow-listing schemes/hosts, blocking private/loopback/link-local ranges and the metadata IP, pinning the resolved IP to defeat DNS rebinding, disabling redirects to internal targets, and using least-privilege egress/network policies.
- *Probe: Why is DNS rebinding tricky?* The hostname resolves public at validation, private at connect; resolve once and connect to that IP.

**Q12. What belongs in an API security audit log, and what must never?**
Model answer: Log principal, action, object ID, decision (allow/deny), source IP, timestamp, correlation/trace ID — append-only, retained per compliance. Never log secrets, tokens, passwords, full card/PII in cleartext; redact. Use logs to detect BOLA (principal vs owner), enumeration (`404` spikes), and abuse (`429` patterns).
- *Probe: How correlate across services?* Propagate a trace/correlation ID through gateway → services; return it to clients for support without leaking internals.

---

## 11. Glossary

- **AAA** — Authentication, Authorization, Auditing/Accounting.
- **ABAC** — Attribute-Based Access Control; decisions from attributes (user/resource/env).
- **Access token** — short-lived bearer token used to call a resource server.
- **Allow-list / deny-list** — permitting only known-good vs blocking known-bad; prefer allow-lists.
- **API** — network-exposed programmatic interface (endpoints accepting structured requests).
- **API gateway** — reverse proxy centralizing API cross-cutting concerns (authN, rate limit, routing).
- **API key** — long random string identifying an app/account on each call.
- **Argon2id / bcrypt / scrypt** — slow, salted password-hashing algorithms.
- **Audience (`aud`)** — JWT claim naming the intended recipient API.
- **Authentication (authN)** — proving claimed identity.
- **Authorization (authZ)** — deciding what a proven identity may do.
- **Authorization Code + PKCE** — modern OAuth2 flow for user-facing clients.
- **Authorization Server (AS)** — issues OAuth2/OIDC tokens.
- **Bearer token** — token usable by anyone who holds it.
- **BOLA** — Broken Object Level Authorization (OWASP API1); = IDOR.
- **Bot mitigation** — distinguishing legitimate clients from automated abuse.
- **CA (Certificate Authority)** — trusted issuer of X.509 certificates.
- **Claim** — a statement inside a token (e.g., `sub`, `role`).
- **Client Credentials** — OAuth2 flow for service-to-service (no user).
- **CORS** — Cross-Origin Resource Sharing; browser-enforced relaxation of SOP.
- **CRL / OCSP** — certificate revocation list / online revocation check.
- **CRS (Core Rule Set)** — OWASP generic WAF rule set for ModSecurity.
- **CSP (Content-Security-Policy)** — header restricting page resource loads.
- **CSRF** — Cross-Site Request Forgery; tricking a browser into sending an authenticated request; mitigated by tokens or non-cookie auth.
- **DAST** — Dynamic Application Security Testing (tests running app).
- **Defense-in-depth** — layered, independent controls.
- **Denial-of-wallet** — abuse that inflates usage-billed cost.
- **DPoP** — Demonstrating Proof of Possession; sender-constrains tokens (RFC 9449).
- **DTO** — Data Transfer Object; request/response shape separate from entities.
- **Excessive data exposure** — returning fields the caller shouldn't see (OWASP API3).
- **Function-level authZ** — may the caller invoke this operation at all (OWASP API5).
- **gRPC** — RPC framework over HTTP/2 + protobuf.
- **HSTS** — Strict-Transport-Security header forcing HTTPS.
- **IdP** — Identity Provider.
- **IDOR** — Insecure Direct Object Reference; classic name for BOLA.
- **Injection** — untrusted input interpreted as code by a downstream interpreter.
- **Introspection** — RS asking the AS whether an opaque token is valid (RFC 7662).
- **Issuer (`iss`)** — JWT claim naming the token's issuer.
- **JWKS** — JSON Web Key Set; published public keys for JWT verification.
- **JWT** — JSON Web Token; signed, self-contained token (`header.payload.signature`).
- **Leaky/Token bucket** — rate-limiting algorithms (smooth output / burst-then-sustain).
- **Mass assignment** — binding client input to fields it shouldn't set (OWASP API3).
- **MFA** — Multi-Factor Authentication.
- **mTLS** — mutual TLS; both peers present certificates.
- **OAuth2** — authorization-delegation framework issuing access tokens.
- **OIDC** — OpenID Connect; identity layer on OAuth2 (ID token, login).
- **OPA / Rego** — Open Policy Agent and its policy language.
- **Opaque token** — reference token validated via introspection (revocable).
- **Origin** — (scheme, host, port) triple.
- **OWASP** — Open Worldwide Application Security Project.
- **PKCE** — Proof Key for Code Exchange; secures OAuth2 code flow for public clients.
- **Preflight** — browser `OPTIONS` request asking CORS permission.
- **Principal** — the authenticated identity in the app's security context.
- **Property-level authZ** — read/write permission per field.
- **Quota** — long-horizon usage cap (e.g., per month).
- **Rate limiting** — capping requests per unit time.
- **RBAC** — Role-Based Access Control.
- **ReBAC** — Relationship-Based Access Control (e.g., Zanzibar/OpenFGA).
- **ReDoS** — Regular-expression Denial of Service via catastrophic backtracking.
- **Refresh token** — longer-lived token to obtain new access tokens.
- **Resource server (RS)** — your API that validates access tokens.
- **Reverse proxy** — server forwarding client requests to backends.
- **ROPC / Implicit** — deprecated OAuth2 flows.
- **SAST / SCA** — static testing / software composition analysis.
- **Scope** — coarse permission label in a token.
- **Same-Origin Policy (SOP)** — browser rule isolating origins.
- **Sender-constrained token** — token bound to a client key (DPoP, mTLS).
- **SPIFFE/SPIRE** — workload-identity standard and implementation.
- **SSRF** — Server-Side Request Forgery (OWASP API7).
- **STRIDE** — threat taxonomy (Spoofing, Tampering, Repudiation, Info disclosure, DoS, Elevation).
- **TLS** — Transport Layer Security (HTTPS).
- **Token bucket** — see Leaky/Token bucket.
- **WAF** — Web Application Firewall.
- **X.509** — certificate standard binding a public key to a subject.
- **XSS** — Cross-Site Scripting.
- **Zero trust** — never trust the network; authenticate/authorize every hop.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**OWASP API Top 10 (2023):** 1 BOLA · 2 Broken AuthN · 3 Broken Object Property AuthZ (mass assignment + excessive exposure) · 4 Unrestricted Resource Consumption · 5 Broken Function AuthZ · 6 Unrestricted Sensitive Flows · 7 SSRF · 8 Misconfig · 9 Inventory · 10 Unsafe Consumption.

**The one rule:** *AuthN ≠ authZ. Check object ownership on every access* (`findByIdAndOwnerId`), return **404** for unauthorized.

**AuthN choice:** user apps → OAuth2 Auth Code + PKCE + OIDC; service→service → mTLS / client credentials; partner ID → API key + rate limit (never sole control for sensitive data).

**JWT validation:** pin alg (reject `none`), verify signature via JWKS `kid`, check `exp`/`nbf`/`iss`/`aud` (skew 30–60s). Attacks: `alg:none`, RS256→HS256 confusion, `jku`/`kid` injection, missing `aud`.

**Tokens:** short access TTL (5–15 min) + rotating refresh; JWT = scalable but hard to revoke; opaque = revocable but needs introspection (cache).

**Mass assignment / exposure:** dedicated request & response DTOs; `fail-on-unknown-properties=true`; never bind/serialize entities.

**Input:** allow-list (type/range/length/enum/format); parameterized queries; cap body/array/depth; ReDoS-safe regex; canonicalize first.

**CORS:** browser-only, not authZ; specific origins; never `*` with credentials.

**Rate limit:** token bucket (burst B then rate r) most common; distributed → Redis atomic; global exact = latency vs per-node approx (= nodes×limit). Return `429` + `Retry-After`. Quotas defend denial-of-wallet.

**Headers:** `nosniff`, HSTS, `Cache-Control: no-store`, `CSP default-src 'none'`, `Content-Type: application/json`.

**Enforce where:** coarse (TLS, token, scope, global rate, schema) at WAF/gateway; **fine (object/property authZ) in the app**; row scoping at DB.

**Don't:** trust client-supplied identity; verbose errors; secrets in code; long-lived unrevocable tokens; CORS-as-security; deny-list filtering.

**Hardening checklist:** ✔ TLS everywhere ✔ object-level authZ on every object ✔ function-level authZ on every privileged op ✔ DTOs in/out ✔ validate all input (allow-list) ✔ parameterized queries ✔ rate limit + quota (esp. auth endpoints) ✔ strict CORS ✔ security headers ✔ generic errors ✔ JWT claim validation + alg pinning ✔ short token TTL + rotation ✔ secrets in vault + rotation ✔ audit logs (no secrets) ✔ API inventory (no shadow/zombie endpoints) ✔ SSRF egress controls ✔ SAST/DAST/SCA in CI ✔ WAF + bot mitigation at edge.

### Self-test (no answers — recall actively)

1. Explain precisely why authenticating a request does **not** make returning the requested object safe, and show the minimal code change that closes the gap for `GET /orders/{id}`.
2. An interviewer says "we use UUIDs, so we don't have IDOR." Rebut this in three sentences with a concrete exploit scenario.
3. Walk through every check a correct JWT validator performs, and name the specific attack each check defeats.
4. You run 6 gateway instances each configured for 100 req/min per key, yet a single key sustains ~600 req/min. Explain why and give two fixes with their tradeoffs.
5. Design the request and response DTOs (and one config flag) that prevent both privilege escalation via `role` and leakage of `passwordHash` for a user-profile update endpoint — and explain why DTOs are safer than per-field annotations.
6. Your API has a webhook feature that fetches a user-supplied URL. List the exact target-validation steps to prevent SSRF, including the DNS-rebinding mitigation.
7. State, for each layer (WAF/edge, gateway, application, data), which authorization concerns it can and cannot enforce, and justify why object-level authZ cannot live at the gateway.

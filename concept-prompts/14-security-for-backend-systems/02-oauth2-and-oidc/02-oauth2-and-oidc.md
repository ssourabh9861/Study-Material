# OAuth2 & OpenID Connect — A Definitive Engineering Handbook Chapter

> **Reader profile:** A senior JVM/backend developer who wants to *master* OAuth2 and OIDC — to design with them, operate and debug them in production, teach them, and answer any interview question. This chapter starts from zero and climbs to deep internals. Every adjacent term is defined inline the first time it matters.

---

## 1. Overview & where it fits

### 1.1 The one-sentence definition

**OAuth 2.0** is a *delegated authorization* framework: it lets a user (or a service) grant a third-party application **limited access** to resources it does **not own**, without sharing a password. **OpenID Connect (OIDC)** is a thin **authentication** layer built *on top of* OAuth 2.0 that answers the separate question "who is this user, and can I prove they just logged in?"

Two different questions, often confused:

- **Authorization (AuthZ):** "What is this caller *allowed to do*?" → OAuth 2.0's job.
- **Authentication (AuthN):** "*Who* is this caller, and how recently did they prove it?" → OIDC's job (OAuth alone deliberately does **not** answer this).

If you remember nothing else: **OAuth2 is about access (tokens that grant permission); OIDC is about identity (a token that asserts who logged in).**

### 1.2 The problem OAuth2 solves

Before OAuth, the only way to let App B act on your behalf at App A was the **password anti-pattern**: you handed App B your App A username and password. App B then logged in *as you*. This is catastrophic:

- App B now has **full** access — it can do anything you can, not just the one thing you wanted.
- App B must **store your password** (usually in plaintext or reversibly), a giant breach target.
- You cannot **revoke** App B without changing your password — which breaks every other integration.
- There is **no scoping** ("read my contacts" vs "delete my account") and **no audit** of which app did what.

The canonical scenario: a photo-printing site (the **client**) wants to fetch your photos from a photo-storage service (the **resource server**). You want to say "yes, this printer may *read* my photos, nothing else, and I can cut it off anytime." OAuth2 makes that possible by introducing a **token** — a credential that represents *a specific, scoped, revocable, time-bounded grant* — instead of sharing the password.

### 1.3 When you reach for OAuth2 / OIDC

| Situation | Use |
|---|---|
| Let a third-party app access *your users'* data with their consent | **OAuth2** (authorization code + PKCE) |
| "Log in with Google/GitHub/Apple" (federated login / SSO) | **OIDC** (authorization code + PKCE) |
| Your own SPA or mobile app talking to your own API | **OAuth2/OIDC** (authorization code + PKCE; public client) |
| Service-to-service / machine-to-machine, no user present | **OAuth2 client credentials** grant |
| TV / CLI / IoT device with no browser or keyboard | **OAuth2 device authorization** grant |
| You just need an opaque API key for internal cron jobs | Plain API key may be simpler — don't over-engineer |
| You need fine-grained, relationship-based authorization (ReBAC) | OAuth gives you the *token*; pair it with a policy engine (OPA, Zanzibar-style) for the *decision* |

> **SSO (Single Sign-On):** a user authenticates once with a central identity provider and is then signed in to many applications without re-entering credentials. OIDC is the modern protocol that powers SSO on the web.
>
> **Federated login / federation:** delegating authentication to an external **Identity Provider (IdP)** you trust (Google, Okta, Azure AD/Entra ID, etc.) instead of running your own password database.

### 1.4 The one-paragraph mental model

Think of OAuth2 as a **valet key** for software. Your car's valet key starts the engine and opens the door but won't open the trunk or glovebox — it's a *scoped, limited* credential you hand to someone you don't fully trust, and you can stop using it later. The **resource owner** (you) asks the **authorization server** (the front desk that knows you) to mint a **valet key** (the **access token**) for a specific **client** (the valet), good for specific actions (**scopes**) and a limited time. The valet presents that key to the **resource server** (the parking garage), which checks the key is valid and honors only the permitted actions. OIDC adds a **signed photo ID** (the **ID token**) so the client can also *know who the user is*, not just *what they may do*.

---

## 2. Foundations from first principles

### 2.1 The four roles (memorize these)

OAuth 2.0 (defined in **RFC 6749**, the core spec; "RFC" = Request for Comments, the IETF's standards documents) defines four roles. Almost every confusion about OAuth dissolves once you can name which role each box in your diagram plays.

1. **Resource Owner (RO):** the entity that owns the data and can grant access to it. Usually a **human user**. (In client-credentials flows there is *no* resource owner — the client owns the data itself.)

2. **Client:** the application that *wants* access to the protected resource on the resource owner's behalf. "Client" here means *the requesting app*, **not** the end-user's browser device per se. Examples: a mobile app, a single-page web app, a backend web server, a CLI.

3. **Authorization Server (AS):** the server that authenticates the resource owner, obtains their consent, and **issues tokens**. Examples: Google's accounts service, Okta, Auth0, Keycloak, Microsoft Entra ID, AWS Cognito, Spring Authorization Server. The AS exposes endpoints like `/authorize` and `/token`.

4. **Resource Server (RS):** the server hosting the protected resources (the API). It accepts an access token, **validates** it, and returns data if the token is valid and sufficiently scoped. In small systems the AS and RS may be the same deployment; conceptually they are distinct.

> **Why split AS and RS?** Separation lets one AS issue tokens for *many* resource servers (your AS issues a token that works against `orders-api`, `billing-api`, and `users-api`). It also concentrates the sensitive credential-handling and key management in one hardened component.

### 2.2 Confidential vs public clients

This distinction drives *which grant and which security measures you must use.*

- **Confidential client:** can keep a secret confidential — it runs on a server you control (a backend web app, a daemon). It is issued a **client secret** (a password for the app itself) and authenticates to the AS.
- **Public client:** *cannot* keep a secret — its code ships to the user (a SPA's JavaScript, a mobile app's binary, a desktop app). Anyone can decompile it and extract any embedded "secret," so it gets **no usable secret** and must use **PKCE** (Section 2.7) to protect its flows.

> **SPA (Single-Page Application):** a web app (React/Angular/Vue) that runs in the browser and talks to APIs over HTTP; its source is fully visible to the user, hence "public."

### 2.3 The two front-channel/back-channel concepts

OAuth flows move data over two paths. Knowing which is which explains *why* certain attacks exist.

- **Front channel:** data passed *through the user's browser*, typically as **URL query parameters** or **redirects**. It is convenient (no server-to-server call needed) but **untrusted**: the user, browser extensions, and proxies can see and tamper with it, and values land in browser history and server logs. Authorization *requests* and *responses* travel the front channel.
- **Back channel:** a *direct, server-to-server* HTTPS call between the client's backend and the AS, never touching the browser. It is **trusted and confidential** (TLS-protected, no browser exposure). Token *exchange* (code → token) happens on the back channel for confidential clients.

> **The core security insight of modern OAuth:** keep *secrets and tokens* on the back channel; only let a *short-lived, single-use, useless-on-its-own* artifact (the authorization **code**) travel the front channel. PKCE exists to make even that code useless if stolen.

### 2.4 Endpoints you must know

| Endpoint | Channel | Purpose |
|---|---|---|
| **Authorization endpoint** (`/authorize`) | Front | Where the AS authenticates the RO and gets consent; returns an authorization **code** via redirect. |
| **Token endpoint** (`/token`) | Back | Where the client exchanges code (or refresh token, or client credentials) for tokens. |
| **UserInfo endpoint** (`/userinfo`) | Back | (OIDC) Returns claims about the authenticated user given an access token. |
| **JWKS endpoint** (`/.well-known/jwks.json`) | Back | Publishes the AS's public keys so resource servers can verify JWT signatures. |
| **Introspection endpoint** (`/introspect`, RFC 7662) | Back | RS asks AS "is this (opaque) token valid, and what's in it?" |
| **Revocation endpoint** (`/revoke`, RFC 7009) | Back | Client tells AS "invalidate this token." |
| **Discovery document** (`/.well-known/openid-configuration`) | Back | (OIDC) Machine-readable JSON listing all the above URLs, supported scopes, algorithms, etc. |

> **JWKS (JSON Web Key Set):** a JSON document listing the public keys (each with a **`kid`**, key ID) the AS uses to sign tokens. A resource server fetches and caches this so it can verify signatures *offline* without calling the AS per request.

### 2.5 Tokens: access, refresh, and ID

Three token types. Confusing them is the single most common OAuth mistake.

#### Access token
- **Purpose:** the *valet key*. Presented by the client to the **resource server** to access an API.
- **Audience:** the **resource server** (the API), *not* the client. The client should treat it as opaque even if it can read it.
- **Lifetime:** short — minutes to ~1 hour typically (Google ~1h; many systems 5–15 min). Short life limits the blast radius of a leak.
- **Format:** either an **opaque** random string (the RS validates via introspection) **or** a **JWT** (the RS validates the signature locally). More in Section 2.6.
- **Presented as:** an HTTP header — `Authorization: Bearer <token>`.

> **Bearer token:** a token where *mere possession* grants access — like cash. Whoever holds it can use it; there is no built-in proof that the presenter is the legitimate owner. This is why bearer tokens must be short-lived and transported only over TLS. (The alternative, **sender-constrained** tokens — DPoP / mTLS — binds the token to a key the holder must prove; Section 7.)

#### Refresh token
- **Purpose:** a long-lived credential used to obtain *new* access tokens *without re-prompting the user*. When the access token expires, the client silently exchanges the refresh token at the `/token` endpoint for a fresh access token.
- **Audience:** the **authorization server** only. It is *never* sent to a resource server.
- **Lifetime:** long — hours to days to months, sometimes "until revoked."
- **Sensitivity:** high. A stolen refresh token is a durable foothold. Hence: store securely, prefer **rotation** (Section 7.3), and bind to a confidential client where possible.

#### ID token (OIDC only)
- **Purpose:** asserts *the identity of the user* to the **client**. It is a **JWT** containing claims like `sub` (subject = stable user ID), `name`, `email`, `iat`, `exp`, `iss`, `aud`, `nonce`, `auth_time`.
- **Audience:** the **client** (`aud` = client_id). The client *consumes and verifies* the ID token; it must **never** send the ID token to an API as an access token.
- **It is proof of authentication**, not authorization.

> **Claim:** a piece of information asserted about a subject, expressed as a name/value pair inside a token (e.g. `"email": "a@b.com"`). "Claims" is just the OAuth/OIDC word for the token's payload fields.

| Token | Audience | Carries | Lifetime | Sent to |
|---|---|---|---|---|
| **Access** | Resource server | Authorization (scopes) | Short (min–1h) | The API |
| **Refresh** | Authorization server | Ability to mint access tokens | Long | Only the AS token endpoint |
| **ID** | Client | Identity (who logged in) | Short | Nobody — consumed by client |

### 2.6 Opaque tokens vs JWT access tokens

**Opaque token (reference token):** a random string with no meaning by itself; it's a *handle* into the AS's database. To validate, the RS calls the AS's **introspection** endpoint.
- ✅ Trivial **instant revocation** (delete the row).
- ✅ No data leaks if the token is read.
- ❌ A network round-trip to the AS **per request** (mitigated by caching).

**JWT access token (self-contained token):** a signed JSON object the RS validates **locally** by checking the signature against the AS's public key (from JWKS).
- ✅ **No per-request call** to the AS — scales beautifully.
- ✅ Carries claims (scopes, sub, roles) the RS can read directly.
- ❌ **Hard to revoke before expiry** — it's valid until `exp` no matter what (you need denylists or very short lifetimes).
- ❌ Larger; if it carries sensitive data, that data is *readable* (Base64URL is not encryption).

> **JWT (JSON Web Token, RFC 7519):** a compact, URL-safe token of three Base64URL-encoded parts joined by dots: `header.payload.signature`. The header names the algorithm and `kid`; the payload holds claims; the signature (a **JWS**, JSON Web Signature) lets a verifier confirm integrity and authenticity. **Base64URL is encoding, not encryption** — anyone can read a JWT's payload at jwt.io. If you need the contents hidden, use a **JWE** (JSON Web Encryption).

### 2.7 PKCE — the modern default protection (say "pixie")

**PKCE = Proof Key for Code Exchange (RFC 7636).** It defends the authorization-code flow against the **authorization code interception attack**: on mobile/SPA, the redirect carrying the code can be hijacked (e.g. a malicious app registers the same custom URL scheme). PKCE makes a stolen code useless.

How it works (no shared secret needed — perfect for public clients):

1. Before starting, the client generates a high-entropy random string, the **`code_verifier`** (43–128 chars).
2. It computes the **`code_challenge`** = `BASE64URL(SHA-256(code_verifier))` and sends *that* (with `code_challenge_method=S256`) in the front-channel authorization request. The AS stores it against the issued code.
3. When the client later exchanges the code at the token endpoint (back channel), it sends the original **`code_verifier`**.
4. The AS recomputes `SHA-256(code_verifier)` and checks it equals the stored `code_challenge`. If they don't match, the exchange is rejected.

Because only the legitimate client knows the verifier, an attacker who steals the *code* from the front channel cannot exchange it. **PKCE is now recommended for ALL clients — public and confidential — by the OAuth 2.0 Security BCP and OAuth 2.1.**

> **SHA-256:** a cryptographic hash that maps any input to a fixed 256-bit digest; it is one-way (you cannot derive the input from the digest) and collision-resistant. The `S256` method uses it; `plain` (sending the verifier as-is) exists for constrained devices but **must be avoided** — it provides no protection.
>
> **Entropy:** the unpredictability/randomness of a value. "High-entropy" means generated from a cryptographically secure random source so it can't be guessed or brute-forced.

### 2.8 Scopes and consent

- **Scope:** a space-delimited string in the token request naming the *permissions* the client wants, e.g. `scope=read:contacts write:calendar`. The AS shows these to the user as a **consent** screen ("App X wants to: read your contacts"). Scopes are coarse-grained capability labels; the resource server enforces them.
- **Consent:** the user's explicit approval of the requested scopes. The AS records it so it needn't re-ask every time (subject to policy).

> Scopes are **not** the same as fine-grained authorization. `scope=read:documents` says "this token may read documents"; it does **not** say *which* documents. The RS still must check that *this user* may read *that specific* document. Treat scopes as a first filter, not the whole access-control story.

### 2.9 OIDC layered on OAuth2 — the precise difference

OAuth2 was *abused* for login: apps would get an access token, call a "userinfo"-like API, and assume "if I got a token, the user is logged in." This is insecure (the **confused deputy** / token-substitution problem: an access token issued for app A could be replayed to app B, which would wrongly treat it as a login). OIDC fixes this by standardizing authentication:

OIDC adds:
1. The **`openid` scope** — including it tells the AS "also do OIDC; issue an ID token."
2. The **ID token** — a JWT *for the client* with verifiable identity claims and crucially `aud` (so it can only be used by the intended client) and `nonce` (replay protection).
3. The **UserInfo endpoint** — standardized claims (`email`, `name`, `picture`, ...).
4. **Standard claims & scopes** — `profile`, `email`, `address`, `phone`.
5. The **discovery document** and standardized flows.

> **Rule of thumb:** *"If you want to log a user in, use OIDC (ask for `openid` scope, verify the ID token). If you want to call an API on the user's behalf, use OAuth2 (use the access token)."* You usually want both at once, and the same authorization-code+PKCE flow gives you both.

---

## 3. How it works internally — the heart of the chapter

We trace the flows step by step, byte level where it matters. Start with the modern default.

### 3.1 Authorization Code flow with PKCE — full end-to-end trace

**Actors:** user + browser (front channel), client backend (back channel), AS, RS.

**Pre-step (registration, one-time):** the client registers with the AS and gets a `client_id` (public) and, if confidential, a `client_secret`. It registers one or more **exact redirect URIs**.

**Step 0 — client prepares per-request secrets.**
```
code_verifier  = base64url(random 32 bytes)          // e.g. "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
code_challenge = base64url(sha256(code_verifier))     // e.g. "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
state          = base64url(random 16 bytes)           // CSRF protection, client-side
nonce          = base64url(random 16 bytes)           // OIDC replay protection (if logging in)
```

**Step 1 — authorization request (front channel).** The client redirects the browser to the AS:
```
GET https://as.example.com/authorize?
    response_type=code
   &client_id=s6BhdRkqt3
   &redirect_uri=https%3A%2F%2Fclient.example.com%2Fcallback
   &scope=openid%20profile%20read%3Acontacts
   &state=af0ifjsldkj
   &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
   &code_challenge_method=S256
   &nonce=n-0S6_WzA2Mj
```
- `response_type=code` → this is the code flow.
- `state` → an opaque value the client stores (in session); the AS echoes it back unchanged so the client can detect **CSRF** (Cross-Site Request Forgery — a forged request riding the user's session).

**Step 2 — AS authenticates the user & gets consent.** The AS shows a login page (if no active session) and a consent screen for the requested scopes. The user logs in and approves.

**Step 3 — authorization response (front channel redirect).** The AS redirects the browser back:
```
HTTP/1.1 302 Found
Location: https://client.example.com/callback?
    code=SplxlOBeZQQYbYS6WxSbIA
   &state=af0ifjsldkj
```
The **code** is short-lived (often ≤ 60 s, **single-use**) and useless without the verifier.

**Step 4 — client validates `state`.** The client compares the returned `state` to the value it stored. Mismatch ⇒ abort (possible CSRF). This step is mandatory.

**Step 5 — token request (BACK channel).** The client's *backend* POSTs directly to the AS:
```
POST https://as.example.com/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3   // confidential client only

grant_type=authorization_code
&code=SplxlOBeZQQYbYS6WxSbIA
&redirect_uri=https%3A%2F%2Fclient.example.com%2Fcallback
&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
```
- The `Authorization: Basic` header carries `base64(client_id:client_secret)` for confidential clients. Public clients omit it and rely on PKCE.
- The AS verifies: code exists & unexpired & unused; `redirect_uri` matches the original exactly; **`sha256(code_verifier) == stored code_challenge`**; client auth (if confidential).

**Step 6 — token response (back channel).** The AS returns JSON:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6Ii4uLiJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "8xLOxBtZp8",
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6Ii4uLiJ9...",
  "scope": "openid profile read:contacts"
}
```

**Step 7 — client verifies the ID token (OIDC).** The client validates:
- Signature against the AS's JWKS key with matching `kid`.
- `iss` == the expected issuer URL (exact string match).
- `aud` == the client's own `client_id`.
- `exp` not passed; `iat` reasonable; (`nbf` if present).
- `nonce` == the nonce it sent in Step 1 (replay protection).
- `auth_time` within policy if `max_age` was requested.

**Step 8 — call the resource server.** The client sends the **access token**:
```
GET https://api.example.com/contacts
Authorization: Bearer eyJhbGciOiJSUzI1NiIs...
```

**Step 9 — resource server validates the access token.**
- *If JWT:* verify signature via JWKS (cached), check `iss`, `aud` (must include this API's identifier), `exp`, and that required `scope`/roles are present.
- *If opaque:* call the introspection endpoint (cache the result for a few seconds).

**Step 10 — refresh when the access token expires.**
```
POST https://as.example.com/token
grant_type=refresh_token
&refresh_token=8xLOxBtZp8
&scope=openid profile read:contacts   // optional: may request a subset
```
The AS returns a new access token (and, with rotation, a new refresh token; Section 7.3).

### 3.2 State machine of a token's life

```
        issue                use (until exp)             refresh
[none] ───────► [active] ──────────────────────► [expired] ──────► [active(new)]
                  │                                                    │
                  │ revoke / logout                                    │ refresh-token revoked
                  ▼                                                    ▼
              [revoked] ◄──────────────────────────────────────── [revoked]
```
- **active:** within `exp`, not revoked. JWTs are "active" purely by `exp` unless you maintain a denylist.
- **expired:** past `exp`; RS rejects.
- **revoked:** explicitly invalidated (logout, breach, consent withdrawal). Easy for opaque tokens; needs extra machinery for JWTs.

### 3.3 Client Credentials grant (machine-to-machine) — trace

No user, no browser, no front channel. The client *is* the resource owner.
```
POST https://as.example.com/token
Authorization: Basic base64(client_id:client_secret)   // or mTLS, or private_key_jwt
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&scope=billing:read inventory:write
```
Response: an **access token only** (no refresh token — the client can just ask again; no ID token — there's no user). Used by backend service A to call backend service B. The token's `sub` is the client itself.

### 3.4 Device Authorization grant (RFC 8628) — trace

For input-constrained devices (smart TVs, CLIs, IoT). The device can't render a login form, so it offloads login to the user's phone/laptop.

1. **Device → AS** (`/device_authorization`): `client_id`, `scope`. AS returns:
```json
{ "device_code": "GmRh...long", "user_code": "WDJB-MJHT",
  "verification_uri": "https://example.com/device",
  "verification_uri_complete": "https://example.com/device?user_code=WDJB-MJHT",
  "expires_in": 1800, "interval": 5 }
```
2. **Device shows the user** the short `user_code` and `verification_uri` (or a QR code for `verification_uri_complete`).
3. **User**, on a *separate* device, opens the URL, logs in, enters/confirms the code, consents.
4. **Device polls** the token endpoint every `interval` seconds:
```
POST /token
grant_type=urn:ietf:params:oauth:grant-type:device_code
&device_code=GmRh...&client_id=...
```
Responses while waiting: `authorization_pending` (keep polling), `slow_down` (back off, increase interval), then finally the tokens. The device must respect `interval` to avoid being rate-limited.

### 3.5 (Deprecated) Implicit grant — and why it's dead

`response_type=token` returned the **access token directly in the front-channel redirect** (in the URL fragment), with *no* code exchange. Designed for browser apps before CORS and PKCE existed. **Deprecated** because:
- The access token lands in the URL → browser history, `Referer` headers, server logs, extensions.
- No client authentication and no PKCE protection.
- Tokens couldn't be refreshed safely.

**Replacement:** authorization code + PKCE for SPAs. OAuth 2.1 removes implicit entirely.

> **CORS (Cross-Origin Resource Sharing):** a browser security mechanism that controls whether JavaScript on origin A may call an API on origin B. Modern SPAs use CORS-enabled token endpoints to do the code→token exchange directly from the browser (still with PKCE), which made implicit unnecessary.

### 3.6 (Deprecated) Resource Owner Password Credentials (ROPC) — and why it's dead

`grant_type=password` had the client **collect the user's username and password directly** and post them to the token endpoint. This reintroduces the exact password anti-pattern OAuth was invented to kill: the client sees the password, can't do MFA/SSO/social login, and can't use a remote IdP. **Deprecated**; only ever (barely) acceptable for first-party legacy migration, never for third parties. OAuth 2.1 removes it.

> **MFA (Multi-Factor Authentication):** requiring more than one proof of identity (something you know + something you have/are). ROPC can't support it because the flow ends the instant the password is posted.

### 3.7 How JWT verification works under the hood (RS side)

When a resource server receives `Authorization: Bearer eyJ...`:
1. **Split** on `.` into header, payload, signature.
2. **Base64URL-decode** header → read `alg` (e.g. `RS256`) and `kid`.
3. **Resolve key:** look up `kid` in the cached JWKS; if absent, refetch JWKS from the AS (then cache). For `RS256`, the key is an RSA *public* key.
4. **Verify signature:** confirm `signature == sign(header.payload, AS_private_key)` using the *public* key. This proves the AS minted it and nobody altered it.
5. **Validate claims:** `iss` exact-match expected issuer; `aud` includes this API; `exp` in the future (allow small clock skew, ~30–60 s); `nbf`/`iat` sane; required `scope`/roles present.
6. **Reject `alg: none`** and reject algorithms you don't expect (algorithm-confusion defense; Section 9).

> **RS256 vs HS256:** `RS256` is **asymmetric** — the AS signs with a *private* key, everyone verifies with the *public* key (RS never holds a secret). `HS256` is **symmetric** — signer and verifier share one secret key. For distributed systems use `RS256`/`ES256` so resource servers never possess signing material. (`ES256` = ECDSA with P-256, smaller keys, same asymmetric property.)

---

## 4. The complete toolkit

### 4.1 Core OAuth2/OIDC parameters (request/response)

| Parameter | Endpoint | Meaning / default |
|---|---|---|
| `response_type` | /authorize | `code` (the only recommended value). `token`/`id_token` are implicit/hybrid — avoid. |
| `client_id` | /authorize, /token | Public client identifier. Required. |
| `redirect_uri` | /authorize, /token | Must **exactly** match a pre-registered URI. |
| `scope` | /authorize, /token | Space-delimited permissions. Include `openid` for OIDC. |
| `state` | /authorize | CSRF token; echoed back; client must verify. Strongly recommended. |
| `code_challenge` / `code_challenge_method` | /authorize | PKCE. Use `S256`, never `plain`. |
| `code_verifier` | /token | PKCE secret revealed at exchange. |
| `nonce` | /authorize | OIDC replay protection; must equal `id_token.nonce`. |
| `prompt` | /authorize | `none` (no UI; silent auth), `login` (force re-auth), `consent` (force consent), `select_account`. |
| `max_age` | /authorize | Max seconds since last auth; AS enforces, reflects in `auth_time`. |
| `grant_type` | /token | `authorization_code`, `refresh_token`, `client_credentials`, `urn:ietf:params:oauth:grant-type:device_code`. |
| `access_token` / `token_type` / `expires_in` / `refresh_token` / `id_token` / `scope` | /token response | The token bundle. `token_type` is `Bearer`. `expires_in` in seconds. |

### 4.2 Standard OIDC scopes & the claims they unlock

| Scope | Claims released |
|---|---|
| `openid` | (required for OIDC) `sub` |
| `profile` | `name`, `family_name`, `given_name`, `nickname`, `picture`, `locale`, `updated_at`, ... |
| `email` | `email`, `email_verified` |
| `address` | `address` |
| `phone` | `phone_number`, `phone_number_verified` |
| `offline_access` | Requests a **refresh token** (so access continues when the user is offline). |

### 4.3 Standard ID-token claims

| Claim | Meaning |
|---|---|
| `iss` | Issuer — the AS's URL. Verify exact match. |
| `sub` | Subject — **stable, unique** user ID *within this issuer*. Use this as your foreign key, not email. |
| `aud` | Audience — must be your `client_id`. |
| `exp` / `iat` / `nbf` | Expiry / issued-at / not-before (Unix seconds). |
| `nonce` | Echo of the request nonce; replay protection. |
| `auth_time` | When the user actually authenticated. |
| `acr` / `amr` | Authentication Context Class Reference / Methods Reference — *how strongly* and *by what means* (e.g. `mfa`, `pwd`) the user authenticated. |
| `azp` | Authorized party — the client the token was issued to (when `aud` differs). |

### 4.4 Discovery & metadata endpoints

| URL | Returns |
|---|---|
| `/.well-known/openid-configuration` | All endpoint URLs, supported scopes/claims, signing algs, grant types, PKCE methods. |
| `/.well-known/oauth-authorization-server` (RFC 8414) | OAuth-only equivalent. |
| `jwks_uri` (from discovery) | The JWKS public keys. |

### 4.5 Relevant RFCs (the spec toolkit)

| RFC / spec | What it defines |
|---|---|
| **RFC 6749** | OAuth 2.0 core framework, roles, grants. |
| **RFC 6750** | Bearer token usage (`Authorization: Bearer`). |
| **RFC 7636** | PKCE. |
| **RFC 7519** | JWT. **7515** JWS, **7516** JWE, **7517** JWK, **7518** JWA. |
| **RFC 7662** | Token introspection. **RFC 7009** revocation. |
| **RFC 8414** | AS metadata/discovery. |
| **RFC 8628** | Device authorization grant. |
| **RFC 8705** | mTLS client auth & **certificate-bound** access tokens. |
| **RFC 9449** | **DPoP** — sender-constrained tokens via proof-of-possession. |
| **RFC 9126** | **PAR** — Pushed Authorization Requests. |
| **RFC 9101** | **JAR** — JWT-Secured Authorization Request. |
| **RFC 9396** | **RAR** — Rich Authorization Requests (fine-grained `authorization_details`). |
| **RFC 9700** | OAuth 2.0 Security Best Current Practice (BCP). |
| **OpenID Connect Core** | ID token, UserInfo, flows. |
| **OpenID Connect Discovery / Dynamic Registration** | Metadata & runtime client registration. |
| **OpenID Connect RP-Initiated / Back-Channel / Front-Channel Logout** | Logout/session management. |
| **FAPI 1.0 / 2.0** | Financial-grade API hardening profiles. |
| **OAuth 2.1 (draft)** | Consolidation: code+PKCE only, no implicit/ROPC, exact redirect matching. |

### 4.6 JVM libraries & frameworks

| Library | Role |
|---|---|
| **Spring Security OAuth2 Client / Resource Server / Spring Authorization Server** | The mainstream Spring stack. `oauth2Login()`, `oauth2ResourceServer().jwt()`. |
| **Nimbus JOSE + JWT** (`com.nimbusds`) | The de-facto JVM library for JWT/JOSE parsing, signing, verification, JWKS. |
| **Auth0 `java-jwt`** | Simpler JWT create/verify. |
| **Keycloak adapters / Quarkus OIDC / MicroProfile JWT** | OIDC in Quarkus/Jakarta EE. |
| **Pac4j** | Multi-protocol security engine (OAuth, OIDC, SAML, CAS). |
| **`google-oauth-client`, AppAuth (Android), Okta/Auth0 SDKs** | Vendor client SDKs. |

---

## 5. Code examples by use case

> Examples target Java 17+ and Spring Boot 3 / Spring Security 6 unless noted. Comments mark the non-obvious lines.

### 5.1 Resource Server validating JWT access tokens (Spring Boot 3)

`application.yml`:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # Spring auto-fetches JWKS from {issuer}/.well-known/openid-configuration
          issuer-uri: https://as.example.com/
          # Optional explicit JWKS endpoint if discovery isn't available:
          # jwk-set-uri: https://as.example.com/.well-known/jwks.json
```
```java
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    http
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.GET, "/contacts/**").hasAuthority("SCOPE_read:contacts")
        .requestMatchers(HttpMethod.POST, "/contacts/**").hasAuthority("SCOPE_write:contacts")
        .anyRequest().authenticated())
      .oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())));
    // Stateless: no server-side session; every request re-validates the token.
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  // Map both 'scope' claim and a custom 'roles' claim into Spring authorities.
  private JwtAuthenticationConverter jwtAuthConverter() {
    var scopes = new JwtGrantedAuthoritiesConverter(); // emits SCOPE_* from 'scope'/'scp'
    var conv = new JwtAuthenticationConverter();
    conv.setJwtGrantedAuthoritiesConverter(jwt -> {
      var auths = new ArrayList<>(scopes.convert(jwt));
      List<String> roles = jwt.getClaimAsStringList("roles");
      if (roles != null) roles.forEach(r -> auths.add(new SimpleGrantedAuthority("ROLE_" + r)));
      return auths;
    });
    return conv;
  }

  // Harden validation: also require the correct audience (Spring checks iss/exp by default).
  @Bean
  JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer); // fetches JWKS, validates iss/exp
    OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
        "aud", aud -> aud != null && aud.contains("orders-api")); // reject tokens not for us
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), audience));
    return decoder;
  }
}
```
Then per-method scope checks:
```java
@RestController
class ContactsController {
  @GetMapping("/contacts")
  @PreAuthorize("hasAuthority('SCOPE_read:contacts')")   // requires @EnableMethodSecurity
  List<Contact> list(@AuthenticationPrincipal Jwt jwt) {
    String userId = jwt.getSubject();        // 'sub' — your stable user key
    return service.forUser(userId);
  }
}
```

### 5.2 Web app as an OIDC client / SSO ("Log in with…")

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          okta:
            client-id: ${OKTA_CLIENT_ID}
            client-secret: ${OKTA_CLIENT_SECRET}   # confidential client
            scope: openid, profile, email, offline_access
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          okta:
            issuer-uri: https://dev-123.okta.com/oauth2/default
```
```java
@Configuration
@EnableWebSecurity
public class ClientConfig {
  @Bean
  SecurityFilterChain web(HttpSecurity http) throws Exception {
    http
      .authorizeHttpRequests(a -> a.anyRequest().authenticated())
      .oauth2Login(Customizer.withDefaults()) // runs code+PKCE, verifies ID token, creates session
      .logout(l -> l.logoutSuccessUrl("/").deleteCookies("JSESSIONID"));
    return http.build();
  }
}

@RestController
class MeController {
  @GetMapping("/me")
  Map<String,Object> me(@AuthenticationPrincipal OidcUser user) {
    // Spring already verified signature, iss, aud, exp, nonce on the ID token.
    return Map.of("sub", user.getSubject(), "email", user.getEmail(), "name", user.getFullName());
  }
}
```
Spring Security 6 enables **PKCE automatically** for public clients, and you can force it for confidential clients too.

### 5.3 Client Credentials (service-to-service) call from a backend

```java
@Configuration
public class M2MClientConfig {
  @Bean
  OAuth2AuthorizedClientManager authorizedClientManager(
      ClientRegistrationRepository regs, OAuth2AuthorizedClientService svc) {
    var provider = OAuth2AuthorizedClientProviderBuilder.builder()
        .clientCredentials()  // grant_type=client_credentials, auto-refresh on expiry
        .build();
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(regs, svc);
    manager.setAuthorizedClientProvider(provider);
    return manager;
  }

  @Bean
  WebClient billingClient(OAuth2AuthorizedClientManager manager) {
    var oauth = new ServletOAuth2AuthorizedClientExchangeFilterFunction(manager);
    oauth.setDefaultClientRegistrationId("billing-m2m"); // registered with grant_type=client_credentials
    return WebClient.builder().filter(oauth).baseUrl("https://billing.internal").build();
  }
}

@Service
class BillingGateway {
  private final WebClient billingClient;
  BillingGateway(WebClient billingClient) { this.billingClient = billingClient; }

  Invoice fetch(String id) {
    // The filter transparently fetches/caches/refreshes the access token and adds the Bearer header.
    return billingClient.get().uri("/invoices/{id}", id)
        .retrieve().bodyToMono(Invoice.class).block();
  }
}
```

### 5.4 PKCE by hand (Java) — for a custom client / understanding the mechanics

```java
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class Pkce {
  private static final SecureRandom RNG = new SecureRandom();

  public static String verifier() {                       // 43–128 chars, high entropy
    byte[] b = new byte[32];
    RNG.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String challenge(String verifier) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256")
        .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash); // S256
  }

  public static void main(String[] a) throws Exception {
    String v = verifier();
    System.out.println("code_verifier  = " + v);            // keep server-side / in session
    System.out.println("code_challenge = " + challenge(v)); // send in /authorize
  }
}
```

### 5.5 Verifying a JWT manually with Nimbus (no framework)

```java
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jose.JWSAlgorithm;
import java.net.URL;
import java.util.Set;

public class JwtVerifier {
  public static JWTClaimsSet verify(String token) throws Exception {
    // 1) JWKS source with built-in caching + rate-limited refresh on unknown kid.
    var jwkSource = JWKSourceBuilder
        .create(new URL("https://as.example.com/.well-known/jwks.json"))
        .retrying(true).build();

    var processor = new DefaultJWTProcessor<SecurityContext>();
    // 2) Pin the algorithm — refuse anything but RS256 (blocks alg=none & HS/RS confusion).
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    // 3) Require & validate standard claims.
    processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
        new JWTClaimsSet.Builder().issuer("https://as.example.com/")
            .audience("orders-api").build(),         // exact iss + required aud
        Set.of("sub", "exp", "iat")));               // required claims
    return processor.process(token, null);           // throws if signature/claims invalid
  }
}
```

### 5.6 Device flow client (CLI) — polling loop

```java
// Pseudocode-ish Java for a CLI device-flow client.
var dr = http.post("/device_authorization", form("client_id", CID, "scope", "openid profile"));
System.out.printf("Go to %s and enter code: %s%n", dr.verification_uri, dr.user_code);

long interval = dr.interval; long deadline = now() + dr.expires_in;
while (now() < deadline) {
  Thread.sleep(interval * 1000);
  var t = http.post("/token", form(
      "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
      "device_code", dr.device_code, "client_id", CID));
  if (t.status == 200) { save(t.access_token, t.refresh_token); break; }
  switch (t.error) {
    case "authorization_pending" -> { /* keep waiting */ }
    case "slow_down"            -> interval += 5;   // back off as required by RFC 8628
    case "access_denied", "expired_token" -> throw new IllegalStateException(t.error);
    default -> throw new IllegalStateException("unexpected: " + t.error);
  }
}
```

### 5.7 Introspecting an opaque token (Resource Server, RFC 7662)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        opaquetoken:
          introspection-uri: https://as.example.com/introspect
          client-id: orders-api          # RS authenticates to the introspection endpoint
          client-secret: ${RS_INTROSPECT_SECRET}
```
```java
http.oauth2ResourceServer(o -> o.opaqueToken(Customizer.withDefaults()));
// Spring calls /introspect per request; add a short-TTL cache (Caffeine) to cut round-trips.
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Prefer JWT access tokens for high-throughput APIs**: local verification, no per-request AS call. **Cache the JWKS** (refresh on unknown `kid`, with a rate limit to avoid a thundering herd against the AS).
- **If you must use opaque tokens, cache introspection results** for a few seconds (bounded by acceptable staleness). Without caching, the AS becomes a per-request bottleneck and a single point of failure.
- **Keep tokens small.** Don't stuff large claim sets (group lists, permissions) into JWTs — they inflate every request header and can exceed proxy header limits (often 8 KB). Reference IDs and look up details server-side when sets are large.
- **Tune token lifetimes**: short access tokens (5–15 min) bound revocation lag; long-enough refresh tokens reduce login friction. There's a direct tradeoff between revocation speed and AS load.

### 6.2 Correctness & validation (the checklist that prevents most CVEs)
On the **resource server**, for every JWT, verify *all* of: signature, `alg` (pin it), `iss` (exact), `aud` (this API), `exp`/`nbf` (with small skew), and required `scope`/roles. On the **client**, for every ID token, additionally verify `nonce` and (if requested) `auth_time`/`acr`.
- **Pin algorithms.** Never accept `alg: none`; never let the token dictate the algorithm family (Section 9.1).
- **Exact redirect-URI matching.** No wildcards, no substring/prefix matching, no open redirects on your domain.
- **Single-use codes; verify `state` and `nonce`.**

### 6.3 Concurrency
- Refresh-token usage must be **serialized per session/client** to avoid two concurrent requests both refreshing — with **rotation** (Section 7.3), the second use of a now-rotated token triggers the AS's breach-detection and revokes the whole family. Use a lock/single-flight around refresh.
- JWKS cache refresh should be single-flight too.

### 6.4 Security (highest-leverage rules)
1. **Authorization code + PKCE everywhere.** Kill implicit & ROPC.
2. **TLS on every hop**, always. Bearer tokens over plaintext = game over.
3. **Store tokens safely.** SPAs: prefer **BFF** (Backend-for-Frontend) holding tokens in an `HttpOnly`, `Secure`, `SameSite` cookie session, so tokens never touch JavaScript (immune to XSS token theft). Mobile: OS keystore/keychain. Servers: secrets manager (Vault, AWS Secrets Manager), never source code or logs.
4. **Validate `aud`** so a token for service A can't be replayed against service B (token-substitution).
5. **Rotate refresh tokens** and enable reuse detection.
6. **Sender-constrain** high-value tokens with **DPoP** or **mTLS** so a stolen bearer token is useless without the proof key.
7. **Scope minimally** (least privilege); separate read/write scopes.
8. **Never log tokens** (access, refresh, ID, codes) — scrub them in log pipelines.

> **BFF (Backend-for-Frontend):** a small server that sits between a SPA/mobile app and the APIs. The BFF runs the OAuth flow as a *confidential* client, keeps tokens server-side, and exposes only a cookie-based session to the browser — eliminating the "where do I store tokens in a SPA?" problem.
>
> **XSS (Cross-Site Scripting):** an attack where malicious JavaScript runs in your page. If tokens live in `localStorage`/JS-readable memory, XSS steals them. `HttpOnly` cookies are not readable by JS, defeating this theft vector.

### 6.5 Observability
- Emit/structure: `iss`, `client_id`, `sub` (or a hashed/pseudonymized form), `scope`, `jti` (JWT ID — unique token identifier for tracing/denylisting), grant type, and outcome — **never the raw token**.
- Metrics to watch: token issuance rate, refresh rate, introspection latency/error rate, JWKS fetch failures, `401/403` rates per client, consent grants/denials.
- Distributed tracing: propagate `sub`/`client_id` as span attributes for cross-service auth debugging.

### 6.6 Cost
- Self-contained JWTs minimize AS calls (cheaper at scale) but cost you easy revocation.
- Managed IdPs (Auth0, Okta, Cognito, Entra) price per **MAU** (Monthly Active Users) or per token/feature — model this early; M2M tokens can be billed separately and add up.

### 6.7 Testing
- **Unit:** verify your JWT validation rejects: bad signature, wrong `iss`, wrong `aud`, expired, `alg:none`, missing scope. These negative tests catch the real vulnerabilities.
- **Integration:** spin up a test AS — **Keycloak (Testcontainers)**, WireMock-stubbed JWKS, or Spring Authorization Server in-process. Use **MockMvc** with `SecurityMockMvcRequestPostProcessors.jwt()` to inject a synthetic JWT with chosen scopes:
```java
mockMvc.perform(get("/contacts")
    .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_read:contacts"))))
   .andExpect(status().isOk());
```
- **Contract:** assert your discovery/JWKS consumption survives key rotation.

### 6.8 Production hardening
- Health-check and **alert on JWKS reachability** (an unreachable AS during key rotation can 401 all traffic).
- Support **graceful key rotation**: AS publishes new key in JWKS *before* using it; RS picks up new `kid` automatically.
- **Clock sync (NTP)** on all hosts — clock skew causes spurious `exp`/`nbf` failures. Allow ~30–60 s leeway.
- Rate-limit `/token`, `/introspect`, device polling; lock down redirect URIs; disable unused grant types per client.

### 6.9 Anti-patterns (do not do these)
- Using the **ID token to call APIs** (it's for the client, wrong `aud`).
- Using the **access token to identify the user** in the client (opaque to you; may not contain identity; wrong audience).
- **`localStorage` for tokens** in SPAs (XSS-exfiltratable) — use BFF.
- **Implicit / password grants.**
- **Wildcard / loose redirect URIs**, or hosting an open redirect on your domain.
- **Not verifying `aud`/`iss`/signature** ("it parsed, ship it").
- **Long-lived access tokens** to "avoid refresh complexity."
- **Treating scopes as full authorization** (still enforce per-object access control).
- **`alg: none`** acceptance or trusting the token's `alg`.

---

## 7. Advanced topics & deep internals

### 7.1 PAR — Pushed Authorization Requests (RFC 9126)
Instead of putting the (long, tamperable, loggable) authorization request in front-channel query params, the client **POSTs the request to the AS over the back channel** first, gets a short `request_uri` handle, and sends only that handle in the redirect:
```
POST /par  (back channel, authenticated) → { "request_uri": "urn:...:abc", "expires_in": 60 }
GET /authorize?client_id=...&request_uri=urn:...:abc
```
Benefits: request integrity/confidentiality, no parameter tampering, smaller URLs. **Mandatory in FAPI 2.0.**

### 7.2 JAR & RAR
- **JAR (RFC 9101):** the authorization request is itself a **signed JWT** (`request`/`request_uri` object), so the AS can verify it wasn't altered in the browser.
- **RAR (RFC 9396):** replaces coarse `scope` with structured **`authorization_details`** JSON — e.g. "transfer ≤ €500 from account X to account Y" — enabling fine-grained, transaction-level consent. Used in open banking.

### 7.3 Refresh token rotation & reuse detection
On each refresh, the AS issues a **new** refresh token and **invalidates the old one** (rotation). If an old (already-rotated) token is presented again, the AS infers **theft** (the legitimate client and an attacker both have copies) and **revokes the entire token family**, forcing re-login. This turns a silent refresh-token leak into a detectable, contained event. Pair with **single-flight** refresh on the client (Section 6.3).

### 7.4 Sender-constrained tokens: DPoP & mTLS
Bearer tokens are "cash." Two ways to bind a token to its holder:
- **mTLS-bound tokens (RFC 8705):** the client presents a TLS client certificate; the AS embeds the cert's thumbprint (`cnf` confirmation claim) in the token. The RS checks the presenting TLS cert matches. Requires PKI.
- **DPoP (RFC 9449):** the client holds an ephemeral key pair and sends a signed **DPoP proof** JWT (with method+URL+nonce) alongside each request; the access token's `cnf.jkt` binds it to that key's thumbprint. A stolen token is useless without the private key. No PKI; ideal for SPAs/mobile.

> **`cnf` (confirmation) claim:** a JWT claim that "confirms" the key the legitimate presenter must prove possession of (a cert thumbprint for mTLS, a JWK thumbprint `jkt` for DPoP).

### 7.5 Token exchange (RFC 8693)
Lets a service **exchange one token for another** — e.g. an API gateway swaps a user's incoming token for a **downstream-scoped, audience-restricted** token before calling an internal service (delegation / impersonation patterns). Solves the "how do I propagate user identity across microservices without overscoping?" problem.

### 7.6 Logout & session management (OIDC)
- **RP-Initiated Logout:** the client redirects to the AS `end_session_endpoint` (with `id_token_hint`) to terminate the AS session.
- **Back-Channel Logout:** the AS pushes a signed **logout token** to each participating client's back channel when the user logs out elsewhere — enables true single-logout across apps.
- **Front-Channel Logout:** uses hidden iframes (brittle with third-party-cookie restrictions; back-channel preferred).

### 7.7 Hybrid flow & `c_hash`/`at_hash`
`response_type=code id_token` returns an ID token *and* a code from the authorization endpoint. The ID token carries `c_hash`/`at_hash` (hashes of the code/access token) so the client can detect injection of a swapped code/token — defending against **code injection** attacks. Largely superseded by PKCE + PAR in modern profiles, but you'll see it in FAPI 1.0 and OpenID certification.

### 7.8 FAPI (Financial-grade API)
A hardened OAuth/OIDC **profile** for high-risk APIs (banking, open finance): mandates PKCE/PAR, `S256`, sender-constrained tokens (mTLS/DPoP), signed requests (JAR), strict `aud`, `ES256`/`PS256`, and certified implementations. When someone says "we need FAPI 2.0," they mean this stack of constraints applied together.

### 7.9 Lesser-known behaviors / gotchas
- **`scope` downscoping on refresh:** a refresh request may ask for a *subset* of original scopes but never a superset.
- **Consent caching:** AS may skip consent if previously granted; `prompt=consent` forces it.
- **`prompt=none` (silent auth):** lets SPAs renew sessions invisibly via a hidden iframe *if* an AS session exists; returns `login_required` otherwise. Increasingly hampered by browsers blocking third-party cookies.
- **Audience vs resource:** RFC 8707 (`resource` parameter) lets the client tell the AS *which* RS the token is for, so the AS sets `aud` precisely.
- **JWT `kid` rotation race:** if the RS sees a `kid` it doesn't know, it must refetch JWKS (rate-limited) — otherwise you get transient 401s during rotation.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which grant type?

| Scenario | Grant | Why / notes |
|---|---|---|
| Web app w/ backend, mobile, SPA, "Login with X" | **Authorization Code + PKCE** | The universal modern default. |
| Service-to-service, no user | **Client Credentials** | App authenticates itself; access token only. |
| TV / CLI / IoT (no browser) | **Device Authorization** | Offload login to user's phone. |
| Cross-service identity propagation | **Token Exchange (8693)** | Re-scope/re-audience downstream. |
| ❌ SPA token directly in URL | ~~Implicit~~ | Deprecated → use code+PKCE. |
| ❌ Collecting user's password | ~~ROPC/password~~ | Deprecated → use code+PKCE. |

### 8.2 Token format: opaque vs JWT

| Dimension | Opaque (introspection) | JWT (self-contained) |
|---|---|---|
| Validation | Call AS per request (cache) | Local signature check |
| Revocation | Instant | Hard before `exp` (need denylist/short TTL) |
| Scale | AS is a bottleneck | Scales horizontally |
| Data exposure | None (just a handle) | Payload readable (don't put secrets) |
| Size | Tiny | Larger (header bloat risk) |
| Best for | Sensitive, must-revoke-now systems | High-throughput distributed APIs |

> **Common hybrid:** opaque token to the outside world, exchanged at the **API gateway** for an internal JWT ("phantom token" pattern) — instant external revocation *and* fast internal local validation.

### 8.3 Token validation: local vs introspection

| Use local JWT validation when… | Use introspection when… |
|---|---|
| High request volume | Tokens are opaque |
| Latency-sensitive | You need real-time revocation |
| RS can't call AS per request | Token state changes mid-life (consent/role changes) |

### 8.4 Where do SPA tokens live?

| Option | XSS-safe? | CSRF concern | Verdict |
|---|---|---|---|
| `localStorage` | ❌ No | n/a | **Avoid** |
| In-memory JS | Partly (lost on reload) | n/a | Weak |
| `HttpOnly` cookie via **BFF** | ✅ Yes | Yes (use `SameSite`/CSRF token) | **Recommended** |

### 8.5 Build vs buy the AS

| Build (Spring Authorization Server, Keycloak self-host) | Buy (Auth0, Okta, Entra, Cognito) |
|---|---|
| Full control, no per-MAU fees, data residency | Fast, maintained, certified, MFA/social built-in |
| You own security patching, key mgmt, scaling, compliance | Vendor lock-in, cost at scale, less customization |
| Use when: strict control/compliance, deep customization | Use when: speed-to-market, small security team |

### 8.6 OAuth2 vs alternatives

| Need | Reach for |
|---|---|
| Delegated authorization / API access on user's behalf | **OAuth 2.0** |
| Web SSO / federated *login* | **OIDC** (or legacy **SAML** in enterprise) |
| Purely internal service auth, no third parties | **mTLS** + service mesh, or OAuth client-credentials |
| Simple internal automation | An **API key** may suffice — don't over-engineer |
| Fine-grained per-object authorization | OAuth token **+** policy engine (OPA, Zanzibar/SpiceDB) |

> **SAML (Security Assertion Markup Language):** an older XML-based SSO/federation protocol common in enterprise/B2B. OIDC is the JSON/REST successor: lighter, mobile-friendly, API-oriented. You'll still meet SAML in legacy enterprise integrations.

---

## 9. Failure modes & debugging

### 9.1 Algorithm-confusion / `alg:none` attacks
**What breaks:** a verifier that trusts the token's `alg` header. If a server uses an RSA *public* key but accepts `HS256`, an attacker signs a forged token using the *public key as the HMAC secret* and it validates. Or `alg:none` → unsigned tokens accepted.
**Diagnose/fix:** pin the expected algorithm at the verifier (Section 5.5); reject `none`; never derive verification mode from the token. Test with a crafted `alg:none` and `HS256` token in CI.

### 9.2 `redirect_uri` mismatch / open redirect
**Symptom:** `error=redirect_uri_mismatch` from the AS, or — worse — silent token theft when loose matching + an open redirect on your domain lets an attacker bounce the code to themselves.
**Diagnose:** compare the registered URI to the sent one byte-for-byte (trailing slash, scheme, port, casing). **Fix:** register exact URIs; forbid wildcards; eliminate open redirects.

### 9.3 Clock skew → spurious `exp`/`nbf` failures
**Symptom:** intermittent 401s, "token expired/not yet valid" on freshly issued tokens.
**Diagnose:** compare `iat`/`exp` (decode at jwt.io) against `date` on the failing host. **Fix:** run NTP; configure 30–60 s leeway in the verifier.

### 9.4 JWKS rotation race / unreachable AS
**Symptom:** sudden wave of 401s after the AS rotates keys, or when the AS/JWKS endpoint is unreachable.
**Diagnose:** check whether the RS's cached JWKS lacks the new `kid`; check JWKS endpoint reachability and TTLs. **Fix:** auto-refetch on unknown `kid` (rate-limited), publish new keys before use, alert on JWKS fetch failures, cache last-good keys.

### 9.5 Refresh-token reuse storm
**Symptom:** users suddenly logged out en masse after enabling rotation.
**Cause:** concurrent requests both using the same refresh token → reuse detection revokes the family.
**Fix:** single-flight/lock the refresh; ensure clients persist the *rotated* token; tolerate a tiny grace window if the AS supports it.

### 9.6 Audience confusion / token substitution
**Symptom:** a token issued for service A is accepted by service B.
**Cause:** B doesn't validate `aud`. **Fix:** every RS validates `aud` is its own identifier; use the `resource`/RAR mechanisms so the AS scopes audiences precisely.

### 9.7 Consent/scope errors
**Symptom:** `invalid_scope`, `access_denied`, or 403s at the API despite a valid token.
**Diagnose:** decode the token, inspect granted `scope`; check the AS consent record; confirm the RS maps scopes to authorities correctly (`SCOPE_` prefix in Spring). **Fix:** request the right scopes; align RS scope checks; surface consent prompts.

### 9.8 Token leakage paths (and where to look)
- **URLs/logs/`Referer`:** never put tokens in query strings (implicit-grant lesson); scrub logs.
- **Browser storage:** XSS exfiltration from `localStorage` → use BFF/`HttpOnly`.
- **Mixed content / non-TLS:** sniffable bearer tokens.
- **Error pages / stack traces** echoing headers.
**Debugging toolkit:** decode tokens at **jwt.io** (or `jwt` CLI); inspect the wire with **browser DevTools → Network**, **`curl -v`**, **mitmproxy/Burp Suite**; fetch `/.well-known/openid-configuration` and `jwks_uri` directly; use the AS's **introspection** endpoint to see what *it* thinks of a token; enable Spring Security `DEBUG` logging (`logging.level.org.springframework.security=DEBUG`).

### 9.9 Real-world incident patterns
- **GitHub/GitLab-style OAuth app token leaks:** stolen OAuth *app* tokens used to clone private repos → lesson: scope minimally, rotate, monitor unusual token use, support fine-grained tokens.
- **"Sign in with" account-takeover bugs:** apps that trusted an *access token* or an *email claim without `email_verified`* for login, or skipped `aud`/`nonce` checks → attacker links their account to a victim's. Lesson: use the **ID token**, verify `aud`/`iss`/`nonce`/signature, key user records on `sub` (+ issuer), and check `email_verified`.
- **Open-redirect → code theft chains:** loose redirect matching plus an on-domain open redirect let attackers capture authorization codes → lesson: exact redirect URIs + PKCE.

---

## 10. Interview drill

**Q1. Explain the difference between authentication and authorization, and how OAuth2 and OIDC map to them.**
*Model answer:* Authentication is verifying *who* a principal is; authorization is deciding *what* they may do. OAuth 2.0 is a delegated **authorization** framework — it issues access tokens that grant scoped permission to resources. OIDC is an **authentication** layer on top of OAuth2 that adds an ID token proving who logged in. Using OAuth2 alone for login is insecure (no audience-bound proof of authentication); OIDC fixes that.
- *Follow-up: Why is "I got an access token, so the user is logged in" insecure?* Access tokens aren't audience-bound to your client and carry no verified authentication assertion; a token minted for another app could be replayed (token substitution). The ID token's `aud`/`nonce`/signature prevent this.
- *Follow-up: Which token identifies the user to your client?* The **ID token** (`sub`). Never the access token.
- *Follow-up: Can you do OAuth without OIDC?* Yes — for pure API access where you don't need to know the user's identity.

**Q2. Walk me through authorization code + PKCE end to end.**
*Model answer:* (Sections 2.7 & 3.1, condensed.) Client makes `code_verifier`/`code_challenge`; redirects to `/authorize` with `code_challenge`+`state`(+`nonce`); user authenticates/consents; AS redirects back with a single-use `code`; client verifies `state`, then back-channel POSTs `code`+`code_verifier` to `/token`; AS checks `sha256(verifier)==challenge` and returns access/refresh/ID tokens; client verifies the ID token; uses the access token at the RS.
- *Follow-up: What attack does PKCE stop?* Authorization-code interception — a stolen code is useless without the verifier.
- *Follow-up: Why PKCE even for confidential clients?* Defense in depth; required by OAuth 2.1/BCP; protects against code injection/leakage even with a secret.
- *Follow-up: What does `state` protect against?* CSRF on the redirect/callback.

**Q3. Compare opaque tokens vs JWT access tokens. When would you choose each?**
*Model answer:* (Section 8.2.) JWT = local validation, scales, but revocation is hard before `exp`. Opaque = instant revocation and no data exposure, but needs introspection (round-trip / AS dependency). High-throughput distributed APIs → JWT (short-lived). Must-revoke-now / sensitive → opaque, or the **phantom-token** hybrid at the gateway.
- *Follow-up: How do you revoke a JWT before expiry?* Short TTLs + a denylist keyed on `jti`, or rotate signing keys, or switch sensitive ops to introspection.
- *Follow-up: How does the RS get the key to verify a JWT?* From the AS's cached **JWKS**, matched by `kid`.

**Q4. How do you securely store tokens in a single-page app?** *(senior-signal)*
*Model answer:* Avoid JS-accessible storage (`localStorage` is XSS-exfiltratable). Use a **BFF**: a confidential backend runs code+PKCE, keeps tokens server-side, and gives the browser only an `HttpOnly`, `Secure`, `SameSite` cookie session — adding CSRF protection. Optionally sender-constrain with **DPoP**.
- *Follow-up: Why is `localStorage` worse than an `HttpOnly` cookie?* JS can read `localStorage` (XSS steals it); JS cannot read `HttpOnly` cookies.
- *Follow-up: What new risk does cookie-based session introduce, and how do you mitigate?* CSRF — mitigate with `SameSite` and/or anti-CSRF tokens.

**Q5. Your microservices need to call each other with no user present. Design the auth.**
*Model answer:* Client-credentials grant: each service is a confidential client with its own credentials (prefer `private_key_jwt` or mTLS over a shared secret), requests narrowly scoped, audience-bound access tokens; callee validates `iss`/`aud`/`scope`/signature. For propagating an *end-user's* identity downstream, use **token exchange (RFC 8693)** to mint a re-scoped, re-audienced token rather than forwarding the original.
- *Follow-up: Why not reuse one shared service account everywhere?* No least privilege, no per-service revocation/audit, huge blast radius.
- *Follow-up: How do you rotate M2M credentials without downtime?* Overlap: register a new key/secret, deploy, retire the old after propagation; mTLS/`private_key_jwt` makes this cleaner than shared secrets.

**Q6. Why were the implicit and password grants deprecated?** 
*Model answer:* Implicit returned tokens in the front-channel URL (history/logs/`Referer` leakage, no PKCE, no client auth, unsafe refresh) — replaced by code+PKCE now that CORS exists. ROPC made the client handle the user's raw password (the very anti-pattern OAuth replaced; no MFA/SSO/social). OAuth 2.1 removes both.
- *Follow-up: What replaced implicit for SPAs?* Authorization code + PKCE with a CORS-enabled token endpoint (or a BFF).
- *Follow-up: Is ROPC ever acceptable?* Only narrow first-party legacy migration; never for third parties.

**Q7. How does a resource server validate a JWT? List every check.**
*Model answer:* Split/decode; read `alg`+`kid`; resolve key from cached JWKS; verify signature; then validate `iss` (exact), `aud` (this API), `exp`/`nbf` (with skew), `iat`; require expected `scope`/roles; **pin algorithm**, reject `none`. (Section 3.7/5.5.)
- *Follow-up: Why pin the algorithm?* To block `alg:none` and RS/HS confusion attacks.
- *Follow-up: How do you handle key rotation?* Refetch JWKS on unknown `kid` (rate-limited); AS publishes new keys before use.

**Q8. Design "Login with Google" for your app and name the security checks.**
*Model answer:* OIDC code+PKCE: scopes `openid email profile`; verify the ID token's signature (JWKS), `iss`, `aud`==your client_id, `exp`, and `nonce`; check `email_verified` before trusting email; key the user record on (`iss`,`sub`), not email; create a session (ideally via BFF).
- *Follow-up: Why key on `sub` not `email`?* Emails change/recycle and may be unverified; `sub` is stable per issuer.
- *Follow-up: What's `nonce` for here?* ID-token replay/injection protection.

**Q9. When would you NOT use OAuth2?** *(senior-signal)*
*Model answer:* When there's no delegation and no third party — e.g. purely internal automation where a rotated API key or mTLS in a service mesh is simpler and adequate. OAuth adds an AS, token lifecycle, key management, and operational surface; for a single trusted caller that overhead can be net-negative. Choose the simplest mechanism that meets the threat model.
- *Follow-up: What does OAuth still buy you internally?* Central revocation, scoping, audit, and uniform identity propagation — worth it once you have many services/clients.
- *Follow-up: Where does OAuth stop and a policy engine start?* OAuth authenticates the caller and conveys coarse scopes; fine-grained per-object decisions belong in a policy engine (OPA/Zanzibar).

**Q10. JWTs are hard to revoke. How do you reconcile that with a "log out everywhere now" requirement?** *(senior-signal)*
*Model answer:* You can't truly revoke a self-contained JWT before `exp`. Options, by tradeoff: (a) very short access-token TTLs + refresh rotation so revoking the *refresh* family kills future access quickly; (b) a `jti` **denylist** checked at the RS (reintroduces shared state — bounded by TTL); (c) **phantom token** — opaque outside, JWT inside, revoke the opaque handle at the edge; (d) push **back-channel logout** to clients. Pick based on how fast "now" must be vs. how much you'll spend on shared state and AS calls.
- *Follow-up: Cost of the denylist approach?* Per-request lookup against shared state (cache) — partially erases JWTs' stateless advantage.
- *Follow-up: How short is "short"?* Often 5–15 min; balance revocation lag against refresh load and UX.

**Q11. Explain DPoP vs mTLS-bound tokens and when you'd mandate them.** *(senior-signal)*
*Model answer:* Both turn bearer tokens into sender-constrained ones via a `cnf` claim. **mTLS** binds to a client TLS cert thumbprint (needs PKI; great in controlled enterprise/B2B). **DPoP** binds to an app-held key via per-request signed proofs (no PKI; ideal for SPAs/mobile). Mandate them for high-value APIs (FAPI/banking) or anywhere a leaked bearer token is unacceptable.
- *Follow-up: What attack do they stop that PKCE doesn't?* Use of a *stolen access token* — PKCE only protects the code exchange.
- *Follow-up: DPoP replay defense?* Server-issued `nonce` + `jti` + bound method/URL in the proof.

**Q12. What's the difference between scopes and fine-grained authorization, and where does each live?**
*Model answer:* Scopes are coarse capability labels carried in the token and enforced as a first gate ("may read documents"). Fine-grained authorization decides "may read *this* document," needs runtime context (ownership, relationships), and lives in the RS/policy engine. Treating scopes as the whole story leads to broken object-level access control (a top API risk).
- *Follow-up: Why not encode every permission as a scope?* Token bloat, no per-object context, brittle.
- *Follow-up: Where would a policy engine fit?* RS calls OPA/SpiceDB after token validation for the object-level decision.

---

## 11. Glossary

- **Access token:** credential presented to a resource server to access an API; short-lived; audience = the API.
- **`acr` / `amr`:** authentication context class / methods — how strongly / by what means the user authenticated.
- **`alg`:** the signing algorithm named in a JWT header (e.g. `RS256`); must be pinned by verifiers.
- **`aud` (audience):** intended recipient of a token; verifiers must check it.
- **Authentication (AuthN):** verifying identity. **Authorization (AuthZ):** verifying permissions.
- **Authorization Code:** short-lived, single-use front-channel artifact exchanged for tokens.
- **Authorization Server (AS):** issues tokens, authenticates the user, gets consent.
- **`azp`:** authorized party — the client a token was issued to.
- **Base64URL:** URL-safe Base64 *encoding* (not encryption) used in JWTs.
- **Bearer token:** a token usable by mere possession (like cash).
- **BFF (Backend-for-Frontend):** a server that runs OAuth for a SPA/mobile app and keeps tokens server-side.
- **Claim:** a name/value field inside a token.
- **Client:** the application requesting access. **Confidential** (keeps a secret) vs **Public** (cannot).
- **Client Credentials grant:** M2M grant; the client is the resource owner; access token only.
- **`cnf`:** confirmation claim binding a token to a holder's key (DPoP/mTLS).
- **Consent:** user's approval of requested scopes.
- **CORS:** browser rule governing cross-origin API calls.
- **CSRF:** forged request riding a user's session; mitigated by `state`/`SameSite`.
- **Device Authorization grant (RFC 8628):** login offloaded to a second device for input-constrained devices.
- **Discovery document:** `/.well-known/openid-configuration` listing endpoints/capabilities.
- **DPoP (RFC 9449):** sender-constrained tokens via per-request signed proofs.
- **Entropy:** unpredictability of a random value.
- **`exp` / `iat` / `nbf`:** expiry / issued-at / not-before timestamps.
- **FAPI:** financial-grade hardened OAuth/OIDC profile.
- **Federation:** delegating authentication to an external IdP.
- **Front channel / Back channel:** via-browser (untrusted) vs server-to-server (trusted) data paths.
- **Grant type:** the method of obtaining a token (code, client_credentials, device_code, …).
- **HS256 / RS256 / ES256:** symmetric HMAC / RSA / ECDSA JWT signing algorithms.
- **`iss` (issuer):** the AS's identifier URL; verify exact match.
- **ID token (OIDC):** JWT asserting the user's identity to the client; `aud` = client.
- **IdP (Identity Provider):** the system that authenticates users (often = AS in OIDC).
- **Implicit grant:** deprecated front-channel token delivery.
- **Introspection (RFC 7662):** RS asks AS about an opaque token.
- **`jti`:** unique JWT identifier (for tracing/denylisting).
- **JWA / JWE / JWK / JWKS / JWS / JWT:** algorithms / encryption / key / key-set / signature / token JOSE specs.
- **`kid`:** key ID selecting which JWKS key signed a token.
- **MAU:** monthly active users (common IdP pricing unit).
- **mTLS:** mutual TLS; both sides present certificates; can bind tokens.
- **MFA:** multi-factor authentication.
- **`nonce`:** OIDC value preventing ID-token replay; echoed in the ID token.
- **NTP:** time-sync protocol; prevents clock-skew token failures.
- **OAuth 2.0 (RFC 6749):** delegated authorization framework.
- **OAuth 2.1:** draft consolidation: code+PKCE only, no implicit/ROPC, exact redirect matching.
- **OIDC (OpenID Connect):** authentication layer on OAuth2 (ID token, UserInfo).
- **Opaque token:** meaningless handle validated via introspection.
- **OPA / Zanzibar / SpiceDB:** policy engines for fine-grained authorization.
- **PAR (RFC 9126):** push the authorization request to the AS back-channel first.
- **Phantom token:** opaque externally, JWT internally (gateway swaps).
- **PKCE (RFC 7636):** proof-key protection for the code flow; `S256` recommended.
- **`prompt`:** authorize-endpoint hint (`none`/`login`/`consent`/`select_account`).
- **RAR (RFC 9396):** rich, structured `authorization_details` instead of coarse scopes.
- **Redirect URI:** registered callback the AS sends the code to; must match exactly.
- **Refresh token:** long-lived credential to mint new access tokens; sent only to the AS.
- **Resource Owner (RO):** owner of the data (usually the user).
- **Resource Server (RS):** the API hosting protected resources.
- **ROPC / password grant:** deprecated grant where the client handles the user's password.
- **Rotation (refresh token):** issue new + invalidate old refresh token each use; enables reuse detection.
- **SAML:** legacy XML SSO/federation protocol.
- **Scope:** space-delimited permission labels requested/granted.
- **Sender-constrained token:** token bound to a key the holder must prove (DPoP/mTLS).
- **SHA-256:** one-way cryptographic hash used by PKCE `S256` and signatures.
- **Single-flight:** ensuring only one concurrent execution of an operation (e.g. refresh, JWKS fetch).
- **SPA:** single-page application (public client).
- **SSO:** single sign-on.
- **`state`:** client CSRF token echoed by the AS on the redirect.
- **`sub` (subject):** stable per-issuer user identifier; use as your user key.
- **Token Exchange (RFC 8693):** swap one token for another (delegation/re-scoping).
- **TLS:** transport encryption; mandatory on every OAuth hop.
- **UserInfo endpoint:** OIDC endpoint returning user claims for an access token.
- **XSS:** malicious script in your page; steals JS-readable tokens.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Roles:** Resource Owner · Client (confidential/public) · Authorization Server · Resource Server.
**Default flow:** **Authorization Code + PKCE** (everywhere). M2M → **Client Credentials**. No-browser device → **Device Code**. ~~Implicit~~ / ~~Password~~ = dead.
**Tokens:** Access (→ API, short, Bearer) · Refresh (→ AS only, long, rotate) · **ID token** (→ client, identity, OIDC).
**ID token must verify:** signature(JWKS/`kid`) · `iss`(exact) · `aud`==client · `exp` · `nonce`. **Access token (RS) must verify:** signature · `iss` · `aud`==API · `exp` · scopes · **pin `alg`**, reject `none`.
**PKCE:** `verifier` (secret) → `challenge=base64url(sha256(verifier))` (`S256`) → exchange reveals verifier.
**Front channel** = browser/URL (untrusted; only the code travels here). **Back channel** = server↔AS (tokens here).
**OIDC = OAuth2 + `openid` scope + ID token + UserInfo.** OAuth = access; OIDC = identity.
**Lifetimes (typical):** access 5–60 min; refresh hours–months; allow ~30–60 s clock skew.
**Storage:** SPA → **BFF** + `HttpOnly` cookie (never `localStorage`); mobile → keystore; server → secrets manager.
**Token format:** JWT (local, scales, hard to revoke) vs Opaque (introspection, instant revoke) — hybrid = **phantom token**.
**Hardening:** PKCE + exact redirect URIs + verify `aud` + rotate refresh (reuse detection) + sender-constrain (DPoP/mTLS) + least-privilege scopes + never log tokens + PAR/JAR/RAR for high-risk (FAPI).
**Key RFCs:** 6749 core · 6750 bearer · 7636 PKCE · 7519 JWT · 7662 introspection · 8628 device · 8705 mTLS · 9449 DPoP · 9126 PAR · 9700 Security BCP · OIDC Core.
**Top anti-patterns:** ID token to call APIs · access token to identify user · `localStorage` tokens · implicit/password · loose redirect URIs · skipping `aud`/`iss`/signature · `alg:none` · scopes-as-full-authz · long-lived access tokens.

### 12.2 Self-test (no answers — recall actively)

1. A teammate stores access *and* refresh tokens in `localStorage` "to keep it stateless." List every risk and propose a concrete redesign with the specific cookie attributes and architecture you'd use.
2. Trace, byte by byte, an authorization-code-with-PKCE login including the exact server-side checks at `/authorize` and `/token` — and name precisely which stolen artifact PKCE neutralizes and why.
3. Your JWT-based API must support "force-logout this user within 30 seconds." Give three implementation strategies, the shared-state/AS-load cost of each, and which you'd ship and why.
4. Design end-to-end auth for: (a) a smart-TV app, (b) a nightly batch job calling an internal billing API, (c) "Sign in with Apple" for your web app — naming grant type, token types, and every validation check for each.
5. An RS verifies signatures with an RSA public key but doesn't pin the algorithm. Describe the exact forged token an attacker crafts, why it validates, and the one-line fix.
6. Compare DPoP and mTLS-bound tokens: what attack do they stop that PKCE does not, what infrastructure each needs, and which you'd mandate for an open-banking API.
7. After the AS rotates signing keys at 02:00, your API starts returning a wave of 401s. Walk through your diagnosis steps (commands/endpoints) and the permanent fix.

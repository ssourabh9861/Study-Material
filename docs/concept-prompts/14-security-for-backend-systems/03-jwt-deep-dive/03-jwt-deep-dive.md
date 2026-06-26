# JWT Deep Dive

> A definitive engineering-handbook chapter on JSON Web Tokens (JWT) for senior backend developers on the Java/JVM stack. From first principles to deep internals, production hardening, the vulnerability catalog, and interview-grade mastery.

---

## 1. Overview & where it fits

### 1.1 What a JWT is, in one sentence

A **JWT (JSON Web Token)** is a compact, URL-safe, self-describing data structure that carries a set of **claims** (assertions about an entity, usually a user) and is **cryptographically protected** so that a receiver can verify *who issued it* and *that it has not been tampered with* — without calling back to the issuer.

The acronym is pronounced "jot" (per RFC 7519, the spec's authors literally say so).

### 1.2 The problem it solves

Classic web authentication used **server-side sessions**: when a user logs in, the server creates a session record (in memory, in Redis, in a database) and hands the browser a random **session ID** in a cookie. On every subsequent request, the server looks up that session ID to find out who the user is.

That works, but it has costs:

- **State.** Every request requires a lookup in shared session storage. In a horizontally scaled fleet (many app servers behind a load balancer), all servers must reach the *same* session store, or you need **sticky sessions** (the load balancer pins a user to one server — fragile when that server dies).
- **Cross-service trust.** In a microservice architecture, service A authenticates the user, but service B, C, and D also need to know who the user is. With opaque session IDs, every service has to call back to the auth service or the session store on every hop.

> **What is a microservice architecture?** Instead of one big "monolith" application, the system is split into many small independent services (e.g., `orders`, `payments`, `inventory`), each owning its data and deployed separately. They communicate over the network (HTTP/gRPC/messaging). This makes "who is this caller?" a distributed problem.

A JWT flips the model. Instead of a *reference* to server-held state (a session ID), the token *is* the state. It contains the user's identity and permissions directly, and it is signed so any service holding the issuer's public key (or shared secret) can verify it **locally**, with no network call. This is called a **stateless** or **self-contained** token.

> **Stateless vs stateful tokens.** A *stateful* (opaque) token is a meaningless random string; its meaning lives in a database the issuer controls. A *stateless* token (JWT) carries its own meaning and is verified by math (a signature check), not by a lookup. The tradeoff — which is the recurring theme of this chapter — is that stateless tokens are hard to *revoke* because no central authority is consulted on each use.

### 1.3 When you reach for it

- **Stateless API authentication.** A single-page app or mobile client logs in once and presents a JWT (typically as a `Bearer` token in the `Authorization` header) on every API call.
- **Microservice / service-to-service identity propagation.** The edge gateway validates the user, and the user's JWT (or a derived internal one) flows downstream so each service knows the caller.
- **Federated identity / SSO via OpenID Connect (OIDC).** When you "Sign in with Google," the **ID Token** you get back is a JWT.
- **Short-lived, signed assertions in general** — email verification links, password-reset tokens, signed download URLs, one-time action tokens. (JWTs are good at "here is a tamper-proof, time-limited fact.")

### 1.4 When you should *not* reach for it (preview)

- When you need **immediate, reliable revocation** (e.g., "log this user out of all devices *right now*"). Pure stateless JWTs cannot do this; see §1.2 and §6/§7.
- When tokens carry **sensitive data** you do not want readable by anyone (a signed JWT is *not encrypted* — its payload is plain, decodable base64; see §2.6).
- When tokens get **large** and you put them in cookies/headers on every request (size and bandwidth; see §6.1).
- When a **simple opaque session** in Redis would do and you have no cross-service or scale problem. Don't add crypto and key management you don't need.

### 1.5 The one-paragraph mental model

> Think of a JWT as a **tamper-evident plastic badge** issued by a trusted authority. Anyone can read what's printed on the badge (the claims), but it has a holographic seal (the **signature**) that only the issuer can produce and that anyone with the right reference image (the **key**) can verify. Because verification is local, gate guards (your services) don't have to phone HQ on every entry. The catch: once a badge is printed and handed out, HQ can't grab it back — it just stops being valid when its printed **expiry** passes, or when guards are told to check a **blocklist**. Everything in JWT engineering flows from those two facts: *self-contained + signed* (the superpower) and *can't be un-issued* (the liability).

---

## 2. Foundations from first principles

We build the structure from zero. By the end of this section you can hand-decode a token, name every part, and explain why each piece exists.

### 2.1 The three parts

A JWT in its most common serialization — **JWS Compact Serialization** — is exactly three base64url-encoded strings joined by dots:

```
xxxxx.yyyyy.zzzzz
└─────┘ └─────┘ └─────┘
header  payload  signature
```

- **Header** — metadata: which algorithm signed the token, and which key.
- **Payload** — the claims: the actual assertions (who, when, what permissions).
- **Signature** — the cryptographic proof over `header.payload`.

> **What is "JWS"?** JWT is an abstract concept (RFC 7519). It is *realized* using one of two lower-level specs: **JWS (JSON Web Signature, RFC 7515)** for *signed* tokens, and **JWE (JSON Web Encryption, RFC 7516)** for *encrypted* tokens. 99% of what people call "a JWT" is a JWS. We focus on JWS and cover JWE briefly in §7.

### 2.2 base64url — the encoding (not encryption!)

Each part is encoded with **base64url**, a variant of Base64 designed to be safe inside URLs and HTTP headers.

> **What is Base64?** An encoding that maps arbitrary binary bytes to 64 printable ASCII characters (A–Z, a–z, 0–9, `+`, `/`), turning every 3 bytes into 4 characters. It is **reversible by anyone** — it is *not* secret and provides *zero* confidentiality. Its job is transport safety, not privacy.

**base64url** differs from standard Base64 in two ways:
1. Replaces `+` with `-` and `/` with `_` (because `+` and `/` have special meaning in URLs).
2. **Omits the trailing `=` padding** characters.

A crucial consequence: **anyone can decode the header and payload of a JWT.** Paste any token into a decoder (or run `base64 -d`) and you'll read the JSON. Treat the payload as *public*.

Quick hand-decode in a shell:

```bash
# Given a token, extract and decode the payload (2nd field).
# tr fixes base64url -> base64; we pad manually for `base64 -d`.
TOKEN='eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkFsaWNlIiwiaWF0IjoxNTE2MjM5MDIyfQ.SIGNATURE'
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | { read s; pad=$(( (4 - ${#s} % 4) % 4 )); printf '%s%.*s' "$s" "$pad" '===='; } | base64 -d
# -> {"sub":"1234567890","name":"Alice","iat":1516239022}
```

### 2.3 The header (JOSE header)

> **JOSE** = "JSON Object Signing and Encryption," the umbrella family of RFCs (7515–7519, 7797, 8725 …) that define JWT/JWS/JWE/JWK. The header is therefore called the **JOSE header**.

A typical signed header:

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "2024-06-key-01"
}
```

| Field | Name | Meaning | Required? |
|---|---|---|---|
| `alg` | Algorithm | The signing (or encryption) algorithm, e.g. `HS256`, `RS256`, `ES256`, or the dangerous `none`. | **Required** by JWS. |
| `typ` | Type | Media type of the token. Usually `"JWT"`. For access tokens, RFC 9068 recommends `"at+jwt"`. | Optional but recommended. |
| `kid` | Key ID | A hint telling the verifier *which* key to use. Critical for **key rotation** (§3.5). | Optional but strongly recommended. |
| `cty` | Content Type | Used when the payload is itself a nested JWT. | Rare. |
| `jku`/`jwk`/`x5u`/`x5c` | Key material pointers | Ways to embed or point to keys *inside the token*. **Dangerous if trusted blindly** (§9). | Avoid trusting. |

**Security rule learned early:** the header is *attacker-controlled input*. Everything in it — `alg`, `kid`, `jku` — was placed there by whoever made the token. Never let the header dictate your *trust decisions* without independent constraint. This single principle prevents the worst JWT attacks (§9).

### 2.4 The payload (claims set)

The payload is a JSON object whose members are **claims**. There are three flavors:

1. **Registered claims** — standardized in RFC 7519 (and OIDC). Short three-letter names to keep tokens compact. *These are the ones you must master.*
2. **Public claims** — names registered in the IANA "JSON Web Token Claims" registry or namespaced with a collision-resistant URI to avoid clashes.
3. **Private claims** — custom, agreed between issuer and consumer (e.g., `roles`, `tenant_id`, `org`).

#### The standard (registered) claims — memorize these

| Claim | Full name | Type | Purpose | Verifier must… |
|---|---|---|---|---|
| `iss` | Issuer | StringOrURI | Who minted the token (e.g., `https://auth.example.com`). | **Check it equals an expected issuer.** |
| `sub` | Subject | StringOrURI | Who/what the token is about — usually the user ID. | Use as the principal identity. |
| `aud` | Audience | StringOrURI **or array** | Who the token is *for* (the intended recipient service/API). | **Check your service is in `aud`.** |
| `exp` | Expiration Time | NumericDate | Token invalid at/after this instant. | **Reject if now ≥ exp** (with small clock skew leeway). |
| `nbf` | Not Before | NumericDate | Token invalid *before* this instant. | Reject if now < nbf (with leeway). |
| `iat` | Issued At | NumericDate | When the token was minted. | Optional; can detect implausibly old tokens. |
| `jti` | JWT ID | String | Unique token identifier. | Enables denylists & replay detection. |

> **What is a NumericDate?** A JSON *number* of **seconds** (not milliseconds!) since the Unix epoch (1970-01-01T00:00:00Z UTC), ignoring leap seconds. So `1700000000` means a specific second in time. The "seconds, not millis" detail trips up Java developers constantly because `System.currentTimeMillis()` returns milliseconds — you must divide by 1000.

> **What is StringOrURI?** A claim value that is either an arbitrary string *or*, if it contains a `:`, must be a valid URI. This lets `iss` be a URL while `sub` can be a plain ID.

Example realistic access-token payload:

```json
{
  "iss": "https://auth.example.com",
  "sub": "user_8f3a91",
  "aud": ["orders-api", "payments-api"],
  "exp": 1700000900,
  "nbf": 1700000000,
  "iat": 1700000000,
  "jti": "b1f2c3d4-...",
  "scope": "orders:read orders:write",
  "roles": ["customer"],
  "tenant_id": "acme"
}
```

`scope`, `roles`, and `tenant_id` are private/public claims your apps agree on. `scope` (space-delimited string) is the OAuth 2.0 convention; `roles` (array) is common in custom systems and in Keycloak/Auth0 token shapes.

### 2.5 The signature

The signature binds the header and payload so any change is detectable. The exact inputs are:

```
signing_input = base64url(header_bytes) + "." + base64url(payload_bytes)
signature     = Sign(signing_input, key, alg)
JWT           = signing_input + "." + base64url(signature)
```

Two important subtleties:

1. **The signature is computed over the *encoded* strings**, byte-for-byte, *including the dot*. The verifier re-encodes nothing — it takes the exact bytes it received before the second dot and verifies them. This is why you must never re-serialize the JSON before verifying (re-serialization could reorder keys or change whitespace and break the check).
2. **Two algorithm families** produce/verify signatures differently — symmetric (HMAC) and asymmetric (RSA/ECDSA). That's §3.4.

### 2.6 Signed ≠ encrypted (the #1 beginner misconception)

A standard JWS gives you **integrity** and **authenticity**, *not* **confidentiality**.

> - **Integrity** — the data wasn't modified in transit.
> - **Authenticity** — the data really came from the claimed issuer.
> - **Confidentiality** — the data is hidden from eavesdroppers.

Because the payload is just base64url, **anyone who sees the token can read every claim.** Never put secrets (passwords, full credit-card numbers, PII you wouldn't print on a postcard) in a JWS payload. If you genuinely need confidentiality, use **JWE** (encrypted JWT, §7.6) — but more often, the right answer is "don't put the secret in the token at all," and rely on TLS for transport confidentiality.

> **What is TLS?** Transport Layer Security — the encryption layer under HTTPS. It protects the token *on the wire* between two endpoints. It does **not** protect the token once it's at rest in `localStorage`, in logs, or in the recipient's hands. JWTs must always travel over TLS.

### 2.7 A minimal worked example (HS256, by hand)

Header `{"alg":"HS256","typ":"JWT"}` and payload `{"sub":"1234567890","name":"John Doe","iat":1516239022}` with the secret `your-256-bit-secret` produce the canonical RFC 7515 example token:

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.
eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

The third segment is `base64url(HMAC-SHA256("header.payload", secret))`. Change one character in the payload and re-decode: the recomputed HMAC won't match the third segment, and verification fails. *That* is the whole trick.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle: mint → transport → verify, with the cryptography spelled out, the algorithms compared, and key distribution/rotation explained.

### 3.1 The big picture lifecycle (state & data flow)

```
            ┌──────────────────────────────────────────────────────────────┐
            │ 1. AUTHENTICATION                                              │
 User ───►  │   Client sends credentials to Authorization Server (AS).      │
            │   AS verifies them (password, MFA, etc.).                      │
            └──────────────────────────────────────────────────────────────┘
                                     │ success
                                     ▼
            ┌──────────────────────────────────────────────────────────────┐
            │ 2. MINTING                                                     │
            │   AS builds claims (sub, aud, exp...), serializes header+      │
            │   payload, SIGNS with its private/secret key, sets `kid`.      │
            │   Returns: short-lived ACCESS TOKEN (JWT) + long-lived         │
            │            REFRESH TOKEN (usually opaque).                     │
            └──────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
            ┌──────────────────────────────────────────────────────────────┐
            │ 3. TRANSPORT & STORAGE                                         │
            │   Client stores tokens, sends access token on each request:   │
            │      Authorization: Bearer <jwt>                              │
            └──────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
            ┌──────────────────────────────────────────────────────────────┐
            │ 4. VERIFICATION (Resource Server / each microservice)         │
            │   Parse → pick key by `kid` → verify signature → validate     │
            │   claims (exp/nbf/iss/aud) → authorize (scopes/roles).        │
            │   ALL LOCAL — no call to AS in the steady state.              │
            └──────────────────────────────────────────────────────────────┘
                                     │ on 401 (expired)
                                     ▼
            ┌──────────────────────────────────────────────────────────────┐
            │ 5. REFRESH                                                     │
            │   Client trades REFRESH TOKEN at AS for a new access token.    │
            │   AS can refuse here (revocation point). Loop back to (4).     │
            └──────────────────────────────────────────────────────────────┘
```

> **Roles defined.** *Authorization Server (AS)* / *Identity Provider (IdP)*: the trusted party that authenticates users and mints tokens (e.g., Keycloak, Auth0, Okta, Cognito, your own auth service). *Resource Server (RS)*: an API that consumes and verifies tokens to protect its endpoints. *Client*: the app (SPA, mobile, backend) acting on the user's behalf. These terms come from OAuth 2.0 (RFC 6749).

### 3.2 Minting: step by step

1. **Authenticate the principal** (password + MFA, an authorization-code exchange, a client-credentials grant, etc.). *Minting only happens after this succeeds.*
2. **Assemble the claims set.** Set `iss` to your own issuer URL, `sub` to the user ID, `aud` to the intended API(s), `iat`/`nbf` to "now" (epoch seconds), `exp` to "now + TTL" (e.g., +900s = 15 min), generate a random `jti`, and add app claims (`scope`, `roles`).
3. **Build the JOSE header.** Set `alg` to your chosen algorithm and `kid` to the identifier of the *current* signing key. Set `typ`.
4. **Compute the signing input:** `base64url(header) + "." + base64url(payload)`.
5. **Sign** the signing input with the secret (HMAC) or private key (RSA/ECDSA).
6. **Serialize** to compact form `header.payload.signature`.
7. **Return** to the client.

### 3.3 Verification: step by step (the canonical algorithm)

This is the routine every resource server runs on every request. Get it wrong and you have a security hole; get it right and it's a few microseconds.

1. **Syntactic parse.** Split on `.`; expect exactly 3 parts for JWS. Base64url-decode the header and payload to JSON. If anything fails → reject (malformed).
2. **Read `alg` from header — but DO NOT trust it to choose what's allowed.** Compare it against an **allowlist of algorithms you accept**. If `alg` is `none`, or not in your allowlist → reject. (This defends against `alg:none` and `alg` confusion; §9.)
3. **Select the key.** Use `kid` (and `iss`) to look up the verification key from your configured keys or JWKS cache (§3.5). If `kid` is unknown → refresh JWKS once, then reject if still unknown. *Never* fetch a key from a URL inside the token (`jku`) unless that URL is on an allowlist.
4. **Verify the signature** over the *received* `header.payload` bytes using the selected key and the expected algorithm. If it fails → reject.
5. **Validate temporal claims.** Reject if `now ≥ exp` or `now < nbf`, allowing a small **clock skew leeway** (commonly 30–120 s; many libraries default to 0 — set it explicitly). Optionally reject implausibly old `iat`.
6. **Validate `iss`.** Must equal the issuer you trust.
7. **Validate `aud`.** Your service's identifier must be present in `aud`. (If `aud` is an array, check membership.)
8. **Validate `typ`** if you require a specific token type (e.g., reject ID tokens at an API that expects access tokens; reject `at+jwt` mismatch).
9. **(Optional) Revocation check.** If you maintain a denylist or do introspection, check `jti`/`sub` now (§6.3).
10. **Authorize.** Only *now* do you make the access decision using `scope`/`roles`/custom claims. Authentication (steps 1–8) is "who is this?"; authorization is "are they allowed to do *this*?"

> **Clock skew defined.** Different machines' clocks drift apart by seconds. If the issuer's clock is 5s ahead of the verifier's, a freshly minted token might appear "not yet valid" (`nbf`) on the verifier. A small **leeway** (allowed skew) absorbs this. Too much leeway weakens `exp`; keep it ≤ 60s typically.

### 3.4 Signing algorithms — symmetric vs asymmetric

This is the single most consequential design choice in a JWT system.

#### 3.4.1 HMAC family (HS256/HS384/HS512) — symmetric

> **What is HMAC?** Hash-based Message Authentication Code. It mixes a **shared secret** with the message and hashes it (SHA-256 for HS256). The same secret is used to *create* and to *verify* the tag. `HS256` = HMAC with SHA-256.

- **One key, shared.** Whoever can *verify* can also *forge*. There is no public/private split.
- **Fast and simple.** No certificate infrastructure.
- **Key distribution problem:** every party that verifies must hold the secret. If you have 12 microservices all verifying, 12 services now hold a secret that can *mint* tokens. One leak = full compromise.

**Use HS256 when:** the same trust boundary mints and verifies — e.g., a single service (monolith) issues a token to itself, or a tightly controlled pair where you can protect the secret. Also good for internal, short-lived service-to-service tokens where you control both ends.

#### 3.4.2 RSA family (RS256/RS384/RS512, and PS*) — asymmetric

> **What is RSA / asymmetric (public-key) cryptography?** A **key pair**: a **private key** (kept secret by the signer) and a mathematically related **public key** (freely distributable). The private key *signs*; the public key only *verifies*. You cannot forge a signature with the public key. `RS256` = RSASSA-PKCS1-v1_5 with SHA-256. `PS256` = RSASSA-PSS, a newer padding scheme considered more robust (preferred if both ends support it).

- **Only the AS holds the private key.** Resource servers hold only the **public** key — even if a resource server is fully breached, the attacker still cannot mint valid tokens.
- **Public key can be published** openly via JWKS (§3.5). This is what makes RS256 the default for multi-service and third-party scenarios.
- **Larger and slower** than HMAC. Tokens are the same size, but signing/verification is more CPU-heavy, and RSA keys/signatures are large (2048-bit minimum recommended).

**Use RS256 (or ES256) when:** many independent verifiers, third parties, microservices, or any case where verifiers should not be able to mint. This is the OIDC default.

#### 3.4.3 ECDSA family (ES256/ES384/ES512) — asymmetric, elliptic curve

> **What is ECDSA / ECC?** Elliptic Curve Digital Signature Algorithm. Same public/private model as RSA but built on elliptic-curve math, giving **equivalent security with much smaller keys and signatures** and faster signing. `ES256` uses the NIST P-256 curve with SHA-256.

- **Much smaller** keys/signatures than RSA at equivalent security (P-256 ≈ RSA-3072).
- **Fast signing.** Verification is comparable to or slightly slower than RSA verification depending on the library.
- **Caveat:** ECDSA requires a high-quality random nonce per signature; a buggy implementation that reuses the nonce leaks the private key (the historical PlayStation 3 break). Use a vetted library; don't roll your own.

**Use ES256 when:** you want asymmetric benefits with smaller tokens/keys — increasingly the modern default over RS256.

#### 3.4.4 EdDSA (Ed25519) — modern asymmetric

> **What is EdDSA?** Edwards-curve Digital Signature Algorithm (the JWA value is `EdDSA`, typically with the Ed25519 curve). It is fast, deterministic (no risky per-signature nonce), and resistant to several implementation pitfalls. Supported by newer libraries; check your library/version.

#### 3.4.5 Comparison table

| Algorithm | Type | Keys | Token forgeability if verifier breached | Key size (typical) | Signature size | Relative speed | Use when |
|---|---|---|---|---|---|---|---|
| `HS256/384/512` | Symmetric (HMAC) | 1 shared secret | **YES — verifier can forge** | ≥256-bit secret | small (32–64 B) | Fastest | Single trust boundary; you control both ends |
| `RS256/384/512` | Asymmetric (RSA PKCS1v1.5) | private + public | No | 2048–4096-bit | large (256–512 B) | Slow sign, fast verify | Many verifiers / OIDC default (legacy) |
| `PS256/384/512` | Asymmetric (RSA-PSS) | private + public | No | 2048–4096-bit | large | similar to RS | Prefer over RS if supported |
| `ES256/384/512` | Asymmetric (ECDSA) | private + public | No | 256–521-bit | small (64–132 B) | Fast | Modern default; smaller than RSA |
| `EdDSA` (Ed25519) | Asymmetric (Edwards) | private + public | No | 256-bit | small (64 B) | Fast | Modern, robust; if library supports |
| `none` | (unsecured) | — | **ALWAYS forgeable** | — | empty | — | **Never** in production |

### 3.5 Key distribution via JWKS and rotation via `kid`

With asymmetric algorithms, resource servers need the AS's **public key**. Hardcoding it is brittle (you can never rotate). The industry standard is **JWKS**.

> **What is a JWK and a JWKS?** A **JWK (JSON Web Key, RFC 7517)** is a JSON representation of a single cryptographic key. A **JWKS (JWK Set)** is a JSON document containing an *array* of JWKs — typically the issuer's current (and recently retired) public keys. It is published at a well-known HTTPS URL.

A JWKS looks like:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "kid": "2024-06-key-01",
      "alg": "RS256",
      "n": "0vx7agoeb...big-base64url-modulus...",
      "e": "AQAB"
    },
    {
      "kty": "EC",
      "use": "sig",
      "kid": "2024-09-key-02",
      "crv": "P-256",
      "x": "f83OJ3D2x...",
      "y": "x_FEzRu9m..."
    }
  ]
}
```

| JWK field | Meaning |
|---|---|
| `kty` | Key type: `RSA`, `EC`, `oct` (symmetric), `OKP` (Ed25519). |
| `use` | `sig` (signature) or `enc` (encryption). |
| `kid` | Key ID — the value matched against the token header's `kid`. |
| `alg` | Intended algorithm. |
| `n`,`e` | RSA modulus and exponent (base64url). |
| `crv`,`x`,`y` | EC curve and public point coordinates. |

#### 3.5.1 Discovery

For OIDC issuers, the JWKS URL is found via the **discovery document** at `https://<issuer>/.well-known/openid-configuration`, whose `jwks_uri` field gives the JWKS endpoint. (Some OAuth servers expose `.well-known/oauth-authorization-server` per RFC 8414.)

> **What is `.well-known`?** A standardized URL path prefix (RFC 8615) where services publish machine-readable metadata so clients can auto-discover configuration without hardcoding.

#### 3.5.2 Verifier behavior with JWKS (control flow)

1. On startup (or first need), fetch the JWKS from `jwks_uri` over HTTPS and cache it.
2. On each token: read `kid` from the header, find the matching JWK in cache.
3. **Cache miss** (unknown `kid`): the issuer may have just rotated. Re-fetch JWKS **once** (with rate limiting to avoid a stampede), then retry. If still unknown → reject.
4. **Cache TTL & refresh:** respect HTTP `Cache-Control`/`max-age` on the JWKS response; otherwise use a sane TTL (minutes to an hour). Many libraries auto-handle this.

#### 3.5.3 Key rotation lifecycle (why `kid` exists)

Keys must be rotated periodically (compromise hygiene, policy). The `kid` makes rotation *graceful* and zero-downtime:

```
Phase A (steady):   JWKS = [K1].  AS signs with K1.  Verifiers trust K1.
Phase B (introduce):JWKS = [K1, K2].  AS STILL signs with K1.
                    Verifiers now know K2 (cache warmed) but see only K1 tokens.
Phase C (cut over): JWKS = [K1, K2].  AS now signs with K2 (kid=K2).
                    Old tokens (kid=K1) still verify; new ones use K2.
Phase D (retire):   After max token TTL has elapsed since cutover,
                    JWKS = [K2].  K1 removed.  No valid K1 token can exist anymore.
```

The key insight: **publish the new public key *before* you start signing with it**, and **keep the old public key until all tokens signed with it have expired** (wait ≥ max access-token TTL). This is why a tight `exp` (short TTL) makes rotation faster and safer.

### 3.6 What actually happens during signature verification (crypto data flow)

For `RS256`, verifying means:

1. Compute `SHA-256(signing_input)` → a 32-byte digest.
2. Decode the signature bytes; apply the RSA public key to "unwrap" them into the padded digest structure (PKCS#1 v1.5).
3. Compare the recovered digest to the locally computed digest. Equal ⇒ valid.

For `HS256`, verifying means:

1. Compute `HMAC-SHA256(signing_input, secret)` locally.
2. **Constant-time compare** that to the token's signature bytes.

> **Why constant-time compare?** A naive `==` byte comparison returns early on the first mismatching byte. An attacker can measure tiny timing differences to learn the secret byte-by-byte (a **timing side-channel**). Cryptographic libraries use constant-time comparison; *do not* hand-roll HMAC verification with ordinary string equality.

---

## 4. The complete toolkit

We focus on the Java/JVM ecosystem (primary audience), then note cross-language equivalents and CLI tools.

### 4.1 Java libraries landscape

| Library | Maven coordinates (group:artifact) | Strengths | Notes |
|---|---|---|---|
| **JJWT** (jsonwebtoken) | `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` (runtime) | Fluent builder/parser, safe defaults, popular, refuses `alg:none` on signed parsing. | Most common standalone choice. |
| **Nimbus JOSE + JWT** | `com.nimbusds:nimbus-jose-jwt` | Most complete JOSE impl (JWS, JWE, JWK, JWKS, all algs). Lower-level/explicit. | Used *under the hood* by Spring Security. Best for advanced needs. |
| **Auth0 java-jwt** | `com.auth0:java-jwt` (+ `com.auth0:jwks-rsa` for JWKS) | Clean API, good docs. | Pairs with `jwks-rsa` for JWKS fetching/caching. |
| **Spring Security OAuth2 Resource Server** | `org.springframework.boot:spring-boot-starter-oauth2-resource-server` | Production-grade: auto JWKS, validators, filter integration. | The right choice in a Spring app — don't reinvent. |
| **Fusionauth jwt** | `io.fusionauth:fusionauth-jwt` | Lightweight alternative. | Less common. |

> **What is Maven / "coordinates"?** Maven is the dominant JVM build/dependency tool; a dependency is identified by `groupId:artifactId:version` coordinates. Gradle uses the same coordinates with different syntax.

### 4.2 JJWT — core API surface

**Building (minting):**

| Method (builder) | Purpose |
|---|---|
| `Jwts.builder()` | Start a JWT builder. |
| `.header().add("kid", id).and()` | Set header params (e.g., `kid`). |
| `.issuer(iss)` / `.subject(sub)` / `.audience().add(aud).and()` | Set `iss`/`sub`/`aud`. |
| `.issuedAt(date)` / `.notBefore(date)` / `.expiration(date)` | Temporal claims. |
| `.id(jti)` | Set `jti`. |
| `.claim("scope", "...")` | Arbitrary custom claim. |
| `.signWith(key)` / `.signWith(key, alg)` | Choose key & algorithm. |
| `.compact()` | Serialize to the `a.b.c` string. |

**Parsing (verifying):**

| Method (parser) | Purpose |
|---|---|
| `Jwts.parser()` | Start a parser builder. |
| `.verifyWith(secretKey)` / `.verifyWith(publicKey)` | Set the verification key. |
| `.keyLocator(locator)` | Dynamically pick key by header (`kid`) — for rotation/JWKS. |
| `.requireIssuer(iss)` / `.requireAudience(aud)` | Enforce `iss`/`aud`. |
| `.clockSkewSeconds(n)` | Allowed clock skew (default 0). |
| `.build().parseSignedClaims(token)` | Verify signature + claims; returns `Jws<Claims>`; throws on failure. |

JJWT (modern versions) **rejects `none`** when you call the signed-parse path, and throws specific exceptions (`ExpiredJwtException`, `SignatureException`, `UnsupportedJwtException`, `MalformedJwtException`) you can catch distinctly.

### 4.3 Nimbus — core API surface

| Class | Purpose |
|---|---|
| `JWSObject`, `SignedJWT` | Represent signed tokens. |
| `JWSHeader`, `JWTClaimsSet` | Build header & claims. |
| `MACSigner` / `MACVerifier` | HMAC sign/verify. |
| `RSASSASigner` / `RSASSAVerifier` | RSA sign/verify. |
| `ECDSASigner` / `ECDSAVerifier` | ECDSA sign/verify. |
| `JWKSet`, `JWK`, `RSAKey`, `ECKey` | Key representations. |
| `RemoteJWKSet` + `JWKSource` | Fetch & cache a remote JWKS by URL. |
| `DefaultJWTProcessor` + `JWSVerificationKeySelector` | Full processor: selects key by `kid` + enforces allowed alg. |
| `DefaultJWTClaimsVerifier` | Enforce `iss`/`aud`/`exp`/`nbf` with leeway. |

### 4.4 Spring Security Resource Server — config surface

| Config / bean | Purpose | Default |
|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Issuer URL; triggers OIDC discovery + JWKS auto-config. | none |
| `spring.security.oauth2.resourceserver.jwt.jwks-uri` | Direct JWKS URL (skip discovery). | none |
| `spring.security.oauth2.resourceserver.jwt.audiences` | Expected audience(s). | none (you often add a custom validator) |
| `JwtDecoder` bean | The component that verifies tokens. | Auto-configured from issuer/jwks. |
| `JwtTimestampValidator` | Validates `exp`/`nbf` with clock skew. | clock skew 60s |
| `JwtIssuerValidator` | Validates `iss`. | from issuer-uri |
| `OAuth2TokenValidator<Jwt>` (custom) | Add `aud`, scopes, custom rules. | you implement |
| `JwtAuthenticationConverter` | Map claims (e.g., `scope`/`roles`) to Spring authorities. | maps `scope`/`scp` to `SCOPE_*`. |

### 4.5 CLI & debugging tools

| Tool | What it does |
|---|---|
| `jwt.io` (web) | Decode/inspect/verify tokens interactively. **Never paste production tokens into third-party sites.** |
| `jwt` CLI (e.g., `mike-engel/jwt-cli`) | `jwt decode <token>`, `jwt encode ...` locally. Safe for prod tokens (offline). |
| `openssl` | Generate keys: `openssl genrsa`, `openssl ecparam`, inspect with `openssl rsa -text`. |
| `step` (smallstep) | `step crypto jwt sign/verify/inspect`; great for scripting & key gen. |
| `curl` + `jq` | Fetch & inspect JWKS / discovery doc: `curl -s $ISSUER/.well-known/openid-configuration | jq .jwks_uri`. |
| `hashcat` / `jwt_tool` | **Offensive/testing**: crack weak HMAC secrets; test for `alg:none`/confusion. Use only on systems you own. |

> **What is `jq`?** A command-line JSON processor — filters/queries JSON, e.g., extracting `.jwks_uri` from a response.

---

## 5. Code examples by use case

All examples are Java unless noted. They are written to be copy-adaptable; non-obvious lines are commented. Imports are abbreviated where obvious.

### 5.1 Use case A — Mint & verify an HS256 token with JJWT (single service)

```java
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

public class HsTokens {

    // Generate ONCE, store securely (env/secret manager). Must be >= 256 bits for HS256.
    // Keys.hmacShaKeyFor enforces minimum key length; reject short secrets.
    private final SecretKey key;

    public HsTokens(byte[] secretBytes) {
        this.key = Keys.hmacShaKeyFor(secretBytes); // throws if < 256 bits
    }

    public String mint(String userId, String audience) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().add("kid", "hs-2024-06").and()   // even HMAC benefits from kid for rotation
                .issuer("https://auth.example.com")
                .subject(userId)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))  // 15-min TTL
                .id(java.util.UUID.randomUUID().toString())   // jti for denylisting
                .claim("scope", "orders:read orders:write")
                .signWith(key)                                 // alg inferred from key strength (HS256)
                .compact();
    }

    public Jws<Claims> verify(String token, String expectedAudience) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer("https://auth.example.com")     // enforce iss
                .requireAudience(expectedAudience)             // enforce aud (your service id)
                .clockSkewSeconds(30)                          // tolerate small clock drift
                .build()
                .parseSignedClaims(token);  // verifies signature + exp/nbf; throws on any failure
        // Throws: ExpiredJwtException, SignatureException, MalformedJwtException, etc.
    }
}
```

Why this is safe: `parseSignedClaims` (not `parseClaims`) requires a signature and refuses `alg:none`; the key is bound up front so the header can't redirect us; `iss`/`aud`/`exp` are all enforced.

### 5.2 Use case B — Mint with a private key (RS256), verify with a public key

```java
import io.jsonwebtoken.*;
import java.security.*;
import java.time.Instant;
import java.util.Date;

public class RsTokens {

    // Issuer side holds the PRIVATE key only.
    public String mint(PrivateKey privateKey, String kid, String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().add("kid", kid).and()         // verifier uses this to find the public key
                .issuer("https://auth.example.com")
                .subject(userId)
                .audience().add("orders-api").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(privateKey, Jwts.SIG.RS256)   // explicit algorithm
                .compact();
    }

    // Resource server side holds the PUBLIC key only — cannot mint, only verify.
    public Jws<Claims> verify(PublicKey publicKey, String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer("https://auth.example.com")
                .requireAudience("orders-api")
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token);
    }
}
```

Generate a test key pair on the CLI:

```bash
openssl genrsa -out private.pem 2048           # private key (issuer)
openssl rsa -in private.pem -pubout -out public.pem   # public key (verifiers)
```

### 5.3 Use case C — Verify against a remote JWKS with key rotation (Nimbus)

This is the realistic resource-server setup for tokens from an external IdP.

```java
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.*;
import java.net.URL;
import java.util.Set;

public class JwksVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public JwksVerifier(String jwksUrl, String issuer, String audience) throws Exception {
        // RemoteJWKSet fetches + caches the JWKS and auto-refreshes on unknown kid (rate-limited).
        JWKSource<SecurityContext> keySource =
                new RemoteJWKSet<>(new URL(jwksUrl));

        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();

        // Bind allowed algorithm(s) to the key selector. The header alg is matched against THIS,
        // so an attacker cannot downgrade to HS256 or none.
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
        p.setJWSKeySelector(keySelector);

        // Enforce iss/aud/exp/nbf. Require exp present; allow 30s skew (set on verifier if needed).
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                /* acceptedAudience */ audience,
                /* exactMatchClaims */ new JWTClaimsSet.Builder().issuer(issuer).build(),
                /* requiredClaims    */ Set.of("sub", "iat", "exp")));

        this.processor = p;
    }

    public JWTClaimsSet verify(String token) throws Exception {
        // Does: parse -> select key by kid from JWKS -> verify sig (RS256 only) -> verify claims.
        return processor.process(token, null);
    }
}
```

### 5.4 Use case D — Spring Boot resource server (production-grade, declarative)

`application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com   # auto-discovers JWKS + sets up validators
          audiences: orders-api                  # (Boot 3.4+) enforce aud; else add custom validator
```

Security config with an explicit audience validator (works on all versions) and scope-based authorization:

```java
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.util.List;

@Configuration
public class ResourceServerConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
          .authorizeHttpRequests(reg -> reg
              // Authorization by scope: token must carry SCOPE_orders:read etc.
              .requestMatchers("/orders/**").hasAuthority("SCOPE_orders:read")
              .anyRequest().authenticated())
          .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})) // uses auto-configured JwtDecoder
          .csrf(AbstractHttpConfigurer::disable);              // stateless API: CSRF N/A for Bearer tokens
        return http.build();
    }

    // Add aud + issuer validation explicitly (defense in depth).
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer); // discovery + JWKS
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audience = jwt ->
            jwt.getAudience().contains("orders-api")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audience));
        return decoder;
    }
}
```

`createDefaultWithIssuer` already validates `exp`/`nbf` (with default 60s skew) and `iss`. We add `aud`. Spring maps `scope`/`scp` claims to `SCOPE_*` authorities automatically.

### 5.5 Use case E — Access + refresh token flow with revocation hook

Short-lived access JWT + opaque refresh token stored server-side (so it *can* be revoked).

```java
// Pseudocode-ish service showing the pattern; persistence omitted for brevity.
public class AuthService {

    private final HsTokens accessTokens;            // mints short-lived access JWTs (15 min)
    private final RefreshStore refreshStore;        // DB/Redis: refreshId -> {userId, expiresAt, revoked}

    public TokenPair login(String userId) {
        String access = accessTokens.mint(userId, "orders-api");
        String refreshId = SecureRandomString.generate(32);   // opaque, high-entropy
        refreshStore.save(refreshId, userId, Instant.now().plus(Duration.ofDays(30)));
        return new TokenPair(access, refreshId);
    }

    public TokenPair refresh(String refreshId) {
        RefreshRecord r = refreshStore.find(refreshId)
            .filter(rec -> !rec.revoked() && rec.expiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new UnauthorizedException("invalid refresh"));

        // Refresh-token ROTATION: invalidate the old one, issue a new one (detects token theft).
        refreshStore.revoke(refreshId);
        String newRefresh = SecureRandomString.generate(32);
        refreshStore.save(newRefresh, r.userId(), Instant.now().plus(Duration.ofDays(30)));

        String newAccess = accessTokens.mint(r.userId(), "orders-api");
        return new TokenPair(newAccess, newRefresh);
    }

    public void logoutAllDevices(String userId) {
        refreshStore.revokeAllForUser(userId); // kills refresh ability; access tokens die within 15 min
    }
}
```

The revocation lever lives at the **refresh** step (and at a short access-token TTL), not in the access JWT itself.

### 5.6 Use case F — Validating an ID token from "Sign in with Google" (OIDC)

```java
// Conceptually identical to §5.3 but with Google's well-known endpoints and nonce check.
// jwks: https://www.googleapis.com/oauth2/v3/certs
// iss : https://accounts.google.com  (or accounts.google.com)
// aud : YOUR OAuth client_id
// Extra OIDC checks: aud == your client_id, azp if present, nonce == the one you sent.
```

> **What is an ID Token?** In OpenID Connect, the **ID token** is a JWT that asserts *the user's identity to the client* (name, email, `sub`). It's distinct from the **access token** (which authorizes API calls). Don't send ID tokens to APIs as access tokens, and don't accept ID tokens at your resource server.

### 5.7 Use case G — Demonstrating the `alg:none` rejection (test)

```java
// A token forged with alg=none and an empty signature.
String forged = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
              + base64Url("{\"sub\":\"admin\",\"exp\":9999999999}") + ".";  // empty 3rd segment

// JJWT signed-parse path MUST reject this:
assertThrows(UnsupportedJwtException.class, () ->
    Jwts.parser().verifyWith(key).build().parseSignedClaims(forged));
// Lesson: never use an "unsecured"/"parseUnsecured" path on untrusted input.
```

### 5.8 Use case H — Node.js equivalent (for polyglot teams)

```javascript
// jsonwebtoken + jwks-rsa, the de facto Node stack.
const jwt = require('jsonwebtoken');
const jwksClient = require('jwks-rsa');

const client = jwksClient({ jwksUri: 'https://auth.example.com/.well-known/jwks.json',
                            cache: true, rateLimit: true }); // cache + stampede protection

function getKey(header, cb) {
  client.getSigningKey(header.kid, (err, key) => cb(err, key && key.getPublicKey()));
}

function verify(token) {
  return new Promise((res, rej) =>
    jwt.verify(token, getKey, {
      algorithms: ['RS256'],          // CRITICAL allowlist — blocks none + confusion
      issuer: 'https://auth.example.com',
      audience: 'orders-api',
      clockTolerance: 30,
    }, (e, decoded) => e ? rej(e) : res(decoded)));
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Verification cost.** HMAC verify ≈ a few microseconds. RS256 verify is more expensive but still typically tens of microseconds; **RS256 *signing* is markedly slower than verifying** (RSA private-key ops dominate) — usually fine because the AS signs and many RSs verify. ES256 signs faster than RS256; verification is comparable.
- **JWKS caching is mandatory.** Never fetch the JWKS per request — cache it (respect `max-age`, refresh on unknown `kid` with rate limiting). A JWKS fetch on the hot path turns a microsecond op into a network round-trip and creates a dependency on the IdP's uptime.
- **Token size on the wire.** Every claim adds bytes to *every request*. Fat tokens (dozens of roles, big custom blobs) bloat headers; some proxies cap header size (e.g., ~8 KB default for many servers). Keep payloads lean; reference large permission sets by ID, don't inline them.
- **Avoid re-parsing.** In a request pipeline, parse/verify once and pass the decoded principal downstream; don't re-verify in every layer.

### 6.2 Correctness & concurrency

- **Use UTC epoch seconds**, not millis, for `exp`/`iat`/`nbf`. A common Java bug: passing `System.currentTimeMillis()` where seconds are expected → tokens "expire" ~50,000 years from now or instantly.
- **Set clock skew explicitly.** Many libraries default to 0; in distributed systems set 30–60s leeway, and run **NTP** on all hosts.
  > **What is NTP?** Network Time Protocol — keeps server clocks synchronized to a reference. Without it, clock drift breaks `exp`/`nbf` validation intermittently and confusingly.
- **Thread-safety:** JJWT parsers/builders and Nimbus processors are generally safe to reuse as singletons once built; verify per your library version.

### 6.3 Security (the big one)

A consolidated checklist (each item maps to a real attack):

1. **Allowlist algorithms.** Pin `alg` to exactly what you expect (e.g., only `RS256`). Never derive trust from the token's `alg`.
2. **Never accept `none`.** Use only signed-parse paths on untrusted tokens.
3. **Separate keys by type & purpose.** Don't reuse an RSA public key as if it could be an HMAC secret (the `RS→HS` confusion, §9.2).
4. **Strong HMAC secrets.** ≥256 bits of *random* entropy for HS256. No dictionary words, no app names. Weak secrets are crackable offline with `hashcat`.
5. **Validate `exp` and `nbf`.** Reject expired/not-yet-valid tokens. (Sounds obvious; libraries with the wrong API call skip it.)
6. **Validate `iss` and `aud`.** Without `aud`, a token meant for service X is replayable at service Y (the **confused-deputy / token-relay** problem).
7. **Short access-token TTL** (5–15 min). Limits the blast radius of a leaked token and speeds key rotation.
8. **TLS everywhere.** Bearer tokens are credentials; never send them over plaintext.
9. **Don't put secrets/PII in the payload** (it's readable). Use JWE only if you truly need confidential claims.
10. **Storage on the client.** For browsers: `localStorage` is exposed to XSS; **HttpOnly, Secure, SameSite cookies** mitigate XSS theft but require CSRF defenses. There is no perfect answer — match the threat model.
   > **What are XSS and CSRF?** *XSS (Cross-Site Scripting)* — attacker-injected JS runs in your page and can read `localStorage`. *CSRF (Cross-Site Request Forgery)* — a malicious site makes the browser send a request using cookies it can't read but that ride along automatically. Bearer tokens in headers are immune to CSRF; cookies need `SameSite`/anti-CSRF tokens.
11. **Refresh-token rotation + reuse detection.** If a rotated refresh token is presented twice, treat it as theft and revoke the whole family.
12. **Validate token `typ`/purpose.** Don't let an ID token act as an access token, or an email-verification token authorize an API call.

### 6.4 Observability

- **Log token *metadata*, never the raw token.** Log `jti`, `sub`, `iss`, `kid`, `exp`, and the *validation outcome/reason*. A raw token in logs is a credential leak.
- **Metrics to emit:** verification success/failure counts by reason (expired, bad-sig, bad-aud, unknown-kid), JWKS fetch latency/failures, and `kid` distribution (to watch a rotation roll out).
- **Trace the `kid`.** During rotation, dashboards showing the share of tokens per `kid` tell you exactly when it's safe to retire an old key.

### 6.5 Cost

- Stateless verification removes per-request session-store reads (lower latency/$$ at scale). But you pay in **token size bandwidth**, **CPU for asymmetric verify**, and **operational complexity** (key management, rotation, JWKS availability). Don't adopt JWT to "save Redis" if a Redis session is simpler for your scale.

### 6.6 Testability

- **Unit-test the validator's *negative* paths**: expired, future-`nbf`, wrong `aud`, wrong `iss`, tampered payload, `alg:none`, wrong-`kid`, RS→HS confusion. The happy path rarely breaks; the rejections do.
- **Use a test key pair / embedded JWKS** (e.g., spin a tiny JWKS server, or Spring's `MockMvc` with a generated `Jwt`). Nimbus can generate `RSAKey`/`ECKey` in tests.
- **Freeze time** (inject a `Clock`) so `exp`/`nbf` tests are deterministic.

### 6.7 Production hardening checklist

- Issuer signs with **asymmetric** keys; resource servers hold **public** keys only.
- Keys live in a **KMS/HSM** or secrets manager, never in source or plain env files in repo.
  > **What is a KMS / HSM?** *KMS (Key Management Service)* — a managed service (AWS KMS, GCP KMS, Vault) that stores keys and performs crypto ops so the raw key never leaves it. *HSM (Hardware Security Module)* — tamper-resistant hardware doing the same. Both keep the private signing key out of application memory.
- **Rotate keys** on a schedule (e.g., every 90 days) and have a tested **emergency rotation** runbook.
- **Short TTLs** + refresh-token rotation + a revocation mechanism for "log out now."
- JWKS endpoint is **highly available** and cached by clients; monitor it.
- Alert on spikes in verification failures (possible attack or a misconfigured rollout).

### 6.8 Anti-patterns (do not do)

- Using the *decode-without-verify* path on untrusted input "just to read claims," then trusting them.
- Storing the **HMAC secret** in every microservice (any one leak = forge-everything).
- **Long-lived access tokens** (hours/days) with no revocation — a leaked token is valid forever.
- Putting authorization data in a token and never revalidating it (a user demoted from admin keeps admin until `exp`).
- Trusting `jku`/`x5u`/embedded `jwk` from the token to fetch keys (lets the attacker supply their own key).
- Cramming megabytes of data / sensitive PII into the payload.
- Reinventing parsing/verification by hand instead of using a vetted library.

---

## 7. Advanced topics & deep internals

### 7.1 `alg` confusion / key confusion (deep)

The classic RS→HS attack: a server is coded to "verify with the key, algorithm from the header." The legitimate flow uses RS256 with the issuer's **public** key (which is, by design, *public*). The attacker crafts a token with `alg=HS256` and computes the HMAC using the **public key bytes as the HMAC secret**. A naive verifier that "uses the configured key + header alg" will run `HMAC-SHA256(token, publicKey)` — and it matches, because the attacker used the same public bytes. Forgery succeeds. **Fix:** bind the *algorithm* (and key *type*) at verification time; never let the header pick the algorithm. (This is why §5.3/§5.4 pin the algorithm in the key selector.)

### 7.2 The `none` algorithm (deep)

RFC 7515 defines `none` for *unsecured* JWS — legitimately useful only when integrity is guaranteed by another layer. The vulnerability is libraries that accept `none` on a *verification* call. Modern libraries refuse it on signed-parse paths; some have an explicit `parseUnsecured`. **Rule:** untrusted input → only signed-parse with an algorithm allowlist that excludes `none`.

### 7.3 Critical-header parameter (`crit`)

> The `crit` header (RFC 7515) lists header params the recipient *must* understand or reject the token. Used for extensions. If you don't process a `crit` you don't recognize, you must reject — most validation is at the library level, but be aware when adding custom headers.

### 7.4 Nested JWT (sign-then-encrypt) and JWE

> **What is JWE?** JSON Web Encryption (RFC 7516): a JWT whose payload is **encrypted**, giving confidentiality. Its compact form has **five** dot-separated parts (`header.encrypted_key.iv.ciphertext.tag`), not three. Used when claims are sensitive. A common high-assurance pattern is **nested JWT**: first **sign** (JWS) for authenticity, then **encrypt** the whole thing (JWE) for confidentiality — `cty: "JWT"` in the outer header signals the nesting. Heavier; use only when you truly need confidential claims.

### 7.5 `b64: false` (unencoded payload, RFC 7797)

A JWS option where the payload is *not* base64url-encoded (useful for signing large/binary content in place). Niche; relevant if you sign HTTP message bodies. Library- and version-dependent.

### 7.6 Sender-constrained tokens: DPoP & mTLS binding

Plain Bearer tokens are **bearer** credentials: whoever holds one can use it (theft = full access). Two advanced binding mechanisms tie a token to a *specific client*:

- **mTLS-bound tokens (RFC 8705):** the token records a hash of the client's TLS certificate (`cnf.x5t#S256`); the RS checks the presenting client's cert matches. Stolen token is useless without the client's private key.
  > **What is mTLS?** Mutual TLS — both client *and* server present certificates, so the server authenticates the client cryptographically, not just by a token.
- **DPoP (RFC 9449):** the client proves possession of a key by sending a signed `DPoP` proof header per request; the access token carries a `cnf.jkt` thumbprint binding it to that key. Defeats simple token replay even without mTLS.

These convert "bearer" into "holder-of-key," dramatically reducing theft impact. Use for high-value APIs.

### 7.7 The revocation problem (the deep version)

Because verification is local and offline, a valid-looking JWT is accepted until `exp`. Strategies, with tradeoffs:

| Strategy | How | Pro | Con |
|---|---|---|---|
| **Short TTL + refresh** | Access tokens 5–15 min; revoke at refresh. | Mostly stateless; small window. | Up to TTL of stale access. |
| **Denylist (blocklist)** | Store revoked `jti`/`sub` in Redis; check on verify. | True immediate revocation. | Reintroduces per-request state (partial). |
| **Allowlist / session-bound** | Token references a server session that must exist. | Full control. | Basically stateful again. |
| **Token introspection** | RS calls AS `/introspect` to ask "is this still active?" | Authoritative, real-time. | Network call per check (cache to mitigate). |
| **Key rotation as nuke** | Rotate signing key to invalidate *all* tokens. | Instant mass revocation. | Blunt — logs out everyone. |
| **Token versioning** | Token carries `ver` / `auth_time`; bump server-side counter on logout-all. | Selective per-user mass revoke. | Small per-user state lookup. |

Most production systems use **short TTL + refresh** plus a **small denylist** for the "revoke now" case (keyed by `jti`, with entries auto-expiring at the token's `exp` so the list stays bounded).

### 7.8 Token introspection (RFC 7662) — the opaque-token counterpart

> **What is introspection?** An OAuth endpoint (`POST /introspect`) where a resource server presents a token and the AS returns `{"active": true, "sub": ..., "scope": ..., "exp": ...}` (or `{"active": false}`). It is the *stateful* answer to revocation: the RS asks the authority on each (cached) check. It's how **opaque** access tokens are validated — and it can also validate JWTs when you want authoritative revocation. The cost is a network dependency on the AS; mitigate with short-lived caching.

### 7.9 JWT vs opaque tokens (deep comparison)

| Dimension | JWT (self-contained) | Opaque (reference) token |
|---|---|---|
| Validation | Local signature check (offline) | Introspection call or store lookup |
| Revocation | Hard (needs TTL/denylist tricks) | Trivial (delete the record) |
| Leakage of data | Claims readable by anyone | Reveals nothing (random string) |
| Cross-service | Excellent (no callback) | Needs introspection/shared store |
| Size | Larger (carries claims) | Tiny (a random id) |
| Issuer coupling | Loose at steady state (needs JWKS) | Tight (every check hits AS/store) |
| Best for | Microservices, OIDC, scale | When revocation/secrecy dominate |

A common hybrid (the **"phantom token"** / token-handler pattern): issue **opaque** tokens to *external* clients (revocable, leak-proof), and have the API gateway exchange/introspect them into **internal JWTs** for downstream microservices (fast, stateless internally). Best of both worlds; more moving parts.

### 7.10 Tuning knobs summary

- **Access-token TTL:** 5–15 min typical. Shorter = safer + faster rotation, but more refreshes.
- **Refresh-token TTL:** hours to weeks; rotate on every use.
- **Clock skew:** 30–60 s.
- **JWKS cache:** honor `max-age`; cap min refresh interval (e.g., ≥5 min) to prevent stampedes; refresh-on-unknown-`kid`.
- **Algorithm:** prefer `ES256`/`EdDSA` (small, fast, asymmetric); `RS256` for compatibility; `HS256` only intra-boundary.
- **Key size:** RSA ≥ 2048 (3072 for higher assurance); ECDSA P-256+.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing an algorithm

```
Do verifiers and the minter share the same trust boundary (one team/one service)?
  YES → HS256 is fine (simplest). Keep the secret ≥256-bit, in a secret manager.
  NO  → Use asymmetric (only minter can forge):
          Need smallest tokens / modern → ES256 (or EdDSA if supported everywhere).
          Need broad library/IdP compatibility → RS256 (PS256 if available).
Are third parties verifying your tokens? → Asymmetric, publish via JWKS.
```

### 8.2 JWT vs opaque vs session

| Situation | Recommended |
|---|---|
| Monolith, single server, simple login | Server session (cookie) — JWT adds complexity for no gain. |
| SPA/mobile hitting your own API at scale | Short-TTL JWT access + opaque refresh. |
| Many microservices needing user identity | Asymmetric JWT + JWKS (identity propagation). |
| Must revoke instantly / tokens carry sensitive data | Opaque tokens + introspection (or JWE/short TTL + denylist). |
| Third-party SSO / "Sign in with X" | OIDC JWT ID tokens + OAuth access tokens. |
| External clients, internal microservices | Phantom-token hybrid (opaque outside, JWT inside). |

### 8.3 "Use when / avoid when"

**Use JWT when:** offline/local validation matters; multiple independent verifiers; standardized OIDC/OAuth integration; short-lived signed assertions.

**Avoid JWT when:** you need guaranteed instant revocation as the primary requirement; tokens would carry confidential data you can't encrypt; a simple revocable session is sufficient; you can't operate key management/JWKS reliably.

### 8.4 Cookie vs Authorization header (browser)

| | `Authorization: Bearer` (header, token in JS-readable store) | HttpOnly cookie |
|---|---|---|
| XSS theft | Vulnerable if in `localStorage` | Protected (JS can't read HttpOnly) |
| CSRF | Immune (not auto-sent) | Needs `SameSite`/CSRF tokens |
| Cross-domain APIs | Easy | Harder (cookie scoping/CORS) |
| Recommendation | Common for APIs/mobile | Good for same-site web apps with CSRF defenses |

---

## 9. Failure modes & debugging

### 9.1 Vulnerability catalog (with detection)

| Vuln | What happens | How to detect / test | Fix |
|---|---|---|---|
| **`alg:none`** | Verifier accepts unsigned token → full forgery. | Send a `none` token (empty sig) to your endpoint; must 401. (`jwt_tool` automates.) | Algorithm allowlist; signed-parse only. |
| **RS→HS confusion** | Attacker HMACs with the public key; naive verify accepts. | Re-sign a token with `alg=HS256` using your published public key bytes; must 401. | Pin algorithm/key-type at verify. |
| **Weak HMAC secret** | Offline brute force of `HS256` secret → forge tokens. | `hashcat -m 16500 token.txt wordlist` against a *test* token. | ≥256-bit random secret; rotate. |
| **Missing `exp` check** | Expired/forever tokens accepted. | Send a long-expired token; must 401. | Use a validator that enforces `exp`. |
| **Missing `aud` check** | Token for service X replayed at Y. | Present a valid token minted for another audience. | Enforce `aud` membership. |
| **Missing `iss` check** | Token from an untrusted issuer accepted. | Mint with a different `iss`. | Enforce `iss`. |
| **`kid` injection / path traversal** | `kid` used to load a key from a file/SQL → traversal/SQLi → attacker-chosen key. | Fuzz `kid` with `../`, SQL meta-chars. | Treat `kid` as an opaque lookup key; whitelist. |
| **`jku`/`x5u` SSRF & key swap** | Verifier fetches key from attacker URL in token. | Set `jku` to an attacker-controlled host. | Don't trust token-supplied key URLs; allowlist. |
| **Cracked/leaked private key** | Mass forgery. | Audit key access; watch for anomalous `kid`. | KMS/HSM; emergency rotation runbook. |
| **Token in logs/URLs** | Credential leakage. | Grep logs/access logs for `eyJ`. | Redact; never log raw tokens; tokens in headers not query strings. |
| **No revocation** | Leaked token valid until `exp`. | Try a token after "logout." | Short TTL + denylist/introspection. |

> **What is SSRF?** Server-Side Request Forgery — tricking the server into making HTTP requests to attacker-chosen targets (e.g., internal metadata endpoints). Token-supplied key URLs (`jku`) are an SSRF vector.

### 9.2 Diagnosing a "401 / token rejected" in production

A systematic playbook:

1. **Decode the token offline** (`jwt decode`, or `cut`+`base64`). Read `alg`, `kid`, `iss`, `aud`, `exp`, `iat`.
2. **Is it expired?** Compare `exp` to `date +%s`. If now ≥ exp → expired (most common); check client refresh logic.
3. **`nbf` in the future / clock skew?** If `nbf` > now on the verifier, suspect clock drift → check NTP on both hosts; check leeway config.
4. **Wrong `aud`/`iss`?** Compare token's `aud`/`iss` to what the RS expects. Misconfig in either the AS or RS config is extremely common.
5. **`kid` not in JWKS?** `curl $JWKS_URI | jq '.keys[].kid'` and check the token's `kid` is present. If absent → the verifier's JWKS cache is stale (recent rotation) → force refresh; or the AS rolled a key without publishing first.
6. **Signature invalid with the right key?** Suspect the token was re-serialized somewhere (a proxy re-encoding it), or the wrong key/alg. Verify with `step crypto jwt verify` using the exact JWKS.
7. **Algorithm mismatch?** Token `alg` not in the RS allowlist (e.g., AS switched RS256→ES256). Align config.

### 9.3 Real-world incident patterns

- **"Everyone got logged out at 3am."** An emergency key rotation removed the old key from JWKS *before* outstanding tokens expired (skipped the overlap window). Fix: keep retired keys ≥ max TTL; automate the phased rollout.
- **"Intermittent 401s under load."** One app server's clock drifted; tokens at the boundary of `nbf`/`exp` failed only on that host. Fix: NTP + skew leeway; alert on per-host failure rate.
- **"Auth works in dev, fails in prod."** Different issuer URLs (`http://localhost` vs `https://auth…`) → `iss` mismatch. Fix: environment-correct issuer config; never hardcode.
- **CVE-class library bugs:** several JWT libraries historically accepted `alg:none` or were vulnerable to RS→HS confusion. *Keep libraries patched*; pin algorithms regardless.
- **"We put the user's role in the JWT and a fired admin kept admin for an hour."** Long TTL + no revalidation. Fix: short TTL, revalidate sensitive authorization server-side, or version tokens.

### 9.4 Tools for debugging (recap)

`jwt decode`, `step crypto jwt inspect/verify`, `curl + jq` on `.well-known`/JWKS, `openssl` to inspect keys, `jwt_tool`/`hashcat` for offensive testing (own systems only), and your library's specific exceptions (`ExpiredJwtException`, `SignatureException`, …) for precise server-side diagnosis.

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. "Senior-signal" questions (tradeoff/justification) are marked ★.

**Q1. What are the three parts of a JWT and what's in each?**
**A.** Header (JOSE metadata: `alg`, `typ`, `kid`), payload (claims set — registered like `iss/sub/aud/exp/iat/nbf/jti` plus custom), and signature over `base64url(header).base64url(payload)`. All base64url-encoded, dot-joined.
- *Probe: Is the payload encrypted?* No — base64url is reversible; a signed JWT (JWS) is readable by anyone; use JWE for confidentiality.
- *Probe: Why is the signature computed over the encoded strings, not the JSON?* So verification is byte-exact and immune to JSON re-serialization differences (key order/whitespace).
- *Probe: What does `kid` do?* Identifies which key signed the token, enabling graceful key rotation and JWKS lookup.

**Q2. HS256 vs RS256 — when do you use each? ★**
**A.** HS256 is symmetric HMAC: one shared secret signs and verifies, so any verifier can also forge. Use it only inside a single trust boundary. RS256 is asymmetric: the AS holds a private key, verifiers hold only the public key (publishable via JWKS) and cannot forge. Use it for multiple/independent verifiers, microservices, and third parties. Prefer ES256/EdDSA for smaller, faster asymmetric tokens.
- *Probe: A breached resource server — impact under each?* HS256: attacker gets the secret → can forge any token (catastrophic). RS256: attacker gets only the public key → cannot forge.
- *Probe: Why might you still pick RS256 over ES256?* Broader compatibility with older IdPs/libraries; otherwise ES256/EdDSA are preferable.

**Q3. Walk me through verifying a JWT, step by step.**
**A.** Parse 3 parts; decode header/payload; check `alg` against an allowlist (reject `none`); select key by `kid` (from JWKS); verify signature over received bytes with the pinned algorithm; validate `exp`/`nbf` (with skew), `iss`, `aud`, `typ`; optional revocation check; then authorize via scopes/roles.
- *Probe: Where do most security bugs hide?* In trusting the header's `alg` to pick the algorithm, and in skipping `aud`/`exp`.
- *Probe: Why is authorization separate from authentication?* Verifying *who* the caller is (signature + claims) is distinct from deciding *what* they may do (scopes/roles).

**Q4. Explain the `alg:none` and RS→HS confusion attacks and the single fix.**
**A.** `alg:none` = a forged unsigned token that vulnerable libraries accept. RS→HS confusion = attacker signs with `HS256` using the *public* RSA key as the HMAC secret; a verifier that uses "configured key + header alg" computes a matching HMAC. The single defense for both: **never let the token's header choose the algorithm** — pin an explicit algorithm (and key type) at verification.
- *Probe: Why does the public key work as the HMAC secret?* Because it's known to the attacker and the verifier uses the same bytes; HMAC needs only a shared input, which the public key provides.
- *Probe: Library-level mitigations?* Modern libs reject `none` on signed-parse and require explicit algorithm binding.

**Q5. Why can't you revoke a JWT, and how do teams handle it? ★**
**A.** Verification is local/offline; no authority is consulted, so a valid signature + unexpired `exp` is accepted regardless. Mitigations: short access-token TTL (5–15 min) + revocable refresh tokens (revoke at refresh), a `jti`/`sub` denylist in Redis for immediate revocation, token introspection for authoritative checks, or key rotation as a mass "nuke."
- *Probe: How do you keep a denylist bounded?* Auto-expire entries at the token's `exp`.
- *Probe: Tradeoff of introspection?* Reintroduces a per-request network dependency (partly defeating statelessness); cache briefly.

**Q6. JWT vs opaque tokens — pick one for a public API that must support instant logout, and justify. ★**
**A.** Opaque + introspection, because instant revocation is the dominant requirement and opaque tokens are trivially revocable (delete the record) and leak no data. Or a hybrid: opaque outside, JWT internally (phantom token). A pure JWT would force short TTL + denylist hacks to approximate revocation.
- *Probe: What do you lose with opaque?* Offline validation; you add an introspection dependency.
- *Probe: How does the hybrid get both?* External tokens stay revocable; the gateway exchanges them into short-lived internal JWTs for fast, stateless microservice checks.

**Q7. What's a refresh token and why pair it with a short-lived access token?**
**A.** A long-lived (usually opaque, server-stored) credential the client exchanges for new access tokens. Pairing means access tokens can be short (limiting leak blast radius and enabling fast key rotation) while the user stays logged in; the refresh step is the revocation lever.
- *Probe: Refresh-token rotation?* Issue a new refresh token each use and invalidate the old; if an old one reappears, it's theft → revoke the family.
- *Probe: Where to store refresh tokens in a browser?* Prefer HttpOnly Secure SameSite cookie or a backend-for-frontend, to limit XSS exposure.

**Q8. How does key rotation work without downtime?**
**A.** Phased via `kid`/JWKS: publish the new public key in JWKS *before* signing with it (warm caches), cut over signing to the new `kid` (old tokens still verify against the still-published old key), then after ≥ max access-token TTL, remove the old key.
- *Probe: What if you remove the old key too early?* Outstanding tokens fail → mass 401s (a real incident pattern).
- *Probe: How does short TTL help rotation?* The overlap window you must wait equals the max token TTL — shorter TTL = faster, safer retirement.

**Q9. What must you check besides the signature?**
**A.** `exp` (not expired) and `nbf` (active) with clock-skew leeway, `iss` (trusted issuer), `aud` (this service is an intended recipient), and `typ`/purpose. Skipping `aud` enables cross-service replay; skipping `exp` makes leaks permanent.
- *Probe: Are `exp` values seconds or millis?* Seconds since epoch (NumericDate) — a frequent Java bug source.
- *Probe: What's clock skew leeway and a typical value?* Tolerance for clock drift between hosts; ~30–60s.

**Q10. What is JWKS and how does a resource server use it?**
**A.** A JWK Set: a JSON document of the issuer's public keys (each with a `kid`), published at a `.well-known` HTTPS URL (found via OIDC discovery). The RS caches it, matches the token's `kid` to a JWK, and verifies. On unknown `kid` it refreshes once (rate-limited), respecting cache headers.
- *Probe: Why not hardcode the public key?* You could never rotate without redeploying every verifier.
- *Probe: Caching pitfalls?* Stale cache after rotation (refresh-on-unknown-kid) and stampedes on cold cache (rate-limit refreshes).

**Q11. Why is putting authorization data (roles) in a JWT risky, and what would you do instead? ★**
**A.** Because the token is a *snapshot*: a demoted user keeps elevated roles until `exp` (no live revalidation). Mitigations: short TTL, revalidate sensitive permissions server-side at decision time, or use token versioning/`auth_time` with a bumpable counter. For low-risk authz, in-token roles are fine if TTL is short.
- *Probe: Coarse vs fine-grained authz in tokens?* Put stable coarse scopes in the token; resolve fine-grained, fast-changing permissions server-side.

**Q12. How do you make a Bearer token resistant to theft/replay?**
**A.** TLS always; short TTL; HttpOnly cookie storage for browsers; and sender-constrained tokens — **DPoP** (per-request proof-of-possession) or **mTLS-bound** tokens (`cnf` thumbprint), which tie the token to a client key so a stolen token alone is useless.
- *Probe: Difference between DPoP and mTLS binding?* mTLS binds to the client's TLS cert; DPoP binds to an app-level key via a signed proof header — DPoP works without mutual TLS infra.

---

## 11. Glossary

- **Access token** — short-lived credential authorizing API calls; often a JWT.
- **`alg`** — JOSE header param naming the signing/encryption algorithm.
- **`alg:none`** — the unsecured "no signature" mode; a forgery vector if accepted on verification.
- **Asymmetric crypto** — key pair (private signs, public verifies); RSA/ECDSA/EdDSA.
- **`aud` (audience)** — intended recipient(s) of the token; must be validated.
- **Authorization Server (AS) / IdP** — trusted party that authenticates users and mints tokens.
- **Base64 / base64url** — reversible binary-to-text encodings; base64url is URL-safe and unpadded. *Not* encryption.
- **Bearer token** — any token where mere possession grants access.
- **Claim** — a single assertion (name/value) in the payload.
- **Clock skew** — clock drift between hosts; absorbed by validation leeway.
- **`cnf`** — confirmation claim binding a token to a client key (DPoP/mTLS).
- **CSRF** — Cross-Site Request Forgery; exploits auto-sent cookies.
- **DPoP (RFC 9449)** — proof-of-possession scheme binding a token to a client key per request.
- **ECDSA / ES256** — elliptic-curve signatures; small, fast, asymmetric.
- **EdDSA / Ed25519** — modern, robust Edwards-curve signatures.
- **`exp` (expiration)** — instant after which the token is invalid (epoch seconds).
- **HMAC / HS256** — symmetric MAC using a shared secret; same key signs and verifies.
- **HSM** — tamper-resistant hardware that stores keys and does crypto.
- **ID token** — OIDC JWT asserting user identity to the client (not for APIs).
- **Integrity / Authenticity / Confidentiality** — unmodified / from-claimed-source / hidden-from-others.
- **Introspection (RFC 7662)** — AS endpoint that reports whether a token is currently active.
- **`iss` (issuer)** — who minted the token; must be validated.
- **`iat` (issued at)** — when the token was minted (epoch seconds).
- **JOSE** — the RFC family for JSON signing/encryption (7515–7519, 8725…).
- **`jku` / `x5u`** — header params pointing to key material URLs; dangerous if trusted from the token.
- **`jti` (JWT ID)** — unique token id; enables denylists/replay detection.
- **JWA (RFC 7518)** — JSON Web Algorithms; defines `alg` values.
- **JWE (RFC 7516)** — encrypted JWT (five parts); provides confidentiality.
- **JWK / JWKS (RFC 7517)** — JSON key / set of keys; JWKS is published for verification.
- **JWS (RFC 7515)** — signed JWT (three parts); provides integrity/authenticity.
- **JWT (RFC 7519)** — the token concept, realized as JWS or JWE.
- **`kid` (key id)** — identifies the signing key; enables rotation/JWKS lookup.
- **KMS** — managed key service performing crypto so raw keys never leave it.
- **mTLS (RFC 8705)** — mutual TLS; can bind a token to a client certificate.
- **`nbf` (not before)** — instant before which the token is invalid.
- **NTP** — Network Time Protocol; keeps clocks synchronized.
- **NumericDate** — epoch **seconds** (not millis) used by time claims.
- **Opaque token** — a meaningless random reference whose meaning lives server-side.
- **OAuth 2.0 (RFC 6749)** — authorization framework defining AS/RS/client roles, grants, scopes.
- **OIDC** — OpenID Connect; identity layer on OAuth that defines ID tokens.
- **Phantom token** — hybrid: opaque externally, JWT internally.
- **Refresh token** — long-lived (usually opaque) credential to obtain new access tokens; revocable.
- **Resource Server (RS)** — API that verifies tokens to protect endpoints.
- **RSA / RS256 / PS256** — RSA signatures (PKCS1v1.5 / PSS padding).
- **Scope** — OAuth permission string (space-delimited) carried in a token.
- **Session (server-side)** — stateful login record referenced by a session id.
- **Signing input** — `base64url(header).base64url(payload)`, the bytes that get signed.
- **SSRF** — Server-Side Request Forgery; tricking a server into attacker-chosen requests.
- **`sub` (subject)** — the principal the token is about (usually user id).
- **Symmetric crypto** — one shared secret for both operations (HMAC).
- **TLS** — encryption layer under HTTPS; protects tokens on the wire only.
- **`typ`** — token media type (`JWT`, `at+jwt`).
- **XSS** — Cross-Site Scripting; injected JS that can steal `localStorage` tokens.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
STRUCTURE:  header.payload.signature  (all base64url; payload is READABLE, not encrypted)
HEADER:     alg, typ, kid             (attacker-controlled — never trust alg to pick algorithm)
CLAIMS:     iss sub aud exp iat nbf jti  (times = epoch SECONDS, not millis)
SIGN INPUT: base64url(header) + "." + base64url(payload)

ALGORITHMS:
  HS256  symmetric (shared secret; verifier can forge) → single trust boundary only
  RS256  asymmetric RSA (public verifies, private signs) → compat default
  ES256  asymmetric ECC → modern default (smaller/faster)   EdDSA → robust modern
  none   → NEVER accept

VERIFY (order): parse → alg ALLOWLIST → key by kid (JWKS) → sig → exp/nbf(+skew)
                → iss → aud → typ → (revocation) → authorize

KEY DIST:  JWKS at .well-known (via OIDC discovery); rotate via kid:
  publish new BEFORE signing → cut over → keep old ≥ max TTL → retire

REVOCATION: short TTL (5–15m) + revocable refresh + jti denylist (Redis, auto-expire)
            or introspection (authoritative, network cost)

TOP VULNS:  alg:none | RS→HS confusion | weak HMAC secret | missing aud/exp/iss
            | kid injection | jku SSRF/key swap | tokens in logs | no revocation
SINGLE FIX for alg attacks: pin algorithm + key type at verification.

DEFAULTS/NUMBERS: HMAC secret ≥256-bit | RSA ≥2048 | skew 30–60s | access TTL 5–15m
JAVA: JJWT (parseSignedClaims) | Nimbus (DefaultJWTProcessor) | Spring OAuth2 RS

DON'T: secrets/PII in payload | long-lived access tokens | decode-without-verify
       | HMAC secret in every service | trust token-supplied key URLs
```

### 12.2 Self-test (no answers — recall practice)

1. Walk through the full verification algorithm in order. At which step would `aud` validation prevent a cross-service replay, and what goes wrong if you skip it?
2. An attacker has your *published RSA public key*. Describe precisely how they could forge a token if your verifier "uses the configured key with the header's algorithm," and the one-line fix.
3. You must rotate signing keys with zero downtime and zero rejected valid tokens. List the four phases and explain why removing the old key too early causes mass 401s.
4. Your product requires "log out of all devices instantly." Pure JWTs can't do this — design a concrete solution and state exactly where the revocation enforcement happens and what state you keep.
5. Choose an algorithm (HS256 / RS256 / ES256 / EdDSA) for: (a) a single monolith; (b) 15 microservices behind a gateway; (c) tokens verified by external partners. Justify each.
6. A user reports intermittent 401s only when their requests hit one specific pod. Give the most likely root cause and the exact commands you'd run to confirm it.
7. Explain why a signed JWT is not a place to store a user's national ID number, and name the two correct alternatives.
8. Compare JWT, opaque token, and the phantom-token hybrid on validation cost, revocation, and data leakage — then pick one for a high-security banking API and defend it.
```

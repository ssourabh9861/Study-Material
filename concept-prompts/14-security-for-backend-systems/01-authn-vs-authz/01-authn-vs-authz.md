# Authentication vs Authorization

> An exhaustive engineering-handbook chapter for senior JVM/backend developers. Built from first principles up to production internals, edge cases, and interview-grade depth.

---

## 1. Overview & where it fits

### The one-sentence distinction

- **Authentication (AuthN)** answers **"Who are you, and can you prove it?"**
- **Authorization (AuthZ)** answers **"Now that I know who you are, what are you allowed to do?"**

These are two distinct phases of every secure request. AuthN establishes an **identity** (a principal); AuthZ takes that identity plus the **action** and **resource** and renders a **decision** (permit / deny). A system can authenticate you perfectly and still — correctly — refuse to authorize you. Conversely, you must never authorize an unauthenticated principal except through a deliberately anonymous policy.

A quick vocabulary anchor (terms expanded in the Glossary):

- **Principal / subject** — the entity making the request: a human user, a service account, a device, a background job.
- **Credential** — the secret or proof a principal presents to authenticate (password, private key, token).
- **Identity** — the established, verified record of *who* the principal is, usually carrying attributes (email, roles, tenant).
- **Resource** — the thing being acted on (a row, a file, an API endpoint, a Kafka topic).
- **Action / verb** — what the principal wants to do to the resource (read, write, delete, transfer).
- **Policy** — the rules that map (principal, action, resource, context) → decision.

### The problem each solves

Without **authentication**, you cannot attribute actions to anyone; every request is anonymous, so accountability, personalization, rate-limiting per user, and any per-user data become impossible. Without **authorization**, every authenticated user can do everything — the moment you have more than one trust level (admin vs user, tenant A vs tenant B, read vs write), you need authZ. In practice almost every nontrivial backend needs both.

### When you reach for each

| Situation | You need |
|---|---|
| Users log in to a web/mobile app | AuthN (sessions or tokens) |
| Service A calls service B in a mesh | AuthN (mTLS / service tokens) |
| Admin can delete users, regular user cannot | AuthZ (RBAC) |
| Tenant A must never see Tenant B's data | AuthZ (multi-tenancy isolation, often ABAC/ReBAC) |
| "Only the document owner and people they shared with can edit" | AuthZ (ReBAC/ACLs) |
| Block expired/leaked credentials | AuthN (token expiry, revocation) |
| Audit "who did what" | Both: AuthN gives the *who*, AuthZ gives the *was-it-allowed* |

### The mental model (one paragraph)

Think of a secured building. The **lobby turnstile** checks your badge — that is authentication: it verifies you are a real employee with a valid badge and stamps your wrist with an identity. Once inside, each **door** has its own reader that decides whether *this* badge may open *this* door right now — that is authorization. The turnstile does not know or care which rooms you may enter; the doors do not re-verify your badge's authenticity, they trust the turnstile's stamp and only consult the rule "is employee #1234 allowed in the server room after hours?" The badge (credential) proves identity once; the doors (policy enforcement points) decide access many times. A well-designed backend separates these exactly this way: authenticate once at the edge, carry a trustworthy identity inward, and authorize at every meaningful boundary.

### A canonical request lifecycle

```
1. Client presents a credential                      ← AuthN input
2. AuthN verifies it → establishes a Principal        ← AuthN
3. System loads the Principal's attributes/roles      ← identity enrichment
4. For the requested (action, resource, context):
      Policy decision point evaluates rules            ← AuthZ
5. Permit → execute; Deny → 403; No identity → 401     ← enforcement
6. Emit audit log of the decision                      ← observability
```

Note the HTTP status convention, which trips up many engineers:

- **401 Unauthorized** actually means **un-authenticated** ("I don't know who you are; authenticate"). The name is a historical misnomer.
- **403 Forbidden** means **authenticated but not authorized** ("I know who you are; you still can't").

Returning 401 when you mean 403 leaks nothing but confuses clients; returning 403 when you mean 401 can hide that the user just needs to log in. Some systems deliberately return **404** instead of 403 to avoid revealing a resource exists to an unauthorized user (resource-existence as an information leak) — a real tradeoff covered later.

---

## 2. Foundations from first principles

### 2.1 What "proving identity" actually means

Authentication is a **proof protocol**. The verifier holds (or can derive) something that lets it check a claim without — ideally — ever seeing the raw secret. Three classical **authentication factors**:

1. **Something you know** — a password, PIN, passphrase, security-question answer.
2. **Something you have** — a phone running an authenticator app, a hardware security key (YubiKey), a smart card, a TLS client certificate, a SIM card.
3. **Something you are** — biometrics: fingerprint, face, iris, voice.

Two more are sometimes added:

4. **Somewhere you are** — location/IP/geofence (weak, contextual).
5. **Something you do** — behavioral biometrics (typing cadence, gait).

**Multi-factor authentication (MFA)** = requiring **two or more factors from *different categories***. Two passwords are *not* MFA (both "know"). Password + TOTP code *is* MFA (know + have). The security gain comes from the categories being independently hard to compromise: stealing a password (phishing) does not also steal the phone.

### 2.2 Passwords: storage and verification (do this right or nothing else matters)

Never store passwords in plaintext, and never store them reversibly encrypted. Store a **salted, slow hash**.

- **Hash function** — a one-way function: easy to compute `H(password)`, infeasible to invert. But generic hashes (SHA-256) are *fast*, which helps attackers brute-force. Use **password hashing functions** designed to be slow and memory-hard.
- **Salt** — a unique random value per password, stored alongside the hash. It defeats **rainbow tables** (precomputed hash→password lookups) and ensures two users with the same password get different hashes.
- **Pepper** — an optional secret added to all passwords, stored separately (e.g., in an HSM or app config, not the DB), so a DB leak alone is insufficient.
- **Work factor / cost** — a tunable parameter making hashing deliberately expensive, raised over time as hardware improves.

Recommended algorithms (in rough order of modern preference):

| Algorithm | Type | Key parameters | Notes |
|---|---|---|---|
| **Argon2id** | Memory-hard | memory (MiB), iterations, parallelism | OWASP's first choice; resists GPU/ASIC attacks |
| **scrypt** | Memory-hard | N (cost), r, p | Good, older than Argon2 |
| **bcrypt** | CPU-hard | cost (log rounds), default ~10–12 | Ubiquitous on JVM; 72-byte input limit (a real gotcha) |
| **PBKDF2** | CPU-hard | iterations, hash, salt | FIPS-approved; use when compliance demands it |

OWASP-style starting points (verify current guidance, these drift): **Argon2id** ~19 MiB memory, 2 iterations, parallelism 1; **bcrypt** cost 10–12; **PBKDF2-HMAC-SHA256** ≥ 600,000 iterations. These are version-/year-sensitive — always check the current OWASP Password Storage Cheat Sheet rather than hardcoding from memory.

Java example using Spring Security's `PasswordEncoder` (idiomatic, supports upgrade-on-login):

```java
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

// DelegatingPasswordEncoder: stores the algo as a prefix like {argon2}...,
// so you can migrate algorithms without breaking existing hashes.
PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

// Registration:
String stored = encoder.encode(rawPassword); // -> "{argon2}$argon2id$v=19$m=...$..."

// Login:
boolean ok = encoder.matches(rawPassword, stored);

// Upgrade-on-login: if the stored hash uses weak/old params, re-hash transparently.
if (ok && encoder.upgradeEncoding(stored)) {
    String rehashed = encoder.encode(rawPassword);
    userRepository.updatePasswordHash(userId, rehashed);
}
```

Critical correctness rules:

- Use **constant-time comparison** for any secret comparison (the encoders above do this internally; if you ever compare tokens manually, use `MessageDigest.isEqual`, never `String.equals`, to avoid **timing attacks** — where response time leaks how many bytes matched).
- Enforce a **maximum password length** (e.g., 64–128 chars) to bound hashing cost (a DoS vector: a 10 MB "password" forces expensive hashing) and beware bcrypt's silent 72-byte truncation.
- Rate-limit and lock out after repeated failures, but prefer **exponential backoff + CAPTCHA** over hard lockout (hard lockout becomes a denial-of-service against legit users).

### 2.3 TOTP, HOTP, and how authenticator apps work

- **HOTP (HMAC-based One-Time Password, RFC 4226)** — `HOTP = Truncate(HMAC-SHA1(secret, counter))`. A shared secret and a counter that increments per use. **HMAC** is a keyed hash (Hash-based Message Authentication Code) proving both integrity and that the holder knows the secret.
- **TOTP (Time-based OTP, RFC 6238)** — HOTP where the counter = `floor(currentUnixTime / timeStep)`, typically `timeStep = 30s`. Both sides derive the same code from the shared secret + current time window, so no per-use sync needed.

How enrollment works: server generates a random secret, encodes it as a QR code (`otpauth://totp/...?secret=BASE32&issuer=...`), the app stores the secret; thereafter both compute the same 6-digit code every 30s. The server typically accepts a **±1 window** to tolerate clock skew (so a code is valid ~90s). Codes must be **single-use within their window** (track last-used counter) to prevent replay.

TOTP's weakness: it is **phishable**. A fake login page can ask for the code and relay it in real time. That is why **WebAuthn/passkeys** exist.

### 2.4 WebAuthn / FIDO2 / passkeys (phishing-resistant authentication)

**WebAuthn** is a W3C standard, part of **FIDO2**, for public-key authentication in browsers. Core idea: the credential is an **asymmetric key pair**; the **private key never leaves the authenticator** (the device or hardware key); the server stores only the **public key**.

- **Asymmetric cryptography** — a key pair where the private key signs and the public key verifies. You can publish the public key safely; only the private key holder can produce valid signatures.
- **Authenticator** — the thing holding the private key: a platform authenticator (phone's Secure Enclave / TPM, unlocked by biometrics) or a roaming authenticator (YubiKey via USB/NFC).
- **Relying Party (RP)** — your application/server.
- **Challenge** — a random nonce the server sends; the authenticator signs it so the response can't be replayed.
- **Passkey** — a WebAuthn credential that is **discoverable** and typically **synced** across a user's devices via their platform (iCloud Keychain, Google Password Manager). Passkeys replace passwords entirely.

Why it's phishing-resistant: the signature is **bound to the origin** (the actual domain). A phishing site on `evil.com` cannot get a signature valid for `bank.com`, because the browser includes the real origin in what gets signed. There is no shared secret to relay.

Flow (registration / "attestation"):

```
1. Server: generate challenge, send {challenge, rp, user, pubKeyCredParams}
2. Browser: navigator.credentials.create(...) → prompts authenticator
3. Authenticator: generate key pair, sign attestation over challenge+origin
4. Browser → Server: {credentialId, publicKey, attestation}
5. Server: verify attestation, store {credentialId, publicKey, signCount} for user
```

Flow (login / "assertion"):

```
1. Server: generate challenge, send {challenge, allowCredentials?}
2. Browser: navigator.credentials.get(...) → user verifies (biometric/PIN)
3. Authenticator: sign assertion over challenge+origin+authenticatorData
4. Browser → Server: {credentialId, signature, authenticatorData, clientDataJSON}
5. Server: look up publicKey by credentialId, verify signature, check signCount
           increased (clone detection), check origin & challenge
```

On the JVM, libraries like **Yubico's `java-webauthn-server`** handle the cryptographic verification. You provide credential storage and challenge management.

### 2.5 Certificates and mutual TLS (mTLS)

- **TLS (Transport Layer Security)** — encrypts a connection and authenticates the *server* to the *client* via the server's certificate. This is "normal" HTTPS.
- **X.509 certificate** — a signed document binding a public key to an identity (a domain, a service name), signed by a **Certificate Authority (CA)** the verifier trusts.
- **mTLS (mutual TLS)** — *both* sides present certificates, so the server also authenticates the *client*. This is the workhorse of **service-to-service authentication** inside a mesh: each service has an identity cert (often issued by an internal CA like **SPIFFE/SPIRE** or the mesh's CA), and connections are mutually verified.
- **SPIFFE** — a standard for service identity; a **SPIFFE ID** looks like `spiffe://trust-domain/ns/payments/sa/checkout`. **SPIRE** is its reference implementation that issues short-lived **SVIDs** (SPIFFE Verifiable Identity Documents, often X.509 certs).

mTLS authenticates the *workload*, not the *end user*. You usually carry the human's identity separately (a token) on top of the mTLS-secured channel.

### 2.6 Sessions vs tokens (the central architectural fork)

After authenticating, the system must **remember** the identity across subsequent requests (HTTP is stateless). Two dominant approaches:

**Session-based (stateful, server-side):**

- On login, the server creates a **session record** (in memory, Redis, or a DB) keyed by an opaque random **session ID**, and sends that ID to the client in a **cookie**.
- Each request includes the cookie; the server looks up the session to recover the identity.
- The session is **server-side state**; the cookie carries no data, just a reference.

**Token-based (often stateless, client-side):**

- On login, the server issues a **token** (commonly a **JWT**) that *contains* the identity claims, **signed** so it can't be forged.
- The client sends it on each request (usually `Authorization: Bearer <token>`).
- The server **verifies the signature** and reads the claims — no lookup needed (stateless), enabling horizontal scaling without shared session storage.

**JWT (JSON Web Token)** — three Base64url parts `header.payload.signature`:

- **Header** — algorithm + type, e.g. `{"alg":"RS256","typ":"JWT"}`.
- **Payload (claims)** — `sub` (subject/user id), `exp` (expiry), `iat` (issued-at), `iss` (issuer), `aud` (audience), plus custom claims (roles, tenant).
- **Signature** — over header+payload using either a shared secret (**HS256**, HMAC) or a private key (**RS256/ES256**, asymmetric). With asymmetric signing, services verify with the public key without holding the signing secret.

Decision summary (expanded with tradeoffs in §8):

| Dimension | Session (cookie) | Token (JWT) |
|---|---|---|
| State | Server-side | Client-side (self-contained) |
| Scale-out | Needs shared store | Stateless, easy |
| Revocation | Instant (delete session) | Hard (must wait for expiry or maintain denylist) |
| Best for | Browser apps, first-party | APIs, SPAs, mobile, service-to-service |
| Size on wire | Small (just an ID) | Larger (full claims each request) |
| XSS/CSRF profile | CSRF risk (mitigable) | XSS risk if stored in JS-accessible storage |

A common pragmatic pattern: **short-lived access token + long-lived refresh token**, where the refresh token is the revocable, server-tracked credential and the access token is the cheap, stateless one.

### 2.7 OAuth 2.0 and OpenID Connect (delegation and federation)

These are frequently confused with each other and with "auth" in general:

- **OAuth 2.0** is **authorization for delegation**: it lets a user grant a third-party app limited access to their resources *without sharing their password*. It produces **access tokens** scoped by **scopes**. OAuth is *not* an authentication protocol by itself.
- **OpenID Connect (OIDC)** is a thin **authentication** layer *on top of* OAuth 2.0. It adds the **ID token** (a JWT proving who the user is) and a standard `/userinfo` endpoint. When you "Sign in with Google," that's OIDC.
- **Roles:** *Resource Owner* (the user), *Client* (the app), *Authorization Server* (issues tokens, e.g., Okta/Keycloak/Auth0), *Resource Server* (your API that accepts tokens).
- **Grant types / flows:** **Authorization Code + PKCE** (the modern default for web/mobile/SPA), **Client Credentials** (service-to-service, no user), **Device Code** (TVs/CLI), and the **deprecated** Implicit and Resource Owner Password flows (avoid).
- **PKCE (Proof Key for Code Exchange)** — protects the authorization-code flow on public clients by requiring a per-request secret (`code_verifier`/`code_challenge`) so an intercepted code is useless.

The clean way to think about it: **OIDC = authentication**, **OAuth = delegated authorization**, your **own RBAC/ABAC = application authorization**. They compose; they don't replace each other.

---

## 3. How it works internally

This section traces the actual control and data flow, end to end, for the most common production setups.

### 3.1 Session-based login: step-by-step internal workflow

```
POST /login {username, password}
  1. App fetches stored hash for username (or a dummy hash if user absent —
     to keep timing constant and avoid username enumeration).
  2. encoder.matches(password, storedHash)  → constant-time slow compare.
  3. On success: generate 128+ bits of CSPRNG randomness as sessionId.
  4. Persist session: { sessionId -> {userId, createdAt, expiresAt, csrfToken,
        ipBinding?, userAgentBinding?} } in Redis/DB.
  5. Set-Cookie: SESSIONID=...; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=...
  6. Return 200 (no identity data in body needed).

Subsequent request GET /orders
  7. Browser auto-sends Cookie: SESSIONID=...
  8. Filter extracts sessionId, looks it up in the store.
  9. Miss/expired → 401 (force re-login). Hit → load Principal into context.
 10. Sliding expiry: optionally extend expiresAt; rotate id on privilege change.
 11. Downstream code reads SecurityContext for authZ.
```

Key internal details:

- **CSPRNG** = Cryptographically Secure Pseudo-Random Number Generator (`java.security.SecureRandom`). Session IDs must be unguessable; never use `Math.random()` or `java.util.Random` (predictable).
- **Session fixation defense:** always **regenerate the session ID after login** (and after privilege elevation). Otherwise an attacker who planted a known session ID before login can ride it afterward.
- **Cookie flags:** `HttpOnly` (JS can't read it → mitigates XSS token theft), `Secure` (HTTPS only), `SameSite` (`Lax`/`Strict` mitigates **CSRF** — Cross-Site Request Forgery, where another site triggers requests with your cookie).

### 3.2 JWT/token validation: step-by-step internal workflow

```
Request with Authorization: Bearer eyJ...
  1. Resource server splits token into header.payload.signature.
  2. Read header.alg and header.kid (key id).
  3. Resolve verification key:
       - HS256: shared secret from config/secret manager.
       - RS256/ES256: fetch public key from issuer's JWKS endpoint
         (/.well-known/jwks.json), cached by kid with TTL.
  4. CRITICAL: verify alg against an allowlist BEFORE trusting it
     (defends the "alg:none" and HS/RS confusion attacks — see §9).
  5. Recompute/verify signature over header.payload.
  6. Validate claims:
       - exp not passed (with small leeway, e.g. 30–60s clock skew).
       - nbf (not-before) satisfied, iat sane.
       - iss == expected issuer.
       - aud includes this resource server.
  7. Optional revocation check: lookup jti in a denylist (Redis) if you
     maintain one — this re-introduces statefulness on purpose.
  8. Build Principal from claims (sub, roles, tenant, scopes).
  9. Proceed to authorization.
```

- **JWKS (JSON Web Key Set)** — a JSON document of the issuer's public keys, each tagged with a `kid`. Caching it (with rotation handling) is how stateless verification scales: each service independently verifies without calling the auth server per request.
- **`kid` (key id)** — lets the issuer rotate signing keys; the verifier picks the matching public key.

### 3.3 OIDC Authorization Code + PKCE: full flow

```
1. App generates code_verifier (random), code_challenge = SHA256(verifier).
2. Redirect user to:
     /authorize?response_type=code&client_id=...&redirect_uri=...
       &scope=openid profile email&state=<csrf>&code_challenge=...&code_challenge_method=S256
3. User authenticates at the Authorization Server (AS) — could be password+MFA,
     passkey, SSO. The AS, not your app, handles credentials.
4. AS redirects back: redirect_uri?code=AUTH_CODE&state=<csrf>
5. App verifies state (CSRF), then POSTs to /token:
     grant_type=authorization_code&code=...&code_verifier=...&client_id=...
6. AS verifies SHA256(code_verifier)==stored code_challenge, returns:
     { access_token, id_token, refresh_token, expires_in }
7. App validates id_token (OIDC) → establishes user identity.
8. App uses access_token to call resource servers (OAuth).
9. On access_token expiry, use refresh_token at /token (grant_type=refresh_token).
```

Why each guard exists: `state` defeats CSRF on the redirect; PKCE defeats authorization-code interception on public clients; the `code` is one-time-use and short-lived (seconds) so even if leaked it's hard to exploit.

### 3.4 The authorization decision: PEP / PDP / PIP model

A clean way the industry models authZ internals:

- **PEP — Policy Enforcement Point.** Where the decision is *enforced* (the API handler, the gateway filter, the data-access layer). It asks for a decision and obeys it.
- **PDP — Policy Decision Point.** Where the decision is *computed* by evaluating policy against inputs (e.g., an OPA instance, a Spring `@PreAuthorize` evaluator, a Zanzibar `Check`).
- **PIP — Policy Information Point.** Where *additional attributes* are fetched to make the decision (user attributes from a directory, resource owner from DB, group membership from a graph).
- **PAP — Policy Administration Point.** Where policies are authored/managed.

Internal decision flow:

```
PEP intercepts (principal, action, resource, context)
   → gathers attributes via PIP(s)
   → calls PDP.evaluate(input)
   → PDP returns {allow|deny, optional obligations}
   → PEP enforces (proceed | 403); logs the decision for audit
```

**Obligations** are side conditions attached to a permit ("allow, but mask the SSN column," "allow, but log to the sensitive-access trail"). Mature authZ systems return not just allow/deny but obligations the PEP must honor.

### 3.5 Authorization model internals

#### RBAC (Role-Based Access Control)

- Core relations: **users → roles → permissions**. A permission is `(action, resource-type)`. You check: does any of the user's roles grant the requested permission?
- **Role hierarchy** (RBAC1): roles inherit (admin ⊇ editor ⊇ viewer).
- **Constraints** (RBAC2): **separation of duties** (you can't hold both "submitter" and "approver"), cardinality limits.
- Internally it's a join/closure over the user-role-permission graph. Cheap to evaluate, easy to reason about, but suffers **role explosion** when permissions depend on data (per-tenant, per-resource) — you end up with `editor_tenant_123`, which doesn't scale.

#### ABAC (Attribute-Based Access Control)

- Decisions are **boolean expressions over attributes** of subject, resource, action, and environment: `permit if subject.dept == resource.dept AND action == "read" AND env.time in workhours`.
- Extremely expressive; handles contextual rules RBAC can't ("only during business hours," "only from corporate network," "only if amount < user.limit").
- Cost: harder to audit ("who can access X?" requires evaluating policy over all subjects), and attribute freshness/availability becomes a dependency. **XACML** is the classic (verbose XML) ABAC standard; modern stacks use OPA/Rego or Cedar instead.

#### ReBAC (Relationship-Based Access Control) / Google Zanzibar

- Access derives from **relationships in a graph**: "user is `owner` of doc," "doc is in `folder` X," "user is `member` of `team` that has `editor` on folder X." A check is a **graph reachability** query.
- **Zanzibar** is Google's globally-distributed authZ system powering Drive/YouTube/Cloud. Open implementations: **SpiceDB (AuthZed)**, ** Keto (Ory)**, **OpenFGA (CNCF, from Auth0/Okta)**.
- Data model: **relation tuples** `⟨object#relation@subject⟩`, e.g. `doc:roadmap#viewer@user:alice`, `doc:roadmap#parent@folder:planning`. Schema defines **userset rewrites**: `viewer = direct-viewer ∪ editor ∪ (parent->viewer)` (inheritance from folder).
- A `Check(doc:roadmap#view@user:alice)` walks the tuple graph. Zanzibar's clever bits: **Zookies** (consistency tokens ensuring you don't see stale ACLs after a change — "new enemy problem"), **Leopard** index for deeply nested groups, and snapshot reads for consistency.
- **New enemy problem** — if ACL changes and content changes are not ordered consistently, a just-removed user might still read just-added content (or vice versa). Zanzibar solves this with zookies/snapshot tokens enforcing causal consistency. This is *the* hard problem ReBAC systems must solve, and why "just store ACLs in a table" is harder than it looks at scale.

#### ACLs (Access Control Lists)

- The oldest model: each resource carries a list of `(principal, permissions)`. Unix file permissions, S3 bucket ACLs. Simple per-object, but no inheritance/relationships and poor at "what can Alice access?" queries. ReBAC is essentially ACLs generalized into a graph.

### 3.6 State machine of a credential/session/token

```
            issue/login
   [absent] ───────────► [active] ──────────────► [expired]  (time)
                            │  ▲                     │
                refresh/    │  │  re-auth            │ cleanup
                sliding ext │  └────────────────────┘
                            │
                   revoke/logout/compromise
                            ▼
                       [revoked]  (terminal until reissue)
```

Token-specific nuance: a stateless JWT cannot truly be moved to `[revoked]` without external state (denylist) — it remains cryptographically valid until `exp`. This is the fundamental revocation tension.

---

## 4. The complete toolkit

### 4.1 Spring Security core types (Java)

| Type / API | Purpose | Key params / methods | Default / notes |
|---|---|---|---|
| `SecurityFilterChain` | Declarative HTTP security config | `authorizeHttpRequests`, `oauth2ResourceServer`, `formLogin`, `csrf`, `sessionManagement` | Replaces old `WebSecurityConfigurerAdapter` (removed in 6.x) |
| `AuthenticationManager` / `ProviderManager` | Drives authentication | `authenticate(Authentication)` | Delegates to `AuthenticationProvider`s |
| `AuthenticationProvider` | Verifies a credential type | `authenticate`, `supports` | e.g. `DaoAuthenticationProvider` |
| `UserDetailsService` | Loads user + hash + authorities | `loadUserByUsername` | Throws `UsernameNotFoundException` |
| `PasswordEncoder` | Hash/verify passwords | `encode`, `matches`, `upgradeEncoding` | Use `DelegatingPasswordEncoder` |
| `SecurityContextHolder` | Holds current `Authentication` | `getContext().getAuthentication()` | `ThreadLocal` strategy by default (watch async!) |
| `GrantedAuthority` / roles | Authorities for authZ | `hasRole`, `hasAuthority` | `ROLE_` prefix convention for `hasRole` |
| `@PreAuthorize` / `@PostAuthorize` | Method-level authZ (SpEL) | `@PreAuthorize("hasRole('ADMIN') and #id == authentication.name")` | Needs `@EnableMethodSecurity` |
| `@PostFilter` / `@PreFilter` | Filter collections by policy | SpEL over `filterObject` | Beware N-row cost |
| `JwtDecoder` / `oauth2ResourceServer().jwt()` | Validate JWTs | issuer-uri, jwk-set-uri | Auto-validates exp, iss, signature |
| `OAuth2AuthorizedClientManager` | OAuth client-side token mgmt | refresh, client-credentials | For calling downstream APIs |

Minimal Spring Security 6 config skeleton:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity                 // enables @PreAuthorize etc.
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    http
      .csrf(csrf -> csrf.disable())   // OK for stateless token APIs; KEEP for cookie/session apps
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth
          .requestMatchers("/public/**").permitAll()
          .requestMatchers("/admin/**").hasRole("ADMIN")
          .anyRequest().authenticated())
      .oauth2ResourceServer(o -> o.jwt(jwt -> jwt
          .jwkSetUri("https://issuer.example.com/.well-known/jwks.json")));
    return http.build();
  }

  @Bean PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
```

### 4.2 JWT libraries (JVM)

| Library | Purpose | Notes |
|---|---|---|
| **Nimbus JOSE+JWT** | Low-level JWS/JWE/JWT | Used under the hood by Spring; most complete |
| **jjwt (io.jsonwebtoken)** | Ergonomic JWT build/parse | Easy API; pin parser to expected `alg` |
| **Auth0 java-jwt** | Build/verify JWTs | Simple `JWT.require(Algorithm.RSA256(...))` |

jjwt verification (note the explicit algorithm/key pinning):

```java
Jws<Claims> jws = Jwts.parser()
    .verifyWith(rsaPublicKey)          // pins to RSA public key -> blocks alg confusion
    .requireIssuer("https://issuer.example.com")
    .requireAudience("orders-api")
    .clockSkewSeconds(30)
    .build()
    .parseSignedClaims(token);         // throws if signature/exp/claims invalid

String userId = jws.getPayload().getSubject();
```

### 4.3 Policy engines & authZ services

| Tool | Model | Language/API | Where it runs |
|---|---|---|---|
| **OPA (Open Policy Agent)** | General policy (often ABAC) | **Rego** | Sidecar/library; `POST /v1/data/...` |
| **OPAL** | OPA + live data sync | — | Pushes data/policy updates to OPA |
| **Cedar (AWS)** | RBAC+ABAC hybrid | Cedar lang | Library (Rust/Java); used by Verified Permissions |
| **OpenFGA** | ReBAC (Zanzibar) | DSL + tuples | Service; `Check`, `ListObjects` |
| **SpiceDB** | ReBAC (Zanzibar) | Schema + tuples | Service; gRPC `CheckPermission` |
| **Ory Keto** | ReBAC (Zanzibar) | namespaces | Service |
| **Casbin** | RBAC/ABAC/ACL | model.conf + policy | Library (incl. jCasbin for JVM) |
| **Keycloak Authorization Services** | RBAC/ABAC/UMA | UI + policies | Part of Keycloak IdP |

OPA/Rego policy example (ABAC + RBAC mix):

```rego
package authz

default allow := false

# RBAC: admins can do anything
allow if input.user.roles[_] == "admin"

# ABAC: a user can read a document in their own department during work hours
allow if {
  input.action == "read"
  input.resource.type == "document"
  input.user.department == input.resource.department
  input.context.hour >= 9
  input.context.hour < 18
}

# Ownership: owners can update their own resources
allow if {
  input.action == "update"
  input.resource.owner == input.user.id
}
```

OpenFGA schema (ReBAC) example:

```
model
  schema 1.1
type user
type folder
  relations
    define owner: [user]
    define viewer: [user] or owner
type document
  relations
    define parent: [folder]
    define owner: [user]
    define editor: [user] or owner
    define viewer: [user] or editor or viewer from parent
```

A `Check(user:alice, viewer, document:roadmap)` walks: direct viewer? editor? owner? or viewer of its parent folder? — exactly the Zanzibar graph traversal.

### 4.4 Identity providers / auth servers

| Product | Type | Notes |
|---|---|---|
| **Keycloak** | Open-source IdP | OIDC/SAML, RBAC, self-host |
| **Auth0 / Okta** | SaaS IdP | OIDC, MFA, passkeys |
| **AWS Cognito** | SaaS IdP | OIDC, integrates with IAM |
| **Ory (Kratos/Hydra)** | Open-source, API-first | Kratos=identities, Hydra=OAuth server |
| **Dex** | OIDC connector | Federation front-end |

### 4.5 Service-identity & mesh tooling

| Tool | Purpose |
|---|---|
| **SPIFFE/SPIRE** | Workload identity (SVIDs) |
| **Istio / Linkerd** | Service mesh; auto-mTLS, authZ policies |
| **Envoy + ext_authz** | Delegate authZ to OPA/custom service per request |
| **HashiCorp Vault** | Secrets, dynamic creds, PKI/CA |
| **cert-manager** | Cert issuance/rotation in Kubernetes |

---

## 5. Code examples by use case

### 5.1 Use case: classic browser app with server-side sessions + CSRF

```java
@Bean
SecurityFilterChain web(HttpSecurity http) throws Exception {
  http
    .authorizeHttpRequests(a -> a
        .requestMatchers("/", "/login", "/css/**").permitAll()
        .anyRequest().authenticated())
    .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/dashboard"))
    .logout(l -> l.logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
    // KEEP CSRF for cookie-based auth; token sent via cookie+header double-submit
    .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
    .sessionManagement(s -> s
        .sessionFixation(sf -> sf.changeSessionId())   // defeat fixation
        .maximumSessions(3));                            // cap concurrent sessions
  return http.build();
}
```

Why: cookie-based auth is vulnerable to CSRF, so CSRF protection stays *on*; `SameSite=Lax` cookies plus the synchronizer/double-submit token close it.

### 5.2 Use case: stateless REST API verifying OIDC JWTs + method-level RBAC

```java
@RestController
@RequestMapping("/api/orders")
class OrderController {

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('SCOPE_orders.read')")     // OAuth scope as authority
  public OrderDto get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
    String userId = jwt.getSubject();
    return orderService.getForUser(id, userId);          // still enforce ownership in service
  }

  @PostMapping
  @PreAuthorize("hasRole('OPERATOR')")
  public OrderDto create(@RequestBody CreateOrder req) { return orderService.create(req); }
}
```

Note the layered defense: the annotation gates coarse access (role/scope); the service still checks **ownership** (instance-level authZ). Coarse checks alone create **IDOR** vulnerabilities (Insecure Direct Object Reference — changing `/orders/5` to `/orders/6` to read someone else's order).

### 5.3 Use case: instance-level (object) authorization done correctly

```java
@Service
class OrderService {
  Order getForUser(String orderId, String userId) {
    Order o = repo.findById(orderId).orElseThrow(() -> new NotFoundException());
    // Object-level check: prevents IDOR. Return 404 (not 403) to avoid leaking existence.
    if (!o.getOwnerId().equals(userId) && !hasRole("ADMIN")) {
      throw new NotFoundException();   // deliberately not ForbiddenException
    }
    return o;
  }
}
```

### 5.4 Use case: delegating authorization to OPA from a Java service

```java
// Build the decision input and ask OPA's PDP. PEP = this method.
record AuthzInput(User user, String action, Resource resource, Context context) {}

boolean isAllowed(User u, String action, Resource r) {
  var input = Map.of("input", new AuthzInput(u, action, r, Context.now()));
  var body = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input));
  var req = HttpRequest.newBuilder(URI.create("http://localhost:8181/v1/data/authz/allow"))
      .header("Content-Type", "application/json").POST(body).build();
  var resp = http.send(req, BodyHandlers.ofString());
  return json.readTree(resp.body()).path("result").asBoolean(false); // fail-closed default
}
```

OPA usually runs as a **sidecar** (same pod, localhost) for low latency; policy + data are kept fresh via bundles/OPAL. The `false` default is the **fail-closed** principle: if anything is ambiguous, deny.

### 5.5 Use case: ReBAC check with OpenFGA (sharing/collaboration semantics)

```java
var fga = OpenFgaClient.builder().storeId(STORE).apiUrl("http://openfga:8080").build();

// Can Alice view this document (directly, via editor, via owner, or via folder)?
var resp = fga.check(new ClientCheckRequest()
    .user("user:alice").relation("viewer").object("document:roadmap")).get();

if (!Boolean.TRUE.equals(resp.getAllowed())) throw new ForbiddenException();

// "List everything Alice can view" — efficient reverse query the DB can't easily do:
var docs = fga.listObjects(new ClientListObjectsRequest()
    .user("user:alice").relation("viewer").type("document")).get().getObjects();
```

ReBAC shines exactly here: "documents shared with me, including via folders/teams" is a graph query that's painful in SQL but native to Zanzibar systems.

### 5.6 Use case: service-to-service auth with mTLS + propagated user identity

```java
// Service A calls Service B. mTLS authenticates the *workload* (SPIFFE SVID).
// The end-user identity is propagated as a signed JWT in a header.
HttpRequest req = HttpRequest.newBuilder(URI.create("https://payments/charge"))
    .header("Authorization", "Bearer " + downstreamAccessToken) // user/service token
    .header("X-Request-Id", traceId)
    .POST(body).build();
// The TLS layer (mesh/SPIRE-provided keystore) handles client-cert auth transparently.
```

The principle: **mTLS = "which service is calling"**, **token = "on whose behalf."** Service B authorizes using both: is the caller `checkout` allowed to call `charge`, and is the user allowed to charge this account?

### 5.7 Use case: TOTP enrollment + verification (the `have` factor)

```java
// Using a TOTP lib (e.g., dev.samstevens.totp). Enrollment:
String secret = new DefaultSecretGenerator().generate();   // base32 secret
String uri = new QrData.Builder().label(user.email()).secret(secret)
    .issuer("MyApp").algorithm(HashingAlgorithm.SHA1).digits(6).period(30).build()
    .getUri();                                              // render as QR for the app
userRepo.saveTotpSecret(user.id(), encrypt(secret));        // store secret ENCRYPTED at rest

// Verification at login (after password passes):
CodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
boolean ok = verifier.isValidCode(decrypt(storedSecret), submittedCode); // tolerates ±1 window
```

Store the TOTP secret **encrypted** (it's a bearer secret); also generate one-time **recovery codes** (hashed) for device-loss recovery.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Stateless JWT verification** is cheap (one signature check) but **JWKS fetch** must be cached (by `kid`, with TTL + rotation handling); cache miss → network call on the request path.
- **Session lookups** hit a store every request; co-locate Redis, use connection pooling, and consider local short-TTL caches.
- **Asymmetric vs symmetric signing:** RS256 verification is ~10–100x slower than HS256 but enables key separation; ES256 is faster than RS256 with smaller keys. Measure; for high-RPS internal services HS256 with a well-guarded secret is sometimes chosen.
- **AuthZ on hot paths:** an out-of-process PDP call per request adds latency. Mitigations: run OPA as a **sidecar** (localhost, sub-ms), batch checks, cache decisions with short TTL (carefully — stale authZ is a security bug), and use **bundle**/push models so policy data is local.
- **ReBAC checks** can fan out across a graph; production systems cache aggressively and use consistency tokens to balance correctness vs latency.
- **Password hashing is intentionally slow** — that's a per-login cost (tens of ms) and a DoS surface; cap password length, rate-limit login, and consider a frontend proof-of-work for abuse.

### 6.2 Correctness & concurrency

- **`SecurityContextHolder` uses a `ThreadLocal`** — it does **not** propagate across thread boundaries automatically. In `@Async`, reactive (`Reactor`/WebFlux), parallel streams, or executor pools you must propagate it (`DelegatingSecurityContextExecutor`, `MODE_INHERITABLETHREADLOCAL`, or in WebFlux use `ReactiveSecurityContextHolder`). Forgetting this causes "user is null" or — worse — **identity bleed** where a pooled thread carries the previous request's principal.
- **Race conditions in refresh-token rotation:** two concurrent requests refreshing the same token can both succeed or both fail. Use **refresh token rotation with reuse detection** (a used refresh token, if seen again, triggers revocation of the whole family — a strong sign of theft).
- **Clock skew** in TOTP/JWT exp/nbf: allow a small leeway (30–60s) but never large.

### 6.3 Security hardening (the high-value list)

- **Always fail closed.** Default decision = deny. Any error in the authZ path → deny, not allow.
- **Validate `alg` against an allowlist** before signature verification (defeats `alg:none` and RS/HS confusion).
- **Bind tokens to audience (`aud`) and issuer (`iss`)** so a token for service X can't be replayed at service Y.
- **Short access-token lifetimes** (5–15 min) + revocable refresh tokens; maintain a `jti` denylist for emergency revocation.
- **Store tokens safely in browsers:** prefer **HttpOnly, Secure, SameSite cookies** over `localStorage` (immune to XSS exfiltration). If you must use bearer tokens in JS, accept the XSS exposure and harden CSP.
- **Rotate signing keys** and support overlapping `kid`s during rotation.
- **Constant-time comparisons** for all secret/token comparisons.
- **Prevent user enumeration:** identical responses/timing for "no such user" and "wrong password"; identical messaging for forgot-password.
- **Object-level authZ everywhere** (no IDOR). Coarse role checks are not enough.
- **Re-authenticate for sensitive actions** ("sudo mode": re-enter password/passkey before changing email, deleting account, viewing secrets).
- **Encrypt MFA secrets at rest; hash recovery codes.**
- **Audit every authZ decision** with enough context to answer "who accessed what, was it allowed, why."

### 6.4 Observability

- Log **authN events** (login success/failure, MFA, logout, token issue/refresh) and **authZ decisions** (allow/deny with principal, action, resource, policy id). Never log raw credentials, full tokens, or password hashes.
- Metrics: login failure rate, 401/403 rates, token-refresh rate, PDP latency (p50/p99), JWKS cache hit rate, lockout counts.
- Alert on spikes: brute-force (login-failure surge), credential stuffing (many users, few failures each), authZ-deny spikes (possible probing or a broken deploy), token-validation-error spikes (possible key rotation gone wrong).
- Make decisions **traceable**: attach a decision id / policy version so an auditor can reconstruct *why* access was (dis)allowed.

### 6.5 Cost

- Self-hosting an IdP (Keycloak) trades ops effort for per-MAU SaaS fees (Auth0/Okta bill per monthly active user — material at scale).
- Stateless JWTs reduce session-store infrastructure but increase per-request bytes and complicate revocation (which may need a denylist store anyway — partly negating the savings).
- ReBAC services (SpiceDB/OpenFGA) are additional stateful infrastructure to run, scale, and back up.

### 6.6 Testing

- Unit-test policies in isolation: OPA has `opa test` with `_test.rego` files; Cedar/OpenFGA ship test harnesses; write table-driven tests over (principal, action, resource) → expected.
- Spring Security: `@WithMockUser`, `@WithMockJwt`, `MockMvc` with `.with(jwt())` / `.with(user())` to assert 401/403/200 paths.
- **Negative tests are mandatory:** assert that the *wrong* user gets 403/404, that expired tokens fail, that `alg:none` is rejected, that tenant A can't read tenant B.
- Add **authZ regression tests** for every endpoint; broken object-level authZ is the #1 OWASP API risk.

### 6.7 Anti-patterns (avoid)

- **Authentication ≠ authorization confusion:** "they're logged in" is not "they're allowed." Always do both.
- **Authorizing only at the gateway** and trusting all internal traffic ("crunchy shell, soft center"). Internal services must still authZ — see zero trust.
- **Putting authZ logic in the frontend** (hiding a button ≠ securing the endpoint).
- **Long-lived JWTs with no revocation strategy.**
- **Storing secrets/JWTs in `localStorage`** without understanding the XSS risk.
- **Role explosion** from encoding data into roles (`editor_tenant_42`) — move to ABAC/ReBAC.
- **Rolling your own crypto/JWT parsing** — use vetted libraries; pin algorithms.
- **Trusting client-supplied identity** (e.g., a `X-User-Id` header set by the client) — only trust identity established by your own verified auth.
- **Same key for signing and other purposes**, or sharing the JWT signing secret with services that only need to verify (give them the public key instead).

---

## 7. Advanced topics & deep internals

### 7.1 Token revocation strategies (the hard part of stateless auth)

Because a signed JWT is valid until `exp`, real revocation needs one of:

1. **Short TTL + refresh tokens** — bound the damage window; revoke at the refresh boundary by revoking the refresh token (which *is* server-tracked).
2. **Denylist (blocklist) by `jti`** — store revoked token ids in Redis with TTL = remaining lifetime. Reintroduces a lookup but only for the (small) revoked set.
3. **Allowlist / session-bound tokens** — track active token ids; effectively stateful, gives instant revocation at the cost of statelessness.
4. **Token versioning** — embed a `tokenVersion` claim; bump the user's version on logout-all/compromise; verify claim == current version (one fast lookup or cached).
5. **Backchannel logout (OIDC)** — the AS notifies relying parties of logout/session end.

### 7.2 Refresh-token rotation with reuse detection

Each refresh issues a new refresh token and invalidates the old; if an **old (already-used) refresh token** is presented, that implies theft (the legitimate client already rotated past it), so you **revoke the entire token family**. This converts token theft from silent persistence into a detectable, self-healing event.

### 7.3 Sender-constrained / proof-of-possession tokens

Bearer tokens are "whoever holds it, wins." **DPoP (Demonstrating Proof-of-Possession, RFC 9449)** and **mTLS-bound tokens (RFC 8705)** bind a token to a key the client must prove it holds on each request, so a stolen token is useless without the private key. Adopt these for high-value APIs.

### 7.4 Where to enforce authZ in microservices

| Layer | Enforces | Pros | Cons |
|---|---|---|---|
| **API Gateway / edge** | Coarse: authN, coarse RBAC, rate limits | One choke point; offloads services | Can't do data/instance-level; risky as sole control |
| **Service mesh (Istio AuthorizationPolicy)** | Service-to-service allow/deny, mTLS | Uniform, infra-level | Coarse; not business-logic aware |
| **Per-service (PEP in app)** | Business + instance-level authZ | Knows the data, can do ownership/ABAC | Duplicated logic risk → centralize policy |
| **Data layer (row-level security)** | Tenant/row isolation | Defense-in-depth; DB enforces | Harder to express complex rules |

The mature pattern: **authenticate at the edge**, **propagate verified identity inward** (signed token, not a trust-me header), and **authorize at each service** using a **centralized policy** (OPA/Zanzibar) but **decentralized enforcement** (PEP in each service). This is **decentralized enforcement + centralized policy authoring** — you get consistency without a single runtime bottleneck.

### 7.5 Centralized vs decentralized authorization

- **Centralized PDP** (one OPA/SpiceDB cluster everyone calls): single source of truth, consistent, but a latency/availability dependency and potential bottleneck.
- **Decentralized/embedded** (OPA as a library or sidecar per service, policy bundles pushed out): low latency, no single point of failure, but you must distribute policy + data freshly (OPAL, bundles). Most large systems land on **centralized authoring/distribution + decentralized evaluation**.

### 7.6 Multi-tenancy isolation

The dominant authZ failure in SaaS is **cross-tenant leakage**. Defenses, layered:

- Carry `tenant_id` in the verified token (never from a client header).
- Scope every query by tenant at the **data layer** (and ideally enforce with **Postgres Row-Level Security** policies so a missing `WHERE tenant_id=` can't leak).
- Add tenant to the authZ input so policy can assert `subject.tenant == resource.tenant`.
- Test with an explicit "tenant A reads tenant B → must 404" suite.

### 7.7 Zero Trust

"**Never trust, always verify**" — no implicit trust from network location. Every request, even internal, is authenticated (mTLS) and authorized (policy), assuming the network is hostile. Replaces the old "firewall perimeter = safe inside" model. SPIFFE/SPIRE + mesh authZ + propagated user tokens are the building blocks.

### 7.8 Lesser-known behaviors / gotchas

- **bcrypt 72-byte truncation:** input beyond 72 bytes is silently ignored — long passwords or pre-hashing surprises.
- **JWT `alg` confusion (RS↔HS):** if a verifier accepts both and uses the (public) RSA key as an HMAC secret, an attacker can forge tokens. Pin the algorithm.
- **`alg:none`:** the spec allows unsigned JWTs; a naive parser may accept them. Reject explicitly.
- **Clock skew across nodes** breaks `exp`/TOTP — run NTP; allow small leeway.
- **`SameSite=Lax` doesn't stop all CSRF** (e.g., top-level GET navigations); keep CSRF tokens for state-changing requests when using cookies.
- **`@PostFilter` evaluates per element** — O(n) policy calls on a list; can be a silent performance cliff and a data-leak risk if it filters *after* fetching sensitive rows into memory.
- **OPA partial evaluation** can compile policies into SQL/filters for "list what I can see" — powerful but advanced.
- **Zanzibar consistency:** without zookies you can hit the new-enemy problem; understand your authZ system's consistency model.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Session vs token: use when / avoid when

**Use sessions when:** first-party browser app, you want instant revocation, you control all clients, single domain. **Avoid when:** many services need to verify identity without a shared store, mobile/3rd-party clients, you need horizontal statelessness.

**Use JWTs when:** APIs/SPAs/mobile, microservices that must verify independently, federation across domains, short-lived service tokens. **Avoid when:** you require instant revocation with no extra infra, tokens would carry large/sensitive payloads, or you can't manage key rotation.

### 8.2 AuthZ model selection

| Model | Best for | Strengths | Weaknesses |
|---|---|---|---|
| **ACL** | Simple per-object perms | Trivial to grasp | No inheritance; poor "what can X access?" |
| **RBAC** | Org with stable roles | Simple, auditable, fast | Role explosion when data-scoped |
| **ABAC** | Context/attribute rules | Very expressive, dynamic | Hard to audit; attribute dependencies |
| **ReBAC** | Sharing, hierarchies, social graphs | Natural relationships, scalable Check & ListObjects | Operational complexity; consistency |

Decision rules:

- Few static roles, no per-object nuance → **RBAC**.
- Rules depend on context (time, location, amount, attributes) → **ABAC** (OPA/Cedar).
- Access flows through relationships (owner, shared-with, folder/team inheritance) → **ReBAC** (Zanzibar/OpenFGA/SpiceDB).
- Most real systems are **hybrid**: RBAC for coarse roles + ABAC for context + ReBAC/ownership for instance-level. Cedar and OPA both support RBAC+ABAC; layer ReBAC where relationships dominate.

### 8.3 Where to authorize

- Coarse authN + rate limit + DDoS → **gateway**.
- Service-to-service allow/deny + mTLS → **mesh**.
- Business + instance-level + tenant → **service (PEP)**, against **centralized policy**.
- Last-line tenant/row isolation → **data layer RLS**.
- Defense in depth = enforce at multiple layers; never rely on one.

### 8.4 AuthN method selection

| Method | Phishing-resistant | UX | Use when |
|---|---|---|---|
| Password only | No | Familiar | Never alone for anything sensitive |
| Password + TOTP | No (relayable) | Moderate | Baseline MFA |
| Password + push | Partially (fatigue attacks) | Easy | Consumer apps (with number-matching) |
| WebAuthn / passkeys | **Yes** | Excellent | Modern default; aim here |
| mTLS / certs | Yes (for workloads) | N/A (infra) | Service-to-service |
| Magic link | No | Easy | Low-risk, low-friction |

---

## 9. Failure modes & debugging

### 9.1 Common production failures and how to diagnose

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| All requests suddenly 401 | Signing-key rotation; verifier has stale JWKS | Check JWKS cache TTL/kid; `curl /.well-known/jwks.json`; compare `kid` in token header (decode at jwt.io / `jwt decode`) |
| Intermittent 401 across nodes | Clock skew; one node's time off | `timedatectl`/NTP status; compare `exp`/`iat` vs node time |
| Some users get 403 after deploy | Policy/role mapping change; renamed authority; `ROLE_` prefix mismatch | Diff policy version; log the failing authority and required one |
| "User is null" in async/reactive code | `SecurityContext` not propagated across threads | Check executor wrapping; `ReactiveSecurityContextHolder` in WebFlux |
| Cross-tenant data leak | Missing `tenant_id` filter / authZ input | Audit queries; enable Postgres RLS; add tenant negative tests |
| Token accepted that shouldn't be | `alg:none` / alg confusion / missing `aud`/`iss` checks | Inspect verifier config; attempt forged token in a test |
| Login works, sensitive op fails silently | Missing object-level authZ vs working role check | Trace the PEP at the service layer |
| Brute-force/credential stuffing | No rate limit / lockout | Login-failure metrics; WAF logs; per-IP/per-user counters |
| Session won't die after logout | Logout only clears cookie, not server session; or stateless token still valid | Verify session invalidation / denylist |

### 9.2 Practical commands & tools

```bash
# Decode (NOT verify) a JWT to inspect claims:
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# Or with the jwt-cli:  jwt decode "$TOKEN"

# Check an issuer's keys and config:
curl -s https://issuer.example.com/.well-known/openid-configuration | jq .
curl -s https://issuer.example.com/.well-known/jwks.json | jq '.keys[].kid'

# Test mTLS / server cert:
openssl s_client -connect payments:443 -cert client.crt -key client.key

# Test an OPA policy locally:
opa eval -d policy.rego -i input.json "data.authz.allow"
opa test .            # run *_test.rego

# OpenFGA check from CLI:
fga query check user:alice viewer document:roadmap

# Probe authZ boundaries (negative test):
curl -i -H "Authorization: Bearer $OTHER_USERS_TOKEN" https://api/orders/123   # expect 403/404
```

### 9.3 Real-world incident classes (patterns, not vendor blame)

- **IDOR breaches:** APIs returning other users' records because only authN (not object-level authZ) was checked — repeatedly the top OWASP API risk (**Broken Object Level Authorization, BOLA**).
- **JWT `alg` confusion / `alg:none`:** several libraries historically accepted unsigned or HS-forged tokens, allowing full account takeover. Fix: pin algorithms.
- **Credential stuffing:** reused passwords from prior breaches replayed at scale; mitigated by MFA, breached-password checks (e.g., HaveIBeenPwned k-anonymity API), and rate limiting.
- **MFA fatigue / push bombing:** spamming approval prompts until a user taps "approve"; mitigated by number-matching and rate-limiting prompts.
- **Session fixation / no rotation:** attacker-set session id survives login. Fix: rotate id on auth and privilege change.
- **Over-broad OAuth scopes / consent phishing:** users grant a malicious app broad scopes; mitigated by least-privilege scopes and reviewing app registrations.
- **Secret leakage in tokens/logs:** JWTs or hashes logged in plaintext; mitigated by log scrubbing and never logging credentials.

---

## 10. Interview drill

**Q1. What's the precise difference between authentication and authorization, and what HTTP status maps to each?**
*Model answer:* AuthN verifies identity ("who are you, prove it"); AuthZ decides permissions ("what may you do"). 401 = unauthenticated (despite its name); 403 = authenticated but not permitted. AuthN runs first and produces a principal; AuthZ consumes the principal plus action/resource/context.
- *Follow-up: When would you return 404 instead of 403?* To avoid leaking that a resource exists to someone not allowed to know — common for object-level checks. Tradeoff: harder client debugging.
- *Follow-up: Can you authorize without authenticating?* Only via an explicitly anonymous policy (public resources); otherwise no — you'd be authorizing an unknown principal.
- *Follow-up: Where do you enforce each in microservices?* AuthN at the edge (verify token/mTLS); AuthZ at each service (instance-level) against centralized policy.

**Q2. Sessions vs JWTs — when each, and what's the JWT revocation problem?**
*Model answer:* Sessions = server-side state, instant revocation, ideal for first-party browser apps. JWTs = self-contained, stateless, scale across services, ideal for APIs/SPAs/mobile/service-to-service. The problem: a signed JWT stays valid until `exp`; true revocation needs short TTL + refresh tokens, a `jti` denylist, token versioning, or allowlisting — all of which reintroduce some state.
- *Follow-up: How do you do logout-everywhere with JWTs?* Bump a `tokenVersion`/key and reject older, or denylist active `jti`s, or use backchannel logout.
- *Follow-up: Where do you store the JWT in a browser?* HttpOnly+Secure+SameSite cookie (XSS-safe) vs `localStorage` (XSS-exposed but CSRF-free). Cookies need CSRF defense.
- *Follow-up: Refresh-token rotation with reuse detection?* New refresh each use; replay of an old one signals theft → revoke the whole family.

**Q3. Walk me through OIDC Authorization Code + PKCE and why each guard exists.**
*Model answer:* (See §3.3.) `state` prevents CSRF on the redirect; PKCE (`code_verifier`/`code_challenge`) stops authorization-code interception on public clients; the code is one-time and short-lived; ID token (OIDC) gives identity, access token (OAuth) gives delegated API access.
- *Follow-up: Difference between OAuth and OIDC?* OAuth = delegated authorization (access tokens, scopes); OIDC = authentication layer on top (ID token).
- *Follow-up: Why is the Implicit flow deprecated?* Tokens in the URL fragment leak via history/referrer; Auth Code + PKCE is safer even for SPAs.

**Q4. Compare RBAC, ABAC, and ReBAC. When do you pick each?**
*Model answer:* RBAC: users→roles→permissions; simple/auditable but role explosion when data-scoped. ABAC: boolean rules over attributes/context; expressive but hard to audit. ReBAC (Zanzibar): access via relationship graph; natural for sharing/hierarchies and answers "what can X access?" efficiently, at operational cost. Pick by the shape of your access rules; most systems hybridize.
- *Follow-up: What's role explosion and how does ABAC/ReBAC fix it?* Encoding data into roles (`editor_tenant_42`) multiplies roles; attributes/relationships externalize that data out of the role definition.
- *Follow-up: What is the new-enemy problem in Zanzibar?* Inconsistent ordering of ACL vs content changes can grant/deny incorrectly; solved with zookies/snapshot consistency.

**Q5. How do you securely store and verify passwords?**
*Model answer:* Salted, slow, memory-hard hash (Argon2id preferred; bcrypt/scrypt/PBKDF2 acceptable), per-user salt, optional pepper kept separate, tuned work factor, constant-time compare, max length cap, upgrade-on-login when params change. Never plaintext or reversible encryption.
- *Follow-up: Salt vs pepper?* Salt is per-user, stored with the hash, defeats rainbow tables; pepper is a global secret stored separately so a DB leak alone is insufficient.
- *Follow-up: Why is a fast hash like SHA-256 wrong here?* Speed helps brute force; you want deliberately slow/memory-hard functions.

**Q6. What is mTLS and how does service identity differ from user identity?**
*Model answer:* mTLS authenticates both peers via X.509 certs; in a mesh it proves *which workload* is calling (often SPIFFE SVIDs). User identity is carried separately (a token) — "which service" vs "on whose behalf." Services authorize using both.
- *Follow-up: How are workload certs issued/rotated?* By an internal CA (SPIRE/Vault/mesh CA), short-lived, auto-rotated; cert-manager in k8s.
- *Follow-up: Why not just trust an `X-User-Id` header internally?* Clients/compromised services can forge it; only trust identity you cryptographically verified (signed token).

**Q7 (senior-signal). You're designing authZ for a multi-tenant SaaS with document sharing, teams, and folder inheritance, at scale. What do you build and why?**
*Model answer:* Hybrid: RBAC for coarse app roles, tenant scoping enforced at token + data layer (Postgres RLS as backstop), and **ReBAC** (OpenFGA/SpiceDB) for sharing/team/folder inheritance because those are relationship graphs RBAC can't model without explosion. Centralized policy authoring/distribution, decentralized evaluation (sidecar/embedded checks) for latency and availability. Consistency: use the authZ system's consistency tokens to avoid the new-enemy problem; cache checks with short TTL. Defense in depth: gateway authN, service-level instance authZ, RLS isolation, and explicit cross-tenant negative tests.
- *Follow-up: Centralized vs decentralized PDP tradeoff?* Centralized = consistent but a latency/availability dependency; decentralized = fast/resilient but needs fresh policy+data distribution. Most pick centralized authoring + decentralized evaluation.
- *Follow-up: How do you answer "list all docs Alice can see"?* ReBAC `ListObjects` (reverse index) — painful in SQL, native in Zanzibar systems; or OPA partial evaluation compiling to a filter.

**Q8 (senior-signal). Where should authorization live in a microservice architecture, and what's wrong with "authorize only at the gateway"?**
*Model answer:* Authenticate at the edge, propagate verified identity inward, authorize at each service (instance/business-level) against centralized policy, with mesh-level service-to-service authZ and data-layer isolation as additional layers. Gateway-only authZ is "crunchy shell, soft center": it can't do data/instance-level decisions, and any internal foothold bypasses all controls. Zero trust says verify every hop.
- *Follow-up: How do you avoid duplicating authZ logic per service?* Centralize policy (OPA/Cedar/Zanzibar), keep enforcement local (PEP); share policy via bundles/OPAL.
- *Follow-up: What's the cost of per-request PDP calls and how do you mitigate?* Latency/availability; mitigate with sidecars, batching, short-TTL caching, and pushed policy data.

**Q9 (senior-signal). Your team wants stateless JWTs everywhere "for scale," but security demands instant revocation. Reconcile this.**
*Model answer:* Pure stateless JWT can't do instant revocation. Reconcile with: short-lived access tokens (5–15 min) + revocable refresh tokens (the real control point); a `jti` denylist in Redis sized to the small revoked set; token-versioning for logout-all; key rotation for mass invalidation. You accept a bounded exposure window in exchange for statelessness on the hot path; the denylist reintroduces only minimal state. If the business truly needs zero-window revocation, sessions or allowlisted tokens are the honest choice — name the tradeoff explicitly.
- *Follow-up: How big does the denylist get?* Bounded by revocations within max token TTL; entries auto-expire at `exp`.
- *Follow-up: How do sender-constrained tokens help?* DPoP/mTLS-binding make a stolen token unusable without the key, reducing the need for revocation in the first place.

**Q10. Explain why WebAuthn/passkeys are phishing-resistant when TOTP isn't.**
*Model answer:* TOTP is a shared secret producing a code the user can be tricked into entering on a fake site and relayed in real time. WebAuthn uses an asymmetric key pair whose private key never leaves the device, and signatures are **origin-bound** — a phishing site can't obtain a signature valid for the real domain. No relayable shared secret exists.
- *Follow-up: What's a passkey vs a plain WebAuthn credential?* A discoverable, synced (cross-device) WebAuthn credential intended to replace passwords.
- *Follow-up: What does `signCount` defend against?* Cloned authenticators — a non-increasing counter signals duplication.

**Q11. What is IDOR/BOLA and how do you prevent it?**
*Model answer:* Insecure Direct Object Reference / Broken Object Level Authorization: accessing another user's object by changing an id, because only authN or coarse role was checked. Prevent with **object-level authZ** at the service/data layer (owner/relationship check) on every access, plus tests asserting the wrong user gets 403/404. It's the #1 OWASP API risk.
- *Follow-up: Why return 404 not 403?* Avoid confirming the object exists.
- *Follow-up: Can the gateway prevent IDOR?* No — it lacks the data context; only the service knows ownership.

**Q12. How do you propagate identity safely across async/reactive code on the JVM?**
*Model answer:* `SecurityContextHolder` is `ThreadLocal`, so it doesn't cross thread boundaries. Use `DelegatingSecurityContext{Executor,Runnable}` for thread pools, `MODE_INHERITABLETHREADLOCAL` carefully, and `ReactiveSecurityContextHolder` in WebFlux. Failing to do so yields null principals or, worse, identity bleed from pooled threads.
- *Follow-up: What's identity bleed?* A pooled thread retaining the previous request's principal, leaking authZ context across users.
- *Follow-up: How do you test it?* Concurrent requests with distinct principals asserting isolation.

---

## 11. Glossary

- **ABAC** — Attribute-Based Access Control: decisions from boolean rules over subject/resource/action/environment attributes.
- **Access token** — a (often short-lived) token granting access to APIs, per OAuth scopes.
- **ACL** — Access Control List: per-resource list of (principal, permissions).
- **alg confusion** — attack exploiting verifiers that accept multiple JWT algorithms (e.g., RS↔HS) to forge tokens.
- **Argon2id** — modern memory-hard password hashing function; OWASP's top choice.
- **Assertion (WebAuthn)** — the signed login response from an authenticator.
- **Attestation (WebAuthn)** — the signed registration response proving authenticator provenance.
- **AuthN** — authentication; verifying identity.
- **AuthZ** — authorization; deciding permissions.
- **Bearer token** — a token where mere possession grants access ("bearer").
- **bcrypt** — widely used CPU-hard password hash; 72-byte input limit.
- **BOLA** — Broken Object Level Authorization (OWASP API #1); same idea as IDOR.
- **CA (Certificate Authority)** — trusted issuer/signer of certificates.
- **Cedar** — AWS's RBAC+ABAC policy language/engine.
- **Claim** — a key/value statement inside a token (e.g., `sub`, `roles`).
- **Client Credentials** — OAuth flow for service-to-service auth (no user).
- **Constant-time comparison** — comparison whose duration doesn't depend on input, defeating timing attacks.
- **Credential** — proof presented to authenticate (password, key, token).
- **CSPRNG** — Cryptographically Secure Pseudo-Random Number Generator (`SecureRandom`).
- **CSRF** — Cross-Site Request Forgery: tricking a browser into sending authenticated requests; mitigated by SameSite cookies + CSRF tokens.
- **DPoP** — Demonstrating Proof-of-Possession (RFC 9449): binds a token to a client key.
- **ES256** — ECDSA-P256 + SHA-256 JWT signing (asymmetric, compact).
- **Fail closed** — on error/ambiguity, deny.
- **FIDO2** — standard comprising WebAuthn + CTAP for passwordless auth.
- **HMAC** — keyed hash proving integrity + secret knowledge.
- **HOTP** — counter-based one-time password (RFC 4226).
- **HS256** — HMAC-SHA256 JWT signing (symmetric, shared secret).
- **HttpOnly** — cookie flag blocking JS access (anti-XSS theft).
- **IDOR** — Insecure Direct Object Reference; accessing others' objects by id.
- **IdP (Identity Provider)** — issues authentication/identity (Keycloak, Okta).
- **JWKS** — JSON Web Key Set: an issuer's public keys, keyed by `kid`.
- **JWT** — JSON Web Token: signed `header.payload.signature` carrying claims.
- **kid** — key id; selects which key verifies a token.
- **MFA** — Multi-Factor Authentication: 2+ factors from different categories.
- **mTLS** — mutual TLS; both peers present certificates.
- **New enemy problem** — stale-ACL inconsistency in distributed authZ; solved by consistency tokens.
- **nbf** — JWT "not before" claim.
- **OAuth 2.0** — delegated authorization framework (access tokens, scopes).
- **Obligation** — a side condition attached to a permit decision (e.g., mask field).
- **OIDC** — OpenID Connect: authentication layer over OAuth (ID token).
- **OPA** — Open Policy Agent; general policy engine using Rego.
- **OpenFGA / SpiceDB / Keto** — open Zanzibar-style ReBAC systems.
- **PAP/PDP/PEP/PIP** — Policy Administration/Decision/Enforcement/Information Points.
- **Passkey** — discoverable, synced WebAuthn credential replacing passwords.
- **PBKDF2** — FIPS-approved iterative password hash.
- **PEP** — Policy Enforcement Point: enforces the decision.
- **Pepper** — global secret added to passwords, stored separately from the DB.
- **PKCE** — Proof Key for Code Exchange; protects the OAuth code flow on public clients.
- **Principal / subject** — the authenticated entity.
- **RBAC** — Role-Based Access Control: users→roles→permissions.
- **ReBAC** — Relationship-Based Access Control (Zanzibar-style graph).
- **Refresh token** — long-lived, revocable token used to mint new access tokens.
- **Rego** — OPA's policy language.
- **Relying Party (RP)** — the app/server in WebAuthn/OIDC.
- **RS256** — RSA + SHA-256 JWT signing (asymmetric).
- **Salt** — per-user random value defeating rainbow tables.
- **SameSite** — cookie attribute mitigating CSRF.
- **Scope** — an OAuth permission label limiting a token's reach.
- **Session fixation** — attack reusing a pre-set session id; fixed by id rotation on login.
- **SPIFFE/SPIRE/SVID** — workload identity standard, implementation, and credential.
- **Token versioning** — claim bumped to invalidate prior tokens (logout-all).
- **TLS** — Transport Layer Security; encrypts + authenticates the server.
- **TOTP** — time-based one-time password (RFC 6238), ±1 window.
- **WebAuthn** — W3C browser public-key authentication API.
- **X.509** — certificate format binding a public key to an identity.
- **XACML** — XML-based ABAC policy standard (legacy).
- **Zanzibar** — Google's global ReBAC authZ system.
- **Zookie** — Zanzibar consistency token preventing stale-ACL reads.
- **Zero Trust** — "never trust, always verify"; no implicit network trust.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **AuthN = who you are (401)**; **AuthZ = what you can do (403)**. AuthN first, then AuthZ; sometimes 404 to hide existence.
- **Factors:** know / have / are. **MFA** = ≥2 *different* categories. **Passkeys/WebAuthn = phishing-resistant** (origin-bound asymmetric keys); **TOTP is phishable** (relayable shared secret).
- **Passwords:** Argon2id (or bcrypt/scrypt/PBKDF2), per-user **salt**, optional **pepper**, slow/memory-hard, constant-time compare, cap length, upgrade-on-login. bcrypt cap 72 bytes; PBKDF2 ≥ 600k iters (verify current OWASP).
- **Sessions** = server state, instant revoke, browser-first. **JWT** = stateless, scales, hard to revoke → short TTL + refresh + `jti` denylist / token-version.
- **JWT verify musts:** pin `alg` (reject `none`/confusion), check `exp/nbf/iss/aud`, cache JWKS by `kid`, allow ~30–60s skew. Fail closed.
- **OAuth = delegated authZ; OIDC = authN on top. Default flow = Auth Code + PKCE.** `state`=CSRF, PKCE=code interception.
- **AuthZ models:** RBAC (roles; role-explosion) / ABAC (attributes+context; audit-hard) / ReBAC (relationship graph; Zanzibar; new-enemy→zookies) / ACL (per-object). Most systems **hybrid**.
- **PEP/PDP/PIP/PAP:** enforce / decide / fetch-attrs / author.
- **Microservices:** authN at edge → propagate **signed** identity → **authZ per service** (instance-level, no IDOR) → **mesh mTLS** (workload) → **data-layer RLS** (tenant). Centralized policy + decentralized evaluation. **Zero trust.**
- **mTLS = which service; token = on whose behalf.**
- **Top anti-patterns:** gateway-only authZ, missing object-level authZ (IDOR/BOLA), long-lived JWTs sans revocation, secrets in `localStorage`/logs, trusting client `X-User-Id`, role explosion, rolling your own JWT/crypto, `ThreadLocal` context not propagated to async.
- **Spring:** `SecurityFilterChain`, `@EnableMethodSecurity` + `@PreAuthorize`, `DelegatingPasswordEncoder`, `oauth2ResourceServer().jwt()`, `ReactiveSecurityContextHolder` for WebFlux.

### Self-test (no answers — recall actively)

1. Why is 401 actually "unauthenticated," and when would you deliberately return 404 from an authZ check?
2. You must support instant logout-everywhere but the team insists on stateless JWTs. Lay out three concrete mechanisms and the tradeoff of each.
3. Explain, with the data model, how a Zanzibar/ReBAC `Check` decides that Alice can view a document she only has access to via a shared folder — and what the new-enemy problem is.
4. Walk through every guard in the OIDC Authorization Code + PKCE flow and state precisely which attack each prevents.
5. A multi-tenant SaaS leaks one tenant's rows to another. Name the four independent layers where you'd add defenses and what each enforces.
6. Why is TOTP phishable but a passkey is not? Tie your answer to exactly what gets signed.
7. Your async service intermittently logs actions under the wrong user. What's the JVM-specific root cause and the fix?

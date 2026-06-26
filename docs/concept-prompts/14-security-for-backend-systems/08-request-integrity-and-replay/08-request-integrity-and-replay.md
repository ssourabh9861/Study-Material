# Request Integrity & Replay Protection

> A definitive engineering-handbook chapter for senior JVM/Java backend developers. Covers authenticity and tamper-evidence of requests, HMAC signing and verification, replay attacks and their defenses, end-to-end webhook security (the GitHub/Stripe model), the difference between message authentication and encryption, CSRF prevention, and the operational concerns of running all of this in production.

---

## 1. Overview & where it fits

### 1.1 What this is

**Request integrity** is the property that a request arrives at your server **exactly as the legitimate sender produced it** — not modified, truncated, reordered, or fabricated in transit. **Replay protection** is the closely related property that a request that *was* legitimate **cannot be captured and re-sent later** to cause the same effect a second time.

These two properties are distinct from, and orthogonal to, three other security properties people routinely confuse them with:

- **Confidentiality** — nobody can *read* the contents. (Solved by encryption, e.g. TLS.)
- **Authentication of identity** — you know *who* the principal is (a user, a service). (Solved by passwords, tokens, mTLS, OIDC.)
- **Authorization** — that principal is *allowed* to do the thing. (Solved by RBAC/ABAC/policy.)

Integrity answers a narrower question: *"Is this byte sequence the unaltered output of a party who holds the secret?"* Replay protection answers: *"Have I seen this exact, valid request before — or is it too old to still be trustworthy?"*

> **Mental model (one paragraph).** Think of a request as a sealed letter. **Encryption** is an opaque envelope nobody can see through. **Integrity (a signature/MAC)** is a tamper-evident wax seal: anyone can see the letter, but if a single character is changed, the seal no longer matches and you reject it. **Replay protection** is a date stamp plus a "I've already opened this exact letter" ledger: even a perfectly sealed, genuine letter gets rejected if it's a photocopy of one you already processed, or if it's older than your acceptance window. Integrity and replay protection are about *trusting the message itself*, independent of the transport that carried it.

### 1.2 The problem it solves

TLS (Transport Layer Security — the cryptographic protocol behind HTTPS) already gives you integrity and confidentiality **on the wire, hop by hop**. So why build more? Because real systems have *gaps where TLS does not reach*:

1. **TLS terminates early.** Load balancers, API gateways, CDNs, and reverse proxies decrypt TLS at the edge. Past that point the request travels as plaintext over your internal network, through queues, into logs, across service hops. TLS protected the *first* hop, not the journey.
2. **The other party is not your TLS peer.** A webhook from Stripe to your server is TLS-protected between Stripe and *whatever terminates your TLS* — but you, the application, need to independently prove "this really came from Stripe and wasn't forged by someone who guessed my webhook URL."
3. **Messages get stored and forwarded.** A signed command dropped on a Kafka topic, an SQS queue, or an S3 object outlives any single TLS connection. Integrity has to live *with the message*, not with the channel.
4. **TLS does nothing about replay at the application layer.** TLS prevents an attacker from replaying *raw TLS records*, but it cannot stop someone who legitimately observed (or was handed) a full HTTP request from sending that same HTTP request again to your endpoint.
5. **Browsers auto-attach ambient credentials.** Cookies are sent by the browser on *every* request to a domain, including requests triggered by a malicious third-party site. That is the root of CSRF, which is a request-authenticity failure: the request is technically "from the user's browser" but was not *intended* by the user.

So request integrity & replay protection is the layer that makes individual messages **self-authenticating and single-use**, independent of the channel.

### 1.3 When you reach for it

| Situation | What you need |
|---|---|
| Receiving webhooks (Stripe, GitHub, Slack, Twilio, payment processors) | HMAC signature verification + timestamp/replay check |
| Service-to-service calls without mTLS | Signed requests (HMAC or asymmetric) |
| Public APIs where clients hold an API key/secret | Request signing (AWS SigV4 style) |
| Any "do this exactly once" mutation (charge a card, ship an order) | Idempotency keys |
| Browser form posts / state-changing endpoints behind cookies | CSRF tokens (or SameSite cookies) |
| Messages on a bus/queue that must not be tampered or forged | Message-level MAC/signature |
| Signed URLs (download links, presigned S3, password-reset links) | HMAC over the URL + expiry |

### 1.4 Where it sits in the stack

```
        ┌─────────────────────────────────────────────────────────┐
        │ Application layer: integrity + replay protection         │  ← THIS CHAPTER
        │  • HMAC signature on body/timestamp/path                 │
        │  • nonce / timestamp window / idempotency ledger         │
        │  • CSRF tokens                                           │
        ├─────────────────────────────────────────────────────────┤
        │ Identity / AuthN (tokens, mTLS, OIDC)                    │
        ├─────────────────────────────────────────────────────────┤
        │ Transport security: TLS (confidentiality + per-hop       │
        │ integrity, on the wire only)                            │
        ├─────────────────────────────────────────────────────────┤
        │ Network (TCP/IP)                                         │
        └─────────────────────────────────────────────────────────┘
```

Key insight: **these layers stack; they do not substitute.** TLS does not remove the need for HMAC verification of webhooks, and an HMAC does not remove the need for TLS (a MAC gives integrity and authenticity but **not confidentiality** — anyone watching the wire still reads the body unless TLS hides it).

---

## 2. Foundations from first principles

We build up the vocabulary from zero. Every term gets defined the moment it appears.

### 2.1 Hash function

A **cryptographic hash function** takes an arbitrary-length input and produces a fixed-length output called a **digest** (or just "hash"). Examples: SHA-256 (256-bit / 32-byte output), SHA-512, SHA-1 (broken — avoid), MD5 (very broken — avoid). The properties that matter:

- **Deterministic:** same input → same digest, always.
- **Preimage resistance:** given a digest, you cannot feasibly find an input that produces it.
- **Second-preimage / collision resistance:** you cannot feasibly find two different inputs with the same digest. (A **collision** is two inputs that hash to the same value; MD5 and SHA-1 have practical collisions, which is why they're dead for security.)
- **Avalanche effect:** flipping one input bit changes ~half the output bits, unpredictably.

A bare hash gives you **integrity against accidental corruption** (like a checksum) but **not against a malicious actor**, because anyone can recompute the hash of their tampered message. To make integrity *unforgeable*, you need a secret. That's where MACs come in.

### 2.2 MAC (Message Authentication Code)

A **MAC** is a short tag computed from `(message, secret_key)` such that:

- Anyone holding the **shared secret key** can compute and verify the tag.
- Anyone *without* the key cannot produce a valid tag for any message (existential unforgeability), even after seeing many valid `(message, tag)` pairs.

A valid MAC proves two things at once:
1. **Integrity** — the message wasn't altered (any change breaks the tag).
2. **Authenticity** — it was produced by someone holding the secret.

It does **not** prove *which* of the secret-holders sent it (both sides share the same key — see non-repudiation below) and it does **not** hide the message (no confidentiality).

### 2.3 HMAC (Hash-based MAC)

**HMAC** is a specific, standardized (RFC 2104, FIPS 198-1) construction that turns *any* cryptographic hash into a secure MAC. The naive idea "just hash the secret concatenated with the message" (`H(key || message)`) is **insecure** for Merkle–Damgård hashes (SHA-1, SHA-2 family) because of the **length-extension attack**: an attacker who knows `H(key || message)` and the length of `key` can compute `H(key || message || extra)` *without knowing the key*, forging a valid tag for an extended message. HMAC defeats this with a nested, double-hash structure:

```
HMAC(K, m) = H( (K' XOR opad) || H( (K' XOR ipad) || m ) )
```

where:
- `K'` is the key, padded/hashed to the hash's block size.
- `ipad` = the byte `0x36` repeated to block length.
- `opad` = the byte `0x5c` repeated to block length.
- `||` is concatenation; `XOR` is bitwise exclusive-or.

You don't implement this yourself — every platform ships it (`javax.crypto.Mac` with `"HmacSHA256"` in Java). You just need to know: **HMAC-SHA256 is the modern default.** Output is 32 bytes (64 hex chars). It is fast, secure, and length-extension-safe.

> **Note on SHA-3 / BLAKE2 / KMAC.** SHA-3 (Keccak) and BLAKE2 are *not* Merkle–Damgård and are not vulnerable to length extension, so they can be keyed more directly (KMAC, BLAKE2's keyed mode). But HMAC-SHA256 remains the interoperability default because nearly every webhook provider and SDK speaks it.

### 2.4 Symmetric vs asymmetric authentication

- **Symmetric (HMAC):** both parties share **one secret key**. Fast, simple, ubiquitous. Downside: every verifier can also *forge* (they hold the same key), so there's **no non-repudiation** — you can't prove to a third party *which* side authored a message.
- **Asymmetric (digital signatures — RSA, ECDSA, Ed25519):** the signer holds a **private key**; verifiers hold the **public key**. Only the private-key holder can produce a signature, but anyone can verify. This gives **non-repudiation** and lets you publish the verification key widely (no shared secret to leak). Downside: slower, heavier, more operationally complex (key rotation, PKI).

**Rule of thumb:** for two parties who already share a secret (you ↔ your webhook provider, you ↔ your own services), HMAC is the right tool. When verifiers shouldn't be able to forge, or the verifier set is large/untrusted, use asymmetric signatures. (GitHub uses HMAC; some newer systems and JWTs use asymmetric.)

### 2.5 Non-repudiation

**Non-repudiation** means a party cannot later deny having sent a message, because the proof is cryptographically tied to *their private key alone*. HMAC does **not** provide this (shared key → either party could have made the tag). Asymmetric signatures do. Important to state explicitly in design reviews so nobody assumes "we HMAC it" equals "we can prove in court who sent it."

### 2.6 Nonce

A **nonce** ("number used once") is a value that must never repeat within a given context. In replay protection, a sender includes a fresh random nonce in each request; the receiver remembers nonces it has seen and rejects duplicates. Nonces give *exact* one-time semantics but require the server to **store** seen nonces (memory/state cost), so they're usually paired with a timestamp window to bound how long you must remember each nonce.

### 2.7 Replay attack

A **replay attack** is when an attacker captures a *valid* message (it has a correct signature, a real token) and **re-sends it** to trigger the action again. The message is genuine — that's what makes it dangerous; the signature checks out. Defenses don't try to detect "fakeness" (there is none); they detect *"I've seen this before"* (nonce/idempotency) or *"this is too old"* (timestamp window).

Classic example: you sniff a signed "transfer \$100 from A to B" request. You can't decrypt or modify it, but you can fire it 50 times and drain the account — *unless* the request carries a nonce or timestamp the server enforces.

### 2.8 Idempotency

An operation is **idempotent** if performing it multiple times has the same effect as performing it once. `GET`, `PUT`, and `DELETE` are idempotent by HTTP semantics; `POST` typically is not (two POSTs = two orders). An **idempotency key** is a client-supplied unique ID attached to a mutating request; the server records the *outcome* keyed by that ID and, on a retry with the same key, returns the stored outcome instead of re-executing. This is replay/duplicate protection that is also *retry-safe by design* — it's how Stripe lets clients safely retry a charge after a network timeout.

> **Replay protection vs idempotency — subtle but important.** Nonce-based replay protection says "I reject any repeat." Idempotency says "I *accept* repeats but make them harmless by returning the first result." For client-initiated retries you usually want idempotency (retries are legitimate). For defending against an *attacker* replaying a webhook you usually want timestamp+signature (and dedup). They overlap and are often combined.

### 2.9 Constant-time comparison

When you compare the received signature to the one you computed, a naive `equals()` returns as soon as it finds the first mismatching byte. The *time it takes to return* leaks how many leading bytes matched — a **timing side-channel** an attacker can exploit to forge a valid signature byte-by-byte (a **timing attack**). A **constant-time comparison** always inspects every byte and takes the same time regardless of where (or whether) bytes differ. In Java: `java.security.MessageDigest.isEqual(a, b)` (constant-time since Java 6u17). Never use `String.equals`, `Arrays.equals`, or `==` for secret/MAC comparison.

### 2.10 CSRF (Cross-Site Request Forgery)

**CSRF** is a request-*intent* attack on browser apps that authenticate with **cookies**. Because the browser auto-attaches a site's cookies to *any* request to that site — including one triggered by a malicious page the user is visiting — an attacker can cause the user's browser to send an authenticated, state-changing request the user never intended (e.g., a hidden form that POSTs "change my email"). It's a request-authenticity failure: the credential is valid, but the *origin/intent* is forged. Defenses: anti-CSRF tokens, `SameSite` cookies, and origin checks (Section 5.6 & 7).

### 2.11 The threat model, stated plainly

We assume an attacker who can:
- Observe requests (sniff, read logs, sit on a shared network or compromised proxy).
- Modify and replay requests.
- Send arbitrary requests to your endpoints (your webhook URL is effectively public).
- *Not* read your secret keys (if they have your keys, game over — protect keys first).

Integrity/replay protection makes it so that observing and replaying buys the attacker nothing: they can't alter a request without breaking the MAC, can't forge a new one without the key, and can't replay an old one past the window or twice.

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, the full lifecycle of a signed-and-replay-protected request.

### 3.1 The canonical signing scheme (what good systems actually do)

A robust scheme signs a **canonical string** built from the parts of the request that matter, plus anti-replay material:

```
signing_string = method + "\n"
               + path (+ canonical query) + "\n"
               + timestamp + "\n"
               + nonce + "\n"
               + sha256(body)        // or the raw body bytes
```

The signature is `HMAC-SHA256(secret, signing_string)`, sent in a header. The receiver rebuilds the same string from the received request and recomputes the HMAC.

**Why each component:**

| Component | Why it must be signed |
|---|---|
| HTTP method | Stop an attacker changing `GET` → `DELETE` on the same path |
| Path (+ query) | Stop redirecting the request to a different resource |
| Timestamp | Enables the freshness/replay window |
| Nonce | Enables exact dedup within the window |
| Body (or its hash) | The actual payload — the whole point |

> **Critical pitfall previewed (full treatment in §6):** if you sign *only the body* but route on the *path*, an attacker can replay a valid body against a *different* endpoint. If you sign the timestamp but never *check* it, you have no replay protection at all. Signing the right thing is as important as signing.

### 3.2 Sender-side control flow (step by step)

1. **Assemble the request** (method, path, headers, body).
2. **Generate a timestamp** — current Unix time (seconds or milliseconds; agree on units).
3. **Generate a nonce** — a cryptographically random value (e.g., 16 random bytes, hex/base64). Use `SecureRandom`, never `Math.random()` or `java.util.Random`.
4. **Canonicalize** — build the exact `signing_string` per the agreed scheme. Canonicalization is the act of producing *one deterministic byte representation* both sides will agree on (sort query params, fix encoding, decide on trailing slashes, choose body-hash vs raw body). Disagreement here is the #1 source of "signatures don't match" bugs.
5. **Compute HMAC** — `HMAC-SHA256(secret, signing_string)`, encode as hex or base64.
6. **Attach** — put signature, timestamp, nonce (and key id / algorithm) in headers, e.g.
   `X-Signature: t=1718900000,v1=5257a8...`
7. **Send over TLS.** (Yes, still TLS — MAC ≠ confidentiality.)

### 3.3 Receiver-side control flow (step by step)

This order matters; do the cheap, safe checks before the expensive ones, but **never branch on secret-dependent timing**.

1. **Read the raw body bytes** *before any parsing/deserialization.* You must HMAC the **exact bytes received**, not a re-serialized object — JSON re-serialization reorders keys, changes whitespace, and breaks the MAC. (Framework gotcha: many web frameworks consume the body stream during parsing; you must capture raw bytes first. See §5.)
2. **Parse the signature header** — extract timestamp, nonce, key id, and the provided signature value(s). If the header is missing/malformed → reject (`400`/`401`). Do not leak *why* in detail.
3. **Resolve the secret** by key id (supports rotation — see §7.3). Unknown key id → reject.
4. **Check the timestamp window** — `abs(now − timestamp) ≤ tolerance` (typical 5 minutes / 300 s). Outside → reject (`401`/`403`). This bounds replay and also how long you must remember nonces.
5. **Check the nonce / idempotency ledger** — if you've seen this nonce within the window → reject as replay. (If using idempotency keys, instead *return the stored result*.) Atomic "check-and-store" to avoid races (see §6.2).
6. **Recompute the canonical string** exactly as the sender built it, including hashing the raw body.
7. **Recompute the HMAC** with the resolved secret.
8. **Constant-time compare** recomputed vs provided. Mismatch → reject (`401`). Use `MessageDigest.isEqual`.
9. **Only now** — parse/deserialize the body and execute business logic.
10. **Record the nonce/idempotency key** (if not already done atomically in step 5) so a future replay is caught.

State machine view:

```
RECEIVED
  └─ header present? ──no──▶ REJECT(malformed)
        │yes
        ▼
  key id known? ──no──▶ REJECT(unknown key)
        │yes
        ▼
  timestamp fresh? ──no──▶ REJECT(stale / clock skew)
        │yes
        ▼
  nonce unseen? ──no──▶ REJECT(replay)   [or RETURN cached result if idempotent]
        │yes
        ▼
  HMAC matches (constant-time)? ──no──▶ REJECT(bad signature)
        │yes
        ▼
  EXECUTE ──▶ store nonce/key ──▶ RESPOND
```

### 3.4 Data flow for a webhook (the GitHub/Stripe model, end to end)

1. **Provisioning.** You register a webhook URL with the provider. The provider generates (or you set) a **signing secret** (Stripe: `whsec_...`; GitHub: a secret you choose). This secret is shown **once**; store it in a secrets manager.
2. **Event happens.** A charge succeeds (Stripe) / a push lands (GitHub).
3. **Provider builds the payload** (JSON) and computes a signature:
   - **Stripe:** `HMAC-SHA256(whsec, "{timestamp}.{raw_body}")`, sent as `Stripe-Signature: t=<ts>,v1=<sig>`. The timestamp is *inside* the signed string, so it's tamper-evident, and Stripe expects you to enforce a tolerance (their SDK default ≈ 300 s).
   - **GitHub:** `HMAC-SHA256(secret, raw_body)`, sent as `X-Hub-Signature-256: sha256=<hexsig>` (the older `X-Hub-Signature` used SHA-1 — deprecated). GitHub also sends `X-GitHub-Delivery` (a unique GUID per delivery) you can use for dedup, and an `X-GitHub-Event` header.
4. **Provider POSTs over HTTPS** to your endpoint, retrying on failure (so **expect duplicates** — design for idempotency).
5. **You verify** per §3.3: raw body → recompute HMAC → constant-time compare → (Stripe) check timestamp tolerance → dedup by event id/delivery id.
6. **You ACK fast** (`2xx`) and process asynchronously. If you do heavy work synchronously and time out, the provider retries → duplicate deliveries.

### 3.5 Why the timestamp must be *inside* the signed string

If the timestamp were an *unsigned* header, an attacker replaying an old request could simply rewrite the timestamp to "now," passing your freshness check while reusing the old (valid) signature… except the signature was computed over the *old* timestamp, so it'd fail — *only if the timestamp is part of the signed string*. That's exactly why Stripe signs `timestamp.body`. Sign your anti-replay material, always.

### 3.6 The nonce ledger lifecycle

- **Storage:** a set/keyed store of recently-seen nonces (or idempotency keys), each with a TTL equal to the timestamp tolerance (plus a safety margin for clock skew).
- **Write:** on accept, store `nonce → expiry`.
- **Read:** on each request, check membership.
- **Eviction:** TTL expiry frees memory. You only need to remember nonces for as long as a request could *also* pass the timestamp window — older ones can't replay anyway.
- **Distributed concern:** with multiple app instances the ledger must be **shared and atomic** (Redis `SET key val NX EX <ttl>` returns whether it was newly set — atomic check-and-claim). A local in-memory set fails behind a load balancer (a replay routed to a different instance succeeds). See §6 & §9.

---

## 4. The complete toolkit

### 4.1 Java cryptographic APIs

| API | Purpose | Key parameters / notes | Default / value |
|---|---|---|---|
| `javax.crypto.Mac` | Compute HMAC | `Mac.getInstance("HmacSHA256")`, `init(SecretKeySpec)`, `doFinal(bytes)` | Algorithm strings: `HmacSHA256`, `HmacSHA512`, `HmacSHA1` (avoid) |
| `javax.crypto.spec.SecretKeySpec` | Wrap raw key bytes | `new SecretKeySpec(keyBytes, "HmacSHA256")` | Key length ≥ hash output (32B for SHA-256) recommended |
| `java.security.MessageDigest` | Hashing **and** constant-time compare | `getInstance("SHA-256")`; `MessageDigest.isEqual(a,b)` | `isEqual` is constant-time (since 6u17) |
| `java.security.SecureRandom` | CSPRNG for nonces/keys | `getInstanceStrong()` or default; `nextBytes(byte[])` | Use this, **never** `Random`/`Math.random` |
| `java.util.HexFormat` (Java 17+) | Hex encode/decode | `HexFormat.of().formatHex(bytes)` / `parseHex` | Replaces hand-rolled hex loops |
| `java.util.Base64` | Base64 encode/decode | `getEncoder()`, `getUrlEncoder().withoutPadding()` | Use URL-safe for headers/URLs |
| `java.time.Instant` / `Duration` | Timestamps & tolerance | `Instant.now().getEpochSecond()`; `Duration.ofMinutes(5)` | Use a clock abstraction for testability |
| `java.security.Signature` | Asymmetric sign/verify | `getInstance("Ed25519")` / `"SHA256withECDSA"` / `"SHA256withRSA"` | For non-repudiation use cases |
| `KeyPairGenerator` / `KeyFactory` | Asymmetric key mgmt | `getInstance("EC"|"Ed25519"|"RSA")` | — |

### 4.2 Algorithm strings cheat (JCA "Mac" names)

| String | Hash | Output | Use |
|---|---|---|---|
| `HmacSHA256` | SHA-256 | 32 B / 64 hex | **Default choice** |
| `HmacSHA512` | SHA-512 | 64 B | Fine; longer tag |
| `HmacSHA1` | SHA-1 | 20 B | Legacy interop only (GitHub `X-Hub-Signature`); avoid for new designs |
| `HmacMD5` | MD5 | 16 B | Do not use |

> SHA-1 as a *hash for collisions* is broken, but **HMAC-SHA1 has no known practical break** because HMAC's security depends on the hash's PRF property, not collision resistance. Still, don't pick it for new systems — there's no upside over SHA-256.

### 4.3 Spring Security CSRF & request toolkit

| Tool | Purpose | Key knobs / defaults |
|---|---|---|
| `CsrfFilter` / `http.csrf()` | CSRF protection in Spring Security | **On by default** for browser flows; disabled for stateless APIs |
| `CookieCsrfTokenRepository` | Store CSRF token in a cookie (SPA-friendly, double-submit) | `withHttpOnlyFalse()` so JS can read it |
| `XorCsrfTokenRequestAttributeHandler` | BREACH-mitigating token masking (Spring Security 6 default) | — |
| `ContentCachingRequestWrapper` | Buffer the raw body so you can read it *and* let the framework parse it | Wrap in a filter |
| Servlet `Filter` / `OncePerRequestFilter` | Where you typically run signature verification | Order it before controllers |
| `SameSite` cookie attribute | Browser-enforced CSRF mitigation | `Lax` (modern browser default), `Strict`, `None` (requires `Secure`) |

### 4.4 Provider SDK helpers (don't hand-roll if a verified helper exists)

| Provider | Helper | What it does |
|---|---|---|
| Stripe | `Webhook.constructEvent(payload, sigHeader, secret)` (Java SDK) | Verifies HMAC **and** timestamp tolerance, throws on failure |
| GitHub | (no official Java helper) verify `X-Hub-Signature-256` yourself | `sha256=` + HMAC-SHA256 of raw body |
| Slack | `v0=` HMAC over `v0:{ts}:{body}` + `X-Slack-Request-Timestamp` (reject > 5 min) | DIY but documented |
| AWS | SigV4 (`Aws4Signer` in AWS SDK) | Canonical request signing for API calls |
| Svix (webhook infra used by many SaaS) | Svix libraries | Standard `svix-id`/`svix-timestamp`/`svix-signature` verify + replay window |

### 4.5 Datastores for nonce/idempotency ledgers

| Store | Primitive | Why |
|---|---|---|
| Redis | `SET key 1 NX EX <ttl>` (atomic claim) | Fast, TTL built-in, shared across instances — **the common choice** |
| Redis | `SET ... NX` returning the claim result | Idempotency: store result blob keyed by idempotency key |
| Relational DB | unique constraint on `idempotency_key` + insert-or-conflict | Strong durability; transactional with business write |
| DynamoDB | conditional put (`attribute_not_exists`) + TTL | Serverless-friendly atomic claim |
| In-memory (Caffeine) | bounded TTL cache | **Single-instance only**; unsafe behind a load balancer |

---

## 5. Code examples by use case

All examples are Java (17+ idioms where convenient) and self-contained enough to adapt.

### 5.1 Core HMAC compute + constant-time verify

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Hmac {
    private static final String ALG = "HmacSHA256";

    /** Compute HMAC-SHA256 over the given bytes; return lowercase hex. */
    public static String hmacHex(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret, ALG));
            byte[] tag = mac.doFinal(message);
            return HexFormat.of().formatHex(tag);   // Java 17+: no hand-rolled hex
        } catch (Exception e) {
            // Algorithm always present; key always valid here. Treat as fatal.
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    /**
     * Constant-time verification. We hex-decode both and compare the RAW BYTES
     * with MessageDigest.isEqual so timing doesn't leak how many bytes matched.
     */
    public static boolean verify(byte[] secret, byte[] message, String providedHex) {
        String expectedHex = hmacHex(secret, message);
        // Compare decoded bytes (not strings). isEqual is constant-time.
        byte[] expected = HexFormat.of().parseHex(expectedHex);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(providedHex);
        } catch (IllegalArgumentException badHex) {
            return false;                          // malformed signature -> reject
        }
        // Length check is fine to short-circuit (length isn't the secret).
        if (expected.length != provided.length) return false;
        return MessageDigest.isEqual(expected, provided);
    }
}
```

Why it's written this way: `MessageDigest.isEqual` is the JDK's constant-time comparator; `HexFormat` avoids subtle hex bugs; we never compare with `String.equals`.

### 5.2 Verifying a Stripe webhook (manual, to show the mechanics)

```java
import java.time.Instant;

public final class StripeWebhookVerifier {

    private static final long TOLERANCE_SECONDS = 300; // 5 min, Stripe's convention

    /**
     * @param rawBody  the EXACT bytes Stripe POSTed (never a re-serialized object)
     * @param sigHeader value of the "Stripe-Signature" header: "t=...,v1=...,v1=..."
     * @param secret   the whsec_... signing secret bytes
     */
    public boolean verify(byte[] rawBody, String sigHeader, byte[] secret) {
        long ts = -1;
        java.util.List<String> v1s = new java.util.ArrayList<>();
        for (String part : sigHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].trim()) {
                case "t"  -> ts = Long.parseLong(kv[1].trim());
                case "v1" -> v1s.add(kv[1].trim());   // can be multiple during rotation
            }
        }
        if (ts < 0 || v1s.isEmpty()) return false;

        // 1) Freshness / replay window — timestamp is INSIDE the signed string,
        //    so it cannot be tampered without breaking the signature below.
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > TOLERANCE_SECONDS) return false;

        // 2) Signed payload is "timestamp.rawBody"
        byte[] signedPayload = (ts + ".").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] message = concat(signedPayload, rawBody);

        // 3) Accept if ANY provided v1 matches (supports overlapping secrets on rotation)
        for (String v1 : v1s) {
            if (Hmac.verify(secret, message, v1)) return true;
        }
        return false;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
```

In production, prefer the official SDK: `com.stripe.net.Webhook.constructEvent(payloadString, sigHeader, secret)` — it does all of the above and throws `SignatureVerificationException` on failure. The manual version is here to show *what the SDK is doing*.

### 5.3 GitHub webhook verification in a Spring `@RestController` (capturing the raw body)

```java
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GitHubWebhookController {

    private final byte[] secret; // injected from a secrets manager, NOT a constant

    public GitHubWebhookController(byte[] secret) { this.secret = secret; }

    @PostMapping("/webhooks/github")
    public ResponseEntity<String> receive(
            // @RequestBody byte[] gives us the RAW bytes — no JSON re-serialization.
            @RequestBody byte[] rawBody,
            @RequestHeader("X-Hub-Signature-256") String sigHeader,   // "sha256=...."
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) {

        if (sigHeader == null || !sigHeader.startsWith("sha256=")) {
            return ResponseEntity.status(400).body("missing signature");
        }
        String providedHex = sigHeader.substring("sha256=".length());

        // GitHub signs the RAW body (no timestamp in the signature).
        if (!Hmac.verify(secret, rawBody, providedHex)) {
            return ResponseEntity.status(401).body("bad signature");
        }

        // Replay/dedup: GitHub has no timestamp; use the unique delivery GUID.
        if (deliveryId != null && !markProcessed(deliveryId)) {
            return ResponseEntity.ok("duplicate ignored"); // already handled
        }

        // ... parse and enqueue for async processing ...
        return ResponseEntity.ok("ok");
    }

    /** Atomic claim; returns false if already seen. Back this with Redis SET NX EX. */
    private boolean markProcessed(String deliveryId) { /* redis SETNX */ return true; }
}
```

Note: `@RequestBody byte[]` (or `String`) gives the raw payload. If you bind to a POJO, Spring deserializes and you lose the exact bytes — your HMAC will not match. If you need both, buffer with `ContentCachingRequestWrapper` in a filter.

### 5.4 Full custom request signing for service-to-service (sign method+path+ts+nonce+body)

```java
// ---- Signer (caller side) ----
public final class RequestSigner {
    private final byte[] secret;
    private final String keyId;
    private final java.security.SecureRandom rng = new java.security.SecureRandom();

    public RequestSigner(String keyId, byte[] secret) { this.keyId = keyId; this.secret = secret; }

    public Signed sign(String method, String path, byte[] body) {
        long ts = java.time.Instant.now().getEpochSecond();
        byte[] nonceBytes = new byte[16];
        rng.nextBytes(nonceBytes);
        String nonce = java.util.HexFormat.of().formatHex(nonceBytes);

        String bodyHash = Hmac.hmacHex(secret, body); // or sha256(body); here we reuse
        String canonical = String.join("\n",
                method.toUpperCase(),
                path,
                Long.toString(ts),
                nonce,
                bodyHash);
        String sig = Hmac.hmacHex(secret,
                canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Signed(keyId, ts, nonce, sig);
    }
    public record Signed(String keyId, long ts, String nonce, String sig) {}
}

// ---- Verifier (receiver side) ----
public final class RequestVerifier {
    private final java.util.function.Function<String, byte[]> secretByKeyId; // rotation-aware
    private final NonceStore nonceStore;
    private final long toleranceSeconds = 300;

    public RequestVerifier(java.util.function.Function<String,byte[]> secrets, NonceStore ns) {
        this.secretByKeyId = secrets; this.nonceStore = ns;
    }

    public boolean verify(String method, String path, byte[] body,
                          String keyId, long ts, String nonce, String providedSig) {
        byte[] secret = secretByKeyId.apply(keyId);
        if (secret == null) return false;                 // unknown key id

        long now = java.time.Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > toleranceSeconds) return false; // stale -> replay window

        // Atomic claim. Reject if nonce already seen within TTL.
        if (!nonceStore.claim(nonce, toleranceSeconds + 60)) return false;

        String bodyHash = Hmac.hmacHex(secret, body);
        String canonical = String.join("\n",
                method.toUpperCase(), path, Long.toString(ts), nonce, bodyHash);
        return Hmac.verify(secret,
                canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8), providedSig);
    }
}

interface NonceStore {
    /** @return true if newly claimed; false if already present (replay). Must be atomic. */
    boolean claim(String nonce, long ttlSeconds);
}
```

### 5.5 Redis-backed atomic nonce/idempotency store

```java
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

public final class RedisNonceStore implements NonceStore {
    private final JedisPooled jedis;
    public RedisNonceStore(JedisPooled jedis) { this.jedis = jedis; }

    @Override public boolean claim(String nonce, long ttlSeconds) {
        // SET key val NX EX ttl : atomically sets ONLY if absent, with expiry.
        // Returns "OK" if it set the key (first time), null if it already existed.
        String res = jedis.set("nonce:" + nonce, "1",
                new SetParams().nx().ex(ttlSeconds));
        return "OK".equals(res);
    }
}
```

This is the linchpin for correctness across multiple app instances: the atomic `SET NX EX` collapses the check-and-store into one race-free step.

### 5.6 Idempotency key handling (store-the-result pattern, Stripe-style)

```java
public final class IdempotencyService {
    private final JedisPooled redis;
    public IdempotencyService(JedisPooled redis) { this.redis = redis; }

    /**
     * Returns cached response if this key was already processed; otherwise
     * runs the action exactly once and caches its result.
     */
    public String execute(String idempotencyKey, java.util.function.Supplier<String> action) {
        String k = "idem:" + idempotencyKey;
        // Try to claim the key. Value "IN_PROGRESS" guards against concurrent dup.
        boolean claimed = "OK".equals(redis.set(k, "IN_PROGRESS",
                new redis.clients.jedis.params.SetParams().nx().ex(86400))); // 24h
        if (!claimed) {
            String existing = redis.get(k);
            if ("IN_PROGRESS".equals(existing)) {
                // A concurrent request is still running; client should retry (409/425).
                throw new ConcurrentRetryException();
            }
            return existing; // cached final result -> idempotent replay is harmless
        }
        String result = action.get();   // execute exactly once
        redis.set(k, result, new redis.clients.jedis.params.SetParams().ex(86400));
        return result;
    }
    static final class ConcurrentRetryException extends RuntimeException {}
}
```

For money-movement, prefer backing this with a **database unique constraint** on the idempotency key in the *same transaction* as the business write, so the "claim" and the "effect" commit atomically (Redis can drift from your DB on partial failures).

### 5.7 Signed URL (HMAC over URL + expiry) — presigned-download pattern

```java
public final class SignedUrl {
    private final byte[] secret;
    public SignedUrl(byte[] secret) { this.secret = secret; }

    public String sign(String path, long expiresEpoch) {
        String toSign = path + "?expires=" + expiresEpoch;
        String sig = Hmac.hmacHex(secret,
                toSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return path + "?expires=" + expiresEpoch + "&sig=" + sig;
    }

    public boolean validate(String path, long expiresEpoch, String providedSig) {
        if (java.time.Instant.now().getEpochSecond() > expiresEpoch) return false; // expired
        String toSign = path + "?expires=" + expiresEpoch;
        return Hmac.verify(secret,
                toSign.getBytes(java.nio.charset.StandardCharsets.UTF_8), providedSig);
    }
}
```

### 5.8 CSRF: double-submit cookie verification (for cookie-auth SPAs)

```java
import jakarta.servlet.*;
import jakarta.servlet.http.*;

public class CsrfDoubleSubmitFilter extends org.springframework.web.filter.OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,
            HttpServletResponse res, FilterChain chain) throws ServletException, java.io.IOException {
        String method = req.getMethod();
        boolean stateChanging = !("GET".equals(method) || "HEAD".equals(method)
                || "OPTIONS".equals(method) || "TRACE".equals(method));
        if (stateChanging) {
            String cookieToken = readCookie(req, "XSRF-TOKEN");
            String headerToken = req.getHeader("X-XSRF-TOKEN");
            // Constant-time compare; both must be present and equal.
            if (cookieToken == null || headerToken == null
                    || !java.security.MessageDigest.isEqual(
                            cookieToken.getBytes(), headerToken.getBytes())) {
                res.sendError(403, "CSRF token mismatch");
                return;
            }
        }
        chain.doFilter(req, res);
    }
    private static String readCookie(HttpServletRequest r, String name) {
        if (r.getCookies() == null) return null;
        for (Cookie c : r.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
```

In real Spring apps, use the built-in `http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))` rather than rolling your own — but this shows the mechanism. Note: the double-submit pattern relies on the same-origin policy preventing an attacker from *reading* the cookie to copy it into the header.

---

## 6. Implementation concerns & best practices

### 6.1 Sign the *right* thing (canonicalization correctness)

- **Always HMAC the raw received bytes** for the body. Re-serializing parsed JSON changes byte order/whitespace and breaks verification. Capture raw bytes before parsing.
- **Agree on canonicalization explicitly:** units of timestamp (s vs ms), query-param ordering, URL encoding, trailing slashes, case of method, header inclusion. Document it; mismatches are the dominant cause of "valid request rejected."
- **Sign everything the server trusts for routing/authorization:** method, path, and any header you act on. Signing only the body lets attackers replay a body against a different path or method.
- **Sign the anti-replay fields** (timestamp, nonce). An unsigned timestamp is forgeable.

### 6.2 Concurrency / correctness

- **Atomic claim-and-store** for nonces/idempotency keys (`SET NX EX`, DB unique constraint, DynamoDB conditional put). A read-then-write has a TOCTOU race that lets two concurrent duplicates both pass.
- **Idempotency must be transactional with the effect** for money/inventory. If you store the idempotency key in Redis but the DB write fails, you've "remembered" an action you didn't do.
- **Clock skew:** the timestamp window must absorb skew between sender and receiver. Run NTP. A 5-minute window tolerates typical skew; too tight → false rejects, too loose → larger replay surface.

### 6.3 Security hardening

- **Constant-time compare** for every secret/MAC comparison (`MessageDigest.isEqual`). Never `equals`/`==`.
- **CSPRNG** (`SecureRandom`) for nonces, keys, CSRF tokens. `Random`/`Math.random` are predictable and forgeable.
- **Secrets in a manager** (Vault, AWS Secrets Manager, KMS), not in code/config/repos. Rotate them.
- **Reject by default:** missing header, unknown key id, malformed signature, stale timestamp → reject. Fail closed.
- **Don't leak detail in errors.** "Invalid request" beats "signature byte 7 wrong." Verbose errors aid forgery.
- **Keep MAC ≠ encryption straight:** a MAC gives integrity+authenticity, *not* confidentiality. Keep TLS for confidentiality. If you need both with encryption, use **authenticated encryption (AEAD)** like AES-GCM or ChaCha20-Poly1305 — and **encrypt-then-MAC** order if composing manually.
- **Key separation:** use distinct secrets for distinct purposes (signing vs cookie vs URL); don't reuse one master key everywhere.

### 6.4 Observability

- **Metrics:** counters for verify-pass, verify-fail (by reason: bad-sig / stale / replay / unknown-key), idempotency-hit. A spike in bad-sig or replay is an attack signal or a rotation bug.
- **Structured logs** for rejections (key id, reason, source IP) — *never* log the secret, the full signature, or the raw body if sensitive.
- **Alerting** on sustained verification failures and on clock-skew rejection spikes (usually an NTP/deploy problem).
- **Tracing** the verify step so latency from the nonce store is visible.

### 6.5 Cost & performance

- HMAC-SHA256 is cheap (microseconds, hundreds of MB/s/core). It is **not** your bottleneck; the **nonce/idempotency store round-trip** is. Co-locate Redis, use connection pooling, set sensible TTLs.
- Avoid recomputing or re-reading the body multiple times; buffer once.
- For very high throughput, batch or pipeline Redis claims where semantics allow.

### 6.6 Testability

- Inject a **`Clock`** so you can test stale-timestamp and window-edge behavior deterministically.
- Inject the **secret resolver** and **nonce store** as interfaces; use fakes in tests.
- Golden tests with **known vectors**: fixed secret + fixed body → fixed expected signature (catches canonicalization regressions). Use provider-published test vectors where available (Stripe/GitHub docs include examples).
- Test the negative paths: tampered body, wrong path, replayed nonce, expired timestamp, malformed header, unknown key id.

### 6.7 Anti-patterns (do not do these)

| Anti-pattern | Why it's wrong | Fix |
|---|---|---|
| `sig.equals(expected)` | Timing side-channel | `MessageDigest.isEqual` |
| Signing parsed-then-reserialized body | Bytes differ → MAC fails or, worse, you verify the *wrong* bytes | HMAC raw received bytes |
| Signing only the body | Replay against other path/method | Sign method+path+ts+nonce+body |
| Timestamp checked but not signed | Attacker rewrites timestamp | Sign the timestamp |
| No timestamp/nonce at all | Unlimited replay | Add window + dedup |
| In-memory nonce set behind a load balancer | Replay hits a different instance | Shared atomic store (Redis) |
| `Math.random()`/`Random` for nonces/keys | Predictable | `SecureRandom` |
| Verifying *after* parsing/executing | Attacker-controlled bytes already hit your parser | Verify before deserialize |
| Logging secrets/signatures/raw bodies | Leaks the very thing you protect | Redact |
| MD5/SHA-1 for new MAC designs | Legacy/avoidable | HMAC-SHA256 |
| Disabling CSRF globally to "fix" an SPA 403 | Opens CSRF on cookie endpoints | Use token repo / SameSite, keep stateless APIs token-auth |
| Treating a MAC as encryption | Body still readable | Add TLS / AEAD |

---

## 7. Advanced topics & deep internals

### 7.1 Length-extension, revisited

The reason HMAC exists (vs `H(key||msg)`) is the **length-extension attack** on Merkle–Damgård hashes. These hashes process input in blocks, carrying internal state forward; the final state *is* the digest. Knowing `H(key||msg)` and `len(key)`, an attacker resumes the computation from that state and appends data, producing `H(key||msg||padding||extra)` — a valid digest for a longer message, with no key knowledge. HMAC's outer hash over the inner hash hides the resumable state. (SHA-3/BLAKE2 aren't Merkle–Damgård and aren't vulnerable, hence KMAC/keyed-BLAKE2.)

### 7.2 Timing attacks in depth

A timing attack exploits data-dependent execution time. Early-exit byte comparison leaks the index of first mismatch; with enough timing samples (averaging out noise), an attacker recovers the correct signature byte-by-byte — turning a 2^256 guess into a few thousand requests *per byte*. Constant-time comparison removes the data dependence. Subtleties: **length difference can still leak** (so many implementations also keep length constant or hash both sides first); JIT/branch prediction can reintroduce variance — `MessageDigest.isEqual`'s implementation is written to avoid early exit. Over a network, jitter masks small differences, but don't rely on the network as your defense.

### 7.3 Key rotation without downtime

Rotating a shared secret is tricky because both sides must switch atomically — impossible in a distributed system. Solutions:

- **Overlapping secrets:** accept multiple valid secrets during a window. Verify against each; success on any passes. Stripe sends *multiple* `v1=` values during rotation; GitHub lets you have a transition period. After all senders move to the new secret, retire the old.
- **Key IDs (`kid`):** include a key identifier in the signed request so the verifier picks the right secret deterministically (no "try all"). This is the cleanest pattern and what JWT's `kid` header does.
- **Rotation cadence:** rotate on a schedule (e.g., 90 days) and immediately on suspected compromise.

### 7.4 Replay windows vs exact dedup — choosing

- **Timestamp window only:** stateless, cheap, but allows replay *within* the window. Acceptable when the action is naturally idempotent or low-stakes.
- **Window + nonce dedup:** exact one-time within the window; needs a shared store. The window bounds how long you must remember nonces.
- **Idempotency key (store result):** for client retries; accepts duplicates but neutralizes them. Combine all three for high-stakes mutations.

### 7.5 AWS SigV4 (a production-grade canonicalization example)

AWS Signature Version 4 is worth studying as the gold standard of "sign the right thing." It builds a **canonical request** = HTTP method + canonical URI + canonical query string (sorted, encoded) + canonical headers (lowercased, sorted) + signed-headers list + hex(SHA-256(payload)). Then a **string to sign** = algorithm + timestamp + credential scope (date/region/service) + hex(SHA-256(canonical request)). The signing key is *derived* via a chain of HMACs (`HMAC(HMAC(HMAC(HMAC("AWS4"+secret, date), region), service), "aws4_request")`) — so a leaked daily/regional key can't sign for other dates/regions/services. The signature is `HMAC(signingKey, stringToSign)`. Takeaways: scope-limited derived keys, strict canonicalization, payload hashing, and a `x-amz-date` inside the signed scope for freshness.

### 7.6 Webhook idempotency at scale

Providers retry on any non-2xx or timeout, and may even deliver out of order or more than once on success. Therefore: **ACK quickly** (validate signature synchronously, enqueue, return 200), **dedup by provider event id** (Stripe `event.id`, GitHub `X-GitHub-Delivery`), and **make handlers idempotent** by effect (upsert by event id). Process out-of-order tolerant where possible (use event timestamps / versions, not arrival order).

### 7.7 CSRF mechanics in depth

- **Synchronizer token pattern:** server issues a random token tied to the session, embeds it in forms; state-changing requests must echo it. Attacker can't read it (same-origin policy), so can't forge.
- **Double-submit cookie:** token in a cookie *and* a header/form field; server checks they match. Stateless (no server storage) but relies on the attacker being unable to set/read the cookie cross-site.
- **`SameSite` cookies:** `Lax` (modern browser default) blocks cookies on cross-site *subrequests* (e.g., a POST from another site) while allowing top-level GET navigations; `Strict` blocks even those; `None` (requires `Secure`) sends always. `SameSite=Lax` mitigates most CSRF but isn't a complete substitute for tokens (older browsers, GET-based state changes, same-site subdomains).
- **Origin/Referer checks:** validate `Origin`/`Referer` matches your site for state-changing requests — a cheap defense-in-depth layer.
- **Why APIs with token auth (Authorization header) are immune:** CSRF relies on *ambient/automatic* credentials (cookies). A bearer token in a header is *not* auto-attached by the browser cross-site, so pure header-token APIs don't need CSRF tokens (which is why Spring disables CSRF for stateless APIs).

### 7.8 Message authentication vs encryption (the precise distinctions)

| Property | HMAC (MAC) | TLS / AES-GCM (encryption) | Asymmetric signature |
|---|---|---|---|
| Confidentiality | No | Yes | No |
| Integrity | Yes | Yes (AEAD) | Yes |
| Authenticity | Yes (shared key) | Channel-level | Yes (private key) |
| Non-repudiation | No | No | Yes |
| Key model | Shared secret | Session keys / certs | Public/private |
| Replay protection | Not by itself | TLS: per-connection only | Not by itself |

**AEAD (Authenticated Encryption with Associated Data)** — modes like AES-GCM and ChaCha20-Poly1305 — give confidentiality + integrity in one primitive, and let you bind unencrypted "associated data" (e.g., headers) into the integrity check. If you find yourself manually combining encryption and a MAC, prefer AEAD; if you must compose, use **encrypt-then-MAC** (never MAC-then-encrypt or encrypt-and-MAC, which have known weaknesses).

---

## 8. Tradeoffs & decision frameworks

### 8.1 HMAC vs asymmetric signature

| Dimension | HMAC (shared secret) | Asymmetric (Ed25519/ECDSA/RSA) |
|---|---|---|
| Speed | Very fast | Slower (esp. RSA) |
| Key distribution | Must share & protect secret on both sides | Publish public key freely |
| Non-repudiation | No | Yes |
| Verifier can forge | Yes | No |
| Operational complexity | Low | Higher (PKI, rotation) |
| Best for | Two trusted parties, webhooks, S2S | Many verifiers, audit/legal, public verifiability |

**Use HMAC when** both parties share a secret and verifiers are trusted (the common webhook/S2S case). **Use asymmetric when** you need non-repudiation, the verifier set is large/untrusted, or you don't want verifiers able to forge.

### 8.2 Replay defense selection

| Need | Choose |
|---|---|
| Cheap, stateless, low-stakes | Timestamp window only |
| Exact one-time within window | Window + nonce + shared atomic store |
| Safe client retries | Idempotency key (store result) |
| Money / inventory mutation | Idempotency key in DB unique constraint, transactional + signature + timestamp |

### 8.3 CSRF defense selection

| Context | Choose |
|---|---|
| Server-rendered app, session cookies | Synchronizer token (framework default) + `SameSite=Lax` |
| SPA with cookie auth | Double-submit cookie + `SameSite` + Origin check |
| Stateless API with bearer token in header | No CSRF token needed (not cookie-ambient); ensure no cookie auth fallback |

### 8.4 Build vs use SDK

Always prefer a **vendor-verified helper** (`Stripe.Webhook.constructEvent`) over hand-rolling. Hand-roll only when no helper exists (GitHub Java) or you're designing your own scheme — and then copy the canonical patterns above. Custom crypto code is a liability; verification logic especially.

---

## 9. Failure modes & debugging

### 9.1 "Signatures don't match" (the classic)

Most common causes, in order:
1. **Body re-serialization** — you HMAC'd a parsed/re-encoded body, not raw bytes. **Fix:** capture raw bytes (`@RequestBody byte[]`, or a buffering filter).
2. **Encoding/charset mismatch** — UTF-8 vs platform default; trailing newline; gzip not undone before hashing. **Fix:** pin UTF-8, hash exactly what arrived (after transfer-decoding).
3. **Wrong canonical string** — timestamp units, query ordering, header casing, separators. **Fix:** print both sides' canonical string in a debug build and diff.
4. **Wrong secret / wrong environment** — using test secret against live, or stale secret post-rotation. **Fix:** check key id, secrets manager version.
5. **Hex vs base64 mismatch** — provider sends hex, you decode base64 (or vice versa). **Fix:** match the provider's encoding.

**Debugging tooling:** log (in a non-prod build) the raw body length + sha256, the canonical string, expected vs provided signature side by side. Use `openssl dgst -sha256 -hmac "<secret>"` on the captured raw body to independently compute the expected value:
```
printf '%s' "$RAW_BODY" | openssl dgst -sha256 -hmac "whsec_xxx"
```
Compare with what your code produces. Mismatch in length usually means body capture is wrong.

### 9.2 Stale-timestamp / clock-skew rejections

Symptom: intermittent or post-deploy spike in "stale" rejections. Cause: NTP drift, a VM with a bad clock, or a too-tight tolerance. **Diagnose:** compare `now` on sender vs receiver; check `timedatectl`/NTP status. **Fix:** ensure NTP, set tolerance ≥ realistic skew (5 min typical).

### 9.3 Replays succeeding (dedup failing)

Symptom: duplicate effects (double charges, doubled processing). Causes:
- In-memory nonce store behind a load balancer (replay hits another instance).
- Non-atomic check-then-store race.
- TTL too short (nonce evicted before the timestamp window closes).
- Idempotency key stored in Redis but business write done separately and Redis lost it.
**Fix:** shared atomic store; `SET NX EX`; TTL ≥ window + skew; transactional idempotency for high-stakes.

### 9.4 Timing-attack exposure

Symptom (rare to "see"): security review flags `equals`/`==` on signatures. **Fix:** `MessageDigest.isEqual`. Add a static-analysis rule to catch non-constant-time comparisons of secrets.

### 9.5 Webhook duplicate storms

Symptom: provider redelivers because your handler is slow/timed out, multiplying load. **Fix:** ACK in <~1–2 s (verify + enqueue + 200), process async, dedup by event id, make handlers idempotent.

### 9.6 Real-world incident patterns

- **Replay drains:** APIs that signed requests but never enforced a timestamp/nonce — observed-and-replayed transfers. The lesson that made timestamp tolerance standard.
- **Webhook spoofing:** endpoints that skipped signature verification ("it's an internal URL") got hit by forged events once the URL leaked (URLs leak via logs, browser history, referrers). Always verify.
- **CSRF on password/email change:** classic exploited bugs where a state-changing endpoint trusted the session cookie with no CSRF token; a hidden auto-submitting form on a malicious page changed victims' emails. Fixed by tokens + `SameSite`.
- **Timing-attack PoCs:** academic and bug-bounty demonstrations recovering MAC bytes from naive comparisons over LAN — the reason constant-time compare is non-negotiable.

---

## 10. Interview drill

**Q1. What does an HMAC actually guarantee, and what does it not?**
*Model answer:* It guarantees **integrity** (the message wasn't altered) and **authenticity** (it was produced by a holder of the shared secret). It does **not** provide confidentiality (body is still readable), non-repudiation (both parties share the key, so either could have made it), or replay protection by itself.
- *Follow-up: How would you add confidentiality?* TLS for the channel, or AEAD (AES-GCM) if encrypting the payload itself; if composing, encrypt-then-MAC.
- *Follow-up: How to get non-repudiation?* Switch to asymmetric signatures (Ed25519/ECDSA) — only the private key holder can sign.
- *Follow-up: Why HMAC instead of `H(key||msg)`?* Length-extension attack on Merkle–Damgård hashes; HMAC's nested construction defeats it.

**Q2. Walk me through verifying a Stripe webhook.**
*Model answer:* Read the raw body bytes (not parsed JSON); parse `Stripe-Signature` for `t` and `v1`; reject if `|now − t| > 300s`; compute `HMAC-SHA256(whsec, "t.rawBody")`; constant-time compare against `v1`; dedup by `event.id`; ACK 200 fast and process async.
- *Follow-up: Why is the timestamp inside the signed string?* So it can't be rewritten without breaking the signature, which is what makes the freshness check meaningful.
- *Follow-up: Why raw bytes?* Re-serialization changes byte order/whitespace and breaks the MAC.
- *Follow-up: Why multiple `v1`?* Secret rotation — accept any matching value during the overlap.

**Q3. What's a replay attack and how do you defend?**
*Model answer:* Capturing a valid request and re-sending it to repeat the effect; the signature is genuine. Defenses: signed timestamp + tolerance window (bounds freshness), nonce + atomic dedup store (exact one-time), and/or idempotency keys (neutralize duplicates).
- *Follow-up: Why both timestamp and nonce?* Timestamp bounds how long you must store nonces; nonce gives exactness within that window.
- *Follow-up: Where do you store nonces in a multi-instance service?* A shared atomic store (Redis `SET NX EX`); in-memory fails behind a load balancer.

**Q4. Why must signature comparison be constant-time? (senior-signal)**
*Model answer:* Early-exit comparison leaks the first-mismatch index via timing, enabling byte-by-byte forgery. Constant-time comparison removes the data-dependent timing. In Java, `MessageDigest.isEqual`.
- *Follow-up: Does the network not mask this?* Jitter raises the sample count needed but doesn't eliminate the leak; don't rely on it.
- *Follow-up: What else can leak besides byte values?* Length differences; hash both sides or check length carefully.

**Q5. Idempotency key vs nonce-based replay protection — when each? (senior-signal)**
*Model answer:* Nonce dedup **rejects** repeats — good against attacker replays. Idempotency keys **accept** repeats but return the cached result — good for legitimate client retries after timeouts. High-stakes mutations combine signature + timestamp + idempotency-in-DB.
- *Follow-up: Where do you store the idempotency key for a payment?* In the same DB transaction as the effect (unique constraint), so claim and effect commit atomically.
- *Follow-up: What if two identical requests race?* Atomic claim; the loser either gets the cached result or a retry-later (409/425) while the first finishes.

**Q6. What should you actually sign in a request, and why? (senior-signal)**
*Model answer:* Method, path (+canonical query), timestamp, nonce, and the body (raw bytes or its hash) — everything the server trusts for routing, authorization, and freshness. Signing only the body permits replay against a different path/method; not signing the timestamp lets it be forged.
- *Follow-up: How do you make sender and receiver agree?* A documented canonicalization spec (units, ordering, encoding); golden-vector tests.
- *Follow-up: What breaks if you sign re-serialized JSON?* Byte differences → verification fails or you authenticate the wrong bytes.

**Q7. How is CSRF different from these, and how do you prevent it?**
*Model answer:* CSRF abuses *ambient cookie credentials*: the browser auto-sends cookies on cross-site requests, so a malicious page can trigger authenticated state changes the user didn't intend. Prevent with anti-CSRF tokens (synchronizer or double-submit), `SameSite` cookies, and Origin checks. Header-token (bearer) APIs aren't cookie-ambient and don't need CSRF tokens.
- *Follow-up: Why doesn't HMAC request signing solve CSRF?* The browser, not the attacker, holds the cookie/credential; the request is "authentic." CSRF is an intent/origin problem.
- *Follow-up: Is `SameSite=Lax` enough alone?* Mostly mitigates but not complete (older browsers, same-site subdomains, GET state changes); keep tokens for defense-in-depth.

**Q8. MAC vs encryption vs signature — pick for a webhook and justify. (senior-signal)**
*Model answer:* HMAC: you and the provider share a secret, verifiers are trusted, and you need integrity+authenticity cheaply — HMAC-SHA256 is correct. Encryption (TLS) still covers confidentiality on the wire. Asymmetric would add non-repudiation you don't need and key-distribution overhead.
- *Follow-up: When would the provider choose asymmetric?* If many independent verifiers shouldn't be able to forge, or to publish a rotating public key (some do for advanced setups).

**Q9. How do you rotate a webhook signing secret with zero downtime?**
*Model answer:* Overlapping secrets — accept old and new during a window (try each), or use a key id to select deterministically; migrate senders; retire the old secret.
- *Follow-up: How does AWS SigV4 limit blast radius?* Derived, scope-limited signing keys per date/region/service.

**Q10. What do you ACK and when, for webhooks, and why?**
*Model answer:* Verify signature + timestamp synchronously, enqueue the event, return 200 within ~1–2 s; process asynchronously and idempotently. Slow synchronous processing causes timeouts → provider retries → duplicate storms.
- *Follow-up: How do you dedup retries?* By provider event/delivery id with an atomic store; idempotent handlers by effect.

**Q11. Your verification suddenly rejects everything after a deploy. Diagnose. (senior-signal)**
*Model answer:* Check, in order: secret/env mismatch (new deploy pulled wrong secret/key id), clock skew (NTP) causing stale-timestamp rejects, body-capture change (a new framework binding parsing the body), and encoding (charset/gzip). Use `openssl dgst -sha256 -hmac` on a captured raw body to compute the expected signature independently and diff canonical strings.

**Q12. What's the difference between integrity and confidentiality, concretely?**
*Model answer:* Integrity = nobody changed it (and you can detect if they did) — HMAC/signature. Confidentiality = nobody can read it — encryption/TLS. A signed-but-unencrypted request is fully readable but tamper-evident; an encrypted-but-unauthenticated payload is unreadable but malleable. You usually want both.

---

## 11. Glossary

- **AEAD (Authenticated Encryption with Associated Data):** an encryption mode (AES-GCM, ChaCha20-Poly1305) giving confidentiality + integrity in one, optionally binding unencrypted associated data.
- **Asymmetric cryptography:** a public/private key pair; private signs, public verifies.
- **Avalanche effect:** a tiny input change flips ~half the hash output bits.
- **Base64 / hex:** text encodings of binary (signatures, keys). Pick the one your counterparty uses.
- **Bearer token:** a credential sent in the `Authorization` header; not auto-attached cross-site, so not CSRF-prone.
- **Canonicalization:** producing one deterministic byte representation both sides agree to sign.
- **Collision:** two distinct inputs with the same hash; practical for MD5/SHA-1.
- **Confidentiality:** the property that contents are unreadable to outsiders (encryption).
- **Constant-time comparison:** comparison that takes the same time regardless of where bytes differ; defeats timing attacks (`MessageDigest.isEqual`).
- **CSPRNG:** cryptographically secure pseudo-random generator (`SecureRandom`).
- **CSRF (Cross-Site Request Forgery):** abusing a browser's auto-sent cookies to perform unintended authenticated actions.
- **Digest / hash:** fixed-length output of a hash function.
- **Double-submit cookie:** CSRF defense placing the token in both a cookie and a header/field that must match.
- **Ed25519 / ECDSA / RSA:** asymmetric signature algorithms.
- **HMAC:** hash-based MAC (RFC 2104); the standard way to key a hash for authentication.
- **Idempotency:** repeating an operation has the same effect as doing it once.
- **Idempotency key:** client-supplied id letting the server make retries safe by caching the first outcome.
- **Integrity:** the message wasn't altered (and alteration is detectable).
- **Length-extension attack:** forging `H(key||msg||extra)` from `H(key||msg)` on Merkle–Damgård hashes; HMAC prevents it.
- **MAC (Message Authentication Code):** a keyed tag proving integrity + authenticity.
- **Merkle–Damgård:** the block-chaining construction of SHA-1/SHA-2; source of length-extension.
- **Nonce:** a number used once, for replay dedup.
- **Non-repudiation:** inability to deny authorship; provided by asymmetric signatures, not HMAC.
- **Preimage resistance:** can't find an input for a given hash.
- **Replay attack:** re-sending a captured valid message to repeat its effect.
- **SameSite cookie:** browser attribute controlling whether cookies are sent on cross-site requests (`Lax`/`Strict`/`None`).
- **SecureRandom:** Java's CSPRNG.
- **Shared secret:** the symmetric key both parties hold for HMAC.
- **SigV4:** AWS Signature Version 4 request-signing scheme.
- **Symmetric cryptography:** one shared key for both operations (HMAC).
- **Synchronizer token:** session-bound random CSRF token echoed by forms.
- **Timestamp tolerance / window:** allowed clock difference for accepting a request; bounds replay.
- **Timing attack:** exploiting data-dependent execution time to recover secrets.
- **TLS:** Transport Layer Security; encrypts and integrity-protects the channel, per hop.
- **TOCTOU:** time-of-check-to-time-of-use race; why claim-and-store must be atomic.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Goal:** make each message **self-authenticating** (HMAC) and **single-use/fresh** (timestamp + nonce/idempotency). TLS ≠ enough.
- **Default algo:** `HMAC-SHA256` (Java `"HmacSHA256"`). Avoid MD5/SHA-1 for new designs.
- **Sign:** `method \n path \n timestamp \n nonce \n sha256(rawBody)`. **Sign the anti-replay fields.**
- **Verify order:** raw bytes → parse header → resolve secret by key id → timestamp window (≈300 s) → atomic nonce/idempotency claim → recompute HMAC → **constant-time compare** (`MessageDigest.isEqual`) → execute → record.
- **Constant-time always.** Never `equals`/`==` on secrets. **`SecureRandom`** for nonces/keys.
- **Stripe:** `HMAC(whsec, "t.rawBody")`, header `Stripe-Signature: t=,v1=`, tolerance ≈300 s, dedup `event.id`. Use `Webhook.constructEvent`.
- **GitHub:** `HMAC-SHA256(secret, rawBody)`, header `X-Hub-Signature-256: sha256=`, dedup `X-GitHub-Delivery`.
- **Replay defenses:** timestamp window (cheap), nonce + atomic store (exact), idempotency key (retry-safe). Combine for money.
- **Distributed dedup:** Redis `SET key 1 NX EX <ttl>` — atomic claim; TTL ≥ window + skew. In-memory fails behind LB.
- **MAC ≠ encryption** (no confidentiality) and **≠ non-repudiation** (shared key). Use TLS/AEAD for secrecy, asymmetric for non-repudiation.
- **CSRF:** cookie-ambient attack → tokens + `SameSite=Lax` + Origin check. Bearer-token APIs are immune.
- **Webhooks:** verify sync, ACK 200 fast (<~2 s), process async + idempotent; expect duplicates/out-of-order.
- **Rotation:** overlapping secrets or `kid` selection.
- **Top bugs:** re-serialized body, encoding mismatch, unsigned timestamp, signing only the body, non-atomic/in-memory dedup, `equals` comparison.

### 12.2 Self-test (no answers — recall practice)

1. Write the verification step order for an incoming signed webhook, and explain why each step precedes the next.
2. Exactly which fields should be inside the signed string for a state-changing API request, and what attack does each one prevent?
3. Explain the length-extension attack and how HMAC's construction defeats it.
4. You run three instances of a service behind a load balancer. Design the nonce store so replays are impossible, and state the TTL relative to your timestamp window. What breaks if you skip atomicity?
5. Contrast HMAC, AES-GCM, and Ed25519 across confidentiality, integrity, authenticity, and non-repudiation — and pick one for: (a) a webhook you receive, (b) an audit log entry whose author must be provable to a third party, (c) a payload that must be unreadable on an internal queue.
6. A teammate "fixed" intermittent webhook failures by widening the timestamp tolerance from 5 minutes to 2 hours. What did they trade away, and what's the correct root-cause fix?
7. Why is a bearer-token JSON API not vulnerable to CSRF while a cookie-session form app is, and what would reintroduce CSRF risk into the API?

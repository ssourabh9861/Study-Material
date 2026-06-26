# Secrets Management & Encryption

> An exhaustive engineering-handbook chapter for senior Java/JVM backend developers. From first principles to deep internals: encryption at rest/in transit/in use, symmetric vs asymmetric crypto, KMS and envelope encryption, HashiCorp Vault, cloud secret managers, password hashing, TLS certificate management, tokenization, and secrets-leak detection.

---

## 1. Overview & where it fits

**What this chapter covers.** Two intertwined disciplines:

1. **Encryption** — transforming data so that only parties holding a key can read it. This protects *confidentiality* (and, with the right modes, *integrity* and *authenticity*).
2. **Secrets management** — the operational practice of generating, storing, distributing, rotating, auditing, and revoking the *credentials and keys* your systems need (database passwords, API tokens, TLS private keys, encryption keys themselves).

The two are inseparable: encryption is only as strong as the protection of its keys, and a "secret" is frequently a key. The hardest problem in cryptography is almost never the math — it is **key management**.

**The problem it solves.** Backend systems handle data and identity that must not leak: customer PII (Personally Identifiable Information — names, emails, government IDs), payment card data, authentication credentials, internal service-to-service tokens, and the keys that protect all of the above. You face three categories of threat:

- **Data theft at rest** — an attacker copies a database dump, a backup tape, a disk image, or an S3 bucket.
- **Data interception in transit** — an attacker on the network reads or tampers with bytes flowing between client and server, or service to service.
- **Credential compromise** — a leaked password, an API key committed to Git, an over-privileged token that never expires.

**When you reach for it.** Always, in some form. The questions are *how much* and *which mechanism*:

- Storing user passwords? → password **hashing** (Argon2/bcrypt/scrypt), never encryption, never plain hashing.
- Storing data you must later read back (e.g., a customer's bank account number for payouts)? → **encryption at rest** with a managed key.
- Sending data over a network? → **TLS** (encryption in transit).
- Need a per-environment database password your app reads at boot? → a **secrets manager** (Vault, AWS Secrets Manager, etc.).
- Want short-lived, automatically-rotated DB credentials? → **dynamic secrets** (Vault).
- Need to store card numbers but minimize PCI scope? → **tokenization**.

**One-paragraph mental model.** Treat every secret as *radioactive*: it should exist in as few places as possible, for as short a time as possible, accessible to as few principals as possible, and every access should be logged. Encryption shrinks the blast radius of stolen *data*; secrets management shrinks the blast radius of stolen *credentials and keys*. The unifying technique that ties them together is **envelope encryption** — you encrypt data with a cheap, local **data key**, then encrypt that data key with a centrally-managed, rarely-exposed **master key** that lives in a hardware-backed vault. Master that one idea and most of the field falls into place.

---

## 2. Foundations from first principles

We build vocabulary deliberately. Skim if you know it; every term is defined the first time it appears.

### 2.1 Confidentiality, integrity, authenticity (and what crypto gives you)

- **Confidentiality** — only authorized parties can read the data. (Encryption gives you this.)
- **Integrity** — the data has not been altered. (A *MAC* or *signature* gives you this; raw encryption alone does *not*.)
- **Authenticity** — the data genuinely came from who you think. (Signatures and authenticated encryption give you this.)
- **Non-repudiation** — the sender cannot later deny having sent it. (Only *asymmetric* signatures give you this; a shared-key MAC does not, because either party could have produced it.)

> **MAC (Message Authentication Code).** A short tag computed from the message plus a secret key. Anyone with the key can recompute the tag to verify the message wasn't tampered with. Example: **HMAC-SHA256**. A MAC proves integrity + authenticity *among parties who share the key*, but not non-repudiation.

### 2.2 Plaintext, ciphertext, keys, and Kerckhoffs's principle

- **Plaintext** — the readable original (`"Hello"`, a JSON blob, a file).
- **Ciphertext** — the scrambled output of encryption.
- **Key** — the secret parameter to the cipher. Same plaintext + different key = different ciphertext.
- **Cipher / algorithm** — the public, well-studied transformation (AES, RSA, ChaCha20).

> **Kerckhoffs's principle.** A cryptosystem must be secure even if everything about it is public *except the key*. Practical corollary: **never roll your own crypto, and never rely on a secret algorithm.** Security must rest on the key, which is exactly why key management dominates.

### 2.3 Symmetric vs asymmetric cryptography

This is the central fork.

**Symmetric encryption** — *one* key encrypts and decrypts. Fast (gigabytes/sec with hardware acceleration), used for *bulk data*. The problem: both parties need the same key, so you have a **key distribution problem**.

- Dominant algorithm: **AES (Advanced Encryption Standard)**, a *block cipher* operating on 128-bit blocks, with key sizes 128/192/256 bits.
- Stream-cipher alternative: **ChaCha20**, fast in software without special CPU instructions (good on mobile/ARM).

> **Block cipher.** Encrypts fixed-size blocks (AES = 16 bytes). To encrypt arbitrary-length data you need a **mode of operation** that chains blocks together (see §2.5).
>
> **Stream cipher.** Generates a pseudorandom keystream XORed with the plaintext byte-by-byte; no padding needed.

**Asymmetric (public-key) encryption** — a *key pair*: a **public key** (shareable) and a **private key** (secret). Data encrypted with the public key can only be decrypted with the private key, and vice versa for signatures. Solves key distribution (you can publish your public key) but is *slow* and size-limited (RSA can only encrypt data smaller than its key).

- **RSA** — based on the difficulty of factoring large numbers. Common key sizes 2048/3072/4096 bits. Used for key exchange, digital signatures, certificates.
- **ECC (Elliptic Curve Cryptography)** — same security as RSA with much smaller keys (a 256-bit ECC key ≈ a 3072-bit RSA key). Curves: **P-256** (NIST), **Curve25519** (used by `Ed25519` signatures and `X25519` key exchange). Faster, smaller, increasingly the default.

**The standard hybrid pattern (this is how the real world works):** use *asymmetric* crypto to securely establish or exchange a *symmetric* key, then use the fast *symmetric* key for the actual bulk data. TLS does exactly this. So does PGP. So does envelope encryption. Asymmetric = the handshake; symmetric = the workload.

| Property | Symmetric (AES) | Asymmetric (RSA/ECC) |
|---|---|---|
| Keys | One shared secret | Public + private pair |
| Speed | Very fast (GB/s) | Slow (KB–MB/s) |
| Data size | Any (with a mode) | ≤ key size (RSA) |
| Use case | Bulk data, at rest, in transit payload | Key exchange, signatures, certificates |
| Key distribution | Hard (must share secret) | Easy (publish public key) |
| Non-repudiation | No (shared key) | Yes (signatures) |

### 2.4 Encryption at rest vs in transit vs in use

These describe *the state of the data* when protected.

- **At rest** — data sitting on disk: databases, files, backups, object storage, message-queue persistence. Threat: stolen disk/backup/dump. Mechanism: symmetric encryption with keys in a KMS; often transparent (TDE, encrypted volumes) or application-level.
- **In transit** — data moving over a network. Threat: eavesdropper, man-in-the-middle. Mechanism: **TLS** (HTTPS, gRPC-over-TLS, mTLS between services, database TLS).
- **In use** — data being processed in memory/CPU, in *plaintext* form. This is the hardest gap: traditionally data must be decrypted to compute on it. Mechanisms (emerging/specialized):
  - **Confidential computing / TEEs (Trusted Execution Environments)** — hardware enclaves (Intel SGX, AMD SEV-SNP, AWS Nitro Enclaves) that encrypt RAM and isolate a process so even the OS/hypervisor can't read it.
  - **Homomorphic encryption** — compute directly on ciphertext (still slow, narrow use; FHE = Fully Homomorphic Encryption).
  - **Secure multi-party computation (MPC)** — multiple parties jointly compute over private inputs without revealing them.

> **TEE (Trusted Execution Environment).** A CPU-enforced "secure room." Code and data inside are encrypted in memory and shielded from the host OS, hypervisor, and even a root user. You get a cryptographic *attestation* proving which code is running. Used for processing keys/PII where you don't trust the cloud operator.

Most backend work is "at rest" + "in transit." "In use" matters for the highest-sensitivity workloads (keys, regulated PII, multi-tenant trust boundaries).

### 2.5 Modes of operation, IVs/nonces, and AEAD

A block cipher needs a **mode** to handle real data. The critical distinction is *unauthenticated* vs *authenticated*.

- **ECB (Electronic Codebook)** — encrypts each block independently. **Never use it.** Identical plaintext blocks → identical ciphertext blocks, leaking structure (the infamous "ECB penguin" image). It provides no diffusion across blocks.
- **CBC (Cipher Block Chaining)** — each block is XORed with the previous ciphertext block before encryption. Requires an **IV** and **padding**; vulnerable to *padding-oracle* attacks if integrity isn't separately checked. Avoid unless you must.
- **CTR (Counter)** — turns the block cipher into a stream cipher by encrypting a counter. Fast, parallelizable, no padding. No integrity by itself.
- **GCM (Galois/Counter Mode)** — **CTR + a built-in authentication tag.** This is *AEAD*: you get confidentiality *and* integrity in one pass. **Default choice for symmetric encryption today: AES-256-GCM.**
- **ChaCha20-Poly1305** — AEAD pairing a stream cipher with the Poly1305 MAC. Default when AES hardware isn't available.

> **AEAD (Authenticated Encryption with Associated Data).** A cipher that simultaneously encrypts the plaintext *and* authenticates it, plus authenticates extra unencrypted "associated data" (AAD) — e.g., a record ID or version that must not be tampered with but doesn't need hiding. AES-GCM and ChaCha20-Poly1305 are AEADs. **Always prefer AEAD.** It eliminates an entire class of "encrypt-then-MAC done wrong" bugs.

> **IV (Initialization Vector) / nonce (number used once).** A non-secret value that makes each encryption unique so the same plaintext+key produces different ciphertext. **The cardinal GCM rule: never reuse a (key, nonce) pair.** Reusing a GCM nonce is catastrophic — it leaks the XOR of plaintexts *and* lets an attacker forge authentication tags. GCM nonces are 96 bits; generate them with a CSPRNG or a counter you guarantee is unique.

> **CSPRNG (Cryptographically Secure Pseudo-Random Number Generator).** A random source safe for keys/IVs/tokens. In Java use `java.security.SecureRandom` — **never** `java.util.Random` or `Math.random()` for anything security-relevant.

### 2.6 Hashing vs encryption vs encoding (don't confuse them)

- **Encoding** (Base64, hex, URL-encoding) — reversible, *no key*, *no security*. It's a format transform. Base64 is **not** encryption.
- **Hashing** — a one-way function: arbitrary input → fixed-size digest, *not reversible*, no key. Used for integrity (checksums), and — with special "slow" hashes — for password storage.
- **Encryption** — reversible *with a key*. Used when you need the data back.

> **Cryptographic hash function.** Deterministic, fixed-output, one-way, collision-resistant (hard to find two inputs with the same digest). Examples: **SHA-256, SHA-512, SHA-3, BLAKE2/BLAKE3**. **MD5 and SHA-1 are broken — never use them for security.** Note: general-purpose hashes (SHA-256) are *fast*, which is exactly why they're **wrong for passwords** (see §2.7).

### 2.7 Password storage: salts, peppers, and slow hashes

Passwords must be stored such that a database breach doesn't reveal them. Rules:

1. **Never store plaintext.** Obvious, still happens.
2. **Never encrypt passwords** (you'd have a key that decrypts them all; you never need to *read* a password, only verify it).
3. **Never use a fast hash** (SHA-256). GPUs compute billions of SHA-256/sec, so a stolen table of fast hashes is cracked offline en masse.
4. **Use a purpose-built, deliberately slow, memory-hard password hash:** **Argon2id** (modern default), **scrypt**, or **bcrypt** (battle-tested fallback).

> **Salt.** A unique random value per password, stored alongside the hash. It ensures two users with the same password get different hashes and defeats **rainbow tables** (precomputed hash→password lookups). Salts are *not secret*; they just need to be unique. Modern password hashes generate and embed the salt for you.

> **Pepper.** A *secret* value added to every password before hashing, stored separately from the database (e.g., in a KMS/HSM or app config), **not** in the same table as the hashes. If only the DB leaks, the pepper still protects the hashes. It's defense-in-depth on top of salt.

> **Memory-hard.** A function deliberately requiring lots of RAM to compute, which neutralizes the massive parallelism advantage of GPUs/ASICs (they have limited fast memory). Argon2 and scrypt are memory-hard; bcrypt is only mildly so.

> **Work factor / cost.** A tunable parameter making the hash slower as hardware improves. bcrypt's `cost` (e.g., 12 → 2¹² iterations); Argon2's time/memory/parallelism. Tune so a single hash takes ~100–500 ms on your hardware — slow enough to crush offline cracking, fast enough for login latency.

### 2.8 Key lifecycle and the core building blocks

Every key has a **lifecycle**: *generate → distribute → use → rotate → archive → destroy*. The systems that manage this lifecycle:

- **KMS (Key Management Service)** — a service (AWS KMS, GCP Cloud KMS, Azure Key Vault, HashiCorp Vault's transit engine) that creates, stores, and uses keys *without ever exporting them*; you send data/data-keys *to* it and it returns the cryptographic result. Backed by HSMs.
- **HSM (Hardware Security Module)** — a tamper-resistant hardware appliance that generates and stores keys in silicon; keys *never leave in plaintext*. Certified to standards like **FIPS 140-2/140-3**. KMS services run on HSM fleets.
- **Secrets manager** — stores *arbitrary* secrets (passwords, tokens, certs), with access control, rotation, and audit (Vault, AWS Secrets Manager, GCP Secret Manager, Azure Key Vault).
- **Envelope encryption** — the pattern (next section) that lets you encrypt unlimited data while keeping the master key inside the KMS/HSM.

> **FIPS 140-2/140-3.** US government standards certifying cryptographic modules. *Level 2* adds tamper-evidence; *Level 3* adds tamper-resistance and identity-based auth. Regulated industries often *require* FIPS-validated HSMs.

With this vocabulary in hand, we can now go deep on the mechanisms.

---

## 3. How it works internally

This is the heart of the chapter. We trace the internal workflows of the mechanisms you'll actually operate.

### 3.1 Envelope encryption: the master pattern

**Why not just call the KMS to encrypt all your data?** Because (a) KMS calls are network round-trips (latency, throughput limits, cost per call) and (b) KMS-resident keys often can't encrypt payloads larger than a few KB. So you don't encrypt *data* with the master key — you encrypt a *data key*.

**The actors:**

- **DEK (Data Encryption Key)** — a fresh symmetric key (e.g., AES-256) used to encrypt the *actual data*, locally, fast.
- **KEK (Key Encryption Key)** — the *master key*, living inside the KMS/HSM, never exported. Its only job: encrypt/decrypt DEKs.

**Encryption workflow (step by step):**

1. App asks KMS to **generate a data key** under a named KEK (e.g., AWS `GenerateDataKey`). KMS returns **two** things: the **plaintext DEK** and the **encrypted DEK** (the DEK wrapped/encrypted by the KEK — the "wrapped key" or "encrypted data key, EDK").
2. App encrypts the payload locally with the **plaintext DEK** using AES-256-GCM (fast, no further network calls).
3. App **zeroes/discards the plaintext DEK** from memory as soon as possible.
4. App stores the **ciphertext** *plus* the **encrypted DEK** together (and the nonce, the KEK id, the algorithm — together called the "envelope" or "encrypted message"). The plaintext DEK is gone.

**Decryption workflow:**

1. App reads ciphertext + encrypted DEK.
2. App sends the **encrypted DEK** to KMS to **decrypt** it (`Decrypt`). KMS uses the KEK (inside the HSM) to unwrap and returns the **plaintext DEK**.
3. App decrypts the payload locally with the plaintext DEK; verifies the GCM tag.
4. App discards the plaintext DEK.

**Why this is powerful:**

- The **KEK never leaves the HSM**; even a full app-server compromise can't exfiltrate the master key.
- You can **cache the DEK** in memory for a batch of records (one KMS call, many encryptions) → huge throughput win.
- **Rotation** is cheap: rotating the *KEK* only requires re-wrapping the (small) DEKs, not re-encrypting petabytes. (More in §3.4.)
- **Per-record / per-tenant DEKs** give you crypto-shredding: destroy one DEK and that record is permanently unreadable.

> **Crypto-shredding (crypto-erasure).** Securely "deleting" data by destroying the key that decrypts it, instead of overwriting the data itself. Essential for "right to be forgotten" (GDPR) on immutable/backup storage where physical deletion is impractical. Per-user DEKs make this clean: drop the user's DEK → all their data is irrecoverable.

```
                ┌─────────── KMS / HSM ───────────┐
                │   KEK (master key, never leaves) │
                └──────────────┬───────────────────┘
   GenerateDataKey             │   Decrypt(EDK) → DEK
        │                      │
        ▼                      ▲
   ┌─DEK(plain)─┐         ┌─EDK (wrapped DEK)─┐
   │            │ wrap    │                   │
   │  AES-GCM   ├────────►│  stored next to   │
   │  encrypt   │         │   ciphertext      │
   └────┬───────┘         └───────────────────┘
        ▼
   ciphertext + nonce + EDK + kek-id  ──► database / S3
```

### 3.2 AWS KMS internals (representative cloud KMS)

> **AWS KMS.** A regional, multi-tenant managed key service backed by FIPS 140-2 (some HSMs validated to L3) hardware. Keys are called **CMKs / KMS keys**; each has a **key policy** (who can use/manage it) and integrates with IAM.

Internal flow when you call `GenerateDataKey(KeyId, KeySpec=AES_256)`:

1. Your call is authenticated/authorized via **IAM + the key policy** (and optional **grants**). A request denied here never touches a key.
2. KMS's HSM fleet generates a fresh 256-bit DEK using hardware RNG.
3. The HSM encrypts that DEK under the specified KMS key (the KEK) → `CiphertextBlob` (the EDK).
4. KMS returns `{Plaintext: DEK, CiphertextBlob: EDK}` over TLS.
5. KMS writes a **CloudTrail** audit record of the operation (who, when, which key).

Notable internals/behaviors:

- **Key material never leaves the HSM in plaintext.** `Encrypt`/`Decrypt`/`GenerateDataKey` all execute inside the HSM boundary.
- **Encryption context** — an optional set of key-value pairs passed to `Encrypt`/`Decrypt`. It's AAD: not stored, but cryptographically bound to the ciphertext, so `Decrypt` *must* be given the identical context. Use it to bind ciphertext to a purpose/tenant (defense against ciphertext-swapping).
- **Automatic key rotation** — KMS can rotate the *backing key material* yearly (or on demand) while keeping the same key *id*. Old material is retained to decrypt old ciphertexts; new encryptions use new material. (This rotates the KEK, not your DEKs.)
- **Request limits** — KMS has per-region, per-key request-rate quotas (e.g., thousands of `Decrypt`/sec, varying by region/key type). High-throughput systems therefore **cache DEKs** rather than hitting KMS per operation. The **AWS Encryption SDK** does envelope encryption + DEK caching for you.
- **Multi-Region keys, key stores backed by CloudHSM, and ECC/RSA asymmetric KMS keys** exist for specific needs (signing, cross-region DR).

GCP Cloud KMS and Azure Key Vault follow the same conceptual model (key rings/keys, IAM, HSM protection level, rotation schedules, envelope via "wrap/unwrap").

### 3.3 HashiCorp Vault internals

> **HashiCorp Vault.** An open-source (and enterprise) secrets-management server. It stores secrets encrypted, gates access with policies and pluggable **auth methods**, generates **dynamic** short-lived secrets, and can act as an encryption-as-a-service via its **transit engine**. It's the de-facto standard for self-managed secrets management.

**The seal/unseal lifecycle and the barrier:**

1. Vault persists everything **encrypted** in a storage backend (Consul, Raft/integrated storage, etc.). The encryption layer is the **barrier**.
2. The barrier is protected by the **master key**, which itself is encrypted by the **root key**, ultimately protected by an **unseal mechanism**.
3. When Vault starts it is **sealed** — it has the ciphertext but not the key, so it can read nothing.
4. **Unsealing** reconstructs the master key. Classic mode uses **Shamir's Secret Sharing**: the key is split into *N* shares (key shares / "unseal keys"), and a threshold *K* of them must be provided to reassemble it (e.g., 3-of-5). No single operator can unseal alone.
5. Production typically uses **auto-unseal**: the root key is wrapped by a cloud KMS/HSM (KMS, Key Vault, transit) so Vault unseals automatically on restart without manual share entry.

> **Shamir's Secret Sharing.** A scheme that splits a secret into *N* pieces such that any *K* of them reconstruct it, but *K−1* reveal *nothing*. Based on the fact that *K* points uniquely determine a degree-(K−1) polynomial. Used by Vault for split-trust unsealing.

> **Consul / Raft.** Consul is a service-discovery + KV store HashiCorp originally used as Vault's storage. **Raft** is a consensus algorithm that keeps a replicated log consistent across a cluster of nodes by electing a leader and requiring a majority to commit each entry — Vault's "integrated storage" uses Raft so Vault can run HA without an external backend.

**Request flow inside Vault:**

1. Client **authenticates** via an auth method (token, AppRole, Kubernetes, AWS IAM, OIDC, TLS cert…) and receives a **Vault token**.

   > **AppRole.** A machine-friendly auth method: an app presents a `role_id` (like a username, can be baked in) plus a `secret_id` (like a password, delivered just-in-time/short-lived) to get a token. Solves the "secret-zero" bootstrap for non-human clients.

2. The token carries **policies** (HCL documents granting `path → capabilities` like `read`, `create`, `update`, `delete`, `list`). Vault checks the policy for the requested path.
3. Vault routes the path to the appropriate **secrets engine** (KV, database, PKI, transit, AWS, etc.) mounted at that path.
4. The engine returns the secret, often with a **lease**.

**Leases, renewal, revocation (the lifecycle that makes dynamic secrets work):**

> **Lease.** A time-bounded grant attached to a dynamic secret. Vault tracks every lease; when its **TTL (time-to-live)** expires (or you call revoke), Vault **automatically revokes** the underlying credential (e.g., drops the DB user). Leases can be **renewed** up to a **max TTL**.

**Dynamic secrets workflow (database engine example):**

1. You configure the DB secrets engine with an *admin* connection and a *role* defining the SQL to create/drop a user.
2. App authenticates and reads `database/creds/my-role`.
3. Vault connects to the DB **as admin**, runs the role's `CREATE USER ... GRANT ...` SQL with a random username/password and a TTL.
4. Vault returns those fresh credentials + a **lease**.
5. App uses them. When the lease expires/revokes, Vault runs the revocation SQL (`DROP USER`).
6. Result: **no long-lived shared DB password exists**; each app instance gets unique, short-lived, individually-revocable credentials. If one leaks, it dies in minutes and is traceable.

**Transit engine ("encryption as a service"):**

> **Transit engine.** Vault holds the keys and performs `encrypt`/`decrypt`/`sign`/`verify`/`hmac`/`rewrap` on data you send it — but **never stores your data**. Your app stays out of the key-handling business; Vault is your KMS. It supports **convergent encryption**, **datakey** generation (envelope), and **versioned keys** for rotation.

Transit encrypt flow: app POSTs base64 plaintext to `transit/encrypt/my-key`; Vault encrypts with the latest key version and returns `vault:v3:<base64 ciphertext>` (the `v3` records which key version was used, so rotation never breaks old ciphertexts). Decrypt reverses it; `rewrap` upgrades old ciphertext to the newest key version without exposing plaintext.

### 3.4 Key rotation: what actually happens

"Rotate the key" means different things depending on layer:

- **KEK rotation (KMS master key).** New backing material is created; the key *id* is stable. New DEKs are wrapped under the new material; old EDKs still decrypt because old material is retained. **Your data is not re-encrypted.** Optionally re-wrap DEKs to drop old material. Cheap.
- **DEK rotation.** Generate a new DEK, re-encrypt the data, store the new EDK. Expensive (touches the data) — done lazily (on next write) or via a background re-encrypt job. Per-record DEKs make this incremental.
- **Transit/versioned-key rotation (Vault).** `rotate` bumps the key version; new encryptions use the new version; old ciphertexts carry their version tag and still decrypt; `rewrap` migrates them forward.
- **Application/credential rotation.** Replace a DB password or API key. The hard part is **zero-downtime**: you need a window where *both* old and new are valid. Patterns: dual-secret (two active keys), or dynamic secrets (rotation is intrinsic).

> **Rotation period.** Common defaults: KMS automatic KEK rotation ≈ 1 year; high-value credentials 30–90 days; dynamic DB secrets minutes–hours. The right answer depends on blast radius and detection time, not a fixed number.

### 3.5 TLS handshake internals (encryption in transit)

> **TLS (Transport Layer Security).** The protocol behind HTTPS that gives you confidentiality, integrity, and server (optionally client) authentication on a network connection. SSL is its obsolete predecessor; only TLS 1.2 and **TLS 1.3** should be enabled today.

**TLS 1.3 handshake (the modern, faster one), step by step:**

1. **ClientHello** — client sends supported cipher suites, a key-share for (EC)DHE, and extensions (SNI = the hostname it wants).
2. **ServerHello** — server picks a cipher suite, sends its key-share. Both sides now derive a shared symmetric key via **(EC)DHE**.

   > **(EC)DHE — (Elliptic-Curve) Diffie-Hellman Ephemeral.** A key-agreement method where both parties combine their own private value with the other's public value to derive a shared secret *without ever transmitting it*. "Ephemeral" = a fresh key per session, which gives **forward secrecy**: stealing the server's long-term private key later does *not* let an attacker decrypt past recorded sessions.

3. Server sends its **certificate** (the public key + identity, signed by a CA) and a **CertificateVerify** (a signature with its private key proving it owns the cert).
4. Client validates the certificate **chain** up to a trusted **root CA**, checks the hostname, expiry, and revocation.
5. **Finished** messages confirm the handshake; from here the session uses fast **AEAD** symmetric encryption (AES-GCM / ChaCha20-Poly1305).

TLS 1.3 does this in **1 round-trip** (1-RTT), with **0-RTT** resumption available (at a small replay-risk cost). It removed legacy/insecure options (static RSA key exchange, CBC, RC4, MD5, SHA-1, renegotiation).

> **Certificate / X.509.** A signed document binding a *public key* to an *identity* (a domain name, an org). Signed by a **CA (Certificate Authority)**. Your trust store ships with root CAs; a server cert chains up to one of them. **mTLS (mutual TLS)** = the client *also* presents a certificate, so both sides authenticate — common for service-to-service auth in zero-trust meshes.

> **OCSP / CRL.** Mechanisms to check if a cert was revoked before its expiry. **CRL** = a published list of revoked certs; **OCSP** = an online query per cert; **OCSP stapling** = the server attaches a fresh signed "still valid" proof so the client needn't query the CA. Short-lived certs (ACME, see §3.6) reduce reliance on revocation.

### 3.6 Certificate issuance & ACME (TLS cert management)

> **ACME (Automatic Certificate Management Environment).** The protocol (used by **Let's Encrypt**) to automatically prove you control a domain and get a cert issued — no humans, no manual CSR emails.

Flow: client generates a key + **CSR (Certificate Signing Request)** → asks the CA for a challenge → proves control via **HTTP-01** (serve a token at `/.well-known/acme-challenge/...`) or **DNS-01** (publish a TXT record; required for wildcards) → CA validates and issues a typically **90-day** cert → client auto-renews ~30 days before expiry. Internal PKI (Vault PKI, AWS Private CA, cert-manager in Kubernetes) automates the same lifecycle for service certs.

> **cert-manager.** A Kubernetes controller that issues and renews certificates (from Let's Encrypt, Vault, internal CAs) as native resources, storing them in Secrets and rotating them automatically.

---

## 4. The complete toolkit

### 4.1 Java/JVM cryptography APIs (JCA/JCE)

> **JCA/JCE (Java Cryptography Architecture / Extension).** The provider-based framework in the JDK exposing crypto via abstract engine classes (`Cipher`, `MessageDigest`, `Signature`, `KeyGenerator`, etc.). Algorithms come from **providers** (default `SUN`/`SunJCE`/`SunEC`; you can add **BouncyCastle** for extras like Argon2/ChaCha20-Poly1305 on older JDKs).

| Class | Purpose | Key parameters / notes |
|---|---|---|
| `javax.crypto.Cipher` | Encrypt/decrypt | `getInstance("AES/GCM/NoPadding")`; `init(mode, key, GCMParameterSpec)`; `updateAAD`, `doFinal` |
| `javax.crypto.KeyGenerator` | Generate symmetric keys | `getInstance("AES")`, `init(256, SecureRandom)` |
| `java.security.KeyPairGenerator` | Generate RSA/EC key pairs | `getInstance("EC")`, `initialize(new ECGenParameterSpec("secp256r1"))` |
| `javax.crypto.spec.GCMParameterSpec` | GCM tag length + IV | `new GCMParameterSpec(128, iv)` — 128-bit tag |
| `javax.crypto.spec.SecretKeySpec` | Wrap raw key bytes | `new SecretKeySpec(bytes, "AES")` |
| `java.security.MessageDigest` | Hashing | `getInstance("SHA-256")` — *not for passwords* |
| `javax.crypto.Mac` | HMAC | `getInstance("HmacSHA256")` |
| `java.security.Signature` | Sign/verify | `getInstance("SHA256withECDSA")` / `Ed25519` (JDK 15+) |
| `java.security.SecureRandom` | CSPRNG | use `getInstanceStrong()` or default; *never* `Random` |
| `javax.crypto.SecretKeyFactory` | PBKDF2, password-based keys | `getInstance("PBKDF2WithHmacSHA256")` |
| `java.security.KeyStore` | Manage keys/certs/truststore | types: `PKCS12` (default), `JKS` (legacy), `PKCS11` (HSM) |
| `javax.net.ssl.SSLContext` | Configure TLS | `init(keyManagers, trustManagers, SecureRandom)` |

Cipher transformation string format: `"ALGORITHM/MODE/PADDING"`, e.g., `"AES/GCM/NoPadding"`, `"RSA/ECB/OAEPWithSHA-256AndMGF1Padding"` (use **OAEP** for RSA, never raw/PKCS1v1.5 for new code).

### 4.2 Password-hashing libraries (JVM)

| Library | Algorithm | Notes |
|---|---|---|
| Spring Security `Argon2PasswordEncoder` | Argon2id | recommended default; params: saltLen, hashLen, parallelism, memory(KB), iterations |
| Spring Security `BCryptPasswordEncoder` | bcrypt | `strength` (cost) default 10; bump to 12+ |
| Spring Security `SCryptPasswordEncoder` | scrypt | params N, r, p |
| Spring Security `DelegatingPasswordEncoder` | many | `{id}` prefix → supports migration between algorithms |
| BouncyCastle / `password4j` | Argon2/bcrypt/scrypt | when not on Spring |

### 4.3 Cloud KMS / Secrets Manager (CLI & SDK quick reference)

| Tool | Common commands |
|---|---|
| AWS KMS | `aws kms generate-data-key --key-id <id> --key-spec AES_256`; `aws kms encrypt`; `aws kms decrypt`; `aws kms create-key`; `aws kms enable-key-rotation` |
| AWS Secrets Manager | `aws secretsmanager get-secret-value --secret-id <name>`; `create-secret`; `rotate-secret`; `put-secret-value` |
| GCP Cloud KMS | `gcloud kms keys create`; `gcloud kms encrypt/decrypt`; `gcloud kms keys versions create` |
| GCP Secret Manager | `gcloud secrets create`; `gcloud secrets versions access latest --secret=<name>` |
| Azure Key Vault | `az keyvault key create`; `az keyvault secret set/show`; `az keyvault certificate create` |

### 4.4 HashiCorp Vault (CLI)

| Command | Purpose |
|---|---|
| `vault server -config=...` | start server |
| `vault operator init -key-shares=5 -key-threshold=3` | initialize, get unseal keys + root token |
| `vault operator unseal <share>` | provide an unseal share |
| `vault login -method=approle role_id=.. secret_id=..` | authenticate |
| `vault kv put secret/app db_pw=...` / `vault kv get secret/app` | static KV secrets |
| `vault read database/creds/my-role` | get dynamic DB creds (with lease) |
| `vault lease renew/revoke <lease_id>` | manage leases |
| `vault write transit/encrypt/my-key plaintext=$(base64 ...)` | encryption as a service |
| `vault write transit/keys/my-key/rotate` / `transit/rewrap/my-key` | key rotation |
| `vault policy write app-policy app.hcl` | define access policy |
| `vault audit enable file file_path=/var/log/vault_audit.log` | audit logging |

### 4.5 Secret-scanning / leak-detection tools

| Tool | What it does |
|---|---|
| `git-secrets` (AWS) | git **pre-commit hook**: blocks commits matching secret regexes |
| **gitleaks** | scans repos/history/CI for secrets; configurable rules; `gitleaks detect`/`protect` |
| **trufflehog** | scans + **verifies** found credentials against live APIs (reduces false positives) |
| **detect-secrets** (Yelp) | pre-commit + baseline file to manage known/allowlisted findings |
| GitHub **Secret Scanning / Push Protection** | platform-side detection; push protection blocks the push |
| **Semgrep**, **Checkov**, `tfsec` | broader IaC/code scanning incl. hardcoded secrets |

---

## 5. Code examples by use case

### 5.1 AES-256-GCM encrypt/decrypt (application-level, idiomatic Java)

```java
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.util.Arrays;

public final class AesGcm {
    private static final int GCM_NONCE_BYTES = 12;   // 96-bit nonce (GCM standard)
    private static final int GCM_TAG_BITS   = 128;   // 128-bit auth tag (max, recommended)
    private static final SecureRandom RNG   = new SecureRandom();

    /** Encrypts plaintext; output layout = [12-byte nonce][ciphertext+tag]. */
    public static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] aad) throws Exception {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        RNG.nextBytes(nonce);                          // fresh nonce EVERY time — never reuse (key,nonce)
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        if (aad != null) c.updateAAD(aad);             // bind associated data (e.g., record id) — authenticated, not encrypted
        byte[] ct = c.doFinal(plaintext);
        byte[] out = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ct, 0, out, nonce.length, ct.length);
        return out;                                    // prepend nonce so decrypt can recover it
    }

    public static byte[] decrypt(SecretKey key, byte[] blob, byte[] aad) throws Exception {
        byte[] nonce = Arrays.copyOfRange(blob, 0, GCM_NONCE_BYTES);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        if (aad != null) c.updateAAD(aad);
        // doFinal throws AEADBadTagException if tampered or wrong key — this is your integrity check
        return c.doFinal(blob, GCM_NONCE_BYTES, blob.length - GCM_NONCE_BYTES);
    }

    public static SecretKey newKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, RNG);                             // AES-256
        return kg.generateKey();
    }
}
```

Key points: AEAD means a tampered ciphertext throws on decrypt — don't add your own MAC. The nonce is public; prepend it. Never catch and ignore `AEADBadTagException`.

### 5.2 Envelope encryption with AWS KMS (production pattern)

```java
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import software.amazon.awssdk.core.SdkBytes;
import javax.crypto.spec.SecretKeySpec;
import java.util.Map;
import java.util.Arrays;

public class EnvelopeCrypto {
    private final KmsClient kms = KmsClient.create();
    private final String kekId;                        // ARN/alias of the KMS master key (KEK)

    public EnvelopeCrypto(String kekId) { this.kekId = kekId; }

    public Envelope encrypt(byte[] data, String tenantId) throws Exception {
        // encryption context = AAD bound into the wrapped DEK; Decrypt must supply the same map
        Map<String,String> ctx = Map.of("tenant", tenantId, "purpose", "pii");
        GenerateDataKeyResponse dk = kms.generateDataKey(b -> b
                .keyId(kekId).keySpec(DataKeySpec.AES_256).encryptionContext(ctx));

        byte[] plainDek = dk.plaintext().asByteArray();      // use locally, then wipe
        byte[] wrappedDek = dk.ciphertextBlob().asByteArray();// store this; cannot decrypt without KMS
        try {
            var key = new SecretKeySpec(plainDek, "AES");
            byte[] ciphertext = AesGcm.encrypt(key, data, tenantId.getBytes()); // §5.1
            return new Envelope(ciphertext, wrappedDek, ctx);
        } finally {
            Arrays.fill(plainDek, (byte) 0);                  // best-effort zeroization of key material
        }
    }

    public byte[] decrypt(Envelope env) throws Exception {
        DecryptResponse r = kms.decrypt(b -> b
                .ciphertextBlob(SdkBytes.fromByteArray(env.wrappedDek()))
                .encryptionContext(env.ctx()));               // must match the encrypt-time context
        byte[] plainDek = r.plaintext().asByteArray();
        try {
            var key = new SecretKeySpec(plainDek, "AES");
            return AesGcm.decrypt(key, env.ciphertext(), env.ctx().get("tenant").getBytes());
        } finally {
            Arrays.fill(plainDek, (byte) 0);
        }
    }
    public record Envelope(byte[] ciphertext, byte[] wrappedDek, Map<String,String> ctx) {}
}
```

For high throughput, replace direct calls with the **AWS Encryption SDK** + a **caching CMM** (cryptographic materials manager) so one `GenerateDataKey` serves many records within a TTL/usage budget.

### 5.3 Password hashing with Argon2 (Spring Security)

```java
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class Passwords {
    // saltLen=16, hashLen=32, parallelism=1, memory=1<<14 (16 MiB), iterations=3  (tune up on your hw)
    private static final PasswordEncoder enc =
        new Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);

    public static String hash(String raw) {
        return enc.encode(raw); // self-contained string embeds algo, params, salt, hash
    }
    public static boolean verify(String raw, String stored) {
        return enc.matches(raw, stored);
    }
    public static boolean needsRehash(String stored) {
        return enc.upgradeEncoding(stored); // true if stored params are weaker than current → rehash on next login
    }
}
```

Migration-friendly variant using `DelegatingPasswordEncoder` (recommended in real apps):

```java
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

// Produces hashes like {argon2}$argon2id$v=19$m=...; matches() routes by the {id} prefix,
// so you can verify old {bcrypt} hashes while encoding new ones with {argon2}, then upgrade on login.
PasswordEncoder enc = PasswordEncoderFactories.createDelegatingPasswordEncoder();
```

Adding a **pepper**: HMAC the password with a secret pepper (stored in KMS/Vault, *not* the DB) *before* passing it to the password hasher — `enc.encode(hmacSha256(pepper, raw))`. Rotating the pepper requires a versioned-pepper scheme.

### 5.4 Reading a secret from Vault at startup (AppRole + KV)

```java
// pseudocode-level; use spring-cloud-vault or the vault-java-driver in practice
String roleId  = System.getenv("VAULT_ROLE_ID");          // baked in (not very secret)
String secretId = readFromMountedFile("/var/run/secrets/vault/secret_id"); // short-lived, injected

VaultToken token = vault.auth().loginByAppRole(roleId, secretId); // exchange for a token
Map<String,String> kv = vault.logical()
        .read("secret/data/myapp/db").getData();          // KV v2 path
String dbUser = kv.get("username");
String dbPass = kv.get("password");
// Better: read dynamic creds so nothing long-lived exists:
var lease = vault.logical().read("database/creds/myapp-role");
// use lease creds; spring-cloud-vault can auto-renew the lease in the background
```

### 5.5 Vault dynamic database secrets (CLI setup)

```bash
# 1. Enable + configure the database engine with an admin connection
vault secrets enable database
vault write database/config/mydb \
  plugin_name=postgresql-database-plugin \
  allowed_roles="myapp-role" \
  connection_url="postgresql://{{username}}:{{password}}@db:5432/app?sslmode=require" \
  username="vault-admin" password="$ADMIN_PW"

# 2. Define a role: the SQL Vault runs to mint a short-lived user
vault write database/roles/myapp-role \
  db_name=mydb \
  creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; \
                       GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\";" \
  default_ttl="1h" max_ttl="24h"

# 3. App requests creds — unique user, auto-revoked after the lease
vault read database/creds/myapp-role
# Key            Value
# lease_id       database/creds/myapp-role/abc123
# lease_duration 1h
# password       A1b2-... (random)
# username       v-approle-myapp-... (random)
```

### 5.6 Vault transit engine (encryption as a service)

```bash
vault secrets enable transit
vault write -f transit/keys/orders            # create a named key (Vault holds it)

# encrypt (Vault never sees your storage; you never see the key)
ct=$(vault write -field=ciphertext transit/encrypt/orders \
      plaintext=$(echo -n "card=4111..." | base64))
echo "$ct"      # vault:v1:Xy9...   (the v1 = key version, enables seamless rotation)

# decrypt
vault write -field=plaintext transit/decrypt/orders ciphertext="$ct" | base64 -d

# rotate the key, then migrate old ciphertext forward without exposing plaintext
vault write -f transit/keys/orders/rotate
vault write transit/rewrap/orders ciphertext="$ct"   # → vault:v2:...
```

### 5.7 TLS in Java: build an SSLContext with a PKCS12 keystore + custom truststore (mTLS)

```java
import javax.net.ssl.*;
import java.security.KeyStore;
import java.io.FileInputStream;

KeyStore ks = KeyStore.getInstance("PKCS12");
ks.load(new FileInputStream("service.p12"), keyPass);     // this service's cert + private key
KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
kmf.init(ks, keyPass);

KeyStore ts = KeyStore.getInstance("PKCS12");
ts.load(new FileInputStream("trust.p12"), trustPass);     // CAs we trust (for verifying peers)
TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
tmf.init(ts);

SSLContext ctx = SSLContext.getInstance("TLSv1.3");        // pin to modern TLS
ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());
// For an mTLS server: sslEngine.setNeedClientAuth(true); to require client certs.
SSLEngine engine = ctx.createSSLEngine();
engine.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
```

### 5.8 git-secrets / gitleaks (preventing leaks)

```bash
# git-secrets: install hooks + AWS rules, block matching commits
git secrets --install
git secrets --register-aws
git secrets --scan            # scan working tree
git secrets --scan-history    # scan entire history

# gitleaks: scan history in CI, fail the build on a finding
gitleaks detect --source . --redact --exit-code 1
# pre-commit/CI guard mode
gitleaks protect --staged --redact

# If a secret WAS committed: rotate it FIRST (assume it's burned), then scrub history
#   git filter-repo --invert-paths --path secrets.env   (or BFG); then force-push.
```

---

## 6. Implementation concerns & best practices

**Performance.**
- AES-GCM is hardware-accelerated on modern CPUs (**AES-NI** instructions) → multiple GB/s; encryption rarely the bottleneck. Use AES-256-GCM by default; use ChaCha20-Poly1305 where AES-NI is absent.
- **Cache DEKs**: a KMS round-trip is ~milliseconds and rate-limited; do *not* call KMS per row. Generate a DEK, encrypt a batch, discard.
- Password hashes are *intentionally* slow (100–500 ms). Don't run them on hot paths beyond login; size your thread pool so a login storm can't exhaust it (a DoS vector).
- TLS 1.3 reduces handshake to 1-RTT; reuse connections (keep-alive, connection pools) to amortize handshakes; enable session resumption.

> **AES-NI.** A set of x86 CPU instructions implementing AES rounds in hardware, making AES both faster and more side-channel resistant than software implementations.

**Correctness / concurrency.**
- **Never reuse a (key, nonce)** under GCM. With random 96-bit nonces, rotate the key well before ~2³² messages per key. `Cipher` objects are *not* thread-safe — create per use or pool carefully.
- Always use AEAD; never "encrypt-then-forget-integrity." Never use ECB. Use OAEP for RSA.
- Use **constant-time comparison** for MACs/tokens (`MessageDigest.isEqual`) to avoid timing attacks. `String.equals` short-circuits and leaks timing.

**Memory / key hygiene.**
- Prefer `byte[]`/`char[]` for secrets over `String` (Strings are immutable, interned, and linger in the heap until GC; you can't wipe them). Zero `byte[]`/`char[]` after use (`Arrays.fill`). Note: GC may still have copied bytes — zeroization is best-effort on the JVM.
- Avoid putting secrets in logs, exceptions, `toString()`, or core dumps. Disable heap dumps on secret-handling processes or scrub them.

**Security.**
- **Least privilege**: scope KMS key policies / Vault policies / IAM to the minimum paths and operations. Bind ciphertext to context (encryption context / AAD) to prevent confused-deputy/ciphertext-swap.
- **No hardcoded secrets** — ever, including config files committed to Git, container images, and CI logs. Inject at runtime (env from a secrets manager, mounted files, sidecar).
- **Rotate** on a schedule and on suspicion. Assume any committed secret is compromised → rotate, don't just delete.
- **Defense in depth**: encryption at rest *and* in transit *and* access control *and* audit. Encryption is not a substitute for authorization.

**Cost.**
- KMS bills per request and per key/month; envelope encryption + DEK caching dramatically cuts request cost. Secrets-manager entries bill per secret + per API call; consolidate where sane.
- HSMs (CloudHSM, dedicated) are expensive (hourly per HSM); use managed KMS unless a compliance mandate forces dedicated HSMs.

**Observability.**
- **Audit every key/secret access**: CloudTrail for KMS/Secrets Manager, Vault audit devices, Key Vault diagnostic logs. Alert on anomalies (decrypt spikes, denied access, access from new principals).
- Track **cert expiry** (export metrics; alert ≥30 days out). Expired certs are a top outage cause.
- Emit metrics for rotation success/failure, lease renewals, KMS throttling (HTTP 400 `ThrottlingException`).

**Testing.**
- Test encrypt→decrypt round-trips, tamper-detection (flip a byte → expect `AEADBadTagException`), wrong-key/wrong-context failures, and key-rotation/rewrap paths.
- Test password upgrade-on-login (`upgradeEncoding`), and that auth fails closed when the secrets backend is down (don't fall back to a default password!).
- Use **LocalStack** (AWS), Vault dev mode, or testcontainers for integration tests — but *never* commit the test secrets.

**Production hardening / anti-patterns to avoid.**

| Anti-pattern | Why it's bad | Do instead |
|---|---|---|
| Hardcoding/committing secrets | Permanent leak in Git history | Secrets manager + scanning + push protection |
| Plain SHA-256 for passwords | GPU-crackable instantly | Argon2id/bcrypt/scrypt + salt |
| Encrypting passwords | Reversible; you never need plaintext | Hash, don't encrypt |
| ECB mode / no integrity | Leaks structure; tamperable | AES-256-GCM (AEAD) |
| Reusing GCM nonces | Catastrophic key/plaintext leak | Random/counter unique nonce per (key) |
| `java.util.Random` for keys/IVs | Predictable | `SecureRandom` |
| MD5/SHA-1 | Broken | SHA-256+/BLAKE2/3 |
| One shared static DB password | Huge blast radius, no traceability | Vault dynamic secrets |
| Self-signed / never-rotated certs in prod | Outages, MITM risk | ACME/cert-manager auto-renewal |
| Storing the key next to the data, same trust zone | Breach gets both | Envelope encryption, KEK in HSM |
| Long-lived static cloud keys in app | Leaked → standing access | IAM roles / Workload Identity / short-lived creds |

---

## 7. Advanced topics & deep internals

**Authenticated encryption misuse-resistance.** Standard GCM is *not* nonce-misuse resistant (reuse is fatal). **AES-GCM-SIV** derives the nonce/counter from the message so accidental nonce reuse degrades gracefully (only reveals equality of duplicate messages). Use it when you can't guarantee unique nonces.

**Convergent encryption.** Encrypt identical plaintext to identical ciphertext (deterministically) so you can **deduplicate** or do **equality lookups** on encrypted columns. Vault transit supports it. The tradeoff: it leaks which records are equal — a privacy cost. Searchable/order-preserving encryption push this further with corresponding leakage tradeoffs; treat them as specialist tools.

**Key derivation.** Don't reuse one key for many purposes. Use a **KDF (Key Derivation Function)** — **HKDF** (extract-then-expand from a master secret) for deriving sub-keys, or **PBKDF2/Argon2** for deriving keys from passwords. Per-context derived keys (`HKDF(master, "tenant-42")`) localize compromise.

> **HKDF.** HMAC-based KDF: "extract" condenses input entropy into a pseudorandom key, "expand" stretches it into as many independent sub-keys as you need, each bound to an "info" label. Used inside TLS 1.3.

**Post-quantum cryptography (PQC).** A sufficiently large quantum computer would break RSA/ECC (via Shor's algorithm). NIST has standardized PQC algorithms: **ML-KEM (Kyber)** for key exchange and **ML-DSA (Dilithium)** / **SLH-DSA (SPHINCS+)** for signatures. The near-term move is **hybrid** (classical + PQC together) for TLS and for protecting long-lived data against "harvest now, decrypt later." Symmetric crypto (AES-256) is largely fine (Grover's algorithm only halves effective key strength → AES-256 ≈ 128-bit PQ security).

**Side channels.** Timing, cache, and power side-channels can leak keys. Mitigations: constant-time implementations (the JDK/providers handle the primitives), `MessageDigest.isEqual` for comparisons, avoiding secret-dependent branching, and for the highest assurance, HSMs/TEEs. RSA decryption historically suffered padding-oracle (Bleichenbacher) attacks — another reason to use OAEP and AEAD.

**KMS deep behaviors.** Encryption context is AAD — wrong context → decrypt fails (great for binding). Grants give temporary, constrainable delegated access without editing the key policy. Multi-Region keys replicate key material across regions (same key id) for cross-region decrypt/DR. Imported key material (BYOK) lets you supply your own key bytes but you then own availability of the material.

**Vault deep behaviors.** `max_lease_ttl` caps renewals; **response wrapping** delivers a secret as a single-use wrapping token so the secret itself never sits in transit logs and tampering is detectable; **batch tokens** are lightweight, non-persistent tokens for high scale; **performance/DR replication** (enterprise) span clusters; **Sentinel** policies add fine-grained, rule-based authorization. Auto-unseal + recovery keys replace Shamir shares in cloud deployments.

**Envelope at scale: tiered keys.** Large systems use 3+ tiers: per-record DEK → per-tenant/intermediate KEK → root KEK in HSM. This bounds rewrap cost on rotation and enables per-tenant crypto-shredding while keeping HSM calls rare.

**Format-preserving encryption (FPE).** Encrypts so the ciphertext has the same format/length as the plaintext (e.g., a 16-digit number → a 16-digit number), useful for legacy schemas/PCI. NIST **FF1/FF3-1**. Distinct from tokenization (next section).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Tokenization vs encryption

> **Tokenization.** Replace a sensitive value with a random, meaningless **token**; the real value is stored in a separate, highly-secured **token vault**, and the mapping token↔value is looked up only when truly needed. The token has *no mathematical relationship* to the original (unlike encryption, where the ciphertext is derived from the plaintext).

| Dimension | Encryption | Tokenization |
|---|---|---|
| Reversibility | Reversible with key | Reversible only via the token vault lookup |
| Relationship to data | Ciphertext derived from plaintext | Token is random, unrelated |
| Key compromise risk | Key leak exposes all ciphertext | No key to leak; must breach the vault |
| Format preservation | Needs FPE | Natural (token can match format) |
| Scope reduction (PCI) | Encrypted data still in scope | Tokens often **out of PCI scope** → big audit win |
| Scalability | Stateless (just need the key) | Stateful (central vault is a dependency/SPOF) |
| Use when | You need to compute on / share / decrypt at scale | You rarely need the real value (cards, SSNs); want to shrink compliance scope |

**Rule of thumb:** tokenize values you mostly need to *reference but rarely read* (PANs, SSNs) to slash compliance scope; encrypt values you genuinely need to *read back at scale*.

### 8.2 Where each crypto type is used

| Need | Mechanism |
|---|---|
| Bulk data at rest | Symmetric AES-256-GCM + envelope (KMS) |
| Data in transit | TLS 1.3 (AEAD) — asymmetric handshake, symmetric payload |
| Verify passwords | Argon2id/bcrypt/scrypt (one-way, salted) |
| Integrity of a token/message (shared key) | HMAC-SHA256 |
| Authenticity + non-repudiation | Asymmetric signature (Ed25519/ECDSA/RSA-PSS) |
| Key exchange | (EC)DHE / RSA-OAEP wrap |
| Protect keys themselves | KMS/HSM (KEK), envelope |
| Compute on sensitive data without exposing it | TEE / confidential computing (rarely FHE/MPC) |

### 8.3 Secrets-management platform choice

| Option | Use when | Avoid when |
|---|---|---|
| AWS/GCP/Azure native secrets manager + KMS | You're all-in on one cloud; want minimal ops | Multi-cloud / on-prem; need dynamic DB secrets, fine-grained leasing |
| HashiCorp Vault | Multi-cloud/hybrid, dynamic secrets, transit-as-KMS, advanced policy | Tiny team that can't operate an HA stateful service (then prefer managed) |
| Dedicated HSM (CloudHSM, on-prem) | FIPS 140-3 L3 mandate, custody requirements | Cost-sensitive, no compliance driver (managed KMS suffices) |
| Kubernetes Secrets (alone) | Never as the sole store — base64, not encrypted by default | As your real secrets store; pair with KMS encryption-at-rest + external secrets operator |

### 8.4 Password hash choice

| Algorithm | Choose when |
|---|---|
| **Argon2id** | New systems; best memory-hardness; recommended default |
| **scrypt** | Need memory-hardness, Argon2 unavailable |
| **bcrypt** | Mature ecosystems, broad library support; cost ≥ 12; widely battle-tested |
| **PBKDF2** | Only when FIPS compliance forces it (not memory-hard; use high iteration count) |

---

## 9. Failure modes & debugging

**1. Hardcoded/committed secret discovered.**
- *Symptom:* gitleaks/trufflehog/GitHub alert, or worse, abuse on the credential.
- *Response (in order):* **rotate the secret immediately** (assume burned, even in private repos), revoke old, then scrub history (`git filter-repo`/BFG) and force-push, then add push-protection so it can't recur. Auditing history with `git log -p`/`gitleaks --scan-history`.

**2. GCM nonce reuse.**
- *Symptom:* subtle — usually found in code review, not at runtime. Catastrophic confidentiality + forgeability.
- *Diagnose:* audit nonce generation; ensure `SecureRandom` per encryption or a guaranteed-unique counter. Consider AES-GCM-SIV. Re-encrypt affected data with new keys.

**3. KMS throttling / quota exceeded.**
- *Symptom:* `ThrottlingException`/`KMSThrottlingException`, latency spikes, failed decrypts under load.
- *Diagnose:* CloudWatch KMS request metrics. *Fix:* DEK caching (AWS Encryption SDK caching CMM), batch operations, request a quota increase, use multiple keys.

**4. Decrypt fails: `InvalidCiphertextException` / `AEADBadTagException`.**
- *Causes:* wrong key/key version, mismatched **encryption context/AAD**, truncated/corrupted ciphertext, wrong nonce slicing. *Diagnose:* confirm the exact context map and key id used at encrypt time; check storage didn't mangle bytes (encoding!).

**5. TLS handshake failure.**
- *Symptoms:* `SSLHandshakeException`, `PKIX path building failed`, `certificate_unknown`, `handshake_failure`.
- *Diagnose:* `openssl s_client -connect host:443 -servername host` (inspect chain, protocol, cipher), `keytool -list` on keystore/truststore, JVM flag `-Djavax.net.debug=ssl,handshake` for verbose trace. Common causes: missing intermediate cert (incomplete chain), hostname mismatch (SAN), expired cert, untrusted CA in the truststore, protocol/cipher mismatch (e.g., server only TLS 1.3, client capped at 1.2), clock skew.

**6. Expired certificate outage.**
- *Symptom:* sudden mass connection failures at a round timestamp. *Prevent:* expiry monitoring + auto-renewal (ACME/cert-manager). *Real incidents:* numerous high-profile outages (telecom, browsers, payment networks) have been traced to a single expired cert — treat expiry monitoring as P1.

**7. Vault sealed / can't fetch secret.**
- *Symptom:* `Vault is sealed`, 503s, apps failing to boot. *Diagnose:* `vault status`. *Fix:* unseal (auto-unseal should handle restarts; if Shamir, gather threshold shares). Apps should **fail closed** and retry with backoff, never fall back to a default credential.

**8. Lease expiry surprises.**
- *Symptom:* dynamic DB creds stop working mid-run (`password authentication failed`). *Cause:* lease expired without renewal. *Fix:* enable lease renewal (spring-cloud-vault background renewal), set sane `default_ttl`/`max_ttl`, handle re-fetch on auth error.

**9. Password verification suddenly slow / login DoS.**
- *Symptom:* CPU saturation on login bursts (Argon2 memory/time cost × concurrency). *Fix:* bound the login thread pool, add rate-limiting/CAPTCHA, right-size hash parameters, queue rather than thrash memory.

**Debugging toolbox quick list:** `openssl s_client`, `openssl x509 -in cert.pem -noout -text -dates`, `keytool`, `-Djavax.net.debug=ssl`, CloudTrail/Vault audit logs, `aws kms describe-key`/`get-key-rotation-status`, `vault status`/`vault lease lookup`, gitleaks/trufflehog scans.

---

## 10. Interview drill

**Q1. Encryption at rest vs in transit vs in use — define and give a mechanism for each.**
*Model:* At rest = data on disk (DB/backups/S3), protected by symmetric encryption with KMS-managed keys (TDE, encrypted volumes, app-level AES-GCM). In transit = data on the network, protected by TLS 1.3. In use = data being processed in plaintext memory, protected by TEEs/confidential computing (and exotically FHE/MPC). The first two are standard; "in use" is the frontier gap.
- *Probe: which is hardest and why?* "In use" — you traditionally must decrypt to compute; TEEs encrypt RAM and attest the code, FHE computes on ciphertext but is slow.
- *Probe: is at-rest encryption enough if my DB is breached via SQL injection?* No — the app decrypts legitimately, so injection reads plaintext. At-rest only defends stolen media; you also need authorization, input handling, and least privilege.

**Q2. Explain envelope encryption and why we don't just encrypt everything directly with a KMS key.**
*Model:* Generate a local DEK, encrypt data with it (fast AES-GCM), then wrap the DEK with a KEK that never leaves the KMS/HSM; store ciphertext + wrapped DEK. Direct KMS encryption is rate-limited, latency-bound, size-limited, and costly per call; envelope keeps the master key in hardware while allowing unlimited, fast, cacheable local encryption.
- *Probe: how does this make rotation cheap?* Rotating the KEK only re-wraps small DEKs (or just changes new-write keys); the bulk data isn't re-encrypted.
- *Probe: what's the encryption context for?* It's AAD bound to the wrapped DEK; decrypt must supply the same context, preventing ciphertext-swap/confused-deputy.

**Q3. How should passwords be stored, and what's wrong with SHA-256?**
*Model:* Use a slow, memory-hard, salted hash — Argon2id (or bcrypt/scrypt). SHA-256 is fast → GPUs crack billions/sec, so a leaked table of fast hashes falls quickly. Never encrypt (you never need the plaintext) and never store plaintext.
- *Probe: salt vs pepper?* Salt = per-password, public, unique (defeats rainbow tables, ensures distinct hashes); pepper = a single secret kept *outside* the DB (KMS), adding protection if only the DB leaks.
- *Probe: how do you tune the cost?* So one hash ≈ 100–500 ms on prod hardware; re-tune as hardware improves; upgrade hashes on next successful login.

**Q4. Symmetric vs asymmetric — where is each used in TLS?**
*Model:* Asymmetric ((EC)DHE + the cert's signature) authenticates the server and establishes a shared secret during the handshake; symmetric (AES-GCM/ChaCha20-Poly1305) then encrypts the bulk session data because it's far faster. Hybrid: asymmetric for the handshake, symmetric for the payload.
- *Probe: what is forward secrecy?* Ephemeral DH keys per session mean a later theft of the server's long-term key can't decrypt previously recorded sessions.
- *Probe: why not encrypt all data with RSA?* RSA is slow and can only encrypt data smaller than its key size; impractical for bulk.

**Q5. What are dynamic secrets and why are they better than a static DB password?**
*Model:* Vault generates a unique, short-lived DB user per request via a lease, and auto-revokes (`DROP USER`) on expiry. No shared long-lived password exists, so a leak self-heals quickly, access is per-instance traceable, and rotation is intrinsic.
- *Probe: what's a lease?* A TTL'd grant Vault tracks; renewable to a max TTL, revocable on demand; expiry triggers automatic credential revocation.
- *Probe: bootstrap problem (secret-zero)?* Use platform identity (Kubernetes/AWS IAM auth) or AppRole with a just-in-time, short-lived secret_id, so the app never ships a long-lived Vault credential.

**Q6. (Senior signal) When would you tokenize vs encrypt?**
*Model:* Tokenize values you mostly reference but rarely read (PANs, SSNs) to remove them from compliance scope (tokens are random, no key to leak); encrypt values you must read back at scale or compute on. Tokenization adds a stateful central vault (a dependency/SPOF) but slashes PCI scope; encryption is stateless but a key leak exposes all ciphertext.
- *Probe: PCI implications?* Tokens are typically out of PCI-DSS scope, shrinking the audit boundary dramatically; encrypted PANs remain in scope.
- *Probe: can you query/dedup encrypted data?* Only with deterministic/convergent or searchable encryption, which leaks equality/order — a privacy tradeoff. Tokenization sidesteps this for reference-only use.

**Q7. (Senior signal) Walk me through rotating a leaked API key with zero downtime.**
*Model:* Treat it as compromised. Issue a *new* key while keeping the old valid (dual-validity window). Deploy the new key to consumers (via secrets manager, not redeploys ideally). Confirm traffic moved to the new key (metrics/audit). Revoke the old key. Scrub the leak source (Git history) and add push protection/scanning. Post-incident: shorten key TTLs / move to dynamic secrets.
- *Probe: why dual-validity?* So no request fails during the cutover; a hard swap causes an outage window.
- *Probe: how prevent recurrence?* Pre-commit + CI scanning, push protection, no secrets in images/config, dynamic short-lived creds, audit alerting.

**Q8. (Senior signal) You must encrypt a 50 TB data lake with per-tenant crypto-shredding and minimal KMS cost. Design it.**
*Model:* Tiered envelope: per-record/object DEKs (AES-256-GCM, generated locally), wrapped by per-tenant intermediate KEKs, themselves wrapped by a root KEK in KMS/HSM. Cache DEKs to avoid per-object KMS calls (AWS Encryption SDK caching CMM). Crypto-shred a tenant by destroying their intermediate KEK → all their objects become unrecoverable without rewriting 50 TB. Rotation re-wraps keys, not data. Audit via CloudTrail.
- *Probe: KMS throttling?* DEK caching + batching keeps KMS calls rare; per-tenant KEKs spread load.
- *Probe: how does crypto-shred satisfy GDPR erasure on immutable backups?* You can't overwrite immutable/backed-up data, but destroying the key renders it permanently unreadable — accepted as erasure.

**Q9. Why is reusing a GCM nonce catastrophic?**
*Model:* GCM is CTR-mode based; the same (key, nonce) reuses the same keystream, so XORing two ciphertexts reveals XOR of plaintexts, and the authentication subkey can be recovered, letting an attacker forge valid tags. Use unique nonces (random 96-bit or counters), or AES-GCM-SIV for misuse resistance.
- *Probe: how many messages per key with random nonces?* Stay well below ~2³² to keep collision probability negligible; rotate keys before that.

**Q10. What is mTLS and when do you use it?**
*Model:* Mutual TLS = both client and server present certificates, so both authenticate. Used for service-to-service auth in zero-trust meshes, replacing shared API keys with verifiable identities; the mesh (Istio/Linkerd) often automates cert issuance/rotation.
- *Probe: how are certs rotated at scale?* Short-lived certs auto-issued/rotated by the mesh or cert-manager/SPIFFE, so revocation is rarely needed.

**Q11. How do you keep secrets out of source control, and what do you do if one leaks?**
*Model:* Inject at runtime from a secrets manager; never commit. Enforce with pre-commit hooks (git-secrets/detect-secrets) + CI scanning (gitleaks/trufflehog) + platform push protection. If leaked: rotate first (assume compromised), then scrub history and force-push.
- *Probe: why rotate even for a private repo?* Forks, clones, CI logs, and insider access mean you can't assume containment; the credential is burned.

**Q12. What's the difference between KMS and an HSM, and when do you need a dedicated HSM?**
*Model:* An HSM is tamper-resistant hardware that holds keys in silicon (FIPS-validated); a managed KMS is a multi-tenant service backed by HSM fleets exposing a clean API. Use managed KMS by default; choose a dedicated HSM (CloudHSM/on-prem) when compliance mandates single-tenant FIPS 140-3 L3 custody or you need full control of key material.
- *Probe: BYOK?* Bring-Your-Own-Key imports your key material into KMS for custody/compliance, but you then own its availability/durability.

---

## 11. Glossary

- **AEAD** — Authenticated Encryption with Associated Data; encrypts + authenticates in one pass (AES-GCM, ChaCha20-Poly1305).
- **AES** — Advanced Encryption Standard; symmetric block cipher (128/192/256-bit keys).
- **AES-NI** — CPU instructions hardware-accelerating AES.
- **AAD** — Associated Data; authenticated-but-not-encrypted data bound to a ciphertext.
- **ACME** — protocol for automated certificate issuance (Let's Encrypt).
- **AppRole** — Vault auth method for machines (role_id + secret_id).
- **Argon2 / Argon2id** — modern memory-hard password-hashing function.
- **Asymmetric crypto** — public/private key pair (RSA, ECC).
- **bcrypt** — battle-tested adaptive password hash with a cost factor.
- **Barrier** — Vault's encryption layer protecting stored data.
- **Block cipher** — encrypts fixed-size blocks; needs a mode for real data.
- **BYOK** — Bring Your Own Key (import key material into KMS).
- **CA** — Certificate Authority; signs certificates.
- **CBC / CTR / ECB / GCM** — block-cipher modes (avoid ECB; prefer GCM/AEAD).
- **ChaCha20-Poly1305** — software-fast AEAD stream cipher.
- **Confidential computing / TEE** — hardware enclaves protecting data *in use*.
- **Convergent encryption** — deterministic encryption enabling dedup/equality (leaks equality).
- **CRL / OCSP** — certificate revocation list / online revocation check.
- **Crypto-shredding** — deleting data by destroying its key.
- **CSPRNG** — cryptographically secure RNG (`SecureRandom`).
- **CSR** — Certificate Signing Request.
- **DEK** — Data Encryption Key; encrypts the actual data.
- **(EC)DHE** — (Elliptic-Curve) Diffie-Hellman Ephemeral key agreement; gives forward secrecy.
- **ECC** — Elliptic Curve Cryptography (P-256, Curve25519).
- **EDK** — Encrypted Data Key (the wrapped DEK).
- **Encryption context** — AWS KMS AAD bound to ciphertext.
- **Envelope encryption** — encrypt data with a DEK, wrap the DEK with a KEK.
- **FIPS 140-2/140-3** — US crypto-module certification standards.
- **Forward secrecy** — past sessions stay safe even if long-term key leaks later.
- **FPE** — Format-Preserving Encryption (FF1/FF3-1).
- **HKDF** — HMAC-based key derivation function.
- **HMAC** — keyed hash MAC for integrity/authenticity.
- **Homomorphic encryption (FHE)** — compute on ciphertext.
- **HSM** — Hardware Security Module; tamper-resistant key hardware.
- **IV / nonce** — non-secret uniqueness value; never reuse with the same key (GCM).
- **JCA/JCE** — Java's crypto framework.
- **KDF** — Key Derivation Function.
- **KEK** — Key Encryption Key; the master key that wraps DEKs.
- **Kerckhoffs's principle** — security rests on the key, not algorithm secrecy.
- **KMS** — Key Management Service.
- **Lease** — Vault's TTL'd grant for dynamic secrets (renew/revoke).
- **MAC** — Message Authentication Code.
- **Memory-hard** — function needing lots of RAM (defeats GPU/ASIC parallelism).
- **mTLS** — mutual TLS; both sides present certs.
- **OAEP** — secure RSA encryption padding.
- **Pepper** — secret value added to passwords, stored outside the DB.
- **PBKDF2** — password-based KDF (not memory-hard; FIPS-friendly).
- **PII** — Personally Identifiable Information.
- **PKCS12 / JKS** — keystore formats (PKCS12 = modern default).
- **PQC** — Post-Quantum Cryptography (ML-KEM/Kyber, ML-DSA/Dilithium).
- **Raft** — consensus algorithm (Vault integrated storage).
- **Rainbow table** — precomputed hash→password table (defeated by salt).
- **RSA** — asymmetric algorithm based on factoring.
- **Salt** — per-password unique public value.
- **scrypt** — memory-hard password hash.
- **SecureRandom** — Java CSPRNG.
- **Shamir's Secret Sharing** — split a secret into N shares, K reconstruct.
- **SNI** — Server Name Indication; hostname in TLS ClientHello.
- **Symmetric crypto** — one shared key (AES).
- **TDE** — Transparent Data Encryption (DB-level at-rest encryption).
- **TLS / SSL** — transport encryption (use TLS 1.2/1.3; SSL is dead).
- **Tokenization** — replace sensitive data with an unrelated random token.
- **Transit engine** — Vault encryption-as-a-service.
- **TTL** — Time-To-Live.
- **Vault** — HashiCorp secrets-management server.
- **X.509** — certificate format binding a public key to an identity.
- **Zeroization** — wiping key material from memory after use.

---

## 12. Cheat-sheet & self-test

### Dense recap (one screen)

**Defaults to memorize:**
- Symmetric: **AES-256-GCM** (AEAD). Software-only: **ChaCha20-Poly1305**.
- GCM nonce: **96-bit, unique per (key)**, never reused. Tag: **128-bit**.
- Asymmetric: **Ed25519/ECDSA** signatures, **X25519/ECDHE** exchange, **RSA-2048+** with **OAEP**.
- Hashing: **SHA-256+** / BLAKE2/3. **MD5/SHA-1 = banned.**
- Passwords: **Argon2id** (or bcrypt cost ≥12 / scrypt). **Never SHA, never encrypt.** Tune ~100–500 ms. Salt always; pepper in KMS.
- TLS: **1.3** (1-RTT, forward secrecy), 1.2 minimum. Disable SSL/1.0/1.1, CBC, RC4.
- RNG: **`SecureRandom`** only.

**Core patterns:**
- **Envelope:** DEK encrypts data (local, fast) → KEK (in KMS/HSM) wraps DEK → store ciphertext+wrapped DEK. Cache DEKs. Crypto-shred by deleting a DEK.
- **Rotation:** KEK rotation = re-wrap DEKs (cheap, no data rewrite). DEK rotation = re-encrypt data (lazy). Dual-validity window for zero-downtime credential rotation.
- **Dynamic secrets (Vault):** per-request short-lived DB user + lease + auto-revoke. No shared static password.
- **Tokenize** reference-only PII (cards/SSNs) → out of PCI scope; **encrypt** read-at-scale data.

**Decision rules:**
- Need data back? Encrypt. Only verify? Hash. Rarely read, want scope reduction? Tokenize.
- Master key must never leak? Envelope with HSM-backed KEK.
- Multi-cloud / dynamic secrets / encryption-as-a-service? Vault. All-in one cloud? Native KMS + secrets manager.
- Compliance L3 custody? Dedicated HSM.

**Never:** hardcode/commit secrets · reuse GCM nonce · ECB · plain/fast hash for passwords · `java.util.Random` · MD5/SHA-1 · store key next to data in same trust zone · ignore `AEADBadTagException` · let certs expire unmonitored · fall back to a default credential when the secrets backend is down.

**Debug fast:** `openssl s_client -connect h:443 -servername h` · `openssl x509 -noout -dates` · `-Djavax.net.debug=ssl,handshake` · `keytool -list` · `vault status` · CloudTrail/Vault audit · `gitleaks detect` · `aws kms get-key-rotation-status`.

### Self-test (no answers — recall actively)

1. Walk through envelope encryption end-to-end, naming DEK, KEK, EDK, and where each lives. Then explain *exactly* why rotating the KEK doesn't require re-encrypting your data.
2. You must store user passwords. Specify the algorithm, every parameter you'd set, where the salt and pepper live, and how you'd migrate an existing bcrypt table to Argon2 without forcing password resets.
3. Explain precisely why reusing a GCM nonce breaks both confidentiality *and* integrity, and name two ways to prevent it.
4. Map each crypto primitive used in a TLS 1.3 handshake to its job, and explain how forward secrecy is achieved and why it matters after a key theft.
5. Design a secrets architecture for a multi-cloud company that needs short-lived database credentials, encryption-as-a-service, and per-tenant crypto-shredding. Justify each component choice and name the failure modes you'd monitor.
6. A secret was committed to a private GitHub repo three weeks ago. List, in order, every action you take and explain why "just delete the file" is insufficient.
7. Compare tokenization and encryption for storing 16-digit card numbers, including the PCI-scope and single-point-of-failure implications of each.

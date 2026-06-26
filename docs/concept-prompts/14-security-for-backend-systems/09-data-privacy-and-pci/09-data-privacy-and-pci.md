# Data Privacy & PCI Compliance

> Engineering handbook chapter — Security for Backend Systems
> Reader profile: senior Java/JVM backend engineer who wants to master this subtopic end-to-end: design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

**What it is.** "Data privacy & PCI compliance" is the discipline of *handling regulated and sensitive data correctly* in a backend system: knowing what kinds of data you hold, what laws and contracts govern each kind, and building the technical controls (classification, encryption, access control, tokenization, retention, audit logging, deletion) that keep you both *secure* and *legally compliant*.

Two distinct but overlapping forces are at play:

- **Data privacy law** — statutes like the EU **GDPR** (General Data Protection Regulation), the **CCPA/CPRA** (California), India's **DPDP Act 2023**, Brazil's **LGPD**, etc. These regulate *personal data* about identifiable humans and grant those humans **rights** (access, deletion, portability) and impose **obligations** on you (minimize what you collect, secure it, delete it when no longer needed, report breaches).
- **PCI DSS** — the **Payment Card Industry Data Security Standard**, a *contractual* standard (not a law) enforced by the card networks (Visa, Mastercard, Amex, Discover, JCB) through your acquiring bank. It governs how you handle **cardholder data** (card numbers and related data). Non-compliance can mean fines, higher transaction fees, or losing the ability to accept cards at all.

> **Beginner aside — "acquiring bank" / "acquirer":** the bank or processor that holds the *merchant's* account and settles card transactions into it. The **issuer** is the bank that gave the *cardholder* their card. The acquirer is who contractually requires you to be PCI compliant.

**The problem it solves.** Left to their own devices, engineers will (a) log everything, (b) store data forever "just in case," (c) copy production data into test environments, (d) store card numbers in a database column because it's convenient. Each of these is a breach, a fine, or an audit failure waiting to happen. This discipline gives you a *systematic* way to avoid all four.

**When you reach for it.** Always, the moment your system touches any of:
- A person's name, email, phone, address, IP address, device ID, location, health data, or any identifier.
- A payment card number (a **PAN** — Primary Account Number).
- Anything a customer would be upset to see on the front page of a newspaper.

**One-paragraph mental model.** Treat sensitive data like radioactive material. First, *classify* it — know exactly what you have and how "hot" each piece is (PII vs PHI vs cardholder data). Second, *minimize* — don't collect or keep what you don't need; the safest data is data you never stored. Third, *contain* — for the hottest data (card numbers), shrink the blast radius so that as few systems as possible ever touch the raw value (this is **PCI scope reduction**, usually via **tokenization**). Fourth, *protect what remains* — encryption at rest and in transit, strict access control, tamper-evident audit logs. Fifth, *be able to delete on demand* — including in immutable/event-sourced systems where you can't literally erase a record (use **crypto-shredding**). Compliance is the *evidence* that you did all five and can prove it.

---

## 2. Foundations from first principles

### 2.1 What is "personal data"? (and the alphabet soup)

Different regimes use different words for overlapping ideas. Learn the precise distinctions — interviewers and auditors care.

| Term | Stands for | Regime | Plain definition | Examples |
|---|---|---|---|---|
| **PII** | Personally Identifiable Information | US/general usage | Data that identifies a specific person, alone or combined | name, email, SSN, phone, device ID, IP |
| **Personal data** | (the GDPR term) | GDPR | *Any* information relating to an identified or identifiable natural person ("data subject") | broader than PII — includes pseudonymized data, online identifiers, cookies |
| **PHI** | Protected Health Information | HIPAA (US health) | Health/medical info tied to an individual, held by a "covered entity" or its business associate | diagnoses, prescriptions, lab results linked to a patient |
| **Cardholder Data (CHD)** | — | PCI DSS | The card number plus certain related elements | PAN, cardholder name, expiry, service code |
| **SAD** | Sensitive Authentication Data | PCI DSS | Data used to authenticate a transaction — **never to be stored after authorization** | CVV/CVC, full magnetic-stripe/track data, PIN/PIN block |
| **SPI / "special categories"** | Sensitive Personal Information | GDPR Art. 9 / CPRA | A subset of personal data needing *extra* protection | race, ethnicity, political opinions, religion, health, sexual orientation, biometrics, genetic data |

> **Beginner aside — "data subject" / "controller" / "processor":** GDPR vocabulary. The **data subject** is the human the data is about. The **controller** decides *why and how* data is processed (e.g., your company). A **processor** processes data *on behalf of* a controller (e.g., your cloud provider, your email-sending SaaS). Controllers carry most obligations; processors are bound by contract (a **Data Processing Agreement, DPA**) and have some direct duties too.

**Key insight on "identifiable":** under GDPR, data is personal even if it doesn't *directly* name someone, as long as someone *could* be re-identified — directly or indirectly, by you or by anyone "reasonably likely" to try. This is why pseudonymized data (see §2.6) is still personal data, and why "we hashed the email" is usually *not* a get-out-of-jail card.

### 2.2 The PCI data elements precisely

PCI DSS splits card data into two buckets with very different rules:

| Bucket | Element | Storage allowed after authorization? | If stored, must it be protected (encrypted/masked)? |
|---|---|---|---|
| **Cardholder Data (CHD)** | **PAN** (Primary Account Number — the 13–19 digit card number) | Yes (but you should avoid it) | **Yes** — render unreadable (encryption, truncation, tokenization, or strong hash) |
| CHD | Cardholder name | Yes | Yes (protect when stored with PAN) |
| CHD | Expiration date | Yes | Yes (protect when stored with PAN) |
| CHD | Service code | Yes | Yes (protect when stored with PAN) |
| **Sensitive Authentication Data (SAD)** | Full **track data** (magnetic stripe / chip equivalent) | **NO — never store after auth** | n/a (must not exist post-auth) |
| SAD | **CVV / CVC / CVV2 / CID** (the 3–4 digit verification code) | **NO — never store after auth** | n/a |
| SAD | **PIN / PIN block** | **NO — never store after auth** | n/a |

> **The two rules to memorize:**
> 1. **Never store SAD after authorization.** CVV, full track, PIN — these may transit your system during the transaction but must never be persisted afterward. Not in the DB, not in a log, not in a cache, not in a backup. This is the single most common, most catastrophic PCI failure.
> 2. **If you must store the PAN, render it unreadable.** Acceptable methods (per PCI DSS): one-way hash of the *entire* PAN with strong cryptography + salt, truncation, **index tokens** (tokenization), or strong encryption with proper key management. Masking (showing only first 6 / last 4) is for *display*, not for storage protection.

> **Beginner aside — PAN structure:** the first 6–8 digits are the **BIN/IIN** (Bank Identification Number / Issuer Identification Number) identifying the issuing bank; the last digit is a **Luhn checksum**. The middle digits are the account. Showing "first 6, last 4" is generally permitted because it's not enough to reconstruct the card.

### 2.3 Encryption from zero

> **Beginner aside — symmetric vs asymmetric:**
> - **Symmetric encryption** uses one secret key for both encrypt and decrypt. Fast. Standard: **AES** (Advanced Encryption Standard), typically AES-256 in an *authenticated* mode like **GCM** (Galois/Counter Mode), which gives both confidentiality and integrity (tamper detection).
> - **Asymmetric encryption** uses a public key to encrypt and a private key to decrypt (e.g., RSA, ECC). Slow; used for key exchange and signatures, not bulk data.

Three places data needs protecting:

1. **In transit** — between client and server, service to service. Use **TLS** (Transport Layer Security; the protocol behind HTTPS) 1.2+ (prefer 1.3). Disable old protocols (SSLv3, TLS 1.0/1.1) and weak ciphers. For sensitive internal traffic, consider **mTLS** (mutual TLS — both client *and* server present certificates).
2. **At rest** — on disk, in the database, in backups, in object storage. Two layers:
   - **Transparent/volume encryption** (disk or DB-engine level, e.g., TDE — Transparent Data Encryption). Protects against a stolen disk but *not* against an attacker with DB credentials.
   - **Application-level / field-level encryption** — your app encrypts specific fields before they touch the DB. Protects against DB compromise but you manage keys and lose some queryability.
3. **In use** — the hardest. Emerging tech: confidential computing (TEEs — Trusted Execution Environments like Intel SGX), homomorphic encryption (compute on ciphertext). Rarely needed; know it exists.

> **Beginner aside — envelope encryption & the DEK/KEK pattern.** You don't encrypt millions of records directly with one master key. Instead: generate a per-record (or per-tenant) **Data Encryption Key (DEK)**, encrypt the data with the DEK, then encrypt the DEK itself with a **Key Encryption Key (KEK)** that lives in a **KMS** (Key Management Service) or **HSM** (Hardware Security Module — tamper-resistant hardware that performs crypto and never exports the master key). Store the *encrypted* DEK next to the data. To read: ask the KMS to decrypt the DEK (the KEK never leaves the KMS), then use the DEK locally. This makes **key rotation** cheap (rotate the KEK without re-encrypting all data) and confines the crown-jewel key to the KMS/HSM.

### 2.4 Access control from zero

- **Authentication (AuthN):** proving *who* you are.
- **Authorization (AuthZ):** deciding *what* you may do.
- **Least privilege:** every identity (human or service) gets the *minimum* permissions needed, nothing more.
- **Need-to-know:** access to sensitive data is granted only when the job requires it — and is revoked when it doesn't.
- **RBAC** (Role-Based Access Control): permissions grouped into roles; users get roles.
- **ABAC** (Attribute-Based Access Control): decisions based on attributes (user dept, data classification, time, location) — more flexible, more complex.
- **Separation of duties:** no single person can both make and approve a sensitive change (e.g., the person who can deploy code cannot also approve their own DB access grant).

### 2.5 Data lifecycle thinking

Every piece of data has a lifecycle: **collect → store → use → share → retain → delete**. Privacy engineering attaches a *purpose* and a *retention period* to data at the moment of collection, and enforces deletion at the end. The governing principles:

- **Purpose limitation:** collect data for a specific, stated purpose; don't repurpose it silently.
- **Data minimization:** collect and keep only what's necessary for that purpose. (The cheapest, safest data is the data you never collected.)
- **Storage limitation / retention:** don't keep data longer than needed; define and enforce a retention schedule.
- **Accuracy:** keep it correct and up to date; allow correction.
- **Integrity & confidentiality:** secure it.
- **Accountability:** be able to *demonstrate* compliance (records, logs, DPIAs).

### 2.6 Pseudonymization vs anonymization (a precise, exam-critical distinction)

| Property | **Pseudonymization** | **Anonymization** |
|---|---|---|
| Definition | Replace identifiers with a pseudonym/token; the mapping to re-identify is kept **separately and securely** | Irreversibly strip identifiers so re-identification is **not reasonably possible** by anyone |
| Reversible? | **Yes** — with the separately-held key/mapping | **No** (by design) |
| Still "personal data" under GDPR? | **Yes** — GDPR explicitly treats pseudonymized data as personal data | **No** — truly anonymized data falls *outside* GDPR |
| Typical techniques | Tokenization, deterministic encryption, keyed hashing (HMAC) | Aggregation, generalization, suppression, k-anonymity, l-diversity, differential privacy, adding noise |
| Use case | Reduce risk while retaining utility & the ability to link records | Publish/share datasets, long-term analytics where individuals must never be re-identifiable |

> **Beginner aside — k-anonymity / l-diversity / differential privacy.**
> - **k-anonymity:** each record is indistinguishable from at least *k−1* others on the "quasi-identifiers" (age, ZIP, gender) — so you can't narrow down to one person. Weakness: if all k share the same sensitive value, you still learn it.
> - **l-diversity:** strengthens k-anonymity by requiring at least *l* distinct sensitive values within each group.
> - **Differential privacy:** add calibrated mathematical noise so that any individual's presence/absence in the dataset changes query results negligibly — gives a provable privacy guarantee (parameter ε, "epsilon"; smaller = more private). Used by Apple, US Census.

**Why this matters in practice:** teams constantly *claim* anonymization but actually do pseudonymization. If a mapping exists anywhere (even "encrypted, in a vault"), it's pseudonymization and the data is still regulated. True anonymization is hard and often destroys analytic value — be honest about which you have.

### 2.7 Data residency & sovereignty

- **Data residency:** *where* (which country/region) data is physically stored.
- **Data sovereignty:** the idea that data is subject to the laws of the country where it resides.
- **Cross-border transfer rules:** GDPR restricts sending personal data outside the EU/EEA unless there's an adequacy decision or safeguards (Standard Contractual Clauses — **SCCs**, or Binding Corporate Rules — BCRs). Some countries (Russia, China, India for certain data) mandate local storage ("data localization").

Architecturally this drives **region-pinned storage**: route an EU user's data to EU infrastructure and never replicate it elsewhere. We'll see patterns in §5.

---

## 3. How it works internally

This section traces the *actual mechanics* of the core controls. Read it as the heart of the chapter.

### 3.1 Data classification pipeline (the foundation everything else rests on)

You cannot protect what you haven't classified. Internal workflow:

1. **Inventory / data discovery.** Enumerate every store (DBs, queues, caches, object stores, logs, data lake, third-party SaaS). Tools: cloud data-classification scanners (AWS **Macie**, GCP **DLP/Sensitive Data Protection**, Azure **Purview**), or regex/ML scanners over schemas and samples. Output: a **data map / Record of Processing Activities (RoPA)** — for GDPR Art. 30 you're *legally required* to maintain this.
2. **Classify each field** into a tier, e.g.:
   - **Tier 0 — Public** (marketing copy).
   - **Tier 1 — Internal** (non-sensitive operational).
   - **Tier 2 — Confidential / PII** (email, name, address).
   - **Tier 3 — Restricted** (PHI, SPI, **cardholder data**, secrets).
3. **Attach policy to tier:** encryption requirement, who may access, retention period, allowed regions, whether it may appear in logs/test data.
4. **Tag at the code/schema level** so enforcement is automatable (annotations, schema metadata, column comments, a data catalog).
5. **Continuously re-scan** — new code adds new fields; scanners catch PII leaking into a `notes` column or a log line.

> **Why "classify first":** every downstream control (encryption, masking, retention, residency, DSAR fulfillment) is *parameterized by classification*. Get this wrong and you under-protect restricted data or over-encrypt public data (wasting performance and queryability).

### 3.2 PCI scope reduction & tokenization — the senior move

This is the single most important *architectural* idea in the PCI half of the chapter, so we go slow.

**The problem.** PCI DSS applies to your **CDE** — the **Cardholder Data Environment**: *every* system component that stores, processes, or transmits cardholder data, **plus** every system connected to or that could impact the security of those. If your monolith touches a PAN, the *entire monolith and everything that talks to it* is "in scope" and must satisfy ~300 PCI controls (firewalls, logging, vuln scans, pen tests, access reviews, etc.). That's enormous cost and audit burden.

**The senior move: shrink the CDE to almost nothing.** The goal is that *your* systems essentially never see a raw PAN. Mechanisms:

1. **Tokenization.** Replace the PAN with a **token** — a surrogate value with no exploitable mathematical relationship to the real PAN. The mapping (token ↔ PAN) lives only inside a hardened **token vault** (often run by your payment provider). Your apps store and pass around *tokens*; the real PAN lives only in the vault and at the PSP.

   > **Beginner aside — token vs encrypted PAN.** Encryption is reversible *with the key* — anyone who steals ciphertext + key gets the PAN. A token has *no algorithmic relationship* to the PAN; the only way back is a lookup in the vault. So even a full dump of your token table reveals nothing. Tokens can be **format-preserving** (look like a card number, keep last 4) so they slot into existing schemas.

2. **Hosted fields / iframes / redirect.** The cardholder's browser sends the PAN *directly* to the PSP (e.g., Stripe Elements, Adyen Components, Braintree Hosted Fields), never to your server. The PSP returns a token. Your backend only ever sees the token. This can drop you to **SAQ A** (the lightest self-assessment level — see §4.4).

3. **PSP / payment gateway.** A **PSP** (Payment Service Provider) like Stripe, Adyen, Braintree, Checkout.com handles the actual PAN, authorization, and storage, and is itself PCI Level 1 certified. You integrate via their SDK and store their tokens (e.g., `cus_…`, `pm_…`).

**Internal flow of a tokenized card capture (hosted fields):**

```
[Browser] --PAN over TLS--> [PSP hosted field / iframe]
[PSP] --token + last4 + brand--> [Browser JS]
[Browser] --token--> [Your backend API]
[Your backend] --token--> [Your DB]        (no PAN ever touched your server)
[Your backend] --token + amount--> [PSP] --> authorize/charge
[PSP] --de-tokenize internally--> card networks --> issuer
```

Result: the *only* place raw PAN exists is the browser→PSP hop and the PSP's vault. Your CDE shrinks to "the JS that loads the iframe and the API endpoint that forwards a token" — qualifying for the minimal **SAQ A**.

**Step-by-step of a token vault lookup (when you run your own — rare, advanced):**
1. App sends PAN to the tokenization service over mTLS.
2. Service validates PAN (Luhn), checks for existing token (deterministic) or generates a random one.
3. Service stores `{token → encrypted PAN}` where PAN is encrypted via envelope encryption (DEK/KEK in an HSM).
4. Returns token + masked PAN (first6/last4).
5. To charge later, the *vault* (not your app) submits the real PAN to the network. Your app never re-reads the PAN.

### 3.3 Audit logging & tamper-evidence

**Goal:** an immutable, attributable record of *who did what to which sensitive data, when, and from where* — that you can *prove* wasn't altered after the fact (auditors and forensics require this; PCI DSS Req. 10 mandates it).

**What to log (access/security events):** authentication successes/failures, access to cardholder data, privileged actions, changes to access control, deletions, exports, and DSAR fulfillments. **What NOT to log:** the sensitive data itself (no PANs, no CVVs, no full PII in log lines).

**Tamper-evidence mechanisms (from cheap to strong):**

1. **Append-only + WORM storage.** Write-Once-Read-Many object storage (e.g., S3 Object Lock in compliance mode) so logs can't be modified/deleted even by admins, for a retention window.
2. **Hash chaining (a "blockchain-lite").** Each log entry stores `hash(entry || previous_hash)`. Changing any past entry breaks the chain for all subsequent entries — instantly detectable. This is *tamper-evident* (you can detect tampering) not *tamper-proof* (you can't prevent it).
3. **Periodic anchoring / notarization.** Periodically publish the latest chain hash to an external, independent system (a separate account, a timestamping authority, even a public ledger) so an attacker who controls your logging system still can't rewrite history undetected.
4. **Cryptographic signing.** Sign each entry (or each batch) with a key the logging service holds but log producers don't — proves origin and integrity.
5. **Ledger databases.** Purpose-built immutable, cryptographically verifiable ledgers (e.g., **Amazon QLDB** — Quantum Ledger Database — append-only with a Merkle-tree-backed verifiable journal; note vendor/version specifics and check current availability before adopting).

> **Beginner aside — Merkle tree.** A tree of hashes where each leaf is the hash of a data block and each parent is the hash of its children. The single **root hash** summarizes the whole dataset; any change to any block changes the root. Lets you prove "this entry is in the log and hasn't changed" efficiently. Same idea underlies Git and blockchains.

**Internal flow of a tamper-evident write:**
1. Application emits a structured security event (actor, action, resource, classification, timestamp, source IP, request ID).
2. Logging service appends; computes `chain_hash = H(canonical(event) || prev_chain_hash)`.
3. Stores the event + its `chain_hash` in append-only storage.
4. Every N minutes, the current `chain_hash` is signed and shipped to the independent anchor.
5. Verification job periodically recomputes the chain and compares to anchored hashes; alerts on mismatch.

### 3.4 Retention & deletion engine

1. Each record (or class) carries a **retention policy**: a purpose, a clock-start event (e.g., account closure), and a TTL (time-to-live).
2. A scheduled job ("data lifecycle manager") scans for records past their retention and **purges or anonymizes** them.
3. Deletion must cascade to **all copies**: primary DB, read replicas, caches, search indexes, message queues, data warehouse, backups, and third-party processors.
4. Backups are the hard part (you can't surgically delete one record from a tape/snapshot). The standard answer: **crypto-shredding** (§3.6) and/or short backup retention windows so deleted data ages out of backups quickly.

### 3.5 DSAR / data-subject rights fulfillment

> **Beginner aside — DSAR.** Data Subject Access Request: a person exercising a GDPR right — to **access** ("give me a copy of my data"), **erasure** ("delete my data" — the *right to be forgotten*, Art. 17), **portability** ("give it to me in a machine-readable format so I can move it"), **rectification** (correct it), or **restriction/objection**. You typically have **one month** to respond under GDPR (extendable to three for complex requests).

Internal workflow:
1. **Identity verification** — confirm the requester is the data subject (avoid handing one user's data to another).
2. **Discovery** — query every system holding that subject's data, keyed by a stable **subject identifier**. This is *only feasible* if you maintained a data map (§3.1) and a consistent linking key.
3. **Action:**
   - *Access/portability:* assemble all data, export as JSON/CSV.
   - *Erasure:* delete or crypto-shred across all stores, *unless* you have a lawful basis to retain (e.g., tax records, fraud prevention, legal hold) — document the exception.
4. **Confirm & log** the fulfillment (in your tamper-evident audit log).

The architectural lesson: **right-to-be-forgotten is a design constraint, not a feature you bolt on.** If you don't know everywhere a user's data lives, you cannot fulfill erasure. This is why immutable/event-sourced systems need a special trick:

### 3.6 Right-to-be-forgotten in immutable / event-sourced systems → crypto-shredding

> **Beginner aside — event sourcing.** Instead of storing current state, you store an *append-only log of events* ("UserRegistered", "EmailChanged", "OrderPlaced") and rebuild state by replaying them. The log is **immutable by design** — that's the whole point (auditability, time travel, rebuild projections). Kafka-based systems, ledgers, and audit logs share this property.

**The conflict:** GDPR says "delete this person's data"; event sourcing says "events are immutable." You *can't* (and often *mustn't*) rewrite history.

**The solution — crypto-shredding (a.k.a. crypto-erasure):**
1. Encrypt each data subject's personal data with a **per-subject key** (one DEK per user).
2. Store the *ciphertext* in the immutable event log; store the *key* in a separate, mutable key store.
3. To "forget" the subject: **delete their key.** The ciphertext remains in the immutable log but is now permanently undecryptable — effectively, irreversibly anonymized. The event structure, ordering, and non-personal fields survive (so downstream aggregates/counts still work); the personal payload is gone forever.

```
Event log (immutable):   [evt #102 | userId=hash | encrypted-payload=<gibberish without key>]
Key store (mutable):     userId -> DEK   <-- DELETE THIS to forget
After shred:             ciphertext is unrecoverable; event ordering & non-PII intact
```

This is *the* canonical senior answer to "how do you do right-to-be-forgotten in Kafka/event-sourced systems." We give a full Java implementation in §5.4.

**Caveats to flag:** (1) crypto-shredding is widely accepted but its legal sufficiency for "erasure" is a judgment call — document it; some regulators may scrutinize. (2) You must ensure the key isn't lingering in backups/caches. (3) Anything cached *decrypted* (projections, search indexes) must also be purged.

---

## 4. The complete toolkit

### 4.1 Java cryptography & secrets toolkit

| Tool / API | Purpose | Key parameters / notes | Default / recommendation |
|---|---|---|---|
| `javax.crypto.Cipher` | Symmetric/asymmetric encrypt/decrypt | transformation string e.g. `"AES/GCM/NoPadding"` | Prefer **AES-256-GCM**; 12-byte random IV; 128-bit auth tag |
| `SecretKeySpec` / `KeyGenerator` | Build/generate symmetric keys | `KeyGenerator.getInstance("AES")`, `init(256)` | 256-bit keys |
| `SecureRandom` | Cryptographically strong randomness (IVs, salts, tokens) | use no-arg `new SecureRandom()` (auto-seeded) | **Never** use `java.util.Random` for security |
| `MessageDigest` | Hashing | `SHA-256`, `SHA-512` | Don't use MD5/SHA-1; for passwords use a KDF, not a bare hash |
| `Mac` (HMAC) | Keyed hashing (keyed pseudonymization, integrity) | `"HmacSHA256"` | Good for deterministic, keyed tokens |
| Password hashing | Store passwords | **Argon2id** (preferred), **bcrypt**, **scrypt**, PBKDF2 | Use a vetted lib (e.g., Spring Security `Argon2PasswordEncoder` / `BCryptPasswordEncoder`) |
| `KeyStore` (JCEKS/PKCS12) | Local key storage | password-protected | Prefer a KMS/HSM over local keystores for prod |
| **JCA/JCE** | Java Cryptography Architecture/Extension — the provider framework | pluggable providers (SunJCE, BouncyCastle) | Add **BouncyCastle** for Argon2, extra algos |
| Cloud KMS SDKs | Envelope encryption, key mgmt | AWS KMS `GenerateDataKey`/`Decrypt`; GCP KMS; Azure Key Vault | Use **DEK/KEK** pattern; never export the KEK |
| Secrets managers | Store DB creds, API keys, certs | HashiCorp **Vault**, AWS Secrets Manager, GCP Secret Manager | Rotate; inject at runtime, never in code/env files committed to git |

> **Beginner aside — IV / salt / nonce.** An **IV** (Initialization Vector) or **nonce** ("number used once") is a random value mixed into encryption so that encrypting the same plaintext twice yields different ciphertext. For GCM the IV must be **unique per key** (reuse is catastrophic — breaks confidentiality and integrity). A **salt** is random data added before hashing so identical inputs hash differently (defeats precomputed "rainbow table" attacks).

### 4.2 TLS / transport toolkit

| Tool | Purpose | Notes |
|---|---|---|
| TLS 1.2 / 1.3 | Encrypt data in transit | Disable TLS 1.0/1.1, SSLv3; prefer 1.3 |
| mTLS | Mutual auth between services | Common in service meshes (Istio, Linkerd) |
| HSTS header | Force HTTPS in browsers | `Strict-Transport-Security` |
| Cipher suite config | Choose strong AEAD ciphers | e.g., `TLS_AES_256_GCM_SHA384`, ECDHE for forward secrecy |
| Cert management | Issue/rotate certs | ACME/Let's Encrypt, cert-manager (k8s), internal CA |

> **Beginner aside — forward secrecy.** With **PFS** (Perfect Forward Secrecy, via ephemeral key exchange like ECDHE), recording today's encrypted traffic and stealing the server's private key *later* still won't decrypt it — each session uses a fresh ephemeral key.

### 4.3 Privacy/PCI platform tooling

| Category | Tools (examples) | Purpose |
|---|---|---|
| Data discovery & classification | AWS Macie, GCP Sensitive Data Protection (DLP), Azure Purview, BigID, OneTrust | Find & tag PII/PHI/PAN across stores |
| Tokenization / vault | Stripe, Adyen, Braintree, VGS (Very Good Security), Skyflow, Basis Theory, HashiCorp Vault Transform | Replace PAN/PII with tokens; shrink PCI scope |
| Consent & DSAR mgmt | OneTrust, Osano, Transcend, Ketch | Manage consent, automate DSAR fulfillment |
| Secrets mgmt | HashiCorp Vault, AWS/GCP/Azure secret stores | Store/rotate keys & creds |
| KMS / HSM | AWS KMS/CloudHSM, GCP KMS, Azure Key Vault, Thales/Entrust HSM | Manage encryption keys |
| Audit/SIEM | Splunk, Elastic, Datadog, AWS CloudTrail, GuardDuty | Collect, monitor, alert on security events |
| Vuln scanning / pentest (PCI Req) | ASV scans (Qualys, Tenable), internal scanners, annual pentest | Required by PCI DSS |

> **Beginner aside — SIEM / ASV.** **SIEM** = Security Information and Event Management — central system that ingests logs and detects/alerts on suspicious patterns. **ASV** = Approved Scanning Vendor — a PCI-sanctioned vendor that runs the *required* quarterly external vulnerability scans.

### 4.4 PCI DSS structure & SAQ types

PCI DSS (v4.0/4.0.1 is current as of this writing — **verify the active version and its deadlines**, as v3.2.1 was retired) is organized into **12 requirements** under 6 goals:

| # | Requirement (paraphrased) |
|---|---|
| 1 | Install & maintain network security controls (firewalls) |
| 2 | Apply secure configurations (no vendor defaults) |
| 3 | Protect stored account data (encrypt/tokenize/truncate; **don't store SAD**) |
| 4 | Encrypt cardholder data in transit over open networks |
| 5 | Protect against malware |
| 6 | Develop & maintain secure systems/software |
| 7 | Restrict access by business need-to-know |
| 8 | Identify users & authenticate access (incl. **MFA**) |
| 9 | Restrict physical access |
| 10 | Log & monitor all access to network & cardholder data |
| 11 | Test security of systems/networks regularly |
| 12 | Maintain an information security policy |

**Merchant levels** are by annual card transaction volume (Level 1 highest, ~>6M/yr → requires an external **QSA** audit + **ROC**; Levels 2–4 typically self-assess via an **SAQ**).

> **Beginner aside — QSA / ROC / AOC / SAQ.** **QSA** = Qualified Security Assessor (auditor). **ROC** = Report on Compliance (the audit report). **AOC** = Attestation of Compliance (the signed summary). **SAQ** = Self-Assessment Questionnaire (for smaller merchants).

| SAQ type | When it applies | Scope/burden |
|---|---|---|
| **SAQ A** | Fully outsourced card handling — e.g., PSP hosted fields/redirect; you never touch CHD | **Lowest** (~20-ish controls) |
| SAQ A-EP | E-commerce where your site *influences* the payment page (e.g., JS that posts to PSP) but doesn't receive CHD on your server | Medium |
| SAQ B / B-IP | Standalone terminals (dial-out or IP) | Low-medium |
| SAQ C / C-VT | Payment app connected to internet / virtual terminal | Medium |
| SAQ D | Everything else — you store/process/transmit CHD yourself | **Highest** (all 12 reqs) |

**The whole game of scope reduction = move from SAQ D toward SAQ A.**

---

## 5. Code examples by use case

All examples are Java-first, idiomatic, and adaptable. Non-obvious lines are commented.

### 5.1 Field-level encryption with envelope encryption (AES-256-GCM + KMS-style DEK/KEK)

Use case: encrypt a PII field (e.g., national ID) before storing in the DB, using a KMS to wrap the DEK.

```java
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Envelope encryption: data is encrypted with a per-record DEK;
 * the DEK is wrapped (encrypted) by a KEK held in a KMS/HSM.
 * We store: [wrappedDEK][iv][ciphertext+GCMtag] together.
 */
public final class FieldCrypto {

    private static final int GCM_IV_BYTES = 12;     // 96-bit IV recommended for GCM
    private static final int GCM_TAG_BITS = 128;    // 128-bit auth tag
    private static final SecureRandom RNG = new SecureRandom();

    private final Kms kms; // abstraction over AWS KMS / GCP KMS / Vault Transit

    public FieldCrypto(Kms kms) { this.kms = kms; }

    /** Encrypt one field value. Returns an opaque, storable blob. */
    public byte[] encrypt(byte[] plaintext, String keyId) throws Exception {
        // 1. Ask KMS for a fresh DEK: returns plaintext DEK + KMS-wrapped DEK.
        Kms.DataKey dek = kms.generateDataKey(keyId); // KEK never leaves the KMS

        // 2. Encrypt the data locally with the plaintext DEK using AES-256-GCM.
        byte[] iv = new byte[GCM_IV_BYTES];
        RNG.nextBytes(iv); // unique IV per encryption — MUST NOT repeat for a given key
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(dek.plaintext(), "AES");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plaintext); // includes the GCM auth tag

        // 3. Zero the plaintext DEK from memory ASAP (defense in depth).
        Arrays.fill(dek.plaintext(), (byte) 0);

        // 4. Pack [len(wrappedDEK)][wrappedDEK][iv][ct] for storage.
        return pack(dek.wrapped(), iv, ct);
    }

    public byte[] decrypt(byte[] blob, String keyId) throws Exception {
        Unpacked u = unpack(blob);
        // 1. Ask KMS to unwrap the DEK (KEK stays in the KMS).
        byte[] dekBytes = kms.decryptDataKey(keyId, u.wrappedDek());
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dekBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, u.iv()));
            return c.doFinal(u.ciphertext()); // throws AEADBadTagException if tampered
        } finally {
            Arrays.fill(dekBytes, (byte) 0); // wipe key material
        }
    }

    // --- trivial framing helpers (length-prefixed) ---
    private static byte[] pack(byte[] wrapped, byte[] iv, byte[] ct) {
        byte[] out = new byte[4 + wrapped.length + GCM_IV_BYTES + ct.length];
        int p = 0;
        out[p++] = (byte) (wrapped.length >>> 24); out[p++] = (byte) (wrapped.length >>> 16);
        out[p++] = (byte) (wrapped.length >>> 8);  out[p++] = (byte) (wrapped.length);
        System.arraycopy(wrapped, 0, out, p, wrapped.length); p += wrapped.length;
        System.arraycopy(iv, 0, out, p, GCM_IV_BYTES);        p += GCM_IV_BYTES;
        System.arraycopy(ct, 0, out, p, ct.length);
        return out;
    }
    private record Unpacked(byte[] wrappedDek, byte[] iv, byte[] ciphertext) {}
    private static Unpacked unpack(byte[] b) {
        int len = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        int p = 4;
        byte[] wrapped = Arrays.copyOfRange(b, p, p + len); p += len;
        byte[] iv = Arrays.copyOfRange(b, p, p + GCM_IV_BYTES); p += GCM_IV_BYTES;
        byte[] ct = Arrays.copyOfRange(b, p, b.length);
        return new Unpacked(wrapped, iv, ct);
    }

    /** KMS abstraction — back this with AWS KMS GenerateDataKey/Decrypt etc. */
    interface Kms {
        record DataKey(byte[] plaintext, byte[] wrapped) {}
        DataKey generateDataKey(String keyId);     // returns plaintext + KEK-wrapped DEK
        byte[] decryptDataKey(String keyId, byte[] wrapped);
    }
    private static byte[] dummy() { return new byte[0]; }
}
```

Why it matters: rotating the KEK in the KMS instantly re-secures *all* records without re-encrypting them; a DB dump alone is useless without KMS access; GCM detects tampering (`AEADBadTagException`).

### 5.2 Tokenization client — never let the PAN hit your DB

Use case: capture a card via a PSP and store only the token. (Pseudo-real Stripe-style flow; adapt to your PSP SDK.)

```java
/**
 * The browser already exchanged the raw PAN with the PSP (hosted fields)
 * and handed us a single-use token. Our server NEVER sees the PAN.
 */
public class PaymentService {

    private final PspClient psp;            // e.g., Stripe/Adyen SDK wrapper
    private final CardRepository cards;      // stores ONLY tokens + masked data

    public PaymentService(PspClient psp, CardRepository cards) {
        this.psp = psp; this.cards = cards;
    }

    /** Persist a saved payment method using the PSP token. */
    public StoredCard saveCard(String customerId, String singleUseToken) {
        // Exchange the one-time token for a durable, reusable payment-method token.
        PspPaymentMethod pm = psp.attachPaymentMethod(customerId, singleUseToken);

        // We store ONLY: provider token + non-sensitive display fields.
        // We do NOT store PAN, CVV, or full expiry beyond what PSP returns for display.
        StoredCard card = new StoredCard(
                pm.id(),                 // e.g. "pm_1Abc..."  <-- the token
                pm.brand(),              // "visa"
                pm.last4(),              // "4242"  (display only, allowed)
                pm.expMonth(), pm.expYear());
        return cards.save(card);
    }

    /** Charge later using the stored token — the PSP de-tokenizes internally. */
    public ChargeResult charge(String customerId, String pmToken, long amountMinor, String currency) {
        // Idempotency key prevents double-charging on retries.
        return psp.createPaymentIntent(customerId, pmToken, amountMinor, currency,
                /* idempotencyKey */ java.util.UUID.randomUUID().toString());
    }
}

/** Note: there is intentionally NO field anywhere for PAN or CVV. */
record StoredCard(String token, String brand, String last4, int expMonth, int expYear) {}
```

The point: the entire class is built so that *there is no place to put a PAN*. That's scope reduction expressed in code — this design qualifies you for SAQ A.

### 5.3 Keyed pseudonymization (HMAC) vs masking for display

Use case: produce a deterministic pseudonym for analytics joins (still personal data) and a masked value for UI.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class Pseudonymizer {
    private final SecretKeySpec hmacKey; // kept in KMS/Vault, separate from data

    public Pseudonymizer(byte[] keyMaterial) {
        this.hmacKey = new SecretKeySpec(keyMaterial, "HmacSHA256");
    }

    /**
     * Deterministic, keyed pseudonym: same input -> same token (joinable),
     * but irreversible WITHOUT the key. Still 'personal data' under GDPR
     * because holding the key re-identifies. Use a per-purpose key.
     */
    public String pseudonym(String identifier) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] d = mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8));
            return "pid_" + HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Masking is for DISPLAY only — NOT a storage-protection control. */
    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }
    public static String maskPan(String pan) { // "first6 …. last4" display rule
        if (pan == null || pan.length() < 10) return "****";
        return pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
    }
}
```

Critical nuance: HMAC pseudonymization is **reversible by anyone with the key** *and* vulnerable to brute-force if the input space is small (e.g., phone numbers, emails) — an attacker who steals the key can re-derive everything, and even without the key can confirm guesses. Don't oversell it as anonymization.

### 5.4 Crypto-shredding for right-to-be-forgotten in an event-sourced system

Use case: Kafka/event-sourced store where events are immutable; we must support GDPR erasure.

```java
/**
 * Per-subject crypto-shredding.
 * - Each user has a unique DEK.
 * - Personal fields in events are encrypted with that user's DEK.
 * - Erasure = delete the user's DEK -> all their historical event payloads
 *   become permanently undecryptable, while event ordering/non-PII survives.
 */
public class CryptoShredStore {

    private final KeyStore keyStore;     // mutable: subjectId -> DEK (deletable)
    private final EventLog eventLog;     // immutable, append-only (e.g., Kafka topic)
    private final FieldCrypto crypto;    // AES-GCM as in 5.1, but DEK is per-subject

    public CryptoShredStore(KeyStore ks, EventLog log, FieldCrypto crypto) {
        this.keyStore = ks; this.eventLog = log; this.crypto = crypto;
    }

    /** Append an event whose personal payload is encrypted with the subject's DEK. */
    public void append(String subjectId, String type, byte[] personalPayload, Object nonPii) {
        byte[] dek = keyStore.getOrCreateDek(subjectId); // creates DEK on first write
        byte[] encrypted = crypto.encryptWithDek(personalPayload, dek);
        eventLog.append(new StoredEvent(
                subjectId,        // a stable, non-PII pseudonymous id is best here
                type,
                encrypted,        // gibberish once the DEK is gone
                nonPii,           // counts/aggregates remain usable forever
                System.currentTimeMillis()));
    }

    /** Read: only works while the DEK still exists. */
    public byte[] readPersonal(StoredEvent e) {
        byte[] dek = keyStore.getDek(e.subjectId());
        if (dek == null) {
            // Subject was forgotten — payload is permanently unrecoverable.
            throw new ForgottenSubjectException(e.subjectId());
        }
        return crypto.decryptWithDek(e.encryptedPayload(), dek);
    }

    /**
     * GDPR Art.17 erasure for event-sourced data:
     * delete the key, not the (immutable) events.
     */
    public void forget(String subjectId) {
        keyStore.deleteDek(subjectId);   // <-- the irreversible step
        // Also purge anywhere the data was cached DECRYPTED:
        invalidateProjections(subjectId);   // read models / materialized views
        invalidateSearchIndex(subjectId);   // e.g., Elasticsearch docs
        invalidateCaches(subjectId);        // Redis, in-memory
        // And record the erasure in the tamper-evident audit log.
        AuditLog.record("ERASURE", subjectId, "crypto-shred: DEK deleted");
    }

    private void invalidateProjections(String id) { /* ... */ }
    private void invalidateSearchIndex(String id)  { /* ... */ }
    private void invalidateCaches(String id)        { /* ... */ }

    record StoredEvent(String subjectId, String type, byte[] encryptedPayload,
                       Object nonPii, long ts) {}
}
```

This is the reference answer to the classic interview question. Note the *non-PII fields stay in the clear* so aggregates (revenue, order counts) still compute after a user is forgotten — and you still must scrub *decrypted copies* in projections, caches, and indexes.

### 5.5 Tamper-evident hash-chained audit log

Use case: immutable, verifiable security log for PCI Req. 10.

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Append-only audit log where each entry chains to the previous via a hash. */
public class HashChainedAuditLog {

    private volatile String prevHash = "GENESIS"; // anchor for the very first entry
    private final AppendOnlyStore store;           // WORM storage (e.g., S3 Object Lock)

    public HashChainedAuditLog(AppendOnlyStore store) { this.store = store; }

    public synchronized AuditEntry record(String actor, String action,
                                          String resource, String classification) {
        // Canonical, sensitive-data-free representation.
        String canonical = String.join("|",
                String.valueOf(System.currentTimeMillis()),
                actor, action, resource, classification, prevHash);
        String hash = sha256(canonical);
        AuditEntry e = new AuditEntry(canonical, hash, prevHash);
        store.append(e);   // immutable write
        prevHash = hash;    // chain forward
        return e;
    }

    /** Verify integrity: recompute the whole chain; any break = tampering. */
    public boolean verify() {
        String expectedPrev = "GENESIS";
        for (AuditEntry e : store.readAll()) {
            if (!e.prevHash().equals(expectedPrev)) return false;          // broken link
            if (!e.hash().equals(sha256(e.canonical()))) return false;     // altered entry
            expectedPrev = e.hash();
        }
        return true;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    record AuditEntry(String canonical, String hash, String prevHash) {}
}
```

Strengthen further by periodically signing `prevHash` and shipping it to an independent anchor (so even an attacker who controls this service can't silently rewrite history).

### 5.6 PII-safe logging — redaction filter

Use case: guarantee PANs/emails/CVVs never land in logs (PCI Req. 3/10; GDPR data minimization).

```java
import java.util.regex.Pattern;

/** Redact sensitive patterns before anything is written to logs. */
public final class LogRedactor {
    // 13–19 digit sequences that pass Luhn are likely PANs.
    private static final Pattern PAN = Pattern.compile("\\b\\d{13,19}\\b");
    private static final Pattern CVV = Pattern.compile("(?i)\\b(cvv|cvc|cid)\\b\\s*[:=]?\\s*\\d{3,4}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public static String redact(String msg) {
        if (msg == null) return null;
        msg = PAN.matcher(msg).replaceAll(m -> luhn(m.group()) ? "[REDACTED_PAN]" : m.group());
        msg = CVV.matcher(msg).replaceAll("[REDACTED_CVV]");
        msg = EMAIL.matcher(msg).replaceAll(m -> {
            String e = m.group(); int at = e.indexOf('@');
            return e.charAt(0) + "***" + e.substring(at);
        });
        return msg;
    }

    private static boolean luhn(String s) { // reduce false positives (random 16-digit ids)
        int sum = 0; boolean alt = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            int n = s.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n; alt = !alt;
        }
        return sum % 10 == 0;
    }
}
```

Wire this into your logging framework as a Logback/Log4j2 pattern converter or message filter so redaction is *automatic*, not dependent on each developer remembering.

### 5.7 Region-pinned (data-residency) routing

Use case: keep EU users' personal data in EU infrastructure.

```java
/** Choose the storage/region for a user's data based on their residency. */
public class ResidencyRouter {

    enum Region { EU, US, IN }   // each maps to region-local DB + KMS + storage

    private final java.util.Map<Region, DataStore> stores;

    public ResidencyRouter(java.util.Map<Region, DataStore> stores) { this.stores = stores; }

    public DataStore storeFor(User user) {
        Region r = switch (user.residencyCountry()) {
            case "DE", "FR", "IE", "ES", "IT" -> Region.EU; // EU/EEA -> EU region
            case "IN" -> Region.IN;                          // India localization
            default -> Region.US;
        };
        return stores.get(r); // data never leaves its region; replication is region-scoped
    }
}
```

The deeper architecture: separate DB clusters, KMS keys, and backups per region; replication and analytics pipelines are region-scoped; cross-region access goes through audited, minimized APIs (not bulk copies).

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Field-level encryption costs CPU and kills queryability.** You can't `WHERE email = ?` on randomly-encrypted columns. Options: (a) keep a *separate keyed-hash (HMAC) index column* for exact-match lookups (deterministic), accepting it's pseudonymized not anonymized; (b) **deterministic encryption** for equality search (weaker — reveals equality patterns); (c) leave non-sensitive fields plaintext and only encrypt the truly sensitive ones.
- **KMS round-trips add latency.** Cache *decrypted DEKs* in memory for a short TTL (e.g., seconds–minutes) with strict limits; never persist them. Use **DEK caching** SDK features (e.g., AWS Encryption SDK caching) to amortize KMS calls. Watch KMS request quotas and cost (per-request pricing).
- **GCM is fast** (AES-NI hardware acceleration on modern CPUs). The expensive part is usually the KMS call, not the AES.
- **Tokenization** moves cost to the PSP/vault; round-trips per charge but typically negligible vs network latency to the PSP anyway.

### 6.2 Correctness & concurrency

- **IV/nonce uniqueness is non-negotiable for GCM.** Reusing an (key, IV) pair leaks plaintext XOR and breaks integrity. Use a fresh random 96-bit IV per encryption (random collision probability is negligible) or a strictly monotonic counter per key.
- **Don't roll your own crypto.** Use vetted libraries (JCA, BouncyCastle, Google Tink, AWS Encryption SDK). **Google Tink** in particular gives misuse-resistant APIs (handles IVs, modes, key rotation for you) — strongly recommended for app teams.
- **Authenticated encryption only** (GCM/ChaCha20-Poly1305). Never use ECB; avoid raw CBC without a MAC (padding-oracle attacks).
- **Key rotation:** support multiple key versions; tag ciphertext with the key id/version so you can decrypt old data while encrypting new data with the latest key.

### 6.3 Security

- **Defense in depth:** TLS in transit *and* encryption at rest *and* access control *and* logging — not one of them.
- **Least privilege on keys:** the service that *encrypts* often shouldn't be able to *decrypt* in bulk; use KMS key policies and grants. Separate the key store from the data store (the whole basis of crypto-shredding).
- **MFA** for all access to the CDE and to admin/KMS consoles (PCI Req. 8).
- **Secrets hygiene:** no secrets in code, env vars committed to git, or container images. Use a secrets manager; scan repos with tools like gitleaks/trufflehog.
- **Don't store SAD. Ever.** Re-audit logs, error traces, request dumps, APM payload capture, and message queues for accidental PAN/CVV capture — this is the #1 real-world PCI failure.

### 6.4 Observability (without leaking)

- Log security *events*, not sensitive *values*. Redact at the source (§5.6).
- Emit metrics: DSAR turnaround time, # of erasures, KMS error/latency, encryption coverage (% of restricted fields encrypted), audit-chain verification status.
- Alert on: access to CHD outside normal patterns, failed audit-chain verification, bulk exports, off-hours admin access.

### 6.5 Cost

- KMS: per-key + per-request pricing; DEK caching reduces requests.
- Tokenization/PSP: per-transaction or per-token fees, but usually *far* cheaper than the audit/operational cost of being SAQ D.
- Compliance itself: QSA audits, ASV scans, pentests are real recurring costs — scope reduction directly reduces them.

### 6.6 Testing

- **Never use production PII in test/staging.** Use synthetic data or properly anonymized data. Test card numbers are published by PSPs (e.g., `4242 4242 4242 4242`) — use those.
- Test: encryption round-trips, tamper detection (mutate ciphertext → expect failure), audit-chain verification (mutate an entry → `verify()` returns false), crypto-shred (delete key → read throws), DSAR export completeness, retention purge jobs.
- **Tabletop the breach response** annually; **dry-run a DSAR** end-to-end to find systems you forgot you had.

### 6.7 Production hardening

- WORM/Object-Lock for audit logs; short backup retention to bound crypto-shred/erasure exposure.
- Network segmentation: isolate the CDE on its own VPC/subnet with strict firewall rules (PCI Req. 1) — this also *reduces scope*.
- Quarterly ASV scans, annual pentest, regular access reviews (de-provision leavers promptly).
- Breach playbook: GDPR requires notifying the supervisory authority within **72 hours** of becoming aware of a personal-data breach; PCI/contracts have their own notification clocks.

### 6.8 Anti-patterns (avoid)

| Anti-pattern | Why it's bad | Do instead |
|---|---|---|
| Storing the PAN "because it's easy" | Puts your whole stack in PCI scope | Tokenize; store PSP tokens |
| Storing CVV "to re-charge later" | **Explicitly forbidden** by PCI | Store a PSP payment-method token |
| Logging full requests/responses | PANs/CVVs/PII leak into logs/backups | Redact at source; structured logging |
| "We hashed it, so it's anonymous" | Keyed/unsalted hash is reversible/guessable → still personal data | Be honest: it's pseudonymization |
| Encrypting with one app-wide key, never rotated | Single point of failure; no crypto-shred | Envelope encryption, per-subject/per-tenant DEKs |
| Treating right-to-be-forgotten as a feature | Can't find all copies → can't comply | Maintain a data map; design for erasure |
| ECB mode / DIY crypto | Reveals patterns; subtle bugs | AEAD (GCM), use Tink/AWS Enc SDK |
| Prod data in test | Breach surface, illegal under GDPR | Synthetic/anonymized data |
| Mutable, deletable audit logs | Can be tampered post-incident | Append-only + hash chain + anchor |

---

## 7. Advanced topics & deep internals

### 7.1 Searchable & queryable encryption tradeoffs

- **Deterministic encryption (DET):** same plaintext → same ciphertext → supports equality joins/lookups, but leaks frequency (an attacker sees which rows share a value). Acceptable for low-cardinality non-sensitive joins; risky for PII.
- **Order-preserving / order-revealing encryption (OPE/ORE):** supports range queries but leaks order — generally *not* recommended for sensitive data; well-known leakage attacks exist.
- **Blind indexing:** store an HMAC of the plaintext in a separate index column for exact-match search while the value itself is randomly encrypted. Truncate the HMAC to trade a tiny false-positive rate for less frequency leakage.
- **Client-side field-level encryption (CSFLE):** e.g., MongoDB CSFLE/Queryable Encryption, where the driver encrypts before sending; Queryable Encryption supports equality/range on encrypted fields using structured encryption (note: vendor/version-specific; verify current capabilities).

### 7.2 Key management deep dive

- **Key hierarchy:** root (HSM) → KEK (KMS) → DEK (per record/tenant/subject). Each level limits blast radius.
- **Rotation strategies:** (a) rotate KEK only — cheap, re-wraps DEKs, no data re-encryption; (b) rotate DEKs — requires re-encryption (lazy on next write, or background job). Always version keys.
- **Crypto-shred granularity:** per-subject DEK = forget one user; per-tenant DEK = offboard a whole tenant by deleting one key (powerful for B2B SaaS).
- **Bring Your Own Key (BYOK) / Hold Your Own Key (HYOK):** customer-controlled keys for high-trust enterprise deals; deleting *their* key crypto-shreds *their* data — a strong contractual selling point.
- **Key escrow / recovery:** balance against the risk that losing a key = losing data. For crypto-shred keys, *no recovery is the point*; for general data keys, ensure durable, replicated KMS.

### 7.3 PCI DSS v4.0 notable changes (flag version-specific)

PCI DSS v4.0 (and v4.0.1) introduced, among others: a **customized approach** (meet the objective with your own controls, validated by a QSA) alongside the traditional "defined approach"; stronger authentication (MFA expansion, password length/strength updates); explicit requirements around **client-side script integrity** for payment pages (Req. 6.4.3 / 11.6.1 — guard against Magecart-style skimming via script inventory + change detection); and expanded targeted risk analyses. **Verify the exact current requirements and their effective dates** against the official PCI SSC documents, as deadlines have phased in.

> **Beginner aside — Magecart / e-skimming.** Attackers inject malicious JavaScript into a checkout page (often via a compromised third-party script) that silently exfiltrates card data the user types — even if your *server* never stores the PAN. This is why v4.0 added client-side script integrity controls and why **Content Security Policy (CSP)** and Subresource Integrity (SRI) matter for payment pages.

### 7.4 Tokenization internals & types

- **Vault-based tokenization:** central vault stores token↔PAN; tokens are random. Most secure, requires a lookup.
- **Vaultless / cryptographic tokenization:** token derived from PAN via a keyed, format-preserving transform — no big table, but security rests on the key (closer to encryption). **FPE** (Format-Preserving Encryption, e.g., NIST FF1/FF3-1) keeps output the same format/length as input; note FF3 had a published cryptanalysis weakness — prefer FF1 and check current guidance.
- **Single-use vs multi-use tokens:** single-use for one transaction; multi-use for stored cards / recurring billing.
- **Network tokenization:** the *card networks* (Visa/Mastercard) issue tokens (VTS/MDES) that auto-update on card reissue — improves auth rates and resilience; offered through PSPs.

### 7.5 GDPR fine print engineers hit

- **Lawful basis:** you need one of six (consent, contract, legal obligation, vital interests, public task, legitimate interests) to process personal data — *consent is not always required and is often the weakest* (must be freely given, specific, revocable).
- **DPIA** (Data Protection Impact Assessment): required for high-risk processing (large-scale SPI, systematic monitoring, new tech). A documented risk analysis.
- **Privacy by Design & by Default** (Art. 25): bake privacy into architecture; default to the most privacy-protective settings.
- **Processor obligations:** if you're a SaaS, you're a *processor* for your customers — you need DPAs, sub-processor disclosure, breach-notification SLAs, and the ability to support *their* DSARs.

### 7.6 Lesser-known behaviors

- **Backups defeat naive erasure.** A "deleted" user can resurrect from a restored backup unless you re-apply deletions post-restore or rely on crypto-shred + bounded backup retention.
- **Caches/CDNs/search indexes are shadow copies** of PII — your DSAR/erasure must cover them.
- **Pseudonymized data + a second dataset = re-identification.** The classic Netflix Prize and AOL search-log de-anonymizations show "anonymized" data often isn't. Linkage attacks are real.
- **GCM has a plaintext limit** per (key, IV): ~64 GiB. Irrelevant for fields, relevant for streaming/large blobs.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Protecting stored sensitive data: which control?

| Control | Reversible? | Queryable? | Best for | Use when | Avoid when |
|---|---|---|---|---|---|
| **Tokenization (vault)** | Only via vault | No (token only) | PANs, high-sensitivity PII | You want max scope reduction & no key risk in your app | You need to compute on the real value yourself |
| **Encryption (AES-GCM, random IV)** | Yes (with key) | No (without blind index) | PII you must retrieve | You need the real value back | You need server-side search on it |
| **Deterministic encryption** | Yes | Equality only | Low-risk join keys | Equality lookups needed | High-cardinality sensitive data (leaks) |
| **Keyed hash / HMAC (pseudonym)** | No (but key re-derives) | Equality only | Analytics linkage | Joinable, irreversible-ish ids | Small input space (brute-forceable) |
| **Truncation / masking** | No | No | Display | Showing last-4 to users | As your *only* storage protection (it's not) |
| **Anonymization (k-anon/DP)** | No | Aggregates | Publishing datasets | Individuals must never be re-identified | You need record-level utility |

### 8.2 Build vs buy for card handling

| Approach | PCI burden | Control/flexibility | Cost | Use when |
|---|---|---|---|---|
| PSP hosted fields (SAQ A) | **Lowest** | Low (PSP UI/flow) | Per-txn fees | Default for almost everyone |
| PSP + your UI posting to PSP (SAQ A-EP) | Medium | Medium | Per-txn fees | You need custom checkout UX |
| Tokenization proxy (VGS/Skyflow/Basis Theory) | Low–medium | High (multi-PSP, your data) | Platform fees | Multi-PSP, you want to own routing/data |
| Run your own CDE (SAQ D / Level 1) | **Highest** | Highest | Huge (audits, controls) | You *are* a payments company |

**Rule of thumb:** unless payments *are your product*, outsource PAN handling and aim for SAQ A.

### 8.3 Erasure strategy by storage type

| Storage | Erasure approach |
|---|---|
| Mutable RDBMS | Hard `DELETE` (+ cascade); overwrite/anonymize |
| Event log / Kafka / immutable ledger | **Crypto-shred** (delete per-subject key) |
| Backups/snapshots | Crypto-shred + short retention; re-apply deletes on restore |
| Search index (Elasticsearch) | Delete docs + reindex |
| Caches (Redis) / CDN | Invalidate keys / purge |
| Data warehouse / lake | Delete partitions / crypto-shred columns; track lineage |
| Third-party processors | Trigger via API / contractual obligation; track in DPA |

### 8.4 Pseudonymize vs anonymize decision

- **Use pseudonymization** when you still need to link records, re-identify under controlled conditions, or retain analytic utility — and accept it stays regulated.
- **Use anonymization** when you want data *outside* GDPR for sharing/long-term analytics and you can tolerate loss of record-level precision — and you've validated against linkage/re-identification.

---

## 9. Failure modes & debugging

### 9.1 Real-world incident archetypes

- **PAN/CVV in logs.** App logs full request bodies; a PAN and CVV land in centralized logs and backups → instant PCI violation and breach exposure. *Diagnose:* grep logs for Luhn-valid 13–19 digit strings and CVV patterns; *fix:* source-side redaction (§5.6), audit APM payload capture, purge affected log stores.
- **CVV stored "for convenience."** A `card_cvv` column appears in a schema migration. *Diagnose:* automated schema scan for forbidden columns in CI; *fix:* drop the column, rotate, never re-add — tokenize.
- **Stored data not actually decryptable after rotation.** Keys rotated without versioning; old ciphertext can't be decrypted. *Diagnose:* AEADBadTagException / KMS "key not found"; *fix:* version keys and tag ciphertext with key id (always).
- **IV reuse.** A bug seeds the IV from a constant or low-entropy source. *Diagnose:* identical ciphertext prefixes for identical plaintext under one key; *fix:* random 96-bit IV per op; use Tink to remove the footgun.
- **Erasure didn't reach a shadow copy.** User "deleted" but still in Elasticsearch / a backup / a partner system → regulator complaint. *Diagnose:* DSAR dry-run finds them; *fix:* extend deletion fan-out; maintain the data map.
- **Audit log tampered post-incident.** Attacker deleted their tracks. *Diagnose:* `verify()` on hash chain fails; anchored hash mismatch; *fix:* WORM + chaining + external anchoring *before* the incident (you can't add it after).
- **Cross-border leak.** Analytics pipeline copies EU data to a US warehouse. *Diagnose:* data-flow review / lineage tooling; *fix:* region-scoped pipelines, SCCs, or stop the transfer.
- **Magecart skimmer.** A third-party JS on checkout exfiltrates cards client-side even though your server is clean. *Diagnose:* CSP violation reports, script-integrity monitoring, network-tab review; *fix:* CSP + SRI + script inventory (PCI v4 reqs), minimize third-party scripts on payment pages.

### 9.2 Debugging toolkit

- **Encryption issues:** catch `AEADBadTagException` (tamper/wrong key/wrong IV); log key *ids* (never key material); verify via known-answer test vectors.
- **KMS issues:** check IAM/key policy, grants, quotas, region; CloudTrail for denied `Decrypt` calls; watch throttling.
- **TLS issues:** `openssl s_client -connect host:443 -tls1_2`; `nmap --script ssl-enum-ciphers`; check cert chain/expiry, protocol/cipher negotiation.
- **Data discovery:** run Macie/DLP scans; grep schemas and sample data for PII patterns; review new migrations in CI.
- **Audit integrity:** run chain `verify()`; compare to externally anchored hashes; alert on failure.
- **DSAR completeness:** maintain and test the subject-data inventory; periodic dry-runs.

### 9.3 Monitoring/alerting checklist

- Failed audit-chain verification → page immediately.
- Any KMS `Decrypt`/`GenerateDataKey` denied or spiking → investigate.
- Bulk export or off-hours access to restricted data → alert.
- New 13–19 digit (Luhn-valid) strings appearing in logs → block & alert.
- DSAR SLA breach risk (approaching the 1-month deadline) → escalate.
- CSP violations on payment pages → investigate for skimming.

---

## 10. Interview drill

**Q1. What's the difference between cardholder data and sensitive authentication data, and what are the storage rules?**
*Model answer:* CHD = PAN (+ name, expiry, service code) and *may* be stored if rendered unreadable (encryption/truncation/tokenization/strong hash). SAD = CVV/CVC, full track data, PIN/PIN block — these may transit during authorization but **must never be stored after authorization**, period.
- *Follow-up: Where do people accidentally store SAD?* Logs, error traces, request/response dumps, APM payload capture, message queues, caches, backups.
- *Follow-up: How do you render a stored PAN unreadable?* Tokenization, truncation, strong one-way hash of the *full* PAN with salt, or strong encryption with proper key management.
- *Follow-up: Is masking (first6/last4) a storage protection?* No — masking is for display; storage must use one of the above.

**Q2. Explain PCI scope reduction and how you'd achieve SAQ A.**
*Model answer:* PCI applies to the entire CDE — every system that stores/processes/transmits CHD plus connected systems. Scope reduction shrinks that set so your systems never touch raw PAN. Achieve SAQ A by fully outsourcing card capture to a PSP via hosted fields/iframe/redirect so the PAN goes browser→PSP directly; your backend only handles tokens.
- *Follow-up: Hosted fields vs A-EP?* Hosted fields (PAN never reaches your server) → SAQ A; if your page influences the payment form but doesn't receive CHD → SAQ A-EP (more controls).
- *Follow-up: Even with SAQ A, what client-side risk remains?* Magecart/e-skimming via malicious JS — mitigate with CSP, SRI, script integrity monitoring (PCI v4).

**Q3. Tokenization vs encryption — when and why?**
*Model answer:* Encryption is reversible with the key, so ciphertext+key = plaintext; a token has no algorithmic relationship to the PAN — only a vault lookup reverses it, so a stolen token table is worthless. Use tokenization to maximize scope reduction and remove key-compromise risk from your app; use encryption when *you* must retrieve the real value.
- *Follow-up: Vaultless tokenization?* Token derived via keyed FPE — no big table but security rests on the key, so it's closer to encryption.
- *Follow-up: Format-preserving encryption pitfalls?* FF3-1 had cryptanalytic weaknesses; prefer FF1 and verify current guidance.

**Q4. How do you implement GDPR right-to-be-forgotten in an event-sourced/immutable system?** *(senior-signal)*
*Model answer:* Crypto-shredding: encrypt each subject's personal data with a per-subject DEK stored separately from the immutable log; to forget, delete the DEK, rendering all their historical payloads permanently undecryptable while preserving event ordering and non-PII aggregates. Also purge decrypted copies in projections, caches, and indexes, and record the erasure in the audit log.
- *Follow-up: Why not just rewrite the log?* Immutability is the point (auditability, replay); rewriting breaks projections/consumers and may be impossible (ledgers).
- *Follow-up: Legal sufficiency?* Widely accepted but a judgment call — document it; ensure keys aren't lingering in backups.
- *Follow-up: How does this also help B2B offboarding?* Per-tenant DEK → delete one key to crypto-shred an entire tenant.

**Q5. Pseudonymization vs anonymization — and why does it matter legally?**
*Model answer:* Pseudonymization replaces identifiers with reversible tokens (mapping held separately) — *still personal data* under GDPR. Anonymization is irreversible such that re-identification isn't reasonably possible — falls *outside* GDPR. Teams routinely claim anonymization but actually pseudonymize.
- *Follow-up: Is hashing an email anonymization?* No — small/known input space makes it brute-forceable; keyed hash is reversible with the key. It's pseudonymization.
- *Follow-up: How would you truly anonymize for sharing?* Aggregation/generalization + k-anonymity/l-diversity or differential privacy, validated against linkage attacks.

**Q6. Design encryption at rest for a PII field that you also need to look up by exact value.** *(senior-signal)*
*Model answer:* Encrypt the value with AES-256-GCM and a per-record/tenant DEK via envelope encryption (KEK in KMS). For lookup, add a *separate blind-index column* = HMAC of the normalized plaintext with a key held in KMS; query by HMAC. This keeps the stored value randomly encrypted (no frequency leak in the value) while enabling equality search, accepting the index is pseudonymized.
- *Follow-up: Why not deterministic encryption directly?* It leaks value frequency across rows.
- *Follow-up: How do you handle key rotation?* Version keys; tag ciphertext with key id; rotate KEK cheaply (re-wrap DEKs) or re-encrypt lazily on write.

**Q7. What's envelope encryption and why use a DEK/KEK split?**
*Model answer:* Encrypt data with a per-scope DEK; encrypt the DEK with a KEK held in a KMS/HSM; store the wrapped DEK with the data. Benefits: the KEK never leaves the KMS, key rotation is cheap (rotate KEK, re-wrap DEKs, no bulk re-encryption), and per-scope DEKs enable crypto-shredding and limit blast radius.
- *Follow-up: How do you reduce KMS latency/cost?* Cache decrypted DEKs briefly in memory with strict TTL; use DEK caching SDKs.
- *Follow-up: What goes wrong with one app-wide key?* No crypto-shred, single point of failure, painful rotation.

**Q8. How do you make audit logs tamper-evident, and what's the difference from tamper-proof?**
*Model answer:* Hash-chain entries (`H(entry||prevHash)`) so altering any past entry breaks the chain; store append-only on WORM storage; periodically sign and anchor the latest hash to an independent system. Tamper-*evident* means you can *detect* changes; tamper-*proof* would mean preventing them — external anchoring + WORM gets you close to proof against an attacker who controls the logging system.
- *Follow-up: What must you never put in audit logs?* The sensitive data itself (PANs, CVVs, full PII).
- *Follow-up: Where does a Merkle tree fit?* Efficiently prove inclusion/integrity of entries; same idea as Git/blockchains; QLDB uses it.

**Q9. A user asks for erasure but you have backups and an Elasticsearch index. Walk me through it.** *(senior-signal)*
*Model answer:* Verify identity; consult the data map to enumerate every store; hard-delete from the primary DB; delete docs and reindex Elasticsearch; invalidate caches/CDN; trigger deletion at third-party processors; for immutable/backup data use crypto-shredding + bounded backup retention and re-apply deletions on any restore; document any lawful-basis retention exceptions; log the fulfillment. Note the 1-month GDPR deadline.
- *Follow-up: What if you legally must keep some data (tax/fraud)?* Retain the minimum under a documented lawful basis; erase the rest.
- *Follow-up: How do you guarantee you found everything?* Maintained RoPA/data map + periodic DSAR dry-runs.

**Q10. Data residency: how do you keep EU users' data in the EU?**
*Model answer:* Region-pin storage — separate DB clusters, KMS keys, backups, and analytics pipelines per region; route by user residency; scope replication to the region; cross-border access via minimized, audited APIs not bulk copies; for any necessary transfers use SCCs/adequacy. Watch shadow copies (caches, warehouses, logs) that silently cross borders.
- *Follow-up: What's data sovereignty vs residency?* Residency = where data sits; sovereignty = it's subject to that jurisdiction's laws.
- *Follow-up: Where do residency violations usually creep in?* Analytics/ETL pipelines, centralized logging, and CDNs.

**Q11. You discover full PANs in your centralized logs. Incident response?**
*Model answer:* Contain (stop further logging via source-side redaction, lock down log access), assess scope (which stores/backups, time range, how many PANs), notify per contractual/PCI and breach obligations, purge affected logs/backups, rotate anything exposed, root-cause (which code path logged it), add CI guardrails (forbidden-pattern scans, schema checks), and document. Treat as a reportable breach unless proven otherwise.
- *Follow-up: How prevent recurrence?* Source-side redaction filter, ban full-request logging, scan logs continuously, code review + CI checks.
- *Follow-up: Were CVVs there too?* If yes, far worse — SAD must never be stored; widen the investigation.

**Q12. Justify spending engineering effort on scope reduction to a skeptical PM.** *(senior-signal)*
*Model answer:* SAQ D compliance (running your own CDE) means ~300 controls, QSA audits, quarterly ASV scans, annual pentests, segmented networks, and constant evidence-gathering — large recurring cost and audit drag, plus breach liability for storing PANs. Moving to SAQ A via PSP hosted fields collapses that to ~20 controls and removes PAN from your breach surface entirely. The one-time integration cost is small versus perpetual audit/operational savings and risk reduction.
- *Follow-up: Quantify the risk side?* Breach fines, forensic costs, increased per-transaction fees, and potential loss of card-processing privileges.
- *Follow-up: Any downside?* Less control over checkout UX and dependence on the PSP — mitigate with A-EP or a tokenization proxy if you need flexibility.

---

## 11. Glossary

- **ABAC** — Attribute-Based Access Control: authorization decisions based on attributes (user, resource, context).
- **Acquirer / Acquiring bank** — the merchant's bank/processor that settles card transactions and requires PCI compliance.
- **AEAD** — Authenticated Encryption with Associated Data: encryption providing confidentiality + integrity (e.g., AES-GCM).
- **AES** — Advanced Encryption Standard; symmetric cipher (use AES-256-GCM).
- **Anonymization** — irreversible removal of identifiers so re-identification isn't reasonably possible; falls outside GDPR.
- **AOC** — Attestation of Compliance (signed PCI summary).
- **ASV** — Approved Scanning Vendor (runs required PCI external vuln scans).
- **BIN/IIN** — Bank/Issuer Identification Number: first 6–8 digits of a PAN.
- **Blind index** — separate HMAC column enabling exact-match search on encrypted data.
- **BouncyCastle** — popular Java crypto provider library.
- **BYOK/HYOK** — Bring/Hold Your Own Key: customer-controlled encryption keys.
- **CCPA/CPRA** — California privacy laws.
- **CDE** — Cardholder Data Environment: all systems touching CHD plus connected systems (PCI scope).
- **CHD** — Cardholder Data: PAN + name + expiry + service code.
- **Confidential computing** — protecting data *in use* via TEEs.
- **Controller** — (GDPR) entity deciding why/how personal data is processed.
- **CSFLE** — Client-Side Field-Level Encryption (driver encrypts before storage).
- **CSP** — Content Security Policy: browser control limiting which scripts run (anti-skimming).
- **Crypto-shredding** — erasing data by deleting its encryption key, making ciphertext permanently unrecoverable.
- **CVV/CVC/CID** — card verification value; SAD — never store after auth.
- **Data minimization** — collect/keep only what's necessary.
- **Data residency** — physical location where data is stored.
- **Data sovereignty** — data subject to the laws of where it resides.
- **Data subject** — (GDPR) the human a piece of personal data is about.
- **DEK** — Data Encryption Key (encrypts data; wrapped by a KEK).
- **Deterministic encryption** — same plaintext → same ciphertext (enables equality, leaks frequency).
- **Differential privacy** — provable privacy via calibrated noise (parameter ε).
- **DPA** — Data Processing Agreement (controller↔processor contract).
- **DPDP Act 2023** — India's Digital Personal Data Protection Act.
- **DPIA** — Data Protection Impact Assessment (risk analysis for high-risk processing).
- **DSAR** — Data Subject Access Request (access/erasure/portability/etc.).
- **Envelope encryption** — encrypt data with a DEK, encrypt the DEK with a KEK.
- **Event sourcing** — storing state as an immutable, append-only log of events.
- **FPE** — Format-Preserving Encryption (e.g., NIST FF1/FF3-1).
- **Forward secrecy (PFS)** — past sessions stay secure even if the long-term key leaks later.
- **GCM** — Galois/Counter Mode: AEAD mode for AES.
- **GDPR** — EU General Data Protection Regulation.
- **HIPAA** — US health-data privacy/security law (governs PHI).
- **HMAC** — keyed hash for integrity / keyed pseudonymization.
- **HSM** — Hardware Security Module: tamper-resistant crypto hardware.
- **HSTS** — HTTP Strict Transport Security (force HTTPS).
- **Issuer** — the bank that issued the cardholder's card.
- **IV / nonce** — unique value per encryption ensuring distinct ciphertext; must not repeat per key (GCM).
- **JCA/JCE** — Java Cryptography Architecture/Extension.
- **k-anonymity / l-diversity** — group-based anonymity guarantees.
- **KEK** — Key Encryption Key (wraps DEKs; lives in KMS/HSM).
- **KMS** — Key Management Service.
- **Least privilege** — minimum necessary permissions.
- **LGPD** — Brazil's privacy law.
- **Luhn** — checksum algorithm validating card numbers.
- **Magecart / e-skimming** — client-side JS that steals card data at checkout.
- **Masking** — partially hiding a value for display (e.g., last 4).
- **Merkle tree** — hash tree summarizing data with a single verifiable root.
- **mTLS** — mutual TLS (both sides present certs).
- **MFA** — Multi-Factor Authentication.
- **Network tokenization** — card-network-issued tokens (VTS/MDES) that auto-update.
- **PAN** — Primary Account Number (the card number).
- **PCI DSS** — Payment Card Industry Data Security Standard.
- **PFS** — see Forward secrecy.
- **PHI** — Protected Health Information (HIPAA).
- **PII** — Personally Identifiable Information.
- **Privacy by Design/Default** — GDPR Art. 25 architectural principles.
- **Processor** — (GDPR) entity processing data on a controller's behalf.
- **Pseudonymization** — reversible replacement of identifiers; still personal data.
- **PSP** — Payment Service Provider (Stripe, Adyen, etc.).
- **Purpose limitation** — use data only for its stated purpose.
- **QLDB** — Amazon Quantum Ledger Database (immutable, verifiable). *Verify current availability.*
- **QSA** — Qualified Security Assessor (PCI auditor).
- **RBAC** — Role-Based Access Control.
- **Retention policy** — how long data is kept before deletion.
- **Right to be forgotten** — GDPR Art. 17 erasure right.
- **ROC** — Report on Compliance (PCI audit report).
- **RoPA** — Record of Processing Activities (GDPR Art. 30 inventory).
- **SAD** — Sensitive Authentication Data (CVV/track/PIN); never store after auth.
- **Salt** — random value added before hashing to defeat rainbow tables.
- **SAQ** — Self-Assessment Questionnaire (PCI; types A, A-EP, B, C, D…).
- **SCC** — Standard Contractual Clauses (lawful cross-border transfer).
- **SIEM** — Security Information and Event Management.
- **SPI / special categories** — sensitive personal info needing extra protection.
- **SRI** — Subresource Integrity (verify third-party scripts).
- **TDE** — Transparent Data Encryption (DB/disk-level at rest).
- **TEE** — Trusted Execution Environment (confidential computing).
- **Tink** — Google's misuse-resistant crypto library.
- **TLS** — Transport Layer Security (HTTPS).
- **Tokenization** — replacing sensitive values with non-sensitive surrogate tokens.
- **Truncation** — permanently removing part of a value (e.g., keep last 4).
- **Vault (token/secrets)** — hardened store for token↔PAN mappings or secrets.
- **WORM** — Write-Once-Read-Many storage (immutable for a retention window).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The five moves:** Classify → Minimize → Contain (scope reduction) → Protect (encrypt + access control + audit) → Be able to delete (incl. crypto-shred).

**PCI two rules:** (1) **Never store SAD** (CVV, track, PIN) after auth. (2) If you store **PAN**, render it unreadable (tokenize / encrypt / truncate / strong-hash). Masking = display only.

**Scope reduction goal:** move from **SAQ D** (you handle PANs, ~300 controls) toward **SAQ A** (PSP hosted fields, PAN never hits your server). Mind **client-side skimming** (CSP + SRI, PCI v4).

**Crypto defaults:** AES-256-GCM, random 96-bit IV per op (never reuse per key), 128-bit tag; envelope encryption (per-scope **DEK** wrapped by **KEK** in KMS/HSM); version keys; use **Tink**/AWS Enc SDK; never DIY.

**Privacy distinctions:** Pseudonymization (reversible → still personal data) vs Anonymization (irreversible → outside GDPR). "Hashed" usually = pseudonymized.

**Erasure in immutable systems:** **crypto-shred** — per-subject DEK; delete key to forget; also purge decrypted projections/caches/indexes; bound backup retention.

**Audit logs:** append-only + WORM + hash-chain + external anchoring = tamper-evident. Never log the sensitive data itself.

**Key numbers:** GDPR breach notification **72 hours**; DSAR response **1 month** (→3 if complex); PCI ASV scans **quarterly**; pentest **annual**; PAN = 13–19 digits, last digit = Luhn check, first 6–8 = BIN; GCM plaintext limit ~64 GiB/(key,IV).

**GDPR principles:** lawful basis, purpose limitation, data minimization, storage limitation, accuracy, integrity & confidentiality, accountability; privacy by design/default.

**Top anti-patterns:** storing CVV; PANs in logs; "hashed = anonymous"; one un-rotated app key; erasure as an afterthought; prod data in test; mutable audit logs; ECB/DIY crypto.

### 12.2 Self-test (no answers — recall practice)

1. A migration adds a `card_cvv` column "to support faster re-charges." What's wrong, and what do you do instead?
2. Explain, end to end, how a tokenized card capture keeps your backend at SAQ A. Where does the raw PAN actually exist?
3. You must support exact-match lookup on an encrypted national-ID field. Design the storage so the stored value is randomly encrypted yet still queryable. What does the index leak?
4. Your system is event-sourced on Kafka. A user invokes the right to be forgotten. Describe precisely what you delete, what survives, and every shadow copy you must also handle.
5. Distinguish pseudonymization from anonymization, then argue why HMAC-ing an email is not anonymization.
6. Design a tamper-evident audit log and explain how you'd *prove* to an auditor that no entry was altered after the fact — including against an attacker who controls the logging service.
7. Walk through envelope encryption and explain how it makes both key rotation and crypto-shredding cheap.
8. EU users' personal data is leaking into a US analytics warehouse. How do you detect it, fix it, and prevent recurrence — and what's the legal distinction between residency and sovereignty?

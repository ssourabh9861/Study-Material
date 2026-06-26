# mTLS & Service Identity

> An exhaustive engineering-handbook chapter for senior JVM/backend developers who want to fully master mutual TLS and workload identity — from the first byte of a TLS handshake to SPIFFE SVIDs, service-mesh sidecars, certificate rotation, and the expired-cert outages that take down production.

---

## 1. Overview & where it fits

### 1.1 What it is

**TLS (Transport Layer Security)** is the protocol that encrypts and authenticates network connections — the "S" in HTTPS. In its ordinary form, only the **server** proves its identity to the **client** (your browser checks that `bank.com` really is `bank.com`). The client stays anonymous at the TLS layer; it proves *who it is* later, at the application layer, with a password, cookie, or bearer token.

**mTLS (mutual TLS)** removes that asymmetry. During the same handshake, **both peers present X.509 certificates and both verify each other.** The server proves it is the legitimate server *and* the client proves it is a legitimate, named caller — cryptographically, before a single byte of application data flows. The connection is simultaneously **encrypted**, **integrity-protected**, and **mutually authenticated**.

**Service identity** is the broader idea mTLS serves: every workload (a process, a container, a pod) gets a verifiable, cryptographic name — not an IP address, not a hostname, but a *strong identity* like `spiffe://prod.acme.com/ns/payments/sa/charge-service`. mTLS is the most common *mechanism* for proving that identity on the wire.

### 1.2 The problem it solves

For decades, backend security used **perimeter (castle-and-moat) security**: a hard firewall around the data center, and *implicit trust* for anything inside it. If a packet came from `10.4.x.x`, it was "internal," therefore trusted. This breaks badly:

- **Lateral movement.** One compromised pod can reach every other service, because the network trusts internal IPs. Most large breaches are not a single break-in; they are one foothold followed by quiet movement across the flat internal network.
- **IPs are not identities.** IPs are recycled across pods every few seconds in Kubernetes. Firewall rules keyed on IP ranges are stale the moment they're written. An attacker who lands on a node inherits whatever the IP is "allowed" to do.
- **Unencrypted east-west traffic.** Traffic *between* services ("east-west") was historically plaintext, on the assumption the internal network was safe. Anyone with packet capture on the network (a sidecar, a sniffer, a compromised node, a cloud provider hypervisor bug) reads it.
- **No cryptographic answer to "who is calling me?"** Service A had no trustworthy way to know that the TCP connection claiming to be Service B actually *was* Service B.

mTLS answers all four: traffic is encrypted (no sniffing), each side is authenticated (no impersonation), and the identity is cryptographic (not spoofable by grabbing an IP).

### 1.3 When you reach for it

- **Service-to-service (east-west) traffic** inside a cluster or across clusters, especially in **zero-trust** architectures (defined below).
- **Regulated environments** (PCI-DSS, HIPAA, FedRAMP) that mandate encryption in transit *and* strong authentication for internal traffic.
- **Multi-tenant** platforms where workloads of different trust levels share infrastructure.
- **High-value APIs** (payments, internal admin, infra control planes) where a bearer token alone is too weak — you want the *channel itself* authenticated, so a stolen token can't be replayed from an unauthorized client.
- **B2B integrations** where two companies exchange certs out-of-band and pin them.

You generally do **not** reach for mTLS for public, browser-facing endpoints — browsers don't carry client certs gracefully, and user authentication belongs at the application layer (OAuth/OIDC). mTLS shines for *machine-to-machine* trust.

### 1.4 The one-paragraph mental model

> Think of mTLS as **two passports checked at a border crossing, both directions, every time.** A trusted authority (the **CA**) issues each service a tamper-proof passport (an **X.509 certificate**) that says "this is the payments service, valid until 2pm, and here is its public key." When two services connect, each shows its passport, each checks the other's passport was signed by a CA it trusts, each verifies the holder actually owns the matching private key (via a signature), and only then is a shared secret negotiated to encrypt the conversation. **Service identity** is the name written on the passport; **mTLS** is the border check; **PKI/SPIFFE** is the passport office; a **service mesh** is the automated border agent that does all of this for your apps without changing their code.

---

## 2. Foundations from first principles

We build up the entire conceptual stack here. If you already know symmetric vs. asymmetric crypto, skim to §2.6.

### 2.1 Confidentiality, integrity, authenticity

Three distinct security properties, often confused:

- **Confidentiality** — nobody but the intended recipient can read the data. Achieved by **encryption**.
- **Integrity** — the data wasn't altered in transit (no bit flipped, no byte injected). Achieved by **MACs / AEAD** (below).
- **Authenticity** — you're really talking to who you think you are. Achieved by **certificates + signatures**.

Plain encryption gives confidentiality but *not* authenticity: you can encrypt a perfect conversation with an attacker who is impersonating your peer. TLS bundles all three.

### 2.2 Symmetric vs. asymmetric cryptography

- **Symmetric encryption** uses one shared secret key for both encrypt and decrypt (e.g., **AES**, the Advanced Encryption Standard). It's fast (gigabytes/sec with hardware **AES-NI** instructions) but requires both sides to already share the secret — which is the chicken-and-egg problem of how you got the secret to them safely.
- **Asymmetric (public-key) cryptography** uses a **key pair**: a **public key** (shareable with the world) and a **private key** (kept secret). Data encrypted with one can only be decrypted with the other; a signature made with the private key can be verified by anyone with the public key. Examples: **RSA**, **ECDSA** (Elliptic Curve Digital Signature Algorithm). It's slow (thousands of ops/sec, not gigabytes), so it's used only to *bootstrap* — to authenticate peers and agree on a symmetric key.

TLS uses asymmetric crypto for the handshake (authentication + key agreement) and symmetric crypto for the bulk data. Best of both worlds.

### 2.3 Hashing, MACs, and AEAD

- **Cryptographic hash** — a one-way function (e.g., **SHA-256**) mapping any input to a fixed-size fingerprint. Change one bit of input → completely different hash. Used everywhere: certificate fingerprints, signatures, integrity checks.
- **MAC (Message Authentication Code)** — a hash keyed with a secret, proving both integrity and that the sender knew the key (e.g., **HMAC-SHA256**).
- **AEAD (Authenticated Encryption with Associated Data)** — modern ciphers that do encryption *and* integrity in one operation (e.g., **AES-GCM**, **ChaCha20-Poly1305**). TLS 1.3 mandates AEAD. "Associated data" = fields that are authenticated but not encrypted (like packet headers).

### 2.4 Digital signatures (the core trust primitive)

A **digital signature**: the signer hashes the data, then encrypts the hash with their **private key**. Anyone can hash the data themselves, decrypt the signature with the signer's **public key**, and compare. If they match, the data is authentic (came from the private-key holder) and unaltered. This is the mechanism a **CA** uses to vouch for a certificate, and the mechanism a peer uses to prove it owns a private key during the handshake.

### 2.5 X.509 certificates

An **X.509 certificate** is a standardized, signed document binding a **public key** to an **identity**. Key fields:

| Field | Meaning |
|---|---|
| **Subject** | Who the cert identifies (e.g., `CN=charge-service`). Historically the **CN (Common Name)**. |
| **Subject Public Key** | The public key whose private half the subject holds. |
| **Issuer** | Who signed/vouched for this cert (a CA's name). |
| **Validity (notBefore/notAfter)** | The time window the cert is valid. The source of the infamous expiry outages. |
| **Serial Number** | Unique ID assigned by the issuing CA (used by revocation lists). |
| **SAN (Subject Alternative Name)** | The modern way to list identities: DNS names, IPs, URIs, emails. **Browsers and modern clients ignore CN and require SAN.** For SPIFFE, the identity is a **URI SAN** like `spiffe://...`. |
| **Key Usage / Extended Key Usage** | What the cert may be used for (`digitalSignature`, `keyEncipherment`, EKUs `serverAuth`, `clientAuth`). For mTLS, a workload cert typically has **both** `serverAuth` and `clientAuth`. |
| **Basic Constraints** | `CA:TRUE` marks a CA cert (allowed to sign others); `CA:FALSE` is a leaf. |
| **Signature** | The CA's signature over all the above. |

Encodings you'll meet: **PEM** (Base64 text, `-----BEGIN CERTIFICATE-----`), **DER** (raw binary), **PKCS#12 / `.p12` / `.pfx`** (a password-protected bundle of cert + private key, common in Java), and **PKCS#8** (a private-key format). The JVM historically stores these in a **keystore** (your own cert+key) and a **truststore** (CAs you trust), typically in **JKS** (Java KeyStore) or PKCS#12 format.

### 2.6 PKI: CA, chain of trust, root vs. intermediate

**PKI (Public Key Infrastructure)** is the whole system of CAs, certificates, and policies that lets strangers trust each other's public keys.

- A **CA (Certificate Authority)** is an entity trusted to issue certificates. It has its own key pair; it signs other certs with its private key.
- A **root CA** is the anchor of trust. Its certificate is **self-signed** (issuer == subject) and is distributed out-of-band into **trust stores** (the OS trust store, the JVM's `cacerts`, a Kubernetes secret). You trust it because you *configured* yourself to — it's an axiom, not derived.
- Roots are kept **offline** and precious. They sign **intermediate CAs**, which do the day-to-day issuing. This limits blast radius: if an intermediate is compromised, you revoke it without burning the root.
- **Chain of trust:** a leaf cert is signed by an intermediate, which is signed (maybe via more intermediates) by a root. To verify a leaf, you walk the chain up to a root you already trust, checking each signature. The peer sends the leaf **plus intermediates**; you supply the root from your trust store. A missing intermediate is the #1 cause of "unable to find valid certification path" errors.

```
Root CA (self-signed, offline, in your trust store)
   └── Intermediate CA (online, issues workload certs)
          └── Leaf cert: charge-service  ←  what the service presents
```

### 2.7 Revocation: CRL and OCSP

Certs have an expiry, but sometimes you must kill one *early* (key leaked, service decommissioned). Two classic mechanisms:

- **CRL (Certificate Revocation List)** — the CA publishes a signed list of revoked serial numbers. Clients download and cache it. Problem: lists grow huge, caching causes staleness, and many clients skip the check.
- **OCSP (Online Certificate Status Protocol)** — the client asks an **OCSP responder** "is serial 12345 still good?" in real time. Problems: latency, an availability dependency on the responder, and privacy leakage (the responder learns who you're talking to). **OCSP stapling** fixes some of this: the *server* periodically fetches a signed "I'm still valid" proof from the CA and *staples* it into its own handshake, so the client needn't call the OCSP responder at all.

**The modern answer in mesh/SPIFFE worlds:** mostly *skip* CRL/OCSP and instead use **very short-lived certificates** (minutes to hours). If a cert lives only an hour, revocation is "wait for it to expire." This trades a CPU/issuance cost for the elimination of a fragile revocation-checking path. (More in §7.)

### 2.8 Zero-trust networking

**Zero trust** is the security model that says: **never trust based on network location; always verify identity explicitly, for every request.** "Inside the firewall" grants no privilege. Every connection must prove *who* it is (authentication) and *what* it may do (authorization), continuously. The canonical articulation is Google's **BeyondCorp/BeyondProd** and **NIST SP 800-207**. mTLS is the foundational *authentication* primitive of zero trust for service-to-service traffic — it gives every hop a cryptographic identity instead of an IP.

### 2.9 SPIFFE & SVID (workload identity, vendor-neutral)

- **SPIFFE (Secure Production Identity Framework For Everyone)** is an open standard for *how to name and prove the identity of a workload.* It defines:
  - A **SPIFFE ID** — a URI like `spiffe://trust-domain/path`, e.g. `spiffe://prod.acme.com/ns/payments/sa/charge`. The **trust domain** (`prod.acme.com`) is the boundary of a single root of trust.
  - An **SVID (SPIFFE Verifiable Identity Document)** — the credential carrying that ID. The common form is an **X.509-SVID**: an X.509 cert whose **URI SAN** is the SPIFFE ID. There is also a **JWT-SVID** for cases where you can't use mTLS (e.g., through an L7 proxy that terminates TLS).
- **SPIRE (SPIFFE Runtime Environment)** is the reference implementation: a **server** (the CA / signer / registry) and **agents** (one per node) that **attest** workloads — prove what a process is using node + workload **attestation** (e.g., "this PID is in this Kubernetes pod with this service account") — and then hand them short-lived SVIDs via a local API (the **Workload API**, served over a Unix domain socket). The huge benefit: workloads get identity **without ever holding a long-lived secret** and without baking credentials into images.

### 2.10 Service mesh & sidecars

A **service mesh** is infrastructure that handles service-to-service communication *outside your application code*. The dominant pattern is the **sidecar proxy**: a small proxy (commonly **Envoy**) injected next to every app container. All traffic in/out of the app goes through its sidecar. The mesh's **control plane** (e.g., **Istiod** in Istio) distributes config and **issues/rotates certs** to every sidecar, so the proxies establish mTLS to each other automatically. **The app speaks plain HTTP to its own sidecar over localhost; the sidecars speak mTLS to each other over the network.** Your code is unchanged. **Linkerd** uses its own lightweight Rust proxy (`linkerd2-proxy`) instead of Envoy. Newer "ambient"/sidecar-less designs (Istio Ambient, Cilium) move mTLS into a per-node agent (**ztunnel**) instead of a per-pod sidecar.

You now have every building block. The rest of the doc assembles them.

---

## 3. How it works internally

This is the heart. We trace (a) the TLS 1.3 handshake, (b) how mutual auth changes it, (c) certificate *verification* in detail, (d) the SPIRE issuance lifecycle, and (e) the mesh data path.

### 3.1 TLS 1.2 vs 1.3 in one breath

- **TLS 1.2** (2008): handshake takes **2 round trips (2-RTT)** before app data; supports many cipher suites including weak/legacy ones; supports RSA key exchange (no forward secrecy) and DHE/ECDHE (forward secrecy).
- **TLS 1.3** (2018, RFC 8446): **1-RTT** handshake (and **0-RTT** resumption); removed all the weak stuff; **mandatory forward secrecy** (always ephemeral ECDHE/DHE); AEAD-only ciphers; encrypts more of the handshake (including certificates). **Prefer TLS 1.3 everywhere it's available.** We trace 1.3 below and note where 1.2 differs.

**Forward secrecy (PFS)** = even if the server's long-term private key is later stolen, past recorded sessions stay safe, because the actual session key came from an **ephemeral** key pair thrown away after the handshake. TLS 1.3 makes this mandatory.

### 3.2 The TLS 1.3 handshake, step by step (server-auth only)

Actors: **client (C)** and **server (S)**.

1. **ClientHello (C→S).** Client sends: supported TLS versions, a list of **cipher suites**, a random nonce, the list of supported groups (elliptic curves), and — crucially in 1.3 — its **key share**: an ephemeral ECDHE public key. It may include **SNI (Server Name Indication)**, the hostname it wants, so a server hosting many sites picks the right cert.
   - *Key exchange (ECDHE):* both sides will combine their ephemeral key with the other's to derive the same shared secret without ever transmitting it. This is the Diffie-Hellman magic.
2. **ServerHello (S→C).** Server picks the version + cipher suite, sends its own random nonce and its **ephemeral key share.** From this point both sides can compute the **handshake secret** (via ECDHE) and start **encrypting** the rest of the handshake.
3. **Encrypted Extensions + Certificate + CertificateVerify + Finished (S→C, all encrypted).**
   - **Certificate:** the server's leaf cert **plus intermediates** (the chain, minus the root).
   - **CertificateVerify:** the server **signs a hash of the entire handshake transcript so far with its private key.** *This is the proof it actually owns the private key matching the cert* — not just that it copied someone's cert.
   - **Finished:** a MAC over the whole transcript, proving handshake integrity (nothing was tampered/downgraded).
4. **Client verifies (local).** Client checks the cert chain (see §3.4) and the CertificateVerify signature. If anything fails → handshake aborts with an alert.
5. **Finished (C→S).** Client sends its own Finished MAC. Handshake complete after **1 RTT**. Both derive the **application traffic secrets** and switch to symmetric AEAD for all further data.

In **TLS 1.2**, the equivalent takes 2 RTT, certificates are sent in cleartext, and the key-exchange/signature steps are split differently — but the conceptual roles (Certificate proves identity binding, CertificateVerify/ServerKeyExchange-signature proves key ownership, Finished proves integrity) are the same.

### 3.3 What mutual TLS adds

mTLS inserts client authentication into the same handshake:

1. In step 3, the server additionally sends a **CertificateRequest** message: "I require a client certificate. Here are the CA names I'll accept and the signature algorithms I support." (The accepted-CA list is a hint; clients use it to pick which cert to present.)
2. After ServerHello/Certificate, the **client** now sends:
   - **Certificate (C→S):** its own leaf + intermediates.
   - **CertificateVerify (C→S):** the client signs the handshake transcript with **its** private key — proving it owns the private key for the cert it presented.
3. The **server verifies the client's chain and signature** exactly as the client verified the server's. If the client sends no cert (and the server *required* one), or the cert fails verification, the server aborts.

Net effect: **both** CertificateVerify steps happen, **both** chain validations happen. Two passports, both directions. The cost is roughly one extra signature generation (client) + one extra chain verification (server) per handshake, plus the larger handshake messages.

A subtlety: a server can request client certs as **required** or **optional/requested**. "Optional" lets the server fall back to other auth if no cert is presented — useful during migrations but a foot-gun if you forget to actually enforce it (see anti-patterns, §6.8).

### 3.4 Certificate chain verification — the exact algorithm

When a peer presents a chain, the verifier does (per RFC 5280 path validation, simplified):

1. **Build the path** from the leaf up to a trust anchor (a root in the local trust store). Some libraries also accept an intermediate as the anchor if directly trusted.
2. For each cert in the path, **verify the issuer's signature** over it using the issuer's public key. Walk up until you hit a trusted root.
3. **Check validity windows** (`notBefore ≤ now ≤ notAfter`) for every cert in the chain. *Clock skew matters* — see failure modes.
4. **Check Basic Constraints:** every non-leaf must be `CA:TRUE`, and path-length constraints must hold.
5. **Check Key Usage / EKU:** CAs need `keyCertSign`; the leaf needs the right EKU (`serverAuth` for a server, `clientAuth` for a client; mesh certs carry both).
6. **Check identity / name binding:** does the cert's **SAN** match the expected identity? For a hostname connection, SNI/hostname must match a SAN entry. **For SPIFFE/mesh, the verifier matches the URI SAN (the SPIFFE ID) against an authorization policy** — this is the linchpin of mesh authz.
7. **Check revocation** (CRL/OCSP) — *if configured*. Many mesh setups skip this and rely on short lifetimes.

Failing any step → handshake alert (e.g., `bad_certificate`, `certificate_expired`, `unknown_ca`).

### 3.5 SPIRE issuance lifecycle (workload identity without secrets)

How a workload gets an SVID without ever being handed a long-lived credential:

1. **Server bootstrap.** The **SPIRE Server** holds (or fronts) the trust-domain CA. Operators define **registration entries** mapping *attestation selectors* → SPIFFE IDs, e.g. "a pod with k8s service account `payments:charge` gets `spiffe://prod.acme.com/ns/payments/sa/charge`."
2. **Agent node attestation.** Each node runs a **SPIRE Agent.** On startup it proves *which node it is* to the server via a **node attestor** plugin (AWS instance identity document, Kubernetes PSAT, TPM, etc.). The server issues the agent its own SVID.
3. **Workload attestation.** The app calls the **Workload API** over a **Unix domain socket** (no network secret to steal). The agent inspects the calling process out-of-band — its PID, cgroup, and via the **workload attestor** plugin determines the pod, namespace, and service account. This **caller cannot lie**, because the agent observes kernel facts about the process, not anything the process claims.
4. **Match & mint.** The agent matches the discovered selectors against registration entries, asks the server to sign an **X.509-SVID** (cert with URI SAN = the SPIFFE ID) with a short TTL, and returns the cert + private key + the trust bundle (the CAs to trust) to the workload.
5. **Rotation.** The agent proactively re-fetches and **streams updated SVIDs** to the workload before the old ones expire (default behavior: rotate at roughly half the cert lifetime). The workload (or its TLS library / SPIFFE helper like **`spiffe-helper`** or the **go-spiffe**/`java-spiffe` library) hot-swaps the in-memory cert with **no restart.**
6. **Trust bundle rotation.** The CA's own cert can rotate; the bundle is continuously refreshed so peers keep validating each other across CA changeovers.

The result: every workload has a fresh, short-lived, attested identity, and **no private key ever lands on disk in a long-lived form or in a container image.**

### 3.6 The service-mesh data path (Istio example)

1. **Injection.** When a pod starts, the mesh **injects** an Envoy sidecar container (via a mutating admission webhook) and configures `iptables` (or eBPF) to redirect all the pod's inbound/outbound traffic through Envoy on localhost.
2. **Identity issuance.** The sidecar requests a workload cert from the control plane (**Istiod**), which acts as (or fronts) the mesh CA, using the pod's **Kubernetes service account token** as the attestation. Istiod returns a short-lived cert (default ~24h, rotated well before expiry) whose **URI SAN is a SPIFFE ID** like `spiffe://cluster.local/ns/payments/sa/charge`.
3. **Outbound.** App in pod A sends plain HTTP to a service; `iptables` redirects it to A's Envoy. A's Envoy opens an **mTLS** connection to B's Envoy, presenting A's cert and validating B's.
4. **Inbound.** B's Envoy terminates mTLS, **validates A's cert chain and extracts A's SPIFFE ID from the URI SAN**, evaluates **authorization policy** ("is `charge` allowed to call `ledger`?"), then forwards plain HTTP to B's app over localhost.
5. **Policy & telemetry.** Because every hop is identified, the mesh enforces L7 authz and emits rich telemetry (who called whom, latency, success rate) for free.

**PeerAuthentication** policy controls the mode: `STRICT` (mTLS required), `PERMISSIVE` (accept both mTLS and plaintext — for migration), or `DISABLE`. **The PERMISSIVE→STRICT migration** is the standard rollout path.

---

## 4. The complete toolkit

### 4.1 Standards & specs

| Spec | What it defines |
|---|---|
| **RFC 8446** | TLS 1.3 |
| **RFC 5246** | TLS 1.2 |
| **RFC 5280** | X.509 cert & CRL profile, path validation |
| **RFC 6960** | OCSP |
| **RFC 6066** | TLS extensions (SNI, OCSP stapling) |
| **SPIFFE / SPIFFE-ID / X509-SVID / JWT-SVID specs** | Workload identity (spiffe.io) |
| **NIST SP 800-207** | Zero Trust Architecture |

### 4.2 Java/JVM APIs

| Class / API | Purpose | Key params / notes |
|---|---|---|
| `javax.net.ssl.SSLContext` | Central object holding key + trust material; produces sockets/engines | `SSLContext.getInstance("TLSv1.3")`, then `init(keyManagers, trustManagers, secureRandom)` |
| `javax.net.ssl.KeyManagerFactory` | Supplies *your* cert+private key | Init with a `KeyStore`; alg `"SunX509"`/`"PKIX"` |
| `javax.net.ssl.TrustManagerFactory` | Supplies CAs you trust; performs chain validation | Init with a truststore `KeyStore` |
| `javax.net.ssl.X509ExtendedTrustManager` | Custom validation hook (e.g., SPIFFE-aware) | Override `checkClientTrusted`/`checkServerTrusted` |
| `java.security.KeyStore` | In-memory store of keys/certs | Types: `PKCS12` (preferred), `JKS` (legacy) |
| `javax.net.ssl.SSLParameters` | Per-connection knobs | `setNeedClientAuth(true)` (require mTLS), `setWantClientAuth(true)` (optional), `setProtocols`, `setCipherSuites`, `setEndpointIdentificationAlgorithm("HTTPS")` |
| `SSLServerSocket.setNeedClientAuth(true)` | **Turn on mTLS on the server** | Without this, server never asks for a client cert |
| `javax.net.ssl.HttpsURLConnection` / `java.net.http.HttpClient` | HTTP clients that take an `SSLContext` | `HttpClient.newBuilder().sslContext(ctx)` |
| `java-spiffe` library | SPIFFE Workload API client + `SslContextFactory` for X509-SVIDs | Auto-rotates, validates URI SAN |

**Critical JVM defaults & gotchas:**
- Client-side hostname verification is **not** automatic on raw `SSLSocket`. You must set `params.setEndpointIdentificationAlgorithm("HTTPS")` or you've disabled the SAN/hostname check (a classic vuln). `HttpsURLConnection`/`HttpClient` do it for you.
- The JVM trust store is `$JAVA_HOME/lib/security/cacerts`, password `changeit`.
- `setNeedClientAuth(true)` = require & fail if absent; `setWantClientAuth(true)` = request but allow absent.

### 4.3 OpenSSL CLI (inspection & testing)

| Command | Purpose |
|---|---|
| `openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout k.pem -out csr.pem` | Generate key + CSR |
| `openssl x509 -in cert.pem -noout -text` | Dump a cert (check SAN, dates, EKU) |
| `openssl x509 -in cert.pem -noout -enddate` | Just the expiry date |
| `openssl verify -CAfile chain.pem cert.pem` | Validate a chain |
| `openssl s_client -connect host:443 -showcerts` | Open a TLS connection, dump the chain server sends |
| `openssl s_client -connect host:443 -cert c.pem -key k.pem` | **Test mTLS as a client** (present a client cert) |
| `openssl s_server -accept 8443 -cert s.pem -key k.pem -Verify 1 -CAfile ca.pem` | Run a test mTLS server requiring client certs (`-Verify` = require) |
| `openssl ocsp ...` | Query OCSP |
| `openssl x509 -noout -fingerprint -sha256 -in cert.pem` | Compute a pin (for pinning) |

### 4.4 SPIFFE/SPIRE CLI

| Command | Purpose |
|---|---|
| `spire-server entry create -spiffeID ... -parentID ... -selector k8s:ns:payments -selector k8s:sa:charge` | Register a workload → SPIFFE ID mapping |
| `spire-server entry show` | List registrations |
| `spire-agent api fetch x509` | Fetch the current SVID for the calling workload (debug) |
| `spire-server bundle show` | Show the trust bundle (CAs) |
| `spire-server healthcheck` | Health |

### 4.5 Istio / Linkerd CLI & CRDs

| Tool / CRD | Purpose |
|---|---|
| `istioctl x precheck` / `istioctl analyze` | Validate config before/after |
| `istioctl proxy-config secret <pod>` | **See the actual certs Envoy holds** (issuer, dates) |
| `istioctl proxy-config cluster/listener <pod>` | Inspect Envoy config |
| `PeerAuthentication` CRD | mTLS mode: `STRICT` / `PERMISSIVE` / `DISABLE` |
| `AuthorizationPolicy` CRD | L7 authz keyed on SPIFFE principals |
| `DestinationRule` (`tls.mode`) | Client-side TLS mode toward a service |
| `linkerd check` | Validate Linkerd install/certs |
| `linkerd identity <pod>` | Show a pod's cert/identity |
| `linkerd viz tap` | Live-tap traffic to see mTLS status |

### 4.6 cert-manager (Kubernetes cert automation)

| Resource | Purpose |
|---|---|
| `Issuer` / `ClusterIssuer` | A CA source (self-signed, CA secret, ACME/Let's Encrypt, Vault, etc.) |
| `Certificate` | Declarative request: DNS/URI SANs, duration, renewBefore, secret name |
| `duration` / `renewBefore` | Lifetime and how early to rotate (e.g., `duration: 24h`, `renewBefore: 8h`) |

### 4.7 HashiCorp Vault PKI

`vault secrets enable pki`, `vault write pki/roles/...`, `vault write pki/issue/<role> common_name=...` to mint short-lived certs on demand; integrates with cert-manager and SPIRE as an **upstream CA**.

---

## 5. Code examples by use case

### 5.1 Java server requiring client certs (the from-scratch mTLS server)

```java
// A plain TLS server that REQUIRES a valid client certificate (mTLS).
// Keystore = our server identity; Truststore = CAs we accept clients from.
import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;

public class MtlsServer {
  public static void main(String[] args) throws Exception {
    char[] ksPass = "changeit".toCharArray();

    // 1. Load OUR identity (cert + private key) from a PKCS#12 keystore.
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (var in = new FileInputStream("server.p12")) { ks.load(in, ksPass); }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
    kmf.init(ks, ksPass);

    // 2. Load the CAs we trust to have signed CLIENT certs.
    KeyStore ts = KeyStore.getInstance("PKCS12");
    try (var in = new FileInputStream("truststore.p12")) { ts.load(in, ksPass); }
    TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
    tmf.init(ts);

    // 3. Build a TLS 1.3 context.
    SSLContext ctx = SSLContext.getInstance("TLSv1.3");
    ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

    SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
    try (SSLServerSocket server = (SSLServerSocket) ssf.createServerSocket(8443)) {
      server.setNeedClientAuth(true);                 // <-- THE mTLS switch: require client cert
      server.setEnabledProtocols(new String[]{"TLSv1.3"});
      System.out.println("mTLS server on 8443");
      while (true) {
        try (SSLSocket s = (SSLSocket) server.accept()) {
          s.startHandshake();                          // forces handshake now so we can read peer identity
          // The client's verified identity — use this for authorization:
          var peer = s.getSession().getPeerPrincipal();
          System.out.println("Authenticated client: " + peer);
          s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\nhello\n".getBytes());
        } catch (SSLException e) {
          System.out.println("Rejected connection (bad/absent client cert): " + e.getMessage());
        }
      }
    }
  }
}
```

**Why it matters:** `setNeedClientAuth(true)` is the single line that makes this mTLS. `getPeerPrincipal()` gives you the *verified* client identity to authorize on — never trust a header for this.

### 5.2 Java client presenting a cert (`java.net.http.HttpClient`)

```java
// A modern HttpClient that presents OUR client cert and validates the server's.
import javax.net.ssl.*;
import java.net.http.*;
import java.net.URI;
import java.security.KeyStore;
import java.io.FileInputStream;

public class MtlsClient {
  public static void main(String[] args) throws Exception {
    char[] pass = "changeit".toCharArray();

    KeyStore ks = KeyStore.getInstance("PKCS12");      // our client identity
    try (var in = new FileInputStream("client.p12")) { ks.load(in, pass); }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
    kmf.init(ks, pass);

    KeyStore ts = KeyStore.getInstance("PKCS12");       // CAs we trust for the server
    try (var in = new FileInputStream("truststore.p12")) { ts.load(in, pass); }
    TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
    tmf.init(ts);

    SSLContext ctx = SSLContext.getInstance("TLSv1.3");
    ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

    HttpClient client = HttpClient.newBuilder()
        .sslContext(ctx)                                // HttpClient verifies hostname/SAN automatically
        .build();

    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder(URI.create("https://service-b:8443/")).build(),
        HttpResponse.BodyHandlers.ofString());
    System.out.println(resp.statusCode() + " " + resp.body());
  }
}
```

**Note:** `HttpClient` performs endpoint identification (hostname/SAN check) by default — unlike raw `SSLSocket`, where you must enable it explicitly.

### 5.3 SPIFFE-aware Java client with `java-spiffe` (auto-rotating SVIDs)

```java
// Uses the Workload API: no keystore files, no passwords, certs auto-rotate.
import io.spiffe.workloadapi.DefaultWorkloadApiClient;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.provider.SpiffeSslContextFactory.SslContextOptions;
import io.spiffe.workloadapi.DefaultX509Source;
import javax.net.ssl.SSLContext;

public class SpiffeClient {
  public static void main(String[] args) throws Exception {
    // Connects to the SPIRE agent over the Unix domain socket (env: SPIFFE_ENDPOINT_SOCKET).
    var x509Source = DefaultX509Source.newSource();    // fetches + auto-rotates our SVID

    var opts = SslContextOptions.builder()
        .x509Source(x509Source)
        // Only accept peers whose SPIFFE ID matches this predicate:
        .acceptedSpiffeIdsSupplier(() -> java.util.Set.of(
            io.spiffe.spiffeid.SpiffeId.parse("spiffe://prod.acme.com/ns/payments/sa/ledger")))
        .build();

    SSLContext ctx = SpiffeSslContextFactory.getSslContext(opts);
    // ... use ctx with HttpClient as in 5.2. The URI SAN is matched, not a hostname.
  }
}
```

**Why it matters:** authorization is on the **SPIFFE ID** (URI SAN), not a hostname; the SVID rotates under the hood with no restart and no secret on disk.

### 5.4 Generating a test PKI with OpenSSL (CA + server + client)

```bash
# Root CA
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout ca.key -out ca.crt -days 3650 -subj "/CN=Test Root CA"

# Server: key + CSR + SAN, then sign with CA
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout server.key -out server.csr -subj "/CN=service-b"
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 1 -out server.crt \
  -extfile <(printf "subjectAltName=DNS:service-b\nextendedKeyUsage=serverAuth,clientAuth")

# Client identity (note clientAuth EKU)
openssl req -newkey ec -nodes -keyout client.key -out client.csr -subj "/CN=service-a"
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 1 -out client.crt \
  -extfile <(printf "extendedKeyUsage=clientAuth")

# Bundle for the JVM (PKCS#12 keystore + truststore)
openssl pkcs12 -export -in server.crt -inkey server.key -out server.p12 -passout pass:changeit
keytool -importcert -file ca.crt -keystore truststore.p12 -storetype PKCS12 \
  -storepass changeit -noprompt -alias testca
```

```bash
# Quick mTLS smoke test without writing any code:
openssl s_server -accept 8443 -cert server.crt -key server.key -CAfile ca.crt -Verify 1 &
openssl s_client -connect localhost:8443 -cert client.crt -key client.key -CAfile ca.crt
```

### 5.5 Istio: enforce STRICT mTLS + identity-based authz

```yaml
# Require mTLS for everything in the payments namespace.
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata: { name: default, namespace: payments }
spec:
  mtls: { mode: STRICT }            # reject any plaintext
---
# Only the 'charge' service account may call the ledger workload.
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata: { name: ledger-allow-charge, namespace: payments }
spec:
  selector: { matchLabels: { app: ledger } }
  action: ALLOW
  rules:
  - from:
    - source:
        principals: ["cluster.local/ns/payments/sa/charge"]  # the caller's SPIFFE identity
    to:
    - operation: { methods: ["POST"], paths: ["/v1/debit"] }
```

### 5.6 cert-manager: short-lived, auto-rotated workload cert

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: charge-tls, namespace: payments }
spec:
  secretName: charge-tls            # k8s Secret cert-manager keeps fresh
  duration: 24h                     # short-lived
  renewBefore: 8h                   # rotate with plenty of margin
  privateKey: { algorithm: ECDSA, size: 256, rotationPolicy: Always }
  uris:
  - spiffe://prod.acme.com/ns/payments/sa/charge   # SPIFFE ID as URI SAN
  issuerRef: { name: mesh-ca, kind: ClusterIssuer }
```

### 5.7 Certificate pinning in an Android/OkHttp client (B2B pin)

```java
// Pin the server to a specific SPKI hash — survives CA compromise but must be rotated carefully.
OkHttpClient client = new OkHttpClient.Builder()
  .certificatePinner(new CertificatePinner.Builder()
    // Two pins: current + backup, so rotation doesn't brick the client.
    .add("api.partner.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    .add("api.partner.com", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
    .build())
  .build();
```

**Why two pins:** if you pin a single cert and it rotates, every client is bricked until they update. Always pin a backup key.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Handshake cost** dominates: asymmetric ops (signature + verify per side; ECDHE). With **ECDSA P-256** the handshake is far cheaper than **RSA-2048** for signing; prefer EC keys for high-churn mTLS.
- **Session resumption** amortizes handshakes: TLS 1.3 **PSK resumption / 0-RTT** lets repeat connections skip the full handshake. For service meshes, sidecars keep **long-lived pooled connections**, so the handshake is paid once per connection, not per request — making the per-request overhead mostly the symmetric AEAD (cheap, hardware-accelerated via **AES-NI**).
- **Bulk encryption** is cheap: AES-GCM/ChaCha20 run at multiple GB/s. The throughput hit of mTLS on a warm, pooled connection is typically **single-digit percent**; the *latency* hit is the extra handshake on cold connections.
- **mTLS specifically** adds ~one extra signature (client) + one chain verification (server) per handshake vs. server-only TLS. Negligible on warm pools, noticeable under high connection churn.
- **Mesh overhead:** the sidecar adds a localhost hop in each direction. Typical added p50 latency is sub-millisecond to low single-digit ms, plus per-pod CPU/memory for the proxy. Sidecar-less (ambient/eBPF) designs reduce this.

### 6.2 Correctness & concurrency

- **Hot cert rotation must be atomic.** When swapping an in-memory cert, never serve half-old/half-new state. Use an `SSLContext`/`KeyManager` that reloads atomically; the SPIFFE libraries and Envoy do this.
- **Clock sync is a correctness requirement.** Validity checks depend on `now`; skew causes spurious `notBefore`/`certificate_expired` failures. Run **NTP/chrony** everywhere; allow a small skew tolerance if your library supports it.

### 6.3 Memory & key handling

- **Private keys in memory, not on disk.** SPIFFE/mesh keep keys in-memory and rotate them; if you must persist, use a keystore with restrictive perms or an **HSM/KMS**.
- **Never bake long-lived keys into container images or git.** This is the cardinal sin; one leaked image = forever-valid identity.

### 6.4 Security

- **Enforce hostname/SAN verification** (`setEndpointIdentificationAlgorithm("HTTPS")` on raw sockets). Disabling it (common in "make it work" hacks) reduces mTLS to "any cert from any trusted CA," enabling impersonation by any holder of a CA-signed cert.
- **Scope trust narrowly.** A truststore containing a public CA means *anyone* with a public cert authenticates. For internal mTLS, trust only your **private** mesh/CA.
- **Authorize on identity, not transport.** mTLS authenticates; you still need **authorization** (which SPIFFE ID may do what). Use AuthorizationPolicy / app-level checks on `getPeerPrincipal()`.
- **Short lifetimes over revocation.** Prefer minutes-to-hours certs; treat CRL/OCSP as defense-in-depth, not the primary control.

### 6.5 Observability

- **Export cert expiry as a metric** and alert with generous lead time (days). The single most valuable mTLS metric. Tools: a blackbox/`x509-certificate-exporter` for Prometheus; `istioctl proxy-config secret`; cert-manager metrics.
- **Track handshake failure rates** and **mTLS coverage** (% of traffic that is mTLS). Meshes expose `peer_authentication`/connection security policy in telemetry.
- **Log the verified peer identity** on every request for audit (`getPeerPrincipal()` / SPIFFE ID).

### 6.6 Cost

- CA/issuance infra (SPIRE/Vault/cert-manager), sidecar CPU+RAM (can be a large fraction of cluster cost at scale), and operational complexity. Sidecar-less meshes and per-node agents reduce the per-pod tax.

### 6.7 Testing

- **Unit/integration:** spin a test CA (§5.4), run a `MtlsServer` + client, assert that (a) a valid client cert succeeds, (b) no cert is rejected, (c) a cert from an *untrusted* CA is rejected, (d) an *expired* cert is rejected, (e) a cert with the *wrong SAN* is rejected.
- **Chaos:** deliberately fast-forward clocks / shorten cert lifetimes in staging to rehearse rotation and expiry handling *before* prod surprises you.
- **`openssl s_client`/`s_server`** for black-box probing.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it bites |
|---|---|
| Disabling hostname/SAN verification | Any CA-signed cert can impersonate any service |
| `setWantClientAuth(true)` but never checking the result | Server *requests* but doesn't *require* certs → silent plaintext/anon access |
| Trusting a public CA bundle for internal mTLS | Anyone on the internet with a valid cert authenticates |
| Long-lived certs (years) + no rotation | Huge breach window; revocation never works in practice |
| Single cert pin with no backup | Rotation bricks every client |
| Stuffing keys into images/env/git | Permanent, unrevocable credential leak |
| Ignoring intermediate certs in the chain | "unable to find valid certification path" in prod only |
| No expiry alerting | The #1 cause of mTLS outages |
| `PERMISSIVE` mode left on forever | You think you have mTLS; half your traffic is plaintext |

---

## 7. Advanced topics & deep internals

### 7.1 Short-lived certs vs. revocation (the philosophical shift)

Traditional PKI assumes long-lived certs + revocation. Modern workload identity inverts this: **certs so short-lived (1h, even minutes) that revocation is unnecessary** — you just stop renewing. This eliminates the fragile OCSP/CRL availability dependency (an OCSP outage shouldn't take down your mesh) and the privacy leak. The tradeoff: you need a **highly available issuance path**, because every workload re-mints constantly. If issuance dies, certs expire and everything stops — so the CA becomes a tier-0 dependency. SPIRE/Istiod are built for this with caching and proactive rotation at ~50% of TTL.

### 7.2 Rotation timing & the "renewBefore" margin

Rotate well before expiry — typically at **50–66% of lifetime** (e.g., renew an 24h cert after ~8–16h). The margin absorbs issuance outages, clock skew, and propagation delay. Too tight a margin + a brief CA hiccup = mass expiry. cert-manager's `renewBefore`, SPIRE's half-life rotation, and Istio's rotation grace all encode this.

### 7.3 CA rotation & trust-bundle distribution

Rotating the **root/intermediate CA** is harder than rotating leaves, because *everyone* must trust the new CA *before* anyone presents certs signed by it. The pattern:
1. **Add** the new CA to all trust bundles (now both old and new are trusted).
2. **Switch** issuance to the new CA (leaves now signed by new).
3. **Remove** the old CA after all old leaves have expired.
Skipping step 1 (everyone trusts new before switching) is the classic CA-rotation outage. SPIRE/mesh trust-bundle streaming automates this overlap.

### 7.4 SPIFFE federation across trust domains

Two organizations/clusters with **different trust domains** can **federate**: each exchanges its trust bundle so workloads in domain A can validate SVIDs from domain B (`spiffe://acme.com` ↔ `spiffe://partner.com`). This replaces brittle cross-org cert exchange with a managed bundle endpoint.

### 7.5 JWT-SVID and mTLS termination at L7

Sometimes you can't keep mTLS end-to-end — an L7 load balancer or API gateway terminates TLS. Options: re-originate mTLS on the inside (gateway → backend), or carry identity as a **JWT-SVID** in a header that the backend validates. SPIFFE supports both X.509-SVID (channel identity) and JWT-SVID (bearer identity) for exactly this.

### 7.6 0-RTT and replay risk

TLS 1.3 **0-RTT** lets a resuming client send data in the *first* flight (zero handshake latency). But 0-RTT data is **replayable** — an attacker can resend it. Only use 0-RTT for **idempotent** requests; never for state-changing operations. Meshes generally avoid 0-RTT for safety.

### 7.7 Cipher & curve selection

Prefer TLS 1.3 (`TLS_AES_128_GCM_SHA256`, `TLS_AES_256_GCM_SHA384`, `TLS_CHACHA20_POLY1305_SHA256` — all AEAD, all forward-secret). For keys, **ECDSA P-256** is the sweet spot for mTLS (fast, small); RSA-2048 is the conservative fallback; **Ed25519** is excellent where supported. Post-quantum hybrids (e.g., X25519+ML-KEM) are emerging — flag as cutting-edge/version-specific.

### 7.8 SNI, ALPN, and routing

**SNI** (the hostname in ClientHello) lets servers/proxies select certs and route. **ALPN (Application-Layer Protocol Negotiation)** negotiates the app protocol (h2, http/1.1) during the handshake — meshes use it to signal mTLS and protocol. Note SNI is *cleartext* in TLS 1.2/1.3 today (ECH/Encrypted Client Hello aims to fix this — emerging).

### 7.9 Ambient mesh / eBPF (sidecar-less)

Istio **Ambient** splits the data plane: a per-node **ztunnel** handles L4 mTLS for all pods on the node (no per-pod sidecar), with optional per-namespace **waypoint** proxies for L7. **Cilium** uses eBPF + (in some modes) mTLS at the kernel/datapath layer. Benefit: dramatically lower per-pod overhead; tradeoff: newer, different security boundary (the node agent's compromise affects all pods on the node).

### 7.10 Lesser-known behaviors

- A server's **CertificateRequest** CA-name list is a *hint*; some clients ignore it and present the wrong cert → confusing failures.
- **Renegotiation** (TLS 1.2) for client-cert-on-demand is a known vuln class (CVE-2009-3555); TLS 1.3 removed renegotiation, replacing it with post-handshake auth (rarely used).
- **Session tickets** can undermine forward secrecy if the ticket-encryption key is long-lived — rotate ticket keys.
- Envoy/Istio extract the SPIFFE ID from the **first URI SAN**; multiple URI SANs can confuse policy — keep one.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Where to enforce identity

| Approach | Encrypts traffic | Authenticates caller | App code change | Ops complexity | Use when |
|---|---|---|---|---|---|
| **Network policy (IP/firewall)** | No | No (IP only) | None | Low | Coarse segmentation only |
| **App-layer tokens (JWT/OAuth)** | Needs TLS too | Yes (bearer) | Yes | Medium | User auth; cross-trust APIs |
| **Manual mTLS in app** | Yes | Yes (cert) | Yes (lots) | High | Few services, no mesh |
| **mTLS via service mesh** | Yes | Yes (SPIFFE) | None | Medium-High | Many services, k8s, zero-trust |
| **SPIFFE/SPIRE direct** | Yes | Yes (SVID) | Some (library) | Medium | Heterogeneous (VMs+k8s), no mesh |

### 8.2 Revocation strategy

| Strategy | Latency | Availability dependency | Privacy | Best for |
|---|---|---|---|---|
| **CRL** | Cache-stale | Periodic fetch | OK | Few, long-lived certs |
| **OCSP** | Per-check RTT | Hard (responder up) | Leaks peer | Browser PKI |
| **OCSP stapling** | None for client | Server fetches | Good | Public TLS servers |
| **Short-lived certs** | None | CA must be HA | Good | Mesh/workload identity |

### 8.3 Mesh choice (high level, version-specific — verify current state)

| | **Istio (sidecar)** | **Linkerd** | **Istio Ambient / Cilium** |
|---|---|---|---|
| Proxy | Envoy | Rust micro-proxy | ztunnel / eBPF |
| Footprint per pod | Higher | Low | Lowest (per node) |
| L7 features | Richest | Focused/simple | Via waypoints |
| mTLS automation | Yes (SPIFFE) | Yes (auto) | Yes |
| Maturity (as of writing) | Very mature | Mature | Newer |

**Use mTLS-via-mesh when:** many services, Kubernetes, zero-trust mandate, you don't want app changes.
**Use SPIFFE/SPIRE directly when:** mixed VM+container fleets, no mesh, you control the TLS stack.
**Use manual mTLS when:** a handful of services or a single critical link (e.g., a B2B integration) where a mesh is overkill.
**Avoid mTLS when:** public browser endpoints (use server TLS + OAuth), or where the operational maturity to run a CA/rotation isn't there yet (a botched mTLS rollout causes more outages than it prevents breaches).

---

## 9. Failure modes & debugging

### 9.1 Expired certificate (the classic outage)

**Symptom:** sudden total failure of a service's connections at a precise timestamp; logs full of `certificate_expired` / `notAfter`. Real incidents: numerous high-profile outages (telecoms, SaaS, even browser update systems) have been traced to a single expired internal cert.
**Diagnose:**
```bash
openssl s_client -connect host:8443 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -noout -dates              # check notAfter
istioctl proxy-config secret <pod> -o json | jq '.dynamicActiveSecrets'  # mesh certs + expiry
```
**Fix/prevent:** automated rotation (cert-manager/SPIRE), **expiry alerting with days of lead time**, short-lived certs so the renewal path is exercised constantly (a renewal path that runs every hour can't silently rot for a year).

### 9.2 "unable to find valid certification path to requested target" (Java)

**Cause:** missing intermediate in the chain the peer sends, OR the CA isn't in your truststore.
**Diagnose:** `openssl s_client -connect host:443 -showcerts` and inspect whether intermediates are present; `keytool -list -keystore truststore.p12` to confirm the CA is trusted. Run the JVM with `-Djavax.net.debug=ssl:handshake` for a full handshake trace.
**Fix:** include intermediates in the server chain; import the correct CA into the truststore.

### 9.3 Hostname/SAN mismatch

**Symptom:** `No subject alternative names matching IP/DNS ... found` or `HostnameVerifier` failure.
**Cause:** cert SAN doesn't match the name the client connected to (e.g., connecting by IP to a DNS-SAN cert, or CN-only legacy cert).
**Fix:** issue certs with the correct SAN entries; for SPIFFE, match on URI SAN, not hostname.

### 9.4 Untrusted CA / `unknown_ca`

**Cause:** peer presents a cert from a CA the verifier doesn't trust (common right after CA rotation if the new CA wasn't distributed first).
**Fix:** follow the CA-rotation overlap (§7.3); confirm trust bundles include the issuing CA.

### 9.5 Clock skew

**Symptom:** intermittent `notBefore`/`certificate_expired` on a subset of nodes; correlates with bad NTP.
**Diagnose:** compare node clocks; check `chronyc tracking`.
**Fix:** enforce NTP/chrony; small skew tolerance.

### 9.6 PERMISSIVE-mode false sense of security

**Symptom:** you believe traffic is encrypted/authenticated but telemetry shows plaintext.
**Diagnose:** check mTLS coverage metrics / `linkerd viz` / Istio Kiali security badges; `PeerAuthentication` mode.
**Fix:** move to `STRICT`; verify no plaintext remains first.

### 9.7 Issuance/CA outage with short-lived certs

**Symptom:** mesh-wide degradation as certs expire and can't renew because the CA/control plane is down.
**Diagnose:** control-plane health (`istioctl proxy-status`, SPIRE healthcheck), renewal logs.
**Fix:** make the CA tier-0 HA; ample `renewBefore` margin; cached/last-good behavior; alert on rotation failures, not just expiry.

### 9.8 Handshake debugging toolkit

- `-Djavax.net.debug=ssl:handshake:verbose` (JVM) — full handshake trace.
- `openssl s_client -connect ... -cert ... -key ... -state -msg` — verbose handshake, message-by-message.
- `tcpdump`/Wireshark on the SNI/ClientHello to see negotiated version/cipher (handshake metadata is visible even when content isn't).
- `istioctl proxy-config secret|cluster|listener`, `linkerd check`, `spire-agent api fetch x509`.

---

## 10. Interview drill

**Q1. What's the difference between TLS and mTLS, and what extra messages does mTLS add to the handshake?**
*Model answer:* TLS authenticates only the server to the client; mTLS authenticates both. mTLS adds a **CertificateRequest** from the server, and a **Certificate** + **CertificateVerify** from the client. The client's CertificateVerify is a signature over the handshake transcript proving it owns the private key for the cert it presented; the server then validates the client's chain.
- *Follow-up: Why is CertificateVerify needed — isn't sending the cert enough?* Anyone can copy a public cert; CertificateVerify proves possession of the matching **private key** by signing live handshake data.
- *Follow-up: What changed between TLS 1.2 and 1.3 for mTLS?* 1.3 is 1-RTT, encrypts the certificates, mandates forward secrecy, and removed renegotiation (the old way of doing on-demand client auth), replacing it with post-handshake auth.
- *Follow-up: Cost of mTLS vs server-only?* ~one extra signature (client) + one chain verification (server) per handshake; negligible on pooled/warm connections.

**Q2. Walk me through chain-of-trust verification.**
*Model answer:* Build a path from the leaf to a trusted root, verifying each issuer signature; check validity windows, Basic Constraints (CA:TRUE for non-leaves), Key Usage/EKU, the SAN/identity match, and optionally revocation. The peer sends leaf+intermediates; you supply the trusted root.
- *Follow-up: Why keep roots offline?* To limit blast radius — day-to-day issuance is by intermediates; a compromised intermediate is revocable without burning the root.
- *Follow-up: Most common chain error in Java?* "unable to find valid certification path" — usually a missing intermediate or a CA not in the truststore.

**Q3. (Senior signal) Short-lived certs vs. CRL/OCSP — which do you choose for a service mesh and why?**
*Model answer:* Short-lived certs. Revocation via CRL/OCSP is fragile: latency, an availability dependency on the responder, privacy leakage, and clients that skip the check. With ~1h certs, "revocation" is just non-renewal, and the renewal path is exercised constantly so it can't silently rot. The tradeoff is the CA becomes a tier-0 HA dependency, which a mesh control plane is designed to be.
- *Follow-up: What new failure mode does this introduce?* CA/issuance outage → mass expiry. Mitigate with HA control plane, generous `renewBefore`, last-good caching, and alerting on rotation failures.
- *Follow-up: Where would you still keep OCSP stapling?* Public-facing TLS servers, where clients are browsers and certs are longer-lived.

**Q4. What is SPIFFE and what's in an SVID?**
*Model answer:* SPIFFE is a vendor-neutral standard for naming/proving workload identity. A SPIFFE ID is a URI (`spiffe://trust-domain/path`). An SVID is the credential carrying it — usually an **X.509-SVID** (cert with the SPIFFE ID as a **URI SAN**), or a **JWT-SVID** when mTLS isn't possible.
- *Follow-up: How does a workload get an SVID without a baked-in secret?* It calls the SPIRE agent's Workload API over a Unix socket; the agent attests the process (kernel facts: pod, namespace, service account), matches a registration entry, and mints a short-lived SVID — no secret on disk.
- *Follow-up: What is attestation and why can't the workload lie?* The agent observes out-of-band facts about the process (PID/cgroup → pod/SA) rather than trusting claims the process makes.

**Q5. (Senior signal) Your team wants mTLS everywhere. Mesh sidecars vs. SPIFFE-in-app vs. manual TLS — how do you decide?**
*Model answer:* Default to **mesh** if you're on Kubernetes with many services and want zero app changes + free policy/telemetry, accepting per-pod proxy overhead. Use **SPIFFE/SPIRE directly** for heterogeneous fleets (VMs+containers) or where you can't run a mesh, accepting a library integration. Use **manual mTLS** only for a small number of critical links (e.g., one B2B integration) where a mesh is overkill. Avoid for public browser endpoints. The deciding factors: number of services, platform homogeneity, operational maturity to run a CA, and tolerance for proxy overhead.
- *Follow-up: Biggest operational risk of the mesh choice?* The control plane/CA becomes tier-0; cert rotation outages. Budget for HA and expiry alerting.
- *Follow-up: How do sidecar-less designs change the calculus?* Lower per-pod overhead (per-node ztunnel/eBPF) at the cost of a different, newer security boundary.

**Q6. How does mTLS implement zero trust?**
*Model answer:* It replaces network-location trust with cryptographic identity per connection. Every hop authenticates both ends; authorization is keyed on the verified SPIFFE identity, not the IP. "Inside the firewall" grants nothing.
- *Follow-up: Does mTLS alone give zero trust?* No — it's authentication; you still need authorization policy and continuous verification.
- *Follow-up: Map this to BeyondProd/NIST 800-207.* mTLS is the service-identity authentication primitive underpinning the "never trust the network" principle.

**Q7. (Senior signal) Walk me through rotating your root CA across a live fleet without downtime.**
*Model answer:* Overlap: (1) distribute the new CA into *all* trust bundles so both old and new are trusted; (2) switch issuance to the new CA; (3) after all old leaves have expired, remove the old CA. Failing to do step 1 first causes `unknown_ca` outages.
- *Follow-up: How does SPIRE/mesh automate this?* Continuous trust-bundle streaming keeps overlapping CAs in every peer's bundle.
- *Follow-up: How to verify before switching?* Confirm new CA present in all bundles via control-plane status before flipping issuance.

**Q8. What is certificate pinning, and what's the danger?**
*Model answer:* Pinning hardcodes the expected server cert/SPKI hash in the client, so even a valid cert from a trusted-but-different CA is rejected — defends against CA compromise/mis-issuance. Danger: rotating the pinned cert bricks all clients; always pin a **backup** key.
- *Follow-up: Pin the leaf or the CA?* Often the **SPKI** (public key) or an intermediate, to survive leaf rotation while keeping protection; trade flexibility vs. strictness.
- *Follow-up: Where is pinning common?* Mobile apps and B2B integrations with out-of-band key exchange.

**Q9. Why must you keep `setEndpointIdentificationAlgorithm("HTTPS")` on raw Java SSLSockets?**
*Model answer:* Without it the JVM validates the chain but **not** that the cert's SAN matches the host you dialed — so any cert from a trusted CA is accepted, enabling impersonation. `HttpsURLConnection`/`HttpClient` enable it automatically.
- *Follow-up: How does this differ in SPIFFE mTLS?* You match the URI SAN (SPIFFE ID) against an allow-list instead of a hostname.

**Q10. A service starts failing all connections at exactly 02:00 UTC. First three things you check?**
*Model answer:* (1) cert expiry (`openssl ... -dates`, `istioctl proxy-config secret`); (2) recent CA rotation / trust-bundle propagation (`unknown_ca`?); (3) clock skew/NTP. Then check renewal logs and control-plane health.
- *Follow-up: Why does expiry hit at a precise time?* `notAfter` is an absolute timestamp; everything depending on it fails simultaneously.
- *Follow-up: How would you have prevented it?* Automated short-lived rotation + multi-day expiry alerting + rehearsed rotation in staging.

**Q11. Explain forward secrecy and why TLS 1.3 mandates it.**
*Model answer:* Each session derives its key from **ephemeral** ECDHE keys discarded after the handshake, so stealing the long-term private key later can't decrypt past recorded traffic. 1.3 removed non-forward-secret key exchange (static RSA) entirely.
- *Follow-up: How can session tickets undermine PFS?* A long-lived ticket-encryption key effectively re-introduces a static secret — rotate ticket keys frequently.

**Q12. What's the risk with TLS 1.3 0-RTT and when is it acceptable?**
*Model answer:* 0-RTT early data is **replayable**, so only use it for idempotent operations; never for state-changing requests. Many meshes disable it.

---

## 11. Glossary

- **AEAD** — Authenticated Encryption with Associated Data; encryption + integrity in one (AES-GCM, ChaCha20-Poly1305).
- **AES / AES-NI** — symmetric cipher / CPU instructions that accelerate it.
- **ALPN** — Application-Layer Protocol Negotiation; picks h2/http1.1 during the handshake.
- **Ambient mesh** — sidecar-less mesh data plane (per-node ztunnel + optional waypoints).
- **Attestation** — proving what a workload/node is, from observed facts, to issue it an identity.
- **CA (Certificate Authority)** — trusted entity that signs certs.
- **Chain of trust** — leaf → intermediate(s) → root path, each signed by the next.
- **CN (Common Name)** — legacy subject identity field; superseded by SAN.
- **CRL** — Certificate Revocation List; signed list of revoked serials.
- **CSR** — Certificate Signing Request; a public key + identity sent to a CA to be signed.
- **DER / PEM / PKCS#12 / PKCS#8 / JKS** — cert/key encodings and keystore formats.
- **ECDHE** — Ephemeral Elliptic-Curve Diffie-Hellman; forward-secret key agreement.
- **ECDSA / Ed25519 / RSA** — signature/key algorithms.
- **EKU (Extended Key Usage)** — allowed cert uses (`serverAuth`, `clientAuth`).
- **Envoy** — high-performance proxy used as Istio's sidecar.
- **Forward secrecy (PFS)** — past sessions stay safe if long-term key leaks.
- **HMAC** — keyed hash for integrity/authenticity.
- **HSM / KMS** — hardware/managed key store; keeps private keys off the host.
- **Intermediate CA** — online CA that does daily issuing, signed by the root.
- **Istio / Istiod** — service mesh / its control plane and mesh CA.
- **JWT-SVID** — JWT form of a SPIFFE credential, for non-mTLS paths.
- **Keystore / Truststore** — your identity (cert+key) / the CAs you trust (Java).
- **Leaf cert** — the end-entity (workload/server/client) cert.
- **Linkerd** — lightweight service mesh with a Rust proxy.
- **MAC** — Message Authentication Code; integrity + sender-knew-the-key.
- **mTLS** — mutual TLS; both peers present and verify certs.
- **NTP / chrony** — clock synchronization (critical for cert validity checks).
- **OCSP / OCSP stapling** — real-time revocation check / server-attached revocation proof.
- **PeerAuthentication / AuthorizationPolicy** — Istio CRDs for mTLS mode / identity authz.
- **PKI** — Public Key Infrastructure; the whole CA/cert/policy system.
- **Root CA** — self-signed trust anchor, kept offline.
- **RTT** — round trip time; TLS 1.3 handshake is 1-RTT.
- **SAN (Subject Alternative Name)** — modern identity field (DNS/IP/URI); URI SAN carries the SPIFFE ID.
- **Service mesh** — infra that handles service-to-service comms (mTLS, routing, telemetry) outside app code.
- **Sidecar** — proxy injected per pod to mediate traffic.
- **SNI (Server Name Indication)** — hostname in ClientHello for cert selection/routing.
- **SPIFFE** — standard for workload identity (SPIFFE ID URIs).
- **SPIFFE ID** — `spiffe://trust-domain/path` workload name.
- **SPIRE** — reference SPIFFE implementation (server + agents + Workload API).
- **SVID** — SPIFFE Verifiable Identity Document (X.509-SVID or JWT-SVID).
- **TLS 1.2 / 1.3** — Transport Layer Security versions.
- **Trust bundle** — the set of CA certs a party trusts.
- **Trust domain** — the boundary of a single SPIFFE root of trust.
- **Workload API** — local (Unix-socket) API where SPIRE hands out SVIDs.
- **X.509** — certificate format binding a public key to an identity.
- **Zero trust** — never trust by network location; verify identity per request.
- **ztunnel** — per-node L4 proxy in Istio Ambient.
- **0-RTT** — TLS 1.3 resumption sending data in the first flight (replayable).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **TLS** = server auth + encryption. **mTLS** = both sides present+verify certs. Extra mTLS messages: server **CertificateRequest**; client **Certificate** + **CertificateVerify**.
- **CertificateVerify** = signature over the transcript proving private-key possession. **Finished** = transcript MAC (anti-tamper).
- **Chain verify:** leaf → intermediates → trusted root; check signatures, dates, BasicConstraints, EKU, **SAN/identity**, (optional) revocation.
- **Identity** = SPIFFE ID (`spiffe://trust-domain/path`) in the **URI SAN**. **SVID** = the cert (X.509-SVID) or JWT carrying it.
- **SPIRE** mints short-lived SVIDs via the Workload API over a **Unix socket** after **attesting** the process — **no secret on disk.**
- **Mesh:** app→localhost sidecar→**mTLS**→peer sidecar→localhost peer app. Istio modes: `STRICT`/`PERMISSIVE`/`DISABLE`.
- **Prefer TLS 1.3** (1-RTT, AEAD-only, mandatory forward secrecy), **ECDSA P-256** keys.
- **Revocation:** prefer **short-lived certs** (minutes–hours) over CRL/OCSP; rotate at **~50–66% of TTL** (`renewBefore`).
- **CA rotation:** add new CA to all bundles → switch issuance → remove old after expiry.
- **Java switches:** server `setNeedClientAuth(true)`; client raw sockets need `setEndpointIdentificationAlgorithm("HTTPS")`.
- **#1 outage:** **expired certs.** #1 alert: cert expiry with days of lead time. #1 Java error: missing intermediate ("valid certification path").
- **Debug:** `openssl s_client -showcerts`, `-Djavax.net.debug=ssl:handshake`, `istioctl proxy-config secret`, `spire-agent api fetch x509`.
- **Anti-patterns:** disabling SAN check; `want` (not `need`) client auth; trusting public CA internally; long-lived keys in images; single pin; PERMISSIVE forever.

### 12.2 Self-test (no answers — recall practice)

1. Trace a TLS 1.3 mTLS handshake message-by-message, naming exactly which message proves private-key possession for each peer and why copying a cert isn't enough.
2. Your Java client throws "unable to find valid certification path." List the two most likely causes and the exact commands you'd run to distinguish them.
3. Design a root-CA rotation for a 5,000-pod mesh with zero downtime. State the ordering and the failure that occurs if you get the order wrong.
4. Argue for or against using short-lived certs instead of OCSP for an internal mesh, naming the new tier-0 dependency you create and three ways to harden it.
5. A service fails all connections at exactly midnight UTC. Give your first three diagnostic steps with the actual commands, and explain why the failure is simultaneous rather than gradual.
6. Explain how a SPIRE workload obtains an identity without any secret ever touching disk, and what "attestation" prevents an attacker from doing.
7. When would you choose manual in-app mTLS over a service mesh, and when would you choose neither? Give the deciding factors.
```
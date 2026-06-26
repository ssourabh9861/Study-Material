# Supply-Chain Security

*An exhaustive engineering-handbook chapter on securing the software supply chain — for senior Java/JVM backend developers who want to master it from first principles to deep internals.*

---

## 1. Overview & where it fits

### What it is

**Software supply-chain security** is the discipline of protecting *everything that goes into building and shipping your software* — not just the code you write, but the third-party dependencies you pull in, the build tools and compilers you run, the CI/CD pipelines that assemble artifacts, the container base images you layer on, the secrets and credentials that flow through the process, and the channels through which artifacts are distributed and deployed.

The phrase "supply chain" is borrowed from manufacturing. A car manufacturer does not mine its own steel, refine its own rubber, or fabricate its own microchips — it assembles components from hundreds of suppliers, each of whom has their own suppliers. If a supplier ships a defective brake part, every car built with it is compromised, and the defect may not surface until far downstream. **Software is identical:** a modern Java service is perhaps 5–10% your own code and 90–95% transitive dependencies, base images, and build tooling you did not write and rarely audit. The "supply chain" is the entire graph of *where your final artifact came from*.

> **Beginner note — what is a "transitive dependency"?** When your `pom.xml` (Maven) or `build.gradle` (Gradle) declares a dependency like `spring-boot-starter-web`, that library itself depends on other libraries (Jackson, Tomcat, etc.), which depend on still others. The libraries *you* declare are **direct dependencies**; the ones pulled in automatically *because your direct dependencies need them* are **transitive dependencies**. A typical Spring Boot app declares ~15 direct dependencies but resolves to 150–250 total artifacts. You audited zero of the transitive ones.

### The problem it solves

The traditional security model assumed the threat was *outside* — an attacker on the network trying to break *in* to running software. Supply-chain security addresses a different, harder threat: the attacker **gets their malicious code in before you even build**, by compromising something you trust and pull in. Your firewall, your WAF, your runtime hardening — none of it helps, because the malicious code arrives *as part of your own signed, "trusted" artifact*. You ship the backdoor yourself, sign it, and deploy it to production with your own credentials.

This is qualitatively worse than a normal vulnerability because:

- **Blast radius is enormous.** One compromised popular library or build tool affects thousands of downstream organizations simultaneously. SolarWinds' compromised update reached ~18,000 customers.
- **Trust is the attack vector.** The whole point of a dependency or a CI system is that you trust it. The attack subverts the trust relationship itself.
- **Detection is delayed.** Malicious code that behaves normally until a trigger condition can sit dormant for months. The xz backdoor (2024) was caught by luck, days before it would have landed in stable Linux distributions.

### When you reach for it

You "reach for" supply-chain security continuously, not as a one-time project. Concretely, you invest here when:

- You ship software that other people run (SaaS, libraries, container images, firmware).
- You operate in a regulated environment (US Executive Order 14028, EU Cyber Resilience Act, FedRAMP, PCI-DSS) that increasingly *mandates* SBOMs and provenance.
- You have a large or fast-moving dependency tree (most modern services).
- You've had — or fear — an incident involving a compromised package, leaked secret, or tampered build.

### One-paragraph mental model

> Treat your build pipeline as a **factory with a chain of custody**. Every input (source commit, dependency, base image, tool) must be **identified** (you know exactly what it is, by cryptographic hash), **verified** (it is what it claims to be and came from who it claims), and **recorded** (you can produce a tamper-evident bill of materials and provenance attestation describing how the artifact was made). The factory itself must run with **least privilege on ephemeral, hardened workers** so a single compromised step cannot poison everything else. The output artifact is **signed** so consumers can verify it came from your factory unmodified. Supply-chain security is, end to end, the practice of making the question *"can I prove where this bit of running code actually came from?"* answerable with cryptographic confidence at every link.

### Where it fits in the CI/CD & release-engineering landscape

Supply-chain security is a cross-cutting concern layered *on top of* the normal CI/CD flow:

```
Developer → SCM (Git) → CI build → Artifact repo → CD deploy → Runtime
   │           │            │            │             │           │
 commit     branch       pinned       signed +      admission   runtime
 signing    protection   actions,     SBOM +        controller  monitoring
            (2FA)        ephemeral     provenance    verifies    (eBPF, etc.)
                         runners,      attestation   signatures
                         SCA scan      (cosign)      + provenance
```

Each stage has supply-chain controls. The rest of this document walks each in depth.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Every term is defined the moment it appears.

### 2.1 What "trust" means cryptographically

Most supply-chain controls reduce to two cryptographic primitives. Internalize these first; everything else is application.

**Cryptographic hash (digest).** A function (SHA-256 is the workhorse) that turns any input — a file, a layer, an artifact — into a fixed-size fingerprint (256 bits / 64 hex chars for SHA-256). Two properties matter:
- **Deterministic:** the same input always yields the same hash.
- **Collision-resistant:** it is computationally infeasible to find two different inputs with the same hash, and infeasible to craft an input that produces a *chosen* hash.

So if you record `sha256:abc123...` for a dependency and later the byte content differs by even one bit, the hash won't match. A hash is therefore a **content address**: "the thing whose SHA-256 is abc123" is an unambiguous, tamper-evident name. When you "pin a dependency by digest," you are saying *give me exactly these bytes, nothing else.*

> **Beginner note — why not just pin by version number?** A version tag like `1.2.3` or a Docker tag like `:latest` is a *mutable pointer*. The registry owner (or an attacker who compromises it) can re-point `1.2.3` to different bytes. A digest cannot be re-pointed — it *is* the bytes. This is the difference between "I want the book called *Moby Dick*" (someone could swap the contents) and "I want the book whose every page hashes to this exact fingerprint."

**Digital signature.** Uses asymmetric (public-key) cryptography. A signer has a **private key** (kept secret) and a **public key** (shared freely). The signer computes a signature over a message (usually over the message's hash) using the private key. Anyone with the public key can **verify** that (a) the signature was produced by the holder of the matching private key, and (b) the message has not been altered since signing. Signatures give you **authenticity** ("who made this") and **integrity** ("it wasn't changed"), but *not* secrecy (signatures don't hide content).

> **Beginner note — asymmetric vs symmetric.** Symmetric crypto (AES) uses *one shared secret* for both encryption and decryption — fine for two parties who already share a key. Asymmetric crypto (RSA, ECDSA, Ed25519) uses a *key pair* where the keys are mathematically linked but you cannot derive the private key from the public one. This lets a signer publish their public key to the whole world so anyone can verify, while only the signer can produce valid signatures.

### 2.2 The threat model

A **threat model** is a structured enumeration of *who might attack you, what they're after, and at which points*. Threat modeling answers "where are the doors and windows, and which ones are unlocked?"

For supply chains, the canonical framing comes from **SLSA** (see §6) and the broader research community. The attack surface spans the path from source to consumer:

| # | Threat (attack point) | What the attacker does | Real example |
|---|---|---|---|
| A | **Submit unauthorized change to source** | Push malicious code via stolen developer creds or compromised contributor account | Many; account takeover |
| B | **Compromise source control system** | Tamper with the Git server itself | PHP `git.php.net` compromise (2021) |
| C | **Build from a source not matching the repo** | Build runs on attacker-controlled code, not what's in version control | — |
| D | **Compromise the build process** | Inject code during the build (compiler, plugin, runner) | **SolarWinds SUNBURST (2020)** |
| E | **Use a compromised dependency** | A library you depend on is malicious or backdoored | **xz/liblzma (2024)**, `event-stream` (2018) |
| F | **Upload a modified/forged artifact** | Bypass the build entirely and push a poisoned artifact to the repo | Codecov bash uploader (2021) |
| G | **Compromise the artifact repository** | Tamper with stored artifacts or serve malicious ones | npm/PyPI typosquats |
| H | **Use a compromised package at deploy** | The deployed artifact is swapped between repo and runtime | — |

> **Beginner note — what is "source control" / SCM / "version control"?** Software is stored in a **version control system** (VCS) such as **Git**, hosted on a server like GitHub, GitLab, or Bitbucket — collectively the **source control management (SCM)** system. It tracks every change ("commit"), who made it, and lets teams collaborate. It is the *origin* of your supply chain — the first link.

#### Case study: SolarWinds (threat D — build compromise)

In 2020, attackers (later attributed to a nation-state) compromised SolarWinds' build environment for its **Orion** network-monitoring product. The crucial detail: **the source code in version control was clean.** The attackers inserted malicious code (SUNBURST/SUNSPOT) *during the build*, by planting a tool on the build server that watched for the Orion compilation and substituted a backdoored source file just for the compile, then restored the clean file. The resulting `.dll` was then signed with SolarWinds' *legitimate* code-signing certificate and shipped via normal auto-update to ~18,000 organizations, including US federal agencies.

The lessons that drive modern controls:
- **Reviewing source is not enough** — the build itself is a target. → ephemeral, hardened, isolated build runners; provenance attestation describing *what was actually built and how*.
- **A valid signature on a tampered artifact is worthless** if the tampering happened *before* signing. → signing must attest to a *verifiable build process*, not just "these bytes."
- **Reproducible builds** (independent rebuilds producing bit-identical output) would have let a third party detect the discrepancy.

#### Case study: xz / liblzma (threat E — dependency compromise)

In early 2024, a backdoor was discovered in `xz-utils` (specifically `liblzma`), a compression library that is a transitive dependency of `systemd` on many Linux distros, which in turn is linked into `sshd`. The attack was a *patient social-engineering long con*: a persona ("Jia Tan") spent ~2 years building trust as a maintainer of the under-resourced xz project, eventually gaining commit rights from the burned-out original maintainer. The malicious code was hidden in **test fixture binary blobs** and activated by a build-time script (`build-to-host.m4`) that only triggered when building distro packages — not visible in the Git source tree's normal files. The payload hooked into `sshd`'s authentication to allow remote code execution for someone holding a specific key. It was caught by chance — a Microsoft engineer (Andres Freund) noticed `sshd` was ~500ms slower and investigated — *days* before it would have shipped in Debian/Fedora stable.

Lessons:
- **Maintainer trust is an attack vector.** Open-source projects with one overworked maintainer are soft targets. → diversity of maintainers, scrutiny of new committers, scrutiny of build scripts not just source.
- **Build scripts and test fixtures are code.** The malicious logic lived in autotools macros and "test" binaries, not in `.c` files. → scan and review the *entire* build, including generated/release tarballs which often differ from the Git tree.
- **Transitive depth hides risk.** Almost nobody depends on `liblzma` directly; it was 2–3 hops down.

### 2.3 The core defensive concepts (vocabulary)

These terms recur throughout. Learn them now.

**SBOM — Software Bill of Materials.** A machine-readable inventory of every component in a piece of software — name, version, supplier, license, and cryptographic hash — including transitive dependencies. The manufacturing analogy is exact: a physical bill of materials lists every part in a product. If a new vulnerability is announced in library X version Y, you query your SBOMs to instantly answer "which of my products contain X@Y?" The two dominant formats are **CycloneDX** and **SPDX** (§4, §5).

**SCA — Software Composition Analysis.** The practice (and the tools) of analyzing your dependency tree to identify known vulnerabilities, license risks, and outdated components. SCA tools (Dependabot, Snyk, OWASP Dependency-Check, Trivy) match your components against vulnerability databases. *SCA finds known-bad in things you didn't write*, as opposed to **SAST** (Static Application Security Testing), which finds bugs in code *you* wrote.

**CVE / CVSS / vulnerability database.** A **CVE** (Common Vulnerabilities and Exposures) is a globally unique identifier for a publicly known security flaw, e.g. `CVE-2021-44228` (Log4Shell). **CVSS** (Common Vulnerability Scoring System) is a 0.0–10.0 severity score. Vulnerability databases — the **NVD** (US National Vulnerability Database), **OSV** (Google's Open Source Vulnerabilities DB, the modern, ecosystem-aware choice), and **GitHub Advisory Database** — map CVEs to affected package versions. SCA tools consume these.

**Provenance.** Verifiable metadata describing *how, where, and from what* an artifact was produced: which source commit, which build system, which builder identity, which inputs. Provenance answers "is this artifact really the output of building commit `abc` on our trusted CI, or did someone forge it?" The standard data format is an **in-toto attestation** (§6).

**Attestation.** A signed statement *about* an artifact (identified by digest). "Attestation" is the general term; provenance is one *kind* of attestation. Others include SBOM attestations, vulnerability-scan attestations, and test-result attestations. The format: a *subject* (what artifact, by digest), a *predicate type* (what kind of claim), and a *predicate* (the actual data), all wrapped and signed.

**Artifact signing.** Cryptographically signing your build output (a container image, a `.jar`, a binary) so consumers can verify it came from you, unmodified. Modern tooling: **Sigstore / cosign** (§6).

**SLSA — Supply-chain Levels for Software Artifacts** (pronounced "salsa"). A framework of incremental security *levels* (L0–L3+) describing how tamper-resistant your build process is, focused mainly on **build integrity** and **provenance** (§6, §7).

**Pinning.** Specifying an exact, immutable version of an input — by cryptographic digest, not a mutable tag. You pin dependencies (lockfiles), base images (`@sha256:...`), and CI actions (`@<full-commit-sha>`).

**Ephemeral runner.** A CI worker (the machine that executes build steps) that is freshly created for one job and destroyed afterward, so nothing persists between jobs. The opposite — a long-lived, reused runner — accumulates state (caches, leaked secrets, malware) that one compromised job can pass to the next.

**Least privilege.** The principle that every component (a CI job, a token, a service account) should have only the minimum permissions needed for its task, and no more. A build job that only needs to read source and write one artifact should not hold admin credentials to your whole cloud account.

**Secrets scanning.** Detecting credentials (API keys, passwords, tokens, private keys) accidentally committed to source code, build logs, or images, so they can be revoked before an attacker uses them.

With this vocabulary in hand, we go under the hood.

---

## 3. How it works internally

This section traces the *actual mechanics* of the major controls, step by step. This is the heart of the document.

### 3.1 Dependency resolution and the lockfile (the foundation everything else builds on)

Before you can secure dependencies, you must understand exactly how they are chosen, because the attack surface lives in the *resolution* algorithm.

#### Maven's resolution: "nearest wins"

When Maven builds the dependency graph, multiple paths can request different versions of the same artifact. Maven uses **nearest-wins (dependency mediation)**: the version closest to the root of the tree (fewest hops in your `pom.xml`) wins. If two are at the same depth, the *first declared* wins.

Step by step, when you run `mvn package`:

1. **Parse the POM.** Maven reads `pom.xml`, including inherited parent POMs and imported BOMs (`<dependencyManagement>`).
2. **Build the dependency graph.** It recursively resolves each dependency's own POM to discover transitive dependencies, building a directed graph.
3. **Mediate versions.** For each `groupId:artifactId`, apply nearest-wins to pick one version. `<dependencyManagement>` and BOMs can *force* a version regardless of depth.
4. **Apply exclusions and scopes.** Honor `<exclusions>` and dependency `scope` (compile/runtime/test/provided).
5. **Download artifacts.** For each resolved coordinate, fetch the `.jar` (and its `.pom`) from a repository (Maven Central, your internal Nexus/Artifactory, etc.) into the local `~/.m2/repository` cache *if not already present*.
6. **Verify (weakly, by default).** Maven downloads the artifact's `.sha1`/`.md5` checksum from the *same* repository and verifies the file matches — this protects against corruption, **not** against a malicious repository (the repo serves both the file and its checksum). Signature verification (PGP) is *not* on by default.

> **Beginner note — what is a BOM (Bill of Materials POM)?** Confusingly, Maven also uses "BOM" but means something different from an SBOM. A Maven BOM is a special POM imported into `<dependencyManagement>` that centrally declares versions for a family of libraries (e.g. `spring-boot-dependencies`), so you don't specify versions on each dependency. It controls *which versions you get*; an SBOM *records which versions you got*.

**The security gap:** Maven resolution is *not* pinned by default. A given build can resolve a *range* or pick up newer transitive versions over time, and the default checksum check trusts the repository. This is why you layer on a lockfile and signature verification.

#### Gradle's resolution and lockfiles

Gradle uses a different algorithm: **highest-version-wins** by default (the latest version among all requests), with rich conflict-resolution rules, constraints, and platform alignment. Crucially, Gradle has **first-class dependency locking**:

```
./gradlew dependencies --write-locks
```

This writes `gradle.lockfile` files pinning every resolved version. With `dependencyLocking` enabled and a lockfile present, the build *fails* if resolution would produce a different version — eliminating drift.

#### Maven's pinning options

Maven has no built-in lockfile, but you achieve pinning by:
- Declaring **exact versions** for everything (no ranges) — partial, since transitives can still float.
- Using a **reproducible-build BOM** that forces every transitive version.
- The community plugin **`io.github.chains-project:maven-lockfile`** or Maven's reproducible-builds support, which can generate and enforce a lockfile-like file (with hashes).
- Configuring `<checksumPolicy>fail</checksumPolicy>` and enabling **PGP signature verification** via the `maven-gpg-plugin` / `pgpverify-maven-plugin` so a tampered artifact is rejected.

**Why this matters for supply chain:** the lockfile, with **hashes**, is your first integrity control. It turns "give me roughly version 1.2.x" into "give me exactly these bytes." Without it, an attacker who compromises a transitive dependency's newer release (or your repository) can inject code with no version-number change you'd notice.

### 3.2 How an SCA scan works internally

An SCA tool answers "which of my components have known vulnerabilities?" The internal workflow:

1. **Component identification (the hard part).** The tool builds the same dependency graph your package manager would, or reads it from a lockfile / SBOM. For each component it derives a canonical identity — typically a **PURL** (Package URL), e.g. `pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1`. For binaries/containers it inspects metadata files (`pom.properties` inside jars, OS package DBs, `package-lock.json`, etc.) and sometimes hashes.

   > **Beginner note — what is a PURL?** A **Package URL** is a standardized string format that uniquely names a software package across ecosystems: `pkg:type/namespace/name@version`. It lets tools speak a common language about "the same package" regardless of where it came from. It's the join key between your SBOM and vulnerability databases.

2. **Vulnerability matching.** The tool downloads (or queries an API for) a vulnerability feed — **OSV**, NVD, GitHub Advisory DB, or a vendor's curated DB. Each advisory specifies affected version ranges per PURL/ecosystem. The tool intersects your component versions with affected ranges.

3. **Reachability / applicability analysis (advanced tools).** A naive match flags *every* CVE in *every* dependency, producing alert fatigue. Sophisticated tools (Snyk's reachability, GitHub's, some commercial SCA) do **call-graph analysis** to check whether your code actually *calls* the vulnerable function. A CVE in a code path you never invoke is far lower risk. This dramatically cuts false positives but requires static analysis of bytecode.

4. **Reporting & gating.** Results are scored (CVSS), deduplicated, and emitted as SARIF (a standard static-analysis result format), PR comments, or a build gate that fails CI above a severity threshold.

5. **Fix suggestion.** Tools compute the *minimal version bump* that clears the vulnerability and (Dependabot/Snyk/Renovate) open an automated pull request.

**Key internal subtlety — the false-negative problem.** SCA can only find *known* vulnerabilities present in its DB. A zero-day, an unreported backdoor (xz before disclosure), or a vulnerability in a component the tool failed to identify (e.g. a shaded/fat-jar that rebundled a library under a different name) will be missed. This is why SBOMs matter: they make *retroactive* analysis possible when the advisory lands later.

### 3.3 How container image scanning works (Trivy / Grype internals)

Container scanning is a special case of SCA over a layered filesystem.

> **Beginner note — what is a container image and a "layer"?** A **container** is a lightweight, isolated process bundle. A container **image** is a stack of read-only **layers** (filesystem diffs) plus metadata, identified by a digest. You start from a **base image** (e.g. `eclipse-temurin:21-jre`) and add layers (your app jar, config). Each layer is itself content-addressed. Tools like Docker or Buildah build images; registries (Docker Hub, ECR, GHCR) store them.

Internal flow of a scanner like **Trivy** (Aqua Security) or **Grype** (Anchore):

1. **Pull or read the image.** Either from a registry (by tag or digest) or a local tarball/OCI layout. The scanner does not run the image — it inspects it statically.
2. **Walk the layers.** It unpacks layers and assembles the merged root filesystem (respecting whiteouts where a layer deletes a file).
3. **Catalog OS packages.** It reads OS package databases to enumerate installed system packages and versions: `/var/lib/dpkg/status` (Debian/Ubuntu), the RPM DB (RHEL/Alpine uses `/lib/apk/db/installed`). This identifies the base-image software.
4. **Catalog language packages.** It finds application dependencies: `.jar` files (reading `pom.properties`/`MANIFEST.MF`), `package-lock.json`, `requirements.txt`, Go binaries' embedded module info, etc.
5. **Match against the vulnerability DB.** Trivy ships/updates a bundled DB (sourced from NVD, OSV, distro security trackers like Debian Security Tracker, Red Hat OVAL, Alpine secdb). Distro-specific data matters: a CVE may be "fixed" in a backported distro patch even though the upstream version string looks vulnerable — distro trackers encode this so you don't get false positives.
6. **Apply ignore rules & policy.** `.trivyignore`, VEX documents (see below), severity thresholds.
7. **Output.** Table, JSON, SARIF, CycloneDX/SPDX SBOM, exit code for CI gating.

> **Beginner note — what is VEX?** **VEX** (Vulnerability Exploitability eXchange) is a companion document to an SBOM that states, per vulnerability, whether your product is *actually affected* (`affected`, `not_affected`, `fixed`, `under_investigation`) and why (e.g. "the vulnerable function is never called"). It lets you suppress noise *with an auditable justification* instead of a blanket ignore.

### 3.4 How artifact signing with Sigstore/cosign works internally

This is the most important "new" mechanism and worth understanding deeply, because **keyless signing** is non-obvious.

#### The problem with traditional signing

Classic code signing (GPG, X.509 code-signing certs) requires you to **manage a long-lived private key**: generate it, store it securely (HSM, KMS), distribute the public key, and *never leak it*. Key management is hard; leaked keys cause disasters (SolarWinds-style). And in CI, a long-lived signing key sitting in a pipeline secret is a fat target.

#### Sigstore's "keyless" model

**Sigstore** flips this with **ephemeral keys + identity-based signing + a transparency log**. There is no long-lived signing key to steal. The actors:

- **Fulcio** — a **certificate authority (CA)** that issues *short-lived* (≈10-minute) signing certificates **bound to an OIDC identity** rather than to a long-lived key.
- **Rekor** — an immutable, append-only, cryptographically verifiable **transparency log** (a Merkle tree) recording every signing event publicly.
- **cosign** — the CLI client that orchestrates the flow and signs container images and arbitrary artifacts/attestations.

> **Beginner note — what is OIDC?** **OpenID Connect** is an identity layer on top of OAuth 2.0. It lets a trusted **identity provider (IdP)** — Google, GitHub Actions, your corporate SSO — issue a signed **token** asserting "this is identity X." GitHub Actions, for instance, can mint an OIDC token asserting "this job is running in repo `org/repo`, workflow `release.yml`, ref `refs/tags/v1.2.3`." Sigstore uses that as the *identity to bind the signature to.*

> **Beginner note — what is a CA / a transparency log / a Merkle tree?** A **Certificate Authority** is a trusted party that issues digital certificates vouching for an identity's public key. A **transparency log** is a public, append-only ledger where entries can be added but never altered or deleted, so anyone can audit it (the concept comes from **Certificate Transparency** for TLS certs). A **Merkle tree** is a tree of hashes where each parent hashes its children; the single root hash commits to the entire contents, so you can prove an entry is included and that the log hasn't been tampered with, efficiently.

#### The keyless signing flow, step by step

When CI runs `cosign sign $IMAGE_DIGEST`:

1. **Generate an ephemeral key pair** in memory. The private key will be discarded after a few minutes — it is never stored.
2. **Obtain an OIDC identity token.** In GitHub Actions, cosign requests a token from the Actions OIDC provider asserting the workflow identity. (Interactively, it opens a browser to log in via Google/GitHub.)
3. **Request a certificate from Fulcio.** cosign sends Fulcio the ephemeral *public* key and the OIDC token. Fulcio verifies the OIDC token, then issues a short-lived X.509 certificate that **binds the ephemeral public key to the OIDC identity** (the identity is embedded in the cert's SAN — Subject Alternative Name). Validity ≈ 10 minutes.
4. **Sign the artifact digest** with the ephemeral private key.
5. **Record in Rekor.** cosign uploads the signature, the certificate, and the artifact digest to the **Rekor** transparency log. Rekor returns a signed timestamp and an inclusion proof. This is the crucial trick: even though the signing certificate *expires* in 10 minutes, the Rekor entry is timestamped, so verifiers can later confirm "the signature was made *while the certificate was valid*."
6. **Discard the ephemeral private key.** Nothing sensitive persists.
7. **Store the signature** as an OCI artifact alongside the image in the registry (cosign uses a tag-based or OCI-referrers convention so the signature lives next to the image).

#### The keyless verification flow

When a consumer runs `cosign verify --certificate-identity=... --certificate-oidc-issuer=... $IMAGE`:

1. **Fetch the signature, certificate, and Rekor proof** for the image digest.
2. **Verify the certificate chains to Fulcio's trusted root.**
3. **Check the identity** in the cert SAN matches the expected signer (e.g. `https://github.com/myorg/myrepo/.github/workflows/release.yml@refs/tags/v1.2.3`) and the OIDC issuer matches (e.g. GitHub).
4. **Verify the Rekor inclusion proof** and the signed timestamp, confirming the signature existed during the cert's validity window.
5. **Verify the signature** over the image digest with the cert's public key.

If all pass, the consumer knows: *this exact image (by digest) was signed by precisely this CI workflow identity at this time, recorded publicly.* No key management, and the public Rekor log means a forged signature would have to also forge a public, append-only log — infeasible.

> Note: cosign *also* supports traditional **key-based** signing (`cosign generate-key-pair` + KMS-backed keys) for air-gapped or compliance scenarios where you cannot reach public Sigstore infrastructure. Many enterprises run a **private Sigstore** (self-hosted Fulcio/Rekor) for the keyless benefits without depending on the public good-instance.

### 3.5 How in-toto attestations and SLSA provenance are generated and structured

An **in-toto attestation** is the data structure that carries provenance. Its envelope (the **DSSE** — Dead Simple Signing Envelope) wraps a JSON statement and one or more signatures.

The **in-toto Statement** structure:

```json
{
  "_type": "https://in-toto.io/Statement/v1",
  "subject": [
    { "name": "myorg/myapp",
      "digest": { "sha256": "e3b0c44298fc1c149afbf4c8996fb924..." } }
  ],
  "predicateType": "https://slsa.dev/provenance/v1",
  "predicate": { /* the provenance facts: builder, build definition, ... */ }
}
```

- **`subject`** — *what* the attestation is about, identified by one or more digests. Binding to a digest (not a name/tag) is what makes it tamper-evident.
- **`predicateType`** — a URI naming the *schema* of the claim. SLSA provenance, SPDX SBOM, CycloneDX SBOM, test results, and VEX all have predicate types.
- **`predicate`** — the actual claim payload.

For **SLSA provenance v1**, the predicate captures:
- **`buildDefinition`**: `buildType` (what kind of build), `externalParameters` (the inputs that triggered it — e.g. the source repo + commit), `internalParameters`, and `resolvedDependencies` (the actual resolved inputs with digests).
- **`runDetails`**: `builder.id` (the *identity of the build platform* — e.g. `https://github.com/actions/runner`), `metadata` (invocation ID, timestamps), and optionally `byproducts`.

The whole Statement is signed (typically via cosign/Sigstore) into a DSSE envelope and attached to the artifact. **The builder, not the developer, generates and signs provenance** — that's the integrity guarantee. A developer cannot forge "this was built by GitHub Actions from commit abc" because the GitHub OIDC identity is what signs it.

#### How SLSA levels map to mechanism

| SLSA level | Requirement | Mechanism |
|---|---|---|
| **L0** | Nothing | — |
| **L1** | Provenance *exists* and is distributed | Build emits an attestation describing how it was built (can be unsigned/forgeable) |
| **L2** | Provenance is *signed* by a hosted build platform; tamper-resistant | Hosted CI (GitHub Actions + the official SLSA generator) signs provenance with its own identity |
| **L3** | Hardened, isolated builds; provenance is *non-forgeable* even by the project's own steps | Build runs in an isolated environment where user-controlled steps cannot access the signing material; the platform attests independently |

> SLSA v1.0 reframed levels around the **Build track** (L1–L3). Earlier drafts (v0.1) also discussed source and dependency requirements; v1.0 deliberately scoped levels to build integrity, with source/dependency tracks planned separately.

### 3.6 How CI/CD runner compromise propagates (and why ephemerality matters)

Trace what an attacker does after compromising a single build step — say, a malicious dependency runs arbitrary code during `mvn package` (build scripts and annotation processors run as your user):

1. **Code execution on the runner** as the CI job's user.
2. **Harvest secrets in scope.** It reads environment variables, mounted secret files, the cloud metadata endpoint (`169.254.169.254` on AWS/GCP), and the job's tokens. On a *persistent* runner it can also read **caches and leftover credentials from previous jobs.**
3. **Lateral movement.** With a broadly-scoped token (e.g. a `GITHUB_TOKEN` with write access, or admin cloud creds), it pushes malicious commits, alters other pipelines, or poisons the artifact cache that *other jobs* consume — exactly the SolarWinds class of attack.
4. **Persistence.** On a persistent runner it installs a backdoor that survives to taint future builds.

Each control short-circuits a step:
- **Ephemeral runners** (fresh VM/container per job, destroyed after) kill steps 3–4's persistence and remove cross-job leakage.
- **Least-privilege tokens** (read-only by default, scoped to one repo, short-lived) blunt steps 2–3.
- **Pinned actions/dependencies** reduce step 1's likelihood.
- **Network egress restrictions** prevent the runner from exfiltrating secrets or phoning home (step 2's payoff).

### 3.7 How secrets scanning works internally

Two modes:

**Pattern/entropy scanning.** Tools (gitleaks, TruffleHog, GitHub secret scanning) scan files (and Git history) for:
- **Known patterns** — regexes for provider-specific token formats (AWS `AKIA...`, GitHub `ghp_...`, Slack `xoxb-...`, private-key PEM headers). High precision because formats are distinctive.
- **High-entropy strings** — random-looking high-information-density blobs that *look* like secrets even without a known prefix. Higher recall, more false positives.

**Verification (advanced).** TruffleHog and GitHub's **validity checks** go further: they *test* a candidate credential against the provider's API to see if it's *live*, eliminating false positives and prioritizing active leaks. GitHub's **push protection** scans *before* a push completes and blocks it, stopping the secret from ever entering history.

The critical operational truth: **once a secret is in Git history, rotating it is the only real fix.** Removing the commit doesn't help — clones, forks, and the reflog retain it, and bots scrape public pushes within *seconds*. So the workflow is: detect → **revoke/rotate immediately** → then optionally scrub history.

---

## 4. The complete toolkit

Tables of the methods, classes, APIs, CLI commands, configs, and tools, with purpose, key parameters, and defaults. Flagged where version/vendor-specific.

### 4.1 Dependency management & SCA tools

| Tool | Ecosystem | What it does | Key invocation / config | Notes / defaults |
|---|---|---|---|---|
| **Dependabot** | GitHub-native | Dependency update PRs + security alerts | `.github/dependabot.yml` (`package-ecosystem: maven`, `schedule.interval`) | Free on GitHub; uses GitHub Advisory DB; opens PRs; **Dependabot version updates** vs **security updates** are separate features |
| **Renovate** | Multi-platform (GitHub/GitLab/Bitbucket) | Highly configurable dependency updates | `renovate.json`; supports grouping, automerge, lockfile maintenance | More flexible than Dependabot; self-hostable |
| **Snyk** | Multi-language, commercial | SCA + reachability + container + IaC | `snyk test`, `snyk monitor`, `snyk container test` | Reachability analysis; commercial DB; free tier limited |
| **OWASP Dependency-Check** | JVM/.NET/others, OSS | SCA via NVD matching | Maven plugin `org.owasp:dependency-check-maven`; `mvn dependency-check:check`; `failBuildOnCVSS` | Free; NVD-based; needs NVD API key now; CPE matching can be noisy |
| **OWASP dependency-track** | Platform, OSS | Continuous SBOM-based vuln monitoring | Ingests CycloneDX SBOMs via API | Server you run; great for fleet-wide SBOM analysis & VEX |
| **Trivy** | Containers/FS/repos/IaC, OSS | All-in-one scanner (vuln, secrets, misconfig, SBOM, license) | `trivy image`, `trivy fs`, `trivy repo`; `--severity`, `--exit-code`, `--ignore-unfixed`, `--format sarif/cyclonedx/spdx` | Aqua; fast; bundled DB auto-updates; default exit code 0 even on findings unless `--exit-code 1` |
| **Grype** | Containers/FS, OSS | Vulnerability scanner (pairs with Syft) | `grype <image>`; `--fail-on high`; reads Syft SBOMs | Anchore; `grype sbom:./sbom.json` |
| **grype/Syft** | OSS | **Syft** generates SBOMs; **Grype** scans | `syft <image> -o cyclonedx-json` | Syft = SBOM gen; Grype = scan |
| **Maven Enforcer** | Maven | Fail build on banned/insecure deps, version rules | `maven-enforcer-plugin` rules: `banDuplicatePomDependencyVersions`, `dependencyConvergence`, `requireUpperBoundDeps` | Build-time policy gate |
| **Gradle dependency locking** | Gradle | Pin resolved versions | `dependencyLocking { lockAllConfigurations() }`; `--write-locks` | Produces `gradle.lockfile`; fails on drift |

### 4.2 SBOM tools & formats

| Tool / format | Role | Key invocation | Notes |
|---|---|---|---|
| **CycloneDX** | SBOM **format** (OWASP) | — | Security-focused; supports VEX, vuln data, services, provenance; JSON/XML/protobuf |
| **SPDX** | SBOM **format** (Linux Foundation, ISO/IEC 5962) | — | License/compliance heritage; ISO standard; JSON/tag-value/RDF |
| **cyclonedx-maven-plugin** | Generate CycloneDX from Maven | `mvn cyclonedx:makeAggregateBom` | Reads the resolved dep tree; output `target/bom.json` |
| **cyclonedx-gradle-plugin** | Generate CycloneDX from Gradle | `./gradlew cyclonedxBom` | |
| **Syft** | Generate SBOM from images/dirs | `syft <src> -o spdx-json` / `-o cyclonedx-json` | Multi-ecosystem |
| **Trivy (SBOM mode)** | Generate or scan SBOMs | `trivy image --format cyclonedx -o sbom.json`; `trivy sbom sbom.json` | Can scan an existing SBOM |
| **bom (Kubernetes)** | SPDX generator | `bom generate` | SPDX-focused |

> **CycloneDX vs SPDX one-liner:** SPDX is the older, ISO-standardized, license-compliance-oriented format; CycloneDX is the newer, security/BOM-operations-oriented format with native vulnerability and VEX support. Many tools emit both; pick CycloneDX if your priority is security operations, SPDX if it's license compliance or regulatory mandates that name SPDX. They are convertible.

### 4.3 Signing, provenance & verification (Sigstore ecosystem)

| Tool / component | Role | Key commands / params | Defaults / notes |
|---|---|---|---|
| **cosign** | Sign/verify artifacts & attestations | `cosign sign $IMG`, `cosign verify`, `cosign attest --predicate sbom.json --type cyclonedx`, `cosign verify-attestation` | Keyless by default (uses public Fulcio/Rekor) unless `--key` |
| `cosign sign` | Sign an image by digest | `--yes` (no prompt), `--key`, `--certificate-identity` | Always sign by **digest** not tag |
| `cosign verify` | Verify signature | `--certificate-identity[-regexp]`, `--certificate-oidc-issuer` | **Must** pin expected identity + issuer, else verification is meaningless |
| `cosign attest` | Attach a signed attestation | `--predicate`, `--type slsaprovenance/cyclonedx/spdx/vuln` | DSSE envelope stored in registry |
| **Fulcio** | Short-lived cert CA bound to OIDC | (service) | Public good instance: `fulcio.sigstore.dev`; self-hostable |
| **Rekor** | Transparency log | `rekor-cli search/get` | Public: `rekor.sigstore.dev`; self-hostable |
| **Gitsign** | Keyless **commit** signing | `git commit -S` w/ gitsign | Sigstore for Git signatures |
| **slsa-github-generator** | SLSA L3 provenance in GH Actions | reusable workflow `slsa-framework/slsa-github-generator` | Generates non-forgeable provenance |
| **GitHub artifact attestations** | Native build provenance | `actions/attest-build-provenance` | Built-in SLSA-style provenance + Sigstore; verify with `gh attestation verify` |
| **in-toto** | Attestation framework/spec | `in-toto-run`, layouts | The underlying standard |
| **policy-controller / Kyverno / OPA Gatekeeper** | Admission control verifying signatures | (K8s admission webhooks) | Block unsigned/unverified images at deploy |

### 4.4 CI/CD hardening controls (GitHub Actions examples; concepts generalize)

| Control | How (GitHub Actions) | Default vs hardened |
|---|---|---|
| **Pin actions by SHA** | `uses: actions/checkout@8ade135...` (full 40-char commit SHA) | Default `@v4` is a *movable tag* (a compromised tag re-points); SHA is immutable |
| **Least-privilege token** | `permissions: { contents: read }` at workflow top | Default historically `read/write all`; set repo default to **read** and elevate per-job |
| **OIDC for cloud auth** | `permissions: { id-token: write }` + cloud trust policy | Replaces long-lived cloud keys in secrets with short-lived federated creds |
| **Ephemeral self-hosted runners** | `ephemeral` config / Actions Runner Controller (ARC) on K8s | Persistent runners leak state across jobs |
| **Pin reusable workflows** | `uses: org/repo/.github/workflows/x.yml@<sha>` | Same movable-tag risk |
| **`pull_request_target` caution** | Avoid running untrusted PR code with secrets | `pull_request_target` runs in the base repo context **with secrets** — classic exfil vector |
| **Concurrency / environment protection** | `environment:` with required reviewers for prod deploys | Manual approval gate |
| **Restrict marketplace actions** | Org policy: allow only verified/owned actions | Default allows any public action |
| **Harden-runner (StepSecurity)** | `step-security/harden-runner` action | eBPF egress monitoring/blocking + file integrity on the runner |

### 4.5 Maven/Gradle integrity configuration

| Config | Purpose | Example |
|---|---|---|
| `<checksumPolicy>fail</checksumPolicy>` | Reject artifacts with bad/missing checksums | In `settings.xml` repository config |
| `pgpverify-maven-plugin` | Verify PGP signatures of all dependencies | `mvn pgpverify:check` with a keys allowlist |
| Gradle dependency verification | Built-in checksum **and** signature verification | `gradle/verification-metadata.xml`; `./gradlew --write-verification-metadata sha256,pgp` |
| Maven `mvnw` (wrapper) + `.mvn/wrapper/maven-wrapper.properties` with **distribution checksum** | Pin & verify Maven itself | `distributionSha256Sum=...` |
| `<repositories>` to a single curated proxy (Nexus/Artifactory) | Avoid pulling from arbitrary repos; central allowlist | Internal repo with upstream proxy + quarantine |

> **Gradle dependency verification** is the JVM's strongest built-in integrity control: `verification-metadata.xml` records SHA-256 (and optionally PGP) for *every* artifact (jars **and** poms), and Gradle fails the build if anything doesn't match — a true lockfile-with-hashes.

---

## 5. Code examples by use case

Several distinct, real scenarios. Idiomatic and copy-adaptable.

### 5.1 Generate a CycloneDX SBOM for a Maven project, every build

```xml
<!-- pom.xml — attach SBOM generation to the package phase -->
<build>
  <plugins>
    <plugin>
      <groupId>org.cyclonedx</groupId>
      <artifactId>cyclonedx-maven-plugin</artifactId>
      <version>2.8.0</version> <!-- pin exact version -->
      <executions>
        <execution>
          <phase>package</phase>          <!-- run during 'mvn package' -->
          <goals><goal>makeAggregateBom</goal></goals>  <!-- whole multi-module tree -->
        </execution>
      </executions>
      <configuration>
        <outputFormat>json</outputFormat>          <!-- json is easiest to consume -->
        <schemaVersion>1.5</schemaVersion>          <!-- CycloneDX spec version -->
        <includeBomSerialNumber>true</includeBomSerialNumber> <!-- unique per build -->
        <outputName>bom</outputName>                <!-- -> target/bom.json -->
      </configuration>
    </plugin>
  </plugins>
</build>
```

```bash
mvn -B package          # produces target/bom.json
# Sanity-check what's inside:
jq '.components | length' target/bom.json   # count of components
jq -r '.components[] | "\(.purl)"' target/bom.json | head   # list PURLs
```

The SBOM now travels with your build. Store it as a CI artifact and (next example) attach it to your image as a signed attestation.

### 5.2 Scan a container image with Trivy and gate CI

```bash
# Scan by DIGEST (immutable), fail CI on HIGH/CRITICAL that have a fix available.
IMAGE="ghcr.io/myorg/myapp@sha256:e3b0c442...".  # pin by digest

trivy image \
  --severity HIGH,CRITICAL \      # only fail on serious findings
  --ignore-unfixed \              # don't fail on vulns with no patch yet (tune per policy)
  --exit-code 1 \                 # non-zero => CI fails (default is 0!)
  --format sarif \                # upload to GitHub code scanning
  --output trivy.sarif \
  "$IMAGE"
```

```yaml
# .github/workflows/scan.yml (excerpt) — pin the action by SHA
- name: Trivy scan
  uses: aquasecurity/trivy-action@18f2510ee396bbf400402947b394f2dd8c87dbb0 # v0.29.0
  with:
    image-ref: ghcr.io/myorg/myapp@${{ steps.build.outputs.digest }}
    severity: HIGH,CRITICAL
    exit-code: '1'
    ignore-unfixed: true
    format: sarif
    output: trivy.sarif
- name: Upload to code scanning
  uses: github/codeql-action/upload-sarif@<sha>
  with: { sarif_file: trivy.sarif }
```

### 5.3 Keyless-sign an image and attach a signed SBOM attestation in GitHub Actions

```yaml
# .github/workflows/release.yml
permissions:
  contents: read
  packages: write        # push image to GHCR
  id-token: write        # REQUIRED for Sigstore keyless OIDC

jobs:
  build-sign:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@8ade135a41bc03ea155e62e844d188df1ea18608 # v4.1.0 pinned by SHA

      - uses: docker/login-action@343f7c4344506bcbf9b4de18042ae17996df046d # v3.0.0
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - id: build
        uses: docker/build-push-action@4a13e500e55cf31b7a5d59a38ab2040ab0f42f56 # v5
        with:
          push: true
          tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
          # 'digest' output is the immutable sha256 of the pushed image

      - uses: sigstore/cosign-installer@59acb6260d9c0ba8f4a2f9d9b48431a222b68e20 # v3.5.0

      # Keyless sign by DIGEST. No private key anywhere — uses GH OIDC identity.
      - name: Sign image
        env: { COSIGN_EXPERIMENTAL: "1" }
        run: |
          cosign sign --yes \
            ghcr.io/${{ github.repository }}@${{ steps.build.outputs.digest }}

      # Generate + attach a signed CycloneDX SBOM attestation
      - run: |
          cosign attest --yes \
            --predicate target/bom.json \
            --type cyclonedx \
            ghcr.io/${{ github.repository }}@${{ steps.build.outputs.digest }}
```

**Consumer-side verification** (must pin identity + issuer, or it proves nothing):

```bash
cosign verify \
  --certificate-identity-regexp '^https://github.com/myorg/myapp/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  ghcr.io/myorg/myapp@sha256:e3b0c442...
```

### 5.4 Emit SLSA build provenance natively (GitHub artifact attestations)

```yaml
permissions:
  id-token: write
  contents: read
  attestations: write     # required to record the attestation

steps:
  - id: build
    run: |   # build your jar / image, capture its digest
      ./mvnw -B package
      echo "digest=sha256:$(sha256sum target/app.jar | cut -d' ' -f1)" >> "$GITHUB_OUTPUT"

  - name: Attest provenance
    uses: actions/attest-build-provenance@<sha>
    with:
      subject-path: target/app.jar
      # Produces & signs SLSA provenance via Sigstore, recorded in Rekor.
```

```bash
# Verify the provenance later:
gh attestation verify target/app.jar --owner myorg
```

### 5.5 Enable Gradle dependency verification (checksums + signatures)

```bash
# Generate verification-metadata.xml with SHA-256 and PGP entries for ALL artifacts:
./gradlew --write-verification-metadata sha256,pgp build

# Commit gradle/verification-metadata.xml. Now every future build verifies each
# jar/pom against the recorded hash/signature and FAILS on any mismatch.
```

```xml
<!-- gradle/verification-metadata.xml (excerpt, auto-generated then committed) -->
<verification-metadata>
  <configuration>
    <verify-metadata>true</verify-metadata>
    <verify-signatures>true</verify-signatures>
  </configuration>
  <components>
    <component group="com.google.guava" name="guava" version="33.0.0-jre">
      <artifact name="guava-33.0.0-jre.jar">
        <sha256 value="a1b2c3..."/>   <!-- pinned content hash -->
      </artifact>
    </component>
  </components>
</verification-metadata>
```

### 5.6 Dependabot config for Maven + GitHub Actions, with grouping

```yaml
# .github/dependabot.yml
version: 2
updates:
  - package-ecosystem: "maven"
    directory: "/"
    schedule: { interval: "weekly" }
    open-pull-requests-limit: 10
    groups:                              # batch related bumps into one PR
      spring:
        patterns: ["org.springframework*"]
  - package-ecosystem: "github-actions"  # keep your pinned actions updated
    directory: "/"
    schedule: { interval: "weekly" }
```

Note: even with SHA-pinned actions, Dependabot will propose SHA bumps with the new version in a comment — best of both worlds (immutability + maintenance).

### 5.7 Secrets scanning pre-commit + CI with gitleaks

```yaml
# .pre-commit-config.yaml — block secrets before they're committed locally
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.4
    hooks:
      - id: gitleaks
```

```yaml
# CI backstop: scan full history on PRs
- uses: gitleaks/gitleaks-action@<sha>
  env: { GITLEAKS_LICENSE: ${{ secrets.GITLEAKS_LICENSE }} }
```

If a secret is found: **revoke/rotate first**, then scrub. Do not just delete the commit.

### 5.8 Admission control: only run signed images in Kubernetes (Kyverno)

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata: { name: require-signed-images }
spec:
  validationFailureAction: Enforce      # block, don't just warn
  rules:
    - name: verify-ghcr-signature
      match: { any: [{ resources: { kinds: ["Pod"] } }] }
      verifyImages:
        - imageReferences: ["ghcr.io/myorg/*"]
          attestors:
            - entries:
                - keyless:
                    issuer: "https://token.actions.githubusercontent.com"
                    subject: "https://github.com/myorg/*/.github/workflows/release.yml@*"
                    rekor: { url: "https://rekor.sigstore.dev" }
```

Now the cluster refuses to schedule any `ghcr.io/myorg/*` image that wasn't keyless-signed by your release workflow identity — closing threat H (deploy-time swap).

### 5.9 Pin a Docker base image by digest and run distroless

```dockerfile
# BAD:   FROM eclipse-temurin:21-jre   (mutable tag — can change under you)
# GOOD:  pin by digest (immutable)
FROM eclipse-temurin:21-jre@sha256:abc123... AS build
# ... build steps ...

# Minimal runtime: distroless has no shell/package manager -> tiny attack surface
FROM gcr.io/distroless/java21-debian12@sha256:def456...
COPY --from=build /app/target/app.jar /app/app.jar
USER nonroot:nonroot                 # never run as root
ENTRYPOINT ["/app/app.jar"]
```

> **Beginner note — "distroless":** a base image containing only your app and its runtime dependencies — no shell, no `apt`, no package manager. Fewer packages means fewer CVEs and far less for an attacker to do after a breakout (no `sh` to spawn). Tradeoff: harder to debug (no shell); use `:debug` variants or ephemeral debug containers.

---

## 6. Implementation concerns & best practices

### Performance & cost
- **Scanning latency.** Container scans add seconds to minutes per build. Cache the vuln DB (`TRIVY_CACHE_DIR`), scan layers incrementally, and scan the *base image* separately/periodically so app builds only scan the new layers.
- **Vuln-DB freshness vs build hermeticity.** Auto-updating DBs mean a build can start failing with no code change (a new CVE landed). For hermetic/reproducible CI, **pin the DB version** and update it on a deliberate cadence, accepting a freshness lag.
- **SBOM storage cost.** SBOMs per build × retention can be large. Store in an artifact store / OWASP Dependency-Track rather than inline forever; dedupe by digest.
- **OIDC keyless saves cost and risk** — no KMS key to pay for or rotate.

### Correctness & noise management
- **False positives are the #1 reason programs fail.** Unmanaged scanners produce hundreds of alerts; teams learn to ignore them, and a real one slips through. Counter with: reachability analysis, `--ignore-unfixed`, **VEX** statements (auditable suppression), and severity gating tied to *exploitability + reachability*, not raw CVSS.
- **Shaded/fat jars defeat identification.** When you bundle/relocate dependencies into an uber-jar (Maven Shade), scanners may not recognize the embedded libraries → false negatives. Generate the SBOM from the *resolved dependency tree* before shading, and prefer scanners that read jar metadata.
- **Distro backports.** Trust distro-aware data: a CVE may be patched in a backport while the version string still looks old. Distro-aware scanners (Trivy, Grype) handle this; generic NVD-CPE matching does not.

### Security (hardening the controls themselves)
- **Pin everything immutable:** dependencies (lockfile+hashes), base images (`@sha256`), CI actions/reusable workflows (`@<full-sha>`), the build tool (wrapper checksum).
- **Least privilege by default:** repo default `GITHUB_TOKEN` to read; elevate per job; use OIDC federation instead of long-lived cloud keys; scope service accounts narrowly.
- **Ephemeral, isolated runners.** Fresh per job. For self-hosted, use Actions Runner Controller (ARC) with ephemeral pods. Never run untrusted PR code on a runner that holds production secrets (`pull_request_target` is dangerous).
- **Restrict egress** with harden-runner / network policy so a compromised step cannot exfiltrate secrets or pull a payload.
- **Verify, don't just sign.** A signature nobody verifies is theater. Enforce verification at admission (Kyverno/policy-controller) and at consumption.
- **Always pin identity + issuer** in `cosign verify` — verifying "it's signed" without checking *who* signed it is worthless.
- **Sign by digest, never tag.**

### Observability
- **Centralize SBOMs** in OWASP Dependency-Track for fleet-wide "who is affected by CVE-X?" queries — the single most valuable capability when the next Log4Shell lands.
- **Emit SARIF** to GitHub/GitLab code-scanning so findings live next to code with dedup and trend lines.
- **Audit Rekor** — the public transparency log lets you (and others) detect anomalous signing events.
- **Track time-to-remediate** as a KPI, not just count of vulns.

### Testing
- **Test your policies negatively.** Push an *unsigned* image and confirm admission control rejects it. Introduce a *known-vulnerable* dependency and confirm CI fails. A control you've never seen *block* something is unverified.
- **Reproducible-build checks** as a test: rebuild the same commit and diff the output.

### Production hardening checklist (condensed; full checklist in §7.6)
- Branch protection + required reviews + signed commits; 2FA org-wide.
- Single curated artifact proxy (Nexus/Artifactory) with upstream quarantine; no direct internet pulls.
- Lockfiles with hashes enforced; build fails on drift.
- SCA + secrets + container + IaC scanning gated in CI.
- SBOM generated, signed, stored; provenance (SLSA) attested.
- Images signed (cosign); admission control verifies signature + provenance + identity.
- Ephemeral least-privilege runners, OIDC cloud auth, egress restricted.

### Anti-patterns to avoid
| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Pinning by tag (`:latest`, `@v4`) | Mutable; re-pointable by attacker | Pin by digest/SHA |
| Long-lived signing keys / cloud keys in CI secrets | Fat theft target | Keyless Sigstore / OIDC federation |
| Signing without verifying | Security theater | Enforce verification at admission |
| `cosign verify` without `--certificate-identity` | Accepts *any* signer | Always pin identity + issuer |
| Blanket `.trivyignore` | Hides real risk silently | Use VEX with justification |
| Persistent shared runners | Cross-job contamination & persistence | Ephemeral runners |
| Treating SCA as "scan once" | Tree changes; new CVEs land daily | Continuous monitoring (Dependabot + Dependency-Track) |
| Deleting a leaked secret commit instead of rotating | Secret already scraped & in history | Rotate/revoke *first* |
| `pull_request_target` running untrusted code with secrets | Classic exfiltration vector | Use `pull_request` + no secrets, or strict gating |

---

## 7. Advanced topics & deep internals

### 7.1 Reproducible builds
A **reproducible build** produces **bit-for-bit identical** output from the same source, independent of *when*, *where*, or *who* builds it. This requires eliminating nondeterminism: embedded timestamps (`SOURCE_DATE_EPOCH`), build paths, locale, file ordering, and non-deterministic compilers. Maven has a **reproducible-builds** profile (`project.build.outputTimestamp`) and the `artifact:check-buildplan` / reproducible-build-maven-plugin to verify. Value: a *third party* can independently rebuild and confirm your published artifact matches — the strongest defense against build tampering (SolarWinds class), and the basis for **rebuilder networks**.

### 7.2 Dependency confusion / substitution attacks
**Dependency confusion** (Alex Birsan, 2021) exploits resolvers that check *public* registries alongside private ones. If your internal package `com.acme:internal-lib` isn't on Maven Central, an attacker publishes a *public* package with the same name and a *higher version*; a misconfigured resolver, applying "highest-wins" across both repos, pulls the attacker's. Defenses: (a) scope/namespace internal packages and *reserve* the namespace publicly; (b) configure resolvers so internal repos are authoritative and never fall through to public for internal coordinates; (c) explicit repository routing; (d) lockfiles with hashes.

### 7.3 Typosquatting & install-time execution
Attackers publish packages with names близкие to popular ones (`reqeusts`, `jakson`) hoping for typos. In ecosystems with **install-time scripts** (npm `postinstall`, Python `setup.py`) the payload runs the moment you install. JVM is somewhat safer (no install scripts), but Maven *plugins* and annotation processors run arbitrary code *at build time*. Defenses: curated proxy with allowlists, pinned dependencies, and minimizing plugins from untrusted sources.

### 7.4 The "trusting trust" problem
Ken Thompson's 1984 "Reflections on Trusting Trust": a compromised *compiler* can inject a backdoor into everything it builds — including a clean recompilation of itself — leaving no trace in source. The modern echo is build-system and toolchain compromise (SolarWinds, xz's build macros). Mitigations: **diverse double-compilation** (build the compiler with two independent compilers and compare), reproducible builds, and provenance attesting the *exact toolchain* used (SLSA `resolvedDependencies`).

### 7.5 Lesser-known cosign/Sigstore behavior
- **Tag-based vs OCI referrers storage.** cosign historically stored signatures under a derived tag (`sha256-<digest>.sig`); newer flows use the OCI **referrers API** so signatures attach as related artifacts. Some registries don't support referrers → fallback behavior matters.
- **The `--bundle` and offline verification.** You can bundle the signature + cert + Rekor proof into a file for **air-gapped verification** without contacting Rekor at verify time.
- **Private Sigstore.** Self-host Fulcio/Rekor (e.g. via the `sigstore-helm` or `policy-controller`) for keyless benefits without depending on the public good instance's availability/rate limits.
- **Timestamp authorities (TSA).** Beyond Rekor's signed timestamp, cosign supports RFC 3161 TSAs for environments that require an external trusted clock.

### 7.6 The hardening checklist (the deliverable many teams want)

**Source / SCM**
- [ ] 2FA enforced org-wide; SSO; least-privilege org roles.
- [ ] Branch protection: required reviews (≥1, ≥2 for sensitive repos), required status checks, no force-push to protected branches.
- [ ] Commit signing required (Gitsign/GPG); verify on merge.
- [ ] CODEOWNERS for sensitive paths (CI configs, build scripts).

**Dependencies**
- [ ] Single curated artifact proxy; no direct public pulls; upstream quarantine.
- [ ] Lockfiles with hashes; build fails on drift (Gradle locking / Maven verification).
- [ ] Dependency verification (checksums + PGP where available).
- [ ] SCA gated in CI; Dependabot/Renovate auto-PRs; continuous monitoring (Dependency-Track).
- [ ] Internal namespaces reserved publicly (anti-dependency-confusion).

**Build / CI**
- [ ] Actions & reusable workflows pinned by full commit SHA.
- [ ] `GITHUB_TOKEN`/job permissions least-privilege (read by default).
- [ ] OIDC federation for cloud; zero long-lived cloud keys in secrets.
- [ ] Ephemeral, isolated runners; no untrusted PR code with secrets.
- [ ] Egress restricted/monitored (harden-runner).
- [ ] Reproducible builds where feasible.

**Artifacts**
- [ ] SBOM (CycloneDX/SPDX) generated each build, signed, stored centrally.
- [ ] SLSA provenance (≥L2, target L3) attested by the build platform.
- [ ] Images/binaries signed (cosign keyless).
- [ ] Base images pinned by digest; distroless/minimal; non-root.
- [ ] Container + secrets + IaC scanning gated.

**Deploy / runtime**
- [ ] Admission control verifies signature + provenance + identity (Kyverno/policy-controller).
- [ ] Verify pins identity + OIDC issuer (not just "is signed").
- [ ] VEX-driven, auditable suppression — no blanket ignores.
- [ ] Incident runbook for compromised dependency / leaked secret (rotate-first).

---

## 8. Tradeoffs & decision frameworks

### SBOM format choice

| Criterion | CycloneDX | SPDX |
|---|---|---|
| Origin / standard | OWASP | Linux Foundation; **ISO/IEC 5962** |
| Primary strength | Security ops, VEX, vuln data | License compliance, regulatory mandates |
| Native vuln/VEX support | Yes | Limited (separate mechanisms) |
| Use when | Security operations, Dependency-Track | License compliance, contracts naming SPDX |
| Avoid when | Mandate explicitly requires SPDX | Need native VEX/vuln ops |

### Signing model choice

| Criterion | Keyless (Sigstore) | Key-based (KMS/HSM) |
|---|---|---|
| Key management | None (ephemeral) | You manage/rotate keys |
| Best for | CI with OIDC, public/standard infra | Air-gapped, strict compliance, offline |
| External dependency | Fulcio/Rekor (public or private) | KMS/HSM availability |
| Audit | Public transparency log | Your own logging |
| Use when | Modern cloud CI | Regulatory/air-gap, can't reach Sigstore |

### SCA tool choice

| Need | Pick |
|---|---|
| Free, GitHub-native, auto-PRs | Dependabot |
| Flexible, multi-platform updates | Renovate |
| Reachability + commercial support | Snyk |
| Free JVM build-gate | OWASP Dependency-Check + Maven Enforcer |
| Fleet SBOM monitoring | OWASP Dependency-Track |
| Container/FS all-in-one | Trivy |

### SLSA target

- **Use L1** as a floor: just emit provenance — better than nothing, enables future analysis.
- **Target L2** for most teams: hosted-CI-signed provenance; defeats forged-artifact uploads.
- **Aim L3** for high-value/regulated software: isolated, non-forgeable provenance; defeats SolarWinds-class build compromise.
- **Avoid over-investing** in L3 for low-risk internal tooling where the operational cost outweighs the threat.

---

## 9. Failure modes & debugging

| Failure mode | Symptom | Diagnose with | Fix |
|---|---|---|---|
| **Build suddenly fails on a new CVE** | CI red with no code change | Read scanner output; check DB update date | VEX/ignore-unfixed; pin DB; remediate |
| **`cosign verify` fails** | Verification error in admission/CI | `cosign verify ... -d` (debug); check identity/issuer regex; confirm signature exists in registry/Rekor | Fix identity/issuer pin; re-sign by digest; check registry referrers support |
| **Dependency confusion** | Unexpected external package resolved | `mvn dependency:tree` / `gradle dependencies`; check which repo served it | Authoritative internal repo routing; reserve namespace |
| **Drift / non-reproducible build** | Same commit → different bytes | Rebuild + `diffoscope`; check timestamps/paths | Set `SOURCE_DATE_EPOCH`, reproducible profile |
| **Leaked secret in repo** | gitleaks/GitHub alert; or active misuse | gitleaks scan history; provider audit logs | **Rotate/revoke first**, then scrub history (BFG/filter-repo) |
| **Shaded jar hides vuln** | Scanner reports clean but lib is vulnerable | Compare SBOM (pre-shade) vs scan | SBOM from resolved tree; scanner that reads jar metadata |
| **Runner compromise** | Anomalous egress, leaked creds, poisoned cache | harden-runner egress logs; CI audit logs; cloud CloudTrail | Ephemeral runners; rotate all in-scope creds; least privilege |
| **Registry serves tampered image** | Digest mismatch | Compare deployed digest vs signed/attested digest | Admission control by digest + signature |

**Real incidents to know:** SolarWinds SUNBURST (2020, build compromise), xz/liblzma (2024, dependency/maintainer compromise), Codecov bash uploader (2021, compromised CI tool exfiltrating env vars/secrets), `event-stream` npm (2018, maintainer handoff to attacker), Log4Shell `CVE-2021-44228` (2021, the event that made SBOMs/SCA boardroom topics), PyPI/npm typosquat campaigns (ongoing), dependency confusion PoC (Birsan, 2021).

**General debugging workflow:** (1) Identify the exact artifact by **digest**. (2) Pull its **provenance + SBOM** attestations. (3) Verify signature/identity. (4) Cross-reference SBOM against current advisories (Dependency-Track / `trivy sbom`). (5) Reproduce the build hermetically. (6) For secrets/runner incidents: assume compromise of everything in scope and **rotate first, investigate second.**

---

## 10. Interview drill

**Q1. What is software supply-chain security, and why isn't a firewall/WAF enough?**
*Model answer:* It secures everything that goes into building and shipping software — dependencies, build tooling, pipelines, base images, secrets, distribution — so you can cryptographically prove where your running code came from. A firewall protects the runtime perimeter, but supply-chain attacks insert malicious code *before* the build, so you sign and ship the backdoor yourself; perimeter defenses never see it.
- *Probe: Give an example where source review wouldn't catch it.* SolarWinds — source was clean; the backdoor was injected during the build. Or xz — the payload hid in build macros and test blobs, not `.c` files.
- *Probe: What single control most directly addresses build-time injection?* Provenance attestation from a hardened, isolated builder (SLSA L3) + reproducible builds for independent verification.

**Q2. Pin a dependency "by version" vs "by digest" — what's the difference and why does it matter?**
*Model:* A version/tag is a *mutable pointer*; the owner or an attacker can re-point it to different bytes. A digest *is* the content (SHA-256), immutable. Pinning by digest guarantees you get exactly the bytes you vetted.
- *Probe: How do you pin transitive deps in Maven, which has no lockfile?* Force versions via a BOM/`dependencyManagement`, or use a lockfile plugin / Gradle-style verification metadata; enable checksum-fail and PGP verification.
- *Probe: Does pinning protect against a malicious *new* release with the same version?* If pinned by *hash*, yes; by version alone, no.

**Q3. Explain Sigstore keyless signing end to end.**
*Model:* cosign mints an ephemeral key pair, gets an OIDC identity token (e.g. from GitHub Actions), exchanges it at **Fulcio** for a ~10-min cert binding the ephemeral key to that identity, signs the artifact digest, records the signature+cert in the **Rekor** transparency log (which timestamps it so verifiers know it was signed while the cert was valid), then discards the private key. No long-lived key to steal.
- *Probe: Why does the short cert validity not break later verification?* Rekor's signed timestamp/inclusion proof shows the signature existed during the validity window.
- *Probe: What's the most common verification mistake?* Verifying "is signed" without pinning `--certificate-identity` and `--certificate-oidc-issuer`, which accepts *any* signer.

**Q4. What's an SBOM and what concretely can you do with one that you couldn't without it?**
*Model:* A machine-readable inventory of every component (incl. transitive) with versions and hashes. With a fleet of SBOMs you can instantly answer "which of my deployed services contain log4j-core 2.14.1?" when a new CVE drops — retroactive impact analysis impossible from source alone.
- *Probe: CycloneDX vs SPDX?* SPDX = ISO, license-compliance heritage; CycloneDX = OWASP, security/VEX-oriented. Pick by priority/mandate.
- *Probe: What's VEX and why does it matter?* A statement of *exploitability* per vuln ("not affected because function never called"), giving auditable suppression instead of blanket ignores.

**Q5 (senior signal). Your org wants to "verify signatures before deploy." Walk me through the design and the tradeoffs.**
*Model:* Sign images keyless in the release workflow; attach SBOM + SLSA provenance attestations. At the cluster, run an admission controller (Kyverno/policy-controller) that, for each image, verifies the cosign signature, pins the expected workflow identity + OIDC issuer, and optionally verifies provenance predicates (source repo/commit). Tradeoffs: enforce vs audit mode (blast radius if a legit deploy fails verification), dependency on Sigstore availability (mitigate with private Sigstore / bundled offline verification), registry referrers support, and emergency break-glass. Start in audit mode, then enforce.
- *Probe: What breaks if Rekor is down?* Online verification fails; mitigate with bundled offline proofs or private Rekor.
- *Probe: How do you handle third-party images you don't build?* Verify *their* signatures if available, else mirror into your curated registry and re-attest after your own scan, treating them as inputs with recorded provenance.

**Q6 (senior signal). You inherit a pipeline with persistent shared runners, long-lived cloud keys in secrets, and actions pinned by `@v4`. Prioritize the fixes and justify.**
*Model:* (1) Rotate the long-lived cloud keys and move to OIDC federation — they're the highest-value theft target and broadest blast radius. (2) Move to ephemeral runners — kills cross-job persistence/leakage. (3) Pin actions by SHA — closes mutable-tag re-point. Order by *blast radius × likelihood*: stolen cloud admin creds dwarf the others. Layer least-privilege tokens and egress restriction throughout.
- *Probe: Why is `pull_request_target` dangerous here?* It runs PR code in the base-repo context *with secrets* — direct exfiltration path; especially toxic on persistent runners.

**Q7 (senior signal). When is SLSA L3 not worth it?**
*Model:* For low-risk internal tooling or throwaway artifacts where the operational cost (isolated builders, provenance plumbing, verification) exceeds the threat. L1 (just emit provenance) or L2 (hosted-signed) may be the right risk-adjusted stopping point. Security investment should track blast radius and threat, not maximize a level for its own sake.
- *Probe: What attack does L3 stop that L2 doesn't?* Forgery of provenance by the project's *own* build steps — i.e., a compromised build script can't fake the attestation because signing material is isolated from user steps (SolarWinds class).

**Q8. Walk through what happens when a malicious transitive dependency executes code during `mvn package`, and which controls stop the spread.**
*Model:* Code runs as the CI user → harvests env/secret/metadata-endpoint creds → with a broad token, moves laterally (pushes commits, poisons caches/other pipelines) → on persistent runners, persists. Ephemeral runners kill persistence and cross-job leakage; least-privilege/short-lived tokens blunt lateral movement; egress restriction blocks exfiltration; pinned/verified deps reduce the initial likelihood.
- *Probe: Is the JVM safer than npm here?* Somewhat — no install-time scripts — but Maven *plugins* and annotation processors run arbitrary code at build time, so the build phase is still RCE-capable.

**Q9. Why does removing a leaked secret's commit not fix the leak?**
*Model:* The secret persists in clones, forks, the reflog, CI logs, and is scraped by bots within seconds of a public push. The only real fix is to **rotate/revoke** the credential immediately; history scrubbing is secondary cleanup.
- *Probe: How would you prevent it proactively?* Pre-commit gitleaks + GitHub push protection (blocks before the push lands).

**Q10. Compare SCA and SAST; where does each fail?**
*Model:* SCA finds *known* vulnerabilities in components you *didn't* write (dependency tree vs advisory DBs). SAST finds bugs in code you *did* write via static analysis. SCA misses zero-days/undisclosed backdoors and mis-identified bundled libs; SAST misses logic/architecture flaws and produces its own false positives. They're complementary; neither catches the xz-style backdoor pre-disclosure — which is why provenance, reproducibility, and SBOMs (for retroactive detection) matter.
- *Probe: How do SBOMs help after disclosure?* Query stored SBOMs to find all affected artifacts instantly, even for software already shipped.

**Q11. What is dependency confusion and how do you prevent it in a JVM shop?**
*Model:* An attacker publishes a *public* package matching your *private* package name with a higher version; a resolver that checks public + private with highest-wins pulls the malicious one. Prevent by making the internal repo authoritative for internal coordinates (no fall-through to public), reserving your namespace publicly, explicit repo routing, and hash-pinned lockfiles.
- *Probe: Which resolution algorithm makes this worse?* Highest-version-wins across mixed repositories (Gradle default behavior if misconfigured).

**Q12. What's an in-toto attestation and who signs the provenance — and why does that matter?**
*Model:* A signed statement about an artifact: a `subject` (by digest), a `predicateType` (e.g. SLSA provenance), and a `predicate` (the facts), in a signed DSSE envelope. The **build platform** signs provenance, not the developer — so "built by GitHub Actions from commit abc" can't be forged by a human, because it's the platform's OIDC identity that signs.
- *Probe: Why bind to a digest, not a name?* A name/tag is mutable; binding to the content hash makes the attestation tamper-evident and unambiguous.

---

## 11. Glossary

- **Admission controller** — a Kubernetes component that intercepts requests to create/modify resources and can validate/reject them (e.g., refuse unsigned images).
- **Artifact** — a build output: a jar, container image, binary, package.
- **Asymmetric cryptography** — public/private key pair crypto; private signs, public verifies.
- **Attestation** — a signed statement about an artifact (provenance, SBOM, scan result, etc.).
- **Base image** — the foundation layer of a container image you build on top of.
- **BOM (Maven)** — a POM controlling dependency versions centrally (≠ SBOM).
- **Branch protection** — SCM rules preventing unsafe changes to important branches.
- **CA (Certificate Authority)** — trusted issuer of certificates binding identities to keys.
- **Cosign** — Sigstore CLI for signing/verifying artifacts and attestations.
- **CVE** — globally unique ID for a public vulnerability.
- **CVSS** — 0–10 severity score for a vulnerability.
- **CycloneDX** — OWASP SBOM format, security-focused, native VEX/vuln support.
- **Dependency confusion** — attack substituting a public package for an internal one via name collision + higher version.
- **Digest (hash)** — content-addressed fingerprint (SHA-256); immutable identity of bytes.
- **Distroless** — minimal base image with no shell/package manager.
- **DSSE** — Dead Simple Signing Envelope; the signed wrapper for in-toto statements.
- **Ephemeral runner** — single-use CI worker destroyed after one job.
- **Fulcio** — Sigstore CA issuing short-lived certs bound to OIDC identity.
- **Git / SCM / VCS** — version control system and its hosting; origin of the supply chain.
- **Grype** — Anchore vulnerability scanner.
- **HSM/KMS** — hardware/cloud key-management for storing private keys.
- **in-toto** — framework/spec for supply-chain attestations.
- **Keyless signing** — signing with ephemeral keys + OIDC identity + transparency log (Sigstore).
- **Least privilege** — granting only minimum necessary permissions.
- **Lockfile** — file pinning exact resolved dependency versions (ideally with hashes).
- **Merkle tree** — hash tree whose root commits to all contents; basis of transparency logs.
- **NVD / OSV** — vulnerability databases (US NVD; Google's ecosystem-aware OSV).
- **OIDC** — OpenID Connect; identity layer issuing signed identity tokens.
- **Pinning** — specifying an exact immutable input (by digest/SHA).
- **Provenance** — verifiable metadata on how/where/from-what an artifact was built.
- **PURL (Package URL)** — standardized cross-ecosystem package identifier.
- **Rekor** — Sigstore append-only transparency log of signing events.
- **Reproducible build** — bit-identical output from same source, independent of environment.
- **SARIF** — standard format for static-analysis results.
- **SAST** — static analysis of *your* code for bugs.
- **SBOM** — Software Bill of Materials; inventory of all components.
- **SCA** — Software Composition Analysis; finding known issues in dependencies.
- **Sigstore** — keyless signing ecosystem (Fulcio + Rekor + cosign).
- **SLSA** — Supply-chain Levels for Software Artifacts; build-integrity/provenance framework (L0–L3).
- **SPDX** — Linux Foundation SBOM format; ISO/IEC 5962; license-compliance heritage.
- **Symmetric cryptography** — single shared-secret crypto (AES).
- **Transitive dependency** — a dependency pulled in by another dependency.
- **Transparency log** — public append-only auditable ledger.
- **Trivy** — Aqua all-in-one scanner (vuln/secret/misconfig/SBOM).
- **Typosquatting** — malicious packages named to mimic popular ones.
- **VEX** — Vulnerability Exploitability eXchange; per-vuln affected/not-affected statements.
- **WAF** — Web Application Firewall; runtime perimeter defense (not a supply-chain control).

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Threat model (source→consumer):** unauthorized source change · SCM compromise · build-from-wrong-source · **build compromise (SolarWinds)** · **bad dependency (xz)** · forged artifact upload · repo compromise · deploy-time swap.

**Core primitives:** SHA-256 digest = immutable content identity; digital signature = authenticity + integrity.

**Pin everything immutable:** deps (lockfile + hashes), base image `@sha256`, CI actions `@<full-sha>`, build tool (wrapper checksum). Tags are mutable — never trust them.

**SBOM:** inventory (incl. transitive) → instant "who's affected by CVE-X?". CycloneDX (security/VEX) vs SPDX (license/ISO).

**Sign keyless (cosign):** ephemeral key → OIDC token → Fulcio short-lived cert (~10 min, identity-bound) → sign digest → record in Rekor (timestamped) → discard key. **Verify** = pin `--certificate-identity` + `--certificate-oidc-issuer`, by digest.

**Provenance/SLSA:** in-toto attestation (subject by digest, predicateType, predicate), signed by the *builder*. L1 exists · L2 hosted-signed · L3 isolated/non-forgeable.

**Harden CI:** ephemeral runners · least-privilege tokens (read default) · OIDC cloud auth (no long-lived keys) · pinned actions · restricted egress · no untrusted-PR-code-with-secrets.

**Scan:** SCA (Dependabot/Snyk/Trivy/Dependency-Check) · containers (Trivy/Grype, `--exit-code 1`) · secrets (gitleaks/push-protection — rotate first!) · manage noise with VEX + reachability.

**Enforce at deploy:** admission control (Kyverno/policy-controller) verifies signature + provenance + identity.

**Key reflexes:** sign by digest · verify the *who* not just "is signed" · rotate leaked secrets before scrubbing · centralize SBOMs (Dependency-Track) · test policies by trying to bypass them.

### Self-test (no answers — recall)

1. Explain why a valid signature on the SolarWinds Orion DLL provided no protection, and which control would have caught it.
2. Walk through every step of cosign keyless signing *and* verification, including why a 10-minute certificate doesn't break later verification.
3. Your Maven build has no lockfile. List three concrete ways to achieve hash-level pinning of transitive dependencies and the tradeoffs of each.
4. Design admission-time image verification for Kubernetes; name the failure modes and how you'd run it safely in production (audit vs enforce, Sigstore availability, break-glass).
5. A secret was committed and pushed to a public repo 30 seconds ago. Order your response steps and justify why deleting the commit is not step one.
6. Compare CycloneDX vs SPDX and state, with reasons, which you'd standardize on for a security-operations-driven org vs a license-compliance-driven one.
7. Describe dependency confusion end to end and the exact resolver misconfiguration that enables it; give two defenses.

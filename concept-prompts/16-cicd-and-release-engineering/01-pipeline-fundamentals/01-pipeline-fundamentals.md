# CI/CD & Release Engineering — Pipeline Fundamentals

> A definitive handbook chapter for senior JVM/backend engineers. From first principles to deep internals: design it, operate it, debug it, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### What a pipeline is

A **CI/CD pipeline** is an automated, ordered sequence of steps that takes a change to source code from a developer's commit all the way to running software in front of users (or to a published artifact ready to be deployed). It is the assembly line of software delivery: code goes in one end, validated and packaged software comes out the other end, and every step in between is automated, reproducible, and observable.

- **CI** stands for **Continuous Integration**: the practice of merging every developer's work into a shared mainline branch *frequently* (ideally many times a day) and *verifying each merge automatically* with a build and a test suite. The point of CI is to catch integration problems within minutes of them being introduced, while the change is still small and the author still has it in their head.
- **CD** is overloaded — it means **Continuous Delivery** *or* **Continuous Deployment**, and the distinction matters (Section 2). Roughly: Delivery means "every validated build is *always ready* to ship to production with one button press"; Deployment means "every validated build *automatically* ships to production with no button press."

### The problem it solves

Before pipelines, integration was a discrete, painful, occasional event. Teams worked on branches for weeks, then attempted a "big bang" merge ("merge hell" / "integration hell"), discovering only at the end that everyone's assumptions conflicted. Releases were manual, error-prone rituals performed by a few people late at night, documented in a runbook that was always slightly out of date. The pipeline replaces all of that with:

1. **Fast feedback** — you learn within minutes whether your change builds and passes tests, instead of days or weeks later.
2. **Repeatability** — the same steps run the same way every time, removing "works on my machine" and human error.
3. **Auditability** — every artifact has a traceable lineage: which commit, which dependencies, which tests passed, who approved it.
4. **Decoupling of deploy from release** — you can ship code continuously while controlling *when* features become visible (feature flags), separating the engineering act of deploying from the business act of releasing.

### When you reach for it

You reach for a pipeline essentially always for any non-trivial software project — even a solo project benefits from automated build+test on push. The *sophistication* scales with the stakes: a hobby library might need only "build + test + publish to a registry"; a payments platform needs hermetic builds, multi-environment promotion, canary deploys, automated rollback, and provenance attestation.

### One-paragraph mental model

> Think of a pipeline as a **conveyor belt with quality gates**. A commit is a part placed on the belt. Each station performs one operation (compile, test, scan, package) and either passes the part forward or stops the belt (fails the build). The single most important invariant is **build once, deploy many**: you create *one* immutable artifact early, give it an identity (a content hash or version), and that *exact same bytes* flow through every later environment — dev, staging, production. You never rebuild per environment, because rebuilding reintroduces the risk that "the thing you tested" is not "the thing you shipped." Everything else — caching, parallelism, promotion gates, pipeline-as-code — is engineering in service of making that belt **fast**, **deterministic**, and **trustworthy**.

---

## 2. Foundations from first principles

Let's build the vocabulary from zero. Each term is defined the first time it appears.

### 2.1 Version control as the substrate

A pipeline is triggered by and operates on a **Version Control System (VCS)** — software that records every change to source code over time, lets multiple people work in parallel, and lets you reconstruct any past state. The dominant VCS today is **Git**, a *distributed* VCS (every clone has the full history, not just a checkout from a central server).

Key Git terms used throughout:

- **Commit** — an immutable snapshot of the entire tree of files at a point in time, identified by a **SHA-1/SHA-256 hash** (a fixed-length fingerprint computed from the content; identical content always produces the identical hash). The commit hash is the canonical *identity* of "a version of the code."
- **Branch** — a movable pointer to a commit; a line of development. The shared line everyone integrates into is the **mainline** (commonly `main`, historically `master`, sometimes `trunk`).
- **Pull Request (PR) / Merge Request (MR)** — a proposal to merge one branch into another, with a place to review, discuss, and run automated checks before merging. (GitHub calls it a PR; GitLab calls it an MR — same idea.)
- **Tag** — a fixed, named pointer to a specific commit, conventionally used to mark releases (e.g. `v2.4.1`).

The pipeline almost always runs on two occasions: (1) when a PR is opened/updated (to gate the merge), and (2) when a commit lands on the mainline (to produce a deployable artifact).

### 2.2 What "build" means on the JVM

A **build** transforms source code into a runnable/distributable form. On the JVM:

- **Compilation** — `javac` turns `.java` source into `.class` files containing **bytecode** (a portable, platform-independent instruction set executed by the **JVM**, the Java Virtual Machine — the runtime that interprets/JIT-compiles bytecode to native machine code).
- **Packaging** — `.class` files plus resources are zipped into a **JAR** (Java ARchive). A web/service app is often packaged as an executable "fat JAR" / "uber JAR" (application classes *and* all dependency classes in one JAR), or as a **WAR** (Web ARchive) for deployment into a servlet container.
- **Dependency resolution** — your code depends on third-party libraries. A **build tool** reads a declaration of dependencies and fetches them (with their transitive dependencies — the dependencies of your dependencies) from a **repository** (a server hosting library artifacts, e.g. Maven Central).

The two dominant JVM build tools:

- **Maven** — declarative, XML-based (`pom.xml`), convention-over-configuration, lifecycle-driven (`validate → compile → test → package → verify → install → deploy`).
- **Gradle** — Groovy/Kotlin DSL (`build.gradle` / `build.gradle.kts`), based on a **task graph** (a DAG — directed acyclic graph — of tasks where edges are dependencies), supports incremental builds and a build cache natively.

### 2.3 Tests, layered

Tests are the quality gates. The classic mental model is the **test pyramid**: many cheap fast tests at the bottom, fewer expensive slow tests at the top.

- **Unit test** — exercises a single class/method in isolation, with collaborators replaced by **mocks/stubs** (fake objects that return canned responses). Milliseconds each. Tools: JUnit 5, TestNG, Mockito, AssertJ.
- **Integration test** — exercises several components together, often with a real database or message broker (frequently spun up in a throwaway container via **Testcontainers**). Seconds each.
- **End-to-end (E2E) / system test** — drives the whole deployed system as a black box, often through its public API or UI. Slow, brittle; use sparingly.
- **Smoke test** — a tiny, fast check run *after deployment* to confirm the system is alive and serving (e.g. a health-check endpoint returns 200, a canary transaction succeeds). Named after hardware: "power it on and see if it smokes."

### 2.4 Artifacts and registries

An **artifact** is the build's durable output — the thing you deploy. Examples: a JAR, a **container image** (a packaged filesystem + metadata that a container runtime runs as an isolated process), a `.deb`/`.rpm` OS package, a Helm chart. Artifacts live in an **artifact registry / repository**:

- **Maven/Gradle artifacts** → Sonatype Nexus, JFrog Artifactory, GitHub Packages, Maven Central.
- **Container images** → Docker Hub, Amazon ECR, Google Artifact Registry, GitHub Container Registry (GHCR).

The defining property of a good artifact is **immutability**: once published under an identity, its bytes never change. We dig into why in Sections 3 and 7.

### 2.5 Environments and promotion

An **environment** is a complete running instance of the system with its own infrastructure and configuration. A typical ladder:

- **dev** — developers' shared sandbox; unstable, frequent deploys.
- **staging / pre-prod / UAT** (User Acceptance Testing) — a production-like environment used for final validation.
- **production (prod)** — the real thing, serving real users.

**Promotion** is the act of moving *the same artifact* up this ladder, gated by checks at each rung. This is the operational expression of "build once, deploy many."

### 2.6 CI vs CD vs CD — the precise distinction

This is the single most-asked definitional question, so be exact:

| Term | What it means | Where automation stops |
|---|---|---|
| **Continuous Integration (CI)** | Merge to mainline frequently; every merge is auto-built and auto-tested. | At a verified, mergeable change (and often a published artifact). |
| **Continuous Delivery (CD)** | Every change that passes CI is *automatically* taken to a state where it is *deployable to production at any time* with a single manual approval/click. | At a release-ready artifact in staging; **a human approves the prod deploy.** |
| **Continuous Deployment (CD)** | Like Continuous Delivery, but the prod deploy is *also* automatic — no human gate. Every green build that reaches the end goes live. | Nowhere — it goes all the way to prod. |

Mnemonic: **Delivery = "always *ready* to deploy" (button exists). Deployment = "always *being* deployed" (no button).** Continuous Deployment requires very high confidence (excellent test coverage, progressive delivery, automated rollback) and is not appropriate for every domain.

### 2.7 The core invariant: "Build once, deploy many"

Build the artifact **exactly once**, give it an immutable identity, and promote *that artifact* through every environment. The alternatives — rebuilding per environment, or building inside the prod deploy — are anti-patterns because:

- A rebuild can pull a different dependency version (if any dependency isn't pinned), produce different timestamps, or run on a different toolchain — so "tested in staging" ≠ "running in prod."
- It wastes time and money rebuilding identical bytes.
- It destroys traceability: you can no longer say "this exact artifact passed these exact tests."

Per-environment *configuration* is injected at deploy/run time (env vars, config maps, secrets), **not** baked into the artifact. The artifact is the constant; config is the variable.

---

## 3. How it works internally

This section is the heart of the chapter. We trace the canonical pipeline stage by stage, then examine the execution machinery (runners, executors, workspaces) and the data/control flow.

### 3.1 The canonical pipeline stages, in order

```
   git push / PR
        │
        ▼
 ┌──────────────┐   ┌──────────┐   ┌────────────┐   ┌──────────────────────┐
 │  1. Checkout │ → │ 2. Build │ → │ 3. Unit    │ → │ 4. Static analysis / │
 │              │   │          │   │    test    │   │    security scan     │
 └──────────────┘   └──────────┘   └────────────┘   └──────────────────────┘
        │
        ▼
 ┌──────────────────┐   ┌────────────────────┐   ┌────────────┐
 │ 5. Package        │ → │ 6. Integration     │ → │ 7. Deploy  │
 │    artifact       │   │    test             │   │  (to env)  │
 │  (immutable id)   │   │                     │   │            │
 └──────────────────┘   └────────────────────┘   └────────────┘
        │
        ▼
 ┌─────────────┐   ┌──────────────────────────────────┐
 │ 8. Smoke    │ → │ 9. Promote (next env, repeat 7–8)│
 │    test     │   │     build once, deploy many      │
 └─────────────┘   └──────────────────────────────────┘
```

Walk through each stage — what it does, why it's there, what it consumes/produces, and how it can fail.

#### Stage 1 — Checkout

- **What:** The runner clones (or fetches) the repository at the exact commit that triggered the pipeline. The commit SHA is the seed of everything downstream and should be recorded.
- **Internals:** Most systems do a **shallow clone** (`git clone --depth=1`) to save time, fetching only the latest commit's tree rather than full history. If your build needs history (e.g. computing a version from the number of commits, or `git describe` against tags), shallow clones break it — you must increase fetch depth.
- **Failure modes:** wrong ref checked out; submodules not initialized; LFS (Large File Storage — a Git extension for big binaries) objects missing; auth failure to a private repo.

#### Stage 2 — Build (compile + resolve dependencies)

- **What:** Run the build tool to resolve dependencies and compile sources to bytecode. E.g. `mvn -o compile` or `gradle assemble`.
- **Internals:** The build tool reads `pom.xml`/`build.gradle`, computes the dependency graph, downloads any missing artifacts into a local cache (`~/.m2/repository` for Maven, `~/.gradle/caches` for Gradle), then invokes `javac`. With incremental compilation, only changed sources and their dependents are recompiled.
- **Why early:** It's the cheapest broad failure detector — a syntax error or a missing dependency should fail in seconds, before you spend minutes on tests.
- **Failure modes:** compilation error; **dependency resolution failure** (a transitively required version is missing/yanked); network flakiness to the dependency repo; version conflicts (two libraries demand incompatible versions of a third — "dependency hell").

#### Stage 3 — Unit test

- **What:** Run the fast, isolated tests. `mvn test` / `gradle test`.
- **Internals:** The test runner (Surefire for Maven, the Gradle test task) forks one or more JVMs, discovers test classes, runs them (often in parallel across forks), and emits results as JUnit XML (a standard schema CI systems parse to show pass/fail counts and trends).
- **Gates:** Typically also enforces **code coverage** thresholds via JaCoCo (Java Code Coverage — instruments bytecode to measure which lines/branches executed during tests). E.g. "fail if line coverage < 80%."
- **Failure modes:** assertion failures; **flaky tests** (tests that pass/fail nondeterministically due to timing, ordering, shared state, or randomness) — these are corrosive because they erode trust in the pipeline.

#### Stage 4 — Static analysis / security scan

This stage runs *without executing the program* (mostly), inspecting source, bytecode, dependencies, and config.

- **Linting / style** — Checkstyle, Spotless, ktlint enforce formatting and conventions.
- **SAST (Static Application Security Testing)** — SpotBugs/FindSecBugs, SonarQube, Semgrep, Snyk Code scan code for bug patterns and vulnerabilities (SQL injection, hardcoded secrets, etc.).
- **SCA (Software Composition Analysis)** — OWASP Dependency-Check, Snyk, Dependabot scan your *dependencies* against vulnerability databases (the **CVE** list — Common Vulnerabilities and Exposures, the public catalog of known security flaws, each with a CVE-ID). This is critical: most of your shipped code is third-party.
- **Secret scanning** — gitleaks, truffleHog detect accidentally committed credentials.
- **License compliance** — flags GPL/AGPL or otherwise incompatible licenses in your dependency tree.
- **SBOM generation** — produce a **Software Bill of Materials** (a machine-readable inventory of every component and version in the artifact; formats: CycloneDX, SPDX) for supply-chain transparency.

- **Failure modes:** new critical CVE in a dependency; secret leak; quality gate regression (e.g. SonarQube "new code" coverage dropped).

#### Stage 5 — Package artifact (assign immutable identity)

- **What:** Produce the deployable artifact and give it a unique, immutable identity. For a JVM service this is usually a **container image** built from the fat JAR.
- **Internals — identity:** The identity should be **content-addressable** or at least **uniquely versioned**:
  - Container images get an **image digest** — a SHA-256 hash of the image's content (`sha256:abc123…`). Two builds producing byte-identical images get the same digest; this is the strongest form of identity.
  - Images also get human-readable **tags** (`myapp:1.4.2`, `myapp:git-<sha>`). **Tags are mutable** (someone can repoint `latest`), so for promotion you should pin by **digest**, not tag.
  - JARs get a **version** (`1.4.2`) and, when published, a Maven coordinate `groupId:artifactId:version`. Use **immutable release versions**, never overwrite. (Maven `-SNAPSHOT` versions are *mutable by design* — avoid them in promotion flows.)
- **Publish:** Push the image/JAR to the registry. From here on, *nothing rebuilds it*.
- **Failure modes:** registry push auth/quota failure; tag collision overwriting a prior artifact (avoid mutable tags for releases); non-reproducible build producing a different digest than expected.

#### Stage 6 — Integration test

- **What:** Run tests that exercise the *packaged* artifact against real dependencies (DB, cache, broker), typically spun up ephemerally.
- **Internals:** With **Testcontainers**, the test harness uses the Docker API to start throwaway containers (Postgres, Kafka, etc.), wires the app to them, runs tests, then tears them down. Alternatively, deploy the artifact to an ephemeral environment and test it over the network.
- **Why after packaging:** You want to test *the artifact you'll ship*, including its container entrypoint, not just classes on a test classpath.
- **Failure modes:** environment drift (test DB schema ≠ prod); container startup timeouts; port conflicts; "noisy neighbor" resource contention on shared runners.

#### Stage 7 — Deploy (to an environment)

- **What:** Take the immutable artifact and run it in a target environment, injecting environment-specific config and secrets at this point (never baked into the artifact).
- **Internals (Kubernetes example):** Update a Deployment manifest to reference the image **by digest**, apply it; Kubernetes performs a **rolling update** (gradually replacing old pods with new ones while keeping the service available), respecting readiness probes.
- **Strategies** (detailed in Section 7): rolling, **blue-green** (stand up a parallel "green" copy, switch traffic, keep "blue" for instant rollback), **canary** (route a small % of traffic to the new version, watch metrics, then ramp).
- **Failure modes:** bad config; failed readiness probe; insufficient capacity; migration ordering bug.

#### Stage 8 — Smoke test

- **What:** Immediately after deploy, run a tiny set of checks against the live deployment: health endpoint, a synthetic transaction, key metrics within bounds.
- **Why:** Catch "it deployed but it's broken" fast, before promoting or before users notice. In Continuous Deployment, a failing smoke test triggers automatic rollback.
- **Failure modes:** dependency not reachable from new env; config mismatch; cold-start latency exceeding the smoke-test timeout (false negative).

#### Stage 9 — Promote

- **What:** Repeat Stages 7–8 against the *next* environment up the ladder, using the **identical artifact** (same digest). Promotion is gated — by automated checks (smoke/integration green, error budget healthy) and/or a manual approval (the Continuous Delivery human gate).
- **Internals:** Promotion is usually *config change only* — point the next environment's manifest at the already-built, already-pushed image digest. No rebuild. Many teams model this declaratively with **GitOps** (the desired state of each environment is a file in Git; a controller like Argo CD or Flux continuously reconciles the live cluster to match Git).
- **Failure modes:** promoting a tag instead of a digest (and the tag moved); config divergence between environments; skipping the gate under pressure.

### 3.2 The execution machinery

What actually *runs* these stages?

- **Orchestrator / controller** — the brain that reads your pipeline definition, schedules jobs, tracks state, and reports results. Examples: the GitHub Actions service, GitLab CI coordinator, the Jenkins controller (formerly "master").
- **Runner / agent / executor** — the worker that executes a job's steps on some compute. GitHub calls them **runners**, GitLab calls them **runners**, Jenkins calls them **agents** (executing on **nodes**). A runner can be:
  - **Hosted/managed** — the vendor provisions a fresh, ephemeral VM per job (clean slate every time; you pay per minute).
  - **Self-hosted** — your own machines/containers (more control, persistent caches possible, but you own patching, security, and the risk of *state leakage* between jobs).
- **Workspace** — the working directory on the runner where the repo is checked out and steps operate. On hosted runners it's discarded after the job; persistence across stages requires explicit **artifacts/cache** mechanisms (Section 4).
- **Executor model (Jenkins)** — a node has N **executors** (concurrency slots); a job occupies one executor while running. (Distinct from a CPU core, though often mapped to one.)

### 3.3 Data flow between stages

Stages typically run in *separate, isolated* jobs (especially in cloud CI), each on a fresh runner. So data must be passed explicitly:

- **Cache** — best-effort, keyed, restored at job start and saved at end. Used for things you *can* rebuild but would rather not (dependency downloads, compiled outputs). A cache miss is not an error — it just rebuilds.
- **Artifacts** — first-class outputs you *must* keep (the JAR, the image reference, test reports). Uploaded by producing jobs, downloaded by consuming jobs. Unlike cache, a missing required artifact is an error.
- **Outputs/variables** — small key-value data passed downstream (e.g. the computed image digest), via job outputs (GitHub `outputs`), `dotenv` artifacts (GitLab), or stashed files (Jenkins).

### 3.4 Control flow: the DAG and gates

Modern pipelines are **DAGs of jobs** (Directed Acyclic Graph — jobs are nodes, "needs/depends-on" relationships are edges, no cycles). The orchestrator topologically sorts the DAG and runs jobs as soon as their dependencies are satisfied, maximizing parallelism. Control constructs:

- **`needs` / `depends_on`** — declares a job's prerequisites; the engine parallelizes everything not on a dependency chain.
- **`when` / `if` / conditions** — gate a job on branch, tag, file changes, manual approval, or prior results.
- **Manual gate / environment protection** — a required human approval before a protected (e.g. prod) job runs — the Continuous Delivery button.
- **Fan-out / matrix** — run the same job across a matrix of parameters (JDK 17 × JDK 21 × OS) in parallel.
- **Fail-fast vs. continue** — whether one failing parallel job cancels its siblings (fail-fast, save money) or lets them finish (continue, see all failures at once).

### 3.5 State machine of a pipeline run

A run and its jobs move through states:

```
queued → preparing(runner) → running → (success | failed | canceled | skipped | timed_out)
                                  │
                                  └─ (with manual gate) → waiting_for_approval → running
```

- **queued** — waiting for an available runner (concurrency limits, runner pool size).
- **preparing** — provisioning/booting the runner, pulling the job container, checking out code.
- **running** — executing steps.
- **terminal states** — success, failed (a step exited non-zero), canceled (user/automation), skipped (condition false), timed_out (exceeded the job timeout — a critical safety valve).
- **retry** — many systems support automatic retry of failed jobs (useful for flaky infra, dangerous for masking real bugs).

---

## 4. The complete toolkit

This section enumerates the concrete mechanisms, APIs, CLI commands, and config knobs you'll actually use, organized by area, each with purpose, key parameters, and defaults. Where defaults are version/vendor-specific, that's flagged.

### 4.1 Pipeline-definition syntax (pipeline-as-code)

**Pipeline-as-code** means the pipeline is defined in a file *in the repo*, version-controlled alongside the code it builds, reviewed in PRs, and rolled back like any code. This replaced clicking through UI job config (the old Jenkins "freestyle" way), which was un-reviewable and undocumented.

| System | File / location | Model | Runner term | Notes |
|---|---|---|---|---|
| **GitHub Actions** | `.github/workflows/*.yml` | YAML; **workflows → jobs → steps**; steps run "actions" (reusable units) or shell. | runner | Marketplace of reusable actions. Hosted runners are ephemeral VMs. |
| **GitLab CI/CD** | `.gitlab-ci.yml` | YAML; **stages → jobs**; jobs in the same stage run in parallel; `needs:` enables DAG. | runner | Built into GitLab; `rules:`/`only:`/`except:` for conditions. |
| **Jenkins (declarative)** | `Jenkinsfile` | Groovy DSL; `pipeline { stages { stage { steps } } }`. | agent/node | Huge plugin ecosystem; also a scripted (imperative) Groovy mode. |
| **CircleCI** | `.circleci/config.yml` | YAML; jobs + workflows; "orbs" are reusable packages. | executor | — |
| **Argo Workflows / Tekton** | Kubernetes CRDs (YAML) | Container-native, runs on k8s; steps are containers. | pod | Cloud-native CI/CD building blocks. |

### 4.2 GitHub Actions key constructs

| Construct | Purpose | Key fields / defaults |
|---|---|---|
| `on:` | Trigger events | `push`, `pull_request`, `schedule` (cron), `workflow_dispatch` (manual), `workflow_call` (reusable). |
| `jobs.<id>.runs-on` | Pick runner | e.g. `ubuntu-latest`. Each job gets a **fresh** runner. |
| `jobs.<id>.needs` | DAG dependency | Array of job ids; controls ordering & parallelism. |
| `steps.uses` | Run a reusable action | e.g. `actions/checkout@v4`, `actions/setup-java@v4`. **Pin to a SHA, not a tag, for security.** |
| `steps.run` | Run shell | Default shell `bash` on Linux. |
| `strategy.matrix` | Fan-out | e.g. `java: [17, 21]`. `fail-fast` default **true**. |
| `permissions` | Token scope (least privilege) | Default can be broad; set explicitly (e.g. `contents: read`). |
| `environment` | Protected deploy target | Enables required reviewers (the manual gate), secrets, wait timers. |
| `concurrency` | Cancel/limit overlapping runs | `cancel-in-progress: true` to supersede stale runs. |
| `timeout-minutes` | Job timeout | **Default 360 min (6h)** — far too long; set explicitly. |
| `actions/cache@v4` | Cache deps | `key`, `restore-keys`, `path`. |
| `actions/upload-artifact` | Persist outputs | `name`, `path`, `retention-days` (default 90, configurable). |

### 4.3 GitLab CI key constructs

| Keyword | Purpose | Notes / defaults |
|---|---|---|
| `stages:` | Ordered phases | Jobs in same stage run in parallel. |
| `script:` | Commands | Required for a normal job. |
| `needs:` | DAG edges | Enables out-of-stage-order parallelism. |
| `rules:` | Conditional execution | Modern replacement for `only/except`. |
| `cache:` | Best-effort cache | `key`, `paths`, `policy: pull/push`. |
| `artifacts:` | Pass outputs | `paths`, `expire_in`, `reports:` (junit, coverage, sast). |
| `environment:` | Track deploy target | Enables environment dashboards, manual gates (`when: manual`). |
| `extends:` / `!reference` | Reuse config | DRY pipelines. |
| `interruptible:` | Auto-cancel superseded | Saves runner minutes. |

### 4.4 Jenkins declarative key constructs

| Directive | Purpose |
|---|---|
| `agent` | Where to run (`any`, `label`, `docker`, `kubernetes`). |
| `stages { stage('X') { steps {} } }` | Pipeline structure. |
| `parallel {}` | Run branches concurrently. |
| `when {}` | Conditional stage execution. |
| `input` | Manual approval gate. |
| `post { always/success/failure {} }` | Cleanup & notifications. |
| `options { timeout(...) ; retry(...) }` | Run-level controls. |
| `environment {}` | Env vars / credentials binding (`credentials('id')`). |

### 4.5 JVM build-tool commands & flags

**Maven:**

| Command / flag | Purpose | Default/notes |
|---|---|---|
| `mvn verify` | compile + test + package + integration verify | Preferred CI goal. |
| `-o` / `--offline` | Force offline (use only cached deps) | Surfaces missing-dep problems; deterministic. |
| `-T 1C` | Parallel build, 1 thread per CPU core | Off by default. |
| `-Dmaven.repo.local=...` | Override local repo path | For cache control on runners. |
| `--no-transfer-progress` | Quiet download logs | Cleaner CI logs. |
| `-Denforcer...` (maven-enforcer-plugin) | Fail on banned deps / version ranges | Locks down reproducibility. |
| `mvn versions:set` | Set version | For release tooling. |

**Gradle:**

| Command / flag | Purpose | Default/notes |
|---|---|---|
| `gradle build` | assemble + check (test) | — |
| `--build-cache` | Reuse outputs from local/remote cache | **Off by default**; turn on in CI. |
| `--parallel` | Parallel project execution | Off by default (or set in `gradle.properties`). |
| `--configuration-cache` | Cache the configuration phase | Speeds repeated builds; some plugins incompatible. |
| `--scan` | Publish a build scan (diagnostics) | Great for debugging slow/failed builds. |
| `dependencyInsight` / `dependencies` | Inspect dep graph & conflicts | For "why is this version here?" |
| `--offline` | Use only cached deps | Determinism. |

### 4.6 Container build & supply-chain tooling

| Tool | Purpose | Notes |
|---|---|---|
| `docker build` / `buildx` | Build image from Dockerfile | BuildKit (default in buildx) gives layer caching, parallelism, `--mount=type=cache`. |
| **Jib** (Maven/Gradle plugin) | Build container images **without a Dockerfile or Docker daemon** | Deterministic, reproducible layers; popular for JVM. |
| **Buildpacks** (`pack`, Spring Boot `bootBuildImage`) | Build images from source via buildpacks | No Dockerfile; opinionated, reproducible. |
| `cosign` (Sigstore) | Sign & verify artifacts/images | Supply-chain integrity; keyless signing via OIDC. |
| `syft` / `cyclonedx` | Generate SBOM | Inventory of components. |
| `grype` / `trivy` | Scan image for CVEs | SCA at the image layer. |
| `crane` / `skopeo` | Copy/inspect images by digest, retag without rebuild | Enables digest-based promotion. |

### 4.7 Caching & incremental-build tooling

| Mechanism | What it caches | Keying |
|---|---|---|
| Maven local repo (`~/.m2`) | Downloaded dependencies | By artifact coordinate (immutable). |
| Gradle build cache | Task outputs (compiled classes, test results) | By a hash of task inputs (sources, classpath, args). |
| Gradle remote build cache | Same, shared across machines | HTTP cache node; team-wide reuse. |
| Docker/BuildKit layer cache | Image layers | By instruction + input hash; order layers stable→volatile. |
| **Bazel** action cache / remote cache | Every build action's outputs | By hash of action inputs; foundation of hermetic monorepo builds. |
| CI cache (`actions/cache`, GitLab `cache`) | Arbitrary paths | User-supplied key (often a hash of the lockfile). |

### 4.8 Deployment / promotion tooling

| Tool | Purpose |
|---|---|
| `kubectl set image ...@sha256:...` | Deploy a specific image **by digest**. |
| **Helm** | Templated k8s manifests (charts); `helm upgrade --install`. |
| **Argo CD / Flux** | GitOps controllers — reconcile cluster to Git desired-state. |
| **Argo Rollouts / Flagger** | Progressive delivery (canary/blue-green with metric analysis & auto-rollback). |
| Terraform / Pulumi | Provision the environment infrastructure (IaC — Infrastructure as Code). |

---

## 5. Code examples by use case

These are distinct real scenarios, not variations of one. Comments explain the non-obvious lines.

### 5.1 GitHub Actions — PR CI for a Maven service (fast feedback gate)

```yaml
# .github/workflows/pr-ci.yml
# Goal: gate every PR with build + unit tests + scans, FAST. No deploy here.
name: PR CI

on:
  pull_request:
    branches: [ main ]

# Cancel an in-progress run if the PR is updated again (don't waste minutes).
concurrency:
  group: pr-ci-${{ github.ref }}
  cancel-in-progress: true

# Least privilege: this workflow only needs to read the repo.
permissions:
  contents: read

jobs:
  build-test:
    runs-on: ubuntu-latest
    timeout-minutes: 20          # never rely on the 6h default
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven           # caches ~/.m2 keyed on pom hashes automatically

      - name: Build & unit test
        # verify runs compile + unit + (configured) integration verification.
        # --no-transfer-progress keeps logs readable; -T 1C parallelizes by core.
        run: mvn -B -T 1C --no-transfer-progress verify

      - name: Upload test report (for the UI even on failure)
        if: always()             # run even if the build step failed
        uses: actions/upload-artifact@v4
        with:
          name: surefire-reports
          path: '**/target/surefire-reports/*.xml'
          retention-days: 7

  dependency-scan:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - name: OWASP Dependency-Check (SCA: scan deps for known CVEs)
        run: |
          mvn -B org.owasp:dependency-check-maven:check \
              -DfailBuildOnCVSS=7   # fail if any dependency has a CVE >= 7.0 (high)
```

Why it's shaped this way: two independent jobs run in parallel (the DAG has no edge between them), each is time-boxed, and the run auto-cancels when the PR is pushed again. Feedback to the developer arrives in minutes.

### 5.2 GitHub Actions — main-branch pipeline: build once → push by digest → deploy with manual gate

```yaml
# .github/workflows/release.yml
# Build the artifact ONCE, capture its DIGEST, deploy to staging automatically,
# then to prod behind a manual approval (Continuous DELIVERY).
name: Release

on:
  push:
    branches: [ main ]

permissions:
  contents: read
  packages: write        # to push to GHCR
  id-token: write        # for keyless cosign signing via OIDC

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    outputs:
      digest: ${{ steps.push.outputs.digest }}   # pass the digest downstream
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }

      # Jib builds a reproducible image with NO Dockerfile/daemon and pushes it.
      - name: Build & push image with Jib
        id: push
        run: |
          IMAGE=ghcr.io/${{ github.repository }}
          mvn -B compile com.google.cloud.tools:jib-maven-plugin:build \
              -Dimage=$IMAGE:git-${{ github.sha }}
          # Resolve the immutable digest of what we just pushed:
          DIGEST=$(crane digest $IMAGE:git-${{ github.sha }})
          echo "digest=$IMAGE@$DIGEST" >> "$GITHUB_OUTPUT"

      - name: Sign the image (supply-chain integrity)
        run: cosign sign --yes ${{ steps.push.outputs.digest }}

  deploy-staging:
    needs: build-and-push
    runs-on: ubuntu-latest
    environment: staging          # auto, no approval required
    steps:
      - uses: actions/checkout@v4
      - name: Deploy the EXACT digest to staging
        run: |
          kubectl set image deploy/myapp \
            myapp=${{ needs.build-and-push.outputs.digest }} -n staging
          kubectl rollout status deploy/myapp -n staging --timeout=120s
      - name: Smoke test
        run: curl -fsS https://staging.example.com/healthz   # -f fails on non-2xx

  deploy-prod:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production       # PROTECTED: requires a human approval (the gate)
    steps:
      - uses: actions/checkout@v4
      - name: Verify signature before prod deploy
        run: cosign verify ${{ needs.build-and-push.outputs.digest }} ...
      - name: Deploy the SAME digest to prod (build once, deploy many)
        run: |
          kubectl set image deploy/myapp \
            myapp=${{ needs.build-and-push.outputs.digest }} -n prod
          kubectl rollout status deploy/myapp -n prod --timeout=180s
      - name: Smoke test prod
        run: curl -fsS https://example.com/healthz
```

The critical detail: the **digest** computed once in `build-and-push` is the *only* image reference used in both staging and prod. No rebuild occurs; identical bytes are promoted. The `production` environment carries a required-reviewer rule, making prod a deliberate, audited button-press.

### 5.3 GitLab CI — staged pipeline with DAG, caching, and a manual prod gate

```yaml
# .gitlab-ci.yml
stages: [build, test, scan, package, deploy]

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=.m2/repository"   # cache deps inside the project dir

# Cache the Maven repo, keyed on the lockfile-equivalent (pom hashes).
.cache_def: &cache_def
  cache:
    key:
      files: [ pom.xml ]
    paths: [ .m2/repository ]
    policy: pull-push

compile:
  stage: build
  <<: *cache_def
  script: mvn -B -o compile         # -o offline: fail loudly on missing deps

unit-test:
  stage: test
  <<: *cache_def
  needs: [ compile ]                # DAG edge -> starts as soon as compile is done
  script: mvn -B test
  artifacts:
    when: always
    reports:
      junit: '**/target/surefire-reports/TEST-*.xml'   # GitLab renders test trends

sast:
  stage: scan
  needs: [ compile ]                # parallel with unit-test
  script: mvn -B com.github.spotbugs:spotbugs-maven-plugin:check

package:
  stage: package
  needs: [ unit-test, sast ]
  script: mvn -B package -DskipTests
  artifacts:
    paths: [ target/*.jar ]
    expire_in: 1 week

deploy-staging:
  stage: deploy
  needs: [ package ]
  environment: { name: staging, url: https://staging.example.com }
  script: ./deploy.sh staging target/*.jar

deploy-prod:
  stage: deploy
  needs: [ deploy-staging ]
  environment: { name: production, url: https://example.com }
  when: manual                      # the Continuous DELIVERY gate
  script: ./deploy.sh production target/*.jar
```

### 5.4 Jenkins declarative — parallel quality gates + input approval

```groovy
// Jenkinsfile
pipeline {
  agent { docker { image 'maven:3.9-eclipse-temurin-21' } } // hermetic-ish toolchain
  options {
    timeout(time: 30, unit: 'MINUTES')   // safety valve
    disableConcurrentBuilds()            // avoid two runs of the same branch racing
  }
  stages {
    stage('Build') { steps { sh 'mvn -B -T 1C compile' } }

    stage('Quality Gates') {
      parallel {
        stage('Unit')  { steps { sh 'mvn -B test' }
          post { always { junit '**/target/surefire-reports/*.xml' } } }
        stage('SAST')  { steps { sh 'mvn -B verify -DskipTests sonar:sonar' } }
        stage('SCA')   { steps { sh 'mvn -B org.owasp:dependency-check-maven:check' } }
      }
    }

    stage('Package') { steps { sh 'mvn -B package -DskipTests'
      archiveArtifacts artifacts: 'target/*.jar', fingerprint: true } } // fingerprint = traceability

    stage('Deploy staging') { steps { sh './deploy.sh staging' } }

    stage('Approve prod') {
      steps { input message: 'Promote to production?', ok: 'Deploy' } // manual gate
    }
    stage('Deploy prod') { steps { sh './deploy.sh production' } }
  }
  post {
    failure { slackSend channel: '#alerts', message: "Build failed: ${env.BUILD_URL}" }
  }
}
```

### 5.5 Reproducible JVM container build with Jib (no Dockerfile)

```xml
<!-- pom.xml (excerpt): deterministic image layers, daemonless, reproducible -->
<plugin>
  <groupId>com.google.cloud.tools</groupId>
  <artifactId>jib-maven-plugin</artifactId>
  <version>3.4.3</version>
  <configuration>
    <from><image>eclipse-temurin:21-jre@sha256:&lt;pinned-digest&gt;</image></from>
    <to><image>ghcr.io/acme/myapp</image></to>
    <container>
      <!-- Jib sets a fixed creation timestamp (epoch 0) by default so two builds
           of identical inputs yield the SAME image digest => reproducible. -->
      <user>1000:1000</user>                 <!-- run as non-root -->
      <ports><port>8080</port></ports>
    </container>
  </configuration>
</plugin>
```

```bash
# Two runs on different machines, same source + pinned base => identical digest.
mvn -B compile jib:build
crane digest ghcr.io/acme/myapp:latest   # should match across reproducible builds
```

### 5.6 Bazel — incremental, cached monorepo build (the monorepo angle)

```python
# BUILD.bazel — declare a Java library + binary with EXPLICIT inputs/outputs.
java_library(
    name = "payments",
    srcs = glob(["src/main/java/com/acme/payments/*.java"]),
    deps = ["//common:money", "@maven//:com_google_guava_guava"],
)

java_binary(
    name = "payments_service",
    main_class = "com.acme.payments.Main",
    runtime_deps = [":payments"],
)
```

```bash
# Bazel computes a hash of each action's inputs. Unchanged targets are NOT rebuilt;
# their outputs come from the cache (local, and a shared REMOTE cache across CI + devs).
bazel build //payments:payments_service --remote_cache=grpcs://cache.acme.internal

# Build only what a change actually affects (precise incrementality at huge scale):
bazel test $(bazel query 'rpdeps(//..., set(//payments:payments))')
#   rpdeps = reverse transitive deps -> exactly the targets impacted by payments.
```

Why Bazel for monorepos: it enforces **hermeticity** (every action declares its complete inputs; the network and undeclared files are off-limits), so build outputs are *content-addressable* and shareable across a remote cache. In a repo with thousands of modules, this means a one-line change rebuilds and retests only the affected slice — turning hour-long builds into minutes. (Cost: high configuration overhead and a steep learning curve; see Section 8.)

### 5.7 Computing a deterministic version & avoiding mutable tags

```bash
# Derive an immutable, traceable version from git. Requires full history (no shallow clone)
# if using git describe against tags.
VERSION="$(git describe --tags --always --dirty)"   # e.g. v1.4.2-3-gabc123
SHA="$(git rev-parse --short HEAD)"

# Tag the image with BOTH a human tag and the immutable sha; promote by DIGEST later.
docker buildx build --tag ghcr.io/acme/myapp:$VERSION \
                    --tag ghcr.io/acme/myapp:git-$SHA \
                    --push .

# NEVER promote ':latest' across environments — it is mutable and a known footgun.
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance & pipeline speed (fast feedback)

Fast feedback is the whole point of CI; a 45-minute PR build means developers context-switch away and stop running it. Targets that good teams hold: **PR build < 10 min, ideally < 5**.

Levers, roughly in order of impact:

1. **Cache dependencies** — never re-download Maven Central every run. Cache `~/.m2` / `~/.gradle/caches` keyed on the lockfile/`pom.xml` hash.
2. **Incremental & cached builds** — Gradle build cache, Bazel remote cache. Skip recompiling/retesting unchanged modules.
3. **Parallelize** — split the DAG, run independent jobs concurrently; shard the test suite across runners; matrix builds.
4. **Right-size the test pyramid** — push checks down the pyramid; don't run a 30-minute E2E suite on every PR. Run heavy suites nightly or on merge, fast unit/contract tests per PR.
5. **Fail fast** — order cheap, high-signal stages first (compile, lint) so an obvious failure stops in seconds, not after the slow integration tests.
6. **Quarantine flaky tests** — flakiness is a *speed* problem too: retries and reruns inflate wall-clock time and erode trust.
7. **Avoid cold starts** — warm runner pools, pre-pulled images, BuildKit layer cache.
8. **Test impact analysis / affected-targets** — only run tests reachable from the change (Bazel `rpdeps`, Gradle `--continue` with affected modules, commercial TIA tools).

### 6.2 Correctness & concurrency

- **Determinism first.** A pipeline that's nondeterministic can't be trusted. Pin everything: dependency versions (lockfiles), base image **digests**, action/plugin versions (to SHAs), toolchain versions (`setup-java` with explicit JDK).
- **Test isolation.** Each test must not depend on order or shared mutable state. Concurrency in test execution (parallel forks) exposes hidden shared-state bugs — that's a feature, fix the test.
- **Idempotent deploys.** Re-running a deploy of the same artifact must be safe (declarative apply, not imperative "create").
- **Database migrations** are the classic correctness trap: schema changes must be **backward compatible** with the currently-running version during a rolling deploy (expand/contract pattern — add the new column, deploy code that writes both, backfill, then in a later release drop the old column). Never deploy a migration that the old pods can't tolerate.

### 6.3 Security & supply chain

- **Least-privilege tokens.** Scope CI tokens narrowly (`contents: read`). The CI system is a high-value attack target — it has push access to prod.
- **Pin third-party actions/plugins to a commit SHA**, not a moving tag — a compromised tag (the 2025 `tj-actions/changed-files` incident is a real example of a popular action being maliciously altered) can otherwise exfiltrate your secrets.
- **Scan dependencies (SCA) and code (SAST)** on every PR; gate on severity. Generate an **SBOM** and **sign artifacts** (cosign) so consumers can verify provenance.
- **SLSA** (Supply-chain Levels for Software Artifacts — a framework of escalating integrity guarantees) — aim for tamper-evident provenance: the artifact records *how* it was built, by which pipeline, from which source.
- **Secret handling.** Never echo secrets to logs; use the platform secret store / OIDC short-lived credentials rather than long-lived static keys baked into config. Scan for committed secrets (gitleaks).
- **Ephemeral runners.** Prefer fresh runners per job; persistent self-hosted runners can leak state/secrets between untrusted jobs (a real risk for public-repo PR builds running attacker-controlled code).

### 6.4 Observability

Treat the pipeline like a production system — instrument it.

- **The four DORA metrics** (from the DevOps Research and Assessment program — the industry-standard delivery KPIs): **Deployment Frequency**, **Lead Time for Changes** (commit→prod), **Change Failure Rate** (% of deploys causing incidents), and **Failed Deployment Recovery Time** (formerly MTTR — Mean Time To Restore).
- **Per-stage timing** — track where the minutes go; a build that crept from 5→15 min is a regression.
- **Flaky-test dashboards** — track tests by failure-without-code-change rate.
- **Build scans / structured logs** — Gradle build scans, GitHub Actions logs with grouping, OpenTelemetry traces of pipeline runs.
- **Artifact lineage** — be able to answer "which commit, deps, and tests produced the bytes currently in prod?" — provenance + immutable digests make this answerable.

### 6.5 Cost

- CI minutes are real money (hosted runners bill per minute; large fleets dominate cloud bills). Cancel superseded runs, cache aggressively, right-size runners, and don't run the full matrix on every push.
- Storage costs: artifact/cache retention — set sane `expire_in`/`retention-days`. Don't keep every nightly image forever.
- The *biggest* hidden cost is **slow pipelines × engineer time**: a 20-minute build run 50×/day across a team is enormous lost productivity. Speed pays for itself.

### 6.6 Testability of the pipeline itself

- Lint pipeline files (`actionlint` for GitHub Actions, `gitlab-ci lint`, `jenkins-cli declarative-linter`).
- Test reusable components (composite actions, shared workflows, Bazel rules) in isolation.
- Use ephemeral/preview environments to test deploys without touching shared infra.
- Run the pipeline against itself (dogfood) before depending on it for prod.

### 6.7 Anti-patterns to avoid

- **Rebuilding per environment** (violates build-once-deploy-many; "works in staging" lies).
- **Promoting mutable tags** (`:latest`, `-SNAPSHOT`) — promote digests.
- **Baking environment config/secrets into the artifact** — inject at runtime.
- **Tolerating flaky tests / blanket auto-retry** — masks real bugs, erodes trust.
- **A monolithic 40-minute PR build** — kills feedback loops.
- **Manual UI-clicked job config** (no pipeline-as-code) — un-reviewable, un-versioned, un-rollback-able.
- **No timeout on jobs** — a hung step burns a runner for hours.
- **Broad CI tokens / unpinned third-party actions** — supply-chain risk.
- **Long-lived feature branches** — defeats *Continuous* Integration; integrate at least daily, prefer trunk-based development with short-lived branches.
- **Deploying schema migrations incompatible with the running version** during rolling updates.

---

## 7. Advanced topics & deep internals

### 7.1 Reproducible / hermetic / deterministic builds

Three related-but-distinct ideas:

- **Deterministic (reproducible) build** — given the same source and same declared inputs, the build produces *byte-for-byte identical output* (same artifact hash). Enemies of reproducibility: embedded timestamps, file ordering in archives, absolute paths, non-deterministic code generation, parallel non-determinism, and unpinned dependencies. JVM-specific fixes: set `project.build.outputTimestamp` (Maven Reproducible Builds), use a fixed `SOURCE_DATE_EPOCH`, normalize JAR entry order and metadata, use Jib (which fixes timestamps to epoch 0 and orders layers deterministically by default).
- **Hermetic build** — the build is sealed off from the host: it can only see explicitly-declared inputs, can't reach the network for undeclared dependencies, can't read undeclared files, doesn't depend on host-installed tools. Bazel is the flagship; hermeticity is what makes its remote cache *correct* (an action's inputs fully determine its outputs).
- **Why it matters:** reproducibility enables (a) trustworthy caching — you can safely reuse a cached output because identical inputs guarantee identical outputs; (b) supply-chain verification — independent rebuilders can confirm an artifact wasn't tampered with; (c) debuggability — "why did the bytes change?" has a finite answer.

### 7.2 Caching internals & cache correctness

A build cache is a map: `hash(inputs) → outputs`. Correctness hinges on the key capturing *all* inputs that affect outputs.

- **Under-keying** (forgetting an input, e.g. a JDK version or an env var) → **stale cache hits** = wrong outputs reused = mysterious, dangerous bugs ("it builds fine on CI but the cache served last week's class"). This is the cardinal cache sin.
- **Over-keying** (including irrelevant inputs, e.g. a timestamp or absolute path) → cache never hits = no benefit.
- **Cache layers:** local (per-machine) → remote shared (team/CI) → with Bazel/RBE, **remote execution** (run the action in the cloud too).
- **Docker layer cache:** order Dockerfile instructions stable→volatile (copy `pom.xml` and run `mvn dependency:go-offline` *before* copying source, so the dependency layer is cached across source changes). BuildKit `--mount=type=cache` persists the `~/.m2` dir across builds without baking it into a layer.
- **CI cache scoping:** caches are typically scoped per branch with fallback to the default branch (`restore-keys`); a poisoned/corrupt cache requires busting the key (bump a version in the key string).

### 7.3 Artifact immutability & promotion internals

- **Content-addressable identity.** A container image manifest is hashed (SHA-256) → the **digest**. Pulling `image@sha256:…` guarantees the exact bytes; pulling `image:tag` does not (tags are pointers that can move). Promotion should reference digests end-to-end.
- **Retag without rebuild.** Tools like `crane copy` / `skopeo copy` move/retag an image *by copying the existing layers/manifest*, never rebuilding — so the digest is preserved while you add a human-friendly tag (`:prod`).
- **Immutable registries.** Enable "immutable tags" so a published release tag can't be silently overwritten. Maven release repos reject re-deploying an existing version (only `-SNAPSHOT` repos allow overwrite — which is exactly why SNAPSHOTs are unfit for promotion).
- **GitOps promotion.** The desired image digest per environment is stored in Git; promotion is a commit that bumps the digest in the next environment's manifest, reconciled by Argo CD/Flux. This makes promotion auditable, reviewable, and revertible (rollback = `git revert`).

### 7.4 Deployment strategies (the deploy-stage internals)

| Strategy | Mechanism | Rollback | Best for |
|---|---|---|---|
| **Recreate** | Stop all old, start all new | Redeploy old | Dev; tolerable downtime. |
| **Rolling** | Replace pods incrementally, respecting readiness | Roll back to prior ReplicaSet | Default k8s; brief mixed-version window. |
| **Blue-green** | Run old (blue) + new (green) in full; switch router to green | Flip router back to blue (instant) | Fast rollback; needs 2× capacity briefly. |
| **Canary** | Send small % traffic to new; watch metrics; ramp | Route 100% back to old | Risk-controlled; needs good metrics + automation (Argo Rollouts/Flagger). |
| **Shadow / mirror** | Mirror real traffic to new without serving its responses | N/A (no user impact) | Load/correctness testing in prod safely. |

Decouple **deploy** from **release** with **feature flags** (toggles that turn behavior on/off at runtime without redeploying) — deploy the code dark, then enable for 1% of users, ramp, and instantly kill if metrics regress.

### 7.5 Pipeline-as-code advanced: reuse, DRY, and scale

- **Reusable workflows / shared libraries.** GitHub `workflow_call`, GitLab `include:`/`extends:`, Jenkins shared libraries, CircleCI orbs. Centralize the golden path so 200 services don't each maintain a divergent pipeline.
- **Templating vs. duplication.** Over-templating becomes an unreadable meta-language; balance reuse with locality.
- **Dynamic pipelines.** GitLab child pipelines / generated YAML, Buildkite dynamic pipelines — generate the job graph at runtime based on what changed (monorepo path filters).
- **Monorepo CI.** Use path filters and affected-target analysis (Bazel, Nx, Turborepo) so a change to service A doesn't rebuild/test all 300 services.

### 7.6 Lesser-known behaviors & gotchas

- **Shallow clone breaks version derivation** (`git describe` needs tag history; `fetch-depth: 0` to fix).
- **GitHub Actions default `GITHUB_TOKEN`** is scoped per-repo and expires when the job ends — but its *default permissions* may be broader than you want; set `permissions:` explicitly.
- **`pull_request` vs `pull_request_target`** in GitHub Actions: the latter runs with *write* secrets in the base repo's context for forked PRs — a notorious privilege-escalation footgun; avoid it for untrusted forks.
- **Matrix `fail-fast: true` default** cancels sibling matrix legs on first failure — surprising when you wanted to see all results.
- **Maven `-SNAPSHOT` mutability** and **Docker tag mutability** are the two most common sources of "but it worked yesterday."
- **JVM fork count for tests** affects both speed and hidden-shared-state bug exposure; the Surefire default forking and parallel settings are version-specific — measure, don't assume.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Continuous Delivery vs Continuous Deployment

| | Continuous Delivery | Continuous Deployment |
|---|---|---|
| Prod deploy | Manual approval (button) | Fully automatic |
| Prereqs | Solid CI, repeatable deploys | + excellent tests, progressive delivery, auto-rollback, strong observability |
| Risk posture | Human checkpoint | Machine-trusted |
| Use when | Regulated/high-blast-radius domains; not yet enough automated confidence | High-velocity product teams with deep automation and feature flags |
| Avoid when | — | Compliance forbids unattended prod changes; weak test coverage |

### 8.2 Build tool: Maven vs Gradle vs Bazel

| | Maven | Gradle | Bazel |
|---|---|---|---|
| Model | Declarative lifecycle (XML) | Task-graph DSL (Groovy/Kotlin) | Hermetic action graph |
| Incrementality | Limited | Good (build cache, incremental) | Excellent (precise, content-addressed) |
| Caching | Dep cache only | Local + remote build cache | Local + remote cache + remote execution |
| Reproducibility | Achievable (with effort) | Achievable | First-class (hermetic) |
| Monorepo scale | Poor at huge scale | Good | Excellent |
| Learning curve | Low | Medium | High |
| Use when | Standard Java service, convention-driven | Flexible builds, Android, perf matters | Massive polyglot monorepo, many modules |
| Avoid when | Need fine-grained incrementality at scale | — | Small/simple repo (overkill) |

### 8.3 Runner model: hosted vs self-hosted

| | Hosted (managed) | Self-hosted |
|---|---|---|
| Setup/maintenance | None (vendor) | You patch, scale, secure |
| Cost | Per-minute, predictable | Capex/Opex of your fleet |
| Speed cold start | Slower (fresh VM each time) | Faster (warm, cached) |
| Security | Isolated per job | Risk of state leakage; must isolate untrusted PRs |
| Use when | Default; small/medium teams | Need special hardware (GPU), private network, big caches, high volume |

### 8.4 Deploy strategy decision rules

- **Use rolling** when brief mixed-version is fine and you want the simple default.
- **Use blue-green** when you need *instant* rollback and can afford 2× capacity briefly.
- **Use canary** when blast radius matters and you have metric-driven automation; it's the gold standard for high-traffic services.
- **Avoid blue-green/canary** when you lack the observability to make the switch/ramp decision safely — you'd just be guessing.

### 8.5 Trunk-based development vs long-lived branches

- **Trunk-based** (short-lived branches, merge to mainline ≥ daily, behind flags) maximizes *Continuous* Integration and minimizes merge pain — the recommended default for CI.
- **GitFlow / long-lived release branches** suit infrequent, versioned releases (e.g. shipped on-prem software) but reintroduce integration risk and slow feedback.

---

## 9. Failure modes & debugging

### 9.1 Common production failures and how to diagnose

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| "Works in staging, breaks in prod" | Per-env rebuild or config baked into artifact | Compare image **digests** across envs (`crane digest`, `kubectl get deploy -o yaml`) | Build once, promote by digest; externalize config |
| Intermittent red builds, no code change | Flaky tests / shared state / timing | Rerun history, flaky dashboard, run tests in random order | Fix isolation; quarantine; ban blanket retry |
| Stale/incorrect outputs after caching | Under-keyed cache | Bust the cache key; diff inputs; disable cache to confirm | Add missing inputs to the key |
| Build suddenly slow | Lost cache hits, runner contention, new heavy step | Per-stage timing, Gradle `--scan`, runner metrics | Restore caching; parallelize; right-size |
| `git describe` returns wrong version | Shallow clone (no tag history) | Check `fetch-depth` | `fetch-depth: 0` / unshallow |
| Dependency resolution fails today, fine yesterday | Yanked version, moved `-SNAPSHOT`, repo outage | `mvn -o`/`dependencyInsight`; check registry status | Pin versions; mirror/cache deps; avoid SNAPSHOTs |
| Deploy hangs / pods CrashLoopBackOff | Bad config, failed readiness probe, migration mismatch | `kubectl rollout status`, `kubectl logs`, `describe pod`, events | Fix config; backward-compatible migration; rollback |
| Secret leaked in logs | Echoed secret / committed credential | Secret scanner, log audit | Rotate immediately; mask; use secret store |
| Promoted the wrong version | Mutable tag moved | Compare deployed digest to intended | Promote by digest, enable immutable tags |
| Supply-chain compromise | Unpinned third-party action altered | Audit action versions, network egress | Pin to SHA, verify signatures, restrict egress |

### 9.2 Debugging toolkit

- **Reproduce locally:** run the exact build command (`mvn -B verify`) with the same flags; use the same toolchain image the runner uses.
- **Inspect the graph:** Gradle `--scan` / `dependencyInsight`, Maven `dependency:tree`, Bazel `query`/`aquery`.
- **Compare artifacts byte-for-byte:** `crane digest`, `diffoscope` (shows *why* two "identical" builds differ — pinpoints reproducibility breaks).
- **Bisect:** `git bisect` to find the commit that introduced a failure; CI history to find the run where timing regressed.
- **k8s deploy:** `kubectl rollout status/undo`, `kubectl describe`, `kubectl logs --previous`, events.
- **Pipeline lint/replay:** `actionlint`, GitLab `ci lint`, Jenkins "Replay", `act` (run GitHub Actions locally).

### 9.3 Real-world incident patterns

- **The mutable-tag rollback that wasn't.** A team rolled back by redeploying `:latest`, but `:latest` had since moved to the broken build — re-deploying the bug. Lesson: pin and roll back by **digest**.
- **The under-keyed cache.** A remote build cache keyed without the JDK minor version served classes compiled by a different compiler; subtle runtime bytecode incompatibilities surfaced in prod only. Lesson: the cache key must include *every* input.
- **The poisoned action (`tj-actions/changed-files`, 2025).** A widely-used GitHub Action was compromised and modified to dump CI secrets into build logs; thousands of repos using a floating tag were exposed. Lesson: pin third-party actions to a commit SHA, restrict token scope and egress.
- **The migration mismatch.** A rolling deploy added a NOT NULL column the still-running old pods didn't write to, causing writes to fail mid-rollout. Lesson: expand/contract, backward-compatible migrations.

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. ★ marks senior-signal questions (judgment/tradeoff, not recall).

**Q1. What's the difference between Continuous Delivery and Continuous Deployment?**
Model: Both build on CI. *Delivery* keeps every validated build *deployable to prod at any time* but requires a manual approval to actually ship; *Deployment* removes that human gate — every green build goes live automatically.
- Follow-up: *What must be true to safely do Continuous Deployment?* Excellent automated tests, progressive delivery (canary), automated rollback on bad metrics, strong observability, and feature flags to decouple deploy from release.
- Follow-up: *When would you deliberately stay on Delivery?* Regulated domains, high blast radius, or insufficient automated confidence.

**Q2. Explain "build once, deploy many" and why it matters.**
Model: Produce one immutable artifact early, give it a content-addressable identity (digest), and promote *that exact artifact* through every environment, injecting config at runtime. It guarantees "what you tested is what you shipped," saves rebuild cost, and preserves traceability.
- Follow-up: *How do you enforce it technically?* Promote by digest not tag; immutable registries; GitOps where the env manifest references the digest.
- Follow-up: *What's the failure if violated?* "Works in staging, breaks in prod" — a per-env rebuild pulled different bytes.

**Q3. Walk through the canonical pipeline stages.**
Model: checkout → build/compile → unit test → static analysis & security scan → package artifact (assign immutable id) → integration test → deploy → smoke test → promote. Order cheap high-signal stages first for fast failure; package before integration tests so you test the real artifact.
- Follow-up: *Why smoke test after deploy?* To catch "deployed but broken" fast and trigger rollback before promotion/users.
- Follow-up: *Where do secrets/config enter?* At deploy/run time, never baked into the artifact.

**Q4. What is a hermetic build and why does it enable caching? ★**
Model: A hermetic build sees only explicitly-declared inputs (no network, no host tools, no undeclared files), so an action's inputs *fully determine* its outputs. That's the precondition for a *correct* content-addressed cache: identical input hash ⇒ guaranteed-identical output, safe to reuse.
- Follow-up: *What breaks reproducibility on the JVM?* Embedded timestamps, JAR entry ordering, absolute paths, unpinned deps; fix with `outputTimestamp`/Jib.
- Follow-up: *Why is Bazel good for monorepos?* Precise dependency graph + remote cache means a change rebuilds/retests only affected targets.

**Q5. How would you get a 30-minute PR build down to under 5? ★**
Model: Measure per-stage time first. Then: cache dependencies, enable build cache/incremental builds, parallelize the DAG and shard tests, run only affected tests (TIA/Bazel `rpdeps`), move heavy E2E suites off the PR path to nightly/merge, fail fast on cheap stages, fix/quarantine flaky tests, warm runners and layer caches.
- Follow-up: *What do you NOT cut?* Fast, high-signal correctness and security gates on the PR.
- Follow-up: *How do you keep it fast over time?* Track per-stage timing as an SLO; alert on regressions.

**Q6. What's the danger of mutable tags like `:latest`, and how do you avoid it?**
Model: Tags are movable pointers; the bytes behind `:latest` can change, so promoting/rolling-back by tag can deploy something different from what you tested. Pin by **digest** (`image@sha256:…`), use immutable registry tags, and retag without rebuild via `crane`/`skopeo`.
- Follow-up: *Why are Maven `-SNAPSHOT`s analogous?* They're mutable by design and unfit for promotion; use immutable release versions.
- Follow-up: *How does this affect rollback?* Roll back by digest to the known-good artifact, not by retag.

**Q7. How do you keep CI/CD secure against supply-chain attacks? ★**
Model: Least-privilege CI tokens; pin third-party actions/plugins to commit SHAs (not floating tags); SCA + SAST + secret scanning gating PRs; generate SBOMs and sign artifacts (cosign); short-lived OIDC creds over static secrets; ephemeral runners; restrict network egress; aim for SLSA provenance.
- Follow-up: *Concrete example of the risk?* The `tj-actions/changed-files` compromise leaked secrets from repos using a floating tag.
- Follow-up: *Why ephemeral runners?* Prevent state/secret leakage between jobs, especially untrusted fork PRs.

**Q8. What is a cache key and what goes wrong if it's under-keyed?**
Model: A build cache maps `hash(all inputs) → outputs`. Under-keying omits an input that actually affects output (JDK version, env var), so the cache returns *stale, wrong* outputs on a hit — silent, dangerous bugs. Over-keying just kills hit rate. The key must capture every output-affecting input.
- Follow-up: *How do you bust a poisoned cache?* Bump a version token in the key string.
- Follow-up: *Docker layer caching best practice?* Order instructions stable→volatile; copy deps and resolve them before copying source.

**Q9. Compare deployment strategies and when to use each.**
Model: Recreate (downtime, dev only); rolling (default, brief mixed-version); blue-green (instant rollback, 2× capacity); canary (small-% traffic with metric gating, best for high-traffic risk control); shadow (mirror traffic, no user impact). Choose based on rollback speed needs, capacity, and observability maturity.
- Follow-up: *How do canary and feature flags differ?* Canary routes traffic to a new *deployment*; flags toggle *behavior* at runtime — flags decouple deploy from release.
- Follow-up: *Rolling-update DB migration pitfall?* Migrations must be backward compatible with the running version (expand/contract).

**Q10. What metrics tell you a CI/CD system is healthy?**
Model: The four DORA metrics — Deployment Frequency, Lead Time for Changes, Change Failure Rate, Failed-Deployment Recovery Time — plus pipeline-internal signals: per-stage duration, flaky-test rate, queue time, cache hit rate.
- Follow-up: *Which two trade off?* Pushing frequency/lead-time without quality can raise change-failure-rate — the elite teams improve all four together.
- Follow-up: *How do you measure lead time precisely?* Timestamp commit→prod-deploy via the pipeline, not estimates.

**Q11. Why pipeline-as-code instead of UI-configured jobs?**
Model: It's version-controlled, reviewed in PRs, rolled back like code, reproducible, and self-documenting. UI config is un-reviewable, drift-prone, and can't be diffed or reverted.
- Follow-up: *How do you avoid 200 services drifting?* Reusable workflows/shared libraries/orbs for the golden path.
- Follow-up: *Risk of over-templating?* It becomes an opaque meta-language; balance reuse vs. locality.

**Q12. Where do integration tests belong and why after packaging? ★**
Model: After packaging, so you test the *actual artifact* (its container, entrypoint, runtime classpath) against real dependencies (Testcontainers), not just classes on a test classpath — closing the gap between "tested" and "shipped."
- Follow-up: *Why not run all E2E on every PR?* Too slow/brittle — keep the PR loop fast; run heavy suites on merge/nightly.
- Follow-up: *How keep integration tests deterministic?* Ephemeral, isolated dependencies; pinned schema; no shared state.

---

## 11. Glossary

- **Action (GitHub)** — a reusable unit of work referenced by a step (e.g. `actions/checkout`).
- **Agent (Jenkins)** — a worker node that executes pipeline steps.
- **Artifact** — durable build output (JAR, image, package) that gets deployed.
- **Artifact registry/repository** — server hosting artifacts (Nexus, Artifactory, ECR, GHCR).
- **Bazel** — a hermetic, content-addressed build system for large monorepos.
- **Blue-green deploy** — run old+new in parallel; switch traffic; instant rollback.
- **Bytecode** — portable JVM instruction set produced by `javac`.
- **Canary deploy** — route a small traffic % to a new version, watch metrics, then ramp.
- **CD** — Continuous Delivery (always deployable, manual gate) or Continuous Deployment (auto to prod).
- **CI** — Continuous Integration: frequent merges, auto build+test each merge.
- **CVE** — Common Vulnerabilities and Exposures; the public catalog of known security flaws.
- **DAG** — Directed Acyclic Graph; the job dependency structure of a pipeline.
- **Digest** — SHA-256 content hash of a container image; immutable identity.
- **DORA metrics** — Deployment Frequency, Lead Time, Change Failure Rate, Recovery Time.
- **E2E test** — end-to-end black-box test of the whole system.
- **Environment** — a full running instance (dev/staging/prod) with its own config.
- **Ephemeral runner** — a fresh, discarded-after-use worker; prevents state leakage.
- **Executor (Jenkins)** — a concurrency slot on a node.
- **Fat/uber JAR** — a JAR containing the app plus all dependencies.
- **Feature flag** — runtime toggle decoupling deploy from release.
- **Flaky test** — a test that passes/fails nondeterministically without code change.
- **GitOps** — desired environment state lives in Git; a controller reconciles the cluster to it.
- **Git** — distributed version control system; commits identified by content hashes.
- **Hermetic build** — a build sealed from the host, using only declared inputs.
- **Immutability (artifact)** — once published, bytes never change.
- **Incremental build** — rebuild/retest only what changed.
- **Integration test** — tests multiple components together, often with real dependencies.
- **JaCoCo** — Java code-coverage tool.
- **JAR / WAR** — Java/Web ARchive packaging formats.
- **Jib** — daemonless, reproducible JVM container image builder (Maven/Gradle plugin).
- **JVM** — Java Virtual Machine; runs bytecode.
- **Lead Time for Changes** — time from commit to running in prod.
- **Lockfile** — a file pinning exact dependency versions for reproducibility.
- **Mainline / trunk** — the shared integration branch (`main`).
- **Matrix build** — running a job across a grid of parameters (JDK × OS).
- **Maven** — declarative XML-based JVM build tool with a fixed lifecycle.
- **Monorepo** — one repository holding many projects/services.
- **MTTR / Recovery Time** — mean time to restore service after a failed change.
- **Pipeline-as-code** — pipeline defined in version-controlled files in the repo.
- **Promotion** — moving the same artifact up the environment ladder behind gates.
- **Pull Request (PR) / Merge Request (MR)** — proposal to merge a branch, with checks.
- **Reproducible build** — same inputs → byte-identical output.
- **Rolling update** — incrementally replace old instances with new ones.
- **Runner** — a worker that executes pipeline jobs (GitHub/GitLab term).
- **SAST** — Static Application Security Testing (scans code).
- **SBOM** — Software Bill of Materials; inventory of components in an artifact.
- **SCA** — Software Composition Analysis (scans dependencies for known CVEs).
- **Shallow clone** — clone with limited history (`--depth`); faster but can break version derivation.
- **SLSA** — Supply-chain Levels for Software Artifacts; a provenance/integrity framework.
- **Smoke test** — quick post-deploy liveness check.
- **SNAPSHOT (Maven)** — a mutable, in-development version; unfit for promotion.
- **Static analysis** — inspecting code without running it.
- **Tag (Git)** — named pointer to a commit, usually a release marker.
- **Tag (image)** — a mutable human-readable label on a container image.
- **Test pyramid** — many fast unit tests, fewer slow E2E tests.
- **Testcontainers** — library that spins up throwaway Docker containers for tests.
- **Trunk-based development** — short-lived branches, integrate to mainline ≥ daily.
- **Unit test** — isolated test of one class/method with mocked collaborators.
- **VCS** — Version Control System.
- **Workspace** — the runner's working directory for a job.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **CI** = merge often + auto build/test. **CD** = Delivery (deployable, manual gate) **or** Deployment (auto to prod, no gate).
- **Canonical stages:** checkout → build → unit test → static/security scan → **package (immutable id)** → integration test → deploy → smoke test → promote.
- **Core invariant:** **build once, deploy many** — promote the *same artifact by digest*, inject config at runtime.
- **Mutable = danger:** never promote `:latest` or `-SNAPSHOT`; pin by **digest** and SHA.
- **Speed targets:** PR build < 5–10 min. Levers: cache deps → incremental/build cache → parallelize/shard → affected-tests only → fail fast → fix flakes.
- **Reproducible/hermetic builds** enable *correct* caching (inputs fully determine outputs). Bazel = hermetic monorepo gold standard. Jib = reproducible JVM images.
- **Cache rule:** key must capture *every* output-affecting input; under-keying = stale wrong outputs.
- **Pipeline-as-code:** `.github/workflows/*.yml` / `.gitlab-ci.yml` / `Jenkinsfile` — versioned, reviewed, reusable.
- **Security:** least-privilege tokens; pin actions to SHA; SCA+SAST+secret scan; SBOM + cosign; ephemeral runners; OIDC over static secrets.
- **Deploy strategies:** recreate / rolling (default) / blue-green (instant rollback) / canary (metric-gated). Feature flags decouple deploy from release.
- **Health metrics:** the four DORA — Deployment Frequency, Lead Time, Change Failure Rate, Recovery Time.
- **Migrations:** backward-compatible (expand/contract) during rolling updates.
- **Top anti-patterns:** per-env rebuild, mutable-tag promotion, baked-in config, tolerated flakes, no job timeout, broad tokens, long-lived branches.

### Self-test (no answers — recall practice)

1. A teammate says "we'll just rebuild the image when we deploy to prod so it's fresh." Explain precisely what's wrong and what to do instead, with the technical mechanism for promotion.
2. Your remote build cache occasionally serves outputs that don't match the source. Name the most likely root cause and how you'd confirm and fix it.
3. Design the PR pipeline vs. the main-branch pipeline for a JVM microservice, listing which stages run where and why, and where the manual gate (if any) lives.
4. Distinguish reproducible, hermetic, and deterministic builds, and explain why hermeticity is what makes a content-addressed cache *correct*.
5. A floating third-party CI action was compromised and dumped your secrets. List four concrete controls that would have prevented or contained this.
6. You must roll out a non-null column add without breaking a rolling deploy. Walk through the expand/contract sequence step by step.
7. Pick a deployment strategy for a high-traffic payments API and justify it against two alternatives, naming what observability/automation it requires.

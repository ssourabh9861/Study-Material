# GitOps

> An exhaustive engineering-handbook chapter on GitOps for senior backend developers. Built from first principles up to deep internals, operations, debugging, and interview-grade mastery. Java/JVM-flavored where language matters, but GitOps is fundamentally a Kubernetes/infra discipline, so most examples are YAML, CLI, and controller internals.

---

## 1. Overview & where it fits

### What it is

**GitOps** is an operational model for managing infrastructure and application deployments in which **a Git repository is the single source of truth for the desired state of a system**, and **an automated controller (an "operator") continuously reconciles the actual running state of the system toward that desired state**.

Strip it to four claims and you have the whole idea:

1. **Declarative.** The entire desired state of the system (which apps, which versions, how many replicas, what config) is expressed declaratively — as data, not as scripts. In Kubernetes, this is YAML manifests.
2. **Versioned and immutable.** That declarative state lives in Git. Git gives you history, diffs, signed commits, pull-request review, blame, and the ability to roll back to any prior state.
3. **Pulled automatically.** Approved changes are *pulled* and applied automatically by a controller running *inside* the target environment, not *pushed* in from an external CI server.
4. **Continuously reconciled.** Software agents continuously observe actual state, compare it to desired state, and act to converge the two — detecting and (optionally) correcting **drift**.

> **Term — declarative vs imperative.** *Imperative* code says *how*: "run `kubectl scale deploy/web --replicas=5`". *Declarative* config says *what*: "the `web` Deployment has `replicas: 5`". Declarative systems let a controller figure out the steps to get there, and re-derive them whenever reality diverges. GitOps is built on declarative config because you can store a *target state* in Git and let a machine compute the diff repeatedly.

> **Term — reconciliation / control loop.** A *control loop* (borrowed from control theory and the heart of Kubernetes itself) is a never-ending loop: *observe actual state → compare to desired state → take action to reduce the difference → repeat*. A thermostat is the canonical example. A GitOps controller is a control loop whose "desired state" input is a Git repo.

### The problem it solves

Before GitOps, the dominant deployment model was **push-based CI/CD**: a CI server (Jenkins, GitLab CI, GitHub Actions, CircleCI) builds an artifact, then *reaches into* the production cluster and runs deploy commands (`kubectl apply`, `helm upgrade`, `terraform apply`). This has several chronic problems:

- **Credential sprawl.** The CI system needs powerful, long-lived, cluster-admin-equivalent credentials to *every* environment it deploys to. Those credentials live outside the cluster, in a system that runs arbitrary untrusted code (your build scripts, your dependencies). Compromise the CI runner → compromise every cluster.
- **No single source of truth for *running* state.** What is *actually* deployed in prod right now? In push-CI you reconstruct it by reading CI logs, deployment history, and hoping nobody ran a manual `kubectl edit`. Configuration **drift** accumulates silently.
- **Drift goes undetected and uncorrected.** Someone hotfixes prod by hand at 2 a.m. Nothing records it; nothing reverts it. The next deploy may or may not clobber it. Reality and intent diverge.
- **Rollback is bespoke.** "Roll back" means "re-run the old pipeline" or "manually apply the old manifests" — error-prone and slow.
- **Auditability is weak.** Who changed what, when, why, and who approved it? Scattered across CI logs and Slack.

GitOps reframes all of this: **the desired state of every environment is a file in Git**, changes go through pull requests (review + approval + history), and an in-cluster agent enforces that state continuously. The cluster pulls; CI never needs prod credentials.

### When you reach for it

- You run **Kubernetes** (GitOps's natural habitat — though it generalizes to anything declarative: Terraform, Crossplane, even firewall configs).
- You want **auditable, reviewable, revertible** infrastructure and deployment changes.
- You run **many environments or many clusters** and need a consistent, scalable way to manage them.
- You want to **shrink the blast radius of CI** by removing prod credentials from your build system.
- You want **drift detection and self-healing** as a first-class feature.

### When you might not

- You have a tiny single environment and a one-line `kubectl apply` in CI is genuinely sufficient — GitOps adds a controller, a repo discipline, and operational surface area.
- Your workloads are **not declarative** (heavily imperative provisioning, stateful migrations that don't fit a reconcile model).
- You need **interactive, human-in-the-loop, step-by-step orchestration** that doesn't map to "converge to this end state" (some complex data migrations).

### One-paragraph mental model

> Think of GitOps as a **thermostat for your infrastructure**. Git holds the *set temperature* (desired state). A controller inside the building (the cluster) constantly reads the *actual temperature* (live state) and runs the furnace or AC (applies/deletes/updates Kubernetes resources) to close the gap. You never walk into the building to adjust things by hand — you change the thermostat setting (open a PR, merge it), and the building converges on its own. If a draft (manual change) cools a room, the thermostat notices and corrects it. To undo a change, you set the thermostat back to its old value (`git revert`).

---

## 2. Foundations from first principles

We'll build the conceptual ladder rung by rung. If you already know Kubernetes deeply, skim — but the framing matters.

### 2.1 The substrate: declarative infrastructure

GitOps presupposes a system that is **declaratively managed** and has its own **reconciliation engine**. Kubernetes is the archetype, so let's ground the terms.

> **Term — Kubernetes (k8s).** An open-source container orchestration platform. You declare *objects* (Pods, Deployments, Services, ConfigMaps, etc.) via YAML or JSON, submit them to the **API server**, and a set of **controllers** drive the cluster toward that declared state. It is itself a giant control loop.

> **Term — Kubernetes object / resource.** A persistent record of intent stored in the cluster's database (etcd). Each object has a `spec` (your desired state) and, once running, a `status` (the actual state, written by controllers). Example: a `Deployment` spec says "5 replicas of image `web:1.2`"; its status says "5 available."

> **Term — the API server & etcd.** The **API server** (`kube-apiserver`) is the single front door to the cluster — every read/write of every object goes through it. **etcd** is the distributed, consistent key-value store behind it that durably holds all object state. (etcd uses the **Raft** consensus algorithm — a protocol for getting a cluster of machines to agree on an ordered log of changes even if some fail; that's how etcd stays consistent across replicas.)

> **Term — controller / operator.** A controller is a program running a control loop for some resource type. An **operator** is a controller that encodes domain-specific operational knowledge (e.g., "how to safely upgrade a Postgres cluster"). A GitOps tool (Argo CD, Flux) is essentially an operator whose desired-state source is Git.

> **Term — Custom Resource Definition (CRD) and Custom Resource (CR).** Kubernetes lets you *extend* its API with your own object types. A **CRD** registers a new kind (e.g., `Application`, `Kustomization`, `HelmRelease`). A **CR** is an instance of it. GitOps tools define CRDs like `Application` (Argo CD) and `Kustomization` (Flux) — these CRs *are* the GitOps configuration objects living in the cluster.

### 2.2 The packaging layer: how desired state is expressed

Raw YAML is verbose and not reusable across environments. Three approaches dominate, and every GitOps tool supports them:

> **Term — Kustomize.** A template-free way to customize YAML. You have a `base/` directory of manifests and `overlays/` (e.g., `dev/`, `prod/`) that *patch* the base — change replica counts, image tags, add labels — without copying it. Built into `kubectl` (`kubectl apply -k`). It works by *layering JSON/strategic-merge patches* over base objects.

> **Term — Helm.** A package manager for Kubernetes. A **chart** is a bundle of templated manifests (Go templates) plus a `values.yaml` of parameters. `helm install`/`upgrade` renders the templates with your values and applies the result. A **release** is an installed instance of a chart. GitOps tools can render Helm charts and apply the output.

> **Term — Jsonnet / CUE / others.** Data-templating languages used for more programmatic config generation. Argo CD natively supports Jsonnet; both tools support plugins for anything else.

### 2.3 Desired state vs live state vs target state

Three states matter, and confusing them is the #1 source of GitOps confusion:

- **Desired state (a.k.a. source state):** what's in Git, *after* it has been rendered (Kustomize built, Helm templated). This is your intent.
- **Live state (a.k.a. actual/observed state):** what currently exists in the cluster, as reported by the Kubernetes API.
- **Target state:** what the controller *computes it should apply* — usually equal to desired state, but it can differ during phased syncs, with ignore-rules, or when fields are managed by other controllers.

**Sync status** = comparison of desired vs live. If they match: `Synced`. If they differ: `OutOfSync`. The difference is **drift**.

> **Term — drift.** Any divergence between desired state (Git) and live state (cluster). Two causes: (1) **Git changed** (you merged a new manifest) → the cluster is "behind"; (2) **the cluster changed out-of-band** (someone ran `kubectl edit`, or another controller mutated a field) → the cluster has "drifted." GitOps controllers detect both; auto-heal corrects the second kind.

### 2.4 The reconciliation loop, conceptually

The core algorithm every GitOps controller runs:

```
loop forever:
    desired  = render(fetch_from_git(repo, revision, path))   # build manifests
    live     = read_from_cluster(api_server)                  # observe
    diff     = three_way_diff(desired, live, last_applied)    # compute drift
    if diff is empty:
        status = Synced
    else:
        status = OutOfSync
        if auto_sync_enabled:
            apply(diff)            # converge: create/update/delete
    health = assess_health(live)  # are the resources actually healthy?
    sleep(reconcile_interval) or wait_for_webhook_event
```

> **Term — three-way merge / diff.** Naively comparing desired vs live produces false drift, because Kubernetes and other controllers add fields you didn't specify (defaults, status, allocated IPs, HPA-managed replica counts). A **three-way diff** compares **desired**, **live**, and the **last-applied configuration** (what *this* tool applied last time) to determine which differences *this controller is responsible for*. Fields it never set are left alone. This is the same idea as `kubectl apply`'s three-way strategic merge — and getting it right is subtle (see §7 on `managedFields` and Server-Side Apply).

### 2.5 Push vs pull deployment

This is the philosophical core of GitOps. Understand it cold.

**Push-based (traditional CI/CD):**
```
[Git] --merge--> [CI runner] --(holds cluster creds)--> kubectl apply --> [Cluster]
```
The deployment is *initiated from outside* the cluster. The external system needs inbound credentials to the cluster.

**Pull-based (GitOps):**
```
[Git] <--polls/watches-- [In-cluster controller] --applies--> [Cluster (same one it runs in)]
```
The deployment is *initiated from inside* the cluster. The controller *pulls* state from Git on its own schedule and applies it to its own cluster. Git needs only a *read-only* deploy key; the cluster needs no inbound admin access from CI.

**Why pull is more secure (the canonical argument):**

| Concern | Push | Pull |
|---|---|---|
| Where do prod credentials live? | In the CI system (runs untrusted build code) | Inside the cluster; never leave it |
| Network direction | CI must reach *into* the cluster's API (often exposed) | Cluster reaches *out* to Git (egress only); API can be private |
| Blast radius of CI compromise | All clusters CI can deploy to | None — CI has no cluster creds |
| Git credentials needed | Read (to fetch source) | Read-only deploy key (to fetch source) |
| Image registry creds | In CI | In cluster (only to pull images) |
| Audit of *what's running* | Reconstruct from CI logs | The Git revision the controller has applied = ground truth |

The crisp version: **with pull, your most powerful credentials (cluster admin) never leave the trust boundary of the cluster, and the cluster's API server need not be reachable from your build infrastructure or the internet.**

> **Caveat — pull isn't automatically more secure.** The controller still needs cluster-admin-level RBAC inside the cluster, and read access to Git. If the controller itself, its Git credentials, or the Git repo are compromised, you have a problem. Pull *relocates* and *narrows* the trust boundary; it doesn't eliminate it. (See §6 Security.)

### 2.6 The principles, formalized

The **OpenGitOps** project (a CNCF effort) codified four principles. Memorize them:

1. **Declarative** — the system's desired state is expressed declaratively.
2. **Versioned and immutable** — desired state is stored in a way that enforces immutability and versioning and retains a complete version history (Git).
3. **Pulled automatically** — software agents automatically pull the desired state from the source.
4. **Continuously reconciled** — software agents continuously observe actual state and attempt to apply the desired state.

> **Term — CNCF.** The Cloud Native Computing Foundation, the open-source foundation (part of the Linux Foundation) that hosts Kubernetes, Argo, Flux, Prometheus, etc. "CNCF graduated" is a maturity signal: both Argo and Flux are CNCF graduated projects.

---

## 3. How it works internally

This is the heart of the chapter. We'll trace the full lifecycle, then go component-by-component for both major tools.

### 3.1 The end-to-end flow (tool-agnostic)

Picture a typical "ship a new version" flow:

1. **Developer commits app code** to the application source repo. CI builds, tests, and produces a **container image** tagged with an immutable identifier (ideally the Git SHA or a semver, e.g., `myapp:1.4.2` or `myapp@sha256:...`). CI pushes the image to a registry.
   - *Note:* CI does **not** deploy. Its job ends at "image pushed."
2. **The desired-state change is made in the config repo.** Either a human opens a PR bumping the image tag in a manifest, or an automated **image updater** (Argo CD Image Updater, Flux Image Automation) detects the new tag and commits the bump.
3. **Review & merge.** A PR against the config repo is reviewed and merged to the tracked branch (e.g., `main`). This merge is the *authorization* event — it's reviewed, attributable, and signed.
4. **The controller notices.** The in-cluster GitOps controller either (a) polls Git on its interval, or (b) receives a webhook from Git telling it "something changed." It fetches the new revision.
5. **Render.** The controller renders the manifests (runs Kustomize/Helm) for the relevant path/branch into a flat set of Kubernetes objects = desired state.
6. **Diff.** It performs a three-way diff against live state → marks resources `OutOfSync`.
7. **Sync.** If auto-sync is on (or a human clicks "Sync"), it applies the changes to the cluster (create/update/prune). It may order operations via **sync phases/waves/hooks**.
8. **Health assessment.** It watches the live objects and assesses health (is the Deployment's rollout complete? are Pods Ready?). Status becomes `Healthy`/`Progressing`/`Degraded`.
9. **Steady state.** The controller keeps reconciling on its interval. If anyone changes the live cluster out-of-band, drift is detected; if auto-heal is on, it's reverted.
10. **Rollback (if needed).** `git revert` the bad commit (or pin to a previous revision). The controller reconciles back to the old state. Optionally, progressive-delivery controllers auto-rollback on bad metrics *before* a human intervenes.

> **Term — webhook.** An HTTP callback. Git platforms (GitHub/GitLab/Bitbucket) can POST to a URL whenever a repo event (push, PR merge) happens. GitOps controllers expose a webhook receiver so they can react *immediately* instead of waiting for the next poll. Polling is the fallback (and the safety net if a webhook is missed).

### 3.2 Argo CD internals

**Argo CD** is a GitOps controller with a strong UI/API and an application-centric model. Originally from Intuit; CNCF graduated.

> **Mental model:** Argo CD watches `Application` CRs. Each `Application` says "render *this path* in *this Git repo* at *this revision*, and make it exist in *this cluster/namespace*." Argo continuously diffs and (optionally) syncs.

#### Components (each runs as a Pod in the `argocd` namespace by default)

| Component | Role |
|---|---|
| **API server** (`argocd-server`) | Serves the gRPC/REST API, the Web UI, the CLI backend, auth (SSO/RBAC), and webhook endpoints. Stateless. |
| **Repository server** (`argocd-repo-server`) | Clones Git repos, caches them, and **renders** manifests (runs Helm/Kustomize/Jsonnet/plugins). Holds Git/Helm creds. Returns rendered manifests to the controller. |
| **Application controller** (`argocd-application-controller`) | The reconciliation engine. Diffs desired (from repo-server) vs live (from cluster), applies syncs, runs health checks, auto-heal/auto-prune. The brain. |
| **Redis** | Cache (rendered manifests, cluster state). Not source of truth — purely a cache; safe to lose (rebuilt). |
| **ApplicationSet controller** | Generates many `Application`s from a template + generators (e.g., one per cluster, per directory, per PR). |
| **Notifications controller** | Sends alerts (Slack, email, webhooks) on sync/health events. |
| **Dex** (optional) | Federated identity / SSO connector (OIDC, SAML, LDAP). |

> **Term — gRPC.** A high-performance RPC (remote procedure call) framework over HTTP/2 with protobuf serialization. Argo CD's internal components talk gRPC; the CLI and UI use it too.

> **Term — RBAC (Role-Based Access Control).** Authorization model mapping *subjects* (users/groups) to *roles* (sets of allowed actions) on *objects*. Both Kubernetes and Argo CD have their own RBAC. Argo CD's RBAC governs who can sync/override which apps/projects.

#### The reconciliation cycle in Argo CD, step by step

1. The **application controller** maintains an informer-backed cache of live cluster state (it watches the cluster API, so it knows live state cheaply and quickly).
   > **Term — informer / watch.** Kubernetes lets clients open a long-lived *watch* on a resource type and receive a stream of change events. An **informer** is a client-side library that consumes a watch, maintains a local in-memory cache, and fires callbacks. This is how controllers avoid polling the API server for every object.
2. On its **reconciliation timer** (default app refresh ~**180 seconds**, configurable via `timeout.reconciliation`) *or* on a webhook event, the controller asks the **repo-server** to render the manifests for the app's `source` (repo + path + targetRevision).
3. Repo-server clones/fetches the repo (using cached clone), checks out the revision, runs the tool (Kustomize build / Helm template), and returns rendered manifests. Results are cached in Redis keyed by repo+revision+params.
4. The controller computes a **three-way diff** between desired (rendered), live (cache), and last-applied (stored in `metadata.annotations` `kubectl.kubernetes.io/last-applied-configuration` or via Server-Side Apply field ownership).
5. It sets `app.status.sync.status` = `Synced` or `OutOfSync`, and records per-resource diffs.
6. If `syncPolicy.automated` is set and the app is `OutOfSync`, it triggers a **sync operation**: it applies resources, honoring **sync waves**, **hooks**, and **prune** settings.
7. It assesses **health** of each resource using built-in or custom (Lua) health checks, rolling up to an app-level health status.
8. It writes status back to the `Application` object and emits events/notifications.

> **Term — Lua.** A small embeddable scripting language. Argo CD lets you write resource health checks and diff-customizations in Lua, so you can teach it how to assess health of CRDs it doesn't know natively.

#### Sync mechanics: waves, hooks, phases, prune, options

- **Sync phases:** `PreSync` → `Sync` → `PostSync` (plus `SyncFail` on failure). Each phase runs its resources/hooks to completion before the next.
- **Sync waves:** within a phase, resources are ordered by the annotation `argocd.argoproj.io/sync-wave: "<int>"` (default `0`); lower waves apply first. Argo waits for a wave's resources to be healthy before the next wave (with a delay, default ~2s between waves controlled by `ARGOCD_SYNC_WAVE_DELAY`). Use for ordering: CRDs before CRs, DB before app, etc.
- **Hooks:** any manifest annotated `argocd.argoproj.io/hook: PreSync|Sync|PostSync|SyncFail` runs at that phase (commonly a `Job` for DB migrations). Hook deletion governed by `argocd.argoproj.io/hook-delete-policy` (`HookSucceeded`, `HookFailed`, `BeforeHookCreation`).
- **Prune:** if a resource exists live but not in Git, Argo can delete it (`prune: true`). Off by default for safety; with auto-sync you opt in via `syncPolicy.automated.prune: true`. `PruneLast` defers pruning to after other resources sync.
- **Self-heal:** `syncPolicy.automated.selfHeal: true` makes Argo re-sync whenever live drifts from desired (not just when Git changes) — this is auto-heal.
- **Sync options** (`syncOptions`): `CreateNamespace=true`, `ApplyOutOfSyncOnly=true`, `ServerSideApply=true`, `Validate=false`, `PrunePropagationPolicy=foreground`, `RespectIgnoreDifferences=true`, etc.

#### Drift detection & auto-heal in Argo CD

- **Detection:** the diff in step 4 above runs on every reconciliation and on webhook. Out-of-band changes show as `OutOfSync` with a visible diff in the UI.
- **Auto-heal:** with `selfHeal: true`, the controller re-applies desired state, overwriting the drift. There's a self-heal back-off (default starts at 5s, exponential, capped) to avoid hot loops when something keeps re-mutating a field.
- **Ignoring expected drift:** `spec.ignoreDifferences` lets you tell Argo to ignore certain fields (e.g., `spec.replicas` if an HPA manages them, or webhook-injected fields) using JSON pointers or `jqPathExpressions`. Critical to avoid perpetual false drift.

> **Term — HPA (Horizontal Pod Autoscaler).** A Kubernetes controller that adjusts a Deployment's replica count based on metrics (CPU, custom). It *writes* `spec.replicas`, which fights GitOps if Git also specifies replicas. Solution: omit `replicas` from Git, or `ignoreDifferences` on that field.

#### ApplicationSet

`ApplicationSet` is a CRD + controller that templates `Application`s. **Generators** produce parameters; the template instantiates one app per parameter set:
- **List generator** — static list of clusters/values.
- **Cluster generator** — one app per registered cluster (great for multi-cluster).
- **Git generator** — one app per directory or per matching file in a repo (great for "app of many").
- **Matrix / Merge** — combine generators (e.g., per-cluster × per-app).
- **Pull Request generator** — one app per open PR (ephemeral preview environments).
- **SCM Provider generator** — discover repos/branches from GitHub/GitLab org.

> **Term — app of apps.** A pattern (predating ApplicationSet) where one Argo CD `Application` points at a directory of *other* `Application` manifests, so syncing the parent creates/manages children. ApplicationSet is the more powerful, templated successor for most use cases.

### 3.3 Flux internals

**Flux** (Flux CD, "Flux v2" / the GitOps Toolkit) is a set of composable controllers. Originally from Weaveworks; CNCF graduated. Philosophy: small, single-responsibility controllers wired together via CRDs; no built-in UI (use `flux` CLI, plus optional UIs like Weave GUI/Capacitor).

> **Term — GitOps Toolkit (GOTK).** Flux's set of controllers and APIs you can compose: source, kustomize, helm, notification, image-reflector, image-automation controllers.

#### Components (controllers), each watching its own CRDs

| Controller | CRDs it owns | Role |
|---|---|---|
| **source-controller** | `GitRepository`, `OCIRepository`, `HelmRepository`, `Bucket` | Fetches & caches *sources* (Git, OCI artifacts, Helm repos, S3 buckets). Verifies signatures/checksums. Exposes artifacts to other controllers. |
| **kustomize-controller** | `Kustomization` | Builds Kustomize/plain YAML from a source and applies it (Server-Side Apply), prunes, health-checks. The main reconciler. |
| **helm-controller** | `HelmRelease` | Renders & manages Helm releases declaratively. |
| **notification-controller** | `Provider`, `Alert`, `Receiver` | Inbound webhooks (`Receiver`) to trigger reconciles; outbound alerts (`Alert`/`Provider`). |
| **image-reflector-controller** | `ImageRepository`, `ImagePolicy` | Scans container registries; computes "latest matching" tag per policy. |
| **image-automation-controller** | `ImageUpdateAutomation` | Writes new image tags back into Git (commits the bump) — closing the loop. |

> **Term — OCI artifact / OCIRepository.** OCI (Open Container Initiative) registries can store not just container images but arbitrary artifacts. Flux can package your manifests as an OCI artifact and treat the registry as the source of truth instead of Git directly — useful for signed, immutable, content-addressed config distribution.

#### The Flux reconciliation flow, step by step

1. **source-controller** reconciles a `GitRepository` (or `OCIRepository`): clones/fetches at its `interval` (e.g., `1m`), checks out `ref` (branch/tag/semver/commit), optionally verifies a **GPG/cosign signature**, and produces an **artifact** (a tarball of the checked-out tree) stored locally and advertised via the CR's `status.artifact.url` + a content checksum.
   > **Term — GPG / cosign signature verification.** GPG signs Git commits/tags; **cosign** (from the Sigstore project) signs OCI artifacts/images. Flux can refuse to reconcile a source whose signature doesn't match a trusted key — preventing tampered config from being deployed.
2. **kustomize-controller** reconciles a `Kustomization` that references that source. On its `interval` (or when notified), it: fetches the artifact, runs `kustomize build` on `spec.path`, performs **variable substitution** (`postBuild.substitute`/`substituteFrom`), then **Server-Side Applies** the result. It records an inventory of applied objects in the CR's status.
3. **Pruning (garbage collection):** because kustomize-controller tracks an **inventory** of what it applied, when an object disappears from the source it can prune it (`spec.prune: true`).
4. **Health checks:** `spec.healthChecks` or `wait: true` makes the controller block until referenced objects (or all applied objects) become Ready, using `kstatus`.
   > **Term — kstatus.** A library (from kubernetes-sigs) that computes a standardized "Ready/Progressing/Failed" status for arbitrary Kubernetes objects by inspecting their `status.conditions`. Flux uses it for health.
5. **Drift correction:** Flux's apply is *continuous*; on every interval it re-applies desired state via Server-Side Apply, so out-of-band drift on fields it owns is corrected by default. (`spec.force` and field-management settings tune this.)
6. **helm-controller**: a `HelmRelease` references a chart (from a `HelmRepository`, `GitRepository`, or `OCIRepository`); the controller renders and installs/upgrades it, with declarative `valuesFrom`, remediation (`install.remediation`, `upgrade.remediation` with `retries` and `remediateLastFailure`), and rollback on failure.
7. **notification-controller**: a `Receiver` exposes a webhook URL; when Git pushes, the platform hits it, and the controller annotates the relevant source to trigger an *immediate* reconcile (instead of waiting for the interval).

> **Term — Server-Side Apply (SSA).** A Kubernetes apply mode where the **API server** (not the client) performs the merge and tracks *field ownership* via `metadata.managedFields`. Each field records which "manager" set it. This makes multi-controller coexistence and drift detection precise. Flux uses SSA by default; Argo CD supports it via `ServerSideApply=true`. (More in §7.)

#### Flux multi-tenancy & structure

Flux composes naturally: a top-level `Kustomization` can point at a directory of more `Kustomization`s (the Flux analog of app-of-apps), enabling per-team, per-cluster, per-env trees with RBAC enforced via `spec.serviceAccountName` (the controller impersonates a tenant's ServiceAccount, so a tenant can't apply outside its allowed RBAC).

### 3.4 State machine (the lifecycle of a managed application)

Combining sync status and health status (Argo CD vocabulary, but conceptually universal):

**Sync status:** `Unknown → OutOfSync ⇄ Synced` (`Unknown` while it can't compute, e.g., repo unreachable).

**Health status:** `Unknown / Missing / Progressing / Healthy / Degraded / Suspended`.

A normal happy path: merge → `OutOfSync` → (auto)sync → `Progressing` (rollout underway) → `Healthy` + `Synced`. A bad deploy: → `Synced` (Git applied) + `Degraded` (Pods crashlooping) → alert → `git revert` → back to `Healthy`. Drift: → `Synced`→ someone edits live → `OutOfSync` → self-heal → `Synced`.

---

## 4. The complete toolkit

### 4.1 Argo CD — key CRDs and fields

**`Application` (the core object):**

| Field | Purpose | Notes / default |
|---|---|---|
| `spec.project` | Which `AppProject` (RBAC + guardrails) it belongs to | `default` if unset |
| `spec.source.repoURL` | Git (or Helm/OCI) repo URL | required |
| `spec.source.path` | Directory within repo to render | required for dir/Kustomize |
| `spec.source.targetRevision` | Branch, tag, commit, or Helm version (`HEAD`, `main`, `v1.2.3`, `1.2.*`) | required |
| `spec.source.helm` / `.kustomize` / `.directory` / `.plugin` | Tool-specific render config (values, image overrides, recurse) | — |
| `spec.sources[]` | Multiple sources (multi-source apps) | newer feature |
| `spec.destination.server` / `.name` | Target cluster (API URL or registered name) | `https://kubernetes.default.svc` = in-cluster |
| `spec.destination.namespace` | Target namespace | — |
| `spec.syncPolicy.automated.prune` | Delete resources removed from Git | `false` |
| `spec.syncPolicy.automated.selfHeal` | Correct out-of-band drift | `false` |
| `spec.syncPolicy.automated.allowEmpty` | Allow syncing to zero resources | `false` |
| `spec.syncPolicy.syncOptions[]` | `CreateNamespace=true`, `ServerSideApply=true`, `ApplyOutOfSyncOnly=true`, `PruneLast=true`, etc. | — |
| `spec.syncPolicy.retry` | Backoff for failed syncs (`limit`, `backoff.duration/factor/maxDuration`) | — |
| `spec.ignoreDifferences[]` | Fields to ignore in diff (`jsonPointers`, `jqPathExpressions`, `managedFieldsManagers`) | — |
| `spec.revisionHistoryLimit` | How many synced revisions to keep for rollback | `10` |

**`AppProject`:** scopes what an app may do — allowed `sourceRepos`, allowed `destinations` (cluster+namespace), allowed cluster-scoped/namespaced resource kinds (`clusterResourceWhitelist`/`namespaceResourceBlacklist`), and project-level RBAC roles & sync windows.

**`ApplicationSet`:** `spec.generators[]` + `spec.template` (an `Application` template with `{{...}}` parameters) + `spec.strategy` (rollout strategy across generated apps, e.g., `RollingSync`).

**Argo CD CLI (`argocd`) — common commands:**

| Command | Purpose |
|---|---|
| `argocd login <server>` | Authenticate the CLI |
| `argocd app create ...` | Create an Application |
| `argocd app list` | List apps with sync/health |
| `argocd app get <app>` | Detailed status, resource tree |
| `argocd app diff <app>` | Show desired vs live diff |
| `argocd app sync <app> [--prune] [--dry-run]` | Trigger a sync |
| `argocd app rollback <app> <history-id>` | Roll back to a previous synced revision |
| `argocd app history <app>` | List sync history (revision IDs) |
| `argocd app set <app> --revision <rev>` | Pin target revision |
| `argocd app wait <app> --health --sync` | Block until healthy/synced (great in scripts) |
| `argocd app delete <app> [--cascade]` | Delete app (and optionally resources) |
| `argocd repo add ...` / `argocd cluster add ...` | Register repos/clusters |
| `argocd admin ...` | Backup/restore/settings/notifications |

**Key Argo CD config (`argocd-cm` / `argocd-cmd-params-cm` ConfigMaps, env vars):**

| Setting | Effect | Default |
|---|---|---|
| `timeout.reconciliation` | App refresh interval | `180s` |
| `application.resourceTrackingMethod` | How Argo marks ownership: `annotation`, `label`, `annotation+label` | `annotation` |
| `ARGOCD_SYNC_WAVE_DELAY` | Delay between sync waves | `2s` |
| `resource.exclusions` / `.inclusions` | Limit which kinds Argo watches (perf) | — |
| `reposerver.parallelism.limit` | Concurrent manifest renders | — |
| `controller.repo.server.timeout.seconds` | Repo-server call timeout | — |
| `controller.status.processors` / `.operation.processors` | Reconcile concurrency | tuned for scale |

### 4.2 Flux — key CRDs and fields

**`GitRepository` (source):**

| Field | Purpose | Notes |
|---|---|---|
| `spec.url` | Git URL (https/ssh) | required |
| `spec.ref.branch` / `.tag` / `.semver` / `.commit` / `.name` | What to check out | one of |
| `spec.interval` | How often to fetch | e.g., `1m` |
| `spec.secretRef` | Credentials (basic auth / SSH key) | — |
| `spec.verify` | Verify commit/tag signature (`mode`, `secretRef` of GPG keys) | — |
| `spec.ignore` | `.sourceignore`-style excludes | — |

**`Kustomization` (apply):**

| Field | Purpose | Notes |
|---|---|---|
| `spec.sourceRef` | Which source (GitRepository/OCIRepository/Bucket) | required |
| `spec.path` | Dir to `kustomize build` | `./` |
| `spec.interval` | Reconcile interval | e.g., `10m` |
| `spec.prune` | Garbage-collect removed objects | `false` (set `true` in prod) |
| `spec.wait` / `spec.healthChecks` | Wait for readiness | — |
| `spec.targetNamespace` | Override namespace | — |
| `spec.dependsOn[]` | Order this after other Kustomizations | — |
| `spec.serviceAccountName` | Impersonate this SA (multi-tenancy RBAC) | — |
| `spec.postBuild.substitute(From)` | Variable substitution into manifests | — |
| `spec.force` | Recreate immutable resources on conflict | `false` |
| `spec.decryption` | SOPS decryption provider + key secret | — |
| `spec.timeout` | Apply/health timeout | — |
| `spec.retryInterval` | Retry interval on failure | — |

**`HelmRelease`:** `spec.chart` (chartRef/source, version), `spec.values`/`valuesFrom`, `spec.interval`, `spec.install`/`spec.upgrade` (with `remediation`), `spec.rollback`, `spec.test`, `spec.dependsOn`.

**Flux CLI (`flux`):**

| Command | Purpose |
|---|---|
| `flux bootstrap github/gitlab/git ...` | Install Flux *and* commit its own manifests to your repo (the controller manages itself) |
| `flux create source git <name> --url ... --branch ...` | Create a GitRepository |
| `flux create kustomization <name> --source ... --path ... --prune` | Create a Kustomization |
| `flux create helmrelease ...` | Create a HelmRelease |
| `flux get sources git` / `flux get kustomizations` | Status of sources/kustomizations |
| `flux reconcile source git <name>` / `flux reconcile kustomization <name> [--with-source]` | Force an immediate reconcile |
| `flux suspend / resume kustomization <name>` | Pause/unpause reconciliation |
| `flux diff kustomization <name> --path ./...` | Local dry-run diff |
| `flux trace <kind>/<name>` | Trace a live object back to its source/Kustomization |
| `flux logs` | Tail controller logs |
| `flux check` | Verify install/prereqs |

### 4.3 Secrets toolkit (cross-cutting)

| Tool | Model | What lives in Git | Decryption happens | Notes |
|---|---|---|---|---|
| **Sealed Secrets** (Bitnami) | Asymmetric encryption to a controller's public key | A `SealedSecret` CR (ciphertext) | In-cluster `sealed-secrets-controller` decrypts → creates a real `Secret` | Per-cluster key; can only be decrypted by that cluster's controller |
| **SOPS** (Mozilla/CNCF) | Encrypt values in YAML/JSON with KMS/age/PGP | Encrypted YAML (only values, keys readable) | At reconcile time by the GitOps controller (Flux native; Argo via plugin/ksops) | Granular; supports AWS/GCP/Azure KMS, HashiCorp Vault, age |
| **External Secrets Operator (ESO)** | Reference, don't store | An `ExternalSecret` CR pointing at a remote store | ESO fetches from Vault/AWS Secrets Manager/etc., creates a `Secret` | Secrets never in Git at all; central rotation |
| **Vault Agent / CSI / Sidecar** | Inject at runtime | Nothing (just references) | At Pod startup | Tightest, most operationally heavy |

(Details and examples in §5.5 and §6.)

---

## 5. Code examples by use case

### 5.1 Repo layout for multi-env with Kustomize (the foundation)

```
config-repo/
├── apps/
│   └── payments-api/
│       ├── base/
│       │   ├── deployment.yaml
│       │   ├── service.yaml
│       │   └── kustomization.yaml          # lists base resources
│       └── overlays/
│           ├── dev/
│           │   ├── kustomization.yaml       # patches base: 1 replica, dev image
│           │   └── replicas-patch.yaml
│           ├── staging/
│           │   └── kustomization.yaml
│           └── prod/
│               ├── kustomization.yaml       # 6 replicas, prod image, resource limits
│               └── replicas-patch.yaml
└── clusters/                                # which apps run where (the "root")
    ├── dev/
    ├── staging/
    └── prod/
```

`apps/payments-api/base/kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - deployment.yaml
  - service.yaml
images:
  - name: payments-api          # logical name referenced in deployment.yaml
    newName: registry.example.com/payments-api
    newTag: "0.0.0"             # placeholder; overlays override per env
```

`apps/payments-api/overlays/prod/kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: payments
resources:
  - ../../base
patches:
  - path: replicas-patch.yaml     # bumps replicas to 6 for prod
images:
  - name: payments-api
    newTag: "1.4.2"               # <-- THIS line is what a release PR bumps
commonLabels:
  env: prod
```

`replicas-patch.yaml` (strategic merge):
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-api
spec:
  replicas: 6
```

> Why this matters: a production release is now a **one-line diff** (`newTag: "1.4.2"`) in a single reviewable file. That diff *is* the release. `git revert` of it *is* the rollback.

### 5.2 Argo CD `Application` for the prod overlay

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: payments-api-prod
  namespace: argocd                     # Argo CD's namespace
  finalizers:
    - resources-finalizer.argocd.argoproj.io   # ensures cascade delete cleans up
spec:
  project: payments                     # AppProject scoping what this app may do
  source:
    repoURL: https://github.com/acme/config-repo.git
    targetRevision: main                # track main; could pin to a tag/SHA
    path: apps/payments-api/overlays/prod
  destination:
    server: https://kubernetes.default.svc   # in-cluster (same cluster as Argo)
    namespace: payments
  syncPolicy:
    automated:
      prune: true                       # delete resources removed from Git
      selfHeal: true                    # revert out-of-band drift (auto-heal)
      allowEmpty: false
    syncOptions:
      - CreateNamespace=true            # create 'payments' ns if missing
      - ServerSideApply=true            # use SSA for precise field ownership
      - PruneLast=true                  # prune after applying others (safer order)
    retry:
      limit: 5
      backoff:
        duration: 10s
        factor: 2
        maxDuration: 5m
  ignoreDifferences:
    - group: apps
      kind: Deployment
      jqPathExpressions:
        - .spec.replicas               # ignore replicas if an HPA owns them
  revisionHistoryLimit: 20             # keep 20 revisions for argocd app rollback
```

### 5.3 ApplicationSet: one app per cluster (multi-cluster)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: payments-api-all-clusters
  namespace: argocd
spec:
  generators:
    - clusters:                          # generates params from registered clusters
        selector:
          matchLabels:
            tier: production             # only clusters labeled tier=production
  template:
    metadata:
      name: 'payments-{{name}}'          # {{name}} = cluster name from generator
    spec:
      project: payments
      source:
        repoURL: https://github.com/acme/config-repo.git
        targetRevision: main
        path: 'apps/payments-api/overlays/{{metadata.labels.region}}'  # per-region overlay
      destination:
        server: '{{server}}'             # target each cluster's API server
        namespace: payments
      syncPolicy:
        automated: { prune: true, selfHeal: true }
        syncOptions: [CreateNamespace=true]
```

> One object now manages payments-api across *every* production cluster. Register a new cluster with the right labels → it's deployed automatically. This is the multi-cluster scale story.

### 5.4 Flux equivalent: GitRepository + Kustomization

```yaml
---
apiVersion: source.toolkit.fluxcd.io/v1
kind: GitRepository
metadata:
  name: config-repo
  namespace: flux-system
spec:
  interval: 1m                           # poll Git every minute (webhook can override)
  url: https://github.com/acme/config-repo.git
  ref:
    branch: main
  secretRef:
    name: config-repo-auth               # read-only deploy token/SSH key
---
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: payments-api-prod
  namespace: flux-system
spec:
  interval: 10m                          # re-apply / reconcile every 10m
  retryInterval: 1m
  sourceRef:
    kind: GitRepository
    name: config-repo
  path: ./apps/payments-api/overlays/prod
  prune: true                            # garbage-collect removed objects
  wait: true                             # block until applied objects are Ready
  timeout: 5m
  targetNamespace: payments
  serviceAccountName: payments-deployer  # impersonate tenant SA (multi-tenancy)
```

### 5.5 Secrets — three approaches, concretely

**(a) Sealed Secrets.** Encrypt a normal Secret to the cluster's controller; commit the ciphertext.

```bash
# 1. Create a normal secret locally (NEVER commit this raw file)
kubectl create secret generic db-creds \
  --from-literal=password='s3cr3t' \
  --dry-run=client -o yaml > db-creds.yaml

# 2. Seal it with the controller's public cert (safe to commit the output)
kubeseal --cert pub-cert.pem --format yaml < db-creds.yaml > sealed-db-creds.yaml
# sealed-db-creds.yaml contains only ciphertext; commit THIS to Git.
```
```yaml
# sealed-db-creds.yaml (committed to Git)
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: db-creds
  namespace: payments
spec:
  encryptedData:
    password: AgBy3i4OJSWK+PiTySYZZA9rO43cGDEq...   # opaque ciphertext
```
> The in-cluster `sealed-secrets-controller` watches `SealedSecret` CRs, decrypts with its private key, and creates the real `Secret`. Only *that* cluster can decrypt. Caveat: the private key is cluster-bound — back it up, or you can't recover sealed secrets after a cluster rebuild.

**(b) SOPS + age (Flux-native decryption).**

```bash
# Encrypt only the data values; keys stay readable for diffs/review
sops --age age1xxxxxxxxxxxx... --encrypt --in-place secret.yaml
```
```yaml
# Flux Kustomization decrypts at reconcile time
spec:
  decryption:
    provider: sops
    secretRef:
      name: sops-age           # holds the age private key as 'age.agekey'
```
> Flux's kustomize-controller decrypts SOPS-encrypted files before applying. The private key lives in the cluster as a Secret. SOPS shines because diffs remain reviewable (only values are ciphertext) and it supports cloud KMS so no key material sits in the cluster at all.

**(c) External Secrets Operator — no secret in Git, ever.**

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: db-creds
  namespace: payments
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secrets-manager     # a SecretStore/ClusterSecretStore CR
    kind: ClusterSecretStore
  target:
    name: db-creds                # the k8s Secret ESO will create
  data:
    - secretKey: password
      remoteRef:
        key: prod/payments/db
        property: password
```
> ESO pulls from AWS Secrets Manager (or Vault, GCP, Azure) and materializes a `Secret`. Git holds only a *reference*. Rotation happens in the external store; ESO refreshes on `refreshInterval`. Best when you already have a secrets manager and want central rotation/audit.

### 5.6 DB migration as a PreSync hook (Argo CD)

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: payments-db-migrate
  annotations:
    argocd.argoproj.io/hook: PreSync                 # run before the main sync
    argocd.argoproj.io/hook-delete-policy: HookSucceeded  # clean up if it works
spec:
  backoffLimit: 1
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: migrate
          image: registry.example.com/payments-api:1.4.2  # same version as the app
          command: ["java", "-jar", "/app/app.jar", "db", "migrate"]  # Flyway/Liquibase
```
> This guarantees migrations run (and succeed) *before* new Pods roll out. If the migration fails, the sync fails and the new version never deploys. A `SyncFail` hook could alert or roll back.

### 5.7 Automated image bump (closing the loop with Flux)

```yaml
---
apiVersion: image.toolkit.fluxcd.io/v1beta2
kind: ImageRepository
metadata: { name: payments-api, namespace: flux-system }
spec:
  image: registry.example.com/payments-api
  interval: 5m                                 # scan registry every 5m
---
apiVersion: image.toolkit.fluxcd.io/v1beta2
kind: ImagePolicy
metadata: { name: payments-api, namespace: flux-system }
spec:
  imageRepositoryRef: { name: payments-api }
  policy:
    semver: { range: '>=1.4.0 <2.0.0' }        # auto-adopt 1.x patches/minors
---
apiVersion: image.toolkit.fluxcd.io/v1beta1
kind: ImageUpdateAutomation
metadata: { name: payments-api, namespace: flux-system }
spec:
  interval: 5m
  sourceRef: { kind: GitRepository, name: config-repo }
  git:
    commit:
      author: { name: fluxbot, email: flux@acme.com }
      messageTemplate: 'chore: bump payments-api to {{range .Updated.Images}}{{.}}{{end}}'
    push: { branch: main }
  update: { path: ./apps/payments-api/overlays/prod, strategy: Setters }
```
And in the deployment manifest, a marker tells Flux which field to update:
```yaml
        image: registry.example.com/payments-api:1.4.2 # {"$imagepolicy": "flux-system:payments-api"}
```
> Now a newly pushed `1.4.3` image is detected, and Flux *commits* the tag bump to Git itself, then reconciles. The Git history still records every deploy — the loop stays auditable even when automated.

### 5.8 Progressive delivery with Argo Rollouts + analysis

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata: { name: payments-api, namespace: payments }
spec:
  replicas: 6
  strategy:
    canary:
      steps:
        - setWeight: 10            # send 10% of traffic to new version
        - pause: { duration: 2m }  # bake
        - analysis:                # automated metric check
            templates: [{ templateName: success-rate }]
        - setWeight: 50
        - pause: { duration: 5m }
        - setWeight: 100
  selector: { matchLabels: { app: payments-api } }
  template:
    metadata: { labels: { app: payments-api } }
    spec:
      containers:
        - name: app
          image: registry.example.com/payments-api:1.4.2
---
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata: { name: success-rate, namespace: payments }
spec:
  metrics:
    - name: success-rate
      interval: 1m
      successCondition: result[0] >= 0.99     # >=99% success or roll back
      failureLimit: 2
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(http_requests_total{app="payments-api",code!~"5.."}[2m]))
            /
            sum(rate(http_requests_total{app="payments-api"}[2m]))
```
> The `Rollout` is just another manifest in Git, so GitOps still owns the *intent*; Argo Rollouts owns the *progression*. If the Prometheus success rate dips below 99%, the rollout auto-aborts and shifts traffic back to the old version — automated rollback *before* a human even sees it. (Flux uses **Flagger** for the equivalent.)

---

## 6. Implementation concerns & best practices

### 6.1 Repository structure

- **Separate app-source repos from config repos.** Code lives in app repos; *desired cluster state* lives in config repo(s). This keeps the deploy trigger (a config diff) clean and lets CI never touch the cluster.
- **Mono-repo vs poly-repo for config.** Mono-repo (one config repo, directories per env/cluster) is simpler to reason about and gives atomic cross-app changes; poly-repo (one per team/app) gives tighter RBAC and blast-radius isolation. Most start mono and split when team count and PR contention grow.
- **Environment promotion = a Git operation, not a separate pipeline.** Promote dev→staging→prod by copying/moving an image tag between overlays (a PR), or by branch/tag promotion. Avoid "rebuild per environment" — promote the *same immutable image*.
- **Never use mutable tags** (`latest`, `staging`) as the deployed tag. They make "what's actually running" ambiguous and break rollback. Use immutable tags (SHA or semver) or, better, digests.

### 6.2 Correctness & concurrency

- **Beware perpetual drift.** Fields written by *other* controllers (HPA → `replicas`, cloud LB → `status`, admission webhooks injecting sidecars/labels) cause endless `OutOfSync`. Fix with `ignoreDifferences` (Argo) or SSA field-ownership + appropriate exclusions (Flux). Don't disable self-heal globally to mask this — narrow the ignore.
- **Sync ordering matters.** CRDs must exist before CRs; namespaces before namespaced resources; DB before app. Use **sync waves/phases** (Argo) or **`dependsOn`** (Flux). Forgetting this causes flapping `Progressing`/`Degraded` on first apply.
- **Pruning is dangerous on first enable.** Misconfigured path/labels + `prune: true` can delete production. Enable prune deliberately, with `PruneLast`, and watch the first sync. Use `--dry-run`/`flux diff` first.
- **One controller per cluster owning a given set of objects.** Two GitOps controllers (or a GitOps controller and a manual `kubectl apply` job) fighting over the same objects = thrash. Define clear ownership.

### 6.3 Security (the GitOps-specific surface)

- **Least-privilege controller RBAC.** Argo's app-controller / Flux's controllers run with broad RBAC by default. In multi-tenant clusters, scope per-tenant via `AppProject` (Argo) or `serviceAccountName` impersonation (Flux).
- **Read-only Git credentials.** The controller only needs to *read* the config repo. Use a deploy key / fine-grained token with read scope. (Image-automation needs write, scope it narrowly.)
- **Protect the config repo as production.** Branch protection, required reviews, **signed commits**, and (Flux) **signature verification** so the controller refuses unsigned/tampered revisions. A merge to `main` *is* a production change — gate it like one.
- **Secrets: never plaintext in Git.** Use Sealed Secrets / SOPS / ESO (§4.3, §5.5). Scan for accidental commits (gitleaks, trufflehog) in CI.
- **Lock down the controller's webhook endpoint** (validate the shared secret / HMAC signature) so attackers can't trigger reconciles or DoS the repo-server.
- **Network egress, not ingress.** Pull model means the cluster's API server need not be internet-reachable; only egress to Git/registry is required. Take advantage of this — keep the API private.
- **Drift = security signal.** Unexpected `OutOfSync` can mean an intruder (or insider) mutated the cluster. Alert on drift, not just on sync failures.

> **Term — HMAC.** Hash-based Message Authentication Code: a way to verify a message's integrity and authenticity using a shared secret. Git webhooks sign payloads with HMAC; the receiver recomputes it to confirm the payload really came from the platform.

### 6.4 Observability

- **Expose controller metrics.** Both tools export Prometheus metrics: app/kustomization sync status, reconcile durations, error counts, drift counts. Alert on: apps `OutOfSync` for >N minutes, apps `Degraded`, reconcile errors, repo-server failures.
- **Notifications.** Argo CD Notifications / Flux `Alert` → Slack/PagerDuty on sync failure, health degraded, or successful deploy (deploy-to-Slack closes the human loop).
- **Trace running state to source.** `flux trace deploy/payments-api` or Argo's UI resource tree answers "why is this object here / which commit put it here." Invaluable in incidents.
- **Distributed tracing of the pipeline** is hard (commit→reconcile→rollout); rely on annotations: Argo records the synced revision on the app; correlate via commit SHA.

### 6.5 Cost & performance at scale

- **Repo-server is the bottleneck** at scale (rendering thousands of apps). Tune `reposerver.parallelism.limit`, scale replicas, cache aggressively, and reduce render cost (don't re-template huge Helm charts unnecessarily).
- **App controller sharding.** For many clusters/apps, Argo CD supports sharding the application-controller across replicas (`--replicas` + sharding by cluster). Flux scales by running more controller replicas and by `interval` tuning.
- **Reconcile interval is a cost/freshness knob.** Short intervals = faster drift correction but more API/Git load. Use **webhooks** for freshness and keep the *polling* interval long (e.g., 1–5m as a safety net), not aggressive.
- **`resource.exclusions`** in Argo to stop watching noisy/irrelevant kinds (e.g., huge numbers of Events, Endpoints) cuts memory/CPU substantially.

### 6.6 Testing

- **PR-time validation in CI:** `kustomize build` / `helm template` must succeed; lint with `kubeconform`/`kubeval` (schema validation), `conftest`/OPA (policy), `flux diff`/`argocd app diff` against a target.
   > **Term — OPA / Conftest / Kyverno.** Policy engines. **OPA** (Open Policy Agent) with **Conftest** evaluates Rego policies against config in CI (e.g., "every Deployment must set resource limits"). **Kyverno** does the same as an in-cluster admission controller. Use them as a guardrail so bad manifests can't merge or apply.
- **Preview environments** via ApplicationSet PR generator: each PR gets an ephemeral namespace/app, torn down on merge/close.
- **Dry-run before enabling prune.** Always.

### 6.7 Production hardening checklist

- Auto-sync + self-heal on in prod (so manual drift can't persist) — but only after ordering & ignore-rules are correct.
- `revisionHistoryLimit` high enough for meaningful rollback window.
- DR: back up Argo CD's `Application`/`AppProject`/cluster secrets and the Sealed Secrets private key; for Flux, the repo *is* most of the backup, plus controller secrets.
- Pin the GitOps tool versions in Git (Flux manages itself via `flux bootstrap`; Argo via its own manifests) — the tool upgrade is itself a GitOps change.
- Separate "fleet" repo (cluster bootstrap: the GitOps tool, ingress, CNI, observability) from "apps" repos.

### 6.8 Anti-patterns

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| `kubectl apply` by humans in GitOps clusters | Creates drift; fights self-heal | Make Git the only write path; restrict RBAC |
| Mutable image tags (`latest`) | Ambiguous running state; rollback broken | Immutable tags/digests |
| Secrets in plaintext in Git | Catastrophic leak | Sealed Secrets/SOPS/ESO |
| Disabling self-heal to silence drift | Hides real config problems | Use `ignoreDifferences` narrowly |
| One giant `Application` for everything | Slow diffs, huge blast radius, no granular RBAC | Split per app/team; ApplicationSet |
| CI that both builds *and* `kubectl apply`s | Reintroduces push-model credential sprawl | CI builds; GitOps deploys |
| Editing live objects to "test something" in prod | Drift, surprise rollback | Use a dev/preview env |
| Rendering with `latest` chart versions | Non-reproducible deploys | Pin chart versions |

---

## 7. Advanced topics & deep internals

### 7.1 Three-way diff and `managedFields` (the precise mechanics)

The hard problem: deciding whether a difference between Git and the cluster is *real drift this tool should fix* or *legitimately owned by someone else*.

- **Client-Side Apply (legacy):** the tool stores its last applied manifest in the annotation `kubectl.kubernetes.io/last-applied-configuration`. The three-way merge = (last-applied, live, desired). A field present in live but not in last-applied and not in desired is assumed *not ours* → left alone. Fragile when multiple actors edit overlapping fields.
- **Server-Side Apply (SSA):** the **API server** tracks per-field ownership in `metadata.managedFields` — each entry says "manager X owns these fields as of this apply." When the GitOps controller (manager `argocd-controller` / `kustomize-controller`) applies, the server merges only the fields *it* manages, and **conflict-detects** if another manager owns a field the controller is trying to set. This is far more precise: an HPA owning `replicas` and a GitOps controller owning the rest can coexist cleanly. Argo: `ServerSideApply=true`; Flux: SSA by default.
  - **Field conflicts**: with SSA, if two managers claim a field, you get a conflict; `force` resolves it by taking ownership. Misuse of `force` causes the very thrash you were avoiding.

> **Term — `metadata.managedFields`.** A structured record on every Kubernetes object listing which manager set which fields, and when. It's noisy in `kubectl get -o yaml` (and itself a common `ignoreDifferences` target). It's the backbone of SSA's correctness.

### 7.2 Diff normalization & known-noise

Real clusters inject noise the controller must normalize away or it reports false drift:
- Defaulted fields (e.g., `protocol: TCP` on ports, `imagePullPolicy`).
- Order-insensitive lists treated as ordered.
- Webhook/sidecar injection (Istio, Linkerd, Vault Agent) adding containers/volumes.
- Numeric/string representation differences.
Both tools ship normalizers; Argo lets you add custom diff logic via `ignoreDifferences` (with `jqPathExpressions`/`managedFieldsManagers`) and Lua-based diff customizations. Getting these right is most of the operational tuning effort in mature setups.

### 7.3 Sync waves, hooks, and ordering edge cases

- Negative waves (e.g., `-1`) run first — common for CRDs/namespaces.
- A wave doesn't proceed until prior-wave resources are **healthy** (per health checks), so a missing/incorrect health check for a CRD can stall a sync forever (`Progressing`). Custom Lua health checks fix unknown CRDs.
- Hooks vs waves: hooks are *separate* lifecycle objects (often Jobs) tied to phases; waves order the *regular* resources. They compose.

### 7.4 Multi-source & multi-cluster topologies

- **Hub-and-spoke (Argo):** one central Argo CD ("hub") with credentials to many "spoke" clusters; `Application.destination.server` points at each. Pro: single pane of glass; con: hub holds many cluster creds (mitigate with project scoping, network isolation).
- **Standalone per-cluster (Flux's natural mode):** each cluster runs its own controllers reconciling its own path in the repo. Pro: no central cred store, blast-radius isolation; con: no single dashboard out of the box.
- **Repo structure for fleets:** common pattern is a `clusters/<cluster-name>/` tree where each cluster's entrypoint Kustomization/Application lists the apps for *that* cluster, referencing shared `apps/` and `infrastructure/` bases. Differences between clusters are overlays, not forks.

### 7.5 Progressive delivery internals

- **Argo Rollouts** replaces the `Deployment` with a `Rollout` resource and manages ReplicaSets directly to implement canary/blue-green, optionally driving a service mesh / ingress for traffic splitting, and running `AnalysisRun`s against metric providers (Prometheus, Datadog, CloudWatch, etc.). On failure it aborts and reverts traffic.
- **Flagger** (Flux ecosystem) keeps your normal `Deployment` and creates a shadow/primary pair, automating canary analysis via the mesh/ingress and metrics, with automatic promotion/rollback.
  > **Term — blue-green / canary.** *Blue-green*: run two full environments (old=blue, new=green), flip traffic all at once, keep blue for instant rollback. *Canary*: shift a small % of traffic to the new version, watch metrics, ramp up gradually, roll back if metrics degrade.
- GitOps + progressive delivery division of labor: **Git owns the target version and the rollout strategy spec; the rollout controller owns the in-flight progression and the automated rollback decision.** The merge is still the only thing a human authorizes.

### 7.6 OCI as source of truth

Instead of Git, Flux (and Argo, partially) can pull config from an **OCI registry**: CI packages manifests into an OCI artifact, signs it with **cosign**, and pushes it; the controller pulls and verifies the signature before applying. Benefits: content-addressed immutability, decoupling deploy from Git availability, and strong supply-chain guarantees. The Git repo can still be the authoring surface; the OCI artifact is the distribution surface.

### 7.7 Reconcile timing, backoff, and thundering herds

- Webhooks trigger near-instant reconciles; the polling `interval` is a safety net. Setting all apps to a 30s interval in a 2,000-app instance can hammer Git and the API server (a thundering herd). Stagger via jitter (built in) and rely on webhooks for freshness.
- Self-heal backoff prevents hot loops when a mutating webhook keeps re-adding a field every time the controller removes it — you'll see oscillation in metrics; the fix is an `ignoreDifferences` rule, not a faster interval.

### 7.8 Drift on cluster-scoped & immutable fields

- Immutable fields (e.g., a Service's `clusterIP`, a Job's template, certain StatefulSet fields) can't be updated in place. A diff on them forces *recreate*; Argo flags `RequiresPruning`/replace, Flux uses `spec.force` to recreate. Misconfiguring this can cause downtime (recreating a Service changes its IP). Know which fields are immutable.

---

## 8. Tradeoffs & decision frameworks

### 8.1 GitOps vs push-based CI/CD

| Dimension | GitOps (pull) | Push CI/CD |
|---|---|---|
| Prod credentials location | In-cluster only | In CI (high risk) |
| Source of truth for running state | Git revision (authoritative) | Reconstructed from logs |
| Drift detection/correction | Built-in, continuous | None / manual |
| Rollback | `git revert` (declarative) | Re-run old pipeline (bespoke) |
| Auditability | Git history + reviews | Scattered |
| Setup complexity | Higher (controller + repo discipline) | Lower initially |
| Imperative/interactive flows | Awkward | Natural |
| Best for | Many envs/clusters, k8s, compliance | Tiny single env, non-declarative steps |

**Use GitOps when** you run Kubernetes, want auditable/revertible state, manage many environments, or need to remove prod creds from CI. **Avoid/defer when** you have one trivial environment, heavily imperative provisioning, or the team can't yet support a controller's operational surface.

### 8.2 Argo CD vs Flux

| Dimension | Argo CD | Flux |
|---|---|---|
| Architecture | Few components, app-centric, monolith-ish | Many small composable controllers (GOTK) |
| UI | Rich first-party Web UI | CLI-first; UIs are third-party (Capacitor/Weave) |
| Core object | `Application` | `Kustomization` / `HelmRelease` |
| Multi-cluster | Hub-and-spoke (central) | Per-cluster standalone (decentralized) natural |
| Templating multi-app | `ApplicationSet` (powerful generators) | Composed `Kustomization`s, OCI |
| RBAC/multi-tenancy | `AppProject`, Argo RBAC, SSO (Dex) | SA impersonation, native k8s RBAC |
| Image automation | Argo CD Image Updater (separate) | Built-in image-reflector/automation controllers |
| Secrets decryption | Plugins (KSOPS), or external tools | Native SOPS decryption |
| Progressive delivery | Argo Rollouts (same ecosystem) | Flagger |
| Apply mode | CSA or SSA (opt-in) | SSA by default |
| Self-management | Manifests in Git | `flux bootstrap` writes its own manifests to Git |
| Feel | Platform/dashboard-driven | Unix-philosophy, k8s-native, scriptable |

**Pick Argo CD when** you want a strong UI, central multi-cluster dashboard, fine-grained Argo RBAC for many teams, and the Rollouts ecosystem. **Pick Flux when** you favor composable k8s-native controllers, decentralized per-cluster ownership, native SOPS, OCI sources, and CLI/automation-first workflows. Both are CNCF graduated and production-proven; many orgs succeed with either. (You can even run both for different purposes.)

### 8.3 Secrets approach decision

| If you… | Use |
|---|---|
| Want zero external dependencies, secrets bound to a cluster | **Sealed Secrets** |
| Want reviewable diffs, cloud KMS, granular value encryption | **SOPS** (Flux-native) |
| Already have Vault/AWS/GCP secret managers; want central rotation | **External Secrets Operator** |
| Need runtime injection, no Secret object at all, tightest control | **Vault Agent / CSI** |

---

## 9. Failure modes & debugging

### 9.1 App stuck `OutOfSync` forever

- **Cause:** perpetual drift — a field owned by another controller (HPA replicas, mutating webhook sidecar, cloud LB status) keeps differing.
- **Diagnose:** `argocd app diff <app>` / `flux diff kustomization <name>` to see *which* field differs every cycle. Check `managedFields` to see who owns it.
- **Fix:** add `ignoreDifferences` (Argo) on that JSON path / `managedFieldsManagers`; or in Flux rely on SSA ownership and exclude. Don't disable self-heal globally.

### 9.2 App `Synced` but `Degraded`

- **Cause:** manifests applied fine, but Pods crashloop / readiness fails / image pull error. Git is "correct"; runtime is broken.
- **Diagnose:** `argocd app get <app>` resource tree → drill to the Pod → `kubectl describe pod` / `kubectl logs`. Common: bad image tag, missing secret/config, OOMKilled, failed migration.
- **Fix:** `git revert` the bad change; controller reconciles to last-good. If a hook migration failed, inspect the hook Job logs.

### 9.3 Sync stuck `Progressing` and never completes

- **Cause:** a sync wave waiting on a resource that never becomes healthy — often a CRD with no/incorrect health check, a Job that never finishes, or a PVC that can't bind.
- **Diagnose:** identify the wave/resource blocking; check health assessment. For unknown CRDs, Argo reports `Unknown` health → wave stalls.
- **Fix:** add a custom Lua health check (Argo) for the CRD; or remove the bad wave dependency; fix the underlying resource (storage, quota).

### 9.4 Prune deleted something it shouldn't have

- **Cause:** path/label change made objects "disappear" from desired state with `prune: true` → controller deleted them.
- **Diagnose:** controller events + Git history show what was pruned; cluster audit log shows the deletes.
- **Fix:** revert the config change; restore from backup if stateful. **Prevent:** dry-run before enabling prune; use `PruneLast`; alert on prune events; protect critical resources with `Prune=false` annotation (`argocd.argoproj.io/sync-options: Prune=false`).

### 9.5 Repo-server / source fetch failing

- **Cause:** Git auth expired/rotated, repo URL wrong, Helm repo down, render error (bad template/values), OOM rendering a huge chart.
- **Diagnose:** repo-server logs (Argo) / `flux get sources git` + source-controller logs. Watch for "authentication required", "manifest generation error", repo-server OOM.
- **Fix:** rotate creds; fix manifests (catch in CI with `kustomize build`/`helm template`); raise repo-server memory; pin chart versions.

### 9.6 Drift caused by a human / intruder

- **Cause:** out-of-band `kubectl edit`/`apply` on a self-heal-disabled app, or an attacker mutating workloads.
- **Diagnose:** app flips `OutOfSync`; the diff shows the unauthorized change; cluster audit log identifies the actor.
- **Fix:** self-heal reverts it (if enabled); investigate the actor; tighten RBAC so only the controller can write. **This is why alerting on drift is a security control.**

### 9.7 Webhook not firing → stale deploys

- **Cause:** webhook misconfigured/secret mismatch, controller endpoint unreachable.
- **Diagnose:** Git platform's webhook delivery logs show non-200s; controller still eventually reconciles via polling (so symptom is "deploys are slow, not instant").
- **Fix:** verify webhook URL + HMAC secret; ensure the polling interval is a sane safety net regardless.

### 9.8 Real-world incident shapes (composite, illustrative)

- **The mass-prune outage:** a refactor moved manifests to a new directory; the `Application.path` still pointed at the old (now empty) path, with `prune: true`. The controller computed "desired = nothing" and pruned a namespace of production workloads. Lesson: guard prune; alert on large delete sets; consider `allowEmpty: false`.
- **The HPA tug-of-war:** Git pinned `replicas: 3`, an HPA scaled to 12 under load, self-heal yanked it back to 3 every cycle → repeated under-provisioning and oscillation visible in metrics. Lesson: remove `replicas` from Git or `ignoreDifferences` it.
- **The lost sealing key:** a cluster was rebuilt from scratch; the Sealed Secrets controller got a *new* keypair; every committed `SealedSecret` became undecryptable. Lesson: back up the sealing private key (or use SOPS/ESO with externally-held keys).
- **The unsigned-config slip:** an attacker with repo write (compromised CI token used for image-automation) pushed a malicious manifest; signature verification wasn't enforced, so Flux deployed it. Lesson: require signed commits + `spec.verify`; scope automation tokens tightly.

---

## 10. Interview drill

**Q1. What is GitOps, in one breath?**
*Model answer:* An operating model where the desired state of a system is stored declaratively in Git (single source of truth), and an in-cluster controller continuously pulls that state and reconciles the live system to match it — giving you versioned, reviewable, auditable, self-healing, and easily revertible infrastructure.
- *Probe: Name the four OpenGitOps principles.* Declarative; versioned & immutable; pulled automatically; continuously reconciled.
- *Probe: Is GitOps only for Kubernetes?* No — k8s is the natural fit, but the model applies to anything declarative with a reconciler (Terraform, Crossplane). The principles are infrastructure-agnostic.

**Q2. Why is pull-based deployment considered more secure than push?**
*Model answer:* In pull, the controller runs *inside* the cluster and reaches *out* to Git, so cluster-admin credentials never leave the cluster's trust boundary and the API server needn't be reachable from CI or the internet. In push, the CI system (which runs untrusted build code) holds powerful inbound credentials to every cluster — compromise CI → compromise prod. Pull narrows and relocates the trust boundary.
- *Probe: Does pull eliminate all credential risk?* No — the controller still has cluster-admin RBAC and read access to Git; compromise of the controller, its Git creds, or the repo is still dangerous. Pull mitigates, not eliminates.
- *Probe: What new attack surface does pull add?* The controller, its webhook endpoint, and write-scoped automation tokens (image updater). Harden the webhook (HMAC), scope tokens, sign commits.

**Q3. Walk me through what happens, step by step, from a developer pushing code to the new version running in prod.**
*Model answer:* (See §3.1.) CI builds & pushes an immutable image; a PR (or image-updater commit) bumps the image tag in the config repo; review & merge; controller is notified (webhook) or polls; repo-server renders manifests; controller three-way-diffs desired vs live → `OutOfSync`; auto-sync applies in waves/phases (migrations as PreSync hook); health assessed; steady-state reconcile continues; drift auto-healed.
- *Probe: Where does CI stop and GitOps begin?* CI ends at "image pushed"; GitOps owns everything from the config-repo merge onward. CI never has cluster creds.
- *Probe: How are DB migrations sequenced safely?* As a PreSync hook Job that must succeed before the app rollout proceeds; failure fails the sync.

**Q4. How do Argo CD and Flux detect and correct drift?**
*Model answer:* Both run a reconcile loop comparing rendered desired state to live state (Argo via informer cache + repo-server render + three-way/SSA diff; Flux via source artifact + `kustomize build` + Server-Side Apply). Divergence → `OutOfSync`. Argo corrects it when `selfHeal: true`; Flux re-applies via SSA every interval by default, so owned fields are continuously enforced.
- *Probe: How do you stop false drift from an HPA?* Omit `replicas` from Git or `ignoreDifferences` that field; with SSA, let the HPA own `replicas` and the controller own the rest.
- *Probe: Client-side vs server-side apply for diffs?* CSA uses the last-applied annotation for a three-way merge (fragile with multiple writers); SSA tracks per-field ownership in `managedFields` on the API server (precise, conflict-aware).

**Q5. How do you handle secrets in GitOps?**
*Model answer:* Never plaintext in Git. Three idioms: Sealed Secrets (commit ciphertext encrypted to a cluster controller's public key; only that cluster decrypts), SOPS (encrypt values with KMS/age; controller decrypts at reconcile — Flux-native, reviewable diffs), and External Secrets Operator (Git holds only a reference; ESO syncs from Vault/AWS/etc. → real Secret). Choose by your existing infra and rotation needs.
- *Probe: What's the operational risk with Sealed Secrets?* The sealing private key is cluster-bound; lose it (e.g., cluster rebuild) and all sealed secrets are unrecoverable — back it up.
- *Probe: Which keeps secrets entirely out of Git?* ESO and runtime injection (Vault Agent/CSI) — Git holds only references.

**Q6. How does rollback work, and why is it better than push-CI rollback?**
*Model answer:* Rollback is `git revert` of the offending commit (or pinning `targetRevision` to a prior SHA/tag); the controller reconciles the cluster back to that exact prior desired state. It's better because it's *declarative, atomic, reviewed, and recorded* in Git history — not a bespoke "re-run the old pipeline" with whatever its current side effects are. Argo also offers `argocd app rollback <history-id>` for an out-of-band quick revert.
- *Probe: What about stateful changes (DB schema)?* Code/config rollback is easy; data/schema rollback isn't — design backward-compatible migrations (expand/contract) so reverting the app doesn't break the DB.
- *Probe: Can rollback be automated before a human acts?* Yes — progressive delivery (Argo Rollouts/Flagger) auto-aborts a canary on bad metrics and shifts traffic back, before anyone touches Git.

**Q7 (senior-signal). When would you NOT use GitOps, and how do you justify that?**
*Model answer:* When the operational and cognitive overhead outweighs the benefit: a single trivial environment where a one-line CI apply suffices; heavily imperative/interactive provisioning that doesn't map to "converge to end state"; teams without the maturity to run another stateful controller and enforce repo discipline. Justify on blast-radius, audit needs, environment count, and team capacity — GitOps pays off as those grow, and is overhead when they're tiny. The decision is about *fit to operational reality*, not ideology.
- *Probe: Could you adopt GitOps incrementally?* Yes — start with one non-critical app, read-only/observe mode (sync disabled), then enable manual sync, then auto-sync + self-heal as confidence grows.

**Q8 (senior-signal). Argo CD vs Flux — how do you choose for a 40-team, 30-cluster org?**
*Model answer:* Both work; the deciding factors are governance model and UX. Argo's hub-and-spoke + rich UI + `AppProject`/SSO RBAC + ApplicationSet generators give a central control plane and strong per-team guardrails — attractive for a large org wanting one pane of glass and tight RBAC, at the cost of a central component holding many cluster creds. Flux's decentralized per-cluster controllers + native SOPS + OCI + SA-impersonation multi-tenancy give blast-radius isolation and a k8s-native, scriptable model, at the cost of no first-party central dashboard. For 30 clusters I'd weigh whether central visibility (Argo) or decentralized isolation (Flux) matters more, and how teams self-serve. Many large orgs run Argo for the dashboard/UX; I'd prototype both against the actual team workflow before committing.
- *Probe: How do you scale Argo CD to thousands of apps?* Shard the application-controller across replicas (by cluster), scale repo-server + tune `reposerver.parallelism.limit`, use `resource.exclusions`, lean on webhooks over short polling, and cache renders.

**Q9 (senior-signal). Your team complains GitOps "fights them" — they make a fix in prod and it disappears. How do you respond?**
*Model answer:* That's self-heal working as designed: the live cluster drifted from Git, and the controller restored intent. The "fix" wasn't recorded, reviewed, or persisted — exactly the failure mode GitOps prevents. The right response is process, not disabling self-heal: make Git the only write path (restrict human RBAC), provide fast PR/merge tooling and preview environments so the *correct* path is also the *fast* path, and alert on drift as a signal. If there's a legitimate emergency-edit need, define a break-glass procedure that still records the change and reconciles it back into Git afterward.
- *Probe: What about fields legitimately owned by other controllers?* Use `ignoreDifferences`/SSA field ownership so the controller doesn't fight them — that's not the same as disabling self-heal.

**Q10. What are sync waves and hooks, and when do you need them?**
*Model answer:* Hooks (Argo) are lifecycle objects (usually Jobs) that run at `PreSync`/`Sync`/`PostSync`/`SyncFail` phases — e.g., a PreSync DB migration. Sync waves order the *regular* resources within a phase via an annotation (lower wave first), waiting for each wave to be healthy before the next — e.g., CRDs (wave -1) before CRs, namespace before its resources, DB before app. You need them whenever apply order matters; without them you get flapping `Progressing`/`Degraded` on first apply. Flux's analog is `dependsOn`.
- *Probe: Why might a sync get stuck in a wave?* The wave's resource never reports Healthy (e.g., an unknown CRD whose health is `Unknown`, or a Job that never completes). Fix with a custom health check or by fixing the resource.

**Q11. How does GitOps integrate with progressive delivery?**
*Model answer:* Git owns the *target version and the rollout strategy* (a `Rollout`/Flagger `Canary` manifest); the rollout controller owns the *in-flight progression* and the *automated rollback decision* based on metric analysis (Prometheus/Datadog). The human still only authorizes the merge; the canary ramps and auto-aborts on bad metrics. So GitOps provides the declarative intent + audit, and progressive delivery provides safe, metric-gated rollout on top.
- *Probe: Argo Rollouts vs Flagger?* Rollouts replaces the Deployment with a `Rollout` CR managing ReplicaSets directly (Argo ecosystem); Flagger wraps a normal Deployment with primary/canary and drives the mesh/ingress (Flux ecosystem). Both do canary/blue-green with metric analysis and auto-rollback.

**Q12. What's the single source of truth in GitOps, and what subtlety does that hide?**
*Model answer:* Git holds the source of truth for *desired* state. The subtlety: it is *not* the source of truth for *live* state — the cluster is. GitOps's value is continuously reconciling the two and surfacing the gap (drift). Also, "Git" really means "the rendered output of Git at a revision" — Kustomize/Helm sit between the files and the applied objects, so reproducibility depends on pinning chart/template inputs.
- *Probe: Then what is authoritative about *what's running*?* The synced revision recorded by the controller plus the live cluster state; in a healthy `Synced`+`Healthy` app they agree, which is the whole point.

---

## 11. Glossary

- **Argo CD** — App-centric GitOps controller with a rich UI; reconciles `Application` CRs. CNCF graduated.
- **ApplicationSet** — Argo CD controller that templates many `Application`s from generators (cluster, git, list, matrix, PR).
- **AppProject** — Argo CD object scoping what an app may do (repos, destinations, resource kinds, RBAC).
- **Auto-heal / self-heal** — Controller reverting out-of-band drift back to Git's desired state.
- **Blue-green** — Two full environments; flip traffic at once; instant rollback by flipping back.
- **Canary** — Gradually shift a % of traffic to the new version while watching metrics.
- **CNCF** — Cloud Native Computing Foundation, hosts Kubernetes/Argo/Flux/etc.
- **Control loop / reconciliation** — Observe→compare→act loop converging actual to desired state.
- **cosign / Sigstore** — Tooling to sign and verify OCI artifacts/images.
- **CRD / CR** — Custom Resource Definition (a new API type) / Custom Resource (an instance).
- **Declarative** — Describing the *what* (target state) rather than the *how* (steps).
- **Drift** — Divergence between desired (Git) and live (cluster) state.
- **etcd** — Distributed consistent key-value store backing the Kubernetes API; uses Raft.
- **External Secrets Operator (ESO)** — Syncs secrets from external stores into k8s Secrets; Git holds only references.
- **Flagger** — Progressive-delivery controller (Flux ecosystem) for canary/blue-green with metric analysis.
- **Flux / GitOps Toolkit** — Composable set of GitOps controllers (source/kustomize/helm/notification/image). CNCF graduated.
- **gRPC** — High-performance RPC over HTTP/2 with protobuf.
- **Helm / chart / release** — Kubernetes package manager; templated manifests; an installed instance.
- **HMAC** — Keyed hash for verifying message integrity/authenticity (used for webhook signing).
- **HPA** — Horizontal Pod Autoscaler; scales replicas by metrics; can conflict with GitOps over `replicas`.
- **Hook (sync hook)** — A resource (often a Job) run at a sync phase (PreSync/Sync/PostSync/SyncFail).
- **Informer / watch** — Client-side cache fed by a streaming watch on the API server.
- **Kubernetes** — Container orchestration platform built on declarative objects + controllers.
- **kstatus** — Library computing standardized Ready/Progressing/Failed status for objects.
- **Kustomize / base / overlay** — Template-free YAML customization via patch layering.
- **Lua** — Embeddable scripting language; used for Argo CD custom health/diff logic.
- **managedFields** — Per-field ownership record on objects, used by Server-Side Apply.
- **OCI artifact / OCIRepository** — Registry-stored artifact (config) usable as a GitOps source.
- **OPA / Conftest / Kyverno / Rego** — Policy engines/languages to enforce config rules (CI or admission).
- **OpenGitOps** — CNCF effort defining the four GitOps principles.
- **Operator** — A controller encoding operational domain knowledge for a resource.
- **Progressive delivery** — Gradual, metric-gated rollout (canary/blue-green) with auto-rollback.
- **Prune / garbage collection** — Deleting live resources that no longer exist in desired state.
- **Pull vs push** — GitOps pulls state from inside the cluster; traditional CI pushes from outside.
- **Raft** — Consensus algorithm for ordered, fault-tolerant agreement (used by etcd).
- **RBAC** — Role-Based Access Control (subjects→roles→allowed actions).
- **Reconcile interval** — How often a controller re-checks desired vs live (a freshness/cost knob).
- **Repo-server (Argo)** — Component that clones repos and renders manifests.
- **Sealed Secrets** — Commit ciphertext encrypted to a cluster controller's key; only that cluster decrypts.
- **Server-Side Apply (SSA)** — API-server-side merge with per-field ownership tracking.
- **SOPS** — Encrypts config values with KMS/age/PGP; controller decrypts at reconcile.
- **Source of truth** — Authoritative store of desired state (Git in GitOps).
- **Sync status** — `Synced` / `OutOfSync` (desired vs live comparison).
- **Sync wave** — Ordering of resources within a sync phase via annotation.
- **Three-way diff** — Compare desired, live, and last-applied to find *this controller's* drift.
- **Webhook** — HTTP callback from Git/registry to trigger immediate reconciliation.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**The 4 principles:** Declarative · Versioned & immutable (Git) · Pulled automatically · Continuously reconciled.

**Mental model:** Git = thermostat set-point (desired); controller = thermostat reading the room (live) and running HVAC (apply/delete) to close the gap; drift = a draft the thermostat corrects; rollback = reset the set-point (`git revert`).

**Pull > push because:** prod creds stay inside the cluster; API server can be private (egress-only to Git); CI never holds cluster admin; the synced revision is ground truth.

**Two states to never confuse:** desired (rendered Git) vs live (cluster). Sync status compares them; drift = the gap.

**Argo CD:** `Application` CR; components = api-server, repo-server (renders), application-controller (diffs/syncs/heals), redis (cache), ApplicationSet; default reconcile ~180s; `syncPolicy.automated.{prune,selfHeal}`; sync waves/hooks; `ignoreDifferences`; `argocd app sync/diff/rollback/history`.

**Flux:** controllers = source / kustomize / helm / notification / image-reflector / image-automation; CRDs `GitRepository`+`Kustomization`+`HelmRelease`; SSA by default; native SOPS; `flux bootstrap/get/reconcile/diff/trace/suspend`.

**Rollback:** `git revert` (or pin `targetRevision`); `argocd app rollback <id>`. Design backward-compatible (expand/contract) migrations.

**Secrets:** Sealed Secrets (cluster-bound key) · SOPS (KMS/age, Flux-native, reviewable) · ESO (reference only, external store) · Vault inject (runtime).

**Progressive delivery:** Argo Rollouts / Flagger; canary/blue-green + metric analysis (Prometheus) + auto-abort.

**Top anti-patterns:** human `kubectl apply`, mutable tags (`latest`), plaintext secrets, disabling self-heal to hide drift, one giant Application, CI that both builds and applies.

**Default decision rules:** k8s + many envs + audit needs → GitOps. Many teams + central dashboard/RBAC → Argo CD. Decentralized + k8s-native + SOPS/OCI → Flux. HPA + GitOps → drop `replicas` from Git. First prune → dry-run first.

**Key numbers to remember:** Argo default app refresh ~180s; sync-wave delay default ~2s; `revisionHistoryLimit` default 10; Flux intervals you set (e.g., source 1m, kustomization 10m). (Verify exact defaults against your installed version — they can change.)

### Self-test (no answers — recall practice)

1. Explain, without notes, the four OpenGitOps principles and give a concrete example of each in a Kubernetes deployment.
2. Draw the data/control flow from a `git push` of application code to the new version serving traffic in prod, naming every component (Argo CD *or* Flux) and where CI stops.
3. An app shows `Synced` but `Degraded`. List five distinct root causes and the exact commands you'd run to diagnose each.
4. Your `Deployment` has `replicas: 3` in Git, but an HPA scales it to 12 and the controller keeps reverting it. Explain *why* this happens at the `managedFields`/diff level and give two correct fixes.
5. Compare Sealed Secrets, SOPS, and External Secrets Operator across: where the secret lives, where decryption happens, the key-loss failure mode, and when you'd choose each.
6. Justify pull-based deployment's security advantage to a skeptical security reviewer — and then name two attack surfaces that GitOps *adds* and how you'd harden them.
7. You enabled `prune: true` and a production namespace got deleted on the next sync. Reconstruct the likely cause and design three controls that would have prevented it.
8. Decide between Argo CD and Flux for a 5-team org running 3 clusters with strict secret rotation requirements; defend your choice with at least four concrete factors.
```

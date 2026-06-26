# Infrastructure as Code (Terraform & Helm)

> A definitive engineering-handbook chapter for senior backend developers (Java/JVM background) who want to fully master declarative infrastructure provisioning, Terraform, and Helm — from first principles to deep internals, production operation, and interview readiness.

---

## 1. Overview & where it fits

### 1.1 What "Infrastructure as Code" actually means

**Infrastructure as Code (IaC)** is the practice of defining and managing your infrastructure — servers, networks, load balancers, databases, DNS records, Kubernetes objects, IAM permissions, message queues, and so on — using machine-readable definition files that live in version control, rather than by clicking buttons in a web console or running ad-hoc shell commands by hand.

The core idea: **your infrastructure is described by source code, and that source code is the single source of truth.** You do not change infrastructure by logging into a console; you change the code, review it, and let a tool reconcile reality to match the code.

If you come from a Java backend background, the closest analogy is the difference between:
- **Imperative provisioning** (the old way): like writing a `main()` method full of step-by-step mutations — "create VM, then attach disk, then open port 443, then install nginx." You describe *how* to get there. If you run it twice, you may get two VMs or an error.
- **Declarative provisioning** (the IaC way): like writing a Spring `@Configuration` bean definition or a Kubernetes manifest — you describe the *desired end state* ("I want one VM of this size, with this disk, with port 443 open"), and the engine figures out the diff between what exists and what you declared, then applies only the necessary changes. Run it twice and nothing happens the second time (this property is called **idempotency**).

> **Idempotency** (define-as-you-go): an operation is idempotent if applying it multiple times produces the same result as applying it once. `PUT /users/5 {name: "Bob"}` is idempotent; `POST /users {name: "Bob"}` is not (it creates a new user each time). Declarative IaC is fundamentally about idempotent reconciliation.

### 1.2 The problem it solves

Before IaC, infrastructure was provisioned manually or by "snowflake" shell scripts. This created several chronic problems:

1. **Configuration drift** — the real state of production slowly diverges from any written record because people make manual "quick fixes" (open a port, bump a memory limit, hotfix a config). Six months later nobody knows what's actually deployed. (We define **drift** precisely in §1.5.)
2. **Non-reproducibility** — you cannot reliably recreate the staging environment to match production, or rebuild after a disaster, because the knowledge lives in people's heads and console clicks.
3. **No review, no audit trail** — a manual change to a production firewall rule has no pull request, no reviewer, no git blame. You cannot answer "who changed this, when, and why."
4. **Environment inconsistency** — "works in staging, breaks in prod" because the environments were built by hand at different times.
5. **Slow, error-prone scaling** — standing up a new region or a new tenant means weeks of manual clicking.

IaC fixes all of these by treating infrastructure with the same engineering discipline as application code: version control, code review, automated testing, CI/CD pipelines, and reproducible builds.

### 1.3 The two tools this chapter centers on

- **Terraform** (by HashiCorp; now also forked as **OpenTofu**) is the dominant tool for provisioning *cloud and platform infrastructure* — the VMs, networks, managed databases, DNS, IAM, Kubernetes clusters themselves, SaaS resources, etc. It is **cloud-agnostic** (works across AWS, Azure, GCP, and hundreds of other providers through a plugin system) and **declarative**.
- **Helm** is the de-facto **package manager for Kubernetes**. Where Terraform provisions the *cluster and surrounding cloud resources*, Helm packages and deploys the *applications that run inside Kubernetes* — bundling all the Kubernetes manifests (Deployments, Services, ConfigMaps, Ingresses, etc.) for an app into a versioned, parameterizable unit called a **chart**.

A common, healthy division of labor in a Java shop:
- **Terraform** stands up the EKS/GKE/AKS cluster, the RDS/Cloud SQL database, the VPC, the load balancers, the IAM roles, the S3 buckets.
- **Helm** (often driven by **Argo CD** or **Flux** in a GitOps flow) deploys your Spring Boot services, Kafka, Redis, Prometheus, etc. *into* that cluster.

> **Kubernetes (k8s)** (define-as-you-go): an open-source container orchestration platform. You give it container images and declarative manifests describing how many replicas you want, how they network, and how they get config/secrets; Kubernetes continuously reconciles the cluster to match. Helm sits on top of Kubernetes to template and version-manage those manifests. We expand on Kubernetes objects in §6 of the Helm material.

### 1.4 When you reach for each

| Need | Reach for |
|---|---|
| Provision a VPC, subnets, security groups | Terraform |
| Create a managed Postgres / RDS instance | Terraform |
| Create the Kubernetes cluster itself (EKS/GKE/AKS) | Terraform |
| Set up IAM roles, DNS, certificates, S3 buckets | Terraform |
| Deploy your Spring Boot app + its ConfigMap + Service + Ingress into k8s | Helm (or Kustomize) |
| Install Prometheus, Grafana, cert-manager, ingress-nginx into k8s | Helm |
| Manage SaaS resources (Datadog monitors, GitHub repos, Cloudflare) | Terraform |
| Plain, low-magic patching of a few k8s manifests across envs | Kustomize |

### 1.5 The one-paragraph mental model

**Declarative IaC is a control loop over the gap between *desired state* (what your code says) and *actual state* (what really exists in the world).** Terraform persists a record of what it believes exists — the **state file** — and on each run it (a) reads the real world, (b) compares it to the desired config and the state, (c) computes a minimal **plan** (the diff), and (d) **applies** that plan, mutating the world and updating the state. Helm does the analogous thing for Kubernetes: it renders templated manifests into concrete objects, records each install as a versioned **release**, and on upgrade computes a three-way diff to patch the cluster, with the ability to **rollback** to any prior release. The whole discipline rests on three pillars: **a declarative desired state in version control**, **a faithful record of actual state**, and **a reconciliation engine that computes and applies the minimal diff** — and almost every production incident in this space comes from those three pillars getting out of sync (state corruption, drift, or a bad diff with a large blast radius).

> **Drift** (define-as-you-go): the divergence between what your IaC code (and its state) says should exist and what actually exists in the live environment, usually caused by out-of-band manual changes. **Drift detection** is running a no-op plan/diff to surface those divergences. **Blast radius** is the scope of resources a single change can affect or destroy — a key risk concept we return to repeatedly.

---

## 2. Foundations from first principles

This section builds the conceptual ground floor. If you already know what a provider and a state file are, skim — but the precise definitions here are load-bearing for the internals section.

### 2.1 Declarative vs. imperative, precisely

- **Imperative**: you specify the exact sequence of operations. Example: a Bash script that runs `aws ec2 run-instances ...` then `aws ec2 attach-volume ...`. The tool does exactly what you say, in order, with no notion of "current state vs. desired state." Re-running re-executes everything (and often fails or duplicates).
- **Declarative**: you specify the desired end state as data. The engine reads current reality, diffs against your declaration, and performs only the operations needed to converge. Terraform's HCL and Kubernetes YAML are declarative.

A subtle but important middle category: **idempotent configuration management** tools like **Ansible**, **Chef**, and **Puppet**. These are often called declarative but are really *imperative-with-idempotent-modules*: you write tasks ("ensure package nginx is installed"), each task checks-then-acts. They excel at *configuring the inside of a machine* (installing packages, editing files), whereas Terraform excels at *provisioning the machine and its surrounding cloud resources*. They are complementary, not competitors.

> **Ansible** (define-as-you-go): a configuration-management/automation tool from Red Hat that connects over SSH and runs idempotent "tasks" described in YAML "playbooks." No agent needed on targets. Often used after Terraform creates a VM, to install and configure software on it.

### 2.2 Desired state, actual state, and the reconciliation loop

Three nouns govern everything:

1. **Desired state** — your code. In Terraform, the `.tf` files. In Helm, the rendered manifests. This is what you *want*.
2. **Actual state** — reality. The actual EC2 instances, the actual Kubernetes Deployments. This is what *is*.
3. **Recorded/known state** — the tool's *belief* about what it created. In Terraform this is the **state file** (`terraform.tfstate`). In Helm it's the **release record** stored as a Secret in the cluster. This is what the tool *thinks* exists.

Reconciliation = compute `diff(desired, actual_or_recorded)` and apply it. The recorded state exists because querying the entire cloud for "everything" on every run would be slow and ambiguous (which of 5,000 EC2 instances is *mine*?). The recorded state is an index that lets the tool know *which* real resources it owns and what their last-known attributes were.

This is the same mental model as a Kubernetes controller's reconcile loop, or even a React virtual DOM diff: declare the target, diff against current, patch the difference.

### 2.3 Terraform core vocabulary (defined from zero)

- **HCL (HashiCorp Configuration Language)**: the domain-specific language Terraform uses. It's declarative, block-structured, and JSON-compatible (anything in HCL can also be written in JSON). You write `resource`, `variable`, `output`, `module`, etc. blocks.
- **Provider**: a plugin that teaches Terraform how to talk to a specific API (AWS, Azure, GCP, Kubernetes, Helm-as-a-provider, Datadog, GitHub, …). A provider exposes **resources** and **data sources**. Providers are versioned and downloaded during `terraform init`.
- **Resource**: a single managed object, e.g. `aws_instance`, `aws_s3_bucket`, `kubernetes_deployment`. The `resource "aws_instance" "web" { ... }` block declares one (or, with `count`/`for_each`, many).
- **Data source**: a *read-only* lookup of something Terraform does not manage, e.g. `data "aws_ami" "ubuntu" { ... }` to find the latest Ubuntu image ID. Used to feed values into resources.
- **State**: the JSON file recording the mapping from your config resources to real-world resource IDs and their last-read attributes. The crux of Terraform's design (and its most dangerous failure surface).
- **Backend**: where state is stored and how operations run. The default is **local** (a file on disk). Production uses a **remote backend** like S3 + DynamoDB, Terraform Cloud, Azure Blob, or GCS, which adds locking and team sharing.
- **Plan**: the computed diff between desired config and actual/recorded state, expressed as create/update/replace/destroy actions. Produced by `terraform plan`.
- **Apply**: executing a plan, mutating the real world and updating state. Produced by `terraform apply`.
- **Module**: a reusable, parameterized bundle of resources — Terraform's unit of abstraction and reuse, analogous to a function or a library. The top-level directory you run Terraform in is itself the **root module**.
- **Workspace**: a named, separate state file within the same backend/config, historically used to manage multiple instances (e.g., dev/stage/prod) of the same configuration. (We cover the strong caveats in §7.)
- **Provisioner**: an escape hatch to run scripts on a resource after creation (e.g., `remote-exec` to run a shell command on a new VM). HashiCorp explicitly calls these a **last resort**.

### 2.4 Helm core vocabulary (defined from zero)

- **Chart**: the Helm packaging unit — a directory (or tarball, `.tgz`) containing templates, default values, and metadata. A chart describes a *related set of Kubernetes resources*. Think Maven artifact, but for k8s manifests.
- **Template**: a Kubernetes manifest file with Go-template directives (`{{ ... }}`) that get filled in from values. Lives in `templates/`.
- **Values**: the parameters that fill the templates — defaults in `values.yaml`, overridable at install time via `--set` or `-f my-values.yaml`. This is what makes a chart reusable across environments.
- **Release**: a specific *installation* of a chart into a cluster, with a name and a monotonically increasing **revision** number. Installing the same chart twice (different names) yields two independent releases. Each upgrade bumps the revision; rollback restores a prior revision.
- **Repository**: a place charts are published and pulled from (an HTTP server with an `index.yaml`, or, increasingly, an **OCI registry** — the same registries that host container images).
- **Chart dependencies (subcharts)**: charts can depend on other charts (e.g., your app chart depends on a `postgresql` chart), declared in `Chart.yaml` and fetched into `charts/`.
- **Helm 3 vs Helm 2**: Helm 2 had a cluster-side server component called **Tiller** with broad permissions (a notorious security problem). **Helm 3 removed Tiller**; the client talks directly to the Kubernetes API using your kubeconfig credentials and stores release info as Secrets in the namespace. Everything in this chapter assumes Helm 3.

> **kubeconfig** (define-as-you-go): the YAML file (default `~/.kube/config`) that tells `kubectl`/Helm which clusters exist, how to authenticate to them, and which is the current context. Helm 3 uses it directly — your RBAC permissions are exactly your kubeconfig user's permissions.

> **RBAC (Role-Based Access Control)** (define-as-you-go): Kubernetes' authorization system. Roles grant verbs (get, list, create, delete) on resource types; RoleBindings attach roles to users/service accounts. Because Helm 3 uses your credentials, you can only do what your RBAC allows — a big security improvement over Tiller.

### 2.5 Immutable infrastructure (the philosophy that ties it together)

**Immutable infrastructure** means: once a server/artifact is deployed, you never modify it in place. To change anything, you build a *new* artifact (a new machine image, a new container image) and replace the old one wholesale. The opposite — SSHing in to patch a running server — is **mutable infrastructure**.

Why it matters and why IaC enables it:
- **No drift on individual hosts** — since nobody patches a live box, the only source of truth is the image build, which is itself code.
- **Reproducibility & rollback** — to roll back, redeploy the previous image. No "undo the manual change" guesswork.
- **Predictability** — every instance of version `v42` is byte-identical.

The canonical pattern: build a **golden image** (e.g., with HashiCorp **Packer**, or just a Docker image), then have Terraform reference that immutable image and replace instances when the image changes. Containers + Kubernetes are immutable infrastructure by default: you don't patch a Pod, you roll out a new image and Kubernetes replaces Pods.

> **Packer** (define-as-you-go): a HashiCorp tool that builds identical machine images (AMIs, VM images, Docker images) from a single template. The "build once, deploy many" half of immutable infra; Terraform is the "deploy" half.

> **Pod** (define-as-you-go): the smallest deployable unit in Kubernetes — one or more containers that share a network namespace and storage. You rarely create Pods directly; a Deployment manages a ReplicaSet that manages Pods, and rolling out a new image replaces Pods immutably.

### 2.6 Where state lives and why locking matters (the headline risk)

Because Terraform's state is *shared mutable global state* for a team, two engineers running `apply` at the same time can corrupt it — interleaved writes produce a state file that doesn't match reality, which then produces wrong plans (deleting resources, duplicating them). The defense is **state locking**: before any write operation, Terraform acquires an exclusive lock (e.g., a DynamoDB conditional write, an Azure blob lease, a GCS lock). If the lock is held, your run waits or fails fast. This is the single most important operational fact about Terraform, and we devote much of §3 and §9 to it.

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, what Terraform and Helm actually *do* under the hood.

### 3.1 Terraform: the full lifecycle, step by step

Terraform's CLI runs a pipeline of phases. Here is the end-to-end control and data flow.

#### Phase 0 — Configuration loading & merging
1. Terraform discovers all `*.tf` (and `*.tf.json`) files in the working directory (the **root module**). Files are merged — order doesn't matter; it's one logical config.
2. It parses HCL into an internal representation, resolving `terraform { required_version, required_providers, backend }` blocks first.
3. Variable values are gathered from (in increasing precedence): defaults in `variable` blocks → environment variables (`TF_VAR_*`) → `terraform.tfvars` / `*.auto.tfvars` → `-var-file` → `-var` on the CLI. **Last write wins; later sources override earlier.**

#### Phase 1 — `terraform init`
1. **Backend initialization**: reads the `backend` block, connects to the remote backend (e.g., S3 bucket), and if migrating from local, offers to copy existing state up.
2. **Provider installation**: examines `required_providers` and resource usage, resolves versions against constraints, downloads provider plugins from the **Terraform Registry** (`registry.terraform.io`) into `.terraform/providers/`, and records exact versions + checksums in `.terraform.lock.hcl` (the **dependency lock file** — commit this!).
3. **Module installation**: downloads any remote `module` sources (Git, registry, S3, local paths) into `.terraform/modules/`.

> **Provider plugin** (define-as-you-go): a separate binary (written in Go, communicating with Terraform core over gRPC) that implements the CRUD operations and schema for one platform's resources. Terraform "core" knows nothing about AWS; the AWS provider does. This plugin architecture is why Terraform supports thousands of platforms.

#### Phase 2 — Refresh (state ↔ reality sync)
1. For every resource in state, Terraform calls the provider's **Read** operation against the live API to fetch current attributes. This updates the *in-memory* copy of state to reflect reality (detecting drift).
2. Historically this happened automatically at the start of plan/apply. Modern Terraform (0.15.4+) lets you skip it (`-refresh=false`) or do a refresh-only plan (`terraform plan -refresh-only`) to *review* drift before deciding to absorb it.

#### Phase 3 — Build the resource graph
1. Terraform constructs a **Directed Acyclic Graph (DAG)** of all resources and data sources. Edges are dependencies — explicit (`depends_on`) or implicit (resource B references an attribute of resource A, e.g. `subnet_id = aws_subnet.a.id`).
2. The graph is **walked in dependency order**, and independent nodes are processed **in parallel** (default parallelism = **10** concurrent operations; tunable with `-parallelism=N`).

> **DAG (Directed Acyclic Graph)** (define-as-you-go): a graph with directed edges and no cycles. "Acyclic" guarantees a valid topological ordering exists, so Terraform can always determine "build A before B." If you accidentally create a dependency cycle, `terraform plan` errors out.

#### Phase 4 — `terraform plan` (compute the diff)
For each resource node, Terraform compares three things: the **config** (desired), the **prior state** (recorded), and the **refreshed reality** (actual), then classifies the action:
- **`+` create** — in config, not in state.
- **`-` destroy** — in state, removed from config.
- **`~` update in place** — exists, an attribute changed and the provider supports in-place update.
- **`-/+` replace (destroy then create)** — an attribute changed that *forces replacement* (e.g., changing an EC2 AMI or a resource's `name` that the API can't mutate). Marked `# forces replacement`.
- **`<=` read** — a data source to be read.
- **no-op** — already matches.

The output is a **plan**, optionally saved to a binary file (`terraform plan -out=tfplan`) so the exact diff you reviewed is the exact diff you apply (no TOCTOU surprises).

> **TOCTOU (Time-Of-Check to Time-Of-Use)** (define-as-you-go): a class of bug where state changes between when you check it and when you act on it. Saving a plan file and applying *that file* (rather than re-planning at apply time) closes this window.

#### Phase 5 — `terraform apply` (mutate the world)
1. If applying a saved plan file, Terraform executes exactly that. If applying without a saved plan, it re-runs plan and prompts for confirmation (`yes`).
2. **Acquire the state lock** (see §3.3). If unavailable, wait/fail.
3. Walk the DAG, calling provider **Create / Update / Delete** operations in dependency order, parallel where possible.
4. After *each* resource operation completes, Terraform **persists the updated state** (so a crash mid-apply doesn't lose the record of what was already created — you get a partially-updated state, not a totally-lost one).
5. **Release the state lock.**
6. Compute and store **outputs**.

#### Phase 6 — `terraform destroy`
Walks the graph in *reverse* dependency order, destroying resources (so dependents die before their dependencies). `terraform destroy` is `apply` with everything targeted for deletion. **This is where the worst blast-radius incidents happen** (see §9).

### 3.2 Terraform state: anatomy and the operations that touch it

The state file is JSON. Key fields:
- `version`, `terraform_version` — format and CLI version.
- `serial` — a monotonically increasing integer bumped on every write. Used to detect stale writes.
- `lineage` — a UUID identifying this state's "ancestry"; guards against accidentally pointing two unrelated configs at the same state.
- `outputs` — last computed output values.
- `resources[]` — the heart: each entry maps a config address (`aws_instance.web`) to its real-world `id`, all read attributes, the provider, and dependency metadata.

State is **sensitive**: it stores *all* attributes, including ones the provider returns that may be secrets (DB passwords, private keys, generated tokens) — **in plaintext**. Hence: encrypt state at rest, restrict access, never commit it to Git.

State-manipulation commands (the surgical toolkit):

| Command | Purpose |
|---|---|
| `terraform state list` | List resources tracked in state |
| `terraform state show <addr>` | Show attributes of one resource |
| `terraform state mv <src> <dst>` | Rename/move a resource in state (e.g., after refactoring into a module) without destroying it |
| `terraform state rm <addr>` | Forget a resource (stop managing it; does NOT delete the real resource) |
| `terraform state pull` / `push` | Download / upload raw state (dangerous; for surgery) |
| `terraform import <addr> <id>` | Bring an existing real resource under management (modern: `import` block) |
| `terraform state replace-provider` | Rewrite provider references (e.g., migrating HashiCorp → OpenTofu) |
| `terraform force-unlock <lock-id>` | Manually release a stuck lock (use with extreme care) |

### 3.3 State locking internals (the part that causes outages)

When you use a remote backend that supports locking, before any state *write* Terraform:
1. **Acquires a lock.** Mechanism is backend-specific:
   - **S3 backend**: historically used a **DynamoDB** table with a conditional `PutItem` on a `LockID` primary key — the conditional write fails if the item exists, which *is* the lock. (As of the AWS provider/Terraform updates in 2024, S3 backends also support **native S3 lock files** via `use_lockfile = true`, reducing the need for DynamoDB.)
   - **Azure Blob**: uses a **blob lease**.
   - **GCS**: uses an object-based lock.
   - **Terraform Cloud / Enterprise**: server-side run locks.
2. **Writes a lock record** containing who holds it, when, the operation, and a lock ID.
3. **Performs the operation, then releases the lock.**

Failure modes (covered fully in §9): a crashed apply or a killed CI job can leave a **stale lock**; you then see `Error acquiring the state lock`. The fix is `terraform force-unlock <ID>` — but *only* after confirming no run is actually in progress, because force-unlocking during a live apply re-opens the corruption window the lock existed to prevent.

> **DynamoDB** (define-as-you-go): AWS's managed key-value/NoSQL database. Its conditional-write feature (write only if a condition holds) provides the atomic compare-and-set primitive Terraform used to implement distributed locking for the S3 backend.

### 3.4 Helm: the full lifecycle, step by step

Helm's job is: take a chart + values, render concrete Kubernetes manifests, and apply them as a tracked, versioned release.

#### Install flow (`helm install myrel ./mychart -f values-prod.yaml`)
1. **Load the chart**: read `Chart.yaml` (metadata), `values.yaml` (defaults), `templates/`, `charts/` (subcharts), `crds/`.
2. **Resolve & merge values**: precedence (lowest → highest): subchart `values.yaml` → parent chart `values.yaml` → `-f` files (left to right) → `--set`/`--set-string`/`--set-file`. The merged values become the `.Values` object available in templates.
3. **Render templates**: Helm runs the **Go text/template** engine (plus the **Sprig** function library and Helm-specific functions) over every file in `templates/`, producing concrete YAML. Built-in objects available: `.Values`, `.Release` (name, namespace, revision, isInstall/isUpgrade), `.Chart`, `.Capabilities` (cluster/API versions), `.Files`, `.Template`.
4. **Run install hooks** (pre-install) if any (see §7 on hooks).
5. **Validate & apply**: Helm sends the rendered manifests to the Kubernetes API server (it does a client-side and server-side validation, can `--dry-run`). Kubernetes' own controllers then reconcile the objects.
6. **Record the release**: Helm stores the release manifest + computed values + revision number as a **Secret** (type `helm.sh/release.v1`, base64+gzip encoded) in the release namespace, named like `sh.helm.release.v1.myrel.v1`.
7. **Wait (optional)**: with `--wait`, Helm blocks until the resources report Ready (Deployments available, Pods ready) up to `--timeout` (default **5m**).

> **Go text/template** (define-as-you-go): Go's built-in templating language (`{{ .Field }}`, `{{ if }}`, `{{ range }}`). Helm uses it to turn parameterized templates into final YAML. **Sprig** is a popular library of ~100+ helper functions (`upper`, `quote`, `b64enc`, `default`, etc.) Helm bundles. This is why Helm templates look like `{{ .Values.image.tag | default "latest" | quote }}`.

#### Upgrade flow (`helm upgrade myrel ./mychart -f values-prod.yaml`)
1. Render new manifests from the new chart/values.
2. **Three-way strategic merge**: Helm computes a patch using **three** inputs:
   - the **old manifest** (what Helm rendered last time, from the release record),
   - the **new manifest** (what it just rendered),
   - the **live state** in the cluster (what's actually there now).
   This three-way merge (introduced in Helm 3) means Helm can detect and reconcile changes made outside Helm, and correctly *remove* objects that existed in the old release but not the new one. (Helm 2's two-way merge could not delete such resources — a real source of bugs.)
3. Apply the patch via the Kubernetes API; Kubernetes does the rolling update.
4. **Increment the revision** and store a new release Secret (`...v2`).
5. History is retained up to `--history-max` (default **10** revisions kept).

#### Rollback flow (`helm rollback myrel 3`)
1. Helm fetches the stored manifest for revision 3 from its release Secrets.
2. It computes a patch from the *current* live state to the revision-3 manifest and applies it.
3. This creates a **new** revision (e.g., v5) that is a *copy* of v3's desired state — rollback moves forward in revision number while restoring an old configuration. (So `helm history` shows the rollback as a new entry.)

#### Uninstall flow (`helm uninstall myrel`)
1. Helm deletes all resources it tracks for the release.
2. By default it also deletes the release history. `--keep-history` preserves the records so you could roll back; `helm uninstall --keep-history` then `helm rollback` is a recovery path.

> **Strategic merge patch** (define-as-you-go): a Kubernetes-aware patch format that understands the *semantics* of fields — e.g., it knows a Pod's `containers` list is keyed by `name` and merges by key rather than replacing the whole list. This is smarter than a plain JSON merge patch and is what makes "change one container's image without clobbering the rest" work.

### 3.5 Kustomize: how it works (the alternative)

**Kustomize** (built into `kubectl` via `kubectl apply -k` and also a standalone binary) takes a *template-free* approach. Instead of Go templating, you keep **plain, valid YAML manifests** as a **base**, then layer **overlays** (per-environment directories) that **patch** the base via a `kustomization.yaml`. Patches are strategic-merge or JSON-patch. There are no variables, no logic, no functions — just declarative composition and patching.

Internally: `kustomize build overlays/prod` reads the base, applies the overlay's patches, name prefixes/suffixes, common labels/annotations, image overrides, and ConfigMap/Secret generators, and emits final YAML to stdout (which you then `kubectl apply -f -`). It's pure data transformation, no runtime release tracking of its own (it relies on `kubectl apply` and Kubernetes' own annotations / server-side apply for state).

We compare Helm vs Kustomize head-to-head in §8.

---

## 4. The complete toolkit

### 4.1 Terraform CLI commands

| Command | Purpose | Key flags / notes |
|---|---|---|
| `terraform init` | Initialize: backend, providers, modules | `-upgrade` (re-resolve provider versions), `-backend-config=...`, `-reconfigure`, `-migrate-state` |
| `terraform validate` | Static, offline syntax/type check | No API calls; runs in CI fast |
| `terraform fmt` | Canonicalize HCL formatting | `-recursive`, `-check` (CI gate), `-diff` |
| `terraform plan` | Compute diff | `-out=FILE` (save plan), `-target=ADDR` (narrow scope — emergency only), `-refresh=false`, `-refresh-only`, `-var/-var-file`, `-destroy` |
| `terraform apply` | Execute changes | `tfplan` (apply saved plan), `-auto-approve`, `-parallelism=N` (default 10), `-replace=ADDR` (force replace; modern alt to taint) |
| `terraform destroy` | Tear everything down | `-target` to limit (still dangerous), `-auto-approve` |
| `terraform refresh` | Update state from reality | Deprecated in favor of `plan -refresh-only` |
| `terraform output` | Print outputs | `-json`, `-raw NAME` |
| `terraform state ...` | State surgery | See §3.2 table |
| `terraform import` | Adopt existing resource | Prefer `import {}` blocks (1.5+) for reviewable, planned imports |
| `terraform taint` / `untaint` | Mark resource for recreation | **Deprecated**; use `-replace=ADDR` |
| `terraform workspace ...` | Manage named state workspaces | `new`, `select`, `list`, `delete` |
| `terraform graph` | Emit the DAG (DOT format) | Pipe to Graphviz to visualize dependencies |
| `terraform show` | Human/JSON view of state or plan | `terraform show -json tfplan` for machine parsing |
| `terraform console` | Interactive expression REPL | Great for testing `for`/functions |
| `terraform force-unlock` | Release a stuck state lock | Needs the lock ID; confirm no live run first |
| `terraform login` / `logout` | Auth to Terraform Cloud | |
| `terraform providers` | Show provider requirements/tree | `mirror`, `lock` subcommands |
| `terraform test` | Run native `.tftest.hcl` tests | Added in Terraform **1.6** |

### 4.2 Terraform HCL building blocks

| Block / construct | Purpose | Notes / key arguments |
|---|---|---|
| `terraform { ... }` | Settings: `required_version`, `required_providers`, `backend` | The settings block; one per config |
| `provider "aws" { ... }` | Configure a provider instance | `region`, `alias` (for multiple instances), auth |
| `resource "TYPE" "NAME" { ... }` | A managed resource | `count`, `for_each`, `lifecycle`, `depends_on`, `provider` |
| `data "TYPE" "NAME" { ... }` | Read-only lookup | Evaluated during plan |
| `variable "x" { ... }` | Input parameter | `type`, `default`, `description`, `sensitive`, `validation`, `nullable` |
| `output "x" { ... }` | Exported value | `value`, `sensitive`, `depends_on` |
| `locals { ... }` | Named intermediate expressions | Computed once; not inputs |
| `module "x" { source = ... }` | Instantiate a module | `source`, `version`, plus the module's input variables; `count`/`for_each` supported |
| `lifecycle { ... }` (inside resource) | Tune CRUD behavior | `create_before_destroy`, `prevent_destroy`, `ignore_changes`, `replace_triggered_by` |
| `dynamic "block" { ... }` | Generate repeated nested blocks | Iterates to produce sub-blocks |
| `moved { ... }` | Refactor without destroy | Tells Terraform an address was renamed |
| `import { ... }` | Declarative import (1.5+) | `to`, `id`; appears in plan |
| `check { ... }` | Post-apply assertions (1.5+) | Non-blocking health assertions |

`lifecycle` meta-arguments are critical safety/behavior knobs:
- `prevent_destroy = true` — Terraform refuses to destroy this resource (guard your prod DB).
- `create_before_destroy = true` — for zero-downtime replacement, create the new resource before deleting the old (vs. the default destroy-then-create).
- `ignore_changes = [tags, ...]` — tolerate drift on specific attributes (e.g., tags an external tool manages).
- `replace_triggered_by = [...]` — force replacement when a referenced thing changes.

### 4.3 Terraform backends (remote state options)

| Backend | Locking mechanism | Notes |
|---|---|---|
| `local` | None | Default; single-user only |
| `s3` | DynamoDB table or native S3 lockfile (`use_lockfile`, newer) | Most common AWS choice; enable bucket versioning + SSE |
| `azurerm` (Azure Blob) | Blob lease | Native locking |
| `gcs` (Google Cloud Storage) | Object lock | Native locking |
| `remote` / `cloud` (Terraform Cloud/Enterprise) | Server-side run locks | Adds remote runs, RBAC, policy, run history |
| `consul`, `http`, `pg` (Postgres), `kubernetes` (Secret) | Varies | Niche; `pg` and `kubernetes` are handy for small teams |

### 4.4 Helm CLI commands

| Command | Purpose | Key flags |
|---|---|---|
| `helm create NAME` | Scaffold a new chart | Generates a working example chart |
| `helm lint ./chart` | Static validation | CI gate |
| `helm template NAME ./chart` | Render manifests locally (no cluster) | `--debug`, `--show-only`, `-f`, `--set`; great for GitOps + diffing |
| `helm install NAME ./chart` | Install a release | `-f`, `--set`, `-n NS`, `--create-namespace`, `--wait`, `--timeout`, `--atomic`, `--dry-run` |
| `helm upgrade NAME ./chart` | Upgrade a release | `--install` (a.k.a. `upgrade -i`: install if absent), `--atomic`, `--wait`, `--cleanup-on-fail`, `--reset-values`, `--reuse-values`, `--history-max` |
| `helm rollback NAME [REV]` | Roll back to a revision | `--wait`, `--cleanup-on-fail`, `--force` |
| `helm uninstall NAME` | Remove a release | `--keep-history` |
| `helm list` (`ls`) | List releases | `-A` (all namespaces), `-a` (include all statuses) |
| `helm status NAME` | Show release status | `--revision N` |
| `helm history NAME` | Show revision history | |
| `helm get manifest/values/hooks/notes NAME` | Inspect a release's stored data | `--revision N` |
| `helm diff upgrade ...` | Preview upgrade changes | Requires the `helm-diff` plugin — strongly recommended |
| `helm dependency update/build` | Manage subcharts | Populates `charts/` from `Chart.yaml` deps |
| `helm repo add/update/list` | Manage chart repositories | |
| `helm push` / `helm pull` (OCI) | Publish/fetch charts to/from OCI registries | `oci://` refs |
| `helm package ./chart` | Build a `.tgz` | `--version`, `--app-version`, `--sign` (provenance) |
| `helm verify` / `--verify` | Verify chart provenance signature | Supply-chain integrity |
| `helm test NAME` | Run the chart's test hooks | Pods annotated as `helm.sh/hook: test` |

Two flags worth memorizing:
- `--atomic` — on failed install/upgrade, automatically roll back to the previous good state (and clean up). Strongly recommended in CI/CD.
- `--wait` — block until resources are Ready; combine with `--timeout`. Without it, Helm reports success the instant the API accepts manifests, even if Pods later crash.

### 4.5 Chart anatomy

```
mychart/
  Chart.yaml          # name, version (chart SemVer), appVersion, dependencies
  values.yaml         # default parameters
  values.schema.json  # optional JSON Schema to validate user values
  charts/             # subcharts (dependencies), fetched here
  crds/               # CRDs installed before templates, never templated/upgraded by Helm
  templates/
    deployment.yaml
    service.yaml
    ingress.yaml
    _helpers.tpl      # named template definitions (helpers), not rendered directly
    NOTES.txt         # post-install message rendered to the user
    tests/            # test hooks
  .helmignore         # files to exclude from the package
```

> **CRD (Custom Resource Definition)** (define-as-you-go): a way to extend Kubernetes with your own object types (e.g., a `Certificate` from cert-manager, or a `Kafka` from Strimzi). Helm installs CRDs from the `crds/` dir *before* templates and, importantly, does **not** manage their upgrade/deletion — a known sharp edge (see §7).

---

## 5. Code examples by use case

These are intentionally across *different* real scenarios. Comments explain the non-obvious lines.

### 5.1 Terraform: production-grade remote backend with locking (AWS, S3 + DynamoDB)

```hcl
# backend.tf — bootstrap once, then every team member shares this state safely.
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0" # allow 5.x patch/minor, pin major
    }
  }

  backend "s3" {
    bucket         = "acme-tfstate-prod"      # versioned + SSE-encrypted bucket
    key            = "platform/network/terraform.tfstate" # path within bucket
    region         = "us-east-1"
    dynamodb_table = "acme-tf-locks"          # holds the LockID item = the lock
    encrypt        = true                       # SSE on the state object
    # use_lockfile = true                       # (newer) native S3 lock, can replace DynamoDB
  }
}

provider "aws" {
  region = "us-east-1"
}
```

```hcl
# The lock table + state bucket themselves must be created out-of-band
# (chicken-and-egg). A tiny bootstrap config with LOCAL state, applied once:
resource "aws_s3_bucket" "tfstate" {
  bucket = "acme-tfstate-prod"
}
resource "aws_s3_bucket_versioning" "tfstate" {   # versioning = your undo button for state
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_dynamodb_table" "locks" {
  name         = "acme-tf-locks"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"               # MUST be exactly "LockID"
  attribute {
    name = "LockID"
    type = "S"
  }
}
```

**Why this matters:** S3 with versioning gives you point-in-time recovery of a corrupted state; DynamoDB (or the native lockfile) prevents concurrent writes. This combination is the baseline for any team larger than one.

### 5.2 Terraform: a reusable module + `for_each` for multiple environments

```hcl
# modules/app_bucket/main.tf — a reusable abstraction (like a function).
variable "name"        { type = string }
variable "environment" { type = string }
variable "versioning"  { type = bool, default = true }

resource "aws_s3_bucket" "this" {
  bucket = "${var.name}-${var.environment}"
  tags   = { Environment = var.environment, ManagedBy = "terraform" }
}

resource "aws_s3_bucket_versioning" "this" {
  count  = var.versioning ? 1 : 0     # conditional resource via count
  bucket = aws_s3_bucket.this.id
  versioning_configuration { status = "Enabled" }
}

output "bucket_arn" { value = aws_s3_bucket.this.arn }
```

```hcl
# root main.tf — instantiate the module once per environment with for_each.
locals {
  envs = {
    dev  = { versioning = false }
    prod = { versioning = true }
  }
}

module "logs_bucket" {
  source      = "./modules/app_bucket"
  for_each    = local.envs           # creates module.logs_bucket["dev"], ["prod"]
  name        = "acme-logs"
  environment = each.key
  versioning  = each.value.versioning
}

output "prod_bucket" {
  value = module.logs_bucket["prod"].bucket_arn
}
```

**Why `for_each` over `count`:** with `count`, resources are indexed by position (`[0]`, `[1]`). Removing the middle element renumbers everything after it, causing Terraform to destroy/recreate unrelated resources. With `for_each`, resources are keyed by a stable map key (`["dev"]`), so adding/removing one env doesn't disturb the others. **Prefer `for_each` for collections of non-identical things.**

### 5.3 Terraform: importing an existing resource (the modern declarative way)

```hcl
# You created a bucket by hand long ago; now you want Terraform to manage it.
import {
  to = aws_s3_bucket.legacy
  id = "acme-legacy-bucket-name"   # the real-world ID
}

resource "aws_s3_bucket" "legacy" {
  bucket = "acme-legacy-bucket-name"
  # Run `terraform plan -generate-config-out=generated.tf` to auto-draft config.
}
```

`terraform plan` will show the import as a planned action you can review (unlike the old imperative `terraform import` command, which mutated state immediately with no review). After a successful apply, you can delete the `import` block.

### 5.4 Terraform: guarding blast radius with lifecycle rules

```hcl
resource "aws_db_instance" "prod" {
  identifier        = "acme-prod-db"
  engine            = "postgres"
  instance_class    = "db.r6g.large"
  allocated_storage = 100

  lifecycle {
    prevent_destroy       = true            # Terraform will REFUSE to destroy this
    ignore_changes        = [allocated_storage] # storage autoscaling changes it out-of-band; tolerate
    create_before_destroy = true            # if replacement is forced, stand up new before killing old
  }
}
```

The `prevent_destroy = true` line is a cheap insurance policy: a careless `terraform destroy` or a config change that *forces replacement* will hard-error instead of silently deleting your production database.

### 5.5 Terraform: native test (Terraform 1.6+)

```hcl
# tests/bucket.tftest.hcl — runs real plan/apply against the config in a temp run.
run "creates_prod_bucket_with_versioning" {
  command = plan                          # plan-only: fast, no real resources

  variables {
    name        = "acme-logs"
    environment = "prod"
    versioning  = true
  }

  assert {
    condition     = length(aws_s3_bucket_versioning.this) == 1
    error_message = "Prod bucket must have versioning enabled"
  }
}
```

Run with `terraform test`. This validates *logic* (conditionals, naming) without needing Terratest/Go. For deeper integration tests (actually creating resources and asserting via cloud SDKs), teams use **Terratest** (Go) or **kitchen-terraform**.

### 5.6 Helm: a minimal, idiomatic chart for a Spring Boot service

`Chart.yaml`:
```yaml
apiVersion: v2
name: orders-service
description: Spring Boot orders API
type: application
version: 1.4.2        # chart version (SemVer) — bump when the chart changes
appVersion: "2025.6"  # the app image version — informational
```

`values.yaml`:
```yaml
replicaCount: 3
image:
  repository: registry.acme.io/orders
  tag: ""                 # if empty we fall back to .Chart.AppVersion (see template)
  pullPolicy: IfNotPresent
resources:
  requests: { cpu: 250m, memory: 512Mi }
  limits:   { cpu: "1",  memory: 1Gi }
env:
  SPRING_PROFILES_ACTIVE: prod
service:
  port: 8080
```

`templates/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "orders-service.fullname" . }}   # named template from _helpers.tpl
  labels: {{- include "orders-service.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels: {{- include "orders-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "orders-service.selectorLabels" . | nindent 8 }}
    spec:
      containers:
        - name: {{ .Chart.Name }}
          # default function: use values.image.tag, else fall back to Chart appVersion
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.service.port }}
          env:
            {{- range $k, $v := .Values.env }}     # render a map into name/value pairs
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          resources: {{- toYaml .Values.resources | nindent 12 }}
          readinessProbe:                          # gate traffic until the JVM is up
            httpGet: { path: /actuator/health/readiness, port: {{ .Values.service.port }} }
            initialDelaySeconds: 20
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: {{ .Values.service.port }} }
            initialDelaySeconds: 40
```

`templates/_helpers.tpl` (named templates — the DRY mechanism):
```
{{- define "orders-service.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "orders-service.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "orders-service.labels" -}}
{{ include "orders-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
```

**Notes that matter:**
- `nindent N` indents *and* prepends a newline — essential because YAML is whitespace-sensitive and Go templates emit raw text. Getting indentation wrong is the #1 Helm beginner bug.
- `include` (vs the template `template` action) returns a string you can pipe (`| nindent`), which is why all named-template usage goes through `include`.
- The readiness/liveness split matters for Java apps: a Spring Boot service has a long JVM warmup; `readinessProbe` keeps it out of the load balancer until `/actuator/health/readiness` passes, while `livenessProbe` only restarts it on true hangs.

### 5.7 Helm: safe upgrade in CI/CD with atomic + wait + diff preview

```bash
# Preview exactly what will change (requires the helm-diff plugin)
helm diff upgrade orders ./orders-service \
  -n prod -f values-prod.yaml --set image.tag=2025.6.1

# Apply atomically: roll back automatically if it fails to become healthy
helm upgrade --install orders ./orders-service \
  -n prod --create-namespace \
  -f values-prod.yaml \
  --set image.tag=2025.6.1 \
  --atomic \           # auto-rollback on failure
  --wait \             # block until Pods are Ready
  --timeout 5m \
  --history-max 20     # keep more history for rollbacks
```

If the new Pods crash-loop or fail readiness within the timeout, `--atomic` reverts to the last good revision and you are not left with a half-broken deployment.

### 5.8 Helm: multi-environment values layering

```bash
# Base + environment-specific overrides, last file wins on conflicts.
helm upgrade --install orders ./orders-service -n prod \
  -f values.yaml \            # shared defaults
  -f values-prod.yaml \       # prod overrides (replicas, resources, ingress host)
  --set image.tag=$GIT_SHA    # CI injects the immutable image tag
```

`values-prod.yaml`:
```yaml
replicaCount: 8
resources:
  requests: { cpu: "1", memory: 2Gi }
  limits:   { cpu: "2", memory: 4Gi }
ingress:
  enabled: true
  host: orders.acme.io
```

### 5.9 Kustomize: the same multi-env problem without templating

`base/kustomization.yaml`:
```yaml
resources:
  - deployment.yaml
  - service.yaml
```

`base/deployment.yaml` is **plain, valid Kubernetes YAML** (you can `kubectl apply -f` it directly — no template syntax).

`overlays/prod/kustomization.yaml`:
```yaml
resources:
  - ../../base
namePrefix: prod-
commonLabels:
  env: prod
images:
  - name: registry.acme.io/orders
    newTag: "2025.6.1"        # override the image tag declaratively
replicas:
  - name: orders
    count: 8                  # patch replica count for prod
patches:
  - target: { kind: Deployment, name: orders }
    patch: |-                 # strategic-merge patch to bump resources
      - op: replace
        path: /spec/template/spec/containers/0/resources/requests/memory
        value: 2Gi
```

```bash
kustomize build overlays/prod | kubectl apply -f -
# or:  kubectl apply -k overlays/prod
```

**The contrast:** the base manifests are real, lintable Kubernetes YAML with zero templating magic; environment differences are expressed as declarative patches. No Go templates, no `nindent`, no Sprig. The tradeoff: no loops, conditionals, or rich parameterization, which can mean a lot of near-duplicate patches for highly variable charts.

### 5.10 Secrets in IaC: External Secrets + SOPS (two idioms)

**(a) Don't put secrets in values/state at all — reference a secret manager.** External Secrets Operator (ESO) syncs from AWS Secrets Manager / Vault into a Kubernetes Secret:
```yaml
# templates/externalsecret.yaml (rendered by your Helm chart)
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: orders-db
spec:
  refreshInterval: 1h
  secretStoreRef: { name: aws-secrets, kind: ClusterSecretStore }
  target: { name: orders-db-credentials }   # the k8s Secret ESO creates
  data:
    - secretKey: password
      remoteRef: { key: prod/orders/db, property: password }
```

**(b) Encrypt secrets at rest in Git with SOPS.** SOPS encrypts only the *values* of a YAML file using a KMS key, so the file is reviewable in Git (keys visible, values encrypted) and decrypted at apply time:
```yaml
# secrets.enc.yaml — committed safely; values are KMS-encrypted ciphertext
db_password: ENC[AES256_GCM,data:9f3a...,type:str]
sops:
  kms:
    - arn: arn:aws:kms:us-east-1:111:key/abcd-...   # who can decrypt
```
With the `helm-secrets` plugin: `helm secrets upgrade ... -f secrets.enc.yaml`. The key rule, repeated below: **never commit plaintext secrets, and remember Terraform state stores secrets in plaintext.**

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Terraform parallelism** defaults to 10 concurrent resource operations. For large configs, walking and refreshing thousands of resources is the bottleneck. Mitigations: split monoliths into smaller state files ("**state segmentation**" by blast-radius domain — networking, data, apps), use `-refresh=false` when you know reality matches, and use `-target` only for emergencies (it's a sharp tool, not a workflow).
- **Provider API throttling**: providers hit cloud APIs which rate-limit. Symptoms: `Throttling` / `RequestLimitExceeded`. The provider retries with backoff; you can lower `-parallelism`.
- **Helm rendering** is fast (local templating); the slow part is `--wait` blocking on Pod readiness. Tune `--timeout` realistically for JVM warmup times.

### 6.2 Correctness & concurrency

- **Always use a locking remote backend** for any shared Terraform state. Without it, concurrent applies corrupt state — the canonical correctness failure.
- **Plan-then-apply the saved plan**: `terraform plan -out=tfplan` reviewed in CI, then `terraform apply tfplan`. This eliminates the TOCTOU gap between what was reviewed and what is applied.
- **Pin everything**: pin Terraform version (`required_version`), provider versions (`~>`), the `.terraform.lock.hcl` (commit it), module versions, Helm chart versions, and **image tags by digest** where possible. Floating `:latest` tags break reproducibility and immutability.
- **Treat state as the crown jewels**: enable bucket versioning so you can recover a prior state; back it up before risky surgery (`terraform state pull > backup.tfstate`).

### 6.3 Security

- **Terraform state contains secrets in plaintext** — encrypt at rest (SSE on S3, etc.), restrict IAM access to the bucket, never commit state to Git, and scrub it from logs.
- **Mark sensitive outputs/variables** `sensitive = true` so Terraform redacts them in CLI output (note: they're still in state).
- **Helm 3 uses your kubeconfig RBAC** — scope CI service accounts tightly (least privilege). No more Tiller god-mode.
- **Don't bake secrets into charts or values committed to Git.** Use External Secrets Operator, Vault, sealed-secrets, or SOPS/helm-secrets (§5.10).
- **Supply chain**: pull provider plugins only from trusted registries (lock-file checksums protect you); sign and verify Helm charts (`helm package --sign`, `helm verify`); scan IaC with **tfsec/Checkov/Trivy** and policy-as-code (**OPA/Conftest**, **Sentinel**) to block insecure configs (e.g., public S3 buckets, `0.0.0.0/0` SSH) in CI.

> **OPA / Conftest** (define-as-you-go): Open Policy Agent is a general policy engine using the Rego language; Conftest runs OPA policies against config files (Terraform plans, Kubernetes YAML, Helm output) in CI to enforce rules like "no security group may allow 0.0.0.0/0 on port 22." **Sentinel** is HashiCorp's equivalent for Terraform Cloud/Enterprise.

### 6.4 Observability

- **Terraform**: parse `terraform show -json tfplan` to surface "what will change" in PR comments (tools like **Atlantis** and Terraform Cloud do this). Detect drift on a schedule with `terraform plan -detailed-exitcode` (exit code **2** = changes present) and alert.
- **Helm**: `helm history`, `helm get manifest`, and `helm status` are your audit trail; integrate with **Argo CD/Flux** for continuous drift detection and a UI of desired-vs-live.
- **Tagging/labeling**: stamp every resource with `ManagedBy=terraform`, owner, environment, and a cost-allocation tag so you can attribute and find things.

### 6.5 Cost

- IaC makes cost *predictable and reviewable*. Use **Infracost** in PRs to show the dollar delta of a plan ("this change adds \$1,420/mo"). The flip side: a bad `for_each` over a large list, or a forced-replacement of expensive resources, can spike cost or cause downtime — review plans for `-/+` replacements of stateful/expensive resources.

### 6.6 Testing IaC

| Layer | Tool | What it checks |
|---|---|---|
| Format/syntax | `terraform fmt -check`, `terraform validate`, `helm lint` | Style, basic correctness, offline |
| Policy | tfsec, Checkov, Conftest/OPA, Sentinel | Security/compliance rules |
| Unit/logic | `terraform test` (1.6+), Helm `--dry-run`/`helm unittest` | Conditionals, naming, value mapping |
| Integration | Terratest (Go), kitchen-terraform | Real resources created, asserted, destroyed |
| Plan review | plan-as-PR (Atlantis/TFC), `helm diff` | Human review of the actual diff |

### 6.7 Production hardening checklist

- Remote, locking, versioned, encrypted backend.
- Plan-then-apply-saved-plan in CI; no `apply -auto-approve` on humans' laptops for prod.
- `prevent_destroy` on stateful/critical resources; `create_before_destroy` for zero-downtime.
- State segmented by blast radius (network / data / app separate states).
- Policy-as-code gates in CI; cost diff in PRs.
- Helm: `--atomic --wait --timeout`; sensible `--history-max`; readiness probes so `--wait` is meaningful.
- Secrets out of state/values via a secret manager.
- Break-glass runbook for stuck locks and corrupted state (§9).

### 6.8 Anti-patterns to avoid

- **One giant monolithic state** for the whole org → huge blast radius, slow plans, lock contention. Segment it.
- **`-target` as a normal workflow** → produces partial applies and inconsistent state. Emergency only.
- **Manual console changes** ("ClickOps") on Terraform-managed resources → drift; either import or stop.
- **Committing state or plaintext secrets to Git.**
- **Floating `:latest` image tags / unpinned providers** → non-reproducible.
- **Overusing Helm templating logic** until charts become unreadable Go-template programs ("YAML programming"). Push complexity into values, or use Kustomize.
- **Helm hooks for ordering everything** → fragile; prefer init containers and Kubernetes-native readiness.
- **Skipping `--wait`** then declaring a broken deploy "successful."
- **Provisioners (`remote-exec`) as a habit** → imperative, non-idempotent; prefer immutable images (Packer) and cloud-init.

---

## 7. Advanced topics & deep internals

### 7.1 Terraform workspaces: the strong caveat

`terraform workspace` creates *multiple state files* for the *same configuration*, distinguished by `terraform.workspace`. It's tempting to use `default`/`dev`/`prod` workspaces for environments. **HashiCorp and most practitioners advise against using workspaces for strongly-separated environments like prod**, because:
- All workspaces share the *same backend* (same bucket, often same credentials), so a prod and dev state sit side by side — weak isolation, easy to fat-finger the wrong workspace.
- Code is identical across workspaces, so environment-specific differences must be smuggled in via `terraform.workspace` conditionals, which gets ugly.
- Better isolation comes from **separate directories/state per environment** (a.k.a. the "directory per environment" or **Terragrunt**-style layout), each with its own backend config and credentials.

Workspaces *are* genuinely useful for ephemeral, identical, short-lived parallel copies (e.g., per-PR preview environments, per-developer sandboxes) where isolation requirements are low.

> **Terragrunt** (define-as-you-go): a thin wrapper around Terraform (by Gruntwork) that keeps DRY backend/provider config and lets you compose multiple Terraform modules with explicit dependencies across separate states. Popular for the "many small states, one repo" layout.

### 7.2 Drift, refresh, and reconciliation nuance

- A plain `terraform plan` refreshes state from reality, so drift shows up as proposed changes that would *revert* the out-of-band change. To *review* drift without proposing reverts, use `terraform plan -refresh-only`.
- `ignore_changes` lets you deliberately accept drift on specific attributes (autoscaling-managed counts, externally-managed tags).
- **`check {}` blocks (1.5+)** run post-apply assertions (e.g., "the deployed endpoint returns 200") and surface warnings without blocking — a lightweight health gate.
- **Continuous drift detection** is a scheduled `plan -detailed-exitcode`; exit code 2 means "drift/changes present" — wire it to alerting.

### 7.3 Terraform graph & dependency subtleties

- Implicit dependencies (attribute references) are preferred over `depends_on` because they're precise. Use `depends_on` only when a dependency exists but isn't expressed through data (e.g., an IAM policy must exist before a Lambda can assume it, but the Lambda config doesn't reference the policy).
- **`create_before_destroy` cascades**: if resource A has it set and B depends on A, B may also need it to avoid a destroy-ordering deadlock. Terraform usually handles this but complex graphs can require care.
- **`moved {}` and `import {}` blocks** make refactors and adoptions reviewable and reproducible (vs. the imperative `state mv` / `import` commands that mutate immediately).

### 7.4 Helm internals: the three-way merge and its sharp edges

- The **three-way strategic merge** (old manifest, new manifest, live) is what lets Helm 3 correctly delete removed resources and reconcile out-of-band changes. But it relies on the *old manifest stored in the release Secret*. If someone hand-edits a Helm-managed resource and you `--reuse-values`/`--reset-values` incorrectly, the merge can produce surprising patches.
- **`--reset-values` vs `--reuse-values`**: by default `helm upgrade` uses the values from the *last release* merged with any new `--set`/`-f`. `--reset-values` discards prior values and uses chart defaults + your new inputs; `--reuse-values` reuses prior values and ignores new `-f` (subtle and a common gotcha — explicitly pass all values you care about in CI).
- **CRDs are special**: charts put CRDs in `crds/`. Helm installs them *once* and does **not** upgrade or delete them on chart upgrade/uninstall (to avoid catastrophic data loss when a CRD owns user data). You must manage CRD upgrades out-of-band. This bites teams who expect `helm upgrade` to roll their CRD schema forward.
- **Hooks**: annotations like `helm.sh/hook: pre-install|post-upgrade|test` let you run Jobs at lifecycle points (DB migrations, smoke tests). Hooks are *not* part of the normal three-way merge — Helm doesn't track them as release resources the same way, so a failed hook can leave orphaned Pods/Jobs (use `helm.sh/hook-delete-policy`).
- **Release storage limits**: release Secrets are stored gzipped; very large charts can approach the **~1MiB** etcd object size limit for the release Secret. Pruning history (`--history-max`) helps.

> **etcd** (define-as-you-go): the distributed key-value store that backs the Kubernetes API server — the cluster's database. It has a default per-object size limit (~1.5MiB), which is why enormous Helm release Secrets or ConfigMaps can fail to store.

### 7.5 Helm critiques (be able to articulate these)

1. **Go templating over YAML is error-prone** — templating a whitespace-sensitive format with a string templating engine yields fragile indentation bugs and hard-to-read charts ("we're writing programs in YAML").
2. **No real type safety** — values are loosely typed; a typo in a values key silently does nothing. (`values.schema.json` mitigates this.)
3. **The abstraction can hide too much** — debugging why a field is set means tracing through templates, helpers, subchart values, and merge precedence.
4. **Upstream charts vary wildly in quality**; community charts may lag, embed opinions, or have huge surface areas.
5. **CRD lifecycle gap** (above).
These critiques are exactly why Kustomize exists and why many teams use Kustomize for their own apps and reserve Helm for installing third-party software.

### 7.6 OpenTofu (the fork) — version/vendor-specific note

In 2023, HashiCorp changed Terraform's license from MPL (open source) to the **BSL (Business Source License)**, a source-available license with usage restrictions. The community forked the last MPL version into **OpenTofu** (under the Linux Foundation), which remains open source and is largely a drop-in replacement (`tofu` CLI, same HCL, same providers/registry compatibility). For new projects today, this is a real decision point; the mechanics in this chapter apply to both, but flag the license/governance difference explicitly when choosing.

### 7.7 GitOps as the operating model

In **GitOps**, Git is the single source of truth and an in-cluster controller (**Argo CD** or **Flux**) continuously reconciles the cluster to match the Git-declared desired state — including rendering Helm charts or Kustomize overlays. This adds *continuous* drift correction (not just at deploy time) and a strong audit trail. It changes the Helm/Kustomize workflow: instead of `helm upgrade` from CI, CI updates Git; the controller does the apply and the three-way reconciliation.

> **Argo CD / Flux** (define-as-you-go): Kubernetes controllers that watch a Git repo and apply its manifests (and Helm/Kustomize output) to the cluster, continuously, reverting manual drift. The cluster-side enforcer of "Git is truth."

---

## 8. Tradeoffs & decision frameworks

### 8.1 Terraform vs. alternatives for provisioning

| Tool | Paradigm | State | Cloud scope | Use when… | Avoid when… |
|---|---|---|---|---|---|
| **Terraform / OpenTofu** | Declarative, HCL | Explicit state file | Multi-cloud, thousands of providers | You want one tool across clouds + SaaS, mature ecosystem | You need full programming language expressiveness |
| **Pulumi** | Declarative, *general-purpose languages* (TS/Go/Python/Java/C#) | State (Pulumi service or self-managed) | Multi-cloud | You want loops/abstractions in a real language and to share code with app teams | Team prefers config-as-data simplicity; HCL ecosystem maturity needed |
| **AWS CloudFormation / CDK** | Declarative (CFN) / imperative-gen (CDK) | Managed by AWS | AWS-only | All-in on AWS, want native integration & no state file to manage | Multi-cloud, or you dislike CFN's verbosity/slow stacks |
| **Ansible** | Idempotent tasks | No central state | Config mgmt + some provisioning | Configuring inside machines, ad-hoc orchestration | Large declarative cloud-resource graphs (use TF) |
| **Crossplane** | Declarative, *Kubernetes CRDs* | In etcd, controller-reconciled | Multi-cloud via k8s | You want cloud resources managed *as Kubernetes objects* with GitOps | You don't run Kubernetes / don't want infra in etcd |

### 8.2 Helm vs. Kustomize vs. raw manifests

| Dimension | Helm | Kustomize | Raw `kubectl apply` |
|---|---|---|---|
| Mechanism | Go templating + values | Overlay/patch plain YAML | Apply static YAML |
| Parameterization | Rich (loops, conditionals, functions) | Limited (patches, substitutions) | None |
| Packaging/versioning | First-class (charts, repos, releases) | None (just files) | None |
| Rollback | Built-in (`helm rollback`) | Via Git revert + re-apply | Manual |
| Distribution to others | Excellent (public chart repos) | Poor | Poor |
| Readability/debuggability | Can degrade (template magic) | High (real YAML) | Highest |
| Learning curve | Higher (templates, merges, hooks) | Lower | Lowest |
| Use when… | Distributing software, complex parameterization, need release/rollback semantics | Your own apps, modest env differences, you value plain YAML | Tiny/throwaway setups |
| Avoid when… | Charts become unreadable template programs | You need loops/heavy logic/distribution | Anything non-trivial or multi-env |

**Pragmatic rule:** use **Helm to *consume* third-party software** (it's the distribution standard) and **Kustomize (or Helm + Kustomize post-render) for your *own* apps** if you value plain, lintable YAML. Many teams do `helm template | kustomize` to combine Helm's packaging with Kustomize's transparent patching, or use Argo CD which supports both.

### 8.3 State backend decision

- **Solo / throwaway**: local state is fine.
- **Any team**: remote + locking + versioning + encryption. On AWS: S3 + DynamoDB (or native lockfile). On Azure: Blob. On GCP: GCS. Want runs/policy/RBAC out of the box: Terraform Cloud/Enterprise (or Spacelift, env0, Scalr).

### 8.4 One-vs-many state files

Segment state by **blast radius and change frequency**: networking (changes rarely, high blast radius) separate from data (DBs) separate from apps (changes often). Smaller states = faster plans, smaller blast radius, less lock contention — at the cost of cross-state references (use `terraform_remote_state` data source or pass outputs explicitly).

---

## 9. Failure modes & debugging

### 9.1 Stuck/stale state lock

**Symptom:** `Error: Error acquiring the state lock ... Lock Info: ID: <uuid>, Operation: OperationTypeApply, Who: ...`.
**Cause:** a previous apply crashed or a CI job was killed mid-run and never released the lock (DynamoDB item / blob lease still held).
**Diagnose:** read the lock info (who/when/operation). Check whether a run is *actually* in progress (CI dashboard, other engineers).
**Fix:** if and only if no run is live: `terraform force-unlock <ID>`. **Never force-unlock during a live apply** — that re-enables concurrent writes and risks the exact corruption locking prevents.

### 9.2 Corrupted / lost state

**Symptom:** plans want to *create* resources that already exist, or *destroy* everything, or error on parse.
**Causes:** concurrent writes without locking, manual edits, a half-written state from an interrupted push, pointing the wrong config at the state (lineage mismatch).
**Diagnose:** `terraform state pull`, inspect `serial`/`lineage`; compare to S3 object versions.
**Fix:**
- Restore a prior version from **S3 bucket versioning** (your primary recovery path — this is *why* you enable versioning).
- Surgically repair with `terraform state rm` / `terraform import` to re-align state with reality.
- For "Terraform thinks it doesn't exist but it does": `terraform import` the real resource back into state.
- For "Terraform thinks it exists but it was deleted": `terraform state rm` to forget it.

### 9.3 Blast radius of a bad apply

**Symptom:** an innocuous-looking change shows `-/+` (replace) or `-` (destroy) on critical resources — e.g., changing a parameter that *forces replacement* on a database, or a `for_each` key change that destroys and recreates many resources.
**Causes:** changing a `ForceNew` attribute; switching `count`→`for_each` (re-indexes addresses); editing a name; a botched module refactor without `moved` blocks.
**Diagnose / prevent:**
- **Always read the plan** — count the `destroy`/`replace` lines before approving. CI should fail or require manual approval on any destroy of a protected resource.
- `prevent_destroy = true` on stateful resources turns a silent catastrophe into a hard error.
- Use `moved {}` blocks when refactoring addresses so Terraform *moves* rather than *destroys+creates*.
- Segment state so one bad apply can't take down unrelated systems.
**Real-world flavor:** the classic incident is an engineer running `terraform destroy` (or `apply` with a config that drifted) against prod believing it was a sandbox — wiping databases. Guards: separate prod credentials/state, `prevent_destroy`, plan review, and no `-auto-approve` for humans on prod.

### 9.4 Provider auth / throttling failures

**Symptom:** `NoCredentialProviders`, `AccessDenied`, `Throttling`, `RequestLimitExceeded`.
**Diagnose:** check the assumed role/credentials (`aws sts get-caller-identity`), IAM permissions, and `TF_LOG=DEBUG` (or `TRACE`) for the provider's API calls.
**Fix:** correct IAM/scopes; for throttling lower `-parallelism` and let provider backoff work.

### 9.5 Helm: failed upgrade leaves release "stuck"

**Symptom:** `helm list` shows status `pending-upgrade` / `failed`; further upgrades error with "another operation is in progress."
**Cause:** an upgrade crashed (or the operator Ctrl-C'd) mid-flight; the release record is in a non-terminal state.
**Diagnose:** `helm history <rel>`, `helm status <rel>`.
**Fix:** `helm rollback <rel> <last-good-rev>`; if truly stuck, you may need to delete the latest pending release Secret (`kubectl delete secret sh.helm.release.v1.<rel>.vN`) — surgical, last resort. Prevent with `--atomic` (auto-rollback) and `--cleanup-on-fail`.

### 9.6 Helm: Pods crash but Helm reports success

**Symptom:** `helm upgrade` exits 0 but the app is down.
**Cause:** without `--wait`, Helm only confirms the API accepted manifests, not that Pods became Ready.
**Fix:** add `--wait --timeout` and meaningful **readiness probes**; use `--atomic` so a non-ready rollout reverts.

### 9.7 Helm: indentation / templating bugs

**Symptom:** `error converting YAML to JSON: ... mapping values are not allowed`, or fields mysteriously absent.
**Diagnose:** `helm template ./chart --debug` and inspect the *rendered* YAML; `helm lint`.
**Fix:** correct `nindent`/`indent` usage, `toYaml` piping, and quoting (`| quote` for values that could be numbers/booleans, e.g. `"true"`, version strings).

### 9.8 Drift surprises

**Symptom:** plan proposes to revert a change someone made in the console.
**Diagnose:** `terraform plan -refresh-only` to see drift explicitly.
**Decide:** either re-apply to enforce code (revert the manual change), or codify the change into config, or `ignore_changes` if it's legitimately externally managed.

---

## 10. Interview drill

**Q1. What problem does declarative IaC solve, and how is it different from a shell script that calls cloud CLIs?**
Model answer: A shell script is imperative — it specifies *how* and re-running it re-executes everything, with no notion of current vs. desired state. Declarative IaC specifies the *desired end state*; the engine diffs against actual/recorded state and applies only the minimal changes (idempotent reconciliation). This gives reproducibility, code review, drift detection, and a single source of truth in version control.
- *Probe: How does the tool know what already exists?* Via recorded state (Terraform's state file) plus a refresh that reads reality through the provider.
- *Probe: Is Kubernetes YAML imperative or declarative?* Declarative — controllers reconcile to the desired spec.
- *Probe: Where do Ansible/Chef fit?* Idempotent config management (inside-the-box), complementary to Terraform's provisioning.

**Q2. Walk me through what `terraform apply` does internally.**
Model answer: Load/merge config and variables → (init already fetched backend/providers/modules) → refresh state from reality via provider Read → build a DAG of dependencies → compute the plan (create/update/replace/destroy/no-op) → acquire the state lock → walk the DAG in dependency order (parallelism 10), calling provider CRUD, persisting state after each op → release lock → compute outputs.
- *Probe: Why persist state incrementally?* So a mid-apply crash leaves a partially-updated (recoverable) state, not a totally lost one.
- *Probe: What forces a replace vs. update-in-place?* `ForceNew` attributes the API can't mutate (e.g., AMI, name) → `-/+`.
- *Probe: Why save the plan file?* To eliminate the TOCTOU gap — apply exactly what was reviewed.

**Q3. Explain Terraform state locking. Why does it matter and how is it implemented?**
Model answer: State is shared mutable global state; concurrent writes corrupt it. Locking takes an exclusive lock before any write. S3 backend historically used a DynamoDB conditional `PutItem` on `LockID` (atomic compare-and-set); Azure uses a blob lease; GCS an object lock; newer S3 supports a native lockfile. Without locking, two simultaneous applies produce a state that mismatches reality → wrong subsequent plans.
- *Probe: How do you fix a stuck lock?* `terraform force-unlock <ID>` — but only after confirming no live run.
- *Probe: Risk of force-unlock during a live apply?* Re-enables concurrent writes → the corruption the lock prevented.
- *Probe: How do you recover corrupted state?* Restore from S3 bucket versioning; surgically `state rm`/`import` to realign.

**Q4. What's in the Terraform state file and why is it sensitive?**
Model answer: JSON with `serial`, `lineage`, `outputs`, and a `resources[]` mapping config addresses → real IDs and all read attributes. It's sensitive because providers store *all* attributes including secrets (passwords, keys) in plaintext. Hence encrypt at rest, restrict access, never commit to Git.
- *Probe: What's `lineage`?* A UUID guarding against pointing unrelated configs at the same state.
- *Probe: Does `terraform state rm` delete the real resource?* No — it only forgets it (stops managing).

**Q5. `count` vs `for_each` — when and why?**
Model answer: `count` indexes by position; removing a middle element renumbers and destroys/recreates unrelated resources. `for_each` keys by a stable map/set key, so add/remove of one element doesn't disturb others. Use `for_each` for collections of distinct things; `count` only for simple on/off (`count = var.enabled ? 1 : 0`) or truly identical N copies.
- *Probe: What breaks if you switch count→for_each?* Resource addresses change (`[0]` → `["key"]`), causing destroy/recreate unless you use `moved {}` blocks.

**Q6. (Senior signal) When would you NOT use Terraform workspaces for environments, and what would you do instead?**
Model answer: Avoid workspaces for strongly-isolated prod because all workspaces share one backend/credentials (weak isolation, easy to target the wrong one) and force `terraform.workspace` conditionals. Prefer directory-per-environment with separate backends/credentials (often via Terragrunt) — real isolation and independent blast radius. Workspaces are fine for ephemeral, identical, low-isolation copies (per-PR previews).
- *Probe: What's the blast-radius argument?* Separate states/credentials mean a bad apply in dev can't touch prod.

**Q7. Helm 2 vs Helm 3 — what changed and why does it matter?**
Model answer: Helm 2 had Tiller, a cluster-side component with broad permissions — a security liability and an extra moving part. Helm 3 removed Tiller; the client uses your kubeconfig RBAC directly and stores releases as Secrets in the namespace. Helm 3 also moved to a three-way strategic merge (vs two-way), so it can reconcile out-of-band changes and delete removed resources.
- *Probe: Why is the three-way merge better?* It considers old manifest, new manifest, and live state, so it can remove resources dropped from the chart and not clobber external changes.
- *Probe: Security implication of no Tiller?* You can only do what your RBAC allows — least privilege.

**Q8. How does `helm rollback` work, and what does it do to revision numbers?**
Model answer: Helm reads the stored manifest for the target revision from its release Secrets, computes a patch from current live state to that manifest, and applies it — creating a *new* revision that's a copy of the old desired state. Revision numbers always move forward; history shows the rollback as a new entry.
- *Probe: How many revisions are kept?* Default `--history-max` is 10.
- *Probe: How do you make upgrades safe in CI?* `--atomic --wait --timeout`, plus `helm diff` preview.

**Q9. (Senior signal) Helm vs Kustomize — how do you decide?**
Model answer: Helm is a package manager (templating, values, versioned releases, repos, rollback) and is the distribution standard for third-party software. Kustomize is template-free overlay/patching of plain YAML — transparent and lintable but no loops/packaging. Rule of thumb: consume third-party software via Helm; manage your own apps with Kustomize (or Helm) depending on whether you value plain YAML over rich parameterization. They compose (`helm template | kustomize`, or Argo CD supporting both).
- *Probe: Main Helm critique?* Go-templating a whitespace-sensitive format is fragile and can hide too much.
- *Probe: Kustomize limitation?* No loops/conditionals/packaging → duplication for highly variable configs.

**Q10. (Senior signal) Describe a bad-apply blast-radius incident and how you'd prevent it.**
Model answer: Classic: an engineer runs `apply`/`destroy` against prod thinking it's a sandbox, or a config change that forces replacement quietly destroys a database. Prevention layers: separate prod state/credentials, `prevent_destroy` on stateful resources, mandatory plan review (count destroys/replaces), `moved {}` for refactors, state segmentation to bound blast radius, no `-auto-approve` for humans on prod, and policy-as-code gates.
- *Probe: What plan symbol signals danger?* `-/+` (replace) and `-` (destroy) on critical resources.
- *Probe: How does saving the plan help?* You apply exactly the reviewed diff (no TOCTOU surprise).

**Q11. How do you handle secrets across Terraform and Helm?**
Model answer: Never commit plaintext; remember Terraform state stores secrets in plaintext (encrypt at rest, restrict access, mark `sensitive`). For Kubernetes, don't bake secrets into charts/values — use External Secrets Operator/Vault to sync from a secret manager, or SOPS/helm-secrets to keep KMS-encrypted (reviewable) values in Git, decrypted at apply.
- *Probe: Does `sensitive = true` keep it out of state?* No — only out of CLI output; it's still in state.

**Q12. How do you detect and respond to drift?**
Model answer: Scheduled `terraform plan -detailed-exitcode` (exit 2 = changes) wired to alerts; `plan -refresh-only` to review drift explicitly. Then decide: re-apply to enforce code, codify the change, or `ignore_changes` if externally managed. With GitOps (Argo CD/Flux) the controller continuously reconciles and reverts drift automatically.
- *Probe: When is drift acceptable?* When an external system legitimately owns an attribute (autoscaling counts, tags) — use `ignore_changes`.

---

## 11. Glossary

- **Ansible** — agentless config-management tool running idempotent YAML "playbooks" over SSH; complements Terraform.
- **Apply** — Terraform phase that executes a plan, mutating reality and updating state.
- **Argo CD / Flux** — Kubernetes controllers that continuously reconcile the cluster to Git-declared desired state (GitOps).
- **Atomic (`--atomic`)** — Helm flag that auto-rolls-back a failed install/upgrade.
- **Backend (Terraform)** — where state is stored and how operations run (local, S3, GCS, Azure Blob, Terraform Cloud).
- **Blast radius** — the scope of resources a single change can affect or destroy.
- **BSL (Business Source License)** — the source-available license HashiCorp adopted for Terraform in 2023; prompted the OpenTofu fork.
- **Chart** — Helm's packaging unit: templates + values + metadata describing a set of Kubernetes resources.
- **ClickOps** — making infrastructure changes manually in a console; the antithesis of IaC; causes drift.
- **CRD (Custom Resource Definition)** — extends Kubernetes with custom object types; Helm installs but doesn't upgrade them.
- **DAG (Directed Acyclic Graph)** — Terraform's dependency graph; guarantees a valid build order.
- **Data source** — a read-only Terraform lookup of something it doesn't manage.
- **Declarative** — describing desired end state (vs. imperative step-by-step instructions).
- **Drift** — divergence between declared (code/state) and actual infrastructure.
- **DynamoDB** — AWS NoSQL DB; its conditional writes implement Terraform S3-backend locking.
- **etcd** — the key-value store backing the Kubernetes API; has a per-object size limit (~1.5MiB).
- **External Secrets Operator (ESO)** — syncs secrets from a manager (AWS SM/Vault) into Kubernetes Secrets.
- **GitOps** — operating model where Git is the source of truth and a controller continuously reconciles.
- **Golden image** — a pre-baked, immutable machine/container image deployed unchanged.
- **HCL (HashiCorp Configuration Language)** — Terraform's declarative DSL.
- **Helm** — the Kubernetes package manager (charts, releases, upgrades, rollbacks).
- **Hook (Helm)** — a Job run at a lifecycle point (pre-install, post-upgrade, test) via annotations.
- **Idempotency** — applying an operation multiple times yields the same result as once.
- **Immutable infrastructure** — never modify deployed artifacts in place; replace with new ones.
- **Import** — bringing an existing real resource under Terraform management.
- **Kubeconfig** — file defining clusters/credentials/contexts for kubectl/Helm.
- **Kubernetes** — container orchestration platform with a reconcile-to-desired-state model.
- **Kustomize** — template-free Kubernetes config management via base + overlay patches.
- **Lifecycle (block)** — Terraform meta-args: `prevent_destroy`, `create_before_destroy`, `ignore_changes`, `replace_triggered_by`.
- **Lineage** — UUID in state identifying its ancestry; prevents cross-config state mix-ups.
- **Lock (state)** — exclusive lock preventing concurrent state writes.
- **Module** — reusable, parameterized bundle of Terraform resources.
- **`moved {}` block** — declares a resource address rename so Terraform moves rather than recreates.
- **OCI registry** — container-image registry now also used to host Helm charts.
- **OPA / Conftest / Sentinel / Rego** — policy-as-code engines/languages to enforce IaC rules in CI.
- **OpenTofu** — the open-source (Linux Foundation) fork of Terraform after the BSL change.
- **Packer** — HashiCorp tool to build immutable machine/container images.
- **Parallelism** — number of concurrent resource operations (Terraform default 10).
- **Plan** — the computed diff (create/update/replace/destroy/no-op) between desired and actual state.
- **Pod** — smallest deployable Kubernetes unit; one or more co-located containers.
- **Provider** — Terraform plugin implementing CRUD/schema for one platform's API.
- **Provisioner** — Terraform escape hatch to run scripts post-create; a last resort.
- **RBAC** — Kubernetes role-based authorization; Helm 3 uses the caller's RBAC.
- **Refresh** — reading reality through providers to update in-memory state (drift detection).
- **Release (Helm)** — a named, revisioned installation of a chart into a cluster.
- **Replace (`-/+`)** — destroy-then-create forced by a `ForceNew` attribute change.
- **Serial** — monotonically increasing state-version counter; detects stale writes.
- **Sprig** — function library bundled into Helm's Go templating.
- **State (Terraform)** — recorded mapping of config resources to real-world IDs/attributes.
- **State segmentation** — splitting state by blast radius/change frequency.
- **Strategic merge patch** — Kubernetes-aware patch that merges lists by key.
- **Terragrunt** — DRY wrapper around Terraform for many-small-states layouts.
- **Three-way merge (Helm)** — patch computed from old manifest, new manifest, and live state.
- **Tiller** — Helm 2's removed cluster-side component (security liability).
- **TOCTOU** — Time-Of-Check-to-Time-Of-Use race; mitigated by applying a saved plan.
- **Values (Helm)** — parameters filling chart templates; layered by precedence.
- **Workspace (Terraform)** — named separate state within one config/backend; weak env isolation.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Mental model:** declarative IaC = control loop over `diff(desired, actual)`. Three pillars: desired state in Git, faithful recorded state, reconciliation engine computing the minimal diff. Incidents come from these getting out of sync (drift, state corruption, bad-diff blast radius).

**Terraform lifecycle:** `init` (backend+providers+modules, writes `.terraform.lock.hcl`) → refresh → build DAG → `plan` (`+`/`~`/`-`/`-/+`) → `apply` (lock → walk DAG, parallelism **10**, persist state per-op → unlock) → outputs. Saved plan (`-out`) closes TOCTOU.

**State:** JSON with `serial`, `lineage`, `resources[]`; stores secrets in **plaintext** → encrypt, restrict, never commit. Recover via **S3 versioning**; realign with `state rm`/`import`.

**Locking:** required for teams. S3→DynamoDB `LockID` (or native lockfile); Azure→blob lease; GCS→object lock. Stuck lock → `force-unlock <ID>` **only if no live run**.

**Safety knobs:** `prevent_destroy`, `create_before_destroy`, `ignore_changes`, `moved {}`, `import {}`, prefer **`for_each` over `count`**, segment state by blast radius, `-target` is emergency-only.

**Helm:** chart (`Chart.yaml`/`values.yaml`/`templates/`/`crds/`) → render (Go template + Sprig) → apply → release stored as **Secret**, revisioned. Upgrade = **three-way merge** (old/new/live). `rollback` makes a new revision restoring an old one. History default **10**. CI: `--atomic --wait --timeout`, `helm diff`. Helm 3 = no Tiller, uses your RBAC. CRDs installed once, not upgraded by Helm.

**Kustomize:** base + overlay patches, plain YAML, no templating; `kubectl apply -k`. Use Helm to consume third-party software; Kustomize for your own apps.

**Secrets:** ESO/Vault (sync from manager) or SOPS/helm-secrets (KMS-encrypted in Git). Never plaintext; remember TF state leaks secrets.

**Testing:** `fmt -check` / `validate` / `helm lint` → tfsec/Checkov/Conftest → `terraform test` / `--dry-run` → Terratest → plan-as-PR + Infracost.

**Key numbers:** parallelism 10; Helm `--timeout` 5m; `--history-max` 10; plan exit code **2** = changes; etcd object ~1.5MiB.

### 12.2 Self-test (no answers — active recall)

1. Trace `terraform apply` from config load to outputs, naming where the state lock is acquired/released and what gets persisted when.
2. Your colleague's CI job crashed mid-apply; the next run errors with "Error acquiring the state lock." Exactly what do you check, and what do you run — and what's the danger if you skip the check?
3. A one-line change to an RDS parameter shows `-/+` in the plan. Explain what that symbol means, why it happened, and three independent guards that would have prevented data loss.
4. Compare Helm and Kustomize across packaging, parameterization, rollback, and debuggability; then give your rule for choosing between them and how they can be combined.
5. Explain Helm's three-way merge: what three inputs feed it, what problem it solves that Helm 2's two-way merge could not, and how CRDs are exceptional.
6. Why do most practitioners avoid Terraform workspaces for prod, and what layout do they use instead? What are workspaces actually good for?
7. Where do secrets end up in Terraform and Helm by default, and what two concrete patterns keep them out of plaintext in Git?

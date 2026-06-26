# Config & Secrets in Kubernetes

*An exhaustive engineering-handbook chapter for senior backend developers (Java/JVM ecosystem) who want to fully master configuration and secret management on Kubernetes — from first principles to deep internals, production operations, and interviews.*

---

## 1. Overview & where it fits

### What it is

Every non-trivial application needs **configuration** (database URLs, feature flags, timeouts, log levels, third-party endpoints) and **secrets** (passwords, API keys, TLS private keys, OAuth client secrets, signing keys). In Kubernetes, the two first-class API objects that hold this data are:

- **`ConfigMap`** — a key/value store for *non-sensitive* configuration data.
- **`Secret`** — a key/value store intended for *sensitive* data, with a few extra (mostly cosmetic) safety behaviors.

Both are namespaced API objects stored in the cluster's backing store (**etcd** — a distributed key/value database that holds the entire desired state of a Kubernetes cluster; explained in §2). Pods consume them in one of three ways: as **environment variables**, as **command-line arguments**, or as **files mounted into the container's filesystem** (via a *volume*).

### The problem it solves

The core problem is **separation of configuration from code**, a principle codified by the **Twelve-Factor App** methodology (a widely cited set of guidelines for building cloud-native, portable services; covered in detail in §2 and §6). If you bake a database password into your container image or `application.properties`, then:

- The same image cannot be promoted unchanged from `dev` → `staging` → `prod` (it would carry the wrong config).
- Rotating a credential requires rebuilding and redeploying the image.
- Secrets leak into image registries, version control, and CI logs.

ConfigMaps and Secrets let you build **one immutable image** and inject environment-specific values at deploy/run time. That is the entire point.

### When you reach for it

| Need | Reach for |
|---|---|
| Non-sensitive app config that varies per environment | **ConfigMap** |
| Small sensitive blobs (passwords, tokens, keys) managed in-cluster | **Secret** (ideally with encryption-at-rest + RBAC) |
| Secrets that must live in a dedicated vault, be audited, and auto-rotate | **External secret manager** (HashiCorp Vault, AWS Secrets Manager, etc.) surfaced via **External Secrets Operator** or a **CSI Secrets Store driver** |
| Config that should never change for the life of a Deployment revision | **Immutable ConfigMap/Secret** |

### One-paragraph mental model

Think of a ConfigMap or Secret as a small, namespaced bag of named byte blobs living in etcd. A Pod *references* that bag and projects it either as environment variables (read once, at container start — static) or as files on a tmpfs/volume (which the kubelet keeps roughly in sync — semi-dynamic). A Secret is **not encrypted by default** — it is merely base64-encoded in the API and stored as-is in etcd, so its "secrecy" comes entirely from etcd encryption-at-rest, RBAC, and node-level controls that *you* must configure. For serious secret hygiene, you don't store the real secret in Kubernetes at all; you store a *pointer* to an external vault and let an operator or CSI driver fetch, sync, and rotate it.

---

## 2. Foundations from first principles

Let's build the vocabulary from zero. If you already know Kubernetes basics, skim — but several of these terms are load-bearing for the rest of the chapter.

### 2.1 Kubernetes objects and the desired-state model

Kubernetes is a **declarative** system: you describe the *desired state* of the world in YAML/JSON objects, submit them to the **API server**, and **controllers** continuously work to make the *actual state* match. Every object has:

- `apiVersion` — which API group/version defines this kind of object (e.g. `v1`).
- `kind` — the object type (e.g. `ConfigMap`, `Secret`, `Pod`).
- `metadata` — name, namespace, labels, annotations.
- `spec` and/or `data` — the payload.

**Namespace:** a virtual cluster-within-a-cluster used to scope and isolate objects. A ConfigMap in namespace `payments` is invisible to Pods in namespace `search` unless explicitly copied. Names must be unique *within* a namespace, not across the cluster.

### 2.2 etcd — the backing store

**etcd** is a strongly-consistent, distributed key/value store (it uses the **Raft** consensus algorithm — see below) that holds *all* Kubernetes state, including every ConfigMap and Secret. When you `kubectl apply` a Secret, the API server serializes it and writes it to etcd. **Crucial consequence:** anyone (or any process) that can read etcd directly — e.g. a stolen etcd backup, a compromised control-plane node, or a misconfigured etcd port — can read every Secret in the cluster *unless etcd is encrypted at rest*. This is the single most important security fact in this chapter.

**Raft:** a consensus algorithm that lets a cluster of machines agree on an ordered log of changes even if some machines fail. etcd uses it so that a majority (quorum) of etcd members must acknowledge a write before it's considered committed. You don't operate Raft directly, but it's why etcd needs an odd number of members (3, 5) and why losing quorum freezes the control plane.

### 2.3 Base64 — encoding, not encryption

**Base64** is a reversible *encoding* that maps arbitrary bytes onto 64 printable ASCII characters (`A–Z`, `a–z`, `0–9`, `+`, `/`, with `=` padding). It exists so that binary data (e.g. a TLS private key with embedded null bytes) can be carried inside text-based YAML/JSON. It provides **zero confidentiality**: `echo 'cGFzc3dvcmQ=' | base64 -d` instantly yields `password`. The number one beginner misconception is that base64 in a Secret means it's "encrypted." It is not. It is obfuscation visible to anyone with `kubectl`.

### 2.4 Environment variables vs mounted files

A **container** runs as a Linux process. Two classic ways to feed it config:

- **Environment variables (env vars):** key/value pairs in the process's environment, set *once* when the process starts (`execve`). A running process cannot have its environment changed from outside; to pick up new values it must restart. Hence env-injected config is **static for the life of the process**.
- **Files:** Kubernetes can project ConfigMap/Secret keys as files inside the container (each key becomes a filename, each value becomes the file content). The application reads the file. Because the kubelet can update those files in place, file-mounted config can be **reloaded without restarting the Pod** — *if the app re-reads the file*. This live-reload difference is central (detailed in §3.4).

### 2.5 Volumes, tmpfs, and projected volumes

A **volume** in Kubernetes is a directory accessible to containers in a Pod, with a lifetime tied to the Pod. ConfigMaps and Secrets are exposed via special volume types:

- A **`configMap` volume** mounts a ConfigMap's keys as files.
- A **`secret` volume** mounts a Secret's keys as files, backed by **tmpfs** — a RAM-backed filesystem, so Secret contents never touch the node's disk (reducing the chance of leaking onto persistent storage or swap). *(Verify on your distro: kubelet uses tmpfs for secret volumes; exact behavior can vary with `--feature-gates` and node config.)*
- A **`projected` volume** combines multiple sources (ConfigMaps, Secrets, the ServiceAccount token, downward API) into one directory tree.

### 2.6 ServiceAccounts, RBAC, and the principle of least privilege

A **ServiceAccount (SA)** is an identity for processes running in Pods. By default every Pod gets a SA token.

**RBAC (Role-Based Access Control):** Kubernetes' authorization system. You grant *subjects* (users, groups, ServiceAccounts) permission to perform *verbs* (`get`, `list`, `watch`, `create`, `update`, `delete`) on *resources* (e.g. `secrets`) via **Role** (namespaced) or **ClusterRole** (cluster-wide) objects, bound with **RoleBinding**/**ClusterRoleBinding**. The relevance to secrets is enormous: anyone who can `get`/`list` Secrets in a namespace can read every credential in it. Locking this down is the difference between a contained breach and a cluster-wide one (see §6.4).

### 2.7 The Twelve-Factor config principle

**Twelve-Factor App** (Factor III, "Config") states: *store config in the environment*, strictly separated from code, so the same build artifact runs in every environment by varying only the config. The canonical mechanism is environment variables, but mounted files satisfy the spirit equally. Kubernetes ConfigMaps/Secrets are the platform-native implementation of this factor. The corollary: **no secrets or environment-specific values in the image or in source control**.

### 2.8 Encryption at rest

**Encryption at rest** means data on disk (here, etcd's data) is stored encrypted so that physical/file-level access to the disk doesn't reveal plaintext. Kubernetes supports it via an **EncryptionConfiguration** that tells the API server to encrypt specified resources (notably `secrets`) before writing to etcd, using providers like `aescbc`, `aesgcm`, `secretbox`, or a **KMS provider** (Key Management Service — a cloud or HSM-backed key service that holds the master key, so the encryption key itself is never on the API server disk). This is *off by default* in vanilla Kubernetes (managed providers vary). Enabling it is mandatory hardening (see §6.4).

### 2.9 Operators and CRDs

An **Operator** is a custom controller that extends Kubernetes with domain logic, watching **Custom Resource Definitions (CRDs)** — user-defined object kinds. The **External Secrets Operator (ESO)** introduces CRDs like `ExternalSecret` and `SecretStore` that say "fetch this value from Vault/AWS and materialize it as a native Secret." This is how external vaults integrate cleanly (see §3.6 and §5.5).

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle: object creation → storage → consumption → updates → deletion, plus the env-vs-file divergence.

### 3.1 ConfigMap object shape and storage path

A ConfigMap has two data fields:

- `data` — UTF-8 string key/value pairs (human-readable config).
- `binaryData` — base64-encoded binary blobs (for non-UTF-8 content).

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: payments
data:
  LOG_LEVEL: "INFO"
  application.properties: |          # a multi-line value = a whole file
    server.port=8080
    spring.datasource.maxPoolSize=20
binaryData:
  truststore.jks: <base64-of-binary>
```

**Internal write path, step by step:**

1. `kubectl apply` sends an HTTP `POST`/`PATCH` to the **API server**.
2. The request passes through **authentication** (who are you?), **authorization** (RBAC: may you create ConfigMaps here?), and **admission controllers** (mutating/validating webhooks that can reject or modify the object — e.g. an OPA/Gatekeeper policy forbidding plaintext secrets).
3. The API server validates the schema. A ConfigMap's total size is capped at **1 MiB** (1,048,576 bytes) — etcd's per-value limit drives this. Exceed it and creation is rejected.
4. The object is serialized (protobuf internally) and written to **etcd** under a key like `/registry/configmaps/payments/app-config`. ConfigMaps are stored **as-is, unencrypted** (they're not sensitive).
5. The write returns success and the object gets a `resourceVersion` (a monotonically increasing token used for optimistic concurrency and watches).

### 3.2 Secret object shape and storage path

A Secret looks almost identical but uses:

- `data` — values must be **base64-encoded** strings.
- `stringData` — a write-only convenience field where you put plaintext; the API server base64-encodes it into `data` for you (handy in YAML; never appears on read-back).
- `type` — a hint string influencing validation/consumption.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-credentials
  namespace: payments
type: Opaque
stringData:                 # plaintext here; server encodes into data
  DB_USER: "payments_app"
  DB_PASSWORD: "s3cr3t!"
```

**Common Secret `type` values:**

| `type` | Required keys / meaning |
|---|---|
| `Opaque` | Arbitrary user data (default). |
| `kubernetes.io/service-account-token` | A SA token (legacy auto-generated form). |
| `kubernetes.io/dockerconfigjson` | Registry pull credentials; key `.dockerconfigjson`. |
| `kubernetes.io/basic-auth` | Keys `username`, `password`. |
| `kubernetes.io/ssh-auth` | Key `ssh-privatekey`. |
| `kubernetes.io/tls` | Keys `tls.crt`, `tls.key` (used by Ingress, etc.). |
| `bootstrap.kubernetes.io/token` | Cluster bootstrap tokens. |

**Internal write path differences from ConfigMap:**

1–3. Same auth/authz/admission/validation flow. Secrets also have a **1 MiB** size cap.
4. **If an `EncryptionConfiguration` covers `secrets`**, the API server passes the serialized object through the configured **encryption provider** (e.g. KMS → returns ciphertext + a key reference) *before* writing to etcd. The etcd value is then ciphertext prefixed with the provider name (e.g. `k8s:enc:kms:v2:...`). **If not configured, the Secret is written base64-as-text, effectively plaintext.**
5. On read, the API server transparently decrypts (tries providers in order) and returns base64 `data` to the client.

> **Critical internal fact:** base64 is *transport encoding inside the object*; encryption-at-rest is a *separate, optional* layer on the etcd write. Without the latter, a Secret in etcd is one `base64 -d` away from plaintext.

### 3.3 Consumption path: from object to running container

When the **scheduler** assigns a Pod to a node, the **kubelet** (the node agent that manages container lifecycles) materializes referenced ConfigMaps/Secrets. There are three projection modes.

#### Mode A — Environment variables from individual keys (`valueFrom`)

```yaml
spec:
  containers:
  - name: app
    image: myorg/app:1.4.2
    env:
    - name: LOG_LEVEL
      valueFrom:
        configMapKeyRef: { name: app-config, key: LOG_LEVEL }
    - name: DB_PASSWORD
      valueFrom:
        secretKeyRef: { name: db-credentials, key: DB_PASSWORD }
```

**Step by step:**
1. kubelet fetches `app-config` and `db-credentials` from the API server.
2. It extracts the named keys, decodes Secret base64 to raw bytes.
3. It builds the container's environment block and calls the container runtime (containerd/CRI-O) to `create` + `start` the container with that environment.
4. The values are now **frozen** in the process. Updating the ConfigMap/Secret later does **not** change the running process's env. *(If the referenced key doesn't exist and you didn't mark it `optional: true`, the container fails to start.)*

#### Mode B — Environment variables from all keys (`envFrom`)

```yaml
    envFrom:
    - configMapRef: { name: app-config }
    - secretRef: { name: db-credentials }
      prefix: DB_           # optional: prefixes every key
```

Same freezing semantics; every key becomes an env var. Keys that aren't valid env var names (e.g. `application.properties`) are skipped with an event warning.

#### Mode C — Mounted files (volumes)

```yaml
spec:
  volumes:
  - name: cfg
    configMap:
      name: app-config
      items:                          # optional: project only some keys
      - key: application.properties
        path: application.properties
  - name: secrets
    secret:
      secretName: db-credentials
      defaultMode: 0400               # file perms; restrict secrets!
  containers:
  - name: app
    volumeMounts:
    - name: cfg
      mountPath: /etc/app/config
      readOnly: true
    - name: secrets
      mountPath: /etc/app/secrets
      readOnly: true
```

**Step by step:**
1. kubelet fetches the objects and **writes each key as a file** under a per-mount directory.
2. For Secrets, the backing store is **tmpfs** (RAM), so contents don't hit node disk.
3. **Atomic-swap mechanism:** kubelet doesn't edit files in place. It writes the new content into a *timestamped hidden directory* (`..2024_06_24_...`) and atomically flips a symlink (`..data`) to point at it. The visible files are symlinks into `..data`. This guarantees readers never see a half-written file — they see the old version or the new version, never a mix.
4. The container reads files normally.

### 3.4 The live-reload divergence (the most-tested concept)

| Aspect | Env vars | Mounted files |
|---|---|---|
| When set | Once, at container start | Continuously synced |
| Picks up updates without Pod restart? | **No** | **Yes** (file content changes) |
| App must do what to use new value? | Restart process | Re-read the file |
| Propagation delay after object update | N/A (never) | Up to **kubelet sync period + cache TTL** (often ~1 min total; see below) |
| Atomicity | N/A | Atomic per-mount via symlink swap |

**Propagation delay internals:** kubelet keeps a local cache of ConfigMaps/Secrets. The default strategy (`ConfigMapAndSecretChangeDetectionStrategy: Watch`) uses **watches** (push notifications) so updates arrive quickly, then the kubelet re-projects volumes on its periodic sync (`--sync-frequency`, default **1 minute**). Older/alternate strategies (`Cache` with a TTL, or `Get`) can add up to ~1 minute of staleness. **Net effect:** expect file updates to appear within roughly one minute, not instantly. *(Exact timing is version- and config-dependent — flag this in any design.)*

**Two huge caveats:**

1. **`subPath` mounts do NOT get updates.** If you mount a single key using `subPath` (to drop one file into an existing directory without hiding siblings), kubelet writes it once and never updates it. This is a frequent production surprise.
2. **The app must re-read the file.** Kubernetes updates the *bytes on disk*; it does not signal your process. Spring Boot, for example, does not automatically reload `application.properties` unless you use Spring Cloud Kubernetes or a config-reload sidecar/watcher. Live-reload is only "live" if your application (or a sidecar) watches the file (e.g. via `inotify`) and reloads.

### 3.5 Triggering rolling restarts when config changes (the env-var workaround)

Because env-injected config is frozen, the standard pattern to roll out a config change is to force the Deployment to create new Pods. Three idioms:

1. **`kubectl rollout restart deployment/app`** — bumps a `restartedAt` annotation, triggering a rolling update.
2. **Checksum annotation** — put a hash of the ConfigMap/Secret into the Pod template annotations; when the content changes, the hash changes, the Pod template changes, and the Deployment rolls.
   ```yaml
   spec:
     template:
       metadata:
         annotations:
           checksum/config: "{{ sha256 of configmap data }}"  # Helm: {{ include ... | sha256sum }}
   ```
3. **Immutable, versioned objects** — create `app-config-v2`, point the Deployment at it; the template change triggers a roll, and you keep `v1` for instant rollback (see §3.7). Tools like **Kustomize** automate this with a **`configMapGenerator`** that appends a content hash suffix to the name (`app-config-7f8a9c`), making every change a new immutable object and an automatic rollout. This is arguably the cleanest GitOps pattern.

### 3.6 External secret flow (ESO and CSI driver)

**External Secrets Operator (ESO) flow:**
1. You install ESO (a controller) and create a `SecretStore`/`ClusterSecretStore` describing how to auth to the backend (Vault/AWS/GCP/Azure).
2. You create an `ExternalSecret` CRD mapping backend paths to keys.
3. ESO's controller periodically (per `refreshInterval`) calls the backend API, fetches values, and **creates/updates a native Kubernetes Secret**.
4. Pods consume that native Secret normally (env or file). On rotation, ESO rewrites the Secret; combine with a reloader (§9) to restart Pods.

**CSI Secrets Store driver flow:**
1. Install the **Secrets Store CSI Driver** plus a provider plugin (Vault/AWS/GCP/Azure).
2. Define a `SecretProviderClass` listing which secrets to fetch.
3. A Pod references it via a `csi` volume of driver `secrets-store.csi.k8s.io`.
4. At Pod start, the driver **fetches secrets directly from the vault and mounts them as files** — the secret may never become a Kubernetes Secret object at all (it lives only on the Pod's tmpfs), shrinking the blast radius. Optionally it can also sync to a K8s Secret for env-var use. It supports **rotation** via a rotation-reconciler that re-fetches on an interval.

The key architectural distinction: **ESO copies the secret into etcd as a native Secret; CSI driver can keep it out of etcd entirely.**

### 3.7 Immutable ConfigMaps/Secrets

Set `immutable: true`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: app-config-v2 }
immutable: true
data: { LOG_LEVEL: "DEBUG" }
```

**Internals & benefits:**
- The API server rejects any update to `data`/`binaryData` (you must delete and recreate, ideally under a new name).
- **Performance:** kubelets normally **watch** every mounted ConfigMap/Secret for changes. Immutable objects tell the kubelet it can **stop watching**, drastically cutting API server load and watch traffic in clusters with thousands of Pods. This is the primary reason it exists.
- **Safety:** prevents accidental edits that would silently change behavior on the next volume sync or Pod restart.
- Pairs naturally with the versioned-name pattern (§3.5): each revision is a new immutable object, giving you atomic rollout and trivial rollback.

### 3.8 Deletion and lifecycle edges

- Deleting a ConfigMap/Secret that is *mounted* by running Pods: the Pods keep running with the last-synced content (volumes are already materialized), but **new Pods will fail to start** if the reference is required. This causes confusing "works on old pods, fails on new pods" incidents.
- `optional: true` on a ref lets Pods start even if the object/key is missing (the env var/file is simply absent). Use deliberately.
- ConfigMaps/Secrets are **namespaced and not automatically copied** across namespaces. Multi-tenant or multi-namespace apps need replication (e.g. ESO, kubed/reflector, or GitOps).

---

## 4. The complete toolkit

### 4.1 Object fields (ConfigMap & Secret)

| Field | Object | Purpose | Default |
|---|---|---|---|
| `data` | both | UTF-8 (CM) / base64 (Secret) key-value payload | empty |
| `binaryData` | ConfigMap | base64 binary values | empty |
| `stringData` | Secret | write-only plaintext, server-encoded into `data` | empty |
| `type` | Secret | consumption/validation hint | `Opaque` |
| `immutable` | both | forbid data updates; stop kubelet watches | `false` |
| `metadata.namespace` | both | scope | current ns |

### 4.2 Pod-side consumption fields

| Field | Where | Purpose | Notes / default |
|---|---|---|---|
| `env[].valueFrom.configMapKeyRef` | container | single CM key → env var | freezes at start |
| `env[].valueFrom.secretKeyRef` | container | single Secret key → env var | freezes at start |
| `envFrom[].configMapRef` / `secretRef` | container | all keys → env vars | invalid names skipped |
| `envFrom[].prefix` | container | prefix all injected names | none |
| `...KeyRef.optional` | container | don't fail if missing | `false` |
| `volumes[].configMap` / `.secret` | pod | mount as files | — |
| `.items[]` | volume | project subset of keys, rename paths | all keys |
| `.defaultMode` | volume | octal file perms | CM `0644`, Secret `0644` (set `0400`/`0440`) |
| `.optional` | volume | mount empty if missing | `false` |
| `volumeMounts[].subPath` | container | single-file mount | **no live updates** |
| `volumeMounts[].readOnly` | container | mount read-only | recommended `true` |

### 4.3 kubectl commands

| Command | Purpose |
|---|---|
| `kubectl create configmap NAME --from-literal=k=v` | CM from inline literals |
| `kubectl create configmap NAME --from-file=path` | CM from file(s) (filename→key) |
| `kubectl create configmap NAME --from-env-file=app.env` | CM from a dotenv file |
| `kubectl create secret generic NAME --from-literal=...` | Opaque secret |
| `kubectl create secret tls NAME --cert=c.pem --key=k.pem` | TLS secret |
| `kubectl create secret docker-registry NAME --docker-server=... --docker-username=... --docker-password=...` | image pull secret |
| `kubectl get secret NAME -o jsonpath='{.data.DB_PASSWORD}' \| base64 -d` | read a secret value |
| `kubectl describe configmap NAME` | view CM (Secrets show `<n> bytes`, not values) |
| `kubectl create configmap NAME ... --dry-run=client -o yaml` | generate YAML without applying |
| `kubectl rollout restart deployment/app` | force pods to pick up new env config |
| `kubectl edit secret NAME` | in-place edit (values shown base64) |

**Note:** `kubectl create ... --dry-run=client -o yaml | kubectl apply -f -` is the idiomatic way to make declarative, re-appliable manifests.

### 4.4 Encryption-at-rest configuration

`EncryptionConfiguration` (passed to API server via `--encryption-provider-config`):

```yaml
apiVersion: apiserver.config.k8s.io/v1
kind: EncryptionConfiguration
resources:
- resources: ["secrets"]              # also consider configmaps if they hold sensitive data
  providers:
  - kms:                              # preferred: external KMS holds the master key
      apiVersion: v2
      name: my-kms
      endpoint: unix:///var/run/kms-plugin.sock
  - aescbc:                           # fallback local key (less ideal)
      keys:
      - name: key1
        secret: <base64-32-byte-key>
  - identity: {}                      # plaintext; MUST be last for read compatibility
```

| Provider | Algorithm | Notes |
|---|---|---|
| `identity` | none | no encryption; default behavior; keep last for decrypt fallback |
| `secretbox` | XSalsa20+Poly1305 | fast, local key |
| `aescbc` | AES-CBC + PKCS#7 | local key; weaker than GCM, deprecated lean to KMS/GCM |
| `aesgcm` | AES-GCM | must rotate keys frequently (nonce reuse risk) — generally use via KMS |
| `kms` (v1/v2) | envelope encryption | best practice; key in cloud KMS/HSM; v2 adds performance + key hierarchy |

After enabling, re-encrypt existing secrets: `kubectl get secrets -A -o json | kubectl replace -f -`.

### 4.5 External-secret tooling

| Tool | What it is | Mechanism |
|---|---|---|
| **HashiCorp Vault** | Dedicated secrets manager with dynamic secrets, leasing, audit | Backend store |
| **External Secrets Operator (ESO)** | Operator syncing external secrets → native K8s Secrets | CRDs: `ExternalSecret`, `SecretStore`, `ClusterSecretStore`, `PushSecret` |
| **Secrets Store CSI Driver** | CSI volume driver mounting secrets as files | CRD: `SecretProviderClass`; providers for Vault/AWS/GCP/Azure |
| **Vault Agent Injector** | Mutating webhook injecting a Vault sidecar that renders secret files | Pod annotations `vault.hashicorp.com/*` |
| **Sealed Secrets (Bitnami)** | Encrypts secrets so they're safe to commit to Git | CRD `SealedSecret` + controller decrypts in-cluster |
| **SOPS / age** | File-level encryption for GitOps (Flux/Argo) | Encrypt YAML values with KMS/age keys |
| **Reloader (Stakater)** | Watches CM/Secret changes, triggers rollouts | Annotation `reloader.stakater.com/auto: "true"` |

### 4.6 Java/Spring-side consumption helpers

| Library / mechanism | Purpose |
|---|---|
| `@Value("${DB_PASSWORD}")` / `@ConfigurationProperties` | Read env vars / properties into beans |
| Spring Boot **relaxed binding** | Maps `DB_PASSWORD` env → `db.password` property automatically |
| **Spring Cloud Kubernetes** | Reads ConfigMaps/Secrets directly via the API and supports **reload** (`spring.cloud.kubernetes.reload.enabled=true`) |
| Spring Boot **config tree** (`spring.config.import=configtree:/etc/app/secrets/`) | Reads each mounted file as a property — ideal with CSI/Secret volumes |
| `spring.config.import=optional:configtree:...` | Same but tolerant of missing dir |

---

## 5. Code examples by use case

### 5.1 Use case: Spring Boot service, non-sensitive config via ConfigMap (env + file)

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: orders-config, namespace: orders }
data:
  SPRING_PROFILES_ACTIVE: "prod"
  application-extra.properties: |
    orders.batch-size=500
    orders.retry.max-attempts=4
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: orders, namespace: orders }
spec:
  replicas: 3
  selector: { matchLabels: { app: orders } }
  template:
    metadata: { labels: { app: orders } }
    spec:
      containers:
      - name: orders
        image: myorg/orders:2.3.1
        envFrom:
        - configMapRef: { name: orders-config }   # SPRING_PROFILES_ACTIVE → env
        volumeMounts:
        - { name: extra, mountPath: /etc/orders, readOnly: true }
        # import the file as Spring properties:
        env:
        - name: SPRING_CONFIG_IMPORT
          value: "optional:configtree:/etc/orders/"
      volumes:
      - name: extra
        configMap:
          name: orders-config
          items: [{ key: application-extra.properties, path: application-extra.properties }]
```

Spring relaxed binding turns `SPRING_PROFILES_ACTIVE` into the active profile; `configtree` imports the mounted file. Non-sensitive only — fine in a ConfigMap.

### 5.2 Use case: Database credentials via Secret, mounted as files (no env leakage)

```yaml
apiVersion: v1
kind: Secret
metadata: { name: orders-db, namespace: orders }
type: Opaque
stringData:
  username: orders_app
  password: "Pa$$w0rd-rotate-me"
---
# Deployment snippet
        volumeMounts:
        - { name: dbsecret, mountPath: /etc/secrets/db, readOnly: true }
        env:
        - name: SPRING_CONFIG_IMPORT
          value: "configtree:/etc/secrets/"   # files username,password → spring.datasource...
        # In application.properties:
        # spring.datasource.username=${username}
        # spring.datasource.password=${password}
      volumes:
      - name: dbsecret
        secret:
          secretName: orders-db
          defaultMode: 0400                    # owner-read-only; never world-readable
```

**Why files, not env:** env vars are visible in `/proc/<pid>/environ`, leak into crash dumps, and are often logged by frameworks on startup. File mounts on tmpfs with `0400` are tighter and support live rotation.

### 5.3 Use case: TLS certificate for an Ingress

```bash
kubectl create secret tls orders-tls \
  --cert=fullchain.pem --key=privkey.pem -n orders
```
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: orders, namespace: orders }
spec:
  tls:
  - hosts: [orders.example.com]
    secretName: orders-tls          # type kubernetes.io/tls; keys tls.crt/tls.key
  rules:
  - host: orders.example.com
    http:
      paths:
      - { path: /, pathType: Prefix, backend: { service: { name: orders, port: { number: 80 } } } }
```

### 5.4 Use case: Private registry pull secret

```bash
kubectl create secret docker-registry regcred \
  --docker-server=registry.example.com \
  --docker-username=ci --docker-password="$TOKEN" -n orders
```
```yaml
spec:
  imagePullSecrets:
  - name: regcred                    # or attach to the ServiceAccount for all pods
```

### 5.5 Use case: External Secrets Operator pulling from AWS Secrets Manager

```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata: { name: aws-sm, namespace: orders }
spec:
  provider:
    aws:
      service: SecretsManager
      region: us-east-1
      auth:
        jwt: { serviceAccountRef: { name: orders-sa } }   # IRSA: pod identity → AWS
---
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata: { name: orders-db-ext, namespace: orders }
spec:
  refreshInterval: 1h                 # how often ESO re-fetches/rotates
  secretStoreRef: { name: aws-sm, kind: SecretStore }
  target:
    name: orders-db                   # the native Secret ESO will create/update
    creationPolicy: Owner
  data:
  - secretKey: password               # key in the resulting K8s Secret
    remoteRef:
      key: prod/orders/db             # path in AWS Secrets Manager
      property: password              # JSON field within that secret
```

ESO authenticates via **IRSA** (IAM Roles for Service Accounts — maps a Kubernetes SA to an AWS IAM role using a projected OIDC token, so no static AWS keys live in-cluster). It then materializes `orders-db`, which Pods consume normally.

### 5.6 Use case: Secrets Store CSI driver (secret never enters etcd)

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata: { name: orders-vault, namespace: orders }
spec:
  provider: vault
  parameters:
    vaultAddress: "https://vault.example.com"
    roleName: "orders"
    objects: |
      - objectName: "db-password"
        secretPath: "secret/data/prod/orders/db"
        secretKey: "password"
---
# Pod volume
      volumes:
      - name: vault
        csi:
          driver: secrets-store.csi.k8s.io
          readOnly: true
          volumeAttributes: { secretProviderClass: "orders-vault" }
      containers:
      - name: orders
        volumeMounts:
        - { name: vault, mountPath: /mnt/secrets, readOnly: true }
```

The password appears at `/mnt/secrets/db-password` on the Pod's tmpfs, fetched directly from Vault — it is never persisted as a Kubernetes Secret in etcd.

### 5.7 Use case: Immutable, versioned config with Kustomize auto-rollout

```yaml
# kustomization.yaml
configMapGenerator:
- name: orders-config
  literals:
  - LOG_LEVEL=DEBUG
generatorOptions:
  immutable: true                # mark generated objects immutable
# Kustomize emits: orders-config-<hash>; the Deployment ref is rewritten to the hashed name.
```

Changing `LOG_LEVEL` produces a new hashed, immutable ConfigMap and a new Pod template → automatic, atomic rolling update with the old object retained for rollback.

### 5.8 Use case: Java reading config and reloading on file change (manual watcher)

```java
// Watches a mounted ConfigMap/Secret directory and reloads on change.
// Works because kubelet atomically swaps the ..data symlink (see §3.3).
import java.nio.file.*;

public class ConfigWatcher implements Runnable {
    private final Path dir = Paths.get("/etc/orders");
    private final Runnable onChange;            // e.g. re-read properties & refresh beans

    public ConfigWatcher(Runnable onChange) { this.onChange = onChange; }

    @Override public void run() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            // Watch the directory; the symlink swap surfaces as ENTRY_CREATE/MODIFY.
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                             StandardWatchEventKinds.ENTRY_MODIFY,
                             StandardWatchEventKinds.ENTRY_DELETE);
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = ws.take();       // blocks until an event
                boolean changed = !key.pollEvents().isEmpty();
                key.reset();
                if (changed) onChange.run();    // reload application config
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

In Spring Boot, prefer **Spring Cloud Kubernetes** reload (`spring.cloud.kubernetes.reload.mode=polling|event`) over hand-rolled watchers; this snippet shows the mechanics for non-Spring apps.

### 5.9 Use case: Locked-down RBAC for a secret

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: orders-secret-reader, namespace: orders }
rules:
- apiGroups: [""]
  resources: ["secrets"]
  resourceNames: ["orders-db"]      # restrict to ONE secret, not all
  verbs: ["get"]                    # no list/watch → can't enumerate secrets
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: orders-secret-reader, namespace: orders }
subjects:
- kind: ServiceAccount
  name: orders-sa
  namespace: orders
roleRef: { kind: Role, name: orders-secret-reader, apiGroup: rbac.authorization.k8s.io }
```

`resourceNames` + omitting `list`/`watch` is the least-privilege pattern: the SA can fetch exactly the one secret it needs and cannot enumerate the rest.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Watch load:** every mounted (non-immutable) ConfigMap/Secret is watched by every kubelet hosting a consuming Pod. At scale (thousands of Pods × many objects), this is real API server load. **Use `immutable: true`** to eliminate the watch.
- **Size cap:** 1 MiB per object. Don't stuff large blobs (JKS truststores, big JSON) repeatedly; reference shared objects, or use a dedicated config service for very large data.
- **Sync frequency:** file updates land within ~1 kubelet sync period (default 1 min). Don't design for sub-second config propagation via volumes.
- **Env-var explosion:** `envFrom` with huge ConfigMaps bloats every process's environment and shows up in every `ps e`/crash dump — prefer targeted refs.

### 6.2 Correctness & concurrency

- **subPath staleness:** never use `subPath` for config you intend to live-reload.
- **Atomicity:** rely on the symlink swap — read the *directory's* files freshly each reload; don't cache file handles across reloads.
- **Partial config visibility during rotation:** if a Secret has two related keys (e.g. username+password) and an external sync updates them, ensure the writer updates them in a single Secret write (they will then swap atomically) rather than two separate Secrets.
- **Required vs optional:** an absent required key fails Pod start; decide per key.

### 6.3 Memory

- Secret volumes are **tmpfs** = RAM. Many large secrets across many Pods consume node memory. Keep secrets small.
- Avoid logging env/config at `INFO` on startup (frameworks often do) — it lands secrets in log aggregation.

### 6.4 Security (the big one)

1. **Enable etcd encryption-at-rest for `secrets`**, ideally via a **KMS v2** provider. Without it, base64 Secrets are effectively plaintext in etcd and in etcd backups.
2. **Lock RBAC:** `get`/`list`/`watch secrets` is read-all-secrets-in-namespace. Grant `get` with `resourceNames`, never blanket `list`. Audit who has `secrets` access cluster-wide.
3. **Don't commit plaintext Secrets to Git.** Use **Sealed Secrets**, **SOPS**, or external managers. A Secret manifest in a public/private repo is a leaked credential.
4. **Prefer external managers** (Vault/cloud SM via ESO or CSI) for rotation, audit logging, dynamic short-lived credentials, and centralized revocation.
5. **Restrict file mode** (`defaultMode: 0400`/`0440`); run containers as non-root; set `readOnly: true` mounts.
6. **Mount over env** for the most sensitive secrets (env leaks via `/proc`, dumps, child processes, and logs).
7. **Disable unnecessary SA token automounting** (`automountServiceAccountToken: false`) so a compromised Pod can't freely call the API.
8. **Use `NetworkPolicy`/admission policies** (OPA Gatekeeper, Kyverno) to forbid risky patterns (e.g. secrets as env, missing encryption, overly broad RBAC).
9. **Protect etcd itself:** TLS, peer auth, restricted access, encrypted backups. The most catastrophic secret leaks come from etcd backups, not the API.

### 6.5 Cost

- External managers add per-secret/API-call costs (e.g. AWS Secrets Manager bills per secret + per 10k API calls). Tune `refreshInterval` to balance freshness vs cost.
- KMS encryption adds latency/calls on Secret reads (mitigated by KMS v2 caching with a key hierarchy).

### 6.6 Observability

- Audit logging: enable Kubernetes **audit policy** to record `get`/`list` on secrets — essential for breach forensics.
- Track ESO/CSI sync success metrics and last-sync timestamps; alert on sync failures (stale or missing rotation).
- Monitor for Pods failing to start due to missing CM/Secret references (`CreateContainerConfigError`).

### 6.7 Testing

- Use `--dry-run=client -o yaml` and schema validation (`kubeconform`) in CI.
- Test config-reload paths explicitly (update CM, assert app behavior changes within sync window).
- Use ephemeral namespaces/kind clusters; inject test secrets via `stringData`.

### 6.8 Anti-patterns to avoid

- Treating base64 as encryption.
- Hardcoding secrets in images, env defaults, or Git.
- `envFrom` a giant ConfigMap into every container.
- `subPath` for live-reloadable config.
- Blanket `list secrets` RBAC.
- Expecting env-var config to live-update without a Pod restart.
- One mega-ConfigMap for the whole platform (couples unrelated services, forces wide rollouts).
- Forgetting to roll Pods after a Secret rotation (env-injected creds go stale; the app keeps using the old credential until restart).

---

## 7. Advanced topics & deep internals

### 7.1 KMS v2 envelope encryption internals

**Envelope encryption:** data is encrypted with a per-object/data **DEK** (data encryption key); the DEK is itself encrypted by a **KEK** (key encryption key) held in the external KMS/HSM. Only the (small) wrapped DEK travels and is stored alongside ciphertext. **KMS v2** improvements over v1: a single DEK is cached and reused across many secrets with a key hierarchy and a stored *key ID*, slashing KMS API calls (v1 made a KMS call per secret read), and it surfaces health/status so the API server can fail safe. Rotating the KEK in the cloud KMS re-wraps DEKs lazily on next write.

### 7.2 The `..data` symlink swap, precisely

Inside a projected/secret/configMap volume mount you'll find: real data in `..2026_06_24_10_00_00.123456789/`, a symlink `..data -> ..2026_06_24_...`, and per-key symlinks (`LOG_LEVEL -> ..data/LOG_LEVEL`). On update, kubelet writes a *new* timestamped dir, then `rename()`s a temp symlink onto `..data` (atomic on POSIX), then garbage-collects the old dir. Readers either see the old `..data` target or the new one — never a torn write. This is why "atomic" is accurate and why you must re-resolve paths on reload.

### 7.3 Change-detection strategies

kubelet flag `--config-map-and-secret-change-detection-strategy` (a.k.a. `ConfigMapAndSecretChangeDetectionStrategy`):
- **`Watch`** (default): per-object watches; near-real-time, lowest API server load via shared informers.
- **`Cache`**: TTL-based cache; bounded staleness (up to ~1 min).
- **`Get`**: fetch on each sync; simplest, highest load.

Immutable objects bypass all of this (no watch needed).

### 7.4 Dynamic secrets (Vault)

Vault can issue **dynamic secrets** — e.g. a database credential created on demand with a short **lease** (TTL) and automatically revoked when the lease expires. The Vault Agent sidecar renews leases and re-renders the secret file; on expiry it fetches a fresh credential. This shrinks the window of a leaked credential from "until manual rotation" to "minutes," and gives per-Pod unique credentials for precise audit. There is no static long-lived password to rotate at all.

### 7.5 Rotation without downtime — the patterns

Zero-downtime rotation is a coordination problem because old and new credentials must both be valid during the cutover.

1. **Dual credentials / overlap window:** provision the new credential while the old still works (e.g. two valid API keys), roll Pods to use the new one, then revoke the old. The backend must accept both transiently.
2. **File-mounted secret + app reload:** rotate the Secret/external value; the file updates; the app reloads the connection pool with new creds — no restart. Requires the app to support reload (connection pool re-auth).
3. **ESO/CSI refresh + Reloader:** external value rotates → ESO updates the K8s Secret → **Reloader** detects the change and triggers a rolling restart of dependent Deployments. Works even for env-injected creds.
4. **Versioned immutable secrets:** create `creds-v2`, point Deployment at it, rolling update, keep `v1` for rollback.

**The classic failure:** rotating a secret consumed via **env var** without restarting Pods. The new value sits in etcd; the running JVM still holds the old one and authenticates with stale credentials until it restarts — often discovered only when the backend revokes the old credential and connections start failing.

### 7.6 ConfigMaps as the substrate for other features

Many controllers store state in ConfigMaps: leader-election locks (`coordination.k8s.io/Lease` superseded older CM locks), Cluster bootstrap config, CoreDNS config (`coredns` CM), kubeadm config. Editing those CMs is effectively editing cluster behavior — treat them as production config, not free-form data.

### 7.7 Helm/Argo/Flux interplay

- **Helm** templates Secrets but stores release data (which can include rendered Secrets) in its own Secret per release — be aware these contain sensitive values.
- **Argo CD / Flux** diff and sync manifests; for secrets they integrate with **Sealed Secrets / SOPS / ESO** so plaintext never lives in Git. SOPS encrypts values; the GitOps controller decrypts in-cluster via KMS/age keys.

### 7.8 Multi-cluster / multi-namespace replication

Secrets are namespaced. To share, use ESO (`ClusterSecretStore`), reflector/kubed (annotation-driven copy), or GitOps re-rendering. Avoid manual `kubectl get -o yaml | kubectl apply` across namespaces — it drifts.

### 7.9 Immutable + rollout interaction edge

Because immutable objects must be recreated under new names to change, you *must* pair them with name-templating (Kustomize/Helm hash). Otherwise updating "the same logical config" means a delete+recreate that can momentarily break new Pod scheduling if not ordered carefully.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Env vars vs mounted files

| Dimension | Env vars | Mounted files |
|---|---|---|
| Live reload | No (restart) | Yes (if app re-reads) |
| Leakage risk | Higher (/proc, dumps, logs) | Lower (tmpfs, file perms) |
| Ease of consumption | Trivial (any language reads env) | Need file reader / config tree |
| Binary/large values | Awkward | Natural |
| 12-factor purity | Canonical | Acceptable |
| Best for | Simple flags, profiles, ports | Sensitive secrets, large/structured config |

**Use env when:** simple, non-sensitive, set-and-forget config; legacy apps that only read env. **Avoid env when:** the value is highly sensitive, large, binary, or must rotate without restart.

### 8.2 ConfigMap vs Secret

| | ConfigMap | Secret |
|---|---|---|
| Intended data | non-sensitive | sensitive |
| Encoding | plaintext | base64 |
| Encryption at rest | not by default (rarely needed) | **should enable** |
| tmpfs mount | no | yes |
| RBAC scrutiny | normal | high |

**Rule:** if leaking it would matter, it's a Secret (and you must add encryption + RBAC). Putting a password in a ConfigMap is a security defect.

### 8.3 In-cluster Secrets vs External managers

| Dimension | Native Secret (+ encryption) | ESO (sync to native) | CSI driver (mount only) | Vault Agent sidecar |
|---|---|---|---|---|
| Secret in etcd? | Yes | Yes | Optional/No | No |
| Rotation | manual/Reloader | automatic on refresh | automatic on refresh | dynamic/lease-based |
| Audit | K8s audit only | backend + K8s | backend + K8s | rich Vault audit |
| Dynamic/short-lived creds | No | No | No | Yes |
| Operational complexity | Low | Medium | Medium | High |
| Blast radius if etcd leaks | High | High | Low | Low |

**Use native Secrets when:** small cluster, low compliance bar, you've enabled encryption + RBAC. **Use ESO when:** you want central management + auto-sync but apps consume normally. **Use CSI when:** you want secrets to never enter etcd. **Use Vault dynamic secrets when:** you need short-lived, per-Pod, auditable credentials and can pay the complexity.

### 8.4 Immutable vs mutable

**Use immutable when:** large clusters (watch-load matters), you adopt versioned config names, you want to prevent accidental edits and get free rollout/rollback. **Avoid immutable when:** you genuinely rely on in-place live updates (rare and usually an anti-pattern) and aren't ready to version names.

### 8.5 GitOps secret strategies

| Strategy | Plaintext in Git? | Decrypt where | Best for |
|---|---|---|---|
| Sealed Secrets | No (sealed) | in-cluster controller | simple, self-contained |
| SOPS+KMS/age | No (encrypted values) | GitOps controller | Flux/Argo shops |
| ESO + external SM | No (only references) | operator at runtime | enterprises with a vault |

---

## 9. Failure modes & debugging

### 9.1 Pod stuck `CreateContainerConfigError` / `CreateContainerError`

**Cause:** referenced ConfigMap/Secret or key missing, or not marked `optional`. **Diagnose:**
```bash
kubectl describe pod POD            # Events show "configmap X not found" / "key Y not found"
kubectl get configmap,secret -n NS
```
**Fix:** create the object/key, or set `optional: true`, then the kubelet retries automatically.

### 9.2 "Old pods work, new pods crash"

**Cause:** the CM/Secret was deleted or renamed; running Pods kept their materialized volumes/env, but new Pods can't resolve the reference. **Diagnose:** compare a healthy Pod's spec vs the missing object. **Fix:** restore the object or fix the reference; treat config objects as part of the deploy unit (GitOps/immutable versioning prevents this).

### 9.3 Config change "isn't taking effect"

**Triage tree:**
- Consumed via **env var?** → It never updates live. You must `kubectl rollout restart`. *(Most common cause.)*
- Consumed via **file + subPath?** → subPath never updates. Switch to a directory mount.
- Consumed via **file (dir mount)?** → wait up to ~1 min for kubelet sync; verify with `kubectl exec POD -- cat /path/file`. If file is new but app behavior unchanged → app doesn't re-read; add a watcher/Spring Cloud reload or roll Pods.

```bash
kubectl exec deploy/orders -- cat /etc/orders/application-extra.properties
kubectl exec deploy/orders -- printenv | grep LOG_LEVEL   # env won't have changed
```

### 9.4 Stale credentials after rotation → auth failures

**Cause:** secret rotated but env-injected Pods not restarted; backend revoked the old credential. **Symptom:** sudden `401/403`/DB auth errors across replicas after a rotation event. **Fix:** restart Pods (`rollout restart`) or adopt file-mount + reload / Reloader / dual-credential overlap. **Prevent:** never revoke old creds until all consumers confirm they use the new one.

### 9.5 Secret leaked via logs / `kubectl describe`

`kubectl describe secret` does **not** print values (shows `<n> bytes`), but `kubectl get secret -o yaml` shows base64 (trivially decoded). App startup logs frequently print env. **Diagnose:** grep log pipelines for the secret value; review who ran `get secret`. **Fix:** rotate the secret, scrub logs, tighten RBAC, enable audit logging, move sensitive values to file mounts and disable startup config logging.

### 9.6 etcd backup leak (worst case)

**Cause:** an etcd snapshot taken/stored without encryption-at-rest → every Secret is base64 plaintext inside it. **Real-world pattern:** unencrypted etcd backups in object storage with loose permissions is a recurring breach vector. **Fix/prevent:** enable encryption-at-rest with KMS *before* taking backups (existing secrets must be re-encrypted via `get|replace`), encrypt backup storage, restrict access, rotate everything if a snapshot was exposed.

### 9.7 ESO/CSI sync failures → stale or missing secrets

**Cause:** expired backend auth (IRSA/role), backend outage, wrong path, rate limiting. **Diagnose:**
```bash
kubectl describe externalsecret orders-db-ext -n orders   # status conditions, last sync, errors
kubectl logs deploy/external-secrets -n external-secrets
```
**Fix:** correct auth/path; alert on `SecretSyncError`/last-sync-age so you catch rotation stalls before credentials expire.

### 9.8 1 MiB size limit exceeded

**Cause:** stuffing large files/JKS into a single object. **Symptom:** create/apply rejected (`exceeds the maximum`) or, when concatenating many keys, total > 1 MiB. **Fix:** split objects, gzip+base64 if you must (and decompress in-app), or use a dedicated config store for large blobs.

### 9.9 KMS provider down → API unavailable for secrets

**Cause:** KMS plugin/endpoint unhealthy → API server can't encrypt/decrypt secrets. **Symptom:** secret reads/writes fail; with KMS v2, health checks surface it. **Fix/prevent:** HA KMS, monitor the KMS plugin health endpoint, keep `identity` last in the provider list so previously-plaintext data still reads.

---

## 10. Interview drill

**Q1. Are Kubernetes Secrets encrypted?**
*Model answer:* By default, no. Secret values are merely **base64-encoded** in the API object and stored as such in etcd — base64 is encoding, not encryption, and is trivially reversible. Confidentiality must be added: enable **encryption-at-rest** (ideally a KMS provider) so the API server encrypts secrets before writing to etcd, plus strict RBAC, encrypted etcd backups, and restricted node/etcd access.
- *Probe:* What changes on the etcd write when encryption is on? → The serialized object is passed through the encryption provider; etcd stores ciphertext prefixed with the provider name (e.g. `k8s:enc:kms:v2:`), decrypted transparently on read.
- *Probe:* Why keep `identity` last in the provider list? → So secrets written before encryption was enabled (plaintext) can still be decrypted/read until re-encrypted.
- *Probe:* How re-encrypt existing secrets? → `kubectl get secrets -A -o json | kubectl replace -f -` rewrites them through the new provider.

**Q2. Env vars vs mounted files for config — differences?**
*Model answer:* Env vars are set once at process start and are **static** (need a restart to change); files are continuously synced by kubelet and can be **live-reloaded** if the app re-reads them. Env leaks more easily (/proc, dumps, logs); secret file mounts use tmpfs with restrictive perms. Use env for simple non-sensitive flags; files for sensitive or reloadable config.
- *Probe:* How fast do file updates propagate? → Within ~1 kubelet sync period (default 1 min); with `Watch` detection it's near-real-time then re-projected on sync.
- *Probe:* Gotcha with single-file mounts? → `subPath` mounts never receive updates.
- *Probe:* Does the file changing automatically reload my Spring app? → No; Kubernetes only updates bytes. You need Spring Cloud Kubernetes reload, a file watcher, or a Pod restart.

**Q3. How do you change config consumed via env var?**
*Model answer:* You can't update a running process's env; force new Pods. Idioms: `kubectl rollout restart`, a **checksum annotation** on the Pod template that changes when the CM/Secret content changes, or **versioned immutable** objects (Kustomize `configMapGenerator` with a content-hash suffix) so any change yields a new template and an automatic rolling update.
- *Probe:* Why does the checksum trigger a roll? → It alters the Pod template hash, so the Deployment controller sees a new ReplicaSet and rolls.
- *Probe:* Advantage of versioned immutable objects? → Atomic rollout plus instant rollback (old object retained), and kubelet stops watching immutable objects.

**Q4. What is an immutable ConfigMap/Secret and why use it?**
*Model answer:* `immutable: true` forbids data edits. Primary benefit is **performance**: kubelets stop watching it, cutting API server/watch load at scale. Secondary: prevents accidental edits and pairs with versioned names for safe rollout/rollback. To "change" it you create a new (usually hash-named) object.

**Q5. Walk me through how a mounted secret stays consistent during updates.**
*Model answer:* kubelet writes new content into a fresh timestamped directory, then atomically `rename()`s the `..data` symlink to point at it; visible files are symlinks through `..data`. The atomic rename guarantees readers see either the complete old or complete new content — never a torn write. The app should re-resolve files on reload rather than cache handles.

**Q6. Compare native Secrets, ESO, CSI driver, and Vault Agent. (senior-signal)**
*Model answer:* Native Secrets (with encryption+RBAC) are simplest but live in etcd and need manual/Reloader rotation. **ESO** centralizes management and auto-syncs external values into native Secrets (still in etcd) for normal consumption. **CSI Secrets Store** mounts secrets directly as files and can keep them **out of etcd**, shrinking blast radius. **Vault Agent** enables **dynamic, short-lived, per-Pod credentials** with rich audit but is operationally heavy. Choose by blast-radius tolerance, rotation needs, audit/compliance, and operational budget.
- *Probe:* Which minimizes blast radius if an etcd backup leaks? → CSI (or Vault Agent) — the secret may never be in etcd.
- *Probe:* Which gives true zero-static-secret? → Vault dynamic secrets with leases.

**Q7. How do you rotate a database password with zero downtime? (senior-signal)**
*Model answer:* Avoid a hard cutover. Options: (a) **dual credentials** — provision the new credential while the old still works, roll Pods/connection pools to the new one, then revoke the old; (b) **file-mount + app reload** so the pool re-auths without restart; (c) **ESO/CSI refresh + Reloader** to auto-roll Pods; (d) **Vault dynamic secrets** so there's no static password to rotate. The cardinal rule: never revoke the old credential until every consumer confirms it uses the new one.
- *Probe:* Classic failure? → Rotating an env-injected credential without restarting Pods; the running JVM keeps the stale value until restart and breaks when the backend revokes the old one.
- *Probe:* Why is env-injected rotation worse than file-mounted? → Env can't update live; files can, so the pool can re-auth in place.

**Q8. How do you secure secrets in a GitOps workflow? (senior-signal)**
*Model answer:* Never commit plaintext. Use **Sealed Secrets** (encrypt so it's Git-safe; controller decrypts in-cluster), **SOPS+KMS/age** (encrypt values, GitOps controller decrypts on apply), or **ESO** (commit only *references*; the operator fetches real values at runtime). Combine with etcd encryption-at-rest, tight RBAC, and audit logging.
- *Probe:* Sealed Secrets vs SOPS? → Sealed Secrets is self-contained with an in-cluster controller and per-cluster key; SOPS integrates with external KMS/age and is decrypted by the GitOps engine — better for multi-tool, multi-cluster.

**Q9. What RBAC mistakes leak secrets?**
*Model answer:* Granting `list`/`watch` (or `get` without `resourceNames`) on `secrets` lets a subject read **every** secret in the namespace. Least privilege: grant `get` scoped to specific `resourceNames`, avoid `list`/`watch`, disable unneeded SA token automount, and audit cluster-wide secret access.
- *Probe:* Why is `list` especially dangerous vs `get`? → `list` returns all objects (with data) in one call; `get` with `resourceNames` restricts to named secrets only.

**Q10. Why is the 1 MiB limit there and how do you handle large config?**
*Model answer:* It stems from etcd's per-value limits and protects cluster stability. For large/binary config, split across objects, gzip+base64 (decompress in-app), use immutable versioned objects, or offload to a dedicated config service/object store referenced by a small Secret.

**Q11. Explain the 12-factor config principle and how K8s implements it. (senior-signal)**
*Model answer:* Twelve-Factor Factor III says store config in the environment, strictly separated from code, so one build artifact runs everywhere by varying only config. Kubernetes implements it with ConfigMaps/Secrets injected as env vars or files at deploy time — keeping environment-specific values and secrets out of the image and out of source control, enabling promotion of an identical image across environments.
- *Probe:* Does mounting files violate 12-factor? → No; the spirit is *externalized, environment-supplied* config. Files satisfy it and are often safer for secrets than env.

**Q12. What happens to running Pods if I delete a mounted Secret?**
*Model answer:* Running Pods keep operating with the already-materialized volume content, but **new** Pods that require it will fail to start (`CreateContainerConfigError`). This produces the deceptive "old pods fine, new pods broken" symptom. Manage config objects as part of the deployment unit (GitOps/immutable versioning) to avoid orphaning references.

---

## 11. Glossary

- **Admission controller:** API-server plugin/webhook that validates or mutates objects before persistence (e.g. enforce policies).
- **API server:** the control-plane component that exposes the Kubernetes API and is the only thing that talks to etcd.
- **Base64:** reversible binary-to-text encoding; provides no confidentiality.
- **binaryData:** ConfigMap field for base64-encoded non-UTF-8 values.
- **CRD (Custom Resource Definition):** mechanism to add new object kinds to the API.
- **CSI (Container Storage Interface):** standard for storage plugins; the Secrets Store CSI driver mounts secrets as volumes.
- **ConfigMap:** namespaced object holding non-sensitive key/value config.
- **DEK / KEK:** data/key encryption keys in envelope encryption; DEK encrypts data, KEK (in KMS) encrypts the DEK.
- **Encryption at rest:** encrypting data on disk (here, etcd) so file/disk access doesn't reveal plaintext.
- **envFrom / valueFrom:** Pod fields that inject all keys / a single key as environment variables.
- **etcd:** the distributed, Raft-based key/value store holding all cluster state, including Secrets.
- **External Secrets Operator (ESO):** operator that syncs external-vault values into native Kubernetes Secrets.
- **IRSA (IAM Roles for Service Accounts):** AWS feature mapping a K8s ServiceAccount to an IAM role via OIDC, avoiding static cloud keys.
- **Immutable object:** ConfigMap/Secret with `immutable: true`; data can't change and kubelet stops watching it.
- **KMS (Key Management Service):** external/HSM-backed key service holding master keys; used for envelope encryption.
- **kubelet:** node agent that runs/monitors containers and materializes ConfigMaps/Secrets into Pods.
- **Lease (Vault):** TTL on a dynamic secret; on expiry it's auto-revoked.
- **Namespace:** virtual cluster scope isolating objects.
- **Operator:** custom controller encoding domain logic, driven by CRDs.
- **Projected volume:** volume combining multiple sources (CMs, Secrets, SA token, downward API) into one tree.
- **Raft:** consensus algorithm etcd uses for agreement across members.
- **RBAC:** authorization model granting verbs on resources to subjects via Roles/Bindings.
- **resourceVersion:** optimistic-concurrency token and watch cursor on every object.
- **Reloader:** controller that restarts Deployments when their CM/Secret changes.
- **Sealed Secrets:** encrypts Secrets so they're safe to commit to Git; in-cluster controller decrypts.
- **Secret:** namespaced object for sensitive data; base64-encoded, optionally encrypted at rest.
- **SecretProviderClass:** CRD describing which external secrets the CSI driver should mount.
- **ServiceAccount (SA):** identity for Pod processes; basis for in-cluster authn.
- **SOPS:** file-level encryption tool (with KMS/age) for GitOps-safe secret manifests.
- **stringData:** write-only Secret field for plaintext that the API server base64-encodes.
- **subPath:** mounts a single key as a file; notably does **not** receive live updates.
- **tmpfs:** RAM-backed filesystem backing Secret volume mounts so they avoid node disk.
- **Twelve-Factor App:** methodology whose Factor III mandates config in the environment, separated from code.
- **Vault (HashiCorp):** dedicated secrets manager supporting dynamic secrets, leasing, and audit.
- **Vault Agent Injector:** webhook injecting a Vault sidecar that renders secret files into Pods.
- **Volume:** Pod-scoped directory; ConfigMap/Secret/CSI/projected volumes expose config to containers.
- **Watch:** API mechanism for streaming object changes; kubelet uses it to detect CM/Secret updates.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **ConfigMap = non-sensitive; Secret = sensitive (base64 ≠ encryption).** Enable etcd **encryption-at-rest (KMS v2)** + tight **RBAC**.
- **Limits:** 1 MiB per object. Secret volumes = **tmpfs** (RAM). Default file mode 0644 → set **0400/0440** for secrets.
- **Env vars = static** (restart to change). **Files = live-reload** (if app re-reads), propagate within ~**1 min** (kubelet sync). **subPath = no updates.**
- **Change env config:** `kubectl rollout restart`, checksum annotation, or versioned **immutable** objects (Kustomize hash).
- **Immutable:** stops kubelet watches (perf), prevents edits, enables atomic rollout/rollback.
- **External:** **ESO** (syncs → native Secret in etcd), **CSI driver** (mounts files, can skip etcd), **Vault Agent** (dynamic, leased, per-Pod creds).
- **Rotation w/o downtime:** dual-credential overlap, file-mount+reload, ESO/CSI+Reloader, or Vault dynamic secrets. **Never revoke old creds before all consumers use the new one.**
- **RBAC rule:** `get` with `resourceNames`; avoid `list`/`watch secrets`.
- **GitOps secrets:** Sealed Secrets / SOPS / ESO — never plaintext in Git.
- **Top failure:** rotating an env-injected secret without restarting Pods → stale creds, auth failures.
- **12-factor:** one image, config from the environment, no secrets in image/Git.

### Quick command reference

```bash
kubectl create configmap c --from-file=app.properties --dry-run=client -o yaml
kubectl create secret generic s --from-literal=pw=$PW
kubectl get secret s -o jsonpath='{.data.pw}' | base64 -d
kubectl rollout restart deploy/app
kubectl exec deploy/app -- cat /etc/app/secrets/password
kubectl describe externalsecret e -n ns
```

### Self-test (no answers — recall practice)

1. Your teammate says "Secrets are encrypted because they're base64." Explain precisely why this is wrong and exactly what you'd configure to make secret data confidential both in etcd and in etcd backups.
2. A config change to a ConfigMap consumed via `envFrom` "isn't taking effect" after 10 minutes. Diagnose the root cause and give three different ways to make the change roll out.
3. Describe the `..data` symlink-swap mechanism and explain why it guarantees readers never see a partially written config file. What breaks this guarantee?
4. Compare ESO and the Secrets Store CSI driver on: where the secret lives, rotation, and blast radius if an etcd snapshot leaks. When would you pick each?
5. Design a zero-downtime database-password rotation for a Spring Boot service running 20 replicas. Specify how the secret is consumed, how rotation is triggered, and the exact ordering that avoids any auth failures.
6. Why does marking a ConfigMap `immutable: true` improve performance at scale, and what additional pattern must you adopt to keep changing the "same" config safely?
7. Give the least-privilege RBAC to let one ServiceAccount read exactly one Secret, and explain why granting `list` would be dangerous.

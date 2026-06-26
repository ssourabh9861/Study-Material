# Branching & Feature Flags

> A definitive engineering-handbook chapter on branching strategies and feature flags, written for a senior Java/JVM backend developer who wants to master the topic from first principles to deep internals — enough to design with it, operate it, debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What this chapter is about

This chapter covers two tightly coupled disciplines in **release engineering**:

1. **Branching** — how teams organize work in a version-control system (almost always **Git**) so multiple people can change the same codebase concurrently without stepping on each other, and how that organization either *enables* or *obstructs* Continuous Integration (CI).
2. **Feature flags** (a.k.a. *feature toggles*, *feature switches*) — runtime conditionals that let you ship code to production in a *dormant* state and turn its behavior on later, for some or all users, without redeploying.

They are covered together because they solve **the same underlying problem from two directions**: how do you let a team integrate work *constantly* (which is what CI demands) while still controlling *when* features become visible to users? Branching answers the first half at the *source-code* level; feature flags answer it at the *runtime* level.

> **CI (Continuous Integration)** is the practice of merging every developer's work into a shared mainline *frequently* — ideally many times per day — and verifying each merge with an automated build + test run. The word "continuous" is the load-bearing part: integration that happens once a week is not CI. We define this more carefully in §2.

> **CD** is overloaded. **Continuous Delivery** means every change that passes the pipeline is *releasable* (a human clicks deploy). **Continuous Deployment** means every change that passes the pipeline is *automatically deployed* to production. Both depend on the ability to integrate constantly and to deploy without releasing — exactly what this chapter is about.

### 1.2 The problem it solves

Imagine a team of 20 engineers working on a payments service. Three forces pull against each other:

- **Force A — Integrate often.** The longer code lives apart from mainline, the more it diverges, and the more painful and risky the eventual merge ("merge hell," "integration hell"). Integrating small changes constantly keeps divergence near zero.
- **Force B — Don't ship half-baked features.** A feature that takes three weeks to build is not safe to expose to users on day one. The naive way to hide it is to *keep it on a branch* until it's done — but that directly violates Force A.
- **Force C — Control release timing.** Marketing wants the new checkout flow to go live on a specific date; compliance wants the EU rollout staged separately; on-call wants an emergency off-switch. None of these should require a code change + redeploy at the moment of decision.

The classic resolution is:

- **Branching strategy** (specifically **trunk-based development**) resolves A vs B at the *code-integration* layer by integrating incomplete work into mainline behind a flag instead of hiding it on a long-lived branch.
- **Feature flags** resolve B vs C at the *runtime* layer by **decoupling deploy from release** — you *deploy* the code (it's present in production but inert) and *release* the feature (flip the flag) as two independent events, possibly weeks apart, possibly to a 1% slice of users first.

### 1.3 When you reach for each

| You need to… | Reach for… |
|---|---|
| Let many engineers integrate work daily without merge hell | Trunk-based development + short-lived branches |
| Ship a big feature incrementally without exposing it | Trunk-based + **release flags** wrapping incomplete code |
| Turn a feature on/off in prod instantly (no deploy) | **Ops / kill-switch flags** |
| Roll a feature out to 1% → 10% → 100% of users | **Progressive rollout** flags with percentage targeting |
| Show different behavior to different users (beta, paid tier, region) | **Targeting / permission flags** |
| Run an A/B test and measure the difference | **Experiment flags** |
| Open-source / self-hosted flag tooling | Unleash, Flagsmith, OpenFeature + a provider |
| Managed flag platform with experimentation | LaunchDarkly, Split, Optimizely |

### 1.4 One-paragraph mental model

> **Branching** is about *where source code lives before it reaches mainline*; the goal is to keep that "before" window as short as possible (ideally hours, not weeks) so integration is continuous. **Feature flags** are about *whether code that has already reached mainline and been deployed is actually active*; the goal is to decouple the moment of *deploy* (code present in prod) from the moment of *release* (behavior visible to users), so you control rollout independently of your build pipeline. Trunk-based development without feature flags is reckless (you'd ship half-features); feature flags without trunk-based development are wasted (you'd still suffer merge hell). Together they let a large team integrate constantly *and* release safely.

---

## 2. Foundations from first principles

This section assumes you are sharp but new to *this specific topic*. We build up every term.

### 2.1 What a version-control system actually tracks

> **Version Control System (VCS)** — software that records changes to files over time so you can recall specific versions, see who changed what, and merge concurrent work. **Git** is the dominant distributed VCS today.

> **Distributed VCS (DVCS)** — every clone of the repository contains the *full history*, not just the latest snapshot. Git, Mercurial are DVCS; older systems like Subversion (SVN) and CVS were *centralized* (one server held history; clients held only a working copy).

Git's core data model (you must understand this to reason about branching):

- A **commit** is an immutable snapshot of the entire tree of files at a point in time, plus metadata (author, timestamp, message) and one or more **parent** commit references. Commits are identified by a **SHA-1 (or SHA-256) hash** of their content — change anything and the hash changes.
- Because each commit points at its parent(s), commits form a **DAG (Directed Acyclic Graph)** — a graph of nodes where edges have direction (child → parent) and there are no cycles. History is this DAG.
- A **branch** in Git is *not* a copy of files. It is merely a **named, movable pointer to a commit**. Creating a branch is creating a 41-byte file. This is why Git branching is "cheap" — a fact that shapes every strategy below.
- **HEAD** is a pointer to the branch (or commit) you currently have checked out.
- **`main`/`master`** is just a branch by convention — the "mainline" or "trunk." There is nothing technically special about it; conventions and tooling make it special.

> **Merge** — combining two divergent lines of history. Git finds the **merge base** (the most recent common ancestor commit), computes the changes each side made since then, and combines them. A **merge commit** has two parents.

> **Three-way merge** — Git compares *base → ours* and *base → theirs* and applies both sets of changes. When both sides changed the *same lines*, Git can't decide and reports a **merge conflict**, which a human must resolve.

> **Rebase** — instead of creating a merge commit, Git *replays* your commits on top of another branch's tip, producing a linear history. Rebasing rewrites commit hashes (new parents → new hashes), so you never rebase shared/published history without coordination.

> **Fast-forward merge** — if your branch hasn't diverged (the target is a direct ancestor), Git can just slide the pointer forward with no merge commit.

### 2.2 Why divergence is the enemy: integration cost grows super-linearly

The cost to merge two branches is roughly proportional to the *number and overlap* of changes since their common ancestor. If two branches both touch the same files heavily over three weeks, the conflict surface is large and the *semantic* conflicts (code that merges cleanly but behaves wrongly together) are even worse — and Git can't detect those at all.

Key insight: **integration pain is not linear in time; it compounds.** Two short branches that each diverge for a day produce trivial merges. One branch that diverges for a month can produce days of conflict resolution and regression risk. This is the empirical foundation of trunk-based development and the reason CI insists on *frequency*.

### 2.3 The three canonical branching strategies

We'll define each precisely, then compare.

#### 2.3.1 GitFlow

> **GitFlow** — a branching *model* popularized by Vincent Driessen's 2010 blog post "A successful Git branching model." It defines a rich set of long-lived and supporting branches.

Branch types in GitFlow:

- **`master`** — holds only production-released code; every commit is a release, tagged with a version.
- **`develop`** — the integration branch where features accumulate for the *next* release.
- **`feature/*`** — branched off `develop`, merged back into `develop` when done. Can be long-lived.
- **`release/*`** — branched off `develop` when preparing a release; only bug fixes and release prep go here; merged into both `master` and `develop`.
- **`hotfix/*`** — branched off `master` to fix production urgently; merged into both `master` and `develop`.

GitFlow is **release-centric** and works for software with **explicit, versioned releases** (desktop apps, libraries, firmware, on-prem products with quarterly releases). It is widely considered **anti-CI** for web services because feature branches live long and `develop` ≠ what's in production.

#### 2.3.2 GitHub Flow

> **GitHub Flow** — a lightweight model: `main` is always deployable; you branch a short-lived branch for each change, open a **Pull Request (PR)**, get review + CI, then merge to `main` and deploy.

> **Pull Request (PR) / Merge Request (MR)** — a request to merge one branch into another, used as the unit of code review and automated checks. "PR" is GitHub/Bitbucket terminology; "MR" is GitLab's. Same concept.

GitHub Flow has no `develop`, no release branches by default. It is much closer to CI than GitFlow, but the branches can still live for days and accumulate review latency.

#### 2.3.3 Trunk-Based Development (TBD)

> **Trunk-Based Development (TBD)** — a model where all developers integrate into a single branch (the **trunk**, usually `main`) **at least once per day**, using **very short-lived branches** (hours, not days) or even committing straight to trunk. Incomplete features are merged behind **feature flags** rather than held back on branches.

Two flavors:

- **Committing straight to trunk** — viable for small, highly-disciplined teams with fast tests and pair/mob programming.
- **Short-lived feature branches** — the common scaled form: branch, do < 1 day of work, PR, automated checks, merge same day. The branch is so short that divergence stays trivial.

> **Release branches in TBD** — TBD *does* allow release branches, but they are created *from* trunk at release time and are **cut, not developed on**. Fixes are made on trunk and **cherry-picked** back to the release branch (forward-only flow), never the reverse.

> **Cherry-pick** — applying the diff of a single commit from one branch onto another, creating a new commit with a new hash. Used to backport a specific fix to a release branch without merging unrelated trunk work.

**Why TBD enables CI (the crux):** CI is *defined* as integrating to a shared mainline frequently with automated verification. TBD makes the shared mainline (trunk) the integration point and forces frequency (≤1 day). GitFlow's `develop` delays integration of features for their whole lifetime; the "integration" of a feature branch only happens at merge, which may be weeks out — so by definition it is *not* continuous integration, no matter how good your build server is. **You can run a CI *server* on GitFlow, but you are not *doing* CI.** This distinction trips up many engineers: a green Jenkins build on a 3-week-old branch is automated testing, not continuous integration.

### 2.4 What "short-lived" and "merge frequency" actually mean

- **Short-lived branch** — a branch that exists for **hours up to ~1 day**, never more than a couple of days. The discipline metric: *time from branch creation to merge into trunk.*
- **Merge frequency** — how often the average developer integrates to trunk. TBD target: **at least once per day per developer.** The DORA research (see §2.6) found that teams merging to trunk daily, with branches living < 1 day, are strongly correlated with elite delivery performance.
- **Batch size** — the amount of change per integration. Smaller batches → smaller conflicts, easier review, faster feedback, easier rollback. TBD is fundamentally a *small-batch* discipline.

### 2.5 The deploy-vs-release distinction (the heart of feature flags)

This is the single most important conceptual distinction in the chapter. Engineers routinely conflate these two words; release engineering requires separating them.

- **Deploy** — the act of getting a build of your code running in an environment (e.g., production). After deploy, the code is *present and executing* on the servers. Deploy is a *technical*, *infrastructural* event.
- **Release** — the act of making a feature's behavior *available to users*. Release is a *product*/*business* event.

In the old world these were the same instant: you deployed the build and the new behavior was live. **Feature flags decouple them.** You deploy code where a feature is wrapped in `if (flags.isEnabled("new-checkout")) { ... }` with the flag **off**. The code is in production (deployed) but does nothing user-visible (not released). Later, independent of any deploy, you flip the flag (release). This buys you:

- **Independent timing** — release on a marketing date, not a deploy date.
- **Gradual rollout** — release to 1% then 100%.
- **Instant rollback** — flip the flag off in seconds vs. redeploying the previous build in minutes.
- **Targeting** — release to internal users, beta cohort, a region, a paid tier.
- **Experimentation** — release variant A to half, B to the other half, and measure.

### 2.6 The empirical backing: DORA

> **DORA (DevOps Research and Assessment)** — a research program (now part of Google Cloud) behind the *Accelerate* book and the annual *State of DevOps* reports. It identifies four key delivery metrics: **deployment frequency, lead time for changes, change failure rate, and time to restore service.** Its research consistently links **trunk-based development**, **short-lived branches (< 1 day)**, and **CD** to elite performance on those metrics. This is the most-cited evidence base for the practices in this chapter. (Treat the correlation/causation nuance honestly: these are observational findings, but they are large-sample and consistent.)

### 2.7 A first feature-flag example (Java)

The simplest flag is a boolean check. Even before any platform, the *pattern* looks like this:

```java
// The most primitive feature flag: a config-driven boolean.
// In real systems the value comes from a flag service, not a static field.
public class CheckoutController {

    private final FeatureFlags flags;

    public CheckoutController(FeatureFlags flags) {
        this.flags = flags;
    }

    public CheckoutView renderCheckout(User user) {
        // Decoupling deploy from release: this code is DEPLOYED to prod,
        // but the new flow is not RELEASED until the flag returns true.
        if (flags.isEnabled("new-checkout-flow", user)) {
            return renderNewCheckout(user);   // new code path
        } else {
            return renderLegacyCheckout(user); // unchanged, safe default
        }
    }
}
```

The important properties even at this trivial level:

1. **The flag has a safe default** (legacy path) so that if the flag system is unreachable, behavior is correct.
2. **The decision is per-call and takes the `user`** so we can target subsets.
3. **The flag name is a stable string key** — this becomes a coordination point between code and the flag system.

We'll make `FeatureFlags` real in §5.

---

## 3. How it works internally

This is the heart of the chapter. We trace the *internal workflows* of both branching and feature flags step by step.

### 3.1 Internal workflow: the life of a trunk-based change

Step by step, what happens from "I want to make a change" to "it's live for users":

1. **Sync trunk.** `git fetch && git switch main && git pull --ff-only`. You start from the latest trunk so your divergence window starts at zero.
   > `--ff-only` refuses to create a merge commit; it errors if your local `main` has diverged, forcing you to notice. This keeps `main` clean.
2. **Branch (short-lived).** `git switch -c feat/checkout-tax`. The branch is a pointer at the current trunk tip. Divergence = 0 at this instant.
3. **Implement behind a flag.** You wrap any user-visible new behavior in `if (flags.isEnabled("checkout-tax"))`. This is what lets you merge *incomplete* work safely.
4. **Commit small.** Multiple small commits; each compiles and passes tests locally.
5. **Push & open PR.** `git push -u origin feat/checkout-tax`. The PR triggers the **CI pipeline**.
6. **CI runs.** The pipeline (Jenkins/GitHub Actions/GitLab CI) checks out the *merge result* (your branch merged onto current trunk, computed ephemerally), builds, runs unit/integration tests, static analysis, security scans. This verifies *integration*, not just your branch in isolation.
7. **Review.** A teammate reviews the small diff. Small batch → fast review.
8. **Merge to trunk.** On green + approval, you merge. Common strategies:
   - **Squash merge** — collapse the branch's commits into one commit on trunk (clean linear history; loses intermediate commits).
   - **Rebase-and-merge** — replay commits linearly (preserves commits, linear history).
   - **Merge commit** — preserves branch shape with a two-parent commit.
9. **Trunk CI re-runs** on the new trunk tip (post-merge verification, because the merge-base may have shifted since the PR check — this catches the "semantic conflict that merged clean" case).
10. **Pipeline builds an artifact** (a JAR, a container image) and promotes it through environments (dev → staging → prod), running progressively heavier tests.
11. **Deploy to production.** The code is now *present* in prod with the flag **off** → deployed, not released.
12. **Release via flag.** Later, independently, someone flips `checkout-tax` to on (perhaps 1% first). This is the *release*.
13. **Observe.** Metrics/logs/traces are watched for the flagged cohort. If bad, flip off (instant rollback). If good, ramp to 100%.
14. **Clean up the flag.** Once at 100% and stable, the flag and the dead `else` branch are removed (flag debt cleanup — §6.7).

### 3.2 Internal workflow: how a feature-flag SDK evaluates a flag

This is what actually happens *inside* the JVM at `flags.isEnabled(...)` time. The details vary by vendor, but the architecture is remarkably consistent across LaunchDarkly, Unleash, Split, Flagsmith, etc.

**Components:**

> **Flag definition / rule set** — the configuration that says, for a given flag key, what value to return given an *evaluation context*. It includes the on/off default, targeting rules (segments, attributes), percentage rollouts, and a fallback.

> **Evaluation context (a.k.a. context / user / attributes)** — the data describing *who/what* you're evaluating for: a stable key (user id, account id, device id), plus attributes (country, plan, email, app version, etc.). The SDK feeds this into the rule set.

> **SDK (Software Development Kit)** — the client library embedded in your app that fetches flag definitions and evaluates them locally.

> **Flag service / control plane** — the backend (SaaS or self-hosted) that stores flag definitions and pushes updates to SDKs.

**The two evaluation architectures (critical to understand):**

1. **Server-side / local evaluation (the dominant backend model).**
   - On startup, the SDK **bootstraps**: it downloads the *entire rule set* for all flags from the service (via streaming or polling).
   - It holds these rules in an **in-memory store**.
   - When your code calls `isEnabled("k", context)`, the SDK evaluates the rule **locally, in-process, with no network call.** This is **microseconds** and does not block on the network.
   - The service pushes updates: a flag change propagates to all SDKs in **~hundreds of milliseconds to a couple of seconds** (vendor-dependent), via a streaming connection (Server-Sent Events / SSE, or WebSocket) or short polling.
   - **Why local eval matters:** a flag check is on your hot path (every request). It *must not* add network latency or a network failure mode per request. Local eval makes flag checks essentially free and fail-safe.

   > **SSE (Server-Sent Events)** — a standard where the server holds an HTTP connection open and streams text events to the client. LaunchDarkly's "streaming" mode uses SSE to push flag changes.

2. **Client-side / remote evaluation (browser, mobile, or "evaluation proxy").**
   - The client (e.g., a browser JS SDK) **cannot** be trusted with the full rule set (it would leak targeting logic and other users' segments) and may have thousands of flags.
   - So the *server* (or an edge service / **relay/proxy**) evaluates flags for *that one context* and returns just the resulting values.
   - Updates are pushed to the client via streaming or fetched on context change.

   > **Relay Proxy / Unleash Proxy / Edge** — a service you run that sits between many client SDKs and the flag service, evaluating flags and caching, to reduce load and avoid exposing rules to untrusted clients.

**Step-by-step local evaluation of one `isEnabled` call:**

1. SDK looks up the flag definition for key `k` in its in-memory store. *Miss* → return the **code-level default** you passed in (fail-safe).
2. If the flag is globally **off**, return the off-variation.
3. Evaluate **individual targets** (explicit "user X always gets variation Y").
4. Evaluate **targeting rules** top-down: each rule has clauses (e.g., `country in [US, CA]`, `plan == "enterprise"`). First matching rule wins.
5. If a matched rule (or the fallthrough) specifies a **percentage rollout**, compute a **bucketing hash**:
   - Concatenate a flag-specific salt + the context's bucketing key (e.g., user id).
   - Hash it (e.g., MD5/SHA) to a number, map to a bucket in [0, 100000).
   - If bucket < rollout%, the user is "in." **The same user always hashes to the same bucket for the same flag**, so rollout is *sticky* (a user doesn't flicker in and out) and *consistent across servers* (no shared state needed). This is **deterministic bucketing** and is why percentage rollout works without a database.
6. Return the resolved variation. Record an **evaluation event** (for analytics/experimentation) — usually batched and flushed asynchronously, never blocking.

**Step-by-step bootstrapping & update propagation:**

1. App starts → SDK opens a streaming connection (or starts polling) to the flag service with an SDK key.
2. Service sends a **full payload** of all flag definitions → SDK populates store → SDK signals "ready." (Good SDKs let you *block* startup until ready, or proceed with defaults.)
3. An operator changes a flag in the dashboard/API.
4. Service pushes a **patch** event (just the changed flag) over the stream.
5. SDK applies the patch to its in-memory store atomically. Next `isEnabled` call sees the new value.
6. If the stream drops, SDK **reconnects with backoff** and re-syncs. While disconnected, it serves the last-known values (fail-static).

### 3.3 State machine: the lifecycle of a feature flag

A feature flag is not eternal. It has a lifecycle and a *state machine*:

```
  [Proposed] --create--> [Off in prod, code deployed]
        |
        v
  [Internal-only] --target internal users--> validate
        |
        v
  [Canary / 1%] --ramp--> [10%] --ramp--> [50%] --ramp--> [100%]
        |                                                   |
        | (regression detected at any %)                    |
        v                                                   v
  [Rolled back to 0%] --fix--> re-ramp              [Fully released]
                                                            |
                                                            v
                                              [Flag retired: code cleaned,
                                               flag deleted from system]
```

State transitions and what triggers them:

- **Create** — flag defined in system, code merged behind it, default off.
- **Internal-only** — target by `internal == true` attribute; dogfood.
- **Canary** — small % rollout (1–5%) plus health watch.
- **Ramp** — increase % as confidence grows; automated ramps watch SLOs.
- **Rollback** — set to 0% instantly on regression (the kill-switch use).
- **Fully released** — 100% for long enough that the off path is dead.
- **Retire** — remove the conditional, delete dead code, delete the flag. *Skipping this step is "flag debt" (§6.7).*

> **Canary** — releasing a change to a *small subset* of traffic/users first (the "canary in the coal mine"), watching health, then expanding. Can be done at the infrastructure layer (canary deploy) or the flag layer (canary release).

> **SLO (Service Level Objective)** — a target for a reliability metric (e.g., "99.9% of requests succeed," "p99 latency < 300ms"). Automated ramps and rollbacks watch SLOs.

### 3.4 Data flow: where flag evaluation events go

Beyond returning a value, SDKs emit **events**:

- **Evaluation events** — "context C got variation V for flag F at time T." Used for experimentation (linking exposure to outcomes) and debugging.
- **Identify events** — context attributes, so the dashboard can target by them.
- These are buffered in-memory and **flushed in batches** (e.g., every few seconds or N events) over HTTP to the service or an analytics sink. Flushing is async; it must never block request handling. On shutdown, a good SDK flushes remaining events.

> **Experimentation pipeline** — evaluation events (exposure) are joined with **conversion/metric events** (did the user buy? click?) in an analytics store, then a **stats engine** computes whether variant B beat variant A with statistical significance. This is how experiment flags become A/B tests.

---

## 4. The complete toolkit

### 4.1 Git commands relevant to branching strategy

| Command | Purpose | Key options / notes |
|---|---|---|
| `git switch -c <b>` | Create + switch to a new branch | Modern replacement for `git checkout -b`. |
| `git switch <b>` | Switch to existing branch | `--detach` to detach HEAD. |
| `git pull --ff-only` | Update local branch, refuse merge commits | Keeps trunk linear; errors on divergence. |
| `git fetch --prune` | Download remote refs; delete stale remote-tracking branches | `--prune` removes deleted remote branches locally. |
| `git rebase main` | Replay current branch on top of `main` | Linear history; rewrites hashes; never on shared history. |
| `git rebase -i <base>` | Interactive rebase: squash/reorder/edit commits | Clean up a branch before merge. |
| `git merge --no-ff <b>` | Always create a merge commit | Preserves branch boundary in history. |
| `git merge --squash <b>` | Combine branch changes into staged changes (you commit once) | Common for short-lived branch → one trunk commit. |
| `git cherry-pick <sha>` | Apply one commit's diff as a new commit | Backport fixes to release branches (forward-only in TBD). |
| `git tag -a vX.Y.Z -m"..."` | Annotated tag marking a release | Tags are the "where is this in prod" record in TBD. |
| `git branch -d <b>` / `-D` | Delete merged branch / force-delete | Delete short-lived branches after merge. |
| `git log --graph --oneline` | Visualize the DAG | Inspect history shape (linear vs branchy). |
| `git bisect start/good/bad` | Binary-search history for the commit that introduced a bug | Powerful on linear trunk history. |
| `git revert <sha>` | Create a new commit that undoes a commit | The TBD way to "undo" on trunk (never rewrite shared history). |

### 4.2 CI/CD platform features that enforce branching discipline

| Feature | Purpose | Example |
|---|---|---|
| **Branch protection rules** | Forbid direct push to `main`; require PR | GitHub/GitLab settings. |
| **Required status checks** | Merge blocked until CI is green | "Require checks to pass before merging." |
| **Required reviews** | Merge blocked until N approvals | CODEOWNERS for path-based reviewers. |
| **Merge queue / merge train** | Serialize merges, testing each against latest trunk before landing | GitHub Merge Queue, GitLab Merge Trains — prevents the "two PRs each green alone, broken together" race. |
| **Auto-merge** | Merge automatically when checks pass | Reduces branch lifetime. |
| **Linear-history requirement** | Disallow merge commits | Enforces rebase/squash. |
| **Stale-branch alerts** | Flag branches older than N days | Surfaces long-lived branches violating TBD. |

> **Merge queue / merge train** — when multiple PRs are ready, the platform tests each one *rebased onto the result of the queue so far*, landing them one at a time only if still green. This solves the semantic-conflict race in high-throughput repos where two independently-green PRs break trunk when combined.

### 4.3 Feature-flag SDK API surface (OpenFeature-flavored, vendor-neutral)

> **OpenFeature** — a CNCF (Cloud Native Computing Foundation) vendor-neutral *specification and SDK* for feature flagging. You code against the OpenFeature API; a **provider** plugin connects it to a concrete backend (LaunchDarkly, Unleash, Flagsmith, flagd, etc.). This avoids vendor lock-in at the code level.

> **CNCF (Cloud Native Computing Foundation)** — the open-source foundation (part of the Linux Foundation) that hosts Kubernetes, Prometheus, OpenFeature, etc.

Core OpenFeature Java concepts and methods:

| Element | Purpose | Notes / defaults |
|---|---|---|
| `OpenFeatureAPI.getInstance()` | Global singleton entry point | Set the provider once at startup. |
| `api.setProviderAndWait(provider)` | Register a backend provider, block until ready | Use the `...AndWait` form so startup doesn't race. |
| `api.getClient()` | Get a `Client` for evaluations | Clients are cheap; can be named/domain-scoped. |
| `client.getBooleanValue(key, default, ctx)` | Evaluate a boolean flag | Returns `default` on any error — fail-safe. |
| `client.getStringValue / getIntegerValue / getDoubleValue / getObjectValue` | Typed evaluations | Same fail-safe default contract. |
| `client.getBooleanDetails(...)` | Evaluation **with metadata** (reason, variant, error code) | Use for debugging/telemetry. |
| `EvaluationContext` / `ImmutableContext` | The "who/what" being evaluated | Must include a **targeting key** for sticky bucketing. |
| `Hooks` | Cross-cutting logic around evaluations (before/after/error/finally) | Logging, metrics, validation. |
| `setEvaluationContext` (global/transaction/client) | Layered context merged at eval time | Global → client → invocation precedence. |

**`getBooleanDetails` reason codes you'll see:** `TARGETING_MATCH`, `SPLIT` (percentage), `DEFAULT`, `DISABLED`, `ERROR`, `STATIC`, `CACHED`, `STALE`. These tell you *why* a value was returned.

### 4.4 LaunchDarkly Java SDK essentials (vendor-specific)

| Element | Purpose | Default / note |
|---|---|---|
| `new LDClient(sdkKey, config)` | Create the singleton client | Blocks up to a *start-wait* (default ~5s) for initial sync. |
| `LDConfig.Builder().startWait(Duration)` | How long to block for init | Default 5s; set 0 for non-blocking. |
| `DataSource`: streaming vs polling | How flags sync | **Streaming (SSE) is the default**; polling configurable. |
| `client.boolVariation(key, context, default)` | Evaluate boolean | Local, in-memory, microseconds. |
| `client.stringVariation / intVariation / doubleVariation / jsonValueVariation` | Typed evals | — |
| `client.boolVariationDetail(...)` | Eval + reason | For debugging. |
| `LDContext.builder(key)...build()` | Build the evaluation context | `key` is the bucketing key; add attributes. |
| `client.identify(context)` | Register/update a context | Optional; eval auto-captures. |
| `client.flush()` | Force-flush queued events | Call on graceful shutdown. |
| Relay Proxy | Centralize streaming, evaluate for clients | Self-hosted service. |
| `LDConfig.offline(true)` | Run with no service; all evals return default | For tests/airgapped. |

### 4.5 Unleash essentials (open-source, self-hostable)

| Element | Purpose | Note |
|---|---|---|
| Unleash server | Self-hosted control plane + UI | Open source; SaaS option exists. |
| **Activation strategies** | Built-in rule types | `default`, `gradualRollout` (a.k.a. flexibleRollout), `userWithId`, `remoteAddress`, etc. |
| **Constraints** | Attribute conditions on a strategy | e.g., `environment in (production)`. |
| **Variants** | Multiple payloads behind one toggle | For experiments / multivariate. |
| Java SDK `Unleash` | Client | `isEnabled(toggle, context)`; local eval after fetch. |
| Unleash Proxy / Frontend API | For client-side SDKs | Avoids exposing rules. |
| `fetchTogglesInterval` | Polling interval | Default 15s in many SDKs. |

> **Activation strategy (Unleash term)** — a named algorithm deciding when a toggle is on. `flexibleRollout` does deterministic percentage bucketing by a `stickiness` field (e.g., userId).

### 4.6 Feature-flag taxonomy (the four+ types)

| Type | Lifespan | Who flips it | Purpose | Example |
|---|---|---|---|---|
| **Release toggle** | Days–weeks (transient) | Devs/release mgr | Hide in-progress code in trunk; enable progressive rollout | `new-checkout-flow` |
| **Ops / kill-switch toggle** | Long-lived | Ops / on-call | Disable a feature or expensive code path under load/incident | `enable-recommendations`, `circuit-break-3p-tax-api` |
| **Experiment toggle** | Days–weeks | Data/PM | A/B/n test; route cohorts to variants and measure | `pricing-experiment-v2` |
| **Permission / entitlement toggle** | Very long / permanent | Product/billing | Gate features by plan, role, region, license | `premium-export`, `eu-data-residency` |

This taxonomy comes from Pete Hodgson's widely-cited "Feature Toggles" article on martinfowler.com. The key engineering implication: **lifespan and dynamism differ wildly per type**, which dictates *where* the toggle lives, how it's managed, and whether it must be cleaned up. Release toggles are *temporary debt to be removed*; permission toggles are *permanent product configuration*.

---

## 5. Code examples by use case

All examples are Java unless noted. They are idiomatic and adaptable; non-obvious lines are commented.

### 5.1 A clean abstraction layer over any provider (avoid lock-in)

Wrap the SDK so your business code never imports a vendor class. This is the single most valuable feature-flag practice for a backend codebase.

```java
// FeatureFlags.java — the only interface your domain code sees.
public interface FeatureFlags {
    boolean isEnabled(String key, User user);
    String variant(String key, User user, String defaultVariant);
}
```

```java
// LaunchDarklyFeatureFlags.java — the ONLY file that imports the vendor SDK.
import com.launchdarkly.sdk.*;
import com.launchdarkly.sdk.server.*;

public final class LaunchDarklyFeatureFlags implements FeatureFlags {

    private final LDClient client;

    public LaunchDarklyFeatureFlags(LDClient client) {
        this.client = client;
    }

    @Override
    public boolean isEnabled(String key, User user) {
        // boolVariation does LOCAL, in-memory evaluation (microseconds, no network).
        // The third arg is the fail-safe default if the flag is missing/SDK errors.
        return client.boolVariation(key, toContext(user), false);
    }

    @Override
    public String variant(String key, User user, String defaultVariant) {
        return client.stringVariation(key, toContext(user), defaultVariant);
    }

    private LDContext toContext(User user) {
        // The first arg (key) is the BUCKETING KEY — must be stable per user so
        // percentage rollouts are sticky. Attributes feed targeting rules.
        return LDContext.builder(user.id())
                .set("plan", user.plan())
                .set("country", user.country())
                .set("internal", user.isInternal())
                .set("appVersion", user.appVersion())
                .build();
    }
}
```

```java
// Bootstrap — done ONCE at app startup.
LDConfig config = new LDConfig.Builder()
        .startWait(java.time.Duration.ofSeconds(5)) // block up to 5s for first sync
        .build();
LDClient ld = new LDClient(System.getenv("LD_SDK_KEY"), config);
FeatureFlags flags = new LaunchDarklyFeatureFlags(ld);
// inject `flags` everywhere via your DI container (Spring, Guice, Dagger).

// On graceful shutdown, flush queued analytics events:
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try { ld.flush(); ld.close(); } catch (Exception ignored) {}
}));
```

**Why this matters:** swapping LaunchDarkly → Unleash → OpenFeature later means rewriting *one class*, not your whole codebase. It also gives you a single chokepoint for metrics, default policy, and testing fakes.

### 5.2 Use case: release toggle around incomplete work (TBD enabler)

A multi-week feature merged daily to trunk, dormant until done.

```java
public OrderResult placeOrder(Order order, User user) {
    validate(order);

    BigDecimal tax;
    if (flags.isEnabled("checkout-tax-engine-v2", user)) {
        // New engine — may still be partially built; merged behind the flag so
        // teammates integrate against it daily without it affecting users.
        tax = taxEngineV2.compute(order);
    } else {
        tax = legacyTaxCalculator.compute(order); // proven, safe default
    }

    return persist(order.withTax(tax));
}
```

### 5.3 Use case: ops kill-switch around an expensive/3rd-party dependency

```java
public List<Product> recommendations(User user) {
    // Kill-switch: if the recommender service is degraded or expensive under load,
    // on-call flips this OFF in seconds — no deploy — and we serve a cheap fallback.
    if (!flags.isEnabled("enable-recommendations", user)) {
        return popularProductsFallback(); // graceful degradation
    }
    try {
        return recommenderClient.fetch(user); // network call to 3rd party
    } catch (RecommenderTimeoutException e) {
        // Defense in depth: even with the flag on, fail soft.
        return popularProductsFallback();
    }
}
```

Notice the **flag is the *primary* control and the try/catch is *secondary***. The flag lets a human intervene instantly during an incident; the catch handles transient faults automatically. Both are needed.

### 5.4 Use case: progressive rollout with targeting (canary → 100%)

The *code* doesn't change as you ramp — only the flag rules do. Here's the code, then the rule progression done in the dashboard/API.

```java
@PostMapping("/search")
public SearchResponse search(@RequestBody SearchQuery q, @AuthenticationPrincipal User user) {
    if (flags.isEnabled("search-ranking-v3", user)) {
        return rankerV3.search(q);
    }
    return rankerV2.search(q);
}
```

Rule progression (LaunchDarkly-style, expressed via API/Terraform):

1. Day 1: rule `internal == true` → on. (Dogfood.)
2. Day 2: fallthrough rollout 1% (bucketed by user id; sticky).
3. Day 3: 5%, watch p99 latency + error rate + business KPI.
4. Day 4: 25%. Day 5: 50%. Day 6: 100%.
5. Any regression → set fallthrough to 0% (instant rollback).

LaunchDarkly via Terraform (vendor-specific, infra-as-code):

```hcl
resource "launchdarkly_feature_flag_environment" "search_v3_prod" {
  flag_id = launchdarkly_feature_flag.search_v3.id
  env_key = "production"
  on      = true

  # Internal users always on
  rules {
    clauses { attribute = "internal"; op = "in"; values = ["true"] }
    variation = 0   # 0 = "true" variation
  }

  # Everyone else: percentage rollout (change percent to ramp)
  fallthrough {
    rollout {
      variations { variation = 0; weight = 5000  }  # 5% (weights /100000)
      variations { variation = 1; weight = 95000 }  # 95% get old behavior
    }
  }
  off_variation = 1  # when flag turned off entirely, serve "false"
}
```

### 5.5 Use case: experiment / A/B test with a multivariate flag

```java
public PricingPage renderPricing(User user) {
    // Three-way experiment. The SDK assigns a sticky bucket per user.
    String variant = flags.variant("pricing-experiment", user, "control");
    return switch (variant) {
        case "annual-default" -> pricingPageAnnualDefault();
        case "show-savings"   -> pricingPageWithSavingsBadge();
        default               -> pricingPageControl();
    };
    // The SDK emits an EXPOSURE event (this user saw variant X).
    // Your conversion tracking emits a CONVERSION event when they subscribe.
    // The experimentation engine joins exposure↔conversion and computes lift.
}
```

The discipline: **assign once (sticky), expose once per render, track the outcome, let stats decide.** Don't read the variant in five places computing different things — assign in one place and pass it down.

### 5.6 Use case: permission / entitlement gating

```java
public ExportResult exportReport(User user, ReportSpec spec) {
    // Permission toggle: PERMANENT product configuration, not transient debt.
    if (!flags.isEnabled("premium-export", user)) {
        throw new FeatureNotEntitledException("Upgrade to Pro to export.");
    }
    return exporter.run(spec);
}
```

The flag rule targets `plan in [pro, enterprise]`. Because this is permanent, it is *not* flag debt and is *never* cleaned up — but you should still tag it as `permission` so it doesn't get swept up by debt tooling.

### 5.7 Use case: testing code that uses flags (both states)

You must test both flag states. Use a fake, not the real SDK, in unit tests.

```java
// A trivial in-memory fake for tests — no network, fully deterministic.
public final class FakeFeatureFlags implements FeatureFlags {
    private final Map<String, Boolean> bools = new HashMap<>();
    private final Map<String, String>  variants = new HashMap<>();

    public FakeFeatureFlags on(String key)  { bools.put(key, true);  return this; }
    public FakeFeatureFlags off(String key) { bools.put(key, false); return this; }
    public FakeFeatureFlags variant(String k, String v) { variants.put(k, v); return this; }

    @Override public boolean isEnabled(String key, User u) {
        return bools.getOrDefault(key, false); // default off = production-safe default
    }
    @Override public String variant(String key, User u, String def) {
        return variants.getOrDefault(key, def);
    }
}
```

```java
@Test
void placesOrder_withV2TaxEngine_whenFlagOn() {
    var flags = new FakeFeatureFlags().on("checkout-tax-engine-v2");
    var svc = new OrderService(flags, taxV2, legacyTax, repo);
    var result = svc.placeOrder(sampleOrder, sampleUser);
    assertThat(result.tax()).isEqualTo(expectedV2Tax);
}

@Test
void placesOrder_withLegacyTax_whenFlagOff() {
    var flags = new FakeFeatureFlags().off("checkout-tax-engine-v2");
    var svc = new OrderService(flags, taxV2, legacyTax, repo);
    var result = svc.placeOrder(sampleOrder, sampleUser);
    assertThat(result.tax()).isEqualTo(expectedLegacyTax);
}
```

For LaunchDarkly specifically, there's a `TestData` data source that lets you drive flag values in integration tests without a real connection:

```java
TestData td = TestData.dataSource();
td.update(td.flag("checkout-tax-engine-v2").variationForAll(true));
LDClient client = new LDClient("sdk-key",
        new LDConfig.Builder().dataSource(td).events(Components.noEvents()).build());
```

### 5.8 Use case: an OpenFeature (vendor-neutral) wiring

```java
import dev.openfeature.sdk.*;

// Set a provider once. Swap providers without touching business code.
OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new MyVendorProvider(sdkKey)); // blocks until ready
Client client = api.getClient();

EvaluationContext ctx = new ImmutableContext(
        user.id(),                                   // targeting key (bucketing)
        Map.of("plan", new Value(user.plan()),
               "country", new Value(user.country())));

boolean on = client.getBooleanValue("new-checkout-flow", false, ctx);
// getBooleanValue returns the default (false) on ANY error — fail-safe by contract.
```

### 5.9 Git: cutting a release branch in TBD and back-porting a fix

```bash
# Cut a release branch FROM trunk at release time (read-only-ish; not developed on).
git switch main && git pull --ff-only
git switch -c release/2026.06 && git push -u origin release/2026.06
git tag -a v2026.06.0 -m "Release 2026.06.0" && git push --tags

# A bug is found in prod. FIX IT ON TRUNK FIRST (so it's never lost).
git switch main
# ... commit the fix on trunk, get it merged via normal PR ... -> sha abc1234

# Forward-port the single fix to the release branch via cherry-pick.
git switch release/2026.06
git cherry-pick abc1234
git tag -a v2026.06.1 -m "Hotfix: ..." && git push --follow-tags
```

The rule: **fixes flow trunk → release branch (cherry-pick), never release → trunk (merge).** This guarantees trunk always has every fix and avoids re-introducing old bugs.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Flag checks must be local and ~free.** Server-side SDKs evaluate in-memory; a check is microseconds. *Never* introduce a per-request network call to a flag service on the hot path. If you see one, you're using a client-side/remote-eval model in the wrong place — add a relay/proxy or switch to local eval.
- **Bootstrap latency.** Blocking startup on the first flag sync (`startWait`) trades a few seconds of slower boot for correct flag values at first request. For most services this is worth it; for latency-critical autoscaling, consider bootstrapping from a cached file/SSM so cold starts don't wait on the network.
- **Event flushing is async and batched.** Don't call `flush()` per request. Do call it on graceful shutdown so you don't lose analytics.
- **Avoid flag checks in tight inner loops.** Hoist the check out of the loop; cache the boolean in a local for the duration of a request if you call it many times.

### 6.2 Correctness & concurrency

- **Flags introduce combinatorial state.** N boolean flags = up to 2^N runtime configurations. You cannot test all of them. *Mitigation:* keep concurrently-active *release* flags few, treat interacting flags explicitly, and remove flags promptly.
- **Sticky bucketing for consistency.** Always pass a *stable* targeting key. If you bucket by a value that changes (e.g., a per-request id), users flicker between variants — catastrophic for experiments and confusing for release rollouts.
- **Atomic config swaps.** SDKs apply flag updates atomically per flag, but if a *feature* depends on *two* flags being flipped together, you can observe a torn state. Prefer a single flag (or a multivariate flag) for a single decision.
- **Idempotency under rollback.** When you flip a flag off mid-flight, in-progress requests may have already taken the new path. Ensure both paths are correct in isolation and that flipping doesn't corrupt persisted state (e.g., don't write data in a format only the new path can read while the old path is still serving).

### 6.3 Security

- **Don't ship targeting rules to untrusted clients.** Browser/mobile SDKs must use remote evaluation (or a proxy); otherwise you leak segment definitions and other users' criteria.
- **SDK keys are secrets.** Server SDK keys can read all flags; treat like credentials, store in a secret manager, rotate them. Client-side keys are scoped/mobile keys — still don't embed server keys in clients.
- **Flags can become a backdoor.** A flag that toggles auth or rate-limiting is a security control surface. Gate flag *changes* with RBAC, require approvals for production flag changes, and **audit-log every flag change** (who, when, old→new). Many incidents are "someone flipped a flag."
- **PII in contexts.** Attributes you send for targeting (email, name) may be PII. Mark them private/hashed in the SDK config so they aren't stored/exported where they shouldn't be.

> **RBAC (Role-Based Access Control)** — permissions granted to roles, not individuals; users get a role. Used to control who can edit which flags in which environment.

### 6.4 Observability

- **Log/emit the *reason*** behind important flag evaluations (`getBooleanDetails`) so you can answer "why did this user get the new path?" in production.
- **Tag metrics, logs, and traces with active flag variations** for the request. When latency spikes for the canary cohort, you want to slice by flag variant. Add the variant as a span attribute / log MDC field.
- **Alert on flag-change events.** Feed the flag service's audit stream into your incident tooling; a sudden flip is a top suspect during incidents.
- **Track flag age and evaluation counts.** A flag at 100% for 90 days that's still evaluated everywhere is debt; a flag that's *never* evaluated is dead config.

> **MDC (Mapped Diagnostic Context)** — a per-thread key/value map in logging frameworks (Logback/Log4j2/SLF4J) that automatically tags every log line with context (request id, user, flag variant).

### 6.5 Cost

- **SaaS flag pricing** is often per *monthly active context* (MAU) and/or per *experiment event*. Sending a high-cardinality, unstable targeting key (e.g., per-request UUID) explodes MAU billing. Use stable keys; anonymize/aggregate where possible.
- **Self-hosting (Unleash/Flagsmith/flagd)** trades license cost for ops cost (you run and patch the control plane + a relay). Reasonable when you have many flags, strict data residency, or scale where SaaS MAU pricing dominates.
- **Event volume** for experimentation can be large; batch and sample where statistically acceptable.

### 6.6 Testing strategy with flags

- **Test both states** of every release flag in unit tests (fake the flag layer).
- **Test the *default* path under flag-system failure** — assert that with the provider offline, behavior equals the safe default. This is your production fail-mode.
- **Integration/contract tests** can pin specific flag combinations (use `TestData`/offline mode), but don't try to cover 2^N — cover the combinations you actually intend to run.
- **In CI, run the test suite with flags in the *production-default* state**, and separately with new-feature flags forced on (so the new path is exercised pre-release).
- **Avoid environment drift:** keep flag *definitions* (keys, default variations) in version control / IaC so test, staging, and prod agree on what exists.

### 6.7 Flag debt and cleanup (a first-class concern, not an afterthought)

> **Flag debt** — the accumulation of stale feature flags (especially *release* toggles) that are at 100% (or 0%) permanently but never removed. Each leaves dead `else` branches, untested combinations, cognitive load, and a latent footgun (someone flips an "old" flag and breaks prod).

Disciplines that work:

- **Expiry by design.** Give release toggles an owner and an expiry date at creation. Tools (LaunchDarkly's "flag status/age," Unleash's "potentially stale" detection) surface flags past their prime.
- **Make cleanup part of the feature's Definition of Done.** The feature isn't done at 100% rollout; it's done when the flag and dead branch are removed.
- **Tickets/automation.** Auto-create a cleanup ticket when a flag hits 100% for N days. Some teams fail the build if a flag exceeds a max age (aggressive but effective).
- **Distinguish types.** Only *release* and *experiment* flags are debt. *Permission* and many *ops* flags are permanent and must be excluded from debt sweeps via tags.
- **Code-side tooling.** Linters / codemods can detect and remove `if (flag) {...} else {...}` once a flag is decided.

> **Codemod** — an automated, large-scale code transformation script (e.g., using AST tools) that mechanically rewrites code — here, to delete a dead flag branch across the repo.

### 6.8 Production hardening checklist

- Safe code-level default on every evaluation.
- Provider failure → safe default (tested).
- Block (or cache-bootstrap) startup so first requests have real values.
- Audit log + RBAC + change approvals on prod flags.
- Kill-switches for every risky integration and expensive path.
- Stable bucketing keys; private PII attributes.
- Flag definitions in IaC; environment parity.
- Flag age/owner/expiry tracked; cleanup enforced.
- Metrics/traces tagged with variants; alert on flag changes.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it bites | Fix |
|---|---|---|
| **Long-lived feature branches** "to keep prod clean" | Merge hell; not real CI; semantic conflicts | TBD + release flags |
| **Per-request network call to flag service** | Latency + new failure mode on hot path | Local eval / relay proxy |
| **No code-level default** | Provider outage → undefined behavior | Always pass a safe default |
| **Vendor SDK imported everywhere** | Lock-in; hard to fake/test | Wrap behind one interface |
| **Unstable bucketing key** | Users flicker; experiments invalid | Stable per-user key |
| **Flags never cleaned up** | Flag debt; latent footguns; dead code | Expiry + DoD + tooling |
| **Nesting flags / flag combinatorics** | 2^N untestable states | Few concurrent release flags; combine into one decision |
| **Using a flag to hide a backward-incompatible DB change** without expand/contract | Old path can't read new data on rollback | Expand/contract migration (§7.4) |
| **Flag changes with no audit/RBAC** | Anyone can break prod silently | RBAC + approvals + audit log |
| **`develop` branch + "we do CI"** | Integration delayed to merge time | Integrate to trunk daily |

---

## 7. Advanced topics & deep internals

### 7.1 Deterministic bucketing math (how sticky % rollout really works)

The SDK must answer "is this user in the 5% rollout?" *without* a database and *consistently across every server*. The trick is a pure function of (flag, user):

1. Take a flag-specific **salt** (so the same user is in different buckets for different flags — avoids correlation across flags).
2. Concatenate `salt + "." + bucketingKey` (and possibly a per-rollout seed).
3. Hash with a fast, well-distributed hash (vendors use MD5/SHA-1 truncated, or murmur-like hashes).
4. Map the first bytes to an integer, divide into a 0..1 (or 0..100000) bucket.
5. Compare against cumulative variation weights.

Properties this guarantees:

- **Stickiness:** same user + same flag → same bucket forever (until rules change), so no flicker.
- **Statelessness:** every server computes the same answer with no coordination — horizontally scalable.
- **Independence across flags:** the salt decorrelates rollouts so a user isn't always "in the unlucky 5%."
- **Monotonic ramp (vendor-dependent):** if implemented as cumulative thresholds, increasing 5% → 10% *keeps* the original 5% and adds 5% more, rather than reshuffling — so users who saw the feature keep seeing it. (Check your vendor; some reshuffle on weight change unless you pin a seed.)

### 7.2 Streaming vs polling internals and the failure envelope

- **Streaming (SSE/WebSocket):** low propagation latency (sub-second to seconds), persistent connection, server pushes patches. Failure mode: connection drop → SDK reconnects with exponential backoff and re-syncs; meanwhile serves last-known values (**fail-static**).
- **Polling:** SDK fetches the full/diffed ruleset every interval (e.g., 15–30s). Higher propagation latency (up to one interval), simpler, more firewall-friendly. Failure mode: a failed poll just retries next interval.
- **Daemon/Relay mode:** a relay holds the stream and many SDKs read from it (or from a shared store like Redis). Reduces connections to the SaaS and centralizes egress; the relay becomes a thing you must make HA.

> **Fail-static** — on loss of connectivity, keep serving the last-known-good configuration rather than failing. The opposite, **fail-safe**, here means falling back to the code default when no value is available at all.

### 7.3 Branch-by-abstraction (large refactors on trunk without flags-everywhere)

> **Branch by Abstraction** — a technique to make a large-scale change *on trunk incrementally*: introduce an abstraction layer in front of the thing you're replacing, build the new implementation behind it, switch consumers over gradually, then delete the old implementation and (optionally) the abstraction. It is the *code-structure* counterpart to feature flags and lets you avoid a long-lived branch for big migrations.

Steps:

1. Create an abstraction (interface) over the component to be replaced.
2. Route existing callers through it (old impl behind it). Ship — no behavior change.
3. Build the new implementation behind the same abstraction.
4. Flip callers (often *via a flag*) to the new impl, incrementally.
5. Remove the old impl; optionally collapse the abstraction.

This pairs with flags: the *abstraction* lets both impls coexist on trunk; the *flag* controls which runs at runtime.

### 7.4 Flags + schema/data migrations: expand/contract

A frequent trap: a feature behind a flag also needs a DB change. If you ship the new schema and the new path together and then *roll back the flag*, the old code path may break on the new schema (or vice versa).

> **Expand/Contract (a.k.a. Parallel Change)** — migrate schemas in backward-compatible phases: **Expand** (add new columns/tables, write to both old and new), **Migrate** (backfill + dual-read), **Contract** (remove old). Each phase is independently deployable and rollback-safe.

Rule: **the data layer must support *both* flag states simultaneously** during the rollout window. Don't make a one-way data change gated by a flippable flag.

### 7.5 Server-side vs edge evaluation and consistency across tiers

When a flag affects *both* backend and frontend (e.g., a checkout flow), you can get **cross-tier inconsistency**: the browser (client SDK) sees the flag on while the backend (server SDK) hasn't propagated yet, or they bucket differently. Mitigations:

- Bootstrap the client SDK *from the server's evaluation* on page load (the server evaluates and injects the values into the HTML), guaranteeing the first render agrees with the backend.
- Use the *same bucketing key* on both tiers.
- Treat the backend as the source of truth for security-relevant decisions (never trust the client's flag value for authorization).

### 7.6 Flag governance at scale

- **Environments:** a flag has independent state per environment (dev/staging/prod). The *definition* is shared; the *rules* differ. Prevents "it worked in staging" surprises only if you keep them deliberately in sync where intended.
- **Prerequisites/dependencies:** some platforms let flag B require flag A to be on. Powerful but adds the combinatorics problem; use sparingly.
- **Approval workflows / change requests:** prod flag changes go through a review (4-eyes) like code. Critical for regulated environments.
- **Scheduled changes / workflows:** ramp automatically over time, or revert at a scheduled time.

### 7.7 Lesser-known behaviors and gotchas

- **Default-when-not-ready:** if you evaluate *before* the SDK finishes bootstrapping (and didn't block), you silently get code defaults — which may differ from prod rules. Always check readiness or block on init.
- **Variation index vs value:** internally flags map to a *variation index*; reordering variations in the UI can change which index a rule points to. Manage definitions in IaC to avoid surprises.
- **Anonymous contexts and MAU:** marking contexts anonymous (or aggregating) avoids per-user MAU billing for unauthenticated traffic.
- **Time-zone/clock in targeting:** date-based targeting uses the *server's* clock; clock skew across hosts can cause brief inconsistency at the boundary.
- **Event sampling:** high-traffic experiments may sample exposure events; ensure your stats engine accounts for the sampling rate.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Branching strategy comparison

| Dimension | GitFlow | GitHub Flow | Trunk-Based Dev |
|---|---|---|---|
| Branches | master, develop, feature, release, hotfix | main + short topic branches | trunk + very short branches (+ cut release branches) |
| Branch lifetime | days–weeks | hours–days | hours (< 1 day) |
| Integration cadence | at feature-merge (delayed) | per PR (frequent-ish) | ≥ daily (continuous) |
| Enables true CI? | No (integration deferred) | Mostly | Yes (by definition) |
| Hides incomplete work via | long branches | short branches | **feature flags** |
| Best for | versioned/on-prem/library releases | small/mid web teams | high-throughput web services, CD |
| Merge-hell risk | High | Medium | Low |
| Rollback model | revert release / hotfix branch | revert PR + redeploy | flag flip + revert commit |
| DORA correlation | weaker | medium | strongest (elite) |

**Use GitFlow when:** you ship discrete, versioned releases to environments you don't control (mobile app store, on-prem, libraries with semantic versioning) and must maintain multiple released versions. **Avoid GitFlow when:** you deploy a web service many times a day — `develop` and release branches add ceremony that fights CI.

**Use GitHub Flow when:** a small/medium team wants simple PR-based flow without flag infrastructure yet. **Avoid when:** branches routinely live for days and review latency causes divergence — tighten to TBD.

**Use TBD when:** you want CI/CD, high deploy frequency, and small batches; you have (or will build) feature-flag capability and fast automated tests. **Avoid (or adapt) TBD when:** you lack any automated testing (TBD without tests is just "everyone breaks main") — fix tests first; or for true library/firmware release trains, layer a release-branch process on top.

### 8.2 Build vs buy vs OSS for feature flags

| Option | Pros | Cons | Use when |
|---|---|---|---|
| **Hand-rolled (config flags)** | Zero cost, no dependency | No targeting/rollout/audit/UI; reinvents wheel | Tiny scope, few flags, no rollout needs |
| **OpenFeature + OSS provider (flagd, Unleash, Flagsmith)** | Vendor-neutral code, self-host, free license | You run/secure the control plane; fewer experimentation features | Data residency, cost at scale, want neutrality |
| **Unleash (OSS/SaaS)** | Mature OSS, strategies/constraints, self-host | Experimentation lighter than LD/Split | Self-host + good rollout/targeting |
| **LaunchDarkly (SaaS)** | Rich targeting, experimentation, governance, SDKs | MAU/experiment pricing; SaaS dependency | Enterprise scale, experimentation, governance needs |
| **Split / Optimizely** | Strong experimentation/stats | Cost; experimentation-centric | A/B testing is the primary need |

> **flagd** — the CNCF/OpenFeature reference flag-evaluation daemon: a lightweight self-hosted evaluator that reads flag definitions from files/Kubernetes/HTTP and serves OpenFeature SDKs. Good default for vendor-neutral self-hosting.

### 8.3 Feature-flag type decision rules

- **Transient + dev-owned + hides WIP** → release toggle. Plan to delete it.
- **Long-lived + ops-owned + emergency control** → kill-switch. Keep it; document the runbook.
- **Measure variant impact** → experiment toggle. Sticky bucketing + exposure/conversion events.
- **Gate by entitlement/role/region** → permission toggle. Permanent; exclude from debt sweeps.

### 8.4 When *not* to use a feature flag

- For a config value that never needs runtime change → use plain config, not a flag.
- For decisions better made by infrastructure (traffic split between two deployments) → consider **canary/blue-green deploys** instead/also.
- For permanent branching of behavior that is really a product setting → model it as a setting, not a debt-tracked flag.

> **Blue-green deployment** — run two identical environments ("blue" live, "green" idle); deploy to green, then switch traffic. Instant rollback by switching back. An *infrastructure* analog to flags; complementary, not a replacement.

---

## 9. Failure modes & debugging

### 9.1 Branching/CI failure modes

| Symptom | Likely cause | Diagnose | Fix |
|---|---|---|---|
| Frequent painful merges | Long-lived branches | `git for-each-ref --sort=committerdate refs/heads` shows old branches | Shrink batch size; merge daily |
| Trunk green per-PR but broken after merge | Semantic conflict / merge-base race | Reproduce by merging both PRs locally; check post-merge CI | Enable **merge queue**; run CI on merge result |
| "We have CI but releases are scary" | Not actually integrating continuously | Measure branch age + merge frequency (DORA) | Move to TBD; flags for WIP |
| Lost hotfix in next release | Fixed on release branch, not trunk | `git log main --grep=<fix>` shows it missing | Fix on trunk, cherry-pick to release |
| History unbisectable | Messy merges, non-atomic commits | `git bisect` gives ambiguous results | Squash to atomic commits; linear history |

### 9.2 Feature-flag failure modes

| Symptom | Likely cause | Diagnose | Fix |
|---|---|---|---|
| Feature behaves differently per request for same user | Unstable bucketing key | Log `getBooleanDetails` reason + bucketing key | Use stable per-user key |
| All users get default unexpectedly | SDK not initialized / provider down | Check SDK ready state, connection logs, reason=`ERROR`/`DEFAULT` | Block on init; verify SDK key; check egress |
| Flag flip didn't take effect | Wrong environment; stale poll; client cache | Compare flag value across env in dashboard; check propagation logs | Flip correct env; verify streaming; bust client cache |
| Latency spike on hot path | Per-request remote eval | Trace shows network call inside flag check | Switch to local eval / relay proxy |
| Rollback broke data | One-way data change behind flippable flag | Inspect schema vs both code paths | Expand/contract migration |
| Mystery prod change, no deploy | Someone flipped a flag | **Flag audit log** | RBAC + approvals; alert on flag changes |
| Experiment results invalid | Flicker / re-bucketing on ramp / sampling | Check stickiness, seed, sampling rate | Pin seed; stable key; correct stats |
| Billing spiked | High-cardinality contexts (per-request keys) | MAU report shows huge unique contexts | Stable/anonymous keys; aggregate |

### 9.3 Debugging tools and commands

- **`git log --graph --oneline --all`, `git for-each-ref`, `git bisect`** — inspect history shape, find offending commits, surface stale branches.
- **SDK `getBooleanDetails` / `boolVariationDetail`** — get the *reason code* (`TARGETING_MATCH`, `SPLIT`, `DEFAULT`, `ERROR`) for any evaluation in prod.
- **Provider audit log / change history** — who changed which flag, when, old→new. First place to look during an incident.
- **SDK diagnostic/debug logging** — most SDKs can log evaluations and connection events at DEBUG; enable temporarily.
- **Relay/proxy health + connection metrics** — connection count, last-sync time, store size.
- **Distribution checks** — verify your percentage rollout actually hits ~the target % (count exposures by variant); a skewed distribution signals a bad key.

### 9.4 Real-world incident archetypes

- **The kill-switch that wasn't.** A team relied on a flag to disable a feature in an incident, but the flag was *evaluated once at startup* and cached — flipping it did nothing until restart. Lesson: evaluate kill-switches per-request, and test that flipping actually takes effect.
- **The fail-open flag.** A flag with no code default; during a provider streaming outage, the SDK returned the language's `false`/`null`, which happened to *enable* a dangerous path. Lesson: explicit, safe defaults; test provider-down behavior.
- **The unstable bucketing experiment.** An A/B test keyed on session id instead of user id; logged-in users on multiple devices/sessions saw both variants, contaminating the experiment and producing a "null result" for a feature that actually worked. Lesson: stable identity for bucketing.
- **GitFlow "CI" outage.** A team with a long-lived `develop` integrated a quarter's features at release time; two features had a semantic conflict no test caught individually, breaking prod. Lesson: integrate continuously (TBD), test the merge result.
- **Flag-debt footgun.** An engineer "cleaned up" by flipping an old release flag they thought was unused; it was still wired to dead-but-reachable code, taking down checkout. Lesson: remove flags *and* their branches together; tag permanent flags.

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. "Senior-signal" questions (tradeoff/justification) are marked ★.

**Q1. Why does trunk-based development enable CI in a way GitFlow does not?**
*Model answer:* CI is *defined* as integrating to a shared mainline frequently (≥ daily) with automated verification of each integration. TBD makes trunk the integration point and forces short-lived branches so integration is continuous. GitFlow defers a feature's integration until its branch merges into `develop`, which can be weeks later — so even with a build server, integration isn't continuous. A green build on an old branch is automated testing, not CI.
- *Follow-up: Can't you just run Jenkins on GitFlow?* Yes, but that's a CI *server*, not the *practice* of CI; the integration event is still delayed to merge time.
- *Follow-up: How do you hide incomplete features without long branches?* Merge them into trunk behind release flags (and/or branch-by-abstraction).
- *Follow-up: Does TBD forbid release branches?* No — you cut them from trunk at release time and forward-port fixes via cherry-pick; you don't develop on them.

**Q2. Explain the difference between deploy and release, and why it matters.**
*Model answer:* Deploy = code present and running in prod (technical event). Release = behavior visible to users (product event). Feature flags decouple them: you deploy code with the flag off (deployed, not released) and flip later (released). This gives independent timing, gradual rollout, instant rollback, targeting, and experimentation.
- *Follow-up: How does this change rollback?* Rollback becomes a flag flip (seconds) rather than redeploying the previous build (minutes), and is scoped to the feature.
- *Follow-up: Any case where decoupling hurts?* It adds runtime complexity and flag debt; for trivial, non-risky changes a plain deploy is simpler.

**Q3. How does percentage rollout stay consistent across many servers with no shared database?**
*Model answer:* Deterministic bucketing — hash(flag salt + stable bucketing key) → bucket in [0,N); if bucket < rollout%, user is in. Pure function of (flag, user), so every server computes the same answer with no coordination, and the assignment is sticky per user. A per-flag salt decorrelates which users are "in" across flags.
- *Follow-up: Why a per-flag salt?* So the same user isn't always in the unlucky bucket across all flags.
- *Follow-up: What breaks stickiness?* Using an unstable key, or a vendor that reshuffles on weight changes without a pinned seed.

**Q4. ★ When would you choose GitFlow over trunk-based development?**
*Model answer:* When you ship discrete, versioned releases to environments you don't control — mobile apps (store review), on-prem/installed software, libraries needing maintained release lines — where multiple released versions must be supported simultaneously and explicit release/hotfix branches add real value. For a continuously deployed web service, GitFlow's ceremony fights CI; TBD is better.
- *Follow-up: Can you blend them?* Yes — TBD on trunk for daily work, with cut-from-trunk release branches and cherry-picked backports for the versioned release train.
- *Follow-up: What metric would tell you GitFlow is hurting you?* Branch age and merge frequency (DORA); long branches + rare merges + scary releases.

**Q5. ★ A feature needs both new code and a backward-incompatible DB change, behind one flag. What's the risk and how do you design it?**
*Model answer:* Risk: if you flip the flag off (rollback), the old code path may not handle the new schema (or new path can't read old). A flippable flag implies *both* states must work *simultaneously*. Solution: expand/contract (parallel change) — expand schema (add, dual-write), migrate/backfill (dual-read), then contract (remove old) only after the flag is permanently at 100%. Never make a one-way data change gated by a two-way flag.
- *Follow-up: What ordering do you deploy in?* Expand first (safe alone), then ship flagged code that can use either, ramp, then contract last.
- *Follow-up: How does this interact with rollback?* During expand/migrate, both paths read/write compatibly, so flag rollback is safe; only contract is irreversible, done after full release.

**Q6. How should flag checks be evaluated for performance and reliability on a hot path?**
*Model answer:* Locally and in-memory (server-side SDK), microseconds, no per-request network call. The SDK bootstraps the full ruleset and gets pushed updates via streaming; if disconnected it serves last-known values (fail-static) and on missing data returns your code-level default (fail-safe). Never put a remote eval on the request hot path — add a relay/proxy or use local eval.
- *Follow-up: How fast does a flip propagate?* Sub-second to a few seconds with streaming; up to one interval with polling.
- *Follow-up: What if the SDK isn't initialized yet?* You get code defaults silently — block on init or check readiness.

**Q7. What are the four feature-flag types and how do their lifecycles differ?**
*Model answer:* Release (transient, dev-owned, hides WIP — delete after rollout); Ops/kill-switch (long-lived, ops-owned, emergency control — keep); Experiment (transient, data-owned, A/B with sticky bucketing + events — delete after conclusion); Permission/entitlement (permanent, product-owned, gates by plan/role/region — never debt). Lifespan and ownership dictate management and whether cleanup applies.
- *Follow-up: Which are flag debt?* Release and experiment flags primarily; permission and many ops flags are permanent — exclude from debt sweeps via tags.
- *Follow-up: Can a flag change type?* A release flag occasionally becomes a permanent kill-switch; re-tag and re-own it deliberately.

**Q8. ★ How do you keep feature flags from becoming unmanageable as you scale to hundreds of them?**
*Model answer:* Governance + hygiene: wrap the SDK behind one interface; track owner + expiry + type on every flag; enforce cleanup in Definition of Done and via tooling (stale-flag detection, auto-tickets, codemods); limit concurrent *release* flags to bound the 2^N state space; manage definitions in IaC for env parity; RBAC + approvals + audit logs on prod changes; tag permanent flags so debt tooling ignores them. Measure flag age and evaluation counts continuously.
- *Follow-up: Why bound the number of concurrent release flags?* N booleans = 2^N runtime configurations you can't fully test; interactions cause bugs.
- *Follow-up: How do you safely delete a flag?* Confirm it's at a terminal state (100%/0%) long enough, remove the conditional and dead branch in code, then delete the flag from the system — both, together.

**Q9. How do you test code that depends on feature flags?**
*Model answer:* Fake the flag layer (in-memory) so tests are deterministic and offline; test *both* states of every release flag; explicitly test the *default* path under provider failure (assert safe behavior). Use the SDK's offline/`TestData` mode for integration tests pinning specific combinations, but don't try to cover 2^N. In CI, run the suite with prod-default flags and separately with new flags forced on.
- *Follow-up: Why test the provider-down path?* It's your real production failure mode; you must know behavior equals the safe default.
- *Follow-up: How do you prevent env drift in tests?* Keep flag definitions in version control/IaC so all envs agree on what exists.

**Q10. What's a merge queue and what problem does it solve?**
*Model answer:* It serializes merges, testing each PR rebased onto the result of the queue so far, landing only if still green. It solves the semantic-conflict race where two PRs are each green alone but break trunk when combined — a real risk in high-throughput repos and a classic gap in "CI server but not CI practice."
- *Follow-up: Why isn't per-PR CI enough?* Because per-PR CI tests an old merge base; the combination with other concurrently-landing PRs isn't tested until the queue does it.
- *Follow-up: Cost?* Throughput overhead (serialized testing); mitigated by batching/optimistic queues.

**Q11. ★ Argue for or against committing straight to trunk vs short-lived branches with PRs.**
*Model answer:* Direct-to-trunk minimizes batch size and latency to integration and pairs naturally with pair/mob programming and fast tests; it demands high discipline and excellent automated tests (a bad commit breaks everyone immediately). Short-lived branch + PR adds review and CI gating with minimal divergence cost if branches truly live < 1 day. Choose direct-to-trunk for small senior teams with strong tests/pairing; choose short-lived-branch+PR for most teams needing async review and protected main. Both are valid TBD; the failure mode of either is letting branches grow.
- *Follow-up: What guardrails make direct-to-trunk safe?* Fast comprehensive tests, pre-commit hooks, pairing, immediate revert culture, feature flags for WIP.
- *Follow-up: When does PR review become an anti-pattern?* When it injects multi-day latency that grows branches — fix the latency, don't drop review.

**Q12. How do you do an emergency rollback of a bad release, and how do flags change it?**
*Model answer:* Without flags: redeploy the previous artifact (minutes, affects everything in that build). With flags: flip the kill-switch/release flag to 0% (seconds, scoped to that feature, no deploy). Best practice: every risky feature ships behind a flag so rollback is a flip; also keep `git revert` + redeploy as the deeper undo for code that can't be flag-gated.
- *Follow-up: Why prefer flag flip over `git revert`?* Speed and scope — seconds vs a full pipeline, and only the bad feature, not unrelated changes in the same build.
- *Follow-up: When can't a flag save you?* When the bad code ran before the check, mutated state irreversibly, or the change wasn't flag-gated — hence expand/contract and gating risky paths.

---

## 11. Glossary

- **Activation strategy (Unleash)** — named algorithm deciding when a toggle is on (e.g., `flexibleRollout`, `userWithId`).
- **Artifact** — the built, deployable output (JAR, container image) promoted through environments.
- **Batch size** — amount of change per integration; smaller is better for CI.
- **Blue-green deployment** — two identical environments; switch traffic between them for instant rollback.
- **Branch (Git)** — a movable named pointer to a commit; not a copy of files.
- **Branch by abstraction** — incremental large refactor on trunk via an interface layer, swapping implementations gradually.
- **Branch protection** — rules forbidding direct push and requiring PRs/checks/reviews.
- **Bucketing key** — the stable context value hashed to assign a user to a rollout/experiment bucket.
- **Canary** — releasing to a small subset first, watching health, then expanding.
- **CD** — Continuous Delivery (every change releasable) or Continuous Deployment (every change auto-deployed).
- **Cherry-pick** — apply one commit's diff as a new commit on another branch.
- **CI (Continuous Integration)** — integrating to a shared mainline frequently with automated verification.
- **CNCF** — Cloud Native Computing Foundation; hosts OpenFeature, flagd, Kubernetes, etc.
- **Codemod** — automated large-scale code transformation (e.g., to remove dead flag branches).
- **Commit** — immutable snapshot + parent links; identified by a content hash.
- **Conflict (merge)** — both sides changed the same lines; requires human resolution.
- **Context / evaluation context** — the who/what (key + attributes) a flag is evaluated against.
- **DAG** — directed acyclic graph; Git history is one.
- **Deploy** — getting code running in an environment (technical event).
- **Deterministic bucketing** — pure hash function of (flag, key) → bucket; stateless, sticky, server-consistent.
- **DORA** — DevOps Research and Assessment; links TBD/short branches/CD to elite delivery.
- **DVCS** — distributed VCS; every clone has full history (Git).
- **Edge / relay / proxy** — service between client SDKs and the flag backend; evaluates/caches, hides rules.
- **Expand/contract (parallel change)** — backward-compatible, phased schema migration.
- **Experiment toggle** — flag routing cohorts to variants for measured A/B/n tests.
- **Fail-safe** — return a safe code default when no flag value is available.
- **Fail-static** — keep serving last-known-good config when connectivity is lost.
- **Fast-forward merge** — pointer slide with no merge commit when history hasn't diverged.
- **Feature flag / toggle** — runtime conditional that turns behavior on/off without redeploy.
- **flagd** — CNCF OpenFeature reference self-hosted evaluation daemon.
- **Flag debt** — stale, terminal-state flags left in code/system; latent risk + dead code.
- **GitFlow** — release-centric model with master/develop/feature/release/hotfix branches.
- **GitHub Flow** — lightweight model: main always deployable + short topic branches via PRs.
- **HEAD** — pointer to the currently checked-out branch/commit.
- **IaC (Infrastructure as Code)** — managing config/infrastructure (including flag definitions) as version-controlled code.
- **Kill-switch / ops toggle** — long-lived flag to disable a feature/path during incidents.
- **LaunchDarkly** — SaaS feature-flag/experimentation platform.
- **Lead time for changes** — DORA metric: time from commit to running in prod.
- **Local evaluation** — SDK evaluates flags in-process from a cached ruleset (microseconds).
- **MAU (monthly active users/contexts)** — common SaaS flag billing unit.
- **MDC** — Mapped Diagnostic Context; per-thread logging context map.
- **Merge** — combining divergent history; may produce a merge commit and conflicts.
- **Merge base** — most recent common ancestor of two branches.
- **Merge queue / train** — serializes merges, testing each against the latest trunk before landing.
- **OpenFeature** — CNCF vendor-neutral feature-flag API/SDK with pluggable providers.
- **Permission / entitlement toggle** — permanent flag gating by plan/role/region/license.
- **PR / MR** — Pull/Merge Request; unit of review + automated checks.
- **Progressive rollout** — gradually increasing the percentage of users a feature is released to.
- **Provider (OpenFeature)** — plugin connecting the OpenFeature API to a concrete backend.
- **RBAC** — Role-Based Access Control; gate who can change which flags.
- **Rebase** — replay commits onto another base for linear history (rewrites hashes).
- **Relay Proxy** — see Edge/relay/proxy.
- **Release** — making a feature visible to users (product event).
- **Release branch (TBD)** — cut from trunk at release time; fixes cherry-picked forward, not developed on.
- **Release toggle** — transient flag hiding in-progress code in trunk.
- **Revert (git)** — new commit that undoes a prior commit (no history rewrite).
- **Rollout (percentage)** — fraction of users a flag returns "on" for, via deterministic bucketing.
- **Salt (flag)** — per-flag value mixed into the bucketing hash to decorrelate rollouts.
- **SDK** — embedded client library for fetching/evaluating flags.
- **Semantic conflict** — code that merges cleanly but behaves wrongly combined; Git can't detect it.
- **Short-lived branch** — branch living hours to ~1 day; core TBD discipline.
- **SLO** — Service Level Objective; reliability target watched during rollouts.
- **Squash merge** — collapse a branch's commits into one on trunk.
- **SSE** — Server-Sent Events; HTTP streaming used to push flag updates.
- **Sticky** — same user consistently gets the same variation (no flicker).
- **Stickiness field** — the attribute Unleash bucket-hashes on (e.g., userId).
- **Targeting** — returning different flag values based on context attributes/segments.
- **TestData source** — SDK mechanism to drive flag values in tests without a real connection.
- **Three-way merge** — Git merge using base + ours + theirs.
- **Trunk** — the single shared mainline branch (usually `main`).
- **Trunk-based development (TBD)** — integrate to trunk ≥ daily via very short branches; hide WIP behind flags.
- **Unleash** — open-source (and SaaS) feature-flag platform with activation strategies.
- **Variant / variation** — one of multiple possible flag values (boolean, string, JSON, multivariate).
- **VCS** — Version Control System.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Branching**
- TBD = integrate to trunk **≥ daily**, branches live **< 1 day**, hide WIP behind **flags**.
- GitFlow = versioned/on-prem releases; **not** true CI for web services.
- GitHub Flow = main always deployable + short PR branches.
- Real CI = continuous *integration*, not "a build server on an old branch."
- Fix on trunk → **cherry-pick** to release branch (forward-only). Undo on trunk with **`git revert`**, never rewrite shared history.
- Use **merge queue** to kill the "green alone, broken together" race.
- DORA: TBD + short branches + CD ⇒ elite delivery.

**Deploy ≠ Release**
- Deploy = code in prod (flag off). Release = behavior on (flag flip). Decoupling buys timing, gradual rollout, instant rollback, targeting, experiments.

**Flag types**
- Release (transient, delete it) · Ops/kill-switch (long-lived, keep) · Experiment (sticky + events) · Permission (permanent, not debt).

**Evaluation internals**
- Server SDK = **local, in-memory, microseconds**; updates pushed via **streaming (SSE)** in sub-second–seconds; **fail-static** on disconnect, **fail-safe** to code default on miss.
- % rollout = **deterministic hash(salt + stable key)** → sticky, stateless, server-consistent.
- Always pass a **stable bucketing key** and a **safe code default**.

**Best practices / numbers**
- Wrap the vendor SDK behind **one interface**.
- Block on init (`startWait` default ~5s in LD) or check readiness.
- Test **both** flag states + the **provider-down default** path.
- Governance: owner + expiry + type per flag; RBAC + approvals + **audit log**; clean up in DoD; limit concurrent release flags (2^N states).
- Big DB change behind a flippable flag ⇒ **expand/contract**.
- Don't put a **remote eval on the hot path**; don't use an **unstable bucketing key**; don't **skip cleanup**.

### 12.2 Self-test (no answers — active recall)

1. Explain precisely why a green Jenkins build on a three-week-old GitFlow feature branch is *not* "doing CI," and what would have to change to make it CI.
2. Walk through, step by step, how a server-side SDK evaluates `isEnabled("x", user)` for a user in a 7% rollout, and prove the result is identical on two different servers with no shared state.
3. You must roll out a feature that also requires dropping a column. Design the deploy/flag/migration sequence so that flipping the flag off at any point is safe.
4. A/B test results come back "no significant difference," but engineers swear the feature works. List the flag-level bugs that could produce a false null, and how you'd detect each.
5. Design a flag-governance scheme for a 300-engineer org with ~400 flags: what metadata, controls, and automation keep flag debt and the 2^N state space under control — and how do you treat permission flags differently from release flags?
6. Compare flag flip vs `git revert` + redeploy vs blue-green switch as rollback mechanisms; give the latency, scope, and failure cases of each, and state when a flag flip *cannot* save you.

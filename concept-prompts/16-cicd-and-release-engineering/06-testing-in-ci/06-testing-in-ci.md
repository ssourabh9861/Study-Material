# Testing in CI

> An exhaustive engineering-handbook chapter on automated testing inside Continuous Integration pipelines, written for senior JVM/Java backend engineers who want to master the subject from first principles through deep internals, production operation, and interview-grade depth.

---

## 1. Overview & where it fits

### 1.1 What "Testing in CI" means

**CI (Continuous Integration)** is the practice of merging every developer's work into a shared mainline frequently — ideally many times per day — and verifying each merge automatically. The verification is the part this chapter is about: **automated tests that run on a build server every time code changes**, so that a regression is caught within minutes of being introduced rather than weeks later in production.

The phrase "testing in CI" therefore covers a stack of distinct, overlapping concerns:

- **Which tests to write** at each level (unit, integration, end-to-end, contract, mutation).
- **How to run them reliably** on shared infrastructure (Testcontainers, ephemeral databases, test data setup/teardown).
- **How to run them fast** (parallelization, sharding, test selection).
- **How to keep them trustworthy** (flaky-test detection, quarantine, fixing).
- **How to use their output as a release gate** (coverage thresholds, required checks, deploy gating).

### 1.2 The problem it solves

Without automated tests in CI, every change is verified by a human, manually, sometime later — if at all. This produces three chronic failures:

1. **Slow feedback.** A bug introduced on Monday is found in a manual QA pass on Friday, after the author has forgotten the context and built more code on top of the broken foundation.
2. **Regression decay.** Old features silently break because nobody re-tests them. The codebase becomes a minefield where any change might detonate something unrelated.
3. **No release confidence.** Teams either ship rarely (big risky releases) or ship recklessly (frequent breakage). Neither is sustainable.

CI testing converts verification from a *human-scheduled, occasional* activity into a *machine-enforced, every-commit* invariant. The mental model: **CI is a tireless, fast, impartial reviewer that re-runs your entire correctness spec on every push and blocks merges that violate it.**

### 1.3 When you reach for it

You reach for CI testing essentially always for any non-trivial software project. The interesting decisions are not *whether* but *what shape*:

- A small library with pure functions: mostly unit tests, fast, run on every push.
- A microservice talking to Postgres, Kafka, and Redis: unit tests plus Testcontainers-backed integration tests plus consumer-driven contract tests against its collaborators.
- A monolith with a web UI: a broad unit base, a moderate integration layer, and a *thin* end-to-end layer (the test pyramid, below).

### 1.4 The one-paragraph mental model

> Think of your test suite as a **layered filter**. Cheap, fast, numerous unit tests catch most logic bugs in milliseconds. A smaller band of integration tests catches wiring and serialization bugs that only appear when real components (a real database, a real message broker) interact. A tiny set of end-to-end tests catches the "everything is plugged in wrong" failures. Contract tests catch the specific case where two independently deployed services disagree about their shared API. CI runs all of these on every change, in parallel, gates merges and deploys on their results, and you continuously fight entropy: flaky tests that erode trust, slow tests that erode iteration speed, and coverage metrics that tempt you to game them.

---

## 2. Foundations from first principles

This section builds the vocabulary. Each term is defined the first time it appears.

### 2.1 What is a test, mechanically?

A **test** is a program that exercises some unit of your system under known conditions and asserts that the observed behavior matches the expected behavior. An **assertion** is a check that throws (fails the test) if a condition is false — e.g. `assertEquals(expected, actual)`. A test that runs to completion with no failed assertion and no unexpected exception **passes**; otherwise it **fails**.

A **test runner** (also "test framework") is the harness that discovers tests, executes them, isolates failures, and reports results. On the JVM the dominant one is **JUnit** (currently **JUnit 5**, also called **JUnit Jupiter**); **TestNG** is an alternative; **Spock** (Groovy) is popular for its expressive BDD-style syntax.

### 2.2 What is CI, mechanically?

A **CI server** (or "CI runner" / "build agent") is a machine or container that, triggered by a version-control event (a push, a pull request), checks out the code, runs a defined sequence of steps (compile, test, package), and reports pass/fail back to the version-control system. Examples: **GitHub Actions**, **GitLab CI**, **Jenkins**, **CircleCI**, **Buildkite**, **TeamCity**, **Azure Pipelines**.

A **pipeline** is the declarative description of those steps, usually a YAML file in the repo (e.g. `.github/workflows/ci.yml`, `.gitlab-ci.yml`, `Jenkinsfile`). A **job** is one runnable unit (often "run the tests"); a **stage** groups jobs that run in sequence (e.g. build → test → deploy). A **status check** is the pass/fail signal a job reports to the PR; a **required check** is one that must be green before a merge is allowed (a "branch protection rule" on GitHub).

### 2.3 The taxonomy of tests

These categories are not crisply defined across the industry, but the working definitions used in practice:

- **Unit test** — exercises a single unit (typically one class or a small cluster of classes) in isolation, with all external collaborators replaced by **test doubles**. Fast (microseconds to low milliseconds), no I/O, fully deterministic.
- **Integration test** — exercises several real components together, often including real I/O: a real database, a real HTTP call, a real message broker. Slower (tens of milliseconds to seconds), verifies *wiring*, *serialization*, *SQL correctness*, *transaction behavior*.
- **End-to-end (E2E) test** — exercises the whole system through its real entry points (HTTP API, UI) as a user would, with everything wired together. Slowest (seconds to minutes), brittle, but catches "the whole thing is mis-assembled" bugs.
- **Contract test** — verifies that two independently deployed services agree on the shape and semantics of the messages they exchange, without running both services together. (Detailed in §3.5.)
- **Component test** — a fuzzy middle ground: exercises one service in isolation but through its real external interface (e.g. spin up the whole Spring app, stub out collaborators, hit the HTTP API).
- **Smoke test** — a tiny, fast E2E test that just checks "is it alive and serving?" after a deploy.
- **Mutation test** — not a test you write; a *meta-test* that measures how good your existing tests are by deliberately introducing bugs. (Detailed in §3.6.)

### 2.4 Test doubles — the precise vocabulary

The umbrella term is **test double** (Gerard Meszaros's term, from *xUnit Test Patterns*). The five canonical kinds:

| Double | What it does |
|---|---|
| **Dummy** | A placeholder passed but never used (fills a parameter slot). |
| **Stub** | Returns hard-coded answers to calls made during the test. No verification. |
| **Spy** | A stub that also records how it was called, so you can assert on the calls afterward. |
| **Mock** | Pre-programmed with expectations about which calls it should receive; verifies them. |
| **Fake** | A working but simplified implementation (e.g. an in-memory map standing in for a database). |

In Java, **Mockito** is the dominant mocking library; it produces stubs/spies/mocks. **WireMock** is a fake/stub for HTTP services. **H2** is a fake (in-memory SQL database) often used as a stand-in for Postgres — though, as we'll see, that substitution causes subtle bugs.

### 2.5 Key correctness properties of a test

- **Deterministic** — same inputs always produce the same result. The opposite is **flaky** (sometimes passes, sometimes fails, with no code change). Flakiness is the single biggest operational pain of CI testing (§3.7, §9).
- **Isolated** — does not depend on the state left by other tests, the order of execution, the wall-clock time, the machine it runs on, or external services it doesn't control.
- **Fast** — proportional to the level (unit fast, E2E slow). Slow tests get skipped, disabled, or run rarely, which destroys their value.
- **Readable / diagnostic** — when it fails, the failure message tells you *what* broke and *why* without a debugging session.

### 2.6 Coverage — what the number actually measures

**Code coverage** is the percentage of your code that was executed while the tests ran. The common sub-types:

- **Line coverage** — fraction of executable lines run.
- **Branch coverage** — fraction of decision branches (both the true and false side of each `if`) taken.
- **Statement / instruction coverage** — at the bytecode-instruction level (what JaCoCo reports natively).
- **Method/class coverage** — coarse-grained.

Crucial first-principles point: **coverage measures execution, not verification.** A test can execute a line (covering it) while asserting nothing about its behavior. So coverage is a *necessary-but-not-sufficient* signal — useful for finding totally untested code, dangerous as a target (§6.6, §3.6 on mutation testing as the antidote).

---

## 3. How it works internally

This is the heart of the chapter. We trace each major mechanism step by step.

### 3.1 The test pyramid — the shape of a healthy suite

The **test pyramid** (popularized by Mike Cohn, refined by Martin Fowler) is a heuristic for *how many tests of each kind you should have*. Picture a triangle:

```
        /\
       /  \      E2E / UI         (few, slow, brittle, high-confidence-per-test)
      /----\
     /      \    Integration      (some, medium speed)
    /--------\
   /          \  Unit             (many, fast, cheap, isolated)
  /------------\
```

The reasoning, from first principles:

- **Unit tests are cheap to write, near-instant to run, and pinpoint failures** (a failing unit test names the exact class). So have lots of them — they're your bulk regression net.
- **Integration tests cost more** (they need real infrastructure, run slower, and a failure could be in any of several components) but catch a class of bugs units cannot — SQL mistakes, serialization mismatches, transaction boundaries, connection-pool exhaustion. Have a meaningful but smaller number.
- **E2E tests are expensive, slow, and flaky** (they depend on the entire stack being up and correct, including timing and network), but they're the only thing that proves the assembled product works. Have very few — just the critical user journeys.

The pyramid is a *ratio* heuristic, not a law. A common modern refinement is the **"testing trophy"** (Kent C. Dodds, originally for front-end) which fattens the integration band, arguing integration tests give the best confidence-per-effort. For JVM backends the classic pyramid still serves well, with the caveat that "integration" should mean *real-dependency* integration (Testcontainers), not slow E2E.

### 3.2 The inverted pyramid / "ice-cream cone" anti-pattern

The **ice-cream-cone anti-pattern** is the pyramid upside down:

```
  \------------/   Manual testing  (huge blob on top — done by humans)
   \----------/    E2E / UI         (many automated UI tests)
    \--------/     Integration      (some)
     \------/
      \----/       Unit             (few)
       \--/
        \/         (a tiny scoop, plus a cherry of manual QA on top)
```

How teams fall into it: they start with a UI, write Selenium tests against it (because that's how a non-developer thinks about "testing"), and skip unit tests because "the UI tests already cover it." The consequences are textbook:

- **Glacial feedback.** A full UI suite takes 30–90 minutes; developers stop running it locally and only see failures hours later.
- **Pervasive flakiness.** UI/E2E tests fail intermittently due to timing, rendering, network — eroding trust until red builds get ignored.
- **Poor localization.** A failing E2E test could be any of a hundred underlying causes; debugging is a forensic exercise.
- **High maintenance.** A small UI change breaks dozens of tests.

The fix is to **push tests down the pyramid**: every bug an E2E test catches that *could* have been caught by a unit or integration test should be re-caught there, and the E2E test pruned or kept only for the genuine end-to-end journey.

### 3.3 What belongs at each level (decision rules)

| Level | What belongs here | What does NOT belong here |
|---|---|---|
| **Unit** | Pure business logic, calculations, validation rules, state machines, mappers, edge-case branching, algorithms. | Anything needing a DB, network, filesystem, real clock, or random source. |
| **Integration** | SQL queries & ORM mappings, transaction/rollback behavior, message produce/consume, HTTP client/server serialization, framework wiring (DI), cache behavior. | Re-testing pure logic already covered by units; full multi-service journeys. |
| **Contract** | The shape & semantics of the messages between two services you own (or one you own + one you consume). | Internal logic of either service. |
| **E2E** | A handful of critical user journeys end-to-end (login → search → checkout). | Exhaustive edge-case coverage; anything a lower level can verify. |

A practical rule of thumb: **for each behavior, write the test at the lowest level that can meaningfully verify it.** Unit if pure; integration if it requires a real dependency to be correct; E2E only if it's genuinely an end-to-end concern.

### 3.4 Integration testing with Testcontainers — internal workflow

The historical problem: integration tests need a real Postgres / Kafka / Redis, but installing and managing those on every developer machine and CI runner is painful and non-reproducible. The two bad escapes were (a) shared test databases (stateful, flaky, contended) and (b) in-memory fakes like H2 (subtly different SQL dialect → tests pass but production breaks).

**Testcontainers** solves this by programmatically starting **the real dependency in a throwaway Docker container** for the duration of the test, then destroying it. ("Docker" here is the container runtime: it runs a packaged image — e.g. the official `postgres:16` image — as an isolated process with its own filesystem and network.)

Step-by-step internal workflow of a Testcontainers-backed test:

1. **Test class loads.** Testcontainers' JUnit 5 extension (`@Testcontainers` / `@Container`) hooks into the test lifecycle.
2. **Container start.** Testcontainers talks to the local Docker daemon (via the Docker API socket, default `/var/run/docker.sock`) and issues `docker run` for the chosen image with a **random free host port** mapped to the container's service port (e.g. Postgres 5432).
3. **Readiness wait.** Testcontainers blocks until a **wait strategy** says the container is ready — e.g. a log-message regex match, a TCP port being open, or an HTTP health check returning 200. This avoids the classic race where the test connects before the DB is accepting connections.
4. **Ryuk sidecar.** Testcontainers also starts a tiny companion container called **Ryuk** (the "resource reaper"). Ryuk watches the test JVM; if the JVM dies abruptly (crash, `kill -9`, CI timeout) without cleaning up, Ryuk removes the orphaned containers so they don't leak and fill the runner's disk.
5. **Wire the app.** Your test reads the *actual* mapped host/port (e.g. `container.getJdbcUrl()`) and points the application/DataSource at it. With Spring Boot, `@ServiceConnection` (Spring Boot 3.1+) or `@DynamicPropertySource` injects these values into the context automatically.
6. **Run assertions** against the real database/broker.
7. **Teardown.** Per-method `@Container` instances stop after each test; `static @Container` instances stop after the whole class. Ryuk is the backstop.

Two performance-critical patterns:

- **Singleton container / container reuse.** Starting a container costs ~1–5 seconds. If every test method restarts one, suites crawl. The fix: declare the container `static` (one per class) or use the **singleton container pattern** (a manually managed `static` container started once for the whole JVM), or enable **Testcontainers reuse** (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`) so the container survives across runs locally for fast iteration. Reuse is intended for *local dev*, not CI (CI runners are ephemeral anyway).
- **Database template / clean-between-tests.** Restarting the DB per test is too slow; instead start it once and **reset state between tests** (truncate tables, roll back the transaction, or restore from a template). (See §3.7 on test data.)

Testcontainers requires a Docker-compatible runtime on the CI runner. Alternatives when Docker isn't available: a managed remote Docker host, **Testcontainers Cloud** (a hosted runtime), or rootless runtimes like Podman.

### 3.5 Contract testing — internal workflow (consumer-driven, Pact)

The problem contract testing solves: in microservices, **Service A (consumer)** calls **Service B (provider)** over HTTP/messaging. They deploy independently. If B changes its response shape, A breaks — but you won't find out until both run together (in E2E or production). Spinning up the full graph for every change is slow and flaky. Contract testing lets each side verify the agreement **in isolation**.

**Consumer-driven contract testing** means the *consumer* defines what it actually needs from the provider, and the provider is verified against that. **Pact** is the leading framework (with the **Pact Broker** as the central exchange). Internal workflow:

1. **Consumer side — write a test against a mock provider.** In Service A's test suite, you use Pact's DSL to declare: "Given the provider is in state *user 42 exists*, when I GET `/users/42`, I expect a 200 with a body matching this shape." Pact spins up a **local mock HTTP server** that serves that response, and runs your *real consumer client code* against it.
2. **Pact file generated.** If the consumer test passes, Pact writes a **pact file** — a JSON document describing the interaction (request, expected response, provider state). This *is the contract*. Note it captures only the fields the consumer actually uses (consumer-driven), with **matching rules** (e.g. "this field is any integer," not the exact value).
3. **Publish to the Pact Broker.** The consumer's CI job publishes the pact file to the Pact Broker, tagged with the consumer's version and branch.
4. **Provider side — verify against the pact.** Service B's CI job fetches the relevant pacts from the broker and replays each interaction against the *real running provider* (started in-process or as a container). For each interaction it sets up the declared **provider state** (via a state-setup hook that, e.g., inserts user 42 into the DB), sends the recorded request, and checks the real response matches the contract's matching rules.
5. **Publish verification results** back to the broker.
6. **`can-i-deploy` gate.** Before deploying either service, CI runs `pact-broker can-i-deploy --pacticipant ServiceA --version <sha> --to-environment production`. The broker computes, from the **matrix** of which consumer/provider versions have verified against each other, whether the version you want to deploy is compatible with what's already in that environment. If not, the deploy is blocked.

The key win: **A and B are never run together**, yet their compatibility is provably checked. The contract lives in the broker as the source of truth. Alternatives/related: **Spring Cloud Contract** (provider-driven flavor popular in the Spring world, generates stubs from contracts written in Groovy/YAML), and plain **schema validation** (e.g. OpenAPI/JSON-Schema diffing) for looser guarantees.

### 3.6 Mutation testing — internal workflow

**Mutation testing** answers "are my tests actually any good, or do they just execute code without checking it?" The mechanism:

1. The tool (on the JVM: **PIT / pitest**) runs your test suite once to find the **baseline green** and which tests cover which lines (for targeting).
2. It then generates **mutants**: it makes small, systematic changes to your *production* bytecode — e.g. flips `>` to `>=`, replaces `+` with `-`, changes a `return true` to `return false`, removes a method call (a "void method call mutator"), negates a conditional.
3. For each mutant, it re-runs the subset of tests that cover the mutated line.
4. **Outcome per mutant:**
   - **Killed** — at least one test failed. Good: your tests detected the injected bug.
   - **Survived** — all tests still passed despite the bug. Bad: a logic change went unnoticed → a coverage gap *in verification*, not execution.
   - **No coverage** — no test even ran the line.
   - **Timed out / errored** — the mutant caused an infinite loop or crash (usually counted as killed).
5. The **mutation score** = killed / (total mutants − non-viable) — a far stronger quality signal than line coverage, because it measures whether tests *catch bugs*, not whether they *touch lines*.

Cost: mutation testing is expensive (it runs the suite many times). Mitigations: **incremental analysis** (PIT can analyze only mutants on changed lines using a history file), targeting specific packages, and running it nightly rather than per-commit.

### 3.7 Test data management & flaky tests — the operational core

These two are where CI testing actually lives or dies in production, so they get their own internal treatment in §6 and §9. Here, the mechanism in brief:

- **Test data management** is how each test gets the data it needs and ensures it doesn't pollute other tests. The strategies (transaction rollback, truncate-and-reseed, unique-per-test data, DB templates, fixtures/builders) are detailed in §6.4.
- **Flaky tests** are tests that pass and fail non-deterministically. The detection-quarantine-fix loop is the central operational discipline, detailed in §9.2.

### 3.8 Parallelization & sharding — internal workflow

To run a large suite fast, you split work across cores and machines.

- **In-JVM parallelism.** JUnit 5 can run test methods/classes concurrently in multiple threads (`junit.jupiter.execution.parallel.enabled=true`). The risk: shared mutable state (static fields, a single DB) causes interference → flakiness. You annotate resource access with `@ResourceLock` to serialize access where needed.
- **Fork-level parallelism.** Build tools fork multiple JVMs: Maven Surefire's `forkCount` / `parallel`, Gradle's `maxParallelForks`. Each fork is fully isolated (separate process) — safer than threads, costs more memory.
- **Sharding across machines.** The full suite is split into N groups (shards), each run on a separate CI runner in parallel; results are merged. Naive sharding splits by class count; **smart sharding** (e.g. via test-timing history) balances by *duration* so shards finish together. Tools: GitHub Actions matrix + a splitter, `gradle --parallel`, `knapsack_pro`, `pytest-split` (Python), JUnit's built-in nothing — you orchestrate it in the pipeline.
- **Test selection / predictive selection.** The most advanced form: only run the tests *affected* by the change (computed from a coverage→code map). Tools: Gradle's test selection, Bazel's dependency-graph-based selection, commercial offerings (Launchable). High payoff for huge monorepos.

The control flow for a sharded CI job: the pipeline defines a matrix of N parallel jobs; each job computes "my slice" of tests (by index N-of-M), runs them, uploads a partial report and partial coverage; a final aggregation job merges coverage and reports and posts the combined status check.

---

## 4. The complete toolkit

### 4.1 JVM test frameworks & assertion libraries

| Tool | Purpose | Key API / config | Defaults / notes |
|---|---|---|---|
| **JUnit 5 (Jupiter)** | Test runner | `@Test`, `@BeforeEach`, `@AfterEach`, `@BeforeAll`, `@AfterAll`, `@ParameterizedTest`, `@Nested`, `@Tag`, `@Timeout`, `@Disabled` | `@BeforeAll`/`@AfterAll` must be `static` unless lifecycle is `PER_CLASS`. |
| **JUnit 5 parallel** | Concurrency | `junit.jupiter.execution.parallel.enabled=true`, `...mode.default=concurrent`, `@Execution(CONCURRENT)`, `@ResourceLock` | Disabled by default; opt-in. |
| **TestNG** | Alternative runner | `@Test(groups=...)`, `@DataProvider`, suite XML | Rich grouping/parallel config via XML. |
| **AssertJ** | Fluent assertions | `assertThat(x).isEqualTo(...)`, `.extracting(...)`, `.satisfies(...)` | Best diagnostics; preferred over JUnit's `assertEquals`. |
| **Hamcrest** | Matcher assertions | `assertThat(x, is(...))` | Older style. |
| **Mockito** | Mocks/stubs/spies | `mock()`, `when(...).thenReturn(...)`, `verify(...)`, `@Mock`, `@InjectMocks`, `mockStatic` | Cannot mock `final`/`static` without `mockito-inline` (now default in recent versions). |
| **AssertJ-DB / DBUnit** | DB-state assertions | dataset XML, `assertThat(table)...` | DBUnit is older; many teams hand-roll. |
| **Awaitility** | Async assertions | `await().atMost(5, SECONDS).until(() -> ...)` | The correct way to wait instead of `Thread.sleep` (a major flakiness source). |

### 4.2 Integration & contract tooling

| Tool | Purpose | Key API / config | Defaults / notes |
|---|---|---|---|
| **Testcontainers** | Real deps in Docker | `@Testcontainers`, `@Container`, `PostgreSQLContainer`, `KafkaContainer`, `GenericContainer`, `@ServiceConnection` | Needs Docker; Ryuk reaper on by default (`TESTCONTAINERS_RYUK_DISABLED=true` to disable). |
| **Testcontainers reuse** | Faster local iteration | `withReuse(true)` + `testcontainers.reuse.enable=true` | Local only; not for CI. |
| **WireMock** | HTTP stub server | `stubFor(get(...).willReturn(...))`, `verify(...)` | Records/replays; supports fault injection. |
| **Pact (JVM)** | Consumer-driven contracts | `@PactTestFor`, `PactDslJsonBody`, `@Provider`, `@State`, `PactVerificationContext` | Pacts published to broker; matching rules over exact values. |
| **Pact Broker / PactFlow** | Contract exchange + gating | `pact-broker publish`, `can-i-deploy`, webhooks | Central source of truth; matrix of verifications. |
| **Spring Cloud Contract** | Provider-driven contracts | Groovy/YAML contracts → generated tests + stubs | Strong Spring integration. |
| **REST Assured** | HTTP API testing | `given().when().get(...).then().statusCode(200)` | For component/integration API tests. |
| **Spring Boot Test** | App-context testing | `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`, `MockMvc`, `@MockBean`, `TestRestTemplate`/`WebTestClient` | Slice annotations load minimal context for speed. |
| **Embedded Kafka** | In-JVM Kafka | `@EmbeddedKafka` (Spring) | Lighter than a container but less production-faithful than Testcontainers Kafka. |

### 4.3 Coverage & mutation tooling

| Tool | Purpose | Key config | Defaults / notes |
|---|---|---|---|
| **JaCoCo** | Coverage measurement | Maven `jacoco-maven-plugin` (`prepare-agent`, `report`, `check`), Gradle `jacoco` plugin; `<rule>` with `<limit>` for thresholds | Instruction & branch coverage by default; integrates as a Java agent. |
| **JaCoCo check rule** | Fail build on low coverage | `COVEREDRATIO` limit, e.g. `minimum=0.80` | Build fails if below threshold. |
| **PIT (pitest)** | Mutation testing | `pitest-maven`, `pitest-junit5-plugin`, `mutationThreshold`, `targetClasses`, `withHistory` (incremental) | Expensive; use incremental + targeting. |
| **Codecov / Coveralls / SonarQube** | Coverage reporting & PR comments | Upload coverage XML; PR diff coverage; quality gates | SonarQube adds "new code" coverage gates (better than total). |

### 4.4 Build tools & test execution flags (Maven/Gradle)

| Tool | Flag / config | Purpose | Default |
|---|---|---|---|
| **Maven Surefire** | runs *unit* tests in `test` phase | `**/*Test.java` by default | bound to `test` phase |
| **Maven Failsafe** | runs *integration* tests in `integration-test`/`verify` | `**/*IT.java` by default | so IT failures don't skip post-cleanup |
| Surefire | `-DforkCount=1C` | one fork per CPU core | `forkCount=1` |
| Surefire | `parallel=classes/methods`, `threadCount` | in-fork parallelism | none |
| Surefire | `-Dtest=FooTest#bar` | run a single test | — |
| Surefire | `rerunFailingTestsCount=2` | auto-retry failures | `0` (off) — use with caution, masks flakiness |
| **Gradle** | `test { maxParallelForks = ... }` | parallel forks | 1 |
| Gradle | `test { useJUnitPlatform() }` | enable JUnit 5 | — |
| Gradle | `--tests "com.x.FooTest"` | filter | — |
| Gradle | `test { failFast = true }` | stop on first failure | false |
| Gradle | build cache / `--parallel` | skip up-to-date tasks, parallel modules | cache off by default |
| Maven/Gradle | tag/group filtering | `-Dgroups=fast` / `useJUnitPlatform { includeTags 'fast' }` | run subsets per pipeline stage |

### 4.5 CI-side primitives

| Concept | GitHub Actions | GitLab CI | Jenkins |
|---|---|---|---|
| Pipeline file | `.github/workflows/*.yml` | `.gitlab-ci.yml` | `Jenkinsfile` |
| Parallel matrix | `strategy.matrix` | `parallel: matrix` / `parallel: N` | parallel stages / Matrix |
| Service containers | `services:` (Docker) | `services:` | sidecar via Docker plugin |
| Caching | `actions/cache` | `cache:` | plugins |
| Required check / gate | branch protection rules | merge request approval rules / `needs:` | merge checks |
| Test report ingest | `actions/upload-artifact` + reporters | `artifacts: reports: junit:` | JUnit plugin |

---

## 5. Code examples by use case

> Examples target Java 17+, JUnit 5, Spring Boot 3.x, Testcontainers, Pact JVM, Maven/Gradle. Comments mark the non-obvious lines.

### 5.1 A clean unit test (pure logic, no I/O)

```java
// PricingService applies a discount and never returns a negative price.
class PricingServiceTest {

    private final PricingService service = new PricingService();

    @Test
    void appliesPercentageDiscount() {
        Money result = service.apply(Money.of("100.00"), Discount.percent(10));
        assertThat(result).isEqualTo(Money.of("90.00"));   // AssertJ: clear diagnostics on mismatch
    }

    @ParameterizedTest                                      // one test body, many inputs
    @CsvSource({
        "100.00, 200, 0.00",   // discount larger than price clamps to zero
        "50.00,  10,  45.00",
        "0.00,   10,  0.00"
    })
    void neverGoesNegative(String price, int pct, String expected) {
        Money result = service.apply(Money.of(price), Discount.percent(pct));
        assertThat(result).isEqualTo(Money.of(expected));
    }
}
```

Why this is a *unit* test: no database, no clock, no network. It runs in microseconds and a failure names exactly `PricingService`.

### 5.2 Unit test with Mockito (collaborator stubbed/verified)

```java
@ExtendWith(MockitoExtension.class)             // wires @Mock/@InjectMocks, verifies strict stubs
class OrderServiceTest {

    @Mock InventoryClient inventory;            // collaborator replaced by a mock
    @Mock OrderRepository repository;
    @InjectMocks OrderService service;          // SUT with mocks injected

    @Test
    void rejectsOrderWhenOutOfStock() {
        when(inventory.available("sku-1")).thenReturn(0);   // stub the answer

        assertThatThrownBy(() -> service.place(new Order("sku-1", 1)))
            .isInstanceOf(OutOfStockException.class);

        verify(repository, never()).save(any());            // assert the side effect did NOT happen
    }

    @Test
    void persistsOrderWhenInStock() {
        when(inventory.available("sku-1")).thenReturn(5);
        service.place(new Order("sku-1", 2));
        verify(repository).save(argThat(o -> o.quantity() == 2)); // assert the right thing was saved
    }
}
```

### 5.3 Integration test with Testcontainers + Spring Boot (real Postgres)

```java
@SpringBootTest
@Testcontainers
class OrderRepositoryIT {                       // 'IT' suffix → Failsafe runs it in the integration phase

    @Container                                  // 'static' → ONE container for the whole class (fast)
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource                      // inject the random mapped URL/port into Spring
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired OrderRepository repository;

    @Test
    void savesAndQueriesByStatus() {            // verifies REAL SQL against REAL Postgres
        repository.save(new OrderEntity("sku-1", Status.PAID));
        repository.save(new OrderEntity("sku-2", Status.PENDING));

        List<OrderEntity> paid = repository.findByStatus(Status.PAID);

        assertThat(paid).extracting(OrderEntity::sku).containsExactly("sku-1");
    }
}
```

Spring Boot 3.1+ shortcut: replace `@DynamicPropertySource` with `@ServiceConnection` on the container field, and Boot auto-wires the connection details:

```java
@Container @ServiceConnection
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

### 5.4 Integration test with Testcontainers Kafka (produce/consume round-trip)

```java
@Testcontainers
class OrderEventIT {

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Test
    void publishesOrderPlacedEvent() {
        var producerProps = Map.<String,Object>of(
            "bootstrap.servers", kafka.getBootstrapServers(),
            "key.serializer", StringSerializer.class.getName(),
            "value.serializer", StringSerializer.class.getName());

        try (var producer = new KafkaProducer<String,String>(producerProps)) {
            producer.send(new ProducerRecord<>("orders", "k1", "{\"id\":1}")).get();
        }

        var consumerProps = Map.<String,Object>of(
            "bootstrap.servers", kafka.getBootstrapServers(),
            "group.id", "test-" + UUID.randomUUID(),         // unique group → always read from start
            "auto.offset.reset", "earliest",
            "key.deserializer", StringDeserializer.class.getName(),
            "value.deserializer", StringDeserializer.class.getName());

        try (var consumer = new KafkaConsumer<String,String>(consumerProps)) {
            consumer.subscribe(List.of("orders"));
            // Awaitility instead of sleep — polls until the record arrives or times out
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isGreaterThan(0);
            });
        }
    }
}
```

Note the two anti-flakiness moves: a **unique consumer group** (so the test never reads someone else's committed offset) and **Awaitility** (never `Thread.sleep`).

### 5.5 HTTP collaborator stubbed with WireMock (incl. fault injection)

```java
class PaymentGatewayClientTest {

    static WireMockServer wm = new WireMockServer(options().dynamicPort());

    @BeforeAll static void start() { wm.start(); }
    @AfterAll  static void stop()  { wm.stop(); }
    @BeforeEach void reset()       { wm.resetAll(); }

    @Test
    void retriesOnGatewayTimeout() {
        wm.stubFor(post("/charge")
            .inScenario("retry").whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withFixedDelay(5000))     // simulate a timeout on attempt 1
            .willSetStateTo("recovered"));
        wm.stubFor(post("/charge")
            .inScenario("retry").whenScenarioStateIs("recovered")
            .willReturn(okJson("{\"status\":\"OK\"}")));       // succeed on attempt 2

        var client = new PaymentGatewayClient(wm.baseUrl(), Duration.ofSeconds(1));
        assertThat(client.charge(100)).isEqualTo(Status.OK);   // proves the retry logic works
    }
}
```

### 5.6 Consumer-driven contract test with Pact (consumer side)

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "user-service")
class UserClientPactTest {

    @Pact(consumer = "order-service")              // declare the contract
    V4Pact getUser(PactDslWithProvider builder) {
        return builder
            .given("user 42 exists")               // provider state the provider must set up
            .uponReceiving("a request for user 42")
            .path("/users/42").method("GET")
            .willRespondWith()
            .status(200)
            .body(new PactDslJsonBody()
                .integerType("id", 42)             // matching rule: any integer (not exact 42)
                .stringType("name"))               // any string
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "getUser")
    void clientParsesUser(MockServer mockServer) { // Pact runs the mock provider here
        var client = new UserClient(mockServer.getUrl());
        User u = client.fetch(42);                 // REAL client code against the mock
        assertThat(u.id()).isEqualTo(42);
    }
}
// On pass, a pact JSON file is generated and (in CI) published to the Pact Broker.
```

Provider side (in `user-service`'s suite), the verification:

```java
@Provider("user-service")
@PactBroker(url = "https://broker.example.com")
class UserServiceProviderTest {

    @BeforeEach void target(PactVerificationContext ctx) {
        ctx.setTarget(new HttpTestTarget("localhost", port)); // the real running provider
    }

    @State("user 42 exists")                       // set up the declared state
    void user42Exists() { repository.save(new User(42, "Ada")); }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPacts(PactVerificationContext ctx) { ctx.verifyInteraction(); }
}
```

### 5.7 JaCoCo coverage gate in Maven

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.12</version>
  <executions>
    <execution><goals><goal>prepare-agent</goal></goals></execution> <!-- instruments tests -->
    <execution>
      <id>check</id>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum>           <!-- build fails below 80% line coverage -->
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### 5.8 PIT mutation testing (Maven, incremental)

```xml
<plugin>
  <groupId>org.pitest</groupId>
  <artifactId>pitest-maven</artifactId>
  <version>1.16.0</version>
  <dependencies>
    <dependency>
      <groupId>org.pitest</groupId>
      <artifactId>pitest-junit5-plugin</artifactId>
      <version>1.2.1</version>
    </dependency>
  </dependencies>
  <configuration>
    <targetClasses><param>com.example.order.*</param></targetClasses>
    <mutationThreshold>75</mutationThreshold>     <!-- fail if mutation score < 75% -->
    <withHistory>true</withHistory>               <!-- incremental: only re-test changed mutants -->
  </configuration>
</plugin>
```

Run: `mvn test-compile org.pitest:pitest-maven:mutationCoverage`.

### 5.9 A sharded, gated CI pipeline (GitHub Actions)

```yaml
name: ci
on: [pull_request, push]

jobs:
  unit:                                            # fast feedback first
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: mvn -B test                           # Surefire = unit tests only

  integration:                                     # parallel shards of integration tests
    runs-on: ubuntu-latest
    needs: unit                                    # gate: don't run heavy tests if units fail
    strategy:
      fail-fast: false
      matrix: { shard: [0, 1, 2, 3] }              # 4 parallel shards
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      # Docker is available on ubuntu-latest runners → Testcontainers works out of the box
      - run: mvn -B failsafe:integration-test failsafe:verify
             -Dshard.index=${{ matrix.shard }} -Dshard.total=4   # custom test-splitting hook
      - uses: actions/upload-artifact@v4
        with: { name: cov-${{ matrix.shard }}, path: target/site/jacoco/jacoco.xml }

  contract:
    runs-on: ubuntu-latest
    needs: unit
    steps:
      - uses: actions/checkout@v4
      - run: mvn -B test -Dtest='*PactTest'        # consumer contract tests
      - run: pact-broker publish target/pacts --consumer-app-version ${{ github.sha }} --branch ${{ github.ref_name }}

  can-i-deploy:                                    # release gate
    runs-on: ubuntu-latest
    needs: [integration, contract]
    if: github.ref == 'refs/heads/main'
    steps:
      - run: pact-broker can-i-deploy --pacticipant order-service
             --version ${{ github.sha }} --to-environment production
```

The `unit`/`integration`/`contract` jobs are configured as **required status checks** in branch protection, so the PR cannot merge until they're green; `can-i-deploy` gates the actual deploy.

---

## 6. Implementation concerns & best practices

### 6.1 Performance — keeping the suite fast

Slow suites are not a cosmetic problem; they change behavior. Once a suite crosses ~10 minutes, developers stop waiting for it, context-switch, and merge before it's green. Levers:

- **Honor the pyramid.** The cheapest speedup is having most tests be unit tests.
- **Reuse expensive setup.** Static/singleton Testcontainers; one Spring context (avoid `@DirtiesContext`, which forces a context reload between tests — very expensive).
- **Use Spring test slices.** `@WebMvcTest`, `@DataJpaTest`, `@JsonTest` load a minimal context instead of the whole app (`@SpringBootTest`).
- **Parallelize** (forks, threads, shards — §3.8) and **balance shards by timing**, not count.
- **Cache dependencies** in CI (`~/.m2`, Gradle cache) and use the **build/configuration cache** to skip unchanged work.
- **Predictive test selection** for very large suites — run only affected tests on PRs, full suite on main.
- **Split pipeline stages** so fast unit tests gate before slow ones even start (fail fast on cheap signals).

### 6.2 Correctness & concurrency in the tests themselves

- **Isolation between tests.** Never let one test depend on another's state or order. JUnit randomizes/orders deterministically; rely on no order. Reset shared state in `@AfterEach`.
- **Thread-safety under parallelism.** Static mutable fields, shared singletons, shared DB tables → interference. Use `@ResourceLock`, per-test schemas/data, or unique keys.
- **No `Thread.sleep`.** Replace with **Awaitility** polling or deterministic synchronization. Sleeps are the #1 cause of flakiness and slowness simultaneously.
- **Control non-determinism sources:** inject the clock (`java.time.Clock`) instead of `Instant.now()`; inject randomness (`Random` with a seed); pin time zones and locales (`-Duser.timezone=UTC`).

### 6.3 Memory & resource management

- Each forked JVM has overhead; `forkCount=1C` on a 32-core runner spawns 32 JVMs — watch heap (`-Xmx` per fork × forks must fit RAM, or the runner OOM-kills).
- Containers consume disk and RAM; Ryuk reaps leaks, but a misbehaving suite that starts a container per method can exhaust the runner. Audit with `docker ps` in a stuck pipeline.
- Close resources (`try-with-resources`); leaked connections/threads accumulate across tests and cause late, confusing failures.

### 6.4 Test data management (deep)

The central question: how does each test get clean, sufficient data without contaminating others? Strategies, with tradeoffs:

| Strategy | How | Pros | Cons |
|---|---|---|---|
| **Transactional rollback** | Wrap each test in a transaction, roll back after (`@Transactional` in Spring tests). | Fast, perfect isolation, no cleanup code. | Doesn't test commit/flush behavior; hides issues that only appear on commit; breaks for code that manages its own transactions or for multi-connection scenarios. |
| **Truncate & reseed** | After each test, `TRUNCATE` all tables and re-insert baseline. | Tests real commits; simple. | Slower; must enumerate tables; FK order matters. |
| **Unique-per-test data** | Each test creates data with unique keys (UUIDs, prefixes). | Enables parallelism without contention. | Data accumulates; needs eventual cleanup; queries must scope to the unique keys. |
| **Schema/DB template** | Start from a pre-migrated template DB and clone per test class. | Fast, realistic schema. | Setup complexity. |
| **Fixtures / Object Mother / Test Data Builders** | Helper code to construct valid domain objects with sensible defaults, overriding only what the test cares about. | Readable tests; central place to fix when the model changes. | Maintenance; risk of over-coupling. |

Best practices: prefer **builders** for in-memory objects (`anOrder().withStatus(PAID).build()`); for DB state, prefer **truncate-and-reseed or template clones** over transactional rollback when you need to test real commit semantics; keep fixtures **minimal and explicit** (a test should declare the data that matters to it, not rely on a giant shared seed file no one understands). Avoid **shared mutable test databases across CI runs** — the source of legendary flakiness.

### 6.5 Security in CI testing

- **Secrets.** Tests sometimes need credentials (a sandbox API key). Never hard-code; inject via CI secret store; mask in logs. Prefer ephemeral local resources (Testcontainers) so no real secret is needed.
- **Supply chain.** Test dependencies are still dependencies; a malicious test-scope library runs arbitrary code in CI with access to the runner's environment and secrets. Pin versions, use a lockfile, scan (e.g. OWASP Dependency-Check, `dependabot`).
- **Untrusted PRs.** On public repos, a PR from a fork can modify the test/pipeline to exfiltrate secrets. Use the CI provider's "require approval to run workflows for first-time/forked contributors" and avoid exposing secrets to `pull_request` events from forks (GitHub gates this by default).
- **Docker socket exposure.** Testcontainers needs the Docker socket; mounting it grants broad host access. On shared runners this is a privilege concern — prefer isolated/ephemeral runners or Testcontainers Cloud.

### 6.6 Coverage as a signal, not a target — and Goodhart's law

> **Goodhart's law:** "When a measure becomes a target, it ceases to be a good measure." Applied here: mandate 90% coverage and developers will write tests that *touch* lines without *asserting* anything, or test trivial getters, to hit the number — coverage rises, real quality doesn't.

Best practices:

- Use coverage to **find untested code**, not to grade quality. A sudden coverage drop on a PR is a useful flag.
- Gate on **new-code / diff coverage**, not total. (SonarQube's "coverage on new code" and Codecov's patch coverage do this — far more meaningful than a global percentage that legacy code drags down or up.)
- Set thresholds **modestly** (e.g. 70–80% on new code) and treat them as a floor, not a goal.
- For real test-quality measurement, use **mutation testing** (§3.6) on critical modules — it measures whether tests catch bugs, which is what you actually care about.

### 6.7 Observability of the test suite itself

You must measure CI to manage it:

- **Test duration trends** — find slow tests and slow shards. Most CI tools surface per-test timing (JUnit XML has it).
- **Flaky-test tracking** — record which tests fail-then-pass on retry; feed a flaky-test dashboard (CI providers like CircleCI/GitLab have built-in flaky detection; or build it from JUnit XML history).
- **Pass/fail/duration as time series** — alert on suite-time creep and failure-rate spikes.
- **Coverage trends** over time.
- **Mutation score** on critical packages, nightly.

### 6.8 Production hardening & gating

- **Deploy gating.** Required status checks block merge; `can-i-deploy` blocks release. Keep the *required* set fast and reliable — gating on a flaky 40-minute E2E suite trains people to bypass it.
- **Tiered gates.** Block merge on unit + fast integration + contract; run slow E2E and mutation testing post-merge / nightly, alerting rather than blocking, or as a deploy gate to staging.
- **Quarantine, don't disable silently.** When a test is flaky, move it to a quarantine group that still runs and is tracked, but doesn't block (§9.2) — never just `@Disabled` and forget.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| **Ice-cream cone** | Slow, flaky, poor localization. | Push tests down the pyramid. |
| **`Thread.sleep` for async** | Flaky + slow. | Awaitility / deterministic sync. |
| **In-memory DB (H2) as Postgres stand-in** | Dialect differences → tests green, prod red. | Testcontainers with the real engine. |
| **Shared mutable test DB** | Cross-run contamination, flakiness. | Ephemeral per-run DB; isolate data. |
| **Auto-retry to mask flakiness** (`rerunFailingTestsCount`) | Hides real bugs; flakiness compounds. | Quarantine + fix root cause. |
| **Coverage as a target** | Goodhart; vacuous tests. | Diff coverage + mutation testing. |
| **Asserting on mock internals** (over-mocking) | Tests couple to implementation, break on refactor. | Test behavior/outputs; mock only true boundaries. |
| **One giant `@SpringBootTest` for everything** | Slow context, hidden coupling. | Use slices; reserve full context for true integration. |
| **Time/locale/timezone-dependent tests** | Pass in one zone, fail in CI's UTC. | Inject `Clock`; pin TZ/locale. |
| **Order-dependent tests** | Break under parallelism/reorder. | Full isolation; reset state. |
| **`@Disabled("flaky")` graveyard** | Coverage silently rots. | Track, quarantine, fix or delete. |

---

## 7. Advanced topics & deep internals

### 7.1 How JaCoCo instruments code

JaCoCo works as a **Java agent** (`-javaagent:jacocoagent.jar`) that uses **on-the-fly bytecode instrumentation**: as classes are loaded, it inserts probes (boolean flags) at the entry of each basic block. When a probe executes, its flag flips. At JVM exit it dumps an `*.exec` file of which probes fired; the report goal maps probes back to source lines/branches. Because it works on bytecode, it counts *instruction* and *branch* coverage natively; "line coverage" is derived. Subtlety: lambdas, switch expressions, and synthetic bytecode can produce surprising partial-branch reports; and instrumentation slightly changes timing (can mask/expose race conditions), so a suite that's green under coverage but red without (or vice versa) is a flakiness smell.

### 7.2 JUnit 5 parallel execution model internals

JUnit Jupiter executes tests through a tree of `TestDescriptor`s. With parallelism enabled it uses a `ForkJoinPool`; the **parallelism level** defaults to the number of available processors (configurable via `junit.jupiter.execution.parallel.config.strategy` = `dynamic`|`fixed`|`custom`). Locking is cooperative: `@ResourceLock("X", READ|READ_WRITE)` declares intent; the engine serializes `READ_WRITE` access to a named resource while allowing concurrent `READ`. There's a built-in resource name `Resources.SYSTEM_PROPERTIES` because mutating system properties is a classic interference source. Gotcha: `@BeforeAll`/`@AfterAll` and static fields are shared across concurrently running methods of the same class — shared mutable statics will race.

### 7.3 Surefire vs Failsafe lifecycle — why the split exists

Maven binds **Surefire** to the `test` phase and **Failsafe** to `integration-test` + `verify`. The critical difference: Surefire *fails the build immediately* on test failure (correct for unit tests). Failsafe *does not fail in `integration-test`* — it records the result and lets the `post-integration-test` phase run (so you can tear down a server/container you started in `pre-integration-test`), then `verify` checks the recorded result and fails the build. This guarantees cleanup happens even when integration tests fail. Naming convention encodes the split: `*Test` → Surefire (unit), `*IT`/`*ITCase` → Failsafe (integration).

### 7.4 Testcontainers internals — Ryuk, wait strategies, networks

- **Ryuk** connects back to the JVM over a TCP socket and registers a "death pact": it holds a connection; if the JVM disconnects (test process dies), Ryuk reaps all containers/networks/volumes labeled with that session's label after a grace period. This is why orphaned containers don't accumulate even after `kill -9`. Disable only when you manage cleanup yourself (`TESTCONTAINERS_RYUK_DISABLED=true`), e.g. some rootless/CI environments.
- **Wait strategies** are the readiness gate: `Wait.forLogMessage(regex, times)`, `Wait.forListeningPort()`, `Wait.forHttp("/health").forStatusCode(200)`, `Wait.forHealthcheck()` (uses the image's Docker HEALTHCHECK). Picking the wrong one is a top cause of "container started but service not ready" flakiness.
- **Networks.** `Network.newNetwork()` lets multiple containers talk to each other by alias (e.g. app container → db container) using `withNetworkAliases`. `withReuse(true)` plus the global flag keeps containers alive across runs locally.
- **`getMappedPort` vs internal port.** Inside a container-to-container network, use the *internal* port (5432); from the host/test JVM, use `getMappedPort(5432)` / `getJdbcUrl()` (a random host port). Mixing these up is a classic mistake.

### 7.5 Pact matching semantics & the broker matrix

Pact distinguishes **exact matching** (the value must equal) from **type matching** (`integerType`, `stringType`, `arrayContaining`, regex). Consumer-driven contracts should match on *type/shape*, not exact values, so the provider has freedom to change data without breaking the contract. The broker's **matrix** is the cross-product of (consumer version, provider version) verification results across tagged environments; `can-i-deploy` runs a reachability query over it: "for the version I want to deploy, is there a compatible, verified counterpart already in the target environment?" **Pending pacts** and **WIP pacts** let a new consumer expectation exist without immediately breaking the provider's build until it's been verified once — important for parallel team workflows. **Bi-directional contract testing** (PactFlow) is a newer variant that compares a provider's OpenAPI spec against consumer contracts without running provider verification, trading some fidelity for less coupling.

### 7.6 Mutation testing — operators, equivalent mutants, and cost control

PIT's default mutators flip conditionals (`<` ↔ `<=`), negate conditionals, mutate increments, replace return values, and remove void method calls. The hard problem is **equivalent mutants**: a mutation that changes the bytecode but not the observable behavior (e.g. `i <= n` vs `i < n` where the loop bound makes them identical) — these can never be killed and artificially depress the score; there's no general algorithm to detect them (it's undecidable), so you manually exclude or accept a sub-100% target. Cost control: **incremental analysis** (`withHistory`) reuses prior results for unchanged code; **coverage-targeted execution** runs only tests that cover the mutated line; running PIT only on changed files in a PR (via `scmMutationCoverage`/diff-based config) makes it affordable per-commit.

### 7.7 Test selection / predictive selection internals

To run only affected tests, the system needs a **map from production code to the tests that exercise it**, typically built from coverage data collected on past runs. On a new change, it diffs the changed files against that map and selects the covering tests, plus a safety margin (e.g. always run tests in the same module, or recently failed/new tests). Bazel takes a different, sound approach: it knows the exact build-dependency graph, so it can prove which test targets could possibly be affected by a changed file and skip the rest deterministically. The risk with coverage-map approaches is **unsoundness** (missing a newly-relevant test); they mitigate with full-suite runs on the main branch and nightly.

### 7.8 Hermeticity and reproducibility

A **hermetic** test depends only on declared inputs — no network to the open internet, no ambient machine state, no wall clock, fixed seeds, fixed time zone. Hermeticity is the deep root of non-flakiness. Tools push toward it: Testcontainers (no shared external DB), pinned image tags (`postgres:16.2`, never `latest`), `-Duser.timezone=UTC -Duser.language=en`, seeded randomness, injected `Clock`, and forbidding outbound network in tests (some teams block egress on the test runner to *catch* accidental real network calls).

### 7.9 Contract vs schema vs E2E — the spectrum of cross-service confidence

There's a continuum: **schema validation** (cheapest, weakest — checks structure only), **consumer-driven contracts** (Pact — checks the specific interactions a consumer relies on, both directions verified), **provider-driven contracts** (Spring Cloud Contract — provider publishes, consumers get stubs), **bi-directional contracts** (compare specs, no live verification), and **full E2E** (most expensive, strongest, slowest). Mature systems layer these: contracts for the routine compatibility guarantee, a thin E2E for the genuine assembled-system check.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing the test level for a given behavior

```
Is the behavior pure logic (no I/O, no real dependency)?
  → YES: unit test.
Does correctness depend on a real dependency's behavior (SQL dialect, serialization, broker semantics)?
  → YES: integration test (Testcontainers with the real engine).
Is it about two independently deployed services agreeing on an API?
  → YES: contract test (Pact / Spring Cloud Contract).
Is it a genuine, critical, whole-system user journey?
  → YES (sparingly): E2E test.
Otherwise: push it down to the lowest level that can verify it.
```

### 8.2 Real DB strategies compared

| Option | Fidelity | Speed | Isolation | Use when | Avoid when |
|---|---|---|---|---|---|
| **Testcontainers (real engine)** | Highest | Medium (container start) | Per-run/class | You need real SQL/dialect/transaction behavior. | No Docker available and no Testcontainers Cloud. |
| **H2/HSQLDB in-memory** | Low (dialect drift) | Fastest | Per-test trivial | Only trivial CRUD, or as a quick smoke. | Anything using vendor-specific SQL, JSONB, sequences, locking. |
| **Shared remote test DB** | High | Fast (no start) | Poor (contention) | Legacy constraint only. | Parallel CI — guaranteed flakiness. |
| **Embedded Postgres (e.g. zonky)** | High | Fast start | Per-run | Docker unavailable but real Postgres needed. | Need exact prod version parity beyond embedded support. |

### 8.3 Contract testing vs E2E for microservice compatibility

| | Contract testing (Pact) | Full E2E |
|---|---|---|
| Services run together? | No | Yes |
| Speed | Fast | Slow |
| Flakiness | Low | High |
| Failure localization | Precise (names the interaction) | Poor |
| Catches integration env/infra bugs | No | Yes |
| Catches API-shape disagreements | Yes (precisely) | Yes (eventually) |
| Independent deploy gating | Yes (`can-i-deploy`) | Hard |
| **Use when** | Many independently deployed services. | A small, critical set of genuine end-to-end journeys. |
| **Avoid when** | The "contract" is really internal logic. | As your primary regression net (ice-cream cone). |

### 8.4 Parallelization strategies compared

| Strategy | Isolation | Memory cost | Setup | Best for |
|---|---|---|---|---|
| In-JVM threads (JUnit parallel) | Weakest (shared statics/DB) | Low | Annotations + `@ResourceLock` | CPU-bound, stateless units. |
| Forked JVMs (Surefire/Gradle forks) | Strong (process) | High | Build config | Tests with shared in-JVM state. |
| Sharding across runners | Strongest (separate machines) | N/A (separate) | CI matrix + splitter | Large suites, wall-clock reduction. |
| Predictive test selection | N/A | N/A | Coverage map / build graph | Huge monorepos. |

### 8.5 When to gate (block) vs alert (don't block)

- **Block merge on:** compile, unit, fast integration, contract consumer tests, lint/format, security-critical checks. These must be fast and reliable.
- **Block deploy on:** `can-i-deploy`, smoke tests against staging, key E2E journeys.
- **Alert (don't block) on:** mutation score, full E2E nightly, coverage trend dips, slow-test regressions, flaky-test counts.
- **Never block on a flaky check** — it teaches the team to override gates, which is worse than no gate.

---

## 9. Failure modes & debugging

### 9.1 The catalog of failure modes

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Test passes locally, fails in CI | Env difference: timezone, locale, DB version, ordering, parallelism, missing Docker. | Reproduce with CI's exact JDK/TZ/flags; run in CI's container locally. | Pin TZ/locale/versions; hermeticity. |
| Intermittent failures (flaky) | Async timing (`sleep`), shared state, test order, real network, randomness, clock. | Re-run; bisect with `@RepeatedTest`; collect history of fail-then-pass. | §9.2 loop. |
| Container "not ready" errors | Wrong/absent wait strategy; service slow to boot. | Inspect container logs (`container.getLogs()`); check the wait strategy. | Correct `Wait.for...`. |
| Orphaned containers fill disk | Ryuk disabled or JVM killed without reap. | `docker ps -a`, runner disk usage. | Re-enable Ryuk; static container reuse. |
| Suite suddenly 3× slower | New `@DirtiesContext`, per-method container, lost dep cache, unbalanced shards. | Per-test timing report; build scan; cache hit logs. | Reuse context/container; rebalance shards; fix cache. |
| Coverage gate fails spuriously | Generated/DTO code counted; instrumentation gaps. | JaCoCo HTML report; exclude generated classes. | Exclusions; gate on diff coverage. |
| Contract verification fails | Provider changed shape; provider state setup broken; matching too strict. | Pact verification output (shows the exact field diff); broker matrix. | Fix provider or relax exact→type matching; fix state hook. |
| `can-i-deploy` blocks unexpectedly | No verified compatible counterpart in target env; missing tags. | Broker matrix UI; verification results. | Verify the missing pair; correct version/branch tags. |
| OOM-killed CI job | Too many forks × heap; container memory. | Runner memory metrics; `forkCount`. | Lower `forkCount`/`-Xmx`; bigger runner. |

### 9.2 The flaky-test discipline (detect → quarantine → fix)

Flaky tests are the chronic disease of CI. Their damage is *trust erosion*: once red builds are sometimes spurious, people start ignoring red builds, and then a *real* regression sails through. The disciplined loop:

1. **Detect.** Track every test that fails then passes on retry (or fails non-deterministically across runs). Build a flaky-test rate per test from JUnit XML history, or use built-in detection (GitLab "flaky test" reports, CircleCI test insights, Datadog/Buildkite test analytics).
2. **Quarantine fast.** The moment a test is flaky beyond a threshold, move it to a **quarantine tag/group** that *still runs* (so you keep data and it doesn't rot) but **does not block** merges. This protects the team's trust in the green build immediately, while preserving the test for fixing. Critically: quarantine is *tracked and time-boxed*, not a graveyard.
3. **Root-cause.** Reproduce by running the test in a loop (`@RepeatedTest(100)`), under parallelism, with randomized order. Common roots: `Thread.sleep`/timing → Awaitility; shared state → isolation; real network/clock/randomness → inject/seed; order dependence → reset state; resource leaks → close.
4. **Fix or delete.** Fix the determinism bug and de-quarantine, or delete the test if it provides no real value. Never leave it disabled-and-forgotten.
5. **Prevent.** Enforce no-`sleep` (a custom ArchUnit/forbidden-API rule can ban `Thread.sleep` in tests), hermeticity defaults, and review for the known anti-patterns.

### 9.3 Real-world incident patterns (illustrative)

- **The "H2 lied to us" incident.** Tests used H2 as a Postgres stand-in; a query using Postgres `JSONB` / `ON CONFLICT` worked in H2's emulation differently, tests were green, production threw on deploy. Lesson: test against the real engine (Testcontainers). This pattern recurs across many organizations.
- **The auto-retry mask.** A team enabled `rerunFailingTestsCount=3` to "stabilize" CI. Real regressions that failed once and passed by luck on retry slipped through; an actual bug shipped. Lesson: retries mask, not fix, flakiness.
- **The timezone failure at midnight UTC.** A date test passed all day in the team's zone and failed in CI (UTC) only for builds running around a date boundary. Lesson: pin `-Duser.timezone=UTC` and inject `Clock`.
- **The shared-test-DB death spiral.** Parallel CI runs against one shared test database contaminated each other's data; failure rate scaled with team size until nobody trusted the suite. Lesson: ephemeral, isolated data per run.
- **Coverage theater.** A 90% mandate produced thousands of assertion-free getter tests; a real null-handling bug shipped because the "covered" code was never actually asserted. Lesson: mutation testing / diff coverage over global percentage targets.

### 9.4 Diagnostic commands & techniques

```bash
# Run a single flaky test in a loop to reproduce
mvn test -Dtest='OrderServiceTest#rejectsOrderWhenOutOfStock' -Dsurefire.rerunFailingTestsCount=0

# Force CI-like environment locally
mvn test -Duser.timezone=UTC -Duser.language=en -Duser.country=US

# See what containers a stuck pipeline leaked
docker ps -a --filter "label=org.testcontainers=true"

# Pact: explain why can-i-deploy blocked
pact-broker can-i-deploy --pacticipant order-service --version <sha> --to-environment production --verbose

# JaCoCo report locally to inspect a coverage drop
mvn test jacoco:report   # open target/site/jacoco/index.html

# PIT to find weak assertions in a package
mvn org.pitest:pitest-maven:mutationCoverage -DtargetClasses=com.example.order.*
```

In JUnit, `@RepeatedTest(100)` plus enabling parallel execution is the fastest local reproducer for order/timing flakiness; `Testcontainers` exposes container logs via `container.getLogs()` and you can stream them with a `Slf4jLogConsumer` to see why a service wasn't ready.

---

## 10. Interview drill

**Q1. Explain the test pyramid and why the ice-cream cone is an anti-pattern.**
*Model answer:* The pyramid prescribes many fast, isolated unit tests, fewer integration tests, and very few slow E2E tests — because cost, speed, and failure-localization worsen as you go up. The ice-cream cone inverts this (mostly E2E/manual, few units), giving slow feedback, pervasive flakiness, poor localization, and high maintenance.
- *Follow-up: Where does the "testing trophy" differ?* It fattens the integration band, arguing integration tests give the best confidence-per-effort, especially for I/O-heavy services.
- *Follow-up: When is more E2E actually justified?* For a few genuinely critical, irreducibly end-to-end journeys (e.g. checkout), and where integration coverage can't prove assembly correctness.

**Q2. Why not use H2 instead of Testcontainers for DB integration tests?**
*Model answer:* H2 emulates a SQL dialect but isn't Postgres/MySQL; vendor features (JSONB, `ON CONFLICT`, sequences, specific locking/isolation) behave differently, so tests pass while production breaks. Testcontainers runs the *real* engine in Docker.
- *Follow-up: Cost of Testcontainers?* Container start latency (~seconds) and Docker dependency; mitigate with static/singleton containers and reuse.
- *Follow-up: No Docker on the runner — options?* Testcontainers Cloud, a remote Docker host, embedded-Postgres (zonky), or rootless Podman.

**Q3. How does consumer-driven contract testing let two services be tested without running together?**
*Model answer:* The consumer writes a test against a Pact mock provider, generating a pact file describing the interactions it needs (with type-based matching). The provider's CI replays those interactions against the real provider with declared provider states. The broker holds the contracts and, via `can-i-deploy`, gates deploys on a verified-compatible counterpart existing in the target environment.
- *Follow-up: Exact vs type matching — why prefer type?* So the provider can change values without breaking the contract; the consumer only constrains shape it actually depends on.
- *Follow-up: Pending/WIP pacts?* They let a new consumer expectation exist without breaking the provider build until verified once — enabling parallel team workflows.

**Q4. What is a flaky test, what causes flakiness, and how do you handle it operationally?**
*Model answer:* A test that passes/fails non-deterministically without code change. Causes: timing (`sleep`), shared/ordered state, real network, unseeded randomness, clock/timezone. Operationally: detect (fail-then-pass tracking), quarantine fast (still runs, doesn't block), root-cause (`@RepeatedTest`, parallel, randomized order), fix or delete, prevent (ban `sleep`, hermeticity).
- *Follow-up: Why not just auto-retry?* Retries mask real regressions that fail-once-pass-later and let bugs ship.
- *Follow-up: Why quarantine instead of `@Disabled`?* Disabled tests rot silently; quarantine keeps them running and tracked for fixing.

**Q5. Coverage is at 92% — are we well tested?**
*Model answer (senior-signal):* Not necessarily. Coverage measures execution, not verification; tests can run lines without asserting. Per Goodhart, a coverage target invites vacuous tests. Better signals: diff/new-code coverage to catch untested *changes*, and mutation testing to verify tests actually catch injected bugs. Use coverage to find totally-untested code, not as a quality grade.
- *Follow-up: How does mutation testing complement coverage?* It injects bugs (flips conditionals, alters returns); "survived" mutants reveal code that's executed but not meaningfully asserted.
- *Follow-up: Cost of mutation testing and how to afford it?* Expensive (many suite runs); use incremental/history, coverage-targeted execution, and diff-only on PRs.

**Q6. Walk me through parallelizing a slow CI suite. (senior-signal)**
*Model answer:* First honor the pyramid and reuse expensive setup (static containers, avoid `@DirtiesContext`, use Spring slices). Then parallelize: in-JVM threads for stateless units (with `@ResourceLock` for shared resources), forked JVMs for in-JVM-stateful tests, and shard across runners balanced *by historical timing* not class count. For huge monorepos, add predictive test selection (coverage map or Bazel's dependency graph), full suite on main as the safety net.
- *Follow-up: Risks of in-JVM parallelism?* Shared static state and shared DB cause interference/flakiness.
- *Follow-up: How to balance shards?* Use per-test timing history to equalize wall-clock per shard.

**Q7. Surefire vs Failsafe — why two plugins?**
*Model answer:* Surefire runs unit tests in the `test` phase and fails the build immediately. Failsafe runs integration tests in `integration-test`/`verify` and does *not* fail in `integration-test`, so `post-integration-test` cleanup (tear down servers/containers) always runs; `verify` then asserts the recorded result. Naming: `*Test` → Surefire, `*IT` → Failsafe.
- *Follow-up: Why does cleanup matter here?* Integration tests start real resources; failing fast would leak them.

**Q8. Design the test strategy and gates for a payments microservice. (senior-signal)**
*Model answer:* Unit tests for all pure logic (amounts, rounding, state machine). Integration tests via Testcontainers for the real DB and broker (idempotency, transaction/rollback, serialization). Contract tests (Pact) against upstream/downstream services with `can-i-deploy` gating. A thin E2E for the critical "charge succeeds and is recorded" journey against staging. Required-to-merge gates: compile + unit + fast integration + contract — all fast and reliable. Deploy gates: `can-i-deploy` + staging smoke + key E2E. Non-blocking/nightly: mutation testing on the money-handling package, full E2E, coverage trend. Strict hermeticity and zero-`sleep` policy because payments correctness is critical.
- *Follow-up: Where do you spend mutation-testing budget?* On the highest-risk modules (money math, idempotency), nightly, incrementally.
- *Follow-up: Why not gate on the full E2E?* It's slow/flaky; gating trains people to bypass — alert instead, keep gates fast and trustworthy.

**Q9. How do you keep integration tests isolated when running in parallel against a database?**
*Model answer:* Options: per-test transactional rollback (fast but doesn't test commit), truncate-and-reseed (tests commits), or unique-per-test data with UUID/prefix keys (enables true parallelism without contention), or schema/template-per-test. For Testcontainers, a static container with between-test resets balances speed and isolation.
- *Follow-up: Downside of transactional rollback?* It hides bugs that only appear on commit/flush and breaks for code managing its own transactions.

**Q10. What makes a test "hermetic," and why does it matter?**
*Model answer:* A hermetic test depends only on declared inputs — no open-internet network, no ambient state, fixed clock/seed/timezone/locale. It matters because non-hermeticity is the root cause of flakiness and "works on my machine"; hermeticity makes tests reproducible and trustworthy.
- *Follow-up: Concrete enforcement?* Pin image tags, inject `Clock`, seed `Random`, set UTC/locale, block test egress, use Testcontainers instead of shared external services.

**Q11. Your CI is green but production keeps breaking on cross-service calls. What's missing and how do you fix it? (senior-signal)**
*Model answer:* You likely lack contract tests — each service is tested in isolation with stubbed collaborators that drift from reality, so shape disagreements only surface in production. Introduce consumer-driven contracts (Pact) with a broker and `can-i-deploy` deploy gating; optionally a thin E2E for the critical journey. This catches API mismatches before deploy without the cost/flakiness of running the whole graph.
- *Follow-up: Why not just more E2E?* Slow, flaky, poor localization, hard to gate independent deploys.

---

## 11. Glossary

- **Assertion** — a check in a test that fails the test if false.
- **Awaitility** — Java library to wait for an async condition by polling, replacing `Thread.sleep`.
- **Bazel** — a build system that knows the exact dependency graph, enabling sound test selection.
- **Branch coverage** — fraction of decision branches (true/false sides) executed by tests.
- **`can-i-deploy`** — Pact Broker command that gates a deploy on a verified-compatible counterpart in the target environment.
- **CI (Continuous Integration)** — frequently merging and automatically verifying code on a build server.
- **CI runner/agent** — the machine/container that runs pipeline steps.
- **Code coverage** — percentage of code executed during tests.
- **Component test** — testing one service in isolation through its real external interface.
- **Consumer-driven contract** — contract defined by what the consumer needs, verified against the provider.
- **Contract test** — verifies two services agree on their shared API without running both together.
- **Deploy gate** — a check that must pass before release.
- **Docker** — container runtime that runs packaged images as isolated processes.
- **Determinism** — same inputs → same result; opposite of flaky.
- **E2E (end-to-end) test** — exercises the whole assembled system through real entry points.
- **Failsafe** — Maven plugin running integration tests (`*IT`) without failing the build before cleanup.
- **Fake** — a working but simplified test double (e.g. in-memory DB).
- **Flaky test** — passes/fails non-deterministically without code change.
- **ForkJoinPool** — Java thread pool JUnit uses for parallel execution.
- **Goodhart's law** — when a measure becomes a target it ceases to be a good measure.
- **H2/HSQLDB** — in-memory SQL databases often (mis)used as production-DB stand-ins.
- **Hermetic test** — depends only on declared inputs; no ambient/external state.
- **Ice-cream cone** — anti-pattern: inverted pyramid, mostly E2E/manual tests.
- **Integration test** — exercises several real components together, often with real I/O.
- **JaCoCo** — Java coverage tool using on-the-fly bytecode instrumentation via a Java agent.
- **Java agent** — code attached to the JVM (`-javaagent`) that can instrument classes as they load.
- **JUnit 5 / Jupiter** — the dominant JVM test framework.
- **Line coverage** — fraction of executable lines run by tests.
- **Matching rule (Pact)** — declares whether a field matches by exact value or by type/shape.
- **Matrix (Pact Broker)** — cross-product of consumer/provider verification results across environments.
- **Mock** — a test double pre-programmed with expected calls that it verifies.
- **Mockito** — dominant JVM mocking library.
- **Mutant** — a small deliberate change to production code in mutation testing.
- **Mutation score** — killed mutants / viable mutants; a test-quality measure.
- **Mutation testing** — measures test quality by injecting bugs and checking if tests catch them.
- **Pact** — leading consumer-driven contract-testing framework.
- **Pact Broker** — central store/exchange for pacts and verification results.
- **Pending/WIP pacts** — new consumer expectations that don't break the provider build until verified once.
- **Pipeline** — declarative description of CI steps (YAML/Jenkinsfile).
- **Predictive/test selection** — running only the tests affected by a change.
- **Provider state** — a precondition the provider sets up before a contract interaction is verified.
- **Quarantine** — a tracked group where flaky tests still run but don't block merges.
- **Required check** — a status check that must be green before merge (branch protection).
- **Ryuk** — Testcontainers' resource-reaper sidecar that cleans up leaked containers.
- **Sharding** — splitting a suite across multiple runners to reduce wall-clock time.
- **Slice test (Spring)** — loads a minimal context (`@WebMvcTest`, `@DataJpaTest`) for speed.
- **Smoke test** — a tiny fast check that the system is alive after deploy.
- **Spring Cloud Contract** — provider-driven contract framework in the Spring ecosystem.
- **Statement/instruction coverage** — coverage at the bytecode-instruction level.
- **Status check** — pass/fail signal a CI job reports to a PR.
- **Stub** — a test double returning hard-coded answers.
- **Surefire** — Maven plugin running unit tests (`*Test`) in the `test` phase.
- **Test double** — umbrella term for dummy/stub/spy/mock/fake.
- **Test pyramid** — heuristic ratio: many unit, fewer integration, few E2E tests.
- **Test runner/framework** — harness that discovers, runs, and reports tests.
- **Testcontainers** — library that runs real dependencies in throwaway Docker containers for tests.
- **Testing trophy** — variant heuristic that emphasizes integration tests.
- **Transactional rollback** — wrapping each test in a transaction rolled back after, for isolation.
- **Wait strategy** — Testcontainers' readiness gate (log/port/HTTP/healthcheck) before a test proceeds.
- **WireMock** — HTTP stub/fake server for testing clients against simulated services.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Shape:** Many unit (ms, isolated) → fewer integration (real deps via Testcontainers) → few E2E (critical journeys). Avoid the ice-cream cone (mostly E2E/manual).
**Levels:** pure logic→unit; real-dependency correctness→integration; cross-service API agreement→contract; whole-system journey→E2E. Always test at the *lowest* level that can verify it.
**Real deps:** Testcontainers runs the *real* engine (Postgres/Kafka/Redis) in Docker; static/singleton containers + reuse for speed; Ryuk reaps leaks; pick the right wait strategy. Don't fake Postgres with H2.
**Contracts:** Pact = consumer-driven; consumer generates pact (type matching) → broker → provider verifies with provider states → `can-i-deploy` gates release. No need to run services together.
**Flaky tests (the big pain):** detect (fail-then-pass) → quarantine (runs, doesn't block) → root-cause (`@RepeatedTest`, parallel, randomized) → fix/delete → prevent (ban `sleep`, hermeticity). Never auto-retry to mask.
**Coverage:** a signal, not a target (Goodhart). Gate on diff/new-code coverage (~70–80% floor), not global %. Use **mutation testing** (PIT, killed/survived) to measure real test strength; run incremental/nightly.
**Speed:** reuse setup, Spring slices, avoid `@DirtiesContext`, parallel forks/threads (`@ResourceLock`), shard by *timing*, predictive selection for monorepos, cache deps.
**Maven:** Surefire=`*Test` unit (`test` phase, fails fast); Failsafe=`*IT` integration (`verify`, cleanup-safe).
**Gating:** block merge on fast+reliable checks (unit, fast integration, contract); block deploy on `can-i-deploy`+smoke; *alert only* on E2E/mutation/coverage-trend; never block on flaky.
**Hermeticity:** pin image tags, inject `Clock`, seed `Random`, force UTC/locale, no open network — the root cure for flakiness.
**Numbers to remember:** keep PR test feedback under ~10 min; container start ~1–5s; coverage gate ~70–80% on new code; mutation score target often ~70–80% (100% rarely achievable due to equivalent mutants).

### 12.2 Self-test questions (no answers — recall practice)

1. A behavior depends on Postgres `JSONB` semantics. At which test level does it belong, with what tooling, and why is H2 the wrong choice?
2. Describe end-to-end the Pact consumer-driven workflow from writing the consumer test through `can-i-deploy` blocking a bad deploy.
3. Your suite went from 8 to 24 minutes after a refactor and developers are merging on red. List the most likely causes and the order in which you'd investigate.
4. Why is a 95% line-coverage suite potentially worse-tested than a 70% suite, and what two measures would you add to find out?
5. Walk through the detect→quarantine→fix loop for a test that fails ~5% of the time, including the exact techniques you'd use to reproduce it.
6. Explain why Maven splits Surefire and Failsafe and what would break if you ran integration tests through Surefire.
7. You must run a 50-minute suite in under 8 minutes of wall-clock on CI. Design the parallelization/selection strategy and name the isolation risks at each layer.
8. Define hermeticity and list five concrete enforcement mechanisms; for each, name the flakiness class it eliminates.

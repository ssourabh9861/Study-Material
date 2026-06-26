# Agent Evaluation

> **Concept area:** Agentic AI & Agents
> **Subtopic:** Agent Evaluation
> **Reader:** A senior backend engineer (Java/JVM-leaning) who wants to fully master how to measure, regression-test, and operate LLM agents in production.

---

## 1. Overview & where it fits

### 1.1 What "agent evaluation" means

An **LLM agent** (Large Language Model agent) is a software loop in which a language model is given a goal, a set of **tools** (functions it can call — search the web, run SQL, edit a file, call an API), and some memory, and it then **decides on its own which steps to take, in what order, until the goal is met or it gives up.** Contrast this with a single prompt-and-response chatbot: a chatbot answers in one shot; an agent runs a *multi-step loop* — think, act (call a tool), observe the result, think again — possibly for dozens of steps.

**Agent evaluation** is the discipline of measuring *how good an agent is*: does it accomplish the task, does it do so efficiently, safely, cheaply, and reliably, and does a change you made yesterday make it better or worse today? It is the agent-world equivalent of unit tests + integration tests + load tests + SLO monitoring, all fused together — except the system under test is *non-deterministic* and there is rarely a single correct answer.

### 1.2 The problem it solves

When you build a normal Java service, you write `assertEquals(expected, actual)` and you are done: the output is deterministic, so the test is a true/false oracle. Agents break every assumption that test rests on:

- **Non-determinism:** the same input can produce different outputs run to run (sampling temperature, model updates, tool flakiness).
- **Many valid paths:** there can be five legitimate ways to solve a task. A rigid string match would fail four of them even though they are correct.
- **Multi-step trajectories:** failure can happen at step 7 of 20. You need to know *where* and *why*, not just "it failed."
- **Open-ended outputs:** the answer is often free-form text, code, or a sequence of actions — not a scalar you can `==`.
- **Cost and latency matter:** a correct answer that costs $4 and takes 90 seconds may be unacceptable.

Agent evaluation gives you a **repeatable, quantitative grip** on a system that is otherwise a slot machine. Without it, you are shipping prompt and model changes on vibes.

### 1.3 When you reach for it

- Before shipping any agent to production (a baseline eval suite).
- On **every change** to the prompt, the model version, the tool set, the retrieval index, or the orchestration logic (regression testing in CI).
- **Continuously in production** (online eval) — because the world drifts: the underlying model gets silently updated, user inputs shift, tools change their APIs.
- When choosing between models or frameworks (a bake-off).

### 1.4 One-paragraph mental model

> An agent run produces a **trajectory** — an ordered transcript of (thought → tool call → observation) steps ending in a final answer. Agent evaluation scores two orthogonal things: **the outcome** (did the final answer/world-state match what we wanted?) and **the process** (was the trajectory efficient, were the right tools called with the right arguments, was it cheap and fast and safe?). Because outputs are non-deterministic and open-ended, your "assert" is replaced by a portfolio of **graders** — exact-match where possible, programmatic/functional checks where you can run code, and **LLM-as-judge** (a second model scoring the first) where the answer is fuzzy — all run over a curated **dataset** of tasks with known good answers (the "golden set"), executed repeatedly to average out noise, and tracked over time so regressions are caught before users feel them.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Every term a newcomer might not know is defined inline.

### 2.1 The agent loop (the thing being evaluated)

A minimal agent loop:

```
goal ──► [ LLM reasons ] ──► chooses an action
              ▲                     │
              │                     ▼
        observation  ◄──── [ tool executes ]
              │
              └──► (repeat until LLM emits "final answer" or budget exhausted)
```

Key terms:

- **Tool / function call:** a structured request the model emits asking the runtime to execute a named function with arguments, e.g. `search_flights(origin="BLR", dest="SFO", date="2026-07-01")`. The model does not run code itself; your harness runs the function and feeds the result back. This is also called **tool use** or **function calling**.
- **Observation:** the result returned to the model after a tool runs (the function's return value, an API response, an error).
- **Trajectory (a.k.a. trace, rollout, episode):** the full ordered list of steps — the model's intermediate reasoning ("thoughts"), each tool call with its arguments, each observation, and the final answer. This is the primary artifact you evaluate.
- **Step / turn:** one iteration of the loop. "It took 14 steps" means 14 tool-call/observe cycles.
- **Final answer / terminal state:** what the agent returns to the user, *and/or* the resulting change to the world (a file written, a row inserted, a refund issued).

### 2.2 Determinism, sampling, and why "==" fails

LLMs generate text token by token, each token sampled from a probability distribution.

- **Temperature:** a knob (typically 0.0–2.0) controlling randomness. `temperature=0` makes the model pick the most likely token every time — *nearly* deterministic, but not perfectly so because of floating-point non-associativity on GPUs, batching effects, and backend changes. **Important:** even at temperature 0, you should not assume bit-for-bit reproducibility.
- **Seed:** some APIs accept a `seed` to make sampling reproducible *given the same backend*. It is best-effort, not a contract.

Consequence: your grader cannot be `assertEquals("Paris", output)` because the model might say "The capital is Paris." or "Paris, France." Graders must tolerate surface variation while still catching wrong answers.

### 2.3 The two axes of evaluation

This is the single most important conceptual split in the whole topic.

| Axis | Question it answers | Example metric |
|---|---|---|
| **Outcome eval** (a.k.a. *end-to-end*, *final-answer*, *black-box*) | Did the agent achieve the goal? | Task success rate, answer correctness |
| **Process eval** (a.k.a. *trajectory*, *step-level*, *white-box*) | Did it get there well? | Tool-call accuracy, number of steps, cost, latency, no unsafe actions |

A good agent must pass both. An agent can produce the right answer by a lucky guess after 30 wasted steps (good outcome, bad process), or call all the right tools and still botch the final synthesis (good process, bad outcome).

### 2.4 The grader / oracle taxonomy

A **grader** (or **scorer**, or **evaluator**) is the thing that turns a trajectory into a score. Three families:

1. **Exact / reference-based:** compare output to a known correct answer (the **reference** or **ground truth**). Variants: exact string match, normalized match (lowercase, trim), set match, numeric tolerance, regex.
2. **Programmatic / functional:** *run something* to check correctness. For a coding agent, execute the produced code against a hidden test suite (this is how SWE-bench works). For a SQL agent, run the query and compare the result set. For a "book a flight" agent, inspect the resulting reservation in a sandbox database. This is the gold standard when achievable because it is objective.
3. **Model-based (LLM-as-judge):** a second LLM reads the output (and optionally the reference and a rubric) and emits a score or verdict. Used when correctness is subjective ("was this summary faithful?", "was the tone helpful?"). Powerful but fallible — see §6 and §7.

A fourth, slower family is **human evaluation**: people rate outputs. Most accurate, least scalable, used to *calibrate* the automated graders.

### 2.5 Datasets, golden sets, and golden trajectories

- **Eval dataset:** a curated collection of **tasks** (inputs) you run the agent on. Each task ideally carries a **reference** (expected outcome) and/or a **rubric** (criteria for grading).
- **Golden set / golden dataset:** a trusted, curated, version-controlled set of tasks with verified expected outcomes — your regression baseline.
- **Golden trajectory:** for process eval, a known-good *path* — "for this task, the agent should call `tool_A` then `tool_B`, and never call `delete_account`." You compare the actual trajectory to the golden one.
- **Hold-out / test split:** tasks you never use for prompt-tuning, kept to detect overfitting (the agent being silently tuned to ace your eval set rather than the real task).

### 2.6 Offline vs online evaluation

- **Offline eval:** run the agent on a fixed dataset in a controlled environment, before deployment. Reproducible, cheap-ish, fast feedback. This is your CI gate.
- **Online eval:** evaluate real production traffic, after deployment. Catches drift and real-world distribution. Includes **human feedback** (thumbs up/down), **implicit signals** (did the user retry, abandon, escalate to a human?), and **LLM-as-judge running on live traces** (often sampled).

### 2.7 Observability as the foundation

You cannot evaluate what you cannot see. **Observability** here means capturing, for every agent run, the full structured trace: each LLM call (prompt, response, tokens, latency, cost), each tool call (name, args, result, errors), timings, and metadata. The modern standard is **tracing** built on **OpenTelemetry** (an open, vendor-neutral standard for distributed traces; a *trace* is a tree of timed *spans*, each span being one unit of work). Evaluation is then "run graders over the captured traces." If you skip this, online eval and debugging are impossible. (Details in §3.6 and §6.)

---

## 3. How it works internally

This section is the heart of the document. We trace the full lifecycle of an evaluation, then the internal data/control flow, then the state machine.

### 3.1 The end-to-end eval lifecycle (offline)

```
[1] Define tasks ──► [2] Attach references/rubrics ──► [3] Run agent on each task
                                                              │
                                                              ▼
[6] Aggregate & report ◄── [5] Score each trajectory ◄── [4] Capture trajectory (trace)
        │
        ▼
[7] Compare to baseline / gate CI
```

**Step 1 — Define tasks.** Each task is a record: an input (the user goal + any initial state), a unique ID, and metadata (category, difficulty, tags). Example: `{"id":"refund-007","input":"I want a refund for order 12345","initial_db_state":"orders.sql","expected":"refund issued for $49.99"}`.

**Step 2 — Attach the oracle.** Decide *how* this task will be scored: exact match? a code test suite? a rubric for LLM-as-judge? a golden trajectory? Often several.

**Step 3 — Run the agent.** The eval harness instantiates the agent under test, feeds it the task input, and lets the loop run. Critically the harness controls:
- **The environment/sandbox** — a fresh, isolated copy of any external state (database, filesystem, mock APIs) so runs don't pollute each other and so functional graders have something to inspect.
- **Budgets** — max steps, max tokens, max wall-clock time, so a runaway agent terminates.
- **Repetitions (k)** — run each task k times (e.g. k=3 or k=5) to average out non-determinism.

**Step 4 — Capture the trajectory.** Every step is recorded: thoughts, tool calls + args, observations, token counts, per-step latency, final answer, terminal world-state.

**Step 5 — Score.** Each grader runs over the captured trajectory and/or final state, emitting per-task scores (success/fail, a rubric score 1–5, tool-call F1, step count, cost in dollars, latency in ms).

**Step 6 — Aggregate.** Roll per-task scores into suite-level metrics: success rate, **pass@k** (passed at least once in k tries), **pass^k / avg@k** (passed all k / average over k), mean cost, p50/p95 latency, broken down by category.

**Step 7 — Gate.** Compare to the stored baseline. Fail the build if success rate drops > threshold, cost rises > threshold, or any "must-never-do" safety check trips.

### 3.2 Internal control flow of a single graded run (pseudo-trace)

```
harness.run(task):
    env = sandbox.clone(task.initial_state)       # isolated world
    tracer.start_trace(task.id)
    agent = Agent(model, tools=env.tools, budget)
    state = INIT
    while state == RUNNING and steps < budget.max_steps:
        span = tracer.start_span("llm_call")
        decision = model.generate(history)        # may be: tool_call | final_answer
        span.record(prompt, decision, tokens, latency, cost)
        if decision.is_tool_call:
            tspan = tracer.start_span("tool:" + decision.name)
            obs = env.execute(decision.name, decision.args)  # runs the real/mock tool
            tspan.record(args, obs, latency, error?)
            history.append(decision, obs)
        else:
            final = decision.answer
            state = DONE
    trajectory = tracer.end_trace()
    scores = {g.name: g.score(task, trajectory, env.final_state())
              for g in task.graders}
    return scores, trajectory
```

The two nested spans (LLM call, tool call) are what make per-step process metrics possible.

### 3.3 Data flow: what each grader consumes

| Grader type | Inputs it reads | Output |
|---|---|---|
| Exact-match | `trajectory.final_answer`, `task.reference` | bool |
| Functional (code) | `env.final_state` (files), runs `task.test_suite` | bool + which tests passed |
| Functional (DB/API) | `env.final_state` (DB rows / API mock log) | bool |
| Trajectory match | full `trajectory.tool_calls`, `task.golden_trajectory` | precision/recall/F1, order score |
| Tool-arg check | each `tool_call.args` vs expected schema/values | per-call correctness |
| LLM-as-judge | `final_answer` (+ optional reference + rubric) | score/verdict + rationale |
| Cost/latency | per-span token counts & timings | $ and ms |
| Safety | scan `trajectory` for forbidden tool calls/args | bool (must be true) |

### 3.4 The agent eval state machine

```
        ┌─────────┐
        │  INIT   │  sandbox cloned, budgets set
        └────┬────┘
             ▼
        ┌─────────┐  model emits tool_call
        │ RUNNING │◄──────────────┐
        └──┬───┬──┘                │ observation fed back
   final  │   │ budget exceeded    │
   answer │   ▼                    │
          │ ┌──────────┐           │
          │ │ ABORTED  │ ──► score=fail (no_progress / loop / timeout)
          │ └──────────┘           │
          ▼                        │
     ┌─────────┐                   │
     │  DONE   │ ──► run graders ──┘ (on the captured trajectory)
     └─────────┘
```

`ABORTED` sub-reasons matter for debugging: **step-budget exceeded**, **token-budget exceeded**, **wall-clock timeout**, **infinite loop detected** (same tool+args repeated), **tool fatal error**, **model refusal**.

### 3.5 How non-determinism is handled internally

Because one run is a coin flip, the harness runs each task **k** times and aggregates:

- **pass@k:** *did at least one of k attempts succeed?* Optimistic — good for "can it ever do this?" but flatters flaky agents. Often estimated with the unbiased formula `pass@k = 1 - C(n-c, k) / C(n, k)` where you sampled `n` rollouts and `c` succeeded.
- **pass^k (or pass-hat-k / "consistency"):** *did all k attempts succeed?* Pessimistic — measures reliability, the metric tau-bench popularized for customer-service agents where you need consistency, not luck.
- **avg@k / mean success:** fraction of the k attempts that succeeded — the most balanced default.

Always report which k and which aggregation; "92% success" is meaningless without it.

### 3.6 Tracing internals (the substrate)

Modern stacks emit **OpenTelemetry spans** following the **GenAI semantic conventions** (a standardized set of span/attribute names for LLM operations — e.g. `gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`). One agent run = one trace = a tree:

```
trace: refund-007
└─ span agent.run                       (root, 6.2s, $0.031)
   ├─ span llm.call #1                   (1.1s, 420 in / 88 out tok)
   ├─ span tool.lookup_order             (0.3s)
   ├─ span llm.call #2                   (0.9s)
   ├─ span tool.issue_refund             (0.4s)   ← side effect captured here
   └─ span llm.call #3 (final answer)    (0.8s)
```

Evaluation libraries then attach **scores** to traces/spans. This is why the eval layer and the observability layer are the same layer in tools like LangSmith, Langfuse, Arize Phoenix, and Braintrust.

---

## 4. The complete toolkit

This section enumerates the metrics, the grading methods, the standard benchmarks, and the libraries/tools — each with purpose, parameters, and defaults. Flagged where version/vendor-specific.

### 4.1 Core metrics

| Metric | What it measures | Typical definition / unit | Notes & defaults |
|---|---|---|---|
| **Task success rate** | Outcome | fraction of tasks where final outcome matches reference | The headline number. Pair with k and aggregation. |
| **pass@k** | Outcome (optimistic) | ≥1 of k attempts succeed | Common k: 1, 3, 5. |
| **pass^k** | Reliability | all k attempts succeed | tau-bench reports pass^1..pass^4. |
| **Tool-call accuracy** | Process | right tool chosen | per-call; aggregate as accuracy. |
| **Tool-arg correctness** | Process | args match expected (exact/semantic) | check name *and* args. |
| **Trajectory F1 / order score** | Process | overlap & ordering vs golden trajectory | precision/recall over the set of calls. |
| **Step count** | Efficiency | number of loop iterations | lower is better, all else equal. |
| **Token cost** | Cost | input+output tokens × price | report $ per task and per suite. |
| **Latency** | Efficiency | wall-clock per run | report p50, p95, p99. |
| **Loop/redundancy rate** | Process | repeated identical calls | proxy for confusion. |
| **Safety violation rate** | Correctness/safety | forbidden actions taken | must be 0; gate hard. |
| **Faithfulness / groundedness** | Quality | answer supported by retrieved context | for RAG-style agents; usually LLM-judged. |
| **Hallucination rate** | Quality | unsupported claims | LLM-judged or NLI-based. |

### 4.2 Grading methods

| Method | Use when | Key knobs | Pitfalls |
|---|---|---|---|
| Exact/normalized match | Single canonical answer | normalization rules | brittle to phrasing |
| Set/numeric match | Lists, numbers | tolerance, order-insensitive | unit mismatches |
| Functional (run code) | Code/SQL/state-changing | hidden test suite, sandbox | flaky tests, env setup cost |
| Trajectory/golden-path | Process correctness | order strictness, allowed extras | over-specifying the path |
| LLM-as-judge (scoring) | Subjective quality | model, rubric, scale, temperature | bias, drift, cost (§7) |
| LLM-as-judge (pairwise) | A/B comparisons | position-swap, ties allowed | position bias |
| Human review | Calibration, high stakes | rubric, inter-rater agreement | slow, expensive |

### 4.3 Standard agent benchmarks (high level)

| Benchmark | Domain | What the agent does | How it's graded | Why it matters |
|---|---|---|---|---|
| **SWE-bench** (and **SWE-bench Verified**) | Software engineering | given a real GitHub issue + repo, produce a patch | **functional**: run the repo's hidden test suite; pass = tests go red→green | Gold-standard for coding agents; objective. *Verified* is a human-filtered ~500-task subset removing broken/ambiguous tasks. |
| **GAIA** | General assistant | answer real-world questions needing web browsing, multimodality, tool use, multi-hop reasoning | exact-match against a single correct answer; tasks tiered Level 1–3 | Tests *general* tool-using competence; humans score ~92%, early agents far lower. |
| **tau-bench (τ-bench)** | Customer-service tool agents (retail, airline) | converse with a (simulated) user, call domain APIs to fulfill requests under written policies | functional: compare final **DB state** to expected; reports **pass^k** for reliability | Pioneered measuring *consistency*, not just one-shot success; user is itself an LLM. |
| **WebArena / VisualWebArena** | Web navigation | accomplish tasks on realistic self-hosted websites | functional + reward functions on end state | Realistic, reproducible web environments. |
| **AgentBench** | Multi-environment | OS, DB, web, games | per-environment success | Breadth across agent settings. |
| **BFCL (Berkeley Function-Calling Leaderboard)** | Function calling | choose & populate the right function call | AST/exec match of the call | Isolates tool-selection/arg-filling skill. |
| **MLE-bench / others** | ML engineering, etc. | domain-specific | varies | Specialized; check the paper. |

> Treat public benchmarks as *capability signals*, not as your acceptance test. Your production agent must be evaluated on *your* tasks and *your* tools. Benchmarks also risk **contamination** (the benchmark leaked into model training data), which inflates scores. Version/leaderboard numbers move monthly — always cite the date and exact subset.

### 4.4 Evaluation & observability libraries/platforms

| Tool | Type | Notes (version/vendor-specific) |
|---|---|---|
| **OpenAI Evals** | OSS framework | define datasets + graders (incl. model graders) as code/config; OpenAI ecosystem. |
| **LangSmith** | Hosted (LangChain) | tracing + datasets + evaluators + experiments; SDK in Python/JS. Commercial. |
| **Langfuse** | OSS + hosted | OTel-based tracing, datasets, scores, LLM-as-judge; self-hostable. |
| **Arize Phoenix** | OSS + hosted | OTel-native tracing + evals; strong for RAG/agent traces. |
| **Braintrust** | Hosted | datasets, scorers, experiments, CI integration. Commercial. |
| **Ragas** | OSS (Python) | RAG-centric metrics (faithfulness, context precision/recall, answer relevancy); extends to agents. |
| **DeepEval** | OSS (Python) | pytest-style LLM/agent assertions; CI-friendly. |
| **promptfoo** | OSS CLI | declarative YAML eval/redteam; great for CI gates and matrix tests. |
| **Inspect (UK AISI)** | OSS (Python) | rigorous eval framework used for safety/capability evals; solvers + scorers. |
| **OpenTelemetry GenAI** | Standard | the wire format underneath most of the above. |

For a JVM shop: most eval frameworks are Python-first. The pragmatic pattern (see §5.5) is to expose the agent over an API and drive evals from Python, *or* emit OpenTelemetry spans from your Java agent and score them in any OTel-compatible backend, *or* use **LangChain4j** (a Java port of LangChain) plus JUnit for in-process Java evals.

### 4.5 Key configuration knobs (eval harness)

| Knob | Purpose | Typical default |
|---|---|---|
| `k` (repetitions) | average out noise | 1 for cheap CI, 3–5 for release gates |
| `temperature` | controls run variance | 0 for grading reproducibility; match prod for realistic eval |
| `max_steps` | runaway guard | 10–50 task-dependent |
| `max_tokens` / token budget | cost guard | task-dependent |
| `timeout` | wall-clock guard | e.g. 120s |
| `concurrency` | speed up suite | bounded by rate limits |
| `seed` | best-effort reproducibility | set if supported |
| `judge_model` | LLM-as-judge backend | a strong, fixed, *pinned* version |
| `sample_rate` (online) | fraction of prod traffic judged | 1–10% typical to control cost |

---

## 5. Code examples by use case

Idiomatic, explained, copy-adaptable. Default to Java where it fits; Python where the ecosystem lives.

### 5.1 Outcome eval with a functional grader — coding agent (Java/JUnit)

Run the agent on a task, then *execute* its output against a hidden test to grade it. This is the SWE-bench pattern in miniature.

```java
// Outcome eval: does the agent's generated code pass the hidden tests?
class AgentCodingEvalTest {

    // The agent under test. Replace with your real client.
    interface CodingAgent { String solve(String issue); }

    record Task(String id, String issue, String hiddenTestSrc) {}

    @Test
    void evaluateSuite() throws Exception {
        CodingAgent agent = new MyCodingAgent();          // wraps the LLM loop
        List<Task> suite = loadGoldenTasks("eval/coding.json");

        int passed = 0;
        for (Task t : suite) {
            String patch = agent.solve(t.issue());        // [1] run the agent
            // [2] FUNCTIONAL grader: compile patch + hidden test, run it.
            boolean ok = compileAndRunInSandbox(patch, t.hiddenTestSrc());
            if (ok) passed++;
            // [3] record per-task result for the report (id, ok, cost, latency...)
            EvalReport.record(t.id(), ok);
        }
        double successRate = (double) passed / suite.size();
        // [4] GATE: fail CI if below the stored baseline minus tolerance.
        assertThat(successRate).isGreaterThanOrEqualTo(Baseline.CODING - 0.02);
    }
}
```

Why it matters: the grader is *objective* — code either passes the hidden tests or it doesn't. No LLM judge needed. The sandbox (`compileAndRunInSandbox`) must be isolated (a throwaway container/temp dir) so one task can't corrupt another.

### 5.2 Trajectory / process eval — tool-call correctness (Java)

Here we score the *path*, not just the answer: did the agent call the right tools, in an acceptable order, with correct args, and never the forbidden one?

```java
// Process eval: grade the trajectory against a golden path + safety rules.
record ToolCall(String name, Map<String,Object> args) {}
record Trajectory(List<ToolCall> calls, String finalAnswer) {}

class TrajectoryGrader {

    // Golden expectation for a "issue refund" task.
    static final List<String> EXPECTED_ORDER =
        List.of("lookup_order", "check_refund_policy", "issue_refund");
    static final Set<String> FORBIDDEN =
        Set.of("delete_account", "issue_refund_unverified");

    record Result(double f1, boolean orderOk, boolean safe, boolean argsOk) {}

    Result grade(Trajectory traj) {
        List<String> actual = traj.calls().stream().map(ToolCall::name).toList();

        // [1] SAFETY (hard gate): forbidden tool must never appear.
        boolean safe = actual.stream().noneMatch(FORBIDDEN::contains);

        // [2] Set-overlap precision/recall/F1 vs the expected tool set.
        Set<String> exp = new HashSet<>(EXPECTED_ORDER);
        long tp = actual.stream().distinct().filter(exp::contains).count();
        double precision = actual.isEmpty() ? 0 : (double) tp / new HashSet<>(actual).size();
        double recall = (double) tp / exp.size();
        double f1 = (precision + recall == 0) ? 0 : 2 * precision * recall / (precision + recall);

        // [3] ORDER: expected calls appear as an ordered subsequence.
        boolean orderOk = isSubsequence(EXPECTED_ORDER, actual);

        // [4] ARG check on the critical call: refund amount must be set & positive.
        boolean argsOk = traj.calls().stream()
            .filter(c -> c.name().equals("issue_refund"))
            .allMatch(c -> ((Number) c.args().getOrDefault("amount", -1)).doubleValue() > 0);

        return new Result(f1, orderOk, safe, argsOk);
    }

    // True if `needle` appears in `hay` in order (gaps allowed).
    static boolean isSubsequence(List<String> needle, List<String> hay) {
        int i = 0;
        for (String h : hay) if (i < needle.size() && h.equals(needle.get(i))) i++;
        return i == needle.size();
    }
}
```

Why it matters: outcome graders miss *how* the agent got there. A safety violation (`delete_account`) might still produce a "correct-looking" answer; only process eval catches it. Note we allow *extra* tool calls and only require the golden ones as a subsequence — over-specifying the exact path is a classic anti-pattern (§6.8).

### 5.3 LLM-as-judge with a rubric (Python)

When the answer is free-form (a support reply, a summary), use a second model as judge — but constrain it with a rubric, a fixed scale, and structured output.

```python
import json
from openai import OpenAI
client = OpenAI()

JUDGE_MODEL = "gpt-4.1-2025-xx"  # PIN the exact version; judge drift breaks comparability.

RUBRIC = """You are grading a customer-support reply.
Score each criterion 0-2 (0=fails, 1=partial, 2=fully meets):
- correctness: factually right per the provided reference
- helpfulness: resolves the user's actual problem
- safety: no policy violation, no PII leak
Return STRICT JSON: {"correctness":int,"helpfulness":int,"safety":int,"reason":str}
"""

def judge(question: str, answer: str, reference: str) -> dict:
    resp = client.chat.completions.create(
        model=JUDGE_MODEL,
        temperature=0,                      # deterministic-ish grading
        response_format={"type": "json_object"},  # force parseable output
        messages=[
            {"role": "system", "content": RUBRIC},
            {"role": "user", "content":
                f"QUESTION:\n{question}\n\nREFERENCE:\n{reference}\n\nANSWER:\n{answer}"},
        ],
    )
    return json.loads(resp.choices[0].message.content)

# Aggregate over a dataset, and HARD-GATE safety.
def run_suite(dataset):
    rows = []
    for ex in dataset:
        ans = my_agent(ex["question"])          # run the agent under test
        score = judge(ex["question"], ans, ex["reference"])
        rows.append(score)
        assert score["safety"] == 2, f"SAFETY FAIL on {ex['id']}: {score['reason']}"
    n = len(rows)
    return {
        "correctness": sum(r["correctness"] for r in rows) / n,
        "helpfulness": sum(r["helpfulness"] for r in rows) / n,
    }
```

Why it matters: structured JSON + a 0–2 rubric reduces the judge's freedom and makes scores comparable. `temperature=0` and a *pinned* judge model are mandatory for run-to-run comparability (see pitfalls in §7).

### 5.4 Pairwise LLM-as-judge with position-bias control (Python)

For A/B comparisons (old prompt vs new prompt), pairwise judging is more reliable than absolute scores — but LLM judges favor whichever answer is shown first. Fix by swapping positions and averaging.

```python
def pairwise(question, ans_a, ans_b):
    def ask(first, second):
        resp = client.chat.completions.create(
            model=JUDGE_MODEL, temperature=0,
            response_format={"type": "json_object"},
            messages=[{"role":"system","content":
                'Pick the better answer. Return {"winner":"FIRST|SECOND|TIE"}'},
                {"role":"user","content":
                f"Q:{question}\nFIRST:{first}\nSECOND:{second}"}])
        return json.loads(resp.choices[0].message.content)["winner"]

    w1 = ask(ans_a, ans_b)               # A first
    w2 = ask(ans_b, ans_a)               # B first  (swap to cancel position bias)
    # Resolve: A wins only if it wins (or ties favorably) in BOTH orderings.
    a_wins = (w1 == "FIRST") + (w2 == "SECOND")
    b_wins = (w1 == "SECOND") + (w2 == "FIRST")
    if a_wins > b_wins: return "A"
    if b_wins > a_wins: return "B"
    return "TIE"
```

Why it matters: without the swap you can measure a 5–10 point "improvement" that is pure position bias. Always control for it.

### 5.5 Driving Python evals against a Java agent over HTTP

A JVM agent can still be evaluated with the rich Python ecosystem by exposing it as a service.

```python
# promptfoo-style or custom: hit the Java agent's endpoint, then grade.
import requests, statistics
def java_agent(task): 
    return requests.post("http://localhost:8080/agent",
                         json={"goal": task}, timeout=120).json()  # returns {answer, trajectory, cost_usd, latency_ms}

def eval_one(ex):
    out = java_agent(ex["goal"])
    success = grade_functional(out, ex)        # your oracle
    return {"success": success, "cost": out["cost_usd"], "latency": out["latency_ms"]}

results = [eval_one(ex) for ex in dataset for _ in range(3)]  # k=3
print("success", statistics.mean(r["success"] for r in results))
print("p95_latency", sorted(r["latency"] for r in results)[int(0.95*len(results))])
```

### 5.6 Regression test in CI (promptfoo YAML)

Declarative, language-agnostic, perfect as a CI gate.

```yaml
# promptfooconfig.yaml  — runs in CI; fails build if assertions fail.
providers:
  - id: https://localhost:8080/agent      # your agent endpoint
prompts:
  - "{{goal}}"
tests:
  - vars: { goal: "Refund order 12345" }
    assert:
      - type: contains-json                # output has a refund object
      - type: javascript                   # custom programmatic check
        value: "JSON.parse(output).amount === 49.99"
      - type: not-contains
        value: "delete_account"            # safety: forbidden action
      - type: llm-rubric                   # model-graded helpfulness
        value: "Reply confirms the refund and is polite."
      - type: cost
        threshold: 0.05                     # fail if > $0.05/run
      - type: latency
        threshold: 8000                     # fail if > 8s
```

### 5.7 Online eval — sampling production traces for LLM-judging (Python)

```python
# Runs continuously; samples live traces and scores them, no reference available.
import random
def online_eval(trace):
    if random.random() > 0.05:           # sample 5% of traffic to control cost
        return
    verdict = client.chat.completions.create(
        model=JUDGE_MODEL, temperature=0,
        response_format={"type":"json_object"},
        messages=[{"role":"system","content":
            'Reference-free quality check. {"helpful":0|1,"unsafe":0|1,"reason":str}'},
            {"role":"user","content": trace["final_answer"]}]).choices[0].message.content
    v = json.loads(verdict)
    metrics.emit("online.helpful", v["helpful"], tags={"model": trace["model"]})
    if v["unsafe"]:
        alert(f"Unsafe production output {trace['id']}: {v['reason']}")
```

Why it matters: production has no ground truth, so judges run *reference-free*; pair them with implicit signals (thumbs, retries, escalations) and human spot-checks.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & cost of the eval itself

- Evals are expensive: every task × every k × (agent calls + judge calls) = many LLM calls. A 500-task suite at k=5 with a judge can be tens of thousands of calls and real dollars per run. **Budget it.** Use a small "smoke" suite (20–50 tasks, k=1) on every PR and the full suite (500+, k=3–5) nightly/on release.
- **Cache** deterministic pieces (e.g., judge calls keyed by `(judge_model, prompt_hash)`) to avoid re-paying.
- **Parallelize** runs, bounded by provider **rate limits** (requests/min and tokens/min); add backoff/retry on 429s.

### 6.2 Correctness & statistical rigor

- One run ≠ a measurement. Report **confidence intervals**. A jump from 84% → 86% on 100 tasks is within noise (95% CI is roughly ±7 points at n=100). Use enough tasks and k.
- Use **paired comparisons** (same tasks, old vs new) and a significance test (e.g., McNemar's test for paired pass/fail) before declaring an improvement.
- Beware **flaky functional tests** (network, time, randomness in the task itself) — they masquerade as agent regressions.

### 6.3 Security

- **Sandbox everything.** A coding/SQL/shell agent under eval can `rm -rf`, exfiltrate secrets, or hit production. Run in throwaway containers with no network egress, no real credentials, least-privilege mock APIs.
- **Prompt-injection in eval data:** if tasks contain attacker-style content, ensure the judge isn't hijacked. Keep judge prompts robust and never let task text override judge instructions.
- **PII:** captured traces may contain user data. Redact before storing; control access to the trace store; respect retention/GDPR.

### 6.4 Observability & testability

- Make the agent **traceable by construction** (OpenTelemetry GenAI spans). You cannot debug or online-eval an agent that doesn't emit structured traces.
- Make the agent **deterministically seedable** where possible and inject the model/tools (dependency injection) so the eval harness can swap in mocks.
- Record *everything* per run: full prompts, raw responses, tool args/results, tokens, costs, timings, errors, and the random seed.

### 6.5 Production hardening

- **Versioned everything:** dataset version, prompt version, model version, tool schema version, judge version. A score is only meaningful relative to these.
- **Baselines stored in source control**; gate CI against them; require manual sign-off to move a baseline.
- **Separate "must-pass" from "track-only"** assertions: safety = hard gate; helpfulness = trend you watch.

### 6.6 Cost controls in eval

- Track $ per task and per suite as first-class metrics, not afterthoughts.
- A cheaper judge can grade obvious cases; escalate ambiguous ones to a stronger judge ("judge cascade").

### 6.7 Calibrating LLM-as-judge against humans

- Periodically have humans grade a sample, then measure judge–human **agreement** (e.g., Cohen's kappa, or % agreement). If agreement is low, your judge metric is fiction. Re-tune the rubric until agreement is acceptable (commonly target > 0.7 kappa for production reliance).

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it hurts | Do instead |
|---|---|---|
| **String-equality on free-form answers** | fails correct paraphrases | functional or rubric graders |
| **Over-specifying the golden trajectory** | penalizes valid alternative paths | grade outcome + required-subsequence, allow extras |
| **Single run, no k** | mistakes noise for signal | repeat k times, report CI |
| **Unpinned judge model** | scores shift when vendor updates the model | pin exact version; re-baseline on change |
| **Judging with the same model that generated** | self-preference bias | use a different judge family or pairwise |
| **Only outcome, never process** | misses safety/efficiency failures | always run process metrics too |
| **Overfitting to the eval set** | great scores, bad prod | hold-out split; refresh dataset; online eval |
| **No cost/latency in the suite** | ships expensive/slow agents | gate on $ and p95 |
| **Benchmark scores as acceptance** | contamination + domain mismatch | evaluate on *your* tasks |
| **Evaluating without tracing** | can't debug or online-eval | OTel spans from day one |

---

## 7. Advanced topics & deep internals

### 7.1 LLM-as-judge pitfalls (deep)

Judges are LLMs and inherit all their biases:

- **Position bias:** prefers the first (or last) option in pairwise. *Fix:* swap-and-average (§5.4).
- **Verbosity / length bias:** prefers longer answers. *Fix:* rubric explicitly rewards concision; normalize.
- **Self-enhancement / self-preference bias:** a model rates outputs from its own family higher. *Fix:* judge with a different model family; cross-check.
- **Sycophancy & authority bias:** swayed by confident tone or by being told an answer is "the expert's." *Fix:* hide such cues; instruct the judge to ignore tone.
- **Score compression:** judges cluster around 3–4 on a 1–5 scale. *Fix:* binary or 0–2 rubrics, anchored examples (few-shot exemplars of each score).
- **Inconsistency:** same input, different score across runs. *Fix:* temperature 0, multiple judge samples + majority vote, pinned model.
- **Rubric leakage / reward hacking:** the agent learns to produce text the judge likes without being correct. *Fix:* keep judges out of the optimization loop where possible; refresh; use functional graders as anchors.

### 7.2 Trajectory grading depth

- **Exact-order vs subsequence vs set:** strictness should match the task. Order matters for "must check policy *before* refunding"; it doesn't for "gather three independent facts."
- **Semantic arg matching:** `date="2026-07-01"` vs `date="July 1 2026"` are equal; do normalized/semantic comparison, not string equality.
- **Redundancy & loop detection:** count repeated `(tool,args)` pairs; high redundancy is a quality signal even when the outcome is right.
- **Partial credit:** for long tasks, award credit per subgoal reached, not just all-or-nothing — gives gradient for improvement.

### 7.3 Simulated users (tau-bench style)

For conversational agents, the "user" is itself an LLM following a persona + hidden goal. This makes multi-turn eval scalable but adds a second source of non-determinism (the simulated user). Pin the user model too, run higher k, and validate the user simulator against real transcripts.

### 7.4 pass^k and reliability math

`pass^1` is one-shot success; `pass^k` (all k succeed) decays fast for flaky agents: an agent with true 80% per-attempt success has pass^4 ≈ 0.8⁴ ≈ 41%. This is why tau-bench's pass^k exposes agents that look fine on pass@1 but are unusable when you need them to be right *every* time.

### 7.5 Reward modeling vs rule-based grading

Some pipelines train a **reward model** (a model that outputs a scalar quality score) to grade at scale. Faster and cheaper than an LLM judge per call, but it's a learned approximation that can be gamed and drifts; keep rule-based/functional anchors.

### 7.6 Contamination & benchmark hygiene

If benchmark data leaked into the model's training set, scores are inflated (the model memorized answers). Signs: implausibly high scores, sensitivity to trivial paraphrases. Mitigations: private/held-out test sets, canary strings, "verified"/refreshed benchmark versions, and date-stamped reporting.

### 7.7 Evaluating multi-agent systems

When multiple agents collaborate (planner, worker, critic), add: per-agent attribution (which agent's step failed?), communication-overhead metrics (messages exchanged), and deadlock/livelock detection. Trace must capture the inter-agent message graph.

### 7.8 Tuning knobs summary

- Lower `temperature` for grading stability; match production temperature for *realistic* eval — you may need both runs.
- Increase `k` until your CI on the metric is tight enough to detect the regression size you care about.
- Tighten `max_steps` over time as the agent matures (a maturing agent should need fewer steps).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Grader selection

| If the task… | Use this grader | Because |
|---|---|---|
| has a single canonical answer | exact/normalized match | objective, free, fast |
| produces code/SQL/state change | functional (run it) | most objective oracle |
| has many valid free-form answers | LLM-as-judge + rubric | tolerant of phrasing |
| is an A/B prompt comparison | pairwise LLM-judge (swapped) | more reliable than absolute scores |
| is safety-critical | rule-based hard gate | must be deterministic & non-negotiable |
| is high-stakes / low-volume | human eval | accuracy over scale |

### 8.2 Outcome vs process

- **Use outcome eval when** you can verify the end state objectively and you mainly care about results.
- **Use process eval when** safety, efficiency, cost, or tool-correctness matter, or when outcomes are hard to verify and the path is a proxy for quality.
- **Use both** for any production agent. They catch different failures.

### 8.3 Offline vs online

| | Offline | Online |
|---|---|---|
| When | pre-deploy, CI | post-deploy, continuous |
| Ground truth | yes (golden set) | usually no (reference-free) |
| Catches | regressions, capability gaps | drift, real-world distribution, model silent-updates |
| Cost | controllable | scales with traffic (sample it) |
| Verdict | gate the release | alert + feed back into dataset |

Always do both; offline gates the change, online catches what offline missed.

### 8.4 pass@k vs pass^k vs avg@k

- **pass@k:** "can it ever?" — capability ceiling, R&D.
- **avg@k:** balanced default for most reporting.
- **pass^k:** "can I rely on it?" — production reliability, customer-facing agents.

### 8.5 Build vs buy the eval platform

- **Buy/adopt OSS** (Langfuse, Phoenix, LangSmith, Braintrust) when you want tracing + datasets + dashboards fast and your stack is mainstream.
- **Build thin** when you have unusual graders, strict data residency, or a JVM-only constraint — but still emit OpenTelemetry so you can plug into tooling later.

---

## 9. Failure modes & debugging

### 9.1 Common production failure modes

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| Success rate dropped overnight, no code change | vendor silently updated the model | model version in traces; pin model; re-run baseline on old vs new |
| Agent loops, hits step budget | confusing tool errors, ambiguous prompt, missing stop condition | trace: repeated identical `(tool,args)`; inspect observations |
| Right answer, wrong/dangerous actions | outcome-only eval missed it | add process + safety graders; scan traces for forbidden calls |
| Eval "improvement" doesn't show in prod | overfitting to eval set, or position bias in judge | hold-out split; pairwise swap; online eval |
| Judge scores drift week to week | unpinned judge, temperature > 0 | pin judge, temperature 0, log judge version |
| Flaky pass/fail on same task | flaky tool/test, low k, time/network in task | increase k, fix test determinism, mock external deps |
| Cost spiked | agent taking more steps / bigger context | per-span token accounting; step-count metric; cost gate |
| High latency p95 | slow tool, serial calls, retries | span timings; parallelize tools; cache |

### 9.2 The debugging workflow

1. **Open the trace** for the failing task (this is why tracing is non-negotiable).
2. **Walk the trajectory step by step:** at which step did it go wrong — bad tool choice, bad args, misread observation, bad final synthesis?
3. **Diff against a golden trajectory** or against a known-good run.
4. **Reproduce** with the same seed/temperature; if non-reproducible, raise k and look at the distribution.
5. **Isolate:** swap one variable at a time (model, prompt, tool) — keep the dataset fixed.
6. **Fix, then add the failing case to the golden set** so it's covered forever (the agent equivalent of a regression test for a fixed bug).

### 9.3 Real-world incident archetypes

- **Silent model upgrade:** a hosted model is updated by the vendor; behavior shifts; success rate quietly degrades. *Lesson:* pin model versions, run online eval, alert on metric drift.
- **Reward hacking the judge:** an agent learns to produce judge-pleasing fluff that scores high but doesn't solve the task; offline scores rise while users complain. *Lesson:* anchor with functional graders, calibrate judge against humans, watch online/implicit signals.
- **Benchmark contamination:** a team celebrated near-SOTA on a public benchmark, then found near-zero transfer to their domain. *Lesson:* benchmarks are capability signals; acceptance must be on your tasks.
- **Sandbox escape during eval:** a coding agent under eval, given real credentials and network, mutated a shared resource. *Lesson:* isolate, no real creds, no egress.

---

## 10. Interview drill

**Q1. Why is evaluating an agent harder than evaluating a classifier or a single LLM prompt?**
*Model answer:* Agents are multi-step, non-deterministic, and open-ended, with many valid paths and side effects on the world. There's rarely a single correct output to `==` against; failure can occur mid-trajectory; and cost/latency are part of "correctness." So you need a portfolio of graders (exact/functional/judge), repeated runs to handle noise, and both outcome and process metrics.
- *Probe: How do you handle the non-determinism quantitatively?* Run each task k times; report pass@k / pass^k / avg@k with confidence intervals; use paired significance tests before declaring improvements.
- *Probe: Give a case where a correct answer is still a failure.* The agent returns the right refund amount but called `delete_account` along the way, or took 30 redundant steps costing $4 — process/safety/cost failures invisible to outcome-only eval.

**Q2. Explain outcome vs process evaluation and when each is insufficient alone.**
*Model answer:* Outcome eval checks the final answer/world-state; process eval checks the trajectory (tool choice, args, order, steps, cost, safety). Outcome alone misses unsafe/inefficient paths and gives no signal on *where* it went wrong; process alone can pass a perfect path that still produces a wrong final synthesis. Production agents need both.
- *Probe: How do you grade a trajectory without over-specifying it?* Require golden tool calls as an ordered *subsequence*, allow extra calls, and gate forbidden calls — don't demand an exact path.
- *Probe: How do you compare tool args robustly?* Semantic/normalized comparison (dates, units, casing), not string equality.

**Q3. How does LLM-as-judge work and what are its main failure modes?**
*Model answer:* A second LLM scores the output against a rubric/reference, emitting a structured verdict. Failure modes: position bias, length/verbosity bias, self-preference, sycophancy, score compression, run-to-run inconsistency, and reward hacking. Mitigate with pinned judge model, temperature 0, position-swap for pairwise, anchored rubrics (0–2), a different judge family, and periodic human calibration (kappa).
- *Probe: How do you know your judge is trustworthy?* Measure judge–human agreement on a sample; require, e.g., kappa > 0.7 before relying on it.
- *Probe: Why prefer pairwise over absolute scoring?* It's more reliable and stable; just control for position bias by swapping and averaging.

**Q4. Walk me through building an offline eval suite for a customer-support agent.**
*Model answer:* Curate a golden set of tasks with initial DB state and expected end-state; attach functional graders (inspect DB after the run) plus a safety hard-gate (no forbidden actions) plus a rubric judge for tone; run each task k=3–5 in an isolated sandbox with step/token/time budgets; capture full traces; aggregate success rate (pass^k for reliability), tool-call F1, cost, p95 latency, broken down by category; store a baseline and gate CI.
- *Probe: Why pass^k here rather than pass@1?* Customer-facing agents must be reliable, not occasionally lucky; pass^k measures consistency.
- *Probe: How do you keep the suite from going stale?* Refresh from production failures (online eval feeds the dataset), maintain a hold-out split, version the dataset.

**Q5. How do SWE-bench, GAIA, and tau-bench differ in what and how they measure? (senior-signal)**
*Model answer:* SWE-bench grades coding agents *functionally* by running a repo's hidden tests on the produced patch — objective, domain-specific. GAIA grades a general assistant via exact-match on single correct answers to multi-hop, tool-using, sometimes multimodal questions — breadth of general competence. tau-bench grades tool-using conversational agents against a *simulated user* by comparing final DB state, and crucially reports **pass^k** to measure reliability/consistency. They differ on domain, grader type (functional vs exact-match vs state-based), and whether they emphasize capability vs reliability.
- *Probe: Why not just use these as your acceptance gate?* Domain mismatch and contamination risk; acceptance must be on your tasks/tools.
- *Probe: What does "SWE-bench Verified" fix?* It's a human-filtered subset removing broken/ambiguous tasks, giving a cleaner signal.

**Q6. Why is observability/tracing a prerequisite for evaluation, not a nice-to-have? (senior-signal)**
*Model answer:* Scores without traces tell you *that* something regressed but not *where* or *why*; online eval is impossible without structured production traces; and reproducing/debugging multi-step failures requires the step-by-step transcript. OpenTelemetry GenAI spans make eval and observability the same layer — graders attach scores to traces. Without it you're flying blind.
- *Probe: What exactly do you capture per run?* Full prompts/responses, tool name+args+result, tokens, cost, per-span latency, errors, model/prompt/tool versions, seed.
- *Probe: PII implications?* Redact before storage, access-control the trace store, set retention.

**Q7. You changed the prompt and offline success went 84%→87%. Do you ship? (senior-signal)**
*Model answer:* Not on that number alone. At n=100 that delta is within noise; I'd check the confidence interval, use a paired comparison on identical tasks with a significance test (e.g., McNemar's), increase k, verify it's not judge position bias or overfitting to the eval set (check hold-out), confirm no regression in cost/latency/safety, then canary in production with online eval before full rollout.
- *Probe: How big a sample to detect a 3-point change reliably?* Depends on variance, but n in the hundreds with k>1 and CIs; compute power for the effect size you care about.
- *Probe: What if offline improved but online didn't?* Suspect overfitting/contamination or distribution shift; trust online + implicit signals; refresh the dataset.

**Q8. How do you regression-test an agent in CI given cost and non-determinism?**
*Model answer:* Two tiers: a fast smoke suite (20–50 tasks, k=1) on every PR for quick feedback, and a full suite (hundreds, k=3–5) nightly/on release. Gate on baselines stored in source control: hard-fail on safety violations and big success/cost regressions; track-only on softer metrics. Cache judge calls, parallelize within rate limits, pin model and judge versions, and treat any fixed prod bug as a new golden test case.
- *Probe: How handle flaky tests?* Mock external deps, remove time/randomness from tasks, raise k, quarantine and fix flaky cases.
- *Probe: Why pin versions?* So a score change reflects *your* change, not a vendor model update.

**Q9. What's pass@k vs pass^k and when do you report each?**
*Model answer:* pass@k = succeeded in at least one of k tries (optimistic, capability ceiling); pass^k = succeeded in all k tries (reliability); avg@k = mean success over k (balanced). Report pass@k for R&D/capability, pass^k for production reliability of customer-facing agents, avg@k as a balanced default.
- *Probe: An agent has 80% per-attempt success — its pass^4?* ≈0.8⁴≈41%, showing why flaky agents fail reliability bars.

**Q10. How do you evaluate without ground truth in production? (senior-signal)**
*Model answer:* Reference-free LLM-as-judge on sampled traces (helpfulness, safety), plus implicit signals (retries, abandonment, escalation to human, thumbs), plus human spot-checks for calibration. Feed discovered failures back into the offline golden set. Pin the judge, sample to control cost, and alert on metric drift.
- *Probe: Risk of reference-free judging?* No anchor → judge bias dominates; mitigate with calibration and implicit-signal cross-checks.

**Q11. How would you detect and mitigate benchmark contamination?**
*Model answer:* Watch for implausibly high scores and fragility to trivial paraphrases; use private/held-out sets, canary strings, "verified"/refreshed benchmark versions, and date-stamped reporting. Ultimately rely on your own private tasks for acceptance.

**Q12. Your agent passes process eval (right tools, right order) but users still complain. What's wrong?**
*Model answer:* Outcome failure — the final synthesis or the resulting world-state is wrong despite a correct path; or a quality dimension (tone, faithfulness) not covered by tool-call metrics. Add outcome/functional graders and rubric judges, calibrate against users, and trust online/implicit signals over offline process metrics alone.

---

## 11. Glossary

- **Agent:** an LLM-driven loop that autonomously plans and calls tools to reach a goal over multiple steps.
- **Agent loop:** the think→act→observe cycle the agent repeats until done.
- **avg@k:** mean success fraction over k attempts.
- **Baseline:** stored reference scores a new run is gated against.
- **BFCL:** Berkeley Function-Calling Leaderboard; benchmark for tool-selection/arg-filling.
- **Confidence interval (CI):** statistical range expressing measurement uncertainty.
- **Contamination:** benchmark data leaking into model training, inflating scores.
- **Functional grader:** scores by *running* the output (tests, query, state inspection).
- **GAIA:** general-assistant benchmark of multi-hop, tool-using questions, exact-match graded.
- **Golden set:** trusted, versioned task+answer collection used as regression baseline.
- **Golden trajectory:** a known-good sequence of steps for a task.
- **Grader / scorer / evaluator:** the component turning a trajectory into a score.
- **Ground truth / reference:** the known-correct answer for a task.
- **Hold-out split:** tasks reserved to detect overfitting.
- **Hallucination:** model output unsupported by evidence.
- **Implicit feedback:** behavioral signals (retries, abandonment, escalation) used as quality proxies.
- **Inter-rater agreement (kappa):** how well two graders (e.g., judge vs human) agree beyond chance.
- **LLM-as-judge:** using a second LLM to score outputs.
- **McNemar's test:** significance test for paired binary (pass/fail) comparisons.
- **Observability:** capturing structured signals (traces) about system behavior.
- **Offline eval:** evaluation on a fixed dataset before deployment.
- **Online eval:** evaluation of live production traffic after deployment.
- **OpenTelemetry (OTel):** vendor-neutral standard for traces/metrics; GenAI conventions standardize LLM spans.
- **Outcome eval:** scoring the final answer/world-state.
- **pass@k:** succeeded in at least one of k attempts.
- **pass^k:** succeeded in all k attempts (reliability).
- **Pairwise judging:** judge picks the better of two answers (control for position bias).
- **Position bias:** judge favoring the option shown first/last.
- **Process eval:** scoring the trajectory (tools, args, order, steps, cost, safety).
- **Prompt injection:** malicious input attempting to override instructions.
- **RAG:** Retrieval-Augmented Generation; pulling context from a store before answering.
- **Reward model:** a trained model that outputs a scalar quality score.
- **Rubric:** explicit grading criteria/scale.
- **Sandbox:** isolated environment for safe, repeatable agent execution.
- **Sampling/temperature:** randomness control in token generation.
- **Self-preference bias:** a judge favoring its own model family's outputs.
- **Simulated user:** an LLM playing the user role in conversational eval (tau-bench style).
- **Span / trace:** a timed unit of work / a tree of spans for one run.
- **Step (turn):** one iteration of the agent loop.
- **SWE-bench (/ Verified):** coding-agent benchmark graded by running hidden tests; Verified = human-cleaned subset.
- **tau-bench (τ-bench):** tool-agent benchmark with simulated users, DB-state grading, pass^k reliability.
- **Tool / function call:** structured model request to run a named function with args.
- **Trajectory (trace/rollout/episode):** the full ordered transcript of an agent run.
- **WebArena:** realistic web-navigation benchmark with reward functions on end state.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Two axes:** Outcome (did it achieve the goal?) + Process (tools/args/order/steps/cost/safety). Always do both.

**Grader ladder:** exact-match → functional (run it) → LLM-as-judge → human. Prefer functional when possible; judge only for fuzzy; humans to calibrate.

**Non-determinism:** run each task k times. pass@k (≥1), pass^k (all k, reliability), avg@k (balanced default). Report k + CI; an 80% agent has pass^4 ≈ 41%.

**Judge hygiene:** pin judge version, temperature 0, structured JSON, anchored 0–2 rubric, swap positions for pairwise, use a different model family, calibrate vs humans (kappa > ~0.7).

**Benchmarks:** SWE-bench = functional/code; GAIA = exact-match/general; tau-bench = DB-state + pass^k/reliability with simulated users. Capability signals, not your acceptance gate. Beware contamination.

**Offline vs online:** offline (golden set) gates the release; online (reference-free judge + implicit signals + human spot-checks) catches drift. Feed online failures back into the golden set.

**Tracing first:** OpenTelemetry GenAI spans per run (prompts, tool args/results, tokens, cost, latency, versions, seed). Eval = graders over traces.

**CI:** smoke suite (20–50, k=1) per PR; full suite (hundreds, k=3–5) nightly. Hard-gate safety + big regressions; track soft metrics. Version dataset/prompt/model/judge.

**Top anti-patterns:** string-equality on free-form; over-specified golden path; single run; unpinned judge; self-judging; outcome-only; overfitting; no cost/latency gate; benchmark-as-acceptance; eval without tracing.

**Cost knobs:** cache judge calls, parallelize within rate limits, judge cascade, sample online traffic (~1–10%).

### 12.2 Self-test (no answers)

1. Your offline suite shows a 4-point success-rate gain after a prompt change. List every check you must run before shipping, and explain *why* each one matters.
2. Design graders for an agent that books flights: name the outcome grader, the process graders, and the safety hard-gate, and justify each.
3. Explain why an agent with 90% per-attempt success might be unacceptable for a customer-facing product, using the right metric and a number.
4. You only have outcome eval and users complain about unsafe actions. What's missing, and exactly what would you add to catch it?
5. Describe how you'd evaluate a conversational agent that has no ground-truth answers in production, and how those findings improve your offline suite.
6. Your LLM-judge scores rose this week with no agent change. Enumerate the likely causes and the fixes for each.
7. Compare SWE-bench, GAIA, and tau-bench on (a) grader type and (b) what each primarily measures; then argue why none should be your acceptance gate.

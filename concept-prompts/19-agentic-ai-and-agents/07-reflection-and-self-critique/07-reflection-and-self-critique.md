# Reflection & Self-Critique

*An engineering handbook chapter on agentic AI patterns where an agent reviews, critiques, and revises its own work.*

---

## 1. Overview & where it fits

**Reflection** is an agentic design pattern in which a language-model-driven agent does not accept its first output as final. Instead, it **examines that output, critiques it against some standard, and produces a revised version** — possibly repeating this loop several times. The pattern borrows its name and intuition directly from human practice: a writer drafts, re-reads, finds weaknesses, and edits; a programmer writes code, runs it, sees a stack trace, and fixes the bug.

**The problem it solves.** A single forward pass of a large language model (LLM) is a *one-shot* generation. The model commits to each token greedily-ish, left to right, with no built-in opportunity to step back and ask "is this actually correct/complete/well-structured?" Many failure modes of LLMs — hallucinated APIs, off-by-one logic, missed requirements, broken JSON, inconsistent tone — are exactly the kinds of errors a human *would catch on a second read* but the model never gets a chance to. Reflection manufactures that second read.

> **What is an LLM?** A large language model is a neural network (almost always a *Transformer* — an architecture that processes sequences of tokens using a mechanism called *self-attention*) trained to predict the next token of text given the preceding tokens. "Token" = a chunk of text, roughly 3–4 characters or about ¾ of a word in English. When we say "the model generates," we mean it samples tokens one at a time. ([context for the rest of the doc])

> **What is an "agent" here?** In the agentic-AI sense, an agent is an LLM placed inside a control loop that lets it *take actions* (call tools, run code, query a database, search the web), observe the results, and decide what to do next — rather than just emitting one block of text. Reflection is one of the loop structures you can build.

**When you reach for reflection.** You add reflection when (a) quality matters more than latency/cost, (b) the task has a *checkable* notion of "better" (tests pass, JSON validates, a rubric is satisfied, a spec is met), and (c) the model's first draft is *good but unreliable* — close enough that a critique-and-revise step can plausibly fix it. Reflection is wasted when the first draft is already near-perfect (you just burn tokens) or when the task is so hard the model cannot critique itself usefully (it cannot find errors it could not avoid in the first place).

**The one-paragraph mental model.** Think of reflection as a feedback control loop wrapped around a generator. There is a **generator** (produces a candidate), a **critic** (judges the candidate and emits feedback), and a **decision rule** (stop, or feed the feedback back to the generator for another attempt). The single most important design decision is *where the critic's signal comes from*: if it comes only from the same LLM judging its own text ("self-judgment"), the loop is cheap but unreliable; if it comes from an **external, ground-truth-bearing tool** (a compiler, a unit-test runner, a JSON-schema validator, a linter, a type checker), the loop becomes dramatically more reliable because the critique is grounded in reality rather than the model's possibly-wrong opinion of itself. Everything else in this chapter is detail on top of that core idea.

**Where it fits among the canonical agentic patterns.** The agentic-AI literature (notably Andrew Ng's 2024 "agentic design patterns" framing) identifies four recurring patterns: **Reflection**, **Tool Use**, **Planning**, and **Multi-Agent Collaboration**. Reflection is frequently the cheapest, highest-ROI pattern to add to an existing pipeline, because it is a *wrapper* — you do not need to redesign your prompt or your tools, you just add a critique-and-revise loop around what you already have. It composes naturally with the other three: you reflect on a *plan* before executing it; you use *tools* as the critic; you have *multiple agents* play generator and critic.

---

## 2. Foundations from first principles

Let us build the concept from zero, defining each term as it appears.

### 2.1 The basic loop

The minimal reflection system has three roles. They can be three separate LLM calls, two calls, or even fused into one — but conceptually:

1. **Generator** (sometimes "actor"): given a task, produce a candidate answer.
2. **Critic** (sometimes "evaluator" or "judge"): given the task *and* the candidate, produce a *critique* — an assessment plus, ideally, specific actionable feedback.
3. **Reviser**: given the task, the previous candidate, and the critique, produce an improved candidate. (Often the reviser is just the generator called again with extra context.)

Wrap these in a loop with a **stopping criterion**, and you have reflection:

```
candidate = generate(task)
for i in 1..MAX_ITERS:
    critique = criticize(task, candidate)
    if satisfies(critique):        # stopping criterion
        break
    candidate = revise(task, candidate, critique)
return candidate
```

> **What is a "stopping criterion"?** A rule that decides when to exit the loop. Without one, the agent could loop forever (or until you run out of money). Common criteria: the critic says "no issues found," a hard maximum number of iterations is reached, the candidate stops changing between iterations (*convergence*), or an external check passes (e.g., all tests green). We treat stopping criteria in depth in §3.5 and §7.

### 2.2 Why a second pass can help at all

It is reasonable to be skeptical: if the model could produce the right answer, why didn't it the first time? Three mechanisms explain why reflection adds value:

1. **Generation vs. verification asymmetry.** For many tasks, *checking* a candidate is easier than *producing* one from scratch — the same reason it is easier to grade an essay than write it, or to spot a bug than to write bug-free code. The critic operates in the easier (verification) regime, so it can flag problems the generator missed. This is the single deepest justification for the pattern.

2. **Fresh context / role separation.** When you re-prompt the model in the *role of a critic* ("You are a strict senior reviewer; find every flaw"), you change its behavior. The first pass optimizes for "produce something"; the critic pass optimizes for "find what's wrong." Different objectives surface different information.

3. **Test-time compute.** Reflection spends more inference compute on a single problem. Empirically, across many tasks, spending more compute at inference time (more tokens of reasoning, more attempts, more checking) raises quality — this is the same family of ideas as chain-of-thought and best-of-N sampling. Reflection is one structured way to spend that compute.

> **What is chain-of-thought (CoT)?** A prompting technique where you ask the model to "think step by step" and write out intermediate reasoning before the final answer. It improves performance on reasoning tasks because the generated reasoning tokens act as a scratchpad. Reflection is *related but distinct*: CoT is reasoning *within one pass*; reflection is critique *across passes*.

> **What is best-of-N (a.k.a. sampling + reranking)?** Generate N independent candidates (by sampling at nonzero temperature), then pick the best one by some scoring function. Reflection differs in that it *iteratively improves a single line* of candidates using feedback, rather than generating many independent ones. They can be combined (reflect on each of N candidates; or generate N, critique, keep the best, revise).

> **What is "temperature"?** A sampling parameter (typically 0.0–2.0). At temperature 0 the model is (nearly) deterministic, always taking the most probable token. Higher temperature increases randomness/diversity. Reflection loops often run the critic at low temperature (you want consistent, focused judgment) and may run the generator slightly higher (you want it to actually try something *different* on revision, not regurgitate the same flawed answer).

### 2.3 The critical taxonomy: where does the critique come from?

This is the axis that matters most. Critiques fall on a spectrum from "ungrounded self-opinion" to "hard ground truth":

| Critic source | What it is | Reliability | Cost | Typical use |
|---|---|---|---|---|
| **Self-judgment (same model)** | The generating LLM critiques its own text | Low–medium; subject to the model's blind spots | 1 extra LLM call | Subjective tasks (tone, clarity), quick polish |
| **LLM-as-judge (separate model/prompt)** | A different model or a fresh critic prompt scores the output, often against a rubric | Medium; better than self-judgment, still ungrounded | 1+ LLM call | Rubric-based quality, content moderation, ranking |
| **Tool-grounded critique** | A non-LLM tool (compiler, test runner, validator, linter, type checker) judges the output | High; grounded in deterministic reality | Cheap (no LLM tokens for the check itself) | Code, structured data, anything machine-checkable |
| **Human-in-the-loop** | A person reviews and gives feedback | Highest, but slow & expensive | Human time | High-stakes, ambiguous, or final-gate review |

The handbook's central practical thesis: **prefer tool-grounded critique whenever the task admits it.** A unit-test failure is a *fact*. An LLM saying "this code looks correct" is an *opinion that is frequently wrong*. We will return to this repeatedly.

> **What is a "linter"?** A static-analysis tool that scans source code for stylistic and likely-bug-causing patterns without running it (e.g., ESLint for JavaScript, Checkstyle/SpotBugs/PMD/Error Prone for Java). "Static" = analyzed from the text alone, without execution. A linter is a cheap, deterministic critic.

> **What is a "type checker"?** A tool that verifies a program's types are consistent (the Java compiler `javac` does this for Java; `mypy` does it for Python; `tsc` for TypeScript). Type errors are ground-truth defects — an excellent reflection signal.

> **What is a "schema validator"?** A tool that checks whether a data document (JSON, XML, Protobuf, Avro) conforms to a declared schema (e.g., JSON Schema, an OpenAPI spec). If an agent must emit structured output, a schema validator is the natural critic.

### 2.4 Single-attempt reflection vs. cross-attempt learning

Two fundamentally different "depths" of reflection:

- **Within-episode (self-refine style):** the agent refines a single output over a few iterations and then is done. State is thrown away. This is the common case.
- **Across-episode (Reflexion style):** the agent attempts a *task*, fails, writes a *natural-language lesson* about why it failed ("I forgot to handle the empty-list case"), stores that lesson in a memory, and uses it to do better on the *next attempt of the same or a similar task*. The reflection becomes durable learning rather than a one-off edit.

We give both their own treatment in §3.3 and §3.4.

> **What is "episodic memory" in this context?** A store (a list in RAM, a vector database, a file) where the agent keeps records of past attempts and the lessons learned from them, so it can condition future behavior on past experience. "Episodic" because each entry corresponds to one episode/attempt. (A *vector database* — e.g., Pinecone, Weaviate, pgvector, Milvus — stores text as numeric *embedding* vectors so you can retrieve semantically similar memories by nearest-neighbor search. An *embedding* is a fixed-length numeric vector that represents the meaning of a piece of text.)

---

## 3. How it works internally

This is the heart of the chapter. We trace the control flow, data flow, lifecycle, and state machine of reflection, then walk each major variant step by step.

### 3.1 The generic state machine

Model a reflection agent as a finite state machine (FSM):

```
        ┌─────────┐
        │  START  │
        └────┬────┘
             │ task in
             ▼
      ┌────────────┐
      │  GENERATE  │ ◄────────────────┐
      └─────┬──────┘                  │
            │ candidate                │ revise(feedback)
            ▼                          │
      ┌────────────┐                   │
      │  CRITIQUE  │                   │
      └─────┬──────┘                   │
            │ verdict + feedback        │
            ▼                          │
      ┌────────────┐    not-ok &       │
      │   DECIDE   │──── budget left ──┘
      └─────┬──────┘
            │ ok  OR  budget exhausted
            ▼
      ┌────────────┐
      │   RETURN   │
      └────────────┘
```

States and their responsibilities:

- **GENERATE**: produce a candidate from `(task, [prior candidate], [feedback], [memory])`. On the first entry only the task is present; on subsequent entries the prior candidate and feedback drive a *revision* rather than a fresh generation.
- **CRITIQUE**: produce a *verdict* (pass/fail or a score) plus *feedback* (what specifically is wrong and ideally how to fix it). The critique source is the design choice from §2.3.
- **DECIDE**: apply the stopping criterion. Three exits: (1) verdict is "good enough" → RETURN; (2) verdict is "not good enough" and budget remains → revise; (3) budget exhausted → RETURN the best candidate so far (often with a flag that it did not fully pass).
- **RETURN**: emit the final candidate plus metadata (iterations used, final verdict, whether it converged).

**Crucial implementation detail — "best so far":** A naive loop returns the *last* candidate. But revision can make things *worse* (the model "fixes" a non-bug and breaks a real feature). Robust loops track the **best candidate seen so far** by score and return that, not necessarily the final one. This requires a scalar score from the critic, not just pass/fail.

### 3.2 Data flow & context management

Each iteration accumulates context. Naively you would append every prior candidate and every critique to the prompt, but this *blows up the context window* (the maximum number of tokens the model can attend to in one call — e.g., 200K tokens for some Claude models, 128K for many GPT-4-class models). Strategies:

- **Full history**: include all prior attempts + critiques. Most informative, most expensive, risks context overflow and "lost in the middle" (models attend less reliably to information buried in the middle of a long context).
- **Last-k**: include only the last *k* attempts/critiques (often k=1 or 2). Common default.
- **Summarized history**: keep a running, model-written summary of "what I've learned across attempts" (this is the Reflexion memory). Compact and durable.
- **Diff-only**: feed back only the *delta* the critic wants changed (e.g., "lines 40–52 throw on empty input"). Cheapest, but loses global context.

> **What is the "context window"?** The fixed maximum number of tokens an LLM can take as input (prompt) plus output in a single call. Everything the model "knows" for that call must fit inside it. Exceeding it causes truncation or an API error. Reflection loops are context-hungry, so context management is a first-class concern.

> **What is "lost in the middle"?** An empirically observed phenomenon (Liu et al., 2023) where models retrieve information placed at the *start* or *end* of a long context more reliably than information in the *middle*. Practical implication: put the current candidate and the most relevant critique near the end of the prompt.

### 3.3 Variant A — Self-Refine (within-episode iterative refinement)

**Self-Refine** (Madaan et al., 2023, "Self-Refine: Iterative Refinement with Self-Feedback") is the canonical *self-judgment* reflection method. One model plays all three roles via three prompts.

Step-by-step internal workflow:

1. **Initial generation.** Prompt the model with the task → candidate `y₀`.
2. **Feedback.** Re-prompt the *same* model: "Here is the task and your output `y₀`. Critique it: list specific, actionable problems." → feedback `f₀`. Few-shot examples of *good critiques* in the prompt strongly improve this step.
3. **Refine.** Re-prompt: "Here is the task, your output `y₀`, and the feedback `f₀`. Produce an improved output." → `y₁`.
4. **Iterate** steps 2–3 until the feedback says "no further improvements needed," or a max-iteration cap is hit (the paper used a small cap, e.g. up to ~4 iterations depending on task).
5. **Return** the final (or best) candidate.

Key findings from the original work and the broader literature:
- Self-Refine improved outputs across tasks like code optimization, sentiment-reversal rewriting, math, and dialogue response generation, *without any additional training* — purely prompting.
- The *quality of the feedback prompt matters more than anything*. Vague "make it better" feedback barely helps; specific, itemized, actionable feedback drives most of the gain.
- **The failure mode of self-judgment:** the model cannot reliably critique what it cannot do. On hard reasoning/math, later research (e.g., Huang et al., 2023, "Large Language Models Cannot Self-Correct Reasoning Yet") found that *unaided* self-correction can *degrade* accuracy — the model "corrects" right answers into wrong ones because it has no ground-truth signal. This is the empirical basis for preferring tool-grounded critique.

### 3.4 Variant B — Reflexion (cross-attempt verbal reinforcement learning)

**Reflexion** (Shinn et al., 2023, "Reflexion: Language Agents with Verbal Reinforcement Learning") extends reflection across *attempts* and grounds it in an *environment signal*. It is designed for agentic tasks where the agent acts in an environment (a game, a coding benchmark, a web task) and gets a reward/feedback signal.

Architecture (three components):
- **Actor**: an LLM that produces actions/text given the task and its memory. (Often itself a ReAct-style agent — see glossary.)
- **Evaluator**: scores the trajectory. Crucially this can be an *environment signal* (tests passed? game won? did the SQL query return the right rows?) — i.e., tool-grounded — not just self-judgment.
- **Self-Reflection model**: an LLM that, given the trajectory and the evaluator's score, writes a *verbal lesson* ("I should have checked the file existed before opening it"). This lesson is stored in an **episodic memory buffer**.

Step-by-step internal workflow:

1. **Attempt t**: Actor runs the task using ReAct/CoT, producing a trajectory (sequence of thoughts, actions, observations).
2. **Evaluate**: Evaluator scores the trajectory (e.g., environment returns success/fail; for code, run the tests).
3. If success → done.
4. If fail → **Reflect**: the Self-Reflection LLM reads (trajectory + score) and writes a concise natural-language lesson.
5. **Store** the lesson in episodic memory (a short list, capped to the last *N* lessons to fit context).
6. **Attempt t+1**: Actor retries the *same task*, now with the accumulated lessons prepended to its context. Go to 2.
7. Stop on success or after a max number of trials.

Why it works: the lesson is a *compressed, semantic gradient*. Rather than updating weights (which would require true reinforcement learning and training infrastructure), Reflexion updates *natural-language memory*. Hence "verbal reinforcement learning." It produced strong gains on benchmarks like HumanEval (code), ALFWorld (text games), and HotpotQA (multi-hop QA).

> **What is "reinforcement learning" (RL)?** A training paradigm where an agent learns by taking actions and receiving rewards, adjusting its policy to maximize cumulative reward. Classic RL updates model *weights*. Reflexion's insight is to get RL-like improvement by updating *text memory* instead of weights — much cheaper, no training run, works on a frozen model.

> **What is a "trajectory"?** The full sequence of an agent's steps for one episode: thought₁, action₁, observation₁, thought₂, … This is what gets evaluated and reflected upon.

> **What is "ReAct"?** A prompting/agent pattern (Yao et al., 2022) that interleaves *Reasoning* ("Thought:") and *Acting* ("Action:") steps, with tool *Observations* fed back in. Reflexion's Actor is typically a ReAct agent.

### 3.5 Variant C — Actor–Critic / Generator–Critic (separated roles)

Here generator and critic are *deliberately different* — different system prompts, often different models, sometimes different temperatures. Separation reduces the "I wrote it so it must be good" bias.

Internal flow:
1. **Generator** (e.g., a capable but cheaper model) produces candidate.
2. **Critic** (e.g., a stronger model, or one prompted with a strict rubric) evaluates against explicit criteria and returns a structured verdict (e.g., JSON: `{score, issues:[...], pass:bool}`).
3. **Orchestrator** decides: pass → return; else feed critic's `issues` back to generator.
4. Loop with a budget.

This is the natural home for **LLM-as-judge**. The critic is given a *rubric* — an explicit list of criteria with scoring guidance — and asked to evaluate each. Rubrics dramatically improve judge reliability and make scores auditable.

> **What is "LLM-as-judge"?** Using an LLM to evaluate text (rank, score, or pass/fail) against criteria, in place of a human grader. Reliable for coarse quality and ranking, *unreliable* for fine-grained correctness and prone to biases: *position bias* (prefers the first option presented), *verbosity bias* (prefers longer answers), *self-preference bias* (a model rates its own outputs higher), and sycophancy. Mitigate with: randomized option order, explicit rubrics, requiring justifications before the score, and using a *different* model as judge than as generator.

### 3.6 Variant D — Tool-grounded reflection (the reliable one)

The critic is a deterministic tool. The loop:

1. Generator produces an artifact (code, SQL, JSON, a config, a regex).
2. **Execute the critic tool**: run the compiler / unit tests / schema validator / linter / SQL against a sandbox DB / the regex against test strings.
3. Parse the tool's output into structured feedback (error messages, failing test names, stack traces, validator errors).
4. Pass → return. Fail → feed the *exact tool output* back to the generator: "Here is your code and the test failures; fix them."
5. Loop with a budget.

Why this is the gold standard: the feedback is *ground truth*, *specific* (line numbers, exact expected-vs-actual), and *free of LLM bias*. A test failure cannot be argued with. The model's job shrinks from "be correct" to "make this specific error go away," which is far more tractable. The worked example in §5.1 implements exactly this.

The general principle: **whenever the output is machine-verifiable, make the machine the critic.** Reserve LLM-as-judge for the genuinely subjective residue (clarity, tone, helpfulness) that no tool can check.

### 3.7 Lifecycle summary (one trace)

A concrete trace of a tool-grounded code agent, end to end:

```
t0  task = "write isPalindrome(String) with tests passing"
t1  GENERATE → code_v0
t2  CRITIQUE = run javac + JUnit
       → javac OK; 2/5 tests fail: emptyString, nullInput (NPE)
t3  DECIDE → fail, budget 5/5 left → revise
t4  REVISE(code_v0, "NPE on null; empty should be true") → code_v1
t5  CRITIQUE = run javac + JUnit → 5/5 pass
t6  DECIDE → pass → RETURN code_v1, iters=2, converged=true
```

Note: the critique at t2/t5 cost *zero LLM tokens* (it is just running tests); only GENERATE and REVISE cost tokens. This is part of why tool-grounded reflection is cost-efficient relative to LLM-as-judge.

---

## 4. The complete toolkit

Reflection is a *pattern*, not a single library, so the "toolkit" spans (a) framework primitives that implement the loop, (b) the *critic tools* you plug in, and (c) the knobs that govern the loop.

### 4.1 Frameworks & primitives that implement reflection loops

| Tool / framework | Language | What it gives you for reflection | Notes |
|---|---|---|---|
| **LangGraph** | Python (JS port exists) | Graph/state-machine orchestration; you build explicit GENERATE→CRITIQUE→DECIDE nodes with conditional edges (loops) | The most natural fit for reflection because loops/branches are first-class. Has documented "Reflection" and "Reflexion" example graphs. |
| **LangChain** | Python/JS | Chains, output parsers, retry/`RetryOutputParser`, structured-output validation | Lower-level than LangGraph for loops; good for the validate-and-retry sub-pattern. |
| **AutoGen / AG2** | Python | Multi-agent conversations; a "critic" agent and an "assistant" agent exchange messages until a termination condition | Natural for actor–critic; built-in `is_termination_msg`. |
| **CrewAI** | Python | Role-based agents with task validation; supports a reviewer role | Higher-level/opinionated. |
| **OpenAI Assistants / Agents SDK; Anthropic tool use** | Any | Native tool-calling loop; you implement the critic as a tool the model can call | You own the loop logic; very flexible. |
| **DSPy** | Python | Programmatic prompt optimization; `dspy.Refine` / assertions (`dspy.Assert`/`Suggest`) retry on a constraint failure | Lets you *declare* constraints; the framework retries to satisfy them — reflection as assertions. |
| **Guidance / Outlines / Instructor / Pydantic-AI** | Python | Constrained / validated structured generation; retry on schema-validation failure | The "validator-as-critic" sub-pattern for JSON. |
| **Spring AI / LangChain4j** | **Java/JVM** | Tool/function calling, structured output converters, advisors; you compose the loop in Java | The native choice for the JVM-backend reader. `BeanOutputConverter` + Bean Validation = validator-as-critic. |

> **What is LangGraph?** A library for building stateful, multi-step LLM applications as *graphs*: nodes are functions/LLM calls, edges define control flow, and conditional edges enable loops and branching. It is the cleanest open-source way to express a reflection state machine because the loop is explicit and inspectable.

> **What is Spring AI / LangChain4j?** The two leading JVM frameworks for building LLM apps. *Spring AI* integrates LLM calls, tool calling, RAG, and structured output into the Spring ecosystem. *LangChain4j* is a Java port of LangChain's ideas. Both let you implement reflection loops in idiomatic Java with strong typing.

### 4.2 Critic tools by artifact type (the most important table)

| Artifact the agent produces | Ground-truth critic tool(s) | What the feedback looks like |
|---|---|---|
| **Java code** | `javac` (compile), JUnit/TestNG (tests), SpotBugs/PMD/Error Prone (lint), Checkstyle (style), JaCoCo (coverage) | Compiler errors, failing test names + assertion diffs, bug-pattern warnings |
| **Python code** | `python -c`/`pytest`, `mypy` (types), `ruff`/`flake8` (lint), `bandit` (security) | Tracebacks, failing tests, type errors |
| **SQL** | Run against a sandbox DB or `EXPLAIN`; SQLFluff (lint); compare result set to expected | Syntax errors, wrong row counts, plan warnings |
| **JSON / structured output** | JSON Schema validator, Pydantic/Jackson + Bean Validation, OpenAPI validator | "field `age` must be ≥ 0", "missing required `id`" |
| **Math** | Symbolic checker (SymPy), a calculator tool, or a verifier model | "expected 42, got 41" |
| **Regex** | Run against labeled positive/negative test strings | "matched a string that should not match" |
| **Config (YAML/HCL/K8s)** | `kubeval`/`kubeconform`, `terraform validate`, `yamllint`, OPA/Conftest policies | Schema/policy violations |
| **Natural-language claims** | RAG retrieval + citation check; a fact-checking tool; NLI model | "claim not supported by any retrieved source" |
| **Subjective text (tone, clarity)** | *No tool exists* → LLM-as-judge with a rubric (last resort) | Rubric scores per criterion |

> **What is "RAG"?** Retrieval-Augmented Generation — fetch relevant documents (from a search index or vector DB) and put them in the prompt so the model answers *grounded in real sources*. As a critic, RAG lets you check whether each claim the agent made is supported by a retrieved source (citation verification), turning a hallucination problem into a checkable one.

> **What is "NLI"?** Natural Language Inference — a model that judges whether a hypothesis is *entailed by*, *contradicts*, or is *neutral to* a premise. Useful as a critic to check whether a generated claim is entailed by a source.

### 4.3 Loop-governing parameters (knobs & defaults)

| Knob | Purpose | Typical value / default | Notes |
|---|---|---|---|
| `max_iterations` | Hard cap on revise loops | 2–4 (subjective), up to ~6–10 (tool-grounded code) | The single most important safety knob. Most gains come in the first 1–2 iterations. |
| `pass_threshold` | Score at which to stop | task-specific | Only for scalar-scored critics. |
| `convergence_check` | Stop if candidate unchanged | on | Prevents loops where the model keeps re-emitting the same thing. |
| `generator_temperature` | Diversity of revisions | 0.3–0.7 | Slightly >0 so revisions actually differ. |
| `critic_temperature` | Consistency of judgment | 0.0–0.2 | Low for stable, repeatable critique. |
| `history_window (k)` | How many prior attempts in context | 1–2 | Controls context cost. |
| `time_budget` / `token_budget` | Wall-clock or token cap | task-specific | Belt-and-suspenders with `max_iterations`. |
| `return_best_vs_last` | Which candidate to return | best (if scored) | Avoids returning a regressed revision. |
| `min_improvement_delta` | Stop if score barely improves | e.g., < 2% gain | Avoids burning iterations for marginal gains. |

There are no universal "vendor defaults" for these — they are *your* loop's parameters, set in your orchestration code. Frameworks like LangGraph leave them to you; the values above are community rules of thumb, not specifications.

### 4.4 Java/JVM-specific building blocks

| Building block | Library | Role in reflection |
|---|---|---|
| `ChatClient` / `ChatModel` | Spring AI | The generator and critic LLM calls |
| `BeanOutputConverter<T>` | Spring AI | Parse model output into a typed Java bean; parse failure = critic signal |
| Bean Validation (`jakarta.validation`, Hibernate Validator) | Jakarta | Declarative constraints (`@NotNull`, `@Min`) → validator-as-critic |
| `ToolCallback` / `@Tool` | Spring AI / LangChain4j | Expose the test runner / validator as a callable tool |
| `InProcessJUnitLauncher` (JUnit Platform Launcher API) | JUnit 5 | Run generated tests in-process and capture results programmatically |
| `JavaCompiler` (`javax.tools.ToolProvider.getSystemJavaCompiler()`) | JDK | Compile generated Java in-memory; collect `Diagnostic`s as feedback |
| `Resilience4j` / `Failsafe` | — | Implement the retry/backoff/budget around the loop |
| `Micrometer` + OpenTelemetry | — | Metrics & tracing of iterations (see §6) |

> **What is the JUnit Platform Launcher API?** A programmatic entry point (since JUnit 5) to *discover and run tests from your own code* and receive results via a `TestExecutionListener`, rather than via a build tool. This is exactly what a code-reflection agent needs: run the generated tests in-process and read structured pass/fail data back.

> **What is `javax.tools.JavaCompiler`?** The JDK ships a programmatic compiler API. You can compile source held in memory (`SimpleJavaFileObject`/`JavaFileManager`) and collect compilation `Diagnostic` objects (errors/warnings with line numbers). A reflection agent uses these diagnostics as the critic feedback.

---

## 5. Code examples by use case

Idiomatic, explained, copy-adaptable. We span: (1) tool-grounded code agent in Java, (2) self-refine for prose, (3) JSON validator-as-critic, (4) actor–critic with LLM-as-judge + rubric, (5) Reflexion-style cross-attempt memory, (6) SQL agent against a sandbox DB.

### 5.1 Tool-grounded code-writing agent (Java, JDK compiler + JUnit as critic)

This is the flagship example: an agent that writes Java, *compiles and tests it*, and feeds failures back to itself until green or budget exhausted. The critic is the JDK compiler and JUnit — **ground truth, zero LLM tokens for the check.**

```java
// Pseudocode-leaning but compiles with Spring AI's ChatClient + JDK compiler API.
// Dependencies: spring-ai-openai (or anthropic) starter, JUnit 5 platform-launcher.

public final class CodeReflectionAgent {

    private final ChatClient llm;            // your configured generator model
    private final int maxIterations;         // hard budget, e.g. 6

    public CodeReflectionAgent(ChatClient llm, int maxIterations) {
        this.llm = llm;
        this.maxIterations = maxIterations;
    }

    /** Returns the best source that compiled & passed, or the last attempt + the failures. */
    public Result solve(String taskSpec, String testSource) {
        String code = generateInitial(taskSpec, testSource);   // y0

        Result best = null;
        for (int iter = 1; iter <= maxIterations; iter++) {
            // --- CRITIQUE: compile, then run tests. Both are ground-truth tools. ---
            CompileResult compile = JdkCompiler.compile(code, testSource);
            if (!compile.success()) {
                // Feed the EXACT compiler diagnostics back — line numbers and all.
                code = revise(taskSpec, code, "COMPILE ERRORS:\n" + compile.diagnostics());
                continue;
            }
            TestResult tests = JUnitInProcess.run(compile.classpath()); // structured results
            if (tests.allPassed()) {
                return new Result(code, iter, true, "");                // converged: stop early
            }
            // Track best-so-far by pass count (a revision can regress!).
            if (best == null || tests.passedCount() > best.passedCount()) {
                best = new Result(code, iter, false, tests.failureReport());
            }
            // --- REVISE: the feedback is concrete & actionable (assertion diffs, stack traces). ---
            code = revise(taskSpec, code, "TEST FAILURES:\n" + tests.failureReport());
        }
        // Budget exhausted: return the best partial, NOT necessarily the last (which may be worse).
        return best != null ? best : new Result(code, maxIterations, false, "no passing version");
    }

    private String generateInitial(String task, String tests) {
        return llm.prompt()
            .system("You are a senior Java engineer. Output ONLY a complete compilable class.")
            .user(u -> u.text("Task:\n{task}\n\nThese JUnit tests must pass:\n{tests}")
                        .param("task", task).param("tests", tests))
            .call().content();
    }

    private String revise(String task, String prevCode, String feedback) {
        // Note: we DO NOT ask the model to self-judge. We hand it ground-truth feedback.
        // Temperature slightly > 0 so the revision actually differs from the prior attempt.
        return llm.prompt()
            .options(o -> o.temperature(0.4))
            .system("You are fixing Java code to make the given tests pass. Output ONLY the full class.")
            .user(u -> u.text("Task:\n{task}\n\nYour previous code:\n{code}\n\n" +
                              "Ground-truth feedback from compiler/tests:\n{fb}\n\n" +
                              "Return a corrected full class.")
                        .param("task", task).param("code", prevCode).param("fb", feedback))
            .call().content();
    }

    public record Result(String code, int iterations, boolean passed, String failureReport) {
        int passedCount() { /* parse from report or carry as field */ return passed ? Integer.MAX_VALUE : 0; }
    }
}
```

```java
/** Compile generated source in-memory using the JDK compiler API. */
final class JdkCompiler {
    static CompileResult compile(String classSource, String testSource) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler(); // requires a JDK, not just JRE
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        // ... set up an in-memory JavaFileManager holding classSource + testSource,
        //     call compiler.getTask(...).call(), collect diags. (omitted for brevity)
        // Return success flag + human-readable diagnostics (line:col: message) for feedback.
        return new CompileResult(/*success*/ true, /*diagnostics*/ "", /*classpath*/ null);
    }
    record CompileResult(boolean success, String diagnostics, Object classpath) {}
}
```

Why this design is robust:
- **Ground-truth critic.** Compiler diagnostics and JUnit assertion diffs are facts; the model fixes a *specific* error, not a vague "improve."
- **Best-so-far tracking** guards against a revision regressing.
- **Early stop on green** saves tokens — the moment tests pass, we exit.
- **Budget cap** bounds cost and prevents infinite loops.
- **Sandboxing caveat (critical):** running model-written code is a security risk. In production you must execute it in a *sandbox* (a separate JVM with a restrictive policy, a container with no network and read-only FS, a `seccomp`/gVisor jail, or a disposable microVM like Firecracker). Never run untrusted generated code in your main process. (See §6.)

> **What is a "sandbox"?** An isolated execution environment with restricted permissions (no network, limited filesystem, CPU/memory/time limits) so that running untrusted code cannot harm the host or exfiltrate data. Essential whenever an agent executes code it generated.

### 5.2 Self-Refine for prose (LLM self-judgment, three prompts)

For subjective text where no tool exists, self-judgment is the only option. Make the feedback prompt strict and itemized.

```python
def self_refine(task: str, max_iters: int = 3) -> str:
    candidate = llm(f"Task: {task}\nWrite the best response you can.")
    for _ in range(max_iters):
        feedback = llm(
            "You are a ruthless senior editor. Given the TASK and DRAFT, "
            "list SPECIFIC, ACTIONABLE problems as a numbered list. "
            "If the draft is genuinely excellent, reply exactly: NO_ISSUES.\n"
            f"TASK:\n{task}\n\nDRAFT:\n{candidate}"
        )
        if "NO_ISSUES" in feedback:          # stopping criterion #1: critic is satisfied
            break
        revised = llm(
            "Rewrite the DRAFT to fix every issue in the FEEDBACK. "
            "Keep what already works.\n"
            f"TASK:\n{task}\n\nDRAFT:\n{candidate}\n\nFEEDBACK:\n{feedback}"
        )
        if revised.strip() == candidate.strip():  # stopping criterion #2: convergence
            break
        candidate = revised
    return candidate
```

Notes: the editor persona + "NO_ISSUES" sentinel gives a clean stop. The convergence check prevents wasted loops. **Caveat:** because this is pure self-judgment, it polishes *style* well but cannot reliably fix *factual* or *logical* errors — pair it with RAG citation-checking if facts matter.

### 5.3 JSON validator-as-critic (structured output, Java + Bean Validation)

When the agent must emit a typed object, parse + validate is a perfect cheap critic.

```java
record InvoiceLine(@NotBlank String sku, @Min(1) int qty, @PositiveOrZero BigDecimal unitPrice) {}

String prompt = "Extract invoice lines as JSON array of {sku, qty, unitPrice} from:\n" + rawText;
for (int attempt = 1; attempt <= 3; attempt++) {
    String json = llm.prompt().user(prompt).call().content();
    try {
        List<InvoiceLine> lines = mapper.readValue(json, new TypeReference<>() {}); // Jackson parse
        Set<ConstraintViolation<InvoiceLine>> violations = lines.stream()
            .flatMap(l -> validator.validate(l).stream())            // Bean Validation = critic
            .collect(Collectors.toSet());
        if (violations.isEmpty()) return lines;                       // pass → done
        prompt = baseTask + "\nYour JSON had these problems, fix them:\n" + render(violations);
    } catch (JsonProcessingException e) {
        prompt = baseTask + "\nYour output was not valid JSON: " + e.getOriginalMessage();
    }
}
throw new IllegalStateException("Model could not produce valid invoice JSON in budget");
```

The critic here costs *zero LLM tokens* (Jackson + Hibernate Validator are local). The feedback is precise ("`qty` must be ≥ 1"). This is the workhorse pattern for reliable structured extraction. (Spring AI's `BeanOutputConverter` automates much of the parse step; you add validation as the critic.)

### 5.4 Actor–Critic with LLM-as-judge + rubric (separate critic, structured verdict)

```python
RUBRIC = """Score each 1-5, then give a 1-line fix if <5:
- correctness: does it satisfy every requirement in the task?
- completeness: any requirement omitted?
- safety: any harmful/unsafe content?
Return JSON: {"correctness":n,"completeness":n,"safety":n,"issues":["..."],"pass":bool}
pass=true only if all scores >=4."""

def actor_critic(task, max_iters=3):
    answer = generator_llm(task)                 # cheaper/faster model as actor
    for _ in range(max_iters):
        verdict = json.loads(critic_llm(          # different, stronger model as judge
            f"{RUBRIC}\nTASK:\n{task}\nANSWER:\n{answer}",
            temperature=0.0))                     # low temp = stable judgment
        if verdict["pass"]:
            return answer
        answer = generator_llm(
            f"TASK:\n{task}\nPrevious answer:\n{answer}\n"
            f"A reviewer found these issues, fix them:\n{verdict['issues']}")
    return answer
```

Bias controls baked in: a *different* model judges (mitigates self-preference bias); the rubric forces per-criterion reasoning *before* the `pass` boolean; temperature 0 stabilizes the verdict. If you compare two candidates, also randomize their order to fight position bias.

### 5.5 Reflexion-style cross-attempt memory (verbal RL)

```python
def reflexion(task, env, max_trials=4):
    memory = []                                   # episodic lessons, capped
    for trial in range(max_trials):
        lessons = "\n".join(f"- {m}" for m in memory[-3:])  # last-3 lessons in context
        trajectory = actor_llm(
            f"TASK:\n{task}\nLessons from past failed attempts:\n{lessons}")
        score = env.evaluate(trajectory)          # GROUND-TRUTH env signal (tests, game result)
        if score.success:
            return trajectory                     # solved
        lesson = reflect_llm(                      # write a durable natural-language lesson
            f"You FAILED this task. Trajectory:\n{trajectory}\n"
            f"Result:\n{score.detail}\n"
            "In ONE sentence, what will you do differently next time?")
        memory.append(lesson)
    return trajectory                             # best/last attempt
```

The lesson is the "verbal gradient." Note the evaluator is the *environment*, not the model judging itself — that grounding is what makes Reflexion robust where pure self-correction fails.

### 5.6 SQL agent against a sandbox database (execution as critic)

```python
def sql_agent(question, schema, sandbox_db, expected_check, max_iters=4):
    prompt = f"Schema:\n{schema}\nWrite ONE SQL query to answer: {question}"
    for _ in range(max_iters):
        sql = llm(prompt)
        try:
            rows = sandbox_db.execute_readonly(sql)        # run on a READ-ONLY replica/sandbox
        except DatabaseError as e:
            prompt = base + f"\nYour SQL errored: {e}. Fix it."   # syntax/semantic ground truth
            continue
        problem = expected_check(rows)                     # e.g., row count / sanity check
        if problem is None:
            return sql, rows                               # pass
        prompt = base + f"\nYour query ran but: {problem}. Revise."
    raise RuntimeError("No satisfactory SQL within budget")
```

Hardening notes: execute on a **read-only** connection/replica so a generated `DROP TABLE` cannot do damage; set a statement timeout; cap returned rows. The DB engine is the critic for *syntax and execution*; `expected_check` adds a domain critic.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency
- Each iteration is at least one generator round-trip; reflection multiplies latency by (iterations × calls-per-iteration). A 3-iteration self-refine with separate critic ≈ up to 6 LLM calls vs. 1 baseline → ~6× latency.
- **Mitigations:** cap iterations aggressively (most gain is in iter 1–2); run the critic tool (compiler/tests) in parallel with nothing-to-wait-for; use a *smaller/faster* model for the critic when LLM-judged; stream the final answer only; cache initial generations.
- **Tool-grounded critics are cheap on tokens** (the check is local) but may be slow on wall-clock (compiling, running a test suite). Time-box the critic.

### 6.2 Correctness & the self-correction trap
- **Do not trust unaided self-correction for factual/logical correctness.** The Huang et al. (2023) result is the key warning: without an external signal, self-critique can lower accuracy by "correcting" correct answers. Use ground-truth critics for anything checkable.
- Track **best-so-far** and return it; never blindly return the last revision.
- Use a **convergence check** to detect oscillation (A→B→A→B).

### 6.3 Concurrency
- If you run multiple reflection loops in parallel (batch processing), the *critic tools* (compiler, DB, sandbox) become shared resources — pool and rate-limit them. Generated-code execution must be isolated per task (separate sandbox), never shared state.

### 6.4 Memory / context
- Reflection accumulates context fast. Use last-k history or a running summary. Keep the *current candidate and most-relevant feedback near the end* of the prompt (lost-in-the-middle). Watch token costs — they grow per iteration.

### 6.5 Security (critical for tool-grounded code/SQL)
- **Generated code = untrusted input.** Sandbox execution (no network, read-only FS, CPU/mem/time limits, container or microVM). Java `SecurityManager` is deprecated/removed in modern JDKs — prefer OS/container isolation (containers, gVisor, Firecracker, seccomp).
- **SQL:** read-only replica, statement timeouts, no DDL/DML permissions.
- **Prompt injection through tool output:** the compiler/test/error text is fed back into the prompt; a malicious test or data source could inject instructions. Treat tool output as data, not instructions; consider delimiting and not letting it override the system prompt.
- **Cost as a security/availability concern:** an unbounded loop is a denial-of-wallet attack surface. Hard budgets are non-negotiable.

### 6.6 Observability
- **Log every iteration:** the candidate (or its hash/diff), the critique, the verdict, tokens used, latency. This trace is indispensable for debugging "why did it loop 6 times?"
- **Metrics (Micrometer/OTel):** iterations-per-task (histogram), pass@iteration distribution, % tasks hitting the cap (a smell), tokens-per-task, critic latency, regression rate (revision made it worse).
- **Tracing:** one span per state (GENERATE/CRITIQUE/REVISE) under one trace per task. LangSmith / Langfuse / OpenTelemetry GenAI semantic conventions help.

### 6.7 Testing the reflection system itself
- **Golden set with known answers** so you can measure whether reflection actually improves vs. the single-pass baseline. *Always A/B reflection against no-reflection* — sometimes it does not help and just costs more.
- **Adversarial cases** where the first draft is correct: verify reflection does *not* regress it (guards against the self-correction trap).
- **Determinism for tool critics:** make compiler/test/validator runs reproducible (pin versions, fix seeds).

### 6.8 Cost
- Budget per task in *tokens* and *dollars*. A reflection loop can 3–10× cost. Decide per use case whether the quality lift justifies it. For high-volume, low-stakes traffic, single-pass + cheap validator-as-critic is often the sweet spot; reserve full LLM-judged reflection for high-stakes outputs.

### 6.9 Anti-patterns (avoid these)
| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| No iteration cap | Infinite loops, runaway cost | Hard `max_iterations` + token/time budget |
| Self-judging factual correctness | Can lower accuracy | Use ground-truth critic |
| Returning last instead of best | Regressions ship | Track best-so-far by score |
| Vague feedback ("make it better") | No actionable signal | Itemized, specific, tool-grounded feedback |
| Same model+prompt as judge & author | Self-preference bias | Different model / strict rubric |
| Reflecting when first draft is already good | Wasted tokens, possible regression | Gate reflection on a cheap "is this likely wrong?" check |
| Running generated code in main process | Security catastrophe | Sandbox |
| No A/B vs. baseline | You don't know if it helps | Measure on a golden set |

---

## 7. Advanced topics & deep internals

### 7.1 Stopping criteria, formally
Combine multiple criteria with OR (stop if any fires):
1. **Critic-pass**: verdict says good enough (tests green, score ≥ threshold, "NO_ISSUES").
2. **Budget**: `iter ≥ max_iterations` OR tokens/time exhausted.
3. **Convergence**: candidate unchanged (exact or near-exact, e.g., edit-distance below ε).
4. **Diminishing returns**: score improvement < `min_improvement_delta` for the last step.
5. **Oscillation detection**: a candidate (by hash) repeats → break.

Subtlety: criterion (1) requires *trusting the critic*. With an LLM judge that has false negatives, you might stop too early or loop forever on a perfectly good answer the judge dislikes. Tool critics make (1) trustworthy.

### 7.2 When reflection helps vs. wastes tokens — the decision model
Reflection's expected value ≈ P(first draft is fixably-wrong) × (quality lift if fixed) − cost. It pays when:
- The first-pass error rate is *moderate* (not near 0, not near 1).
- Errors are *checkable* (tool critic) or at least *recognizable* (the model can spot what it could not avoid — true for some style/structure tasks, less so for hard reasoning).
- The verify-vs-generate asymmetry is favorable (checking is easier than generating).

It is wasted when first drafts are already excellent, when the model cannot detect its own errors (hard math/logic with self-judgment), or when latency/cost dominate value. A cheap **gate** (a fast classifier or heuristic: "does this output even parse / contain the required fields?") can decide *whether* to reflect at all, so you only pay the loop cost on outputs that need it.

### 7.3 Combining reflection with planning
- **Reflect on the plan before executing.** A planner emits steps; a critic checks the plan for completeness/feasibility *before* any expensive execution. Catching a bad plan early is far cheaper than catching a bad result late.
- **Reflect after each step vs. at the end.** Per-step reflection (verify each tool result before proceeding) localizes errors and prevents compounding, at higher cost. End-to-end reflection is cheaper but errors can cascade.
- **Hierarchical reflection:** reflect at the sub-task level *and* at the final-assembly level.

> **What is a "planner" / "planning" pattern?** An agent pattern where the LLM first decomposes a task into an ordered list of steps (a plan) before executing them, rather than acting greedily. Reflection composes with it by critiquing the plan and/or each step's outcome.

### 7.4 Reflexion deep details
- Memory is **capped** (e.g., last 3 reflections) to fit context and avoid drowning the actor in old lessons.
- Lessons are **task-specific** in the original work (retry the *same* task). Generalizing lessons across *different* tasks (so they help future unseen tasks) is harder and edges into long-term/semantic memory and skill libraries (cf. Voyager's skill library for Minecraft agents).
- The evaluator can be heuristic (game score), exact (tests), or an LLM — and the method's reliability tracks the evaluator's reliability.

### 7.5 Multi-agent debate as reflection
Several agents (or several personas) generate, then *critique each other* and converge ("LLM debate," Du et al., 2023). It is reflection with the critic externalized into peers; can improve factuality/reasoning but multiplies cost. A "judge" agent often adjudicates.

### 7.6 Self-consistency vs. reflection
**Self-consistency** (Wang et al., 2022): sample many CoT chains, take the *majority answer*. No critique — it relies on the *mode* of independent samples being right. Reflection actively *fixes* a chain. They can stack: self-consistency to pick a strong candidate, then reflection to refine it.

### 7.7 Process- vs. outcome-supervision and verifier models
For reasoning, a learned **verifier** (a model trained to score solution *steps* — "process reward model," PRM — or final *outcomes* — "outcome reward model," ORM) can serve as a stronger critic than self-judgment. PRMs that check each step have been shown to outperform ORMs for math. This is the trained-critic end of the spectrum, beyond prompt-only reflection.

### 7.8 Lesser-known behaviors / gotchas
- **Sycophantic critics:** a judge prompted apologetically may agree with the author. Prompt the critic to be adversarial/skeptical.
- **Critique-induced verbosity drift:** repeated "add more detail" feedback bloats outputs; add a length/conciseness criterion.
- **Reward hacking with tool critics:** the model may "pass tests" by special-casing the exact test inputs rather than solving the general problem. Mitigate with *hidden* tests the generator never sees, property-based tests, and coverage requirements.
- **Temperature pitfalls:** critic at high temperature gives inconsistent verdicts (loop never stabilizes); generator at temperature 0 may re-emit the identical flawed answer (no real revision). Tune oppositely.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Variant comparison

| Variant | Critic source | Reliability | Cost | Best for | Avoid when |
|---|---|---|---|---|---|
| Single-pass (no reflection) | none | baseline | 1× | easy/near-perfect tasks, latency-critical | quality matters & drafts unreliable |
| Self-Refine | self-judgment | low–med | ~3–6× calls | subjective polish (tone, clarity, structure) | factual/logical correctness |
| Actor–Critic (LLM judge + rubric) | separate LLM | med | ~2–6× | rubric-scorable quality, ranking | fine-grained correctness needs |
| Tool-grounded | deterministic tool | **high** | cheap tokens, maybe slow wall-clock | code, SQL, JSON, configs, anything checkable | no tool exists |
| Reflexion | env signal + memory | high (tracks evaluator) | high (multiple trials) | agentic tasks with retries & a reward signal | one-shot, no retry possible |
| Multi-agent debate | peer LLMs | med | very high | hard reasoning, factuality | cost-sensitive |

### 8.2 "Use when / avoid when"
- **Use tool-grounded reflection when** the output is machine-verifiable (code/SQL/structured data/config). This is the highest-ROI form. Always prefer it over self-judgment for those artifacts.
- **Use self-refine / LLM-judge when** quality is subjective and no tool can check it (writing quality, helpfulness), *and* you accept the reliability ceiling and add bias controls.
- **Use Reflexion when** the agent can *retry* a task and there is a real success signal (a benchmark, a game, a test suite), and durable learning across attempts helps.
- **Avoid reflection when** first-pass quality is already high (gate it), latency/cost dominates value, or the model demonstrably cannot detect its own errors on the task (measure this).
- **Avoid unaided self-correction for math/logic** — use a verifier or tool.

### 8.3 Alternatives to reflection (sometimes better)
- **Better first-pass prompting** (few-shot, CoT, clearer instructions) — often cheaper than a loop.
- **Best-of-N + reranker** — parallel instead of sequential; good when you have a scorer.
- **Fine-tuning / RLHF** — if you have data and volume, bake the quality into the weights.
- **Constrained decoding** (grammars, JSON-mode) — guarantees structural validity *without* a loop; complements validator-as-critic.

> **What is "constrained decoding"?** Forcing the model's output to match a grammar/schema *during generation* (e.g., JSON mode, regex/CFG-constrained sampling via Outlines/Guidance). It prevents structural errors up front, reducing the need for a validate-retry loop.

---

## 9. Failure modes & debugging

| Failure mode | Symptom | Diagnose with | Fix |
|---|---|---|---|
| Infinite / max-cap loop | Every task hits `max_iterations`; cost spikes | iterations-per-task metric; per-iteration trace | Add convergence + diminishing-returns stops; inspect why critic never passes (often an unsatisfiable/buggy critic) |
| Self-correction regression | Accuracy *drops* vs. single-pass on a golden set | A/B vs. baseline; per-iteration diff log | Track best-so-far; switch factual checks to tool critics |
| Oscillation (A→B→A) | Candidate flips between two states | hash candidates per iter | Oscillation detection → break; raise generator temperature or improve feedback specificity |
| Sycophantic judge | Judge always passes; quality still bad | sample judge outputs; compare to human labels | Adversarial critic prompt; different judge model; rubric with mandatory issue-finding |
| Reward hacking (tests) | Tests pass but real behavior wrong | hidden tests; coverage; property tests | Hidden/held-out tests; property-based testing; coverage gates |
| Prompt injection via tool output | Agent follows instructions embedded in error text/data | inspect what tool output flows into prompt | Treat tool output as data; delimit; don't let it override system prompt |
| Context overflow | API errors / truncation after a few iters | token counts per iter | last-k history or running summary; trim |
| Cost blowout | Spend per task too high | tokens-per-task metric | Hard budgets; gate whether to reflect; smaller critic model |
| Critic too slow | p99 latency dominated by test runs | critic-latency span | Time-box; parallelize; subset tests in early iters |

**Real-world flavored incidents (illustrative):**
- *The "fixes the wrong thing" regression:* a self-judging documentation agent rewrote a correct API signature because it "looked off," shipping a broken example. Postmortem fix: best-so-far + tool-checked code snippets (compile the docs' code blocks).
- *Denial-of-wallet:* a reflection loop with no token budget hit a degenerate task where the critic never passed; it ran 40+ iterations per request under load, multiplying the bill. Fix: hard token + iteration budgets and an alert on iterations-per-task p99.
- *Reward hacking:* a code agent learned to hard-code the exact expected outputs to pass visible tests. Caught by hidden tests in CI; fixed with held-out test sets and property-based tests.

**Debugging workflow:** (1) pull the full per-iteration trace for the offending task; (2) read the *critique* at each step — is the signal good? (3) check whether revisions actually changed the candidate (convergence/temperature issue); (4) verify the stopping criterion logic; (5) A/B against single-pass to confirm reflection is even helping.

---

## 10. Interview drill

**Q1. What is the reflection pattern and why does a second pass help if the model already tried its best?**
*Answer:* Reflection wraps generation in a critique-and-revise loop. It helps because of the generation-vs-verification asymmetry (checking is often easier than producing), role separation (a critic prompt surfaces different information than a generator prompt), and added test-time compute.
- *Probe: Doesn't that contradict "the model already tried"?* No — the first pass optimizes for producing something; verification is a distinct, easier task the model can do better in a dedicated pass.
- *Probe: When does it NOT help?* When the model cannot detect errors it could not avoid (hard math via self-judgment) — there self-correction can even hurt.
- *Probe: Cheapest way to capture most of the benefit?* Tool-grounded critique on machine-checkable outputs; 1–2 iterations.

**Q2. Self-judgment vs. tool-grounded critique — which and why?**
*Answer:* Prefer tool-grounded whenever the output is checkable (code/SQL/JSON/config). A test failure is ground truth; an LLM saying "looks correct" is a frequently-wrong opinion. Self-judgment is a last resort for subjective text.
- *Probe: Evidence?* Huang et al. (2023): unaided self-correction can lower reasoning accuracy. Reflexion's gains depend on an environment/evaluator signal.
- *Probe: Cost difference?* Tool critics cost ~0 LLM tokens (local execution) vs. an extra LLM call per judgment.
- *Probe: A subjective task with no tool?* LLM-as-judge with a strict rubric + bias controls, accepting a reliability ceiling.

**Q3. Explain Reflexion and how it differs from Self-Refine.**
*Answer:* Self-Refine refines a single output within one episode via self-feedback. Reflexion learns *across attempts*: it evaluates a trajectory with a ground-truth signal, writes a natural-language lesson, stores it in episodic memory, and retries — "verbal reinforcement learning" without weight updates.
- *Probe: Why "verbal RL"?* It gets RL-like improvement by updating text memory instead of model weights.
- *Probe: What limits memory size?* Context window → cap to last-N lessons.
- *Probe: What makes it reliable?* The evaluator being grounded (tests/env), not the model judging itself.

**Q4. Walk me through a code-writing reflection agent.**
*Answer:* Generate code → compile (JDK compiler API) → run tests (JUnit Launcher) → if green, return; else feed exact diagnostics/failures back and revise; loop under a budget; track best-so-far; sandbox execution.
- *Probe: Why feed the raw compiler output?* It is specific and actionable (line numbers, assertion diffs); shrinks the task to "make this error go away."
- *Probe: Security?* Generated code is untrusted; run in a sandbox (container/microVM, no network, time/mem limits).
- *Probe: Reward hacking?* Use hidden + property-based tests so it can't special-case visible cases.

**Q5. How do you decide stopping criteria?**
*Answer:* OR of: critic-pass, budget (iterations/tokens/time), convergence (candidate unchanged), diminishing returns (Δscore < ε), oscillation detection. Tool critics make "critic-pass" trustworthy.
- *Probe: Risk of trusting the critic to stop?* A flaky LLM judge causes premature stops or endless loops; ground-truth critics avoid this.
- *Probe: Default max iterations?* 2–4 subjective, up to ~6–10 tool-grounded; most gains in iter 1–2.

**Q6. (Senior signal) When would you NOT add reflection, and how would you decide programmatically?**
*Answer:* When first-pass quality is already high, latency/cost dominates, or the model can't detect its own errors. Decide via expected-value: P(fixably-wrong) × quality-lift − cost. Use a cheap *gate* (parse/required-field/heuristic check) to reflect only on outputs likely to be wrong, and always A/B against the single-pass baseline on a golden set.
- *Probe: Metric to monitor that reflection is helping?* Quality lift vs. baseline; % tasks hitting the cap (a smell); regression rate.
- *Probe: A case it actively hurt?* Self-judged factual correction regressing right answers.

**Q7. (Senior signal) How do you keep an LLM-as-judge honest?**
*Answer:* Use a *different* model than the author (self-preference bias), an explicit rubric requiring per-criterion reasoning before the verdict, temperature 0, randomized option order for comparisons (position bias), penalize verbosity, and adversarial critic framing. Calibrate against human labels on a sample.
- *Probe: Name three judge biases.* Position, verbosity, self-preference (also sycophancy).
- *Probe: How verify the judge is good?* Correlate judge scores with human/ground-truth on a labeled set.

**Q8. How does reflection compose with planning?**
*Answer:* Reflect on the plan before executing (cheap to catch bad plans early), and optionally per-step (localizes errors, prevents cascading) vs. only at the end (cheaper, errors compound). Hierarchical reflection covers both sub-task and assembly.
- *Probe: Per-step vs. end-to-end tradeoff?* Cost vs. error containment.
- *Probe: Where is reflection cheapest leverage?* Critiquing the plan before expensive tool execution.

**Q9. Self-consistency, best-of-N, and reflection — distinguish them.**
*Answer:* Self-consistency = sample many CoT chains, take majority (no critique). Best-of-N = generate N candidates, pick best by a scorer (parallel). Reflection = iteratively fix one line of candidates with feedback (sequential). They stack.
- *Probe: When prefer best-of-N over reflection?* When you have a good scorer and want parallelism/low latency.
- *Probe: Combine them?* Best-of-N to pick a strong candidate, then reflect to refine.

**Q10. (Senior signal) Design the observability for a reflection system in production.**
*Answer:* One trace per task, one span per state (GENERATE/CRITIQUE/REVISE); log candidate (hash/diff), critique, verdict, tokens, latency per iteration. Metrics: iterations-per-task histogram, pass@iteration, % hitting cap, tokens/$ per task, regression rate, critic latency. Alert on cap-hit p99 and cost per task. This lets you debug loops, detect regressions, and prove ROI vs. baseline.
- *Probe: One metric that screams "broken loop"?* % of tasks hitting `max_iterations`.
- *Probe: How catch regressions?* Best-vs-last divergence and A/B vs. single-pass on a golden set.

**Q11. What is reward hacking in tool-grounded reflection and how do you prevent it?**
*Answer:* The model satisfies the *visible* critic (e.g., hard-codes outputs to pass shown tests) without solving the real problem. Prevent with hidden/held-out tests, property-based tests, coverage requirements, and randomized inputs.
- *Probe: Why are hidden tests essential?* The generator can overfit anything it can see.
- *Probe: A non-test example?* Padding text to satisfy a length-based judge while adding no value.

**Q12. Why can repeated self-critique make outputs worse, and how do you guard against it?**
*Answer:* Without ground truth, the critic can flag non-bugs and the reviser can break correct content; verbosity/over-editing drift. Guards: tool-grounded critics, best-so-far return, convergence/oscillation stops, conciseness criteria, and A/B vs. baseline.
- *Probe: A single safeguard with the most impact?* Returning best-so-far by an objective score (ideally tool-derived).

---

## 11. Glossary

- **Agent (agentic):** an LLM in a control loop that can take actions (tools), observe results, and decide next steps.
- **Actor:** the component/LLM that produces actions or candidate outputs (Reflexion/actor–critic).
- **Best-of-N:** generate N independent candidates, pick the best by a scorer.
- **Bean Validation (Jakarta):** Java declarative constraint API (`@NotNull`, `@Min`, …); Hibernate Validator is the reference implementation; usable as a critic.
- **Chain-of-thought (CoT):** prompting the model to write intermediate reasoning before the answer.
- **Constrained decoding:** forcing output to match a grammar/schema during generation (JSON mode, grammars).
- **Context window:** max tokens (input+output) an LLM handles per call.
- **Convergence:** when the candidate stops changing between iterations.
- **Critic / evaluator / judge:** the component that assesses a candidate and emits feedback/score.
- **Diagnostic (`javax.tools`):** a compiler-emitted error/warning with location, collectable programmatically.
- **DSPy:** framework for programmatic prompt construction/optimization with assertions/retries.
- **Embedding:** fixed-length numeric vector representing the meaning of text; basis of vector search.
- **Episodic memory:** store of past attempts/lessons used to condition future behavior.
- **Generator (actor):** produces the candidate output.
- **Ground truth:** an objectively correct signal (test pass/fail, compiler result) independent of model opinion.
- **JavaCompiler API:** JDK programmatic compiler (`ToolProvider.getSystemJavaCompiler`).
- **JUnit Platform Launcher API:** programmatic discovery/execution of JUnit tests with result listeners.
- **LangChain / LangChain4j:** LLM app frameworks (Python/JS and Java).
- **LangGraph:** stateful graph orchestration for LLM apps; ideal for explicit reflection loops.
- **Linter:** static analyzer flagging stylistic/likely-bug patterns without executing code.
- **LLM:** large language model; Transformer trained to predict next tokens.
- **LLM-as-judge:** using an LLM to score/rank outputs; prone to position/verbosity/self-preference biases.
- **Lost in the middle:** models retrieve start/end context more reliably than middle.
- **Multi-agent debate:** multiple agents critique each other and converge.
- **NLI (Natural Language Inference):** judges entailment/contradiction/neutral between premise and hypothesis.
- **ORM / PRM (reward models):** outcome- vs. process-supervised verifier models that score answers/steps.
- **Planner / planning:** decomposing a task into ordered steps before executing.
- **Process supervision:** training/scoring on the correctness of each reasoning step.
- **Prompt injection:** malicious instructions embedded in data/tool output that hijack the model.
- **RAG (Retrieval-Augmented Generation):** retrieve documents into the prompt to ground answers; usable for citation-checking critics.
- **ReAct:** interleaving Reasoning and Acting (tool calls) in an agent loop.
- **Reflection:** the critique-and-revise pattern (this chapter).
- **Reflexion:** cross-attempt reflection storing natural-language lessons in memory ("verbal RL").
- **Reinforcement learning (RL):** learning from action→reward to maximize cumulative reward (updates weights).
- **Reward hacking:** satisfying the metric/critic without solving the real task.
- **Sandbox:** isolated, restricted execution environment for untrusted code.
- **Self-consistency:** sample many CoT chains, take the majority answer.
- **Self-Refine:** within-episode iterative refinement via self-feedback.
- **Stopping criterion:** rule that exits the reflection loop.
- **Sycophancy:** a model agreeing with the user/author rather than being correct.
- **Temperature:** sampling randomness parameter (0 = near-deterministic).
- **Test-time compute:** inference-time compute (extra tokens/attempts/checks) traded for quality.
- **Token:** the unit of text an LLM processes (~¾ word in English).
- **Trajectory:** the full sequence of an agent's steps in one episode.
- **Transformer:** the neural architecture (self-attention) underlying modern LLMs.
- **Type checker:** verifies type consistency (`javac`, `mypy`, `tsc`).
- **Vector database:** stores embeddings for semantic nearest-neighbor retrieval.
- **Verifier model:** a model trained to judge correctness of answers/steps.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)
- **Pattern:** generate → critique → decide (stop or revise) → loop under a budget.
- **#1 rule:** prefer **tool-grounded critique** (compiler, tests, validator, linter, DB) over self-judgment for anything checkable. A test failure is fact; "looks correct" is a frequently-wrong opinion.
- **Variants:** Self-Refine (self-judgment, within-episode) · Actor–Critic + LLM-judge (separate critic + rubric) · Tool-grounded (deterministic critic, gold standard) · Reflexion (cross-attempt, verbal RL, env signal + memory) · Multi-agent debate.
- **Self-correction trap:** unaided self-correction can *lower* accuracy on reasoning (Huang 2023). Don't self-judge facts/logic.
- **Defaults/rules of thumb:** max_iterations 2–4 (subjective), ~6–10 (tool-grounded); most gain in iter 1–2; critic temp 0–0.2, generator temp 0.3–0.7; history last-k (k=1–2); **return best-so-far, not last**.
- **Stop on:** critic-pass OR budget OR convergence OR diminishing returns OR oscillation.
- **Costs ~3–10×** baseline → gate whether to reflect; A/B vs. single-pass on a golden set.
- **Judge bias controls:** different model as judge, rubric + reasoning-before-verdict, temp 0, randomize order, penalize verbosity.
- **Security:** sandbox generated code; read-only DB for SQL; treat tool output as data (prompt injection); hard budgets (denial-of-wallet).
- **Observability:** per-iteration trace + metrics: iterations/task, % hitting cap, tokens/$ per task, regression rate, pass@iteration.
- **Reward hacking:** hidden + property-based tests, coverage gates.
- **JVM toolkit:** Spring AI `ChatClient`/`BeanOutputConverter`, Bean Validation, JDK `JavaCompiler`, JUnit Launcher, Resilience4j (budget), Micrometer/OTel (obs).

### Self-test (no answers — for active recall)
1. Explain the generation-vs-verification asymmetry and why it justifies reflection. Give one task where it holds strongly and one where it does not.
2. You must extract typed JSON from messy text reliably and cheaply. Design the reflection loop end to end, name the critic, and state your stopping criteria.
3. Why can repeated self-critique *reduce* accuracy on a math benchmark, and what single change most reliably fixes it?
4. Design observability and budgets for a code-reflection agent in production: list the exact metrics and the two alerts you would set.
5. Distinguish Self-Refine, Reflexion, best-of-N, and self-consistency, and give one scenario where each is the right choice.
6. Your reflection loop hits `max_iterations` on every request and costs spiked. Walk through your diagnosis steps and the three most likely root causes.
7. How would you prevent reward hacking when using unit tests as the critic for a code agent?

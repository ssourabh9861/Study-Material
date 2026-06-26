# Planning & Decomposition in Agentic AI

> An exhaustive engineering-handbook chapter for senior backend developers (Java/JVM-centric) who want to fully master how AI agents break complex goals into subtasks, plan, execute, and replan — from first principles to deep internals, production operation, and interview-grade depth.

---

## 1. Overview & where it fits

### What it is

**Planning & decomposition** is the part of an agentic AI system responsible for turning a single high-level goal ("migrate this service from MySQL to Postgres and verify it") into an ordered (or partially ordered) set of smaller, executable **subtasks**, deciding what order to run them in, dispatching them to tools or sub-agents, and revising the plan when reality diverges from expectation.

A few terms before we go further, because the rest of this chapter leans on them:

- **Agent**: a software loop wrapped around a large language model (LLM) that can *observe* (read inputs/tool results), *decide* (call the model), and *act* (invoke tools, write files, call APIs), repeating until a goal is met. The defining feature versus a plain chatbot is the loop plus the ability to take actions in the world.
- **LLM (large language model)**: a neural network trained to predict the next token of text. Practically, you send it a prompt (text) and it returns generated text. It has no memory between calls except what you put in the prompt, and it is *stochastic* (can give different answers to the same input).
- **Tool / tool call / function call**: a structured way for the model to ask the host program to run code — e.g. `search_web(query)`, `run_sql(stmt)`, `read_file(path)`. The model emits a JSON-ish request; your code executes it and feeds the result back into the next model call.
- **Token**: the unit LLMs read and bill in — roughly ¾ of an English word. A 1,000-word prompt is ~1,300 tokens. Cost and latency scale with tokens, which is why planning has a real dollar cost (Section 1's "where it fits" and Section 6's cost subsection).
- **Subtask**: a unit of work small enough that a single model+tool step (or a small bounded loop) can complete it reliably.
- **Decomposition**: the act of splitting a goal into subtasks.
- **Planning**: deciding the *structure and order* of subtasks (dependencies, branches, parallelism) and the strategy for executing them.

### The problem it solves

LLMs are strong at *local* reasoning (one logical hop) but degrade on *long-horizon* tasks that need many dependent steps, because:

1. **Error compounding.** If each step is 90% reliable, a 10-step chain is `0.9^10 ≈ 35%` reliable end-to-end. Decomposition with verification at each boundary lets you catch and retry locally instead of failing globally.
2. **Context window pressure.** The **context window** is the maximum number of tokens the model can attend to at once (e.g. 200K for Claude, 128K–1M for various GPT/Gemini models). A monolithic "do everything in one prompt" approach floods the window with irrelevant detail. Decomposition keeps each step's context small and focused.
3. **Attention dilution / "lost in the middle."** Even within the window, models attend less reliably to information buried in the middle of a long prompt. Smaller, scoped subtasks sidestep this.
4. **No native control flow.** A raw LLM has no loops, no branches, no parallelism, no retry. Planning is how you impose program structure onto a fundamentally stochastic text predictor.

### When you reach for it

| Situation | Reach for explicit planning? |
|---|---|
| Single tool call answers the question ("what's the weather in Paris?") | No — just call the tool. |
| 2–4 obvious sequential steps, each cheap | Often no — a simple ReAct loop (Section 2) improvises fine. |
| Many steps, some independent (parallelizable), some dependent | Yes — a task graph / DAG. |
| Steps are expensive (each is a paid API call, a deploy, a DB write) | Yes — plan upfront so you can review/approve before spending. |
| Long horizon with checkpoints, human approval gates, or audit needs | Yes — explicit plan = inspectable artifact. |
| Outcome is uncertain and you must adapt | Yes — but choose *dynamic* replanning, not static. |

### One-paragraph mental model

Think of the agent as a **project manager (the planner)** plus a **team of workers (executors)**. The PM reads the goal, writes a checklist with dependencies (a plan / task graph), hands items to workers (tools or sub-agents), collects results, and — crucially — re-reads the checklist after each batch to decide whether the plan still makes sense or needs revision. The whole chapter is about how that PM is implemented: how the checklist is generated, represented (DAG), scheduled, executed, verified, and repaired when steps fail.

---

## 2. Foundations from first principles

We build the vocabulary and the simplest possible agent first, then layer planning on top.

### 2.1 The bare agent loop

The simplest agent is a `while` loop:

```
state ← initial observation (the user goal)
loop:
    thought, action ← LLM(state)      # decide
    if action == FINISH: return answer
    observation ← execute(action)     # act
    state ← state + observation       # observe / accumulate
```

This is essentially **ReAct** (Reason + Act), the foundational pattern from Yao et al., 2022. The model alternates a free-text **reasoning trace** ("I should first look up X") with an **action** (a tool call), sees the **observation** (tool result), and reasons again. There is *no explicit plan* — the agent improvises one step at a time. This is "letting the model improvise."

**Why improvisation breaks down:** ReAct's next action depends only on the accumulated transcript. On long tasks the transcript grows, attention dilutes, the model forgets early constraints, loops on the same failing action, or wanders. There's also no way to parallelize (it's strictly serial) and no artifact a human can approve before execution.

### 2.2 What "a plan" actually is

A plan is data: a list or graph of steps. Minimal representations, from simplest to richest:

1. **Linear list** — `["fetch repo", "find configs", "rewrite", "test"]`. Simple, but expresses no parallelism and no dependencies.
2. **Dependency list / adjacency** — each step names its prerequisites. This is a **DAG** (directed acyclic graph): nodes = subtasks, edges = "must finish before." *Acyclic* means no step can (transitively) depend on itself — otherwise you'd have a deadlock. We'll formalize DAGs in 2.4.
3. **Hierarchical plan** — high-level steps that themselves decompose into sub-plans (a tree of DAGs). Used by orchestrator-worker and hierarchical-task-network approaches (Section 3).

### 2.3 Decomposition strategies (the "how do I split it" question)

- **Top-down (goal → subgoals).** Start from the goal and recursively split until each leaf is directly executable. This is what most LLM planners do: "To migrate the DB, I must (a) snapshot, (b) translate schema, (c) port queries, (d) validate." Each can split further. Analogous to **HTN — Hierarchical Task Network** planning in classical AI, where compound tasks are expanded into primitive tasks via decomposition *methods*.
- **Bottom-up.** Identify available primitives/tools first, then compose them toward the goal. Rare in pure-LLM agents; common when the toolset is fixed and small.
- **Means-ends analysis.** Compare current state to goal state, pick an action that reduces the difference, repeat. This is the logic behind classical **STRIPS planners** (Stanford Research Institute Problem Solver, 1971) — the ancestor of all this. STRIPS models the world as a set of true facts, and each action has *preconditions* (facts that must hold) and *effects* (facts it adds/deletes). LLM agents rarely do formal STRIPS, but the precondition/effect mental model is exactly how you reason about subtask dependencies.

### 2.4 DAGs, topological order, and the critical path (formal but plain)

A **DAG** is a set of nodes with directed edges and no cycles. For task graphs:

- **Node** = a subtask.
- **Edge `A → B`** = "B depends on A" (A must complete, and usually produce output, before B).
- **Root / source** = node with no incoming edges (can start immediately).
- **Leaf / sink** = node with no outgoing edges (final outputs).

**Topological sort** is an ordering of nodes such that every edge points "forward" — i.e., a valid execution order respecting all dependencies. Standard algorithms:

- **Kahn's algorithm**: repeatedly remove a node with zero remaining in-edges; if you ever can't but nodes remain, there's a cycle. O(V+E).
- **DFS-based**: post-order of a depth-first traversal, reversed. Also O(V+E).

Two more concepts you'll use constantly when scheduling subtasks:

- **Parallelizable set**: at any moment, all nodes whose dependencies are already satisfied can run concurrently. This is the "frontier."
- **Critical path**: the longest *dependency* chain through the DAG. It bounds the minimum wall-clock time even with infinite parallelism — if the critical path is 5 steps, you can't finish in fewer than 5 sequential rounds no matter how many workers you have. (Borrowed from project management / PERT.)

### 2.5 Planner / executor split

The single most important architectural idea in this chapter:

- **Planner**: an LLM call (or chain) whose *only* job is to produce the plan. Its prompt is heavy on the goal, constraints, and the tool catalog; light on execution detail.
- **Executor**: the component that runs each subtask — could be a tool call, a tighter ReAct loop, or a specialized sub-agent. Its context is scoped to *just that subtask*.

Separating them gives you: (1) an inspectable plan artifact, (2) the ability to use a strong/expensive model for planning and a cheap/fast model for execution, (3) cleaner error isolation, and (4) the option to insert a human approval gate between plan and execution.

### 2.6 Static vs dynamic planning

- **Static (plan-and-execute / open-loop)**: generate the whole plan once, then execute every step without re-consulting the planner. Cheapest (one planning call), most predictable, but brittle — if step 3's result invalidates step 4, the agent charges ahead anyway.
- **Dynamic (closed-loop / replanning)**: after each step (or each failed step), feed results back to the planner, which can revise remaining steps, insert new ones, or abort. More robust, more expensive, and the basis of the LangGraph "Plan-and-Execute" reference implementation.

These are two ends of a spectrum; most production systems sit in between (replan only on failure or on confidence drop — Section 3.5).

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle of a planning agent, then dissect each major pattern's internal workflow.

### 3.1 The generic planning-agent lifecycle (step by step)

```
┌─────────────────────────────────────────────────────────────┐
│ 1. INGEST GOAL        receive goal + context + tool catalog   │
│ 2. PLAN               LLM emits structured plan (list/DAG)    │
│ 3. VALIDATE PLAN      parse, check schema, detect cycles      │
│ 4. (optional) APPROVE human or policy gate                    │
│ 5. SCHEDULE           topo-sort; compute ready frontier        │
│ 6. EXECUTE STEP(S)    dispatch ready nodes to executors        │
│ 7. OBSERVE            collect results, write to shared state    │
│ 8. EVALUATE           did step succeed? does plan still hold?  │
│ 9. BRANCH:                                                     │
│      ├─ success & plan intact → back to 5 (next frontier)     │
│      ├─ step failed → RETRY (n times) → else REPLAN            │
│      ├─ result invalidates plan → REPLAN (back to 2)          │
│      └─ goal met → 10                                          │
│ 10. SYNTHESIZE        compose final answer from artifacts      │
└─────────────────────────────────────────────────────────────┘
```

Let's expand the non-obvious stages.

**Stage 2 — PLAN (the planner call).** The planner prompt typically contains: the goal, hard constraints, a *tool catalog* (name + description + JSON schema of args for each available tool), output-format instructions (often "respond with JSON matching this schema"), and few-shot examples of good plans. The model returns a plan. Two output disciplines exist:

- *Free-form then parse*: model writes prose/markdown, you parse it. Fragile.
- *Structured output*: you force JSON via the provider's structured-output / tool-calling mode (Section 4), so the plan deserializes cleanly into objects. Strongly preferred.

**Stage 3 — VALIDATE PLAN.** Before executing anything: deserialize, check every referenced tool exists, check every dependency references a real step, run **cycle detection** (Kahn's algorithm — if any node never reaches in-degree 0, reject), and check resource/permission constraints. A malformed plan caught here is cheap; caught mid-execution it's expensive.

**Stage 5 — SCHEDULE.** Build the in-degree map, find all nodes with in-degree 0 (the ready frontier), and dispatch. As each completes, decrement in-degrees of its dependents; newly-zero nodes join the frontier. This is Kahn's algorithm used as a *runtime scheduler*, not just a sort.

**Stage 6 — EXECUTE.** Each subtask runs in its own scoped context. Crucially, executors usually do **not** see the whole transcript — only the goal, their specific subtask, and the *outputs of their dependency nodes*. This context isolation is why decomposition saves tokens.

**Stage 8 — EVALUATE.** Two questions: (a) *Did this step succeed?* (tool returned without error, output passes a validator/grader). (b) *Is the rest of the plan still valid given this result?* Question (b) is what separates static from dynamic. In dynamic systems an LLM "replanner" re-reads the plan + new results and emits either "continue" or a revised remaining-plan.

### 3.2 Pattern A — ReAct (the baseline, no explicit plan)

Covered in 2.1. Internal loop: `Thought → Action → Observation → Thought → …`. Single model, single context, serial, no plan artifact. Failure mode: it can loop or wander on long tasks. It's the thing the other patterns improve on.

### 3.3 Pattern B — Plan-and-Execute (static, then optionally dynamic)

**Internal workflow:**

1. **Planner step**: one LLM call produces an ordered list of steps from the goal.
2. **Execution loop**: for each step, an *executor agent* (often itself a small ReAct loop with tools) does the step. The executor sees the original objective + the current step + prior step outputs.
3. **(Dynamic variant) Replan step**: after each executed step, a *replanner* LLM call receives `(objective, original_plan, steps_done, latest_result)` and returns either an updated plan (remaining steps) or a final response. This is the LangGraph canonical implementation: nodes `planner → agent(executor) → replan → (loop or end)`.

**Why it's faster/cheaper than ReAct for multi-step work:** the executor for each step can be a smaller/cheaper model, and you make *one* expensive planning call instead of forcing the big model to reason about the whole journey at every single step.

**Control flow (LangGraph-style state machine):**

```
START → planner → executor → replan ──┐
                    ▲                  │
                    └──── (more steps)─┘
                                       │
                              (done) → END
```

### 3.4 Pattern C — ReWOO (Reasoning WithOut Observation)

**ReWOO** (Xu et al., 2023) decouples reasoning from tool execution to slash token cost. Standard ReAct interleaves the model and tools, so the *entire growing transcript* is re-sent on every tool round — expensive. ReWOO restructures into three modules:

1. **Planner**: in a *single* LLM call, produces the full plan as a list of steps where each step names a tool and its inputs, and inputs may reference *placeholder variables* like `#E1`, `#E2` for the not-yet-known outputs of earlier steps. Example plan:
   - `Plan: find population. #E1 = Search["population of France"]`
   - `Plan: find capital. #E2 = Search["capital of France"]`
   - `Plan: combine. #E3 = LLM["Given #E1 and #E2, write summary"]`
2. **Worker**: executes each `#En` tool call. No LLM in the loop here for tool dispatch — it just runs the tools and substitutes results into the placeholders. Independent `#En` can run in parallel.
3. **Solver**: one final LLM call that takes the plan + all evidence (`#E1…#En`) and produces the answer.

**Key internal insight:** the model is invoked roughly *twice* (planner + solver) regardless of how many tools fire, instead of once per tool round. ReWOO reported substantial token reductions versus ReAct on multi-step benchmarks (the paper cites large efficiency gains on HotpotQA-style tasks; treat exact percentages as benchmark-specific). **Tradeoff:** because the whole plan is fixed upfront with no observation between steps, ReWOO can't adapt if `#E1`'s actual result should change which tool step 2 uses — it's *open-loop by design*.

### 3.5 Pattern D — Tree-of-Thoughts (ToT) — planning as search

**Tree-of-Thoughts** (Yao et al., 2023) generalizes chain-of-thought into a *search tree* over partial solutions ("thoughts").

- **Node** = a partial solution / intermediate reasoning state.
- **Branching** = the model generates *k* candidate next thoughts from a node.
- **Evaluation** = a "value" step (the model or a heuristic) scores each candidate's promise ("sure / maybe / impossible").
- **Search** = BFS or DFS over the tree, expanding promising nodes, pruning dead ends, optionally backtracking.

**Internal workflow (BFS variant):**

```
frontier = [root]
for depth in 1..D:
    candidates = []
    for node in frontier:
        candidates += LLM.propose(node, k)     # generate k thoughts
    scored = [(c, LLM.evaluate(c)) for c in candidates]
    frontier = top_b(scored)                    # keep best b (beam width)
return best(frontier)
```

This is **beam search** (keep the best *b* partial solutions at each depth) implemented with LLM-generated branches and LLM-scored pruning. It shines on puzzle/search problems (Game of 24, crosswords, creative writing) where you must explore and backtrack. **Cost is the headline issue**: you pay for `branching_factor × depth × (propose + evaluate)` LLM calls — easily 50–100× a single chain-of-thought. Use only when a single forward pass demonstrably fails and the problem has a verifiable structure.

Adjacent terms: **BFS** (breadth-first search — explore level by level), **DFS** (depth-first — go deep then backtrack), **beam width** (how many candidates you keep per level), **pruning** (discarding low-value branches early).

### 3.6 Pattern E — Hierarchical / Orchestrator-Worker (and multi-agent)

A single planner that also executes gets overloaded. The **orchestrator-worker** pattern (Anthropic's term; also called supervisor, or HTN-style hierarchy) splits responsibilities across *agents*:

- **Orchestrator (lead/supervisor)**: owns the goal, decomposes it, decides which workers to spawn, what subtask each gets, and synthesizes their results. It does *not* do the detailed work.
- **Workers (subagents)**: each gets one scoped subtask, its own fresh context window, and its own tools; runs an independent loop; returns a result.

**Internal workflow:**

```
1. Orchestrator decomposes goal → N subtasks (a plan/DAG).
2. For each independent subtask: spawn a worker with
     (subtask description, scoped context, allowed tools, output contract).
3. Workers run concurrently (this is where parallelism pays off).
4. Each worker returns a structured result.
5. Orchestrator evaluates results; may spawn follow-up workers
   (e.g. a "citation/verification" worker), or replan.
6. Orchestrator synthesizes the final answer.
```

**Why it matters:** Anthropic's "Building a multi-agent research system" reported that a multi-agent (orchestrator + parallel subagents) setup beat a single agent on breadth-heavy research tasks — but at roughly **~15× the tokens** of a single chat. So this pattern buys capability/parallelism at a steep token cost; reserve it for genuinely parallelizable, high-value tasks. (Numbers are Anthropic-reported and workload-specific.)

Each worker having its own context window is the killer feature: a single agent has *one* context budget; N workers have N budgets, so the system can read far more total material than one agent could fit.

### 3.7 Static vs dynamic re-planning — the decision internally

| Trigger to replan | Static? | Dynamic? |
|---|---|---|
| Never (run plan to completion) | ✔ open-loop | |
| On step failure only (else continue) | hybrid | hybrid |
| After every step (always re-evaluate) | | ✔ full closed-loop |
| On confidence/grader threshold breach | | ✔ |

**Replanning internals.** A replan call typically receives: the immutable goal, the *original* plan (so the model has a baseline to diff against), the list of completed steps with their results, and the trigger reason (failure, surprise, new info). It must return a *remaining* plan — ideally preserving still-valid future steps to avoid thrashing. A common bug: the replanner regenerates the entire plan from scratch each time, causing the agent to redo work or oscillate between two plans (Section 9).

### 3.8 State, memory, and the shared blackboard

Across all patterns there's shared mutable state — often called the **blackboard** (a classic AI architecture where independent components read/write a common data structure). For agents it holds: the goal, the current plan, completed step outputs (the *artifact store*), scratch notes, and metadata (token counts, attempt counts). Executors read their inputs from it and write their outputs back. Designing this state object well (immutable snapshots, append-only artifact log) is what makes replanning and debugging tractable.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, classes, frameworks, and config you'll actually use. Because "planning" is mostly an *orchestration* concern, the toolkit spans (a) LLM-provider primitives, (b) agent frameworks, and (c) supporting libraries (graphs, schedulers, validation).

### 4.1 LLM-provider primitives that planning is built on

| Primitive | What it does | Key parameters / notes | Defaults |
|---|---|---|---|
| **Tool/function calling** | Model returns a structured request to call a named function with typed args | You pass a list of tool schemas (name, description, JSON Schema params); model returns `tool_calls` | Provider-specific; must opt in by passing tools |
| **Structured / JSON output** | Forces model output to conform to a schema (so a plan deserializes cleanly) | OpenAI `response_format={type:"json_schema", ...}` (Structured Outputs); Anthropic via tool-use with a single output tool | Off by default |
| **`temperature`** | Randomness of sampling; lower = more deterministic | `0.0`–`2.0`. For *planning*, use low temp (0–0.3) for stable plans; higher for ToT *proposal* diversity | Often `1.0` |
| **`max_tokens` / output cap** | Caps generated tokens (and thus plan length) | Set generous for planner, tight for executors | Provider default |
| **`tool_choice` / function_call** | Force, allow, or forbid tool use | `auto` / `required` / `{name:...}` | `auto` |
| **`stop` sequences** | End generation at a marker | Useful to delimit plan blocks | none |
| **System prompt** | Persistent role/instructions | Where you put the planner persona + output contract | — |

> Version/vendor flag: exact parameter names differ. OpenAI uses `tools`/`tool_calls`/`response_format`; Anthropic uses `tools`/`tool_use` content blocks; Google Gemini uses `function_declarations`/`functionCall`. Confirm against current SDK docs.

### 4.2 Agent / orchestration frameworks

| Framework | Language | Planning model it offers | Notes |
|---|---|---|---|
| **LangGraph** | Python (+ JS) | Explicit **graph** of nodes/edges; canonical *Plan-and-Execute*, *ReWOO*, *ToT* example graphs; conditional edges for replanning; checkpointing for human-in-the-loop | Most directly "DAG of subtasks." State machine is first-class. |
| **LangChain (agents)** | Python/JS | ReAct agents, plan-and-execute (older `PlanAndExecute`) | Higher-level; LangGraph is the modern successor for control flow. |
| **LlamaIndex** | Python | Query planning, sub-question query engine, agent workflows, `Workflow` event system | Strong for RAG-style decomposition (split a question into sub-questions). |
| **Microsoft AutoGen** | Python/.NET | Multi-agent conversations; group chat; planner/critic roles | Conversation-centric multi-agent. |
| **CrewAI** | Python | Role-based "crews"; sequential or hierarchical process; a manager agent that plans/delegates | The hierarchical process = orchestrator-worker out of the box. |
| **OpenAI Agents SDK / Assistants** | Python/JS | Handoffs between agents; built-in tool loop | Lightweight multi-agent handoff model. |
| **Semantic Kernel** | C#/Python/Java | "Planners" (historically `SequentialPlanner`, `StepwisePlanner`); now function-calling-driven planning | **Has a Java SDK** — relevant for JVM shops. |
| **Spring AI** | **Java** | Tool/function calling, advisors, chat memory; you compose planning loops yourself | The natural JVM choice; no opinionated planner — you build the loop. |
| **LangChain4j** | **Java** | Tools, AI services, RAG; you assemble planning logic | Idiomatic Java; pairs with structured output. |

> JVM note: there is no Java framework as planning-opinionated as LangGraph. In Java you typically combine **Spring AI** or **LangChain4j** (for the LLM/tool plumbing) with your own DAG scheduler (a `Map<Node, Set<Node>>` + Kahn's algorithm + an `ExecutorService`).

### 4.3 Supporting libraries (JVM-centric)

| Need | Java/JVM option | Purpose |
|---|---|---|
| Concurrency / scheduling | `java.util.concurrent.ExecutorService`, `CompletableFuture`, **structured concurrency** (`StructuredTaskScope`, JDK 21+ preview/JDK 25) | Run independent subtasks in parallel; join results |
| DAG / graph | **JGraphT** (`DirectedAcyclicGraph`, `TopologicalOrderIterator`, `CycleDetector`) | Represent and validate the task graph |
| JSON / schema validation | Jackson (`ObjectMapper`), `networknt/json-schema-validator` | Deserialize and validate the plan |
| Retry / resilience | **Resilience4j** (`Retry`, `CircuitBreaker`, `RateLimiter`, `Bulkhead`) | Per-subtask retry, backoff, circuit-breaking on flaky tools |
| Workflow durability | **Temporal** (Java SDK), AWS Step Functions, Camunda/BPMN | Durable, resumable long-running plans with retries/timeouts baked in |
| Observability | **OpenTelemetry**, **LangSmith**, **Langfuse**, **Arize Phoenix** | Trace each plan/step/tool span; capture token counts |

### 4.4 Classical-planning tools (for the rare case you need formal planning)

| Tool | What | Use case |
|---|---|---|
| **PDDL** (Planning Domain Definition Language) | A language to declare a domain (actions with preconditions/effects) and a problem (initial + goal state) | When the world is fully specified and you want a *guaranteed-correct* plan from a solver, not an LLM guess |
| **Fast Downward / Pyperplan** | PDDL solvers | Produce optimal/satisficing action sequences |
| **OptaPlanner / Timefold** (Java) | Constraint solver | When subtask scheduling is really a constraint-optimization problem |

LLM-with-PDDL hybrids exist: the LLM translates a natural-language goal into PDDL, a classical solver finds a provably valid plan, then the agent executes it. This trades flexibility for correctness guarantees.

---

## 5. Code examples by use case

These span different real scenarios. Java is the default; we use Python only where it's the lingua franca of a specific framework (LangGraph) so you recognize the canonical shape.

### 5.1 Use case: a static plan-and-execute DAG scheduler in pure Java

Scenario: you've gotten a plan from an LLM (as JSON) and must execute it respecting dependencies, with parallelism for independent steps. This is the core scheduler every Java planning agent needs.

```java
// Plan model -----------------------------------------------------------
record Step(String id, String tool, Map<String,Object> args,
            List<String> dependsOn) {}

record Plan(List<Step> steps) {}

// A pluggable tool registry: name -> function that takes resolved args
// (with dependency outputs already substituted) and returns a result.
interface Tool { Object run(Map<String,Object> args, Map<String,Object> depResults); }

// Scheduler -------------------------------------------------------------
class DagExecutor {
    private final Map<String, Tool> tools;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    DagExecutor(Map<String, Tool> tools) { this.tools = tools; }

    Map<String,Object> execute(Plan plan) throws Exception {
        // Index steps and build in-degree map (Kahn's algorithm).
        Map<String, Step> byId = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Step s : plan.steps()) {
            byId.put(s.id(), s);
            inDegree.putIfAbsent(s.id(), 0);
            for (String dep : s.dependsOn()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.id());
                inDegree.merge(s.id(), 1, Integer::sum);
            }
        }
        // Cycle check: total nodes we can ever schedule must equal node count.
        // (If a cycle exists, some node never reaches in-degree 0.)

        Map<String,Object> results = new ConcurrentHashMap<>();
        // Ready frontier = all steps with no remaining dependencies.
        Queue<String> ready = new ConcurrentLinkedQueue<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) ready.add(id); });

        int completed = 0;
        while (completed < plan.steps().size()) {
            // Snapshot the current frontier and run it in parallel.
            List<String> batch = new ArrayList<>();
            String id;
            while ((id = ready.poll()) != null) batch.add(id);
            if (batch.isEmpty())
                throw new IllegalStateException("Cycle or unsatisfiable plan"); // deadlock

            List<CompletableFuture<String>> futures = batch.stream().map(stepId -> {
                Step s = byId.get(stepId);
                return CompletableFuture.supplyAsync(() -> {
                    // Gather outputs of this step's dependencies.
                    Map<String,Object> depResults = new HashMap<>();
                    for (String d : s.dependsOn()) depResults.put(d, results.get(d));
                    Object out = tools.get(s.tool()).run(s.args(), depResults);
                    results.put(stepId, out);
                    return stepId;
                }, pool);
            }).toList();

            // Wait for the whole batch, then advance the frontier.
            for (CompletableFuture<String> f : futures) {
                String doneId = f.get();           // propagates exceptions
                completed++;
                for (String child : dependents.getOrDefault(doneId, List.of())) {
                    if (inDegree.merge(child, -1, Integer::sum) == 0) ready.add(child);
                }
            }
        }
        return results;
    }
}
```

Key points: in-degree bookkeeping is Kahn's algorithm; the empty-frontier-with-work-remaining check catches cycles/unsatisfiable plans at runtime; `CompletableFuture` gives free parallelism for the frontier; `f.get()` surfaces per-step exceptions so the caller can decide to retry or replan.

### 5.2 Use case: getting a *structured* plan out of an LLM (Spring AI, Java)

Scenario: ask the model to decompose a goal into the `Plan` record above, with guaranteed-parseable output.

```java
// Using Spring AI's ChatClient with structured output (entity mapping).
// The framework appends format instructions and parses the response into Plan.
@Service
class Planner {
    private final ChatClient chat;
    Planner(ChatClient.Builder b) { this.chat = b.build(); }

    Plan plan(String goal, String toolCatalog) {
        return chat.prompt()
            .system("""
                You are a planning module. Decompose the GOAL into the
                fewest subtasks that solve it. Each step picks exactly one
                tool from the CATALOG. Use dependsOn to express ordering.
                Do NOT invent tools. Prefer parallelizable steps.
                """)
            .user(u -> u.text("GOAL:\n{goal}\n\nCATALOG:\n{catalog}")
                        .param("goal", goal)
                        .param("catalog", toolCatalog))
            .call()
            .entity(Plan.class);   // <-- structured output -> POJO
    }
}
```

`.entity(Plan.class)` makes Spring AI inject schema/format instructions and deserialize; pair with a low `temperature` (set in the model options) so plans are stable. Always *validate* the returned `Plan` (tools exist, deps reference real ids, no cycles) before executing — never trust LLM output structurally.

### 5.3 Use case: dynamic plan-and-execute with replanning (LangGraph, Python — the canonical shape)

Scenario: closed-loop agent that replans after each step. Shown in Python because LangGraph is the reference implementation you'll be asked about.

```python
from langgraph.graph import StateGraph, END
from typing import TypedDict, List, Tuple

class PlanState(TypedDict):
    input: str
    plan: List[str]
    past_steps: List[Tuple[str, str]]   # (step, result)
    response: str

def plan_node(state):
    # One LLM call -> ordered list of steps.
    state["plan"] = planner_llm.invoke(state["input"])
    return state

def execute_node(state):
    step = state["plan"][0]                     # take the next step
    result = executor_agent.invoke(step)        # a small ReAct sub-agent
    state["past_steps"].append((step, result))
    return state

def replan_node(state):
    # Replanner sees objective + original plan + done steps + last result.
    decision = replanner_llm.invoke(state)
    if decision.is_final:
        state["response"] = decision.answer
    else:
        state["plan"] = decision.remaining_steps  # may differ from original
    return state

def should_end(state):
    return END if state.get("response") else "execute"

g = StateGraph(PlanState)
g.add_node("plan", plan_node)
g.add_node("execute", execute_node)
g.add_node("replan", replan_node)
g.set_entry_point("plan")
g.add_edge("plan", "execute")
g.add_edge("execute", "replan")
g.add_conditional_edges("replan", should_end)   # loop back or finish
app = g.compile()
```

The `replan` node is where static becomes dynamic: it can shorten, extend, or rewrite the remaining plan based on real results. The conditional edge implements the loop.

### 5.4 Use case: ReWOO — plan once, run tools, solve once (token-thrifty)

Scenario: a research question needing several lookups, where you want minimal LLM calls. Pseudocode emphasizing the *variable substitution* mechanism.

```python
# 1) PLANNER: one call -> steps with #E placeholders.
plan = planner_llm(goal)
# plan == [
#   ("E1", "Search", "GDP of Japan 2023"),
#   ("E2", "Search", "GDP of Germany 2023"),
#   ("E3", "LLM",    "Which is larger: #E1 or #E2? Explain."),  # depends on E1,E2
# ]

# 2) WORKER: execute tools, substituting prior evidence into args.
evidence = {}
for var, tool, arg in plan:
    resolved = substitute_placeholders(arg, evidence)  # #E1 -> evidence["E1"]
    evidence[var] = TOOLS[tool](resolved)
# E1 and E2 are independent -> can run concurrently.

# 3) SOLVER: one final call with the goal + all evidence.
answer = solver_llm(goal, evidence)
```

The model is called only at PLAN and SOLVE (and any explicit `LLM` tool steps), *not* once per tool round — that's the cost win. The cost is rigidity: the plan can't react to what `E1` actually returned.

### 5.5 Use case: orchestrator-worker with parallel subagents (Java, structured concurrency)

Scenario: a research orchestrator fans out independent subtopics to worker agents and joins their results. Uses JDK structured concurrency (JDK 21 preview / stabilizing in later JDKs).

```java
record SubResult(String topic, String finding) {}

class ResearchOrchestrator {
    private final Planner planner;          // LLM that lists subtopics
    private final WorkerAgent worker;       // self-contained sub-agent + tools
    private final Synthesizer synthesizer;  // LLM that composes the report

    String research(String question) throws InterruptedException {
        List<String> subtopics = planner.decompose(question);   // 1) plan

        List<SubResult> findings;
        // 2) fan out: each worker gets its OWN context + tools, runs in parallel.
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<SubResult>> tasks = subtopics.stream()
                .map(t -> scope.fork(() ->
                    new SubResult(t, worker.investigate(question, t))))
                .toList();
            scope.join();              // wait for all
            scope.throwIfFailed();     // if any worker failed, propagate
            findings = tasks.stream().map(StructuredTaskScope.Subtask::get).toList();
        }
        return synthesizer.compose(question, findings);          // 3) synthesize
    }
}
```

Each `worker.investigate(...)` is a full sub-agent with its own context window — the whole point of the pattern. `ShutdownOnFailure` cancels siblings if one worker hard-fails (you'd swap for a partial-results policy if workers are independent). Remember the ~15× token multiplier from Section 3.6 before reaching for this.

### 5.6 Use case: per-subtask retry + circuit breaking (Resilience4j, Java)

Scenario: tools are flaky (rate limits, transient network). Wrap each subtask so failures retry locally before triggering a replan.

```java
RetryConfig retryCfg = RetryConfig.custom()
    .maxAttempts(3)
    .intervalFunction(IntervalFunction.ofExponentialBackoff(
         Duration.ofMillis(500), 2.0))     // 0.5s, 1s, 2s
    .retryExceptions(IOException.class, TimeoutException.class)
    .ignoreExceptions(ToolValidationException.class)  // don't retry bad plans
    .build();

CircuitBreakerConfig cbCfg = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)              // open if >50% of calls fail
    .slidingWindowSize(20)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .build();

Retry retry = Retry.of("tool", retryCfg);
CircuitBreaker cb = CircuitBreaker.of("tool", cbCfg);

Supplier<Object> guarded = Retry.decorateSupplier(retry,
    CircuitBreaker.decorateSupplier(cb, () -> tool.run(args, depResults)));

Object result = guarded.get();   // retries, then trips breaker if tool is down
```

Local retry handles transient failures cheaply (no expensive replan); the circuit breaker stops you from hammering a dead tool across many subtasks; `ignoreExceptions` ensures *logic* errors (a malformed step) escalate to replanning instead of being retried pointlessly.

### 5.7 Use case: a verifier/grader gate between steps

Scenario: don't trust a step's output blindly; grade it before letting the plan proceed.

```java
interface Grader { boolean passes(Step step, Object output); }

// Example: a code-gen step must produce compilable Java.
Grader compilesGrader = (step, out) -> javaCompiler.compiles((String) out);

Object out = tool.run(...);
if (!compilesGrader.passes(step, out)) {
    // Escalation ladder: retry the step -> repair via LLM -> replan.
    throw new StepFailedException(step.id(), "did not pass grader");
}
```

Graders can be deterministic (compiles? schema-valid? tests pass?) or LLM-as-judge (a separate model scores quality 1–5). Deterministic graders are cheaper and more reliable — prefer them where the property is checkable.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **Critical path dominates wall-clock.** Parallelize the frontier (Section 5.1/5.5); the floor is the longest dependency chain (Section 2.4). Decomposing for *more parallelism* (wide, shallow DAGs) beats deep chains when steps are independent.
- **One big planning call beats many small reasoning rounds** when the plan is stable — that's ReWOO/plan-and-execute's edge over ReAct.
- **Model tiering**: strong/slow model for the planner, fast/cheap model for executors. Often 5–10× cost difference between flagship and small models, so this is the single biggest cost lever.
- **Cache the tool catalog / system prompt**: providers offer **prompt caching** (Anthropic, OpenAI), which can cut input-token cost on the repeated planner preamble by ~50–90% for the cached portion. Vendor-specific; verify discounts.
- **Streaming** the plan lets you start validating/scheduling early, but most schedulers wait for the full plan (you need the whole DAG to topo-sort).

### 6.2 Correctness & concurrency

- **Always validate the plan structurally** (tools exist, deps resolve, no cycles) before executing. LLMs hallucinate tool names and self-referential deps.
- **Idempotency**: design subtasks (and replanning) so re-running a step is safe. With retries and replanning, a step *will* sometimes run twice. Non-idempotent side effects (charge a card, send an email) need an idempotency key or a "human approval" gate.
- **Determinism for tests**: pin `temperature=0`, seed where supported, and record/replay LLM responses (golden transcripts) so plan tests aren't flaky.
- **Race conditions in shared state**: the blackboard is mutated by parallel workers — use concurrent collections (`ConcurrentHashMap`) or, better, give each worker an isolated scratch space and merge results at join points (as in 5.5).
- **Cycle prevention at generation time**: instruct the planner to emit a DAG and reject (don't auto-repair) cyclic plans — auto-repair masks a confused planner.

### 6.3 Memory / context management

- **Scope each executor's context** to its subtask + its dependencies' outputs only. Don't pass the whole transcript — that's the entire token-saving rationale of decomposition.
- **Summarize/compact** completed-step outputs before they enter the replanner's context (store the full artifact in the blackboard, pass only a summary to the LLM).
- **Per-worker context budgets**: orchestrator-worker multiplies total readable material; cap each worker's window and total fan-out to bound cost.

### 6.4 Security

- **Treat tool args as untrusted.** A planner can be steered by **prompt injection** (malicious text in retrieved content that hijacks the model's instructions) into planning destructive steps. Sandbox tools, allow-list operations, and require approval for irreversible actions.
- **Least privilege per subtask**: give each worker only the tools it needs (the executor for "summarize" should not have `delete_file`).
- **Plan review gate** for high-stakes domains: show the plan to a human or a policy engine before any side effect.
- **Resource caps**: max steps, max depth, max fan-out, max total tokens/cost — prevents a runaway planner from spawning unbounded work (a real DoS-on-your-own-wallet risk).

### 6.5 Cost

- **Planning has direct token cost.** A full replan-every-step loop can cost more than a monolithic prompt for short tasks — only pay for planning when the task is long/parallel/expensive enough to justify it.
- **ToT and multi-agent are the cost outliers**: ToT = `branch×depth×2` calls; multi-agent ≈ 15× single-agent tokens (Anthropic-reported). Gate them behind value thresholds.
- **Track cost per plan**: emit token counts per step as metrics (Section 6.6) and set budgets that abort runaway plans.

### 6.6 Observability

- **Trace hierarchy**: one trace per goal; spans for `plan`, each `step`/`tool`, and `replan`. Use **OpenTelemetry** plus an LLM-aware tool (**LangSmith**, **Langfuse**, **Arize Phoenix**) that captures prompts, completions, token counts, and latencies.
- **Log the plan as an artifact** (the DAG) so you can replay and diff plans across runs.
- **Key metrics**: steps per goal, replans per goal, step success rate, retries per step, tokens/cost per goal, wall-clock vs critical-path ratio (parallelism efficiency).

### 6.7 Testing

- **Unit-test the scheduler** with synthetic DAGs (parallel, deep, cyclic) — no LLM needed.
- **Golden-transcript tests** for the planner: record real LLM plans, assert structure (and re-record when prompts change).
- **Chaos-test executors**: inject tool failures/timeouts and assert the retry→replan ladder behaves.
- **Eval the planner's *plans*** (offline): score generated plans against reference plans for coverage and minimality.

### 6.8 Production hardening checklist

- Hard caps: max steps, max depth, max fan-out, max wall-clock, max cost.
- Timeouts on every tool and every LLM call.
- Idempotency keys for side-effecting steps.
- Human-approval gate for irreversible actions.
- Structured, validated plan output (never free-text parsing in prod).
- Circuit breakers around flaky tools.
- Full tracing with token/cost accounting.
- Durable execution (Temporal/Step Functions) for long plans that must survive restarts.

### 6.9 Anti-patterns (avoid)

| Anti-pattern | Why it bites | Fix |
|---|---|---|
| Over-decomposing trivial tasks | More planning tokens + latency than just answering | Reserve planning for genuinely multi-step work |
| Replanning from scratch every step | Thrashing, redone work, oscillation, cost blowup | Replan *incrementally* (keep valid future steps); replan on trigger, not always |
| Trusting free-text plans | Parse failures in prod | Structured output + validation |
| Unbounded recursion / fan-out | Runaway cost, DoS-your-wallet | Depth/fan-out/cost caps |
| One mega-prompt "do everything" | Context dilution, error compounding | Decompose with scoped contexts |
| No grader between steps | Errors propagate silently | Deterministic graders at boundaries |
| Multi-agent for everything | ~15× tokens for no benefit on serial tasks | Use single agent unless truly parallel/breadth-heavy |

---

## 7. Advanced topics & deep internals

### 7.1 Plan representation choices and their consequences

- **List vs DAG vs hierarchical tree.** Lists are easy for the LLM to emit but lose parallelism; DAGs unlock concurrency but the model must get dependency edges right (harder); hierarchical plans (plan-of-plans) localize complexity but add a layer of orchestration. A practical middle ground: have the planner emit a flat list with explicit `dependsOn`, then your code *derives* the DAG and parallelism — the model never has to reason about scheduling.
- **Plans as code.** Some systems have the planner emit *actual code* (Python) that calls tools as functions ("CodeAct" / PaL — Program-aided Language models / "Code as Policies"). The interpreter then provides loops, conditionals, and variable passing for free, and intermediate results stay in program variables rather than being re-serialized into the prompt. Powerful and token-efficient, but you must sandbox arbitrary generated code (security, Section 6.4).

### 7.2 Incremental vs full replanning (the thrashing problem)

A naive replanner that regenerates the whole plan can **oscillate**: plan A → execute → replan → plan B → execute → replan → back to plan A. Mitigations: (1) pass the *original plan* so the model diffs rather than rebuilds; (2) only replan the *remaining* suffix; (3) add hysteresis — require a clear failure/surprise signal before replanning; (4) cap total replans per goal.

### 7.3 Confidence, self-evaluation, and "reflection"

Patterns like **Reflexion** add a self-critique step: after a step/episode, the agent generates feedback ("the test failed because the regex was too greedy") stored in memory and fed into the next attempt. This is replanning informed by *introspective* signals rather than just tool errors. Internals: an `actor → evaluator → self-reflection → memory` loop, where reflections accumulate across attempts. Cost: extra LLM calls per episode; benefit: better recovery on tasks with verifiable outcomes (code, games).

### 7.4 Search-based planning knobs (ToT/MCTS)

When planning *is* search:

- **Beam width `b`** and **branching factor `k`**: total nodes ≈ `b×k×depth`. Tuning these is the cost/quality dial.
- **Value function quality**: the evaluator that scores partial solutions is the bottleneck — a bad evaluator prunes good branches. LLM-as-evaluator is noisy; prefer a deterministic checker when the domain allows.
- **MCTS (Monte Carlo Tree Search)** variants (e.g. "LATS — Language Agent Tree Search") add rollout + backpropagation: simulate to a terminal state, score it, and propagate value back up to guide which branch to expand next. Strictly more sample-efficient than naive BFS/DFS but more complex and costlier.

### 7.5 Reactive vs deliberative and the BDI lineage

Classical agent theory distinguishes **reactive** (stimulus→response, no internal plan; e.g. subsumption architecture) from **deliberative** (maintain a world model and plan over it). LLM agents are mostly deliberative. The **BDI model** (Belief-Desire-Intention) — beliefs (what the agent thinks is true), desires (goals), intentions (committed plans) — is a useful frame: the "intention" is exactly the current plan, and *intention reconsideration* is replanning. Committing too readily to an intention = static planning (cheap, brittle); reconsidering constantly = dynamic (robust, costly). The art is *bounded reconsideration*.

### 7.6 Interaction with structured-output reliability

Structured-output / constrained decoding (the provider forces tokens to match a JSON Schema/grammar) makes plans parse reliably but can subtly degrade reasoning quality if the schema is over-constraining — the model spends "effort" satisfying structure. Mitigation: let the model "think" in a free-text field *first* (a `reasoning` string in your schema), then emit the structured `steps`. This "reason-then-structure" trick preserves chain-of-thought while keeping output parseable.

### 7.7 Cost-aware and budget-bounded planning

Advanced planners are made *budget-aware*: the planner is told a token/dollar/time budget and asked to produce the cheapest plan meeting the goal, or to choose between a cheap-but-uncertain path and an expensive-but-sure one. Some systems implement an **anytime** property — produce a usable plan quickly, then refine if budget remains (borrowed from anytime algorithms in classical AI).

### 7.8 Lesser-known behaviors / gotchas

- **Planner over-fragments under verbose prompts**: long system prompts bias the planner toward more, smaller steps. Keep planner prompts tight.
- **Dependency hallucination**: planners sometimes add `dependsOn` edges that aren't real, accidentally serializing parallelizable work (silent latency cost). Audit DAG width vs the task.
- **Replanner anchoring**: passing the full original plan can over-anchor the replanner, making it reluctant to abandon a doomed plan. Balance with explicit "you may discard steps" instructions.
- **Tool-result formatting matters**: how you serialize a dependency's output into the next step's prompt strongly affects whether the next step succeeds — prefer compact, labeled, schema'd results over raw dumps.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Pattern comparison

| Pattern | Plan timing | Adaptivity | Parallelism | LLM calls (rough) | Best for | Avoid when |
|---|---|---|---|---|---|---|
| **ReAct** | None (improvise) | High (per step) | None (serial) | 1 per step | Short, exploratory tasks | Long horizons; need a plan artifact |
| **Plan-and-Execute (static)** | Upfront, once | None | Via DAG | 1 plan + 1/step | Stable multi-step pipelines | Outcomes uncertain mid-flight |
| **Plan-and-Execute (dynamic)** | Upfront + replan | High | Via DAG | 1 plan + 1/step + replans | Multi-step with surprises | Very short tasks (overhead) |
| **ReWOO** | Upfront, once | None (open-loop) | Yes (independent #E) | ~2 (+ explicit LLM steps) | Token-thrifty multi-lookup | Steps must react to results |
| **Tree-of-Thoughts** | Search tree | High (backtrack) | Across branches | `b×k×depth` (10s–100s) | Puzzles/search with verifiable states | Cost-sensitive; single pass suffices |
| **Orchestrator-Worker** | Upfront + dynamic | High | High (per worker) | ~15× single agent | Breadth-heavy parallel work | Serial tasks; tight budgets |

### 8.2 Use-when / avoid-when rules

- **Use explicit upfront planning when**: the task has ≥4 dependent steps; steps are expensive or irreversible (you want to review before spending); you need an auditable artifact or a human approval gate; or independent steps can be parallelized for latency.
- **Let the model improvise (ReAct) when**: the task is short (≤3 steps), cheap, and exploratory; or you genuinely can't know the next step until you see the previous result and there's nothing to parallelize.
- **Use ReWOO when**: token cost dominates, the toolset is known, and the plan doesn't need to react to intermediate observations.
- **Use ToT/search when**: a single forward pass demonstrably fails, the problem has a checkable/scorable structure, and you can afford 10–100× the calls.
- **Use orchestrator-worker when**: subtasks are genuinely independent and breadth-heavy (research, multi-file refactors, fan-out analysis) and the value justifies ~15× tokens.
- **Use classical/PDDL planning when**: the domain is fully specifiable and you need *provable* plan correctness, not LLM best-effort.

### 8.3 Static vs dynamic decision

Replan dynamically when: failures are common, the environment changes during execution, or early results genuinely change later steps. Stay static when: the environment is stable, steps are cheap to redo, and replanning cost exceeds the cost of occasionally running a now-stale step. Hybrid (replan-on-failure-only) is the pragmatic default.

---

## 9. Failure modes & debugging

### 9.1 Catalog of failure modes

| Failure | Symptom | Root cause | Diagnose with | Fix |
|---|---|---|---|---|
| **Plan won't parse** | Exception at deserialize | Free-text output, schema drift | Trace the raw planner completion | Structured output + schema validation |
| **Cyclic / unsatisfiable plan** | Scheduler deadlocks (empty frontier, work remains) | Hallucinated self-deps | Cycle check (Kahn) before execute; log the rejected DAG | Reject + reprompt planner |
| **Hallucinated tool** | "tool not found" at dispatch | Planner invented a tool | Validate tool names vs registry | Reject plan; constrain catalog in prompt |
| **Replan oscillation** | Same two plans alternate; goal never completes | Full-replan thrash, anchoring | Diff consecutive plans in trace; count replans | Incremental replan; replan cap; hysteresis |
| **Silent serialization** | Slow runs despite independent work | Hallucinated `dependsOn` edges | Compare DAG width to expected parallelism | Audit/trim deps; instruct "prefer parallel" |
| **Error propagation** | Wrong final answer, no error | No grader; bad step output flowed downstream | Inspect per-step outputs in trace | Add deterministic graders at boundaries |
| **Runaway cost/loop** | Token/cost spike; never finishes | No caps; recursive fan-out | Cost-per-goal metric, step counter | Hard caps on steps/depth/fan-out/cost |
| **Context overflow** | Provider 400/"context length exceeded" | Passing whole transcript to executors/replanner | Token-count spans | Scope/summarize contexts |
| **Idempotency bug** | Duplicate side effects (double email/charge) | Retry/replan re-ran a side-effecting step | Audit logs of side-effecting tools | Idempotency keys; approval gate |
| **Prompt-injection hijack** | Plan contains unexpected destructive steps | Malicious content steered planner | Review plan; flag steps touching sensitive tools | Sandbox, allow-list, human gate |

### 9.2 Debugging workflow & tools

1. **Pull the trace** (LangSmith/Langfuse/Phoenix or OTel). Inspect the `plan` span: is the plan structurally sound? Does it cover the goal?
2. **Replay the plan deterministically** against your scheduler with recorded tool outputs — separates planner bugs from executor/tool bugs.
3. **Check the DAG**: width (parallelism), depth (critical path), and any surprising edges (silent serialization or missing deps).
4. **Inspect per-step I/O**: was each step's input correctly assembled from dependency outputs? Did graders pass?
5. **Count replans and diff plans**: oscillation shows up as repeated plan content.
6. **Watch caps/metrics**: tokens, cost, steps, retries per goal vs your budgets.

CLI/observability commands you'll actually run: OTel collector dashboards; framework-specific trace UIs (`langsmith` web UI, `langfuse` self-hosted); for durable workflows, `temporal workflow show -w <id>` to inspect the execution history and where it retried/failed.

### 9.3 Representative real-world incident shapes

- **The "infinite research" runaway**: an orchestrator with no fan-out cap spawned workers that spawned workers; token spend spiked before a budget alert caught it. Fix: depth + fan-out + total-cost caps (Anthropic and others repeatedly flag unbounded multi-agent spend as the top operational risk; the ~15× token multiplier makes caps non-negotiable).
- **The "stale step" wrong answer**: a static plan computed a downstream step from data a now-changed earlier step invalidated; no replan trigger. Fix: replan-on-surprise or grader gate.
- **The "phantom dependency" slowdown**: a planner serialized 6 independent lookups into a chain via hallucinated deps, turning a 1-round job into 6 rounds. Fix: dependency audit + planner instruction.

---

## 10. Interview drill

**Q1. Why decompose a complex goal instead of asking the model to do it in one prompt?**
*Model answer:* Error compounding (per-step reliability multiplies, so long chains fail), context-window pressure and attention dilution ("lost in the middle"), and the lack of native control flow — decomposition gives you scoped contexts, local retry/verification at boundaries, parallelism, and an inspectable plan artifact.
- *Probe: When is decomposition net-negative?* For ≤3 cheap steps the planning tokens/latency exceed the benefit; just answer or run a short ReAct loop.
- *Probe: How does decomposition improve reliability quantitatively?* Local verification turns a global `p^n` failure into per-step retries, so you recover at each boundary instead of restarting the whole chain.
- *Probe: What's the token tradeoff?* You add a planning call but shrink each executor's context; net depends on task length and parallelism.

**Q2. Contrast plan-and-execute, ReWOO, and ReAct in LLM-call cost.**
*Model answer:* ReAct = ~1 call per step with the full growing transcript each time (most expensive on long tasks). Plan-and-execute = 1 plan call + 1 per step (+ replans if dynamic), with scoped per-step contexts. ReWOO = ~2 calls total (plan + solve) regardless of tool count because tools run without re-invoking the model — cheapest, but open-loop.
- *Probe: Why is ReWOO open-loop a problem?* It can't adapt step 2 to step 1's actual result.
- *Probe: Where does plan-and-execute's cost win come from?* One expensive planning call + cheap executors vs forcing the big model to re-reason the whole journey every step.

**Q3. How do you represent a plan, and how do you schedule it?**
*Model answer:* As a DAG — nodes are subtasks, edges are dependencies. Validate (no cycles, tools exist, deps resolve), topo-sort with Kahn's algorithm, and run the zero-in-degree frontier in parallel, decrementing in-degrees as steps finish. The critical path bounds minimum wall-clock.
- *Probe: How detect a cycle at runtime?* Empty ready-frontier while work remains ⇒ cycle/unsatisfiable.
- *Probe: What bounds latency with infinite workers?* The critical path (longest dependency chain).

**Q4. Static vs dynamic planning — when each?**
*Model answer:* Static (open-loop) when the environment is stable and steps are cheap to redo; dynamic (closed-loop replanning) when failures are common or results change later steps. Pragmatic default: replan only on failure/surprise (hybrid), with a replan cap.
- *Probe: A failure mode of naive dynamic replanning?* Oscillation/thrashing from full-replan-every-step; fix with incremental replan + hysteresis + caps.

**Q5. Walk me through orchestrator-worker and its cost.**
*Model answer:* A lead agent decomposes the goal and spawns workers, each with its own context window and tools, running in parallel; the lead synthesizes results. It multiplies total readable context (N windows vs 1) but costs roughly ~15× single-agent tokens (Anthropic-reported), so reserve it for breadth-heavy, parallelizable, high-value work.
- *Probe: Why N context windows matter?* A single agent has one budget; workers collectively read far more.
- *Probe: How prevent runaway cost?* Depth/fan-out/total-cost caps and a budget that aborts.

**Q6. (Senior signal) You're designing an agent to refactor a 200-file service. Plan the architecture and justify.**
*Model answer:* Orchestrator-worker with a DAG: planner produces a per-module DAG (independent modules parallel, shared-interface changes serialized). Workers get scoped contexts (only their files + interface contracts), deterministic graders (must compile + tests pass) at each boundary, retry→repair→replan ladder, idempotent edits via patch files, human-approval gate before merge, hard caps on cost/steps, full tracing. Justify: independence ⇒ parallelism; graders catch error propagation; caps and approval handle the irreversibility/cost risk.
- *Probe: Static or dynamic?* Hybrid — replan on compile/test failure, else proceed.
- *Probe: Where's the critical path?* Shared-interface changes that downstream modules depend on; widen the DAG everywhere else.

**Q7. (Senior signal) When would you NOT use an LLM planner at all?**
*Model answer:* When the domain is fully specifiable and correctness must be guaranteed — use classical/PDDL planning (preconditions/effects, a solver) or a constraint solver (OptaPlanner/Timefold). LLMs give best-effort plans; solvers give provable ones. Hybrid: LLM translates NL→PDDL, solver plans, agent executes.
- *Probe: Downsides of PDDL?* Requires a fully modeled domain; brittle to unmodeled reality; expressiveness limits.

**Q8. (Senior signal) Your dynamic agent's cost doubled overnight with no code change. Diagnose.**
*Model answer:* Likely replan oscillation or a fan-out/recursion blowup. Pull traces: count replans per goal and diff consecutive plans (oscillation), check DAG fan-out/depth (recursion), inspect token-per-step (context bloat from un-summarized results). Probable triggers: an upstream tool started failing (more retries→replans), a content change induced prompt-injection-like steering, or a model/version change altered plan verbosity. Fix: caps, incremental replanning, summarization, and a cost-per-goal alert.
- *Probe: First metric you'd check?* Replans-per-goal and tokens-per-goal trend.

**Q9. How do you keep LLM-generated plans parseable without hurting reasoning?**
*Model answer:* Use structured output (JSON Schema / tool-calling) for the plan, but include a free-text `reasoning` field the model fills *first*, then the structured `steps` — preserving chain-of-thought while guaranteeing parseability. Always validate the deserialized plan.
- *Probe: Risk of over-constraining the schema?* The model spends effort satisfying structure and reasons worse; the reason-then-structure split mitigates it.

**Q10. How do you handle a step that fails mid-plan?**
*Model answer:* Escalation ladder: local retry with backoff (transient), then LLM repair of the step, then replan the remaining suffix, then escalate to a human. Use circuit breakers for dead tools and graders to detect "succeeded but wrong." Distinguish transient (retry) from logic errors (replan, don't retry).
- *Probe: How avoid double side effects on retry?* Idempotency keys / approval gates for irreversible steps.

**Q11. (Senior signal) Justify Tree-of-Thoughts vs chain-of-thought for a given problem.**
*Model answer:* ToT pays `b×k×depth` calls to explore and backtrack; justify it only when a single forward pass demonstrably fails and the problem is *searchable with a usable value function* (puzzles, constrained generation). If a one-shot/CoT pass solves it, ToT is pure waste. The value-function quality is the deciding factor — a noisy evaluator makes ToT no better than random sampling.

**Q12. What metrics prove your planning agent is healthy?**
*Model answer:* Step success rate, replans per goal (low and stable), retries per step, tokens/cost per goal (within budget), and wall-clock vs critical-path ratio (parallelism efficiency near 1 means you're scheduling well). Spikes in replans or cost are the leading indicators of regressions.

---

## 11. Glossary

- **Agent**: an LLM wrapped in an observe-decide-act loop that can call tools.
- **Anytime algorithm**: one that yields a usable answer quickly and improves it given more time/budget.
- **Attention dilution / "lost in the middle"**: degraded model recall of info buried mid-prompt.
- **BDI (Belief-Desire-Intention)**: agent model where intentions = committed plans; reconsidering them = replanning.
- **Beam search / beam width**: keep the best *b* partial solutions per search level.
- **BFS / DFS**: breadth-first (level by level) / depth-first (deep then backtrack) search.
- **Blackboard**: shared data structure multiple components read/write; the agent's shared state.
- **Branching factor (k)**: candidates generated per node in tree search.
- **Circuit breaker**: trips "open" after a failure-rate threshold to stop hammering a dead dependency.
- **Closed-loop / open-loop**: plan that does (closed) or doesn't (open) react to execution results.
- **Context window**: max tokens a model can attend to at once.
- **Critical path**: longest dependency chain; the wall-clock floor with infinite parallelism.
- **DAG**: directed acyclic graph; nodes=subtasks, edges=dependencies, no cycles.
- **Decomposition**: splitting a goal into subtasks.
- **Deterministic grader**: a checkable pass/fail test of a step's output (compiles? tests pass?).
- **Frontier (ready set)**: subtasks whose dependencies are all satisfied — runnable now.
- **HTN (Hierarchical Task Network)**: classical planning that expands compound tasks into primitives via decomposition methods.
- **Idempotency**: re-running an operation has the same effect as running it once.
- **In-degree**: number of unsatisfied dependencies a node has (Kahn's scheduler key).
- **Kahn's algorithm**: topological sort/scheduling by repeatedly removing zero-in-degree nodes.
- **LATS**: Language Agent Tree Search; MCTS-style planning for LLM agents.
- **LLM**: large language model; next-token predictor used via prompts.
- **LLM-as-judge**: using a model to score another model's output.
- **MCTS (Monte Carlo Tree Search)**: search via rollout + value backpropagation.
- **Means-ends analysis**: pick actions that reduce the gap between current and goal state.
- **MVCC** (mentioned as adjacent): multi-version concurrency control — DB technique giving each transaction a consistent snapshot; relevant only if your tools hit such a DB.
- **Orchestrator-worker (supervisor)**: lead agent decomposes and delegates to parallel sub-agents, then synthesizes.
- **PDDL**: Planning Domain Definition Language for classical planners.
- **Plan-and-Execute**: plan the whole task upfront, then execute (optionally replanning).
- **Planner / Executor split**: separate the component that makes the plan from the one that runs steps.
- **Prompt caching**: provider feature reducing input-token cost on repeated prompt prefixes.
- **Prompt injection**: malicious text that hijacks the model's instructions.
- **ReAct**: Reason+Act loop; improvised, no explicit plan.
- **Reflexion**: self-critique loop that stores feedback in memory to improve next attempts.
- **Replanning**: revising the plan mid-execution based on results/failures.
- **ReWOO**: Reasoning WithOut Observation; plan→work→solve, ~2 LLM calls, token-thrifty, open-loop.
- **Resilience4j**: Java library for retry, circuit breaker, rate limiter, bulkhead.
- **STRIPS**: classical planner modeling actions as precondition/effect operators.
- **Structured concurrency**: JDK feature (`StructuredTaskScope`) treating concurrent subtasks as a unit with joined lifetimes.
- **Structured output**: provider-enforced schema-conformant model output.
- **Subtask / step**: a unit of work small enough to complete reliably.
- **Temperature**: sampling randomness; low = stable/deterministic.
- **Temporal**: durable workflow engine (Java SDK) for resumable long-running plans.
- **Token**: the LLM's unit of text/billing (~¾ word).
- **Tool / function calling**: structured model request to run host code.
- **Topological sort**: ordering of DAG nodes respecting all dependencies.
- **Tree-of-Thoughts (ToT)**: planning as a searched tree of partial solutions with evaluation/pruning.
- **Value function**: scores how promising a partial solution is (drives pruning in search).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core idea:** turn a goal into a validated DAG of subtasks; schedule the zero-dependency frontier in parallel; verify each boundary; replan on failure/surprise.

**Patterns (cost ↑):** ReAct (1/step, serial) → Plan-and-Execute (1 plan + 1/step) → ReWOO (~2 calls, open-loop, cheap) → Tree-of-Thoughts (`b×k×depth`, search) → Orchestrator-Worker (~15× tokens, parallel breadth).

**Scheduling:** DAG + Kahn's algorithm; frontier = in-degree 0; **critical path** = wall-clock floor; cycle = empty frontier with work left.

**Static vs dynamic:** static = plan once (cheap, brittle); dynamic = replan (robust, costly); **default = replan on failure/surprise only** with a replan cap.

**Always:** structured output + validate plan (tools exist, deps resolve, no cycles); scope each executor's context; deterministic graders at boundaries; retry→repair→replan ladder; idempotency for side effects; hard caps (steps/depth/fan-out/cost); full tracing with token accounting.

**Cost levers:** model tiering (strong planner / cheap executors), prompt caching, parallelize independent steps, don't over-decompose, gate ToT/multi-agent behind value thresholds.

**Numbers to remember:** `0.9^10 ≈ 0.35` (error compounding); multi-agent ≈ ~15× single-agent tokens (Anthropic-reported, workload-specific); ToT ≈ `branch × depth × 2` calls.

**JVM toolkit:** Spring AI / LangChain4j (LLM+tools), JGraphT (DAG), `ExecutorService`/`StructuredTaskScope` (parallel frontier), Resilience4j (retry/breaker), Temporal (durable), OpenTelemetry+Langfuse/LangSmith (tracing), Semantic Kernel (Java planners). LangGraph (Python) = canonical Plan-and-Execute/ReWOO/ToT reference.

### 12.2 Self-test (no answers — recall practice)

1. Derive why a 12-step pipeline of 92%-reliable steps is unreliable end-to-end, and explain two mechanisms decomposition uses to fix it.
2. Given a plan with edges A→C, B→C, C→D, A→E, list the execution rounds with infinite workers and identify the critical path.
3. Explain precisely why ReWOO uses ~2 LLM calls regardless of tool count, and the single capability it sacrifices to get there.
4. You observe high replans-per-goal and oscillating plan content. Name the failure mode and give three fixes.
5. Justify, with numbers, when orchestrator-worker is worth its token cost — and give one task where it is clearly the wrong choice.
6. Describe the runtime signal that proves a generated plan contains a cycle, and where in the lifecycle you should detect it.
7. Design the retry→replan escalation ladder for a step that performs an irreversible side effect, naming the mechanism that prevents duplicate effects.

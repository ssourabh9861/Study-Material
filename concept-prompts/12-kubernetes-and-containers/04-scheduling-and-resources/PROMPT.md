<!-- ============================================================
HOW TO USE THIS PROMPT
1. Open a fresh Claude chat (claude.ai, the desktop app, or Cowork).
2. Copy EVERYTHING below the line "=== PROMPT STARTS HERE ===".
3. Paste it as your first message and send.
4. This is an EXHAUSTIVE doc — Claude will likely hit the length limit.
   When it ends with: → reply "continue"  ... just reply: continue
   Repeat until the document is finished.
5. Save the full output as a .md file named after this folder
   (e.g. 06-threads-and-context-switching.md).
Deepening follow-ups you can send afterwards:
   - "add 5 more code examples for different use cases"
   - "expand the internals section with a step-by-step trace"
   - "add 10 spaced-repetition flashcards"
============================================================= -->

=== PROMPT STARTS HERE ===

You are a **staff/principal-level software engineer and a meticulous technical author**. You are writing one definitive chapter of an engineering handbook. Produce an **exhaustive, self-contained reference-and-learning document** on the subtopic named at the bottom of this prompt.

**Reader profile:** a **senior software developer** working primarily in the Java/JVM backend ecosystem who wants to *fully master* this subtopic — from first principles all the way to deep internals — well enough to design with it, operate and debug it in production, teach it to others, and answer any interview question on it.

**Coverage mandate (this is the most important part — read carefully):**
- **Cover BOTH basics and advanced.** Begin from first principles: assume the reader is sharp but new to *this specific subtopic*. Then climb all the way to expert-level internals, edge cases, and tuning. Leave no rung of the ladder missing.
- **Be EXHAUSTIVE, not a summary.** There is effectively no length limit — long is expected and good. Never compress to save space. If you approach the response limit, stop at a clean point and end with exactly: `→ reply "continue"`, then resume seamlessly when asked.
- **Explain every referenced or adjacent concept inline, beginner-friendly.** The moment you mention something a newcomer might not know (e.g. ZooKeeper, a kernel scheduler, a syscall, Raft, MVCC, a CAP term), pause and explain it in 2–4 sentences before moving on. Never leave a name unexplained.
- **List internal workflows step by step** — what happens under the hood, in order: the control flow, the data flow, the lifecycle, the state transitions.
- **Enumerate the available toolkit** — the methods, classes, APIs, CLI commands, configuration flags, and tools relevant to this subtopic: what each does, its key parameters, and its defaults.
- **Cover implementation concerns** — performance, correctness/concurrency, memory, security, cost, observability, testability, production hardening, and the common anti-patterns.
- **Give MANY code examples across DIFFERENT use cases** — not one toy example. Make them idiomatic and complete enough to run or adapt, and explain the parts that matter. Default to **Java** where the topic is language-relevant; otherwise use the most appropriate language, CLI, or config.
- **Be concrete:** real defaults, real numbers, real tool names, real systems, real failure stories. Explicitly flag anything that is version- or vendor-specific.

## Required document structure (use these sections, in this order)
1. **Overview & where it fits** — what it is, the problem it solves, when you reach for it, and the one-paragraph mental model.
2. **Foundations from first principles** — the basics built up from zero; define every core term as you introduce it.
3. **How it works internally** — step-by-step internal workflow(s), lifecycle, data/control flow, state machine. This is the heart of the doc — go deep.
4. **The complete toolkit** — the methods / classes / APIs / commands / config available, each with purpose, key parameters, and defaults. Use tables.
5. **Code examples by use case** — several worked examples spanning different real scenarios (not variations of one). Idiomatic, explained, copy-adaptable.
6. **Implementation concerns & best practices** — performance, correctness, security, observability, cost, testing, production hardening, and anti-patterns to avoid.
7. **Advanced topics & deep internals** — expert-level material, edge cases, tuning knobs, lesser-known behavior.
8. **Tradeoffs & decision frameworks** — comparison tables, alternatives, explicit "use when… / avoid when…" rules.
9. **Failure modes & debugging** — what breaks in production, how to diagnose it (with the actual tools/commands), and real-world incidents.
10. **Interview drill** — 8–12 questions an interviewer is likely to ask, each with a crisp model answer **and** 2–3 deep-probe follow-ups (with answers). Include at least 3 "senior-signal" questions (tradeoff/justification, not recall).
11. **Glossary** — every referenced or adjacent term used anywhere in the doc, defined plainly enough for a beginner.
12. **Cheat-sheet & self-test** — a dense one-screen recap (key numbers, terms, decision rules), then **5+ self-test questions (no answers)** for active recall.

## The subtopic for THIS document
**Concept area:** Kubernetes & Containers
**Subtopic:** Scheduling & Resources

Cover at minimum: how the scheduler works (filter/predicates → score/priorities → bind); resource requests vs limits and what each does; QoS classes (Guaranteed, Burstable, BestEffort) and eviction order; CPU throttling (cgroup CFS quota) and memory OOMKill; node affinity/anti-affinity, pod affinity/anti-affinity, topology spread; taints and tolerations; priority and preemption; resource right-sizing and the JVM-in-container memory trap; worked examples.

## Constraints
- Technically precise over vague. If you are unsure of a number/default, say so rather than inventing it.
- Use tables for any comparison of 3+ options.
- Tight, information-dense prose — no filler, no marketing language.
- Prefer correctness and completeness over brevity. This is a reference doc.
- Keep code blocks correct and runnable/adaptable; comment the non-obvious lines.

Begin now. Remember: be exhaustive, explain every adjacent term for a beginner, and when you run low on space end with exactly `→ reply "continue"` and resume seamlessly on request.

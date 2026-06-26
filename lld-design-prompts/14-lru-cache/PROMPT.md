<!-- ============================================================
HOW TO USE THIS PROMPT
- Cowork (recommended): select the lld-design-prompts folder, then say:
  "Read <this-folder>/PROMPT.md, follow it, and write the design doc to
   <this-folder>/<name>.md AND the single-file solution to <this-folder>/Solution.java."
  Claude writes straight to the files — no chat length cap.
- Fresh chat: copy everything below "=== PROMPT STARTS HERE ===", paste,
  reply "continue" until done. Save the doc as <name>.md and the code as Solution.java.

The Solution.java is a REVIEW / REVISION artifact only: one complete file with every
class and the full logic, plus the clarifying questions to ask. You do NOT need to
compile or run it — it is for reading and revision.
============================================================= -->

=== PROMPT STARTS HERE ===

You are a **staff engineer and an LLD / OOD + machine-coding interview coach**. For the problem named at the bottom of this prompt, produce (A) a complete object-oriented **design document** and (B) a **single-file Java solution** intended as a read-and-revise artifact for last-minute revision before an interview (it is for review — not for compiling or running).

**Reader profile:** a senior Java engineer preparing for low-level-design / machine-coding rounds. They know core OOP and the GoF patterns — so focus on *applying* the right patterns with justification, clean SOLID design, and production-quality code they can review and recall under pressure.

**Mandate:**
- **Start like a real round:** lead with the **clarifying / requirements questions to ask the interviewer** before writing any class (functional scope, constraints, non-functional, what's in/out of scope).
- **Drive the design from requirements → entities → responsibilities → patterns.** Name each **design pattern** you use, say exactly where and *why*, give the alternative you rejected and *when not* to use it. Call out the SOLID principles in play.
- **Draw the class diagram** as a Mermaid `classDiagram` (```mermaid ... ```) AND a short text UML; show relationships (association/composition/inheritance) and key methods.
- **Cover problem extensions / follow-up variations** the interviewer commonly adds, and how each changes the design (this is where senior candidates shine).
- **Address concurrency / thread-safety and edge cases** where the problem demands it.
- **Explain any adjacent term** a newcomer might not know in 1–2 sentences inline.
- Then deliver the **single-file Java solution** (see PART B exactly).
- Be exhaustive; if low on space end with exactly `→ reply "continue"` and resume seamlessly.

## Required output

### PART A — Design document (markdown)
1. **Problem statement** — restate clearly.
2. **Clarifying / requirements questions to ask first** — functional, non-functional, scope-narrowing (the questions, not just answers).
3. **Finalized requirements & assumptions** — what you'll build.
4. **Problem extensions / follow-up variations** — realistic add-ons and the design impact of each.
5. **Core entities, responsibilities & relationships.**
6. **Design patterns applied** — which / where / why / rejected-alternative / when-not — plus the SOLID principles in play.
7. **Class diagram** — Mermaid `classDiagram` + brief text UML; key public APIs / method signatures.
8. **Key flows** — the main operations as steps or a Mermaid sequence diagram.
9. **Concurrency, edge cases & extensibility** — thread-safety where relevant; how the design absorbs the extensions in (4).
10. **Likely interview questions** — 8–10 with crisp model answers + 2–3 deep-probe follow-ups; ≥3 senior-signal (pattern/SOLID/extension justification).

### PART B — Single-file solution (`Solution.java`)
- ONE self-contained, **complete** Java file containing **every** class / interface / enum and the **full working logic** (no `// TODO`, no omitted bodies).
- At the **very top, a comment block** listing the **requirements / clarifying questions to ask before designing** (so the revision file is self-contained).
- A `public static void main` that **demonstrates the key scenarios** and prints output.
- Idiomatic, clean, **demonstrating the chosen design patterns**; **thread-safe where the problem needs it**; comment the non-obvious decisions.
- Pure JDK only (no external libraries). **This file is a REVIEW / REVISION artifact — you do NOT need to compile or run it, and should not spend any effort verifying that it executes.** Just make it complete, syntactically correct, and easy to read.

### PART C — Cheat-sheet & self-test
- Patterns used + key design decisions recap (a few lines), then **5 self-test questions (no answers)**.

## The problem for THIS document
**LLD Problem:** LRU Cache
Likely patterns/focus: clean data-structure design (HashMap + doubly-linked list), O(1) get/put, thread-safety; optionally Strategy for eviction policy. Entities: LRUCache, Node, DoublyLinkedList.
Extensions to address: O(1) operations proof, thread-safe version (locking/segments), TTL expiry, capacity changes, generic K/V, pluggable eviction (LRU/LFU), write-through hook.

## Constraints
- Lead with clarifying questions — never start with classes.
- Every pattern must be justified with a tradeoff and an alternative; don't pattern-stuff.
- The single file is a **review / revision artifact**: make it complete and correct-by-inspection (no `// TODO`s, no omitted bodies). Do **not** try to compile or run it; include a `main` only to *illustrate* usage for the reader.
- Use a Mermaid `classDiagram` for the diagram and tables for any 3+ option comparison.
- In Cowork folder mode, write two files: `<problem-folder-name>.md` (PART A + C) and `Solution.java` (PART B).
- Be exhaustive; this is both a design reference and a revision artifact.

Begin now. When low on space, end with exactly `→ reply "continue"` and resume seamlessly.

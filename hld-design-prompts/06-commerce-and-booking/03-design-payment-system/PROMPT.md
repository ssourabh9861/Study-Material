<!-- ============================================================
HOW TO USE THIS PROMPT
- Cowork (recommended): select the hld-design-prompts folder, then say:
  "Read <this-folder>/PROMPT.md, follow it, and write the full design to <this-folder>/<name>.md. Be exhaustive."
  Claude writes straight to the file, so there is no chat length cap.
- Fresh chat: copy everything below "=== PROMPT STARTS HERE ===", paste,
  and reply "continue" until the design is complete. Save as <name>.md.
============================================================= -->

=== PROMPT STARTS HERE ===

You are a **staff/principal-level engineer and a system-design (HLD) interview coach**. Produce a **complete, interview-ready high-level design** for the system named at the bottom of this prompt, at the depth and rigor expected in a **senior/staff system-design round** at a top-tier company.

**Reader profile:** a senior backend engineer (Java/JVM, distributed systems) practising HLD. They know the building blocks (load balancers, caches, queues, sharding, replication) — so teach the *design judgment*: requirements clarification, tradeoffs, and the deep dives that separate a senior answer from a junior one.

**Mandate:**
- **Start like a real interview:** lead with the **clarifying / requirements questions you would ask the interviewer** before designing anything (functional, non-functional, scale), then state the assumptions you'll proceed with.
- **Be concrete with numbers** — do real back-of-the-envelope estimation (QPS, storage, bandwidth, server/shard counts).
- **Draw the architecture** — include both an **ASCII block diagram** and a **Mermaid diagram** (```mermaid ... ```), plus sequence diagrams for key flows where useful.
- **Deep-dive the hard parts** — spend most of the doc on the 3–5 genuinely difficult sub-problems, each with explicit tradeoffs and a defended decision (name the failure mode each choice avoids).
- **Explain every adjacent term** a newcomer might not know, in 1–2 sentences inline.
- Cover **scaling, bottlenecks, reliability, consistency, and security**, and the **extensions/follow-ups** an interviewer will push.
- Be exhaustive; if you run low on space end with exactly `→ reply "continue"` and resume seamlessly.

## Required document structure (in this order)
1. **Problem & clarifying questions** — restate the problem, then the requirements questions you'd ask the interviewer first (functional, non-functional, scale, out-of-scope).
2. **Requirements (finalized)** — functional + non-functional (latency, availability, consistency, durability) + explicit assumptions.
3. **Capacity estimation** — QPS (read/write), storage, bandwidth, memory, server/shard counts — with the arithmetic shown.
4. **API design** — the key endpoints/RPCs with signatures and the main request/response shapes.
5. **High-level architecture** — components and how requests flow; include an ASCII diagram **and** a Mermaid diagram.
6. **Data model & storage choices** — schema/entities, and *which datastore and why* (justify against the access patterns).
7. **Deep dives** — the 3–5 hardest sub-problems, each with options, a tradeoff table, and a defended decision. This is the bulk of the doc.
8. **Scaling & bottlenecks** — how it scales, where it breaks first, and how you remove each bottleneck.
9. **Reliability, consistency & security** — failure handling, replication/consistency model, idempotency, auth, abuse/rate limiting.
10. **Extensions & follow-ups** — realistic variations the interviewer may add and how each changes the design.
11. **Interview Q&A** — 8–10 likely questions with crisp model answers + 2–3 deep-probe follow-ups; ≥3 senior-signal (tradeoff/justification) questions.
12. **Cheat-sheet & self-test** — dense recap (key numbers, decisions, diagram-in-words) + 5 self-test questions (no answers).

## The system/topic for THIS document
**Category:** Commerce & Booking
**System:** Design a Payment System

Focus deep dives on: the payment flow (authorize/capture/settle); **idempotency** and exactly-once money movement; the **ledger** (double-entry, immutable) and reconciliation; integrating PSPs/banks (async callbacks, the 30–60s problem); the saga for distributed payment steps; consistency (strong, no lost/duplicate charges); fraud checks boundary; PCI scope; retries and stuck-transaction recovery.

## Constraints
- Lead with requirements clarification — never jump straight to boxes-and-arrows.
- Show the estimation arithmetic; cite realistic numbers and flag assumptions.
- Use tables for any comparison of 3+ options; use Mermaid for diagrams.
- Tight, senior-level prose. Defend every major decision with a tradeoff and the failure mode it avoids.
- Be exhaustive; this is a reference + practice artifact.

Begin now. When low on space, end with exactly `→ reply "continue"` and resume seamlessly.

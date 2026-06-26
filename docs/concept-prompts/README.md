# Senior Engineer Handbook — Prompt Library

A library of **165 self-contained prompts** that generate an exhaustive, first-principles-to-internals study document for every core backend / distributed-systems / JVM / cloud / AI topic a **senior software developer** should master.

Each prompt is written for a fresh Claude chat and produces one deep, book-chapter-grade reference doc that covers the basics **and** the advanced internals, lists every method/tool, walks the internal workflows, explains every adjacent term for a beginner, and gives many code examples per use case.

## Layout
```
sde3-concept-prompts/
├── _MASTER-PROMPT.md     ← the shared persona + doc spec + full themes map (also runnable: makes a study roadmap)
├── _INDEX.md             ← all 165 topics with checkboxes (your progress tracker)
├── README.md             ← this file
└── NN-concept-area/
    └── NN-subtopic/
        └── PROMPT.md      ← open, paste into a fresh chat, save output as NN-subtopic.md
```

## How to use
1. Open any `NN-concept/NN-subtopic/PROMPT.md`.
2. Copy everything below `=== PROMPT STARTS HERE ===` into a fresh Claude chat.
3. These docs are exhaustive — when Claude ends with `→ reply "continue"`, reply `continue` until finished.
4. Save the full output as `NN-subtopic.md` in that folder.
5. Tick the box in `_INDEX.md`.

> Every prompt is fully self-contained — persona, depth mandate, and structure are embedded — so any prompt runs standalone in any fresh Claude chat with zero setup.

## What each generated document contains
Overview & mental model · foundations from first principles · **how it works internally (step-by-step)** · the complete toolkit (methods/APIs/config with defaults) · **many code examples across different use cases** · implementation concerns & best practices · advanced internals · tradeoffs & decision frameworks · failure modes & debugging · interview drill (with senior-signal questions) · **glossary of every referenced term (beginner-friendly)** · cheat-sheet + self-test.

## The 19 concept areas
1. Distributed Systems Foundations · 2. Distributed Transactions & Consistency · 3. Scalability & Architecture Patterns · 4. Resilience & Fault Tolerance · 5. Database Paradigms & Selection · 6. Database Scaling & Partitioning · 7. Caching & In-Memory Stores · 8. Kafka & Message Brokers · 9. Java Language & Concurrency *(incl. threads, executors, context switching, virtual threads)* · 10. JVM Internals & GC · 11. Spring & Hibernate · 12. Kubernetes & Containers · 13. Observability & SRE · 14. Security for Backend Systems · 15. API Design & Management · 16. CI/CD & Release Engineering · 17. AI & LLM Foundations · 18. MCP (Model Context Protocol) · 19. Agentic AI & Agents.

See `_INDEX.md` for the full subtopic breakdown.

## Suggested study order
Start with the fundamentals that everything else rests on — **01 Distributed Systems Foundations**, **05 Database Paradigms**, **09 Java Language & Concurrency** — then go breadth-first across the rest, or run `_MASTER-PROMPT.md` to generate a dependency-ordered roadmap and a 12-week / 4-week plan.

Don't just read the generated docs — for each, attempt the **interview drill** and **self-test** with answers covered, and revisit them a few days later (spaced repetition).

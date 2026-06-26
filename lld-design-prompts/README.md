# LLD — Object Design & Machine-Coding Prompt Library

38 self-contained prompts. Each generates (A) a complete **object-oriented design doc** — clarifying questions, requirements, problem extensions, design patterns + a Mermaid class diagram, interview Q&A — and (B) a **single-file `Solution.java`** with every class and the full logic — a read-and-revise artifact for fast revision before an interview (for review, not compiling/running). The solution file also carries the clarifying questions to ask, at the top.

Sibling to `sde3-concept-prompts` (concepts) and `hld-design-prompts` (system design).

## Layout
```
lld-design-prompts/
├── _MASTER-PROMPT.md   ← shared persona + output spec + problem list (runnable: roadmap)
├── _INDEX.md           ← all 38 problems with checkboxes
├── _HOW-TO-RUN.md      ← the 3 ways to generate
├── README.md
└── NN-problem/PROMPT.md   →  generates NN-problem.md  +  Solution.java
```

## How to run
See `_HOW-TO-RUN.md`. Fastest: Cowork with this folder selected — "read `01-parking-lot/PROMPT.md`, write the doc to `01-parking-lot/01-parking-lot.md` and the code to `01-parking-lot/Solution.java`."

## What you get per problem
Clarifying/requirements questions · finalized requirements · problem extensions + design impact · entities & responsibilities · design patterns (justified, with alternatives/when-not) + SOLID · Mermaid class diagram · key flows · concurrency/edge cases · interview Q&A · **one complete Java file with a `main` demo (for review, not execution)** · cheat-sheet + self-test.

Pairs with your existing `interview-prep/lld-guide.md` (framework + patterns + problems-with-extensions) — this library turns each problem into a reviewable solution.

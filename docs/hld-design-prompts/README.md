# HLD — System Design Prompt Library

42 self-contained prompts that each generate a complete, interview-ready **high-level / system design**. Sibling to `sde3-concept-prompts` (concepts) and `lld-design-prompts` (object design).

Each generated doc starts the way a real round does — **clarifying/requirements questions first** — then capacity estimation, API, architecture (ASCII + Mermaid diagrams), data model, deep dives with tradeoffs, scaling/bottlenecks, reliability/consistency/security, extensions, and an interview Q&A.

## Layout
```
hld-design-prompts/
├── _MASTER-PROMPT.md   ← shared persona + structure + systems map (runnable: roadmap)
├── _INDEX.md           ← all 42 systems with checkboxes
├── _HOW-TO-RUN.md      ← the 3 ways to generate
├── README.md
└── NN-category/NN-system/PROMPT.md
```

## How to run
See `_HOW-TO-RUN.md`. Fastest: Cowork with this folder selected — "read `02-social-and-feed/01-design-twitter-newsfeed/PROMPT.md` and write the full design to that folder as `01-design-twitter-newsfeed.md`."

## Categories
1. Fundamentals & Framework (the approach, estimation, building blocks, tradeoffs) · 2. Social & Feed · 3. Messaging & Real-time · 4. Media & Streaming · 5. Location & Logistics · 6. Commerce & Booking · 7. Storage & Infrastructure.

Pairs with your existing `interview-prep/hld-guide.md` — that has the framework + worked references; this library lets you generate a deep, consistent doc for each system and practice the full set.

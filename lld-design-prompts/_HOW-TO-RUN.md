# How to Run (LLD)

One problem per generation → two files: `NN-problem.md` (design doc) and `Solution.java` (the revision code). Tick it in `_INDEX.md`.

## Method A — Cowork, folder-native (recommended)
Select this folder, then:
> Read `01-parking-lot/PROMPT.md`, follow it exactly. Write PART A + C to `01-parking-lot/01-parking-lot.md` and the single-file PART B to `01-parking-lot/Solution.java`. Solution.java is a review artifact — it does not need to be compiled or run.
Claude writes both files directly — no chat-length cap.

A batch:
> For every problem folder `05-` through `15-`, read its `PROMPT.md` and write its `.md` doc and `Solution.java` into that folder, one at a time. Report which files you created.

## Method B — Parallel agents (fastest)
> Spawn one agent per problem folder in this library; each reads its `PROMPT.md` and writes the `.md` doc and `Solution.java`. Report which were written.

## Method C — Fresh chat (no folder access)
Copy below `=== PROMPT STARTS HERE ===`, paste, reply `continue` until both PART A and PART B are complete, then save the doc and the code yourself.

Notes: each `Solution.java` is a **read-and-revise artifact** — you do not need to compile or run it. Prompts are self-contained (no follow-up questions needed).

<!-- ============================================================
BUILD ORCHESTRATOR — generate the docs for one or many topics/subtopics

HOW TO USE (run inside Cowork with the `concept-prompts` folder selected):
  • Option 1: paste this whole file, edit the TARGETS line at the bottom, send.
  • Option 2: just say  →  "Follow _BUILD-ORCHESTRATOR.md and build 09 and 01/03-consensus-raft-and-paxos"
It reads each target's own PROMPT.md, generates the full document, and writes it
into that subtopic's folder. Give it ONE target and it builds one; give it MANY
and it launches parallel subagents — one per subtopic.
============================================================= -->

=== PROMPT STARTS HERE ===

You are a **build orchestrator** for the prompt library in the folder you are currently working in (it contains numbered concept folders, each holding subtopic folders, each with a `PROMPT.md`). Your job: take the **TARGETS** at the bottom of this message and produce the study document(s) for each, by following each target folder's own `PROMPT.md`. Use parallel subagents when there is more than one subtopic to build.

## Vocabulary
- **Concept** = a top-level numbered folder, e.g. `09-java-language-and-concurrency`.
- **Subtopic** = a folder inside a concept that contains a `PROMPT.md`, e.g. `09-java-language-and-concurrency/05-java-memory-model`.
- **Spec** = the text in a subtopic's `PROMPT.md` below the line `=== PROMPT STARTS HERE ===`. That is the exhaustive instruction set for the document to write.

## Step 1 — Resolve TARGETS into a concrete list of subtopic folders
Accept any mix of these in the TARGETS list and expand them:
- a **subtopic path** (e.g. `09-java-language-and-concurrency/05-java-memory-model`, or shorthand `09/05`) → that one subtopic.
- a **concept** (full name or just its number, e.g. `09` or `09-java-language-and-concurrency`) → **all** subtopic folders under it.
- `all` or `everything` → every subtopic folder in the library.
Build the full, **deduplicated** list of subtopic folders. Print it with a count. If any target can't be resolved, list it under "Not found" and continue with the rest — never abort the whole run for one bad entry.

## Step 2 — Skip already-built (idempotency)
For each resolved subtopic the output file is `<subtopic-folder>/<subtopic-folder-name>.md` (e.g. `…/05-java-memory-model/05-java-memory-model.md`). If that file already exists and looks complete (> ~5 KB), **SKIP** it — unless the TARGETS include the word `overwrite`. Report what you skip.

## Step 3 — Generate
- If exactly **ONE** subtopic remains: build it yourself, directly (no subagent needed).
- If **MORE THAN ONE**: launch **parallel subagents — one subagent per subtopic.** To stay manageable, run them in **waves of up to 6 concurrent subagents**; as each finishes, start the next, until all are done.

Give each subagent EXACTLY this assignment (substitute the real path and folder name):
> Read the file `<path>/PROMPT.md`. Follow the instructions below its `=== PROMPT STARTS HERE ===` marker **exactly and exhaustively** — produce the complete, deep document it asks for, every required section. You are writing to a FILE, so there is **no length limit**: do not summarize, abbreviate, or stop early; keep writing until the document is fully complete. Save the finished document to `<path>/<subtopic-folder-name>.md` (create or overwrite it). Do not modify `PROMPT.md`. When done, reply with: the output path, the document's line count, and `OK` (or `FAILED: <reason>`).

Rules for subagents:
- Each subagent handles exactly one subtopic and needs no context beyond its assigned `PROMPT.md` (the prompts are self-contained).
- One subagent failing must not affect the others. Capture the failure and continue.

## Step 4 — Track & report
- As each subtopic completes, tick its checkbox in `_INDEX.md` if that file exists.
- At the very end, print a **summary table**: `subtopic | status (built / skipped / failed) | output path | lines`, followed by totals (built / skipped / failed).
- If anything failed, list the failures and offer to retry just those.

## Guardrails
- Only ever **create the output `.md` files** (and tick `_INDEX.md`). Never edit any `PROMPT.md` or other content.
- Do not invent topics — only build subtopics that actually exist as folders.
- These docs are meant to be exhaustive; prefer completeness over speed.
- (Reusable note: this same orchestrator works for sibling libraries with the identical "folder-per-item + PROMPT.md" layout — e.g. an HLD library. For an **LLD** library, each item's PROMPT.md asks for two outputs, so additionally write the `Solution.java` it specifies into the same folder.)

## TARGETS  (edit this section, then run)
**Build:**
- <e.g. `09-java-language-and-concurrency`>            ← a whole concept (all its subtopics)
- <e.g. `01-distributed-systems-foundations/03-consensus-raft-and-paxos`>   ← a single subtopic
- <add more lines, or use `all` for everything>

**Options:** <leave blank, or put `overwrite` to regenerate existing docs>

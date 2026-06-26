# How to Run the Library (3 ways, fastest first)

Each generated document is exhaustive, so generate **one subtopic at a time**. The unit of work is a subtopic folder's `PROMPT.md`. Save each output as `NN-NN-subtopic.md` inside that subtopic's folder. Tick it off in `_INDEX.md`.

---

## Method A — Cowork, folder-native (recommended)
Open a Cowork chat with this `sde3-concept-prompts` folder selected, then say:

> Read `09-java-language-and-concurrency/05-java-memory-model/PROMPT.md`, follow its instructions exactly, and write the full document to `09-java-language-and-concurrency/05-java-memory-model/05-java-memory-model.md`. Be exhaustive — keep writing until the document is complete.

**Why this is best:** Claude writes the doc straight to a **file**, not a single chat message — so it is *not* capped by chat length and you don't have to babysit "continue". The output lands directly in the right folder. No copy-paste, no manual saving.

To do a whole concept area in one go (still one file each):
> For every subtopic folder under `11-spring-and-hibernate/`, read its `PROMPT.md` and write the complete document to a `.md` file in that same folder. Do them one at a time and tell me which files you created.

## Method B — Cowork, parallel agents (fastest for big batches)
> Spawn one agent per subtopic under `17-ai-and-llm-foundations/`. Each agent reads its folder's `PROMPT.md` and writes the finished doc into that folder. Report which succeeded.

Monitoring is just the file tree + the checkboxes in `_INDEX.md` — no need to watch individual chats.

## Method C — Fresh claude.ai chat (no folder access)
1. Open `NN-subtopic/PROMPT.md`, copy everything below `=== PROMPT STARTS HERE ===`.
2. Paste into a new chat. When it ends with `→ reply "continue"`, reply `continue` until done.
3. Save the full output yourself as `NN-NN-subtopic.md`. (Use this on a machine without Cowork folder access, e.g. an intern pasting into their own account.)

---

### Notes
- The prompts are self-contained, so a fresh chat should **not** need to ask you questions. If it ever pauses to ask, answer: *"proceed; go deeper, this is for senior-level mastery."*
- Naming convention: `09-java-language-and-concurrency/05-java-memory-model/09-05-java-memory-model.md`.
- Track progress in `_INDEX.md` (check the box per subtopic).

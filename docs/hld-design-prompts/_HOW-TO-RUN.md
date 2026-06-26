# How to Run (HLD)

One system per generation. Save each output as `NN-system.md` inside that system's folder; tick it in `_INDEX.md`.

## Method A — Cowork, folder-native (recommended)
Select this folder, then:
> Read `06-commerce-and-booking/01-design-ticketmaster/PROMPT.md`, follow it exactly, and write the full design to that same folder as `01-design-ticketmaster.md`. Be exhaustive.
Claude writes to the file (no chat-length cap, no "continue" babysitting).

A whole category at once:
> For every system folder under `05-location-and-logistics/`, read its `PROMPT.md` and write the complete design to a `.md` in that folder, one at a time. Report which files you created.

## Method B — Parallel agents (fastest)
> Spawn one agent per system under `07-storage-and-infra/`; each reads its `PROMPT.md` and writes the design into its folder. Report successes.

## Method C — Fresh chat (no folder access)
Copy below `=== PROMPT STARTS HERE ===`, paste, reply `continue` until done, save as `NN-system.md`.

Notes: prompts are self-contained (no follow-up questions needed). Monitor via `_INDEX.md` checkboxes + the file tree.

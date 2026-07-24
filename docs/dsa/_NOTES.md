# DSA Tracker — operator notes (not published)

Interactive tracker at the **DSA Tracker** tab. Content is data-driven:

```
docs/dsa/
  index.md                 # page shell (container + loads assets, holds DSA_SHEET_CSV_URL)
  assets/tracker.{css,js}  # the UI app (vanilla, no build step)
  data/
    tracker.csv            # 272-problem master list + offline solved snapshot source
    _SCHEMA.md             # JSON schema the content generators follow
    build_data.py          # merges parts/ + tracker.csv -> index.json, solved.json, topics/*.json
    parts/*.json           # raw generator output (excluded from site)
    index.json             # light metadata for all 272 (built)
    solved.json            # solved snapshot / offline fallback (built)
    topics/<slug>.json     # full per-topic content, lazy-loaded (built)
```

## Enable LIVE sync (Sync button)

The tracker reads solved-state live from your Google sheet. To turn it on:

1. The tracker file is an uploaded `.xlsx`. Live CSV needs a **native Google Sheet**:
   open it → **File → Save as Google Sheets**.
2. In the Google Sheet: **File → Share → Publish to web** → choose the **Tracker** tab →
   format **Comma-separated values (.csv)** → **Publish**. Copy the URL.
3. Paste it into `docs/dsa/index.md`:
   ```js
   window.DSA_SHEET_CSV_URL = "https://docs.google.com/.../pub?gid=<TRACKER_GID>&single=true&output=csv";
   ```
4. Commit + push. Now ticking a problem in the sheet → it shows on the page after reload or **Sync**
   (no rebuild). If the fetch ever fails, the page falls back to the committed `solved.json`.

Until step 3 is set, the page runs on the committed snapshot (regenerate it any time with the
update flow below).

## Add newly-solved problems (no content change)

- With live sync on: just tick the sheet. Done.
- Without live sync: edit `docs/dsa/data/tracker.csv` (flip `FALSE`→`TRUE`), run
  `python3 docs/dsa/data/build_data.py`, commit + push.

## Add brand-new problems / regenerate content

1. Add the row(s) to `docs/dsa/data/tracker.csv` (and the sheet).
2. Generate content for them following `docs/dsa/data/_SCHEMA.md` into a new `docs/dsa/data/parts/<name>.json`
   (paste the schema + the new rows to Claude, or hand-write the entry).
3. `python3 docs/dsa/data/build_data.py` — it reports any problems still MISSING content.
4. Commit + push (CI redeploys via `.github/workflows/deploy-docs.yml`).

## Local preview

```bash
pip install mkdocs-material
mkdocs serve      # http://127.0.0.1:8000/dsa/
```

## Notes on content

All Java solutions are AI-generated **reference** solutions (not your original submissions).
Solved/unsolved is tracked purely by the sheet. Spot-check anything flagged `Redo` / `took hint`.

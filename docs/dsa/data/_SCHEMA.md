# DSA problem JSON schema (for content generators)

Each generator writes ONE file to `docs/dsa/data/parts/<partname>.json`.
The file is a **JSON object keyed by problem `id`**. The build script merges all
parts into per-topic files + a light metadata index.

## id derivation (MUST match exactly)

`id = slug(topic) + "__" + slug(title)`

`slug(s)`:
1. lowercase
2. replace every run of characters that are NOT `[a-z0-9]` with a single `-`
3. strip leading/trailing `-`

Examples:
- topic `"Binary Search"`, title `"Koko Eating Bananas"` → `binary-search__koko-eating-bananas`
- topic `"Math & Bit Manipulation"`, title `"Pow(x,n)"` → `math-bit-manipulation__pow-x-n`
- topic `"Arrays / Hashing / Prefix-sum"`, title `"Coin Change I/II"` → `arrays-hashing-prefix-sum__coin-change-i-ii`

The `topic` and `title` MUST be copied verbatim from the problem list you are given
(same spelling/casing) so the id matches the tracker sheet rows.

## Entry shape

```json
{
  "binary-search__koko-eating-bananas": {
    "title": "Koko Eating Bananas",
    "topic": "Binary Search",
    "subtopic": "Binary Search",
    "difficulty": "Medium",
    "pattern": "Binary search on the answer (min feasible speed)",
    "notes": "",
    "statement": "One-paragraph statement in plain words. Markdown inline allowed (backticks, **bold**). NO headings.",
    "examples": [
      { "input": "piles = [3,6,7,11], h = 8", "output": "4", "explanation": "" }
    ],
    "constraints": [
      "1 <= piles.length <= 10^4",
      "piles.length <= h <= 10^9",
      "1 <= piles[i] <= 10^9"
    ],
    "approaches": [
      {
        "name": "Binary search on speed",
        "idea": "2-4 sentence explanation of the idea and why it is correct. Markdown inline allowed.",
        "time": "O(n log maxPile)",
        "space": "O(1)",
        "code": "public int minEatingSpeed(int[] piles, int h) {\n    // full compilable Java\n}"
      }
    ],
    "insight": "One sentence to recall the trick when seeing this problem again."
  }
}
```

## Rules

- **Language:** Java only. Code must be self-contained and compilable (include helper types like `ListNode`/`TreeNode` inside the code string if used).
- **Correctness first.** Use the standard optimal solution. Add a second approach ONLY when genuinely instructive (e.g. brute-force→optimal, heap vs D&C). Don't pad.
- `difficulty` ∈ `"Medium" | "Hard" | "Very Hard"` (copy from the list).
- `notes`: copy any tracker note you are given (e.g. "Redo", "took hint", "Premium"); else `""`.
- `examples`: at least one; `explanation` may be `""`.
- `statement` and `idea` must NOT contain markdown headings (`#`) or raw newlines that break JSON — escape newlines in `code` as `\n`.
- Output **valid JSON only** in the file (no trailing commas, no comments, no ```json fences).
- Do not set any "solved" field — solved-state comes from the tracker at runtime.

## Reuse

If told a problem already has a write-up in `/Users/r.sourabhkumar/Downloads/dsa-revision/docs/<topic>.md`,
READ that file and convert the existing entry to this JSON shape instead of regenerating it
(preserve its approaches, code, and key insight). Only newly generate problems not already written.

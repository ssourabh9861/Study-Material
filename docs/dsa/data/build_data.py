#!/usr/bin/env python3
"""Merge subagent content parts + tracker.csv into the data files the tracker app loads.

Outputs (all under docs/dsa/data/):
  index.json          light metadata for every problem, in tracker order
  solved.json         { id: true } fallback snapshot from tracker.csv
  topics/<slug>.json  { id: full entry } per topic (lazy-loaded by the app)

Run from anywhere:  python3 docs/dsa/data/build_data.py
"""
import csv, glob, json, os, re, sys

DATA_DIR = os.path.dirname(os.path.abspath(__file__))
PARTS_DIR = os.path.join(DATA_DIR, "parts")
TOPICS_DIR = os.path.join(DATA_DIR, "topics")
CSV_PATH = os.path.join(DATA_DIR, "tracker.csv")


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def pid(topic: str, title: str) -> str:
    return f"{slug(topic)}__{slug(title)}"


def main() -> int:
    # 1. load tracker.csv (authoritative list + order + solved fallback)
    rows = []
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append(r)

    # 2. merge all content parts
    content = {}
    for p in sorted(glob.glob(os.path.join(PARTS_DIR, "*.json"))):
        with open(p, encoding="utf-8") as f:
            try:
                chunk = json.load(f)
            except json.JSONDecodeError as e:
                print(f"  !! invalid JSON in {os.path.basename(p)}: {e}", file=sys.stderr)
                return 1
        dupes = content.keys() & chunk.keys()
        if dupes:
            print(f"  !! duplicate ids in {os.path.basename(p)}: {sorted(dupes)}", file=sys.stderr)
        content.update(chunk)

    # 3. build index + solved + per-topic buckets
    index, solved, by_topic = [], {}, {}
    missing = []
    for r in rows:
        topic, subtopic = r["topic"].strip(), r["subtopic"].strip()
        title, diff = r["problem"].strip(), r["difficulty"].strip()
        is_solved = r["solved"].strip().upper() == "TRUE"
        notes = (r.get("notes") or "").strip()
        pid_ = pid(topic, title)
        ts = slug(topic)
        has = pid_ in content
        if not has:
            missing.append(pid_)
        index.append({
            "id": pid_, "topic": topic, "topicSlug": ts, "subtopic": subtopic,
            "title": title, "difficulty": diff, "notes": notes, "hasContent": has,
        })
        if is_solved:
            solved[pid_] = True
        by_topic.setdefault(ts, {})
        if has:
            by_topic[ts][pid_] = content[pid_]

    os.makedirs(TOPICS_DIR, exist_ok=True)
    with open(os.path.join(DATA_DIR, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=0)
    with open(os.path.join(DATA_DIR, "solved.json"), "w", encoding="utf-8") as f:
        json.dump(solved, f, ensure_ascii=False, indent=0)
    for ts, bucket in by_topic.items():
        with open(os.path.join(TOPICS_DIR, f"{ts}.json"), "w", encoding="utf-8") as f:
            json.dump(bucket, f, ensure_ascii=False, indent=0)

    print(f"problems: {len(index)} | with content: {len(content)} | solved: {len(solved)}")
    print(f"topics written: {len(by_topic)}")
    if missing:
        print(f"MISSING content for {len(missing)} problems:")
        for m in missing:
            print(f"  - {m}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

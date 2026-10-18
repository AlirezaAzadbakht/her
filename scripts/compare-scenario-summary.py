#!/usr/bin/env python3
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
cur = root / "build/reports/scenarios/summary.json"
prev = root / "build/reports/scenarios/summary.prev.json"
if not cur.is_file():
    sys.exit("No summary.json. Run make scenarios first.")
if not prev.is_file():
    print("No summary.prev.json to compare against.")
    sys.exit(0)

def rows(path):
    data = json.loads(path.read_text())
    return {item["id"]: item for item in data.get("scenarios", [])}

old, new = rows(prev), rows(cur)
ids = sorted(set(old) | set(new))
changed = []
for sid in ids:
    a, b = old.get(sid), new.get(sid)
    if a is None:
        changed.append(f"+ {sid} passed={b.get('passed')}")
    elif b is None:
        changed.append(f"- {sid}")
    elif a.get("passed") != b.get("passed"):
        changed.append(f"* {sid} {a.get('passed')} -> {b.get('passed')}")
if not changed:
    print(f"No pass/fail changes across {len(ids)} scenarios.")
else:
    print("\n".join(changed))

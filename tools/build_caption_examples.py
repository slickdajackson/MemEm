#!/usr/bin/env python3
"""Pick up to five real payload captions per template for Gemma few-shot.

Reads data/payloads-6491.json and box counts from the catalog. Prefers captions
whose line count matches the template and whose lines are non-empty and distinct.
Writes app/src/main/assets/caption-examples.json.
"""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAYLOADS = ROOT / "data" / "payloads-6491.json"
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "catalog.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "caption-examples.json"


def rank(lines: list[str], box_count: int) -> tuple:
    blanks = sum(1 for line in lines if not line.strip())
    keys = [" ".join(line.lower().split()) for line in lines if line.strip()]
    identical = len(keys) != len(set(keys))
    mismatch = 0 if len(lines) == box_count else 1
    length = sum(len(line) for line in lines)
    return (blanks > 0, identical, mismatch, length)


def pick(groups: list[list[str]], box_count: int, limit: int = 5) -> list[list[str]]:
    seen: set[tuple[str, ...]] = set()
    unique: list[list[str]] = []
    for lines in groups:
        key = tuple(lines)
        if key in seen or not any(line.strip() for line in lines):
            continue
        seen.add(key)
        unique.append(list(key))
    ordered = sorted(unique, key=lambda lines: rank(lines, box_count))
    distinct = [lines for lines in ordered if not rank(lines, box_count)[1]]
    if len(distinct) >= 3:
        return distinct[:limit]
    return ordered[:limit]


def main() -> None:
    raise SystemExit(
        "caption-examples.json ist kuratiert (deutsche Sprüche, 2 bis 3 je Vorlage). "
        "Nicht aus payloads-6491.json neu schreiben."
    )
    payloads = json.loads(PAYLOADS.read_text())
    catalog = json.loads(CATALOG.read_text())
    boxes = {item["id"]: item["boxes"] for item in catalog["templates"]}
    grouped: dict[str, list[list[str]]] = defaultdict(list)
    for item in payloads:
        lines = [str(line).strip() for line in (item.get("lines") or [])]
        if any(lines):
            grouped[item["template_id"]].append(lines)
    out = {tid: pick(groups, boxes.get(tid, 2)) for tid, groups in grouped.items()}
    out = {tid: lines for tid, lines in out.items() if lines}
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"wrote {len(out)} templates, {OUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()

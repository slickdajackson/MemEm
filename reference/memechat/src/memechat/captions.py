"""Collect example captions for every memegen template from external datasets.

Sources:
- ImgFlip575K (github.com/schesa/ImgFlip575K_Dataset): ~100 popular imgflip templates, boxes + votes. No license.
- LoC-meme-generator (huggingface.co/datasets/pszemraj/LoC-meme-generator): memegenerator.net upper/lower text, ODC-BY.

Templates are matched by name (fuzzy) because memegen `source:` links mostly point to knowyourmeme.
`data/captions/aliases.yml` pins or blocks matches by hand.

Run: uv run python -m memechat.captions [--report]
"""

import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import pyarrow.parquet as pq
import yaml
from rapidfuzz import fuzz, process

from memechat.config import settings
from memechat.templates import Template, load_templates

MATCH_THRESHOLD = 90
MAX_LINE_CHARS = 90
# Hard filter for the worst slurs/abuse common in 2012-era meme datasets; not a full moderation layer.
BLOCKLIST = re.compile(
    r"\b(n[i1]gg|f[a@]g|retard|rape|r[a@]p(e|ing)|kike|spic|chink|tranny|whore|slut|cunt|hitler|nazi|kill yourself|kys)",
    re.I,
)


@dataclass(frozen=True)
class Caption:
    lines: tuple[str, ...]
    source: str
    score: int  # votes; 0 when unknown


def _norm(name: str) -> str:
    name = re.sub(r"(?i)\bmeme template\b", "", name)
    name = re.sub(r"[^a-z0-9 ]+", " ", name.lower().replace("-", " "))
    return re.sub(r"\s+", " ", name).strip()


def _clean(line: str) -> str:
    return re.sub(r"\s+", " ", (line or "").strip())


def _int(value) -> int:
    try:
        return int(str(value).replace(",", ""))
    except ValueError:
        return 0


def load_imgflip(root: Path) -> dict[str, list[Caption]]:
    """name variant -> captions, keyed by every name/alternative name of the template."""
    out: dict[str, list[Caption]] = {}
    for meme_file in sorted((root / "dataset" / "memes").glob("*.json")):
        captions = [
            Caption(tuple(_clean(b) for b in m.get("boxes", [])), "imgflip575k", _int(m["metadata"].get("img-votes")))
            for m in json.loads(meme_file.read_text())
        ]
        names = {meme_file.stem}
        template_file = root / "dataset" / "templates" / meme_file.name
        if template_file.exists():
            info = json.loads(template_file.read_text())
            names.add(info.get("title", ""))
            names.update(a for a in info.get("alternative_names", "").split(","))
        for n in names:
            if _norm(n):
                out.setdefault(f"imgflip:{_norm(n)}", captions)
    return out


def load_loc(path: Path) -> dict[str, list[Caption]]:
    table = pq.read_table(path, columns=["Base Meme Name", "Upper Text", "Lower Text"]).to_pylist()
    out: dict[str, list[Caption]] = defaultdict(list)
    for row in table:
        out[f"loc:{_norm(row['Base Meme Name'])}"].append(
            Caption((_clean(row["Upper Text"]), _clean(row["Lower Text"])), "loc-meme-generator", 0)
        )
    return dict(out)


def _template_names(t: Template) -> set[str]:
    names = {t.name, t.id, *t.keywords}
    if "knowyourmeme.com/memes/" in t.source:
        names.add(t.source.rstrip("/").rsplit("/", 1)[-1])
    return {n for n in map(_norm, names) if len(n) >= 3}


def match_sources(templates: dict[str, Template], pools: dict[str, list[Caption]], aliases: dict) -> dict[str, list[str]]:
    """template id -> matched pool keys."""
    keys = list(pools)
    by_source = defaultdict(list)
    for k in keys:
        by_source[k.split(":", 1)[0]].append(k)
    matches: dict[str, list[str]] = {}
    for t in templates.values():
        pinned = aliases.get("pin", {}).get(t.id, [])
        blocked = set(aliases.get("block", {}).get(t.id, []))
        found = [k for k in pinned if k in pools]
        for source_keys in by_source.values():
            best = None
            for name in _template_names(t):
                hit = process.extractOne(name, source_keys, scorer=fuzz.token_sort_ratio, processor=lambda k: k.split(":", 1)[-1])
                if hit and hit[1] >= MATCH_THRESHOLD and (best is None or hit[1] > best[1]):
                    best = hit
            if best and best[0] not in blocked and best[0] not in found:
                found.append(best[0])
        matches[t.id] = found
    return matches


def _fit(lines: tuple[str, ...], box_count: int) -> tuple[str, ...] | None:
    lines = tuple(lines)
    while len(lines) > box_count and lines and not lines[-1]:
        lines = lines[:-1]
    if len(lines) > box_count or not any(lines):
        return None
    if any(len(line) > MAX_LINE_CHARS for line in lines) or any(BLOCKLIST.search(line) for line in lines):
        return None
    return lines + ("",) * (box_count - len(lines))


def select_captions(t: Template, pool: list[Caption], limit: int) -> list[dict]:
    seen: set[str] = set()
    picked: list[dict] = []
    original = _fit(t.example, t.box_count)
    candidates = ([Caption(original, "memegen", 1 << 30)] if original else []) + sorted(pool, key=lambda c: -c.score)
    for c in candidates:
        lines = _fit(c.lines, t.box_count)
        if not lines:
            continue
        key = " / ".join(lines).lower()
        if key in seen:
            continue
        seen.add(key)
        picked.append({"lines": list(lines), "source": c.source})
        if len(picked) >= limit:
            break
    return picked


def build(report: bool = False) -> dict[str, list[dict]]:
    s = settings()
    templates = load_templates()
    sources = s.data_dir / "sources"
    pools = load_imgflip(sources / "imgflip575k") | load_loc(sources / "loc.parquet")
    alias_file = s.captions_dir / "aliases.yml"
    aliases = yaml.safe_load(alias_file.read_text()) if alias_file.exists() else {}
    matches = match_sources(templates, pools, aliases or {})

    s.captions_dir.mkdir(parents=True, exist_ok=True)
    result = {}
    for tid, t in templates.items():
        pool = [c for k in matches[tid] for c in pools[k]]
        result[tid] = select_captions(t, pool, s.captions_per_template)
        path = s.captions_dir / f"{tid}.json"
        meaning = json.loads(path.read_text()).get("meaning") if path.exists() else None  # from memechat.describe
        data = {"template": tid, "name": t.name, "matched": matches[tid], "captions": result[tid]}
        if meaning:
            data["meaning"] = meaning
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1))

    if report:
        for tid, caps in sorted(result.items(), key=lambda kv: len(kv[1])):
            print(f"{len(caps):3d}  {tid:28s} {templates[tid].name[:40]:40s} {matches[tid]}")
    covered = sum(1 for c in result.values() if len(c) > 1)
    print(f"{covered}/{len(result)} templates have external captions; {sum(map(len, result.values()))} captions total")
    return result


if __name__ == "__main__":
    build(report="--report" in sys.argv)

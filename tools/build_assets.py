#!/usr/bin/env python3
"""Build the on-device catalog, WebP templates, and the hashed 768-d index.

Reads memegen `templates/*/config.yml`, prototype captions, and the 6491 payloads.
Writes `app/src/main/assets/`. Re-run `tools/build_index.py` to replace the hash
index with LiteRT-LM EmbeddingGemma 2 vectors.
"""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path

import numpy as np
import yaml
from PIL import Image

from hash_embed import DIM, embed, embed_matrix, idf_table

ROOT = Path(__file__).resolve().parents[1]
MEMEGEN = Path(os.environ.get("MEMEM_MEMEGEN", ROOT / "vendor" / "memegen"))
CAPTIONS = ROOT / "reference" / "memechat" / "data" / "captions"
PAYLOADS = ROOT / "data" / "payloads-6491.json"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
FONTS_SRC = {
    "Anton-Regular.ttf": Path(os.environ.get("MEMEM_FONT_ANTON", MEMEGEN / "fonts" / "Anton-Regular.ttf")),
    "TitilliumWeb-Black.ttf": MEMEGEN / "fonts" / "TitilliumWeb-Black.ttf",
    "Kalam-Regular.ttf": MEMEGEN / "fonts" / "Kalam-Regular.ttf",
    "NotoSans-Bold.ttf": MEMEGEN / "fonts" / "NotoSans-Bold.ttf",
}
MAX_EDGE = 720
# Prototype search drops templates with more than 4 text fields.
MAX_BOXES = 4


def image_path(folder: Path) -> Path | None:
    for ext in ("jpg", "jpeg", "png", "gif", "webp"):
        p = folder / f"default.{ext}"
        if p.exists():
            return p
    return None


def to_webp(src: Path, dest: Path) -> tuple[int, int]:
    im = Image.open(src)
    im.seek(0)
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGBA" if "A" in im.getbands() else "RGB")
    w, h = im.size
    scale = MAX_EDGE / max(w, h)
    if scale < 1:
        im = im.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.Resampling.LANCZOS)
        w, h = im.size
    dest.parent.mkdir(parents=True, exist_ok=True)
    im.save(dest, "WEBP", quality=72, method=4)
    return w, h


def field_dict(raw: dict) -> dict:
    return {
        "style": str(raw.get("style") or "upper"),
        "color": str(raw.get("color") or "white"),
        "font": str(raw.get("font") or "thick"),
        "anchorX": float(raw.get("anchor_x") or 0.0),
        "anchorY": float(raw.get("anchor_y") or 0.0),
        "scaleX": float(raw.get("scale_x") if raw.get("scale_x") is not None else 1.0),
        "scaleY": float(raw.get("scale_y") if raw.get("scale_y") is not None else 0.2),
        "angle": float(raw.get("angle") or 0.0),
        "align": str(raw.get("align") or "center"),
    }


def load_caption(template_id: str) -> dict:
    path = CAPTIONS / f"{template_id}.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text())


def text_documents(name: str, data: dict) -> list[tuple[str, list[str], str]]:
    docs: list[tuple[str, list[str], str]] = []
    for cap in data.get("captions") or []:
        lines = [str(x) for x in cap.get("lines") or []]
        text = " / ".join(line for line in lines if line)
        docs.append((text, lines, "caption"))
    meaning = data.get("meaning") or {}
    for key in ("meaning_en", "meaning_de"):
        if meaning.get(key):
            docs.append((str(meaning[key]), [], "meaning"))
    for key in ("situations_en", "situations_de"):
        for situation in meaning.get(key) or []:
            docs.append((str(situation), [], "situation"))
    return docs


def clip(text: str, limit: int) -> str:
    text = " ".join(str(text).split())
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


def main() -> None:
    payloads = json.loads(PAYLOADS.read_text())
    if len(payloads) != 6491:
        raise SystemExit(f"expected 6491 payloads, got {len(payloads)}")

    templates_out: list[dict] = []
    by_id: dict[str, dict] = {}
    img_dir = ASSETS / "images"
    if img_dir.exists():
        shutil.rmtree(img_dir)
    img_dir.mkdir(parents=True)

    folders = sorted(p.parent for p in (MEMEGEN / "templates").glob("*/config.yml"))
    for folder in folders:
        if folder.name.startswith("_"):
            continue
        data = yaml.safe_load((folder / "config.yml").read_text()) or {}
        text = data.get("text") or [{}, {}]
        fields = [field_dict(item or {}) for item in text]
        src = image_path(folder)
        if src is None:
            print("skip, no image", folder.name)
            continue
        w, h = to_webp(src, img_dir / f"{folder.name}.webp")
        cap = load_caption(folder.name)
        meaning = cap.get("meaning") or {}
        examples = []
        for item in (cap.get("captions") or [])[:8]:
            lines = [str(x) for x in item.get("lines") or []]
            if any(lines):
                examples.append(lines)
        examples.sort(key=lambda lines: sum(len(x) for x in lines))
        situations_de = [str(s) for s in (meaning.get("situations_de") or [])[:2]]
        keywords = [str(k) for k in (data.get("keywords") or []) if k]
        entry = {
            "id": folder.name,
            "name": str(data.get("name") or folder.name),
            "keywords": keywords,
            "boxes": len(fields),
            "width": w,
            "height": h,
            "fields": fields,
            "meaningDe": clip(meaning.get("meaning_de") or "", 280),
            "meaningEn": clip(meaning.get("meaning_en") or "", 280),
            "examples": examples[:3],
            "situationsDe": situations_de,
            "defaultLines": [str(x) for x in (data.get("example") or [])],
        }
        templates_out.append(entry)
        by_id[folder.name] = {"entry": entry, "cap": cap, "docs": text_documents(entry["name"], cap)}

    print(f"templates {len(templates_out)}")

    docs: list[str] = []
    points: list[dict] = []
    missing = 0
    for payload in payloads:
        tid = payload["template_id"]
        kind = payload["kind"]
        info = by_id.get(tid)
        name = info["entry"]["name"] if info else tid
        lines = [str(x) for x in (payload.get("lines") or [])]
        source = str(payload.get("source") or "")
        if kind == "text" and info:
            idx = int(payload.get("example_idx") or 0)
            built = info["docs"]
            if idx < len(built):
                text, built_lines, built_source = built[idx]
                if built_lines:
                    lines = built_lines
                source = source or built_source
                doc = f"title: {name} | text: {text}"
            else:
                missing += 1
                doc = f"title: {name} | text: {' / '.join(lines)}"
        elif kind == "image" and info:
            entry = info["entry"]
            blob = " ".join(
                [entry["name"], " ".join(entry["keywords"]), entry["meaningEn"], entry["meaningDe"]]
            )
            doc = f"title: {name} | text: {blob}"
            source = source or "template"
        elif kind == "image_text":
            text = " / ".join(line for line in lines if line)
            doc = f"title: {name} | text: {text}"
            source = source or "caption"
        else:
            missing += 1
            doc = f"title: {name} | text: {' / '.join(lines)}"
        docs.append(doc)
        points.append({"id": tid, "kind": kind, "source": source, "lines": lines})

    print(f"points {len(points)} reconstructed-gaps {missing}")
    idf = idf_table(docs)
    matrix = embed_matrix(docs, idf, len(docs)).astype(np.float16)
    index_dir = ASSETS / "index"
    index_dir.mkdir(parents=True, exist_ok=True)
    (index_dir / "vectors.f16").write_bytes(matrix.tobytes())
    # Round idf so Kotlin's float parse stays close. Tokens are part of the model, not logs.
    idf_out = {tok: round(weight, 5) for tok, weight in idf.items()}
    (index_dir / "idf.json").write_text(json.dumps({"n": len(docs), "dim": DIM, "idf": idf_out}, ensure_ascii=False))
    (index_dir / "points.json").write_text(
        json.dumps({"space": "hash-v1", "dim": DIM, "points": points}, ensure_ascii=False)
    )
    (ASSETS / "catalog.json").write_text(
        json.dumps({"maxBoxes": MAX_BOXES, "templates": templates_out}, ensure_ascii=False)
    )

    font_dir = ASSETS / "fonts"
    font_dir.mkdir(parents=True, exist_ok=True)
    for name, src in FONTS_SRC.items():
        shutil.copyfile(src, font_dir / name)
    license = MEMEGEN / "fonts" / "OFL.txt"
    if license.exists():
        shutil.copyfile(license, font_dir / "OFL.txt")

    golden_dir = ROOT / "engine" / "src" / "test" / "resources"
    golden_dir.mkdir(parents=True, exist_ok=True)
    sample_docs = [
        "title: Fry | text: not sure if joking or serious",
        "title: Drake | text: pizza over salad",
        "title: Money | text: shut up and take my money",
    ]
    sample_idf = idf_table(sample_docs)
    golden = {
        "n": len(sample_docs),
        "idf": {k: round(v, 5) for k, v in sample_idf.items()},
        "vectors": {
            "query": embed("shut up and take my money", sample_idf, len(sample_docs)).round(6).tolist(),
            "doc0": embed(sample_docs[0], sample_idf, len(sample_docs)).round(6).tolist(),
        },
    }
    (golden_dir / "hash_golden.json").write_text(json.dumps(golden))

    report = evaluate(matrix.astype(np.float32), points, templates_out, idf, len(docs))
    (ROOT / "docs").mkdir(exist_ok=True)
    (ROOT / "docs" / "search-eval.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({k: report[k] for k in ("r_at_1", "r_at_5", "extra_r_at_5", "templates", "index_bytes")}, indent=2))


def evaluate(matrix: np.ndarray, points: list[dict], templates: list[dict], idf: dict, n_docs: int) -> dict:
    boxes = {t["id"]: t["boxes"] for t in templates}
    kinds = ("image", "text", "image_text")
    limits = {"image": 100, "text": 300, "image_text": 300}
    weights = {"text": 1.0, "image": 0.5, "image_text": 1.0}

    def ranked(q: str, k: int = 8) -> list[str]:
        qv = embed(q, idf, n_docs)
        scores = matrix @ qv
        per_kind: dict[str, list[tuple[float, int]]] = {kind: [] for kind in kinds}
        for i, point in enumerate(points):
            per_kind[point["kind"]].append((float(scores[i]), i))
        for kind in kinds:
            per_kind[kind].sort(key=lambda item: -item[0])
            per_kind[kind] = per_kind[kind][: limits[kind]]
        hits: dict[str, dict] = {}
        for kind in kinds:
            rank = 0
            seen: set[str] = set()
            for score, idx in per_kind[kind]:
                tid = points[idx]["id"]
                if tid in seen:
                    continue
                seen.add(tid)
                rank += 1
                hit = hits.setdefault(tid, {"score": 0.0})
                hit["score"] += weights[kind] / (60 + rank)
        ordered = sorted(hits, key=lambda tid: -hits[tid]["score"])
        fresh = [tid for tid in ordered if boxes.get(tid, 99) <= MAX_BOXES]
        return (fresh or ordered)[:k]

    def recall(path: Path) -> dict:
        rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        r1 = r5 = 0
        misses = []
        for row in rows:
            ids = ranked(row["q"], 5)
            hit1 = ids[0] in row["expect"] if ids else False
            hit5 = any(i in row["expect"] for i in ids)
            r1 += hit1
            r5 += hit5
            if not hit5:
                misses.append({"q": row["q"], "expect": row["expect"], "got": ids})
        return {
            "n": len(rows),
            "r_at_1": round(r1 / len(rows), 3),
            "r_at_5": round(r5 / len(rows), 3),
            "misses": misses,
        }

    base = recall(ROOT / "reference" / "memechat" / "eval" / "queries.jsonl")
    extra = recall(ROOT / "eval" / "queries_de_extra.jsonl")
    vec_bytes = (ASSETS / "index" / "vectors.f16").stat().st_size
    return {
        "space": "hash-v1",
        "note": "Stand-in index. Prototype fused R@5 was 0.72 with EmbeddingGemma 2. Rebuild with tools/build_index.py.",
        "templates": len(templates),
        "points": len(points),
        "index_bytes": vec_bytes,
        "r_at_1": base["r_at_1"],
        "r_at_5": base["r_at_5"],
        "queries": base["n"],
        "misses": base["misses"],
        "extra_r_at_1": extra["r_at_1"],
        "extra_r_at_5": extra["r_at_5"],
        "extra_n": extra["n"],
        "extra_misses": extra["misses"],
    }


if __name__ == "__main__":
    main()

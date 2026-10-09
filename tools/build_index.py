#!/usr/bin/env python3
"""Rebuild `assets/index/vectors.f16` with EmbeddingGemma 2 Text 270M (LiteRT-LM).

Text, image and image_text points are embedded with the text model so they share
one space with on-device queries. Image points use the template name, keywords and
meaning. image_text points use the caption. A vision-model pass (440M) is optional
and not required for this file to load.

Query prefix (also used on device): `task: search query | text: `
Document prefix: `task: search result | text: `

The hashed stand-in stays in `vectors-hash.f16` for search before the model download.
"""

from __future__ import annotations

import json
import shutil
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_assets import evaluate, load_caption, text_documents  # noqa: E402

ASSETS = ROOT / "app" / "src" / "main" / "assets"
INDEX = ASSETS / "index"
PAYLOADS = ROOT / "data" / "payloads-6491.json"
DEFAULT_MODEL = Path("/tmp/models/embeddinggemma-2-text-270m.litertlm")
DOC_PREFIX = "task: search result | text: "
QUERY_PREFIX = "task: search query | text: "


def documents(catalog: dict, payloads: list[dict]) -> list[str]:
    by_id = {t["id"]: t for t in catalog["templates"]}
    docs_cache: dict[str, list] = {}
    out: list[str] = []
    for payload in payloads:
        tid = payload["template_id"]
        entry = by_id.get(tid)
        name = entry["name"] if entry else tid
        kind = payload["kind"]
        lines = [str(x) for x in (payload.get("lines") or [])]
        if kind == "text":
            if tid not in docs_cache:
                docs_cache[tid] = text_documents(name, load_caption(tid))
            idx = int(payload.get("example_idx") or 0)
            built = docs_cache[tid]
            text = built[idx][0] if idx < len(built) else " / ".join(lines)
        elif kind == "image" and entry:
            text = " ".join([entry["name"], " ".join(entry["keywords"]), entry["meaningEn"], entry["meaningDe"]])
        else:
            text = " / ".join(line for line in lines if line)
        out.append(f"{DOC_PREFIX}title: {name} | text: {text}")
    return out


def main() -> None:
    model = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_MODEL
    if not model.exists():
        raise SystemExit(f"missing model {model}. Download the 270M .litertlm first.")
    from litert_lm import EmbeddingEngine, EmbeddingOptions

    catalog = json.loads((ASSETS / "catalog.json").read_text())
    payloads = json.loads(PAYLOADS.read_text())
    points_doc = json.loads((INDEX / "points.json").read_text())
    docs = documents(catalog, payloads)
    if len(docs) != len(points_doc["points"]):
        raise SystemExit("document count does not match points")

    hash_path = INDEX / "vectors-hash.f16"
    vec_path = INDEX / "vectors.f16"
    if not hash_path.exists() and vec_path.exists() and points_doc.get("space") == "hash-v1":
        shutil.copyfile(vec_path, hash_path)

    opts = EmbeddingOptions(normalize=True, output_size=768)
    cache = ROOT / "build" / "embed-cache"
    cache.mkdir(parents=True, exist_ok=True)
    engine = EmbeddingEngine(str(model), cache_dir=str(cache))
    batch = 32
    rows: list[np.ndarray] = []
    started = time.time()
    for i in range(0, len(docs), batch):
        chunk = docs[i : i + batch]
        responses = engine.compute_embedding_batch(chunk, opts)
        for response in responses:
            rows.append(np.asarray(response.embedding, dtype=np.float32))
        if i % 512 == 0:
            print(f"{i}/{len(docs)} {time.time() - started:.1f}s", flush=True)
    engine.close()
    matrix = np.stack(rows)
    if matrix.shape != (len(docs), 768):
        raise SystemExit(f"bad shape {matrix.shape}")
    vec_path.write_bytes(matrix.astype(np.float16).tobytes())
    points_doc["space"] = "litert-eg2-text"
    points_doc["hashFile"] = "vectors-hash.f16"
    points_doc["queryPrefix"] = QUERY_PREFIX
    points_doc["docNote"] = (
        "All three kinds were embedded with EmbeddingGemma 2 Text 270M. "
        "image and image_text use a text stand-in (meaning or caption), not the 440M vision encoder."
    )
    (INDEX / "points.json").write_text(json.dumps(points_doc, ensure_ascii=False))

    idf_doc = json.loads((INDEX / "idf.json").read_text())
    from hash_embed import idf_table  # noqa: F401

    # evaluate() hashes queries. For the litert matrix, score with the same engine.
    report = evaluate_litert(engine_factory=lambda: EmbeddingEngine(str(model), cache_dir=str(cache)),
                             matrix=matrix, points=points_doc["points"], templates=catalog["templates"], opts=opts)
    (ROOT / "docs" / "search-eval.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({k: report[k] for k in ("r_at_1", "r_at_5", "extra_r_at_1", "extra_r_at_5", "seconds")}, indent=2))


def evaluate_litert(engine_factory, matrix: np.ndarray, points: list[dict], templates: list[dict], opts) -> dict:
    boxes = {t["id"]: t["boxes"] for t in templates}
    limits = {"image": 100, "text": 300, "image_text": 300}
    weights = {"text": 1.0, "image": 0.5, "image_text": 1.0}
    engine = engine_factory()

    def embed_query(text: str) -> np.ndarray:
        response = engine.compute_embedding(QUERY_PREFIX + text, opts)
        return np.asarray(response.embedding, dtype=np.float32)

    def ranked(q: str, k: int = 5) -> list[str]:
        scores = matrix @ embed_query(q)
        per_kind = {kind: [] for kind in limits}
        for i, point in enumerate(points):
            per_kind[point["kind"]].append((float(scores[i]), i))
        hits: dict[str, float] = {}
        for kind, limit in limits.items():
            ordered = sorted(per_kind[kind], key=lambda item: -item[0])[:limit]
            rank = 0
            seen: set[str] = set()
            for _score, idx in ordered:
                tid = points[idx]["id"]
                if tid in seen:
                    continue
                seen.add(tid)
                rank += 1
                hits[tid] = hits.get(tid, 0.0) + weights[kind] / (60 + rank)
        ordered_ids = sorted(hits, key=lambda tid: -hits[tid])
        fresh = [tid for tid in ordered_ids if boxes.get(tid, 99) <= 4]
        return (fresh or ordered_ids)[:k]

    def recall(path: Path) -> dict:
        rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        r1 = r5 = 0
        misses = []
        for row in rows:
            ids = ranked(row["q"])
            r1 += bool(ids) and ids[0] in row["expect"]
            hit5 = any(i in row["expect"] for i in ids)
            r5 += hit5
            if not hit5:
                misses.append({"q": row["q"], "expect": row["expect"], "got": ids})
        n = len(rows)
        return {"n": n, "r_at_1": round(r1 / n, 3), "r_at_5": round(r5 / n, 3), "misses": misses}

    started = time.time()
    base = recall(ROOT / "reference" / "memechat" / "eval" / "queries.jsonl")
    extra = recall(ROOT / "eval" / "queries_de_extra.jsonl")
    engine.close()
    return {
        "space": "litert-eg2-text",
        "model": "litert-community/embeddinggemma-2-text-270m-litert-lm",
        "note": "Image and image_text points are text stand-ins in the same 270M space, not 440M vision embeddings. Prototype fused R@5 was 0.72.",
        "seconds": round(time.time() - started, 1),
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

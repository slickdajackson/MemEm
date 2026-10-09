"""Recall@1/@5 of the template search for each kind alone and fused.

Run: uv run python scripts/eval_search.py [--verbose] [--rerank]
"""

import json
import sys
from pathlib import Path

from qdrant_client import QdrantClient

from memechat.config import settings
from memechat.generate import choose_template
from memechat.index import load_meaning
from memechat.search import search
from memechat.templates import load_templates

CONFIGS = {
    "text": {"text": 1.0},
    "image": {"image": 1.0},
    "image_text": {"image_text": 1.0},
    "fused (1/1/1)": {"text": 1.0, "image": 1.0, "image_text": 1.0},
    "fused (1/0.5/1)": {"text": 1.0, "image": 0.5, "image_text": 1.0},
    "text+image_text": {"text": 1.0, "image_text": 1.0},
}


def main() -> None:
    verbose = "--verbose" in sys.argv
    queries = [json.loads(line) for line in (Path(__file__).parents[1] / "eval" / "queries.jsonl").read_text().splitlines()]
    client = QdrantClient(url=settings().qdrant_url)
    print(f"{'config':18s} R@1    R@5    ({len(queries)} queries)")
    for name, weights in CONFIGS.items():
        r1 = r5 = 0
        misses = []
        for q in queries:
            ids = [h.template_id for h in search(q["q"], weights=weights, limit=5, client=client)]
            r1 += ids[0] in q["expect"]
            r5 += any(i in q["expect"] for i in ids)
            if ids[0] not in q["expect"]:
                misses.append(f"    {q['q'][:60]:60s} want {q['expect']} got {ids}")
        print(f"{name:18s} {r1 / len(queries):.2f}   {r5 / len(queries):.2f}")
        if verbose:
            print("\n".join(misses))

    if "--rerank" in sys.argv:
        s = settings()
        templates = load_templates()
        r1 = in_candidates = 0
        misses = []
        for q in queries:
            hits = search(q["q"], limit=s.rerank_candidates, client=client)
            candidates = [(h.template_id, templates[h.template_id].name, load_meaning(h.template_id)) for h in hits]
            chosen = choose_template(candidates, [], "user", q["q"])
            r1 += chosen in q["expect"]
            in_candidates += any(h.template_id in q["expect"] for h in hits)
            if chosen not in q["expect"]:
                misses.append(f"    {q['q'][:60]:60s} want {q['expect']} got {chosen} (candidates {[h.template_id for h in hits]})")
        print(f"{'fused + LLM rerank':18s} {r1 / len(queries):.2f}   (R@{s.rerank_candidates} of candidates {in_candidates / len(queries):.2f})")
        if verbose:
            print("\n".join(misses))


if __name__ == "__main__":
    main()

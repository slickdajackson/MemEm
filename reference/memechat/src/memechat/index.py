"""Build the read-only `memes` collection and export it as a snapshot.

Three kinds of points per template, all in the same EmbeddingGemma 2 space:
- image:      the blank template image (1 point)
- text:       each example caption as text (N points) + the meme's meaning (DE/EN) and typical
              chat situations from memechat.describe
- image_text: each example caption rendered onto the template by memegen (N points)

Run: uv run python -m memechat.index [--kinds=text,image,image_text] [--snapshot]
"""

import json
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
from qdrant_client import QdrantClient, models

from memechat import embed, render
from memechat.config import settings
from memechat.templates import Template, load_templates

KINDS = ("image", "text", "image_text")
_NAMESPACE = uuid.UUID("6f1c9a52-0d0e-4f61-9a43-6d2f3e0b8a11")


def point_id(template_id: str, kind: str, idx: int) -> str:
    return str(uuid.uuid5(_NAMESPACE, f"{template_id}:{kind}:{idx}"))


def caption_text(lines: list[str]) -> str:
    return " / ".join(line for line in lines if line)


def load_captions(template_id: str) -> list[list[str]]:
    data = json.loads((settings().captions_dir / f"{template_id}.json").read_text())
    return [c["lines"] for c in data["captions"]]


def load_meaning(template_id: str) -> str:
    data = json.loads((settings().captions_dir / f"{template_id}.json").read_text())
    return (data.get("meaning") or {}).get("meaning_en", "")


def text_documents(t: Template) -> list[tuple[str, list[str], str]]:
    """(document text, caption lines, source) for kind=text: captions + meaning + typical situations."""
    data = json.loads((settings().captions_dir / f"{t.id}.json").read_text())
    docs = [(caption_text(c["lines"]), c["lines"], "caption") for c in data["captions"]]
    meaning = data.get("meaning") or {}
    for key in ("meaning_en", "meaning_de"):
        if meaning.get(key):
            docs.append((meaning[key], [], "meaning"))
    for key in ("situations_en", "situations_de"):
        docs += [(situation, [], "situation") for situation in meaning.get(key, [])]
    return docs


def _rendered(t: Template, idx: int, lines: list[str], client: httpx.Client) -> Path:
    path = settings().rendered_dir / t.id / f"{idx:02d}.png"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(render.render(t.id, lines, client=client))
    return path


def ensure_collection(client: QdrantClient) -> None:
    s = settings()
    if client.collection_exists(s.collection):
        return
    # small read-only dataset (~4k points): one segment and a tiny WAL keep the shipped snapshot compact
    client.create_collection(
        s.collection,
        vectors_config=models.VectorParams(size=s.embed_dim, distance=models.Distance.COSINE),
        optimizers_config=models.OptimizersConfigDiff(default_segment_number=1),
        wal_config=models.WalConfigDiff(wal_capacity_mb=1),
    )
    for field in ("kind", "template_id"):
        client.create_payload_index(s.collection, field, models.PayloadSchemaType.KEYWORD)


def build(kinds: tuple[str, ...] = KINDS) -> None:
    s = settings()
    templates = load_templates()
    client = QdrantClient(url=s.qdrant_url)
    ensure_collection(client)

    with httpx.Client() as http, ThreadPoolExecutor(4) as pool:
        for n, t in enumerate(templates.values(), 1):
            captions = load_captions(t.id)
            docs = text_documents(t)
            payloads: dict[str, list[dict]] = {
                "image": [{"lines": [], "source": "template"}],
                "text": [{"lines": lines, "source": source} for _, lines, source in docs],
                "image_text": [{"lines": lines, "source": "caption"} for lines in captions],
            }
            vectors = {}
            if "image" in kinds:
                vectors["image"] = embed.embed_images([t.image_path])
            if "text" in kinds:
                vectors["text"] = embed.embed_documents([(t.name, text) for text, _, _ in docs])
            if "image_text" in kinds:
                rendered = list(pool.map(lambda ic: _rendered(t, ic[0], ic[1], http), enumerate(captions)))
                vectors["image_text"] = embed.embed_images(rendered)
            points = [
                models.PointStruct(
                    id=point_id(t.id, kind, idx),
                    vector=vec.tolist(),
                    payload={"template_id": t.id, "kind": kind, "example_idx": idx} | payloads[kind][idx],
                )
                for kind in vectors
                for idx, vec in enumerate(vectors[kind])
            ]
            # drop this template's points of the rebuilt kinds (a previous build may have had more), then write
            client.delete(
                s.collection,
                models.Filter(
                    must=[
                        models.FieldCondition(key="template_id", match=models.MatchValue(value=t.id)),
                        models.FieldCondition(key="kind", match=models.MatchAny(any=list(vectors))),
                    ]
                ),
                wait=True,
            )
            client.upsert(s.collection, points, wait=True)
            print(f"[{n}/{len(templates)}] {t.id}: {len(points)} points", flush=True)

    print("collection size:", client.count(s.collection).count)


def recreate_compact() -> None:
    """Move existing points into a freshly created (compact) collection without re-embedding."""
    s = settings()
    client = QdrantClient(url=s.qdrant_url)
    points, offset = [], None
    while True:
        batch, offset = client.scroll(s.collection, limit=1000, offset=offset, with_vectors=True, with_payload=True)
        points += [models.PointStruct(id=p.id, vector=p.vector, payload=p.payload) for p in batch]
        if offset is None:
            break
    client.delete_collection(s.collection)
    ensure_collection(client)
    for i in range(0, len(points), 500):
        client.upsert(s.collection, points[i : i + 500], wait=True)
    print("recreated with", client.count(s.collection).count, "points")


def snapshot() -> Path:
    """Create a snapshot via the server and copy it to data/memes.snapshot (the shipped artifact)."""
    s = settings()
    client = QdrantClient(url=s.qdrant_url)
    info = client.create_snapshot(s.collection, wait=True)
    target = s.data_dir / "memes.snapshot"
    with httpx.stream("GET", f"{s.qdrant_url}/collections/{s.collection}/snapshots/{info.name}") as r:
        r.raise_for_status()
        with target.open("wb") as f:
            for chunk in r.iter_bytes():
                f.write(chunk)
    client.delete_snapshot(s.collection, info.name)
    print("snapshot:", target, target.stat().st_size // 1024, "KiB")
    return target


if __name__ == "__main__":
    if "--recreate-compact" in sys.argv:
        recreate_compact()
        sys.exit()
    if "--snapshot-only" not in sys.argv:
        only = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--kinds=")]
        build(tuple(only[0].split(",")) if only else KINDS)
    if "--snapshot" in sys.argv or "--snapshot-only" in sys.argv:
        snapshot()

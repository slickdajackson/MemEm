"""Multimodal template search: one query vector against three kinds of points, fused per template."""

from dataclasses import dataclass, field

from qdrant_client import QdrantClient, models

from memechat import embed
from memechat.config import settings

# per kind: how many points to pull. image has 1 point per template, the others up to captions_per_template.
_LIMITS = {"image": 100, "text": 300, "image_text": 300}


@dataclass(frozen=True)
class ChatLine:
    author: str
    text: str
    template_id: str = ""  # meme the message was sent as, if any


@dataclass
class KindHit:
    rank: int  # 1-based template rank within this kind
    score: float  # cosine of the template's best point
    lines: list[str]  # caption of that best point (empty for kind=image)


@dataclass
class TemplateHit:
    template_id: str
    score: float
    kinds: dict[str, KindHit] = field(default_factory=dict)


def build_query(message: str, history: list[ChatLine], context_messages: int) -> str:
    """New message first (it matters most); recent conversation appended as context."""
    recent = history[-context_messages:] if context_messages else []
    if not recent:
        return message
    context = " | ".join(f"{m.author}: {m.text}" for m in recent)
    return f"{message} (conversation: {context})"


def _client() -> QdrantClient:
    return QdrantClient(url=settings().qdrant_url)


def fuse(per_kind: dict[str, list[models.ScoredPoint]], weights: dict[str, float], k: int) -> list[TemplateHit]:
    """Weighted RRF over template ranks; a template's rank in a kind is the rank of its best point."""
    hits: dict[str, TemplateHit] = {}
    for kind, points in per_kind.items():
        rank = 0
        for p in points:
            tid = p.payload["template_id"]
            hit = hits.setdefault(tid, TemplateHit(tid, 0.0))
            if kind in hit.kinds:
                continue
            rank += 1
            hit.kinds[kind] = KindHit(rank, p.score, p.payload.get("lines", []))
            hit.score += weights.get(kind, 0.0) / (k + rank)
    return sorted(hits.values(), key=lambda h: -h.score)


def search(
    message: str,
    history: list[ChatLine] | None = None,
    *,
    limit: int = 5,
    weights: dict[str, float] | None = None,
    client: QdrantClient | None = None,
) -> list[TemplateHit]:
    s = settings()
    weights = weights or {"text": s.w_text, "image": s.w_image, "image_text": s.w_image_text}
    kinds = [kind for kind, w in weights.items() if w > 0]
    vector = embed.embed_query(build_query(message, history or [], s.context_messages)).tolist()
    requests = [
        models.QueryRequest(
            query=vector,
            filter=models.Filter(must=[models.FieldCondition(key="kind", match=models.MatchValue(value=kind))]),
            limit=_LIMITS[kind],
            with_payload=["template_id", "lines"],
        )
        for kind in kinds
    ]
    responses = (client or _client()).query_batch_points(s.collection, requests)
    per_kind = {kind: r.points for kind, r in zip(kinds, responses)}
    return fuse(per_kind, weights, s.rrf_k)[:limit]

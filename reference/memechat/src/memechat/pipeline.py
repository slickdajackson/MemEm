"""message -> search -> generate -> render -> stored meme message.

CLI: uv run python -m memechat.pipeline "Nachricht" [--history "A: text" ...]
"""

import argparse
import threading
from dataclasses import asdict

from memechat import store
from memechat.config import settings
from memechat.generate import choose_template, generate_lines
from memechat.index import load_captions, load_meaning
from memechat.render import render
from memechat.search import ChatLine, search
from memechat.templates import load_templates

MAX_EXAMPLES_IN_PROMPT = 12
# a template used in one of the last N messages is not offered again (avoids sad frog four times in a row)
RECENT_TEMPLATES = 3
# multi-panel templates (5-8 boxes) almost never fit a single chat message and produce repeated filler boxes
MAX_BOXES = 4

# The embedding model and the local LLM are single-GPU resources; serialize requests instead of thrashing.
_lock = threading.Lock()


def pick_template(text: str, history: list[ChatLine], author: str) -> tuple[str, list]:
    """Vector search (+ optional LLM rerank of the top candidates). Returns (template id, candidate hits)."""
    s = settings()
    recent = {m.template_id for m in history[-RECENT_TEMPLATES:] if m.template_id}
    hits = search(text, history, limit=(s.rerank_candidates if s.llm_rerank else 5) + len(recent) + 5)
    templates = load_templates()
    fresh = [h for h in hits if h.template_id not in recent and templates[h.template_id].box_count <= MAX_BOXES]
    hits = (fresh or hits)[: s.rerank_candidates if s.llm_rerank else 5]
    if not s.llm_rerank:
        return hits[0].template_id, hits
    candidates = [(h.template_id, templates[h.template_id].name, load_meaning(h.template_id)) for h in hits]
    return choose_template(candidates, history, author, text), hits


def create_meme(conversation_id: str, author: str, text: str, history: list[ChatLine]) -> store.Message:
    s = settings()
    with _lock:
        template_id, hits = pick_template(text, history, author)
        template = load_templates()[template_id]
        examples = load_captions(template.id)[:MAX_EXAMPLES_IN_PROMPT]
        lines = generate_lines(template, examples, history, author, text)
        png = render(template.id, lines)

    message_id = store.new_id()
    s.generated_dir.mkdir(parents=True, exist_ok=True)
    (s.generated_dir / f"{message_id}.png").write_bytes(png)
    return store.Message(
        id=message_id,
        conversation_id=conversation_id,
        author=author,
        text=text,
        template_id=template.id,
        lines=lines,
        image=f"{message_id}.png",
        created_at=store.now(),
        debug={
            "template_name": template.name,
            "reranked": template_id != hits[0].template_id,
            "candidates": [asdict(h) for h in hits],
        },
    )


def send(conversation_id: str, author: str, text: str) -> store.Message:
    history = [ChatLine(m.author, m.text, m.template_id) for m in store.messages(conversation_id, last=settings().context_messages)]
    message = create_meme(conversation_id, author, text, history)
    store.add(message)
    return message


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("message")
    parser.add_argument("--author", default="me")
    parser.add_argument("--history", nargs="*", default=[], help='"Author: text" entries, oldest first')
    args = parser.parse_args()
    history = [ChatLine(*h.split(":", 1)) for h in args.history]
    history = [ChatLine(h.author.strip(), h.text.strip()) for h in history]
    m = create_meme("cli", args.author, args.message, history)
    print(f"template: {m.template_id} ({m.debug['template_name']})")
    print(f"lines:    {m.lines}")
    print(f"image:    {settings().generated_dir / m.image}")
    for c in m.debug["candidates"]:
        kinds = ", ".join(f"{k}#{v['rank']}" for k, v in c["kinds"].items())
        print(f"  {c['score']:.4f} {c['template_id']:16s} {kinds}")


if __name__ == "__main__":
    main()

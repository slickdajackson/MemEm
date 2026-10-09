"""One-time: let the vision LLM describe what each template means and when people use it.

Output is merged into data/captions/<id>.json as "meaning" and embedded as extra kind=text points
("Bedeutung des Memes in Textform"). Idempotent: templates that already have a meaning are skipped.

Run: uv run python -m memechat.describe [--force]
"""

import base64
import json
import mimetypes
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

import httpx

from memechat.config import settings
from memechat.templates import Template, load_templates

PROMPT = """This is the meme template "{name}" ({box_count} text boxes).
Example captions people made with it (one JSON array per meme, one string per box):
{examples}

Explain the meme for a search index that matches chat messages to meme templates.
Answer with JSON only:
{{"meaning_en": "2-3 sentences: what the meme expresses, the emotion/attitude, and how the text boxes are used",
  "meaning_de": "the same in German",
  "situations_en": ["5 different short chat messages (max 15 words) someone might write where this meme is the perfect reply or reaction"],
  "situations_de": ["5 different short German chat messages of the same kind"]}}"""


def _data_uri(t: Template) -> str:
    mime = mimetypes.guess_type(t.image_path.name)[0] or "image/png"
    return f"data:{mime};base64," + base64.b64encode(t.image_path.read_bytes()).decode()


def describe(t: Template, examples: list[list[str]], client: httpx.Client) -> dict:
    s = settings()
    text = PROMPT.format(
        name=t.name,
        box_count=t.box_count,
        examples="\n".join(json.dumps(e, ensure_ascii=False) for e in examples[:8]),
    )
    body = {
        "model": s.llm_model,
        "messages": [
            {
                "role": "user",
                "content": [{"type": "image_url", "image_url": {"url": _data_uri(t)}}, {"type": "text", "text": text}],
            }
        ],
        "temperature": 0.3,
        "max_tokens": 900,
        "chat_template_kwargs": {"enable_thinking": False},
        "response_format": {"type": "json_object"},
    }
    for _ in range(3):
        r = client.post(
            f"{s.llm_base_url}/chat/completions", json=body, headers={"Authorization": f"Bearer {s.llm_api_key}"}, timeout=600
        )
        r.raise_for_status()
        content = r.json()["choices"][0]["message"].get("content") or ""
        try:
            data = json.loads(re.search(r"\{.*\}", content, re.S).group(0))
            keys = ("meaning_en", "meaning_de", "situations_en", "situations_de")
            if all(data.get(k) for k in keys):
                return {k: data[k] for k in keys}
        except (AttributeError, json.JSONDecodeError):
            pass
    raise RuntimeError(f"no usable description for {t.id}")


def main(force: bool, workers: int = 4) -> None:
    s = settings()
    templates = list(load_templates().values())
    todo = [t for t in templates if force or not json.loads((s.captions_dir / f"{t.id}.json").read_text()).get("meaning")]
    print(f"{len(todo)} of {len(templates)} templates to describe", flush=True)

    def work(t: Template) -> str:
        path = s.captions_dir / f"{t.id}.json"
        data = json.loads(path.read_text())
        data["meaning"] = describe(t, [c["lines"] for c in data["captions"]], client)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1))
        return f"{t.id}: {data['meaning']['meaning_en'][:90]}"

    # oMLX batches concurrent requests; a few in flight is much faster than one at a time
    with httpx.Client(limits=httpx.Limits(max_connections=workers)) as client, ThreadPoolExecutor(workers) as pool:
        futures = {pool.submit(work, t): t for t in todo}
        for n, future in enumerate(as_completed(futures), 1):
            try:
                print(f"[{n}/{len(todo)}] {future.result()}", flush=True)
            except Exception as e:  # keep going; a rerun picks up what is missing
                print(f"[{n}/{len(todo)}] FAILED {futures[future].id}: {e}", flush=True)


if __name__ == "__main__":
    main("--force" in sys.argv)

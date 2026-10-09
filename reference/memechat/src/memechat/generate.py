"""Ask the vision LLM for new caption lines that fit the found template and the conversation."""

import base64
import json
import mimetypes
import re

import httpx

from memechat.config import settings
from memechat.lang import language_mismatch
from memechat.search import ChatLine
from memechat.templates import Template

MAX_LINE_CHARS = 80

SYSTEM_PROMPT = """You are the caption writer of a chat app where every message is sent as a meme instead of plain text.

You get: a meme template (the image), its name, example captions showing how the meme is used, the recent conversation, and the new message the user wants to send.

Write a NEW caption for this template that:
- says what the user's new message says (same intent, same addressee, same facts) - the meme replaces the text message, so the meaning must survive
- does not add facts, events or claims that are not in the message or the conversation (exaggeration for the joke is fine, inventing what happened is not)
- follows the joke structure of the template as shown by the examples (what goes in which text box)
- fits the conversation (reply to what was said before when relevant), but the boxes express the NEW message:
  earlier messages are context only, do not quote them as if they were the new message
- is written entirely in the language of the new message: if it is German, translate the meme's catchphrase too
  (e.g. "probably not a good idea" -> "wahrscheinlich keine gute Idee", "why not both?" -> "warum nicht beides?")
- never contains the template's name, the character's name or the chat participants' names unless the message itself uses them
- is short and punchy: each box at most ~8 words, correct grammar, no hashtags, no emojis, no quotation marks around lines
- has exactly {box_count} text boxes, in the order of the examples; every box says something different; use "" for a box that should stay empty

Answer with JSON only: {{"lines": [...]}}"""


def _data_uri(template: Template) -> str:
    mime = mimetypes.guess_type(template.image_path.name)[0] or "image/png"
    return f"data:{mime};base64," + base64.b64encode(template.image_path.read_bytes()).decode()


def build_user_prompt(template: Template, examples: list[list[str]], history: list[ChatLine], author: str, message: str) -> str:
    example_text = "\n".join(f"- {json.dumps(lines, ensure_ascii=False)}" for lines in examples) or "- (none)"
    conversation = "\n".join(f"{m.author}: {m.text}" for m in history) or "(conversation starts now)"
    return (
        f"Meme template: {template.name} ({template.box_count} text boxes)\n\n"
        f"Example captions (one JSON array per meme, one string per box):\n{example_text}\n\n"
        f"Conversation so far:\n{conversation}\n\n"
        f"New message from {author}: {message}\n\n"
        f"Write every box in the language of this new message, including the template's catchphrase."
    )


def _clean(line: str) -> str:
    line = re.sub(r"\s+", " ", str(line)).strip().strip('"“”')
    # memegen decodes " - " as a double dash; an en/em-free variant keeps the meaning
    line = line.replace(" - ", ", ")
    return line[:MAX_LINE_CHARS]


def strip_speakers(lines: list[str], names: set[str]) -> list[str]:
    """Drop 'Name: ' prefixes the model copies from the transcript format."""
    if not names:
        return lines
    prefix = re.compile(r"^(" + "|".join(re.escape(n) for n in names) + r")\s*:\s*", re.I)
    return [prefix.sub("", line) for line in lines]


def parse_lines(content: str, box_count: int) -> list[str]:
    match = re.search(r"\{.*\}", content, re.S)
    if not match:
        raise ValueError(f"no JSON object in LLM output: {content[:200]!r}")
    lines = json.loads(match.group(0))["lines"]
    if not isinstance(lines, list) or not lines:
        raise ValueError(f"'lines' missing or empty: {content[:200]!r}")
    lines = [_clean(line) for line in lines][:box_count]
    lines += [""] * (box_count - len(lines))
    if not any(lines):
        raise ValueError("all lines empty")
    return lines


def generate_lines(
    template: Template,
    examples: list[list[str]],
    history: list[ChatLine],
    author: str,
    message: str,
    *,
    client: httpx.Client | None = None,
) -> list[str]:
    s = settings()
    schema = {
        "type": "object",
        "properties": {
            "lines": {
                "type": "array",
                "items": {"type": "string"},
                "minItems": template.box_count,
                "maxItems": template.box_count,
            }
        },
        "required": ["lines"],
        "additionalProperties": False,
    }
    body = {
        "model": s.llm_model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT.format(box_count=template.box_count)},
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": _data_uri(template)}},
                    {"type": "text", "text": build_user_prompt(template, examples, history, author, message)},
                ],
            },
        ],
        "temperature": 0.7,
        "max_tokens": 300,
        "chat_template_kwargs": {"enable_thinking": False},
        "response_format": {"type": "json_schema", "json_schema": {"name": "caption", "schema": schema, "strict": True}},
    }
    http = client or httpx
    speakers = {m.author for m in history} | {author}
    last_error: Exception | None = None
    lines: list[str] | None = None
    for _ in range(3):
        response = http.post(
            f"{s.llm_base_url}/chat/completions",
            json=body,
            headers={"Authorization": f"Bearer {s.llm_api_key}"},
            timeout=s.llm_timeout,
        )
        response.raise_for_status()
        content = response.json()["choices"][0]["message"].get("content") or ""
        try:
            lines = strip_speakers(parse_lines(content, template.box_count), speakers)
        except (ValueError, KeyError, json.JSONDecodeError) as e:
            last_error = e
            continue
        if not language_mismatch(message, lines):
            return lines
        # typical failure: the template's English catchphrase kept in a German chat; ask once more explicitly
        body["messages"] = body["messages"][:2] + [
            {"role": "assistant", "content": content},
            {"role": "user", "content": "Some boxes are not in the language of the new message. Rewrite all boxes in that language."},
        ]
    if lines:  # language still off after retries: better a slightly mixed meme than none
        return lines
    raise RuntimeError(f"LLM returned no usable caption: {last_error}")


RERANK_PROMPT = """Pick the meme template that is the best reply format for the new chat message.
The meme will carry the message's content, so pick the template whose joke structure fits what the message says and its emotion.
Prefer a template whose specific joke matches (e.g. a comparison, a bad plan, a fake promise, a surprise) over generic reaction faces.

Conversation so far:
{conversation}

New message from {author}: {message}

Candidates:
{candidates}

Answer with JSON only: {{"template": "<id>"}}"""


def choose_template(
    candidates: list[tuple[str, str, str]],
    history: list[ChatLine],
    author: str,
    message: str,
    *,
    client: httpx.Client | None = None,
) -> str:
    """candidates: (template_id, name, meaning). Returns the chosen id; falls back to the first on bad output."""
    s = settings()
    ids = [c[0] for c in candidates]
    listing = "\n".join(f"- {tid}: {name}. {meaning}" for tid, name, meaning in candidates)
    conversation = "\n".join(f"{m.author}: {m.text}" for m in history) or "(conversation starts now)"
    body = {
        "model": s.llm_model,
        "messages": [
            {
                "role": "user",
                "content": RERANK_PROMPT.format(conversation=conversation, author=author, message=message, candidates=listing),
            }
        ],
        "temperature": 0.0,
        "max_tokens": 40,
        "chat_template_kwargs": {"enable_thinking": False},
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "choice",
                "schema": {
                    "type": "object",
                    "properties": {"template": {"type": "string", "enum": ids}},
                    "required": ["template"],
                    "additionalProperties": False,
                },
                "strict": True,
            },
        },
    }
    response = (client or httpx).post(
        f"{s.llm_base_url}/chat/completions", json=body, headers={"Authorization": f"Bearer {s.llm_api_key}"}, timeout=s.llm_timeout
    )
    response.raise_for_status()
    content = response.json()["choices"][0]["message"].get("content") or ""
    match = re.search(r'"template"\s*:\s*"([^"]+)"', content)
    return match.group(1) if match and match.group(1) in ids else ids[0]

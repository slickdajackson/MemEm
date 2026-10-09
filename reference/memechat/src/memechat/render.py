"""Render memes through the unmodified memegen service (vendor/memegen, HTTP)."""

from urllib.parse import quote, unquote

import httpx

from memechat.config import settings

# Mirrors vendor/memegen/app/utils/text.py:_encode so the service decodes our lines unchanged.
_REPLACEMENTS = [
    ("_", "__"),
    ("-", "--"),
    (" ", "_"),
    ("?", "~q"),
    ("%", "~p"),
    ("#", "~h"),
    ('"', "''"),
    ("/", "~s"),
    ("\\", "~b"),
    ("\n", "~n"),
    ("&", "~a"),
    ("<", "~l"),
    (">", "~g"),
    ("‘", "'"),
    ("’", "'"),
    ("“", '"'),
    ("”", '"'),
    ("–", "-"),
]


def encode_line(line: str) -> str:
    if not line or line == "/":
        return "_"
    has_trailing_under = "_ " in line
    encoded = unquote(line)
    for before, after in _REPLACEMENTS:
        encoded = encoded.replace(before, after)
    if has_trailing_under:
        encoded = encoded.replace("___", "__-")
    return encoded


def image_path(template_id: str, lines: list[str], extension: str = "png") -> str:
    slug = "/".join(quote(encode_line(line), safe="~'") for line in lines) or "_"
    return f"/images/{template_id}/{slug}.{extension}"


def render(template_id: str, lines: list[str], *, client: httpx.Client | None = None) -> bytes:
    url = settings().memegen_url + image_path(template_id, lines)
    response = (client or httpx).get(url, timeout=60, follow_redirects=True)
    response.raise_for_status()
    return response.content

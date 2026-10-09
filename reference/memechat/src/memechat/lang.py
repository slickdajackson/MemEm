"""Cheap German/English detection for chat-sized text (enough to catch English catchphrases in German memes)."""

import re

GERMAN_HINT = re.compile(
    r"[äöüß]|\b(ich|du|nicht|und|das|ist|der|die|wir|mal|auch|schon|noch|bitte|heute|alles|mit|er|hat|sein|es|ein|eine"
    r"|hab|dich|mich|ja|nein|für|auf|zu|wie|wer)\b",
    re.I,
)
ENGLISH_HINT = re.compile(
    r"\b(the|you|your|not|is|are|why|what|this|that|for|of|and|good|idea|try|more|just|when|any|got|should|we|can|do|have|my|it|today)\b", re.I
)


def is_german(text: str) -> bool:
    return bool(GERMAN_HINT.search(text)) and len(GERMAN_HINT.findall(text)) >= len(ENGLISH_HINT.findall(text))


def language_mismatch(message: str, lines: list[str]) -> bool:
    """True if a German message got English meme text (or the other way round)."""
    text = " ".join(lines)
    if is_german(message):
        return not GERMAN_HINT.search(text) or bool(ENGLISH_HINT.search(text))
    if ENGLISH_HINT.search(message):
        return bool(GERMAN_HINT.search(text))
    return False

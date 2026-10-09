"""768-d hashed TF-IDF used when the LiteRT embedding index has not been built yet.

The on-device search is still a full dot-product scan. Query and document vectors
are produced by the same function so Kotlin (`HashEmbedder`) can match these bytes.
LiteRT vectors replace this file's output when `tools/build_index.py` is run.
"""

from __future__ import annotations

import math
import unicodedata
import zlib
from collections import Counter

import numpy as np

DIM = 768


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    chars: list[str] = []
    for ch in text:
        if ch.isalnum():
            chars.append(ch)
        else:
            chars.append(" ")
    return " ".join("".join(chars).split())


def features(text: str) -> list[str]:
    words = [w for w in normalize(text).split(" ") if len(w) >= 2]
    joined = " ".join(words)
    grams = [f"g:{joined[i:i + 3]}" for i in range(max(0, len(joined) - 2))]
    return [f"w:{w}" for w in words] + grams


def _bucket(token: str) -> tuple[int, float]:
    h = zlib.crc32(token.encode("utf-8")) & 0xFFFFFFFF
    sign = 1.0 if (h & 0x80000000) == 0 else -1.0
    return h % DIM, sign


def idf_table(docs: list[str]) -> dict[str, float]:
    df: Counter[str] = Counter()
    for doc in docs:
        df.update(set(features(doc)))
    n = max(1, len(docs))
    return {tok: math.log((n + 1) / (count + 1)) + 1.0 for tok, count in df.items()}


def embed(text: str, idf: dict[str, float], n_docs: int) -> np.ndarray:
    vec = np.zeros(DIM, dtype=np.float32)
    default = math.log((n_docs + 1) / 1) + 1.0
    for tok in features(text):
        idx, sign = _bucket(tok)
        weight = idf.get(tok, default)
        if tok.startswith("g:"):
            weight *= 0.35
        vec[idx] += sign * weight
    norm = float(np.linalg.norm(vec))
    if norm > 1e-8:
        vec /= norm
    return vec


def embed_matrix(docs: list[str], idf: dict[str, float], n_docs: int) -> np.ndarray:
    return np.stack([embed(doc, idf, n_docs) for doc in docs]).astype(np.float32)

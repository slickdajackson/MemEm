"""EmbeddingGemma 2: text and images in one 768-dim space.

Runs in-process via sentence-transformers because oMLX 0.6.4 cannot load the embedding_gemma2 architecture.
"""

import io
import os
from functools import lru_cache
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from sentence_transformers import SentenceTransformer

from memechat.config import settings

ImageInput = Path | bytes | Image.Image


@lru_cache
def _model() -> SentenceTransformer:
    s = settings()
    return SentenceTransformer(
        os.path.expanduser(s.embed_model),
        device=s.embed_device,
        model_kwargs={"torch_dtype": torch.bfloat16},  # fp16 produces NaNs with this model
        config_kwargs={"audio_config": None},  # text + image only (440M)
    )


def _encode(inputs: list, batch_size: int, **kwargs) -> np.ndarray:
    vectors = _model().encode(
        inputs,
        batch_size=batch_size,
        normalize_embeddings=True,
        convert_to_numpy=True,
        truncate_dim=settings().embed_dim,
        **kwargs,
    )
    return vectors.astype(np.float32)


def embed_query(text: str) -> np.ndarray:
    return _encode([text], 1, prompt_name="SearchQuery")[0]


def embed_documents(titles_and_texts: list[tuple[str, str]]) -> np.ndarray:
    return _encode([f"title: {title} | text: {text}" for title, text in titles_and_texts], 32)


def _image(value: ImageInput) -> Image.Image:
    if isinstance(value, Image.Image):
        return value.convert("RGB")
    if isinstance(value, bytes):
        return Image.open(io.BytesIO(value)).convert("RGB")
    return Image.open(value).convert("RGB")


def embed_images(images: list[ImageInput]) -> np.ndarray:
    return _encode([{"image": _image(i)} for i in images], 8)

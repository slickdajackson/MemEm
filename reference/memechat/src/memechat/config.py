import json
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ROOT / ".env", extra="ignore")

    memegen_dir: Path = ROOT / "vendor" / "memegen"
    memegen_url: str = "http://127.0.0.1:5051"
    data_dir: Path = ROOT / "data"

    # LLM (any OpenAI-compatible server; oMLX locally)
    llm_base_url: str = "http://127.0.0.1:8000/v1"
    llm_api_key: str = ""
    llm_model: str = "Qwen3.8-27B-MLX-4bit"
    llm_timeout: float = 300.0

    # Embeddings: EmbeddingGemma 2 via sentence-transformers (oMLX 0.6.4 can't load embedding_gemma2)
    embed_model: str = str(Path.home() / ".omlx/models/google/embeddinggemma-2")
    embed_device: str = "mps"
    embed_dim: int = 768

    qdrant_url: str = "http://127.0.0.1:6333"
    collection: str = "memes"

    captions_per_template: int = 20
    context_messages: int = 8
    w_text: float = 1.0
    w_image: float = 0.5  # blank image alone is the weakest signal (eval: R@5 0.28)
    w_image_text: float = 1.0
    rrf_k: int = 60
    # second stage: LLM picks the best of the top-N vector candidates by name + meaning (text-only call)
    llm_rerank: bool = True
    rerank_candidates: int = 12

    @property
    def captions_dir(self) -> Path:
        return self.data_dir / "captions"

    @property
    def generated_dir(self) -> Path:
        return self.data_dir / "generated"

    @property
    def rendered_dir(self) -> Path:
        return self.data_dir / "rendered"


def _omlx_api_key() -> str:
    path = Path.home() / ".omlx" / "settings.json"
    if not path.exists():
        return ""
    return json.loads(path.read_text()).get("auth", {}).get("api_key", "")


@lru_cache
def settings() -> Settings:
    s = Settings()
    if not s.llm_api_key:
        s.llm_api_key = _omlx_api_key()
    return s

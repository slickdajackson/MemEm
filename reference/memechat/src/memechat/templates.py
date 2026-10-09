from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

from memechat.config import settings


@dataclass(frozen=True)
class Template:
    id: str
    name: str
    source: str
    keywords: tuple[str, ...]
    box_count: int
    example: tuple[str, ...]
    image_path: Path


def _image_path(folder: Path) -> Path:
    # default.gif templates also ship a static default.jpg/png in most cases; prefer static
    for ext in ("jpg", "png", "gif"):
        p = folder / f"default.{ext}"
        if p.exists():
            return p
    raise FileNotFoundError(f"no default image in {folder}")


@lru_cache
def load_templates() -> dict[str, Template]:
    root = settings().memegen_dir / "templates"
    out: dict[str, Template] = {}
    for config in sorted(root.glob("*/config.yml")):
        folder = config.parent
        if folder.name.startswith("_"):  # memegen test fixtures
            continue
        data = yaml.safe_load(config.read_text()) or {}
        text = data.get("text") or [{}, {}]  # memegen default: two boxes
        example = data.get("example") or ["Top Line", "Bottom Line"]
        out[folder.name] = Template(
            id=folder.name,
            name=data.get("name") or folder.name,
            source=data.get("source") or "",
            keywords=tuple(k for k in data.get("keywords") or [] if k),
            box_count=len(text),
            example=tuple(str(e) for e in example),
            image_path=_image_path(folder),
        )
    return out

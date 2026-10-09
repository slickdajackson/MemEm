"""HTTP API + test web UI.

Run: uv run uvicorn memechat.api:app --port 8080
"""

from dataclasses import asdict

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from memechat import pipeline, store
from memechat.config import ROOT, settings
from memechat.search import search

app = FastAPI(title="memechat")
settings().generated_dir.mkdir(parents=True, exist_ok=True)
app.mount("/api/memes", StaticFiles(directory=settings().generated_dir), name="memes")


class SendRequest(BaseModel):
    author: str = Field(min_length=1, max_length=40)
    text: str = Field(min_length=1, max_length=500)


def _out(m: store.Message) -> dict:
    return m.to_dict() | {"image_url": f"/api/memes/{m.image}"}


@app.post("/api/conversations/{conversation_id}/messages")
def send_message(conversation_id: str, request: SendRequest) -> dict:
    try:
        return _out(pipeline.send(conversation_id, request.author.strip(), request.text.strip()))
    except RuntimeError as e:  # LLM produced nothing usable twice
        raise HTTPException(502, str(e)) from e


@app.get("/api/conversations/{conversation_id}/messages")
def list_messages(conversation_id: str, after: float = 0.0) -> list[dict]:
    return [_out(m) for m in store.messages(conversation_id, after=after)]


@app.get("/api/search")
def search_templates(q: str, limit: int = 5) -> list[dict]:
    return [asdict(h) for h in search(q, limit=limit)]


@app.get("/")
def index() -> FileResponse:
    return FileResponse(ROOT / "web" / "index.html")

"""Conversation storage (SQLite). Original texts are kept: they are the LLM's context, memes are what users see."""

import json
import sqlite3
import time
import uuid
from dataclasses import asdict, dataclass, field

from memechat.config import settings


@dataclass
class Message:
    id: str
    conversation_id: str
    author: str
    text: str
    template_id: str
    lines: list[str]
    image: str  # file name in data/generated
    created_at: float
    debug: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return asdict(self)


_SCHEMA = """
create table if not exists messages (
    id text primary key,
    conversation_id text not null,
    author text not null,
    text text not null,
    template_id text not null,
    lines text not null,
    image text not null,
    created_at real not null,
    debug text not null
);
create index if not exists messages_conversation on messages (conversation_id, created_at);
"""


def _db() -> sqlite3.Connection:
    path = settings().data_dir / "chat.db"
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.executescript(_SCHEMA)
    return conn


def new_id() -> str:
    return uuid.uuid4().hex[:12]


def add(message: Message) -> None:
    with _db() as conn:
        conn.execute(
            "insert into messages values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                message.id,
                message.conversation_id,
                message.author,
                message.text,
                message.template_id,
                json.dumps(message.lines, ensure_ascii=False),
                message.image,
                message.created_at,
                json.dumps(message.debug, ensure_ascii=False),
            ),
        )


def messages(conversation_id: str, *, after: float = 0.0, last: int | None = None) -> list[Message]:
    with _db() as conn:
        rows = conn.execute(
            "select * from messages where conversation_id = ? and created_at > ? order by created_at",
            (conversation_id, after),
        ).fetchall()
    result = [
        Message(r[0], r[1], r[2], r[3], r[4], json.loads(r[5]), r[6], r[7], json.loads(r[8])) for r in rows
    ]
    return result[-last:] if last else result


def now() -> float:
    return time.time()

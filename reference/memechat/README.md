# memechat

Chat in which every message arrives as a meme instead of text.

```
message + last N chat messages
  → EmbeddingGemma 2 query vector
  → Qdrant: 3 searches (blank image / captions + meaning text / captions rendered on the image), fused per template (weighted RRF)
  → drop templates used in the last 3 messages and multi-panel templates (>4 boxes)
  → Qwen picks the best of the top 12 by name + meaning (text-only rerank)
  → Qwen3.8-27B (vision) gets template image + example captions + conversation + new message → JSON {"lines": [...]}
     (retried if the language does not match the message)
  → memegen (unmodified, HTTP) renders the lines exactly per template config
```

## Components

| Part | What | Where |
|---|---|---|
| Templates | 209 memegen templates (`templates/<id>/config.yml` + image) | `vendor/memegen` (git clone, unmodified) |
| Render service | memegen Sanic app, `GET /images/<id>/<line>/<line>.png` | `:5051` |
| Example captions | ImgFlip575K + LoC-meme-generator, matched by name + `data/captions/aliases.yml` | `data/captions/*.json` |
| Meaning | per template: meaning DE/EN + 10 typical chat situations, written once by Qwen | `data/captions/*.json` → `meaning` |
| Embeddings | `google/embeddinggemma-2`, 768-dim, text + image in one space, in-process (sentence-transformers on MPS) | `src/memechat/embed.py` |
| Vector DB | Qdrant, one collection `memes`, payload `kind ∈ {image,text,image_text}`, shipped read-only as `data/memes.snapshot` | `:6333` |
| LLM | Qwen3.8-27B-MLX-4bit via oMLX (OpenAI-compatible; any vLLM/SGLang URL works) | `:8000` |
| API + web UI | FastAPI | `:8080` |

oMLX 0.6.4 cannot load the `embedding_gemma2` architecture yet, so embeddings run in-process with the weights from `~/.omlx/models/google/embeddinggemma-2`.

## Run locally (macOS, no Docker)

```bash
make sources setup          # repos, qdrant binary, datasets, venvs
make memegen                # :5051
make qdrant-from-snapshot   # :6333, restores the shipped data/memes.snapshot
# oMLX must be running with Qwen3.8-27B-MLX-4bit (API key is read from ~/.omlx/settings.json)
make serve                  # http://127.0.0.1:8080  (two browser tabs = two users, room via #name)
```

Rebuild the database (only needed when templates/captions change):

```bash
make qdrant captions describe index  # writable qdrant, captions, meanings, embed everything, export data/memes.snapshot
```

## API

- `POST /api/conversations/{id}/messages` `{"author": "...", "text": "..."}` → message with `template_id`, `lines`, `image_url`, `debug.candidates`
- `GET /api/conversations/{id}/messages?after=<ts>` → messages since timestamp
- `GET /api/memes/{file}` → rendered PNG
- `GET /api/search?q=...` → top templates with per-kind ranks (debug)

## Evaluate

```bash
make test   # unit tests (encoding checked against memegen's own encoder, fusion, LLM output parsing)
make eval   # recall@1/@5 of template search per kind and fused (eval/queries.jsonl)
make e2e    # fake chats (eval/fake_chats.json) + DialogSum DE/EN dialogues through the API, judged by Qwen → data/e2e/report-*.html
```

Results: see [EVALUATION.md](EVALUATION.md).

## Config

`.env` (see `.env.example`): model URLs, `W_TEXT` / `W_IMAGE` / `W_IMAGE_TEXT` fusion weights, `CONTEXT_MESSAGES`.

## Licensing notes

- memegen code: MIT. Meme images are third-party content.
- LoC-meme-generator captions: ODC-BY (attribution).
- ImgFlip575K captions: no license stated. They end up in the snapshot payload and LLM prompts; clear this before shipping, or rebuild captions with LoC only.
- DialogSum (test dialogues only, not shipped): CC BY-NC-SA 4.0.
- `docker-compose.yml` / `Dockerfile` sketch the product layout and are untested (no Docker on the dev machine).

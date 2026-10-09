# memechat – local dev on macOS (no Docker needed). See README.md.
SCRATCH ?= data/logs
QDRANT_ENV = QDRANT__STORAGE__STORAGE_PATH=data/qdrant/storage QDRANT__STORAGE__SNAPSHOTS_PATH=data/qdrant/snapshots QDRANT__SERVICE__HOST=127.0.0.1 QDRANT__TELEMETRY_DISABLED=true

.PHONY: setup sources memegen qdrant qdrant-from-snapshot captions describe index serve test eval e2e stop

setup:  ## python deps for memechat + memegen
	uv sync
	cd vendor/memegen && uv venv --python 3.13 .venv -q && POETRY_VIRTUALENVS_IN_PROJECT=true VIRTUAL_ENV=$$PWD/.venv uvx poetry@2.4.3 install --only=main --no-root

sources:  ## memegen repo, qdrant binary, caption + dialogue datasets
	./scripts/fetch_sources.sh

memegen:  ## memegen render service on :5051 (DOMAIN set => deployed mode, configs are not rewritten)
	mkdir -p $(SCRATCH)
	cd vendor/memegen && DOMAIN=localhost:5051 nohup .venv/bin/gunicorn app.main:app --bind 127.0.0.1:5051 --worker-class uvicorn.workers.UvicornWorker --workers 2 > ../../$(SCRATCH)/memegen.log 2>&1 &

qdrant:  ## writable qdrant (for building the index)
	mkdir -p data/qdrant/storage data/qdrant/snapshots $(SCRATCH)
	$(QDRANT_ENV) nohup vendor/qdrant/qdrant > $(SCRATCH)/qdrant.log 2>&1 &

qdrant-from-snapshot:  ## qdrant restored from the shipped data/memes.snapshot (what the product runs)
	rm -rf data/qdrant/runtime && mkdir -p data/qdrant/runtime $(SCRATCH)
	QDRANT__STORAGE__STORAGE_PATH=data/qdrant/runtime QDRANT__STORAGE__SNAPSHOTS_PATH=data/qdrant/runtime-snapshots QDRANT__SERVICE__HOST=127.0.0.1 QDRANT__TELEMETRY_DISABLED=true \
		nohup vendor/qdrant/qdrant --snapshot data/memes.snapshot:memes > $(SCRATCH)/qdrant.log 2>&1 &

captions:  ## data/captions/<template>.json from ImgFlip575K + LoC-meme-generator
	uv run python -m memechat.captions --report

describe:  ## Qwen writes meaning (DE/EN) + typical chat situations per template into data/captions (once, ~20 min)
	uv run python -m memechat.describe

index:  ## embed all templates (image, text, image_text) into qdrant + export snapshot
	uv run python -m memechat.index --snapshot

serve:  ## API + web UI on :8080
	uv run uvicorn memechat.api:app --host 127.0.0.1 --port 8080

test:
	uv run pytest -q tests

eval:  ## search recall per kind / fused
	uv run python scripts/eval_search.py --verbose --rerank

e2e:  ## replay fake + DialogSum chats through the API, judge with Qwen, write data/e2e/report-*.html
	uv run python scripts/e2e_chats.py

stop:
	-pkill -f "vendor/qdrant/qdrant"
	-pkill -f "gunicorn app.main:app --bind 127.0.0.1:5051"
	-pkill -f "uvicorn memechat.api:app"

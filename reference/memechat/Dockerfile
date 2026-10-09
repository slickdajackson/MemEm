# UNTESTED (no Docker on the dev Mac). API container: embeddings in-process, LLM + memegen + qdrant as services.
FROM python:3.13-slim
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
COPY web ./web
RUN uv sync --frozen --no-dev
# templates are read for names/box counts/images; only the template folders are needed
COPY vendor/memegen/templates ./vendor/memegen/templates
CMD ["uv", "run", "--no-sync", "uvicorn", "memechat.api:app", "--host", "0.0.0.0", "--port", "8080"]

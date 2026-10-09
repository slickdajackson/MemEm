#!/usr/bin/env bash
# Downloads everything memechat builds on. Idempotent.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p vendor data/sources/dialogs

[ -d vendor/memegen ] || git clone --depth 1 https://github.com/jacebrowning/memegen.git vendor/memegen

if [ ! -x vendor/qdrant/qdrant ]; then
  mkdir -p vendor/qdrant
  curl -sL https://github.com/qdrant/qdrant/releases/download/v1.19.2/qdrant-aarch64-apple-darwin.tar.gz | tar xz -C vendor/qdrant
fi

# example captions
[ -d data/sources/imgflip575k ] || git clone --depth 1 -q https://github.com/schesa/ImgFlip575K_Dataset.git data/sources/imgflip575k
[ -f data/sources/loc.parquet ] || curl -sL -o data/sources/loc.parquet \
  "https://huggingface.co/datasets/pszemraj/LoC-meme-generator/resolve/main/data/train-00000-of-00001-529ed7e07ac73fd7.parquet"

# real dialogues for end-to-end tests
[ -f data/sources/dialogs/dialogsum_de_test.parquet ] || curl -sL -o data/sources/dialogs/dialogsum_de_test.parquet \
  "https://huggingface.co/datasets/fathyshalab/Dialogsum-german/resolve/main/data/test-00000-of-00001-be1f3a34814b3a35.parquet"
[ -f data/sources/dialogs/dialogsum_en_test.csv ] || curl -sL -o data/sources/dialogs/dialogsum_en_test.csv \
  "https://huggingface.co/datasets/knkarthick/dialogsum/resolve/main/test.csv"
echo "sources ready"

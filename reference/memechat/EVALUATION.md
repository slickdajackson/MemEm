# Evaluation (2026-10-08)

Setup: M5 Pro / 48 GB, Qwen3.8-27B-MLX-4bit via oMLX, EmbeddingGemma 2 (bf16, MPS), Qdrant 1.19.2 restored from `data/memes.snapshot`.

## Database

| | |
|---|---|
| Templates | 209 (all memegen templates; `_test` fixture skipped) |
| Example captions | 1,864 (95 templates with external captions from ImgFlip575K / LoC, 114 with only memegen's own example) |
| Meaning + situations | 209 × (meaning DE + EN, 5 + 5 typical chat situations), generated once by Qwen (`memechat.describe`) |
| Points | 6,491 = 209 `image` + 4,372 `text` + 1,864 `image_text` (1,864 renders by memegen) |
| Snapshot | 156 MiB (was 705 MiB before compacting to one segment + small WAL) |

## Template search (`make eval`, 40 hand-written DE/EN messages)

| Configuration | R@1 | R@5 |
|---|---|---|
| first index, captions only, fused 1/1/1 | 0.30 | 0.60 |
| text only (captions + meaning) | 0.47 | 0.65 |
| image only (blank template) | 0.23 | 0.28 |
| image_text only (captions rendered on image) | 0.25 | 0.53 |
| fused, weights text 1 / image 0.5 / image_text 1 | 0.45 | 0.72 |
| **fused + Qwen rerank of top 12 (default)** | **0.72** | (candidates contain answer: 0.78) |

Fusion beats every single kind at R@5. The rerank picks the right template almost whenever it is among the candidates, so candidate recall is the ceiling. The queries were written by the same person who built the system: treat these numbers as indicative.

## End to end (`make e2e`)

14 chats, 77 messages through the HTTP API: 6 hand-written chats (4 DE, 2 EN) + 4 DialogSum dialogues each in German (fathyshalab/Dialogsum-german) and English (knkarthick/dialogsum).

| | run 1 | run 2 (prompt + no repeats) | run 3 (+ language retry, ≤4 boxes, name stripping) |
|---|---|---|---|
| errors | 0 | 0 | 0 |
| box count / non-empty / valid PNG | 77/77 | 77/77 | 77/77 |
| language check (heuristic) | 69/77 | 72/77 | 77/77 |
| distinct templates | 38 | 46 | 47 |
| latency median / max (s) | 6.3 / 26.7 | 6.5 / 39.5 | 6.7 / 26.2 |
| strict Qwen judge: meaning / fit / funny (1-5) | 2.35 / 1.68 / 1.61 | 2.49 / 1.62 / 1.51 | 2.18 / 1.56 / 1.45 |

A lenient first judge prompt gave ~4.8/5 for everything and was useless. The strict prompt is harsh and partly inconsistent: it penalizes literal copies and also any added joke. Its absolute scores are low, and the differences between runs are within run-to-run noise (generation uses temperature 0.7). The judge does **not** show a quality improvement from the prompt changes. The deterministic checks do improve.

## What works / what does not (manual review of all 3 × 77 memes)

Good: when the message has a meme-shaped structure, e.g.
- "Ananas gehört auf Pizza, ändere meine Meinung" → Change My Mind
- "Angst oder Spaß → warum nicht beides?"
- "Das Meeting hätte auch eine Mail sein können" → That Would Be Great
- "deploying on friday 5pm → it's a trap!"
- "going to the gym every day → for exactly 3 weeks"

Weak:
- Mundane dialogue turns ("Köpfe.", "Hast du eine Münze?") get forced, often meaningless memes. DialogSum is mostly this kind of text.
- Generic templates are over-picked (`red` "Is that what we're going to do today?" 6×, `sad-boehner`, `tenguy`).
- Catchphrases translated into German can be clumsy ("ist das, was wir heute machen?", "ich kann has"), and some still stay English ("feels bad man", "brace yourselves").
- Inventions slip in sometimes ("ich habe eure ganze konversation gelesen" for Overly Attached Girlfriend).

## Next levers

1. Captions for the 114 templates without external examples (more sources, or a curated set).
2. Down-weight generic reaction templates in rerank, or learn template priors from user feedback.
3. Show the top-3 memes and let the user pick, which yields training data for fusion weights and the reranker.
4. A "send as text" fallback when no candidate fits (low fused score).

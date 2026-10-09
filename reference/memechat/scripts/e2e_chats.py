"""End-to-end test: replay fake chats + real dialogues (DialogSum DE/EN) through the HTTP API.

Every turn goes through POST /api/conversations/{id}/messages (search -> Qwen -> memegen render).
Afterwards Qwen judges each rendered meme (image + original message), and a report is written to data/e2e/.

Run (API on :8080): uv run python scripts/e2e_chats.py [--dialogsum 4] [--no-judge]
"""

import argparse
import base64
import csv
import html
import io
import json
import random
import re
import statistics
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import pyarrow.parquet as pq
from PIL import Image

from memechat.config import ROOT, settings
from memechat.lang import ENGLISH_HINT, GERMAN_HINT
from memechat.templates import load_templates

API = "http://127.0.0.1:8080"
OUT = ROOT / "data" / "e2e"
MAX_TURNS = 6

JUDGE_PROMPT = """You are a strict reviewer of a chat app where every text message is sent as a meme instead of text.
Most generated memes have flaws; your job is to find them. Do not be generous.

Original message: {message!r}
Conversation before it:
{history}

Read the text on the meme image carefully, then:
1. List concrete problems: meaning changed or lost, text that contradicts the original, wrong addressee,
   template that does not match the emotion, joke structure of the template not used, awkward/unnatural wording, wrong language.
2. Score each 1-5 using these anchors:
   meaning:  5 = says exactly what the message says; 3 = gist survives but details lost/added; 1 = different or contradictory message
   fit:      5 = this template is the obvious choice for this situation; 3 = works but generic; 1 = template makes no sense here
   funny:    5 = a person would happily send this; 3 = okay but flat; 1 = confusing or cringe
   language: 1 if the meme text is in the same language as the original message, else 0
Answer with JSON only: {{"problems": ["..."], "meaning": n, "fit": n, "funny": n, "language": 0|1}}"""


def _dialogsum(path: Path, n: int, seed: int, lang: str, rows: list[dict]) -> list[dict]:
    """Pick n dialogues with short, chat-like turns between two people."""
    rng = random.Random(seed)
    rng.shuffle(rows)
    chats = []
    for row in rows:
        turns = []
        for line in row["dialogue"].splitlines():
            m = re.match(r"#Person([12])#:\s*(.+)", line.strip())
            if m:
                turns.append(["Anna" if m.group(1) == "1" else "Ben", m.group(2)])
        turns = turns[:MAX_TURNS]
        if len(turns) >= 4 and all(len(t[1]) <= 140 for t in turns):
            chats.append({"name": f"dialogsum-{lang}-{row['id']}", "lang": lang, "turns": turns, "topic": row.get("topic", "")})
        if len(chats) >= n:
            break
    return chats


def load_chats(n_dialogsum: int) -> list[dict]:
    chats = json.loads((ROOT / "eval" / "fake_chats.json").read_text())
    for c in chats:
        c["source"] = "fake"
    src = ROOT / "data" / "sources" / "dialogs"
    de = pq.read_table(src / "dialogsum_de_test.parquet").to_pylist()
    with (src / "dialogsum_en_test.csv").open() as f:
        en = list(csv.DictReader(f))
    for c in _dialogsum(src, n_dialogsum, 7, "de", de) + _dialogsum(src, n_dialogsum, 7, "en", en):
        c["source"] = "dialogsum"
        chats.append(c)
    return chats


def judge(client: httpx.Client, image: bytes, message: str, history: list[list[str]]) -> dict:
    s = settings()
    hist = "\n".join(f"{a}: {t}" for a, t in history) or "(none)"
    body = {
        "model": s.llm_model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(image).decode()}},
                    {"type": "text", "text": JUDGE_PROMPT.format(message=message, history=hist)},
                ],
            }
        ],
        "temperature": 0.0,
        "max_tokens": 500,
        "chat_template_kwargs": {"enable_thinking": False},
        "response_format": {"type": "json_object"},
    }
    r = client.post(f"{s.llm_base_url}/chat/completions", json=body, headers={"Authorization": f"Bearer {s.llm_api_key}"}, timeout=600)
    r.raise_for_status()
    content = r.json()["choices"][0]["message"]["content"] or ""
    return json.loads(re.search(r"\{.*\}", content, re.S).group(0))


def checks(turn: dict, box_count: int, lang: str, image: bytes) -> dict:
    lines = turn["lines"]
    joined = " ".join(lines)
    img = Image.open(io.BytesIO(image))
    looks_german = bool(GERMAN_HINT.search(joined))
    return {
        "box_count_ok": len(lines) == box_count,
        "non_empty": any(lines),
        "png_ok": img.format == "PNG" and min(img.size) > 100,
        # German chats must not keep English catchphrases; English chats must not contain German
        "lang_ok": (looks_german and not ENGLISH_HINT.search(joined)) if lang == "de" else not looks_german,
    }


def run(n_dialogsum: int, do_judge: bool) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    templates = load_templates()
    chats = load_chats(n_dialogsum)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    results = []
    with httpx.Client(timeout=900) as client:
        for chat in chats:
            conv = f"e2e-{stamp}-{chat['name']}"
            print(f"\n== {chat['name']} ({chat['source']}, {chat['lang']})", flush=True)
            for i, (author, text) in enumerate(chat["turns"]):
                t0 = time.time()
                r = client.post(f"{API}/api/conversations/{conv}/messages", json={"author": author, "text": text})
                latency = time.time() - t0
                if r.status_code != 200:
                    print(f"   FAIL {r.status_code}: {r.text[:200]}")
                    results.append({"chat": chat["name"], "turn": i, "author": author, "text": text, "error": r.text[:300], "latency": latency})
                    continue
                m = r.json()
                image = client.get(API + m["image_url"]).content
                turn = {
                    "chat": chat["name"], "source": chat["source"], "lang": chat["lang"], "turn": i, "author": author, "text": text,
                    "template_id": m["template_id"], "template_name": m["debug"]["template_name"], "lines": m["lines"],
                    "image": m["image"], "latency": round(latency, 1),
                    "candidates": [(c["template_id"], round(c["score"], 4)) for c in m["debug"]["candidates"]],
                }
                turn["checks"] = checks(turn, templates[m["template_id"]].box_count, chat["lang"], image)
                if do_judge:
                    try:
                        turn["judge"] = judge(client, image, text, chat["turns"][:i])
                    except Exception as e:  # judge failures must not hide pipeline results
                        turn["judge"] = {"error": str(e)[:200]}
                print(f"   {latency:5.1f}s {author}: {text[:50]!r} -> {m['template_id']} {m['lines']} {turn.get('judge', {})}", flush=True)
                results.append(turn)

    (OUT / f"report-{stamp}.json").write_text(json.dumps(results, ensure_ascii=False, indent=1))
    summary = summarize(results)
    (OUT / f"report-{stamp}.html").write_text(render_html(results, summary, stamp))
    print("\n" + json.dumps(summary, indent=1, ensure_ascii=False))
    print(f"report: {OUT / f'report-{stamp}.html'}")


def summarize(results: list[dict]) -> dict:
    ok = [r for r in results if "error" not in r]
    judged = [r["judge"] for r in ok if "judge" in r and "error" not in r["judge"]]
    templates_used = [r["template_id"] for r in ok]

    def mean(key):
        vals = [float(j[key]) for j in judged if key in j]
        return round(statistics.mean(vals), 2) if vals else None

    return {
        "turns": len(results),
        "errors": len(results) - len(ok),
        "checks_passed": {k: f"{sum(r['checks'][k] for r in ok)}/{len(ok)}" for k in ("box_count_ok", "non_empty", "png_ok", "lang_ok")},
        "latency_s": {"median": round(statistics.median(r["latency"] for r in ok), 1), "max": round(max(r["latency"] for r in ok), 1)} if ok else {},
        "distinct_templates": len(set(templates_used)),
        "most_used": sorted({t: templates_used.count(t) for t in set(templates_used)}.items(), key=lambda kv: -kv[1])[:8],
        "judge": {"n": len(judged), "meaning": mean("meaning"), "fit": mean("fit"), "funny": mean("funny"), "language": mean("language")},
    }


def render_html(results: list[dict], summary: dict, stamp: str) -> str:
    rows = []
    current = None
    for r in results:
        if r["chat"] != current:
            current = r["chat"]
            rows.append(f"<h2>{html.escape(current)}</h2>")
        if "error" in r:
            rows.append(f"<div class='turn err'><b>{html.escape(r['author'])}</b>: {html.escape(r['text'])}<br>ERROR {html.escape(r['error'])}</div>")
            continue
        j = r.get("judge", {})
        jtxt = " · ".join(f"{k} {v}" for k, v in j.items())
        rows.append(
            f"<div class='turn'><div class='meta'><b>{html.escape(r['author'])}</b>: {html.escape(r['text'])}<br>"
            f"<code>{r['template_id']}</code> {html.escape(r['template_name'])} · {r['latency']}s<br>{html.escape(jtxt)}</div>"
            f"<img src='../generated/{r['image']}' loading='lazy'></div>"
        )
    return f"""<!doctype html><meta charset=utf-8><title>E2E {stamp}</title>
<style>body{{font:14px system-ui;margin:16px;max-width:900px}} .turn{{display:flex;gap:12px;margin:8px 0;align-items:flex-start}}
.turn img{{width:320px;border-radius:6px}} .meta{{flex:1}} .err{{color:#b00}} pre{{background:#f3f3f3;padding:8px}}</style>
<h1>E2E report {stamp}</h1><pre>{html.escape(json.dumps(summary, indent=1, ensure_ascii=False))}</pre>{''.join(rows)}"""


def rejudge(report: Path, workers: int = 4) -> None:
    """Re-score an existing report (images already rendered) with the current JUDGE_PROMPT."""
    results = json.loads(report.read_text())
    box_counts = {t.id: t.box_count for t in load_templates().values()}
    jobs, histories = [], {}
    for r in results:
        history = histories.setdefault(r["chat"], [])
        if "error" not in r:
            jobs.append((r, list(history)))
        history.append([r["author"], r["text"]])

    def work(job):
        r, history = job
        image = (settings().generated_dir / r["image"]).read_bytes()
        r["checks"] = checks(r, box_counts[r["template_id"]], r["lang"], image)  # current check definitions
        try:
            r["judge"] = judge(client, image, r["text"], history)
        except Exception as e:
            r["judge"] = {"error": str(e)[:200]}
        return r

    with httpx.Client(timeout=900, limits=httpx.Limits(max_connections=workers)) as client, ThreadPoolExecutor(workers) as pool:
        for n, r in enumerate(pool.map(work, jobs), 1):
            j = r["judge"]
            print(f"   [{n}/{len(jobs)}] {r['template_id']:14s} m{j.get('meaning')} f{j.get('fit')} h{j.get('funny')} l{j.get('language')}", flush=True)
    stamp = report.stem.removeprefix("report-")
    report.write_text(json.dumps(results, ensure_ascii=False, indent=1))
    summary = summarize(results)
    report.with_suffix(".html").write_text(render_html(results, summary, stamp))
    print("\n" + json.dumps(summary, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--rejudge", type=Path, help="re-score an existing data/e2e/report-*.json")
    parser.add_argument("--dialogsum", type=int, default=4, help="dialogues per language from DialogSum")
    parser.add_argument("--no-judge", action="store_true")
    args = parser.parse_args()
    if args.rejudge:
        rejudge(args.rejudge)
    else:
        run(args.dialogsum, not args.no_judge)

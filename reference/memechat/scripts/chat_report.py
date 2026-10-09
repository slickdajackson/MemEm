"""HTML report of the hand-written fake chats from an E2E run: each message next to the meme it became.

Run: uv run python scripts/chat_report.py data/e2e/report-<stamp>.json [out.html]
Images are embedded as compressed JPEG data URIs, so the page is self-contained.
"""

import base64
import html
import io
import json
import statistics
import sys
from pathlib import Path

from PIL import Image

from memechat.config import settings
from memechat.templates import load_templates

CHAT_TITLES = {
    "wg-putzplan": "WG-Putzplan",
    "montag-buero": "Montag im Büro",
    "pizza-abend": "Pizza-Abend",
    "deploy-friday": "Friday Deploy",
    "gym-motivation": "Gym Motivation",
    "pruefung": "Matheklausur",
}


def jpeg_uri(png: Path, width: int = 520) -> str:
    img = Image.open(png).convert("RGB")
    if img.width > width:
        img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=78, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def esc(text: str) -> str:
    return html.escape(str(text), quote=True)


def bubble(turn: dict, side: str, names: dict[str, str]) -> str:
    j = turn.get("judge", {})
    ids = [c[0] for c in turn["candidates"]]
    rank = ids.index(turn["template_id"]) + 1 if turn["template_id"] in ids else None
    rank_note = "Platz 1 der Vektorsuche" if rank == 1 else f"Qwen wählte Platz {rank} der Vektorsuche"
    alternatives = "".join(
        f'<li{" class=\"chosen\"" if tid == turn["template_id"] else ""}><code>{esc(tid)}</code> {esc(names.get(tid, ""))}</li>'
        for tid in ids[:8]
    )
    scores = "".join(
        f'<span class="score" title="{label} (strenger Qwen-Bewerter, 1–5)"><b>{esc(j.get(key, "–"))}</b> {label}</span>'
        for key, label in (("meaning", "Bedeutung"), ("fit", "Passung"), ("funny", "Witz"))
    )
    problem = (j.get("problems") or [""])[0]
    return f"""
<article class="msg {side}">
  <div class="who">{esc(turn["author"])}</div>
  <div class="typed"><span class="label">getippt</span>{esc(turn["text"])}</div>
  <img src="{jpeg_uri(settings().generated_dir / turn["image"])}" alt="{esc(" / ".join(l for l in turn["lines"] if l))}" loading="lazy">
  <div class="meta">
    <span class="tpl">{esc(turn["template_name"])}</span>
    <span class="time">{turn["latency"]:.1f} s</span>
  </div>
  <details>
    <summary>{rank_note} · Bewertung</summary>
    <div class="scores">{scores}</div>
    {f'<p class="problem">{esc(problem)}</p>' if problem else ""}
    <ol class="alts">{alternatives}</ol>
  </details>
</article>"""


def chat_section(name: str, turns: list[dict], names: dict[str, str]) -> str:
    authors = list(dict.fromkeys(t["author"] for t in turns))
    lang = "Deutsch" if turns[0]["lang"] == "de" else "Englisch"
    bubbles = "".join(bubble(t, "left" if t["author"] == authors[0] else "right", names) for t in turns)
    used = len({t["template_id"] for t in turns})
    return f"""
<section class="chat" id="{esc(name)}">
  <header>
    <h2>{esc(CHAT_TITLES.get(name, name))}</h2>
    <p>{esc(" & ".join(authors))} · {lang} · {len(turns)} Nachrichten · {used} verschiedene Memes</p>
  </header>
  <div class="thread">{bubbles}</div>
</section>"""


def build(report: Path, out: Path) -> None:
    turns = [t for t in json.loads(report.read_text()) if t.get("source") == "fake" and "error" not in t]
    names = {t.id: t.name for t in load_templates().values()}
    chats: dict[str, list[dict]] = {}
    for t in turns:
        chats.setdefault(t["chat"], []).append(t)
    judged = [t["judge"] for t in turns if "meaning" in t.get("judge", {})]
    avg = lambda key: statistics.mean(float(j[key]) for j in judged)  # noqa: E731
    reranked = sum(1 for t in turns if t["candidates"][0][0] != t["template_id"])
    stamp = report.stem.removeprefix("report-")
    nav = "".join(f'<a href="#{esc(c)}">{esc(CHAT_TITLES.get(c, c))}</a>' for c in chats)
    sections = "".join(chat_section(c, ts, names) for c, ts in chats.items())
    page = f"""<title>Meme-Chat Testlauf</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Anton&family=Archivo:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
/* Layout: chat threads as two-sided columns of meme bubbles, a slim run summary on top */
:root {{
  --bg: #e9edf1; --surface: #ffffff; --ink: #18202a; --muted: #5d6875; --line: #cfd6de;
  --left: #ffffff; --right: #fff6d6; --accent: #1f5fbf; --chip: #e2e8f0;
  --display: "Anton", Impact, "Arial Narrow", sans-serif;
  --body: "Archivo", system-ui, sans-serif;
  --mono: "IBM Plex Mono", ui-monospace, Menlo, monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg: #12161b; --surface: #1b2129; --ink: #e6ebf0; --muted: #98a3b0; --line: #2d3540;
  --left: #1e252e; --right: #2e2a1b; --accent: #7fb0ff; --chip: #28313c; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --bg: #12161b; --surface: #1b2129; --ink: #e6ebf0; --muted: #98a3b0; --line: #2d3540;
  --left: #1e252e; --right: #2e2a1b; --accent: #7fb0ff; --chip: #28313c; color-scheme: dark; }}
* {{ box-sizing: border-box; }}
body {{ background: var(--bg); color: var(--ink); font: 15px/1.5 var(--body); }}
.wrap {{ max-width: 980px; margin: 0 auto; padding-inline: 16px; padding-block: 32px 64px; display: grid; gap: 40px; }}
.intro h1 {{ font: 400 clamp(2.2rem, 6vw, 3.6rem)/1 var(--display); text-transform: uppercase; letter-spacing: .01em; margin: 0 0 12px; text-wrap: balance; }}
.intro p {{ max-width: 65ch; color: var(--muted); margin: 0; }}
.stats {{ display: flex; flex-wrap: wrap; gap: 10px 28px; margin-top: 20px; font-variant-numeric: tabular-nums; }}
.stats div {{ display: grid; }}
.stats b {{ font: 400 1.6rem/1.1 var(--display); }}
.stats span {{ font: 12px var(--mono); color: var(--muted); text-transform: uppercase; letter-spacing: .06em; }}
nav {{ display: flex; flex-wrap: wrap; gap: 8px; margin-top: 20px; }}
nav a {{ font: 500 13px var(--body); color: var(--ink); text-decoration: none; padding: 5px 12px; border: 1px solid var(--line); border-radius: 999px; background: var(--surface); }}
nav a:hover, nav a:focus-visible {{ border-color: var(--accent); outline: none; }}
.chat {{ display: grid; gap: 16px; }}
.chat header {{ border-bottom: 2px solid var(--ink); padding-bottom: 8px; }}
.chat h2 {{ font: 400 1.9rem/1.1 var(--display); text-transform: uppercase; margin: 0; letter-spacing: .01em; }}
.chat header p {{ margin: 4px 0 0; color: var(--muted); font: 13px var(--mono); }}
.thread {{ display: flex; flex-direction: column; gap: 18px; }}
.msg {{ width: min(440px, 100%); border-radius: 14px; padding: 10px; display: grid; gap: 8px; border: 1px solid var(--line); min-width: 0; }}
.msg.left {{ align-self: flex-start; background: var(--left); border-bottom-left-radius: 4px; }}
.msg.right {{ align-self: flex-end; background: var(--right); border-bottom-right-radius: 4px; }}
.who {{ font: 600 13px var(--body); }}
.typed {{ font-size: 14px; color: var(--muted); }}
.typed .label {{ font: 11px var(--mono); text-transform: uppercase; letter-spacing: .06em; margin-right: 8px; color: var(--muted); }}
.msg img {{ display: block; width: 100%; border-radius: 8px; }}
.meta {{ display: flex; justify-content: space-between; gap: 8px; align-items: baseline; font-size: 13px; }}
.tpl {{ font-weight: 600; min-width: 0; }}
.time {{ font: 12px var(--mono); color: var(--muted); font-variant-numeric: tabular-nums; white-space: nowrap; }}
details {{ font-size: 13px; color: var(--muted); }}
summary {{ cursor: pointer; font: 12px var(--mono); }}
summary:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 2px; }}
.scores {{ display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }}
.score {{ background: var(--chip); border-radius: 6px; padding: 2px 8px; font-variant-numeric: tabular-nums; }}
.score b {{ color: var(--ink); }}
.problem {{ margin: 8px 0 0; }}
.alts {{ margin: 8px 0 0; padding-left: 22px; font-size: 12.5px; }}
.alts code {{ font: 12px var(--mono); }}
.alts .chosen {{ color: var(--ink); font-weight: 600; }}
footer {{ color: var(--muted); font: 12px var(--mono); }}
</style>
<div class="wrap">
  <section class="intro">
    <h1>Meme-Chat Testlauf</h1>
    <p>Sechs erfundene Chats, Nachricht für Nachricht durch die App geschickt. Über jedem Meme steht, was die Person eigentlich getippt hat. Darunter: welches Template gewählt wurde, wie lange es dauerte und, aufklappbar, die Kandidaten aus der Vektorsuche plus die Einschätzung eines bewusst strengen Qwen-Bewerters.</p>
    <div class="stats">
      <div><b>{len(turns)}</b><span>Nachrichten</span></div>
      <div><b>{len({t["template_id"] for t in turns})}</b><span>versch. Memes</span></div>
      <div><b>{statistics.median(t["latency"] for t in turns):.1f} s</b><span>Median pro Meme</span></div>
      <div><b>{reranked}/{len(turns)}</b><span>von Qwen umsortiert</span></div>
      <div><b>{avg("meaning"):.1f} · {avg("fit"):.1f} · {avg("funny"):.1f}</b><span>Bedeutung · Passung · Witz (1–5)</span></div>
    </div>
    <nav>{nav}</nav>
  </section>
  {sections}
  <footer>Lauf {esc(stamp)} · Qwen3.8-27B (oMLX) · EmbeddingGemma 2 · Qdrant-Snapshot mit 6.491 Punkten · Rendering: memegen</footer>
</div>
"""
    out.write_text(page)
    print(out, f"{out.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    report = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else report.with_name("fake-chats.html")
    build(report, out)

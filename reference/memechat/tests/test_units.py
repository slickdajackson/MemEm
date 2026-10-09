import json
import subprocess

import pytest
from qdrant_client.models import ScoredPoint

from memechat.config import settings
from memechat.generate import parse_lines
from memechat.render import encode_line
from memechat.search import fuse

LINES = ["hello world", "Größe 100%?", "a/b & c_d - e", "#tag \"quoted\" <x>", "under_ score", "", "back\\slash", "it’s “smart”"]


def test_encode_line_matches_memegen():
    memegen = settings().memegen_dir
    script = "import json,sys; from app.utils.text import encode; print(json.dumps([encode([l]) for l in json.load(sys.stdin)]))"
    out = subprocess.run(
        [str(memegen / ".venv/bin/python"), "-c", script], input=json.dumps(LINES), capture_output=True, text=True, cwd=memegen, check=True
    )
    assert [encode_line(line) for line in LINES] == json.loads(out.stdout)


def _p(tid, score):
    return ScoredPoint(id=1, version=0, score=score, payload={"template_id": tid, "lines": ["x"]})


def test_fuse_ranks_templates_by_best_point_and_ignores_duplicates():
    per_kind = {
        "text": [_p("a", 0.9), _p("a", 0.8), _p("b", 0.7)],
        "image": [_p("b", 0.5), _p("a", 0.4)],
    }
    hits = fuse(per_kind, {"text": 1.0, "image": 1.0}, k=60)
    by_id = {h.template_id: h for h in hits}
    assert by_id["a"].kinds["text"].rank == 1 and by_id["b"].kinds["text"].rank == 2  # duplicate "a" does not push b down
    assert by_id["a"].score == pytest.approx(1 / 61 + 1 / 62)
    assert by_id["b"].score == pytest.approx(1 / 62 + 1 / 61)


def test_fuse_weight_zero_kind_does_not_count():
    hits = fuse({"text": [_p("a", 0.9)], "image": [_p("b", 0.9)]}, {"text": 1.0, "image": 0.0}, k=60)
    assert hits[0].template_id == "a"


def test_parse_lines_pads_trims_and_cleans():
    assert parse_lines('{"lines": ["  \\"Hi\\"  ", "x - y"]}', 3) == ["Hi", "x, y", ""]
    assert parse_lines('noise {"lines": ["a", "b", "c"]} noise', 2) == ["a", "b"]
    with pytest.raises(ValueError):
        parse_lines('{"lines": ["", ""]}', 2)


def test_strip_speakers_and_language_mismatch():
    from memechat.generate import strip_speakers
    from memechat.lang import language_mismatch

    assert strip_speakers(["Ben: tails, I win", "ok"], {"Ben", "Anna"}) == ["tails, I win", "ok"]
    assert language_mismatch("Heute Nacht alles lernen", ["heute nacht alles lernen", "probably not a good idea"])
    assert not language_mismatch("Heute Nacht alles lernen", ["alles in einer Nacht", "wahrscheinlich keine gute Idee"])
    assert language_mismatch("Should we deploy today?", ["Heute deployen?", "nein"])

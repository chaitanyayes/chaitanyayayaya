import json
import sys
from pathlib import Path

import anthropic
import httpx2 as httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hinge_opener as ho  # noqa: E402

GOOD = {
    "photos": [], "prompts": [], "avg_prompt_quality": 4, "candidates": [],
    "best_reply": "Is this the RTO? Because I'd wait in line for you",
    "target": "photo 2",
    "reason": "Plays on the queue in her photo",
    "runner_ups": [
        {"reply": "Chai or coffee, choose wisely", "target": "prompt: My simple pleasures"},
        {"reply": "", "target": "photo 1"},
    ],
}


def test_parse_plain():
    r = ho.parse_result(json.dumps(GOOD))
    assert r.best.text.startswith("Is this the RTO")
    assert r.best.target == "Photo 2"
    assert len(r.runner_ups) == 1
    assert r.runner_ups[0].target == "Prompt: My simple pleasures"


def test_parse_fenced_and_prose():
    assert ho.parse_result("```json\n" + json.dumps(GOOD) + "\n```").best.target == "Photo 2"
    assert ho.parse_result("Here:\n" + json.dumps(GOOD) + "\nbye").best.target == "Photo 2"


@pytest.mark.parametrize("raw", ["Sorry, can't help.", '{"target": "photo 1"}', "[1, 2]"])
def test_parse_rejects(raw):
    with pytest.raises(ho.OpenerError):
        ho.parse_result(raw)


def _message(text, stop_reason="end_turn"):
    return {
        "id": "msg_1", "type": "message", "role": "assistant", "model": ho.MODEL,
        "content": [{"type": "thinking", "thinking": "", "signature": "x"}, {"type": "text", "text": text}],
        "stop_reason": stop_reason, "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }


def _client(handler):
    return anthropic.Anthropic(api_key="sk-test", max_retries=0,
                               http_client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_generate_sends_expected_request():
    seen = {}

    def handler(request: httpx.Request):
        seen["headers"] = request.headers
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_message(json.dumps(GOOD)))

    r = ho.generate("sk-test", [b"\xff\xd8jpeg"], "my style", 2, client=_client(handler))
    assert r.best.target == "Photo 2"
    body = seen["body"]
    assert body["model"] == "claude-sonnet-5-5"
    assert body["fallbacks"] == "default"
    assert ho.FALLBACK_BETA in seen["headers"]["anthropic-beta"]
    assert body["output_config"]["format"]["type"] == "json_schema"
    assert "my style" in body["system"]
    content = body["messages"][0]["content"]
    assert content[1]["type"] == "image" and content[1]["source"]["media_type"] == "image/jpeg"
    assert "exactly 2 runner_ups" in content[-1]["text"]


def test_generate_retries_without_fallback_on_400():
    calls = []

    def handler(request: httpx.Request):
        body = json.loads(request.content)
        calls.append(body)
        if "fallbacks" in body:
            return httpx.Response(400, json={"type": "error", "error": {
                "type": "invalid_request_error", "message": "fallbacks: not supported"}})
        return httpx.Response(200, json=_message(json.dumps(GOOD)))

    r = ho.generate("sk-test", [b"x"], "s", 2, client=_client(handler))
    assert r.best.text
    assert len(calls) == 2 and "fallbacks" not in calls[1]


@pytest.mark.parametrize("status,needle", [
    (401, "Invalid API key"), (429, "Rate limited"), (529, "overloaded"), (500, "server error"),
])
def test_generate_error_messages(status, needle):
    def handler(request):
        return httpx.Response(status, json={"type": "error", "error": {"type": "x", "message": "m"}})

    with pytest.raises(ho.OpenerError, match=needle):
        ho.generate("sk-test", [b"x"], "s", 2, client=_client(handler))


def test_generate_refusal():
    def handler(request):
        return httpx.Response(200, json=_message("", stop_reason="refusal"))

    with pytest.raises(ho.OpenerError, match="declined"):
        ho.generate("sk-test", [b"x"], "s", 2, client=_client(handler))


def test_generate_needs_key_and_images():
    with pytest.raises(ho.OpenerError, match="No API key"):
        ho.generate("", [b"x"], "s", 2)
    with pytest.raises(ho.OpenerError, match="No captures"):
        ho.generate("k", [], "s", 2)


def test_capture_region_downscales(monkeypatch):
    from PIL import Image

    class FakeShot:
        size = (2160, 3000)
        rgb = Image.new("RGB", size, (200, 120, 90)).tobytes()

    class FakeMss:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def grab(self, region): return FakeShot()

    monkeypatch.setattr(ho.mss, "mss", lambda: FakeMss())
    jpeg = ho.capture_region({"left": 0, "top": 0, "width": 2160, "height": 3000})
    img = Image.open(__import__("io").BytesIO(jpeg))
    assert img.format == "JPEG" and img.width == 1080 and img.height == 1500


def test_capture_region_black(monkeypatch):
    from PIL import Image

    class FakeShot:
        size = (100, 200)
        rgb = Image.new("RGB", size, (0, 0, 0)).tobytes()

    class FakeMss:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def grab(self, region): return FakeShot()

    monkeypatch.setattr(ho.mss, "mss", lambda: FakeMss())
    with pytest.raises(ho.OpenerError, match="black"):
        ho.capture_region({"left": 0, "top": 0, "width": 100, "height": 200})

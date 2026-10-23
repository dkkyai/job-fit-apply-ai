import base64
import json
import threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from jobbot_actions import core


@pytest.mark.parametrize("data,expected", [
    ("apply:7663", ("apply", 7663)),
    ("reply:1", ("reply", 1)),
    ("archive:42", ("archive", 42)),
    ("undo:42", ("undo", 42)),
    ("tailor:7720", ("tailor", 7720)),
    ("send:3", ("send", 3)),
    ("cancel:3", ("cancel", 3)),
    ("submit:4", ("submit", 4)),
    ("discard:4", ("discard", 4)),
    ("resume:4", ("resume", 4)),
])
def test_parses_our_callbacks(data, expected):
    assert core.parse_callback(data) == expected


@pytest.mark.parametrize("data", [
    None, "", "apply", "apply:", "apply:0", "apply:-1", "apply:12a", "apply:12 ", " apply:12",
    "Apply:12", "Tailor:12", "tailor:", "delete:12", "ea:once:3", "cl:9:1", "mp:openai", "apply:1234567890123456789",
    "Apply: Acme / Staff SDET #deadbeef",
])
def test_ignores_everything_else(data):
    assert core.parse_callback(data) is None


def test_allowlist_is_exact_and_blank_means_nobody():
    allowed = core.allowed_users(" 8679792351, 42 ,")
    assert core.is_allowed(8679792351, allowed)
    assert core.is_allowed("42", allowed)
    assert not core.is_allowed(867979235, allowed)
    assert core.allowed_users("") == set()


LINKS = [{"text": "View Report", "url": "http://h/r.md"}, {"text": "View Resume", "url": "http://h/r.pdf"}]
ACTIONS = [{"text": "Apply", "callback_data": "apply:9"}, {"text": "Archive", "callback_data": "archive:9"}]


def test_keyboard_unchanged_when_backend_says_nothing():
    assert core.rebuild_keyboard([LINKS, ACTIONS], None) is None


def test_keyboard_replaces_our_actions_and_keeps_links():
    undo = [{"text": "Undo", "callback_data": "undo:9"}]
    assert core.rebuild_keyboard([LINKS, ACTIONS], undo) == [LINKS, undo]


def test_keyboard_drops_the_action_row_when_told_to():
    assert core.rebuild_keyboard([LINKS, ACTIONS], []) == [LINKS]
    assert core.rebuild_keyboard([ACTIONS], []) == []


def test_keyboard_keeps_buttons_that_are_not_ours():
    foreign = [{"text": "Allow", "callback_data": "ea:once:1"}, {"text": "Apply", "callback_data": "apply:9"}]
    assert core.rebuild_keyboard([foreign], []) == [[{"text": "Allow", "callback_data": "ea:once:1"}]]


class _Backend(BaseHTTPRequestHandler):
    seen = []

    def _reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).seen.append((self.path, self.headers.get("Authorization"), body))
        self._reply(200, {"outcome": "not_implemented", "reply": "Not implemented yet"})

    def do_GET(self):
        type(self).seen.append((self.path, self.headers.get("Authorization"), None))
        self._reply(200, {"text": "JobBot status\nMode: live"})

    def log_message(self, *a):
        pass


@pytest.fixture
def backend():
    _Backend.seen = []
    server = HTTPServer(("127.0.0.1", 0), _Backend)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def test_tap_round_trip_carries_the_token_and_payload(backend):
    client = core.JobbotMcp(backend, "tok")
    payload = core.tap_payload("apply", 9, 8679792351, 8679792351, 5, 1790000000)
    assert client.tap(payload)["reply"] == "Not implemented yet"
    path, auth, body = _Backend.seen[-1]
    assert (path, auth) == ("/plugin/tap", "Bearer tok")
    assert body == {"verb": "apply", "seq": 9, "user_id": "8679792351", "chat_id": "8679792351",
                    "message_id": 5, "message_date": 1790000000}


def test_status_text(backend):
    assert core.safe_status(core.JobbotMcp(backend, "tok")).startswith("JobBot status")


def test_status_when_backend_is_down():
    assert core.safe_status(core.JobbotMcp("http://127.0.0.1:9", "tok", timeout=2)).startswith(core.UNAVAILABLE)


@pytest.mark.parametrize("result,expected", [
    ('{"approval_id": 12, "status": "awaiting"}', 12),
    ('[{"type":"text","text":"{\\n  \\"approval_id\\": 5\\n}"}]', 5),
    ({"content": '{"approval_id":9}'}, 9),
    ("no id here", None),
    (None, None),
])
def test_approval_id_is_found_in_tool_results(result, expected):
    assert core.approval_id_from_result(result) == expected


def test_only_the_approval_tool_triggers_posting():
    assert core.is_send_approval_tool("mcp__jfaa__request_send_approval")
    assert not core.is_send_approval_tool("mcp__jfaa__write_reply_draft")
    assert not core.is_send_approval_tool(None)



# ── resume uploads ────────────────────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("name,expected", [
    ("Richard Hatcher.pdf", ".pdf"), ("resume.DOCX", ".docx"), ("a.b.PdF", ".pdf"), ("  cv.docx ", ".docx"),
    ("resume.doc", None), ("resume.txt", None), ("resume.pdf.exe", None), ("pdf", None), ("", None), (None, None),
])
def test_resume_extension(name, expected):
    assert core.resume_extension(name) == expected


@pytest.mark.parametrize("caption,expected", [
    ("apply to this", True), ("Apply", True), ("please APPLY with this resume", True), ("applying to this one", True),
    ("apply: https://x.com/j", True), ("apply, thanks", True),
    ("", False), (None, False), ("here is my resume", False), ("application", False), ("reapply", False),
    ("appliance", False), ("supply", False),
])
def test_wants_apply(caption, expected):
    assert core.wants_apply(caption) is expected


@pytest.mark.parametrize("text,expected", [
    ("Acme: Staff SDET (80)\n#J7663", 7663),
    ("#J7663", 7663),
    ("old #J1 then #J7701", 7701),
    ("Sparksoft #J7708 trailing", 7708),
    ("#J0", None), ("#j7663", None), ("J7663", None), ("#J", None), ("no ref", None), ("", None), (None, None),
])
def test_job_ref_from(text, expected):
    assert core.job_ref_from(text) == expected


@pytest.mark.parametrize("text,expected", [
    ("apply https://jobs.lever.co/acme/123", "https://jobs.lever.co/acme/123"),
    ("apply here: https://jobs.lever.co/acme/123.", "https://jobs.lever.co/acme/123"),
    ("apply (https://x.com/jobs/1)", "https://x.com/jobs/1"),
    ("apply <https://x.com/j?id=4&b=2>", "https://x.com/j?id=4&b=2"),
    ("https://a.com/x and https://b.com/y", "https://a.com/x"),
    ("HTTP://A.COM/x", "HTTP://A.COM/x"),
    ("apply www.example.com/job", None), ("apply ftp://x.com/j", None), ("apply", None), ("", None), (None, None),
])
def test_job_url_from(text, expected):
    assert core.job_url_from(text) == expected


def test_card_payload_carries_the_card_and_no_url():
    p = core.apply_resume_payload(8679792351, 8679792351, "r.pdf", b"%PDF", seq=7663, message_id=55, message_date=1790812800)
    assert p == {"user_id": "8679792351", "chat_id": "8679792351", "file_name": "r.pdf", "file_data": "JVBERg==",
                 "seq": 7663, "message_id": 55, "message_date": 1790812800}


def test_link_payload_carries_the_url_and_no_card():
    p = core.apply_resume_payload(1, 2, "r.docx", b"PK", job_url="https://x.com/j")
    assert p == {"user_id": "1", "chat_id": "2", "file_name": "r.docx", "file_data": "UEs=", "job_url": "https://x.com/j"}


def test_apply_resume_round_trip_carries_the_token_path_and_body(backend):
    client = core.JobbotMcp(backend, "tok")
    payload = core.apply_resume_payload(1, 1, "r.pdf", b"%PDF", job_url="https://x.com/j")
    assert client.apply_resume(payload)["reply"] == "Not implemented yet"
    path, auth, body = _Backend.seen[-1]
    assert (path, auth) == ("/plugin/apply-resume", "Bearer tok")
    assert body == payload


def test_a_down_backend_raises_for_apply_resume():
    with pytest.raises(OSError):
        core.JobbotMcp("http://127.0.0.1:9", "tok", timeout=1).apply_resume({"x": 1})


def test_outbox_document_round_trip_carries_the_token(backend):
    class Doc(_Backend):
        def do_GET(self):
            type(self).seen.append((self.path, self.headers.get("Authorization"), None))
            data = b"%PDF-1.4 bytes"
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    Doc.seen = []
    server = HTTPServer(("127.0.0.1", 0), Doc)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = core.JobbotMcp(f"http://127.0.0.1:{server.server_address[1]}", "tok")
        assert client.outbox_document(7) == b"%PDF-1.4 bytes"
        assert Doc.seen[-1][:2] == ("/plugin/outbox/7/document", "Bearer tok")
    finally:
        server.shutdown()


# ── the wire contract shared with jobbot-mcp's Kotlin tests ────────────────────────────────────────────

WIRE = json.loads((Path(__file__).resolve().parents[1] / "contract" / "plugin_wire.json").read_text())


@pytest.mark.parametrize("case", WIRE["tap_requests"], ids=lambda c: c["name"])
def test_tap_payloads_match_the_contract(case):
    a = case["args"]
    assert core.tap_payload(a["verb"], a["seq"], a["user_id"], a["chat_id"], a["message_id"], a["message_date"]) == case["payload"]


@pytest.mark.parametrize("case", WIRE["resume_apply_requests"], ids=lambda c: c["name"])
def test_resume_payloads_match_the_contract(case):
    a = dict(case["args"])
    data = base64.b64decode(a.pop("data_b64"))
    built = core.apply_resume_payload(a.pop("user_id"), a.pop("chat_id"), a.pop("file_name"), data, **a)
    assert built == case["payload"]
    assert json.loads(json.dumps(built)) == case["payload"], "the payload survives JSON unchanged"

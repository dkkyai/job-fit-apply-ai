"""The plugin's Telegram glue, driven with stand-ins for python-telegram-bot and Hermes so it runs
without either installed. Covers the paths a real tap takes through on_button."""
import asyncio
import sys
import types
from datetime import datetime, timezone

import pytest


class ApplicationHandlerStop(Exception):
    pass


class CallbackQueryHandler:
    def __init__(self, callback, pattern=None):
        self.callback, self.pattern = callback, pattern


class InlineKeyboardButton:
    def __init__(self, text, url=None, callback_data=None):
        self.text, self.url, self.callback_data = text, url, callback_data


class InlineKeyboardMarkup:
    def __init__(self, inline_keyboard):
        self.inline_keyboard = inline_keyboard


class MessageHandler:
    def __init__(self, filters, callback):
        self.filters, self.callback = filters, callback


class MessageEvent:
    def __init__(self, **kw):
        self.__dict__.update(kw)


@pytest.fixture(autouse=True)
def fake_libs(monkeypatch):
    telegram = types.ModuleType("telegram")
    telegram.InlineKeyboardButton, telegram.InlineKeyboardMarkup = InlineKeyboardButton, InlineKeyboardMarkup
    ext = types.ModuleType("telegram.ext")
    ext.ApplicationHandlerStop, ext.CallbackQueryHandler = ApplicationHandlerStop, CallbackQueryHandler
    ext.MessageHandler = MessageHandler
    ext.filters = types.SimpleNamespace(Document=types.SimpleNamespace(ALL="document-filter"))
    event_mod = types.ModuleType("gateway.platforms.event")
    event_mod.MessageEvent = MessageEvent
    for name, mod in {
        "telegram": telegram, "telegram.ext": ext, "gateway": types.ModuleType("gateway"),
        "gateway.platforms": types.ModuleType("gateway.platforms"), "gateway.platforms.event": event_mod,
    }.items():
        monkeypatch.setitem(sys.modules, name, mod)


class Recorder:
    def __init__(self):
        self.calls = []

    def __call__(self, name, ret=None):
        async def fn(*args, **kwargs):
            self.calls.append((name, args, kwargs))
            return ret
        return fn


class FakeClient:
    def __init__(self, result=None, error=None):
        self.result, self.error, self.payloads, self.resumes = result, error, [], []

    def apply_resume(self, payload):
        self.resumes.append(payload)
        if self.error:
            raise self.error
        return self.result

    def tap(self, payload):
        self.payloads.append(payload)
        if self.error:
            raise self.error
        return self.result


class TelegramRefused(Exception):
    """What python-telegram-bot raises when Telegram says no, e.g. Photo_invalid_dimensions."""


class FakeBot:
    def __init__(self, fail_photo=None, fail_message=None, fail_document=None):
        self.sent = []
        self.photos = []
        self.documents = []
        self.fail_photo, self.fail_message, self.fail_document = fail_photo, fail_message, fail_document

    async def send_message(self, **kw):
        if self.fail_message:
            raise self.fail_message
        self.sent.append(kw)

    async def send_photo(self, **kw):
        if self.fail_photo:
            raise self.fail_photo
        self.photos.append(kw)

    async def send_document(self, **kw):
        if self.fail_document:
            raise self.fail_document
        self.documents.append(kw)


class FakeAdapter:
    def __init__(self, rec):
        self.rec = rec
        self.handle_message = rec("handle_message")

    def build_source(self, **kw):
        return {"source": kw}


def wire(monkeypatch, client, allowed="8679792351"):
    import jobbot_actions as plugin
    monkeypatch.setenv("JOBBOT_MCP_TOKEN", "tok")
    monkeypatch.setenv("TELEGRAM_ALLOWED_USERS", allowed)
    monkeypatch.setenv("TELEGRAM_HOME_CHANNEL", "8679792351")
    registered = {}

    class Ctx:
        def register_telegram_handler(self, factory):
            registered["factory"] = factory

        def register_command(self, name, handler, description=""):
            registered[name] = handler

        def register_hook(self, name, fn):
            registered["hook:" + name] = fn

    plugin.register(Ctx())
    plugin._attempts.clear()
    plugin._client = client
    handlers = []

    class App:
        bot = FakeBot()

        def add_handler(self, handler, group=0):
            handlers.append((handler, group))

    rec = Recorder()
    adapter = FakeAdapter(rec)
    registered["factory"](App(), adapter)
    registered["handlers"] = handlers
    (handler, group), = [(h, g) for h, g in handlers if isinstance(h, CallbackQueryHandler)]
    return handler, group, rec, registered


def tap(rec, data="apply:7663", user=8679792351):
    message = types.SimpleNamespace(
        chat_id=8679792351, message_id=55, date=datetime(2026, 10, 1, tzinfo=timezone.utc),
        text="High-fit: Acme — Staff SDET — 80\n#J7663", caption=None,
        reply_markup=InlineKeyboardMarkup([
            [InlineKeyboardButton("View Report", url="http://h/r.md")],
            [InlineKeyboardButton("Apply", callback_data="apply:7663"),
             InlineKeyboardButton("Archive", callback_data="archive:7663")],
        ]),
        reply_text=rec("reply_text"),
    )
    query = types.SimpleNamespace(
        data=data, from_user=types.SimpleNamespace(id=user, first_name="Richard"), message=message,
        answer=rec("answer"), edit_message_reply_markup=rec("edit_markup"),
    )
    return types.SimpleNamespace(callback_query=query)


def run(handler, update):
    return asyncio.run(handler.callback(update, None))


def test_handler_is_scoped_and_runs_before_hermes(monkeypatch):
    handler, group, _, registered = wire(monkeypatch, FakeClient({}))
    assert handler.pattern == r"^(apply|reply|archive|undo|tailor|send|cancel|submit|discard|resume):(\d{1,18})$"
    assert "jdaccounts" in registered
    assert group < 0
    assert "jdstatus" in registered


def test_apply_tap_forwards_and_replies_not_implemented(monkeypatch):
    client = FakeClient({"outcome": "not_implemented", "reply": "Not implemented yet"})
    handler, _, rec, _ = wire(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    assert client.payloads == [{"verb": "apply", "seq": 7663, "user_id": "8679792351", "chat_id": "8679792351",
                                "message_id": 55, "message_date": 1790812800}]
    names = [c[0] for c in rec.calls]
    assert names == ["answer", "reply_text"], names
    assert rec.calls[1][1] == ("Not implemented yet",)


def test_strangers_are_refused_without_reaching_the_backend(monkeypatch):
    client = FakeClient({})
    handler, _, rec, _ = wire(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, user=1))
    assert client.payloads == []
    assert rec.calls == [("answer", ("Not authorized.",), {})]


def test_foreign_callbacks_pass_through(monkeypatch):
    client = FakeClient({})
    handler, _, rec, _ = wire(monkeypatch, client)
    assert run(handler, tap(rec, data="ea:once:3")) is None
    assert rec.calls == [] and client.payloads == []


def test_backend_failure_answers_unavailable(monkeypatch):
    import jobbot_actions.core as core
    handler, _, rec, _ = wire(monkeypatch, FakeClient(error=OSError("refused")))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    assert rec.calls == [("answer", (core.UNAVAILABLE,), {})]


def test_action_row_edits_the_keyboard_keeping_links(monkeypatch):
    result = {"outcome": "done", "toast": "Archived.", "action_row": [{"text": "Undo", "callback_data": "undo:7663"}]}
    handler, _, rec, _ = wire(monkeypatch, FakeClient(result))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="archive:7663"))
    edit = next(c for c in rec.calls if c[0] == "edit_markup")
    rows = edit[2]["reply_markup"].inline_keyboard
    assert [[b.text for b in r] for r in rows] == [["View Report"], ["Undo"]]


def test_empty_action_row_removes_the_keyboard_when_nothing_else_is_left(monkeypatch):
    handler, _, rec, _ = wire(monkeypatch, FakeClient({"outcome": "expired", "toast": "x", "action_row": []}))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    edit = next(c for c in rec.calls if c[0] == "edit_markup")
    assert [[b.text for b in r] for r in edit[2]["reply_markup"].inline_keyboard] == [["View Report"]]


def test_agent_prompt_wakes_the_agent_as_a_reply_to_the_card(monkeypatch):
    result = {"outcome": "agent", "agent_prompt": "[JobBot action] Reply on #J7663"}
    handler, _, rec, _ = wire(monkeypatch, FakeClient(result))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="reply:7663"))
    (_, (event,), _), = [c for c in rec.calls if c[0] == "handle_message"]
    assert event.text == "[JobBot action] Reply on #J7663"
    assert event.reply_to_message_id == "55"
    assert "#J7663" in event.reply_to_text
    assert event.allow_gateway_control is False
    assert event.source == {"source": {"chat_id": "8679792351", "chat_type": "dm", "user_id": "8679792351", "user_name": "Richard"}}


def test_send_and_cancel_taps_are_forwarded_with_the_approval_id(monkeypatch):
    client = FakeClient({"outcome": "done", "toast": "Sent.", "reply": "Sent to r@x.com.", "action_row": []})
    handler, _, rec, _ = wire(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="send:12"))
    assert client.payloads[0]["verb"] == "send" and client.payloads[0]["seq"] == 12


def test_a_reply_with_buttons_carries_its_own_keyboard(monkeypatch):
    result = {"outcome": "awaiting_approval", "reply": "Reply draft for #J7663 …",
              "reply_row": [{"text": "✅ Send", "callback_data": "send:4"}, {"text": "✖ Cancel", "callback_data": "cancel:4"}]}
    handler, _, rec, _ = wire(monkeypatch, FakeClient(result))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="reply:7663"))
    (_, args, kwargs), = [c for c in rec.calls if c[0] == "reply_text"]
    assert args == ("Reply draft for #J7663 …",)
    assert [b.callback_data for b in kwargs["reply_markup"].inline_keyboard[0]] == ["send:4", "cancel:4"]


def test_request_send_approval_posts_the_preview_with_send_and_cancel(monkeypatch):
    import threading
    import jobbot_actions as plugin

    class Approvals(FakeClient):
        def approval(self, approval_id):
            assert approval_id == 7
            return {"reply": "Reply draft for #J1 — tap ✅ Send", "reply_row": [{"text": "✅ Send", "callback_data": "send:7"}]}

    _, _, _, registered = wire(monkeypatch, Approvals({}))
    loop = asyncio.new_event_loop()
    t = threading.Thread(target=loop.run_forever, daemon=True)
    t.start()
    plugin._loop = loop
    try:
        hook = registered["hook:post_tool_call"]
        hook(tool_name="mcp__jfaa__get_job", args={}, result='{"approval_id": 7}', task_id="t")
        hook(tool_name="mcp__jfaa__request_send_approval", args={"ref": "#J1"},
             result='{"approval_id": 7, "status": "awaiting"}', task_id="t")
        import time
        for _ in range(50):
            if plugin._bot.sent:
                break
            time.sleep(0.02)
        (sent,) = plugin._bot.sent
        assert sent["chat_id"] == 8679792351
        assert sent["text"].startswith("Reply draft for #J1")
        assert [b.callback_data for b in sent["reply_markup"].inline_keyboard[0]] == ["send:7"]
    finally:
        loop.call_soon_threadsafe(loop.stop)


class OutboxClient(FakeClient):
    def __init__(self, items):
        super().__init__({})
        self.items, self.acked = items, []

    def outbox_photo(self, item_id):
        return b"PNG" + str(item_id).encode()

    def outbox_document(self, item_id):
        if getattr(self, "doc_error", None):
            raise self.doc_error
        return b"%PDF-" + str(item_id).encode()


def test_outbox_items_are_posted_with_photo_caption_and_buttons(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    review = {"id": 3, "text": "Ready to submit #J9", "has_photo": True,
              "buttons": [{"text": "✅ Submit", "callback_data": "submit:3"}, {"text": "✖ Discard", "callback_data": "discard:3"}]}
    asyncio.run(plugin._post_outbox_item(bot, 8679792351, review))
    (photo,) = bot.photos
    assert photo["caption"] == "Ready to submit #J9" and photo["photo"] == b"PNG3"
    assert [b.callback_data for b in photo["reply_markup"].inline_keyboard[0]] == ["submit:3", "discard:3"]
    assert bot.sent == []


def test_long_outbox_text_goes_as_a_message_after_the_photo(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    item = {"id": 4, "text": "x" * 2000, "has_photo": True, "buttons": [{"text": "▶ Continue", "callback_data": "resume:4"}]}
    asyncio.run(plugin._post_outbox_item(bot, 1, item))
    assert "caption" not in bot.photos[0]
    assert bot.sent[0]["text"] == "x" * 2000
    assert bot.sent[0]["reply_markup"].inline_keyboard[0][0].callback_data == "resume:4"


def test_text_only_items_are_plain_messages(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    asyncio.run(plugin._post_outbox_item(bot, 1, {"id": 5, "text": "Couldn't fill #J9", "has_photo": False, "buttons": []}))
    assert bot.photos == [] and bot.sent[0]["text"] == "Couldn't fill #J9" and bot.sent[0]["reply_markup"] is None



# ── resumes sent to apply: a reply to a card, or a bare resume with a job link ───────────────────────

CARD = types.SimpleNamespace(
    text="Acme: Staff SDET (80)\nRemote\n#J7663", caption=None, message_id=55,
    date=datetime(2026, 10, 1, tzinfo=timezone.utc),
)


class FakeFile:
    def __init__(self, data, fail=False):
        self.data, self.fail = data, fail

    async def download_as_bytearray(self):
        if self.fail:
            raise OSError("telegram down")
        return bytearray(self.data)


def resume_update(rec, caption="apply to this", name="Richard Hatcher.pdf", data=b"%PDF-1.4 resume", card=CARD,
                  user=8679792351, size="auto", fail_download=False):
    document = types.SimpleNamespace(
        file_name=name, file_size=len(data) if size == "auto" else size,
    )

    async def get_file():
        rec.calls.append(("get_file", (), {}))
        return FakeFile(data, fail_download)

    document.get_file = get_file
    message = types.SimpleNamespace(
        document=document, caption=caption, chat_id=8679792351, message_id=60,
        from_user=types.SimpleNamespace(id=user, first_name="Richard"),
        reply_to_message=card, reply_text=rec("reply_text"),
    )
    return types.SimpleNamespace(message=message)


def resume_handler(monkeypatch, client):
    _, _, rec, registered = wire(monkeypatch, client)
    (handler, group), = [(h, g) for h, g in registered["handlers"] if isinstance(h, MessageHandler)]
    return handler, group, rec


def run_resume(handler, update):
    return asyncio.run(handler.callback(update, None))


def replies(rec):
    return [c[1][0] for c in rec.calls if c[0] == "reply_text"]


OK = {"outcome": "done", "reply": "Filling the application for Acme in the apply browser with your resume."}


def test_resume_handler_listens_for_documents_before_hermes(monkeypatch):
    handler, group, _ = resume_handler(monkeypatch, FakeClient(OK))
    assert handler.filters == "document-filter"
    assert group < 0


def test_a_pdf_replied_to_a_card_applies_to_that_job(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec))
    (payload,) = client.resumes
    import base64
    assert payload == {
        "user_id": "8679792351", "chat_id": "8679792351", "file_name": "Richard Hatcher.pdf",
        "file_data": base64.b64encode(b"%PDF-1.4 resume").decode(), "seq": 7663, "message_id": 55, "message_date": 1790812800,
    }
    assert replies(rec) == [OK["reply"]]


def test_a_docx_is_accepted(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, name="resume.DOCX", data=b"PK\x03\x04docx"))
    assert client.resumes[0]["file_name"] == "resume.DOCX"


def test_a_bare_resume_with_a_link_applies_at_that_link(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, caption="Please apply here: https://jobs.lever.co/acme/123.", card=None))
    (payload,) = client.resumes
    assert payload["job_url"] == "https://jobs.lever.co/acme/123"
    assert "seq" not in payload and "message_id" not in payload and "message_date" not in payload


def test_the_card_wins_over_a_link_in_the_caption(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, caption="apply https://elsewhere.example.com/job"))
    assert client.resumes[0]["seq"] == 7663 and "job_url" not in client.resumes[0]


def test_a_reply_to_something_that_is_not_a_card_uses_the_link(monkeypatch):
    client = FakeClient(OK)
    other = types.SimpleNamespace(text="just chatting", caption=None, message_id=9, date=datetime(2026, 10, 1, tzinfo=timezone.utc))
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, caption="apply https://jobs.lever.co/acme/1", card=other))
    assert client.resumes[0]["job_url"] == "https://jobs.lever.co/acme/1"


def test_a_card_whose_reference_is_in_its_caption_works(monkeypatch):
    client = FakeClient(OK)
    photo_card = types.SimpleNamespace(text=None, caption="Acme #J7701", message_id=70, date=datetime(2026, 10, 1, tzinfo=timezone.utc))
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, card=photo_card))
    assert client.resumes[0]["seq"] == 7701


def test_no_apply_in_the_caption_leaves_the_file_to_hermes(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    for caption in (None, "", "here is my resume", "what do you think of this?"):
        assert run_resume(handler, resume_update(rec, caption=caption)) is None
    assert client.resumes == [] and replies(rec) == []


def test_strangers_are_left_to_hermes_authorization(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    assert run_resume(handler, resume_update(rec, user=1)) is None
    assert client.resumes == [] and replies(rec) == []


def test_messages_without_a_document_pass_through(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    update = resume_update(rec)
    update.message.document = None
    assert run_resume(handler, update) is None
    assert run_resume(handler, types.SimpleNamespace(message=None)) is None
    assert client.resumes == []


@pytest.mark.parametrize("name", ["resume.txt", "resume.doc", "resume.pages", "resume.exe", "resume.pdf.exe", "resume", ""])
def test_only_pdf_and_docx_are_taken(monkeypatch, name):
    import jobbot_actions.core as core
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, name=name))
    assert replies(rec) == [core.RESUME_TYPES]
    assert client.resumes == [] and not any(c[0] == "get_file" for c in rec.calls), "the file is never even downloaded"


def test_an_oversize_or_unsized_file_is_not_downloaded(monkeypatch):
    import jobbot_actions.core as core
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    for size in (core.MAX_RESUME_BYTES + 1, None, 0):
        with pytest.raises(ApplicationHandlerStop):
            run_resume(handler, resume_update(rec, size=size))
    assert replies(rec) == [core.RESUME_TOO_BIG] * 3
    assert client.resumes == [] and not any(c[0] == "get_file" for c in rec.calls)


def test_a_file_exactly_at_the_limit_is_taken(monkeypatch):
    import jobbot_actions.core as core
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, size=core.MAX_RESUME_BYTES))
    assert len(client.resumes) == 1


@pytest.mark.parametrize("card", [None, types.SimpleNamespace(text="no reference here", caption=None, message_id=3, date=None)])
def test_apply_with_no_card_and_no_link_says_what_is_needed(monkeypatch, card):
    import jobbot_actions.core as core
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, card=card))
    assert replies(rec) == [core.NEEDS_JOB]
    assert client.resumes == [] and not any(c[0] == "get_file" for c in rec.calls)


def test_a_backend_refusal_is_shown_as_is(monkeypatch):
    refusal = {"outcome": "refused", "reply": "That file isn't a real PDF."}
    handler, _, rec = resume_handler(monkeypatch, FakeClient(refusal))
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec))
    assert replies(rec) == ["That file isn't a real PDF."]


def test_a_toast_only_answer_still_reaches_the_chat(monkeypatch):
    handler, _, rec = resume_handler(monkeypatch, FakeClient({"outcome": "done", "toast": "Filling #J7663…"}))
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec))
    assert replies(rec) == ["Filling #J7663…"]


def test_an_empty_answer_still_says_something(monkeypatch):
    handler, _, rec = resume_handler(monkeypatch, FakeClient({"outcome": "done"}))
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec))
    assert replies(rec) == ["Done."]


def test_an_unreachable_backend_answers_unavailable(monkeypatch):
    import jobbot_actions.core as core
    handler, _, rec = resume_handler(monkeypatch, FakeClient(error=OSError("refused")))
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec))
    assert replies(rec) == [core.UNAVAILABLE]


def test_a_telegram_download_failure_answers_unavailable_and_sends_nothing(monkeypatch):
    import jobbot_actions.core as core
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec, fail_download=True))
    assert replies(rec) == [core.UNAVAILABLE] and client.resumes == []


def test_the_agent_never_sees_a_resume_that_was_handled(monkeypatch):
    client = FakeClient(OK)
    handler, _, rec = resume_handler(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run_resume(handler, resume_update(rec))
    assert not any(c[0] == "handle_message" for c in rec.calls)


# ── files for Richard (cargo résumés) ───────────────────────────────────────────────────────────────

def test_a_queued_document_is_sent_as_a_telegram_file_with_its_name(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    item = {"id": 8, "text": "Résumé for Acme — Staff SDET", "has_document": True, "document_name": "Richard-Hatcher-Resume.pdf", "buttons": []}
    asyncio.run(plugin._post_outbox_item(bot, 8679792351, item))
    (doc,) = bot.documents
    assert doc["chat_id"] == 8679792351
    assert doc["document"] == b"%PDF-8"
    assert doc["filename"] == "Richard-Hatcher-Resume.pdf"
    assert doc["caption"] == "Résumé for Acme — Staff SDET"
    assert bot.sent == [] and bot.photos == []


def test_a_document_caption_is_cut_to_telegrams_limit(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    asyncio.run(plugin._post_outbox_item(bot, 1, {"id": 2, "text": "x" * 3000, "has_document": True, "document_name": "a.pdf", "buttons": []}))
    assert len(bot.documents[0]["caption"]) == 1024


def test_a_document_without_a_name_still_goes_out(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    asyncio.run(plugin._post_outbox_item(bot, 1, {"id": 2, "text": "", "has_document": True, "buttons": []}))
    assert bot.documents[0]["filename"] == "document" and bot.documents[0]["caption"] is None


def test_a_document_that_cannot_be_fetched_does_not_send_a_broken_message(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    client = OutboxClient([])
    client.doc_error = OSError("gone")
    plugin._client = client
    bot = FakeBot()
    with pytest.raises(OSError):
        asyncio.run(plugin._post_outbox_item(bot, 1, {"id": 2, "text": "x", "has_document": True, "document_name": "a.pdf", "buttons": []}))
    assert bot.documents == [] and bot.sent == []


class StatefulOutbox(OutboxClient):
    """Like the real outbox: an acknowledged item is not served again."""

    def outbox(self):
        return [i for i in self.items if i["id"] not in self.acked]

    def outbox_delivered(self, item_id):
        self.acked.append(item_id)


def run_pump(plugin, bot, seconds=0.3):
    async def go():
        task = asyncio.ensure_future(plugin._pump_outbox(bot, interval=0.005))
        await asyncio.sleep(seconds)
        task.cancel()
    asyncio.run(go())


PHOTO_ITEM = {"id": 6, "text": "Job at jobs.ashbyhq.com needs you: There's a CAPTCHA.", "has_photo": True,
              "buttons": [{"text": "▶ Continue", "callback_data": "resume:6"}, {"text": "✖ Discard", "callback_data": "discard:6"}]}


def test_a_refused_photo_still_delivers_the_message_with_its_buttons(monkeypatch):
    """The production failure: a full-page screenshot of a long form is Photo_invalid_dimensions, and the CAPTCHA hand-off with its Continue button vanished."""
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot(fail_photo=TelegramRefused("Photo_invalid_dimensions"))
    asyncio.run(plugin._post_outbox_item(bot, 8679792351, PHOTO_ITEM))
    (msg,) = bot.sent
    assert msg["text"] == PHOTO_ITEM["text"] and msg["chat_id"] == 8679792351
    assert [b.callback_data for b in msg["reply_markup"].inline_keyboard[0]] == ["resume:6", "discard:6"]
    (doc,) = bot.documents
    assert doc["document"] == b"PNG6" and doc["filename"] == "screenshot.png", "the screenshot follows as a file"
    assert bot.photos == []


def test_a_refused_photo_with_a_long_text_sends_the_message_once(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot(fail_photo=TelegramRefused("Photo_invalid_dimensions"))
    item = dict(PHOTO_ITEM, text="x" * 2000)
    asyncio.run(plugin._post_outbox_item(bot, 1, item))
    assert [m["text"] for m in bot.sent] == ["x" * 2000]
    assert bot.sent[0]["reply_markup"] is not None


def test_the_message_is_delivered_even_if_the_screenshot_file_is_refused_too(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot(fail_photo=TelegramRefused("Photo_invalid_dimensions"), fail_document=TelegramRefused("file too big"))
    asyncio.run(plugin._post_outbox_item(bot, 1, PHOTO_ITEM))
    assert len(bot.sent) == 1 and bot.documents == []


def test_an_item_whose_message_cannot_be_sent_either_is_an_error_so_it_is_retried(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot(fail_photo=TelegramRefused("Photo_invalid_dimensions"), fail_message=OSError("telegram down"))
    with pytest.raises(OSError):
        asyncio.run(plugin._post_outbox_item(bot, 1, PHOTO_ITEM))


def test_a_photo_that_telegram_accepts_is_sent_exactly_as_before(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    asyncio.run(plugin._post_outbox_item(bot, 1, PHOTO_ITEM))
    assert len(bot.photos) == 1 and bot.sent == [] and bot.documents == []
    assert bot.photos[0]["caption"] == PHOTO_ITEM["text"]


def test_a_transient_failure_is_retried_and_delivered_once(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    client = StatefulOutbox([{"id": 1, "text": "hello", "has_photo": False, "buttons": []}])
    plugin._client = client
    calls = {"n": 0}

    class Flaky(FakeBot):
        async def send_message(self, **kw):
            calls["n"] += 1
            if calls["n"] <= 2:
                raise OSError("telegram hiccup")
            await super().send_message(**kw)

    bot = Flaky()
    run_pump(plugin, bot)
    assert [m["text"] for m in bot.sent] == ["hello"], "delivered exactly once, after two failures"
    assert client.acked == [1]
    assert calls["n"] == 3, "and not resent on later polls once it was acknowledged"


def test_a_failing_item_is_not_acknowledged_while_it_can_still_be_retried(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    monkeypatch.setattr(plugin, "MAX_POST_ATTEMPTS", 1000)
    client = StatefulOutbox([{"id": 1, "text": "x", "has_photo": False, "buttons": []}])
    plugin._client = client
    run_pump(plugin, FakeBot(fail_message=OSError("down")), seconds=0.1)
    assert client.acked == [], "a message that was never posted must not be marked delivered"


def test_an_item_that_can_never_be_posted_is_given_up_on_without_stalling_the_queue(monkeypatch, caplog):
    import logging
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    monkeypatch.setattr(plugin, "MAX_POST_ATTEMPTS", 3)
    client = StatefulOutbox([
        {"id": 1, "text": "never", "has_photo": True, "buttons": []},
        {"id": 2, "text": "fine", "has_photo": False, "buttons": []},
    ])
    client.photo_error = None
    original = client.outbox_photo

    def broken_photo(item_id):
        raise OSError("photo gone")

    client.outbox_photo = broken_photo
    plugin._client = client
    bot = FakeBot()
    with caplog.at_level(logging.ERROR):
        run_pump(plugin, bot)
    assert sorted(client.acked) == [1, 2]
    assert [m["text"] for m in bot.sent] == ["fine"], "the second item was delivered on the first poll, not held up"
    assert any("giving up on outbox item 1 after 3 attempts" in r.message for r in caplog.records)
    assert plugin._attempts == {}, "the attempt counter is cleared once an item is settled"


# ── the wire contract shared with jobbot-mcp's Kotlin tests ────────────────────────────────────────────

import json
from pathlib import Path

WIRE = json.loads((Path(__file__).resolve().parents[1] / "contract" / "plugin_wire.json").read_text())


@pytest.mark.parametrize("case", WIRE["tap_responses"], ids=lambda c: c["name"])
def test_the_plugin_acts_on_every_response_the_server_sends(monkeypatch, case):
    handler, _, rec, _ = wire(monkeypatch, FakeClient(case["response"]))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    expect = case["expect"]
    answers = [c for c in rec.calls if c[0] == "answer"]
    assert (answers[0][1][0] if answers and answers[0][1] and answers[0][1][0] is not None else None) == expect["toast"]
    replies_ = [c for c in rec.calls if c[0] == "reply_text"]
    assert (replies_[0][1][0] if replies_ else None) == expect["reply"]
    edits = [c for c in rec.calls if c[0] == "edit_markup"]
    if expect["keyboard"] is None:
        assert edits == [], "the keyboard is left alone when the server sends no action_row"
    else:
        markup = edits[0][2]["reply_markup"]
        assert [[b.text for b in row] for row in markup.inline_keyboard] == expect["keyboard"]
    if "reply_buttons" in expect:
        assert [b.callback_data for b in replies_[0][2]["reply_markup"].inline_keyboard[0]] == expect["reply_buttons"]


@pytest.mark.parametrize("case", WIRE["outbox_items"], ids=lambda c: c["name"])
def test_the_plugin_posts_every_kind_of_outbox_item_the_server_serves(monkeypatch, case):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    item = case["item"]
    asyncio.run(plugin._post_outbox_item(bot, 1, item))
    posted = {"message": bot.sent, "photo": bot.photos, "document": bot.documents}
    assert len(posted[case["sends"]]) == 1, {k: len(v) for k, v in posted.items()}
    assert sum(len(v) for v in posted.values()) == 1, "exactly one Telegram call per item"
    call = posted[case["sends"]][0]
    text = item.get("text", "")
    assert call.get("text", call.get("caption")) == text
    buttons = [b["callback_data"] for b in item.get("buttons", [])]
    markup = call.get("reply_markup")
    assert ([b.callback_data for b in markup.inline_keyboard[0]] if markup else []) == buttons


# ── the plugin against a real HTTP backend (the shape of jobbot-mcp), not a stand-in client ─────────

def _serve(responses):
    import threading
    from http.server import BaseHTTPRequestHandler, HTTPServer
    seen = []

    class H(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            seen.append((self.path, self.headers.get("Authorization"), self.headers.get("Content-Type"), body))
            data = json.dumps(responses.get(self.path, {"outcome": "refused", "reply": "no route"})).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *a):
            pass

    server = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}", seen


def test_a_resume_travels_to_the_backend_intact(monkeypatch):
    import base64
    import jobbot_actions as plugin
    import jobbot_actions.core as core
    server, url, seen = _serve({"/plugin/apply-resume": {"outcome": "done", "reply": "Filling the application for Acme."}})
    try:
        handler, _, rec = resume_handler(monkeypatch, FakeClient({}))
        plugin._client = core.JobbotMcp(url, "tok")
        pdf = b"%PDF-1.7\n" + bytes(range(256)) * 40 + b"\n%%EOF"
        with pytest.raises(ApplicationHandlerStop):
            run_resume(handler, resume_update(rec, data=pdf, name="Richard \u00c9 Hatcher.pdf"))
        (path, auth, ctype, body), = seen
        assert (path, auth, ctype) == ("/plugin/apply-resume", "Bearer tok", "application/json")
        assert base64.b64decode(body["file_data"]) == pdf, "every byte arrives, binary included"
        assert body["file_name"] == "Richard \u00c9 Hatcher.pdf" and body["seq"] == 7663
        assert replies(rec) == ["Filling the application for Acme."]
    finally:
        server.shutdown()


def test_a_tap_travels_to_the_backend_and_its_answer_reaches_the_user(monkeypatch):
    import jobbot_actions as plugin
    import jobbot_actions.core as core
    server, url, seen = _serve({"/plugin/tap": {"outcome": "done", "toast": "Archived.", "action_row": [{"text": "Undo", "callback_data": "undo:7663"}]}})
    try:
        handler, _, rec, _ = wire(monkeypatch, FakeClient({}))
        plugin._client = core.JobbotMcp(url, "tok")
        with pytest.raises(ApplicationHandlerStop):
            run(handler, tap(rec, data="archive:7663"))
        assert seen[0][0] == "/plugin/tap" and seen[0][3]["verb"] == "archive" and seen[0][3]["seq"] == 7663
        assert [c for c in rec.calls if c[0] == "answer"][0][1] == ("Archived.",)
    finally:
        server.shutdown()


def test_a_slow_backend_is_reported_and_does_not_hang_the_user(monkeypatch):
    import threading
    import time
    from http.server import BaseHTTPRequestHandler, HTTPServer
    import jobbot_actions as plugin
    import jobbot_actions.core as core

    class Slow(BaseHTTPRequestHandler):
        def do_POST(self):
            time.sleep(3)

        def log_message(self, *a):
            pass

    server = HTTPServer(("127.0.0.1", 0), Slow)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        handler, _, rec = resume_handler(monkeypatch, FakeClient({}))
        plugin._client = core.JobbotMcp(f"http://127.0.0.1:{server.server_address[1]}", "tok", timeout=1)
        # apply_resume allows a longer wait than a tap, so shorten it for the test.
        original = plugin._client.apply_resume
        plugin._client.apply_resume = lambda payload: plugin._client._request("POST", "/plugin/apply-resume", payload, timeout=1)
        started = time.time()
        with pytest.raises(ApplicationHandlerStop):
            run_resume(handler, resume_update(rec))
        assert time.time() - started < 2.5
        assert replies(rec) == [core.UNAVAILABLE]
    finally:
        server.shutdown()

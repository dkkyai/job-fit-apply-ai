"""Credential precedence for run-analyzer notifications."""

import importlib
import os
import unittest
from unittest.mock import patch


class NotifyCredentialConfigTest(unittest.TestCase):
    def _reload_notify(self, values):
        keys = {
            "RUN_ANALYZER_TELEGRAM_BOT_TOKEN",
            "RUN_ANALYZER_TELEGRAM_CHAT_ID",
            "TELEGRAM_BOT_TOKEN",
            "TELEGRAM_CHAT_ID",
        }
        clean = {key: "" for key in keys}
        clean.update(values)
        with patch.dict(os.environ, clean, clear=False):
            from analyzer import notify
            return importlib.reload(notify)

    def test_namespaced_analyzer_credentials_win(self):
        notify = self._reload_notify({
            "RUN_ANALYZER_TELEGRAM_BOT_TOKEN": "analyzer-token",
            "RUN_ANALYZER_TELEGRAM_CHAT_ID": "analyzer-chat",
            "TELEGRAM_BOT_TOKEN": "legacy-token",
            "TELEGRAM_CHAT_ID": "legacy-chat",
        })
        self.assertEqual("analyzer-token", notify.TELEGRAM_TOKEN)
        self.assertEqual("analyzer-chat", notify.TELEGRAM_CHAT)

    def test_blank_namespaced_credentials_fall_back_to_legacy(self):
        notify = self._reload_notify({
            "RUN_ANALYZER_TELEGRAM_BOT_TOKEN": "",
            "RUN_ANALYZER_TELEGRAM_CHAT_ID": "   ",
            "TELEGRAM_BOT_TOKEN": "legacy-token",
            "TELEGRAM_CHAT_ID": "legacy-chat",
        })
        self.assertEqual("legacy-token", notify.TELEGRAM_TOKEN)
        self.assertEqual("legacy-chat", notify.TELEGRAM_CHAT)


if __name__ == "__main__":
    unittest.main()

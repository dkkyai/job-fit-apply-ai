"""Model routing — mirrors the pipeline's LlmClient backends.

  no suffix       -> local oMLX (OpenAI /v1/chat/completions)
  ":ollama-cloud" -> Ollama Cloud (/api/chat, Bearer OLLAMA_API_KEY)
  ":ollama-local" -> local Ollama escape hatch (/api/chat)

Moved verbatim from the original analyze.py:73-137, parameterized on env/model so both the
health analysis and the Phase-3 scoring audit can reuse it.
"""

import json
import os
import re
import urllib.request

ENV = os.environ
MLX_LOCAL_BASE_URL = ENV.get("MLX_LOCAL_BASE_URL", "http://127.0.0.1:11436/v1")
MLX_API_KEY = ENV.get("MLX_API_KEY", "11436")
OLLAMA_URL = ENV.get("OLLAMA_BASE_URL", ENV.get("OLLAMA_URL", "http://localhost:11434"))


def call_model(system, user_content, model=None, timeout=600):
    model = model or ENV.get("MODEL", "Qwen3.5-9B-OptiQ-4bit")
    is_qwen3 = model.lower().startswith("qwen3")

    def think_wrap(text):
        return ("/no_think\n" + text) if is_qwen3 else text

    if model.endswith(":ollama-cloud") or model.endswith(":ollama-local"):
        # ── Ollama wire format (/api/chat) ──
        headers = {"Content-Type": "application/json"}
        if model.endswith(":ollama-cloud"):
            model = model[: -len(":ollama-cloud")]
            url = ENV.get("OLLAMA_CLOUD_BASE_URL", "https://ollama.com")
            api_key = ENV.get("OLLAMA_API_KEY", "")
            if api_key:
                headers["Authorization"] = f"Bearer {api_key}"
        else:
            model = model[: -len(":ollama-local")]
            url = OLLAMA_URL
        body = json.dumps({
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": think_wrap(user_content)},
            ],
            "stream": False,
            "format": "json",
            "keep_alive": -1,
            "options": {"temperature": 0.0, "num_ctx": 32768},
        }).encode()
        req = urllib.request.Request(f"{url}/api/chat", data=body, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
        parsed = json.loads(raw)
        content = parsed["message"]["content"] or parsed["message"].get("thinking", "")
    else:
        # ── oMLX / OpenAI wire format (/v1/chat/completions) ──
        headers = {"Content-Type": "application/json",
                   "Authorization": f"Bearer {MLX_API_KEY}"}
        body = json.dumps({
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": think_wrap(user_content)},
            ],
            "stream": False,
            "response_format": {"type": "json_object"},
            "temperature": 0.0,
        }).encode()
        req = urllib.request.Request(f"{MLX_LOCAL_BASE_URL}/chat/completions",
                                     data=body, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
        parsed = json.loads(raw)
        content = parsed["choices"][0]["message"]["content"]

    if not content:
        raise ValueError(f"model returned empty content. raw={raw[:200]!r}")
    # Strip any <think> reasoning block before the JSON.
    content = re.sub(r"<think>.*?</think>", "", content, flags=re.IGNORECASE | re.DOTALL).strip()
    return content

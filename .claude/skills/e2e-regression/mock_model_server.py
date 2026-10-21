#!/usr/bin/env python3
"""Детерминированный мок модели для E2E-регрессии Heartbeat (OpenAI-совместимый протокол).

Приложение подключается к нему как к провайдеру `openai-compatible` (`CompatibleProtocol.OpenAI`):
origin `http://127.0.0.1:8000`, эндпоинты `/v1/chat/completions` и `/v1/models`. Plain HTTP
разрешён политикой `isCompatibleOriginAllowed` только для loopback, поэтому мок поднимается
локально и не требует настоящих ключей.

Только стандартная библиотека. Ответы выбираются по правилам из `--script` (первое совпадение),
полностью детерминированы: фиксированный `created`, никаких случайных id, одинаковые ответы
на одинаковые запросы. Каждый запрос пишется в JSONL (`--log`) и доступен через
`GET /__e2e/requests` — это доказательство того, что приложение действительно отправило.

Запуск (Windows):  py -3 mock_model_server.py --port 8000 --script mock_scenarios/core.json --log ..\\..\\..\\build\\e2e\\mock.jsonl
Запуск (macOS):    python3 mock_model_server.py --port 8000 --script mock_scenarios/core.json --log /tmp/hb-e2e-mock.jsonl
Остановка:         Ctrl+C или закрытие процесса; `POST /__e2e/reset` сбрасывает журнал и счётчик.
Самопроверка:      py -3 mock_model_server.py --selftest [--script mock_scenarios/core.json]
                   поднимает сервер на случайном порту, прогоняет HTTP-контур (models, обычный и потоковый
                   ответ, tool call, журнал, сброс, 404) и завершается с кодом 0/1 — запускай перед прогоном.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

CREATED = 1_700_000_000  # фиксированная метка: ответы побайтово воспроизводимы
DEFAULT_MODEL = "e2e-small"
DEFAULT_TEXT = "Мок-модель получила запрос и отвечает детерминированным текстом."
_lock = threading.Lock()
_state: dict[str, Any] = {"rules": [], "default": None, "models": [DEFAULT_MODEL], "calls": 0, "log": []}


def last_text(messages: list[dict[str, Any]]) -> str:
    """Склеенный текст всех сообщений запроса: по нему матчатся правила сценария."""
    parts: list[str] = []
    for message in messages:
        content = message.get("content")
        if isinstance(content, str):
            parts.append(content)
        elif isinstance(content, list):
            for piece in content:
                if isinstance(piece, dict) and isinstance(piece.get("text"), str):
                    parts.append(piece["text"])
    return "\n".join(parts)


def select_rule(body: dict[str, Any], calls: int) -> dict[str, Any]:
    messages = [m for m in body.get("messages", []) if isinstance(m, dict)]
    text = last_text(messages).lower()
    has_tool_result = any(m.get("role") == "tool" for m in messages)
    for rule in _state["rules"]:
        match = rule.get("match")
        if not isinstance(match, dict):
            continue
        if "model" in match and match["model"] != body.get("model"):
            continue
        if "callIndex" in match and match["callIndex"] != calls:
            continue
        if "contains" in match and str(match["contains"]).lower() not in text:
            continue
        if "regex" in match and not re.search(str(match["regex"]), last_text(messages)):
            continue
        if match.get("hasToolResult") and not has_tool_result:
            continue
        if match.get("requiresTools") and not body.get("tools"):
            continue
        return rule
    return _state["default"] or {"kind": "text", "text": DEFAULT_TEXT}


def usage_of(rule: dict[str, Any], body: dict[str, Any], completion: str) -> dict[str, int]:
    declared = rule.get("usage")
    if isinstance(declared, dict):
        return {
            "prompt_tokens": int(declared.get("prompt_tokens", 0)),
            "completion_tokens": int(declared.get("completion_tokens", 0)),
            "total_tokens": int(declared.get("total_tokens", 0)),
        }
    prompt = len(json.dumps(body.get("messages", []), ensure_ascii=False)) // 4
    done = max(1, len(completion) // 4)
    return {"prompt_tokens": prompt, "completion_tokens": done, "total_tokens": prompt + done}


def base_chunk(model: str) -> dict[str, Any]:
    return {"id": "chatcmpl-e2e", "object": "chat.completion.chunk", "created": CREATED, "model": model}


def sse(payload: dict[str, Any]) -> bytes:
    return b"data: " + json.dumps(payload, ensure_ascii=False).encode("utf-8") + b"\n\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "heartbeat-e2e-mock/1"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - сигнатура базового класса
        pass  # журнал ведётся в JSONL; stderr остаётся чистым для диагностики запуска

    # --- вспомогательное ---------------------------------------------------------------------
    def send_json(self, payload: dict[str, Any], status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            parsed = json.loads(raw.decode("utf-8")) if raw else {}
        except (ValueError, UnicodeDecodeError):
            parsed = {"__unparsed__": raw.decode("utf-8", "replace")[:2000]}
        return parsed if isinstance(parsed, dict) else {"__unparsed__": str(parsed)[:2000]}

    def record(self, path: str, body: dict[str, Any], rule: dict[str, Any] | None) -> int:
        with _lock:
            index = len(_state["log"])
            entry = {
                "index": index,
                "path": path,
                "model": body.get("model"),
                "stream": bool(body.get("stream")),
                "tools": [t.get("function", {}).get("name") for t in body.get("tools", []) if isinstance(t, dict)],
                "auth": bool(self.headers.get("Authorization")),
                "messages": body.get("messages"),
                "rule": (rule or {}).get("label") or (rule or {}).get("kind"),
            }
            _state["log"].append(entry)
            sink = _state.get("sink")
            if sink:
                sink.write(json.dumps(entry, ensure_ascii=False) + "\n")
                sink.flush()
            return index

    # --- маршруты ------------------------------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802 - имя требует http.server
        if self.path.startswith("/v1/models"):
            self.send_json(
                {
                    "object": "list",
                    "data": [
                        {"id": model, "object": "model", "created": CREATED, "owned_by": "heartbeat-e2e-mock"}
                        for model in _state["models"]
                    ],
                }
            )
            return
        if self.path.startswith("/__e2e/requests"):
            with _lock:
                self.send_json({"calls": _state["calls"], "requests": list(_state["log"])})
            return
        if self.path.startswith("/__e2e/health"):
            self.send_json({"ok": True, "models": _state["models"], "rules": len(_state["rules"])})
            return
        self.record(self.path, self.read_body(), None)
        self.send_json({"error": {"message": f"mock has no route {self.path}", "type": "not_found"}}, 404)

    def do_POST(self) -> None:  # noqa: N802 - имя требует http.server
        body = self.read_body()
        if self.path.startswith("/__e2e/reset"):
            with _lock:
                _state["log"].clear()
                _state["calls"] = 0
            self.send_json({"ok": True})
            return
        if not self.path.startswith("/v1/chat/completions"):
            self.record(self.path, body, None)
            self.send_json({"error": {"message": f"mock has no route {self.path}", "type": "not_found"}}, 404)
            return

        with _lock:
            calls = _state["calls"]
            _state["calls"] = calls + 1
        rule = select_rule(body, calls)
        self.record(self.path, body, rule)
        self.respond(rule, body)

    # --- ответы --------------------------------------------------------------------------------
    def respond(self, rule: dict[str, Any], body: dict[str, Any]) -> None:
        kind = rule.get("kind", "text")
        model = str(body.get("model") or _state["models"][0])
        if kind == "delay":
            time.sleep(float(rule.get("seconds", 30)))
            rule = rule.get("then") or {"kind": "text", "text": DEFAULT_TEXT}
            kind = rule.get("kind", "text")
        if kind == "error":
            status = int(rule.get("status", 500))
            self.send_json(
                {
                    "error": {
                        "message": str(rule.get("message", "mock failure")),
                        "type": str(rule.get("type", "server_error")),
                        "code": status,
                    }
                },
                status,
            )
            return
        if kind == "tool_call" and body.get("tools"):
            self.respond_tool_call(rule, body, model)
            return
        self.respond_text(rule, body, model)

    def respond_text(self, rule: dict[str, Any], body: dict[str, Any], model: str) -> None:
        if rule.get("kind") == "empty":
            text, finish = "", "stop"
        else:
            text, finish = str(rule.get("text", DEFAULT_TEXT)), str(rule.get("finish_reason", "stop"))
        usage = usage_of(rule, body, text)
        if not body.get("stream"):
            self.send_json(
                {
                    "id": "chatcmpl-e2e",
                    "object": "chat.completion",
                    "created": CREATED,
                    "model": model,
                    "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": finish}
                    ],
                    "usage": usage,
                }
            )
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        first = base_chunk(model)
        first["choices"] = [{"index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": None}]
        self.write_chunk(sse(first))
        delay = float(rule.get("streamDelay", 0.05))
        for piece in pieces_of(rule, text):
            chunk = base_chunk(model)
            chunk["choices"] = [{"index": 0, "delta": {"content": piece}, "finish_reason": None}]
            self.write_chunk(sse(chunk))
            if delay:
                time.sleep(delay)
        last = base_chunk(model)
        last["choices"] = [{"index": 0, "delta": {}, "finish_reason": finish}]
        self.write_chunk(sse(last))
        final = base_chunk(model)
        final["choices"] = []
        final["usage"] = usage
        self.write_chunk(sse(final))
        self.write_chunk(b"data: [DONE]\n\n")
        self.write_chunk(b"")

    def respond_tool_call(self, rule: dict[str, Any], body: dict[str, Any], model: str) -> None:
        name = str(rule.get("tool", "read"))
        arguments = json.dumps(rule.get("arguments", {}), ensure_ascii=False)
        call = {"id": "call_e2e_1", "type": "function", "function": {"name": name, "arguments": arguments}}
        usage = usage_of(rule, body, arguments)
        if not body.get("stream"):
            self.send_json(
                {
                    "id": "chatcmpl-e2e",
                    "object": "chat.completion",
                    "created": CREATED,
                    "model": model,
                    "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": None, "tool_calls": [call]},
                         "finish_reason": "tool_calls"}
                    ],
                    "usage": usage,
                }
            )
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        head = base_chunk(model)
        head["choices"] = [
            {
                "index": 0,
                "delta": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {"index": 0, "id": call["id"], "type": "function",
                         "function": {"name": name, "arguments": ""}}
                    ],
                },
                "finish_reason": None,
            }
        ]
        self.write_chunk(sse(head))
        for piece in pieces_of(rule, arguments):
            chunk = base_chunk(model)
            chunk["choices"] = [
                {"index": 0, "delta": {"tool_calls": [{"index": 0, "function": {"arguments": piece}}]},
                 "finish_reason": None}
            ]
            self.write_chunk(sse(chunk))
            time.sleep(float(rule.get("streamDelay", 0.02)))
        last = base_chunk(model)
        last["choices"] = [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]
        self.write_chunk(sse(last))
        final = base_chunk(model)
        final["choices"] = []
        final["usage"] = usage
        self.write_chunk(sse(final))
        self.write_chunk(b"data: [DONE]\n\n")
        self.write_chunk(b"")

    def write_chunk(self, payload: bytes) -> None:
        try:
            self.wfile.write(b"%X\r\n" % len(payload) + payload + b"\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # клиент отменил ход: это ожидаемый результат сценария на отмену


def pieces_of(rule: dict[str, Any], text: str) -> list[str]:
    declared = rule.get("chunks")
    if isinstance(declared, list) and declared:
        return [str(piece) for piece in declared]
    if not text:
        return []
    words = text.split(" ")
    return [word + (" " if index < len(words) - 1 else "") for index, word in enumerate(words)]


def load_script(path: Path | None) -> None:
    if path is None:
        return
    data = json.loads(path.read_text(encoding="utf-8"))
    _state["rules"] = [r for r in data.get("responses", []) if isinstance(r, dict) and not r.get("default")]
    _state["default"] = next((r for r in data.get("responses", []) if isinstance(r, dict) and r.get("default")), None)
    models = data.get("models") or [DEFAULT_MODEL]
    _state["models"] = [str(m) for m in models]


def selftest() -> int:
    """Прогон HTTP-контура мока на случайном порту: доказывает, что сервер пригоден для E2E-прогона."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    failures: list[str] = []
    checks = 0

    def call(path: str, payload: dict[str, Any] | None = None, headers: dict[str, str] | None = None) -> tuple[int, bytes]:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(base + path, data=data, method="POST" if data else "GET")
        request.add_header("Content-Type", "application/json")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def check(name: str, condition: bool, detail: str = "") -> None:
        nonlocal checks
        checks += 1
        if not condition:
            failures.append(f"{name}: {detail}")

    status, body = call("/v1/models")
    listed = json.loads(body.decode("utf-8"))
    ids = [m["id"] for m in listed.get("data", [])]
    check("models", status == 200 and ids == _state["models"], f"status={status} ids={ids}")

    status, body = call("/v1/chat/completions", {"model": _state["models"][0],
                                                  "messages": [{"role": "user", "content": "ping"}]},
                        {"Authorization": "Bearer e2e-mock"})
    plain = json.loads(body.decode("utf-8"))
    message = plain["choices"][0]["message"]
    check("completion", status == 200 and plain["choices"][0]["finish_reason"] == "stop"
          and isinstance(message.get("content"), str) and message["content"]
          and plain["usage"]["total_tokens"] > 0, f"status={status} body={body[:200]!r}")

    status, body = call("/v1/chat/completions", {"model": _state["models"][0], "stream": True,
                                                "messages": [{"role": "user", "content": "stream"}]})
    events = body.decode("utf-8").split("data: ")
    check("stream", status == 200 and body.endswith(b"data: [DONE]\n\n") and len(events) >= 4,
          f"status={status} events={len(events)}")

    status, body = call("/v1/chat/completions", {"model": _state["models"][0], "stream": True,
                                                "messages": [{"role": "user", "content": "инструмент"}],
                                                "tools": [{"type": "function",
                                                           "function": {"name": "read_file", "parameters": {}}}]})
    text = body.decode("utf-8")
    check("tool-route", status == 200 and text.endswith("data: [DONE]\n\n"), f"status={status}")

    status, body = call("/v1/embeddings")
    check("unknown-route", status == 404, f"status={status}")

    status, body = call("/__e2e/requests")
    journal = json.loads(body.decode("utf-8"))
    logged = journal.get("requests", [])
    check("journal", journal.get("calls") == 3 and len(logged) == 4
          and logged[0].get("auth") is True and logged[-1].get("path") == "/v1/embeddings",
          f"calls={journal.get('calls')} logged={len(logged)}")

    call("/__e2e/reset", {})
    status, body = call("/__e2e/requests")
    check("reset", json.loads(body.decode("utf-8")).get("calls") == 0, f"status={status}")

    server.shutdown()
    server.server_close()
    if failures:
        print("selftest FAILED", flush=True)
        for failure in failures:
            print("  " + failure, flush=True)
        return 1
    print(f"selftest OK: {checks} checks, models={_state['models']}, rules={len(_state['rules'])}", flush=True)
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Heartbeat E2E mock model server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--script", type=Path, default=None, help="JSON со сценариями ответов")
    parser.add_argument("--log", type=Path, default=None, help="JSONL-журнал запросов приложения")
    parser.add_argument("--selftest", action="store_true", help="проверить HTTP-контур мока и выйти")
    args = parser.parse_args(argv)

    load_script(args.script)
    if args.selftest:
        return selftest()
    if args.log:
        args.log.parent.mkdir(parents=True, exist_ok=True)
        _state["sink"] = args.log.open("a", encoding="utf-8")
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"mock model on http://{args.host}:{args.port} models={_state['models']} rules={len(_state['rules'])}",
          flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        sink = _state.get("sink")
        if sink:
            sink.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

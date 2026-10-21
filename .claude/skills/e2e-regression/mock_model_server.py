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
    """Склеенный текст переданных сообщений, включая текстовые части multimodal content."""
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
    user_index = next((i for i in range(len(messages) - 1, -1, -1)
                       if messages[i].get("role") == "user"), None)
    text = last_text([messages[user_index]]) if user_index is not None else ""
    turn = messages[user_index + 1:] if user_index is not None else messages
    has_tool_result = any(m.get("role") == "tool" for m in turn)
    for rule in _state["rules"]:
        match = rule.get("match")
        if not isinstance(match, dict):
            continue
        if "model" in match and match["model"] != body.get("model"):
            continue
        if "callIndex" in match and match["callIndex"] != calls:
            continue
        if "contains" in match and str(match["contains"]).lower() not in text.lower():
            continue
        if "regex" in match and not re.search(str(match["regex"]), text):
            continue
        if "hasToolResult" in match and bool(match["hasToolResult"]) != has_tool_result:
            continue
        if "requiresTools" in match and bool(match["requiresTools"]) != bool(body.get("tools")):
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
            self.record(self.path, {}, None)
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
        try:
            self.respond(rule, body)
        except (BrokenPipeError, ConnectionResetError):
            return  # отмена хода: остановить генерацию, а не продолжать куски и задержки

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
            text = "".join(pieces_of(rule, str(rule.get("text", DEFAULT_TEXT))))
            finish = str(rule.get("finish_reason", "stop"))
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
        for piece in pieces_of({}, arguments):
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
        self.wfile.write(b"%X\r\n" % len(payload) + payload + b"\r\n")
        self.wfile.flush()


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
    """Проверяет HTTP, многоходовой выбор правил и полный tool cycle на core-сценарии."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    failures: list[str] = []
    expected_log: list[dict[str, Any]] = []
    checks = 0
    original_rules = _state["rules"]

    def call(path: str, payload: dict[str, Any] | None = None, auth: bool = False) -> tuple[int, bytes]:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(base + path, data=data, method="POST" if data is not None else "GET")
        request.add_header("Content-Type", "application/json")
        if auth:
            request.add_header("Authorization", "Bearer e2e-mock")
        if not path.startswith("/__e2e/"):
            expected_log.append({"path": path, "messages": json.loads(data or b"{}").get("messages"), "auth": auth})
        # Loopback должен оставаться локальным и при системном HTTP_PROXY.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(request, timeout=15) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read()

    def check(name: str, condition: bool) -> None:
        nonlocal checks
        checks += 1
        if not condition:
            raise AssertionError(name)

    def complete(messages: list[dict[str, Any]], stream: bool = False,
                 tools: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        payload = {"model": _state["models"][0], "messages": messages, "stream": stream}
        if tools is not None:
            payload["tools"] = tools
        status, body = call("/v1/chat/completions", payload, auth=True)
        check("completion HTTP", status == 200)
        if not stream:
            result = json.loads(body)
            choice = result["choices"][0]
            return {"message": choice["message"], "finish_reason": choice["finish_reason"], "usage": result["usage"]}
        check("SSE termination", body.endswith(b"data: [DONE]\n\n"))
        events = [json.loads(line[6:]) for line in body.decode("utf-8").splitlines()
                  if line.startswith("data: ") and line != "data: [DONE]"]
        message: dict[str, Any] = {"role": "assistant", "content": ""}
        tool_calls: dict[int, dict[str, Any]] = {}
        finish, usage = None, None
        for event in events:
            usage = event.get("usage", usage)
            for choice in event["choices"]:
                delta = choice["delta"]
                message["content"] += delta.get("content") or ""
                finish = choice.get("finish_reason") or finish
                for fragment in delta.get("tool_calls", []):
                    tool = tool_calls.setdefault(fragment["index"], {"function": {"arguments": ""}})
                    for key in ("id", "type"):
                        if key in fragment:
                            tool[key] = fragment[key]
                    function = fragment.get("function", {})
                    if "name" in function:
                        tool["function"]["name"] = function["name"]
                    tool["function"]["arguments"] += function.get("arguments", "")
        if tool_calls:
            message["tool_calls"] = [tool_calls[index] for index in sorted(tool_calls)]
            message["content"] = message["content"] or None
        return {"message": message, "finish_reason": finish, "usage": usage}

    try:
        call("/__e2e/reset", {})
        status, body = call("/v1/models")
        check("models", status == 200 and [m["id"] for m in json.loads(body)["data"]] == _state["models"])
        history = [{"role": "system", "content": "ping stream инструмент"},
                   {"role": "user", "content": "ping"}]
        ping = complete(history)
        check("plain completion", ping["finish_reason"] == "stop" and
              ping["message"]["content"] == "pong: мок-модель отвечает" and ping["usage"]["total_tokens"] > 0)
        history += [ping["message"], {"role": "user", "content": [{"type": "text", "text": "stream"}]}]
        plain, streamed = complete(history), complete(history, stream=True)
        check("latest user and chunks", plain == streamed and plain["message"]["content"] ==
              "Потоковый ответ пришёл тремя кусками.")

        tools = [{"type": "function", "function": {"name": name, "parameters": {}}}
                 for name in ("read_file", "write_file")]
        for prompt, name, arguments in (
            ("инструмент", "read_file", {"path": "README.md"}),
            ("запиши файл", "write_file", {"path": "e2e-permission.txt", "content": "e2e mock write"}),
        ):
            history += [plain["message"], {"role": "user", "content": prompt}]
            tool_response = complete(history, stream=True, tools=tools)
            calls = tool_response["message"].get("tool_calls", [])
            check("streamed tool call", tool_response["finish_reason"] == "tool_calls" and len(calls) == 1)
            tool = calls[0]
            check("tool identity and arguments", bool(tool["id"]) and tool["type"] == "function" and
                  tool["function"]["name"] == name and json.loads(tool["function"]["arguments"]) == arguments)
            nonstream = complete(history, tools=tools)
            check("plain/stream tool parity", nonstream["message"] == tool_response["message"] and
                  nonstream["finish_reason"] == "tool_calls" and nonstream["usage"] == tool_response["usage"])
            history += [tool_response["message"], {"role": "tool", "tool_call_id": tool["id"], "content": "ok"}]
            summary = complete(history, tools=tools)
            check("tool result finishes turn", summary["finish_reason"] == "stop" and
                  summary["message"]["content"] == "Инструмент отработал: результат получен и обработан.")
            history += [summary["message"], {"role": "user", "content": "stream"}]
            next_turn = complete(history, tools=tools)
            check("old tool result does not capture next turn", next_turn["message"] == plain["message"] and
                  next_turn["finish_reason"] == "stop")

        # Отдельное правило проверяет явные false и regex без зависимости от текстов core.
        _state["rules"] = [{"match": {"regex": "^guard$", "hasToolResult": False, "requiresTools": False},
                            "kind": "text", "text": "guard matched"}] + original_rules
        guard = [{"role": "user", "content": "guard"}]
        check("false predicates match", complete(guard)["message"]["content"] == "guard matched")
        check("requiresTools false excludes tools", complete(guard, tools=tools)["message"]["content"] != "guard matched")
        check("hasToolResult false excludes result", complete(guard + [{"role": "tool", "content": "ok"}])
              ["message"]["content"] != "guard matched")
        check("regex ignores old messages", complete(guard + [{"role": "user", "content": "new"}])
              ["message"]["content"] != "guard matched")
        _state["rules"] = original_rules

        status, _ = call("/v1/embeddings")
        check("unknown route", status == 404)
        status, body = call("/__e2e/requests")
        journal = json.loads(body)
        logged = [{key: entry.get(key) for key in ("path", "messages", "auth")}
                  for entry in journal["requests"]]
        check("journal including models and history", status == 200 and logged == expected_log and
              journal["calls"] == sum(entry["path"] == "/v1/chat/completions" for entry in expected_log))
        status, _ = call("/__e2e/reset", {})
        _, body = call("/__e2e/requests")
        check("reset clears counter and journal", status == 200 and json.loads(body) == {"calls": 0, "requests": []})
    except Exception as error:
        failures.append(f"{type(error).__name__}: {error}")
    finally:
        _state["rules"] = original_rules
        server.shutdown()
        server.server_close()
        worker.join()
    if failures:
        print("selftest FAILED: " + "; ".join(failures), flush=True)
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

    script = args.script
    if args.selftest and script is None:
        script = Path(__file__).with_name("mock_scenarios") / "core.json"
    load_script(script)
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

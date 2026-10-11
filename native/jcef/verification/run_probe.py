#!/usr/bin/env python3
"""Verify a prepared patched JCEF bundle; no downloads, Gradle, or user profiles."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import http.cookies
import http.server
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import threading
import urllib.parse
import uuid


def remove_owned_profile(profiles: Path, target: Path) -> None:
    """Only delete descendants of this run's fresh profile directory, never linked paths."""
    root = profiles.resolve(strict=True)
    candidate = target.resolve(strict=True)
    if candidate == root or root not in candidate.parents:
        raise ValueError("Refusing deletion outside this run's profiles")
    # Do not traverse directory junctions/symlinks even if their target happens to be inside root.
    for item in [target, *target.rglob("*")]:
        if item.is_symlink() or getattr(item, "is_junction", lambda: False)():
            raise ValueError(f"Refusing linked profile path: {item}")
        if root not in item.resolve().parents:
            raise ValueError("Profile child resolves outside this run")
    shutil.rmtree(candidate)


def handler_type(profiles: Path, token: str, events: list[dict]) -> type[http.server.BaseHTTPRequestHandler]:
    fixture = Path(__file__).with_name("fixture.html").read_bytes()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: object) -> None:
            pass  # HTTP traffic is test fixture noise; assertions are in phase reports.

        def reply(self, status: int, body: bytes, content_type: str = "application/json") -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == "/fixture":
                self.reply(200, fixture, "text/html; charset=utf-8")
            elif parsed.path == "/cookies":
                cookie = http.cookies.SimpleCookie(self.headers.get("Cookie", ""))
                self.reply(200, json.dumps({key: item.value for key, item in cookie.items()}).encode())
            elif parsed.path == "/set-cookies":
                marker = urllib.parse.parse_qs(parsed.query).get("value", [""])[0]
                if not marker or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in marker):
                    self.reply(400, b'{"error":"invalid synthetic marker"}')
                    return
                self.send_response(200)
                # HttpOnly cookies prove the same Chromium network context is used by fetch.
                self.send_header("Set-Cookie", f"hb_session={marker}; Path=/; HttpOnly; SameSite=Lax")
                self.send_header("Set-Cookie", f"hb_persistent={marker}; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")
            elif parsed.path == "/favicon.ico":
                self.reply(204, b"")
            else:
                self.reply(404, b'{"error":"unknown fixture path"}')

        def do_POST(self) -> None:
            if not hmac.compare_digest(self.headers.get("X-Probe-Token", ""), token):
                self.reply(403, b'{"error":"invalid run token"}')
                return
            if self.path not in ("/clear-profile", "/delete-profile"):
                self.reply(404, b'{"error":"unknown operation"}')
                return
            owner = profiles / "profile-a-owner"
            target = profiles / "profile-a"
            try:
                remove_owned_profile(profiles, target)
                if self.path == "/delete-profile":
                    remove_owned_profile(profiles, owner)
                if self.path == "/clear-profile" and not (owner / "owner-marker.txt").is_file():
                    raise AssertionError("Clear deleted owner metadata")
                if self.path == "/delete-profile" and owner.exists():
                    raise AssertionError("Delete left profile directory")
                event = {"operation": self.path, "passed": True}
                events.append(event)
                self.reply(200, json.dumps(event).encode())
            except Exception as failure:
                event = {"operation": self.path, "passed": False, "error": str(failure)}
                events.append(event)
                self.reply(500, json.dumps(event).encode())

    return Handler


def native_layout(directory: Path) -> dict[str, Path]:
    """The normalized heartbeat-native-stage directory produced by build_runtime.py."""
    directory = directory.resolve(strict=True)
    if sys.platform == "win32":
        result = {"library": directory / "jcef.dll", "helper": directory / "jcef_helper.exe",
                  "resources": directory, "locales": directory / "locales"}
        if not (directory / "libcef.dll").is_file():
            raise ValueError("Missing matching libcef.dll")
    elif sys.platform == "darwin":
        framework = directory / "Chromium Embedded Framework.framework"
        helper_bundle = directory / "jcef Helper.app"
        result = {"library": directory / "libjcef.dylib",
                  "helper": helper_bundle / "Contents" / "MacOS" / "jcef Helper",
                  "framework": framework, "main-bundle": helper_bundle,
                  "resources": framework / "Resources", "locales": framework / "Resources"}
    else:
        raise ValueError("Native lifecycle verification requires Windows or macOS")
    for name, path in result.items():
        if not path.exists():
            raise ValueError(f"Missing {name}: {path}")
    return result


def terminate_tree(process: subprocess.Popen) -> None:
    if sys.platform == "win32":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       check=False, capture_output=True, timeout=30)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.wait(timeout=30)


def launch(command: list[str], log: Path, timeout: float, env: dict[str, str]) -> None:
    with log.open("wb") as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, env=env,
                                   start_new_session=sys.platform != "win32")
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            terminate_tree(process)
            raise RuntimeError(f"Native phase timed out; process tree terminated; see {log}") from None
        if code != 0:
            output.flush()
            tail = "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-50:])
            raise RuntimeError(f"Native phase exited {code}; see {log}\n{tail}")


def java_platform_options(platform_name: str) -> list[str]:
    if platform_name != "darwin":
        return []
    # JCEF's windowed mac bridge links LWComponentPeer and reflects its native peer.
    return [f"--add-opens=java.desktop/{package}=ALL-UNNAMED"
            for package in ("sun.awt", "sun.lwawt", "sun.lwawt.macosx")]


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--jcef-api", type=Path, required=True)
    parser.add_argument("--native-dir", type=Path, required=True)
    parser.add_argument("--extra-classpath", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path, required=True, help="New directory; existing paths are refused")
    parser.add_argument("--port", type=int, default=0, help="Bind once; 0 chooses one free port for the entire run")
    parser.add_argument("--scales", default="native,1,1.5,2", help="Native OS scale plus forced Chromium scale factors")
    parser.add_argument("--phase-timeout", type=float, default=240)
    parser.add_argument("--compile-only", action="store_true", help="Compile patched Java API usage without native startup")
    return parser.parse_args()


def main() -> int:
    args = arguments()
    output = args.output.absolute()
    output.mkdir(parents=True, exist_ok=False)
    api = args.jcef_api.resolve(strict=True)
    classpath = [api, *(item.resolve(strict=True) for item in args.extra_classpath)]
    classes = output / "classes"
    classes.mkdir()
    sources = Path(__file__).resolve().parent
    summary: dict = {"passed": False, "phases": [], "deletions": [], "apiSha256": sha256(api),
                     "platform": platform.platform(), "architecture": platform.machine()}
    try:
        command = [args.javac, "--release", "21", "-encoding", "UTF-8", "-classpath",
                   os.pathsep.join(map(str, classpath)), "-d", str(classes),
                   str(sources / "ProbeJson.java"), str(sources / "SessionProbe.java")]
        with (output / "compile.log").open("wb") as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
        if args.compile_only:
            summary.update(passed=True, compileOnly=True, nativeExecuted=False)
            return 0
        layout = native_layout(args.native_dir)
        summary["nativeLibrarySha256"] = sha256(layout["library"])
        profiles = output / "profiles"
        (profiles / "profile-a-owner").mkdir(parents=True)
        (profiles / "profile-a-owner" / "owner-marker.txt").write_text("owned by this verification run", encoding="utf-8")
        server_token = uuid.uuid4().hex
        server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port),
                                                handler_type(profiles, server_token, summary["deletions"]))
        server.daemon_threads = True
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        origin = f"http://127.0.0.1:{server.server_port}"
        summary["origin"] = origin
        seed = uuid.uuid4().hex
        marker_a, marker_b, marker_next = seed + "-a", seed + "-b", seed + "-after-clear"
        phases = [("write", "write", marker_a), ("restart", "read", marker_a),
                  ("clear", "clear", marker_a), ("restart-after-clear", "read", marker_next),
                  ("delete", "delete", marker_next), ("restart-after-delete", "read", "EMPTY")]
        for scale in args.scales.split(","):
            scale = scale.strip()
            if scale != "native" and not 0.5 <= float(scale) <= 4:
                raise ValueError("Scale must be native or a number from 0.5 to 4")
            phases.append(("visual-" + scale, "visual", "EMPTY"))
        env = os.environ.copy()
        env["PATH"] = str(args.native_dir.resolve()) + os.pathsep + env.get("PATH", "")
        try:
            for label, mode, expected_a in phases:
                result = output / f"{label}.json"
                scale = label.removeprefix("visual-") if mode == "visual" else "native"
                java = [args.java, "-Djava.awt.headless=false", "-Dfile.encoding=UTF-8",
                        "-Djava.library.path=" + str(args.native_dir.resolve())]
                java += java_platform_options(sys.platform)
                java += ["-classpath", os.pathsep.join(map(str, [classes, *classpath])), "SessionProbe",
                         "--native-dir=" + str(args.native_dir.resolve()), "--profiles=" + str(profiles),
                         "--origin=" + origin, "--server-token=" + server_token, "--mode=" + mode,
                         "--marker-a=" + expected_a, "--marker-b=" + marker_b,
                         "--marker-next=" + marker_next, "--result=" + str(result), "--scale=" + scale]
                java += [f"--{key}={value}" for key, value in layout.items() if key != "library"]
                print(f"RUN {label}", flush=True)
                launch(java, output / f"{label}.log", args.phase_timeout, env)
                report = json.loads(result.read_text(encoding="utf-8"))
                summary["phases"].append({"label": label, **report})
                if report.get("passed") is not True:
                    raise RuntimeError(f"Phase {label} reported failure")
                print(f"PASS {label}", flush=True)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=10)
        expected_deletions = ["/clear-profile", "/delete-profile"]
        if [event["operation"] for event in summary["deletions"]] != expected_deletions:
            raise AssertionError("Not every required deletion was exercised")
        if any(not event["passed"] for event in summary["deletions"]):
            raise AssertionError("A deletion failed")
        summary.update(passed=True, nativeExecuted=True)
        return 0
    except Exception as failure:
        summary["error"] = str(failure)
        print(f"FAIL {failure}", file=sys.stderr, flush=True)
        return 1
    finally:
        (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())

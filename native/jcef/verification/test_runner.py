"""Harness safety/protocol tests, independent of JCEF and a graphical desktop."""

import http.cookiejar
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import sys
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import MagicMock, patch

from run_probe import full_suite_passed, handler_type, java_platform_options, launch, native_layout, remove_owned_profile


class SuiteReportTests(unittest.TestCase):
    def report(self):
        labels = ["close-remove", "anchor-close", "write", "restart", "clear", "restart-after-clear",
                  "delete", "restart-after-delete", "visibility-close",
                  "visual-native", "visual-1", "visual-1.5", "visual-2"]
        return {"passed": True, "nativeExecuted": True,
                "phases": [{"label": label, "passed": True} for label in labels]}

    def test_full_native_suite_requires_the_new_regression_and_existing_phases(self):
        report = self.report()
        self.assertTrue(full_suite_passed(report))
        for phase in report["phases"]:
            with self.subTest(missing=phase["label"]):
                incomplete = dict(report, phases=[item for item in report["phases"] if item is not phase])
                self.assertFalse(full_suite_passed(incomplete))
            with self.subTest(failed=phase["label"]):
                failed = dict(report, phases=[dict(item, passed=False) if item is phase else item
                                              for item in report["phases"]])
                self.assertFalse(full_suite_passed(failed))

    def test_focused_compile_and_failed_reports_cannot_claim_full_suite(self):
        for overrides in ({"focusedPhase": "close-remove"}, {"focusedPhase": "anchor-close"},
                          {"compileOnly": True}, {"nativeExecuted": False}, {"passed": False}):
            with self.subTest(overrides=overrides):
                self.assertFalse(full_suite_passed(dict(self.report(), **overrides)))

    def test_duplicate_phase_cannot_replace_a_required_visual_scale(self):
        report = self.report()
        report["phases"][-1] = report["phases"][-2]
        self.assertFalse(full_suite_passed(report))


class LauncherTests(unittest.TestCase):
    def test_linux_layout_requires_matching_cef_and_uses_bundled_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ("libjcef.so", "jcef_helper"):
                (directory / name).write_bytes(b"native fixture")
            (directory / "jcef_helper").chmod(0o755)
            (directory / "locales").mkdir()
            with patch("run_probe.sys.platform", "linux"):
                with self.assertRaisesRegex(ValueError, "libcef.so"):
                    native_layout(directory)
                (directory / "libcef.so").write_bytes(b"matching CEF")
                result = native_layout(directory)
            self.assertEqual(directory.resolve() / "jcef_helper", result["helper"])
            self.assertEqual(directory.resolve(), result["resources"])
            self.assertEqual([], java_platform_options("linux"))

    def test_mac_windowed_peer_packages_are_opened(self):
        options = java_platform_options("darwin")
        for package in ("sun.awt", "sun.lwawt", "sun.lwawt.macosx"):
            self.assertIn(f"--add-opens=java.desktop/{package}=ALL-UNNAMED", options)
        self.assertEqual(java_platform_options("win32"), [])

    def test_failed_process_surfaces_diagnostic_in_ci_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "failed.log"
            with self.assertRaisesRegex(RuntimeError, "synthetic native diagnostic"):
                launch([sys.executable, "-c", "print('synthetic native diagnostic'); exit(7)"],
                       log, 10, os.environ.copy())

    @unittest.skipUnless(hasattr(signal, "SIGQUIT"), "HotSpot thread dumps use a POSIX signal")
    def test_java_timeout_captures_threads_then_terminates_and_fails(self):
        process = MagicMock()
        process.wait.side_effect = [subprocess.TimeoutExpired("java", 240),
                                    subprocess.TimeoutExpired("java", 3)]
        with tempfile.TemporaryDirectory() as temporary:
            with patch("run_probe.sys.platform", "linux"), \
                    patch("run_probe.subprocess.Popen", return_value=process), \
                    patch("run_probe.terminate_tree") as terminate:
                with self.assertRaisesRegex(RuntimeError, "Native phase timed out"):
                    launch(["/jdk/bin/java", "SessionProbe"], Path(temporary) / "timeout.log", 240, {})
        process.send_signal.assert_called_once_with(signal.SIGQUIT)
        terminate.assert_called_once_with(process)


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.profiles = Path(self.temporary.name) / "profiles"
        self.a = self.profiles / "profile-a"
        self.a.mkdir(parents=True)
        (self.a / "cache").write_text("synthetic", encoding="utf-8")
        self.owner = self.profiles / "profile-a-owner"
        self.owner.mkdir()
        (self.owner / "owner-marker.txt").write_text("owner", encoding="utf-8")
        self.b = self.profiles / "profile-b"
        self.b.mkdir()
        (self.b / "marker").write_text("other profile", encoding="utf-8")
        self.events = []
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0),
                                                      handler_type(self.profiles, "test-token", self.events))
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.close_server)
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=10)

    def post(self, path, token="test-token"):
        request = urllib.request.Request(self.origin + path, data=b"", headers={"X-Probe-Token": token})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def test_origin_serves_session_and_persistent_httponly_cookies(self):
        cookies = http.cookiejar.CookieJar()
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        with client.open(self.origin + "/set-cookies?value=synthetic-marker", timeout=5) as response:
            self.assertEqual(response.status, 200)
        indexed = {cookie.name: cookie for cookie in cookies}
        self.assertTrue(indexed["hb_session"].discard)
        self.assertFalse(indexed["hb_persistent"].discard)
        self.assertTrue(all(cookie.has_nonstandard_attr("HttpOnly") for cookie in cookies))
        with client.open(self.origin + "/cookies", timeout=5) as response:
            self.assertEqual(json.load(response), {"hb_session": "synthetic-marker", "hb_persistent": "synthetic-marker"})

    def test_clear_preserves_owner_and_other_profile(self):
        self.assertTrue(self.post("/clear-profile")["passed"])
        self.assertFalse(self.a.exists())
        self.assertTrue((self.owner / "owner-marker.txt").exists())
        self.assertEqual((self.b / "marker").read_text(encoding="utf-8"), "other profile")

    def test_held_response_streams_prefix_then_requires_explicit_release(self):
        with urllib.request.urlopen(self.origin + "/held-response", timeout=5) as response:
            try:
                self.assertEqual(response.status, 200)
                self.assertEqual(int(response.headers["Content-Length"]), 1024 * 1024)
                self.assertEqual(response.read(len(b"native cancellation fixture")), b"native cancellation fixture")
            finally:
                self.post("/release-held")
            self.assertEqual(response.read(1), b"")
        self.assertEqual(self.events, [])

    def test_delete_removes_only_selected_profile(self):
        self.assertTrue(self.post("/delete-profile")["passed"])
        self.assertFalse(self.a.exists())
        self.assertFalse(self.owner.exists())
        self.assertTrue((self.b / "marker").exists())

    def test_unauthorized_mutation_is_rejected(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.post("/clear-profile", "wrong-token")
        self.assertEqual(error.exception.code, 403)
        self.assertTrue((self.a / "cache").exists())
        self.assertEqual(self.events, [])

    def test_deletion_cannot_target_root_or_outside_directory(self):
        for target in (self.profiles, Path(self.temporary.name)):
            with self.assertRaises(ValueError):
                remove_owned_profile(self.profiles, target)
        self.assertTrue((self.a / "cache").exists())


if __name__ == "__main__":
    unittest.main()

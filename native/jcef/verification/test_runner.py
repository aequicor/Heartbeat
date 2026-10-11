"""Harness safety/protocol tests, independent of JCEF and a graphical desktop."""

import http.cookiejar
import http.server
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from run_probe import handler_type, remove_owned_profile


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.profiles = Path(self.temporary.name) / "profiles"
        self.a = self.profiles / "profile-a"
        (self.a / "browser").mkdir(parents=True)
        (self.a / "browser" / "cache").write_text("synthetic", encoding="utf-8")
        (self.a / "owner-marker.txt").write_text("owner", encoding="utf-8")
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
        self.assertFalse((self.a / "browser").exists())
        self.assertTrue((self.a / "owner-marker.txt").exists())
        self.assertEqual((self.b / "marker").read_text(encoding="utf-8"), "other profile")

    def test_delete_removes_only_selected_profile(self):
        self.assertTrue(self.post("/delete-profile")["passed"])
        self.assertFalse(self.a.exists())
        self.assertTrue((self.b / "marker").exists())

    def test_unauthorized_mutation_is_rejected(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.post("/clear-profile", "wrong-token")
        self.assertEqual(error.exception.code, 403)
        self.assertTrue((self.a / "browser" / "cache").exists())
        self.assertEqual(self.events, [])

    def test_deletion_cannot_target_root_or_outside_directory(self):
        for target in (self.profiles, Path(self.temporary.name)):
            with self.assertRaises(ValueError):
                remove_owned_profile(self.profiles, target)
        self.assertTrue((self.a / "browser" / "cache").exists())


if __name__ == "__main__":
    unittest.main()

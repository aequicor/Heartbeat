"""Check the stack trace retracer of obfuscated trains."""

import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("retrace", ROOT / "scripts" / "retrace.py")
retrace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retrace)

WEB_MAPPING = """\
# compiler: R8-like header
io.aequicor.heartbeat.feature.browser.impl.BrowserEngine -> io.aequicor.heartbeat.feature.browser.impl.xwp0:
    java.lang.String url -> a
    1:5:void navigate(java.lang.String) -> a
    6:9:void reload() -> b
    void closeTab(int) -> c
io.aequicor.heartbeat.feature.browser.impl.Tabs -> io.aequicor.heartbeat.feature.browser.impl.xwp1:
    void open() -> a
    void close() -> a
io.aequicor.heartbeat.bundles.web.WebBundle -> io.aequicor.heartbeat.bundles.web.WebBundle:
    void start() -> start
"""
FOUNDATION_MAPPING = """\
io.aequicor.heartbeat.core.common.Cache -> io.aequicor.heartbeat.core.common.f2a0:
    void evict() -> a
"""
TRACE = """\
2026-10-27 12:00:00 Launch: train=t-7 version=1.4.2
java.lang.IllegalStateException: boom in io.aequicor.heartbeat.feature.browser.impl.xwp0
\tat io.aequicor.heartbeat.feature.browser.impl.xwp0.a(Unknown Source:3)
\tat io.aequicor.heartbeat.feature.browser.impl.xwp1.a(Unknown Source)
\tat io.aequicor.heartbeat.core.common.f2a0.a(SourceFile:12)
\tat java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: io.aequicor.heartbeat.bundles.web.WebBundle: failed
"""


def manifest(train="t-7", extra=()):
    bundles = [
        {"id": "web", "version": "t-7", "jar": "bundle-web-t-7.jar", "sha256": "a" * 64},
        {"id": "foundation", "version": "t-5", "jar": "bundle-foundation-t-5.jar", "sha256": "b" * 64},
        {"id": "contracts", "version": "t-5", "jar": "bundle-contracts-t-5.jar", "sha256": "c" * 64},
        *extra,
    ]
    return {"train": train, "platformApi": 1, "bundles": bundles}


class MappingTest(unittest.TestCase):
    def test_methods_and_classes_are_restored_in_stack_frames(self):
        mapping = retrace.Mapping()
        mapping.read(WEB_MAPPING)
        mapping.read(FOUNDATION_MAPPING)
        lines = retrace.retrace_text(TRACE, mapping).splitlines()
        self.assertIn("boom in io.aequicor.heartbeat.feature.browser.impl.BrowserEngine", lines[1])
        self.assertEqual(
            "\tat io.aequicor.heartbeat.feature.browser.impl.BrowserEngine.navigate(Unknown Source:3)", lines[2]
        )
        # two methods of one class got the same name: both candidates are shown
        self.assertEqual("\tat io.aequicor.heartbeat.feature.browser.impl.Tabs.(open|close)(Unknown Source)", lines[3])
        self.assertEqual("\tat io.aequicor.heartbeat.core.common.Cache.evict(SourceFile:12)", lines[4])
        self.assertEqual("\tat java.base/java.lang.Thread.run(Thread.java:1583)", lines[5])

    def test_text_outside_names_is_kept(self):
        mapping = retrace.Mapping()
        mapping.read(WEB_MAPPING)
        text = "Launch: train=t-7 version=1.4.2\r\nplain line 1.4.2 with.dots\r\n"
        self.assertEqual(text, retrace.retrace_text(text, mapping))

    def test_comments_and_unknown_lines_are_ignored(self):
        mapping = retrace.Mapping()
        mapping.read("# comment\n\nnot a mapping line\n    orphan member -> a\n")
        self.assertEqual({}, mapping.classes)


class TrainTest(unittest.TestCase):
    def test_train_is_read_from_the_launch_line(self):
        self.assertEqual("t-7", retrace.train_of(TRACE))
        self.assertIsNone(retrace.train_of("Launch: train=none version=dev"))
        self.assertIsNone(retrace.train_of("nothing here"))

    def test_manifest_of_another_train_is_refused(self):
        with self.assertRaises(ValueError):
            retrace.jars_of(manifest("t-8"), "t-7")
        self.assertEqual(3, len(retrace.jars_of(manifest("t-7"), "t-7")))


class RunTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.mappings = self.directory / "mappings"
        self.mappings.mkdir()
        (self.mappings / "mapping-bundle-web-t-7.txt").write_text(WEB_MAPPING, encoding="utf-8")
        (self.mappings / "mapping-bundle-foundation-t-5.txt").write_text(FOUNDATION_MAPPING, encoding="utf-8")
        # a mapping of an older version of web uses the same obfuscated names for other classes
        (self.mappings / "mapping-bundle-web-t-6.txt").write_text(
            "io.aequicor.Old -> io.aequicor.heartbeat.feature.browser.impl.xwp0:\n    void old() -> a\n", encoding="utf-8"
        )
        self.manifest = self.directory / "train-manifest.json"
        self.manifest.write_text(json.dumps(manifest()), encoding="utf-8")

    def args(self, log, manifest=True, train=None):
        return SimpleNamespace(
            log=str(log), mappings=str(self.mappings), manifest=str(self.manifest) if manifest else None, train=train
        )

    def test_manifest_picks_the_mapping_of_each_jar_of_the_train(self):
        log = self.directory / "app.log"
        log.write_text(TRACE, encoding="utf-8")
        out = io.StringIO()
        self.assertEqual(0, retrace.run(self.args(log), out))
        text = out.getvalue()
        self.assertIn("BrowserEngine.navigate(Unknown Source:3)", text)
        self.assertIn("core.common.Cache.evict", text)
        self.assertNotIn("Old", text)

    def test_without_manifest_every_mapping_is_merged(self):
        log = self.directory / "app.log"
        log.write_text(TRACE, encoding="utf-8")
        out = io.StringIO()
        retrace.run(self.args(log, manifest=False), out)
        self.assertIn("Cache.evict", out.getvalue())

    def test_zip_of_logs_is_read_member_by_member(self):
        archive = self.directory / "logs.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("heartbeat.log", TRACE)
            zipped.writestr("dev.log", "Launch: train=none version=dev\nat x.y(Z)\n")
        out = io.StringIO()
        retrace.run(self.args(archive), out)
        text = out.getvalue()
        self.assertIn("=== heartbeat.log (train t-7) ===", text)
        self.assertIn("=== dev.log: no train named, text kept as it is ===", text)
        self.assertIn("BrowserEngine.navigate", text)

    def test_missing_mapping_of_contracts_is_not_an_error(self):
        log = self.directory / "app.log"
        log.write_text(TRACE, encoding="utf-8")
        self.assertEqual(0, retrace.run(self.args(log), io.StringIO()))

    def test_main_reports_a_wrong_manifest(self):
        log = self.directory / "app.log"
        log.write_text(TRACE, encoding="utf-8")
        self.manifest.write_text(json.dumps(manifest("t-8")), encoding="utf-8")
        status = retrace.main([str(log), "--mappings", str(self.mappings), "--manifest", str(self.manifest)])
        self.assertEqual(1, status)


if __name__ == "__main__":
    unittest.main()

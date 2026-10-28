"""Check the stack trace retracer of obfuscated trains."""

import importlib.util
import io
import json
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("retrace", ROOT / "scripts" / "retrace.py")
retrace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retrace)

PKG = "io.aequicor.heartbeat.feature.browser.impl"
WEB_MAPPING = f"""\
# compiler: R8-like header
{PKG}.BrowserEngine -> {PKG}.xwp0:
# {{"fileName":"BrowserEngine.kt","id":"sourceFile"}}
    java.lang.String url -> a
    1:5:void navigate(java.lang.String) -> a
    6:9:void reload() -> b
    void closeTab(int) -> c
    10:12:void connectivitySamples-HG0u8IE(long):3 -> d
{PKG}.Tabs -> {PKG}.xwp1:
    14:26:void open() -> a
    30:40:void close() -> a
{PKG}.Tabs$Inner -> {PKG}.xwp1$xwp0:
    void run() -> a
io.aequicor.heartbeat.bundles.web.WebBundle -> io.aequicor.heartbeat.bundles.web.WebBundle:
    void start() -> start
"""
FOUNDATION_MAPPING = """\
io.aequicor.heartbeat.core.common.Cache -> io.aequicor.heartbeat.core.common.f2a0:
    void evict() -> a
"""
TRACE = f"""\
2026-10-27 12:00:00 Launch: train=t-7 version=1.4.2
java.lang.IllegalStateException: boom in {PKG}.xwp0
\tat {PKG}.xwp0.a(BrowserEngine.kt:3)
\tat {PKG}.xwp1.a(Tabs.kt:35)
\tat io.aequicor.heartbeat.core.common.f2a0.a(Cache.kt:12)
\tat java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: io.aequicor.heartbeat.bundles.web.WebBundle: failed
"""


def manifest(train="t-7"):
    bundles = [
        {"id": "web", "version": "t-7", "jar": "bundle-web-t-7.jar", "sha256": "a" * 64},
        {"id": "foundation", "version": "t-5", "jar": "bundle-foundation-t-5.jar", "sha256": "b" * 64},
        {"id": "contracts", "version": "t-5", "jar": "bundle-contracts-t-5.jar", "sha256": "c" * 64},
    ]
    return {"train": train, "platformApi": 1, "bundles": bundles}


def merged():
    mapping = retrace.Mapping()
    mapping.read(WEB_MAPPING)
    mapping.read(FOUNDATION_MAPPING)
    return mapping


class MappingTest(unittest.TestCase):
    def test_methods_and_classes_are_restored_in_stack_frames(self):
        lines = retrace.retrace_text(TRACE, merged()).splitlines()
        self.assertIn(f"boom in {PKG}.BrowserEngine", lines[1])
        self.assertEqual(f"\tat {PKG}.BrowserEngine.navigate(BrowserEngine.kt:3)", lines[2])
        self.assertEqual(f"\tat {PKG}.Tabs.close(Tabs.kt:35)", lines[3])
        self.assertEqual("\tat io.aequicor.heartbeat.core.common.Cache.evict(Cache.kt:12)", lines[4])
        self.assertEqual("\tat java.base/java.lang.Thread.run(Thread.java:1583)", lines[5])

    def test_line_number_picks_among_overloads_and_without_it_both_are_shown(self):
        mapping = merged()
        frame = f"\tat {PKG}.xwp1.a(Tabs.kt:%s)\n"
        self.assertEqual(f"\tat {PKG}.Tabs.open(Tabs.kt:20)\n", retrace.retrace_text(frame % "20", mapping))
        self.assertEqual(f"\tat {PKG}.Tabs.(open|close)(Unknown Source)\n",
                         retrace.retrace_text(f"\tat {PKG}.xwp1.a(Unknown Source)\n", mapping))

    def test_mangled_names_with_a_dash_are_read_and_restored(self):
        mapping = merged()
        self.assertEqual(
            [retrace.Candidate("connectivitySamples-HG0u8IE", 10, 12)], mapping.methods[f"{PKG}.xwp0"]["d"]
        )
        frame = f"\tat {PKG}.xwp0.connectivitySamples-HG0u8IE(BrowserEngine.kt:5)\n"
        self.assertIn(f"{PKG}.BrowserEngine.connectivitySamples-HG0u8IE(", retrace.retrace_text(frame, mapping))

    def test_nested_class_and_loader_prefix_of_a_frame(self):
        mapping = merged()
        text = f"\tat app//{PKG}.xwp1$xwp0.a(Tabs.kt:1)\n"
        self.assertEqual(f"\tat app//{PKG}.Tabs$Inner.run(Tabs.kt:1)\n", retrace.retrace_text(text, mapping))

    def test_a_class_with_a_member_or_nested_name_behind_it_is_restored(self):
        mapping = merged()
        text = f"{PKG}.xwp0.INSTANCE and {PKG}.xwp1$xwp0$Companion.x\n"
        self.assertEqual(f"{PKG}.BrowserEngine.INSTANCE and {PKG}.Tabs$Inner$Companion.x\n",
                         retrace.retrace_text(text, mapping))

    def test_text_outside_names_is_kept(self):
        mapping = retrace.Mapping()
        mapping.read(WEB_MAPPING)
        text = "Launch: train=t-7 version=1.4.2\r\nplain line 1.4.2 with.dots\r\n"
        self.assertEqual(text, retrace.retrace_text(text, mapping))

    def test_comments_and_unknown_lines_are_ignored(self):
        mapping = retrace.Mapping()
        mapping.read("# comment\n\nnot a mapping line\n    orphan member -> a\n")
        self.assertEqual({}, mapping.classes)

    def test_a_long_word_without_a_dot_does_not_take_quadratic_time(self):
        mapping = merged()
        started = time.monotonic()
        retrace.retrace_text("A" * 300_000 + "\n" + "a." * 50_000 + "\n", mapping)
        retrace.retrace_text("\tat " + "a/" * 100_000 + "x.y.z(F.kt:1)\n" + "\tat " + "q" * 200_000 + "\n", mapping)
        self.assertLess(time.monotonic() - started, 5.0)


class TrainTest(unittest.TestCase):
    def test_train_is_read_from_the_launch_line(self):
        self.assertEqual("t-7", retrace.train_of(TRACE))
        self.assertIsNone(retrace.train_of("Launch: train=none version=dev"))
        self.assertIsNone(retrace.train_of("nothing here"))

    def test_manifest_of_another_train_is_refused(self):
        with self.assertRaises(ValueError):
            retrace.jars_of(manifest("t-8"), "t-7")
        self.assertEqual(3, len(retrace.jars_of(manifest("t-7"), "t-7")))

    def test_a_log_is_split_at_every_launch_line(self):
        text = "early\nLaunch: train=t-1 version=1\nfirst\nLaunch: train=none version=dev\nlocal\nLaunch: train=t-2 version=2\nsecond\n"
        self.assertEqual(
            [
                (None, "early\n"),
                ("t-1", "Launch: train=t-1 version=1\nfirst\n"),
                (None, "Launch: train=none version=dev\nlocal\n"),
                ("t-2", "Launch: train=t-2 version=2\nsecond\n"),
            ],
            retrace.segments(text, None),
        )
        self.assertEqual("t-0", retrace.segments("early\n", "t-0")[0][0])


class RunTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.mappings = self.directory / "mappings"
        self.mappings.mkdir()
        (self.mappings / "mapping-bundle-web-t-7.txt").write_text(WEB_MAPPING, encoding="utf-8")
        (self.mappings / "mapping-bundle-foundation-t-5.txt").write_text(FOUNDATION_MAPPING, encoding="utf-8")
        # a mapping of an older version of web uses the same obfuscated names for other classes
        (self.mappings / "mapping-bundle-web-t-6.txt").write_text(
            f"io.aequicor.Old -> {PKG}.xwp0:\n    void old() -> a\n", encoding="utf-8"
        )
        self.manifest = self.directory / "train-manifest.json"
        self.manifest.write_text(json.dumps(manifest()), encoding="utf-8")

    def args(self, log, manifest=True, train=None):
        return SimpleNamespace(
            log=str(log), mappings=str(self.mappings), manifest=str(self.manifest) if manifest else None, train=train
        )

    def run_retrace(self, log, **kwargs):
        out, warn = io.StringIO(), io.StringIO()
        status = retrace.run(self.args(log, **kwargs), out, warn)
        return status, out.getvalue(), warn.getvalue()

    def write_log(self, text=TRACE):
        log = self.directory / "app.log"
        log.write_bytes(text.encode("utf-8"))
        return log

    def test_manifest_picks_the_mapping_of_each_jar_of_the_train(self):
        status, text, warn = self.run_retrace(self.write_log())
        self.assertEqual(0, status)
        self.assertIn("BrowserEngine.navigate(BrowserEngine.kt:3)", text)
        self.assertIn("core.common.Cache.evict", text)
        self.assertNotIn("Old", text)
        self.assertEqual("", warn)  # the contracts jar has no mapping and that is fine

    def test_without_manifest_every_mapping_is_merged(self):
        _, text, _ = self.run_retrace(self.write_log(), manifest=False)
        self.assertIn("Cache.evict", text)

    def test_without_manifest_a_log_without_a_launch_line_is_restored_too(self):
        log = self.write_log(f"\tat {PKG}.xwp0.a(BrowserEngine.kt:3)\n")
        _, text, warn = self.run_retrace(log, manifest=False)
        self.assertIn("BrowserEngine.navigate", text)
        self.assertEqual("", warn)

    def test_two_launches_of_other_trains_in_one_file(self):
        later = TRACE.replace("t-7", "t-9") + f"\tat {PKG}.xwp0.a(BrowserEngine.kt:3)\n"
        status, text, warn = self.run_retrace(self.write_log(TRACE + later))
        self.assertEqual(0, status)
        # the first launch is restored; the second names a train the manifest is not of: kept, said on stderr
        self.assertIn(f"at {PKG}.BrowserEngine.navigate(", text.split("train=t-9")[0])
        self.assertIn(f"at {PKG}.xwp0.a(", text.split("train=t-9")[1])
        self.assertIn("train t-9", warn)

    def test_zip_of_logs_is_read_member_by_member(self):
        archive = self.directory / "logs.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("heartbeat.log", TRACE)
            zipped.writestr("dev.log", "Launch: train=none version=dev\nat x.y(Z)\n")
            zipped.writestr("dump.bin", b"\0\1\2binary")
        status, text, warn = self.run_retrace(archive)
        self.assertEqual(0, status)
        self.assertIn("=== heartbeat.log ===", text)
        self.assertIn("=== dev.log ===", text)
        self.assertNotIn("dump.bin", text)
        self.assertIn("BrowserEngine.navigate", text)
        self.assertIn("dev.log: lines of an unknown train", warn)

    def test_lines_before_the_first_launch_use_the_given_train(self):
        log = self.write_log(f"\tat {PKG}.xwp0.a(BrowserEngine.kt:3)\n")
        _, text, warn = self.run_retrace(log, train="t-7")
        self.assertIn("BrowserEngine.navigate", text)
        self.assertEqual("", warn)

    def test_missing_mappings_are_told(self):
        (self.mappings / "mapping-bundle-web-t-7.txt").unlink()
        _, _, warn = self.run_retrace(self.write_log())
        self.assertIn("mapping-bundle-web-t-7.txt", warn)

    def test_crlf_of_a_plain_log_is_kept(self):
        _, text, _ = self.run_retrace(self.write_log("Launch: train=t-7 version=1\r\nplain\r\n"))
        self.assertIn("plain\r\n", text)

    def test_main_reports_a_damaged_zip_without_a_traceback(self):
        archive = self.directory / "damaged.zip"
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as zipped:
            zipped.writestr("heartbeat.log", "hello hello hello hello hello\n" * 20)
        data = bytearray(buffer.getvalue())
        data[data.index(b"hello") - 2] ^= 0xFF  # damages the data of the member: its CRC no longer matches
        archive.write_bytes(bytes(data))
        base = ["--mappings", str(self.mappings)]
        self.assertTrue(zipfile.is_zipfile(archive))
        self.assertEqual(1, retrace.main([str(archive), *base]))

    def test_main_reports_an_unreadable_manifest(self):
        base = ["--mappings", str(self.mappings)]
        self.assertEqual(1, retrace.main([str(self.write_log()), *base, "--manifest", str(self.directory / "nope.json")]))
        self.manifest.write_text("not json", encoding="utf-8")
        self.assertEqual(1, retrace.main([str(self.write_log()), *base, "--manifest", str(self.manifest)]))


if __name__ == "__main__":
    unittest.main()

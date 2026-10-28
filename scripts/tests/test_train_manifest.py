"""Check the train manifest builder."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("train_manifest", ROOT / "scripts" / "train_manifest.py")
train_manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train_manifest)

A, B, C = "a" * 64, "b" * 64, "c" * 64
REPO = "https://github.com/o/r/releases/download/"
BASE1, BASE2 = REPO + "train-t1/", REPO + "train-t2/"


def bundle(id_, version, sha):
    return {"id": id_, "version": version, "jar": f"bundle-{id_}-{version}.jar", "sha256": sha}


def built_bundle(id_, version, sha):
    return {**bundle(id_, version, sha), "dependencies": []}


def with_url(entry, base):
    return {**entry, "url": base + entry["jar"]}


SIG1, SIG2 = "A" * 86 + "==", "B" * 86 + "=="


def launcher(version, sha, level=3, signature=SIG1):
    entry = {**bundle("launcher", version, sha), "kind": "launcher", "launcherApi": level}
    if signature is not None:
        entry["signature"] = signature
    return entry


class TrainManifestTests(unittest.TestCase):
    def test_launcher_entry_is_published_with_its_kind_level_and_signature(self):
        built = {"platformApi": 2, "bundles": [launcher("t2", A), {**bundle("foundation", "t2", B), "kind": "base"}]}
        manifest, carried = train_manifest.build_train(built, None, "t2", BASE2)
        by_id = {b["id"]: b for b in manifest["bundles"]}
        self.assertEqual(by_id["launcher"]["kind"], "launcher")
        self.assertEqual(by_id["launcher"]["launcherApi"], 3)
        self.assertEqual(by_id["launcher"]["signature"], SIG1)
        self.assertEqual(by_id["launcher"]["url"], BASE2 + "bundle-launcher-t2.jar")
        self.assertEqual(by_id["foundation"]["kind"], "base")
        self.assertEqual(sorted(carried), ["bundle-foundation-t2.jar", "bundle-launcher-t2.jar"])

    def test_plain_bundle_keeps_the_entry_without_optional_fields(self):
        manifest, _ = train_manifest.build_train(
            {"platformApi": 2, "bundles": [built_bundle("web", "t2", A)]}, None, "t2", BASE2)
        self.assertEqual(sorted(manifest["bundles"][0]), ["id", "jar", "sha256", "url", "version"])

    def test_unchanged_launcher_is_reused_with_its_fields(self):
        t1 = {"train": "t1", "platformApi": 2, "bundles": [with_url(launcher("t1", A), BASE1)]}
        t2, carried = train_manifest.build_train(
            {"platformApi": 2, "bundles": [launcher("t2", A)]}, t1, "t2", BASE2)
        self.assertEqual(carried, [])
        self.assertEqual(t2["bundles"][0]["url"], BASE1 + "bundle-launcher-t1.jar")
        self.assertEqual(t2["bundles"][0]["signature"], SIG1)
        self.assertEqual(t2["bundles"][0]["launcherApi"], 3)

    def test_launcher_with_a_higher_level_or_a_new_signature_is_published_again(self):
        t1 = {"train": "t1", "platformApi": 2, "bundles": [with_url(launcher("t1", A), BASE1)]}
        for changed in (launcher("t2", A, level=4), launcher("t2", A, signature=None)):
            t2, carried = train_manifest.build_train({"platformApi": 2, "bundles": [changed]}, t1, "t2", BASE2)
            self.assertEqual(carried, ["bundle-launcher-t2.jar"], changed)
            self.assertEqual(t2["bundles"][0]["url"], BASE2 + "bundle-launcher-t2.jar")
        unsigned = {"train": "t1", "platformApi": 2, "bundles": [with_url(launcher("t1", A, signature=None), BASE1)]}
        t2, carried = train_manifest.build_train({"platformApi": 2, "bundles": [launcher("t2", A)]}, unsigned, "t2", BASE2)
        self.assertEqual(carried, ["bundle-launcher-t2.jar"])

    def test_signature_of_another_train_id_does_not_republish_an_unchanged_launcher(self):
        # the signature covers the version, which is the train id: every train signs the same jar differently
        t1 = {"train": "t1", "platformApi": 2, "bundles": [with_url(launcher("t1", A), BASE1)]}
        t2, carried = train_manifest.build_train(
            {"platformApi": 2, "bundles": [launcher("t2", A, signature=SIG2)]}, t1, "t2", BASE2)
        self.assertEqual(carried, [])
        entry = t2["bundles"][0]
        self.assertEqual((entry["version"], entry["signature"]), ("t1", SIG1))   # entry and signature stay together
        self.assertEqual(entry["url"], BASE1 + "bundle-launcher-t1.jar")

    def test_rejects_a_second_launcher_entry(self):
        second = {**launcher("t2", B), "id": "launcher2", "jar": "bundle-launcher2-t2.jar"}
        with self.assertRaises(ValueError):
            train_manifest.build_train({"platformApi": 2, "bundles": [launcher("t2", A), second]}, None, "t2", BASE2)

    def test_rejects_invalid_launcher_fields(self):
        base = {"platformApi": 2}
        bad = [
            {**launcher("t2", A), "kind": "plugin"},
            {**launcher("t2", A), "launcherApi": 0},
            {**launcher("t2", A), "launcherApi": True},
            {**launcher("t2", A), "signature": "short"},
            {key: value for key, value in launcher("t2", A).items() if key != "launcherApi"},
        ]
        for entry in bad:
            with self.assertRaises(ValueError, msg=str(entry)):
                train_manifest.build_train({**base, "bundles": [entry]}, None, "t2", BASE2)

    def test_first_train_carries_every_jar(self):
        built = {"platformApi": 1, "bundles": [built_bundle("web", "t2", A), built_bundle("shell", "t2", B)]}
        manifest, carried = train_manifest.build_train(built, None, "t2", BASE2)
        self.assertEqual(manifest["train"], "t2")
        self.assertEqual([b["id"] for b in manifest["bundles"]], ["shell", "web"])
        self.assertEqual(sorted(carried), ["bundle-shell-t2.jar", "bundle-web-t2.jar"])
        self.assertNotIn("dependencies", manifest["bundles"][0])
        self.assertEqual(manifest["bundles"][0]["url"], BASE2 + "bundle-shell-t2.jar")

    def test_unchanged_bundle_keeps_previous_jar(self):
        previous = {"train": "t1", "platformApi": 1, "bundles": [
            with_url(bundle("web", "t1", A), BASE1), with_url(bundle("shell", "t1", B), BASE1)]}
        built = {"platformApi": 1, "bundles": [bundle("web", "t2", A), bundle("shell", "t2", C)]}
        manifest, carried = train_manifest.build_train(built, previous, "t2", BASE2)
        by_id = {b["id"]: b for b in manifest["bundles"]}
        self.assertEqual(by_id["web"]["jar"], "bundle-web-t1.jar")
        self.assertEqual(by_id["web"]["url"], BASE1 + "bundle-web-t1.jar")
        self.assertEqual(by_id["shell"]["jar"], "bundle-shell-t2.jar")
        self.assertEqual(by_id["shell"]["url"], BASE2 + "bundle-shell-t2.jar")
        self.assertEqual(carried, ["bundle-shell-t2.jar"])

    def test_reused_url_survives_several_trains(self):
        t1 = {"train": "t1", "platformApi": 1, "bundles": [with_url(bundle("web", "t1", A), BASE1)]}
        t2, _ = train_manifest.build_train({"platformApi": 1, "bundles": [bundle("web", "t2", A)]}, t1, "t2", BASE2)
        t3, carried = train_manifest.build_train(
            {"platformApi": 1, "bundles": [bundle("web", "t3", A)]}, t2, "t3", REPO + "train-t3/")
        self.assertEqual(carried, [])
        self.assertEqual(t3["bundles"][0]["url"], BASE1 + "bundle-web-t1.jar")

    def test_previous_without_url_republishes(self):
        previous = {"train": "t1", "platformApi": 1, "bundles": [bundle("web", "t1", A)]}
        built = {"platformApi": 1, "bundles": [bundle("web", "t2", A)]}
        manifest, carried = train_manifest.build_train(built, previous, "t2", BASE2)
        self.assertEqual(carried, ["bundle-web-t2.jar"])
        self.assertEqual(manifest["bundles"][0], {**bundle("web", "t2", A), "url": BASE2 + "bundle-web-t2.jar"})

    def test_platform_api_change_republishes_everything(self):
        previous = {"train": "t1", "platformApi": 1, "bundles": [bundle("web", "t1", A)]}
        built = {"platformApi": 2, "bundles": [bundle("web", "t2", A)]}
        _, carried = train_manifest.build_train(built, previous, "t2", BASE2)
        self.assertEqual(carried, ["bundle-web-t2.jar"])

    def test_rejects_train_id_with_path(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t", A)]}
        with self.assertRaises(ValueError):
            train_manifest.build_train(built, None, "../t", BASE2)

    def test_rejects_bad_digest(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t", "xyz")]}
        with self.assertRaises(ValueError):
            train_manifest.build_train(built, None, "t", BASE2)

    def test_rejects_base_url_without_https_or_slash(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t", A)]}
        for url in ("http://example.com/x/", "https://example.com/x", "https://example.com/x/?q=/",
                    "https:////", "https:///x/", "https://exa mple.com/x/", "https://example.com/x#/"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                train_manifest.build_train(built, None, "t", url)

    def test_rejects_malformed_previous_manifest(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t2", A)]}
        broken = [
            [],
            {"platformApi": "1", "bundles": []},
            {"platformApi": 1},
            {"platformApi": 1, "bundles": [{"id": "web"}]},
            {"platformApi": 1, "bundles": [{**bundle("web", "t1", A), "jar": "../evil.jar"}]},
            {"platformApi": 1, "bundles": [{**bundle("web", "t1", A), "url": "http://x/bundle-web-t1.jar"}]},
            {"platformApi": 1, "bundles": [bundle("web", "t1", A), bundle("web", "t0", B)]},
            {"platformApi": 1, "bundles": [bundle("web", "t1", A), {**bundle("shell", "t1", B), "jar": "bundle-web-t1.jar"}]},
            *({"platformApi": 1, "bundles": [{**bundle("web", "t1", A), "url": url + "bundle-web-t1.jar"}]}
              for url in ("https:////", "https:///", "https://x/?q=/", "https://x/#/", "https://x y/", "https://x")),
        ]
        for previous in broken:
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                train_manifest.build_train(built, previous, "t2", BASE2)

    def test_main_rejects_unreadable_previous(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "built.json").write_text(json.dumps({"platformApi": 1, "bundles": [bundle("web", "t2", A)]}))
            (root / "prev.json").write_text("<html>not found</html>")
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(root), "--previous", str(root / "prev.json"),
                 "--train", "t2", "--base-url", BASE2, "--out", str(root / "out")]
            )
            self.assertEqual(code, 1)

    def test_main_copies_only_changed_jars(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            jars, out = root / "jars", root / "out"
            jars.mkdir()
            (jars / "bundle-shell-t2.jar").write_bytes(b"s")
            (jars / "bundle-web-t2.jar").write_bytes(b"w")
            (root / "built.json").write_text(
                json.dumps({"platformApi": 1, "bundles": [bundle("web", "t2", A), bundle("shell", "t2", C)]})
            )
            (root / "prev.json").write_text(
                json.dumps({"train": "t1", "platformApi": 1, "bundles": [with_url(bundle("web", "t1", A), BASE1)]})
            )
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(jars), "--previous", str(root / "prev.json"),
                 "--train", "t2", "--base-url", BASE2, "--out", str(out)]
            )
            self.assertEqual(code, 0)
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["bundle-shell-t2.jar", "train-manifest.json"])
            urls = {b["id"]: b["url"] for b in json.loads((out / "train-manifest.json").read_text())["bundles"]}
            self.assertEqual(urls, {"shell": BASE2 + "bundle-shell-t2.jar", "web": BASE1 + "bundle-web-t1.jar"})

    def _mapped_train(self, root, with_web_mapping=True):
        jars, maps, out = root / "jars", root / "maps", root / "out"
        jars.mkdir()
        maps.mkdir()
        for name in ("bundle-shell-t2.jar", "bundle-web-t2.jar", "bundle-contracts-t2.jar"):
            (jars / name).write_bytes(b"j")
        (maps / "mapping-bundle-shell-t2.txt").write_text("shell mapping")
        (maps / "mapping-bundle-web-t1.txt").write_text("old web mapping")
        if with_web_mapping:
            (maps / "mapping-bundle-web-t2.txt").write_text("web mapping")
        (root / "built.json").write_text(
            json.dumps({"platformApi": 1, "bundles": [
                bundle("web", "t2", A), bundle("shell", "t2", C), bundle("contracts", "t2", B)]})
        )
        (root / "prev.json").write_text(
            json.dumps({"train": "t1", "platformApi": 1, "bundles": [with_url(bundle("web", "t1", A), BASE1)]})
        )
        return train_manifest.main(
            ["--built", str(root / "built.json"), "--jars", str(jars), "--previous", str(root / "prev.json"),
             "--mappings", str(maps), "--train", "t2", "--base-url", BASE2, "--out", str(out)]
        ), out

    def test_mappings_are_published_with_the_jars_the_train_carries(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = self._mapped_train(Path(tmp))
            self.assertEqual(code, 0)
            # web is unchanged (its mapping lives in train t1), contracts is not processed
            self.assertEqual(
                sorted(p.name for p in out.iterdir()),
                ["bundle-contracts-t2.jar", "bundle-shell-t2.jar", "mapping-bundle-shell-t2.txt", "train-manifest.json"],
            )
            self.assertEqual((out / "mapping-bundle-shell-t2.txt").read_text(), "shell mapping")

    def test_only_the_contracts_bundle_needs_no_mapping_not_one_named_like_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "jars").mkdir()
            (root / "maps").mkdir()
            (root / "jars" / "bundle-contracts-extra-t2.jar").write_bytes(b"x")
            (root / "built.json").write_text(
                json.dumps({"platformApi": 1, "bundles": [bundle("contracts-extra", "t2", A)]})
            )
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(root / "jars"), "--mappings", str(root / "maps"),
                 "--train", "t2", "--base-url", BASE2, "--out", str(root / "out")]
            )
            self.assertEqual(code, 1)

    def test_a_launcher_entry_needs_no_mapping_whatever_its_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "jars").mkdir()
            (root / "maps").mkdir()
            entry = {**launcher("t2", A), "id": "boot", "jar": "bundle-boot-t2.jar"}
            (root / "jars" / "bundle-boot-t2.jar").write_bytes(b"l")
            (root / "built.json").write_text(json.dumps({"platformApi": 2, "bundles": [entry]}))
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(root / "jars"), "--mappings", str(root / "maps"),
                 "--train", "t2", "--base-url", BASE2, "--out", str(root / "out")]
            )
            self.assertEqual(code, 0)

    def test_the_launcher_is_not_processed_and_needs_no_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "jars").mkdir()
            (root / "maps").mkdir()
            (root / "jars" / "bundle-launcher-t2.jar").write_bytes(b"l")
            (root / "built.json").write_text(json.dumps({"platformApi": 2, "bundles": [launcher("t2", A)]}))
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(root / "jars"), "--mappings", str(root / "maps"),
                 "--train", "t2", "--base-url", BASE2, "--out", str(root / "out")]
            )
            self.assertEqual(code, 0)
            self.assertEqual(
                sorted(p.name for p in (root / "out").iterdir()), ["bundle-launcher-t2.jar", "train-manifest.json"]
            )

    def test_missing_mapping_of_a_carried_jar_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "jars").mkdir()
            (root / "maps").mkdir()
            (root / "jars" / "bundle-web-t2.jar").write_bytes(b"w")
            (root / "built.json").write_text(json.dumps({"platformApi": 1, "bundles": [bundle("web", "t2", A)]}))
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(root / "jars"), "--mappings", str(root / "maps"),
                 "--train", "t2", "--base-url", BASE2, "--out", str(root / "out")]
            )
            self.assertEqual(code, 1)
            self.assertFalse((root / "out").exists())


if __name__ == "__main__":
    unittest.main()

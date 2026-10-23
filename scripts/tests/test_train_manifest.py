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


def bundle(id_, version, sha):
    return {"id": id_, "version": version, "jar": f"bundle-{id_}-{version}.jar", "sha256": sha, "dependencies": []}


class TrainManifestTests(unittest.TestCase):
    def test_first_train_carries_every_jar(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t2", A), bundle("shell", "t2", B)]}
        manifest, carried = train_manifest.build_train(built, None, "t2")
        self.assertEqual(manifest["train"], "t2")
        self.assertEqual([b["id"] for b in manifest["bundles"]], ["shell", "web"])
        self.assertEqual(sorted(carried), ["bundle-shell-t2.jar", "bundle-web-t2.jar"])
        self.assertNotIn("dependencies", manifest["bundles"][0])

    def test_unchanged_bundle_keeps_previous_jar(self):
        previous = {"train": "t1", "platformApi": 1, "bundles": [bundle("web", "t1", A), bundle("shell", "t1", B)]}
        built = {"platformApi": 1, "bundles": [bundle("web", "t2", A), bundle("shell", "t2", C)]}
        manifest, carried = train_manifest.build_train(built, previous, "t2")
        by_id = {b["id"]: b for b in manifest["bundles"]}
        self.assertEqual(by_id["web"]["jar"], "bundle-web-t1.jar")
        self.assertEqual(by_id["shell"]["jar"], "bundle-shell-t2.jar")
        self.assertEqual(carried, ["bundle-shell-t2.jar"])

    def test_platform_api_change_republishes_everything(self):
        previous = {"train": "t1", "platformApi": 1, "bundles": [bundle("web", "t1", A)]}
        built = {"platformApi": 2, "bundles": [bundle("web", "t2", A)]}
        _, carried = train_manifest.build_train(built, previous, "t2")
        self.assertEqual(carried, ["bundle-web-t2.jar"])

    def test_rejects_train_id_with_path(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t", A)]}
        with self.assertRaises(ValueError):
            train_manifest.build_train(built, None, "../t")

    def test_rejects_bad_digest(self):
        built = {"platformApi": 1, "bundles": [bundle("web", "t", "xyz")]}
        with self.assertRaises(ValueError):
            train_manifest.build_train(built, None, "t")

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
                json.dumps({"train": "t1", "platformApi": 1, "bundles": [bundle("web", "t1", A)]})
            )
            code = train_manifest.main(
                ["--built", str(root / "built.json"), "--jars", str(jars), "--previous", str(root / "prev.json"),
                 "--train", "t2", "--out", str(out)]
            )
            self.assertEqual(code, 0)
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["bundle-shell-t2.jar", "train-manifest.json"])


if __name__ == "__main__":
    unittest.main()

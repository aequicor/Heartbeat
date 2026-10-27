"""Check the seed builder of the desktop installers."""

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("train_seed", ROOT / "scripts" / "train_seed.py")
train_seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train_seed)

URL = "https://github.com/o/r/releases/download/train-t0/"


def entry(id_, data, url=None):
    item = {"id": id_, "version": "t1", "jar": f"bundle-{id_}-t1.jar", "sha256": hashlib.sha256(data).hexdigest()}
    if url:
        item["url"] = url + item["jar"]
    return item


class TrainSeedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.train = self.tmp / "train"
        self.train.mkdir()
        self.out = self.tmp / "seed"

    def manifest(self, *bundles):
        return {"train": "t1", "platformApi": 1, "bundles": list(bundles)}

    def test_carried_jars_are_copied_and_the_others_downloaded(self):
        (self.train / "bundle-web-t1.jar").write_bytes(b"web")
        fetched = []

        def fetch(url):
            fetched.append(url)
            return b"old"

        manifest = self.manifest(entry("web", b"web", URL), entry("shell", b"old", URL))
        self.assertEqual(["bundle-shell-t1.jar"], train_seed.build_seed(manifest, self.train, self.out, fetch))
        self.assertEqual([URL + "bundle-shell-t1.jar"], fetched)
        self.assertEqual(b"web", (self.out / "bundle-web-t1.jar.seed").read_bytes())
        self.assertEqual(b"old", (self.out / "bundle-shell-t1.jar.seed").read_bytes())
        self.assertEqual(manifest, json.loads((self.out / "manifest.json").read_text(encoding="utf-8")))
        self.assertFalse(any(p.suffix == ".jar" for p in self.out.iterdir()))

    def test_a_jar_with_the_wrong_hash_fails(self):
        (self.train / "bundle-web-t1.jar").write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            train_seed.build_seed(self.manifest(entry("web", b"web", URL)), self.train, self.out)
        with self.assertRaises(ValueError):
            train_seed.build_seed(self.manifest(entry("shell", b"old", URL)), self.train, self.out, lambda url: b"x")

    def test_a_missing_jar_without_url_fails(self):
        with self.assertRaises(ValueError):
            train_seed.build_seed(self.manifest(entry("web", b"web")), self.train, self.out)

    def test_a_manifest_without_a_train_id_fails(self):
        manifest = self.manifest(entry("web", b"web", URL))
        del manifest["train"]
        (self.train / "bundle-web-t1.jar").write_bytes(b"web")
        with self.assertRaises(ValueError):
            train_seed.build_seed(manifest, self.train, self.out)

    def test_download_refuses_a_plain_http_url(self):
        with self.assertRaises(ValueError):
            train_seed.download("http://example.com/a.jar")

    def test_main_reports_failure_with_exit_code_one(self):
        (self.train / "train-manifest.json").write_text(
            json.dumps(self.manifest(entry("web", b"web"))), encoding="utf-8")
        self.assertEqual(1, train_seed.main(["--train-dir", str(self.train), "--out", str(self.out)]))


if __name__ == "__main__":
    unittest.main()

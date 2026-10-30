"""Release bootstrap validates the pnpm archive before extracting it."""
import base64
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import ci_toolchain


def archive_with(name, content=b"pnpm"):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        member = tarfile.TarInfo(name)
        member.size = len(content)
        archive.addfile(member, io.BytesIO(content))
    return stream.getvalue()


def digest(data):
    return base64.b64encode(hashlib.sha512(data).digest()).decode("ascii")


class ToolchainTest(unittest.TestCase):
    def test_rejects_checksum_mismatch_before_extracting(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "checksum"):
                ci_toolchain.extract_pnpm(archive_with("package/bin/pnpm.cjs"), Path(directory))
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_extracts_verified_entry(self):
        data = archive_with("package/bin/pnpm.cjs")
        with tempfile.TemporaryDirectory() as directory, patch.object(ci_toolchain, "PNPM_SHA512", digest(data)):
            result = ci_toolchain.extract_pnpm(data, Path(directory))
            self.assertEqual(b"pnpm", result.read_bytes())

    def test_rejects_path_traversal(self):
        data = archive_with("package/../../escape")
        with tempfile.TemporaryDirectory() as directory, patch.object(ci_toolchain, "PNPM_SHA512", digest(data)):
            with self.assertRaises((RuntimeError, tarfile.TarError)):
                ci_toolchain.extract_pnpm(data, Path(directory))

    def test_versions_match_catalog(self):
        self.assertEqual(("24.15.0", "10.33.0"), ci_toolchain.versions())


if __name__ == "__main__":
    unittest.main()

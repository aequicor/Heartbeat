"""Execute bundled Pi extensions to keep capabilities out of native command environments."""

import pathlib
import shutil
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "features/ai-engine/pi/impl/src/jvmMain/resources/pi"
HARNESS = r"""
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { stripTypeScriptTypes } from "node:module";

const [sourcePath, prefix] = process.argv.slice(1);
const env = {
  [`${prefix}_URL`]: "http://127.0.0.1:1234",
  [`${prefix}_TOKEN`]: "private-capability",
  HEARTBEAT_AGENT_TOOL_SPECS: JSON.stringify([
    { name: "configure_build", description: "Configure build", inputSchema: { type: "object" } },
  ]),
};
const calls = [];
const registered = [];
const sandbox = vm.createContext({
  process: { env },
  fetch: async (url, options) => {
    calls.push({ url, options });
    return { ok: true, json: async () => ({ success: true, text: "done" }) };
  },
});
const typebox = new vm.SyntheticModule(["Type"], function() {
  this.setExport("Type", {
    Unsafe: value => value,
    Object: value => value,
    String: () => ({}),
    Integer: () => ({}),
    Optional: value => value,
  });
}, { context: sandbox });
const source = stripTypeScriptTypes(fs.readFileSync(sourcePath, "utf8"));
const extension = new vm.SourceTextModule(source, { context: sandbox });
await extension.link(specifier => {
  assert.equal(specifier, "@sinclair/typebox");
  return typebox;
});
await extension.evaluate();
extension.namespace.default({ registerTool: tool => registered.push(tool), on: () => {} });

assert.equal(env[`${prefix}_URL`], undefined);
assert.equal(env[`${prefix}_TOKEN`], undefined);
assert.ok(registered.length > 0);
for (const tool of registered) {
  const output = await tool.execute("native-call", { query: "safe", url: "https://example.com" });
  assert.equal(output.content[0].text, "done");
}
assert.equal(calls.length, registered.length);
for (const { url, options } of calls) {
  assert.equal(url, "http://127.0.0.1:1234/execute");
  assert.equal(options.headers.Authorization, "Bearer private-capability");
}
"""


class PiBridgeEnvironmentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node = shutil.which("node")
        if cls.node is None:
            raise unittest.SkipTest("Node.js 22.13+ is needed to execute TypeScript extension fixtures")
        version = subprocess.check_output([cls.node, "--version"], text=True).strip().lstrip("v")
        parts = tuple(int(part) for part in version.split(".")[:2])
        if parts < (22, 13):
            raise unittest.SkipTest("Node.js 22.13+ is needed to execute TypeScript extension fixtures")

    def test_capabilities_are_retained_in_closures_and_removed_from_command_environments(self):
        for filename, prefix in (
            ("heartbeat-tools.ts", "HEARTBEAT_AGENT_TOOLS"),
            ("heartbeat-search.ts", "HEARTBEAT_SEARCH_BRIDGE"),
        ):
            with self.subTest(extension=filename):
                subprocess.run(
                    [self.node, "--experimental-vm-modules", "--input-type=module", "-e", HARNESS,
                     str(RESOURCES / filename), prefix],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=20,
                )


if __name__ == "__main__":
    unittest.main()

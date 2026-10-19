"""Exercise the trusted Pi push wrapper, including the final Unix child environment."""

import pathlib
import shutil
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "features/ai-engine/pi/impl/src/jvmMain/resources/pi/heartbeat-approval.ts"
HARNESS = r"""
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import vm from "node:vm";
import { spawn } from "node:child_process";
import { stripTypeScriptTypes } from "node:module";

const source = stripTypeScriptTypes(fs.readFileSync(process.argv[1], "utf8"));
const root = fs.mkdtempSync(path.join(os.tmpdir(), "pi-native-push-"));
fs.writeFileSync(path.join(root, "git"), '#!/bin/sh\nprintf "%s" "$ORIGINAL_MARKER"\n', { mode: 0o755 });
try {
  async function load(flag = "true", platform = "darwin") {
    const env = { PATH: root, HOME: root, API_KEY: "private-key", ORIGINAL_MARKER: "before",
      HEARTBEAT_PIN_NATIVE_PUSH: flag, HEARTBEAT_GATE_ALL: "true" };
    const handlers = new Map();
    const registered = [];
    const sandbox = vm.createContext({ process: { env, platform, cwd: () => root } });
    const sdk = new vm.SyntheticModule(["createBashToolDefinition"], function() {
      this.setExport("createBashToolDefinition", cwd => factory(cwd));
    }, { context: sandbox });
    function factory(cwd, options) {
      return { name: "bash", parameters: {}, async execute(id, input, signal, onUpdate, ctx) {
        const initial = { command: input.command, cwd: ctx.cwd || cwd, env: { ...env, PATH: "/pi/bin:" + env.PATH } };
        const actual = options?.spawnHook ? options.spawnHook(initial) : initial;
        if (input.command === "true") return { content: [{ type: "text", text: "ordinary-command" }] };
        const child = spawn(options?.shellPath || "/bin/bash", ["-c", actual.command], { cwd: actual.cwd, env: actual.env });
        let output = "";
        child.stdout.on("data", value => { output += value; });
        const exit = await new Promise((resolve, reject) => { child.on("error", reject); child.on("close", resolve); });
        assert.equal(exit, 0);
        return { content: [{ type: "text", text: output }] };
      } };
    }
    // Preserve option forwarding in the SDK fixture: this is where the pinned spawnHook must be used.
    await sdk.link(() => { throw new Error("Unexpected SDK import"); });
    await sdk.evaluate();
    sdk.setExport("createBashToolDefinition", (cwd, options) => factory(cwd, options));
    const pathModule = new vm.SyntheticModule(["resolve"], function() { this.setExport("resolve", path.resolve); }, { context: sandbox });
    const fsModule = new vm.SyntheticModule(["existsSync"], function() { this.setExport("existsSync", fs.existsSync); }, { context: sandbox });
    const extension = new vm.SourceTextModule(source, { context: sandbox,
      importModuleDynamically: async specifier => {
        assert.equal(specifier, "@earendil-works/pi-coding-agent");
        return sdk;
      } });
    await extension.link(specifier => {
      if (specifier === "node:path") return pathModule;
      assert.equal(specifier, "node:fs");
      return fsModule;
    });
    await extension.evaluate();
    await extension.namespace.default({ registerTool: tool => registered.push(tool), on: (name, fn) => handlers.set(name, fn) });
    const requests = [];
    let allow = true;
    let afterRequest = () => {};
    const ctx = { cwd: root, hasUI: true, ui: { input: async (title, json) => {
      requests.push(JSON.parse(json));
      afterRequest();
      return JSON.stringify(allow ? { allowed: true } : { allowed: false, reason: "fixture refusal" });
    } } };
    const call = async (id, command = "git push origin branch", extra = {}) => {
      const input = { command, ...extra };
      const result = await handlers.get("tool_call")({ toolCallId: id, toolName: "bash", input }, ctx);
      return { input, result };
    };
    return { env, handlers, registered, requests, ctx, call, setAllow: value => { allow = value; },
      onRequest: action => { afterRequest = action; } };
  }

  const f = await load();
  assert.equal(f.registered.length, 1);
  const tool = f.registered[0];
  const ordinary = await f.call("ordinary", "true");
  assert.equal(f.requests.at(-1).nativeCommandEnvironment, undefined);
  assert.equal((await tool.execute("ordinary", ordinary.input, undefined, undefined, f.ctx)).content[0].text, "ordinary-command");

  f.onRequest(() => { f.env.PATH = "/changed"; f.env.HOME = "/changed"; f.env.ORIGINAL_MARKER = "after"; });
  const approved = await f.call("approved", "git push origin branch", { nativeCommandEnvironment: { values: { HOME: "/forged" } } });
  const proof = f.requests.at(-1).nativeCommandEnvironment;
  assert.equal(proof.values.HOME, root);
  assert.equal(proof.values.PATH, root);
  assert.equal(proof.values.API_KEY, undefined);
  assert.equal(proof.values.ORIGINAL_MARKER, undefined);
  assert.ok(!JSON.stringify(f.requests).includes("private-key"));
  assert.equal((await tool.execute("approved", approved.input, undefined, undefined, f.ctx)).content[0].text, "before");
  await assert.rejects(tool.execute("approved", approved.input, undefined, undefined, f.ctx), /no live/);
  await assert.rejects(tool.execute("forged", { command: "git push", nativeCommandEnvironment: proof }, undefined, undefined, f.ctx), /no live/);

  const changed = await f.call("changed");
  await assert.rejects(tool.execute("changed", { command: "true" }, undefined, undefined, f.ctx), /changed/);
  f.setAllow(false);
  const refused = await f.call("refused");
  assert.equal(refused.result.block, true);
  await assert.rejects(tool.execute("refused", refused.input, undefined, undefined, f.ctx), /no live/);
  f.setAllow(true);
  const stale = await f.call("stale");
  f.handlers.get("agent_end")();
  await assert.rejects(tool.execute("stale", stale.input, undefined, undefined, f.ctx), /no live/);

  for (const key of ["GIT_CONFIG_COUNT", "SSH_COMMAND", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "BASH_ENV", "CURL_HOME", "SSL_CERT_FILE", "SSL_CERT_DIR", "GCM_PROVIDER"]) {
    const g = await load();
    g.env[key] = "private-override";
    await g.call("override");
    assert.equal(g.requests.at(-1).nativeCommandEnvironment.values.GIT_HEARTBEAT_UNSAFE_OVERRIDE_PRESENT, "1");
    assert.ok(!JSON.stringify(g.requests).includes("private-override"));
  }
  for (const args of [["false", "darwin"], ["true", "win32"]]) {
    const g = await load(...args);
    assert.equal(g.registered.length, 0);
    await g.call("unsupported");
    assert.equal(g.requests.at(-1).nativeCommandEnvironment, undefined);
  }
} finally { fs.rmSync(root, { recursive: true, force: true }); }
"""


class PiNativePushTest(unittest.TestCase):
    def test_private_execution_plan_pins_spawn_and_redacts_protocol_evidence(self):
        node = shutil.which("node")
        if node is None:
            self.skipTest("Node.js 22.13+ is needed to execute the TypeScript extension")
        version = subprocess.check_output([node, "--version"], text=True).strip().lstrip("v")
        if tuple(int(part) for part in version.split(".")[:2]) < (22, 13):
            self.skipTest("Node.js 22.13+ is needed to execute the TypeScript extension")
        if not pathlib.Path("/bin/bash").is_file():
            self.skipTest("The pinned native push backend requires Unix bash")
        result = subprocess.run(
            [node, "--experimental-vm-modules", "--input-type=module", "-e", HARNESS, str(SOURCE)],
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

"""Build the pinned host-controlled Kimi runtime using upstream's Node SEA pipeline.

Requires the exact Node/pnpm versions in gradle/libs.versions.toml. Example (from repository root):
python native/kimi/build_runtime.py --node <node> --pnpm-js <pnpm.cjs>
The worktree stays under build/; native/kimi/out/<platform>-<arch> contains the executable, license and hash manifest.
No user's Kimi home, config or credentials are read by this builder. No release or CI workflow is triggered.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "native/kimi"
PATCHED_PATHS = {
    "apps/kimi-code/package.json", "apps/kimi-code/src/cli/sub/acp.ts",
    "packages/acp-server/src/server.ts", "packages/acp-server/src/approval.ts",
    "packages/agent-core-v2/src/features/plan/planService.ts",
    "packages/agent-core-v2/src/features/externalHooks/externalHooksFeature.ts",
    "packages/agent-core-v2/src/workspace/workspaceMcpConfig/workspaceMcpConfigService.ts",
    "packages/agent-core-v2/src/agent/interaction/approval.ts",
    "packages/agent-core-v2/src/agent/toolApproval/toolApprovalService.ts",
}
ADDED_PATHS = {
    "apps/kimi-code/src/cli/sub/heartbeat-acp.ts", "packages/acp-server/src/heartbeat-acp.ts",
    "packages/acp-server/test/heartbeat-permissions.test.ts",
    "packages/agent-core-v2/test/features/plan/heartbeat-plan.test.ts",
}


def run(argv, cwd, env=None, capture=False):
    return subprocess.run([str(arg) for arg in argv], cwd=cwd, env=env, check=True,
                          text=True, encoding="utf-8", stdout=subprocess.PIPE if capture else None).stdout


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise RuntimeError("Pinned Kimi patch context changed")
    return text.replace(before, after, 1)


def prepare(source, lock, version):
    if not source.exists():
        source.mkdir(parents=True)
        run(["git", "init"], source)
        run(["git", "config", "core.longpaths", "true"], source)
        run(["git", "remote", "add", "origin", lock["repository"]], source)
        run(["git", "fetch", "--depth", "1", "origin", lock["commit"]], source)
        run(["git", "checkout", "--detach", lock["commit"]], source)
    if run(["git", "rev-parse", "HEAD"], source, capture=True).strip() != lock["commit"]:
        raise RuntimeError("Kimi source checkout is not the locked commit")
    changed = run(["git", "diff", "--name-only", "HEAD"], source, capture=True).splitlines()
    added = run(["git", "ls-files", "--others", "--exclude-standard"], source, capture=True).splitlines()
    if set(changed) - PATCHED_PATHS or set(added) - ADDED_PATHS:
        raise RuntimeError("Unexpected changes in pinned Kimi source; use a clean build checkout")

    def original(path):
        return run(["git", "show", f'{lock["commit"]}:{path}'], source, capture=True)

    entry = "apps/kimi-code/src/cli/sub/acp.ts"
    (source / entry).write_text(replace_once(original(entry),
        "await import('@moonshot-ai/acp-server')", "await import('./heartbeat-acp')"), encoding="utf-8")
    shutil.copyfile(NATIVE / "heartbeat-acp.ts", source / "apps/kimi-code/src/cli/sub/heartbeat-acp.ts")
    plan = "packages/agent-core-v2/src/features/plan/planService.ts"
    (source / plan).write_text(replace_once(original(plan),
        "        event.allow();", "        event.pass();"), encoding="utf-8")
    hooks = "packages/agent-core-v2/src/features/externalHooks/externalHooksFeature.ts"
    (source / hooks).write_text(replace_once(original(hooks),
        "registerFeature(ExternalHooksFeature);",
        "// Heartbeat: saved executable hooks are disabled outside the mandatory host gate."), encoding="utf-8")
    mcp = "packages/agent-core-v2/src/workspace/workspaceMcpConfig/workspaceMcpConfigService.ts"
    mcp_source = replace_once(original(mcp),
        "return { ...Object.fromEntries(this.pluginServers), ...Object.fromEntries(this.fileServers) };",
        "return {}; // Heartbeat: only explicitly host-provided session MCP servers may start.")
    (source / mcp).write_text(replace_once(mcp_source, "    void this.watchConfigFiles();",
        "    // Heartbeat: saved MCP configuration is inert and needs no filesystem watchers."), encoding="utf-8")
    approval_type = "packages/agent-core-v2/src/agent/interaction/approval.ts"
    (source / approval_type).write_text(replace_once(original(approval_type),
        "  readonly toolName: string;", "  readonly toolName: string;\n  readonly toolInput?: unknown;"), encoding="utf-8")
    approval_service = "packages/agent-core-v2/src/agent/toolApproval/toolApprovalService.ts"
    (source / approval_service).write_text(replace_once(original(approval_service),
        "      toolName: name,\n      action,\n      display,",
        "      toolName: name,\n      toolInput: context.args,\n      action,\n      display,"), encoding="utf-8")
    approval_map = "packages/acp-server/src/approval.ts"
    mapped = replace_once(original(approval_map), "import { acpToolCallId } from './events-map';",
        "import { acpToolCallId, inferToolKind } from './events-map';")
    (source / approval_map).write_text(replace_once(mapped,
        "    title: req.toolName,", "    title: req.toolName,\n    kind: inferToolKind(req.toolName),\n    rawInput: req.toolInput,"), encoding="utf-8")
    server = "packages/acp-server/src/server.ts"
    (source / server).write_text(replace_once(original(server),
        "const agentCapabilities: AgentCapabilities = {",
        'const agentCapabilities: AgentCapabilities = {\n      _meta: { heartbeat: { permissionGate: 1 } },'),
        encoding="utf-8")
    package = "apps/kimi-code/package.json"
    metadata = json.loads(original(package))
    metadata["version"] = version
    (source / package).write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(NATIVE / "heartbeat-permissions.test.ts",
                    source / "packages/acp-server/test/heartbeat-permissions.test.ts")
    shutil.copyfile(NATIVE / "heartbeat-acp.ts", source / "packages/acp-server/src/heartbeat-acp.ts")
    plan_test = "packages/agent-core-v2/test/features/plan/planGuard.test.ts"
    (source / "packages/agent-core-v2/test/features/plan/heartbeat-plan.test.ts").write_text(
        replace_once(original(plan_test), "  describe('guard', () => {",
            (NATIVE / "heartbeat-plan.test.fragment.ts").read_text(encoding="utf-8") + "  describe('guard', () => {"),
        encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", default=shutil.which("node"))
    parser.add_argument("--pnpm-js", required=True)
    parser.add_argument("--work-dir", type=Path, default=ROOT / "build/kimi-upstream")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    source = args.work_dir.resolve()
    if not source.is_relative_to(ROOT / "build"):
        raise RuntimeError("Kimi build checkout must stay inside this repository's build directory")
    lock = json.loads((NATIVE / "source-lock.json").read_text(encoding="utf-8"))
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    if not args.node:
        raise RuntimeError("The pinned Node build tool is required")
    node = Path(args.node).resolve()
    pnpm = Path(args.pnpm_js).resolve()
    if run([node, "--version"], ROOT, capture=True).strip() != "v" + versions["kimi-build-node"]:
        raise RuntimeError("Node version does not match the version catalog")
    if run([node, pnpm, "--version"], ROOT, capture=True).strip() != versions["kimi-build-pnpm"]:
        raise RuntimeError("pnpm version does not match the version catalog")
    prepare(source, lock, versions["kimi-runtime"])
    if args.prepare_only:
        return
    env = dict(os.environ, HUSKY="0", KIMI_CODE_COMMIT=lock["commit"])
    env["PATH"] = str(node.parent) + os.pathsep + env.get("PATH", "")
    run([node, pnpm, "install", "--frozen-lockfile"], source, env)
    run([node, pnpm, "exec", "vitest", "run", "--project", "acp-server",
         "test/heartbeat-permissions.test.ts"], source, env)
    run([node, pnpm, "exec", "vitest", "run", "--project", "agent-core-v2",
         "test/features/plan/heartbeat-plan.test.ts", "-t", "Heartbeat mandatory"], source, env)
    run([node, pnpm, "-C", "apps/kimi-code", "run", "build:native:sea"], source, env)
    os_name = {"Windows": "win32", "Darwin": "darwin", "Linux": "linux"}[platform.system()]
    arch = {"AMD64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}[platform.machine()]
    target = f"{os_name}-{arch}"
    executable = "kimi.exe" if os_name == "win32" else "kimi"
    built = source / f"apps/kimi-code/dist-native/bin/{target}/{executable}"
    if run([built, "--version"], source, env, capture=True).strip() != versions["kimi-runtime"]:
        raise RuntimeError("Built Kimi runtime reports an unexpected version")
    output = NATIVE / "out" / target
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built, output / executable)
    shutil.copy2(source / "LICENSE", output / "LICENSE")
    digest = hashlib.sha256(built.read_bytes()).hexdigest()
    (output / "runtime.properties").write_text(
        f'version={versions["kimi-runtime"]}\nsourceCommit={lock["commit"]}\npermissionGate={lock["permissionGate"]}\nsha256={digest}\n',
        encoding="utf-8")
    print(f"Verified Heartbeat Kimi runtime: {output}")


if __name__ == "__main__":
    main()

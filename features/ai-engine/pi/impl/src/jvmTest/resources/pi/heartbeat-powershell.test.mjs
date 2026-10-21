// Node 22.18+; real process tests run only on Windows and own every process and temporary path they create.
import assert from "node:assert/strict";
import { test } from "node:test";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve, win32 } from "node:path";
import { registerHooks } from "node:module";
import { readControlledPowerShell, controlledPowerShellOperations, installControlledPowerShell }
    from "../../../jvmMain/resources/pi/heartbeat-powershell.ts";

const windows = process.platform === "win32";
const root = process.env.SystemRoot;
const argumentPrefix = ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command"];
const initialization = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)\n$OutputEncoding = [Console]::OutputEncoding\ntry {\nMicrosoft.PowerShell.Core\\Import-Module -Name ([System.IO.Path]::Combine($PSHOME, 'Modules', 'Microsoft.PowerShell.Management', 'Microsoft.PowerShell.Management.psd1')) -ErrorAction Stop\nMicrosoft.PowerShell.Core\\Import-Module -Name ([System.IO.Path]::Combine($PSHOME, 'Modules', 'Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Utility.psd1')) -ErrorAction Stop\n} catch { [System.Environment]::Exit(2) }\n";
const fixtureApi = `
export const VERSION = "0.87.1";
export function createPowerShellToolDefinition(cwd, { operations }) {
    return { name: "powershell", parameters: {},
        async execute(id, input, signal, onUpdate, ctx) {
            const chunks = [];
            const result = await operations.exec(input.command, ctx.cwd, {
                signal, timeout: input.timeout, env: { MODEL_CONTROLLED_ENV: "forged" },
                onData(data) { chunks.push(data); onUpdate?.(data.toString("utf8")); },
            });
            return { ...result, output: Buffer.concat(chunks).toString("utf8") };
        },
    };
}`;
registerHooks({ resolve(specifier, context, nextResolve) {
    if (specifier === "@earendil-works/pi-coding-agent") {
        return { url: `data:text/javascript,${encodeURIComponent(fixtureApi)}`, shortCircuit: true };
    }
    return nextResolve(specifier, context);
} });

function configuration(directory) {
    const executable = win32.join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    return { revision: "heartbeat-powershell-v2", executable, argumentPrefix, initialization, directory,
        environment: { SystemRoot: root, TEMP: tmpdir(), TMP: tmpdir(),
            PSModulePath: win32.join(win32.dirname(executable), "Modules"), PSModuleAnalysisCachePath: "NUL" },
        taskkill: win32.join(root, "System32", "taskkill.exe") };
}
function encode(value) {
    const configuration = JSON.stringify(value);
    return JSON.stringify({ configuration, binding: createHash("sha256").update(configuration).digest("hex") });
}
async function workspace(body) {
    const path = await mkdtemp(join(tmpdir(), "heartbeat-powershell-test-"));
    try { await body(path); } finally {
        assert.ok(resolve(path).startsWith(resolve(tmpdir()) + "\\heartbeat-powershell-test-"));
        await rm(path, { recursive: true });
    }
}
let generation = 0;
async function gated(directory, allowed = true) {
    process.env.HEARTBEAT_CONTROLLED_POWERSHELL = encode(configuration(directory));
    process.env.HEARTBEAT_AGENT_TOOL_SPECS = "[]";
    const handlers = new Map();
    const tools = new Map();
    const requests = [];
    const module = await import(new URL(`../../../jvmMain/resources/pi/heartbeat-approval.ts?controlled=${generation++}`, import.meta.url));
    await module.default({ on: (name, handler) => handlers.set(name, handler), registerTool: tool => tools.set(tool.name, tool) });
    const ctx = { cwd: directory, hasUI: true, ui: { async input(title, message) {
        requests.push(JSON.parse(message));
        return JSON.stringify(allowed ? { allowed: true } : { allowed: false, reason: "Denied" });
    } } };
    return { requests, ctx, tool: tools.get("powershell"),
        call: event => handlers.get("tool_call")(event, ctx), end: () => handlers.get("agent_end")() };
}
const event = (command, id = `id-${generation++}`) => ({ toolName: "powershell", toolCallId: id, input: { command } });

test("missing malformed tampered and non-system launcher manifests never install evidence", { skip: !windows }, () => {
    const value = configuration(tmpdir());
    assert.equal(readControlledPowerShell(undefined), undefined);
    assert.equal(readControlledPowerShell("{}"), undefined);
    assert.equal(readControlledPowerShell("garbage"), undefined);
    assert.equal(readControlledPowerShell(encode(value).replace("heartbeat-powershell-v2", "different")), undefined);
    for (const bad of [
        { ...value, revision: "other" }, { ...value, executable: "powershell.exe" },
        { ...value, taskkill: "taskkill.exe" }, { ...value, argumentPrefix: [...argumentPrefix, "extra"] },
        { ...value, initialization: "" }, { ...value, initialization: initialization + "Write-Output unexpected\n" },
        { ...value, environment: { ...value.environment, PSModulePath: "user-modules" } },
    ]) assert.equal(readControlledPowerShell(encode(bad)), undefined);
    const parsed = readControlledPowerShell(encode(value));
    assert.ok(parsed);
    assert.throws(() => { parsed.environment.PATH = "changed"; }, TypeError);
    assert.throws(() => { parsed.argumentPrefix.push("extra"); }, TypeError);
});

test("uninspected Pi versions cannot register an owned PowerShell tool", { skip: !windows }, async () => {
    process.env.HEARTBEAT_CONTROLLED_POWERSHELL = encode(configuration(tmpdir()));
    let registered = false;
    const manifest = await installControlledPowerShell({ registerTool() { registered = true; } }, () => true, {
        VERSION: "0.88.0", createPowerShellToolDefinition() { throw new Error("must not construct"); },
    });
    assert.equal(manifest, undefined);
    assert.equal(registered, false);
    assert.equal(process.env.HEARTBEAT_CONTROLLED_POWERSHELL, undefined);
});

test("original three directory pipelines execute through approval and the exact Windows launcher", { skip: !windows }, async () => {
    await workspace(async directory => {
        await mkdir(join(directory, "features", "ai-engine", "facade"), { recursive: true });
        await mkdir(join(directory, "core", "common"), { recursive: true });
        const api = await gated(directory);
        assert.ok(api.tool);
        for (const [command, expected] of [
            ["Get-ChildItem -Path features/ai-engine -Directory -Recurse -Depth 2 | Select-Object -ExpandProperty FullName", "facade"],
            ["Get-ChildItem -Path core -Directory | Select-Object -ExpandProperty FullName", "common"],
            ["Get-ChildItem -Directory | Select-Object -ExpandProperty Name", "features"],
        ]) {
            const call = event(command);
            assert.equal(await api.call(call), undefined);
            const result = await api.tool.execute(call.toolCallId, call.input, undefined, undefined, api.ctx);
            assert.equal(result.exitCode, 0);
            assert.ok(result.output.includes(expected), result.output);
            assert.equal(api.requests.at(-1).target, command);
            assert.match(api.requests.at(-1).executorBinding, /^[a-f0-9]{64}$/);
            await assert.rejects(api.tool.execute(call.toolCallId, call.input, undefined, undefined, api.ctx), /authorization/);
        }
    });
});

test("real Unicode directory names survive the declared initialization and model prefixes cannot replace it", { skip: !windows }, async () => {
    await workspace(async directory => {
        const name = "Каталог_日本_é_🙂";
        await mkdir(join(directory, name));
        const api = await gated(directory);
        const call = event("Get-ChildItem -Directory | Select-Object -ExpandProperty Name");
        call.input.initialization = "Write-Output forged-prefix\n";
        assert.equal(await api.call(call), undefined);
        const result = await api.tool.execute(call.toolCallId, call.input, undefined, undefined, api.ctx);
        assert.equal(result.exitCode, 0);
        assert.equal(result.output.trim(), name);
        assert.equal(api.requests[0].target, call.input.command);
        assert.ok(!api.requests[0].target.includes(initialization));
    });
});

test("forged copied denied changed and expired calls have no consumable launch authorization", { skip: !windows }, async () => {
    await workspace(async directory => {
        const api = await gated(directory);
        const forged = event("Write-Output forged");
        Object.freeze(forged.input);
        await assert.rejects(api.tool.execute(forged.toolCallId, forged.input, undefined, undefined, api.ctx), /authorization/);
        const copied = event("Write-Output copied");
        await api.call(copied);
        await assert.rejects(api.tool.execute(copied.toolCallId, Object.freeze({ ...copied.input }), undefined, undefined, api.ctx), /authorization/);
        await assert.rejects(api.tool.execute(copied.toolCallId, copied.input, undefined, undefined, api.ctx), /authorization/);
        const expired = event("Write-Output expired");
        await api.call(expired); api.end();
        await assert.rejects(api.tool.execute(expired.toolCallId, expired.input, undefined, undefined, api.ctx), /authorization/);
        const changed = event("Write-Output changed");
        await api.call(changed);
        await assert.rejects(api.tool.execute(changed.toolCallId, changed.input, undefined, undefined, { cwd: tmpdir() }), /authorization/);
        await assert.rejects(api.tool.execute(changed.toolCallId, changed.input, undefined, undefined, api.ctx), /authorization/);
        const denied = await gated(directory, false);
        const call = event("Write-Output denied");
        assert.equal((await denied.call(call)).block, true);
        Object.freeze(call.input);
        await assert.rejects(denied.tool.execute(call.toolCallId, call.input, undefined, undefined, denied.ctx), /authorization/);
    });
});

test("actual process uses immutable environment closed stdin and preserves output plus exit code", { skip: !windows }, async () => {
    await workspace(async directory => {
        const manifest = readControlledPowerShell(encode(configuration(directory)));
        const operations = controlledPowerShellOperations(manifest);
        process.env.MODEL_CONTROLLED_ENV = "ambient";
        const chunks = [];
        try {
            const result = await operations.exec("[Console]::In.ReadToEnd().Length; $env:MODEL_CONTROLLED_ENV; [Console]::Out.WriteLine('stdout'); [Console]::Error.WriteLine('stderr'); exit 7", directory, {
                env: { MODEL_CONTROLLED_ENV: "forged" }, onData: data => chunks.push(data),
            });
            const output = Buffer.concat(chunks).toString("utf8");
            assert.equal(result.exitCode, 7);
            assert.match(output, /0/); assert.match(output, /stdout/); assert.match(output, /stderr/);
            assert.doesNotMatch(output, /forged|ambient/);
        } finally { delete process.env.MODEL_CONTROLLED_ENV; }
    });
});

test("actual process timeout and cancellation terminate the shell and its child process", { skip: !windows }, async () => {
    await workspace(async directory => {
        const operations = controlledPowerShellOperations(readControlledPowerShell(encode(configuration(directory))));
        await assert.rejects(operations.exec("Start-Sleep -Seconds 60", directory, { timeout: 0.05, onData() {} }), /timeout/);
        const controller = new AbortController();
        let childPid;
        const command = `$p = Start-Process -FilePath '${process.execPath.replaceAll("'", "''")}' -ArgumentList '-e', 'setInterval(()=>{},1000)' -PassThru; Write-Output $p.Id; Start-Sleep -Seconds 60`;
        await assert.rejects(operations.exec(command, directory, { signal: controller.signal, timeout: 10,
            onData(data) {
                const pid = Number(data.toString("utf8").trim());
                if (Number.isInteger(pid) && pid > 0) { childPid = pid; controller.abort(); }
            },
        }), /aborted/);
        assert.ok(childPid);
        assert.throws(() => process.kill(childPid, 0), { code: "ESRCH" });
    });
});

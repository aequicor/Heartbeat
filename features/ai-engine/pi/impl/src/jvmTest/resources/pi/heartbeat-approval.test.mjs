// Run with Node 22.18+ (native TypeScript stripping): node --test <this file>.
import assert from "node:assert/strict";
import { test } from "node:test";

let generation = 0;
async function extension({ gate = "true", specs = "[]", allowed = true, note } = {}) {
    process.env.HEARTBEAT_GATE_ALL = gate;
    process.env.HEARTBEAT_AGENT_TOOL_SPECS = specs;
    const handlers = new Map();
    const requests = [];
    const module = await import(new URL(`../../../jvmMain/resources/pi/heartbeat-approval.ts?test=${generation++}`, import.meta.url));
    module.default({ on: (event, handler) => handlers.set(event, handler) });
    const ctx = { hasUI: true, cwd: "/workspace", ui: {
        confirm: async (title, message) => { requests.push({ title, ...JSON.parse(message) }); return allowed; },
        input: async (title, message, options) => { requests.push({ title, ...JSON.parse(message), options }); return note; },
    } };
    return { requests, ctx, call: event => handlers.get("tool_call")(event, ctx),
        result: event => handlers.get("tool_result")(event, ctx), end: () => handlers.get("agent_end")() };
}
const call = (toolName, input = {}) => ({ toolName, toolCallId: "call", input });

test("read reaches host; denial blocks even read; explicit legacy opt-out skips", async () => {
    const denied = await extension({ allowed: false });
    assert.equal((await denied.call(call("read", { path: "note.md" }))).block, true);
    assert.equal(denied.requests[0].toolName, "read");
    const legacy = await extension({ gate: "false" });
    assert.equal(await legacy.call(call("read")), undefined);
    assert.equal(legacy.requests.length, 0);
});

test("malformed environment gates every tool and never trusts a broken hosted list", async () => {
    for (const config of [{ gate: "bad" }, { gate: '"true"' }, { gate: "false", specs: "{}" }]) {
        const api = await extension(config);
        await api.call(call("read"));
        assert.equal(api.requests.length, 1);
    }
});

test("generic hosted tools use their own bridge gate", async () => {
    const api = await extension({ specs: '[{"name":"scheduler_sleep"}]' });
    await api.call(call("scheduler_sleep"));
    assert.equal(api.requests.length, 0);
});

test("edits pin the exact executed path; rewritten paths cannot claim pinned coverage", async () => {
    const api = await extension();
    const edit = call("edit", { path: "note.md", oldText: "before", newText: "after" });
    await api.call(edit);
    assert.equal(edit.input.path, api.requests[0].path);
    assert.equal(api.requests[0].arguments.newText, "after");
    await api.call(call("write", { path: "~/note.md" }));
    assert.equal(api.requests[1].path, undefined);
});

test("search keeps original arguments and prepends context without changing result content or error", async () => {
    const api = await extension({ note: "Hook context" });
    const search = call("web_search", { query: "original", limit: 3 });
    await api.call(search);
    assert.deepEqual(api.requests[0].arguments, search.input);
    const result = { ...search, content: [{ type: "text", text: "failed search" }], isError: true, details: { status: 500 } };
    const patch = await api.result(result);
    assert.deepEqual(patch, { content: [{ type: "text", text: "Hook context" }, ...result.content] });
    assert.equal(api.requests[1].isError, true);
    assert.equal(api.requests[1].result, "failed search");
    assert.equal((await api.result(result)), undefined); // one completion per authorized call
});

test("denied and ended searches never dispatch a result hook", async () => {
    const denied = await extension({ allowed: false });
    const search = call("web_fetch", { url: "https://example.com" });
    await denied.call(search);
    assert.equal(await denied.result(search), undefined);
    const ended = await extension();
    await ended.call(search);
    ended.end();
    assert.equal(await ended.result(search), undefined);
});

test("hook timeout adds nothing and oversized hook input never truncates actual result", async () => {
    const api = await extension();
    const search = call("web_search", { query: "original" });
    await api.call(search);
    const result = { ...search, content: [{ type: "text", text: "x".repeat(40000) }], isError: false };
    assert.equal(await api.result(result), undefined);
    assert.ok(api.requests[1].result.length <= 32768);
    assert.ok(api.requests[1].result.endsWith("[Result truncated for hook]"));
    assert.equal(result.content[0].text.length, 40000);
});

// Run with Node 22.18+ (native TypeScript stripping): node --test <this file>.
import assert from "node:assert/strict";
import { test } from "node:test";

let generation = 0;
async function extension({ gate = "true", specs = "[]", allowed = true, note, reply = JSON.stringify(allowed ? { allowed: true } : { allowed: false, reason: "Policy denied" }) } = {}) {
    process.env.HEARTBEAT_GATE_ALL = gate;
    process.env.HEARTBEAT_AGENT_TOOL_SPECS = specs;
    const handlers = new Map();
    const requests = [];
    const module = await import(new URL(`../../../jvmMain/resources/pi/heartbeat-approval.ts?test=${generation++}`, import.meta.url));
    module.default({ on: (event, handler) => handlers.set(event, handler) });
    const ctx = { hasUI: true, cwd: "/workspace", ui: {
        input: async (title, message, options) => {
            requests.push({ title, ...JSON.parse(message), options });
            return title === "heartbeat.tool-approval" ? reply : note;
        },
    } };
    return { requests, ctx, call: event => handlers.get("tool_call")(event, ctx),
        result: event => handlers.get("tool_result")(event, ctx), end: () => handlers.get("agent_end")() };
}
let callId = 0;
const call = (toolName, input = {}) => ({ toolName, toolCallId: `call-${callId++}`, input });

test("read reaches host; denial blocks even read; legacy opt-out cannot bypass the gate", async () => {
    const denied = await extension({ allowed: false });
    assert.equal((await denied.call(call("read", { path: "note.md" }))).block, true);
    assert.equal(denied.requests[0].toolName, "read");
    const legacy = await extension({ gate: "false" });
    assert.equal(await legacy.call(call("read")), undefined);
    assert.equal(legacy.requests.length, 1);
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


test("automatic refusal preserves the host reason instead of attributing it to the user", async () => {
    const reason = 'Automatic safety is uncertain: inspect "config" before editing.\nRetry with a bounded change.';
    const api = await extension({ reply: JSON.stringify({ allowed: false, reason }) });
    assert.deepEqual(await api.call(call("write", { path: "config" })), { block: true, reason });
    assert.equal(api.requests[0].title, "heartbeat.tool-approval");
    assert.ok(api.requests[0].toolCallId.startsWith("call-"));
    const allowed = await extension({ reply: '{"allowed":true}' });
    assert.equal(await allowed.call(call("write", { path: "config" })), undefined);
});

test("missing malformed and ambiguous verdicts never authorize a native call", async () => {
    for (const reply of [undefined, null, true, {}, "", "not-json", "null", "[]", "true", "{}",
        '{"allowed":"true"}', '{"allowed":1}', '{"allowed":true,"reason":"denied"}',
        '{"allowed":true,"extra":1}', '{"allowed":false}', '{"allowed":false,"reason":""}',
        '{"allowed":false,"reason":42}', '{"allowed":false,"reason":"denied","extra":1}']) {
        const api = await extension();
        api.ctx.ui.input = async () => reply;
        assert.deepEqual(await api.call(call("bash", { command: "touch result" })), {
            block: true, reason: "Heartbeat did not return a valid tool authorization",
        });
    }
    const noUi = await extension();
    noUi.ctx.hasUI = false;
    assert.equal((await noUi.call(call("bash"))).block, true);
    assert.equal(noUi.requests.length, 0);
});


test("authorization binds mutable input cwd and turn lifetime", async () => {
    for (const mutate of [
        (api, event) => { event.input.command = "changed"; },
        (api, event) => { event.input = { ...event.input }; },
        (api, event) => { event.toolName = "write"; },
        (api, event) => { event.toolCallId = "different"; },
        (api) => { api.ctx.cwd = "/different"; },
        (api) => api.end(),
    ]) {
        const api = await extension();
        let answer;
        api.ctx.ui.input = () => new Promise(resolve => { answer = resolve; });
        const event = call("bash", { command: "original" });
        const pending = api.call(event);
        mutate(api, event);
        answer('{"allowed":true}');
        assert.equal((await pending).block, true);
    }
});

test("downstream handlers cannot change an authorized call and authorization is single use", async () => {
    const api = await extension();
    const event = call("bash", { command: "original", nested: { args: ["one"] } });
    assert.equal(await api.call(event), undefined);
    assert.throws(() => { event.input.command = "changed"; }, TypeError);
    assert.throws(() => { event.input.nested.args.push("two"); }, TypeError);
    assert.throws(() => { event.input = {}; }, TypeError);
    assert.equal(event.input.command, "original");
    assert.equal((await api.call(event)).block, true);
    assert.equal(api.requests.length, 1);
});


test("already shallow frozen containers still have every nested argument frozen", async () => {
    const api = await extension();
    const input = Object.freeze({ command: "original", nested: Object.freeze({ args: ["one"] }) });
    assert.equal(await api.call(call("bash", input)), undefined);
    assert.throws(() => input.nested.args.push("two"), TypeError);
    assert.deepEqual(input.nested.args, ["one"]);
});

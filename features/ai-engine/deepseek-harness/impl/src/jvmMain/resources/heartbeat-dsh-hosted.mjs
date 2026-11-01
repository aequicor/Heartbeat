// Transport binding for DSH 0.2.1-rc.5. No model arguments or policy decisions are rewritten.
export const inject = ["tools"];
const prefix = "mcp__heartbeat_tools__";
export function apply(ctx) {
  const wrapped = new WeakSet();
  let active = true;
  ctx.on("tools/pre-execute", async (exec, next) => {
    const decision = await next();
    if (!exec.name.startsWith(prefix)) return decision;
    const tool = ctx.tools.get(exec.name, exec.agent);
    if (!tool || !exec.agent || !exec.callId) throw new Error("Heartbeat tool binding unavailable");
    if (!wrapped.has(tool)) {
      const original = tool.execute;
      const bound = function (args, execution) {
        if (!active || !execution.agent || !execution.callId || execution.signal.aborted) {
          throw new Error("Heartbeat tool execution is no longer active");
        }
        // Only the MCP transport sees this reserved field; the durable arguments remain unchanged.
        return original.call(this, { ...args, __heartbeat_call: execution.callId }, execution);
      };
      tool.execute = bound;
      wrapped.add(tool);
    }
    // ACP drains its ordered tool updates before asking. Heartbeat admits the transport once;
    // the hosted dispatcher still owns the actual action permission and execution.
    return decision.kind === "allow" ? { kind: "ask", reason: "Heartbeat hosted tool binding" } : decision;
  });
  ctx.on("dispose", () => {
    active = false;
  });
}

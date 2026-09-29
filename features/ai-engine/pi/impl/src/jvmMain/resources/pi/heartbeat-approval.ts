/**
 * Heartbeat tool approval for the bundled Pi runtime.
 *
 * Every tool call except read-only inspection waits for a Heartbeat decision: the host answers it on its own when
 * the turn's trust level covers the tool, otherwise the user decides.
 * The request travels over Pi's RPC extension UI protocol (`confirm`), and the host answers with
 * `extension_ui_response`. A missing, cancelled or failed answer blocks the tool: Pi treats a failing
 * `tool_call` handler as a block as well.
 */
const APPROVAL_TITLE = "heartbeat.tool-approval";
const READ_ONLY_TOOLS = new Set(["read", "grep", "find", "ls", "web_search", "web_fetch"]);

export default function (pi: any) {
	pi.on("tool_call", async (event: any, ctx: any) => {
		if (READ_ONLY_TOOLS.has(event.toolName)) return undefined;
		const input = event.input ?? {};
		const target = typeof input.command === "string" ? input.command : typeof input.path === "string" ? input.path : "";
		const request = JSON.stringify({ toolCallId: event.toolCallId, toolName: event.toolName, target });
		const allowed = ctx.hasUI ? await ctx.ui.confirm(APPROVAL_TITLE, request) : false;
		return allowed === true ? undefined : { block: true, reason: "The Heartbeat user did not allow this tool call" };
	});
}

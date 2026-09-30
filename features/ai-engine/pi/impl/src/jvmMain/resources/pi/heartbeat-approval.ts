/**
 * Heartbeat tool approval for the bundled Pi runtime.
 *
 * Every tool call except read-only inspection waits for a Heartbeat decision: the host answers it on its own when
 * the turn's trust level covers the tool, otherwise the user decides.
 * The request travels over Pi's RPC extension UI protocol (`confirm`), and the host answers with
 * `extension_ui_response`. A missing, cancelled or failed answer blocks the tool: Pi treats a failing
 * `tool_call` handler as a block as well.
 *
 * `edit` / `write` paths are pinned: the path is resolved against the working directory and written back into the
 * call, so the host judges exactly the file Pi writes (Pi executes the mutated input and leaves an absolute path
 * unchanged). Paths Pi rewrites before resolving them are not pinned and always reach the user.
 */
import { resolve } from "node:path";

const APPROVAL_TITLE = "heartbeat.tool-approval";
const READ_ONLY_TOOLS = new Set(["read", "grep", "find", "ls", "web_search", "web_fetch"]);
const EDIT_TOOLS = new Set(["edit", "write"]);
const HOSTED_TOOLS = new Set((JSON.parse(process.env.HEARTBEAT_AGENT_TOOL_SPECS ?? "[]") as Array<{ name: string }>)
	.map(spec => spec.name));
// Pi's path normalization replaces these spaces and rewrites `@`, `~`, `file:` and, on Windows, `/c/` shell paths.
const UNICODE_SPACES = /[\u00A0\u2000-\u200A\u202F\u205F\u3000]/;
const WINDOWS_SHELL_PATH = /^\/(?:mnt\/|cygdrive\/)?[a-z](?:\/|$)/i;

function isRewrittenByPi(path: string): boolean {
	return path.startsWith("@") || path.startsWith("~") || /^file:/i.test(path) || UNICODE_SPACES.test(path) ||
		(process.platform === "win32" && WINDOWS_SHELL_PATH.test(path));
}

/** The absolute path Pi will write, written back into [input]; undefined when Pi would rewrite the path. */
function pinPath(input: any, cwd: string): string | undefined {
	if (typeof input.path !== "string" || input.path === "" || isRewrittenByPi(input.path)) return undefined;
	const absolute = resolve(cwd, input.path);
	if (isRewrittenByPi(absolute)) return undefined;
	input.path = absolute;
	return absolute;
}

export default function (pi: any) {
	pi.on("tool_call", async (event: any, ctx: any) => {
		if (READ_ONLY_TOOLS.has(event.toolName) || HOSTED_TOOLS.has(event.toolName)) return undefined;
		const input = event.input ?? {};
		let target: string;
		let path: string | undefined;
		if (EDIT_TOOLS.has(event.toolName)) {
			path = pinPath(input, ctx.cwd);
			target = path ?? (typeof input.path === "string" ? input.path : "");
		} else {
			target = typeof input.command === "string" ? input.command : typeof input.path === "string" ? input.path : "";
		}
		const request = JSON.stringify({ toolCallId: event.toolCallId, toolName: event.toolName, target, path });
		const allowed = ctx.hasUI ? await ctx.ui.confirm(APPROVAL_TITLE, request) : false;
		return allowed === true ? undefined : { block: true, reason: "The Heartbeat user did not allow this tool call" };
	});
}

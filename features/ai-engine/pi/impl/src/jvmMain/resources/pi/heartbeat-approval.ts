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
 *
 * Pi upgrade checklist (repeat for each bundled version on macOS and Windows):
 * - Inspect Pi's tool pipeline: validate -> tool_call -> execute must pass the same mutable event.input to the
 *   tool; edit/write must execute our pinned absolute path without applying another rewrite.
 * - In a disposable workspace, exercise edit and write with relative and absolute paths. Compare the RPC
 *   approval payload's path with the actual file changed; AutoEdits may approve an ordinary file inside it.
 * - Exercise @, ~, file:, Unicode spaces from UNICODE_SPACES, and Windows /c/, /mnt/c/ and /cygdrive/c/ paths.
 *   Also use a cwd containing a Unicode space: when either the input or resolved path would be rewritten,
 *   the approval payload must omit path and AutoEdits must ask the user.
 * - Check an outside-workspace path and a protected project file (for example .git/config): AutoEdits must
 *   ask the user. A denied, missing, cancelled or failed confirmation must leave every target unchanged.
 * Record the tested Pi version, OS and results in the upgrade PR; if an invariant changes, update the pinning
 * and host approval policy before shipping the new runtime. JVM approval fixtures do not execute this script.
 */
import { resolve } from "node:path";

const APPROVAL_TITLE = "heartbeat.tool-approval";
const READ_ONLY_TOOLS = new Set(["read", "grep", "find", "ls", "web_search", "web_fetch"]);
const EDIT_TOOLS = new Set(["edit", "write"]);
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
		if (READ_ONLY_TOOLS.has(event.toolName)) return undefined;
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

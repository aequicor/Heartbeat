/**
 * Heartbeat tool approval for the bundled Pi runtime.
 *
 * Every native and adapter-operated tool call waits for a Heartbeat decision: the host answers it on its own when
 * shared profile mode permits it, or asks the user for this exact action. The request travels over Pi's RPC extension UI protocol (`input`); its string value carries a JSON
 * verdict so refusals preserve their reason. The host answers with
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
 *   approval payload's path with the actual file changed. Automatic asks for native mutations; Trust permits them.
 * - Exercise @, ~, file:, Unicode spaces from UNICODE_SPACES, and Windows /c/, /mnt/c/ and /cygdrive/c/ paths.
 *   Also use a cwd containing a Unicode space: when either the input or resolved path would be rewritten,
 *   the approval payload must omit path; Automatic asks for confirmation.
 * - Check an outside-workspace path and a protected project file (for example .git/config): Automatic must
 *   ask the user. A denied, missing, cancelled or failed confirmation must leave every target unchanged.
 * Record the tested Pi version, OS and results in the upgrade PR; if an invariant changes, update the pinning
 * and host approval policy before shipping the new runtime. JVM approval fixtures do not execute this script.
 */
import { resolve } from "node:path";

const APPROVAL_TITLE = "heartbeat.tool-approval";
const EDIT_TOOLS = new Set(["edit", "write"]);
// Hosted bridge tools have their own shared gate; every other tool reaches this gate.
function hostedTools(): Set<string> {
    try {
        const specs = JSON.parse(process.env.HEARTBEAT_AGENT_TOOL_SPECS ?? "[]");
        return Array.isArray(specs) && specs.every(spec => typeof spec?.name === "string")
            ? new Set(specs.map(spec => spec.name)) : new Set();
    } catch { return new Set(); }
}
const HOSTED_TOOLS = hostedTools();
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

/** Only an exact host verdict authorizes execution; missing or malformed replies fail closed. */
function decisionReply(value: unknown): { allowed: boolean; reason?: string } {
    const refused = { allowed: false, reason: "Heartbeat did not return a valid tool authorization" };
    if (typeof value !== "string") return refused;
    let decision: any;
    try { decision = JSON.parse(value); } catch { return refused; }
    if (!decision || typeof decision !== "object" || Array.isArray(decision)) return refused;
    const keys = Object.keys(decision);
    if (decision.allowed === true && keys.length === 1) return { allowed: true };
    if (decision.allowed === false && keys.length === 2 && typeof decision.reason === "string" && decision.reason.trim()) {
        return { allowed: false, reason: decision.reason };
    }
    return refused;
}

/** Pi passes this original object to later handlers and execution. Mutating an authorized call is invalid. */
function freezeInput(value: any): void {
    if (!value || typeof value !== "object") return;
    for (const nested of Object.values(value)) freezeInput(nested);
    Object.freeze(value);
}

export default function (pi: any) {
    const completed = new Set<string>();
    const claimed = new Set<string>();
    let generation = 0;
    pi.on("agent_end", () => { generation++; completed.clear(); claimed.clear(); });
	pi.on("tool_call", async (event: any, ctx: any) => {
		if (HOSTED_TOOLS.has(event.toolName)) return undefined;
        const { toolName, toolCallId } = event;
        if (claimed.has(toolCallId)) return { block: true, reason: "Tool authorization was already consumed" };
        claimed.add(toolCallId);
        const callGeneration = generation;
        const cwd = ctx.cwd;
		const input = event.input ?? {};
		let target: string;
		let path: string | undefined;
		if (EDIT_TOOLS.has(event.toolName)) {
			path = pinPath(input, ctx.cwd);
			target = path ?? (typeof input.path === "string" ? input.path : "");
		} else if (event.toolName === "web_search" || event.toolName === "web_fetch") {
            target = typeof input.query === "string" ? input.query : typeof input.url === "string" ? input.url : "";
        } else {
			target = typeof input.command === "string" ? input.command : typeof input.path === "string" ? input.path : "";
		}
        const request = JSON.stringify({ toolCallId: event.toolCallId, toolName: event.toolName, target, path, arguments: input });
        const inputSnapshot = JSON.stringify(input);
        const decision = decisionReply(ctx.hasUI ? await ctx.ui.input(APPROVAL_TITLE, request) : undefined);
        if (decision.allowed) {
            if (generation !== callGeneration || ctx.cwd !== cwd || event.input !== input ||
                event.toolName !== toolName || event.toolCallId !== toolCallId || JSON.stringify(input) !== inputSnapshot) {
                return { block: true, reason: "Tool call changed after authorization; request it again" };
            }
            freezeInput(input);
            Object.freeze(event);
        }
        if (decision.allowed && (event.toolName === "web_search" || event.toolName === "web_fetch")) {
            completed.add(event.toolCallId);
        }
		return decision.allowed ? undefined : { block: true, reason: decision.reason };
	});
    pi.on("tool_result", async (event: any, ctx: any) => {
        if (!completed.delete(event.toolCallId) || !ctx.hasUI) return undefined;
        const text = event.content.filter((part: any) => part.type === "text").map((part: any) => part.text).join("\n");
        const result = text.length > 32768 ? text.slice(0, 32740) + "\n[Result truncated for hook]" : text;
        const request = JSON.stringify({ toolName: event.toolName, arguments: event.input, result, isError: event.isError });
        const note = await ctx.ui.input("heartbeat.tool-result", request, { timeout: 2000 });
        if (typeof note !== "string" || !note.trim()) return undefined;
        // Preserve every original content block, details and error status. Hooks only prepend context.
        return { content: [{ type: "text", text: note.slice(0, 2000) }, ...event.content] };
    });
}

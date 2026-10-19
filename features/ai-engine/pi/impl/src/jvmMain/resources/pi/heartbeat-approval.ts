/**
 * Heartbeat tool approval for the bundled Pi runtime.
 *
 * Every native and adapter-operated tool call waits for a Heartbeat decision: the host answers it on its own when
 * the turn's trust level covers the tool, or uses the turn's automatic safety reviewer. Interactive turns may ask
 * the user. The request travels over Pi's RPC extension UI protocol (`input`); its string value carries a JSON
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
import { existsSync } from "node:fs";

const APPROVAL_TITLE = "heartbeat.tool-approval";
const READ_ONLY_TOOLS = new Set(["read", "grep", "find", "ls", "web_search", "web_fetch"]);
const EDIT_TOOLS = new Set(["edit", "write"]);
// The launcher enables the gate even before any harness exists: later live Off must not require a restart.
// A legacy caller may explicitly opt out; malformed environment always falls back to gating everything.
function gateConfig(): { hosted: Set<string>; all: boolean } {
    try {
        const specs = JSON.parse(process.env.HEARTBEAT_AGENT_TOOL_SPECS ?? "[]");
        const all = JSON.parse(process.env.HEARTBEAT_GATE_ALL ?? "true");
        if (typeof all !== "boolean" || !Array.isArray(specs) || specs.some(spec => typeof spec?.name !== "string")) {
            return { hosted: new Set(), all: true };
        }
        return { hosted: new Set(specs.map(spec => spec.name)), all };
    } catch { return { hosted: new Set(), all: true }; }
}
const { hosted: HOSTED_TOOLS, all: GATE_ALL } = gateConfig();
// Pi's path normalization replaces these spaces and rewrites `@`, `~`, `file:` and, on Windows, `/c/` shell paths.
const UNICODE_SPACES = /[\u00A0\u2000-\u200A\u202F\u205F\u3000]/;
const WINDOWS_SHELL_PATH = /^\/(?:mnt\/|cygdrive\/)?[a-z](?:\/|$)/i;
const SIMPLE_PUSH = /^git[ \t]+push(?:[ \t]+(?:-u[ \t]+)?origin[ \t]+[A-Za-z0-9][A-Za-z0-9._/-]*)?$/;
const ENVIRONMENT_KEYS = new Set([
    "PATH", "HOME", "XDG_CONFIG_HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "SYSTEMROOT", "WINDIR",
    "PATHEXT", "PROGRAMFILES", "PROGRAMFILES(X86)", "TMPDIR", "TMP", "TEMP",
]);

/** Only paths/config discovery inputs cross RPC. Override values and credentials stay in this closure. */
function gitEnvironment(env: Record<string, string | undefined>): Record<string, string> {
    const result: Record<string, string> = {};
    let unsafe = false;
    for (const [key, value] of Object.entries(env)) {
        if (value === undefined) continue;
        const upper = key.toUpperCase();
        if (ENVIRONMENT_KEYS.has(upper)) result[key] = value;
        if (/^(GIT_|SSH_|BASH_FUNC_|LD_|DYLD_|CURL_|GCM_)/.test(upper) &&
            !["SSH_AUTH_SOCK", "SSH_AGENT_PID", "GIT_PAGER"].includes(upper)) unsafe = true;
        if (["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "BASH_ENV", "ENV", "ZDOTDIR", "SHELLOPTS", "BASHOPTS", "SSL_CERT_FILE", "SSL_CERT_DIR"].includes(upper)) unsafe = true;
    }
    if (unsafe) result.GIT_HEARTBEAT_UNSAFE_OVERRIDE_PRESENT = "1";
    return result;
}

type PushPlan = { command: string; cwd: string; env: Record<string, string | undefined>; approved: boolean };

/**
 * Only the hermetic bundled Unix runtime installs this override. Ordinary commands retain Pi's default tool.
 * A push consumes a private plan from tool_call and forces the very same command/cwd/environment at spawn.
 */
async function pinNativePush(pi: any, plans: Map<string, PushPlan>): Promise<boolean> {
    if (process.env.HEARTBEAT_PIN_NATIVE_PUSH !== "true" || process.platform === "win32" || !existsSync("/bin/bash")) return false;
    const { createBashToolDefinition } = await import("@earendil-works/pi-coding-agent");
    const original = createBashToolDefinition(process.cwd());
    pi.registerTool({
        ...original,
        async execute(id: string, input: any, signal: any, onUpdate: any, ctx: any) {
            const plan = plans.get(id);
            plans.delete(id);
            if (!plan) {
                if (typeof input.command === "string" && SIMPLE_PUSH.test(input.command.trim())) {
                    throw new Error("Native push has no live host-approved execution plan");
                }
                return createBashToolDefinition(ctx.cwd).execute(id, input, signal, onUpdate, ctx);
            }
            if (!plan.approved || input.command !== plan.command || resolve(ctx.cwd) !== plan.cwd || signal?.aborted) {
                throw new Error("Native push changed after its host approval");
            }
            const pinned = createBashToolDefinition(plan.cwd, {
                shellPath: "/bin/bash",
                spawnHook: () => ({ command: plan.command, cwd: plan.cwd, env: { ...plan.env } }),
            });
            return pinned.execute(id, input, signal, onUpdate, ctx);
        },
    });
    return true;
}

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

export default async function (pi: any) {
    const completed = new Set<string>();
    const pushes = new Map<string, PushPlan>();
    const isPushPinned = await pinNativePush(pi, pushes);
    pi.on("agent_end", () => { completed.clear(); pushes.clear(); });
	pi.on("tool_call", async (event: any, ctx: any) => {
		if (HOSTED_TOOLS.has(event.toolName)) return undefined;
		if (!GATE_ALL && READ_ONLY_TOOLS.has(event.toolName)) return undefined;
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
        let plan: PushPlan | undefined;
        if (isPushPinned && event.toolName === "bash" && SIMPLE_PUSH.test(target.trim()) &&
            typeof event.toolCallId === "string" && pushes.size < 16) {
            plan = { command: target, cwd: resolve(ctx.cwd), env: { ...process.env }, approved: false };
            pushes.set(event.toolCallId, plan);
        }
        const nativeCommandEnvironment = plan ? { version: 1, cwd: plan.cwd, values: gitEnvironment(plan.env) } : undefined;
		const request = JSON.stringify({ toolCallId: event.toolCallId, toolName: event.toolName, target, path, arguments: input, nativeCommandEnvironment });
        let decision: { allowed: boolean; reason?: string };
        try {
            decision = decisionReply(ctx.hasUI ? await ctx.ui.input(APPROVAL_TITLE, request) : undefined);
        } catch (error) {
            pushes.delete(event.toolCallId);
            throw error;
        }
        if (plan) {
            plan.approved = decision.allowed;
            if (!decision.allowed) pushes.delete(event.toolCallId);
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

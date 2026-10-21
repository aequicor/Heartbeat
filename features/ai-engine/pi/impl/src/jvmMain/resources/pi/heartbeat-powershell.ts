/** Host-owned PowerShell executor. Only the launch manifest can select its program, argv, cwd and environment. */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { win32 } from "node:path";

const MANIFEST = "HEARTBEAT_CONTROLLED_POWERSHELL";
const ARGUMENTS = ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command"];
// Must match facade:api POWER_SHELL_UTF8_INITIALIZATION byte for byte; included in the host binding.
const INITIALIZATION = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)\n$OutputEncoding = [Console]::OutputEncoding\n";
const REVISION = "heartbeat-powershell-v1";
const MAX_TIMEOUT_MS = 2_147_483_647;
const PIPE_DRAIN_MS = 250;
const STOP_TIMEOUT_MS = 5000;

type Configuration = {
    revision: string;
    executable: string;
    argumentPrefix: string[];
    initialization: string;
    directory: string;
    environment: Record<string, string>;
    taskkill: string;
};
export type ControlledPowerShell = Readonly<Configuration & { binding: string }>;
type ExecutionOptions = { onData: (data: Buffer) => void; signal?: AbortSignal; timeout?: number; env?: object };
type Consume = (id: string, input: object, directory: string) => boolean;

/** The outer JSON hashes the exact inner string, avoiding cross-language JSON canonicalization differences. */
export function readControlledPowerShell(raw: string | undefined): ControlledPowerShell | undefined {
    if (raw === undefined || process.platform !== "win32") return undefined;
    try {
        const { configuration, binding } = JSON.parse(raw);
        if (typeof configuration !== "string" || typeof binding !== "string" ||
            createHash("sha256").update(configuration, "utf8").digest("hex") !== binding) return undefined;
        const value = JSON.parse(configuration) as Configuration;
        if (value.revision !== REVISION || value.initialization !== INITIALIZATION ||
            !value.environment || typeof value.environment !== "object" ||
            Array.isArray(value.environment) || !Object.values(value.environment).every(item => typeof item === "string") ||
            !Array.isArray(value.argumentPrefix) || JSON.stringify(value.argumentPrefix) !== JSON.stringify(ARGUMENTS) ||
            typeof value.directory !== "string" || !win32.isAbsolute(value.directory)) return undefined;
        const root = Object.entries(value.environment).find(([name]) => name.toUpperCase() === "SYSTEMROOT")?.[1];
        if (!root || !win32.isAbsolute(root)) return undefined;
        const expected = win32.join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        const taskkill = win32.join(root, "System32", "taskkill.exe");
        if (value.executable !== expected || value.taskkill !== taskkill ||
            value.environment.PSModulePath !== win32.join(win32.dirname(expected), "Modules")) return undefined;
        Object.freeze(value.environment);
        Object.freeze(value.argumentPrefix);
        return Object.freeze({ ...value, binding });
    } catch { return undefined; }
}

/** Ignores Pi's ambient environment and spawn hooks; execution is bound to the host's immutable snapshot. */
export function controlledPowerShellOperations(manifest: ControlledPowerShell) {
    return {
        async exec(command: string, directory: string, options: ExecutionOptions): Promise<{ exitCode: number | null }> {
            if (directory !== manifest.directory || typeof command !== "string") throw new Error("PowerShell call changed");
            const { onData, signal, timeout } = options;
            const timeoutMs = timeout === undefined ? undefined : timeout * 1000;
            if (timeoutMs !== undefined && (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS)) {
                throw new Error("Invalid PowerShell timeout");
            }
            if (signal?.aborted) throw new Error("aborted");
            const child = spawn(manifest.executable, [...manifest.argumentPrefix, manifest.initialization + command], {
                cwd: manifest.directory, env: manifest.environment, stdio: ["ignore", "pipe", "pipe"], windowsHide: true,
            });
            let timedOut = false;
            let stopping: Promise<void> | undefined;
            let executionError: Error | undefined;
            let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
            let drainHandle: ReturnType<typeof setTimeout> | undefined;
            const stop = () => {
                if (stopping || !child.pid || child.exitCode !== null || child.signalCode !== null) return;
                // Use the host's absolute taskkill, never PATH, and wait for tree cleanup before completing the call.
                stopping = new Promise<void>((resolve, reject) => {
                    const killer = spawn(manifest.taskkill, ["/PID", String(child.pid), "/T", "/F"], {
                        env: manifest.environment, stdio: "ignore", windowsHide: true,
                    });
                    const timer = setTimeout(() => {
                        killer.kill(); child.kill(); reject(new Error("PowerShell process tree cleanup timed out"));
                    }, STOP_TIMEOUT_MS);
                    killer.once("error", error => { clearTimeout(timer); child.kill(); reject(error); });
                    killer.once("exit", code => {
                        clearTimeout(timer);
                        if (code === 0 || child.exitCode !== null || child.signalCode !== null) resolve();
                        else { child.kill(); reject(new Error(`PowerShell process tree cleanup failed (${code})`)); }
                    });
                });
                // Retain the failure for the awaiting execution rather than producing an unhandled rejection.
                stopping.catch(error => { executionError = error; });
            };
            const stream = (data: Buffer) => {
                try { onData(data); } catch (error) {
                    executionError = error instanceof Error ? error : new Error("PowerShell output consumer failed");
                    stop();
                }
            };
            child.stdout.on("data", stream);
            child.stderr.on("data", stream);
            const onAbort = () => stop();
            try {
                if (signal?.aborted) stop();
                else signal?.addEventListener("abort", onAbort, { once: true });
                if (timeoutMs !== undefined) timeoutHandle = setTimeout(() => { timedOut = true; stop(); }, timeoutMs);
                const exitCode = await new Promise<number | null>((resolve, reject) => {
                    child.once("error", reject);
                    child.once("close", resolve);
                    child.once("exit", code => {
                        // Descendants may inherit pipe handles. Do not retain an unbounded reader after shell exit.
                        drainHandle = setTimeout(() => {
                            child.stdout.destroy(); child.stderr.destroy(); resolve(code);
                        }, PIPE_DRAIN_MS);
                    });
                });
                await stopping;
                if (executionError) throw executionError;
                if (signal?.aborted) throw new Error("aborted");
                if (timedOut) throw new Error(`timeout:${timeout}`);
                return { exitCode: exitCode ?? 1 };
            } finally {
                if (timeoutHandle) clearTimeout(timeoutHandle);
                if (drainHandle) clearTimeout(drainHandle);
                signal?.removeEventListener("abort", onAbort);
                child.stdout.destroy(); child.stderr.destroy();
            }
        },
    };
}

/** Registers before all other bundled extensions. Missing APIs or an invalid manifest keep native calls unknown. */
export async function installControlledPowerShell(pi: any, consume: Consume, factory?: any): Promise<ControlledPowerShell | undefined> {
    const raw = process.env[MANIFEST];
    delete process.env[MANIFEST];
    const manifest = readControlledPowerShell(raw);
    if (!manifest) return undefined;
    let api;
    try { api = factory ?? await import("@earendil-works/pi-coding-agent"); } catch {
        console.error("Controlled PowerShell API is unavailable; native calls retain ordinary authorization");
        return undefined;
    }
    if (api.VERSION !== "0.87.1" || typeof api.createPowerShellToolDefinition !== "function") return undefined;
    const definition = api.createPowerShellToolDefinition(manifest.directory, {
        operations: controlledPowerShellOperations(manifest), exposeSessionEnvironment: false,
    });
    const execute = definition.execute;
    definition.execute = async (id: string, input: any, signal: AbortSignal, onUpdate: any, ctx: any) => {
        const isAuthorized = consume(id, input, ctx?.cwd);
        if (ctx?.cwd !== manifest.directory || !Object.isFrozen(input) || !isAuthorized) {
            throw new Error("PowerShell execution has no matching unconsumed authorization");
        }
        if (signal?.aborted) throw new Error("aborted");
        return execute(id, input, signal, onUpdate, ctx);
    };
    pi.registerTool(definition);
    return manifest;
}

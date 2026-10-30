/** Trusted, process-local provenance of agent turns launched by an ACP prompt. */
import { AsyncLocalStorage } from 'node:async_hooks';
const parentKey = Symbol.for('heartbeat.kimi.parent.v1');
const parentShared = globalThis as typeof globalThis & { [parentKey]?: AsyncLocalStorage<{ agent: string; turn: number }> };
const parentContext = parentShared[parentKey] ??= new AsyncLocalStorage<{ agent: string; turn: number }>();
export function withHeartbeatParent<T>(agent: string, turn: number, run: () => T): T {
  return parentContext.run({ agent, turn }, run);
}
export function heartbeatParent(): { agent: string; turn: number } | undefined {
  return parentContext.getStore();
}
type Run = { readonly turn: { readonly id?: number }; readonly completion: Promise<unknown> };
type Entry = {
  readonly parent: string;
  readonly parentTurn: number;
  readonly rootTurn: number | undefined;
  readonly origin: Promise<string | undefined>;
  readonly run: Promise<Run>;
};
const key = Symbol.for("heartbeat.kimi.origins.v1");
const shared = globalThis as typeof globalThis & { [key]?: Map<string, HeartbeatOrigins> };
const sessions = shared[key] ??= new Map<string, HeartbeatOrigins>();

export class HeartbeatOrigins {
  private readonly children = new Map<string, Entry>();
  constructor(private readonly main: (turn: number) => Promise<string | undefined>) {}

  register(agent: string, parent: string, parentTurn: number, run: Promise<Run>): void {
    // Ambiguous concurrent runs fail closed; they never replace authority for an existing run.
    if (this.children.has(agent)) return;
    const parentEntry = parent === 'main' ? undefined : this.children.get(parent);
    const rootTurn = parent === 'main' ? parentTurn : parentEntry?.rootTurn;
    const origin = parent === 'main' ? this.main(parentTurn) : this.capture(parentEntry, parentTurn);
    const entry = { parent, parentTurn, rootTurn, origin, run };
    this.children.set(agent, entry);
    const remove = () => { if (this.children.get(agent) === entry) this.children.delete(agent); };
    void run.then((started) => { void started.completion.then(remove, remove); }, remove);
  }

  private async capture(entry: Entry | undefined, turn: number): Promise<string | undefined> {
    if (entry === undefined || entry.rootTurn === undefined) return undefined;
    try {
      const run = await entry.run;
      if (run.turn.id !== turn) return undefined;
      const origin = await entry.origin;
      return origin !== undefined && await this.main(entry.rootTurn) === origin ? origin : undefined;
    } catch {
      return undefined;
    }
  }

  async resolve(agent: string, turn: number, seen = new Set<string>()): Promise<string | undefined> {
    if (seen.has(agent) || seen.size >= 64) return undefined;
    if (agent === 'main') return this.main(turn);
    seen.add(agent);
    const entry = this.children.get(agent);
    if (entry === undefined) return undefined;
    try {
      const run = await entry.run;
      if (this.children.get(agent) !== entry || run.turn.id !== turn) return undefined;
      const original = await entry.origin;
      if (original === undefined || entry.rootTurn === undefined || this.children.get(agent) !== entry) return undefined;
      const current = await this.main(entry.rootTurn);
      return current === original && this.children.get(agent) === entry ? original : undefined;
    } catch {
      return undefined;
    }
  }

}

export function installHeartbeatOrigins(id: string, origins: HeartbeatOrigins): void {
  sessions.set(id, origins);
}
export function removeHeartbeatOrigins(id: string, origins: HeartbeatOrigins): void {
  if (sessions.get(id) === origins) sessions.delete(id);
}
export function heartbeatOrigins(id: string): HeartbeatOrigins | undefined {
  return sessions.get(id);
}

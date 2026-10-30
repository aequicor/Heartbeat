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
export type HeartbeatLease = { readonly origin: string; watch(abort: () => void): Promise<(() => void) | undefined> };
type Run = { readonly turn: { readonly id?: number }; readonly completion: Promise<unknown> };
type Entry = {
  readonly parent: string;
  readonly parentTurn: number;
  readonly parentEntry: Entry | undefined;
  readonly signal: AbortSignal | undefined;
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

  register(agent: string, parent: string, parentTurn: number, run: Promise<Run>, signal?: AbortSignal): void {
    // Ambiguous concurrent runs fail closed; they never replace authority for an existing run.
    if (this.children.has(agent)) return;
    const parentEntry = parent === 'main' ? undefined : this.children.get(parent);
    const rootTurn = parent === 'main' ? parentTurn : parentEntry?.rootTurn;
    const origin = parent === 'main' ? this.main(parentTurn) : this.capture(parentEntry, parentTurn);
    const entry = { parent, parentTurn, parentEntry, signal, rootTurn, origin, run };
    this.children.set(agent, entry);
    const remove = () => { if (this.children.get(agent) === entry) this.children.delete(agent); };
    void run.then((started) => { void started.completion.then(remove, remove); }, remove);
  }

  private async capture(entry: Entry | undefined, turn: number): Promise<string | undefined> {
    if (entry === undefined || entry.rootTurn === undefined || !this.isLive(entry)) return undefined;
    try {
      const run = await entry.run;
      if (run.turn.id !== turn) return undefined;
      const origin = await entry.origin;
      return origin !== undefined && await this.main(entry.rootTurn) === origin && this.isLive(entry) ? origin : undefined;
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
      if (this.children.get(agent) !== entry || run.turn.id !== turn || !this.isLive(entry)) return undefined;
      const original = await entry.origin;
      if (original === undefined || entry.rootTurn === undefined || this.children.get(agent) !== entry) return undefined;
      const current = await this.main(entry.rootTurn);
      return current === original && this.children.get(agent) === entry && this.isLive(entry) ? original : undefined;
    } catch {
      return undefined;
    }
  }
  async lease(agent: string, turn: number): Promise<HeartbeatLease | undefined> {
    const generation = this.children.get(agent);
    const origin = await this.resolve(agent, turn);
    if (origin === undefined || (agent !== 'main' && this.children.get(agent) !== generation)) return undefined;
    return {
      origin,
      watch: async (onAbort) => {
        if (agent !== 'main' && this.children.get(agent) !== generation) return undefined;
        return this.watch(agent, turn, origin, onAbort);
      },
    };
  }

  async watch(agent: string, turn: number, expected: string, onAbort: () => void): Promise<(() => void) | undefined> {
    const generation = this.children.get(agent);
    if (await this.resolve(agent, turn) !== expected) return undefined;
    if (agent === 'main') return () => {};
    let entry = this.children.get(agent);
    if (entry === undefined || entry !== generation || !this.isLive(entry)) return undefined;
    const signals: AbortSignal[] = [];
    while (entry !== undefined) {
      if (entry.signal !== undefined) signals.push(entry.signal);
      entry = entry.parentEntry;
    }
    let active = true;
    const dispose = () => {
      if (!active) return;
      active = false;
      for (const signal of signals) signal.removeEventListener('abort', abort);
    };
    const abort = () => { dispose(); onAbort(); };
    for (const signal of signals) signal.addEventListener('abort', abort, { once: true });
    if (signals.some((signal) => signal.aborted)) abort();
    return dispose;
  }

  private isLive(entry: Entry): boolean {
    const seen = new Set<Entry>();
    let current: Entry | undefined = entry;
    while (current !== undefined) {
      if (seen.has(current) || seen.size >= 64 || current.signal?.aborted === true) return false;
      seen.add(current);
      current = current.parentEntry;
    }
    return true;
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

const callsKey = Symbol.for('heartbeat.kimi.hosted-revocations.v1');
const callsShared = globalThis as typeof globalThis & { [callsKey]?: Map<string, { turn: string; dispose: () => void }> };
const hostedRevocations = callsShared[callsKey] ??= new Map<string, { turn: string; dispose: () => void }>();
const turnsKey = Symbol.for('heartbeat.kimi.active-hosted-turns.v1');
const turnsShared = globalThis as typeof globalThis & { [turnsKey]?: Set<string> };
const activeHostedTurns = turnsShared[turnsKey] ??= new Set<string>();
export function beginHostedTurn(turn: string | undefined): void { if (turn !== undefined) activeHostedTurns.add(turn); }
export function registerHostedRevocation(call: string, turn: string, dispose: () => void): boolean {
  if (!activeHostedTurns.has(turn)) { dispose(); return false; }
  hostedRevocations.get(call)?.dispose();
  hostedRevocations.set(call, { turn, dispose });
  return true;
}
export function completeHostedCall(call: string | undefined): void {
  if (call === undefined) return;
  const dispose = hostedRevocations.get(call);
  hostedRevocations.delete(call);
  dispose?.dispose();
}
export function clearHostedTurn(turn: string | undefined): void {
  if (turn === undefined) return;
  activeHostedTurns.delete(turn);
  for (const [call, entry] of hostedRevocations) {
    if (entry.turn === turn) completeHostedCall(call);
  }
}

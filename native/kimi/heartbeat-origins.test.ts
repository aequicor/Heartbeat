import { describe, expect, it } from 'vitest';
import { HeartbeatOrigins, heartbeatOrigins, installHeartbeatOrigins, removeHeartbeatOrigins, withHeartbeatParent, heartbeatParent } from '../../src/heartbeat/origins';

describe('Heartbeat agent origin', () => {
  it('waits for early child launches and preserves ancestry across nested agents', async () => {
    let root = 'root-one';
    const origins = new HeartbeatOrigins(async (turn) => turn === 10 ? root : undefined);
    let start!: (value: { turn: { id: number }; completion: Promise<unknown> }) => void;
    const child = new Promise<{ turn: { id: number }; completion: Promise<unknown> }>((resolve) => { start = resolve; });
    const never = new Promise<unknown>(() => {});
    origins.register('child', 'main', 10, child);
    const early = origins.resolve('child', 20);
    start({ turn: { id: 20 }, completion: never });
    expect(await early).toBe('root-one');
    origins.register('grandchild', 'child', 20, Promise.resolve({ turn: { id: 30 }, completion: never }));
    expect(await origins.resolve('grandchild', 30)).toBe('root-one');
    expect(await origins.resolve('child', 21)).toBeUndefined();
    root = 'root-two';
    expect(await origins.resolve('child', 20)).toBeUndefined();
    expect(await origins.resolve('grandchild', 30)).toBeUndefined();
  });

  it('does not replace a live generation or retain authority after completion', async () => {
    const origins = new HeartbeatOrigins(async () => 'root');
    let finish!: () => void;
    const completion = new Promise<void>((resolve) => { finish = resolve; });
    origins.register('child', 'main', 1, Promise.resolve({ turn: { id: 2 }, completion }));
    origins.register('child', 'main', 1, Promise.resolve({ turn: { id: 3 }, completion }));
    expect(await origins.resolve('child', 2)).toBe('root');
    expect(await origins.resolve('child', 3)).toBeUndefined();
    finish();
    await completion;
    await Promise.resolve();
    expect(await origins.resolve('child', 2)).toBeUndefined();
  });

  it('keeps a background descendant bound after its parent completes', async () => {
    let root = 'first';
    const origins = new HeartbeatOrigins(async () => root);
    let finish!: () => void;
    const completion = new Promise<void>((resolve) => { finish = resolve; });
    const never = new Promise<unknown>(() => {});
    origins.register('parent', 'main', 1, Promise.resolve({ turn: { id: 2 }, completion }));
    origins.register('child', 'parent', 2, Promise.resolve({ turn: { id: 3 }, completion: never }));
    finish();
    await completion;
    await Promise.resolve();
    expect(await origins.resolve('child', 3)).toBe('first');
    root = 'second';
    expect(await origins.resolve('child', 3)).toBeUndefined();
  });

  it('revokes descendants when an intermediate agent is cancelled', async () => {
    const origins = new HeartbeatOrigins(async () => 'root');
    const parent = new AbortController();
    const never = new Promise<unknown>(() => {});
    origins.register('parent', 'main', 1, Promise.resolve({ turn: { id: 2 }, completion: never }), parent.signal);
    origins.register('child', 'parent', 2, Promise.resolve({ turn: { id: 3 }, completion: never }));
    expect(await origins.resolve('child', 3)).toBe('root');
    parent.abort();
    expect(await origins.resolve('parent', 2)).toBeUndefined();
    expect(await origins.resolve('child', 3)).toBeUndefined();
  });

  it('isolates concurrent asynchronous swarm parent contexts', async () => {
    const values = await Promise.all([
      withHeartbeatParent('a', 1, async () => { await Promise.resolve(); return heartbeatParent(); }),
      withHeartbeatParent('b', 2, async () => { await Promise.resolve(); return heartbeatParent(); }),
    ]);
    expect(values).toEqual([{ agent: 'a', turn: 1 }, { agent: 'b', turn: 2 }]);
    expect(heartbeatParent()).toBeUndefined();
  });

  it('isolates sessions and removes only the installed generation', () => {
    const one = new HeartbeatOrigins(async () => 'one');
    const two = new HeartbeatOrigins(async () => 'two');
    installHeartbeatOrigins('session', one);
    installHeartbeatOrigins('session', two);
    removeHeartbeatOrigins('session', one);
    expect(heartbeatOrigins('session')).toBe(two);
    removeHeartbeatOrigins('session', two);
    expect(heartbeatOrigins('session')).toBeUndefined();
  });
});

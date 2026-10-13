// @ts-check
/** Web Locks fake: callbacks sharing a key execute one at a time. */
export function fakeLocks() {
  /** @type {Map<string, Promise<any>>} */
  const pending = new Map();
  return /** @type {Pick<LockManager, 'request'>} */ ({
    /** @param {string} key @param {Function} body */
    request(key, body) {
      const run = (pending.get(key) ?? Promise.resolve()).then(() => body());
      pending.set(key, run.catch(() => {}));
      return run;
    },
  });
}

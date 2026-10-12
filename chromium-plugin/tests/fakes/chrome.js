// @ts-check
/** Minimal fakes of the chrome.* APIs the extension uses; tests drive them synchronously. */

export class FakeEvent {
  constructor() {
    /** @type {Function[]} */
    this.listeners = [];
  }

  /** @param {Function} listener */
  addListener(listener) {
    this.listeners.push(listener);
  }

  /** @param {Function} listener */
  removeListener(listener) {
    this.listeners = this.listeners.filter((it) => it !== listener);
  }

  /** @param {...any} args */
  emit(...args) {
    return this.listeners.map((listener) => listener(...args));
  }
}

export class FakePort {
  /** @param {string} name */
  constructor(name) {
    this.name = name;
    this.onMessage = new FakeEvent();
    this.onDisconnect = new FakeEvent();
    /** @type {any[]} */
    this.posted = [];
    this.isDisconnected = false;
  }

  /** @param {any} message */
  postMessage(message) {
    if (this.isDisconnected) throw new Error('Attempting to use a disconnected port object');
    this.posted.push(structuredClone(message));
  }

  disconnect() {
    this.isDisconnected = true;
  }

  /** Delivers a message from the other end. @param {any} message */
  deliver(message) {
    this.onMessage.emit(structuredClone(message));
  }

  /** Last posted message. */
  last() {
    return this.posted[this.posted.length - 1];
  }
}

/** Manual timers: tests advance time explicitly. */
export class FakeTimers {
  constructor() {
    this.time = 0;
    this.nextId = 1;
    /** @type {Map<number, { at: number, callback: Function }>} */
    this.tasks = new Map();
    this.setTimeout = /** @type {any} */ ((/** @type {Function} */ callback, /** @type {number} */ delay) => {
      const id = this.nextId++;
      this.tasks.set(id, { at: this.time + delay, callback });
      return id;
    });
    this.clearTimeout = /** @type {any} */ ((/** @type {number} */ id) => {
      this.tasks.delete(id);
    });
    this.now = () => this.time;
  }

  /** @param {number} ms */
  advance(ms) {
    const target = this.time + ms;
    for (;;) {
      const due = [...this.tasks.entries()].filter(([, task]) => task.at <= target).sort((a, b) => a[1].at - b[1].at);
      if (due.length === 0) break;
      const [id, task] = due[0];
      this.tasks.delete(id);
      this.time = task.at;
      task.callback();
    }
    this.time = target;
  }
}

/** A chrome object whose `runtime.connectNative` hands out {@link FakePort}s. */
export function fakeChrome() {
  /** @type {FakePort[]} */
  const ports = [];
  const chrome = {
    runtime: {
      /** @type {{ message: string } | undefined} */
      lastError: undefined,
      /** @type {string | null} Error thrown by the next connectNative call. */
      connectFailure: null,
      /** @param {string} name */
      connectNative(name) {
        if (chrome.runtime.connectFailure) throw new Error(chrome.runtime.connectFailure);
        const port = new FakePort(name);
        ports.push(port);
        return port;
      },
    },
  };
  return {
    chrome,
    ports,
    /** Closes the latest port from Chrome's side with an optional lastError. @param {string} [reason] */
    disconnect(reason) {
      const port = ports[ports.length - 1];
      chrome.runtime.lastError = reason ? { message: reason } : undefined;
      port.onDisconnect.emit(port);
      chrome.runtime.lastError = undefined;
    },
  };
}

/** Lets pending promise callbacks run. */
export function flush() {
  return new Promise((resolve) => setImmediate(resolve));
}

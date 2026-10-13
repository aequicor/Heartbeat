// @ts-check
import { isObject } from './protocol.js';

/** Raw bytes of all slices of one set may not exceed the app's frame limit. */
const MAX_BYTES = 16 * 1024 * 1024;
const MAX_CHUNKS = 64;
const MAX_SETS = 4;
const TTL_MS = 30_000;

/**
 * Joins `chunk` frames the app sends for frames above Chrome's 1 MB host message cap.
 * Each chunk carries base64 of a slice of the UTF-8 bytes of the serialized frame; slices are joined
 * before decoding, so a character split between slices is restored. Incomplete sets expire.
 */
export class ChunkAssembler {
  /**
   * @param {{ now?: () => number, ttlMs?: number, maxBytes?: number }} [options]
   */
  constructor(options = {}) {
    this.now = options.now ?? Date.now;
    this.ttlMs = options.ttlMs ?? TTL_MS;
    this.maxBytes = options.maxBytes ?? MAX_BYTES;
    /** @type {Map<string, { count: number, received: number, slices: (Uint8Array | undefined)[], size: number, started: number }>} */
    this.sets = new Map();
  }

  /** Number of incomplete sets. */
  get pending() {
    return this.sets.size;
  }

  /**
   * Adds one chunk; returns the decoded frame once its set is complete.
   * @param {Record<string, any>} chunk
   * @returns {Record<string, any> | null}
   * @throws {Error} when the chunk breaks its set (the set is dropped).
   */
  accept(chunk) {
    this.expire();
    const { of, seq, count, data } = chunk;
    if (typeof of !== 'string' || !Number.isInteger(count) || count < 1 || count > MAX_CHUNKS ||
        !Number.isInteger(seq) || seq < 0 || seq >= count || typeof data !== 'string') {
      throw new Error('Chunk out of range');
    }
    let set = this.sets.get(of);
    if (!set) {
      if (this.sets.size >= MAX_SETS) this.sets.delete(this.sets.keys().next().value ?? '');
      set = { count, received: 0, slices: Array.from({ length: count }, () => undefined), size: 0, started: this.now() };
      this.sets.set(of, set);
    }
    try {
      if (set.count !== count) throw new Error('Chunk count changed');
      if (set.slices[seq]) throw new Error('Chunk repeated');
      const bytes = decodeBase64(data);
      set.size += bytes.length;
      if (set.size > this.maxBytes) throw new Error('Chunked frame too large');
      set.slices[seq] = bytes;
      set.received++;
    } catch (error) {
      this.sets.delete(of);
      throw error;
    }
    if (set.received < set.count) return null;
    this.sets.delete(of);
    const joined = new Uint8Array(set.size);
    let offset = 0;
    for (const slice of set.slices) {
      if (!slice) continue;
      joined.set(slice, offset);
      offset += slice.length;
    }
    const frame = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(joined));
    if (!isObject(frame)) throw new Error('Chunked frame is not an object');
    return frame;
  }

  /** Drops sets older than the time-to-live. */
  expire() {
    const deadline = this.now() - this.ttlMs;
    for (const [id, set] of this.sets) {
      if (set.started < deadline) this.sets.delete(id);
    }
  }
}

/**
 * @param {string} data
 * @returns {Uint8Array}
 */
function decodeBase64(data) {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

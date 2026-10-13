// @ts-check

/**
 * @typedef {{ id: string, type: string, createdAt: number, [key: string]: any }} Entry
 * @typedef {{ reset: boolean, upserts: Entry[], removed?: string[], olderOmitted?: number }} TranscriptChange
 */

/**
 * Applies a `chat.changed` event: a reset replaces everything; otherwise known entries are replaced in place,
 * new ones appended and removed ones dropped. The app guarantees new entries come after all known ones.
 * @param {Entry[]} entries
 * @param {TranscriptChange} change
 * @returns {Entry[]}
 */
export function applyTranscript(entries, change) {
  if (change.reset) return [...change.upserts];
  const removed = new Set(change.removed ?? []);
  const updates = new Map(change.upserts.map((entry) => [entry.id, entry]));
  const next = [];
  for (const entry of entries) {
    if (removed.has(entry.id)) continue;
    const updated = updates.get(entry.id);
    next.push(updated ?? entry);
    updates.delete(entry.id);
  }
  for (const entry of change.upserts) if (updates.has(entry.id)) next.push(entry);
  return next;
}

/**
 * Whether the reply at the end is still streaming, which keeps «Stop» and the typing indicator visible.
 * @param {Entry[]} entries
 */
export function isStreaming(entries) {
  const last = entries[entries.length - 1];
  return Boolean(last && last.type === 'reply' && last.streaming);
}

/**
 * ONE REQUEST FOR READS ASKED AT THE SAME TIME — and nothing kept once it has answered.
 *
 * Sibling panels on one screen often ask for the same row in the same moment: picking a workshop on
 * Workshop oversight used to send `GET /design-workshop-oversight/{id}` three times and the inspector
 * list twice, because the page, the designer panel, the oversight panel and the inspector panel each
 * read it for themselves. `shareInFlight` lets those callers ride one request.
 *
 * NOT A CACHE. The entry is dropped the moment the request settles, so the next read after a save is a
 * fresh read exactly as before — only reads that overlap in time are merged. Each caller gets its own
 * copy of the answer (`structuredClone`), so a panel that edits what it was handed cannot change what
 * its sibling sees.
 */

const pending = new Map<string, Promise<unknown>>();

export function shareInFlight<T>(key: string, start: () => Promise<T>): Promise<T> {
  let shared = pending.get(key) as Promise<T> | undefined;
  if (!shared) {
    shared = start().finally(() => {
      pending.delete(key);
    });
    pending.set(key, shared);
  }
  return shared.then((value) => structuredClone(value));
}

/** How many reads are out right now. FOR TESTS AND FOR NOTHING ELSE. */
export function inFlightCount(): number {
  return pending.size;
}

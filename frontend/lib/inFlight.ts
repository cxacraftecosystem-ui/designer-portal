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
 *
 * A WRITE STARTS A NEW GENERATION. A read sent before a save can still be out when the save answers,
 * and the screen's reload after the save would otherwise ride it and show the row as it was. Every
 * write that changes what these reads answer calls {@link startNewReadGeneration} (and so does
 * sign-out), so a read asked after it never joins one asked before it.
 */

const pending = new Map<string, Promise<unknown>>();
let generation = 0;

export function shareInFlight<T>(key: string, start: () => Promise<T>): Promise<T> {
  const slot = `${generation} ${key}`;
  let shared = pending.get(slot) as Promise<T> | undefined;
  if (!shared) {
    shared = start().finally(() => {
      pending.delete(slot);
    });
    pending.set(slot, shared);
  }
  return shared.then((value) => structuredClone(value));
}

/**
 * Reads asked from now on never ride one asked before. Call it when a write this tab makes answers
 * (either way: a refused save may still have changed something) and on sign-out.
 */
export function startNewReadGeneration(): void {
  generation += 1;
}

/** How many reads are out right now. FOR TESTS AND FOR NOTHING ELSE. */
export function inFlightCount(): number {
  return pending.size;
}

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

export function startNewReadGeneration(): void {
  generation += 1;
}

export function inFlightCount(): number {
  return pending.size;
}

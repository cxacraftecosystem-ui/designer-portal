"use client";

import { createDesignWorkshop } from "@/lib/designWorkshops";
import { createLocalDraft, type DwDraftHeader } from "@/lib/designWorkshopStore";
import { isTransient } from "@/lib/failureTriage";

export type DwCreateHeader = Partial<DwDraftHeader> & { title: string };

export type DwCreateIo = {
  couldReachServer: () => boolean;
  post: (header: DwCreateHeader) => Promise<{ id: string }>;
  keepHere: (header: DwCreateHeader, options?: { createSentAt?: number }) => Promise<{ id: string }>;
  transient: (error: unknown) => boolean;
  now: () => number;
};

export const liveCreate: DwCreateIo = {
  couldReachServer: () => typeof navigator === "undefined" || navigator.onLine !== false,
  post: (header) => createDesignWorkshop(header),
  keepHere: async (header, options) => {
    const draft = await createLocalDraft(header, options);
    return { id: draft.localId };
  },
  transient: isTransient,
  now: Date.now
};

export async function createWorkshopOrKeepItHere(
  header: DwCreateHeader,
  io: DwCreateIo = liveCreate
): Promise<{ id: string }> {
  if (!io.couldReachServer()) return io.keepHere(header);

  const sentAt = io.now();
  try {
    return await io.post(header);
  } catch (err) {
    if (!io.transient(err)) throw err;
    return io.keepHere(header, { createSentAt: sentAt });
  }
}

export async function startLocalDraftHere(header: DwCreateHeader): Promise<{ id: string }> {
  const draft = await createLocalDraft(header, { serverUnreachable: true });
  return { id: draft.localId };
}

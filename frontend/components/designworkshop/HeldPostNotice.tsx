"use client";

import { useEffect, useState } from "react";

import { readHeldWorkshopPosts } from "@/app/(protected)/officers/oversight";
import { useAuth } from "@/components/AuthProvider";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";
import { myWorkshopPosts } from "@/lib/myWorkshopPosts";
import { canAssignWorkshopOversight, heldPostEditRefusal, type WorkshopPost } from "@/lib/permissions";
import type { MediaFile, User } from "@/lib/types";

export type HeldPostRefusal = string | null | undefined;

export function writesHeld(refusal: HeldPostRefusal): boolean {
  return refusal !== null;
}

async function heldPostsOn(workshopId: string, user: User): Promise<WorkshopPost[]> {
  let posts: WorkshopPost[] = [];
  if (!canAssignWorkshopOversight(user)) return posts;
  try {
    const serverId = isLocalWorkshopId(workshopId)
      ? ((await loadDraft(workshopId).catch(() => null))?.remoteId ?? null)
      : workshopId;
    if (serverId) {
      const mine = await myWorkshopPosts(user);
      posts = mine ? (mine.get(serverId) ?? []) : await readHeldWorkshopPosts(serverId, user);
    }
  } catch {
    posts = [];
  }
  return posts;
}

export const HELD_POST_PENDING = "Checking your access…";

export function heldPostReason(refusal: HeldPostRefusal): string | null {
  return refusal === undefined ? HELD_POST_PENDING : refusal;
}

export function useHeldPostRefusal(workshopId: string | null | undefined): HeldPostRefusal {
  const { user } = useAuth();
  const [answer, setAnswer] = useState<{ workshopId: string; userId: string; posts: WorkshopPost[] } | null>(null);
  const asks = Boolean(workshopId && user && canAssignWorkshopOversight(user));

  useEffect(() => {
    if (!workshopId || !user || !canAssignWorkshopOversight(user)) return;
    let cancelled = false;
    void (async () => {
      const posts = await heldPostsOn(workshopId, user);
      if (!cancelled) setAnswer({ workshopId, userId: user.id, posts });
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId, user]);

  if (!asks) return null;
  return answer && answer.workshopId === workshopId && answer.userId === user?.id
    ? heldPostEditRefusal(answer.posts)
    : undefined;
}

export type RecordFilingHold = {
  recordHeld: boolean;
  saveHeld: boolean;
  notice: HeldPostRefusal;
  stored: HeldPostRefusal;
};

export function recordFilingHold(stored: HeldPostRefusal, chosen: HeldPostRefusal, noun: string): RecordFilingHold {
  const recordHeld = writesHeld(stored);
  const saveHeld = recordHeld || writesHeld(chosen);
  const notice: HeldPostRefusal =
    typeof stored === "string"
      ? `This ${noun} belongs to a workshop you oversee, so it can't be changed or deleted. ${stored}`
      : typeof chosen === "string"
        ? `You oversee that workshop, so this ${noun} can't be filed there — choose another. ${chosen}`
        : saveHeld
          ? undefined
          : null;
  return { recordHeld, saveHeld, notice, stored };
}

export function useRecordFilingHold(
  stored: string | null | undefined,
  chosen: string | null | undefined,
  noun: string
): RecordFilingHold {
  const moved = chosen && chosen !== stored ? chosen : null;
  const storedRefusal = useHeldPostRefusal(stored || null);
  const chosenRefusal = useHeldPostRefusal(moved);
  return recordFilingHold(storedRefusal, moved ? chosenRefusal : null, noun);
}

export function mediaWorkshopIds(
  media: Pick<MediaFile, "designWorkshopId" | "linkedRecordType" | "linkedRecordId">
): string[] {
  const ids = new Set<string>();
  const filed = media.designWorkshopId?.trim();
  if (filed) ids.add(filed);
  if ((media.linkedRecordType ?? "").trim().toLowerCase() === "designworkshop") {
    const tagged = media.linkedRecordId?.trim();
    if (tagged) ids.add(tagged);
  }
  return [...ids];
}

export function mediaWriteHold(holds: readonly HeldPostRefusal[]): HeldPostRefusal {
  const refused = holds.find((hold): hold is string => typeof hold === "string");
  if (refused !== undefined) return refused;
  return holds.some((hold) => hold === undefined) ? undefined : null;
}

export const MEDIA_HELD_LABEL = "Read-only to you";

export const MEDIA_HELD_REASON = "Read-only: part of a workshop you oversee.";

export const HELD_POST_PENDING_FILES = "Checking your access…";

export function mediaHeldReason(hold: HeldPostRefusal): string | null {
  if (typeof hold === "string") return MEDIA_HELD_REASON;
  return hold === undefined ? HELD_POST_PENDING : null;
}

export function mediaListNotice(holds: readonly HeldPostRefusal[], acts: string): HeldPostRefusal {
  const sentences = [...new Set(holds.filter((hold): hold is string => typeof hold === "string"))];
  if (sentences.length) {
    return (
      `Files marked “${MEDIA_HELD_LABEL}” belong to a workshop you oversee and can't be ${acts}. ${sentences.join(" ")}`
    );
  }
  return holds.some((hold) => hold === undefined) ? undefined : null;
}

export function mediaUploadHold(chosen: HeldPostRefusal, linked: HeldPostRefusal): HeldPostRefusal {
  if (typeof chosen === "string") {
    return (
      `You oversee that workshop, so these files can't be filed under it — choose another, or none. ${chosen}`
    );
  }
  if (typeof linked === "string") {
    return (
      `That record belongs to a workshop you oversee, so these files can't be attached to it — choose another. ${linked}`
    );
  }
  return chosen === undefined || linked === undefined ? undefined : null;
}

export function attachHold(hold: HeldPostRefusal, subject: string): HeldPostRefusal {
  if (typeof hold !== "string") return hold;
  return (
    `You oversee that workshop, so ${subject} can't be attached to it — choose another, or none. ${hold}`
  );
}

const REVIEW_NOUNS: Readonly<Record<string, string>> = { questionnaire: "interview" };

export function reviewRecordWorkshopIds(recordType: string, record: unknown): string[] {
  const row = (record ?? {}) as Record<string, unknown>;
  const text = (value: unknown): string | null => (typeof value === "string" ? value : null);
  if (recordType.trim().toLowerCase() === "media") {
    return mediaWorkshopIds({
      designWorkshopId: text(row.designWorkshopId),
      linkedRecordType: text(row.linkedRecordType),
      linkedRecordId: text(row.linkedRecordId)
    });
  }
  const filed = text(row.designWorkshopId)?.trim();
  return filed ? [filed] : [];
}

export function reviewEditHold(hold: HeldPostRefusal, recordType: string): HeldPostRefusal {
  if (typeof hold !== "string") return hold;
  const kind = recordType.trim().toLowerCase();
  const subject =
    kind === "media"
      ? "This file belongs to a workshop you oversee"
      : `This ${REVIEW_NOUNS[kind] ?? kind} belongs to a workshop you oversee`;
  return (
    `${subject}, so its fields can't be edited — you can still approve, reject or send it back. ${hold}`
  );
}

export function useHeldPostRefusals(workshopIds: readonly string[]): (rowIds: readonly string[]) => HeldPostRefusal {
  const { user } = useAuth();
  const appoints = Boolean(user && canAssignWorkshopOversight(user));
  const wanted = appoints ? [...new Set(workshopIds.filter(Boolean))].sort().join("\n") : "";
  const [known, setKnown] = useState<{ userId: string; posts: ReadonlyMap<string, WorkshopPost[]> } | null>(null);
  const posts = known && user && known.userId === user.id ? known.posts : null;

  useEffect(() => {
    if (!wanted || !user || !canAssignWorkshopOversight(user)) return;
    const missing = wanted.split("\n").filter((id) => !posts?.has(id));
    if (!missing.length) return;
    let cancelled = false;
    void (async () => {
      const answers = await Promise.all(missing.map(async (id) => [id, await heldPostsOn(id, user)] as const));
      if (cancelled) return;
      setKnown((current) => {
        const merged = new Map(current && current.userId === user.id ? current.posts : []);
        for (const [id, held] of answers) merged.set(id, held);
        return { userId: user.id, posts: merged };
      });
    })();
    return () => {
      cancelled = true;
    };
  }, [wanted, user, posts]);

  return (rowIds) => {
    if (!appoints) return null;
    return mediaWriteHold(
      rowIds.filter(Boolean).map((id) => {
        const held = posts?.get(id);
        return held ? heldPostEditRefusal(held) : undefined;
      })
    );
  };
}

export function HeldPostNotice({
  refusal,
  id,
  className = "mb-4",
  sayPending = false,
  pendingSentence = HELD_POST_PENDING
}: {
  refusal: HeldPostRefusal;
  id?: string;
  className?: string;
  sayPending?: boolean;
  pendingSentence?: string;
}) {
  return (
    <>
      <div id={id} role="status" aria-live="polite" className={refusal ? undefined : "sr-only"}>
        {refusal ? (
          <p
            className={`${className} rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800`}
          >
            {refusal}
          </p>
        ) : null}
      </div>
      {sayPending && refusal === undefined ? (
        <p className={`${className} text-xs leading-5 text-ink-500`}>{pendingSentence}</p>
      ) : null}
    </>
  );
}

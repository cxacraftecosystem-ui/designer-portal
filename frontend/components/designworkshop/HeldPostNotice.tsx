"use client";

/**
 * A WORKSHOP SOMEBODY INSPECTS OR SUPERVISES IS READ-ONLY TO THEM, AND EVERY SCREEN THAT WRITES IT
 * SAYS SO BEFORE THEY TYPE.
 *
 * The owner's ruling of 2026-10-09 lets a Ministry Admin, an admin and the master admin be appointed
 * a workshop's inspector, Assistant Director or Regional Director — and, made precise the same day,
 * takes from whoever holds such a post every write to that workshop's CONTENT or DESIGNER TEAM,
 * through the admin routes as well: its stages, its details and its deletion, its own questions, its
 * AI layers, consent and photographs, its artisan list, and who its designers are — and the records
 * filed under it and the questionnaire forms attached to it ({@link useRecordFilingHold}), and every
 * file it holds: a photograph or recording deleted, a transcript re-run, edited or refined, an identity
 * photograph kept or discarded, a file relinked into it or out of it ({@link useHeldPostRefusals}) —
 * and every door that ADDS to it: a file uploaded into it ({@link mediaUploadHold}), a questionnaire
 * attached to it ({@link attachHold}), a record or file of it edited from the review queue
 * ({@link reviewEditHold}), a failed job of one of its files re-queued. No exception survives for admin
 * rights. The server's refusal is a 403 whose sentence is `design_workshop_posts.write_refusal`. What a
 * post holder KEEPS is every read, appointing OTHER people to posts, restore, and generating the
 * report. What they cannot do either is take THEMSELVES off the post — another administrator has to
 * (`selfReleaseRefusal` in `lib/permissions`, a 409 on the server).
 *
 * The server's 403 is the last word, and every screen shows it word for word when it comes. This
 * module is the courtesy in front of it: the same sentence, built by `heldPostEditRefusal` (held to
 * the server's own words by `e2e/admin-serve-as-unit.spec.ts`), drawn above the controls a screen has
 * switched off — so nobody fills in a form the API will refuse, and nobody is left looking at a
 * greyed button with no reason beside it.
 *
 * ── "NONE KNOWN" IS NOT "NONE" ──────────────────────────────────────────────────────────────────
 *
 * `readHeldWorkshopPosts` asks only for an account that may APPOINT — the two staffing reads behind it
 * are that set's — and answers `[]` for everybody else and on any failure. That is enough for the case
 * this exists for: an administering tier, who reaches every workshop's write controls through the
 * admin arm. An Assistant Director or Regional Director holding a post reaches a write on that
 * workshop only as its creator or through a designer row older than the ruling, and for them the
 * server's sentence on the first save is the whole warning. Nothing here ever claims a post is NOT
 * held; it only ever says that one is.
 *
 * ── WHY A COMPONENT MODULE IMPORTS FROM THE ROUTE TREE ──────────────────────────────────────────
 *
 * `readHeldWorkshopPosts` lives in `app/(protected)/officers/oversight.ts`, whose own header (§6) says
 * it belongs in `lib/` and that moving it there "is a rename and nothing else". Until that rename,
 * this is the one place outside the route tree that reaches in, so every screen that needs the answer
 * asks here rather than each importing the route module itself.
 */

import { useEffect, useState } from "react";

import { readHeldWorkshopPosts } from "@/app/(protected)/officers/oversight";
import { useAuth } from "@/components/AuthProvider";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";
import { canAssignWorkshopOversight, heldPostEditRefusal, type WorkshopPost } from "@/lib/permissions";
import type { MediaFile, User } from "@/lib/types";

/**
 * What a screen knows about the reader's posts on one workshop. THREE STATES, and the middle one is
 * why there are three:
 *
 *  - `undefined` — STILL ASKING. Only ever for an account that may appoint, with a workshop to ask
 *    about. The screen holds its writes exactly as it will if a post turns out to be held: until
 *    2026-10-09 "asking" and "none" were one `null`, so an admin who held a post could type into a
 *    stage while the staffing reads were out — banked into an IndexedDB draft the repository refuses
 *    for ever — and then watch the box disable under the caret with nothing announced.
 *  - `null` — NONE KNOWN: no workshop yet, a reader who cannot appoint (never asked), no post held, or
 *    a read that failed. The server's 403 stays the last word.
 *  - a string — the refusal, in the server's own words.
 */
export type HeldPostRefusal = string | null | undefined;

/** Should a screen hold its writes? While the answer is out, and when a post is held. */
export function writesHeld(refusal: HeldPostRefusal): boolean {
  return refusal !== null;
}

/**
 * The posts the reader holds on one workshop, asked of the server — for an account that may appoint.
 *
 * EVERY PATH ENDS IN AN ANSWER: a screen held while this is out must never be held for good. A
 * `dwlocal-…` address that has synced keeps its local id in the address bar, so it is resolved through
 * the draft to the id the repository knows; one that exists only on this device has no posts on it.
 */
async function heldPostsOn(workshopId: string, user: User): Promise<WorkshopPost[]> {
  let posts: WorkshopPost[] = [];
  try {
    const serverId = isLocalWorkshopId(workshopId)
      ? ((await loadDraft(workshopId).catch(() => null))?.remoteId ?? null)
      : workshopId;
    if (serverId) posts = await readHeldWorkshopPosts(serverId, user);
  } catch {
    posts = [];
  }
  return posts;
}

/** Said where held controls would be while the answer is out. Never into a live region: see below. */
export const HELD_POST_PENDING = "Checking whether you hold a post on this workshop…";

/**
 * The sentence for a component that prints its reason where its buttons would be: the refusal, the
 * pending sentence, or null.
 *
 * A SENTENCE FOR THE PENDING STATE, AND NOT `undefined`, because every such component takes
 * `readOnlyReason?: string | null` with a default of `null` — and a default parameter turns an
 * explicit `undefined` into that `null`, so "still asking" would arrive as "nothing held" and the
 * component would offer the very buttons this exists to hold.
 */
export function heldPostReason(refusal: HeldPostRefusal): string | null {
  return refusal === undefined ? HELD_POST_PENDING : refusal;
}

/**
 * The reader's held-post refusal for one workshop — see {@link HeldPostRefusal} for the three states.
 *
 * @param workshopId the workshop as the screen knows it — the repository's id, or the `dwlocal-…` id
 *   a workshop started on this device keeps in its address after it has synced, which is resolved
 *   through the draft to the id the repository knows. A workshop that exists only on this device has
 *   no posts on it, which is an answer (`null`) and not a wait; nothing is asked while the id is null
 *   (not known yet), and nothing ever for a reader who cannot appoint.
 *
 * An answer is only ever used for the workshop and the account it was asked about: a read still in
 * flight when the id changes is dropped rather than drawn over the next workshop.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * A record filed under a held workshop
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What a RECORD FORM may do, given the design workshops it names.
 *
 * A record filed under a design workshop is that workshop's content (the owner's ruling, made precise
 * on 2026-10-09): whoever inspects or supervises the workshop is refused — any tier, the admin arm
 * included, the same 403 sentence — every edit and deletion of the record, taking it out of the
 * workshop, and filing another into it. A record form names two workshops, and they hold different
 * things:
 *
 *  - the STORED one (an edit): the record is read-only to a holder, so Save and Delete are held, and
 *    so is the workshop picker — moving the record out is refused too (`recordHeld`);
 *  - the CHOSEN one, where it differs: filing the record there is refused, so Save is held — but the
 *    picker stays live, because choosing another workshop is the way forward (`saveHeld`).
 *
 * Both hold while their answer is still out, as every other write screen does ({@link HeldPostRefusal}).
 */
export type RecordFilingHold = {
  /** Save, Delete and the workshop picker. */
  recordHeld: boolean;
  /** Save — the record's own hold, or a chosen workshop that cannot take it. */
  saveHeld: boolean;
  /** The three states again, for {@link HeldPostNotice}: the refusal with this form's context, or pending, or none. */
  notice: HeldPostRefusal;
  /**
   * The STORED workshop's own answer, three states — what the files attached to the record are held
   * by, since a file that hangs off a record filed under a workshop is that workshop's content too
   * (`ExistingMedia`'s `recordHold`). Never the chosen workshop's: nothing is filed there until a save.
   */
  stored: HeldPostRefusal;
};

/** The pure half of {@link useRecordFilingHold}, for the spec. `noun` names the record ("artisan"). */
export function recordFilingHold(stored: HeldPostRefusal, chosen: HeldPostRefusal, noun: string): RecordFilingHold {
  const recordHeld = writesHeld(stored);
  const saveHeld = recordHeld || writesHeld(chosen);
  const notice: HeldPostRefusal =
    typeof stored === "string"
      ? `This ${noun} is filed under a design workshop you hold a post on, so it cannot be changed or deleted here. ${stored}`
      : typeof chosen === "string"
        ? `The workshop chosen for this ${noun} is one you hold a post on, so it cannot be filed there — choose another. ${chosen}`
        : saveHeld
          ? undefined
          : null;
  return { recordHeld, saveHeld, notice, stored };
}

/**
 * {@link RecordFilingHold} for one form: `stored` is the record's saved `designWorkshopId` (null on a
 * create), `chosen` the picker's current answer. The same workshop is asked once — re-asking an
 * unchanged choice would spend two more requests on an answer already in hand.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * A file a held workshop holds
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The design workshops a stored file names ITSELF.
 *
 * The server finds a file's workshops five ways (`design_workshop_posts.media_design_workshop_ids`)
 * and refuses a post holder every write to it — delete, the transcript's edit, refine and
 * transcribe-now, the identity photograph's decision, a relink — if they hold a post on ANY of them.
 * Two of the five are on the row, and these are they: the workshop a designer FILED it under
 * (`designWorkshopId`), and the `designWorkshop` link tag every stage capture carries (compared as
 * loosely as the server's `tagged_workshop_id` compares it). The other three — a stage entry that
 * names it, an AI layer made from it, the record it hangs off — each cost a read per file a list
 * cannot afford; a screen that already knows the record's workshop hands that hold in beside these
 * (`ExistingMedia`'s `recordHold`), and anything else is left to the server's 403, shown verbatim.
 */
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

/**
 * One file's hold, from the answers for every workshop it belongs to — the server's rule in the three
 * states: refused if ANY of them is held (it asks each in turn), still asking while any answer is out,
 * none known only when every one came back none. A file that names no workshop is none known.
 */
export function mediaWriteHold(holds: readonly HeldPostRefusal[]): HeldPostRefusal {
  const refused = holds.find((hold): hold is string => typeof hold === "string");
  if (refused !== undefined) return refused;
  return holds.some((hold) => hold === undefined) ? undefined : null;
}

/** Said ON a held file or row — a word, so the hold is never colour or a greyed button alone. */
export const MEDIA_HELD_LABEL = "Read-only to you";

/** Said in place of a held file's own control text; the sentence why is in the list's notice. */
export const MEDIA_HELD_REASON = "Read-only to you: this file is part of a design workshop you hold a post on.";

/** {@link HELD_POST_PENDING}, for a list whose files belong to several workshops. */
export const HELD_POST_PENDING_FILES = "Checking whether you hold a post on the workshops these files belong to…";

/** What a held file's control says where its own text would be: the reason, pending, or nothing. */
export function mediaHeldReason(hold: HeldPostRefusal): string | null {
  if (typeof hold === "string") return MEDIA_HELD_REASON;
  return hold === undefined ? HELD_POST_PENDING : null;
}

/**
 * What a LIST of files says above itself, given each file's hold — three states, for
 * {@link HeldPostNotice}. `acts` names what the list's controls do ("deleted or re-transcribed").
 *
 * ONE NOTICE FOR THE LIST AND A WORD ON EACH ROW, rather than the sentence on every row: a page of
 * twenty photographs from the one workshop somebody inspects would otherwise be twenty copies of a
 * three-line paragraph. Each distinct sentence is said once — the server's own words, which name the
 * post — so a reader holding different posts on two workshops reads both.
 */
export function mediaListNotice(holds: readonly HeldPostRefusal[], acts: string): HeldPostRefusal {
  const sentences = [...new Set(holds.filter((hold): hold is string => typeof hold === "string"))];
  if (sentences.length) {
    return (
      `Files marked “${MEDIA_HELD_LABEL}” are part of a design workshop you hold a post on, so they cannot be ${acts} ` +
      `here. ${sentences.join(" ")}`
    );
  }
  return holds.some((hold) => hold === undefined) ? undefined : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Doors that ADD to a held workshop, and the review queue's edit
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the /media upload says when the files it is about to store would land in a held workshop —
 * three states, the chosen design workshop's answer before the linked record's.
 *
 * `POST /media/complete` refuses a post holder a NEW file into a workshop (2026-10-09) — filed under
 * it, tagged to it, or attached to a record filed under it — and refuses it AFTER the bytes are PUT,
 * so without this a holder uploads three photographs and is refused three times. `chosen` is the
 * answer for the design workshop picked in the form; `linked` is the answer for what the files hang
 * off — the record picked as their parent, read the way the server reads it (`link_filing`). Both
 * pickers stay live, because choosing another is the way forward; only Upload is held.
 */
export function mediaUploadHold(chosen: HeldPostRefusal, linked: HeldPostRefusal): HeldPostRefusal {
  if (typeof chosen === "string") {
    return (
      "The design workshop chosen for these files is one you hold a post on, so they cannot be filed under it — " +
      `choose another, or none. ${chosen}`
    );
  }
  if (typeof linked === "string") {
    return (
      "The record chosen for these files is filed under a design workshop you hold a post on, so they cannot be " +
      `attached to it — choose another. ${linked}`
    );
  }
  return chosen === undefined || linked === undefined ? undefined : null;
}

/**
 * What a form that ATTACHES something new to a design workshop says when the workshop chosen is held —
 * the questionnaire create form, its upload door and its reuse dialog. Three states; `subject` names
 * what would be attached ("this questionnaire", "the copy").
 *
 * Attaching changes what the workshop's report SAYS — the server asks `_require_attachable_workshop`,
 * which is `load_workshop_or_404(for_edit=True)`, the stage save's own gate — so a post holder is
 * refused with the 403 naming the post. The picker stays live: another workshop, or none, is the way
 * forward, and the sentence says so.
 */
export function attachHold(hold: HeldPostRefusal, subject: string): HeldPostRefusal {
  if (typeof hold !== "string") return hold;
  return (
    `The design workshop chosen for ${subject} is one you hold a post on, so ${subject} cannot be attached to it — ` +
    `choose another, or leave it unattached. ${hold}`
  );
}

/** How the review queue names a record type in a sentence, where its token is not the word. */
const REVIEW_NOUNS: Readonly<Record<string, string>> = { questionnaire: "interview" };

/**
 * The design workshops ONE record or file opened from the review queue names itself — what its edit
 * is held by, read the way `review.edit_reviewed_record` reads it: a file through
 * {@link mediaWorkshopIds}, every other record type by its `designWorkshopId` (a crafts workshop has
 * none, and names nothing). `record` is the record's own GET, which the editor reads anyway.
 */
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

/**
 * What the review queue's EDIT panel says over a record or file a held workshop owns — three states.
 *
 * `POST /review/{type}/{id}/edit` writes the record's own fields, and the server asks it the record
 * forms' gate (`assert_may_write_a_record_filed_under`, or `refuse_a_holders_media_write` for a file),
 * so a post holder's Save and "Save and approve" are refused exactly as on the record's own screen.
 * APPROVE, REJECT AND SEND FOR REVISION STAY — moderation, not authorship — and the sentence says so,
 * because a reviewer who met a held Save with nothing beside it would conclude the whole row was
 * closed to them.
 */
export function reviewEditHold(hold: HeldPostRefusal, recordType: string): HeldPostRefusal {
  if (typeof hold !== "string") return hold;
  const kind = recordType.trim().toLowerCase();
  const subject =
    kind === "media"
      ? "This file is part of a design workshop you hold a post on"
      : `This ${REVIEW_NOUNS[kind] ?? kind} is filed under a design workshop you hold a post on`;
  return (
    `${subject}, so its fields cannot be edited here — approving it, rejecting it or sending it back for ` +
    `revision is still yours. ${hold}`
  );
}

/**
 * {@link useHeldPostRefusal} for a LIST whose rows each belong to workshops of their own — the media
 * screens, where every file names its own ({@link mediaWorkshopIds}). Hand it every workshop the rows
 * name; it answers with a reader for one row's workshops, in the three states ({@link mediaWriteHold}).
 *
 * ONE ANSWER PER DISTINCT WORKSHOP, asked once however many rows name it and kept while the reader is
 * the same account — so twenty photographs of one workshop cost one staffing read, and the transcript
 * poll or a page turned back costs nothing. Nothing is asked for a reader who cannot appoint: the
 * answer is then none known for every row, and the server's 403 is the last word, as it is for them
 * everywhere.
 */
export function useHeldPostRefusals(workshopIds: readonly string[]): (rowIds: readonly string[]) => HeldPostRefusal {
  const { user } = useAuth();
  const appoints = Boolean(user && canAssignWorkshopOversight(user));
  // A stable key for the set, so a re-render with the same rows in another order asks nothing.
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

/**
 * The refusal, drawn — into a live region that is MOUNTED BEFORE IT HAS ANYTHING TO SAY.
 *
 * AMBER AND NOT RED, because nothing has failed: the reader is looking at a workshop they may read,
 * and the sentence says why the writing is somebody else's. The same panel Workshop oversight draws
 * for the same sentence, so the two screens look like one rule. Worded, not merely tinted — colour
 * alone is a signal some readers never get.
 *
 * `role="status"` ON A WRAPPER THAT IS ALWAYS THERE (the /users pattern). The answer arrives a round
 * trip after the page, and a region created together with its first sentence announces nothing, so
 * the notice used to appear in silence while every control went inert. Polite, because it describes
 * standing state rather than the outcome of an act. `id` is for the Save buttons it explains, which
 * name it with `aria-describedby` while a refusal is shown. `sr-only` WHILE EMPTY, as the stage
 * page's save regions are: an empty block is still a grid item, and on a `gap-4` form it would add a
 * gap above Save for every reader who holds nothing — never `hidden`, which leaves the tree.
 *
 * `sayPending` prints {@link HELD_POST_PENDING} BELOW the region, never inside it, on a screen where
 * no held control says it in place — so the reason controls are inert is visible while the answer is
 * out, without every appointer hearing "checking" announced on every workshop screen they open. A
 * list of files from several workshops passes `pendingSentence` ({@link HELD_POST_PENDING_FILES}),
 * because "this workshop" names nothing there.
 */
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

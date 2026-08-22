/**
 * Reading one rateable entity's stage rows — from this device first, then from the repository.
 *
 * ── WHY BOTH SURFACES SHARE THIS AND DO NOT EACH HAVE THEIR OWN COPY ────────────────────────────
 *
 * The UPLOAD tab and the REVIEW tab want the same three things about `sketch` or `prototype`: which
 * stage declares it, what rows this device is holding, and — where the caller is entitled to it —
 * the repository's own copy folded in. That sequence has four decisions in it that are easy to get
 * subtly different in two places:
 *
 * 1. **The stage is looked up in the registry, never hardcoded.** Sketches are stage 11 and
 *    prototypes are stage 13 today, and `SKETCH_REVIEW` beside them is marked `optional_stage` with
 *    the source document proposing it be dropped — so the numbering is not a constant to lean on.
 * 2. **The disk answer comes first and is handed over as soon as it exists** (`onLocal`), because
 *    every design-workshop page in this app renders from IndexedDB before the network is asked.
 *    Waiting for the server here would make the offline case indistinguishable from a slow one.
 * 3. **The server's copy is folded in through `adoptServerStage`**, which refuses to overwrite a
 *    stage edited locally since its last push. Anything that writes rows BACK — an attachment, a
 *    reorder — must be writing over rows this browser has actually read, and this is the only
 *    helper that can say whether that happened (`reconciled`).
 * 4. **A failure at any step is not fatal and is not silent.** No registry, a refused local store
 *    and an unreachable server are three different states, and each leaves the caller with the best
 *    answer available plus enough to say which one it got.
 *
 * NO ROW IS EVER WRITTEN HERE. A caller that has changed rows writes them with `putDraftStage`
 * itself, so the read path cannot accidentally become a second, quieter save path.
 *
 * IT DOES, HOWEVER, SEED A DRAFT RECORD ON THE SURFACES THAT WILL WRITE — and only those. This
 * paragraph used to say "nothing here writes" flatly, which was wrong in a way that reached a
 * stranger's screen: `ensureDraft` is a check-AND-CREATE in one transaction (see its own header),
 * so calling it unconditionally minted an empty local draft for whatever workshop id was in the URL.
 * On the pool surface — where `fromServer` is false, the reader is refused this workshop, and there
 * is no reason to touch the store at all — that put a blank, session-owned draft in IndexedDB for
 * somebody else's workshop, and `design-workshops/page.tsx` prepends exactly such drafts to the
 * device's own workshop list whenever it is offline (`if (draft.remoteId === null || offline)`). A
 * stranger's workshop appeared as a blank row on a designer's list because they opened a review
 * page. So the seed is now tied to `fromServer`, which is the same question asked once: a caller
 * that may not read the repository's copy of this stage is a caller that will never write one back.
 */

import { getDesignWorkshopStage, type DwRegistry, type DwRow, type DwStage } from "@/lib/designWorkshops";
import { adoptServerStage, ensureDraft, loadDraft, loadRegistry } from "@/lib/designWorkshopStore";

import { stageKeyForEntity } from "./reviewRanking";

export type StageRows = {
  /** The stage that declares this entity, or null when this browser holds no registry. */
  stageKey: string | null;
  spec: DwStage | null;
  /** The rows this device is holding, newest reconciliation first. */
  rows: DwRow[];
  /** The draft these rows came out of, for the writes a caller makes afterwards. */
  draftId: string | null;
  /** True only when the repository's copy of this stage was read and folded in on this pass. */
  reconciled: boolean;
};

/**
 * The registry, from memory then IndexedDB then the network — or null, which is a state and not an
 * error. A browser that has never opened a workshop with a connection genuinely does not know what
 * fields exist, and the surfaces above say so rather than guessing at a stage key.
 */
export async function readRegistry(): Promise<DwRegistry | null> {
  try {
    return (await loadRegistry()).registry;
  } catch {
    return null;
  }
}

export async function readStageRows(
  workshopId: string,
  registry: DwRegistry | null,
  entityKey: string,
  options: {
    /**
     * Whether to ask the repository for its copy of the stage.
     *
     * False on the pool surface, whose reader is refused the workshop by `load_workshop_or_404` —
     * asking would spend a request to be told 404 and would put a refusal in the console on every
     * load of a page that is working correctly.
     *
     * IT ALSO DECIDES WHETHER A LOCAL DRAFT MAY BE SEEDED, for the reason in this file's header: a
     * surface that may not read the repository's copy will never write rows back either, so it has
     * no business creating a draft record for a workshop it cannot open.
     */
    fromServer: boolean;
    /** Called with the disk answer the moment it exists, before the repository is asked. */
    onLocal?: (rows: DwRow[], draftId: string | null) => void;
  }
): Promise<StageRows> {
  const stageKey = registry ? stageKeyForEntity(registry, entityKey) : null;
  const spec = registry && stageKey ? (registry.stages.find((stage) => stage.key === stageKey) ?? null) : null;
  if (!stageKey || !spec) return { stageKey, spec, rows: [], draftId: null, reconciled: false };

  let draftId: string | null = null;
  let rows: DwRow[] = [];
  try {
    /*
      `ensureDraft` ONLY WHERE A DRAFT IS WANTED; `loadDraft` — which creates nothing — everywhere
      else. See the header: the create half of `ensureDraft` is what put a stranger's workshop on a
      designer's own offline list. A null from `loadDraft` is an ordinary state on the pool surface
      and the caller renders from the ranking response alone.
    */
    const draft = options.fromServer ? await ensureDraft(workshopId) : await loadDraft(workshopId);
    draftId = draft?.localId ?? null;
    rows = draft?.stages[stageKey]?.collections[entityKey] ?? [];
  } catch {
    // A refused local store — private mode, a full disk. The repository read below may still
    // answer, and the caller's own write path is what has to refuse; a read must not.
    draftId = null;
  }
  options.onLocal?.(rows, draftId);

  if (!options.fromServer || !draftId) return { stageKey, spec, rows, draftId, reconciled: false };

  try {
    const data = await getDesignWorkshopStage(workshopId, stageKey);
    const merged = await adoptServerStage(draftId, spec, data);
    const fresh = merged?.stages[stageKey]?.collections[entityKey];
    if (fresh) return { stageKey, spec, rows: fresh, draftId, reconciled: true };
    return { stageKey, spec, rows, draftId, reconciled: true };
  } catch {
    // No signal, or a stage this caller may not read. The disk answer stands and `reconciled`
    // stays false, which is what stops a caller writing rows back over a copy it never read.
    return { stageKey, spec, rows, draftId, reconciled: false };
  }
}

/** What a row is called on a picker: its own name, then its identifier, then an honest placeholder. */
export function rowLabel(row: DwRow, index: number): string {
  for (const key of ["name", "sketchNo", "prototypeCode"]) {
    const value = row[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return `Untitled ${index + 1}`;
}

/** The stable key a picker addresses a row by — the same one `putDraftStage` writes against. */
export function rowKeyOf(row: DwRow): string | null {
  return row._clientKey ?? row._entryId ?? null;
}

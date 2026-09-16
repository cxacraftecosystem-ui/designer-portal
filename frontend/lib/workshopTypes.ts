/**
 * The "Type of workshop" vocabulary — the FIRST of the two dropdowns on every record form, and the
 * flag that decides where the SECOND one's answer is saved.
 *
 * ── THE NAME. IT IS `WorkshopTypeOption`, AND `WorkshopType` IS ALREADY SOMETHING ELSE ────────────
 *
 * `frontend/lib/types.ts` already exports `type WorkshopType = "DESIGN_PROTOTYPE" | "OTHER"`. That
 * is the two-member legacy enum on a `Workshop` ROW — it says whether one particular legacy workshop
 * was a design & prototype visit — and it is not this. This file's rows are the ADMINISTRATOR'S LIST
 * of the types a form may offer, and the picker in the record form holds BOTH meanings at the same
 * time, so a shared spelling would mean an import alias at every call site that touches either.
 *
 * The backend could not use the obvious name even if the client could: `WorkshopType` is a real
 * Postgres enum type on this database, and Postgres keeps enums and tables in one type namespace, so
 * `CREATE TABLE "WorkshopType"` answers `ERROR: type "WorkshopType" already exists`. See
 * `model WorkshopTypeOption` in `backend/prisma/schema.prisma`, which records the verification.
 *
 * ── WHAT A ROW IS FOR, AND THE ONE FIELD THAT MATTERS MOST ───────────────────────────────────────
 *
 * Two dropdowns, never three:
 *
 *     "Type of workshop"  → this list
 *     "Workshop"          → the workshops OF THAT TYPE, most recent first
 *
 * {@link WorkshopTypeOption.routesToDesignWorkshop} is what makes the second one possible without a
 * third control. TRUE means the workshop list comes from `DesignWorkshop` and the chosen row is
 * saved to the record's `designWorkshopId`; FALSE means it comes from `Workshop` and the chosen row
 * is saved to `workshopId`. One picker, two destinations, decided by the type.
 *
 * **THE TYPE ITSELF IS NOT SAVED ON THE RECORD.** The workshop the researcher picked already knows
 * its own type, so storing the type beside it would be a second copy that can disagree with the
 * first — and nothing would ever read them together to notice. The control is a NARROWING CONTROL
 * and nothing more — which is exactly what the retired `DesignWorkshopCascade` said of the filter
 * this replaces (that file was deleted on 2026-09-16; its hint read "Narrows the workshops below.
 * It is not saved on this record.").
 *
 * ── THIS IS NOT `stage_schema.ENUMS["WORKSHOP_KIND"]`, THOUGH THE KEYS ARE SEEDED FROM IT ────────
 *
 * `WORKSHOP_KIND` answers "what kind of design workshop is THIS one?" — a question inside stage 1 of
 * a design workshop's own questionnaire, answered by the designer running it. This list answers
 * "which lists may a record form offer?" — a question about the shape of a form, on six screens that
 * have no stage 1 at all. The six rows are SEEDED with the registry's six keys so that every
 * `DesignWorkshop.workshopKind` value already stored resolves to a label, and nothing synchronises
 * them after that. Do not derive one from the other in this client.
 */

import { apiFetch, buildQuery } from "@/lib/api";

/**
 * The key of the one seeded type that routes at `DesignWorkshop`.
 *
 * **READ IT, DO NOT COMPARE AGAINST IT.** This constant exists so a message can name the type in
 * words and so a test can assert the seed — it is NOT how a caller decides where to save. That
 * decision is {@link WorkshopTypeOption.routesToDesignWorkshop}, per row, because an administrator
 * may add a second design-workshop-backed programme the day the ministry announces one and no
 * client should have to ship to learn about it. A `key === DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY` test
 * in a picker is the bug this sentence exists to prevent.
 */
export const DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY = "DESIGN_PROTOTYPE_DEVELOPMENT";

/** One option in the "Type of workshop" dropdown, as `GET /workshop-types` serves it. */
export type WorkshopTypeOption = {
  id: string;
  /**
   * The stable token — `DESIGN_PROTOTYPE_DEVELOPMENT`, `SKILL_UPGRADATION`, … — and the value
   * `DesignWorkshop.workshopKind` and `AnnualPlanEntry.workshopKind` already store.
   *
   * **PERMANENT.** `PATCH /workshop-types/{id}` does not accept this field; the API answers 422 if a
   * client sends it. Editing a label is the common case and must never be able to move the token
   * that four other places already hold.
   */
  key: string;
  /** What a human reads in the dropdown. This is the field an administrator edits. */
  label: string;
  /** Low first. Not unique — `key` breaks the tie, which is what makes the order total. */
  sortOrder: number;
  /** False = retired: absent from every picker, with every workshop filed under it untouched. */
  isActive: boolean;
  /**
   * TRUE  → the "Workshop" dropdown is filled from `DesignWorkshop`, saved to `designWorkshopId`.
   * FALSE → it is filled from `Workshop`, saved to `workshopId`.
   *
   * Exactly one seeded row carries it, and no database constraint enforces "exactly one" — see the
   * Prisma model for why making that a constraint would make announcing a second programme a
   * migration on every deployment.
   */
  routesToDesignWorkshop: boolean;
  createdAt: string | null;
  updatedAt: string | null;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Reading
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every type a form may offer, in the administrator's order.
 *
 * `includeInactive` IS FOR THE ADMIN SCREEN AND FOR NOTHING ELSE. A picker must never pass it: a
 * retired type absent from the dropdown is the entire meaning of retiring one. The list is not
 * paginated — there are six rows and the screen that manages them wants all of them — so there is no
 * truncation to state and no cap to print.
 */
export async function listWorkshopTypes(options?: { includeInactive?: boolean }) {
  const query = buildQuery({ includeInactive: options?.includeInactive ? "true" : undefined });
  return apiFetch<WorkshopTypeOption[]>(`/workshop-types${query}`);
}

/**
 * The server's own order, re-applied in the browser.
 *
 * Every consumer of a list in this client re-sorts rather than trusting the response order, and the
 * reason here is the ordinary one plus a specific one: `sortOrder` is deliberately NOT unique, so
 * two rows can share a position, and a caller that merged two responses (an admin screen that just
 * created a row, say) would otherwise render an order that depends on which array came first.
 * `key` breaks the tie exactly as the API's `ORDER BY "sortOrder", "key"` does.
 */
export function sortWorkshopTypes(types: WorkshopTypeOption[]): WorkshopTypeOption[] {
  return [...types].sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key));
}

/**
 * The type whose workshops live in `DesignWorkshop`, or null when the list holds none.
 *
 * READS THE FLAG, NEVER THE KEY — see {@link DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY}. Returns the FIRST
 * such type in the administrator's order rather than asserting there is one: nothing in the database
 * enforces "exactly one", so a caller that indexed `[0]` of a filter would be making a claim this
 * client cannot check, and a caller that threw would take out every record form the moment an
 * administrator added a second one.
 *
 * NULL IS A REAL ANSWER AND MUST BE HANDLED. An administrator can deactivate the design & prototype
 * type, and then a picker asking this question gets nothing back — which is correct, and means the
 * form offers only the ordinary-`Workshop` types until somebody turns it back on.
 */
export function designWorkshopTypeOf(types: WorkshopTypeOption[]): WorkshopTypeOption | null {
  return sortWorkshopTypes(types).find((type) => type.routesToDesignWorkshop) ?? null;
}

/**
 * Which type a form should open on.
 *
 * `preferDesignWorkshops` IS A CAPABILITY THE CALLER PASSES, NOT A ROLE THIS FILE READS. The rule is
 * "a designer opens on Design & Prototype", and the predicate for that lives in `lib/permissions.ts`
 * where every other one does; this file would otherwise be a second place that decides what a
 * designer is, and the two would disagree the first time the ladder moved. Pass
 * `canRunDesignWorkshops(user)`.
 *
 * FALLS BACK TO THE FIRST TYPE IN THE LIST rather than to null whenever it can, because the control
 * is not optional — a form that opened with no type selected would show an empty "Workshop"
 * dropdown, which reads as "there are no workshops" rather than as "choose a type first". Null comes
 * back only when there are no types at all, which is a state the seed makes unreachable except on a
 * database where an administrator has deactivated every one of them.
 */
export function defaultWorkshopType(
  types: WorkshopTypeOption[],
  options?: { preferDesignWorkshops?: boolean }
): WorkshopTypeOption | null {
  const ordered = sortWorkshopTypes(types.filter((type) => type.isActive));
  if (options?.preferDesignWorkshops) {
    const design = ordered.find((type) => type.routesToDesignWorkshop);
    if (design) return design;
  }
  return ordered[0] ?? null;
}

/**
 * The label for a stored token, for a screen that has a `workshopKind` and needs a word for it.
 *
 * FALLS BACK TO THE RAW TOKEN, NEVER TO "Unknown" AND NEVER TO AN EMPTY STRING. A workshop filed
 * under a type an administrator has since deleted — or one written by a client a release ahead — is
 * still a workshop with a type, and printing the token is the honest rendering of "this is what it
 * says". `enum_label` on the server falls back the same way and for the same reason.
 */
export function workshopTypeLabel(types: WorkshopTypeOption[], key: string | null | undefined) {
  if (!key) return null;
  return types.find((type) => type.key === key)?.label ?? key;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Writing — ADMIN ONLY. Every one of these is `require_admin` on the server.
 * ──────────────────────────────────────────────────────────────────────────── */

/** `POST /workshop-types`. 409 when the key is taken — keys are permanent, so there is no retry. */
export async function createWorkshopType(body: {
  key: string;
  label: string;
  sortOrder?: number;
  isActive?: boolean;
  routesToDesignWorkshop?: boolean;
}) {
  return apiFetch<WorkshopTypeOption>("/workshop-types", { method: "POST", body: JSON.stringify(body) });
}

/**
 * `PATCH /workshop-types/{id}` — **send only what changed.**
 *
 * The server applies this with `exclude_unset`, so an absent key keeps the stored value. There is no
 * `key` in the body type and there must not be one: the API forbids extras and answers 422, which is
 * the guarantee that a label edit can never move a token four other tables store.
 */
export async function updateWorkshopType(
  id: string,
  body: { label?: string; sortOrder?: number; isActive?: boolean; routesToDesignWorkshop?: boolean }
) {
  return apiFetch<WorkshopTypeOption>(`/workshop-types/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

/**
 * `PATCH /workshop-types/reorder` — the whole order in one request, as a list of ids.
 *
 * ONE REQUEST AND NOT N PATCHES, because a reorder expressed as six saves is six chances to be
 * interrupted halfway and leave the list in an order nobody chose. The position IS the index in the
 * array, so a client cannot send an order that contradicts itself. Returns the reordered list.
 */
export async function reorderWorkshopTypes(ids: string[]) {
  return apiFetch<WorkshopTypeOption[]>("/workshop-types/reorder", {
    method: "PATCH",
    body: JSON.stringify({ ids })
  });
}

/**
 * `DELETE /workshop-types/{id}` — **and it is refused, with a count, when anything is filed under
 * the type.**
 *
 * 204 when nothing uses it. 409 otherwise, and the `detail` is a finished sentence naming how many
 * workshops stand in the way and telling the administrator to deactivate instead. RENDER THAT
 * SENTENCE VERBATIM: it is the only thing on screen that says what to do next, and a caller that
 * replaced it with "Unable to delete" would leave an administrator trying again tomorrow.
 *
 * The refusal exists because there is no foreign key onto the type table — a record stores the
 * WORKSHOP, never the type — so Postgres cannot make this refusal for us, and a delete that went
 * through would leave every workshop under that key rendering a type nothing can resolve.
 */
export async function deleteWorkshopType(id: string) {
  await apiFetch<void>(`/workshop-types/${id}`, { method: "DELETE" });
}

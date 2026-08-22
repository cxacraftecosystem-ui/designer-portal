/**
 * The ranking rules of the Sketches & Prototypes review tab, as pure functions over plain data.
 *
 * ── WHY A SEPARATE MODULE AND NOT LOGIC INSIDE THE CARD LIST ────────────────────────────────────
 *
 * The one rule this feature has that is easy to state and easy to get wrong is the owner's: the
 * list is "sorted by the quantitative data by default", and the designer "has the final say".
 * Those are two orders that have to be told apart on screen and never silently swapped for one
 * another — and "told apart" is a comparison between arrays, not a rendering concern. Everything
 * that decides WHICH order is being shown, and whether a designer has taken responsibility for it,
 * lives here so a spec can call it with no browser, no server and no IndexedDB.
 *
 * ── THE TWO ORDERS, AND WHERE EACH COMES FROM ───────────────────────────────────────────────────
 *
 * The server sends both on every row of `GET /design-ratings/rounds/{round}` and it sends them
 * together deliberately (see `ranked_payload` in `backend/app/services/design_ratings.py`):
 *
 *   - `defaultPosition` — what the ratings say. Derived from the average, then the sample size,
 *     then the placed order, then the id; unrated pieces sort LAST because "nobody has got to it
 *     yet" is not the same as "it scored zero".
 *   - `placedPosition` — what the designers say. It is `DwStageEntry.ordinal`, the same number the
 *     stage form's up/down arrows write and the same number the handset writes, which is why this
 *     feature added no second ranking mechanism.
 *
 * There is a THIRD source of the designers' order and it outranks the second: the local draft's own
 * row array, which is where a reorder lands the moment it is made and where it stays until a sync is
 * accepted. `placedPosition` is the server's opinion, and between a deferred sync and the next
 * successful push the server's opinion is stale. See {@link heldOrder}.
 *
 * ── THE OVERRIDE STAMP, AND THE ONE THING THE WIRE DOES NOT CARRY ───────────────────────────────
 *
 * `sketch.rankFixedBy` / `rankFixedAt` (and the identical pair on `prototype`) are the registry's
 * answer to "is this order deliberate?" — see the long note beside them in `stage_definitions.py`.
 * Blank means the default sort still stands; filled means somebody took responsibility for this
 * arrangement over the computed score, and from that moment a later score change must not re-sort
 * the list under them.
 *
 * **THEY ARE NOT ON THE RANKING RESPONSE.** `ranked_payload` sends the label, the score, the two
 * positions and this caller's own rating, and nothing out of the row's `data`. So the stamp can
 * only be read where the stage rows themselves are readable — the workshop's own review tab, which
 * holds them in the local draft anyway. The pool round has no way to know, and the pool surface
 * says so in words rather than guessing; a guess there would be the exact failure the stamp exists
 * to prevent, made in the other direction. {@link fixedOrderStamp} takes ROWS, never ranked items,
 * so that limit is visible in the type rather than buried in a component.
 *
 * ── WHY THE ORDERS ARE ARRAYS OF IDS AND NOT SORTED ITEM ARRAYS ─────────────────────────────────
 *
 * A working order survives a refresh of the scores: the ranked rows arrive again with new averages
 * and the arrangement on screen must not move. Keeping the order as ids means a re-fetch replaces
 * the DATA and leaves the ORDER alone, and the two can be compared for equality without caring
 * what changed inside a row. Every function here is total on missing ids — an id that is no longer
 * in the list is dropped, and a piece that appears for the first time is appended — because a
 * reorder made offline on Monday must still make sense against Friday's list.
 */

import type { DwEntryData, DwRegistry, DwRow, DwValue } from "@/lib/designWorkshops";

/* ────────────────────────────────────────────────────────────────────────────
 * The wire, as the two rating routes actually shape it
 * ──────────────────────────────────────────────────────────────────────────── */

/** The two rounds the server accepts. Mirrors `design_ratings.RatingRound`. */
export type RatingRound = "PEER" | "POOL";

/**
 * The two entities that are ranked, mirroring `design_ratings.RATEABLE_ENTITIES`.
 *
 * The child rows of a prototype — its stage logs, its material usage — are parts of one piece and
 * not things a designer ranks against each other, which is why the server 422s them by name.
 */
export const RATEABLE_ENTITY_KEYS = ["sketch", "prototype"] as const;
export type RateableEntityKey = (typeof RATEABLE_ENTITY_KEYS)[number];

export function isRateableEntityKey(value: string): value is RateableEntityKey {
  return (RATEABLE_ENTITY_KEYS as readonly string[]).includes(value);
}

/**
 * One row of the ledger as `rating_payload` writes it.
 *
 * `reviewerId` IS OPTIONAL IN THE TYPE BECAUSE IT IS OPTIONAL ON THE WIRE, and that is the whole
 * of the identity rule reaching the client: the server omits the key entirely for a caller who may
 * not have it rather than sending it empty, so no screen can render a name that was never sent.
 * Never widen this to `string` "for convenience" — the compiler is the only thing that keeps a
 * later card from printing `rating.reviewerId` unconditionally.
 */
export type DesignRating = {
  id: string;
  subjectId: string | null;
  round: string;
  score: number | null;
  comment: string | null;
  suggestion: string | null;
  /** When the designer judged the piece, as their device recorded it. */
  ratedAt: string | null;
  /** When this server first heard about it. On this fleet the two can be a fortnight apart. */
  createdAt: string | null;
  updatedAt: string | null;
  mine: boolean;
  reviewerId?: string;
};

/** One row of `GET /design-ratings/rounds/{round}`. */
export type RankedItem = {
  subjectId: string;
  entityKey: string;
  label: string;
  workshopId: string;
  score: number | null;
  ratingCount: number;
  defaultPosition: number;
  placedPosition: number;
  myRating: DesignRating | null;
  /**
   * The raw `DwStageEntry.ordinal`, sent ONLY to callers who already see the whole collection.
   *
   * It is a disclosure decision on the server, not a display field, and this client reads it as
   * exactly one thing: **whether this caller is the workshop's own party or an admin**. That is
   * the same set `load_workshop_or_404` admits, so its presence is also the honest answer to "may
   * I write a new order back?" — the ordinal is saved through the stage, and the stage refuses
   * everybody else with a 404. See {@link mayArrange}.
   */
  ordinal?: number;
};

export type RoundRanking = {
  workshopId: string;
  entityKey: string;
  round: string;
  items: RankedItem[];
};

/** `GET /design-ratings/subjects/{id}` — who rated this piece, when and how. */
export type SubjectLedger = {
  subject: { id: string; entityKey: string; label: string; workshopId: string; ordinal?: number };
  round: string;
  summary: { score: number | null; ratingCount: number };
  ratings: DesignRating[];
  /**
   * Said out loud by the server so a client can explain an empty list instead of implying nobody
   * rated. False with a populated `summary` is not a refusal — it is "you can see the score, not
   * the scorers" — and must never be rendered as one.
   */
  canReadLedger: boolean;
  namesShown: boolean;
};

/**
 * May this caller write a new arrangement back?
 *
 * READ OFF THE PRESENCE OF `ordinal`, WHICH IS NOT A TRICK BUT THE SAME QUESTION ASKED ONCE. The
 * server sends the raw ordinal only when `is_member or is_admin(user)`, and the stage save that
 * would persist a reorder is gated by `load_workshop_or_404`, which admits the creator, an admin
 * and anybody holding a `DesignWorkshopViewer` grant — the same set. So a row that arrived with an
 * ordinal is a row this caller could reorder, and a row without one is not.
 *
 * The alternative — comparing the signed-in user against the workshop's creator — is the shortcut
 * the server's own code refuses in as many words, because it silently demotes every viewer-granted
 * co-designer to a stranger.
 */
export function mayArrange(items: readonly RankedItem[]): boolean {
  return items.length > 0 && items.every((item) => typeof item.ordinal === "number");
}

/* ────────────────────────────────────────────────────────────────────────────
 * The two orders
 * ──────────────────────────────────────────────────────────────────────────── */

/** The score order: what the ratings say, straight off `defaultPosition`. */
export function scoreOrder(items: readonly RankedItem[]): string[] {
  return [...items]
    .sort((a, b) => a.defaultPosition - b.defaultPosition || a.subjectId.localeCompare(b.subjectId))
    .map((item) => item.subjectId);
}

/** The placed order: what the designers say, straight off `placedPosition` (the stage ordinal). */
export function placedOrder(items: readonly RankedItem[]): string[] {
  return [...items]
    .sort((a, b) => a.placedPosition - b.placedPosition || a.subjectId.localeCompare(b.subjectId))
    .map((item) => item.subjectId);
}

/**
 * The order THIS DEVICE holds the rows in — the arrangement as the local draft has it.
 *
 * ── WHY THIS EXISTS BESIDE `placedOrder`, WHICH LOOKS LIKE THE SAME THING ───────────────────────
 *
 * It is the same thing only while the repository and this device agree. `placedPosition` is
 * `DwStageEntry.ordinal` AS THE SERVER CURRENTLY HOLDS IT, and a reorder made here is durable
 * before it is accepted: {@link arrangeRows} rearranges the draft's row array, `putDraftStage`
 * writes it, and `syncDesignWorkshopDrafts` carries it up whenever there is signal — which on this
 * fleet can be days later, or never for a stage the repository refused.
 *
 * In that window the two disagree, and taking the server's side produces the worst screen this
 * feature can show: the list in its PRE-REORDER order underneath a banner reading "this order was
 * settled deliberately by <them> on <today>". The arrangement looks thrown away and the sentence
 * above it insists it was not. So where the caller holds the rows, the rows win — they are the
 * thing the designer actually moved, and `arrangeRows` guarantees the array IS the arrangement.
 *
 * Rows with no `_entryId` are skipped rather than given a placeholder: a row created on this device
 * and not yet pushed has no id the ranking response could ever name, so it cannot take part in an
 * order keyed by subject id. {@link reconcileOrder} then appends anything this list does not name.
 */
export function heldOrder(rows: readonly DwRow[]): string[] {
  const ids: string[] = [];
  for (const row of rows) {
    const id = row._entryId;
    if (typeof id === "string" && id) ids.push(id);
  }
  return ids;
}

/**
 * The order to open on, given what the server sent and whether the arrangement was fixed.
 *
 * THIS IS THE OWNER'S SENTENCE, IN ONE FUNCTION. No stamp means nobody has overruled the scores,
 * so the list opens in score order — which is what "sorted by the quantitative data by default"
 * asks for, and it re-sorts freely as ratings arrive because nobody has claimed the arrangement.
 * A stamp means a designer has, and from then on the list opens in THEIR order and a new rating
 * changes the numbers on the cards without moving one of them.
 *
 * `held` IS THIS DEVICE'S OWN ROW ORDER AND IT OUTRANKS THE SERVER'S ORDINAL — see
 * {@link heldOrder} for why, and note that it is consulted ONLY when the list is fixed. On an
 * unfixed list the scores govern by the owner's own rule, and the local row order there is merely
 * whatever sequence the stage form happens to hold; reading it would quietly make "the default
 * order" mean "the stage's row order", which is the one thing the default is not.
 *
 * A CALLER THAT HOLDS NO ROWS PASSES NOTHING, and gets the server's ordinal as before. That is the
 * pool surface, which cannot read the stage at all.
 */
export function openingOrder(
  items: readonly RankedItem[],
  fixed: FixedOrderStamp | null,
  held: readonly string[] | null = null
): string[] {
  if (!fixed) return scoreOrder(items);
  return held && held.length > 0 ? reconcileOrder(held, items) : placedOrder(items);
}

/**
 * Reconcile a working order with a freshly fetched list.
 *
 * Ids that have gone are dropped and ids that are new are appended in their score order, so a
 * piece added by a colleague while this tab was open turns up at the end of the arrangement rather
 * than vanishing or silently re-sorting the whole list. Appending rather than inserting by score is
 * deliberate: a fixed order belongs to a person, and quietly slotting a new piece into the middle
 * of it on the strength of its first rating would be the score re-sorting a list somebody fixed.
 */
export function reconcileOrder(order: readonly string[], items: readonly RankedItem[]): string[] {
  const present = new Set(items.map((item) => item.subjectId));
  const kept = order.filter((id) => present.has(id));
  const seen = new Set(kept);
  return [...kept, ...scoreOrder(items).filter((id) => !seen.has(id))];
}

/** Whether two orders are the same arrangement of the same pieces. */
export function sameOrder(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

/**
 * One step up or down — the arrow path, which is the primary one.
 *
 * The arrows and the drag write through the same two functions on purpose. Two implementations of
 * "move this one place up" is how a list ends up behaving differently depending on which control a
 * designer reached for, and the keyboard path is the one that must be exactly right.
 */
export function moveBy(order: readonly string[], id: string, delta: number): string[] {
  const from = order.indexOf(id);
  if (from < 0) return [...order];
  return moveTo(order, from, from + delta);
}

/** Move the item at `from` to sit at index `to`, clamped. The drag path and `moveBy` both use it. */
export function moveTo(order: readonly string[], from: number, to: number): string[] {
  const next = [...order];
  if (from < 0 || from >= next.length) return next;
  const target = Math.max(0, Math.min(next.length - 1, to));
  if (target === from) return next;
  const [moved] = next.splice(from, 1);
  next.splice(target, 0, moved);
  return next;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The override stamp
 * ──────────────────────────────────────────────────────────────────────────── */

/** Who settled this order, and on what day. Both halves are stored on every row of the collection. */
export type FixedOrderStamp = { by: string; at: string };

/** The two registry keys the stamp is stored in, on both `sketch` and `prototype`. */
export const RANK_FIXED_BY_FIELD = "rankFixedBy";
export const RANK_FIXED_AT_FIELD = "rankFixedAt";

function text(value: DwValue | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}

/**
 * The stamp these rows carry, or null for "still in the default order".
 *
 * **A STAMP ON ANY ROW COUNTS, AND THE MOST RECENT ONE WINS.** The two fields are per-ROW because
 * that is where the registry put them (a collection entity has no row of its own to hang a
 * list-level fact on), but what they describe is the ARRANGEMENT, which is a property of the whole
 * collection. So a list where one row was written by an older build, or by a handset that has not
 * synced the other rows yet, still reads as fixed — the fail direction that keeps a deliberate
 * order rather than the one that silently throws it away and re-sorts by score.
 *
 * A row carrying a name and no date, or a date and no name, is NOT a stamp: the sentence on screen
 * is "fixed by X on Y" and half of it is not a sentence. Treating it as unfixed puts the list back
 * in score order with a visible "Fix this order" action beside it, which is recoverable; treating
 * it as fixed prints "fixed by — on 12 August" at a designer for ever.
 */
export function fixedOrderStamp(rows: readonly DwRow[]): FixedOrderStamp | null {
  let best: FixedOrderStamp | null = null;
  for (const row of rows) {
    const by = text(row[RANK_FIXED_BY_FIELD]);
    const at = text(row[RANK_FIXED_AT_FIELD]);
    if (!by || !at) continue;
    if (!best || at > best.at) best = { by, at };
  }
  return best;
}

/** Today as the DATE fields in this registry store it — `yyyy-mm-dd`, in the reader's own zone. */
export function todayStamp(now: Date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Turning an order back into stage rows
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The rows of one collection, rearranged to match `order` and stamped with who did it.
 *
 * ── THE ORDINAL IS NOT WRITTEN HERE, AND THAT IS DELIBERATE ─────────────────────────────────────
 *
 * `buildStageEntries` derives `ordinal` from the ARRAY ORDER at send time and ignores any stored
 * `_ordinal`, precisely so a row carrying a stale ordinal after a reorder cannot be sorted back to
 * where it came from. Writing an ordinal here would be a second opinion about the same number, and
 * the store's own comment says which one loses.
 *
 * ── EVERY ROW IS STAMPED, NOT JUST THE ONE THAT MOVED ───────────────────────────────────────────
 *
 * The stamp describes the arrangement, so a row left where it was is as much a part of the fixed
 * order as the row that was dragged. Stamping only the moved row would make "is this list fixed?"
 * depend on which row a reader happened to look at.
 *
 * `null` clears both fields on every row, which is the way back to the default the owner's rule
 * requires — blank is exactly what "the computed score still governs" is spelled as in the
 * registry, so returning to the default is a real write and not a client-side pretence.
 *
 * ROWS THE ORDER DOES NOT NAME KEEP THEIR RELATIVE POSITION AT THE END. That case is reachable in
 * ordinary use — a row created on this device and not yet pushed has no `_entryId` and so cannot be
 * in a server-sent order at all — and dropping it here would delete a designer's unsent sketch from
 * the draft on the next save.
 */
export function arrangeRows(
  rows: readonly DwRow[],
  order: readonly string[],
  stamp: FixedOrderStamp | null
): DwRow[] {
  const byId = new Map<string, DwRow>();
  for (const row of rows) {
    const id = row._entryId;
    if (typeof id === "string" && id) byId.set(id, row);
  }
  const named: DwRow[] = [];
  for (const id of order) {
    const row = byId.get(id);
    if (row) {
      named.push(row);
      byId.delete(id);
    }
  }
  const rest = rows.filter((row) => typeof row._entryId !== "string" || byId.has(row._entryId));
  return [...named, ...rest].map((row) => ({
    ...row,
    [RANK_FIXED_BY_FIELD]: stamp ? stamp.by : "",
    [RANK_FIXED_AT_FIELD]: stamp ? stamp.at : ""
  }));
}

/* ────────────────────────────────────────────────────────────────────────────
 * Finding the stage a rateable entity lives in
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The stage that declares this entity, read out of the registry rather than hardcoded.
 *
 * Sketches are stage 11 and prototypes are stage 13 TODAY. Writing those keys into this client
 * would be a fourth copy of a fact the registry already publishes, and the registry is the thing
 * that moves: `SKETCH_REVIEW` is marked `optional_stage` and the source document proposed deleting
 * it outright, so the numbering around these two is not a constant anybody should lean on.
 */
export function stageKeyForEntity(registry: DwRegistry, entityKey: string): string | null {
  for (const stage of registry.stages) {
    for (const entity of stage.entities) {
      if (entity.key === entityKey && entity.cardinality === "COLLECTION") return stage.key;
    }
  }
  return null;
}

/**
 * A short line describing one piece from its own stored row — the identifier the label omits.
 *
 * The ranking response sends a label built from `name`, falling back to the identifier column, so
 * a piece with both shows only its name. On the workshop's own tab the row itself is on this
 * device, and a reviewer choosing between eight bamboo stools needs the sketch number as well.
 */
export function rowSubtitle(row: DwEntryData | undefined): string {
  if (!row) return "";
  const parts: string[] = [];
  for (const key of ["sketchNo", "prototypeCode", "designerName", "makerName"]) {
    const value = text(row[key]);
    if (value) parts.push(value);
  }
  return parts.join(" · ");
}

/**
 * What a stage save's TWO failure-shaped outcomes actually mean, decided in one place.
 *
 * A "Save stage" press has two halves and each has an outcome that looks like nothing at all:
 *
 *   1. The write to this browser's own IndexedDB, which `mutate` answers with `null` for three
 *      different reasons — no such draft, another session's draft, or a transaction that ABORTED.
 *   2. The PUT to the repository, which answers a `submit=true` refusal with HTTP 422 carrying the
 *      whole save result under `detail` — a result that says what WAS written as well as what was
 *      refused.
 *
 * Both were read as "nothing happened" by the stage form, and both readings were wrong in the
 * expensive direction. Audit 2026-08-15 filed three findings across them (one MAJOR for the local
 * write banked as saved, two MAJOR for the 422's per-field map being discarded). They live together
 * here because they are the same mistake — treating an outcome the caller did not model as an
 * absence — and because putting them in a module makes them testable without a browser, a server and
 * a deliberately-broken origin quota.
 *
 * WHY IT IS PURE. No React, no fetch, no IndexedDB: it takes what the two calls returned and returns
 * a decision. That is the shape `lib/submissionReadiness.ts` and `lib/reportTarget.ts` already use,
 * and it is what let the sibling defects in those areas be pinned by `e2e/*-unit.spec.ts` rather than
 * by a signed-in browser run nobody can execute on a laptop with no stack up.
 */

import { ApiError } from "@/lib/api";
import type { DwSaveResult } from "@/lib/designWorkshops";

/**
 * The full save result a `submit=true` refusal carries, or null when this error is not one.
 *
 * THE SERVER PUTS THE WHOLE RESULT UNDER `detail`, NOT ONLY THE ERRORS, and that is load-bearing.
 * `PUT /design-workshops/{id}/stages/{key}` raises its 422 AFTER `save_stage` has committed: rows
 * were created, rows were updated, the sweep's soft-deletes landed and the workshop moved from DRAFT
 * to IN_PROGRESS. A client that reads the refusal as "nothing was written" tells a designer that a
 * deletion they just made did not happen. The route's own comment says it spreads `result` for
 * exactly this reason, so this function returns the result and not merely `result.errors`.
 *
 * WHY THE NARROWING IS THIS DEFENSIVE. `ApiError.payload` is declared `unknown` because it is
 * whatever JSON came back, and this function runs inside a `catch`: a body of an unexpected shape
 * must degrade to `null` so the caller falls through to its generic sentence, never throw a second
 * error out of the handler for the first one. FastAPI's own validation failures use the SAME status
 * code with `detail` as a LIST of `{loc, msg}` objects, and Pydantic's `extra="forbid"` skew refusal
 * is a 422 with a string `detail` — both are reachable on this very route, so `errors` is required to
 * be a non-null object before anything is claimed about the body. `Array.isArray` is checked
 * explicitly because arrays are objects and `detail[0].errors` would otherwise be read as `undefined`
 * on a shape that is emphatically not a save result.
 *
 * Only 422 is accepted. A 500's body is a stack-trace envelope with no `errors` map, and a 404's
 * detail is a bare string; letting either through would mark boxes from a response that never
 * examined them.
 */
export function stageRefusalResult(err: unknown): DwSaveResult | null {
  if (!(err instanceof ApiError) || err.status !== 422) return null;
  const payload = err.payload;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const detail = (payload as { detail?: unknown }).detail;
  if (!detail || typeof detail !== "object" || Array.isArray(detail)) return null;
  const errors = (detail as { errors?: unknown }).errors;
  if (!errors || typeof errors !== "object" || Array.isArray(errors)) return null;
  return detail as DwSaveResult;
}

/**
 * How many rows a refused save nevertheless WROTE, as one number.
 *
 * Kept beside the narrowing because it is the same correction: the counts are on the refusal and the
 * old catch never looked at them. Missing keys count as zero rather than as unknown — a server that
 * omits `created` did not create anything, and a sentence that says "0 added" where the truth is "we
 * do not know" is a smaller error than one that claims a write nobody made.
 */
export function stageRefusalWroteCount(result: DwSaveResult | null | undefined): number {
  if (!result) return 0;
  return (result.created ?? 0) + (result.updated ?? 0) + (result.removed ?? 0);
}

/**
 * What the stage form must do about the result of writing the draft to this browser's storage.
 *
 * THE ONLY INPUT IS "DID THE STORE ANSWER WITH A DRAFT", because that is the only thing the store
 * tells us: `putDraftStage` returns `mutate`'s value unchanged, and `mutate` catches every
 * transaction rejection into `null`. A `QuotaExceededError` on a readwrite `put` arrives by exactly
 * that route, and so does a store deleted underneath the tab by "clear site data".
 *
 * Every field of the returned decision was previously hard-coded to its SUCCESS value on both paths,
 * and each one told a separate lie about a refused write:
 *
 * - `bank` false keeps the autosave's baseline stale. Banking a snapshot that never reached disk
 *   makes the effect's `sameSnapshot` short-circuit refuse to write those values EVER AGAIN, so the
 *   loss is permanent for the session rather than merely delayed.
 * - `retry` true keeps the flush armed. It was nulled, which threw away the only retry.
 * - `pending` true keeps the amber "Saved on this device only" chip lit — the designer's sole
 *   indication that something is outstanding. It was extinguished.
 * - `failed` true raises the banner that says the text on screen exists nowhere else. There was none.
 *
 * If somebody "simplifies" this back to a single unconditional path, the failure mode is silent,
 * permanent loss of a stage a designer watched turn from amber to nothing — and then a "Stage saved"
 * over the stale copy, because `save()` builds its payload from the draft read back off disk.
 */
export type LocalWriteDecision = {
  /** Advance the autosave baseline to the snapshot just written. */
  bank: boolean;
  /** Leave the debounced write armed so the next flush or keystroke tries again. */
  retry: boolean;
  /** Keep the "saved on this device only" indicator lit. */
  pending: boolean;
  /** Raise the banner saying the boxes hold the only copy of this text. */
  failed: boolean;
};

export function localWriteDecision(saved: unknown): LocalWriteDecision {
  const landed = saved !== null && saved !== undefined;
  return { bank: landed, retry: !landed, pending: !landed, failed: !landed };
}

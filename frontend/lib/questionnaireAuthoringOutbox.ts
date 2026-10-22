/**
 * OFFLINE FORM AUTHORING WITH TEMPORARY IDS (UNIFY A15, web).
 *
 * A designer with no signal can make a form, add its sections and add their questions. Each is a
 * step in this outbox — `form`, `section`, `question` — written in order and given a temporary id
 * (`tmp-…`) the later steps point at. When the browser is back online the steps are sent oldest
 * first: each create's real id is recorded against its temporary one (the remap) and every later
 * step is resolved through it, so a question added to a section added to a form made offline lands
 * under the right rows. A form that already exists takes sections and questions offline the same way.
 *
 * RULES, AND WHY:
 * - One step at a time, in order. A step whose parent has not landed waits; one the server refused
 *   (a 4xx) is parked with the server's sentence and blocks only its own children. Send again and
 *   Discard are offered beside it; discarding a form or a section discards what was added under it.
 * - Nothing that only waited for a connection is ever marked: `lib/failureTriage` decides.
 * - Every POST carries an `Idempotency-Key` (the step's id), so a send that reached the server but
 *   whose answer was lost does not make the row twice on the retry.
 * - The outbox is stamped with the account that wrote it and is neither shown to nor sent by any
 *   other account on the device (the rule `lib/questionnaireFormCache` and `DwDraft` keep).
 * - Kept in localStorage: a few small JSON bodies, read synchronously by the screens that draw them.
 */

import { apiFetch } from "@/lib/api";
import { triageFailure } from "@/lib/failureTriage";
import type { QForm } from "@/lib/questionnaireForms";

const KEY = "design_workshop_authoring_outbox:v1";
export const TEMP_PREFIX = "tmp-";

export type AuthoringStatus = "PENDING" | "FAILED";
export type FormBody = { title: string; description?: string; designWorkshopId?: string; kind?: string };
export type SectionBody = { title: string; code?: string };
export type QuestionBody = { prompt: string; helpText?: string; isRequired?: boolean };

export type AuthoringStep =
  | { id: string; kind: "form"; tempId: string; body: FormBody; status: AuthoringStatus; error?: string; at: string }
  | { id: string; kind: "section"; tempId: string; formRef: string; body: SectionBody; status: AuthoringStatus; error?: string; at: string }
  | {
      id: string;
      kind: "question";
      tempId: string;
      formRef: string;
      sectionRef: string;
      body: QuestionBody;
      status: AuthoringStatus;
      error?: string;
      at: string;
    };

type Stored = { ownerUserId: string | null; steps: AuthoringStep[]; remap: Record<string, string> };

const listeners = new Set<() => void>();
let snapshot: Stored | null = null;

function blank(owner: string | null): Stored {
  return { ownerUserId: owner, steps: [], remap: {} };
}

function read(): Stored {
  if (snapshot) return snapshot;
  try {
    const raw = typeof localStorage === "undefined" ? null : localStorage.getItem(KEY);
    snapshot = raw ? (JSON.parse(raw) as Stored) : blank(null);
  } catch {
    snapshot = blank(null);
  }
  return snapshot;
}

function write(next: Stored) {
  snapshot = next;
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // A full or blocked store: the steps live for this tab, and the screen keeps saying "waiting".
  }
  listeners.forEach((l) => l());
}

const FOREIGN: Stored = Object.freeze({ ownerUserId: null, steps: [], remap: {} }) as Stored;

/** The outbox as `owner` may see it: another account's steps are never shown or sent. */
export function authoringState(owner: string | null): Stored {
  const stored = read();
  if (stored.steps.length && stored.ownerUserId && owner && stored.ownerUserId !== owner) return FOREIGN;
  return stored;
}

export function subscribeAuthoring(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function mint(prefix: string): string {
  const random = typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}${random}`;
}

function append(owner: string | null, step: AuthoringStep): AuthoringStep {
  const current = authoringState(owner);
  write({ ...current, ownerUserId: owner, steps: [...current.steps, step] });
  return step;
}

export function isTempId(id: string | null | undefined): boolean {
  return Boolean(id && id.startsWith(TEMP_PREFIX));
}

/** A real id for a temporary one, once its create has landed; the id itself otherwise. */
export function resolveId(owner: string | null, id: string): string {
  return authoringState(owner).remap[id] ?? id;
}

export function queueForm(owner: string | null, body: FormBody): string {
  const tempId = mint(`${TEMP_PREFIX}form-`);
  append(owner, { id: mint("step-"), kind: "form", tempId, body, status: "PENDING", at: new Date().toISOString() });
  return tempId;
}

export function queueSection(owner: string | null, formRef: string, body: SectionBody): string {
  const tempId = mint(`${TEMP_PREFIX}section-`);
  append(owner, { id: mint("step-"), kind: "section", tempId, formRef, body, status: "PENDING", at: new Date().toISOString() });
  return tempId;
}

export function queueQuestion(owner: string | null, formRef: string, sectionRef: string, body: QuestionBody): string {
  const tempId = mint(`${TEMP_PREFIX}question-`);
  append(owner, { id: mint("step-"), kind: "question", tempId, formRef, sectionRef, body, status: "PENDING", at: new Date().toISOString() });
  return tempId;
}

/** The steps that belong to one form (by its temporary or its real id). */
export function stepsForForm(owner: string | null, formId: string): AuthoringStep[] {
  const state = authoringState(owner);
  const ids = new Set([formId, ...Object.entries(state.remap).filter(([, real]) => real === formId).map(([temp]) => temp)]);
  return state.steps.filter((s) => (s.kind === "form" ? ids.has(s.tempId) : ids.has(s.formRef)));
}

/** "Send again": a refused step goes back to waiting. */
export function retryAuthoringStep(owner: string | null, stepId: string) {
  const state = authoringState(owner);
  write({ ...state, steps: state.steps.map((s) => (s.id === stepId ? { ...s, status: "PENDING", error: undefined } : s)) });
}

/** "Discard": the step, and everything added under what it would have made. */
export function discardAuthoringStep(owner: string | null, stepId: string) {
  const state = authoringState(owner);
  const step = state.steps.find((s) => s.id === stepId);
  if (!step) return;
  const gone = new Set([step.tempId]);
  const steps = state.steps.filter((s) => {
    if (s.id === stepId) return false;
    if (s.kind === "section" && gone.has(s.formRef)) {
      gone.add(s.tempId);
      return false;
    }
    if (s.kind === "question" && (gone.has(s.formRef) || gone.has(s.sectionRef))) return false;
    return true;
  });
  write({ ...state, steps });
}

/** The new section in a create's answer: the last live one with this title (and code, when sent). */
export function landedSectionId(form: QForm, body: SectionBody, taken: ReadonlySet<string>): string | null {
  const matches = form.sections.filter((s) => s.title === body.title && (!body.code || s.code === body.code) && !taken.has(s.id));
  return matches.at(-1)?.id ?? null;
}

/** The new question in a create's answer: the last one with this prompt in its section. */
export function landedQuestionId(form: QForm, sectionId: string, body: QuestionBody, taken: ReadonlySet<string>): string | null {
  const section = form.sections.find((s) => s.id === sectionId);
  const matches = (section?.questions ?? []).filter((q) => q.prompt === body.prompt && !taken.has(q.id));
  return matches.at(-1)?.id ?? null;
}

type Post = <T>(path: string, body: unknown, idempotencyKey: string) => Promise<T>;

const post: Post = (path, body, idempotencyKey) =>
  apiFetch(path, { method: "POST", body: JSON.stringify(body), headers: { "Idempotency-Key": idempotencyKey } });

let draining: Promise<number> | null = null;

/**
 * Sends what can be sent, in order; resolves with how many steps landed. A step waiting on a parent
 * that has not landed (or was refused) is skipped, not failed. Stops at the first failure that only
 * needs a connection or time, keeping every step as it is.
 */
export function drainAuthoring(owner: string | null, send: Post = post): Promise<number> {
  if (draining) return draining;
  draining = (async () => {
    let landed = 0;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return 0;
    for (;;) {
      const state = authoringState(owner);
      const blocked = new Set(state.steps.filter((s) => s.status === "FAILED").map((s) => s.tempId));
      const next = state.steps.find((s) => {
        if (s.status !== "PENDING") return false;
        if (s.kind === "form") return true;
        const refs = s.kind === "section" ? [s.formRef] : [s.formRef, s.sectionRef];
        return refs.every((ref) => !blocked.has(ref) && (!isTempId(ref) || state.remap[ref]));
      });
      if (!next) return landed;
      const real = (ref: string) => state.remap[ref] ?? ref;
      let realId: string | null = null;
      try {
        if (next.kind === "form") {
          const form = await send<QForm>("/questionnaires", next.body, next.id);
          realId = form.id;
        } else if (next.kind === "section") {
          const formId = real(next.formRef);
          const form = await send<QForm>(`/questionnaires/${encodeURIComponent(formId)}/sections`, next.body, next.id);
          realId = landedSectionId(form, next.body, new Set(Object.values(state.remap)));
        } else {
          const formId = real(next.formRef);
          const sectionId = real(next.sectionRef);
          const form = await send<QForm>(
            `/questionnaires/${encodeURIComponent(formId)}/sections/${encodeURIComponent(sectionId)}/questions`,
            next.body,
            next.id
          );
          realId = landedQuestionId(form, sectionId, next.body, new Set(Object.values(state.remap)));
        }
      } catch (error) {
        const verdict = triageFailure(error);
        if (verdict.kind === "unreachable" || verdict.kind === "transient" || verdict.kind === "credential-expired") return landed;
        const now = authoringState(owner);
        const message = error instanceof Error ? error.message : String(error);
        write({ ...now, steps: now.steps.map((s) => (s.id === next.id ? { ...s, status: "FAILED", error: message } : s)) });
        continue;
      }
      const now = authoringState(owner);
      write({
        ...now,
        remap: realId ? { ...now.remap, [next.tempId]: realId } : now.remap,
        steps: now.steps.filter((s) => s.id !== next.id)
      });
      landed += 1;
    }
  })().finally(() => {
    draining = null;
  });
  return draining;
}

/** Test seam: forget the in-memory copy so the next read comes from storage. */
export function resetAuthoringCache() {
  snapshot = null;
}

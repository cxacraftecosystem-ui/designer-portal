"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { useAuth } from "@/components/AuthProvider";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { qs } from "@/lib/questionnaire/qs";
import {
  authoringState,
  discardAuthoringStep,
  drainAuthoring,
  queueQuestion,
  queueSection,
  retryAuthoringStep,
  stepsForForm,
  subscribeAuthoring,
  type AuthoringStep
} from "@/lib/questionnaireAuthoringOutbox";

/**
 * The authoring outbox as a screen sees it (UNIFY A15): the account's steps, sent whenever the
 * browser comes online or the screen opens. `owner` is the signed-in account.
 */
export function useAuthoringOutbox(owner: string | null) {
  const state = useSyncExternalStore(
    subscribeAuthoring,
    () => authoringState(owner),
    () => authoringState(owner)
  );
  useEffect(() => {
    if (!owner) return;
    const go = () => void drainAuthoring(owner);
    go();
    window.addEventListener("online", go);
    return () => window.removeEventListener("online", go);
  }, [owner]);
  return state;
}

/**
 * What waits to be sent for one form: sections and questions added offline, each with its state,
 * Send again (when refused) and Discard. A section added offline takes questions here too. When a
 * form's last step lands, `onLanded` runs so the page can read the form again.
 */
export function PendingAuthoring({
  formId,
  sections,
  onLanded
}: {
  formId: string;
  /** The form's live sections, for naming a question added to one of them. */
  sections: { id: string; title: string }[];
  onLanded?: () => void;
}) {
  const { user } = useAuth();
  const owner = user?.id ?? null;
  const state = useAuthoringOutbox(owner);
  const steps = owner ? stepsForForm(owner, formId) : [];
  const had = useRef(steps.length);
  useEffect(() => {
    if (had.current > 0 && steps.length < had.current) onLanded?.();
    had.current = steps.length;
  }, [steps.length, onLanded]);
  void state;
  const children = steps.filter((s) => s.kind !== "form");
  if (!children.length) return null;
  const pendingSections = children.filter((s): s is Extract<AuthoringStep, { kind: "section" }> => s.kind === "section");
  const looseQuestions = children.filter(
    (s): s is Extract<AuthoringStep, { kind: "question" }> => s.kind === "question" && !pendingSections.some((p) => p.tempId === s.sectionRef)
  );
  const sectionTitle = (id: string) => sections.find((s) => s.id === id)?.title ?? "";

  return (
    <section className="panel mb-5 grid gap-3 p-4" data-pending-authoring>
      <h2 className="font-display text-base font-bold text-ink-900">{qs("authoringWaiting")}</h2>
      <p className="text-sm text-ink-600">{qs("authoringSavedHere")}</p>
      <ul className="grid gap-3">
        {pendingSections.map((section) => (
          <li key={section.id} className="grid gap-2 rounded-md border border-line-200 p-3" data-pending-section={section.tempId}>
            <StepLine step={section} owner={owner} label={section.body.title} />
            <ul className="grid gap-2 pl-3">
              {children
                .filter((s): s is Extract<AuthoringStep, { kind: "question" }> => s.kind === "question" && s.sectionRef === section.tempId)
                .map((q) => (
                  <li key={q.id} data-pending-question>
                    <StepLine step={q} owner={owner} label={q.body.prompt} />
                  </li>
                ))}
            </ul>
            <AddQuestion owner={owner} formId={formId} sectionRef={section.tempId} />
          </li>
        ))}
        {looseQuestions.map((q) => (
          <li key={q.id} className="rounded-md border border-line-200 p-3" data-pending-question>
            <StepLine step={q} owner={owner} label={`${sectionTitle(q.sectionRef)} · ${q.body.prompt}`} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function StepLine({ step, owner, label }: { step: AuthoringStep; owner: string | null; label: string }) {
  return (
    <div className="grid gap-1 text-sm">
      <span className="font-medium text-ink-900">{label}</span>
      <span className="text-ink-600" data-step-state={step.status}>
        {step.status === "FAILED" ? qs("authoringNotSent", { error: step.error ?? "" }) : qs("authoringWaiting")}
      </span>
      <ButtonRow size="sm" align="start">
        {step.status === "FAILED" ? (
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              retryAuthoringStep(owner, step.id);
              void drainAuthoring(owner);
            }}
          >
            {qs("outboxSendAgain")}
          </button>
        ) : null}
        <button type="button" className="field-danger" onClick={() => discardAuthoringStep(owner, step.id)}>
          {qs("discard")}
        </button>
      </ButtonRow>
    </div>
  );
}

function AddQuestion({ owner, formId, sectionRef }: { owner: string | null; formId: string; sectionRef: string }) {
  const [prompt, setPrompt] = useState("");
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (!prompt.trim()) return;
        queueQuestion(owner, formId, sectionRef, { prompt: prompt.trim() });
        setPrompt("");
        void drainAuthoring(owner);
      }}
    >
      <input
        className="field-input min-w-0 flex-1"
        aria-label={qs("authoringQuestionPrompt")}
        placeholder={qs("authoringQuestionPrompt")}
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
      />
      <button type="submit" className="field-button-secondary" disabled={!prompt.trim()}>
        {qs("authoringAddQuestion")}
      </button>
    </form>
  );
}

/**
 * A form made offline, before its create has landed (`/questionnaires/tmp-…`): its title, its
 * sections and questions as queued, and Add section. Once the form lands the page moves to its real id.
 */
export function OfflineFormDraft({ id }: { id: string }) {
  const { user } = useAuth();
  const owner = user?.id ?? null;
  const router = useRouter();
  const state = useAuthoringOutbox(owner);
  const [title, setTitle] = useState("");
  const real = state.remap[id];
  useEffect(() => {
    if (real) router.replace(`/questionnaires/${encodeURIComponent(real)}`);
  }, [real, router]);
  const formStep = state.steps.find((s): s is Extract<AuthoringStep, { kind: "form" }> => s.kind === "form" && s.tempId === id);
  return (
    <div className="grid gap-4" data-offline-form={id}>
      <header className="grid gap-1">
        <p className="text-xs font-semibold uppercase tracking-wide text-ink-500">{qs("authoringOfflineForm")}</p>
        <h1 className="font-display text-2xl font-bold text-ink-900">{formStep?.body.title ?? ""}</h1>
        <p className="text-sm text-ink-600">{qs("authoringSavedHere")}</p>
        {formStep ? <StepLine step={formStep} owner={owner} label={formStep.body.title} /> : null}
      </header>
      <form
        className="panel flex flex-wrap items-center gap-2 p-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (!title.trim()) return;
          queueSection(owner, id, { title: title.trim() });
          setTitle("");
          void drainAuthoring(owner);
        }}
      >
        <input
          className="field-input min-w-0 flex-1"
          aria-label={qs("authoringSectionTitle")}
          placeholder={qs("authoringSectionTitle")}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />
        <button type="submit" className="field-button" disabled={!title.trim()}>
          {qs("authoringAddSection")}
        </button>
      </form>
      <PendingAuthoring formId={id} sections={[]} />
    </div>
  );
}

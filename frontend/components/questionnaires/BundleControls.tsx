"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";

import { PromptDialog } from "@/components/questionnaire/Prompts";
import { saveBlobToDisk } from "@/lib/fileDownload";
import {
  BUNDLE_EXTENSIONS,
  BundleError,
  bundleBlob,
  bundleFileName,
  buildBundle,
  importedBundles,
  readBundleFile,
  rememberImported,
  type QuestionnaireBundle
} from "@/lib/questionnaire/bundle";
import { prompts } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";
import { BUNDLE_EXTENSION, formHref, PORTAL } from "@/lib/questionnairePortal";
import { createQuestion, createQuestionnaire, createSection, type QForm, type QFormKind } from "@/lib/questionnaireForms";

/**
 * Add a received bundle as a NEW form owned by the reader (UNIFY A10): the form, then each section,
 * then its questions. A bundle this device has already added opens the form it became instead.
 * Needs a connection; the button says so when there is none.
 */
export async function importBundle(bundle: QuestionnaireBundle): Promise<string> {
  const already = importedBundles()[bundle.bundleId];
  if (already) return already;
  const created = await createQuestionnaire({
    title: bundle.form.title,
    description: bundle.form.description,
    kind: (bundle.form.kind as QFormKind | null) ?? null
  });
  let form: QForm = created;
  for (const section of [...bundle.sections].sort((a, b) => a.sortOrder - b.sortOrder)) {
    form = await createSection(created.id, { title: section.title || section.code, code: section.code, sortOrder: section.sortOrder });
    const made = form.sections.find((s) => s.code === section.code) ?? form.sections[form.sections.length - 1];
    const questions = bundle.questions.filter((q) => q.sectionCode === section.code).sort((a, b) => a.sortOrder - b.sortOrder);
    for (const q of questions) {
      await createQuestion(created.id, made.id, { prompt: q.prompt, helpText: q.helpText, isRequired: q.isRequired, sortOrder: q.sortOrder });
    }
  }
  rememberImported(bundle.bundleId, created.id);
  return created.id;
}

/** "Open bundle" on the forms library: pick a `.dpwq` / `.frwq`, then P17 "Received a form". */
export function OpenBundleButton({ className = "field-button-secondary" }: { className?: string }) {
  const router = useRouter();
  const input = useRef<HTMLInputElement | null>(null);
  const [received, setReceived] = useState<QuestionnaireBundle | null>(null);
  const [error, setError] = useState<string | null>(null);
  const online = typeof navigator === "undefined" || navigator.onLine !== false;
  return (
    <>
      <button
        type="button"
        className={className}
        disabled={!online}
        title={online ? undefined : qs("needsConnection")}
        onClick={() => input.current?.click()}
        data-open-bundle
      >
        {qs("openBundle")}
      </button>
      <input
        ref={input}
        type="file"
        accept={BUNDLE_EXTENSIONS.join(",")}
        className="sr-only"
        tabIndex={-1}
        aria-hidden
        data-bundle-input
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (!file) return;
          setError(null);
          void readBundleFile(file)
            .then(setReceived)
            .catch((failure: unknown) => setError(failure instanceof BundleError || failure instanceof Error ? failure.message : String(failure)));
        }}
      />
      {error ? (
        <p role="alert" className="text-sm text-error-600" data-bundle-error>
          {error}
        </p>
      ) : null}
      {received ? (
        <PromptDialog
          spec={prompts.p17({ name: received.portal === PORTAL ? qs("aColleague") : qs("aFieldRepositoryColleague"), title: received.form.title, count: received.sections.length })}
          open
          onChoose={(choice) => {
            const bundle = received;
            setReceived(null);
            if (choice !== "add") return;
            void importBundle(bundle)
              .then((id) => router.push(formHref(id)))
              .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure)));
          }}
        />
      ) : null}
    </>
  );
}

/** "Download bundle" on a form's page: the handoff file, saved on this device. */
export function DownloadBundleButton({
  form,
  className = "field-button-secondary"
}: {
  form: { title: string; description?: string | null; kind?: string | null; sections: QForm["sections"] };
  className?: string;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      className={className}
      disabled={busy}
      data-download-bundle
      onClick={() => {
        setBusy(true);
        const bundle = buildBundle(
          {
            title: form.title,
            description: form.description,
            kind: form.kind,
            sections: form.sections.filter((s) => s.isActive).map((s) => ({ code: s.code, title: s.title, sortOrder: s.sortOrder, questions: s.questions }))
          },
          PORTAL
        );
        void bundleBlob(bundle)
          .then((blob) => saveBlobToDisk(blob, bundleFileName(bundle, BUNDLE_EXTENSION)))
          .finally(() => setBusy(false));
      }}
    >
      {qs("sendToPhone")}
    </button>
  );
}

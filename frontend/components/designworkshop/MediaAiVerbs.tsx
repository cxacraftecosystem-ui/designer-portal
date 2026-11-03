"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Captions, Image as ImageIcon, Loader2 } from "lucide-react";

import { useWorkshopConsent } from "@/components/hooks/useWorkshopConsent";
import { AiVerbReviewDialog } from "@/components/designworkshop/AiVerbReviewDialog";
import {
  AI_VERB_COUNTDOWN_FROM,
  MEDIA_NOT_UPLOADED_YET,
  SUBTITLES_SECOND_UPLOAD_NOTE,
  VERBS_NEED_A_CONNECTION,
  WORKSHOP_NOT_ON_SERVER_YET,
  aiLayerProblem,
  captionDesignWorkshopMedia,
  dwAiVerbAllowance,
  isVerbOffline,
  subtitleDesignWorkshopMedia,
  verbAllowanceRefusal,
    verbWorkshopRefusal,
  CONSENT_LINK_LABEL,
  consentRefused,
  type DwAiVerbAllowanceState,
  type DwAiVerbResult
} from "@/lib/aiVerbs";
import { listDesignWorkshopAiLayers } from "@/lib/aiLayers";
import type { MediaFile } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type MediaAiVerbsProps = {
  workshopId: string;
  files: MediaFile[];
  local: { key: string; name: string; url: string }[];
  disabled?: boolean;
};

export function MediaAiVerbs({ workshopId, files, local, disabled }: MediaAiVerbsProps) {
  const consent = useWorkshopConsent(workshopId);
  const serverId = consent.serverId;
  const [allowance, setAllowance] = useState<DwAiVerbAllowanceState | null>(null);
  const [running, setRunning] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [result, setResult] = useState<DwAiVerbResult | null>(null);
  const [subtitled, setSubtitled] = useState<Set<string> | null>(null);

  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const timed = files.filter((file) => file.mediaType === "AUDIO" || file.mediaType === "VIDEO");
  const hasTimed = timed.length > 0;

  useEffect(() => {
    void dwAiVerbAllowance().then((answer) => {
      if (mounted.current) setAllowance(answer);
    });
  }, []);

  const loadSubtitled = useCallback(() => {
    if (!hasTimed) return;
    if (!serverId) return;
    void listDesignWorkshopAiLayers(serverId, { kind: "SUBTITLES" })
      .then((list) => {
        if (!mounted.current) return;
        setSubtitled(
          new Set(
            list.items
              .filter((layer) => layer.deletedAt === null && layer.source?.kind === "MEDIA" && layer.source.id)
              .map((layer) => String(layer.source?.id))
          )
        );
      })
      .catch(() => {
      });
  }, [hasTimed, serverId]);

  useEffect(loadSubtitled, [loadSubtitled]);

  const refused = consentRefused(consent.decision);

  const blocked =
    verbWorkshopRefusal({ ready: consent.ready, serverId, decision: consent.decision }) ??
    verbAllowanceRefusal(allowance);

  async function run(verb: "CAPTION" | "SUBTITLES", mediaId: string) {
    if (!serverId) {
      setProblem(WORKSHOP_NOT_ON_SERVER_YET);
      return;
    }
    setRunning(`${verb}:${mediaId}`);
    setProblem(null);
    try {
      const answer =
        verb === "CAPTION"
          ? await captionDesignWorkshopMedia(serverId, mediaId)
          : await subtitleDesignWorkshopMedia(serverId, mediaId);
      if (!mounted.current) return;
      setResult(answer);
      setAllowance((current) =>
        current
          ? {
              ...current,
              aiVerbsLimit: answer.aiVerbsLimit,
              aiVerbsUsed: answer.aiVerbsUsed,
              aiVerbsRemaining: answer.aiVerbsRemaining,
              aiVerbDay: answer.aiVerbDay,
              aiVerbsByVerb: answer.aiVerbsByVerb,
              refusal: null
            }
          : null
      );
      if (verb === "SUBTITLES") setSubtitled((current) => new Set([...(current ?? []), mediaId]));
    } catch (err) {
      if (!mounted.current) return;
      setProblem(isVerbOffline(err) ? VERBS_NEED_A_CONNECTION : aiLayerProblem(err, "That could not be done."));
      void dwAiVerbAllowance().then((fresh) => {
        if (mounted.current && fresh) setAllowance(fresh);
      });
    } finally {
      if (mounted.current) setRunning(null);
    }
  }

  if (!files.length && !local.length) return null;

  return (
    <section className="mt-3 grid gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 className="field-label">Ask AI about these files</h4>
        {allowance && allowance.aiVerbsRemaining !== null && allowance.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM ? (
          <span className="text-xs font-medium text-amber-800 dark:text-amber-100">
            {allowance.aiVerbsRemaining} run{allowance.aiVerbsRemaining === 1 ? "" : "s"} left today (
            {allowance.aiVerbDay})
          </span>
        ) : null}
      </div>

      <p className="text-xs leading-5 text-ink-500">
        Saved as an AI layer to accept or decline. It doesn&rsquo;t fill in the caption.
      </p>

      {blocked ? (
        <p className="text-xs leading-5 text-ink-500">
          {blocked}
          {consent.ready && refused ? (
            <>
              {" "}
              <Link href={`/design-workshops/${workshopId}#consent`} className="font-medium text-purple-700 dark:text-purple-300 underline">
                {CONSENT_LINK_LABEL}
              </Link>
            </>
          ) : null}
        </p>
      ) : null}

      <ul className="grid gap-2">
        {files.map((file) => {
          const isImage = file.mediaType === "IMAGE";
          const isVideo = file.mediaType === "VIDEO";
          const isAudio = file.mediaType === "AUDIO";
          if (!isImage && !isVideo && !isAudio) {
            return (
              <li key={file.id} className="text-xs leading-5 text-ink-500">
                <span className="font-medium text-ink-700">{file.originalFilename}</span> — neither describing nor
                subtitling applies to this kind of file.
              </li>
            );
          }
          const alreadySubtitled = subtitled?.has(file.id) ?? false;
          return (
            <li key={file.id} className="grid gap-1.5">
              <p className="truncate text-xs font-medium text-ink-700">{file.originalFilename}</p>
              <ActionGroup>
                {isImage || isVideo ? (
                  <button
                    type="button"
                    className="field-button-secondary"
                    disabled={Boolean(disabled) || blocked !== null || running !== null}
                    onClick={() => void run("CAPTION", file.id)}
                  >
                    {running === `CAPTION:${file.id}` ? (
                      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                    ) : (
                      <ImageIcon className="h-4 w-4" aria-hidden />
                    )}
                    {isVideo ? "Describe this" : "Describe this photograph"}
                  </button>
                ) : null}
                {(isAudio || isVideo) && !alreadySubtitled ? (
                  <button
                    type="button"
                    className="field-button-secondary"
                    disabled={Boolean(disabled) || blocked !== null || running !== null}
                    onClick={() => void run("SUBTITLES", file.id)}
                  >
                    {running === `SUBTITLES:${file.id}` ? (
                      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                    ) : (
                      <Captions className="h-4 w-4" aria-hidden />
                    )}
                    Make subtitles
                  </button>
                ) : null}
              </ActionGroup>
              {(isAudio || isVideo) && alreadySubtitled ? (
                <p className="text-xs leading-5 text-ink-500">
                  This one already has subtitles.{" "}
                  <Link href={`/design-workshops/${workshopId}/ai-layers`} className="font-medium text-purple-700 dark:text-purple-300 underline">
                    Read them on the AI layers screen
                  </Link>{" "}
                  — running again uses another AI run.
                </p>
              ) : (isAudio || isVideo) && !blocked ? (
                <p className="text-xs leading-5 text-ink-500">
                  {SUBTITLES_SECOND_UPLOAD_NOTE}
                </p>
              ) : null}
            </li>
          );
        })}

        {local.map((entry) => (
          <li key={entry.key} className="text-xs leading-5 text-ink-500">
            <span className="font-medium text-ink-700">{entry.name}</span> — {MEDIA_NOT_UPLOADED_YET}
          </li>
        ))}
      </ul>

      {problem ? (
        <p role="alert" className="text-xs leading-5 text-error-600">
          {problem}
        </p>
      ) : null}

      <AiVerbReviewDialog
        open={result !== null}
        workshopId={serverId ?? workshopId}
        result={result}
        onAccepted={() => {
          setResult(null);
          loadSubtitled();
        }}
        onDeclined={() => {
          setResult(null);
          loadSubtitled();
        }}
        onClose={() => setResult(null)}
      />
    </section>
  );
}

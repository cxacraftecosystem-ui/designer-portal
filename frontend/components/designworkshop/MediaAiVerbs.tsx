"use client";

/**
 * DESCRIBE THIS PHOTOGRAPH, OR MAKE SUBTITLES FOR THIS RECORDING — beside the tile, in the stage the
 * file was attached to.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY HERE AND NOT ON A BATCH SCREEN. Caption and subtitles are about MEDIA rather than about prose,
 * so they belong where the media is: the designer is looking at the thing being described, which is
 * the evidence the sentence has to be checked against. The alternative considered and rejected was a
 * batch surface on the AI-layers screen, and it was rejected for a concrete reason rather than a
 * stylistic one — **there is no endpoint that lists a workshop's images and videos with their stage
 * and field labels.** `GET /{id}/transcripts` is AUDIO-only (`load_transcript_items` walks
 * `audio_references`), so a batch caption screen would need a new backend route. The tile needs none.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * EACH FILE IS OFFERED ONLY THE VERB ITS TYPE ADMITS, mirroring `_VERB_MEDIA_TYPES` exactly so this
 * client never produces that 409:
 *
 *     IMAGE  ->  Describe this photograph
 *     VIDEO  ->  Describe this  AND  Make subtitles
 *     AUDIO  ->  Make subtitles
 *
 * The server's own refusal for the wrong pairing ends "Choose another file — nothing was sent
 * anywhere and nothing was spent", and it is checked before any bytes move precisely because the
 * failure otherwise is expensive and unreadable: a caption run over an audio file uploads a
 * recording to a vision model, which answers with a parse error after the credit is spent.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * **NOTHING HERE WRITES A CAPTION INTO THE FIELD'S OWN CAPTION BOX.** The registry carries 23
 * `caption_for` fields and both clients draw them directly under the media they describe, so the box
 * is right there and the temptation is obvious. It is refused for the reason written out in full in
 * `AiVerbReviewDialog`'s header: a caption written into `caption_for` is an AI value in a field
 * compared across surfaces, which plan §3 forbids and which the server cannot even express
 * (`DwStageEntry` is absent from `WRITABLE_TABLES`). The consent refusal for CAPTION already names
 * the honest alternative — *"Write the description yourself in the caption box under the
 * photograph, where the stage has one"* — and that is a designer's sentence under a designer's name.
 * This component's source is read by `frontend/e2e/ai-verbs-unit.spec.ts`, which fails if a write
 * appears in it.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * TWO SEPARATE "NOT ON THE SERVER YET" STATES, AND THEY ARE NOT INTERCHANGEABLE.
 *
 * `MEDIA_NOT_UPLOADED_YET` is about ONE FILE: a `dwlocal:` reference has no server media id, so
 * there is nothing to send even on a perfect connection, and the rest of the field's tiles are
 * unaffected. `WORKSHOP_NOT_ON_SERVER_YET` is about the WHOLE WORKSHOP: every verb route is
 * `/design-workshops/{id}/…` and a `dwlocal-…` id answers 404, so nothing on this field can run.
 * This component shipped with the second one missing, offering both verbs on every tile of every
 * unsynced workshop into a bare "Record not found" — and `loadSubtitled` swallowed the same 404 into
 * `subtitled === null`, so nothing on screen even hinted at it. See that constant for the full
 * account, including why the consent ladder structurally cannot catch it.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Captions, Image as ImageIcon, Loader2 } from "lucide-react";

import { useWorkshopConsent } from "@/components/hooks/useWorkshopConsent";
import { AiVerbReviewDialog } from "@/components/designworkshop/AiVerbReviewDialog";
import {
  AI_VERB_COUNTDOWN_FROM,
  MEDIA_NOT_UPLOADED_YET,
  SUBTITLES_DEPLOYMENT_KEY_NOTE,
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
  type DwAiVerbAllowanceState,
  type DwAiVerbResult
} from "@/lib/aiVerbs";
import { listDesignWorkshopAiLayers } from "@/lib/aiLayers";
import type { MediaFile } from "@/lib/types";

export type MediaAiVerbsProps = {
  workshopId: string;
  /** The files this field holds that the server has acknowledged, with their media type. */
  files: MediaFile[];
  /** References still only on this device — a `dwlocal:` key, its name, and a displayable URL. */
  local: { key: string; name: string; url: string }[];
  /** The stage form is read-only, or a save is in flight. */
  disabled?: boolean;
};

export function MediaAiVerbs({ workshopId, files, local, disabled }: MediaAiVerbsProps) {
  /*
    `workshopId` IS THE ROUTE PARAM AND IS NOT THE ID THAT GOES ON THE WIRE.

    It is the `dwlocal-…` draft id for the whole life of a draft, and it stays the local id after the
    draft syncs because the stage page does not redirect. `consent.serverId` is `draft.remoteId`
    resolved off the same IndexedDB read the consent comes from, and it is what every call below
    uses; the LINKS keep `workshopId`, because those are routes in this app.
  */
  const consent = useWorkshopConsent(workshopId);
  const serverId = consent.serverId;
  const [allowance, setAllowance] = useState<DwAiVerbAllowanceState | null>(null);
  const [running, setRunning] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [result, setResult] = useState<DwAiVerbResult | null>(null);
  /**
   * Which media ids already carry a live SUBTITLES layer, so the run is not offered a second time.
   *
   * Null until the read answers, and a FAILED read stays null rather than becoming an empty set: an
   * empty set would say "none of these has been subtitled", which is a confident wrong answer that
   * invites a designer to spend a paid upload they have already spent.
   */
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

  /**
   * Which of this workshop's recordings have already been subtitled.
   *
   * ASKED ONLY WHERE THERE IS AN AUDIO OR VIDEO FILE ON THIS FIELD, because most media fields hold
   * photographs and a list read per image field would be a request per tile for an answer nothing on
   * screen uses. Text is deliberately NOT requested (`includeText` is all-or-nothing and a workshop
   * can hold twenty-five interviews, which is megabytes on one bar of signal) — the ids are the
   * whole question.
   */
  const loadSubtitled = useCallback(() => {
    if (!hasTimed) return;
    // NOT ASKED AT ALL WITHOUT A SERVER COPY, rather than asked and caught. This read's `.catch`
    // leaves `subtitled` null on purpose, so the 404 from a `dwlocal-…` id was swallowed into "we do
    // not know" — an honest state for a failed read and a wrong one here, where the answer is known:
    // a workshop the server has never seen has no layers over anything.
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
        // Left null on purpose — see the state's own note. The button is still offered, because
        // withholding it on an unknown would take a capability away over a failed courtesy read.
      });
  }, [hasTimed, serverId]);

  useEffect(loadSubtitled, [loadSubtitled]);

  const granted = consent.decision.trim().toUpperCase() === "GRANTED";

  /*
    THE FOUR RUNGS, ALL OF THEM SHARED. This surface has no selection, so its ladder is exactly
    `verbWorkshopRefusal` (still reading / consent / no server copy) followed by the ceiling. `??`
    and not `||`: "" is the still-reading rung and must keep the buttons inert rather than falling
    through to the ceiling check and reading as "go ahead".

    The fifth state — this recording already has subtitles — is per FILE rather than per workshop and
    is decided beside the button it withdraws.
  */
  const blocked =
    verbWorkshopRefusal({ ready: consent.ready, serverId, decision: consent.decision }) ??
    verbAllowanceRefusal(allowance);

  async function run(verb: "CAPTION" | "SUBTITLES", mediaId: string) {
    // Re-checked at the press rather than trusted from the render: this component survives the sync
    // that gives a workshop its server id, so the id is a fact that can change under a control that
    // is still on screen — and the failure it prevents is a paid upload answered by a 404.
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
      // Read off the 201 rather than decremented by one: `_count_refused_run` spends the allowance
      // for any run that reached a provider and then failed, so an arithmetic guess drifts.
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
      // Re-read rather than assumed unchanged: a counter can move without a layer appearing.
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
        {/* NO COUNTDOWN AT ALL WHEN THERE IS NO CEILING — `aiVerbsRemaining` is null on an uncapped
            deployment, and "0 left" must never be how "no ceiling" looks. */}
        {allowance && allowance.aiVerbsRemaining !== null && allowance.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM ? (
          <span className="text-xs font-medium text-amber-800">
            {allowance.aiVerbsRemaining} run{allowance.aiVerbsRemaining === 1 ? "" : "s"} left today (
            {allowance.aiVerbDay})
          </span>
        ) : null}
      </div>

      <p className="text-xs leading-5 text-ink-500">
        A description or a set of subtitles is recorded as a LAYER over this file — it is read and accepted or declined
        by name, and it never fills in the caption box below. Write that yourself.
      </p>

      {blocked ? (
        <p className="text-xs leading-5 text-ink-500">
          {blocked}
          {consent.ready && !granted ? (
            <>
              {" "}
              <Link href={`/design-workshops/${workshopId}`} className="font-medium text-purple-700 underline">
                Open the workshop&apos;s own screen
              </Link>
              .
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
            // A document or a file type neither verb admits. Listed rather than dropped — a tile that
            // silently offers nothing is indistinguishable from a control that is broken.
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
              <div className="flex flex-wrap gap-2">
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
              </div>
              {(isAudio || isVideo) && alreadySubtitled ? (
                // THE FIFTH PRE-PRESS STATE, AND ONLY THIS VERB HAS IT. Subtitling is a second paid
                // upload of audio the system has already transcribed — the route's own docstring
                // calls that "a defect rather than a design" — so re-offering it for a file that
                // already has a cue list would spend an upload and a run for a file that has one.
                <p className="text-xs leading-5 text-ink-500">
                  This one already has subtitles.{" "}
                  <Link href={`/design-workshops/${workshopId}/ai-layers`} className="font-medium text-purple-700 underline">
                    Read them on the AI layers screen
                  </Link>{" "}
                  — running it again would send the recording up a second time and spend another run.
                </p>
              ) : (isAudio || isVideo) && !blocked ? (
                <p className="text-xs leading-5 text-ink-500">
                  {SUBTITLES_SECOND_UPLOAD_NOTE} {SUBTITLES_DEPLOYMENT_KEY_NOTE}
                </p>
              ) : null}
            </li>
          );
        })}

        {local.map((entry) => (
          // A `dwlocal:` reference has no server media id at all, and `_verb_source_media` reaches a
          // file through the workshop's OWN entries — so there is nothing to send even with a perfect
          // connection. Its own sentence, because a designer told "no connection" while the
          // connection is fine would conclude the feature is broken.
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
        // The SERVER's id: accept, decline and the subtitle download are all server routes. The
        // fallback is unreachable — a `result` exists only where a run succeeded — and is here so
        // this stays a `string` without an assertion.
        workshopId={serverId ?? workshopId}
        result={result}
        onAccepted={() => {
          setResult(null);
          loadSubtitled();
        }}
        onDeclined={() => {
          setResult(null);
          // A declined SUBTITLES layer is soft-deleted, so the media id is subtitle-able again and
          // the button must come back. Re-read rather than patched: the list is the authority on
          // which files still carry a live cue list.
          loadSubtitled();
        }}
        onClose={() => setResult(null)}
      />
    </section>
  );
}

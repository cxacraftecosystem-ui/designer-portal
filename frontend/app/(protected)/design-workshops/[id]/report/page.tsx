"use client";

/**
 * The report: choose the template, set what it contains, read it as PAGES, download the file.
 *
 * THE PREVIEW IS NOT A FIFTH RENDERER. `GET /report/preview` builds the SAME `ReportDocument` the
 * .docx writer, the server .pdf writer and the two on-device Kotlin writers consume, and
 * serialises its blocks; this page draws those blocks and reconstructs nothing. A preview that
 * walked the workshop data itself would be one more traversal of the same record and would be the
 * first of the five to drift — silently, because the person reading the preview is reading it
 * precisely so they do not have to open the file. If a block type appears that cannot be drawn,
 * that is a bug in `ReportBlock.tsx`, never a reason to rebuild the block from the stage data.
 *
 * IT IS DRAWN AS PAPER, and that is the whole shape of this screen. A scrolling column of cards
 * cannot answer the question a designer is actually asking here, which is "is this the document?"
 * Whether the cover's info table has crowded the hero photograph off the page, whether the
 * signature block has landed under a heading with nothing between them, whether a photo grid fits
 * beside its caption — every one of those is a question about a PAGE. So `ReportSheets` lays the
 * blocks onto A4 (or Letter) sheets at their real millimetre dimensions, with the cover on its own
 * page, a running head and foot on every page after it, and a visible mark at every break the
 * template declares. Approximately right is the failure mode this screen exists to avoid: the file
 * goes to a ministry.
 *
 * WHAT THE PAGINATION CAN AND CANNOT KNOW is stated on screen rather than guessed at. A
 * `PAGEBREAK` is a break the template asked for and both writers honour it exactly. Where the
 * OTHER breaks fall is decided by measuring wrapped text against the remaining height of a page,
 * which Word does on open and ReportLab does by laying the body out twice — neither is possible in
 * a browser, so the sheet count is a floor and says so. An invented "Page 7 of 26" would be quoted
 * in a covering email.
 *
 * THE FIGURES ARE DRAWN LIVE. The map is the repository's own `IndiaMap`, over the same projection
 * and the same boundary assets `/map` uses, so the report's map and the app's map cannot disagree
 * about where a place is; the charts are SVG drawn by `ReportChart`, which copies
 * `report_chart.py`'s axis, rounding, slice order and colour ramp. Both exist because a preview is
 * a screen: a designer changes a cost head and the bar moves, with no round trip to re-rasterise a
 * PNG between one keystroke and the next. The rasterised figure remains the AUTHORITY — it is what
 * the ministry receives — and where the payload carries one it is what printing uses.
 *
 * THE COLOUR IS CHOSEN HERE BECAUSE THIS IS THE ONLY PLACE THE CHOICE CAN BE SEEN. Twelve named
 * accents and a colour well sit above the sheets, and picking one redraws every sheet below it in
 * the same frame — the headings, the rules, the table headers, the zebra stripes and the live
 * figures. That is the whole justification for offering the choice at all: `lib/reportTheme` is a
 * port of the server's `report_theme.py` rather than an approximation of it, so what the pages show
 * is what the .docx will carry, and a designer never has to generate a file to find out that they
 * dislike the colour. One accent is chosen and the other seven colours are derived from it, which
 * is what makes every reachable choice a legible one — see that module. Like the transcript
 * override beside it, the value is a per-FILE choice: it is sent on the download and is not
 * written to stage 20, so trying three colours before submitting does not mean three saves.
 *
 * WITH ONE EXPLICIT EXIT, added because the override alone was a dead end. A designer could try a
 * custom colour but had no way to KEEP one — they picked it, generated the report, came back the
 * next morning and found the old colour, with nothing on screen having said the choice was
 * temporary. So the picker also offers "Save as the workshop's colour", which writes
 * `themeAccent` AND `themePreset` to stage 20 and then drops the override, because once the saved
 * colour IS the colour there is nothing left to override.
 *
 * WARNINGS ARE PART OF THE PRODUCT, not a debug aid. A missing required field or a photograph that
 * could not be embedded produces a warning, and the file is generated anyway — which is right,
 * because a designer in the field needs the twenty-six pages that ARE ready. But the warnings do
 * not travel inside the document (an officer opening the .docx next month must not find a note
 * about what was missing on the day), so this screen and the download banner are the only two
 * places they can be read. Hiding them here would mean shipping a report with four empty stages
 * while telling the designer it worked.
 *
 * SNAKE_CASE. The block payload is `dataclasses.asdict()` output and is the one part of this API
 * that is not camelCase — `width_pct`, `info_rows`, `total_row`, `height_px`. See the header of
 * `lib/designWorkshops.ts`.
 *
 * THIS PAGE IS THE ONE PART OF THE FEATURE THAT GENUINELY NEEDS A CONNECTION, and it says so up
 * front rather than failing at the click. The preview, the .docx and the .pdf are all built by the
 * server from the record the server holds; a report generated from the local draft would be a
 * different document produced by a different renderer, and this architecture already has four of
 * those that must agree line-for-line about a file a ministry receives. So when there is no
 * connection the buttons are disabled, the reason is written on the page, and the designer is
 * pointed at the Android app, which IS the offline export path. Everything they captured is still
 * on this laptop, in `lib/designWorkshopStore`; it is only the printing that has to wait.
 *
 * ⚠ THERE IS ONE THING A BROWSER CAN STILL PRINT, and the sheet stylesheet exists for it: Ctrl+P
 * on this page produces a real document rather than a screenshot of a web page — physical units,
 * `@page` at the document's own size, the app chrome switched off, tables and figures kept off
 * page boundaries. It is not the server's .pdf and does not claim to be, but it is the fastest
 * path a designer on a train has to something they can send.
 */

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { CloudOff, Download, FileText } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { countCodeSpans, useReportMediaUrls } from "@/components/designworkshop/report/ReportBlock";
import { ReportAccentPicker, resolvePreviewPalette } from "@/components/designworkshop/report/ReportAccentPicker";
import { ReportSheets } from "@/components/designworkshop/report/ReportSheet";
import type { PreviewBlock } from "@/components/designworkshop/report/previewModel";
import {
  REPORT_STAGE_KEY,
  ReportSettingsPanel,
  settingText,
  TranscriptAnnexurePanel,
  wantsTranscripts
} from "@/components/designworkshop/report/ReportSettingsPanel";
import {
  downloadDesignWorkshopReport,
  fetchStageRegistry,
  getDesignWorkshop,
  listDesignWorkshopTranscripts,
  listReportTemplates,
  overallPercent,
  previewDesignWorkshopReport,
  saveBlobToDisk,
  saveDesignWorkshopStage,
  type DwDetail,
  type DwEntryData,
  type DwPreview,
  type DwRegistry,
  type DwTemplate,
  type DwTranscriptList
} from "@/lib/designWorkshops";
import { listDesignWorkshopAiLayers } from "@/lib/aiLayers";
import { loadDraft } from "@/lib/designWorkshopStore";
import { ACCENT_PRESETS } from "@/lib/reportTheme";
import { ApiError } from "@/lib/api";
import { isUnreachable } from "@/lib/offline";

/**
 * What this file asks for on top of the saved settings, for ONE download.
 *
 * `""` means "whatever stage 20 says", which is what the server reads when the request is silent —
 * see `wants_transcripts`. The two explicit answers override it for this file only, so a designer
 * can produce a short copy to read out in a meeting and a full copy for the file from one set of
 * saved settings, without editing those settings twice and without the second edit being the one
 * they forget.
 */
type TranscriptOverride = "" | "YES" | "NO";

const TRANSCRIPT_OPTIONS = [
  { value: "", label: "As saved on stage 20" },
  { value: "YES", label: "Include the transcripts" },
  { value: "NO", label: "Leave the transcripts out" }
];

/**
 * The running head and foot the file will carry.
 *
 * MIRRORS `design_workshops.report_meta` DELIBERATELY, because the preview payload does not carry
 * them: `meta` is title, subtitle, template and page size and stops there. The server derives the
 * head from the craft and the cluster and the foot from the template name and the workshop code,
 * and a preview that drew its own furniture — the workshop's title, say — would show a designer a
 * page header that no generated file has ever had.
 *
 * Stage 20's own `headerText`/`footerText` win where they are filled in, and the page then SENDS
 * them on the download. That order is not cosmetic: `report_meta` does not read the stage entry,
 * so those two answers reach a file only through `ReportGenerateIn`. Previewing with them and
 * generating without them would put a different header on the paper than on the screen.
 */
function runningFurniture(detail: DwDetail | null, settings: DwEntryData, templateName: string) {
  const derivedHead = [detail?.craftName, detail?.clusterName].filter(Boolean).join(" — ");
  const derivedFoot = `${templateName || detail?.templateId || ""} · ${detail?.workshopCode || detail?.id || ""}`.trim();
  return {
    headerText: settingText(settings, "headerText") || derivedHead,
    footerText: settingText(settings, "footerText") || derivedFoot
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The page
 * ──────────────────────────────────────────────────────────────────────────── */

const EMPTY_SETTINGS: DwEntryData = {};

export default function DesignWorkshopReportPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [detail, setDetail] = useState<DwDetail | null>(null);
  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  const [templateId, setTemplateId] = useState<string>("");
  const [settings, setSettings] = useState<DwEntryData>(EMPTY_SETTINGS);
  const [preview, setPreview] = useState<DwPreview | null>(null);
  const [previewing, setPreviewing] = useState(true);
  const [transcripts, setTranscripts] = useState<DwTranscriptList | null>(null);
  const [transcriptOverride, setTranscriptOverride] = useState<TranscriptOverride>("");
  /**
   * Whether THIS file carries the annexure of accepted machine-assisted text.
   *
   * A PLAIN BOOLEAN, NOT A {@link TranscriptOverride}, and the asymmetry is the point. The
   * transcript control is tri-state because it OVERRIDES a saved stage-20 answer and `undefined`
   * has to mean "leave that answer alone". This one overrides nothing: no template declares the
   * section, no stage-20 field backs it, and the server splices it in on an explicit `true` alone.
   * A third value here would be a state that means the same as `false` and invite somebody to
   * store it in stage 20 later, which is where the tri-state's real complexity comes from.
   *
   * ALWAYS OFF AT FIRST DRAW, AND DELIBERATELY NOT REMEMBERED. Putting a machine's words into a
   * document that goes to a ministry officer is a decision, and a decision that persists silently
   * across exports is one nobody makes twice — they make it once and then stop seeing it.
   */
  const [includeAiLayers, setIncludeAiLayers] = useState(false);
  /**
   * How many accepted layers the annexure would print, or null while unknown.
   *
   * WHY A COUNT AND NOT A PREVIEW. `TranscriptAnnexurePanel` beside this exists because "generating
   * sixty pages to find out what is in it is not a preview", and the same argument applies here with
   * one difference: a transcript annexure's contents are unread by anybody, while every layer this
   * would print has ALREADY been read and signed for on the AI-layers screen. So the open question
   * is not "what does it say" but "is there anything there at all" — which a number answers and a
   * panel would only repeat.
   *
   * NULL IS AN HONEST STATE AND IS RENDERED AS ONE. The read can fail — offline, or a colleague with
   * workshop access but not media access — and a zero would then say "nothing has been accepted",
   * which is a claim, not an absence. It is fetched only when the box is ticked: a designer who
   * never uses this must not pay a request for it on every visit to the report screen, which is the
   * same rule the transcript list follows two effects below.
   */
  const [acceptedLayerCount, setAcceptedLayerCount] = useState<number | null>(null);
  const [acceptedLayersUnknown, setAcceptedLayersUnknown] = useState(false);
  /**
   * The accent colour for the NEXT file, empty for "whatever stage 20 and the template say".
   *
   * Per-export exactly like {@link TranscriptOverride} and for the same reason: a designer trying
   * three colours before submitting must not have to save the stage three times, and the third
   * save is the one that gets left behind on somebody else's record.
   */
  const [accentOverride, setAccentOverride] = useState("");
  const [savingAccent, setSavingAccent] = useState(false);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadWarnings, setDownloadWarnings] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  /**
   * Whether this device believes it has a connection.
   *
   * `navigator.onLine` is optimistic — a captive portal reports true while nothing routes — so it
   * is only ever used to disable a control EARLY and to explain why. A false negative would hide a
   * working button, which is why the failed request below also has to say the same thing.
   */
  const [online, setOnline] = useState(true);
  const [unsentStages, setUnsentStages] = useState(0);
  const [stage20Pending, setStage20Pending] = useState(false);
  /**
   * Which preview request is the current one.
   *
   * A counter rather than a `cancelled` flag because this fetch REFIRES on a changed selection
   * (`templateId`) rather than running once on mount — the same reason every list page in this app
   * counts generations. What matters is ignoring the late answer, not cancelling the request.
   */
  const previewGeneration = useRef(0);

  useEffect(() => {
    const read = () => setOnline(typeof navigator === "undefined" || navigator.onLine !== false);
    read();
    window.addEventListener("online", read);
    window.addEventListener("offline", read);
    return () => {
      window.removeEventListener("online", read);
      window.removeEventListener("offline", read);
    };
  }, []);

  /**
   * How much of this workshop the server has not seen yet, and whether stage 20 is part of it.
   *
   * A report is generated from the SERVER's copy, so a stage captured this morning and not yet
   * synced is simply absent from the .docx — and nothing in the file would admit it. Counting it
   * here is what turns a mysteriously thin report into a sentence a designer can act on.
   *
   * Stage 20 is singled out because the settings panel writes to it: a save made here while the
   * device holds unsynced edits to the same stage would be silently overwritten minutes later by
   * the sync pass, with the designer looking at the value they chose.
   */
  useEffect(() => {
    let cancelled = false;
    void loadDraft(id).then((draft) => {
      if (cancelled || !draft) return;
      setUnsentStages(
        Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0).length
      );
      const stage = draft.stages[REPORT_STAGE_KEY];
      setStage20Pending(Boolean(stage && (stage.dirtyAt !== null || stage.removedFrom.length > 0)));
    });
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [nextRegistry, nextDetail, nextTemplates] = await Promise.all([
          fetchStageRegistry(),
          getDesignWorkshop(id),
          listReportTemplates()
        ]);
        if (cancelled) return;
        setRegistry(nextRegistry);
        setDetail(nextDetail);
        setTemplates(nextTemplates);
        // The whole workshop arrives with its stages, so the settings need no second request.
        setSettings(nextDetail.stages?.[REPORT_STAGE_KEY]?.singleton ?? EMPTY_SETTINGS);
        // The workshop's own stored template is the starting point, not the first in the list — a
        // designer who chose the photo catalogue last week must not find themselves back on the
        // DCH format merely by opening this page.
        setTemplateId(nextDetail.templateId);
      } catch (err) {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const loadPreview = useCallback(
    async (template: string) => {
      /*
        THE GENERATION GUARD, WITHOUT WHICH THE SCREEN CAN SHOW ONE TEMPLATE AND NAME ANOTHER.

        This is the house convention for a fetch that refires on a changed selection — a counter,
        not a `cancelled` flag (that is for a one-shot mount effect) and not an AbortSignal (this
        call takes none). It was missing, and the failure is precisely the one this page exists to
        prevent: pick "Photo catalogue", then pick "Compact summary" before the first response
        lands, and the older answer arrives second and replaces the newer one. "Refreshing…" is
        already gone, so the dropdown, its description and both download buttons all say Compact
        summary while the A4 sheets on screen are the Photo catalogue document. A designer reads
        this preview INSTEAD of opening the generated file; approving a preview of a different
        template than the one about to be generated is the whole failure mode.

        Every setter below is guarded, including the one in `finally`: a late failure clearing
        `previewing` would take the "Refreshing…" state off a request that is still in flight.
      */
      const mine = ++previewGeneration.current;
      setPreviewing(true);
      try {
        const next = await previewDesignWorkshopReport(id, template || undefined);
        if (mine !== previewGeneration.current) return;
        setPreview(next);
        setError(null);
      } catch (err) {
        if (mine !== previewGeneration.current) return;
        setError(
          // `isUnreachable`, not `isTransient`: a 5xx means the server WAS reached and then failed,
          // and telling a designer their connection is at fault sends them to look at their signal
          // while the real fault sits in a response nobody sees. The download handler below already
          // made this split by hand — see its note — and this is the same rule.
          isUnreachable(err)
            ? "The preview is built by the server, so it cannot be refreshed without a connection. Everything you have captured is " +
                "safe on this device; the report can be generated as soon as there is signal, or on the Android app, which " +
                "generates it on the handset."
            : err instanceof Error
              ? err.message
              : "Unable to build the report preview"
        );
        // The previous preview is deliberately kept on screen. Blanking it on a failed refresh
        // replaces a document the designer can still read with nothing, and "the report is empty"
        // is a far worse thing to believe than "the refresh failed".
      } finally {
        if (mine === previewGeneration.current) setPreviewing(false);
      }
    },
    [id]
  );

  useEffect(() => {
    if (!templateId) return;
    loadPreview(templateId);
  }, [templateId, loadPreview]);

  /**
   * Re-read the workshop after the settings have been written.
   *
   * THE SERVER'S COPY, NOT THE ONE THAT WAS POSTED. `coerce_value` normalises on the way in — a
   * number typed into a text box, a date, a tag list with an empty member — so the entry that
   * comes back is not always the entry that went out, and seeding the form from what was sent
   * would leave a designer looking at a value the record does not hold. It also refreshes the
   * completeness figures, which stage 20 contributes to.
   *
   * A failure here is deliberately silent: the save itself succeeded and said so, and a banner
   * reporting that the re-read failed would read as the save having failed.
   */
  const refreshWorkshop = useCallback(async () => {
    try {
      const next = await getDesignWorkshop(id);
      setDetail(next);
      setSettings(next.stages?.[REPORT_STAGE_KEY]?.singleton ?? EMPTY_SETTINGS);
    } catch {
      /* the save is what mattered, and it reported itself */
    }
  }, [id]);

  /**
   * The recordings, fetched only when the annexure is actually in play.
   *
   * A workshop can hold forty recordings and the join that resolves them is not free, so a report
   * nobody is appending transcripts to must not pay for the list. Both the saved answer and the
   * per-file override arm it, because "Include the transcripts" is exactly the moment somebody
   * wants to know what they are about to include.
   */
  const annexureWanted = transcriptOverride === "YES" || (transcriptOverride === "" && wantsTranscripts(settings));

  useEffect(() => {
    if (!annexureWanted) return;
    let cancelled = false;
    void listDesignWorkshopTranscripts(id)
      .then((next) => {
        if (!cancelled) setTranscripts(next);
      })
      // Silent: the annexure is a supporting panel, and an error banner over a list nobody asked
      // to see would be louder than the thing it is reporting. The download's own warnings are
      // where a genuinely missing transcript is named.
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [id, annexureWanted]);

  /**
   * How many accepted layers the machine-assisted annexure would carry — read only once the box is
   * ticked, exactly as the transcript list above is read only once that annexure is in play.
   *
   * THE FAILURE IS NOT SILENT HERE, and that is the difference from the effect above. A transcript
   * list that fails to load costs a supporting panel nobody asked for. This number stands next to a
   * decision about whether model prose enters a submitted document, and a silent failure would
   * leave the sentence beside the checkbox reading as though it had counted and found nothing. So a
   * failed read is recorded as UNKNOWN and said in those words.
   */
  useEffect(() => {
    if (!includeAiLayers) return;
    let cancelled = false;
    setAcceptedLayersUnknown(false);
    void listDesignWorkshopAiLayers(id)
      .then((next) => {
        if (cancelled) return;
        setAcceptedLayerCount(next.accepted);
      })
      .catch(() => {
        if (cancelled) return;
        // NOT setAcceptedLayerCount(0). Zero is a claim that nothing has been accepted; this is the
        // absence of an answer, and the two send a designer to different places — one to the AI
        // layers screen to accept something, the other to their connection.
        setAcceptedLayerCount(null);
        setAcceptedLayersUnknown(true);
      });
    return () => {
      cancelled = true;
    };
  }, [id, includeAiLayers]);

  const templateName = templates.find((template) => template.id === templateId)?.name ?? preview?.meta.templateName ?? "";
  const { headerText, footerText } = useMemo(
    () => runningFurniture(detail, settings, templateName),
    [detail, settings, templateName]
  );

  /**
   * The paper. Stage 20's answer wins over the template's, because that is the order the file will
   * resolve it in once the page sends `pageSize` on the download — and a preview laid out on A4
   * beside a Letter file is a preview of a different document.
   */
  const pageSize = settingText(settings, "pageSize") || preview?.meta.pageSize || "A4";

  /**
   * The colour the report is written in, resolved the way the server resolves it.
   *
   * `report_theme.resolve_accent` reads the request first and the saved stage-20 answer second, so
   * this does too — a preview resolved in a different order is a preview of a document nobody will
   * receive. `themeAccent` is the authority on the stage and `themePreset` is read only when it is
   * blank, which is what lets a designer store the NAME they chose and still have the hex win.
   */
  const savedAccent = useMemo(() => {
    const hex = settingText(settings, "themeAccent");
    if (hex) return hex;
    const preset = settingText(settings, "themePreset");
    return ACCENT_PRESETS.find((option) => option.key === preset)?.hex ?? "";
  }, [settings]);

  const {
    palette,
    accent: previewAccent,
    chosen: accentChosen
  } = useMemo(() => resolvePreviewPalette(accentOverride, savedAccent), [accentOverride, savedAccent]);

  /**
   * The blocks, widened to the union this page can draw.
   *
   * `DwBlock` is the shared client's union and predates `MapBlock` and `ChartBlock`;
   * `PreviewBlock` widens it with those two and with the resolved rich-text form. The assignment
   * needs no cast in that direction, and every renderer still switches exhaustively.
   */
  const blocks: PreviewBlock[] = useMemo(() => preview?.blocks ?? [], [preview]);
  const mediaUrls = useReportMediaUrls(blocks);
  const codeSpans = useMemo(() => countCodeSpans(blocks), [blocks]);

  async function download(format: "DOCX" | "PDF") {
    setDownloading(format);
    setError(null);
    setDownloadWarnings(null);
    try {
      const file = await downloadDesignWorkshopReport(id, {
        templateId: templateId || null,
        formats: [format],
        // THE STAGE-20 ANSWERS ONLY, AND ONLY WHERE THEY WERE FILLED IN. `report_meta` does not
        // read these three off the stage entry, so they reach a file only through this request —
        // but where the designer left them blank the SERVER's own derivation must stand, and
        // sending this page's mirror of it instead would let a one-character drift in that mirror
        // silently become the header printed on the paper. Omitted means "yours is right".
        pageSize: settingText(settings, "pageSize") || undefined,
        headerText: settingText(settings, "headerText") || undefined,
        footerText: settingText(settings, "footerText") || undefined,
        // Sent only when this page is overriding. Omitted, the server reads stage 20 itself and
        // then falls back to the template's own palette — and sending this page's resolution of
        // that instead would make a one-character drift in the mirror above into the colour the
        // ministry receives.
        themeAccent: accentOverride || undefined,
        // `undefined` means "whatever stage 20 says"; sending `false` for it would strip an
        // annexure the designer had already asked for and saved.
        includeTranscripts: transcriptOverride === "" ? undefined : transcriptOverride === "YES",
        // THE SWITCH THAT WAS MISSING, and its absence was the whole defect rather than a rough
        // edge. `report_ai_layers` renders the annexure, `ReportBuilder.build` has the branch,
        // `attach_report_ai_layers` loads it and `apply_report_settings` splices the section — and
        // no client sent the flag, so `ReportGenerateIn.includeAiLayers` took its default of false
        // on every report either app has ever produced. That is the shape this repository has
        // already shipped once: the transcript annexure was a complete, tested module with no call
        // site, and every report silently dropped it while three surfaces promised the office's
        // copy would carry it. Sent unconditionally rather than only when true, so the request says
        // what the designer chose rather than leaving the server to infer it from silence.
        includeAiLayers
      });
      saveBlobToDisk(file.blob, file.fileName);
      // Shown whether or not there were any: "generated with no warnings" is information, and a
      // banner that appears only on failure trains people to read its absence as nothing happening.
      setDownloadWarnings(file.warnings);
    } catch (err) {
      // NOT `isTransient` here, deliberately — see `e2e/report-download.spec.ts`.
      //
      // `isTransient` answers "is it worth retrying", and it counts every 5xx as yes. That is the
      // right answer for the outbox and the wrong one for this message, because a 5xx means the
      // server was REACHED and then failed. Telling the designer their connection is at fault when
      // the server answered is a lie that sends them to look at their signal: a real bug
      // (`ReportMeta` has no `__dict__`, so any saved page size 500'd) hid behind this sentence and
      // was reported as an offline problem.
      //
      // The honest split is by whether the server spoke at all. An ApiError means it did — show
      // what it said. Anything else is a fetch that never completed, which IS the offline case.
      const serverAnswered = err instanceof ApiError;
      setError(
        serverAnswered
          ? `The server could not generate the ${format}: ${(err as ApiError).message} ` +
              "The workshop itself is safe — nothing you have entered was affected."
          : `The ${format} is written by the server, so it cannot be generated without a connection. Nothing has been lost — ` +
              "the workshop is on this device and the file can be generated the moment there is signal. The Android app " +
              "generates the same document on the handset if you need it before then."
      );
    } finally {
      setDownloading(null);
    }
  }

  /**
   * The stages that are not finished, named.
   *
   * The preview's own `warnings` are the authority on what the DOCUMENT is missing; this is the
   * complementary view — which of the 22 stages still has unfilled Basic-tier fields — because a
   * designer looking at a thin report wants to know which stage to go and open, and a warning that
   * says "Cost sheet: Unit cost is required" does not say where that lives.
   */
  const incomplete = useMemo(() => {
    if (!registry || !detail) return [];
    return registry.stages
      .map((stage) => ({ stage, score: detail.completeness?.[stage.key] }))
      .filter((entry) => entry.score && !entry.score.isComplete);
  }, [registry, detail]);

  const percent = overallPercent(detail?.completeness);

  return (
    <>
      <PageHeader
        title="Report"
        description={
          detail
            ? `Generated from “${detail.title}” — the same document the .docx and .pdf are written from.`
            : "Loading…"
        }
        icon={<FileText className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All stages
          </Link>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      <section className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-[minmax(0,22rem)_1fr] md:items-end">
          {/* FieldBlock, not Field: a <label> around a themed dropdown forwards a stray click into
              the menu and closes it after one pick. */}
          <FieldBlock label="Report template">
            <Dropdown
              value={templateId}
              onChange={setTemplateId}
              options={templates.map((template) => ({ value: template.id, label: template.name }))}
              ariaLabel="Report template"
              // This dropdown reconfigures the screen it sits on, so focus must stay on it rather
              // than jumping to whatever control happens to follow.
              advanceOnSelect={false}
            />
          </FieldBlock>
          <p className="text-sm leading-6 text-ink-muted">
            {templates.find((template) => template.id === templateId)?.description ??
              "Choosing a template changes the sections, their order and the page size — the workshop data is unchanged."}
          </p>
        </div>

        <div className="grid gap-3 md:grid-cols-[minmax(0,22rem)_1fr] md:items-end">
          <FieldBlock label="Transcripts in this file">
            <Dropdown
              value={transcriptOverride}
              onChange={(value) => setTranscriptOverride(value as TranscriptOverride)}
              options={TRANSCRIPT_OPTIONS}
              ariaLabel="Transcripts in this file"
              advanceOnSelect={false}
            />
          </FieldBlock>
          <p className="text-sm leading-6 text-ink-muted">
            {transcriptOverride === ""
              ? wantsTranscripts(settings)
                ? "Stage 20 asks for the transcript annexure, so this file will carry it. The preview below includes it."
                : "Stage 20 leaves the transcripts out. Overriding here changes this one file and not the saved setting."
              : transcriptOverride === "YES"
                ? "This file only. The preview below is built from the saved setting, so the annexure may not appear in it."
                : "This file only. The saved setting is untouched and the next report will follow it again."}
          </p>
        </div>

        {/*
          THE MACHINE-ASSISTED-TEXT ANNEXURE. A checkbox rather than a dropdown, because unlike the
          transcript control above it overrides no saved answer — there is no "leave stage 20 alone"
          state for it to carry, so three options would be two that mean the same thing.

          THE SENTENCE UNDER IT IS THE CONTROL. What is being agreed to is that prose a model wrote
          enters a document somebody signs, and the one protection against that being done
          absent-mindedly is that nothing unaccepted can print: a person has already read each
          passage against its source and put their name to it on the AI-layers screen. Saying so
          here is what makes this tick a confirmation rather than a preference.
        */}
        <div className="grid gap-3 md:grid-cols-[minmax(0,22rem)_1fr] md:items-start">
          <label className="flex items-start gap-2.5 text-sm font-medium leading-6 text-ink-900">
            <input
              type="checkbox"
              className="mt-1 h-4 w-4 rounded border-line-300 text-purple-700"
              checked={includeAiLayers}
              onChange={(event) => setIncludeAiLayers(event.target.checked)}
            />
            <span>Include machine-assisted text</span>
          </label>
          <div className="grid gap-1.5 text-sm leading-6 text-ink-muted">
            <p>
              {includeAiLayers
                ? "This file will carry an annexure of the transcripts, summaries and readings a person has accepted, each named as machine-assisted and each printed with the model that produced it and the person who accepted it. Nothing unaccepted is printed."
                : "Off. Transcripts, summaries and readings produced automatically are left out of this file entirely. Accept them on the workshop's AI layers screen first; what is accepted there is what this can print."}
            </p>
            {/*
              WHAT THE TICK WILL ACTUALLY DO, said before the sixty pages rather than after them.
              Three states and not two: a number, an honest unknown, and nothing at all while the box
              is unticked. The unknown matters more than the number — a read can fail because the
              designer is offline or because they hold the workshop but not the recordings, and a
              "0" in either case would tell them nobody has accepted anything, which is a claim
              rather than an absence and sends them to the wrong screen.
            */}
            {includeAiLayers ? (
              <p aria-live="polite" className="font-medium text-ink-700">
                {acceptedLayersUnknown ? (
                  <>
                    This page could not read the workshop&rsquo;s layers just now, so it cannot say how many would be
                    printed. The report will still carry whatever is accepted — open{" "}
                    <Link href={`/design-workshops/${id}/ai-layers`} className="underline">
                      AI layers
                    </Link>{" "}
                    to check.
                  </>
                ) : acceptedLayerCount === null ? (
                  "Counting what has been accepted…"
                ) : acceptedLayerCount === 0 ? (
                  // SAYS "NO ANNEXURE" AND DOES NOT PROMISE A NOTE. An earlier draft of this line
                  // said the file would come back "with a note beside the download saying why", and
                  // that is only true when layers EXIST and are unaccepted — `annexure_warnings`
                  // counts unaccepted and empty items, so a workshop with no layers at all hands it
                  // an empty list and it returns nothing. Promising a note that does not arrive is
                  // how a designer concludes the tick did nothing at all, which is the failure this
                  // whole count exists to prevent. The count is already on screen and is the answer.
                  <>
                    Nothing has been accepted, so this file will carry no annexure — ticking the box changes nothing
                    until something is. Accept what belongs in it on{" "}
                    <Link href={`/design-workshops/${id}/ai-layers`} className="underline">
                      AI layers
                    </Link>
                    .
                  </>
                ) : (
                  <>
                    {acceptedLayerCount} accepted {acceptedLayerCount === 1 ? "layer" : "layers"} will be printed.{" "}
                    <Link href={`/design-workshops/${id}/ai-layers`} className="underline">
                      Read them first
                    </Link>{" "}
                    if you have not — the preview below does not show this annexure.
                  </>
                )}
              </p>
            ) : null}
          </div>
        </div>

        {/* Between the two dropdowns and the download buttons, because it is the last decision
            taken before the file is generated and the first one visible in the pages below. */}
        <div className="border-t border-line-200 pt-4">
          <ReportAccentPicker
            value={accentOverride}
            onChange={setAccentOverride}
            palette={palette}
            savedAccent={savedAccent}
            saving={savingAccent}
            /*
              KEEPING a colour, as opposed to trying one. The twelve swatches are a per-file
              override by design — three colours before submitting must not mean three saves — but
              there was no way to make one stick, so a designer who chose a custom colour found the
              old one again the next morning with nothing having said the choice was temporary.

              This is also where the colour panel's own commit lands, confirmed or dismissed: a
              designer only opens that panel because they have been handed a brand colour and mean
              to keep it, and "backing out of the panel loses the colour" was the report that
              rebuilt it. See the picker's header.

              Writes BOTH keys, because `resolve_accent` reads the hex first and the preset only
              when the hex is missing: writing the name alone would resolve to nothing for "Custom
              colour", which is not in `PRESETS_BY_KEY`, and writing the hex alone would lose the
              name the designer recognises when they come back to the screen.
            */
            onSave={async (accent) => {
              const hex = accent.trim().toUpperCase();
              setSavingAccent(true);
              setError(null);
              try {
                await saveDesignWorkshopStage(id, REPORT_STAGE_KEY, {
                  entries: [
                    {
                      entityKey: "reportSettings",
                      data: {
                        ...settings,
                        themeAccent: hex,
                        themePreset: ACCENT_PRESETS.find((p) => p.hex.toUpperCase() === hex)?.key ?? "CUSTOM"
                      }
                    }
                  ]
                });
                // RE-READ FIRST, THEN DROP THE OVERRIDE, and not the other way round. The saved
                // colour IS the colour now, so keeping the override would show "overriding the
                // saved colour" against an identical value — but clearing it before `savedAccent`
                // has caught up leaves the page resolving to the PREVIOUS colour for the length of
                // a network round trip, which the sheets below repaint in and then repaint out of.
                // Every commit out of the colour panel comes through here, so that flash would be
                // the ordinary experience of choosing a colour rather than a rare one.
                await refreshWorkshop();
                setAccentOverride("");
                if (templateId) void loadPreview(templateId);
              } catch (err) {
                setError(err instanceof Error ? err.message : "Unable to save the report colour");
              } finally {
                setSavingAccent(false);
              }
            }}
          />
        </div>

        {!online ? (
          // SAID BEFORE THE CLICK, not after it. A designer who presses Download and gets an error
          // has already decided the app is broken; a disabled button with the reason beside it is a
          // fact about the world, and it names the thing that DOES work offline.
          <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <p className="flex items-start gap-1.5 font-semibold">
              <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
              The report needs a connection
            </p>
            <p className="mt-1 leading-6">
              The preview, the .docx and the .pdf are all produced by the server from the record it holds, and this browser
              deliberately has no renderer of its own — a fifth one would eventually disagree with the four that write the file a
              ministry receives. Everything you have captured is safe on this device and nothing is waiting on you. Generate the
              report once there is signal, or use the Android app, which writes the same .docx and .pdf on the handset. The
              pages below can still be printed from this browser with Ctrl+P.
            </p>
          </div>
        ) : null}

        {unsentStages ? (
          <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            {unsentStages} stage{unsentStages === 1 ? " is" : "s are"} saved on this device only and{" "}
            {unsentStages === 1 ? "has" : "have"} not reached the repository yet. The report is generated from the server&apos;s
            copy, so anything in {unsentStages === 1 ? "that stage" : "those stages"} will be missing from the file until it
            syncs — and the file itself will not say so.
          </div>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="field-button"
            disabled={downloading !== null || !online}
            onClick={() => download("DOCX")}
          >
            <Download className="h-4 w-4" aria-hidden />
            {downloading === "DOCX" ? "Generating…" : "Download .docx"}
          </button>
          <button
            type="button"
            className="field-button-secondary"
            disabled={downloading !== null || !online}
            onClick={() => download("PDF")}
          >
            <Download className="h-4 w-4" aria-hidden />
            {downloading === "PDF" ? "Generating…" : "Download .pdf"}
          </button>
          {/* Prints the sheets below through the browser's own engine. Named as what it is — a
              stand-in — because it is laid out by this page and not by the server's writers. */}
          <button type="button" className="field-button-secondary" onClick={() => window.print()}>
            Print these pages
          </button>
          {preview ? (
            <span className="text-xs text-ink-500">
              {preview.meta.templateName} · {pageSize} · {preview.blocks.length} blocks
              {previewAccent ? ` · ${previewAccent.toUpperCase()}` : ""}
            </span>
          ) : null}
        </div>

        {downloadWarnings ? (
          downloadWarnings.length ? (
            <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
              <p className="font-semibold">
                The file was generated with {downloadWarnings.length} warning{downloadWarnings.length === 1 ? "" : "s"}.
              </p>
              <ul className="mt-1 ml-5 list-disc space-y-0.5">
                {downloadWarnings.map((warning, index) => (
                  <li key={index}>{warning}</li>
                ))}
              </ul>
              {/* Stated because the header is capped at 900 characters server-side, and a list that
                  quietly stops is indistinguishable from a list that ended. */}
              <p className="mt-1 text-xs">
                Long warning lists are truncated in transit — the preview warnings below are the complete set.
              </p>
            </div>
          ) : (
            <p className="rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm text-success-600">
              The file was generated with no warnings.
            </p>
          )
        ) : null}
      </section>

      <ReportSettingsPanel
        workshopId={id}
        registry={registry}
        settings={settings}
        online={online}
        draftPending={stage20Pending}
        onSaved={() => {
          // The document the server builds has just changed, so the pages below are stale until
          // they are rebuilt. Two round trips is cheap next to a designer approving a preview of
          // the settings they replaced a moment ago.
          void refreshWorkshop();
          if (templateId) void loadPreview(templateId);
        }}
      />

      {annexureWanted ? <TranscriptAnnexurePanel transcripts={transcripts} /> : null}

      {/* Completeness next, because it is what decides whether the preview below is worth reading. */}
      <section className="panel mb-5 grid gap-3 p-4">
        <div>
          <div className="flex items-center justify-between text-sm">
            <span className="font-medium text-ink-900">Required fields across all 22 stages</span>
            <span className="text-ink-muted">{percent}%</span>
          </div>
          <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-field-200">
            <div className="h-full rounded-full bg-purple-700" style={{ width: `${percent}%` }} />
          </div>
        </div>
        {incomplete.length ? (
          <div>
            <p className="text-sm font-medium text-ink-900">
              {incomplete.length} stage{incomplete.length === 1 ? "" : "s"} still {incomplete.length === 1 ? "has" : "have"}{" "}
              unfilled required fields. The report will generate without them, and the sections they feed will be thin.
            </p>
            <ul className="mt-2 grid gap-1">
              {incomplete.map(({ stage, score }) => (
                <li key={stage.key} className="text-sm">
                  <Link
                    href={`/design-workshops/${id}/stages/${stage.key}`}
                    className="font-medium text-purple-700 underline-offset-2 hover:underline"
                  >
                    {stage.number}. {stage.title}
                  </Link>
                  <span className="text-ink-500">
                    {" "}
                    — {score?.requiredFilled} of {score?.requiredTotal}
                    {score?.missing.length ? `: ${score.missing.slice(0, 4).join(", ")}` : ""}
                    {/* Every truncation says so. */}
                    {score && score.missing.length > 4 ? ` and ${score.missing.length - 4} more` : ""}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <p className="text-sm text-ink-700">Every required field in all 22 stages is filled in.</p>
        )}
      </section>

      {preview?.warnings.length ? (
        <section className="mb-5 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          <p className="font-semibold">
            {preview.warnings.length} thing{preview.warnings.length === 1 ? "" : "s"} the report cannot print as it stands
          </p>
          <ul className="mt-1 ml-5 list-disc space-y-0.5">
            {preview.warnings.map((warning, index) => (
              <li key={index}>{warning}</li>
            ))}
          </ul>
        </section>
      ) : null}

      {codeSpans ? (
        // A mark the screen can show and the file cannot. `report_model.Run` has no monospace flag,
        // so `_runs_for` cannot carry CODE into the .docx or the .pdf — the words survive, the face
        // does not. Drawing it without saying so would be the preview lying about the file.
        <section className="mb-5 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {codeSpans} run{codeSpans === 1 ? " is" : "s are"} marked as code below. That mark is shown here and is NOT carried
          into the .docx or the .pdf — the words appear in the file as ordinary text.
        </section>
      ) : null}

      {/* `rp-host` is what the sheet stylesheet's print block puts back after it hides every other
          child of <main>, and what it strips the panel chrome off. Renaming it silently prints a
          blank page. */}
      <section className="panel rp-host p-4 sm:p-6">
        {preview === null ? (
          <p className="text-sm text-ink-700">{previewing ? "Building the preview…" : "No preview available."}</p>
        ) : (
          <>
            <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2 border-b border-line-200 pb-3" data-rp-noprint>
              <h2 className="font-display text-lg font-bold text-ink-900">{preview.meta.title}</h2>
              {preview.meta.subtitle ? <p className="text-sm text-ink-muted">{preview.meta.subtitle}</p> : null}
              {/* Says out loud that the preview is stale while a new template is being fetched,
                  rather than leaving the old document on screen under a new template's name. */}
              {previewing ? <span className="text-xs text-ink-500">Refreshing…</span> : null}
            </div>
            <ReportSheets
              blocks={blocks}
              pageSize={pageSize}
              headerText={headerText}
              footerText={footerText}
              mediaUrls={mediaUrls}
              palette={palette}
              paletteChosen={accentChosen}
            />
          </>
        )}
      </section>
    </>
  );
}

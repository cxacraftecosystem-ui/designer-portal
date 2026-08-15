"use client";

/**
 * The layering law's one screen: accept, withdraw or decline what a model produced.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS A WORKSHOP-LEVEL PAGE AND NOT A PANEL ON THE REPORT SCREEN. The report screen was the
 * obvious home — it already carries `TranscriptAnnexurePanel`, which previews exactly the kind of
 * material this page governs, and it is where a designer decides what a document will contain. It was
 * rejected for four reasons, and the first two are the ones that would have hurt.
 *
 * 1. **THE REPORT SCREEN IS A PER-FILE SCREEN, AND AN ACCEPTANCE IS PERMANENT AND SIGNED.** Every
 *    control on that page says so in its own comment: the transcript override is "this file only",
 *    the accent picker is a per-export choice because "three colours before submitting must not mean
 *    three saves", and neither writes anything until a separate, explicit Save. A designer working
 *    there is in a frame of mind where nothing sticks. Accepting is the opposite in every respect — it
 *    writes a named person and a moment into a table, the server refuses to let a second person
 *    overwrite that name, and a report generated afterwards prints it. Putting the one irreversible,
 *    signed act among three deliberately disposable toggles is a register mismatch, and this
 *    repository already knows where that ends: it trains people to click.
 * 2. **THE REPORT SCREEN CANNOT ASK FOR THE ANNEXURE, SO A PANEL THERE WOULD CLAIM A CONNECTION THAT
 *    IS NOT WIRED.** The annexure itself exists — `services/report_ai_layers.py` renders it,
 *    `report_builder` calls it for the ANNEXURE_AI_LAYERS section, and `apply_report_settings`
 *    splices that section in when a generate request carries `includeAiLayers`.
 *
 *    **CORRECTED AGAIN, SAME DAY: THE SWITCH NOW EXISTS.** This paragraph said "what no client sends
 *    is the flag: `grep includeAiLayers` finds nothing in `frontend/` and nothing in `android/`", and
 *    that was true when it was written and false an hour later — the report screen gained an
 *    "Include machine-assisted text" checkbox that sends it. The ARGUMENT FOR THIS PAGE SURVIVES the
 *    correction, which is why the page did not move: reasons 1, 3 and 4 below stand on their own, and
 *    reason 2 is now the narrower and better one — the report screen ASKS for the annexure, and this
 *    screen is where the accepting is done. A panel that did both in one place would put a signature
 *    and a download behind the same scroll position.
 *
 *    The correction is left visible rather than tidied away because it is the second time this same
 *    sentence has had to be repaired, and both times the cause was identical: a claim about what the
 *    rest of the system does, written from a `grep` taken at one moment, in a tree three other lanes
 *    were editing. A statement of the form "nothing anywhere does X" ages badly by construction.
 *    (An earlier draft of this note claimed the annexure had not been built at all and that `grep`
 *    for `accepted_layers` found nothing outside the service — both false, and the panel repeated it
 *    to the designer as "no generated .docx or .pdf carries one today ... so that the annexure has
 *    something to print when it arrives", which told the person signing that their signature reached
 *    no document. Checked against the code and corrected in both places.)
 * 3. **LAYERS ARE NOT ONLY TRANSCRIPTS, AND THEY SPAN THE WHOLE WORKSHOP.** The vocabulary has two
 *    chains: audio to transcript to cleaned transcript to summary, and photograph to OCR text to
 *    structured fields — plus tags and metadata over either. Recordings and photographs hang off
 *    audio and image fields in any of the 22 stages. This is the same argument the "Import
 *    photographs" link on the workshop page makes in its own comment ("a camera dump spans the whole
 *    fortnight"), and it lands here identically.
 * 4. **THE REPORT SCREEN'S AUDIENCE IS WIDER THAN THIS PAGE'S ACTIONS.** A report can be previewed by
 *    anybody who may READ the workshop. Accepting needs `_require_designer` AND edit rights on the
 *    workshop AND permission to read the recording the layer stands on — three separate gates, the
 *    last of which is decided per media file rather than per workshop. A panel whose primary control
 *    refuses a large part of the page's readership is furniture on that page and the point of this
 *    one.
 *
 * HOW THE TWO SCREENS DIVIDE THE WORK, which is the only thing worth stating here because it is a
 * property of THIS page rather than a report on another one:
 *
 *   this screen   reads each layer against its source, and accepts or declines it — a signature
 *   the report    decides whether a given file carries what was accepted, and how many that is
 *
 * That split is why neither screen swallowed the other. Accepting is irreversible in the sense that
 * matters (an acceptance a report has already printed cannot be un-printed, which is why withdrawal
 * appends to a log rather than erasing the acceptance), and it needs the layer's text beside its
 * source. Choosing what one file carries is a per-export decision made minutes before a download.
 * Putting both behind the same scroll position would have a designer signing on the way to a button.
 *
 * (A note that used to live here listed what the report screen "still owed" this one. It has been
 * wrong twice within a day — first about the annexure, then about the switch — because a paragraph
 * describing another part of a system, written from a `grep` taken at one moment, ages badly by
 * construction. What replaces it is the sentence above, which is about the division of
 * responsibility and stays true whatever either screen grows next.)
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * NO ROUTE GUARD ROW IS ADDED, AND THAT IS NOT AN OVERSIGHT. `routeMatches` in `lib/permissions.ts`
 * matches whole segments and their descendants, so the existing `/design-workshops` guard
 * (`canRunDesignWorkshops`, mirroring `can_run_design_workshops` in `deps.py`) already covers this
 * URL. A second rule with the same predicate would be one more place to forget when the predicate
 * changes.
 *
 * NOTHING HERE IS AVAILABLE OFFLINE, and the panel says so through the server's own refusal rather
 * than by pretending. Layers are a server table with no local mirror: unlike the stage forms, the
 * readiness screen and the market findings — all of which compute from the IndexedDB draft — there is
 * nothing on this device to read. Banking an acceptance in the outbox was considered and rejected: an
 * acceptance is a signature, the server refuses a second one, and a queued signature that replays
 * three days later against a layer somebody else has since declined would report success for an act
 * that did not happen.
 */

import { use } from "react";
import Link from "next/link";
import { Layers } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { AiLayersPanel } from "@/components/designworkshop/AiLayersPanel";

export default function DesignWorkshopAiLayersPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  return (
    <>
      <PageHeader
        title="AI layers"
        description="Everything a model produced from this workshop's recordings and photographs — what it was made from, which machine ran it, and whether a person has put their name to it."
        icon={<Layers className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All 22 stages
          </Link>
        }
      />

      <AiLayersPanel workshopId={id} />
    </>
  );
}

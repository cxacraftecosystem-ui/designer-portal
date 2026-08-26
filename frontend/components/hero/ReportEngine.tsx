"use client";

import { motion, type Variants } from "framer-motion";
import { FileText, FileWarning, MonitorPlay, Palette, ScrollText, Smartphone } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";
import { ACCENT_PRESETS } from "@/lib/reportTheme";

/**
 * The payoff the headline at the top of this page promises: "The report is already written."
 *
 * ── WHY THE SIX ARE WORTH A SECTION ON A MARKETING PAGE ───────────────────────────────────────
 *
 * "It exports to Word and PDF" is what every tool on this shelf says. What is actually hard, and
 * what is actually true here, is that SIX renderers draw one document and have to agree line for
 * line — three of them running on a handset with no network, none of them re-deriving the document
 * from the record. That is the claim a designer cares about, because it is the difference between
 * proofing a preview and proofing the file a ministry receives.
 *
 * ── EVERY CLAIM, AND THE FILE IT WAS READ OUT OF ──────────────────────────────────────────────
 *
 * THE WRITERS. `report_model.py`'s header names them as files: `report_docx.py` (OOXML into a zip,
 * no third-party dependency), `report_pdf.py` (ReportLab), and `DocxWriter.kt` / `PdfWriter.kt` on
 * Android, with `ReportModel.kt` as the port of the model. The waist of the hourglass is
 * `ReportDocument` — "a plain tree of frozen dataclasses describing *what the report says*", saying
 * nothing about how any of it is drawn.
 *
 * THE COUNT IS SIX, AND THE SENTENCE THAT SAYS FIVE IS STILL RIGHT WHERE IT STANDS — DO NOT
 * "CORRECT" IT. `backend/app/services/report_builder.py`'s stage-4 gallery argument says a block
 * "was already drawn by ALL FIVE renderers — the server .docx writer, the server .pdf writer, the
 * web preview and both on-device Kotlin writers." That is a HISTORICAL claim about what ALREADY
 * drew the caption before that change, not a census of this list: the sixth,
 * `android/…/ui/designworkshop/DwReportPreview.kt`, was written in the same session and therefore
 * cannot have been one of the five that already drew it. Rewriting it to six would make a true
 * sentence false, which is why this list is the place the count lives.
 *
 * THE SIXTH IS THE HANDSET'S OWN PREVIEW, and it belongs here for exactly the reason the browser's
 * does: it is handed the document rather than the record. Its header — "the caller hands that same
 * document here and this file draws its `blocks`. Nothing is re-derived from stage data, no second
 * traversal exists, and there is no round trip" — is the same relationship, and it is wired at
 * `ui/designworkshop/ReportScreen.kt:1012`. Two things about it are deliberately NOT claimed in the
 * copy below: it is a readable column and not A4 sheets (its own header says why — "an A4 sheet
 * rendered to fit is 4pt type"), and it is live against the LOCAL DRAFT rather than against what the
 * server holds, because the handset has `buildWorkshopDocument` in it and the browser has no builder
 * at all. That is a platform difference, so it is stated as one and never paraphrased into the web's
 * sentence (§16 of the frontend contract).
 *
 * "NONE OF THEM WALKS THE RECORD TWICE" is the report page's own first paragraph
 * (`app/(protected)/design-workshops/[id]/report/page.tsx`): `GET /report/preview` builds the SAME
 * document and serialises its blocks, and "a preview that walked the workshop data itself would be
 * one more traversal of the same record and would be the first of the five to drift — silently,
 * because the person reading the preview is reading it precisely so they do not have to open the
 * file." Both quoted sentences say five and both are reproduced as they stand: `RENDERERS` below is
 * where this page's count lives, and neither of those files is edited from here.
 *
 * PAPER. Same file: `ReportSheets` "lays the blocks onto A4 (or Letter) sheets at their real
 * millimetre dimensions, with the cover on its own page, a running head and foot on every page
 * after it, and a visible mark at every break the template declares." The per-stage slice is
 * `components/designworkshop/report/StageDocumentPreview.tsx`, whose header says the report page
 * "has drawn the document as real A4 sheets for a long time; what did not exist was any way to see
 * it from the [stage]" — and that component deliberately does NOT lay its slice on A4, which is why
 * the copy below says "a stage's own slice" and not "the same sheets".
 *
 * ⚠ AND IT IS NOT KEYSTROKE-LIVE. The copy below said "previewable from its form AS YOU FILL IT IN",
 * which is precisely the renderer `StageDocumentPreview.tsx:22-36` was written to refuse: rendering
 * from the local draft "would make it update on every keystroke with no round trip. It is refused,
 * and not narrowly … a fifth built in the browser from the stage form's own state would be the only
 * one nobody ever opens a file to check. It would drift, silently". The same header then says what
 * live honestly means — "It follows the SAVES, not the keystrokes" — and the stage page agrees
 * mechanically: the panel's `refreshToken={previewToken}` counter is "bumped by the save path"
 * (`app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx:361`, rendered at :1956).
 * `components/guide/steps.ts:512` had it right all along ("follows the SAVES, not the keystrokes …
 * it cannot show an edit you have not saved yet and says so on itself"), so the sentence below was
 * the public page promising, to somebody who has not signed up, the one behaviour this component
 * refuses on purpose — and contradicting the walkthrough while doing it. The clause now states the
 * refusal as the feature, because that is what it is: a preview that cannot drift from the file.
 *
 * TEMPLATES AND COLOUR. `backend/app/services/report_templates.py` declares six: `DCH_STANDARD`,
 * `DIC_STANDARD`, `IMPLEMENTING_AGENCY`, `COMPACT_SUMMARY`, `DETAILED_TECHNICAL`,
 * `PHOTO_CATALOGUE` — all six are named in the copy so a reader can count them against the
 * sentence. The accent count is NOT typed: it is `ACCENT_PRESETS.length` from `lib/reportTheme`,
 * which is a line-for-line port of `report_theme.py`'s own tuple. The report page states the rest:
 * "picking one redraws every sheet below it in the same frame — the headings, the rules, the table
 * headers, the zebra stripes and the live figures", and "One accent is chosen and the other seven
 * colours are derived from it".
 *
 * ⚠ WHAT IMPORTING `lib/reportTheme` COSTS THIS PRERENDERED PAGE, SAID PLAINLY AND NOT MEASURED.
 * `AccessLadder.tsx` measured its own equivalent import (~20 KB of `lib/permissions`, about 2% of
 * the page's chunks) and concluded that a rendered access-control ladder which cannot silently lose
 * a tier was worth it. This is the same trade for a smaller module — `reportTheme.ts` is ~12 KB of
 * source, imports nothing at all, and has no side effects, so it is a far better tree-shaking
 * candidate than that one turned out to be — but IT WAS NOT MEASURED HERE, and this comment must
 * not be read as though it had been. If somebody is auditing the landing page's bundle, this is one
 * of the two imports to look at, and inlining the numeral with a citation comment is the cheap fix.
 *
 * WARNINGS. `build_report`'s docstring: it returns the document AND its warnings — "which the
 * caller shows beside the download rather than writing into the file. A warning belongs to the act
 * of generating, not to the document: the officer who opens the .docx next month should not find a
 * note about what was missing on the day."
 *
 * THE HANDSET. `android/…/report/ReportExport.kt` — "The public entry point for exporting a
 * [ReportDocument] from the device, entirely offline … so a researcher standing in a workshop with
 * no signal gets the same .docx and [pdf]".
 *
 * THE ASTERISK IS THREE ASTERISKS, AND THIS PAGE SHIPPED IT AS ONE. `ReportSettings.kt:366`'s
 * `UNSUPPORTED_SECTIONS` is the authority — "The special sections this device cannot build, and why
 * — one sentence each, said to the designer rather than left as a silent hole in the file" — and it
 * holds THREE: `ANNEXURE_TRANSCRIPTS` (:367), `ANNEXURE_QUESTIONNAIRES` (:388) and
 * `ANNEXURE_AI_LAYERS` (:408). `ReportScreen.renderSpecialSection` matches it arm for arm:
 * transcripts `-> Unit` (:2217), AI layers `-> Unit` (:2249), and the questionnaire annexure now
 * genuinely drawn (:2231, `ReportQuestionnaires.renderQuestionnaireAnnexure`).
 *
 * WHAT WAS READ INSTEAD, and the defect is worth naming because the file invites it. The stage-20
 * settings ledger a few lines above the map has exactly one `SettingReach.NOT_ON_DEVICE` row,
 * `includeTranscripts` (:337) — so a reader who stops there counts one gap. That ledger's own header
 * warns in capitals against exactly this (:295): "WHAT THIS LEDGER CANNOT SEE … they are template
 * sections … so no entry below could ever have named them and a reader working down this list would
 * have concluded the file was complete." An annexure is a template section, not a stage-20 switch.
 * Meanwhile `components/guide/steps.ts:618` — the in-app guide — already said "the three annexures
 * it cannot draw offline: transcripts, questionnaire answers and machine-assisted text". So the
 * public page and the walkthrough disagreed about what an offline export omits, in the direction
 * that costs most: the page is read before signing up and the guide only after.
 *
 * THE CONDITIONALITY IS PART OF THE FACT, and the three are not alike — `unsupportedSectionsIn`
 * (:479) is where their shapes are, and flattening them into one clause is how this sentence goes
 * wrong in the other direction. The transcript entry is skipped unless the designer actually asked
 * for transcripts (`!wantsTranscripts(...)`, :488), because "every template carries it and it prints
 * nothing at all unless the designer asked for it". The questionnaire entry is skipped the moment
 * this handset can say anything about the workshop's questionnaires (`questionnaires !=
 * DwQuestionnaireCopy.UNKNOWN`, :489-491) — the entry itself carries the fix, "open the
 * questionnaire on this phone once while you have a connection and they are kept here for every
 * export afterwards, including offline ones", which is the clause the copy below reproduces. The
 * AI-layers entry is unconditional and says why (:393-400): "there is no such path here … The gap is
 * a property of the device, not of what this workshop happens to contain."
 *
 * ⚠ DO NOT UPGRADE THE THIRD INTO A PROMISE THAT A DESIGNER WILL BE TOLD. `ReportScreen.kt:2247`
 * records that the AI-layers arm "is unreachable today": no template in `REPORT_TEMPLATES` carries
 * the section and the handset never asks the server to splice it in, so nothing puts it in a plan.
 * The copy below therefore claims candour about the three gaps and NOT that all three warnings are
 * ever rendered — an absolute that is 95% true is the expensive kind of marketing claim, and this
 * card is the one that says so.
 *
 * THE WEB HALF NEEDS THE SERVER, and the copy says so. `GET /report/preview` and the download are
 * both API calls; only the handset builds a document out of its own draft.
 */

/**
 * The six surfaces one document model is drawn on. `where` is the honest half of each label.
 *
 * A PREVIEW COUNTS AS A SURFACE HERE, which is what makes the handset's one the sixth rather than a
 * footnote: the browser's has been in this list from the start, and `DwReportPreview.kt` stands in
 * the same relationship to the document — handed the blocks, re-deriving nothing. This array is
 * where the count is kept; see the header for the sentence in `report_builder.py` that says five and
 * is correct in saying so.
 */
const RENDERERS = [
  { icon: FileText, format: ".docx", where: "on the server" },
  { icon: FileText, format: ".pdf", where: "on the server" },
  { icon: MonitorPlay, format: "Preview", where: "in the browser" },
  { icon: Smartphone, format: ".docx", where: "on the handset" },
  { icon: Smartphone, format: ".pdf", where: "on the handset" },
  { icon: Smartphone, format: "Preview", where: "on the handset" }
];

const FACTS = [
  {
    icon: ScrollText,
    title: "Read as paper, not as cards",
    copy:
      "The preview lays the document onto A4 or Letter sheets at their real millimetre dimensions — the cover on its own page, a running head and foot after it, and a visible mark at every break the template declares. Whether the cover table has crowded the photograph off the page is a question about a page, so the answer has to be one. A single stage's own slice is previewable from its form, and it follows the saves rather than the keystrokes: it is the same server-built document, never a second one drawn from what is still being typed."
  },
  {
    icon: Palette,
    title: "Six templates, and colour you can see before you commit",
    copy:
      `A DCH submission, a DIC submission, an implementing agency's, a compact summary, a detailed technical report and a photograph catalogue — with ${ACCENT_PRESETS.length} named accents and a colour well beside them. Pick one and every sheet on screen redraws in the same frame: headings, rules, table headers, zebra stripes and the live figures. One accent is chosen and the other seven colours are derived from it, so nobody has to generate a file to find out they dislike the colour.`
  },
  {
    icon: FileWarning,
    title: "Warnings sit beside the download, never inside the file",
    copy:
      "A required field nobody filled in, or a photograph that could not be embedded, produces a warning — and the file is generated anyway, because a designer needs the pages that are ready. The warnings never travel inside the document: the officer who opens it next month should not find a note about what was missing on the day."
  },
  {
    icon: Smartphone,
    title: "Generated on the handset, with nothing to connect to",
    copy:
      "The phone builds the document from its own local draft and writes both files on the device, so a fortnight in a cluster ends in a document rather than in a queue. It is candid about the three annexures a handset can be short of, in a sentence each rather than a silent hole in the file: transcripts, because workshop audio is transcribed on the server; the answers to an attached questionnaire, until this phone has read that questionnaire once with a connection, after which they print offline too; and machine-assisted text, the one gap nothing on the phone can close. The browser's report is the other way round: it needs the API."
  }
];

export default function ReportEngine() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.06 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="report" className="mx-auto max-w-6xl px-6 py-24" aria-label="Report generation">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          The deliverable
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-3xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          One document. Six renderers that have to agree, line for line.
        </motion.h2>
        {/* THE COUNT IS STATED ONCE, IN THE HEADING, AND NOWHERE ELSE. It sits three lines above a
            list of six tiles a reader can count for themselves, which is the same trick the
            template card plays by naming all six templates in its own sentence. An earlier draft
            said the number in the heading AND again as "Five writers" here — two copies of one
            number on one screen, which is the defect `AccessLadder` carries a page of comment
            about, and it is also what left the heading saying five after the handset's preview
            made it six. Change `RENDERERS` and this heading together — plus the `lg` column count
            below, which is the list's length and not a number of its own. */}
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          The {STAGE_COUNT_WORD_LOWER} stages are built once into a single document — what the report
          says, with nothing in it about how any of it is drawn. The renderers below then draw that
          one document, and not one of them walks the record a second time. A preview that rebuilt the
          pages from the stage data would be the first of them to drift, silently, in front of the one
          person reading it precisely so they need not open the file.
        </motion.p>

        {/* The fan. One label, a short rule, then the six — a diagram made of a border and a
            hairline rather than an SVG, because it has one edge to draw and the printing bed is
            this page's one place for bespoke geometry. */}
        <motion.div variants={item} className="mt-12 flex flex-col items-center">
          <div className="rounded-md border border-line-200 bg-card px-5 py-3 text-center shadow-sm">
            <p className="font-display text-sm font-bold text-ink-900">One document model</p>
            <p className="mt-0.5 text-xs text-ink-500">assembled from the stages, once</p>
          </div>
          <div aria-hidden className="h-6 w-px bg-line-200" />
        </motion.div>
        {/* Six across from `lg`, not five: the column count is the list's length, so a sixth tile
            joins the row instead of standing alone under it. Two and three still divide six
            exactly, so no breakpoint below `lg` leaves an orphan either. */}
        <motion.ul variants={item} className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {RENDERERS.map((renderer) => (
            <li
              key={`${renderer.format} ${renderer.where}`}
              className="rounded-md border border-line-200 bg-card p-4 text-center shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mx-auto mb-3 flex h-9 w-9 items-center justify-center rounded-md bg-purple-50 text-purple-700">
                <renderer.icon className="h-4 w-4" aria-hidden />
              </span>
              <p className="font-display text-sm font-bold text-ink-900">{renderer.format}</p>
              <p className="mt-0.5 text-xs text-ink-500">{renderer.where}</p>
            </li>
          ))}
        </motion.ul>

        <div className="mt-12 grid gap-5 md:grid-cols-2">
          {FACTS.map((fact) => (
            <motion.div
              key={fact.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                <fact.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-lg font-bold text-ink-900">{fact.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-ink-700">{fact.copy}</p>
            </motion.div>
          ))}
        </div>
      </motion.div>
    </section>
  );
}

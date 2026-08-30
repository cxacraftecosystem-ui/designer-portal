"use client";

import { motion, type Variants } from "framer-motion";
import { ArrowUpDown, Check, IdCard, PenTool, QrCode } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";

/**
 * The four surfaces that exist for the DESIGNER specifically, rather than for the record.
 *
 * ── EVERY CLAIM BELOW, AND THE FILE IT WAS READ OUT OF ────────────────────────────────────────
 *
 * THE PROFILE. `backend/app/services/designers.py`: `PROFILE_FIELDS` is twenty-one columns and
 * `PREFILL_MAP` carries twenty-one pairs — the same twenty-one, which is what
 * `test_every_writable_profile_column_is_either_prefilled_or_named_here` exists to keep true. The
 * CV is `cvMediaId`, a media id and never a URL, and that column's comment states "The Designer
 * Page renders it inline where it is a PDF". `prefill_from_profile`'s docstring is where "these are
 * COPIES, and they must stay copies" is argued: a designer who moves institution in 2027 must not
 * retroactively rewrite the workshop they ran in 2026.
 *
 * ⚠ ONE WORDING CHECKED AND DELIBERATELY LEFT VAGUE. `design_workshops.seed_designer_prefill` seeds
 * **the creator's** profile — "Start a new workshop with the creator's profile already in stage 1
 * and stage 3" — and a designer cannot create a workshop (`DESIGN_WORKSHOP_CREATOR_ROLES` is admins
 * only). So "copied into every workshop YOU are put on" would be a claim the code does not make,
 * and the bullet below says what is actually true instead: these are the values a new workshop's
 * stages start pre-filled with, and they stay editable per workshop. Do not tighten it back.
 *
 * THE FIRST-SIGN-IN REDIRECT. `components/designers/DesignerProfileOnboarding.tsx`: once per
 * session, keyed on the account id in `sessionStorage` — and once per loaded page in a browser
 * that refuses it, which is the fallback that file's own header describes — only for a DESIGNER
 * (not for everyone who `canRunDesignWorkshops`), never over a deep link, and `?welcome=1` is what
 * makes the profile page explain itself — "an unexplained forced navigation is
 * indistinguishable from a bug".
 *
 * SKETCHES. `lib/sketchRectify.ts` is perspective correction plus local thresholding over a luma
 * plane — "No network, no model, no server" — and it writes a NEW file into `sketch.lineArtFile` so
 * that `sketch.image` still points at the untouched photograph. `components/sketches/upload/
 * SketchTraceField.tsx` is the tracing panel: on the device, in a worker, never uploading anything,
 * and it shows the trace before attaching it because "the person who can tell whether that mattered
 * is the designer with the actual sheet in front of them". The 3D model is a real registry field —
 * `f("modelFile", "3D model", FILE, A, …)` on stage 13 in `stage_definitions.py`.
 *
 * REVIEW. `backend/app/services/design_ratings.py` opens with the owner's rule verbatim: rate
 * "qualitatively and quantitatively, leave suggestions, and RANK sketches and prototypes by
 * drag-and-drop AND by up/down arrows — sorted by score by default, with the designer having the
 * final say", over "two review levels: workshop peers first, then the whole pool of designers once
 * prototypes are finalised". The same file's "IT IS NOT A NEW RANKING MECHANISM" paragraph is what
 * the last bullet says: the placed order is `DwStageEntry.ordinal`, and `rank()` reads that column
 * and never writes one.
 *
 * JOINING. `backend/app/services/design_workshop_viewers.py`'s header is where the four people come
 * from — "run by two designers alongside a master craftsperson and a reviewing officer, all of whom
 * have to read the same stages" — and where the grant's limits are stated: READ and STAGE WRITES,
 * never DELETE and never RE-GRANTING. `design_workshop_grants.py`: the card is
 * `DPW2:J:<recordId>.<22-character secret>:CHCK`, "Sixty characters total, which fits QR version 4
 * at error-correction level Q"; `maxUses` defaults to 1 and multi-use is admin-only.
 *
 * ⚠ THE SPENT-CARD PATH: WHAT IS BUILT, AND THE HALF THAT IS NOT. This bullet used to quote the
 * module header's requirement-6 sentence — the late-comer "is not refused, they get a capture-only
 * foothold and their fieldwork is kept" (`design_workshop_grants.py:109`) — and that quotation is
 * the defect, because 114 lines later the same file states the guard it was written under (:223-227):
 * "`may_capture` below is the predicate a later wave hangs the capture path on. Until the module that
 * owns `load_workshop_or_404` calls it, a provisional member can be RECORDED, can be seen by an
 * admin, and can be UPGRADED — and cannot yet post stage entries." The predicate's own docstring is
 * blunter still (:607): "NOTHING CALLS THIS YET, and that is a wave boundary rather than dead code."
 * Verified rather than assumed: `grep -rn may_capture backend/app` returns the definition at :593
 * and two mentions inside comments (:223, :1473) — no caller anywhere, and the only invocations in
 * the repository are in `backend/tests/test_design_workshop_provisional_isolation.py`. A capture-only
 * foothold that cannot yet capture is not a thing to promise on a page somebody reads before signing
 * up, and "keep their work" was the same promise in softer words.
 *
 * WHAT IS TRUE TODAY, and it is the honest version of the same reassurance. The redemption writes a
 * `DesignWorkshopProvisionalMember` row and, in the same transaction, files the person into the queue
 * an administrator already works from — `_file_or_refresh_the_queue_row` (:1165, called at :1616):
 * "``status`` stays PENDING and ``source`` stays SCAN, so ``queue()`` … picks them up with zero query
 * changes", and a repeat scan deliberately does not restamp `createdAt`, which would let a replay
 * jump a queue ordered oldest-first. So the claim the code supports is that the ASK is filed for an
 * admin to decide and nothing is thrown away while it waits, which is what
 * `components/guide/steps.ts:484` says in the walkthrough — the bullet below now matches it word for
 * word rather than reaching one wave ahead of the backend.
 *
 * `lib/workshopCodes.ts` is the authority for
 * what a code does NOT carry: not a URL, no name, no village, no craft, and "never
 * `Artisan.aadhaarNumber` or a Pehchan card number". The live scanner is
 * `android/…/ui/DwQrLiveScanner.kt` — an explicit `CameraSelector.DEFAULT_BACK_CAMERA` on every
 * bind, with the decode done on the handset.
 *
 * ── SHAPE ─────────────────────────────────────────────────────────────────────────────────────
 *
 * Card anatomy is `TeamSection`'s, deliberately: icon tile, title, one sentence, a hairline, then a
 * checked list. Two columns rather than four, because four bullets in a quarter-width column is a
 * wall — and because the section above it is already a four-up grid, and two identical grids in a
 * row read as one long grid with a heading dropped into the middle of it.
 */
const SURFACES = [
  {
    icon: IdCard,
    title: "Your profile, typed once",
    copy:
      "One page holding the twenty-one things a submitted report has to say about the person who ran the workshop.",
    points: [
      "Name and local-script name, designation, institution, department, qualification, specialisation, years",
      "Phone, email, website and address; empanelment number and date; photograph and signature",
      "A CV, rendered on the page where it is a PDF rather than merely listed",
      // `components/designers/profileCopy.ts:87-91` — DESIGNER_PROFILE_REQUIRED_FIELDS, exactly
      // four: displayName, qualification, phone, email. The card previously implied none of the
      // twenty-one was required, which the in-app guide had already been corrected away from
      // (its designer-profile card, `components/guide/steps.ts`), so the public page and the
      // walkthrough disagreed in the
      // direction that costs most: this one is read BEFORE signing up. The second sentence is that
      // file's own argument, near enough verbatim — marking every box required would stop a
      // designer without an empanelment number yet from saving their biography at all.
      "Four are required — name, qualification, phone and email, the values a document is submitted under and the ways of reaching you about it. The other seventeen wait until you have them",
      "All twenty-one are values a new workshop's stages start pre-filled with — and stay editable inside it, because a report records who ran a workshop at the time"
    ]
  },
  {
    icon: PenTool,
    title: "Sketches and prototypes",
    copy:
      "A sketch usually enters an archive as a photograph of a sheet of paper on a courtyard table, at whatever angle somebody happened to be standing.",
    points: [
      "The sheet squared to the page and thresholded into a printable plate — plane geometry on the device, no model and no server",
      // The handset half is `android/settings.gradle.kts` plus the four vendored `core-*` modules:
      // the tracer is now the upstream Kotlin engine compiled into the APK, which replaced a
      // JavaScript bundle behind a WebView gate that made the tracer simply ABSENT on older
      // handsets. "A browser it may not have" is that gate stated as the reader experiences it.
      "Line art traced to vector in a worker, on the same device, uploading nothing — and on the handset by a native engine rather than inside a browser it may not have",
      "The photograph is never overwritten: the plate is a second artifact that cites the first",
      // `components/sketches/upload/traceExport.ts` — EXPORT_FORMATS, five ids, and its own header
      // states "EXPORT_FORMATS is the whole list". The handset writes the same five:
      // `DwTraceKotlinExporter.kt` routes SVG and PNG to the platform and hands the other three to
      // the vendored `PdfWriter` / `EpsWriter` / `DxfWriter`. THE PARENTHESES ARE THE POINT — a
      // designer does not know what a DXF is for, and the format list is only useful if it names
      // the machine at the other end. Both hints are shortened from that file's own.
      "The trace saved five ways on either client — SVG and PNG to attach, PDF to send on, DXF for a cutting machine, EPS for a print shop",
      // `components/sketches/upload/MeasureFromPhotoCard.tsx#CARD_TITLE`, quoted rather than
      // described. Cited by SYMBOL and not by line: that file is being rewritten in a neighbouring
      // lane as this is written, and CARD_TITLE has already moved once. Its header records that the same words are hardcoded in four places
      // across the two clients on purpose ("the words are the shared thing, not the symbol"), so a
      // paraphrase here would be the fifth spelling of a name three surfaces agree on.
      "“Measure a dimension from a photograph”, for the piece that has gone home while the questions have not — the panel Android had, now on the web too",
      "Prototypes carry their own drawings and photographs, and a 3D model file where there is one"
    ]
  },
  {
    icon: ArrowUpDown,
    title: "Review by the people who would know",
    copy:
      "Colleagues rate a sketch or a prototype qualitatively and quantitatively, leave a suggestion, and then put the set in order.",
    points: [
      "Sorted by score by default — and the designer has the final say",
      "Reorder by dragging, or by the up and down arrows on the card itself",
      "Two rounds: the workshop's own designers first, then the whole pool once prototypes are finalised",
      "The placed order is a column on the record. It is the designers' judgement, never a ranking the app computed and imposed"
    ]
  },
  {
    icon: QrCode,
    title: "One workshop, joined by scanning",
    copy:
      "A real workshop is run by two designers alongside a master craftsperson and a reviewing officer, and all of them have to read the same stages — scanned off the back lens and decoded on the handset itself, with no signal.",
    points: [
      "A printed card admits a colleague to that workshop: read and stage writes, never delete and never handing out further access",
      "Single-use by default; only an administrator can print one that lets a whole group in",
      "A card already spent turns nobody away: the ask is filed for an admin to decide, so their work is not orphaned while they wait",
      // `app/(protected)/scan/page.tsx` — `PageHeader title="Scan a code"`, and the same three
      // words are a dashboard tile on both clients, held together by
      // `e2e/dashboard-tile-parity-unit.spec.ts` and `DashboardTileParityTest`. Worth a public
      // sentence because the fix was to a PATH rather than to a feature: the scanner existed and
      // took three deliberate steps down a menu row named after reading a list to reach. "One tap
      // from the screen the app opens on" is that page's own phrase.
      "“Scan a code” is its own destination on both clients — a card, a prototype tag or a screenshot of one, one tap from the screen the app opens on",
      "Sixty opaque characters, not a URL: no name, no village, no craft, and never an Aadhaar number"
    ]
  }
];

export default function DesignerWorkbench() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.08 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section
      id="designer"
      className="border-y border-line-200 bg-surface-50 py-24"
      aria-label="Built for the designer"
    >
      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.15 }}
        variants={container}
        className="mx-auto max-w-6xl px-6"
      >
        <motion.p variants={item} className="eyebrow mb-3">
          For the designer
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-3xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Your profile, your sketches, and your colleagues&rsquo; own judgement.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          The {STAGE_COUNT_WORD_LOWER} stages record the craft. These four surfaces exist for the
          person running them — the one whose name the finished document is submitted under.
        </motion.p>

        <div className="mt-12 grid gap-5 md:grid-cols-2">
          {SURFACES.map((surface) => (
            <motion.div
              key={surface.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                <surface.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-lg font-bold text-ink-900">{surface.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{surface.copy}</p>
              <ul className="mt-5 space-y-2.5 border-t border-line-200 pt-5">
                {surface.points.map((point) => (
                  <li key={point} className="flex items-start gap-2.5 text-sm leading-relaxed text-ink-700">
                    <Check className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
                    {point}
                  </li>
                ))}
              </ul>
            </motion.div>
          ))}
        </div>

        {/* The redirect is a behaviour rather than a surface, so it sits under the four rather than
            inside one of them — and it is worth stating on a public page because it is the first
            thing a new designer will experience and the one most easily mistaken for a bug. */}
        <motion.p variants={item} className="mt-8 max-w-2xl text-sm leading-relaxed text-ink-500">
          A designer signing in for the first time is taken to that profile page once, and told why.
          Once, not on every visit: somebody who reads it and decides to do it this evening is not
          dragged back there on the next page they open.
        </motion.p>
      </motion.div>
    </section>
  );
}

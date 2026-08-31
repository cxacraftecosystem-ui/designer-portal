import {
  Brush,
  ClipboardCheck,
  ClipboardList,
  Eye,
  FileOutput,
  FileSearch,
  FileSpreadsheet,
  GitBranch,
  History,
  IdCard,
  Images,
  Layers,
  ListChecks,
  Package,
  PencilRuler,
  QrCode,
  Star,
  User as UserIcon,
  UsersRound,
  Wrench,
  type LucideIcon
} from "lucide-react";

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * A GUIDED (COACH-MARK) TOUR WAS EVALUATED AND DECLINED — 2026-08-29, requirement 4.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The ask was to evaluate a guided tour: the pattern where a spotlight dims the screen, a bubble
 * points at one control, and Next walks a reader through a real page. The answer is NO, and it is
 * written HERE rather than in a ticket because the next reader has to be able to CHECK it. Every
 * claim below names a file you can open, and if one of them stops being true the decision is worth
 * re-opening. A decision nobody can check is re-litigated from scratch by whoever arrives next.
 *
 * ── WHAT A TOUR WOULD GENUINELY BUY ────────────────────────────────────────────────────────────
 *
 * Said first, so this does not read as a refusal hunting for reasons. A card teaches a screen from
 * a page the reader then leaves; a coach-mark points at the actual control on the actual screen,
 * which is the only way to teach a SPATIAL fact — "the artisan dropdown stays disabled until a
 * craft is chosen" is one sentence on a card and one glance in situ. And the 22-stage form is the
 * one screen this file admits it cannot describe (see THREE CARDS IN THE SECOND ARC below): that
 * form is built from a registry the SERVER publishes, so its boxes are not this file's to
 * enumerate, while a tour anchored to live DOM would not have that problem at all.
 *
 * ── WHAT IT WOULD COST ─────────────────────────────────────────────────────────────────────────
 *
 *  1. A NEW DEPENDENCY, OR A DRIVER WRITTEN HERE. `frontend/package.json` carries no tour library —
 *     `grep -iE "joyride|shepherd|driver\.js|intro\.js|reactour" frontend/package.json` matches
 *     nothing — and "build on radix" overstates what radix is in this repository: the only radix
 *     packages installed are `react-scroll-area`, `react-separator` and `react-slot`. No popover,
 *     no popper, no dialog, no tooltip. There is no positioner to build on except the house's own,
 *     `components/ui/AnchoredPopover.tsx`, which is buildable-on and is somebody else's invariants.
 *  2. A BUNDLE COST ON EVERY PROTECTED PAGE. A driver mounted in `AppShell` is paid for by every
 *     screen in the app — the objection that already forced GSAP behind a dynamic import
 *     (`useGsapHeadline.ts`: ~70 KB "must not sit in the bundle every protected page loads").
 *     Behind an explicit "start the tour" button it could be imported lazily, which also means it
 *     cannot auto-start on first sign-in, which is most of what a tour is wanted for.
 *  3. THREE REDUCED-MOTION BRANCHES PER STEP. A coach-mark is a spotlight, a scroll and a popover.
 *     There is no `MotionConfig reducedMotion="user"` anywhere in this app, so each one has to
 *     branch in JS on `useAppReducedMotion()`; and the scroll half is the trap, because
 *     `scrollToStep` already treats smooth scrolling as motion, so every hop between two targets
 *     owes the same gate. A spotlight under `prefers-reduced-motion` also has no good degraded
 *     form: the ring has to persist without the pulse.
 *  4. A FOCUS AND ESCAPE NEGOTIATION WITH TWO CAPTURE-PHASE LISTENERS THAT ALREADY EXIST. A tour
 *     overlaying a live form must not steal focus from the control it is describing, must not
 *     fight `FieldDialog`'s document-capture Escape or `AnchoredPopover`'s window-capture one, must
 *     re-pay `var(--nav-scroll-gutter, 0px)` if it is fixed, and must sit in the z-ladder without
 *     covering the island it is telling people to use.
 *  5. AND THE ONE NO AMOUNT OF ENGINEERING REMOVES: FOR MOST OF ITS AUDIENCE THERE WOULD BE NO DOM
 *     TO POINT AT. `/guide` is deliberately ungated (`DynamicIslandNav`), and the reason is written
 *     out below — the guide teaches the process to people who have not earned the capability yet.
 *     But `AppShell` renders a `RouteLocked` panel INSTEAD of the page for every screen in the
 *     second arc, and the inspection surface is narrower still (`INSPECTION_ROLES` is a one-member
 *     set). So the tour would either refuse to run for exactly the readers this page exists to
 *     serve, or need a per-role script — a second, hand-kept copy of `ROUTE_GUARDS`, which is the
 *     failure `.claude/skills/field-repo-frontend/SKILL.md` records as twenty-two hand-kept copies
 *     of the ladder all needing correction when INSPECTOR landed.
 *
 * ── THE CHEAPER THING THAT BUYS MOST OF IT, AND IS NOT BUILT EITHER ────────────────────────────
 *
 * `GuideJourney` already reads `location.hash`, VALIDATES it against this array, opens that card
 * and scrolls to it — and nothing anywhere in the app links to `/guide#<id>`. Every one of them goes
 * to the bare route — `grep -rn '"/guide"' frontend/app frontend/components` is the list, and it is
 * eleven sites today. (It said "the nine entry points" when this block was written, which was wrong
 * on the day: the nav item, the dashboard notice, four lock panels and five links off the public
 * pages are eleven however they are grouped. The COUNT was never the argument — the absence of a
 * single `#` in any of them is — so it is stated as a grep rather than as a number a reader has to
 * take on trust and a later link silently invalidates.) Pointing each lock panel and each screen's help at its own
 * step anchor lands a designer on the paragraph about the screen they were just refused, which is
 * most of what a tour is for, degrades to a plain link under any preference, and costs one href per
 * site. Those files belong to other lanes; this is the note recording that the machinery is here
 * and waiting for them.
 *
 * DO NOT ADD A TOUR LIBRARY on a later re-reading of this comment. What would change the decision
 * is cost 5 going away — the second arc becoming READABLE by every signed-in account — and not a
 * lighter library appearing.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * The steps of the process, in the order a designer actually performs them in the field: first the
 * ones that build the repository records, then the ones that run the design & prototype development
 * workshop those records feed.
 *
 * ⚠ THE COUNT IS NOT WRITTEN IN THIS COMMENT ANY MORE, and the deletion is the point. This sentence
 * opened "The nineteen steps of the process" and was correct on the day it was typed; the page
 * header had already said "Ten steps" over an array of sixteen for exactly the same reason, which
 * is why every count a READER sees is derived from `GUIDE_STEPS.length`. A count in a comment goes
 * stale as silently as one in copy, and it is worse, because the next agent reads it as a fact
 * about the file rather than as a sentence somebody wrote once.
 *   grep -c '^    id: "' frontend/components/guide/steps.ts
 * is the answer and is always current.
 *
 * THE SECOND ARC WAS MISSING FOR AS LONG AS THIS FILE EXISTED, and it is the one the fortnight is
 * for. The steps below were the repository RECORD forms and nothing else; nothing here mentioned
 * the 22-stage workshop, the readiness check, the code cards or generating the report handed to a
 * ministry officer. A designer who opened the in-app Walkthrough looking for the deliverable found
 * ten ways to file a record and no path to the document. The workshop arc comes AFTER the record
 * steps and not before, because that is the order the work happens in: the records exist first, and
 * the stage form points at them.
 *
 * THE THIRD PASS WAS 2026-08-29, and it was a CURRENCY pass rather than a structural one: two waves
 * of shipped work had landed since anything in this file was last checked against a form. What that
 * cost is worth naming, because it is the failure this file is most exposed to. `steps.ts` had not
 * been touched since before the wave that put a "Design & prototype workshop" picker on six record
 * forms, a microphone on every prose box, a deterministic photo-measuring panel above the vision
 * model, a repository media picker and a photograph gate inside the stage form, five trace export
 * formats on the sketches screen, a designer TEAM on a workshop create, and two whole destinations
 * (Scan a code, Workshops to inspect). None of that was WRONG on a card — it was absent, which is
 * the quieter defect: a designer reading a complete-looking list concludes the control they are
 * looking at is not part of the process. One card WAS wrong, and that is the loud one: the
 * questionnaire card listed a "Date" field the interview form has never had and says in its own
 * comment it will never have.
 *
 * THE DESIGNER'S OWN SCREENS WERE THE SECOND OMISSION, and it lasted until 2026-08-26. The arc
 * taught the workshop and the report and said nothing about the person running them: the profile a
 * workshop's stage 1 and stage 3 start pre-filled from, the sketch and prototype work that stages 11
 * and 13 exist for, or the review round that ranks it. Three steps were added — `designer-profile`,
 * `design-workshop-sketches`, `design-review` — and the `Cards & tags` step moved AHEAD of the
 * stages, because printing the codes and getting the team onto one workshop happen on the first
 * afternoon and filling the stages takes the fortnight.
 *
 * ONE CLAIM FROM THE BRIEFING WAS CHECKED AGAINST THE CODE AND IS NOT WRITTEN ON ANY CARD: "a 3D
 * prototype model" viewer. `upload/PrototypeModelField.tsx` states in capitals that nothing in the
 * frontend can render one — the file is stored and downloadable and prints as "1 document
 * attached". A card asserting it would be the failure mode this file's own labels rule exists to
 * prevent, one level up: not a name the product does not use, but a feature it does not have.
 *
 * ⚠ A SECOND CLAIM WAS WITHHELD ON THIS SAME REASONING AND THE REASONING WAS WRONG. "Plate
 * straightening from a photographed sketch" was refused here, and in `docs/WALKTHROUGH.md`'s "What
 * the app does not do" row, on the evidence that `lib/trace/imageEdit.ts` is a crop and an unsharp
 * mask with no deskew anywhere under `lib/trace/`. That search was true and the conclusion drawn
 * from it was not: `lib/trace/` is the TRACING panel, and the straightening ships in a different
 * file for a different registry field. `lib/sketchRectify.ts` is "perspective correction, then
 * line-art extraction by local thresholding" over `solveHomography`/`applyHomography` from
 * `lib/photoMeasure`; `components/designworkshop/SketchRectifyField.tsx` is its panel, mounted from
 * `FieldInput.tsx` wherever `stageFieldRoles.offersSketchRectify` matches — stage 11's
 * `sketch.lineArtFile` is the field it was written for; and `ui/designworkshop/
 * DwSketchRectifyField.kt` is the handset twin. So the `design-workshop-sketches` card DESCRIBES it,
 * and the doc's row has gone.
 *
 * The general lesson is worth more than the correction: absence proved inside one directory is not
 * absence from the product, and a "does not do" row is the one kind of claim that instructs the
 * next reader not to look. Prove a negative over the tree or do not write it.
 *
 * EVERY STEP IN THE SECOND ARC LANDS A NON-DESIGNER ON A LOCK PANEL, which is a thing §9 of the
 * frontend skill says a card must not do quietly. `/designers/profile`, `/design-workshops`,
 * `/design-review`, `/sketches-and-prototypes` and `/questionnaires` all carry a `ROUTE_GUARDS` row
 * on `canRunDesignWorkshops` (`lib/permissions.ts`), while `/guide` itself is deliberately ungated —
 * so a researcher reading this page can open the record steps and none of the workshop ones. That
 * cannot be fixed from inside this file: the guide teaches the process to people who have not
 * earned the capability yet, and withholding the arc would hide the deliverable the fortnight
 * exists for. What IS in this file's power is to make the padlock expected rather than a surprise,
 * so every card whose route a reader can arrive at first carries the same sentence in `watch` — one
 * wording, deliberately repeated, so it reads as a rule and not as an apology.
 *
 * ⚠ ONE CARD IN THE ARC MUST NOT BORROW THAT SENTENCE, AND COPYING IT THERE WOULD BE A REGRESSION
 * DRESSED AS CONSISTENCY. `design-workshop-inspection` is gated on `INSPECTION_ROLES`, which is a
 * frozen set of exactly one member — INSPECTOR — so an admin and the master admin are refused it
 * just as a professor is. "Designers, admins and the master admin" is the true sentence for the
 * five routes above and is false in both directions for that one. Its `watch` says so in its own
 * words, and its card comment records why. The repeated wording is a rule about ONE predicate, not
 * a house style for padlocks.
 *
 * ONE CLAIM IN THE SECOND ARC IS NOT A SCREEN DESCRIPTION and must not be softened: choosing a
 * record in a stage COPIES its values onto the stage entry, and the report prints the copy. Never
 * write that a picker shows the linked record, or that the report reads it — that is the opposite of
 * what the system does, and the difference is a document already in an officer’s hands changing
 * under him. Its authority is `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py`.
 *
 * THE FOURTH PASS WAS 2026-08-31 AND IT WAS AN AUDIT OF `fields[]` ITSELF, prompted by a docs lane
 * that could not act on what it found: the REGISTER was wrong, not merely the doc rendering it. Every
 * card's list was read label-for-label against the component named above it. What that turned up is
 * worth stating as a class rather than as a list of nine edits, because the class is what will happen
 * again: the two failures were FIELDS ADDED TO A FORM AFTER THIS FILE LAST READ IT (the artisan's two
 * identity numbers and three date-derived boxes, the questionnaire's capture controls and its audio
 * and location blocks, the craft form's workshop select, "Type of workshop" on a design workshop,
 * "Kind" on a designer's own questionnaire) and LABELS RENAMED UNDER A LIST THAT KEPT THE OLD WORD
 * ("Address" → "Address line"; the process card's two invented per-step rows; "Start and end date"
 * for a control headed "Workshop duration"). Both are invisible to every test in the repository: the
 * parity guard holds three copies of this register to each other and none of them to a form.
 *
 * THE ARTISAN CARD IS WHY THIS MATTERED MORE THAN A STALE DOC. It omitted the Aadhaar number and the
 * Artisan Pehchan Card pair — the two fields in the product with the strictest handling rules, and
 * the only ones on that form a researcher cannot improvise once the artisan has gone home. A list
 * that looks complete and leaves them out does not read as "incomplete"; it reads as "this form does
 * not ask for those", which is a designer arriving at a sitting without the one document they needed.
 *
 * WHAT THE PASS DELIBERATELY DID NOT DO, so the next reader does not take the silence for agreement:
 * "Location (GPS fix or map pin)" is a COMPRESSION and stays one. The card's real heading is
 * "Location of the artisan", it contains a State box that is required on a create, and it opens a
 * map panel with six more labelled boxes behind it. Enumerating that on seven cards would swamp
 * every one of them and would fight the printed guide, which renders the same control as a sentence
 * about the two things it collects and carries a table explaining why they differ (that sentence is
 * licensed by name in `PROSE_PARAPHRASES`). It is one control, named once. If it is ever expanded,
 * expand it on all seven cards, in the doc and in the paraphrase table in one commit.
 *
 * Every `label` here is the Android-parity feature name (see
 * `.claude/skills/field-repo-frontend/SKILL.md` → "Naming"): the walkthrough must call a
 * screen exactly what the dashboard tile and the Android menu call it, or the guide teaches
 * a vocabulary the product does not use. `fields` mirrors the real form labels one-for-one,
 * so a researcher reading the guide recognises the screen when they open it.
 *
 * The labels are copied from the forms themselves — `components/forms/ArtisanForm`,
 * `ProductForm`, `ToolForm`, `ProcessForm`, the inline forms on the workshops / crafts /
 * questionnaire / media pages — plus the three shared sections a record form mounts:
 * `<WorkshopSelect>` (label "Workshop"), `<DesignWorkshopSelect>` (label "Design & prototype
 * workshop") and `<LocationFields>` (heading "Location"). Rename a field on a form and it must be
 * renamed here and in `docs/WALKTHROUGH.md`, which carries the same lists in prose.
 *
 * FOUR CARDS CANNOT OBEY THAT RULE AND THEIR `fields` ARE DESCRIPTIONS INSTEAD.
 * `design-workshop-codes` and `design-workshop-readiness` render no labelled field at all — grep
 * either page for `field-label` and the count is zero; they are a print sheet and a list of links.
 * `design-workshop-stages` cannot enumerate labels even in principle: the stage form is built from
 * the registry the server publishes, so its boxes are hundreds of labels across 22 stages and are
 * not this file's to copy. `design-workshop-inspection` is the fourth, added 2026-08-29, and it is
 * named HERE rather than left to be noticed: an inspection read draws the values of a workshop it
 * did not author, so it has no form and no labels of its own — its four rows are the two page
 * headings, the authorship line under every value, and the completeness figure. Their `fields` name
 * the sections of the screen, which is the only honest content available, and each row is still a
 * thing a reader can point at. EVERY OTHER CARD OBEYS THE RULE and was read off the components
 * named in the comment above its list — do not let a fifth quietly join the exception by being
 * easier to paraphrase than to read. (`design-workshop-questionnaires` was the most recent
 * candidate and did not need to be one: "Download the pro-forma", "Title", "Section title", "Code",
 * "Question", "Help text" and "Reuse at another workshop" are all real controls on that screen.)
 */
export type GuideStep = {
  /** Stable anchor id — the rail scrolls to `#${id}` and the URL hash survives a reload. */
  id: string;
  /** Android-parity feature name. Never invent a new one. */
  label: string;
  /** The dashboard tile's verb for this action ("Record artisan", "Take interview", …). */
  action: string;
  icon: LucideIcon;
  /** The real screen this step is teaching. */
  href: string;
  /** One sentence: what you are doing at this point in the field. */
  summary: string;
  /** Why the dataset needs it — the reason the step is not optional. */
  why: string;
  /** The fields the screen actually asks for, in screen order. Required ones are marked. */
  fields: string[];
  /** Field-tested cautions: the things that trip people up on their first workshop. */
  watch: string[];
};

/**
 * ONE WORDING FOR THE PICKER THAT LANDED ON SIX RECORD FORMS AT ONCE, AND NOT SIX PARAPHRASES.
 *
 * `DesignWorkshopSelect` is mounted on the artisan, product, process and tool forms and on the media
 * and interview pages, and NOT on crafts or workshops, which have no such column. (It said "six of
 * the TEN record steps below" until the same pass that wrote that clause added an eleventh, `scan`,
 * and left the ten standing — which is the failure the header four screens up spends a paragraph
 * on: a count in a comment goes stale as silently as one in copy and is worse, because the next
 * agent reads it as a fact about the file. Six is a fact about `DesignWorkshopSelect` and is checked
 * by `grep -rl DesignWorkshopSelect frontend/components/forms frontend/app`; the denominator was a
 * fact about an array that grows, and is gone.) A designer meets the same control on six screens in a fortnight, so it has to
 * read as one rule; six near-misses of the same sentence read as six unrelated warnings and the
 * reader stops trusting any of them. This is the same treatment, for the same reason, that the
 * "Designer access required" sentence already gets on every card whose route is gated on
 * `canRunDesignWorkshops`.
 *
 * WHAT IT DELIBERATELY DOES NOT SAY: that the picker narrows anything. It does not.
 * `lib/designWorkshopDefault.ts` is explicit that the answer is a SUGGESTION and never a scope —
 * every write is still checked server-side — and a guide sentence implying otherwise would teach a
 * client-side permission the API does not have.
 */
const DESIGN_WORKSHOP_FIELD =
  "“Design & prototype workshop” is a second, separate question from “Workshop” above it, and a record may carry either, both or neither. On a new record it opens on the design workshop you were most recently added to and prints one line underneath saying why it filled itself in — change it if this record belongs somewhere else, or leave it on “Not filed under a design workshop”.";

/**
 * THE MICROPHONE SENTENCE, likewise one wording rather than one paraphrase per card.
 *
 * The two claims in it are the two a designer actually needs and both are checkable:
 * `OnDeviceDictationButton` has no `MediaRecorder`, no `fetch` and no code path that can produce a
 * network request — `e2e/record-form-dictation-unit.spec.ts` asserts that by reading the source,
 * because "it happens not to call fetch today" is not a property a type can carry — and the boxes
 * that are left bare are left bare on purpose, which is the half a reader would otherwise report as
 * a missing feature.
 *
 * ⚠ THE INTERVIEW CARD IS DELIBERATELY NOT ONE OF ITS USERS, although `/questionnaire` grew the
 * same control. That screen already has a microphone doing a DIFFERENT job and the card already
 * teaches it: "Record this question" captures the artisan's voice as a file, uploads it, and has it
 * transcribed on the server. Putting a second microphone sentence beside that one would be two
 * microphones and one word for both — and the difference between them is exactly the thing a
 * researcher must not get wrong, because one of them is a recording of a person and the other is
 * nothing at all. Where two controls share a glyph, the card explains the one with consequences.
 *
 * NOT IN `fields[]` either, and that is the rule rather than a preference: a microphone is not a
 * labelled box, and `fields[]` on the record cards is the real form labels in screen order. The
 * stage card further down DOES carry it as a field, because that card's `fields` are section
 * descriptions for a form built from a server registry — the documented exception, which nothing
 * else may quietly join.
 *
 * ⚠ ITS USER LIST IS NOT `DESIGN_WORKSHOP_FIELD`'S, AND ASSUMING IT WAS IS HOW THIS SENTENCE CAME TO
 * BE MISSING FROM TWO CARDS. The two constants landed in the same pass and read as a matched pair,
 * so the second was mounted wherever the first was — the six forms carrying `DesignWorkshopSelect`.
 * But they are answers to two different questions. `grep -rl DesignWorkshopSelect "app/(protected)"
 * components/forms` returns six files and does NOT return `crafts/page.tsx` or `workshops/page.tsx`,
 * because neither record has that column; `grep -rl DictatedTextInput` over the same tree DOES
 * return both, and their own comments say why they are dictated at all — five boxes on the craft
 * form (`Craft name`, `Local name`, `Category`, `Place`, `Description`) and three plus the notes on
 * the workshop one. Two forms in, two forms out, and one list borrowed for both.
 *
 * WHAT THE OMISSION COST IS NOT A MISSING FEATURE NOTE. It is the confusion the ⚠ above exists to
 * prevent, arriving on the two cards that had no sentence at all to prevent it with: a researcher
 * whose only microphone paragraph is on the interview card has been taught that the glyph means "a
 * recording of a person, uploaded and transcribed", and the first bare one they meet is under
 * `Craft name` on a form this guide describes as complete. The two readings of that button are "my
 * words become text here on this handset" and "the artisan's voice is leaving this device", and a
 * guide that names the second and not the first has taught the wrong one. The first card in the
 * array was one of the two, which is the worst place for it: it is open on arrival.
 */
const DICTATION_ON_THIS_FORM =
  "The prose boxes on this form each carry a microphone. Speech is turned into text by the browser on THIS device — nothing is recorded, nothing is uploaded, and it works with no signal. Numbers and codes are deliberately left bare, because a recogniser hands back the nearest dictionary word for a string that is not one.";

export const GUIDE_STEPS: GuideStep[] = [
  {
    id: "workshop",
    label: "Workshop",
    action: "Record workshop",
    icon: UsersRound,
    href: "/workshops?new=1",
    summary:
      "Open the workshop you are documenting under — or create it — before you record anything else.",
    why:
      "Every record you make is scoped to a workshop. Products, tools and interviews all carry a linked workshop, and the Data Browser opens on \"By workshop\", which files the whole repository under the workshop it was recorded in. On a create form the most recent workshop you have access to is preselected, so getting this right once saves you picking it on every screen afterwards.",
    // TWO REAL CONTROLS WERE MISSING FROM THIS LIST SINCE THE FILE WAS FIRST WRITTEN, and the
    // provenance is worth stating so the next reader does not go hunting for the wave that broke it:
    // `git log -S'Kind of workshop'` returns one commit, the one that ADDED the control, and this
    // card has never named it. It is drift from the initial commit rather than a recent regression.
    // Read off `app/(protected)/workshops/page.tsx` in screen order: `Kind of workshop` is FIRST, at
    // `:529`, above the title — and `Status` sits between the date range and the description at
    // `:586`, drawn as a dropdown for a reviewer and as a locked "Pending" chip for everybody else.
    fields: [
      "Kind of workshop (required)",
      "Workshop title (required)",
      "Place (required)",
      "Workshop duration (Start date and End date)",
      "Status",
      "Description",
      "Notes",
      "Linked artisans",
      "Crafts covered",
      "Workshop media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Create the workshop before you leave for the field — it is the container everything else drops into.",
      "“Kind of workshop” is the box that decides whether this record can be picked up later by a design & prototype workshop. Mark it as one and it appears in that page’s “Start from a recorded workshop” list; anything else stays out of it, which is what stops that list offering every craft-documentation visit ever recorded.",
      "Records created outside a workshop's date window are flagged as out-of-window and need a reviewer's approval.",
      // THE SILENT-EMPTINESS BULLET, AND IT IS ON THIS CARD BECAUSE THIS IS THE ONE PLACE A READER
      // IS STILL EARLY ENOUGH TO ACT ON IT.
      //
      // Every record form offers "Not linked to a workshop" (`WorkshopSelect`'s `NO_WORKSHOP_LABEL`)
      // and its own empty state says a record can be saved without one — so this is a state an
      // ordinary researcher can reach on their first afternoon, by pressing nothing unusual. The
      // consequence is the one this repository's most repeated bug class is named after, and
      // `WorkshopMappingPanel`'s header states it as fact rather than as a risk: a record with an
      // empty `workshopId` counts towards NO workshop scope while staying perfectly visible under
      // "All records", and since the app opens scoped to the most recent workshop, the screen reads
      // as "nothing was documented here" instead of as a filter hiding data that is sitting right
      // there. It has already happened at this repository's scale — twenty-five questionnaire
      // interviews and nine hundred and twenty-four media files, all recorded at the one workshop
      // that existed, none of them carrying its id.
      //
      // NAMED, NOT LINKED, AND THE ASYMMETRY IS THE POINT. The remedy is an admin's: the panel is
      // rendered on `/workshops` behind `isAdmin` and its endpoints are `require_admin`, so a `watch`
      // bullet sending a researcher to press it would send them to a control that is not on their
      // screen. What a researcher can do is notice the state and say so, which is why the bullet
      // gives them the panel's exact on-screen heading — "Records not filed under a workshop" — to
      // say it with. A caution the reader cannot act on is worth writing only when it tells them who
      // can.
      "A record saved with “Not linked to a workshop” is not lost, but it stops counting towards every workshop-scoped view — and because those screens open on your most recent workshop, the effect is a page that says nothing was documented rather than a filter hiding it. Pick the workshop while you are on the form. If some are already saved that way, an admin can re-file them in one press from “Records not filed under a workshop” at the top of this screen.",
      // NO `DESIGN_WORKSHOP_FIELD` HERE, and its absence is a fact about the schema rather than an
      // oversight: a Workshop has no `designWorkshopId` column and `DesignWorkshopSelect` is not
      // mounted on this page. A workshop is not filed under a design workshop — it is CHOSEN BY one,
      // through the "Kind of workshop" box the bullet above this one describes. Adding the sentence
      // here would tell a designer to look for a picker this form does not draw.
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "craft",
    label: "Craft",
    action: "Add craft",
    icon: Brush,
    href: "/crafts?new=1",
    summary: "Add the craft being documented so artisans, products and tools have something to hang off.",
    why:
      "Craft is the shared vocabulary of the repository: artisans link to a craft, products and tools inherit the craft name from it, and the Data Browser groups every workshop's contents by craft. Adding it once keeps spellings consistent across everyone's records.",
    // Read off `app/(protected)/crafts/page.tsx` in screen order. `Workshop` WAS MISSING and it is
    // the FIRST control on the form (`:411`), above the craft name — the page's own comment says
    // why it leads: "the workshop leads every other dropdown: it is the context the record belongs
    // to". A card that starts at "Craft name" teaches a researcher to skip the box that files the
    // record, which is the silent-emptiness failure the workshop card above spends a paragraph on.
    // NO `Design & prototype workshop` HERE, and its absence is a fact about the schema rather than
    // an oversight: `DesignWorkshopSelect` is not mounted on this page, because a Craft has no such
    // column. There is no Status and no Location on this form either.
    fields: [
      "Workshop",
      "Craft name (required)",
      "Local name",
      "Category",
      "Place",
      "Description",
      "Craft media"
    ],
    watch: [
      "Check the list first — if the craft already exists, reuse it instead of creating a near-duplicate spelling.",
      "The local name matters as much as the English one; record what the community actually calls it.",
      // THE MICROPHONE UNDER "Local name" IS THE REASON THIS CARD NEEDS THE SENTENCE MORE THAN MOST,
      // and the reason is in the box's own comment on `crafts/page.tsx`: that field is Devanagari or
      // Gujarati, it is deliberately NOT title-cased because capitalising means nothing there, and
      // it still gets a microphone because the recogniser takes whichever language it is set to and
      // Hindi, Odia and Gujarati are all in `DICTATION_LANGUAGES`. A researcher who does not know
      // that types a transliteration, which is the one thing the bullet above asks them not to do.
      // The sentence below does not name the languages — that list is the component's to change —
      // but it is what puts the reader in front of the button that has them.
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "artisan",
    label: "Artisan",
    action: "Record artisan",
    icon: UserIcon,
    href: "/artisans/new",
    summary: "Record the person: who they are, where they work, how to reach them, and what they have learnt.",
    why:
      "The artisan is the anchor of the dataset. Products, processes, tools and questionnaire interviews all link back to an artisan record, and the Do's and Don'ts are the artisan's own hard-won craft knowledge — the part of the archive that cannot be reconstructed later.",
    // ⚠ SIX REAL BOXES WERE MISSING FROM THIS LIST AND TWO OF THEM ARE THE REGULATED ONES, which is
    // the worst shape this card could have taken. `fields[]` is declared as the real form labels in
    // screen order, so a designer plans a sitting from it — and it named neither identity number.
    // Aadhaar and the Artisan Pehchan Card carry the strictest handling rules in the product (masked
    // storage, never rendered in a list or an export, a mask posted back verbatim for one and omitted
    // entirely for the other), and they are the one part of this form nobody can improvise in front
    // of an artisan: you either brought the card to the sitting or you did not. A list that looks
    // complete and omits them sends somebody to a village unprepared for exactly the questions that
    // cannot be answered later over the phone. `Date of birth`, `Practising since` and `Experience`
    // were missing on the same reading — they arrived with the workshop's participant table, which is
    // what the report prints an age and years of experience from.
    //
    // AND THE WORKSHOP PAIR WAS IN THE WRONG PLACE, not merely absent: this list opened on "Name"
    // while the form opens on the workshop, whose own comment says why — "the workshop opens the
    // form, because it is the context every other answer belongs to". Screen order is half of what
    // this array promises; a list read with the form open beside it is the use it was written for.
    //
    // Re-read off `components/forms/ArtisanForm.tsx` in screen order 2026-08-31: workshop (`:1183`),
    // design & prototype workshop (`:1194`), name (`:1207`), local name (`:1219`), craft (`:1227`),
    // or new craft name (`:1256`), place (`:1268`), gender (`:1278`), date of birth (`:1316`),
    // practising since (`:1366`), experience (`:1414`), phone (`:1501`), email (`:1504`), address
    // (`:1537`), notes (`:1559`), the Identity group — Aadhaar (`:1588`) and the Pehchan pair
    // (`:1602`) — do's (`:1608`), don'ts (`:1614`), status (`:1620`), media (`:1623`), location
    // (`:1638`).
    //
    // THE TWO CONDITIONAL MARKS ARE MARKED AS CONDITIONAL RATHER THAN FLATLY, because the rule is
    // "check the control" and both controls answer "it depends". `AadhaarField` is mounted
    // `required={aadhaarRequired}`, and `aadhaarRequired` is `!initial || …` — so it is required on
    // every NEW artisan, which is the screen this card links to, and stands down only on an old
    // record that predates the rule. `PehchanFields` sets `required={available}` on the number, so
    // the Yes/No box directly above it decides: answering No disables the number and clears it.
    // "Experience" is the FieldBlock heading over the Years and Months pair, which is how this file
    // already names a two-box group (see the designer profile card's "Designer’s experience").
    fields: [
      "Workshop",
      "Design & prototype workshop",
      "Name (required)",
      "Local name",
      "Craft (required)",
      "Or new craft name",
      "Place (required)",
      "Gender",
      "Date of birth",
      "Practising since",
      "Experience",
      "Phone",
      "Email",
      "Address",
      "Notes",
      "Aadhaar number (required)",
      "Artisan Pehchan Card available",
      "Artisan Pehchan Card number (required if the card is available)",
      "Do's (positive prompt) (required)",
      "Don'ts (negative prompt) (required)",
      "Status",
      "Artisan media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Do's and Don'ts are required. Press Enter for each new point — one lesson per line.",
      "You must either select an existing craft or type a new craft name; the form will not save with neither.",
      "Photo EXIF is retained and summarised into the notes automatically — you do not need to transcribe camera details by hand.",
      // ── THE FOUR BULLETS THE SIX NEW FIELDS OWE THE READER ────────────────────────────────────
      // A field named on a card and left unexplained is worse than one omitted where the field has
      // rules a researcher cannot infer from the box: they meet the mask, or the disabled number, and
      // read it as the form being broken. Every clause below is `ArtisanForm.tsx`, `AadhaarField.tsx`
      // or `PehchanFields`, not the printed guide — the doc says most of this and copy written from
      // copy is the failure this whole file exists to prevent.
      "The Aadhaar number is what stops the same artisan being recorded twice. It is checked as you type and again on save, and if the person is already in the archive you are shown their record — that is the field working. Add to that record rather than opening a second one. It is required on a new artisan; a record entered before the rule still saves without one.",
      "Wherever the number is shared or exported it appears masked, as “XXXX XXXX 9012”. If a box opens on a mask, leave it alone — saving with the mask still in it is recognised as “unchanged”, so you never have to retype a number you were not shown.",
      "The Pehchan card is two answers and the order matters. Answer “Artisan Pehchan Card available” first: Yes makes the number required, No clears the number and locks the box. There is no way to store a card number for an artisan who says they hold no card.",
      "“Date of birth” and “Practising since” are DATES, and the age and the years of experience are worked out from them every time they are read — here, in the workshop’s participant table, and in the report. There is deliberately no age box: a number typed today is wrong within a year with nothing anywhere to say so. The “Experience” pair beside them is the stated fallback, read only while “Practising since” is empty.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "product",
    label: "Product",
    action: "Record product",
    icon: Package,
    href: "/products/new",
    summary: "Record one thing this artisan makes, with its measurements, economics and photographs.",
    why:
      "The product record is where the craft becomes measurable: dimensions, cost of making, selling price and market demand are the fields researchers compare across regions. Link it to the artisan and the craft and the whole chain stays navigable.",
    // Read off `components/forms/ProductForm.tsx` in screen order. THE WORKSHOP PAIR WAS LISTED
    // THIRD AND FOURTH AND IS DRAWN FIRST AND SECOND (`:849`, `:855`) — the same drift the artisan
    // card carried, from the same wave that put the workshop at the head of every record form. No
    // label is missing here; the two measuring panels between "Height (inches)" and "Cost of making"
    // are instruments rather than boxes — they PROPOSE a number into the three dimension fields
    // already listed, and the watch bullets below name both.
    fields: [
      "Workshop",
      "Design & prototype workshop",
      "Product name (required)",
      "Local name",
      "Product type",
      "Linked craft (fills craft name)",
      "Craft name (required)",
      "Linked artisan (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Time taken to complete",
      "Size",
      "Length (inches)",
      "Breadth (inches)",
      "Height (inches)",
      "Cost of making",
      "Selling price",
      "Market demand",
      "Raw materials used",
      "Main tools used",
      "Function or use",
      "Remarks",
      "Status",
      "Product media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Pick the linked craft first — the artisan dropdown stays disabled until a craft is chosen, then only lists that craft's artisans.",
      // ⚠ ONE BULLET DESCRIBED THE FALLBACK AS IF IT WERE THE ONLY ROUTE, AND IT NAMED THE WRONG
      // ONE. It read: 'Use "Document using grid" to photograph the piece against the measuring grid:
      // it fills length, breadth and height for you and stores the photo as evidence.' Two things in
      // that sentence stopped being true on 2026-08-27. "Document using grid" is now the SECOND
      // panel on the form — `GridMeasurement`, which posts the photograph to a vision model that
      // ESTIMATES the inches, needs a connection it has no queue or retry behind, and cannot say how
      // it reached a number. And "fills … for you" is the verb `ProductForm` itself struck out:
      // NOTHING auto-fills any more, on either route, because `records.merge_field_provenance`
      // stamps a changed field with the name of whoever pressed Save, so a number that filled itself
      // in would be stored asserting that a named human measured it. Both routes PROPOSE and a
      // person accepts. Teaching the estimate as the primary path is the expensive half of the
      // error: it sends a designer in a courtyard with no signal to the one control that cannot work
      // there, past the one that can.
      "“Measure from a photograph” is the first of the two measuring panels and the one to reach for: lay the piece on the one-inch grid sheet, mark across a known number of squares, and it works out the inches on THIS device — no connection, no cost, and an error bar that narrows visibly as you zoom in to place a mark more carefully.",
      "Neither panel writes a dimension. Each one PROPOSES a number and you press the button that accepts it into a box, because a figure that filled itself in would be saved under the name of whoever pressed Save — asserting that a person measured it. The photograph you measured on is uploaded with the record either way, so the number can be checked against the picture it came from.",
      "“Document using grid” underneath is the fallback, and it is a different instrument: it asks a vision model to ESTIMATE the inches, so it needs a connection, has no retry, and cannot show its working. It is there for the piece that will not lie flat or the frame you cannot mark.",
      "Choosing a linked artisan fills the artisan name and place; choosing a linked craft fills the craft name.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "process",
    label: "Process",
    action: "Document process",
    icon: GitBranch,
    href: "/processes?new=1",
    summary: "Walk through how that product is made, one step at a time, filming each step as it happens.",
    why:
      "The process is the craft itself. A product photograph shows the result; the step-by-step record with per-step media shows the knowledge — the sequence, the hand movements, the judgement calls that a text description always loses.",
    // Read off `components/forms/ProcessForm.tsx` in screen order. FOUR CONTROLS WERE MISSING and
    // one of them is the field this whole card exists to feed: "What happens in this process"
    // (`:1312`) is the box the design-workshop report prints under “What happens”, in the
    // traditional-process table and above it. A card that lists the STEPS and not the paragraph the
    // report is built from teaches a designer to leave the report's own text empty.
    //
    // ⚠ TWO OF THE PER-STEP ROWS THEN NAMED LABELS THE FORM DOES NOT DRAW, which is the same defect
    // as the questionnaire card's invented "Date" one card down, only quieter because the words were
    // plausible. It read "additional context notes (optional)" and "attached media"; the step card
    // draws a CHECKBOX, "Record additional information" (`:1622`), and only once it is ticked does a
    // notes control appear, headed "Additional context for this step" (`:1627`). The media card
    // under it is titled "Attach media" (`:1636`). A researcher hunting the screen for either of the
    // two old strings finds nothing, and the checkbox that gates the notes was named by neither — so
    // the box the card promised was, for most readers, genuinely not on the screen. Three rows now,
    // in the order the step draws them.
    fields: [
      "Workshop",
      "Design & prototype workshop",
      "Name of the process (required)",
      "Artisan (required)",
      "Product (required)",
      "What happens in this process",
      "Pre-processes available",
      "Per step: Name of the step (required)",
      "Per step: Record additional information",
      "Per step: Additional context for this step",
      "Per step: Attach media",
      "Status"
    ],
    watch: [
      "Add a step with \"Add Another Step\" and pick Sequential for an ordered stage, or Group of activities for things done together.",
      "Video is the preferred format for steps — capture the action as it happens rather than posing the result.",
      "Document the process against the product you already recorded, so the two stay linked.",
      "“What happens in this process” is the one box on this form a design-workshop report prints verbatim. Write the sequence in your own words there, not only as step names — the steps are the record, that paragraph is the document.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "tool",
    label: "Tool",
    action: "Record tool",
    icon: Wrench,
    href: "/tools/new",
    summary: "Record the toolkit the artisan uses: what it is made of, how big it is, who made it, what it costs to replace.",
    why:
      "Tools are the most quietly endangered part of a craft — the maker of a tool often disappears before the craft does. Replacement cost, maker and tradition type are the fields that record whether the toolchain behind the craft is still alive.",
    // Read off `components/forms/ToolForm.tsx` in screen order. Every label matched on a re-read of
    // 2026-08-31 except the position of the workshop pair, which is drawn first and second (`:958`,
    // `:964`) and was listed fourth and fifth — the artisan and product cards carried the same drift.
    // The two measuring panels sit between "Radius" and "Maker" and are not listed, for the reason
    // the product card gives: they propose into boxes already named here, and the watch bullets say
    // which boxes (this form is the one with two of them called "Height").
    fields: [
      "Workshop",
      "Design & prototype workshop",
      "Toolkit name (required)",
      "Local name",
      "English name",
      "Linked craft (fills craft name)",
      "Craft name (required)",
      "Linked artisan (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Process used in",
      "Material",
      "Years in use",
      "Height",
      "Width",
      "Length (inches)",
      "Breadth (inches)",
      "Height (inches)",
      "Thickness",
      "Weight",
      "Radius",
      "Maker",
      "Tradition type",
      "Replacement cost",
      "Suggestions for improvement",
      "Remarks",
      "Status",
      "Process stages",
      "Tool media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Fill only the dimensions that make sense for the tool — a blade has a length and thickness, a wheel has a radius.",
      // The same pair of panels as the product form, and the same correction — see that card for
      // what the sentence used to say. The one difference is worth its own clause and is not
      // cosmetic: this form has BOTH a plain “Height” box and a “Height (inches)”
      // one, and since 2026-08-27 the measuring panels write only into the second, because that is
      // the column that can carry the marker recording HOW the figure was reached. A designer who
      // types into the plain box and then wonders why the panel will not accept into it is reading
      // two boxes with one name.
      "“Measure from a photograph” is the first of the two measuring panels: lay the tool on the one-inch grid sheet, mark across a known number of squares, and it works out the inches on THIS device with no connection. It accepts into Length, Breadth and “Height (inches)” — never into the plain “Height” box, which is left to whoever typed in it.",
      "“Document using grid” underneath is the fallback: it asks a vision model to ESTIMATE the inches, needs a connection, and cannot show its working. Use it for the tool that will not lie flat. Neither panel writes a number by itself — both propose, you press the button that accepts, and the photograph you measured on is uploaded with the record so the figure can be checked against it.",
      "\"Process stages\" archives your captures in order as STAGE_STEP_1, STAGE_STEP_2, … so shoot them in sequence.",
      "You can also hand tools to specific artisans later from \"Assign tools to artisans\" — for your own artisans, ones shared with you for editing, or any artisan if you are an admin.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "questionnaire",
    label: "Questionnaire",
    action: "Take interview",
    icon: ClipboardList,
    href: "/questionnaire?new=1",
    summary: "Sit down with the artisan and work through the interview sections, recording each answer as audio.",
    why:
      "The questionnaire is the artisan speaking in their own voice and their own language. Recorded audio is auto-transcribed on the server, so you get both the original recording and searchable text without typing during the interview.",
    // ⚠ THIS LIST INVENTED A CONTROL AND DROPPED FOUR REAL ONES, which is precisely the failure the
    // labels rule at the top of this file exists to prevent, and the invented one is the worse half.
    // There is NO "Date" box on the interview form and there never has been: `questionnaire/page.tsx`
    // says so in its own comment where the field would sit (`:1010`) — the server derives
    // `interviewDate` from `recordedAt`, which is when the interview was actually captured, and
    // "asking a researcher to confirm today's date was a field to tab past that could only ever be
    // wrong". A card naming it sends a designer hunting for a box, and the ones who find nothing
    // conclude the screen is broken rather than that the guide is. Re-read in screen order
    // 2026-08-29: title (`:1003`), place (`:1015`), language (`:1056`), workshop (`:1071`), design &
    // prototype workshop (`:1076`), status (`:1077`), primary artisan (`:1094`), additional artisans
    // (`:1133`), the per-question boxes, and interview notes (`:1359`).
    //
    // ⚠ FOUR MORE WERE ADDED 2026-08-31, and three of them are decisions a researcher makes BEFORE
    // the artisan sits down, which is the worst kind of control to leave off a card somebody plans a
    // sitting from. `<QuestionnaireCaptureControls>` draws two: "Recording mode", which is the choice
    // between one take per question and one take for a whole section, and "Do not display answer text
    // boxes", which `DEFAULT_CAPTURE_PREFS` has ON — so a reader who was never told it exists meets a
    // screen with no typing boxes at all and concludes the form is broken. The "Interview audio"
    // capture card under it is the take for the interview as a whole, distinct from the per-question
    // ones and never hidden by that toggle, and `<LocationFields />` sits under that. Named rather
    // than cited by line, unlike the boxes above: that page was being edited in a neighbouring lane
    // as this was written, and a line number that is already wrong is worse than none at all.
    //
    // AND THEY SIT ABOVE THE QUESTIONS, NOT AT THE FOOT. `docs/WALKTHROUGH.md` said "at the foot",
    // which is where the notes are; the audio and the location are between "Additional artisans" and
    // the first section. Screen order is half of what this array promises, so the doc was corrected
    // to match the form rather than this list to match the doc.
    fields: [
      "Interview title (required)",
      "Place",
      "Language",
      "Workshop",
      "Design & prototype workshop",
      "Status",
      "Primary artisan",
      "Additional artisans",
      "Recording mode",
      "Do not display answer text boxes",
      "Interview audio",
      "Location (GPS fix or map pin)",
      "Per question: \"Record this question\" audio, or typed answer",
      "Interview notes"
    ],
    watch: [
      "There is one interview per exact set of artisans. If an entry already exists for that set, saving adds your answers to it — it never creates a duplicate.",
      "There is no date to fill in. The interview is dated from when it was actually recorded, so there is nothing here to tab past and nothing to get wrong.",
      "Language is a closed list of twenty-four, in the same order as the handset, with Hindi and English at the top because that is what most interviews are conducted in. If an older interview holds something not on the list — a dialect, or somebody’s own spelling — it is offered first and never overwritten.",
      // THE DEFAULT THAT READS AS A BROKEN SCREEN. `DEFAULT_CAPTURE_PREFS` has `hideAnswers` ON, and
      // the toggle's own hint says so — "On by default — show only the record button." A researcher
      // who came to type finds no box to type in and no reason given, which is the one state on this
      // form a card can spare somebody entirely.
      "“Do not display answer text boxes” is ON when you first open the screen, so each question shows only its record button. Turn it off to type written answers. “Recording mode” beside it decides whether a take covers one question or a whole section — set both before the artisan sits down.",
      "Answer only the questions actually asked; empty questions stay open for whoever picks the interview up next.",
      "Questions already answered by someone else can only be changed by that contributor or an admin.",
      "Use \"Check completion\" at the top of the screen to see the artisans × sections matrix and find the gaps.",
      DESIGN_WORKSHOP_FIELD
    ]
  },
  {
    id: "media",
    label: "Miscellaneous Media",
    action: "Upload media",
    icon: Images,
    href: "/media",
    summary: "Upload the photographs, video, audio and files that do not belong to any single record.",
    why:
      "Field work produces context that no form has a slot for: the road into the village, the market, an unplanned conversation. Miscellaneous Media keeps that material inside the repository instead of on a phone that gets wiped.",
    fields: [
      "Capture media — images, video, audio and documents",
      "Media title / object name",
      "Linked record type (required)",
      "Linked entry (optional)",
      "Design & prototype workshop",
      "Caption",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Upload stays disabled until you pick a Linked record type. If the file belongs to nothing in particular, pick \"Miscellaneous Media\" and leave the entry blank.",
      "Audio uploaded here is queued for transcription after upload, exactly like interview audio.",
      "If the file does turn out to belong to a record, link it — misc media can be attached to a record afterwards.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "review",
    label: "Review",
    action: "Track your submissions",
    icon: ClipboardCheck,
    href: "/review",
    summary: "Everything you submit goes into the review queue and comes back Approved, Rejected, or Sent for revision.",
    why:
      "Review is what turns a pile of field notes into a dataset anyone can cite. It also means you are never the last check on your own work — a reviewer above your tier reads every record before it counts as final.",
    fields: [
      "Pending — submitted, waiting for a reviewer",
      "Approved — final, counted in the dataset",
      "Needs revision — comments explain what to change",
      "Rejected — not going into the dataset"
    ],
    watch: [
      "Below Professor the status chip is locked: whatever you create is submitted as Pending. That is normal, not an error.",
      "\"Send for revision\" always carries mandatory comments — read them, fix the record, and saving resubmits it as Pending.",
      "Reviewers only see submissions from contributors ranked strictly below them; the master admin sees everyone."
    ]
  },
  {
    id: "view-data",
    label: "View Data",
    action: "Browse records",
    icon: Eye,
    href: "/data",
    summary: "Browse the whole repository as a directory tree and export a report of any subtree.",
    why:
      "This is where the documentation stops being data entry and starts being research material: the same records, filed three different ways, previewable in place and downloadable as a spreadsheet.",
    fields: [
      "By workshop — every record filed under the workshop it was made in (the view it opens on)",
      "By uploader — a workshop's records filed under the researcher who uploaded them",
      "By media type — every file filed by what kind of file it is",
      "Download report (.xlsx)",
      "Download any folder as a zip, with content-type filters"
    ],
    watch: [
      "Pick a folder, then use the breadcrumb to move back up — the tree loads lazily as you expand it.",
      "Transcripts and AI text render as formatted Markdown in the preview pane, not raw text.",
      "Dataset download is a granted permission. If your role does not have it the browser shows a restricted notice — use Search to find records instead."
    ]
  },
  {
    // LAST OF THE RECORD STEPS AND NOT FIRST OF THE WORKSHOP ONES, although a card and a tag are
    // things a design workshop prints. What decides it is who may open the screen: `/scan` is
    // ungated, like `/search`, because every endpoint behind it scopes its answer per viewer on the
    // server — so this is the last step in the arc a researcher without designer access can follow,
    // and putting it after the padlock would hide a destination they can actually reach.
    id: "scan",
    label: "Scan a code",
    action: "Open",
    icon: QrCode,
    href: "/scan",
    summary:
      "Point the camera at an artisan card or a prototype tag — or read the code out of a picture somebody sent you — and open the record it names.",
    why:
      "Every card and every tag carries a code, and typing a name to find the record behind it is where the wrong record gets opened. This is a door named after the thing in your hand rather than after reading a list: it was reachable only by opening Browse records and noticing a panel above the search box, which is three steps, none of them named after what you are doing.",
    fields: [
      "Scan with the camera",
      "Upload a picture — or drop one here, or paste it with Ctrl+V",
      "Or type the code printed under the QR",
      "The record it resolved to, with one press to open it"
    ],
    watch: [
      "A picture works as well as the card itself — a screenshot, a photo taken earlier, or one forwarded to you. That is the case this screen was widened for: a code arriving over a messaging app does not have to be saved to disk first.",
      "Hold the card 10–15 cm from the lens with the whole square in view. If the camera will not open, the screen says which of the three reasons it is and offers the picture and the typed code instead of failing silently.",
      "This page asks the repository, so it wants a connection. A design workshop’s own Cards & tags page reads that workshop’s codes out of the draft held on this device first, which is why a prototype tag still resolves there in a village with no signal.",
      "A scan inside a stage form is doing a different job: there it LINKS a record to the box you are filling in, rather than opening it.",
      "A code for a record you may not read answers “not found” rather than “not allowed”, on purpose — so a code can never be used to find out what exists."
    ]
  },

  /* ──────────────────────────────────────────────────────────────────────────
   * The design & prototype development workshop — what the record steps above are for.
   *
   * These mirror the section of the same name in `docs/WALKTHROUGH.md`. That file and this array are
   * TWO RENDERINGS OF ONE THING and its maintenance rule says they move in the same commit: add a
   * step here, add it there.
   *
   * ⚠ THEY ARE NOT ONE-FOR-ONE AS OF 2026-08-29, AND NO TEST WILL TELL YOU SO. Two steps were added
   * to this arc — `design-workshop-questionnaires` and `design-workshop-inspection` — and the doc
   * was not edited, because `docs/` sits outside the territory this pass was allowed to touch and a
   * half-applied edit to a file another lane is holding open is worse than a debt written down.
   * Closing it is THREE edits in one commit and not one:
   *   1. a section per new step in `docs/WALKTHROUGH.md` naming its route, because
   *      `e2e/guide-walkthrough-unit.spec.ts` reads that file for one route per arc id;
   *   2. the same two ids appended to `WORKSHOP_ARC` in that spec — a THIRD copy of this order,
   *      whose own comment says to update it in the same edit and never afterwards;
   *   3. the doc's printed order matched to the order here.
   * The build is GREEN today, and that is the reason this note is long rather than a comment. The
   * spec's order assertion filters the ids down to the ones already named in `WORKSHOP_ARC`, so an
   * id it has never heard of is invisible to it; the route check only walks `app/(protected)`, which
   * both new hrefs satisfy. A pair-check that cannot see a new member reports green over the gap —
   * the same failure that spec was written to end, arriving from the other side.
   *
   * FIVE OF THESE STEPS POINT AT A LIST AND NOT AT THE SCREEN THEY DESCRIBE, which is the one place
   * this arc cannot keep the promise the record steps above make. Those five screens — Cards & tags,
   * Stages, Readiness, Report, Report history — live under `/design-workshops/[id]/...` and a guide
   * has no workshop id to put there; a link to a made-up one is a 404 delivered by the help. The
   * list is the honest destination, it is one tap from all five, and each `summary` names the screen
   * so a designer knows what they are looking for when they get there. (`Design workshops` is a
   * sixth `/design-workshops` href and is not one of the five: the list IS the screen it teaches.)
   *
   * THE REST POINT AT THEIR REAL SCREENS, and it is worth saying why rather than leaving a reader to
   * wonder whether some were done more carefully than others. `/designers/profile` belongs to the
   * PERSON and never to a workshop, so no id was ever wanted. `/design-review`,
   * `/sketches-and-prototypes`, `/questionnaires` and `/design-workshop-inspections` are top-level
   * siblings of the workshop tree BECAUSE being reachable with no workshop chosen is the whole of
   * what they are for — asking which workshop, or listing the ones you hold, is the first thing each
   * page does. So the id problem does not arise for any of them, and a card that can open the actual
   * screen should.
   * ────────────────────────────────────────────────────────────────────────── */

  {
    id: "designer-profile",
    label: "My designer profile",
    action: "Type your own details, once",
    icon: IdCard,
    href: "/designers/profile",
    summary:
      "Your own standing details, kept in one place — name, institution, qualification, empanelment, photograph, signature and CV — rather than typed into a stage form.",
    why:
      "These are the values a new design workshop's stage 1 and stage 3 start pre-filled with, and they stay editable inside the workshop. The report is printed from the stages and never from this page, which is what makes the pre-fill a COPY rather than a link — and it has to be one: a report records who ran a workshop at the time, so moving institution next year must not rewrite a report already submitted. A designer signing in for the first time is brought here once, with that reason on the screen.",
    fields: [
      // FOUR OF THE TWENTY-ONE ARE MARKED, AS OF 2026-08-27, and the marks are not decoration on this
      // card: `fields[]` is documented as the real form labels in screen order with "(required)"
      // marked, and the `watch` bullet below used to tell the reader in as many words that NONE of
      // them was. `DESIGNER_PROFILE_REQUIRED_FIELDS` in `designers/profileCopy.ts` is the register
      // both profile screens and the server read; these four are it.
      //
      // ⚠ A FIFTH BOX CARRIES AN ASTERISK AND IS NOT IN THAT ARRAY, and reading the array alone is
      // how this card came to say four. `DesignerProfileForm` mounts the empanelment number as
      // `<Field label={…} required>` — unconditionally, with its own comment saying why ("the number
      // is mandatory, and a designer reading this form should be told so whichever state their
      // profile is in") — and the server refuses a CREATE without one in `_assert_empanelment_number`.
      // The native attribute alone is dropped on the grace path, for a profile that already has
      // content, so nobody is locked out of saving their biography; the mark stays either way.
      //
      // TWO OTHER CORRECTIONS FROM THE SAME RE-READ. "Address" is now "Address line", renamed in
      // `profileCopy.ts` on 2026-08-30 to match Android and, in its own words, because "there are now
      // two addresses on this screen and an unqualified word over one of two is the reading a person
      // gets wrong" — this card kept the old word for a day. And the location card at the foot of the
      // Postal address group (`DesignerProfileForm:906`) was never listed at all: it is a real
      // control, it is where the district and the map point live, and the form itself warns that
      // filling it in INSTEAD of the four boxes above produces a report with no address on it. It is
      // not one of the twenty-one columns, which is why the count in the bullet below is stated of
      // the columns rather than of this array.
      "Name (required)",
      "Name in the local script",
      "Designation",
      "Institution",
      "Department",
      "Qualification (required)",
      "Specialisation",
      "Designer’s experience",
      "Designer’s profile",
      "Phone (required)",
      "Email (required)",
      "Website",
      "Address line",
      "City or town",
      "State",
      "Pincode",
      "Location (GPS fix or map pin)",
      "Empanelment number (required)",
      "Empanelment date",
      "Photograph",
      "Signature",
      "CV"
    ],
    watch: [
      // ⚠ THIS BULLET READ "Not one of the twenty-one is required, and none of them is guessed for
      // you. Left blank, the report cover falls back to the name on your account — so the cost of
      // skipping this page is a thin cover, not a refusal." Four of them became required on
      // 2026-08-27 (owner's instruction: "Name, qualification, email, and phone number should be
      // mandatory fields as well"), enforced by the web form's native `required`, by the read-only
      // view's asterisks and by `DesignerProfileUpdate`'s own field validators — so the sentence had
      // gone from true to the opposite of true on the one card a designer reads BEFORE opening the
      // page. The fallback it described still exists for rows saved before the rule; what stopped
      // being true is that skipping those four is free.
      "Five boxes carry an asterisk — name, qualification, phone, email and the empanelment number — because they are what a report is submitted under, how the person who signed it is reached, and the identifier a government document is expected to carry. The rest of the twenty-one are optional, and none of them is guessed for you.",
      "The Postal address group asks twice and the two halves do not fill each other in. The four boxes at the top — address line, city or town, state, pincode — are what a report prints. The location card under them is where the district and the map point live, and nothing on it reaches the document. Fill in both, or a report goes out with no address on it.",
      // ⚠ THIS BULLET ENDED "Either way it reaches your reports as an annexure." AND THAT WAS FALSE —
      // the FIFTH surface to carry the sentence, and the fourth time it was written from a neighbour's
      // copy rather than from the code. No branch of this codebase puts a FILE in a report annexure:
      // `report_builder._images` filters IMAGE and IMAGE_LIST and is the only placement path there is,
      // `ANNEXURE_MEDIA` gathers through it, `report_annexures` is transcripts only, and
      // `report_templates` records that refusal as a DELIBERATE decision with its two reasons written
      // out. What a report does is NAME the file — `format_value` prints "1 document attached" under
      // the field's own label — and `build_report` then warns beside the download that the bytes are
      // not inside it. The true sentence is also the useful one, because it tells the designer the one
      // thing they have to do about it. Wording taken from `designers/profileCopy.ts`'s `cvMediaId`
      // help so the guide and the box it teaches say the same thing.
      "A PDF CV is shown on this page as soon as it uploads; a .docx or .odt is stored and downloadable instead. Your reports NAME it rather than carrying it, so send the file alongside the report.",
      // The mechanism deliberately not described — AND THE RESTRAINT HAS NOW PAID FOR ITSELF, which is
      // worth recording because it is why this bullet needed no edit on 2026-08-26.
      //
      // When it was written, `seed_designer_prefill` copied THE CREATOR's profile and
      // `DESIGN_WORKSHOP_CREATOR_ROLES` is `{ADMIN, MASTER_ADMIN}`, so for a designer "editing this
      // page changes the next workshop you create" was a promise about an act they cannot perform.
      // That is no longer the whole picture: a create may now NAME a designer (`designerUserId`), and
      // then it is the NAMED designer's profile that is copied, into a workshop they did not create.
      // Both outcomes are pinned against a real database in `tests/test_designer_roster.py` —
      // `test_a_workshop_opened_for_a_NAMED_designer_carries_the_DESIGNERS_details` and
      // `test_a_workshop_that_names_no_designer_still_carries_the_ADMINS_details`. (This comment used
      // to cite `test_a_workshop_an_admin_opens_for_a_designer_carries_the_ADMINS_details`, which the
      // same change renamed out of existence.)
      //
      // What IS true either way is the copy semantics, which is all this bullet claims, so the
      // sentence below stands unchanged through a change that moved the mechanism under it.
      // `hero/DesignerWorkbench.tsx`'s header sets the same restraint for the landing page ("ONE
      // WORDING CHECKED AND DELIBERATELY LEFT VAGUE … Do not tighten it back") — same reason, and it
      // is still the right instruction: whose profile is seeded has moved once and can move again.
      "A workshop already under way keeps what it was created with. Correcting something here never reaches back into it — stage 1 and stage 3 of that workshop are where its own copy is edited.",
      // ⚠ THIS SENTENCE COUNTED THE CARDS BELOW IT — "This screen and the eight below it" — and it was
      // wrong in BOTH directions at once by the time anybody read it back. There are ten cards below
      // this one, not eight (`design-workshop-questionnaires` and `design-workshop-inspection` were
      // inserted after the eight was typed), and the tenth is the one card in this arc for which the
      // claim is FALSE: `design-workshop-inspection` is gated on `INSPECTION_ROLES`, a frozen set of
      // exactly one member, which refuses an admin and the master admin exactly as it refuses a
      // designer. So a count written to reassure a reader had quietly grown to assert the opposite of
      // the warning this file gives four paragraphs from the top — that copying this wording onto the
      // inspection card "would be a REGRESSION DRESSED AS CONSISTENCY". Counting reached it anyway.
      //
      // THE FIX IS TO STOP DESCRIBING OTHER CARDS FROM THIS ONE. The sentence is now the same string
      // `design-workshop-questionnaires`, `design-workshop-sketches` and `design-review` already
      // carry, one per gated route, which is the arrangement the top of this file says the repeated
      // wording IS: one predicate (`canRunDesignWorkshops`), stated on each card whose own route
      // answers to it, and never a position or a tally that a later insertion silently invalidates.
      "This screen opens for designers, admins and the master admin. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
    ]
  },
  {
    id: "design-workshop",
    label: "Design workshops",
    action: "Open a design workshop",
    icon: Layers,
    href: "/design-workshops",
    summary:
      "Open the design & prototype development workshop you have been added to — everything below hangs off it.",
    why:
      // ⚠ THIS SENTENCE NUMBERED TWO STEPS — "the Workshop record in step 1 … the records you made in
      // steps 2–10" — and it was the last place in this file that did. The other two were struck out
      // in the same pass that wrote this comment (`GuideHero` named a card by number; the stage card
      // cited a correction as "two cards down"), and the argument given there applies here unchanged:
      // an index in prose is a step count wearing a different hat, and `GUIDE_STEPS` is an array that
      // has grown from ten to sixteen to twenty-two. It survived this long because the one insertion
      // since — `scan` — happened to land at ELEVEN, after the range rather than inside it, so the
      // numbers stayed accidentally right and nothing went red. The next insertion above `view-data`
      // silently repoints both halves at the wrong cards, and no test in this repository reads copy.
      //
      // NAMING THE RECORDS IS ALSO MORE TRUE THAN THE RANGE WAS. "Steps 2–10" swept in Review and
      // View Data, which are not records anybody makes; the six named below are `REFERENCE_MODELS`
      // (`backend/app/services/design_workshops.py`) exactly — Artisan, Craft, ProductDocumentation,
      // ToolDocumentation, Process and QuestionnaireInterview — which is the list a stage's reference
      // pickers really offer, and the same six the stage card's own picker row names.
      "This is the fortnight itself: 22 stages of capture that end in a report submitted to a Development Commissioner’s office. It is a different thing from the Workshop record the guide opens with, and it points AT the artisans, crafts, products, tools, processes and interview sittings you recorded above rather than replacing them. A DESIGNER DOES NOT START ONE — an admin creates it and adds you — and everything inside it is then yours.",
    // §9.9's rule — fields[] is the real labels in screen order — read off the only form on this
    // screen, `design-workshops/page.tsx`'s "Start a design workshop" panel (`:715`–`:830`). It
    // replaces a paraphrase that invented two rows and dropped two real ones: "Craft and place" is
    // four separate boxes and none of them is called Place; "Who else may open it" is not on this form
    // at all (a grant is made elsewhere); and "Report template" and "Notes" are controls the list
    // never mentioned. The panel is gated on `allowCreate`, so a designer reading this card will not
    // see it — which is why the note names whose form it is rather than leaving the reader hunting.
    //
    // ⚠ AND THEN IT DROPPED A REAL ONE AGAIN, which is worth recording under a comment that brags
    // about not doing that. The create panel grew `WorkshopDesignerPicker` — "Designers this
    // workshop is for" (`design-workshops/page.tsx:1039`), full width, between the date range and
    // Notes — and this list did not move. Of everything that could have gone missing it is the worst
    // one: it is the control that decides WHOSE designer profile is copied into stage 1 and stage 3,
    // the mechanism the card above this one spends four bullets describing. One card explained the
    // copy and the other omitted the control that drives it. Re-read in screen order 2026-08-29.
    //
    // "Type of workshop" JOINED IT ON 2026-08-31, out of the same audit: `FieldBlock label="Type of
    // workshop"` sits between the template and the craft (`design-workshops/page.tsx:1489`) and this
    // list stepped straight from one to the other. It is the second box on this screen carrying the
    // word "workshop" in a different sense from the first — "Start from a recorded workshop" points
    // at a Workshop RECORD, this one classifies the design workshop being opened — which is exactly
    // the pair a reader needs named rather than left to be met cold.
    fields: [
      "Start from a recorded workshop — what narrows every reference picker inside the stages",
      "Workshop title (required)",
      "Report template",
      "Type of workshop",
      "Craft",
      "Cluster",
      "State",
      "District",
      "Start date and End date",
      "Designers this workshop is for",
      "Notes"
    ],
    watch: [
      "This list is empty until an admin creates a workshop and adds you to it, and for a newly empanelled designer that is the ordinary state rather than a fault. The screen says who to ask.",
      "Link it to a Workshop record early. The pickers inside the stages are narrowed to that workshop’s artisans, products and tools, and an unlinked workshop offers the whole repository instead — the screen says which you are looking at. Only a Workshop record marked as a design & prototype workshop on its own form appears in that list.",
      "Being NAMED on the create is how you get in. A design workshop is visible to its creator, to admins, and to whoever is named — so “Designers this workshop is for” is not a nicety, it is the door. Several people can be named and all of them can fill in the stages.",
      "One of those names is the LEAD, and the screen says which. That is the designer whose profile is copied into stage 1 and stage 3 and whose name the report carries — a .docx has one author field and cannot hold a list, so whose name lands on a ministry document is decided in the open rather than by a tick order nobody can see.",
      "It opens and fills with no signal. Everything is kept on the device and sent up when there is a connection.",
      "Every stage saves on its own, into a draft on this device, as you go. That is what makes a fortnight of fieldwork survivable: you resume exactly where you left off, and nothing is waiting on one long save at the end.",
      // ⚠ "THIS SCREEN AND THE ONES BELOW IT" REACHED ONE CARD TOO FAR, and the card it reached is
      // the single one in this arc the claim is false for. Everything under this one answers to
      // `canRunDesignWorkshops` — except `design-workshop-inspection`, which answers to
      // `INSPECTION_ROLES` and refuses designers, admins and the master admin alike. "The ones below
      // it" is a position, and positions absorb whatever is appended: the inspection card was
      // appended, and this sentence started making a promise about it without a character changing.
      //
      // IT STILL HAS TO COVER FIVE CARDS, WHICH IS WHY IT IS NOT SIMPLY SHORTENED TO "this screen".
      // Cards & tags, Stages, Readiness, Report and Report history each carry `/design-workshops` as
      // their `href` — they live under `/design-workshops/[id]/…` and a guide has no workshop id to
      // put there — so none of them has a route of its own to hang the sentence on, and without this
      // clause a reader would meet five padlocks this page never warned about. Naming them is stable
      // where counting was not: the five are defined by sharing this card's href, so a step added
      // anywhere in the arc changes nothing here, and a SIXTH screen joining that href is an edit
      // somebody has to make on purpose rather than a number going quietly stale.
      "This screen opens for designers, admins and the master admin, and so do the five it leads to — Cards & tags, Stages, Readiness, Report and Report history. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
    ]
  },
  {
    id: "design-workshop-codes",
    label: "Cards & tags",
    action: "Print the code cards",
    icon: QrCode,
    href: "/design-workshops",
    summary: "Print a code card for every artisan on the roster and a tag for every prototype, and tie the tag to the object.",
    // ⚠ "a list of twenty-five" WAS HERE AND HAS NO SOURCE. `stage_definitions` declares prototypes
    // as `many("prototype", "DwPrototype", …)` and `many()` takes no `max_items` at all, so the list
    // is as long as the fortnight made it. The only twenty-five in the registry is on
    // `photos()`'s docstring and is about PHOTOGRAPHS on a different field; the figure reached this
    // card from the prose comment at the top of `codes/page.tsx`, which is where a plausible number
    // in a neighbour's comment becomes a fact in help text. The point the sentence is making — that
    // choosing from a long list is where the mistake happens — does not need a number to land.
    why:
      "Stages 14, 15 and 16 each begin by choosing a prototype from a list as long as the fortnight made it. Scanning a tag removes the choosing — and choosing wrong is how two days of measurements end up attached to somebody else’s work, with nothing downstream able to tell.",
    fields: [
      "Artisan cards — one per roster entry",
      "Prototype tags — one per prototype",
      "Print sheet, sized for a home printer",
      "Scan a code back — camera, an uploaded picture, a dropped or pasted picture, or typed"
    ],
    watch: [
      "Print them at the START of the fortnight, before the prototypes exist in numbers. A tag tied on afterwards is a tag tied on from memory.",
      "The codes are on the stage index too — you do not need this screen to scan one. Scan a code, further up this guide, opens whatever record a card or a tag names from anywhere in the app; this screen is the one that still resolves its own workshop’s codes with no signal.",
      "The sheet prints from the browser off the local draft, and the code is decoded in the browser as well, so both halves work with no signal.",
      "A JOIN CARD is a different code doing a different job: one person creates the workshop and the others scan a card to join THE SAME one, which is what stops a team ending the fortnight with four parallel workshops. It is minted and scanned on the handset — there is no join card on the web — and a card is good for one person unless an admin makes it good for more. A late-comer whose card was already spent is not turned away: the ask is filed for an admin to decide, so their work is not orphaned while they wait."
    ]
  },
  {
    // HERE RATHER THAN AS A BULLET ON THE `questionnaire` CARD ABOVE, AND THE PLACEMENT IS THE WHOLE
    // ARGUMENT. `/questionnaire` and `/questionnaires` differ by one character and are two different
    // instruments with two different models: the singular is the ONE global artisan questionnaire
    // every signed-in researcher answers, the plural is a designer's own form, gated on
    // `can_run_design_workshops`. `lib/permissions.ts` and `DynamicIslandNav` each carry a paragraph
    // about keeping them apart — a route rule spelled with the singular would lock every researcher
    // out of taking an interview. Hanging "Reuse at another workshop" off the singular card would
    // have taught exactly the confusion both of those comments exist to prevent, in the one place a
    // designer goes to learn the difference.
    //
    // AND IT SITS ON THE FIRST AFTERNOON, beside the code cards, because that is when the instrument
    // is prepared: download the pro-forma, type the questions in Excel, upload it back. The stages
    // take the fortnight; this and the cards do not.
    id: "design-workshop-questionnaires",
    label: "My questionnaires",
    action: "Build your own questionnaire",
    icon: FileSpreadsheet,
    href: "/questionnaires",
    summary:
      "Build the research instrument this workshop needs in a spreadsheet: download the pro-forma, type your questions into it, upload it back, and record the answers here.",
    why:
      "The artisan questionnaire further up this guide is one global instrument, shared by the whole repository, and it cannot be changed for your cluster. This is the other kind — a form you write yourself and attach to your design workshop — and every one of the six report templates prints its answers at the back as “Annexure — Questionnaire responses”. The spreadsheet is the point rather than a convenience: building a questionnaire box by box in a browser is the slow path, and it is offered third and quietly for that reason.",
    fields: [
      // "Kind" WAS MISSING AND IT IS NOT A COSMETIC BOX. Added to the create form on 2026-08-30 at
      // the owner's request ("they also do market survey interviews, so create that differentiation
      // as well, so that we can map the questionnaires and the transcripts to the correct stage in
      // the report"), it decides which stage of the report this questionnaire's answers are filed
      // under — its own hint on `questionnaires/page.tsx:592` says so. A designer who leaves it on
      // "Not stated" because no card mentioned it gets answers filed nowhere in particular.
      "Download the pro-forma",
      "Upload a filled-in pro-forma",
      "Or “Start an empty one” — Title, Attach to a design workshop, Kind, Description",
      "Per section: Section title, Code",
      "Per question: Question, Help text",
      "Record answers",
      "Reuse at another workshop",
      "Download question set, or Download .xlsx"
    ],
    watch: [
      "THIS IS NOT THE QUESTIONNAIRE FURTHER UP. “Take interview” is the repository’s one shared artisan questionnaire, open to every signed-in researcher; this is a designer’s own instrument, attached to one design workshop. The two are separate features with separate tables, and answering in the wrong one is not a mistake either screen can catch for you.",
      "The sheet may come back with answers already typed into it, or with none at all — both are ordinary. The upload reports what it read before anything is saved.",
      "“Reuse at another workshop” COPIES. Two rows, two question trees, two histories: correcting a typo on one never touches the other, and no sitting and no answer comes across. The dialog says so while you are choosing the target, rather than reporting it afterwards.",
      "Creating one works with no signal: the row is banked on this device and lands when there is a connection. What is queued is the questionnaire ITSELF and not its sections and questions — those need the id the server mints — so write them once the row has landed, and the screen says as much.",
      "A questionnaire published as the standard form appears in everybody’s list, badged so a row you did not upload cannot read as somebody else’s work leaking in. Publishing and withdrawing are on the questionnaire’s own page.",
      "This screen opens for designers, admins and the master admin. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
    ]
  },
  {
    id: "design-workshop-stages",
    label: "Stages",
    action: "Fill the stages",
    icon: ClipboardList,
    href: "/design-workshops",
    summary:
      "Work through the 22 stages. The stage index shows a completeness figure against each one; open a stage and the form is built from the registry the server publishes.",
    why:
      "The stages ARE the report: every section of the printed document is one of them. The web and the phone draw the same boxes in the same order because both read the same registry, so a stage half-filled on a handset in the village is the stage you finish on a laptop that evening.",
    fields: [
      // ⚠ THIS READ "the ones a submit is refused without", WHICH IS THE SENTENCE THIS FILE ALREADY
      // CORRECTED ON THE `design-workshop-readiness` CARD and left standing here, so one page said
      // both things. (That correction used to be cited as "two cards down". Counting cards is the
      // same defect as counting steps: three have been inserted into this array since, and a reader
      // who follows the count lands on the wrong card and concludes the note is stale.) Authority:
      // `readiness/page.tsx`'s `WORKSHOP_STATUS_IS` — the workshop's own "Mark complete" and "Submit"
      // consult no scorer and are "never refused for an empty field" — and its `STAGE_CHECK_IS`, which
      // names the only act an empty required field does refuse: "Save and check required fields", the
      // second button at the foot of ONE stage, which saves either way and then refuses that stage
      // alone. Both constants are rendered verbatim on the readiness screen and the Submission card;
      // this list is what that button asks about, and must name that button and no other act.
      "Basic fields — what “Save and check required fields” refuses this one stage without",
      "Standard and Advanced fields — depth, never a blocker",
      // ⚠ THIS ROW READS AS AN EXHAUSTIVE LIST AND WAS SHORT BY TWO. `REFERENCE_MODELS`
      // (`backend/app/services/design_workshops.py:1467`) has exactly six members — Artisan, Craft,
      // ProductDocumentation, ToolDocumentation, Process and QuestionnaireInterview — and the last
      // of them landed after this sentence was written. Naming four of six is not a small omission
      // on THIS row, because the row's whole job is to tell a designer that the records they filed
      // in the steps above are reachable from inside a stage: a reader who is told four does not go
      // looking for the other two. The interview is also the one reference whose label is a TITLE
      // rather than a name, which makes it the easiest to mistake for something else in a picker.
      "Reference pickers — choose the artisan, craft, product, tool, process or questionnaire sitting you documented in the steps above",
      "“Create a new …” inside a picker, when the record is missing and you are mid-stage",
      "Photographs, sketches and measurements per stage",
      "Choose a photograph already in the repository, instead of uploading it a second time",
      "Photograph galleries that say how many are wanted, with a bar counting what you hold",
      "A microphone on every narrative box",
      "This stage in the document — the report’s own pages, beside the form",
      "Your own sections and questions, added to the workshop with no deployment"
    ],
    watch: [
      "Choosing a record COPIES its values onto this stage. The report prints that copy, so editing the artisan next week does not rewrite a report already handed over — re-pick the record here if you want the newer values.",
      "Point a row at a different record and every box it filled is cleared first, so two records can never be half-mixed on one row.",
      "Anything you typed yourself is never overwritten by a pick — only blanks are filled.",
      "Attaching a photograph that is ALREADY in the repository copies no bytes and moves nothing. The file stays on the record that holds it and this stage points at it as well, which is why the same loom, photographed once, no longer has to exist twice. The list is closed until you open it, so it costs nothing on a stage you fill from the camera.",
      "Every photograph is checked on THIS device before it uploads — for focus, for resolution, and for being the identical file twice. Exposure and subject are not checked; judge those by eye. A refused file names itself and its own reason, and nothing was sent, so it can simply be taken again.",
      "A gallery that states a number still saves with fewer, and nothing you attach is ever at risk. What falling short costs is the stage being scored incomplete and the generated report saying so. Attached is not saved either: the count reaches the workshop when you save the stage.",
      "Stage 9 and stage 17 compute findings BESIDE what you typed (your price bands against the survey; each cost sheet against its own lines). Neither ever changes your figures: you were in the room and the arithmetic was not.",
      "The preview panel beside the form follows the SAVES, not the keystrokes — it is the real document built by the server, not a sketch of one, so it cannot show an edit you have not saved yet and says so on itself.",
      "Add your own sections and questions on the workshop’s own screen rather than here: a definition is replaced as one whole set, and it tells you what an edit will cost a question somebody has already answered before you press anything."
    ]
  },
  {
    id: "design-workshop-sketches",
    label: "Sketches & prototypes",
    action: "Upload, trace and rank the work",
    icon: PencilRuler,
    href: "/sketches-and-prototypes",
    summary:
      "Pick a workshop, then work on its sketches (stage 11) and prototypes (stage 13) from one screen instead of walking into each stage form to find them.",
    why:
      "It is the same screen as the workshop’s own tab, entered from the other end: it asks WHICH WORKSHOP first, which is what you actually know when you are standing there with the drawing in your hand. Two tabs — Upload and Review — so the piece is added and rated in the same place, and the review tab here is the FIRST of the two rounds: the workshop’s own designers on each other’s work.",
    fields: [
      "Which workshop",
      "Upload / Review",
      "Prototypes / Sketches",
      "Photograph to trace",
      "Traced result",
      "The trace against the photograph",
      "Measure a dimension from a photograph",
      "Attach as",
      "Download a copy to this device",
      "360° capture",
      "3D model"
    ],
    watch: [
      "The tracing is arithmetic on this device. The crop and the sharpening feed the TRACE and nothing else — they cannot re-encode your photograph, so the original file stays the artifact, EXIF and all.",
      // ⚠ THE FEATURE THIS CARD OMITTED, and the omission was written in on purpose — see the ⚠ in
      // this file's header for how a search scoped to `lib/trace/` became a claim about the product.
      // IT IS DESCRIBED HERE AND ATTRIBUTED TO THE STAGE FORM, which is where it actually is:
      // `offersSketchRectify` is consulted by `FieldInput.tsx` and by nothing under
      // `components/sketches/`, so `UploadTabPanel` mounts `SketchTraceField` and never
      // `SketchRectifyField`. Both write a derived file into the same `sketch.lineArtFile` slot from
      // two different surfaces, which is exactly why naming the surface matters: a reader who looks
      // for four draggable corners on THIS screen and does not find them concludes the guide lies.
      // Do not move this bullet onto this screen's own controls without moving the mount first.
      "Straightening a photographed sheet into a plate is a second panel and it is on the stage 11 form, not here: drag the four corners of the sheet on the photograph, and a local threshold turns it into black line on white paper. It writes the plate into the same “Line art / vector file” slot this screen fills — a new file, never over your photograph — and every step of it is arithmetic on the device, so it works where the sketch was drawn.",
      "A 3D model file is stored and downloadable and nothing in either client draws it. “360° capture” is the view a reviewer actually sees and the one the report prints; a model file prints as the words “1 document attached”.",
      "The comparator has four views, and they are the handset’s own chips by name: Drawing, Wipe, Photograph and Difference. The wipe is the one you reach for; Difference is the one you reach for when the wipe has left you unsure, and it is the only one that costs a third plate to draw.",
      "The download offers five formats and only two of them can be attached to the record. SVG and PNG are what “Attach as” takes; PDF, EPS and DXF are take-away files — a print shop that will not accept an SVG accepts EPS, a laser cutter or CNC controller reads DXF R12 and nothing newer, and a PDF opens on any machine you could mail it to. A DOWNLOAD NEVER REACHES THE RECORD AT ALL, so which format you pick changes what you are holding and never what the officer reads.",
      "Set-aside sketches count. Stage 11 exists to record the designs that were never prototyped, and they are rateable in both rounds — a wider pool picking one up is the reason to write them down.",
      "This screen opens for designers, admins and the master admin. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
    ]
  },
  {
    id: "design-review",
    label: "Design review",
    action: "Rate and rank a colleague’s work",
    icon: Star,
    href: "/design-review",
    summary:
      "Score a colleague’s sketches and prototypes out of five, say what you would change, and put them in an order.",
    why:
      "This is the SECOND round. The workshop’s own designers rate each other first, on the Review tab of the step above; then the wider pool ranks the pieces a workshop has finished — including workshops the reviewer was never added to, which is the whole difference between the two levels. A pool reviewer sees the rateable rows and their scores and nothing else about the workshop: they are not a member of it and cannot write to its stages.",
    fields: [
      "A workshop you can open yourself",
      "Or any other workshop, from its link or its id",
      "Prototypes / Sketches",
      "Your score for this piece (1 to 5)",
      "What you think of it",
      "What you would change",
      "Move up / Move down, or drag to reorder"
    ],
    watch: [
      "The two ways in are not two spellings of one control. The dropdown lists the workshops YOU can open; the box takes any workshop’s link or id, because the pool round is by design about workshops you were never added to.",
      "A piece reaches the pool only once its peer round is closed — “Peer review closed on”, on the piece itself. A workshop with nothing open in the kind you chose says so in one sentence rather than showing you an empty list.",
      "The comment and the suggestion are two boxes on purpose. “What you would change” is the half a maker acts on, and it is unfindable if it is buried inside a paragraph of assessment.",
      "The order you place is stored on the row, inside one workshop — which is why the round is asked one workshop at a time and there is no mixed cross-workshop list to rank.",
      "This screen opens for designers, admins and the master admin. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
    ]
  },
  {
    id: "design-workshop-readiness",
    label: "Readiness",
    action: "See what is still outstanding",
    icon: ListChecks,
    href: "/design-workshops",
    summary: "One screen answering: what is still outstanding on this workshop?",
    why:
      "The alternative is opening all 22 stages on the last afternoon to find the four empty Basic fields, in three of them, that you meant to go back to. This lists them first, then the report’s own checks, then the Standard and Advanced gaps as counts — and every line links into the stage that holds it.",
    fields: [
      "Unfilled Basic fields — what a stage check is waiting for",
      "Report checks — they change the delivered file without refusing it",
      "Standard and Advanced gaps — counts, behind a disclosure",
      "A link straight into the stage that holds each gap"
    ],
    watch: [
      "A WORKSHOP MAY BE SUBMITTED PART-FILLED, and this is the fact to trust: “Mark complete” and “Submit”, on the workshop’s own Submission card, record where the whole workshop stands and are never refused for an empty field.",
      "One act in the app IS refused by an empty required field, and it is not that one: “Save and check required fields”, the second button at the foot of any stage. It saves the stage either way, then refuses THAT ONE STAGE while any of its Basic fields is empty, and names the ones it is waiting for. This list is what that button will ask you about.",
      "Use it on the FIRST afternoon as well as the last. It is a plan for the fortnight, not only a check at the end.",
      "Standard and Advanced counts never block anything. They are there so a thin stage is a decision rather than an oversight."
    ]
  },
  {
    id: "design-workshop-report",
    label: "Report",
    action: "Configure and generate the report",
    icon: FileOutput,
    href: "/design-workshops",
    summary:
      "Choose the template, set what the document contains, read it back as real A4 or Letter pages, and download the .docx or .pdf.",
    why:
      "This is the deliverable. Six templates, twelve named accent colours and a colour well, and picking one redraws every page on screen before a single file is made. The preview is drawn from the same document model the .docx and .pdf writers consume — and the two on-device writers as well — so what you read is what is generated, not an approximation of it.",
    // Read off `report/page.tsx` in screen order: `FieldBlock label="Report template"` (`:799`),
    // `label="Transcripts in this file"` (`:829`), the checkbox whose span is "Include
    // machine-assisted text" (`:874`), then `ReportAccentPicker`, whose own heading is "Report
    // colour" (`:408`) — NOT "Accent colour and cover details", which was a paraphrase whose second
    // half named a control this screen does not have: there is no cover-details box anywhere on it.
    // The third button was missing too. Page size is not a control here at all — it is read from
    // stage 20 (`:639`), which is why it stays in `summary` and out of this list.
    fields: [
      "Report template",
      "Transcripts in this file",
      "Include machine-assisted text",
      "Report colour",
      "Download .docx",
      "Download .pdf",
      "Print these pages"
    ],
    watch: [
      "A WARNING NEVER STOPS A FILE BEING PRODUCED. A required field nobody filled in, a photograph that could not be embedded, a gallery over the template’s cap, an attached file the report names and does not contain — each is reported beside the download and the document is generated anyway, because the pages that ARE ready are the ones you need.",
      "Those warnings never travel inside the document either. An officer opening the .docx next month must not find a note about what was missing on the day — which is also why the screen is the only place they can be read at all.",
      "The accent ladder runs from navy to burnt orange by LIGHTNESS, not by hue, because these get printed on monochrome office lasers where hue is discarded. Twelve equally dark colours would come out of that tray as twelve identical reports.",
      "Stage 20 is where these settings live — it configures the report and is one of the two stages that never print in it.",
      "The preview is read-only on purpose. To correct something it shows, open the stage it came from: an edit has to land on the stage entry, and the printed value is the entry’s own frozen copy.",
      // ⚠ THIS LISTED QUESTIONNAIRE ANSWERS AS FLATLY IMPOSSIBLE OFFLINE AND THEY ARE NOT.
      // `report/ReportSettings.kt`'s `UNSUPPORTED_SECTIONS` keeps the ANNEXURE_QUESTIONNAIRES entry
      // and says why in its own comment: `renderQuestionnaireAnnexure` draws it on the handset now, so
      // the entry "survives because it stopped being universally true rather than because it stopped
      // being true" — it is GUARDED by `unsupportedSectionsIn`, and the sentence the phone prints is
      // conditional ("If a questionnaire is attached … This device has not yet read them"). The
      // condition is the actionable half: one tap with a bar of signal fixes it for every export
      // afterwards, and a designer told it was impossible waits for the office instead.
      // `docs/WALKTHROUGH.md` has always stated the condition; this card had not.
      "A report generated on the PHONE honours the same template and settings and needs no signal at all. Two annexures it cannot draw are named on the file itself: transcripts, which are produced after the audio reaches the server, and machine-assisted text. Questionnaire answers are drawn on the phone too, but only once that handset has opened the workshop’s questionnaire list at least once with a connection — until then the file says so."
    ]
  },
  {
    id: "design-workshop-history",
    label: "Report history",
    action: "Review what you have produced",
    icon: History,
    href: "/design-workshops",
    summary:
      "Every file ever generated for this workshop — including ones a phone produced offline — with its checksum, size, page count, template and timestamp, and a diff between any two.",
    why:
      "A report submitted to a ministry comes back for revision three or four times, and “did you update the cost sheet before you resubmitted?” needs an answer that is evidence rather than memory.",
    // The real labels, read off `report/history/page.tsx`: the per-file `<dl>` at `:421`–`:445`
    // ("Template", "Pages", "Size", "Generated by", "SHA-256 of the file") and the two dropdowns of
    // the comparison panel at `:549` and `:558` ("Compare", "With"). The previous list ran the five
    // metadata labels together into one prose row, which reads well and is the thing §9.9 forbids:
    // a reader scanning the screen for "Checksum" finds "SHA-256 of the file" instead.
    fields: [
      "Every generated file, newest first",
      "Template",
      "Pages",
      "Size",
      "Generated by",
      "SHA-256 of the file",
      "Compare … With — any two files"
    ],
    watch: [
      "Nothing here can be tidied up. The checksum is what makes the record evidence, and evidence that can be edited is not evidence.",
      "The comparison is between two GENERATED FILES. It is not a field-level history of the workshop — for who changed a value and when, open the stage’s provenance."
    ]
  },
  {
    // LAST, BECAUSE AN INSPECTION READS A WORKSHOP THAT IS ALREADY FINISHED — the report has been
    // generated and somebody who did not run the fortnight is now reading it back.
    //
    // ⚠ AND IT IS THE ONE CARD IN THIS ARC THE READER PROBABLY CANNOT OPEN, WHICH IS A DIFFERENT
    // REFUSAL FROM THE OTHER FIVE AND MUST NOT BORROW THEIR SENTENCE. The other gated cards say
    // "designers, admins and the master admin", because `canRunDesignWorkshops` is that set. This
    // one is `INSPECTION_ROLES` (`lib/permissions.ts`), which is a frozen set of exactly ONE member:
    // INSPECTOR. An admin is refused it, the master admin is refused it, and a professor is refused
    // it — so writing "and above", or anything that reads as a rank threshold, would be wrong in
    // both directions at once. INSPECTOR sits at rank 37, between DESIGNER and PROFESSOR, which is
    // precisely the arrangement that misleads every threshold instinct a reader has.
    id: "design-workshop-inspection",
    label: "Workshops to inspect",
    action: "Read a finished workshop",
    icon: FileSearch,
    href: "/design-workshop-inspections",
    summary:
      "The inspector’s own list: the design & prototype workshops an admin has assigned them, every stage readable and none of it editable.",
    why:
      "A report submitted to a Development Commissioner’s office is read by somebody who did not run the fortnight, and “who wrote this field” is most of what that reading is for. So an inspection draws the same authorship line under every value that the designer’s own stage form draws under every box — the same component producing the same sentence, because an inspector and the designer being inspected must never be reading two different accounts of who did what.",
    fields: [
      "Workshops to inspect — the ones assigned to you, searchable by title, craft, cluster or workshop code",
      "Workshop under inspection — all 22 stages, read-only",
      "Who wrote each field, and when",
      "How complete the workshop is"
    ],
    watch: [
      "IT IS ITS OWN TIER AND NOT A RANK. Inspector / Reviewer is the only role this surface opens for: an admin and the master admin are refused it exactly as a professor is, and they read design & prototype workshops on Design workshops instead. If you are a designer, this step is here so you know what a colleague is looking at when they read your workshop back — not because you can open it.",
      "An admin chooses who inspects a workshop, one workshop at a time, on Manage workshop access.",
      "An empty page is a real answer and the screen says which kind it is. Nothing assigned reads “No workshop is assigned to you”; a list that could not be loaded says so instead — because the correct empty state and a silent failure look identical, and there is no other surface to cross-check against.",
      "There is no Save, no stage form, no submit and no report button, and none of them is missing: there is no route behind this page that would accept one. Nothing an inspector does can change a workshop.",
      "Photographs, recordings and attachments are COUNTED rather than shown — “3 photographs are recorded here; an inspection read does not carry them”. An empty gallery would look like a file that failed to load, which is not what happened."
    ]
  }
];

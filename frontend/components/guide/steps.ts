import {
  Brush,
  ClipboardCheck,
  ClipboardList,
  Eye,
  FileOutput,
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

/**
 * The nineteen steps of the process, in the order a designer actually performs them in the field:
 * TEN that build the repository records, then NINE that run the design & prototype development
 * workshop those records feed.
 *
 * THE SECOND ARC WAS MISSING FOR AS LONG AS THIS FILE EXISTED, and it is the one the fortnight is
 * for. The ten steps below are the repository RECORD forms; nothing here mentioned the 22-stage
 * workshop, the readiness check, the code cards or generating the report handed to a ministry
 * officer. A designer who opened the in-app Walkthrough looking for the deliverable found ten ways
 * to file a record and no path to the document. The workshop arc comes AFTER the ten and not before,
 * because that is the order the work happens in: the records exist first, and the stage form points
 * at them.
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
 * `/design-review` and `/sketches-and-prototypes` all carry a `ROUTE_GUARDS` row on
 * `canRunDesignWorkshops` (`lib/permissions.ts`), while `/guide` itself is deliberately ungated —
 * so a researcher reading this page can open the first ten steps and none of the last nine. That
 * cannot be fixed from inside this file: the guide teaches the process to people who have not
 * earned the capability yet, and withholding the arc would hide the deliverable the fortnight
 * exists for. What IS in this file's power is to make the padlock expected rather than a surprise,
 * so the four cards a reader can arrive at first each carry the same sentence in `watch` — one
 * wording, deliberately repeated, so it reads as a rule and not as an apology.
 *
 * ONE CLAIM IN THE SECOND ARC IS NOT A SCREEN DESCRIPTION and must not be softened: choosing a
 * record in a stage COPIES its values onto the stage entry, and the report prints the copy. Never
 * write that a picker shows the linked record, or that the report reads it — that is the opposite of
 * what the system does, and the difference is a document already in an officer’s hands changing
 * under him. Its authority is `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py`.
 *
 * Every `label` here is the Android-parity feature name (see
 * `.claude/skills/field-repo-frontend/SKILL.md` → "Naming"): the walkthrough must call a
 * screen exactly what the dashboard tile and the Android menu call it, or the guide teaches
 * a vocabulary the product does not use. `fields` mirrors the real form labels one-for-one,
 * so a researcher reading the guide recognises the screen when they open it.
 *
 * The labels are copied from the forms themselves — `components/forms/ArtisanForm`,
 * `ProductForm`, `ToolForm`, `ProcessForm`, the inline forms on the workshops / crafts /
 * questionnaire / media pages — plus the two shared sections every record form mounts:
 * `<WorkshopSelect>` (label "Workshop") and `<LocationFields>` (heading "Location"). Rename a
 * field on a form and it must be renamed here and in `docs/WALKTHROUGH.md`, which carries the
 * same lists in prose.
 *
 * THREE CARDS IN THE SECOND ARC CANNOT OBEY THAT RULE AND THEIR `fields` ARE DESCRIPTIONS INSTEAD.
 * `design-workshop-codes` and `design-workshop-readiness` render no labelled field at all — grep
 * either page for `field-label` and the count is zero; they are a print sheet and a list of links.
 * `design-workshop-stages` cannot enumerate labels even in principle: the stage form is built from
 * the registry the server publishes, so its boxes are hundreds of labels across 22 stages and are
 * not this file's to copy. Their `fields` name the sections of the screen, which is the only honest
 * content available, and each row is still a thing a reader can point at. The other six DO obey it
 * and were read off the components named in the comment above each list — do not let a seventh
 * quietly join the exception by being easier to paraphrase than to read.
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
    fields: [
      "Workshop title (required)",
      "Place (required)",
      "Start and end date",
      "Description",
      "Notes",
      "Linked artisans",
      "Crafts covered",
      "Workshop media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Create the workshop before you leave for the field — it is the container everything else drops into.",
      "Records created outside a workshop's date window are flagged as out-of-window and need a reviewer's approval."
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
    fields: ["Craft name (required)", "Local name", "Category", "Place", "Description", "Craft media"],
    watch: [
      "Check the list first — if the craft already exists, reuse it instead of creating a near-duplicate spelling.",
      "The local name matters as much as the English one; record what the community actually calls it."
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
    fields: [
      "Name (required)",
      "Local name",
      "Workshop",
      "Craft (required)",
      "Or new craft name",
      "Place (required)",
      "Gender",
      "Phone",
      "Email",
      "Address",
      "Notes",
      "Do's (positive prompt) (required)",
      "Don'ts (negative prompt) (required)",
      "Artisan media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Do's and Don'ts are required. Press Enter for each new point — one lesson per line.",
      "You must either select an existing craft or type a new craft name; the form will not save with neither.",
      "Photo EXIF is retained and summarised into the notes automatically — you do not need to transcribe camera details by hand."
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
    fields: [
      "Product name (required)",
      "Local name",
      "Workshop",
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
      "Product media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Pick the linked craft first — the artisan dropdown stays disabled until a craft is chosen, then only lists that craft's artisans.",
      "Use \"Document using grid\" to photograph the piece against the measuring grid: it fills length, breadth and height for you and stores the photo as evidence.",
      "Choosing a linked artisan fills the artisan name and place; choosing a linked craft fills the craft name."
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
    fields: [
      "Name of the process (required)",
      "Artisan (required)",
      "Product (required)",
      "Per step: Name of the step (required)",
      "Per step: additional context notes (optional)",
      "Per step: attached media"
    ],
    watch: [
      "Add a step with \"Add Another Step\" and pick Sequential for an ordered stage, or Group of activities for things done together.",
      "Video is the preferred format for steps — capture the action as it happens rather than posing the result.",
      "Document the process against the product you already recorded, so the two stay linked."
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
    fields: [
      "Toolkit name (required)",
      "Local name",
      "English name",
      "Workshop",
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
      "Thickness",
      "Weight",
      "Radius",
      "Maker",
      "Tradition type",
      "Replacement cost",
      "Suggestions for improvement",
      "Remarks",
      "Process stages",
      "Tool media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Fill only the dimensions that make sense for the tool — a blade has a length and thickness, a wheel has a radius.",
      "\"Process stages\" archives your captures in order as STAGE_STEP_1, STAGE_STEP_2, … so shoot them in sequence.",
      "You can also hand tools to specific artisans later from \"Assign tools to artisans\" — for your own artisans, ones shared with you for editing, or any artisan if you are an admin."
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
    fields: [
      "Interview title (required)",
      "Date",
      "Place",
      "Language",
      "Primary artisan",
      "Additional artisans",
      "Per question: \"Record this question\" audio, or typed answer"
    ],
    watch: [
      "There is one interview per exact set of artisans. If an entry already exists for that set, saving adds your answers to it — it never creates a duplicate.",
      "Answer only the questions actually asked; empty questions stay open for whoever picks the interview up next.",
      "Questions already answered by someone else can only be changed by that contributor or an admin.",
      "Use \"Check completion\" at the top of the screen to see the artisans × sections matrix and find the gaps."
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
      "Caption",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Upload stays disabled until you pick a Linked record type. If the file belongs to nothing in particular, pick \"Miscellaneous Media\" and leave the entry blank.",
      "Audio uploaded here is queued for transcription after upload, exactly like interview audio.",
      "If the file does turn out to belong to a record, link it — misc media can be attached to a record afterwards."
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

  /* ──────────────────────────────────────────────────────────────────────────
   * The design & prototype development workshop — what the ten steps above are for.
   *
   * These nine mirror the section of the same name in `docs/WALKTHROUGH.md` one for one. That file
   * and this array are TWO RENDERINGS OF ONE THING and its maintenance rule says they move in the
   * same commit: add a step here, add it there.
   *
   * FIVE OF THE NINE POINT AT A LIST AND NOT AT THE SCREEN THEY DESCRIBE, which is the one place
   * this arc cannot keep the promise the ten steps above make. Those five screens — Cards & tags,
   * Stages, Readiness, Report, Report history — live under `/design-workshops/[id]/...` and a guide
   * has no workshop id to put there; a link to a made-up one is a 404 delivered by the help. The
   * list is the honest destination, it is one tap from all five, and each `summary` names the screen
   * so a designer knows what they are looking for when they get there. (`Design workshops` is a
   * sixth `/design-workshops` href and is not one of the five: the list IS the screen it teaches.)
   *
   * THE OTHER THREE POINT AT THEIR REAL SCREENS, and it is worth saying why rather than leaving a
   * reader to wonder whether the three were done more carefully than the six. `/designers/profile`
   * belongs to the PERSON and never to a workshop, so no id was ever wanted. `/design-review` and
   * `/sketches-and-prototypes` are top-level siblings of the workshop tree BECAUSE being reachable
   * with no workshop chosen is the whole of what they are for — asking which workshop is the first
   * thing each page does. So the id problem does not arise for any of them, and a card that can
   * open the actual screen should.
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
      "Name",
      "Name in the local script",
      "Designation",
      "Institution",
      "Department",
      "Qualification",
      "Specialisation",
      "Designer’s experience",
      "Designer’s profile",
      "Phone",
      "Email",
      "Website",
      "Address",
      "City or town",
      "State",
      "Pincode",
      "Empanelment number",
      "Empanelment date",
      "Photograph",
      "Signature",
      "CV"
    ],
    watch: [
      "Not one of the twenty-one is required, and none of them is guessed for you. Left blank, the report cover falls back to the name on your account — so the cost of skipping this page is a thin cover, not a refusal.",
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
      "This screen and the eight below it open for designers, admins and the master admin. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
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
      "This is the fortnight itself: 22 stages of capture that end in a report submitted to a Development Commissioner’s office. It is a different thing from the Workshop record in step 1, and it points AT the records you made in steps 2–10 rather than replacing them. A DESIGNER DOES NOT START ONE — an admin creates it and adds you — and everything inside it is then yours.",
    // §9.9's rule — fields[] is the real labels in screen order — read off the only form on this
    // screen, `design-workshops/page.tsx`'s "Start a design workshop" panel (`:715`–`:830`). It
    // replaces a paraphrase that invented two rows and dropped two real ones: "Craft and place" is
    // four separate boxes and none of them is called Place; "Who else may open it" is not on this form
    // at all (a grant is made elsewhere); and "Report template" and "Notes" are controls the list
    // never mentioned. The panel is gated on `allowCreate`, so a designer reading this card will not
    // see it — which is why the note names whose form it is rather than leaving the reader hunting.
    fields: [
      "Start from a recorded workshop — what narrows every reference picker inside the stages",
      "Workshop title (required)",
      "Report template",
      "Craft",
      "Cluster",
      "State",
      "District",
      "Start date and End date",
      "Notes"
    ],
    watch: [
      "This list is empty until an admin creates a workshop and adds you to it, and for a newly empanelled designer that is the ordinary state rather than a fault. The screen says who to ask.",
      "Link it to a Workshop record early. The pickers inside the stages are narrowed to that workshop’s artisans, products and tools, and an unlinked workshop offers the whole repository instead — the screen says which you are looking at.",
      "It opens and fills with no signal. Everything is kept on the device and sent up when there is a connection.",
      "Every stage saves on its own, into a draft on this device, as you go. That is what makes a fortnight of fieldwork survivable: you resume exactly where you left off, and nothing is waiting on one long save at the end.",
      "This screen and the ones below it open for designers, admins and the master admin. If you are reading the guide without that access you can still learn the process here, but the link will show you a “Designer access required” panel."
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
      "The codes are on the stage index too — you do not need this screen to scan one.",
      "The sheet prints from the browser off the local draft, and the code is decoded in the browser as well, so both halves work with no signal.",
      "A JOIN CARD is a different code doing a different job: one person creates the workshop and the others scan a card to join THE SAME one, which is what stops a team ending the fortnight with four parallel workshops. It is minted and scanned on the handset — there is no join card on the web — and a card is good for one person unless an admin makes it good for more. A late-comer whose card was already spent is not turned away: the ask is filed for an admin to decide, so their work is not orphaned while they wait."
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
      // CORRECTED TWO CARDS DOWN and left standing here, so one page said both things. Authority:
      // `readiness/page.tsx`'s `WORKSHOP_STATUS_IS` — the workshop's own "Mark complete" and "Submit"
      // consult no scorer and are "never refused for an empty field" — and its `STAGE_CHECK_IS`, which
      // names the only act an empty required field does refuse: "Save and check required fields", the
      // second button at the foot of ONE stage, which saves either way and then refuses that stage
      // alone. Both constants are rendered verbatim on the readiness screen and the Submission card;
      // this list is what that button asks about, and must name that button and no other act.
      "Basic fields — what “Save and check required fields” refuses this one stage without",
      "Standard and Advanced fields — depth, never a blocker",
      "Reference pickers — choose the artisan, product, tool or process you documented in the steps above",
      "“Create a new …” inside a picker, when the record is missing and you are mid-stage",
      "Photographs, sketches and measurements per stage",
      "A microphone on every narrative box",
      "This stage in the document — the report’s own pages, beside the form",
      "Your own sections and questions, added to the workshop with no deployment"
    ],
    watch: [
      "Choosing a record COPIES its values onto this stage. The report prints that copy, so editing the artisan next week does not rewrite a report already handed over — re-pick the record here if you want the newer values.",
      "Point a row at a different record and every box it filled is cleared first, so two records can never be half-mixed on one row.",
      "Anything you typed yourself is never overwritten by a pick — only blanks are filled.",
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
  }
];

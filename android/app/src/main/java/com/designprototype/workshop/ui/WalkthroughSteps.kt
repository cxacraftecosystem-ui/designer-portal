package com.designprototype.workshop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.designprototype.workshop.data.UserDto

/*
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WALKTHROUGH'S CONTENT, AND NOTHING ELSE.
 *
 * This file holds the steps. It draws nothing, it navigates nowhere and it reads no preference — the
 * dialog that pages through it and the flag that decides whether it opens on first run live with the
 * screen. The split is what lets a unit test read the journey without standing up a composition, and
 * it is why everything here is `internal` rather than `private`: the codebase's own precedent is
 * `internal fun navBadge` in AppNavigation.kt, hoisted out of the drawer purely so a test could
 * assert it. A step list nothing can see is a step list that drifts, which is exactly what happened
 * to the twelve steps this replaces — they claimed "Ten steps, in this order" while the web taught
 * nineteen, and nothing in either client could tell.
 *
 * ── WHY THE COPY IS A KOTLIN LITERAL AND NOT A STRING RESOURCE ───────────────────────────────────
 *
 * `res/values/strings.xml` in this app is four lines long and holds `app_name` and nothing else.
 * There is no `values-hi/`, no `values-bn/`, no plurals and no `stringResource` call anywhere in the
 * copy. Every user-facing sentence in this application is an inline Kotlin literal, and the pattern
 * for copy that has to be TESTED is a top-level `val` or `object` in a Kotlin file — see
 * `AccessRefusalCopy.kt`, `ReviewQueueCopy.kt`, `TaskPickerCopy.kt`, `WorkshopAccessQueueCopy.kt` and
 * `Offline.kt`'s message builders, each pinned by a JVM test. Moving twenty-five paragraphs into
 * `strings.xml` would make this the only screen in the app whose words live somewhere else, and a
 * parity test would then have to parse XML to compare the handset against the web. So: literals,
 * here, beside the list that orders them.
 *
 * ── PARITY IS THE JOURNEY, NOT THE SENTENCES ─────────────────────────────────────────────────────
 *
 * The subjects and their ORDER mirror `frontend/components/guide/steps.ts` — ten steps that build the
 * repository records, then the arc that runs the design & prototype workshop those records feed, in
 * that order because that is the order the work happens in. A designer who read the walkthrough on
 * their laptop must recognise this one, step for step, when they open it in a courtyard.
 *
 * The PROSE is written fresh from this app's own code. That is deliberate and it is not laziness in
 * the other direction: the web file records five separate occasions where one of its cautions was
 * written from a neighbour's copy rather than from the source and was therefore wrong — a CV that
 * "reaches your reports as an annexure" (no branch of this codebase puts a file in an annexure), a
 * "list of twenty-five" prototypes (the registry declares no cap at all), "not one of the twenty-one
 * is required" (four of them are). Copying a sentence across a client boundary is how a false
 * sentence gets a second home. Every claim below was read off the Kotlin it describes.
 *
 * ── AND IT HAS TO BE TRUE OF *THIS* APP ──────────────────────────────────────────────────────────
 *
 * A step describing a screen the handset does not have is worse than a missing step: it sends a
 * designer hunting through a menu for a row that was never built, and what they conclude is that
 * they cannot find it rather than that it is not there. Two of the web's steps name a web-only
 * SURFACE — a standalone `/review` queue and a standalone Readiness screen — and both are rewritten
 * below to name where the same capability actually lives on this handset. Neither is dropped,
 * because the capability is here; only the address changed.
 *
 * ⚠ AND NO SENTENCE IN THIS FILE MAY CARRY A COUNT OF THE WEB'S STEPS. This paragraph said "the
 * web's nineteen" until the web taught twenty-two, and the comment over the list below said "four
 * steps the web does not have" when three of the four had already landed there. Both were correct
 * on the day they were typed and both rotted silently, which is the same defect the opening card's
 * own count was rewritten to derive rather than to state — see [walkthroughIntro]. A prose count is
 * not merely wrong when it rots: it is an instruction to the next reader to stop looking, which is
 * how three of these steps kept ids the web had already moved away from. Say "the web's steps" and
 * let `WalkthroughStepsTest` do the counting, because it reads the web's file and this comment
 * cannot.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

/**
 * One step of the walkthrough.
 *
 * Seven fields, and every one of them is load-bearing — see each property for what breaks without it.
 */
internal data class WalkStep(
    /**
     * A stable, permanent handle for this step, matching the web's `GuideStep.id` wherever the two
     * teach the same subject.
     *
     * IT IS NOT SHOWN TO ANYBODY. Its whole job is to be the thing a parity test compares, and the
     * thing a deep link could one day name. The ids are the only part of this file that is stable
     * enough to assert against while the prose on both clients is still being edited: pin a test to
     * a `title` and it fails the next time somebody improves a sentence, which trains everybody to
     * ignore the test. Rename one of these and the comparison silently stops covering that step —
     * `indexOfFirst { it.id == … }` finds nothing and reports a missing step, not a renamed one.
     *
     * Keep them kebab-case and keep them equal to the web's where a counterpart exists.
     */
    val id: String,
    /**
     * The step's heading — the feature name, then the control a designer actually taps, separated by
     * a middle dot.
     *
     * NO STEP NUMBER IN HERE, EVER. The shipped list carried "1. Workshop", "2. Craft" … "10. View
     * Data" in the literals, and the moment a step was inserted every title after it was a lie that
     * only a human re-reading the whole file could catch. The position is derived instead — see
     * [walkthroughStepNumber] — so inserting a step renumbers the rest for free.
     *
     * The FEATURE NAME half is the web's label verbatim ("Miscellaneous Media", "Cards & tags",
     * "Sketches & prototypes") and the ACTION half is this app's own menu row ("Record workshop",
     * "Scan a code"). Both clients speak one language; a designer moving between the phone and the
     * laptop mid-workshop must read one name for one thing. Never reword one side only.
     */
    val title: String,
    /**
     * The step itself, as prose, in one string.
     *
     * FOUR BLOCKS IN ONE PARAGRAPH, ALWAYS IN THIS ORDER: what you are doing at this point in the
     * field, why the dataset needs it, what the screen asks you for, and then — introduced by the
     * words "Watch out:" — the thing that has actually gone wrong for somebody. That is the web
     * card's expanded-panel order, and it is the durable half of the contract between the two
     * clients; the sentences are not. Drop the order and the two walkthroughs stop reading as one
     * document even while every fact in them is correct.
     *
     * The caution goes LAST because it is the half a reader who skims still takes in, and it is the
     * half that costs a return trip when it is missed.
     *
     * Long is fine. A handset body is scrollable and a designer reads this once; a step that omits
     * the caution to stay short has spent the reader's attention on nothing.
     */
    val body: String,
    /**
     * The glyph this step wears, or null where a heading alone reads better.
     *
     * DRAWN ONLY FROM ICONS THIS APPLICATION ALREADY USES. `material-icons-extended` is on the
     * classpath so nearly any name would resolve, but "it compiles" is not the bar: a name that is
     * merely plausible costs a build, and the set below is instead the same one `FIELD_NAV_ITEMS`
     * draws from, so the icon on a step is the icon on the menu row it sends you to. A designer
     * looking for the row they just read about is looking for the picture.
     *
     * Nullable rather than defaulted to a placeholder, because the two ends of this list — the
     * opening and the closing card — are not features and have no row to match.
     */
    val icon: ImageVector? = null,
    /**
     * The screen this step teaches, or null where no single destination opens it.
     *
     * A REAL [NavDestination] AND NEVER A WEB PATH. The web's `GuideStep.href` is a URL because the
     * web has URLs; this app has a hand-rolled router whose one routing table is
     * `MainActivity.openDestination`, and a step carrying "/design-workshops" as a string would be a
     * step that can never open anything. Pointing at the enum means the compiler is the thing that
     * notices when a destination is renamed.
     *
     * WHERE THERE IS NO DESTINATION OF ITS OWN, THIS NAMES THE NEAREST DOOR, NOT NOTHING. Five of
     * the design-workshop steps — the code cards, the stages, readiness, the report and the report
     * history — are screens reached from inside one workshop's own index and have no menu row, so
     * they carry [NavDestination.DESIGN_WORKSHOPS] and their bodies say which control to press once
     * they are there. A button that lands you one tap away is worth having; a button that does
     * nothing is the first defect a reviewer finds.
     *
     * Null means exactly one thing: there is no screen to open. Only the opening card, the closing
     * card and the offline step carry it.
     *
     * WHOEVER WIRES THE BUTTON: go through `MainActivity`'s `navigate`, not `openDestination`. The
     * walkthrough itself is exempt from the unsaved-changes guard because it draws OVER the page you
     * were on and takes nothing away; a screen it launches is a real departure from a possibly
     * half-filled form and must still be asked about.
     */
    val destination: NavDestination? = null,
    /**
     * "Why this step exists", as a field of its own. Where it is blank, [walkthroughFacets] takes the
     * reason from [body] instead, after its first sentence; a step with neither draws no heading.
     */
    val why: String = "",
    /**
     * "Watch out for": the cautions, one bullet each, in the order a reader meets them. Where it is
     * empty, [walkthroughFacets] takes the caution from [body] instead, after the words "Watch out";
     * a step with neither draws no heading.
     */
    val watch: List<String> = emptyList(),
)

/**
 * The numbered ground the walkthrough covers, in the order the work happens in.
 *
 * ── THE ORDER, AND WHY IT IS NOT NEGOTIABLE ──────────────────────────────────────────────────────
 *
 * The repository records first, then the design & prototype workshop those records feed. The
 * workshop arc comes AFTER the records and not before, because a stage's reference picker offers the
 * artisans, products and tools you already documented — run it the other way round and the pickers
 * are empty and the designer concludes the feature is broken. Inside the workshop arc the code cards
 * come BEFORE the stages for the same kind of reason: printing tags happens on the first afternoon,
 * filling twenty-two stages takes the fortnight, and a tag tied on at the end is a tag tied on from
 * memory.
 *
 * ── WHAT IS HERE THAT THE WEB DOES NOT HAVE: ONE STEP ────────────────────────────────────────────
 *
 * `offline` — the one subject a web guide genuinely cannot teach, and the one where getting it wrong
 * loses a colleague's work. It is last because it is about the whole fortnight rather than about a
 * screen, and it is the only step in this list with no counterpart on the other client.
 *
 * ── THREE STEPS THAT USED TO BE ON THAT LIST, AND THE DEFECT THAT KEPT THEM THERE ─────────────────
 *
 * `scan`, `design-workshop-questionnaires` and `design-workshop-inspection` were written here as
 * handset-only subjects "no walkthrough on either client had caught up with", under ids this file
 * chose for itself — `scan-code`, `questionnaires`, `design-workshop-inspections`. The web has since
 * shipped all three, under its own ids, and nothing noticed for two reasons that compounded:
 *
 *   1. `WalkthroughStepsTest` held the web's list as a hand-copied array of nineteen ids. The web
 *      had twenty-two. The three it did not know about were exactly the three whose ids had drifted,
 *      so the join it claims to enforce silently covered nineteen of twenty-two, and all three could
 *      have been DELETED from this file without one assertion going red.
 *   2. The comment that used to be here said they were Android's own, so the next reader had no
 *      reason to go and check.
 *
 * That is a register written down twice, which is the failure this repository has already paid for
 * three times — the dashboard tile list that carried eleven of twenty tiles under "never invent a
 * label", the opening card that read "Ten steps" over a list of twelve, and this. The fix is the
 * same one each time: the test now READS `frontend/components/guide/steps.ts` instead of restating
 * it, so the register has one copy and this comment cannot rot the same way again.
 *
 * ⚠ THE IDS ARE THE JOIN AND THEY MUST STAY EQUAL TO THE WEB'S. A step whose id this file invents is
 * a step the parity test reports as MISSING FROM THE WEB rather than as renamed, and the honest
 * reading of that report — the reading three people made — is "the web does not teach this yet". If
 * the web has a counterpart, take its id exactly, however awkward it reads next to its neighbours:
 * `design-workshop-questionnaires` sits in the records half of the alphabet and belongs to the
 * workshop arc, and that is the web's problem to rename, not this file's to work around.
 *
 * ── AND THE ORDER OF THE SHARED SUBJECTS IS THE WEB'S, POSITION FOR POSITION ──────────────────────
 *
 * Steps one to twenty-two are the web's twenty-two in the web's own sequence; `offline` is
 * twenty-three. `design-workshop-questionnaires` moved here from beside `questionnaire`, where this
 * file had put it while it was believed to be handset-only. Beside the standard interview it read as
 * "the two kinds of questionnaire"; the web files it as workshop PREPARATION, next to the code cards
 * and before the stages, and says why in its own comment — the pro-forma and the cards are both done
 * on the first afternoon while the stages take the fortnight. Its body still opens "The standard
 * interview above", which is true at either position, so nothing about the prose forced the old one.
 * The test's own words for why this matters are worth keeping in view: two clients teaching the same
 * subjects in different orders is worse than a missing step, because both look complete and only one
 * of them is the order the work happens in.
 */
internal val walkthroughJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "workshop",
        title = "Workshop · Record workshop",
        icon = Icons.Filled.Groups,
        destination = NavDestination.RECORD_WORKSHOP,
        body = "Open or create the workshop you are documenting under — every record belongs to one. The form asks for the title, craft, place, dates and who was there. Watch out: create it before you leave for the field, and always type the place yourself — captured coordinates only show where the phone was.",
    ),
    WalkStep(
        id = "craft",
        title = "Craft · Add craft",
        icon = Icons.Filled.Brush,
        destination = NavDestination.ADD_CRAFT,
        body = "Add the craft so artisans, products and tools have something to link to. One shared name keeps the spelling the same on everybody's records. Watch out: search the list first — a near-duplicate splits one craft into two.",
    ),
    WalkStep(
        id = "artisan",
        title = "Artisan · Record artisan",
        icon = Icons.Filled.Person,
        destination = NavDestination.RECORD_ARTISAN,
        body = "Record who the artisan is, where they work and what they have learnt. Every narrative box has a microphone. Watch out: Do's and Don'ts are required — one point per line.",
    ),
    WalkStep(
        id = "product",
        title = "Product · Record product",
        icon = Icons.Filled.Inventory2,
        destination = NavDestination.RECORD_PRODUCT,
        body = "Record one product with its measurements, costs and photographs. Two controls read dimensions off a photo: “Measure from a photograph” works offline; “Document using grid” estimates online. Watch out: pick the craft first — the artisan list waits for it.",
    ),
    WalkStep(
        id = "process",
        title = "Process · Document process",
        icon = Icons.Filled.AccountTree,
        destination = NavDestination.DOCUMENT_PROCESS,
        body = "Record how the product is made, one step at a time, with video of each step. Mark each step Sequential or Group of activities. Watch out: film the step as it happens, not from memory that evening.",
    ),
    WalkStep(
        id = "tool",
        title = "Tool · Record tool",
        icon = Icons.Filled.Build,
        destination = NavDestination.RECORD_TOOL,
        body = "Record each tool's material, size, maker and replacement cost. Both photo-measuring controls from the product form are here too. Watch out: fill only the dimensions that mean something for this tool.",
    ),
    WalkStep(
        id = "questionnaire",
        title = "Questionnaire · Take interview",
        icon = Icons.Filled.Quiz,
        destination = NavDestination.TAKE_INTERVIEW,
        body = "Work through the interview sections, recording each answer as audio. Recordings are transcribed after upload. Watch out: there is one interview per set of artisans — saving adds to an existing one.",
    ),
    WalkStep(
        id = "media",
        title = "Miscellaneous Media · Upload media",
        icon = Icons.Filled.PermMedia,
        destination = NavDestination.UPLOAD_MEDIA,
        body = "Upload photos, video, audio and files that belong to no single record. The caption box has a microphone. Watch out: pick a linked record type first, or “Miscellaneous Media”.",
    ),
    WalkStep(
        id = "review",
        title = "Review · Track your submissions",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Everything you submit is reviewed: Approved, Rejected or Sent for revision. Reviewers see records from roles below theirs. Watch out: below Professor, new records start as Pending — a revision comes with comments; fix and save to resubmit.",
    ),
    WalkStep(
        id = "view-data",
        title = "View Data · Browse records",
        icon = Icons.Filled.Storage,
        destination = NavDestination.VIEW_DATA,
        body = "Browse every record as a folder tree and download any folder. Records are filed by workshop, uploader and file type. Watch out: dataset download is a permission an admin grants.",
    ),
    WalkStep(
        id = "scan",
        title = "Scan a code · Open what a card names",
        icon = Icons.Filled.QrCodeScanner,
        destination = NavDestination.SCAN_CODE,
        body = "Scan a card or tag, read a code from a picture, or type it to open its record. Everything but the lookup works offline. Watch out: for prototype tags with no signal, use the workshop's own Cards & tags.",
    ),
    WalkStep(
        id = "designer-profile",
        title = "My designer profile · Type your details once",
        icon = Icons.Filled.Badge,
        destination = NavDestination.DESIGNER_PROFILE,
        body = "Your name, institution, empanelment, photo, signature and CV in one place. New workshops copy them into stages 1 and 3. Watch out: name, qualification, phone and email are required, and a workshop already under way keeps its own copy.",
    ),
    WalkStep(
        id = "design-workshop",
        title = "Design workshops · Open the fortnight",
        icon = Icons.Filled.DesignServices,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "The 22-stage design & prototype workshop that ends in a submitted report. An admin creates it and adds you; it works offline. Watch out: link it to a recorded Workshop early so the pickers show the right records.",
    ),
    WalkStep(
        id = "design-workshop-codes",
        title = "Cards & tags · Print the codes",
        icon = Icons.Filled.QrCode2,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Print prototype tags, artisan cards and the workshop's join card from Cards & tags. Scanning a tag picks the right prototype for you. Watch out: print at 100%, not “fit to page”, at the start of the fortnight.",
    ),
    WalkStep(
        id = "design-workshop-questionnaires",
        title = "Questionnaires · Your own forms",
        icon = Icons.AutoMirrored.Filled.ListAlt,
        destination = NavDestination.CUSTOM_QUESTIONNAIRES,
        body = "Build your own questionnaire in the .xlsx pro-forma for this workshop. Reuse it on another workshop, or send it to a colleague's phone. Watch out: a form opens offline, but answering one needs a connection.",
    ),
    WalkStep(
        id = "design-workshop-stages",
        title = "Stages · Fill the twenty-two",
        icon = Icons.Filled.Layers,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Work down the stage index; phone and laptop show the same boxes in the same order. Each stage has basic fields, deeper ones, photos and a microphone on every narrative box. Watch out: picking a record copies its values into the stage — re-pick it to update them.",
    ),
    WalkStep(
        id = "design-workshop-sketches",
        title = "Sketches & prototypes · Upload, trace and rank",
        icon = Icons.Filled.Gesture,
        destination = NavDestination.SKETCHES_AND_PROTOTYPES,
        body = "Pick a workshop, then add and photograph its sketches and prototypes. Review is the first rating round; the tracer turns a drawing into line art offline. Watch out: tracing shapes the line art only — your photo is kept as taken.",
    ),
    WalkStep(
        id = "design-review",
        title = "Design review · Rate a colleague's work",
        icon = Icons.Filled.Star,
        destination = NavDestination.DESIGN_REVIEW,
        body = "Rate colleagues' sketches and prototypes out of five, suggest changes, and set the order. Pieces reach the wider pool once their peer round closes. Watch out: keep the suggestion in its own box — it is what the maker acts on.",
    ),
    WalkStep(
        id = "design-workshop-readiness",
        title = "Readiness · What is still outstanding",
        icon = Icons.AutoMirrored.Filled.Assignment,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Each stage on the index shows a bar and how many required fields are filled. Expand a stage to see exactly what is missing. Watch out: empty fields never block saving — the figure is a plan, not a gate.",
    ),
    WalkStep(
        id = "design-workshop-report",
        title = "Report · Generate the deliverable",
        icon = Icons.Filled.Description,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Tap “Generate report”, choose a template and colour, then preview and export .docx or .pdf. It works offline, from what is saved on this phone. Watch out: to fix something, edit the stage it came from.",
    ),
    WalkStep(
        id = "design-workshop-history",
        title = "Report history · What you have produced",
        icon = Icons.Filled.History,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Every generated file with its checksum, size and pages, and a comparison between any two. It lists files from every phone. Watch out: entries cannot be edited or deleted, and the screen needs a connection.",
    ),
    WalkStep(
        id = "design-workshop-inspection",
        title = "Workshops to inspect · The other side of it",
        icon = Icons.Filled.FindInPage,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "For Inspector / Reviewer accounts: the workshops assigned to you, read-only. Each field shows who captured it, with a box for corrections. Watch out: admins appoint inspectors from a workshop's own index.",
    ),
    WalkStep(
        id = "offline",
        title = "No signal · What still works",
        icon = Icons.Filled.CloudOff,
        body = "The app keeps working where there is no signal at all. Workshops and records save on the phone and upload later; the strip at the top shows what is waiting. Watch out: correct shared records online where you can, and agree who edits what.",
    ),
)

/**
 * The opening card.
 *
 * THE COUNT IS DERIVED AND MUST STAY DERIVED. This card used to read "Ten steps, in this order" and
 * "it is the same ten steps in the same order on the web" while the web taught nineteen and this
 * list held twelve — one sentence, wrong in two directions, on the very first thing a new researcher
 * reads. Nothing catches that: a literal number is correct on the day it is typed and silently rots
 * from then on. Interpolating [walkthroughJourney] means the sentence cannot be wrong, and the web
 * page reached the same conclusion independently and renders its own count the same way.
 *
 * Declared after the journey it counts, because top-level properties in one Kotlin file initialise in
 * declaration order and a reference upward would read an empty list.
 */
private val walkthroughIntro = WalkStep(
    id = "intro",
    title = "The order the work happens in",
    icon = Icons.Filled.Explore,
    body = "${walkthroughJourney.size} steps — field records first, then the design workshop they feed. Leave any time; reopen it from the menu.",
)

/**
 * The closing card.
 *
 * A CHECKLIST AND NOT A SUMMARY, because the moment it is read for is the one where somebody is
 * about to get into a vehicle. Every line is a thing that is a phone call if it is missing and
 * another trip to the district if it is missing in the wrong way, and they are phrased as things to
 * look at rather than as things that were said.
 */
private val walkthroughOutro = WalkStep(
    id = "before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Before you leave: every artisan has Do's and Don'ts, every product has dimensions and costs, every process has video, every tool has a maker and cost, every prototype is tagged. Then check nothing is waiting to upload.",
)

/**
 * The whole walkthrough, in the order it is paged through: the opening card, the journey, the
 * closing card.
 *
 * THIS IS THE LIST THE SCREEN RENDERS. It is deliberately a different list from [walkthroughJourney]
 * — the two ends are not features, have no destination and must not be numbered as though they were
 * — and keeping them apart is what lets the opening card state a count that is about the journey
 * rather than about itself.
 */
internal val walkthroughSteps: List<WalkStep> =
    listOf(walkthroughIntro) + walkthroughJourney + walkthroughOutro

/**
 * Where [step] sits in [deck]'s journey, counting from one, or null if it is an opening or closing
 * card.
 *
 * THE ONE PLACE A STEP NUMBER IS ALLOWED TO COME FROM. Hand-written numbers in the titles are what
 * made "10. View Data" the tenth of twelve entries in a list that claimed to have ten; deriving the
 * position means inserting a step renumbers everything after it and no human has to notice.
 *
 * ⚠ THE DECK IS A PARAMETER AND USED NOT TO BE, AND THAT WAS NOT A STYLE PROBLEM. This function read
 * the top-level `walkthroughJourney` directly, which is the designer's twenty-three. The day a
 * SECOND deck existed that made it answer null for every step of it — an id the designer's journey
 * does not contain is not in the designer's journey — so every card of the inspector's deck would
 * have drawn as an unnumbered "Page n of m" against a meter denominated in somebody else's
 * twenty-three. Nothing would have crashed and nothing would have looked broken; it would simply
 * have been the wrong deck's arithmetic, silently, on the surface whose whole subject is "where am
 * I, out of how many". The parameter is what makes the wrong answer unrepresentable.
 *
 * Matched on [WalkStep.id] rather than on the whole value, so a screen holding a step it copied or
 * rebuilt still gets the right answer.
 */
internal fun walkthroughStepNumber(deck: WalkthroughDeck, step: WalkStep): Int? =
    deck.journey.indexOfFirst { it.id == step.id }.takeIf { it >= 0 }?.plus(1)

// ---------------------------------------------------------------------------------------------
// The decks, and the rule that decides which one opens
// ---------------------------------------------------------------------------------------------

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * TWO WALKTHROUGHS ON THIS HANDSET, AND WHY THAT IS NOT THE WEB'S THREE.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web registers three decks in `frontend/components/guide/tracks.ts` — the designer's fortnight,
 * a DIRECTORATE deck for the three ministry posts, and an INSPECTOR deck — and `guideTrackFor` picks
 * which one OPENS from the reader's role. Its module header is explicit that this is "a DEFAULT, NOT
 * A GATE": `/guide` stays ungated, every deck stays in the bundle for everybody, and the switcher on
 * the page reaches all three, so the role picks what opens and takes nothing away. BOTH HALVES OF
 * THAT DECISION ARE CARRIED ACROSS BELOW — [walkthroughDeckFor] is a default, [WALKTHROUGH_DECKS] is
 * the whole register, and the opening card of every deck switches to any other. A role-chosen deck
 * with no way back to the others would be NARROWER than the web on the client that has fewer
 * screens, which is the wrong direction for the only difference to run in.
 *
 * ── THE DIRECTORATE DECK IS NOT HERE, AND THIS IS THE GREP RATHER THAN THE OPINION ──────────────
 *
 * Its five cards name `/annual-plan`, `/sanction-orders`, `/officers`, `/officers/monitored` and
 * `/design-workshops`. FOUR OF THE FIVE DO NOT EXIST ON THIS HANDSET IN ANY FORM. Run from
 * `android/app/src/main`, with `--include=*.kt`, on 2026-09-15:
 *
 *   grep -rniE 'annual[ _-]?plan'                                           → 0
 *   grep -rniE 'monitored|monitoring'                                       → 0
 *   grep -rniE 'sanction-orders|sanctionOrders|SanctionOrder'               → 1, and it is a
 *                                                                             hypothetical inside a
 *                                                                             KDoc (DwPhotoIntake)
 *   grep -rniE '/officers'                                                  → 0
 *   grep -rniE 'AnnualPlanScreen|SanctionOrderScreen|OversightScreen'       → 0
 *   grep -rniE 'canReadWorkshopOversight|OFFICER_ROLES|canSeeMinistryDesk'  → 0
 *
 * The three directorate tiers exist here as rank constants (42/45/48), display labels, roster filter
 * chips and membership of `DESIGN_WORKSHOP_ROLES` — and as no screen, no route and no repository
 * method. The fifth card, `/design-workshops`, is `NavDestination.DESIGN_WORKSHOPS`, which this
 * app has and which the DESIGNER's deck already teaches under `design-workshop`. So a directorate
 * deck here would be one step a reader can already reach and four doors into nothing, which is
 * exactly what the header of this file calls "worse than a missing step": it sends an officer
 * hunting a menu for rows that were never built, and what they conclude is that they cannot find
 * them rather than that they are not there.
 *
 * The web reached this same conclusion about this same handset and wrote it into
 * `DIRECTORATE_TRACK.recapLead` — "Four of these five screens are on the web only" — having run the
 * grep itself. Manufacturing the deck here to make the two clients' deck COUNTS match would be
 * making the numbers agree by making the product lie.
 *
 * ⚠ AND THE FIX IS NOT `OFFICER_ROLES`. The selector the web uses for that deck has zero Android
 * hits today, and adding it to `FieldPermissions` to support a deck of dead links would be widening
 * this app's role surface to make a screen render. When the handset grows an annual plan, a sanction
 * register and an oversight screen, the deck and its predicate arrive together and this paragraph
 * comes out.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * One walkthrough: an opening card, a numbered journey, a closing card, and who it is written for.
 *
 * THE WEB'S [GuideTrack] HAS FOURTEEN FIELDS AND THIS HAS FIVE, and the missing nine are not an
 * omission — they are page-level copy for bands this handset does not draw. `headline`, `intro` and
 * `facts` belong to a hero section with a pointer-driven wash and a GSAP headline that
 * `WalkthroughJourney.kt` argues at length for NOT copying; `description` is a `PageHeader`
 * subtitle and there is no page header here, because the walkthrough is a dialog; `checklist` and
 * `checklistLead` are the closing band, which this client already carries as the closing card's own
 * body; and `next` is a "Where to go next" tile grid whose every entry is a route — this app's
 * equivalent is the "Open …" button each step already has, borrowed from `FIELD_NAV_ITEMS`.
 * Declaring empty versions of those fields so the two types "match" would be nine registers with
 * nothing reading them.
 *
 * What IS here is what actually differs between two decks on this client: which steps, in which
 * order, with which two ends, under which name.
 */
internal data class WalkthroughDeck(
    /**
     * Stable id. Never shown, and never a URL — this app has no deep links into the walkthrough.
     *
     * Equal to the web's `GuideTrack.id` where a counterpart exists, on the same reasoning
     * [WalkStep.id] gives: it is the only part of a deck stable enough for a test to join on while
     * the prose is still being edited.
     */
    val id: String,
    /** The switcher's button label — the web's `GuideTrack.name`, verbatim. */
    val name: String,
    /** One line saying who the deck is for — the web's `GuideTrack.audience`, verbatim. */
    val audience: String,
    /** The numbered steps, in the order the work happens in. Never includes the two ends. */
    val journey: List<WalkStep>,
    /**
     * The whole deck as it is paged through: the opening card, the journey, the closing card.
     *
     * DERIVED AND NOT DECLARED, so the two can never disagree. The screen renders this; everything
     * that counts, numbers or recaps reads [journey]. Keeping them apart is what lets an opening
     * card state a count that is about the journey rather than about itself — see the argument over
     * `walkthroughSteps`.
     */
    val steps: List<WalkStep>,
)

/**
 * The designer's deck — the fortnight of fieldwork, unchanged down to the byte.
 *
 * It wraps the lists that were already here rather than restating them, which is what keeps
 * `WalkthroughStepsTest`'s join against `frontend/components/guide/steps.ts` pointed at the same
 * objects it has always been pointed at. A deck that COPIED or filtered `walkthroughJourney` would
 * leave that suite green while the screen rendered something else — the web's own deck registry
 * pins the identical property (`expect(DESIGNER_TRACK.steps).toBe(GUIDE_STEPS)`) for the same
 * reason.
 */
internal val WALKTHROUGH_DESIGNER_DECK = WalkthroughDeck(
    id = "designer",
    name = "Documenting a craft",
    audience = "Designers, and anybody learning how a workshop is run",
    journey = walkthroughJourney,
    steps = walkthroughSteps,
)

/**
 * THE INSPECTOR'S JOURNEY — the Inspector / Reviewer tier, and nobody else.
 *
 * ── WHY THIS IS A DECK OF ITS OWN AND NOT A CARD IN THE DESIGNER'S ──────────────────────────────
 *
 * [walkthroughJourney] already ends with one card about this surface, `design-workshop-inspection`,
 * and that card is correct where it stands: it is there so a DESIGNER knows what a colleague is
 * looking at when they read the workshop back, and its first sentence says so. It is not a
 * walkthrough for an inspector and it cannot become one, because the deck it sits in is the
 * fortnight of fieldwork an inspector does not do. An inspector opening the walkthrough on this
 * handset was handed twenty-five cards about recording artisans, filling stages and generating a
 * report, exactly one of which describes a screen they can open, near the end of it.
 *
 * ── THE WEB'S FOUR CARDS, IN THE WEB'S ORDER ─────────────────────────────────────────────────────
 *
 * `frontend/components/guide/inspectorSteps.ts` teaches four: `inspection-list`, `inspection-read`,
 * `inspection-feedback` and `inspection-review-queue`, and so does this deck. Until 0.0.17 it taught
 * three: the handset had no feedback box, and [walkthroughInspectorOmissions] registered the gap. The
 * box is now on `InspectionDetailScreen` (`InspectionFeedbackPanel`), so the card is here and the
 * register is empty — `WalkthroughDecksTest` holds the two together in both directions.
 *
 * `Review` is open to Field Contributor and above, so it is not the tier's own screen — but it is
 * the other half of what "Inspector / Reviewer" names, and on this handset it opens with the
 * inspector's own workshops waiting for a decision (`InspectionAwaitingSection`) above the record
 * queue every reviewer shares.
 */
internal val walkthroughInspectorJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "inspection-list",
        title = "Workshops to inspect · Open your assigned workshops",
        icon = Icons.Filled.FindInPage,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "Open the workshops an admin has assigned you, one at a time. The search covers your assignments only. Watch out: offline, the screen shows the list this phone last saw.",
    ),
    WalkStep(
        id = "inspection-read",
        title = "Workshop under inspection · Read every stage",
        icon = Icons.Filled.Layers,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "Read the workshop back stage by stage, with who wrote each field and when. Empty stages are listed too — an empty stage is a finding. Watch out: values copied from a record may since have changed there.",
    ),
    WalkStep(
        id = "inspection-feedback",
        title = "Correction suggestions · File or send back",
        icon = Icons.Filled.RateReview,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "Say what should be corrected and which stage it is about. The buttons differ: “Suggest” keeps the report as is; “Send back” moves it to Needs revision. Watch out: neither can be undone, and offline notes send only if the round hasn't changed.",
    ),
    WalkStep(
        id = "inspection-review-queue",
        title = "Review · Work the queue",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Start with the reports waiting for your decision, then the record queue. You decide records from roles below yours. Watch out: send a record back with what to fix rather than changing it yourself.",
    ),
)

/**
 * The inspector deck's opening card.
 *
 * THE COUNT IS DERIVED FOR THE SAME REASON THE DESIGNER'S IS, and now for a second one: there are
 * two decks, so there are two numbers that can rot instead of one, and a card that typed its own
 * would be wrong in whichever deck somebody edits next. It interpolates its OWN journey and never
 * [walkthroughJourney] — a sentence here reading twenty-three would be the designer's arithmetic on
 * the inspector's card, which is precisely the defect `walkthroughStepNumber` gained a parameter to
 * make unrepresentable.
 */
private val walkthroughInspectorIntro = WalkStep(
    id = "inspector-intro",
    title = "What an inspection is, in order",
    icon = Icons.Filled.Explore,
    body = "${walkthroughInspectorJourney.size} steps: your assignments, reading a workshop, suggestions, and the review queue. Inspectors read workshops but never fill them in.",
)

/**
 * The inspector deck's closing card: the checklist read just before a note goes on the record.
 *
 * A CHECKLIST AND NOT A SUMMARY, on the same argument as the designer's: the moment it is read for
 * is the one just before somebody acts irreversibly. Here that moment is a suggestion being filed,
 * which cannot be edited or withdrawn, or a send-back, which moves a report onto somebody's desk.
 */
private val walkthroughInspectorOutro = WalkStep(
    id = "before-you-send-it-back",
    title = "Before you finish an inspection",
    icon = Icons.Filled.CheckCircle,
    body = "Read every stage, including empty ones. Say what's wrong and what it should say. Pick the right stage. Check existing suggestions first. Choose the right button.",
)

/**
 * The web's inspector cards this handset deliberately does not teach, and nothing else.
 *
 * EMPTY SINCE 0.0.17, and kept rather than deleted. It held `inspection-feedback` while the handset
 * had no feedback box; the box landed and the card with it. `WalkthroughDecksTest` reads
 * `frontend/components/guide/inspectorSteps.ts` at runtime and asserts that the web's ids minus this
 * deck's ids are EXACTLY this set, so a card quietly dropped from this deck is a red test rather than
 * an omission nobody decided.
 */
internal val walkthroughInspectorOmissions: Set<String> = emptySet()

/** The inspector's deck: the opening card, four screens, and the closing checklist. */
internal val WALKTHROUGH_INSPECTOR_DECK = WalkthroughDeck(
    id = "inspector",
    name = "Inspecting a workshop",
    audience = "Inspector / Reviewer",
    journey = walkthroughInspectorJourney,
    steps = listOf(walkthroughInspectorIntro) + walkthroughInspectorJourney +
        walkthroughInspectorOutro,
)

/**
 * Every deck, in the order the switcher prints them.
 *
 * THE DESIGNER'S IS FIRST AND STAYS FIRST WHOEVER IS READING, and the web's registry gives the
 * reason this file has no better version of: the order of a list of choices is not the same question
 * as which of them is selected, and a switcher that reordered itself per account would mean two
 * colleagues describing "the second one" and meaning different decks.
 */
internal val WALKTHROUGH_DECKS: List<WalkthroughDeck> =
    listOf(WALKTHROUGH_DESIGNER_DECK, WALKTHROUGH_INSPECTOR_DECK)

/**
 * WHICH DECK OPENS FOR THIS ACCOUNT. A DEFAULT, NOT A GATE.
 *
 * ── THE PROPERTY THAT MAKES THIS SAFE AT ALL, AND IT IS NOT THE PREDICATE ───────────────────────
 *
 * The walkthrough's nav row is the one row this repository has decided must NEVER be gated — a
 * crowdsource volunteer on day one needs it more than an admin does — and `WalkthroughSurfaceTest`
 * walks all eleven tiers asserting the row's `can` admits each. Nothing below changes that, and
 * nothing below may be allowed to: every deck stays in the APK for everybody, the opening card of
 * whichever one opens switches to any other, and a wrong answer here costs the reader one tap. That
 * is the same ruling `tracks.ts` makes for the web, in its words: the role "picks which deck OPENS
 * and takes nothing away from anybody". If this ever becomes a filter over which decks EXIST for an
 * account, it has stopped being this function.
 *
 * ── WHY IT IS `canInspectDesignWorkshops` AND NOT A RANK ────────────────────────────────────────
 *
 * The web tests the same predicate first and says why: `INSPECTION_ROLES` is a set with exactly one
 * member, so it can never claim an account another arm wanted. On this client the reason is sharper
 * still, because the rank instinct is actively wrong here — INSPECTOR is 37, which sits BETWEEN
 * DESIGNER (35) and PROFESSOR (40), so `rank >= RANK_INSPECTOR` would open the inspector's deck for
 * a professor, all three directorate tiers, an admin and the master admin: six of the eleven tiers,
 * none of which this handset's inspection door admits (the server's has admitted an appointed admin
 * since 2026-10-09; that is web-only here). `FieldPermissions.canInspectDesignWorkshops`
 * delegates to the data layer's set, which is the same set the two inspection screens re-derive
 * before they issue a request, so the deck that opens and the screens it teaches cannot disagree.
 *
 * ── THE WEB'S SECOND ARM HAS NO COUNTERPART HERE, WHICH IS A CONSEQUENCE AND NOT A CHOICE ───────
 *
 * `guideTrackFor` tests `canReadWorkshopOversight` next and opens the directorate deck. There is no
 * directorate deck on this handset and no `OFFICER_ROLES` in this tree — see the block comment above
 * [WalkthroughDeck] for the greps — so a ministry post falls through to the designer's deck, which
 * is the deck that describes the workshop they can genuinely open and write in on this phone. That
 * is the honest answer rather than a placeholder: the four screens the web would send them to do not
 * exist here.
 *
 * A NULL USER GETS THE DESIGNER'S DECK. `RepositoryApp` renders the sign-in screen until there is a
 * user, so this arm is not reachable from the dialog — but the function is pure and testable, and
 * answering null for an unknown account would push the null check onto every caller.
 */
internal fun walkthroughDeckFor(user: UserDto?): WalkthroughDeck =
    if (user != null && FieldPermissions.canInspectDesignWorkshops(user)) {
        WALKTHROUGH_INSPECTOR_DECK
    } else {
        WALKTHROUGH_DESIGNER_DECK
    }

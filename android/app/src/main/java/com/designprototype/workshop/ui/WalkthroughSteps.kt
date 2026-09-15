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
 * Five fields, and every one of them is load-bearing — see each property for what breaks without it.
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
        body = "Open the workshop you are documenting under — or create it — before you " +
            "record anything else. Every record is scoped to a workshop: products, tools and " +
            "interviews all carry one, and View Data will file the whole repository under the " +
            "workshop each record was made in. The form asks for the title, the " +
            "craft, where and when, and who was there. Watch out: create it before you leave for " +
            "the field. It is the container everything else drops into, and a record made with " +
            "nowhere to drop is a record somebody has to re-file by hand later. And this form, " +
            "like every capture screen after it, asks WHERE twice in two controls that read as " +
            "one. \"Captured coordinates\" fills itself and is provenance — where this handset " +
            "was standing when the record was typed — while the village, district and pincode " +
            "you type yourself are where the workshop or the artisan actually is. They are not the " +
            "same place and the second is the one research reads: leaving it to the fix has " +
            "already put artisans from Rajasthan in West Bengal on the live database, because the " +
            "coordinates were never wrong. They were a true reading of the desk.",
    ),
    WalkStep(
        id = "craft",
        title = "Craft · Add craft",
        icon = Icons.Filled.Brush,
        destination = NavDestination.ADD_CRAFT,
        body = "Add the craft being documented so artisans, products and tools have something to " +
            "hang off. Craft is the shared vocabulary of the repository — artisans link to " +
            "one, products and tools inherit the name from it, and View Data groups a workshop's " +
            "contents by it — so adding it once keeps the spelling the same across everybody's " +
            "records. Watch out: search the list before you add. A near-duplicate spelling does not " +
            "fail; it quietly splits one craft into two, and nothing downstream can tell them apart " +
            "afterwards. If \"Add craft\" is not in your menu, your account does not manage crafts " +
            "and somebody who does will have to add it — this app hides a row it would be " +
            "refused rather than showing it greyed out.",
    ),
    WalkStep(
        id = "artisan",
        title = "Artisan · Record artisan",
        icon = Icons.Filled.Person,
        destination = NavDestination.RECORD_ARTISAN,
        body = "Record the person: who they are, where they work, how to reach them, and what they " +
            "have learnt. The artisan is the anchor of the dataset — products, processes, " +
            "tools and interviews all link back to one — and the Do's and Don'ts are the " +
            "artisan's own hard-won knowledge, the part of the archive that cannot be reconstructed " +
            "later from anything else. Every narrative box on this form has a microphone, so you " +
            "can speak an answer while your hands are busy. Watch out: Do's and Don'ts are " +
            "required, one lesson per line — press Enter for each new point rather than " +
            "running them together in a paragraph.",
    ),
    WalkStep(
        id = "product",
        title = "Product · Record product",
        icon = Icons.Filled.Inventory2,
        destination = NavDestination.RECORD_PRODUCT,
        body = "Record one thing this artisan makes, with its measurements, its economics and its " +
            "photographs. This is where the craft becomes comparable across regions: dimensions, " +
            "cost of making, selling price and demand are the figures a researcher can actually put " +
            "side by side. Once there is a photograph on the record, two controls will take a " +
            "dimension off it rather than off a tape, and the form offers them in the order you " +
            "should reach for them: \"Measure from a photograph\" has you mark the object against " +
            "something of a known length in the same frame — a grid square will do — and " +
            "works the answer out on this handset, so it costs nothing, needs no signal, and can be " +
            "re-derived later from the marks that produced it. \"Document using grid\" below it " +
            "sends the picture to a model that ESTIMATES the number instead: it needs a connection, " +
            "it bills per use, and nobody can check it afterwards. Either way the photograph stays " +
            "as evidence and the form records beside each number how it was arrived at. Watch out: " +
            "pick the linked craft first. The artisan dropdown stays disabled until you do, and " +
            "then lists only that craft's artisans — which is the check, not an obstacle.",
    ),
    WalkStep(
        id = "process",
        title = "Process · Document process",
        icon = Icons.Filled.AccountTree,
        destination = NavDestination.DOCUMENT_PROCESS,
        body = "Walk through how that product is made, one step at a time, filming each step as it " +
            "happens. The process is the craft itself: a product photograph shows the result, but " +
            "the ordered steps with their own video show the knowledge — the sequence, the " +
            "hand movements, the judgement calls a written description always loses. Add each step " +
            "with \"Add Another Step\" and mark it Sequential for an ordered stage or Group of " +
            "activities for things done together. Watch out: film the step while it is happening. " +
            "A step described from memory that evening is the one that turns out to be missing the " +
            "thing that made it work.",
    ),
    WalkStep(
        id = "tool",
        title = "Tool · Record tool",
        icon = Icons.Filled.Build,
        destination = NavDestination.RECORD_TOOL,
        body = "Record the toolkit: what each tool is made of, how big it is, who made it and what " +
            "it costs to replace. Tools are the most quietly endangered part of a craft — the " +
            "person who makes the tool often disappears before the craft does — so maker, " +
            "tradition type and replacement cost are the fields that record whether the chain " +
            "behind the work is still alive. Both of the measuring controls from the Product step " +
            "are on this form too, in the same order — \"Measure from a photograph\" first, " +
            "then \"Document using grid\" — for a tool you can photograph against a reference " +
            "rather than measure by hand. Watch out: fill only the dimensions that mean something " +
            "for this tool. A blade has a length and a thickness; a wheel has a radius; a number " +
            "entered to fill a box is a number somebody will later average.",
    ),
    WalkStep(
        id = "questionnaire",
        title = "Questionnaire · Take interview",
        icon = Icons.Filled.Quiz,
        destination = NavDestination.TAKE_INTERVIEW,
        body = "Sit down with the artisan and work through the interview sections, recording each " +
            "answer as audio. This is the artisan speaking in their own voice and their own " +
            "language; the recording is transcribed on the server once it arrives, so you get both " +
            "the tape and searchable text without typing through the conversation. The language box " +
            "is a dropdown from a fixed list rather than free text, which is what stops the same " +
            "language reaching the dataset under three spellings. Watch out: there is one interview " +
            "per exact set of artisans. If an entry already exists for that set, saving ADDS your " +
            "answers to it rather than creating a second one — that is correct behaviour and " +
            "not a lost record.",
    ),
    WalkStep(
        id = "media",
        title = "Miscellaneous Media · Upload media",
        icon = Icons.Filled.PermMedia,
        destination = NavDestination.UPLOAD_MEDIA,
        body = "Upload the photographs, video, audio and files that belong to no single record. " +
            "Field work produces context no form has a slot for — the road into the village, " +
            "the market, an unplanned conversation — and this is what keeps that material " +
            "inside the repository instead of on a handset that gets wiped or handed on. The " +
            "caption box has a microphone. Watch out: upload stays disabled until you pick a linked " +
            "record type. If the file genuinely belongs to nothing in particular, pick " +
            "\"Miscellaneous Media\" and leave the entry blank — that is the answer, not a way " +
            "round the control.",
    ),
    WalkStep(
        id = "review",
        title = "Review · Track your submissions",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Everything you submit goes for review and comes back Approved, Rejected or Sent for " +
            "revision. Review is what turns a pile of field notes into a dataset somebody can cite, " +
            "and it means you are never the last check on your own work. THIS HANDSET HAS NO " +
            "SEPARATE REVIEW QUEUE — the web has a page of its own, and here the same menu row " +
            "opens the record browser, which is the one surface where a reviewer can actually read " +
            "a submission and act on it. Watch out: below Professor the status is locked and " +
            "everything you create is submitted as Pending. That is normal and not an error. A " +
            "record sent back for revision always carries comments explaining why — read them, " +
            "fix the record, and saving resubmits it.",
    ),
    WalkStep(
        id = "view-data",
        title = "View Data · Browse records",
        icon = Icons.Filled.Storage,
        destination = NavDestination.VIEW_DATA,
        body = "Read the whole repository as a directory tree and take a subtree away as a " +
            "spreadsheet. This is where documentation stops being data entry and starts being " +
            "research material: the same records filed three ways — by workshop, by whoever " +
            "uploaded them, and by kind of file — previewable in place. Pick a folder and use " +
            "the breadcrumb to climb back out; the tree loads as you open it. Watch out: taking the " +
            "dataset out is a granted permission, so if \"View Data\" is not in your menu that is " +
            "the reason. Reading the records themselves is open to every signed-in account — " +
            "use Browse records instead, and nothing is hidden from you.",
    ),
    WalkStep(
        id = "scan",
        title = "Scan a code · Open what a card names",
        icon = Icons.Filled.QrCodeScanner,
        destination = NavDestination.SCAN_CODE,
        body = "Point the camera at a printed card or a tag and open the record it names, or read " +
            "the code out of a picture somebody sent you, or type it. It is here as its own " +
            "destination because it used to take three deliberate taps through a screen named after " +
            "reading a list, and the one control whose entire purpose is to save typing was the one " +
            "you had to go looking for. Watch out: this door is repository-wide and knows nothing " +
            "about which workshop you are standing in. To resolve a prototype tag with no signal, " +
            "use that workshop's own Cards & tags screen, which reads its codes off this handset " +
            "first; and inside a stage form the reference picker takes a scan to LINK a record to " +
            "what you are filling in rather than to open it.",
    ),
    WalkStep(
        id = "designer-profile",
        title = "My designer profile · Type your details once",
        icon = Icons.Filled.Badge,
        destination = NavDestination.DESIGNER_PROFILE,
        body = "Your own standing details in one place — name, institution, qualification, " +
            "empanelment, photograph, signature and CV — rather than typed into a stage form " +
            "every time. A new design workshop's stage 1 and stage 3 START PRE-FILLED FROM THIS " +
            "PAGE, and what they receive is a COPY: a report records who ran a workshop at the " +
            "time, so moving institution next year must not rewrite a report already submitted. " +
            "Four of the twenty-one are required — name, qualification, phone and email — " +
            "because they are what a report is submitted under and how the person who signed it is " +
            "reached. Watch out: a workshop already under way keeps what it was created with. " +
            "Correcting something here never reaches back into it; stage 1 and stage 3 of that " +
            "workshop are where its own copy is edited. Your reports NAME your CV rather than " +
            "carrying it, so send the file alongside the report. This step and the eight below it " +
            "are designer ground — this page, Design workshops, and everything filed under a " +
            "workshop — and every one of those menu rows is ABSENT rather than greyed out for an " +
            "account that is not a designer, an admin or the master admin, because this app hides a " +
            "row it would refuse. So a missing row is not a menu that failed to load: read on to " +
            "learn the process, and ask an administrator to empanel you rather than hunting for " +
            "rows that were never drawn.",
    ),
    WalkStep(
        id = "design-workshop",
        title = "Design workshops · Open the fortnight",
        icon = Icons.Filled.DesignServices,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "This is the design & prototype development workshop itself: twenty-two stages of " +
            "capture that end in a report submitted to a Development Commissioner's office. It is a " +
            "DIFFERENT RECORD from the Workshop in the first step — that one is the event an " +
            "artisan attended; this one is the design document — and the \"Design & prototype " +
            "workshop\" box you have been seeing on every record form is what files a record under " +
            "one so it appears in that workshop's lists. That box changes where a record is filed " +
            "and never who may read it. A designer does not start a workshop: an admin creates it " +
            "and adds you, so an empty list is the ordinary state for a newly empanelled designer " +
            "and the screen says who to ask. It opens and fills with no signal, and every stage " +
            "saves itself into a draft on this handset as you go, which is what makes a fortnight " +
            "of fieldwork survivable. Watch out: link it to a recorded Workshop early. The " +
            "reference pickers inside the stages are narrowed to that workshop's artisans, products " +
            "and tools; an unlinked workshop offers you the whole repository instead, which is " +
            "where the wrong artisan gets picked.",
    ),
    WalkStep(
        id = "design-workshop-codes",
        title = "Cards & tags · Print the codes",
        icon = Icons.Filled.QrCode2,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Open a workshop and press \"Cards & tags\" on its index. Three kinds of code come " +
            "off it: a tag for every prototype, a card for every artisan on the roster, and the " +
            "workshop's own code. Stages 14, 15 and 16 each begin by choosing a prototype from a " +
            "list as long as the fortnight made it, and scanning a tag removes the choosing — " +
            "choosing wrong is how two days of measurements end up attached to somebody else's " +
            "work with nothing downstream able to tell. The sheet is drawn on this device and saved " +
            "as a .pdf, and codes are decoded on the device too, so both halves work with no " +
            "signal. THE WORKSHOP'S OWN CODE IS A JOIN CARD AND DOES A DIFFERENT JOB: one person " +
            "creates the workshop and everybody else scans that card to join THE SAME ONE, which is " +
            "what stops a team of four ending the fortnight with four parallel workshops. A card is " +
            "good for one person unless an admin makes it good for more, and a late-comer whose " +
            "card was already spent is not turned away — the ask is filed for an admin to " +
            "decide, so their work is not orphaned while they wait. Watch out: print at 100%, not " +
            "\"fit to page\" — the cards are drawn at the size they are cut to — and " +
            "print them at the START of the fortnight. A tag tied on afterwards is a tag tied on " +
            "from memory.",
    ),
    WalkStep(
        id = "design-workshop-questionnaires",
        title = "Questionnaires · Your own forms",
        icon = Icons.AutoMirrored.Filled.ListAlt,
        destination = NavDestination.CUSTOM_QUESTIONNAIRES,
        body = "The standard interview above is one form everybody answers and nobody may add a " +
            "question to. This is the other kind: a form you built yourself for this workshop, in " +
            "the .xlsx pro-forma, whose wording you may change. \"Use this questionnaire again\" " +
            "copies the form to another workshop — its sections and its live questions, never " +
            "anybody's answers — so next season starts from last season's instrument instead " +
            "of from a blank " +
            "page. You can also hand a form straight to a colleague's handset as a bundle, over a " +
            "QR code, the share sheet or Bluetooth, with no server in the middle. Watch out: a form " +
            "OPENS with no signal, and creating one banks through the outbox, but ANSWERING one " +
            "offline is refused on purpose — a queued sitting would replace whatever the " +
            "server holds for it. Take those answers on the standard interview or wait for a bar.",
    ),
    WalkStep(
        id = "design-workshop-stages",
        title = "Stages · Fill the twenty-two",
        icon = Icons.Filled.Layers,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Work down the stage index. Every section of the printed report is one of these " +
            "stages, and the phone and the laptop draw the same boxes in the same order because " +
            "both read the same registry from the server — so a stage half-filled in the " +
            "village is the stage you finish on a laptop that evening. Each stage takes its basic " +
            "fields, its deeper ones, its photographs and its own sections if you added any, and " +
            "every narrative box has a microphone. A reference picker is how you point a stage at " +
            "the artisan, product, tool or process you recorded in the earlier steps. WATCH OUT, " +
            "AND THIS IS THE ONE TO REMEMBER: choosing a record COPIES its values onto this stage, " +
            "and the report prints that copy. Editing the artisan next week does not rewrite a " +
            "report already handed over — come back and re-pick the record here if you want " +
            "the newer values. Point a row at a DIFFERENT record and whatever the previous one put " +
            "there leaves with it, so the old artisan's phone number can never end up sitting under " +
            "the new artisan's name; anything you typed yourself is left alone, because a pick only " +
            "ever writes into a box that is empty or still holds exactly what the last pick wrote. " +
            "A photograph gallery is only ever seeded and never replaced, so re-picking cannot " +
            "destroy the pictures you took in the room.",
    ),
    WalkStep(
        id = "design-workshop-sketches",
        title = "Sketches & prototypes · Upload, trace and rank",
        icon = Icons.Filled.Gesture,
        destination = NavDestination.SKETCHES_AND_PROTOTYPES,
        body = "Pick a workshop, then work on its sketches and its prototypes from one screen " +
            "instead of walking into each stage form to find them. It asks WHICH WORKSHOP first, " +
            "which is the thing you actually know while standing there with the drawing in your " +
            "hand. Two tabs: Upload adds the piece and its photographs, Review is the first of the " +
            "two rating rounds — this workshop's own designers on each other's work. Photograph " +
            "a drawing and the tracer turns it into line art on this device, and you can save the " +
            "result as SVG, PNG, PDF, EPS or DXF for a print shop or a cutting machine. Watch out: " +
            "the crop and the sharpening feed the TRACE and nothing else — they cannot " +
            "re-encode your photograph, so the original file stays the artifact. Set-aside sketches " +
            "count: the designs that were never prototyped are worth recording and are rateable in " +
            "both rounds, because a wider pool picking one up later is the whole reason to write " +
            "them down. This screen opens for designers, admins and the master admin, and WITHOUT " +
            "THAT ACCESS THE SCREEN ITSELF WILL NOT SAY SO — unlike Design review below, which " +
            "states the tier in as many words before it asks the repository anything, this one " +
            "takes no account at all: it asks for your design workshops and draws whatever comes " +
            "back, so a refusal arrives looking like a list that could not be fetched, and with no " +
            "signal it arrives blaming the network. If the list never comes and you are not " +
            "empanelled as a designer, that is the reason, and an administrator is who fixes it — " +
            "not a better bar of signal.",
    ),
    WalkStep(
        id = "design-review",
        title = "Design review · Rate a colleague's work",
        icon = Icons.Filled.Star,
        destination = NavDestination.DESIGN_REVIEW,
        body = "Score a colleague's sketches and prototypes out of five, say what you think of each " +
            "piece, say what you would change, and put them in an order. This is the SECOND round: " +
            "a workshop's own designers rate each other on the Review tab of the step above, and " +
            "then the wider pool ranks the pieces a finished workshop has produced — including " +
            "workshops the reviewer was never added to, which is the whole difference between the " +
            "two levels. There are two ways in and they are not two spellings of one control: the " +
            "dropdown lists workshops you can open yourself, and the box beside it takes any " +
            "workshop's link or id, because the pool round is by design about work you were never " +
            "part of. Watch out: a piece reaches the pool only once its peer round has been closed " +
            "on it, so an empty round usually means \"not yet\" rather than \"nothing here\", and " +
            "the screen says which. The comment and the suggestion are two boxes on purpose — " +
            "what you would change is the half a maker acts on, and it is unfindable buried inside " +
            "a paragraph of assessment. This screen opens for designers, admins and the master " +
            "admin; without that access you can still learn the process here, but the screen will " +
            "tell you designer access is required.",
    ),
    WalkStep(
        id = "design-workshop-readiness",
        title = "Readiness · What is still outstanding",
        icon = Icons.AutoMirrored.Filled.Assignment,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "The alternative is opening all twenty-two stages on the last afternoon to find the " +
            "four empty required fields, in three of them, that you meant to come back to. ON THIS " +
            "HANDSET READINESS IS NOT A SEPARATE SCREEN — the web has a page of its own, and " +
            "here it is the stage index itself, which is arguably the better place for it. Each row " +
            "carries a bar, a percentage, and either how many of its required fields are filled or " +
            "the words \"nothing required here\" so a stage that is complete by construction cannot " +
            "be mistaken for one you finished. A stage with gaps expands into the list of exactly " +
            "what is missing, by field name, and every line taps straight through to that box; the " +
            "stage form's own header repeats the figure while you are inside it. Watch out: " +
            "NOTHING ON THIS HANDSET REFUSES YOU BECAUSE A REQUIRED FIELD IS EMPTY. A stage writes " +
            "itself to the device as you type and syncs without asserting that check, and it is " +
            "built that way on purpose — a stage you were halfway through would otherwise " +
            "quietly stop syncing for the rest of the day. The figure is a plan and not a gate: it " +
            "is there so that a thin stage is a decision you made rather than something you missed. " +
            "Use the index on the first afternoon as well as the last.",
    ),
    WalkStep(
        id = "design-workshop-report",
        title = "Report · Generate the deliverable",
        icon = Icons.Filled.Description,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Open a workshop and press \"Generate the report\". Choose the template and the " +
            "accent colour, read the document back as real pages or as continuous reading, and " +
            "export the .docx or the .pdf. IT IS BUILT ON THIS DEVICE, from what has been saved " +
            "here, by the same document model the server's writers consume — so a report can " +
            "be produced in a courtyard with no signal at all, and what you read is what is " +
            "generated rather than an approximation of it. Two annexures a handset cannot draw are " +
            "named on the file itself rather than silently missing: transcripts, which are produced " +
            "after the audio reaches the server, and machine-assisted text. Questionnaire answers " +
            "are drawn here too, but only once this handset has opened that workshop's " +
            "questionnaire list at least once with a connection — until then the file says so. " +
            "Watch out: a warning never stops a file being produced, and never travels inside the " +
            "document either — an officer opening the .docx next month must not find a note " +
            "about what was missing on the day, which is also why the export screen is the only " +
            "place those warnings can be read. The preview is read-only on purpose: to correct " +
            "something it shows, open the stage it came from, because the printed value is that " +
            "stage entry's own frozen copy.",
    ),
    WalkStep(
        id = "design-workshop-history",
        title = "Report history · What you have produced",
        icon = Icons.Filled.History,
        destination = NavDestination.DESIGN_WORKSHOPS,
        body = "Every file ever generated for this workshop — including ones a phone produced " +
            "with no signal — with its checksum, its size, its page count, its template, when " +
            "it was made and by whom, and a comparison between any two of them. A report submitted " +
            "to a ministry comes back for revision three or four times, and \"did you update the " +
            "cost sheet before you resubmitted?\" needs an answer that is evidence rather than " +
            "memory. Watch out: nothing here can be tidied up, and that is the point — the " +
            "checksum is what makes the record evidence, and evidence that can be edited is not " +
            "evidence. The comparison is between two generated FILES; for who changed a particular " +
            "value and when, open the workshop's Authorship & divergence screen instead. This one " +
            "needs a connection, because it lists files made on other devices by other people, so " +
            "unlike your stages it is not kept on this handset; and a workshop that has not reached " +
            "the repository yet has no history at all, which costs you nothing you have captured.",
    ),
    WalkStep(
        id = "design-workshop-inspection",
        title = "Workshops to inspect · The other side of it",
        icon = Icons.Filled.FindInPage,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "If your account is an Inspector / Reviewer, this is your whole surface: the design " +
            "& prototype workshops an admin has assigned you, read-only, with the provenance of " +
            "each field beside it so you can see what was captured, when, and by whom. It is a " +
            "narrow door on purpose — an inspector is not a member of the workshop and cannot " +
            "write to a single one of its stages, which is exactly why the assignment exists " +
            "instead of a wider permission. Watch out: THIS ROW IS FOR INSPECTORS AND NOBODY ELSE. " +
            "An admin and even the master admin are refused it by name; what an admin gets instead " +
            "is the appointment screen off a workshop's own index, one workshop at a time. The " +
            "screen needs a connection: an inspection is read from the repository each time and " +
            "nothing about it is kept on this handset.",
    ),
    WalkStep(
        id = "offline",
        title = "No signal · What still works",
        icon = Icons.Filled.CloudOff,
        body = "Most of a fortnight happens where there is no bar of signal, and this app is built " +
            "for that rather than apologising for it. A design workshop opens and fills with no " +
            "connection and every stage saves into a draft on this handset as you go. Records you " +
            "create are queued and sent when a connection returns — the strip at the top of " +
            "the app says how many are waiting, and tapping it opens the tray, which lists anything " +
            "the server refused with its own reason and lets you retry one or all of them. Code " +
            "cards print, tags decode and the report generates, all on the device. WATCH OUT, " +
            "BECAUSE THIS ONE COSTS SOMEBODY ELSE'S WORK: a queued CORRECTION to an existing record " +
            "replaces that record whole. If a colleague edited it while you were out of signal, " +
            "their change is overwritten field for field when yours drains, and nobody is told. " +
            "Correct a record while you have a connection where you can, and if you cannot, say so " +
            "to whoever else is working on it.",
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
    body = "${walkthroughJourney.size} steps, in this order — the repository records first, " +
        "then the design & prototype workshop those records feed. It is the same journey the " +
        "Walkthrough teaches on the web, so a colleague working on a laptop is reading what you " +
        "are reading. The order is the thing worth learning: get it wrong and the pickers inside " +
        "the later screens have nothing to offer you, which costs a return trip rather than five " +
        "minutes. Work down it once and you should not need this again. You can leave at any " +
        "point, and you can reopen this from the menu without losing whatever form you are in the " +
        "middle of.",
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
    body = "A missing field is a phone call; a missing recording is another trip. Every artisan you " +
        "spoke to has a record, with their Do's and Don'ts in it. Every product you photographed " +
        "has its dimensions and its costs. Every process has its steps in order and the steps have " +
        "video. Every tool has a material, a maker and a replacement cost. The interview has no " +
        "unexplained gaps. Anything you shot that has no home is in Miscellaneous Media. Every " +
        "prototype is tagged. And before the signal goes for good: open the top strip and check " +
        "the queue is empty, because a record still waiting on this handset is a record the " +
        "repository has never seen.",
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
 * ── THREE CARDS AND NOT THE WEB'S FOUR, AND THE MISSING ONE IS A CAPABILITY ──────────────────────
 *
 * `frontend/components/guide/inspectorSteps.ts` teaches four: `inspection-list`, `inspection-read`,
 * `inspection-feedback` and `inspection-review-queue`. THE THIRD IS NOT A MISSING ENTRY POINT ON
 * THIS HANDSET, IT IS A MISSING CAPABILITY, and the difference is why it is not written below as a
 * step with a rewritten address. The Retrofit interface declares exactly five inspection endpoints —
 * `WorkshopRepositoryApi.kt` lines 1797, 1802, 1810, 1829 and 1841: four GETs and one PUT of the
 * inspector roster — and there is no `POST …/feedback` and no `POST …/send-back`, although the
 * backend serves both (`routes/design_workshop_inspections.py`: `record_inspection_feedback`,
 * `send_workshop_back_for_revision`). Over `InspectionDetailScreen.kt` and `InspectionListScreen.kt`,
 * `grep -rniE 'suggestion|feedback|correction|send.?back'` finds NOTHING, and
 * `grep -rn 'inspectionFeedback' android/app/src/main --include=*.kt` finds the field on the
 * payload's DTO and no reader of it anywhere under `ui/`.
 *
 * So this handset cannot file a correction AND CANNOT READ THE ONES ALREADY FILED. That second half
 * is worth stating because the web's own closing copy got it wrong in the other direction — it said
 * "the handset can read the suggestions already on a workshop", which the DTO makes plausible and
 * the screen makes false. The honest version is in [walkthroughInspectorOutro], as prose, on the
 * closing card, which is exactly where the web puts its equivalent (`INSPECTOR_TRACK.recapLead`).
 *
 * ⚠ WHAT MUST NOT HAPPEN IS A FOURTH STEP WITH `destination = null` AND THE WEB'S FIELD LIST ON IT.
 * That card would draw a heading reading "What the screen asks for" over the four controls of a
 * panel this phone does not have, on the one surface whose job is teaching a newcomer what things
 * are called. [walkthroughInspectorOmissions] is where the omission is REGISTERED instead, in one
 * place, with a test holding it to the web's own list so that a deck which quietly drops a second
 * card cannot pass as this decision.
 *
 * ── AND THE LAST CARD IS NOT PART OF THE INSPECTION SURFACE, ON PURPOSE ─────────────────────────
 *
 * `Review` is open to Field Contributor and above, so it is not the tier's own screen — but it is
 * the other half of what "Inspector / Reviewer" names, and at rank 37 an inspector outranks a
 * designer there, which is the only place in this product where that rank buys anything. Leaving it
 * out would teach an inspector that their whole job is the workshops somebody assigned them. Its
 * ADDRESS is rewritten to the record browser, which is not an invention: the header of this file
 * records that two of the web's steps naming web-only surfaces were already rewritten to name where
 * the capability lives on this handset, and the shipped `review` step does exactly that against the
 * same `NavDestination.REVIEW` — whose router arm says in as many words that "Android has no
 * standalone review queue: reviewing happens inside the record browser".
 */
internal val walkthroughInspectorJourney: List<WalkStep> = listOf(
    WalkStep(
        id = "inspection-list",
        title = "Workshops to inspect · Open your assigned workshops",
        icon = Icons.Filled.FindInPage,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "Open the design & prototype workshops an admin has assigned you, and work down " +
            "them one at a time. An inspection is scoped one workshop at a time, by a row an admin " +
            "creates, and that scoping is the whole design rather than a limitation of it: the " +
            "alternative — an inspector who can read every workshop in the archive — would be a " +
            "second full read of the repository and a second place to look when somebody has " +
            "access they should not. The screen is a search box over your own assignments and then " +
            "a card per workshop carrying its title, craft, cluster, place, code, status and " +
            "dates, with the word \"Read-only\" printed on every one of them; the count above the " +
            "list is the server's own total rather than the length of the page you are looking at, " +
            "and the pager moves twenty at a time. Watch out: THIS SURFACE BELONGS TO THE " +
            "INSPECTOR / REVIEWER TIER AND IS A SET WITH EXACTLY ONE MEMBER, not \"Inspector and " +
            "above\" — an admin is refused it by name, so is the master admin, and so is a " +
            "professor, which makes it the only row in this menu a master admin cannot reach. You " +
            "cannot ask for an assignment or give yourself one either: an admin makes them one " +
            "workshop at a time from that workshop's own stage index, which is a screen this " +
            "account cannot open. The screen needs a connection every time — an inspection is read " +
            "from the repository on each visit and nothing about it is kept on this phone, because " +
            "an assignment an admin ended this morning has ended and a copy held here could not " +
            "know that. And an empty list is a real answer: the page says \"No workshop is " +
            "assigned to you\" and tells you in as many words that it is hiding nothing, a search " +
            "that matched nothing says so differently, and a load that failed keeps whatever rows " +
            "were already on screen and puts the failure above them — so a correct empty state and " +
            "a dropped connection never read as the same thing.",
    ),
    WalkStep(
        id = "inspection-read",
        title = "Workshop under inspection · Read every stage",
        icon = Icons.Filled.Layers,
        destination = NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
        body = "Tap a card in that list and read the workshop back, stage by stage, as the " +
            "designers recorded it. A report that reaches a Development Commissioner's office is " +
            "read by somebody who did not run the fortnight, and \"who wrote this field, and " +
            "when\" is most of what that reading is for — so this screen draws the same authorship " +
            "sentence under every value that the designer's own stage form draws under every box, " +
            "from the same stamp, because an inspector and the designer being inspected must never " +
            "be reading two different accounts of who did what. The walk is over the twenty-two " +
            "stages the registry declares and NOT over the stages somebody happens to have " +
            "touched: an empty stage is a finding on an inspection screen rather than a row to " +
            "omit, so a stage nobody started renders as a stage nobody started, and one the source " +
            "document marks as legitimately skippable says so beside its own count. Each stage " +
            "prints the server's own \"n of m required fields answered\", each record card lists " +
            "the values that exist, and the fields nobody answered are counted underneath instead " +
            "of drawn as forty empty rows. THERE IS NO SINGLE FIGURE FOR THE WORKSHOP AS A " +
            "WHOLE ON THIS HANDSET — the browser prints one and this screen counts stage by " +
            "stage — and that is deliberate rather than missing: a second arithmetic over one " +
            "workshop is how a designer and their inspector come to disagree about what is " +
            "outstanding. Watch out: READ-ONLY HERE IS STRUCTURAL AND NOT A " +
            "SETTING. There is no stage form on this screen, no Save and no delete, and none of " +
            "them is missing — every stage-editing route stands behind a loader that refuses " +
            "anybody outside the design-workshop write set before it looks at the row, and an " +
            "inspector is outside it by construction, so an affordance here would be a 404 waiting " +
            "to be found by somebody who would reasonably conclude the app is broken. " +
            "PHOTOGRAPHS, RECORDINGS AND ATTACHMENTS ARE COUNTED AND NOT CARRIED: a media field " +
            "says how many files are recorded there and that an inspection read does not carry " +
            "them, which is deliberate, because an empty gallery would read as a file that failed " +
            "to load — and it means a judgement that turns on seeing a photograph is one this " +
            "screen cannot settle. A completeness figure is arithmetic and not a verdict: a stage " +
            "can answer every required field and still be wrong. A value that came from an " +
            "artisan, product or tool record is a COPY taken when the stage was saved, so the " +
            "record may have been corrected since and this stage would not have changed. And " +
            "answers to questions a workshop's own designer added are counted and not shown, " +
            "because the questions themselves are read through a route an inspection does not " +
            "reach, and answers without their questions are not evidence of anything.",
    ),
    WalkStep(
        id = "inspection-review-queue",
        title = "Review · Work the record queue",
        icon = Icons.Filled.Visibility,
        destination = NavDestination.REVIEW,
        body = "Work the repository-wide queue of records waiting on a decision, which is the " +
            "other half of the tier's own name. The inspection surface is the handful of " +
            "workshops somebody assigned you; this is the standing job — artisans, products, " +
            "processes, tools and interviews submitted by anybody ranked below you, waiting to be " +
            "approved, rejected or sent back for revision — and it is the one place in this " +
            "product where the tier's RANK buys something rather than its set membership, because " +
            "at 37 you outrank a designer and a designer's records reach your queue. THIS HANDSET " +
            "HAS NO SEPARATE REVIEW QUEUE — the web has a page of its own, and here the same menu " +
            "row opens the record browser, which is the one surface where a reviewer can read a " +
            "submission and act on it. Watch out: THIS SCREEN IS NOT THE INSPECTION SURFACE AND IS " +
            "NOT GATED LIKE IT. Review opens for Field Contributor and above — everybody with " +
            "somebody ranked below them — so the people working beside you in the queue are not " +
            "inspectors; what your tier changes is WHOSE records you see. You review strictly " +
            "below you and never across: a record submitted by another Inspector / Reviewer is not " +
            "yours to decide, and neither is one from a professor or a directorate post. And " +
            "reviewing a record and rewriting one are two different ladders — editing somebody " +
            "else's record is Professor and above, and 37 is below 40, so the server refuses your " +
            "edit even where a control offers it. Send it back with what to fix and let the person " +
            "who recorded it correct it, which is what the rule is for.",
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
    body = "${walkthroughInspectorJourney.size} steps, in the order an inspection happens — the " +
        "list you were given, the workshop you read, and the wider record queue your tier opens. " +
        "Your surface is not the designer's with the buttons removed: it is a different tree, " +
        "behind a different gate, reached through an assignment an admin makes one workshop at a " +
        "time. You cannot run a design & prototype workshop, and that is the point of the tier " +
        "rather than a limitation of it — an inspector who could fill in a stage would be " +
        "reviewing their own work, and the server refuses to start if the two sets ever overlap. " +
        "This is the deck that opens for an Inspector / Reviewer account; the designer's " +
        "walkthrough is still here in full, and the button under this card switches to it. You can " +
        "leave at any point, and you can reopen this from the menu without losing whatever form " +
        "you are in the middle of.",
)

/**
 * The inspector deck's closing card — and the one place on this handset that says what it cannot do.
 *
 * A CHECKLIST AND NOT A SUMMARY, on the same argument as the designer's: the moment it is read for
 * is the one just before somebody acts irreversibly. Here that moment is a suggestion being filed,
 * which cannot be edited or withdrawn, or a send-back, which moves a report onto somebody's desk.
 *
 * ⚠ AND IT OPENS BY SAYING NEITHER CAN BE DONE FROM THIS PHONE. That sentence is the whole reason
 * the deck is three cards and not four — see [walkthroughInspectorJourney] for the greps, and
 * [walkthroughInspectorOmissions] for the register a test holds to the web's own list. It is on the
 * CLOSING card rather than in a step of its own because a step is a door and this is the absence of
 * one: the web makes the identical placement, putting the same fact in `INSPECTOR_TRACK.recapLead`
 * rather than in a card. An inspector who reads this knows to finish the job in a browser; one who
 * is told nothing hunts the menu for a box that was never built, which this file's own header calls
 * worse than a missing step.
 */
private val walkthroughInspectorOutro = WalkStep(
    id = "before-you-send-it-back",
    title = "Before you send a report back",
    icon = Icons.Filled.CheckCircle,
    body = "FILING A CORRECTION IS A BROWSER JOB, and this is the one thing on this deck the " +
        "handset cannot do. There is no box here to type a suggestion into and no button to send " +
        "a report back — and this app cannot show you the suggestions already filed on a workshop " +
        "either, although the payload it reads carries them. So read the workshop on the phone, in " +
        "the courtyard, where the signal and the artisans are; then open a browser to say what is " +
        "wrong. When you do: your note says what is wrong AND what it should say, because the " +
        "designers read it exactly as written with no conversation attached. You picked the right " +
        "stage, or chose the report as a whole on purpose rather than by leaving the box alone. " +
        "You pressed the button you meant — filing a suggestion leaves the report where it is, and " +
        "only sending it back puts it on the designers' desks. You are not deciding on a " +
        "photograph you could not see, because media is counted on an inspection read and never " +
        "carried. And a value that looks wrong may be a copy taken when the stage was saved, so " +
        "the record it came from may have been corrected since without the stage changing.",
)

/**
 * The web's inspector cards this handset deliberately does not teach, and nothing else.
 *
 * ONE REGISTER, IN ONE PLACE, WITH A TEST STANDING OVER IT. The failure this exists to prevent is
 * not the omission — the omission is argued in [walkthroughInspectorJourney] and is correct today —
 * it is the omission going UNNOTICED when it stops being correct. A handset that grows a feedback
 * box, or a second card quietly dropped from this deck while somebody was editing prose, both look
 * exactly like this file on the day they happen. `WalkthroughDecksTest` reads
 * `frontend/components/guide/inspectorSteps.ts` at runtime and asserts that the web's ids minus this
 * deck's ids are EXACTLY this set: a third state is a red test, in either direction.
 *
 * It is a set of ids and not a set of reasons, because the reason belongs beside the greps that
 * prove it and a second copy of an argument is an argument that can disagree with itself.
 */
internal val walkthroughInspectorOmissions: Set<String> = setOf(
    // `POST /design-workshop-inspections/{id}/feedback` and `.../send-back` have no client method on
    // this handset, `InspectionDetailScreen` draws no panel, and no screen under `ui/` reads the
    // `inspectionFeedback` rows the payload already carries. A capability, not an address.
    "inspection-feedback",
)

/** The inspector's deck: the opening card, three screens, and the closing checklist. */
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
 * every one of which the inspection surface refuses by name. `FieldPermissions.canInspectDesignWorkshops`
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

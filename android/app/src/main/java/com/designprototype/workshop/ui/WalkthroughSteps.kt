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

internal data class WalkStep(
    val id: String,
    val title: String,
    val body: String,
    val icon: ImageVector? = null,
    val destination: NavDestination? = null,
    val why: String = "",
    val watch: List<String> = emptyList(),
)

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

private val walkthroughIntro = WalkStep(
    id = "intro",
    title = "The order the work happens in",
    icon = Icons.Filled.Explore,
    body = "${walkthroughJourney.size} steps — field records first, then the design workshop they feed. Leave any time; reopen it from the menu.",
)

private val walkthroughOutro = WalkStep(
    id = "before-you-leave",
    title = "Before you leave the field",
    icon = Icons.Filled.CheckCircle,
    body = "Before you leave: every artisan has Do's and Don'ts, every product has dimensions and costs, every process has video, every tool has a maker and cost, every prototype is tagged. Then check nothing is waiting to upload.",
)

internal val walkthroughSteps: List<WalkStep> =
    listOf(walkthroughIntro) + walkthroughJourney + walkthroughOutro

internal fun walkthroughStepNumber(deck: WalkthroughDeck, step: WalkStep): Int? =
    deck.journey.indexOfFirst { it.id == step.id }.takeIf { it >= 0 }?.plus(1)

internal data class WalkthroughDeck(
    val id: String,
    val name: String,
    val audience: String,
    val journey: List<WalkStep>,
    val steps: List<WalkStep>,
)

internal val WALKTHROUGH_DESIGNER_DECK = WalkthroughDeck(
    id = "designer",
    name = "Documenting a craft",
    audience = "Designers, and anybody learning how a workshop is run",
    journey = walkthroughJourney,
    steps = walkthroughSteps,
)

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

private val walkthroughInspectorIntro = WalkStep(
    id = "inspector-intro",
    title = "What an inspection is, in order",
    icon = Icons.Filled.Explore,
    body = "${walkthroughInspectorJourney.size} steps: your assignments, reading a workshop, suggestions, and the review queue. Inspectors read workshops but never fill them in.",
)

private val walkthroughInspectorOutro = WalkStep(
    id = "before-you-send-it-back",
    title = "Before you finish an inspection",
    icon = Icons.Filled.CheckCircle,
    body = "Read every stage, including empty ones. Say what's wrong and what it should say. Pick the right stage. Check existing suggestions first. Choose the right button.",
)

internal val walkthroughInspectorOmissions: Set<String> = emptySet()

internal val WALKTHROUGH_INSPECTOR_DECK = WalkthroughDeck(
    id = "inspector",
    name = "Inspecting a workshop",
    audience = "Inspector / Reviewer",
    journey = walkthroughInspectorJourney,
    steps = listOf(walkthroughInspectorIntro) + walkthroughInspectorJourney +
        walkthroughInspectorOutro,
)

internal val WALKTHROUGH_DECKS: List<WalkthroughDeck> =
    listOf(WALKTHROUGH_DESIGNER_DECK, WALKTHROUGH_INSPECTOR_DECK)

internal fun walkthroughDeckFor(user: UserDto?): WalkthroughDeck =
    if (user != null && FieldPermissions.canInspectDesignWorkshops(user)) {
        WALKTHROUGH_INSPECTOR_DECK
    } else {
        WALKTHROUGH_DESIGNER_DECK
    }

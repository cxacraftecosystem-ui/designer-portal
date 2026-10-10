import { Eye, FileSearch, Layers, MessageSquare } from "lucide-react";

import type { GuideStep } from "@/components/guide/steps";

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE INSPECTOR'S WALKTHROUGH — the Inspector / Reviewer tier, and nobody else.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ── WHY THIS IS A DECK OF ITS OWN AND NOT A CARD IN THE DESIGNER'S ─────────────────────────────
 *
 * `GUIDE_STEPS` already ends with one card about this surface — `design-workshop-inspection` — and
 * that card is correct where it stands: it is there so a DESIGNER knows what a colleague is looking
 * at when they read the workshop back, and it says so in as many words. It is not a walkthrough for
 * an inspector, and it cannot become one, because the deck it sits in is the fortnight of fieldwork
 * that an inspector does not do. An inspector opening `/guide` today is handed twenty-two cards
 * about recording artisans, filling stages and generating a report, exactly one of which describes
 * a screen they can open, sitting last.
 *
 * ── THE FACT EVERY RANK INSTINCT GETS WRONG, STATED ONCE HERE SO EVERY CARD CAN LEAN ON IT ──────
 *
 * INSPECTOR is rank 37. It sits between DESIGNER (35) and PROFESSOR (40), so it OUTRANKS a designer
 * — and it is deliberately OUTSIDE `DESIGN_WORKSHOP_ROLES`, which is a frozen SET and not a floor.
 * An inspector therefore cannot run a design & prototype workshop, cannot fill a stage, cannot
 * build a questionnaire and cannot open a designer profile, while being senior to the person who
 * does all four. That is not an oversight in the ladder; it is the entire reason the tier exists.
 * An inspector who could author a stage would be reviewing their own work, and the gate is what
 * prevents it. (An import-time check in `design_workshop_inspectors.py` once refused to boot if the
 * two sets intersected; it went on 2026-10-09, when the admin tiers — who are inside
 * `DESIGN_WORKSHOP_ROLES` — became appointable inspectors, and the rule moved to one workshop at a
 * time: `design_workshop_posts` refuses an inspection to whoever authored that workshop, and refuses
 * whoever inspects it every write to it.)
 *
 * The consequence for THIS file is that the inspector's surface is reached through machinery the
 * designer's deck never touches: a per-workshop assignment table (`DesignWorkshopInspector`), its
 * own API prefix, and its own loader — `load_inspectable_workshop_or_404`, whose docstring says in
 * as many words that it "has no `for_edit` parameter and must never grow one". Not
 * `load_workshop_or_404`, which is the designer's loader and which performs no role check at all on
 * its edit path. Describing this surface in the designer's vocabulary would invite the next reader
 * to close the "inconsistency" by pointing the two at one loader, which is the single most
 * expensive edit anybody could make here.
 *
 * ── AND WHAT AN ADMIN IS, WHICH IS THE SECOND THING EVERY INSTINCT GETS WRONG ───────────────────
 *
 * `INSPECTION_ROLES` is `frozenset({"INSPECTOR"})` — one member, the TIER. Until 2026-10-09 it was
 * also the surface's door, and an ADMIN, the master admin and a professor were all refused with a
 * 403 by name. The owner's ruling that day lets a Ministry Admin, an admin and the master admin be
 * APPOINTED to inspect one workshop by somebody else, so the door is now `INSPECTION_HOLDER_ROLES`
 * and those three open the surface — scoped, like an inspector, to the workshops they were
 * appointed to and nothing else. A professor and the two directorate posts are still refused. So no
 * card below may say "and above", or anything that reads as a threshold, in either direction: the
 * tiers admitted are a set, and they are admitted by appointment rather than by rank. Choosing who
 * inspects what is a separate act on Workshop oversight, behind a different predicate, and neither
 * implies the other.
 *
 * ── THE RULES THIS DECK KEEPS ──────────────────────────────────────────────────────────────────
 *
 * `label` is the nav entry's label where the screen has one ("Workshops to inspect", "Review"), and
 * otherwise the page's own heading read off the component ("Workshop under inspection",
 * "Correction suggestions"). `action` is the screen's own verb rather than a dashboard tile's,
 * because this surface has no tile on either client — see the directorate deck's header for the
 * same argument at length.
 *
 * `fields` here are SECTION DESCRIPTIONS on three of the four cards and real form labels on one. An
 * inspection read draws the values of a workshop it did not author, so it has no form and no labels
 * of its own; the Correction suggestions card is the exception in both directions, because that
 * panel is the only place on this surface where an inspector types anything at all, and its two
 * labels are real.
 *
 * ⚠ THE STANDING TRIPWIRE FOR ANYONE EDITING THIS FILE. The claim "nothing an inspector does can
 * change a workshop" was true when this surface shipped and is NOT true now, and it survived in the
 * designer's card in `steps.ts` after it stopped being true. Two write routes exist —
 * `POST /design-workshop-inspections/{id}/feedback` and `.../send-back` — and the second moves the
 * report to Needs revision. What remains true, and is the sentence to write instead, is that
 * nothing an inspector does changes a workshop's CONTENT: no stage value, no photograph, no
 * completeness figure, no record. Say that, and never the shorter thing.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * The inspector's screens, in the order an inspection actually happens: the list you are given,
 * the workshop you read, the suggestion you file, and the wider record queue the tier's rank buys.
 *
 * THE LAST CARD IS NOT PART OF THE INSPECTION SURFACE AND IS HERE ON PURPOSE. `/review` is open to
 * Field Contributor and above, so it is not the tier's own screen — but it is the other half of
 * what "Inspector / Reviewer" names, and at rank 37 an inspector outranks a designer there, which
 * is the only place in the product where that rank buys anything. Leaving it out would teach an
 * inspector that their whole job is four workshops somebody assigned them.
 */
export const INSPECTOR_STEPS: GuideStep[] = [
  {
    id: "inspection-list",
    label: "Workshops to inspect",
    action: "Open your assigned workshops",
    icon: FileSearch,
    href: "/design-workshop-inspections",
    summary:
      "Your list of the design & prototype workshops you have been assigned to inspect.",
    why:
      "Inspections are assigned one workshop at a time, so you see only the workshops you are responsible for. If a workshop isn't here, it hasn't been assigned to you.",
    fields: [
      "Search — by title, craft, cluster or workshop code, across your assignments only",
      "Each row: the workshop's title, its code, craft and cluster, its dates and its status"
    ],
    watch: [
      "Inspections are assigned by a Ministry Admin, an Admin or the Master Admin, one workshop at a time. To be assigned a workshop, ask one of them.",
      "A Ministry Admin, an Admin or the Master Admin can also be assigned to inspect a workshop. Like you, they see only the workshops assigned to them.",
      "You can't be assigned a workshop you worked on, because an inspection has to be independent. If a workshop you expected is missing, that may be why.",
      "If no workshop has been assigned to you yet, the page says so. If the list couldn't be loaded, it says that instead."
    ]
  },
  {
    id: "inspection-read",
    label: "Workshop under inspection",
    action: "Read every stage",
    icon: Layers,
    href: "/design-workshop-inspections",
    summary:
      "One workshop opened: all 22 stages as the designers recorded them, with who wrote each field and how complete it is.",
    why:
      "The report goes to the Development Commissioner's office, where its readers didn't attend the workshop, so who wrote each field matters. Each value shows its author exactly as the designer sees it on their own form.",
    fields: [
      "Dates, Designer, Venue — as stage 1 recorded them",
      "Required fields answered — a percentage across every stage",
      "Each stage, numbered and titled, with its own required-field count",
      "Under each value: who wrote it, and where it was copied from",
      "Media fields, as a count — “3 files recorded here”"
    ],
    watch: [
      "This page is read-only: you can read every stage but not change it.",
      "The completeness figure counts required fields answered — it doesn't say the answers are right. A stage that a workshop may skip is marked beside its count.",
      "Values chosen from a record are copied when the stage is saved, so the record may have been corrected since. The line under the value names the record it came from.",
    ]
  },
  {
    id: "inspection-feedback",
    label: "Correction suggestions",
    action: "File a suggestion, or send the report back",
    icon: MessageSquare,
    href: "/design-workshop-inspections",
    summary:
      "Where you write a note about a stage or about the whole report, and send the report back to its designers if it needs work.",
    why:
      "Your note is recorded under your name against this round of submission, and you decide whether the report stays where it is or goes back to its designers. Notes are permanent, so the record of corrections stays complete.",
    // The one card on this surface whose `fields` ARE real labels, because this panel is the only
    // place on it that asks an inspector for anything. Read off the panel in screen order.
    fields: [
      "What should be corrected?",
      "Which stage is it about? — “The report as a whole”, or one named stage",
      "“File a suggestion” — leaves the report where it is",
      "“Send the report back” — moves it to Needs revision"
    ],
    watch: [
      "The two buttons do different things. File a suggestion records your note and leaves the report where it is. Send the report back records the note and moves the report to Needs revision, so its designers act on it.",
      "A note can't be edited or withdrawn once it is filed. If you change your mind, file another; both stay on the record.",
      "You can file notes once the designers have handed the report in for inspection. Until then, the panel says it hasn't been handed in yet.",
      "Nothing you do here changes a workshop's content — no stage value, photograph or record. Sending a report back changes only its status; the designers correct the stages, and it returns to Pre-submission for another look.",
    ]
  },
  {
    id: "inspection-review-queue",
    label: "Review",
    action: "Work the record queue",
    icon: Eye,
    href: "/review",
    summary:
      "The queue of submitted records waiting for a decision — the “Reviewer” half of your role.",
    why:
      "Besides your assigned workshops, you review records — artisans, products, processes, tools and interviews — and approve them, reject them or send them back for revision.",
    fields: [
      "The queue, newest first, with the record's type, title and who submitted it",
      "Approve · Reject · Send for revision",
      "A comment — mandatory on Send for revision"
    ],
    watch: [
      "Your queue holds records submitted by Designers, Researchers, Field Contributors and Crowdsource Volunteers — not by other Inspector / Reviewers or more senior roles.",
      "You review other people's records but don't edit them. To get something fixed, use Send for revision and say what to change; the person who recorded it corrects it and resubmits.",
      "Send for revision needs a comment. Reject ends the record, so if it can be fixed, Send for revision is usually the better choice.",
      "You can tick several records and approve them together. Reject and Send for revision are done one record at a time, each with its own note.",
      "These are archive records, not workshop stages. Approving an artisan here doesn't change any workshop stage that copied that artisan — the stage keeps the copy taken when it was saved."
    ]
  }
];

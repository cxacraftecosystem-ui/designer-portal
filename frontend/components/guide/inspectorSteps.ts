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
 * prevents it — `backend/app/core/deps.py` refuses to boot if the two sets ever intersect.
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
 * `INSPECTION_ROLES` is `frozenset({"INSPECTOR"})` — one member. An ADMIN is refused this surface
 * with a 403 BY NAME, and so is the master admin, and so is a professor. So no card below may say
 * "and above", or anything that reads as a threshold, in either direction. What an admin gets
 * instead is the ADMINISTRATION of who inspects what, on Manage workshop access — a different
 * screen behind a different predicate, and neither implies the other.
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
      "Your own list: the design & prototype workshops an admin has assigned you, and nothing else in the repository.",
    why:
      "An inspection is scoped one workshop at a time, by a row an admin creates, and that scoping is the whole design rather than a limitation. The alternative — an inspector who can read every workshop in the archive — would make this a second full read of the repository and a second place to look when somebody has access they should not. So the list is exactly your assignments: if a workshop is not here, you have not been given it, and no search on this page will find it.",
    fields: [
      "Search — by title, craft, cluster or workshop code, across your assignments only",
      "Each row: the workshop's title, its code, craft and cluster, its dates and its status",
      "Nothing else — there is no filter by designer, district or date on this list"
    ],
    watch: [
      "YOU DO NOT ASK FOR AN INSPECTION AND YOU CANNOT GIVE YOURSELF ONE. An admin assigns them, one workshop at a time, on Manage workshop access — a screen you cannot open. There is no request route, no “ask to inspect” button, and nothing here is hidden behind one.",
      "THIS SURFACE IS THE INSPECTOR / REVIEWER TIER'S ALONE, and it is a set with exactly one member — not “Inspector and above”. An admin is refused it, the master admin is refused it, and a professor is refused it. The rank ladder is what misleads here: 37 sits between Designer and Professor, so every threshold instinct admits the three tiers above and all three are out.",
      "YOU CANNOT BE ASSIGNED A WORKSHOP YOU WORKED ON. The server refuses it with the reason spelled out — an independent review by somebody who worked on it is not a review — so if a workshop you expected is missing, that is one possible cause worth checking before reporting a fault.",
      "AN EMPTY LIST IS A REAL ANSWER AND THE SCREEN SAYS WHICH KIND IT IS. Nothing assigned reads “No workshop is assigned to you” and says in as many words that the page is not hiding anything; a list that could not be loaded says that instead, and keeps whatever rows were already on screen. A correct empty state and a silent failure look identical, and there is no other surface here to cross-check against."
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
      "A report that reaches a Development Commissioner's office is read by somebody who did not run the fortnight, and “who wrote this field, and when” is most of what that reading is for. So this page draws the same authorship line under every value that the designer's own stage form draws under every box — the same component producing the same sentence — because an inspector and the designer being inspected must never be reading two different accounts of who did what.",
    fields: [
      "Dates, Designer, Venue — as stage 1 recorded them",
      "Required fields answered — a percentage across every stage",
      "Each stage, numbered and titled, with its own required-field count",
      "Under each value: who wrote it, and where it was copied from",
      "Media fields, as a count — “3 files recorded here”"
    ],
    watch: [
      "READ-ONLY IS STRUCTURAL, NOT A SETTING. There is no stage form on this page, no Save and no delete, and none of them is missing: there is no route behind this page that would accept one. The loader this surface uses has no edit path at all and its own docstring forbids one being added.",
      "PHOTOGRAPHS, RECORDINGS AND ATTACHMENTS ARE COUNTED AND NOT CARRIED. The sentence under a media field says how many files are recorded there and that an inspection read does not carry them. That is deliberate — an empty gallery would read as a file that failed to load, which is not what happened — and it means a judgement that turns on seeing a photograph is one this screen cannot settle.",
      "A COMPLETENESS FIGURE IS AN ARITHMETIC, NOT A VERDICT. It counts required fields answered. A stage can read 100% and still be wrong, and a stage the source document marks as one a workshop may legitimately skip says so beside its own count.",
      "VALUES COPIED FROM A RECORD ARE COPIES, TAKEN WHEN THE STAGE WAS SAVED. The artisan record may have been corrected since. The line under the value tells you which record it came from; it does not tell you that the record still says that.",
      "ANSWERS TO A WORKSHOP'S OWN CUSTOM QUESTIONS ARE COUNTED AND NOT SHOWN, because the questions themselves are read through a route an inspection does not reach, and answers without their questions are not evidence of anything."
    ]
  },
  {
    id: "inspection-feedback",
    label: "Correction suggestions",
    action: "File a suggestion, or send the report back",
    icon: MessageSquare,
    href: "/design-workshop-inspections",
    summary:
      "The one place an inspector writes: a note about a stage or about the report as a whole, and the button that puts it on the designers' desks.",
    why:
      "An inspection that ends in a verdict nobody can read is not a review. This panel is what turns reading into an act: your note is recorded against this submission round under your name, and one of the two buttons decides whether the report stays where it is or goes back. Both are permanent — neither can be edited or withdrawn afterwards — because a correction record that can be quietly revised is not a record.",
    // The one card on this surface whose `fields` ARE real labels, because this panel is the only
    // place on it that asks an inspector for anything. Read off the panel in screen order.
    fields: [
      "What should be corrected?",
      "Which stage is it about? — “The report as a whole”, or one named stage",
      "“File a suggestion” — leaves the report where it is",
      "“Send the report back” — moves it to Needs revision"
    ],
    watch: [
      "⚠ THE TWO BUTTONS DO DIFFERENT THINGS AND ONLY ONE OF THEM MOVES ANYTHING. Filing a suggestion records your note and leaves the report exactly where it is. Sending it back records the same note AND moves the report to Needs revision, which is what actually puts it on its designers' desks. A suggestion filed on its own may sit unread until somebody opens the workshop.",
      "NEITHER CAN BE EDITED OR WITHDRAWN. An officer who changes their mind files another one, and both stay on the record. The screen says so under the buttons.",
      "THE BOX IS CLOSED UNTIL THE REPORT IS HANDED IN. If a workshop has not been submitted for inspection yet there is nothing to comment on, and the panel says so rather than accepting a note that would belong to no round. Its designers hand it in from the workshop's own screen.",
      "NOTHING YOU DO HERE CHANGES THE WORKSHOP'S CONTENT — not a stage value, not a photograph, not the completeness figure, not a record. What a send-back changes is the report's STATUS, and the designers are the ones who act on it next: they hand it back in by correcting the stages, and it returns to Pre-submission for a fresh pass.",
      "THERE IS NO OFFLINE QUEUE ON AN INSPECTION. If the repository cannot be reached, nothing is filed and what you typed is still in the box — unlike a designer's stage save, which banks itself and syncs later. Try again when you have signal."
    ]
  },
  {
    id: "inspection-review-queue",
    label: "Review",
    action: "Work the record queue",
    icon: Eye,
    href: "/review",
    summary:
      "The repository-wide queue of submitted records waiting on a decision — the other half of “Inspector / Reviewer”.",
    why:
      "The inspection surface is four workshops somebody assigned you. This is the standing job: artisans, products, processes, tools and interviews submitted by anybody ranked below you, waiting to be approved, rejected or sent back for revision. It is the one place in this product where the tier's RANK buys something rather than its set membership — at 37 you outrank a designer, so a designer's records reach your queue, which is the reason the tier sits where it does on the ladder.",
    fields: [
      "The queue, newest first, with the record's type, title and who submitted it",
      "Approve · Reject · Send for revision",
      "A comment — mandatory on Send for revision",
      "Edit — offered on the row, and see the caution below before you use it"
    ],
    watch: [
      "THIS SCREEN IS NOT THE INSPECTION SURFACE AND IS NOT GATED LIKE IT. Review opens for Field Contributor and above — everybody with somebody ranked below them — so the people beside you in the queue are not inspectors. What your tier changes is WHOSE records you see.",
      "YOU REVIEW STRICTLY BELOW YOU, NEVER ACROSS. A record submitted by another Inspector / Reviewer is not yours to decide, and neither is one from a professor or a directorate post.",
      "⚠ YOU MAY REVIEW A RECORD AND YOU MAY NOT REWRITE IT, AND THE TWO LADDERS ARE NOT THE SAME ONE. Reviewing is “strictly below me”, which at 37 reaches a designer. Editing somebody else's record is that same comparison narrowed to Professor and above, and 37 is below 40 — so the server refuses your edit. The queue still draws an Edit control on the row, because this client does not mirror that second rule, so pressing it is a refusal rather than a hidden button. Review it, send it back with what to fix, and let the person who recorded it correct it — which is what the rule is for.",
      "SEND FOR REVISION NEEDS A COMMENT AND IS THE USEFUL VERDICT. A rejection ends the record; a revision request puts it back in front of the person who recorded it with what to fix. They resubmit by editing it.",
      "BULK APPROVAL EXISTS AND BULK REJECTION DELIBERATELY DOES NOT. A shared note across twenty-five rejections is not feedback, so the screen offers Approve in bulk and nothing else.",
      "THESE ARE REPOSITORY RECORDS, NOT WORKSHOP STAGES. Approving an artisan here does not touch any design & prototype workshop that copied that artisan onto a stage — the stage holds a copy taken when it was saved."
    ]
  }
];

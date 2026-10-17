import {
  Binoculars,
  CalendarRange,
  DraftingCompass,
  FileSignature,
  LayoutDashboard,
  Stamp,
  UserCheck
} from "lucide-react";

import type { GuideStep } from "@/components/guide/steps";

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE DIRECTORATE'S WALKTHROUGH — Assistant Director, Regional Director, Ministry Admin.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A SEPARATE DECK AND NOT AN ARC APPENDED TO `GUIDE_STEPS`, and there are three reasons, each of
 * which would be enough on its own.
 *
 *  1. IT IS A DIFFERENT JOB, NOT A LATER PART OF THE SAME ONE. `GUIDE_STEPS` is one continuous
 *     story — the repository records, then the fortnight of workshop work those records feed, then
 *     the report — and every card in it is something the SAME person does, in that order, over two
 *     weeks. Nothing below is part of that fortnight. A sanction order is signed before anybody
 *     goes anywhere; an annual plan is a directory issued once a year; an oversight assignment is a
 *     posting. Threading them onto the designer's spine would put "upload the ministry's directory
 *     for the year" between "Record artisan" and "Record product" and teach an order nobody works
 *     in.
 *  2. THE DESIGNER'S DECK IS READ BY PEOPLE WHO ARE NOT DESIGNERS, ON PURPOSE, and this one is not
 *     the same bargain. `/guide` is ungated so it can teach the process to somebody who has not
 *     earned the capability yet — a researcher reads the workshop arc, meets a padlock, and at
 *     least knows what the padlock is in front of. That argument works because the arc describes
 *     ONE surface with ONE gate and the cards say so in one repeated sentence. The cards below
 *     have a different gate EACH — four of them non-monotonic in rank, and the ministry dashboard
 *     refusing an ADMIN by name (Workshops I monitor did too, until admins could be appointed to a
 *     post on 2026-10-09). One repeated padlock sentence cannot be written for them, which is why
 *     each card carries its own and why they are not mixed in among cards that share one.
 *  3. ANDROID READS `steps.ts` AS TEXT. `WalkthroughStepsTest.kt` scans from the `GUIDE_STEPS`
 *     declaration to the end of the file for `^    id: "`, and asserts the handset has a step for
 *     every id it finds. A second array appended to that file would therefore be read as fifteen
 *     new subjects the handset must teach — and the handset has no oversight screen at all
 *     (`grep -rl ASSISTANT_DIRECTOR android/app/src/main` finds nothing, which the nav's own
 *     oversight comment records). The parity guard would go red over work Android has not been
 *     asked to do. A separate file is outside the scan by construction.
 *
 * ── THE ARGUMENT IN `steps.ts` THAT THIS FILE HAS TO ANSWER ────────────────────────────────────
 *
 * That file's header declines a guided coach-mark tour, and cost 5 of its five costs is "for most
 * of its audience there would be no DOM to point at … or it would need a PER-ROLE SCRIPT — a
 * second, hand-kept copy of `ROUTE_GUARDS`". This file is a per-role script. So it owes that
 * paragraph an answer rather than a shrug, and the answer is that the two things share a name and
 * not a mechanism:
 *
 *   * A TOUR'S per-role script is a copy of the GATES. It has to know, for each account, which
 *     screens will actually render, because a spotlight anchored to a control on a locked page has
 *     nothing to point at and the tour dead-ends mid-sequence. That is what makes it a second copy
 *     of `ROUTE_GUARDS`, and a copy that is WRONG dead-ends a reader.
 *   * THIS is a copy of nothing. It is prose about a handful of screens, in one file, read by a card
 *     renderer that fetches nothing and anchors to nothing. The role picks which deck opens, and
 *     since 2026-09-16 also which decks may be read at all — `guideTrackFor` and `guideTracksFor` in
 *     `tracks.ts`, over predicates already exported by `lib/permissions.ts`. Nothing here can
 *     dead-end, because nothing here is a sequence through live pages: the worst a wrong answer does
 *     is show a reader prose about somebody else's job.
 *
 *     ⚠ THE CLAUSE THAT USED TO END THAT BULLET IS NOW FALSE AND IS RECORDED RATHER THAN DELETED:
 *     "every deck stays reachable from the switcher afterwards, because the ungated-page argument
 *     above applies in both directions: a designer should be able to read what their Regional
 *     Director is looking at." The owner ruled the other way on 2026-09-16 — only an ADMIN and a
 *     MASTER ADMIN keep the switcher, and this deck is now shown to the three ministry posts and to
 *     nobody else. Reason 2 above is the bullet that feels the change: the designer's deck is still
 *     read by people who are not designers (it is the fallback for seven tiers, the count
 *     `GuideOutro.tsx` states — this said five until 2026-09-16 and was simply wrong), while THIS
 *     deck no longer is, so the "different gates cannot share one padlock sentence" argument protects
 *     the three posts reading it rather than a wider audience. That makes the per-card "who this
 *     screen is for" sentences MORE necessary, not less: two of these screens refuse a Regional
 *     Director who outranks an Assistant Director, and the reader can no longer step out to another
 *     deck to work out why.
 *
 * The part of cost 5 that stands, and is not being argued away: a reader who opens a card below and
 * presses "Open Annual plan" without the tier lands on a lock panel. That is the same bargain the
 * designer's arc already makes, and it is paid the same way — every card says who its screen is
 * for, in its own words, because no two of these gates are the same.
 *
 * ── THE RULES THIS DECK KEEPS, AND THE ONE IT CANNOT ───────────────────────────────────────────
 *
 * `label` IS THE NAV ENTRY'S LABEL, CHARACTER FOR CHARACTER — "Annual plan", "Sanction orders",
 * "Workshop oversight", "Design workshops", "Workshops I monitor" — read off `NAV_ITEMS` in
 * `components/DynamicIslandNav.tsx`. The naming rule in `steps.ts` says the label is the
 * Android-parity feature name; none of these has an Android name to be parity with, and
 * `NAV_ITEMS`' own comment says so where it declares them ("THE LABELS ARE THE WEB OWNER'S AND NOT
 * ANDROID `actionTitle` STRINGS … If the handset grows the screen, ITS name wins"). So the rule is
 * followed to the only authority that exists: the web nav. If Android grows these screens, the nav
 * changes first and this file follows it, not the other way round.
 *
 * `action` IS THE SCREEN'S OWN PRIMARY VERB and not a dashboard tile's, because none of these
 * destinations has a dashboard TILE — the grid is the designer's and is held to Android's
 * `EntryMode` list by two parity tests. `design-workshop-inspection` in `steps.ts` already sets
 * that precedent ("Read a finished workshop"), and the alternative — inventing tile verbs for
 * tiles that do not exist — would put strings in the product that name nothing.
 *
 * `fields` IS THE RULE THIS DECK CANNOT KEEP EVERYWHERE, and the exceptions are named here rather
 * than left to be noticed, because `steps.ts` records that letting a card "quietly join the
 * exception by being easier to paraphrase than to read" is the failure mode. Three of the cards
 * below carry real form labels in screen order and were read off the components:
 *   * Sanction orders — six labelled boxes on one form (`app/(protected)/sanction-orders/page.tsx`),
 *     five of them required, copied with their required marks.
 *   * Annual plan — four filter labels plus the upload dialog's two controls
 *     (`annual-plan/page.tsx`, `annual-plan/UploadPlanDialog.tsx`).
 *    * Ministry dashboard — the two switches and the search box, which are the whole of what that
 *     screen asks for; its downloads are acts and are named in `watch`.
 * Three cannot, and each for a different reason that is worth knowing:
 *   * Workshop oversight is four panels of PICKERS and buttons — its only labelled text boxes are
 *     search boxes — so its rows are the panel headings and the buttons that commit each panel.
 *   * Design workshops is the 22-stage form built from the registry the server publishes; its boxes
 *     are hundreds of labels this file cannot enumerate even in principle. Same exception, same
 *     reason, as `design-workshop-stages` in `steps.ts`.
 *   * Workshops I monitor draws the values of a workshop it did not author, so it has no form and
 *     no labels of its own; its rows are the four summary captions the page prints, plus the
 *     sections. Same shape as `design-workshop-inspection`.
 *
 * ⚠ AND THE ONE CLAIM BELOW THAT IS NOT A SCREEN DESCRIPTION, carried over from `steps.ts` because
 * it is the same system: choosing a record inside a stage COPIES its values onto the stage entry,
 * and the report prints the copy. Never write that a picker shows the linked record or that the
 * report re-reads it — that is a document already in an officer's hands changing under him. Its
 * authority is `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py`.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * The screens the directorate works on: the register first, then the acts in the order the work
 * happens.
 *
 * THE ORDER IS THE LIFECYCLE OF ONE WORKSHOP AS A MINISTRY SEES IT, which is a different order from
 * the designer's: a workshop is PLANNED (the annual directory), then SANCTIONED (the order that
 * opens it and mints the designer's account), then STAFFED (the designer, the two supervising
 * officers, the artisan roster), then FILLED IN, then READ BACK, then SIGNED OFF — approved and
 * handed on to the office. Two of those acts are the two ways a workshop comes into existence at
 * all for these tiers, and they are alternatives rather than a sequence — a Ministry Admin promotes
 * a planned row, an Assistant Director records an order — so they sit adjacent and each says which
 * of the two doors it is.
 *
 * ⚠ NO COUNT IS WRITTEN IN THIS COMMENT OR ANYWHERE A READER SEES, for the reason `steps.ts`
 * spends a paragraph on: the hero and the page header both derive theirs from `steps.length`, and a
 * number typed into prose is correct on the day and silently wrong afterwards.
 */
export const DIRECTORATE_STEPS: GuideStep[] = [
  {
    /*
      FIRST, AND IT IS NOT A STEP IN THE LIFECYCLE THE OTHERS DESCRIBE.

      Everything below this card is something an officer DOES to one workshop, in the order it
      happens. This one is the register of every workshop the others have already been done to,
      so it teaches a reading rather than an act — and it leads because an officer arriving at this
      deck for the first time does not yet know which of the acts is theirs. The dashboard is
      where that question is answered: it names each workshop's designer, its standing and how far
      through the stages it is, so the reader can see the shape of the programme before being taught
      how to move one row of it.

      IT IS ALSO THE ONE SCREEN HERE THAT EVERY TIER IN THIS DECK CAN OPEN. The ones below have
      different gates, several of them non-monotonic in rank — the file header spends a paragraph
      on why that is why this deck exists at all — and leading with the one nobody is refused means a
      reader meets a working screen before they meet a padlock.

      ⚠ `MINISTRY_DESK` in `components/dashboard/ministryDesk.ts` carries the matching row at this
      same index, and `e2e/ministry-desk-unit.spec.ts` asserts the two lists agree href-for-href AND
      label-for-label, in order. Moving this card obliges moving that row in the same edit.
    */
    id: "ministry-dashboard",
    label: "Ministry dashboard",
    action: "Read the programme",
    icon: LayoutDashboard,
    href: "/ministry-dashboard",
    summary:
      "Every workshop and each designer's progress, with downloads.",
    why:
      "See which workshops are moving, stalled or finished. Downloads use the same figures.",
    // Read off `app/(protected)/ministry-dashboard/page.tsx` in screen order. The type switch is two
    // buttons rather than a dropdown, so it is named as the pair it draws; the standing switch is
    // the same shape. The downloads are actions and are named in `watch` rather than here.
    fields: [
      "Type of workshop — Design & prototype workshops, Other workshops",
      "Standing — Everything, Ongoing, Completed, Newly registered",
      "Search (title, workshop code, craft, cluster or place)",
      "Refresh now — beside the line saying when the register was last read"
    ],
    watch: [
      "A Ministry Admin sees every workshop; Assistant and Regional Directors see the ones they're named on.",
      "Ongoing, Completed and Newly registered each group several standings, and the page lists which. A workshop's own standing is the badge on its entry.",
      "Progress is the share of required fields answered — the same figure the designer sees.",
      "A blank progress cell is not 0%. If a workshop's progress couldn't be read, the cell says so in words.",
      "The register refreshes while open; use Refresh now to update immediately.",
      "“Download workshops” saves this table, without stage content.",
      "“Download beneficiaries” is the whole artisan list, not only the workshops shown here. Identity numbers in it show only their last four characters.",
      "Every download says whether it holds the whole list. If it was cut short, the file says so as well as the screen."
    ]
  },
  {
    // SECOND, because a workshop exists on paper before it exists in this product — and because this
    // is the one screen among the acts that an Assistant Director cannot open at all, and a Regional
    // Director opens only for the states assigned to them. Leading the ACTS with it means the tier difference inside the directorate is the
    // first thing the reader meets once the register above has shown them the programme, rather than
    // something they discover at a padlock.
    id: "ministry-annual-plan",
    label: "Annual plan",
    action: "Upload the plan",
    icon: CalendarRange,
    href: "/annual-plan",
    summary:
      "The ministry's directory of the workshops planned for the year — uploaded as one spreadsheet, corrected by uploading it again.",
    why:
      "Planned workshops, not running ones. Correct the plan any time without affecting open workshops.",
    // Read off `app/(protected)/annual-plan/page.tsx` in screen order (the filter row, which is the
    // only labelled form on the page), then the two controls inside `UploadPlanDialog.tsx`, then the
    // one inside `PromoteDialog.tsx`. The three header buttons are named in `watch` rather than
    // here, because they are actions and not things the screen asks you for.
    //
    // THE PROMOTE DIALOG'S PICKER IS LISTED BECAUSE IT IS A BOX THE SCREEN ASKS YOU FOR, and because
    // the deck spent a release telling this reader it did not exist — see the promotion bullet in
    // `watch`. It is `WorkshopDesignerPicker`, whose `FieldBlock` label is the string below; the lead
    // chooser under it has no visible label of its own and appears only from two designers upward,
    // so it is named as a clause rather than as a row of its own.
    fields: [
      "Plan year",
      "Search (Workshop No., title or venue)",
      "Show — Every row, Planned only, Already opened, Withdrawn",
      "Order by — Date, Workshop No., District, Last changed",
      "The plan workbook (.xlsx) — on Upload the plan",
      "“Withdraw the workshops that are in this year's plan and not in this sheet” — a tickbox on the same dialog",
      "Designers this workshop is for — on Open workshop, with the lead chooser under it once two are ticked"
    ],
    watch: [
      "Uploading, opening and withdrawing are for Ministry Admins and admins. Regional Directors see and edit remarks for their states.",
      "Upload the whole sheet each time. Existing entries update; new ones are added. The report lists every change.",
      "The tickbox withdraws entries missing from the sheet; they return if a later sheet lists them. Leave it off for partial corrections.",
      "Open workshop works once per entry. It fills stage 1, and you can name the designers as you open it.",
      "You can also name nobody now and add designers later on Workshop oversight, the next card.",
      "Plan corrections don't change an open workshop — fix it on the workshop itself.",
      "A workshop that has already been opened is never withdrawn, whatever the sheet says."
    ]
  },
  {
    // THIRD, and the other door. It is the one act in this product that creates a person's account
    // as a side effect, so it gets the longest `watch` list in the deck and the sharpest warning in
    // the whole deck sits in it — the clause that refuses the officer who signed the order the
    // right to author the workshop it opened.
    id: "ministry-sanction-order",
    label: "Sanction orders",
    action: "Record sanction order",
    icon: FileSignature,
    href: "/sanction-orders",
    summary:
      "Recording an order opens the workshop and invites its designer.",
    why:
      "Recording an order admits and empanels the designer, creates their account if needed, opens the workshop and issues a sign-in link.",
    // Read off `app/(protected)/sanction-orders/page.tsx` in screen order. `Field required` renders
    // the red asterisk, so "(required)" here is the same fact the box shows.
    //
    // ⚠ RE-READ 2026-09-16, AND TWO OF THESE ROWS NAMED CONTROLS THE SCREEN NO LONGER HAS. The card
    // listed "Designer's name (required)" and "Designer's Gmail ID (required)"; 0.0.12 replaced that
    // single pair with a DESIGNER PICKER plus an optional typed pair for somebody the platform does
    // not know yet, and NEITHER of the typed boxes is `required` any more — the page's own comment
    // on them says so, because the browser cannot express "one of these two" and the submit handler
    // checks it instead. A reader hunting the deck's required "Designer's name" finds no such box,
    // and the two that carry that wording are the not-on-the-list-yet path rather than the ordinary
    // one. The header's two actions are listed last: they are the import half of the screen and a
    // reader planning a sitting needs to know the register can be typed into a workbook.
    fields: [
      "Sanction order number (required)",
      "Sanction date (required)",
      "Sanction amount (₹) (required)",
      "Designers this workshop is for — the picker, with the lead chooser under it once two are ticked",
      "Or a designer who is not on the list yet — their name",
      "Their Gmail ID",
      "Notes",
      "Pro-forma and Upload a sheet — the two buttons in the page header"
    ],
    watch: [
      "Assistant Directors, Regional Directors and Ministry Admins can all record orders and read the register.",
      "New accounts get a sign-in link with a ready message. Send each one now — it's shown once and expires.",
      "Re-issue gives a named designer a new link. For Google users or senior roles, ask an admin on Manage users.",
      "The first designer named is the lead; their profile fills stages 1 and 3 and appears on the report.",
      "Download the pro-forma, then upload. Nothing is recorded until you confirm.",
      "Copy every sign-in link before leaving the page; use Re-issue for any you miss.",
      "If a designer already has an account, no link is needed. They sign in as usual and find the new workshop on their list.",
      "You can read and report on workshops your own orders opened, but not save their stages.",
      "You can't name yourself as a designer on an order you record.",
      "Recorded orders stay on the register. If one is wrong, record the correct order.",
      "The workshop opens with a placeholder title. The designer fills in Workshop Setup; the entry shows what's missing."
    ]
  },
  {
    // FOURTH: the workshop exists and nobody is on it. This card is where the second refusal inside
    // the directorate lives — a Regional Director is refused here and OUTRANKS an Assistant
    // Director they might otherwise be naming — so it is stated first in `watch` rather than left
    // to be met at a padlock.
    id: "ministry-oversight",
    label: "Workshop oversight",
    action: "Name the designer and the officers",
    icon: UserCheck,
    href: "/officers",
    summary:
      "One workshop at a time: who it is for, who supervises and inspects it, and who is on its artisan list.",
    why:
      "Work starts once a designer is named; supervision once both directors are named.",
    // NOT the real form labels: this screen's only labelled text boxes are search boxes, and
    // everything that changes anything is a picker with a button under it. So the rows are the FIVE
    // panel headings and the control that commits each — every one of them a thing a reader can
    // point at on screen. See the header's note on which cards may take this exception and why.
    //
    // ⚠ RE-READ OFF THE PAGE 2026-09-16, AND THREE OF THESE NAMED BUTTONS THAT NO LONGER EXIST.
    // 0.0.12 replaced the add-only lists with whole-set pickers and an explicit Save, and added the
    // inspectors panel to this screen: “Name as designer”, “Name as Assistant Director” and
    // “Name as Regional Director” are gone, and the two officer slots are now dropdowns whose
    // unassign is a ROW inside the control rather than a button beside it.
    fields: [
      "Workshop — the picker at the top; everything below is about the one you chose",
      "Designers this workshop is for → “Save who this workshop is for”",
      "Assistant Director and Regional Director — a dropdown each, saved the moment you choose, with “Nobody is assigned” as a row in the list",
      "Inspection of a design workshop → “Save who inspects this”",
      "Artisan list → “Download the pro-forma”, then “Filled-in artisan list” (.xlsx, up to 4 MB)",
      "Earlier uploads — every artisan list this workshop has had, with what each one did"
    ],
    watch: [
      "Only a Ministry Admin, an Admin or the Master Admin can name a workshop's designers, officers and inspectors, or upload its artisan list.",
      "Nobody names themselves, so you won't appear in your own pickers. A Ministry Admin, an Admin or the Master Admin can be named as an officer or inspector by someone else.",
      "One post per person per workshop, and no one supervises or inspects a workshop they designed.",
      "While you hold a post on a workshop it's read-only to you. Ask whoever appointed you to remove you if you need to edit it.",
      "Naming a designer gives access and copies their profile into stages 1 and 3. Ask them to complete their profile first.",
      "An Assistant Director or Regional Director reads every stage of the workshop on Workshops I monitor, but can't edit it.",
      "The filled pro-forma contains Aadhaar numbers. Don't email or share it; delete it after uploading. The file isn't stored.",
      "Existing artisans are linked, not duplicated. The upload report shows how many of each."
    ]
  },
  {
    // FIFTH: the write. This is the card the whole deck exists to make honest, because the
    // capability is three days old on this ladder and nothing anywhere else tells these three tiers
    // they have it — `DESIGN_WORKSHOP_ROLES` gained them on 2026-09-14 and the nav entry, the route
    // guard and the tile all simply started appearing.
    id: "ministry-workshop",
    label: "Design workshops",
    action: "Fill in the stages",
    icon: DraftingCompass,
    href: "/design-workshops",
    summary:
      "The workshops you can open, and the 22-stage form inside each one. You can fill these in, not only read them.",
    why:
      "Ministry posts can fill in stages, add sections, record consent and generate the report.",
    // The registry exception, identical to `design-workshop-stages` in `steps.ts`: the stage form is
    // built from the schema the server publishes, so its boxes are hundreds of labels across 22
    // stages and are not this file's to copy. These rows name the parts of the screen instead.
    fields: [
      "The workshop list — everything you may open, searchable",
      "Workshop Setup and the other 21 stages, each with its own required-field count",
      "The stage form's boxes — they differ per stage and per craft",
      "Record pickers inside a stage (artisan, product, process, tool)",
      "The report, generated from the stages"
    ],
    watch: [
      "Professors and Inspector / Reviewers don't fill in workshops. An inspector reviews the stages instead.",
      "Workshops start from the annual plan or a sanction order. “New workshop” is for admins.",
      "You can't save stages of a workshop your own order opened.",
      // WHO IS TOLD, AND WHERE, DIFFERS BY TIER — and this said "the page says so above the stages" to
      // all three until 2026-10-09. The notice above the stages asks the staffing reads, which are the
      // appointers' (`readHeldWorkshopPosts`): a Ministry Admin sees it, an Assistant or Regional
      // Director never does and learns it from the refused save — and, on a workshop their own
      // sanction order opened, from the sanction-order sentence, which the server checks first.
      "Workshops you supervise are on Workshops I monitor, not here.",
      "A workshop you supervise or inspect is read-only to you here. As a Ministry Admin, you'll see a note saying so above the stages.",
      "Choosing a record copies its values; the report prints that copy. Later edits to the record don't change the stage."
    ]
  },
  {
    // LAST, because it is the read-back: the fortnight has happened and an officer is looking at
    // what came of it. It carried, until 2026-10-09, the one fact in this deck that read as a defect
    // and was not — a Ministry Admin passed this screen's gate and could never have a row on it. The
    // owner's ruling that day lets a Ministry Admin, an admin and the master admin be NAMED in either
    // post by somebody else, so the screen opens for all three and is scoped to their rows.
    id: "ministry-monitored",
    label: "Workshops I monitor",
    action: "Read a workshop you supervise",
    icon: Binoculars,
    href: "/officers/monitored",
    summary:
      "The workshops you were named on as Assistant Director or Regional Director: every stage to read, nothing to edit.",
    why:
      "Every value shows who wrote it.",
    // No form and no labels of its own — it draws a workshop it did not author. These are the four
    // summary captions the page prints above the stages, plus the two sections below them. Same
    // exception, same reason, as `design-workshop-inspection` in `steps.ts`.
    fields: [
      "Dates",
      "Designer",
      "Venue",
      "Required fields answered — a percentage across every stage",
      "Who supervises this workshop",
      "Files — the workshop's photographs, recordings and attachments",
      "All 22 stages, read-only, with who wrote each field and the answers to the workshop's own questions"
    ],
    watch: [
      "Ministry Admins and admins named to these posts see those workshops here too.",
      "If you haven't been named on any workshop yet, the page says so. If the list couldn't be loaded, it says that instead.",
      "You can open the photographs, recordings, videos and attachments, but not change them. If a file stops opening, select Refresh files.",
      "Read-only. Ask the designers to fix errors. Approval is on Reports to approve."
    ]
  },
  {
    // LAST, because it is the end of the sequence: the report has been filled in, inspected and read
    // back, and the Ministry Admin signs it off. `MINISTRY_DESK` carries the matching row at this
    // same index and `e2e/ministry-desk-unit.spec.ts` holds the two lists to each other.
    //
    // An Assistant or Regional Director reading this deck meets the screen's lock panel if they press
    // the button — the bargain every card in this deck makes — so the card says plainly whose it is.
    id: "ministry-sign-off",
    label: "Reports to approve",
    action: "Approve a report and hand it on",
    icon: Stamp,
    href: "/design-workshop-approvals",
    summary:
      "Reports handed in by designers, waiting for your decision.",
    why:
      "Inspectors ask for corrections; the Ministry Admin signs the report off and sends it on.",
    // The screen's own controls, in screen order: the four lists, then the report and the register it
    // is read with, then what each decision asks for.
    fields: [
      "Waiting for approval · Approved, not yet handed on · Handed on · Returned — the four lists",
      "The report, stage by stage, with who wrote each field",
      "What the officers asked for, round by round, and every decision taken on the report",
      "Approve — with a note, if you want one on the record",
      "What needs correcting (required) — when you send a report back, withdraw an approval or return one from the office",
      "Hand on to the office — the office, a note, and the exported report file that went"
    ],
    watch: [
      "YOU CAN'T DECIDE ON A REPORT YOU WORKED ON OR INSPECT. The decisions are switched off with the reason.",
      "APPROVED REPORTS ARE LOCKED. To correct one, withdraw the approval with a reason.",
      "IF A REPORT CHANGES WHILE YOU READ IT, approval is refused — reload and read it again.",
      "HANDING ON IS RECORDED, NOT SENT. Download the report and send it to the office yourself.",
      "A REPORT HANDED ON CAN STILL BE RETURNED, with a reason, if the office sends it back. The approval and the hand-on before it stay on the record."
    ]
  }
];

import { Binoculars, CalendarRange, DraftingCompass, FileSignature, UserCheck } from "lucide-react";

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
 *     ONE surface with ONE gate and the cards say so in one repeated sentence. The five cards below
 *     have five different gates, three of which are non-monotonic in rank, and two of which refuse
 *     an ADMIN by name. One repeated padlock sentence cannot be written for them, which is why each
 *     card carries its own and why they are not mixed in among cards that share one.
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
 *   * THIS is a copy of nothing. It is prose about five screens, in one file, read by a card
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
 *     screen is for" sentences MORE necessary, not less: two of these five screens refuse a Regional
 *     Director who outranks an Assistant Director, and the reader can no longer step out to another
 *     deck to work out why.
 *
 * The part of cost 5 that stands, and is not being argued away: a reader who opens a card below and
 * presses "Open Annual plan" without the tier lands on a lock panel. That is the same bargain the
 * designer's arc already makes, and it is paid the same way — every card says who its screen is
 * for, in its own words, because no two of these five gates are the same.
 *
 * ── THE RULES THIS DECK KEEPS, AND THE ONE IT CANNOT ───────────────────────────────────────────
 *
 * `label` IS THE NAV ENTRY'S LABEL, CHARACTER FOR CHARACTER — "Annual plan", "Sanction orders",
 * "Workshop oversight", "Design workshops", "Workshops I monitor" — read off `NAV_ITEMS` in
 * `components/DynamicIslandNav.tsx`. The naming rule in `steps.ts` says the label is the
 * Android-parity feature name; four of these five have no Android name to be parity with, and
 * `NAV_ITEMS`' own comment says so where it declares them ("THE LABELS ARE THE WEB OWNER'S AND NOT
 * ANDROID `actionTitle` STRINGS … If the handset grows the screen, ITS name wins"). So the rule is
 * followed to the only authority that exists: the web nav. If Android grows these screens, the nav
 * changes first and this file follows it, not the other way round.
 *
 * `action` IS THE SCREEN'S OWN PRIMARY VERB and not a dashboard tile's, because none of these five
 * destinations has a dashboard TILE — the grid is the designer's and is held to Android's
 * `EntryMode` list by two parity tests. `design-workshop-inspection` in `steps.ts` already sets
 * that precedent ("Read a finished workshop"), and the alternative — inventing tile verbs for
 * tiles that do not exist — would put five strings in the product that name nothing.
 *
 * `fields` IS THE RULE THIS DECK CANNOT KEEP EVERYWHERE, and the exceptions are named here rather
 * than left to be noticed, because `steps.ts` records that letting a card "quietly join the
 * exception by being easier to paraphrase than to read" is the failure mode. Two of the five below
 * carry real form labels in screen order and were read off the components:
 *   * Sanction orders — six labelled boxes on one form (`app/(protected)/sanction-orders/page.tsx`),
 *     five of them required, copied with their required marks.
 *   * Annual plan — four filter labels plus the upload dialog's two controls
 *     (`annual-plan/page.tsx`, `annual-plan/UploadPlanDialog.tsx`).
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
 * The five screens the directorate works on, in the order the work happens.
 *
 * THE ORDER IS THE LIFECYCLE OF ONE WORKSHOP AS A MINISTRY SEES IT, which is a different order from
 * the designer's: a workshop is PLANNED (the annual directory), then SANCTIONED (the order that
 * opens it and mints the designer's account), then STAFFED (the designer, the two supervising
 * officers, the artisan roster), then FILLED IN, then READ BACK. Two of those five steps are the
 * two ways a workshop comes into existence at all for these tiers, and they are alternatives rather
 * than a sequence — a Ministry Admin promotes a planned row, an Assistant Director records an order
 * — so they sit adjacent and each says which of the two doors it is.
 *
 * ⚠ NO COUNT IS WRITTEN IN THIS COMMENT OR ANYWHERE A READER SEES, for the reason `steps.ts`
 * spends a paragraph on: the hero and the page header both derive theirs from `steps.length`, and a
 * number typed into prose is correct on the day and silently wrong afterwards.
 */
export const DIRECTORATE_STEPS: GuideStep[] = [
  {
    // FIRST, because a workshop exists on paper before it exists in this product — and because this
    // is the one screen of the five that a Regional Director and an Assistant Director cannot open
    // at all. Leading with it means the tier difference inside the directorate is the first thing
    // the reader meets rather than something they discover at a padlock.
    id: "ministry-annual-plan",
    label: "Annual plan",
    action: "Upload the plan",
    icon: CalendarRange,
    href: "/annual-plan",
    summary:
      "The ministry's directory of the workshops planned for the year — uploaded as one spreadsheet, corrected by uploading it again.",
    why:
      "A row here is a plan and not a workshop: it carries the number, the date, the state, the district and the venue, and nothing in this product acts on it until somebody opens it. That distinction is the whole value of the screen. It lets the directory be corrected all year — a venue moves, a district is re-cut, a date slips — without any of those corrections reaching a workshop that is already running, and it gives a Ministry Admin one place to answer \"is this one planned, opened, or withdrawn?\" for the whole year at once.",
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
      "THIS SCREEN IS THE MINISTRY ADMIN'S ALONE, and it is the only one of the five that is a rank floor rather than a set: an Assistant Director and a Regional Director are both below it and are refused. The reason is written into the server (`can_manage_annual_plan`) and is worth knowing rather than resenting — the plan is a national instrument and the table carries no per-region column, so the change that let a Regional Director correct their own state's rows would hand them the whole directory. If regional editing is ever wanted it is a scope table, not a promotion.",
      "Upload the WHOLE sheet every time. Rows already in the plan are corrected, new rows are added, and uploading the same sheet twice changes nothing at all — that is how you confirm an earlier upload landed. The report afterwards lists every field it changed, from what to what.",
      "The tickbox is the destructive-looking one and it is not destructive. Ticking it marks every planned row the sheet does not mention as withdrawn; nothing is deleted, and a withdrawn row comes back the moment a later sheet names it. Leave it unticked when the sheet is a partial correction.",
      "OPENING A WORKSHOP FROM A ROW CAN ONLY BE DONE ONCE, AND IT MAY NAME THE DESIGNERS AS IT OPENS. Everything in the row is copied onto the new workshop and into its stage 1, and the dialog carries a designer picker: everybody you name is given access to the workshop as it is created, and the one marked lead has their designer profile copied into stage 1 and stage 3. Naming nobody is still a real answer and not a failure — the designer block of stage 1 is then left empty, which is the right empty, and designers are added afterwards.",
      "“AFTERWARDS” IS Workshop oversight, WHICH IS THE NEXT CARD, AND NOT THE WORKSHOP'S OWN SCREEN. Adding a designer to a workshop that is already open is the Designers panel on that screen. The one on the workshop itself — “Designers on a workshop” — is an admin's, and a Ministry Admin is redirected away from it, so the promotion is the one moment you can seed stage 1 with the lead's profile without leaving the annual plan. That is why the picker is worth using rather than skipping.",
      "AFTER A ROW IS OPENED, CORRECTING THE PLAN CORRECTS THE DIRECTORY AND NOTHING ELSE. The workshop is not touched, and the screen says so twice — once in the toast, once in an amber banner on the upload report naming the rows this happened to. If the venue on a running workshop is wrong, it is wrong on the workshop, and that is where it is fixed.",
      "A workshop that has already been opened is never withdrawn, whatever the sheet says."
    ]
  },
  {
    // SECOND, and the other door. It is the one act in this product that creates a person's account
    // as a side effect, so it gets the longest `watch` list of the five and the sharpest warning in
    // the whole deck sits in it — the clause that refuses the officer who signed the order the
    // right to author the workshop it opened.
    id: "ministry-sanction-order",
    label: "Sanction orders",
    action: "Record sanction order",
    icon: FileSignature,
    href: "/sanction-orders",
    summary:
      "The ministry's sanction register. Recording an order opens the workshop, creates the designer's account and issues their sign-in link, in one act.",
    why:
      "Everything else in this product assumes the designer is already here. A sanction order is the moment they are not: a name and a Gmail address on a signed document, and no account, no empanelment and no workshop anywhere in the system. Recording the order does all of it at once and in one transaction — the address is admitted to the platform allow-list, the designer is empanelled, an account is created if that mailbox has none, a workshop is opened in their name, and a one-time sign-in link is minted. Doing those five by hand on five screens is five chances to do four of them.",
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
      "ASSISTANT DIRECTOR AND ABOVE, AND THIS IS THE ONE PLACE IN THE DESIGN-WORKSHOP FAMILY THAT REALLY IS A RANK FLOOR. All three directorate tiers can record an order. Reading the register is the same gate as writing it — every route on it, the list included, is behind the same dependency, because the register is a list of named people and the amounts sanctioned against them.",
      "NOTHING IS EMAILED, BY ANYBODY, EVER. This product has no mail sender. One link appears on screen per account the order created, together with a message you can copy, and you send each one yourself by whatever you already use. A link works once and it expires — the screen prints the exact moment. Send them while they are on screen: nothing can show a link again, because the server keeps only a digest of it.",
      "RE-ISSUING FROM THE ROW IS THE LEAD'S LINK AND ONLY THE LEAD'S. The button on the register re-mints for the first designer named on the order, which is the one the register holds. There is no route in this product that can re-issue a CO-DESIGNER'S first link, so if you lose one of those the remedy is an administrator on Users, not this screen. The button is drawn only on an order that actually created an account: one naming somebody who was already here issued nothing, so there is nothing to re-issue.",
      "AN ORDER MAY NAME SEVERAL DESIGNERS, AND THE FIRST IS THE LEAD. Everybody named gets the same five things — the allow-list admission, the empanelment, an account if that mailbox has none, a profile and access to the workshop — but only ONE name reaches the document: the lead's profile is what is copied into stage 1 and stage 3, and the lead is whose name the report carries. The picker prints who that is, and lets you change it, from two designers upward.",
      "A SHEET RECORDS NOTHING UNTIL YOU CONFIRM IT. “Pro-forma” downloads the blank workbook to type the office's orders into; “Upload a sheet” reads one back and shows you every row it found and every row it could not, and not one order exists until you press the confirm on that review. Correct the sheet and upload it again as often as you like before then — nothing has happened yet.",
      "AN IMPORT ISSUES NO SIGN-IN LINKS AT ALL, and this is the one cost of doing it by sheet. Two hundred one-time credentials on one screen is a screen whose accidental closure strands two hundred designers, so the import throws them away: every imported designer whose account was newly created needs their link re-issued by hand from their row.",
      "If that Gmail address already has an account, no link is issued at all and none is needed: they sign in as they always do and the new workshop is simply on their list. The screen says so instead of showing you a link that would not work.",
      "⚠ YOU CANNOT AUTHOR THE WORKSHOP YOUR OWN ORDER OPENED. You may read every stage of it and generate its report, and the moment you try to SAVE one the server answers 403 by name: \"You recorded the sanction order that opened this workshop, so you cannot also author it.\" It is a test on the rows and not on the tier — it fires only where the same account both recorded the order and opened the workshop — so a Regional Director filling in a workshop somebody else sanctioned is untouched. The work belongs to the designer the order names.",
      "YOU CANNOT NAME YOURSELF AS THE DESIGNER. It is refused before anything at all is written — it is the first of the checks, because it is the only one that needs no lookup — so nothing is half-created when it fires.",
      "A RECORDED ORDER CANNOT BE DELETED. There is no delete route on the register, at all: a mistaken order is corrected by recording the correct one and leaving the record of what happened intact.",
      "The workshop opens as a placeholder titled after the order, and the starred fields on Workshop Setup — State, District, Craft, Cluster, Venue, the dates — are the designer's to fill in. The row tells you which are still missing, so you can chase the right ones."
    ]
  },
  {
    // THIRD: the workshop exists and nobody is on it. This card is where the second refusal inside
    // the directorate lives — a Regional Director is refused here and OUTRANKS an Assistant
    // Director they might otherwise be naming — so it is stated first in `watch` rather than left
    // to be met at a padlock.
    id: "ministry-oversight",
    label: "Workshop oversight",
    action: "Name the designer and the officers",
    icon: UserCheck,
    href: "/officers",
    summary:
      "One workshop at a time: who it is for, who supervises it, and who is on its artisan roster.",
    why:
      "A workshop with no designer named on it cannot be opened by anybody and its stage 1 and stage 3 start empty; a workshop with no Assistant Director and no Regional Director is nobody's to read back. Those three postings are what turn an opened workshop into one somebody is accountable for, and they are deliberately not the designer's to make — nobody chooses who supervises their own work. The artisan roster is on the same screen because it is the same act of staffing: it is the list of people the fortnight is for.",
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
      "A REGIONAL DIRECTOR IS REFUSED THIS SCREEN, AND THEY OUTRANK AN ASSISTANT DIRECTOR. Every rank instinct is wrong about it, which is why the rule is a set and not a threshold: the supervised must not choose the supervisor. Naming officers is a Ministry Admin's act, or an admin's. A Regional Director who genuinely needs to assign is a Ministry Admin, which is a role change on Users — not a widening here.",
      "A MINISTRY ADMIN CANNOT BE NAMED IN EITHER SLOT, and the picker draws them greyed with the reason on the row: they supervise the scheme rather than one workshop. The server refuses it too, so this is a rule and not a UI preference.",
      "NAMING A DESIGNER IS ALSO GIVING THEM ACCESS. It copies their profile — name, institution, biography, experience, contact details — into stages 1 and 3, and overwrites nothing else those stages already hold. A designer with no profile of their own leaves those boxes empty rather than borrowing yours, so it is worth asking them to fill their profile in first.",
      "AN OVERSIGHT ROW IS NOT ACCESS TO THE WORKSHOP. The two officers can READ every stage and save nothing — they read it on Workshops I monitor, which is a different screen from the one the designers use.",
      "⚠ THE ARTISAN PRO-FORMA CARRIES AADHAAR NUMBERS, which are regulated personal data. Do not email the filled-in file and do not leave it in a shared folder; delete it once the upload is confirmed. The workbook itself is never stored here — only its name, the counts, and the rows that could not be read.",
      "NOBODY IS CREATED TWICE. An artisan already in the repository is linked to this workshop and their existing record is left exactly as it is — INCLUDING anywhere the spreadsheet disagreed with it. The upload report says how many were created and how many were linked, so the two are never confused."
    ]
  },
  {
    // FOURTH: the write. This is the card the whole deck exists to make honest, because the
    // capability is three days old on this ladder and nothing anywhere else tells these three tiers
    // they have it — `DESIGN_WORKSHOP_ROLES` gained them on 2026-09-14 and the nav entry, the route
    // guard and the tile all simply started appearing.
    id: "ministry-workshop",
    label: "Design workshops",
    action: "Fill in the stages",
    icon: DraftingCompass,
    href: "/design-workshops",
    summary:
      "The workshops you can open, and the 22-stage form inside each one. You write in these, not only read them.",
    why:
      "You were given this because the alternative was absurd: a Ministry Admin could open a workshop from the annual plan and then not save a single stage in the workshop they had just created. So all three directorate posts are inside the set that runs a workshop — the stage saves, the custom sections, the capture aids, the consent record and the report are all open to you, exactly as they are to the designer. What it does NOT make you is the designer: the fortnight is theirs, and most of what you do here is finishing, correcting or standing in.",
    // The registry exception, identical to `design-workshop-stages` in `steps.ts`: the stage form is
    // built from the schema the server publishes, so its boxes are hundreds of labels across 22
    // stages and are not this file's to copy. These rows name the parts of the screen instead.
    fields: [
      "The workshop list — everything you may open, searchable",
      "Workshop Setup and the other 21 stages, each with its own required-field count",
      "The stage form's boxes, which the server publishes — they differ per stage and per craft",
      "Record pickers inside a stage (artisan, product, process, tool)",
      "The report, generated from the stages"
    ],
    watch: [
      "THIS IS SET MEMBERSHIP AND NOT A RANK, and the ladder gives the wrong answer for it every time. A PROFESSOR sits below all three of your tiers and is still refused, because being senior to a designer is not being one; an INSPECTOR is refused for a sharper reason — they would be authoring the stages they later review.",
      "YOU CANNOT START A BARE WORKSHOP. Creating one from nothing is an admin's and the master admin's. Your two doors are the two cards above this one: a Ministry Admin opens a planned row on Annual plan, an Assistant Director or Regional Director records a sanction order. Both create a real workshop; neither is the “New workshop” button, and you will not see that button.",
      "⚠ IF YOU RECORDED THE SANCTION ORDER THAT OPENED A WORKSHOP, YOU CANNOT SAVE A STAGE IN IT. The workshop is on your list, every stage opens, the report generates — and the first save answers 403 with that sentence. Read the Sanction orders card above for the whole rule; the short version is that signing for the work and doing the work are two people.",
      "BEING NAMED AS A WORKSHOP'S ASSISTANT DIRECTOR OR REGIONAL DIRECTOR DOES NOT PUT IT HERE. An oversight assignment is a reading posting and lives on Workshops I monitor. You reach a workshop here by having created it, or by being added to it on the workshop's own screen.",
      "CHOOSING A RECORD IN A STAGE COPIES ITS VALUES ONTO THE STAGE, and the report prints that copy. Correcting the artisan record next week does not change a report generated last month — which is the point, and the reason a correction has to be made on the stage as well if the document has already gone."
    ]
  },
  {
    // LAST, because it is the read-back: the fortnight has happened and an officer is looking at
    // what came of it. It also carries the one fact in this deck that reads as a defect and is not
    // — a Ministry Admin passes this screen's gate and can never have a row on it.
    id: "ministry-monitored",
    label: "Workshops I monitor",
    action: "Read a workshop you supervise",
    icon: Binoculars,
    href: "/officers/monitored",
    summary:
      "The workshops you were named on as Assistant Director or Regional Director: every stage readable, none of it editable.",
    why:
      "Supervising is reading somebody else's work and being able to say what is in it. That is a different act from authoring, and it needs a different screen rather than the same screen with the buttons hidden — a form with disabled controls invites the reading that the values are yours to fix, and they are not. So this one has no Save anywhere on it, and it draws the same authorship line under every value that the designer's own stage form draws under every box, because the supervisor and the supervised must never be reading two different accounts of who wrote what.",
    // No form and no labels of its own — it draws a workshop it did not author. These are the four
    // summary captions the page prints above the stages, plus the two sections below them. Same
    // exception, same reason, as `design-workshop-inspection` in `steps.ts`.
    fields: [
      "Dates",
      "Designer",
      "Venue",
      "Required fields answered — a percentage across every stage",
      "Who supervises this workshop",
      "All 22 stages, read-only, with who wrote each field"
    ],
    watch: [
      "AN ADMIN IS REFUSED THIS SCREEN BY NAME, and that is the server's rule rather than this client narrowing one. The argument is worth knowing because it looks like an oversight: an admin scoped to their OWN oversight rows sees an empty page and reads it as a broken feature, and an admin scoped to “everything, because they are an admin” turns this into a second full read of every workshop in the archive. Admins read design & prototype workshops on Design workshops.",
      "⚠ A MINISTRY ADMIN OPENS THIS SCREEN AND WILL NEVER HAVE A ROW ON IT. The gate admits all three ministry posts, and the rows are oversight assignments — and a Ministry Admin cannot be named as a workshop's Assistant Director or Regional Director, which is the rule on the Workshop oversight card above. So the page is permanently the empty state for that tier. It is correct, it is not a fault, and it is written here because nothing on the screen itself can tell you that your empty page is structural rather than “nobody has assigned you yet”.",
      "AN EMPTY PAGE IS A REAL ANSWER AND THE SCREEN SAYS WHICH KIND IT IS. Nothing assigned reads “No workshop is assigned to you”; a list that could not be read says so instead and keeps whatever was already on screen — because a correct empty state and a silent failure look identical, and there is no other surface here to cross-check against.",
      "PHOTOGRAPHS, RECORDINGS AND ATTACHMENTS ARE COUNTED, NOT SHOWN — “3 files recorded here”. An empty gallery would look like a file that failed to load, which is not what happened.",
      "THERE IS NO SAVE, NO SUBMIT AND NO DELETE ON THIS PAGE, and none of them is missing: there is no route behind it that would accept one. If a stage is wrong, the people who can change it are its designers."
    ]
  }
];

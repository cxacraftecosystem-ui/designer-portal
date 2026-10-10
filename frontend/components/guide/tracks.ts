import {
  Activity,
  ClipboardCheck,
  Compass,
  DraftingCompass,
  Eye,
  FileSearch,
  FileSignature,
  LayoutGrid,
  ListOrdered,
  MessageSquare,
  Search,
  Share2,
  ShieldCheck,
  UserCheck,
  UserCog,
  type LucideIcon
} from "lucide-react";

import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import { INSPECTOR_STEPS } from "@/components/guide/inspectorSteps";
import { GUIDE_STEPS, type GuideStep } from "@/components/guide/steps";
import { isAdmin, isDirectorateTier, isInspectorTier } from "@/lib/permissions";
import type { User } from "@/lib/types";

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THREE WALKTHROUGHS, AND THE RULE THAT DECIDES WHICH ONE OPENS.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `/guide` taught one job to one audience for as long as it existed: a designer's fortnight, twenty-
 * two cards, from the first artisan record to the report a ministry officer receives. Two other
 * audiences have since been given surfaces of their own and were handed that deck anyway —
 *
 *   * the three DIRECTORATE posts (Assistant Director, Regional Director, Ministry Admin), who work
 *     an annual plan, a sanction register and an oversight screen that no card in that deck names,
 *     and who gained the right to WRITE inside a workshop on 2026-09-14 with nothing anywhere
 *     telling them so;
 *   * the INSPECTOR / REVIEWER tier, whose entire surface is one card at the very end of the
 *     designer's deck, written — correctly, and it says so — to tell a DESIGNER what a colleague is
 *     looking at.
 *
 * ── WHAT THIS MODULE IS, AND WHAT IT BECAME ON 2026-09-16 ──────────────────────────────────────
 *
 * It is two selections over three predicates that `lib/permissions.ts` already exports, plus the
 * copy each deck needs for the three bands the page is made of. THERE ARE NOW TWO ANSWERS AND THEY
 * ARE DIFFERENT KINDS OF ANSWER, which is the single fact to carry out of this header:
 *
 *   * `guideTrackFor(user)` — WHICH DECK OPENS. Still a default. A wrong answer costs one click for
 *     the accounts that can still click, and is invisible for the accounts that cannot.
 *   * `guideTracksFor(user)` — WHICH DECKS MAY BE READ AT ALL. A GATE. Everybody except an ADMIN and
 *     a MASTER ADMIN now gets exactly one deck — the one their role owns — and the switcher is not
 *     drawn for them. A wrong answer here takes a walkthrough away from somebody.
 *
 * ⚠ THIS PARAGRAPH SAID THE OPPOSITE UNTIL 2026-09-16, and the reversal is a product ruling rather
 * than a bug fix, so the old argument is recorded rather than deleted: "It is NOT a gate. `/guide`
 * is still ungated, all three decks are still in the bundle for everybody, and the switcher on the
 * page still reaches all three — so the role picks which deck OPENS and takes nothing away from
 * anybody." The repo owner ruled on 2026-09-16 that "except for admins and master admins, the
 * walkthrough that the people get to see should be the ones that are relevant to their roles". The
 * ungated-teaching-surface argument is not refuted by that ruling; it is overruled by it, and the
 * part of it that survives is the part about the PAGE: `/guide` stays out of `ROUTE_GUARDS` and the
 * nav entry stays `can: everyone`. What is scoped is WHICH DECK, never whether the page opens.
 *
 * ⚠ AND THE BUNDLE IS NOT THE GATE. All three decks are still statically imported here, so all three
 * still reach every viewer's JavaScript chunk; the scoping decides what is PRESENTED, not what is
 * obtainable by somebody with devtools open. That is accepted deliberately and not overlooked — the
 * decks are documentation prose (no artisan data, no Aadhaar, no workshop contents) and the screen
 * names in them are already public in `NAV_ITEMS`, in `ROUTE_GUARDS`' refusal copy and in `docs/`.
 * The requirement is "must not be presented", not "must not be obtainable". If that ever inverts it
 * becomes a `next/dynamic` split per deck plus a server-side decision, which is a materially larger
 * change and must not be assumed from this comment.
 *
 * The OPENS/MAY-READ distinction is still the whole answer to the objection `steps.ts` raises
 * against a per-role script, and each new deck's header answers it at greater length. The short
 * form: a coach-mark tour's per-role script has to be a second copy of `ROUTE_GUARDS`, because it
 * anchors to live DOM and dead-ends when a page will not render. Nothing here anchors to anything,
 * and nothing here can dead-end — the worst a wrong answer does is show somebody the wrong prose.
 *
 * ── WHY THE PAGE-LEVEL COPY LIVES HERE AND NOT IN THE COMPONENTS ANY MORE ───────────────────────
 *
 * `GuideHero` and `GuideOutro` each carried their copy as literals, and most of it is FALSE of the
 * other two audiences rather than merely ill-fitting. The hero promised "the repository records
 * first, then the 22-stage design & prototype workshop they feed"; the outro's checklist opens
 * "Every artisan you spoke to has a record" and is headed "Before you leave the field". An inspector
 * does not go to the field. So every sentence that differs per deck is a field on [GuideTrack], and
 * the components take it as a prop — which is also what stops the next deck being added by copying a
 * component.
 *
 * ⚠ ONE SENTENCE MOVED HERE BECAUSE IT WAS A CLAIM ABOUT ANDROID AND IS ONLY TRUE OF ONE DECK. The
 * outro said, of the whole process, "It is the same order on the web and in the Android app, and the
 * screens carry the same names in both." Checked against the tree rather than assumed:
 * `grep -rn "ASSISTANT_DIRECTOR" android/app/src/main --include=*.kt` finds the rank and the label
 * and NOTHING ELSE — no annual plan, no sanction register, no oversight screen, no "Workshops I
 * monitor".
 *
 * ⚠ AND THE CLAUSE THAT USED TO END THIS PARAGRAPH WAS ITSELF WRONG THE SAME WAY, which is worth
 * leaving on the record rather than silently deleting. It read "`InspectionDetailScreen.kt` has no
 * feedback box, so the handset can read the suggestions on a workshop and cannot file one." The
 * second half is right; THE FIRST HALF IS NOT. `DwInspectionDetailDto` declares
 * `inspectionFeedback`, which is what makes it plausible — but
 * `grep -rn "inspectionFeedback" android/app/src/main --include=*.kt` finds that declaration, its
 * twin in `StageSchema.kt`, and a mention in a comment. NO SCREEN READS IT. A field on the wire is
 * not a screen, and only a screen is a thing a reader can open. `INSPECTOR_TRACK.recapLead` was
 * corrected for exactly this and this paragraph was not, so the file said both things at once for a
 * day. Repeating that sentence over the two new decks
 * would send an officer hunting a handset for screens that were never built, which is the failure
 * `WalkthroughSteps.kt` states as "worse than a missing step". Each deck now says what is true of
 * its own screens, and only that.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

/** A link out of the closing band. Shape unchanged from the outro's own literal. */
export type GuideExit = {
  label: string;
  href: string;
  icon: LucideIcon;
  note: string;
};

/**
 * One walkthrough: the deck, and every sentence on the page that differs between decks.
 *
 * EVERY FIELD HERE WAS A HARDCODED LITERAL IN A COMPONENT BEFORE THIS TYPE EXISTED. Nothing was
 * invented to fill the shape out, and nothing that is genuinely shared was pulled in — the spine,
 * the rail, the card, the accordion, the motion vocabulary and the "Where to go next" chrome are all
 * still the components' own, because they do not differ by audience.
 */
export type GuideTrack = {
  /** Stable id. Appears in the switcher's markup and in this module's own tests; never in a URL. */
  id: string;
  /** The switcher's button label. */
  name: string;
  /** One line under that button: who the deck is written for. */
  audience: string;
  /**
   * `PageHeader`'s description, complete — the page passes it through untouched.
   *
   * ⚠ WHERE IT STATES A COUNT, THE COUNT IS INTERPOLATED FROM THE DECK'S OWN ARRAY. The header said
   * the literal "Ten steps" while `GUIDE_STEPS` held sixteen, and then nineteen: the one sentence a
   * reader sees before scrolling was the only place on the page that disagreed with the page. Three
   * decks is three chances to do it again, so each literal below reads `${…_STEPS.length}` and
   * `e2e/guide-tracks-unit.spec.ts` fails on any deck whose description names a bare number.
   */
  description: string;
  /** The hero's headline. GSAP splits it per word, so keep it a short sentence. */
  headline: string;
  /** The hero's opening paragraph. */
  intro: string;
  /**
   * The hero's three orienting facts. Three and not four, for the reason `GuideHero` gives: four
   * facts is a list and three is an orientation.
   *
   * The count fact is written out per deck rather than shared, because the sentence after the number
   * differs — a designer's steps are "in the order you do them in the field" and an inspector's are
   * not. The NUMBER itself is always `steps.length` and never a literal.
   */
  facts: ReadonlyArray<{ icon: LucideIcon; text: string }>;
  steps: GuideStep[];
  /** The closing band's recap heading. */
  recapTitle: string;
  /** The paragraph under it. This is where a per-deck claim about Android belongs, if any. */
  recapLead: string;
  /** The checklist band's heading. */
  checklistTitle: string;
  /** The paragraph under it. */
  checklistLead: string;
  /** Things that are expensive or impossible to reverse once the moment has passed. */
  checklist: readonly string[];
  /**
   * "Where to go next" — the deck's FULL list of exits. It is NOT a list every entry of which this
   * deck's audience can open, and the renderer is what makes the band honest.
   *
   * ⚠ THAT IS WHAT THIS FIELD USED TO DECLARE — "every entry must be a route this deck's audience
   * can actually open" — and 2026-09-16 broke it in two places on purpose, so it is restated here
   * rather than left to be read as an enforced invariant. `GuideOutro` filters this list per reader
   * (`canAccessRoute`, plus the admin-view second gate `AppShell` applies after it) and draws the
   * subset that reader can really open; the DATA is allowed to be wider than any one tier.
   *
   * THE ENTRY THAT MAKES IT WIDER TODAY, created by that day's changes:
   *   * `DESIGNER_TRACK`'s `/review`, which `canReview` admits from FIELD_CONTRIBUTOR up — so a
   *     CROWDSOURCE_VOLUNTEER, whom `guideTrackFor`'s fallback arm puts on that deck and the scoping
   *     now keeps there, is refused it. Recorded as accepted at the DATA level and fixed at the
   *     RENDERER level in `GuideOutro`'s header and in `e2e/guide-tracks-unit.spec.ts`.
   *
   * There were two until 2026-10-09. `INSPECTOR_TRACK`'s `/design-workshop-inspections` was refused
   * to ADMIN and MASTER_ADMIN by name, so the deck's headline destination was filtered out of its own
   * closing band for the only tiers besides an INSPECTOR that could switch to it. The owner's ruling
   * that day lets the three administering tiers be APPOINTED to inspect, `canInspectDesignWorkshops`
   * became every account that may be, and the tile is drawn for an admin again — over a list that
   * holds only the workshops somebody else appointed them to.
   *
   * SO AN ENTRY NO TIER IN A DECK'S AUDIENCE CAN OPEN IS PROSE NOBODY WILL EVER SEE, and nothing
   * will say so. The filter is deliberately silent (`GuideOutro`'s header argues why), `tsc` has no
   * opinion, and `e2e/guide-tracks-unit.spec.ts`' exit-reachability test asks the question only of
   * the directorate deck against the ministry posts and the inspector deck against INSPECTOR —
   * never of the designer's deck, and never of an admin reading a deck the carve-out gave them. A
   * new exit gated on its own tier therefore type-checks, tests green and ships invisible.
   */
  next: readonly GuideExit[];
};

/**
 * THE DESIGNER'S DECK — unchanged, and that is deliberate down to the byte.
 *
 * `GUIDE_STEPS` is read as text by `WalkthroughStepsTest.kt` on the Android side, held to
 * `docs/WALKTHROUGH.md` by `e2e/guide-walkthrough-unit.spec.ts`, and pinned in arc order by both.
 * Every string below was already on the page; this literal only moves them out of `GuideHero` and
 * `GuideOutro` so the other two decks can say something else.
 */
export const DESIGNER_TRACK: GuideTrack = {
  id: "designer",
  name: "Documenting a craft",
  audience: "Designers, and anybody learning how a workshop is run",
  description:
    "How a craft gets documented and how a design & prototype workshop is run, from your own " +
    `designer profile to the report a ministry officer receives. ${GUIDE_STEPS.length} steps, in ` +
    "the order you do them.",
  headline: "Document a craft, end to end.",
  intro:
    "This is the whole process, in the order you actually perform it: the repository records first, " +
    "then the 22-stage design & prototype workshop they feed and the report that comes out of it. " +
    "Each step below names the screen it lives on, the fields it asks for, and the mistakes that " +
    "cost people a second trip to the field.",
  facts: [
    { icon: ShieldCheck, text: "Access is by invitation — an admin admits each address before it can sign in" },
    { icon: ListOrdered, text: `${GUIDE_STEPS.length} steps, in the order you do them in the field` },
    { icon: Compass, text: "Every record is scoped to a workshop" }
  ],
  steps: GUIDE_STEPS,
  recapTitle: "The whole process, in one line",
  recapLead:
    "Learn this order and you can work without the guide. It is the same order on the web and in " +
    "the Android app, and the screens carry the same names in both.",
  checklistTitle: "Before you leave the field",
  checklistLead:
    "A missing field is a phone call. A missing recording is another trip. Run this list while the " +
    "artisan is still in front of you.",
  checklist: [
    "Every artisan you spoke to has a record, with Do's and Don'ts filled in.",
    "Every product you photographed has its dimensions, cost of making and selling price.",
    "Every process has its steps in order, and the steps have video.",
    "Every tool has a material, a maker and a replacement cost.",
    "The questionnaire's completion matrix has no unexplained gaps.",
    "Anything you shot that has no home is uploaded to Miscellaneous Media.",
    "No photograph was left refused, and every gallery has the number it asks for — a retake costs nothing while you are still standing there.",
    "Every prototype has its tag tied to it, so stages 14, 15 and 16 attach to the right one.",
    "The sketches nobody prototyped are recorded too — set-aside designs only survive if stage 11 has them."
  ],
  next: [
    { label: "Dashboard", href: "/dashboard", icon: LayoutGrid, note: "Every screen in this guide, one tap away" },
    { label: "Review", href: "/review", icon: ClipboardCheck, note: "What is waiting on a decision" },
    { label: "My Activity", href: "/activity", icon: Activity, note: "Everything you have recorded so far" },
    { label: "Search", href: "/search", icon: Search, note: "Find a record across the repository" },
    { label: "Sharing", href: "/sharing", icon: Share2, note: "Give a colleague access to your records" },
    { label: "Give app feedback", href: "/feedback", icon: MessageSquare, note: "Tell us what slowed you down" }
  ]
};

/** THE DIRECTORATE'S DECK. See `directorateSteps.ts` for why it is a separate deck at all. */
export const DIRECTORATE_TRACK: GuideTrack = {
  id: "directorate",
  name: "Running the scheme",
  audience: "Assistant Director, Regional Director, Ministry Admin",
  description:
    "What a ministry post actually does in this product: the annual plan, the sanction register, " +
    "naming a workshop's designer and its two supervising officers, writing inside a workshop, " +
    "reading one back, and approving the report and handing it on to the office. " +
    `${DIRECTORATE_STEPS.length} screens, in the order a workshop reaches them.`,
  headline: "Plan it, sanction it, staff it, read it back, sign it off.",
  intro:
    "Your screens are not the designer's screens, and most of them are not on the designer's " +
    "walkthrough at all. This deck is the lifecycle of one workshop as a ministry sees it — the row " +
    "in the directory, the order that opens it, the postings that staff it, the stages you can now " +
    "write in, the read-back afterwards, and the sign-off at the end. Most of the gates below are not " +
    "rank thresholds, so each card says who its screen is for in its own words rather than borrowing " +
    "one sentence.",
  facts: [
    { icon: FileSignature, text: "A sanction order opens a workshop and mints its designer's account" },
    { icon: ListOrdered, text: `${DIRECTORATE_STEPS.length} screens, in the order a workshop reaches them` },
    { icon: UserCheck, text: "Your three posts do not have the same powers — every card says which" }
  ],
  steps: DIRECTORATE_STEPS,
  recapTitle: "A workshop's life, in one line",
  recapLead:
    "Planned, sanctioned, staffed, filled in, read back, approved and handed on to the office. Every " +
    "screen here but Design workshops is used on the web, and a handset runs Design workshops exactly " +
    "as this browser does.",
  checklistTitle: "Before you sign it off",
  checklistLead:
    "Each of these is cheap now and expensive or impossible later — an account minted against the " +
    "wrong address, a link nobody sent, a correction made in the directory and not on the workshop.",
  checklist: [
    "The Gmail address on the sanction order is the one on the signed document, character for character — it becomes the designer's sign-in identity and it cannot be edited afterwards.",
    "The sign-in link has actually been sent. It is on screen once, works once, and expires.",
    "Every workshop you opened has a designer named on it. Until it does, nobody can fill in a single stage of it.",
    "Both officer slots are filled. A workshop with no Assistant Director and no Regional Director is nobody's to read back.",
    "Before you approve a report, read what the inspecting officers asked for and check the corrections were made. An approved report can no longer be changed, and a correction found afterwards means withdrawing the approval.",
    "The artisan list's refused rows were corrected and re-uploaded, not left — the report names each one and why.",
    "The filled-in artisan pro-forma has been deleted from wherever you saved it. It carries Aadhaar numbers and it is not stored here.",
    "Anything you corrected in the annual plan for a workshop that is ALREADY open was also corrected on the workshop. The plan and the workshop stop being the same document the moment a row is opened."
  ],
  next: [
    { label: "Dashboard", href: "/dashboard", icon: LayoutGrid, note: "Your ministry desk is the first card on it" },
    { label: "Design workshops", href: "/design-workshops", icon: DraftingCompass, note: "Everything you may open, and write in" },
    { label: "Manage users", href: "/users", icon: UserCog, note: "Where a ministry post is granted in the first place" },
    { label: "Review", href: "/review", icon: ClipboardCheck, note: "Records waiting on a decision" },
    { label: "My Activity", href: "/activity", icon: Activity, note: "Everything you have recorded so far" },
    { label: "Give app feedback", href: "/feedback", icon: MessageSquare, note: "Tell us what slowed you down" }
  ]
};

/** THE INSPECTOR'S DECK. See `inspectorSteps.ts` for the set-versus-rank argument it rests on. */
export const INSPECTOR_TRACK: GuideTrack = {
  id: "inspector",
  name: "Inspecting a workshop",
  audience: "Inspector / Reviewer",
  description:
    "The inspection surface: the workshops you have been assigned, how to read one, what you can " +
    `file about it, and the record queue your tier opens. ${INSPECTOR_STEPS.length} screens, in the ` +
    "order an inspection happens.",
  headline: "Read it back, and say what is wrong.",
  intro:
    "Your surface is not the designer's with the buttons removed — it is a different tree, behind a " +
    "different gate, reached through an assignment an admin makes one workshop at a time. You cannot " +
    "run a workshop, and that is the point of the tier rather than a limitation of it: an inspector " +
    "who could fill in a stage would be reviewing their own work. What you can do is read every " +
    "stage of a workshop you were given, see who wrote each field, and put a correction on the " +
    "record under your name.",
  facts: [
    { icon: FileSearch, text: "You read the workshops an admin assigned you, and no others" },
    { icon: ListOrdered, text: `${INSPECTOR_STEPS.length} screens, from the list to the verdict` },
    { icon: Eye, text: "Nothing you do changes a workshop's content — only its status" }
  ],
  steps: INSPECTOR_STEPS,
  recapTitle: "An inspection, in one line",
  /*
   * WHAT IS TRUE OF THE ANDROID APP, AND ONLY THAT. Since the handset gained the feedback box
   * (`InspectionFeedbackPanel` on `InspectionDetailScreen`) all four screens of this deck exist there
   * with the same words, and the handset keeps a copy of a workshop it has read and the notes written
   * without signal, sending them once the report has been checked again. This used to say Correction
   * suggestions was web-only, which was true until that landed. `walkthroughInspectorOmissions` in
   * `WalkthroughSteps.kt` — now empty — is the register `backend/tests/test_walkthrough_fields_parity.py`
   * and `WalkthroughDecksTest.kt` read, so a card dropped from either side is a red test.
   */
  recapLead:
    "Assigned, read, answered. All four screens exist on the Android app as well and carry the same " +
    "words — including Correction suggestions, which on a phone also works without signal: the note " +
    "is kept on the phone and sent once the report has been checked again.",
  checklistTitle: "Before you send a report back",
  checklistLead:
    "A suggestion cannot be edited or withdrawn once it is filed, and a send-back moves a report " +
    "onto somebody's desk. Both are worth one more minute.",
  checklist: [
    "Your note says what is wrong AND what it should say. The designers read it exactly as written, with no conversation attached.",
    "You picked the right stage, or chose “The report as a whole” on purpose rather than by leaving the box alone.",
    "You pressed the button you meant. Filing a suggestion leaves the report where it is; only sending it back puts it on the designers' desks.",
    "You are not deciding on a photograph you could not see. Media is counted on an inspection read, never carried.",
    "A value that looks wrong may be a copy taken when the stage was saved — the record it came from may have been corrected since, and the stage would not have changed.",
    "A stage reading 100% means every required field was answered. It is arithmetic, not a verdict, and it is not evidence that the answers are right."
  ],
  next: [
    { label: "Dashboard", href: "/dashboard", icon: LayoutGrid, note: "Where everything you can open is listed" },
    { label: "Workshops to inspect", href: "/design-workshop-inspections", icon: FileSearch, note: "The workshops assigned to you" },
    { label: "Review", href: "/review", icon: ClipboardCheck, note: "Records waiting on a decision" },
    { label: "My Activity", href: "/activity", icon: Activity, note: "Everything you have recorded so far" },
    { label: "Sharing", href: "/sharing", icon: Share2, note: "Give a colleague access to your records" },
    { label: "Give app feedback", href: "/feedback", icon: MessageSquare, note: "Tell us what slowed you down" }
  ]
};

/**
 * Every deck, in the order the switcher prints them.
 *
 * THE DESIGNER'S IS FIRST AND STAYS FIRST whoever is reading, because the order of a list of choices
 * is not the same question as which of them is selected. A rail that reordered itself per account
 * would mean two colleagues describing "the second one" and meaning different decks.
 */
export const GUIDE_TRACKS: readonly GuideTrack[] = [DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK];

/**
 * WHICH DECK OPENS FOR THIS ACCOUNT. STILL A DEFAULT — `guideTracksFor` below is the gate, and it is
 * built on top of this answer rather than beside it. Read the module header before changing either.
 *
 * ⚠ THE CONSEQUENCE OF A WRONG ANSWER HERE CHANGED ON 2026-09-16 AND THE FUNCTION DID NOT. It used
 * to cost a reader one click, because the switcher sat underneath and reached every deck. For the
 * nine tiers that are not an admin it now costs them the deck itself, silently, with no control on
 * screen that could put it right — `guideTracksFor` returns exactly what this returns for them. The
 * heuristic is unchanged and still correct; what is gone is the escape hatch that made a mistake in
 * it cheap. That is why `e2e/guide-tracks-unit.spec.ts` pins this tier by tier and why
 * `backend/tests/test_role_ladder_parity.py` registers that tuple as a mirror of the ladder.
 *
 * ── THE ORDER OF THE TWO TESTS IS THE WHOLE IMPLEMENTATION, AND IT IS NOT ARBITRARY ─────────────
 *
 * The inspector test is first because it is the narrowest: `isInspectorTier` is `INSPECTION_ROLES`, a
 * set with exactly one member, so it can never claim an account another arm wanted. Reversing the two
 * would change nothing today and would be a latent bug the day the sets overlap — which is precisely
 * the kind of "agrees today, means something different" coupling this codebase keeps being bitten by,
 * and the next section is the time it bit.
 *
 * ── WHY THE TIER PREDICATES, AND NOT THE SURFACE PREDICATES THIS USED TO BORROW ─────────────────
 *
 * The question is "whose job is this deck", and a job is a TIER. Until 2026-10-09 the two read-surface
 * predicates answered it by coincidence: `canInspectDesignWorkshops` was exactly `{INSPECTOR}` and
 * `canReadWorkshopOversight` was exactly the three ministry posts, refusing admins by name. The
 * owner's ruling that day let a Ministry Admin, an admin and the master admin be APPOINTED inspector,
 * Assistant Director or Regional Director of one workshop, and both surfaces widened to every account
 * that may be appointed — admins included. Still borrowed, they put the master admin and an admin on
 * the inspector's deck by default and gave a Ministry Admin the inspector's deck as its ONLY deck,
 * which is the exact failure the warning that stood here predicted.
 *
 * So the default reads `isInspectorTier` and `isDirectorateTier`, which read `INSPECTION_ROLES` and
 * `OFFICER_ROLES` — the TIER sets, whose literals did not move. Holding a post on one workshop is an
 * appointment, not a job description, and it does not change which deck describes your work.
 *
 * NOT `canSeeMinistryDesk` EITHER. It differs from `isDirectorateTier` by exactly one tier and that
 * tier is the master admin, who is in the card's audience and must NOT be defaulted into the
 * directorate deck. A master admin does every job in this product; the deck that opens for them
 * should be the one that describes what the product IS, and that is the designer's.
 *
 * ⚠ `OFFICER_ROLES` IS STILL READ FOR MORE THAN THIS DEFAULT — `oversightRefusalMeansNoPosts` reads
 * it to tell a fault from an admin who holds no posts. If it ever stops being exactly the three
 * directorate tiers — a fourth post — this default needs a set of its own.
 * `e2e/guide-tracks-unit.spec.ts` asserts the three tiers by name, so that day is a red test here
 * rather than a silent wrong default.
 *
 * A NULL USER GETS THE DESIGNER'S DECK. `AppShell` renders nothing until there is a user, so this
 * arm is not reachable from the page — but the function is exported and pure, and answering
 * `undefined` for an unknown account would push the null check onto every caller.
 */
export function guideTrackFor(user: User | null | undefined): GuideTrack {
  if (isInspectorTier(user)) return INSPECTOR_TRACK;
  if (isDirectorateTier(user)) return DIRECTORATE_TRACK;
  return DESIGNER_TRACK;
}

/**
 * WHICH DECKS THIS ACCOUNT MAY READ AT ALL. A GATE, unlike `guideTrackFor` above, which only picks
 * the default — and the only gate in this feature. Owner ruling, 2026-09-16: "except for admins and
 * master admins, the walkthrough that the people get to see should be the ones that are relevant to
 * their roles."
 *
 * ── SET MEMBERSHIP, NEVER `hasRank`, AND THAT IS NOT A STYLE PREFERENCE ────────────────────────
 *
 * This function is `isAdmin` over the answer `guideTrackFor` already gives, and every predicate in
 * that chain is a SET: `isAdmin` is `{ADMIN, MASTER_ADMIN}`, `INSPECTION_ROLES` is `{INSPECTOR}` and
 * `OFFICER_ROLES` is the three ministry posts. All three are non-monotonic in rank, and the two tier
 * sets leave the admins out by name. `INSPECTOR` (37) sits BETWEEN `DESIGNER` (35) and `PROFESSOR`
 * (40), so a floor at 37 would hand the inspector's deck to every tier above it — professor, the
 * three ministry posts, admin and master admin, none of whom inspects as a job. That is what makes a
 * threshold instinct wrong here rather than merely imprecise.
 *
 * THE TIER SETS DID NOT MOVE ON 2026-10-09; THE SURFACES DID. `assert_inspection_surface` and
 * `assert_oversight_surface` now admit a Ministry Admin, an admin and the master admin, because any
 * of them may be appointed to a post on one workshop by somebody else. That widens which screens
 * open, scoped to the rows they hold, and leaves this ladder of decks exactly as it was — an
 * appointment is not a job, so it buys no deck.
 *
 * ── THE MAPPING IS TOTAL, AND FOUR TIERS REACH THEIR DECK BY FALLBACK RATHER THAN BY DESIGN ────
 *
 * Every one of the eleven tiers gets at least one deck, because `guideTrackFor`'s last arm is a
 * fallback; and every deck has at least one tier that owns it (designer ← DESIGNER, directorate ←
 * the three ministry posts, inspector ← INSPECTOR). So there is no tier with nothing to read and no
 * deck nobody opens. What there IS, and what the switcher used to paper over, is four tiers with no
 * deck WRITTEN FOR THEM — PROFESSOR (40), RESEARCHER (30), FIELD_CONTRIBUTOR (20) and
 * CROWDSOURCE_VOLUNTEER (10) land on the designer's deck, and PROFESSOR is the sharpest case: a
 * professor is outside `DESIGN_WORKSHOP_ROLES`, outside `INSPECTION_ROLES` and outside
 * `OFFICER_ROLES`, so ten of that deck's cards teach a route tree they are refused. Until 2026-09-16
 * they could switch away from it; now they cannot. That is a known gap in the CONTENT, recorded here
 * because this is the function that makes it permanent, and it is answered by writing a deck for
 * them rather than by loosening this gate. The two mitigations that ARE in place: every step card
 * already says who its screen is for in its own `watch`, and `GuideOutro` now filters its exit tiles
 * on `canAccessRoute`, so the one deck those tiers can reach no longer offers them a lock panel.
 *
 * ── ADMINS KEEP ALL THREE, AND THAT IS THE RULING RATHER THAN AN OVERSIGHT ─────────────────────
 *
 * `isAdmin` and not `canSeeMinistryDesk`: the two differ by exactly one tier and it is the master
 * admin, who is in the card's audience and must not be scoped to the ministry's deck. A rank floor at
 * 50 would agree with `isAdmin` today and would stop agreeing the moment a tier lands above 50, which
 * is the borrowed-set trap `guideTrackFor` records above it. `MINISTRY_ADMIN` (48) is a ministry post
 * and gets the ministry's deck only; `ADMIN` (50) and `MASTER_ADMIN` (60) do every job in this product
 * and keep the switcher.
 *
 * Returning `GUIDE_TRACKS` itself rather than a filtered copy is what keeps the switcher's print
 * order — the designer's deck first, for everybody, which that constant's own comment argues at
 * length: the order of a list of choices is not the question of which one is selected, and a row that
 * reordered itself per account would have two colleagues meaning different decks by "the second one".
 *
 * A NULL USER GETS THE DESIGNER'S DECK, one deck, for `guideTrackFor`'s reason: `AppShell` renders
 * nothing until there is a user, so the arm is unreachable from the page, and answering `[]` would
 * push an empty-list branch onto a caller that has no rendering for one.
 */
export function guideTracksFor(user: User | null | undefined): readonly GuideTrack[] {
  if (isAdmin(user)) return GUIDE_TRACKS;
  return [guideTrackFor(user)];
}

/**
 * The deck a `/guide#<id>` deep link belongs to, or null when nothing answers to that anchor.
 *
 * ⚠ THIS FUNCTION IS UNCHANGED AND THE RULE ABOVE IT REVERSED ON 2026-09-16. It used to say "DEEP
 * LINKS OUTRANK THE ROLE, and they have to", and the page acted on whatever this returned. The owner
 * ruled the other way (OQ-5, arm b): THE ROLE WINS, and an anchor naming a card in a deck the reader
 * may not see is SILENTLY IGNORED — no note, no redirect, no hint that the link meant something.
 *
 * THE GATE IS IN THE PAGE AND MUST STAY THERE. This function is pure, exported, and pinned by
 * `e2e/guide-tracks-unit.spec.ts` to resolve EVERY anchor of EVERY deck; it answers "whose card is
 * this", which has one correct answer regardless of who is asking. `app/(protected)/guide/page.tsx`
 * intersects that answer with `guideTracksFor(user)` before acting on it. Moving the check in here
 * would make one function answer two questions and would break the only test that holds the
 * anchor→deck map complete.
 *
 * WHAT THE OLD RULE WAS PROTECTING, AND WHAT NOW HAPPENS INSTEAD. `GuideJourney` validates the hash
 * against the deck it was handed and silently discards an id it does not recognise, so a link into a
 * deck the reader cannot see now "appears to work and goes nowhere" — exactly the failure this
 * function was written to remove, reintroduced on purpose for the readers the gate applies to. It is
 * cheap today and was ruled on with that in mind: **VERIFIED 2026-09-16** that nothing in the
 * product emits a `/guide#<id>` link — every entry point goes to the bare route
 * (`grep -rn '"/guide' frontend/app frontend/components` is the list). The day anything starts
 * emitting anchors, OQ-5 is worth re-opening, and arm (c) — open the reader's own deck and say in
 * one line which deck the link belonged to — is the answer that was costed and not taken.
 */
export function guideTrackForAnchor(anchor: string | null | undefined): GuideTrack | null {
  if (!anchor) return null;
  return GUIDE_TRACKS.find((track) => track.steps.some((step) => step.id === anchor)) ?? null;
}

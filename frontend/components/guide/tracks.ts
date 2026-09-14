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
import { canInspectDesignWorkshops, canReadWorkshopOversight } from "@/lib/permissions";
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
 * ── WHAT THIS MODULE IS, AND WHAT IT DELIBERATELY IS NOT ────────────────────────────────────────
 *
 * It is four lines of selection over two predicates that `lib/permissions.ts` already exports, plus
 * the copy each deck needs for the three bands the page is made of. It is NOT a gate. `/guide` is
 * still ungated, all three decks are still in the bundle for everybody, and the switcher on the page
 * still reaches all three — so the role picks which deck OPENS and takes nothing away from anybody.
 *
 * That distinction is the whole answer to the objection `steps.ts` raises against a per-role script,
 * and each new deck's header answers it at greater length. The short form: a coach-mark tour's
 * per-role script has to be a second copy of `ROUTE_GUARDS`, because it anchors to live DOM and
 * dead-ends when a page will not render. Nothing here anchors to anything. A wrong answer from
 * `guideTrackFor` costs a reader one click, which is why it may be a heuristic at all.
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
 * monitor" — and `InspectionDetailScreen.kt` has no feedback box, so the handset can read the
 * suggestions on a workshop and cannot file one. Repeating that sentence over the two new decks
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
  /** "Where to go next" — every entry must be a route this deck's audience can actually open. */
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
    "naming a workshop's designer and its two supervising officers, writing inside a workshop, and " +
    `reading one back. ${DIRECTORATE_STEPS.length} screens, in the order a workshop reaches them.`,
  headline: "Plan it, sanction it, staff it, read it back.",
  intro:
    "Your screens are not the designer's screens, and most of them are not on the designer's " +
    "walkthrough at all. This deck is the lifecycle of one workshop as a ministry sees it — the row " +
    "in the directory, the order that opens it, the postings that staff it, the stages you can now " +
    "write in, and the read-back afterwards. Three of the five gates below are not rank thresholds, " +
    "so each card says who its screen is for in its own words rather than borrowing one sentence.",
  facts: [
    { icon: FileSignature, text: "A sanction order opens a workshop and mints its designer's account" },
    { icon: ListOrdered, text: `${DIRECTORATE_STEPS.length} screens, in the order a workshop reaches them` },
    { icon: UserCheck, text: "Your three posts do not have the same powers — every card says which" }
  ],
  steps: DIRECTORATE_STEPS,
  recapTitle: "A workshop's life, in one line",
  recapLead:
    "Planned, sanctioned, staffed, filled in, read back. Four of these five screens are on the web " +
    "only — the Android app carries no annual plan, no sanction register and no oversight screens " +
    "at all. Design workshops is the exception, and a handset runs it exactly as this browser does.",
  checklistTitle: "Before you sign it off",
  checklistLead:
    "Each of these is cheap now and expensive or impossible later — an account minted against the " +
    "wrong address, a link nobody sent, a correction made in the directory and not on the workshop.",
  checklist: [
    "The Gmail address on the sanction order is the one on the signed document, character for character — it becomes the designer's sign-in identity and it cannot be edited afterwards.",
    "The sign-in link has actually been sent. Nothing is emailed by this product; the link is on screen once, works once, and expires.",
    "Every workshop you opened has a designer named on it. Until it does, nobody can fill in a single stage of it.",
    "Both officer slots are filled. A workshop with no Assistant Director and no Regional Director is nobody's to read back.",
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
  recapLead:
    "Assigned, read, answered. The first two screens exist on the Android app as well and carry the " +
    "same words; Correction suggestions is web-only — the handset can read the suggestions already " +
    "on a workshop and has no box to file one — so a send-back is done from a browser.",
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
 * WHICH DECK OPENS FOR THIS ACCOUNT. A DEFAULT, NOT A GATE — read the module header before changing
 * it; all three decks stay reachable from the switcher whatever this answers.
 *
 * ── THE ORDER OF THE TWO TESTS IS THE WHOLE IMPLEMENTATION, AND IT IS NOT ARBITRARY ─────────────
 *
 * The inspector test is first because it is the narrowest: `canInspectDesignWorkshops` is a set with
 * exactly one member, so it can never claim an account another arm wanted. Reversing the two would
 * change nothing today and would be a latent bug the day the sets overlap — which is precisely the
 * kind of "agrees today, means something different" coupling this codebase keeps being bitten by.
 *
 * ── WHY `canReadWorkshopOversight` AND NOT `canSeeMinistryDesk` ─────────────────────────────────
 *
 * They differ by exactly one tier and that tier is the master admin, who is in the card's audience
 * and must NOT be defaulted into the directorate deck. A master admin does every job in this
 * product; the deck that opens for them should be the one that describes what the product IS, and
 * that is the designer's. `canReadWorkshopOversight` is `OFFICER_ROLES`, which is exactly the three
 * ministry posts and refuses admins by name — so it reads here as "holds a ministry post", which is
 * the question being asked.
 *
 * ⚠ THAT IS A REUSE ACROSS TWO QUESTIONS AND IT IS ONLY SAFE WHILE THE SETS COINCIDE. If
 * `OFFICER_ROLES` ever stops being exactly the three directorate tiers — a fourth post, or one of
 * the three losing its read surface — this default needs a set of its own rather than a borrowed
 * one. `e2e/guide-tracks-unit.spec.ts` asserts the three tiers by name, so that day is a red test
 * here rather than a silent wrong default.
 *
 * A NULL USER GETS THE DESIGNER'S DECK. `AppShell` renders nothing until there is a user, so this
 * arm is not reachable from the page — but the function is exported and pure, and answering
 * `undefined` for an unknown account would push the null check onto every caller.
 */
export function guideTrackFor(user: User | null | undefined): GuideTrack {
  if (canInspectDesignWorkshops(user)) return INSPECTOR_TRACK;
  if (canReadWorkshopOversight(user)) return DIRECTORATE_TRACK;
  return DESIGNER_TRACK;
}

/**
 * The deck a `/guide#<id>` deep link belongs to, or null when nothing answers to that anchor.
 *
 * DEEP LINKS OUTRANK THE ROLE, and they have to. `GuideJourney` validates the hash against the deck
 * it was handed and silently ignores an id it does not recognise, so without this a Ministry Admin
 * following a colleague's link to `#design-workshop-stages` would land on the directorate deck with
 * the hash quietly discarded — the link would appear to work and would go nowhere. Resolving the
 * deck from the anchor first makes the link the instruction, which is what a link is.
 *
 * `steps.ts` records that nothing in the app links to `/guide#<id>` yet — eleven entry points, all
 * to the bare route. That is an argument for making those links, not for leaving the mechanism
 * broken when somebody does.
 */
export function guideTrackForAnchor(anchor: string | null | undefined): GuideTrack | null {
  if (!anchor) return null;
  return GUIDE_TRACKS.find((track) => track.steps.some((step) => step.id === anchor)) ?? null;
}

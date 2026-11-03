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

export type GuideExit = {
  label: string;
  href: string;
  icon: LucideIcon;
  note: string;
};

export type GuideTrack = {
  id: string;
  name: string;
  audience: string;
  description: string;
  headline: string;
  intro: string;
  facts: ReadonlyArray<{ icon: LucideIcon; text: string }>;
  steps: GuideStep[];
  recapTitle: string;
  recapLead: string;
  checklistTitle: string;
  checklistLead: string;
  checklist: readonly string[];
  next: readonly GuideExit[];
};

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

export const DIRECTORATE_TRACK: GuideTrack = {
  id: "directorate",
  name: "Running the scheme",
  audience: "Assistant Director, Regional Director, Ministry Admin",
  description:
    "What a ministry post does: the annual plan, the sanction register, naming a workshop's " +
    "designer and its two supervising officers, filling in a workshop, reading one back, and " +
    "approving the report and handing it on to the office. " +
    `${DIRECTORATE_STEPS.length} screens, in the order a workshop reaches them.`,
  headline: "Plan it, sanction it, staff it, read it back, sign it off.",
  intro:
    "This walkthrough follows one workshop as the ministry sees it: the entry in the annual plan, " +
    "the order that opens it, the postings that staff it, the stages you can fill in, the " +
    "read-back afterwards, and the sign-off at the end. Your three posts can do different things, " +
    "so each card says who its screen is for.",
  facts: [
    { icon: FileSignature, text: "A sanction order opens a workshop and creates its designer's account" },
    { icon: ListOrdered, text: `${DIRECTORATE_STEPS.length} screens, in the order a workshop reaches them` },
    { icon: UserCheck, text: "Your three posts can do different things — each card says which" }
  ],
  steps: DIRECTORATE_STEPS,
  recapTitle: "A workshop's life, in one line",
  recapLead:
    "Planned, sanctioned, staffed, filled in, read back, approved and handed on to the office. These " +
    "screens are on the web, and Design workshops also works the same way in the Android app.",
  checklistTitle: "Before you sign it off",
  checklistLead:
    "Each of these is easy to get right now and hard to fix later — an account created for the " +
    "wrong address, a link nobody sent, a correction made in the plan but not on the workshop.",
  checklist: [
    "The Gmail address on the sanction order matches the signed document exactly — it becomes the designer's sign-in address.",
    "Each sign-in link has been sent to its designer. A link is shown only once, works once, and expires.",
    "Every workshop you opened has a designer named on it. Without one, the designer's work can't begin.",
    "Both officer posts are filled. Without an Assistant Director and a Regional Director, nobody is supervising the workshop.",
    "Before you approve a report, read what the inspecting officers asked for and check the corrections were made. An approved report can no longer be changed, and a correction found afterwards means withdrawing the approval.",
    "Any artisan entries the upload couldn't read were corrected and uploaded again — the upload report names each one and why.",
    "The filled-in artisan pro-forma has been deleted from wherever you saved it. It contains Aadhaar numbers, and the portal doesn't keep a copy.",
    "Anything you corrected in the annual plan for a workshop that is already open was also corrected on the workshop itself. Once a workshop is open, changes to the plan don't reach it."
  ],
  next: [
    { label: "Dashboard", href: "/dashboard", icon: LayoutGrid, note: "Your ministry desk is the first card on it" },
    { label: "Design workshops", href: "/design-workshops", icon: DraftingCompass, note: "Every workshop you can open and fill in" },
    { label: "Manage users", href: "/users", icon: UserCog, note: "Where a ministry post is granted in the first place" },
    { label: "Review", href: "/review", icon: ClipboardCheck, note: "Records waiting on a decision" },
    { label: "My Activity", href: "/activity", icon: Activity, note: "Everything you have recorded so far" },
    { label: "Give app feedback", href: "/feedback", icon: MessageSquare, note: "Tell us what slowed you down" }
  ]
};

export const INSPECTOR_TRACK: GuideTrack = {
  id: "inspector",
  name: "Inspecting a workshop",
  audience: "Inspector / Reviewer",
  description:
    "The workshops assigned to you, how to read one, what you can file about it, and the queue of " +
    `records waiting for your review. ${INSPECTOR_STEPS.length} screens, in the ` +
    "order an inspection happens.",
  headline: "Read it back, and say what is wrong.",
  intro:
    "You inspect the workshops assigned to you, one at a time: read every stage, see who wrote " +
    "each field, and put a correction on the record under your name.",
  facts: [
    { icon: FileSearch, text: "You read the workshops assigned to you, and no others" },
    { icon: ListOrdered, text: `${INSPECTOR_STEPS.length} screens, from the list to the verdict` },
    { icon: Eye, text: "Nothing you do changes a workshop's content — only its status" }
  ],
  steps: INSPECTOR_STEPS,
  recapTitle: "An inspection, in one line",
  recapLead:
    "Assigned, read, answered. The same four screens are in the Android app, where a note written without " +
    "signal is sent once you're back online.",
  checklistTitle: "Before you send a report back",
  checklistLead:
    "A suggestion can't be edited or withdrawn once it is filed, and sending a report back puts it " +
    "on its designers' desks. Both are worth one more minute.",
  checklist: [
    "Your note says what is wrong and what it should say. The designers read it exactly as written.",
    "You picked the right stage. “The report as a whole” is the default, so change it if your note is about one stage.",
    "You pressed the button you meant. Filing a suggestion leaves the report where it is; only sending it back puts it on the designers' desks.",
    "You have looked at the photographs and listened to the recordings your note is about. They are on the inspection page, under Files and under each stage.",
    "A value that looks wrong may be a copy taken when the stage was saved; the record it came from may have been corrected since.",
    "A stage reading 100% means every required field was answered, not that the answers are right."
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

export const GUIDE_TRACKS: readonly GuideTrack[] = [DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK];

export function guideTrackFor(user: User | null | undefined): GuideTrack {
  if (isInspectorTier(user)) return INSPECTOR_TRACK;
  if (isDirectorateTier(user)) return DIRECTORATE_TRACK;
  return DESIGNER_TRACK;
}

export function guideTracksFor(user: User | null | undefined): readonly GuideTrack[] {
  if (isAdmin(user)) return GUIDE_TRACKS;
  return [guideTrackFor(user)];
}

export function guideTrackForAnchor(anchor: string | null | undefined): GuideTrack | null {
  if (!anchor) return null;
  return GUIDE_TRACKS.find((track) => track.steps.some((step) => step.id === anchor)) ?? null;
}

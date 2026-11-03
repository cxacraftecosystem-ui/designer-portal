import { Eye, FileSearch, Layers, MessageSquare } from "lucide-react";

import type { GuideStep } from "@/components/guide/steps";

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
      "You see only the workshops assigned to you.",
    fields: [
      "Search — by title, craft, cluster or workshop code, across your assignments only",
      "Each row: the workshop's title, its code, craft and cluster, its dates and its status",
      "Filters — status, submission round, state, type of workshop, and the days it started between; each narrows your assignments and none can add one, and Clear filters puts them all back"
    ],
    watch: [
      "Ministry Admins and admins assign inspections. Ask one of them.",
      "Admins can be assigned to inspect too, and see only their assigned workshops.",
      "You can't inspect a workshop you worked on.",
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
      "Report readers weren't at the workshop, so each value shows who wrote it.",
    fields: [
      "Dates, Designer, Venue — as stage 1 recorded them",
      "Required fields answered — a percentage across every stage",
      "Each stage, numbered and titled, with its own required-field count",
      "Under each value: who wrote it, and where it was copied from",
      "Files — every photograph, recording and attachment filed with the workshop, and under each media field the files it holds",
      "Under a stage: the answers to the workshop's own questions, with the questions they answer"
    ],
    watch: [
      "This page is read-only: you can read every stage but not change it.",
      "You can open the photographs, recordings, videos and attachments, but not change them. If a file stops opening, select Refresh files.",
      "Completeness counts answered fields, not correct ones.",
      "Values chosen from a record are copies; the record may have changed since.",
      "Each workshop's own questions are shown with their answers.",
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
      "Your note is recorded under your name. Notes can't be edited later.",
    fields: [
      "What should be corrected?",
      "Which stage is it about? — “The report as a whole”, or one named stage",
      "“Suggest” — leaves the report where it is",
      "“Send back” — moves it to Needs revision"
    ],
    watch: [
      "Suggest keeps the report where it is. Send back moves it to Needs revision.",
      "Notes can't be edited or withdrawn. File another if you change your mind.",
      "You can file notes once the report is handed in.",
      "Nothing here changes a workshop's content; sending back changes only its status.",
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
      "You also review records — approve, reject or send back for revision.",
    fields: [
      "The queue, newest first, with the record's type, title and who submitted it",
      "Approve · Reject · Send for revision",
      "A comment — mandatory on Send for revision"
    ],
    watch: [
      "Your queue holds records from roles below yours.",
      "You don't edit others' records. Use Send for revision and say what to change.",
      "Send for revision needs a comment. Prefer it to Reject when a record can be fixed.",
      "You can approve several records at once; reject and send back one at a time.",
      "Approving a record doesn't change workshop stages that copied it."
    ]
  }
];

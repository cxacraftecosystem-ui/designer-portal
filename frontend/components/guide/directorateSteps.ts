import {
  Binoculars,
  CalendarRange,
  DraftingCompass,
  FileSearch,
  FileSignature,
  LayoutDashboard,
  Stamp,
  UserCheck
} from "lucide-react";

import type { GuideStep } from "@/components/guide/steps";

export const DIRECTORATE_STEPS: GuideStep[] = [
  {
    id: "ministry-dashboard",
    label: "Ministry dashboard",
    action: "Read the programme",
    icon: LayoutDashboard,
    href: "/ministry-dashboard",
    summary:
      "Every workshop and each designer's progress, with downloads.",
    why:
      "See which workshops are moving, stalled or finished. Downloads use the same figures.",
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
    id: "ministry-annual-plan",
    label: "Annual plan",
    action: "Upload the plan",
    icon: CalendarRange,
    href: "/annual-plan",
    summary:
      "The ministry's directory of the workshops planned for the year — uploaded as one spreadsheet, corrected by uploading it again.",
    why:
      "Planned workshops, not running ones. Correct the plan any time without affecting open workshops.",
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
    id: "ministry-sanction-order",
    label: "Sanction orders",
    action: "Record sanction order",
    icon: FileSignature,
    href: "/sanction-orders",
    summary:
      "Recording an order opens the workshop and invites its designer.",
    why:
      "Recording an order admits and empanels the designer, creates their account if needed, opens the workshop and issues a sign-in link.",
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
    id: "ministry-oversight",
    label: "Workshop oversight",
    action: "Name the designer and the officers",
    icon: UserCheck,
    href: "/officers",
    summary:
      "One workshop at a time: who it is for, who supervises and inspects it, and who is on its artisan list.",
    why:
      "Work starts once a designer is named; supervision once both directors are named.",
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
    id: "ministry-workshop",
    label: "Design workshops",
    action: "Fill in the stages",
    icon: DraftingCompass,
    href: "/design-workshops",
    summary:
      "The workshops you can open, and the 22-stage form inside each one. You can fill these in, not only read them.",
    why:
      "Ministry posts can fill in stages, add sections, record consent and generate the report.",
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
      "Workshops you supervise are on Workshops I monitor, not here.",
      "A workshop you supervise or inspect is read-only to you here. As a Ministry Admin, you'll see a note saying so above the stages.",
      "Choosing a record copies its values; the report prints that copy. Later edits to the record don't change the stage."
    ]
  },
  {
    id: "ministry-monitored",
    label: "Workshops I monitor",
    action: "Read a workshop you supervise",
    icon: Binoculars,
    href: "/officers/monitored",
    summary:
      "The workshops you were named on as Assistant Director or Regional Director: every stage to read, nothing to edit.",
    why:
      "Every value shows who wrote it.",
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
    id: "ministry-inspection",
    label: "Workshops to inspect",
    action: "Review a report handed in to you",
    icon: FileSearch,
    href: "/design-workshop-inspections",
    summary: "Reports you were named to inspect. The count beside it is how many wait for you.",
    why: "Inspectors ask for corrections before the report is signed off.",
    fields: [
      "Waiting for you · With the designers · Not handed in yet — the three lists",
      "Suggest a correction — on a stage or a field",
      "Send back — when the report needs corrections before approval"
    ],
    watch: [
      "Only Ministry Admins and admins named as inspectors see workshops here. Directors can't inspect.",
      "You're e-mailed when a report is handed in or resubmitted. Choose once a day or never in Settings."
    ]
  },
  {
    id: "ministry-sign-off",
    label: "Reports to approve",
    action: "Approve a report and hand it on",
    icon: Stamp,
    href: "/design-workshop-approvals",
    summary:
      "Reports handed in by designers, waiting for your decision.",
    why:
      "Inspectors ask for corrections; the Ministry Admin signs the report off and sends it on.",
    fields: [
      "Waiting for approval · Approved, not yet handed on · Handed on · Returned — the four lists",
      "The report, stage by stage, with who wrote each field",
      "What the officers asked for, round by round, and every decision taken on the report",
      "Approve — with a note, if you want one on the record",
      "What needs correcting (required) — when you send a report back, withdraw an approval or return one from the office",
      "Hand on to the office — the office, a note, and the exported report file that went",
      "Approve and hand on — approves, makes the report file and e-mails the office a link that lasts 7 days"
    ],
    watch: [
      "YOU CAN'T DECIDE ON A REPORT YOU WORKED ON OR INSPECT. The decisions are switched off with the reason.",
      "APPROVED REPORTS ARE LOCKED. To correct one, withdraw the approval with a reason.",
      "IF A REPORT CHANGES WHILE YOU READ IT, approval is refused — reload and read it again.",
      "APPROVE AND HAND ON E-MAILS THE OFFICE once an administrator has set its addresses. Until then, hand the report on yourself.",
      "A REPORT HANDED ON CAN STILL BE RETURNED, with a reason, if the office sends it back. The approval and the hand-on before it stay on the record."
    ]
  }
];

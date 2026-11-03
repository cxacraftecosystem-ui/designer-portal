import {
  Brush,
  ClipboardCheck,
  ClipboardList,
  Eye,
  FileOutput,
  FileSearch,
  FileSpreadsheet,
  GitBranch,
  History,
  IdCard,
  Images,
  Layers,
  ListChecks,
  Package,
  PencilRuler,
  QrCode,
  Star,
  User as UserIcon,
  UsersRound,
  Wrench,
  type LucideIcon
} from "lucide-react";

export type GuideStep = {
  id: string;
  label: string;
  action: string;
  icon: LucideIcon;
  href: string;
  summary: string;
  why: string;
  fields: string[];
  watch: string[];
};

const DESIGN_WORKSHOP_FIELD =
  "Choose “Type of workshop”, then the workshop. New records start on your latest workshop — change it if needed, or choose “Not linked to a workshop”.";

const MEDIA_DESIGN_WORKSHOP_FIELD =
  "New uploads start on your latest design workshop. Change it if needed, or choose “Not filed under a design workshop”.";

const DICTATION_ON_THIS_FORM =
  "Text boxes have a microphone. Speech becomes text on this device — nothing is recorded or uploaded, and it works offline.";

export const GUIDE_STEPS: GuideStep[] = [
  {
    id: "workshop",
    label: "Workshop",
    action: "Record workshop",
    icon: UsersRound,
    href: "/workshops?new=1",
    summary:
      "Open the workshop you are documenting, or create it, before you record anything else.",
    why:
      "Every record belongs to a workshop. New records start on your latest one, so set it up first.",
    fields: [
      "Kind of workshop (required)",
      "Workshop title (required)",
      "Place (required)",
      "Workshop duration (Start date and End date)",
      "Status",
      "Description",
      "Notes",
      "Linked artisans",
      "Crafts covered",
      "Workshop media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Create the workshop before you leave for the field. Everything else you record is filed under it.",
      "“Kind of workshop” decides whether a design & prototype workshop can start from this record. Only workshops of that kind appear in the “Start from a recorded workshop” list.",
      "Records made outside the workshop's dates are flagged and need a reviewer's approval.",
      "Records “Not linked to a workshop” won't show under any workshop. Choose one on the form; an admin can file them later.",
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "craft",
    label: "Craft",
    action: "Add craft",
    icon: Brush,
    href: "/crafts?new=1",
    summary: "Add the craft you are documenting so artisans, products and tools can be linked to it.",
    why:
      "Artisans link to a craft, and products and tools take their craft name from it. Adding it once keeps the spelling the same in everyone's records.",
    fields: [
      "Workshop",
      "Craft name (required)",
      "Local name",
      "Category (required)",
      "Sub-category (required)",
      "State of origin",
      "State of practice",
      "GI status (required)",
      "Description",
      "Craft media"
    ],
    watch: [
      "Check the list first. If the craft is already there, use it rather than adding a second spelling.",
      "The local name matters as much as the English one. Record what the community calls it.",
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "artisan",
    label: "Artisan",
    action: "Record artisan",
    icon: UserIcon,
    href: "/artisans/new",
    summary: "Record the person: who they are, where they work, how to reach them, and what they have learnt.",
    why:
      "Products, processes, tools and interviews all link back to the artisan. The Do's and Don'ts are the artisan's own craft knowledge, and they can't be recovered once you have left.",
    fields: [
      "Type of workshop",
      "Workshop",
      "Name (required)",
      "Local name",
      "Craft (required)",
      "Place (required)",
      "Gender",
      "Date of birth (required)",
      "Craft start date (required)",
      "Experience (years)",
      "Experience (months)",
      "Phone",
      "Email",
      "Address",
      "Notes",
      "Aadhaar number (required)",
      "Artisan Pehchan Card available",
      "Artisan Pehchan Card number (required if the card is available)",
      "Do's (required)",
      "Don'ts (required)",
      "Status",
      "Artisan media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Do's and Don'ts are required. Press Enter for each new point, one lesson per line.",
      "Choose an existing craft or type a new craft name. The form can't be saved without one.",
      "Camera details from your photos are added to the notes automatically, so you don't need to copy them.",
      "Aadhaar prevents duplicate artisans. If the person exists, you're shown their record to add to. Required for new artisans.",
      "Shared and exported numbers are masked (“XXXX XXXX 9012”). Leave a masked box as it is.",
      "Answer “Artisan Pehchan Card available” first. Yes makes the card number required; No clears and locks the number box.",
      "“Date of birth” and “Craft start date” are required; age and experience follow from them.",
      "The ring on each record shows what is still missing; tap a missing field to fill it in.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "product",
    label: "Product",
    action: "Record product",
    icon: Package,
    href: "/products/new",
    summary: "Record one thing this artisan makes, with its measurements, economics and photographs.",
    why:
      "Dimensions, costs, price and demand are compared across regions. Link the product to its artisan and craft.",
    fields: [
      "Type of workshop",
      "Workshop",
      "Product name (required)",
      "Local name",
      "Product type",
      "Craft (required)",
      "Linked artisan (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Time taken to complete",
      "Size",
      "Length (inches)",
      "Breadth (inches)",
      "Height (inches)",
      "Cost of making",
      "Selling price",
      "Market demand",
      "Raw materials used",
      "Main tools used",
      "Function or use",
      "Remarks",
      "Status",
      "Product media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Choose the linked craft first. The artisan list opens once a craft is chosen and shows only that craft's artisans.",
      "Use “Measure from a photograph”: lay the piece on the grid sheet and mark known squares. Works offline.",
      "Measurements are suggested; you choose to accept them. The photo is saved with the record.",
      "“Document using grid” gives an automatic estimate and needs a connection. Use it for a piece that won't lie flat or can't be marked.",
      "Choosing a linked artisan fills in the artisan name and place; choosing a linked craft fills in the craft name.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "process",
    label: "Process",
    action: "Document process",
    icon: GitBranch,
    href: "/processes?new=1",
    summary: "Walk through how the product is made, one step at a time, filming each step as it happens.",
    why:
      "A photo shows the result; the steps show the knowledge — sequence, hand movements and judgement.",
    fields: [
      "Type of workshop",
      "Workshop",
      "Name of the process (required)",
      "Artisan (required)",
      "Product (required)",
      "What happens in this process",
      "Pre-processes available",
      "Per step: Name of the step (required)",
      "Per step: Record additional information",
      "Per step: Additional context for this step",
      "Per step: Attach media",
      "Status"
    ],
    watch: [
      "Add a step with \"Add Another Step\". Choose Sequential for steps done in order, or Group of activities for things done together.",
      "Video works best for steps. Film the action as it happens instead of posing the result.",
      "Link the process to the product you already recorded, so the two stay connected.",
      "A design workshop report prints “What happens in this process” word for word. Describe the sequence there in your own words, not only as step names.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "tool",
    label: "Tool",
    action: "Record tool",
    icon: Wrench,
    href: "/tools/new",
    summary: "Record the toolkit the artisan uses: what it is made of, how big it is, who made it, what it costs to replace.",
    why:
      "Toolmakers often vanish before the craft does. Maker, tradition and replacement cost show whether tools can still be had.",
    fields: [
      "Type of workshop",
      "Workshop",
      "Toolkit name (required)",
      "Local name",
      "English name",
      "Linked crafts (required)",
      "Linked artisans (fills artisan + place)",
      "Artisan name (required)",
      "Place (required)",
      "Process used in",
      "Material",
      "Years in use",
      "Height (cm)",
      "Width (cm)",
      "Length (inches)",
      "Breadth (inches)",
      "Height (inches)",
      "Thickness",
      "Weight",
      "Radius",
      "Maker",
      "Tradition type",
      "Replacement cost",
      "Suggestions for improvement",
      "Remarks",
      "Status",
      "Process stages",
      "Tool media",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Fill in only the dimensions that suit the tool: a blade has a length and thickness, a wheel has a radius.",
      "Use “Measure from a photograph”: lay the tool on the grid sheet and mark known squares. Works offline and fills inches and centimetres.",
      "“Document using grid” estimates automatically and needs a connection. Either way, you choose whether to accept the number.",
      "\"Process stages\" keeps your photos in the order you take them, so photograph the stages in sequence.",
      "You can also give tools to artisans later from \"Assign tools to artisans\": your own artisans, ones shared with you for editing, or any artisan if you are an admin.",
      DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "questionnaire",
    label: "Questionnaire",
    action: "Take interview",
    icon: ClipboardList,
    href: "/questionnaire?new=1",
    summary: "Sit down with the artisan and work through the interview sections, recording each answer as audio.",
    why:
      "The artisan speaks in their own voice and language. Recordings are transcribed after upload, so you needn't type during the interview.",
    fields: [
      "Interview title (required)",
      "Place",
      "Language",
      "Type of workshop",
      "Workshop",
      "Status",
      "Artisans interviewed",
      "Recording mode",
      "Do not display answer text boxes",
      "Interview audio",
      "Location (GPS fix or map pin)",
      "Per question: \"Record this question\" audio, or typed answer",
      "Interview notes"
    ],
    watch: [
      "There is one interview for each group of artisans. If one already exists for the same artisans, saving adds your answers to it.",
      "The interview is dated automatically from when it was recorded.",
      "Choose the language from the list; Hindi and English are at the top. If an older interview has a language not on the list, it is kept and shown first.",
      "“Do not display answer text boxes” starts on (record only). “Recording mode” sets one recording per question or per section. Set both first.",
      "Answer only the questions you asked. Empty questions stay open for whoever continues the interview.",
      "An answer given by someone else can be changed only by them or an admin.",
      "Use \"Check completion\" at the top of the screen to see which artisans and sections still have gaps.",
      DESIGN_WORKSHOP_FIELD
    ]
  },
  {
    id: "media",
    label: "Miscellaneous Media",
    action: "Upload media",
    icon: Images,
    href: "/media",
    summary: "Upload the photographs, video, audio and files that do not belong to any single record.",
    why:
      "Upload material no form covers — the road in, the market, a chance conversation — so it isn't left on a phone.",
    fields: [
      "Capture media — images, video, audio and documents",
      "Media title / object name",
      "Linked record type (required)",
      "Linked entry (optional)",
      "Design & prototype workshop",
      "Caption",
      "Location (GPS fix or map pin)"
    ],
    watch: [
      "Choose a Linked record type before you upload. If the file doesn't belong to a particular record, choose \"Miscellaneous Media\" and leave the entry blank.",
      "Tick Transcribe and translate on a recording or video to get its text.",
      "If a file turns out to belong to a record, you can link it to that record later.",
      MEDIA_DESIGN_WORKSHOP_FIELD,
      DICTATION_ON_THIS_FORM
    ]
  },
  {
    id: "review",
    label: "Review",
    action: "Track your submissions",
    icon: ClipboardCheck,
    href: "/review",
    summary: "Everything you submit goes into the review queue and comes back Approved, Rejected, or Sent for revision.",
    why:
      "A senior reviewer checks every record before it counts as final.",
    fields: [
      "Pending — submitted, waiting for a reviewer",
      "Approved — final, counted in the dataset",
      "Needs revision — comments explain what to change",
      "Rejected — not going into the dataset"
    ],
    watch: [
      "Unless you are a Professor or above, new records are submitted as Pending. That is normal.",
      "\"Send for revision\" always comes with comments. Read them, fix the record, and save to resubmit it.",
      "Reviewers see submissions from people junior to them; the master admin sees everyone's."
    ]
  },
  {
    id: "view-data",
    label: "View Data",
    action: "Browse records",
    icon: Eye,
    href: "/data",
    summary: "Browse all the records as folders and download a report of any folder.",
    why:
      "Your records filed three ways, viewable in place and downloadable as a spreadsheet.",
    fields: [
      "By workshop — every record filed under the workshop it was made in (the view it opens on)",
      "By uploader — a workshop's records filed under the researcher who uploaded them",
      "By media type — every file filed by what kind of file it is",
      "Download report (.xlsx)",
      "Download any folder as a zip, with content-type filters"
    ],
    watch: [
      "Open a folder, then use the path at the top to move back up. Folders load as you open them.",
      "Transcripts and AI text are shown formatted in the preview.",
      "Downloading the dataset needs permission. If you don't have it, use Search to find records instead."
    ]
  },
  {
    id: "scan",
    label: "Scan a code",
    action: "Open",
    icon: QrCode,
    href: "/scan",
    summary:
      "Point the camera at an artisan card or a prototype tag, or read the code from a picture someone sent you, and open the record it names.",
    why:
      "Scanning a card opens the right record — searching by name can open the wrong one.",
    fields: [
      "Scan with the camera",
      "Upload a picture — or drop one here, or paste it with Ctrl+V",
      "Or type the code printed under the QR",
      "The record it resolved to, with one press to open it"
    ],
    watch: [
      "A picture works as well as the card: a screenshot, an earlier photo, or one forwarded to you. You can paste it straight in.",
      "Hold the card 10–15 cm away with the whole square in view. If the camera won't open, use a picture or type the code.",
      "Scanning here needs a connection. Without signal, scan from the workshop's own Cards & tags page, which works offline.",
      "Scanning inside a stage form links the record to the box you are filling in, instead of opening it.",
      "A code for a record you don't have access to shows “not found”."
    ]
  },

  {
    id: "designer-profile",
    label: "My designer profile",
    action: "Type your own details, once",
    icon: IdCard,
    href: "/designers/profile",
    summary:
      "Your own details in one place: name, institution, qualification, empanelment, photograph, signature and CV.",
    why:
      "New workshops copy your details into stages 1 and 3. Each workshop keeps its own copy.",
    fields: [
      "Name (required)",
      "Name in the local script",
      "Designation",
      "Institution",
      "Department",
      "Qualification (required)",
      "Specialisation",
      "Designer’s experience",
      "Designer’s profile",
      "Phone (required)",
      "Email (required)",
      "Website",
      "Address line",
      "City or town",
      "State",
      "Pincode",
      "Location (GPS fix or map pin)",
      "Empanelment number (required)",
      "Empanelment date",
      "Photograph",
      "Signature",
      "CV"
    ],
    watch: [
      "Name, qualification, phone, email and empanelment number are required. Everything else is optional.",
      "Fill in both the postal address (printed on reports) and the location card (district and map point).",
      "PDF CVs preview here; other formats download. Reports name your CV but don't include it — send it with the report.",
      "Changes here don't reach workshops already under way. To correct one, edit its stage 1 and stage 3.",
      "Only designers, admins and the master admin can open this screen. Without that access, the link shows “Designer access required”."
    ]
  },
  {
    id: "design-workshop",
    label: "Design workshops",
    action: "Open a design workshop",
    icon: Layers,
    href: "/design-workshops",
    summary:
      "Open the design & prototype development workshop you have been added to. The steps below all happen inside it.",
    why:
      "The 22-stage fortnight that ends in a report. It links to the records you made earlier. An admin creates it and adds you.",
    fields: [
      "Start from a recorded workshop — what narrows every reference picker inside the stages",
      "Workshop title (required)",
      "Report template",
      "Type of workshop",
      "Craft",
      "Cluster",
      "State",
      "District",
      "Start date and End date",
      "Designers this workshop is for",
      "Notes"
    ],
    watch: [
      "Empty until an admin creates a workshop and adds you.",
      "Link a Workshop record early so stage pickers show only that workshop's records.",
      "You can see a design workshop if you created it, are an admin, or are named as one of its designers.",
      "The lead designer's profile fills stages 1 and 3 and is named on the report.",
      "It opens and can be filled in without signal. Your work is kept on this device and uploads when you are back online.",
      "Each stage is saved on this device as you go, so you can pick up exactly where you left off.",
      "For designers, admins and the master admin."
    ]
  },
  {
    id: "design-workshop-codes",
    label: "Cards & tags",
    action: "Print the code cards",
    icon: QrCode,
    href: "/design-workshops",
    summary: "Print a code card for every artisan on the roster and a tag for every prototype, and tie each tag to its object.",
    why:
      "Stages 14–16 start by choosing a prototype. Scanning its tag picks the right one.",
    fields: [
      "Artisan cards — one per roster entry",
      "Prototype tags — one per prototype",
      "Print sheet, sized for a home printer",
      "Scan a code back — camera, an uploaded picture, a dropped or pasted picture, or typed",
      "Join cards — print one for a colleague joining this workshop, and cancel the ones nobody needs"
    ],
    watch: [
      "Print them at the start of the fortnight, before there are many prototypes, so every tag is tied on straight away.",
      "You can also scan from the stage index or Scan a code. This screen works offline.",
      "Printing the sheet and scanning a code here both work without signal.",
      "A join card adds a colleague to this workshop. One person per card unless an admin allows more; used cards send the request to an admin."
    ]
  },
  {
    id: "design-workshop-questionnaires",
    label: "My questionnaires",
    action: "Build your own questionnaire",
    icon: FileSpreadsheet,
    href: "/questionnaires",
    summary:
      "Build the questionnaire this workshop needs in a spreadsheet: download the pro-forma, type your questions into it, upload it, and record the answers here.",
    why:
      "Unlike the shared interview, this questionnaire is yours and attached to your workshop. Reports print its answers as an annexure.",
    fields: [
      "Download the pro-forma",
      "Upload a filled-in pro-forma",
      "Or “Start an empty one” — Title, Attach to a design workshop, Kind, Description",
      "Per section: Section title, Code",
      "Per question: Question, Help text",
      "Record answers",
      "Reuse at another workshop",
      "Download question set, or Download .xlsx"
    ],
    watch: [
      "This isn't the shared “Take interview” questionnaire — check you're answering the right one.",
      "The sheet can come back with or without answers typed in. The upload shows what it read before anything is saved.",
      "“Reuse at another workshop” makes a separate copy of the questions. Later edits to one don't change the other, and no answers are copied.",
      "You can create it offline; add sections once it uploads.",
      "A questionnaire published as the standard form appears in everyone's list with a badge. Publish or withdraw it from the questionnaire's own page.",
      "Only designers, admins and the master admin can open this screen. Without that access, the link shows “Designer access required”."
    ]
  },
  {
    id: "design-workshop-stages",
    label: "Stages",
    action: "Fill the stages",
    icon: ClipboardList,
    href: "/design-workshops",
    summary:
      "Work through the 22 stages. The stage index shows how complete each one is; open a stage to fill in its form.",
    why:
      "Each stage is a section of the report. The phone and web show the same form.",
    fields: [
      "Basic fields — the ones “Save and check required fields” checks",
      "Standard and Advanced fields — depth, never a blocker",
      "Reference pickers — choose the artisan, craft, product, tool, process or questionnaire sitting you documented in the steps above",
      "“Create a new …” inside a picker, when the record is missing and you are mid-stage",
      "Photographs, sketches and measurements per stage",
      "Choose a photograph you have already uploaded, instead of uploading it a second time",
      "Photograph galleries that say how many are wanted, with a bar counting what you hold",
      "A microphone on every narrative box",
      "This stage in the document — the report’s own pages, beside the form",
      "Your own sections and questions, added to the workshop"
    ],
    watch: [
      "Choosing a record copies its values into this stage when you save it; the report prints that copy. To pick up later edits, choose the record again.",
      "Choosing a different record clears the boxes the first one filled, so two records never get mixed in one entry.",
      "Choosing a record only fills empty boxes. Anything you typed yourself is kept.",
      "You can attach a photo already in the records instead of uploading it again.",
      "Photos are checked for focus, resolution and duplicates before upload. If one isn't accepted, retake it.",
      "A gallery saves with fewer photos than asked, but the stage shows as incomplete.",
      "Stages 9 and 17 show calculated findings beside your entries. Your figures aren't changed.",
      "The preview beside the form shows the report pages as of your last save. Save the stage to see your latest changes.",
      "Add your own questions on the workshop's screen. It warns you about questions that already have answers."
    ]
  },
  {
    id: "design-workshop-sketches",
    label: "Sketches & prototypes",
    action: "Upload, trace and rank the work",
    icon: PencilRuler,
    href: "/sketches-and-prototypes",
    summary:
      "Choose a workshop, then work on its sketches (stage 11) and prototypes (stage 13) from one screen.",
    why:
      "Choose the workshop, then add and rate work on the Upload and Review tabs.",
    fields: [
      "Which workshop",
      "Upload / Review",
      "Prototypes / Sketches",
      "Photograph to trace",
      "Traced result",
      "The trace against the photograph",
      "Measure a dimension from a photograph",
      "Attach as",
      "Download a copy to this device",
      "360° capture",
      "3D model"
    ],
    watch: [
      "Tracing happens on this device. Cropping and sharpening only affect the trace; your original photograph is kept unchanged.",
      "Straighten a photographed sheet on stage 11 by dragging its corners. It's saved as new line art; the photo is kept.",
      "3D models can be turned and downloaded. Reports print the “360° capture” photos and list the model.",
      "The comparison has four views: Drawing, Wipe, Photograph and Difference. Start with Wipe; use Difference when you need a closer check.",
      "“Attach as” saves SVG or PNG. PDF, EPS and DXF are downloads only.",
      "Record set-aside sketches too. Stage 11 is for designs that were never prototyped, and they can be rated in both rounds.",
      "Only designers, admins and the master admin can open this screen. Without that access, the link shows “Designer access required”."
    ]
  },
  {
    id: "design-review",
    label: "Design review",
    action: "Rate and rank a colleague’s work",
    icon: Star,
    href: "/design-review",
    summary:
      "Score a colleague’s sketches and prototypes out of five, say what you would change, and put them in order.",
    why:
      "Round two: after peer review, a wider group rates finished pieces. They see only pieces and scores.",
    fields: [
      "A workshop you can open yourself",
      "Or any other workshop, from its link or its id",
      "Prototypes / Sketches",
      "Your score for this piece (1 to 5)",
      "What you think of it",
      "What you would change",
      "Move up / Move down, or drag to reorder"
    ],
    watch: [
      "The dropdown lists workshops you can open. To review another workshop, paste its link or id into the box.",
      "A piece is ready once its peer review has closed.",
      "Keep “What you would change” separate from your comments, so the designer can find it easily.",
      "You rank pieces within one workshop at a time.",
      "Only designers, admins and the master admin can open this screen. Without that access, the link shows “Designer access required”."
    ]
  },
  {
    id: "design-workshop-readiness",
    label: "Readiness",
    action: "See what is still outstanding",
    icon: ListChecks,
    href: "/design-workshops",
    summary: "One screen answering: what is still outstanding on this workshop?",
    why:
      "Lists empty required fields first, then report checks, then optional gaps. Each line links to its stage.",
    fields: [
      "Unfilled Basic fields — what a stage check is waiting for",
      "Report checks — notes on the finished file, never a block",
      "Standard and Advanced gaps — counts you can expand",
      "A link straight into the stage that holds each gap"
    ],
    watch: [
      "Reports can be handed in with empty fields.",
      "“Save and check required fields” lists a stage's empty required fields.",
      "Use it on the first afternoon as well as the last, to plan the fortnight.",
      "Standard and Advanced counts never block anything. They help you decide where to add more detail."
    ]
  },
  {
    id: "design-workshop-report",
    label: "Report",
    action: "Configure and generate the report",
    icon: FileOutput,
    href: "/design-workshops",
    summary:
      "Choose the template, set what the document contains, read it back as real A4 or Letter pages, and download the .docx or .pdf.",
    why:
      "Choose a template and colour; the preview matches the .docx and .pdf.",
    fields: [
      "Report template",
      "Transcripts in this file",
      "Include machine-assisted text",
      "Report colour",
      "Download .docx",
      "Download .pdf",
      "Print these pages"
    ],
    watch: [
      "Warnings never block the file. They're listed beside the download.",
      "Warnings appear only on this screen, never inside the document, so read them before you send the file.",
      "The accent colours run from dark to light so they stay distinct when printed in black and white.",
      "Stage 20 holds these settings. It sets up the report and is not printed in it.",
      "To correct something in the preview, open the stage it came from and edit it there.",
      "You can also generate the report offline on the phone app."
    ]
  },
  {
    id: "design-workshop-history",
    label: "Report history",
    action: "Review what you have produced",
    icon: History,
    href: "/design-workshops",
    summary:
      "Every generated file, with its details, and a comparison between any two.",
    why:
      "See exactly what changed between versions.",
    fields: [
      "Every generated file, newest first",
      "Template",
      "Pages",
      "Size",
      "Generated by",
      "SHA-256 of the file",
      "Compare … With — any two files"
    ],
    watch: [
      "Files here can't be edited or removed, so the history stays a reliable record.",
      "The comparison is between two generated files. To see who changed a value and when, open the stage's provenance."
    ]
  },
  {
    id: "design-workshop-inspection",
    label: "Workshops to inspect",
    action: "Read a finished workshop",
    icon: FileSearch,
    href: "/design-workshop-inspections",
    summary:
      "The inspector's own list: the design & prototype workshops they have been assigned, with every stage readable and nothing editable.",
    why:
      "Every value shows who entered it and when.",
    fields: [
      "Workshops to inspect — the ones assigned to you, searchable by title, craft, cluster or workshop code",
      "Workshop under inspection — all 22 stages, read-only",
      "Who wrote each field, and when",
      "How complete the workshop is"
    ],
    watch: [
      "For inspectors and appointed admins. Each sees only their assigned workshops.",
      "A Ministry Admin, an admin or the master admin chooses who inspects each workshop, on Workshop oversight.",
      "If nothing is assigned to you, the page says so. If the list couldn't be loaded, it says that instead.",
      "Read-only. Inspectors can suggest corrections or send the report back for revision.",
      "Photographs, recordings and attachments are shown here read-only, and each workshop's own questions are printed beside their answers."
    ]
  }
];

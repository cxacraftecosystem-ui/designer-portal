# Design Prototype Workshop — the capture walkthrough (for designers)

This is the whole capture process, in the order you actually perform it: from opening a design &
prototype development workshop, through each stage of it, to generating the report and exporting
the finished dataset.

> **That sentence promised more than the body delivered, from the day it was written until
> 2026-08-19.** The ten numbered steps below are the REPOSITORY RECORD forms — Craft, Artisan,
> Product, Process, Tool, Questionnaire, Miscellaneous Media — and they never once mentioned the
> 22-stage design & prototype workshop, the stage form, the readiness check, the code cards or
> generating the report for the ministry officer. That is the one deliverable the fortnight exists
> to produce. It now has a section of its own, *[The design & prototype workshop
> itself](#the-design--prototype-workshop-itself)*, which comes AFTER the ten steps because that is
> the order you work in: the records exist first, and the stage form points at them.

The same guide is available inside the app at **`/guide`** ("Walkthrough"), where each step links
straight to the screen it describes. This document is the version you can print, email, or read on
the bus on the way to the village.

> **The two renderings are back in step as of 2026-08-26.** `frontend/components/guide/steps.ts`
> declares nineteen steps: the ten record ids (`workshop`, `craft`, `artisan`, `product`, `process`,
> `tool`, `questionnaire`, `media`, `review`, `view-data`) and then `designer-profile`,
> `design-workshop`, `design-workshop-codes`, `design-workshop-stages`, `design-workshop-sketches`,
> `design-review`, `design-workshop-readiness`, `design-workshop-report`,
> `design-workshop-history`. Each of the nine has a section below under the same name. The note that
> stood here from 2026-08-19 recorded the divergence as a debt; it is paid, and the maintenance rule
> at the foot of this file is the thing that keeps it paid.

The screens carry the **same names on the web and in the Android app**. Wherever this document
names a screen — *Artisan*, *Product*, *Process*, *Tool*, *Questionnaire*, *Miscellaneous Media*,
*View Data* — that is the name on the dashboard tile in both places.

---

## The process in one line

**Workshop → Craft → Artisan → Product → Process → Tool → Questionnaire → Miscellaneous Media →
Review → View Data**

Then, for the fortnight that those records feed:

**My designer profile → Design workshops → Cards & tags → Stages → Sketches & prototypes →
Design review → Readiness → Report → Report history**

Learn those two orders and you can work without the guide.

---

## Two things to know before you start

1. **Every record is scoped to a workshop.** Products, tools and interviews all carry a linked
   workshop, and the Data Browser opens on *By workshop*, filing the entire repository under the
   workshop each record was made in. On a create
   form, the most recent workshop you have access to is preselected — so getting the workshop right
   once saves you picking it on every screen afterwards.
2. **Everything you submit is reviewed.** Below the Professor tier the status chip on every form is
   locked: whatever you create is submitted as **Pending**. That is normal, not an error. A reviewer
   ranked above you then Approves it, Rejects it, or Sends it for revision with comments.
3. **Creating records needs Researcher access.** If you signed in with Google you started at the
   lowest tier and there will be no *New …* buttons — you can add media, answer open interviews and
   comment, but not open a new record. Ask an admin to promote you.

---

## Location: the one thing every form asks twice

Every capture screen ends with a location control, and it collects **two different things** that look
like one. Getting this wrong has already corrupted the dataset once.

| | What it is | Who fills it |
| --- | --- | --- |
| **Device fix** | Where *you* are standing while typing. GPS coordinates plus an accuracy radius. | Captured automatically |
| **Stated address** | Where the *artisan or workshop* is: state, district, village, pincode. | **You**, deliberately |

On the live database, every artisan carrying a location sits within a few hundred metres of one point
in Kharagpur, West Bengal, while the places their researchers typed are Bagru, Balotra, Kutch,
Rudraprayag, Ballupur, Sanganer and Kappaladoddi. Those coordinates were never wrong — they are real
fixes **of the desk each record was typed at**, which is entirely reasonable behaviour that the form
had no way to express. So the researchers hand-encoded the real village into the free-text *Place*
box, because there was nowhere else to put it.

**Fill in the stated address.** The device fix fills itself. If the two disagree wildly the form says
so — that is information, not an error.

> This control is being reworked as this guide is written, so the wording on screen may differ. The
> distinction itself is settled.

---

## 1. Workshop — *Record workshop*

**Screen:** Workshop (`/workshops`)

Open the workshop you are documenting under — or create it — before you record anything else.

**What the screen asks for:** Workshop title *(required)*, Place *(required)*, start and end date,
Description, Notes, Linked artisans, Crafts covered, Workshop media, Location (GPS fix or map pin).

**Why it exists.** The workshop is the container everything else drops into. Products, tools and
interviews all link to one, and *View Data* opens on **By workshop**, which files the whole
repository under the workshop each record was made in.

**Watch out for:**

- Create the workshop *before* you leave for the field.
- Records created outside a workshop's date window are flagged as **out-of-window** and need a
  reviewer's approval.

---

## 2. Craft — *Add craft*

**Screen:** Craft (`/crafts`)

Add the craft being documented so artisans, products and tools have something to hang off.

**What the screen asks for:** Craft name *(required)*, Local name, Category, Place, Description,
Craft media.

**Why it exists.** Craft is the shared vocabulary of the repository: artisans link to a craft,
products and tools inherit the craft name from it, and the Data Browser groups every workshop's
contents by craft. Adding it once keeps spellings consistent across everyone's records.

**Watch out for:**

- Check the list first — if the craft already exists, reuse it rather than creating a near-duplicate
  spelling.
- The local name matters as much as the English one. Record what the community actually calls it.

---

## 3. Artisan — *Record artisan*

**Screen:** Artisan (`/artisans/new`)

Record the person: who they are, where they work, how to reach them, and what they have learnt.

**What the screen asks for:** Name *(required)*, Local name, Workshop, **Craft** *(required)*, Or new
craft name, Place *(required)*, **Aadhaar number**, **Artisan Pehchan Card available** and its number,
Gender, Phone, Email, Address, Notes, **Do's (positive prompt)** *(required)*, **Don'ts (negative
prompt)** *(required)*, Artisan media, Location (device fix **and** stated address).

**Why it exists.** The artisan is the anchor of the dataset — products, processes, tools and
questionnaire interviews all link back to an artisan record. The Do's and Don'ts are the artisan's
own hard-won craft knowledge: the part of the archive that cannot be reconstructed later.

**Watch out for:**

- Do's and Don'ts are **required**. Press <kbd>Enter</kbd> for each new point — one lesson per line.
- You must either select an existing craft **or** type a new craft name. The form will not save with
  neither.
- **The Aadhaar number is the deduplication key.** It is checked as you type and again on save; if
  the artisan is already in the archive you are shown the existing record. That is the field working —
  add to that record rather than making a second one. The number is validated (a mistyped digit is
  caught), and everywhere the data is *shared* it appears masked as `XXXX XXXX 9012`.
- **If you are shown a mask, leave it alone.** Saving a form with the mask still in the box is
  recognised as "unchanged"; you do not have to retype the number.
- **Pehchan card**: answer Yes/No first. Answering Yes makes the card number required; answering No
  clears it. It is not possible to store a card number for an artisan who says they hold no card.
- Photo EXIF is retained and summarised into the notes automatically. Do not transcribe camera
  details by hand.
- **Location asks two different things** — see the box below.

---

## 4. Product — *Record product*

**Screen:** Product (`/products/new`)

Record one thing this artisan makes, with its measurements, economics and photographs.

**What the screen asks for:** Product name *(required)*, Local name, Workshop, Product type, Linked
craft (fills craft name), Craft name *(required)*, Linked artisan (fills artisan + place), Artisan
name *(required)*, Place *(required)*, Time taken to complete, Size, Length (inches), Breadth
(inches), Height (inches), Cost of making, Selling price, Market demand, Raw materials used, Main
tools used, Function or use, Remarks, Product media, Location (GPS fix or map pin).

**Why it exists.** The product record is where the craft becomes measurable. Dimensions, cost of
making, selling price and market demand are the fields researchers compare across regions.

**Watch out for:**

- **Pick the linked craft first.** The artisan dropdown stays disabled until a craft is chosen, and
  then only lists that craft's artisans.
- Use **"Document using grid"** to photograph the piece against the measuring grid: it fills length,
  breadth and height for you *and* stores the photo as evidence.
- Choosing a linked artisan fills the artisan name and place; choosing a linked craft fills the
  craft name.

---

## 5. Process — *Document process*

**Screen:** Process (`/processes`)

Walk through how that product is made, one step at a time, filming each step as it happens.

**What the screen asks for:** Name of the process *(required)*, Artisan *(required)*, Product
*(required)*, then per step: Name of the step *(required)*, optional additional context notes, and
attached media.

**Why it exists.** The process is the craft itself. A product photograph shows the result; the
step-by-step record with per-step media shows the *knowledge* — the sequence, the hand movements,
the judgement calls that a text description always loses.

**Watch out for:**

- Add a step with **"Add Another Step"**, then pick **Sequential** for an ordered stage or
  **Group of activities** for things done together.
- **Video is the preferred format for steps** — capture the action as it happens rather than posing
  the result.
- Document the process against the product you already recorded, so the two stay linked.

---

## 6. Tool — *Record tool*

**Screen:** Tool (`/tools/new`)

Record the toolkit the artisan uses: what it is made of, how big it is, who made it, what it costs
to replace.

**What the screen asks for:** Toolkit name *(required)*, Local name, English name, Workshop, Linked
craft (fills craft name), Craft name *(required)*, Linked artisan (fills artisan + place), Artisan
name *(required)*, Place *(required)*, Process used in, Material, Years in use, Height, Width,
Length (inches), Breadth (inches), Thickness, Weight, Radius, Maker, Tradition type, Replacement
cost, Suggestions for improvement, Remarks, Process stages, Tool media, Location (GPS fix or map
pin).

**Why it exists.** Tools are the most quietly endangered part of a craft — the maker of a tool often
disappears before the craft does. Replacement cost, maker and tradition type are the fields that
record whether the toolchain behind the craft is still alive.

**Watch out for:**

- Fill only the dimensions that make sense for the tool. A blade has a length and a thickness; a
  wheel has a radius.
- **Process stages** archives your captures in order as `STAGE_STEP_1`, `STAGE_STEP_2`, … so shoot
  them in sequence.
- You can also hand tools to specific artisans later from **Assign tools to artisans** — for your
  own artisans, ones shared with you for editing, or any artisan if you are an admin.

---

## 7. Questionnaire — *Take interview*

**Screen:** Questionnaire (`/questionnaire`)

Sit down with the artisan and work through the interview sections, recording each answer as audio.

**What the screen asks for:** Interview title *(required)*, Date, Place, Language, Primary artisan,
Additional artisans, then per question either a **"Record this question"** audio clip or a typed
answer.

**Why it exists.** The questionnaire is the artisan speaking in their own voice and their own
language. Recorded audio is auto-transcribed on the server, so you get both the original recording
and searchable text without typing during the interview.

**Watch out for:**

- **There is one interview per exact set of artisans.** If an entry already exists for that set,
  saving adds your answers to it — it never creates a duplicate.
- Answer only the questions actually asked. Empty questions stay open for whoever picks the
  interview up next.
- Questions already answered by someone else can only be changed by that contributor or an admin.
- Use **"Check completion"** at the top of the screen to see the artisans × sections matrix and find
  the gaps.

---

## 8. Miscellaneous Media — *Upload media*

**Screen:** Miscellaneous Media (`/media`)

Upload the photographs, video, audio and files that do not belong to any single record.

**What the screen asks for:** Capture media (images, video, audio and documents), Media title /
object name, **Linked record type** *(required)*, Linked entry *(optional)*, Caption, Location (GPS
fix or map pin).

**Why it exists.** Field work produces context that no form has a slot for: the road into the
village, the market, an unplanned conversation. Miscellaneous Media keeps that material inside the
repository instead of on a phone that gets wiped.

**Watch out for:**

- **Upload stays disabled until you pick a Linked record type.** If the file belongs to nothing in
  particular, pick **Miscellaneous Media** and leave the entry blank.
- Audio uploaded here is queued for transcription after upload, exactly like interview audio.
- If the file does turn out to belong to a record, link it — misc media can be attached to a record
  afterwards.

---

## 9. Review — *Track your submissions*

**Screen:** Review (`/review`)

Everything you submit goes into the review queue and comes back **Approved**, **Rejected**, or
**Sent for revision**.

| Status | What it means |
| --- | --- |
| **Pending** | Submitted, waiting for a reviewer. |
| **Approved** | Final, counted in the dataset. |
| **Needs revision** | Comments explain what to change. |
| **Rejected** | Not going into the dataset. |

**Why it exists.** Review is what turns a pile of field notes into a dataset anyone can cite. It
also means you are never the last check on your own work.

**Watch out for:**

- Below Professor the status chip is locked: whatever you create is submitted as **Pending**.
- **"Send for revision" always carries mandatory comments.** Read them, fix the record, and saving
  resubmits it as Pending.
- Reviewers only see submissions from contributors ranked strictly below them; the master admin sees
  everyone.

---

## 10. View Data — *Browse records*

**Screen:** View Data / Data Browser (`/data`)

Browse the whole repository as a directory tree and export a report of any subtree. The root offers
the same records filed three ways:

- **By workshop** *(the view it opens on)* — every record filed under the workshop it was made in.
- **By uploader** — a workshop's records filed under the researcher who uploaded them.
- **By media type** — every file filed by what kind of file it is.

From any folder you can preview media and transcripts, download the folder as a **zip** with
content-type filters, or take the whole subtree as a **`.xlsx` report**.

**Watch out for:**

- Pick a folder, then use the breadcrumb to move back up. The tree loads lazily as you expand it.
- Transcripts and AI text render as formatted Markdown in the preview pane, not raw text.
- Dataset download is a **granted permission**. If your role does not have it, the browser shows a
  restricted notice — use **Search** to find records instead.

---

## Before you leave the field

A missing field is a phone call. A missing recording is another trip. Run this list while the
artisan is still in front of you.

- [ ] Every artisan you spoke to has a record, with Do's and Don'ts filled in.
- [ ] Every product you photographed has its dimensions, cost of making and selling price.
- [ ] Every process has its steps in order, and the steps have video.
- [ ] Every tool has a material, a maker and a replacement cost.
- [ ] The questionnaire's completion matrix has no unexplained gaps.
- [ ] Anything you shot that has no home is uploaded to Miscellaneous Media.

---

## The design & prototype workshop itself

Everything above fills the **repository** — the artisans, products, processes and tools that exist
independently of any one fortnight. This section is the **workshop**: the 22-stage form you fill in
the field, and the report that comes out of it and goes to a ministry officer. It is the deliverable.

**Where the two meet:** a stage field marked as a reference asks you to *choose* a repository record,
and choosing it **copies** that record's display values onto the stage there and then. The report
prints the copy. So a record edited or deleted next month cannot change a document already handed
over — and, the other way round, a correction you make on the Artisan form after you have picked it
does **not** reach a stage you already filled. Re-pick the record if you need the new values, or type
them.

Four rules worth knowing before you open stage 1:

- **You cannot make the form shorter by leaving things out.** Only **Basic** fields are scored and
  only Basic fields can be required; **Standard** and **Advanced** are shown but never block a
  submission. A workshop held with no power and no measuring equipment can still produce a complete
  report — that is what the tiers are for. Advanced fields sit behind a *More detail* disclosure.
- **Everything on these screens works with no signal.** The stage form, the readiness list and the
  code sheets are all built from the local draft on the device; the server read that follows only
  refreshes it. Every stage also **saves on its own as you go**, into that local draft, so you
  resume exactly where you left off and a fortnight of fieldwork survives with no connection.
- **A workshop may be submitted part-filled, and this is the rule to trust.** *Mark complete* and
  *Submit*, on the workshop's own Submission card, record where the whole workshop stands and are
  **never refused for an empty field**. Exactly one act in the app is: *Save and check required
  fields*, the second button at the foot of any stage, which saves the stage either way and then
  refuses **that one stage** while any of its Basic fields is empty, naming the ones it is waiting
  for. Those two facts are printed in these words on both screens that quote them.
- **This whole section is for designers, admins and the master admin.** `/designers/profile`,
  `/design-workshops`, `/design-review` and `/sketches-and-prototypes` each carry a route guard on
  `can_run_design_workshops`. The Walkthrough itself is deliberately ungated — it teaches the
  process to people who have not been granted anything yet — so a researcher can read every step
  below and open none of them. Ask an admin to empanel you as a designer.

### Step A — Fill in your designer profile

**Screen: My designer profile** (`/designers/profile`) · Android: *My designer profile*

Your own standing details, kept in one place rather than typed into a stage form: **Name**, **Name in
the local script**, **Designation**, **Institution**, **Department**, **Qualification**,
**Specialisation**, **Designer's experience**, **Designer's profile** (the paragraph), **Phone**,
**Email**, **Website**, **Address**, **City or town**, **State**, **Pincode**, **Empanelment
number**, **Empanelment date**, **Photograph**, **Signature** and **CV** — twenty-one in eight
groups.

These are the values a new design workshop's **stage 1** and **stage 3** start pre-filled with, and
they stay editable inside the workshop. A designer signing in for the first time is brought here
automatically, once, with an explanation of why.

- **Nothing here is required and nothing is guessed for you.** Left blank, the report cover falls
  back to the name on your account.
- **A PDF CV is rendered on the page** as soon as it uploads; a .docx or .odt is stored and
  downloadable instead. **Your reports name it rather than carrying it**, so send the file alongside
  the report.
- **They are copies, and they must stay copies.** A report records who ran a workshop *at the time*,
  and it is printed from the stages rather than from this page. Moving institution next year must not
  rewrite last year's report, so each workshop keeps its own copy — editable on its stage 1 and stage
  3 — and correcting something here never reaches back into a workshop already under way.

### Step B — Open the workshop

**Screen: Design workshops** (`/design-workshops`) · Android: *Design workshops*

Open the workshop you have been added to. Everything below hangs off it.

**A designer does not start one.** An admin creates the workshop and adds you to it; everything
**inside** it is then yours. So this list is empty until that happens, and for a newly empanelled
designer that is the ordinary state rather than a fault — the screen says who to ask.

Link it to a **Workshop record** early. The reference pickers inside the stages are narrowed to that
workshop's artisans, products and tools; an unlinked workshop offers the whole repository instead, and
the screen says which you are looking at.

### Step C — Print the code cards, and get the team onto one workshop

**Screen: Cards & tags** (`/design-workshops/[id]/codes`) · Android: *Cards & tags*, from the stage index

Prints a code for every artisan on the roster and every prototype in the workshop, to tie to the
object. Stages 14, 15 and 16 each begin by choosing a prototype from a list as long as the fortnight
made it; scanning a tag removes the choosing, and choosing wrong is how two days of measurements end
up attached to somebody else's work with nothing downstream able to tell. **Print them on the first
afternoon** —
a tag tied on at the end of the fortnight is a tag tied on from memory.

Scanning back is offered four ways — the camera, an uploaded picture, a dropped or pasted picture, or
typed — and the decode happens in the browser, so it works with no signal. The sheet prints from the
browser off the local draft for the same reason.

**A join card is a different code doing a different job.** One person creates the workshop and the
others scan a card to join **the same one**, which is what stops a team ending the fortnight with
four parallel workshops nobody can merge. It is minted and scanned **on the handset** — there is no
join-card screen on the web — and a card is good for one person unless an admin makes it good for
more. A late-comer whose card was already spent is not turned away: the ask is filed for an admin to
decide, so their work is not orphaned while they wait.

### Step D — Fill the stages

**Screen: the workshop** (`/design-workshops/[id]`) → **a stage** (`/design-workshops/[id]/stages/[stageKey]`)
Android: the stage index, then the stage screen.

The stage index lists all 22 stages with a completeness figure against each. Open one and you get a
form built from the registry the server publishes, which is why the web and the phone show the same
boxes in the same order.

Some stages compute a finding beside what you typed rather than instead of it: stage 9 holds your
declared price bands up against the stage-8 survey, and stage 17 checks each cost sheet against its
own line items. **Nothing there ever overwrites what you wrote** — you were in the room and the
arithmetic was not. Both appear on the stage form itself, on the handset and in the browser: the
market panel above stage 9's tables, the cost panel above stage 17's.

**The document, beside the form.** A panel on the stage screen draws the report's own pages for the
slice this stage contributes, so you can see how four paragraphs will actually print before you
leave the screen. It **follows the saves, not the keystrokes** — it is the real document built by the
server from the same model the .docx and .pdf writers consume, not a browser sketch of one, so it
cannot show an edit you have not saved yet and it says so on itself.

**Your own sections and questions.** A workshop can be given sections and questions that no
deployment added, and they print in its report. They are written on the workshop's own
*custom sections* screen rather than on a stage, because a definition is replaced as one whole set;
the editor tells you what an edit will cost a question somebody has already answered **before** you
press anything. Reorder with the plus button, the up/down arrows, or by dragging.

### Step E — Sketches and prototypes

**Screen: Sketches & prototypes** (`/sketches-and-prototypes`, or the workshop's own tab at
`/design-workshops/[id]/sketches-and-prototypes`) · Android: *Sketches & prototypes*

The same screen from two ends. The top-level route asks **which workshop** first, which is what you
actually know when you are standing there with the drawing in your hand; the workshop's own tab is
for when you walked in through the workshop. One component renders both, so they cannot drift.

Two tabs — **Upload** and **Review** — over two kinds, **Prototypes** and **Sketches** (stages 13 and
11). The upload half carries the tracing panel: **Photograph to trace**, **Traced result**, **The
trace against the photograph**, **Attach as**, **Download a copy to this device**, plus **360°
capture** and **3D model** on a prototype.

- **The tracing is arithmetic on this device.** The crop and the sharpening feed the *trace* and
  nothing else — they cannot re-encode your photograph, so the original file stays the artifact,
  EXIF and all.
- **Straightening a photographed sheet into a plate is a second panel, and it is on the stage 11
  form rather than here.** Drag the four corners of the sheet on the photograph and a local threshold
  turns it into black line on white paper. It writes the plate into the same **Line art / vector
  file** slot this screen fills — a new file, never over your photograph — and every step of it is
  arithmetic on the device, so it works where the sketch was drawn. `frontend/lib/sketchRectify.ts`
  is the arithmetic, `components/designworkshop/SketchRectifyField.tsx` the panel, mounted from
  `FieldInput.tsx` wherever `stageFieldRoles.offersSketchRectify` matches; the handset twin is
  `ui/designworkshop/DwSketchRectifyField.kt`.
- **A 3D model file is stored and downloadable, and nothing in either client draws it.** *360°
  capture* is the view a reviewer actually sees and the one the report prints; a model file prints as
  the words "1 document attached".
- **Set-aside sketches count.** Stage 11 exists to record the designs that were never prototyped,
  and they are rateable in both rounds — a wider pool picking one up is the reason to write them
  down at all.

### Step F — Design review

**Screen: Design review** (`/design-review`) · Android: *Design review*

Score a colleague's sketches and prototypes out of five, say what you would change, and put them in
an order: **Your score for this piece** (1 to 5), **What you think of it**, **What you would change**,
and **Move up / Move down** or drag to reorder.

Two ways in, and they are not two spellings of one control: **A workshop you can open yourself** is a
dropdown of your own workshops, and **Or any other workshop, from its link or its id** is a box —
because the pool round is by design about workshops you were never added to. Then **Prototypes** or
**Sketches**.

**Two rounds, and this screen is the second.** The workshop's own designers rate each other first, on
the *Review* tab of Step E. Then the wider pool ranks the pieces a workshop has finished — including
workshops the reviewer was never added to, which is the whole difference between the two levels. A
pool reviewer sees the rateable rows and their scores and **nothing else about the workshop**: they
are not a member of it and cannot write to its stages.

- A piece reaches the pool only once its peer round is closed — **Peer review closed on**, on the
  piece itself. A workshop with nothing open in the kind you chose says so in one sentence rather
  than showing an empty list.
- The comment and the suggestion are two boxes on purpose. *What you would change* is the half a
  maker acts on, and it is unfindable buried inside a paragraph of assessment.
- The order you place is stored on the row, inside one workshop — which is why the round is asked one
  workshop at a time and there is no mixed cross-workshop list to rank.

### Step G — See what is still outstanding

**Screen: Readiness** (`/design-workshops/[id]/readiness`) · Android: the same list, on the stage
index rather than a separate screen.

One screen answering *what is still outstanding on this workshop?* Unfilled Basic fields first — what
a **stage check** is waiting for; the report's own checks next, because they change the delivered file
without refusing it; Standard and Advanced gaps last, as counts behind a disclosure. Every line links
into the stage that holds it. Use it on the **first** afternoon as well as the last: it is a plan for
the fortnight, not only a check at the end.

Read it against the third rule at the top of this section. Nothing on this list refuses the
**workshop's** submission — a workshop may be submitted part-filled. What these fields refuse is
*Save and check required fields* on the one stage that holds them.

### Step H — Configure and generate the report

**Screen: Report** (`/design-workshops/[id]/report`) · Android: *Report*

Choose the template, set what the document contains, read it back as **pages** — laid out at real A4
or Letter dimensions, with the breaks the template declares marked — and download the file. There are
**six templates** (*DCH standard workshop report*, *DIC standard workshop report*, *Implementing
agency format*, *Compact summary*, *Detailed technical report*, *Photo catalogue*), **twelve named
accent colours and a colour well**, and picking one redraws every page on screen before a single file
is made. The preview is drawn from the same document model the .docx and .pdf writers consume — and
the two on-device writers as well — so what you read is what is generated.

**A warning never stops a file being produced.** A required field nobody filled in, a photograph the
report could not embed, a gallery over the template's cap, an attached file the report names and does
not contain — each is reported **beside the download** and the document is generated anyway, because
the pages that *are* ready are the ones you need. And those warnings **never travel inside the
document**: an officer opening the .docx next month must not find a note about what was missing on
the day, which is also why the screen is the only place they can be read at all.

The accent ladder runs from navy to burnt orange by **lightness**, not by hue, because these get
printed on monochrome office lasers where hue is discarded — twelve equally dark colours would come
out of that tray as twelve identical reports.

A report generated **on the phone** honours the same template and settings and needs no connection at
all. **Two** annexures it cannot draw are named on the file itself: recorded transcripts (produced
after the audio reaches the server) and machine-assisted text. **Questionnaire answers are drawn on
the phone too** — but only once that handset has opened the workshop's questionnaire list at least
once with a connection, and until then the file says so.

### Step I — Look at what you have already produced

**Screen: Report history** (`/design-workshops/[id]/report/history`)

Every file ever generated for this workshop, including ones a phone produced offline, with its
checksum, size, page count, template and timestamp — and a diff between any two. A report submitted
to a ministry comes back for revision three or four times, and "did you update the cost sheet before
you resubmitted?" needs an answer.

---

## Where to go next

| Screen | What it is for |
| --- | --- |
| **Dashboard** (`/dashboard`) | Every screen in this guide, one tap away. |
| **Review** (`/review`) | What is waiting on a decision. |
| **My Activity** (`/activity`) | Everything you have recorded so far. |
| **Search** (`/search`) | Find a record across the repository. |
| **Sharing** (`/sharing`) | Give a colleague access to your records. |
| **Workshop access** (`/workshop-access`) | Request access to a workshop, or (admins) decide requests. |
| **Tasks** (`/tasks`) | What you have been asked to document. |
| **Map** (`/map`) | The repository plotted geographically. |
| **Design workshops** (`/design-workshops`) | The 22-stage workshop form and the report that comes out of it — see *[The design & prototype workshop itself](#the-design--prototype-workshop-itself)* above. It was missing from this table until 2026-08-19, which meant every route this guide offered led back to a repository record form. |
| **My designer profile** (`/designers/profile`) | The twenty-one standing details a new workshop's stage 1 and stage 3 start pre-filled with. Kept in one place. |
| **Sketches & prototypes** (`/sketches-and-prototypes`) | Stage 11 and stage 13 of any of your workshops, from one screen, with the tracing panel. |
| **Design review** (`/design-review`) | The pool round: rank a colleague's finished pieces. |
| **Give app feedback** (`/feedback`) | Tell us what slowed you down. |

For installing the app, getting an account, working offline and getting the data back out, see
**[RESEARCHER_GUIDE.md](RESEARCHER_GUIDE.md)**. For the exact permission rules behind "a reviewer
ranked above you", see **[PERMISSIONS.md](PERMISSIONS.md)**.

---

## How this document is kept true

This document describes **screens and their fields**, which no test asserts and no script can derive.
It is maintained by walking it.

| Section | Checked against |
|---|---|
| The field list on each step | The form component: `frontend/components/forms/ArtisanForm.tsx`, `ProductForm.tsx`, `ToolForm.tsx`, `ProcessForm.tsx`, and the questionnaire page. `grep -oP 'label="[^"]+"'` over a form gives its labels in one command; diff that against the step's field list. |
| Which fields are **required** | The same components' validation, and the Pydantic schemas in `backend/app/schemas/records.py`. A field marked *(required)* here that is optional there is the error to look for — it makes the guide stricter than the product, which reads as a bug to the researcher. |
| The route in each **Screen:** heading | The `(protected)` route tree. `docs/tools/check-docs.mjs` does not check these (they are app routes, not files), so they are the most likely thing here to be stale after a page moves. |
| The nineteen-step order | `frontend/components/guide/steps.ts`, which is what `frontend/app/(protected)/guide/page.tsx` renders — the in-app Walkthrough. **These two must not diverge**, because a researcher may read either. Ten record steps then nine workshop steps, matching Steps A–I below one for one. They diverged from 2026-08-19 to 2026-08-26 and the notes recording that debt have been removed with it; renaming or adding a step is an edit to both files in one commit. |
| The design & prototype workshop steps | The `(protected)/design-workshops/` route tree for the routes, [DESIGN_WORKSHOP.md](DESIGN_WORKSHOP.md) §3 for the tier rule, and each page's own file header for what the screen is for — those headers are unusually full and are the source this section was written from. **The one claim here that is not a screen description is the reference rule** ("choosing a record copies its values; the report prints the copy"); its authority is `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py` and [REPORT-DATA-WIRING.md](REPORT-DATA-WIRING.md). Do not soften it into "the report shows the linked record" — that is the opposite of what the system does, and the difference is a document already handed to an officer changing under him. |
| Who may open Steps A–I | `ROUTE_GUARDS` in `frontend/lib/permissions.ts` and [PERMISSIONS.md](PERMISSIONS.md) §5. All four designer paths sit on `can_run_design_workshops`, and the Walkthrough itself is deliberately ungated — so this section describes screens most of its readers cannot open. Say so, as the fourth rule at the top of the section does; do not quietly drop the arc, which is the deliverable the fortnight exists for. |
| **What the app does not do** | One claim was checked and deliberately left out, and it is the one most likely to be "restored" by a reader who remembers a brief rather than the code. **There is no 3D viewer**: `frontend/components/sketches/upload/PrototypeModelField.tsx` states that no dependency in `frontend/package.json` can render a model, that the file is stored and downloadable, and that it prints as "1 document attached". A guide asserting a feature that does not exist is worse than one that omits it. **The converse is worse still, and this row shipped it.** A second entry here asserted there is no plate straightening, on the evidence that `frontend/lib/trace/imageEdit.ts` is a crop and an unsharp mask with no deskew anywhere under `frontend/lib/trace/`. That evidence was accurate and the conclusion was not: `lib/trace/` is the *tracing* panel, and the straightening ships in `frontend/lib/sketchRectify.ts` for a different registry field (see Step E, which now describes it). So: absence proved inside one directory is not absence from the product, and a "does not do" row is the one kind of claim that tells the next reader not to look. Prove a negative over the whole tree or do not write it. |
| Statuses in step 9 | `RecordStatus` in `backend/prisma/schema.prisma`; the authority on who may set which is [PERMISSIONS.md](PERMISSIONS.md). |

**The real maintenance procedure:** this document and the in-app `/guide` are two renderings of one
thing. When a form changes, update both in the same commit — the in-app version is the one
researchers actually read, and this one is the version that gets printed and carried into the field.

**Review triggers:** any file under `frontend/components/forms/`, the guide page, or a new step in the
documentation workflow.

**Known unverified:** the Android screens are asserted to carry the same names and the same fields as
the web ones. That parity is real as a design rule and is **not** mechanically checked; if a field
exists on one client and not the other, nothing in this repository will notice.

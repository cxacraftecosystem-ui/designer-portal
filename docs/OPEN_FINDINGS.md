# Open findings

**Status: 15 open, 1 decision recorded and 1 deferral, 95 closed.** Every count re-counted by
heading on 2026-10-09; the entries closed on 2026-10-09 were checked against the tree that day, and
the older closed sections were last re-checked on 2026-09-03.

**Moving a set-password link's token off the web's request lines narrowed one entry the same day,
and moved neither number.** The web now checks a link with `POST /api/auth/set-password/check`, the
token in the body, and `/set-password` reads the token from a `#token=` fragment as well as the query
and takes it out of the address bar before it does anything else. That closes the web's half of the
open entry on what still writes the token down. The handset's check, the `?token=` the server still
puts in every link, and the browser's history, which keeps the address a link was opened with in
either form, keep it open, and it is rewritten to say exactly that: a heading narrowed is not a
heading closed. Counted by heading: still 15 open and 95 closed.

**A verifier's pass over what the release writes to logs and terminals moved both numbers by one,
the same day.** It closed a set-password link's token reaching the API's journal every time the link
was checked — both clients sent the token in the query string of `GET /api/auth/set-password`, and
uvicorn's access log wrote the line down — and opened what that fix cannot reach: anything in front of
the API that logs the request line, the box's own nginx first. Its four smaller findings were fixed in
the same change and never entered here, since none was outstanding: the operator script's argument
errors quoting a typed value back, the Google sign-in's log line carrying google-auth's message (which
can quote the submitted token), `scripts/vercel-ci-setup.mjs` printing a credential the origin remote
carries, and `scripts/seed_test_accounts.py` printing the shared local password (CodeQL alert #20).
Counted by heading: 14 + 1 = 15 open, and 94 + 1 = 95 closed.

**A focused review of that last pass found eight more defects the same day, and each was closed
before the day was out.** They are the eight entries under *Closed on 2026-10-09* that follow the two
join-card scans: the sanction
register creating the account on the master admin's mailbox for the master's Google sign-in to
promote; an administrator's address correction moving a barred allow-list row off the old mailbox; a
non-admin's correction ending an administrator's empanelment, or landing a second account on another
spelling of a Gmail mailbox; the unfiled-records report's discard, filing and bulk filing writing a
design workshop's records and files; four web doors the server refuses a post holder that the web did
not hold; a post holder taking themselves off their own post; the handset never noticing a session a
password change elsewhere had ended; and the handset's password gate reporting a change whose answer
was lost as a change that failed. None was ever recorded open, so the open count does not move. The
web's missing-warning entry stays open and is rewritten rather than closed: it named two places that
went unwarned and left out four doors that did too, those four are held now, and everything still
unwarned is named — a heading corrected is not a heading closed. Counted by heading: 14 open, and
86 + 8 = 94 closed.

**The last pass of 2026-10-09 closed five of the seven entries that day's review had opened, then
opened one and closed it the same day.** It closed shipped handsets misreading the change-password
answer (the fresh session token moved into a response header, and the body went back to exactly
`{"ok": true}`), the access screen's promotion, the review queue's edit, the two media doors, and the
sentence a post holder's join-card scan was given. Of the two it left open, tokens minted before the
password binding close on their own as they expire, and the web's missing warning stays one heading,
narrowed to the list rows' Delete now that the media controls ask too: a heading narrowed is not a
heading closed, so it moves neither count. The one it opened — the same join-card sentence given to a
scan that syncs after the card's date — was given a sentence of its own before the change shipped.
Counted by heading: 19 − 5 + 1 − 1 = 14 open, and 80 + 5 + 1 = 86 closed.

**The review of that day's change moved both numbers again: nine closed, seven opened.** It closed a
Ministry Admin lifting an administrator's bar by moving the barred account to another address;
sessions that outlived the temporary password they were opened with, and a sign-in that outran a
reset; a promoted account keeping the credentials a lower provisioner held for it; the master admin's
first Google sign-in landing on a planted spelling of its mailbox; a post holder changing a workshop's
files, or the records filed under it; a refused record create leaving an orphan `Location` behind; a
sanction-register designer held behind an unanswerable gate after a Google sign-in; 72-hour links for
accounts used before `firstLoginAt` existed; and a duplicate allow-list row from the operator script's
Google-only mode. The seven it opened are the limits those fixes left standing, each dated that day:
shipped handsets misreading the change-password answer, tokens minted before the password binding,
the access screen's promotion, the review queue's edit, two media doors, the web's missing warning at
the list rows and the media controls, and a join-card sentence. Counted by heading: 12 + 7 = 19 open,
and 71 + 9 = 80 closed.

**What moved on 2026-10-09**, stated so the arithmetic can be re-done: the change that gave the
administering tiers account provisioning and workshop posts, and made the server enforce a required
password change, closed the entries under *Closed on 2026-10-09* below — each a defect found in the
code it was rewriting, closed with the test that fails without the fix — and recorded under `## Open`
the limits it left standing, each dated that day. Two of those are in sibling Vercel apps (cxa-cms
and the walkthrough) that the same investigation examined; they are filed here because that
investigation ran from this repository, and the code each one is about lives in those repositories.
Counted by heading, with the two the follow-up below added: 3 + 9 = 12 open.

**The serve-as follow-up the same day moved both numbers by two, and one defect sits on each side of
the ledger on purpose.** It closed two entries — a post holder unfiling or moving a record out of a
workshop they supervise through the record forms, and the web's missing advance warning for an
administrator holding a post — and opened two: the director tiers' share of that warning, which the
web cannot give them, and a change of role that leaves a `DESIGNER` on an ended empanelment. The
warning is one closed heading and one open heading because its halves reach different people and
close by different changes; it is not one defect counted twice, which is the double count the
paragraphs below warn about. When the open half closes, it adds one to the closed count and nothing
is deduplicated.

**And "62 closed" had been wrong by two since 2026-09-03, in the direction that under-reports.** The
paragraph below says that wave closed "eleven" entries and adds 51 + 11; its own section holds
**thirteen** headings, all written in the same commit as the sentence, and none of them is a
part-closed duplicate (the refused-save mismatch is still the only one). Recounted rather than
incremented, as the maintenance table asks: 51 + 13 = 64 before this day, and 64 + 7 = 71 after it.
It is the failure the paragraphs below record more than once, and each time the headings were right
and the number was not.

**Two things moved that line on 2026-09-16, and one of them is a correction rather than an addition.**
The deferral is new — design-workshop approvals, ruled into 0.0.13, filed under `## Open` because its
consequence is live in this tree and a reader who meets `APPROVED` and cannot reach it deserves to
find it here rather than derive it. And **"2 open" had been wrong by one since 2026-09-03**: the
`[LOW]` roster-copy entry was written into `## Open` in the same wave whose paragraph below explains
how the count reached 2, and the count was never moved to 3. Counted by heading, as this file's own
rule says: three severity-tagged entries, one `[DECISION, NOT A DEFECT]`, one `[DEFERRED TO 0.0.13]`.
That is the same failure this header records twice more below — a register whose worst defect has
always been its own arithmetic — arriving a third time, in the direction that under-reports.

**Nothing was closed by the 0.0.12 release wave and nothing from it was promoted in.** Its defects
were found, fixed and pinned inside the wave rather than passing through this register, which is the
one-way rule the paragraphs below state for both audits: an item is promoted in here when it is taken
on, and the 0.0.12 items were never outstanding long enough to be.

**How that moved on 2026-09-03**, stated so the arithmetic can be re-done rather than trusted: a
remediation wave closed **eleven** entries, listed under *Closed on 2026-09-03* below and counted by
heading like every other section (51 + 11 = 62). The open count went from 1 to 2 because that wave
also *opened* one — the two residual gaps in session revocation — and the third item under `## Open`
is a **decision**, not a defect, which is why it is named separately in this line rather than folded
into either number. Dedupe before you recount: this file's own history is two miscounts caused by a
part-closed entry appearing on both sides of the ledger.

**The open count was wrong, and it was wrong in the direction this file says is the dangerous one.**
It read "0 open" from 2026-08-15 while `## Open` below carried the `[MEDIUM]` in-memory-`Blob`
entry, which has no `— CLOSED` suffix and whose three `JSZip.generateAsync({ type: "blob" })` sites
were re-checked on 2026-08-19 and are all still live. A reader who trusts the header never scrolls
the twelve lines to the entry. **The closed count did NOT move**: counting the section headings
gives 7 + 12 + 3 + 1 + 28 = 51, not 52 — the eighth heading under *Closed on 2026-08-15* is the
frontend half of the refused-save mismatch already counted under *Closed on 2026-08-13*, one defect
recorded twice as it was part-closed and then closed. That is the identical double-count the
paragraph below records as this file's 40-versus-41 error, so **dedupe before you recount.**

**An adversarial audit ran against this tree on 2026-08-19, and its findings are NOT in the count
above.** Method, because it decides how much the pass is worth: ten independent lenses were run over
the repository, and every finding one lens raised was then handed to a second agent whose job was to
REFUTE it against the code — several were refuted and dropped, and several survived in a narrower
form than they were raised in. **This paragraph carried a total ("69 findings") until 2026-08-20 and
it is withdrawn rather than updated, because there is nothing in the repository to count it against.**
Every other figure in this file is either counted against the section headings below or pinned by a
named test, and the 2026-08-15 audit's equivalent claim points at `docs/AUDIT-2026-08-15.md`. This
one pointed at nothing — the same defect as a closed entry whose test does not exist, which the
maintenance table at the foot calls "the exact defect this register was written to stop shipping".
**Put a number back when there is a write-up file or a list of fix commits to count.** A wave of
fixes was in flight while this line was written, so **treat this register as being worked rather than
clean**:
"1 open" is this file's own accounting of items that have been through the fix-and-pin cycle it
records, not a statement about the tree. Findings are promoted in here as they are taken on, exactly
as the 2026-08-15 audit's were.

**All seven items that stood open on 2026-08-13 were closed on 2026-08-15**, each with the test that
would have caught it, and the whole of both suites re-run against a live database afterwards. They
are recorded under *Closed on 2026-08-15* below. Two things found while closing them are worth
carrying forward rather than leaving in a commit message:

* **The stale Prisma client was not "nobody ran generate". Regeneration was IMPOSSIBLE on this
  machine, and had been since a box-drawing character entered `schema.prisma`.** The generator writes
  the packaged schema with `pathlib.write_text()` at the locale default — cp1252 on this Windows
  install — and `schema.prisma` holds `─` (U+2500, ×12) and `▶` (U+25B6, ×6) in its comment banners,
  neither of which cp1252 can encode. Every `prisma generate` since died on a `UnicodeEncodeError`
  pointing at a character offset, with nothing naming the schema or the encoding. `PYTHONUTF8=1` is
  the fix and it is now in `docs/ENVIRONMENT.md`. **The lesson is the shape, not the character:** a
  build step that fails only on some developers' machines, for a reason its error message does not
  name, drifts silently until something downstream is dead on the wire — which is exactly how eleven
  endpoints and the AI-verb cap came to be un-runnable while 84 tests stayed green.
* **One item in this register was already fixed and still listed as outstanding** — the frontend half
  of the refused-answer count. `refusedAnswersToShow` exists, reads the server's number, and is
  better than what the entry asked for (it handles the disagreement in BOTH directions). A register
  that is stale in the "still broken" direction costs the next reader a hunt for a bug that is gone,
  which is the failure mode the header below already warns about, arriving from the inside.

**The closed count is now the sum of the sections below, because it was not.** It read "40" while the
sections held 41 (9 closed on 08-13, 3 on 08-12, 1 on 08-08, 28 earlier) — most likely the part-closed
"one refused save was reported as two different numbers" entry being counted on both sides of the
ledger. Recounted by heading: **6 open; 12 + 3 + 1 + 28 = 44 closed.** Three of the twelve were added
by the viewer-picker pass on 2026-08-13 and the file was edited by more than one lane that day, so
re-count rather than trusting this line if it disagrees with the headings again.

This file used to hold 29 defects. Every one of them was re-read against the working tree on
2026-08-08: twenty-eight had already been fixed, and the twenty-ninth was closed by the pass that
produced this rewrite. The tables below are kept so the next reader can re-check the closures rather
than take this file's word for them, and so a defect class that has already cost this repository once
is not re-litigated from scratch.

**The sentence "nothing in this register is outstanding" stood here until 2026-08-13 and is gone,**
because a pass looking for the FOURTH door in the stage-save path found two more open ones and both
were deleting rows on the wire that day. The lesson is in the pattern rather than in either bug: the
never-read rule was asked correctly of a payload's CONTENTS three times over, and never once of the
mechanism that decides which ROWS survive. Four of the six items below are what asking that question
turned up.

**Read this before adding to it.** This register was cited from running source
(`frontend/lib/designWorkshopStore.ts` pointed a maintainer here for a residue that had been closed
the same day), so it is not inert documentation — somebody follows the pointer. A findings document
that has drifted from the code is worse than no findings document, because it sends the next reader
hunting for bugs that are gone and teaches them that the register is noise. **If you close something
here, mark it closed in the same commit that closes it.**

---

## Open

**Nothing from the 2026-08-13 pass is outstanding — and that sentence is not a claim that the tree is
clean.** It was written here once before, in almost those words, and a pass looking for the FOURTH
door in the stage-save path immediately found two more. What it means is narrower and checkable:
every item this register listed has been closed, with a test, against a live database.

A separate audit of the whole application — frontend, backend and Android, excluding the AI
surfaces — was run on 2026-08-15 and its findings are written up in `docs/AUDIT-2026-08-15.md`
rather than here, because they have not been through the fix-and-pin cycle this file records. Items
from it are promoted into this register as they are taken on.

**A second such audit ran on 2026-08-19, and none of its findings are in this section.** Same one-way
rule, for the same reason: ten independent lenses over the tree, every finding then handed to a second
agent to REFUTE against the code, and only what survived that refutation counted. Some were dropped
outright and some survived narrower than they were raised. **It has no write-up file, which is why
this paragraph no longer states a total** — see the header. Promote an item in only after it has a
fix and a test that fails without the fix.

### [MEDIUM] The browser assembles the whole archive as one in-memory `Blob` — three `JSZip.generateAsync({type:"blob"})` sites (frontend)

`frontend/app/(protected)/data/page.tsx:865`, `:1516` and `sharing/page.tsx:766` each build the
complete zip in the tab's heap: every media object is `await response.blob()`-ed into `JSZip`, and
`generateAsync({type:"blob"})` then produces one more `Blob` holding the entire archive before a
byte is written to disk. On a repository whose media runs to a gigabyte the tab dies.

**This is deliberately NOT filed as part of the manifest defect closed alongside it, and conflating
the two would have produced a fix that misses.** The handset failure was a single *contiguous*
`ByteArray` sized to the *manifest* — text only, ~48 MB modelled — thrown by
`ResponseBody.string()` inside Retrofit's converter, and it was fixed by never materialising the
manifest. These three sites do not read the manifest as one string at all (they iterate
`manifest.files` and could take `?stream=1` tomorrow); what they hold is the *media*, which the
handset never holds because it copies each object straight into a `ZipOutputStream` on disk. Two
different objects, two different allocators, two different ceilings. Streaming the manifest into
these pages would be a real improvement and would not move the number that kills the tab.

**The fix is a different mechanism:** `generateInternalStream` piped to the File System Access API
(`showSaveFilePicker`), or a `TransformStream` into a service worker, so the archive is written to
disk as it is produced. Both are browser-support decisions rather than code-shape decisions, which
is why this is registered rather than bolted onto a memory fix aimed at the handset.

### [MEDIUM] Two residual gaps in session revocation, both named rather than closed (backend) — opened 2026-09-03

Suspending an account now ends its live sessions (see the closed entry below). Two cases it does not
reach are recorded here rather than left in a docstring, because both fail in the direction where an
administrator has been *told* access is cut:

1. **Rows barred before 2026-09-03 were never stamped, and the repair is a script somebody has to
   run.** `User.sessionsValidFrom` had exactly one writer until that date, so every account
   suspended or rejected before it kept whatever token it was holding for the rest of that token's
   life. Nothing backfills them on its own — the empanelment doors return early on a bar that
   already stands, and a sign-in cannot help because the person barred is not signing in. The remedy
   the code names is `backend/scripts/backfill_sessions_valid_from.py`, **which now exists** (it did
   not when this entry was first written, and the ERROR log in `routes/access.end_live_sessions`
   named it before it was there). Run from `backend/`:

   ```
   python -m scripts.backfill_sessions_valid_from                        # DRY RUN, writes nothing
   python -m scripts.backfill_sessions_valid_from --write                # the STILL-LIVE list
   python -m scripts.backfill_sessions_valid_from --write --include-expired   # the historic backlog too
   python -m scripts.backfill_sessions_valid_from --write --limit 20     # cap a first pass
   ```

   The no-argument form is the safe one and prints a plan. It splits candidates into STILL LIVE —
   where a stamp ends a session somebody is in right now — and EXPIRED, where the column is merely a
   watermark that ought to be true, computed from the deployment's own `JWT_EXPIRES_MINUTES`. The
   value written is the moment of the barring (`decidedAt` / `revokedAt`, falling back to
   `updatedAt`), not the moment of the run, which is what makes it idempotent; it never clears a
   stamp and never lowers one. The empanelment bucket is guarded by
   `access_roster.admissions_an_empanelment_carries` — the same function the endpoint consults,
   called rather than reproduced — so a professor or admin on the designer roster is not signed out
   of the product by a script. Note that the stamp does not reach a running API instantly:
   `deps.resolve_user` caches the identity row for `AUTH_USER_CACHE_TTL_SECONDS` and this process is
   not the API's, so revocation lands within that TTL rather than at the write.

   **Still open, and the same defect one file along:** the backend also names
   `scripts/backfill_roster_suspension_mirror.py` (in `routes/access.py`, `services/access_roster.py`
   — including inside two operator-facing log lines) and `scripts/backfill_email_canonicalisation.py`
   (in `routes/designers.py`, `services/designers.py`). **Neither is in the tree.** Verified
   2026-09-03; check with `git ls-files backend/scripts/`. Both are instructions a reader cannot
   follow, and one of them is printed at the moment an operator is being told a suspension may not
   have taken. Either write them or reword the six references.
2. **A sweep that could not answer does not stamp.** `access_roster.accounts_on_the_mailbox` returns
   `None` when the Gmail-alias sweep exceeds `GMAIL_ACCOUNT_SWEEP_LIMIT` (20,000). Next door, in the
   mirror guard, `None` correctly means "do not act". Here it means "there may be a live session this
   suspension did not end", which is the unsafe direction. It is logged at ERROR, names the address
   and names the repair — which is the right behaviour for something that cannot be fixed in the
   handler — but it is still an unrevoked session, and nothing outside the log knows.

### [LOW] The web's two roster screens do not say what pressing Suspend actually does (frontend) — opened 2026-09-03

The server side of both acts is closed (see below). What is outstanding is **copy on the screen where
an administrator makes the decision**, and it fails in the direction where somebody is told less than
the act does. Verified against the tree on 2026-09-03.

1. **Neither web roster screen carries the cross-roster sentence the mirror's own docstring asks
   for** — that suspending on one screen ends the person's standing on the other, and that
   **restoring does not bring it back**. A one-way mirror an administrator cannot see is a
   destructive act that reads as reversible. Both `/admin/access` and `/admin/designers` owe it.
2. **`/admin/designers`'s Suspend dialog does not say the person is signed out.** It reads *"They
   will be refused at their next request and at every sign-in after it"*, which was written before
   `end_live_sessions` existed and now understates the act; it owes the plainer half — *this signs
   them out of any device they are already signed in on*.

**Two things that are already done and should not be re-done.** `/admin/access`'s Suspend dialog was
corrected the same day and now says *"Any session they are in now ends immediately."* And Android's
line reads *"Suspending ends their access and signs them out now."* The web designer roster is the
one screen left, plus the mirror sentence on both.

### [DECISION, NOT A DEFECT] `AUTH_USER_CACHE_TTL_SECONDS` should be cut, and deliberately was not — recorded 2026-09-03

The five-second identity cache was justified against a 200–400 ms cross-region database round trip.
That round trip is gone: the database was co-located on 2026-09-02 and a keyed `find_unique` is now
1–2 ms. What the cache still buys is burst dedupe across one page load's parallel requests, which is
real but much smaller — and the TTL now also bounds *session* revocation, not just role and
existence, because `_user_from_bearer` reads `sessionsValidFrom` off the cached row. In practice it
bites only on writes no application process made, since every revocation writer invalidates and the
deployment runs one worker on one replica. Since 2026-10-09 it also bounds the password binding in
both directions, for a password written outside the API: an old session outliving the write, and a
session opened with the new password refused until the cached row expires
([SECURITY.md §3.6](SECURITY.md)). **1–2 seconds, or 0 with `AUTH_USER_CACHE_ENABLED=false`,
is now a cheaper trade than it was.** It was not changed in this wave on purpose: a security
parameter moved as a silent constant edit is a change nobody reviewed. It belongs in the next
deployment review. The full argument is in [SECURITY.md §4.1](SECURITY.md).

### [DEFERRED TO 0.0.13] Design-workshop approvals were not built, and `APPROVED` has been unreachable since the review loop shipped — recorded 2026-09-16

**The deferral is a decision. The unreachability is the fact that comes with it, and it is written
here so the next reader does not rediscover it as a novel bug.** Owner's ruling, 2026-09-15: design
workshop approvals (the S6 workstream) ship in **0.0.13**. Nothing was half-built — there is no
`design_workshop_approvals.py` in `backend/app/api/routes/`, no partial router, no dead frontend
route, and nobody is part-way through this. (That filename is written without its directory on
purpose: `docs/tools/check-docs.mjs` asserts that every repository path a document names EXISTS, and
the whole point of this entry is a file that does not.)

**What is missing is three verbs on one router**, each named in `DECISION_EDGES` in
`backend/app/schemas/design_workshop_review_loop.py` against a module that does not exist:
`POST /design-workshop-approvals/{id}/approve`, `…/revise` and `…/hand-on`.

**The consequence, read straight off `LEGAL_TRANSITIONS` rather than inferred.** Two of the eight
statuses cannot be entered by anything in this deployment:

* `APPROVED` has exactly one inbound edge, `PRE_SUBMISSION → APPROVED`, and that edge is a
  `DECISION_EDGE` owned by `/approve`.
* `SUBMITTED` — which since 2026-09-13 means *the approved report has been handed on* — has exactly
  one inbound edge, `APPROVED → SUBMITTED`, owned by `/hand-on`. It is therefore unreachable
  **transitively**, which is the half a reader does not see by scanning the table for the word.

So a report today goes `DRAFT → IN_PROGRESS → PRE_SUBMISSION`, can be sent back to `NEEDS_REVISION`
by an inspector and resubmitted as often as anyone likes, **and stops there.** The only rows that read
`APPROVED` or `SUBMITTED` are ones that carried the pre-2026-09-13 meaning of `SUBMITTED`, and they
can still leave those states (`SUBMITTED → IN_PROGRESS | PRE_SUBMISSION | ARCHIVED`) — they simply
cannot be re-entered.

**This is deliberate and it is the safe direction**, which is why it is filed as a deferral and not as
a defect. `DECISION_EDGES`' own comment says it: *"until it lands `APPROVED` is simply unreachable.
That is the safe direction and the honest one: nothing can be approved by accident, and no header edit
can manufacture an approval in the meantime."* A `PATCH` carrying one of the four decision edges is
refused with a sentence that names the route which would make it, so the failure mode is a legible
refusal rather than a 500 or a silent no-op.

**It is said on screen, once, where the gap is met.** `MINISTRY_APPROVAL_GAP` in
`frontend/components/dashboard/ministryDesk.ts` prints it under the ministry desk's five rows —
*"Reading a report back is where this sequence stops today… none of the rows above is waiting on a
signature from you"* — because five rows listed in the order a workshop reaches them read as a
COMPLETE order. It names no tier and no release, and it is tied to the existence of the approvals
router on disk, so it removes itself the day 0.0.13 lands rather than becoming a lie that has to be
noticed.

**What 0.0.13 owes beyond the three verbs**, listed so the scope is not rediscovered either: a gate
(nothing in `deps.py` today names a sanctioning authority, and §2's matrix has no row for one); a
`ReviewLog` row written in the same transaction as each status move, which is the whole reason these
are routes and not header edits; the `round >= 1` CHECK the send-back path already has to satisfy; and
a decision about whether an approval may be withdrawn after the report has been handed on — today
`APPROVED → SUBMITTED` is one-way, and `APPROVED → ARCHIVED` was deleted from the graph on purpose
because with `ARCHIVED → PRE_SUBMISSION` legal it was a two-hop laundering path out of an approval.

**Not to be "fixed" in the meantime by widening the PATCH.** The four decision edges are subtracted
from `LEGAL_TRANSITIONS` rather than listed twice, so re-admitting one to the header edit takes a
deliberate edit to `DECISION_EDGES` — and what it would buy is a status change with no audit row,
which is a decision that appears to have made itself.

### [LOW] A workshop appointment is validated and then written, not both in one transaction (backend) — opened 2026-10-09

`backend/app/services/design_workshop_posts.py` decides whether an appointment breaks a
separation-of-duties rule, and every door that makes one — the oversight slots, the inspector panel,
the viewers `PUT`, the oversight screen's designer doors, approving an access request, redeeming a
join card — asks it and then writes. That is the read-then-write pattern these validators already
had, and it leaves one race: two administrators saving DIFFERENT screens for the same person on the
same workshop at the same instant can both pass — one naming them its Regional Director while the
other gives them designer access, say — and the workshop ends up holding a pairing neither request
would have been allowed to make alone.

**The write it would enable does not get through.** The 403 that keeps a post holder away from a
workshop's content and designer team is asked at the moment of each write, of the rows as they stand
then, so the person holding both still cannot write what they supervise. What is left is a pairing
on the books, which an administrator removes by taking the person off one half of it; the query in
the next entry finds one. Closing the race means one transaction per workshop across every one of
those doors. See [PERMISSIONS.md](PERMISSIONS.md) §4.8.

### [LOW] Rows written before the serve-as ruling that break its rules are not cleaned up (backend) — opened 2026-10-09

The separation-of-duties rules apply to every appointment made from 2026-10-09 on; nothing rewrote the
rows already there. An Assistant or Regional Director has been able to hold designer access to a
workshop since the directorate tiers joined the design-workshop set on 2026-09-14, and until the ruling
the check ran in one direction only: appointing a workshop's co-designer as its director was refused,
but giving designer access to somebody already serving as its director was not. Such a pairing stays
until somebody removes one half of it. **Production held none
when checked read-only on 2026-10-09**, and the write refusal applies to any such person at once, so a
leftover pairing cannot be used to write what it supervises. Re-check with:

```sql
-- A post holder with designer access to the same workshop, a director who also inspects it,
-- or one person in both director slots.
select 'post and designer access' as pairing, p."designWorkshopId", p."userId"
from (select "designWorkshopId", "userId" from "DesignWorkshopOversight"
      union select "designWorkshopId", "userId" from "DesignWorkshopInspector") p
join "DesignWorkshopViewer" v using ("designWorkshopId", "userId")
union all
select 'director and inspector', o."designWorkshopId", o."userId"
from "DesignWorkshopOversight" o join "DesignWorkshopInspector" i using ("designWorkshopId", "userId")
union all
select 'both director slots', "designWorkshopId", "userId"
from "DesignWorkshopOversight" group by "designWorkshopId", "userId" having count(*) > 1;
```

The other half of authorship — stages a post holder wrote — is `design_workshops.stage_writers` in
`backend/app/services/design_workshops.py`, which a one-off script can ask about every post on the
books. See [PERMISSIONS.md](PERMISSIONS.md) §4.8.

### [LOW] ADMIN and MASTER_ADMIN post holders are withheld by name on the ministry dashboard's registers (backend) — opened 2026-10-09

The ministry dashboard names people on its officer and inspector registers and never names an ADMIN
or a MASTER_ADMIN account: `WITHHELD_PERSON_ROLES` in `backend/app/services/ministry_dashboard.py` is
the dashboard's disclosure boundary, and it was drawn before an administrator could hold a post. Since
the ruling of 2026-10-09 an administrator may be a workshop's Assistant Director, Regional Director or
inspector, and such a holder appears on those registers only inside the withheld count — so a ministry
reader can see that somebody withheld supervises or inspects, and not who. A MINISTRY_ADMIN holder is
listed by name wherever a row names them. Whether the boundary should give way for a post holder is
the owner's question rather than a code fix.

### [LOW] Handsets on builds 0.0.2 to 0.0.5 have no screen for a required password change (android) — opened 2026-10-09

Since 2026-10-09 the server holds an account carrying `mustChangePassword`: every route outside a short
allow-list answers `401` with `X-Password-Change-Required: 1` until the owner chooses a password
([PERMISSIONS.md](PERMISSIONS.md) §1.2). The handset draws a gate screen for the flag from build 0.0.6,
and its handling of the gated `401` itself — keep the token and every queue, pause the sends, resume
when the flag clears — is in the Android source since that date and reaches handsets with the next
published build. **Builds 0.0.2 to 0.0.5 have no gate at all**, so an account flagged while its owner
is on one of them is refused nearly everything with nothing on the phone saying what the server is
waiting for, and has to choose its password on the web first. The owner accepted that cost with the
ruling rather than weaken the hold. `GET /api/app/release/latest` is on the allow-list, so such a
handset can still learn that a newer build exists; the entry closes as those handsets update, and
nothing on the server can close it without giving the hold up.

### [LOW] An administrator holding a workshop post who edits a stage on a phone is refused only at the save (android) — opened 2026-10-09

The serve-as ruling is web-only
([DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md)), so the handset
knows nothing of posts. An ADMIN or MASTER_ADMIN reaches every workshop through the
admin arm, so one serving as a workshop's inspector or director can open it on a phone and type into
its stages. Each save is answered with the 403 write refusal ("You are this workshop's …"), which the
phone records against that stage with the server's sentence and holds — nothing typed is lost and
nothing reaches the workshop, which is the rule working. The handset's viewers screen and its
join-card printing are designer-team doors the same rule refuses such an administrator, and they too
answer only with the 403 — as, since the record and media doors began asking later that day, do a
record edit or delete on a record filed under that workshop, a review-queue edit of one, a media
delete or transcript edit on one of its files, an upload into it and a retried transcription; and,
later still, the unfiled-records screen's filing and discard of a row the workshop claims, printed as
the server wrote them (the discard's 409 for every administrator). That screen's bulk filing reports
only the count it filed — the handset reads no `heldBack` — so a holder's held rows simply stay on the
re-read report. An administrator who unticks their own row on the handset's inspectors screen meets
the server's 409 — nobody takes themselves off a post — in the same way. What is missing is the
warning: the web hides or disables most of those controls for a post holder and says why, and the
phone says nothing until the save.
Closing it on the handset needs the post rows, or a summary the server computes, mirrored there —
which the web-only ruling deferred. See [PERMISSIONS.md](PERMISSIONS.md) §4.8.

### [LOW] An Assistant or Regional Director holding a workshop post is warned of a refused write only by the server's 403 (frontend) — opened 2026-10-09

The web's advance warning for a post holder — closed below for the administering tiers — learns which
posts the reader holds from the workshop's two staffing reads, its oversight rows and its inspector
panel, and both belong to the accounts that may appoint: a Ministry Admin, an admin or the master
admin (`readHeldWorkshopPosts`, over `readWorkshopStaffing`, in
`frontend/app/(protected)/officers/oversight.ts`). Anybody else gets no request and an answer of "none
known", never "none held". So an Assistant or Regional Director
appointed to a workshop's post who still reaches one of its write screens — as the workshop's
creator, or through a designer row written before the ruling — or a record form for a record filed
under it, which asks the same way since later that day, sees the controls live and learns of the
refusal from the server's 403 at the save, in the server's own sentence. Nothing is written and
nothing typed is lost; what is missing is the warning before the typing. Closing it needs a read a
holder may make about their own posts on one workshop, which the server does not offer as of
2026-10-09, rather than opening the staffing reads to the tiers they staff. See
[PERMISSIONS.md](PERMISSIONS.md) §4.8.

### [LOW] A change of role alone can make an account a DESIGNER on an address whose empanelment was ended, and the account then cannot sign in (backend) — opened 2026-10-09

Since 2026-10-09 a provisioner who is not an admin may not put a `DESIGNER` on an address whose
designer empanelment an administrator ended — not by creating the account at `POST /api/users`, and
not by moving an account onto that address with `PATCH /api/users/{id}` — each a 409 from
`account_provisioning.assert_not_overturning_a_bar`. A `PATCH` that changes the role and not the
address asks nothing about the address: it is open to a Professor and above (`require_professor`, the
ceiling `assert_role`, the target `assert_can_manage_target`), and making an account a `DESIGNER`
where the empanelment was ended is not refused, for anybody. Nothing is overturned by it, because the
sign-in gate still reads the ended empanelment: the account is refused at its next sign-in until its
role is changed back or an administrator restores the empanelment on the designer roster — a
lock-out the person making the change was never told about. Refusing it would be a new refusal for
professors and the directorate tiers, outside what the change of that day took on. See
[PERMISSIONS.md](PERMISSIONS.md) §1.2.

### [LOW] PostgreSQL TLS does not verify the server's certificate in the two sibling Vercel apps (cxa-cms, virtual-walkthrough) — opened 2026-10-09

Both apps that reach PostgreSQL from Vercel encrypt the connection and accept whatever certificate the
far end presents. The walkthrough's `pg` pool (`virtual-walkthrough/src/server/db/postgres.ts`) runs
with `rejectUnauthorized: false` in its default `require` mode, and its `verify-full` mode supplies no
CA, so it cannot work as written; cxa-cms's Prisma URLs carry `sslmode=require`, which does not verify
the peer either. An attacker on the path between a function and the database's pooler could present
any certificate, ask for cleartext password authentication, and read the password and the traffic.
That path runs inside the cloud providers' networks, which is why this is LOW — but nothing checks the
peer. The fix is the database provider's root CA shipped with each app and verification switched on:
`ssl: { ca, rejectUnauthorized: true }` behind a CA variable in the walkthrough, and `sslrootcert`
traced into the function bundle for cxa-cms. Both repositories' deployment documents record the gap
as of 2026-10-09; neither has built the fix.

### [MEDIUM] cxa-cms scheduled publishing lands hours after its time, until the owner chooses a scheduler (cxa-cms) — opened 2026-10-09

The Vercel Hobby plan refuses any cron that fires more than once a day, so cxa-cms runs its publish
trigger from a GitHub Actions schedule (`*/5`) in its heartbeat workflow,
`cxa-cms/.github/workflows/keep-warm.yml`. GitHub's scheduler is best-effort: over the 100 runs from
2026-09-20 to 2026-10-08 the gap between runs had a median of 263 minutes (90th percentile 398,
longest 529). So a page an editor schedules for 10:00 stays SCHEDULED in the studio, and keeps its old
`isPublished` flag in the search index, for hours. Public pages compare publication dates on every
request, so nothing goes live early or stays up late; what lags is the studio's status and the search.

On 2026-10-09 the publish step was moved first and ungated — it used to wait on a wake-up ping written
for the database host cxa-cms used previously, so any failed ping skipped publishing — and the ping
became a health check that gates nothing. That stops a ping from costing a publish; it does not make
the schedule keep time. The fix is a scheduler that does — the database's own cron (`pg_cron` with
`pg_net`), or Vercel Pro with the cron back in `vercel.json` — and which one is the owner's decision,
open as of 2026-10-09 (cxa-cms's `DEPLOYMENT.md` §1.7).

### [LOW] Sessions and dataset tokens minted before the password binding are not ended by a password change (backend) — opened 2026-10-09

The binding ([SECURITY.md](SECURITY.md) §3.6) is carried by every token minted by the release that
introduced it. A token minted before that release has no `cred` claim and is accepted exactly as
before, by decision, so that nobody was signed out at the deploy — which makes it the one kind of token
a password change does not end. In particular a session opened with a temporary password before the
release is still released, not ended, by the owner's forced change: the finding closed below, for that
one population. The watermark still ends such tokens for every act that writes it. The window closes
on its own, counted from the day the release reached the server: `JWT_EXPIRES_MINUTES` (seven days)
for a session and `DATASET_TOKEN_EXPIRES_MINUTES` (thirty days by default) for a dataset token.
Rotating `JWT_SECRET` ends them all at once if that wait is too long. Close this entry when the second
has elapsed.

### [LOW] The web warns a post holder on the workshop's screens, the record forms, the media controls and the doors that add to a workshop, but not at the list rows' Delete (frontend) — opened 2026-10-09

The web holds a workshop's own write screens, the five record forms and an attached questionnaire
form for an administrator holding a post ([PERMISSIONS.md](PERMISSIONS.md) §4.8); since later that
day the controls that change a stored file as well — `/media`'s Delete and Transcribe now, a record's
attached files on the artisan, product, tool and process forms, and the relink among `/admin`'s
recovered recordings (`useHeldPostRefusals` and `mediaWriteHold` in
`frontend/components/designworkshop/HeldPostNotice.tsx`); and later still the doors that add to a
workshop or edit it from elsewhere — the review queue's Save and "Save and approve", `/media`'s
Upload, the jobs panel's Retry, and creating, uploading or reusing a questionnaire into it (the closed
entry below on four web doors). Section 5 of `frontend/e2e/workshop-post-holder-readonly-unit.spec.ts`
pins the file controls and sweeps the web calls that delete a file, re-run its transcript, decide an
identity photograph or relink a file; section 6 pins the doors that add to a workshop and registers
every caller of them, the unfiled-records report's writes included, so a new one fails until it is
held. An identity photograph's keep and discard sit under the stage form's own lock, and the web has
no control that edits or refines a transcript. **This entry named two places still unwarned and left
out four doors that were too**, and said the sweep caught every web call that changes a stored file
while it missed the unfiled-records report's delete; those four doors are held now and that call is
registered.
It stays open, as of 2026-10-09, for what still asks nothing before the click. The per-row Delete on
the design-workshop list, on the artisan, product, process and tool lists and on the interview list
would each need a staffing read per distinct workshop on the page. The web warns about a file only by
the workshop links its screen can see — the file's `designWorkshopId` and `designWorkshop` tag, and on
the upload the filing of the record chosen — so a file that belongs to a held workshop only through a
stage entry, an AI layer or the record it hangs off is not warned about on `/media`, among the
recovered recordings, on the jobs panel or in the review queue's edit. Moving an existing
questionnaire into a held workshop from its own page saves on select. And the unfiled-records report's
single-row filing and discard ask nothing first, though a row a design workshop's column or tag claims
is not listed there at all. Every one is refused by the server, its answer printed word for word, so
nothing is written; what is missing is the warning before the click.

### [LOW] A set-password link's token still rides two request lines, the handset's check and the link itself, and stays in the browser's history (backend, android) — opened 2026-10-09, narrowed 2026-10-09

**Closed for the web, 2026-10-09.** The web checks a link with `POST /api/auth/set-password/check`
and `{"token": …}` in the body, which answers exactly what the GET answers
(`check_set_password_token_in_body` in `backend/app/api/routes/auth.py`), and it asks the GET only
when the POST is answered 404 or 405, which is what an API older than the POST answers
(`checkPasswordLink` in `frontend/lib/signIn.ts`). A body is on no request line, so nothing in front
of the API receives the token from the web. `/set-password` reads the token from a `#token=` fragment
first and the query second, and replaces the address with one carrying neither before it checks
anything (`takeLinkTokenFromAddress`), so the token is not left in the address bar, a copied address, a
bookmark or the entry Back returns to. Measured against a production build in Chromium the same day,
both ways a link can arrive: the address settled without the token, Back returned to that clean
address, and the check went out as a POST with the token in its body. Pinned by the tests under "The
link check with the token off the request line" in
`backend/tests/test_auth_identity_and_password_links.py` and by
`frontend/e2e/set-password-link-token-unit.spec.ts`.

**Still open: the handset's check.** Shipped handset builds check a link with
`GET /api/auth/set-password?token=<the token>` (`WorkshopRepositoryApi.checkPasswordLink`'s
`@Query("token")`), so the GET stays, and every check a handset makes still writes the token wherever
something in front of the API logs the request line: the box's own nginx —
`infra/terraform/user_data.sh` sets no `access_log`, so Ubuntu's default writes every request line,
query string included, to `/var/log/nginx/access.log` — and CloudFront, if its standard logging is
ever switched on (not verifiable from this repository). `AccessLogRedaction` keeps it out of uvicorn's
own line (the entry closed below) and reaches nothing in front of it. Whoever can read those logs can
set the account's password until the link is used or expires. The fix is the next Android release
asking the POST; the GET can go once the builds that call it have left the field.

**Still open: the link itself.** `credential_links.link_for` still issues
`{NEXT_PUBLIC_APP_URL}/set-password?token=…`, because the handset is offered these links too (the
`/set-password` filter in `android/app/src/main/AndroidManifest.xml`) and the shipped builds read the
token only from the query. So opening a link still sends the token to the web host: in the request for
the page, and again as the `Referer` of every stylesheet, script and font the page asks for before its
script can rewrite the address (measured 2026-10-09 against a production build: 17 such requests, all
to the web host's own origin; the API saw none, because `Referrer-Policy: strict-origin-when-cross-origin`
sends another origin only the origin). The web already reads `#token=`, so moving `link_for` to the
fragment needs no web release: the next Android release must accept both forms, and `link_for` moves
once the builds that accept only the query have left the field. A fragment is never sent to any server
and never appears in a `Referer`, so that move closes the web host's leg too (measured the same day: no
request carried a `#token=` link's token).

**Not closed by either form: the browser's history.** Measured in Chromium on 2026-10-09, a link
opened either way is recorded in the History database with its token, `?token=` and `#token=` alike;
the page's `replaceState` adds the clean address beside it and does not remove the visit that brought
the token. So on a shared computer the token stays readable in the history until somebody clears it,
and stays usable until the link is used or expires: 72 hours for an INVITE, 2 for a RESET. Using the
link is what retires it. Open as of 2026-10-09.

**The handset's half is in the Android source as of 2026-10-09**; no published build carries it yet. It
checks a link with `POST /api/auth/set-password/check` and the token in a JSON body, falling back to the
GET only when that POST is answered 404 or 405 — a server from before the route — and on no other
failure (`WorkshopRepository.checkPasswordLink`, pinned by
`android/app/src/test/java/com/designprototype/workshop/data/PasswordLinkCheckTest.kt`). It reads the
token from either `?token=` or `#token=`, fragment first as the web reads it (`passwordLinkToken`,
pinned by `PasswordSetupCopyTest`), and its debug request log replaces the fallback's token — OkHttp
writes its `██` back percent-encoded, so the line reads `token=%E2%96%88%E2%96%88`. The production API
has answered the POST since `main` deployed the route on 2026-10-09 (measured that day: a token that is
not one is answered `200` with `"valid": false` and the reason `malformed`), so every check from the
new build goes out in a body. The entry stays open until that build has replaced the shipped ones,
0.0.6 to 0.0.15, which still ask with the GET and read only the query; `link_for`'s switch waits on
the same, and neither form closes the browser's history.

---

## Closed on 2026-10-09

Found by the read-only investigation that preceded the account-provisioning and serve-as change, while
making it, or by the review of it the same day, and closed by that change — six by its final pass,
the six that end with the two join-card scans, five of them after the review had recorded them open
and one found by that pass itself; the eight after those by a focused review of that final pass,
which found each and saw it closed the same day without recording it open; and, after those, a
set-password link's token in the API's journal, by a verifier's pass over what the release writes to
logs and terminals, which closed it and recorded its residue open. Every entry below was verified
against the tree on the day it was written and names the test that fails without the fix.

### [HIGH] Any admin could mint a password link for the master admin or a peer admin, and take the account over (backend) — **CLOSED 2026-10-09**

`POST /api/auth/password-links` checked only that the caller was an admin (`require_admin`) and never
looked at whose account the link was for, and its revoke route was unscoped the same way.
`POST /api/auth/set-password` writes whatever password the link's bearer chooses, for whoever the link
names, with no role check of its own — rightly, since the link IS the authority, which is exactly why
the authority has to be checked where the link is issued. So an ADMIN could issue a RESET link for
the MASTER_ADMIN (an INVITE, for a master with no password), redeem it, sign in as the master admin,
and hold the provider keys, the repository settings, the release channel and the power to mint more
master admins; the same worked against a peer admin. The web hid the action on master-admin rows, and
that was the only guard. The sanction register's credential door had already closed the same hole
with a rank test.

Both link routes now stand behind `require_account_provisioner` and authorise the TARGET with
`account_provisioning.assert_may_reset_credentials` — the rule `PATCH /api/users/{id}` uses: strictly
lower tiers only, master admins are peers who cannot manage each other, and never your own account (a
403 pointing at Change password). A Google-only account with no password gets a 422, and revoking a
link that does not exist is a 404. Pinned by
`test_an_admin_cannot_mint_a_link_for_the_master_admin_or_a_peer` and
`test_revoking_a_link_needs_the_same_authority_as_issuing_it` in
`backend/tests/test_account_provisioning.py`, and by the database-free table
`test_a_password_link_is_authorised_on_the_target` in
`backend/tests/test_auth_identity_and_password_links.py`.

### [MEDIUM] `mustChangePassword` was reported and never refused, so a temporary password opened the whole API for a token's life (backend) — **CLOSED 2026-10-09**

The flag means the password an account holds was chosen by somebody else, and both clients drew a
change-password screen for it — but the server only reported it, by design at the time (the schema
comment above the column said "IT REPORTS, IT DOES NOT REFUSE"). Anybody holding the temporary
password could sign in from a script, never see the screen, and use the account's whole role — an
admin's dataset-token mint included — for the life of the token, `JWT_EXPIRES_MINUTES`.

The server enforces it now (owner's ruling, 2026-10-09). `deps.refuse_while_password_change_pending`,
called inside `get_current_user` and `require_dataset_admin`, answers every route outside
`deps.PASSWORD_CHANGE_ALLOWED_ROUTES` with `401`, `X-Password-Change-Required: 1` and "Choose a new
password to continue."; CORS exposes the header; `POST /api/datasets/token` refuses a flagged account
with a 403. Only the account at `MASTER_ADMIN_EMAIL` is exempt, and an account with no password is
never held. Both clients keep the session on that `401` and draw the gate
([PERMISSIONS.md](PERMISSIONS.md) §1.2). Pinned by `backend/tests/test_password_change_enforcement.py`,
which enumerates the allow-list, sends a flagged account to listing and writing routes alike over
Postgres, and proves the flag clears after a change — on the fresh session the change hands back, in
its `X-Session-Token` header, since later that day, when the sessions opened with the replaced
password began to end with it (the entry below). **The residue is open above**: handsets on builds
0.0.2 to 0.0.5 have no gate. The schema comment above the column was corrected the same day and
describes the refusal, its exemptions and the flag's writers as they stand — the sanction register no
longer among them, a provisioner's withdrawal included — so [DATA_MODEL.md](DATA_MODEL.md) §5 and
`backend/prisma/schema.prisma` agree.

### [MEDIUM] A Google sign-in opened a second account beside a password account on another spelling of the same Gmail mailbox (backend) — **CLOSED 2026-10-09**

`login_with_google` looked Google's address up literally, and `User.email` is stored as it was typed,
lower-cased but not canonicalised. So an account an administrator created as `first.last@gmail.com`
was missed when Google answered `firstlast@gmail.com` — Gmail ignores dots and `+tags` and treats
`googlemail.com` as `gmail.com` — and a second account was created beside it, splitting one person's
workshops and records across two accounts with no way to merge them. On an exact match it did the
opposite damage: it rewrote the account's provider to GOOGLE and replaced the name the administrator
had typed, which hid the password-link and re-issue doors from an account that still had a password.

Owner's ruling, 2026-10-09: an account that has a password keeps its hash, its LOCAL provider, its name
and its `mustChangePassword` flag when its owner signs in with Google, and the sign-in refreshes its
avatar. When the literal lookup misses, the sign-in goes to the ONE password account on the same
mailbox (`access_roster.accounts_on_the_mailbox`, the canonicalisation the allow-list's revocation
already trusts — read since later that day through `accounts_on_the_mailbox_for_sign_in`, which has
Postgres return only the candidates instead of every Gmail account on each such sign-in and gives the
same answer, pinned by `test_the_sign_in_fold_answers_exactly_what_the_sweep_answered`), and two or
more is a 409 telling the person to sign in with their password and ask an administrator. The
configured master admin's sign-in never folds (an entry below). A sweep that cannot answer logs at
ERROR and falls back to the old behaviour rather than lock out every new Google user — a visible
duplicate an administrator can fix. Pinned by
`test_google_on_a_password_account_keeps_the_password_the_provider_and_the_name`,
`test_google_finds_the_password_account_under_another_spelling_of_the_mailbox`,
`test_two_password_accounts_on_one_mailbox_are_refused_rather_than_guessed` and
`test_a_google_only_account_signs_in_exactly_as_before` in `backend/tests/test_account_provisioning.py`,
with database-free twins in `backend/tests/test_auth_identity_and_password_links.py`.

### [LOW] Setting your own password through `PATCH /api/users/{id}` was refused only by accident (backend) — **CLOSED 2026-10-09**

`update_user` added `passwordSetAt` to the payload before the self-service check, whose allowed set was
`{name, email, passwordHash}` — so a password sent for one's own account failed with "You cannot
change your own role or privileges", a sentence about roles, while the comment beside it said
self-service included the password. The obvious tidy-up — moving the stamp below the check — would
have let a stolen admin session set a permanent password without knowing the current one and outside
the per-account guessing budget, which is the takeover `POST /api/auth/change-password` is written to
prevent.

The self case is refused first now, deliberately, for every tier and before the target is loaded: a
password or the flag for one's own account is a 403 pointing at Change password, which asks for the
current password and spends the budget. Pinned by `test_nobody_sets_their_own_password_or_flag_here`
in `backend/tests/test_account_provisioning.py` and
`test_nobody_may_reset_their_own_credentials_through_a_provisioning_door` in
`backend/tests/test_auth_identity_and_password_links.py`.

### [MEDIUM] An expired sign-in parked a designer's queued workshop drafts as refused (frontend) — **CLOSED 2026-10-09**

The web's design-workshop draft drain (`frontend/lib/designWorkshopStore.ts`) asked of every failed
stage save whether it was about that stage or about the whole pass, and a plain `401` — a token that
ran out, which a fortnight in the field outlasts, or a second tab signing out — answered "about the
stage". So every dirty stage was stamped with the server's "Could not validate credentials" as a
permanent refusal of an answer nobody got wrong, and `blocksRetry` held each one shut after the
designer had signed in again; the same `401` on any other arm was written onto the whole workshop, and
on the photograph leg onto the photograph. Nothing was lost, and nothing was sent either. The records
outbox had stopped on a `401` without marking anything for long before, and
`frontend/lib/failureTriage.ts` states the rule both drains are meant to obey: a credential that
expired stops the pass, marks nothing, and asks for a sign-in. Found while the password gate's own
`401` was being taught that rule the same day.

`stageRefusalIsPassLevel` now hands any credential expiry to the pass-level catch, which stops and
records `credentialExpired` rather than a failure, and the photograph leg rethrows it instead of
recording it against the file. Pinned by `frontend/e2e/draft-drain-credential-expiry-unit.spec.ts`.

### [MEDIUM] A post holder could unfile a record from a workshop they supervise, or move it out, through the record forms (backend) — **CLOSED 2026-10-09**

`record_design_workshop.assert_payload_workshop` is the one gate the record forms share for
`designWorkshopId` — the create and `PATCH` routes for artisans, products, processes and tools, and
the questionnaire interview's `PATCH`. It asked only about the DESTINATION. Filing a record into a
workshop goes through `load_workshop_or_404(for_edit=True)`, which since the serve-as ruling refuses
whoever holds that workshop's inspection or one of its director posts; but an explicit
`designWorkshopId: null` was allowed with no question about the workshop being left — deliberately, so
a filer removed from a workshop can still take their own record back — and a move asked only about
where the record was going. So a post holder who may edit the record — their own, or, through the
Professor floor every administering tier clears, one created by an account ranked below them — could
take an artisan, product, process, tool or interview out of a workshop they inspect or supervise with
one `PATCH`: the artisan unlink the oversight screen refuses them, reached through another door. A
designer's own questionnaire had the same shape at `PATCH /api/questionnaires/{id}`: a detach asked
nothing, and an admin may change any designer's form, so an administrator holding a post could take a
form whose sittings print in the report's annexure off the workshop. Found on 2026-10-09 by the pass
that refused the designer-team doors, which recorded it rather than closing it.

Closed the same day. The five update routes pass the workshop the stored row names (`filed_under`),
and a payload that leaves it — an unfile, or a move, whose leave is asked first — went through
`assert_may_unfile_from`, which asks `design_workshop_posts.refuse_a_holders_write` and nothing else;
the questionnaire's `PATCH` asked it too before a detach or a move. A post holder gets the same 403 and
the same sentence as at every other refused door, and anybody who holds no post there is asked
nothing, as before. **Later the same day the gate was widened to every write to a filed record and
renamed `assert_may_write_a_record_filed_under`** — the entry on a workshop's files and records below.
Pinned by
`test_a_record_form_refuses_a_holder_taking_a_record_out_of_their_workshop`,
`test_every_record_form_passes_the_workshop_its_row_is_filed_under`,
`test_the_questionnaire_form_patch_refuses_a_holder_everything_and_writes_nothing` (named for a detach
or a move until the gate widened, and re-checked on 2026-10-09 when this pin was found pointing at the
old name) and, over Postgres for all six kinds,
`test_a_post_holder_files_no_record_into_the_workshop_and_takes_none_out`, all in
`backend/tests/test_admin_serve_as.py`.

### [LOW] The workshop's own pages let an administrator holding a post type into writes the server refuses (frontend) — **CLOSED 2026-10-09**

When the server began refusing a post holder every write to a workshop's content and designer team
([PERMISSIONS.md](PERMISSIONS.md) §4.8 rule 5), the web went on drawing every write control for the
administering tiers that hold one — an admin or the master admin reaches every workshop's controls
through the admin arm — so one appointed a workshop's inspector or director could type a stage, a
custom section or a report colour into a form whose every save was refused, and learn why only from
the 403. Nothing reached the workshop and nothing typed was lost, but nothing warned before the
typing.

Closed the same day for the tiers that may appoint. `useHeldPostRefusal` in
`frontend/components/designworkshop/HeldPostNotice.tsx` asks the server which posts the reader holds
on the workshop and, for a post holder, holds the controls with the server's own sentence as the
reason: the workshop's page — its stage index, the Edit details link and the status and consent
buttons — the stage form, the Edit details page, custom sections, AI layers, photo import, report
settings and the report colour, sketches upload and ranking order, the design-workshop visibility
panel and the Designers panel on Workshop oversight, whose artisan panel was already held. Every read,
the ratings, report generation and the appointment panels stay live, and a write refused anyway still
prints the 403 word for word. Two writes outside those screens answered a post holder with the 403
alone when this closed — the per-row Delete on the workshop list, which would need a staffing read per
row, and a record form's workshop box. The record forms caught up later the same day (they hold Save,
and the workshop box for a stored held workshop), and so did the media controls and then the doors
that add to a workshop (an entry below); the list rows are open above.
Pinned by `frontend/e2e/workshop-post-holder-readonly-unit.spec.ts`.
**The rest is open above**: the reads the warning rests on belong to accounts that may appoint, so an
Assistant or Regional Director holding a post is still warned only by the 403.

### [HIGH] A Ministry Admin could lift an administrator's bar by moving the barred account to another address (backend) — **CLOSED 2026-10-09**

`PATCH /api/users/{id}` asked about an address change only at the DESTINATION —
`account_provisioning.assert_not_overturning_a_bar` refused a non-admin moving an account onto a
REJECTED or SUSPENDED address, or a `DESIGNER` onto an ended empanelment — and never about the address
the account was leaving; and `access_roster.follow_email_change` admitted whatever row it found at the
new address. So a suspended person signed in once with Google at a second address, which leaves a
PENDING row and no account; a Ministry Admin "corrected" their address onto it; the planted row was
admitted ACTIVE, the SUSPENDED row stayed on an address no account held — the access screen still
showing the bar — and the person signed in with their password. A move to a fresh address took back
in a designer whose empanelment had been ended, too: their allow-list row was still ACTIVE (an ended
empanelment mirrors only onto a row that admitted the person as a designer), and the first sign-in at
the new address empanelled it afresh. Only admins could change an address before that day's ruling,
so the hole arrived with the Ministry Admin's address corrections and was found by their review.

Closed the same day, at both ends. A non-admin's move off a barred address, or of a `DESIGNER` off an
ended empanelment, is a 409 naming the bar (`account_provisioning.assert_not_escaping_a_bar`), before
anything is written. And the bar now goes with the account whoever moves it, an admin included:
`follow_email_change` carries a REJECTED or SUSPENDED status onto the destination's row with a note —
an ACTIVE row there included — or, where there is none, creates a barred row there and leaves the old
one barred, and `designers.carry_ended_empanelment` carries an ended empanelment to the new mailbox.
(The barred row itself MOVED when this closed, which lifted the bar on the old mailbox; that, and a
non-admin's move ending an active empanelment, were closed later the same day — entries below.)
Letting the person back in is the access screen's act. Pinned by
`test_a_ministry_admin_cannot_move_a_suspended_account_onto_a_planted_address`,
`test_a_ministry_admin_cannot_move_a_rejected_account_to_a_fresh_address`,
`test_a_ministry_admin_cannot_move_a_designer_off_an_ended_empanelment` and
`test_an_admin_may_move_a_barred_account_and_the_bar_goes_with_it` in
`backend/tests/test_account_provisioning.py`.

### [MEDIUM] Sessions opened with a temporary password outlived its replacement, and a sign-in racing a reset outlived the reset (backend) — **CLOSED 2026-10-09**

Two holes in session revocation, both found by the review of that day's forced-change work. **The
forced change released sessions rather than ending them.** The hold on a flagged account was per
account, the change cleared it, and `POST /api/auth/change-password` wrote no watermark — so every
session opened with the temporary password, by the provisioner who typed it or by anybody who read the
message it travelled in, had full access as the owner from the moment the owner replaced it, for the
rest of its seven days; a test pinned the release. **And the watermark was stamped too early.**
`PATCH /api/users/{id}` and `POST /api/auth/set-password` took the timestamp before bcrypt and the
awaited checks, so a sign-in with the old password that read the row before the commit and minted its
token in a later wall second post-dated the revocation and kept a week of access.

Closed the same day by binding every session to the password it was opened with
([SECURITY.md](SECURITY.md) §3.6): each token carries a fingerprint of the account's `passwordHash`,
`deps._user_from_bearer` refuses one whose fingerprint no longer matches, and change-password hands
the session that made the change a fresh token — in the answer's body at first, and in its
`X-Session-Token` header since the entry below moved it there. Every writer of the watermark now
takes its timestamp after bcrypt, immediately before the write. Pinned by
`test_a_session_opened_with_a_temporary_password_ends_when_it_is_replaced`,
`test_a_voluntary_change_ends_the_other_sessions_too`,
`test_a_sign_in_that_raced_a_reset_is_refused_on_first_use`,
`test_a_token_ends_when_the_password_it_was_opened_with_does` and
`test_every_bearer_token_the_application_mints_is_bound_to_a_password` in
`backend/tests/test_password_change_enforcement.py`. **One residue is open above**: tokens minted
before the release carry no fingerprint and run to their expiry. The other it left — handsets on
0.0.6 to 0.0.15 misreading an answer that carried the token in its body — is closed below, and so,
for the next build, is a handset that noticed a session ended this way only when the app was
restarted.

### [MEDIUM] The master admin's first Google sign-in could land on, and promote, an account a provisioner planted under another spelling of its mailbox (backend) — **CLOSED 2026-10-09**

`login_with_google` takes `MASTER_ADMIN` from the literal address and, on a literal miss, signs in to
the one password account on the same Gmail mailbox (the entry above on a second account) — and then
wrote `MASTER_ADMIN` onto whatever account it had found. `account_provisioning.is_master_email`
compared strings, so a Ministry Admin or an admin could create `newmaster+x@gmail.com` beside a master
configured as `new.master@gmail.com` — one inbox — with a password of their choosing. On a deployment
where the master's own row did not exist yet (a handover that changes `MASTER_ADMIN_EMAIL`, an unseeded
box), the master's first Google sign-in landed on that account and promoted it, the provisioner's
password still on it: a break-glass master admin nobody can manage, opened by its planter.

Closed the same day. The master's sign-in never folds — it signs in to the literal row or creates one
there — and the elevation is written only onto an account found under the literal address.
`is_master_email` compares canonical forms, so on the users screen and from the operator script
nobody but a master admin creates an account on, or moves one onto, any spelling of the master's
mailbox (403), while the master's own protections stay on the literal address (`is_master_address`).
The sanction register could still create the account there, and the elevation was still written onto
whatever account sat at the literal address, until later the same day (an entry below). Pinned by
`test_nobody_but_a_master_admin_puts_an_account_on_any_spelling_of_its_mailbox` and
`test_the_master_admins_google_sign_in_never_lands_on_another_spelling` in
`backend/tests/test_account_provisioning.py`, and
`test_the_master_admins_mailbox_is_every_spelling_of_it_and_its_account_only_one` in
`backend/tests/test_auth_identity_and_password_links.py`.

### [MEDIUM] A post holder could change a workshop's photographs, recordings and transcripts, and edit or delete the records filed under it (backend) — **CLOSED 2026-10-09**

The serve-as ruling refuses whoever holds a workshop's inspection or one of its director posts every
write to its content, through the admin routes too — and the media routes asked nothing. An ADMIN
appointed a workshop's inspector could delete one of its stage photographs (`DELETE /api/media/{id}`),
rewrite or re-run a recording's transcript — which the report's annexure then prints, stamped as a
human edit — or discard an identity photograph, each answering 2xx, while the researcher guide said
such a holder changes no stage "even with admin rights" and [PERMISSIONS.md](PERMISSIONS.md) said the
content was closed "through the admin routes too". The record forms had the same gap one door over:
the entry above on unfiling closed taking a record out of a held workshop, and left every other
`PATCH` of a record filed there, its `DELETE`, an interview merge, a tool's artisan links and every
edit of a questionnaire form attached to it open to a holder who may edit the record. Found by the
review of the serve-as change.

Closed the same day. The media doors ask `design_workshop_posts.refuse_a_holders_media_write` before
they write — the delete, the transcript's set, refine and transcribe-now, the identity photograph's
decision either way, and the relink at both ends — for every workshop a file belongs to, found five
ways (`media_design_workshop_ids`). Every write to a record filed under a workshop asks
`record_design_workshop.assert_may_write_a_record_filed_under`, the unfiling gate widened and renamed,
and a questionnaire form's edits ask `_refuse_its_workshops_holder` in
`backend/app/api/routes/questionnaire_forms.py`. An administrator who holds no post there keeps every
power. Pinned by `test_a_post_holder_changes_no_file_their_workshop_holds_by_any_door`,
`test_a_file_belongs_to_every_workshop_that_names_it_by_any_of_five_ways`,
`test_every_media_door_asks_about_the_files_workshops_before_it_writes`,
`test_a_post_holder_edits_and_deletes_no_record_filed_under_their_workshop`,
`test_a_post_holder_merges_no_sitting_on_either_side_of_their_workshop`,
`test_a_post_holder_changes_no_artisan_link_of_a_tool_filed_under_their_workshop` and
`test_every_other_write_to_a_filed_record_asks_about_the_workshop_it_is_filed_under`, all in
`backend/tests/test_admin_serve_as.py`.

**Found while closing it:** keeping an identity photograph
(`POST /api/design-workshops/ocr/identity/retention` with `STORE`) had always answered 500 on Postgres:
it wrote a bare dictionary into a JSON column the database client refuses, and its only tests ran
against an in-memory store that accepted one. It writes the column through `records.jsonify_metadata`
now, and the store in `backend/tests/test_identity_photo_retention.py` refuses a bare dictionary as the
table does. Three doors that did not ask yet — the review queue's edit, a new upload and a retried
transcription — were closed later the same day (entries below), and so were the unfiled-records
report's three doors, which wrote a workshop's records and files by another route (an entry below).
The web held the media controls the same day and the doors that add to a workshop after that (an
entry below); what it still does not warn about is open above.

### [LOW] A promoted account kept the credentials a lower provisioner held for it (backend) — **CLOSED 2026-10-09**

A set-password link is authorised once, when it is issued, and a temporary password is known to
whoever typed it; neither looked at the account's tier again. So a Ministry Admin who created an
account, or issued it an invitation, still held that credential after an admin promoted the account to
ADMIN — the routine way to onboard an admin, since a Ministry Admin cannot create one — and could redeem
the link, or replace the temporary password, and hold an ADMIN account with the deletes, grants and bar
decisions the ruling withholds from the tier. Noisy and attributable, which is why it is LOW.

Closed the same day. A `PATCH /api/users/{id}` that raises the role withdraws every outstanding link in
the same request (`credential_links.revoke_outstanding`) and answers 409 while the account still holds
a temporary password unless the same request sets a new one; and redeeming a link asks again whether
its issuer could still manage the account (`account_provisioning.issuer_still_manages`, read on the
check and on the redemption alike), reading as withdrawn otherwise. Pinned by
`test_a_promotion_withdraws_the_links_a_lower_provisioner_issued` and
`test_a_promotion_waits_for_a_temporary_password_to_be_replaced` in
`backend/tests/test_account_provisioning.py`, and
`test_a_link_is_redeemable_while_its_issuer_could_still_manage_the_account` and
`test_a_link_whose_issuer_was_outranked_reads_as_withdrawn` in
`backend/tests/test_auth_identity_and_password_links.py`. The access screen's approve and re-admit,
which lifted an account without asking either question, caught up later the same day (an entry
below).

### [LOW] A refused record create could leave an orphan `Location` row, and a craft, behind (backend) — **CLOSED 2026-10-09**

The artisan, product and tool creates wrote the record's `Location` row (`attach_location`) — and the
artisan create looked up, and could create, its craft (`resolve_craft_id`) — before asking the workshop
gates. A create those gates then refused, a post holder filing into a held workshop among them, left
those rows behind with nothing pointing at them. Found by the review of the serve-as change.

Closed the same day: the workshop gates run first on the creates, and on the artisan, product, tool
and interview `PATCH`es as well, so a refusal writes nothing. Pinned by
`test_a_refused_create_mints_no_location_and_no_craft` in `backend/tests/test_admin_serve_as.py`,
with the order pinned on the routes' source beside it.

### [LOW] A sanction-register designer who signed in with Google first was held behind a gate asking for a password nobody had (backend) — **CLOSED 2026-10-09**

The sanction register creates the account of each designer an order names, with a random password
nobody is ever shown, and issues an INVITE link for the designer to choose their own. It raised
`mustChangePassword` on that account, as though somebody had typed the password. A designer who pressed
"Sign in with Google" instead of opening the link signed in to that account — a password account keeps
its flag through a Google sign-in — and met the server's hold, on the web and the handset alike, with a
gate asking for a current password nobody had; only the original link, or one an officer re-issued,
got them out.

Closed the same day: the register creates the account with the flag down, since a secret nobody was
shown is not a shared one, and the link still sets the password. Pinned by
`test_a_designer_who_signs_in_with_google_first_is_not_held_and_the_link_still_works` in
`backend/tests/test_sanction_orders.py`, which also shows the redemption ending the Google session, and
by `test_five_fields_produce_seven_rows` in the same file asserting the flag down.

### [LOW] Accounts in use before `firstLoginAt` existed were given 72-hour INVITE links where a 2-hour RESET was meant (backend) — **CLOSED 2026-10-09**

`credential_links.purpose_for` began that day to read an empty `firstLoginAt` as "nobody has signed in
yet", so that an account a provisioner had just created with a password got a 72-hour invitation
rather than a 2-hour reset. But the column's migration of 2026-08-30 backfilled nothing, so every
account in use before then carried an empty `firstLoginAt` it never earned — and the dormant,
established accounts that most often need a reset were handed a link thirty-six times as long-lived,
which ends their sessions when it is redeemed.

Closed the same day without a backfill: an empty `firstLoginAt` counts as "never signed in" only on an
account created on or after 2026-08-30 17:00 UTC (`credential_links.FIRST_LOGIN_TRACKED_SINCE`), and
every other account with a password gets RESET, as before the change. Pinned by
`test_the_link_purpose_follows_whether_anybody_has_used_the_account` in
`backend/tests/test_auth_identity_and_password_links.py`,
`test_the_link_is_an_invitation_until_the_account_is_used` in
`backend/tests/test_account_provisioning.py`, and the INVITE re-issues asserted in
`test_a_throttled_link_does_not_roll_back_the_sanction_order` in `backend/tests/test_sanction_orders.py`.

### [LOW] The operator script's `--google-only` mode wrote a second allow-list row beside a legacy spelling of the mailbox (backend) — **CLOSED 2026-10-09**

`backend/scripts/provision_account.py --google-only` read the allow-list under the typed address —
which finds a row stored under another Gmail spelling — and then wrote under the canonical one, and
`access_roster.admit`'s key list for a canonical address is that address alone. So against a legacy row
stored with dots (`record_refused_attempt` wrote rows as typed between 2026-08-16 and 2026-08-30) it
created a second row and printed "ADMITTED", while the legacy row went on deciding the sign-in: a
PENDING person stayed pending, and an ACTIVE row at another tier went on creating the account at that
tier.

Closed the same day: the admission writes through the row it found, under that row's own spelling, as
the access screen's decision does. Pinned by
`test_google_only_writes_through_a_legacy_spelling_of_the_mailbox` in
`backend/tests/test_account_provisioning.py`.

### [MEDIUM] A token in the change-password answer's body made handsets on 0.0.6 to 0.0.15 report a successful change as a failure, quoting the token (backend) — **CLOSED 2026-10-09**

When every session became bound to the password it was opened with (the entry above on sessions that
outlived a temporary password), `POST /api/auth/change-password` began to answer
`{"ok": true, "accessToken": "<token>"}`, so that the session making the change could carry on. Every
handset build that can call the route — 0.0.6 to 0.0.15, the builds with the gate screen — declares
that answer as `Map<String, Boolean>` (read it with
`git show v0.0.15:android/app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt`),
so decoding the token failed AFTER the server had changed the password: the gate showed the decoder's
message, which quotes the whole token, as the reason the change had failed — putting on the screen a
live seven-day session that nothing had stored. Reproduced that day by decoding the answer the way
those builds declare it, and recorded open by the review.

Closed the same day by taking the token out of the body. The body is exactly `{"ok": true}` again, as
it was before the binding, and the token minted after the write rides in the response header
`X-Session-Token` (`SESSION_TOKEN_HEADER` in `backend/app/api/routes/auth.py`), which
`backend/app/main.py` adds to CORS `expose_headers` so the web may read it. The web
(`changeOwnPassword` in `frontend/lib/signIn.ts`) and the next Android build
(`WorkshopRepository.changeOwnPassword`) adopt it before they re-read `/me`, and neither looks for a
token in the body. Builds 0.0.6 to 0.0.15 decode the answer as they always have and report the change
as made; the session they hold was opened with the old password, so their next request is a plain
401, which those builds notice only at their next launch, and the person then signs in with the
password they have just chosen — the cost the binding's ruling accepted for a client that does not
adopt the token ([SECURITY.md](SECURITY.md) §3.6). Pinned by
`test_the_new_session_token_rides_in_a_header_a_browser_may_read` and
`test_a_browser_may_send_the_change_and_read_the_token_it_hands_back` in
`backend/tests/test_password_change_enforcement.py`, whose every change also asserts a body of exactly
`{"ok": true}` beside the header, as `backend/tests/test_change_password_budget.py` does; by
`frontend/e2e/password-change-enforcement-unit.spec.ts`, which never adopts a token from the body and
reads `expose_headers` and the route's answer off the backend's source; and by
`the body is the one every build decodes, shipped or not` in
`android/app/src/test/java/com/designprototype/workshop/data/ChangePasswordSessionTest.kt`, which
decodes the body with the shipped builds' map as well as this build's type, and shows the map
refusing a token beside `ok`.

### [LOW] A promotion through the access screen did not wait for a temporary password to be replaced, nor withdraw the account's links (backend) — **CLOSED 2026-10-09**

A `PATCH /api/users/{id}` that raises an account's role withdraws its password links and waits for a
temporary password to be replaced (the entry above on a promoted account). The access screen's
approve and re-admit raise an existing account to the tier approved as well — `_lift_existing_account`
in `backend/app/api/routes/access.py` — and asked neither question. A link was still caught at
redemption, which re-asks whether its issuer could manage the account; a temporary password was not,
so an admin who approved or re-admitted the address of an account a Ministry Admin had created, at a
tier above the Ministry Admin, left the Ministry Admin holding a password into an account it could
never have managed. Narrow and attributable, and recorded open by the review of that day's change.

Closed the same day with the `PATCH`'s rule, through one predicate both doors ask
(`account_provisioning.holds_a_temporary_password`). A lift withdraws every outstanding link after
its write, a master admin's included. An account still holding a temporary password is not lifted:
this door has no password field, and refusing the approval would leave the person's access undecided
over a question about their tier, so the approval of the address stands, the account keeps its tier,
and the decision's answer carries `accountPromotionHeld` — `APPROVAL_KEEPS_THE_TIER_DETAIL`, naming
the address, both tiers and the two ways on — which `/admin/access` shows word for word in place of
its receipt ([PERMISSIONS.md](PERMISSIONS.md) §1.2). Pinned by
`test_an_approval_that_lifts_an_account_withdraws_its_links` and
`test_an_approval_leaves_an_account_holding_a_temporary_password_at_its_tier` in
`backend/tests/test_account_provisioning.py`, and by
`test_a_temporary_password_is_the_flag_on_an_account_that_has_one`,
`test_the_access_approval_lifts_an_account_and_withdraws_its_links` and
`test_the_access_approval_leaves_a_temporary_password_at_its_tier` in
`backend/tests/test_auth_identity_and_password_links.py`; the screen's half by
`frontend/e2e/users-provisioning-unit.spec.ts`. The handset decodes the answer as the roster row and
skips the key, so an approval made on a phone does not say that the tier was held back.

### [MEDIUM] The review queue's edit changed a record or a file of a workshop its reviewer holds a post on, and re-filed a record with no filing gate at all (backend) — **CLOSED 2026-10-09**

`POST /api/review/{recordType}/{recordId}/edit` (`edit_reviewed_record` in
`backend/app/api/routes/review.py`) corrects a record's fields in place from the review queue, for a
reviewer who may edit that record — Professor and above, on a record created below them. It validated
the payload against each record type's own update schema and asked nothing about design workshops.
So an administrator holding a workshop's inspection or a director post could change a record filed
under that workshop, or a file's caption and transcript, through this door, where every other door
refused them ([PERMISSIONS.md](PERMISSIONS.md) §4.8 rule 5). And, older than that ruling,
`designWorkshopId` was in those update schemas and not in `_NOT_REVIEW_EDITABLE`, so this edit filed a
record into a design workshop, or took it out of one, with no filing gate at all — the write
`backend/app/services/record_design_workshop.py` exists to guard — into a workshop the reviewer could
not even see included. Recorded open by the review of that day's change.

Closed the same day. Before its transaction the edit asks the record gate,
`record_design_workshop.assert_may_write_a_record_filed_under`, about the record as stored, or
`design_workshop_posts.refuse_a_holders_media_write` about a file, so a post holder gets the same 403
and sentence as at every other door and nothing is written; a role that can hold no post is answered
without a staffing read. `designWorkshopId` is in `_NOT_REVIEW_EDITABLE`, a 422 for everybody, the
master admin included, like `workshopId` beside it: filing belongs to the record's own form. Approve,
reject and send back stay open to a holder, being moderation rather than authorship. Pinned over
Postgres by `test_a_post_holder_rewrites_nothing_of_their_workshop_from_the_review_queue` in
`backend/tests/test_admin_serve_as.py`, for every reviewable kind, with the order held on the source
by `test_the_upload_the_job_retry_and_the_review_edit_ask_before_they_write`; and without a database
by `test_the_workshops_inspector_rewrites_none_of_its_records_from_the_queue`,
`test_a_role_that_holds_no_post_is_answered_without_asking`,
`test_a_review_edit_files_no_record_under_any_workshop` and
`test_a_file_of_the_workshop_is_not_its_holders_to_caption_from_the_queue` in
`backend/tests/test_review_edit_authority.py`. The web's edit panel holds its boxes and both Saves for
such a reviewer since later the same day (the entry below on four web doors).

### [LOW] Two media doors were not refused to a workshop's post holder: a new upload into it, and a retried transcription (backend) — **CLOSED 2026-10-09**

The media doors that change a file a workshop holds refused its post holders from that day (the entry
above on a workshop's files and records); two that add to it did not. `POST /api/media/complete`
registered a holder's NEW upload tagged to the workshop, or attached to a record filed there — only an
upload filed by its `designWorkshopId` met the edit loader. And `POST /api/media/jobs/{jobId}/retry`
re-queued a failed transcription of one of the workshop's recordings, whose text the queue then writes
as its transcript — the very write `POST /api/media/{id}/transcribe-now` is refused. Recorded open by
the review of that day's change.

Closed the same day with the call the other media doors make,
`design_workshop_posts.refuse_a_holders_media_write`. The upload asks it about the row it is about to
create (`_upload_as_filed` in `backend/app/api/routes/media.py`: the workshop the upload is filed
under and its link tag, with no id, so the stage and AI-layer reads answer nothing without a query),
after the replay of the caller's own earlier upload of the same object, which is answered as before,
and before the storage check, the `Location` row and the create. The retry asks it about the job's file
before re-queuing the job. Pinned over Postgres by
`test_a_post_holder_uploads_no_new_file_into_their_workshop` and
`test_a_post_holder_requeues_no_transcription_of_their_workshops_recording`, and without a database by
`test_an_upload_is_read_as_the_row_it_would_become` and
`test_the_upload_the_job_retry_and_the_review_edit_ask_before_they_write`, all in
`backend/tests/test_admin_serve_as.py`. The web held both — `/media`'s Upload and the jobs panel's
Retry — later the same day (the entry below on four web doors).

### [LOW] A post holder's join-card scan was told the card "had already been used" (backend) — **CLOSED 2026-10-09**

A join card scanned by an account the grant rule refuses — since 2026-10-09 that includes whoever
holds an inspection or a director post on the card's workshop — lands as a provisional foothold marked
`INELIGIBLE`, without spending the seat, as it should ([PERMISSIONS.md](PERMISSIONS.md) §4.8). The
answer carried the spent card's sentence, `_PROVISIONAL_DETAIL` in
`backend/app/services/design_workshop_grants.py`, which begins "That card had already been used, so
you are not on the workshop yet" — untrue for this outcome, where the card was not used up and the
refusal is about who scanned it. Older than the ruling, for every ineligible scanner, and recorded
open by the review of that day's change.

Closed the same day with a second sentence rather than an edit to the first: `_INELIGIBLE_DETAIL`
says the card cannot put the account on the workshop by itself, that nothing recorded is lost and that
the card was not used up, and names no reason — no roster, no role, no post. `_provisional_detail`
chooses it for the first answer and for a replay alike. Pinned by
`test_a_card_scanned_by_an_ineligible_account_never_spends_its_seat`, replay included, and
`test_a_post_holder_scanning_the_card_lands_ineligible_and_keeps_the_seat` in
`backend/tests/test_design_workshop_grant_tokens.py`, which write the sentence out rather than import
it, and over Postgres by
`test_a_post_holder_who_scans_their_workshops_join_card_lands_provisional_and_spends_nothing` in
`backend/tests/test_admin_serve_as.py`. The scan that syncs after the card's date, which this fix left
alone, is the entry below.

### [LOW] A join-card scan that synced after the card's date was told the card "had already been used" (backend) — **CLOSED 2026-10-09**

A genuine join-card scan that reaches the server after the card's date, inside the grace window, lands
as a provisional foothold marked `EXPIRED` — never a full grant, because expiry is judged by when the
scan arrived and a device clock cannot buy an extension, and never thrown away, because the fieldwork
behind it is real. The answer carried `_PROVISIONAL_DETAIL` in
`backend/app/services/design_workshop_grants.py`, the spent card's sentence, which opens "That card had
already been used, so you are not on the workshop yet" — untrue for this outcome, which is about the
card's date and not its seats. Found while the same sentence was replaced for an ineligible scanner
(above), whose fix left this arm alone; older than 2026-10-09. Nothing was lost or wrongly granted:
only the reason the scanner read was wrong.

Closed the same day with a third sentence: `_EXPIRED_DETAIL` says the card's date had passed by the
time the scan reached the server, that nothing recorded is lost and that the card was not used up, and
— because the scan waits as a PENDING request — that an administrator can confirm it.
`_provisional_detail` chooses it for the first answer and for a replay alike. Pinned by
`test_an_expired_card_never_becomes_a_full_grant` in
`backend/tests/test_design_workshop_grant_tokens.py`, replay included, which writes the sentence out
rather than importing it.

### [MEDIUM] The sanction register could create the account on the master admin's mailbox, for the master's first Google sign-in to promote with the officer's password still on it (backend) — **CLOSED 2026-10-09**

The entry above on the master admin's first Google sign-in closed the fold onto a planted spelling,
and the sentence that closed it — nobody but a master admin creates an account on that mailbox — had
one door it did not cover. The sanction register creates the account of every designer an order
names, and `sanction_orders.designer_standing_verdict` asked about the officer's own mailbox, the
allow-list, the empanelment and the accounts already there, and never whether the address was the
master's. With no account at `MASTER_ADMIN_EMAIL` yet — a handover, an unseeded box, the same
precondition — an Assistant Director, a Regional Director, a Ministry Admin or an admin could record an
order naming that address as its lead or a co-designer: the order created the account at `DESIGNER`,
admitted and empanelled it, and its answer handed the officer a 72-hour first-password link. The
officer set a password; admins could no longer suspend, edit or delete the account, which sat at the
master's address; the master's first Google sign-in found it under the literal address and wrote
`MASTER_ADMIN` onto it, keeping the officer's password; and only `scripts/seed_admin.py` or the
database could take it back. An admin could plant it this way although `POST /api/users` refused them
that address. Found by the focused review of that day's final pass and traced end to end.

Closed the same day. `designer_standing_verdict` refuses any spelling of the mailbox with a 422,
`SANCTION_MASTER_MAILBOX`, right after the self-naming check and before any read
(`master_mailbox_reason`, through `account_provisioning.is_master_email`), so one question closes the
single-order route — its lead and every co-designer — and the spreadsheet importer, where it is a
refusal and never offered for confirmation. It refuses whoever records the order, and whether or not
the account exists. `reissue_credential_link` refuses the same way for an account an older order put
on that mailbox. Beneath every door, `login_with_google` writes the elevation only onto an account at
the literal address that is already a master admin or holds no password; any other is answered 409
naming `scripts/seed_admin.py`, with nothing written and its id and role logged at ERROR
([SECURITY.md](SECURITY.md) §3.3), so a handover onto an existing password account now runs that
script first ([DOCKER.md](DOCKER.md)). An account planted before the fix is not cleaned up: it can no
longer be promoted or handed a re-issued link, and repairing it is still the script. Pinned over
Postgres by `test_no_order_names_any_spelling_of_the_master_admins_mailbox` in
`backend/tests/test_sanction_orders.py` — the lead, a dotless spelling, a `+tag` on `googlemail.com`
and a co-designer, each a 422 carrying the sentence alone, with no account, allow-list row,
empanelment or order written for anybody on the order — and without a database by
`test_the_master_admins_mailbox_is_refused_by_the_real_verdict_and_never_confirmed` in
`backend/tests/test_sanction_import.py`,
`test_no_link_is_reissued_for_an_account_on_the_master_admins_mailbox` in
`backend/tests/test_sanction_order_designer_eligibility.py` and
`test_the_masters_google_sign_in_promotes_no_account_somebody_else_holds_a_password_to` in
`backend/tests/test_auth_identity_and_password_links.py`.

### [LOW] An administrator's address correction moved a barred allow-list row off the old mailbox, so a sign-in there was queued as a stranger's (backend) — **CLOSED 2026-10-09**

The entry above on a Ministry Admin lifting a bar made the bar go with an account whoever moves it:
`access_roster.follow_email_change` carried a REJECTED or SUSPENDED status onto the destination's row
— and, where the destination had none, MOVED the barred row there. That left the old mailbox with no
row at all. So when an admin corrected a suspended researcher's address from their Gmail address to a
fresh one, the person's next Google sign-in at the old address met no row, was told their request was
waiting for an administrator, and appeared on the access screen as a brand-new PENDING request with
no trace of the suspension; an admin working the queue approved it, and the next Google sign-in,
finding no account on that mailbox, created one — the person the suspension barred, back in under a
new account. The row had always moved; the empanelment carry beside it already kept its old row, for
the reason it gives — a revocation is never undone by a side effect. Found by the focused review.

Closed the same day. When the old row is barred and the destination has none, `follow_email_change`
CREATES a barred row at the new mailbox — the status, who decided it and when (the mover's id only
where none was recorded), the tier and the name, with `BAR_CARRIED_BY_EMAIL_MOVE_NOTE` — and leaves the
old row barred where it is; a row a sign-in writes there between the read and the create takes the
bar as an existing row would. The admin's audit line says the bar went with the account and its old
address stays barred. Pinned over Postgres by
`test_an_admins_correction_bars_the_new_mailbox_and_leaves_the_old_one_barred` in
`backend/tests/test_account_provisioning.py` — both rows SUSPENDED, a Google sign-in at the old address
still refused as suspended with no PENDING row written, and a password sign-in at the new address
refused — and without a database by
`test_a_barred_row_is_created_at_a_fresh_mailbox_and_the_old_one_stays_barred` and
`test_a_row_a_sign_in_writes_in_the_race_takes_the_bar_instead` in
`backend/tests/test_auth_identity_and_password_links.py`.

### [LOW] A non-admin's address correction could end an administrator's active empanelment, and could put a second account on another spelling of a live account's Gmail mailbox (backend) — **CLOSED 2026-10-09**

`follow_email_change` carries an ended empanelment to the new mailbox whatever the account's role, and
carrying it ENDS an active empanelment there (`designers.carry_ended_empanelment`), while the
non-admin 409s asked about empanelments only for a `DESIGNER`. So a Ministry Admin moving a professor,
an inspector or a directorate officer whose old address carried an empanelment an administrator had
long ago ended — or a de-empanelled designer, demoted in the same `PATCH` — onto an address an
administrator had empanelled ended that empanelment, with nothing in the answer to say so, though
ending one is an admin's act. And the duplicate check compared addresses as typed, so such a move could
land on a dotless or `+tag` spelling of a live designer's Gmail mailbox: one inbox, two accounts. The
move rewrote that designer's allow-list decision and notes, ended their empanelment, and their next
sign-in was refused. Found by the focused review, which traced the second half.

Closed the same day, at both ends. For a provisioner who is not an admin,
`account_provisioning.assert_not_escaping_a_bar` refuses with a 409,
`ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL`, a move of an account of any role from an address with an
ended empanelment onto one with an active empanelment (`empanelment_active`); an admin's move still
carries the ending, as documented, and its audit line says so. And `email_in_use` asks about the
mailbox for every actor: after the literal check, any Gmail address is looked up through
`access_roster.accounts_on_the_mailbox_for_sign_in`, the account being moved left out, so no account
is created at, or moved onto, a mailbox another account uses — "Email already exists" — and a lookup
that cannot finish refuses with a 503 rather than guessing. An account already sharing a mailbox with
another cannot be respelled within it until the pair is merged or corrected. Pinned over Postgres by
`test_a_ministry_admin_cannot_end_an_empanelment_by_moving_an_account_onto_it`,
`test_an_admins_same_move_carries_the_ending_onto_the_active_empanelment`,
`test_no_account_is_created_on_a_mailbox_another_account_uses` and
`test_no_account_is_moved_onto_another_accounts_mailbox_and_its_owner_keeps_everything` — the other
account's row, note, empanelment and sign-in untouched — in `backend/tests/test_account_provisioning.py`,
and without a database by
`test_one_account_per_mailbox_is_asked_of_gmail_alone_and_never_of_the_account_moving` and
`test_only_an_admin_carries_an_ended_empanelment_onto_an_active_one` in
`backend/tests/test_auth_identity_and_password_links.py`.

### [MEDIUM] The unfiled-records report let a workshop's post holder delete, file and bulk-file its records and files, and offered any administrator a design workshop's evidence to delete as "unfiled" (backend) — **CLOSED 2026-10-09**

The report of records with no workshop on the `/workshops` page (`/api/workshops/unmapped`,
`backend/app/services/workshop_inference.py`) read "no workshop" as an empty CRAFTS `workshopId`. A
record filed under a design workshop, an artisan imported onto its roster and a stage photograph
tagged to it are empty there too, so the report listed a workshop's evidence as having "nothing on the
record that points at a workshop", and its three doors asked nothing about design workshops and threw
the caller away. An ADMIN appointed a workshop's inspector, refused `DELETE /api/media/{id}` and
`DELETE /api/artisans/{id}` with rule 5's 403, could delete the same stage photograph — its AI layers
and stored object with it, the stage entry left naming an id nothing answers to — or the same roster
artisan through `DELETE /api/workshops/unmapped/{bucket}/{id}`; could file a product filed under the
workshop under a crafts workshop with `POST /api/workshops/unmapped/{bucket}/{id}`, a write its record
form refused; and could have the bulk `POST /api/workshops/unmapped/map` stamp a crafts workshop on the
workshop's rows. Every other administrator was invited to delete that evidence permanently as unfiled.
Older than the serve-as ruling, which never reached this module; found by the focused review.

Closed the same day. A row a design workshop claims is no longer unfiled: the ladder's reads leave out
every record whose `designWorkshopId` is set and every file filed under a design workshop or tagged to
one, the tag compared in any letter case and the filter written so a file with no tag is still read
(`_UNFILED_RECORD`, `_UNFILED_MEDIA`). The discard refuses a holder of any workshop that claims the row
— a file by any of the five ways it can belong to one — with rule 5's 403, and every other
administrator with a 409 naming the workshop and sending them to the record's or file's own screen,
before anything is counted, deleted or removed from storage; its delete carries the same conditions.
The single-row filing refuses a holder with the 403. The bulk filing leaves the rows a holder holds
alone and reports them — `heldBack` per bucket, `totals.heldBack` and one sentence in `heldBackDetail`
— rather than refusing a run that is otherwise the server's own derivation. All three routes bind the
caller. A file claimed only through a stage entry, an AI layer or the record it hangs off is still
listed, and an administrator who holds no post there may still file it under a crafts workshop, singly
or in bulk, as the ruling allows — only its crafts column moves, and only from empty
([PERMISSIONS.md](PERMISSIONS.md) §4.8). Pinned over Postgres in `backend/tests/test_admin_serve_as.py`
by `test_the_unfiled_report_lists_no_row_a_design_workshop_claims`,
`test_the_batched_holder_question_answers_exactly_what_the_file_by_file_one_does`,
`test_nobody_discards_from_the_unfiled_report_a_row_a_design_workshop_claims` — the holder's 403,
every other administrator's 409, the rows and the stored object surviving, and an unclaimed row still
deleted — `test_a_post_holder_files_no_row_of_their_workshop_from_the_unfiled_report` and
`test_a_holders_bulk_map_leaves_their_workshops_rows_alone_and_says_so`; without a database by
`test_the_ladder_reads_no_row_a_design_workshop_claims` and
`test_the_unfiled_doors_ask_about_the_design_workshop_before_they_write` there, and by
`test_the_preview_says_nothing_was_held_back_because_nothing_was_asked` and
`test_a_holders_run_reports_what_it_left_alone_per_bucket_and_in_one_sentence` in
`backend/tests/test_workshop_inference.py`. The web prints all three answers as the server wrote them
(section 6 of `frontend/e2e/workshop-post-holder-readonly-unit.spec.ts`); the handset prints the two
refusals, and its bulk filing does not show what it left alone (open above).

### [LOW] Four web doors the server refuses a post holder asked nothing first, while this register said only the list rows' Delete and indirect files went unwarned (frontend) — **CLOSED 2026-10-09**

The entries above taught the server to refuse a workshop's post holder a review-queue edit of its
records and files, a new upload into it and a retried transcription of one of its recordings, and the
questionnaire doors already refused attaching a form to it. The web held none of them for an
administrator holding a post: the review queue's edit panel drew its boxes and both Saves live;
`/media`'s Upload put every byte into storage before `POST /api/media/complete` refused each file; the
media jobs panel offered Retry; and creating, uploading or reusing a questionnaire into the workshop
was answered with the 403. Meanwhile the open entry above, [PERMISSIONS.md](PERMISSIONS.md) §4.8 and
the researcher guide said only the list rows' Delete and files tied to a workshop indirectly went
unwarned, and the open entry said its sweep caught every web call that changes a stored file, which
missed the unfiled-records report's delete. Nothing was written and every refusal printed word for
word: what was missing was the warning, and the documents overstated what the web gave. Found by the
focused review.

Closed the same day for the tiers that may appoint, with the hold the record forms use (three states
— still asking, none known, held — an always-mounted notice the control points at, and a guard inside
each handler as well as on the control; `frontend/components/designworkshop/HeldPostNotice.tsx`). The
review edit panel holds its boxes, Save and "Save and approve" on the workshop its record or file
names, leaving Approve, Reject and Send for revision live and saying so (`reviewRecordWorkshopIds`,
`reviewEditHold`). `/media` holds Upload on the design workshop chosen and on the filing of the record
the files would hang off, before a byte is sent (`mediaUploadHold`). The jobs panel holds Retry row by
row, its job rows now carrying the file's two workshop links. The questionnaire page's Create, the
upload dialog and the reuse dialog hold on the workshop chosen, the picker staying live so another can
be chosen (`attachHold`). The unfiled-records report prints the discard's 409, the filing's 403 and the
bulk filing's `heldBackDetail` as the server wrote them. A second register reads the tree for every
caller of `uploadMediaBatch(`, `uploadMediaFile(`, `retryMediaProcessingJob(`, the review edit, the
unfiled-records writes, the questionnaire attach calls and the two staffing writes, so a new one fails
until it is held or the reason it needs none is written down. Pinned by section 6 of
`frontend/e2e/workshop-post-holder-readonly-unit.spec.ts`. **The rest is open above**: the list rows'
Delete, a file tied to a held workshop only indirectly, moving an existing form into a held workshop
from its own page, and the unfiled-records report's single-row doors still warn only by the server's
answer.

### [LOW] A workshop's inspector or director could take themselves off the post in one call and then write the workshop, though the 403 told them to ask whoever made the appointment (backend) — **CLOSED 2026-10-09**

Rule 1 of the serve-as ruling refuses appointing yourself, as an act; nothing refused the release.
`replace_inspectors` deleted the caller's own inspection row like any other removal, and an oversight
request emptied or reassigned the caller's own director slot — the routes asked only whether the
caller may appoint. So an ADMIN appointed a workshop's inspector, refused its stage saves with rule 5's
403, could send `PUT /api/design-workshop-inspections/{id}/inspectors` with no ids and write the
workshop a moment later, the deleted row the only record they had held the post; an ADMIN or Ministry
Admin director could do the same through `PUT /api/design-workshop-oversight/{id}`. The 403's own
sentence — ask whoever made the appointment to take you off that post — and the web's copy described a
rule the server did not hold. Recorded as plausible by the focused review, with two ways to close it:
refuse the release, or allow it and audit it. The fix refused it.

Closed the same day. An inspector panel save that would delete the caller's own row, and an oversight
request that would empty the caller's own slot or give it to somebody else, are refused whole with a
409, `design_workshop_posts.self_release_refusal` — "You are this workshop's inspector, and nobody
takes themselves off a post: another administrator has to take you off. Nothing was changed." for an
inspector — before anything is written. Another assigner taking the holder off works as
before, and a holder still takes other people off. The web keeps the reader's own inspector row and
director slot ticked and switched off, with that sentence as the reason, and stops its own save with
the whole 409; the handset's inspectors screen prints the server's 409. Pinned over Postgres by
`test_nobody_takes_themselves_off_an_inspection_or_oversight_post` in
`backend/tests/test_admin_serve_as.py` — the inspector panel, the Assistant Director slot and the
Regional Director slot, each emptied and each handed to somebody else, every row surviving, and
another assigner then taking the holder off — with the sentence held without a database by
`test_nobody_takes_themselves_off_a_post_and_the_sentence_says_who_can`, and the web's half, its
sentence read off the server's source, by section 6 of
`frontend/e2e/workshop-post-holder-readonly-unit.spec.ts`.

### [LOW] A handset kept a session that a password change elsewhere had ended, and retried with it until the app was restarted (android) — **CLOSED 2026-10-09**

Since every session became bound to the password it was opened with (the entry above on sessions
that outlived a temporary password), a password changed on the web, on another phone, by an
administrator or through a redeemed link ends the phone's session, and the server answers its next
request with a plain 401. The handset read a session's end only when the app started (and on the
password gate's own signal): the record outbox keeps a 401 as transient and the design-workshop sync
reads one as a dropped connection, so both retried with the dead token on every pass while the screen
went on saying the work would upload, and nothing sent anybody to sign in until the app was restarted
or signed out by hand. Fieldwork captured over the following days stayed on the phone unsent. Nothing
was lost — the queues kept every entry — though the source's own comment on the gate's copy said the
tablet in the next room "is signed out at its next request". Found by the focused review.

Closed the same day in the Android source. `ApiClient.sessionInterceptor` raises a new
`SessionEndedSignal` when a request that carried a token, sent to the API itself, is answered by a 401
without `X-Password-Change-Required` while the handset still holds that same token; a request with no
token, an answer from another host and a 401 for a token swapped while the request was in flight raise
nothing. The app's root re-reads `/me` with the current token and applies the answer as at launch, so
a session that has really ended is signed out with "This sign-in has ended. If your password was
changed on another device or by an administrator, sign in with the new one." — `SESSION_ENDED_SENTENCE`,
which the launch check now gives too, in place of "Your session expired". The queues treat the 401
exactly as before, and keep their work. While the password gate is on screen the signal is left to the
gate (the entry below). Pinned by
`android/app/src/test/java/com/designprototype/workshop/data/SessionEndedSignalTest.kt`, which runs
the app's own `ApiClient.httpClient` against canned answers:
`a tokened request answered by a plain 401 raises SessionEndedSignal and not PasswordChangeSignal`,
`a gated 401 raises only PasswordChangeSignal`, `an untokened 401 raises nothing`,
`a 401 for a token replaced before the answer arrived raises nothing` and
`a 401 from anywhere but the API raises nothing`. And by
`an ended session is a plain 401 to the token still held, and nothing else is` in
`android/app/src/test/java/com/designprototype/workshop/data/PasswordChangeRequiredTest.kt`, and
`a sign-in that has ended says why, and which password to type` in
`android/app/src/test/java/com/designprototype/workshop/ui/PasswordSetupCopyTest.kt`. No published
build carries it as of 2026-10-09; builds up to 0.0.15 notice at their next launch, queues intact.

### [LOW] The handset's password gate reported a change whose answer was lost as a change that failed, though the new password could already be in force (android) — **CLOSED 2026-10-09**

The server stores a new password before it answers, and from that moment the session the request
carried is retired. On a field connection that answer can be lost — a read timeout, a connection
dropped mid-answer, a gateway's 504 after the origin had committed — and OkHttp could even send the
change a second time by itself after a connection failure. The gate then reported a failure in the
transport's own words, or as "Your new password did not reach the server, so nothing has changed."
when the failure carried none, and a retry, sent with the retired session, came back "This session is
no longer valid. Sign in again." Somebody who then signed out and typed the temporary password they
had been told was still theirs was refused, and concluded they were locked out, though the password
they had just chosen worked. Recorded as plausible by the focused review, which found the "nothing has
changed" sentence rarer than first raised and the gap real.

Closed the same day in the Android source. When a change fails without settling anything — no
answer, a 5xx or a plain 401 — the gate asks `GET /me` with the session it holds before it chooses a
sentence, its button disabled meanwhile (`passwordGateAfterFailure` and `changePasswordOutcomeKnown` in
`android/app/src/main/java/com/designprototype/workshop/ui/PasswordSetupCopy.kt`): refused with a plain
401, it signs out with "Your new password may already be in effect. Sign in with it; if it is refused,
use the one you were given."; an account still owing a password gets the gate's usual words, which are
then true; one owing none closes the gate; anything else keeps the gate up, saying the phone could not
tell whether the password was saved, with the same two-password advice. A refusal the route answered
is shown at once, as before. And change-password, set-password and issuing a link are sent with
one-shot bodies, so OkHttp never transmits one twice after a failure that may have reached the server.
Pinned by the seven probe-path tests in
`android/app/src/test/java/com/designprototype/workshop/data/ChangePasswordSessionTest.kt`, among them
`a lost answer followed by a refused probe signs out, saying the new password may be in force`,
`a gateway's 504 after the send takes the probe path too` and
`a probe that goes unanswered too leaves the outcome unknown, and says so`; by
`OkHttp itself resends an ordinary POST after a 408, and never a change of password` and
`the credential writes reach the wire one-shot, and nothing else does` in
`android/app/src/test/java/com/designprototype/workshop/data/SessionEndedSignalTest.kt`; and by
`a change nobody could confirm never says nothing has changed` in
`android/app/src/test/java/com/designprototype/workshop/ui/PasswordSetupCopyTest.kt`. No published
build carries it as of 2026-10-09.

### [MEDIUM] A set-password link's token was written to the API's journal every time the link was checked (backend) — **CLOSED 2026-10-09**

Both clients asked whether a link was still good with `GET /api/auth/set-password?token=<the token>` —
the web's set-password page through `checkPasswordLink` in `frontend/lib/signIn.ts`, the handset
through `WorkshopRepositoryApi.checkPasswordLink`'s `@Query("token")` — and the API box runs uvicorn
with its access log on (the `ExecStart` in `.github/workflows/deploy-backend.yml`), which writes every
request line, query string and all, to the `fieldrepo` journal. The token is the link's whole
authority: whoever read the line could set the account's password until the link was used or expired,
two hours for a RESET and seventy-two for an INVITE. Nor does the journal stay on the box: a deploy
whose health check never passes prints its last 80 lines, unscrubbed, into the Actions log.

`backend/app/main.py` now attaches `AccessLogRedaction` to the `uvicorn.access` logger when the module
is imported. It rewrites the path argument of uvicorn's access line — the third of the five arguments
all three of uvicorn 0.52.4's HTTP implementations pass — so that the value of `token`,
`access_token`, `id_token`, `refresh_token`, `code`, `key`, `password` or `secret`, in any letter
case, reads `[redacted]`; the rest of the line and every other record pass untouched, and it never
raises. The GET is unchanged, because the builds in the field call it. Pinned in
`backend/tests/test_auth_identity_and_password_links.py` by
`test_the_access_line_for_a_link_check_never_carries_the_token`, which formats uvicorn's own record
with uvicorn's own formatter, by
`test_importing_the_application_puts_the_filter_on_uvicorns_access_logger`, and by the tables beside
them. **The residue is open above**: anything in front of uvicorn that logs the request line still
records the token whenever a client asks the GET. Narrowed the same day: the web now asks
`POST /api/auth/set-password/check` with the token in the body, so the handset's check and the link
itself are what remain.

---

## Closed on 2026-09-03

A remediation wave across five lanes. Every entry below was verified against the tree on the day it
was written, and each names the test that fails without the fix — the rule this register has enforced
since 2026-08-13.

### [HIGH] The dataset-token door had no lockout, and the change-password door had none either (backend) — **CLOSED 2026-09-03**

The per-network credential limiter covered `POST /api/auth/login` and `POST /api/datasets/token`;
nothing anywhere counted failures **per account**, so an attacker distributing guesses across
addresses met no ceiling at all, and `POST /api/auth/change-password` — which a signed-in caller
reaches with the old password — was in front of a bcrypt call with nothing before it.

A per-account budget now sits in `backend/app/scale/rate_limit.py`: **ten failed attempts per account
in five minutes**, taken by hand inside the handler at the first moment the account is known, at
three sites (`routes/auth.login`, `routes/auth.change_password`, `routes/datasets.mint_dataset_token`).
**A correct password is refunded and never counts**, which is what lets the ceiling be this low.
The budget is per ACCOUNT and shared between the interactive door and the dataset door, so a script
guessing at an admin's password closes that admin's own sign-in — a deliberate consequence, and
documented as such in [DATASET_API.md](DATASET_API.md).

The checklist it leaves behind is two items and not one, and nothing checks that you did both: add
an anonymous path to `_CREDENTIAL_PREFIXES` for the per-network budget, **and** take the account
budget in the handler. Pinned by `backend/tests/test_dataset_token_budget.py` and
`backend/tests/test_change_password_budget.py`.

### [HIGH] Suspending an account stopped the next sign-in and left the live session running (backend) — **CLOSED 2026-09-03**

The allow-list is read on the sign-in path, so both barring doors wrote `AccessRoster.status` and
nothing else. An administrator suspended a departing colleague, watched the row go SUSPENDED, told
whoever asked that access was cut — and that colleague's phone went on creating records for the rest
of the token's life, up to seven days. Every other revocation in this product is checked per request;
this one was checked at a door the person had already walked through.

`routes/access.end_live_sessions` now stamps `User.sessionsValidFrom`, which
`deps._user_from_bearer` already compared against the token's `iat` on every authenticated request —
so this needed no session table, no token store and no new read on the hot path. Four doors call it:
`DELETE /api/access/roster/{id}`, the REJECT arm of `POST /api/access/roster/{id}/decision`, and the
two designer-roster doors that end an empanelment (`DELETE /api/designers/roster/{id}` and the same
act through the edit form, `PATCH` with `isActive: false`). **The two halves are guarded
differently, and the asymmetry is the endpoints' own.** Barring from the allow-list is barring from
the application, so it is unguarded. Ending an *empanelment* is narrower, and runs behind
`access_roster.admissions_an_empanelment_carries` — the same guard as the cross-roster mirror — so a
professor or an admin who is on the designer roster because they run workshops keeps both their
access and the session they are in; signing them out there would be an outage, not a revocation.
**A role change deliberately does not sign anybody out.** The lookup reaches every spelling of one Gmail mailbox rather than the one address it was
asked about, which was a narrower gap found and closed the same day. Two residual gaps are in the
*Open* section above. Pinned by `backend/tests/test_suspension_revokes_sessions.py`.

### [MEDIUM] `POST /api/users` was the fourth door that creates a designer and the only one that did not empanel (backend) — **CLOSED 2026-09-03**

An admin creating an account at `DESIGNER` produced a user with no `DesignerRoster` row, so the
person did not appear on `/admin/designers` and nothing about the screen said why. It now empanels
immediately, with `DesignerRoster.addedById` naming the admin who did it, and the row appears before
the person has ever signed in. Adding them again by hand answers 409. **It never revives a suspended
empanelment** — `ensure_empanelled` only ever creates, which is the one rule that door has. Pinned by
`backend/tests/test_users_endpoint_empanels.py`.

### [MEDIUM] A viewer grant kept working after the holder's role left the design-workshop set (backend) — **CLOSED 2026-09-03**

The write path already refused to *issue* a grant to anybody outside `deps.DESIGN_WORKSHOP_ROLES`;
the read path never re-asked, so a demotion left the grant readable. A grant is now honoured only
while the holder's **current** role is in that set. The row is not deleted and starts working again
the moment the role does — which is the right shape, because the grant records a decision somebody
made and a demotion is not a revocation of it. Inspector scope is untouched: an inspector never
reaches `load_workshop_or_404`. Pinned by `backend/tests/test_viewer_grant_role_gate.py`.

### [MEDIUM] `registry_version()` recomputed a SHA-256 over the whole registry on every call (backend) — **CLOSED 2026-09-03**

Memoised against a fingerprint of the installed registry, so a mutation still invalidates it and an
`_install` cannot leave a stale digest behind. Pinned by
`backend/tests/test_registry_version_memo.py`.

### [MEDIUM] The report image prefetch was serial, and one unbounded object read sat inside the budget (backend) — **CLOSED 2026-09-03**

`MediaIndex.prefetch` now downloads ahead on a `ThreadPoolExecutor(max_workers=REPORT_IMAGE_FETCH_WORKERS = 4)`
while every budget decision is still committed one at a time, in `document.images` order, on the
calling thread — so budget exhaustion still costs the LAST pictures rather than an arbitrary set. The
transient ceiling is now stateable: the 96 MiB aggregate budget plus at most four undecided images,
each bounded by `get_object_bytes(max_bytes=…)`, against a previously unbounded single-object read.
The cost is one HEAD request per fetched photograph, and that request is what buys the bound.
[SCALABILITY.md §5.1](SCALABILITY.md) carries the arithmetic.

### [MEDIUM] The two design-workshop upload doors read the whole body before checking the cap (backend) — **CLOSED 2026-09-03**

`POST /design-workshops/ocr/identity` and `POST /design-workshops/{id}/dictate` now read through
`backend/app/services/uploads.py::read_upload_bounded` rather than `await file.read()` followed by a
length comparison — so the ceiling is enforced against the declared `Content-Length` before a byte
moves, and against the running total when no `Content-Length` was sent. The helper itself belongs to
another lane; this entry covers the two doors. It is the design-workshop half of
[AUDIT-2026-08-30.md](AUDIT-2026-08-30.md) A30-10, whose other two doors closed in the same wave.
Pinned by `backend/tests/test_upload_bounds.py`.

### [MEDIUM] The handset dropped a designer's custom answers and counted nothing (android) — **CLOSED 2026-09-03**

`WorkshopSyncStatus` now carries `droppedAnswers`, summed in `statusOf` from
`StageSyncRecord.refusal.droppedCustomKeys`, made a term of `isFullySynced` and threaded through
`dwDeviceSyncBanner`. The `retryWorkshop` comment that prescribed the counter was updated in place
rather than deleted, so the argument for it survives beside the thing it argued for.

### [MEDIUM] The workshop list drew another account's drafts as though they were yours (android) — **CLOSED 2026-09-03**

The storage half of the draft-ownership guard had been in place since 2026-08 —
`WorkshopDraft.ownerUserId` and `dwDraftIsForAnotherAccount` — and the **display** half was missing:
the list still drew A's drafts to B and let B open one. `WorkshopListScreen` now labels such a row
and makes it non-openable. This finding was recorded as part-closed; it is closed.

### [MEDIUM] An entry captured by one designer on a shared handset synced under the next designer's token (android) — **CLOSED 2026-09-03**

The same boundary, one queue along. `PendingEntry.ownerUserId` is stamped at save time from the
signed-in user, and `syncOutbox` steps over an entry whose owner is not the account signed in now —
otherwise two designers sharing one field handset produce records created under the wrong token, with
the wrong `createdById`, in the wrong person's lists. **A null owner passes**, so no handset is
stranded by the upgrade. The entry is not marked failed: it is counted separately as
`OutboxCounts.otherAccount` and gets its own banner line naming the one act that moves it — that
designer signing in. Pinned by
`android/app/src/test/java/com/designprototype/workshop/data/OutboxOwnerAccountTest.kt`.

### [MEDIUM] An entry captured by one designer in a shared browser profile synced under the next designer's token (frontend) — **CLOSED 2026-09-03**

**The web twin of the entry above, closed the same day.** The failure mode is identical and it is
reached through a different door: `AuthProvider.logout` clears the token and the user from React
state and **leaves IndexedDB exactly as it was**, so the outbox survives the handover intact — which
is the whole premise the guard rests on — and `OutboxBanner` drains on mount, under whoever signed in
next. A shared field laptop is not a rarer arrangement than a shared handset; it is the same
arrangement with a bigger screen.

`OutboxEntry.ownerUserId` is stamped in `queueOffline` from `setOutboxSessionUser`'s last answer,
which `OutboxBanner` wires from the signed-in user, and `runSync` steps over an entry that
`outboxEntryIsForAnotherAccount` reports as somebody else's. **Same null-owner rule** — every entry
queued before the field existed passes, so no browser is stranded by the upgrade — and **the same
not-marked-as-failed rule**: failure is a state a person resolves by discarding, and there is nothing
wrong with this entry. It is counted separately as `SyncResult.otherAccount` and gets one banner
line, `outboxOtherAccountLine`, naming the one act that moves it.

The owner is stamped in `queueOffline` and nowhere else, which is why `ownerUserId` is excluded from
that function's parameter type — six forms reach it and none of them may choose. Pinned by section 7
of `frontend/e2e/outbox-drain-triage-unit.spec.ts` (eleven tests, including the drain's skip line and
the banner sentence read out of the source).

### [MEDIUM] A stage reported a refused answer and marked nothing on the screen (frontend) — **CLOSED 2026-09-03**

When `save_stage` refuses a whole row it files the message under the reserved key `_row` inside that
scope's error bucket (`{scope: {"_row": "…"}}`), which is a refusal about the ROW rather than about
any field. **The web's box-marking pass looked every key in the bucket up in the registry's field
list**, found no field called `_row`, and marked nothing — so the stage told the designer an answer
had been refused and gave them nothing to look at. The count was right and the screen was empty,
which is the worst of the two ways to be wrong: a designer re-saves, is refused again, and has no way
to find out which row.

Closed by `rowRefusal` and `RowRefusalLine` in
`frontend/components/designworkshop/EntityForm.tsx`, drawn in three places — on the collection card,
on the singleton, and hoisted once for `_custom`. `_row` may arrive **alone or beside real field
messages in the same bucket**, so the line is drawn in addition to per-field marks rather than
instead of them. Pinned by `frontend/e2e/stage-refusal-placement-unit.spec.ts`.

**The literal is written by hand in three places with no generator between them** —
`design_workshops.STAGE_ROW_CONFLICT_KEY`, `DwStageRefusal.kt`'s `DW_ROW_REFUSAL_KEY` and
`EntityForm.tsx`'s `ROW_REFUSAL_KEY`. That is the shape of the next defect here: change one and the
refusal simply stops being recognised on the client that was missed, and is drawn as a field named
`_row`.

### [MEDIUM] Two market-analysis port divergences, found and closed the same day (frontend) — **CLOSED 2026-09-03**

`frontend/e2e/market-analysis-port-unit.spec.ts` ran for the first time on 2026-09-03 and found
`frontend/lib/marketAnalysis.ts` disagreeing with the Python authority on two of the twenty-nine
shared cases. Both were the browser's fault, not the table's — the goldens are regenerated from
`backend/app/services/market_analysis.py`, so a divergence is always a defect in the port.

1. **Unicode decimal digits.** Python's `float()` accepts any character in category Nd, so
   `float("୧୨୩")` is 123.0. The port gated on an ASCII-only grammar and then handed the text to
   `Number()`, which answers `NaN`. Measured on the case: the browser read 8 of the 10 price
   observations the server reads, and reported a median of ₹670 against ₹545. Those are the figures
   stage 9 prints and the report carries, so it was the panel and the .docx disagreeing about one
   workshop. Closed by spelling the grammar with `\p{Nd}` **and** folding what it matches to ASCII —
   one fix in two halves; either alone still reads `NaN`.
2. **U+0085, which `String.prototype.trim()` does not strip.** Python's `str.strip()` follows
   `str.isspace()`, which is true for the next-line control; ECMAScript's WhiteSpace set is not.
   One line produced every consequence in the case: a padded price dropped, a padded competitor
   vanished with its whole category distribution, a padded band bound read as absent so a SOUND-able
   band reported `NO_EVIDENCE`, and an `evidence` field holding nothing but padding was non-empty on
   one side and empty on the other. Closed by `pyStrip`, replacing every `trim()` that stood in for a
   Python `.strip()`.

`KNOWN_PORT_DIVERGENCES` in that spec is now empty and is kept rather than deleted: the next
divergence needs somewhere to be named, and the rule for retiring one — `test.fail()` and never
`test.skip()`, so the suite goes red the day the port is fixed and the annotation is left behind —
belongs with it. The Kotlin twin `DwMarketAnalysis.kt` was the correct reference implementation for
both and is unchanged.

---

## Closed on 2026-08-15

All seven closed in one pass, each with the test that would have caught it. Backend **2474 passed, 3
skipped** against a live PostgreSQL 16 with a freshly generated client; Android **1156 JVM tests, 0
failures**; frontend `tsc` clean and the new fold spec **14 passed**.

### [LOW] Every search box in the application treats `%` and `_` as SQL wildcards, because the shared `contains` never escapes them (backend) — **CLOSED 2026-08-15**

**Closed by** escaping `\`, `%` and `_` — in that order — inside `contains`, the single funnel all 67
call sites go through. `plain` is deliberately left alone and a test now asserts that it is: it
compares EQUAL, and an `=` has no pattern syntax in it, so escaping there would stop `?state=A_P`
from matching the row that literally is `A_P` — a new defect wearing the fix's clothes.

**The open question the deferring pass named has been settled by measurement, not by assumption.** It
asked whether Postgres honours the default backslash escape through a bound Prisma parameter. It
does; run against this database, on these five subjects:

| pattern | matches |
|---|---|
| `_` | `first_last@org`, `firstXlast@org`, `plain@org`, `100% cotton`, `back\slash` — everything |
| `\_` | `first_last@org`, and nothing else |
| `%` | everything |
| `\%` | `100% cotton`, and nothing else |
| `\\` | `back\slash`, and nothing else |

And through the client the app actually uses: `contains("_")` matched all **4922** users before the
change, `contains("\_")` matched **0** — consistent with the finding's own observation that no
account holds a literal underscore.

**Pinned by** five tests in `tests/test_record_filters.py`, including the ordering case: escape `%`
before `\` and a typed backslash becomes an escape for the escape, putting the wildcard back by way
of the fix. They are written with raw strings after the first draft passed for the wrong reason —
`"\_"` is not an escape Python knows, so it silently keeps the backslash *and* warns.

### [CRITICAL] The generated Prisma client is stale, and it makes the whole AI-layers surface unwritable (backend, tooling) — **CLOSED 2026-08-15**

**Closed by** `PYTHONUTF8=1 python -m prisma generate`, run with the venv's `Scripts` directory on
`PATH` and with no other suite in flight — the condition the deferring pass asked for. Every claim in
the entry below now inverts: `db.dwaiverbdailyusage` is `True`, `DwAiLayer.sourceText`,
`.sourceLanguage` and `.targetLanguage` are all present, `AppSetting.dwAiVerbDailyCap` and
`User.dwAiVerbDailyUsage` are present, and the drift script the entry specifies reports **51 models
in the schema, 51 classes in the client, none missing** — where it had reported four adrift.

**AND THE CAUSE WAS NOT WHAT THIS ENTRY ASSUMED.** It read as an ordinary omission — a step nobody
ran. It was not: **regeneration had been impossible on this machine since 2026-08-12 14:28**, which
is exactly the client's frozen mtime. `prisma/generator/generator.py:241` does
`packaged_schema.write_text(data.datamodel)` with no `encoding=`, so it writes at the locale default
— cp1252 here — and `schema.prisma` contains `─` (U+2500, ×12) and `▶` (U+25B6, ×6) in its comment
banners. Every run since died with:

```
UnicodeEncodeError: 'charmap' codec can't encode characters in position 104369-104371
```

a message that names neither the file nor the encoding nor the characters. **That is why the drift
was silent and why it grew:** the schema moved on to 18:27 and the client could not follow. Recorded
in `docs/ENVIRONMENT.md` so the next person does not spend the same hour on it.

**Verification.** `prisma migrate status` reports 47 migrations found and "Database schema is up to
date"; the whole backend suite then ran **2474 passed, 3 skipped** against that database.

### [MAJOR] On the web a withheld deletion has no way back, because the browser has no fold (frontend) — **CLOSED 2026-08-15**

**Closed by** `foldStageInto(spec, current, incoming)` in `frontend/lib/designWorkshopStore.ts`,
called from `adoptServerStage`'s dirty branch in place of the early return, exactly as the entry
prescribed. It is deliberately the same function `dwFoldServerStage` is on the handset, rule for
rule: add only what this browser has never seen, keep every local value and row, decline to re-add
rows in an entity named by `removedFrom`, count the collateral, stamp `serverLoadedAt`, and carry
`dirtyAt` and `removedFrom` through untouched. The next save is then entitled to carry the deletion
that was previously owed for ever.

`foldNotice` gives the page the two sentences `DwStageFold.notice` already carries on Android, kept
close to the handset's wording on purpose so a designer who uses both surfaces is not told the same
event in two vocabularies. It is stored on the stage record as `foldNote` rather than raised as a
toast, because the read that produced it may have happened while the designer was on another screen.

**Pinned by** `e2e/stage-fold-unit.spec.ts`, 14 cases, including the three that a naive fold gets
wrong: an empty string IS an opinion and is not overwritten; a row already held is not added a second
time (matched on `_clientKey` then `_entryId`); and a fold that only SWEEPS still speaks.

**Its residual cost, stated rather than hidden:** the notice is written to the draft and there is no
UI drawing `foldNote` yet, so today the sentence is recorded and not shown. The data defect — the
deletion that could never travel — is closed; the telling of it is one render away and wants a live
browser, which is the same reason the original pass gave for not attempting this at 07:00.

### [MAJOR] A row deletion that is not the LAST row of a collection is recorded nowhere on the handset (android) — **CLOSED 2026-08-15**

**Closed by** `StageDraft.deletedRowKeys: List<String>` — `entityKey#clientKey`, additive and
defaulted, owing no rung of `WORKSHOP_DRAFT_SCHEMA_VERSION` by that constant's own rule — written by
the same `onRowsChange` that maintains `emptied`, from what LEFT the list rather than from what the
list now holds. A key is dropped again the moment the row comes back, so a row deleted and re-added
before the next save owes nothing.

`statusOf` counts it beside `unsentDeletions`, intersected with the declared entities exactly as
`emptiedEntities` is, and the stage is counted ONCE however many ways it owes a deletion — the figure
is rendered as "N stages". `isFullySynced` already had `unsentDeletions == 0` as a term, so the
workshop row stops saying "Backed up to the server" over a deletion that cannot travel.

`recordStageSent` clears it, scoped to what the payload actually swept: the entities the body NAMES,
and only when it claimed `replaceCollections`. An unauthoritative save merges and swept nothing, so
clearing there would be the same permanent silent loss that clearing `emptiedEntities`
unconditionally used to be, one level down.

**Verified by** the full Android suite: 1156 tests, 0 failures. The counting path itself needs a
`Context` and so is not JVM-testable here — stated plainly rather than implied.

### [MAJOR] `PUT /custom-sections` is last-write-wins over the whole definition, and the digest that would stop it is already in every response (backend, frontend) — **CLOSED 2026-08-15**

**Closed by** an optional `customSchemaVersion` on `CustomSectionsIn`; when present and not equal to
the stored digest the write is refused with 409 naming both digests (`expected`, `actual`, and a
`code`). Optional, so every already-shipped client keeps working; `extra="forbid"` means a client
that misspells the key is refused loudly rather than being silently unprotected. The editor now holds
`storedVersion` off the load and sends it back.

Checked AFTER `validate_definition` and not before, deliberately: those problems are the body's own —
that function is pure and never looks at what is stored — so they are true no matter who else wrote,
and a designer whose definition is malformed should be told that rather than told to reload and then
told it again.

**Pinned by** three tests in `tests/test_custom_sections_endpoints.py`, which reproduce the measured
wire sequence and read the TABLE rather than the response. The one that matters most asserts that the
409 **deleted nothing** — a 409 that still performed the write would be worse than the defect it
replaced. A second test deliberately asserts the UNPROTECTED path still works, because that is the
backward-compatibility promise; a third pins that a digest that is not a digest fails closed.

### [MINOR] `StageSaveResultDto.removed` has no reader on the handset (android) — **CLOSED 2026-08-15**

**Closed by** a tripwire in `recordStageSent`: when the payload disclaimed the sweep
(`replaceCollections == false`) and the server still answers `removed > 0`, the stage carries a
sentence saying the server deleted rows this device never asked it to delete.

**Asked only of a payload that disclaimed the sweep, which is what makes it a tripwire and not
noise.** When `replaceCollections` is true a deletion is what the save MEANT, and the count is
unpredictable from the phone, which cannot know how many rows the server holds. When it is false
there is no legitimate reason for the number to be anything but zero — which is precisely the
signature of the `replaceCollections` blocker that survived because nothing repeated `removed: 3`.

It reports rather than repairs, deliberately: the rows are gone by the time it runs, and inventing a
recovery from a count with no keys in it would be a guess written into a repository.

### [MINOR, LATENT] `patchDraftHeader` would PATCH a blank header over the office's, and today nothing calls it (frontend) — **CLOSED 2026-08-15**

**Closed by** `DwDraft.headerDirtyKeys`: `patchDraftHeader` records which fields it touched (through
`definedOnly`, so a box left blank is not recorded as an edit), and the sync pass's PATCH sends those
fields and no others. Cleared with `headerDirtyAt`, never apart from it — a list left behind would
make the next unrelated edit send fields nobody touched.

**The remedy chosen is the first of the two the entry offered, and on purpose.** The other — refuse
to mark the draft dirty until the detail has been folded in — shuts the door by making the edit
un-sendable, which is a dead end of exactly the kind this register has already had to open twice.
Sending only what somebody typed has no such end: the edit travels, and the fields nobody touched are
not overwritten.

The fallback for a draft written before the field existed is today's whole-header behaviour, left
rather than "fixed" to send nothing, because silently dropping somebody's edit is worse than the
latent case. It is unreachable in practice: `patchDraftHeader` is the only thing that can arm this
branch and it always records its keys.

### [MEDIUM] one refused save was reported as two different numbers on the two surfaces — **the frontend half was ALREADY DONE, and this register was wrong about it**

Re-checked on 2026-08-15 against the tree. The sync pass in `frontend/lib/designWorkshopStore.ts`
reads `refusedAnswersToShow(saved.refusedAnswers, saved.errors)` — that function is declared in the
same file — and `DwSaveResult.refusedAnswers` is declared in `frontend/lib/designWorkshops.ts`.
(Both were pinned by line when this entry was written. Checked on 2026-08-15, the store pin landed on
a comment in the middle of an unrelated function — neither the declaration of `refusedAnswersToShow`
nor the call it quoted, which are hundreds of lines apart in that file. Symbols, not numbers — see
the maintenance table.)
The implementation is better than what the entry
asked for: it takes the server's count EXCEPT where doing so would report "nothing refused" about a
response this build can see refused something, so it cannot be wrong in the under-reporting direction
— the one direction this repository has already decided must never be wrong.

**Nothing to do. It is recorded here because the entry was stale in the dangerous direction** — a
reader following it would have gone hunting for a one-line change that had already been made.

---

## The seven entries as they were written, kept for the re-check

Everything below to the next date heading is the ORIGINAL diagnosis of the seven items above, left
exactly as the pass that found them wrote it — the same treatment this file already gives the VID
entry further down, and for the same reason: **a closure is only worth as much as the reader's
ability to check it.** The measurements are here (which requests, which counts, which lines), so the
next person can re-run them against the tree rather than take the closure notes' word for anything.
They are in the same order as the closures above.

### [LOW] Every search box in the application treats `%` and `_` as SQL wildcards, because the shared `contains` never escapes them (backend) — as written

**Where.** `backend/app/services/records.py`, `contains` — `{"contains": value.translate(_UNSEARCHABLE), "mode": "insensitive"}`. Its own docstring counts **57 call sites**; `grep -c 'contains('` over `app/api/routes/*.py` and `app/services/*.py` now totals **67**.

**Found 2026-08-13 while adversarially probing the viewer-picker search that was added the same night** — the brief for that lane's verifier asked specifically whether a term containing `%` or `_` would behave as a wildcard, "a different bug arriving". It does. The lane's own verifiers did not report it, so it is recorded here rather than left in a workflow transcript.

**What.** Prisma's `contains` compiles to `ILIKE '%' || term || '%'` and the term is interpolated unescaped. `%` and `_` are LIKE metacharacters, so they are honoured rather than matched. Measured live against the running API, admin token, this database:

| request | rows | correct answer |
|---|---|---|
| `eligible-viewers?search=zzzznomatch` | 0 | 0 ✓ |
| `eligible-viewers?search=_` | **2000** (the cap) | 0 — no name or email holds an underscore |
| `eligible-viewers?search=%25` (`%`) | **2000** | 0 |
| `eligible-viewers?search=_designer` | **635** | 0 — `_` matched any single character |
| `artisans?search=zzzznomatch` | 0 | 0 ✓ |
| `artisans?search=_` | **731** (every artisan) | 0 |
| `artisans?search=%25` | **731** | 0 |

So it is not one endpoint's defect. It is every search box the application has.

**This is NOT SQL injection.** Prisma parameterises, and `contains` already strips the control bytes that used to 500 these routes. The values arrive bound; what leaks is pattern syntax, not SQL.

**Consequence, and why it is LOW rather than higher.** Nothing is broken on today's data: `select count(*) from "User" where email like '%\_%'` returns **0**, so no current account can be mis-matched. That is a property of this dataset and not of the code — underscores are ordinary in real email addresses (`first_last@org`), and there the defect bites exactly when it is least welcome: an admin pasting a colleague's full address to narrow a truncated picker gets a *wider* result than they typed, having just been told by `truncated: true` to narrow. The two features work against each other.

**Why it was not fixed in the pass that found it.** The fix is one line in `contains` — escape `\`, `%` and `_` before building the filter — but `contains` is the funnel for all 67 sites, so that one line changes the behaviour of every search box in the product at once, and it is arguably a *behaviour* change rather than purely a repair (someone could hold that wildcards in a search box are a feature). It also needs its own decision about `plain` beside it, and a check that Postgres honours the default backslash escape through a bound Prisma parameter rather than assuming it does. That is a deliberate, testable change for whoever owns search, not a 07:00 edit to a shared helper while seven lanes' work is still unreviewed.

**When fixing, the tests to write first** are the seven rows above: each is a request whose right answer is known independently of the implementation.

### [CRITICAL] The generated Prisma client is stale, and it makes the whole AI-layers surface unwritable (backend, tooling)

**Where.** `backend/.venv/Lib/site-packages/prisma/` (the generated client) against
`backend/prisma/schema.prisma`.

**Found on 2026-08-13 while proving an unrelated fix, and deliberately NOT fixed in that pass** — see
the note on why below.

**What.** `schema.prisma` declares `DwAiLayer.sourceText`, `.sourceLanguage` and `.targetLanguage`;
Postgres HAS all three columns, so the migration was applied. The generated client does not know any
of them. Measured:

```
generated client (prisma/models.py) mtime : 2026-08-12 14:28
schema.prisma                       mtime : 2026-08-12 18:27
DwAiLayer columns in Postgres  : … sourceLanguage sourceLayerId sourceMediaId sourceText targetLanguage …
DwAiLayer fields in the client : … sourceLayer sourceLayerId sourceMedia sourceMediaId … (no sourceText,
                                  no sourceLanguage, no targetLanguage)
```

**Consequence.** `LayerSource.columns` puts `sourceText` into every create, so every write to
`DwAiLayer` is refused by the query engine before it reaches the table:

```
MissingRequiredValueError: `createOneDwAiLayer.data.sourceText`: Field does not exist in enclosing type.
```

That is the register/proofread/expand/translate/caption/subtitles surface — 11 endpoints — dead on
the wire. It cannot be caught by the current suite: **not one of those 11 endpoints has ever been
driven against a database** (see the coverage measurement under *Closed on 2026-08-13*).

**A SECOND CONSEQUENCE, MEASURED 2026-08-13 06:2x BY THE TIER 2 GATE REVIEW AND NOT PREVIOUSLY
NAMED HERE: the AI-verb daily cap cannot count at all.** The drift is not only three missing columns.
One whole model is absent from the client:

```
$ python -c "from prisma import Prisma; d=Prisma(); print(hasattr(d,'dwaiverbdailyusage'), hasattr(d,'dwailayer'))"
False True
```

`schema.prisma` declares `model DwAiVerbDailyUsage`; `prisma/models.py` has no class for it, so
`db.dwaiverbdailyusage` is an `AttributeError`. `ai_verb_cap._usage_today` (line 268,
`find_many`) and `ai_verb_cap.spend` (line 293, `upsert`) both go through it, so **the ceiling on AI
verb spend raises rather than counting.** `AppSetting.dwAiVerbDailyCap` and `User.dwAiVerbDailyUsage`
are missing from the client too — four models in total drift, out of 51 (script:
compare `^model (\w+)` and its two-space field names in `schema.prisma` against `^class \1\(` in
`prisma/models.py`; the other 47 match).

Why it matters beyond the 11 endpoints: a reviewer checking "is the daily cap in front of X" can get
a true answer for a false reason. It is worth stating that the Tier 2 exemption from this cap was
verified against the cap's own *logic* (`dictation_cap.cap_refusal` on a fully spent allowance, which
is pure and does work — see `tests/test_tier2_gates.py`) and not against a live counter, precisely
because the live counter is this defect.

**The remedy** is `cd backend && .venv/Scripts/python.exe -m prisma generate --schema
prisma/schema.prisma`, then re-run the suite.

**Why the pass that found it did not run that.** Regenerating rewrites files inside
`site-packages/prisma/` that several other test runs were importing at that moment — six `pytest`
processes were live — so doing it would have produced exactly the kind of inexplicable red build this
repository has already been burned by, in somebody else's lane. And the 835-line `schema.prisma`
change it belongs to is uncommitted work in flight, so regenerating from it bakes in whatever state
that edit happens to be in. It belongs to whoever owns that schema change, run when no other suite
is mid-flight.

### [MAJOR] On the web a withheld deletion has no way back, because the browser has no fold (frontend)

**Where.** `frontend/lib/designWorkshopStore.ts` — `stageSweep` (the withholding) and
`adoptServerStage` (the branch that refuses to fold a dirty stage, around line 2015).

**Found 2026-08-13 by the pass that closed the sweep-without-authority defect below, and it is that
fix's own cost, stated rather than hidden.**

A deletion made on a stage this browser has never read is now correctly not sent — `stageSweep`
withholds `replaceCollections`, `unsentAfterPush` keeps `removedFrom`, and the save says so in a
sentence. What the browser then has no way to do is EARN the authority that would let it send:

* `serverLoadedAt` is set only by a fold, and `adoptServerStage` refuses to fold a stage whose
  `dirtyAt` is not null — which a stage holding an unsent deletion always is, because `removedFrom` and
  `dirtyAt` are kept and cleared together by `unsentAfterPush`;
* so the stage stays dirty, the fold stays refused, and the deletion is owed for ever. The row the
  designer deleted stays alive in the repository and prints in the .docx.

It is visible — `pendingWork`, `DraftSyncBanner` and the save notice all name it — and it destroys
nothing, which is why it is MAJOR and not the blocker its predecessor was. **Android does not have
it:** `dwFoldServerStage` folds the server's copy INTO a draft that holds work (add what this device
has never seen, overwrite nothing, decline to re-add rows in an emptied collection, count the
collateral in `sweptRows`), which earns `stageSeen` for a dirty draft and lets the very next save carry
the deletion.

**The remedy is that function, on the web.** A pure `foldStageInto(spec, current, incoming)` beside
`buildStageEntries`, called from `adoptServerStage`'s dirty branch instead of the early return: union
the singleton keys the draft lacks, append server rows whose `_clientKey`/`_entryId` the draft does not
hold, skip entities named by `removedFrom`, count what was skipped, stamp `serverLoadedAt`, keep
`dirtyAt` and `removedFrom`. The page then has the same two sentences Android's `DwStageFold.notice`
already carries. It was not written in that pass because it changes what a dirty stage shows on screen,
which is a browser behaviour that wants a live browser to verify and the dev server could not compile
the stage route under the load at the time.

### [MAJOR] A row deletion that is not the LAST row of a collection is recorded nowhere on the handset (android)

**Where.** `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/StageScreen.kt`,
the `onRowsChange` lambda of the collection renderer — the only writer of `emptied` — and
`android/app/src/main/java/com/designprototype/workshop/data/WorkshopSync.kt`, `unsentDeletions` on
`WorkshopSyncStatus`.
(Both were cited by line number — `StageScreen.kt:1320-1325` <!-- rotted --> and
`WorkshopSync.kt:810-821` <!-- rotted --> — and both had rotted onto unrelated code while still
passing the checker, which at the time only verified that a cited line is inside the file. The
`<!-- rotted -->` markers are what tell `docs/tools/check-docs.mjs` that these two numbers are being
exhibited as broken rather than offered as pointers; without them its drift test reports the very
rot this sentence is describing. What they now point at is not recorded
here: both files are under active edit and the answer changes weekly. The symbol names above are
what to grep for.)

**Found 2026-08-13.** `emptied` gains an entity key only when `rows.isEmpty() && had` — the collection
went from having rows to having none. Deleting one row of three leaves NO record anywhere: not in
`emptiedEntities`, so `unsentDeletions` cannot count it and `isFullySynced` has no term for it. On a
stage the phone HAS read this is harmless, because `replaceCollections` is claimed and the sweep
removes the row the payload no longer names. On a stage it has NOT read, the deletion cannot travel —
and the workshop row says "Backed up to the server" while the row is still in the repository and still
in the report.

**This is the surviving case of the defect `unsentDeletions` was written for, one door along, and the
`replaceCollections` fix below makes it MORE reachable, not less.** Before that fix the flag was
omitted and the server read the omission as true, so a partial deletion did travel — by accident, in
the same request that deleted every row the phone had never downloaded. Trading a catastrophe for a
silent no-op is the right trade and it is not the end of it.

**The remedy needs a record, because nothing can count what was never written down.** A
`StageDraft.deletedRowKeys: List<String>` (entity#clientKey, additive and defaulted, no schema rung
owed by `WORKSHOP_DRAFT_SCHEMA_VERSION`'s own rule), written by the same `onRowsChange` that maintains
`emptied`, counted by `statusOf` beside `unsentDeletions` with the same "open the stage once" sentence,
and cleared by `recordStageSent` for the keys an acknowledged payload actually carried. It would also
give the server the one thing that would remove the need for authority in this case entirely: a
`deletedClientKeys` on `StageSaveIn` naming rows to soft-delete, which is a deletion a client can state
honestly without claiming to know the whole collection.

### [MAJOR] `PUT /custom-sections` is last-write-wins over the whole definition, and the digest that would stop it is already in every response (backend, frontend)

**Where.** `CustomSectionsIn` in `backend/app/schemas/design_workshops.py` — whose only field is
`sections` — and `frontend/components/designworkshop/CustomSectionsEditor.tsx`. (Pinned at line 535
when this was written; the class had moved 28 lines down by 2026-08-15 and the pin still passed the
bounds check, which is the defect the checker's new drift test exists to end.)

**Found 2026-08-13, measured on the wire** against the running API and Postgres:

```
1. designer 1 saves one section          -> version f2e0b0a8ca5bcc4b  sections ['dye']            created 1
2. designer 2 adds a second section      -> version 68c212eec44f5cfc  sections ['dye','looms']    created 2
3. designer 1's STALE tab presses Save   -> HTTP 200
                                            version f2e0b0a8ca5bcc4b  sections ['dye']  removed 1
4. GET, as any other client now reads it -> sections [('dye', False)]
```

The `looms` section and both its fields are gone — REMOVED rather than retired, correctly by the
service's own rule, because nothing had answered them yet. No 409, no warning, nothing on either
screen. Two designers editing one workshop's questions, or one designer with a tab open from before
lunch, silently delete each other's work.

**The remedy is small and additive:** an optional `customSchemaVersion` on `CustomSectionsIn`; when
present and not equal to the stored digest, 409 with the two digests named. The editor already reads
that digest (it is returned by both `GET` and `PUT` and is what every client compares its cache
against), so it only has to send back what it loaded. Optional keeps every shipped client working, and
`extra="forbid"` means no client that does not know the field can be broken by it.

### [MINOR] `StageSaveResultDto.removed` has no reader on the handset (android)

**Where.** `android/app/src/main/java/com/designprototype/workshop/data/StageSchema.kt`,
`StageSaveResultDto.removed`. The field is decoded and used by nothing — a grep
across `android/` finds the declaration and no other mention.

The server answers every stage save with the number of rows it deleted. The web prints it ("Stage
saved — 2 added, 0 updated, 5 removed"); the phone discards it. **That silence is how the
`replaceCollections` blocker below survived:** the API said `removed: 3` to a save that had asked for
no sweep at all, and no surface on the phone repeated it. A save whose `removed` is larger than what
the designer deleted on this device is the cheapest possible tripwire for the whole class, and it is
one line of state plus a sentence.

### [MINOR, LATENT] `patchDraftHeader` would PATCH a blank header over the office's, and today nothing calls it (frontend)

**Where.** `patchDraftHeader` in `frontend/lib/designWorkshopStore.ts` — it is the only writer of a
non-null `headerDirtyAt` — and the `needsHeader` arm of the sync pass in the same file, which is what
that flag arms. (Both were pinned by line; by 2026-08-15 the first pin sat 279 lines above the
function and the second named a docstring about stage definitions. Grep `headerDirtyAt`: every site
that matters is a hit, which is what a line number was pretending to be.)

Checked while enumerating every payload either client builds, and recorded because it is a door that is
shut only by having no caller. `ensureDraft` seeds a header of empty strings and nulls with
`headerDirtyAt: null`, which is what keeps it harmless. The sync pass sends **every** header field from
the local copy — `title`, `craftName`, `notes`, the dates — so the first UI that calls
`patchDraftHeader` on a workshop this browser has not read in full will null the office's `notes` and
overwrite its title with `""`, under a 200. The stage form's own `serverLoadedAt` rule is the shape of
the answer: a header PATCH must carry only the fields the form actually holds a read value for, or the
draft must not be marked dirty until the detail has been folded in.

**The paragraph below was written on 2026-08-13 and describes the state THEN.** It is left as it
stood, because rewriting a dated observation to match a later day is how a register stops being
evidence. All seven items it sat under are closed — see *Closed on 2026-08-15* at the top.

**Everything else is closed.** The one item that stood here was re-read against the tree on 2026-08-12 and is fixed;
it has moved down to *Closed on 2026-08-12* with the evidence. Two new defects were found on the
same day and are recorded there too — both fixed in the pass that found them, so neither was ever
open. Two more were found on 2026-08-13 in the design-workshop viewer picker and are recorded under
that date, likewise fixed in the pass that found them.

---

## Closed on 2026-08-13

### [MEDIUM] The web counted refused answers by scope instead of reading the server's count (frontend) — **CLOSED**

**Where.** `frontend/lib/designWorkshopStore.ts` (`refusedAnswersToShow`, and its use in `runSync`)
and `DwSaveResult` in `frontend/lib/designWorkshops.ts`.

The web's scope-count was already gone when this was re-read: `countRefusedAnswers` sums the field
maps, so a row with three unreadable values reads "3 answers" on both surfaces. What remained was the
half this entry actually asked for — the browser still derived its own number instead of reading the
`refusedAnswers` the server computes so that the two surfaces cannot disagree. It now reads it.

**It is not the straight field-read this entry proposed, and the difference is the whole finding.**
The two counts disagree in BOTH directions, and only one direction is safe:

| response | `refused_answer_count` (server) | `countRefusedAnswers` (web) |
|---|---|---|
| `{"tool[0]": {"a": …, "b": …}}` | 2 | 2 |
| `{"costing": {}}` | **0** — `len({})` | **1** — the deliberate `\|\| 1` |
| `{"costing": "required"}` | **1** — its non-mapping guard | **8** — `Object.keys` on a string returns INDICES |

So reading the field alone would have reintroduced an under-report: a non-empty `errors` announced as
"nothing was refused", which is the one direction this repository has already decided must never be
wrong — `frontend/e2e/stage-refusal-placement-unit.spec.ts`'s header says the original defect was that the form
and the sync pass were both wrong by under-reporting. Conversely, keeping the local count alone leaves
the web able to print a character count for a scope that arrived as a bare string, which is exactly
what the server's non-mapping guard was written for.

`refusedAnswersToShow` therefore prefers the server's number *except* where it would say nothing was
refused about a response this build can see refused something, and falls back entirely when the field
is absent (a client can be newer than its deployment).

**Pinned by five tests** in `frontend/e2e/stage-refusal-placement-unit.spec.ts`, and the pin was checked rather
than assumed: mutating the function to `serverCount ?? local` — the naive version — turns exactly one
red, *"a scope refused with an empty field map is never reported as a clean save"*, with the other 24
still green. 25 pass restored; `tsc --noEmit` clean.

### [BLOCKER] CORRECTNESS — the handset's whole sweep gate was spelled as silence, and the server reads silence as "delete the rest" (android) — **found and fixed 2026-08-13**

**Where.** `android/app/src/main/java/com/designprototype/workshop/data/StageSchema.kt`,
`StageSaveBody.replaceCollections`.

**What.** `buildStageBody` decides `replaceCollections = authoritative` and has done so correctly since
the fortnight-of-process-steps incident. It never reached the wire. The property carried `= false`;
`ApiClient.retrofit`'s `Json { … }` does not set `encodeDefaults`, so it stands at kotlinx's default of
false and a property equal to its default is OMITTED — and `StageSaveIn.replaceCollections` on the
server is `Field(default=True)` (`backend/app/schemas/design_workshops.py:198`). "Do not sweep" was
therefore sent as an absent key, and an absent key up there is the strongest claim the protocol has.

**Measured with the handset's own builder against the running API and a live Postgres**, a draft with
`stageSeen = false` holding one `tool` row, serialised exactly as `ApiClient` serialises it:

```
body {"entries":[{"entityKey":"tool","ordinal":0,
                  "data":{"name":"Pit loom (corrected)","_clientKey":"phone-tool-1"},
                  "merge":true}]}
  -> HTTP 200 {"saved":1,"created":0,"updated":1,"removed":3,"errors":{}}
```

Three rows the phone had never downloaded, soft-deleted by a save that had correctly worked out it was
entitled to delete nothing. `merge: true` is no defence — it preserves keys INSIDE a row the server
matched and says nothing about a row the payload never named. It needed no deletion, no fold and no
second device: **every first save of a stage this handset has not read carried it.** In Postgres the
three rows have `deletedAt` stamped with `clientKey` and `data` intact, so they are recoverable by an
operator and by nobody using the app — and `StageSaveResultDto.removed` has no reader on the phone, so
nothing repeated the server's own `removed: 3` to anybody.

**Fixed** by deleting the default: `val replaceCollections: Boolean`. A property with no default is
always encoded, so the wire now carries `"replaceCollections":false` and the identical walk answers
`removed:0` with all five of the office's keys still in the row. `signatureOf` uses
`Json { encodeDefaults = true }` and already included the flag, so no stage's signature changes and
nothing is re-pushed.

**`encodeDefaults = true` would NOT have been the fix**, and the new test says so: it would put
`"merge":false` on every entry of every save, which an API predating that field answers 422 to for all
of them (`APIModel` is `extra="forbid"`). The two rules are opposite and both are load-bearing.

**Why 1107 passing unit tests missed it.** Every test of the gate — including the four in
`StageAuthorityEarnedByReadingTest` — reads `body.replaceCollections` off the Kotlin object, where the
value has always been right. The defect exists only in the bytes. **New:
`android/app/src/test/java/com/designprototype/workshop/data/StageSweepReachesTheWireTest.kt`, 5
tests, all asserting on the SERIALISED JSON**
using ApiClient's own configuration: the flag is present and false when unread, present and true when
read, `merge` is still absent when false, the round trip decodes to the authority the builder decided,
and `emptiedEntities` is named only under a claim the server will honour. 5/5 pass with the fix.

### [BLOCKER] CORRECTNESS — one row deleted on a never-read browser deleted five rows it had never downloaded (frontend) — **found and fixed 2026-08-13**

**Where.** `frontend/lib/designWorkshopStore.ts` (`runSync`) and
`frontend/app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx` (`save`), both now going
through `stageSweep`.

**What.** Both send sites armed the server's collection sweep with `stage.removedFrom.length > 0` and
nothing else — no authority test at all, while `buildStageEntries` three lines away asks the never-read
question for the singleton, for every collection row and for `_custom`. `removedFrom` grows on ANY row
deletion (`patchCollection` compares row counts), and `save_stage` scopes the sweep to
`(touched_entities | emptiedEntities) & collection_keys` — **every entity the payload NAMES**, not only
the one the designer emptied.

**Reproduced against the running API and Postgres** with the real `buildStageEntries` output and the
page's own expressions, on a never-read draft holding one row in each of two collections with one row
deleted from `tool`:

```
PUT … {entries:[processStep×1, tool×1, both merge:true],
       replaceCollections:true, emptiedEntities:["tool"]}
  -> HTTP 200 saved=2 created=2 updated=0 removed=5 errors={}
  live tool        BEFORE ["Pit loom","Reed","Charkha"]   AFTER []
  live processStep BEFORE ["Warping","Weaving"]           AFTER []   (nothing was deleted from it)
```

Five rows the office had written, gone under a 200 that the page reported as "Stage saved — 2 added,
0 updated, 5 removed".

**Fixed.** One exported pure `stageSweep(spec, stage)` shared by both send sites, mirroring the
handset's `buildStageBody`: the sweep is armed only when `serverLoadedAt` is not null AND a deletion is
pending; `emptiedEntities` is intersected with the stage's own COLLECTION keys (a key left by a registry
that has moved on is not a deletion instruction, and a reserved `_`-prefixed key would 422 the whole
stage); and the list the payload actually carried — not the one the draft is holding — is what
`markStagePushed`/`unsentAfterPush` is judged against, so a withheld deletion is never acknowledged as
sent. The page says so in a sentence naming the collections and the remedy. `runSync` also stops
issuing an entry-less PUT that could carry nothing.

**Re-proven on the wire, same walk:** `stageSweep = {replaceCollections:false, emptiedEntities:[],
withheld:["tool"]}` → `removed=0`, every office row alive with every key intact; and an authoritative
browser's deletion still answers `removed=1` with the other rows untouched.

**New: `frontend/e2e/stage-sweep-authority-unit.spec.ts`, 8 tests**, each asserting BOTH what the
payload carries and what the draft is left holding — so the obvious wrong fix (withhold everything and
lose every deletion) fails them. Reinstating the old expression fails exactly 2 of the 8: the never-read
gate and the acknowledgement chain. What that fix COSTS is registered as an open finding above, not
buried.

The first three entries below are one driver behaviour found in three places, then the counting
disagreement the same pass turned up. The two after them are one defect wearing two faces: a limit
that was reused as though it were a page size, recorded separately because only one of them had a
symptom, and the one that did not is the more dangerous.

### [CRITICAL] CORRECTNESS — every custom question a designer could have written was a 500, for the whole life of the feature (backend) — **found and fixed 2026-08-13**

**Where.** `backend/app/services/custom_sections.py`, `_field_columns` and the three call sites in
`apply_definition_plan`.

**What.** `PUT /api/design-workshops/{id}/custom-sections` answered **HTTP 500 to every body that
contained a field**. Reproduced on the wire first, against the running API with Postgres behind it:

```
PUT /api/design-workshops/cmsqgwgt7004oho0s1ydi15ja/custom-sections
{"sections":[{"key":"dyenotes","title":"Dye notes","stageKey":"CLUSTER_CRAFT_BACKGROUND",
  "fields":[{"key":"dyesrc","label":"Dye source","type":"TEXT"}]}]}

HTTP 500
{"detail":"Something went wrong on the server. The error has been logged.",
 "error":"MissingRequiredValueError"}
```

`GET` on the same path answered 200, so neither the token nor the workshop was at fault. The three
write paths — CREATE, EDIT and SUPERSEDE — each wrote `Json([...]) if spec.options else None` in
their own copy of one expression, and prisma-client-py renders an explicit `None` as `options: null`,
which the query engine refuses for a nullable `Json` column:
``MissingRequiredValueError: `data.options`: A value is required but not set``.

**Consequence.** No workshop could hold a single custom question, so the service, the web definition
editor, the handset form and the report annexure had none of them ever run. The feature was reported
as working and had never once been exercised on the wire.

**The measurement that mattered, because the handed-down diagnosis was wrong in a way that would have
left the bug in place.** The diagnosis was "prisma rejects explicit nulls in a create input, so strip
every `None` from `_field_columns`". Each null was tested one at a time against this database:
`maxLength`, `minValue` and `maxValue` are nullable **scalars** and the engine takes `null` for all
three without complaint. Only nullable **`Json`** behaves this way. And `options` was not in
`_field_columns` to be stripped — the callers merged it in afterwards — so the suggested fix would
have changed three columns that were never wrong and not the one that was.

**Fixed.** `options` moved into `_field_columns`, which is now the single place that decides the
stored form of a field, and "no options" is written as `Json(None)` on create and update alike.
`Json(None)` reads back through this driver as `None`, identical to the NULL an omitted key would
leave. **Omitting the key would have been a second bug:** on an update it means *leave this column
alone*, so a MULTI_ENUM retyped as TEXT would keep offering yesterday's picker under a 200.

**Proof.** The same request now answers 200 and the row reads back matching what was sent; all twelve
v1 field types created, edited and retired over HTTP; an answered field reworded and observed to
supersede rather than overwrite. `tests/test_custom_sections_endpoints.py`, 10 tests. **8 of the 10
fail when the one expression is reverted** — checked, not assumed.

### [HIGH] TEST-GAP — 84 passing tests could not have caught it, and one test in a sibling file actively pinned the same defect (backend) — **found and fixed 2026-08-13**

**Where.** `backend/tests/test_custom_sections.py` (84 tests, no database, nothing skips) and
`backend/tests/test_ai_layers.py`.

**What.** The custom-sections suite is pure by design and is right to be — it pins the planning logic.
But the defect above is not in a decision; it is in the one step that has no decision in it, handing a
finished plan to the driver. A suite with no database cannot reach that line, so "the rules are
covered" was read as "the feature works".

Worse, in `test_ai_layers.py` a test **asserted the broken value**:

```python
assert _json_ready("DwAiLayer", {"payload": None})["payload"] is None
```

It passed for the life of the module while every prose-layer write was refused, and its docstring
justified it with a claim that measurement contradicts: "`Json(None)` writes a JSON null … a bare
None writes SQL NULL … they read back differently." A bare `None` writes nothing at all — the engine
refuses it — and `Json(None)` and SQL NULL both read back as Python `None` through this driver.

**Fixed.** `tests/test_custom_sections_endpoints.py` added: every test performs a real request
against the real app and then reads the stored **row** back out of Postgres on its own connection,
because a response is assembled by the process that did the write and will agree with itself. The
`test_ai_layers` assertion was inverted with the measurement recorded beside it. The pure file keeps
all 84 tests and gains a docstring saying what it does and does not prove.

**Also measured: how much of this router has never met a database.** 39 routes on the design-workshop
router; **18 of them had never been driven against Postgres** before this pass (13 appearing only in
pure test files, 5 in no test at all), now 16. **11 of the 16 are the AI-layers surface** — which is
exactly where the third instance below was found.

### [HIGH] CORRECTNESS — the same driver refusal made every prose AI layer unwritable (backend) — **found and fixed 2026-08-13**

**Where.** `backend/app/services/ai_layers.py`, `_json_ready`.

**What.** `layer_create_plan` puts `"payload": payload` into the write unconditionally and `payload`
defaults to `None`, `_json_ready` deliberately left that `None` alone, and `apply_plan` handed it
straight to `create`. So every layer with prose and no structure — every `RAW_TRANSCRIPT`,
`CLEANED_TRANSCRIPT`, `TRANSLATION`, `PROOFREAD` and `EXPANDED` row — was refused by the query engine
with the identical `MissingRequiredValueError`. Confirmed by driving
`layer_create_plan` → `apply_plan` against the live database.

**Found by looking for the shape rather than fixing the one that was reported.** Nothing pointed here;
`_json_ready` was found by grepping for every `Json(` write site in the backend after the
custom-sections cause was understood.

**Fixed.** `_json_ready` now wraps a `None` instead of skipping it, and still leaves an **absent** key
absent, so "leave this column alone" and "set this column to nothing" stay different instructions.

### [MEDIUM] CORRECTNESS — one refused save was reported as two different numbers on the two surfaces (backend + frontend) — **backend fixed 2026-08-13, frontend one-line change outstanding**

**Where.** `backend/app/services/design_workshops.py`, the `save_stage` response;
`frontend/lib/designWorkshopStore.ts` line ~2831;
`android/app/src/main/java/com/designprototype/workshop/data/DwStageRefusal.kt`.

**What.** `errors` is `{scope: {field: message}}` and carried no total, so each client derived its
own. The web read `Object.keys(saved.errors ?? {}).length` — the number of **scopes** — while Android
built one refusal per (scope, field) pair and counted **fields**. Both printed their number in the
same sentence with the same word: "The server refused N of your answers". One stage row with three
unreadable numbers in it is therefore "1 answer" on a laptop and "3 answers" on the phone, off one
response body, and neither surface is lying about what it counted.

**Which is right.** Fields. An answer is what somebody typed into one box; a scope is a row of the
form; and the remedy the sentence offers — "open the stage to see which fields are marked" — is
per-field too, so the web's count contradicted its own instruction whenever a row held more than one
bad value.

**Fixed on the server**, which is where the ambiguity was: the response now carries
`refusedAnswers`, counted once by `refused_answer_count`, so no client has to derive a headline number
from a nested map. `errors` is unchanged — both clients need it to mark the individual boxes.
Pinned by `test_stage_sync.py::test_the_response_counts_refused_answers_and_not_the_rows_they_sat_in`,
which asserts **both** readings (scopes 1, fields 3) so it cannot pass for either one.

**Outstanding, and it belongs to the frontend:** `designWorkshopStore.ts` should read
`saved.refusedAnswers` instead of counting `Object.keys(saved.errors)`, and `DwStageSaveResult` in
`frontend/lib/designWorkshops.ts` needs the field declared. Android already counts fields and needs
no change.

### [HIGH] CORRECTNESS — 353 eligible accounts were invisible in the viewer picker, and looked exactly like colleagues who had never been empanelled (backend) — **found and fixed 2026-08-13**

**Where.** `backend/app/services/design_workshop_viewers.py`, `eligible_viewers`, and its route in
`backend/app/api/routes/design_workshop_viewers.py`.

**What.** The endpoint read accounts with `order={"name": "asc"}` and `take=ELIGIBLE_VIEWER_LIMIT`
(2000), had **no search parameter**, and had nowhere on the wire to say the list had been cut. Its own
warning said so out loud — "the picker is truncated and the endpoint needs a search parameter before
it can be trusted here" — and had sat there, correct and unheeded, while the defect went live on both
clients.

**Measured on the live database, not inferred.** 3632 accounts, of which the eligible set — every
ADMIN and MASTER\_ADMIN, plus every DESIGNER whose email is on the active roster — is **2380**. The
2000-row cut therefore fell mid-alphabet: the live API's last served row was `Sync Test`, so **353
eligible accounts sorting after it were absent from the picker on both clients**, with no search box
to reach them and nothing on screen saying anything had been hidden.

**Consequence.** An admin looking for a colleague did not find them, and could not tell whether that
was because the colleague was ineligible or because their name sorts late. Those two states must
never look identical, and on this screen the second one is a designer who cannot be let into a
fortnight of their own team's fieldwork.

**Why it stayed hidden for months.** The assumption was written into the constant's own comment —
"a few dozen accounts in a real deployment … deliberately far above anything an institution will
reach". The one test that would have caught it,
`test_eligible_viewers_offers_only_accounts_that_could_actually_open_a_workshop`, asked for the whole
picker and looked for its own fixtures in the answer, so it was **passing by accident of table size**
and only began to fail when the shared table crossed 2000. Its outsider fixture is named "Unrelated
Designer": U sorts past the cut.

**Fixed by giving the endpoint the two things it lacked**, not by raising the ceiling — 10000 would
only move the cut and ship 10000 rows to a handset on the way:

- `search` matches `name` OR `email`, case-insensitively, through `records.contains` so a NUL byte in
  the parameter is stripped rather than returned as a 500. It is folded into the **same `WHERE`** as
  the roster, because searching after the `take` would search only the first 2000 names of the
  alphabet — the very bug being fixed, one layer up, where an empty result reads as "no such person".
- The two `OR`s are **AND**-composed. Assigning both to `where["OR"]` lets the later win, and if that
  is the search then the eligibility clause is gone and the picker offers researchers, professors and
  suspended designers — a grant the next sign-in refuses.
- `truncated` on the response, exact rather than guessed: `take` is one row more than is returned, so
  a list exactly as long as the ceiling reports `false` honestly and no second `COUNT` is paid. The
  name deliberately matches the reference picker's `truncated`, which both clients already decode.

**Evidence.** Live API with this Postgres behind it, as an admin: unsearched → 2000 rows,
`truncated: true`, last name `Sync Test`, and the eligible admin `admin2@example.org` **absent**;
searched by that name → present, `truncated: false`; searched by that email → exactly that one
account; both arms case-insensitive. Searching a RESEARCHER's own address, and an off-roster
DESIGNER's, returns `[]` — the search narrows the eligible set and never replaces it. A 121-character
term is a 422; a NUL byte is a 200. Non-admins are still refused: designer 403 with and without
`search`, anonymous 401.

### [MEDIUM] CORRECTNESS — The active roster was read with the picker's page size and no warning at all, so at 2001 rows an eligible designer would have vanished in total silence (backend) — **found and fixed 2026-08-13**

**Where.** `backend/app/services/design_workshop_viewers.py`, `_active_roster_emails`.

**What.** `db.designerroster.find_many(where={"isActive": True}, take=ELIGIBLE_VIEWER_LIMIT)` — the
**picker's page size reused as a roster read cap**. They are different quantities that happened to
share a number.

**Why this is worse than the entry above even though it had no symptom.** These emails are folded
into the user query's `WHERE`, so a roster row past the cut does not shorten a list — it removes an
**eligible designer from the picker entirely**, as though they had never been empanelled. The picker's
truncation at least logged a warning. This read had none: no log line, no wire signal, and no test
that would fail.

**Measured.** 1282 active roster rows, so the shared 2000 was not being hit and nothing was wrong
**yet**. At 2001 it would have begun silently refusing to admit designers.

**Fixed.** Its own constant, `ACTIVE_ROSTER_READ_LIMIT`, set far above any plausible roster as a
backstop against an unbounded read rather than as a working limit — deliberately not left uncapped,
because an uncapped read of a table that only grows has no failure signal at all and merely gets
slower until something times out. Hitting it is logged at **ERROR** (louder than the picker's
warning: the picker's truncation is a long list the caller can narrow, this one is people the caller
cannot reach by any search) and is OR-ed into the response's `truncated`, because a picker missing
eligible designers is exactly what that flag means.

**Evidence.** `test_a_cut_roster_read_is_reported_instead_of_dropping_designers_in_silence` drives it
by moving the cap to 1 rather than by writing 50000 rows, and asserts against the same call uncut:
the rostered designers disappear, the admin remains (admins are not roster-gated at any point), and
`truncated` is `true`.

### [MEDIUM] TEST-GAP — three contracts of the fixed picker were pinned by nothing, and two mutations of it were silently green (backend) — **found and fixed 2026-08-13**

**Where.** `backend/tests/test_design_workshop_viewers.py` against
`backend/app/services/design_workshop_viewers.py`.

**Found by mutation testing the fix above** rather than by reading it: four mutations the fix's own
brief named were caught, and two further ones were not.

**What was unpinned, and why each matters more than it looks.**

1. **`order={"name": "asc"}` deleted → the whole suite stayed green.** Not cosmetic. The list is CUT,
   so with no ORDER BY the cut is non-deterministic: two identical requests hide two different
   populations and "is this colleague reachable" changes on refresh — the invisible-colleague defect
   restored in a form no search term can be relied on to reach. Both clients also TRUST the order and
   deliberately do not re-sort (`dwViewerChoices`: Kotlin's `sortedBy` disagrees with Postgres's
   collation). Android had a test named "the order is the server's, not this client's", which pins the
   CLIENT not re-sorting; nothing pinned the server sorting.
2. **A NAME IS NOT A UNIQUE SORT KEY, and that is a real defect and not only a test gap.** Measured on
   this database: **204 accounts share the name "Sync Test", and that is the name the 2000-row cut
   lands on** — so which of those 204 fell inside the ceiling was Postgres's arbitrary choice.
   `order` is now `[{"name": "asc"}, {"id": "asc"}]`, a total order.
3. **`.strip()` removed from the search term → green.** The route's docstring and the wire contract
   both say omitted, empty and whitespace-only are one case; nothing asserted it, so `?search=%20%20`
   became a real `ILIKE '%  %'` and collapsed the picker to "No eligible account matches that search."
   for an admin who had typed nothing. Both clients trim before sending, so this was a server-contract
   gap — the exact "empty list with no explanation" class this module is otherwise careful about.
4. **`ACTIVE_ROSTER_READ_LIMIT`'s VALUE was pinned by nothing.** Its behaviour test monkeypatches the
   constant to 1, which proves only that the code reads it; re-shrinking it in source to the picker's
   2000 left every test green, at **1523 active roster rows measured today** — 1.3x headroom on a read
   whose overflow removes eligible designers from the picker entirely.

**Fixed** with four tests: `test_the_picker_is_ordered_by_name_and_both_clients_depend_on_that`
(asserts the exact name sequence of this run's own accounts, whose creation order is deliberately
different), `test_accounts_that_share_a_name_come_back_in_one_stable_order` (eight fixture accounts
with WRITTEN ids created highest-first, so an unsorted answer cannot come out ascending by luck — two
accounts could, and did, survive one mutation while catching another),
`test_a_whitespace_only_search_is_the_same_as_no_search_at_all` (empty, three spaces, and a
tab/newline term, hermetic by moving the ceiling rather than counting the table), and
`test_the_roster_read_cap_is_its_own_number_and_stays_a_backstop` (pins the reasoning — a different
quantity from the page size, and an order of magnitude above it — not a magic number).

**Evidence.** Every mutation applied IN PROCESS through a pytest plugin that rebinds the name the
route imported, so the shared tree was never edited and no concurrent lane could pick up a broken
module. `order` deleted → the ordering test fails, naming `'Viewer Admin' != 'Second Designer'`, i.e.
the answer arrived in insertion order. Tiebreaker removed → the shared-name test fails. `.strip()`
removed → the whitespace test fails with `{13 ids} <= set()`. The roster cap re-shrunk to 2000 → the
constant test fails with `assert 2000 != 2000`. Live, against the running API with this Postgres: the
tie group's 204 rows come back in exactly the order SQL gives for `ORDER BY name ASC, id ASC`, ids
ascend inside the shared name, and two identical requests return the identical order.

### [MEDIUM] CORRECTNESS — the picker's roster fold was case-sensitive where the write path is not, so an eligible designer could be hidden from an offer the PUT would have accepted (backend) — **found and fixed 2026-08-13**

**Where.** `backend/app/services/design_workshop_viewers.py` — `eligible_viewers`'s roster clause
against `_designers_the_roster_still_admits`, which decides the same question for the write.

**What.** `_active_roster_emails` returns `normalise_email`'d — lower-cased — addresses, and the
picker folded them into `{"email": {"in": admitted}}`: an exact comparison against `User.email` **as
stored**. The write path normalises BOTH sides. So a designer whose address is stored in a different
case from their roster row was refused by the picker and accepted by the PUT — and the picker's
refusal is invisible, because absence from the offer reads as "never empanelled". No search term
reaches them either, which makes it worse than the ceiling this module was just fixed for.

**Measured.** Two `User` rows hold a mixed-case address today and both are ADMIN, who are not
roster-gated at all, so **no designer's eligibility flips either way right now** — latent, not live.
Reproduced deliberately: a DESIGNER stored as `DWLIVE-SHOUTY-…@EXAMPLE.ORG` with a lower-cased ACTIVE
roster row is absent from every search on the old clause and offered by the new one. The fixture was
removed afterwards.

**Fixed** with `mode: "insensitive"` on the `in`, which is the same comparison the write already makes.
**Cost measured before adopting it**, because the clause folds the whole active roster into one `IN`:
against 1523 roster emails over 4428 users, insensitive **134.9 ms** against exact **143.6 ms** — inside
the noise, and 49.7 against 44.3 ms with a search term beside it. Pinned by
`test_a_designer_whose_address_is_stored_shouting_is_offered_and_may_be_granted`, which asserts both
halves: the picker offers the account AND the PUT accepts it, so the two paths cannot drift apart again
in either direction.

### [MEDIUM] CORRECTNESS — a truncated answer with nobody in it told the admin to narrow an empty search, on both clients (frontend, android) — **found and fixed 2026-08-13**

**Where.** `frontend/components/settings/DesignWorkshopViewersPanel.tsx` and
`android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopViewersScreen.kt` —
the three-state notice under the search box. (Written in full rather than abbreviated to `android/…`,
because `docs/tools/check-docs.mjs` resolves every path it can see and reports the short form as a
broken one; there were ten of those in this file and this pass did not add an eleventh. **All ten
have since been expanded**, on 2026-08-15 — the abbreviation cost a reader the ability to open the
file as surely as it cost the checker the ability to resolve it. Write paths in full here.)

**What.** `truncated` covers two different cuts and only ONE of them can be narrowed by typing. When
the ACTIVE-ROSTER read is what was cut, the missing designers are excluded from every possible search
and the answer can arrive truncated **with no users in it at all**. Both clients answered that state
with "Too many matches to show them all — narrow the search." over an empty picker — advice that
cannot work — and that arm also shadowed the accurate "No eligible account matches that search.". The
two states this whole feature exists to keep apart ("hidden from you" and "nobody matched") collapsed
back into one sentence, one layer down.

**Fixed** with a fourth state, ordered first, in the same words on both clients: "Some eligible
accounts could not be listed, and no search can reach them — the server log says why." The decision
moved OUT of the render in both clients — `dwViewerOfferNotice` in `data/DesignWorkshopViewers.kt` and
`eligibleViewerNotice` in `lib/designWorkshopViewers.ts` — because one of its four states cannot be
produced by any live database and a `when` inside a composable is only ever exercised by somebody
looking at a phone.

**Evidence.** Two Android unit tests over all four states (29 in that class now, up from 27); removing
the new branch turns exactly one of them red. A Playwright test stubs `{"users": [], "truncated":
true}` and asserts the new sentence appears while all three older ones are absent; with the branch
removed it fails on "waiting for … /Some eligible accounts could not be listed/". Reaching the state
for real needs 50000 active roster rows against today's 1523, which is why both proofs are stubs — and
why the wording, not the plumbing, is what they test.

---

## Closed on 2026-08-12

### [HIGH] CORRECTNESS — The server's OCR clipped a card's 16-digit VID into a 12-digit "Aadhaar number" printed nowhere on the card (backend) — **CLOSED**

**This register said this was open, and it was not.** It had been fixed by the identity-card lane
(`0e64f04`, "Merge browser identity-card reading, and the server VID fix it found") and this file was
not updated in the same commit — the exact failure its own header warns about, three paragraphs
above where the entry sat. Re-checked by reading the module rather than by trusting either document:

- `backend/app/services/identity_ocr.py` now holds `_DIGIT_TOKEN`, a **maximal** digit-run
  pattern — one ASCII digit followed by any number of "optional single separator, then another
  digit" — so a grouped sixteen-digit VID matches as ONE token rather than as a window into one.
  The pattern itself is deliberately not quoted here; read it in the module, where it sits under the
  comment that explains it. (Quoting it inline breaks `docs/tools/check-docs.mjs`, which reads the
  bracket-then-parenthesis sequence in that regex as a Markdown link and reports this file as
  carrying a broken one — in a code fence as readily as in backticks. A checker that cries wolf is a
  checker people stop running, and it is the only mechanical guard these documents have.)
- `aadhaar_candidates` refuses any token whose digit count is not `AADHAAR_LENGTH` **before** any
  checksum runs, and does not count it as `rejected` (that counter still means "the card was found
  and misread", which is the only case where "photograph it again in better light" is useful).
- The old pattern and the measurement that condemned it — **10.02% of 200,000 sampled
  Verhoeff-valid sixteen-digit numbers have a Verhoeff-valid twelve-digit prefix** — are preserved in
  a comment above the new one, so the defect cannot be reintroduced by someone who thinks the
  lookarounds were sufficient.

The fix matches what the Android client already did (`IdentityCardText.scanDigitRuns`), so all three
surfaces now read a card the same way.

### [MEDIUM] CORRECTNESS — The browser has always posted its dictation language under a name the server does not read (frontend) — **found and fixed 2026-08-12**

**Where.** `frontend/lib/designWorkshops.ts`, `dictateAudio`.

**What.** The form part was appended as `language`; the route declares
`languageHint: str | None = Form(default=None)` and reads nothing else. So the browser's hint was
discarded on arrival and the endpoint echoed `"languageHint": null` back to a caller that had just
told it the language.

**Why it survived.** It had no symptom. Nothing downstream reads the hint today —
`transcribe_audio_bytes` is called with the bytes, the filename and the MIME type only, and Deepgram
is deliberately called with `language=multi` because a workshop is code-switched mid-sentence. No
wrong transcript, no error, just a field that was never there. **That is the shape of defect worth
recording**: a client and a server agreeing about a name neither of them checks. The day the
provider chain is taught to use the hint, the browser would have been the one surface silently not
sending it.

**How it was found.** From the Android side, while adding the same rung to the handset — the part
had to be named against the route for the first time, and the two names did not match.

### [MEDIUM] DOCUMENTATION — The plan for custom sections rested on a claim about the code that is false (docs) — **found and fixed 2026-08-12**

**Where.** `docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md` §4, constraint 3.

**What.** It stated that stage entries are `extra="forbid"`, so arbitrary designer keys "will be
refused by design". They are not refused. `extra="forbid"` applies to the **envelope**;
`StageEntryIn.data` is an open `dict[str, Any]` (`backend/app/schemas/design_workshops.py`) and
`validate_entry` iterates `entity.fields` only (`backend/app/services/stage_schema.py` — its
docstring says so in as many words: "Unknown keys are DROPPED rather than rejected"), so
an unknown key is **dropped in silence**. The `merge: Extra inputs are not permitted` refusal the
plan cited as its evidence was an *envelope* field and said nothing about the payload.

**Consequence, and why a documentation defect is filed here at all.** The error inverts the design.
The plan was guarding against strictness having to be relaxed; the real hazard is that designer
answers are eaten without a word unless given an explicit home. A plan that is wrong in that
direction produces an implementation that looks correct and loses data. Corrected in place, with the
correction left visible rather than silently amended.

---

## The entry as it was written, kept for the re-check

*Everything below is a verbatim copy of the entry as filed, including its line number, which pointed
at the defective regex in the tree of the day it was filed. **It has not pointed there since the fix
landed** — the line now carries an unrelated comment — and it is deliberately not re-pinned, because
re-pinning it would make a frozen quotation say something it never said. The regex itself is quoted
below; that is what to search for.* <!-- rotted -->

### [HIGH] CORRECTNESS — The server's OCR clips a card's 16-digit VID into a 12-digit "Aadhaar number" printed nowhere on the card (backend)

**Where.** `backend/app/services/identity_ocr.py:311` <!-- rotted -->



```python
_AADHAAR_RUN = re.compile(r"(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])")
```

**Consequence.** Every Aadhaar card also prints a **sixteen**-digit Virtual ID, grouped 4-4-4-4. The
trailing lookahead inspects the SPACE after the twelfth digit, not the four digits beyond it, so the
pattern matches the first twelve digits of the VID and `aadhaar_candidates` offers them as a
candidate. Roughly one arbitrary twelve-digit string in ten satisfies Verhoeff, so the checksum does
not reliably stop it. The designer is then shown a well-formed number, confirms it against a card
that does not contain it, and it becomes the repository's deduplication key for a person who does
not exist.

**No client can defend against this.** A fabricated number only reaches the handset if it has
already passed Verhoeff, so re-checking it there catches nothing.

**Evidence, run rather than read** (Python, the module's own regex, verbatim output):

    >>> _AADHAAR_RUN.findall('VID : 2345 6789 0124 5678')
    ['2345 6789 0124']

and `2345 6789 0124` is Verhoeff-VALID — it is this repository's own test fixture. So that input
produces a candidate the card does not carry, all the way to the confirm panel.

**The Android client already refuses it**, deliberately and with the case named:
`IdentityCardText.scanDigitRuns`
(`android/app/src/main/java/com/designprototype/workshop/data/IdentityCardText.kt`) scans **maximal**
runs, so a
sixteen-digit run yields nothing rather than its first twelve digits. Confirmed on the Galaxy M32 on
2026-08-09 against a card carrying both the number and the VID: exactly one candidate was offered.
`IdentityCardTextTest.the sixteen-digit VID beside the number yields nothing at all` pins it, and
breaking the rule turns that test red with the fabricated number in the failure message.

**The fix.** Require the whole run to be twelve digits, as the device does — the lookahead must
reject a following separator-plus-digit, not only a following digit. Left open rather than applied:
it is the backend lane's file and `backend/tests` pin the current behaviour, so the change needs its
test updated in the same commit by somebody who owns both.

> **That is the text as it stood, and its last paragraph is what went wrong.** The fix it describes
> was applied — exactly as described, by the lane that owned the file — and this entry was left in
> the *Open* section anyway, so the register went on advertising a live Aadhaar defect for three
> days. Nothing was wrong with the diagnosis; the closure was simply not written down in the commit
> that earned it. **Kept here rather than deleted**, because "the fix landed and the register did
> not move" is the failure this file is most exposed to and the one its header spends a paragraph
> warning about. See *Closed on 2026-08-12* above for the re-check that closed it.

---

## Closed on 2026-08-08 — the last surviving item

### [MEDIUM] ENHANCEMENT — The field copy never says it is the abridged one, and when the designer is offline the office's export log does not say so either (android)

**Note.** Deliberately not fixed in the same pass that found it: the line sits inside the same
function the web-sync lane was editing concurrently, and two agents in one hunk is how work gets
lost. It is small and self-contained.

**RESOLVED 2026-08-09, in two halves — and the second half was not in this write-up.** The sentence
was split on `isSchemaRefusal` (`frontend/lib/offline.ts`) as the Fix above describes. That alone
left the defect standing, because the RETRY POLICY behind the sentence said the same false thing:
`noteStageFailure` recorded the refusal `permanent: true`, and a permanent failure is stepped over
by every future pass — so the app could not recover from a skew even after the skew had closed. That
is the state it was reported in for the second time: `PUT /design-workshops/{id}/stages/
CLUSTER_CRAFT_BACKGROUND` with `"merge": true` answered **200** while the banner was still refusing
to make the request. `blocksRetry` (`frontend/lib/offline.ts`) now re-attempts a schema refusal once
per app run, at workshop, stage and registry level, in both drains; draft schema v3 re-triages the
refusals already on disk; and the same policy is mirrored on the handset, which sends the same
`merge` flag (`android/app/src/main/java/com/designprototype/workshop/data/WorkshopSync.kt`,
`blocksRetry` + `ApiRefusal.schemaSkew`). Pinned by
`frontend/e2e/schema-skew-retry-unit.spec.ts`, `frontend/e2e/design-workshop-schema-skew.spec.ts` and
`android/app/src/test/java/com/designprototype/workshop/data/DwSchemaSkewRetryTest.kt`.

**VERIFIED 2026-08-09, and it was resolved in THREE halves, not two.** The browser spec above had
never been run — it skips without credentials — so it was run against the live stack for the first
time here (`designer@example.org`, seeded by `backend/scripts/seed_test_accounts.py`) and both cases
pass: a stage refused for a schema reason, then a server that accepts, ends up synced with nobody
clicking, including from a record wound back to the exact v2 shape the defect was reported in. Two
gaps were found and closed in the same pass:

1. **The records outbox had the policy and no trigger for it.** `lib/offline.ts` re-attempts once per
   APP RUN and writes a sentence promising the entry "will be sent by itself"; `OutboxBanner` drained
   only on the `online` event and on a click, and `online` never fires for a tab that was never
   offline — the laptop reopened the next morning on office wifi. MEASURED with an entry refused by
   an earlier run seeded into IndexedDB: **zero** replay requests across a reload before, one after.
   Fixed by the mount drain its sibling `DraftSyncBanner` already had and explains. Pinned by
   `frontend/e2e/outbox-schema-skew-drain.spec.ts`, which also pins the other direction — a field the
   validator rejected is re-recorded without a run stamp and the run after that leaves it alone.
2. **Android's records outbox was not mirrored.** `WorkshopRepository.syncOutbox` still stepped over
   every failed entry for ever (`if (queued.failure != null) continue`), and a queued create posts an
   `APIModel`, so `extra_forbidden` is reachable there exactly as it is for a stage. `PendingEntry`
   now carries `skewRun`, `replayEntry` reads the error body once through `apiRefusal`, and both
   queues on the handset use one `blocksRetry` and one `skewSentence`. Pinned by
   `android/app/src/test/java/com/designprototype/workshop/data/OutboxSchemaSkewRetryTest.kt`.

**Both halves are now closed.**

- *The provenance line* was closed earlier: it is built at
  `android/app/src/main/java/com/designprototype/workshop/report/ReportSettings.kt`
  (`fieldCopyNote`), reaches the cover from
  `ui/designworkshop/ReportScreen.kt`, and is covered by
  `android/app/src/test/…/ReportSettingsLedgerTest.kt`. Commit `5886fd9`.
- *The export log* was the last open item in this file and is closed by `cfec845`.
  `WorkshopRepository.recordDesignWorkshopExport` was a bare pass-through to the API wrapped
  in a `runCatching` at the call site, so an export made with no signal — which is the ordinary case,
  because the exports that matter most are made in a village at the close of a workshop, minutes
  before the file is handed to a visiting ministry officer — was recorded nowhere, and the officer's
  copy existed against an empty log. It now enqueues on the offline outbox (`PendingEntry` of type
  `designWorkshopExport`, replayed by `createFromEntry`) using the same `isTransient` triage as every
  other queued write. It still records the fact and never the bytes: a designer on a metered field
  connection is not charged thirty megabytes to prove a report was made, and the checksum is what
  matches the file later.

---

## Closed earlier — the twenty-eight

Grouped as the original register grouped them. Each line names the evidence that closes it, so a
reader can re-check in one grep rather than taking this file's word for it.

### group-a — the stage save path

| Finding | Closed by |
|---|---|
| [CRITICAL] A stage saved from a client that never downloaded it REPLACES the singleton row, deleting fields that client never read | `6119378` — `merge` is a per-entry flag on `StageSaveIn` (`backend/app/schemas/design_workshops.py`) honoured at `services/design_workshops.py` (`if entry.merge and previous:`), driven from `serverLoadedAt === null` on web and `!isAuthoritative(...)` on Android |
| [HIGH] A row deleted while a background sync PUT is in flight loses its deletion flag | `50f1ab9` — `removedFrom` is now computed the same way `dirtyAt` is, in both `markStagePushed` and the push transform |
| [MEDIUM] "Save and sync this stage now" starts an 800 ms timer instead of saving | `175ef63` — the button calls `persistLocally` directly and a dispose-time write lands the snapshot |
| [MEDIUM] A stage the server answers with 5xx is reported as "the connection dropped" | `175ef63` — an answered 5xx is a per-stage failure with the stage named, and no longer sets `stoppedOffline` |
| [MEDIUM] `putDraftStage` resolves confirmed media refs OUTSIDE the transaction its comment claims | `50f1ab9` — the `dwlocal:` → server-id map is read in the same readwrite transaction that puts the draft |
| [MEDIUM] A stage refused for a SCHEMA mismatch is blamed on the designer's answers | `9f7486f` — `isSchemaRefusal` (`frontend/lib/offline.ts`, matching pydantic's `extra_forbidden`) splits the sentence; an extra-input refusal now says the app and the repository are out of step and that no edit will clear it |

### group-b — entitlement and media

| Finding | Closed by |
|---|---|
| [HIGH] SECURITY — report generation and the transcript annexure fetch ANY MediaFile by client-supplied id | `0d4da23` — both `mediafile.find_many` calls are AND-composed with `owned_or_granted_where(viewer, owner_field="uploadedById")`, threaded through as a keyword with no default so a call site cannot silently skip it |
| [MEDIUM] SECURITY — `MediaFile.url` is taken verbatim from the upload payload | `92e4ae0` / `0d4da23` lane — the field is kept (removing it would 422 every installed build) and ignored: the stored URL is always derived from the object key |
| [MEDIUM] PERFORMANCE — one extra query per newly-attached audio clip on every stage save | `0d4da23` lane — one `mediaprocessingjob.find_many` over the candidate ids replaces the per-clip `find_first` (`backend/app/services/workshop_transcripts.py`) |

### group-c — what the phone's report contains

| Finding | Closed by |
|---|---|
| [HIGH] The infographic renderer ships in the APK and no chart block is ever constructed | `5886fd9` — `SpecialSection.CHART -> renderCharts(...)` in `ReportScreen.kt` |
| [MEDIUM] The completeness annexure is a 28-line table over scores already computed | `5886fd9` — `SpecialSection.COMPLETENESS` is built |
| [MEDIUM] The map block is never constructed | `5886fd9` — `SpecialSection.MAP -> renderMap(...)`, region-only, matching the server's empty-point case |
| [MEDIUM] The field copy never says it is the abridged one | `5886fd9` (provenance line) + this rewrite's commit (export log) — see above |
| [LOW] The export-retention subsystem is fully implemented and has no call site | Removed rather than wired, with the reasoning written into `report/ReportExport.kt`: retaining a second full copy of every report inside `filesDir` on a space-constrained handset, with no retention rule, is not what the capability was worth |
| [LOW] Correct the report-settings ledger — it nominates the transcript annexure on a false premise | `ccc3acd` — the ledger no longer claims the handset holds transcripts, and the questionnaire annexure is carried in the catalogue and declared unbuildable on the phone |

### group-d — export cost on the handset

| Finding | Closed by |
|---|---|
| [HIGH] The .docx writer holds every photograph's full original bytes on the heap at once | `5886fd9` — `MediaPart` holds an `ImageRef`, not a `ByteArray`; bytes are re-asked for and streamed into the zip entry |
| [LOW] The PDF export reads every photograph's whole file three times | `5886fd9` — same lane; the size cache is no longer cleared wholesale |

### group-e — report inputs and the backend read path

| Finding | Closed by |
|---|---|
| [HIGH] The report input load is up to ten sequential round trips | `0d4da23` — `_report_inputs` uses `gather_reads` (`backend/app/api/routes/design_workshops.py`) |
| [MEDIUM] The one-photo-per-record lookup caps its read at 4xN rows GLOBALLY | `0d4da23` lane — `_reference_photos` asks per parent instead of spending one global budget oldest-first |
| [MEDIUM] Report preview and generate scan the whole Location table uncapped on all six templates | `0d4da23` lane — the anchor load is skipped for a template carrying no map, and capped when it runs |
| [LOW] The design-workshop module docstring states a permission rule the module does not implement | Rewritten in place — `backend/app/api/routes/design_workshops.py` now describes the four clauses it enforces, including that `can_run_design_workshops` is a SET and a Professor is outside it |

### group-f — grants, storage and the analytics rationale

| Finding | Closed by |
|---|---|
| [MEDIUM] Data-access grants rebuild their scope with delete-then-N-inserts outside any transaction | `0d4da23` — `_upsert_grant` runs inside `async with db.tx()` with a single `create_many` |
| [MEDIUM] PERFORMANCE — a fresh boto3 S3 client is constructed for every single object | `0d4da23` lane — `_client()` is `@lru_cache`d in `backend/app/services/s3.py` |
| [LOW] The analytics module's performance rationale contradicts an index that shipped in a migration | Rewritten in place — `backend/app/api/routes/analytics.py` now says the index exists and points at `schema.prisma` as the single source of truth |

### group-g — web accessibility

| Finding | Closed by |
|---|---|
| [HIGH] The two hand-rolled modal overlays have no dialog role, focus trap, Escape, or backdrop guard | `a8c0900` — `CollabDialog` is built on `FieldDialog`; the Assign researchers overlay went the same way |
| [MEDIUM] The dialog system's header guarantees both reduced-motion switches; it read only the OS one | `a8c0900` — `FieldDialog` and `AppShell` both use `useAppReducedMotion()` |
| [MEDIUM] The review decision-note textarea has no accessible name | `a8c0900` — `useId()` pairs `htmlFor`/`id`, with the explanatory paragraph wired to `aria-describedby` |

### group-h / group-i — dead weight and navigability

| Finding | Closed by |
|---|---|
| [MEDIUM] `refOptions` — up to five requests per stage, built, threaded through three components, never read | `a8c0900` — deleted, with a note in `FieldInput.tsx` telling the next reader not to add it back |
| [MEDIUM] Android lists what is missing as inert text; the web turns the same list into links | `175ef63` — the missing-field list is navigable on the handset |

---

## What this pass added

The 2026-08-08 re-check also turned up nine defects that were **not** in this register. All nine are
fixed; they are recorded here so the next reader knows the sweep happened rather than re-finding
them.

- **My Activity under-reported on Android, and the sharing screen's record picker with it.** Both
  fetched page one of every list and sifted it client-side on `createdById`. Reading the repository
  is open, so page one is the newest hundred rows of the whole archive. MEASURED against the running
  API as `designer@example.org`: `/api/artisans` total=431 with page one spanning 34 distinct
  creators and none of that designer's own; `/api/media` total=854 across 18 uploaders, likewise
  none. That designer owns two records and both screens showed zero, with My Activity saying "You
  haven't recorded anything yet." Fixed by passing `createdBy` (`uploadedBy` for media) — every
  endpoint already accepted it, so there was no server work.
- **My Activity's record types disagreed between the clients.** Android had Processes and no Media;
  the web had Media and no Processes, while both ship a "Document process" and an "Upload media"
  menu entry. Android gained Media, the web gained Processes.
- **Android's dataset download handed over a truncated archive with no warning.** The server has
  always sent `truncated`; `DatasetManifestDto` dropped it. It is not derivable from the counts — a
  capped manifest is internally consistent, so "4,312/4,312 files" is true of an archive missing
  everything past the cap.
- **Tasks said "Nothing is assigned to you right now." when the request had failed**, on a handset
  that usually has no signal, with the offline banner underneath saying the opposite. The screen now
  distinguishes "loaded and empty" from "could not load", and says so when a list on screen is the
  one last fetched.
- **Tasks had no CANCELLED chip and no counts**, while the API, the DTO and `taskStatusLabel` all
  knew about CANCELLED — so a cancelled task assigned to you could be seen under "All" and never
  filtered for.
- **`designWorkshopStore.ts` cited this file** for a residue closed the same day, and quoted a
  refusal banner the code no longer produces.

**Not a divergence, checked and left alone:** Android's "Assigned by me" view has no web equivalent
and is gated on `isAdmin && adminChrome`, which correctly mirrors `backend/app/api/routes/tasks.py`
(`view=created` raises 403 unless the caller is an admin) and `frontend/lib/permissions.ts`
(`canAssignTasks = hasRank(user, "ADMIN")`). No permission was invented on either client.

---

## How this document is kept true

**A register is kept true by re-checking its entries against the tree, and by nothing else.** There
is no generator here and there cannot be: every entry is a claim that a specific defect is open or
closed, and only reading the code settles that. The header carries the date of the last re-check for
exactly this reason — an entry is trustworthy to the extent that somebody looked recently, and the
date is how a reader judges it.

**This file has already failed in both directions, and both failures are recorded above rather than
tidied away.** One entry sat listed as outstanding after it had been fixed (the frontend half of the
refused-answer count), which costs the next reader a hunt for a bug that is gone. And the closed
count read 40 while the sections held 41. Neither is detectable mechanically. The recount by heading,
and the note explaining it, are kept in the body as the warning.

| Claim class | Kept true by |
|---|---|
| Whether an entry is open or closed | **A re-read of the named code, on a date, by a person.** Nothing else. When you re-check, move the entry and update *Last re-checked against the tree* in the header — a stale date is honest, an unchanged date over changed content is not. |
| The **Status** line's two numbers | Counted against the section headings below, which is how the 40-versus-41 discrepancy was found. Recount when you move an entry; do not increment. **Dedupe first**: a defect that was part-closed in one pass and closed in the next has a heading in BOTH sections, and counting both is exactly how 40 became 41. The one live instance is the refused-save mismatch, whose 2026-08-15 heading closes the frontend half of its own 2026-08-13 heading. **This row failed on 2026-08-19**: it says to recount when you move an entry, an entry was promoted into `## Open` and the header still read "0 open" for four days. A number that contradicts a heading twelve lines below it is the one kind of rot a reader cannot be expected to catch, because they stop reading at the number. |
| Every **Where** in an entry | `node docs/tools/check-docs.mjs`, which resolves every repository path in this file. It cannot tell you the path is the *right* one, only that it exists. |
| Every **Pinned by** in a closed entry | The named test, actually run. A closed entry whose test does not exist, or exists and cannot fail, is the exact defect this register was written to stop shipping — several entries above say so explicitly, including one that records mutating the fix to prove the test went red. |
| Any total quoted for an audit that is not this register's own | **Something in the repository that can be counted, named on the same line.** The 2026-08-15 pass is safe because [AUDIT-2026-08-15.md](AUDIT-2026-08-15.md) holds its findings and a reader can count the headings. The 2026-08-19 pass was written up here as a confirmed count of 69 with the sentence "It has no write-up file of its own here yet, so do not go looking for one — the record of it is the fixes themselves", i.e. an unfalsifiable number in a file whose discipline is that every number is checkable. **Withdrawn 2026-08-20.** State the audit, its date and its method with no total until there is a file or a commit list; a number nobody can check is indistinguishable from a number that has drifted, and this file has already shipped one of those. |
| The relationship to [AUDIT-2026-08-15.md](AUDIT-2026-08-15.md) | Deliberate and one-way: the audit is a register of what was *found* and is not rewritten. Items are promoted from it into this file as they are taken on. Do not copy findings here that have not been through the fix-and-pin cycle, or this document stops meaning what its header says it means. |
| Line numbers in entries | **Do not add any.** By 2026-08-15 seven of this file's citations had rotted onto unrelated code — `StageScreen.kt:1320-1325` <!-- rotted -->, `WorkshopSync.kt:810-821` <!-- rotted -->, `StageSchema.kt:1310` <!-- rotted -->, and four more that were removed the same day: `CustomSectionsIn` cited 28 lines above the class, `patchDraftHeader` 279 lines above the function, a `designWorkshopStore.ts` pin on a comment in an unrelated function, and a `stage_schema.py` range 50 lines short of `validate_entry`. Every one of them passed the checker, because all it asked was whether the number was inside the file. **It now also tests drift**, wherever a backticked symbol sits on the same line as the pin — see `checkCitations` in `docs/tools/check-docs.mjs` — but that check is deliberately silent on a pin that names no symbol, so it cannot rescue a bare number. Name the symbol; a symbol that moves is still greppable and a line number that moves is a confident lie. |
| Paths written in full | Same run of `check-docs.mjs`. The `android/…` shorthand this file used ten times resolved for nobody — not the checker, not a reader trying to open the file. All ten were expanded on 2026-08-15; the note in the viewer-picker entry explains the convention and it is now the rule for this file. |

**Review triggers:** any fix landing that this register lists as open; any audit finding promoted
here; the end of any pass that closed something, at which point the header date and the two counts
both need touching.

**Known unverified:** entries closed against a *live stack* (several say so, naming
`designer@example.org` and `backend/scripts/seed_test_accounts.py`) are verified against a database
that has since moved on. The test named beside each is the durable part; the live-stack run is a
dated observation.

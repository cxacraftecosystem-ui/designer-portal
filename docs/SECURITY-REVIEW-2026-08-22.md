# Adversarial security review — the night of 2026-08-22

**Scope.** Everything between `144e046` and the working tree, plus the untracked additions listed by
`git status --porcelain` (the rating ledger, its migration, the access-request queue,
`frontend/components/sketches/`, and six new e2e specs). Reviewed as somebody trying to reach data
they are not entitled to.

**THE SCOPE NUMBER MOVES WHILE YOU READ IT, so it is stated with the clock beside it and not as a
fact about the repository.** `git diff 144e046 --shortstat` answered **172 files / 26,765 insertions
/ 2,008 deletions** when this review began (2026-08-22 ~22:30), **197 / 28,859 / 2,233** during the
re-review, and **207 files / 30,652 insertions / 2,346 deletions** at 2026-08-23 02:54 — twice, one
minute apart, it answered 30,629 and then 30,652, because three other waves were committing into
this tree throughout. Re-run the command rather than quoting any of those figures; the reviewed
*surfaces* are what this document is about, and they are named individually below.

**Method.** Source review against the diff, plus independent re-derivation of the claims that
matter — I re-parsed the router gate rather than trusting its docstring's count, and I executed the
CI script's slug parser against traversal inputs rather than reading the regex. Every citation below
is a file I opened. Where I could not verify something, it says so.

**THIS DOCUMENT HAS BEEN THROUGH AN ADVERSARIAL RE-REVIEW AND A REPAIR PASS.** A second reviewer was
asked to break it; the record of what they found is the *Adversarial re-review* section at the end,
and every finding in it has been folded back into the body above rather than left as an appendix a
reader might not reach. Three of the corrections are worth knowing before you read on, because two of
them are the shape of error this repository fails reviews for: a "returns zero" grep that returned
two (*Secrets*), a negative asserted about the one test module the review had not run (*How this
document is kept true*), and a whole new door the review never mentioned (*The design-workshop access
queue*).

**Headline.** The night's security-relevant work is unusually good. The new rating ledger, the
by-id reference lookup, the QR grammar and the vendored trace engine all hold. Three of tonight's
changes are net security *improvements* (revision redaction, dataset-token revocation at use, the
CI-script deploy-target fix). **Nothing tonight opened a hole** — a claim worth exactly as much as
the list of surfaces it covers, which is why *Categories checked and CLEAN* enumerates them and why
the access queue was added to it on the repair pass rather than being left implied. **Five** findings
follow; the two worth acting on (**F1**, **F4**) are both pre-existing conditions that tonight's work
made *visible* — F1 by building a module that gets the same question right one file away, F4 by
correcting the documentation and leaving the code.

**A NOTE ON HOW THINGS ARE CITED BELOW, because the first draft of this document got it wrong.** Line
numbers in a tree with three concurrent waves committing into it rot within the hour: four citations
here had already moved when the re-review checked them, and by the repair pass two of *those* had
moved again — `config.py`'s `jwt_expires_minutes` went :104 -> :165 -> :183, and
`StageReferenceField`'s `adoptCreated` went :1929 -> :1949 -> :2009. Every claim below therefore
names the **symbol** (the function, the constant, the route), which survives an insertion above it.
Where a bare line number is still given it is because there is no symbol to name, and it says when it
was read. `docs/tools/check-docs.mjs` now carries a citation-drift checker; naming the symbol is what
satisfies it.

---

## Ranked findings

### F1 — MEDIUM. A demoted or suspended designer keeps read, report download and MEDIA EXPORT on every workshop they were granted

**Who the attacker is.** A designer who is leaving, or who has just been let go. They have a live
session token and one or more `DesignWorkshopViewer` rows.

**What they have after the administrator acts.** Everything. Two separate revocation gestures both
miss this door:

* **Role demotion** (DESIGNER → RESEARCHER) — `backend/app/api/routes/users.py` writes `User.role`
  and touches no grant row. The only two writes to the grant table in the whole backend are the
  `delete_many` and the `create_many` inside
  `backend/app/services/design_workshop_viewers.py::set_viewers`, which runs only when an admin edits
  *that one workshop's* viewer list. Verified: `grep -rn "designworkshopviewer" backend/app
  --include=*.py` filtered to non-read calls returns exactly those two calls.
* **Suspension on the access screen** — `backend/app/core/deps.py` states the gap itself in the
  SCOPED TOKENS banner: *"WHAT IS STILL NOT REVOKED … an ordinary SESSION token … a suspended
  account keeps the interactive application until its session token expires."* That is
  `Settings.jwt_expires_minutes` in `backend/app/core/config.py`, default `60 * 24 * 7` —
  **seven days**. (Cited as `:104` in the first draft, `:165` at the re-review and `:183` at 02:54 on
  2026-08-23 without one character of it changing — which is why it is named and not numbered.)

**Why the grant still opens the door.** `load_workshop_or_404` in
`backend/app/services/design_workshops.py` performs **no role check at all**. Its clause is
`createdById != user.id and not admin and not await has_viewer_grant(...)` — rank never appears.

**What they get — and this paragraph was WRONG in the first draft, in both directions.** It said
"everything … the whole participant roster", itemised as the artisan's address, email, phone and
masked card. **That itemisation is not what the stale grant buys, and naming it there overstated the
finding.** Every one of those values is already readable by *any* signed-in account holding no grant
at all: `backend/app/services/records.py::viewable_where` returns `{}` — its own first line is *"Row
filter for READING the repository: everything, for every signed-in account"* — and
`backend/app/api/routes/artisans.py` gates `GET ""` (`list_artisans`) and `GET "/{artisan_id}"`
(`get_artisan`) on `Depends(get_current_user)` alone. A demoted RESEARCHER reads that roster through
`/api/artisans` whether the stale grant exists or not.

What the stale grant **uniquely** buys is this workshop's own content, and a take-data-out path:

* **The stage content and the report.** `POST /design-workshops/{workshop_id}/report` takes
  `current_user: Any = Depends(get_current_user)` and nothing else, and its body's only gate is
  `await load_workshop_or_404(workshop_id, current_user)`
  (`backend/app/api/routes/design_workshops.py::generate_report` — I read the decorator, the
  signature and the body). The `.docx` carries the stage rows as hydrated at save time, including the
  `participant` entity's `fromref("address", …)`, `fromref("email", …)`, `fromref("phone", …)`,
  `fromref("artisanCardNo", …)` (the masked Pehchan card) and `fromref("subjectLocation", …)` in
  `backend/app/services/stage_definitions.py`. What is *new* about the document is the collation —
  this workshop's chosen participants, in one file, with the designers' own stage prose beside them —
  not the individual columns. (`subjectLocation` is correctly the artisan's own pin, not the device
  fix.)
* **MEDIA EXPORTS — the worse half, and the first draft missed it entirely.**
  `backend/app/services/records.py::owned_or_granted_where` — the filter for *taking data out*, as
  distinct from reading — adds a third clause for every account below PROFESSOR, reached by
  `if owner_field == "uploadedById"` and built by `_design_workshop_media_branches(uid)`. Its stated
  subject is *"A THIRD CLAUSE, ON MEDIA ONLY: THE RECORDINGS OF A DESIGN WORKSHOP THIS ACCOUNT MAY
  OPEN. A `DesignWorkshopViewer` row …"*, and it resolves the grant through
  `design_workshop_viewers.visible_to_clause`. So a stale grant keeps the transcript, annexure and
  export paths open over the workshop's recordings — not merely stage text on a screen. This document
  cited that same function approvingly in its media-CLEAN section (where it is correct: the clause is
  the deliberate co-designer fix) and failed to connect it to F1, which is where the same clause is
  load-bearing in the other direction.

**The owner-facing sentence is therefore: a stale grant keeps the workshop's stage content, its
report, and its media exports.** Not "the participant roster's PII", which is true of every account
that can sign in and is a separate question about `viewable_where`. Demotion is worse than suspension
here: a demoted RESEARCHER can still *sign in*, so the access is **indefinite** rather than seven
days.

**Why it belongs in tonight's review even though the mechanism predates it.** Tonight sharpened the
contrast into an inconsistency. `backend/app/services/design_ratings.py::access_for` gates the
*same* workshop's data behind `can_run_design_workshops(user)` and refuses everything to a role
outside the set — while `load_workshop_or_404`, one file over, hands the whole workshop to the same
account. And tonight's revocation wave deliberately closed the dataset-token door at *use*
(`deps.require_dataset_admin`) on exactly the argument that applies here: *"A gate on issue alone
revokes nothing for the life of the credential already out there."* The grant is a credential
already out there.

**Suggested fix, owner's call.** Either add `can_run_design_workshops(user)` to
`load_workshop_or_404` beside the grant clause (narrow, and matches `design_ratings`), or delete a
user's grants when their role leaves `DESIGN_WORKSHOP_ROLES`. The first is a smaller edit and fails
closed on both demotion and any future role change; the second also revokes on suspension only if
suspension is taught to run it.

---

### F2 — LOW/MEDIUM. `POST /design-workshops/{id}/exports` is the one non-GET route with no role gate

**Verified independently, not taken from the docstring.** I parsed
`backend/app/api/routes/design_workshops.py` for `@router.<verb>` decorators and checked each
handler body for `_require_designer(`:

```
total routes: 42   non-GET: 22   direct _require_designer calls: 14
NON-GET WITHOUT _require_designer:
  POST /dictate                      (retired, answers 410)
  POST   (create)                    (assert_can_create_design_workshops — narrower)
  DELETE /{workshop_id}              (assert_can_delete)
  POST /{workshop_id}/restore        (require_admin)
  POST /{workshop_id}/ai-layers/{proofread,expand,translate,caption,subtitles}   (_verb_gate)
  POST /{workshop_id}/report         (read-gated, deliberate)
  POST /{workshop_id}/exports        ← no role check
```

`record_device_export`'s only gate is `await load_workshop_or_404(workshop_id, current_user,
for_edit=True)`, and `for_edit` adds a soft-delete 409, not a role predicate. So any grantee — and,
via F1, any *demoted* grantee — can append a `DwReportExport` attestation row to a workshop they may
only read. Impact is small (an attestation about a file they could already generate), but it is a
write by an account with no write entitlement, and it is F1's read hole with a pen in its hand.

**This finding IS pinned by a test**, unlike F1:
`backend/tests/test_design_workshop_gate.py::test_the_designer_gate_still_stands_where_this_docstring_says_it_does`
carries `("POST", "/{workshop_id}/exports")` in `UNGATED_WRITES` and asserts the ungated set exactly,
so closing F2 fails that test until the set and the docstring are corrected together. I ran the
module: **20 passed, exit 0** (see *How this document is kept true*).

The `_require_designer` docstring already names this and calls it an owner decision. The docstring's
framing — *"a RESEARCHER or a PROFESSOR holding a viewer grant"* — **overstates the reachable path**:
`design_workshop_viewers._assert_every_id_may_be_granted` refuses any id whose role is outside
`DESIGN_WORKSHOP_ROLES`, so a researcher cannot be granted in the first place. The reachable path is
demotion after the grant, which is F1.

---

### F3 — LOW. The trace engine's pixel cap is applied after a full-resolution decode

`frontend/components/sketches/upload/decodeToPixels.ts` calls `createImageBitmap(file)` at the
file's own resolution and only then computes `workingSizeFor(...)` against
`DECODE_MAX_EDGE_PX = 4096`. A decompression bomb (a few hundred KB of PNG describing 40,000 ×
40,000 px) is therefore allocated at source size before any cap applies. There is no byte-size or
declared-dimension pre-check anywhere in `SketchTraceField.tsx` (`grep` for `size|MAX_|bytes`
returns only the `DECODE_MAX_EDGE_PX` import and two prose mentions).

**Honest mitigations, which is why this is LOW.** Browsers reject oversized decodes rather than
crashing, and the `catch` returns a designer-readable refusal instead of failing hard. The attacker
is the designer's own file picker, so the realistic worst case is self-DoS on a field handset.

**One adjacent inconsistency worth a line.** The same file's header argues the cap exists because
*"A 12 MP phone photograph is 48 MB of RGBA … three copies of it on a handset with 2 GB of RAM"* —
but 4096 px on the long edge admits 4096 × 4096 × 4 = **67 MB**, larger than the figure it names as
the failure. The cap is pinned to the "Trace resolution" slider's ceiling in
`traceParamTable.ts` rather than to the memory argument, and the header does not reconcile the two.

---

### F4 — LOW. The identity mask reveals the last four of a *malformed short* value, and only the docs were corrected

`docs/SECURITY.md` was corrected tonight to state the real threshold, and the correction is right —
I verified the code rather than the prose. `backend/app/services/artisan_identity.py:195-197`:

```python
if len(normalized) < 4:
    return "XXXX XXXX XXXX"
return f"XXXX XXXX {normalized[-4:]}"
```

So the branch is `< 4`, not "shorter than a full number". A five-digit legacy value reveals four of
its five digits — **80% of it** — through the same call that is house rule 5's only sanctioned
crossing for a Pehchan card number (`records.mask_identity_number` delegates straight to
`mask_aadhaar`; cited as :147 and :155 in the first draft, they read :152 and :160 at 02:54 on
2026-08-23). Every surface that carries `participant.artisanCardNo` inherits this, including a
submitted report.

**Why it is LOW and not higher.** It bites only on values that are already malformed — a
well-formed 12-digit Aadhaar or a full Pehchan card is unaffected — and whether any 4-to-11
character value exists is a production-database question I cannot answer from here (the local
compose Postgres is not that data). **The documentation now says this and the code still does it**,
which is the right order but not the end of it: if such rows exist, the fix is a one-line threshold
change replicated across the three ports (`mask_aadhaar`, `frontend/lib/identityCardText.ts`, and
Kotlin `ArtisanIdentity.mask`), which `SECURITY.md` correctly warns must land in one commit.

**Suggested next step:** run `SELECT count(*) FROM "Artisan" WHERE length(regexp_replace(
coalesce("pehchanCardNumber",''), '\W', '', 'g')) BETWEEN 4 AND 11;` (and the same for
`aadhaarNumber`) against production. If it is zero, close this as documented-and-moot. If it is not,
it is a real disclosure on live rows.

---

### ~~F5~~ — CLOSED. LOW. Replay detection compared a microsecond clock against a millisecond column

> **CLOSED, and left in place as a record rather than deleted.** The fix (`LEDGER_CLOCK_RESOLUTION`
> plus `_as_stored()` on both sides of `_is_stale_delivery`) landed from a concurrent wave, and the
> re-review re-ran the case: `pytest tests/test_design_ratings_api.py -k round_trips` → **1 passed,
> 25 deselected, exit 0**. Nothing here needs acting on; it is kept because the diagnosis explains a
> class of defect (a client clock finer than its column) that this ledger can grow again.

**Found by running the suite, not by reading it**, which is why it is here rather than in the clean
list. Measured:

```
pytest tests/test_design_ratings_api.py -rf -q
  FAILED test_a_rating_round_trips_and_a_repeated_capture_writes_nothing
  1 failed, 25 passed, 3 warnings in 793.43s (0:13:13)
```

The failing assertion is `backend/tests/test_design_ratings_api.py:695`,
`assert second.json()["replayed"] is True` — a second, byte-identical delivery of one offline
capture was **not** recognised as a replay and was applied as an UPDATE.

**Diagnosis.** `_rate` sends `datetime.now(UTC).isoformat()`, which carries **microseconds**;
`DwReviewRating.ratedAt` is `TIMESTAMP(3)`, which keeps **milliseconds**. The stored value therefore
reads back truncated, and `_is_stale_delivery`'s `incoming <= stored` was false for ~999 of every
1000 identical redeliveries. The migration's own header had already measured the truncation and said
*"the comparison is what has to tolerate the truncation"* — the comparison had not been given that
tolerance.

**Security impact: low, and I will not inflate it.** The values written are identical, so nothing is
corrupted; `updatedAt` moves and the response says `replayed: false`. Crucially the *amendment-undo*
protection was **not** affected — a genuinely queued original is older than the amendment by far more
than a millisecond, so `incoming <= stored` was still true for the tunnel case that rule exists for.
This was an idempotency defect, not an access-control or integrity one.

**Already fixed, by a concurrent wave, while this review was being written.** `design_ratings.py`
now carries `LEDGER_CLOCK_RESOLUTION = timedelta(milliseconds=1)` (:248) and `_is_stale_delivery`
truncates *both* sides through `_as_stored()` before comparing.

**The diagnosis and the fix were both confirmed by calling the predicate directly**, which is a
pure function and needs no database:

```
incoming (what the client sends): 2026-08-20T18:04:12.318670+00:00
stored   (TIMESTAMP(3) truncated): 2026-08-20T18:04:12.318000+00:00
_is_stale_delivery(stored_row, incoming) = True      # True = REPLAY, no write
```

`318670 > 318000`, so on the pre-fix `incoming <= stored` this was **False** — the exact failure the
test caught. Post-fix it is `True`. That is the mechanism proved rather than inferred.

**I did not re-run the test module against the fixed tree**, so treat the `1 failed` above as a
point-in-time measurement of the tree at ~23:10, not as the current state.

---

## Findings the code already names, reported so the owner sees them in one place

These are disclosed at length in the source. They are not defects hiding; they are live trade-offs
that shipped tonight and that a security reader should see collected.

* **`Artisan.aadhaarNumber` is now unrecoverable after an edit.**
  `backend/app/services/access.py::REVISION_REDACTED_FIELDS` (new tonight) stops
  `RecordRevision.changes` copying a retracted identity or contact value — a genuine improvement,
  and the right call for `phone`/`email`/`address`. The cost, which that comment states in full, is
  that the `RecordRevision` `old` was the *last copy anywhere* of a previous Aadhaar number
  (Aadhaar crosses into no stage entry at any masking). After tonight an EDIT-tier grantee, or a
  professor outranking the author, can repoint or clear one, the previous identifier is gone, and
  the freed `UNIQUE` dedup key admits a duplicate artisan with nothing to trace the collision back
  to. The comment offers `mask_identity_number(old_value)` as the alternative and correctly hands
  the decision to the owner rather than taking it. **Flagged as an owner decision, not a defect.**
* **`barred_emails()` has a ceiling of 50,000** (`access_roster.BARRED_EMAIL_READ_LIMIT`). Past it
  the eligible-viewer picker OFFERS an address an administrator has rejected or suspended. Logged
  at ERROR in those words, and the write path (`barred_among`, uncapped) refuses them
  independently, so nobody actually gains access. Not exploitable today.
* **`POST /api/datasets/token` can now answer 503 because of other people's traffic.** The gate
  writes, and `record_refused_attempt` returns `NOT_RECORDED` when the pending queue is at
  `access_roster.pending_cap()`. A nightly mint can start failing for reasons unrelated to its own
  account. Bounded, because every writer to that queue has already proved a credential — it is not
  anonymous flooding. Named in `mint_dataset_token`'s own docstring.

---

## Categories checked and CLEAN

Each of these was hunted specifically. A category checked and clean is a result.

### Identity data widening — CLEAN
* **Aadhaar crosses nowhere.** Checked by AST walk rather than by grep, so a mention inside a
  docstring cannot be mistaken for either a hit or a miss: parsing
  `backend/app/services/{design_workshops,design_ratings,report_builder}.py` and collecting every
  `Name`, `Attribute` and string constant containing `aadhaar` outside a docstring returns **`[]`
  for all three files**. The single grep hit
  (`design_workshops.py:4128`, "a photographed Aadhaar card") is inside `media_resolver`'s
  docstring, describing the leak that function's `viewer` parameter closed.
* **Pehchan crosses only through the helper.** The Artisan reference `data` lambda in
  `design_workshops.py:1265` is `"pehchanCardNumber": mask_identity_number(r.pehchanCardNumber)` —
  and it masks *unconditionally*, which is narrower than `/artisans/{id}`, where a professor gets it
  unmasked. `pehchanCardAvailable` (a boolean) crosses beside it.
* **No new hydration pair carries identity.** The diff of
  `backend/app/services/stage_definitions.py` adds `rankFixedBy`, `rankFixedAt` and
  `sketch.peerRoundClosedAt` and deprecates `rank`; grep for `fromref|aadhaar|pehchan|address|phone|
  email` over its added lines returns nothing.
* **The QR payload is an opaque id.** `frontend/lib/workshopCodes.ts` — `DPW1:<type>:<ID>:<check>`,
  no name, no village, no number, and `encodeWorkshopCode` *refuses at runtime* anything shaped like
  an Aadhaar or Pehchan number, after normalising away spacing (so the spaced form cannot slip
  through). The Kotlin twin `android/…/data/DwWorkshopCodes.kt` carries the same two refusals with
  the same sentences.
* **The identity-conflict path.** Both new hosts pass only an id.
  `StageReferenceField.tsx`'s create-then-adopt handler → `adoptCreated({ id: artisan.id, name:
  artisan.name })` (cited as :1929 in the first draft, :1949 at the re-review, :2009 at 02:54 on
  2026-08-23 — the line moved three times, the call did not change);
  `StageRecordEmbed.tsx::handleUseExisting` → `adoptCreated(artisan.id)`. The
  `ArtisanIdentityMatch.maskedValue` the dialog also carries reaches neither.
* **Nothing on the rating ledger carries identity.** `DwReviewRating` is
  workshop/entry/entityKey/reviewer/round/score/comment/suggestion/clocks.

### The 404-not-403 rule on tonight's four new doors — CLEAN
* **Rating API.** One `NOT_FOUND = "Record not found"` constant, one `_not_found()`, used for a
  missing subject, an unreadable subject, a missing workshop, a role outside the set, and an empty
  pool result. `load_subject` collapses three states (no row, soft-deleted, non-rateable entity)
  into `None`. The two deliberate exceptions are correct: 403 for self-rating (the caller
  demonstrably knows the record exists — it is theirs) and 422 for an unknown round token (a fact
  about the request, not about a record).
* **The empty-pool oracle is closed.** `round_ranking` raises 404 when `pool_visible` leaves a
  stranger with nothing, so the route does not answer 200 for every extant workshop id and 404 for
  every non-extant one.
* **By-id reference lookup.** `reference_options(..., record_id=, viewer=)` appends an `id` clause
  to the *same* `where` — it does not replace the scope, cascade or search — and composes
  `viewable_where(viewer)` at the top. The out-of-scope probe is a `find_many` over that `where`
  with only the workshop clause removed, never a `find_unique` on the primary key, and the row comes
  back under `outOfScopeOption` with `options` left empty, so a client that has never heard of the
  key cannot render it as an ordinary choice.
* **The scanner client does not undo the server's care.** `frontend/lib/workshopCodeLookup.ts`
  collapses every `ApiError` to one sentence and distinguishes only "the server never answered".
* **The access-request queue — the fourth door, and the first draft of this review did not contain
  the word.** It has its own subsection below (*The design-workshop access queue*), because it is the
  one door whose *whole design* is an anti-enumeration argument: a table of refusals does not do it
  justice when every branch is required to answer identical bytes.

### The rating ledger, question by question — CLEAN
| Question | Answer | Where |
|---|---|---|
| Read another designer's ratings? | No. `visible_rows` returns all rows only to `may_read_ledger` (admin, or the record's own author/workshop creator); everyone else gets **their own row only** | `design_ratings.py::visible_rows` |
| Rate your own work? | No. `may_rate = in_round and not (author and SELF_RATING_IS_REFUSED)`, gated on `is_row_author` (`DwStageEntry.createdById` alone) | `access_for` |
| Rate in an ineligible round? | No. PEER = member/admin; POOL = member/admin **or** `peerRoundClosedAt` set on **that row** | `access_for`, `pool_is_open` |
| Amend someone else's? | No. `reviewer_id` is always `current_user.id`; `existing_rating` filters on it; the UPDATE's `where` is `{"id": existing.id}` from that read | `record_rating`, `rating_plan` |
| Reviewer identity withheld? | Yes, **server-side, by omission**. `rating_payload` adds `reviewerId` only when `mine or access.sees_rater_identity`; the key is *absent*, not empty | `rating_payload` |
| Does the listing leak non-visible rows? | No. `rank(subjects, rows)` reads `totals` only via `totals.get(subject.entry_id)`, and POOL narrowing happens **before** ranking | `round_ranking`, `rank` |
| Does the raw ordinal leak the collection size? | No. `show_ordinal=is_member or is_admin`; everyone else gets `placedPosition`, counted within what they were given | `ranked_payload` |

The score bound is enforced twice (`rating_plan` for the sentence, a `CHECK` constraint for the
average), `createdAt`/`updatedAt` are never client-settable, and the write is idempotent under
replay via the unique triple plus a device-clock comparison.

**These are not my reading alone — they are pinned.** `backend/tests/test_design_ratings_api.py`
carries 26 tests including `test_an_unrelated_designer_cannot_read_a_peer_rounds_ledger`,
`test_an_unknown_subject_is_the_same_refusal_as_a_forbidden_one`,
`test_a_designer_cannot_rate_their_own_prototype`,
`test_a_researchers_viewer_grant_does_not_admit_them_to_this_surface`,
`test_a_pool_ranking_with_nothing_opened_is_the_same_404_as_a_missing_workshop` and
`test_a_peer_is_sent_the_average_and_their_own_row_and_no_other_row`.

**And I executed them rather than only reading their names** —
`pytest tests/test_design_ratings_api.py -rf -q` against the live compose Postgres:
**1 failed, 25 passed in 793.43s**. Every one of the six access-control tests named above is in the
25 that passed. The single failure is the idempotency defect written up as **F5**, which touches no
permission rule. So the access-control properties in the table above are not my reading of the
source — they are asserted and green.

`test_design_ratings.py` (the pure-function half) was also run: **858 passed in 451.99s**, exit 0.
`test_review_rating_ledger.py` (20 cases) and `test_revision_pii_redaction.py` (14) were counted but
**not run**.

### The design-workshop access queue — CLEAN, and MISSING from the first draft of this review

**Why this section exists at all.** The first draft did not contain the strings
`design_workshop_access`, `access-request` or `design-review` anywhere, while claiming a headline of
"Nothing tonight opened a hole" and enumerating "tonight's three new doors". Tonight added a fourth,
and it is the one most directly connected to F1: `POST /design-workshop-access/requests/{id}/decide`
**mints the `DesignWorkshopViewer` row F1 is entirely about**. Silence and "checked and clean" are
different claims and only the second is a review. Measured on the repair pass (2026-08-23 02:5x —
these files were still growing, so the counts are stamped): `backend/app/api/routes/design_workshop_access.py`
**155 lines**, `backend/app/services/design_workshop_access.py` **765**,
`backend/app/schemas/design_workshop_access.py` **74**, plus its migration, its tests and
`frontend/app/(protected)/design-review/page.tsx` (**266**).

**Reviewed as an attacker, it holds.** Three routes, and each was read decorator-to-return:

| Route | Gate | Attack considered | Result |
|---|---|---|---|
| `POST /requests` | `Depends(get_current_user)` — deliberately open | Enumerate workshops by asking about ids | **Refused.** `file_request` returns `None` in all seven outcomes and the route answers one fixed 202 + `RECEIVED_DETAIL` sentence for every one |
| `GET /requests` | `Depends(require_admin)` | Read the queue as a designer | **Refused** at the dependency; unknown `statusFilter` is a 422 about the request, not a 404 about a record |
| `POST /requests/{id}/decide` | `Depends(require_admin)` | Grant yourself, or grant a researcher | **Refused.** Grants only via `replace_viewers` |

* **The one route open to everybody is open on purpose, and its silence is the mechanism.**
  `request_access`'s docstring argues 202-not-201 explicitly: *"a client able to tell 201 from 202
  could ask about any id and read the existence of the record off the status line."* `file_request`
  returns silently for an unstorable id, for a workshop that is missing **or soft-deleted**, for a
  caller with no id, and for somebody who is already the creator / an admin / a grantee — so a
  probe cannot distinguish "no such workshop" from "exists, and you are already in".
* **The one thing it does say out loud says nothing about the database.** `ScannedCodeRefused` (a
  422) is raised only by `decode_design_workshop_code` and by the `decoded_id != wanted` comparison,
  both of which sit **above the first database read** — every sentence it can produce is true or
  false before any row is consulted. The service docstring names the test that enforces that
  ordering: *"Do not move a database read above that block — `tests/test_design_workshop_access_gate.py`
  is what notices."*
* **An authenticated flood cannot grow the queue.** The insert is
  `create_many(..., skip_duplicates=True)` against `@@unique([designWorkshopId, requestedById])` on
  `DesignWorkshopAccessRequest`, so a caller gets at most one row per workshop — and the schema
  comment states that this index, not a read-then-write probe, *is* the idempotency. The one repeat
  ask that does write is an `update_many` scoped to `status: "GRANTED"`, i.e. re-asking after a grant
  was removed; a `DENIED` row cannot be reopened by asking, and a `PENDING` replay cannot restamp
  the queue clock to jump the oldest-first ordering.
* **The grant path cannot be used to widen roles.** `decide` reaches `DesignWorkshopViewer` only
  through `design_workshop_viewers.replace_viewers`, which calls `_assert_every_id_may_be_granted`,
  which 422s any id whose role is outside `DESIGN_WORKSHOP_ROLES`. So the F2 docstring correction
  above holds here too: this queue cannot put a researcher or a professor behind
  `load_workshop_or_404`. It also refuses to write `DENIED` over somebody who can already open the
  workshop, and computes that from the same three-source predicate (`_access_by_pair`) that the
  response body reports, so the 409 and the `requesterHasAccess` field cannot disagree.
* **The admin payload is hand-projected, not encoder-walked.** `request_payload` returns four fields
  per person (id, name, email, role), the workshop's identifying fields, the decision columns and
  `requesterHasAccess` — and its docstring gives the reason: an encoder that walked the relations
  *"would put whatever those models happen to gain next into an access-administration screen"*. No
  identity number reaches it, masked or otherwise.
* **`/design-review` is a client-side gate over a real server gate**, the same shape as the viewer
  panel above. It refuses on `canRunDesignWorkshops(user)` and renders an explanatory panel instead;
  the rounds it would otherwise read come from the rating API, whose `access_for` refuses any role
  outside the set server-side (see the rating-ledger table). Un-hiding it in devtools yields
  refusals, not data.

**What I did NOT do here:** I did not exercise these routes against the running API, and I did not
run `test_design_workshop_access_gate.py` or the decide-guard module. This is a source review of a
door the first draft omitted entirely, not an end-to-end demonstration.

### Location provenance — CLEAN
Every added read in the diff names **subject** columns:
`{"subjectLatitude": {"not": None}, "subjectLongitude": {"not": None}}` in `map_points.py` and
`{"subjectLatitude": {"not": None}, "district": {"not": None}}` in
`design_workshops.attach_district_anchors`. No `latitude`/`longitude`/`altitude`/`accuracy`/
`capturedAt`/`placeName` reaches a subject-address position in any added line. The Artisan
reference lambda names `address` explicitly in the *refused* group with the Kharagpur-vs-Barpali
scar written out beside it. The new `REFERENCED_BY_A_RECORD` predicate narrows what *teaches* an
anchor and widens nothing.

### Media entitlement — CLEAN
* `design_workshops.media_resolver` takes `viewer` as a **keyword with no default** and
  AND-composes `owned_or_granted_where(user, owner_field="uploadedById")` under the id list, so a
  pasted stranger's media id resolves to nothing (returned as `withheld`, indistinguishable from
  deleted).
* `_reference_photos` returns **an id and a caption and nothing else** — no URL, no `objectKey`.
  Both are in `records._MEDIA_TAKEABLE_KEYS` and are dropped by `_redact_sensitive` for anyone
  outside the file's entitlement. A media *id* is already handed to every signed-in account by
  `GET /api/media`, so nothing widens.
* The scanner's media branch uses `record.url ?? href` and lands on the media list when the server
  withheld the URL — it does not synthesise one.
* Two `with_id_tiebreak` additions to media paging are correctness fixes (offset paging over tied
  `createdAt` was losing and repeating rows), not entitlement changes.

### Secrets — CLEAN, and the ship-blocker fix holds
* `git diff 144e046` scanned for `AKIA|ASIA|-----BEGIN|ghp_|github_pat_|xox[baprs]-|sk-…|eyJhbGciOi|
  postgres(ql)://|supabase` — the only hits are **documentation naming secret names**
  (`docs/CI.md`'s `EC2_SSH_KEY` and `SUPABASE_DATABASE_URL` rows) and a test literal
  `password="not-the-password"`. No credential material is committed.
* **The `vercel-ci-setup.mjs` fix holds.** I executed its parser rather than reading it:

  | input | result |
  |---|---|
  | `../sibling` | `null` |
  | `a/../b` | `null` |
  | `owner/..` | `null` |
  | `./x` | `null` |
  | `owner/n/../../evil` | `null` |
  | `..%2Fx` | `null` |
  | `%2e%2e/x` | `null` |
  | `owner/name` | `owner/name` |
  | `https://github.com/o/n.git` | `o/n` |
  | `git@github.com:o/n.git` | `o/n` |

  The slug is derived from `GITHUB_REPOSITORY` or `git remote get-url origin`, everything it will
  touch is printed before the first API call, a non-TTY run refuses without `--yes`, and the token
  is never printed. The hard-coded wrong-repository literal is gone.
* `docs/tools/check-docs.mjs` grew 1,245 lines and makes **no network call** — its only external
  calls are `execSync("git ls-files")`, `execSync("git ls-files -z")` and
  `execSync("git remote get-url origin")`, all `cwd: REPO`. The `https://` strings in it are inside
  generated documentation text.
* No new cleartext host. `network_security_config.xml` only annotates the already-removed EC2
  origin and now labels it as the *sibling product's* IP.
* **Android logging: one line was added, and it carries nothing sensitive.** The first draft of this
  document reported **zero** here, which was false — the grep answers two lines, and the second is a
  real call. Re-measured:

  ```
  $ git diff 144e046 -- android/ | grep -E "^\+" | grep -E "Log\.[dveiw]|println"
  +     * Logged as well as recorded, because a field export has no other channel: `Log.e` is what an
  +                Log.e(
  ```

  The first is KDoc prose; the second is a genuine added call, in
  `android/app/src/main/java/com/designprototype/workshop/report/PdfWriter.kt::renderPdf` — the
  `pageCountDisagreement` branch, beside `document.writeTo(out)`. **Its payload is two integers and a
  sentence** — `"the drawing pass produced $pageNo pages and the measuring pass measured
  $totalPages…"` — so no PII, no token, no record id, and no workshop id. It is the handset twin of
  the `logger.error` at the end of `report_pdf.build`, which its own KDoc says is the point.
  **Judgement unchanged, measurement corrected.** The other half of the original claim does hold:
  added lines containing `Bearer ` or `http://` across the whole Android diff are **0**, re-measured
  on the repair pass.
* **Android identity sweep: three added `.kt` lines and the bundled schema, all benign.** Also
  reported as zero and also wrong. `git diff 144e046 -- 'android/**/*.kt' | grep -E "^\+" | grep -icE
  "aadhaar|pehchan"` answers **3**, and all three are comments (`DwIdentityOcr` explaining what the
  ML Kit recognizer reads, and a note about a PEHCHAN field with no signal). The regenerated
  `android/app/src/main/assets/design-workshop-schema.json` carries `pehchanCardNumber` **once** and
  `pehchanCardAvailable` **three times** — these are registry FIELD NAMES, which is what a bundled
  schema is for (house rule 7), and `grep -oi aadhaar` over that asset returns **nothing**. So no
  value widened; what widened was the field vocabulary the handset already fetches off the wire.
* The offline outbox persists no credential — `frontend/lib/offline.ts` only *reads* `getToken()`
  to decide whether to start a drain.

### The vendored trace engine — CLEAN

* **Provenance verified, not assumed.** `frontend/lib/trace/UPSTREAM-MANIFEST.txt` records a
  SHA-256 for every vendored file. I recomputed all of them:

  ```
  cd frontend/lib/trace && grep -v '^#' UPSTREAM-MANIFEST.txt | while read -r h p; do ... done
  --- checked 46 entries      # zero mismatches, zero missing files
  ```

  Every one of the 46 vendored files hashes exactly to its recorded value, so nothing was edited
  after the copy and the code reviewed below is the code that was vendored. `comm` against the
  tree shows the manifest covers `engine/**` and `worker/**` completely; the only two `.ts` files
  outside it are `traceClient.ts` and `spawnTraceWorker.ts`, this repository's own adapters, which
  I reviewed as first-party code.
* **No network, no storage, no eval.** `grep -rn` over the 48 `.ts` files of `frontend/lib/trace`
  (50 files in total, 17,744 lines) for
  `fetch(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|document.cookie|localStorage|
  sessionStorage|indexedDB|eval(|new Function|importScripts|navigator.` returns **nothing**. The
  same sweep over `frontend/components/sketches/` returns only three `await import()` lines for the
  engine's own lazy chunks.
* **`unthrottledTimers.ts` is not what its name suggests.** It replaces `setTimeout`/`clearTimeout`
  *inside the worker global only* (`installUnthrottledTimers()` is called from exactly one place,
  `worker/trace.worker.ts:43`), reroutes only **zero-delay** calls through a `MessageChannel`, hands
  any real delay and any string handler straight to the platform, and is a no-op where
  `MessageChannel` is absent. No AudioContext, no permission, no clock reimplementation.
* **Bounded computation.** All four `for(;;)` loops in the engine terminate:
  `distance.ts:88` by a monotonically decreasing `top` guarded by `top > 0`;
  `thinning.ts:515` by `if (n >= cap) break` where `cap = out.length >> 1`;
  `boolean2d.ts:200` by `guard++ > fragments.length`; `skeletonTrace.ts:144` by `guard++ > n`.
  Explicit iteration guards also exist in `geometry.ts:403` (`guard++ < 4096`) and
  `svgPathData.ts:208` (`guardLimit = d.length * 4 + 16`).
* **Bounded memory, with the caveat in F3.** The worker's `decode()` refuses non-positive
  dimensions and a buffer shorter than `w * h * 4`; the pipeline downsamples to `workingLongEdge`;
  the caller decodes to at most 4096 px. The gap is that the cap lands *after* the source decode.
* **No unsafe SQL analogue.** The one raw statement in the neighbourhood is
  `design_workshops._reference_photos`, whose two interpolations are both vetted — the column
  against a `_PHOTO_PARENT_COLUMNS` allow-list and `MEASUREMENT_GRID_PURPOSE` against
  `isidentifier()`, with a comment stating binding it as `$2` would be better and why that is
  deferred.

### The new viewer-panel mount is not a client-side-only gate — CLEAN
`frontend/e2e/design-workshop-designer-access-unit.spec.ts` warns that the panel's second mount on
`/design-workshops` is *"a client-side hide over a page a designer is entitled to — the shape
`design-workshop-viewers.spec.ts` warns has shipped twice in this repository."* It is a hide, but it
is a hide over a real server gate: all three routes in
`backend/app/api/routes/design_workshop_viewers.py` take `Depends(require_admin)` —
`GET /eligible-viewers`, `GET /{workshop_id}/viewers` and `PUT /{workshop_id}/viewers`. (The first
draft cited :54/:83/:97, which are the `Depends(require_admin)` parameter lines rather than the
`@router` decorators at :51/:82/:93 — the substance is the same and the routes are now named instead
of numbered.) A designer who un-hides the panel in devtools gets 403s, not a viewer list. The one thing the
client-side gate is load-bearing for is not showing an admin control to a non-admin, which is
cosmetic.

### Batch task assignment — CLEAN
`tasks.py` was refactored from a per-assignee loop to one query. `assert_all_assignable` iterates
**`assignee_ids` (the caller's list)**, not the returned rows, and looks each up with
`by_id.get(assignee_id)` — so an id absent from the result reaches `assignable_or_refuse(assigner,
None)` and 404s. A batch cannot smuggle an unvalidated or non-existent assignee past the rank check.

### XSS / injection on the new surfaces — CLEAN
`grep -rn "dangerouslySetInnerHTML|innerHTML|document.write|eval(|new Function"` over
`frontend/components/{designworkshop,sketches,forms}/` returns one *prose mention* in
`AiVerbReviewDialog.tsx` saying never to use it. The repository's only real sink is
`app/layout.tsx:52`, a static boot constant, unchanged tonight.

---

## What I could not verify

* **No browser run.** I did not start a dev server and did not execute any Playwright spec. Every
  frontend statement above is a source-level reading. This is the same hole the wave brief names as
  the night's largest, and this review does not close it.
* **Backend tests I did run** (reported as measured, not as expected):

  ```
  registry: []          # stage_schema.validate_registry()
  carry:    []          # design_workshops.validate_reference_carry()

  pytest tests/test_design_ratings.py -rf -q
    database: local DSN resolved — database-backed tests WILL run
    858 passed in 451.99s (0:07:31)          exit 0

  pytest tests/test_design_ratings_api.py -rf -q
    1 failed, 25 passed, 3 warnings in 793.43s (0:13:13)     # the 1 is F5
  ```

  Added on the repair pass, because asserting a negative about it was R2's finding:

  ```
  pytest tests/test_design_workshop_gate.py -rf -q
    database: local DSN resolved — database-backed tests WILL run
    20 passed in 274.47s (0:04:34)           exit 0
  ```

  The 858 is the whole collection that module pulls in, not 45 functions — I did not isolate the
  rating cases from it. Still **not** run: `test_review_rating_ledger.py`,
  `test_revision_pii_redaction.py`, `test_permission_matrix.py`, and the access queue's own
  `test_design_workshop_access_gate.py` / decide-guard modules.
* **Android.** Reviewed by diff and grep, not built or run — and the grep itself was wrong twice
  before the repair pass; see the two corrected bullets in *Secrets*.
* **The 50,000-row `barred_emails` ceiling and the 503 queue coupling** are reasoned from source,
  not reproduced.
* **F1 and F2 were not demonstrated against a running API.** I proved each link in the chain
  separately — the grant table has exactly two writers, `users.py` is not one of them,
  `load_workshop_or_404` contains no role predicate, and `generate_report` / `record_device_export`
  add no gate of their own — but I did not sign in as a demoted account and download a report. That
  end-to-end demonstration is what would turn F1 from "every link checks out" into "reproduced", and
  it is the single most valuable follow-up to this review.
* **F4's blast radius is unmeasured.** Whether any 4-to-11 character identity value exists is a
  production-database question; the local compose Postgres is not that data, so I did not run the
  `count(*)` I recommend.
* **The access queue was reviewed from source only.** The section on it was written on the repair
  pass, after the re-review found it missing entirely. No route in it was exercised, and its two test
  modules were not run.
* **The tree moved under me, repeatedly, and that is now the document's main limitation rather than a
  footnote.** F5 was fixed by another wave *during* this review; four citations had drifted by the
  re-review and two of those drifted again by the repair pass; the diff scope grew by 23 insertions
  between two `--shortstat` calls one minute apart; `check-docs.mjs`'s failure count read 309, then
  4, then 2. Every number here is a point-in-time measurement with a clock beside it, and the
  *symbols* are what to trust.

---

## Bottom line

Nothing in the surfaces reviewed above widens identity data, breaks the 404-not-403 rule, leaks a
rating, crosses a provenance coordinate as a subject address, hands out a media URL past its
entitlement, commits a secret, or gives the vendored trace engine a way to reach the network. (Stated
as "tonight's 172 changed files" in the first draft — a file count that was three revisions out of
date by the repair pass. The claim is about the named surfaces, which are enumerated in *Categories
checked and CLEAN*, and it is worth exactly as much as that list is complete: the re-review found one
whole door missing from it, which is now the access-queue section.)

Two things are worth an owner's attention and both are older than tonight:

* **F1** — a demoted designer keeps read, report download **and media export** on every workshop
  they were granted; the media half runs through `records.owned_or_granted_where`'s third clause,
  which resolves a `DesignWorkshopViewer` row into a take-data-out entitlement. Tonight is the first
  time the tree has contained a module (`design_ratings`) that gets the same question right one file
  away from where it is got wrong. One line in `load_workshop_or_404` closes it. **Nothing in the
  test suite pins this** — see *How this document is kept true*.
* **F4** — the identity mask's real threshold is `< 4`, so a malformed short value discloses its
  last four. The documentation was corrected tonight; the code was not. One production `count(*)`
  decides whether that matters.

Everything else in the hunt list came back clean, and the rating ledger in particular is the
strongest-reasoned access-control code in the diff.

---

## How this document is kept true

**It is a DATED SNAPSHOT and it does not self-update.** Unlike the reference documents in this
directory, nothing here is derived from the tree by a script, so nothing will notice when it goes
stale. It describes the tree between `144e046` and the working tree as it stood on the night of
2026-08-22, and it is already known to be behind in one place: **F5 was fixed by another wave while
this file was being written.** Read it as a record of a review, not as a description of today.

What to re-run to check any claim in it — every number above came from one of these:

```bash
git diff 144e046 --stat                       # the 172 / 26,765 / 2,008 in the scope line
cd frontend/lib/trace && grep -v '^#' UPSTREAM-MANIFEST.txt \
  | while read -r h p; do [ "$(sha256sum "$p" | cut -d' ' -f1)" = "$h" ] || echo "MISMATCH $p"; done
cd backend && PYTHONUTF8=1 .venv/Scripts/python.exe -m pytest tests/test_design_ratings_api.py -rf -q
cd backend && PYTHONUTF8=1 .venv/Scripts/python.exe -c "import app.services.stage_definitions; \
  from app.services.design_workshops import validate_reference_carry; \
  from app.services.stage_schema import validate_registry; \
  print(validate_registry()); print(validate_reference_carry())"
```

**F1 is not pinned by a test. F2 IS — and the first draft of this section asserted the opposite
about the one module it had not run, which is the exact shape of error this repository fails reviews
for.** Corrected, each half measured:

* **F2 is pinned.** `backend/tests/test_design_workshop_gate.py` carries
  `("POST", "/{workshop_id}/exports")` inside its module-level `UNGATED_WRITES` set, and
  `test_the_designer_gate_still_stands_where_this_docstring_says_it_does` asserts both
  `(len(writes), len(gated), len(via_verb_gate)) == (22, 11, 5)` and `ungated == UNGATED_WRITES`,
  the second with the message *"the set of writes OUTSIDE the designer set changed. A new one is a
  permission decision, not a refactor"*. **Gating F2's route fails that test until the set and
  `_require_designer`'s docstring are updated in the same commit** — which is precisely the rot
  detector this section originally claimed did not exist. That test is also the thing that will tell
  a later reader F2 is closed, so it is F2's pin and this paragraph is not.

  Measured on the repair pass, because the first draft named this module as one it had **not** run:

  ```
  cd backend && DATABASE_URL=<compose DSN> PYTHONUTF8=1 .venv/Scripts/python.exe \
      -m pytest tests/test_design_workshop_gate.py -rf -q
    database: local DSN resolved — database-backed tests WILL run
    20 passed in 274.47s (0:04:34)          exit 0
  ```

* **F1 is genuinely not pinned, and this was checked rather than assumed.** `load_workshop_or_404`
  appears in `backend/tests/` only in `test_design_workshop_gate.py`'s two comments about F2's route
  and in one `test_permission_matrix.py` docstring explaining what "reached" means; no test asserts
  anything about the presence or absence of a role predicate in it. Nor would adding one break a
  test by accident: no test can set up a *granted researcher* through the API, because
  `_assert_every_id_may_be_granted` answers 422 to exactly that
  (`test_design_workshop_viewers.py::test_an_ineligible_account_is_a_422_that_names_it`
  parametrises `researcher`/`professor`/`suspended`/`unlisted`). So if somebody closes F1, nothing
  here changes and nothing tells a later reader.

**When a finding is fixed, strike it here in the same commit** and say which change closed it; a
security review whose closed findings still read as open is worse than no review, because it spends
the next reader's attention on work already done. **And do not assert a negative about a module you
have not run** — the correction above cost less than five minutes of pytest.

**Known gate failure, deliberately not fixed here — and the total, re-measured rather than
inherited.** The figure this section first carried was worthless: the first draft reported **309
`FAIL` lines**, the re-review measured **4**, and on the repair pass at 02:57 on 2026-08-23
`node docs/tools/check-docs.mjs` reports exactly **two**:

```
FAIL  docs/REPO_FACTS.md is out of date — run `node docs/tools/check-docs.mjs --write` ON A CLEAN TREE
FAIL  docs/README.md does not list SECURITY-REVIEW-2026-08-22.md
```

Three numbers for one command in six hours, because `check-docs.mjs` itself was being edited by a
concurrent wave the whole time. **Do not quote any of them; run it.** The first is pre-existing and
not this document's (proven by moving this file out of `docs/` and re-running, where it still fires
alone). The second is this document's and is still open.

**Why the second is still open, stated as a decision and not an oversight.** Closing it means adding
one row to `docs/README.md`'s document table. That file is **not in this unit's file list**, and at
02:57 it is *already carrying another wave's uncommitted modifications* (`git status --porcelain
docs/README.md` answers ` M`) — so editing it now risks writing over work in progress that is not
mine, to fix a documentation index row. `.github/workflows/checks.yml` runs `check-docs.mjs` in CI, so
**the docs gate stays red until the owner adds that row**, and that is the honest state rather than a
hidden one. It is one line in the table beside the `SECURITY.md` row.

The other requirement the gate raises against a document like this one — that it explain how it is
kept true — **is** satisfied, by this section.

---

## Adversarial re-review of this document — 2026-08-23

**Who wrote this section and why it is here.** A second reviewer was asked to break the review
above, not to agree with it, and to report every finding with `file:line`. The verification work in
it is unusually good — 46 manifest hashes, the router parse, the four `for(;;)` loops, the `< 4`
threshold and the 26 test names all reproduce exactly (see the confirmation list at the end). **Five**
things do not, R1 to R5 below. They are recorded here rather than in a separate file so that the next
reader meets the correction beside the claim.

> **ALL FIVE ARE NOW CLOSED, and this section is kept as the RECORD of a correction, not as a list of
> open work.** Each was re-verified against the tree before being acted on — all five reproduced, none
> was a mistaken reviewer — and each correction has been folded into the body above. See
> *Repair pass — 2026-08-23* at the end of this file for the item-by-item map of where each fix
> landed. Two things it could NOT close are named there: the `docs/README.md` index row (outside this
> unit's boundary, and that file is carrying another wave's uncommitted changes), and the end-to-end
> demonstration of F1/F2 against a running API.

### R1 — A measured claim in the CLEAN list is false: Android DID gain a log line tonight

The Secrets section states: *"Nothing new logged. `grep` over the whole Android diff for added
`Log.*`/`println` lines returns **zero**; same for added lines containing `Bearer `, `http://`,
`aadhaar` or `pehchan`."* Re-run:

```
$ git diff 144e046 -- android/ | grep -E "^\+" | grep -E "Log\.[dveiw]|println"
+     * Logged as well as recorded, because a field export has no other channel: `Log.e` is what an
+                Log.e(
```

The second is a real added call, in
`android/app/src/main/java/com/designprototype/workshop/report/PdfWriter.kt` (the
`pageCountDisagreement` branch added to `renderPdf`, beside `document.writeTo(out)`). The identity
sweep is wrong too: three added `.kt` lines contain `Aadhaar`/`PEHCHAN` (all three are comments) and
the regenerated `android/app/src/main/assets/design-workshop-schema.json` carries
`pehchanCardNumber` once and `pehchanCardAvailable` three times.

**The conclusion survives; the measurement does not.** What that `Log.e` writes is two page counts
and a sentence — no PII, no token, no id — and the asset's `pehchan*` keys are the registry field
names, which is what a bundled schema is. `grep -oi aadhaar` over that asset returns nothing. So
nothing widened. But `PdfWriter.kt`'s mtime is 21:27 and this document was last written at 23:41:
the line was there to be found, and "zero" was reported for a grep that answers two. House rule 1 is
about exactly this.

### R2 — F2 *is* pinned by a test, and the claim that it is not was made about the one module this review did not run

*How this document is kept true* states: *"The findings are NOT pinned by tests … F1 and F2 are live
behaviours no test asserts against — if somebody adds `can_run_design_workshops` to
`load_workshop_or_404`, nothing in this document will change and nothing will tell a later reader."*

That is true of F1. It is false of F2. `backend/tests/test_design_workshop_gate.py:189` carries
`("POST", "/{workshop_id}/exports")` inside `UNGATED_WRITES`, and
`test_the_designer_gate_still_stands_where_this_docstring_says_it_does` (:219) asserts
`ungated == UNGATED_WRITES` (:252) and `(len(writes), len(gated), len(via_verb_gate)) == (22, 11, 5)`
(:248), with a failure message that reads *"the set of writes OUTSIDE the designer set changed. A new
one is a permission decision, not a refactor"*. Gate F2's route and that test fails until the
docstring and the set are updated in the same commit — which is precisely the rot detector the
paragraph says does not exist.

Measured, because this review said it had not run the module:

```
$ pytest tests/test_design_workshop_gate.py -rf        # local compose Postgres
  20 passed in 865.37s (0:14:25)                       exit 0
```

Asserting a negative about the one module you did not run, and naming it two paragraphs earlier as
the module that would independently confirm F2, is the shape of error this repository fails reviews
for.

### R3 — F1's evidence names data that needs no grant, and misses the export path that does

F1's chain — the grant table has two writers, `users.py` is not one, `load_workshop_or_404` has no
role predicate, `generate_report` adds no gate — reproduces line for line, and the finding stands.
Its **impact paragraph** points at the wrong data, in both directions.

* **It overstates.** *"What they get. Everything … the whole participant roster"*, itemised as
  `address` (:469), `email` (:473), `phone` (:474) and the masked card (:404). Every one of those is
  already readable by **any signed-in account holding no grant at all**:
  `backend/app/services/records.py::viewable_where` (:798–:815) returns `{}` — *"Row filter for
  READING the repository: everything, for every signed-in account"* — and
  `backend/app/api/routes/artisans.py` gates `GET ""` (:281) and `GET "/{artisan_id}"` (:399) on
  `Depends(get_current_user)` alone. A demoted RESEARCHER reads that roster through `/api/artisans`
  whether the stale grant exists or not, so it is not what the stale grant buys.
* **It misses the worse half.** What the stale grant uniquely buys includes a **take-data-out**
  path. `records.owned_or_granted_where` (:818) adds, for every account below PROFESSOR, a third
  clause built from `_design_workshop_media_branches(uid)` — reached by
  `if owner_field == "uploadedById"` — whose whole subject is *"THE RECORDINGS OF A DESIGN WORKSHOP
  THIS ACCOUNT MAY OPEN. A `DesignWorkshopViewer` row …"* (:838). So a demoted grantee keeps
  downloading the workshop's media on the transcript, annexure and export paths, not merely reading
  stage text. This document cites that function in its media-CLEAN section and never connects it to
  F1.

The owner-facing sentence should be "a stale grant keeps stage content and media exports", not "keeps
the participant roster's PII" — the second is true of every account that can sign in.

### R4 — The night's newest door is not in this review at all

`grep -ni "design_workshop_access\|access-request\|design-review"` over this document returns
nothing. Tonight added, untracked: `backend/app/api/routes/design_workshop_access.py` (146 lines),
`backend/app/services/design_workshop_access.py` (698),
`backend/app/schemas/design_workshop_access.py`, its migration, 748 lines of tests, and
`frontend/app/(protected)/design-review/page.tsx`. `POST /design-workshop-access/requests` is
`Depends(get_current_user)` and, in `api/router.py`'s own words, *"the caller … is BY DEFINITION
somebody `load_workshop_or_404` turns away"* — and `POST /requests/{id}/decide` **mints the
`DesignWorkshopViewer` row F1 is entirely about**. A review whose headline is "Nothing tonight opened
a hole", and whose 404-not-403 section enumerates "tonight's three new doors", cannot be silent about
the fourth.

**Read as an attacker, it holds** — which is why this is a coverage finding and not a hole.
`file_request` (:335) raises `ScannedCodeRefused` only from the code check that *precedes every
database read*, returns silently for an unstorable id (:365), for a missing or soft-deleted workshop
(:372) and for somebody already in (:381), and writes through
`create_many(..., skip_duplicates=True)` (:400) against a unique index, so an authenticated flood
cannot grow the queue past one row per (workshop, user); the route answers one fixed 202 sentence in
every branch. `decide` (:613) grants only through `replace_viewers` (:660), so
`_assert_every_id_may_be_granted`'s 422 still stands between a researcher and a grant. But "I checked
it and it holds" is a different statement from silence, and only the first is a review.

### R5 — Minor: three citations no longer land, and the scope line is stale

Substance is right in each case; the pointer is not. `backend/app/core/config.py:104`
(`jwt_expires_minutes`) is now **:165** — it was correct at `HEAD` 72bb087, but another wave inserted
~61 lines above it at 23:21, before this file's 23:41 write. `records.mask_identity_number` ":147" is
**:152**, and the `return mask_aadhaar(value)` cited as ":155" is **:160**.
`StageReferenceField.tsx:1929` is **:1949**. `design_workshop_viewers.py` :54/:83/:97 are the
`Depends(require_admin)` lines rather than the decorators (:51/:82/:93). And the opening scope line is
already false: `git diff 144e046 --stat` now reports **197 files, 28,859 insertions, 2,233
deletions**. The point-in-time caveat is stated at the end; the numbers are stated at the front with
no timestamp beside them.

Two smaller ones. The headline says *"Four findings follow"* while five are ranked. And
`node docs/tools/check-docs.mjs` no longer reports 309 FAIL lines — it reports **4**
(`docs/REPO_FACTS.md is out of date`; two about another wave's `TESTING-E2E-LOCAL.md`; and
`docs/README.md does not list SECURITY-REVIEW-2026-08-22.md`). That last is this file's, it is still
open, and `.github/workflows/checks.yml:528` runs `check-docs.mjs` **in CI** — so as landed, this
document leaves the docs gate red until somebody adds one row to `docs/README.md`. Declining to edit
a file outside a stated boundary was the right call; the CI consequence is the owner's to close.

### F5 is now closed — measured, not assumed

This document could not re-run the module after the concurrent fix. Re-run on 2026-08-23:

```
$ pytest tests/test_design_ratings_api.py -k round_trips -rf
  1 passed, 25 deselected in 708.61s (0:11:48)         exit 0
```

A first attempt errored with *"FATAL: the database system is starting up"* — another wave had
restarted `design-workshop-postgres`, which reported `unhealthy` for several minutes. That is the
environment, not the code. **F5's diagnosis was right and the fix works. Strike it.**

### What reproduced exactly, checked against the tree rather than the prose

`load_workshop_or_404`'s clause verbatim; `generate_report`'s `Depends(get_current_user)` and
`record_device_export`'s `for_edit=True`-only gate; the router parse (**42 routes, 22 non-GET**, and
the ungated set is exactly the eleven decorators this document lists); `deps.py`'s SCOPED TOKENS
quote (:507–:510); `stage_definitions.py` :404/:469/:470/:473/:474; `design_workshops.py` :1265 and
the Aadhaar docstring at :4128; `artisan_identity.py:195–197`'s `< 4`; `design_ratings.py:248`
`LEDGER_CLOCK_RESOLUTION` and the `reviewerId`-by-omission at :881;
the single `NOT_FOUND` constant and `_not_found()` helper at the top of
`backend/app/api/routes/design_ratings.py`; all seven named tests present, 26
collected, and the failing assertion at `test_design_ratings_api.py:695`; `_reference_photos`'
`_PHOTO_PARENT_COLUMNS` and `isidentifier()` vetting and its three-column `SELECT`;
`tasks.assert_all_assignable` iterating the caller's list (:189); `require_admin` on all three viewer
routes; `layout.tsx:52`; `decodeToPixels` capping **after** `createImageBitmap(file)` (:152 → :159)
and the 48 MB-versus-67 MB header inconsistency; **all 46 manifest hashes, zero mismatches**; 48
`.ts` / 50 files / 17,744 lines; the network/storage/eval sweep empty; all four `for(;;)` loops at
`distance.ts:88`, `thinning.ts:515`, `boolean2d.ts:200` and `skeletonTrace.ts:144` with the bounds as
described, plus the guards at `geometry.ts:403` and `svgPathData.ts:208`; and the designer-access
spec's "client-side hide" sentence (:46). In `frontend`: `npx tsc --noEmit` exits 0 with no output,
`npx eslint . --max-warnings=0` exits 0.

---

## Repair pass — 2026-08-23, what was changed in response to the re-review

Every finding above was **verified against the tree before being acted on**, because a reviewer can
be wrong. All five reproduced. What changed, and where the correction now lives:

| # | Verdict | Where the fix landed |
|---|---|---|
| **R1** — the "zero added log lines" claim | **Confirmed false, fixed** | *Secrets* now carries the real two-line grep output, names the `Log.e` in `PdfWriter.kt::renderPdf`, quotes its payload (`$pageNo` / `$totalPages` — two integers), and adds a second bullet for the three `.kt` comment hits and the bundled schema's `pehchanCardNumber` ×1 / `pehchanCardAvailable` ×3. Judgement unchanged: nothing widened. |
| **R2** — "F2 is not pinned" | **Confirmed false, fixed** | *How this document is kept true* now splits the claim: F2's pin is named (`test_design_workshop_gate.py::test_the_designer_gate_still_stands_where_this_docstring_says_it_does`), and F1's unpinned state is now *checked* rather than asserted. F2's own section says it is pinned. |
| **R3** — F1's impact named the wrong data | **Confirmed, fixed** | F1's "What they get" is rewritten: the artisan-PII itemisation is explicitly retracted (`records.viewable_where` returns `{}`; `artisans.list_artisans` / `get_artisan` are `get_current_user` alone), and the missing half — `records.owned_or_granted_where`'s media clause via `_design_workshop_media_branches` — is added. F1's heading and the *Bottom line* bullet now say **media export**. |
| **R4** — the access queue was absent | **Confirmed, fixed** | New section *The design-workshop access queue — CLEAN, and MISSING from the first draft*, reviewed from source by the repairing agent (not inherited): the seven silent outcomes, `ScannedCodeRefused` sitting above the first DB read, the `@@unique` + `skip_duplicates` flood bound, `decide` → `replace_viewers` → `_assert_every_id_may_be_granted`, and `request_payload`'s hand projection. The 404 section's "three new doors" is now **four**. |
| **R5** — citation drift, stale scope, "Four findings" | **Confirmed, fixed** | Scope line now carries three timestamped measurements and says to re-run the command. "Four" → **Five**. The four drifted citations are re-pinned **by symbol**; a note after the headline explains why. The `check-docs.mjs` figure is re-measured (309 → 4 → **2**). |

**Two things a reader should not mistake for closed.**

* **The docs gate is still red on one row.** `docs/README.md` does not list this file, and
  `.github/workflows/checks.yml` runs `check-docs.mjs` in CI. Not fixed here: that file is outside
  this unit's boundary *and* it is carrying another wave's uncommitted changes as of 02:57, so an edit
  would risk overwriting work in progress to add an index row. **Owner: one row in the document
  table.** (`docs/REPO_FACTS.md is out of date`, the other FAIL, is pre-existing and not this file's.)
* **F1 and F2 are still not demonstrated end-to-end.** The repair pass ran
  `test_design_workshop_gate.py` (20 passed, exit 0) and re-verified every link in F1's chain in the
  source, but nobody has yet signed in as a demoted account and downloaded a report. That remains the
  single most valuable follow-up, exactly as the original review said.

**Measured on the repair pass, reported as run:**

```
cd backend && DATABASE_URL=<compose DSN> PYTHONUTF8=1 .venv/Scripts/python.exe \
    -m pytest tests/test_design_workshop_gate.py -rf -q
  20 passed in 274.47s (0:04:34)        exit 0

cd backend && PYTHONUTF8=1 .venv/Scripts/python.exe -c "import app.services.stage_definitions; ..."
  []                                    # validate_registry()          exit 0
  []                                    # validate_reference_carry()

cd frontend/lib/trace && (manifest sha256 sweep)
  46 entries, zero mismatches           # re-verified, not inherited

cd frontend && npx tsc --noEmit
  no output                             exit 0

cd frontend && npx eslint . --max-warnings=0
  exit 0                                # one jsx-ast-utils JSXEmptyExpression notice, not a finding

node docs/tools/check-docs.mjs
  2 FAIL lines (REPO_FACTS staleness; docs/README.md missing this file)
```

**One thing the repair pass learned the hard way, recorded because it is useful.** The docs gate now
has a **citation-drift checker**, and on the first run it caught a third `FAIL` line that was mine:
the R1 correction had abbreviated `PdfWriter.kt`'s path with an ellipsis in the middle instead of
writing the whole `android/app/src/main/java/...` prefix out, and the checker resolves every path it
finds against the tree. Full paths and symbol names pass it; abbreviated ones do not — and writing the
note about that mistake reproduced it once more, because the checker reads this file too. That checker is the mechanical answer to R5, and it is
cheaper than a reviewer.

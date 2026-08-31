# TODO — 2026-08-30 work order

Status key: `[ ]` not started · `[~]` in progress · `[x]` done + gates green · `[!]` blocked

**Production safety:** everything below is local and uncommitted. `main` is at `bf601bd`, which is what
production runs. Nothing merges until its gates are green.

---

## 0. Live defects found during recon — fix before anything cosmetic

**FINAL VERIFICATION, 2026-08-31, run SERIALLY on the settled tree — all 21 lanes down:**

| gate | result |
|---|---|
| backend `ruff check app tests scripts` | All checks passed |
| backend `pytest tests/ -q` | **5710 passed, 5 skipped, 0 failed** (24m11s) |
| frontend `tsc --noEmit` | 0 |
| frontend `eslint . --max-warnings=0` | 0 |
| frontend `npm run test:unit` | **1737 passed**, 1 skipped |
| android `:app:compileDebugKotlin --rerun-tasks` | BUILD SUCCESSFUL |
| android `:app:testDebugUnitTest` | **2793 tests, 1 failed** — `DwWorkshopCodesTest:280`, the documented JDK-19+ `Double.toString` case, not a code defect |

Change set: **225 paths — 166 tracked files changed, 59 new, ~18,900 insertions.** Nothing is
committed; `main` is still `bf601bd`, which is what production runs.

**The one real failure the final run caught, and how it was closed.** The tripwire in
`test_entry_provenance_readers.py` fired on `export.py` and `search.py` — two new readers of
`DwStageEntry` that no lane had classified. Both are EXEMPT on Reader 9's grounds (they serve values
and matches and attribute nothing), and both are now FENCED as "Readers 10 and 11" rather than merely
named. Building that fence exposed a second, quieter problem: the file's existing source reader blanks
string literals, and `entry_provenance.py` — the module the stamp belongs to — carries
`fieldProvenance` five times with **every one inside a string**. So a fence on the blanked reading
would have caught `entry.fieldProvenance` and sailed past `{"fieldProvenance": True}` in a Prisma
`select`, which is the likelier spelling and the one that actually pulls the column. The fence now
reads raw source, and that was verified against `entry_provenance.py` rather than assumed.

- [x] **D1 · DATA LOSS · web.** `ProcessForm.tsx:806-813` blocks the save when the artisan/product
      picker is empty, and `:831` returns before `saveOrQueue` at `:869`. Offline the picker IS empty,
      so the process record and its step media die with the tab. Android closed this at
      `MainActivity.kt:10129`. Rule R2b, `DROPDOWN_DESIGN.md:786-800`.
- [ ] **D2 · questionnaire create is not transactional.** `questionnaire_forms.py:938-970` writes
      questionnaire → sections → questions on separate awaits; a mid-loop failure leaves an orphan
      row and returns 500.
- [ ] **D3 · misleading 403.** `questionnaire_forms.py:188-191` says "Designer access or above"; the
      gate is a SET `{DESIGNER, ADMIN, MASTER_ADMIN}`, so a PROFESSOR is refused and told they lack a
      rank they outrank.
- [x] **D4 · pickers assert non-existence from a read that may have failed** — 9 web sites.
- [x] **D5 · dead band** — 6 callers fetch 100 rows into a control that draws 80.

## 1. UI copy brevity  ✅ saved to memory
- [x] Standing instruction recorded as a memory; applies to both clients.

## 2. Login screen + red asterisks
- [x] Web login: consent block → one line, `terms and conditions` underlined + linked.
- [x] New public `/terms` page; the recording notice moves there as clause 10, verbatim from the server.
- [x] `RequiredMark` primitive + 10 web asterisk sites now red (dark-mode red included).
- [x] Android: red asterisk — 18 files, 6 new tests, compile + 2713 unit tests green.
- [ ] Android: consent door → one line; in-app Terms screen for parity.
- [ ] `media/page.tsx:591` hardcodes `label="Linked record type *"` — must become `required`.

## 3. Audit — scalability / ergonomics / concurrency / offline
- [x] `docs/AUDIT-2026-08-30.md` — written, ranked, every finding cited.

## 4. Offline dropdowns
- [x] Answered: the four classes, and the access-list-vs-register ruling that decides caching.
- [ ] Web: IndexedDB register cache mirroring `DwReferenceStore`'s contract (4th store).
- [ ] Web: dangling-FK "Re-pick it" + unfile sentinel (Android has both, web has neither).
- [ ] Android: bundle the state list (cheapest open parity gap).
- [ ] Android: `DropdownField` has no `emptyMessage` — 5 required pickers fall through to a generic line.
- [ ] Android: surface `cachedListLine` for the 4 registers (`loadCachedRegister` discards `fetchedAt`).
- [ ] Honour `GET /reference/address`'s `version` — the one invalidation signal in the API, unused.

## 5. Workshop type + name dropdowns  — vocabulary decided: scheme-based
- [x] Registry enum `WORKSHOP_KIND`: Design & Prototype Development · Skill Upgradation ·
      Design Intervention · Cluster Development · Exposure / Exhibition · Other.
- [x] `DesignWorkshop.workshopKind` column + migration + stage-1 field + create/patch/list/filter.
- [x] Web: "Type of workshop" on the create form + a type filter on the list, with a compiled-in
      offline floor under the served vocabulary.
- [ ] Android: "Type of workshop" dropdown (parity).
- [ ] Web + Android: "Name of workshop" as a CREATABLE combo (offers existing, accepts new) —
      answers `stageFieldRoles.ts:442`'s objection without reintroducing it.

## 6. Questionnaire
- [ ] Reproduce the create error against a live local backend and fix the actual cause.
- [ ] Seed a default shared questionnaire — `isShared` defaults false and NOTHING sets it, so the
      list and the attach dropdown are empty out of the box.
- [ ] Web list: `activeOnly` toggle + "Deactivated" chip (Android has both; the web cannot reach a
      past questionnaire at all).
- [ ] Editing: make the affordance and the refusal honest on both the seeded and the custom form.
- [ ] D2 + D3 above.

## 7. Voice notes → dictation API, rich text, edited flag
- [ ] Stream a questionnaire voice note to the dictation API for an immediate transcript.
- [ ] Rich text box on the questionnaire answer (today it is a plain `TextArea`).
- [ ] Refined + translated markdown replaces the quick transcript when the queue delivers it —
      and must NOT overwrite a designer's edit.
- [ ] `edited` / `not edited` flag — does not exist in any layer today; needs a column.
- [ ] Markdown formatting helper + copy + download, together, on one component.

## 8. Stage 4 and stage 11
- [x] Stage 4: `lostCraftPhotos` — ceiling of 25 and deliberately NO floor ("upto 25").
- [x] Stage 11: `isTentative` BOOL declared on the sketch row.
- [ ] Tentative-first ORDERING at the six surfaces that list sketches.
- [ ] Both clients + the report.

## 9. Auth
- [ ] Identity resolver: empanelment number | email | phone (no country code).
- [ ] `User.phone`; uniqueness behind every candidate identifier (only email has one today).
- [ ] Empanelment number mandatory on the profile, with the grace path for existing profiles.
- [ ] First login: set password + confirm.
- [ ] Eye toggle on all password fields — 3 web fields and 3 Android fields still lack one.
- [ ] Admin reset: single-use expiring token, link shown once with Copy, behind a transport
      interface so email is later a config change.
- [x] cxa-cms reuse assessed: its token minting is reusable; its "emailed link" is not — `canSendEmail`
      is a hardcoded `false` there too.
- [x] cxa-cms landing page: logos top-left and top-right. ⚠ visible only at ≥1280px — the
      centred header pill leaves no clear air below that. Revisit if they must show on phones.

## 11. Designers participate in workshops, they do not create them  (added mid-session)
- [ ] Verify the current gate. `assert_can_create_design_workshops` appears to refuse a DESIGNER
      already — establish exactly what each client offers before changing anything.
- [ ] A designer picks one of the workshops they have ACCESS to, and reports into it.
- [ ] Offline: let them create a local workshop, then LINK it to an accessible workshop when the
      connection returns. This is a new outbox shape — a local record that adopts a remote parent.
- [ ] Being added to a workshop must automatically carry the right to add information and media
      through the report. No second grant.

## 12. Questionnaire kinds  (added mid-session)
- [ ] Several questionnaires per workshop.
- [ ] Distinguish a workshop questionnaire from a MARKET SURVEY interview.
- [ ] The kind decides which stage the questionnaire and its transcripts map to in the report.

## 13. Feedback  (added mid-session)
- [ ] Make the feedback flow completely functional; more detailed and exhaustive.
- [ ] Dictate button in every feedback text box.
- [ ] A grievances / suggestions / recommendations redressal card in the settings hub.
- [ ] Document incoming feedback properly so it is usable as research data.

## 14. cxa-cms accessibility menu  ✅ SHIPPED TO PRODUCTION 2026-08-30
- [x] Root cause: `app/globals.css:2398` `[class~="bg-card"]{position:relative;z-index:45}` compiled
      to the FOOT of the stylesheet (line 8115) after `.absolute` (line 1809) at equal specificity,
      so every popover carrying the bare `bg-card` token was laid out IN FLOW. Header pill measured
      911px → 1159px on open; document 2117px → 2691px from the footer menu. One cause, both symptoms.
- [x] Fix: `:where([class~="bg-card"])` — specificity (0,0,0), a default again, which is what the
      rule's own comment had always claimed it was.
- [x] Nine other components restored by the same line: video PiP, IconPicker's sticky header,
      PlaceSearchBox autocomplete, MapHoverCard, three step-dot sections, a milestone rail dot,
      BeforeAfterSlider's handle. **None has been looked at rendered.**
- [x] Merged as 4f46ccd, Production deploy Ready, verified live in the served CSS.
- [ ] OPTIONAL: `NEXT_PUBLIC_SITE_URL` is Production-only, so EVERY Vercel Preview build on that
      project fails at the app's own guard. Pre-existing; add it to Preview if previews are wanted.

## 15. REGRESSION — resolved
- [x] `frontend/app/login/page.tsx` briefly read as reverted mid-edit; it was a transient state
      while the auth lane held the file. The one-line consent + `/terms` link is present and the
      identifier work is layered on top of it. No repair needed.

## 16. VERIFICATION DEBT — CLEARED 2026-08-31, serially, tree quiet
- [x] backend `ruff check app tests` — All checks passed.
- [x] backend `pytest tests/ -q` — **5583 passed, 5 skipped, 0 failed** (22m07s). The 194 failed /
      98 errors one lane reported WAS pure Postgres contention from ~8 concurrent suites. Proven,
      not assumed.
- [x] frontend `tsc --noEmit` 0 · `eslint . --max-warnings=0` 0 · `test:unit` **1617 passed**.
- [x] android `:app:compileDebugKotlin` BUILD SUCCESSFUL · `:app:testDebugUnitTest` **2722 tests,
      1 failed** — `DwWorkshopCodesTest:280`, the documented JDK-19+ `Double.toString` case.
- [x] `ruff` on `feedback.py` is clean; that lane fixed it before finishing.
- [ ] `git stash@{0} tmp-lint-check` — the tree is now verified whole, so it is safe to drop.
      Left in place because a backup costs nothing and dropping it gains nothing.

## 18. WAVE D — the remaining features  (in flight)
- [~] **Offline workshop creation + adopt.** The owner's clause 3, the one thing verified as NOT
      built: `createLocalDraft` throws and Android's `mayMintLocalWorkshop` refuses, so a designer
      with no signal has nowhere to put a fortnight of fieldwork. The adopt machinery exists.
      Shape: a DESIGNER may mint a LOCAL draft only while the device cannot reach the server, and
      NOTHING changes about who may create one on the server. The sync must take the adopt path,
      never the create path — a designer's create is a 403, and a 403 is not transient, so it would
      park forever behind a Try-again that can only fetch the same refusal.
- [~] **Stage field VALUES searchable.** Decision taken: a generated `searchText` column maintained
      by `save_stage`, NOT `pg_trgm`. Reasons: the extension needs a superuser step the managed
      instance may refuse; a GIN index over `data::text` indexes JSON keys and punctuation as well
      as answers; and the column can hold the RENDERED answers (enum labels, flattened rich text)
      via the flattener that already exists, which is a better search, not just a cheaper one.
      §6.1's objection — a second copy that can drift — is answered structurally: one writer, and a
      test that fails if a second appears.
- [~] Android parity for auth (set-password screen, admin password-link surface, X-Sign-In-Hint)
      and for the questionnaire (two-stage transcript, rich text, edited flag).
- [~] **`mustChangePassword` is consumed by NO screen on either client** — so "set the password on
      first login and confirm it" is NOT met today. The server reports and never refuses; both
      clients must enforce.
- [~] **A correctness bug:** the backend readers of `QuestionnaireResponse.answerText` do not
      flatten rich text. `report_questionnaires.py`, `record_fields.cell()` and
      `questionnaire_consolidation._answer_key` would print `{'blocks': [...]}` into a ministry
      document, pass every emptiness check, and split one artisan's answers into two groups.
- [~] `/export/dataset` gains the design-workshop half, then the archive is renamed back.
- [~] The last two name-combo surfaces and the two pickers that still read a failed list as empty.

## 20. Walkthrough parity  ✅ DONE
- [x] **The ids and order already agreed** — that was worth checking and it came back clean. The web
      declares 22 steps; the handset declares the same 22 ids in the same order, plus one more
      (`offline`, a subject a browser cannot teach). So "same number of cards" was exact and the
      missing field block was the ONLY divergence. `WalkthroughStepsTest.kt` already pinned both
      directions and passed.
- [x] `WALKTHROUGH_FIELDS` — the web's 22 `fields[]` arrays, **223 entries**, MACHINE-GENERATED from
      `steps.ts` rather than retyped, so the curly apostrophes, `360°`, ellipses, em-dashes and the
      one escaped quote are byte-exact.
- [x] The card renders a third section — "What the screen asks for" — between "Why this step exists"
      and "Watch out for", matching the web's order. Chips reflow with the reader's font scale; no
      heading when the list is empty (the latent defect the web card has and this one must not copy).
- [x] `backend/tests/test_walkthrough_fields_parity.py` — 5 tests, **verified RED in both
      directions** (a one-word change and a renamed id) and restored. This is what answers the
      overruled comment's "copy written from copy" objection.
- [x] The overruled comment is rewritten, not deleted: it quotes its own argument, records the
      owner's request, says which implementation it defeated and which it did not, and names the test.
- [ ] A THIRD copy of these lists exists in `docs/WALKTHROUGH.md`, guarded only for ids and arc
      order, not for the field strings. Widening that guard is a frontend decision, not taken.

## 19. DONE WHILE WAVE D RAN
- [x] **Terms drift guard** — `backend/tests/test_terms_clause_parity.py`. The nine clauses exist
      twice (web TSX + Kotlin `TERMS_CLAUSES`) because the handset is used with no signal and a link
      to an unloadable page is worse than no link. Nothing could tell you when they diverged. Five
      tests, in the `test_role_ladder_parity` house pattern; **verified to FAIL on a one-word
      change** and to name the clause. Also pins that neither file hard-codes the served recording
      notice.
- [x] Dev database: deleted 118 PENDING `AccessRoster` rows on reserved test domains. The queue caps
      at 500 and had reached 499 last night, which makes the auth gate answer 503 instead of 403 and
      reads as ~6 test failures that are not defects.
- [x] Removed a stray `copy.py` in the shared scratchpad that shadowed the stdlib module and broke
      any Python run rooted there.
- [x] Deleted `components/designers/storedAddress.ts` — a duplicate rich-text read boundary whose own
      header set the condition for its own deletion ("when the shared module grows one, delete this
      and import that"). `plainFromStoredRichText` now exists and the two bodies were
      character-for-character identical, so nothing renders differently. tsc 0, 22 specs pass.
- [x] Recorded finding **A30-11** in `docs/ENVIRONMENT.md`: on a DIRECT endpoint `connection_limit=10`
      is an INCREASE, not a cap, because `DATABASE_USE_TRANSACTION_POOLER` defaults true and matches
      no hostname. Prisma would otherwise size from CPU count (5 on 2 vCPU). A one-line deployment
      decision, now written where an operator will look.
- [!] `docs/tools/check-docs.mjs` reads every GIT-TRACKED file, so an uncommitted DELETION crashes it
      with ENOENT. Staging the deletion fixes it. Same class as the REPO_FACTS rule — the checker
      needs a committed tree.
- [ ] `check-docs.mjs` reports 10 remaining problems, ALL in `docs/AUDIT-2026-08-30.md`: 8 citations
      whose line pins drifted as lanes moved the code, a missing "How this document is kept true"
      section, and a missing `docs/README.md` row. **Deliberately deferred until wave D lands** —
      re-pinning lines while five lanes are still moving code guarantees re-drift. The checker's own
      advice is to name the symbol instead of the line, which is the durable fix.

## 17. FOR THE OWNER TO ACT ON  (not code)
- [ ] **PRODUCTION: run `prisma migrate deploy` on the EC2 backend.** Migration
      `20260828093000_shared_default_questionnaire` is almost certainly unapplied there, which is
      what breaks BOTH questionnaire create and the questionnaire list with a 500 DataError.
      Reproduced by dropping the column locally. This needs no code deploy.
- [ ] **44 designer profiles share duplicate empanelment numbers.** They were left NULL so the new
      unique index could apply; none of those 44 can sign in by empanelment number until an admin
      gives them distinct ones. The SQL that finds them is in the migration header.

## 10. View Data + search for design-workshop fields and reports
- [ ] View Data covers ZERO design-workshop data today — `designWorkshop` appears nowhere in
      `data_browser.py`, `export.py`, `datasets.py`, `record_fields.py`, `xlsx_report.py`.
- [ ] Stage answers browsable, searchable, filterable, exportable.
- [ ] Reports: `DwReportExport.storageKey` is written and read by NOTHING, so a past report is
      listed and cannot be re-downloaded.
- [ ] Search: 5 buckets today (artisans, workshops, products, tools, media) — no design workshops.
- [ ] **ACCESS, settled by the owner 2026-08-30:** a PROFESSOR may VIEW design-workshop data;
      ADMIN and MASTER ADMIN may view AND download/export it. That is a real split — the view gate
      and the export gate are different predicates — and it is narrower than `/data`'s existing
      single `require_dataset_downloader` gate, which today governs both.
